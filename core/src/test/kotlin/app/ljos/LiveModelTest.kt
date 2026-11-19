package app.ljos

import app.ljos.data.CloudSeries
import app.ljos.data.Feeds
import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import app.ljos.data.KpPoint
import app.ljos.data.MIN_MS
import app.ljos.data.Spots
import app.ljos.data.SwPoint
import app.ljos.model.Live
import app.ljos.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveModelTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun clearSky(from: Long) = Spots.all.associate {
        it.id to CloudSeries(LongArray(96) { i -> from + i * HOUR_MS }, IntArray(96), IntArray(96), IntArray(96), IntArray(96))
    }

    /** Two hours of minute data ending at [end], all with the given field and speed. */
    private fun wind(end: Long, bz: Double, by: Double = 0.0, bt: Double = 8.0, v: Double = 500.0) =
        (0 until 120).map { i -> SwPoint(end - (119 - i) * MIN_MS, bz, by, bt, v) }

    @Test fun parsesLiveKp() {
        val json = """[{"time_tag":"2026-10-05T15:54:00","kp_index":2,"estimated_kp":2.33,"kp":"2P"},
            {"time_tag":"2026-10-05T21:51:00","kp_index":3,"estimated_kp":2.67,"kp":"3M"}]"""
        val k = Feeds.parseKpNow(json)
        assertEquals(2.67, k!!.kp, 1e-9)
        assertEquals(ms("2026-10-05T21:51:00Z"), k.time)
    }

    @Test fun joinsMagAndPlasmaByMinutePreferringActive() {
        val mag = """[
            {"time_tag":"2026-10-06T11:10:00","active":false,"bt":4.0,"by_gsm":1.0,"bz_gsm":-1.0},
            {"time_tag":"2026-10-06T11:10:00","active":true,"bt":6.0,"by_gsm":2.0,"bz_gsm":-5.0},
            {"time_tag":"2026-10-06T11:11:00","active":true,"bt":6.5,"by_gsm":2.5,"bz_gsm":-6.0}]"""
        val wind = """[{"time_tag":"2026-10-06T11:10:00","active":true,"proton_speed":520.0}]"""
        val s = Feeds.parseSolarWind(mag, wind)
        assertEquals(2, s.size)
        assertEquals(-5.0, s[0].bz, 1e-9)
        assertEquals(520.0, s[0].speed, 1e-9)
        assertTrue(s[1].speed.isNaN())
    }

    @Test fun northwardFieldHasNoCouplingSouthwardHasPlenty() {
        val north = Live.newell(SwPoint(0, bz = 8.0, by = 0.0, bt = 8.0, speed = 500.0), 500.0)
        val south = Live.newell(SwPoint(0, bz = -8.0, by = 0.0, bt = 8.0, speed = 500.0), 500.0)
        assertEquals(0.0, north, 1e-6)
        assertTrue("was $south", south > 10_000)
    }

    @Test fun travelTimeDependsOnSpeed() {
        assertEquals(50, (Live.delayMs(500.0) / MIN_MS).toInt())
        assertEquals(25, (Live.delayMs(1000.0) / MIN_MS).toInt())
    }

    @Test fun arrivalLooksBackByTheTravelTime() {
        val now = ms("2024-01-11T22:30:00Z")
        // Southward until 40 min ago, then north. At 500 km/s (50 min travel) Earth is still
        // getting the southward part right now.
        val series = wind(now, bz = -8.0).map { if (it.time > now - 40 * MIN_MS) it.copy(bz = 6.0) else it }
        val inp = Inputs(emptyList(), clearSky(now - 24 * HOUR_MS), null, null, now, solarWind = series)
        val a = Live.arriving(inp, now, now - 20 * MIN_MS, now)
        assertNotNull(a)
        assertTrue("meanBz ${a!!.meanBz}", a.meanBz < -7)
    }

    @Test fun sustainedSouthTriggersLookUpButABlipDoesNot() {
        val now = ms("2024-01-11T23:10:00Z")
        val kp = listOf(KpPoint(now - 24 * HOUR_MS, 3.0, true))
        val sustained = Inputs(kp, clearSky(now - 24 * HOUR_MS), null, null, now, solarWind = wind(now, bz = -8.0))
        assertTrue(Model.nowState(now, sustained).lookUp)

        // Quiet, with a single southward minute in the arriving window: not enough.
        val blip = wind(now, bz = 3.0).mapIndexed { i, p -> if (i == 60) p.copy(bz = -15.0) else p }
        val quiet = Inputs(kp, clearSky(now - 24 * HOUR_MS), null, null, now, solarWind = blip)
        assertFalse(Model.nowState(now, quiet).lookUp)
    }

    @Test fun weakSlowSouthDoesNotTriggerLookUpButStrongFastDoes() {
        val now = ms("2024-01-11T23:10:00Z")
        val kp = listOf(KpPoint(now - 24 * HOUR_MS, 3.0, true))
        // Bz −5 in a weak (Bt 5), slow (330 km/s) wind: coupling ~6,700, below the look-up limit.
        val weak = Inputs(kp, clearSky(now - 24 * HOUR_MS), null, null, now, solarWind = wind(now, bz = -5.0, bt = 5.0, v = 330.0))
        assertFalse(Model.nowState(now, weak).lookUp)
        assertEquals(Live.Drive.HELPING, Model.nowState(now, weak).drive)
        // The same Bz in a strong, fast wind: well above it.
        val strong = Inputs(kp, clearSky(now - 24 * HOUR_MS), null, null, now, solarWind = wind(now, bz = -5.0, bt = 12.0, v = 600.0))
        assertTrue(Model.nowState(now, strong).lookUp)
        assertEquals(Live.Drive.STRONG, Model.nowState(now, strong).drive)
    }

    @Test fun northFieldIsBlocking() {
        val now = ms("2024-01-11T23:10:00Z")
        val inp = Inputs(emptyList(), clearSky(now - 24 * HOUR_MS), null, null, now, solarWind = wind(now, bz = 4.0, by = 2.0, bt = 5.0, v = 400.0))
        assertEquals(Live.Drive.BLOCKING, Model.nowState(now, inp).drive)
    }

    @Test fun parsesDensityAndSpotsAShock() {
        val mag = """[{"time_tag":"2026-10-06T11:10:00","active":true,"bt":6.0,"by_gsm":2.0,"bz_gsm":-5.0}]"""
        val wind = """[{"time_tag":"2026-10-06T11:10:00","active":true,"proton_speed":520.0,"proton_density":7.5}]"""
        assertEquals(7.5, Feeds.parseSolarWind(mag, wind)[0].density, 1e-9)

        val now = ms("2024-01-11T23:10:00Z")
        val calm = wind(now, bz = -2.0).map { it.copy(density = 4.0, speed = 400.0) }
        val hit = calm.mapIndexed { i, p -> if (i >= 110) p.copy(density = 12.0, speed = 480.0) else p }
        assertFalse(Live.shock(Inputs(emptyList(), emptyMap(), null, null, now, solarWind = calm), now))
        assertTrue(Live.shock(Inputs(emptyList(), emptyMap(), null, null, now, solarWind = hit), now))
        assertEquals(1.6726e-6 * 4.0 * 400.0 * 400.0, Live.pressure(4.0, 400.0), 1e-9)
    }

    @Test fun liveKpReplacesForecastForTheCurrentHourOnly() {
        val now = ms("2024-01-11T22:10:00Z")
        val t = now - now % HOUR_MS
        val inp = Inputs(
            listOf(KpPoint(now - 24 * HOUR_MS, 1.0, true)), clearSky(now - 24 * HOUR_MS), null, null, now,
            kpNow = KpPoint(now - 5 * MIN_MS, 4.0, false),
        )
        assertEquals(4.0, Model.hourScore(t, Spots.home, inp, now).kp, 1e-9)
        assertEquals(2.5, Model.hourScore(t + HOUR_MS, Spots.home, inp, now).kp, 1e-9)
        assertEquals(1.0, Model.hourScore(t + 3 * HOUR_MS, Spots.home, inp, now).kp, 1e-9)
    }

    @Test fun twilightIsScoredCautiously() {
        assertEquals(0.15, Model.darkFactor(-6.01), 0.01)
        assertEquals(0.45, Model.darkFactor(-9.0), 1e-9)
        assertEquals(0.80, Model.darkFactor(-12.0), 1e-9)
        assertEquals(1.0, Model.darkFactor(-15.0), 1e-9)
    }

    @Test fun cloudsComeFromTheIcelandCoveringModel() {
        assertTrue(Feeds.cloudsUrl(Spots.all).contains("models=dmi_seamless"))
    }

    @Test fun noMinuteDataMeansNoArrival() {
        val inp = Inputs(emptyList(), emptyMap(), null, null, 0L)
        assertNull(Live.arriving(inp, 0L, 0L, HOUR_MS))
    }
}
