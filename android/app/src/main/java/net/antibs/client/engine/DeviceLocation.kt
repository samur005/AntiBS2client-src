package net.antibs.client.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 1.0.48: one-shot device position for the home globe / map (Android LocationManager; the app has no Google Play
 * services, so no FusedLocation). Never runs in the background: called only while «Главная» is visible.
 */
object DeviceLocation {
    fun granted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 12_000): Location? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        }
        val fresh = withTimeoutOrNull(timeoutMs) {
            for (p in providers) {
                val l = runCatching { one(ctx, lm, p) }.getOrNull()
                if (l != null) return@withTimeoutOrNull l
            }
            null
        }
        if (fresh != null) return fresh
        // fall back to the newest cached fix (≤ 30 min old)
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 30 * 60_000L }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun one(ctx: Context, lm: LocationManager, provider: String): Location? =
        if (Build.VERSION.SDK_INT >= 30) suspendCancellableCoroutine { c ->
            val cancel = android.os.CancellationSignal()
            c.invokeOnCancellation { cancel.cancel() }
            lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { l -> if (c.isActive) c.resume(l) }
        } else withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { c ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(l: Location) { lm.removeUpdates(this); if (c.isActive) c.resume(l) }
                    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderDisabled(p: String) { lm.removeUpdates(this); if (c.isActive) c.resume(null) }
                }
                c.invokeOnCancellation { lm.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
            }
        }

    /** Best-effort city / country name for a fix (platform Geocoder, may be unavailable offline). */
    suspend fun place(ctx: Context, lat: Double, lon: Double): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!android.location.Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(ctx, java.util.Locale.forLanguageTag("ru")).getFromLocation(lat, lon, 1)?.firstOrNull()
        }.getOrNull()?.let { a -> (a.locality ?: a.subAdminArea ?: a.adminArea ?: "") to (a.countryName ?: "") }
    }
}
