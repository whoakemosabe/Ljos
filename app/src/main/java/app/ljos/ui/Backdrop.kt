package app.ljos.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Records whatever this node draws into [layer] (a GPU display list) and then draws it normally.
 * A [FrostHeader] can then redraw the same pixels, blurred, without composing the
 * content twice. The display list is shared, so the header stays in sync while scrolling.
 */
fun Modifier.backdropSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * One frosted-header look, shared by every screen so they all feel the same:
 * fully frosted behind the header's contents, then a short fixed fade into the sharp page.
 * Only the frosted height differs from place to place.
 */
object Frost {
    /** How far below the frosted part the blur takes to dissolve. */
    val Fade = 20.dp
    val MaxRadius = 40.dp
    const val TintAlpha = 0.6f
    const val Bands = 5
}

/**
 * Oppo Weather style header. Blur strength climbs smoothly towards the top instead of a blurred
 * copy simply fading out, so there's no "double image" in the fade.
 *
 * [Frost.Bands] blur layers are stacked: above [frostPx] every band is on (full frost); inside
 * the [Frost.Fade] below it the strongest band dissolves first and the gentlest reaches the very
 * bottom. Each band is blurred first and masked second, so the blur can never spill past its
 * mask and get cut off by the header's edge (that was the hard line under the settings header).
 * A tint in [base] (the surface colour behind) dims the frosted part for legibility.
 * Android 12+ blurs; older phones keep just the tint.
 */
@Composable
fun FrostHeader(
    layer: GraphicsLayer,
    /** Height of the fully frosted part in px, read at layout/draw time (may change with scroll). */
    frostPx: () -> Float,
    base: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fadePx = with(density) { Frost.Fade.toPx() }
    val zone = Modifier
        .fillMaxWidth()
        .layout { measurable, constraints ->
            val h = (frostPx() + fadePx).toInt().coerceAtLeast(0)
            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
            layout(p.width, h) { p.place(0, 0) }
        }
    if (Build.VERSION.SDK_INT >= 31) {
        val bleed = with(density) { (Frost.MaxRadius * 2).roundToPx() }
        for (i in 0 until Frost.Bands) {
            val t = i / (Frost.Bands - 1f)
            val radius = Frost.MaxRadius * (0.12f + 0.88f * t.pow(1.6f))
            Box(
                modifier
                    .then(zone)
                    .clipToBounds()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val h = size.height
                        if (h <= 0f) return@drawWithContent
                        val e = (h - 0.45f * fadePx * t) / h
                        val s = (e - 0.55f * fadePx / h).coerceAtLeast(0f)
                        drawRect(
                            Brush.verticalGradient(0f to Color.Black, s to Color.Black, e to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    }
            ) {
                // Blurred copy, a little taller than the zone so the blur sees what's just below
                // it instead of a smeared edge row.
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val h = constraints.maxHeight + bleed
                            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(p.width, constraints.maxHeight) { p.place(0, 0) }
                        }
                        .fillMaxWidth()
                        .graphicsLayer {
                            val r = radius.toPx()
                            renderEffect = BlurEffect(r, r, TileMode.Clamp)
                        }
                        .drawBehind { drawLayer(layer) }
                )
            }
        }
    }
    val tint = base.copy(alpha = Frost.TintAlpha)
    Box(
        modifier
            .then(zone)
            .drawWithContent {
                val h = size.height
                if (h <= 0f) return@drawWithContent
                val k = ((h - fadePx) / h).coerceIn(0f, 1f)
                drawRect(
                    Brush.verticalGradient(
                        // Eases off steadily from the top, so there's no dark "lid" over the sky.
                        0f to tint,
                        (k * 0.5f) to tint.copy(alpha = tint.alpha * 0.7f),
                        k to tint.copy(alpha = tint.alpha * 0.35f),
                        (k + (1f - k) * 0.5f) to tint.copy(alpha = tint.alpha * 0.1f),
                        1f to Color.Transparent,
                    )
                )
            }
    )
}

/**
 * Hides content under a header: fully transparent above [hiddenUntil], fading back to fully
 * visible by [visibleFrom]. Use on content with a see-through background that scrolls under a
 * [FrostHeader]; place it before [backdropSource] so the blur still sees everything.
 */
fun Modifier.fadeUnderHeader(hiddenUntil: Dp, visibleFrom: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val a = hiddenUntil.toPx() / size.height
        val b = visibleFrom.toPx() / size.height
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                a.coerceIn(0f, 1f) to Color.Transparent,
                b.coerceIn(0f, 1f) to Color.Black,
                1f to Color.Black,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
