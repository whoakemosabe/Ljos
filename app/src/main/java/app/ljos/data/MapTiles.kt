package app.ljos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Dark basemap tiles (CARTO "dark_all", OpenStreetMap data) for the small location map.
 * Tiles are cached on disk, so the map only downloads once per area.
 */
object MapTiles {
    const val ATTRIBUTION = "© OpenStreetMap · © CARTO"

    class Tile(val x: Int, val y: Int, val bitmap: Bitmap)

    /** Position in "world pixels" at [zoom], 256 px per tile. */
    fun project(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val n = 256.0 * (1 shl zoom)
        val x = (lon + 180.0) / 360.0 * n
        val r = Math.toRadians(lat)
        val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
        return x to y
    }

    /** Most zoomed-in level that still fits [radiusKm] in half the map height. */
    fun zoomFor(lat: Double, radiusKm: Double, halfHeightDp: Float): Int {
        for (z in 12 downTo 5) {
            val metersPerPx = 156_543.03 * cos(Math.toRadians(lat)) / (1 shl z)
            if (radiusKm * 1000 * 1.15 <= metersPerPx * halfHeightDp) return z
        }
        return 5
    }

    /** Tiles covering a [widthDp]×[heightDp] map centred on lat/lon, one map dp = one tile px. */
    suspend fun load(
        context: Context, lat: Double, lon: Double, zoom: Int, widthDp: Float, heightDp: Float,
    ): List<Tile> = withContext(Dispatchers.IO) {
        val (cx, cy) = project(lat, lon, zoom)
        val x0 = floor((cx - widthDp / 2) / 256).toInt()
        val x1 = floor((cx + widthDp / 2) / 256).toInt()
        val y0 = floor((cy - heightDp / 2) / 256).toInt()
        val y1 = floor((cy + heightDp / 2) / 256).toInt()
        val max = (1 shl zoom) - 1
        val dir = File(context.cacheDir, "tiles").apply { mkdirs() }
        coroutineScope {
            (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
                .filter { (_, y) -> y in 0..max }
                .map { (x, y) ->
                    async {
                        val wx = ((x % (max + 1)) + (max + 1)) % (max + 1)
                        val file = File(dir, "dark_${zoom}_${wx}_$y.png")
                        if (!file.exists() || file.length() == 0L) {
                            try {
                                val sub = "abcd"[(wx + y) % 4]
                                val c = URL("https://$sub.basemaps.cartocdn.com/dark_all/$zoom/$wx/$y@2x.png")
                                    .openConnection() as HttpURLConnection
                                c.connectTimeout = 10_000
                                c.readTimeout = 15_000
                                c.setRequestProperty("User-Agent", "Ljos/1.0 (Android; personal aurora app)")
                                if (c.responseCode in 200..299) {
                                    val tmp = File(dir, file.name + ".tmp")
                                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                                    tmp.renameTo(file)
                                }
                                c.disconnect()
                            } catch (e: Exception) { }
                        }
                        BitmapFactory.decodeFile(file.path)?.let { Tile(x, y, it) }
                    }
                }.awaitAll().filterNotNull()
        }
    }
}
