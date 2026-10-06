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
}
