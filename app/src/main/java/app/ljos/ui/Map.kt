package app.ljos.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.data.MapTiles
import app.ljos.data.Spot
import app.ljos.model.Geo
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Dark map centred on home, with the comparison spots. With [scores], pins glow in their score
 * colour, the best one pulses, and tapping a pin calls [onSelect].
 */
@Composable
internal fun SpotMap(
    home: Spot,
    spots: List<Spot>,
    modifier: Modifier,
    scores: Map<String, Int>? = null,
    bestId: String? = null,
    selectedId: String? = null,
    onSelect: ((Spot) -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val shape = RoundedCornerShape(18.dp)
    val measurer = rememberTextMeasurer()
    val pulse = rememberInfiniteTransition(label = "pins")
    val ring by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "ring",
    )
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Color(0xFF0A1322))
            .border(1.dp, Color(0x1FFFFFFF), shape)
    ) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val radiusKm = spots.maxOf { Geo.km(home.lat, home.lon, it.lat, it.lon) }.coerceAtLeast(8.0)
        val zoom = remember(home.lat, radiusKm, hDp) { MapTiles.zoomFor(home.lat, radiusKm, hDp / 2f) }
        var tiles by remember { mutableStateOf<List<MapTiles.Tile>>(emptyList()) }
        LaunchedEffect(home.lat, home.lon, zoom, wDp, hDp) {
            tiles = MapTiles.load(context, home.lat, home.lon, zoom, wDp, hDp)
        }
        val fade by animateFloatAsState(if (tiles.isEmpty()) 0f else 1f, tween(700), label = "tiles")
        val images = remember(tiles) { tiles.map { it to it.bitmap.asImageBitmap() } }
        val selectedGlow by animateFloatAsState(if (selectedId != null) 1f else 0f, tween(300, easing = FastOutSlowInEasing), label = "sel")

        fun screen(px: Float, lat: Double, lon: Double): Offset {
            val (cx, cy) = MapTiles.project(home.lat, home.lon, zoom)
            val (wx, wy) = MapTiles.project(lat, lon, zoom)
            return Offset(((wx - cx) * px).toFloat() + wDp * px / 2f, ((wy - cy) * px).toFloat() + hDp * px / 2f)
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (onSelect == null) Modifier else Modifier.pointerInput(spots) {
                        detectTapGestures { tap ->
                            val px = size.width / wDp
                            val hit = spots.drop(1).minByOrNull { s ->
                                val p = screen(px, s.lat, s.lon)
                                hypot(p.x - tap.x, p.y - tap.y)
                            }
                            if (hit != null) {
                                val p = screen(px, hit.lat, hit.lon)
                                if (hypot(p.x - tap.x, p.y - tap.y) < 32.dp.toPx()) {
                                    Haptics.tap(view)
                                    onSelect(hit)
                                }
                            }
                        }
                    }
                )
        ) {
            val px = size.width / wDp
            val (cx, cy) = MapTiles.project(home.lat, home.lon, zoom)
            images.forEach { (t, img) ->
                val tw = 256.0 / (1 shl (t.zoom - zoom))
                val ox = ((t.x * tw - (cx - wDp / 2)) * px).toFloat()
                val oy = ((t.y * tw - (cy - hDp / 2)) * px).toFloat()
                val side = (tw * px).roundToInt() + 1
                drawImage(
                    img,
                    dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                    dstSize = IntSize(side, side),
                    alpha = fade,
                    filterQuality = FilterQuality.Medium,
                )
            }
            drawRect(Color(0x260A1A3A))
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99050812)), radius = size.maxDimension * 0.75f))

            spots.drop(1).forEach { s ->
                val p = screen(px, s.lat, s.lon)
                val score = scores?.get(s.id)
                val col = if (score != null) scoreColor(score) else Teal
                if (s.id == bestId) {
                    // Slow expanding ring on the best spot
                    drawCircle(col.copy(alpha = 0.5f * (1f - ring)), (8f + 18f * ring).dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                }
                val sel = s.id == selectedId
                drawCircle(col.copy(alpha = 0.22f + 0.2f * (if (sel) selectedGlow else 0f)), (if (sel) 13 else 10).dp.toPx(), p)
                drawCircle(col, (if (sel) 5.5f else 4f).dp.toPx(), p)
                if (score != null) {
                    val label = measurer.measure(
                        score.toString(),
                        TextStyle(color = col, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    )
                    drawText(label, topLeft = Offset(p.x + 9.dp.toPx(), p.y - label.size.height / 2f))
                } else {
                    drawCircle(Color(0xFF0A1322), 1.4.dp.toPx(), p)
                }
            }
            // Home pin
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Green.copy(alpha = 0.14f), 24.dp.toPx(), c)
            val head = c + Offset(0f, -15.dp.toPx())
            val pin = Path().apply {
                moveTo(c.x, c.y)
                lineTo(head.x - 7.5.dp.toPx(), head.y + 3.dp.toPx())
                lineTo(head.x + 7.5.dp.toPx(), head.y + 3.dp.toPx())
                close()
            }
            drawCircle(Color(0x66000000), 3.dp.toPx(), c + Offset(0f, 1.dp.toPx()))
            drawPath(pin, Green)
            drawCircle(Green, 8.5.dp.toPx(), head)
            drawCircle(Color(0xFF0A1322), 3.2.dp.toPx(), head)
        }
        Text(
            MapTiles.attribution, color = Color(0x80E8F1FF), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )
    }
}
