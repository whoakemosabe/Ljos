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

class WhatsNewTest {
    @org.junit.Test
    fun readsBulletsUnderWhatsNew() {
        val body = """
            ## What's new

            - Update card on home with what changed
            - Pull to refresh checks for updates
              right away

            Tap **ljos.apk** below on your phone to install or update.
            - not a change
        """.trimIndent()
        org.junit.Assert.assertEquals(
            listOf("Update card on home with what changed", "Pull to refresh checks for updates right away"),
            app.ljos.data.Updater.whatsNew(body),
        )
    }

    @org.junit.Test
    fun oldReleasesWithoutSectionGiveNothing() {
        org.junit.Assert.assertEquals(emptyList<String>(), app.ljos.data.Updater.whatsNew("Tap **ljos.apk** below.\n\n- Kp 1.3"))
    }
}
