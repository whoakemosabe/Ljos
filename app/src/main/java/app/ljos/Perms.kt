package app.ljos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permission helpers. Android stops showing the prompt after two denials and just
 * returns "denied", so a plain re-request would make the Allow button do nothing. Ljós remembers
 * which permissions it has asked for; once one was asked and the system no longer offers a
 * rationale, the only way to grant it is the app's settings page.
 */
object Perms {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("ljos_perms", Context.MODE_PRIVATE)

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun wasAsked(context: Context, permission: String): Boolean =
        prefs(context).getBoolean(permission, false)

    fun markAsked(context: Context, permission: String) {
        prefs(context).edit().putBoolean(permission, true).apply()
    }

    /** Asked before, still not granted, and the system won't show the prompt again. */
    fun blocked(context: Context, permission: String): Boolean {
        if (granted(context, permission) || !wasAsked(context, permission)) return false
        val activity = context.findActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
