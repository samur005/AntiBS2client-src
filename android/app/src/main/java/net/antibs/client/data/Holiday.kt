package net.antibs.client.data

import java.time.LocalDate
import java.time.MonthDay

/** Seasonal / holiday decoration shown on the main screen. */
data class Holiday(
    val id: String,
    val name: String,
    val emoji: String,
    /** Month-day ranges (inclusive). For multi-day holidays use several entries or a range helper. */
    val dates: List<MonthDay>,
)

object HolidayCalendar {
    private val fixed = listOf(
        // Russian major holidays
        Holiday("ny", "Новый год", "🎄", listOf(MonthDay.of(12, 31), MonthDay.of(1, 1), MonthDay.of(1, 2), MonthDay.of(1, 3), MonthDay.of(1, 4), MonthDay.of(1, 5), MonthDay.of(1, 6), MonthDay.of(1, 7), MonthDay.of(1, 8))),
        Holiday("xmas", "Рождество", "⭐", listOf(MonthDay.of(1, 7))),
        Holiday("defender", "День защитника Отечества", "🎖️", listOf(MonthDay.of(2, 23))),
        Holiday("womens", "Международный женский день", "🌸", listOf(MonthDay.of(3, 8))),
        Holiday("victory", "День Победы", "🎗️", listOf(MonthDay.of(5, 9))),
        Holiday("russia", "День России", "🇷🇺", listOf(MonthDay.of(6, 12))),
        Holiday("unity", "День народного единства", "🤝", listOf(MonthDay.of(11, 4))),
        // Muslim major holidays (approximate Gregorian for 2026-2027; can be extended)
        Holiday("eid_fitr", "Ураза-байрам", "🌙", listOf(MonthDay.of(3, 20), MonthDay.of(3, 21), MonthDay.of(3, 9))), // 2026 ~20 Mar, 2027 ~9 Mar
        Holiday("eid_adha", "Курбан-байрам", "🐑", listOf(MonthDay.of(5, 27), MonthDay.of(5, 28), MonthDay.of(5, 16))), // 2026 ~27 May, 2027 ~16 May
        Holiday("mawlid", "Маулид ан-Наби", "🕌", listOf(MonthDay.of(9, 4), MonthDay.of(8, 14))), // approx
        Holiday("ashura", "День Ашура", "🕯️", listOf(MonthDay.of(7, 6), MonthDay.of(6, 15))),
    )

    /** Returns the holiday active on [date], or null. */
    fun current(date: LocalDate = LocalDate.now()): Holiday? {
        val md = MonthDay.from(date)
        return fixed.firstOrNull { h -> h.dates.any { it == md } }
    }
}
