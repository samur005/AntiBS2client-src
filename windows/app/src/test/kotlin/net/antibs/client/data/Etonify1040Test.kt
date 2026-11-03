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

/** 1.0.40 (restored in 1.0.41): Etonify port — geo files, presets, masking (2.0.15: AdGuard/whitelist/STUN removed with the ABS ad-block port). */
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
