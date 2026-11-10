package net.antibs.client.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class HolidayStyleTest {
    @Test fun offOrHiddenLabelYieldsNothing() {
        assertNull(HolidayStyle.stripHoliday("off", true, date = LocalDate.of(2026, 1, 1)))
        assertNull(HolidayStyle.stripHoliday("auto", false, date = LocalDate.of(2026, 1, 1)))
        assertNull(HolidayStyle.stripHoliday("ny", false, date = LocalDate.of(2026, 6, 1)))
    }

    @Test fun autoUsesCalendarDate() {
        val ny = HolidayStyle.stripHoliday("auto", true, date = LocalDate.of(2026, 1, 1))
        assertEquals("ny", ny!!.id)
        val womens = HolidayStyle.stripHoliday("auto", true, date = LocalDate.of(2026, 3, 8))
        assertEquals("womens", womens!!.id)
        val victory = HolidayStyle.stripHoliday("auto", true, date = LocalDate.of(2026, 5, 9))
        assertEquals("victory", victory!!.id)
        assertNull(HolidayStyle.stripHoliday("auto", true, date = LocalDate.of(2026, 11, 10)))
    }

    @Test fun forcedIdShowsEvenOffSeason() {
        val h = HolidayStyle.stripHoliday("eid_fitr", true, date = LocalDate.of(2026, 11, 10))
        assertEquals("eid_fitr", h!!.id)
        assertEquals("Ураза-байрам", h.name)
    }

    @Test fun everyBuiltinHasAPalette() {
        for (h in HolidayCalendar.builtins()) {
            val p = HolidayStyle.palette(h.id)
            assertTrue(h.id, p.colors.size >= 3)
            assertNotEquals(0L, p.text)
        }
        val custom = HolidayStyle.palette("custom-xyz")
        assertTrue(custom.colors.size >= 3)
    }

    @Test fun themedStopsMatchTheBrief() {
        val ny = HolidayStyle.palette("ny").colors
        assertTrue(ny.contains(0xFFC41E3A))
        assertTrue(ny.contains(0xFF0B7A32))
        assertTrue(ny.contains(0xFFE0B12A))
        val victory = HolidayStyle.palette("victory").colors
        assertTrue(victory.contains(0xFFE65100) || victory.contains(0xFFFF8F00))
        assertTrue(victory.contains(0xFF1A1A1A))
        val eid = HolidayStyle.palette("eid_fitr").colors
        assertEquals(eid, HolidayStyle.palette("eid_adha").colors)
        assertTrue(eid.any { it == 0xFF0B5D3A || it == 0xFF147A4B })
        assertTrue(eid.contains(0xFFD4AF37) || eid.contains(0xFFF0D78C))
        val womens = HolidayStyle.palette("womens").colors
        assertTrue(womens.any { it == 0xFFE16BA8 || it == 0xFFFFB6C8 })
    }
}
