package app.ljos.ui

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Records whatever this node draws into [layer] (a GPU display list) and then draws it normally.
 * A [ProgressiveBlurHeader] elsewhere can then redraw the same pixels, blurred, without composing
 * the content twice. The display list is shared, so the header stays in sync while scrolling.
 */
fun Modifier.backdropSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * Oppo-weather style header: the content behind is blurred hardest at the top and fades to sharp
 * towards the bottom edge, with no visible line. Must share its top-left corner with the source.
 * Android 12+ blurs; older phones just get the tint.
 */
@Composable
fun ProgressiveBlurHeader(
    layer: GraphicsLayer,
    height: Dp,
    modifier: Modifier = Modifier,
    radius: Dp = 22.dp,
    tint: Color = Color(0x99050812),
) {
    if (Build.VERSION.SDK_INT >= 31) {
        Box(
            modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .graphicsLayer {
                    val r = radius.toPx()
                    renderEffect = BlurEffect(r, r, TileMode.Clamp)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawLayer(layer)
                    // Keep the blurred copy at the top, fade it out downward.
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawWithContent {
                drawRect(Brush.verticalGradient(0f to tint, 0.6f to tint.copy(alpha = tint.alpha * 0.45f), 1f to Color.Transparent))
            }
    )
}
