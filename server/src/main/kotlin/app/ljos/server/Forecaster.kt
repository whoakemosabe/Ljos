package app.ljos.server

import app.ljos.data.Feeds
import app.ljos.data.Inputs
import app.ljos.data.MIN_MS
import app.ljos.data.Spot
import app.ljos.data.Spots
import app.ljos.model.Model
import java.util.Locale
import kotlin.math.roundToLong

/** Rounds positions to cells of about 10 km, so everyone nearby shares one cloud fetch. */
object Grid {
    /** 0.1° of latitude is ~11 km; 0.2° of longitude is ~10 km at Iceland's latitude. */
    fun cell(lat: Double, lon: Double): Pair<Double, Double> =
        (lat * 10).roundToLong() / 10.0 to (lon * 5).roundToLong() / 5.0

    fun key(lat: Double, lon: Double): String = String.format(Locale.US, "%.1f,%.1f", lat, lon)
}

/**
 * Builds the same [Inputs] the phone builds, from the shared feed cache. NOAA feeds are global
 * (one copy for everyone); clouds are fetched per place: a grid cell, or a saved place.
 */
class Forecaster(
    private val cache: FeedCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Parsed(val entry: FeedCache.Entry, val value: Any?)
    private val parsed = object : LinkedHashMap<String, Parsed>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?): Boolean = size > 2000
    }

    /** Parses a body once per fetch, not once per request (the minute feeds are large). */
    @Suppress("UNCHECKED_CAST")
    private fun <T> parse(key: String, entry: FeedCache.Entry?, parser: (String) -> T?): T? {
        if (entry == null) return null
        synchronized(parsed) {
            val hit = parsed[key]
            if (hit != null && hit.entry === entry) return hit.value as T?
        }
        val value = try { parser(entry.body) } catch (e: Exception) { null }
        synchronized(parsed) { parsed[key] = Parsed(entry, value) }
        return value
    }

    /** [cloudKey] identifies whose clouds these are: "cell:63.9,-22.6" or "place:<id>". */
    fun inputs(home: Spot, cloudKey: String): Inputs {
        val spots = Spots.forHome(home)
        val now = clock()

        val kpE = cache.get("kp", Feeds.KP_URL, 60 * MIN_MS) { Feeds.parseKp(it).isNotEmpty() }
        val magE = cache.get("mag", Feeds.MAG_SUMMARY_URL, 5 * MIN_MS) { Feeds.parseMagSummary(it) != null }
        val windE = cache.get("wind", Feeds.WIND_SUMMARY_URL, 5 * MIN_MS) { Feeds.parseWindSummary(it) != null }
        val kpNowE = cache.get("kp_now", Feeds.KP_NOW_URL, 5 * MIN_MS) { Feeds.parseKpNow(it) != null }
        val cloudsE = cache.get(cloudKey, Feeds.cloudsUrl(spots), 60 * MIN_MS) { Feeds.parseClouds(it, spots).isNotEmpty() }

        val kp = parse("kp", kpE) { Feeds.parseKp(it) } ?: emptyList()
        val clouds = parse(cloudKey, cloudsE) { Feeds.parseClouds(it, spots) } ?: emptyMap()
        val magSummary = parse("mag", magE) { Feeds.parseMagSummary(it) }
        val wind = parse("wind", windE) { Feeds.parseWindSummary(it) }
        val kpNow = parse("kp_now", kpNowE) { Feeds.parseKpNow(it) }
        val updated = listOfNotNull(kpE, cloudsE, magE, windE).maxOfOrNull { it.fetchedAt } ?: 0L

        val base = Inputs(kp, clouds, magSummary, wind, updated, home, spots, kpNow)
        // Minute-level solar wind is a bigger download; like the app, only when it's getting
        // dark and somewhere nearby is clear enough to matter.
        if (!Model.worthLiveCheck(now, base)) return base

        val magRtE = cache.get("mag_rt", Feeds.MAG_RTSW_URL, 5 * MIN_MS) { Feeds.parseMagRtsw(it) != null }
        val windRtE = cache.get("wind_rt", Feeds.WIND_RTSW_URL, 5 * MIN_MS) { it.trim().startsWith("[") }
        val magMinute = parse("mag_rt", magRtE) { Feeds.parseMagRtsw(it) }
        val rtFresh = magRtE != null && now - magRtE.fetchedAt < 90 * MIN_MS
        val solarWind = if (rtFresh) {
            parse("sw:${magRtE?.fetchedAt}:${windRtE?.fetchedAt}", magRtE) { Feeds.parseSolarWind(it, windRtE?.body) }
                ?: emptyList()
        } else emptyList()
        val mag = listOfNotNull(magSummary, magMinute).maxByOrNull { it.time }
        return Inputs(kp, clouds, mag, wind, updated, home, spots, kpNow, solarWind)
    }

    /** Fetch problems worth showing, in plain words. */
    fun problems(cloudKey: String): List<String> {
        val labels = mapOf(
            "kp" to "Kp forecast", "mag" to "Solar wind field", "wind" to "Solar wind speed",
            "kp_now" to "Live Kp", "mag_rt" to "Solar wind field", "wind_rt" to "Solar wind speed",
            cloudKey to "Clouds",
        )
        return cache.errors().mapNotNull { (k, v) -> labels[k]?.let { "$it: $v" } }.distinct()
    }
}
