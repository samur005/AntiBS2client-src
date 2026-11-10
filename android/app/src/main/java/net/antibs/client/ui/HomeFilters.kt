package net.antibs.client.ui

import net.antibs.client.data.AutoSelect
import net.antibs.client.data.EngineKind
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.ServerGrouping

/**
 * 2.0.36: СЕТЬ-style Home filters — IPv4 / IPv6 / Антиблок / Мосты, then one tab per protocol
 * present in the current list (zero-count tabs are hidden). Country chips combine with the tab (AND).
 * Empty tab+country = no filter (subscription banners). A selected tab or country shows a flat list.
 * 2.0.39: «Мосты» matches local chain profiles, a route in the name (arrows / 2+ distinct flags),
 * or bridge keywords; country for those hosts is the exit (last) flag.
 */
object HomeFilters {
    const val TAB_IPV6 = "IPv6"
    const val TAB_IPV4 = "IPv4"
    const val TAB_ANTIBLOCK = "Антиблок"
    const val TAB_BRIDGES = "Мосты"
    /** Sentinel for the «Все» chip (not a real tab filter). Empty string also means no tab. */
    const val TAB_ALL = "__all__"
    const val COUNTRY_ALL = ""

    fun isAllTab(tab: String): Boolean = tab.isEmpty() || tab == TAB_ALL

    data class Tab(val id: String, val label: String, val count: Int)
    data class Country(val code: String, val flag: String, val name: String, val count: Int, val bestPing: Long?)

    private val IPV4_RE = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val MARK_V6 = Regex("""(?i)(?<![a-z0-9])(ipv6|ip-v6|aaaa)(?![a-z0-9])|(?<![a-z0-9])v6(?![a-z0-9])""")
    private val MARK_V4 = Regex("""(?i)(?<![a-z0-9])(ipv4|ip-v4)(?![a-z0-9])|(?<![a-z0-9])v4(?![a-z0-9])""")
    private val ANTIBLOCK_RE = Regex("""(?i)q?wdtt|(?<![a-z0-9а-яё])4g(?![a-z0-9а-яё])""")
    private val IPV4_IN_TEXT = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    /** Arrow between hops, including a spaced ASCII '>'. */
    private val ROUTE_ARROW_RE = Regex("""→|->|=>|➡|⇒|»|›|\s>\s""")
    /** Whole-word or word-start, not preceded by a letter (so «Телемост» does not match «мост»). */
    private val BRIDGE_KEYWORD_RE = Regex(
        """(?iu)(?<![\p{L}])(мосты|мост|каскад|bridges?|cascades?|relays?|via|через)""",
    )

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

    /** 2.0.39: local chain profile, a hop route in the name, or a bridge/cascade keyword. */
    fun isBridge(s: ServerEntry): Boolean =
        s.xray?.protocol.equals("chain", ignoreCase = true) == true || nameIsBridge(s.name)

    /**
     * Pure name-side test for [isBridge] (no GeoIP). A name is a bridge/cascade when it has
     * a hop arrow, two or more distinct country-flag emojis, or a keyword (мост, каскад, bridge, …).
     */
    fun nameIsBridge(name: String): Boolean {
        if (name.isBlank()) return false
        if (ROUTE_ARROW_RE.containsMatchIn(name)) return true
        if (flagEmojis(name).distinct().size >= 2) return true
        return BRIDGE_KEYWORD_RE.containsMatchIn(name)
    }

    /** Regional-indicator flag emoji pairs in [name], in order of appearance. */
    fun flagEmojis(name: String): List<String> {
        val cps = name.codePoints().toArray()
        val out = ArrayList<String>()
        var i = 0
        while (i < cps.size - 1) {
            if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) {
                out += String(cps, i, 2)
                i += 2
            } else i++
        }
        return out
    }

    /** ISO country code of the last flag in [name], or null when there is none. */
    fun lastCountryCode(name: String): String? {
        val flag = UiLogic.exitFlag(name)
        if (flag == "🌐") return null
        return ServerGrouping.countryCode(flag)
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
        TAB_BRIDGES -> isBridge(s)
        else -> protocolTab(s) == tab
    }

    /** Exit (last) flag for a bridge/cascade; otherwise the first flag, else «Другие». */
    fun countryCode(s: ServerEntry): String {
        if (isBridge(s)) lastCountryCode(s.name)?.let { return it }
        return ServerGrouping.countryCode(s.name) ?: ServerGrouping.OTHER
    }

    fun matchesCountry(s: ServerEntry, country: String): Boolean =
        country.isEmpty() || country == COUNTRY_ALL || countryCode(s) == country

    fun apply(servers: List<ServerEntry>, tab: String, country: String): List<ServerEntry> =
        servers.filter { (isAllTab(tab) || matchesTab(it, tab)) && matchesCountry(it, country) }

    /**
     * 2.0.37: servers one filter chip itself matches, not intersected with the other row.
     * [tab] set → that protocol/IP/Antiblock tab; [country] set → that country;
     * both blank («Все страны») → [servers] as given (all visible, unscoped).
     */
    fun chipTargets(servers: List<ServerEntry>, tab: String = "", country: String = ""): List<ServerEntry> = when {
        isAllTab(tab) && (country.isBlank() || country == COUNTRY_ALL) -> servers
        !isAllTab(tab) && tab.isNotBlank() -> servers.filter { matchesTab(it, tab) }
        country.isNotBlank() && country != COUNTRY_ALL -> servers.filter { matchesCountry(it, country) }
        else -> servers
    }

    fun chipLabel(tab: String, country: String, countries: List<Country>): String = when {
        tab == TAB_ALL -> "Все"
        tab.isNotBlank() -> tab
        country.isNotBlank() && country != COUNTRY_ALL ->
            countries.firstOrNull { it.code == country }?.name ?: country
        else -> "Все страны"
    }

    fun chipMenuKey(tab: String = "", country: String = ""): String = when {
        tab == TAB_ALL -> "tab:all"
        tab.isNotBlank() -> "tab:$tab"
        country.isNotBlank() && country != COUNTRY_ALL -> "country:$country"
        else -> "country:"
    }

    fun tabTestTag(id: String): String = "filter-tab:" + if (isAllTab(id)) "all" else id
    fun countryTestTag(code: String): String = "filter-country:" + if (code.isEmpty() || code == COUNTRY_ALL) "all" else code

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
        addFixed(TAB_BRIDGES, ::isBridge)
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
        val grouped = LinkedHashMap<String, MutableList<ServerEntry>>()
        for (s in servers) grouped.getOrPut(countryCode(s)) { ArrayList() }.add(s)
        val other = grouped.remove(ServerGrouping.OTHER)
        val keys = grouped.keys.sortedBy { ServerGrouping.countryName(it).lowercase() }
        fun toCountry(code: String, list: List<ServerEntry>) = Country(
            code = code,
            flag = if (code == ServerGrouping.OTHER) "🌐" else ServerGrouping.flag(code),
            name = ServerGrouping.countryName(code),
            count = list.size,
            bestPing = ServerGrouping.bestPing(list.map { pings[it.id] }),
        )
        return keys.map { toCountry(it, grouped.getValue(it)) } +
            listOfNotNull(other?.let { toCountry(ServerGrouping.OTHER, it) })
    }

    /** Keep [selected] when it still exists; otherwise no tab (""). Never auto-picks IPv4. */
    fun resolveTab(selected: String, tabs: List<Tab>): String =
        if (!isAllTab(selected) && selected.isNotBlank() && tabs.any { it.id == selected }) selected else ""

    /** Row 1 is single-select and toggleable: the same chip or «Все» clears the tab. */
    fun toggleTab(current: String, tapped: String): String =
        if (isAllTab(tapped) || current == tapped) "" else tapped

    /** «Все страны» or a second tap on the active country clears the country filter. */
    fun toggleCountry(current: String, tapped: String): String =
        if (tapped.isBlank() || tapped == COUNTRY_ALL || current == tapped) "" else tapped

    fun filterActive(tab: String, country: String): Boolean = !isAllTab(tab) && tab.isNotBlank() || country.isNotBlank()

    /** Title above the flat filtered list, e.g. «IPv4», «Германия», «IPv4 · Германия». */
    fun caption(tab: String, country: String, countries: List<Country>): String {
        val t = if (isAllTab(tab)) "" else tab
        val ctry = countries.firstOrNull { it.code == country }?.name.orEmpty()
        return listOf(t, ctry).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Keep [selected] when that country is still present; otherwise «Все страны». */
    fun resolveCountry(selected: String, countries: List<Country>): String =
        if (selected.isEmpty() || selected == COUNTRY_ALL || countries.any { it.code == selected }) {
            if (selected == COUNTRY_ALL) "" else selected.takeIf { countries.any { c -> c.code == it } }.orEmpty()
        } else ""
}
