package net.antibs.client.data

import java.io.ByteArrayOutputStream

/** 1.0.59: pure helpers for LAN device identification (mDNS records, SSDP, UPnP XML, HTTP, classification). */
object DeviceIdLogic {
    enum class DevType(val label: String) {
        ROUTER("Роутер"), PHONE("Телефон / планшет"), PC("Компьютер"), TV("ТВ / медиа"), PRINTER("Принтер"),
        CAMERA("Камера"), NAS("Сетевое хранилище"), SPEAKER("Умная колонка"), IOT("Умный дом / IoT"), UNKNOWN("Устройство"),
    }

    val MDNS_TYPES = listOf(
        "_services._dns-sd._udp.local", "_airplay._tcp.local", "_googlecast._tcp.local", "_ipp._tcp.local", "_ipps._tcp.local",
        "_printer._tcp.local", "_pdl-datastream._tcp.local", "_hap._tcp.local", "_smb._tcp.local", "_raop._tcp.local",
        "_companion-link._tcp.local", "_device-info._tcp.local", "_spotify-connect._tcp.local", "_workstation._tcp.local",
        "_http._tcp.local", "_afpovertcp._tcp.local", "_sleep-proxy._udp.local", "_androidtvremote2._tcp.local", "_amzn-wplay._tcp.local",
        "_rtsp._tcp.local", "_matter._tcp.local", "_miio._udp.local", "_ssh._tcp.local",
    )

    /** Ports for the quick fingerprint. */
    val FP_PORTS = listOf(9100, 631, 554, 8009, 62078, 445, 139, 5000, 5001, 80, 443, 8080, 22, 53)

    sealed class Rec(val name: String) {
        class Ptr(name: String, val target: String) : Rec(name)
        class Srv(name: String, val target: String, val port: Int) : Rec(name)
        class Txt(name: String, val kv: Map<String, String>) : Rec(name)
        class A(name: String, val ip: String) : Rec(name)
    }

    private fun u16(o: ByteArrayOutputStream, v: Int) { o.write((v ushr 8) and 255); o.write(v and 255) }

    /** One mDNS multicast query packet with several PTR questions. */
    fun mdnsQuery(names: List<String>): ByteArray {
        val o = ByteArrayOutputStream()
        u16(o, 0); u16(o, 0); u16(o, names.size); u16(o, 0); u16(o, 0); u16(o, 0)
        for (n in names) {
            for (l in n.trimEnd('.').split('.')) { val b = l.toByteArray(); o.write(b.size); o.write(b) }
            o.write(0); u16(o, 12); u16(o, 1)
        }
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

    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)

    /** All PTR / SRV / TXT / A records of a DNS packet (answers + authority + additional). */
    fun parseRecords(b: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        if (b.size < 12) return out
        val qd = be16(b, 4); val total = be16(b, 6) + be16(b, 8) + be16(b, 10)
        var i = 12
        try {
            repeat(qd) { i = readName(b, i).second + 4 }
            repeat(total) {
                if (i >= b.size) return out
                val (name, p) = readName(b, i); i = p
                if (i + 10 > b.size) return out
                val type = be16(b, i); val rdlen = be16(b, i + 8); i += 10
                val end = i + rdlen
                if (end > b.size) return out
                when (type) {
                    12 -> out += Rec.Ptr(name, readName(b, i).first)
                    33 -> if (rdlen >= 7) out += Rec.Srv(name, readName(b, i + 6).first, be16(b, i + 4))
                    16 -> {
                        val kv = LinkedHashMap<String, String>(); var j = i
                        while (j < end) { val l = b[j].toInt() and 255; if (j + 1 + l > end) break
                            val s = String(b, j + 1, l, Charsets.UTF_8); j += 1 + l
                            if (s.isNotEmpty()) kv[s.substringBefore('=').lowercase()] = s.substringAfter('=', "") }
                        out += Rec.Txt(name, kv)
                    }
                    1 -> if (rdlen == 4) out += Rec.A(name, (0..3).joinToString(".") { k -> (b[i + k].toInt() and 255).toString() })
                }
                i = end
            }
        } catch (_: Exception) { }
        return out
    }

    data class MdnsInfo(val name: String? = null, val model: String? = null, val services: Set<String> = emptySet())

    /** Service type label: «_airplay._tcp.local» → «airplay». */
    fun serviceOf(n: String): String? {
        val parts = n.split('.')
        val idx = parts.indexOfFirst { it == "_tcp" || it == "_udp" }
        return if (idx >= 1) parts[idx - 1].removePrefix("_") else null
    }

    /** Fold records (from one sender [srcIp]) into per‑IP info. */
    fun mergeMdns(acc: MutableMap<String, MdnsInfo>, srcIp: String, recs: List<Rec>) {
        val hostIp = HashMap<String, String>()
        recs.filterIsInstance<Rec.A>().forEach { hostIp[it.name.lowercase()] = it.ip }
        val instIp = HashMap<String, String>()
        recs.filterIsInstance<Rec.Srv>().forEach { s -> instIp[s.name.lowercase()] = hostIp[s.target.lowercase()] ?: srcIp }
        fun ipOf(inst: String) = instIp[inst.lowercase()] ?: srcIp
        fun upd(ip: String, f: (MdnsInfo) -> MdnsInfo) { acc[ip] = f(acc[ip] ?: MdnsInfo()) }
        for (r in recs) when (r) {
            is Rec.Ptr -> {
                if (r.name.startsWith("_services._dns-sd")) continue
                val svc = serviceOf(r.name) ?: continue
                val inst = r.target.substringBefore("._").takeIf { it.isNotBlank() && !r.target.endsWith("in-addr.arpa") }
                upd(ipOf(r.target)) { it.copy(services = it.services + svc, name = it.name ?: inst?.let(::cleanInstance)) }
            }
            is Rec.Txt -> {
                val model = r.kv["model"] ?: r.kv["md"] ?: r.kv["ty"] ?: r.kv["usb_mdl"]?.let { m -> listOfNotNull(r.kv["usb_mfg"], m).joinToString(" ") } ?: r.kv["am"]
                val fn = r.kv["fn"]
                val svc = serviceOf(r.name)
                upd(ipOf(r.name)) { it.copy(model = it.model ?: model?.takeIf { m -> m.isNotBlank() }, name = fn ?: it.name,
                    services = if (svc != null) it.services + svc else it.services) }
            }
            is Rec.A -> upd(r.ip) { it.copy(name = it.name ?: r.name.removeSuffix(".local").takeIf { n -> n.isNotBlank() }) }
            else -> {}
        }
    }

    /** Google cast instance names look like «Chromecast-abc123…»; AirPlay «AA:BB:..@Name». */
    fun cleanInstance(s: String): String = s.substringAfter('@').replace(Regex("-[0-9a-f]{20,}$"), "").trim()

    /** SSDP M‑SEARCH request. */
    fun ssdpSearch(st: String = "ssdp:all"): ByteArray =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $st\r\n\r\n".toByteArray()

    /** Header map (lower‑case keys) of an SSDP / HTTP response. */
    fun headers(text: String): Map<String, String> = text.lineSequence().drop(1).mapNotNull { l ->
        val c = l.indexOf(':'); if (c <= 0) null else l.substring(0, c).trim().lowercase() to l.substring(c + 1).trim()
    }.toMap()

    data class Upnp(val friendlyName: String?, val manufacturer: String?, val modelName: String?, val deviceType: String?)

    private fun tag(xml: String, t: String): String? =
        Regex("<(?:\\w+:)?$t>\\s*([^<]*?)\\s*</(?:\\w+:)?$t>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }

    fun parseUpnp(xml: String) = Upnp(tag(xml, "friendlyName"), tag(xml, "manufacturer"), tag(xml, "modelName"), tag(xml, "deviceType"))

    fun htmlTitle(html: String): String? =
        Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.let(::unescape)?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.takeIf { it.isNotBlank() }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    data class Evidence(
        val isGateway: Boolean = false,
        val ports: Collection<Int> = emptyList(),
        val host: String? = null,
        val vendor: String? = null,
        val mdns: MdnsInfo? = null,
        val upnp: Upnp? = null,
        val upnpType: String? = null,
        val netbios: String? = null,
        val httpTitle: String? = null,
        val httpServer: String? = null,
    )

    private fun has(h: String, vararg w: String) = w.any { it in h }

    /** Combine all evidence into a device type. */
    fun classify(e: Evidence): DevType {
        if (e.isGateway) return DevType.ROUTER
        val svc = e.mdns?.services.orEmpty()
        val p = e.ports
        val h = listOfNotNull(e.host, e.vendor, e.mdns?.name, e.mdns?.model, e.upnp?.friendlyName, e.upnp?.manufacturer, e.upnp?.modelName,
            e.upnp?.deviceType, e.upnpType, e.netbios, e.httpTitle, e.httpServer).joinToString(" ").lowercase()
        return when {
            has(h, "internetgatewaydevice", "wandevice", "openwrt", "keenetic", "mikrotik", "routeros", "tp-link router", "router", "роутер", "asuswrt", "zyxel", "netgear", "fritz!box") -> DevType.ROUTER
            "ipp" in svc || "ipps" in svc || "printer" in svc || "pdl-datastream" in svc || 9100 in p || 631 in p ||
                has(h, "printer", "laserjet", "deskjet", "officejet", "epson", "canon", "brother", "kyocera", "xerox", "pantum", "mfp") -> DevType.PRINTER
            has(h, "homepod", "sonos", "echo", "alexa", "yandex station", "яндекс станция", "станция", "smart speaker", "google home", "nest audio", "nest mini", "marusia", "маруся") -> DevType.SPEAKER
            "googlecast" in svc || 8009 in p || "androidtvremote2" in svc || "amzn-wplay" in svc ||
                has(h, "chromecast", "bravia", "smart tv", "smarttv", "webos", "tizen", "android tv", "apple tv", "appletv", "roku", "fire tv", "mediarenderer", "kodi", "plex", "телевизор", " tv") -> DevType.TV
            has(h, "synology", "diskstation", "qnap", "nas", "truenas", "freenas", "unraid", "asustor", "terramaster", "wd my cloud", "mycloud") -> DevType.NAS
            554 in p || "rtsp" in svc || has(h, "hikvision", "dahua", "camera", "ipcam", "камера", "ezviz", "reolink", "imou", "nvr", "dvr") -> DevType.CAMERA
            62078 in p || "companion-link" in svc || has(h, "iphone", "ipad", "android", "galaxy", "redmi", "pixel", "xiaomi", "huawei", "honor", "oneplus", "poco", "realme", "oppo", "vivo", "phone") -> DevType.PHONE
            "hap" in svc || "matter" in svc || "miio" in svc || has(h, "espressif", "esp32", "esp8266", "tuya", "shelly", "sonoff", "yeelight", "philips hue", "hue bridge", "aqara", "lumi", "tasmota", "home assistant", "zigbee", "broadlink") -> DevType.IOT
            5000 in p || 5001 in p -> DevType.NAS
            e.netbios != null || "smb" in svc || "workstation" in svc || "afpovertcp" in svc || 445 in p || 139 in p || 3389 in p ||
                has(h, "macbook", "imac", "mac mini", "windows", "desktop", "laptop", "notebook", "ubuntu", "linux pc") -> DevType.PC
            "airplay" in svc || "raop" in svc -> DevType.TV
            53 in p -> DevType.ROUTER
            else -> DevType.UNKNOWN
        }
    }

    /** Best display name and «manufacturer · model» from the evidence. */
    fun bestName(e: Evidence): String? = e.upnp?.friendlyName ?: e.mdns?.name ?: e.netbios ?: e.host

    fun makeModel(e: Evidence): String? {
        val mf = e.upnp?.manufacturer ?: e.vendor?.takeIf { it != "Случайный MAC" }
        val md = e.upnp?.modelName ?: e.mdns?.model
        return listOfNotNull(mf, md).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
    }
}
