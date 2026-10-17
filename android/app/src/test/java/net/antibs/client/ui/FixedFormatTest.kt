package net.antibs.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.13: fixed-format speed/total strings for the static speed card. */
class FixedFormatTest {
    @Test fun bytesFixedAlwaysOneDecimal() {
        assertEquals("0.0 КБ", Format.bytesFixed(0))
        assertEquals("0.0 КБ", Format.bytesFixed(-5))
        assertEquals("0.5 КБ", Format.bytesFixed(512))
        assertEquals("12.0 МБ", Format.bytesFixed(12L * 1024 * 1024))
        assertEquals("999.0 МБ", Format.bytesFixed(999L * 1024 * 1024))
        // ≥ 999.95 of a unit rolls over so the number never exceeds 999.9
        assertEquals("1.0 ГБ", Format.bytesFixed(1023L * 1024 * 1024 + 1000L * 1024))
        assertEquals("2.5 ТБ", Format.bytesFixed((2.5 * 1024 * 1024 * 1024 * 1024).toLong()))
        assertEquals("999.9 ТБ", Format.bytesFixed(Long.MAX_VALUE))
        assertEquals("12.0 МБ/с", Format.speedFixed(12L * 1024 * 1024))
    }

    @Test fun fixedStringsNeverExceedTemplate() {
        var v = 1L
        while (v < Long.MAX_VALUE / 3) {
            for (x in listOf(v, v * 999 / 1000, v + v / 2)) {
                val s = Format.speedFixed(x)
                val t = Format.bytesFixed(x)
                assertTrue(s, s.length <= Format.SPEED_TEMPLATE.length)
                assertTrue(t, t.length <= Format.TOTAL_TEMPLATE.length)
                assertTrue(t, Regex("""^\d{1,3}\.\d [КМГТ]Б$""").matches(t) )
            }
            v *= 3
        }
    }
}
