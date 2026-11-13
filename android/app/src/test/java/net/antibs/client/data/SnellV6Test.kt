package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 2.0.45: Snell v6 runs on mihomo 1.19.34 (modes default / unshaped / unsafe-raw, reuse, no simple-obfs). */
class SnellV6Test {
    private val psk = "0123456789abcdef"

    private fun proxyOf(p: XrayProfile): JsonObject {
        val e = ServerEntry("s", EngineKind.XRAY, "S", xray = p)
        val plan = XrayConfigBuilder.plan(e, listOf(e))
        assertNotNull(plan.mihomo); assertNull(plan.singBox)
        return Json.parseToJsonElement(plan.mihomo!!).jsonObject["slots"]!!.jsonArray[0].jsonObject["proxy"]!!.jsonObject
    }

    @Test fun engineRouting() {
        fun eng(v: Int) = ProtocolSupport.engineOf(XrayProfile("n", "snell", "h", 1, password = psk, protoVersion = v))
        for (v in 1..3) assertEquals(ProtocolSupport.ENGINE_MIHOMO, eng(v))
        assertEquals(ProtocolSupport.ENGINE_SINGBOX, eng(4))
        assertEquals(ProtocolSupport.ENGINE_SINGBOX, eng(5))
        assertEquals(ProtocolSupport.ENGINE_MIHOMO, eng(6))
    }

    @Test fun uriV6WithModeAndReuse() {
        val p = LinkParser.parseXray("snell://$psk@s.example:9106?version=6&mode=unshaped&reuse=true&obfs=http&obfs-host=bing.com#S6")!!
        assertEquals(6, p.protoVersion); assertEquals("unshaped", p.snellMode); assertTrue(p.snellReuse)
        assertNull(p.headerType); assertNull(p.host) // v6 has no simple-obfs
        assertEquals("S6", p.remarks)
        val proxy = proxyOf(p)
        assertEquals("snell", proxy["type"]!!.jsonPrimitive.content)
        assertEquals(6, proxy["version"]!!.jsonPrimitive.int)
        assertEquals(psk, proxy["psk"]!!.jsonPrimitive.content)
        assertEquals("unshaped", proxy["mode"]!!.jsonPrimitive.content)
        assertEquals("true", proxy["reuse"]!!.jsonPrimitive.content)
        assertNull(proxy["obfs-opts"])
    }

    @Test fun defaultModeIsOmittedAndUnknownModeDropped() {
        val d = LinkParser.parseXray("snell://$psk@s.example:9106?version=6&mode=default")!!
        assertNull(d.snellMode)
        val proxy = proxyOf(d)
        assertNull(proxy["mode"]); assertNull(proxy["reuse"])
        assertNull(LinkParser.parseXray("snell://$psk@s.example:9106?version=6&mode=bogus")!!.snellMode)
        assertEquals("unsafe-raw", LinkParser.parseXray("snell://$psk@s.example:9106?version=6&mode=UNSAFE-RAW")!!.snellMode)
        // mode is v6-only
        assertNull(LinkParser.parseXray("snell://$psk@s.example:9106?version=3&mode=unshaped")!!.snellMode)
    }

    @Test fun v6PskLength() {
        assertFalse(ProtocolSupport.snellV6PskOk("short"))
        assertTrue(ProtocolSupport.snellV6PskOk("x".repeat(12)))
        assertTrue(ProtocolSupport.snellV6PskOk("x".repeat(255)))
        assertFalse(ProtocolSupport.snellV6PskOk("x".repeat(256)))
        val e = ServerEntry("s", EngineKind.XRAY, "S", xray = XrayProfile("n", "snell", "h", 1, password = "short", protoVersion = 6))
        try { MihomoConfigBuilder.proxy(e.xray!!); fail("short v6 PSK accepted") } catch (x: IllegalStateException) {
            assertTrue(x.message!!.contains("12"))
        }
    }

    @Test fun olderVersionsKeepObfs() {
        val p = LinkParser.parseXray("snell://psk@s.example:9102?version=3&obfs=tls&obfs-host=bing.com")!!
        val proxy = proxyOf(p)
        assertEquals("tls", proxy["obfs-opts"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertNull(proxy["mode"])
    }

    @Test fun surgeLine() {
        val e = LinkParser.parse("My Snell = snell, 1.2.3.4, 8443, psk=$psk, version=6, reuse=true, tfo=true")!!
        val p = e.xray!!
        assertEquals("My Snell", p.remarks); assertEquals("1.2.3.4", p.server); assertEquals(8443, p.port)
        assertEquals(psk, p.password); assertEquals(6, p.protoVersion); assertTrue(p.snellReuse)
        val v4 = LinkParser.parseXray("S4 = snell, h.example, 443, psk=abc, version=4, obfs=http, obfs-host=bing.com")!!
        assertEquals(4, v4.protoVersion); assertEquals("http", v4.headerType); assertEquals("bing.com", v4.host)
        assertNull(LinkParser.parseXray("X = ss, h, 443, encrypt-method=aes-128-gcm, password=p"))
        assertNull(LinkParser.parseXray("X = snell, h, 443, version=4")) // no PSK
    }

    @Test fun clashYamlBlockAndFlow() {
        val yaml = """
            mixed-port: 7890
            proxies:
              - name: "Snell v6"   # comment
                type: snell
                server: s6.example.com
                port: 44046
                psk: "$psk"
                version: 6
                mode: unsafe-raw
                reuse: true
              - {name: snell-v3, type: snell, server: s3.example.com, port: 443, psk: p3, version: 3, obfs-opts: {mode: tls, host: bing.com}}
              - name: snell-v4
                type: snell
                server: "[2001:db8::1]"
                port: 8443
                psk: p4
                version: 4
                obfs-opts:
                  mode: http
                  host: www.example.com
              - {name: vless-x, type: vless, server: v.example.com, port: 443, uuid: 00000000-0000-0000-0000-000000000000}
            proxy-groups:
              - name: auto
                type: select
                proxies: [Snell v6]
        """.trimIndent()
        val list = SnellFormats.clashYaml(yaml)
        assertEquals(3, list.size)
        val (v6, v3, v4) = list
        assertEquals("Snell v6", v6.remarks); assertEquals(6, v6.protoVersion); assertEquals("unsafe-raw", v6.snellMode); assertTrue(v6.snellReuse)
        assertEquals(psk, v6.password); assertEquals(44046, v6.port)
        assertEquals(3, v3.protoVersion); assertEquals("tls", v3.headerType); assertEquals("bing.com", v3.host)
        assertEquals("2001:db8::1", v4.server); assertEquals("http", v4.headerType); assertEquals("www.example.com", v4.host)

        val sub = SubscriptionParser.parse(yaml, "sub1")!!
        assertEquals(3, sub.servers.size)
        assertEquals(setOf("Snell v6", "snell-v3", "snell-v4"), sub.servers.map { it.name }.toSet())
        assertEquals(3, sub.servers.map { it.id }.toSet().size)
        val pasted = ImportText.classify(yaml) as ImportOutcome.Servers
        assertEquals(3, pasted.entries.size)
    }

    @Test fun clashYamlInlineList() {
        val list = SnellFormats.clashYaml("proxies: [{name: a, type: snell, server: h, port: 1, psk: '$psk', version: 6}]")
        assertEquals(1, list.size); assertEquals(6, list[0].protoVersion)
    }

    @Test fun label() {
        val p = XrayProfile("n", "snell", "h", 1, password = psk, protoVersion = 6)
        assertEquals("Snell v6", Format.protocolLabel(p))
        assertEquals("Snell v2", Format.protocolLabel(p.copy(protoVersion = 2)))
    }

    @Test fun oldStoredProfileStillLoads() {
        val j = Json { ignoreUnknownKeys = true }
        val p = j.decodeFromString(XrayProfile.serializer(), """{"remarks":"n","protocol":"snell","server":"h","port":1,"password":"p","protoVersion":6}""")
        assertNull(p.snellMode); assertFalse(p.snellReuse)
    }
}
