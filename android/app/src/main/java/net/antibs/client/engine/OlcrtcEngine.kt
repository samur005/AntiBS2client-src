package net.antibs.client.engine

import android.annotation.SuppressLint
import android.content.Context
import net.antibs.client.data.Olcrtc
import net.antibs.client.data.XrayProfile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 1.0.41: olcRTC client (libolcrtc.so run <cfg.json>). Joins the WebRTC room, opens a loopback SOCKS5 that Xray dials.
 * Our app is excluded from its own VPN, so the child's WebRTC/ICE sockets go straight to the network.
 */
class OlcrtcEngine(private val ctx: Context, private val profile: XrayProfile, private val port: Int, private val dns: String) {
    private var process: Process? = null
    @Volatile private var stopped = false
    @Volatile private var ready = false
    @Volatile var onExit: ((String) -> Unit)? = null
    private val lastLines = ArrayDeque<String>()

    fun start(timeoutMs: Long = 45_000) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libolcrtc.so")
        if (!bin.exists()) throw IllegalStateException("olcRTC не найден: ${bin.absolutePath}")
        Olcrtc.validate(profile)?.let { throw IllegalStateException("olcRTC: $it") }
        val cfg = File(ctx.filesDir, "olcrtc-$port.json")
        cfg.writeText(Olcrtc.clientJson(profile, port, dns, deviceId(ctx)))
        VpnState.log("[olcRTC] запуск: ${Olcrtc.PROVIDER_LABELS[profile.method] ?: profile.method} / ${profile.network}, SOCKS 127.0.0.1:$port")
        val pb = ProcessBuilder(bin.absolutePath, "run", cfg.absolutePath).directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        val p = pb.start()
        process = p
        thread(name = "olcrtc-log", isDaemon = true) { readLoop(p) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ready) { VpnState.log("[olcRTC] канал в комнате установлен"); return }
            if (!p.isAlive) { cfg.delete(); throw IllegalStateException("olcRTC: ${lastError() ?: "процесс завершился (код ${p.exitValue()})"}") }
            Thread.sleep(100)
        }
        stop()
        throw IllegalStateException("olcRTC не подключился за ${timeoutMs / 1000} с" + (lastError()?.let { ": $it" } ?: ""))
    }

    private fun lastError(): String? = synchronized(lastLines) {
        lastLines.lastOrNull { it.startsWith("ERR") || it.contains("error", true) || it.contains("failed", true) }?.removePrefix("ERR ")?.take(240)
    }

    private fun readLoop(p: Process) {
        try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "").trim()
                if (line.isEmpty()) return@forEachLine
                if (line.startsWith("READY")) ready = true
                synchronized(lastLines) { lastLines.addLast(line); while (lastLines.size > 20) lastLines.removeFirst() }
                // room keys / tokens never reach the log
                VpnState.log("[olcRTC] ${redact(line).take(300)}")
            }
        } catch (_: Exception) {}
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        File(ctx.filesDir, "olcrtc-$port.json").delete()
        if (!stopped) onExit?.invoke("olcRTC завершился (код $code)" + (lastError()?.let { ": $it" } ?: ""))
    }

    private fun redact(s: String): String {
        var out = s
        profile.password?.takeIf { it.length >= 8 }?.let { out = out.replace(it, "•••") }
        profile.username?.takeIf { it.length >= 8 }?.let { out = out.replace(it, "•••") }
        return out
    }

    fun stop() {
        stopped = true
        val p = process ?: return
        process = null
        thread(isDaemon = true) {
            try {
                try { p.outputStream.apply { write("STOP\n".toByteArray()); flush() } } catch (_: Exception) {}
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) { p.destroy(); if (!p.waitFor(1000, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
            } catch (_: Exception) {}
            File(ctx.filesDir, "olcrtc-$port.json").delete()
        }
    }

    companion object {
        private val live = java.util.concurrent.CopyOnWriteArrayList<OlcrtcEngine>()
        fun register(e: OlcrtcEngine) { live += e }
        fun stopAll() { live.forEach { it.stop() }; live.clear() }

        /** qWDTT «DNS для Go-клиента» setting → plain DNS host:port. */
        fun dnsFor(key: String): String = when (key) { "cloudflare" -> "1.1.1.1:53"; "google" -> "8.8.8.8:53"; else -> "77.88.8.8:53" }

        @SuppressLint("HardwareIds")
        fun deviceId(ctx: Context): String = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Exception) { "" }

        private val pingPort = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Real ping: a throw-away client joins the room, then HTTP GET [url] through its SOCKS (best of 3).
         * -1 = failed, -2 = not applicable. Blocking.
         */
        fun ping(ctx: Context, p: XrayProfile, dns: String, url: String, timeoutMs: Int): Long {
            if (Olcrtc.validate(p) != null) return -2L
            val bin = File(ctx.applicationInfo.nativeLibraryDir, "libolcrtc.so")
            if (!bin.exists()) return -2L
            val port = Olcrtc.BASE_PORT + 50 + (pingPort.getAndIncrement() and 31)
            val cfg = File(ctx.cacheDir, "olcrtc-ping-$port.json")
            cfg.writeText(Olcrtc.clientJson(p, port, dns, deviceId(ctx)))
            val t = timeoutMs.coerceIn(10_000, 30_000)
            return try {
                val pb = ProcessBuilder(bin.absolutePath, "ping", cfg.absolutePath, url, t.toString()).directory(ctx.cacheDir).redirectErrorStream(true)
                pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
                val proc = pb.start()
                var res = -1L
                val reader = thread(isDaemon = true) {
                    try {
                        proc.inputStream.bufferedReader().forEachLine { l ->
                            val s = l.trim()
                            if (s.startsWith("PING ")) res = s.removePrefix("PING ").trim().toLongOrNull()?.coerceAtLeast(1) ?: -1L
                            else if (s.startsWith("ERR ")) VpnState.log("[olcRTC] пинг «${p.remarks}»: ${s.removePrefix("ERR ").take(200)}")
                        }
                    } catch (_: Exception) {}
                }
                if (!proc.waitFor(t + 8_000L, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
                reader.join(500)
                res
            } catch (e: Exception) { VpnState.log("[olcRTC] пинг: ${e.message}"); -1L } finally { cfg.delete() }
        }
    }
}
