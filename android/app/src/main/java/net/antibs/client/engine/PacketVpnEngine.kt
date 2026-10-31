package net.antibs.client.engine

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import kittoku.osc.SstpSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import net.antibs.client.data.PacketVpnConfig
import net.antibs.client.data.XrayProfile
import net.antibs.zt.ZtSession
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 2.0.1: SSTP (Open-SSTP-Client) and ZeroTier run in-process; their IP packets go to libvpnstack.so «pkt»
 * (gVisor netstack) that exposes loopback SOCKS [port] for Xray — so routing, per-app rules and the
 * live speed graph (Xray outbound stats) work exactly like OpenVPN / OpenConnect.
 */
class PacketVpnEngine(private val ctx: Context, private val profile: XrayProfile, private val port: Int) {
    private var process: Process? = null
    private var socket: LocalSocket? = null
    private var sstp: SstpSession? = null
    private var zt: ZtSession? = null
    @Volatile private var stopped = false
    @Volatile private var ready = false
    @Volatile private var failure: String? = null
    @Volatile var onExit: ((String) -> Unit)? = null
    private val isSstp = profile.protocol == PacketVpnConfig.SSTP
    private val label = if (isSstp) "SSTP" else "ZeroTier"
    private val lastLines = ArrayDeque<String>()

    private fun protectSocket(s: Socket): Boolean = (ctx as? VpnService)?.protect(s) ?: true
    private fun protectUdp(s: java.net.DatagramSocket): Boolean = (ctx as? VpnService)?.protect(s) ?: true

    fun start(timeoutMs: Long = 60_000) {
        VpnState.log("[$label] запуск: ${PacketVpnConfig.hostOf(profile)}, SOCKS 127.0.0.1:$port")
        if (isSstp) {
            val s = SstpSession(ctx, ::protectSocket, { t -> openPipe(listOf(t.ipv4), listOfNotNull(t.dns).ifEmpty { listOf("1.1.1.1", "8.8.8.8") }, t.mtu, emptyList()) }) { err ->
                failure = err ?: "SSTP: соединение закрыто"
                if (!stopped && ready) onExit?.invoke(failure!!)
            }
            sstp = s
            // 2.0.3: stage-by-stage status like ZeroTier (TLS → HTTP → SSTP → LCP → auth → IPCP → connected)
            kittoku.osc.SstpLog.listener = { m ->
                val st = when {
                    m.startsWith("TLS: host") -> "TCP/TLS: соединение…"
                    m.startsWith("TCP connected") -> "TCP: OK · TLS: рукопожатие…"
                    m.startsWith("TLS handshake done") -> "TLS: OK · HTTP: запрос…"
                    m.startsWith("HTTP: ") -> "HTTP: ${m.removePrefix("HTTP: ").take(40)} · SSTP…"
                    m.startsWith("stage ") && m.contains(": ok") -> {
                        val w = m.removePrefix("stage ").substringBefore(':')
                        when {
                            w == "SSL" -> "TLS: OK · SSTP…"
                            w.contains("SSTP") -> "OK · LCP…"
                            w == "LCP" -> "LCP: OK ${m.substringAfter(": ok").trim()} · авторизация…"
                            w.contains("PAP") || w.contains("CHAP") || w.contains("MSCHAP") || w.contains("EAP") -> "авторизация: OK · IPCP…"
                            w.contains("IPCP") -> "IPCP: OK"
                            else -> "$w: OK"
                        }
                    }
                    m.startsWith("IP: ") -> "подключено · ${m.removePrefix("IP: ")}"
                    m.startsWith("FAIL ") -> kittoku.osc.SstpLog.lastFailure?.let { kittoku.osc.SstpSession.errorText(it).removePrefix("SSTP: ") } ?: "ошибка: ${m.removePrefix("FAIL ").take(120)}"
                    else -> null
                }
                if (st != null) { VpnState.setDetail("SSTP: $st"); VpnState.log("[SSTP] $st") }
                if (m.contains(" → ") || m.contains(" ← ") || m.contains("нет ответа")) VpnState.log("[SSTP] $m")
            }
            VpnState.setDetail("SSTP: запуск…")
            s.start(SstpSession.Params(profile.server, profile.port, profile.username.orEmpty(), SecretBox.open(profile.password),
                verifyCert = profile.olc?.get("verify") != "0", sni = profile.olc?.get("sni").orEmpty()))
        } else {
            val z = ZtSession(ztStateDir(ctx), ::protectUdp,
                { t -> VpnState.log("[ZeroTier] сеть: ${t.ipv4}/${t.prefix}, маршруты ${t.routes}, MTU ${t.mtu}"); openPipe(listOf(t.ipv4), t.dns, t.mtu, t.routes) },
                { err -> failure = err ?: "ZeroTier: соединение закрыто"; if (!stopped && ready) onExit?.invoke(failure!!) },
                log = { if (!it.startsWith("trace ")) VpnState.log("[ZeroTier] $it") else ztLog(it) },
                onStatus = { VpnState.setDetail("ZeroTier: $it"); VpnState.log("[ZeroTier] $it") })
            zt = z
            VpnState.setDetail("ZeroTier: запуск узла…")
            thread(name = "zt-start", isDaemon = true) {
                z.start(profile.server, timeoutMs - 5_000)?.let { failure = it; VpnState.setDetail(it) }
                if (failure == null) {
                    VpnState.log("[ZeroTier] узел ${z.nodeId}, адрес ${z.address}")
                    thread(name = "zt-peers", isDaemon = true) {
                        var n = 0
                        while (!stopped && z.connected) {
                            val ps = z.peersSummary()
                            if (n % 30 == 0 || n == 5 || n == 15) VpnState.log("[ZeroTier] peers: $ps · tx ${z.txBytes.get()} rx ${z.rxBytes.get()} frames ${z.framesIn.get()}")
                            // 2.0.3: make the "no direct path" case visible instead of a misleading plain OK
                            if (n == 20 && z.framesIn.get() == 0L) {
                                val relayOnly = ps.split("; ").filter { it.contains(" LEAF ") }.let { l -> l.isNotEmpty() && l.all { it.contains("[relay]") } }
                                val w = if (relayOnly) "нет прямого пути к узлам сети (только relay) — входящих кадров нет; нужен публичный UDP 9993 у узла/контроллера" else "входящих кадров нет"
                                VpnState.setDetail("ZeroTier: ${z.nodeId} · ${z.address} · $w"); VpnState.log("[ZeroTier] ВНИМАНИЕ: $w")
                            }
                            n++
                            Thread.sleep(1000)
                        }
                    }
                }
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !stopped) {
            if (ready) { VpnState.log("[$label] подключено"); return }
            failure?.let { stop(); throw IllegalStateException(it) }
            if (process?.isAlive == false && !ready) { val e = lastError() ?: "netstack завершился"; stop(); throw IllegalStateException("$label: $e") }
            Thread.sleep(100)
        }
        stop()
        throw IllegalStateException("$label не подключился за ${timeoutMs / 1000} с")
    }

    private fun lastError(): String? = synchronized(lastLines) { lastLines.lastOrNull { it.startsWith("ERR") }?.removePrefix("ERR ")?.take(240) }

    /** Starts the netstack helper for the negotiated addresses and returns the framed packet pipe. Blocking. */
    @Synchronized private fun openPipe(addrs: List<String>, dns: List<String>, mtu: Int, routes: List<String>): Pair<InputStream, OutputStream> {
        val lib = ctx.applicationInfo.nativeLibraryDir
        val self = File(lib, "libvpnstack.so")
        if (!self.exists()) throw IllegalStateException("libvpnstack.so не найден")
        val dir = File(ctx.noBackupFilesDir, "vpn").apply { mkdirs() }
        val sock = File(dir, "pkt-$port.sock").apply { delete() }
        val cfg = File(dir, "pkt-$port.json")
        cfg.writeText(buildJsonObject {
            put("mode", "run"); put("port", port); put("sock", sock.absolutePath); put("mtu", mtu)
            putJsonArray("addrs") { addrs.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("dns") { dns.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("routes") { routes.forEach { add(JsonPrimitive(it)) } }
        }.toString())
        val pb = ProcessBuilder(self.absolutePath, "pkt", cfg.absolutePath).directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = lib
        val p = pb.start(); process = p
        val listening = java.util.concurrent.atomic.AtomicBoolean(false)
        thread(name = "pkt-log", isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { raw ->
                    val line = raw.trim(); if (line.isEmpty()) return@forEachLine
                    synchronized(lastLines) { lastLines.addLast(line); while (lastLines.size > 30) lastLines.removeFirst() }
                    if (line.startsWith("LISTEN")) listening.set(true)
                    if (line.startsWith("READY")) ready = true
                    VpnState.log("[$label] ${line.removePrefix("LOG ").take(300)}")
                }
            } catch (_: Exception) {}
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            cfg.delete()
            if (!stopped && ready) { val m = "$label: netstack завершился (код $code)" + (lastError()?.let { ": $it" } ?: ""); stop(); onExit?.invoke(m) }
        }
        val end = System.currentTimeMillis() + 10_000
        while (!listening.get() && p.isAlive && System.currentTimeMillis() < end) Thread.sleep(20)
        if (!listening.get()) throw IllegalStateException("netstack не запустился" + (lastError()?.let { ": $it" } ?: ""))
        val ls = LocalSocket().apply { connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
        socket = ls
        return ls.inputStream to ls.outputStream
    }

    fun stop() {
        if (stopped) return
        stopped = true
        if (!isSstp) VpnState.setDetail(null)
        if (isSstp) kittoku.osc.SstpLog.listener = null
        runCatching { sstp?.stop() }; sstp = null
        runCatching { zt?.stop() }; zt = null
        runCatching { socket?.close() }; socket = null
        val p = process ?: return
        process = null
        thread(isDaemon = true) {
            try {
                try { p.outputStream.apply { write("STOP\n".toByteArray()); flush() } } catch (_: Exception) {}
                if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) { p.destroy(); if (!p.waitFor(1000, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
            } catch (_: Exception) {}
        }
    }

    companion object {
        private fun ztLog(m: String) { try { android.util.Log.d("ZeroTier", m) } catch (_: Throwable) {} }

        /**
         * Persistent ZeroTier state (identity.secret/public, planet, peers.d, networks.d, port) — reused on every
         * connect so the node ID stays the same. 2.0.1 kept it in noBackupFilesDir; migrate it once.
         */
        fun ztStateDir(ctx: Context): File {
            val dir = File(ctx.filesDir, "zerotier")
            val old = File(ctx.noBackupFilesDir, "zerotier")
            if (!File(dir, "identity.secret").isFile && File(old, "identity.secret").isFile) runCatching { old.copyRecursively(dir, overwrite = true) }
            return dir.apply { mkdirs() }
        }

        private val live = java.util.concurrent.CopyOnWriteArrayList<PacketVpnEngine>()
        fun register(e: PacketVpnEngine) { live += e }
        fun stopAll() { live.forEach { it.stop() }; live.clear() }

        /** Ping without a full tunnel: SSTP = TCP connect time to the server; ZeroTier = n/a (-2). */
        fun ping(p: XrayProfile, timeoutMs: Int): Long {
            if (p.protocol != PacketVpnConfig.SSTP) return -2L
            return try {
                val t0 = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(p.server, p.port.takeIf { it in 1..65535 } ?: 443), timeoutMs.coerceIn(2_000, 15_000)) }
                ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
            } catch (_: Exception) { -1L }
        }
    }
}
