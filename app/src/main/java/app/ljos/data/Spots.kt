package app.ljos.data

import app.ljos.model.Geo
import kotlin.math.cos

object Spots {
    /** Default home until the phone reports a location. */
    val home = Spot("home", "Njarðvík", 63.9740, -22.5490, dark = false)

    private val reykjanes = listOf(
        Spot("gardskagi", "Garðskagi lighthouse", 64.0817, -22.6897, dark = true),
        Spot("hafnir", "Hafnir", 63.9325, -22.6830, dark = true),
        Spot("reykjanesviti", "Reykjanesviti", 63.8160, -22.7040, dark = true),
        Spot("kleifarvatn", "Kleifarvatn", 63.9300, -21.9900, dark = true),
    )

    /** Njarðvík plus the hand-picked Reykjanes spots. */
    val all: List<Spot> = listOf(home) + reykjanes

    /**
     * Spots to compare for a given home. Near Reykjanes, the hand-picked dark places.
     * Anywhere else, eight points around you: four at 12 km and four at 25 km.
     */
    fun forHome(home: Spot): List<Spot> {
        if (Geo.km(home.lat, home.lon, Spots.home.lat, Spots.home.lon) <= 40.0) return listOf(home) + reykjanes
        val ring = listOf(
            Triple("N", 0.0, 12.0), Triple("E", 90.0, 12.0), Triple("S", 180.0, 12.0), Triple("W", 270.0, 12.0),
            Triple("NE", 45.0, 25.0), Triple("SE", 135.0, 25.0), Triple("SW", 225.0, 25.0), Triple("NW", 315.0, 25.0),
        )
        return listOf(home) + ring.map { (dir, bearing, km) ->
            val rad = Math.toRadians(bearing)
            val dLat = km * cos(rad) / 111.32
            val dLon = km * kotlin.math.sin(rad) / (111.32 * cos(Math.toRadians(home.lat)))
            Spot("ring-$dir", "${km.toInt()} km $dir", home.lat + dLat, home.lon + dLon, dark = true)
        }
    }
}
