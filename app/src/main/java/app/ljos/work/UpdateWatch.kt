package app.ljos.work

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.ljos.Prefs
import app.ljos.data.Updater

/**
 * Notices new releases on its own: the background refresh asks GitHub at most every 6 hours
 * and posts one quiet notification per new version; opening the app asks at most hourly and
 * shows a banner. Tapping either opens Settings with the update ready to download.
 */
object UpdateWatch {
    private const val BACKGROUND_EVERY = 6 * 60 * 60_000L
    private const val OPEN_EVERY = 60 * 60_000L

    /** Set when the app should open Settings → Updates (e.g. from the notification). */
    val openUpdates = mutableStateOf(false)

    /** The newer version waiting, if any, from the last check. */
    fun waiting(context: Context): String? {
        val v = Prefs(context).availableVersion
        return v.takeIf { it.isNotBlank() && Updater.isNewer(it, Updater.installedVersion(context)) }
    }

    /** Asks GitHub if it's been long enough; returns the newer version waiting, if any. */
    suspend fun check(context: Context, background: Boolean): String? {
        val p = Prefs(context)
        val now = System.currentTimeMillis()
        val every = if (background) BACKGROUND_EVERY else OPEN_EVERY
        if (now - p.lastUpdateCheck >= every) {
            p.lastUpdateCheck = now
            when (val r = Updater.check(context, "")) {
                is Updater.Check.Available -> p.availableVersion = r.release.version
                Updater.Check.UpToDate -> p.availableVersion = ""
                is Updater.Check.Failed -> {}
            }
        }
        return waiting(context)
    }

    /** Background: check, and notify once per new version. */
    suspend fun checkAndNotify(context: Context) {
        val v = check(context, background = true) ?: return
        val p = Prefs(context)
        if (p.notifiedVersion == v) return
        p.notifiedVersion = v
        Alerts.postUpdate(context, v)
    }
}
