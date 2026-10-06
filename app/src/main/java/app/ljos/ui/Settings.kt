package app.ljos.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.ljos.data.MapTiles
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ljos.Fmt
import app.ljos.L
import app.ljos.widget.Widgets
import app.ljos.Prefs
import app.ljos.data.Spot
import app.ljos.data.Updater
import app.ljos.model.Geo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/** Everything inside the settings sheet. */
@Composable
internal fun SettingsContent(
    home: Spot,
    spots: List<Spot>,
    detectedAt: Long,
    detecting: Boolean,
    now: Long,
    onDetect: () -> Unit,
    onClose: () -> Unit,
) {
    Column {
        SectionLabel(L.t("LOCATION", "STAÐSETNING"))
        SpotsMap(home, spots, Modifier.fillMaxWidth().height(190.dp))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(home.name, color = Ink, fontSize = 15.sp)
                Text(
                    String.format(Locale.US, "%.2f° N, %.2f° %s", home.lat, kotlin.math.abs(home.lon), if (home.lon < 0) "W" else "E") +
                        if (detectedAt > 0) L.t(" · detected ", " · fundið ") + Fmt.ago(detectedAt, now) else L.t(" · default", " · sjálfgefið"),
                    color = Faint, fontSize = 12.sp,
                )
            }
            Pill(if (detecting) L.t("Detecting…", "Leita…") else L.t("Detect", "Finna"), primary = true, busy = detecting, icon = !detecting, onClick = onDetect)
        }
        Text(
            if (spots.any { it.id.startsWith("ring-") }) L.t(
                "Away from Reykjanes, so the dots are 8 points ${Fmt.distance(12.0)}–${Fmt.distance(25.0)} around you.",
                "Utan Reykjaness eru punktarnir 8 staðir ${Fmt.distance(12.0)}–${Fmt.distance(25.0)} í kringum þig.",
            )
            else L.t("Dots are Garðskagi, Hafnir, Reykjanesviti and Kleifarvatn.", "Punktarnir eru Garðskagi, Hafnir, Reykjanesviti og Kleifarvatn."),
            color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )

        AutoDetectToggle()
        Spacer(Modifier.height(20.dp))
        AlertSettings()
        Spacer(Modifier.height(20.dp))
        DisplaySettings()
        Spacer(Modifier.height(20.dp))
        BackgroundSettings()
        Spacer(Modifier.height(20.dp))
        UpdateSettings()
        Spacer(Modifier.height(20.dp))
        Text(L.t("Data: NOAA SWPC · Open-Meteo", "Gögn: NOAA SWPC · Open-Meteo"), color = Faint, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
}

/** Dark OpenStreetMap tiles with your pin and the comparison spots. */
@Composable
private fun SpotsMap(home: Spot, spots: List<Spot>, modifier: Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Color(0xFF0A1322))
            .border(1.dp, Color(0x1FFFFFFF), shape)
    ) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val radiusKm = spots.maxOf { Geo.km(home.lat, home.lon, it.lat, it.lon) }.coerceAtLeast(8.0)
        val zoom = remember(home.lat, radiusKm, hDp) { MapTiles.zoomFor(home.lat, radiusKm, hDp / 2f) }
        var tiles by remember { mutableStateOf<List<MapTiles.Tile>>(emptyList()) }
        LaunchedEffect(home.lat, home.lon, zoom, wDp, hDp) {
            tiles = MapTiles.load(context, home.lat, home.lon, zoom, wDp, hDp)
        }
        val fade by animateFloatAsState(if (tiles.isEmpty()) 0f else 1f, tween(700), label = "tiles")
        val images = remember(tiles) { tiles.map { it to it.bitmap.asImageBitmap() } }

        Canvas(Modifier.fillMaxSize()) {
            val px = size.width / wDp
            val (cx, cy) = MapTiles.project(home.lat, home.lon, zoom)
            fun toScreen(wx: Double, wy: Double) = Offset(
                ((wx - (cx - wDp / 2)) * px).toFloat(),
                ((wy - (cy - hDp / 2)) * px).toFloat(),
            )
            images.forEach { (t, img) ->
                val tw = 256.0 / (1 shl (t.zoom - zoom))
                val o = toScreen(t.x * tw, t.y * tw)
                val side = (tw * px).roundToInt() + 1
                drawImage(
                    img,
                    dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                    dstSize = IntSize(side, side),
                    alpha = fade,
                    filterQuality = FilterQuality.Medium,
                )
            }
            // Cool night tint so the map sits inside the app's palette
            drawRect(Color(0x260A1A3A))
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99050812)), radius = size.maxDimension * 0.75f))

            spots.drop(1).forEach { s ->
                val (wx, wy) = MapTiles.project(s.lat, s.lon, zoom)
                val p = toScreen(wx, wy)
                drawCircle(Teal.copy(alpha = 0.22f), 9.dp.toPx(), p)
                drawCircle(Teal, 3.5.dp.toPx(), p)
                drawCircle(Color(0xFF0A1322), 1.4.dp.toPx(), p)
            }
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Green.copy(alpha = 0.16f), 26.dp.toPx(), c)
            drawCircle(Green.copy(alpha = 0.10f), 14.dp.toPx(), c)
            val head = c + Offset(0f, -15.dp.toPx())
            val pin = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y)
                lineTo(head.x - 7.5.dp.toPx(), head.y + 3.dp.toPx())
                lineTo(head.x + 7.5.dp.toPx(), head.y + 3.dp.toPx())
                close()
            }
            drawCircle(Color(0x66000000), 3.dp.toPx(), c + Offset(0f, 1.dp.toPx()))
            drawPath(pin, Green)
            drawCircle(Green, 8.5.dp.toPx(), head)
            drawCircle(Color(0xFF0A1322), 3.2.dp.toPx(), head)
        }
        Text(
            MapTiles.attribution, color = Color(0x80E8F1FF), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )
    }
}

@Composable
private fun AlertSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var tonight by remember { mutableStateOf(prefs.tonightAlerts) }
    var lookUp by remember { mutableStateOf(prefs.lookUpAlerts) }
    var threshold by remember { mutableIntStateOf(prefs.threshold) }

    var live by remember { mutableStateOf(prefs.liveLockScreen) }
    var quiet by remember { mutableStateOf(prefs.quietOn) }
    var quietFrom by remember { mutableIntStateOf(prefs.quietFrom) }
    var quietTo by remember { mutableIntStateOf(prefs.quietTo) }

    SectionLabel(L.t("ALERTS", "VIÐVARANIR"))
    ToggleRow(
        L.t("Evening heads-up", "Kvöldviðvörun"),
        L.t("Once a night, 16:00–23:00, if tonight reaches your level", "Einu sinni á kvöldi, 16–23, ef kvöldið nær þínu marki"),
        tonight,
    ) { tonight = it; prefs.tonightAlerts = it }
    ToggleRow(
        L.t("Look up now", "Líttu upp núna"),
        L.t("When it's dark, clear enough and the solar wind turns south", "Þegar er dimmt, nógu heiðskírt og sólvindurinn snýst suður"),
        lookUp,
    ) { lookUp = it; prefs.lookUpAlerts = it }
    ToggleRow(
        L.t("Aurora on lock screen", "Norðurljós á lásskjá"),
        L.t("A silent live card with the score while aurora is likely", "Hljóðlaust spjald með einkunn á meðan norðurljós eru líkleg"),
        live,
    ) { live = it; prefs.liveLockScreen = it }
    ToggleRow(
        L.t("Quiet hours", "Næðistími"),
        L.t("No sounds or pop-ups; the lock screen card stays silent", "Engin hljóð eða sprettigluggar; lásskjáspjaldið er hljóðlaust"),
        quiet,
    ) { quiet = it; prefs.quietOn = it }
    androidx.compose.animation.AnimatedVisibility(
        visible = quiet,
        enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) +
            androidx.compose.animation.fadeIn(tween(260)),
        exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 400f)) +
            androidx.compose.animation.fadeOut(tween(180)),
    ) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HourStepper(L.t("From", "Frá"), quietFrom) { quietFrom = it; prefs.quietFrom = it }
            Spacer(Modifier.width(18.dp))
            HourStepper(L.t("To", "Til"), quietTo) { quietTo = it; prefs.quietTo = it }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(L.t("Heads-up level", "Viðvörunarmark"), color = Ink, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    val levels = listOf(30, 40, 50, 60, 70)
    SlidingSegments(levels.map { it.toString() }, levels.indexOf(threshold).coerceAtLeast(0), Modifier.fillMaxWidth()) {
        threshold = levels[it]; prefs.threshold = levels[it]
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BackgroundSettings() {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    val unrestricted = remember(checks) { pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            checks++
        }
    }

    SectionLabel(L.t("BACKGROUND", "Í BAKGRUNNI"))
    if (unrestricted) {
        Text(
            L.t("Background refresh is allowed. Widgets and alerts update about every 15 minutes.",
                "Uppfærsla í bakgrunni er leyfð. Græjur og viðvaranir uppfærast á um 15 mínútna fresti."),
            color = Muted, fontSize = 13.sp,
        )
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x1AFF8A80))
                .clickable {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                .padding(14.dp),
        ) {
            Text(L.t("Allow background refresh", "Leyfa uppfærslu í bakgrunni"), color = Warn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                L.t("OnePlus and Oppo pause apps to save battery, which stops alerts and widget updates.",
                    "OnePlus og Oppo stöðva forrit til að spara rafhlöðu, sem stöðvar viðvaranir og græjur."),
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}

private sealed class UpdateUi {
    data object Idle : UpdateUi()
    data object Checking : UpdateUi()
    data object UpToDate : UpdateUi()
    data class Available(val release: Updater.Release) : UpdateUi()
    data class Downloading(val release: Updater.Release) : UpdateUi()
    data class Ready(val file: File) : UpdateUi()
    data class Error(val message: String) : UpdateUi()
}

@Composable
private fun UpdateSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var token by remember { mutableStateOf(prefs.githubToken) }
    var showToken by remember { mutableStateOf(prefs.githubToken.isBlank()) }
    val version = remember { Updater.installedVersion(context) }

    SectionLabel(L.t("UPDATES", "UPPFÆRSLUR"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(L.t("Version ", "Útgáfa ") + version, color = Ink, fontSize = 15.sp)
            val line = when (val s = state) {
                UpdateUi.Idle -> L.t("Builds come from GitHub releases", "Útgáfur koma af GitHub")
                UpdateUi.Checking -> L.t("Checking…", "Athuga…")
                UpdateUi.UpToDate -> L.t("You're on the latest version", "Þú ert með nýjustu útgáfu")
                is UpdateUi.Available -> L.t("Version ${s.release.version} is available", "Útgáfa ${s.release.version} er komin")
                is UpdateUi.Downloading -> L.t("Downloading ", "Sæki ") + "${s.release.version}… ${(progress * 100).toInt()}%"
                is UpdateUi.Ready -> L.t("Downloaded. Tap Install.", "Sótt. Ýttu á Setja upp.")
                is UpdateUi.Error -> s.message
            }
            Text(line, color = if (state is UpdateUi.Error) Warn else Faint, fontSize = 12.sp)
        }
        when (val s = state) {
            is UpdateUi.Available -> Pill(L.t("Download", "Sækja"), primary = true) {
                state = UpdateUi.Downloading(s.release)
                progress = 0f
                scope.launch {
                    state = try {
                        UpdateUi.Ready(Updater.download(context, s.release, prefs.githubToken) { progress = it })
                    } catch (e: Exception) {
                        UpdateUi.Error(e.message ?: L.t("Download failed", "Niðurhal mistókst"))
                    }
                }
            }
            is UpdateUi.Ready -> Pill(L.t("Install", "Setja upp"), primary = true) {
                if (!Updater.install(context, s.file)) {
                    state = UpdateUi.Error(L.t("Allow Ljós to install updates, then tap Install again.", "Leyfðu Ljós að setja upp, ýttu svo aftur á Setja upp."))
                }
            }
            else -> Pill(
                if (state == UpdateUi.Checking) L.t("Checking…", "Athuga…") else L.t("Check", "Athuga"),
                primary = false,
                busy = state == UpdateUi.Checking || state is UpdateUi.Downloading,
            ) {
                if (state == UpdateUi.Checking || state is UpdateUi.Downloading) return@Pill
                state = UpdateUi.Checking
                scope.launch {
                    state = when (val r = Updater.check(context, prefs.githubToken)) {
                        Updater.Check.UpToDate -> UpdateUi.UpToDate
                        is Updater.Check.Available -> UpdateUi.Available(r.release)
                        is Updater.Check.Failed -> {
                            if (prefs.githubToken.isBlank()) showToken = true
                            UpdateUi.Error(r.message)
                        }
                    }
                }
            }
        }
    }
    if (state is UpdateUi.Downloading) {
        Spacer(Modifier.height(8.dp))
        val shown by animateFloatAsState(progress, tween(260), label = "download")
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x1AFFFFFF))
        ) {
            if (shown > 0.002f) {
                Box(
                    Modifier
                        .fillMaxWidth(shown)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(Teal, Green)))
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    if (showToken) {
        Text(L.t("GitHub token", "GitHub lykill"), color = Ink, fontSize = 14.sp)
        Text(
            L.t("The repo is private, so checking needs a read-only token: Contents → Read, Ljos only.",
                "Safnið er lokað, svo það þarf lesaðgangslykil: Contents → Read, aðeins Ljos."),
            color = Faint, fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = token,
            onValueChange = { token = it; prefs.githubToken = it },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(Green),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (token.isEmpty()) Text("github_pat_…", color = Faint, fontSize = 14.sp)
                inner()
            },
        )
    } else {
        Text(
            L.t("GitHub token saved · change", "GitHub lykill vistaður · breyta"),
            color = Faint, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showToken = true }.padding(vertical = 4.dp),
        )
    }
}

@Composable
internal fun Pill(text: String, primary: Boolean, busy: Boolean = false, icon: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press")
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) Green else Color(0x1AFFFFFF))
            .clickable(interactionSource = interaction, indication = null) { Haptics.tap(view); onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) Color(0xFF03130B) else Ink
        if (busy) {
            CircularProgressIndicator(Modifier.size(12.dp), color = fg, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
        } else if (icon) {
            Canvas(Modifier.size(12.dp)) {
                drawCircle(fg, size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawCircle(fg, 2.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Haptics.toggle(view, !checked); onChange(!checked)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(sub, color = Faint, fontSize = 12.sp)
        }
        SoftSwitch(checked) { onChange(it) }
    }
}

/** A springy switch: the knob glides and squishes a little, the track glows green when on. */
@Composable
private fun SoftSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    val pos by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 380f), label = "knob")
    val track by animateColorAsState(if (checked) Green else Color(0x24FFFFFF), tween(320), label = "track")
    val knob by animateColorAsState(if (checked) Color(0xFF03130B) else Color(0xFFCFD8E6), tween(320), label = "knobColor")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val stretch by animateFloatAsState(if (pressed) 1.25f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "stretch")
    Canvas(
        Modifier
            .size(width = 46.dp, height = 28.dp)
            .clickable(interactionSource = interaction, indication = null) { Haptics.toggle(view, !checked); onChange(!checked) }
    ) {
        val h = size.height
        drawRoundRect(track, cornerRadius = CornerRadius(h / 2f))
        if (checked || pos > 0.01f) {
            drawRoundRect(Green.copy(alpha = 0.25f * pos), Offset(-3.dp.toPx(), -3.dp.toPx()),
                Size(size.width + 6.dp.toPx(), h + 6.dp.toPx()), CornerRadius(h / 2f + 3.dp.toPx()))
        }
        val pad = 3.dp.toPx()
        val d = h - pad * 2
        val w = d * stretch
        val x = pad + (size.width - pad * 2 - w) * pos
        drawRoundRect(knob, Offset(x, pad), Size(w, d), CornerRadius(d / 2f))
    }
}

@Composable
private fun AutoDetectToggle() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var auto by remember { mutableStateOf(prefs.autoDetect) }
    Spacer(Modifier.height(4.dp))
    ToggleRow(
        L.t("Detect on open", "Finna við opnun"),
        if (auto) L.t("Follows you when you travel", "Fylgir þér á ferðalögum")
        else L.t("Home is fixed. Tap Detect to move it.", "Heimili er fast. Ýttu á Finna til að færa það."),
        auto,
    ) { auto = it; prefs.autoDetect = it }
}

@Composable
private fun DisplaySettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    fun changed() { scope.launch { try { Widgets.updateAll(context) } catch (e: Exception) { } } }

    SectionLabel(L.t("DISPLAY", "BIRTING"))
    SegmentRow(L.t("Language", "Tungumál"), listOf("English", "Íslenska"), if (L.icelandic) 1 else 0) {
        L.icelandic = it == 1; prefs.icelandic = L.icelandic; changed()
    }
    SegmentRow(L.t("Time", "Tími"), listOf("24h", "12h"), if (L.clock24) 0 else 1) {
        L.clock24 = it == 0; prefs.clock24 = L.clock24; changed()
    }
    SegmentRow(L.t("Distance", "Fjarlægð"), listOf("km", "mi"), if (L.miles) 1 else 0) {
        L.miles = it == 1; prefs.miles = L.miles; changed()
    }
}

@Composable
private fun SegmentRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        SlidingSegments(options, selected, Modifier.width((options.size * 76).dp), onSelect)
    }
}

/** Glass track with a green pill that glides to the chosen option. */
@Composable
internal fun SlidingSegments(options: List<String>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    val pos by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.72f, stiffness = 340f), label = "segment")
    BoxWithConstraints(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(13.dp))
            .padding(3.dp)
    ) {
        val segW = maxWidth / options.size
        Box(
            Modifier
                .offset(x = segW * pos)
                .width(segW)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.horizontalGradient(listOf(Green, Color(0xFF5CFFC0))))
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val closeness = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (i != selected) Haptics.segment(view)
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = androidx.compose.ui.graphics.lerp(Muted, Color(0xFF03130B), closeness),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** "From ‹ 01:00 ›" style hour picker. */
@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Faint, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "‹", color = Ink, fontSize = 18.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange((hour + 23) % 24) }.padding(horizontal = 8.dp),
        )
        val label24 = String.format(Locale.US, "%02d:00", hour)
        val label12 = "${if (hour % 12 == 0) 12 else hour % 12}${if (hour < 12) "am" else "pm"}"
        Text(if (L.clock24) label24 else label12, color = Ink, fontSize = 15.sp, modifier = Modifier.width(48.dp))
        Text(
            "›", color = Ink, fontSize = 18.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange((hour + 1) % 24) }.padding(horizontal = 8.dp),
        )
    }
}
