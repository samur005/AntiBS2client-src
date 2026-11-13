package net.antibs.client.engine

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Third helper engine: libmhx.so (native/mihomo-lite, mihomo v1.19.34 outbounds + quic-go HTTP/3 CONNECT).
 * Serves ShadowsocksR, Snell v1–v3 + v6, TrustTunnel, ShadowQUIC and HTTP/3 proxies on loopback SOCKS slots (11100+);
 * Xray dials them through plain socks outbounds, exactly like sing-box / mieru.
 */
class MihomoEngine(private val ctx: Context, private val config: String, private val ports: List<Int>) {
    private var process: Process? = null
    @Volatile private var stopped = false
    @Volatile var onExit: ((String) -> Unit)? = null
    private val lastLines = ArrayDeque<String>()

    fun binary(): File = File(ctx.applicationInfo.nativeLibraryDir, "libmhx.so")

    /** Starts sing-box and blocks until every SOCKS inbound accepts connections (or throws). */
    fun start(timeoutMs: Long = 10_000) {
        val bin = binary()
        if (!bin.exists()) throw IllegalStateException("mihomo (libmhx) не найден: ${bin.absolutePath}")
        val cfg = File(ctx.filesDir, "mihomo.json")
        cfg.writeText(config)
        VpnState.log("[mihomo] запуск, слотов: ${ports.size} (${ports.joinToString()})")
        val pb = ProcessBuilder(bin.absolutePath, "run", cfg.absolutePath, "--watch-stdin")
            .directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        val p = pb.start()
        process = p
        thread(name = "mihomo-log", isDaemon = true) { readLoop(p) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) throw IllegalStateException("mihomo: ${lastError() ?: "процесс завершился (код ${p.exitValue()})"}")
            if (ports.all { portOpen(it) }) {
                VpnState.log("[mihomo] готов")
                return
            }
            Thread.sleep(80)
        }
        stop()
        throw IllegalStateException("mihomo не открыл SOCKS-порт за ${timeoutMs / 1000} с" + (lastError()?.let { ": $it" } ?: ""))
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
                if (line.isEmpty() || line == "MHX_STARTED") return@forEachLine
                synchronized(lastLines) { lastLines.addLast(line); while (lastLines.size > 20) lastLines.removeFirst() }
                VpnState.log("[mihomo] ${line.take(300)}")
            }
        } catch (_: Exception) {}
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        if (!stopped) onExit?.invoke("mihomo завершился (код $code)" + (lastError()?.let { ": $it" } ?: ""))
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
        @Volatile var current: MihomoEngine? = null
    }
}
