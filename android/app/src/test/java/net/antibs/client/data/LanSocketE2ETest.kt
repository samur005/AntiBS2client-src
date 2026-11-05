package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Localhost TCP E2E for the LAN wire (no ACK required by peer).
 * Send-write success is NOT treated as confirmed import when the peer closes silently.
 */
class LanSocketE2ETest {
    private fun readAll(sock: Socket, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        sock.soTimeout = 5000
        while (out.size() < max) {
            val k = sock.getInputStream().read(buf)
            if (k < 0) break
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    }

    @Test fun plainSendReceiveCorrectPin() {
        val code = "482913"
        val body = LanProtocol.plainBody(
            code,
            listOf(Subscription(id = "s1", url = "https://example.invalid/sub/fake", title = "Demo")),
        )
        val got = AtomicReference<String?>(null)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val ss = ServerSocket(0)
        val port = ss.localPort
        Thread {
            try {
                ready.countDown()
                ss.accept().use { c ->
                    got.set(String(readAll(c, LanProtocol.MAX_BODY), Charsets.UTF_8))
                }
            } finally {
                done.countDown()
                runCatching { ss.close() }
            }
        }.also { it.isDaemon = true; it.start() }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.getOutputStream().apply { write(body.toByteArray()); flush() }
            s.shutdownOutput()
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        val r = LanProtocol.decode(got.get()!!, code, null) as LanProtocol.Incoming.Ok
        assertEquals(1, r.subs.size)
        assertEquals("https://example.invalid/sub/fake", r.subs[0]["url"].toString().trim('"'))
    }

    @Test fun wrongPinRejected() {
        val body = LanProtocol.plainBody("111111", listOf(Subscription(id = "s1", url = "https://example.invalid/a", title = "T")))
        val r = LanProtocol.decode(body, "222222", null) as LanProtocol.Incoming.Rejected
        assertTrue(r.wrongCode)
    }

    @Test fun sealedRoundTripBothDirections() {
        val code = "654321"
        val session = LanProtocol.newSession("127.0.0.1", code)
        val plain = LanProtocol.plainBody(code, listOf(Subscription(id = "x", url = "https://example.invalid/b", title = "B")))
        val sealed = LanProtocol.seal(session.key, plain)
        val ok = LanProtocol.decode(sealed, code, session) as LanProtocol.Incoming.Ok
        assertTrue(ok.sealed)
        assertEquals(1, ok.subs.size)
        // reverse: open then re-seal
        val opened = LanProtocol.open(session.key, Json.parseToJsonElement(sealed).jsonObject)!!
        assertEquals(plain, opened)
    }

    @Test fun writeSuccessWithoutAckIsUnknown() {
        // Peer reads and closes without writing ACK — mirrors peers that do not speak our extension.
        val ss = ServerSocket(0)
        val port = ss.localPort
        val ready = CountDownLatch(1)
        Thread {
            ready.countDown()
            ss.accept().use { c -> readAll(c, LanProtocol.MAX_BODY) /* no reply */ }
            runCatching { ss.close() }
        }.also { it.isDaemon = true; it.start() }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        val reply: ByteArray? = Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.getOutputStream().apply { write(LanProtocol.plainBody("123456", emptyList()).toByteArray()); flush() }
            s.shutdownOutput()
            s.soTimeout = 800
            runCatching { readAll(s, 4096) }.getOrNull()
        }
        val ack = reply?.let { LanProtocol.parseAck(String(it, Charsets.UTF_8)) }
        assertNull("no ACK expected", ack)
    }
}
