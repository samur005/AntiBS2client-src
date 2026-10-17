package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.41: olcRTC, OpenVPN, OpenConnect parsing + Xray plans (fake test keys only). */
class Release1041Test {
    private val key = "0123456789abcdef".repeat(4)

    private val ovpn = """
        # name: Test VPN
        client
        dev tun
        proto udp
        remote vpn.example.com 1194
        auth-user-pass
        script-security 2
        up /system/bin/sh
        management 0.0.0.0 7505
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIBfake
        -----END CERTIFICATE-----
        </ca>
        <tls-crypt>
        -----BEGIN OpenVPN Static key V1-----
        00ff
        -----END OpenVPN Static key V1-----
        </tls-crypt>
    """.trimIndent()

    @Test fun olcrtcRoundTrip() {
        val p = Olcrtc.parse("olcrtc://telemost?datachannel@room-123#$key\$Мой")
        assertEquals("olcrtc", p.protocol); assertEquals("room-123", p.server); assertEquals(key, p.password)
        assertNull(Olcrtc.validate(p))
        val back = Olcrtc.parse(Olcrtc.toUri(p))
        assertEquals(p.server, back.server); assertEquals(p.password, back.password); assertEquals(p.method, back.method); assertEquals(p.network, back.network)
        assertNotNull(Olcrtc.validate(p.copy(password = "short")))
        assertFalse(Olcrtc.clientJson(p, 11200).contains("\"$key\"x"))
    }

    @Test fun ovpnDetectAndSanitize() {
        assertTrue(OpenVpnConfig.looksLike(ovpn))
        assertFalse(OpenVpnConfig.looksLike("vless://a@b:1#x"))
        val s = OpenVpnConfig.summary(ovpn)
        assertEquals("vpn.example.com", s.remote); assertEquals(1194, s.port); assertEquals("udp", s.proto)
        assertTrue(s.needsAuth); assertFalse(s.keyEncrypted); assertTrue(s.errors.toString(), s.errors.isEmpty())
        val clean = OpenVpnConfig.sanitize(ovpn)
        assertFalse(clean.contains("script-security")); assertFalse(clean.contains("management 0.0.0.0")); assertFalse(clean.contains("/system/bin/sh"))
        assertTrue(clean.contains("<ca>") && clean.contains("MIIBfake") && clean.contains("</tls-crypt>"))
        val p = OpenVpnConfig.profile(ovpn, user = "u", pass = "p")
        assertEquals("openvpn", p.protocol); assertEquals("u", p.username)
        assertEquals(EngineKind.XRAY, EngineKind.XRAY)
    }

    @Test fun ovpnForm() {
        val f = OpenVpnConfig.Form(server = "1.2.3.4", port = 443, proto = "tcp", ca = "-----BEGIN CERTIFICATE-----\nx\n-----END CERTIFICATE-----", userPass = true, tlsCrypt = "k")
        assertTrue(OpenVpnConfig.formErrors(f).isEmpty())
        val t = OpenVpnConfig.build(f)
        assertTrue(OpenVpnConfig.looksLike(t))
        assertEquals("tcp", OpenVpnConfig.summary(t).proto)
        assertTrue(OpenVpnConfig.formErrors(f.copy(ca = "", userPass = false)).size >= 2)
    }

    @Test fun openConnectLinks() {
        val p = OpenConnectConfig.parse("openconnect://alice:s%40cret@vpn.example.org:8443/grp?protocol=gp&pin=pin-sha256:AAAA#Работа")
        assertEquals("https://vpn.example.org:8443/grp", p.server); assertEquals(8443, p.port)
        assertEquals("alice", p.username); assertEquals("s@cret", p.password)
        assertEquals("gp", p.olc!!["proto"]); assertEquals("Работа", p.remarks)
        assertEquals("vpn.example.org", OpenConnectConfig.host(p))
        assertEquals("pin-sha256:" + "A".repeat(43) + "=", OpenConnectConfig.normPin("A".repeat(43) + "="))
        val f = OpenConnectConfig.profile("vpn.example.org", "anyconnect", "bob", "", "", "", "123456", "", "")
        assertEquals("https://vpn.example.org", f.server); assertEquals(443, f.port); assertEquals("123456", f.olc!!["answers"])
    }

    @Test fun linkParserAndImport() {
        val r = ImportText.classify(ovpn, null)
        assertTrue(r.toString(), r is ImportOutcome.Servers)
        val e = (r as ImportOutcome.Servers).entries.single()
        assertEquals("openvpn", e.xray!!.protocol)
        assertEquals("openconnect", LinkParser.parseXray("openconnect://u@h.example#n")!!.protocol)
        assertEquals("olcrtc", LinkParser.parseXray("olcrtc://jitsi?datachannel@r#$key")!!.protocol)
    }

    @Test fun plansUseLoopbackSocks() {
        val ov = ServerEntry("o1", EngineKind.XRAY, "ov", xray = OpenVpnConfig.profile(ovpn))
        val oc = ServerEntry("c1", EngineKind.XRAY, "oc", xray = OpenConnectConfig.parse("openconnect://u:p@h.example#n"))
        val olc = ServerEntry("w1", EngineKind.XRAY, "olc", xray = Olcrtc.parse("olcrtc://jitsi?datachannel@r#$key"))
        val p1 = XrayConfigBuilder.plan(ov, listOf(ov))
        assertEquals(XrayConfigBuilder.VPN_BASE_PORT, p1.vpnInstances.single().second)
        assertTrue(p1.xray.contains("\"port\":${XrayConfigBuilder.VPN_BASE_PORT}") || p1.xray.contains("\"port\": ${XrayConfigBuilder.VPN_BASE_PORT}"))
        assertTrue(p1.engineLabel.contains("OpenVPN"))
        val p2 = XrayConfigBuilder.plan(oc, listOf(oc))
        assertTrue(p2.engineLabel.contains("OpenConnect"))
        val p3 = XrayConfigBuilder.plan(olc, listOf(olc))
        assertEquals(Olcrtc.BASE_PORT, p3.olcInstances.single().second)
        assertFalse(p3.xray.contains(key))
        assertFalse(p1.xray.contains("MIIBfake"))
    }
}
