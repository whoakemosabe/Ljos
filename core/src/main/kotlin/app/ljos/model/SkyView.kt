package app.ljos.model

import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * What the live sky shows for one hour, as 0..1 screen values for the shaders. The web pages
 * do the same in aurora.js (skyDetail and starPositions); keep the two in step.
 *
 * The view faces the moon when it's up, else north (where the aurora usually is); that
 * direction sits at [anchor] across the screen, with east to the right of north as when you
 * face it. Stars and twilight keep their true places around it. [horizon] is the screen height
 * (0 top, 1 bottom) of the horizon and [top] of straight overhead, so each screen can keep the
 * sky clear of its text.
 */
data class SkyDetail(
    val moonX: Float, val moonY: Float, val waxing: Float, val moonWash: Float,
    val cloudLow: Float, val cloudMid: Float, val cloudHigh: Float,
    val driftX: Float, val driftY: Float,
    val rain: Float, val rainSlant: Float, val snow: Float, val haze: Float,
    val sunX: Float, val sunY: Float,
    val bzBoost: Float, val redFringe: Float, val pinkEdge: Float,
    /** 14 stars as x, y, brightness (0 = below the horizon). */
    val stars: FloatArray,
)

object SkyView {
    private const val RAD = PI / 180.0

    // Bright stars: right ascension (hours), declination (degrees), brightness.
    private val STARS = arrayOf(
        doubleArrayOf(11.062, 61.75, 0.9), doubleArrayOf(11.031, 56.38, 0.75), doubleArrayOf(11.897, 53.69, 0.7),
        doubleArrayOf(12.257, 57.03, 0.55), doubleArrayOf(12.900, 55.96, 0.9), doubleArrayOf(13.399, 54.93, 0.8),
        doubleArrayOf(13.792, 49.31, 0.85), // Big Dipper
        doubleArrayOf(2.530, 89.26, 0.8), // Polaris
        doubleArrayOf(0.675, 56.54, 0.75), doubleArrayOf(0.153, 59.15, 0.7), doubleArrayOf(0.945, 60.72, 0.75),
        doubleArrayOf(1.430, 60.24, 0.65), doubleArrayOf(1.907, 63.67, 0.6), // Cassiopeia
        doubleArrayOf(5.278, 46.0, 1.0), // Capella
    )

    private fun wrap180(a: Double) = (((a + 180) % 360) + 360) % 360 - 180

    fun azToX(az: Double, viewAz: Double = 0.0, anchor: Float = 0.5f): Float = (anchor + wrap180(az - viewAz) / 360).toFloat()

    fun altToY(alt: Double, horizon: Float, top: Float): Float =
        (horizon - (alt.coerceIn(-10.0, 70.0) / 70.0) * (horizon - top)).toFloat()

    fun stars(ms: Long, lat: Double, lon: Double, horizon: Float, top: Float, anchor: Float = 0.5f, viewAz: Double = 0.0): FloatArray {
        val out = FloatArray(STARS.size * 3)
        val jd = ms / 86_400_000.0 + 2440587.5
        val lst = (280.46061837 + 360.98564736629 * (jd - 2451545) + lon) * RAD
        val phi = lat * RAD
        STARS.forEachIndexed { i, (ra, dec, mag) ->
            val h = lst - ra * 15 * RAD
            val d = dec * RAD
            val alt = asin(sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h)) / RAD
            val az = (atan2(sin(h), cos(h) * sin(phi) - tan(d) * cos(phi)) / RAD + 180) % 360
            val x = azToX(az, viewAz, anchor)
            if (alt > 3 && x > -0.05f && x < 1.05f) {
                out[i * 3] = x; out[i * 3 + 1] = altToY(alt, horizon, top); out[i * 3 + 2] = mag.toFloat()
            }
        }
        return out
    }

    /** [bz] is the latest solar wind field when it's fresh, else null. */
    fun detail(h: HourScore, inp: Inputs, bz: Double?, horizon: Float, top: Float, anchor: Float = 0.5f, starsAt: Long = h.time + HOUR_MS / 2): SkyDetail {
        val home = inp.home
        val mid = h.time + HOUR_MS / 2
        val c01 = { v: Double -> v.coerceIn(0.0, 1.0).toFloat() }
        val moonAz = Astro.moonAzimuth(mid, home.lat, home.lon)
        val sunAz = Astro.sunAzimuth(mid, home.lat, home.lon)
        val waxing = Astro.moonIllumination(mid + 6 * HOUR_MS) > Astro.moonIllumination(mid)
        val viewAz = if (h.moonAlt > 0) moonAz else 0.0

        var low = 0f; var midC = 0f; var high = 0f; var wind = Float.NaN
        val cs = inp.clouds[home.id]
        val ci = cs?.indexAt(h.time) ?: -1
        if (cs != null && ci >= 0) {
            val l = cs.low.getOrElse(ci) { -1 }; val m = cs.mid.getOrElse(ci) { -1 }; val hi = cs.high.getOrElse(ci) { -1 }
            if (l < 0 && m < 0 && hi < 0) midC = h.cloud.coerceAtLeast(0) / 100f
            else { low = l.coerceAtLeast(0) / 100f; midC = m.coerceAtLeast(0) / 100f; high = hi.coerceAtLeast(0) / 100f }
            wind = cs.wind.getOrElse(ci) { Float.NaN }
        } else midC = h.cloud.coerceAtLeast(0) / 100f
        val w = (if (wind.isNaN()) 5.0 else wind.toDouble()).coerceAtMost(20.0)

        var driftX = 0.4f; var driftY = 0f; var slant = 0f
        var rain = 0f; var snow = 0f; var haze = 0f
        val sky = inp.sky
        val si = sky?.indexAt(h.time) ?: -1
        if (sky != null && si >= 0) {
            val dir = sky.windDir[si]
            if (!dir.isNaN()) {
                val toward = (dir + 180.0) * RAD
                driftX = (sin(toward) * w / 10).toFloat()
                driftY = (-cos(toward) * w / 10 * 0.15).toFloat()
                slant = (-sin(toward) * w / 40).coerceIn(-0.35, 0.35).toFloat()
            }
            val r = sky.rain[si]; val s = sky.snow[si]; val v = sky.visibility[si]
            // Rain and snow only fall from real low or mid cloud, so the sky never contradicts the cloud bars.
            val wet = maxOf(low, midC) >= 0.3f
            if (wet && !r.isNaN() && r > 0.05f) rain = 0.08f + 0.4f * c01(r / 4.0)
            if (wet && !s.isNaN() && s > 0.02f) snow = 0.1f + 0.4f * c01(s / 1.5)
            if (!v.isNaN()) haze = c01((8000.0 - v) / 7000.0)
        }

        return SkyDetail(
            moonX = azToX(moonAz, viewAz, anchor),
            moonY = altToY(h.moonAlt, horizon, top).coerceAtLeast(top),
            waxing = if (waxing) 1f else 0f,
            moonWash = if (h.moonAlt > 0) (h.moonIllum * (h.moonAlt / 30.0).coerceIn(0.0, 1.0)).toFloat() else 0f,
            cloudLow = low, cloudMid = midC, cloudHigh = high,
            driftX = driftX, driftY = driftY,
            rain = rain, rainSlant = slant, snow = snow, haze = haze,
            sunX = azToX(sunAz, viewAz, anchor), sunY = horizon,
            bzBoost = if (bz != null) c01(-bz / 12.0) else 0f,
            redFringe = c01((h.kp - 5.5) / 2.0),
            pinkEdge = c01((h.factors.activity - 0.6) / 0.3),
            stars = stars(starsAt, home.lat, home.lon, horizon, top, anchor, viewAz),
        )
    }
}
