package kittoku.osc.terminal

import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.SstpTunnel
import kittoku.osc.Where
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * IP side of the PPP link. Instead of a VpnService TUN the packets go to the host's packet pipe
 * (each packet framed as a 2-byte big-endian length + payload).
 */
internal class IPTerminal(private val bridge: SharedBridge) {
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var raw: InputStream? = null

    internal suspend fun initialize() {
        if (bridge.currentIPv4.contentEquals(ByteArray(4))) {
            bridge.controlMailbox.send(ControlMessage(Where.IPv4, Result.ERR_INVALID_ADDRESS))
            return
        }
        val ip = InetAddress.getByAddress(bridge.currentIPv4).hostAddress!!
        // the server proposed no DNS — without one nothing resolves inside the tunnel
        val dns = if (!bridge.currentProposedDNS.contentEquals(ByteArray(4))) InetAddress.getByAddress(bridge.currentProposedDNS).hostAddress else null
        kittoku.osc.SstpLog.i("IP: $ip dns=${dns ?: "—"}")
        val (i, o) = bridge.service.openTunnel(SstpTunnel(ip, dns, bridge.PPP_MTU))
        raw = i; input = DataInputStream(i); output = o
        bridge.controlMailbox.send(ControlMessage(Where.IP, Result.PROCEEDED))
    }

    internal fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        val o = output ?: return
        synchronized(o) {
            o.write(byteArrayOf((size shr 8).toByte(), size.toByte()))
            o.write(buffer.array(), start, size)
            o.flush()
        }
        bridge.service.rxBytes.addAndGet(size.toLong())
    }

    internal fun readPacket(buffer: ByteBuffer) {
        buffer.clear()
        val i = input
        var n = 0
        if (i != null) {
            n = i.readUnsignedShort()
            val cap = buffer.capacity()
            if (n > cap) { i.skipBytes(n); n = 0 } else i.readFully(buffer.array(), 0, n)
            if (n > 0) bridge.service.txBytes.addAndGet(n.toLong())
        }
        buffer.position(n)
        buffer.flip()
    }

    internal fun close() {
        runCatching { raw?.close() }
        runCatching { output?.close() }
    }
}
