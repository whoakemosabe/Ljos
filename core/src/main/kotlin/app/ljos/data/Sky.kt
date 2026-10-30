package app.ljos.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Weather that only the live sky uses: wind direction (which way the clouds drift), rain,
 * snow and visibility. A separate request from the clouds on purpose: if it fails, the
 * forecast itself is untouched.
 */
class SkySeries(
    val times: LongArray,
    /** Degrees the wind blows from: 0 north, 90 east. NaN if missing. */
    val windDir: FloatArray,
    /** mm in the hour. */
    val rain: FloatArray,
    /** cm in the hour. */
    val snow: FloatArray,
    /** metres. */
    val visibility: FloatArray,
) {
    fun indexAt(t: Long): Int {
        var best = -1
        var bestDiff = Long.MAX_VALUE
        for (i in times.indices) {
            val diff = t - times[i]
            if (diff in 0 until HOUR_MS) return i
            if (abs(diff) < bestDiff) { bestDiff = abs(diff); best = i }
        }
        return if (bestDiff <= 3 * HOUR_MS) best else -1
    }
}

object SkyFeed {
    fun url(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lon) +
            "&hourly=wind_direction_10m,rain,showers,snowfall,visibility" +
            "&timeformat=unixtime&timezone=GMT&past_days=1&forecast_days=2" +
            // Same DMI HARMONIE model as the cloud feed, so rain and cloud come from one forecast.
            "&models=dmi_seamless"

    fun parse(json: String): SkySeries? {
        val hourly = JSONObject(json.trim()).optJSONObject("hourly") ?: return null
        val time = hourly.optJSONArray("time") ?: return null
        val n = time.length()
        if (n == 0) return null
        val times = LongArray(n) { time.optLong(it) * 1000L }
        val rain = floats(hourly.optJSONArray("rain"), n)
        val showers = floats(hourly.optJSONArray("showers"), n)
        return SkySeries(
            times,
            floats(hourly.optJSONArray("wind_direction_10m"), n),
            FloatArray(n) { i -> listOf(rain[i], showers[i]).filter { !it.isNaN() }.sum() },
            floats(hourly.optJSONArray("snowfall"), n),
            floats(hourly.optJSONArray("visibility"), n),
        )
    }

    private fun floats(a: JSONArray?, n: Int) = FloatArray(n) { i ->
        if (a == null || i >= a.length() || a.isNull(i)) Float.NaN else a.optDouble(i, Double.NaN).toFloat()
    }
}
