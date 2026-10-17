package net.antibs.client.engine

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.antibs.client.desktop.Engines
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Desktop Xray glue (Android uses libv2ray in-process; here xray.exe runs as a child process — see CoreRunner).
 * Same entry points as on Android: geo assets, certificate pins, throw-away URL test.
 */
object XrayCore {
    fun version(): String = runCatching {
        val (_, out) = net.antibs.client.desktop.run(Engines.file("xray").path, "version", timeoutSec = 5)
        Regex("""Xray (\S+)""").find(out)?.groupValues?.get(1) ?: "?"
    }.getOrDefault("?")

    /** geoip.dat / geosite.dat next to the downloaded routing / block files (asset dir = %APPDATA%\AntiBS2client). */
    @Synchronized
    fun prepareAssets(ctx: Context) {
        for (n in listOf("geoip.dat", "geosite.dat")) {
            val src = File(net.antibs.client.desktop.Paths.engines, n)
            val dst = File(ctx.filesDir, n)
            if (!src.isFile) continue
            if (dst.isFile && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) continue
            runCatching { val tmp = File(ctx.filesDir, "$n.tmp"); src.copyTo(tmp, overwrite = true); tmp.renameTo(dst) || run { dst.delete(); tmp.renameTo(dst) } }
                .onFailure { VpnState.log("geo: не удалось скопировать $n: ${it.message}") }
        }
    }

    /** 1.0.19: «insecure» hosts are pinned to the certificate the server presents right now (TLS only; QUIC → none). */
    fun installCertPins() {
        if (net.antibs.client.data.CertPins.fetcher != null) return
        net.antibs.client.data.CertPins.fetcher = { host, port, sni, quic -> if (quic) null else tlsLeafSha256(host, port, sni) }
    }

    fun tlsLeafSha256(host: String, port: Int, sni: String?): String? = runCatching {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        val raw = java.net.Socket(java.net.Proxy.NO_PROXY).apply { connect(java.net.InetSocketAddress(host, port), 5000); soTimeout = 5000 }
        (ctx.socketFactory.createSocket(raw, sni?.ifBlank { null } ?: host, port, true) as SSLSocket).use { s ->
            val name = sni?.ifBlank { null } ?: host
            if (!name.matches(Regex("""[\d.]+|.*:.*"""))) s.sslParameters = s.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(name)) }
            s.startHandshake()
            val leaf = s.session.peerCertificates.first()
            MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        }.also { VpnState.log("сертификат $host:$port закреплён (небезопасный TLS)") }
    }.onFailure { VpnState.log("сертификат $host:$port: ${it.message}") }.getOrNull()

    private val gate = java.util.concurrent.Semaphore(4)

    /**
     * URL test through a throw-away xray process: the config's inbounds are replaced by one SOCKS inbound on a free
     * port, then [method] [url] is requested through it. [mode] 0 = best of 2, 1 = second of two fresh, 2 = keep-alive
     * (approximated by the second request). -1 = failed.
     */
    fun measureDelayEx(ctx: Context, config: String, url: String, method: String, mode: Int, timeoutMs: Int): Long {
        if (!Engines.available("xray")) return -1
        prepareAssets(ctx)
        gate.acquire()
        var p: Process? = null
        val f = File(net.antibs.client.desktop.Paths.run, "ping-${System.nanoTime()}.json")
        try {
            val port = Engines.freePort()
            val root = kotlinx.serialization.json.Json.parseToJsonElement(config).jsonObject
            val cfg = JsonObject(root.toMutableMap().apply {
                put("log", buildJsonObject { put("loglevel", "none") })
                put("inbounds", JsonArray(listOf(buildJsonObject {
                    put("tag", "socks"); put("listen", "127.0.0.1"); put("port", port); put("protocol", "socks")
                    putJsonObject("settings") { put("auth", "noauth"); put("udp", false) }
                })))
                remove("api"); remove("stats"); remove("metrics")
                // routing rules may reference removed inbounds (api, http, dns-in) — xray ignores unknown inboundTag
            })
            f.writeText(cfg.toString())
            p = Engines.start("xray", listOf("run", "-c", f.path), File(net.antibs.client.desktop.Paths.logs, "ping-xray.log").takeIf { it.length() < 1_000_000 }, mapOf("XRAY_LOCATION_ASSET" to ctx.filesDir.path))
            if (!runBlocking { Engines.waitPort(port, p, 4000) }) return -1
            fun once(): Long = when (val r = TunnelProbe.check(port, url, method, timeoutMs)) { is TunnelProbe.Result.Ok -> r.ms; else -> -1L }
            val a = once()
            if (a < 0) return -1
            val b = once()
            return when (mode) { 1, 2 -> if (b > 0) b else a; else -> if (b > 0) minOf(a, b) else a }
        } catch (e: Exception) {
            return -1
        } finally {
            p?.let { runCatching { it.destroy(); if (!it.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() } }
            f.delete()
            gate.release()
        }
    }
}
