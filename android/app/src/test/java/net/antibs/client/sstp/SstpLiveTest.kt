package net.antibs.client.sstp

import kittoku.osc.SstpLog
import kittoku.osc.SstpSession
import kittoku.osc.SstpTunnel
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Live SSTP test against a real server. Gated by env SSTP_TEST="host:port login password"; the value is never printed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SstpLiveTest {
    @Test fun live() {
        val env = System.getenv("SSTP_TEST")
        assumeTrue(!env.isNullOrBlank())
        val (hp, user, pass) = env!!.trim().split(Regex("\\s+"), limit = 3)
        val host = hp.substringBeforeLast(':'); val port = hp.substringAfterLast(':').toIntOrNull() ?: 443
        fun red(s: String) = s.replace(host, "<HOST>").replace(user, "<USER>").replace(pass, "<PASS>")
        SstpLog.listener = { println("[sstp] " + red(it)) }
        val up = CountDownLatch(1); val closed = CountDownLatch(1)
        var tun: SstpTunnel? = null; var err: String? = null
        // app -> tunnel and tunnel -> app pipes
        val toTunIn = PipedInputStream(1 shl 16); val toTunOut = PipedOutputStream(toTunIn)
        val fromTunIn = PipedInputStream(1 shl 16); val fromTunOut = PipedOutputStream(fromTunIn)
        val s = SstpSession(RuntimeEnvironment.getApplication(), { true }, { t -> tun = t; up.countDown(); toTunIn to fromTunOut }) { e -> err = e; closed.countDown() }
        s.start(SstpSession.Params(host, port, user, pass, verifyCert = true))
        val ok = up.await(45, TimeUnit.SECONDS)
        println("[sstp] tunnel=" + (tun?.let { "ip=${it.ipv4} dns=${it.dns} mtu=${it.mtu}" }) + " err=" + err?.let(::red))
        assertTrue("no tunnel: " + err?.let(::red), ok)
        // DNS query for example.com to 1.1.1.1 through the tunnel
        val src = tun!!.ipv4.split('.').map { it.toInt().toByte() }.toByteArray()
        val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        val udp = java.nio.ByteBuffer.allocate(8 + q.size).putShort(40000.toShort()).putShort(53).putShort((8 + q.size).toShort()).putShort(0).put(q).array()
        val ip = java.nio.ByteBuffer.allocate(20 + udp.size).put(0x45).put(0).putShort((20 + udp.size).toShort()).putInt(0).put(64).put(17).putShort(0).put(src).put((System.getenv("SSTP_DNS") ?: "1.1.1.1").split(".").map { it.toInt().toByte() }.toByteArray()).put(udp).array()
        var sum = 0; for (i in 0 until 20 step 2) sum += ((ip[i].toInt() and 0xff) shl 8) or (ip[i + 1].toInt() and 0xff)
        while (sum > 0xffff) sum = (sum and 0xffff) + (sum shr 16); val ck = sum.inv() and 0xffff
        ip[10] = (ck shr 8).toByte(); ip[11] = ck.toByte()
        var answered = false
        val reader = Thread { runCatching { val d = DataInputStream(fromTunIn); while (true) { val n = d.readUnsignedShort(); val b = ByteArray(n); d.readFully(b)
            println("[sstp] rx packet len=$n proto=${b[9]}"); if (b[9].toInt() == 17 && n > 28 && b[20 + 8].toInt() == 0x12) { answered = true; return@runCatching } } } }.apply { isDaemon = true; start() }
        repeat(5) { if (!answered) { toTunOut.write(byteArrayOf((ip.size shr 8).toByte(), ip.size.toByte())); toTunOut.write(ip); toTunOut.flush(); reader.join(2000) } }
        println("[sstp] dns via tunnel answered=$answered tx=${s.txBytes.get()} rx=${s.rxBytes.get()}")
        s.stop(); closed.await(5, TimeUnit.SECONDS)
        assertTrue("no DNS answer through tunnel", answered)
    }

    /** Full chain like the app: SSTP → libvpnstack «pkt» (host build, env VPNSTACK_BIN) → SOCKS → HTTP. */
    @Test fun liveThroughNetstack() {
        val env = System.getenv("SSTP_TEST"); val bin = System.getenv("VPNSTACK_BIN")
        assumeTrue(!env.isNullOrBlank() && !bin.isNullOrBlank())
        val (hp, user, pass) = env!!.trim().split(Regex("\\s+"), limit = 3)
        val host = hp.substringBeforeLast(':'); val port = hp.substringAfterLast(':').toIntOrNull() ?: 443
        fun red(s: String) = s.replace(host, "<HOST>").replace(user, "<USER>").replace(pass, "<PASS>")
        SstpLog.listener = { println("[sstp] " + red(it)) }
        val socks = java.net.ServerSocket(0).use { it.localPort }
        val dir = java.nio.file.Files.createTempDirectory("pkt").toFile()
        var proc: Process? = null; val ready = CountDownLatch(1); var err: String? = null
        val s = SstpSession(RuntimeEnvironment.getApplication(), { true }, { t ->
            val sock = java.io.File(dir, "p.sock"); val cfg = java.io.File(dir, "p.json")
            cfg.writeText("""{"mode":"run","port":$socks,"sock":"${sock.absolutePath}","mtu":${t.mtu},"addrs":["${t.ipv4}"],"dns":["${t.dns ?: "1.1.1.1"}"],"routes":[]}""")
            val p = ProcessBuilder(bin, "pkt", cfg.absolutePath).redirectErrorStream(true).start(); proc = p
            val listening = CountDownLatch(1)
            Thread { p.inputStream.bufferedReader().forEachLine { println("[pkt] " + red(it)); if (it.startsWith("LISTEN")) listening.countDown(); if (it.startsWith("READY")) ready.countDown() } }.apply { isDaemon = true; start() }
            listening.await(10, TimeUnit.SECONDS)
            val ch = java.nio.channels.SocketChannel.open(Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String::class.java).invoke(null, sock.absolutePath) as java.net.SocketAddress)
            // own streams: Channels.newInputStream/newOutputStream share the channel's blockingLock (a read stalls writes)
            object : java.io.InputStream() {
                override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
                override fun read(b: ByteArray, off: Int, len: Int): Int = ch.read(java.nio.ByteBuffer.wrap(b, off, len))
            } to object : java.io.OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) { val bb = java.nio.ByteBuffer.wrap(b, off, len); while (bb.hasRemaining()) ch.write(bb) }
            }
        }) { e -> err = e }
        try {
            s.start(SstpSession.Params(host, port, user, pass, verifyCert = true))
            assertTrue("netstack not ready: " + err?.let(::red), ready.await(45, TimeUnit.SECONDS))
            Thread { repeat(30) { Thread.sleep(1000); println("[sstp] t+$it tx=${s.txBytes.get()} rx=${s.rxBytes.get()}") } }.apply { isDaemon = true; start() }
            // hostname resolved by the netstack through the tunnel DNS (like Xray → SOCKS)
            val t0 = System.currentTimeMillis()
            val sk = java.net.Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socks)))
            sk.soTimeout = 15000; sk.connect(java.net.InetSocketAddress.createUnresolved("example.com", 80), 15000)
            sk.getOutputStream().write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".toByteArray())
            val buf = ByteArray(65536); var got = 0; val ins = sk.getInputStream()
            while (got < buf.size) { val n = runCatching { ins.read(buf, got, buf.size - got) }.getOrDefault(-1); if (n <= 0) break; got += n; if (String(buf, 0, got).contains("</html>")) break }
            val resp = buf.copyOf(got); sk.close()
            val status = String(resp, 0, minOf(resp.size, 40)).lineSequence().first()
            println("[sstp] HTTP via SOCKS→SSTP: '$status', ${resp.size} bytes in ${System.currentTimeMillis() - t0} ms, tx=${s.txBytes.get()} rx=${s.rxBytes.get()}")
            assertTrue(status.contains(" 200"))
        } finally { s.stop(); proc?.destroy() }
    }
}
