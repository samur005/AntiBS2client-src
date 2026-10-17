package net.antibs.client.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.antibs.client.data.EngineKind
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.XrayConfigBuilder
import net.antibs.client.desktop.CoreRunner
import net.antibs.client.desktop.Paths
import net.antibs.client.engine.AppState
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.VpnState
import net.antibs.client.engine.VpnStatus
import net.antibs.client.engine.XrayCore
import net.antibs.client.ui.UiLogic
import java.io.File

/**
 * Desktop counterpart of the Android VpnService: same entry points (connect / disconnect with a Context), the same
 * option assembly as the Android service, then [CoreRunner] starts xray.exe (+ sing-box helper) and the chosen OS
 * integration: «vpn» = sing-box TUN (admin), «proxy» = WinINET system proxy.
 */
object AntiBSVpnService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val runner = CoreRunner(scope)
    private var job: Job? = null

    fun connect(ctx: Context) {
        job?.cancel()
        job = scope.launch { connectNow(ctx) }
    }

    fun disconnect(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        job?.cancel()
        job = scope.launch { runner.stop(); VpnState.log("отключено") }
    }

    /** Called on app exit: stop engines and restore the system proxy synchronously. */
    fun shutdown() { runner.stopNow() }

    private suspend fun connectNow(ctx: Context) {
        XrayCore.installCertPins()
        val switching = runner.running
        val store = AppStore(ctx)
        val snap = store.snapshot()
        val entry = store.selectedServer()
        if (entry == null) { VpnState.set(VpnStatus(ConnState.ERROR, message = "Сервер не выбран")); return }
        val a = snap.adv
        val protocol = UiLogic.protocolGroup(entry)
        VpnState.set(VpnStatus(ConnState.CONNECTING, serverName = entry.name, protocol = protocol, message = if (switching) "Переключение…" else "Подключение…"))
        VpnState.log("подключение к «${entry.name}» ($protocol), режим=${modeLabel(a.mode)}")
        try {
            if (entry.engine == EngineKind.QWDTT) error("qWDTT (VK TURN) в версии для Windows пока не поддерживается")
            val reason = entry.xray?.unsupportedReason
            if (!reason.isNullOrBlank()) error(reason)
            if (entry.xray != null && !ProtocolSupport.isRunnable(entry.xray!!.protocol))
                error(ProtocolSupport.unsupportedReason(entry.xray!!.protocol) ?: "Протокол ${entry.xray!!.protocol} не поддерживается")
            val apiPort = net.antibs.client.engine.XrayApi.freePort()
            val o = opts(ctx, snap).copy(apiPort = apiPort)
            val plan0 = XrayConfigBuilder.plan(entry, store.servers(), o)
            VpnState.log("движок: ${plan0.engineLabel}")
            val plan = plan0.copy(xray = withApi(plan0.xray, apiPort))
            runner.start(entry, snap, if (a.mode == "proxy") "proxy" else "tun", protocol, apiPort, plan)
            VpnState.log("подключено: ${entry.name}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            VpnState.log("ошибка: ${e.message}")
            VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = e.message ?: "ошибка"))
            runner.stopNow()
        }
    }

    fun modeLabel(m: String) = if (m == "proxy") "системный прокси" else "VPN (TUN)"

    /** Xray API with StatsService for the live traffic counters (full panel configs may already carry one). */
    internal fun withApi(cfg: String, port: Int): String {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(cfg).jsonObject }.getOrNull() ?: return cfg
        if (o.containsKey("api")) return cfg
        val api = buildJsonObject {
            put("tag", "api"); put("listen", "127.0.0.1:$port")
            putJsonArray("services") {
                add("StatsService"); add("RoutingService")
                // ObservatoryService needs an observatory in the config, otherwise Xray refuses to start
                if (o.containsKey("observatory") || o.containsKey("burstObservatory")) add("ObservatoryService")
            }
        }
        return JsonObject(o + ("api" to api)).toString()
    }

    private fun opts(ctx: Context, s: AppState): XrayConfigBuilder.Options {
        val a = s.adv
        val routing = s.adv.activeRoutingId?.let { id -> s.adv.routingProfiles.firstOrNull { it.id == id } }?.let { r ->
            fun ok(f: String) = f.isNotBlank() && File(ctx.filesDir, f).length() > 0
            r.copy(geoipFile = r.geoipFile.takeIf { ok(it) }.orEmpty(), geositeFile = r.geositeFile.takeIf { ok(it) }.orEmpty())
        }
        XrayCore.prepareAssets(ctx)
        val geoBundled = net.antibs.client.engine.GeoFiles.bundledTags(ctx)
        if (routing != null && geoBundled != null) {
            val dropped = routing.rules.mapNotNull { r -> r.value.takeIf { net.antibs.client.data.GeoDat.resolve(it, routing, geoBundled) == null } }
            if (dropped.isNotEmpty()) VpnState.log("маршрутизация «${routing.name}»: нет в geo-файлах, пропущено: ${dropped.joinToString()}")
        }
        if (routing != null && routing.rules.any { it.kind == "process" })
            VpnState.log("маршрутизация: правила по приложениям в версии для Windows пока не поддерживаются — пропущены")
        return XrayConfigBuilder.Options(
            socksPort = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT,
            bypassLan = s.bypassLan,
            mux = s.mux,
            fragmentation = s.fragmentation,
            ipType = s.ipType,
            allowLan = s.allowLan,
            sniffing = a.sniffing,
            logLevel = a.logLevel.takeIf { it in setOf("none", "error", "warning", "info", "debug") } ?: "warning",
            adv = a.copy(httpInbound = true),
            xrayTun = false,
            logDir = Paths.logs.absolutePath,
            routing = routing,
            geoBundled = geoBundled,
            autoExclude = s.autoExcludeIds.toSet(),
            adBlock = net.antibs.client.engine.BlockFiles.prepare(ctx, a) { VpnState.log(it) },
            geoExtra = net.antibs.client.engine.BlockFiles.geoExtra(ctx),
        )
    }
}
