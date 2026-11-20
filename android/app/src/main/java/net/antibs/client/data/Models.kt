package net.antibs.client.data

import kotlinx.serialization.Serializable

enum class EngineKind { XRAY, QWDTT }

/**
 * Which engine runs a protocol. Xray-core stays the front engine (TUN → hev → Xray SOCKS, routing, DNS, stats);
 * protocols Xray can't dial are handed to a bundled sing-box process through a local SOCKS hop.
 */
object ProtocolSupport {
    const val MANUAL_SUB_ID = "sub-manual"
    const val MANUAL_TITLE = "Серверы"

    const val ENGINE_XRAY = "Xray"
    const val ENGINE_SINGBOX = "sing-box"
    const val ENGINE_MIERU = "mieru"
    const val ENGINE_MIHOMO = "mihomo"
    const val ENGINE_OLCRTC = "olcRTC"
    const val ENGINE_VPNSTACK = "vpnstack"

    /** Protocols the bundled Xray-core (26.9.30 / hysteria v2) dials itself. */
    val XRAY_WORKING = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "wireguard",
        "hysteria", "socks", "http", "https",
        "chain", "balancer", "custom",
    )

    /** Protocols dialed by the bundled sing-box 1.14 (libsbx.so) behind a local SOCKS inbound. Snell v4/v5 only (v1–v3, v6 → mihomo). */
    val SINGBOX_WORKING = setOf("hysteria1", "tuic", "anytls", "ssh", "snell", "naive", "socks4")

    /**
     * 1.0.19: empty — mieru moved to mihomo's mieru v3 outbound (libmhx). The bundled mieru 1.15.1 client (libmieru.so)
     * can't talk to current (v3) servers: the TCP session opens but no data ever comes back.
     */
    val MIERU_WORKING = emptySet<String>()

    /**
     * Protocols dialed by libmhx.so (native/mihomo-lite): mihomo 1.19.34 outbounds (ShadowsocksR, Snell v1–v3 + v6,
     * TrustTunnel, ShadowQUIC) and an HTTP/3 CONNECT client (quic-go http3, as in Exclave).
     */
    val MIHOMO_WORKING = setOf("ssr", "trusttunnel", "shadowquic", "http3", "amneziawg", "mieru")

    /** Nothing is blocked in 1.0.6 — every chip in «Создать вручную» has a working engine. */
    val UNSUPPORTED: Map<String, String> = emptyMap()

    /** Messages written by ≤1.0.5 into stored profiles; cleared on load because those protocols now run. */
    val LEGACY_REASONS = setOf(
        "Hysteria 1 не поддерживается ядром Xray (только Hysteria2).",
        "TUIC не входит в Xray-core; второй движок (sing-box) не подключён.",
        "AnyTLS не входит в Xray-core; второй движок (sing-box) не подключён.",
        "SSH не входит в Xray-core; второй движок (sing-box) не подключён.",
        "Snell не входит в Xray-core (нужен mihomo).",
        "NaiveProxy не входит в Xray-core (нужен cronet / sing-box).",
        "Протокол не поддерживается ядром",
        "HTTP/3 CONNECT-прокси не входит в Xray-core и sing-box (есть HTTP/HTTPS CONNECT и Naive/QUIC).",
        "Snell v1–v3 не поддерживается (sing-box умеет v4/v5/v6).",
    )

    @Deprecated("Snell v1–v3 runs on mihomo since 1.0.6")
    const val SNELL_OLD_REASON = "Snell v1–v3 не поддерживается (sing-box умеет v4/v5/v6)."

    /**
     * Engine for a concrete profile. Snell splits by version: v1–v3 and (2.0.45) v6 → mihomo 1.19.34 (v6 with its
     * traffic modes and PSK-derived shaping), v4/v5 → sing-box.
     */
    fun engineOf(p: XrayProfile): String {
        val proto = p.protocol.lowercase()
        if (needsPlaintextHelper(p)) return ENGINE_MIHOMO
        if (proto == "snell") return if (snellOnMihomo(p.protoVersion ?: 4)) ENGINE_MIHOMO else ENGINE_SINGBOX
        return engineFor(proto)
    }

    /**
     * 1.0.19: Xray-core 26 refuses VLESS without TLS (and without VLESS encryption) to a public address
     * ("vless without TLS or other encryption is prohibited unless the server address is a private IP or domain").
     * Such hosts are dialed by mihomo (libmhx) instead.
     */
    fun needsPlaintextHelper(p: XrayProfile): Boolean {
        val proto = p.protocol.lowercase()
        if (proto != "vless") return false
        if (p.security == "tls" || p.security == "reality") return false
        if (proto == "vless" && !p.encryption.isNullOrEmpty() && p.encryption != "none") return false
        if (p.network.lowercase() !in setOf("tcp", "ws", "grpc", "h2", "http", "httpupgrade")) return false
        return !isPrivateAddress(p.server)
    }

    fun isPrivateAddress(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".localhost")) return true
        val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80") || h == "::1"
        val (a, b) = v4[0] to v4[1]
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
    }

    /** 2.0.45: Snell versions dialed by mihomo (v1–v3 and v6); v4/v5 stay on sing-box. */
    fun snellOnMihomo(version: Int): Boolean = version in 1..3 || version >= 6

    /** 2.0.45: Snell v6 PSK length accepted by mihomo (12–255 bytes). */
    fun snellV6PskOk(psk: String?): Boolean = psk != null && psk.toByteArray(Charsets.UTF_8).size in 12..255

    val SNELL_V6_MODES = listOf("default", "unshaped", "unsafe-raw")

    fun isSingBox(protocol: String): Boolean = protocol.lowercase() in SINGBOX_WORKING
    fun isMieru(protocol: String): Boolean = protocol.lowercase() in MIERU_WORKING
    fun isMihomo(protocol: String): Boolean = protocol.lowercase() in MIHOMO_WORKING
    fun isOlcrtc(protocol: String): Boolean = protocol.lowercase() == Olcrtc.PROTOCOL
    /** 1.0.41: OpenVPN / OpenConnect through libvpnstack.so. */
    fun isClassicVpn(protocol: String): Boolean = protocol.lowercase() in setOf(OpenVpnConfig.PROTOCOL, OpenConnectConfig.PROTOCOL, PacketVpnConfig.SSTP, PacketVpnConfig.ZEROTIER)
    fun isRunnable(protocol: String): Boolean = protocol.lowercase().let {
        it in XRAY_WORKING || it in SINGBOX_WORKING || it in MIERU_WORKING || it in MIHOMO_WORKING || it == Olcrtc.PROTOCOL || isClassicVpn(it)
    }
    fun unsupportedReason(protocol: String): String? = UNSUPPORTED[protocol.lowercase()]
    fun engineFor(protocol: String): String = when {
        isSingBox(protocol) -> ENGINE_SINGBOX
        isMieru(protocol) -> ENGINE_MIERU
        isMihomo(protocol) -> ENGINE_MIHOMO
        isOlcrtc(protocol) -> ENGINE_OLCRTC
        isClassicVpn(protocol) -> ENGINE_VPNSTACK
        else -> ENGINE_XRAY
    }

    /** Protocols whose engine path has no UDP relay → Xray DNS must go over TCP through the proxy. */
    fun lacksUdp(p: XrayProfile): Boolean = when (p.protocol.lowercase()) {
        "http", "https", "naive", "ssh", "http3", "socks4", "olcrtc" -> true
        "snell" -> (p.protoVersion ?: 4) in 1..2
        else -> false
    }

    /** Upgrade a profile stored by an older build (clears the old "not supported" stamp). */
    fun migrate(p: XrayProfile): XrayProfile {
        if (!isRunnable(p.protocol) || p.unsupportedReason == null || p.unsupportedReason !in LEGACY_REASONS) return p
        var out = p.copy(unsupportedReason = null)
        if (p.protocol == "tuic" && out.id.isNullOrEmpty() && !p.username.isNullOrEmpty()) out = out.copy(id = p.username)
        if (p.protocol == "naive" && out.security == null) out = out.copy(security = "tls")
        if (p.protocol == "anytls" && out.security == null) out = out.copy(security = "tls")
        if (p.protocol == "snell" && out.protoVersion == null) out = out.copy(protoVersion = 4)
        return out
    }

    fun migrate(e: ServerEntry): ServerEntry = e.xray?.let { x -> migrate(x).let { if (it === x) e else e.copy(xray = it) } } ?: e

    /** Settings → «Протоколы»: what really runs, by engine (shown verbatim in the UI). */
    data class ProtocolRow(val label: String, val engine: String, val note: String = "")

    val MATRIX: List<ProtocolRow> = listOf(
        ProtocolRow("VLESS (TCP/WS/gRPC/XHTTP/HTTPUpgrade, TLS/REALITY)", ENGINE_XRAY),
        ProtocolRow("VMess", ENGINE_XRAY),
        ProtocolRow("Trojan", ENGINE_XRAY),
        ProtocolRow("Shadowsocks / Shadowsocks 2022", ENGINE_XRAY),
        ProtocolRow("Hysteria2", ENGINE_XRAY),
        ProtocolRow("WireGuard", ENGINE_XRAY),
        ProtocolRow("SOCKS5", ENGINE_XRAY),
        ProtocolRow("HTTP / HTTPS (CONNECT)", ENGINE_XRAY, "без UDP"),
        ProtocolRow("Прокси-цепь / Балансировщик / Свой JSON", ENGINE_XRAY),
        ProtocolRow("Hysteria v1", ENGINE_SINGBOX),
        ProtocolRow("TUIC v5", ENGINE_SINGBOX),
        ProtocolRow("AnyTLS", ENGINE_SINGBOX),
        ProtocolRow("SSH", ENGINE_SINGBOX, "без UDP"),
        ProtocolRow("Snell v4 / v5", ENGINE_SINGBOX),
        ProtocolRow("Snell v6", ENGINE_MIHOMO, "режимы default / unshaped / unsafe-raw"),
        ProtocolRow("NaiveProxy (HTTP/2, QUIC)", ENGINE_SINGBOX, "cronet"),
        ProtocolRow("SOCKS4 / SOCKS4A", ENGINE_SINGBOX, "без UDP"),
        ProtocolRow("Mieru (TCP/UDP)", ENGINE_MIHOMO),
        ProtocolRow("HTTP/3 (CONNECT поверх QUIC)", ENGINE_MIHOMO, "quic-go http3, без UDP"),
        ProtocolRow("Snell v1 / v2 / v3", ENGINE_MIHOMO, "UDP только v3"),
        ProtocolRow("ShadowsocksR", ENGINE_MIHOMO),
        ProtocolRow("TrustTunnel", ENGINE_MIHOMO),
        ProtocolRow("ShadowQUIC", ENGINE_MIHOMO),
        ProtocolRow("AmneziaWG 1.5 / 2.0 / 3.0 / 3.1 (.conf, amneziawg://, vpn://)", ENGINE_MIHOMO),
    )
}

@Serializable
data class XrayProfile(
    val remarks: String,
    val protocol: String,          // Xray: vless | vmess | shadowsocks | trojan | wireguard | hysteria(2) | socks | http | chain | balancer | custom; sing-box: hysteria1 | tuic | anytls | ssh | snell | naive
    val server: String,
    val port: Int,
    val id: String? = null,        // vless/vmess uuid
    val encryption: String? = null,
    val flow: String? = null,
    val password: String? = null,  // trojan / shadowsocks / hy2 auth / http/socks pass
    val method: String? = null,    // shadowsocks method or vmess security
    val username: String? = null,  // socks / http
    val network: String = "tcp",
    val headerType: String? = null,
    val host: String? = null,
    val path: String? = null,
    val serviceName: String? = null,
    val authority: String? = null,
    val mode: String? = null,      // grpc mode / xhttp mode
    val extra: String? = null,     // xhttp extra JSON
    val security: String? = null,  // tls | reality | null
    val sni: String? = null,
    val fingerprint: String? = null,
    val alpn: String? = null,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,
    val insecure: Boolean = false,
    // wireguard
    val secretKey: String? = null,
    val preSharedKey: String? = null,
    val localAddress: String? = null,
    val reserved: String? = null,
    val mtu: Int? = null,
    /** Imported WG/AWG peer restrictions and keepalive (null = legacy share-link defaults). */
    val wgAllowedIps: List<String>? = null,
    val wgKeepAlive: Int? = null,
    /** Saved for export/inspection; the application's DNS/routing settings remain authoritative. */
    val wgDns: List<String> = emptyList(),
    // hysteria2
    val obfsPassword: String? = null,
    val portHopping: String? = null,
    val bandwidthUp: String? = null,
    val bandwidthDown: String? = null,
    val pinnedPeerCertSha256: String? = null,
    // sing-box protocols (tuic / ssh / snell / naive)
    val congestionControl: String? = null,   // tuic
    val udpRelayMode: String? = null,        // tuic: native | quic
    val privateKey: String? = null,          // ssh (PEM)
    val privateKeyPassphrase: String? = null,
    val hostKey: String? = null,             // ssh, comma separated
    val protoVersion: Int? = null,           // snell version
    /** 2.0.45: Snell v6 traffic mode for mihomo — default | unshaped | unsafe-raw (null = default). */
    val snellMode: String? = null,
    /** 2.0.45: Snell v4/v6 connection reuse (mihomo `reuse`). */
    val snellReuse: Boolean = false,
    val mieruTransport: String? = null,     // mieru: TCP | UDP
    val mieruMultiplexing: String? = null,  // MULTIPLEXING_* optional
    val mieruHandshake: String? = null,     // HANDSHAKE_* optional (mieru v3)
    /** 1.0.19 AmneziaWG obfuscation (jc, jmin, jmax, s1–s4, h1–h4 — values or AWG 2.0 ranges —, i1–i5). */
    val awg: Map<String, String>? = null,
    /** 1.0.41 olcRTC transport parameters (URI payload keys: vp8-fps, video-codec, …). */
    val olc: Map<String, String>? = null,
    // ShadowsocksR (mihomo): method = cipher, password; obfs/protocol below
    val ssrProtocol: String? = null,
    val ssrProtocolParam: String? = null,
    val ssrObfs: String? = null,
    val ssrObfsParam: String? = null,
    // ShadowQUIC: udp over stream (true) or datagram
    val udpOverStream: Boolean? = null,
    val zeroRtt: Boolean = false,
    // TrustTunnel / HTTP3: QUIC transport instead of HTTP/2
    val quic: Boolean = false,
    // special
    val customJson: String? = null,
    val chainIds: List<String> = emptyList(),
    val balancerIds: List<String> = emptyList(),
    /** leastPing | random */
    val balancerStrategy: String? = null,
    /** 1.0.34: panel auto-select JSON (protocol "custom" with routing.balancers) — outbound tag → host name («🇷🇺→🇩🇪 Германия»). */
    val balancerTagNames: Map<String, String> = emptyMap(),
    /** If set, connect must refuse — protocol not runnable. */
    val unsupportedReason: String? = null,
)

@Serializable
data class QwdttProfile(
    val name: String,
    val peer: String,
    val hashes: String = "",
    val workers: Int = 9,
    val port: Int = 9000,
    val password: String = "",
)

@Serializable
data class ServerEntry(
    val id: String,
    val engine: EngineKind,
    val name: String,
    val subscriptionId: String? = null,
    val xray: XrayProfile? = null,
    val qwdtt: QwdttProfile? = null,
)

@Serializable
data class Subscription(
    val id: String,
    val url: String,
    val kind: String = "auto",     // auto | remnawave | qwdtt | manual
    val title: String = "",
    val description: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expireEpoch: Long = 0,     // seconds
    val trafficUsedMb: Double = 0.0,
    val trafficLimitMb: Double = 0.0,
    val lastRefresh: Long = 0,
    val lastError: String = "",
    val serverCount: Int = 0,
    // Remnawave 3.x response headers (persisted)
    val announce: String = "",
    val supportUrl: String = "",
    val webPageUrl: String = "",
    /** profile-update-interval, hours (0 = not provided). */
    val updateIntervalHours: Int = 0,
    val routing: String = "",
    /** content-disposition filename */
    val fileName: String = "",
    /** 1.0.18: HWID sent only for this subscription (empty = use the global one from Settings → HWID). */
    val hwidOverride: String = "",
    /** 1.0.19: «Авто-маршрутизация из подписки» — install/refresh the routing profile the subscription delivers. */
    val autoRouting: Boolean = true,
    /** 1.0.22: host ids the user deleted from this subscription — skipped on every refresh until restored. */
    val hiddenHosts: List<String> = emptyList(),
    /** 1.0.22: user edits of subscription hosts (by host id) — re-applied after every refresh. */
    val hostOverrides: Map<String, HostOverride> = emptyMap(),
    /** 2.0.35: after a completed ping of this subscription (or ping-all), hide unreachable hosts. Default on. */
    val autoDeleteUnreachable: Boolean = true,
)

/** 1.0.22: an edited subscription host: [profile] is what the user saved, [original] what the subscription delivered. */
@Serializable
data class HostOverride(val profile: XrayProfile, val original: XrayProfile)

/** 1.0.22: pure helpers for deleting / editing hosts (unit-tested). */
object HostEdits {
    /** Subscription servers after a refresh: hidden hosts dropped, user edits applied. */
    fun applyToRefresh(sub: Subscription?, servers: List<ServerEntry>): List<ServerEntry> {
        if (sub == null || (sub.hiddenHosts.isEmpty() && sub.hostOverrides.isEmpty())) return servers
        val hidden = sub.hiddenHosts.toSet()
        return servers.filterNot { it.id in hidden }.map { s ->
            val o = sub.hostOverrides[s.id]
            if (o != null && s.xray != null) s.copy(name = o.profile.remarks, xray = o.profile) else s
        }
    }

    /** Overrides whose «original» is refreshed to what the subscription delivers now (so reset restores the fresh host). */
    fun refreshOriginals(sub: Subscription, fresh: List<ServerEntry>): Map<String, HostOverride> =
        sub.hostOverrides.mapValues { (id, o) -> fresh.firstOrNull { it.id == id }?.xray?.let { o.copy(original = it) } ?: o }

    fun isEdited(sub: Subscription?, id: String): Boolean = sub?.hostOverrides?.containsKey(id) == true

    /**
     * Merge the edit dialog's values into [base]: only the edited fields change, every other parameter of the host
     * (transport extras, keys, AWG params, …) stays as it was. Blank optional values become null.
     */
    fun merge(base: XrayProfile, fields: Map<String, String>): XrayProfile {
        fun opt(k: String, cur: String?): String? = if (k in fields) fields[k]!!.trim().ifEmpty { null } else cur
        return base.copy(
            remarks = fields["remarks"]?.trim()?.ifEmpty { null } ?: base.remarks,
            server = fields["server"]?.trim()?.ifEmpty { null } ?: base.server,
            port = fields["port"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: base.port,
            id = opt("id", base.id),
            password = opt("password", base.password),
            username = opt("username", base.username),
            method = opt("method", base.method),
            network = fields["network"]?.trim()?.ifEmpty { null } ?: base.network,
            security = opt("security", base.security),
            sni = opt("sni", base.sni),
            fingerprint = opt("fingerprint", base.fingerprint),
            host = opt("host", base.host),
            path = opt("path", base.path),
            serviceName = opt("serviceName", base.serviceName),
            flow = opt("flow", base.flow),
            publicKey = opt("publicKey", base.publicKey),
            shortId = opt("shortId", base.shortId),
            alpn = opt("alpn", base.alpn),
            obfsPassword = opt("obfsPassword", base.obfsPassword),
            customJson = opt("customJson", base.customJson),
        )
    }
}

data class RemoteSubscription(
    val kind: String,
    val title: String?,
    val description: String,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expireEpoch: Long,
    val trafficUsedMb: Double,
    val trafficLimitMb: Double,
    val servers: List<ServerEntry>,
    val announce: String = "",
    val supportUrl: String = "",
    val webPageUrl: String = "",
    val updateIntervalHours: Int = 0,
    val routing: String = "",
    val fileName: String = "",
)
