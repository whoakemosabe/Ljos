package app.ljos.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
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
