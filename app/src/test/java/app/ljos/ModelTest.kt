package app.ljos

import app.ljos.data.CloudSeries
import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import app.ljos.data.KpPoint
import app.ljos.data.MagReading
import app.ljos.data.Spots
import app.ljos.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ModelTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun clouds(from: Long, hours: Int, pct: Int) = CloudSeries(
        LongArray(hours) { from + it * HOUR_MS },
        IntArray(hours) { pct }, IntArray(hours) { pct }, IntArray(hours) { pct }, IntArray(hours) { pct },
    )

    private fun inputs(at: Long, kp: Double, cloudPct: Int, mag: MagReading? = null): Inputs {
        val from = at - 24 * HOUR_MS
        return Inputs(
            kp = listOf(KpPoint(from, kp, true)),
            clouds = Spots.all.associate { it.id to clouds(from, 96, cloudPct) },
            mag = mag,
            wind = null,
            updatedAt = at,
        )
    }

    @Test fun kpMappingIsMonotonic() {
        var last = -1.0
        for (i in 0..18) {
            val v = Model.activityFromKp(i * 0.5)
            assertTrue(v >= last)
            last = v
        }
        assertEquals(0.5, Model.activityFromKp(2.0), 1e-9)
        assertEquals(0.7, Model.activityFromKp(3.0), 1e-9)
        assertEquals(1.0, Model.activityFromKp(6.0), 1e-9)
    }

    @Test fun daylightZeroesTheScore() {
        assertEquals(0.0, Model.darkFactor(10.0), 0.0)
        assertEquals(0.0, Model.darkFactor(-5.9), 0.0)
        assertEquals(1.0, Model.darkFactor(-20.0), 0.0)
    }

    // New moon night in January: dark, moon down, clear sky, Kp 3 → exactly 70.
    @Test fun clearDarkMoonlessKp3Scores70() {
        val t = ms("2024-01-11T22:00:00Z")
        val h = Model.hourScore(t, Spots.home, inputs(t, 3.0, 0), now = t - 12 * HOUR_MS)
        assertEquals(70, h.score)
    }

    @Test fun overcastScoresZero() {
        val t = ms("2024-01-11T22:00:00Z")
        val h = Model.hourScore(t, Spots.home, inputs(t, 5.0, 100), now = t - 12 * HOUR_MS)
        assertEquals(0, h.score)
    }

    @Test fun southwardBzBoostsTheCurrentHour() {
        val t = ms("2024-01-11T22:00:00Z")
        val now = t + 10 * 60_000
        val quiet = Model.hourScore(t, Spots.home, inputs(t, 2.0, 0), now)
        val stormy = Model.hourScore(t, Spots.home, inputs(t, 2.0, 0, MagReading(now - 60_000, -10.0, 12.0)), now)
        assertTrue(stormy.score > quiet.score)
        assertTrue(stormy.live)
    }

    @Test fun octoberNightHasDarkHoursAndAPeak() {
        val now = ms("2026-10-06T12:00:00Z")
        val night = Model.night(now, inputs(now, 3.0, 20), zone = ZoneOffset.UTC)
        assertTrue(night.hours.size in 10..18)
        assertNotNull(night.peak)
        assertTrue(night.darkFrom!! > now)
    }

    @Test fun lookUpFiresOnlyWhenDarkClearAndSouth() {
        val t = ms("2024-01-11T23:10:00Z")
        val south = MagReading(t - 60_000, -9.0, 11.0)
        assertTrue(Model.nowState(t, inputs(t, 3.0, 10, south)).lookUp)
        assertTrue(!Model.nowState(t, inputs(t, 3.0, 95, south)).lookUp)
        assertTrue(!Model.nowState(t, inputs(t, 3.0, 10, MagReading(t - 60_000, 4.0, 6.0))).lookUp)
    }

    @Test fun spotsAreRankedBestFirst() {
        val t = ms("2024-01-11T22:00:00Z")
        val ranked = Model.spotsAt(t, inputs(t, 3.0, 0), t - 12 * HOUR_MS)
        assertEquals(Spots.all.size, ranked.size)
        // Same sky everywhere, so a dark spot must beat town lights at home.
        assertTrue(ranked.first().spot.dark)
    }
}
