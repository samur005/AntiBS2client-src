package net.antibs.client.data

import java.io.ByteArrayOutputStream

/** 1.0.41: pure helpers for the Wi‑Fi scanner (subnet maths, DNS / mDNS PTR, NetBIOS NBSTAT, OUI). */
object NetScanLogic {
    /** Max hosts we scan: a /22 (1022 addresses). Bigger subnets are cut to the /22 around our address. */
    const val MAX_PREFIX_BITS = 22

    fun ipToInt(ip: String): Int? {
        val p = ip.trim().split('.')
        if (p.size != 4) return null
        var r = 0
        for (x in p) { val n = x.toIntOrNull() ?: return null; if (n !in 0..255) return null; r = (r shl 8) or n }
        return r
    }

    fun intToIp(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    data class Range(val network: Int, val prefix: Int, val first: Int, val last: Int) {
        val count: Int get() = (last.toLong() - first.toLong() + 1).toInt().coerceAtLeast(0)
        val cidr: String get() = "${intToIp(network)}/$prefix"
    }

    /** Hosts to scan for [ip]/[prefix]: network & broadcast excluded, clamped to a /22 around [ip]. */
    fun range(ip: String, prefix: Int): Range? {
        val a = ipToInt(ip) ?: return null
        val pf = prefix.coerceIn(MAX_PREFIX_BITS, 30)
        val mask = if (pf == 0) 0 else (-1 shl (32 - pf))
        val net = a and mask
        val bcast = net or mask.inv()
        return Range(net, pf, net + 1, bcast - 1)
    }

    fun hosts(r: Range): List<String> = (r.first..r.last).map { intToIp(it) }

    /** «4.3.2.1.in-addr.arpa» */
    fun reverseName(ip: String): String = ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"

    private fun u16(o: ByteArrayOutputStream, v: Int) { o.write((v ushr 8) and 255); o.write(v and 255) }

    /** DNS query packet (one question). type 12 = PTR. */
    fun dnsQuery(id: Int, name: String, type: Int = 12, unicastResponse: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        u16(o, id); u16(o, if (unicastResponse) 0 else 0x0100); u16(o, 1); u16(o, 0); u16(o, 0); u16(o, 0)
        for (l in name.trimEnd('.').split('.')) { val b = l.toByteArray(); o.write(b.size); o.write(b) }
        o.write(0); u16(o, type); u16(o, if (unicastResponse) 0x8001 else 1)
        return o.toByteArray()
    }

    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val parts = ArrayList<String>(); var i = start; var end = -1; var hops = 0
        while (i < b.size) {
            val len = b[i].toInt() and 255
            when {
                len == 0 -> { i++; break }
                len and 0xC0 == 0xC0 -> {
                    if (i + 1 >= b.size || ++hops > 16) return parts.joinToString(".") to (if (end >= 0) end else i + 2)
                    if (end < 0) end = i + 2
                    i = ((len and 0x3F) shl 8) or (b[i + 1].toInt() and 255)
                }
                else -> { if (i + 1 + len > b.size) break; parts += String(b, i + 1, len, Charsets.UTF_8); i += 1 + len }
            }
        }
        return parts.joinToString(".") to (if (end >= 0) end else i)
    }

    /** First PTR answer of a DNS / mDNS response, without the trailing dot / «.local». */
    fun parsePtr(b: ByteArray): String? {
        if (b.size < 12) return null
        val qd = ((b[4].toInt() and 255) shl 8) or (b[5].toInt() and 255)
        val an = ((b[6].toInt() and 255) shl 8) or (b[7].toInt() and 255)
        var i = 12
        repeat(qd) { i = readName(b, i).second + 4 }
        repeat(an) {
            if (i >= b.size) return null
            val (_, p) = readName(b, i); i = p
            if (i + 10 > b.size) return null
            val type = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
            val rdlen = ((b[i + 8].toInt() and 255) shl 8) or (b[i + 9].toInt() and 255)
            i += 10
            if (type == 12) return readName(b, i).first.trimEnd('.').removeSuffix(".local").takeIf { it.isNotBlank() }
            i += rdlen
        }
        return null
    }

    /** NetBIOS NBSTAT request («*» name) for UDP 137. */
    fun nbstatQuery(id: Int): ByteArray {
        val o = ByteArrayOutputStream()
        u16(o, id); u16(o, 0); u16(o, 1); u16(o, 0); u16(o, 0); u16(o, 0)
        o.write(32)
        // «*» padded with NULs, first-level encoded
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (c in raw) { val v = c.toInt() and 255; o.write('A'.code + (v ushr 4)); o.write('A'.code + (v and 15)) }
        o.write(0); u16(o, 0x21); u16(o, 1)
        return o.toByteArray()
    }

    /** Workstation name (suffix 0x00, unique) from an NBSTAT response. */
    fun parseNbstat(b: ByteArray): String? {
        if (b.size < 57) return null
        var i = 12
        // answer name: 0x20 + 32 bytes + 0, or a pointer
        i = if ((b[i].toInt() and 0xC0) == 0xC0) i + 2 else i + 34
        i += 10 // type, class, ttl, rdlength
        if (i >= b.size) return null
        val n = b[i].toInt() and 255; i++
        for (k in 0 until n) {
            if (i + 18 > b.size) return null
            val name = String(b, i, 15, Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
            val suffix = b[i + 15].toInt() and 255
            val flags = ((b[i + 16].toInt() and 255) shl 8) or (b[i + 17].toInt() and 255)
            if (suffix == 0 && flags and 0x8000 == 0 && name.isNotBlank()) return name
            i += 18
        }
        return null
    }

    fun normMac(mac: String): String? {
        val h = mac.lowercase().filter { it in "0123456789abcdef" }
        if (h.length != 12 || h == "000000000000") return null
        return h.chunked(2).joinToString(":")
    }

    /** Locally administered (randomized) MAC — vendor unknown by design. */
    fun isRandomMac(mac: String): Boolean = normMac(mac)?.let { (it.substring(0, 2).toInt(16) and 2) != 0 } ?: false

    fun ouiKey(mac: String): String? = normMac(mac)?.replace(":", "")?.substring(0, 6)?.uppercase()

    /** «/proc/net/arp» or «ip neigh» output → ip → mac. */
    fun parseNeighbours(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val macRe = Regex("""([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""")
        for (l in text.lineSequence()) {
            val ip = l.trim().substringBefore(' ').takeIf { ipToInt(it) != null } ?: continue
            if (l.contains("FAILED") || l.contains("INCOMPLETE")) continue
            val mac = macRe.find(l)?.value?.let { normMac(it) } ?: continue
            out[ip] = mac
        }
        return out
    }

    val COMMON_PORTS = listOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "HTTP", 139 to "NetBIOS", 443 to "HTTPS", 445 to "SMB",
        515 to "LPD", 548 to "AFP", 554 to "RTSP", 631 to "IPP", 1883 to "MQTT", 3389 to "RDP", 5000 to "UPnP/NAS", 5001 to "NAS",
        5900 to "VNC", 7000 to "AirPlay", 8000 to "HTTP-alt", 8008 to "Chromecast", 8080 to "HTTP-proxy", 8443 to "HTTPS-alt",
        8888 to "HTTP-alt", 9100 to "Принтер", 32400 to "Plex", 49152 to "UPnP", 62078 to "iPhone",
    )
    /** Ports used to find live hosts quickly (refused = alive too). */
    val PROBE_PORTS = listOf(80, 443, 22, 445, 139, 62078, 8080, 5000, 554, 9100)

    /** Rough device kind from hostname / vendor / ports. */
    fun guessKind(host: String?, vendor: String?, ports: Collection<Int>, isGateway: Boolean): String {
        val h = (host.orEmpty() + " " + vendor.orEmpty()).lowercase()
        return when {
            isGateway -> "Роутер"
            62078 in ports || "iphone" in h || "ipad" in h -> "iPhone/iPad"
            "android" in h || "galaxy" in h || "redmi" in h || "pixel" in h -> "Android"
            9100 in ports || 631 in ports || 515 in ports || "printer" in h || "epson" in h || "canon" in h || "brother" in h -> "Принтер"
            8008 in ports || "chromecast" in h || "tv" in h || "bravia" in h || "lg electronics" in h -> "ТВ / медиа"
            3389 in ports || 445 in ports || 139 in ports -> "Компьютер"
            "synology" in h || "qnap" in h || 5000 in ports -> "NAS"
            554 in ports || "hikvision" in h || "dahua" in h || "camera" in h -> "Камера"
            "apple" in h || "macbook" in h || 548 in ports -> "Apple"
            else -> "Устройство"
        }
    }

    // 2.0.5: guard against probes leaking into a VPN tunnel (every address "answers" the same way)
    data class Probe(val ip: String, val latencyMs: Int?, val via: String, val ports: List<Int>)
    data class Verdict(val fake: Set<String>, val unreliable: Boolean, val reason: String?)

    /** Hosts sharing one identical (via, ports, ~latency) signature across most of the subnet are fake. */
    fun judge(alive: List<Probe>, total: Int, keep: Set<String> = emptySet()): Verdict {
        if (total < 8 || alive.isEmpty()) return Verdict(emptySet(), false, null)
        val groups = alive.groupBy { it.via to it.ports.sorted() }
        val (_, big) = groups.maxByOrNull { it.value.size }!!.toPair()
        val lat = big.mapNotNull { it.latencyMs }
        val flat = lat.size >= 2 && (lat.max() - lat.min()) <= maxOf(6, lat.min() / 5)
        val fake = if (big.size >= maxOf(8, total / 2) && flat) big.map { it.ip }.toSet() - keep else emptySet()
        val left = alive.size - fake.size
        val unreliable = fake.isNotEmpty() || left >= maxOf(8, (total * 0.8).toInt())
        val reason = when {
            fake.isNotEmpty() -> "Отброшено ${fake.size} ложных ответов: почти все адреса отвечают одинаково — похоже, проверки идут через VPN-туннель."
            unreliable -> "Подозрительно много «живых» адресов ($left из $total) — результат может быть недостоверным."
            else -> null
        }
        return Verdict(fake, unreliable, reason)
    }
}
