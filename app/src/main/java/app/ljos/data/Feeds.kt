package app.ljos.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Feed URLs and tolerant parsers. Parsers are pure so they can be unit tested. */
object Feeds {
    const val KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"
    const val MAG_SUMMARY_URL = "https://services.swpc.noaa.gov/products/summary/solar-wind-mag-field.json"
    const val WIND_SUMMARY_URL = "https://services.swpc.noaa.gov/products/summary/solar-wind-speed.json"
    const val MAG_RTSW_URL = "https://services.swpc.noaa.gov/json/rtsw/rtsw_mag_1m.json"
    const val WIND_RTSW_URL = "https://services.swpc.noaa.gov/json/rtsw/rtsw_wind_1m.json"
    const val KP_NOW_URL = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"

    fun cloudsUrl(spots: List<Spot>): String {
        val lats = spots.joinToString(",") { String.format(Locale.US, "%.4f", it.lat) }
        val lons = spots.joinToString(",") { String.format(Locale.US, "%.4f", it.lon) }
        return "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high" +
            "&timeformat=unixtime&timezone=GMT&past_days=1&forecast_days=3" +
            // DMI HARMONIE: 2 km model covering Iceland, blended with ECMWF beyond ~2.5 days.
            "&models=dmi_seamless"
    }

    /** NOAA times are UTC, with or without a trailing Z, sometimes with a space instead of T. */
    fun parseTime(s: String): Long {
        val t = s.trim().replace(' ', 'T').removeSuffix("Z")
        val core = if (t.length > 19) t.substring(0, 19) else t
        return LocalDateTime.parse(core).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    /** Handles both the current array-of-objects format and the older array-of-arrays one. */
    fun parseKp(json: String): List<KpPoint> {
        val out = ArrayList<KpPoint>()
        val arr = JSONArray(json.trim())
        for (i in 0 until arr.length()) {
            when (val e = arr.get(i)) {
                is JSONObject -> {
                    val kp = num(e, "kp", "Kp") ?: continue
                    val tt = str(e, "time_tag") ?: continue
                    out += KpPoint(parseTime(tt), kp, e.optString("observed") == "predicted")
                }
                is JSONArray -> {
                    val tt = e.optString(0)
                    if (tt == "time_tag" || tt.isBlank()) continue
                    val kp = e.optString(1).toDoubleOrNull() ?: continue
                    out += KpPoint(parseTime(tt), kp, e.optString(2) == "predicted")
                }
            }
        }
        return out.sortedBy { it.time }
    }

    fun parseMagSummary(json: String): MagReading? {
        val o = firstObject(json) ?: return null
        val bz = num(o, "bz_gsm", "Bz", "bz") ?: return null
        val bt = num(o, "bt", "Bt") ?: Double.NaN
        val tt = str(o, "time_tag", "TimeStamp") ?: return null
        return MagReading(parseTime(tt), bz, bt)
    }

    fun parseWindSummary(json: String): WindReading? {
        val o = firstObject(json) ?: return null
        val speed = num(o, "proton_speed", "WindSpeed", "speed") ?: return null
        val tt = str(o, "time_tag", "TimeStamp") ?: return null
        return WindReading(parseTime(tt), speed)
    }

    /** Minute feed lists several spacecraft; prefer the one NOAA marks active, then the newest. */
    fun parseMagRtsw(json: String): MagReading? {
        val arr = JSONArray(json.trim())
        var best: MagReading? = null
        var bestActive = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val bz = num(o, "bz_gsm") ?: continue
            val tt = str(o, "time_tag") ?: continue
            val t = try { parseTime(tt) } catch (e: Exception) { continue }
            val active = o.optBoolean("active", false)
            val r = MagReading(t, bz, num(o, "bt") ?: Double.NaN)
            val current = best
            if (current == null || (active && !bestActive) || (active == bestActive && t > current.time)) {
                best = r
                bestActive = active
            }
        }
        return best
    }

    /** Open-Meteo returns an array when several coordinates are requested, an object for one. */
    fun parseClouds(json: String, spots: List<Spot>): Map<String, CloudSeries> {
        val t = json.trim()
        val objs: List<JSONObject> = if (t.startsWith("[")) {
            val a = JSONArray(t)
            List(a.length()) { a.getJSONObject(it) }
        } else {
            listOf(JSONObject(t))
        }
        val out = HashMap<String, CloudSeries>()
        for (i in 0 until minOf(objs.size, spots.size)) {
            val hourly = objs[i].optJSONObject("hourly") ?: continue
            val time = hourly.optJSONArray("time") ?: continue
            val n = time.length()
            val times = LongArray(n) { idx ->
                when (val v = time.opt(idx)) {
                    is Number -> v.toLong() * 1000L
                    else -> parseTime(v.toString())
                }
            }
            out[spots[i].id] = CloudSeries(
                times,
                ints(hourly, "cloud_cover", n),
                ints(hourly, "cloud_cover_low", n),
                ints(hourly, "cloud_cover_mid", n),
                ints(hourly, "cloud_cover_high", n),
            )
        }
        return out
    }

    /** Latest minute of NOAA's estimated planetary Kp. */
    fun parseKpNow(json: String): KpPoint? {
        val arr = JSONArray(json.trim())
        var best: KpPoint? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kp = num(o, "estimated_kp", "kp_index") ?: continue
            val tt = str(o, "time_tag") ?: continue
            val t = try { parseTime(tt) } catch (e: Exception) { continue }
            val current = best
            if (current == null || t > current.time) best = KpPoint(t, kp, predicted = false)
        }
        return best
    }

    /**
     * Joins the minute magnetic-field and plasma feeds into one series, oldest first.
     * Several spacecraft report at once; for each minute the one NOAA marks active wins.
     */
    fun parseSolarWind(magJson: String, windJson: String?): List<SwPoint> {
        data class M(val bz: Double, val by: Double, val bt: Double, val active: Boolean)
        val mag = HashMap<Long, M>()
        val ma = JSONArray(magJson.trim())
        for (i in 0 until ma.length()) {
            val o = ma.optJSONObject(i) ?: continue
            val bz = num(o, "bz_gsm") ?: continue
            val by = num(o, "by_gsm") ?: continue
            val bt = num(o, "bt") ?: continue
            val t = try { parseTime(str(o, "time_tag") ?: continue) } catch (e: Exception) { continue }
            val minute = t - t % 60_000L
            val active = o.optBoolean("active", false)
            val prev = mag[minute]
            if (prev == null || (active && !prev.active)) mag[minute] = M(bz, by, bt, active)
        }
        val speed = HashMap<Long, Pair<Double, Boolean>>()
        if (windJson != null) {
            val wa = JSONArray(windJson.trim())
            for (i in 0 until wa.length()) {
                val o = wa.optJSONObject(i) ?: continue
                val v = num(o, "proton_speed") ?: continue
                val t = try { parseTime(str(o, "time_tag") ?: continue) } catch (e: Exception) { continue }
                val minute = t - t % 60_000L
                val active = o.optBoolean("active", false)
                val prev = speed[minute]
                if (prev == null || (active && !prev.second)) speed[minute] = v to active
            }
        }
        return mag.entries.sortedBy { it.key }.map { (t, m) ->
            SwPoint(t, m.bz, m.by, m.bt, speed[t]?.first ?: Double.NaN)
        }
    }

    private fun ints(hourly: JSONObject, key: String, n: Int): IntArray {
        val a = hourly.optJSONArray(key)
        return IntArray(n) { idx ->
            if (a == null || idx >= a.length() || a.isNull(idx)) -1 else a.optDouble(idx, -1.0).toInt()
        }
    }

    private fun num(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val d = when (val v = o.opt(k)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null && !d.isNaN()) return d
        }
        return null
    }

    private fun str(o: JSONObject, vararg keys: String): String? =
        keys.firstOrNull { o.has(it) && !o.isNull(it) }?.let { o.getString(it) }

    private fun firstObject(json: String): JSONObject? {
        val t = json.trim()
        return when {
            t.startsWith("[") -> JSONArray(t).optJSONObject(0)
            t.startsWith("{") -> JSONObject(t)
            else -> null
        }
    }
}
