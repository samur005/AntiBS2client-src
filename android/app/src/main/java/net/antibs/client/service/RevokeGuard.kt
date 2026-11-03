package net.antibs.client.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 2.0.8: another VPN app took over (VpnService.onRevoke). Our session is torn down and automatic restarts
 * (subscription-update reconnect, schedule «on», boot) are held off for [HOLD_MS] so we don't fight the other app.
 * A manual tap on the power button always connects.
 */
object RevokeGuard {
    const val HOLD_MS = 5 * 60_000L
    private const val CHANNEL = "antibs_revoke"
    private const val NOTIF_ID = 7
    @Volatile var revokedAt: Long = 0L

    /** Pure rule (unit tested). */
    fun holding(now: Long, revokedAt: Long = this.revokedAt): Boolean = revokedAt > 0 && now - revokedAt in 0 until HOLD_MS

    fun mark(ctx: Context, now: Long = System.currentTimeMillis()) {
        revokedAt = now
        ctx.getSharedPreferences("revoke", Context.MODE_PRIVATE).edit().putLong("at", now).apply()
    }

    fun clear(ctx: Context) {
        revokedAt = 0L
        ctx.getSharedPreferences("revoke", Context.MODE_PRIVATE).edit().remove("at").apply()
    }

    fun holdingNow(ctx: Context): Boolean {
        if (revokedAt == 0L) revokedAt = ctx.getSharedPreferences("revoke", Context.MODE_PRIVATE).getLong("at", 0L)
        return holding(System.currentTimeMillis())
    }

    fun notifyRevoked(ctx: Context) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return@runCatching
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Отключение другой VPN", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = android.app.PendingIntent.getActivity(ctx, 0,
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName), android.app.PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
        nm.notify(NOTIF_ID, b.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("VPN отключён")
            .setContentText("Включена другая VPN-программа")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}
