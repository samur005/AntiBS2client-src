package net.antibs.client.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.MutableStateFlow
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.VpnState
import net.antibs.client.service.AntiBSVpnService
import net.antibs.client.ui.AppRoot
import net.antibs.client.ui.AppViewModel
import net.antibs.client.ui.BackDispatcher
import net.antibs.client.ui.Desk
import net.antibs.client.ui.UiMessages
import net.antibs.client.ui.UiPrefs

/** Second launch → the running instance shows its window (args forwarded). */
private object SingleInstance {
    private const val PORT = 47611
    val requests = MutableStateFlow(0)
    @Volatile var lastArgs: List<String> = emptyList()

    fun claim(args: Array<String>): Boolean = try {
        val ss = java.net.ServerSocket(PORT, 4, java.net.InetAddress.getByName("127.0.0.1"))
        Thread({
            while (true) runCatching {
                ss.accept().use { s ->
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    if (line.startsWith("ANTIBS2")) { lastArgs = line.removePrefix("ANTIBS2").trim().split(' ').filter { it.isNotBlank() }; requests.value++ }
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
        true
    } catch (_: Exception) {
        runCatching { java.net.Socket("127.0.0.1", PORT).use { it.getOutputStream().write(("ANTIBS2 " + args.joinToString(" ") + "\n").toByteArray()) } }
        false
    }
}

/** Engines left behind by a crash (same engines folder) and a system proxy we set are cleaned up on start. */
private fun cleanupLeftovers() {
    Platform.current.systemProxy.restore()
    val dir = Paths.engines.absolutePath.lowercase()
    runCatching {
        ProcessHandle.allProcesses().filter { p -> p.info().command().map { it.lowercase().startsWith(dir) }.orElse(false) }
            .forEach { it.destroyForcibly() }
    }
}

fun main(args: Array<String>) {
    if (!SingleInstance.claim(args)) return
    cleanupLeftovers()
    val app = android.app.Application.instance
    Runtime.getRuntime().addShutdownHook(Thread { AntiBSVpnService.shutdown() })
    net.antibs.client.engine.SubscriptionSync.reconnector = { AntiBSVpnService.connect(app) }
    FilePrefs.log("start ${net.antibs.client.BuildConfig.VERSION_NAME} on ${System.getProperty("os.name")} ${System.getProperty("os.version")}, admin=${Platform.current.isElevated()}")
    val startHidden = "--minimized" in args
    val connectNow = "--connect" in args

    application {
        val vm = remember { AppViewModel(app) }
        var visible by remember { mutableStateOf(!startHidden) }
        val reopen by SingleInstance.requests.collectAsState()
        LaunchedEffect(reopen) {
            if (reopen > 0) { visible = true; Desk.window?.toFront(); if ("--connect" in SingleInstance.lastArgs) AntiBSVpnService.connect(app) }
        }
        LaunchedEffect(Unit) { if (connectNow) AntiBSVpnService.connect(app) }
        fun quit() { AntiBSVpnService.shutdown(); exitApplication() }
        Desk.exitHook = { quit() }
        val vpn by VpnState.status.collectAsState()
        val on = vpn.state == ConnState.CONNECTED || vpn.state == ConnState.CONNECTING
        Tray(
            icon = painterResource("brand_icon.png"),
            tooltip = "AntiBS2client — " + when (vpn.state) { ConnState.CONNECTED -> "подключено: ${vpn.serverName}"; ConnState.CONNECTING -> "подключение…"; else -> "отключено" },
            onAction = { visible = true; Desk.window?.toFront() },
            menu = {
                Item("Открыть AntiBS2client", onClick = { visible = true; Desk.window?.toFront() })
                Item(if (on) "Отключить" else "Подключить", onClick = { if (on) AntiBSVpnService.disconnect(app) else AntiBSVpnService.connect(app) })
                Separator()
                Item("Выход", onClick = { quit() })
            },
        )
        val state = rememberWindowState(size = DpSize(UiPrefs.getInt("win_w", 440).dp, UiPrefs.getInt("win_h", 860).dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = { if (UiPrefs.getBoolean("tray_on_close", true)) visible = false else quit() },
            visible = visible,
            state = state,
            title = "AntiBS2client",
            icon = painterResource("brand_icon.png"),
            onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) BackDispatcher.back() else false },
        ) {
            LaunchedEffect(Unit) { Desk.window = window; window.minimumSize = java.awt.Dimension(380, 620) }
            LaunchedEffect(state.size) { UiPrefs.edit().putInt("win_w", state.size.width.value.toInt()).putInt("win_h", state.size.height.value.toInt()).apply() }
            val snackbar = remember { SnackbarHostState() }
            val toast by vm.toast.collectAsState()
            LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.consumeToast() } }
            LaunchedEffect(Unit) { UiMessages.flow.collect { snackbar.showSnackbar(it) } }
            LaunchedEffect(Unit) { vm.autoCheckUpdates(); vm.autoRefreshMuslimHolidays() }
            val undo by vm.undo.collectAsState()
            LaunchedEffect(undo) {
                val u = undo ?: return@LaunchedEffect
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar("Хост удалён", actionLabel = "Отменить", duration = androidx.compose.material3.SnackbarDuration.Long)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undoDelete(u)
                vm.consumeUndo()
            }
            Box {
                AppRoot(vm)
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
        }
    }
}
