package app.ljos.model

import app.ljos.L
import app.ljos.data.CloudSeries
import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import app.ljos.data.KpPoint
import app.ljos.data.MIN_MS
import app.ljos.data.MagReading
import app.ljos.data.Spot
import app.ljos.data.WindReading
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Each factor is 0..1. The score is their product, so clouds or daylight can zero it on their own. */
data class Factors(val activity: Double, val clear: Double, val moon: Double, val dark: Double)

data class HourScore(
    val time: Long,
    val score: Int,
    val factors: Factors,
    val kp: Double,
    /** Effective cloud cover in percent, -1 if unknown. */
    val cloud: Int,
    val sunAlt: Double,
    val moonIllum: Double,
    val moonAlt: Double,
    /** True when live solar-wind data nudged this hour. */
    val live: Boolean,
)

data class Night(
    val start: Long,
    val end: Long,
    /** Dark hours plus one twilight hour either side. Empty in summer. */
    val hours: List<HourScore>,
    val peak: HourScore?,
    val darkFrom: Long?,
    val darkUntil: Long?,
)

data class SpotScore(val spot: Spot, val score: Int, val cloud: Int, val distanceKm: Double)

data class NowState(
    val score: Int,
    val isDark: Boolean,
    val cloud: Int,
    val bz: Double?,
    val bzFresh: Boolean,
    val lookUp: Boolean,
    /** Clearest dark spot right now, if home is cloudy but somewhere nearby isn't. */
    val clearerSpot: SpotScore?,
    /** Minutes until what's measured at L1 now reaches Earth; null without live solar wind. */
    val etaMinutes: Int? = null,
    /** Mean Bz arriving over the last ~20 minutes (sustained, not a blip). */
    val bzSustained: Double? = null,
)

object Model {
    private const val DEFAULT_KP = 1.5
    private const val TOWN_LIGHTS = 0.85

    /** Iceland sits under the auroral oval, so even modest Kp is often visible. */
    fun activityFromKp(kp: Double): Double = when {
        kp <= 0.0 -> 0.1
        kp < 3.0 -> 0.1 + 0.2 * kp
        kp < 4.0 -> 0.7 + 0.15 * (kp - 3.0)
        kp < 5.0 -> 0.85 + 0.15 * (kp - 4.0)
        else -> 1.0
    }

    /** Southward Bz and fast wind push activity up over the next hour or so. Null if data is stale. */
    fun liveBoost(mag: MagReading?, wind: WindReading?, now: Long): Double? {
        if (mag == null || now - mag.time > 60 * MIN_MS) return null
        var b = (-mag.bz / 20.0).coerceIn(-0.2, 0.35)
        val speed = wind?.takeIf { now - it.time < 90 * MIN_MS }?.speed
        if (speed != null) {
            b += when {
                speed >= 650 -> 0.1
                speed >= 500 -> 0.05
                else -> 0.0
            }
        }
        return b
    }

    /**
     * Sun above -6° is daylight/civil twilight: nothing. Faint aurora needs nautical darkness
     * (-12°), so the factor climbs slowly through -6…-12° and reaches full at -15°.
     */
    fun darkFactor(sunAlt: Double): Double = when {
        sunAlt >= -6.0 -> 0.0
        sunAlt >= -9.0 -> 0.15 + 0.30 * ((-6.0 - sunAlt) / 3.0)   // -6 → 0.15, -9 → 0.45
        sunAlt >= -12.0 -> 0.45 + 0.35 * ((-9.0 - sunAlt) / 3.0)  // -9 → 0.45, -12 → 0.80
        sunAlt > -15.0 -> 0.80 + 0.20 * ((-12.0 - sunAlt) / 3.0)  // -12 → 0.80, -15 → 1.0
        else -> 1.0
    }

    /** Low and mid cloud block everything; thin high cloud only partly. Returns factor and effective %. */
    fun clearFactor(cs: CloudSeries?, i: Int): Pair<Double, Int> {
        if (cs == null || i < 0) return 0.6 to -1
        val total = cs.total[i]
        val low = cs.low[i]
        val mid = cs.mid[i]
        val high = cs.high[i]
        val eff = when {
            low >= 0 && mid >= 0 && high >= 0 ->
                maxOf(low.toDouble(), mid.toDouble(), high * 0.6, if (total >= 0) total * 0.7 else 0.0)
            total >= 0 -> total.toDouble()
            else -> return 0.6 to -1
        }
        val pct = eff.roundToInt().coerceIn(0, 100)
        return (1.0 - eff / 100.0).coerceIn(0.0, 1.0) to pct
    }

    /** A bright moon high in the sky washes out faint aurora. */
    fun moonFactor(illum: Double, moonAlt: Double): Double {
        if (moonAlt <= 0.0) return 1.0
        val up = (moonAlt / 20.0).coerceIn(0.0, 1.0)
        return 1.0 - 0.4 * illum * up
    }

    fun kpAt(kp: List<KpPoint>, t: Long): Double? {
        if (kp.isEmpty()) return null
        return (kp.lastOrNull { it.time <= t } ?: kp.first()).kp
    }

    fun hourScore(t: Long, spot: Spot, inp: Inputs, now: Long, townPenalty: Boolean = false): HourScore {
        val mid = t + 30 * MIN_MS
        val sun = Astro.sunAltitude(mid, spot.lat, spot.lon)
        val moonAlt = Astro.moonAltitude(mid, spot.lat, spot.lon)
        val moonIllum = Astro.moonIllumination(mid)
        val forecastKp = kpAt(inp.kp, t) ?: DEFAULT_KP
        // For the current hour, NOAA's minute-by-minute estimated Kp beats the 3-hour forecast;
        // for the next hour, blend the two.
        val kpNow = inp.kpNow?.takeIf { now - it.time <= 30 * MIN_MS }?.kp
        val kp = when {
            kpNow == null -> forecastKp
            now >= t && now < t + HOUR_MS -> kpNow
            now >= t - HOUR_MS && now < t -> (kpNow + forecastKp) / 2.0
            else -> forecastKp
        }

        // Live solar wind: use what will actually be arriving at Earth during this hour,
        // given today's travel time from L1. Falls back to the single summary reading.
        val arrival = Live.arriving(inp, now, t, t + HOUR_MS)
        val (boost, weight) = if (arrival != null) {
            Live.boost(arrival) to 1.0
        } else if (inp.solarWind.isEmpty()) {
            liveBoost(inp.mag, inp.wind, now) to when {
                now >= t && now < t + HOUR_MS -> 1.0
                now >= t - HOUR_MS && now < t -> 0.5
                else -> 0.0
            }
        } else {
            null to 0.0
        }
        val activity = (activityFromKp(kp) + (boost ?: 0.0) * weight).coerceIn(0.05, 1.0)

        val series = inp.clouds[spot.id]
        val (clear, cloud) = clearFactor(series, series?.indexAt(t) ?: -1)
        val moon = moonFactor(moonIllum, moonAlt)
        val dark = darkFactor(sun)
        val lights = if (townPenalty && !spot.dark) TOWN_LIGHTS else 1.0

        val score = (100.0 * activity * clear * moon * dark * lights).roundToInt().coerceIn(0, 100)
        return HourScore(
            time = t,
            score = score,
            factors = Factors(activity, clear, moon, dark),
            kp = kp,
            cloud = cloud,
            sunAlt = sun,
            moonIllum = moonIllum,
            moonAlt = moonAlt,
            live = weight > 0 && boost != null,
        )
    }

    /** "Tonight" runs 15:00 to 10:00 local; before 10:00 it still means the night that's ending. */
    fun nightWindow(now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val day = if (local.hour < 10) local.toLocalDate().minusDays(1) else local.toLocalDate()
        val start = day.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun night(now: Long, inp: Inputs, zone: ZoneId = ZoneId.systemDefault()): Night {
        val spot = inp.home
        val (start, end) = nightWindow(now, zone)
        val all = ArrayList<HourScore>()
        var t = start
        while (t < end) {
            all += hourScore(t, spot, inp, now)
            t += HOUR_MS
        }
        val firstDark = all.indexOfFirst { it.factors.dark > 0 }
        val lastDark = all.indexOfLast { it.factors.dark > 0 }
        if (firstDark < 0) return Night(start, end, emptyList(), null, null, null)

        val shown = all.subList(maxOf(0, firstDark - 1), minOf(all.size, lastDark + 2)).toList()
        val dark = all.subList(firstDark, lastDark + 1)
        val upcoming = dark.filter { it.time + HOUR_MS > now }
        val peak = upcoming.ifEmpty { dark }.maxByOrNull { it.score }
        return Night(start, end, shown, peak, dark.first().time, dark.last().time + HOUR_MS)
    }

    fun spotsAt(t: Long, inp: Inputs, now: Long): List<SpotScore> =
        inp.spots.map { s ->
            val h = hourScore(t, s, inp, now, townPenalty = true)
            SpotScore(s, h.score, h.cloud, Geo.km(inp.home.lat, inp.home.lon, s.lat, s.lon))
        }.sortedWith(compareByDescending<SpotScore> { it.score }.thenBy { it.distanceKm })

    fun nowState(now: Long, inp: Inputs): NowState {
        val hourStart = now - now % HOUR_MS
        val h = hourScore(hourStart, inp.home, inp, now)
        val sunNow = Astro.sunAltitude(now, inp.home.lat, inp.home.lon)
        val latest = Live.latest(inp, now)
        val mag = inp.mag
        val bz = latest?.bz ?: mag?.bz
        val bzFresh = latest != null || (mag != null && now - mag.time <= 60 * MIN_MS)

        val bestDark = spotsAt(hourStart, inp, now).firstOrNull { it.spot.dark }
        val homeClear = h.factors.clear >= 0.45
        val elsewhereClear = bestDark != null && bestDark.cloud in 0..50
        val skyOk = sunNow < -9.0 && (homeClear || elsewhereClear) && maxOf(h.score, bestDark?.score ?: 0) >= 40

        // Sustained southward field arriving *now* (the last ~20 min of L1 data, shifted by travel time).
        val arriving = Live.arriving(inp, now, now - 20 * MIN_MS, now)
        val driven = if (arriving != null) {
            arriving.meanBz <= -5.0 && arriving.southFraction >= 0.75
        } else {
            bzFresh && bz != null && bz <= -6.0 // fallback: single summary reading
        }
        val lookUp = skyOk && driven

        val speed = Live.speed(inp, now)
        val eta = if (latest != null && speed != null) (Live.delayMs(speed) / MIN_MS).toInt() else null
        val clearer = if (!homeClear && elsewhereClear) bestDark else null
        return NowState(h.score, sunNow < -6.0, h.cloud, bz, bzFresh, lookUp, clearer, eta, arriving?.meanBz)
    }

    /** Only worth fetching the minute-level feeds when it's getting dark and somewhere is clear-ish. */
    fun worthLiveCheck(now: Long, inp: Inputs): Boolean {
        val sun = Astro.sunAltitude(now, inp.home.lat, inp.home.lon)
        if (sun > -4.0) return false
        val hourStart = now - now % HOUR_MS
        return inp.spots.any { s ->
            val series = inp.clouds[s.id]
            clearFactor(series, series?.indexAt(hourStart) ?: -1).first >= 0.4
        }
    }

    fun label(score: Int): String = when {
        score >= 80 -> L.t("Excellent", "Frábærar líkur")
        score >= 60 -> L.t("Good chance", "Góðar líkur")
        score >= 40 -> L.t("Possible", "Mögulegt")
        score >= 20 -> L.t("Unlikely", "Ólíklegt")
        else -> L.t("Not tonight", "Ekki í kvöld")
    }

    /** When the moon is up between [start] and [end], at 10-minute resolution, plus its brightness. */
    fun moonTimeline(start: Long, end: Long, home: Spot): MoonTimeline {
        val step = 10 * MIN_MS
        val segments = ArrayList<Pair<Long, Long>>()
        var upFrom: Long? = null
        var rise: Long? = null
        var set: Long? = null
        var prevUp: Boolean? = null
        var t = start
        while (t <= end) {
            val up = Astro.moonAltitude(t, home.lat, home.lon) > 0.0
            if (prevUp != null && up != prevUp) { if (up) rise = rise ?: t else set = set ?: t }
            if (up && upFrom == null) upFrom = t
            if (!up && upFrom != null) { segments += upFrom to t; upFrom = null }
            prevUp = up
            t += step
        }
        upFrom?.let { segments += it to end }
        val mid = start + (end - start) / 2
        return MoonTimeline(segments, rise, set, Astro.moonIllumination(mid), waxing = Astro.moonIllumination(mid + 6 * HOUR_MS) > Astro.moonIllumination(mid))
    }

    /** Kp forecast grouped into today and the next two days, local time. */
    fun kpOutlook(kp: List<KpPoint>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<KpDay> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..2L).map { offset ->
            val date = today.plusDays(offset)
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val blocks = kp.filter { it.time >= dayStart && it.time < dayEnd }
            KpDay(dayStart, blocks, blocks.maxOfOrNull { it.kp })
        }
    }
}

data class MoonTimeline(
    val upSegments: List<Pair<Long, Long>>,
    val rise: Long?,
    val set: Long?,
    val illumination: Double,
    val waxing: Boolean,
)

data class KpDay(val dayStart: Long, val blocks: List<KpPoint>, val maxKp: Double?)

