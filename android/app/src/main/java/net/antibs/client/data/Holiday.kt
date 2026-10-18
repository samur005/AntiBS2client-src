package net.antibs.client.data

import java.time.LocalDate
import java.time.MonthDay
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

data class Holiday(
    val id: String,
    val name: String,
    val emoji: String,
    val isMuslim: Boolean = false,
)

object HolidayCalendar {
    private val all = listOf(
        Holiday("ny", "Новый год", "🎄"),
        Holiday("xmas", "Рождество", "⭐"),
        Holiday("defender", "День защитника Отечества", "🎖️"),
        Holiday("womens", "Международный женский день", "🌸"),
        Holiday("victory", "День Победы", "🎗️"),
        Holiday("russia", "День России", "🇷🇺"),
        Holiday("unity", "День народного единства", "🤝"),
        Holiday("eid_fitr", "Ураза-байрам", "🌙", isMuslim = true),
        Holiday("eid_adha", "Курбан-байрам", "🐑", isMuslim = true),
        Holiday("mawlid", "Маулид ан-Наби", "🕌", isMuslim = true),
        Holiday("ashura", "День Ашура", "🕯️", isMuslim = true),
    )

    fun byId(id: String): Holiday? = all.firstOrNull { it.id == id }
    fun allHolidays(): List<Holiday> = all

    /** Active holiday on [date]. Muslim holidays use HijrahDate. */
    fun current(date: LocalDate = LocalDate.now()): Holiday? {
        val md = MonthDay.from(date)
        // Fixed Gregorian
        when (md) {
            MonthDay.of(12, 31), MonthDay.of(1, 1), MonthDay.of(1, 2), MonthDay.of(1, 3),
            MonthDay.of(1, 4), MonthDay.of(1, 5), MonthDay.of(1, 6), MonthDay.of(1, 7), MonthDay.of(1, 8) -> return byId("ny")
            MonthDay.of(1, 7) -> return byId("xmas")
            MonthDay.of(2, 23) -> return byId("defender")
            MonthDay.of(3, 8) -> return byId("womens")
            MonthDay.of(5, 9) -> return byId("victory")
            MonthDay.of(6, 12) -> return byId("russia")
            MonthDay.of(11, 4) -> return byId("unity")
        }
        // Hijri-based
        return try {
            val hijri = HijrahDate.from(date)
            val m = hijri.get(ChronoField.MONTH_OF_YEAR)
            val d = hijri.get(ChronoField.DAY_OF_MONTH)
            when {
                m == 10 && d in 1..3 -> byId("eid_fitr")      // Shawwal 1-3
                m == 12 && d in 10..13 -> byId("eid_adha")    // Dhul-Hijjah 10-13
                m == 3 && d == 12 -> byId("mawlid")           // Rabi al-awwal 12
                m == 1 && d == 10 -> byId("ashura")           // Muharram 10
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
