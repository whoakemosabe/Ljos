package app.ljos.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
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

private val GrooveFloor = Color.Black.copy(alpha = 0.32f)

/** A rounded rect with its own top and bottom corner radii (hour bars stand square on the floor). */
private fun rrect(x: Float, y: Float, w: Float, h: Float, top: Float, bottom: Float): Path = Path().apply {
    val t = CornerRadius(top.coerceAtLeast(0f))
    val b = CornerRadius(bottom.coerceAtLeast(0f))
    addRoundRect(RoundRect(x, y, x + w, y + h, topLeftCornerRadius = t, topRightCornerRadius = t, bottomRightCornerRadius = b, bottomLeftCornerRadius = b))
}

/** The groove's floor. Draw before the fill. [bottom] is the bottom corner radius (defaults to [radius]). */
internal fun DrawScope.grooveFloor(topLeft: Offset, size: Size, radius: Float, bottom: Float = radius) {
    drawPath(rrect(topLeft.x, topLeft.y, size.width, size.height, radius, bottom), GrooveFloor)
}

/**
 * The groove's shading. Draw after the fill. [lip] adds the light catching the bottom edge, inside
 * and just below; channels that stand square on a floor (the hour bars) leave it off.
 */
internal fun DrawScope.grooveShade(topLeft: Offset, size: Size, radius: Float, bottom: Float = radius, lip: Boolean = true) {
    val px = 1.dp.toPx()
    val path = rrect(topLeft.x, topLeft.y, size.width, size.height, radius, bottom)
    clipPath(path) {
        // The web's inset shadow (0 2px 3px black 65%): soft, falling off over ~7dp under the top lip.
        val shadeH = minOf(size.height, 7.dp.toPx())
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.7f), 0.3f to Color.Black.copy(alpha = 0.38f),
                0.65f to Color.Black.copy(alpha = 0.1f), 1f to Color.Transparent,
                startY = topLeft.y, endY = topLeft.y + shadeH,
            ),
            topLeft, Size(size.width, shadeH),
        )
        // Light catching the bottom inner edge (white 16%).
        if (lip) drawRect(Color.White.copy(alpha = 0.16f), Offset(topLeft.x, topLeft.y + size.height - px), Size(size.width, px))
    }
    // Dark inner rim (black 30%), so the channel's walls read.
    drawPath(
        rrect(topLeft.x + px / 2, topLeft.y + px / 2, size.width - px, size.height - px, radius - px / 2, bottom - px / 2),
        Color.Black.copy(alpha = 0.3f), style = Stroke(px),
    )
    // The pane's light catching the lip just below the cut (the web's 0 1px 0 white 7%): drawn
    // outside the channel only.
    if (lip) clipPath(path, clipOp = ClipOp.Difference) {
        drawPath(rrect(topLeft.x, topLeft.y + px, size.width, size.height, radius, bottom), Color.White.copy(alpha = 0.09f))
    }
}

/** A fill sitting in a groove: lit along its top, with a faint hairline. Draw over the fill. */
internal fun DrawScope.fillLight(topLeft: Offset, size: Size, radius: Float, bottom: Float = radius) {
    val px = 1.dp.toPx()
    clipPath(rrect(topLeft.x, topLeft.y, size.width, size.height, radius, bottom)) {
        drawRect(Color.White.copy(alpha = 0.5f), topLeft, Size(size.width, 1.5.dp.toPx()))
    }
    drawPath(
        rrect(topLeft.x + px / 2, topLeft.y + px / 2, size.width - px, size.height - px, radius - px / 2, bottom - px / 2),
        Color.White.copy(alpha = 0.12f), style = Stroke(px),
    )
}

/** A layout groove for Box channels: floor behind the content, shading over it. */
internal fun Modifier.ljosGroove(radius: androidx.compose.ui.unit.Dp): Modifier = drawWithContent {
    val r = radius.toPx()
    grooveFloor(Offset.Zero, size, r)
    drawContent()
    grooveShade(Offset.Zero, size, r)
}
