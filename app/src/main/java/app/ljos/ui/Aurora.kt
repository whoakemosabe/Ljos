package app.ljos.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/** What the "Live" sky shows: conditions for one hour, all 0..1 (moon position in screen units). */
data class SkyState(
    val activity: Float,
    val cloud: Float,
    val moonIllum: Float,
    val moonUp: Float,
    val moonX: Float,
    val moonY: Float,
    val storm: Float,
)

/**
 * Aurora drawn on the GPU (Android 13+).
 *  - Calm (default): curtains brighten with [intensity] (0..1).
 *  - Live ([sky] non-null): the sky *is* the forecast. Curtain brightness and height follow
 *    activity, storms tint violet, cloud rolls in as fog, and the real moon sits in the sky
 *    at its phase, dimming the stars.
 * [pull] (0..~1.3) stretches and brightens the curtains for pull-to-refresh.
 * Falls back to a slow moving gradient on older phones or if the shader fails to compile.
 */
@Composable
fun AuroraBackground(
    intensity: Float,
    modifier: Modifier = Modifier,
    sky: SkyState? = null,
    pull: () -> Float = { 0f },
) {
    if (Build.VERSION.SDK_INT >= 33) {
        val shader = remember { makeShader() }
        if (shader != null) {
            ShaderAurora(shader, intensity, sky, pull, modifier)
            return
        }
    }
    GradientAurora(intensity, modifier)
}

private fun makeShader(): Any? =
    if (Build.VERSION.SDK_INT >= 33) {
        try { RuntimeShader(AURORA_AGSL) } catch (e: Exception) { null }
    } else {
        null
    }

@RequiresApi(33)
@Composable
private fun ShaderAurora(shaderAny: Any, intensity: Float, sky: SkyState?, pull: () -> Float, modifier: Modifier) {
    val shader = shaderAny as RuntimeShader
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { value = (it - start) / 1_000_000_000f }
        }
    }
    // Every live value eases, so scrubbing hours or switching modes morphs the sky smoothly.
    val ease = tween<Float>(700, easing = FastOutSlowInEasing)
    val live by animateFloatAsState(if (sky != null) 1f else 0f, tween(900), label = "live")
    val activity by animateFloatAsState(sky?.activity ?: intensity, ease, label = "activity")
    val cloud by animateFloatAsState(sky?.cloud ?: 0f, ease, label = "cloud")
    val moonIllum by animateFloatAsState(sky?.moonIllum ?: 0f, ease, label = "moonIllum")
    val moonUp by animateFloatAsState(sky?.moonUp ?: 0f, ease, label = "moonUp")
    val moonX by animateFloatAsState(sky?.moonX ?: 0.75f, ease, label = "moonX")
    val moonY by animateFloatAsState(sky?.moonY ?: 0.2f, ease, label = "moonY")
    val storm by animateFloatAsState(sky?.storm ?: 0f, ease, label = "storm")
    Canvas(modifier) {
        shader.setFloatUniform("iResolution", size.width, size.height)
        shader.setFloatUniform("iTime", time)
        shader.setFloatUniform("intensity", intensity)
        shader.setFloatUniform("live", live)
        shader.setFloatUniform("activity", activity)
        shader.setFloatUniform("cloud", cloud)
        shader.setFloatUniform("moonIllum", moonIllum)
        shader.setFloatUniform("moonUp", moonUp)
        shader.setFloatUniform("moonPos", moonX, moonY)
        shader.setFloatUniform("storm", storm)
        shader.setFloatUniform("pull", pull())
        drawRect(ShaderBrush(shader))
    }
}

@Composable
private fun GradientAurora(intensity: Float, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val shift by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "shift",
    )
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF040611), Color(0xFF07122A), NightBg)))
        val a = 0.25f + 0.6f * intensity
        drawRect(
            Brush.radialGradient(
                listOf(Green.copy(alpha = 0.35f * a), Color.Transparent),
                center = Offset(size.width * (0.2f + 0.6f * shift), size.height * 0.28f),
                radius = size.width * 0.9f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Violet.copy(alpha = 0.25f * a), Color.Transparent),
                center = Offset(size.width * (0.8f - 0.5f * shift), size.height * 0.18f),
                radius = size.width * 0.7f,
            )
        )
    }
}

private const val AURORA_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float intensity;
uniform float live;
uniform float activity;
uniform float cloud;
uniform float moonIllum;
uniform float moonUp;
uniform float2 moonPos;
uniform float storm;
uniform float pull;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float aspect = iResolution.x / iResolution.y;
    float t = iTime * 0.04;
    float3 col = mix(float3(0.012, 0.020, 0.050), float3(0.030, 0.060, 0.110), smoothstep(0.0, 0.7, uv.y));

    float calmGlow = 0.15 + 0.85 * clamp(intensity, 0.0, 1.0);
    float liveGlow = 0.08 + 1.05 * pow(clamp(activity, 0.0, 1.0), 1.3);
    float glow = mix(calmGlow, liveGlow, live) * (1.0 + 0.9 * pull);
    float height = mix(1.0, 0.65 + 0.8 * clamp(activity, 0.0, 1.0), live) * (1.0 + 0.6 * pull);
    float violetBoost = live * storm;

    for (int k = 0; k < 3; k++) {
        float fk = float(k);
        float x = uv.x * aspect * (1.2 + fk * 0.35) + fk * 5.3;
        float wave = fbm(float2(x * 0.9 + t * (1.0 + fk * 0.4), t * 0.7 + fk * 3.1));
        float base = 0.22 + fk * 0.075 + (wave - 0.5) * 0.22 + 0.10 * pull;
        float d = uv.y - base;
        float band = d < 0.0 ? exp(d * (7.0 - fk) / height) : exp(-d * 45.0);
        float rays = 0.45 + 0.55 * fbm(float2(x * 7.0 + t * 3.0, uv.y * 1.2 - t));
        float a = band * rays * glow * (0.75 - fk * 0.18);
        float3 green = float3(0.24, 1.0, 0.63);
        float3 violet = float3(0.62, 0.45, 1.0);
        float3 c = mix(green, violet, clamp(-d * 4.0 + fk * 0.2 + violetBoost * 0.6, 0.0, 1.0));
        col += c * a;
    }

    // Moon (live only): soft halo plus a disc with its real phase.
    float mUp = live * moonUp;
    float2 mp = float2((uv.x - moonPos.x) * aspect, uv.y - moonPos.y);
    float md = length(mp);
    col += float3(0.85, 0.82, 0.70) * exp(-md * 9.0) * 0.35 * moonIllum * mUp;
    float r = 0.032;
    float disc = smoothstep(r, r - 0.003, md);
    float shadow = smoothstep(r, r - 0.003, length(mp - float2(moonIllum * 2.0 * r, 0.0)));
    col = mix(col, float3(0.95, 0.92, 0.80), disc * (1.0 - shadow) * mUp);

    // Stars dim under a bright moon and vanish behind cloud.
    float s = hash(floor(fragCoord / 3.0));
    float twinkle = 0.6 + 0.4 * sin(iTime * 2.0 + s * 40.0);
    float starDim = (1.0 - 0.75 * moonIllum * mUp) * (1.0 - live * clamp(cloud, 0.0, 1.0));
    col += float3(step(0.9982, s) * twinkle * (1.0 - smoothstep(0.2, 0.6, uv.y)) * 0.8 * starDim);

    // Cloud as slow drifting fog over everything (live only), faintly moonlit.
    float fogN = fbm(float2(uv.x * 2.2 * aspect + t * 1.5, uv.y * 3.0 - t * 0.4));
    float fogAmt = live * clamp(cloud * (0.6 + 0.6 * fogN) - 0.08, 0.0, 1.0);
    float3 fogCol = float3(0.075, 0.09, 0.12) + float3(0.10, 0.10, 0.09) * moonIllum * mUp;
    col = mix(col, fogCol, fogAmt * 0.92);

    col *= mix(1.0, 0.18, smoothstep(0.38, 0.95, uv.y));
    return half4(half3(col), 1.0);
}
"""
