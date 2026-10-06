package app.ljos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
 * Dark basemap for the small location map, no API key needed.
 * Primary: Esri "World Dark Gray" canvas plus its label layer. Fallback: OpenStreetMap tiles,
 * colour-inverted to dark. Tiles are fetched one zoom level deeper than displayed for crisp
 * edges on high-density screens, composited once and cached on disk.
 */
object MapTiles {
    var attribution = "Esri, HERE, Garmin, © OpenStreetMap"
        private set

    /** [x], [y] are tile indices at [zoom]; each covers 256 / 2^(zoom - viewZoom) world px. */
    class Tile(val x: Int, val y: Int, val zoom: Int, val bitmap: Bitmap)

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

    /** Tiles covering a [widthDp]×[heightDp] map at view zoom [zoom] (1 map dp = 1 world px). */
    suspend fun load(
        context: Context, lat: Double, lon: Double, zoom: Int, widthDp: Float, heightDp: Float,
    ): List<Tile> = withContext(Dispatchers.IO) {
        val z = zoom + 1 // fetch deeper for sharpness
        val (cx, cy) = project(lat, lon, z)
        val w = widthDp * 2
        val h = heightDp * 2
        val x0 = floor((cx - w / 2) / 256).toInt()
        val x1 = floor((cx + w / 2) / 256).toInt()
        val y0 = floor((cy - h / 2) / 256).toInt()
        val y1 = floor((cy + h / 2) / 256).toInt()
        val n = 1 shl z
        val dir = File(context.cacheDir, "tiles").apply { mkdirs() }
        coroutineScope {
            (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
                .filter { (_, y) -> y in 0 until n }
                .map { (x, y) ->
                    async {
                        val wx = ((x % n) + n) % n
                        val file = File(dir, "v2_${z}_${wx}_$y.png")
                        if (!file.exists() || file.length() == 0L) fetchComposite(z, wx, y)?.let { bmp ->
                            File(dir, file.name + ".tmp").also { tmp ->
                                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                tmp.renameTo(file)
                            }
                        }
                        BitmapFactory.decodeFile(file.path)?.let { Tile(x, y, z, it) }
                    }
                }.awaitAll().filterNotNull()
        }
    }

    private fun fetchComposite(z: Int, x: Int, y: Int): Bitmap? {
        val base = get("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x")
        if (base != null) {
            val labels = get("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/$z/$y/$x")
            val out = base.copy(Bitmap.Config.ARGB_8888, true)
            if (labels != null) Canvas(out).drawBitmap(labels, null, android.graphics.Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
            attribution = "Esri, HERE, Garmin, © OpenStreetMap"
            return out
        }
        // Fallback: standard OSM tiles, inverted and cooled to read as a night map.
        val osm = get("https://tile.openstreetmap.org/$z/$x/$y.png") ?: return null
        val out = Bitmap.createBitmap(osm.width, osm.height, Bitmap.Config.ARGB_8888)
        val invert = ColorMatrix(
            floatArrayOf(
                -0.85f, 0f, 0f, 0f, 235f,
                0f, -0.85f, 0f, 0f, 240f,
                0f, 0f, -0.8f, 0f, 250f,
                0f, 0f, 0f, 1f, 0f,
            )
        ).apply { postConcat(ColorMatrix().apply { setSaturation(0.25f) }) }
        Canvas(out).drawBitmap(osm, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(invert) })
        attribution = "© OpenStreetMap contributors"
        return out
    }

    private fun get(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Ljos/1.0 (Android aurora app; github.com/whoakemosabe)")
        val bmp = if (c.responseCode in 200..299) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
        c.disconnect()
        bmp?.takeIf { it.width >= 128 }
    } catch (e: Exception) {
        null
    }
}
