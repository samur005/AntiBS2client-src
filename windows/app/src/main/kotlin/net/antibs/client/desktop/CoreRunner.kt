package net.antibs.client.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.XrayConfigBuilder
import net.antibs.client.engine.AppState
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.VpnState
import net.antibs.client.engine.VpnStatus
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Engine process helpers shared by the tunnel and the ping/URL test. */
object Engines {
    fun file(name: String): File = File(Paths.engines, Platform.current.exe(name))
    fun available(name: String) = file(name).let { it.isFile && (it.canExecute() || Platform.current.id == "windows") }

    fun start(name: String, args: List<String>, log: File?, env: Map<String, String> = emptyMap()): Process {
        val f = file(name)
        check(f.isFile) { "не найден ${f.name} (папка engines)" }
        val pb = ProcessBuilder(listOf(f.path) + args).directory(Paths.engines)
        pb.environment()["XRAY_LOCATION_ASSET"] = Paths.home.path
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        if (log != null) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log)) else pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        return pb.start()
    }

    /** Waits until something listens on 127.0.0.1:[port] (or the process dies). */
    suspend fun waitPort(port: Int, p: Process, timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive) return@withContext false
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }; return@withContext true } catch (_: Exception) {}
            delay(60)
        }
        false
    }

    fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}

/**
 * Runs the tunnel: Xray (config from the shared [XrayConfigBuilder.plan]) + an optional sing-box helper for
 * hysteria2/TUIC/… slots, and then either the sing-box TUN («VPN», admin) or the WinINET system proxy («Прокси»).
 */
class CoreRunner(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val procs = mutableListOf<Process>()
    @Volatile private var proxySet = false
    @Volatile var mode: String = ""
        private set

    val running: Boolean get() = procs.isNotEmpty() && procs.all { it.isAlive }

    /** Starts everything for [entry]. Throws with a user-facing (Russian) message on failure. */
    suspend fun start(entry: ServerEntry, st: AppState, vpnMode: String, protocol: String, apiPort: Int, plan: XrayConfigBuilder.Plan) = withContext(Dispatchers.IO) {
        stopNow()
        try {
            if (plan.mieruInstances.isNotEmpty() || plan.mihomo != null || plan.olcInstances.isNotEmpty() || plan.vpnInstances.isNotEmpty())
                error("${plan.engineLabel}: этот протокол в версии для Windows пока не поддерживается")
            val run = Paths.run
            val logs = Paths.logs
            plan.singBox?.let { sb ->
                val f = File(run, "helper.json"); f.writeText(sb)
                val p = Engines.start("sing-box", listOf("run", "-c", f.path, "--disable-color"), File(logs, "sing-box-helper.log"))
                procs += p
            }
            val xf = File(run, "xray.json"); xf.writeText(plan.xray)
            val xp = Engines.start("xray", listOf("run", "-c", xf.path), File(logs, "xray-stdout.log"))
            procs += xp
            val socks = st.adv.socksPort
            if (!Engines.waitPort(socks, xp, 8000)) error("Xray не запустился — подробности в «Логи»\n" + tail(File(logs, "xray-stdout.log")))
            when (vpnMode) {
                "proxy" -> {
                    Platform.current.systemProxy.enable("127.0.0.1", st.adv.httpPort, socks).getOrThrow()
                    proxySet = Platform.current.systemProxy.supported
                }
                "tun" -> {
                    if (!Platform.current.isElevated()) error("Для режима VPN (TUN) нужны права администратора")
                    val cfg = TunConfig.build(socks, st.adv.tunMtu, st.bypassLan, logFile = File(logs, "sing-box-tun.log").path,
                        ipv6 = st.ipType != "ipv4", excludeCidrs = st.adv.excludedRoutes)
                    val f = File(run, "tun.json"); f.writeText(net.antibs.client.data.XrayConfigBuilder.toJsonString(cfg, pretty = true))
                    val tp = Engines.start("sing-box", listOf("run", "-c", f.path, "--disable-color"), File(logs, "sing-box-tun.out.log"))
                    procs += tp
                    delay(1500)
                    if (!tp.isAlive) error("sing-box TUN не запустился:\n" + tail(File(logs, "sing-box-tun.out.log")))
                }
                else -> {} // «только локальный прокси»: SOCKS/HTTP on 127.0.0.1 without touching the OS
            }
            mode = vpnMode
            VpnState.set(VpnStatus(ConnState.CONNECTED, entry.name, protocol, since = System.currentTimeMillis(),
                message = plan.engineLabel + when (vpnMode) { "tun" -> " · TUN"; "proxy" -> " · системный прокси"; else -> " · локальный прокси" }))
            watch(entry)
            if (apiPort > 0) traffic(apiPort)
        } catch (e: Throwable) {
            stopNow()
            VpnState.set(VpnStatus(ConnState.ERROR, entry.name, message = e.message ?: e.javaClass.simpleName))
            VpnState.log("connect failed: ${e.message}")
            throw e
        }
    }

    private fun watch(entry: ServerEntry) = scope.launch {
        while (procs.isNotEmpty()) {
            delay(1000)
            val dead = procs.firstOrNull { !it.isAlive } ?: continue
            VpnState.log("engine exited with ${dead.exitValue()}")
            stopNow()
            VpnState.set(VpnStatus(ConnState.ERROR, entry.name, message = "Ядро неожиданно завершилось — см. «Логи»"))
            break
        }
    }

    /** Live up/down rates from Xray's StatsService (outbound counters, reset on read) — like the Android service. */
    private fun traffic(apiPort: Int) = scope.launch {
        var last = System.nanoTime()
        var upT = 0L; var downT = 0L; var dUpT = 0L; var dDownT = 0L
        while (procs.isNotEmpty()) {
            delay(1000)
            val d = net.antibs.client.engine.XrayStats.delta(apiPort) ?: continue
            val now = System.nanoTime(); val dt = ((now - last) / 1e9).coerceAtLeast(0.2); last = now
            upT += d.proxyUp; downT += d.proxyDown; dUpT += d.directUp; dDownT += d.directDown
            VpnState.update { it.copy(upBytes = upT, downBytes = downT, directUpBytes = dUpT, directDownBytes = dDownT,
                upSpeed = (d.proxyUp / dt).toLong(), downSpeed = (d.proxyDown / dt).toLong(),
                directUpSpeed = (d.directUp / dt).toLong(), directDownSpeed = (d.directDown / dt).toLong()) }
        }
    }

    private fun tail(f: File, n: Int = 6) = runCatching { f.readLines().takeLast(n).joinToString("\n") }.getOrDefault("")

    fun stopNow() {
        if (proxySet) { Platform.current.systemProxy.restore(); proxySet = false }
        procs.reversed().forEach { p -> runCatching { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } }
        procs.clear()
        mode = ""
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        VpnState.update { it.copy(state = ConnState.DISCONNECTING) }
        stopNow()
        VpnState.set(VpnStatus(ConnState.DISCONNECTED))
    }
}
