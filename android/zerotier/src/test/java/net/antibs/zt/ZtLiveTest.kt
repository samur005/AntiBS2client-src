package net.antibs.zt

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Live test against a real ZeroTier network: ZT_LIVE=<nwid>,<target-ip> and the host JNI on java.library.path. */
class ZtLiveTest {
    private fun csum(b: ByteArray, o: Int, n: Int): Int {
        var s = 0L; var i = o
        while (i < o + n - 1) { s += ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255); i += 2 }
        if (i < o + n) s += (b[i].toInt() and 255) shl 8
        while (s shr 16 != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }

    private fun icmpEcho(src: String, dst: String, seq: Int): ByteArray {
        val p = ByteArray(28 + 16)
        p[0] = 0x45; p[3] = p.size.toByte(); p[8] = 64; p[9] = 1
        java.net.InetAddress.getByName(src).address.copyInto(p, 12); java.net.InetAddress.getByName(dst).address.copyInto(p, 16)
        csum(p, 0, 20).let { p[10] = (it shr 8).toByte(); p[11] = it.toByte() }
        p[20] = 8; p[25] = 7; p[27] = seq.toByte()
        csum(p, 20, p.size - 20).let { p[22] = (it shr 8).toByte(); p[23] = it.toByte() }
        return p
    }

    private fun run(dir: File, nwid: String, target: String): String {
        val toZt = PipedOutputStream(); val ztIn = PipedInputStream(toZt, 1 shl 16)
        val fromZt = PipedInputStream(1 shl 16); val ztOut = PipedOutputStream(fromZt)
        var tunnel: ZtTunnel? = null
        val s = ZtSession(dir, { true }, { t -> tunnel = t; println("TUNNEL $t"); ztIn to ztOut }, { println("CLOSED $it") }, log = { if (!it.startsWith("trace")) println("ZT $it") }, onStatus = { println("STATUS $it") },
            wireFilter = { a -> System.getenv("ZT_NO_LAN") == null || !(a.address.isSiteLocalAddress || a.address.isLoopbackAddress || a.address.isLinkLocalAddress) })
        val err = s.start(nwid, 90_000)
        println("NODE ${s.nodeId} err=$err")
        assertNull(err)
        val t = tunnel!!
        val rd = DataInputStream(fromZt)
        var ok = false
        val reader = Thread {
            try { while (true) { val n = rd.readUnsignedShort(); val b = ByteArray(n); rd.readFully(b)
                if (n >= 28 && b[9].toInt() == 1 && b[20].toInt() == 0) { println("ECHO REPLY from ${java.net.InetAddress.getByAddress(b.copyOfRange(12, 16)).hostAddress}"); ok = true } } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        for (i in 1..20) {
            if (ok) break
            val p = icmpEcho(t.ipv4, target, i)
            synchronized(toZt) { toZt.write(byteArrayOf((p.size shr 8).toByte(), p.size.toByte())); toZt.write(p); toZt.flush() }
            Thread.sleep(1000)
        }
        println("tx=${s.txBytes} rx=${s.rxBytes} frames=${s.framesIn}")
        println("PEERS ${s.peersSummary()}")
        s.stop(); reader.interrupt()
        assertTrue("no ICMP reply from $target", ok)
        return s.nodeId
    }

    @Test fun joinAndPing() {
        val live = System.getenv("ZT_LIVE"); assumeTrue(live != null)
        val (nwid, target) = live!!.split(',')
        val dir = File(System.getProperty("java.io.tmpdir"), "zt-live").apply { mkdirs() }
        val a = run(dir, nwid, target)
        println("FILES ${dir.walk().filter { it.isFile }.map { it.relativeTo(dir).path }.toList()}")
        val b = run(dir, nwid, target)
        assertEquals("identity must persist", a, b)
    }

    /** Brand-new identity: the network answers ACCESS_DENIED until the admin authorizes it; connect must keep waiting. */
    @Test fun freshNodeWaitsForAuthorization() {
        val live = System.getenv("ZT_LIVE"); assumeTrue(live != null && System.getenv("ZT_LIVE_FRESH") != null)
        val (nwid, target) = live!!.split(',')
        val dir = File(System.getProperty("java.io.tmpdir"), "zt-live-fresh").apply { deleteRecursively(); mkdirs() }
        val a = run(dir, nwid, target)
        assertEquals(a, run(dir, nwid, target))
    }
}
