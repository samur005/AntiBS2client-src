package net.antibs.client.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSpeedTest {

    @Test fun rateMbpsFormula() {
        // 1_000_000 bytes in 1 s = 8 Mbit/s
        assertEquals(8.0, SessionSpeed.rateMbps(1_000_000, 1_000), 1e-9)
        assertEquals(0.0, SessionSpeed.rateMbps(0, 1_000), 0.0)
        assertEquals(0.0, SessionSpeed.rateMbps(1000, 0), 0.0)
        // 25 MB over 8 s
        assertEquals(25.0, SessionSpeed.rateMbps(25_000_000, 8_000), 1e-9)
    }

    @Test fun warmupIsExcludedFromRate() {
        // 2 MB landed in the first 1.5 s, then 8 MB more in the next 0.5 s
        val rate = SessionSpeed.rateAfterWarmup(totalBytes = 10_000_000, elapsedMs = 2_000, baseBytes = 2_000_000, baseMs = 1_500)
        assertEquals(128.0, rate, 1e-9)
        assertEquals(0.0, SessionSpeed.rateAfterWarmup(100, 1_000, baseBytes = -1, baseMs = 0), 0.0)
        assertEquals(0.0, SessionSpeed.rateAfterWarmup(100, 1_500, baseBytes = 100, baseMs = 1_500), 0.0)
    }

    @Test fun rateWindowArmsAfterWarmupAndPushesEvery250ms() {
        val w = SessionSpeed.RateWindow()
        SessionSpeed.addBytes(w, 1_000_000)
        assertNull(SessionSpeed.maybePushRate(w, 1_000))
        SessionSpeed.maybeArmWarmup(w, 1_000)
        assertEquals(-1L, w.base)
        SessionSpeed.maybeArmWarmup(w, 1_500)
        assertEquals(1_000_000L, w.base)
        assertEquals(1_500L, w.baseMs)
        SessionSpeed.addBytes(w, 2_000_000)
        val first = SessionSpeed.maybePushRate(w, 1_750)
        assertEquals(SessionSpeed.rateAfterWarmup(3_000_000, 1_750, 1_000_000, 1_500), first!!, 1e-9)
        // too soon for another push
        SessionSpeed.addBytes(w, 100)
        assertNull(SessionSpeed.maybePushRate(w, 1_900))
        val second = SessionSpeed.maybePushRate(w, 2_000)
        assertEquals(SessionSpeed.rateAfterWarmup(3_000_100, 2_000, 1_000_000, 1_500), second!!, 1e-9)
    }

    @Test fun formatMbpsUsesIntegerFrom100AndOneDecimalBelow() {
        assertEquals("—", SessionSpeed.dashOrMbps(null))
        assertEquals("12.3", SessionSpeed.formatMbps(12.34))
        assertEquals("12.0", SessionSpeed.formatMbps(12.0))
        assertEquals("99.9", SessionSpeed.formatMbps(99.94))
        assertEquals("100", SessionSpeed.formatMbps(100.4))
        assertEquals("128", SessionSpeed.formatMbps(127.6))
        assertEquals("—", SessionSpeed.formatPing(null))
        assertEquals("18", SessionSpeed.formatPing(18))
    }
}
