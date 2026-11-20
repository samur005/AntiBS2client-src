package net.antibs.client.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WireGuardImportTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val pub = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 50).toByte() })
    private fun conf(extra: String = "") = """
        # synthetic keys only
        [Interface]
        PrivateKey = $key
        Address = 10.8.0.2/32, fd00::2/128
        DNS = 1.1.1.1, 2606:4700:4700::1111
        MTU = 1280
        $extra
        [Peer]
        PublicKey = $pub
        PresharedKey = $key
        Endpoint = [2001:db8::1]:51820
        AllowedIPs = 10.0.0.0/8, ::/0
        PersistentKeepalive = 25
    """.trimIndent()
    private fun imported(text: String, fileName: String? = null): XrayProfile {
        val r = ImportText.classify(text, "manual", fileName)
        assertTrue((r as? ImportOutcome.Failed)?.message ?: "not a server", r is ImportOutcome.Servers)
        return (r as ImportOutcome.Servers).entries.single().xray!!
    }
    private fun v3() = """
        S1 = 20
        S2 = 20
        S3 = 20
        S4 = 20
        HeaderProtectionKey = $key
        ContentPaddingAddition = 1-16
        RekeyAfterTime = 120-130
        RekeyTimeout = 5-6
        RejectAfterTime = 180-190
        KeepaliveTimeout = 10-12
        MaxHandshakeAttempts = 18-20
    """.trimIndent()
    @Test fun clipboardWG() {
        val p = imported("\uFEFF" + conf().replace("\n", "\r\n"))
        assertEquals("wireguard", p.protocol); assertEquals("2001:db8::1", p.server); assertEquals(51820, p.port)
        assertEquals(key, p.secretKey); assertEquals(pub, p.publicKey); assertEquals(key, p.preSharedKey)
        assertEquals("10.8.0.2/32,fd00::2/128", p.localAddress); assertEquals(25, p.wgKeepAlive)
        assertEquals(listOf("10.0.0.0/8", "::/0"), p.wgAllowedIps)
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), p.wgDns)
        val e = ServerEntry("wg", EngineKind.XRAY, "wg", xray = p)
        val plan = XrayConfigBuilder.plan(e, listOf(e))
        val o = Json.parseToJsonElement(plan.xray).jsonObject["outbounds"]!!.jsonArray.first().jsonObject
        val peer = o["settings"]!!.jsonObject["peers"]!!.jsonArray.single().jsonObject
        assertEquals("25", peer["keepAlive"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("[\"10.0.0.0/8\",\"::/0\"]"), peer["allowedIPs"])
    }
    @Test fun downloadedFileWG() {
        val t = ImportText.readConfigFile(conf().byteInputStream())
        assertEquals("test-client", imported(t, "test-client.conf").remarks)
        assertEquals(imported(conf()).secretKey, imported(t).secretKey)
    }
    @Test fun awg15LegacyAnd20Ranges() {
        val p = imported(conf("Jc = 4\nJmin = 10\nJmax = 20\nH1 = 11-20\nI1 = <r 16>\nJ1 = <r 16>\nItime = 10"))
        val o = MihomoConfigBuilder.proxy(p)["amnezia-wg-option"]!!.jsonObject
        assertNull(o["version"]); assertEquals("<r 16>", o["j1"]!!.jsonPrimitive.content)
        assertEquals("10", o["itime"]!!.jsonPrimitive.content); assertEquals("11-20", o["h1"]!!.jsonPrimitive.content)
        val o2 = MihomoConfigBuilder.proxy(imported(conf("S3 = 12\nS4 = 14\nH1 = 11-20\nI1 = <r 16>")))["amnezia-wg-option"]!!.jsonObject
        assertNull(o2["version"]); assertEquals("12", o2["s3"]!!.jsonPrimitive.content)
    }
    @Test fun awg30And31ReachV3Runtime() {
        for (tail in listOf("", "\nRandomTrailers = true\nDisableCookies = 1")) {
            val p = imported(conf(v3() + tail))
            assertEquals("amneziawg", p.protocol); assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineOf(p))
            val o = MihomoConfigBuilder.proxy(p)
            assertEquals("25", o["persistent-keepalive"]!!.jsonPrimitive.content)
            val a = o["amnezia-wg-option"]!!.jsonObject
            assertEquals("3", a["version"]!!.jsonPrimitive.content); assertEquals(key, a["header-protection-key"]!!.jsonPrimitive.content)
            assertEquals("1-16", a["content-padding-addition"]!!.jsonPrimitive.content)
            for (k in listOf("rekey-after-time", "rekey-timeout", "reject-after-time", "keepalive-timeout", "max-handshake-attempts")) assertNotNull(a[k])
            if (tail.isNotEmpty()) { assertTrue(a["random-trailers"]!!.jsonPrimitive.boolean); assertTrue(a["disable-cookies"]!!.jsonPrimitive.boolean) }
        }
    }
    @Test fun advancedShareLink() {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val p = LinkParser.parseAmneziaWg("awg://${enc(key)}@wg.example:51820?publickey=${enc(pub)}&address=10.8.0.2/32&s1=20&s2=20&s3=20&s4=20&headerprotectionkey=${enc(key)}&randomtrailers=true")!!
        val o = MihomoConfigBuilder.proxy(p)["amnezia-wg-option"]!!.jsonObject
        assertEquals("3", o["version"]!!.jsonPrimitive.content); assertTrue(o["random-trailers"]!!.jsonPrimitive.boolean)
    }
    @Test fun invalidConfigsAreNotPartialImports() {
        val bad = listOf(conf() + "\n[Peer]\nPublicKey = $pub", conf().replace("Address =", "Unknown ="),
            conf().replace(key, "not-a-key"), conf().replace(":51820", ":0"), conf("PostUp = touch /tmp/no"),
            conf("HeaderProtectionKey = $key"), conf(v3()+"\nJ1 = <r 16>"), conf(v3()+"\nRandomTrailers = maybe"),
            conf().replace("PersistentKeepalive = 25", "PersistentKeepalive = 20-30"), conf("Jc = wrong"))
        for (s in bad) {
            val r = ImportText.classify(s)
            assertTrue("must reject malformed / unsupported whole config", r is ImportOutcome.Failed)
            assertFalse((r as ImportOutcome.Failed).message.contains(key))
        }
    }
    @Test fun repeatedAddressesAllowedIPsAndComment() {
        val p = imported(conf().replace("10.8.0.2/32, fd00::2/128", "10.8.0.2/32 # local\nAddress = fd00::2/128").replace("10.0.0.0/8, ::/0", "10.0.0.0/8\nAllowedIPs = ::/0"))
        assertEquals("10.8.0.2/32,fd00::2/128", p.localAddress); assertEquals(2, p.wgAllowedIps!!.size)
    }
    @Test fun boundedFileReadAndInvalidUTF8() {
        try { ImportText.readConfigFile(ByteArray(1024*1024+1).inputStream()); fail("oversized") } catch (_: IllegalArgumentException) {}
        try { ImportText.readConfigFile(byteArrayOf(0xFF.toByte()).inputStream()); fail("encoding") } catch (_: java.nio.charset.CharacterCodingException) {}
    }
}
