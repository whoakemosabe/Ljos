package app.ljos.model

import app.ljos.data.Inputs
import app.ljos.data.MIN_MS
import app.ljos.data.SwPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin

/**
 * Live solar wind, done properly:
 *  - it's measured at L1, ~1.5 million km out, so it reaches Earth after a delay set by its speed;
 *  - what drives aurora is the Newell coupling function, not Bz alone:
 *        dΦ/dt = v^(4/3) · Bt^(2/3) · sin^(8/3)(θ/2),  θ = IMF clock angle atan2(By, Bz)
 *  - and it has to *stay* southward for a while, so we average over windows, not single minutes.
 */
object Live {
    private const val L1_KM = 1_500_000.0

    /** What's arriving at Earth over a time window. */
    data class Arrival(
        /** Mean Newell coupling, (km/s)^(4/3)·nT^(2/3). Quiet ~1–3k, active ~8k+, storm 15k+. */
        val coupling: Double,
        val meanBz: Double,
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
        return Arrival(
            coupling = couplings.average(),
            meanBz = pts.map { it.bz }.average(),
            southFraction = pts.count { it.bz < 0 }.toDouble() / pts.size,
            delayMs = d,
            minutes = pts.size,
        )
    }

    /** Activity nudge from coupling: northward/quiet pulls down a little, strong driving pushes up. */
    fun boost(a: Arrival): Double = ((a.coupling - 4000.0) / 12_000.0 * 0.45).coerceIn(-0.15, 0.4)

    /** Latest reading at L1, for display. */
    fun latest(inp: Inputs, now: Long): SwPoint? = inp.solarWind.lastOrNull()?.takeIf { now - it.time <= 30 * MIN_MS }
}
