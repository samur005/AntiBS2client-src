package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.QwdttProfile
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.ServerGrouping
import net.antibs.client.data.XrayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFiltersTest {
    private fun xray(
        name: String,
        protocol: String = "vless",
        server: String = "example.com",
        security: String? = null,
        network: String = "tcp",
        remarks: String? = null,
        localAddress: String? = null,
    ) = ServerEntry(
        id = name, engine = EngineKind.XRAY, name = name,
        subscriptionId = "s1",
        xray = XrayProfile(remarks = remarks ?: name, protocol = protocol, server = server, port = 443,
            network = network, security = security, localAddress = localAddress),
    )

    private fun qwdtt(name: String, peer: String = "1.2.3.4") = ServerEntry(
        id = name, engine = EngineKind.QWDTT, name = name, subscriptionId = "s1",
        qwdtt = QwdttProfile(name = name, peer = peer),
    )

    @Test fun ipv4HostnamesAndLiterals() {
        assertTrue(HomeFilters.hasIpv4(xray("🇩🇪 Франкфурт", server = "de.example.com")))
        assertTrue(HomeFilters.hasIpv4(xray("v4", server = "8.8.8.8")))
        assertFalse(HomeFilters.hasIpv4(xray("🇩🇪 v6 only", server = "2001:db8::1")))
        assertTrue(HomeFilters.hasIpv4(xray("🇩🇪 Dual IPv4", server = "2001:db8::1"))) // name marks v4
    }

    @Test fun ipv6LiteralAndName() {
        assertTrue(HomeFilters.hasIpv6(xray("DE IPv6", server = "2001:db8::1")))
        assertTrue(HomeFilters.hasIpv6(xray("🇩🇪 Helsinki IPv6", server = "fi.example.com")))
        assertFalse(HomeFilters.hasIpv6(xray("🇩🇪 Франкфурт", server = "de.example.com")))
        assertTrue(HomeFilters.hasIpv6(xray("WG", protocol = "wireguard", server = "wg.example.com", localAddress = "10.0.0.2/32,fd00::2/128")))
    }

    @Test fun antiblockIsQwdttWdttAnd4g() {
        assertTrue(HomeFilters.isAntiblock(qwdtt("Амстердам WDTT")))
        assertTrue(HomeFilters.isAntiblock(xray("🇫🇷 Лион 4G")))
        assertTrue(HomeFilters.isAntiblock(xray("qWDTT node", protocol = "vless")))
        assertFalse(HomeFilters.isAntiblock(xray("🇩🇪 Франкфурт")))
    }

    @Test fun protocolTabsAreCompactAndSkipQwdtt() {
        assertEquals("VLESS Reality", HomeFilters.protocolTab(xray("r", security = "reality", protocol = "vless")))
        assertEquals("VLESS WS", HomeFilters.protocolTab(xray("w", protocol = "vless", network = "ws")))
        assertEquals("gRPC", HomeFilters.protocolTab(xray("g", protocol = "vless", network = "grpc")))
        assertEquals("xHTTP", HomeFilters.protocolTab(xray("x", protocol = "vless", network = "xhttp")))
        assertEquals("Trojan", HomeFilters.protocolTab(xray("t", protocol = "trojan")))
        assertEquals("SS", HomeFilters.protocolTab(xray("s", protocol = "shadowsocks")))
        assertEquals("HY2", HomeFilters.protocolTab(xray("h", protocol = "hysteria")))
        assertEquals("WG", HomeFilters.protocolTab(xray("w", protocol = "wireguard")))
        assertEquals("TUIC", HomeFilters.protocolTab(xray("u", protocol = "tuic")))
        assertEquals(null, HomeFilters.protocolTab(qwdtt("4G")))
    }

    @Test fun tabsOrderAndHideZero() {
        val servers = listOf(
            xray("🇩🇪 Франкфурт", security = "reality"),
            xray("🇳🇱 Амстердам IPv6", server = "2001:db8::2", security = "reality"),
            xray("🇩🇪 Мюнхен", protocol = "trojan"),
            qwdtt("🇳🇱 Амстердам WDTT"),
            xray("🇵🇱 Варшава 4G"),
        )
        val tabs = HomeFilters.tabs(servers)
        assertEquals(listOf("IPv4", "IPv6", "Антиблок", "VLESS Reality", "Trojan", "VLESS"), tabs.map { it.id })
        assertEquals(1, tabs.first { it.id == "IPv6" }.count)
        assertTrue(tabs.first { it.id == "IPv4" }.count >= 4)
        assertEquals(2, tabs.first { it.id == "Антиблок" }.count)
        assertTrue(tabs.none { it.id == "SS" })
    }

    @Test fun filterCombinesTabAndCountry() {
        val servers = listOf(
            xray("🇩🇪 Франкфурт", protocol = "trojan"),
            xray("🇩🇪 Гамбург", protocol = "trojan"),
            xray("🇳🇱 Амстердам", protocol = "trojan"),
            xray("🇩🇪 Берлин", security = "reality"),
        )
        val de = HomeFilters.apply(servers, "Trojan", "DE").map { it.id }
        assertEquals(listOf("🇩🇪 Франкфурт", "🇩🇪 Гамбург"), de)
        val all = HomeFilters.apply(servers, "Trojan", "").map { it.name }
        assertEquals(3, all.size)
        assertEquals(4, HomeFilters.apply(servers, "", "").size)
        assertEquals(3, HomeFilters.apply(servers, "", "DE").size)
    }

    @Test fun countriesShowFlagNameAndBestPing() {
        val servers = listOf(xray("🇩🇪 Франкфурт"), xray("🇩🇪 Берлин"), xray("🇳🇱 Амстердам"))
        val pings = mapOf("🇩🇪 Франкфурт" to 38L, "🇩🇪 Берлин" to 44L, "🇳🇱 Амстердам" to 52L)
        val cs = HomeFilters.countries(servers, pings)
        assertEquals("DE", cs[0].code)
        assertEquals("Германия", cs[0].name)
        assertEquals("🇩🇪", cs[0].flag)
        assertEquals(2, cs[0].count)
        assertEquals(38L, cs[0].bestPing)
        assertEquals("NL", cs[1].code)
    }

    @Test fun resolveResetsMissingTabAndCountry() {
        val tabs = listOf(HomeFilters.Tab("IPv6", "IPv6", 1), HomeFilters.Tab("IPv4", "IPv4", 4), HomeFilters.Tab("Trojan", "Trojan", 2))
        assertEquals("Trojan", HomeFilters.resolveTab("Trojan", tabs))
        assertEquals("", HomeFilters.resolveTab("HY2", tabs))
        assertEquals("", HomeFilters.resolveTab("", tabs))
        assertEquals("IPv4", HomeFilters.toggleTab("", "IPv4"))
        assertEquals("", HomeFilters.toggleTab("IPv4", "IPv4"))
        assertEquals("IPv6", HomeFilters.toggleTab("IPv4", "IPv6"))
        assertEquals("DE", HomeFilters.toggleCountry("", "DE"))
        assertEquals("", HomeFilters.toggleCountry("DE", "DE"))
        assertEquals("", HomeFilters.toggleCountry("DE", HomeFilters.COUNTRY_ALL))
        assertFalse(HomeFilters.filterActive("", ""))
        assertTrue(HomeFilters.filterActive("IPv4", ""))
        assertTrue(HomeFilters.filterActive("", "DE"))
        val countries = listOf(HomeFilters.Country("DE", "🇩🇪", "Германия", 2, 38), HomeFilters.Country("NL", "🇳🇱", "Нидерланды", 1, 52))
        assertEquals("DE", HomeFilters.resolveCountry("DE", countries))
        assertEquals("", HomeFilters.resolveCountry("US", countries))
        assertEquals("", HomeFilters.resolveCountry("", countries))
    }

    @Test fun otherCountryForNamelessHosts() {
        val servers = listOf(xray("mystery-node"))
        val cs = HomeFilters.countries(servers)
        assertEquals(ServerGrouping.OTHER, cs.single().code)
        assertEquals("Другие", cs.single().name)
    }

    @Test fun captionJoinsTabAndCountry() {
        val countries = listOf(
            HomeFilters.Country("DE", "🇩🇪", "Германия", 2, 38),
            HomeFilters.Country("NL", "🇳🇱", "Нидерланды", 1, 52),
        )
        assertEquals("IPv4", HomeFilters.caption("IPv4", "", countries))
        assertEquals("Германия", HomeFilters.caption("", "DE", countries))
        assertEquals("IPv4 · Германия", HomeFilters.caption("IPv4", "DE", countries))
        assertEquals("IPv4", HomeFilters.caption("IPv4", "US", countries)) // unknown country omitted
        assertEquals("", HomeFilters.caption("", "", countries))
    }

    @Test fun chipTargetsAreTheChipsOwnSetNotAnIntersection() {
        val de4 = xray("🇩🇪 Франкфурт", server = "8.8.8.8")
        val de6 = xray("🇩🇪 v6", server = "2001:db8::1")
        val nl4 = xray("🇳🇱 Амстердам", server = "1.1.1.1")
        val all = listOf(de4, de6, nl4)
        assertEquals(2, HomeFilters.chipTargets(all, tab = "IPv4").size)
        assertEquals(setOf(de4.id, de6.id), HomeFilters.chipTargets(all, country = "DE").map { it.id }.toSet())
        assertEquals(all, HomeFilters.chipTargets(all, tab = "", country = ""))
        assertEquals("IPv4", HomeFilters.chipLabel("IPv4", "", emptyList()))
        assertEquals("Все страны", HomeFilters.chipLabel("", "", emptyList()))
    }
}
