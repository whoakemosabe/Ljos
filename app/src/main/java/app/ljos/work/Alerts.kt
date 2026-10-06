package app.ljos.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ljos.Fmt
import app.ljos.MainActivity
import app.ljos.Prefs
import app.ljos.R
import app.ljos.data.Inputs
import app.ljos.data.MIN_MS
import app.ljos.model.Model
import java.time.Instant
import java.time.ZoneId

object Alerts {
    private const val CH_TONIGHT = "tonight"
    private const val CH_NOW = "lookup"
    private const val ID_TONIGHT = 1
    private const val ID_NOW = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_TONIGHT, "Tonight's forecast", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Evening heads-up when tonight looks good"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NOW, "Look up now", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Live alert when aurora is likely overhead"
            }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun check(context: Context, inp: Inputs, now: Long = System.currentTimeMillis()) {
        if (inp.isEmpty || !canNotify(context)) return
        createChannels(context)
        val prefs = Prefs(context)

        // Evening heads-up: once per night, between 16:00 and 22:59, for an upcoming peak.
        val night = Model.night(now, inp)
        val peak = night.peak
        val localHour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val nightKey = Instant.ofEpochMilli(night.start).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (prefs.tonightAlerts && peak != null && localHour in 16..22 &&
            peak.time + 60 * MIN_MS > now && peak.score >= prefs.threshold && prefs.lastTonightKey != nightKey
        ) {
            val best = Model.spotsAt(peak.time, inp, now).firstOrNull()
            val where = best?.let { " Clearest at ${it.spot.name}." } ?: ""
            post(
                context, CH_TONIGHT, ID_TONIGHT,
                "Tonight: ${peak.score} · ${Model.label(peak.score)}",
                "Peaks around ${Fmt.hhmm(peak.time)}, ${Fmt.cloud(peak.cloud)}.$where",
            )
            prefs.lastTonightKey = nightKey
        }

        // Look up now: dark, clear enough, and the solar wind field just turned south.
        val st = Model.nowState(now, inp)
        if (prefs.lookUpAlerts && st.lookUp && now - prefs.lastLookUpAt > 90 * MIN_MS) {
            val bz = st.bz?.let { "Bz ${Fmt.signed(it)} nT" } ?: "Solar wind turned south"
            val where = st.clearerSpot?.let { " Cloudy at home — ${it.spot.name} is clearer." } ?: ""
            post(context, CH_NOW, ID_NOW, "Look up now", "$bz, score ${st.score}.$where")
            prefs.lastLookUpAt = now
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_aurora)
            .setColor(0xFF3DFFA0.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (channel == CH_NOW) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(id, n)
    }
}
