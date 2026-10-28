package app.glasspane

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Glasspane tokens for Compose. Mirrors tokens/tokens.json exactly; edit that file first.
 * Lengths that are px on the web are dp here.
 */

/** RGB tints for the smoke (tokens.json → tints). */
object GlassTints {
    val Navy = Color(0xFF0C1222)
    val Night = Color(0xFF070B16)
    val Neutral = Color(0xFF767680)
    val White = Color(0xFFFFFFFF)
    val Warm = Color(0xFF22160E)
}

/** Coloured-glass accents (tokens.json → accents). */
object GlassAccents {
    val Aurora = Color(0xFF79E8B0)
    val Teal = Color(0xFF60C4C4)
    val Amber = Color(0xFFF3C77A)
    val Coral = Color(0xFFF2A48F)
    val Blue = Color(0xFF0071E3)
}

/**
 * One glass material: the eleven axes from SPEC.md.
 * [lightAngle] is in Kyant's convention (45 = top left). Web CSS angle = lightAngle + 115.
 */
@Immutable
data class GlassStyle(
    val thickness: Float,
    val clarity: Float,
    val smoke: Float,
    val frost: Dp,
    val refraction: Float,
    val dispersion: Float,
    val sheen: Float,
    val shadow: Float,
    val radius: Dp,
    val saturation: Float,
    val tint: Color,
    val lightAngle: Float = 45f,
    /** Light pages flip the outer hairline to black. */
    val onLight: Boolean = false,
) {
    private val t get() = thickness.coerceIn(0f, 1f)

    // ───── Derived (SPEC.md § Derived) ─────
    val band: Dp get() = (4f + 28f * t).dp
    val bend: Dp get() = (refraction * (10f + 36f * t)).dp
    val rimWidth: Dp get() = (0.5f + 0.5f * t).dp // Kyant's highlight is drawn sharper than CSS; half the web width reads the same
    val rimAlpha: Float get() = ((0.14f + 0.14f * t) * 3.6f).coerceIn(0f, 1f)
    val depthAlpha: Float get() = 0.04f + 0.26f * t
    val hairAlpha: Float get() = 0.06f + 0.05f * t
    val shadowY: Dp get() = (2f + 22f * t).dp
    val shadowBlur: Dp get() = ((10f + 50f * t) / 2f).dp // Kyant radius ≈ half a CSS blur
    val shadowAlpha: Float get() = (shadow * (0.22f + 0.40f * t)).coerceIn(0f, 1f)
    val smokeAlpha: Float get() = (smoke * 0.7f).coerceIn(0f, 1f)
    val hazeAlpha: Float get() = (1f - clarity.coerceIn(0f, 1f)) * 0.28f
    val blur: Dp get() = frost + ((1f - clarity.coerceIn(0f, 1f)) * 14f).dp
    val contrast: Float get() = 0.65f + 0.35f * clarity.coerceIn(0f, 1f)
    val brightness: Float get() = (1f - clarity.coerceIn(0f, 1f)) * 0.06f
    /** Kyant's dispersion is on/off. */
    val chromatic: Boolean get() = dispersion >= 0.35f
    /** Kyant's depth effect suits thick glass. */
    val depthEffect: Boolean get() = t >= 0.5f
    val hairline: Color get() = if (onLight) Color.Black else Color(0xFFBED2FF)
    val shadowColor: Color get() = if (onLight) Color(0xFF141828) else Color(0xFF000008)
}

/** The four materials (tokens.json → presets). */
object GlassPresets {
    /** korbenmade header pill. Floating controls: nav pills, tab bars, toggles, chips, toasts. */
    val Liquid = GlassStyle(
        thickness = 0.30f, clarity = 1f, smoke = 0.09f, frost = 3.dp, refraction = 1.0f, dispersion = 1.0f,
        sheen = 0.55f, shadow = 0.8f, radius = 999.dp, saturation = 1.75f, tint = GlassTints.White,
    )

    /** Large clear smoky pane with refracted edges: hero panels, big cards, see-through sheets. */
    val Smoky = GlassStyle(
        thickness = 0.18f, clarity = 0.95f, smoke = 0.34f, frost = 1.5.dp, refraction = 1.15f, dispersion = 0.6f,
        sheen = 0.12f, shadow = 0.55f, radius = 28.dp, saturation = 1.4f, tint = GlassTints.Night,
    )

    /** Ljós lobby Solid Glass: panels that carry text, settings, modals, data cards. */
    val Solid = GlassStyle(
        thickness = 1f, clarity = 0.9f, smoke = 0.72f, frost = 26.dp, refraction = 0.3f, dispersion = 0.3f,
        sheen = 0f, shadow = 1f, radius = 22.dp, saturation = 1.2f, tint = GlassTints.Navy,
    )

    /** korbenmade tinted frosted glass: quiet buttons and fields. */
    val Frosted = GlassStyle(
        thickness = 0.12f, clarity = 0.8f, smoke = 0.17f, frost = 20.dp, refraction = 0f, dispersion = 0f,
        sheen = 0f, shadow = 0.2f, radius = 999.dp, saturation = 1.8f, tint = GlassTints.Neutral,
    )

    /** The same materials for light screens (web: [data-gp-theme="light"]). */
    fun onLight(style: GlassStyle): GlassStyle = when (style) {
        Liquid -> style.copy(smoke = 0.57f, onLight = true)
        Solid -> style.copy(tint = GlassTints.White, smoke = 0.8f, onLight = true)
        Smoky -> style.copy(tint = GlassTints.White, smoke = 0.3f, onLight = true)
        else -> style.copy(onLight = true)
    }
}

/** Corner radii by role (tokens.json → radius). */
object GlassRadius {
    val Control = 999.dp
    val Chip = 12.dp
    val Field = 14.dp
    val Card = 22.dp
    val Pane = 28.dp
    val Sheet = 32.dp
}

/** Springs (tokens.json → motion.springs), for androidx.compose.animation.core.spring(). */
object GlassMotion {
    // stiffness / damping ratio = damping / (2·√stiffness)
    const val SnappyStiffness = 420f
    const val SnappyDamping = 0.78f
    const val GentleStiffness = 260f
    const val GentleDamping = 0.81f
    const val WobblyStiffness = 300f
    const val WobblyDamping = 0.52f
    const val PressScale = 0.97f
}
