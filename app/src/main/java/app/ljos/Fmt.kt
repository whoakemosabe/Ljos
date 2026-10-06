package app.ljos

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val hh = DateTimeFormatter.ofPattern("HH", Locale.US)

    fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hhmm)
    fun hour(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hh)
    fun one(d: Double): String = String.format(Locale.US, "%.1f", d)

    /** Uses a real minus sign so negative Bz reads cleanly. */
    fun signed(d: Double): String = (if (d < 0) "−" else "+") + one(abs(d))

    fun cloud(pct: Int): String = if (pct < 0) "clouds unknown" else "$pct% cloud"

    fun ago(ms: Long, now: Long): String {
        val mins = ((now - ms) / 60_000L).coerceAtLeast(0)
        return when {
            ms <= 0L -> "never"
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 48 * 60 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }
}
