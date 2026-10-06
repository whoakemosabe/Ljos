package app.ljos.ui

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.effects.lens

/*
 * Liquid glass, built on Kyant's Backdrop library (io.github.kyant0:backdrop), the most faithful
 * open-source take on iOS 26's material for Compose. It records the page behind (layerBackdrop)
 * and redraws it through a chain of effects: a touch of extra colour, a light frost, and the
 * library's lens, which refracts a rounded-rect glass edge with real depth and chromatic
 * dispersion. On top of that we add only what Ljós needs: a soft dissolve at the bottom of the
 * header, a whisper of tint, and a glint that follows the phone's tilt.
 *
 * The lens and the dissolve need Android 13; older phones get a plain tint.
 */

/** Fades the frosted copy out over the pane's last stretch, (1 - x)², so there's no edge. */
private const val Dissolve = """
uniform shader content;
uniform float2 offset;
uniform float fadeTop;
uniform float fadeBottom;

half4 main(float2 coord) {
    float y = coord.y + offset.y;
    float x = clamp((y - fadeTop) / max(fadeBottom - fadeTop, 1.0), 0.0, 1.0);
    float a = (1.0 - x) * (1.0 - x);
    return content.eval(coord) * half(a);
}
"""

private fun BackdropEffectScope.dissolve(fadeTop: Float, fadeBottom: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val shader = obtainRuntimeShader("LjosDissolve", Dissolve).apply {
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("fadeTop", fadeTop)
        setFloatUniform("fadeBottom", fadeBottom)
    }
    effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}

/** The glass recipe shared by the header and the sheet. */
private fun BackdropEffectScope.liquidGlass(frost: Float, lensHeight: Float, lensAmount: Float) {
    colorControls(saturation = 1.15f)
    blur(frost)
    lens(lensHeight, lensAmount, depthEffect = true, chromaticAberration = true)
}

/**
 * A pane of liquid glass across the top of a screen or sheet. [backdrop] is what's behind it
 * (recorded with layerBackdrop). The glass's body runs [bodyPx] down, then it dissolves over
 * [fade]. [visible] (0..1, read while drawing) lets it materialise as content scrolls under it.
 * [tint] is the fallback for phones that can't run the glass, and a whisper of it is kept for
 * the status bar.
 */
@Composable
fun GlassHeader(
    backdrop: LayerBackdrop,
    bodyPx: () -> Float,
    fade: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    visible: () -> Float = { 1f },
    frost: Dp = 6.dp,
) {
    val density = LocalDensity.current
    val fadePx = with(density) { fade.toPx() }
    // The pane reaches past the screen's sides and top by this much, so the lens bends only
    // its bottom edge; the parts off screen are filled with a mirror of the page (no dark rims).
    val margin = with(density) { 22.dp.roundToPx() }
    // Screen width in px, kept from layout for the mirror below (the backdrop is drawn into a
    // padded layer, so its own size isn't the pane's).
    val screenW = remember { floatArrayOf(0f) }
    val glass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val shownTint = if (glass) tint.copy(alpha = tint.alpha * 0.22f) else tint
    Box(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val h = (bodyPx() + fadePx).toInt().coerceAtLeast(1)
                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(p.width, h) { p.place(0, 0) }
            }
            .graphicsLayer { alpha = visible().coerceIn(0f, 1f) }
    ) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    screenW[0] = constraints.maxWidth.toFloat()
                    val w = constraints.maxWidth + margin * 2
                    val h = constraints.maxHeight + margin
                    val p = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(-margin, -margin) }
                }
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(0.dp) },
                    effects = {
                        if (glass) {
                            // The Apple way for a top bar: frost and colour, no lens. A bar that
                            // fades out has no real edge, so bending one would only draw a line.
                            colorControls(saturation = 1.15f)
                            blur(frost.toPx())
                            val bottom = size.height
                            dissolve(bottom - fadePx, bottom)
                        }
                    },
                    onDrawBackdrop = { drawPage ->
                        if (!glass) return@drawPlainBackdrop
                        drawPage()
                        // Mirror the page into the off-screen margins, like a clamp, so the frost
                        // never pulls emptiness in at the screen's edges.
                        val m = margin.toFloat()
                        val right = m + screenW[0]
                        withTransform({ scale(-1f, 1f, pivot = Offset(m, 0f)) }) { drawPage() }
                        withTransform({ scale(-1f, 1f, pivot = Offset(right, 0f)) }) { drawPage() }
                        withTransform({ scale(1f, -1f, pivot = Offset(0f, m)) }) { drawPage() }
                    },
                    onDrawSurface = {
                        val h = size.height
                        val k = ((h - fadePx) / h).coerceIn(0f, 1f)
                        drawRect(Brush.verticalGradient(0f to shownTint, k to shownTint.copy(alpha = shownTint.alpha * 0.6f), 1f to Color.Transparent))
                    },
                )
        )
    }
}

/** Pages and cards that want glass controls read the backdrop to refract from here. */
val LocalGlassBackdrop = androidx.compose.runtime.staticCompositionLocalOf<LayerBackdrop?> { null }

/** Shared tilt-following highlight angle, so every glass piece on the page uses one sensor. */
val LocalGlassAngle = androidx.compose.runtime.staticCompositionLocalOf<State<Float>?> { null }

/**
 * Liquid glass for a card: more frost than a control (it carries text), a gentle lens around its
 * rounded edge with colour fringing, a rim highlight that follows the tilt, a soft shadow and a
 * dark wash so text stays crisp over bright aurora.
 */
fun Modifier.glassCard(
    backdrop: LayerBackdrop,
    shape: androidx.compose.foundation.shape.CornerBasedShape,
    lightAngle: State<Float>? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            colorControls(saturation = 1.2f)
            blur(10.dp.toPx())
            lens(16.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
        }
    },
    highlight = { Highlight(style = HighlightStyle.Default(angle = lightAngle?.value ?: 45f)) },
    shadow = { Shadow(radius = 20.dp, color = Color.Black.copy(alpha = 0.25f)) },
    onDrawSurface = {
        drawRect(Color(0x4D070B16))
        drawRect(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x05FFFFFF))))
    },
)

/** Light catches the glass rims from the top left, as on iOS. (Fixed; no tilt sensor.) */
@Composable
fun rememberGlassLightAngle(): State<Float> = remember { mutableStateOf(45f) }

/**
 * True liquid glass for a floating control (button, pill, chip), as on iOS 26: barely frosted,
 * the lens bending its whole rounded edge with colour fringing, a rim highlight that moves with
 * the phone's tilt, and a soft shadow. [shape] must be a rounded shape (RoundedCornerShape,
 * CircleShape). Phones before Android 13 get a plain translucent fill.
 */
fun Modifier.glassControl(
    backdrop: LayerBackdrop,
    shape: androidx.compose.foundation.shape.CornerBasedShape,
    lightAngle: State<Float>? = null,
    lensHeight: Dp = 10.dp,
    lensAmount: Dp = 18.dp,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            colorControls(saturation = 1.25f)
            blur(2.dp.toPx())
            lens(lensHeight.toPx(), lensAmount.toPx(), chromaticAberration = true)
        }
    },
    highlight = {
        Highlight(style = HighlightStyle.Default(angle = lightAngle?.value ?: 45f))
    },
    shadow = { Shadow(radius = 14.dp, color = Color.Black.copy(alpha = 0.22f)) },
    onDrawSurface = {
        // A faint dark wash so white text and icons read over bright things behind.
        drawRect(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Color(0x2605080F) else Color(0x33FFFFFF))
    },
)

/**
 * The settings sheet's body: one big piece of liquid glass showing the screen behind it,
 * frosted more than the header (there's text on it), with the lens bending its rounded top.
 */
fun Modifier.glassSheet(backdrop: LayerBackdrop, cornerRadius: Dp, tint: Color): Modifier =
    drawPlainBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius) },
        effects = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                liquidGlass(14.dp.toPx(), lensHeight = 20.dp.toPx(), lensAmount = 28.dp.toPx())
            } else {
                blur(14.dp.toPx())
            }
        },
        onDrawSurface = {
            drawRect(tint)
            drawRect(Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color(0x05FFFFFF))))
        },
    )
