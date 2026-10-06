package app.ljos.ui

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.L
import app.ljos.data.Inputs
import app.ljos.model.HourScore
import app.ljos.model.Model
import app.ljos.model.Night
import app.ljos.model.NowState
import app.ljos.model.SpotScore
import kotlin.math.roundToInt

internal val Glow = Shadow(color = Color(0x99000000), blurRadius = 24f)

internal val HeroScoreStyle: TextStyle = TextStyle(
    color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.ExtraLight,
    lineHeight = 124.sp, shadow = Glow,
)

@Composable
internal fun Hero(
    night: Night?,
    inp: Inputs?,
    loading: Boolean,
    scoreAlpha: () -> Float = { 1f },
    onScorePlaced: (Offset, IntSize) -> Unit = { _, _ -> },
) {
    val peak = night?.peak
    val shown by animateIntAsState(peak?.score ?: 0, tween(1200), label = "score")
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 18.dp)) {
        Text(L.t("TONIGHT", "Í KVÖLD"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
        Text(
            if (peak != null) shown.toString() else "—",
            style = HeroScoreStyle,
            modifier = Modifier
                .onGloballyPositioned { onScorePlaced(it.positionInRoot(), it.size) }
                .graphicsLayer { alpha = if (peak != null) scoreAlpha() else 1f },
        )
        val headline = when {
            peak != null -> Model.label(peak.score)
            night != null -> L.t("Too bright for aurora", "Of bjart fyrir norðurljós")
            inp == null || inp.isEmpty -> if (loading) L.t("Reading the sky…", "Les himininn…") else L.t("No data yet — tap ↻", "Engin gögn enn — ýttu á ↻")
            else -> "—"
        }
        Text(headline, style = TextStyle(color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light, shadow = Glow))
        if (peak != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                L.t("Peak around ", "Hámark um ") + "${Fmt.hhmm(peak.time)} · Kp ${Fmt.one(peak.kp)} · ${Fmt.cloud(peak.cloud)}",
                color = Muted, fontSize = 14.sp,
            )
        }
        if (night?.darkFrom != null && night.darkUntil != null) {
            Text(L.t("Dark ", "Myrkur ") + "${Fmt.hhmm(night.darkFrom)}–${Fmt.hhmm(night.darkUntil)}", color = Faint, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun NowCard(st: NowState) {
    val accent = if (st.lookUp) Green else Ink
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (st.lookUp) {
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0x403DFFA0), Color(0x20B79CFF))))
                } else {
                    Modifier
                }
            )
            .glass()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (st.lookUp) L.t("LOOK UP NOW", "LÍTTU UPP NÚNA") else L.t("RIGHT NOW", "NÚNA"),
                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(4.dp))
                val bzText = when {
                    st.bz == null -> L.t("Solar wind data unavailable", "Sólvindsgögn vantar")
                    !st.bzFresh -> L.t("Solar wind data is stale", "Sólvindsgögn eru gömul")
                    st.bz <= -3 -> "Bz ${Fmt.signed(st.bz)} nT · " + L.t("field pointing south, good", "segulsvið til suðurs, gott")
                    st.bz >= 3 -> "Bz ${Fmt.signed(st.bz)} nT · " + L.t("pointing north, quiet", "til norðurs, rólegt")
                    else -> "Bz ${Fmt.signed(st.bz)} nT · " + L.t("neutral", "hlutlaust")
                }
                Text(bzText, color = Muted, fontSize = 13.sp)
                Text(Fmt.cloud(st.cloud) + L.t(" here", " hér"), color = Faint, fontSize = 13.sp)
                st.clearerSpot?.let {
                    Text(L.t("Clearer at ", "Heiðskírara við ") + "${it.spot.name} (${Fmt.cloud(it.cloud)})", color = Green, fontSize = 13.sp)
                }
            }
            Text(st.score.toString(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
internal fun WhyCard(h: HourScore) {
    Column(Modifier.glass()) {
        CardTitle(L.t("Why ${Fmt.hhmm(h.time)} scores ${h.score}", "Af hverju ${Fmt.hhmm(h.time)} fær ${h.score}"), L.t("Each one multiplies the score", "Hver þáttur margfaldar einkunnina"))
        Spacer(Modifier.height(14.dp))
        FactorRow(
            L.t("Solar activity", "Sólvirkni"),
            "Kp ${Fmt.one(h.kp)}" + if (h.live) L.t(" · live boost", " · lifandi uppfærsla") else "",
            h.factors.activity,
        )
        FactorRow(
            L.t("Clear sky", "Heiðskírt"),
            if (h.cloud < 0) L.t("unknown", "óþekkt") else L.t("${100 - h.cloud}% clear", "${100 - h.cloud}% heiðskírt"),
            h.factors.clear,
        )
        FactorRow(
            L.t("Moon", "Tungl"),
            L.t("${(h.moonIllum * 100).roundToInt()}% lit · ", "${(h.moonIllum * 100).roundToInt()}% lýst · ") +
                if (h.moonAlt > 0) L.t("up ${h.moonAlt.roundToInt()}°", "á lofti ${h.moonAlt.roundToInt()}°") else L.t("below horizon", "undir sjóndeildarhring"),
            h.factors.moon,
        )
        FactorRow(
            L.t("Darkness", "Myrkur"),
            L.t("sun ${h.sunAlt.roundToInt()}°", "sól ${h.sunAlt.roundToInt()}°"),
            h.factors.dark,
            last = true,
        )
    }
}

@Composable
internal fun FactorRow(name: String, value: String, f: Double, last: Boolean = false) {
    Column(Modifier.padding(bottom = if (last) 0.dp else 12.dp)) {
        Row {
            Text(name, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(value, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x14FFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(f.toFloat().coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(Teal, Green)))
            )
        }
    }
}

@Composable
internal fun WhereCard(spots: List<SpotScore>, sel: HourScore) {
    val context = LocalContext.current
    val view = LocalView.current
    Column(Modifier.glass()) {
        CardTitle(
            L.t("Where to go at ", "Hvert á að fara kl. ") + Fmt.hhmm(sel.time),
            L.t("Tap a place for directions", "Ýttu á stað til að fá leiðsögn"),
        )
        Spacer(Modifier.height(6.dp))
        spots.forEachIndexed { i, s ->
            val best = i == 0 && s.score > 0
            val here = s.distanceKm < 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (here) Modifier else Modifier.clickable {
                            Haptics.tap(view)
                            openDirections(context, s.spot.lat, s.spot.lon, s.spot.name)
                        }
                    )
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.spot.name, color = Ink, fontSize = 15.sp)
                        if (best) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                L.t("BEST", "BEST"),
                                color = Color(0xFF03130B), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Green)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val where = if (here) L.t("you're here", "þú ert hér")
                    else L.t("≈ ${driveMinutes(s.distanceKm)} min drive · ", "≈ ${driveMinutes(s.distanceKm)} mín akstur · ") + Fmt.distance(s.distanceKm)
                    Text("$where · ${Fmt.cloud(s.cloud)}", color = Faint, fontSize = 12.sp)
                }
                Text(s.score.toString(), color = scoreColor(s.score).copy(alpha = 0.95f), fontSize = 22.sp, fontWeight = FontWeight.Light)
                if (!here) Text("  ›", color = Faint, fontSize = 18.sp)
            }
        }
    }
}

/**
 * Rough door-to-door estimate from straight-line distance: roads wander (~1.35×) and town
 * stretches are slow. Shown with "≈" because it's a guess, not routing.
 */
internal fun driveMinutes(km: Double): Int {
    val road = km * 1.35
    val speed = if (road < 6) 40.0 else 70.0
    return (road / speed * 60 + 2).roundToInt().coerceAtLeast(2)
}

private fun openDirections(context: android.content.Context, lat: Double, lon: Double, name: String) {
    val uri = android.net.Uri.parse(
        "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent.setPackage("com.google.android.apps.maps"))
    } catch (e: Exception) {
        // No Google Maps: let any map app or the browser take it.
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) { }
    }
}

@Composable
internal fun CardTitle(title: String, sub: String?) {
    Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    if (sub != null) Text(sub, color = Faint, fontSize = 12.sp)
}

