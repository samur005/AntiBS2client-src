package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.engine.AppState
import net.antibs.client.engine.UiSettings
import net.antibs.client.engine.advancedFromJson
import net.antibs.client.engine.toJsonString
import net.antibs.client.engine.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class SettingsRoutingTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun cfg(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun outbounds(c: JsonObject) = c["outbounds"]!!.jsonArray.map { it.jsonObject }
    private fun inbounds(c: JsonObject) = c["inbounds"]!!.jsonArray.map { it.jsonObject }
    private fun rules(c: JsonObject) = c["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun tag(o: JsonObject) = o["tag"]?.jsonPrimitive?.content

    private val vlessTls = XrayProfile("V", "vless", "v.example", 443, id = "11111111-1111-1111-1111-111111111111", network = "ws",
        security = "tls", sni = "v.example", path = "/ws")
    private val vlessReality = XrayProfile("R", "vless", "r.example", 443, id = "11111111-1111-1111-1111-111111111111", network = "tcp",
        security = "reality", sni = "www.microsoft.com", publicKey = "pbk", shortId = "ab", flow = "xtls-rprx-vision")

    // ───────── settings persistence ─────────

    @Test fun advancedSettingsRoundTrip() {
        val a = AdvancedSettings(
            fragPackets = "1-3", noise = true, noiseType = "hex", muxConcurrency = 16, socksPort = 20808, httpInbound = true,
            tunMtu = 1400, tunnelBindBlock = true, localDns = true, mode = "proxy", excludedRoutes = listOf("10.0.0.0/8"),
            pingType = "head", pingMode = "keepalive", pingTimeoutSec = 9, perAppMode = "bypass", perAppBypass = listOf("com.a"),
            routingProfiles = listOf(RoutingProfile("r1", "RU", rules = listOf(RoutingRule("direct", "domain", "geosite:ru")))),
            activeRoutingId = "r1", hideEmptyManual = false, logLevel = "debug",
        )
        assertEquals(a, advancedFromJson(a.toJsonString()))
        assertEquals(AdvancedSettings(), advancedFromJson(null))
        assertEquals(AdvancedSettings(), advancedFromJson("{garbage"))
        // unknown future keys are ignored
        assertEquals(9, advancedFromJson("""{"pingTimeoutSec":9,"futureKey":1}""").pingTimeoutSec)
    }

    @Test fun appStateWithoutAdvDecodesDefaults() {
        // a 1.0.5 blob has no "adv" key
        val old = """{"theme":"dark","mux":true,"rev":4}"""
        val st = json.decodeFromString(AppState.serializer(), old)
        assertEquals(AdvancedSettings(), st.adv)
        assertTrue(st.mux)
        val ui: UiSettings = st.copy(adv = st.adv.copy(noise = true)).toUi()
        assertTrue(ui.adv.noise)
        val back = json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), st.copy(adv = AdvancedSettings(tunMtu = 1280))))
        assertEquals(1280, back.adv.tunMtu)
    }

    @Test fun parseRangeValues() {
        assertEquals(50 to 100, parseRange("50-100"))
        assertEquals(7 to 7, parseRange(" 7 "))
        assertNull(parseRange("100-50"))
        assertNull(parseRange("x"))
        assertNull(parseRange(""))
    }

    // ───────── per-app ─────────

    @Test fun perAppModes() {
        val own = "fun.antibs.client"
        val off = PerApp.plan("off", listOf("a"), listOf("b"), own)
        assertEquals(listOf(own), off.disallowed); assertTrue(off.allowed.isEmpty())

        val inc = PerApp.plan("include", listOf("com.tg", own, "com.tg", ""), emptyList(), own)
        assertEquals(listOf("com.tg"), inc.allowed); assertTrue(inc.disallowed.isEmpty())

        val incEmpty = PerApp.plan("include", emptyList(), emptyList(), own)
        assertTrue(incEmpty.allowed.isEmpty()); assertEquals(listOf(own), incEmpty.disallowed)

        val byp = PerApp.plan("bypass", listOf("x"), listOf("com.bank", "com.bank"), own)
        assertEquals(listOf("com.bank", own), byp.disallowed); assertTrue(byp.allowed.isEmpty())

        assertEquals(listOf("i"), AdvancedSettings(perAppMode = "include", perAppInclude = listOf("i"), perAppBypass = listOf("b")).perAppSelected)
        assertEquals(listOf("b"), AdvancedSettings(perAppMode = "bypass", perAppInclude = listOf("i"), perAppBypass = listOf("b")).perAppSelected)
    }

    // ───────── routing import ─────────

    private val happJson = """{"Name":"RU direct","GlobalProxy":"true","DomainStrategy":"IPIfNonMatch",
        "DirectSites":["geosite:ru","domain:yandex.ru"],"DirectIp":["geoip:ru","10.0.0.0/8"],
        "ProxySites":["geosite:youtube"],"ProxyIp":[],"BlockSites":["geosite:category-ads-all"],"BlockIp":["1.2.3.4"],
        "RemoteDns":"1.1.1.1","DomesticDns":"77.88.8.8"}"""

    @Test fun importHappJson() {
        val p = RoutingImport.parse(happJson, "id1")!!
        assertEquals("RU direct", p.name)
        assertTrue(p.globalProxy)
        assertEquals(7, p.rules.size)
        assertTrue(p.rules.contains(RoutingRule("direct", "domain", "geosite:ru")))
        assertTrue(p.rules.contains(RoutingRule("direct", "ip", "10.0.0.0/8")))
        assertTrue(p.rules.contains(RoutingRule("block", "ip", "1.2.3.4")))
        assertTrue(p.rules.contains(RoutingRule("proxy", "domain", "geosite:youtube")))
        assertEquals(RoutingProfile.MY_GROUP, p.group)
    }

    @Test fun importHappDeepLinkAndBase64() {
        val b64 = Base64.getEncoder().encodeToString(happJson.toByteArray())
        val viaLink = RoutingImport.parse("happ://routing/add/$b64", "id2")!!
        assertEquals("RU direct", viaLink.name)
        val viaOnAdd = RoutingImport.parse("happ://routing/onadd/$b64", "id3", group = "AntiBS", subscriptionId = "sub-1")!!
        assertEquals("AntiBS", viaOnAdd.group)
        assertEquals("sub-1", viaOnAdd.subscriptionId)
        val raw = RoutingImport.parse(b64, "id4")!!
        assertEquals(7, raw.rules.size)
        val globalFalse = RoutingImport.parse("""{"Name":"x","GlobalProxy":"false","ProxySites":"geosite:google, domain:x.com"}""", "id5")!!
        assertFalse(globalFalse.globalProxy)
        assertEquals(2, globalFalse.rules.size)
    }

    @Test fun importRejectsGarbage() {
        assertNull(RoutingImport.parse("", "x"))
        assertNull(RoutingImport.parse("hello world", "x"))
        assertNull(RoutingImport.parse("vless://abc@host:443", "x"))
        assertNull(RoutingImport.parse("{}", "x"))
    }

    @Test fun nativeExportRoundTripAndHappExport() {
        val p = RoutingProfile("r", "Mine", rules = listOf(RoutingRule("block", "port", "25"), RoutingRule("proxy", "process", "com.tg")),
            proxyServerIds = listOf("s1"), directEnabled = false)
        val back = RoutingImport.parse(RoutingImport.export(p), "new")!!
        assertEquals(p.copy(id = "new"), back)
        val happ = RoutingImport.parse(RoutingImport.exportHapp(RoutingImport.parse(happJson, "h")!!), "h2")!!
        assertEquals(7, happ.rules.size)
    }

    @Test fun kindGuess() {
        assertEquals("ip", RoutingImport.kindOf("1.2.3.0/24"))
        assertEquals("ip", RoutingImport.kindOf("geoip:ru"))
        assertEquals("ip", RoutingImport.kindOf("2001:db8::/32"))
        assertEquals("domain", RoutingImport.kindOf("geosite:ru"))
        assertEquals("domain", RoutingImport.kindOf("example.com"))
        assertEquals("process", RoutingImport.kindOf("app:com.tg"))
    }

    // ───────── routing → Xray ─────────

    @Test fun routingRulesInXrayConfig() {
        val prof = RoutingProfile("r", "T", globalProxy = false, rules = listOf(
            RoutingRule("block", "domain", "geosite:category-ads-all"),
            RoutingRule("direct", "domain", "yandex.ru"),
            RoutingRule("direct", "domain", ".vk.com"),
            RoutingRule("direct", "ip", "geoip:ru"),
            RoutingRule("proxy", "port", "443"),
            RoutingRule("proxy", "process", "com.tg"),
        ))
        val e = ServerEntry("a", EngineKind.XRAY, "A", xray = vlessTls)
        val c = cfg(XrayConfigBuilder.plan(e, listOf(e), XrayConfigBuilder.Options(routing = prof)).xray)
        val rs = rules(c)
        val block = rs.first { it["outboundTag"]?.jsonPrimitive?.content == "block" }
        assertEquals("geosite:category-ads-all", block["domain"]!!.jsonArray[0].jsonPrimitive.content)
        val directDomains = rs.first { it["outboundTag"]?.jsonPrimitive?.content == "direct" && it.containsKey("domain") }["domain"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("domain:yandex.ru", "domain:vk.com"), directDomains)
        assertTrue(rs.any { it["port"]?.jsonPrimitive?.content == "443" && it["outboundTag"]?.jsonPrimitive?.content == "proxy" })
        // process rules need Xray TUN → skipped here
        assertTrue(rs.none { it.containsKey("process") })
        // GlobalProxy=false → final catch-all direct
        val last = rs.last()
        assertEquals("tcp,udp", last["network"]!!.jsonPrimitive.content)
        assertEquals("direct", last["outboundTag"]!!.jsonPrimitive.content)
        // order: block before proxy before direct
        val idxBlock = rs.indexOf(block)
        val idxProxy = rs.indexOfFirst { it["outboundTag"]?.jsonPrimitive?.content == "proxy" }
        val idxDirect = rs.indexOfFirst { it["outboundTag"]?.jsonPrimitive?.content == "direct" && it.containsKey("domain") }
        assertTrue(idxBlock < idxProxy && idxProxy < idxDirect)
        assertEquals("IPIfNonMatch", c["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
    }

    @Test fun processRulesWithXrayTun() {
        val prof = RoutingProfile("r", "T", rules = listOf(RoutingRule("direct", "process", "com.bank"), RoutingRule("direct", "process", "app:10999")))
        val e = ServerEntry("a", EngineKind.XRAY, "A", xray = vlessTls)
        val c = cfg(XrayConfigBuilder.plan(e, listOf(e), XrayConfigBuilder.Options(routing = prof, xrayTun = true, packageUids = mapOf("com.bank" to 10123))).xray)
        val pr = rules(c).first { it.containsKey("process") }
        assertEquals(listOf("10123", "10999"), pr["process"]!!.jsonArray.map { it.jsonPrimitive.content })
        val tun = inbounds(c).first { tag(it) == "tun" }
        assertEquals("tun", tun["protocol"]!!.jsonPrimitive.content)
        assertEquals(1500, tun["settings"]!!.jsonObject["mtu"]!!.jsonPrimitive.int)
        // DNS hijack also for the tun inbound
        val dnsRule = rules(c).first { it["outboundTag"]?.jsonPrimitive?.content == "dns-out" }
        assertEquals(listOf("socks", "tun"), dnsRule["inboundTag"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun routingProxyServersGetOwnOutbound() {
        val a = ServerEntry("a", EngineKind.XRAY, "A", xray = vlessTls)
        val b = ServerEntry("b", EngineKind.XRAY, "B", xray = vlessReality)
        val prof = RoutingProfile("r", "T", proxyServerIds = listOf("b"), rules = listOf(RoutingRule("proxy", "domain", "geosite:youtube")))
        val c = cfg(XrayConfigBuilder.plan(a, listOf(a, b), XrayConfigBuilder.Options(routing = prof)).xray)
        val rp = outbounds(c).first { tag(it) == XrayConfigBuilder.ROUTING_PROXY_TAG }
        assertEquals("r.example", rp["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertTrue(rules(c).any { it["outboundTag"]?.jsonPrimitive?.content == XrayConfigBuilder.ROUTING_PROXY_TAG })
        assertEquals("proxy", tag(outbounds(c)[0]))
    }

    // ───────── fragment / noise / mux ─────────

    private fun stream(c: JsonObject) = outbounds(c)[0]["streamSettings"]!!.jsonObject

    @Test fun fragmentFinalmask() {
        val adv = AdvancedSettings(fragLength = "60-120", fragDelay = "5-9", fragMaxSplit = "150")
        val c = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(fragmentation = true, adv = adv)))
        val m = stream(c)["finalmask"]!!.jsonObject["tcp"]!!.jsonArray[0].jsonObject
        assertEquals("fragment", m["type"]!!.jsonPrimitive.content)
        val s = m["settings"]!!.jsonObject
        assertEquals("tlshello", s["packets"]!!.jsonPrimitive.content)
        assertEquals("60-120", s["length"]!!.jsonPrimitive.content)
        assertEquals("5-9", s["delay"]!!.jsonPrimitive.content)
        assertEquals("150", s["maxSplit"]!!.jsonPrimitive.content)
        assertFalse(stream(c)["sockopt"]?.jsonObject?.containsKey("fragment") ?: false)

        val r = cfg(XrayConfigBuilder.forProfile(vlessReality, XrayConfigBuilder.Options(fragmentation = true)))
        assertEquals("1-3", stream(r)["finalmask"]!!.jsonObject["tcp"]!!.jsonArray[0].jsonObject["settings"]!!.jsonObject["packets"]!!.jsonPrimitive.content)

        val plain = cfg(XrayConfigBuilder.forProfile(vlessTls.copy(security = null, server = "10.0.0.5"), XrayConfigBuilder.Options(fragmentation = true)))
        assertNull(stream(plain)["finalmask"])
        val off = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(fragmentation = false)))
        assertNull(stream(off)["finalmask"])
    }

    @Test fun noiseFinalmask() {
        val hy2 = XrayProfile("H", "hysteria", "h.example", 443, password = "pw", network = "hysteria", security = "tls", sni = "h.example", alpn = "h3")
        val adv = AdvancedSettings(noise = true, noiseType = "array", noisePacket = "1A,2B,3C", noiseDelay = "10-16", noiseRand = "1-8192", noiseRandRange = "0-255")
        val c = cfg(XrayConfigBuilder.forProfile(hy2, XrayConfigBuilder.Options(adv = adv)))
        val n = stream(c)["finalmask"]!!.jsonObject["udp"]!!.jsonArray[0].jsonObject
        assertEquals("noise", n["type"]!!.jsonPrimitive.content)
        val items = n["settings"]!!.jsonObject["noise"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, items.size)
        assertEquals(listOf(26, 43, 60), items[0]["packet"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertNull(items[0]["rand"])
        assertEquals("1-8192", items[1]["rand"]!!.jsonPrimitive.content)
        assertNull(items[1]["packet"])
        // salamander already occupies the UDP mask → no noise
        val withObfs = cfg(XrayConfigBuilder.forProfile(hy2.copy(obfsPassword = "x"), XrayConfigBuilder.Options(adv = adv)))
        val udp = stream(withObfs)["finalmask"]!!.jsonObject["udp"]!!.jsonArray
        assertEquals(1, udp.size)
        assertEquals("salamander", udp[0].jsonObject["type"]!!.jsonPrimitive.content)
        // TCP profiles never get noise
        assertNull(stream(cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(adv = adv))))["finalmask"])
        assertEquals("abc", (XrayConfigBuilder.noisePacket("str", "abc") as kotlinx.serialization.json.JsonPrimitive).content)
        assertNull(XrayConfigBuilder.noisePacket("hex", "zz"))
    }

    @Test fun muxParams() {
        val adv = AdvancedSettings(muxConcurrency = 16, muxXudpConcurrency = 4, muxXudpQuic = "skip")
        val c = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(mux = true, adv = adv)))
        val mux = outbounds(c)[0]["mux"]!!.jsonObject
        assertTrue(mux["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(16, mux["concurrency"]!!.jsonPrimitive.int)
        assertEquals(4, mux["xudpConcurrency"]!!.jsonPrimitive.int)
        assertEquals("skip", mux["xudpProxyUDP443"]!!.jsonPrimitive.content)
        // vision never muxes
        val v = cfg(XrayConfigBuilder.forProfile(vlessReality, XrayConfigBuilder.Options(mux = true, adv = adv)))
        assertFalse(outbounds(v)[0]["mux"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    // ───────── inbounds / dns / log ─────────

    @Test fun inboundsDnsAndLog() {
        val adv = AdvancedSettings(socksPort = 20808, httpInbound = true, httpPort = 20809, localDns = true, localDnsPort = 10853)
        val c = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(socksPort = 20808, adv = adv, logDir = "/data/x/logs", logLevel = "info")))
        val ins = inbounds(c)
        assertEquals(20808, ins.first { tag(it) == "socks" }["port"]!!.jsonPrimitive.int)
        assertEquals(20809, ins.first { tag(it) == "http" }["port"]!!.jsonPrimitive.int)
        val dnsIn = ins.first { tag(it) == "dns-in" }
        assertEquals(10853, dnsIn["port"]!!.jsonPrimitive.int)
        assertTrue(rules(c).any { r -> r["inboundTag"]?.jsonArray?.any { it.jsonPrimitive.content == "dns-in" } == true })
        assertTrue(ins.none { tag(it) == "tun" })
        val log = c["log"]!!.jsonObject
        assertEquals("/data/x/logs/xray-error.log", log["error"]!!.jsonPrimitive.content)
        assertEquals("/data/x/logs/xray-access.log", log["access"]!!.jsonPrimitive.content)
        val quiet = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(adv = AdvancedSettings(logging = false), logDir = "/l")))
        assertEquals("none", quiet["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive.content)
        assertNull(quiet["log"]!!.jsonObject["error"])
    }

    @Test fun dnsOverTcpForUdpLessLeaf() {
        val http = XrayProfile("H", "http", "p.example", 8080, username = "u", password = "p")
        val e = ServerEntry("h", EngineKind.XRAY, "H", xray = http)
        val c = cfg(XrayConfigBuilder.plan(e, listOf(e)).xray)
        val servers = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(servers.all { it.startsWith("tcp://") })
        val v = ServerEntry("v", EngineKind.XRAY, "V", xray = vlessTls)
        val cv = cfg(XrayConfigBuilder.plan(v, listOf(v)).xray)
        assertTrue(cv["dns"]!!.jsonObject["servers"]!!.jsonArray.none { it.jsonPrimitive.content.startsWith("tcp://") })
    }

    @Test fun dnsFromJson() {
        val adv = AdvancedSettings(dnsFromJson = true, dnsJson = """{"servers":["https://dns.google/dns-query"],"queryStrategy":"UseIP"}""")
        val c = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(adv = adv)))
        assertEquals("https://dns.google/dns-query", c["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("UseIP", c["dns"]!!.jsonObject["queryStrategy"]!!.jsonPrimitive.content)
        val arr = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(adv = adv.copy(dnsJson = """["8.8.8.8"]"""))))
        assertEquals("8.8.8.8", arr["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonPrimitive.content)
        val bad = cfg(XrayConfigBuilder.forProfile(vlessTls, XrayConfigBuilder.Options(adv = adv.copy(dnsJson = "{oops"))))
        assertEquals("1.1.1.1", bad["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonPrimitive.content)
    }

    // ───────── excluded routes ─────────

    @Test fun cidrComplement() {
        val ex = listOf(Cidr.parseV4("10.0.0.0/8")!!, Cidr.parseV4("192.168.1.7")!!)
        val routes = Cidr.complement(ex)
        fun covers(r: Cidr.V4, ip: String): Boolean {
            val x = Cidr.parseV4(ip)!!.addr
            val mask = if (r.prefix == 0) 0L else (0xFFFFFFFFL shl (32 - r.prefix)) and 0xFFFFFFFFL
            return (x and mask) == r.addr
        }
        assertTrue(routes.none { covers(it, "10.1.2.3") })
        assertTrue(routes.none { covers(it, "192.168.1.7") })
        assertTrue(routes.any { covers(it, "192.168.1.8") })
        assertTrue(routes.any { covers(it, "8.8.8.8") })
        assertTrue(routes.any { covers(it, "11.0.0.1") })
        // total size = 2^32 - 2^24 - 1
        val total = routes.sumOf { 1L shl (32 - it.prefix) }
        assertEquals((1L shl 32) - (1L shl 24) - 1, total)
        assertNull(Cidr.parseV4("300.1.1.1/8"))
        assertEquals("10.0.0.0/8", Cidr.parseV4("10.9.9.9/8").toString())
    }

    // ───────── mihomo engine ─────────

    @Test fun mihomoPlanForSnellV2() {
        val p = LinkParser.parseXray("snell://psk1@s.example:9102?version=2&obfs=tls&obfs-host=bing.com#S2")!!
        val e = ServerEntry("s", EngineKind.XRAY, "S", xray = p)
        val plan = XrayConfigBuilder.plan(e, listOf(e))
        assertNotNull(plan.mihomo)
        assertEquals(listOf(MihomoConfigBuilder.BASE_PORT), plan.mihomoPorts)
        assertNull(plan.singBox)
        assertTrue(plan.engineLabel.contains("mihomo"))
        val ob = outbounds(cfg(plan.xray))[0]
        assertEquals("socks", ob["protocol"]!!.jsonPrimitive.content)
        assertEquals(MihomoConfigBuilder.BASE_PORT, ob["settings"]!!.jsonObject["port"]!!.jsonPrimitive.int)
        val slot = cfg(plan.mihomo!!)["slots"]!!.jsonArray[0].jsonObject
        assertEquals("mihomo", slot["type"]!!.jsonPrimitive.content)
        val proxy = slot["proxy"]!!.jsonObject
        assertEquals("snell", proxy["type"]!!.jsonPrimitive.content)
        assertEquals(2, proxy["version"]!!.jsonPrimitive.int)
        assertEquals("psk1", proxy["psk"]!!.jsonPrimitive.content)
        assertEquals("tls", proxy["obfs-opts"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("false", proxy["udp"]!!.jsonPrimitive.content)
        // snell v2 can't carry UDP → DNS over TCP
        assertTrue(cfg(plan.xray)["dns"]!!.jsonObject["servers"]!!.jsonArray.all { it.jsonPrimitive.content.startsWith("tcp://") })
        // snell v4 stays on sing-box
        val v4 = ServerEntry("s4", EngineKind.XRAY, "S4", xray = p.copy(protoVersion = 4))
        val plan4 = XrayConfigBuilder.plan(v4, listOf(v4))
        assertNull(plan4.mihomo); assertNotNull(plan4.singBox)
    }

    @Test fun mihomoHttp3Slot() {
        val h3 = LinkParser.parseXray("http3://alice:pw@h3.example:8443/proxy?sni=sni.example&host=auth.example&insecure=1#H3")!!
        assertEquals(8443, h3.port)
        assertEquals("/proxy", h3.path)
        assertEquals("auth.example", h3.host)
        assertTrue(h3.insecure)
        val e = ServerEntry("h", EngineKind.XRAY, "H", xray = h3)
        val plan = XrayConfigBuilder.plan(e, listOf(e))
        val slot = cfg(plan.mihomo!!)["slots"]!!.jsonArray[0].jsonObject
        assertEquals("h3", slot["type"]!!.jsonPrimitive.content)
        val o = slot["h3"]!!.jsonObject
        assertEquals("h3.example", o["server"]!!.jsonPrimitive.content)
        assertEquals("sni.example", o["sni"]!!.jsonPrimitive.content)
        assertEquals("alice", o["username"]!!.jsonPrimitive.content)
        assertEquals("/proxy", o["path"]!!.jsonPrimitive.content)
        val exclave = LinkParser.parseXray("quic://u:p@q.example:443?sni=q.example#Q")!!
        assertEquals("http3", exclave.protocol)
    }

    @Test fun mihomoProxiesForSsrTrustTunnelShadowQuic() {
        val ssrBody = "ssr.example:8388:auth_aes128_md5:aes-256-cfb:tls1.2_ticket_fastauth:" +
            Base64.getUrlEncoder().withoutPadding().encodeToString("secret".toByteArray()) +
            "/?obfsparam=" + Base64.getUrlEncoder().withoutPadding().encodeToString("cdn.example".toByteArray()) +
            "&protoparam=" + Base64.getUrlEncoder().withoutPadding().encodeToString("1:abc".toByteArray()) +
            "&remarks=" + Base64.getUrlEncoder().withoutPadding().encodeToString("Мой SSR".toByteArray())
        val ssr = LinkParser.parseXray("ssr://" + Base64.getUrlEncoder().withoutPadding().encodeToString(ssrBody.toByteArray()))!!
        assertEquals("ssr", ssr.protocol)
        assertEquals("Мой SSR", ssr.remarks)
        assertEquals("secret", ssr.password)
        assertEquals("auth_aes128_md5", ssr.ssrProtocol)
        assertEquals("tls1.2_ticket_auth", ssr.ssrObfs)
        assertEquals("cdn.example", ssr.ssrObfsParam)
        assertEquals("1:abc", ssr.ssrProtocolParam)
        val px = MihomoConfigBuilder.proxy(ssr)
        assertEquals("ssr", px["type"]!!.jsonPrimitive.content)
        assertEquals("aes-256-cfb", px["cipher"]!!.jsonPrimitive.content)
        assertEquals("cdn.example", px["obfs-param"]!!.jsonPrimitive.content)

        val sq = LinkParser.parseXray("sq://user:pass@sq.example:443?sni=sq.example&udp_mode=datagram&zero_rtt=true&alpn=h3#SQ")!!
        assertEquals("shadowquic", sq.protocol)
        val sqp = MihomoConfigBuilder.proxy(sq)
        assertEquals("false", sqp["udp-over-stream"]!!.jsonPrimitive.content)
        assertEquals("true", sqp["zero-rtt"]!!.jsonPrimitive.content)
        assertEquals("h3", sqp["alpn"]!!.jsonArray[0].jsonPrimitive.content)

        val tt = LinkParser.parseXray("trusttunnel://u:p@tt.example:443?sni=tt.example&quic=1&fp=chrome#TT")!!
        val ttp = MihomoConfigBuilder.proxy(tt)
        assertEquals("trusttunnel", ttp["type"]!!.jsonPrimitive.content)
        assertEquals("true", ttp["quic"]!!.jsonPrimitive.content)
        assertEquals("chrome", ttp["client-fingerprint"]!!.jsonPrimitive.content)

        val s4 = LinkParser.parseXray("socks4a://uid@s4.example:1080#S4")!!
        assertEquals("socks4", s4.protocol)
        assertEquals("4a", s4.method)
        assertEquals(ProtocolSupport.ENGINE_SINGBOX, ProtocolSupport.engineOf(s4))
        val s4cfg = SingBoxConfigBuilder.toJson(listOf(SingBoxConfigBuilder.Slot(0, s4)), "1.1.1.1", "auto")
        assertTrue(s4cfg.contains("\"version\": \"4a\"") || s4cfg.contains("\"version\":\"4a\""))
    }

    @Test fun mihomoCantBeMidChain() {
        val a = ServerEntry("a", EngineKind.XRAY, "A", xray = vlessTls)
        val s = ServerEntry("s", EngineKind.XRAY, "S", xray = XrayProfile("S", "ssr", "x.example", 1, password = "p", method = "aes-256-cfb"))
        val okChain = ServerEntry("c1", EngineKind.XRAY, "C1", xray = XrayProfile("C1", "chain", "", 0, chainIds = listOf("s", "a")))
        val plan = XrayConfigBuilder.plan(okChain, listOf(a, s, okChain))
        assertNotNull(plan.mihomo)
        val bad = ServerEntry("c2", EngineKind.XRAY, "C2", xray = XrayProfile("C2", "chain", "", 0, chainIds = listOf("a", "s")))
        try { XrayConfigBuilder.plan(bad, listOf(a, s, bad)); fail("mid-chain mihomo must be refused") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("первым"))
        }
    }

    @Test fun protocolMatrixHasNoUnsupported() {
        assertTrue(ProtocolSupport.UNSUPPORTED.isEmpty())
        listOf("vless", "vmess", "trojan", "shadowsocks", "wireguard", "hysteria", "socks", "http", "https", "hysteria1", "tuic",
            "anytls", "ssh", "snell", "naive", "socks4", "mieru", "ssr", "trusttunnel", "shadowquic", "http3").forEach {
            assertTrue(it, ProtocolSupport.isRunnable(it))
        }
        // legacy 1.0.5 stamps are cleared on load
        val old = XrayProfile("o", "http3", "h", 443, unsupportedReason = "HTTP/3 CONNECT-прокси не входит в Xray-core и sing-box (есть HTTP/HTTPS CONNECT и Naive/QUIC).")
        assertNull(ProtocolSupport.migrate(old).unsupportedReason)
        val oldSnell = XrayProfile("o", "snell", "h", 443, protoVersion = 2, unsupportedReason = "Snell v1–v3 не поддерживается (sing-box умеет v4/v5/v6).")
        assertNull(ProtocolSupport.migrate(oldSnell).unsupportedReason)
    }

    @Suppress("unused")
    private fun dummy(a: JsonArray) = a
}
