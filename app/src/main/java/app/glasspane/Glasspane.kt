package app.glasspane

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/*
 * Glasspane for Compose, on Kyant's Backdrop library:
 *     implementation("io.github.kyant0:backdrop:2.0.1")
 *
 * Record what's behind once, high in the tree:
 *     val backdrop = rememberLayerBackdrop()
 *     Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { Wallpaper() }
 * Then make anything glass:
 *     Box(Modifier.glass(backdrop, GlassPresets.Smoky))
 *
 * Layer order matches the web (SPEC.md § Layers): colour boost, contrast/clarity, frost, lens
 * (edge refraction, optional chromatic dispersion), then smoke, haze, sheen, hairline, rim
 * highlight, depth (inner bottom shade) and the drop shadow.
 *
 * The lens needs runtime shaders (Android 13+) and blur needs RenderEffect (Android 12+);
 * Kyant skips both on older phones, so the surface fills in with a near-solid tint there,
 * the same fallback the web uses without backdrop-filter.
 */

private val canBlur get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
private val canLens get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Make this element glass.
 * [shape] defaults to the style's radius (999.dp = pill). [lightAngle] overrides the style's,
 * so a whole screen can share one moving light (read a State in the lambda).
 */
fun Modifier.glass(
    backdrop: Backdrop,
    style: GlassStyle,
    shape: CornerBasedShape = RoundedCornerShape(style.radius),
    lightAngle: (() -> Float)? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        colorControls(brightness = style.brightness, contrast = style.contrast, saturation = style.saturation)
        blur(style.blur.toPx())
        if (style.refraction > 0.01f) {
            lens(
                refractionHeight = style.band.toPx(),
                refractionAmount = style.bend.toPx(),
                depthEffect = style.depthEffect,
                chromaticAberration = style.chromatic,
            )
        }
    },
    highlight = {
        Highlight(
            width = style.rimWidth,
            alpha = style.rimAlpha,
            style = HighlightStyle.Default(angle = lightAngle?.invoke() ?: style.lightAngle),
        )
    },
    shadow = {
        if (style.shadow <= 0f) null
        else Shadow(
            radius = style.shadowBlur,
            offset = DpOffset(0.dp, style.shadowY),
            color = style.shadowColor.copy(alpha = style.shadowAlpha),
        )
    },
    innerShadow = {
        // depth: thicker glass gets a darker inner bottom edge
        InnerShadow(
            radius = (1f + style.thickness * 2f).dp,
            offset = DpOffset(0.dp, -(1f + style.thickness).dp),
            color = Color.Black.copy(alpha = style.depthAlpha),
        )
    },
    onDrawSurface = {
        // smoke (near-solid where the phone can't blur)
        val smoke = if (canBlur) style.smokeAlpha else maxOf(style.smokeAlpha, 0.94f)
        drawRect(style.tint.copy(alpha = smoke))
        // milky haze from low clarity
        if (style.hazeAlpha > 0f) drawRect(Color.White.copy(alpha = style.hazeAlpha))
        // sheen over the top half
        if (style.sheen > 0f) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = style.sheen * 0.8f),
                    0.5f to Color.White.copy(alpha = 0f),
                ),
            )
        }
        // outer hairline (Ljós --edge), drawn on the rim
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, style.hairline.copy(alpha = style.hairAlpha * 1.6f), style = Stroke(1.dp.toPx()))
    },
)

/**
 * Coloured glass (Ljós .tinted): badges, score tiles, selected chips. Not a backdrop effect,
 * so it's cheap enough for lists. Fill 13%, top rim 75%, hairline 22% of [accent].
 */
fun Modifier.tintedGlass(accent: Color, shape: CornerBasedShape = RoundedCornerShape(GlassRadius.Control)): Modifier =
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, accent.copy(alpha = 0.13f))
        drawOutline(outline, accent.copy(alpha = 0.22f), style = Stroke(1.dp.toPx()))
        // lit top edge and shaded bottom edge
        val r = minOf(size.height / 2f, shape.topStart.toPx(size, this))
        drawLine(accent.copy(alpha = 0.75f), Offset(r, 0.75.dp.toPx()), Offset(size.width - r, 0.75.dp.toPx()), 1.5.dp.toPx())
        drawLine(Color.Black.copy(alpha = 0.28f), Offset(r, size.height - 0.5.dp.toPx()), Offset(size.width - r, size.height - 0.5.dp.toPx()), 1.dp.toPx())
    }

/** The one solid pane (Ljós Now flag / segmented thumb): lit on top, shaded underneath. */
fun Modifier.glassThumb(shape: CornerBasedShape = RoundedCornerShape(GlassRadius.Control)): Modifier =
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, Color(0xF0EEF3FB))
        val r = minOf(size.height / 2f, shape.topStart.toPx(size, this))
        drawLine(Color.White, Offset(r, 0.5.dp.toPx()), Offset(size.width - r, 0.5.dp.toPx()), 1.dp.toPx())
        drawLine(Color.Black.copy(alpha = 0.22f), Offset(r, size.height - 0.5.dp.toPx()), Offset(size.width - r, size.height - 0.5.dp.toPx()), 1.dp.toPx())
    }

/** Grooves: tracks cut into the pane (sliders, meters, segmented backgrounds). */
fun Modifier.glassGroove(shape: CornerBasedShape = RoundedCornerShape(GlassRadius.Control)): Modifier =
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, Color.Black.copy(alpha = 0.22f))
        val r = minOf(size.height / 2f, shape.topStart.toPx(size, this))
        drawLine(Color.Black.copy(alpha = 0.42f), Offset(r, 0.75.dp.toPx()), Offset(size.width - r, 0.75.dp.toPx()), 1.5.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.09f), Offset(r, size.height - 0.5.dp.toPx()), Offset(size.width - r, size.height - 0.5.dp.toPx()), 1.dp.toPx())
    }

/** True when this phone shows the full glass (lens + frost); false means the solid fallback. */
val glassIsFull: Boolean get() = canLens
