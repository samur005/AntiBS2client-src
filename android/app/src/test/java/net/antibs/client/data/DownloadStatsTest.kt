package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStatsTest {
    @Test fun smoothing() { assertEquals(100.0, DownloadStats.smooth(0.0, 100.0), 0.0); assertEquals(125.0, DownloadStats.smooth(100.0, 200.0), 0.0) }
    @Test fun eta() {
        assertEquals(10L, DownloadStats.etaSec(0, 1000, 100.0)); assertNull(DownloadStats.etaSec(0, 0, 100.0))
        assertNull(DownloadStats.etaSec(0, 1000, 0.0)); assertEquals(0L, DownloadStats.etaSec(1000, 1000, 0.0))
    }
    @Test fun format() {
        assertEquals("01:05", DownloadStats.mmss(65)); assertEquals("--:--", DownloadStats.mmss(null))
        assertEquals("1,5 МБ", DownloadStats.mb(1572864)); assertEquals("512 КБ/с", DownloadStats.speed(524288.0))
        assertEquals("2,0 МБ/с", DownloadStats.speed(2097152.0))
    }
    @Test fun pingCounts() {
        val c = DownloadStats.pingCounts(setOf("a", "b", "c", "d"), setOf("d"), mapOf("a" to 50L, "b" to -1L, "c" to 70L))
        assertEquals(Triple(4, 3, 1), c)
    }
}
