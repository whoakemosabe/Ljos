package app.ljos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.L
import app.ljos.data.HOUR_MS
import app.ljos.data.MIN_MS
import app.ljos.data.SwPoint
import app.ljos.model.HourScore
import app.ljos.model.MoonTimeline
import app.ljos.model.Night
import app.ljos.model.Weather
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A glass card whose title row folds it to a single summary line. The folded state is remembered
 * between launches. Content unfolds with a spring; the chevron turns as it goes.
 */
@Composable
internal fun CollapsibleCard(
    title: String,
    subtitle: String?,
    summary: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    val turn by animateFloatAsState(if (collapsed) 0f else 180f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "chevron")
    Column(modifier.glass()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    Haptics.tap(view); onToggle()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                AnimatedContent(
                    targetState = collapsed,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                    label = "cardSub",
                ) { folded ->
                    val line = if (folded) summary else subtitle
                    if (line != null) Text(line, color = if (folded) Muted else Faint, fontSize = 12.sp, maxLines = if (folded) 1 else 3)
                }
            }
            Canvas(Modifier.size(22.dp).graphicsLayer { rotationZ = turn }) {
                val sw = 1.6.dp.toPx()
                val w = size.width
                val path = Path().apply {
                    moveTo(w * 0.32f, w * 0.42f)
                    lineTo(w * 0.5f, w * 0.6f)
                    lineTo(w * 0.68f, w * 0.42f)
                }
                drawPath(path, Faint, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 300f)) + fadeIn(tween(260, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 420f)) + fadeOut(tween(140)),
        ) {
            Column { content() }
        }
    }
}

/** Kp · cloud · moon · dark, as tappable chips under the score. */
@Composable
internal fun ConditionChips(peak: HourScore, night: Night, now: Long, onChip: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("kp", "Kp ${Fmt.one(peak.kp)}", onChip) { KpIcon(it) }
        Chip("cloud", if (peak.cloud < 0) "—" else "${peak.cloud}%", onChip) { CloudIcon(it) }
        Chip("moon", "${(peak.moonIllum * 100).roundToInt()}%", onChip) { MoonIcon(it, peak.moonIllum.toFloat()) }
        val darkText = when {
            night.darkFrom == null -> "—"
            now < night.darkFrom -> Fmt.hhmm(night.darkFrom)
            night.darkUntil != null && now < night.darkUntil -> L.t("until ", "til ") + Fmt.hhmm(night.darkUntil)
            else -> Fmt.hhmm(night.darkFrom)
        }
        Chip("dark", darkText, onChip) { DarkIcon(it) }
    }
}

@Composable
private fun Chip(id: String, text: String, onChip: (String) -> Unit, icon: @Composable (Modifier) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x1AFFFFFF))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp))
            .clickable { Haptics.tap(view); onChip(id) }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Ink, fontSize = 13.sp)
    }
}

@Composable
private fun KpIcon(m: Modifier) = Canvas(m) {
    // Little aurora wave
    val p = Path().apply {
        moveTo(0f, size.height * 0.7f)
        cubicTo(size.width * 0.3f, size.height * 0.1f, size.width * 0.55f, size.height * 0.95f, size.width, size.height * 0.3f)
    }
    drawPath(p, Green, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
}

@Composable
private fun CloudIcon(m: Modifier) = Canvas(m) {
    val w = size.width
    val h = size.height
    drawCircle(Muted, w * 0.22f, Offset(w * 0.35f, h * 0.58f))
    drawCircle(Muted, w * 0.28f, Offset(w * 0.58f, h * 0.48f))
    drawRoundRect(Muted, Offset(w * 0.12f, h * 0.55f), Size(w * 0.76f, h * 0.28f), androidx.compose.ui.geometry.CornerRadius(h * 0.14f))
}

@Composable
private fun MoonIcon(m: Modifier, illum: Float) = Canvas(m) {
    val r = size.minDimension / 2f - 1f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(Color(0xFFF3E7C1), r, c)
    val shift = 2f * r * illum.coerceIn(0f, 1f)
    if (shift < 2f * r - 0.5f) drawCircle(Color(0xFF1A2232), r * 1.02f, Offset(c.x - shift, c.y))
}

@Composable
private fun DarkIcon(m: Modifier) = Canvas(m) {
    // Sun below the horizon
    val w = size.width
    drawLine(Muted, Offset(0f, w * 0.55f), Offset(w, w * 0.55f), 1.5.dp.toPx(), StrokeCap.Round)
    drawArc(Violet, 0f, 180f, true, Offset(w * 0.25f, w * 0.3f), Size(w * 0.5f, w * 0.5f))
}

/** Countdown under the score: what happens next tonight. */
internal fun countdownLine(night: Night, now: Long): String? {
    val peak = night.peak ?: return null
    fun span(ms: Long): String {
        val m = (ms / MIN_MS).coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
    return when {
        night.darkFrom != null && now < night.darkFrom ->
            L.t("Dark in ${span(night.darkFrom - now)} · peak around ${Fmt.hhmm(peak.time)}", "Myrkur eftir ${span(night.darkFrom - now)} · hámark um ${Fmt.hhmm(peak.time)}")
        now < peak.time ->
            L.t("Peak in ${span(peak.time - now)}, around ${Fmt.hhmm(peak.time)}", "Hámark eftir ${span(peak.time - now)}, um ${Fmt.hhmm(peak.time)}")
        night.darkUntil != null && now < night.darkUntil ->
            L.t("Dark until ${Fmt.hhmm(night.darkUntil)}", "Myrkur til ${Fmt.hhmm(night.darkUntil)}")
        else -> null
    }
}

/** Shown when tomorrow is clearly better than tonight. */
@Composable
internal fun TomorrowNudge(tomorrowScore: Int, onTap: () -> Unit) {
    val view = LocalView.current
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0x263DFFA0), Color(0x1AB79CFF))))
            .border(1.dp, Color(0x333DFFA0), RoundedCornerShape(16.dp))
            .clickable { Haptics.tap(view); onTap() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(L.t("Tomorrow looks better", "Morgundagurinn lítur betur út"), color = Ink, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Text("$tomorrowScore ↗", color = scoreColor(tomorrowScore), fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Hour by hour, with "why" folded in: tap a bar and its factors open right under the strip.
 * A Tonight/Tomorrow switch sits on top.
 */
@Composable
internal fun HourCard(
    night: Night,
    sel: HourScore?,
    explicitSelection: Boolean,
    now: Long,
    moon: MoonTimeline?,
    tomorrow: Boolean,
    hasTomorrow: Boolean,
    onTomorrow: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onClearSelection: () -> Unit,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val peak = night.peak
    CollapsibleCard(
        title = L.t("Hour by hour", "Klukkustund fyrir klukkustund"),
        subtitle = L.t("Tap a bar to see why · tap it again to close", "Ýttu á súlu til að sjá af hverju · ýttu aftur til að loka"),
        summary = peak?.let {
            (if (tomorrow) L.t("Tomorrow ", "Á morgun ") else "") + L.t("peak ", "hámark ") + "${Fmt.hhmm(it.time)} · ${it.score}"
        } ?: "—",
        collapsed = collapsed,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        if (hasTomorrow) {
            Spacer(Modifier.height(12.dp))
            SlidingSegments(
                listOf(L.t("Tonight", "Í kvöld"), L.t("Tomorrow", "Á morgun")),
                if (tomorrow) 1 else 0,
                Modifier.fillMaxWidth(),
            ) { onTomorrow(it == 1) }
        }
        Spacer(Modifier.height(14.dp))
        HourStrip(
            night.hours, sel?.time, now,
            onSelect = onSelect,
            onTapSelected = onClearSelection,
        )
        if (moon != null) {
            Spacer(Modifier.height(10.dp))
            MoonLine(night.hours, moon)
        }
        AnimatedVisibility(
            visible = explicitSelection && sel != null,
            enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 300f)) + fadeIn(tween(240, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 420f)) + fadeOut(tween(140)),
        ) {
            sel?.let { WhyPanel(it) }
        }
    }
}

/** Temperature, feels-like and wind at the hour you'd be outside, with what to wear. */
@Composable
internal fun DressCard(w: Weather, at: Long, place: String, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    fun t(c: Float) = if (L.miles) "${(c * 9f / 5f + 32f).roundToInt()}°F" else "${c.roundToInt()}°"
    val windText = if (w.wind.isNaN()) "" else if (L.miles) " · ${(w.wind * 2.237f).roundToInt()} mph" else " · ${w.wind.roundToInt()} m/s"
    val advice = when {
        w.feels <= -10f -> L.t("Serious cold. Layers, hat, gloves, warm boots — and a flask.", "Mikill kuldi. Lög af fötum, húfa, vettlingar, hlý stígvél — og hitabrúsi.")
        w.feels <= 0f -> L.t("Cold. Warm jacket, hat and gloves; you'll be standing still.", "Kalt. Hlý úlpa, húfa og vettlingar; þú stendur kyrr.")
        w.feels <= 6f -> L.t("Chilly. A proper jacket and a hat.", "Svalt. Almennileg úlpa og húfa.")
        else -> L.t("Mild for aurora night. A light jacket will do.", "Milt fyrir norðurljósakvöld. Létt úlpa dugar.")
    }
    CollapsibleCard(
        title = L.t("Dress for it", "Klæddu þig rétt"),
        subtitle = L.t("At ${Fmt.hhmm(at)} in $place", "Kl. ${Fmt.hhmm(at)} í $place"),
        summary = L.t("${t(w.temp)}, feels like ${t(w.feels)}", "${t(w.temp)}, finnst eins og ${t(w.feels)}"),
        collapsed = collapsed,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(t(w.temp), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(12.dp))
            Text(
                L.t("feels like ${t(w.feels)}", "finnst eins og ${t(w.feels)}") + windText,
                color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(advice, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Phone camera starting points for tonight's brightness. */
@Composable
internal fun PhotoTipsCard(score: Int, moonBright: Boolean, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val (shutter, iso) = when {
        score >= 80 -> "0.5–2 s" to "800"
        score >= 60 -> "2–4 s" to "800–1600"
        else -> "4–8 s" to "1600–3200"
    }
    val tips = listOf(
        L.t("Night mode or Pro: $shutter, ISO $iso", "Næturstilling eða Pro: $shutter, ISO $iso"),
        L.t("Focus at infinity (∞), or tap a bright star", "Fókus á óendanlegt (∞), eða ýttu á bjarta stjörnu"),
        L.t("Rest the phone on something solid; use a 3 s timer", "Hvíldu símann á einhverju stöðugu; notaðu 3 sek. teljara"),
        if (moonBright) L.t("Bright moon: drop ISO a step, it'll light the landscape for you", "Bjart tungl: lækkaðu ISO um eitt þrep, það lýsir landslagið")
        else L.t("Dark sky: include a foreground — a lighthouse, rocks, the shoreline", "Dimmur himinn: hafðu forgrunn með — vita, kletta, fjöruna"),
    )
    CollapsibleCard(
        title = L.t("Photo tips", "Ljósmyndaráð"),
        subtitle = L.t("Starting points for tonight's brightness", "Upphafsstillingar fyrir birtu kvöldsins"),
        summary = L.t("$shutter · ISO $iso · focus ∞", "$shutter · ISO $iso · fókus ∞"),
        collapsed = collapsed,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(10.dp))
        tips.forEach { tip ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Text("•", color = Green, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Text(tip, color = Ink, fontSize = 14.sp)
            }
        }
    }
}

/** The last two hours of Bz at L1 as a slim line; minutes pointing south are shaded green. */
@Composable
internal fun BzSparkline(points: List<SwPoint>, now: Long, modifier: Modifier) {
    Canvas(modifier) {
        val t0 = now - 2 * HOUR_MS
        val span = (2 * HOUR_MS).toFloat()
        val maxAbs = max(8.0, points.maxOf { abs(it.bz) }).toFloat()
        val mid = size.height / 2f
        fun x(t: Long) = ((t - t0) / span).coerceIn(0f, 1f) * size.width
        fun y(bz: Double) = mid - (bz.toFloat() / maxAbs) * (size.height / 2f - 2.dp.toPx())

        // Southward area
        val fill = Path().apply {
            moveTo(x(points.first().time), mid)
            points.forEach { p -> lineTo(x(p.time), if (p.bz < 0) y(p.bz) else mid) }
            lineTo(x(points.last().time), mid)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(Color.Transparent, Green.copy(alpha = 0.45f)), startY = mid, endY = size.height))
        drawLine(Color(0x26FFFFFF), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val line = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.time), y(p.bz)) else lineTo(x(p.time), y(p.bz)) }
        }
        drawPath(line, Ink.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        val last = points.last()
        drawCircle(if (last.bz < 0) Green else Ink, 3.dp.toPx(), Offset(x(last.time), y(last.bz)))
    }
}
