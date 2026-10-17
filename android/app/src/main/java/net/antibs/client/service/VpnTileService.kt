package net.antibs.client.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.antibs.client.MainActivity
import net.antibs.client.R
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.VpnState

/** 1.0.40: Quick Settings tile «AntiBS» — connect / disconnect from the notification shade. */
class VpnTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        render()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch { VpnState.status.collectLatest { render() } }
        }
    }

    override fun onStopListening() {
        scope?.cancel(); scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnState.status.value.state
        if (state == ConnState.CONNECTED || state == ConnState.CONNECTING) { AntiBSVpnService.disconnect(this); return }
        if (VpnService.prepare(this) != null || AppStore(this).selectedServer() == null) { openApp(); return }
        try { AntiBSVpnService.connect(this) } catch (_: Exception) { openApp() }
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    private fun render() {
        val t = qsTile ?: return
        val st = VpnState.status.value
        val on = st.state == ConnState.CONNECTED || st.state == ConnState.CONNECTING || st.state == ConnState.DISCONNECTING
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat_vpn)
        t.label = "AntiBS"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = when (st.state) {
            ConnState.CONNECTED -> st.serverName.ifBlank { "Подключено" }
            ConnState.CONNECTING -> "Подключение…"
            ConnState.DISCONNECTING -> "Отключение…"
            else -> "Отключено"
        }
        t.updateTile()
    }

    companion object {
        fun refresh(ctx: Context) {
            try { requestListeningState(ctx, ComponentName(ctx, VpnTileService::class.java)) } catch (_: Throwable) { }
        }
    }
}
