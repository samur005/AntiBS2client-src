package net.antibs.client.data

import net.antibs.client.engine.AppState
import net.antibs.client.engine.SubscriptionFetcher
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.18: HWID headers, settings and Remnawave HWID-limit handling. */
class HwidTest {
    private val dev = "0123456789abcdef0123456789abcdef"

    @Test fun headersWhenEnabled() {
        val h = SubscriptionFetcher.headersFor(dev, "14", "Pixel 8")
        assertEquals(
            linkedMapOf(
                "User-Agent" to SubscriptionFetcher.UA,
                "Accept" to "application/json, text/plain, */*",
                "x-hwid" to dev,
                "x-device-os" to "Windows",
                "x-ver-os" to "14",
                "x-device-model" to "Pixel 8",
            ),
            h,
        )
        assertEquals("unknown", Hwid.requestHeaders(dev, null, " ")["x-ver-os"])
    }

    @Test fun noHwidOrDeviceHeadersWhenDisabled() {
        val off = Hwid.effective(sendHwid = false, customHwid = "customHWID-12345", deviceId = dev, subOverride = "subHWID-123456")
        assertNull(off)
        val h = SubscriptionFetcher.headersFor(off, "14", "Pixel 8")
        assertEquals(setOf("User-Agent", "Accept"), h.keys)
        assertTrue(h.keys.none { it.lowercase().startsWith("x-") })
        assertEquals(listOf(false), Hwid.attempts("qwdtt", off))
        assertEquals(listOf(false), Hwid.attempts("remnawave", off))
    }

    @Test fun effectivePriority() {
        assertEquals(dev, Hwid.effective(true, "", dev))
        assertEquals("customHWID-12345", Hwid.effective(true, "customHWID-12345", dev))
        assertEquals("subHWID-123456", Hwid.effective(true, "customHWID-12345", dev, "subHWID-123456"))
        // invalid override / custom are skipped
        assertEquals(dev, Hwid.effective(true, "bad hwid!", dev, "short"))
    }

    @Test fun validation() {
        assertTrue(Hwid.isValid(dev))
        assertTrue(Hwid.isValid("UE42LJXu4DbiCaBv"))
        assertTrue(Hwid.isValid("abc=def-1234"))
        assertFalse(Hwid.isValid("short"))
        assertFalse(Hwid.isValid("has space 12345"))
        assertFalse(Hwid.isValid("a".repeat(65)))
        val g = Hwid.generate()
        assertTrue(Hwid.isValid(g))
        assertEquals(32, g.length)
    }

    @Test fun attemptOrder() {
        assertEquals(listOf(false, true), Hwid.attempts("qwdtt", dev))   // historical qWDTT order
        assertEquals(listOf(true, false), Hwid.attempts("remnawave", dev))
        assertEquals(listOf(true, false), Hwid.attempts("auto", dev))
    }

    @Test fun remnawaveLimitMessages() {
        val max = mapOf<String?, String?>("x-hwid-active" to "true", "x-hwid-max-devices-reached" to "true")
        assertTrue(Hwid.problemMessage(404, max, sent = true)!!.contains("лимит устройств"))
        assertTrue(Hwid.limitReached(mapOf("X-Hwid-Limit" to "true")))
        val notSupported = mapOf<String?, String?>("x-hwid-active" to "true", "x-hwid-not-supported" to "true")
        assertTrue(Hwid.problemMessage(404, notSupported, sent = false)!!.contains("Отправлять HWID"))
        assertTrue(Hwid.problemMessage(404, mapOf("x-hwid-active" to "true"), sent = false)!!.contains("Отправлять HWID"))
        assertNotNull(Hwid.problemMessage(404, mapOf("x-hwid-active" to "true"), sent = true))
        // Normal OK response with HWID limit enabled → no message.
        assertNull(Hwid.problemMessage(200, mapOf("x-hwid-active" to "true"), sent = true, serversEmpty = false))
        assertNull(Hwid.problemMessage(404, emptyMap(), sent = true))
    }

    @Test fun settingsSerializeAndDefaults() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString(AppState.serializer(), "{}")
        assertTrue(old.sendHwid)
        assertEquals("", old.customHwid)
        val st = AppState(sendHwid = false, customHwid = "customHWID-12345",
            subscriptions = listOf(Subscription(id = "s", url = "https://sub.example.com/x", hwidOverride = "subHWID-123456")))
        val back = json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), st))
        assertFalse(back.sendHwid)
        assertEquals("customHWID-12345", back.customHwid)
        assertEquals("subHWID-123456", back.subscriptions.single().hwidOverride)
    }
}
