package app.ljos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.ljos.Fmt
import app.ljos.L
import app.ljos.work.Alerts
import app.ljos.Prefs
import app.ljos.data.HOUR_MS
import app.ljos.data.Inputs
import app.ljos.data.Locator
import app.ljos.data.Repo
import app.ljos.data.Spots
import app.ljos.model.HourScore
import app.ljos.model.Model
import app.ljos.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val HeaderBar = 92.dp
private val HeaderFade = 44.dp

@Composable
fun LjosApp() {
    val context = LocalContext.current
    val repo = remember { Repo(context) }
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var inputs by remember { mutableStateOf<Inputs?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var home by remember { mutableStateOf(prefs.home) }
    var detecting by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // Settings sheet position: 0 = fully open, 1 = hidden.
    val sheet = remember { Animatable(1f) }
    fun openSheet() { scope.launch { sheet.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f)) } }
    fun closeSheet() { scope.launch { sheet.animateTo(1f, tween(240)) } }
    BackHandler(enabled = sheet.targetValue < 1f) { closeSheet() }

    suspend fun reload(force: Boolean) {
        loading = true
        inputs = withContext(Dispatchers.IO) { repo.inputs() }
        errors = repo.refresh(force)
        inputs = withContext(Dispatchers.IO) { repo.inputs() }
        now = System.currentTimeMillis()
        loading = false
        inputs?.let { i -> try { withContext(Dispatchers.IO) { Alerts.check(context, i) } } catch (e: Exception) { } }
        try { Widgets.updateAll(context) } catch (e: Exception) { }
    }

    suspend fun detect() {
        if (detecting) return
        detecting = true
        val moved = try { Locator.detectAndSave(context) } catch (e: Exception) { null }
        home = prefs.home
        detecting = false
        if (moved != null) reload(false)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) scope.launch { detect() }
    }
    fun askOrDetect(locationToo: Boolean = true) {
        if (locationToo && Locator.hasPermission(context)) {
            scope.launch { detect() }
        } else {
            val wanted = buildList {
                if (locationToo) add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        // Auto-detect on open unless home is pinned; Detect in settings always works.
        askOrDetect(locationToo = prefs.autoDetect)
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
    val sel: HourScore? = night?.let { n ->
        n.hours.firstOrNull { it.time == selected }
            ?: n.hours.firstOrNull { now >= it.time && now < it.time + HOUR_MS && it.factors.dark > 0 }
            ?: n.peak
    }
    val spotScores = remember(inp, sel?.time, now) {
        if (inp != null && sel != null) Model.spotsAt(sel.time, inp, now) else emptyList()
    }
    val moon = remember(night, inp) {
        val n = night
        if (n == null || n.hours.isEmpty() || inp == null) null
        else Model.moonTimeline(n.hours.first().time, n.hours.last().time + HOUR_MS, inp.home)
    }
    val kpDays = remember(inp, now) { inp?.takeIf { it.kp.isNotEmpty() }?.let { Model.kpOutlook(it.kp, now) } }
    val intensity by animateFloatAsState((night?.peak?.score ?: 0) / 100f, tween(1800), label = "intensity")

    // The page, drawn once for real and once (blurred, non-interactive) inside the header.
    val page: @Composable (Boolean) -> Unit = { interactive ->
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(intensity, Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = interactive)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HeaderBar)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Hero(night, inp, loading)
                if (nowState != null && nowState.isDark) NowCard(nowState)
                if (night != null && night.hours.isNotEmpty()) {
                    Column(Modifier.glass()) {
                        CardTitle(L.t("Hour by hour", "Klukkustund fyrir klukkustund"), L.t("Tap or drag a bar", "Ýttu á eða dragðu súlu"))
                        Spacer(Modifier.height(12.dp))
                        HourStrip(night.hours, sel?.time, now, onSelect = if (interactive) { t -> selected = t } else null)
                        if (moon != null) {
                            Spacer(Modifier.height(10.dp))
                            MoonLine(night.hours, moon)
                        }
                    }
                }
                if (sel != null) WhyCard(sel)
                if (spotScores.isNotEmpty() && sel != null) WhereCard(spotScores, sel)
                if (kpDays != null) KpOutlookCard(kpDays, now)
                MadeWithLove(errors)
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(NightBg)) {
        val screenH = maxHeight
        val openness = 1f - sheet.value
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val headerH = statusTop + HeaderBar + HeaderFade
        val heroGonePx = with(density) { 260.dp.toPx() }
        val collapsed = scroll.value > heroGonePx

        Box(
            Modifier
                .fillMaxSize()
                .then(if (openness > 0.01f) Modifier.blur((28 * openness).dp) else Modifier)
        ) {
            page(true)

            // Progressive blur: a blurred copy of the page, masked so it fades out downward.
            if (Build.VERSION.SDK_INT >= 31) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(headerH)
                        .clipToBounds()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(Alignment.Top, unbounded = true)
                            .height(screenH)
                            .blur(22.dp, BlurredEdgeTreatment.Rectangle)
                    ) { page(false) }
                }
            }
            // Tint so the title stays readable over a bright aurora
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(headerH)
                    .background(Brush.verticalGradient(listOf(Color(0x99050812), Color(0x00050812))))
            )
            Header(
                placeName = home.name,
                score = night?.peak?.score,
                collapsed = collapsed,
                loading = loading,
                onRefresh = { scope.launch { reload(true) } },
                onSettings = { openSheet() },
            )
        }

        if (sheet.value < 0.999f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f * openness))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeSheet() }
            )
            SettingsSheet(
                sheet = sheet,
                maxHeight = screenH * 0.9f,
                onSettle = { velocity ->
                    scope.launch {
                        if (sheet.value > 0.3f || velocity > 1400f) sheet.animateTo(1f, tween(220))
                        else sheet.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
                    }
                },
                onDrag = { fraction -> scope.launch { sheet.snapTo((sheet.value + fraction).coerceIn(0f, 1f)) } },
            ) {
                SettingsContent(
                    home = home,
                    spots = inp?.spots ?: Spots.forHome(home),
                    detectedAt = prefs.homeDetectedAt,
                    detecting = detecting,
                    now = now,
                    onDetect = { askOrDetect() },
                    onClose = { closeSheet() },
                )
            }
        }
    }
}

@Composable
private fun Header(
    placeName: String,
    score: Int?,
    collapsed: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val miniAlpha by animateFloatAsState(if (collapsed && score != null) 1f else 0f, tween(300), label = "mini")
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ljós", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            if (score != null) {
                Text(
                    L.t("  ·  Tonight $score · ", "  ·  Í kvöld $score · ") + Model.label(score),
                    color = Muted.copy(alpha = Muted.alpha * miniAlpha), fontSize = 13.sp, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            RoundButton(onClick = onRefresh, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Ink, strokeWidth = 2.dp)
                else Text("↻", color = Ink, fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            RoundButton(onClick = onSettings) { TuneIcon() }
        }
        Row(
            Modifier
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onSettings)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PinIcon(Modifier.size(width = 10.dp, height = 13.dp))
            Spacer(Modifier.width(6.dp))
            Text(placeName, color = Muted, fontSize = 13.sp, maxLines = 1)
            Text("  ›", color = Faint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x1FFFFFFF))
            .border(1.dp, Color(0x14FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Bottom sheet that follows the finger. Drags inside its scrolling content move the sheet
 * whenever the content is already at the top, so you can pull it down from anywhere.
 */
@Composable
private fun SettingsSheet(
    sheet: Animatable<Float, *>,
    maxHeight: Dp,
    onSettle: (velocityY: Float) -> Unit,
    onDrag: (fraction: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    var heightPx by remember { mutableIntStateOf(100_000) }
    val inner = rememberScrollState()
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pulling up while the sheet is partly down: raise the sheet before scrolling content.
                if (available.y < 0 && sheet.value > 0f) {
                    onDrag(available.y / heightPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Content is at the top and the finger keeps going down: move the sheet.
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    onDrag(available.y / heightPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheet.value > 0f) {
                    onSettle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    // On Android 12+ the page behind is blurred, so the sheet itself can stay very see-through.
    val tint = if (Build.VERSION.SDK_INT >= 31) Color(0x660A1022) else Color(0xEB0A1022)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .offset { IntOffset(0, (sheet.value * heightPx).roundToInt()) }
                .clip(shape)
                .background(tint)
                .background(Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x06FFFFFF))))
                .border(1.dp, Color(0x2EFFFFFF), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .nestedScroll(connection)
                .verticalScroll(inner)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 22.dp)
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x59FFFFFF))
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** Three slider lines with knobs. */
@Composable
private fun TuneIcon() {
    Canvas(Modifier.size(16.dp)) {
        val sw = 1.6.dp.toPx()
        val ys = listOf(0.2f, 0.5f, 0.8f)
        val knobs = listOf(0.68f, 0.32f, 0.58f)
        ys.forEachIndexed { i, y ->
            drawLine(Ink, Offset(0f, size.height * y), Offset(size.width, size.height * y), sw, StrokeCap.Round)
            drawCircle(NightBg, 3.2.dp.toPx(), Offset(size.width * knobs[i], size.height * y))
            drawCircle(Ink, 2.4.dp.toPx(), Offset(size.width * knobs[i], size.height * y), style = Stroke(sw))
        }
    }
}

@Composable
private fun PinIcon(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.width / 2f
        val path = Path().apply {
            moveTo(size.width / 2f, size.height)
            lineTo(0.6f, r * 1.25f)
            lineTo(size.width - 0.6f, r * 1.25f)
            close()
        }
        drawPath(path, Green)
        drawCircle(Green, r, Offset(r, r))
        drawCircle(NightBg, r * 0.4f, Offset(r, r))
    }
}

