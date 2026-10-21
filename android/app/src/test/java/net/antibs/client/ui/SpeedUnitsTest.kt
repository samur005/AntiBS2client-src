package net.antibs.client.ui

import kotlinx.serialization.json.Json
import net.antibs.client.data.AdvancedSettings
import org.junit.Assert.*
import org.junit.Test

/** 1.0.55: «Единицы скорости». */
class SpeedUnitsTest {
    @Test fun bits() {
        assertEquals("0.0 кбит/с", Format.speedFixedBits(0))
        assertEquals("8.0 кбит/с", Format.speedFixedBits(1000))
        assertEquals("100.0 Мбит/с", Format.speedFixedBits(12_500_000))
        assertEquals("1.0 Гбит/с", Format.speedFixedBits(125_000_000))
        assertEquals("999.9 Тбит/с", Format.speedFixedBits(Long.MAX_VALUE))
    }
    @Test fun switchAndTemplates() {
        assertEquals(Format.speedFixed(12L * 1024 * 1024), Format.speedFixed(12L * 1024 * 1024, bits = false))
        assertEquals("12.0 МБ/с", Format.speedFixed(12L * 1024 * 1024, false))
        assertEquals("80.0 кбит/с", Format.speedFixed(10_000, true))
        var v = 1L
        while (v < Long.MAX_VALUE / 3) {
            assertTrue(Format.speedFixedBits(v).length <= Format.speedTemplate(true).length)
            assertTrue(Format.speedFixed(v).length <= Format.speedTemplate(false).length)
            v *= 3
        }
    }
    @Test fun defaultIsBytesAlsoForOldSettings() {
        assertEquals("bytes", AdvancedSettings().speedUnits)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AdvancedSettings.serializer(), "{}")
        assertEquals("bytes", old.speedUnits)
    }
}
