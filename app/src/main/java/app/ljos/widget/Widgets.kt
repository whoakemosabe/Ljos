package app.ljos.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.ljos.Fmt
import app.ljos.L
import app.ljos.MainActivity
import app.ljos.data.HOUR_MS
import app.ljos.data.Repo
import app.ljos.model.HourScore
import app.ljos.model.Model
import app.ljos.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Widgets {
    suspend fun updateAll(context: Context) {
        SmallWidget().updateAll(context)
        WideWidget().updateAll(context)
    }
}

/** Everything a widget shows, computed from the cached feeds. */
class WidgetData(
    val score: Int?,
    val label: String,
    val sub: String,
    val hours: List<HourScore>,
    val now: Long,
    val lookUp: Boolean,
) {
    companion object {
        fun load(context: Context): WidgetData {
            L.load(context)
            val now = System.currentTimeMillis()
            val inp = Repo(context).inputs()
            if (inp.isEmpty) return WidgetData(null, L.t("Open Ljós", "Opnaðu Ljós"), L.t("to load data", "til að sækja gögn"), emptyList(), now, false)
            val night = Model.night(now, inp)
            val peak = night.peak
                ?: return WidgetData(null, L.t("Too bright", "Of bjart"), L.t("No dark hours tonight", "Engin myrkur í nótt"), emptyList(), now, false)
            val st = Model.nowState(now, inp)
            val stale = now - inp.updatedAt > 6 * HOUR_MS
            val sub = when {
                stale -> L.t("Updated ", "Uppfært ") + Fmt.ago(inp.updatedAt, now)
                st.isDark -> L.t("Now ${st.score} · peak ${Fmt.hhmm(peak.time)}", "Núna ${st.score} · hámark ${Fmt.hhmm(peak.time)}")
                else -> L.t("Peak ", "Hámark ") + "${Fmt.hhmm(peak.time)} · ${Fmt.cloud(peak.cloud)}"
            }
            val label = if (st.lookUp) L.t("Look up now!", "Líttu upp!") else Model.label(peak.score)
            return WidgetData(peak.score, label, sub, night.hours, now, st.lookUp)
        }
    }
}

private val White = Color(0xFFFFFFFF)
private val Ink = Color(0xFFE8F1FF)
private val Muted = Color(0xA6E8F1FF)
private val Accent = Color(0xFF3DFFA0)

class SmallWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) { WidgetData.load(context) }
        provideContent { SmallContent(data) }
    }
}

class WideWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) { WidgetData.load(context) }
        provideContent { WideContent(data) }
    }
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallWidget()
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Scheduler.ensure(context)
    }
}

class WideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WideWidget()
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Scheduler.ensure(context)
    }
}

@Composable
private fun SmallContent(d: WidgetData) {
    val size = LocalSize.current
    val bg = WidgetArt.render(size.width.value, size.height.value, d.score ?: 0)
    Box(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(26.dp).clickable(actionStartActivity<MainActivity>()),
    ) {
        Image(
            provider = ImageProvider(bg),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            Text(
                L.t("TONIGHT", "Í KVÖLD"),
                style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.defaultWeight())
            ScoreBlock(d, 42)
        }
    }
}

@Composable
private fun WideContent(d: WidgetData) {
    val size = LocalSize.current
    val leftDp = 120f
    val bg = WidgetArt.render(size.width.value, size.height.value, d.score ?: 0, d.hours, d.now, barsLeftDp = leftDp + 8f)
    Box(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(26.dp).clickable(actionStartActivity<MainActivity>()),
    ) {
        Image(
            provider = ImageProvider(bg),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Row(modifier = GlanceModifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.Bottom) {
            Column(modifier = GlanceModifier.width((leftDp - 16f).dp).fillMaxHeight()) {
                Text(
                    L.t("TONIGHT", "Í KVÖLD"),
                    style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(GlanceModifier.defaultWeight())
                ScoreBlock(d, 40)
            }
        }
    }
}

@Composable
private fun ScoreBlock(d: WidgetData, bigSp: Int) {
    Text(
        d.score?.toString() ?: "—",
        style = TextStyle(color = ColorProvider(White), fontSize = bigSp.sp, fontWeight = FontWeight.Bold),
    )
    Spacer(GlanceModifier.height(2.dp))
    Text(
        d.label,
        style = TextStyle(
            color = ColorProvider(if (d.lookUp) Accent else Ink),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
    Text(d.sub, style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp), maxLines = 1)
}
