package net.antibs.client.engine

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Second engine: bundled sing-box 1.14.2 (native/singbox-lite → libsbx.so, built with
 * with_quic + with_utls + with_naive_outbound). Runs as a child process with loopback SOCKS inbounds only;
 * Xray reaches it through a socks outbound, so the TUN path stays TUN → hev-socks5-tunnel → Xray.
 * The app UID is excluded from the VPN, so sing-box's own sockets go straight to the physical network.
 */
class SingBoxEngine(private val ctx: Context, private val config: String, private val ports: List<Int>) {
    private var process: Process? = null
    @Volatile private var stopped = false
    @Volatile var onExit: ((String) -> Unit)? = null
    private val lastLines = ArrayDeque<String>()

    fun binary(): File = File(ctx.applicationInfo.nativeLibraryDir, "libsbx.so")

    /** Starts sing-box and blocks until every SOCKS inbound accepts connections (or throws). */
    fun start(timeoutMs: Long = 10_000) {
        val bin = binary()
        if (!bin.exists()) throw IllegalStateException("sing-box не найден: ${bin.absolutePath}")
        val cfg = File(ctx.filesDir, "sing-box.json")
        cfg.writeText(config)
        VpnState.log("[sing-box] запуск, слотов: ${ports.size} (${ports.joinToString()})")
        val pb = ProcessBuilder(bin.absolutePath, "run", cfg.absolutePath, "--watch-stdin")
            .directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        val p = pb.start()
        process = p
        thread(name = "sing-box-log", isDaemon = true) { readLoop(p) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) throw IllegalStateException("sing-box: ${lastError() ?: "процесс завершился (код ${p.exitValue()})"}")
            if (ports.all { portOpen(it) }) {
                VpnState.log("[sing-box] готов")
                return
            }
            Thread.sleep(80)
        }
        stop()
        throw IllegalStateException("sing-box не открыл SOCKS-порт за ${timeoutMs / 1000} с" + (lastError()?.let { ": $it" } ?: ""))
    }

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150); true }
    } catch (_: Exception) { false }

    private fun lastError(): String? = synchronized(lastLines) {
        lastLines.lastOrNull { it.contains("FATAL") || it.contains("ERROR") || it.contains("error") }
            ?.substringAfter("FATAL ")?.take(240)
    }

    private fun readLoop(p: Process) {
        try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
                if (line.isEmpty() || line == "SBX_STARTED") return@forEachLine
                synchronized(lastLines) { lastLines.addLast(line); while (lastLines.size > 20) lastLines.removeFirst() }
                VpnState.log("[sing-box] ${line.take(300)}")
            }
        } catch (_: Exception) {}
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        if (!stopped) onExit?.invoke("sing-box завершился (код $code)" + (lastError()?.let { ": $it" } ?: ""))
    }

    fun stop() {
        stopped = true
        val p = process ?: return
        process = null
        try { p.outputStream.close() } catch (_: Exception) {} // --watch-stdin → graceful close
        thread(isDaemon = true) {
            try {
                if (!p.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroy()
                    if (!p.waitFor(700, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile var current: SingBoxEngine? = null

        /** DNS of the physical network (sing-box is outside the VPN); falls back to 1.1.1.1. */
        /** 2.0.43: all DNS servers of the active non-VPN network (empty when unknown). */
        fun underlyingDnsServers(ctx: Context): List<String> = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val nets = cm.allNetworks.filter { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            val active = cm.activeNetwork?.takeIf { it in nets } ?: nets.firstOrNull()
            active?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty().mapNotNull { it.hostAddress }
        } catch (_: Exception) { emptyList() }

        fun underlyingDns(ctx: Context): String = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val nets = cm.allNetworks.filter { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            val active = cm.activeNetwork?.takeIf { it in nets } ?: nets.firstOrNull()
            active?.let { cm.getLinkProperties(it)?.dnsServers }
                ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "1.1.1.1"
        } catch (_: Exception) { "1.1.1.1" }
    }
}
