package app.ljos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import app.ljos.model.Night
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.layout.wrapContentSize
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

private val HeaderExpanded = 82.dp
private val HeaderCollapsed = 56.dp
private val HeaderFade = 56.dp

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
    var pullPx by remember { mutableFloatStateOf(0f) }
    var home by remember { mutableStateOf(prefs.home) }
    var detecting by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var heroBase by remember { mutableStateOf(Offset.Unspecified) }
    var heroSize by remember { mutableStateOf(IntSize.Zero) }
    var pillTarget by remember { mutableStateOf(Offset.Unspecified) }

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
    // Tomorrow, for the "looks better" nudge and the Tonight/Tomorrow switch in the hour card.
    val tomorrowNight = remember(inp, now) { inp?.takeUnless { it.isEmpty }?.let { Model.tomorrow(now, it) } }
    var showTomorrow by remember { mutableStateOf(false) }
    val shownNight = if (showTomorrow && tomorrowNight != null) tomorrowNight else night
    val sel: HourScore? = shownNight?.let { n ->
        n.hours.firstOrNull { it.time == selected }
            ?: n.hours.firstOrNull { now >= it.time && now < it.time + HOUR_MS && it.factors.dark > 0 }
            ?: n.peak
    }
    val explicitSelection = shownNight?.hours?.any { it.time == selected } == true
    val spotScores = remember(inp, sel?.time, now) {
        if (inp != null && sel != null) Model.spotsAt(sel.time, inp, now) else emptyList()
    }
    val moon = remember(shownNight, inp) {
        val n = shownNight
        if (n == null || n.hours.isEmpty() || inp == null) null
        else Model.moonTimeline(n.hours.first().time, n.hours.last().time + HOUR_MS, inp.home)
    }
    val weather = remember(inp, sel?.time) { if (inp != null && sel != null) Model.weatherAt(inp, inp.home, sel.time) else null }
    val isNight = nowState?.isDark == true

    // Folded cards, remembered between launches.
    var collapsed by remember { mutableStateOf(prefs.collapsedCards) }
    fun toggle(id: String) {
        collapsed = if (id in collapsed) collapsed - id else collapsed + id
        prefs.collapsedCards = collapsed
    }
    // Where each card is on screen, so chips can scroll to the right one.
    val cardSpots = remember { HashMap<String, LayoutCoordinates>() }
    fun Modifier.cardAnchor(id: String) = onGloballyPositioned { cardSpots[id] = it }
    val kpDays = remember(inp, now) { inp?.takeIf { it.kp.isNotEmpty() }?.let { Model.kpOutlook(it.kp, now) } }
    val intensity by animateFloatAsState((night?.peak?.score ?: 0) / 100f, tween(1800), label = "intensity")

    // Recorded once per frame and reused (blurred) by the header, so the page is only composed once.
    val pageLayer = rememberGraphicsLayer()

    BoxWithConstraints(Modifier.fillMaxSize().background(NightBg)) {
        val screenH = maxHeight
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val view = LocalView.current

        // Everything scroll- or drag-linked below is read inside layout/draw lambdas, never during
        // composition, so scrolling and dragging the sheet don't rebuild the screen every frame.
        val placeRange = with(density) { 110.dp.toPx() }
        // Long runway so the number drifts up gently rather than snapping into the header.
        val flightRange = with(density) { 340.dp.toPx() }
        val flightLead = with(density) { 200.dp.toPx() }
        val headerOrigin = with(density) { Offset(20.dp.toPx(), statusTop.toPx() + 12.dp.toPx()) }
        val placeMorph: () -> Float = { (scroll.value / placeRange).coerceIn(0f, 1f) }
        val scoreMorph: () -> Float = {
            if (!heroBase.isSpecified || !pillTarget.isSpecified) 0f
            else {
                // Never in flight at rest: the flight starts only after you've scrolled, once the
                // number is within flightLead of the header, then takes flightRange of scrolling.
                val restCenterY = heroBase.y + heroSize.height / 2f
                val landY = headerOrigin.y + pillTarget.y
                val startScroll = (restCenterY - landY - flightLead).coerceAtLeast(0f)
                ((scroll.value - startScroll) / flightRange).coerceIn(0f, 1f)
            }
        }
        val headerHeightPx: () -> Float = {
            with(density) {
                val bar = HeaderExpanded + (HeaderCollapsed - HeaderExpanded) * placeMorph()
                (statusTop + bar + HeaderFade).toPx()
            }
        }
        val openness: () -> Float = { 1f - sheet.value }

        // Pull-to-refresh: pulling past the top stretches the aurora; release past the line refreshes.
        val pullLine = with(density) { 110.dp.toPx() }
        val pullFraction: () -> Float = { pullPx / pullLine }
        val pullConnection = remember(pullLine) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0 && pullPx > 0f) {
                        val used = maxOf(available.y, -pullPx)
                        pullPx += used
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        val before = pullPx
                        // Rubber band: each pixel counts for less the further you've pulled.
                        val resistance = 0.5f * (1f - (pullPx / (pullLine * 1.6f))).coerceIn(0.05f, 1f)
                        pullPx = (pullPx + available.y * resistance).coerceAtMost(pullLine * 1.5f)
                        if (before < pullLine && pullPx >= pullLine) Haptics.segment(view)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pullPx <= 0f) return Velocity.Zero
                    if (pullPx >= pullLine && !loading) {
                        Haptics.settle(view)
                        scope.launch { reload(true) }
                    }
                    animate(pullPx, 0f, animationSpec = spring(dampingRatio = 0.62f, stiffness = 260f)) { v, _ -> pullPx = v }
                    return available
                }
            }
        }
        val skyState: SkyState? = if (L.liveSky) sel?.let { h -> skyFor(h, night) } else null
        val sheetVisible by remember { derivedStateOf { sheet.value < 0.999f } }

        // A soft bump when the score lands in the header.
        LaunchedEffect(Unit) {
            snapshotFlow { scoreMorph() >= 0.98f }.distinctUntilChanged().drop(1).collect { landed ->
                if (landed) Haptics.settle(view)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val o = openness()
                    if (Build.VERSION.SDK_INT >= 31 && o > 0.01f) {
                        val r = (28 * o).dp.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Clamp)
                    } else {
                        renderEffect = null
                    }
                }
        ) {
            Box(Modifier.fillMaxSize().backdropSource(pageLayer)) {
                AuroraBackground(intensity, Modifier.fillMaxSize(), sky = skyState, pull = { pullFraction().coerceIn(0f, 1.4f) })
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = pullPx * 0.55f }
                        .nestedScroll(pullConnection)
                        .verticalScroll(scroll)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(top = statusTop + HeaderExpanded + 8.dp)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Order follows the questions people ask.
                    // Day (planning): will I see it → when → where → what to wear → this week.
                    // Night (going out now): what's happening now → where → how to photograph it →
                    // the rest of tonight → what to wear → this week.
                    val liveNow: @Composable ColumnScope.() -> Unit = {
                        SoftReveal(isNight) { nowState?.let { NowCard(it, inp?.solarWind.orEmpty(), now) } }
                    }
                    val tonightSummary: @Composable ColumnScope.() -> Unit = {
                        Hero(
                            night, inp, loading,
                            now = now,
                            scoreAlpha = { if (scoreMorph() > 0f) 0f else 1f },
                            // Store the position as if unscrolled: identical every frame, so no recomposition.
                            onScorePlaced = { pos, size -> heroBase = pos + Offset(0f, scroll.value.toFloat()); heroSize = size },
                        )
                        val peak = night?.peak
                        if (night != null && peak != null) {
                            ConditionChips(peak, night, now) { id ->
                                val target = when (id) {
                                    "kp" -> "days"
                                    "cloud" -> "where"
                                    else -> "hours"
                                }
                                if (target in collapsed) toggle(target)
                                val coords = cardSpots[target]
                                if (coords != null && coords.isAttached) {
                                    val headerBottom = with(density) { (statusTop + HeaderCollapsed + 12.dp).toPx() }
                                    val y = coords.positionInRoot().y
                                    scope.launch {
                                        scroll.animateScrollTo(
                                            (scroll.value + y - headerBottom).toInt().coerceAtLeast(0),
                                            spring(dampingRatio = 0.9f, stiffness = 120f),
                                        )
                                    }
                                }
                            }
                        }
                        val tomorrowPeak = tomorrowNight?.peak?.score
                        SoftReveal(tomorrowPeak != null && peak != null && tomorrowPeak >= 40 && tomorrowPeak >= peak.score + 15 && !showTomorrow) {
                            TomorrowNudge(tomorrowPeak ?: 0) {
                                showTomorrow = true
                                selected = null
                                if ("hours" in collapsed) toggle("hours")
                            }
                        }
                    }
                    val hourByHour: @Composable ColumnScope.() -> Unit = {
                        SoftReveal(shownNight != null && shownNight.hours.isNotEmpty()) {
                            shownNight?.let { n ->
                                HourCard(
                                    night = n, sel = sel, explicitSelection = explicitSelection, now = now, moon = moon,
                                    tomorrow = showTomorrow, hasTomorrow = tomorrowNight?.hours?.isNotEmpty() == true,
                                    onTomorrow = { showTomorrow = it; selected = null },
                                    onSelect = { t -> selected = t },
                                    onClearSelection = { selected = null },
                                    collapsed = "hours" in collapsed, onToggle = { toggle("hours") },
                                    modifier = Modifier.cardAnchor("hours"),
                                )
                            }
                        }
                    }
                    val photoTips: @Composable ColumnScope.() -> Unit = {
                        val photoScore = maxOf(nowState?.score ?: 0, if (isNight) night?.peak?.score ?: 0 else 0)
                        SoftReveal(isNight && photoScore >= 40) {
                            PhotoTipsCard(
                                score = photoScore,
                                moonBright = (sel?.moonIllum ?: 0.0) > 0.5 && (sel?.moonAlt ?: 0.0) > 0,
                                collapsed = "photo" in collapsed, onToggle = { toggle("photo") },
                            )
                        }
                    }
                    val dressForIt: @Composable ColumnScope.() -> Unit = {
                        SoftReveal(weather != null && sel != null) {
                            if (weather != null && sel != null) {
                                DressCard(weather, sel.time, home.name, "dress" in collapsed, { toggle("dress") })
                            }
                        }
                    }
                    val whereToGo: @Composable ColumnScope.() -> Unit = {
                        SoftReveal(spotScores.isNotEmpty() && sel != null) {
                            sel?.let {
                                WhereCard(
                                    spotScores, it, "where" in collapsed, { toggle("where") },
                                    tomorrow = showTomorrow, modifier = Modifier.cardAnchor("where"),
                                )
                            }
                        }
                    }
                    val nextDays: @Composable ColumnScope.() -> Unit = {
                        SoftReveal(kpDays != null) {
                            kpDays?.let { KpOutlookCard(it, now, "days" in collapsed, { toggle("days") }, Modifier.cardAnchor("days")) }
                        }
                    }
                    if (isNight) {
                        liveNow(); whereToGo(); photoTips(); tonightSummary(); hourByHour(); dressForIt(); nextDays()
                    } else {
                        tonightSummary(); hourByHour(); whereToGo(); dressForIt(); nextDays()
                    }
                    MadeWithLove(errors)
                    Spacer(Modifier.height(16.dp))
                }
            }

            ProgressiveBlurHeader(pageLayer, height = statusTop + HeaderExpanded + HeaderFade, heightPx = headerHeightPx)
            // Teach pull-to-refresh on the first three opens.
            val showPullIntro = remember { prefs.pullHintCount < 3 }
            LaunchedEffect(Unit) { if (showPullIntro) prefs.pullHintCount = prefs.pullHintCount + 1 }
            PullHint(
                pullFraction = pullFraction,
                loading = loading,
                top = statusTop + HeaderExpanded + 6.dp,
                showOnOpen = showPullIntro,
            )
            Header(
                placeName = home.name,
                score = night?.peak?.score,
                placeMorph = placeMorph,
                scoreMorph = scoreMorph,
                loading = loading,
                onRefresh = { scope.launch { reload(true) } },
                onSettings = { openSheet() },
                onPillTarget = { pillTarget = it },
                pull = pullFraction,
            )

            // The big number itself, flying from the hero into the header pill. Always composed;
            // position, size, colour and visibility are all applied on the GPU per frame.
            val peakScore = night?.peak?.score
            if (peakScore != null) {
                val endScale = with(density) { 15.sp.toPx() / HeroScoreStyle.fontSize.toPx() } * 1.25f
                val flight: GraphicsLayerScope.(Boolean) -> Unit = { coloured ->
                    val m = scoreMorph()
                    if (m <= 0f || !heroBase.isSpecified || !pillTarget.isSpecified) {
                        alpha = 0f
                    } else {
                        val e = FastOutSlowInEasing.transform(m)
                        val sc = 1f + (endScale - 1f) * e
                        val target = headerOrigin + pillTarget
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = sc
                        scaleY = sc
                        val cx = (heroBase.x + heroSize.width / 2f) * (1f - e) + target.x * e
                        val cy = (heroBase.y - scroll.value + heroSize.height / 2f) * (1f - e) + target.y * e
                        translationX = cx - heroSize.width * sc / 2f
                        translationY = cy - heroSize.height * sc / 2f
                        val fadeOut = 1f - ((m - 0.86f) / 0.14f).coerceIn(0f, 1f)
                        // White copy fades as the score-coloured copy fades in: a colour shift with no recomposition.
                        alpha = fadeOut * if (coloured) e else 1f - e
                    }
                }
                Text(
                    peakScore.toString(), style = HeroScoreStyle, maxLines = 1,
                    modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer { flight(false) },
                )
                Text(
                    peakScore.toString(), style = HeroScoreStyle.copy(color = scoreColor(peakScore), shadow = null), maxLines = 1,
                    modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer { flight(true) },
                )
            }
        }

        if (sheetVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = openness() }
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeSheet() }
            )
            SettingsSheet(
                sheet = sheet,
                maxHeight = screenH * 0.9f,
                onClose = { closeSheet() },
                onSettle = { velocity ->
                    Haptics.settle(view)
                    scope.launch {
                        if (sheet.value > 0.3f || velocity > 1400f) sheet.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
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

/**
 * Expanded: "Ljós" with the place on its own line below. As you scroll, the place glides up into
 * the title line, then "Tonight 63 · Good chance" fades in after it. Everything moves on the GPU
 * (graphicsLayer), so nothing re-lays out mid-scroll.
 */
@Composable
private fun Header(
    placeName: String,
    score: Int?,
    placeMorph: () -> Float,
    scoreMorph: () -> Float,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onPillTarget: (Offset) -> Unit,
    pull: () -> Float = { 0f },
) {
    val density = LocalDensity.current
    var titleW by remember { mutableIntStateOf(0) }
    var chipW by remember { mutableIntStateOf(0) }
    var chipH by remember { mutableIntStateOf(0) }
    var pillW by remember { mutableIntStateOf(0) }
    var pillH by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
            .height(HeaderExpanded - 12.dp)
    ) {
        val rowH = with(density) { 36.dp.toPx() }
        val gap = with(density) { 10.dp.toPx() }
        val line2Y = with(density) { 42.dp.toPx() }
        val buttonsW = with(density) { 92.dp.toPx() }
        val fullW = constraints.maxWidth.toFloat()

        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Ljós", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                modifier = Modifier.onSizeChanged { titleW = it.width },
            )
            Spacer(Modifier.weight(1f))
            RoundButton(onClick = onRefresh, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Ink, strokeWidth = 2.dp)
                else RefreshIcon(Modifier.graphicsLayer { rotationZ = pull().coerceIn(0f, 1.5f) * 300f })
            }
            Spacer(Modifier.width(8.dp))
            RoundButton(onClick = onSettings) { TuneIcon() }
        }

        // Place chip: own line when expanded, glides into the title line when collapsed.
        Row(
            Modifier
                .graphicsLayer {
                    val p = placeMorph()
                    translationX = (titleW + gap) * p
                    translationY = line2Y * (1f - p) + ((rowH - chipH) / 2f) * p
                }
                .onSizeChanged { chipW = it.width; chipH = it.height }
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onSettings)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PinIcon(Modifier.size(width = 10.dp, height = 13.dp))
            Spacer(Modifier.width(6.dp))
            // Width that fits between the title and the score pill once collapsed.
            val nameMax = with(density) {
                (fullW - buttonsW - pillW - titleW - gap * 2 - 24.dp.toPx()).coerceAtLeast(40.dp.toPx()).toDp()
            }
            Text(
                placeName, maxLines = 1, fontSize = 13.sp, color = Ink.copy(alpha = 0.78f),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = nameMax),
            )
            Text("  ›", color = Faint, fontSize = 13.sp, modifier = Modifier.graphicsLayer { alpha = 1f - placeMorph() })
        }

        // Compact score pill, right-aligned against the buttons once the hero has scrolled away.
        if (score != null) {
            val pillX = fullW - buttonsW - pillW
            // Tell the screen where the number should land (pill centre, header coordinates).
            LaunchedEffect(pillX, pillW, pillH) {
                if (pillW > 0) onPillTarget(Offset(pillX + pillW / 2f, rowH / 2f))
            }
            Box(
                Modifier
                    .onSizeChanged { pillW = it.width; pillH = it.height }
                    .graphicsLayer {
                        val landed = ((scoreMorph() - 0.8f) / 0.2f).coerceIn(0f, 1f)
                        translationX = pillX
                        translationY = (rowH - pillH) / 2f
                        alpha = landed
                        val sc = 0.9f + 0.1f * landed
                        scaleX = sc; scaleY = sc
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(scoreColor(score).copy(alpha = 0.18f))
                    .border(1.dp, scoreColor(score).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(score.toString(), color = scoreColor(score), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    val view = LocalView.current
    Box(
        Modifier
            // requiredSize: never squeezed by a crowded row, so it stays a true circle
            .requiredSize(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color(0x1FFFFFFF))
            .border(1.dp, Color(0x14FFFFFF), CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { Haptics.tap(view); onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val SheetHeader = 66.dp

/**
 * Bottom sheet that follows the finger. It has its own blurred header (same technique as the
 * main screen) that you can drag, and drags inside the content move the sheet once the content
 * is scrolled to the top.
 */
@Composable
private fun SettingsSheet(
    sheet: Animatable<Float, *>,
    maxHeight: Dp,
    onClose: () -> Unit,
    onSettle: (velocityY: Float) -> Unit,
    onDrag: (fraction: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    var heightPx by remember { mutableIntStateOf(100_000) }
    val inner = rememberScrollState()
    val layer = rememberGraphicsLayer()
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && sheet.value > 0f) {
                    onDrag(available.y / heightPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
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
    val dragState = rememberDraggableState { d -> onDrag(d / heightPx) }

    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val tint = if (Build.VERSION.SDK_INT >= 31) Color(0x660A1022) else Color(0xEB0A1022)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
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
        ) {
            Column(
                Modifier
                    // Fade the sharp content out where the header sits, so only the blurred copy
                    // shows there. (The sheet's content has no opaque background, so without this
                    // plain text stays readable through the blur.) The recorded layer below is
                    // captured before this mask, so the blur still has full content to work with.
                    .fadeUnderHeader(SheetHeader, SheetHeader + 36.dp)
                    .backdropSource(layer)
                    .nestedScroll(connection)
                    .verticalScroll(inner)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 22.dp)
                    .padding(top = SheetHeader + 4.dp, bottom = 18.dp)
            ) { content() }

            ProgressiveBlurHeader(layer, SheetHeader + 40.dp, maxRadius = 56.dp, tint = Color(0xE00A1022))
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(SheetHeader)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { v -> onSettle(v) },
                    )
                    .padding(horizontal = 22.dp)
                    .padding(top = 10.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x59FFFFFF))
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Settings", "Stillingar"), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.weight(1f))
                    Text(
                        L.t("Done", "Lokið"), color = Green, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
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


/** Live-sky conditions for one hour. Moon height follows its altitude; it drifts across the night. */
private fun skyFor(h: HourScore, night: Night?): SkyState {
    val hours = night?.hours.orEmpty()
    val frac = if (hours.size > 1) {
        ((h.time - hours.first().time).toFloat() / (hours.last().time - hours.first().time)).coerceIn(0f, 1f)
    } else 0.5f
    return SkyState(
        activity = h.factors.activity.toFloat(),
        cloud = (h.cloud.coerceAtLeast(0) / 100f),
        moonIllum = h.moonIllum.toFloat(),
        moonUp = if (h.moonAlt > 0) 1f else 0f,
        moonX = 0.15f + 0.7f * frac,
        moonY = 0.32f - (h.moonAlt.coerceIn(0.0, 40.0) / 40.0).toFloat() * 0.22f,
        storm = ((h.kp - 4.5) / 2.0).coerceIn(0.0, 1.0).toFloat(),
    )
}

/**
 * Circular-arrow refresh icon drawn geometrically, so it sits dead centre in its circle
 * (a text glyph carries font ascent/descent and drifts off-centre).
 */
@Composable
private fun RefreshIcon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val sw = 1.8.dp.toPx()
        val r = size.minDimension / 2f - sw - 1.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        val startDeg = -60f
        val sweep = 290f
        drawArc(
            Ink, startDeg, sweep, false,
            topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        // Arrowhead at the arc's end, pointing clockwise (the way the arc travels).
        val endDeg = startDeg + sweep
        val a = Math.toRadians(endDeg.toDouble())
        val tip = Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat())
        val head = 4.2.dp.toPx()
        // Clockwise travel at angle θ heads toward θ + 90°; the wings trail back toward θ − 90°.
        val back = Math.toRadians(endDeg.toDouble() - 90.0)
        fun wing(off: Double) = Offset(
            tip.x + (head * kotlin.math.cos(back + off)).toFloat(),
            tip.y + (head * kotlin.math.sin(back + off)).toFloat(),
        )
        val path = Path().apply {
            moveTo(wing(0.55).x, wing(0.55).y)
            lineTo(tip.x, tip.y)
            lineTo(wing(-0.55).x, wing(-0.55).y)
        }
        drawPath(path, Ink, style = Stroke(sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Cards fade and unfold into place when their data arrives, instead of popping in. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SoftReveal(visible: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(tween(420, easing = FastOutSlowInEasing)) +
            androidx.compose.animation.expandVertically(spring(dampingRatio = 0.9f, stiffness = 260f)),
        exit = androidx.compose.animation.fadeOut(tween(200)) +
            androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 400f)),
    ) { content() }
}

/**
 * A quiet line under the header that teaches pull-to-refresh: fades in with the pull, says
 * "Release to refresh" past the line, "Refreshing…" while loading, and fades out after.
 * Also drifts in and out once on the first few opens so people know it exists.
 */
@Composable
private fun PullHint(pullFraction: () -> Float, loading: Boolean, top: Dp, showOnOpen: Boolean) {
    val pastLine by remember { derivedStateOf { pullFraction() >= 1f } }
    val pulling by remember { derivedStateOf { pullFraction() > 0.02f } }
    // "Refreshing…" shows only for refreshes the pull started.
    var pullStarted by remember { mutableStateOf(false) }
    LaunchedEffect(pastLine) { if (pastLine) pullStarted = true }
    LaunchedEffect(loading) { if (!loading) { delay(350); pullStarted = false } }
    val refreshing = loading && pullStarted
    val refreshAlpha by animateFloatAsState(if (refreshing) 1f else 0f, tween(400, easing = FastOutSlowInEasing), label = "refreshing")

    val intro = remember { Animatable(0f) }
    LaunchedEffect(showOnOpen) {
        if (!showOnOpen) return@LaunchedEffect
        delay(1200)
        intro.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(2600)
        intro.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
    }

    val text = when {
        refreshing -> L.t("Refreshing…", "Uppfæri…")
        pastLine -> L.t("Release to refresh", "Slepptu til að uppfæra")
        else -> L.t("Pull down to refresh", "Dragðu niður til að uppfæra")
    }
    Box(Modifier.fillMaxWidth().padding(top = top), contentAlignment = Alignment.TopCenter) {
        androidx.compose.animation.AnimatedContent(
            targetState = text,
            transitionSpec = {
                androidx.compose.animation.fadeIn(tween(220)) togetherWith androidx.compose.animation.fadeOut(tween(160))
            },
            label = "pullText",
        ) { t ->
            Row(
                Modifier.graphicsLayer {
                    val p = pullFraction().coerceIn(0f, 1.2f)
                    alpha = maxOf((p * 1.4f).coerceAtMost(1f), intro.value, refreshAlpha)
                    // Drifts down a touch with the pull, like it's being drawn out.
                    translationY = p * 10.dp.toPx() + (1f - intro.value) * (if (pulling) 0f else -4.dp.toPx())
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (t == L.t("Refreshing…", "Uppfæri…")) {
                    CircularProgressIndicator(Modifier.size(11.dp), color = Muted, strokeWidth = 1.5.dp)
                } else {
                    Text(if (pastLine) "↑" else "↓", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                Text(t, color = Muted, fontSize = 12.sp, letterSpacing = 0.4.sp)
            }
        }
    }
}
