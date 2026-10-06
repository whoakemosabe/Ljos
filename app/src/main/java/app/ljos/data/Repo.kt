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

    suspend fun refresh(force: Boolean = false): List<String> = withContext(Dispatchers.IO) {
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
                async { pull(MAG, 10 * MIN_MS, Feeds.MAG_SUMMARY_URL, force, errors) { Feeds.parseMagSummary(it) != null } },
                async { pull(WIND, 10 * MIN_MS, Feeds.WIND_SUMMARY_URL, force, errors) { Feeds.parseWindSummary(it) != null } },
            ).awaitAll()
        }
        // The summary feed sometimes lags. When it's dark and clear enough to matter,
        // fall back to the minute-by-minute feed (bigger download, so only then).
        val now = System.currentTimeMillis()
        val inp = inputs()
        val magAge = inp.mag?.let { now - it.time } ?: Long.MAX_VALUE
        if (magAge > 45 * MIN_MS && Model.worthLiveCheck(now, inp)) {
            pull(MAG_RT, 10 * MIN_MS, Feeds.MAG_RTSW_URL, force, errors) { Feeds.parseMagRtsw(it) != null }
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
        val updated = listOf(KP, CLOUDS, MAG, WIND).maxOf { f(it).takeIf { file -> file.exists() }?.lastModified() ?: 0L }
        return Inputs(kp, clouds, mag, wind, updated, home, spots)
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
        WIND -> "Solar wind speed"
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
    }
}
