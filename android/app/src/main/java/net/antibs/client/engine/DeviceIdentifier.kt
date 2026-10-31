package net.antibs.client.engine

import android.net.Network
import android.os.SystemClock
import net.antibs.client.data.DeviceIdLogic
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** 1.0.59: multicast discovery (mDNS / SSDP) for the LAN scanner. Blocking — call from Dispatchers.IO. */
object DeviceIdentifier {
    data class Ssdp(val location: String?, val server: String?, val st: String?, val upnp: DeviceIdLogic.Upnp?)

    val trustAll: javax.net.ssl.SSLSocketFactory by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    private inline fun collect(s: DatagramSocket, deadline: Long, onPacket: (String, ByteArray) -> Unit) {
        val buf = ByteArray(9000)
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime(); if (left <= 0) break
            s.soTimeout = left.toInt().coerceAtLeast(1)
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: SocketTimeoutException) { break } catch (_: Exception) { break }
            val ip = p.address?.hostAddress ?: continue
            onPacket(ip, buf.copyOf(p.length))
        }
    }

    /** One‑shot mDNS query (legacy unicast reply) for common service types; two rounds. */
    fun mdns(net: Network?, timeoutMs: Long): Map<String, DeviceIdLogic.MdnsInfo> {
        val acc = HashMap<String, DeviceIdLogic.MdnsInfo>()
        val group = InetAddress.getByName("224.0.0.251")
        val s = DatagramSocket()
        try {
            (net ?: return emptyMap()).bindSocket(s)
            val asked = DeviceIdLogic.MDNS_TYPES.toMutableSet()
            fun send(names: List<String>) = names.chunked(12).forEach { ch ->
                val q = DeviceIdLogic.mdnsQuery(ch); runCatching { s.send(DatagramPacket(q, q.size, group, 5353)) }
            }
            send(DeviceIdLogic.MDNS_TYPES)
            val extra = LinkedHashSet<String>()
            val half = SystemClock.elapsedRealtime() + timeoutMs / 2
            collect(s, half) { ip, b ->
                val recs = DeviceIdLogic.parseRecords(b)
                recs.filterIsInstance<DeviceIdLogic.Rec.Ptr>().filter { it.name.startsWith("_services._dns-sd") }.forEach { if (it.target !in asked) extra += it.target }
                DeviceIdLogic.mergeMdns(acc, ip, recs)
            }
            if (extra.isNotEmpty()) { asked += extra; send(extra.take(24).toList()) }
            collect(s, half + timeoutMs / 2) { ip, b -> DeviceIdLogic.mergeMdns(acc, ip, DeviceIdLogic.parseRecords(b)) }
        } catch (_: Exception) { } finally { s.close() }
        return acc
    }

    /** SSDP M‑SEARCH, then the LOCATION description XML per IP. */
    fun ssdp(net: Network?, timeoutMs: Long): Map<String, Ssdp> {
        val raw = LinkedHashMap<String, Ssdp>()
        val s = DatagramSocket()
        try {
            (net ?: return emptyMap()).bindSocket(s)
            val group = InetAddress.getByName("239.255.255.250")
            for (st in listOf("ssdp:all", "upnp:rootdevice")) { val q = DeviceIdLogic.ssdpSearch(st); runCatching { s.send(DatagramPacket(q, q.size, group, 1900)) } }
            collect(s, SystemClock.elapsedRealtime() + timeoutMs) { ip, b ->
                val h = DeviceIdLogic.headers(String(b, Charsets.UTF_8))
                val old = raw[ip]
                val loc = h["location"]?.takeIf { it.startsWith("http://") && it.contains("//$ip") }
                val st = h["st"] ?: h["nt"]
                // prefer the root device description
                if (old == null || (old.location == null && loc != null) || (st == "upnp:rootdevice" && loc != null))
                    raw[ip] = Ssdp(loc ?: old?.location, h["server"] ?: old?.server, if (st?.startsWith("uuid:") == true) old?.st else st ?: old?.st, null)
            }
        } catch (_: Exception) { } finally { s.close() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        return try {
            val futures = raw.mapValues { (_, v) -> pool.submit<Ssdp> { v.copy(upnp = v.location?.let { fetch(net, it) }?.let { DeviceIdLogic.parseUpnp(it) }) } }
            futures.mapValues { (ip, f) -> runCatching { f.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: raw.getValue(ip) }
        } finally { pool.shutdownNow() }
    }

    private fun fetch(net: Network?, u: String): String? = runCatching {
        val url = java.net.URL(u)
        val c = (net ?: return@runCatching null).openConnection(url) as java.net.HttpURLConnection
        c.connectTimeout = 1200; c.readTimeout = 1500
        try { c.inputStream.use { String(it.readBytes().let { b -> if (b.size > 65536) b.copyOf(65536) else b }, Charsets.UTF_8) } } finally { c.disconnect() }
    }.getOrNull()
}
