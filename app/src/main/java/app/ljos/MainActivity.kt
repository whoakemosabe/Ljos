package app.ljos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
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
            LjosTheme {
                var onboarded by remember { mutableStateOf(prefs.onboarded) }
                Crossfade(onboarded, animationSpec = tween(600), label = "start") { done ->
                    if (done) LjosApp() else Onboarding { prefs.onboarded = true; onboarded = true }
                }
            }
        }
    }
}
