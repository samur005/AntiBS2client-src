package net.antibs.client.engine

import net.antibs.client.data.AdvancedSettings
import net.antibs.client.data.LocalProxy
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 2.0.43: the app's own clients authenticate to the password-protected inbound — TunnelProbe (raw RFC 1929) and
 * OkHttp via java.net SOCKS + [LocalProxy]'s Authenticator (SpeedTest / NetMonitor / GeoLocate / SessionLook / XrayApi).
 */
class SocksAuthTest {
    /** Fake SOCKS5 that insists on username/password [u]/[p], then relays to 127.0.0.1:[target] whatever was asked. */
    private fun authSocks(u: String, p: String, target: Int, seen: MutableList<String>): ServerSocket {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                while (true) {
                    val c: Socket = ss.accept()
                    thread(isDaemon = true) {
                        c.use { s ->
                            val i = DataInputStream(s.getInputStream()); val o = s.getOutputStream()
                            i.readByte(); val n = i.readByte().toInt(); val methods = ByteArray(n).also { i.readFully(it) }
                            if (2.toByte() !in methods) { o.write(byteArrayOf(5, 0xFF.toByte())); o.flush(); synchronized(seen) { seen += "noauth-offered" }; return@use }
                            o.write(byteArrayOf(5, 2)); o.flush()
                            i.readByte()
                            val user = String(ByteArray(i.readByte().toInt() and 0xff).also { i.readFully(it) })
                            val pass = String(ByteArray(i.readByte().toInt() and 0xff).also { i.readFully(it) })
                            if (user != u || pass != p) { o.write(byteArrayOf(1, 1)); o.flush(); synchronized(seen) { seen += "bad-login" }; return@use }
                            o.write(byteArrayOf(1, 0)); o.flush()
                            i.readByte(); i.readByte(); i.readByte()
                            val host = when (i.readByte().toInt()) {
                                3 -> String(ByteArray(i.readByte().toInt() and 0xff).also { i.readFully(it) })
                                1 -> ByteArray(4).also { i.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                                else -> "?"
                            }
                            i.readUnsignedShort()
                            synchronized(seen) { seen += "ok:$host" }
                            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                            Socket("127.0.0.1", target).use { up ->
                                val t = thread(isDaemon = true) { try { up.getInputStream().copyTo(o) } catch (_: Exception) {} }
                                try { s.getInputStream().copyTo(up.getOutputStream()) } catch (_: Exception) {}
                                t.join(2000)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return ss
    }

    /** Minimal HTTP server answering 204 to everything. */
    private fun http204(): ServerSocket {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                while (true) {
                    val c = ss.accept()
                    thread(isDaemon = true) {
                        c.use { s ->
                            val r = s.getInputStream().bufferedReader()
                            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                            s.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); s.getOutputStream().flush()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return ss
    }

    @Test fun tunnelProbeAuthenticates() {
        val seen = mutableListOf<String>()
        http204().use { web -> authSocks("u1", "p1", web.localPort, seen).use { ss ->
            val r = TunnelProbe.check(ss.localPort, "http://probe.example/generate_204", "GET", 4000, auth = "u1" to "p1")
            assertTrue(r.toString(), r is TunnelProbe.Result.Ok)
            assertEquals(204, (r as TunnelProbe.Result.Ok).status)
            assertTrue(seen.toString(), "ok:probe.example" in seen)
        } }
    }

    @Test fun tunnelProbeWithoutCredentialsIsRejected() {
        val seen = mutableListOf<String>()
        http204().use { web -> authSocks("u1", "p1", web.localPort, seen).use { ss ->
            assertTrue(TunnelProbe.check(ss.localPort, "http://probe.example/", "GET", 2000, auth = null) is TunnelProbe.Result.Failed)
            assertTrue(TunnelProbe.check(ss.localPort, "http://probe.example/", "GET", 2000, auth = "u1" to "wrong") is TunnelProbe.Result.Failed)
            assertTrue(seen.toString(), "noauth-offered" in seen && "bad-login" in seen)
        } }
    }

    @Test fun okHttpSocksClientAuthenticatesViaLocalProxy() {
        LocalProxy.install(AdvancedSettings(socksUser = "u2", socksPass = "p2"))
        val seen = mutableListOf<String>()
        http204().use { web -> authSocks("u2", "p2", web.localPort, seen).use { ss ->
            SpeedTest.socks(ss.localPort).newCall(Request.Builder().url("http://geo.example/x").build()).execute().use { r ->
                assertEquals(204, r.code)
            }
            assertTrue(seen.toString(), "ok:geo.example" in seen)
            assertTrue(XrayApi.probe(ss.localPort, "http://geo2.example/", 4000) > 0)
        } }
    }
}
