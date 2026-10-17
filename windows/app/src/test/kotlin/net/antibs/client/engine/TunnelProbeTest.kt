package net.antibs.client.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** 1.0.19: SOCKS5 + HTTP probe against a fake local SOCKS5 server. */
class TunnelProbeTest {
    /** Fake SOCKS5 inbound: records the requested host:port and answers the HTTP request (or not). */
    private fun fakeSocks(answer: String?, seen: MutableList<String>): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                while (true) {
                    val c: Socket = ss.accept()
                    thread(isDaemon = true) {
                        c.use { s ->
                            val i = DataInputStream(s.getInputStream()); val o = s.getOutputStream()
                            i.readByte(); val n = i.readByte().toInt(); repeat(n) { i.readByte() }
                            o.write(byteArrayOf(5, 0))
                            i.readByte(); i.readByte(); i.readByte()
                            val host = when (i.readByte().toInt()) {
                                3 -> { val l = i.readByte().toInt() and 0xff; String(ByteArray(l).also { i.readFully(it) }) }
                                1 -> ByteArray(4).also { i.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                                else -> "?"
                            }
                            val port = i.readUnsignedShort()
                            synchronized(seen) { seen += "$host:$port" }
                            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                            val req = StringBuilder()
                            while (!req.endsWith("\r\n\r\n")) { val b = i.read(); if (b < 0) break; req.append(b.toChar()) }
                            synchronized(seen) { seen += req.lineSequence().first() }
                            if (answer != null) { o.write(answer.toByteArray()); o.flush() } else Thread.sleep(5_000)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return ss
    }

    @Test fun okThroughSocksWithDomainName() {
        val seen = mutableListOf<String>()
        fakeSocks("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n", seen).use { ss ->
            val r = TunnelProbe.check(ss.localPort, "http://www.gstatic.com/generate_204", "HEAD", 3000)
            assertTrue(r.toString(), r is TunnelProbe.Result.Ok)
            assertEquals(204, (r as TunnelProbe.Result.Ok).status)
            assertTrue(r.ms >= 1)
            assertEquals("www.gstatic.com:80", seen[0])
            assertEquals("HEAD /generate_204 HTTP/1.1", seen[1])
        }
    }

    @Test fun timeoutWhenTunnelSilent() {
        val seen = mutableListOf<String>()
        fakeSocks(null, seen).use { ss ->
            val t0 = System.currentTimeMillis()
            val r = TunnelProbe.check(ss.localPort, "http://example.com/x?y=1", "GET", 800)
            assertTrue(r.toString(), r is TunnelProbe.Result.Failed)
            assertTrue(System.currentTimeMillis() - t0 < 3000)
            assertEquals("GET /x?y=1 HTTP/1.1", seen[1])
        }
    }

    @Test fun noInboundWhenPortClosed() {
        val port = ServerSocket(0).use { it.localPort }
        assertTrue(TunnelProbe.check(port, "http://example.com/", "GET", 1000) is TunnelProbe.Result.NoInbound)
    }

    @Test fun garbageIsFailure() {
        val seen = mutableListOf<String>()
        fakeSocks("garbage\r\n", seen).use { ss ->
            assertTrue(TunnelProbe.check(ss.localPort, "http://example.com/", "GET", 1000) is TunnelProbe.Result.Failed)
        }
    }
}
