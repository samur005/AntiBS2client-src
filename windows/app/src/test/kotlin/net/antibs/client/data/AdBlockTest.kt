package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.0.39: «Блокировка рекламы» — block rule on top of every config type. */
class AdBlockTest {
    private val vless = """{"protocol":"vless","settings":{"vnext":[{"address":"de.example.com","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"de.example.com"}}}"""
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun rules(c: JsonObject) = c["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun outs(c: JsonObject) = c["outbounds"]!!.jsonArray.map { it.jsonObject }
    private fun strs(o: JsonObject, k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun isAd(r: JsonObject) = r["ruleTag"]?.jsonPrimitive?.content == "antibs-adblock"
    private val off = AdvancedSettings(blockAds = false, blockTrackers = false)

    private fun built(adv: AdvancedSettings = AdvancedSettings(), xrayTun: Boolean = false, routing: RoutingProfile? = null) =
        obj(XrayConfigBuilder.customConfig(vless, XrayConfigBuilder.Options(adv = adv, xrayTun = xrayTun, routing = routing)))

    private val panel = """{"remarks":"auto","dns":{"servers":["1.1.1.1"],"hosts":{"domain:x.test":"1.2.3.4"}},
        "inbounds":[{"tag":"socks-in","port":10808,"listen":"127.0.0.1","protocol":"socks"}],
        "outbounds":[${vless.replaceFirst("{", "{\"tag\":\"proxy\",")},{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
        "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"},{"type":"field","inboundTag":["socks-in"],"network":"tcp,udp","balancerTag":"B"}],
        "balancers":[{"tag":"B","selector":["proxy"]}]}}"""

    @Test fun defaultsOn() {
        val a = AdvancedSettings()
        assertTrue(a.blockAds); assertTrue(a.blockTrackers)
        // older saves without the fields decode to ON
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AdvancedSettings.serializer(), "{\"mode\":\"vpn\"}")
        assertTrue(old.blockAds && old.blockTrackers)
    }

    @Test fun builtConfigBlocksFirst() {
        val c = built()
        val r0 = rules(c).first()
        assertTrue(isAd(r0))
        assertEquals("block", r0["outboundTag"]!!.jsonPrimitive.content)
        assertEquals(listOf("socks"), strs(r0, "inboundTag"))
        val d = strs(r0, "domain")
        assertTrue("geosite:category-ads-all" in d)
        assertTrue("domain:google-analytics.com" in d && "domain:mc.yandex.ru" in d && "domain:appmetrica.yandex.net" in d)
        assertEquals("blackhole", outs(c).first { it["tag"]!!.jsonPrimitive.content == "block" }["protocol"]!!.jsonPrimitive.content)
        val hosts = c["dns"]!!.jsonObject["hosts"]!!.jsonObject
        assertEquals("#3", hosts["domain:mc.yandex.ru"]!!.jsonPrimitive.content)
        assertEquals("#3", hosts["domain:adfox.ru"]!!.jsonPrimitive.content)
        assertNull(hosts["geosite:category-ads-all"]) // memory: geosite only in routing
        assertEquals("8.8.8.8", hosts["dns.google"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals(1, rules(c).count { isAd(it) })
    }

    @Test fun tunAndHttpInboundsCovered() {
        val r0 = rules(built(AdvancedSettings(httpInbound = true), xrayTun = true)).first()
        assertEquals(listOf("socks", "http", "tun"), strs(r0, "inboundTag"))
    }

    @Test fun togglesIndependent() {
        val c = built(off)
        assertTrue(rules(c).none { isAd(it) })
        assertNull(c["dns"]!!.jsonObject["hosts"]!!.jsonObject["domain:mc.yandex.ru"])
        val ads = strs(rules(built(AdvancedSettings(blockTrackers = false))).first(), "domain")
        assertTrue("geosite:category-ads-all" in ads); assertFalse("domain:api.amplitude.com" in ads)
        val tr = strs(rules(built(AdvancedSettings(blockAds = false))).first(), "domain")
        assertFalse("geosite:category-ads-all" in tr); assertTrue("domain:api.amplitude.com" in tr)
    }

    @Test fun beforeRoutingProfileRules() {
        val rp = RoutingProfile(id = "r", name = "r", rules = listOf(RoutingRule("direct", "domain", "yandex.ru")))
        val rs = rules(built(routing = rp))
        assertTrue(isAd(rs[0]))
        val direct = rs.indexOfFirst { "domain:yandex.ru" in strs(it, "domain") }
        assertTrue(direct > 0)
    }

    @Test fun panelJsonMinimalPatch() {
        val c = obj(XrayConfigBuilder.customConfig(panel, XrayConfigBuilder.Options(xrayTun = true)))
        val rs = rules(c)
        assertTrue(isAd(rs[0]))
        assertEquals(listOf("socks", "tun"), strs(rs[0], "inboundTag"))
        assertEquals("block", rs[0]["outboundTag"]!!.jsonPrimitive.content)
        // panel rules untouched, same order, after ours
        assertEquals(listOf("geoip:private"), strs(rs[1], "ip"))
        assertEquals("B", rs[2]["balancerTag"]!!.jsonPrimitive.content)
        // existing blackhole reused, no outbound added
        assertEquals(listOf("proxy", "direct", "block"), outs(c).map { it["tag"]!!.jsonPrimitive.content })
        val hosts = c["dns"]!!.jsonObject["hosts"]!!.jsonObject
        assertEquals("1.2.3.4", hosts["domain:x.test"]!!.jsonPrimitive.content)
        assertEquals("#3", hosts["domain:google-analytics.com"]!!.jsonPrimitive.content)
        assertNull(hosts["geosite:category-ads-all"])
        assertEquals(c["balancers"], null) // balancers stay inside routing
        assertEquals(obj(panel)["routing"]!!.jsonObject["balancers"], c["routing"]!!.jsonObject["balancers"])
    }

    @Test fun panelWithoutBlackholeGetsOne() {
        val p = obj(panel.replace(",{\"tag\":\"block\",\"protocol\":\"blackhole\"}", ""))
        val c = AdBlock.patch(XrayConfigBuilder.patchFullConfig(p, XrayConfigBuilder.Options(adv = off)), AdvancedSettings())
        assertEquals("blackhole", outs(c).last()["protocol"]!!.jsonPrimitive.content)
        assertEquals("block", outs(c).last()["tag"]!!.jsonPrimitive.content)
        val q = obj(panel.replace("{\"tag\":\"block\",\"protocol\":\"blackhole\"}", "{\"tag\":\"block\",\"protocol\":\"freedom\"}"))
        val c2 = obj(XrayConfigBuilder.customConfig(q.toString()))
        assertEquals("adblock", rules(c2)[0]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("blackhole", outs(c2).first { it["tag"]!!.jsonPrimitive.content == "adblock" }["protocol"]!!.jsonPrimitive.content)
    }

    @Test fun patchIsIdempotentAndOffIsNoop() {
        val c1 = obj(XrayConfigBuilder.customConfig(panel))
        val c2 = AdBlock.patch(c1, AdvancedSettings())
        assertEquals(1, rules(c2).count { isAd(it) })
        assertEquals(outs(c1).size, outs(c2).size)
        val raw = XrayConfigBuilder.patchFullConfig(obj(panel), XrayConfigBuilder.Options(adv = off))
        assertTrue(rules(raw).none { isAd(it) })
        assertNull(raw["dns"]!!.jsonObject["hosts"]!!.jsonObject["domain:adfox.ru"])
    }

    @Test fun probesAndBridgesNotFiltered() {
        // own balancer (chain/balancer helpers) — the rule names only user inbounds
        val c = built()
        val r0 = rules(c).first()
        assertTrue(strs(r0, "inboundTag").none { it.startsWith("probe-") || it.startsWith("sb-bridge") || it == "dns-in" })
    }

    @Test fun realSnapshotAllConfigs() {
        val f = File("/workspace/backup_autoselect_20261009_164844/sub_happ_before.raw")
        if (!f.exists()) return
        val arr = Json.parseToJsonElement(f.readText().trim()).jsonArray
        arr.forEach { e ->
            val c = obj(XrayConfigBuilder.customConfig(e.toString(), XrayConfigBuilder.Options(xrayTun = true)))
            val rs = rules(c)
            assertTrue(isAd(rs[0])); assertEquals(1, rs.count { isAd(it) })
            assertEquals(outs(e.jsonObject).size, outs(c).size)
            assertEquals(e.jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray.size + 1, rs.size)
            System.getenv("ADBLOCK_DUMP")?.let { d -> File(d).mkdirs(); File(d, "cfg-${arr.indexOf(e)}.json").writeText(XrayConfigBuilder.toJsonString(c, true)) }
        }
    }
}
