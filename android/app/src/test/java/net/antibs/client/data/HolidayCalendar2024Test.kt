package net.antibs.client.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HolidayCalendar2024Test {
    private fun id(y: Int, m: Int, d: Int, ov: List<MuslimOverride> = emptyList()): String? {
        HolidayCalendar.overrides = ov; return HolidayCalendar.current(LocalDate.of(y, m, d))?.id.also { HolidayCalendar.overrides = emptyList() }
    }
    @Test fun offlineHijri() {
        assertEquals("eid_fitr", id(2026, 3, 20)); assertEquals("eid_fitr", id(2026, 3, 22)); assertNull(id(2026, 3, 23))
        assertEquals("eid_adha", id(2026, 5, 27)); assertEquals("eid_adha", id(2026, 5, 30)); assertNull(id(2026, 5, 31))
        assertEquals("ashura", id(2026, 6, 25)); assertEquals("mawlid", id(2026, 8, 25))
        assertEquals("eid_fitr", id(2027, 3, 9)); assertEquals("eid_adha", id(2027, 5, 16))
        assertEquals("ashura", id(2027, 6, 15)); assertEquals("mawlid", id(2027, 8, 14))
    }
    @Test fun fixedRanges() {
        assertEquals("ny", id(2026, 12, 31)); assertEquals("ny", id(2027, 1, 6)); assertEquals("xmas", id(2027, 1, 7))
        assertEquals("victory", id(2026, 5, 9)); assertNull(id(2026, 4, 1))
    }
    @Test fun overrideWinsForItsYear() {
        val ov = listOf(MuslimOverride("eid_fitr", 2026, 3, 21, 3))
        assertNull(id(2026, 3, 20, ov)); assertEquals("eid_fitr", id(2026, 3, 23, ov))
        assertEquals("eid_fitr", id(2027, 3, 9, ov)) // other year → offline
    }
    @Test fun fetchParsesAndFilters() {
        val r = MuslimDates.fetch(2026) { url ->
            val p = url.substringAfterLast("/").split("-"); val hy = p[2].toInt()
            val d = java.time.LocalDate.from(java.time.chrono.HijrahDate.of(hy, p[1].toInt(), p[0].toInt()))
            "{\"code\":200,\"data\":{\"gregorian\":{\"date\":\"%02d-%02d-%d\"}}}".format(d.dayOfMonth, d.monthValue, d.year)
        }!!
        assertTrue(r.all { it.year in 2026..2027 }); assertTrue(r.any { it.id == "eid_fitr" && it.start == LocalDate.of(2027, 3, 9) })
        assertNull(MuslimDates.fetch(2026) { null })
    }
    @Test fun seasonRules() {
        assertTrue(SnowMode.inSeason(LocalDate.of(2026, 2, 28))); assertFalse(SnowMode.inSeason(LocalDate.of(2026, 3, 10)))
        val fx = SeasonalFx(); assertFalse(fx.garlandOn(false)); assertTrue(fx.garlandOn(true))
    }
}
