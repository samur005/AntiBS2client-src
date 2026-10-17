package net.antibs.client.data

import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.0.37: auto-select labels are computed at runtime and never show a domain. */
class AutoLabelRuntimeTest {
    private val sub = "rw"
    private val domainRe = Regex("""[A-Za-z0-9-]+\.[A-Za-z]{2,}""")

    private fun trojan(tag: String, host: String, sni: String = host) =
        """{"tag":"$tag","protocol":"trojan","settings":{"servers":[{"address":"$host","port":10450,"password":"pw"}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"$sni"}}}"""
    private fun vless(tag: String, host: String) =
        """{"tag":"$tag","protocol":"vless","settings":{"vnext":[{"address":"$host","port":8443,"users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"$host"}}}"""

    private fun autoEntry(outs: List<String>, stored: Map<String, String>) = ServerEntry(
        id = "auto", engine = EngineKind.XRAY, name = "🇪🇺 🎲Автовыбор сервера", subscriptionId = sub,
        xray = XrayProfile(remarks = "auto", protocol = "custom", server = "", port = 0, balancerTagNames = stored,
            customJson = """{"outbounds":[${outs.joinToString(",")},{"tag":"direct","protocol":"freedom"}],"routing":{"balancers":[{"tag":"B","selector":["proxy"]}]}}"""),
    )

    private fun host(name: String, server: String, sni: String? = null) = ServerEntry(
        id = "h-$name-$server", engine = EngineKind.XRAY, name = name, subscriptionId = sub,
        xray = XrayProfile(remarks = name, protocol = "vless", server = server, port = 8443, sni = sni),
    )

    @Test fun staleStoredDomainLabelIsRecomputed() {
        val auto = autoEntry(listOf(vless("proxy", "fin.example.com"), trojan("proxy-18", "fin.example.com")),
            mapOf("proxy" to "🇫🇮 Финляндия", "proxy-18" to "Trojan · fin.example.com"))
        val hosts = listOf(auto, host("🇫🇮 Финляндия", "fin.example.com"), host("🇷🇺→🇫🇮 Финляндия", "ru.example.com"))
        val n = AutoSelect.liveNames(auto, hosts)
        assertEquals("🇫🇮 Финляндия", n["proxy"])
        assertEquals("🇫🇮 Финляндия Trojan", n["proxy-18"])
        assertEquals("Финляндия Trojan · авто", UiLogic.liveHostTitle(auto.name, n["proxy-18"]!!, true))
        assertEquals("🇫🇮", UiLogic.flag(n["proxy-18"]!!))
    }

    @Test fun storedNameOfVanishedHostIsNotTrusted() {
        val auto = autoEntry(listOf(trojan("proxy-16", "kan.example.com")), mapOf("proxy-16" to "🇨🇦 Старое имя"))
        val n = AutoSelect.liveNames(auto, listOf(auto, host("Канада", "kan.example.com")))
        assertEquals("🇨🇦 Канада Trojan", n["proxy-16"])   // host name without flag emoji → flag from the country
    }

    @Test fun sniAndPrefixFallbacks() {
        val auto = autoEntry(listOf(trojan("proxy-2", "203.0.113.7", sni = "g.example.com"), trojan("proxy-3", "nider.example.net"),
            trojan("proxy-4", "zz.example.net")), emptyMap())
        val n = AutoSelect.liveNames(auto, listOf(auto, host("🇩🇪 Германия", "g.example.com")))
        assertEquals("🇩🇪 Германия Trojan", n["proxy-2"])         // SNI matches the host's address
        assertEquals("🇳🇱 Нидерланды Trojan", n["proxy-3"])       // domain prefix heuristic
        assertEquals("Trojan", n["proxy-4"])                      // nothing → protocol only (🌐 in the header)
        assertEquals("🌐", UiLogic.flag(n["proxy-4"]!!))
        n.values.forEach { assertFalse(it, domainRe.containsMatchIn(it)) }
    }

    @Test fun sanitizerNeverLeavesDomains() {
        assertEquals("Trojan", AutoSelect.cleanLabel("Trojan · fin.example.com", "x"))
        assertEquals("🇩🇪 Германия", AutoSelect.cleanLabel("🇩🇪 Германия 1.2.3.4:443", "x"))
        assertEquals("x", AutoSelect.cleanLabel("kan.example.com", "x"))
        assertEquals("🇷🇺→🇩🇪 Германия", AutoSelect.cleanLabel("🇷🇺→🇩🇪 Германия", "x"))
        assertEquals("Сервер · авто", UiLogic.liveHostTitle("a", "usa.example.com", true))
        assertEquals("Trojan · авто", UiLogic.liveHostTitle("a", "Trojan · fin.example.com", true))
    }

    @Test fun realSnapshotTable() {
        val raw = File("/workspace/backup_autoselect_20261009_164844/sub_happ_before.raw")
        val links = File("/workspace/remnawave_sub.txt")
        if (!raw.exists() || !links.exists()) return
        val linkText = String(java.util.Base64.getMimeDecoder().decode(links.readText().trim()))
        val hosts = linkText.lines().mapNotNull { LinkParser.parse(it, sub) }
        val cfgs = AutoSelect.parseConfigs(raw.readText())
        val attached = AutoSelect.attachJson(hosts, cfgs)
        val auto = attached.first { AutoSelect.isJsonAuto(it) }
        // simulate a host imported under 1.0.34: hidden outbounds stored as «Trojan · <domain>»
        val stale = auto.copy(xray = auto.xray!!.copy(balancerTagNames = auto.xray!!.balancerTagNames.mapValues { (t, v) ->
            if (v.endsWith("Trojan")) "Trojan · x.example.com" else v }))
        val live = AutoSelect.liveNames(stale, attached.map { if (it.id == auto.id) stale else it })
        val imported = auto.xray!!.balancerTagNames
        File("build/auto-label-table.txt").writeText(live.entries.joinToString("\n") { "${it.key}\t${it.value}\t${imported[it.key]}" })
        assertEquals(18, live.size)
        assertEquals(imported, live)
        live.values.forEach { assertFalse(it, domainRe.containsMatchIn(it)); assertTrue(it, LinkParser.flagOf(it) != null) }
        assertEquals("🇫🇮 Финляндия Trojan", live["proxy-18"])
    }
}
