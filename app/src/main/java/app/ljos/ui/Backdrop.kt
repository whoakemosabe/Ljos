package app.ljos.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Dp

/**
 * Records whatever this node draws into [layer] (a GPU display list) and then draws it normally.
 * Glass pieces ([LiquidGlass]) then redraw the same pixels, frosted and bent, without composing
 * the content twice. The display list is shared, so the header stays in sync while scrolling.
 */
fun Modifier.backdropSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * Hides content under a header: fully transparent above [hiddenUntil], fading back to fully
 * visible by [visibleFrom]. Use on content with a see-through background that scrolls under a
 * header; place it before [backdropSource] if the glass should still see everything.
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
