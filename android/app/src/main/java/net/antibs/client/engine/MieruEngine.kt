package net.antibs.client.engine

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Bundled mieru 1.15.1 client (native/mieru-lite → libmieru.so). Foreground `run` with
 * MIERU_CONFIG_JSON_FILE and rpcPort=0 (mobile mode). Xray dials its loopback SOCKS.
 */
class MieruEngine(private val ctx: Context, private val configJson: String, private val socksPort: Int) {
    private var process: Process? = null
    @Volatile private var stopped = false
    @Volatile var onExit: ((String) -> Unit)? = null
    private val lastLines = ArrayDeque<String>()

    fun start(timeoutMs: Long = 12_000) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libmieru.so")
        if (!bin.exists()) throw IllegalStateException("mieru не найден: ${bin.absolutePath}")
        val cfg = File(ctx.filesDir, "mieru-client.json")
        cfg.writeText(configJson)
        VpnState.log("[mieru] запуск SOCKS 127.0.0.1:$socksPort")
        val pb = ProcessBuilder(bin.absolutePath, "run")
            .directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        pb.environment()["MIERU_CONFIG_JSON_FILE"] = cfg.absolutePath
        val p = pb.start()
        process = p
        thread(name = "mieru-log", isDaemon = true) { readLoop(p) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) throw IllegalStateException("mieru: ${lastError() ?: "процесс завершился (код ${p.exitValue()})"}")
            if (portOpen(socksPort)) { VpnState.log("[mieru] готов"); return }
            Thread.sleep(80)
        }
        stop()
        throw IllegalStateException("mieru не открыл SOCKS за ${timeoutMs / 1000} с" + (lastError()?.let { ": $it" } ?: ""))
    }

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150); true }
    } catch (_: Exception) { false }

    private fun lastError(): String? = synchronized(lastLines) {
        lastLines.lastOrNull { it.contains("failed", true) || it.contains("FATAL", true) || it.contains("error", true) }?.take(240)
    }

    private fun readLoop(p: Process) {
        try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                synchronized(lastLines) { lastLines.addLast(line); while (lastLines.size > 20) lastLines.removeFirst() }
                VpnState.log("[mieru] ${line.take(300)}")
            }
        } catch (_: Exception) {}
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        if (!stopped) onExit?.invoke("mieru завершился (код $code)" + (lastError()?.let { ": $it" } ?: ""))
    }

    fun stop() {
        stopped = true
        val p = process ?: return
        process = null
        thread(isDaemon = true) {
            try {
                p.destroy()
                if (!p.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
            } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile var current: MieruEngine? = null
        private val live = java.util.concurrent.CopyOnWriteArrayList<MieruEngine>()
        fun register(e: MieruEngine) { live += e; current = e }
        fun stopAll() {
            live.toList().forEach { it.stop() }
            live.clear()
            current = null
        }
    }
}
