package app.ljos

import app.ljos.data.Spot
import app.ljos.data.Spots
import app.ljos.data.Updater
import app.ljos.model.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceAndUpdateTest {
    @Test fun nearHomeUsesReykjanesSpots() {
        val keflavik = Spot("home", "Keflavík", 64.0049, -22.5624, dark = false)
        val spots = Spots.forHome(keflavik)
        assertEquals(keflavik, spots.first())
        assertTrue(spots.any { it.id == "gardskagi" })
    }

    @Test fun awayFromHomeUsesARingOfEight() {
        val akureyri = Spot("home", "Akureyri", 65.6835, -18.0878, dark = false)
        val spots = Spots.forHome(akureyri)
        assertEquals(9, spots.size)
        spots.drop(1).forEach { s ->
            val d = Geo.km(akureyri.lat, akureyri.lon, s.lat, s.lon)
            assertTrue("${s.name} was $d km", d in 11.0..26.0)
        }
    }

    @Test fun versionComparison() {
        assertTrue(Updater.isNewer("v1.0.5", "1.0.3"))
        assertTrue(Updater.isNewer("v1.0.10", "1.0.9"))
        assertFalse(Updater.isNewer("v1.0.3", "1.0.3"))
        assertFalse(Updater.isNewer("v1.0.2", "1.0.3"))
    }
}
