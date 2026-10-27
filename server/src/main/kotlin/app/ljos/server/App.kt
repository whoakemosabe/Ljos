package app.ljos.server

import app.ljos.data.Spot
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/** A saved place: a hotel lobby, or the default when a phone hasn't shared its location. */
data class SavedPlace(val id: String, val name: String, val lat: Double, val lon: Double, val tz: String, val title: String?)

object Places {
    private val ID = Regex("^[a-z0-9-]{1,40}$")

    fun parse(json: String): Map<String, SavedPlace> {
        val arr = JSONArray(json)
        val out = LinkedHashMap<String, SavedPlace>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            require(ID.matches(id)) { "bad place id: $id" }
            ZoneId.of(o.getString("tz")) // fail at startup, not on the first request
            out[id] = SavedPlace(id, o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getString("tz"), o.optString("title").ifBlank { null })
        }
        return out
    }

    fun fromResource(): Map<String, SavedPlace> =
        parse(App::class.java.getResource("/places.json")!!.readText())
}

class Response(val status: Int, val type: String, val body: ByteArray, val cache: String = "no-store")

/**
 * Routes, kept apart from the socket so tests can call [respond] directly.
 *
 *  GET /v1/forecast?lat=&lon=[&tz=&name=]  forecast for any position (cached per ~10 km cell)
 *  GET /v1/places/{id}                      forecast for a saved place
 *  GET /healthz                             feed status, for uptime checks
 *  GET /, /lobby, /lobby/{id}               the web app (phone layout, or TV layout under /lobby)
 */
class App(
    private val forecaster: Forecaster,
    private val places: Map<String, SavedPlace>,
    private val defaultPlace: String = "njardvik",
    private val clock: () -> Long = System::currentTimeMillis,
    private val responseTtlMs: Long = 60_000L,
    private val resources: (String) -> ByteArray? = { name -> App::class.java.getResource("/web/$name")?.readBytes() },
) {
    private class Cached(val at: Long, val response: Response)
    private val responses = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 1000
    }

    fun respond(method: String, path: String, rawQuery: String?): Response {
        if (method != "GET" && method != "HEAD") return json(405, Api.error("METHOD_NOT_ALLOWED", "Only GET is supported"))
        val query = parseQuery(rawQuery)
        return when {
            path == "/v1/forecast" -> forecastByPosition(query)
            path.startsWith("/v1/places/") -> forecastForPlace(path.removePrefix("/v1/places/"))
            path == "/healthz" -> health()
            path == "/" || path == "/index.html" || path == "/lobby" || path.startsWith("/lobby/") -> page("index.html", "no-cache")
            else -> staticFile(path.removePrefix("/"))
        }
    }

    private fun forecastByPosition(q: Map<String, String>): Response {
        if (q["lat"] == null && q["lon"] == null) return forecastForPlace(defaultPlace)
        val lat = q["lat"]?.toDoubleOrNull()
        val lon = q["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return json(400, Api.error("BAD_POSITION", "lat must be -90..90 and lon -180..180"))
        }
        val zone = zoneOr400(q["tz"]) ?: return json(400, Api.error("BAD_TZ", "tz must be an IANA time zone such as Atlantic/Reykjavik"))
        val name = q["name"]?.trim()?.take(60)?.ifBlank { null } ?: "Your location"
        val (cLat, cLon) = Grid.cell(lat, lon)
        val cellKey = "cell:" + Grid.key(cLat, cLon)
        return cached("$cellKey|${zone.id}|$name") {
            build(Spot("home", name, cLat, cLon, dark = false), cellKey, zone, Api.Place(name, cLat, cLon))
        }
    }

    private fun forecastForPlace(id: String): Response {
        val p = places[id] ?: return json(404, Api.error("UNKNOWN_PLACE", "No saved place with that id"))
        return cached("place:$id") {
            build(Spot("home", p.name, p.lat, p.lon, dark = false), "place:$id", ZoneId.of(p.tz), Api.Place(p.name, p.lat, p.lon, p.title))
        }
    }

    private fun build(home: Spot, cloudKey: String, zone: ZoneId, place: Api.Place): Response {
        val inp = forecaster.inputs(home, cloudKey)
        if (inp.isEmpty) return json(503, Api.error("NO_DATA", "Forecast feeds are unavailable right now; try again in a minute"))
        val body = Api.forecast(inp, clock(), zone, place, forecaster.problems(cloudKey))
        return json(200, body, "public, max-age=60")
    }

    /** The finished response for a key is reused for [responseTtlMs]; errors are never cached. */
    private fun cached(key: String, make: () -> Response): Response {
        val now = clock()
        synchronized(responses) {
            val hit = responses[key]
            if (hit != null && now - hit.at < responseTtlMs) return hit.response
        }
        val r = make()
        if (r.status == 200) synchronized(responses) { responses[key] = Cached(now, r) }
        return r
    }

    private fun health(): Response {
        val problems = forecaster.problems("")
        return json(200, JSONObject().put("ok", true).put("problems", JSONArray(problems)))
    }

    private val staticName = Regex("^[a-z0-9-]+\\.(js|css|svg|png|webmanifest|html)$")

    private fun staticFile(name: String): Response {
        if (!staticName.matches(name)) return notFound()
        // The service worker must never be cached long, or an old one sticks around.
        val cache = if (name == "sw.js" || name.endsWith(".html")) "no-cache" else "public, max-age=300"
        return page(name, cache)
    }

    private fun page(name: String, cache: String): Response {
        val bytes = resources(name) ?: return notFound()
        return Response(200, contentType(name), bytes, cache)
    }

    private fun notFound() = json(404, Api.error("NOT_FOUND", "Not found"))

    private fun contentType(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "webmanifest" -> "application/manifest+json"
        else -> "application/octet-stream"
    }

    private fun zoneOr400(tz: String?): ZoneId? {
        if (tz.isNullOrBlank()) return ZoneId.of("Atlantic/Reykjavik")
        return try { ZoneId.of(tz) } catch (e: Exception) { null }
    }

    private fun json(status: Int, o: JSONObject, cache: String = "no-store") =
        Response(status, "application/json; charset=utf-8", o.toString().toByteArray(StandardCharsets.UTF_8), cache)

    companion object {
        fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else {
                    val k = URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8)
                    val v = URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8)
                    k to v
                }
            }.toMap()
        }

        /** Gzip for text over 1 KB when the client accepts it; JSON shrinks ~5×, which is most of the egress. */
        fun maybeGzip(r: Response, acceptEncoding: String?): Pair<ByteArray, Boolean> {
            val compressible = r.type.startsWith("text/") || r.type.startsWith("application/json") ||
                r.type.startsWith("application/manifest") || r.type.startsWith("image/svg")
            if (!compressible || r.body.size < 1024 || acceptEncoding?.contains("gzip") != true) return r.body to false
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(r.body) }
            return out.toByteArray() to true
        }
    }
}
