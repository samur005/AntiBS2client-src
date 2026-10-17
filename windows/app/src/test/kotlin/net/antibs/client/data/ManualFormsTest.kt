package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFormsTest {
    private val k1 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val k2 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val k3 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test fun amneziaWgFormEqualsImport() {
        val awg = mapOf("jc" to "4", "jmin" to "40", "jmax" to "70", "s1" to "15", "s2" to "68", "h1" to "100-200", "h2" to "2", "h3" to "3", "h4" to "4", "i1" to "<b 0xf6ab>")
        val form = ManualForms.amneziaWg("AWG", "awg.example", 51820, k1, k2, k3, "10.8.0.2/32", "1280", awg + ("s3" to " "))
        val link = "amneziawg://${enc(k1)}@awg.example:51820?publickey=${enc(k2)}&presharedkey=${enc(k3)}&address=${enc("10.8.0.2/32")}&mtu=1280&" +
            awg.entries.joinToString("&") { "${it.key}=${enc(it.value)}" } + "#AWG"
        assertEquals(LinkParser.parseAmneziaWg(link), form)
        assertTrue(ManualForms.amneziaWgErrors("awg.example", "51820", k1, k2, k3, "10.8.0.2/32", "1280", awg).isEmpty())
        // runs through mihomo with the amnezia options
        assertTrue(ProtocolSupport.isMihomo(form.protocol))
    }

    @Test fun amneziaWgValidation() {
        val e = ManualForms.amneziaWgErrors("", "0", "short", k2, "bad", "nope", "9000", mapOf("jc" to "x", "h1" to "a-b", "jmin" to "80", "jmax" to "10"))
        assertEquals(listOf("адрес", "порт", "приватный ключ", "PSK", "адрес интерфейса", "MTU", "jc", "h1", "jmax"), e)
        assertTrue(ManualForms.isAddressList("10.8.0.2/32, fd00::2/128"))
        assertFalse(ManualForms.isAddressList(""))
        assertTrue(ManualForms.isWgKey(k1)); assertFalse(ManualForms.isWgKey("abc"))
    }

    @Test fun qwdttFormEqualsImport() {
        val form = ManualForms.qwdtt("🇳🇱 NL", "nl.example.com:56000", "h1, h2  h3", "12", "9100", "pw")
        val uri = "qwdtt://config?name=${enc("🇳🇱 NL")}&peer=${enc("nl.example.com:56000")}&hashes=h1,h2,h3&workers=12&port=9100&pass=pw"
        assertEquals(LinkParser.parseQwdttUri(uri), form)
        val d = ManualForms.qwdtt("", "p.example:1", "", "", "", "x")
        assertEquals(QwdttProfile("qWDTT", "p.example:1", "", 9, 9000, "x"), d)
        assertTrue(ManualForms.qwdttErrors("nl.example.com:56000", "9", "9000", "pw").isEmpty())
        assertEquals(listOf("peer (хост:порт)", "пароль", "потоки", "локальный порт"), ManualForms.qwdttErrors("nl.example.com", "0", "80", ""))
    }

    @Test fun amneziaKeyParsesLikeImport() {
        val conf = "[Interface]\nPrivateKey = $k1\nAddress = 10.8.1.2/32\nJc = 3\nJmin = 10\nJmax = 50\nH1 = 11\n\n[Peer]\nPublicKey = $k2\nEndpoint = 203.0.113.5:40000\nAllowedIPs = 0.0.0.0/0\n"
        val last = kotlinx.serialization.json.buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive(conf)) }.toString()
        val root = kotlinx.serialization.json.buildJsonObject {
            put("hostName", kotlinx.serialization.json.JsonPrimitive("203.0.113.5"))
            put("defaultContainer", kotlinx.serialization.json.JsonPrimitive("amnezia-awg"))
            put("containers", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("container", kotlinx.serialization.json.JsonPrimitive("amnezia-awg"))
                    put("awg", kotlinx.serialization.json.buildJsonObject { put("last_config", kotlinx.serialization.json.JsonPrimitive(last)) })
                })
            })
        }.toString().toByteArray()
        val z = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(root) } }.toByteArray()
        val payload = java.nio.ByteBuffer.allocate(4 + z.size).putInt(root.size).put(z).array()
        val key = "vpn://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val p = ManualForms.amneziaKey(key, "Мой AWG")
        assertNotNull(p)
        assertEquals("amneziawg", p!!.protocol)
        assertEquals("203.0.113.5", p.server); assertEquals(40000, p.port)
        assertEquals(LinkParser.parseAmneziaVpn(key)!!.copy(remarks = "Мой AWG"), p)
        assertNull(ManualForms.amneziaKey("vless://x"))
        assertNull(ManualForms.amneziaKey("vpn://!!!"))
    }
}
