package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.engine.JsonAutoPick
import net.antibs.client.engine.SubscriptionFetcher
import net.antibs.client.engine.XrayApi
import net.antibs.client.engine.XrayCore
import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.0.34: the panel's «🎲Автовыбор сервера» (Remnawave «Balanser» Xray JSON) runs as-is. */
class PanelAutoSelectJsonTest {
    private val sub = "rw-test"

    private fun vless(host: String, id: String) =
        """{"protocol":"vless","settings":{"vnext":[{"address":"$host","port":443,"users":[{"id":"$id","encryption":"none","flow":"xtls-rprx-vision"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"$host","fingerprint":"chrome","publicKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","shortId":"ab"}}}"""
    private fun trojan(host: String) =
        """{"protocol":"trojan","settings":{"servers":[{"address":"$host","port":443,"password":"pw"}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"$host"}}}"""
    private fun tagged(tag: String, ob: String) = ob.replaceFirst("{", "{\"tag\":\"$tag\",")
    private val plumbing = """{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}"""
    private fun single(name: String, ob: String) =
        """{"remarks":"$name","inbounds":[{"tag":"socks","port":10808,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true}}],"outbounds":[${tagged("proxy", ob)},$plumbing],"routing":{"rules":[]}}"""

    private val de = vless("de.example.com", "11111111-1111-1111-1111-111111111111")
    private val ruDe = vless("ru.example.com", "22222222-2222-2222-2222-222222222222").replace("\"flow\":\"xtls-rprx-vision\"", "\"flow\":\"\"")
    private val ru = vless("ru2.example.com", "33333333-3333-3333-3333-333333333333")
    private val nlTrojan = trojan("nl.example.com")

    private val autoCfg = """{"remarks":"🇪🇺 🎲Автовыбор сервера",
        "dns":{"servers":["1.1.1.1"],"queryStrategy":"UseIP"},
        "inbounds":[{"tag":"socks","port":10808,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true}},{"tag":"http","port":10809,"listen":"127.0.0.1","protocol":"http"}],
        "outbounds":[${tagged("proxy", de)},${tagged("proxy-2", ruDe)},${tagged("proxy-3", nlTrojan)},$plumbing],
        "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"type":"field","inboundTag":["socks"],"port":"53","outboundTag":"direct"},{"type":"field","ip":["geoip:private"],"outboundTag":"direct"},{"type":"field","network":"tcp,udp","balancerTag":"Super_Balancer"}],
          "balancers":[{"tag":"Super_Balancer","selector":["proxy"],"strategy":{"type":"leastPing"}}]},
        "burstObservatory":{"subjectSelector":["proxy"],"pingConfig":{"destination":"http://www.gstatic.com/generate_204","interval":"1m","timeout":"3s","sampling":1}}}"""

    private val jsonAnswer = "[" + listOf(autoCfg, single("🇩🇪 Германия", de), single("🇷🇺→🇩🇪 Германия", ruDe),
        single("🇷🇺 Россия", ru)).joinToString(",") + "]"

    private val stubLink = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@web.max.ru:1234#%F0%9F%87%AA%F0%9F%87%BA%20%F0%9F%8E%B2%D0%90%D0%B2%D1%82%D0%BE%D0%B2%D1%8B%D0%B1%D0%BE%D1%80%20%D1%81%D0%B5%D1%80%D0%B2%D0%B5%D1%80%D0%B0"
    private val ruLink = "vless://33333333-3333-3333-3333-333333333333@ru2.example.com:443?encryption=none&type=tcp&security=reality&sni=ru2.example.com&fp=chrome&pbk=0qf1yqC8cClcVweSYlke84Ken7BROmrO3TDjJviNfL0&sid=ab#%F0%9F%87%B7%F0%9F%87%BA%20%D0%A0%D0%BE%D1%81%D1%81%D0%B8%D1%8F"
    private val deLink = "vless://11111111-1111-1111-1111-111111111111@de.example.com:443?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality&sni=de.example.com&fp=chrome&pbk=0qf1yqC8cClcVweSYlke84Ken7BROmrO3TDjJviNfL0&sid=ab#DE"
    private val linkServers = listOf(stubLink, deLink, ruLink).map { LinkParser.parse(it, sub)!! }

    private fun attached() = AutoSelect.attachJson(linkServers, AutoSelect.parseConfigs(jsonAnswer))

    @Test fun stubBecomesPanelJsonKeepingId() {
        val out = attached()
        val auto = out[0]
        assertEquals(linkServers[0].id, auto.id)
        assertTrue(AutoSelect.isJsonAuto(auto))
        assertFalse(AutoSelect.isSubscriptionAuto(auto))
        assertEquals("custom", auto.xray!!.protocol)
        assertEquals("Автовыбор", UiLogic.protocolGroup(auto))
        assertEquals(mapOf("proxy" to "🇩🇪 Германия", "proxy-2" to "🇷🇺→🇩🇪 Германия", "proxy-3" to "🇳🇱 Нидерланды Trojan"), auto.xray!!.balancerTagNames)
        assertFalse(auto.xray!!.balancerTagNames.values.any { it.contains("Россия") && !it.contains("→") })
        // other hosts untouched
        assertEquals(linkServers.drop(1), out.drop(1))
    }

    @Test fun noJsonBalancerKeepsStub() {
        val noAuto = "[" + single("🇩🇪 Германия", de) + "]"
        assertEquals(linkServers, AutoSelect.attachJson(linkServers, AutoSelect.parseConfigs(noAuto)))
        assertEquals(linkServers, AutoSelect.attachJson(linkServers, AutoSelect.parseConfigs("c3M6Ly8=")))
    }

    @Test fun panelJsonRunsAsIsWithAppInbounds() {
        val all = attached()
        val opt = XrayConfigBuilder.Options(socksPort = 10901, apiPort = 47123, xrayTun = true, adv = AdvancedSettings(blockAds = false))
        val cfg = XrayConfigBuilder.plan(all[0], all, opt).xray
        File("build/xray-configs-auto").mkdirs()
        File("build/xray-configs-auto/panel_auto.json").writeText(cfg)
        val root = Json.parseToJsonElement(cfg).jsonObject
        val src = Json.parseToJsonElement(autoCfg).jsonObject
        // outbounds: exactly the panel's, nothing added (no b-N, no Россия)
        val tags = root["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("proxy", "proxy-2", "proxy-3", "direct", "block"), tags)
        assertEquals(src["outbounds"]!!.jsonArray.size, root["outbounds"]!!.jsonArray.size)
        // routing, balancer, observatory, dns kept
        assertEquals(src["burstObservatory"], root["burstObservatory"])
        assertEquals(src["dns"], root["dns"])
        val r = root["routing"]!!.jsonObject
        assertEquals(src["routing"]!!.jsonObject["balancers"], r["balancers"])
        assertEquals("IPIfNonMatch", r["domainStrategy"]!!.jsonPrimitive.content)
        // template's local inbounds replaced with the app's; rule on "socks" still matches the app's inbounds
        val inb = root["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(10901, inb.first { it["tag"]!!.jsonPrimitive.content == "socks" }["port"]!!.jsonPrimitive.content.toInt())
        assertTrue(inb.any { it["protocol"]!!.jsonPrimitive.content == "tun" })
        assertFalse(inb.any { it["port"]?.jsonPrimitive?.content == "10809" })
        val dnsRule = r["rules"]!!.jsonArray[0].jsonObject["inboundTag"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(dnsRule.containsAll(listOf("socks", "tun")))
        // API for the balancer pick + traffic stats
        val api = root["api"]!!.jsonObject
        assertEquals("127.0.0.1:47123", api["listen"]!!.jsonPrimitive.content)
        assertTrue(api["services"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("RoutingService", "ObservatoryService")))
        assertTrue(root.containsKey("stats"))
        assertEquals("true", root["policy"]!!.jsonObject["system"]!!.jsonObject["statsOutboundUplink"]!!.jsonPrimitive.content)
        // no api unless asked
        assertNull(Json.parseToJsonElement(XrayConfigBuilder.plan(all[0], all).xray).jsonObject["api"])
    }

    @Test fun directJsonSubscriptionKeepsTagNames() {
        val hosts = SubscriptionParser.parseXrayJsonConfigs(jsonAnswer, sub)
        val auto = hosts.first { it.name.contains("Автовыбор") }
        assertTrue(AutoSelect.isJsonAuto(auto))
        assertEquals("🇷🇺→🇩🇪 Германия", auto.xray!!.balancerTagNames["proxy-2"])
    }

    @Test fun ownBalancerHonoursExclusions() {
        val auto = linkServers[0]
        val ru = linkServers[2]
        assertTrue(AutoSelect.members(auto, linkServers).any { it.id == ru.id })
        val m = XrayConfigBuilder.balancerMembers(auto, linkServers, setOf(ru.id))
        assertEquals(listOf(linkServers[1].id), m.map { it.id })
        val cfg = XrayConfigBuilder.plan(auto, linkServers, XrayConfigBuilder.Options(autoExclude = setOf(ru.id))).xray
        assertFalse(cfg.contains("ru2.example.com"))
    }

    @Test fun balancerInfoProtobuf() {
        val principle = XrayApi.Proto.string(1, "proxy-2")
        val bal = XrayApi.Proto.bytes(6, principle)
        val resp = XrayApi.Proto.bytes(1, bal)
        assertEquals(listOf("proxy-2"), XrayApi.parseBalancerInfo(resp))
        val withOverride = XrayApi.Proto.bytes(1, XrayApi.Proto.bytes(5, XrayApi.Proto.string(2, "proxy-3")) + bal)
        assertEquals(listOf("proxy-3", "proxy-2"), XrayApi.parseBalancerInfo(withOverride))
        val st = XrayApi.Proto.varint(1, 1) + XrayApi.Proto.varint(2, 87) + XrayApi.Proto.string(4, "proxy")
        val obs = XrayApi.Proto.bytes(1, XrayApi.Proto.bytes(1, st))
        assertEquals(listOf(XrayApi.OutboundStatus("proxy", true, 87)), XrayApi.parseOutboundStatus(obs))
        assertTrue(XrayApi.parseBalancerInfo(byteArrayOf(0x0a, 0x7f)).isEmpty())
    }

    @Test fun pickAndLabel() {
        val p = JsonAutoPick(1, "Super_Balancer", mapOf("proxy-2" to "🇷🇺→🇩🇪 Германия"), mapOf("proxy-2" to "vless"))
        assertEquals("proxy-2", p.update(listOf("proxy-2"), emptyMap()))
        assertNull(p.update(listOf("proxy-2"), emptyMap()))
        assertEquals("🇷🇺→🇩🇪 Германия", p.nameOf("proxy-2"))
        assertEquals("proxy-9", p.nameOf("proxy-9"))
        // leastLoad keeps several targets → the one carrying traffic
        assertEquals("b", JsonAutoPick.choose(listOf("a", "b"), mapOf("b" to 10L), null))
        assertEquals("a", JsonAutoPick.choose(listOf("a", "b"), emptyMap(), null))
        assertEquals("b", JsonAutoPick.choose(listOf("a", "b"), emptyMap(), "b"))
        assertEquals("🇷🇺→🇩🇪 Германия · авто", UiLogic.liveHostTitle("🇪🇺 🎲Автовыбор сервера", "🇷🇺→🇩🇪 Германия", true))
        assertEquals("Германия · авто", UiLogic.liveHostTitle("x", "🇩🇪 Германия", true))
        assertEquals("🇩🇪", UiLogic.exitFlag("🇷🇺→🇩🇪 Германия"))
        assertTrue(XrayCore.isProxyTag("proxy-7")); assertFalse(XrayCore.isProxyTag("direct"))
    }

    @Test fun jsonCandidates() {
        val c = SubscriptionFetcher.jsonCandidates("https://sub.example.com/abc?x=1")
        assertEquals("https://sub.example.com/abc?x=1", c[0].first)
        assertEquals(SubscriptionFetcher.JSON_UA, c[0].second)
        assertEquals("https://sub.example.com/abc/json?x=1", c[1].first)
        assertEquals("https://sub.example.com/abc/v2ray-json?x=1", c[2].first)
    }

    @Test fun hiddenTrojanNamedByAddress() {
        val kan = vless("kan.example.com", "44444444-4444-4444-4444-444444444444")
        val ruEntry = vless("ru.example.com", "55555555-5555-5555-5555-555555555555")
        val auto = """{"remarks":"🎲Автовыбор сервера","outbounds":[${tagged("proxy", kan)},${tagged("proxy-16", trojan("kan.example.com"))},""" +
            """${tagged("proxy-17", trojan("ru.example.com"))},${tagged("proxy-18", trojan("x.example.com"))},${tagged("proxy-19", trojan("lt.example.com"))},$plumbing],""" +
            """"routing":{"balancers":[{"tag":"B","selector":["proxy"]}]}}"""
        val answer = "[" + listOf(auto, single("🇨🇦 Канада", kan), single("🇷🇺→🇨🇦 Канада", ruEntry), single("🇷🇺 Россия (VLESS-TCP)", ruEntry)).joinToString(",") + "]"
        val cfgs = AutoSelect.parseConfigs(answer)
        val lt = LinkParser.parse("vless://66666666-6666-6666-6666-666666666666@lt.example.com:443?encryption=none&security=tls&sni=lt.example.com#%F0%9F%87%B1%F0%9F%87%B9%20%D0%9B%D0%B8%D1%82%D0%B2%D0%B0%204G", sub)!!
        val names = AutoSelect.tagNames(cfgs[0], cfgs, listOf(lt))
        assertEquals("🇨🇦 Канада", names["proxy"])
        assertEquals("🇨🇦 Канада Trojan", names["proxy-16"])
        assertEquals("🇷🇺 Россия Trojan", names["proxy-17"])   // cascade on the same entry address is skipped
        assertEquals("Trojan", names["proxy-18"])
        assertEquals("🇱🇹 Литва Trojan", names["proxy-19"])     // secondary source: subscription host
        assertEquals("Канада Trojan · авто", UiLogic.liveHostTitle("auto", names["proxy-16"]!!, true))
        assertEquals("🇨🇦", UiLogic.flag(names["proxy-16"]!!))
        assertEquals("🌐", UiLogic.flag("Trojan"))
        assertEquals("🇩🇪 Германия", AutoSelect.countryFrom(listOf("🇷🇺→🇩🇪 Германия", "🇩🇪 Германия 4G")))
        assertNull(AutoSelect.countryFrom(listOf("🇷🇺→🇩🇪 Германия")))
    }

    @Test fun realSnapshotTrojansMapToCountries() {
        val f = File("/workspace/backup_autoselect_20261009_164844/sub_happ_before.raw")
        if (!f.exists()) return
        val cfgs = AutoSelect.parseConfigs(f.readText())
        val names = AutoSelect.tagNames(AutoSelect.jsonAutoConfigs(cfgs).first(), cfgs)
        File("build/trojan-mapping.txt").writeText(names.entries.joinToString("\n") { "${it.key} = ${it.value}" })
        val trojans = names.filterValues { it.endsWith("Trojan") }
        assertEquals(6, trojans.size)
        trojans.values.forEach { assertTrue(it, LinkParser.flagOf(it) != null && !it.contains(".")) }
    }
}
