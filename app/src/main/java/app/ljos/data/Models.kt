package app.ljos.data

import kotlin.math.abs

const val MIN_MS = 60_000L
const val HOUR_MS = 3_600_000L

data class Spot(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** False for places inside town lights. */
    val dark: Boolean,
)

/** One 3-hour Kp block from NOAA, starting at [time]. */
data class KpPoint(val time: Long, val kp: Double, val predicted: Boolean)

/** Interplanetary magnetic field. Negative [bz] (southward) is what drives aurora. */
data class MagReading(val time: Long, val bz: Double, val bt: Double)

data class WindReading(val time: Long, val speed: Double)

/** Hourly cloud cover in percent for one place. -1 means missing. */
class CloudSeries(
    val times: LongArray,
    val total: IntArray,
    val low: IntArray,
    val mid: IntArray,
    val high: IntArray,
) {
    /** Index of the hour that contains [t], else the nearest hour within 3h, else -1. */
    fun indexAt(t: Long): Int {
        if (times.isEmpty()) return -1
        var best = -1
        var bestDiff = Long.MAX_VALUE
        for (i in times.indices) {
            val diff = t - times[i]
            if (diff in 0 until HOUR_MS) return i
            val ad = abs(diff)
            if (ad < bestDiff) {
                bestDiff = ad
                best = i
            }
        }
        return if (bestDiff <= 3 * HOUR_MS) best else -1
    }
}

class Inputs(
    val kp: List<KpPoint>,
    val clouds: Map<String, CloudSeries>,
    val mag: MagReading?,
    val wind: WindReading?,
    val updatedAt: Long,
    /** Where "you" are. Spots[0] is always this place. */
    val home: Spot = Spots.home,
    val spots: List<Spot> = Spots.all,
    /** NOAA's minute-by-minute estimated Kp (latest reading), for "right now". */
    val kpNow: KpPoint? = null,
    /** Minute-level solar wind at the L1 satellite, oldest first. Empty when not fetched. */
    val solarWind: List<SwPoint> = emptyList(),
) {
    val isEmpty: Boolean get() = kp.isEmpty() && clouds.isEmpty()
}

/** One minute of solar wind measured at L1, ~1.5 million km upstream of Earth. */
data class SwPoint(
    val time: Long,
    val bz: Double,
    val by: Double,
    val bt: Double,
    /** km/s; NaN if the plasma instrument had no reading that minute. */
    val speed: Double,
)
