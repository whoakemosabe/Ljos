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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.ljos.model.MoonTimeline
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import app.ljos.model.Model
import app.ljos.model.Night
import app.ljos.model.NowState
import app.ljos.model.SpotScore
import kotlin.math.roundToInt

internal val Glow = Shadow(color = Color(0x99000000), blurRadius = 24f)

internal val HeroScoreStyle: TextStyle = TextStyle(
    color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.ExtraLight,
    lineHeight = 88.sp, shadow = Glow,
)

/**
 * Tonight at a glance. With dark hours, a night dial (drag the ring to explore, release to snap
 * back) wraps the score; otherwise just the headline.
 */
@Composable
internal fun Hero(
    night: Night?,
    inp: Inputs?,
    loading: Boolean,
    now: Long = System.currentTimeMillis(),
    moon: MoonTimeline? = null,
    onScrub: (Long?) -> Unit = {},
    scoreAlpha: () -> Float = { 1f },
    onScorePlaced: (Offset, IntSize) -> Unit = { _, _ -> },
) {
    val peak = night?.peak
    val shown by animateIntAsState(peak?.score ?: 0, tween(1200), label = "score")
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (night != null && night.hours.isNotEmpty() && peak != null) {
            NightDial(
                hours = night.hours,
                moon = moon,
                now = now,
                onScrub = onScrub,
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f),
            ) { scrubbed ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (scrubbed == null) L.t("TONIGHT", "Í KVÖLD") else L.t("AT ", "KL. ") + Fmt.hhmm(scrubbed.time),
                        color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp,
                    )
                    val value = scrubbed?.score ?: shown
                    Text(
                        value.toString(),
                        style = HeroScoreStyle.copy(color = if (scrubbed == null) Color.White else scoreColor(value)),
                        modifier = Modifier
                            .onGloballyPositioned { onScorePlaced(it.positionInRoot(), it.size) }
                            .graphicsLayer { alpha = if (scrubbed == null) scoreAlpha() else 1f },
                    )
                    Text(
                        Model.label(scrubbed?.score ?: peak.score),
                        style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Light, shadow = Glow),
                    )
                    if (scrubbed != null) {
                        Text(
                            "Kp ${Fmt.one(scrubbed.kp)} · ${Fmt.cloud(scrubbed.cloud)}",
                            color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                L.t("Peak around ", "Hámark um ") + "${Fmt.hhmm(peak.time)} · Kp ${Fmt.one(peak.kp)} · ${Fmt.cloud(peak.cloud)}",
                color = Muted, fontSize = 14.sp,
            )
            if (night.darkFrom != null && night.darkUntil != null) {
                Text(
                    L.t("Dark ", "Myrkur ") + "${Fmt.hhmm(night.darkFrom)}–${Fmt.hhmm(night.darkUntil)} · " +
                        L.t("drag the ring to explore", "dragðu hringinn til að skoða"),
                    color = Faint, fontSize = 12.sp,
                )
            }
        } else {
            val headline = when {
                night != null -> L.t("Too bright for aurora", "Of bjart fyrir norðurljós")
                inp == null || inp.isEmpty -> if (loading) L.t("Reading the sky…", "Les himininn…") else L.t("No data yet — tap ↻", "Engin gögn enn — ýttu á ↻")
                else -> "—"
            }
            Spacer(Modifier.height(60.dp))
            Text(headline, style = TextStyle(color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light, shadow = Glow))
            Spacer(Modifier.height(60.dp))
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
                val eta = st.etaMinutes?.let { L.t(" · here in ~$it min", " · hér eftir ~$it mín") } ?: ""
                val bzText = when {
                    st.bz == null -> L.t("Solar wind data unavailable", "Sólvindsgögn vantar")
                    !st.bzFresh -> L.t("Solar wind data is stale", "Sólvindsgögn eru gömul")
                    st.bz <= -3 -> "Bz ${Fmt.signed(st.bz)} nT · " + L.t("field pointing south, good", "segulsvið til suðurs, gott")
                    st.bz >= 3 -> "Bz ${Fmt.signed(st.bz)} nT · " + L.t("pointing north, quiet", "til norðurs, rólegt")
                    else -> "Bz ${Fmt.signed(st.bz)} nT · " + L.t("neutral", "hlutlaust")
                }
                Text(bzText + eta, color = Muted, fontSize = 13.sp)
                st.bzSustained?.let {
                    Text(
                        L.t("Arriving now: Bz ${Fmt.signed(it)} nT on average", "Kemur núna: Bz ${Fmt.signed(it)} nT að meðaltali"),
                        color = Faint, fontSize = 12.sp,
                    )
                }
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
    val home = spots.firstOrNull { it.spot.id == "home" } ?: spots.first()
    val best = spots.firstOrNull { it.score > 0 && it.spot.id != "home" }
    var picked by remember(sel.time) { mutableStateOf(best?.spot?.id) }
    val pickedSpot = spots.firstOrNull { it.spot.id == picked }

    Column(Modifier.glass()) {
        CardTitle(
            L.t("Where to go at ", "Hvert á að fara kl. ") + Fmt.hhmm(sel.time),
            L.t("Tap a pin or a place", "Ýttu á pinna eða stað"),
        )
        Spacer(Modifier.height(12.dp))
        SpotMap(
            home = home.spot,
            spots = listOf(home.spot) + spots.filter { it !== home }.map { it.spot },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            scores = spots.associate { it.spot.id to it.score },
            bestId = best?.spot?.id,
            selectedId = picked,
            onSelect = { picked = it.id },
        )
        // Selected pin: score, drive time and a Directions button.
        androidx.compose.animation.AnimatedContent(
            targetState = pickedSpot,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.slideInVertically { it / 4 }) togetherWith
                    androidx.compose.animation.fadeOut(tween(160))
            },
            label = "picked",
        ) { s ->
            if (s != null) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.spot.name, color = Ink, fontSize = 16.sp)
                        Text(
                            L.t("≈ ${driveMinutes(s.distanceKm)} min drive · ", "≈ ${driveMinutes(s.distanceKm)} mín akstur · ") +
                                Fmt.distance(s.distanceKm) + " · " + Fmt.cloud(s.cloud),
                            color = Faint, fontSize = 12.sp,
                        )
                    }
                    Text(s.score.toString(), color = scoreColor(s.score), fontSize = 24.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.width(10.dp))
                    Pill(L.t("Go", "Fara"), primary = true) { openDirections(context, s.spot.lat, s.spot.lon, s.spot.name) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        spots.forEach { s ->
            val here = s.spot.id == "home" || s.distanceKm < 1
            val isPicked = s.spot.id == picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isPicked) Color(0x0FFFFFFF) else Color.Transparent)
                    .then(if (here) Modifier else Modifier.clickable { Haptics.tap(view); picked = s.spot.id })
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(scoreColor(s.score)))
                Spacer(Modifier.width(10.dp))
                Text(s.spot.name, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    if (here) L.t("you're here", "þú ert hér") else Fmt.distance(s.distanceKm),
                    color = Faint, fontSize = 12.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text(s.score.toString(), color = scoreColor(s.score), fontSize = 16.sp, fontWeight = FontWeight.Light)
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

