package net.antibs.zt

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicLong

/** What the host needs to build a packet pipe for the joined network. */
data class ZtTunnel(val ipv4: String, val prefix: Int, val routes: List<String>, val dns: List<String>, val mtu: Int, val name: String)

/**
 * ZeroTier node + L2 bridge running inside the host's VpnService (no TUN of its own).
 * IP packets go through the host's pipe framed as a 2-byte big-endian length + payload.
 */
class ZtSession(
    private val dir: File,
    private val protect: (DatagramSocket) -> Boolean,
    private val openTunnel: (ZtTunnel) -> Pair<InputStream, OutputStream>,
    private val onClosed: (String?) -> Unit,
    private val log: (String) -> Unit = {},
    /** Human-readable progress for the UI: node ID, network status, assigned address. */
    private val onStatus: (String) -> Unit = {},
    private val wireFilter: (java.net.InetSocketAddress) -> Boolean = { true },
) {
    @Volatile var status = ""; private set
    @Volatile var address = ""; private set
    @Volatile var nodeId = ""; private set
    @Volatile var error: String? = null; private set
    @Volatile var connected = false; private set
    val txBytes = AtomicLong()
    val rxBytes = AtomicLong()
    /** Ethernet frames delivered by the core (any type) — diagnostics. */
    val framesIn = AtomicLong()
    private var engine: ZtEngine? = null
    @Volatile private var bridge: L2Bridge? = null
    @Volatile private var stopping = false
    private var pipe: Pair<InputStream, OutputStream>? = null

    /** Blocks until the tunnel is up (returns null) or fails (returns the Russian error text). */
    fun start(networkId: String, timeoutMs: Long = 45_000): String? {
        val nwid = ZtEngine.parseNetworkId(networkId) ?: return "ZeroTier: неверный Network ID (нужно 16 шестнадцатеричных символов)"
        val e = ZtEngine(dir, protect = protect, log = log, wireFilter = wireFilter,
            onNetwork = { n -> onNetwork(nwid, n) },
            onFrame = { _, src, et, d -> framesIn.incrementAndGet(); bridge?.fromZt(src, et, d) })
        engine = e
        try { e.start(nwid) } catch (t: Throwable) {
            stop()
            return if (t is UnsatisfiedLinkError) "ZeroTier недоступен на этой архитектуре процессора (нужен arm64-v8a)" else "ZeroTier: не удалось запустить узел — ${t.message}"
        }
        nodeId = "%010x".format(e.address)
        report("узел $nodeId: запрос конфигурации сети ${ZtEngine.idString(nwid)}…")
        val end = System.currentTimeMillis() + timeoutMs
        while (!stopping && System.currentTimeMillis() < end && !connected && error == null) Thread.sleep(250)
        if (connected) return null
        val n = e.lastNetwork
        val msg = error ?: when {
            stopping -> "ZeroTier: остановлено"
            !e.online && n == null -> "ZeroTier: нет связи с корневыми серверами ZeroTier (UDP 9993 заблокирован?)"
            n?.status == "ACCESS_DENIED" -> statusError("ACCESS_DENIED", nodeId)!!
            n == null || n.status == "REQUESTING_CONFIGURATION" -> "ZeroTier: контроллер сети не ответил — сеть существует и доступна? (узел $nodeId)"
            n.ipv4 == null -> "ZeroTier: сеть не выдала IPv4-адрес (проверьте авто-назначение адресов в панели сети)"
            else -> "ZeroTier: не удалось поднять туннель"
        }
        stop()
        return msg
    }

    @Synchronized private fun onNetwork(nwid: Long, n: ZtNetwork) {
        if (nodeId.isEmpty()) engine?.address?.takeIf { it != 0L }?.let { nodeId = "%010x".format(it) }
        if (status != n.status) { status = n.status; log("status ${n.status} addresses=${n.addresses} (node $nodeId)") }
        // ACCESS_DENIED is not final while connecting: the admin may authorize the node meanwhile — keep waiting.
        if (n.status == "ACCESS_DENIED" && !connected) { report("узел $nodeId: ожидает авторизации в панели сети (ACCESS_DENIED)"); return }
        statusError(n.status, nodeId)?.let { error = it; report(it); if (connected) Thread { close(it) }.start(); return }
        if (n.status != "OK") { if (!connected) report("узел $nodeId: ${n.status}"); return }
        if (connected || stopping) return
        val v4 = n.ipv4 ?: run { report("узел $nodeId: OK, ждём IPv4-адрес от контроллера…"); return }
        address = "${v4.first}/${v4.second}"
        try {
            val routes = n.routes.filter { it.v4 }.map { "${it.target}/${it.prefix}" }.ifEmpty { listOf(prefixOf(v4.first, v4.second)) }
            val p = openTunnel(ZtTunnel(v4.first, v4.second, routes, n.dns, n.mtu.coerceIn(1280, 2800), n.name.ifBlank { ZtEngine.idString(nwid) }))
            pipe = p
            val out = p.second
            val myIp = ZtRoute.ipInt(v4.first)
            val e = engine!!
            bridge = L2Bridge(n.mac, myIp, n.routes, { m, et, pk -> e.sendFrame(nwid, n.mac, m, et, pk) }, toTun = { pk: ByteArray ->
                runCatching {
                    synchronized(out) { out.write(byteArrayOf((pk.size shr 8).toByte(), pk.size.toByte())); out.write(pk); out.flush() }
                    rxBytes.addAndGet(pk.size.toLong())
                }
            })
            e.subscribe(nwid, L2Bridge.BROADCAST, myIp.toLong() and 0xffffffffL)
            Thread({ pump(p.first) }, "zt-pipe").apply { isDaemon = true }.start()
            log("tunnel up ${v4.first}/${v4.second} mtu=${n.mtu} routes=${n.routes.size}")
            connected = true
            report("узел $nodeId · ${v4.first}/${v4.second} · OK")
        } catch (t: Throwable) { error = "ZeroTier: не удалось поднять туннель — ${t.message}" }
    }

    private fun report(s: String) { runCatching { onStatus(s) } }

    private fun pump(inp: InputStream) {
        val d = DataInputStream(inp)
        while (!stopping) {
            val pk = try { val n = d.readUnsignedShort(); ByteArray(n).also { d.readFully(it) } } catch (_: Exception) { break }
            txBytes.addAndGet(pk.size.toLong()); bridge?.fromTun(pk)
        }
        if (!stopping) close("ZeroTier: туннель закрыт")
    }

    private fun close(reason: String?) { if (stopping) return; stop(); onClosed(reason) }

    fun peersSummary(): String = engine?.peersSummary().orEmpty()

    fun stop() {
        stopping = true; connected = false; bridge = null
        pipe?.let { runCatching { it.first.close() }; runCatching { it.second.close() } }; pipe = null
        runCatching { engine?.stop() }; engine = null
    }

    companion object {
        fun statusError(status: String, nodeId: String): String? = when (status) {
            "ACCESS_DENIED" -> "ZeroTier: доступ запрещён — авторизуйте устройство $nodeId в панели управления сетью"
            "NOT_FOUND" -> "ZeroTier: сеть не найдена — проверьте Network ID"
            "PORT_ERROR" -> "ZeroTier: ошибка виртуального порта"
            "CLIENT_TOO_OLD" -> "ZeroTier: версия клиента не поддерживается сетью"
            "AUTHENTICATION_REQUIRED" -> "ZeroTier: сеть требует вход через браузер (SSO) — не поддерживается"
            else -> null
        }
        fun prefixOf(ip: String, prefix: Int): String {
            val v = ZtRoute.ipInt(ip); val m = if (prefix <= 0) 0 else if (prefix >= 32) -1 else (-1 shl (32 - prefix))
            return "${L2Bridge.ipToString(v and m)}/$prefix"
        }
    }
}
