package app.ljos.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.ljos.Fmt
import app.ljos.Prefs
import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import app.ljos.data.Repo
import app.ljos.model.HourScore
import app.ljos.model.Model
import app.ljos.model.Night
import app.ljos.model.NowState
import app.ljos.model.SpotScore
import app.ljos.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Glow = Shadow(color = Color(0x99000000), blurRadius = 24f)

@Composable
fun LjosApp() {
    val context = LocalContext.current
    val repo = remember { Repo(context) }
    val scope = rememberCoroutineScope()

    var inputs by remember { mutableStateOf<Inputs?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selected by remember { mutableStateOf<Long?>(null) }

    suspend fun reload(force: Boolean) {
        loading = true
        inputs = withContext(Dispatchers.IO) { repo.inputs() }
        errors = repo.refresh(force)
        inputs = withContext(Dispatchers.IO) { repo.inputs() }
        now = System.currentTimeMillis()
        loading = false
        try { Widgets.updateAll(context) } catch (e: Exception) { }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        reload(false)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    val inp = inputs
    val night = remember(inp, now) { inp?.takeUnless { it.isEmpty }?.let { Model.night(now, it) } }
    val nowState = remember(inp, now) { inp?.takeUnless { it.isEmpty }?.let { Model.nowState(now, it) } }

    // Default selection: the current hour if it's dark, else tonight's peak.
    val sel: HourScore? = night?.let { n ->
        n.hours.firstOrNull { it.time == selected }
            ?: n.hours.firstOrNull { now >= it.time && now < it.time + HOUR_MS && it.factors.dark > 0 }
            ?: n.peak
    }
    val spots = remember(inp, sel?.time, now) {
        if (inp != null && sel != null) Model.spotsAt(sel.time, inp, now) else emptyList()
    }
    val intensity by animateFloatAsState((night?.peak?.score ?: 0) / 100f, tween(1800), label = "intensity")

    Box(Modifier.fillMaxSize().background(NightBg)) {
        AuroraBackground(intensity, Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TopBar(inp?.updatedAt ?: 0L, now, loading) { scope.launch { reload(true) } }
            Hero(night, inp, loading)
            if (nowState != null && nowState.isDark) NowCard(nowState)
            if (night != null && night.hours.isNotEmpty()) {
                Column(Modifier.glass()) {
                    CardTitle("Hour by hour", "Tap or drag a bar")
                    Spacer(Modifier.height(12.dp))
                    HourStrip(night.hours, sel?.time, now, onSelect = { selected = it })
                }
            }
            if (sel != null) WhyCard(sel)
            if (spots.isNotEmpty() && sel != null) WhereCard(spots, sel)
            AlertsCard()
            Footer(errors)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TopBar(updatedAt: Long, now: Long, loading: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Ljós", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(
            if (updatedAt > 0) "Updated ${Fmt.ago(updatedAt, now)}" else "",
            color = Faint, fontSize = 12.sp,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x1AFFFFFF))
                .clickable(enabled = !loading, onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Ink, strokeWidth = 2.dp)
            } else {
                Text("↻", color = Ink, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Hero(night: Night?, inp: Inputs?, loading: Boolean) {
    val peak = night?.peak
    val shown by animateIntAsState(peak?.score ?: 0, tween(1200), label = "score")
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 18.dp)) {
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
private fun NowCard(st: NowState) {
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
private fun WhyCard(h: HourScore) {
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
private fun FactorRow(name: String, value: String, f: Double, last: Boolean = false) {
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
private fun WhereCard(spots: List<SpotScore>, sel: HourScore) {
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

@SuppressLint("BatteryLife")
@Composable
private fun AlertsCard() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var tonight by remember { mutableStateOf(prefs.tonightAlerts) }
    var lookUp by remember { mutableStateOf(prefs.lookUpAlerts) }
    var threshold by remember { mutableIntStateOf(prefs.threshold) }
    var checks by remember { mutableIntStateOf(0) }
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    val unrestricted = remember(checks) { pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true }

    Column(Modifier.glass()) {
        CardTitle("Alerts", null)
        Spacer(Modifier.height(6.dp))
        ToggleRow("Evening heads-up", "Once a night, 16:00–23:00, if tonight reaches your level", tonight) {
            tonight = it; prefs.tonightAlerts = it
        }
        ToggleRow("Look up now", "When it's dark, clear enough and the solar wind turns south", lookUp) {
            lookUp = it; prefs.lookUpAlerts = it
        }
        Spacer(Modifier.height(8.dp))
        Text("Heads-up level", color = Ink, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 40, 50, 60, 70).forEach { v ->
                FilterChip(
                    selected = threshold == v,
                    onClick = { threshold = v; prefs.threshold = v },
                    label = { Text(v.toString()) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0x10FFFFFF),
                        labelColor = Muted,
                        selectedContainerColor = Green,
                        selectedLabelColor = Color(0xFF03130B),
                    ),
                    border = null,
                )
            }
        }
        if (!unrestricted) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x1AFF8A80))
                    .clickable {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                        checks++
                    }
                    .padding(14.dp),
            ) {
                Column {
                    Text("Allow background refresh", color = Warn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "OnePlus and Oppo pause apps to save battery, which stops alerts and widget updates. Tap to allow.",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            checks++
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(sub, color = Faint, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Green,
                checkedThumbColor = Color(0xFF03130B),
                uncheckedTrackColor = Color(0x1AFFFFFF),
                uncheckedThumbColor = Muted,
                uncheckedBorderColor = Color(0x33FFFFFF),
            ),
        )
    }
}

@Composable
private fun CardTitle(title: String, sub: String?) {
    Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    if (sub != null) Text(sub, color = Faint, fontSize = 12.sp)
}

@Composable
private fun Footer(errors: List<String>) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        errors.forEach { Text(it, color = Warn.copy(alpha = 0.8f), fontSize = 11.sp) }
        Text("Data: NOAA SWPC · Open-Meteo", color = Faint, fontSize = 11.sp)
    }
}
