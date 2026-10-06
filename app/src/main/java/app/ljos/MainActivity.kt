package app.ljos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
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
        setContent {
            // No Android 12+ "stretch" overscroll: inside the recorded blur layer its spring-back
            // never fires, leaving the page stretched and swallowing swipes at the bottom.
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            LjosTheme {
                var onboarded by remember { mutableStateOf(prefs.onboarded) }
                Crossfade(onboarded, animationSpec = tween(600), label = "start") { done ->
                    if (done) LjosApp() else Onboarding { prefs.onboarded = true; onboarded = true }
                }
            }
            }
        }
    }
}
