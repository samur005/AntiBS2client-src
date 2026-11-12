package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.engine.AppState
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.AutoPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Random

/** 2.0.43: leak hardening — bind block, SOCKS login/password/port, hev config, tunnel DNS, «.RU» via a server. */
class LeakHardeningTest {
    private fun cfg(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun inbounds(c: JsonObject) = c["inbounds"]!!.jsonArray.map { it.jsonObject }
    private fun outbounds(c: JsonObject) = c["outbounds"]!!.jsonArray.map { it.jsonObject }
    private fun rules(c: JsonObject) = c["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun tag(o: JsonObject) = o["tag"]?.jsonPrimitive?.content
    private val vless = XrayProfile("V", "vless", "v.example", 443, id = "11111111-1111-1111-1111-111111111111", network = "ws",
        security = "tls", sni = "v.example", path = "/ws")
    private val ruNode = XrayProfile("RU", "vless", "ru.example", 443, id = "22222222-2222-2222-2222-222222222222", network = "ws",
        security = "tls", sni = "ru.example", path = "/ws")

    // ───────── migration ─────────

    @Test fun freshDefaultsBlockBypass() {
        assertTrue(AdvancedSettings().tunnelBindBlock)
        assertEquals(0, AdvancedSettings().hardening)
    }

    @Test fun migrationFlipsBindBlockAndGeneratesCredentialsOnce() {
        val old = AdvancedSettings(tunnelBindBlock = false, socksPort = 10808)
        val m = LocalProxy.migrate(old, Random(1)) { true }
        assertTrue(m.tunnelBindBlock)
        assertEquals(LocalProxy.HARDENING_VERSION, m.hardening)
        assertTrue(LocalProxy.hasAuth(m))
        assertTrue(LocalProxy.validCred(m.socksUser)); assertTrue(LocalProxy.validCred(m.socksPass))
        assertTrue(m.socksPass.length >= 20)
        assertNotEquals(10808, m.socksPort)
        assertTrue(m.socksPort in LocalProxy.PORT_MIN..LocalProxy.PORT_MAX)
        // later user choice is respected: running the migration again changes nothing
        val userOff = m.copy(tunnelBindBlock = false, socksPort = 10808, socksUser = "me", socksPass = "secret")
        assertSame(userOff, LocalProxy.migrate(userOff, Random(2)) { true })
    }

    @Test fun migrationKeepsOwnPortAndCredentials() {
        val m = LocalProxy.migrate(AdvancedSettings(socksPort = 20808, socksUser = "a", socksPass = "b"), Random(3)) { true }
        assertEquals(20808, m.socksPort); assertEquals("a", m.socksUser); assertEquals("b", m.socksPass)
        assertTrue(m.tunnelBindBlock)
    }

    @Test fun newPortSkipsBusyPorts() {
        var calls = 0
        val q = LocalProxy.newPort(Random(6)) { calls++; calls >= 3 }
        assertEquals(3, calls); assertTrue(q in LocalProxy.PORT_MIN..LocalProxy.PORT_MAX)
    }

    @Test fun regenerateChangesEverything() {
        val a = AdvancedSettings(socksUser = "u1", socksPass = "p1", socksPort = 30000)
        val r = LocalProxy.regenerate(a, Random(7)) { true }
        assertNotEquals("u1", r.socksUser); assertNotEquals("p1", r.socksPass); assertNotEquals(30000, r.socksPort)
    }

    @Test fun appStateHardenedBumpsRevOnlyOnce() {
        val s = AppState(rev = 5, adv = AdvancedSettings(tunnelBindBlock = false))
        val h = AppStore.hardened(s)
        assertEquals(6, h.rev); assertTrue(h.adv.tunnelBindBlock); assertTrue(LocalProxy.hasAuth(h.adv))
        assertSame(h, AppStore.hardened(h))
    }

    @Test fun oldJsonWithoutMarkerIsMigrated() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString(AdvancedSettings.serializer(), """{"socksPort":10808,"tunnelBindBlock":false}""")
        assertEquals(0, old.hardening)
        assertFalse(old.tunnelBindBlock)
        assertTrue(LocalProxy.migrate(old, Random(8)) { true }.tunnelBindBlock)
    }

    @Test fun validCredRules() {
        assertTrue(LocalProxy.validCred("uAbc123"))
        assertFalse(LocalProxy.validCred(""))
        assertFalse(LocalProxy.validCred("a b"))
        assertFalse(LocalProxy.validCred("a:b"))
        assertFalse(LocalProxy.validCred("x".repeat(256)))
    }

    @Test fun authenticatorAnswersOnlyLoopbackSocks() {
        val lo = InetAddress.getByName("127.0.0.1")
        assertTrue(LocalProxy.answers("SOCKS5", "127.0.0.1", lo))
        assertFalse(LocalProxy.answers("http", "127.0.0.1", lo))
        assertFalse(LocalProxy.answers("SOCKS5", "example.com", InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))))
    }

    // ───────── Xray / sing-box / hev configs ─────────

    @Test fun socksAndHttpInboundsRequirePassword() {
        val adv = AdvancedSettings(httpInbound = true, httpPort = 20809)
        val c = cfg(XrayConfigBuilder.forProfile(vless, XrayConfigBuilder.Options(socksPort = 31000, socksUser = "u", socksPass = "p", adv = adv)))
        val socks = inbounds(c).first { tag(it) == "socks" }
        assertEquals(31000, socks["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("127.0.0.1", socks["listen"]!!.jsonPrimitive.content)
        val st = socks["settings"]!!.jsonObject
        assertEquals("password", st["auth"]!!.jsonPrimitive.content)
        val acc = st["accounts"]!!.jsonArray[0].jsonObject
        assertEquals("u", acc["user"]!!.jsonPrimitive.content); assertEquals("p", acc["pass"]!!.jsonPrimitive.content)
        val http = inbounds(c).first { tag(it) == "http" }["settings"]!!.jsonObject
        assertEquals("u", http["accounts"]!!.jsonArray[0].jsonObject["user"]!!.jsonPrimitive.content)
    }

    @Test fun noCredentialsKeepsNoauth() {
        val c = cfg(XrayConfigBuilder.forProfile(vless, XrayConfigBuilder.Options()))
        assertEquals("noauth", inbounds(c).first { tag(it) == "socks" }["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
    }

    @Test fun lanListenStillAuthenticated() {
        val c = cfg(XrayConfigBuilder.forProfile(vless, XrayConfigBuilder.Options(allowLan = true, socksUser = "u", socksPass = "p")))
        val socks = inbounds(c).first { tag(it) == "socks" }
        assertEquals("0.0.0.0", socks["listen"]!!.jsonPrimitive.content)
        assertEquals("password", socks["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
    }

    @Test fun singBoxLoopbackUsesCredentials() {
        val slots = listOf(SingBoxConfigBuilder.Slot(0, XrayProfile("H", "anytls", "h.example", 443, password = "x", sni = "h.example", security = "tls"), bridgePort = 10951))
        val sb = SingBoxConfigBuilder.fullConfig(slots, user = "u", pass = "p")
        val inb = sb["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("u", inb["users"]!!.jsonArray[0].jsonObject["username"]!!.jsonPrimitive.content)
        val bridge = sb["outbounds"]!!.jsonArray.map { it.jsonObject }.first { tag(it) == "bridge-0" }
        assertEquals("p", bridge["password"]!!.jsonPrimitive.content)
        val hop = XrayConfigBuilder.singBoxHop("proxy", 10900, "u", "p")["settings"]!!.jsonObject
        assertEquals("u", hop["user"]!!.jsonPrimitive.content); assertEquals("p", hop["pass"]!!.jsonPrimitive.content)
        assertNull(XrayConfigBuilder.singBoxHop("proxy", 10900)["settings"]!!.jsonObject["user"])
    }

    @Test fun autoPinProbesUseCredentials() {
        val base = """{"inbounds":[{"tag":"socks","port":30000,"protocol":"socks"}],"outbounds":[{"tag":"proxy","protocol":"freedom"}],"routing":{"rules":[]}}"""
        val out = cfg(AutoPin.withProbes(base, 47000, mapOf("proxy" to 47001), "u", "p"))
        val probe = inbounds(out).first { tag(it) == "probe-proxy" }["settings"]!!.jsonObject
        assertEquals("password", probe["auth"]!!.jsonPrimitive.content)
        assertEquals("p", probe["accounts"]!!.jsonArray[0].jsonObject["pass"]!!.jsonPrimitive.content)
        val open = cfg(AutoPin.withProbes(base, 47000, mapOf("proxy" to 47001)))
        assertEquals("noauth", inbounds(open).first { tag(it) == "probe-proxy" }["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
    }

    @Test fun hevConfigAuthAndIcmpOff() {
        val y = HevConfig.build(31000, 1500, "10.10.14.1", "fc00::10:10:14:1", "u'x", "p")
        assertTrue(y.contains("  icmp: 'off'\n"))
        assertTrue(y.contains("  port: 31000\n"))
        assertTrue(y.contains("  username: 'u''x'\n"))
        assertTrue(y.contains("  password: 'p'\n"))
        val noAuth = HevConfig.build(31000, 1500, "10.10.14.1", null)
        assertFalse(noAuth.contains("username")); assertTrue(noAuth.contains("icmp: 'off'"))
    }

    // ───────── tunnel DNS ─────────

    @Test fun tunDnsUsesUnderlyingResolvers() {
        assertEquals(listOf("10.10.0.1", "2a00:1fa0::53"), TunDns.forTun(listOf("10.10.0.1", "2a00:1fa0::53", "10.10.0.1")))
        assertEquals(listOf("192.168.1.1"), TunDns.forTun(listOf("fe80::1%wlan0", "127.0.0.1", "192.168.1.1")))
        assertEquals(TunDns.FALLBACK, TunDns.forTun(emptyList()))
        assertEquals(TunDns.FALLBACK, TunDns.forTun(listOf("::1", "999.1.1.1", "bogus")))
    }

    // ───────── «.RU без VPN» via a server ─────────

    private fun entry(id: String, p: XrayProfile) = ServerEntry(id = id, engine = EngineKind.XRAY, name = p.remarks, xray = p)

    @Test fun ruTrafficDirectByDefault() {
        val main = entry("m", vless)
        val plan = XrayConfigBuilder.plan(main, listOf(main), XrayConfigBuilder.Options(adv = AdvancedSettings(trafficPreset = TrafficPresets.RU)))
        val c = cfg(plan.xray)
        assertTrue(outbounds(c).none { tag(it) == XrayConfigBuilder.RU_VIA_TAG })
        assertTrue(rules(c).any { r -> r["outboundTag"]?.jsonPrimitive?.content == "direct" && r["domain"]?.toString()?.contains("domain:ru") == true })
    }

    @Test fun ruTrafficViaChosenServer() {
        val main = entry("m", vless); val ru = entry("ru", ruNode)
        val adv = AdvancedSettings(trafficPreset = TrafficPresets.RU, ruVia = "ru")
        val c = cfg(XrayConfigBuilder.plan(main, listOf(main, ru), XrayConfigBuilder.Options(adv = adv)).xray)
        val via = outbounds(c).first { tag(it) == XrayConfigBuilder.RU_VIA_TAG }
        assertTrue(via.toString().contains("ru.example"))
        val r = rules(c)
        assertTrue(r.any { it["outboundTag"]?.jsonPrimitive?.content == XrayConfigBuilder.RU_VIA_TAG && it["domain"]?.toString()?.contains("domain:ru") == true })
        assertTrue(r.any { it["outboundTag"]?.jsonPrimitive?.content == XrayConfigBuilder.RU_VIA_TAG && it["ip"]?.toString()?.contains("geoip:ru") == true })
        // LAN stays direct, and no RU rule goes to freedom any more
        assertTrue(r.any { it["outboundTag"]?.jsonPrimitive?.content == "direct" && it["ip"]?.toString()?.contains("geoip:private") == true })
        assertFalse(r.any { it["outboundTag"]?.jsonPrimitive?.content == "direct" && it["domain"]?.toString()?.contains("domain:ru") == true })
        // RU DNS server reached through the chosen server as well
        assertTrue(r.any { it["ip"]?.toString()?.contains(TrafficPresets.DEFAULT_RU_DNS) == true && it["outboundTag"]?.jsonPrimitive?.content == XrayConfigBuilder.RU_VIA_TAG })
    }

    @Test fun ruViaMissingServerFallsBackToDirect() {
        val main = entry("m", vless)
        val adv = AdvancedSettings(trafficPreset = TrafficPresets.RU, ruVia = "gone")
        val c = cfg(XrayConfigBuilder.plan(main, listOf(main), XrayConfigBuilder.Options(adv = adv)).xray)
        assertTrue(outbounds(c).none { tag(it) == XrayConfigBuilder.RU_VIA_TAG })
    }

    @Test fun ruViaIgnoredForOtherPresets() {
        val main = entry("m", vless); val ru = entry("ru", ruNode)
        val adv = AdvancedSettings(trafficPreset = TrafficPresets.AI, ruVia = "ru")
        val c = cfg(XrayConfigBuilder.plan(main, listOf(main, ru), XrayConfigBuilder.Options(adv = adv)).xray)
        assertTrue(outbounds(c).none { tag(it) == XrayConfigBuilder.RU_VIA_TAG })
    }

    @Test fun ruViaCandidatesAreSingleServers() {
        assertTrue(TrafficPresets.ruViaCandidate(entry("ru", ruNode)))
        assertFalse(TrafficPresets.ruViaCandidate(entry("c", vless.copy(protocol = "chain"))))
    }
}
