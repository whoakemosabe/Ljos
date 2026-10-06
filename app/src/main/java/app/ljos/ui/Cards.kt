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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.data.Inputs
import app.ljos.model.HourScore
import app.ljos.model.Model
import app.ljos.model.Night
import app.ljos.model.NowState
import app.ljos.model.SpotScore
import kotlin.math.roundToInt

internal val Glow = Shadow(color = Color(0x99000000), blurRadius = 24f)

@Composable
internal fun Hero(night: Night?, inp: Inputs?, loading: Boolean) {
    val peak = night?.peak
    val shown by animateIntAsState(peak?.score ?: 0, tween(1200), label = "score")
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 18.dp)) {
        Text("TONIGHT", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
        Text(
            if (peak != null) shown.toString() else "—",
            style = TextStyle(
                color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.ExtraLight,
                lineHeight = 124.sp, shadow = Glow,
            ),
        )
        val headline = when {
            peak != null -> Model.label(peak.score)
            night != null -> "Too bright for aurora"
            inp == null || inp.isEmpty -> if (loading) "Reading the sky…" else "No data yet — tap ↻"
            else -> "—"
        }
        Text(headline, style = TextStyle(color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light, shadow = Glow))
        if (peak != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Peak around ${Fmt.hhmm(peak.time)} · Kp ${Fmt.one(peak.kp)} · ${Fmt.cloud(peak.cloud)}",
                color = Muted, fontSize = 14.sp,
            )
        }
        if (night?.darkFrom != null && night.darkUntil != null) {
            Text("Dark ${Fmt.hhmm(night.darkFrom)}–${Fmt.hhmm(night.darkUntil)}", color = Faint, fontSize = 13.sp)
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
                    if (st.lookUp) "LOOK UP NOW" else "RIGHT NOW",
                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(4.dp))
                val bzText = when {
                    st.bz == null -> "Solar wind data unavailable"
                    !st.bzFresh -> "Solar wind data is stale"
                    st.bz <= -3 -> "Bz ${Fmt.signed(st.bz)} nT · field pointing south, good"
                    st.bz >= 3 -> "Bz ${Fmt.signed(st.bz)} nT · pointing north, quiet"
                    else -> "Bz ${Fmt.signed(st.bz)} nT · neutral"
                }
                Text(bzText, color = Muted, fontSize = 13.sp)
                Text(Fmt.cloud(st.cloud) + " at home", color = Faint, fontSize = 13.sp)
                st.clearerSpot?.let {
                    Text("Clearer at ${it.spot.name} (${Fmt.cloud(it.cloud)})", color = Green, fontSize = 13.sp)
                }
            }
            Text(st.score.toString(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
internal fun WhyCard(h: HourScore) {
    Column(Modifier.glass()) {
        CardTitle("Why ${Fmt.hhmm(h.time)} scores ${h.score}", "Each one multiplies the score")
        Spacer(Modifier.height(14.dp))
        FactorRow(
            "Solar activity",
            "Kp ${Fmt.one(h.kp)}" + if (h.live) " · live boost" else "",
            h.factors.activity,
        )
        FactorRow(
            "Clear sky",
            if (h.cloud < 0) "unknown" else "${100 - h.cloud}% clear",
            h.factors.clear,
        )
        FactorRow(
            "Moon",
            "${(h.moonIllum * 100).roundToInt()}% lit · " + if (h.moonAlt > 0) "up ${h.moonAlt.roundToInt()}°" else "below horizon",
            h.factors.moon,
        )
        FactorRow(
            "Darkness",
            "sun ${h.sunAlt.roundToInt()}°",
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
    Column(Modifier.glass()) {
        CardTitle("Where to go at ${Fmt.hhmm(sel.time)}", "Town lights cost home a few points")
        Spacer(Modifier.height(10.dp))
        spots.forEachIndexed { i, s ->
            val best = i == 0 && s.score > 0
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.spot.name, color = Ink, fontSize = 15.sp)
                        if (best) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "BEST",
                                color = Color(0xFF03130B), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Green)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val dist = if (s.distanceKm < 1) "you're here" else "${s.distanceKm.roundToInt()} km away"
                    Text("$dist · ${Fmt.cloud(s.cloud)}", color = Faint, fontSize = 12.sp)
                }
                Text(s.score.toString(), color = scoreColor(s.score).copy(alpha = 0.95f), fontSize = 22.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
internal fun CardTitle(title: String, sub: String?) {
    Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    if (sub != null) Text(sub, color = Faint, fontSize = 12.sp)
}

@Composable
internal fun Footer(errors: List<String>) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        errors.forEach { Text(it, color = Warn.copy(alpha = 0.8f), fontSize = 11.sp) }
        Text("Data: NOAA SWPC · Open-Meteo", color = Faint, fontSize = 11.sp)
    }
}
