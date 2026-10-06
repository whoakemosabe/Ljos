package app.ljos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ljos.ui.LjosApp
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
        setContent {
            LjosTheme { LjosApp() }
        }
    }
}
