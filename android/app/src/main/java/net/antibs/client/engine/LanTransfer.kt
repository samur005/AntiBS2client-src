package net.antibs.client.engine

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import net.antibs.client.data.LanProtocol
import net.antibs.client.data.Subscription
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 2.0.12+/2.0.25: sockets for [LanProtocol] (receiver: TCP 45571 + UDP 45572 responder; sender: UDP discovery, /24 TCP probe fallback, one TCP post). */
object LanTransfer {
    data class RecvState(val running: Boolean = false, val ip: String = "", val code: String = "",
                         val session: LanProtocol.Session? = null, val lastInfo: String = "", val error: String = "")

    private val _recv = MutableStateFlow(RecvState())
    val recv: StateFlow<RecvState> = _recv
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var disc: DatagramSocket? = null
    @Volatile private var wrong = 0

    private fun ifaceAddrs(): List<Triple<String, Inet4Address, InetAddress?>> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.interfaceAddresses.mapNotNull { ia -> (ia.address as? Inet4Address)?.let { Triple(nif.name, it, ia.broadcast) } } }
    }.getOrDefault(emptyList())

    fun localIp(ctx: Context): String {
        LanProtocol.pickLanIp(ifaceAddrs().map { it.first to (it.second.hostAddress ?: "") })?.let { return it }
        @Suppress("DEPRECATION")
        val ip = runCatching { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
        return if (ip == 0) "" else "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    /** true when some Wi-Fi / hotspot / Ethernet interface has a private IPv4. */
    fun hasLan(): Boolean = ifaceAddrs().any { LanProtocol.isLanIface(it.first) && LanProtocol.isPrivate(it.second.hostAddress ?: "") }

    private fun rotate(ip: String, newCode: Boolean) {
        val code = if (newCode) LanProtocol.newCode() else _recv.value.code
        _recv.value = _recv.value.copy(code = code, session = LanProtocol.newSession(ip, code))
    }

    /** Start receive mode. [onReceive] gets wire-format subscription objects and returns how many were added. */
    fun startReceiver(ctx: Context, onReceive: (List<JsonObject>) -> Int) {
        stop()
        val ip = localIp(ctx)
        wrong = 0
        running.set(true)
        _recv.value = RecvState(running = true, ip = ip)
        rotate(ip, newCode = true)
        val model = runCatching { android.os.Build.MODEL }.getOrNull().orEmpty()
        thread(name = "lan-disc", isDaemon = true) {
            try {
                val sock = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(LanProtocol.DISC_PORT)) }
                disc = sock
                val buf = ByteArray(256)
                while (running.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim() == LanProtocol.DISC_ASK) {
                        val out = LanProtocol.reply(model).toByteArray(Charsets.UTF_8)
                        runCatching { sock.send(DatagramPacket(out, out.size, pkt.address, pkt.port)) }
                    }
                }
            } catch (_: Exception) {}
        }
        thread(name = "lan-recv", isDaemon = true) {
            try {
                val ss = ServerSocket(LanProtocol.PORT); server = ss
                while (running.get()) {
                    val c = ss.accept()
                    runCatching { c.use { handle(it, ip, onReceive) } }
                }
            } catch (e: Exception) {
                if (running.get()) _recv.value = _recv.value.copy(error = "Порт ${LanProtocol.PORT} занят: ${e.message}")
            }
        }
    }

    private fun handle(sock: Socket, ip: String, onReceive: (List<JsonObject>) -> Int) {
        sock.soTimeout = 15000
        val bytes = sock.getInputStream().readNBytesCompat(LanProtocol.MAX_BODY)
        val st = _recv.value
        val answer = when (val r = LanProtocol.decode(String(bytes, Charsets.UTF_8), st.code, st.session)) {
            is LanProtocol.Incoming.Ok -> {
                val n = onReceive(r.subs)
                wrong = 0
                if (r.sealed) rotate(ip, newCode = false)
                _recv.value = _recv.value.copy(lastInfo = "Получено подписок: $n от ${sock.inetAddress?.hostAddress}")
                LanProtocol.ack(n)
            }
            is LanProtocol.Incoming.Rejected -> {
                if (r.reason == "expired") rotate(ip, newCode = false)
                if (r.wrongCode && ++wrong >= LanProtocol.MAX_WRONG) {
                    wrong = 0; rotate(ip, newCode = true)
                    _recv.value = _recv.value.copy(lastInfo = "Слишком много неверных кодов — код сменён")
                }
                LanProtocol.nack(r.reason)
            }
        }
        // optional ACK extension: peer that already closed after write will not read this (write may fail silently)
        runCatching { sock.getOutputStream().apply { write(answer.toByteArray()); flush() }; sock.shutdownOutput() }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (out.size() < max) { val k = read(buf); if (k < 0) break; out.write(buf, 0, k) }
        return out.toByteArray()
    }

    fun refreshSession() { val s = _recv.value; if (s.running) rotate(s.ip, newCode = false) }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }; server = null
        runCatching { disc?.close() }; disc = null
        _recv.value = RecvState()
    }

    // ---- sender ----
    data class Peer(val ip: String, val name: String)

    /**
     * Find devices in receive mode: UDP broadcast discovery first (names), then a TCP connect probe of the receive
     * port over each own /24 (300 ms timeout, 48 at a time). Skips VPN/cellular interfaces. Cancellable.
     * [onFound] may be invoked from IO threads — hop to Main in the UI.
     */
    suspend fun discover(ctx: Context, onFound: (Peer) -> Unit) = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("antibs-lan")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            val nets = ifaceAddrs().filter { LanProtocol.isLanIface(it.first) && LanProtocol.isPrivate(it.second.hostAddress ?: "") }
            val own = nets.mapNotNull { it.second.hostAddress }.toSet()
            val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            fun emit(ip: String, name: String) {
                if (ip !in own && seen.add(ip)) onFound(Peer(ip, name))
            }
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true; sock.soTimeout = 250
                    val ask = LanProtocol.DISC_ASK.toByteArray(Charsets.UTF_8)
                    val targets = (nets.mapNotNull { it.third } + InetAddress.getByName("255.255.255.255")).distinct()
                    repeat(2) { targets.forEach { tgt -> runCatching { sock.send(DatagramPacket(ask, ask.size, tgt, LanProtocol.DISC_PORT)) } } }
                    val until = System.currentTimeMillis() + 1500
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < until) {
                        ensureActive()
                        val pkt = DatagramPacket(buf, buf.size)
                        try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                        val name = LanProtocol.parseReply(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) ?: continue
                        pkt.address?.hostAddress?.let { emit(it, name) }
                    }
                }
            } catch (_: Exception) {
            }
            val hosts = nets.flatMap { LanProtocol.subnetHosts(it.second.hostAddress ?: "") }.distinct().filter { it !in own }
            val sem = kotlinx.coroutines.sync.Semaphore(48)
            coroutineScope {
                hosts.forEach { h ->
                    launch {
                        sem.acquire()
                        try {
                            ensureActive()
                            if (!seen.contains(h)) {
                                val open = runCatching {
                                    Socket().use { it.connect(InetSocketAddress(h, LanProtocol.PORT), 300); true }
                                }.getOrDefault(false)
                                if (open) emit(h, h)
                            }
                        } finally { sem.release() }
                    }
                }
            }
        } finally { runCatching { lock?.release() } }
    }

    sealed class SendResult {
        data class Confirmed(val n: Int) : SendResult()
        object Unknown : SendResult()   // peer closed without ACK — write success ≠ confirmed import
        data class Refused(val reason: String) : SendResult()
        data class Failed(val message: String) : SendResult()
    }

    suspend fun send(host: String, port: Int, code: String, subs: List<Subscription>, key: ByteArray? = null): SendResult = withContext(Dispatchers.IO) {
        runCatching {
            val plain = LanProtocol.plainBody(code, subs)
            val body = if (key != null) LanProtocol.seal(key, plain) else plain
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5000)
                s.getOutputStream().apply { write(body.toByteArray()); flush() }
                s.shutdownOutput()
                s.soTimeout = 2500
                val reply = runCatching { s.getInputStream().readNBytesCompat(4096) }.getOrNull()
                val ack = reply?.let { LanProtocol.parseAck(String(it, Charsets.UTF_8)) }
                when {
                    ack == null -> SendResult.Unknown
                    ack.first -> SendResult.Confirmed(ack.second)
                    else -> SendResult.Refused(runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(String(reply, Charsets.UTF_8)).let { (it as JsonObject)["err"].toString().trim('"') }
                    }.getOrDefault("?"))
                }
            }
        }.getOrElse { SendResult.Failed(it.message ?: it.javaClass.simpleName) }
    }
}
