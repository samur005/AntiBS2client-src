package net.antibs.client.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import kotlinx.serialization.json.Json
import net.antibs.client.data.VpnScheduleCfg
import net.antibs.client.engine.VpnState
import net.antibs.client.engine.ConnState

/** «Расписание VPN» (ABS port): AlarmManager-driven connect/disconnect; re-armed on boot, update and every change. */
object VpnScheduler {
    const val ACTION = "net.antibs.client.SCHEDULE_FIRE"
    private const val PREFS = "vpn_schedule"; private const val KEY = "cfg"; private const val REQ = 7340
    private const val CHANNEL = "antibs_schedule"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(ctx: Context): VpnScheduleCfg = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { json.decodeFromString(VpnScheduleCfg.serializer(), it) } ?: VpnScheduleCfg()
    } catch (_: Exception) { VpnScheduleCfg() }

    fun save(ctx: Context, cfg: VpnScheduleCfg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.encodeToString(VpnScheduleCfg.serializer(), cfg)).apply()
        arm(ctx, cfg)
    }

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false)

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, REQ, Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Returns the armed time, or null. */
    fun arm(ctx: Context, cfg: VpnScheduleCfg = load(ctx)): Long? {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return null
        val p = pi(ctx)
        am.cancel(p)
        val at = cfg.next(System.currentTimeMillis())?.first ?: return null
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
        return at
    }

    fun fire(ctx: Context) {
        val cfg = load(ctx)
        val rule = cfg.due(System.currentTimeMillis())
        if (cfg.on && rule != null) {
            val running = VpnState.status.value.state.let { it == ConnState.CONNECTED || it == ConnState.CONNECTING }
            val msg: String? = if (rule.connect) {
                when {
                    running -> null
                    VpnService.prepare(ctx) != null -> "Расписание: нет разрешения VPN — откройте приложение"
                    else -> try { AntiBSVpnService.connect(ctx); "VPN включён по расписанию" } catch (e: Exception) { "Расписание: ${e.message}" }
                }
            } else if (running) { try { AntiBSVpnService.disconnect(ctx); "VPN выключен по расписанию" } catch (e: Exception) { "Расписание: ${e.message}" } } else null
            if (msg != null) { VpnState.log(msg); if (cfg.notify) notify(ctx, msg) }
        }
        arm(ctx, cfg)
    }

    private fun notify(ctx: Context, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Расписание VPN", NotificationManager.IMPORTANCE_DEFAULT))
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            nm.notify(7341, b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Расписание VPN").setContentText(text).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            VpnScheduler.ACTION -> VpnScheduler.fire(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> VpnScheduler.arm(context)
        }
    }
}
