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

/** 2.0.15: ABS ad blocking port — block-ads outbound, categories, telemetry, ad DNS, counter. */
class AdBlockTest {
    private val vless = """{"protocol":"vless","settings":{"vnext":[{"address":"de.example.com","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"de.example.com"}}}"""
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun rules(c: JsonObject) = c["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun outs(c: JsonObject) = c["outbounds"]!!.jsonArray.map { it.jsonObject }
    private fun strs(o: JsonObject, k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun tag(r: JsonObject) = r["ruleTag"]?.jsonPrimitive?.content
    private fun out(r: JsonObject) = r["outboundTag"]?.jsonPrimitive?.content
    private val off = AdvancedSettings(blockAds = false)
    private fun built(adv: AdvancedSettings = AdvancedSettings()) = obj(XrayConfigBuilder.customConfig(vless, XrayConfigBuilder.Options(adv = adv)))

    private val panel = """{"inbounds":[{"tag":"socks","port":10808,"listen":"127.0.0.1","protocol":"socks"}],
        "outbounds":[${vless.replaceFirst("{", "{\"tag\":\"proxy\",")},{"tag":"direct","protocol":"freedom"}],
        "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"},{"type":"field","inboundTag":["socks"],"network":"tcp,udp","outboundTag":"proxy"}]}}"""

    @Test fun defaultsAndOldSaves() {
        assertTrue(AdvancedSettings().blockAds)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AdvancedSettings.serializer(),
            """{"blockTrackers":true,"adGuard":true,"blockStun":true,"allowCustom":["x.test"]}""")
        assertTrue(old.blockAds); assertTrue(old.adCats.isEmpty()); assertFalse(old.adDnsOn)
    }

    @Test fun adsRuleOnTopToBlockAds() {
        val c = built()
        val rs = rules(c)
        val ad = rs.first { tag(it) == "antibs-adblock" }
        assertEquals(AdBlock.TAG, out(ad))
        assertTrue(AdBlock.ADS_GEOSITE in strs(ad, "domain"))
        assertTrue("domain:doubleclick.net" in strs(ad, "domain") || strs(ad, "domain").any { it.contains("doubleclick") })
        assertTrue(rs.indexOf(ad) < rs.indexOfFirst { it["ip"]?.toString()?.contains("geoip:private") == true }.let { if (it < 0) Int.MAX_VALUE else it })
        assertTrue(outs(c).any { it["tag"]!!.jsonPrimitive.content == AdBlock.TAG && it["protocol"]!!.jsonPrimitive.content == "blackhole" })
    }

    @Test fun offMeansNothing() {
        val c = built(off)
        assertTrue(rules(c).none { tag(it) in setOf("antibs-adblock", "antibs-adcats") })
        assertTrue(outs(c).none { it["tag"]?.jsonPrimitive?.content == AdBlock.TAG })
        assertFalse(AdBlock.enabled(off))
        assertFalse(AdBlockCounter.configCounts(c))
    }

    @Test fun categoriesAndTelemetry() {
        val a = off.copy(adCats = listOf("adult", "tm_windows", "bogus"))
        assertTrue(AdBlock.enabled(a))
        val r = rules(built(a)).first { tag(it) == "antibs-adcats" }
        assertEquals(AdBlock.TAG, out(r))
        val d = strs(r, "domain")
        assertEquals(2, d.size)
        assertTrue(rules(built(a)).none { tag(it) == "antibs-adblock" })
        assertFalse(AdBlock.enabled(off.copy(adCats = listOf("bogus"))))
    }

    @Test fun missingBundledTagSkipped() {
        val bundled = mapOf("site" to setOf("CATEGORY-ADS-ALL"))
        val d = AdBlock.catDomains(off.copy(adCats = AdBlock.CAT_IDS.toList()), bundled)
        assertTrue(d.isEmpty())
        assertTrue(AdBlock.adDomains(bundled).contains(AdBlock.ADS_GEOSITE))
        assertFalse(AdBlock.adDomains(mapOf("site" to emptySet())).contains(AdBlock.ADS_GEOSITE))
    }

    @Test fun patchPanelKeepsRoutingAndLan() {
        val c = AdBlock.patch(obj(panel), AdvancedSettings())
        val rs = rules(c)
        assertEquals("antibs-adblock", tag(rs.first { tag(it) != null && tag(it) != "antibs-quic" }))
        assertTrue(rs.any { strs(it, "ip").contains("geoip:private") && out(it) == "direct" })
        assertTrue(rs.any { out(it) == "proxy" && strs(it, "inboundTag") == listOf("socks") })
        assertEquals(listOf("socks"), strs(rs.first { tag(it) == "antibs-adblock" }, "inboundTag"))
        val inb = c["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals("true", inb["sniffing"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        // idempotent
        val c2 = AdBlock.patch(c, AdvancedSettings())
        assertEquals(rules(c).size, rules(c2).size)
        assertEquals(outs(c).size, outs(c2).size)
    }

    @Test fun dnsBlocker() {
        assertNull(AdBlock.dnsUrl(off))
        val u = AdBlock.DNS_PRESETS.first().variants.first().url
        val a = off.copy(adDnsOn = true, adDnsUrl = u)
        assertTrue(AdBlock.dnsUrl(a) != null)
        assertEquals(AdBlock.DNS_PRESETS.first(), AdBlock.findDns(u)?.first)
        assertTrue(AdBlock.dnsUrlOk("https://dns.example/dns-query"))
        assertTrue(AdBlock.dnsUrlOk("quic://dns.example"))
        assertFalse(AdBlock.dnsUrlOk("ftp://x"))
        assertNull(AdBlock.dnsUrl(off.copy(adDnsOn = true, adDnsUrl = "ftp://x")))
        assertTrue(built(a)["dns"].toString().contains(AdBlock.dnsUrl(a)!!))
    }

    @Test fun counterParse() {
        val log = """
            2026/11/03 10:00:00 from 10.0.0.2:5000 accepted tcp:ads.example:443 [socks -> block-ads]
            2026/11/03 10:00:01 from 10.0.0.2:5001 accepted tcp:ok.example:443 [socks -> proxy]
            2026/11/03 10:00:02 from 10.0.0.2:5002 accepted udp:t.example:443 [tun >> block-ads]
            2026/11/03 10:00:03 from 10.0.0.2:5003 accepted tcp:x:443 [socks -> block]
        """.trimIndent()
        assertEquals(2, AdBlockCounter.count(log))
    }

    @Test fun accessLogInjection() {
        val c = built()
        assertTrue(AdBlockCounter.configCounts(c))
        val (c2, p) = AdBlockCounter.withAccessLog(c, "/x/a.log")
        assertEquals("/x/a.log", p)
        assertEquals("/x/a.log", c2["log"]!!.jsonObject["access"]!!.jsonPrimitive.content)
        // 2.0.30: always dedicated counter log (never keep Логи's xray-access.log)
        val keep = JsonObject(c + ("log" to obj("""{"loglevel":"none","access":"/y/xray-access.log"}""")))
        val (c3, p3) = AdBlockCounter.withAccessLog(keep, "/x/a.log")
        assertEquals("/x/a.log", p3)
        assertEquals("/x/a.log", c3["log"]!!.jsonObject["access"]!!.jsonPrimitive.content)
        assertEquals("warning", c3["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive.content) // none → warning
        assertNull(AdBlockCounter.withAccessLog(built(off), "/x/a.log").second)
    }

    @Test fun counterMatchesXray26Lines() {
        val log = """
            2026/11/09 21:21:09.889308 from tcp:127.0.0.1:53694 accepted tcp:pagead2.googlesyndication.com:443 [socks -> block-ads]
            2026/11/09 21:21:10.000000 from tcp:127.0.0.1:53695 accepted tcp:ads.example:443 [tun-in -> block-ads]
            2026/11/09 21:21:11.000000 from tcp:127.0.0.1:53696 accepted tcp:x:443 [socks -> proxy]
            2026/11/09 21:21:12.000000 from tcp:127.0.0.1:53697 accepted tcp:y:443 [socks >> block-ads]
        """.trimIndent()
        assertEquals(3, AdBlockCounter.count(log))
    }

    @Test fun countsDayRollAndStore() {
        val c = AdBlockCounter.Counts().add(3, "2026-11-02").add(2, "2026-11-03")
        assertEquals(2, c.todayFor("2026-11-03")); assertEquals(5, c.total); assertEquals(0, c.todayFor("2026-11-04"))
        assertEquals("н/д", AdBlockCounter.show(false, 0)); assertEquals("7", AdBlockCounter.show(true, 7))
        val d = kotlin.io.path.createTempDirectory().toFile()
        val f = File(d, AdBlockCounter.COUNTS_NAME)
        assertFalse(AdBlockCounter.load(f).measured)
        AdBlockCounter.markMeasured(f); assertTrue(AdBlockCounter.load(f).measured)
        AdBlockCounter.add(f, 4, "2026-11-03"); assertEquals(4, AdBlockCounter.load(f).total)
        AdBlockCounter.reset(f); assertEquals(0, AdBlockCounter.load(f).total)
        d.deleteRecursively()
    }

    @Test fun drainIncremental() {
        val d = kotlin.io.path.createTempDirectory().toFile()
        val f = File(d, "a.log")
        f.writeText("x [socks -> block-ads]\npartial [socks -> block-a")
        val (o1, n1) = AdBlockCounter.drain(f, 0)
        assertEquals(1, n1)
        f.appendText("ds]\n")
        val (o2, n2) = AdBlockCounter.drain(f, o1)
        assertEquals(1, n2); assertEquals(f.length(), o2)
        assertEquals(0, AdBlockCounter.drain(f, o2).second)
        d.deleteRecursively()
    }
}
