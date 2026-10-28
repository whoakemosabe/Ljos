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
}
