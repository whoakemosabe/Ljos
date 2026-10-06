package app.ljos.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Finds the phone's rough location without Google Play services. City-level accuracy is plenty. */
object Locator {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** A fresh fix if one arrives within ~10 s, otherwise the newest last-known fix. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val providers = candidates.filter { p -> try { lm.isProviderEnabled(p) } catch (e: Exception) { false } }

        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                val fix = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        try {
                            lm.getCurrentLocation(p, signal, context.mainExecutor) { loc ->
                                if (cont.isActive) cont.resume(loc)
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                if (fix != null) return fix
            }
        }
        return providers
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
    }

    /** Town name for the header, e.g. "Njarðvík". Null if the phone has no geocoder. */
    @Suppress("DEPRECATION")
    suspend fun placeName(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.locality ?: a?.subLocality ?: a?.subAdminArea ?: a?.adminArea
        } catch (e: Exception) {
            null
        }
    }

    /** Detects, names and saves the location. Returns the new home, or null if nothing changed. */
    suspend fun detectAndSave(context: Context): Spot? {
        val loc = current(context) ?: return null
        val prefs = app.ljos.Prefs(context)
        val old = prefs.home
        val movedKm = app.ljos.model.Geo.km(old.lat, old.lon, loc.latitude, loc.longitude)
        val name = placeName(context, loc.latitude, loc.longitude)
            ?: if (movedKm < 2.0) old.name else String.format(Locale.US, "%.2f°, %.2f°", loc.latitude, loc.longitude)
        // Small jitter shouldn't refetch clouds; only save when it really moved or the name improved.
        if (movedKm < 2.0 && name == old.name && prefs.homeDetectedAt > 0) {
            prefs.home = old
            return null
        }
        val home = Spot("home", name, loc.latitude, loc.longitude, dark = false)
        prefs.home = if (movedKm < 2.0) old.copy(name = name) else home
        return prefs.home
    }
}
