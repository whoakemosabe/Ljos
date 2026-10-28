package app.ljos

import app.ljos.model.Astro
import app.ljos.model.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AstroTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // Reykjavík. Noon altitude at the June solstice is 90 - 64.15 + 23.44 ≈ 49.3°.
    @Test fun sunIsHighAtSummerSolsticeNoon() {
        assertEquals(49.3, Astro.sunAltitude(ms("2024-06-21T13:30:00Z"), 64.15, -21.94), 1.5)
    }

    @Test fun sunIsFarBelowAtWinterSolsticeMidnight() {
        assertEquals(-49.3, Astro.sunAltitude(ms("2024-12-21T01:30:00Z"), 64.15, -21.94), 1.5)
    }

    @Test fun fullMoonIsLit() {
        assertTrue(Astro.moonIllumination(ms("2024-01-25T17:54:00Z")) > 0.97)
    }

    @Test fun newMoonIsDark() {
        assertTrue(Astro.moonIllumination(ms("2024-01-11T11:57:00Z")) < 0.03)
    }

    @Test fun gardskagiIsAboutTwelveKmFromNjardvik() {
        val d = Geo.km(63.9740, -22.5490, 64.0817, -22.6897)
        assertTrue("was $d", d in 10.0..15.0)
    }

    @Test fun sunIsSouthAtMiddayAndNorthAtMidnight() {
        // Reykjavik's solar noon is ~13:30 UTC; at midnight the sun is below the north horizon.
        assertEquals(180.0, Astro.sunAzimuth(ms("2024-06-21T13:30:00Z"), 64.15, -21.94), 5.0)
        val night = Astro.sunAzimuth(ms("2024-12-21T01:30:00Z"), 64.15, -21.94)
        assertTrue(night < 10.0 || night > 350.0)
        // Rising in the east-ish, setting in the west-ish.
        assertTrue(Astro.sunAzimuth(ms("2024-03-20T08:30:00Z"), 64.15, -21.94) in 70.0..120.0)
    }

    @Test fun moonAzimuthIsACompassAngle() {
        val az = Astro.moonAzimuth(ms("2024-01-25T23:00:00Z"), 64.15, -21.94)
        assertTrue(az in 0.0..360.0)
        // Full moon around local midnight sits roughly south.
        assertEquals(180.0, Astro.moonAzimuth(ms("2024-01-26T01:30:00Z"), 64.15, -21.94), 30.0)
    }
}
