package app.ljos.model

import app.ljos.data.Inputs
import app.ljos.data.MIN_MS
import app.ljos.data.SwPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live solar wind, done properly:
 *  - it's measured at L1, ~1.5 million km out, so it reaches Earth after a delay set by its speed;
 *  - what drives aurora is the Newell coupling function, not Bz alone:
 *        dΦ/dt = v^(4/3) · Bt^(2/3) · sin^(8/3)(θ/2),  θ = IMF clock angle atan2(By, Bz)
 *  - and it has to *stay* southward for a while, so we average over windows, not single minutes;
 *  - dense, fast wind presses harder on Earth's field (dynamic pressure, nPa = 1.6726e-6 · n · v²),
 *    which adds a little on top, and a sudden jump in density and speed is a shock arriving.
 */
object Live {
    private const val L1_KM = 1_500_000.0

    /** What's arriving at Earth over a time window. */
    data class Arrival(
        /** Mean Newell coupling, (km/s)^(4/3)·nT^(2/3). Quiet ~1–3k, active ~8k+, storm 15k+. */
        val coupling: Double,
        val meanBz: Double,
        /** Mean total field strength (Bt) over the same minutes, nT. */
        val meanBt: Double,
        /** Mean dynamic pressure, nPa; NaN without density readings. Quiet ~1–3, high 5+, shock 10+. */
        val pressure: Double,
        /** Share of minutes with Bz pointing south. */
        val southFraction: Double,
        /** How long today's L1 readings take to get here. */
        val delayMs: Long,
        val minutes: Int,
    )

    fun newell(p: SwPoint, v: Double): Double {
        val theta = atan2(p.by, p.bz)
        val s = abs(sin(theta / 2.0))
        return v.pow(4.0 / 3.0) * p.bt.coerceAtLeast(0.0).pow(2.0 / 3.0) * s.pow(8.0 / 3.0)
    }

    /** Typical recent wind speed: median of the last 30 minutes with a reading, else the summary feed. */
    fun speed(inp: Inputs, now: Long): Double? {
        val recent = inp.solarWind.filter { !it.speed.isNaN() && now - it.time <= 30 * MIN_MS }.map { it.speed }.sorted()
        if (recent.size >= 3) return recent[recent.size / 2]
        return inp.wind?.takeIf { now - it.time < 90 * MIN_MS }?.speed
    }

    fun delayMs(speedKmS: Double): Long = (L1_KM / speedKmS.coerceIn(250.0, 1500.0) * 1000).toLong()

    /** Solar wind arriving at Earth during [from, to), or null if we don't have those minutes. */
    fun arriving(inp: Inputs, now: Long, from: Long, to: Long): Arrival? {
        if (inp.solarWind.isEmpty()) return null
        val v = speed(inp, now) ?: return null
        val d = delayMs(v)
        val pts = inp.solarWind.filter { it.time >= from - d && it.time < to - d }
        if (pts.size < 8) return null
        val couplings = pts.map { newell(it, if (it.speed.isNaN()) v else it.speed) }
        val pressures = pts.filter { it.density.isFinite() }.map { pressure(it.density, if (it.speed.isNaN()) v else it.speed) }
        return Arrival(
            coupling = couplings.average(),
            meanBz = pts.map { it.bz }.average(),
            pressure = if (pressures.size >= 4) pressures.average() else Double.NaN,
            meanBt = pts.map { it.bt }.filter { it.isFinite() }.ifEmpty { listOf(Double.NaN) }.average(),
            southFraction = pts.count { it.bz < 0 }.toDouble() / pts.size,
            delayMs = d,
            minutes = pts.size,
        )
    }

    /** Dynamic pressure in nPa from density (cm⁻³) and speed (km/s). */
    fun pressure(density: Double, speed: Double): Double = 1.6726e-6 * density * speed * speed

    /**
     * Activity nudge from coupling: northward/quiet pulls down a little, strong driving pushes up.
     * High pressure adds a little (it compresses the field and brightens what is already there),
     * but only when the field is also open: pressure alone does not make aurora.
     */
    fun boost(a: Arrival): Double {
        val base = (a.coupling - 4000.0) / 12_000.0 * 0.45
        val push = if (a.pressure.isFinite() && a.coupling >= COUPLING_HELPING) ((sqrt(a.pressure) - 1.5) * 0.04).coerceIn(0.0, 0.06) else 0.0
        return (base + push).coerceIn(-0.15, 0.4)
    }

    /** Coupling limits, (km/s)^(4/3)·nT^(2/3). Bz −5, Bt 6 at 400 km/s is about 9,700. */
    const val COUPLING_HELPING = 6_000.0
    const val COUPLING_STRONG = 15_000.0
    const val COUPLING_LOOK_UP = 9_000.0
    private const val COUPLING_NEUTRAL = 2_500.0

    /** How hard the solar wind is driving aurora. One word for the app, the web app and alerts. */
    enum class Drive { STRONG, HELPING, NEUTRAL, BLOCKING }

    fun drive(coupling: Double): Drive = when {
        coupling >= COUPLING_STRONG -> Drive.STRONG
        coupling >= COUPLING_HELPING -> Drive.HELPING
        coupling >= COUPLING_NEUTRAL -> Drive.NEUTRAL
        else -> Drive.BLOCKING
    }

    /** Without minute data: a rough word from the summary Bz alone. */
    fun driveFromBz(bz: Double): Drive = when {
        bz <= -10 -> Drive.STRONG
        bz <= -3 -> Drive.HELPING
        bz >= 3 -> Drive.BLOCKING
        else -> Drive.NEUTRAL
    }

    /**
     * A shock at L1 in the last 15 minutes: density up 1.8× or more and speed up 40 km/s or more
     * against the 30 minutes before it. Big, fast changes in Bz often follow.
     */
    fun shock(inp: Inputs, now: Long): Boolean {
        val recent = inp.solarWind.filter { now - it.time <= 15 * MIN_MS && it.density.isFinite() && !it.speed.isNaN() }
        val before = inp.solarWind.filter { now - it.time in (15 * MIN_MS + 1)..(45 * MIN_MS) && it.density.isFinite() && !it.speed.isNaN() }
        if (recent.size < 5 || before.size < 10) return false
        val n1 = recent.map { it.density }.sorted()[recent.size / 2]
        val n0 = before.map { it.density }.sorted()[before.size / 2]
        val v1 = recent.map { it.speed }.sorted()[recent.size / 2]
        val v0 = before.map { it.speed }.sorted()[before.size / 2]
        return n0 > 0 && n1 / n0 >= 1.8 && v1 - v0 >= 40
    }

    /** Typical density over the last 30 minutes at L1, cm⁻³. */
    fun density(inp: Inputs, now: Long): Double? {
        val r = inp.solarWind.filter { it.density.isFinite() && now - it.time <= 30 * MIN_MS }.map { it.density }.sorted()
        return if (r.size >= 3) r[r.size / 2] else null
    }

    /** Latest reading at L1, for display. */
    fun latest(inp: Inputs, now: Long): SwPoint? = inp.solarWind.lastOrNull()?.takeIf { now - it.time <= 30 * MIN_MS }
}
