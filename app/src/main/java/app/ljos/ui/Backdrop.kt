package app.ljos.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Records whatever this node draws into [layer] (a GPU display list) and then draws it normally.
 * A [ProgressiveBlurHeader] can then redraw the same pixels, blurred, without composing the
 * content twice. The display list is shared, so the header stays in sync while scrolling.
 */
fun Modifier.backdropSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * Oppo Weather style header. Blur strength climbs smoothly towards the top instead of a blurred
 * copy simply fading out, so there's no "double image" halfway down.
 *
 * Built from [bands] stacked blur layers: the gentlest reaches all the way to the bottom edge,
 * each stronger one covers a little less, and every band has a long soft mask so neighbours blend.
 * A tint goes on top for legibility, dissolving to nothing at the bottom. Android 12+ blurs;
 * older phones keep just the tint.
 */
@Composable
fun ProgressiveBlurHeader(
    layer: GraphicsLayer,
    height: Dp,
    modifier: Modifier = Modifier,
    /** Optional live height in px (e.g. shrinking with scroll); read at layout time only. */
    heightPx: (() -> Float)? = null,
    maxRadius: Dp = 36.dp,
    tint: Color = Color(0xB3050812),
    bands: Int = 5,
    /**
     * Optional fixed fade length in px, read at draw time. Everything above the last [feather] px
     * is fully frosted and the blur dissolves only inside it, so the fade hugs the header's
     * contents instead of growing with the header. Without it the fade is a share of the height.
     */
    featherPx: (() -> Float)? = null,
) {
    if (Build.VERSION.SDK_INT >= 31) {
        for (i in 0 until bands) {
            val t = if (bands == 1) 1f else i / (bands - 1f)
            val radius = maxRadius * (0.12f + 0.88f * t.pow(1.6f))
            // Each band is fully on over a generous top section and dissolves below it; stronger
            // bands stop a little higher. The area behind the title stays heavily frosted.
            val end = 1f - 0.32f * t          // where this band has fully faded out
            val solid = (end - 0.32f).coerceAtLeast(0f)
            Box(
                modifier
                    .fillMaxWidth()
                    .liveHeight(height, heightPx)
                    .clipToBounds()
                    .graphicsLayer {
                        val r = radius.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Clamp)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawLayer(layer)
                        val f = featherPx?.invoke()
                        val (a, b) = if (f == null || size.height <= 0f) solid to end else {
                            // Strongest band starts fading first, gentlest reaches the very bottom.
                            val h = size.height
                            val e = (h - 0.45f * f * t) / h
                            ((e - 0.55f * f / h).coerceAtLeast(0f)) to e
                        }
                        drawRect(
                            Brush.verticalGradient(0f to Color.Black, a to Color.Black, b to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    }
            )
        }
    }
    // Tint: strongest behind the status bar and title, then a long, eased dissolve.
    Box(
        modifier
            .fillMaxWidth()
            .liveHeight(height, heightPx)
            .drawWithContent {
                val f = featherPx?.invoke()
                if (f != null && size.height > 0f) {
                    val k = ((size.height - f) / size.height).coerceIn(0f, 1f)
                    drawRect(
                        Brush.verticalGradient(
                            0f to tint,
                            k to tint.copy(alpha = tint.alpha * 0.6f),
                            (k + (1f - k) * 0.5f) to tint.copy(alpha = tint.alpha * 0.18f),
                            1f to Color.Transparent,
                        )
                    )
                    return@drawWithContent
                }
                drawRect(
                    Brush.verticalGradient(
                        0f to tint,
                        0.35f to tint.copy(alpha = tint.alpha * 0.72f),
                        0.65f to tint.copy(alpha = tint.alpha * 0.3f),
                        0.85f to tint.copy(alpha = tint.alpha * 0.08f),
                        1f to Color.Transparent,
                    )
                )
            }
    )
}

/** Fixed height, or a height re-read during layout so scroll changes skip recomposition. */
private fun Modifier.liveHeight(height: Dp, heightPx: (() -> Float)?): Modifier =
    if (heightPx == null) this.height(height)
    else this.layout { measurable, constraints ->
        val h = heightPx().toInt().coerceAtLeast(0)
        val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(p.width, h) { p.place(0, 0) }
    }

/**
 * Hides content under a header: fully transparent above [hiddenUntil], fading back to fully
 * visible by [visibleFrom]. Use on content with a see-through background that scrolls under a
 * [ProgressiveBlurHeader]; place it before [backdropSource] so the blur still sees everything.
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
