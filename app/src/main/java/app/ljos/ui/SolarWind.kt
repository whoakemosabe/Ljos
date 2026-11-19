package app.ljos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.L
import app.ljos.data.HOUR_MS
import app.ljos.data.SwPoint
import app.ljos.model.Live
import app.ljos.model.NowState

/*
 * The solar wind, explained with its two readings: Bz (which way the field points) and Bt (how
 * strong it is). The strip under the score stays small; tapping it opens the Solar wind sheet.
 * The strip word comes from the core's Live.Drive (the Newell coupling of what is arriving now),
 * so it agrees with the score and the look-up alert. The Bz/Bt verdicts describe the readings.
 * The web app (app.js, renderNow/renderWind) uses the same words and limits.
 */

/** Bz: south (minus) opens the door, north (plus) keeps it shut. Same limits as the strip word. */
internal enum class BzLevel { South, Neutral, North }
internal fun bzLevel(bz: Double): BzLevel = when { bz <= -3 -> BzLevel.South; bz >= 3 -> BzLevel.North; else -> BzLevel.Neutral }

/** Bt in nT: below 5 weak, 5–10 moderate, 10–20 strong, 20+ very strong. */
internal enum class BtLevel { Weak, Moderate, Strong, VeryStrong }
internal fun btLevel(bt: Double): BtLevel = when { bt < 5 -> BtLevel.Weak; bt < 10 -> BtLevel.Moderate; bt < 20 -> BtLevel.Strong; else -> BtLevel.VeryStrong }

private fun bzWord(l: BzLevel) = when (l) {
    BzLevel.South -> L.t("south", "suður")
    BzLevel.Neutral -> L.t("near zero", "nálægt núlli")
    BzLevel.North -> L.t("north", "norður")
}
private fun btWord(l: BtLevel) = when (l) {
    BtLevel.Weak -> L.t("weak", "veikt")
    BtLevel.Moderate -> L.t("moderate", "miðlungs")
    BtLevel.Strong -> L.t("strong", "sterkt")
    BtLevel.VeryStrong -> L.t("very strong", "mjög sterkt")
}
private fun bzColor(l: BzLevel) = when (l) { BzLevel.South -> Green; BzLevel.Neutral -> Ink; BzLevel.North -> Muted }

/** Strip word and colour for what is arriving now. */
internal fun driveWord(d: Live.Drive): Pair<String, Color> = when (d) {
    Live.Drive.STRONG -> L.t("Strong", "Sterkt") to Green
    Live.Drive.HELPING -> L.t("Helping", "Hjálpar") to Green
    Live.Drive.NEUTRAL -> L.t("Neutral", "Hlutlaust") to Ink
    Live.Drive.BLOCKING -> L.t("Blocking", "Lokar") to Muted
}

/** km/s: below 400 slow, 400–500 normal, 500–700 fast, 700+ very fast. */
private fun speedWord(v: Double) = when {
    v < 400 -> L.t("slow", "hægur"); v < 500 -> L.t("normal", "venjulegur"); v < 700 -> L.t("fast", "hraður"); else -> L.t("very fast", "mjög hraður")
}
/** cm⁻³: below 3 low, 3–10 normal, 10–20 high, 20+ very high. */
private fun densityWord(n: Double) = when {
    n < 3 -> L.t("low", "lítill"); n < 10 -> L.t("normal", "venjulegur"); n < 20 -> L.t("high", "mikill"); else -> L.t("very high", "mjög mikill")
}

private val Sun = Color(0xFFFFD27A)

/** "Bz −6.2 south · Bt 9.4 moderate", or null without fresh data. */
internal fun readingsLine(st: NowState): String? {
    val bz = st.bz?.takeIf { st.bzFresh } ?: return null
    val b = "Bz ${Fmt.signed(bz)} ${bzWord(bzLevel(bz))}"
    val t = st.bt?.let { " · Bt ${Fmt.one(it)} ${btWord(btLevel(it))}" } ?: ""
    return b + t
}

/** One plain sentence for the top of the sheet, about what is arriving here now. */
private fun headline(st: NowState): String = when (st.drive) {
    null -> L.t("No live solar wind data right now.", "Engin lifandi sólvindsgögn núna.")
    Live.Drive.STRONG -> L.t("Strong solar wind is arriving. Aurora can be bright.", "Sterkur sólvindur er að berast. Norðurljós geta orðið björt.")
    Live.Drive.HELPING -> L.t("What is arriving now helps. Aurora can switch on.", "Það sem berst núna hjálpar. Norðurljós geta kviknað.")
    Live.Drive.NEUTRAL -> L.t("What is arriving now is neutral. Not much is getting in.", "Það sem berst núna er hlutlaust. Lítið kemst inn.")
    Live.Drive.BLOCKING -> L.t("What is arriving now blocks most aurora.", "Það sem berst núna lokar á flest norðurljós.")
}

/** What the two readings mean together, tonight. */
private fun together(bz: Double, bt: Double?): String {
    val bzS = Fmt.signed(bz)
    val btS = bt?.let { Fmt.one(it) }
    return when (bzLevel(bz)) {
        BzLevel.South -> {
            val first = L.t("Bz $bzS is south, so the door is open.", "Bz $bzS er suður, svo dyrnar eru opnar.")
            val second = when (bt?.let { btLevel(it) }) {
                null -> ""
                BtLevel.Weak -> L.t(" Bt $btS is weak, so expect a soft show.", " Bt $btS er veikt, svo búist við daufum ljósum.")
                BtLevel.Moderate -> L.t(" Bt $btS is moderate. If Bt goes above 10, expect more.", " Bt $btS er miðlungs. Ef Bt fer yfir 10 má búast við meiru.")
                else -> L.t(" Bt $btS is strong, so a lot can come through.", " Bt $btS er sterkt, svo mikið getur komist í gegn.")
            }
            first + second
        }
        BzLevel.Neutral -> L.t("Bz $bzS is near zero, so the door is only a little open. Watch for it to drop below −3.",
            "Bz $bzS er nálægt núlli, svo dyrnar eru rétt opnar. Fylgstu með hvort það fer undir −3.")
        BzLevel.North -> L.t("Bz $bzS is north, so the door is shut." + (btS?.let { " Bt $it does not matter much until Bz turns minus." } ?: ""),
            "Bz $bzS er norður, svo dyrnar eru lokaðar." + (btS?.let { " Bt $it skiptir litlu fyrr en Bz verður mínus." } ?: ""))
    }
}

/**
 * Under the big number: the solar wind in one line of words and one line of readings, with the
 * last two hours as a chart where up is good. Tap to open the Solar wind sheet.
 */
@Composable
internal fun SolarWindStrip(st: NowState, solarWind: List<SwPoint>, now: Long, onOpen: () -> Unit) {
    val view = LocalView.current
    val (word, color) = when {
        st.lookUp -> L.t("Look up now", "Líttu upp núna") to Green
        st.drive == null -> L.t("No live data", "Engin lifandi gögn") to Faint
        else -> driveWord(st.drive!!)
    }
    val backdrop = LocalGlassBackdrop.current
    val angle = LocalGlassAngle.current
    val shape = RoundedCornerShape(20.dp)
    val glass: Modifier = if (backdrop != null) Modifier.glassControl(backdrop, shape, angle, lensHeight = 10.dp, lensAmount = 16.dp)
    else Modifier.clip(shape).background(Color(0x1AFFFFFF)).border(1.dp, Color(0x14FFFFFF), shape)
    Column(
        Modifier
            .fillMaxWidth()
            .then(glass)
            .then(if (st.lookUp) Modifier.background(Brush.horizontalGradient(listOf(Color(0x403DFFA0), Color(0x20B79CFF))), shape) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Haptics.tap(view); onOpen() }
            .semantics { contentDescription = L.t("Right now: $word. Open solar wind details", "Núna: $word. Opna sólvindsupplýsingar") }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.t("Right now", "Núna"), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(word, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(st.score.toString(), color = scoreColor(st.score), fontSize = 20.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(6.dp))
            // A ">" : the strip opens a sheet now, it no longer folds down.
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val p = Path().apply { moveTo(w * 0.42f, w * 0.3f); lineTo(w * 0.6f, w * 0.5f); lineTo(w * 0.42f, w * 0.7f) }
                drawPath(p, Faint, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (st.shock) Text(L.t("Shock just passed the satellite", "Höggbylgja fór framhjá gervitunglinu"), color = Sun, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        readingsLine(st)?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
        val recent = solarWind.filter { now - it.time <= 2 * HOUR_MS }
        if (recent.size > 5) {
            Spacer(Modifier.height(6.dp))
            GoodUpChart(recent, now, 34)
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(L.t("Bz, last 2 h", "Bz, síðustu 2 klst"), color = Faint, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                st.etaMinutes?.let { Text(L.t("reaches us in ~$it min", "nær okkur eftir ~$it mín"), color = Faint, fontSize = 11.sp) }
            }
        }
    }
}

/** Bz chart flipped so up is good, like the score, with "Helps" and "Blocks" at the side. */
@Composable
private fun GoodUpChart(points: List<SwPoint>, now: Long, heightDp: Int) {
    Row(Modifier.fillMaxWidth().height(heightDp.dp)) {
        BzSparkline(points, now, Modifier.weight(1f).fillMaxHeight(), goodUp = true)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(L.t("Helps", "Hjálpar"), color = Green, fontSize = 10.sp, lineHeight = 11.sp)
            Text(L.t("Blocks", "Lokar"), color = Muted, fontSize = 10.sp, lineHeight = 11.sp)
        }
    }
}

/**
 * The Solar wind sheet. Top: what is arriving here now (the strip word, the score's input).
 * Then the readings at the satellite now, one by one, with what they mean, and the rule of thumb.
 */
@Composable
internal fun SolarWindContent(st: NowState, solarWind: List<SwPoint>, now: Long) {
    val bz = st.bz?.takeIf { st.bzFresh }
    val bt = st.bt?.takeIf { st.bzFresh }
    val level = bz?.let { bzLevel(it) }
    val bzTone = level?.let { bzColor(it) } ?: Faint
    val (_, driveTone) = st.drive?.let { driveWord(it) } ?: ("" to Faint)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(headline(st), color = driveTone, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (st.shock) {
            InfoBox(
                L.t("A shock just passed the satellite: density and speed jumped." +
                    (st.etaMinutes?.let { " It reaches us in about $it min." } ?: "") + " Bz can swing fast after a shock, so keep an eye on it.",
                    "Höggbylgja fór framhjá gervitunglinu: þéttleiki og hraði jukust." +
                    (st.etaMinutes?.let { " Hún nær okkur eftir um $it mín." } ?: "") + " Bz getur sveiflast hratt eftir höggbylgju, svo fylgstu með."),
                Sun,
            )
        }

        val recent = solarWind.filter { now - it.time <= 2 * HOUR_MS }
        if (recent.size > 5) {
            Column {
                Text(L.t("Bz at the satellite, last 2 hours", "Bz við gervitunglið, síðustu 2 klst"), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GoodUpChart(recent, now, 58)
                Row(Modifier.fillMaxWidth().padding(top = 3.dp, end = 50.dp)) {
                    Text(L.t("2 h ago", "fyrir 2 klst"), color = Faint, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(L.t("now", "núna"), color = Faint, fontSize = 11.sp)
                }
            }
        }

        Section(L.t("ARRIVING HERE NOW", "BERST HINGAÐ NÚNA"))
        Journey(st.etaMinutes)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            st.bzSustained?.let {
                val (w, c) = when (bzLevel(it)) {
                    BzLevel.South -> L.t("Mostly south", "Aðallega suður") to Green
                    BzLevel.Neutral -> L.t("Near zero", "Nálægt núlli") to Ink
                    BzLevel.North -> L.t("Mostly north", "Aðallega norður") to Muted
                }
                Row2(L.t("Bz, 20 min average", "Bz, 20 mín meðaltal"), "$w (${Fmt.signed(it)})", c)
            }
            st.btSustained?.let { Row2(L.t("Bt, 20 min average", "Bt, 20 mín meðaltal"), "${btWord(btLevel(it)).replaceFirstChar { c -> c.uppercase() }} (${Fmt.one(it)})", Ink) }
            Row2(L.t("Clouds here", "Ský hér"), Fmt.cloud(st.cloud), Ink)
            st.clearerSpot?.let { Row2(L.t("Clearer at", "Heiðskírara við"), "${it.spot.name} (${Fmt.cloud(it.cloud)})", Green) }
        }

        Section(L.t("AT THE SATELLITE NOW", "VIÐ GERVITUNGLIÐ NÚNA"))
        if (bz != null) {
            ReadingTile(
                name = "Bz", what = L.t("direction", "stefna"), value = Fmt.signed(bz), unit = "nT",
                verdict = when (level!!) {
                    BzLevel.South -> L.t("South · helping", "Suður · hjálpar")
                    BzLevel.Neutral -> L.t("Near zero · neutral", "Nálægt núlli · hlutlaust")
                    BzLevel.North -> L.t("North · blocking", "Norður · lokar")
                },
                color = bzTone,
                // +10 … −10, zero in the middle; marks at +3, 0, −3 (the limits) and −5 (the rule of thumb).
                pos = ((10 - bz) / 20).toFloat(),
                ticks = listOf(0.35f, 0.5f, 0.65f, 0.75f),
                bar = listOf(Muted.copy(alpha = 0.35f), Color(0x26FFFFFF), Color(0x26FFFFFF), Green),
                stops = listOf(0f, 0.35f, 0.65f, 1f),
                labels = listOf(L.t("+10 north", "+10 norður"), "0", L.t("−10 south", "−10 suður")),
                note = L.t("Which way the field points. Minus (south) opens the door for aurora. Plus (north) keeps it shut.",
                    "Hvert segulsviðið snýr. Mínus (suður) opnar dyrnar fyrir norðurljós. Plús (norður) heldur þeim lokuðum."),
            )
        } else {
            Text(L.t("No live Bz reading.", "Engin lifandi Bz mæling."), color = Faint, fontSize = 13.sp)
        }
        if (bt != null) {
            ReadingTile(
                name = "Bt", what = L.t("strength", "styrkur"), value = Fmt.one(bt), unit = "nT",
                verdict = btWord(btLevel(bt)).replaceFirstChar { it.uppercase() },
                color = Violet,
                // 0 … 20, labels evenly spaced so each sits on its value.
                pos = (bt / 20).toFloat(),
                ticks = listOf(0.25f, 0.5f, 0.75f),
                bar = listOf(Violet.copy(alpha = 0.15f), Violet.copy(alpha = 0.45f), Violet),
                stops = listOf(0f, 0.5f, 1f),
                labels = listOf("0", "5", "10", "15", "20+"),
                note = L.t("How strong the field is in total. Higher Bt makes a minus Bz count for more.",
                    "Hversu sterkt segulsviðið er í heild. Hærra Bt gerir mínus Bz áhrifameira."),
            )
        }
        if (st.speed != null || st.density != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                st.speed?.let { v ->
                    SmallTile(L.t("Speed", "Hraði"), "${v.toInt()}", "km/s", speedWord(v),
                        L.t("Faster wind brings more energy.", "Hraðari vindur ber meiri orku."), Modifier.weight(1f))
                }
                st.density?.let { n ->
                    SmallTile(L.t("Density", "Þéttleiki"), Fmt.one(n), "/cm³", densityWord(n),
                        L.t("Denser wind pushes harder.", "Þéttari vindur þrýstir fastar."), Modifier.weight(1f))
                }
            }
        }
        if (bz != null) InfoBox(together(bz, bt), bzTone)
        InfoBox(
            L.t("Best night: Bz below −5 and Bt above 10 in fast wind (500+ km/s), for 20 minutes or more.",
                "Besta kvöldið: Bz undir −5 og Bt yfir 10 í hröðum vindi (500+ km/s), í 20 mínútur eða lengur."),
            Violet,
        )
        Text(
            L.t("nT (nanotesla) is the unit for magnetic field strength. The readings come from a satellite between the Sun and Earth. The word at the top uses all four readings together.",
                "nT (nanótesla) er eining fyrir styrk segulsviðs. Mælingarnar koma frá gervitungli milli sólar og jarðar. Orðið efst notar alla fjóra lestrana saman."),
            color = Faint, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

/** Speed or density: name, value with unit, a one-word verdict and one short sentence. */
@Composable
private fun SmallTile(name: String, value: String, unit: String, verdict: String, note: String, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(Color(0x0AFFFFFF)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Ink, fontSize = 18.sp)
            Text(" $unit", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(verdict.replaceFirstChar { it.uppercase() }, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(note, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun Section(t: String) = Text(t, color = Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Row2(k: String, v: String, c: Color) = Row {
    Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
    Text(v, color = c, fontSize = 13.sp)
}

@Composable
private fun InfoBox(text: String, color: Color) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.07f)).border(1.dp, color.copy(alpha = 0.33f), shape).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Canvas(Modifier.padding(top = 1.dp).size(18.dp)) {
            val r = size.width / 2
            drawCircle(color, r - 0.7.dp.toPx(), style = Stroke(1.4.dp.toPx()))
            drawLine(color, Offset(r, r * 0.9f), Offset(r, r * 1.4f), 1.6.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color, 0.9.dp.toPx(), Offset(r, r * 0.6f))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

/** One reading: name, what it is, value in nT, a verdict, a scale with its marks, and one sentence. */
@Composable
private fun ReadingTile(
    name: String, what: String, value: String, unit: String, verdict: String, color: Color,
    pos: Float, ticks: List<Float>, bar: List<Color>, stops: List<Float>, labels: List<String>, note: String,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0x0AFFFFFF)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(what, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 1.dp))
            Spacer(Modifier.weight(1f))
            Text(value, color = Ink, fontSize = 18.sp)
            Text(" $unit", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(verdict, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Canvas(Modifier.fillMaxWidth().height(20.dp).semantics { contentDescription = "$name $value $unit: $verdict" }) {
            val track = 8.dp.toPx()
            val y = size.height / 2
            drawRoundRect(Brush.horizontalGradient(*stops.zip(bar).toTypedArray(), startX = 0f, endX = size.width), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track / 2))
            ticks.forEach { t -> drawLine(Color(0x4DFFFFFF), Offset(size.width * t, y - 6.dp.toPx()), Offset(size.width * t, y + 6.dp.toPx()), 1.dp.toPx()) }
            val x = pos.coerceIn(0f, 1f) * size.width
            drawCircle(color.copy(alpha = 0.4f), 11.dp.toPx(), Offset(x, y))
            drawCircle(Color.White, 8.dp.toPx(), Offset(x, y))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { Text(it, color = Faint, fontSize = 11.sp) }
        }
        Text(note, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Sun → satellite → you, with how long the solar wind takes to get here. */
@Composable
private fun Journey(eta: Int?) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0x0AFFFFFF)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = L.t("Sun, satellite, you", "Sól, gervitungl, þú") }) {
            val y = size.height / 2
            val r = 5.dp.toPx()
            val sunX = r * 2; val satX = size.width * 0.5f; val youX = size.width - r * 2
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            drawLine(Color(0x40FFFFFF), Offset(sunX + r * 2, y), Offset(satX - r * 2, y), 1.dp.toPx(), pathEffect = dash)
            drawLine(Green, Offset(satX + r * 2, y), Offset(youX - r * 2, y), 1.dp.toPx(), pathEffect = dash)
            drawCircle(Sun, r, Offset(sunX, y))
            drawRect(Violet, Offset(satX - r * 0.6f, y - r * 0.6f), Size(r * 1.2f, r * 1.2f), style = Stroke(1.4.dp.toPx()))
            drawLine(Violet, Offset(satX - r * 2, y), Offset(satX - r * 0.6f, y), 2.5.dp.toPx())
            drawLine(Violet, Offset(satX + r * 0.6f, y), Offset(satX + r * 2, y), 2.5.dp.toPx())
            drawCircle(Green, r * 1.5f, Offset(youX, y), style = Stroke(1.4.dp.toPx()))
        }
        Box(Modifier.fillMaxWidth()) {
            Text(L.t("Sun", "Sól"), color = Faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart))
            Text(L.t("Satellite", "Gervitungl"), color = Faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            Text(L.t("You", "Þú"), color = Faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Text(
            L.t("A satellite 1.5 million km away feels the solar wind first.", "Gervitungl í 1,5 milljón km fjarlægð mælir sólvindinn fyrst.") +
                (eta?.let { L.t(" What it sees now reaches Iceland in about $it min.", " Það sem það sér núna nær Íslandi eftir um $it mín.") } ?: ""),
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}
