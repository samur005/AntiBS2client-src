package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull

/** WireGuard parameters handed out by the qWDTT server ([Interface]/[Peer] block printed by the Go client). */
data class WgConfig(
    val privateKey: String,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val publicKey: String,
    val endpoint: String,
    val keepAlive: Int,
    val preSharedKey: String? = null,
) {
    companion object {
        fun parse(text: String): WgConfig? {
            var pk = ""; var addr = listOf<String>(); var dns = listOf<String>(); var mtu = 1280
            var pub = ""; var ep = ""; var ka = 25; var psk: String? = null
            for (raw in text.lines()) {
                val line = raw.trim().trim('║').trim()
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val k = line.substring(0, eq).trim().lowercase()
                val v = line.substring(eq + 1).trim()
                when (k) {
                    "privatekey" -> pk = v
                    "address" -> addr = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "dns" -> dns = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "mtu" -> mtu = v.toIntOrNull() ?: mtu
                    "publickey" -> pub = v
                    "endpoint" -> ep = v
                    "persistentkeepalive" -> ka = v.toIntOrNull() ?: ka
                    "presharedkey" -> psk = v
                }
            }
            if (pk.isEmpty() || pub.isEmpty() || ep.isEmpty() || addr.isEmpty()) return null
            return WgConfig(pk, addr, dns, mtu, pub, ep, ka, psk)
        }
    }
}

object XrayConfigBuilder {
    const val SOCKS_PORT = 10808
    val prettyJson = Json { prettyPrint = true }

    private val privateCidrs = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "224.0.0.0/4", "255.255.255.255/32", "fc00::/7", "fe80::/10", "::1/128",
    )

    private fun csv(s: String?): List<String> = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun isIp(s: String): Boolean =
        s.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || s.contains(':')

    /** Collects the sing-box profiles one Xray config needs (each gets its own loopback SOCKS slot). */
    class SingBoxSlots {
        val slots = mutableListOf<SingBoxConfigBuilder.Slot>()
        fun add(p: XrayProfile, bridgePort: Int? = null): Int {
            val s = SingBoxConfigBuilder.Slot(slots.size, p, bridgePort)
            slots += s
            return s.port
        }
    }

    /** What the VPN service must start: the Xray JSON plus, if needed, the sing-box JSON it dials into. */
    data class Plan(
        val xray: String,
        val singBox: String?,
        val singBoxSlots: List<SingBoxConfigBuilder.Slot> = emptyList(),
        /** Each pair is (client JSON, socks port). */
        val mieruInstances: List<Pair<String, Int>> = emptyList(),
        /** libmhx.so config (mihomo outbounds + HTTP/3 CONNECT), null when unused. */
        val mihomo: String? = null,
        val mihomoPorts: List<Int> = emptyList(),
        /** 1.0.41: libolcrtc.so instances — (profile, socks port). */
        val olcInstances: List<Pair<XrayProfile, Int>> = emptyList(),
        /** 1.0.41: libvpnstack.so instances (OpenVPN / OpenConnect) — (profile, socks port). */
        val vpnInstances: List<Pair<XrayProfile, Int>> = emptyList(),
    ) {
        val mieru: String? get() = mieruInstances.firstOrNull()?.first
        val mieruPort: Int? get() = mieruInstances.firstOrNull()?.second
        val engineLabel: String get() = buildList {
            add("Xray")
            if (singBox != null) add("sing-box")
            if (mieruInstances.isNotEmpty()) add("mieru")
            if (mihomo != null) add("mihomo")
            if (olcInstances.isNotEmpty()) add("olcRTC")
            vpnInstances.map { if (it.first.protocol == OpenVpnConfig.PROTOCOL) "OpenVPN" else "OpenConnect" }.distinct().forEach { add(it) }
        }.joinToString(" + ")
    }

    /** Xray side of a sing-box profile: a plain socks outbound to its loopback slot. */
    fun singBoxHop(tag: String, port: Int, user: String = "", pass: String = ""): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", "socks")
        putJsonObject("settings") {
            put("address", "127.0.0.1"); put("port", port); put("level", 8)
            if (user.isNotEmpty() && pass.isNotEmpty()) { put("user", user); put("pass", pass) }
        }
        putJsonObject("mux") { put("enabled", false); put("concurrency", -1) }
    }

    /**
     * Outbound for one leaf profile. sing-box protocols need [sb]; without it they get slot 0
     * (enough for single-profile configs and tests).
     */
    /** Collects mieru profiles that need libmieru.so (one SOCKS port each). */
    class MieruSlots {
        val slots = mutableListOf<Pair<XrayProfile, Int>>() // profile to socks port
        fun add(p: XrayProfile): Int {
            val port = MieruConfigBuilder.BASE_PORT + slots.size
            slots += p to port
            return port
        }
    }

    /** 1.0.41: olcRTC profiles for libolcrtc.so, one process + SOCKS port each. */
    class OlcSlots(private val base: Int = Olcrtc.BASE_PORT) {
        val slots = mutableListOf<Pair<XrayProfile, Int>>()
        fun add(p: XrayProfile): Int {
            slots.firstOrNull { it.first == p }?.let { return it.second }
            val port = base + slots.size
            slots += p to port
            return port
        }
    }

    /** Collects profiles for libmhx.so (mihomo outbounds + HTTP/3 CONNECT), one SOCKS port each. */
    class MihomoSlots {
        val slots = mutableListOf<MihomoConfigBuilder.Slot>()
        fun add(p: XrayProfile): Int {
            val s = MihomoConfigBuilder.Slot(slots.size, p)
            slots += s
            return s.port
        }
    }

    /** True when [p] is dialed by a helper process (sing-box / mieru / mihomo) behind a loopback SOCKS. */
    fun isHelper(p: XrayProfile): Boolean = ProtocolSupport.engineOf(p) != ProtocolSupport.ENGINE_XRAY

    fun outbound(
        p: XrayProfile, tag: String = "proxy", opt: Options = Options(), sb: SingBoxSlots? = null, bridgePort: Int? = null,
        mieru: MieruSlots? = null, mhx: MihomoSlots? = null, olc: OlcSlots? = null,
    ): JsonObject = when (ProtocolSupport.engineOf(p)) {
        ProtocolSupport.ENGINE_OLCRTC -> singBoxHop(tag, (olc ?: currentOlc.get() ?: OlcSlots()).add(p))
        ProtocolSupport.ENGINE_VPNSTACK -> singBoxHop(tag, (currentVpn.get() ?: OlcSlots(VPN_BASE_PORT)).add(p))
        ProtocolSupport.ENGINE_SINGBOX -> singBoxHop(tag, (sb ?: SingBoxSlots()).add(p, bridgePort), opt.socksUser, opt.socksPass)
        ProtocolSupport.ENGINE_MIERU -> singBoxHop(tag, (mieru ?: MieruSlots()).add(p))
        ProtocolSupport.ENGINE_MIHOMO -> singBoxHop(tag, (mhx ?: MihomoSlots()).add(p))
        else -> xrayOutbound(p, tag, opt)
    }

    private fun xrayOutbound(p: XrayProfile, tag: String, opt: Options): JsonObject = buildJsonObject {
        put("tag", tag)
        when (p.protocol) {
            "vless" -> {
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        add(buildJsonObject {
                            put("address", p.server); put("port", p.port)
                            putJsonArray("users") {
                                add(buildJsonObject {
                                    put("id", p.id ?: ""); put("encryption", p.encryption ?: "none"); put("level", 8)
                                    if (!p.flow.isNullOrEmpty()) put("flow", p.flow)
                                })
                            }
                        })
                    }
                }
            }
            "vmess" -> {
                put("protocol", "vmess")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        add(buildJsonObject {
                            put("address", p.server); put("port", p.port)
                            putJsonArray("users") {
                                add(buildJsonObject {
                                    put("id", p.id ?: ""); put("security", p.method ?: "auto"); put("level", 8)
                                })
                            }
                        })
                    }
                }
            }
            "trojan" -> {
                put("protocol", "trojan")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        add(buildJsonObject {
                            put("address", p.server); put("port", p.port); put("password", p.password ?: ""); put("level", 8)
                        })
                    }
                }
            }
            "shadowsocks" -> {
                put("protocol", "shadowsocks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        add(buildJsonObject {
                            put("address", p.server); put("port", p.port)
                            put("method", p.method ?: "aes-256-gcm"); put("password", p.password ?: ""); put("level", 8)
                        })
                    }
                }
            }
            "hysteria" -> {
                put("protocol", "hysteria")
                putJsonObject("settings") {
                    put("version", 2)
                    put("address", p.server)
                    put("port", p.port)
                }
            }
            "socks" -> {
                put("protocol", "socks")
                putJsonObject("settings") {
                    put("address", p.server); put("port", p.port); put("level", 8)
                    if (!p.username.isNullOrEmpty()) {
                        put("user", p.username)
                        put("pass", p.password ?: "")
                    }
                }
            }
            "http", "https" -> {
                put("protocol", "http")
                putJsonObject("settings") {
                    put("address", p.server); put("port", p.port); put("level", 8)
                    if (!p.username.isNullOrEmpty()) {
                        put("user", p.username)
                        put("pass", p.password ?: "")
                    }
                }
            }
            "wireguard" -> {
                put("protocol", "wireguard")
                putJsonObject("settings") {
                    put("secretKey", p.secretKey ?: "")
                    putJsonArray("address") { csv(p.localAddress).ifEmpty { listOf("172.16.0.2/32") }.forEach { add(it) } }
                    putJsonArray("peers") {
                        add(buildJsonObject {
                            put("publicKey", p.publicKey ?: "")
                            if (!p.preSharedKey.isNullOrEmpty()) put("preSharedKey", p.preSharedKey)
                            val host = if (p.server.contains(':')) "[${p.server}]" else p.server
                            put("endpoint", "$host:${p.port}")
                            putJsonArray("allowedIPs") { add("0.0.0.0/0"); add("::/0") }
                        })
                    }
                    p.mtu?.let { put("mtu", it) }
                    csv(p.reserved).mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.let { r ->
                        putJsonArray("reserved") { r.forEach { add(it) } }
                    }
                    put("noKernelTun", true)
                    put("domainStrategy", "ForceIPv4")
                }
            }
        }
        if (p.protocol != "wireguard") put("streamSettings", streamSettings(p, opt))
        // XTLS Vision rejects mux; leave it off for those profiles even if the user enabled it.
        val allowMux = opt.mux && p.flow?.contains("vision", ignoreCase = true) != true && p.protocol !in setOf("wireguard", "hysteria", "socks", "http", "https", "mieru", "http3")
        putJsonObject("mux") {
            put("enabled", allowMux)
            if (allowMux) {
                put("concurrency", opt.adv.muxConcurrency.coerceIn(-1, 1024))
                put("xudpConcurrency", opt.adv.muxXudpConcurrency.coerceIn(-1, 1024))
                put("xudpProxyUDP443", opt.adv.muxXudpQuic.takeIf { it in setOf("reject", "allow", "skip") } ?: "reject")
            } else put("concurrency", -1)
        }
    }

    fun streamSettings(p: XrayProfile, opt: Options = Options()): JsonObject = buildJsonObject {
        val network = when {
            p.protocol == "hysteria" -> "hysteria"
            p.protocol == "shadowsocks" && p.headerType != "http" -> "tcp"
            else -> p.network
        }
        put("network", network)
        var sniFromTransport: String? = null
        val tcpMasks = mutableListOf<JsonObject>()
        val udpMasks = mutableListOf<JsonObject>()
        var quicParams: JsonObject? = null
        when (network) {
            "tcp" -> putJsonObject("tcpSettings") {
                if (p.headerType == "http") {
                    putJsonObject("header") {
                        put("type", "http")
                        putJsonObject("request") {
                            put("version", "1.1"); put("method", "GET")
                            putJsonArray("path") { csv(p.path).ifEmpty { listOf("/") }.forEach { add(it) } }
                            putJsonObject("headers") {
                                val hosts = csv(p.host)
                                if (hosts.isNotEmpty()) putJsonArray("Host") { hosts.forEach { add(it) } }
                                putJsonArray("User-Agent") { add("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36") }
                                putJsonArray("Accept-Encoding") { add("gzip, deflate") }
                                putJsonArray("Connection") { add("keep-alive") }
                                put("Pragma", "no-cache")
                            }
                        }
                    }
                    sniFromTransport = csv(p.host).firstOrNull()
                } else {
                    putJsonObject("header") { put("type", "none") }
                    sniFromTransport = p.host
                }
            }
            "ws" -> putJsonObject("wsSettings") {
                put("path", p.path ?: "/")
                if (!p.host.isNullOrEmpty()) put("host", p.host)
                sniFromTransport = p.host
            }
            "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                put("path", p.path ?: "/")
                if (!p.host.isNullOrEmpty()) put("host", p.host)
                sniFromTransport = p.host
            }
            "xhttp" -> putJsonObject("xhttpSettings") {
                put("path", p.path ?: "/")
                if (!p.host.isNullOrEmpty()) put("host", p.host)
                put("mode", p.mode ?: "auto")
                val extra = p.extra?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                if (extra is JsonObject) put("extra", extra)
                sniFromTransport = p.host
            }
            "grpc" -> putJsonObject("grpcSettings") {
                put("serviceName", p.serviceName ?: p.path ?: "")
                if (!p.authority.isNullOrEmpty()) put("authority", p.authority)
                put("multiMode", p.mode == "multi")
                put("idle_timeout", 60); put("health_check_timeout", 20)
                sniFromTransport = p.authority
            }
            "h2" -> putJsonObject("xhttpSettings") {
                // legacy HTTP/2 transport was removed from Xray; map to XHTTP stream-one over h2
                put("path", p.path ?: "/")
                if (!p.host.isNullOrEmpty()) put("host", csv(p.host).first())
                put("mode", "stream-one")
                sniFromTransport = csv(p.host).firstOrNull()
            }
            "kcp" -> putJsonObject("kcpSettings") { }
            "hysteria" -> {
                putJsonObject("hysteriaSettings") {
                    put("version", 2)
                    put("auth", p.password ?: "")
                }
                if (!p.obfsPassword.isNullOrEmpty()) {
                    udpMasks += buildJsonObject {
                        put("type", "salamander")
                        putJsonObject("settings") { put("password", p.obfsPassword) }
                    }
                }
                if (!p.portHopping.isNullOrEmpty()) {
                    udpMasks += buildJsonObject {
                        put("type", "udphop")
                        putJsonObject("settings") {
                            put("mode", "intervalremote")
                            put("interval", "30")
                            put("remotePorts", p.portHopping)
                        }
                    }
                }
                if (!p.bandwidthUp.isNullOrEmpty() || !p.bandwidthDown.isNullOrEmpty()) {
                    quicParams = buildJsonObject {
                        put("congestion", "brutal")
                        p.bandwidthUp?.let { put("brutalUp", it) }
                        p.bandwidthDown?.let { put("brutalDown", it) }
                    }
                }
            }
        }
        if (network == "h2") put("network", "xhttp")
        val sec = p.security
        if (sec == "tls" || sec == "reality") {
            put("security", sec)
            val sni = p.sni?.ifEmpty { null }
                ?: sniFromTransport?.takeIf { it.isNotEmpty() && !isIp(it) }
                ?: p.server.takeIf { !isIp(it) }
            val obj = buildJsonObject {
                if (sni != null) put("serverName", sni)
                if (!(p.protocol == "hysteria" && p.fingerprint.isNullOrEmpty())) put("fingerprint", p.fingerprint ?: "chrome")
                // 1.0.19: Xray 26 has no allowInsecure — an insecure host is pinned to its current certificate.
                val pin = p.pinnedPeerCertSha256?.ifEmpty { null }
                    ?: if (sec == "tls" && p.insecure) CertPins.pinFor(p.server, p.port, sni, quic = p.protocol == "hysteria") else null
                if (pin != null) put("pinnedPeerCertSha256", pin)
                if (sec == "tls") {
                    // 1.0.19: WebSocket / HTTPUpgrade can't run over h2 — offering "h2" made servers that support
                    // it (nginx/caddy) pick HTTP/2 and the WS dial failed ("protocol h2 was given but is not supported").
                    val alpn = csv(p.alpn).let { a -> if (network in setOf("ws", "httpupgrade")) a.filter { it != "h2" } else a }
                    if (alpn.isNotEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
                } else {
                    put("publicKey", p.publicKey ?: "")
                    put("shortId", p.shortId ?: "")
                    if (!p.spiderX.isNullOrEmpty()) put("spiderX", p.spiderX)
                }
            }
            put(if (sec == "tls") "tlsSettings" else "realitySettings", obj)
        }
        // Fragment (TCP) / noise (UDP) — Xray finalmask, same layout as v2rayNG/Exclave. REALITY gets packets "1-3".
        val tcpBased = network in setOf("tcp", "ws", "httpupgrade", "grpc", "xhttp", "h2")
        if (opt.fragmentation && tcpBased && (sec == "tls" || sec == "reality")) {
            tcpMasks += fragmentMask(opt.adv, reality = sec == "reality")
        }
        if (opt.adv.noise && network in setOf("hysteria", "kcp") && udpMasks.isEmpty()) {
            noiseMask(opt.adv)?.let { udpMasks += it }
        }
        if (tcpMasks.isNotEmpty() || udpMasks.isNotEmpty() || quicParams != null) {
            putJsonObject("finalmask") {
                if (tcpMasks.isNotEmpty()) put("tcp", JsonArray(tcpMasks))
                if (udpMasks.isNotEmpty()) put("udp", JsonArray(udpMasks))
                quicParams?.let { put("quicParams", it) }
            }
        }
    }

    fun fragmentMask(a: AdvancedSettings, reality: Boolean = false): JsonObject = buildJsonObject {
        put("type", "fragment")
        putJsonObject("settings") {
            val packets = a.fragPackets.trim().ifEmpty { "tlshello" }
            put("packets", if (reality && packets == "tlshello") "1-3" else packets)
            put("length", rangeOr(a.fragLength, "50-100"))
            put("delay", rangeOr(a.fragDelay, "10-20"))
            put("maxSplit", rangeOr(a.fragMaxSplit, "100-200"))
        }
    }

    private fun rangeOr(v: String, def: String): String = parseRange(v)?.let { (a, b) -> if (a == b) "$a" else "$a-$b" } ?: def

    /** "1A,2B,3C" (array of hex bytes) → [26,43,60]; hex/str/base64 stay strings. */
    fun noisePacket(type: String, raw: String): JsonElement? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return when (type.lowercase()) {
            "array" -> {
                val bytes = t.split(',', ' ', ';').map { it.trim().removePrefix("0x").removePrefix("0X") }.filter { it.isNotEmpty() }
                    .map { it.toIntOrNull(16) ?: return null }.filter { it in 0..255 }
                if (bytes.isEmpty()) null else JsonArray(bytes.map { JsonPrimitive(it) })
            }
            "hex" -> t.replace(" ", "").takeIf { it.length % 2 == 0 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }?.let { JsonPrimitive(it) }
            else -> JsonPrimitive(t)
        }
    }

    /** Noise items: one for the fixed packet, one for the random block (Xray forbids both in a single item). */
    fun noiseMask(a: AdvancedSettings): JsonObject? {
        val items = mutableListOf<JsonObject>()
        val delay = rangeOr(a.noiseDelay, "10-16")
        noisePacket(a.noiseType, a.noisePacket)?.let { pkt ->
            items += buildJsonObject {
                put("type", a.noiseType.lowercase().takeIf { it in setOf("array", "hex", "str", "base64") } ?: "array")
                put("packet", pkt)
                put("delay", delay)
            }
        }
        parseRange(a.noiseRand)?.takeIf { it.second > 0 }?.let { (lo, hi) ->
            items += buildJsonObject {
                put("rand", if (lo == hi) "$lo" else "$lo-$hi")
                put("randRange", rangeOr(a.noiseRandRange, "0-255").let { r -> parseRange(r)?.let { (x, y) -> "${x.coerceIn(0, 255)}-${y.coerceIn(0, 255)}" } ?: "0-255" })
                put("delay", delay)
            }
        }
        if (items.isEmpty()) return null
        return buildJsonObject {
            put("type", "noise")
            putJsonObject("settings") { put("noise", JsonArray(items)) }
        }
    }

    /** Outbound for the qWDTT engine: WireGuard to the local qWDTT TURN listener. */
    fun qwdttOutbound(wg: WgConfig, tag: String = "proxy"): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", "wireguard")
        putJsonObject("settings") {
            put("secretKey", wg.privateKey)
            putJsonArray("address") { wg.addresses.forEach { add(it) } }
            putJsonArray("peers") {
                add(buildJsonObject {
                    put("publicKey", wg.publicKey)
                    wg.preSharedKey?.let { put("preSharedKey", it) }
                    put("endpoint", wg.endpoint)
                    put("keepAlive", wg.keepAlive)
                    putJsonArray("allowedIPs") { add("0.0.0.0/0") }
                })
            }
            put("mtu", wg.mtu)
            put("noKernelTun", true)
            put("domainStrategy", "ForceIPv4")
        }
    }

    data class Options(
        val socksPort: Int = SOCKS_PORT,
        val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
        val bypassLan: Boolean = true,
        val logLevel: String = "warning",
        val sniffing: Boolean = true,
        val mux: Boolean = false,
        val fragmentation: Boolean = false,
        /** auto (same DNS strategy as 1.0.0) | ipv4 | ipv6 */
        val ipType: String = "auto",
        /** SOCKS inbound on 0.0.0.0 instead of loopback. */
        val allowLan: Boolean = false,
        /** 1.0.6 tunnel/VPN/routing settings. */
        val adv: AdvancedSettings = AdvancedSettings(),
        /** Xray's own TUN inbound (fd passed via xray.tun.fd) instead of hev-socks5-tunnel. */
        val xrayTun: Boolean = false,
        /** Directory for xray-error.log / xray-access.log (Логи screen tails them). */
        val logDir: String? = null,
        /** Resolve DNS over TCP (leaf protocol can't carry UDP). */
        val dnsTcp: Boolean = false,
        val routing: RoutingProfile? = null,
        /** package → uid, for «process» routing rules (Xray TUN mode only). */
        val packageUids: Map<String, Int> = emptyMap(),
        /** Outbound tagged [ROUTING_PROXY_TAG] that «Прокси» rules use (routing profile with assigned servers). */
        val routingProxyOutbound: JsonObject? = null,
        /** 1.0.19: tags of the stock geoip.dat/geosite.dat ("ip"/"site"); unknown refs are dropped. null = don't check. */
        val geoBundled: Map<String, Set<String>>? = null,
        /** 1.0.40: tags present in the downloaded rf files ("site"/"ip"). */
        val geoExtra: Map<String, Set<String>> = emptyMap(),
        /** 1.0.34: >0 → Xray API (RoutingService / ObservatoryService / StatsService) on 127.0.0.1:[apiPort]. */
        val apiPort: Int = 0,
        /** 1.0.34: host ids left out of the app's own auto-select balancer («Исключить из автовыбора»). */
        val autoExclude: Set<String> = emptySet(),
        /** 2.0.43: SOCKS / HTTP inbound login + password (both set → auth "password"; empty → noauth as before). */
        val socksUser: String = "",
        val socksPass: String = "",
        /** 2.0.43: outbound tagged [RU_VIA_TAG] for «.RU без VPN» traffic (null = direct). */
        val ruViaOutbound: JsonObject? = null,
    ) {
        val socksAuth: Boolean get() = socksUser.isNotEmpty() && socksPass.isNotEmpty()
    }

    const val RU_VIA_TAG = "ru-via"

    /** Xray socks inbound settings: RFC 1929 account when [Options.socksAuth], else noauth. */
    fun socksInboundSettings(opt: Options, udp: Boolean): JsonObject = buildJsonObject {
        if (opt.socksAuth) {
            put("auth", "password")
            putJsonArray("accounts") { add(buildJsonObject { put("user", opt.socksUser); put("pass", opt.socksPass) }) }
        } else put("auth", "noauth")
        put("udp", udp); put("userLevel", 8)
    }

    const val ROUTING_PROXY_TAG = "rp-proxy"

    private val domainPrefixes = listOf("geosite:", "domain:", "full:", "regexp:", "keyword:", "ext:", "dotless:")

    fun domainValue(v: String): String {
        val t = v.trim()
        if (domainPrefixes.any { t.startsWith(it, ignoreCase = true) }) return t
        if (t.startsWith("*.")) return "domain:" + t.drop(2)
        if (t.startsWith(".")) return "domain:" + t.drop(1)
        return "domain:$t"
    }

    /** Xray routing rules for one profile (block → proxy → direct; final catch-all when GlobalProxy=false). */
    fun routingRules(r: RoutingProfile, opt: Options, proxyTarget: Pair<String, String>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun target(action: String): Pair<String, String> = when (action) {
            "block" -> "outboundTag" to "block"
            "direct" -> "outboundTag" to "direct"
            else -> proxyTarget
        }
        for (action in listOf("block", "proxy", "direct")) {
            val enabled = when (action) { "block" -> r.blockEnabled; "direct" -> r.directEnabled; else -> r.proxyEnabled }
            if (!enabled) continue
            val rs = r.rules.filter { it.action == action }
            val (k, v) = target(action)
            val domains = rs.filter { it.kind == "domain" }.map { domainValue(it.value) }.filter { it.length > 7 }
                .mapNotNull { GeoDat.resolve(it, r, opt.geoBundled) }.distinct()
            val ips = rs.filter { it.kind == "ip" }.map { it.value.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { GeoDat.resolve(it, r, opt.geoBundled) }.distinct()
            val ports = rs.filter { it.kind == "port" }.map { it.value.trim().removePrefix("port:") }.filter { it.isNotEmpty() }
            val uids = if (!opt.xrayTun) emptyList() else rs.filter { it.kind == "process" }.mapNotNull {
                val pkg = it.value.trim().substringAfter(':', it.value.trim())
                pkg.toIntOrNull()?.toString() ?: opt.packageUids[pkg]?.toString()
            }.distinct()
            if (domains.isNotEmpty()) out += buildJsonObject { put("type", "field"); put("domain", JsonArray(domains.map { JsonPrimitive(it) })); put(k, v) }
            if (ips.isNotEmpty()) out += buildJsonObject { put("type", "field"); put("ip", JsonArray(ips.map { JsonPrimitive(it) })); put(k, v) }
            if (ports.isNotEmpty()) out += buildJsonObject { put("type", "field"); put("port", ports.joinToString(",")); put(k, v) }
            if (uids.isNotEmpty()) out += buildJsonObject { put("type", "field"); put("process", JsonArray(uids.map { JsonPrimitive(it) })); put(k, v) }
        }
        if (!r.globalProxy) out += buildJsonObject { put("type", "field"); put("network", "tcp,udp"); put("outboundTag", "direct") }
        return out
    }

    /** 1.0.40: RU preset → Yandex DNS for .ru/.su/.рф first in dns.servers. */
    private fun withRuDns(dns: JsonObject, a: AdvancedSettings): JsonObject {
        val ru = TrafficPresets.ruDnsServer(a) ?: return dns
        val servers = (dns["servers"] as? JsonArray).orEmpty()
        return JsonObject(dns + ("servers" to JsonArray(listOf(ru) + servers)))
    }

    private fun dnsSection(opt: Options): JsonObject {
        val a = opt.adv
        if (a.dnsFromJson && a.dnsJson.isNotBlank()) {
            val el = runCatching { Json.parseToJsonElement(a.dnsJson.trim()) }.getOrNull()
            val obj = when (el) {
                is JsonObject -> (el["dns"] as? JsonObject) ?: el
                is JsonArray -> buildJsonObject { put("servers", el) }
                else -> null
            }
            if (obj != null) return obj
        }
        val rp = opt.routing
        fun plain(s: String) = if (opt.dnsTcp && !s.contains("://") && isIp(s)) "tcp://$s" else s
        return buildJsonObject {
            putJsonObject("hosts") {
                put("dns.google", JsonArray(listOf(JsonPrimitive("8.8.8.8"), JsonPrimitive("8.8.4.4"))))
                rp?.dnsHosts?.forEach { (k, v) -> if (k != "dns.google") put(k, v) }
            }
            putJsonArray("servers") {
                // 1.0.19: routing profile DNS (Happ RemoteDNS / DomesticDNS): domestic resolver for «Напрямую» domains
                val direct = if (rp != null && rp.directEnabled && rp.domesticDns.isNotBlank()) rp.rules
                    .filter { it.action == "direct" && it.kind == "domain" }.map { domainValue(it.value) }
                    .mapNotNull { GeoDat.resolve(it, rp, opt.geoBundled) }.distinct() else emptyList()
                if (direct.isNotEmpty()) add(buildJsonObject {
                    put("address", plain(rp!!.domesticDns))
                    putJsonArray("domains") { direct.forEach { add(it) } }
                    put("skipFallback", true)
                })
                val adDns = AdBlock.dnsUrl(a)
                if (adDns != null) add(plain(adDns)) // 2.0.15 (ABS): «ДНС-блокировщик рекламы» replaces the remote resolver
                else if (rp != null && rp.remoteDns.isNotBlank()) add(plain(rp.remoteDns))
                else opt.dnsServers.forEach { s ->
                    // UDP-less leaf (http/naive/ssh/http3/snell v1-2…): plain UDP DNS through it would hang
                    add(plain(s))
                }
            }
            put("queryStrategy", when (opt.ipType) {
                "ipv6" -> "UseIPv6"
                "ipv4" -> "UseIPv4"
                else -> "UseIPv4" // auto: keep the 1.0.0 strategy that already connects
            })
        }
    }

    /** Full Xray JSON config: socks inbound (fed by hev-socks5-tunnel) or TUN inbound, proxy + direct + block + dns outbounds. */
    fun fullConfig(
        proxyOutbound: JsonObject,
        opt: Options = Options(),
        remarks: String = "",
        extraOutbounds: List<JsonObject> = emptyList(),
        balancerTag: String? = null,
        balancerSelector: String? = null,
        balancerStrategy: String? = null,
        extraInbounds: List<JsonObject> = emptyList(),
        extraRules: List<JsonObject> = emptyList(),
    ): JsonObject = buildJsonObject {
        if (remarks.isNotEmpty()) put("remarks", remarks)
        putJsonObject("log") {
            val lvl = if (!opt.adv.logging) "none" else opt.logLevel
            put("loglevel", lvl)
            if (opt.logDir != null && lvl != "none") {
                put("error", "${opt.logDir}/xray-error.log")
                put("access", if (lvl == "info" || lvl == "debug") "${opt.logDir}/xray-access.log" else "none")
            }
        }
        putJsonObject("stats") { }
        putJsonObject("policy") {
            putJsonObject("levels") {
                putJsonObject("8") {
                    put("handshake", 4); put("connIdle", 300); put("uplinkOnly", 1); put("downlinkOnly", 1)
                    put("statsUserUplink", false); put("statsUserDownlink", false)
                }
            }
            putJsonObject("system") { put("statsOutboundUplink", true); put("statsOutboundDownlink", true) }
        }
        put("dns", withRuDns(dnsSection(opt), opt.adv))
        val sniff = buildJsonObject {
            // 2.0.15 (ABS): ad blocking matches domains → sniffing on while it is enabled
            put("enabled", opt.sniffing || AdBlock.enabled(opt.adv))
            putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
            if (opt.routing != null) put("routeOnly", false)
        }
        putJsonArray("inbounds") {
            add(buildJsonObject {
                put("tag", "socks"); put("listen", if (opt.allowLan) "0.0.0.0" else "127.0.0.1"); put("port", opt.socksPort); put("protocol", "socks")
                put("settings", socksInboundSettings(opt, udp = true))
                put("sniffing", sniff)
            })
            if (opt.adv.httpInbound) add(buildJsonObject {
                put("tag", "http"); put("listen", if (opt.allowLan) "0.0.0.0" else "127.0.0.1"); put("port", opt.adv.httpPort); put("protocol", "http")
                putJsonObject("settings") {
                    if (opt.socksAuth) putJsonArray("accounts") { add(buildJsonObject { put("user", opt.socksUser); put("pass", opt.socksPass) }) }
                    put("userLevel", 8)
                }
                put("sniffing", sniff)
            })
            if (opt.xrayTun) add(buildJsonObject {
                put("tag", "tun"); put("protocol", "tun")
                putJsonObject("settings") { put("name", "xray0"); put("mtu", opt.adv.tunMtu.coerceIn(1280, 9000)); put("userLevel", 8) }
                put("sniffing", sniff)
            })
            if (opt.adv.localDns) add(buildJsonObject {
                put("tag", "dns-in"); put("listen", "127.0.0.1"); put("port", opt.adv.localDnsPort); put("protocol", "dokodemo-door")
                putJsonObject("settings") { put("address", opt.dnsServers.firstOrNull { isIp(it) } ?: "1.1.1.1"); put("port", 53); put("network", "tcp,udp") }
            })
            extraInbounds.forEach { add(it) }
        }
        putJsonArray("outbounds") {
            add(proxyOutbound)
            extraOutbounds.forEach { add(it) }
            opt.routingProxyOutbound?.let { add(it) }
            opt.ruViaOutbound?.let { add(it) }
            val freedomIp = when (opt.ipType) { "ipv6" -> "UseIPv6"; "ipv4" -> "UseIPv4"; else -> "UseIP" }
            add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom"); putJsonObject("settings") { put("domainStrategy", freedomIp) } })
            add(buildJsonObject { put("tag", "block"); put("protocol", "blackhole"); putJsonObject("settings") { putJsonObject("response") { put("type", "http") } } })
            if (AdBlock.enabled(opt.adv)) add(AdBlock.blackhole(AdBlock.TAG))
            add(buildJsonObject { put("tag", "dns-out"); put("protocol", "dns") })
        }
        putJsonObject("routing") {
            // 2.0.30: with ad-block, IPIfNonMatch so a sniffed/resolved name still hits domain rules
            put("domainStrategy", opt.routing?.domainStrategy
                ?: if (AdBlock.enabled(opt.adv)) "IPIfNonMatch" else "AsIs")
            putJsonArray("rules") {
                // 2.0.15 (ABS): categories / ads → «block-ads», QUIC → block — first, for the user-facing inbounds only
                val userInb = listOfNotNull("socks", "http".takeIf { opt.adv.httpInbound }, "tun".takeIf { opt.xrayTun })
                val mainTarget = if (balancerTag != null) "balancerTag" to balancerTag else "outboundTag" to ((proxyOutbound["tag"] as? JsonPrimitive)?.contentOrNull ?: "proxy")
                AdBlock.leadingRules(opt.adv, userInb, "block", opt.geoBundled).forEach { add(it) }
                extraRules.forEach { add(it) }
                add(buildJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { add("socks"); if (opt.xrayTun) add("tun") }
                    put("port", "53"); put("outboundTag", "dns-out")
                })
                if (opt.adv.localDns) add(buildJsonObject {
                    put("type", "field"); putJsonArray("inboundTag") { add("dns-in") }; put("outboundTag", "dns-out")
                })
                if (opt.bypassLan) add(buildJsonObject {
                    put("type", "field"); putJsonArray("ip") { privateCidrs.forEach { add(it) } }; put("outboundTag", "direct")
                })
                // 1.0.40: «Правила трафика» preset (replaces the routing profile, see plan())
                // 2.0.43: «.RU без VPN» via a chosen server → its outbound [RU_VIA_TAG] instead of freedom
                val ruVia = if (opt.ruViaOutbound != null) "outboundTag" to RU_VIA_TAG else null
                TrafficPresets.ruDnsRule(opt.adv, if (ruVia != null) RU_VIA_TAG else "direct")?.let { add(it) }
                TrafficPresets.rules(opt.adv, userInb, mainTarget, "direct", opt.geoExtra, opt.geoBundled, ruVia).forEach { add(it) }
                opt.routing?.let { r ->
                    val target = when {
                        opt.routingProxyOutbound != null -> "outboundTag" to ROUTING_PROXY_TAG
                        balancerTag != null -> "balancerTag" to balancerTag
                        else -> "outboundTag" to "proxy"
                    }
                    routingRules(r, opt, target).forEach { add(it) }
                }
                if (balancerTag != null) add(buildJsonObject {
                    put("type", "field"); put("network", "tcp,udp"); put("balancerTag", balancerTag)
                })
            }
            if (balancerTag != null && balancerSelector != null) {
                putJsonArray("balancers") {
                    add(buildJsonObject {
                        put("tag", balancerTag)
                        putJsonArray("selector") { add(balancerSelector) }
                        // Until the observatory has a live result (or when every member is down) use the first member.
                        (proxyOutbound["tag"] as? JsonPrimitive)?.contentOrNull?.let { put("fallbackTag", it) }
                        putJsonObject("strategy") { put("type", if (balancerStrategy == "random") "random" else "leastping") }
                    })
                }
            }
        }
        if (balancerTag != null && balancerSelector != null && balancerStrategy != "random") {
            putJsonObject("observatory") {
                putJsonArray("subjectSelector") { add(balancerSelector) }
                put("probeURL", opt.adv.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL })
                put("probeInterval", "30s")
                put("enableConcurrency", true)
            }
        }
    }

    fun toJsonString(e: JsonElement, pretty: Boolean = false): String =
        if (pretty) prettyJson.encodeToString(JsonElement.serializer(), e) else e.toString()

    fun forProfile(p: XrayProfile, opt: Options = Options()): String =
        toJsonString(fullConfig(outbound(p, opt = opt), opt, p.remarks), pretty = true)

    fun forQwdtt(wg: WgConfig, opt: Options = Options()): String {
        val dns = wg.dns.filter { !it.contains(':') }.ifEmpty { opt.dnsServers }
        return toJsonString(fullConfig(qwdttOutbound(wg), opt.copy(dnsServers = dns, bypassLan = opt.bypassLan), "qWDTT"), pretty = true)
    }

    /** Xray JSON only (kept for callers/tests that don't start sing-box). */
    fun forEntry(entry: ServerEntry, all: List<ServerEntry>, opt: Options = Options()): String = plan(entry, all, opt).xray

    /**
     * Engine plan for a server: Xray JSON (+ sing-box / mieru / mihomo helper configs when any leaf needs them).
     * [singBoxDns] resolves proxy-server domains inside sing-box.
     */
    fun plan(entry0: ServerEntry, all: List<ServerEntry>, optIn: Options = Options(), singBoxDns: String = "1.1.1.1"): Plan {
        // 1.0.40: a traffic preset replaces the routing profile (one rule set at a time, as in Etonify)
        val opt0 = if (TrafficPresets.active(optIn.adv) && optIn.routing != null) optIn.copy(routing = null) else optIn
        // 1.0.19: a subscription's «🎲Автовыбор сервера» stub runs as a leastPing balancer over that subscription.
        val entry = AutoSelect.resolve(entry0, all, opt0.autoExclude)
        val p = entry.xray ?: throw IllegalArgumentException("нет профиля Xray")
        if (!p.unsupportedReason.isNullOrBlank() || !ProtocolSupport.isRunnable(p.protocol)) {
            throw IllegalStateException(p.unsupportedReason ?: "Протокол ${p.protocol} пока не поддерживается ядром")
        }
        val sb = SingBoxSlots()
        val mieru = MieruSlots()
        val mhx = MihomoSlots()
        val olc = OlcSlots()
        currentOlc.set(olc)
        val vpn = OlcSlots(VPN_BASE_PORT)
        currentVpn.set(vpn)
        try {
        // UDP-less exit → DNS over TCP
        val exitLeaves: List<XrayProfile> = when (p.protocol) {
            "chain" -> listOfNotNull(p.chainIds.lastOrNull()?.let { id -> all.firstOrNull { it.id == id }?.let { leaf(it) } })
            "balancer" -> p.balancerIds.mapNotNull { id -> all.firstOrNull { it.id == id }?.let { leaf(it) } }
            "custom" -> emptyList()
            else -> listOf(p)
        }
        var opt = opt0.copy(dnsTcp = opt0.dnsTcp || exitLeaves.any { ProtocolSupport.lacksUdp(it) })
        // Routing profile with its own «Прокси» servers → extra outbound
        val rp = opt.routing
        if (rp != null && rp.proxyEnabled && rp.proxyServerIds.isNotEmpty() && p.protocol != "custom") {
            val target = rp.proxyServerIds.firstNotNullOfOrNull { id -> all.firstOrNull { it.id == id && it.id != entry.id }?.let { leaf(it) } }
            if (target != null) opt = opt.copy(routingProxyOutbound = outbound(target, ROUTING_PROXY_TAG, opt, sb, mieru = mieru, mhx = mhx))
        }
        // 2.0.43: «.RU без VPN» through a chosen server (e.g. a node in Russia) instead of directly from the phone
        if (opt.adv.trafficPreset == TrafficPresets.RU && opt.adv.ruVia.isNotBlank()) {
            val via = all.firstOrNull { it.id == opt.adv.ruVia }?.let { leaf(it) }
            if (via != null) opt = opt.copy(ruViaOutbound = outbound(via, RU_VIA_TAG, opt, sb, mieru = mieru, mhx = mhx))
        }
        val xray = when (p.protocol) {
            "custom" -> customConfig(p.customJson ?: throw IllegalArgumentException("пустой JSON"), opt, p.remarks)
            "chain" -> chainConfig(p, all, opt, sb, mieru, mhx)
            "balancer" -> balancerConfig(p, all, opt, sb, mieru, mhx)
            else -> toJsonString(fullConfig(outbound(p, opt = opt, sb = sb, mieru = mieru, mhx = mhx), opt, p.remarks), pretty = true)
        }
        val sbJson = if (sb.slots.isEmpty()) null else SingBoxConfigBuilder.toJson(sb.slots, singBoxDns, opt.ipType,
            if (opt.socksAuth) opt.socksUser else "", if (opt.socksAuth) opt.socksPass else "")
        val mieruInstances = mieru.slots.map { (prof, port) -> MieruConfigBuilder.clientJson(prof, port) to port }
        val mhxJson = if (mhx.slots.isEmpty()) null else MihomoConfigBuilder.toJson(mhx.slots)
        return Plan(xray, sbJson, sb.slots.toList(), mieruInstances, mhxJson, mhx.slots.map { it.port }, olc.slots.toList(), vpn.slots.toList())
        } finally { currentOlc.remove(); currentVpn.remove() }
    }

    /** olcRTC slots of the plan being built (chain/balancer/routing helpers reach outbound() without an extra parameter). */
    private val currentOlc = ThreadLocal<OlcSlots?>()
    private val currentVpn = ThreadLocal<OlcSlots?>()
    const val VPN_BASE_PORT = 11300

    private fun leaf(s: ServerEntry): XrayProfile? {
        val p = s.xray ?: return null
        if (!p.unsupportedReason.isNullOrBlank()) return null
        if (p.protocol in setOf("chain", "balancer", "custom")) return null
        if (!ProtocolSupport.isRunnable(p.protocol)) return null
        return p
    }

    private fun chainConfig(
        p: XrayProfile, all: List<ServerEntry>, opt: Options, sb: SingBoxSlots = SingBoxSlots(), mieru: MieruSlots = MieruSlots(),
        mhx: MihomoSlots = MihomoSlots(),
    ): String {
        val members = p.chainIds.mapNotNull { id -> all.firstOrNull { it.id == id }?.let { leaf(it) } }
        if (members.isEmpty()) throw IllegalStateException("В цепи нет рабочих серверов")
        val tags = members.indices.map { i -> if (i == members.lastIndex) "proxy" else "hop-$i" }
        val inbounds = mutableListOf<JsonObject>()
        val rules = mutableListOf<JsonObject>()
        val outs = members.mapIndexed { i, m ->
            when (ProtocolSupport.engineOf(m)) {
                ProtocolSupport.ENGINE_XRAY -> {
                    var ob = outbound(m, tags[i], opt, sb, mieru = mieru, mhx = mhx)
                    if (i > 0) ob = withDialer(ob, tags[i - 1])
                    ob
                }
                ProtocolSupport.ENGINE_SINGBOX -> if (i == 0) outbound(m, tags[i], opt, sb, mieru = mieru, mhx = mhx) else {
                    // sing-box dials this hop through Xray: its outbound detours to a loopback Xray inbound
                    // that is routed to the previous hop.
                    val bridge = SingBoxConfigBuilder.XRAY_BRIDGE_BASE + i
                    inbounds += buildJsonObject {
                        put("tag", "sb-bridge-$i"); put("listen", "127.0.0.1"); put("port", bridge); put("protocol", "socks")
                        put("settings", socksInboundSettings(opt, udp = true))
                    }
                    rules += buildJsonObject {
                        put("type", "field"); putJsonArray("inboundTag") { add("sb-bridge-$i") }; put("outboundTag", tags[i - 1])
                    }
                    outbound(m, tags[i], opt, sb, bridgePort = bridge, mieru = mieru, mhx = mhx)
                }
                else -> if (i == 0) outbound(m, tags[i], opt, sb, mieru = mieru, mhx = mhx)
                // mieru/mihomo helpers have no detour; refuse mid-chain for honesty
                else throw IllegalStateException("${net.antibs.client.ui.Format.protocolLabel(m.protocol)} можно ставить только первым звеном цепи")
            }
        }
        val proxy = outs.last()
        val extra = outs.dropLast(1)
        return toJsonString(fullConfig(proxy, opt, p.remarks, extraOutbounds = extra, extraInbounds = inbounds, extraRules = rules), pretty = true)
    }

    /**
     * Hosts behind the balancer outbounds b-0, b-1, … (same order as [balancerConfig]) for [entry] — a «Балансировщик»
     * or a subscription auto-select stub; empty for anything else.
     */
    fun balancerMembers(entry: ServerEntry, all: List<ServerEntry>, exclude: Set<String> = emptySet()): List<ServerEntry> {
        val p = (try { AutoSelect.resolve(entry, all, exclude) } catch (_: Exception) { return emptyList() }).xray ?: return emptyList()
        if (p.protocol != "balancer") return emptyList()
        return p.balancerIds.mapNotNull { id -> all.firstOrNull { it.id == id }?.takeIf { leaf(it) != null } }
    }

    const val BALANCER_TAG = "bal"
    const val BALANCER_PREFIX = "b-"

    private fun balancerConfig(
        p: XrayProfile, all: List<ServerEntry>, opt: Options, sb: SingBoxSlots = SingBoxSlots(), mieru: MieruSlots = MieruSlots(),
        mhx: MihomoSlots = MihomoSlots(),
    ): String {
        val members = p.balancerIds.mapNotNull { id -> all.firstOrNull { it.id == id }?.let { leaf(it) } }
        if (members.isEmpty()) throw IllegalStateException("В балансировщике нет рабочих серверов")
        val strategy = if (p.balancerStrategy == "random") "random" else "leastping"
        val tagged = members.mapIndexed { i, m -> outbound(m, "b-$i", opt, sb, mieru = mieru, mhx = mhx) }
        // First outbound is the default if a packet misses the balancer rule; keep a real member there.
        val proxy = tagged.first()
        val extra = tagged.drop(1)
        return toJsonString(
            fullConfig(proxy, opt, p.remarks, extraOutbounds = extra, balancerTag = BALANCER_TAG, balancerSelector = BALANCER_PREFIX, balancerStrategy = strategy),
            pretty = true,
        )
    }

    fun customConfig(json: String, opt: Options = Options(), remarks: String = ""): String {
        val el0 = Json.parseToJsonElement(json.trim())
        if (el0 !is JsonObject) throw IllegalArgumentException("JSON должен быть объектом")
        val el = when {
            el0.containsKey("outbounds") -> JsonObject(el0 + ("outbounds" to JsonArray((el0["outbounds"] as? JsonArray).orEmpty().map { o ->
                (o as? JsonObject)?.let { modernizeOutbound(it) } ?: o
            })))
            el0.containsKey("protocol") -> modernizeOutbound(el0)
            else -> el0
        }
        if (el.containsKey("outbounds") && el.containsKey("inbounds")) {
            return toJsonString(patchFullConfig(el, opt), pretty = true)
        }
        val outbound = when {
            el.containsKey("outbounds") -> el["outbounds"] as? JsonArray
            el.containsKey("protocol") -> null
            else -> throw IllegalArgumentException("Нужен outbound (protocol) или полный конфиг Xray")
        }
        val one = if (outbound != null) {
            outbound.firstOrNull() as? JsonObject ?: throw IllegalArgumentException("пустой outbounds")
        } else el
        return toJsonString(fullConfig(withTag(one, "proxy"), opt, remarks.ifEmpty { "Свой JSON" }), pretty = true)
    }

    /**
     * 1.0.34: a full Xray JSON (e.g. the panel's auto-select «Balanser» template) runs as-is — outbounds, routing,
     * balancers, observatory / burstObservatory and DNS are kept untouched. Only what the app itself needs is patched:
     * inbounds become the app's own (SOCKS on the configured port for hev-socks5-tunnel, Xray TUN, HTTP, local DNS),
     * rules that named the template's local inbounds point at the app's inbounds, log paths, traffic stats, and the
     * Xray API when [Options.apiPort] is set (to read which outbound the balancer picked).
     */
    fun patchFullConfig(cfg: JsonObject, opt: Options): JsonObject {
        val base = fullConfig(buildJsonObject { put("tag", "proxy"); put("protocol", "freedom") }, opt.copy(routing = null))
        val ourInbounds = base["inbounds"] as JsonArray
        val ourTags = ourInbounds.mapNotNull { (it as? JsonObject)?.get("tag")?.let { t -> (t as? JsonPrimitive)?.contentOrNull } }
            .filter { it != "dns-in" }
        val oldLocal = (cfg["inbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase() in setOf("socks", "http", "mixed", "tun") }
            .mapNotNull { (it["tag"] as? JsonPrimitive)?.contentOrNull }.toSet()
        var out = JsonObject(cfg + ("inbounds" to ourInbounds) + ("log" to base["log"]!!))
        if (!out.containsKey("stats")) out = JsonObject(out + ("stats" to base["stats"]!!))
        val pol = out["policy"] as? JsonObject
        out = JsonObject(out + ("policy" to if (pol == null) base["policy"]!! else {
            val sys = (pol["system"] as? JsonObject).orEmpty() + mapOf("statsOutboundUplink" to JsonPrimitive(true), "statsOutboundDownlink" to JsonPrimitive(true))
            JsonObject(pol + ("system" to JsonObject(sys)))
        }))
        val routing = out["routing"] as? JsonObject
        if (routing != null && oldLocal.isNotEmpty()) {
            val rules = (routing["rules"] as? JsonArray).orEmpty().map { r ->
                val ro = r as? JsonObject ?: return@map r
                val tags = ro["inboundTag"] as? JsonArray ?: return@map ro
                val names = tags.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (names.none { it in oldLocal }) return@map ro
                val fixed = (names.filter { it !in oldLocal } + ourTags).distinct()
                JsonObject(ro + ("inboundTag" to JsonArray(fixed.map { JsonPrimitive(it) })))
            }
            out = JsonObject(out + ("routing" to JsonObject(routing + ("rules" to JsonArray(rules)))))
        }
        if (opt.apiPort > 0) out = JsonObject(out + ("api" to buildJsonObject {
            put("tag", "api"); put("listen", "127.0.0.1:${opt.apiPort}")
            putJsonArray("services") { add("RoutingService"); add("ObservatoryService"); add("StatsService") }
        }))
        // 1.0.39: the only routing change to a panel / custom config — the ad / tracker block rule on top
        val via = opt.ruViaOutbound
        if (via != null && TrafficPresets.active(opt.adv)) {
            val outs = (out["outbounds"] as? JsonArray).orEmpty().filterNot { (it as? JsonObject)?.get("tag")?.let { t -> (t as? JsonPrimitive)?.contentOrNull } == RU_VIA_TAG }
            out = JsonObject(out + ("outbounds" to JsonArray(outs + via)))
        }
        return AdBlock.patch(out, opt.adv, opt.geoExtra, opt.geoBundled, ruVia = if (via != null) RU_VIA_TAG else null)
    }

    /**
     * 1.0.19: makes an imported Xray outbound load on Xray-core 26: tlsSettings.allowInsecure (also LucX's nested
     * tlsSettings.settings.allowInsecure) → pinnedPeerCertSha256 of the server's current certificate, and VLESS users
     * without "encryption" get "none".
     */
    fun modernizeOutbound(o: JsonObject): JsonObject {
        var ob = o
        val proto = (ob["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (proto == "vless") {
            val st = ob["settings"] as? JsonObject
            val vnext = st?.get("vnext") as? JsonArray
            if (st != null && vnext != null) {
                val fixed = JsonArray(vnext.map { v ->
                    val vo = v as? JsonObject ?: return@map v
                    val users = vo["users"] as? JsonArray ?: return@map v
                    JsonObject(vo + ("users" to JsonArray(users.map { u ->
                        val uo = u as? JsonObject ?: return@map u
                        if ((uo["encryption"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) JsonObject(uo + ("encryption" to JsonPrimitive("none"))) else uo
                    })))
                })
                ob = JsonObject(ob + ("settings" to JsonObject(st + ("vnext" to fixed))))
            } else if (st != null && st.containsKey("address") && (st["encryption"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) {
                ob = JsonObject(ob + ("settings" to JsonObject(st + ("encryption" to JsonPrimitive("none")))))
            }
        }
        val ss = ob["streamSettings"] as? JsonObject ?: return ob
        var tls = ss["tlsSettings"] as? JsonObject ?: return ob
        val nested = (tls["settings"] as? JsonObject)?.get("allowInsecure") as? JsonPrimitive
        if (nested != null) tls = JsonObject(tls - "settings" + (if (tls.containsKey("allowInsecure")) emptyMap() else mapOf("allowInsecure" to nested)))
        val net = (ss["network"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (net == "ws" || net == "httpupgrade") (tls["alpn"] as? JsonArray)?.let { a ->
            val kept = a.filter { (it as? JsonPrimitive)?.contentOrNull != "h2" }
            if (kept.size != a.size) tls = if (kept.isEmpty()) JsonObject(tls - "alpn") else JsonObject(tls + ("alpn" to JsonArray(kept)))
        }
        val insecure = (tls["allowInsecure"] as? JsonPrimitive)?.contentOrNull == "true"
        if (tls.containsKey("allowInsecure")) {
            tls = JsonObject(tls - "allowInsecure")
            if (insecure && !tls.containsKey("pinnedPeerCertSha256")) {
                val (host, port) = outboundAddress(ob)
                val sni = (tls["serverName"] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
                CertPins.pinFor(host, port, sni, quic = proto == "hysteria")?.let { tls = JsonObject(tls + ("pinnedPeerCertSha256" to JsonPrimitive(it))) }
            }
        }
        return JsonObject(ob + ("streamSettings" to JsonObject(ss + ("tlsSettings" to tls))))
    }

    /** First server address / port of an Xray outbound (vnext / servers / peers / flat settings). */
    fun outboundAddress(o: JsonObject): Pair<String, Int> {
        val st = o["settings"] as? JsonObject ?: return "" to 0
        val first = ((st["vnext"] ?: st["servers"] ?: st["peers"]) as? JsonArray)?.firstOrNull() as? JsonObject
        val src = first ?: st
        val host = ((src["address"] ?: src["server"] ?: src["endpoint"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val port = (src["port"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return host to port
    }

    private fun withTag(ob: JsonObject, tag: String): JsonObject = buildJsonObject {
        put("tag", tag)
        ob.forEach { (k, v) -> if (k != "tag") put(k, v) }
    }

    private fun withDialer(ob: JsonObject, dialer: String): JsonObject = buildJsonObject {
        ob.forEach { (k, v) -> if (k != "streamSettings") put(k, v) }
        val ss = ob["streamSettings"] as? JsonObject ?: buildJsonObject { put("network", "tcp") }
        put("streamSettings", buildJsonObject {
            ss.forEach { (k, v) -> if (k != "sockopt") put(k, v) }
            val so = ss["sockopt"] as? JsonObject
            put("sockopt", buildJsonObject {
                so?.forEach { (k, v) -> if (k != "dialerProxy") put(k, v) }
                put("dialerProxy", dialer)
            })
        })
    }

    @Suppress("unused")
    private fun arr(vararg s: String) = buildJsonArray { s.forEach { add(it) } }
}
