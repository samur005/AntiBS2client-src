package net.antibs.client.engine

import net.antibs.client.data.AdvancedSettings
import org.junit.Assert.*
import org.junit.Test

class NetMonitorTest {
    @Test fun tones() {
        assertEquals(NetMonitor.Tone.NONE, NetMonitor.tone(null))
        assertEquals(NetMonitor.Tone.BAD, NetMonitor.tone(-1))
        assertEquals(NetMonitor.Tone.GOOD, NetMonitor.tone(212))
        assertEquals(NetMonitor.Tone.MID, NetMonitor.tone(540))
        assertEquals(NetMonitor.Tone.BAD, NetMonitor.tone(861))
    }
    @Test fun labels() {
        assertEquals("—", NetMonitor.label(null))
        assertEquals("тайм-аут", NetMonitor.label(-1))
        assertEquals("184", NetMonitor.label(184))
    }
    @Test fun targets() {
        assertEquals(listOf("Google", "Telegram", "Запретgram", "YouTube"), NetMonitor.URL_TARGETS.map { it.title })
        assertTrue(NetMonitor.URL_TARGETS.all { it.url.startsWith("https://") })
        assertTrue(NetMonitor.VPN_URL.contains("gstatic"))
        assertTrue(NetMonitor.DIRECT_URL.contains("vk.ru"))
    }
    @Test fun defaultsOnAndOldJsonKeepsThemOn() {
        val a = AdvancedSettings()
        assertTrue(a.homeUrlTest && a.revokeNotify)
        val j = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = j.decodeFromString(AdvancedSettings.serializer(), "{\"speedUnits\":\"bits\"}")
        assertTrue(old.homeUrlTest && old.revokeNotify)
    }
    @Test fun healthOffWhenVpnOff() {
        assertEquals(NetMonitor.Health.OK, NetMonitor.health(false, false, 9, 9, false))
    }
    @Test fun healthNeedsTwoVpnFailures() {
        assertEquals(NetMonitor.Health.OK, NetMonitor.health(true, true, 1, 0, true))
        assertEquals(NetMonitor.Health.SERVER_DOWN, NetMonitor.health(true, true, 2, 0, true))
    }
    @Test fun healthNoInternetNeedsDirectFailAndNoValidatedNet() {
        assertEquals(NetMonitor.Health.NO_INTERNET, NetMonitor.health(true, true, 2, 1, false))
        assertEquals(NetMonitor.Health.NO_INTERNET, NetMonitor.health(true, false, 0, 2, false))   // while connecting
        assertEquals(NetMonitor.Health.SERVER_DOWN, NetMonitor.health(true, true, 3, 2, true))     // validated net → server
        assertEquals(NetMonitor.Health.OK, NetMonitor.health(true, true, 0, 0, false))
    }
    @Test fun failCounterResetsOnSuccess() {
        var f = 0
        f = NetMonitor.nextFails(f, false); f = NetMonitor.nextFails(f, false)
        assertEquals(2, f)
        assertEquals(0, NetMonitor.nextFails(f, true))
        assertEquals("Нет интернета", NetMonitor.healthText(NetMonitor.Health.NO_INTERNET))
        assertEquals("Сервер недоступен", NetMonitor.healthText(NetMonitor.Health.SERVER_DOWN))
        assertEquals(null, NetMonitor.healthText(NetMonitor.Health.OK))
    }
    @Test fun revokeHold() {
        assertTrue(net.antibs.client.service.RevokeGuard.holding(1000L, 900L))
        assertTrue(!net.antibs.client.service.RevokeGuard.holding(900L + net.antibs.client.service.RevokeGuard.HOLD_MS + 1, 900L))
        assertTrue(!net.antibs.client.service.RevokeGuard.holding(1000L, 0L))
    }
    @Test fun failedProbeIsMinusOne() {
        val c = okhttp3.OkHttpClient.Builder().connectTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        assertEquals(-1L, NetMonitor.probe(c, "http://127.0.0.1:1/"))
    }
}
