package app.ljos

import android.content.Context

class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("ljos", Context.MODE_PRIVATE)

    var tonightAlerts: Boolean
        get() = p.getBoolean("tonight_alerts", true)
        set(v) = p.edit().putBoolean("tonight_alerts", v).apply()

    var lookUpAlerts: Boolean
        get() = p.getBoolean("lookup_alerts", true)
        set(v) = p.edit().putBoolean("lookup_alerts", v).apply()

    /** Evening heads-up fires when tonight's peak reaches this score. */
    var threshold: Int
        get() = p.getInt("threshold", 50)
        set(v) = p.edit().putInt("threshold", v).apply()

    var lastTonightKey: String?
        get() = p.getString("last_tonight_key", null)
        set(v) = p.edit().putString("last_tonight_key", v).apply()

    var lastLookUpAt: Long
        get() = p.getLong("last_lookup_at", 0L)
        set(v) = p.edit().putLong("last_lookup_at", v).apply()
}
