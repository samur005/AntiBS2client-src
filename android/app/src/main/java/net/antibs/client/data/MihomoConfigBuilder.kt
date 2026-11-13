package net.antibs.client.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Config for libmhx.so (native/mihomo-lite). Each slot = loopback SOCKS5 (127.0.0.1:[BASE_PORT]+i) that dials
 * one profile through a mihomo outbound (ssr / snell v1–v3 / trusttunnel / shadowquic) or the HTTP/3 CONNECT client.
 * Xray reaches the slot through a plain socks outbound (same pattern as sing-box and mieru).
 */
object MihomoConfigBuilder {
    const val BASE_PORT = 11100

    data class Slot(val index: Int, val profile: XrayProfile) {
        val port: Int get() = BASE_PORT + index
    }

    private fun csv(s: String?): List<String> = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun requireServer(p: XrayProfile) {
        if (p.server.isBlank() || p.server == "—") throw IllegalStateException("Не указан адрес сервера")
        if (p.port !in 1..65535) throw IllegalStateException("Неверный порт: ${p.port}")
    }

    /** mihomo proxy mapping (Clash.Meta YAML keys) for one profile. */
    fun proxy(p: XrayProfile): JsonObject {
        requireServer(p)
        return buildJsonObject {
            put("name", p.remarks.ifBlank { p.protocol })
            put("server", p.server)
            put("port", p.port)
            when (p.protocol) {
                "ssr" -> {
                    put("type", "ssr")
                    put("cipher", p.method?.ifBlank { null } ?: "aes-256-cfb")
                    put("password", p.password ?: throw IllegalStateException("SSR: не указан пароль"))
                    put("protocol", p.ssrProtocol?.ifBlank { null } ?: "origin")
                    p.ssrProtocolParam?.ifBlank { null }?.let { put("protocol-param", it) }
                    put("obfs", p.ssrObfs?.ifBlank { null } ?: "plain")
                    p.ssrObfsParam?.ifBlank { null }?.let { put("obfs-param", it) }
                    put("udp", true)
                }
                "snell" -> {
                    val v = p.protoVersion ?: 1
                    put("type", "snell")
                    val psk = p.password?.ifBlank { null } ?: throw IllegalStateException("Snell: не указан PSK")
                    put("psk", psk)
                    put("version", v)
                    put("udp", v >= 3)
                    if (v >= 6) {
                        // 2.0.45 (mihomo 1.19.34): v6 has no simple-obfs (PSK-derived shaping instead); PSK 12–255 bytes
                        if (!ProtocolSupport.snellV6PskOk(psk)) throw IllegalStateException("Snell v6: PSK должен быть от 12 до 255 байт")
                        p.snellMode?.lowercase()?.takeIf { it in ProtocolSupport.SNELL_V6_MODES && it != "default" }?.let { put("mode", it) }
                        if (p.snellReuse) put("reuse", true)
                    } else {
                        if (v == 4 && p.snellReuse) put("reuse", true)
                        val mode = p.headerType?.lowercase()
                        if (mode == "http" || mode == "tls") putJsonObject("obfs-opts") {
                            put("mode", mode)
                            p.host?.ifBlank { null }?.let { put("host", it) }
                        }
                    }
                }
                "trusttunnel" -> {
                    put("type", "trusttunnel")
                    p.username?.ifBlank { null }?.let { put("username", it) }
                    p.password?.ifBlank { null }?.let { put("password", it) }
                    p.sni?.ifBlank { null }?.let { put("sni", it) }
                    val alpn = csv(p.alpn)
                    if (alpn.isNotEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
                    if (p.insecure) put("skip-cert-verify", true)
                    p.fingerprint?.ifBlank { null }?.let { put("client-fingerprint", it) }
                    put("udp", true)
                    if (p.quic) put("quic", true)
                    p.congestionControl?.ifBlank { null }?.let { put("congestion-controller", it) }
                }
                "shadowquic" -> {
                    put("type", "shadowquic")
                    put("username", p.username?.ifBlank { null } ?: throw IllegalStateException("ShadowQUIC: не указан логин"))
                    put("password", p.password?.ifBlank { null } ?: throw IllegalStateException("ShadowQUIC: не указан пароль"))
                    put("sni", p.sni?.ifBlank { null } ?: p.server)
                    val alpn = csv(p.alpn)
                    if (alpn.isNotEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
                    put("udp-over-stream", p.udpOverStream ?: true)
                    if (p.zeroRtt) put("zero-rtt", true)
                    p.congestionControl?.ifBlank { null }?.let { put("congestion-controller", it) }
                    put("udp", true)
                }
                // 1.0.19: plaintext VLESS (no TLS) — refused by Xray 26 for public addresses.
                "vless" -> {
                    put("type", "vless")
                    put("uuid", p.id?.ifBlank { null } ?: throw IllegalStateException("VLESS: не указан UUID"))
                    p.flow?.ifBlank { null }?.let { put("flow", it) }
                    put("tls", false)
                    put("udp", true)
                    put("packet-encoding", "xudp")
                    when (p.network.lowercase()) {
                        "ws", "httpupgrade" -> {
                            put("network", "ws")
                            putJsonObject("ws-opts") {
                                put("path", p.path?.ifBlank { null } ?: "/")
                                p.host?.ifBlank { null }?.let { h -> putJsonObject("headers") { put("Host", h) } }
                                if (p.network.lowercase() == "httpupgrade") put("v2ray-http-upgrade", true)
                            }
                        }
                        "grpc" -> {
                            put("network", "grpc")
                            putJsonObject("grpc-opts") { put("grpc-service-name", p.serviceName ?: "") }
                        }
                        "h2", "http" -> {
                            put("network", "h2")
                            putJsonObject("h2-opts") {
                                put("path", p.path?.ifBlank { null } ?: "/")
                                p.host?.ifBlank { null }?.let { h -> putJsonArray("host") { add(h) } }
                            }
                        }
                        else -> {
                            if (p.headerType == "http") throw IllegalStateException("VLESS: TCP с HTTP-заголовком без TLS не поддерживается")
                            put("network", "tcp")
                        }
                    }
                }
                "mieru" -> {
                    // 1.0.19: mieru v3 via mihomo (libmieru 1.15.1 can't talk to v3 servers).
                    put("type", "mieru")
                    put("transport", (p.mieruTransport ?: "TCP").uppercase().let { if (it == "UDP") "UDP" else "TCP" })
                    put("username", p.username?.ifBlank { null } ?: throw IllegalStateException("Mieru: не указан пользователь"))
                    put("password", p.password?.ifBlank { null } ?: throw IllegalStateException("Mieru: не указан пароль"))
                    p.mieruMultiplexing?.uppercase()?.takeIf { it.startsWith("MULTIPLEXING_") }?.let { put("multiplexing", it) }
                    p.mieruHandshake?.uppercase()?.takeIf { it.startsWith("HANDSHAKE_") }?.let { put("handshake-mode", it) }
                    put("udp", true)
                }
                "amneziawg" -> {
                    // 1.0.19: AmneziaWG through mihomo's wireguard outbound + amnezia-wg-option.
                    put("type", "wireguard")
                    put("private-key", p.secretKey?.ifBlank { null } ?: throw IllegalStateException("AmneziaWG: не указан приватный ключ"))
                    put("public-key", p.publicKey?.ifBlank { null } ?: throw IllegalStateException("AmneziaWG: не указан публичный ключ"))
                    p.preSharedKey?.ifBlank { null }?.let { put("pre-shared-key", it) }
                    val addrs = csv(p.localAddress?.replace(' ', ','))
                    val v4 = addrs.firstOrNull { !it.contains(':') }?.substringBefore('/')
                    val v6 = addrs.firstOrNull { it.contains(':') }?.substringBefore('/')
                    if (v4 == null && v6 == null) throw IllegalStateException("AmneziaWG: не указан адрес интерфейса")
                    v4?.let { put("ip", it) }
                    v6?.let { put("ipv6", it) }
                    putJsonArray("allowed-ips") { add("0.0.0.0/0"); add("::/0") }
                    p.mtu?.takeIf { it in 1100..1500 }?.let { put("mtu", it) }
                    put("udp", true)
                    val o = p.awg.orEmpty()
                    if (o.isNotEmpty()) putJsonObject("amnezia-wg-option") {
                        for (k in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")) o[k]?.trim()?.toIntOrNull()?.let { put(k, it) }
                        for (k in listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")) o[k]?.trim()?.ifEmpty { null }?.let { put(k, it) }
                    }
                }
                else -> throw IllegalArgumentException("mihomo: протокол ${p.protocol} не поддерживается")
            }
        }
    }

    /** HTTP/3 CONNECT options for the quic-go client. */
    fun h3(p: XrayProfile): JsonObject {
        requireServer(p)
        return buildJsonObject {
            put("server", p.server)
            put("port", p.port)
            p.username?.ifBlank { null }?.let { put("username", it) }
            p.password?.ifBlank { null }?.let { put("password", it) }
            (p.sni?.ifBlank { null } ?: p.host?.ifBlank { null })?.let { put("sni", it) }
            p.host?.ifBlank { null }?.let { put("host", it) }
            p.path?.ifBlank { null }?.let { put("path", it) }
            if (p.insecure) put("insecure", true)
        }
    }

    fun fullConfig(slots: List<Slot>): JsonObject = buildJsonObject {
        require(slots.isNotEmpty()) { "нет профилей mihomo" }
        putJsonArray("slots") {
            slots.forEach { s ->
                add(buildJsonObject {
                    put("port", s.port)
                    if (s.profile.protocol == "http3") {
                        put("type", "h3")
                        put("h3", h3(s.profile))
                    } else {
                        put("type", "mihomo")
                        put("proxy", proxy(s.profile))
                    }
                })
            }
        }
    }

    fun toJson(slots: List<Slot>): String = XrayConfigBuilder.toJsonString(fullConfig(slots), pretty = true)
}
