package net.antibs.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoCatalogTest {
    // exactly what the 1.0.24 chip list offered
    private val offered = listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria1", "tuic", "wireguard",
        "anytls", "socks", "http", "https", "http3", "ssh", "snell", "naive", "mieru", "socks4", "ssr", "trusttunnel",
        "shadowquic", "chain", "balancer", "custom",
        // 1.0.26: supported by import/engines, now creatable by hand too
        "amneziawg", "qwdtt", "amneziakey",
        // 1.0.41
        "olcrtc", "openvpn", "openconnect", "sstp", "zerotier")

    @Test fun sameProtocolsNoDuplicates() {
        val p = ProtoCatalog.all.map { it.protocol }
        assertEquals(p.size, p.toSet().size)
        assertEquals(offered.toSet(), p.toSet())
        assertEquals(listOf("Xray", "QUIC / UDP", "Другие", "WebRTC и VPN", "Расширенные"), ProtoCatalog.groups.map { it.title })
        assertEquals(listOf("chain", "balancer", "custom"), ProtoCatalog.groups.last().items.map { it.protocol })
        ProtoCatalog.all.forEach { assertTrue(it.label, it.subtitle.isNotBlank() && it.monogram.isNotBlank()) }
    }

    @Test fun rowsOfThreePadded() {
        val r = ProtoCatalog.rows(listOf(1, 2, 3, 4))
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, null, null)), r)
        ProtoCatalog.groups.forEach { g -> ProtoCatalog.rows(g.items).forEach { assertEquals(3, it.size) } }
    }
}
