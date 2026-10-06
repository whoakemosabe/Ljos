package app.ljos.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.ljos.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.L
import app.ljos.data.HOUR_MS
import app.ljos.data.Updater
import app.ljos.model.HourScore
import app.ljos.model.KpDay
import app.ljos.model.MoonTimeline
import kotlin.math.roundToInt

private val MoonGold = Color(0xFFF3E7C1)
private val Pacifico = FontFamily(Font(R.font.pacifico))

/**
 * A hairline under the hour strip showing when the moon is up. Brighter moon, brighter line.
 * Shares the strip's time axis so segments sit under the right bars.
 */
@Composable
internal fun MoonLine(hours: List<HourScore>, moon: MoonTimeline, modifier: Modifier = Modifier) {
    if (hours.isEmpty()) return
    val start = hours.first().time
    val end = hours.last().time + HOUR_MS
    val glow = (0.25f + 0.75f * moon.illumination.toFloat()).coerceIn(0.25f, 1f)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val y = size.height / 2f
            val track = 2.dp.toPx()
            drawRoundRect(Color(0x14FFFFFF), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track))
            fun x(t: Long) = ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f) * size.width
            moon.upSegments.forEach { (a, b) ->
                val x0 = x(a)
                val x1 = x(b)
                if (x1 - x0 < 1f) return@forEach
                // Soft halo, then the line itself, fading in and out at the ends
                drawRoundRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, MoonGold.copy(alpha = 0.18f * glow), MoonGold.copy(alpha = 0.18f * glow), Color.Transparent),
                        startX = x0, endX = x1,
                    ),
                    Offset(x0, y - 4.dp.toPx()), Size(x1 - x0, 8.dp.toPx()), CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    Brush.horizontalGradient(
                        listOf(MoonGold.copy(alpha = 0.3f * glow), MoonGold.copy(alpha = glow), MoonGold.copy(alpha = 0.3f * glow)),
                        startX = x0, endX = x1,
                    ),
                    Offset(x0, y - track / 2), Size(x1 - x0, track), CornerRadius(track),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonPhase(moon.illumination, moon.waxing, Modifier.size(11.dp))
            Spacer(Modifier.width(6.dp))
            val pct = (moon.illumination * 100).roundToInt()
            val parts = buildList {
                add(L.t("Moon $pct%", "Tungl $pct%"))
                moon.rise?.let { add(L.t("rises ", "rís ") + Fmt.hhmm(it)) }
                moon.set?.let { add(L.t("sets ", "sest ") + Fmt.hhmm(it)) }
                if (moon.upSegments.isEmpty()) add(L.t("down all night", "undir sjóndeildarhring í nótt"))
            }
            Text(parts.joinToString(" · "), color = Faint, fontSize = 11.sp, letterSpacing = 0.3.sp)
        }
    }
}

@Composable
private fun MoonPhase(illumination: Double, waxing: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(r, r)
        drawCircle(Color(0x33FFFFFF), r, c)
        drawCircle(MoonGold, r, c)
        // Shadow disc slides across: offset 0 = new moon, 2r = full
        val shift = (2f * r * illumination.toFloat()).coerceIn(0f, 2f * r)
        val shadowX = if (waxing) c.x - shift else c.x + shift
        if (shift < 2f * r - 0.5f) drawCircle(Color(0xFF0B1220), r * 1.02f, Offset(shadowX, c.y))
    }
}

/** Three slim rows of Kp bars: today, tomorrow and the day after. */
@Composable
internal fun KpOutlookCard(days: List<KpDay>, now: Long) {
    Column(Modifier.glass()) {
        CardTitle(L.t("Next 3 days", "Næstu 3 dagar"), L.t("Solar activity forecast, 3-hour blocks", "Spá um sólvirkni, 3 tíma bil"))
        Spacer(Modifier.height(12.dp))
        days.forEachIndexed { i, d ->
            val name = when (i) {
                0 -> L.t("Today", "Í dag")
                1 -> L.t("Tomorrow", "Á morgun")
                else -> Fmt.weekday(d.dayStart)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(name, color = Ink, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                Canvas(Modifier.weight(1f).height(26.dp)) {
                    val slot = size.width / 8f
                    val w = slot * 0.62f
                    for (b in 0 until 8) {
                        val blockStart = d.dayStart + b * 3 * HOUR_MS
                        val kp = d.blocks.firstOrNull { it.time == blockStart }?.kp
                        val x = b * slot + (slot - w) / 2f
                        drawRoundRect(Color(0x10FFFFFF), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2f))
                        if (kp != null) {
                            val h = (size.height * (kp / 7.0).toFloat()).coerceIn(w, size.height)
                            val col = when {
                                kp >= 5 -> Violet
                                kp >= 3 -> Green
                                else -> Teal.copy(alpha = 0.75f)
                            }
                            drawRoundRect(col, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2f))
                        }
                        if (now >= blockStart && now < blockStart + 3 * HOUR_MS) {
                            drawCircle(Color.White, 1.5.dp.toPx(), Offset(x + w / 2f, size.height + 3.dp.toPx()))
                        }
                    }
                }
                val max = d.maxKp
                Text(
                    if (max == null) "—" else "Kp ${Fmt.one(max)}",
                    color = when {
                        max == null -> Faint
                        max >= 5 -> Violet
                        max >= 3 -> Green
                        else -> Muted
                    },
                    fontSize = 12.sp,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
    }
}

/** "made with ♥ in Njarðvík" — a beating aurora heart and a slow shimmer across the town name. */
@Composable
internal fun MadeWithLove(errors: List<String>) {
    val context = LocalContext.current
    val version = remember { Updater.installedVersion(context) }
    val t = rememberInfiniteTransition(label = "love")
    // Heart colour drifts green ↔ violet; slow ease so it breathes rather than blinks.
    val drift by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift",
    )
    // Town name: a long green–violet ribbon sliding through the letters.
    val flow by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Restart),
        label = "flow",
    )

    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        errors.forEach { Text(it, color = Warn.copy(alpha = 0.8f), fontSize = 11.sp) }
        if (errors.isNotEmpty()) Spacer(Modifier.height(10.dp))

        // Aurora hairline
        Canvas(Modifier.width(120.dp).height(1.dp)) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Teal, Green, Violet, Color.Transparent)))
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.t("MADE WITH", "GERT MEÐ"), color = Faint, fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                val h = size.height
                val heart = Path().apply {
                    moveTo(0.5f * w, 0.92f * h)
                    cubicTo(0.18f * w, 0.70f * h, 0f, 0.50f * h, 0f, 0.30f * h)
                    cubicTo(0f, 0.12f * h, 0.14f * w, 0f, 0.29f * w, 0f)
                    cubicTo(0.39f * w, 0f, 0.46f * w, 0.06f * h, 0.5f * w, 0.14f * h)
                    cubicTo(0.54f * w, 0.06f * h, 0.61f * w, 0f, 0.71f * w, 0f)
                    cubicTo(0.86f * w, 0f, w, 0.12f * h, w, 0.30f * h)
                    cubicTo(w, 0.50f * h, 0.82f * w, 0.70f * h, 0.5f * w, 0.92f * h)
                    close()
                }
                val a = lerp(Green, Violet, drift)
                val b = lerp(Violet, Green, drift)
                drawCircle(a.copy(alpha = 0.16f), w * 0.95f, Offset(w / 2f, h / 2f))
                drawPath(heart, Brush.linearGradient(listOf(a, b), Offset(0f, h), Offset(w, 0f)))
            }
            Spacer(Modifier.width(8.dp))
            Text(L.t("IN", "Í"), color = Faint, fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "Njarðvík",
            style = TextStyle(
                fontFamily = Pacifico,
                fontSize = 28.sp,
                brush = Brush.linearGradient(
                    colors = listOf(Green, Color(0xFF8FF5D0), Violet, Color(0xFFD9C8FF), Green),
                    start = Offset(-flow * 600f, 0f),
                    end = Offset(-flow * 600f + 600f, 120f),
                    tileMode = TileMode.Repeated,
                ),
            ),
        )
        Spacer(Modifier.height(10.dp))
        Text("Ljós $version · NOAA SWPC · Open-Meteo", color = Faint.copy(alpha = 0.6f), fontSize = 10.sp, letterSpacing = 0.5.sp)
    }
}
