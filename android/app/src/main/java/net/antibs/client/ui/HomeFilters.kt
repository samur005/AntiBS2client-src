package net.antibs.client.ui

import net.antibs.client.data.AutoSelect
import net.antibs.client.data.EngineKind
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.ServerGrouping

/**
 * 2.0.36: СЕТЬ-style Home filters — IPv4 / IPv6 / Антиблок, then one tab per protocol
 * present in the current list (zero-count tabs are hidden). Country chips combine with the tab (AND).
 * Empty tab+country = no filter (subscription banners). A selected tab or country shows a flat list.
 */
object HomeFilters {
    const val TAB_IPV6 = "IPv6"
    const val TAB_IPV4 = "IPv4"
    const val TAB_ANTIBLOCK = "Антиблок"
    const val COUNTRY_ALL = ""

    data class Tab(val id: String, val label: String, val count: Int)
    data class Country(val code: String, val flag: String, val name: String, val count: Int, val bestPing: Long?)

    private val IPV4_RE = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val MARK_V6 = Regex("""(?i)(?<![a-z0-9])(ipv6|ip-v6|aaaa)(?![a-z0-9])|(?<![a-z0-9])v6(?![a-z0-9])""")
    private val MARK_V4 = Regex("""(?i)(?<![a-z0-9])(ipv4|ip-v4)(?![a-z0-9])|(?<![a-z0-9])v4(?![a-z0-9])""")
    private val ANTIBLOCK_RE = Regex("""(?i)q?wdtt|(?<![a-z0-9а-яё])4g(?![a-z0-9а-яё])""")
    private val IPV4_IN_TEXT = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")

    private val PROTO_ORDER = listOf(
        "VLESS Reality", "VLESS WS", "Trojan", "SS", "HY2", "TUIC", "WG", "gRPC", "xHTTP",
        "VMess", "VMess WS", "VLESS", "AnyTLS", "SOCKS5", "HTTP", "Hysteria", "AmneziaWG",
    )

    fun hostAddress(s: ServerEntry): String {
        val x = s.xray?.server?.trim().orEmpty()
        if (x.isNotEmpty() && x != "—") return x.removePrefix("[").removeSuffix("]").substringBefore('%')
        val peer = s.qwdtt?.peer?.trim().orEmpty()
        if (peer.isEmpty()) return ""
        // peer may be "host:port"; IPv6 in brackets or with more than one colon stays as-is (drop port only for host:port v4).
        return if (peer.startsWith("[")) peer.substringAfter("[").substringBefore(']')
        else if (peer.count { it == ':' } == 1 && IPV4_RE.matches(peer.substringBefore(':'))) peer.substringBefore(':')
        else peer
    }

    fun isIpv4Literal(addr: String): Boolean = IPV4_RE.matches(addr.trim())

    fun isIpv6Literal(addr: String): Boolean {
        val a = addr.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (a.isEmpty() || ':' !in a) return false
        return a.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    fun hasIpv6(s: ServerEntry): Boolean {
        val addr = hostAddress(s)
        if (isIpv6Literal(addr)) return true
        if (MARK_V6.containsMatchIn(s.name) || MARK_V6.containsMatchIn(s.xray?.remarks.orEmpty())) return true
        val loc = s.xray?.localAddress.orEmpty()
        if (loc.split(',', ' ', ';').any { isIpv6Literal(it.substringBefore('/').trim()) }) return true
        return false
    }

    fun hasIpv4(s: ServerEntry): Boolean {
        val addr = hostAddress(s)
        if (isIpv4Literal(addr)) return true
        if (MARK_V4.containsMatchIn(s.name) || MARK_V4.containsMatchIn(s.xray?.remarks.orEmpty())) return true
        val loc = s.xray?.localAddress.orEmpty()
        if (IPV4_IN_TEXT.containsMatchIn(loc)) return true
        // IPv6-only literal (no v4 name / localAddress) stays out of the IPv4 tab.
        if (isIpv6Literal(addr)) return false
        // Hostnames are treated as IPv4 (dual-stack is not probed at filter time).
        return addr.isNotEmpty() || !hasIpv6(s)
    }

    fun isAntiblock(s: ServerEntry): Boolean {
        if (s.engine == EngineKind.QWDTT) return true
        if (s.xray?.protocol.equals("qwdtt", true)) return true
        val n = s.name + " " + s.xray?.remarks.orEmpty()
        return ANTIBLOCK_RE.containsMatchIn(n)
    }

    /** Compact protocol tab for a host; null = no protocol tab (qWDTT / auto-select). */
    fun protocolTab(s: ServerEntry): String? {
        if (s.engine == EngineKind.QWDTT) return null
        if (AutoSelect.isAnyAuto(s)) return null
        val p = s.xray ?: return UiLogic.protocolGroup(s).takeIf { it.isNotBlank() && it != "qWDTT" }
        if (p.protocol.equals("qwdtt", true)) return null
        val proto = p.protocol.lowercase()
        val net = p.network.lowercase()
        val sec = p.security?.lowercase()
        return when {
            sec == "reality" && proto == "vless" -> "VLESS Reality"
            sec == "reality" -> "${Format.protocolLabel(p.protocol)} Reality"
            net in setOf("grpc", "gun") -> "gRPC"
            net in setOf("xhttp", "splithttp") -> "xHTTP"
            proto == "vless" && net in setOf("ws", "websocket", "httpupgrade") -> "VLESS WS"
            proto == "vmess" && net in setOf("ws", "websocket", "httpupgrade") -> "VMess WS"
            proto == "shadowsocks" || proto == "ss" -> "SS"
            proto in setOf("hysteria", "hysteria2", "hy2") -> "HY2"
            proto in setOf("wireguard", "amneziawg") -> "WG"
            proto == "trojan" -> "Trojan"
            proto == "tuic" -> "TUIC"
            else -> Format.protocolLabel(p.protocol)
        }
    }

    fun matchesTab(s: ServerEntry, tab: String): Boolean = when (tab) {
        TAB_IPV6 -> hasIpv6(s)
        TAB_IPV4 -> hasIpv4(s)
        TAB_ANTIBLOCK -> isAntiblock(s)
        else -> protocolTab(s) == tab
    }

    fun countryCode(s: ServerEntry): String =
        ServerGrouping.countryCode(s.name) ?: ServerGrouping.OTHER

    fun matchesCountry(s: ServerEntry, country: String): Boolean =
        country.isEmpty() || country == COUNTRY_ALL || countryCode(s) == country

    fun apply(servers: List<ServerEntry>, tab: String, country: String): List<ServerEntry> =
        servers.filter { (tab.isBlank() || matchesTab(it, tab)) && matchesCountry(it, country) }

    fun tabs(servers: List<ServerEntry>): List<Tab> {
        if (servers.isEmpty()) return emptyList()
        val out = mutableListOf<Tab>()
        fun addFixed(id: String, pred: (ServerEntry) -> Boolean) {
            val n = servers.count(pred)
            if (n > 0) out += Tab(id, id, n)
        }
        addFixed(TAB_IPV4, ::hasIpv4)
        addFixed(TAB_IPV6, ::hasIpv6)
        addFixed(TAB_ANTIBLOCK, ::isAntiblock)
        val protoCounts = LinkedHashMap<String, Int>()
        for (s in servers) {
            val t = protocolTab(s) ?: continue
            protoCounts[t] = (protoCounts[t] ?: 0) + 1
        }
        val ordered = protoCounts.keys.sortedWith(
            compareBy<String> { PROTO_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.lowercase() },
        )
        for (id in ordered) {
            val n = protoCounts[id] ?: continue
            if (n > 0) out += Tab(id, id, n)
        }
        return out
    }

    fun countries(servers: List<ServerEntry>, pings: Map<String, Long> = emptyMap()): List<Country> {
        if (servers.isEmpty()) return emptyList()
        val grouped = ServerGrouping.group(servers, { it.name }, ServerGrouping.SORT_NAME, ping = { pings[it.id] })
        return grouped.map { (code, list) ->
            Country(
                code = code,
                flag = if (code == ServerGrouping.OTHER) "🌐" else ServerGrouping.flag(code),
                name = ServerGrouping.countryName(code),
                count = list.size,
                bestPing = ServerGrouping.bestPing(list.map { pings[it.id] }),
            )
        }
    }

    /** Keep [selected] when it still exists; otherwise no tab (""). Never auto-picks IPv4. */
    fun resolveTab(selected: String, tabs: List<Tab>): String =
        if (selected.isNotBlank() && tabs.any { it.id == selected }) selected else ""

    /** Row 1 is single-select and toggleable: the same chip again clears the tab. */
    fun toggleTab(current: String, tapped: String): String =
        if (tapped.isBlank() || current == tapped) "" else tapped

    /** «Все страны» or a second tap on the active country clears the country filter. */
    fun toggleCountry(current: String, tapped: String): String =
        if (tapped.isBlank() || tapped == COUNTRY_ALL || current == tapped) "" else tapped

    fun filterActive(tab: String, country: String): Boolean = tab.isNotBlank() || country.isNotBlank()

    /** Keep [selected] when that country is still present; otherwise «Все страны». */
    fun resolveCountry(selected: String, countries: List<Country>): String =
        if (selected.isEmpty() || selected == COUNTRY_ALL || countries.any { it.code == selected }) {
            if (selected == COUNTRY_ALL) "" else selected.takeIf { countries.any { c -> c.code == it } }.orEmpty()
        } else ""
}
