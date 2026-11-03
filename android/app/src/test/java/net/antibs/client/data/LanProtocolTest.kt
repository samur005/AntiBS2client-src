package net.antibs.client.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LanProtocolTest {
    /** Exact shape of ABS 3.1.27 Subscription.toJson(). */
    private val absSub = """{"id":"7f1c","name":"Мой VPN","url":"https://panel.example/sub/abc","used":1048576,"total":10737418240,
        "expire":1798761600,"lastUpdated":1730000000000,"title":"Мой VPN","interval":12,"updateAlways":false,
        "supportUrl":"https://t.me/sup","webPageUrl":"https://panel.example","announce":"hi","announceUrl":"",
        "routing":"happ://routing/x","hideSettings":false,"deviceLimit":false,"hwidActive":true,"lastCount":5,"lastReceived":5,"lastSkipped":0}"""

    @Test fun absJsonMapsToOurs() {
        val s = LanProtocol.fromAbs(Json.parseToJsonElement(absSub).jsonObject, "sub-1")!!
        assertEquals("https://panel.example/sub/abc", s.url); assertEquals("Мой VPN", s.title)
        assertEquals(1048576L, s.download); assertEquals(10737418240L, s.total); assertEquals(1798761600L, s.expireEpoch)
        assertEquals(12, s.updateIntervalHours); assertEquals("https://t.me/sup", s.supportUrl); assertEquals("happ://routing/x", s.routing)
    }

    @Test fun oursToAbsHasAbsFieldNamesAndRoundTrips() {
        val ours = Subscription(id = "sub-x", url = "https://a.b/s", title = "T", upload = 5, download = 10, total = 100, expireEpoch = 9, updateIntervalHours = 6)
        val o = LanProtocol.toAbs(ours)
        listOf("id","name","url","used","total","expire","lastUpdated","title","interval","updateAlways","supportUrl","webPageUrl",
            "announce","announceUrl","routing","hideSettings","deviceLimit","hwidActive","lastCount","lastReceived","lastSkipped")
            .forEach { assertTrue(it, o.containsKey(it)) }
        assertEquals(15L, o["used"]!!.jsonPrimitive.long)
        val back = LanProtocol.fromAbs(o, "n")!!
        assertEquals(ours.url, back.url); assertEquals("T", back.title); assertEquals(6, back.updateIntervalHours)
    }

    @Test fun nonHttpEntryDropped() {
        assertNull(LanProtocol.fromAbs(buildJsonObject { put("url", "vless://x") }, "a"))
    }

    @Test fun plainBodyDecodes() {
        val body = """{"code":"123456","subs":[$absSub]}"""
        val r = LanProtocol.decode(body, "123456", null) as LanProtocol.Incoming.Ok
        assertEquals(1, r.subs.size); assertFalse(r.sealed)
        val w = LanProtocol.decode(body, "654321", null) as LanProtocol.Incoming.Rejected
        assertTrue(w.wrongCode)
    }

    /** Replica of ABS LanQr.seal: AES/GCM/NoPadding, 128-bit tag, AAD "abs-lan:1", b64url no padding. */
    private fun absSeal(key: ByteArray, iv: ByteArray, plain: String): String {
        val e = java.util.Base64.getUrlEncoder().withoutPadding()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        c.updateAAD("abs-lan:1".toByteArray())
        return """{"v":2,"iv":"${e.encodeToString(iv)}","ct":"${e.encodeToString(c.doFinal(plain.toByteArray()))}"}"""
    }

    @Test fun aesGcmCompatibleWithAbs() {
        val key = ByteArray(32) { it.toByte() }; val iv = ByteArray(12) { (it + 7).toByte() }
        val plain = """{"code":"111222","subs":[$absSub]}"""
        val abs = absSeal(key, iv, plain)
        assertEquals(abs, LanProtocol.seal(key, plain, iv))   // byte-identical to ABS
        assertEquals(plain, LanProtocol.open(key, Json.parseToJsonElement(abs).jsonObject))
        val s = LanProtocol.Session("192.168.1.2", 45571, "111222", key, Long.MAX_VALUE)
        val r = LanProtocol.decode(abs, "111222", s) as LanProtocol.Incoming.Ok
        assertTrue(r.sealed)
        val other = LanProtocol.Session("192.168.1.2", 45571, "111222", ByteArray(32), Long.MAX_VALUE)
        assertEquals("bad key", (LanProtocol.decode(abs, "111222", other) as LanProtocol.Incoming.Rejected).reason)
        val old = LanProtocol.Session("192.168.1.2", 45571, "111222", key, 10)
        assertEquals("expired", (LanProtocol.decode(abs, "111222", old, nowSec = 11) as LanProtocol.Incoming.Rejected).reason)
    }

    @Test fun qrPayloadRoundTripAndValidation() {
        val s = LanProtocol.newSession("192.168.43.1", "123456", nowSec = 1000)
        val p = LanProtocol.payload(s)
        assertTrue(p.startsWith("abs-lan:1?h=192.168.43.1&p=45571&c=123456&k=")); assertTrue(p.endsWith("&e=1600"))
        val q = LanProtocol.parseQr(p)!!
        assertArrayEquals(s.key, q.key); assertEquals(45571, q.port); assertEquals(1600L, q.expires)
        assertNull(LanProtocol.parseQr("abs-lan:1?h=1.2.3.4;rm&p=45571&c=1&k=${p.substringAfter("k=").substringBefore('&')}&e=1"))
        assertNull(LanProtocol.parseQr(p.replace("p=45571", "p=70000")))
        assertNull(LanProtocol.parseQr("abs-lan:1?h=1.2.3.4&p=45571&c=1&k=AAAA&e=1"))
        assertNull(LanProtocol.parseQr("https://x"))
    }

    @Test fun ipPicking() {
        assertEquals("192.168.43.1", LanProtocol.pickLanIp(listOf("wlan0" to "192.168.1.5", "ap0" to "192.168.43.1", "tun0" to "10.10.0.2")))
        assertEquals("192.168.1.5", LanProtocol.pickLanIp(listOf("rmnet0" to "10.1.2.3", "wlan0" to "192.168.1.5")))
        assertNull(LanProtocol.pickLanIp(listOf("tun0" to "10.0.0.2", "rmnet0" to "100.64.1.1")))
        assertFalse(LanProtocol.isLanIface("rmnet_data0")); assertFalse(LanProtocol.isLanIface("ccmni0"))
        assertTrue(LanProtocol.isLanIface("wlan0")); assertTrue(LanProtocol.isLanIface("swlan0"))
    }

    @Test fun discoveryAndAck() {
        assertEquals("Pixel 8", LanProtocol.parseReply("ABS_HERE_V1|Pixel 8"))
        assertEquals("ABS", LanProtocol.parseReply("ABS_HERE_V1|"))
        assertNull(LanProtocol.parseReply("HELLO"))
        assertEquals("ABS_HERE_V1|" + "x".repeat(60), LanProtocol.reply("x".repeat(80)))
        assertEquals(true to 3, LanProtocol.parseAck(LanProtocol.ack(3)))
        assertEquals(false, LanProtocol.parseAck(LanProtocol.nack("code"))!!.first)
        assertNull(LanProtocol.parseAck(""))
        assertEquals("10.0.0.5" to 45571, LanProtocol.parseAddress("10.0.0.5"))
        assertEquals("10.0.0.5" to 1234, LanProtocol.parseAddress("10.0.0.5:1234"))
        assertNull(LanProtocol.parseAddress("10.0.0.5:abc"))
    }

    @Test fun pingThresholds() {
        assertEquals(PingLevel.Level.NONE, PingLevel.of(null))
        assertEquals(PingLevel.Level.BAD, PingLevel.of(-1))
        assertEquals(PingLevel.Level.EXCELLENT, PingLevel.of(0)); assertEquals(PingLevel.Level.EXCELLENT, PingLevel.of(99))
        assertEquals(PingLevel.Level.GOOD, PingLevel.of(100)); assertEquals(PingLevel.Level.GOOD, PingLevel.of(199))
        assertEquals(PingLevel.Level.MID, PingLevel.of(200)); assertEquals(PingLevel.Level.MID, PingLevel.of(299))
        assertEquals(PingLevel.Level.BAD, PingLevel.of(300)); assertEquals(PingLevel.Level.BAD, PingLevel.of(5000))
        assertNotEquals(PingLevel.EXCELLENT, PingLevel.GOOD)
    }
}
