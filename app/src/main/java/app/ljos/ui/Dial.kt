package app.ljos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.data.HOUR_MS
import app.ljos.model.HourScore
import app.ljos.model.MoonTimeline
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val START_DEG = 120f   // dusk, lower left
private const val SWEEP_DEG = 300f   // round to dawn, lower right; the gap sits at the bottom

/**
 * The night as a ring, dusk to dawn: one coloured segment per hour, a pale gold arc while the
 * moon is up, and a knob at "now". Touch the ring and drag to explore; let go and the knob springs
 * back to now. [center] gets the hour under your finger (or null at rest).
 */
@Composable
internal fun NightDial(
    hours: List<HourScore>,
    moon: MoonTimeline?,
    now: Long,
    onScrub: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    center: @Composable BoxScope.(scrubbed: HourScore?) -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val n = hours.size
    val start = hours.first().time
    val end = hours.last().time + HOUR_MS
    val nowFrac = ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    val knob = remember { Animatable(nowFrac) }
    var dragging by remember { mutableStateOf(false) }
    var scrubIndex by remember { mutableIntStateOf(-1) }
    val restFrac by rememberUpdatedState(nowFrac)
    val scrubCallback by rememberUpdatedState(onScrub)

    LaunchedEffect(nowFrac) { if (!dragging) knob.animateTo(nowFrac, spring(dampingRatio = 0.8f, stiffness = 120f)) }

    Box(
        modifier.pointerInput(hours) {
            val ringR = min(size.width, size.height) / 2f - 34.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            fun fracAt(p: Offset): Float {
                var deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                if (deg < 0) deg += 360f
                var rel = (deg - START_DEG + 360f) % 360f
                if (rel > SWEEP_DEG) rel = if (rel > SWEEP_DEG + (360f - SWEEP_DEG) / 2f) 0f else SWEEP_DEG
                return rel / SWEEP_DEG
            }
            fun update(p: Offset) {
                val f = fracAt(p)
                scope.launch { knob.snapTo(f) }
                val idx = floor(f * n).toInt().coerceIn(0, n - 1)
                if (idx != scrubIndex) {
                    scrubIndex = idx
                    Haptics.scrub(view)
                    scrubCallback(hours[idx].time)
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val r = hypot(down.position.x - c.x, down.position.y - c.y)
                // Only the ring itself is grabbable; elsewhere the page scrolls normally.
                if (r < ringR - 40.dp.toPx() || r > ringR + 40.dp.toPx()) return@awaitEachGesture
                down.consume()
                dragging = true
                update(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    update(ch.position)
                    ch.consume()
                }
                dragging = false
                scrubIndex = -1
                scrubCallback(null)
                Haptics.settle(view)
                scope.launch { knob.animateTo(restFrac, spring(dampingRatio = 0.55f, stiffness = 170f)) }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ringR = min(size.width, size.height) / 2f - 34.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(c.x - ringR, c.y - ringR)
            val arcSize = Size(ringR * 2, ringR * 2)
            val stroke = 13.dp.toPx()

            drawArc(Color(0x14FFFFFF), START_DEG, SWEEP_DEG, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

            val per = SWEEP_DEG / n
            val gap = min(2.2f, per * 0.25f)
            hours.forEachIndexed { i, h ->
                val active = i == scrubIndex
                val col = scoreColor(h.score)
                val alpha = if (h.score == 0) 0.25f else 0.55f + 0.45f * (h.score / 100f)
                val w = if (active) stroke * 1.45f else stroke
                drawArc(
                    col.copy(alpha = if (scrubIndex >= 0 && !active) alpha * 0.55f else alpha),
                    START_DEG + per * i + gap / 2f, per - gap, false,
                    Offset(c.x - ringR, c.y - ringR), arcSize,
                    style = Stroke(w, cap = StrokeCap.Butt),
                )
            }

            // Moon up: thin gold arc just inside the ring
            if (moon != null) moon.upSegments.forEach { (a, b) ->
                val f0 = ((a - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                val f1 = ((b - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                if (f1 - f0 > 0.005f) {
                    val mr = ringR - stroke / 2f - 6.dp.toPx()
                    drawArc(
                        Color(0xFFF3E7C1).copy(alpha = 0.35f + 0.55f * moon.illumination.toFloat()),
                        START_DEG + SWEEP_DEG * f0, SWEEP_DEG * (f1 - f0), false,
                        Offset(c.x - mr, c.y - mr), Size(mr * 2, mr * 2),
                        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // Hour labels every 3 hours, outside the ring so the centre stays clear
            val labelStyle = TextStyle(color = Faint, fontSize = 10.sp)
            hours.forEachIndexed { i, h ->
                if (i % 3 != 0) return@forEachIndexed
                val ang = Math.toRadians((START_DEG + per * (i + 0.5f)).toDouble())
                val lr = ringR + stroke / 2f + 12.dp.toPx()
                val tl = measurer.measure(Fmt.hour(h.time), labelStyle)
                drawText(
                    tl,
                    topLeft = Offset(
                        c.x + (lr * cos(ang)).toFloat() - tl.size.width / 2f,
                        c.y + (lr * sin(ang)).toFloat() - tl.size.height / 2f,
                    ),
                )
            }

            // Knob
            val ka = Math.toRadians((START_DEG + SWEEP_DEG * knob.value).toDouble())
            val kp = Offset(c.x + (ringR * cos(ka)).toFloat(), c.y + (ringR * sin(ka)).toFloat())
            drawCircle(Color.White.copy(alpha = if (dragging) 0.22f else 0.12f), (if (dragging) 17 else 13).dp.toPx(), kp)
            drawCircle(Color.White, 7.dp.toPx(), kp)
            drawCircle(NightBg, 2.6.dp.toPx(), kp)
        }
        center(if (scrubIndex >= 0) hours[scrubIndex] else null)
    }
}
