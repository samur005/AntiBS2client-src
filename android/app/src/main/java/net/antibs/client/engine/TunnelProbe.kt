package net.antibs.client.engine

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 1.0.19: connectivity check through the *running* tunnel. Opens a SOCKS5 connection to the local Xray
 * inbound (127.0.0.1:socksPort — the same inbound hev-socks5-tunnel / every app's traffic goes through),
 * asks it for the ping URL's host by name (DNS is resolved on the far side), does TLS for https and sends one
 * GET/HEAD. Any HTTP status line back = the tunnel works. Pure JVM, no Android APIs (unit-testable).
 */
object TunnelProbe {
    sealed class Result {
        data class Ok(val ms: Long, val status: Int) : Result()
        /** Local SOCKS inbound refused the connection (core not listening there, e.g. a custom config). */
        object NoInbound : Result()
        data class Failed(val reason: String) : Result()
    }

    fun check(socksPort: Int, url: String, method: String, timeoutMs: Int, socksHost: String = "127.0.0.1"): Result {
        val u = try { URI(url) } catch (e: Exception) { return Result.Failed("bad url") }
        val https = u.scheme.equals("https", true)
        val host = u.host ?: return Result.Failed("bad url")
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        val path = (u.rawPath?.ifEmpty { null } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        fun left(): Int = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            try {
                raw.connect(InetSocketAddress(socksHost, socksPort), left().coerceAtMost(3000))
            } catch (e: java.net.ConnectException) {
                return Result.NoInbound
            }
            val t0 = System.nanoTime()
            raw.soTimeout = left()
            socks5Connect(raw.getInputStream(), raw.getOutputStream(), host, port)
            val sock: Socket = if (https) {
                raw.soTimeout = left()
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                ssl.soTimeout = left()
                ssl.startHandshake()
                ssl
            } else raw
            val req = "${method.uppercase()} $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: AntiBS\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            sock.soTimeout = left()
            sock.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
            val line = readLine(sock.getInputStream(), 512) ?: return Result.Failed("no response")
            val m = Regex("""^HTTP/\d(?:\.\d)?\s+(\d{3})""").find(line) ?: return Result.Failed("bad response")
            val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
            try { sock.close() } catch (_: Exception) {}
            return Result.Ok(ms, m.groupValues[1].toInt())
        } catch (e: java.net.SocketTimeoutException) {
            return Result.Failed("timeout")
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            try { raw.close() } catch (_: Exception) {}
        }
    }

    /** RFC 1928 no-auth CONNECT with a domain-name (or literal IP) address. */
    internal fun socks5Connect(ins: InputStream, out: OutputStream, host: String, port: Int) {
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val g = readN(ins, 2)
        if (g[0].toInt() != 5 || g[1].toInt() != 0) throw IllegalStateException("socks auth rejected")
        val h = host.removePrefix("[").removeSuffix("]")
        val addr: ByteArray = when {
            Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h) -> byteArrayOf(1) + h.split('.').map { it.toInt().toByte() }.toByteArray()
            h.contains(':') -> byteArrayOf(4) + java.net.InetAddress.getByName(h).address
            else -> { val b = h.toByteArray(Charsets.US_ASCII); byteArrayOf(3, b.size.toByte()) + b }
        }
        out.write(byteArrayOf(5, 1, 0) + addr + byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
        val r = readN(ins, 4)
        if (r[1].toInt() != 0) throw IllegalStateException("socks connect failed (${r[1]})")
        val skip = when (r[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> readN(ins, 1)[0].toInt() and 0xff; else -> throw IllegalStateException("socks bad reply") }
        readN(ins, skip + 2)
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(b, off, n - off)
            if (r < 0) throw java.io.EOFException("closed")
            off += r
        }
        return b
    }

    private fun readLine(ins: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = ins.read()
            if (c < 0) return sb.toString().ifEmpty { null }
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
