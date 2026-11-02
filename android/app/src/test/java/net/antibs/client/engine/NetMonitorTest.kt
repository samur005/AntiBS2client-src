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
        assertTrue(a.homeNetMonitor && a.homeUrlTest)
        val j = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = j.decodeFromString(AdvancedSettings.serializer(), "{\"speedUnits\":\"bits\"}")
        assertTrue(old.homeNetMonitor && old.homeUrlTest)
    }
    @Test fun failedProbeIsMinusOne() {
        val c = okhttp3.OkHttpClient.Builder().connectTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        assertEquals(-1L, NetMonitor.probe(c, "http://127.0.0.1:1/"))
    }
}
