package app.ljos.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid glass, in the spirit of iOS 26: a piece of thick glass floating over the page.
 * Whatever is behind it is lightly frosted and, near the rim, bent inward like the edge of a
 * lens. A soft sheen and a bright rim catch the light from the top left.
 *
 * Android 13+ gets the bending (an AGSL shader), Android 12 gets frost only, older phones get
 * the tint and rim. The page behind is read from [backdrop], recorded with [backdropSource],
 * which must start at the screen's top-left corner.
 */
object Glass {
    /** How much the glass frosts what's behind it. Light: the bending is the point. */
    val Frost = 9.dp
    /** Width of the band near the rim that bends the view, and how far it pulls. */
    val Rim = 18.dp
    val Bend = 24.dp
    val Tint = Color(0x5C0A1022)
}

private const val REFRACT = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float rim;
uniform float strength;

half4 main(float2 p) {
    float2 c = p - size * 0.5;
    float2 q = abs(c) - (size * 0.5 - radius);
    // Signed distance to the rounded rectangle's edge: negative inside.
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    // Outward normal of the nearest edge.
    float2 s = float2(c.x < 0.0 ? -1.0 : 1.0, c.y < 0.0 ? -1.0 : 1.0);
    float2 n;
    if (q.x > 0.0 && q.y > 0.0) n = normalize(q) * s;
    else if (q.x > q.y) n = float2(s.x, 0.0);
    else n = float2(0.0, s.y);
    // 0 in the middle, 1 at the rim, rising steeply like the curve of a lens edge.
    float t = clamp(1.0 + d / rim, 0.0, 1.0);
    float m = t * t * t;
    return content.eval(p - n * m * strength);
}
"""

/** Set if the shader ever fails to compile on a phone, so we quietly fall back to frost only. */
private var refractBroken = false

@RequiresApi(33)
private fun glassEffect(size: Size, radius: Float, rim: Float, bend: Float, frost: Float): AndroidRenderEffect {
    val blur = AndroidRenderEffect.createBlurEffect(frost.coerceAtLeast(0.1f), frost.coerceAtLeast(0.1f), Shader.TileMode.CLAMP)
    if (refractBroken) return blur
    val shader = try { RuntimeShader(REFRACT) } catch (e: Exception) { refractBroken = true; return blur }
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("radius", radius.coerceAtMost(minOf(size.width, size.height) / 2f))
    shader.setFloatUniform("rim", rim)
    shader.setFloatUniform("strength", bend)
    val refract = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
    // Frost first, then bend the frosted view.
    return AndroidRenderEffect.createChainEffect(refract, blur)
}

/**
 * A glass surface showing [backdrop] through it. [cornerRadius] shapes it (use half the height
 * for a capsule or circle). [tint] darkens it a little so text on it stays readable.
 */
@Composable
fun LiquidGlass(
    backdrop: GraphicsLayer?,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Glass.Tint,
    frost: Dp = Glass.Frost,
    rim: Dp = Glass.Rim,
    bend: Dp = Glass.Bend,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(cornerRadius)
    // Where this piece sits on screen, so it shows the part of the page right behind it.
    // Kept in state and read only while drawing, so moving (e.g. dragging a sheet) just redraws.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape),
        contentAlignment = contentAlignment,
    ) {
        if (backdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val r = cornerRadius.toPx()
                        renderEffect = when {
                            size.minDimension <= 0f -> null
                            Build.VERSION.SDK_INT >= 33 ->
                                glassEffect(size, r, rim.toPx(), bend.toPx(), frost.toPx()).asComposeRenderEffect()
                            Build.VERSION.SDK_INT >= 31 -> BlurEffect(frost.toPx() * 1.6f, frost.toPx() * 1.6f, TileMode.Clamp)
                            else -> null
                        }
                    }
                    .drawBehind {
                        val o = origin
                        translate(-o.x, -o.y) { drawLayer(backdrop) }
                    }
            )
        }
        Box(Modifier.matchParentSize().drawBehind { drawGlassLight(cornerRadius.toPx(), tint) })
        content()
    }
}

/** Tint, sheen and rim: the light-catching part of the glass, drawn over whatever is behind. */
internal fun DrawScope.drawGlassLight(radius: Float, tint: Color) {
    val r = CornerRadius(radius.coerceAtMost(size.minDimension / 2f))
    drawRoundRect(tint, cornerRadius = r)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x0AFFFFFF))),
        cornerRadius = r,
    )
    // Soft sheen from the top left.
    drawRoundRect(
        Brush.radialGradient(
            listOf(Color(0x33FFFFFF), Color.Transparent),
            center = Offset(size.width * 0.2f, 0f),
            radius = maxOf(size.width * 0.55f, size.height * 0.9f),
        ),
        cornerRadius = r,
    )
    // Bright rim on the lit edge, fading round to a faint one on the far side.
    val w = 1.5.dp.toPx()
    drawRoundRect(
        Brush.linearGradient(
            0f to Color(0x8CFFFFFF),
            0.45f to Color(0x24FFFFFF),
            1f to Color(0x33FFFFFF),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius((r.x - w / 2f).coerceAtLeast(0f)),
        style = Stroke(w),
    )
}

// ---------------------------------------------------------------------------------------------
// Glass header: one full-width pane of glass across the top, dissolving softly at the bottom.

private const val LIP = """
uniform shader content;
uniform float edge;     // where the glass's body ends
uniform float rim;      // how far above the edge the bending starts
uniform float tail;     // how far below the edge it eases back to straight
uniform float strength; // how far it pulls, at the edge

half4 main(float2 p) {
    float m;
    if (p.y <= edge) {
        float t = clamp(1.0 - (edge - p.y) / rim, 0.0, 1.0);
        m = t * t * t;
    } else {
        float t = clamp(1.0 - (p.y - edge) / tail, 0.0, 1.0);
        m = t * t;
    }
    // Near the bottom edge the view is pulled up from below, like light through a glass lip.
    return content.eval(float2(p.x, p.y + m * strength));
}
"""

private var lipBroken = false

@RequiresApi(33)
private fun lipEffect(edge: Float, rim: Float, tail: Float, bend: Float, frost: Float): AndroidRenderEffect {
    val blur = AndroidRenderEffect.createBlurEffect(frost, frost, Shader.TileMode.CLAMP)
    if (lipBroken) return blur
    val shader = try { RuntimeShader(LIP) } catch (e: Exception) { lipBroken = true; return blur }
    shader.setFloatUniform("edge", edge)
    shader.setFloatUniform("rim", rim)
    shader.setFloatUniform("tail", tail)
    shader.setFloatUniform("strength", bend)
    return AndroidRenderEffect.createChainEffect(AndroidRenderEffect.createRuntimeShaderEffect(shader, "content"), blur)
}

/**
 * A pane of liquid glass across the top of a screen or sheet. [backdrop] (recorded with
 * [backdropSource] from the same top-left corner) shows through it frosted. The glass's body
 * runs [bodyPx] down; below that it dissolves over [fade] (no line), and right at the edge the
 * view bends a little, like the thick lip of a glass pane. A soft sheen catches the light.
 * Android 13+ bends, 12 frosts, older phones get the tint.
 */
@Composable
fun GlassHeader(
    backdrop: GraphicsLayer,
    bodyPx: () -> Float,
    fade: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    frost: Dp = 20.dp,
    bend: Dp = 10.dp,
) {
    val density = LocalDensity.current
    val fadePx = with(density) { fade.toPx() }
    val bleed = with(density) { (frost * 3).roundToPx() }
    Box(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val h = (bodyPx() + fadePx).toInt().coerceAtLeast(1)
                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(p.width, h) { p.place(0, 0) }
            }
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // The whole pane eases out over the fade, slowly at first: (1 - x)².
                val h = size.height
                val k = ((h - fadePx) / h).coerceIn(0f, 1f)
                fun at(x: Float) = k + (1f - k) * x
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        k to Color.Black,
                        at(0.25f) to Color.Black.copy(alpha = 0.56f),
                        at(0.5f) to Color.Black.copy(alpha = 0.25f),
                        at(0.75f) to Color.Black.copy(alpha = 0.06f),
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    ) {
        if (Build.VERSION.SDK_INT >= 31) {
            Box(
                Modifier
                    // A little taller than the pane so the frost sees what's just below it.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minHeight = constraints.maxHeight + bleed, maxHeight = constraints.maxHeight + bleed))
                        layout(p.width, constraints.maxHeight) { p.place(0, 0) }
                    }
                    .fillMaxWidth()
                    .graphicsLayer {
                        val f = frost.toPx()
                        val edge = bodyPx()
                        renderEffect = if (Build.VERSION.SDK_INT >= 33) {
                            lipEffect(edge, 22.dp.toPx(), fadePx, bend.toPx(), f).asComposeRenderEffect()
                        } else {
                            BlurEffect(f, f, TileMode.Clamp)
                        }
                    }
                    .drawBehind { drawLayer(backdrop) }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val h = size.height
                    val k = ((h - fadePx) / h).coerceIn(0f, 1f)
                    // Tint, a touch deeper behind the status bar.
                    drawRect(Brush.verticalGradient(0f to tint, k to tint.copy(alpha = tint.alpha * 0.8f), 1f to tint.copy(alpha = tint.alpha * 0.5f)))
                    // Sheen from the top left, like light on glass.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x1FFFFFFF), Color.Transparent),
                            center = Offset(size.width * 0.15f, 0f),
                            radius = size.width * 0.7f,
                        )
                    )
                    // A faint glow along the glass's lower lip, soft on both sides (not a line).
                    val e = h - fadePx
                    val band = 14.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            ((e - band) / h).coerceIn(0f, 1f) to Color.Transparent,
                            (e / h).coerceIn(0f, 1f) to Color(0x0DFFFFFF),
                            ((e + band) / h).coerceIn(0f, 1f) to Color.Transparent,
                        )
                    )
                }
        )
    }
}
