package app.ljos.server

import app.ljos.data.CloudSeries
import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import app.ljos.data.KpPoint
import app.ljos.data.MIN_MS
import app.ljos.data.SkyFeed
import app.ljos.data.Spots
import app.ljos.model.Model
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class ServerTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val zone = ZoneId.of("Atlantic/Reykjavik")

    // --- Feed cache -------------------------------------------------------------------------

    @Test fun cacheServesFreshCopyWithoutRefetching() {
        var now = 0L
        var calls = 0
        val cache = FeedCache({ calls++; "body$calls" }, { now })
        assertEquals("body1", cache.get("k", "u", 1000) { true }!!.body)
        now = 999
        assertEquals("body1", cache.get("k", "u", 1000) { true }!!.body)
        assertEquals(1, calls)
        now = 1000
        assertEquals("body2", cache.get("k", "u", 1000) { true }!!.body)
    }

    @Test fun cacheKeepsLastGoodBodyAndBacksOffAfterFailure() {
        var now = 0L
        var fail = false
        var calls = 0
        val cache = FeedCache({ calls++; if (fail) throw IOException("HTTP 503") else "good" }, { now }, retryAfterMs = 60_000)
        cache.get("k", "u", 1000) { true }
        fail = true
        now = 5_000
        assertEquals("good", cache.get("k", "u", 1000) { true }!!.body)
        assertEquals("HTTP 503", cache.errors()["k"])
        now = 10_000 // within the back-off: no new request
        cache.get("k", "u", 1000) { true }
        assertEquals(2, calls)
        fail = false
        now = 70_000
        cache.get("k", "u", 1000) { true }
        assertEquals(3, calls)
        assertNull(cache.errors()["k"])
    }

    @Test fun cacheRejectsInvalidBodies() {
        val cache = FeedCache({ "<html>oops</html>" })
        assertNull(cache.get("k", "u", 1000) { it.startsWith("[") })
        assertEquals("unexpected data", cache.errors()["k"])
    }

    // --- Grid -------------------------------------------------------------------------------

    @Test fun nearbyPositionsShareACell() {
        assertEquals(Grid.cell(63.974, -22.549), Grid.cell(63.99, -22.51))
        assertEquals(63.9 to -22.6, Grid.cell(63.94, -22.58))
        assertEquals("63.9,-22.6", Grid.key(63.9, -22.6))
    }

    // --- API JSON ---------------------------------------------------------------------------

    private fun clouds(from: Long, hours: Int, pct: Int) = CloudSeries(
        LongArray(hours) { from + it * HOUR_MS },
        IntArray(hours) { pct }, IntArray(hours) { pct }, IntArray(hours) { pct }, IntArray(hours) { pct },
        FloatArray(hours) { -4f }, FloatArray(hours) { -9f }, FloatArray(hours) { 5f },
    )

    private fun inputs(at: Long, kp: Double, cloudPct: Int): Inputs {
        val from = at - 24 * HOUR_MS
        return Inputs(
            kp = listOf(KpPoint(from, kp, true)),
            clouds = Spots.all.associate { it.id to clouds(from, 96, cloudPct) },
            mag = null,
            wind = null,
            updatedAt = at,
        )
    }

    @Test fun forecastMatchesTheModel() {
        val now = ms("2024-01-11T18:00:00Z") // new moon, January evening
        val inp = inputs(now, 3.0, 0)
        val json = Api.forecast(inp, now, zone, Api.Place("Njarðvík", 63.974, -22.549), emptyList())
        val night = Model.night(now, inp, zone)

        assertEquals(1, json.getInt("version"))
        assertFalse(json.getBoolean("stale"))
        val tonight = json.getJSONObject("tonight")
        val peak = tonight.getJSONObject("peak")
        assertEquals(night.peak!!.score, peak.getInt("score"))
        assertEquals(Model.chance(night.peak!!.score).name, peak.getString("chance"))
        assertEquals(night.hours.size, tonight.getJSONArray("hours").length())
        // Every hour carries the comparison spots, best first.
        val spots = tonight.getJSONArray("hours").getJSONObject(0).getJSONArray("spots")
        assertEquals(inp.spots.size, spots.length())
        assertEquals(json.getJSONArray("spots").length(), inp.spots.size)
        assertEquals(3, json.getJSONArray("outlook").length())
        assertNotNull(json.getJSONObject("weather"))
        // Round-trips as valid JSON (no NaN sneaks in).
        JSONObject(json.toString())
    }

    @Test fun hoursCarrySkyDetail() {
        val now = ms("2024-01-11T18:00:00Z")
        val inp = inputs(now, 3.0, 40)
        val t0 = now - 24 * HOUR_MS
        val sky = SkyFeed.parse(
            """{"hourly":{"time":[${(0 until 72).joinToString(",") { ((t0 + it * HOUR_MS) / 1000).toString() }}],""" +
                """"wind_direction_10m":[${List(72) { "200" }.joinToString(",")}],"rain":[${List(72) { "0.4" }.joinToString(",")}],""" +
                """"showers":[${List(72) { "null" }.joinToString(",")}],"snowfall":[${List(72) { "0" }.joinToString(",")}],""" +
                """"visibility":[${List(72) { "8000" }.joinToString(",")}]}}"""
        )
        assertNotNull(sky)
        val json = Api.forecast(inp, now, zone, Api.Place("x", 63.974, -22.549), emptyList(), sky)
        val h = json.getJSONObject("tonight").getJSONArray("hours").getJSONObject(0)
        assertTrue(h.getDouble("sunAz") in 0.0..360.0)
        assertTrue(h.getDouble("moonAz") in 0.0..360.0)
        assertEquals(40, h.getInt("cloudLow"))
        assertEquals(200.0, h.getDouble("windDir"), 0.1)
        assertEquals(0.4, h.getDouble("rain"), 0.01)
        assertEquals(8000.0, h.getDouble("visibility"), 0.1)
        // Without the sky feed the forecast still works, just without weather detail.
        val plain = Api.forecast(inp, now, zone, Api.Place("x", 63.974, -22.549), emptyList())
        assertFalse(plain.getJSONObject("tonight").getJSONArray("hours").getJSONObject(0).has("windDir"))
        assertNull(SkyFeed.parse("""{"error":true}"""))
    }

    @Test fun oldDataIsMarkedStale() {
        val now = ms("2024-01-11T18:00:00Z")
        val inp = inputs(now - 3 * HOUR_MS, 3.0, 0)
        val stale = Inputs(inp.kp, inp.clouds, null, null, now - 3 * HOUR_MS)
        val json = Api.forecast(stale, now, zone, Api.Place("x", 64.0, -22.0), listOf("Clouds: HTTP 503"))
        assertTrue(json.getBoolean("stale"))
        assertEquals("Clouds: HTTP 503", json.getJSONArray("problems").getString(0))
    }

    @Test fun summerNightHasNoPeak() {
        val now = ms("2024-06-21T23:00:00Z")
        val json = Api.forecast(inputs(now, 5.0, 0), now, zone, Api.Place("x", 64.0, -22.0), emptyList())
        assertTrue(json.getJSONObject("tonight").isNull("peak"))
        assertEquals(0, json.getJSONObject("tonight").getJSONArray("hours").length())
    }

    // --- Routes -----------------------------------------------------------------------------

    private val places = Places.parse(
        """[{"id":"njardvik","name":"Njarðvík","lat":63.974,"lon":-22.549,"tz":"Atlantic/Reykjavik","title":"Ljós"}]"""
    )

    /** Feeds that answer like NOAA and Open-Meteo, with one cloud block per requested spot. */
    private fun fakeFeeds(now: Long): (String) -> String = { url ->
        when {
            url.contains("k-index-forecast") ->
                """[{"time_tag":"${Instant.ofEpochMilli(now - 3 * HOUR_MS).toString().removeSuffix("Z")}","kp":3.0,"observed":"predicted"}]"""
            url.contains("open-meteo") -> {
                val n = url.substringAfter("latitude=").substringBefore("&").split(",").size
                val start = (now - 24 * HOUR_MS) / 1000
                val times = (0 until 96).joinToString(",") { (start + it * 3600).toString() }
                val zeros = (0 until 96).joinToString(",") { "0" }
                (1..n).joinToString(",", "[", "]") {
                    """{"hourly":{"time":[$times],"cloud_cover":[$zeros],"cloud_cover_low":[$zeros],"cloud_cover_mid":[$zeros],"cloud_cover_high":[$zeros]}}"""
                }
            }
            else -> throw IOException("HTTP 404")
        }
    }

    private fun app(now: Long, fetch: (String) -> String) =
        App(Forecaster(FeedCache(fetch, { now }), { now }), places, clock = { now }, resources = { name ->
            when (name) {
                "index.html" -> "<!doctype html><title>phone</title>".toByteArray()
                "lobby.html" -> "<!doctype html><title>lobby</title>".toByteArray()
                else -> null
            }
        })

    @Test fun forecastByPositionWorks() {
        val now = ms("2024-01-11T18:00:00Z")
        val r = app(now, fakeFeeds(now)).respond("GET", "/v1/forecast", "lat=63.99&lon=-22.51&tz=Atlantic%2FReykjavik")
        assertEquals(200, r.status)
        val json = JSONObject(String(r.body))
        assertEquals(64.0, json.getJSONObject("place").getDouble("lat"), 1e-9) // the cell, not the exact spot
        assertEquals(-22.6, json.getJSONObject("place").getDouble("lon"), 1e-9)
        assertEquals("Your location", json.getJSONObject("place").getString("name"))
    }

    @Test fun badInputIsRejected() {
        val now = ms("2024-01-11T18:00:00Z")
        val a = app(now, fakeFeeds(now))
        assertEquals(400, a.respond("GET", "/v1/forecast", "lat=95&lon=0").status)
        assertEquals(400, a.respond("GET", "/v1/forecast", "lat=abc&lon=0").status)
        assertEquals(400, a.respond("GET", "/v1/forecast", "lat=64&lon=-22&tz=Not%2FAZone").status)
        assertEquals(404, a.respond("GET", "/v1/places/nope", null).status)
        assertEquals(405, a.respond("POST", "/v1/forecast", null).status)
    }

    @Test fun savedPlaceAndPagesAreServed() {
        val now = ms("2024-01-11T18:00:00Z")
        val a = app(now, fakeFeeds(now))
        val place = a.respond("GET", "/v1/places/njardvik", null)
        assertEquals(200, place.status)
        assertEquals("Ljós", JSONObject(String(place.body)).getJSONObject("place").getString("title"))
        assertEquals(200, a.respond("GET", "/", null).status)
        assertTrue(String(a.respond("GET", "/", null).body).contains("phone"))
        val lobby = a.respond("GET", "/lobby/njardvik", null)
        assertEquals(200, lobby.status)
        assertTrue(String(lobby.body).contains("lobby"))
        assertEquals("text/html; charset=utf-8", a.respond("GET", "/lobby", null).type)
    }

    @Test fun pathTraversalIsNotFound() {
        val now = ms("2024-01-11T18:00:00Z")
        val a = app(now, fakeFeeds(now))
        assertEquals(404, a.respond("GET", "/../places.json", null).status)
        assertEquals(404, a.respond("GET", "/web/../../etc/passwd", null).status)
        assertEquals(404, a.respond("GET", "/places.json", null).status)
    }

    @Test fun noFeedsMeansServiceUnavailable() {
        val now = ms("2024-01-11T18:00:00Z")
        val r = app(now) { throw IOException("offline") }.respond("GET", "/v1/forecast", "lat=64&lon=-22")
        assertEquals(503, r.status)
        assertEquals("NO_DATA", JSONObject(String(r.body)).getJSONObject("error").getString("code"))
    }

    @Test fun responsesAreReusedWithinAMinute() {
        val now = ms("2024-01-11T18:00:00Z")
        var calls = 0
        val feeds = fakeFeeds(now)
        val a = app(now) { calls++; feeds(it) }
        a.respond("GET", "/v1/places/njardvik", null)
        val first = calls
        a.respond("GET", "/v1/places/njardvik", null)
        assertEquals(first, calls)
        assertTrue(MIN_MS > 0)
    }

    @Test fun gzipOnlyWhenWorthIt() {
        val big = Response(200, "application/json; charset=utf-8", ByteArray(4000) { 'a'.code.toByte() })
        assertTrue(App.maybeGzip(big, "gzip, br").second)
        assertFalse(App.maybeGzip(big, null).second)
        val png = Response(200, "image/png", ByteArray(4000))
        assertFalse(App.maybeGzip(png, "gzip").second)
    }
}
