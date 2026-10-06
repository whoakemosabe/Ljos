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
import app.ljos.L
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
    private const val CH_LIVE = "live"
    private const val ID_TONIGHT = 1
    private const val ID_NOW = 2
    private const val ID_LIVE = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_TONIGHT, L.t("Tonight's forecast", "Spá kvöldsins"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = L.t("Evening heads-up when tonight looks good", "Kvöldviðvörun þegar kvöldið lítur vel út")
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NOW, L.t("Look up now", "Líttu upp núna"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = L.t("Live alert when aurora is likely overhead", "Viðvörun þegar norðurljós eru líkleg")
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, L.t("Aurora on lock screen", "Norðurljós á lásskjá"), NotificationManager.IMPORTANCE_LOW).apply {
                description = L.t("Silent live card while aurora is likely", "Hljóðlaust spjald á meðan norðurljós eru líkleg")
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun check(context: Context, inp: Inputs, now: Long = System.currentTimeMillis()) {
        if (inp.isEmpty || !canNotify(context)) return
        L.load(context)
        createChannels(context)
        val prefs = Prefs(context)
        val localHour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        val quiet = prefs.isQuiet(localHour)

        // Evening heads-up: once per night, 16:00–22:59, for an upcoming peak.
        val night = Model.night(now, inp)
        val peak = night.peak
        val nightKey = Instant.ofEpochMilli(night.start).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (prefs.tonightAlerts && !quiet && peak != null && localHour in 16..22 &&
            peak.time + 60 * MIN_MS > now && peak.score >= prefs.threshold && prefs.lastTonightKey != nightKey
        ) {
            val best = Model.spotsAt(peak.time, inp, now).firstOrNull()
            val where = when {
                best == null -> ""
                best.spot.id == "home" -> L.t(" Best right where you are.", " Best þar sem þú ert.")
                else -> L.t(" Best at ${best.spot.name}.", " Best við ${best.spot.name}.")
            }
            post(
                context, CH_TONIGHT, ID_TONIGHT,
                L.t("Tonight: ${peak.score} · ${Model.label(peak.score)}", "Í kvöld: ${peak.score} · ${Model.label(peak.score)}"),
                L.t("Peaks around ${Fmt.hhmm(peak.time)}, ${Fmt.cloud(peak.cloud)}.", "Hámark um ${Fmt.hhmm(peak.time)}, ${Fmt.cloud(peak.cloud)}.") + where,
            )
            prefs.lastTonightKey = nightKey
        }

        // Look up now: dark, clear enough, and the solar wind field just turned south.
        val st = Model.nowState(now, inp)
        if (prefs.lookUpAlerts && !quiet && st.lookUp && now - prefs.lastLookUpAt > 90 * MIN_MS) {
            val bz = st.bz?.let { "Bz ${Fmt.signed(it)} nT" } ?: L.t("Solar wind turned south", "Sólvindurinn snerist suður")
            val where = st.clearerSpot?.let {
                L.t(" Cloudy at home — ${it.spot.name} is clearer.", " Skýjað heima — heiðskírara við ${it.spot.name}.")
            } ?: ""
            post(context, CH_NOW, ID_NOW, L.t("Look up now", "Líttu upp núna"), L.t("$bz, score ${st.score}.", "$bz, einkunn ${st.score}.") + where)
            prefs.lastLookUpAt = now
        }

        // Live lock-screen card: silent, ongoing, updated each refresh while aurora is likely.
        val live = prefs.liveLockScreen && st.isDark && (st.lookUp || st.score >= prefs.threshold)
        if (live) {
            val title = if (st.lookUp) L.t("Aurora now · ${st.score}", "Norðurljós núna · ${st.score}")
            else L.t("Aurora likely · ${st.score}", "Norðurljós líkleg · ${st.score}")
            val bz = st.bz?.takeIf { st.bzFresh }?.let { "Bz ${Fmt.signed(it)} nT · " } ?: ""
            val where = st.clearerSpot?.let { L.t(" · clearer at ${it.spot.name}", " · heiðskírara við ${it.spot.name}") } ?: ""
            post(context, CH_LIVE, ID_LIVE, title, bz + Fmt.cloud(st.cloud) + where, ongoing = true, progress = st.score)
        } else {
            NotificationManagerCompat.from(context).cancel(ID_LIVE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(
        context: Context, channel: String, id: Int, title: String, text: String,
        ongoing: Boolean = false, progress: Int? = null,
    ) {
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_aurora)
            .setColor(0xFF3DFFA0.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(if (channel == CH_NOW) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (ongoing) b.setCategory(NotificationCompat.CATEGORY_STATUS).setSilent(true)
        if (progress != null) b.setProgress(100, progress.coerceIn(0, 100), false)
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(id, b.build())
    }
}
