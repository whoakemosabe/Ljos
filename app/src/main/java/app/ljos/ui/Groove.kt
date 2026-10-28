package app.ljos.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/*
 * Ljós grooves: the one recipe for every channel cut into a pane (hour bars, Kp blocks, factor
 * bars, the segmented control), on the phone web page, the lobby and here. The same numbers live
 * in style.css and lobby.css ("Grooves"):
 *   floor      black 30%
 *   over fill  top shadow 2dp fading over 3dp (black 65%), inner hairline black 28%,
 *              light catch along the bottom (white 16%)
 *   fill       lit along the top (white 50%, 1.5dp), white 12% hairline
 * The shading is drawn after the fill, so the channel still reads as cut in when it's full.
 */

private val GrooveFloor = Color.Black.copy(alpha = 0.30f)

/** The groove's floor. Draw before the fill. */
internal fun DrawScope.grooveFloor(topLeft: Offset, size: Size, radius: Float) {
    drawRoundRect(GrooveFloor, topLeft, size, CornerRadius(radius))
}

/** The groove's shading. Draw after the fill. */
internal fun DrawScope.grooveShade(topLeft: Offset, size: Size, radius: Float) {
    val px = 1.dp.toPx()
    val path = Path().apply { addRoundRect(RoundRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, CornerRadius(radius))) }
    clipPath(path) {
        val shadeH = minOf(size.height, 5.dp.toPx())
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.65f), 0.4f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent,
                startY = topLeft.y, endY = topLeft.y + shadeH,
            ),
            topLeft, Size(size.width, shadeH),
        )
        drawRect(Color.White.copy(alpha = 0.16f), Offset(topLeft.x, topLeft.y + size.height - px), Size(size.width, px))
    }
    drawRoundRect(
        Color.Black.copy(alpha = 0.28f), Offset(topLeft.x + px / 2, topLeft.y + px / 2), Size(size.width - px, size.height - px),
        CornerRadius((radius - px / 2).coerceAtLeast(0f)), style = Stroke(px),
    )
}

/** A fill sitting in a groove: lit along its top, with a faint hairline. Draw over the fill. */
internal fun DrawScope.fillLight(topLeft: Offset, size: Size, radius: Float) {
    val px = 1.dp.toPx()
    val path = Path().apply { addRoundRect(RoundRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, CornerRadius(radius))) }
    clipPath(path) {
        drawRect(Color.White.copy(alpha = 0.5f), topLeft, Size(size.width, 1.5.dp.toPx()))
    }
    drawRoundRect(
        Color.White.copy(alpha = 0.12f), Offset(topLeft.x + px / 2, topLeft.y + px / 2), Size(size.width - px, size.height - px),
        CornerRadius((radius - px / 2).coerceAtLeast(0f)), style = Stroke(px),
    )
}

/** A layout groove for Box channels: floor behind the content, shading over it. */
internal fun Modifier.ljosGroove(radius: androidx.compose.ui.unit.Dp): Modifier = drawWithContent {
    val r = radius.toPx()
    grooveFloor(Offset.Zero, size, r)
    drawContent()
    grooveShade(Offset.Zero, size, r)
}
