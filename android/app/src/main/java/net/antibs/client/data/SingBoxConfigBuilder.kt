package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Config for the bundled sing-box 1.14 (libsbx.so). It never touches the TUN: every sing-box profile gets
 * its own loopback SOCKS inbound (127.0.0.1:[BASE_PORT]+i) routed 1:1 to its outbound. Xray dials that inbound
 * as a plain socks outbound, so routing, DNS, LAN bypass and traffic counters stay in Xray.
 *
 * If a sing-box server sits behind another hop of a chain, its outbound detours through a socks outbound
 * pointing back at a dedicated Xray inbound (127.0.0.1:[XRAY_BRIDGE_BASE]+i) that Xray routes to the previous hop.
 */
object SingBoxConfigBuilder {
    const val BASE_PORT = 10900
    const val XRAY_BRIDGE_BASE = 10950

    data class Slot(val index: Int, val profile: XrayProfile, val bridgePort: Int? = null) {
        val port: Int get() = BASE_PORT + index
    }

    private fun csv(s: String?): List<String> = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun isIp(s: String): Boolean =
        s.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || s.contains(':')

    /** "443,5000-6000" → ["443:443", "5000:6000"] (sing-box server_ports syntax). */
    fun serverPorts(spec: String?): List<String> = csv(spec).mapNotNull { part ->
        val a = part.substringBefore('-').trim().toIntOrNull() ?: return@mapNotNull null
        val b = if (part.contains('-')) part.substringAfter('-').trim().toIntOrNull() ?: return@mapNotNull null else a
        if (a !in 1..65535 || b !in 1..65535) null else "${minOf(a, b)}:${maxOf(a, b)}"
    }

    private fun mbps(v: String?, def: Int): Int {
        val digits = v?.trim()?.takeWhile { it.isDigit() || it == '.' }.orEmpty()
        return digits.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: def
    }

    private fun sni(p: XrayProfile): String? =
        p.sni?.ifEmpty { null } ?: p.server.takeIf { it.isNotEmpty() && !isIp(it) }

    private fun tls(p: XrayProfile, alpnDefault: List<String> = emptyList(), utls: Boolean = false, allowInsecure: Boolean = true) =
        buildJsonObject {
            put("enabled", true)
            sni(p)?.let { put("server_name", it) }
            if (allowInsecure && p.insecure) put("insecure", true)
            val alpn = csv(p.alpn).ifEmpty { alpnDefault }
            if (alpn.isNotEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
            if (utls) putJsonObject("utls") { put("enabled", true); put("fingerprint", p.fingerprint?.ifEmpty { null } ?: "chrome") }
        }

    /** Translate one profile into a sing-box outbound. Throws for incomplete profiles (shown to the user). */
    fun outbound(p: XrayProfile, tag: String): JsonObject = buildJsonObject {
        when (p.protocol) {
            "hysteria1" -> {
                put("type", "hysteria")
                put("tag", tag)
                server(p)
                val ports = serverPorts(p.portHopping)
                if (ports.size > 1 || (ports.size == 1 && ports[0] != "${p.port}:${p.port}")) {
                    putJsonArray("server_ports") { ports.forEach { add(it) } }
                }
                put("up_mbps", mbps(p.bandwidthUp, 50))
                put("down_mbps", mbps(p.bandwidthDown, 100))
                if (!p.obfsPassword.isNullOrEmpty()) put("obfs", p.obfsPassword)
                if (!p.password.isNullOrEmpty()) put("auth_str", p.password)
                put("tls", tls(p, listOf("hysteria")))
            }
            "tuic" -> {
                put("type", "tuic")
                put("tag", tag)
                server(p)
                put("uuid", p.id?.ifEmpty { null } ?: throw IllegalStateException("TUIC: не указан UUID"))
                put("password", p.password ?: "")
                put("congestion_control", when (p.congestionControl?.lowercase()) {
                    "cubic" -> "cubic"; "new_reno", "newreno", "reno" -> "new_reno"; else -> "bbr"
                })
                put("udp_relay_mode", if (p.udpRelayMode.equals("quic", true)) "quic" else "native")
                put("tls", tls(p, listOf("h3")))
            }
            "anytls" -> {
                put("type", "anytls")
                put("tag", tag)
                server(p)
                put("password", p.password ?: throw IllegalStateException("AnyTLS: не указан пароль"))
                put("tls", tls(p, utls = true))
            }
            "ssh" -> {
                put("type", "ssh")
                put("tag", tag)
                server(p)
                put("user", p.username?.ifEmpty { null } ?: "root")
                if (!p.password.isNullOrEmpty()) put("password", p.password)
                if (!p.privateKey.isNullOrBlank()) putJsonArray("private_key") { add(p.privateKey.trim()) }
                if (!p.privateKeyPassphrase.isNullOrEmpty()) put("private_key_passphrase", p.privateKeyPassphrase)
                val hk = csv(p.hostKey)
                if (hk.isNotEmpty()) putJsonArray("host_key") { hk.forEach { add(it) } }
                if (p.password.isNullOrEmpty() && p.privateKey.isNullOrBlank()) throw IllegalStateException("SSH: нужен пароль или приватный ключ")
            }
            "snell" -> {
                val v = p.protoVersion ?: 4
                if (ProtocolSupport.snellOnMihomo(v)) throw IllegalStateException("Snell v$v идёт через mihomo, не sing-box")
                put("type", "snell")
                put("tag", tag)
                server(p)
                put("version", if (v >= 6) 6 else 4) // v5 servers accept v4 clients
                put("psk", p.password ?: throw IllegalStateException("Snell: не указан PSK"))
                if (v < 6) {
                    val mode = p.headerType?.lowercase()
                    if (mode == "http" || mode == "tls") {
                        put("obfs_mode", mode)
                        p.host?.ifEmpty { null }?.let { put("obfs_host", it) }
                    }
                }
            }
            "socks4" -> {
                put("type", "socks")
                put("tag", tag)
                server(p)
                // SOCKS4A when the user leaves the address as a domain (remote resolve)
                put("version", if (p.method == "4") "4" else "4a")
                if (!p.username.isNullOrEmpty()) put("username", p.username)
            }
            "naive" -> {
                put("type", "naive")
                put("tag", tag)
                server(p)
                if (!p.username.isNullOrEmpty()) put("username", p.username)
                if (!p.password.isNullOrEmpty()) put("password", p.password)
                if (p.network == "quic") put("quic", true)
                val headers = naiveHeaders(p.extra)
                if (headers.isNotEmpty()) putJsonObject("extra_headers") { headers.forEach { (k, v) -> put(k, v) } }
                // cronet: no insecure / alpn / utls on naive
                put("tls", buildJsonObject { put("enabled", true); sni(p)?.let { put("server_name", it) } })
            }
            else -> throw IllegalArgumentException("sing-box: протокол ${p.protocol} не поддерживается")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.server(p: XrayProfile) {
        if (p.server.isBlank() || p.server == "—") throw IllegalStateException("Не указан адрес сервера")
        if (p.port !in 1..65535) throw IllegalStateException("Неверный порт: ${p.port}")
        put("server", p.server)
        put("server_port", p.port)
    }

    /** "Header1: v1\r\nHeader2: v2" or JSON object → map. */
    fun naiveHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val t = raw.trim()
        if (t.startsWith("{")) {
            val o = runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull() ?: return emptyMap()
            return o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.jsonPrimitive?.contentOrNull?.let { k to it } }.toMap()
        }
        return t.split("\r\n", "\n", "|").mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    }

    /**
     * Full sing-box config. [dnsServer] resolves the proxy servers' domains (the sing-box process is outside
     * the VPN, so the underlying network's DNS works; 1.1.1.1 as fallback).
     */
    fun fullConfig(
        slots: List<Slot>, dnsServer: String = "1.1.1.1", ipType: String = "auto", logLevel: String = "warn",
        /** 2.0.43: loopback SOCKS login / password (inbounds + Xray bridge); empty = none. */
        user: String = "", pass: String = "",
    ): JsonObject = buildJsonObject {
        val auth = user.isNotEmpty() && pass.isNotEmpty()
        require(slots.isNotEmpty()) { "нет профилей sing-box" }
        putJsonObject("log") { put("level", logLevel); put("timestamp", false) }
        putJsonObject("dns") {
            putJsonArray("servers") {
                add(buildJsonObject { put("type", "udp"); put("tag", "dns"); put("server", dnsServer) })
            }
            put("strategy", when (ipType) { "ipv6" -> "prefer_ipv6"; "ipv4" -> "ipv4_only"; else -> "prefer_ipv4" })
        }
        putJsonArray("inbounds") {
            slots.forEach { s ->
                add(buildJsonObject {
                    put("type", "socks"); put("tag", "in-${s.index}")
                    put("listen", "127.0.0.1"); put("listen_port", s.port)
                    if (auth) putJsonArray("users") { add(buildJsonObject { put("username", user); put("password", pass) }) }
                })
            }
        }
        putJsonArray("outbounds") {
            slots.forEach { s ->
                val ob = outbound(s.profile, "out-${s.index}")
                add(if (s.bridgePort != null) buildJsonObject { ob.forEach { (k, v) -> put(k, v) }; put("detour", "bridge-${s.index}") } else ob)
                if (s.bridgePort != null) add(buildJsonObject {
                    put("type", "socks"); put("tag", "bridge-${s.index}")
                    put("server", "127.0.0.1"); put("server_port", s.bridgePort); put("version", "5")
                    if (auth) { put("username", user); put("password", pass) }
                })
            }
            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
        }
        putJsonObject("route") {
            putJsonArray("rules") {
                slots.forEach { s ->
                    add(buildJsonObject { putJsonArray("inbound") { add("in-${s.index}") }; put("outbound", "out-${s.index}") })
                }
            }
            put("final", "out-${slots.first().index}")
            put("default_domain_resolver", "dns")
        }
    }

    fun toJson(slots: List<Slot>, dnsServer: String = "1.1.1.1", ipType: String = "auto", user: String = "", pass: String = ""): String =
        XrayConfigBuilder.toJsonString(fullConfig(slots, dnsServer, ipType, user = user, pass = pass), pretty = true)
}
