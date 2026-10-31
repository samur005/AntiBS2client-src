package net.antibs.zt

/**
 * Minimal L3 <-> L2 glue between an Android TUN (IP packets) and a ZeroTier virtual Ethernet port.
 * IPv4 only for sending (ARP is emulated, as ZeroTier's own clients do); received IPv4/IPv6 frames go to the TUN.
 * Pure JVM, unit-tested.
 */
class L2Bridge(
    private val myMac: Long,
    private val myIp: Int,
    private val routes: List<ZtRoute>,
    private val sendFrame: (dstMac: Long, etherType: Int, payload: ByteArray) -> Unit,
    private val toTun: (ByteArray) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val arp = HashMap<Int, Long>()
    private val pending = HashMap<Int, ArrayDeque<ByteArray>>()
    private val lastAsk = HashMap<Int, Long>()

    @Synchronized fun macOf(ip: Int): Long? = arp[ip]

    /** Next hop for an IPv4 destination: the gateway of the most specific matching route with a via, else the destination itself. */
    fun nextHop(dst: Int): Int {
        val r = routes.filter { it.v4 && matches(dst, it.targetInt, it.prefix) }.maxByOrNull { it.prefix }
        return if (r != null && r.via != 0) r.via else dst
    }

    @Synchronized fun fromTun(pkt: ByteArray) {
        if (pkt.isEmpty() || (pkt[0].toInt() ushr 4) != 4 || pkt.size < 20) return
        val dst = int(pkt, 16)
        if (dst == -1 || (dst ushr 28) == 0xE) { sendFrame(BROADCAST, 0x0800, pkt); return }
        val nh = nextHop(dst)
        val mac = arp[nh]
        if (mac != null) { sendFrame(mac, 0x0800, pkt); return }
        val q = pending.getOrPut(nh) { ArrayDeque() }
        if (q.size < 32) q.addLast(pkt)
        val now = clock()
        if (now - (lastAsk[nh] ?: 0L) > 1000) { lastAsk[nh] = now; sendFrame(BROADCAST, 0x0806, arpPacket(1, myMac, myIp, 0L, nh)) }
    }

    @Synchronized fun fromZt(srcMac: Long, etherType: Int, data: ByteArray) {
        when (etherType) {
            0x0800, 0x86DD -> toTun(data)
            0x0806 -> if (data.size >= 28 && u16(data, 0) == 1 && u16(data, 2) == 0x0800) {
                val op = u16(data, 6); val sha = mac(data, 8); val spa = int(data, 14); val tpa = int(data, 24)
                if (spa != 0) {
                    arp[spa] = sha
                    pending.remove(spa)?.forEach { sendFrame(sha, 0x0800, it) }
                }
                if (op == 1 && tpa == myIp) sendFrame(sha, 0x0806, arpPacket(2, myMac, myIp, sha, spa))
            }
        }
    }

    companion object {
        const val BROADCAST = 0xffffffffffffL
        fun matches(ip: Int, net: Int, prefix: Int): Boolean {
            if (prefix <= 0) return true
            val m = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
            return (ip and m) == (net and m)
        }
        fun int(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
        private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
        private fun mac(b: ByteArray, o: Int): Long { var v = 0L; for (i in 0 until 6) v = (v shl 8) or (b[o + i].toLong() and 255); return v }
        private fun putMac(b: ByteArray, o: Int, m: Long) { for (i in 0 until 6) b[o + i] = (m ushr (8 * (5 - i))).toByte() }
        private fun putInt(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v ushr (8 * (3 - i))).toByte() }
        fun arpPacket(op: Int, sha: Long, spa: Int, tha: Long, tpa: Int): ByteArray = ByteArray(28).also {
            it[1] = 1; it[2] = 8; it[4] = 6; it[5] = 4; it[7] = op.toByte()
            putMac(it, 8, sha); putInt(it, 14, spa); putMac(it, 18, tha); putInt(it, 24, tpa)
        }
        fun ipToString(ip: Int) = "${ip ushr 24}.${(ip ushr 16) and 255}.${(ip ushr 8) and 255}.${ip and 255}"
    }
}

data class ZtRoute(val target: String, val prefix: Int, val viaStr: String?) {
    val v4: Boolean get() = !target.contains(':')
    val targetInt: Int get() = if (v4) ipInt(target) else 0
    val via: Int get() = if (viaStr != null && !viaStr.contains(':') && viaStr != "0.0.0.0") ipInt(viaStr) else 0
    companion object {
        fun ipInt(s: String): Int = java.net.InetAddress.getByName(s).address.let { L2Bridge.int(it, 0) }
    }
}

