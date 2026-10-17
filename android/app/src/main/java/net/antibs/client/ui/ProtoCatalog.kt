package net.antibs.client.ui

/**
 * 1.0.25: «Создать вручную» protocol picker — grouped grid of cards (pure data, unit-tested).
 * [protocol] values are exactly what ManualFormDialog accepts.
 */
object ProtoCatalog {
    enum class Family(val argb: Long) {
        XRAY(0xFF4C8DFF), QUIC(0xFF7A5CFF), OTHER(0xFF1FA89A), VPN(0xFFE0457B), ADVANCED(0xFFFF8A4C)
    }

    data class Item(val label: String, val protocol: String, val subtitle: String, val monogram: String, val family: Family)
    data class Group(val title: String, val family: Family, val items: List<Item>)

    const val COLUMNS = 3

    val groups: List<Group> = listOf(
        Group("Xray", Family.XRAY, listOf(
            Item("VLESS", "vless", "Reality / TLS", "VL", Family.XRAY),
            Item("VMess", "vmess", "WS / gRPC / TCP", "VM", Family.XRAY),
            Item("Trojan", "trojan", "TLS", "TR", Family.XRAY),
            Item("Shadowsocks", "shadowsocks", "AEAD / 2022", "SS", Family.XRAY),
            Item("SOCKS5", "socks", "Прокси", "S5", Family.XRAY),
            Item("HTTP", "http", "Прокси", "HT", Family.XRAY),
            Item("HTTPS", "https", "HTTP + TLS", "HS", Family.XRAY),
        )),
        Group("QUIC / UDP", Family.QUIC, listOf(
            Item("Hysteria2", "hysteria", "QUIC", "H2", Family.QUIC),
            Item("Hysteria", "hysteria1", "QUIC", "HY", Family.QUIC),
            Item("TUIC", "tuic", "QUIC", "TU", Family.QUIC),
            Item("WireGuard", "wireguard", "UDP", "WG", Family.QUIC),
            Item("AmneziaWG", "amneziawg", "WG + обфускация", "AW", Family.QUIC),
        )),
        Group("Другие", Family.OTHER, listOf(
            Item("AnyTLS", "anytls", "TLS", "AT", Family.OTHER),
            Item("Naive", "naive", "HTTP/2 · QUIC", "NV", Family.OTHER),
            Item("SSH", "ssh", "Туннель", "SH", Family.OTHER),
            Item("Snell", "snell", "Surge", "SN", Family.OTHER),
            Item("Mieru", "mieru", "TCP / UDP", "MI", Family.OTHER),
            Item("ShadowsocksR", "ssr", "SSR", "SR", Family.OTHER),
            Item("ShadowQUIC", "shadowquic", "QUIC", "SQ", Family.OTHER),
            Item("TrustTunnel", "trusttunnel", "HTTP/2 · HTTP/3", "TT", Family.OTHER),
            Item("HTTP/3", "http3", "QUIC-прокси", "H3", Family.OTHER),
            Item("SOCKS4", "socks4", "SOCKS4 / 4a", "S4", Family.OTHER),
            Item("qWDTT", "qwdtt", "VK TURN → WG", "QW", Family.OTHER),
            Item("Amnezia ключ", "amneziakey", "vpn://", "AK", Family.OTHER),
        )),
        // 1.0.41: tunnels that look like calls / classic VPNs
        Group("WebRTC и VPN", Family.VPN, listOf(
            Item("olcRTC", "olcrtc", "Телемост / Jitsi", "OL", Family.VPN),
            Item("OpenVPN", "openvpn", ".ovpn", "OV", Family.VPN),
            Item("OpenConnect", "openconnect", "AnyConnect", "OC", Family.VPN),
        )),
        Group("Расширенные", Family.ADVANCED, listOf(
            Item("Прокси цепь", "chain", "Через несколько", "⛓", Family.ADVANCED),
            Item("Балансировщик", "balancer", "Группа серверов", "⚖", Family.ADVANCED),
            Item("Свой JSON", "custom", "Конфиг Xray", "{ }", Family.ADVANCED),
        )),
    )

    val all: List<Item> get() = groups.flatMap { it.items }

    /** Rows of [COLUMNS]; the last row is padded with nulls so cards keep the same width. */
    fun <T> rows(items: List<T>, columns: Int = COLUMNS): List<List<T?>> =
        items.chunked(columns).map { r -> r + List(columns - r.size) { null } }
}
