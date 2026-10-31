package net.antibs.zt

import com.zerotier.sdk.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Snapshot of the joined network for the UI / VpnService. */
data class ZtNetwork(
    val nwid: Long, val name: String, val status: String, val mac: Long, val mtu: Int,
    val addresses: List<Pair<String, Int>>, val routes: List<ZtRoute>, val dns: List<String>, val dnsDomain: String
) {
    val ipv4: Pair<String, Int>? get() = addresses.firstOrNull { !it.first.contains(':') }
}

/**
 * ZeroTier node (ZeroTierOne core via its JNI SDK) with a UDP socket and file data store.
 * Pure JVM: the Android VpnService passes [protect]; tests run it against the real ZeroTier network.
 */
class ZtEngine(
    private val dir: File,
    private val protect: (DatagramSocket) -> Boolean = { true },
    private val log: (String) -> Unit = {},
    private val onNetwork: (ZtNetwork) -> Unit = {},
    private val onOnline: (Boolean) -> Unit = {},
    private val onFrame: (nwid: Long, srcMac: Long, etherType: Int, data: ByteArray) -> Unit = { _, _, _, _ -> },
    /** Tests: drop wire traffic to/from some addresses (e.g. LAN) to force relayed paths like on a CGNAT phone. */
    private val wireFilter: (InetSocketAddress) -> Boolean = { true },
) {
    private val lock = Object()
    @Volatile private var running = false
    private var node: Node? = null
    private var socket: DatagramSocket? = null
    private val deadline = LongArray(1)
    private val threads = mutableListOf<Thread>()
    /** Core callbacks must never re-enter the core (its mutexes are not recursive): deliver them on this thread. */
    private val cb = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "zt-cb").apply { isDaemon = true } }
    @Volatile var address: Long = 0; private set
    @Volatile var online = false; private set
    @Volatile var lastNetwork: ZtNetwork? = null; private set

    fun start(nwid: Long) {
        dir.mkdirs()
        // One node per state directory: two nodes started together on an empty dir would each generate and
        // write a different identity (the running node then no longer matches identity.secret on disk).
        synchronized(active) { active.remove(dir.canonicalPath)?.let { if (it !== this) { log("stopping previous node"); it.stop() } }; active[dir.canonicalPath] = this }
        log(if (file("identity.secret").length() > 0) "identity: loaded from ${dir.path}" else "identity: none yet — generating a new one in ${dir.path}")
        val s = openSocket()
        if (!protect(s)) { s.close(); throw IllegalStateException("protect failed") }
        socket = s
        val n = Node(System.currentTimeMillis())
        val rc = n.init(
            DataStoreGetListener { name, out -> read(name, out) },
            object : DataStorePutListener {
                override fun onDataStorePut(name: String, buffer: ByteArray, secure: Boolean): Int = write(name, buffer)
                override fun onDelete(name: String): Int { file(name).delete(); return 0 }
            },
            PacketSender { _, remote, data, _ -> if (!wireFilter(remote)) return@PacketSender 0; runCatching { s.send(DatagramPacket(data, data.size, remote)) }.fold({ 0 }, { -1 }) },
            object : EventListener {
                override fun onEvent(event: Event) {
                    log("event $event")
                    when (event) {
                        Event.EVENT_ONLINE -> { online = true; onOnline(true) }
                        Event.EVENT_OFFLINE -> { online = false; onOnline(false) }
                        else -> Unit
                    }
                }
                override fun onTrace(message: String) { log("trace $message") }
            },
            VirtualNetworkFrameListener { id, src, _, et, _, data -> runCatching { cb.execute { onFrame(id, src, et.toInt(), data) } } },
            VirtualNetworkConfigListener { id, op, cfg -> if (cfg != null) publish(cfg, op) else if (op == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY) log("network $id destroyed"); 0 },
            object : PathChecker {
                override fun onPathCheck(ztAddress: Long, localSocket: Long, remoteAddress: InetSocketAddress) = wireFilter(remoteAddress)
                override fun onPathLookup(ztAddress: Long, ss_family: Int): InetSocketAddress? = null
            }
        )
        if (rc != ResultCode.RESULT_OK) { s.close(); throw IllegalStateException("node init: $rc") }
        node = n
        address = n.address()
        log("node ${"%010x".format(address)} port ${s.localPort}")
        running = true
        synchronized(lock) { n.join(nwid) }
        threads += Thread({ recvLoop(s) }, "zt-recv").apply { isDaemon = true; start() }
        threads += Thread({ bgLoop() }, "zt-bg").apply { isDaemon = true; start() }
    }

    private fun publish(cfg: VirtualNetworkConfig, op: VirtualNetworkConfigOperation) {
        val net = ZtNetwork(
            cfg.nwid, cfg.name.orEmpty(), cfg.status.name.removePrefix("NETWORK_STATUS_"), cfg.mac, cfg.mtu,
            cfg.assignedAddresses.orEmpty().map { it.address.hostAddress!!.substringBefore('%') to it.port },
            cfg.routes.orEmpty().map { ZtRoute(it.target.address.hostAddress!!.substringBefore('%'), it.target.port, it.via?.address?.hostAddress) },
            cfg.dns?.servers.orEmpty().map { it.address.hostAddress!! }, cfg.dns?.domain.orEmpty()
        )
        lastNetwork = net
        log("network ${op.name.removePrefix("VIRTUAL_NETWORK_CONFIG_OPERATION_")} ${net.status} ${net.addresses} routes=${net.routes.map { "${it.target}/${it.prefix}" }}")
        runCatching { cb.execute { onNetwork(net) } }
    }

    private fun recvLoop(s: DatagramSocket) {
        val buf = ByteArray(16384)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: Exception) { if (running) log("recv: ${e.message}"); continue }
            if (!wireFilter(InetSocketAddress(p.address, p.port))) continue
            val data = buf.copyOf(p.length)
            synchronized(lock) {
                node?.processWirePacket(System.currentTimeMillis(), 0, InetSocketAddress(p.address, p.port), data, deadline)
                (lock as Object).notifyAll()
            }
        }
    }

    private fun bgLoop() {
        while (running) {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                if (now >= deadline[0]) node?.processBackgroundTasks(now, deadline)
                val wait = (deadline[0] - System.currentTimeMillis()).coerceIn(10, 1000)
                (lock as Object).wait(wait)
            }
        }
    }

    fun sendFrame(nwid: Long, srcMac: Long, dstMac: Long, etherType: Int, data: ByteArray) = synchronized(lock) {
        node?.processVirtualNetworkFrame(System.currentTimeMillis(), nwid, srcMac, dstMac, etherType, 0, data, deadline)
        (lock as Object).notifyAll()
    }

    /** One line per known peer: address, role, version, latency and the direct paths (for logs / diagnostics). */
    fun peersSummary(): String = synchronized(lock) {
        node?.peers().orEmpty().joinToString("; ") { p ->
            val paths = p.paths.orEmpty().joinToString(",") { "${it.address}${if (it.isPreferred) "*" else ""}" }
            "%010x %s v%d.%d.%d %dms [%s]".format(p.address, p.role.name.removePrefix("PEER_ROLE_"), p.versionMajor, p.versionMinor, p.versionRev, p.latency, paths.ifEmpty { "relay" })
        }
    }

    fun subscribe(nwid: Long, group: Long, adi: Long) = synchronized(lock) { node?.multicastSubscribe(nwid, group, adi) }

    fun stop() {
        synchronized(active) { active.remove(dir.canonicalPath, this) }
        running = false
        socket?.close()
        synchronized(lock) { node?.close(); node = null; (lock as Object).notifyAll() }
        threads.forEach { it.join(1500) }; threads.clear()
        cb.shutdownNow()
    }

    /**
     * Binds the UDP socket to the same local port on every start (stored in `port`). With a fresh random port each
     * time, peers keep answering the previous (dead) path for a while, so a reconnect looked "up" but got no replies.
     */
    private fun openSocket(): DatagramSocket {
        val pf = File(dir, "port")
        val saved = runCatching { pf.readText().trim().toInt() }.getOrNull()?.takeIf { it in 1024..65535 }
        for (port in listOfNotNull(saved, (20000..60000).random(), 0)) {
            val s = DatagramSocket(null)
            try {
                s.reuseAddress = true; s.bind(InetSocketAddress(port))
                if (s.localPort != saved) runCatching { pf.writeText(s.localPort.toString()) }
                return s
            } catch (e: Exception) { s.close(); log("bind udp $port: ${e.message}") }
        }
        throw IllegalStateException("no UDP port")
    }

    private fun file(name: String) = File(dir, name.replace("..", "_"))
    private fun read(name: String, out: ByteArray): Long {
        val f = file(name); if (!f.isFile || f.length() == 0L) return -1
        val b = f.readBytes(); if (b.size > out.size) { log("state $name too large (${b.size})"); return -1 }
        System.arraycopy(b, 0, out, 0, b.size); return b.size.toLong()
    }
    private fun write(name: String, data: ByteArray): Int = runCatching {
        val f = file(name); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        java.io.FileOutputStream(tmp).use { it.write(data); it.fd.sync() }  // survive a process kill right after
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "rename" } }
        if (name.startsWith("identity.")) log("identity: saved $name")
        0
    }.getOrElse { log("state write $name: ${it.message}"); -1 }

    companion object {
        private val active = HashMap<String, ZtEngine>()
        fun parseNetworkId(s: String): Long? = s.trim().lowercase().takeIf { it.matches(Regex("[0-9a-f]{16}")) }?.let { java.lang.Long.parseUnsignedLong(it, 16) }
        fun idString(id: Long) = "%016x".format(id)
    }
}
