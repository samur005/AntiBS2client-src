package net.antibs.client.cell

import android.content.Context
import android.location.LocationManager

/** Location service state (Android only reports cells / Wi‑Fi networks while it is on). */
object NetLocation {
    fun enabled(ctx: Context): Boolean = runCatching {
        val lm = ctx.getSystemService(LocationManager::class.java); androidx.core.location.LocationManagerCompat.isLocationEnabled(lm!!)
    }.getOrDefault(false)
}
