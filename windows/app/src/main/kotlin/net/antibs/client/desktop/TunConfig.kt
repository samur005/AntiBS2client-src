package net.antibs.client.desktop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * System-wide VPN on Windows: sing-box TUN (wintun, auto_route + strict_route) that hands every TCP/UDP flow to the
 * local Xray SOCKS inbound — the desktop equivalent of Android's VpnService + hev-socks5-tunnel. Our own engine
 * processes go straight out (no loop); LAN stays direct when [bypassLan]. DNS is hijacked and resolved through the
 * tunnel over TCP, so the provider's resolver never sees the names.
 */
object TunConfig {
    fun build(
        socksPort: Int,
        mtu: Int = 9000,
        bypassLan: Boolean = true,
        dns: String = "1.1.1.1",
        ipv6: Boolean = true,
        excludeProcesses: List<String> = Platform.current.engineProcessNames,
        logFile: String? = null,
        excludeCidrs: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        putJsonObject("log") { put("level", "warn"); if (logFile != null) put("output", logFile); put("timestamp", true) }
        putJsonObject("dns") {
            putJsonArray("servers") {
                addJsonObject { put("type", "tcp"); put("tag", "remote"); put("server", dns); put("detour", "proxy") }
                addJsonObject { put("type", "local"); put("tag", "local") }
            }
            put("final", "remote")
            put("strategy", if (ipv6) "prefer_ipv4" else "ipv4_only")
        }
        putJsonArray("inbounds") {
            addJsonObject {
                put("type", "tun"); put("tag", "tun-in")
                put("interface_name", "AntiBS")
                putJsonArray("address") { add("172.19.0.1/30"); if (ipv6) add("fdfe:dcba:9876::1/126") }
                put("mtu", mtu.coerceIn(1280, 9000))
                put("auto_route", true); put("strict_route", true)
                put("stack", "mixed")
                if (excludeCidrs.isNotEmpty()) putJsonArray("route_exclude_address") { excludeCidrs.forEach { add(it) } }
            }
        }
        putJsonArray("outbounds") {
            addJsonObject { put("type", "socks"); put("tag", "proxy"); put("server", "127.0.0.1"); put("server_port", socksPort); put("version", "5") }
            addJsonObject { put("type", "direct"); put("tag", "direct") }
        }
        putJsonObject("route") {
            put("auto_detect_interface", true)
            put("default_domain_resolver", "local")
            put("final", "proxy")
            putJsonArray("rules") {
                addJsonObject { put("action", "sniff") }
                addJsonObject { put("protocol", "dns"); put("action", "hijack-dns") }
                if (excludeProcesses.isNotEmpty()) addJsonObject { putJsonArray("process_name") { excludeProcesses.forEach { add(it) } }; put("outbound", "direct") }
                if (bypassLan) addJsonObject { put("ip_is_private", true); put("outbound", "direct") }
            }
        }
    }
}
