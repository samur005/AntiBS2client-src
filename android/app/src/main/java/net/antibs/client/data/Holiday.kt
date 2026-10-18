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
    val custom: Boolean = false,
    val month: Int = 0,
    val day: Int = 0,
)

object HolidayCalendar {
    private val builtin = listOf(
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

    fun builtins(): List<Holiday> = builtin

    fun all(custom: List<CustomHoliday>): List<Holiday> =
        builtin + custom.map { Holiday(it.id, it.name, it.emoji, custom = true, month = it.month, day = it.day) }

    fun byId(id: String, custom: List<CustomHoliday> = emptyList()): Holiday? =
        all(custom).firstOrNull { it.id == id }

    fun current(date: LocalDate = LocalDate.now(), custom: List<CustomHoliday> = emptyList()): Holiday? {
        val md = MonthDay.from(date)
        // Custom first (user overrides) — supports start..end range
        custom.firstOrNull { c ->
            val start = java.time.MonthDay.of(c.month, c.day)
            val end = if (c.endMonth in 1..12 && c.endDay in 1..31) java.time.MonthDay.of(c.endMonth, c.endDay) else start
            if (start <= end) md in start..end else md >= start || md <= end
        }?.let {
            return Holiday(it.id, it.name, it.emoji, custom = true, month = it.month, day = it.day)
        }
        // Builtin Gregorian
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
        // Hijri
        return try {
            val hijri = HijrahDate.from(date)
            val m = hijri.get(ChronoField.MONTH_OF_YEAR)
            val d = hijri.get(ChronoField.DAY_OF_MONTH)
            when {
                m == 10 && d in 1..3 -> byId("eid_fitr")
                m == 12 && d in 10..13 -> byId("eid_adha")
                m == 3 && d == 12 -> byId("mawlid")
                m == 1 && d == 10 -> byId("ashura")
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
