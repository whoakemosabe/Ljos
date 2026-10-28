package app.ljos

import app.ljos.data.Feeds
import app.ljos.data.Spots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Samples copied from the live feeds' current formats (October 2026). */
class FeedsTest {
    @Test fun parsesKpObjects() {
        val json = """[
            {"time_tag":"2026-09-29T00:00:00","kp":2.00,"observed":"observed","noaa_scale":null},
            {"time_tag":"2026-09-29T03:00:00","kp":0.33,"observed":"observed","noaa_scale":null},
            {"time_tag":"2026-10-08T21:00:00","kp":2.00,"observed":"predicted","noaa_scale":null}
        ]"""
        val kp = Feeds.parseKp(json)
        assertEquals(3, kp.size)
        assertEquals(Instant.parse("2026-09-29T00:00:00Z").toEpochMilli(), kp[0].time)
        assertEquals(0.33, kp[1].kp, 1e-9)
        assertTrue(kp[2].predicted)
    }

    @Test fun parsesLegacyKpArrays() {
        val json = """[["time_tag","kp","observed","noaa_scale"],["2025-01-01 00:00:00","2.67","observed",null]]"""
        val kp = Feeds.parseKp(json)
        assertEquals(1, kp.size)
        assertEquals(2.67, kp[0].kp, 1e-9)
    }

    @Test fun parsesMagAndWindSummaries() {
        val mag = Feeds.parseMagSummary("""[{"bt": 5, "bz_gsm": -1, "time_tag": "2026-10-05T17:49:00Z"}]""")
        assertNotNull(mag)
        assertEquals(-1.0, mag!!.bz, 1e-9)
        assertEquals(Instant.parse("2026-10-05T17:49:00Z").toEpochMilli(), mag.time)
        val wind = Feeds.parseWindSummary("""[{"proton_speed": 496, "time_tag": "2026-10-06T05:46:00Z"}]""")
        assertEquals(496.0, wind!!.speed, 1e-9)
    }

    @Test fun minuteFeedPrefersActiveSpacecraft() {
        val json = """[
            {"time_tag": "2026-10-06T11:11:02", "active": false, "source": "IMAP", "bt": 4.17, "bz_gsm": -2.06},
            {"time_tag": "2026-10-06T11:10:00", "active": true, "source": "SOLAR1", "bt": 6.0, "bz_gsm": -7.5},
            {"time_tag": "2026-10-06T11:09:00", "active": true, "source": "SOLAR1", "bt": 6.0, "bz_gsm": null}
        ]"""
        val r = Feeds.parseMagRtsw(json)
        assertEquals(-7.5, r!!.bz, 1e-9)
    }

    @Test fun parsesOpenMeteoMultiLocation() {
        val json = """[
            {"latitude":63.97,"longitude":-22.55,"hourly":{"time":[1759708800,1759712400],
              "cloud_cover":[10,100],"cloud_cover_low":[0,90],"cloud_cover_mid":[10,null],"cloud_cover_high":[20,50]}},
            {"latitude":64.08,"longitude":-22.69,"hourly":{"time":[1759708800,1759712400],
              "cloud_cover":[0,5],"cloud_cover_low":[0,0],"cloud_cover_mid":[0,0],"cloud_cover_high":[0,10]}}
        ]"""
        val m = Feeds.parseClouds(json, Spots.all.take(2))
        assertEquals(2, m.size)
        val home = m.getValue("home")
        assertEquals(1759708800_000L, home.times[0])
        assertEquals(-1, home.mid[1])
        assertEquals(0, home.indexAt(1759708800_000L + 30 * 60_000))
    }

    @Test fun cloudsUrlUsesDotDecimals() {
        val url = Feeds.cloudsUrl(Spots.all)
        assertTrue(url.contains("latitude=63.9740,"))
        assertTrue(!url.contains(";"))
    }
}
