package app.ljos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.data.HOUR_MS
import app.ljos.model.HourScore
import kotlin.math.max

/** Tonight's hours as bars. Grey caps from the top show cloud cover. Tap or drag to pick an hour. */
@Composable
fun HourStrip(
    hours: List<HourScore>,
    selected: Long?,
    now: Long,
    onSelect: ((Long) -> Unit)?,
    /** Tapping the bar that's already selected (e.g. to go back to the default hour). */
    onTapSelected: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = Muted, fontSize = 11.sp) }
    val n = hours.size
    val view = LocalView.current
    val current by rememberUpdatedState(selected)
    val pick: (Float, Float, Boolean) -> Unit = { x, width, tap ->
        if (n > 0 && onSelect != null) {
            val t = hours[(x / width * n).toInt().coerceIn(0, n - 1)].time
            when {
                t != current -> { Haptics.scrub(view); onSelect(t) }
                tap -> { Haptics.tap(view); onTapSelected() }
            }
        }
    }

    // While you scrub, a liquid-glass loupe rides above your finger and magnifies the bars,
    // like the iOS 26 slider thumb. (Android 13+.)
    val glass = android.os.Build.VERSION.SDK_INT >= 33
    val strip = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    var dragX by remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    val loupe by androidx.compose.animation.core.animateFloatAsState(
        if (dragX >= 0f) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "loupe",
    )
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth()) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(156.dp)
            .then(if (glass) Modifier.layerBackdrop(strip) else Modifier)
            .then(
                if (onSelect == null) Modifier else Modifier
                    .pointerInput(hours) {
                        detectTapGestures { o -> pick(o.x, size.width.toFloat(), true) }
                    }
                    .pointerInput(hours) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragX = o.x },
                            onDragEnd = { dragX = -1f },
                            onDragCancel = { dragX = -1f },
                        ) { change, _ ->
                            dragX = change.position.x.coerceIn(0f, size.width.toFloat())
                            pick(change.position.x, size.width.toFloat(), false)
                        }
                    }
            )
    ) {
        if (n == 0) return@Canvas
        val labelH = 28.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / n
        val barW = slot * 0.58f
        val r = CornerRadius(barW / 2f)

        hours.forEachIndexed { i, h ->
            val x = i * slot + (slot - barW) / 2f
            val isSel = h.time == selected
            val dim = if (selected == null || isSel) 1f else 0.5f

            // Track
            drawRoundRect(Color(0x10FFFFFF), Offset(x, 0f), Size(barW, chartH), r)
            // Cloud cap
            if (h.cloud > 0) {
                drawRoundRect(Color(0x26C9D6E8), Offset(x, 0f), Size(barW, chartH * h.cloud / 100f), r)
            }
            // Score bar
            val bh = max(chartH * h.score / 100f, barW)
            val col = scoreColor(h.score)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(col, col.copy(alpha = 0.3f)), startY = chartH - bh, endY = chartH),
                topLeft = Offset(x, chartH - bh),
                size = Size(barW, bh),
                cornerRadius = r,
                alpha = dim,
            )
            if (isSel) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(x - 2.dp.toPx(), -2.dp.toPx()),
                    size = Size(barW + 4.dp.toPx(), chartH + 4.dp.toPx()),
                    cornerRadius = CornerRadius(barW / 2f + 2.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                )
            }
            if (now >= h.time && now < h.time + HOUR_MS) {
                drawCircle(Green, radius = 2.5.dp.toPx(), center = Offset(x + barW / 2f, chartH + 5.dp.toPx()))
            }
            if (i % 2 == 0) {
                val tl = measurer.measure(Fmt.hour(h.time), labelStyle)
                drawText(tl, topLeft = Offset(x + barW / 2f - tl.size.width / 2f, chartH + 11.dp.toPx()))
            }
        }
    }
    if (glass && loupe > 0.01f) {
        val loupeSize = 60.dp
        androidx.compose.foundation.layout.Box(
            Modifier
                // Placed by layout (not a graphics transform) so the glass knows exactly what's under it.
                .offset {
                    val half = loupeSize.roundToPx() / 2
                    androidx.compose.ui.unit.IntOffset((if (dragX >= 0f) dragX.toInt() else 0) - half, 30.dp.roundToPx() - half)
                }
                .graphicsLayer { alpha = loupe }
                .size(loupeSize)
                .glassControl(
                    strip, androidx.compose.foundation.shape.CircleShape, lensHeight = 14.dp, lensAmount = 22.dp,
                    surface = Color(0x0FFFFFFF), magnify = 1.5f,
                    magnifyPivot = androidx.compose.ui.unit.DpOffset(loupeSize / 2, loupeSize / 2),
                )
        )
    }
    }
}
