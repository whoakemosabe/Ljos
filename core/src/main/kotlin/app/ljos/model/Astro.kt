package app.ljos.model

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Sun and moon positions, ported from the suncalc algorithms (accurate to well under a degree,
 * which is plenty for "is it dark" and "is the moon up").
 */
object Astro {
    private const val RAD = PI / 180.0
    private const val OBLIQUITY = RAD * 23.4397
    private const val SUN_DIST_KM = 149_598_000.0

    private class Eq(val ra: Double, val dec: Double, val dist: Double = 0.0)

    private fun toDays(ms: Long): Double = ms / 86_400_000.0 - 0.5 + 2440588.0 - 2451545.0

    private fun rightAscension(l: Double, b: Double) =
        atan2(sin(l) * cos(OBLIQUITY) - tan(b) * sin(OBLIQUITY), cos(l))

    private fun declination(l: Double, b: Double) =
        asin(sin(b) * cos(OBLIQUITY) + cos(b) * sin(OBLIQUITY) * sin(l))

    private fun siderealTime(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw

    private fun altitude(h: Double, phi: Double, dec: Double) =
        asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))

    private fun sunCoords(d: Double): Eq {
        val m = RAD * (357.5291 + 0.98560028 * d)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        return Eq(rightAscension(l, 0.0), declination(l, 0.0))
    }

    private fun moonCoords(d: Double): Eq {
        val l0 = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        val dist = 385001.0 - 20905.0 * cos(m)
        return Eq(rightAscension(l, b), declination(l, b), dist)
    }

    /** Degrees above the horizon; negative is below. */
    fun sunAltitude(ms: Long, lat: Double, lon: Double): Double {
        val d = toDays(ms)
        val c = sunCoords(d)
        val h = siderealTime(d, RAD * -lon) - c.ra
        return altitude(h, RAD * lat, c.dec) / RAD
    }

    fun moonAltitude(ms: Long, lat: Double, lon: Double): Double {
        val d = toDays(ms)
        val c = moonCoords(d)
        val h = siderealTime(d, RAD * -lon) - c.ra
        return altitude(h, RAD * lat, c.dec) / RAD
    }

    /** Lit fraction of the moon, 0 (new) to 1 (full). */
    fun moonIllumination(ms: Long): Double {
        val d = toDays(ms)
        val s = sunCoords(d)
        val m = moonCoords(d)
        val phi = acos(
            (sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra)).coerceIn(-1.0, 1.0)
        )
        val inc = atan2(SUN_DIST_KM * sin(phi), m.dist - SUN_DIST_KM * cos(phi))
        return (1 + cos(inc)) / 2
    }
}

object Geo {
    /** Great-circle distance in km. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
