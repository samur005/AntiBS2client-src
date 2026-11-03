package net.antibs.client.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
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

/** 2.0.24 (ABS Seasonal): first day of a Muslim holiday confirmed by aladhan.com, with its year (old builds kept month/day only). */
data class MuslimOverride(val id: String, val year: Int, val month: Int, val day: Int, val days: Int) {
    val start: LocalDate get() = LocalDate.of(year, month, day)
    val end: LocalDate get() = start.plusDays(days - 1L)
}

object HolidayCalendar {
    private class Rule(val id: String, val sm: Int, val sd: Int, val em: Int, val ed: Int)
    /** New Year 31 Dec – 6 Jan, Christmas 7 – 8 Jan (no overlap). */
    private val FIXED = listOf(
        Rule("ny", 12, 31, 1, 6), Rule("xmas", 1, 7, 1, 8), Rule("defender", 2, 23, 2, 23), Rule("womens", 3, 8, 3, 8),
        Rule("victory", 5, 9, 5, 9), Rule("russia", 6, 12, 6, 12), Rule("unity", 11, 4, 11, 4),
    )
    /** Muslim holidays: id → Hijri month, first Hijri day, length in days. */
    internal class Hijri(val id: String, val hMonth: Int, val hDay: Int, val days: Int)
    internal val HIJRI = listOf(Hijri("eid_fitr", 10, 1, 3), Hijri("eid_adha", 12, 10, 4), Hijri("mawlid", 3, 12, 1), Hijri("ashura", 1, 10, 1))
    val MUSLIM_IDS = HIJRI.map { it.id }.toSet()

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

    /** Confirmed dates (aladhan), year-aware; see [MuslimDates]. */
    @Volatile var overrides: List<MuslimOverride> = emptyList()

    fun byId(id: String, custom: List<CustomHoliday> = emptyList()): Holiday? = all(custom).firstOrNull { it.id == id }

    fun inRange(date: LocalDate, sm: Int, sd: Int, em: Int, ed: Int): Boolean {
        val k = date.monthValue * 100 + date.dayOfMonth; val a = sm * 100 + sd; val b = em * 100 + ed
        return if (a <= b) k in a..b else k >= a || k <= b
    }

    /** Hijri (Umm al-Qura) month/day, null when the platform has no Hijri data. */
    fun hijri(date: LocalDate): Pair<Int, Int>? = runCatching {
        val h = HijrahDate.from(date); h.get(ChronoField.MONTH_OF_YEAR) to h.get(ChronoField.DAY_OF_MONTH)
    }.getOrNull()

    fun muslimOn(date: LocalDate, ov: List<MuslimOverride> = overrides): Holiday? {
        val hj = hijri(date)
        for (h in HIJRI) {
            // a confirmed date for this holiday and year replaces the offline estimate
            val mine = ov.filter { it.id == h.id && (it.year == date.year || it.end.year == date.year) }
            val hit = if (mine.isNotEmpty()) mine.any { !date.isBefore(it.start) && !date.isAfter(it.end) }
            else hj != null && hj.first == h.hMonth && hj.second in h.hDay until h.hDay + h.days
            if (hit) return byId(h.id)
        }
        return null
    }

    /** Offline first days of the Muslim holidays that start in [year] (Umm al-Qura). */
    fun muslimStarts(year: Int): List<Pair<String, LocalDate>> = runCatching {
        val a = HijrahDate.from(LocalDate.of(year, 1, 1)).get(ChronoField.YEAR)
        val b = HijrahDate.from(LocalDate.of(year, 12, 31)).get(ChronoField.YEAR)
        (a..b).flatMap { hy -> HIJRI.map { it.id to LocalDate.from(HijrahDate.of(hy, it.hMonth, it.hDay)) } }.filter { it.second.year == year }.sortedBy { it.second }
    }.getOrDefault(emptyList())

    fun current(date: LocalDate = LocalDate.now(), custom: List<CustomHoliday> = emptyList()): Holiday? = runCatching {
        custom.firstOrNull { c -> inRange(date, c.month.coerceIn(1, 12), c.day.coerceIn(1, 31),
            if (c.endMonth in 1..12) c.endMonth else c.month, if (c.endMonth in 1..12 && c.endDay in 1..31) c.endDay else c.day) }
            ?.let { return@runCatching Holiday(it.id, it.name, it.emoji, custom = true, month = it.month, day = it.day) }
        FIXED.firstOrNull { inRange(date, it.sm, it.sd, it.em, it.ed) }?.let { return@runCatching byId(it.id) }
        muslimOn(date)
    }.getOrNull()
}

/** aladhan.com Hijri → Gregorian, stored in a small file (ABS AladhanDates); refreshed by the button and automatically. */
object MuslimDates {
    private const val FILE = "muslim_dates.txt"
    private val DAYS = HolidayCalendar.HIJRI.associate { it.id to it.days }
    @Volatile var updatedAt: Long = 0L; private set
    @Volatile var updatedYear: Int = 0; private set

    fun parseGregorian(body: String): LocalDate? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        val code = (o["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 200
        if (code != 200) return null
        val g = ((o["data"] as kotlinx.serialization.json.JsonObject)["gregorian"] as kotlinx.serialization.json.JsonObject)["date"] as kotlinx.serialization.json.JsonPrimitive
        val p = g.content.split("-"); LocalDate.of(p[2].toInt(), p[1].toInt(), p[0].toInt())
    }.getOrNull()

    /** Confirmed dates for [year] and the next; null when the service could not be reached. */
    fun fetch(year: Int, get: (String) -> String? = ::httpGet): List<MuslimOverride>? {
        val hy = runCatching {
            HijrahDate.from(LocalDate.of(year, 1, 1)).get(ChronoField.YEAR)..HijrahDate.from(LocalDate.of(year + 1, 12, 31)).get(ChronoField.YEAR)
        }.getOrNull() ?: return null
        val out = ArrayList<MuslimOverride>(); var failed = 0
        for (h in HolidayCalendar.HIJRI) for (y in hy) {
            val d = get("https://api.aladhan.com/v1/hToG/%02d-%02d-%d".format(h.hDay, h.hMonth, y))?.let { parseGregorian(it) }
            if (d == null) { failed++; continue }
            if (d.year in year..year + 1) out.add(MuslimOverride(h.id, d.year, d.monthValue, d.dayOfMonth, h.days))
        }
        return if (out.isEmpty() || failed > 3) null else out
    }

    fun load(dir: File) = runCatching {
        val lines = File(dir, FILE).takeIf { it.exists() }?.readLines() ?: return@runCatching
        val head = lines.firstOrNull()?.split(" ") ?: return@runCatching
        updatedAt = head.getOrNull(0)?.toLongOrNull() ?: 0L; updatedYear = head.getOrNull(1)?.toIntOrNull() ?: 0
        HolidayCalendar.overrides = lines.drop(1).mapNotNull { l ->
            val p = l.split(","); if (p.size != 5) return@mapNotNull null
            val y = p[1].toIntOrNull() ?: return@mapNotNull null; val m = p[2].toIntOrNull() ?: return@mapNotNull null
            val d = p[3].toIntOrNull() ?: return@mapNotNull null; val n = p[4].toIntOrNull() ?: return@mapNotNull null
            if (p[0] !in HolidayCalendar.MUSLIM_IDS || y !in 1900..2200 || n !in 1..7) return@mapNotNull null
            runCatching { LocalDate.of(y, m, d) }.getOrNull()?.let { MuslimOverride(p[0], y, m, d, n) }
        }
    }

    fun save(dir: File, list: List<MuslimOverride>, now: Long = System.currentTimeMillis(), year: Int = LocalDate.now().year) {
        HolidayCalendar.overrides = list; updatedAt = now; updatedYear = year
        runCatching { File(dir, FILE).writeText("$now $year\n" + list.joinToString("\n") { "${it.id},${it.year},${it.month},${it.day},${it.days}" }) }
    }

    /** Auto refresh: never updated, older than 30 days, or the year changed. */
    fun stale(now: Long = System.currentTimeMillis(), year: Int = LocalDate.now().year) =
        updatedAt == 0L || updatedYear != year || now - updatedAt > 30L * 24 * 3600 * 1000

    /** Fetch + save; true on success. Call off the main thread. */
    fun refresh(dir: File): Boolean { val y = LocalDate.now().year; val l = fetch(y) ?: return false; save(dir, l, year = y); return true }

    private fun httpGet(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    }.getOrNull()
}
