package app.ljos.server

import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import app.ljos.data.MIN_MS
import app.ljos.model.Astro
import app.ljos.model.HourScore
import app.ljos.model.Model
import app.ljos.model.Night
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * The forecast as JSON for the web app. Times are epoch milliseconds; the page formats them in
 * [zone]. Score bands are keys (`GOOD`, `POSSIBLE`…), never display text, so the page words them.
 *
 * Contract: fields are only ever added. Anything a deployed screen reads stays put.
 */
object Api {
    const val VERSION = 1

    /** Data older than this is shown as stale on screen. */
    const val STALE_MS = 90 * MIN_MS

    data class Place(val name: String, val lat: Double, val lon: Double, val title: String? = null)

    fun forecast(inp: Inputs, now: Long, zone: ZoneId, place: Place, problems: List<String>, sky: SkySeries? = null): JSONObject {
        val ctx = Ctx(inp, now, sky)
        val hourStart = now - now % HOUR_MS
        val tonight = Model.night(now, inp, zone)
        val tomorrow = Model.tomorrow(now, inp, zone)
        val st = Model.nowState(now, inp)
        val current = Model.hourScore(hourStart, inp.home, inp, now)
        val peak = tonight.peak
        val focus = if (peak != null && peak.time + HOUR_MS > now) peak.time else hourStart
        val weather = Model.weatherAt(inp, inp.home, focus)
        val moon = Model.moonTimeline(tonight.start, tonight.end, inp.home)

        return JSONObject()
            .put("version", VERSION)
            .put("now", now)
            .put("updatedAt", inp.updatedAt)
            .put("stale", inp.updatedAt <= 0 || now - inp.updatedAt > STALE_MS)
            .put("tz", zone.id)
            .put("place", JSONObject()
                .put("name", place.name)
                .putOrNull("title", place.title)
                .num("lat", place.lat, 4)
                .num("lon", place.lon, 4))
            .put("current", JSONObject()
                .put("score", st.score)
                .put("chance", Model.chance(st.score).name)
                .put("isDark", st.isDark)
                .put("cloud", st.cloud)
                .num("bz", st.bz, 1)
                .put("bzFresh", st.bzFresh)
                .putOrNull("bzSustained", st.bzSustained?.let { round(it, 1) })
                .put("lookUp", st.lookUp)
                .putOrNull("etaMinutes", st.etaMinutes)
                .putOrNull("clearer", st.clearerSpot?.let {
                    JSONObject().put("id", it.spot.id).put("name", it.spot.name)
                        .put("score", it.score).put("cloud", it.cloud).num("distanceKm", it.distanceKm, 1)
                })
                .put("hour", hour(current, ctx)))
            .put("tonight", night(tonight, ctx))
            .put("tomorrow", night(tomorrow, ctx, withSpots = false))
            .put("spots", JSONArray().also { arr ->
                Model.spotsAt(focus, inp, now).forEach {
                    arr.put(JSONObject().put("id", it.spot.id).put("name", it.spot.name)
                        .num("lat", it.spot.lat, 4).num("lon", it.spot.lon, 4)
                        .put("dark", it.spot.dark).num("distanceKm", it.distanceKm, 1))
                }
            })
            .put("spotsAt", focus)
            .put("outlook", JSONArray().also { arr ->
                Model.kpOutlook(inp.kp, now, zone).forEach { day ->
                    arr.put(JSONObject()
                        .put("dayStart", day.dayStart)
                        .num("maxKp", day.maxKp, 2)
                        .put("blocks", JSONArray().also { b ->
                            day.blocks.forEach { k -> b.put(JSONObject().put("time", k.time).num("kp", k.kp, 2).put("predicted", k.predicted)) }
                        }))
                }
            })
            .put("moon", JSONObject()
                .num("illumination", moon.illumination, 3)
                .put("waxing", moon.waxing)
                .putOrNull("rise", moon.rise)
                .putOrNull("set", moon.set)
                .put("up", JSONArray().also { arr -> moon.upSegments.forEach { (a, b) -> arr.put(JSONArray().put(a).put(b)) } }))
            .put("weather", weather?.let {
                JSONObject().put("time", focus).num("temp", it.temp.toDouble(), 1)
                    .num("feels", it.feels.toDouble(), 1).num("wind", it.wind.toDouble(), 1)
            } ?: JSONObject.NULL)
            .put("solarWind", JSONArray().also { arr ->
                // Last two hours at L1, every second minute: enough for a Bz sparkline.
                inp.solarWind.filter { now - it.time <= 2 * HOUR_MS }
                    .filterIndexed { i, _ -> i % 2 == 0 }
                    .forEach { p -> if (p.bz.isFinite()) arr.put(JSONArray().put(p.time).put(round(p.bz, 1))) }
            })
            .put("problems", JSONArray(problems))
    }

    private class Ctx(val inp: Inputs, val now: Long, val sky: SkySeries?)

    private fun night(n: Night, ctx: Ctx, withSpots: Boolean = true): JSONObject =
        JSONObject()
            .put("start", n.start)
            .put("end", n.end)
            .putOrNull("darkFrom", n.darkFrom)
            .putOrNull("darkUntil", n.darkUntil)
            .put("peak", n.peak?.let { hour(it, ctx, withSpots = false) } ?: JSONObject.NULL)
            .put("hours", JSONArray().also { arr -> n.hours.forEach { arr.put(hour(it, ctx, withSpots)) } })

    /** One hour. With [withSpots], each comparison spot as `[id, score, cloud, rank]`, best first. */
    private fun hour(h: HourScore, ctx: Ctx, withSpots: Boolean = false): JSONObject {
        val inp = ctx.inp
        val now = ctx.now
        val o = JSONObject()
            .put("time", h.time)
            .put("score", h.score)
            .put("chance", Model.chance(h.score).name)
            .put("cloud", h.cloud)
            .num("kp", h.kp, 2)
            .num("activity", h.factors.activity, 3)
            .num("clear", h.factors.clear, 3)
            .num("moon", h.factors.moon, 3)
            .num("dark", h.factors.dark, 3)
            .num("sunAlt", h.sunAlt, 1)
            .num("moonAlt", h.moonAlt, 1)
            .num("moonIllum", h.moonIllum, 3)
            .put("live", h.live)
        // For the lobby sky: where the sun and moon are, cloud by layer, and the weather.
        val mid = h.time + HOUR_MS / 2
        o.num("sunAz", Astro.sunAzimuth(mid, inp.home.lat, inp.home.lon), 1)
            .num("moonAz", Astro.moonAzimuth(mid, inp.home.lat, inp.home.lon), 1)
        inp.clouds[inp.home.id]?.let { cs ->
            val i = cs.indexAt(h.time)
            if (i >= 0) {
                o.put("cloudLow", cs.low.getOrElse(i) { -1 }).put("cloudMid", cs.mid.getOrElse(i) { -1 })
                    .put("cloudHigh", cs.high.getOrElse(i) { -1 })
                cs.wind.getOrNull(i)?.let { o.num("wind", it.toDouble(), 1) }
            }
        }
        ctx.sky?.let { s ->
            val i = s.indexAt(h.time)
            if (i >= 0) {
                o.num("windDir", s.windDir[i].toDouble(), 0).num("rain", s.rain[i].toDouble(), 2)
                    .num("snow", s.snow[i].toDouble(), 2).num("visibility", s.visibility[i].toDouble(), 0)
            }
        }
        if (withSpots) {
            o.put("spots", JSONArray().also { arr ->
                Model.spotsAt(h.time, inp, now).forEach {
                    arr.put(JSONArray().put(it.spot.id).put(it.score).put(it.cloud).put(it.rank))
                }
            })
        }
        return o
    }

    fun error(code: String, message: String): JSONObject =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message))

    private fun round(v: Double, places: Int): Double {
        var f = 1.0
        repeat(places) { f *= 10 }
        return (v * f).roundToLong() / f
    }

    /** org.json rejects NaN and infinity, and drops keys set to null; this writes JSON null instead. */
    private fun JSONObject.num(key: String, v: Double?, places: Int): JSONObject =
        put(key, if (v != null && v.isFinite()) round(v, places) else JSONObject.NULL)

    private fun JSONObject.putOrNull(key: String, v: Any?): JSONObject = put(key, v ?: JSONObject.NULL)
}
