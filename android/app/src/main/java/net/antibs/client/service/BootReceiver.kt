package net.antibs.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.antibs.client.engine.AppStore

/** Re-schedules subscription auto-update; the VPN is no longer started on boot (2.0.9). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = AppStore(context)
        // 1.0.25: make sure «Автообновление подписок» is scheduled (WorkManager also restores it itself).
        net.antibs.client.engine.SubscriptionSync.schedule(context, store.snapshot())
        // 2.0.9: VPN start-on-boot removed (VpnScheduler restores its own alarms).
    }
}
