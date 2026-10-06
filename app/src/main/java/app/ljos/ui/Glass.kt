package app.ljos.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

/*
 * What makes it read as Apple's Liquid Glass rather than plain frosted glass:
 *  - vibrancy: what's behind is frosted only lightly and its colours are boosted, so the glass
 *    looks lit from within instead of grey;
 *  - a convex bevel: the bend is gentle across most of the rim and steepens right at the edge,
 *    the way a rounded glass edge refracts (a circular profile, not a straight ramp);
 *  - dispersion: red, green and blue bend by slightly different amounts, leaving a faint
 *    colour fringe where the bend is strongest;
 *  - a specular rim: light from the top left catches the curved edge as a soft highlight.
 */

private const val REFRACT = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float rim;
uniform float strength;
uniform float dispersion;
uniform float spec;

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
    // Convex bevel: 0 inside, 1 at the edge, steepening like the side of a rounded lens.
    float t = clamp(1.0 + d / rim, 0.0, 1.0);
    float m = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float2 off = -n * m * strength;
    half4 g = content.eval(p + off);
    half r = content.eval(p + off * (1.0 + dispersion)).r;
    half b = content.eval(p + off * (1.0 - dispersion)).b;
    half4 col = half4(r, g.g, b, g.a);
    // Light from the top left catches the rim; the far side gets a little too.
    float lit = max(dot(n, normalize(float2(-0.55, -0.85))), 0.0);
    float shine = spec * m * (0.3 + 0.7 * lit);
    col.rgb = min(col.rgb + half3(shine) * col.a, half3(1.0));
    return col;
}
"""

/** Set if a shader ever fails to compile on a phone, so we quietly fall back to frost only. */
private var refractBroken = false

/** Light frost with a hint of vibrancy: colours a touch richer behind the glass, not shifted. */
@RequiresApi(31)
private fun frosted(frost: Float): AndroidRenderEffect {
    val blur = AndroidRenderEffect.createBlurEffect(frost.coerceAtLeast(0.1f), frost.coerceAtLeast(0.1f), Shader.TileMode.CLAMP)
    // Only a touch richer, so colours behind the glass stay true as they slide under it.
    val m = android.graphics.ColorMatrix().apply { setSaturation(1.12f) }
    return AndroidRenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(m), blur)
}

@RequiresApi(33)
private fun glassEffect(size: Size, radius: Float, rim: Float, bend: Float, frost: Float): AndroidRenderEffect {
    val base = frosted(frost)
    if (refractBroken) return base
    val shader = try { RuntimeShader(REFRACT) } catch (e: Exception) { refractBroken = true; return base }
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("radius", radius.coerceAtMost(minOf(size.width, size.height) / 2f))
    shader.setFloatUniform("rim", rim)
    shader.setFloatUniform("strength", bend)
    shader.setFloatUniform("dispersion", 0.22f)
    shader.setFloatUniform("spec", 0.1f)
    // Frost and colour first, then bend the result.
    return AndroidRenderEffect.createChainEffect(AndroidRenderEffect.createRuntimeShaderEffect(shader, "content"), base)
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
                            Build.VERSION.SDK_INT >= 31 -> frosted(frost.toPx() * 1.6f).asComposeRenderEffect()
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
//
// Modelled on iOS 26's regular Liquid Glass rather than plain frosted glass:
//  - barely frosted (5dp): you see the page through it, softened, not smeared;
//  - readability comes from adaptive dimming: bright things behind are toned down more than dark
//    sky, per pixel, so over the aurora it's nearly clear;
//  - the lower lip is a rounded glass edge and refracts by Snell's law (index 1.5): flat body,
//    then a quarter-round bevel whose growing slope bends the view more and more, magnifying
//    what's just below the edge. Red, green and blue have slightly different indices, so the
//    fringe comes out of the physics rather than being painted on;
//  - the lip catches the light (stronger where the glass curves most, like Fresnel), and that
//    highlight slides along the edge as you tilt the phone;
//  - at the top of the page the glass isn't there at all; it materialises as content scrolls under.

private const val LIP = """
uniform shader content;
uniform float width;
uniform float edge;      // where the glass's flat body ends and the lip begins to curve away
uniform float bevel;     // radius of the rounded lip
uniform float depth;     // glass thickness the light travels through
uniform float tail;      // below the edge, the pane dissolves over this
uniform float3 ior;      // index of refraction for red, green, blue
uniform float dimBase;
uniform float dimBright;

// Tilt of the glass surface at height y: flat in the body, curving to vertical at the lip.
float surfaceAngle(float y) {
    float s = edge - y;
    if (s >= bevel) return 0.0;
    float r = bevel - max(s, 0.0);
    float h = sqrt(max(bevel * bevel - r * r, 0.001));
    return atan(r / h);
}

// Sideways shift of a ray from the viewer through a surface tilted by theta (Snell's law).
float shift(float theta, float n) {
    float inside = asin(sin(theta) / n);
    return depth * tan(theta - inside);
}

half4 main(float2 p) {
    float theta;
    float ease;
    if (p.y <= edge) {
        theta = surfaceAngle(p.y);
        ease = 1.0;
    } else {
        // Past the lip the pane is dissolving: let the bend relax with it so nothing tears.
        theta = 1.5707;
        float x = clamp((p.y - edge) / tail, 0.0, 1.0);
        ease = (1.0 - x) * (1.0 - x);
    }
    float dr = shift(theta, ior.r) * ease;
    float dg = shift(theta, ior.g) * ease;
    float db = shift(theta, ior.b) * ease;
    half4 g = content.eval(float2(p.x, p.y + dg));
    half r = content.eval(float2(p.x, p.y + dr)).r;
    half b = content.eval(float2(p.x, p.y + db)).b;
    half4 col = half4(r, g.g, b, g.a);

    // Adaptive dimming, gentle: only really bright things behind are toned down, so the title
    // stays readable without the glass going dark or shifting colours.
    float lum = dot(float3(col.rgb), float3(0.2126, 0.7152, 0.0722));
    float dim = dimBase + dimBright * smoothstep(0.35, 0.85, lum);
    col.rgb *= half(1.0 - dim);
    return col;
}
"""

/** Holds the compiled lip shader, so tilting only updates uniforms instead of recompiling. */
@RequiresApi(33)
private class LipShader {
    private val shader = RuntimeShader(LIP)

    fun effect(width: Float, edge: Float, bevel: Float, depth: Float, tail: Float, frost: Float): AndroidRenderEffect {
        shader.setFloatUniform("width", width.coerceAtLeast(1f))
        shader.setFloatUniform("edge", edge)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("depth", depth)
        shader.setFloatUniform("tail", tail.coerceAtLeast(1f))
        shader.setFloatUniform("ior", 1.48f, 1.50f, 1.53f)
        shader.setFloatUniform("dimBase", 0.0f)
        shader.setFloatUniform("dimBright", 0.16f)
        // Frost and colour first, then refract and dim the result.
        return AndroidRenderEffect.createChainEffect(AndroidRenderEffect.createRuntimeShaderEffect(shader, "content"), frosted(frost))
    }
}

/**
 * Where light glints on the glass, as fractions of its width and height. Follows the phone's
 * tilt like a real reflection: held normally it sits top left; roll the phone left or right and it
 * slides across, tip it towards or away from you and it moves down or up. The sensor only runs
 * while the app is in front.
 */
@Composable
private fun rememberTiltLight(): State<Offset> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val light = remember { mutableStateOf(Offset(0.3f, 0.2f)) }
    DisposableEffect(lifecycle) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var sx = 0.3f
        var sy = 0.2f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = 9.81f
                // x: roll (gravity across the screen); y: pitch (gravity into the screen).
                val tx = (0.3f - e.values[0] / g * 1.4f).coerceIn(-0.1f, 1.1f)
                val ty = (0.2f + (e.values[2] / g - 0.55f) * 1.6f).coerceIn(-0.2f, 1.2f)
                sx += (tx - sx) * 0.2f
                sy += (ty - sy) * 0.2f
                val cur = light.value
                if (kotlin.math.abs(sx - cur.x) > 0.003f || kotlin.math.abs(sy - cur.y) > 0.003f) light.value = Offset(sx, sy)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                Lifecycle.Event.ON_PAUSE -> sm?.unregisterListener(listener)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sm?.unregisterListener(listener)
        }
    }
    return light
}

/**
 * A pane of liquid glass across the top of a screen or sheet. [backdrop] (recorded with
 * [backdropSource] from the same top-left corner) shows through it. The glass's body runs
 * [bodyPx] down; then its lip curves away and it dissolves over [fade]. [visible] (0..1, read
 * while drawing) lets it materialise as content scrolls under it. [tint] is only a fallback for
 * phones that can't run the glass shader; with it, readability comes from adaptive dimming.
 */
@Composable
fun GlassHeader(
    backdrop: GraphicsLayer,
    bodyPx: () -> Float,
    fade: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    visible: () -> Float = { 1f },
    frost: Dp = 6.dp,
    bevel: Dp = 16.dp,
    depth: Dp = 7.dp,
) {
    val density = LocalDensity.current
    val fadePx = with(density) { fade.toPx() }
    val bleed = with(density) { 48.dp.roundToPx() }
    val lip: Any? = remember {
        if (Build.VERSION.SDK_INT >= 33 && !lipBroken) {
            try { LipShader() } catch (e: Exception) { lipBroken = true; null }
        } else null
    }
    val light = rememberTiltLight()
    // With the shader, just a whisper of tint for the status bar; without it, the full tint.
    val shownTint = if (lip != null) tint.copy(alpha = tint.alpha * 0.22f) else tint
    Box(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val h = (bodyPx() + fadePx).toInt().coerceAtLeast(1)
                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(p.width, h) { p.place(0, 0) }
            }
            .clipToBounds()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = visible().coerceIn(0f, 1f)
            }
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
                    // Taller than the pane so the frost and the lip's bend see what's just below.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minHeight = constraints.maxHeight + bleed, maxHeight = constraints.maxHeight + bleed))
                        layout(p.width, constraints.maxHeight) { p.place(0, 0) }
                    }
                    .fillMaxWidth()
                    .graphicsLayer {
                        val f = frost.toPx()
                        renderEffect = if (Build.VERSION.SDK_INT >= 33 && lip is LipShader) {
                            lip.effect(
                                width = size.width, edge = bodyPx() - bevel.toPx() * 0.5f, bevel = bevel.toPx(),
                                depth = depth.toPx(), tail = fadePx, frost = f,
                            ).asComposeRenderEffect()
                        } else {
                            frosted(f * 2f).asComposeRenderEffect()
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
                    drawRect(Brush.verticalGradient(0f to shownTint, k to shownTint.copy(alpha = shownTint.alpha * 0.6f), 1f to Color.Transparent))
                    // A broad, soft glint that moves as you tilt the phone, like light sliding
                    // across a sheet of glass. Wide and faint, so it reads as glass, not as a spot.
                    val l = light.value
                    val c = Offset(size.width * l.x, k * h * l.y)
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.13f),
                            0.45f to Color.White.copy(alpha = 0.05f),
                            1f to Color.Transparent,
                            center = c,
                            radius = size.width * 0.62f,
                        )
                    )
                    // A slimmer streak across it, angled like a reflection.
                    val streakX = size.width * (l.x + 0.18f)
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.07f),
                            1f to Color.Transparent,
                            start = Offset(streakX - 40.dp.toPx(), 0f),
                            end = Offset(streakX + 40.dp.toPx(), 26.dp.toPx()),
                        )
                    )
                }
        )
    }
}

private var lipBroken = false
