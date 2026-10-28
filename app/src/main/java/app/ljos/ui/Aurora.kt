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
    /** 0 at night, 1 in daylight; dusk is the in-between glow around sunset. */
    val daylight: Float = 0f,
    val dusk: Float = 0f,
    /** The real sky: weather, sun and moon directions, aurora colour and stars (core SkyView). */
    val detail: app.ljos.model.SkyDetail? = null,
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
    val daylight by animateFloatAsState(sky?.daylight ?: 0f, ease, label = "daylight")
    val dusk by animateFloatAsState(sky?.dusk ?: 0f, ease, label = "dusk")
    val d = sky?.detail
    val waxing = eased(d?.waxing ?: 0f, "waxing")
    val moonWash = eased(d?.moonWash ?: 0f, "moonWash")
    val cloudLow = eased(d?.cloudLow ?: 0f, "cloudLow")
    // Without detail (older data), the total cloud shows as the middle layer, like the old fog.
    val cloudMid = eased(d?.cloudMid ?: (sky?.cloud ?: 0f), "cloudMid")
    val cloudHigh = eased(d?.cloudHigh ?: 0f, "cloudHigh")
    val driftX = eased(d?.driftX ?: 0.4f, "driftX")
    val driftY = eased(d?.driftY ?: 0f, "driftY")
    val rain = eased(d?.rain ?: 0f, "rain")
    val rainSlant = eased(d?.rainSlant ?: 0f, "rainSlant")
    val snow = eased(d?.snow ?: 0f, "snow")
    val haze = eased(d?.haze ?: 0f, "haze")
    val sunX = eased(d?.sunX ?: 0.5f, "sunX")
    val sunY = eased(d?.sunY ?: 0.5f, "sunY")
    val bzBoost = eased(d?.bzBoost ?: 0f, "bzBoost")
    val redFringe = eased(d?.redFringe ?: 0f, "redFringe")
    val pinkEdge = eased(d?.pinkEdge ?: 0f, "pinkEdge")
    val stars = d?.stars ?: NO_STARS
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
        shader.setFloatUniform("daylight", daylight)
        shader.setFloatUniform("dusk", dusk)
        shader.setFloatUniform("pull", pull())
        shader.setFloatUniform("dens", density)
        shader.setFloatUniform("waxing", waxing)
        shader.setFloatUniform("moonWash", moonWash)
        shader.setFloatUniform("cloudLow", cloudLow)
        shader.setFloatUniform("cloudMid", cloudMid)
        shader.setFloatUniform("cloudHigh", cloudHigh)
        shader.setFloatUniform("drift", driftX, driftY)
        shader.setFloatUniform("rain", rain)
        shader.setFloatUniform("rainSlant", rainSlant)
        shader.setFloatUniform("snow", snow)
        shader.setFloatUniform("haze", haze)
        shader.setFloatUniform("sunPos", sunX, sunY)
        shader.setFloatUniform("bzBoost", bzBoost)
        shader.setFloatUniform("redFringe", redFringe)
        shader.setFloatUniform("pinkEdge", pinkEdge)
        shader.setFloatUniform("stars", stars)
        drawRect(ShaderBrush(shader))
    }
}

private val NO_STARS = FloatArray(14 * 3)

@Composable
private fun eased(target: Float, label: String): Float {
    val v by animateFloatAsState(target, tween(700, easing = FastOutSlowInEasing), label = label)
    return v
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
uniform float daylight;
uniform float dusk;
uniform float dens;
uniform float waxing;
uniform float moonWash;
uniform float cloudLow;
uniform float cloudMid;
uniform float cloudHigh;
uniform float2 drift;
uniform float rain;
uniform float rainSlant;
uniform float snow;
uniform float haze;
uniform float2 sunPos;
uniform float bzBoost;
uniform float redFringe;
uniform float pinkEdge;
uniform float3 stars[14];

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

float blob(float2 q, float2 c, float r) {
    float2 d = q - c;
    return exp(-dot(d, d) / (r * r));
}

// The moon's face on the unit disc (x right, y down, north up): the maria and a few bright
// craters, as in moon.js on the web.
float moonAlbedo(float2 q) {
    float m = 0.15 * blob(q, float2(-0.55, -0.05), 0.42) + 0.19 * blob(q, float2(-0.28, -0.45), 0.28)
        + 0.19 * blob(q, float2(0.18, -0.38), 0.19) + 0.19 * blob(q, float2(0.32, -0.10), 0.22)
        + 0.21 * blob(q, float2(0.68, -0.30), 0.12) + 0.14 * blob(q, float2(0.60, 0.12), 0.15)
        + 0.14 * blob(q, float2(0.40, 0.26), 0.11) + 0.14 * blob(q, float2(-0.18, 0.36), 0.20)
        + 0.14 * blob(q, float2(-0.48, 0.30), 0.11) + 0.10 * blob(q * float2(0.5, 1.0), float2(-0.02, -0.70), 0.14);
    float bright = 0.14 * blob(q, float2(-0.12, 0.72), 0.04) + 0.08 * blob(q, float2(-0.30, -0.12), 0.035);
    float tex = (noise(q * 6.0) - 0.5) * 0.07 + (noise(q * 22.0) - 0.5) * 0.025;
    return clamp(1.0 - m + bright + tex, 0.0, 1.1);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float aspect = iResolution.x / iResolution.y;
    float t = iTime * 0.04;
    float3 col = mix(float3(0.012, 0.020, 0.050), float3(0.030, 0.060, 0.110), smoothstep(0.0, 0.7, uv.y));

    float calmGlow = 0.15 + 0.85 * clamp(intensity, 0.0, 1.0);
    float liveGlow = 0.08 + 1.05 * pow(clamp(activity, 0.0, 1.0), 1.3);
    // Live sky by day and at dusk: a deep evening blue, and no aurora until it's properly dark.
    float day = live * daylight;
    float3 dayCol = mix(float3(0.075, 0.13, 0.24), float3(0.15, 0.20, 0.32), smoothstep(0.0, 0.8, uv.y));
    col = mix(col, dayCol, day);
    // Twilight glows on the horizon where the sun actually is; a high moon lights the sky.
    float2 sp = float2((uv.x - sunPos.x) * aspect, (uv.y - sunPos.y) * 2.4);
    col += live * dusk * float3(0.55, 0.24, 0.10) * exp(-length(sp) * 1.3) * smoothstep(0.0, sunPos.y, uv.y);
    col += live * moonWash * float3(0.035, 0.05, 0.08) * (1.0 - smoothstep(0.0, 0.7, uv.y));
    float glow = mix(calmGlow, liveGlow * (1.0 + 0.6 * bzBoost - 0.4 * moonWash), live) * (1.0 + 0.9 * pull) * (1.0 - 0.95 * smoothstep(0.0, 0.7, day));
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
        // Strong nights get red tops and a pink lower edge, as they do in photos.
        c = mix(c, float3(1.0, 0.28, 0.32), live * redFringe * smoothstep(0.08, 0.32, -d));
        c = mix(c, float3(1.0, 0.45, 0.78), live * pinkEdge * exp(-abs(d + 0.006) * 55.0));
        col += c * a;
    }

    // Moon (live only): a halo, then a lit sphere with its real phase, maria and earthshine.
    float mUp = live * moonUp;
    float2 mp = float2((uv.x - moonPos.x) * aspect, uv.y - moonPos.y);
    float md = length(mp);
    col += float3(0.85, 0.82, 0.70) * exp(-md * 9.0) * 0.35 * moonIllum * mUp;
    float r = 0.034;
    if (mUp > 0.001 && md < r * 1.05) {
        float2 q = mp / r;
        float qd = length(q);
        float edge = smoothstep(1.0, 1.0 - 1.5 / (r * iResolution.y), qd);
        float3 n = float3(q.x, q.y, sqrt(max(0.0, 1.0 - qd * qd)));
        float inc = acos(clamp(2.0 * moonIllum - 1.0, -1.0, 1.0));
        float side = waxing > 0.5 ? 1.0 : -1.0;
        float ndl = dot(n, float3(side * sin(inc), 0.0, cos(inc)));
        float lit = smoothstep(-0.04, 0.10, ndl);
        float alb = moonAlbedo(q);
        float3 face = float3(1.0, 0.98, 0.92) * alb * lit * mix(0.78, 1.0, smoothstep(0.0, 0.45, ndl))
            + float3(0.10, 0.11, 0.14) * alb * (1.0 - lit);
        col = mix(col, face, edge * mUp);
    }

    // Stars dim under a bright moon and vanish behind cloud.
    float s = hash(floor(fragCoord / 3.0));
    float twinkle = 0.6 + 0.4 * sin(iTime * 2.0 + s * 40.0);
    float starDim = (1.0 - 0.75 * moonIllum * mUp) * (1.0 - live * clamp(cloud, 0.0, 1.0)) * (1.0 - smoothstep(0.05, 0.45, day));
    starDim *= 1.0 - live * 0.55 * moonWash;
    col += float3(step(0.9982, s) * twinkle * (1.0 - smoothstep(0.2, 0.6, uv.y)) * 0.8 * starDim);
    // Real bright stars (Big Dipper, Polaris, Cassiopeia, Capella) where they are now.
    for (int i = 0; i < 14; i++) {
        float3 st = stars[i];
        float2 dp = (fragCoord - st.xy * iResolution) / dens;
        float d2 = dot(dp, dp);
        col += float3(0.88, 0.92, 1.0) * st.z * starDim * live * (exp(-d2 * 0.45) + 0.12 * exp(-sqrt(d2) * 0.5));
    }

    // Cloud in three layers, drifting with the wind: a thin high veil, a mid deck, and dense
    // low cloud that moves fastest. Each catches some moonlight.
    float2 dr = drift * iTime * 0.003;
    float hiN = fbm(float2((uv.x * aspect + dr.x * 0.6) * 1.1, uv.y * 7.0 + dr.y));
    float hi = clamp(cloudHigh * (hiN * 1.4 - 0.25), 0.0, 1.0) * (1.0 - smoothstep(0.15, 0.65, uv.y));
    col = mix(col, float3(0.13, 0.14, 0.18) + float3(0.26, 0.25, 0.22) * moonWash + float3(0.30, 0.14, 0.07) * dusk, hi * 0.6);
    float miN = fbm(float2((uv.x * aspect + dr.x) * 2.0, uv.y * 3.0 + dr.y) + 3.7);
    float mi = clamp(cloudMid * (0.6 + 0.9 * miN) - 0.15, 0.0, 1.0);
    col = mix(col, float3(0.095, 0.105, 0.13) + float3(0.14, 0.14, 0.12) * moonWash + float3(0.10, 0.10, 0.09) * moonIllum * mUp, mi * 0.8);
    float loN = fbm(float2((uv.x * aspect + dr.x * 1.7) * 2.6, uv.y * 2.2 + dr.y) + 7.1);
    float lo = clamp(cloudLow * (0.45 + 0.9 * loN) - 0.15, 0.0, 1.0) * smoothstep(0.02, 0.3, uv.y);
    float3 loCol = mix(float3(0.05, 0.055, 0.07), float3(0.11, 0.12, 0.145), smoothstep(0.35, 0.8, loN)) + float3(0.10, 0.10, 0.09) * moonWash;
    col = mix(col, loCol, lo * 0.92);
    col = mix(col, float3(0.10, 0.11, 0.13) + float3(0.08) * moonWash, haze * 0.6);

    // Rain: thin streaks slanted by the wind. Snow: two layers of drifting flakes.
    float2 px = fragCoord / dens;
    if (rain > 0.0) {
        float2 q = float2(px.x + px.y * rainSlant, px.y - iTime * 650.0);
        float2 cs = float2(16.0, 72.0);
        float2 cell = floor(q / cs);
        float2 f = fract(q / cs);
        float xx = 0.1 + 0.8 * hash(cell + 1.3);
        float line = step(hash(cell), rain) * smoothstep(0.09, 0.0, abs(f.x - xx)) * smoothstep(0.0, 0.35, f.y) * smoothstep(1.0, 0.6, f.y);
        col += float3(0.55, 0.62, 0.75) * line * 0.2;
    }
    if (snow > 0.0) {
        for (int L = 0; L < 2; L++) {
            float fl = float(L);
            float sz = 34.0 + fl * 26.0;
            float2 q = px;
            q.y -= iTime * (24.0 + fl * 16.0);
            q.x -= drift.x * iTime * (10.0 + fl * 6.0) + sin(iTime * 0.6 + q.y * 0.02 + fl * 2.0) * 7.0;
            float2 cell = floor(q / sz);
            float2 f = fract(q / sz) - 0.5;
            float2 off = float2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
            float dd = length((f - off * 0.7) * sz);
            col += float3(0.86, 0.89, 0.95) * step(hash(cell + fl * 17.0), snow) * smoothstep(2.6 + fl * 1.6, 0.4, dd) * (0.45 + fl * 0.25);
        }
    }

    col *= mix(1.0, 0.18, smoothstep(0.38, 0.95, uv.y));
    return half4(half3(col), 1.0);
}
"""
