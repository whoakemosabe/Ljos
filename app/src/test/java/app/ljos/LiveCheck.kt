package app.ljos

import app.ljos.data.Feeds
import app.ljos.data.Inputs
import app.ljos.data.Spots
import app.ljos.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

/**
 * Runs the real parsers and scoring against today's live feeds. Only in CI with LIVE_CHECK=1;
 * the summary ends up in the release notes so format changes in the feeds show up early.
 */
class LiveCheck {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 40_000
        c.setRequestProperty("User-Agent", "Ljos-CI/1.0")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    @Test fun liveFeedsParseAndScore() {
        assumeTrue(System.getenv("LIVE_CHECK") == "1")
        val out = StringBuilder()
        val now = System.currentTimeMillis()

        val kp = Feeds.parseKp(get(Feeds.KP_URL))
        val clouds = Feeds.parseClouds(get(Feeds.cloudsUrl(Spots.all)), Spots.all)
        val magSummary = Feeds.parseMagSummary(get(Feeds.MAG_SUMMARY_URL))
        val magMinute = Feeds.parseMagRtsw(get(Feeds.MAG_RTSW_URL))
        val wind = Feeds.parseWindSummary(get(Feeds.WIND_SUMMARY_URL))
        val mag = listOfNotNull(magSummary, magMinute).maxByOrNull { it.time }
        val kpNow = Feeds.parseKpNow(get(Feeds.KP_NOW_URL))
        val series = Feeds.parseSolarWind(get(Feeds.MAG_RTSW_URL), get(Feeds.WIND_RTSW_URL))
        val inp = Inputs(kp, clouds, mag, wind, now, kpNow = kpNow, solarWind = series)
        val speed = app.ljos.model.Live.speed(inp, now)
        val arriving = app.ljos.model.Live.arriving(inp, now, now - 20 * 60_000L, now)

        val night = Model.night(now, inp, zone = ZoneId.of("Atlantic/Reykjavik"))
        val st = Model.nowState(now, inp)

        out.appendLine("### Live feed check at build time")
        out.appendLine()
        out.appendLine("| Feed | Result |")
        out.appendLine("| --- | --- |")
        out.appendLine("| Kp forecast | ${kp.size} blocks, ${kp.count { it.predicted }} predicted, max ${kp.maxOfOrNull { it.kp }?.let { Fmt.one(it) }} |")
        out.appendLine("| Clouds | ${clouds.size}/${Spots.all.size} spots, ${clouds.values.firstOrNull()?.times?.size ?: 0} hours each |")
        out.appendLine("| Bz summary | ${magSummary?.let { "${Fmt.signed(it.bz)} nT, ${Fmt.ago(it.time, now)}" } ?: "missing"} |")
        out.appendLine("| Bz minute feed | ${magMinute?.let { "${Fmt.signed(it.bz)} nT, ${Fmt.ago(it.time, now)}" } ?: "missing"} |")
        out.appendLine("| Live Kp | ${kpNow?.let { "${Fmt.one(it.kp)}, ${Fmt.ago(it.time, now)}" } ?: "missing"} |")
        out.appendLine("| Minute solar wind | ${series.size} min, ${series.count { !it.speed.isNaN() }} with speed |")
        out.appendLine("| Travel time L1→Earth | ${speed?.let { "${app.ljos.model.Live.delayMs(it) / 60_000} min at ${it.toInt()} km/s" } ?: "—"} |")
        out.appendLine("| Arriving now | ${arriving?.let { "coupling ${it.coupling.toInt()}, Bz ${Fmt.signed(it.meanBz)} nT, ${(it.southFraction * 100).toInt()}% south" } ?: "—"} |")
        out.appendLine("| Wind speed | ${wind?.let { "${it.speed.toInt()} km/s, ${Fmt.ago(it.time, now)}" } ?: "missing"} |")
        out.appendLine()
        val peak = night.peak
        if (peak != null) {
            out.appendLine("**Tonight:** ${peak.score} (${Model.label(peak.score)}), peak ${Fmt.hhmm(peak.time)}, " +
                "Kp ${Fmt.one(peak.kp)}, ${Fmt.cloud(peak.cloud)}. Dark ${Fmt.hhmm(night.darkFrom!!)}–${Fmt.hhmm(night.darkUntil!!)}.")
            out.appendLine()
            out.appendLine("Hours: " + night.hours.joinToString(" · ") { "${Fmt.hour(it.time)} ${it.score}" })
            val best = Model.spotsAt(peak.time, inp, now).first()
            out.appendLine()
            out.appendLine("Best spot at peak: ${best.spot.name} (${best.score}, ${Fmt.cloud(best.cloud)})")
        } else {
            out.appendLine("**Tonight:** no dark hours")
        }
        out.appendLine()
        out.appendLine("Now: score ${st.score}, dark=${st.isDark}, look-up=${st.lookUp}")

        File("build").mkdirs()
        File("build/live-check.md").writeText(out.toString())

        assertTrue("Kp forecast parsed", kp.isNotEmpty())
        assertEquals("cloud series for every spot", Spots.all.size, clouds.size)
        assertTrue("some Bz reading", mag != null)
    }
}
