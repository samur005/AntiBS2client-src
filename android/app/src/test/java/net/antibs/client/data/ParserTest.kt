package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class ParserTest {

    private val links: List<String> by lazy {
        javaClass.classLoader.getResourceAsStream("remnawave_links.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
    }

    @Test fun remnawaveLinkCount() { assertEquals(17, links.size) }

    @Test fun everyRemnawaveLinkParses() {
        links.forEachIndexed { i, l ->
            val p = LinkParser.parseXray(l)
            assertNotNull("line $i failed: ${l.take(40)}", p)
        }
    }

    @Test fun remnawaveProtocols() {
        val parsed = links.map { LinkParser.parseXray(it)!! }
        assertEquals(1, parsed.count { it.protocol == "shadowsocks" })
        assertEquals(16, parsed.count { it.protocol == "vless" })
        val ss = parsed.first { it.protocol == "shadowsocks" }
        assertEquals("2022-blake3-aes-256-gcm", ss.method)
        assertEquals("web.max.ru", ss.server)
        assertEquals(1234, ss.port)
        assertTrue(ss.password!!.isNotEmpty())
        assertTrue(ss.remarks.contains("Автовыбор"))
        val reality = parsed.filter { it.security == "reality" }
        assertEquals(13, reality.size)
        reality.forEach {
            assertEquals("xtls-rprx-vision", it.flow)
            assertEquals("tcp", it.network)
            assertEquals("firefox", it.fingerprint)
            assertTrue(!it.publicKey.isNullOrEmpty())
            assertTrue(!it.shortId.isNullOrEmpty())
            assertTrue(!it.sni.isNullOrEmpty())
        }
        val xhttp = parsed.filter { it.network == "xhttp" }
        assertEquals(3, xhttp.size)
        xhttp.forEach {
            assertEquals("tls", it.security)
            assertEquals("packet-up", it.mode)
            assertTrue(it.extra!!.contains("xPaddingObfsMode"))
            assertTrue(it.extra!!.contains("sessionIDTable"))
            assertEquals("h2", it.alpn)
        }
    }

    @Test fun flagEmoji() {
        val p = LinkParser.parseXray(links[1])!!
        assertEquals("🇨🇦", LinkParser.flagOf(p.remarks))
    }

    @Test fun outboundJsonValidForAll() {
        links.forEach { l ->
            val p = LinkParser.parseXray(l)!!
            val cfg = Json.parseToJsonElement(XrayConfigBuilder.forProfile(p)).jsonObject
            val ob = cfg["outbounds"]!!.jsonArray[0].jsonObject
            assertEquals(p.protocol, ob["protocol"]!!.jsonPrimitive.content)
            if (p.protocol == "vless") {
                val user = ob["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
                assertEquals(p.id, user["id"]!!.jsonPrimitive.content)
            }
            if (p.security == "reality") {
                val ss = ob["streamSettings"]!!.jsonObject
                assertEquals("reality", ss["security"]!!.jsonPrimitive.content)
                val r = ss["realitySettings"]!!.jsonObject
                assertEquals(p.publicKey, r["publicKey"]!!.jsonPrimitive.content)
                assertEquals(p.shortId, r["shortId"]!!.jsonPrimitive.content)
                assertEquals(p.sni, r["serverName"]!!.jsonPrimitive.content)
                assertEquals("xtls-rprx-vision", ob["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject["flow"]!!.jsonPrimitive.content)
            }
            if (p.network == "xhttp") {
                val x = ob["streamSettings"]!!.jsonObject["xhttpSettings"]!!.jsonObject
                assertEquals("packet-up", x["mode"]!!.jsonPrimitive.content)
                assertTrue(x["extra"]!!.jsonObject.containsKey("sessionIDTable"))
            }
            if (p.protocol == "shadowsocks") {
                val srv = ob["settings"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
                assertEquals("2022-blake3-aes-256-gcm", srv["method"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun syntheticTransports() {
        fun check(link: String, net: String, sec: String?) {
            val p = LinkParser.parseXray(link)!!
            assertEquals(net, p.network); assertEquals(sec, p.security)
            val ob = Json.parseToJsonElement(XrayConfigBuilder.forProfile(p)).jsonObject["outbounds"]!!.jsonArray[0].jsonObject
            val ss = ob["streamSettings"]!!.jsonObject
            when (net) {
                "ws" -> assertEquals("/ws", ss["wsSettings"]!!.jsonObject["path"]!!.jsonPrimitive.content)
                "grpc" -> assertEquals("svc", ss["grpcSettings"]!!.jsonObject["serviceName"]!!.jsonPrimitive.content)
                "httpupgrade" -> assertEquals("/up", ss["httpupgradeSettings"]!!.jsonObject["path"]!!.jsonPrimitive.content)
                "xhttp" -> assertTrue(ss["xhttpSettings"]!!.jsonObject.containsKey("extra"))
            }
        }
        check("vless://11111111-1111-1111-1111-111111111111@h.example:443?encryption=none&type=ws&path=%2Fws&host=h.example&security=tls&sni=h.example#ws", "ws", "tls")
        check("vless://11111111-1111-1111-1111-111111111111@h.example:443?encryption=none&type=grpc&serviceName=svc&security=tls&sni=h.example#grpc", "grpc", "tls")
        check("vless://11111111-1111-1111-1111-111111111111@h.example:443?encryption=none&type=httpupgrade&path=%2Fup&host=h.example&security=tls#up", "httpupgrade", "tls")
        check("vless://11111111-1111-1111-1111-111111111111@h.example:443?encryption=none&type=xhttp&path=%2Fx&mode=auto&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D&security=tls&sni=h.example#xhttp", "xhttp", "tls")
        check("vmess://eyJhZGQiOiJob3N0LmV4YW1wbGUiLCJwb3J0IjoiNDQzIiwiaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEiLCJhaWQiOiIwIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJob3N0LmV4YW1wbGUiLCJwYXRoIjoiL3dzIiwidGxzIjoidGxzIiwic24iOiJob3N0LmV4YW1wbGUiLCJwcyI6IlZNZXNzIFdTIn0=#ignored", "ws", "tls")
        val trojan = LinkParser.parseXray("trojan://secret@t.example:443?type=tcp&security=tls&sni=t.example#Trojan")!!
        assertEquals("trojan", trojan.protocol); assertEquals("secret", trojan.password); assertEquals("tls", trojan.security)
        val wg = LinkParser.parseXray("wireguard://c2VjcmV0S2V5@wg.example:51820?publickey=cHVibGljS2V5&address=10.0.0.2%2F32&mtu=1280#WG")!!
        assertEquals("wireguard", wg.protocol); assertEquals("c2VjcmV0S2V5", wg.secretKey); assertEquals("cHVibGljS2V5", wg.publicKey); assertEquals(1280, wg.mtu)
        val wgOb = Json.parseToJsonElement(XrayConfigBuilder.forProfile(wg)).jsonObject["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("wireguard", wgOb["protocol"]!!.jsonPrimitive.content)
        assertEquals("wg.example:51820", wgOb["settings"]!!.jsonObject["peers"]!!.jsonArray[0].jsonObject["endpoint"]!!.jsonPrimitive.content)
    }

    @Test fun ssLegacyAndPlainUserinfo() {
        val legacy = "method:pass@1.2.3.4:8388"
        val link = "ss://" + Base64.getEncoder().encodeToString(legacy.toByteArray()) + "#legacy"
        val p = LinkParser.parseXray(link)!!
        assertEquals("method", p.method); assertEquals("pass", p.password); assertEquals("1.2.3.4", p.server); assertEquals(8388, p.port); assertEquals("legacy", p.remarks)
        val plain = LinkParser.parseXray("ss://2022-blake3-aes-256-gcm:mysecret@h.example:1234#plain")!!
        assertEquals("2022-blake3-aes-256-gcm", plain.method); assertEquals("mysecret", plain.password)
    }

    @Test fun subscriptionBase64AndHeaders() {
        val body = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())
        val r = SubscriptionParser.parse(body, "s1", "upload=1; download=2; total=9; expire=1700000000", "base64:QW50aUJTIChSVyk=")!!
        assertEquals("remnawave", r.kind)
        assertEquals(17, r.servers.size)
        assertEquals(1L, r.upload); assertEquals(2L, r.download); assertEquals(9L, r.total); assertEquals(1700000000L, r.expireEpoch)
        assertEquals("AntiBS (RW)", r.title)
        assertTrue(r.servers.all { it.subscriptionId == "s1" })
    }

    @Test fun qwdttJsonSchema() {
        val doc = """
        {"subscriptionName":"Subbota","description":"Подписка · до 31.12.2026","trafficUsedMb":12.5,"trafficLimitMb":51200,
         "version":1,"updatedAt":"2026-10-08","profiles":[
           {"name":"🇳🇱 Нидерланды","peer":"nl.example.com:9000","hashes":"hash1,hash2","workers":9,"port":9000,"password":"pw-nl"},
           {"name":"🇫🇮 Финляндия","peer":"fi.example.com","hashes":"","workers":0,"port":0,"password":"pw-fi"},
           {"name":"🇩🇪 Германия","peer":"de.example.com:9443","hashes":"h3","workers":18,"port":9443,"password":"pw de"}
         ]}
        """.trimIndent()
        val r = SubscriptionParser.parse(doc, "q1")!!
        assertEquals("qwdtt", r.kind)
        assertEquals("Subbota", r.title)
        assertEquals(12.5, r.trafficUsedMb, 0.001)
        assertEquals(51200.0, r.trafficLimitMb, 0.001)
        assertEquals(3, r.servers.size)
        assertTrue(r.servers.all { it.engine == EngineKind.QWDTT })
        val fi = r.servers[1].qwdtt!!
        assertEquals(9, fi.workers)          // 0 falls back to default
        assertEquals(9000, fi.port)
        assertEquals("", fi.hashes)
        val de = r.servers[2].qwdtt!!
        assertEquals("pw de", de.password)
        assertEquals(18, de.workers)
        val exp = SubscriptionParser.expiryFromDescription(r.description)
        assertTrue(exp > 1_700_000_000L)
        // base64-wrapped JSON (LucX subEncrypt=true)
        val wrapped = SubscriptionParser.parse(Base64.getEncoder().encodeToString(doc.toByteArray()), "q2")!!
        assertEquals(3, wrapped.servers.size)
        // bare array + alternative keys (SpaceNeuroX parser compatibility)
        val arr = """[{"name":"N","peer":"p.example:1","vkHashes":"h","workersPerHash":6,"listenPort":8000,"pass":"x"}]"""
        val a = SubscriptionParser.parse(arr, "q3")!!
        assertEquals("h", a.servers[0].qwdtt!!.hashes)
        assertEquals(6, a.servers[0].qwdtt!!.workers)
        assertEquals(8000, a.servers[0].qwdtt!!.port)
        assertEquals("x", a.servers[0].qwdtt!!.password)
        // qwdtt://config URI line(s)
        val uri = "qwdtt://config?name=%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B&peer=nl.example%3A9000&hashes=h1%2Ch2&workers=9&port=9000&pass=s3cret"
        val u = SubscriptionParser.parse(uri, "q4")!!
        val qp = u.servers[0].qwdtt!!
        assertEquals("Нидерланды", qp.name)
        assertEquals("nl.example:9000", qp.peer)
        assertEquals("h1,h2", qp.hashes)
        assertEquals("s3cret", qp.password)
        // profile without peer is rejected
        val bad = """{"subscriptionName":"x","profiles":[{"name":"no peer","password":"p"}]}"""
        assertTrue(SubscriptionParser.parse(bad, "q5") == null)
    }

    @Test fun wgConfigParse() {
        val text = """
            [Interface]
            PrivateKey = abc+def=
            Address = 10.70.0.2/32
            DNS = 1.1.1.1, 8.8.8.8
            MTU = 1280
            [Peer]
            PublicKey = xyz=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 127.0.0.1:9000
            PersistentKeepalive = 25
        """.trimIndent()
        val wg = WgConfig.parse(text)!!
        assertEquals("abc+def=", wg.privateKey)
        assertEquals("xyz=", wg.publicKey)
        assertEquals("127.0.0.1:9000", wg.endpoint)
        assertEquals(1280, wg.mtu)
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.forQwdtt(wg)).jsonObject
        val ob = cfg["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("wireguard", ob["protocol"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1:9000", ob["settings"]!!.jsonObject["peers"]!!.jsonArray[0].jsonObject["endpoint"]!!.jsonPrimitive.content)
        assertTrue(cfg["inbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content == "socks")
    }

    @Test fun hysteria2AndSocksHttp() {
        val hy = LinkParser.parseXray("hy2://secret@hy.example:8443?sni=hy.example&insecure=1&obfs=salamander&obfs-password=ob#HY2")!!
        assertEquals("hysteria", hy.protocol)
        assertEquals("secret", hy.password)
        assertEquals("hy.example", hy.server)
        assertEquals(8443, hy.port)
        assertEquals("tls", hy.security)
        assertEquals("h3", hy.alpn)
        assertEquals("ob", hy.obfsPassword)
        assertTrue(hy.insecure)
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.forProfile(hy)).jsonObject
        val ob = cfg["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("hysteria", ob["protocol"]!!.jsonPrimitive.content)
        assertEquals(2, ob["settings"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt())
        val ss = ob["streamSettings"]!!.jsonObject
        assertEquals("hysteria", ss["network"]!!.jsonPrimitive.content)
        assertEquals("secret", ss["hysteriaSettings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
        assertEquals("tls", ss["security"]!!.jsonPrimitive.content)

        val socks = LinkParser.parseXray("socks5://user:pass@1.2.3.4:1080#S")!!
        assertEquals("socks", socks.protocol); assertEquals("user", socks.username); assertEquals("pass", socks.password)
        val http = LinkParser.parseXray("http://u:p@proxy.example:8080#H")!!
        assertEquals("http", http.protocol); assertEquals(8080, http.port)
        val https = LinkParser.parseXray("https://u:p@proxy.example:8443?sni=proxy.example#HS")!!
        assertEquals("https", https.protocol); assertEquals("tls", https.security)
    }

    @Test fun singBoxSchemesParseAndPlan() {
        val tuic = LinkParser.parseXray("tuic://11111111-1111-1111-1111-111111111111:pass@t.example:443?sni=t.example&congestion_control=bbr#T")!!
        assertEquals("tuic", tuic.protocol)
        assertNull(tuic.unsupportedReason)
        assertEquals("11111111-1111-1111-1111-111111111111", tuic.id)
        assertEquals("pass", tuic.password)
        assertEquals("bbr", tuic.congestionControl)
        assertTrue(ProtocolSupport.isSingBox("tuic") && ProtocolSupport.isRunnable("tuic"))

        val hy1 = LinkParser.parseXray("hysteria://auth@h.example:443?peer=h.example&upmbps=100&downmbps=100&obfs=xplus&obfsParam=secret#H1")!!
        assertEquals("hysteria1", hy1.protocol)
        assertNull(hy1.unsupportedReason)
        assertEquals("auth", hy1.password)
        assertEquals("secret", hy1.obfsPassword)

        val hyShort = LinkParser.parseXray("hy://auth@h.example:443#H")!!
        assertEquals("hysteria1", hyShort.protocol)

        val ssh = LinkParser.parseXray("ssh://root:pw@ssh.example:22#SSH")!!
        assertEquals("ssh", ssh.protocol)
        assertEquals("root", ssh.username)
        assertEquals("pw", ssh.password)
        assertEquals(22, ssh.port)

        val snell = LinkParser.parseXray("snell://psk@s.example:9102?version=4&obfs=http&obfs-host=bing.com#Sn")!!
        assertEquals("snell", snell.protocol)
        assertEquals(4, snell.protoVersion)
        assertEquals("http", snell.headerType)
        assertEquals("bing.com", snell.host)
        assertNull(snell.unsupportedReason)

        val snellOld = LinkParser.parseXray("snell://psk@s.example:9102?version=2#Old")!!
        assertNull(snellOld.unsupportedReason)
        assertEquals(2, snellOld.protoVersion)
        assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineOf(snellOld))
        assertEquals(ProtocolSupport.ENGINE_SINGBOX, ProtocolSupport.engineOf(snell))

        val naive = LinkParser.parseXray("naive+https://u:p@n.example:443?sni=n.example#N")!!
        assertEquals("naive", naive.protocol)
        assertEquals("u", naive.username)
        assertEquals("tls", naive.security)

        val naiveQ = LinkParser.parseXray("naive+quic://u:p@n.example:443#NQ")!!
        assertEquals("quic", naiveQ.network)

        val any = LinkParser.parseXray("anytls://pass@a.example:443?sni=a.example#A")!!
        assertEquals("anytls", any.protocol)
        assertEquals("pass", any.password)
        assertEquals("tls", any.security)

        // Xray plan dials sing-box via local SOCKS; sing-box JSON carries the real outbound.
        val entry = ServerEntry("t", EngineKind.XRAY, "T", xray = tuic)
        val plan = XrayConfigBuilder.plan(entry, listOf(entry))
        assertTrue(plan.singBox != null)
        assertEquals(1, plan.singBoxSlots.size)
        val xOb = Json.parseToJsonElement(plan.xray).jsonObject["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("socks", xOb["protocol"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfigBuilder.BASE_PORT, xOb["settings"]!!.jsonObject["port"]!!.jsonPrimitive.content.toInt())
        val sb = Json.parseToJsonElement(plan.singBox!!).jsonObject
        val sbOut = sb["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tuic", sbOut["type"]!!.jsonPrimitive.content)
        assertEquals("t.example", sbOut["server"]!!.jsonPrimitive.content)

        for (p in listOf(hy1, ssh, snell, naive, any)) {
            val e = ServerEntry("x", EngineKind.XRAY, p.remarks, xray = p)
            val pl = XrayConfigBuilder.plan(e, listOf(e))
            assertTrue("expected sing-box for ${p.protocol}", pl.singBox != null)
            val type = Json.parseToJsonElement(pl.singBox!!).jsonObject["outbounds"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
            val expected = when (p.protocol) { "hysteria1" -> "hysteria"; else -> p.protocol }
            assertEquals(expected, type)
        }
    }

    @Test fun migrateClearsLegacyUnsupported() {
        val legacy = XrayProfile("T", "tuic", "t.example", 443, id = null, username = "uuid", password = "p",
            unsupportedReason = "TUIC не входит в Xray-core; второй движок (sing-box) не подключён.")
        val m = ProtocolSupport.migrate(legacy)
        assertNull(m.unsupportedReason)
        assertEquals("uuid", m.id)
    }

    @Test fun importTextClassifies() {
        assertTrue(ImportText.classify("https://sub.example.com/token") is ImportOutcome.SubscriptionUrl)
        val multi = ImportText.classify("vless://11111111-1111-1111-1111-111111111111@h.example:443?encryption=none&type=tcp&security=tls#A\nhy2://x@hy.example:443#B")
        assertTrue(multi is ImportOutcome.Servers)
        assertEquals(2, (multi as ImportOutcome.Servers).entries.size)
        val socks = ImportText.classify("socks5://u:p@1.2.3.4:1080#S")
        assertTrue(socks is ImportOutcome.Servers)
    }

    @Test fun customJsonAndChainBuilder() {
        val custom = LinkParser.parseCustomJson("""{"protocol":"freedom","tag":"x","settings":{}}""")!!
        assertEquals("custom", custom.protocol)
        val cfg = XrayConfigBuilder.customConfig("""{"protocol":"freedom","settings":{}}""")
        assertTrue(cfg.contains("freedom") && cfg.contains("protocol"))
        val a = XrayProfile("A", "socks", "1.1.1.1", 1080, username = "u", password = "p")
        val b = XrayProfile("B", "socks", "2.2.2.2", 1080)
        val sa = ServerEntry("a", EngineKind.XRAY, "A", xray = a)
        val sb = ServerEntry("b", EngineKind.XRAY, "B", xray = b)
        val chain = ServerEntry("c", EngineKind.XRAY, "Chain", xray = XrayProfile("Chain", "chain", "", 0, chainIds = listOf("a", "b")))
        val json = XrayConfigBuilder.forEntry(chain, listOf(sa, sb, chain))
        assertTrue(json.contains("dialerProxy"))
        assertTrue(json.contains("hop-0"))
    }

    @Test fun mieruAndHttpVariants() {
        val http = LinkParser.parseXray("http://u:p@proxy.example:8080#H")!!
        assertEquals("http", http.protocol)
        assertNull(http.security)
        val https = LinkParser.parseXray("https://u:p@proxy.example:8443?sni=proxy.example#HS")!!
        assertEquals("https", https.protocol)
        assertEquals("tls", https.security)
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.forProfile(https)).jsonObject
        assertEquals("http", cfg["outbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content)

        val h3 = LinkParser.parseXray("http3://u:p@h3.example:443?sni=h3.example#H3")!!
        assertEquals("http3", h3.protocol)
        assertNull(h3.unsupportedReason)
        assertTrue(ProtocolSupport.isRunnable("http3"))

        val mieru = LinkParser.parseXray("mieru://alice:secret@1.2.3.4:2999?protocol=UDP&mtu=1400#M")!!
        assertEquals("mieru", mieru.protocol)
        assertEquals("alice", mieru.username)
        assertEquals("UDP", mieru.mieruTransport)
        // 1.0.19: mieru runs on mihomo's mieru v3 outbound (libmhx), not on libmieru 1.15.1
        assertTrue(ProtocolSupport.isRunnable("mieru"))
        val entry = ServerEntry("m", EngineKind.XRAY, "M", xray = mieru)
        val plan = XrayConfigBuilder.plan(entry, listOf(entry))
        assertTrue(plan.mieruInstances.isEmpty())
        assertTrue(plan.mihomoPorts.isNotEmpty())
        assertTrue(Regex(""""type"\s*:\s*"mieru"""").containsMatchIn(plan.mihomo!!) && Regex(""""transport"\s*:\s*"UDP"""").containsMatchIn(plan.mihomo!!))
        assertEquals("socks", Json.parseToJsonElement(plan.xray).jsonObject["outbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        assertTrue(plan.mihomo!!.contains("alice"))
    }

}
