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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.ljos.L
import app.ljos.Prefs
import app.ljos.R
import app.ljos.data.Locator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private val Script = FontFamily(Font(R.font.pacifico))

/** Three-page first-run welcome: what Ljós is, how the score works, and permissions with context. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    // Aurora brightens page by page. Keyed on the target page (not the live swipe offset),
    // so swiping doesn't recompose the screen every frame.
    val glow by animateFloatAsState(
        0.35f + 0.3f * pager.targetPage,
        tween(900, easing = FastOutSlowInEasing),
        label = "glow",
    )

    Box(Modifier.fillMaxSize().background(NightBg)) {
        AuroraBackground(glow, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                SlidingSegments(listOf("English", "Íslenska"), if (L.icelandic) 1 else 0, Modifier.width(170.dp)) {
                    L.icelandic = it == 1
                    prefs.icelandic = L.icelandic
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1, // neighbours are ready before you swipe to them
            ) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Read the swipe offset here (draw phase only): soft fade and a
                            // slight scale-down as a page leaves, no extra sideways motion.
                            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                            alpha = 1f - offset * 0.55f
                            val sc = 1f - offset * 0.06f
                            scaleX = sc
                            scaleY = sc
                        }
                        .padding(horizontal = 28.dp),
                ) {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> ScorePage()
                        else -> PermissionsPage()
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { i ->
                    val active = pager.currentPage == i
                    val w by animateDpAsState(if (active) 22.dp else 7.dp, spring(dampingRatio = 0.7f, stiffness = 400f), label = "dot")
                    val c by animateColorAsState(if (active) Green else Color(0x40FFFFFF), tween(250), label = "dotColor")
                    Box(Modifier.padding(end = 6.dp).size(width = w, height = 7.dp).clip(CircleShape).background(c))
                }
                Spacer(Modifier.weight(1f))
                val last = pager.currentPage == 2
                Pill(if (last) L.t("Let's go", "Byrjum") else L.t("Next", "Áfram"), primary = true) {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            }
        }
    }
}

/** True on short screens: pages tighten their type and spacing. */
private val LocalCompact = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A page that's vertically centred when it fits and scrolls when it doesn't, so nothing is ever
 * cut off on small screens. Short screens also get tighter type and spacing.
 */
@Composable
private fun PageColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val minH = maxHeight
        androidx.compose.runtime.CompositionLocalProvider(LocalCompact provides compact) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minH).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.Center,
                    content = content,
                )
            }
        }
    }
}

/** Spacing that shrinks on short screens. */
@Composable
private fun gap(normal: Int): androidx.compose.ui.unit.Dp = if (LocalCompact.current) (normal * 0.55f).dp else normal.dp

@Composable
private fun WelcomePage() {
    val compact = LocalCompact.current
    PageColumn {
        Text(
            "Ljós",
            style = TextStyle(
                fontFamily = Script,
                fontSize = if (LocalCompact.current) 58.sp else 76.sp,
                brush = Brush.linearGradient(listOf(Green, Color(0xFF8FF5D0), Violet)),
            ),
        )
        Spacer(Modifier.height(gap(8)))
        Text(
            L.t("Will you see the northern lights tonight?", "Sérðu norðurljósin í kvöld?"),
            color = Ink, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Light, lineHeight = if (compact) 28.sp else 32.sp,
        )
        Spacer(Modifier.height(gap(16)))
        Text(
            L.t(
                "One score for tonight, right where you are, plus where to drive for clearer skies.",
                "Ein einkunn fyrir kvöldið, þar sem þú ert, og hvert á að keyra til að fá heiðskírari himin.",
            ),
            color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
        )
    }
}

@Composable
private fun ScorePage() {
    val compact = LocalCompact.current
    PageColumn {
        Text(L.t("How the score works", "Svona virkar einkunnin"), color = Ink, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(6.dp))
        Text(
            L.t("Four things, multiplied. Any one at zero means no show.", "Fjórir þættir, margfaldaðir. Ef einn er núll sést ekkert."),
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(gap(22)))
        Explainer(L.t("Solar activity", "Sólvirkni"), L.t("How stirred up the sky is (Kp)", "Hversu órólegur himinninn er (Kp)"), Green)
        Explainer(L.t("Clear sky", "Heiðskírt"), L.t("Low clouds hide everything", "Lág ský fela allt"), Teal)
        Explainer(L.t("Moon", "Tungl"), L.t("A bright moon washes out faint aurora", "Bjart tungl skyggir á dauf norðurljós"), Color(0xFFF3E7C1))
        Explainer(L.t("Darkness", "Myrkur"), L.t("The sun well below the horizon", "Sólin vel undir sjóndeildarhring"), Violet)
        Spacer(Modifier.height(gap(22)))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(Slate, Teal, Green, Violet)))
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("0", "40", "60", "80+").forEachIndexed { i, label ->
                Text(
                    label, color = Faint, fontSize = 11.sp, modifier = Modifier.weight(1f),
                    textAlign = if (i == 3) TextAlign.End else TextAlign.Start,
                )
            }
        }
        Text(
            L.t("40 is worth a look. 60+ is worth a drive.", "40 er þess virði að kíkja. 60+ er þess virði að keyra."),
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Explainer(title: String, sub: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (LocalCompact.current) 4.dp else 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Ink, fontSize = 16.sp)
            Text(sub, color = Faint, fontSize = 13.sp)
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Re-check while this page is open (people come back from system settings).
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            tick++
        }
    }
    val location = remember(tick) { Locator.hasPermission(context) }
    val notifications = remember(tick) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val background = remember(tick) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val compact = LocalCompact.current
    PageColumn {
        Text(L.t("Let Ljós look out for you", "Leyfðu Ljós að vaka fyrir þig"), color = Ink, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Light, lineHeight = if (compact) 28.sp else 32.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            L.t("All optional. You can change these later in Settings.", "Allt valfrjálst. Þú getur breytt þessu síðar í stillingum."),
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(gap(22)))
        PermissionRow(
            L.t("Location", "Staðsetning"),
            L.t("Clouds and darkness for where you actually are", "Ský og myrkur þar sem þú ert"),
            location,
        ) { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
        PermissionRow(
            L.t("Notifications", "Tilkynningar"),
            L.t("Evening heads-up and \"look up now\"", "Kvöldviðvörun og \"líttu upp núna\""),
            notifications,
        ) { if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }
        PermissionRow(
            L.t("Background refresh", "Uppfærsla í bakgrunni"),
            L.t("OnePlus and Oppo pause apps otherwise, so alerts never arrive", "Annars stöðva OnePlus og Oppo forritið og viðvaranir berast ekki"),
            background,
        ) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}"))
                )
            } catch (e: Exception) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, sub: String, granted: Boolean, onAllow: () -> Unit) {
    val view = LocalView.current
    val check by animateFloatAsState(if (granted) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "check")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (LocalCompact.current) 4.dp else 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(18.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !granted) {
                Haptics.tap(view); onAllow()
            }
            .padding(if (LocalCompact.current) 11.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(sub, color = Faint, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        // A fixed-size slot with both the button and the tick always in it: only their alpha and
        // scale animate, so nothing re-lays out (the springy tick overshooting used to make the
        // button pop back in and out, and the row jump in width).
        Box(Modifier.widthIn(min = 80.dp).height(36.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                L.t("Allow", "Leyfa"), color = Color(0xFF03130B), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .graphicsLayer {
                        val c = check.coerceIn(0f, 1f)
                        alpha = 1f - c; scaleX = 1f - 0.3f * c; scaleY = 1f - 0.3f * c
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(Green)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(28.dp).graphicsLayer { scaleX = check; scaleY = check; alpha = check.coerceIn(0f, 1f) }) {
                    drawCircle(Green.copy(alpha = 0.18f))
                    val sw = 2.2.dp.toPx()
                    val w = size.width
                    drawLine(Green, Offset(w * 0.3f, w * 0.52f), Offset(w * 0.44f, w * 0.66f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Green, Offset(w * 0.44f, w * 0.66f), Offset(w * 0.72f, w * 0.36f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}
