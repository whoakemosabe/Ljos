package app.ljos.data

import android.content.Context
import app.ljos.L
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer ljos.apk, downloads it and hands it to the system installer.
 * The repo is public, so no token is needed (one is still sent if passed, e.g. for a fork).
 */
object Updater {
    private const val REPO = "whoakemosabe/Ljos"

    data class Release(val version: String, val assetUrl: String, val sizeBytes: Long)

    sealed class Check {
        data object UpToDate : Check()
        data class Available(val release: Release) : Check()
        data class Failed(val message: String) : Check()
    }

    fun installedVersion(context: Context): String =
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { null } ?: "0"

    /** "v1.0.12" / "1.0.12" → [1, 0, 12]. */
    fun parts(v: String): List<Int> = v.trim().removePrefix("v").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(context: Context, token: String): Check = withContext(Dispatchers.IO) {
        try {
            val c = open("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json")
            val code = c.responseCode
            if (code == 404 || code == 401) {
                c.disconnect()
                return@withContext Check.Failed(
                    L.t("GitHub refused the request. Try again in a bit.", "GitHub hafnaði beiðninni. Reyndu aftur eftir smá stund.")
                )
            }
            if (code !in 200..299) throw IOException("GitHub returned $code")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var asset: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) asset = a
            }
            val apk = asset ?: return@withContext Check.Failed(L.t("Latest release has no APK yet.", "Nýjasta útgáfan er ekki með APK enn."))
            if (!isNewer(tag, installedVersion(context))) return@withContext Check.UpToDate
            Check.Available(Release(tag.removePrefix("v"), apk.getString("url"), apk.optLong("size")))
        } catch (e: Exception) {
            Check.Failed(L.t("Couldn't reach GitHub.", "Náði ekki sambandi við GitHub."))
        }
    }

    /** Downloads the APK into the app cache, reporting progress 0..1. */
    suspend fun download(context: Context, release: Release, token: String, progress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            // The asset API answers with a redirect to signed storage, which must be fetched without the token.
            val first = open(release.assetUrl, token, "application/octet-stream")
            first.instanceFollowRedirects = false
            val conn = if (first.responseCode in 300..399) {
                val location = first.getHeaderField("Location") ?: throw IOException("Download link missing")
                first.disconnect()
                open(location, "", "application/octet-stream")
            } else {
                first
            }
            if (conn.responseCode !in 200..299) throw IOException("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "ljos.apk")
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            conn.disconnect()
            out
        }

    /** True if the system installer was opened; false if Ljós first needs "install unknown apps". */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Ljos-updater")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }
}
