package app.ljos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

val NightBg = Color(0xFF05070D)
val Ink = Color(0xFFE8F1FF)
val Muted = Color(0xA6E8F1FF)
val Faint = Color(0x66E8F1FF)
val Green = Color(0xFF3DFFA0)
val Teal = Color(0xFF2FD3C6)
val Violet = Color(0xFFB79CFF)
val Slate = Color(0xFF2E4A60)
val Warn = Color(0xFFFF8A80)

fun scoreColor(score: Int): Color {
    val s = score.coerceIn(0, 100)
    return when {
        s < 40 -> lerp(Slate, Teal, s / 40f)
        s < 80 -> lerp(Teal, Green, (s - 40) / 40f)
        else -> lerp(Green, Violet, (s - 80) / 20f)
    }
}

private val CardShape = RoundedCornerShape(24.dp)

/** Frosted card: translucent fill with a soft top sheen and hairline border. */
fun Modifier.glass(): Modifier = this
    .clip(CardShape)
    .background(Color(0x66070B16))
    .background(Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color(0x08FFFFFF))))
    .border(1.dp, Color(0x1FFFFFFF), CardShape)
    .padding(18.dp)

@Composable
fun LjosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            onPrimary = Color(0xFF03130B),
            secondary = Violet,
            background = NightBg,
            surface = NightBg,
            onSurface = Ink,
            onBackground = Ink,
            outline = Faint,
        ),
        content = content,
    )
}
