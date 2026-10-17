package net.antibs.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.VpnState

/** Re-schedules subscription auto-update; starts the VPN after boot only when the user turned autostart on and already granted the VPN consent. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = AppStore(context)
        // 1.0.25: make sure «Автообновление подписок» is scheduled (WorkManager also restores it itself).
        net.antibs.client.engine.SubscriptionSync.schedule(context, store.snapshot())
        if (!store.snapshot().autostart) return
        if (VpnService.prepare(context) != null) {
            VpnState.log("автозапуск: нет разрешения VPN")
            return
        }
        try {
            AntiBSVpnService.connect(context)
        } catch (e: Exception) {
            VpnState.log("автозапуск: ${e.message}")
        }
    }
}
