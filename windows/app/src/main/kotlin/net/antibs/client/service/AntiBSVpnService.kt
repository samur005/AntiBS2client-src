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

    @Volatile private var watchdog: net.antibs.client.engine.ConnectWatchdog? = null

    fun connect(ctx: Context) {
        watchdog?.cancel()
        job?.cancel()
        job = scope.launch { connectNow(ctx) }
    }

    fun disconnect(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        watchdog?.cancel()
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
        // 1.0.53: not connected within 5 s (e.g. no network) → abort: stop cores, restore the proxy, «Ошибка подключения»
        val attempt = kotlin.coroutines.coroutineContext[Job]
        val wd = net.antibs.client.engine.ConnectWatchdog {
            attempt?.cancel()
            VpnState.log("нет подключения за ${net.antibs.client.engine.ConnectWatchdog.TIMEOUT_MS / 1000} с — попытка прервана")
            runner.stopNow()
            VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = net.antibs.client.engine.ConnectWatchdog.MESSAGE))
        }
        watchdog = wd.start()
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
            val plan = plan0.copy(xray = withAdCounter(ctx, withApi(plan0.xray, apiPort)))
            runner.start(entry, snap, if (a.mode == "proxy") "proxy" else "tun", protocol, apiPort, plan)
            if (!wd.established()) {
                // the timeout (or a cancel) already won: tear the late connection down again
                runner.stopNow()
                if (wd.timedOut) VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = net.antibs.client.engine.ConnectWatchdog.MESSAGE))
                return
            }
            VpnState.log("подключено: ${entry.name}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (wd.timedOut) VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = net.antibs.client.engine.ConnectWatchdog.MESSAGE))
            else wd.cancel()
            throw e
        } catch (e: Throwable) {
            wd.cancel()
            if (wd.timedOut) return
            VpnState.log("ошибка: ${e.message}")
            VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = e.message ?: "ошибка"))
            runner.stopNow()
        }
    }

    // 2.0.20 (ABS AdBlockCounter, same as Android 2.0.15): access log of «block-ads» hits, drained every 2 s
    @Volatile private var adLog: File? = null
    @Volatile private var adLogOwn = true
    private var adOff = 0L
    private var adJob: Job? = null

    private fun withAdCounter(ctx: Context, config: String): String {
        adLog = null
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(config) as? JsonObject ?: return config
            if (!net.antibs.client.data.AdBlockCounter.configCounts(obj)) return config
            val own = File(Paths.logs.apply { mkdirs() }, net.antibs.client.data.AdBlockCounter.LOG_NAME)
            val (out, path) = net.antibs.client.data.AdBlockCounter.withAccessLog(obj, own.absolutePath)
            if (path == null) return config
            val counts = File(ctx.filesDir, net.antibs.client.data.AdBlockCounter.COUNTS_NAME)
            net.antibs.client.data.AdBlockCounter.markMeasured(counts)
            val f = File(path)
            adLogOwn = f.absolutePath == own.absolutePath
            if (adLogOwn) try { own.writeText("") } catch (_: Throwable) {}
            adOff = if (adLogOwn) 0L else f.length()
            adLog = f
            adJob?.cancel()
            adJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    val lf = adLog ?: break
                    try {
                        val (off, n) = net.antibs.client.data.AdBlockCounter.drain(lf, adOff, truncate = adLogOwn)
                        adOff = off
                        if (n > 0) net.antibs.client.data.AdBlockCounter.add(counts, n)
                    } catch (_: Throwable) {}
                }
            }
            out.toString()
        } catch (_: Throwable) { config }
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
            geoExtra = net.antibs.client.engine.BlockFiles.geoExtra(ctx),
        )
    }
}
