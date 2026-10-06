package app.ljos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.ljos.ui.LjosApp
import app.ljos.ui.Onboarding
import app.ljos.ui.LjosTheme
import app.ljos.work.Alerts
import app.ljos.work.Scheduler

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        L.load(this)
        Alerts.createChannels(this)
        Scheduler.ensure(this)
        val prefs = Prefs(this)
        handleIntent(intent)
        setContent {
            // No Android 12+ "stretch" overscroll: inside the recorded blur layer its spring-back
            // never fires, leaving the page stretched and swallowing swipes at the bottom.
            // Scale everything on narrow phones: the layout is designed for ~410dp wide, so a
            // 360dp phone sees the same layout at 88% instead of wrapping. Never below 85%, so
            // text and buttons stay comfortable; and the system text size is capped at 115% so
            // very large text can't push rows onto two lines either.
            val base = LocalDensity.current
            val widthDp = LocalConfiguration.current.screenWidthDp
            val scale = (widthDp / 410f).coerceIn(0.85f, 1f)
            val scaled = Density(base.density * scale, base.fontScale.coerceAtMost(1.15f))
            CompositionLocalProvider(LocalOverscrollFactory provides null, LocalDensity provides scaled) {
            LjosTheme {
                var onboarded by remember { mutableStateOf(prefs.onboarded) }
                Crossfade(onboarded, animationSpec = tween(600), label = "start") { done ->
                    if (done) LjosApp() else Onboarding { prefs.onboarded = true; onboarded = true }
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The "update ready" notification asks to open Settings → Updates. */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(Alerts.EXTRA_OPEN_UPDATES, false) == true) {
            intent.removeExtra(Alerts.EXTRA_OPEN_UPDATES)
            app.ljos.work.UpdateWatch.openUpdates.value = true
        }
    }
}
