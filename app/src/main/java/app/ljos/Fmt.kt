package app.ljos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display settings: language, clock and distance units. Backed by Compose state so the whole
 * UI re-renders the moment a setting changes; worker and widgets call [load] first.
 */
object L {
    var icelandic by mutableStateOf(false)
    var clock24 by mutableStateOf(true)
    var miles by mutableStateOf(false)
    /** Background sky: calm (default) or live, drawn from the forecast. */
    var liveSky by mutableStateOf(false)

    /** Picks the English or Icelandic text. */
    fun t(en: String, isl: String): String = if (icelandic) isl else en

    fun load(context: Context) {
        val p = Prefs(context)
        icelandic = p.icelandic
        clock24 = p.clock24
        miles = p.miles
        liveSky = p.liveSky
    }
}

object Fmt {
    private val h24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val h12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val hh24 = DateTimeFormatter.ofPattern("HH", Locale.US)
    private val hh12 = DateTimeFormatter.ofPattern("ha", Locale.US)

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    fun hhmm(ms: Long): String = at(ms).format(if (L.clock24) h24 else h12)
    fun hour(ms: Long): String = at(ms).format(if (L.clock24) hh24 else hh12).lowercase(Locale.US)
    fun one(d: Double): String = String.format(Locale.US, "%.1f", d)

    /** Uses a real minus sign so negative Bz reads cleanly. */
    fun signed(d: Double): String = (if (d < 0) "−" else "+") + one(abs(d))

    fun cloud(pct: Int): String =
        if (pct < 0) L.t("clouds unknown", "skýjahula óþekkt") else L.t("$pct% cloud", "$pct% skýjað")

    fun distance(km: Double): String =
        if (L.miles) "${(km * 0.621371).roundToInt()} mi" else "${km.roundToInt()} km"

    fun ago(ms: Long, now: Long): String {
        val mins = ((now - ms) / 60_000L).coerceAtLeast(0)
        return when {
            ms <= 0L -> L.t("never", "aldrei")
            mins < 1 -> L.t("just now", "rétt í þessu")
            mins < 60 -> L.t("${mins}m ago", "fyrir $mins mín")
            mins < 48 * 60 -> L.t("${mins / 60}h ago", "fyrir ${mins / 60} klst")
            else -> L.t("${mins / 1440}d ago", "fyrir ${mins / 1440} d")
        }
    }

    /** Short weekday, e.g. "Wed" / "mið". */
    fun weekday(ms: Long): String {
        val d = at(ms).dayOfWeek.value
        val en = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val isl = listOf("mán", "þri", "mið", "fim", "fös", "lau", "sun")
        return (if (L.icelandic) isl else en)[d - 1]
    }
}
