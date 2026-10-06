package app.ljos.data

import android.content.Context
import app.ljos.Prefs
import app.ljos.model.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * Keeps the raw feed responses on disk. Everything shown (app, widgets, alerts) is
 * computed from these files, so the app works offline with the last good data.
 */
class Repo(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "feeds").apply { mkdirs() }
    private val prefs = Prefs(context)

    private fun place(): Pair<Spot, List<Spot>> {
        val home = prefs.home
        return home to Spots.forHome(home)
    }

    /**
     * Fetches whatever is stale. [live] (the app is open after dark) treats the live feeds,
     * solar wind and live Kp, as stale after a minute instead of ten.
     */
    suspend fun refresh(force: Boolean = false, live: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val liveAge = if (live) MIN_MS else 10 * MIN_MS
        val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
        val (_, spots) = place()
        val cloudsUrl = Feeds.cloudsUrl(spots)
        // A new location means the cached clouds are for the wrong places.
        val moved = read(CLOUDS_URL) != cloudsUrl
        coroutineScope {
            listOf(
                async { pull(KP, 60 * MIN_MS, Feeds.KP_URL, force, errors) { Feeds.parseKp(it).isNotEmpty() } },
                async {
                    pull(CLOUDS, 60 * MIN_MS, cloudsUrl, force || moved, errors) {
                        val ok = Feeds.parseClouds(it, spots).isNotEmpty()
                        if (ok) write(CLOUDS_URL, cloudsUrl)
                        ok
                    }
                },
                async { pull(MAG, liveAge, Feeds.MAG_SUMMARY_URL, force, errors) { Feeds.parseMagSummary(it) != null } },
                async { pull(WIND, liveAge, Feeds.WIND_SUMMARY_URL, force, errors) { Feeds.parseWindSummary(it) != null } },
                async { pull(KP_NOW, liveAge, Feeds.KP_NOW_URL, force, errors) { Feeds.parseKpNow(it) != null } },
            ).awaitAll()
        }
        // Minute-level solar wind (for travel time, coupling and "sustained" checks) is a bigger
        // download, so only fetch it once it's getting dark and somewhere is clear enough to matter.
        val now = System.currentTimeMillis()
        if (Model.worthLiveCheck(now, inputs())) {
            coroutineScope {
                listOf(
                    async { pull(MAG_RT, liveAge, Feeds.MAG_RTSW_URL, force, errors) { Feeds.parseMagRtsw(it) != null } },
                    async { pull(WIND_RT, liveAge, Feeds.WIND_RTSW_URL, force, errors) { it.trim().startsWith("[") } },
                ).awaitAll()
            }
        }
        errors.toList()
    }

    fun inputs(): Inputs {
        val kp = parse(KP) { Feeds.parseKp(it) } ?: emptyList()
        val (home, spots) = place()
        val cloudsFresh = read(CLOUDS_URL) == Feeds.cloudsUrl(spots)
        val clouds = if (cloudsFresh) parse(CLOUDS) { Feeds.parseClouds(it, spots) } ?: emptyMap() else emptyMap()
        val magSummary = parse(MAG) { Feeds.parseMagSummary(it) }
        val magMinute = parse(MAG_RT) { Feeds.parseMagRtsw(it) }
        val mag = listOfNotNull(magSummary, magMinute).maxByOrNull { it.time }
        val wind = parse(WIND) { Feeds.parseWindSummary(it) }
        val kpNow = parse(KP_NOW) { Feeds.parseKpNow(it) }
        // Only trust the minute series while it's recent; old files would skew "now".
        val magRtFresh = f(MAG_RT).let { it.exists() && System.currentTimeMillis() - it.lastModified() < 90 * MIN_MS }
        val solarWind = if (magRtFresh) {
            val magText = read(MAG_RT)
            if (magText == null) emptyList() else try { Feeds.parseSolarWind(magText, read(WIND_RT)) } catch (e: Exception) { emptyList() }
        } else emptyList()
        val updated = listOf(KP, CLOUDS, MAG, WIND).maxOf { f(it).takeIf { file -> file.exists() }?.lastModified() ?: 0L }
        return Inputs(kp, clouds, mag, wind, updated, home, spots, kpNow, solarWind)
    }

    private fun <T> parse(name: String, parser: (String) -> T?): T? {
        val text = read(name) ?: return null
        return try { parser(text) } catch (e: Exception) { null }
    }

    private fun pull(
        name: String,
        maxAge: Long,
        url: String,
        force: Boolean,
        errors: MutableList<String>,
        valid: (String) -> Boolean,
    ) {
        val file = f(name)
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() < maxAge) return
        try {
            val body = get(url)
            val ok = try { valid(body) } catch (e: Exception) { false }
            if (ok) write(name, body) else errors += "${label(name)}: unexpected data"
        } catch (e: Exception) {
            errors += "${label(name)}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun f(name: String) = File(dir, name)

    private fun read(name: String): String? =
        f(name).takeIf { it.exists() && it.length() > 0 }?.readText()

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f(name))) {
            f(name).writeText(text)
            tmp.delete()
        }
    }

    private fun label(name: String) = when (name) {
        KP -> "Kp forecast"
        CLOUDS -> "Clouds"
        MAG, MAG_RT -> "Solar wind field"
        WIND, WIND_RT -> "Solar wind speed"
        KP_NOW -> "Live Kp"
        else -> name
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", "Ljos/1.0 (Android; personal aurora app)")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val KP = "kp.json"
        const val CLOUDS = "clouds.json"
        const val MAG = "mag.json"
        const val MAG_RT = "mag_rt.json"
        const val WIND = "wind.json"
        const val CLOUDS_URL = "clouds.url"
        const val KP_NOW = "kp_now.json"
        const val WIND_RT = "wind_rt.json"
    }
}
