package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * 1.0.19: Happ-style routing from subscriptions. The header sample is the real `Routing` header LucX sends
 * on /subbota/ and /jeyson/ (public routing data — «RoscomVPN Whitelist», no keys).
 */
class SubscriptionRoutingTest {
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream(n)!!.bufferedReader().readText()
    private val header get() = res("happ_routing_header.txt").trim()
    private val sub = "sub-r"

    @Test fun realHeaderDecodesEveryHappField() {
        assertTrue(header.startsWith("happ://routing/onadd/"))
        val p = RoutingImport.parse(header, "route-x")!!
        assertEquals("RoscomVPN Whitelist", p.name)
        assertTrue(p.globalProxy)
        assertEquals("IPIfNonMatch", p.domainStrategy)
        assertEquals(listOf("geosite:private", "geosite:whitelist"), p.rules.filter { it.action == "direct" && it.kind == "domain" }.map { it.value })
        assertEquals(listOf("geoip:private", "geoip:whitelist"), p.rules.filter { it.action == "direct" && it.kind == "ip" }.map { it.value })
        assertEquals(3, p.rules.count { it.action == "block" })
        assertEquals("https://8.8.8.8/dns-query", p.remoteDns)
        assertEquals("https://77.88.8.8/dns-query", p.domesticDns)
        assertEquals(2, p.dnsHosts.size)
        assertTrue(p.geoipUrl.endsWith("/geoip.dat")); assertTrue(p.geositeUrl.endsWith("/geosite.dat"))
        assertTrue(p.needsGeoDownload)
    }

    @Test fun headerNameIsCaseInsensitiveAndSurvivesTheParse() {
        val h = SubscriptionParser.parseHeaders(mapOf("Routing" to header, null to "HTTP/1.1 200"))
        val r = SubscriptionParser.parse(res("lucx_subbota_links.txt"), sub, h)!!
        assertEquals(header, r.routing)
    }

    private fun happLine(mode: String, name: String) = "happ://routing/$mode/" +
        Base64.getEncoder().encodeToString("""{"Name":"$name","GlobalProxy":"false","DirectSites":["geosite:ru"],"BlockSites":["domain:ads.example"]}""".toByteArray())

    @Test fun bodyHappLineIsUsedAndNotTreatedAsAHost() {
        val links = LinkParser.b64Decode(res("lucx_subbota_links.txt").trim())!!.trim()
        val plainBody = happLine("add", "Body") + "\n" + links
        for (body in listOf(plainBody, Base64.getEncoder().encodeToString(plainBody.toByteArray()))) {
            val r = SubscriptionParser.parse(body, sub)!!
            assertEquals(16, r.servers.size)
            assertTrue(r.routing.startsWith("happ://routing/add/"))
            assertEquals("Body", RoutingImport.parse(r.routing, "x")!!.name)
        }
        val h = SubscriptionParser.parseHeaders(mapOf("routing" to header))
        assertEquals(header, SubscriptionParser.parse(plainBody, sub, h)!!.routing)
    }

    @Test fun routingMetaLine() {
        val links = LinkParser.b64Decode(res("lucx_subbota_links.txt").trim())!!.trim()
        val r = SubscriptionParser.parse("#routing: " + happLine("onadd", "Meta") + "\n" + links, sub)!!
        assertEquals(16, r.servers.size)
        assertEquals("Meta", RoutingImport.parse(r.routing, "x")!!.name)
        assertEquals(SubscriptionRouting.MODE_ONADD, SubscriptionRouting.mode(r.routing))
    }

    @Test fun xrayJsonRouting() {
        assertEquals("", SubscriptionParser.parse(res("lucx_jeyson.json"), sub)!!.routing)
        val cfg = """[{"remarks":"A","outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"vpn.example.com","port":443,
            "users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},"streamSettings":{"security":"reality",
            "realitySettings":{"serverName":"a.example","publicKey":"AAAA","shortId":""}}},{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
            "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"type":"field","domain":["geosite:category-ads-all"],"outboundTag":"block"},
            {"type":"field","domain":["geosite:category-ru","domain:yandex.ru"],"ip":["geoip:ru"],"outboundTag":"direct"},
            {"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}}]"""
        val r = SubscriptionParser.parse(cfg, sub, SubscriptionParser.SubHeaders(title = "Моя"))!!
        val p = RoutingImport.parse(r.routing, "x")!!
        assertEquals("Моя", p.name)
        assertEquals(listOf("geosite:category-ru", "domain:yandex.ru"), p.rules.filter { it.action == "direct" && it.kind == "domain" }.map { it.value })
        assertEquals(listOf("geoip:ru"), p.rules.filter { it.action == "direct" && it.kind == "ip" }.map { it.value })
        assertEquals(1, p.rules.count { it.action == "block" })
        assertTrue(p.globalProxy)
    }

    @Test fun onaddInstallsOnceWithoutDuplicates() {
        val a1 = SubscriptionRouting.apply(AdvancedSettings(), sub, "LucX", header)
        assertTrue(a1.outcome is SubscriptionRouting.Outcome.Installed)
        assertEquals("route-$sub", a1.adv.activeRoutingId)
        assertEquals("Маршрутизация «RoscomVPN Whitelist» загружена из подписки и установлена", SubscriptionRouting.message(a1.outcome!!))
        val a2 = SubscriptionRouting.apply(a1.adv, sub, "LucX", header)
        assertEquals(1, a2.adv.routingProfiles.size); assertNull(a2.outcome)
        val a3 = SubscriptionRouting.apply(a2.adv.copy(activeRoutingId = null), sub, "LucX", header)
        assertNull(a3.adv.activeRoutingId); assertEquals(1, a3.adv.routingProfiles.size)
        val withGeo = a3.adv.copy(routingProfiles = a3.adv.routingProfiles.map { it.copy(geositeFile = "f.dat", geositeTags = listOf("WHITELIST"), blockEnabled = false) })
        val kept = SubscriptionRouting.apply(withGeo, sub, "LucX", header).adv.routingProfiles.single()
        assertEquals("f.dat", kept.geositeFile); assertFalse(kept.blockEnabled)
    }

    @Test fun addReactivatesAndUpdates() {
        val a1 = SubscriptionRouting.apply(AdvancedSettings(), sub, "S", happLine("add", "One"))
        assertEquals("route-$sub", a1.adv.activeRoutingId)
        val a2 = SubscriptionRouting.apply(a1.adv.copy(activeRoutingId = null), sub, "S", happLine("add", "One"))
        assertEquals("route-$sub", a2.adv.activeRoutingId)
        assertTrue(a2.outcome is SubscriptionRouting.Outcome.Installed)
        val a3 = SubscriptionRouting.apply(a2.adv, sub, "S", happLine("add", "Two"))
        assertEquals("Two", a3.adv.routingProfiles.single().name)
        assertTrue(a3.outcome is SubscriptionRouting.Outcome.Installed)
    }

    @Test fun switchOffAndBrokenRouting() {
        val off = SubscriptionRouting.apply(AdvancedSettings(), sub, "S", header, autoRouting = false)
        assertTrue(off.adv.routingProfiles.isEmpty()); assertNull(off.outcome)
        val bad = SubscriptionRouting.apply(AdvancedSettings(), sub, "S", "happ://routing/add/!!!notbase64")
        assertTrue(bad.outcome is SubscriptionRouting.Outcome.Failed)
        assertTrue(SubscriptionRouting.message(bad.outcome!!).startsWith("Не удалось загрузить маршрутизацию"))
        assertTrue(bad.adv.routingProfiles.isEmpty())
    }

    private fun geoFile(vararg tags: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (t in tags) {
            val tb = t.toByteArray()
            val entry = byteArrayOf(10, tb.size.toByte()) + tb + byteArrayOf(18, 0)
            out.write(10); out.write(entry.size); out.write(entry)
        }
        return out.toByteArray()
    }

    @Test fun geoDatTagsAndRefs() {
        assertEquals(listOf("WHITELIST", "PRIVATE"), GeoDat.tags(geoFile("whitelist", "PRIVATE")))
        val p = RoutingProfile(id = "x", name = "x", geositeFile = "geosite-1.dat", geositeTags = listOf("WHITELIST", "PRIVATE"))
        val bundled = mapOf("site" to setOf("PRIVATE", "CATEGORY-ADS"), "ip" to setOf("PRIVATE", "RU"))
        assertEquals("ext:geosite-1.dat:whitelist", GeoDat.resolve("geosite:whitelist", p, bundled))
        assertEquals("geosite:category-ads", GeoDat.resolve("geosite:category-ads", p, bundled))
        assertNull(GeoDat.resolve("geosite:torrent", p, bundled))
        assertEquals("geosite:torrent", GeoDat.resolve("geosite:torrent", p, null))
        assertEquals("geoip:ru", GeoDat.resolve("geoip:ru", p, bundled))
        assertEquals("domain:a.ru", GeoDat.resolve("domain:a.ru", p, bundled))
    }

    @Test fun xrayConfigWithHappRouting() {
        val base = RoutingImport.parse(header, "route-x")!!
        val ip = File("/tmp/rc-geoip.dat"); val site = File("/tmp/rc-geosite.dat")
        val p = if (ip.exists() && site.exists()) base.copy(
            geoipFile = "rc-geoip.dat", geoipTags = GeoDat.tags(ip.readBytes()),
            geositeFile = "rc-geosite.dat", geositeTags = GeoDat.tags(site.readBytes()),
        ) else base.copy(geoipFile = "rc-geoip.dat", geoipTags = listOf("PRIVATE", "WHITELIST"),
            geositeFile = "rc-geosite.dat", geositeTags = listOf("PRIVATE", "WHITELIST", "WIN-SPY", "TORRENT", "CATEGORY-ADS"))
        val vless = LinkParser.parse("vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?security=reality&sni=a.example&pbk=qVzKBn3Pdpzu-e-yXbgTlh8XLOprxYE446-YsJ77pY8&type=tcp#r", sub)!!
        val opt = XrayConfigBuilder.Options(routing = p, geoBundled = mapOf("site" to setOf("PRIVATE"), "ip" to setOf("PRIVATE")))
        val cfg = XrayConfigBuilder.plan(vless, listOf(vless), opt).xray
        File("build/lucx-configs").mkdirs(); File("build/lucx-configs/routing_happ.json").writeText(cfg)
        val o = Json.parseToJsonElement(cfg).jsonObject
        val rules = o["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val direct = rules.first { it["outboundTag"]?.jsonPrimitive?.content == "direct" && it.containsKey("domain") }
        assertEquals(listOf("ext:rc-geosite.dat:private", "ext:rc-geosite.dat:whitelist"), direct["domain"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(rules.any { r -> r["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "ext:rc-geoip.dat:whitelist" } == true })
        assertEquals("IPIfNonMatch", o["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
        val dns = o["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray
        assertEquals("https://77.88.8.8/dns-query", servers[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals("https://8.8.8.8/dns-query", servers[1].jsonPrimitive.content)
        assertEquals("213.24.64.175", dns["hosts"]!!.jsonObject["lkfl2.nalog.ru"]!!.jsonPrimitive.content)
    }
}
