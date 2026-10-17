package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.engine.SubscriptionFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.0.19: LucX panel subscriptions (sanitized samples of real answers; keys / uuids / hosts are fake).
 *  - /subbota/…: qWDTT UA → base64 qWDTT JSON (3 profiles); any other UA → base64 link list with vless, trojan,
 *    hysteria2, naive, anytls, mierus, amneziawg, vpn:// (AmneziaVPN) and qwdtt:// links.
 *  - /jeyson/…: JSON array of full Xray configs.
 */
class LucxSubscriptionTest {
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream(n)!!.bufferedReader().readText()
    private val sub = "sub-lucx"

    @Test fun linkListImportsEveryEntry() {
        val r = SubscriptionParser.parse(res("lucx_subbota_links.txt"), sub)!!
        assertEquals(16, r.servers.size)
        assertEquals(r.servers.size, r.servers.map { it.id }.toSet().size)   // the duplicated mierus line gets its own id
        val protos = r.servers.map { it.xray?.protocol ?: "qwdtt" }
        assertEquals(listOf("vless", "vless", "vless", "vless", "vless", "trojan", "hysteria", "naive", "anytls",
            "mieru", "mieru", "amneziawg", "amneziawg", "qwdtt", "qwdtt", "qwdtt"), protos)
        r.servers.mapNotNull { it.xray }.forEach { assertTrue(it.protocol, ProtocolSupport.isRunnable(it.protocol)); assertTrue(it.unsupportedReason.isNullOrBlank()) }
        // every Xray-side host produces a plan (helper engines included)
        File("build/lucx-configs").mkdirs()
        CertPins.fetcher = { _, _, _, _ -> "12".repeat(32) }
        try {
            r.servers.filter { it.engine == EngineKind.XRAY }.forEachIndexed { i, s ->
                val plan = XrayConfigBuilder.plan(s, r.servers)
                File("build/lucx-configs/links_%02d_%s.json".format(i, s.xray!!.protocol)).writeText(plan.xray)
                plan.singBox?.let { File("build/lucx-configs/links_%02d_%s.singbox.json".format(i, s.xray!!.protocol)).writeText(it) }
                plan.mihomo?.let { File("build/lucx-configs/links_%02d_%s.mhx.json".format(i, s.xray!!.protocol)).writeText(it) }
            }
        } finally { CertPins.fetcher = null; CertPins.clear() }
    }

    @Test fun mierusLink() {
        val r = SubscriptionParser.parse(res("lucx_subbota_links.txt"), sub)!!
        val m = r.servers.first { it.xray?.protocol == "mieru" }.xray!!
        assertEquals("vpn.example.com", m.server); assertEquals(20100, m.port)
        assertEquals("user", m.username); assertEquals("fakepass", m.password)
        assertEquals("TCP", m.mieruTransport); assertEquals(1400, m.mtu)
        // mieru v3 servers: dialed by mihomo's mieru outbound (libmieru 1.15.1 can't talk to them)
        assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineOf(m))
        val px = MihomoConfigBuilder.proxy(m)
        assertEquals("mieru", px["type"]!!.jsonPrimitive.content)
        assertEquals("TCP", px["transport"]!!.jsonPrimitive.content)
        assertEquals("MULTIPLEXING_LOW", px["multiplexing"]!!.jsonPrimitive.content)
        assertEquals("HANDSHAKE_STANDARD", px["handshake-mode"]!!.jsonPrimitive.content)
        assertEquals(20100, px["port"]!!.jsonPrimitive.content.toInt())
        // the legacy mieru JSON must not carry fields mieru 1.15.1's protojson rejects
        val js = Json.parseToJsonElement(MieruConfigBuilder.clientJson(m, 11000)).jsonObject
        assertEquals(null, js["profiles"]!!.jsonArray[0].jsonObject["multiplexing"])
    }

    @Test fun amneziaWgLinkAndAmneziaVpnKeyGiveTheSameProfile() {
        val r = SubscriptionParser.parse(res("lucx_subbota_links.txt"), sub)!!
        val (link, key) = r.servers.filter { it.xray?.protocol == "amneziawg" }.map { it.xray!! }
        for (p in listOf(link, key)) {
            assertEquals("vpn.example.com", p.server); assertEquals(57753, p.port)
            assertEquals("aGVsbG8tdGhpcy1pcy1hLWZha2Uta2V5LTAwMDAwMDA=", p.secretKey)
            assertEquals("cHViLWZha2Uta2V5LWZvci10ZXN0cy0wMDAwMDAwMDA=", p.publicKey)
            assertEquals("cHNrLWZha2Uta2V5LWZvci10ZXN0cy0wMDAwMDAwMDA=", p.preSharedKey)
            assertEquals("20321-68238", p.awg!!["h1"]); assertEquals("6", p.awg!!["jc"]); assertEquals("12", p.awg!!["s4"])
            assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineOf(p))
            val m = MihomoConfigBuilder.proxy(p)
            assertEquals("wireguard", m["type"]!!.jsonPrimitive.content)
            assertEquals("10.200.0.3", m["ip"]!!.jsonPrimitive.content)
            val o = m["amnezia-wg-option"]!!.jsonObject
            assertEquals(6, o["jc"]!!.jsonPrimitive.content.toInt())
            assertEquals("319837-372405", o["h4"]!!.jsonPrimitive.content)
            assertEquals(25, o["s3"]!!.jsonPrimitive.content.toInt())
        }
        assertEquals(1420, link.mtu); assertEquals(1420, key.mtu)
        // full engine plan: Xray → socks hop → libmhx slot
        val s = r.servers.first { it.xray?.protocol == "amneziawg" }
        val plan = XrayConfigBuilder.plan(s, r.servers)
        assertNotNull(plan.mihomo)
        File("build/lucx-configs").mkdirs()
        File("build/lucx-configs/mhx_awg.json").writeText(plan.mihomo!!)
    }

    @Test fun qwdttAnswerIsMergedWithTheLinkList() {
        val q = SubscriptionParser.parse(res("lucx_subbota_qwdtt.txt"), sub)!!
        assertEquals("qwdtt", q.kind); assertEquals(3, q.servers.size)
        val links = SubscriptionParser.parse(res("lucx_subbota_links.txt"), sub)!!
        val m = SubscriptionFetcher.mergeQwdttWithLinks(q, links)
        assertEquals(16, m.servers.size)
        assertEquals("qwdtt", m.kind)                      // keeps the LucX no-HWID-first refresh order
        assertEquals(q.title, m.title); assertEquals(q.description, m.description)
        // qWDTT hosts keep the ids they had from the JSON answer → the selection survives the upgrade
        assertEquals(q.servers.map { it.id }.toSet(), m.servers.filter { it.engine == EngineKind.QWDTT }.map { it.id }.toSet())
        assertEquals(m.servers.size, m.servers.map { it.id }.toSet().size)
        // a panel that only knows qWDTT keeps the JSON answer
        assertEquals(q, SubscriptionFetcher.mergeQwdttWithLinks(q, null))
        assertEquals(q, SubscriptionFetcher.mergeQwdttWithLinks(q, q))
    }

    @Test fun xrayJsonArraySubscription() {
        CertPins.clear()
        CertPins.fetcher = { _, _, _, _ -> "ab".repeat(32) }
        try {
            val r = SubscriptionParser.parse(res("lucx_jeyson.json"), sub)!!
            assertEquals(7, r.servers.size)
            assertEquals("🇳🇱 VLESS (VLESS-TCP-TLS-max.ru)", r.servers[0].name)
            assertEquals("🇳🇱 Hysteria2 (Hysteria2-max.ru)", r.servers[6].name)
            File("build/lucx-configs").mkdirs()
            r.servers.forEachIndexed { i, s ->
                val x = s.xray!!
                assertEquals("vpn.example.com", x.server)
                val plan = XrayConfigBuilder.plan(s, r.servers)
                val cfg = plan.xray
                assertTrue(cfg.contains("\"tag\": \"socks\""))
                assertFalse(cfg.contains("allowInsecure"))
                if (i in 3..4) {
                    // plaintext VLESS → native profile dialed by mihomo (Xray 26 refuses it)
                    assertEquals("vless", x.protocol)
                    assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineOf(x))
                    assertNotNull(plan.mihomo)
                    File("build/lucx-configs/jeyson_%02d_mhx.json".format(i)).writeText(plan.mihomo!!)
                } else {
                    assertEquals("custom", x.protocol)
                    val ob = Json.parseToJsonElement(x.customJson!!).jsonObject
                    assertTrue(ob.containsKey("protocol"))       // reduced to the proxy outbound
                    assertTrue(cfg.contains("\"pinnedPeerCertSha256\": \"" + "ab".repeat(32) + "\""))
                }
                File("build/lucx-configs/jeyson_%02d.json".format(i)).writeText(cfg)
            }
            assertEquals(listOf("vless", "vless", "vless", "trojan", "hysteria"),
                r.servers.filter { it.xray!!.protocol == "custom" }.map { Json.parseToJsonElement(it.xray!!.customJson!!).jsonObject["protocol"]!!.jsonPrimitive.content })
        } finally { CertPins.fetcher = null; CertPins.clear() }
    }

    @Test fun insecureShareLinksArePinnedNotAllowInsecure() {
        CertPins.clear()
        CertPins.fetcher = { _, _, _, quic -> (if (quic) "cd" else "ef").repeat(32) }
        try {
            val r = SubscriptionParser.parse(res("lucx_subbota_links.txt"), sub)!!
            val vlessTls = r.servers.first { it.xray?.protocol == "vless" && it.xray?.security == "tls" }
            val hy2 = r.servers.first { it.xray?.protocol == "hysteria" }
            val c1 = XrayConfigBuilder.plan(vlessTls, r.servers).xray
            assertFalse(c1.contains("allowInsecure")); assertTrue(c1.contains("ef".repeat(32)))
            val c2 = XrayConfigBuilder.plan(hy2, r.servers).xray
            assertTrue(c2.contains("cd".repeat(32)))
            val plainVless = r.servers.first { it.name.contains("VLESS-WS-none") }
            assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineOf(plainVless.xray!!))
            val mh = Json.parseToJsonElement(XrayConfigBuilder.plan(plainVless, r.servers).mihomo!!).jsonObject
            val ob = mh["slots"]!!.jsonArray[0].jsonObject["proxy"]!!.jsonObject
            assertEquals("vless", ob["type"]!!.jsonPrimitive.content)
            assertEquals("ws", ob["network"]!!.jsonPrimitive.content)
            assertEquals("/ws", ob["ws-opts"]!!.jsonObject["path"]!!.jsonPrimitive.content)
            assertEquals("false", ob["tls"]!!.jsonPrimitive.content)
        } finally { CertPins.fetcher = null; CertPins.clear() }
    }

    @Test fun privateAddressesStayOnXray() {
        assertTrue(ProtocolSupport.isPrivateAddress("192.168.1.5"))
        assertTrue(ProtocolSupport.isPrivateAddress("10.0.0.1"))
        assertTrue(ProtocolSupport.isPrivateAddress("router.lan"))
        assertFalse(ProtocolSupport.isPrivateAddress("8.8.8.8"))
        assertFalse(ProtocolSupport.isPrivateAddress("vpn.example.com"))
    }

    /** 1.0.19: WebSocket can't run over h2 — "h2" in ALPN made nginx pick HTTP/2 and the WS dial failed. */
    @Test fun wsTlsNeverOffersH2() {
        val ws = LinkParser.parse("vless://11111111-2222-3333-4444-555555555555@vpn.example.com:2053?security=tls&type=ws&host=a.example&path=%2Fws&sni=a.example&alpn=h2%2Chttp%2F1.1#ws", sub)!!
        val o = Json.parseToJsonElement(XrayConfigBuilder.plan(ws, listOf(ws)).xray).jsonObject["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("http/1.1"), o["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
        val r = SubscriptionParser.parse(res("lucx_jeyson.json"), sub)!!
        CertPins.fetcher = { _, _, _, _ -> "12".repeat(32) }
        try {
            r.servers.filter { it.xray?.protocol == "custom" }.forEach { e ->
                val ob = Json.parseToJsonElement(XrayConfigBuilder.plan(e, r.servers).xray).jsonObject["outbounds"]!!.jsonArray
                    .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
                val ss = ob["streamSettings"]?.jsonObject ?: return@forEach
                if (ss["network"]?.jsonPrimitive?.content == "ws")
                    assertFalse(e.name, ss["tlsSettings"]?.jsonObject?.get("alpn")?.jsonArray?.any { it.jsonPrimitive.content == "h2" } == true)
            }
        } finally { CertPins.fetcher = null }
    }
}
