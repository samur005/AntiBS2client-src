package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** 1.0.40 (restored in 1.0.41): Etonify port — geo files, AdGuard list, whitelist, STUN, presets, masking. */
class Etonify1040Test {
    private val vless = """{"protocol":"vless","settings":{"vnext":[{"address":"de.example.com","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"de.example.com"}}}"""
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun rules(c: JsonObject) = c["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun strs(o: JsonObject, k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun tag(r: JsonObject) = r["ruleTag"]?.jsonPrimitive?.content
    private fun built(adv: AdvancedSettings) = obj(XrayConfigBuilder.customConfig(vless, XrayConfigBuilder.Options(adv = adv)))

    @Test fun geoPackRoundTrip() {
        val e1 = GeoPack.siteEntry("ADS", listOf(GeoPack.DOMAIN to "ads.test", GeoPack.FULL to "x.y.test"))
        val e2 = GeoPack.siteEntry("OTHER", listOf(GeoPack.PLAIN to "kw"))
        val file = GeoPack.list(listOf(e1, e2))
        assertEquals(listOf("ADS", "OTHER"), GeoPack.tags(ByteArrayInputStream(file)))
        val got = GeoPack.extract(ByteArrayInputStream(file), setOf("ads"))
        assertEquals(setOf("ADS"), got.keys)
        assertEquals(listOf(GeoPack.DOMAIN to "ads.test", GeoPack.FULL to "x.y.test"), GeoPack.domains(got["ADS"]!!))
        assertEquals("OTHER", GeoPack.tagOf(e2))
    }

    @Test fun adGuardParse() {
        val p = AdGuardFilter.parse(
            """
            ! comment
            ||ads.example.com^
            ||track.example.org^${'$'}third-party
            0.0.0.0 hosts.example.net
            @@||good.example.com^
            ||good.example.com^
            example.com##.banner
            /regex.*/
            ||cdn.example.io:8080^
            """.trimIndent()
        )
        assertTrue("ads.example.com" in p.block && "track.example.org" in p.block && "hosts.example.net" in p.block)
        assertTrue("cdn.example.io" in p.block)
        assertFalse("good.example.com" in p.block)
        assertTrue("good.example.com" in p.allow)
        assertFalse(p.block.any { it.contains('/') || it.contains('#') })
    }

    @Test fun userDomainsParse() {
        val l = UserDomains.split("ads.test, full:exact.test;  *.wild.test\nhttps://u.test/path")
        val p = UserDomains.parse(l).map { UserDomains.toXray(it) }
        assertTrue("domain:ads.test" in p)
        assertTrue("full:exact.test" in p)
        assertTrue("domain:wild.test" in p)
    }

    @Test fun compilerWhitelist() {
        val r = AdBlockCompiler.compile(
            listOf(listOf(GeoPack.DOMAIN to "ads.test", GeoPack.DOMAIN to "a.good.test", GeoPack.DOMAIN to "tracker.test")),
            listOf(GeoPack.DOMAIN to "good.test", GeoPack.DOMAIN to "ok.tracker.test"),
        )
        assertEquals(2, r.count); assertEquals(1, r.removed)
        assertEquals(listOf("domain:ok.tracker.test"), r.allowOverrides)
        val e = GeoPack.extract(ByteArrayInputStream(r.bytes), setOf(AdBlockCompiler.TAG)).values.single()
        assertEquals(setOf("ads.test", "tracker.test"), GeoPack.domains(e).map { it.second }.toSet())
    }

    @Test fun whitelistInDefaultLists() {
        val p = AdBlock.defaultPrepared(AdvancedSettings(allowCustom = listOf("mc.yandex.ru")))
        assertFalse("domain:mc.yandex.ru" in p.block)
        assertTrue("domain:mc.yandex.ru" in p.allow)
    }

    @Test fun stunRuleAndAllowOverride() {
        val c = built(AdvancedSettings(blockStun = true, allowCustom = listOf("mc.yandex.ru")))
        val rs = rules(c)
        val stun = rs.first { tag(it) == "antibs-stun" }
        assertEquals("block", stun["outboundTag"]!!.jsonPrimitive.content)
        val iAllow = rs.indexOfFirst { tag(it) == "antibs-allow" }
        val iAd = rs.indexOfFirst { tag(it) == "antibs-adblock" }
        assertTrue(iAllow in 0 until iAd)
    }

    @Test fun presetRuBuilt() {
        val c = built(AdvancedSettings(trafficPreset = TrafficPresets.RU))
        val rs = rules(c)
        val dnsRule = rs.firstOrNull { strs(it, "ip").contains("77.88.8.8") }
        assertTrue(dnsRule != null && dnsRule["inboundTag"] == null)
        val withInb = rs.filter { tag(it) == "antibs-preset" && it["inboundTag"] != null }
        val dom = withInb.first { it["domain"] != null }
        assertTrue(strs(dom, "domain").any { it == "domain:ru" || it.endsWith(":ru") || it == "geosite:category-ru" })
        assertTrue(c["dns"].toString().contains("77.88.8.8"))
    }

    @Test fun presetAiSocialRestDirect() {
        for (p in listOf(TrafficPresets.AI, TrafficPresets.SOCIAL)) {
            val rs = rules(built(AdvancedSettings(trafficPreset = p))).filter { tag(it) == "antibs-preset" }
            assertTrue(rs.isNotEmpty())
            val last = rs.last()
            assertNull(last["domain"]); assertNull(last["ip"])
            assertTrue(last["outboundTag"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test fun presetOffNoRules() {
        val rs = rules(built(AdvancedSettings(trafficPreset = TrafficPresets.OFF)))
        assertTrue(rs.none { tag(it) == "antibs-preset" })
        assertFalse(TrafficPresets.active(AdvancedSettings()))
    }

    @Test fun notificationAndMask() {
        val hosts = setOf("de.example.com", "203.0.113.7")
        val l = UiLogic.maskLine("dial tcp de.example.com:443 via 203.0.113.7 ok 127.0.0.1", hosts)
        assertFalse(l.contains("de.example.com")); assertFalse(l.contains("203.0.113.7"))
        assertTrue(l.contains("127.0.0.1"))
    }
}
