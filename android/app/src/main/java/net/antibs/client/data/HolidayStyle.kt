package net.antibs.client.data

import java.time.LocalDate

/** Colours for the Home holiday strip (ARGB). [colors] are the moving gradient stops; [text] is on-strip text. */
data class HolidayPalette(val id: String, val colors: List<Long>, val text: Long)

object HolidayStyle {
    /**
     * Holiday to show in the Home list-header strip, or null when nothing should take that space
     * (decorations off, label hidden, or no active holiday).
     */
    fun stripHoliday(
        decor: String,
        showLabel: Boolean,
        custom: List<CustomHoliday> = emptyList(),
        date: LocalDate = LocalDate.now(),
    ): Holiday? {
        if (decor == "off" || !showLabel) return null
        return if (decor == "auto") HolidayCalendar.current(date, custom)
        else HolidayCalendar.byId(decor, custom)
    }

    fun palette(id: String): HolidayPalette = when (id) {
        // New Year: classic red / green / gold
        "ny" -> pal("ny", 0xFFC41E3A, 0xFF0B7A32, 0xFFE0B12A, 0xFFC41E3A, text = 0xFFFFF8E1)
        // Christmas (7 Jan): warm gold, ivory, deep red
        "xmas" -> pal("xmas", 0xFF8B1E1E, 0xFFD4AF37, 0xFFFFF4D6, 0xFFD4AF37, 0xFF8B1E1E, text = 0xFFFFF8E1)
        // 23 February: olive, crimson, brass
        "defender" -> pal("defender", 0xFF3D4A2A, 0xFF8B1E1E, 0xFFC6A24A, 0xFF3D4A2A, text = 0xFFFFF4D6)
        // 8 March: rose, blush, soft gold
        "womens" -> pal("womens", 0xFFE16BA8, 0xFFFFB6C8, 0xFFF6D48A, 0xFFE16BA8, text = 0xFF4A1530)
        // 9 May: St George ribbon — black and orange
        "victory" -> pal("victory", 0xFFE65100, 0xFF1A1A1A, 0xFFFF8F00, 0xFF1A1A1A, 0xFFE65100, text = 0xFFFFF3E0)
        // Russia Day: white / blue / red
        "russia" -> pal("russia", 0xFFF5F7FA, 0xFF1E4FA3, 0xFFC62828, 0xFF1E4FA3, 0xFFF5F7FA, text = 0xFF101820)
        // Unity Day: warm gold over the tricolour
        "unity" -> pal("unity", 0xFFC62828, 0xFF1E4FA3, 0xFFD4AF37, 0xFFC62828, text = 0xFFFFF8E1)
        // Eid / Mawlid / Ashura: green and gold (crescent)
        "eid_fitr", "eid_adha", "mawlid", "ashura" ->
            pal(id, 0xFF0B5D3A, 0xFFD4AF37, 0xFF147A4B, 0xFFF0D78C, 0xFF0B5D3A, text = 0xFFFFF8E1)
        else -> pal(id, 0xFF5B2C8A, 0xFFD4AF37, 0xFF7E57C2, 0xFFD4AF37, text = 0xFFFFF8E1)
    }

    private fun pal(id: String, vararg colors: Long, text: Long) = HolidayPalette(id, colors.toList(), text)
}
