package app.ljos

import android.content.Context
import app.ljos.data.Spot
import app.ljos.data.Spots

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

    /** Last detected location; defaults to Njarðvík. */
    var home: Spot
        get() {
            if (!p.contains("home_lat")) return Spots.home
            return Spot(
                "home",
                p.getString("home_name", null) ?: "Your location",
                Double.fromBits(p.getLong("home_lat", 0L)),
                Double.fromBits(p.getLong("home_lon", 0L)),
                dark = false,
            )
        }
        set(v) = p.edit()
            .putLong("home_lat", v.lat.toRawBits())
            .putLong("home_lon", v.lon.toRawBits())
            .putString("home_name", v.name)
            .putLong("home_at", System.currentTimeMillis())
            .apply()

    val homeDetectedAt: Long get() = p.getLong("home_at", 0L)

    /** Optional read-only GitHub token, needed for update checks while the repo is private. */
    var githubToken: String
        get() = p.getString("github_token", "") ?: ""
        set(v) = p.edit().putString("github_token", v.trim()).apply()

    // Display
    var icelandic: Boolean
        get() = p.getBoolean("lang_is", false)
        set(v) = p.edit().putBoolean("lang_is", v).apply()

    var clock24: Boolean
        get() = p.getBoolean("clock24", true)
        set(v) = p.edit().putBoolean("clock24", v).apply()

    var miles: Boolean
        get() = p.getBoolean("miles", false)
        set(v) = p.edit().putBoolean("miles", v).apply()

    // Location
    /** When false, home stays fixed and is only changed with the Detect button. */
    var autoDetect: Boolean
        get() = p.getBoolean("auto_detect", true)
        set(v) = p.edit().putBoolean("auto_detect", v).apply()

    // Alerts
    var liveLockScreen: Boolean
        get() = p.getBoolean("live_lock", true)
        set(v) = p.edit().putBoolean("live_lock", v).apply()

    var quietOn: Boolean
        get() = p.getBoolean("quiet_on", false)
        set(v) = p.edit().putBoolean("quiet_on", v).apply()

    /** Hours of the day, 0..23. Quiet runs from [quietFrom] up to (not including) [quietTo], wrapping midnight. */
    var quietFrom: Int
        get() = p.getInt("quiet_from", 1)
        set(v) = p.edit().putInt("quiet_from", v).apply()

    var quietTo: Int
        get() = p.getInt("quiet_to", 7)
        set(v) = p.edit().putInt("quiet_to", v).apply()

    fun isQuiet(hour: Int): Boolean {
        if (!quietOn || quietFrom == quietTo) return false
        return if (quietFrom < quietTo) hour in quietFrom until quietTo else hour >= quietFrom || hour < quietTo
    }
}
