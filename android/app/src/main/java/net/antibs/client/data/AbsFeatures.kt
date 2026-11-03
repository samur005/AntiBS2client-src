package net.antibs.client.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.Calendar

/*
 * 2.0.6: features ported from ABS proxy 3.1.x — subscription remainder line, country groups, VPN schedule
 * and the seasonal effects layer. Pure Kotlin (no Android), so all rules are unit-tested.
 */

// ───────────── «Остаток подписки» (ABS SubLine / SubStyle)

@Serializable
data class SubRemainStyle(
    val show: Boolean = true,
    val size: Int = SIZE_DEF,
    val bold: Boolean = false,
    /** 0 left, 1 centre, 2 right */
    val align: Int = ALIGN_CENTER,
    /** ARGB, 0 = default colour */
    val daysText: Long = 0L,
    val daysBar: Long = 0L,
    val trafficText: Long = 0L,
    val trafficBar: Long = 0L,
) {
    companion object { const val SIZE_MIN = 9; const val SIZE_MAX = 16; const val SIZE_DEF = 11
        const val ALIGN_START = 0; const val ALIGN_CENTER = 1; const val ALIGN_END = 2 }
}

/** «122 дн · до 05.03 ▬   1,4 / 150 ГБ ▬» */
data class SubLineData(val days: String, val until: String?, val dayFrac: Float?, val traffic: String, val trafficFrac: Float?) {
    companion object {
        const val MONTH_DAYS = 30
        private val UNITS = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")

        /** null = nothing to show (no expiry, no total and no usage). */
        fun of(sub: Subscription?, nowMs: Long): SubLineData? {
            if (sub == null) return null
            val used = (sub.upload + sub.download).coerceAtLeast(0)
            val total = sub.total.coerceAtLeast(0)
            if (sub.expireEpoch <= 0 && total <= 0 && used <= 0) return null
            val d = daysLeft(sub.expireEpoch, nowMs)
            val days = when { d == null -> "∞"; d < 0 -> "истекла"; else -> "$d дн" }
            val until = if (sub.expireEpoch > 0) "до " + ddMM(sub.expireEpoch) else null
            val dayFrac = d?.let { (it.toFloat() / MONTH_DAYS).coerceIn(0f, 1f) }
            val traffic = if (total > 0) pair(used, total) else amount(used) + " · безлимит"
            val tf = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else null
            return SubLineData(days, until, dayFrac, traffic, tf)
        }

        fun daysLeft(expireSec: Long, nowMs: Long): Int? {
            if (expireSec <= 0) return null
            val delta = expireSec * 1000 - nowMs
            return if (delta < 0) -1 else (delta / 86_400_000L).toInt()
        }

        fun ddMM(expireSec: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = expireSec * 1000 }
            return "%02d.%02d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
        }

        private fun unitOf(b: Long): Int { var u = 0; var v = b.toDouble(); while (v >= 1024 && u < UNITS.size - 1) { v /= 1024; u++ }; return u }
        private fun num(b: Long, u: Int): String {
            val v = b / Math.pow(1024.0, u.toDouble())
            val s = if (v >= 10 || v == Math.floor(v)) "%.0f".format(java.util.Locale.ROOT, v) else "%.1f".format(java.util.Locale.ROOT, v)
            return s.replace('.', ',')
        }
        /** «114 / 150 ГБ»: both in the unit of the total; a much smaller used value keeps its own unit (never raw bytes). */
        fun pair(used: Long, total: Long): String {
            val u = unitOf(total)
            if (unitOf(used) != u && used > 0) return amount(used) + " / " + amount(total)
            return "${num(used, u)} / ${num(total, u)} ${UNITS[u]}"
        }
        fun amount(b: Long): String { val u = unitOf(b); return num(b, u) + " " + UNITS[u] }
    }
}

// ───────────── «Группировать по странам» (ABS ServerGrouping)

@Serializable
data class SubGrouping(val on: Boolean? = null, val sort: String? = null, val openDefault: Boolean? = null, val showPing: Boolean? = null) {
    fun isEmpty() = on == null && sort == null && openDefault == null && showPing == null
}

@Serializable
data class ServerGrouping(
    val on: Boolean = false,
    val sort: String = SORT_NAME,
    val rememberOpen: Boolean = true,
    /** open-state keys toggled away from [openDefault] ("subId|CC"). */
    val open: Set<String> = emptySet(),
    val subs: Map<String, SubGrouping> = emptyMap(),
    val openDefault: Boolean = false,
    val showPing: Boolean = true,
) {
    fun isOn(subId: String): Boolean = subs[subId]?.on ?: on
    fun forSub(subId: String): ServerGrouping = subs[subId].let { o -> if (o == null) this else copy(on = o.on ?: on, sort = o.sort ?: sort, openDefault = o.openDefault ?: openDefault, showPing = o.showPing ?: showPing) }
    fun withSub(subId: String, f: (SubGrouping) -> SubGrouping): ServerGrouping = f(subs[subId] ?: SubGrouping()).let { n -> copy(subs = if (n.isEmpty()) subs - subId else subs + (subId to n)) }
    fun isOpen(subId: String, code: String): Boolean = (openKey(subId, code) in open) != forSub(subId).openDefault
    fun toggle(subId: String, code: String): ServerGrouping { val k = openKey(subId, code); return copy(open = if (k in open) open - k else open + k) }

    companion object {
        const val SORT_NAME = "name"; const val SORT_COUNT = "count"; const val SORT_PING = "ping"
        const val OTHER = "__other__"
        fun openKey(subId: String, code: String) = "$subId|$code"

        /** «🇫🇮 Helsinki» → «FI» (first regional-indicator pair), else null. */
        fun countryCode(name: String): String? {
            val cps = name.codePoints().toArray()
            for (i in 0 until cps.size - 1) {
                val a = cps[i]; val b = cps[i + 1]
                if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) return "${('A' + (a - 0x1F1E6))}${('A' + (b - 0x1F1E6))}"
            }
            return null
        }
        fun flag(code: String): String = if (code.length != 2) "🌐" else String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
        fun countryName(code: String): String = if (code == OTHER) "Другие" else java.util.Locale("", code).getDisplayCountry(java.util.Locale("ru")).ifBlank { code }

        /** Groups [items] by country; countries sorted by [sort], «Другие» always last. */
        fun <T> group(items: List<T>, name: (T) -> String, sort: String, ping: (T) -> Long? = { null }, countryName: (String) -> String = ::countryName): List<Pair<String, List<T>>> {
            val m = LinkedHashMap<String, MutableList<T>>()
            for (it in items) m.getOrPut(countryCode(name(it)) ?: OTHER) { ArrayList() }.add(it)
            val other = m.remove(OTHER)
            val keys = when (sort) {
                SORT_PING -> m.keys.sortedWith(compareBy<String> { k -> m[k]!!.mapNotNull(ping).filter { it >= 0 }.minOrNull() ?: Long.MAX_VALUE }.thenBy { countryName(it).lowercase() })
                SORT_COUNT -> m.keys.sortedWith(compareByDescending<String> { m[it]!!.size }.thenBy { countryName(it).lowercase() })
                else -> m.keys.sortedBy { countryName(it).lowercase() }
            }
            return keys.map { it to m[it]!!.toList() } + listOfNotNull(other?.let { OTHER to it.toList() })
        }

        /** Lowest real ping (ms ≥ 0) among a country's servers. */
        fun bestPing(pings: List<Long?>): Long? = pings.filterNotNull().filter { it >= 0 }.minOrNull()
    }
}

// ───────────── «Расписание VPN» (ABS VpnSchedule)

@Serializable
data class ScheduleRule(val id: Long, val on: Boolean = true, val connect: Boolean = true, val hour: Int = 8, val minute: Int = 0, val days: Int = ALL) {
    /** Calendar.DAY_OF_WEEK → bit: Mon = 0 … Sun = 6. */
    fun hasDay(calDow: Int): Boolean = days and (1 shl dayIndex(calDow)) != 0
    companion object {
        const val ALL = 0x7F; const val WORKDAYS = 0x1F; const val WEEKEND = 0x60
        fun dayIndex(calDow: Int): Int = (calDow + 5) % 7
        val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }
}

@Serializable
data class VpnScheduleCfg(val on: Boolean = false, val notify: Boolean = true, val rules: List<ScheduleRule> = emptyList()) {
    /** Next (time ms, rule) strictly after [now]; null = nothing armed. */
    fun next(now: Long): Pair<Long, ScheduleRule>? {
        if (!on) return null
        var best: Pair<Long, ScheduleRule>? = null
        for (r in rules) if (r.on && r.days != 0) {
            val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                set(Calendar.HOUR_OF_DAY, r.hour.coerceIn(0, 23)); set(Calendar.MINUTE, r.minute.coerceIn(0, 59)) }
            for (i in 0 until 8) {
                if (c.timeInMillis > now && r.hasDay(c.get(Calendar.DAY_OF_WEEK))) {
                    if (best == null || c.timeInMillis < best.first) best = c.timeInMillis to r
                    break
                }
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return best
    }
    /** Rule due now (fired within the last 2 minutes — alarms may be late). */
    fun due(now: Long): ScheduleRule? = next(now - 120_000L)?.takeIf { it.first <= now }?.second

    companion object {
        fun daysLabel(d: Int): String = when (d) {
            ScheduleRule.ALL -> "Каждый день"; ScheduleRule.WORKDAYS -> "Будни"; ScheduleRule.WEEKEND -> "Выходные"; 0 -> "Нет дней"
            else -> (0..6).filter { d and (1 shl it) != 0 }.joinToString(", ") { ScheduleRule.DAY_NAMES[it] }
        }
    }
}

// ───────────── «Сезонные украшения»: эффекты (ABS SeasonalSettings)

object SnowMode {
    const val OFF = "off"; const val AUTO = "auto"; const val ON = "on"
    /** 2.0.24: winter only — 1 December – end of February (covers the New Year period 31 Dec – 6 Jan). */
    fun inSeason(date: LocalDate): Boolean = date.monthValue == 12 || date.monthValue <= 2
}
object SeasonalEffectsMode { const val OFF = "off"; const val HOLIDAY = "holiday"; const val ALWAYS = "always" }

@Serializable
data class SeasonalFx(
    val showLabel: Boolean = true,
    /** ARGB, 0 = theme text colour */
    val labelColor: Long = 0L,
    val labelAlpha: Int = 100,
    val labelSize: Int = 100,
    /** garland: off / holiday / always */
    val effects: String = SeasonalEffectsMode.HOLIDAY,
    val snowMode: String = SnowMode.AUTO,
    val snowCount: Int = 30,
    val snowSpeed: Int = 100,
    val snowSize: Int = 70,
    val snowColor: Long = 0xFFFFFFFFL,
    val garland: Boolean = true,
    val garlandBlink: Boolean = true,
    val garlandSpeed: Int = 100,
) {
    /** [holidayMode] = AdvancedSettings.seasonalDecor ("off" / "auto" / id). */
    fun snowOn(date: LocalDate, holidayMode: String): Boolean = when (snowMode) {
        SnowMode.ON -> true
        SnowMode.AUTO -> holidayMode == "auto" && SnowMode.inSeason(date)
        else -> false
    }
    /** 2.0.24: [holidayToday] = today's holiday is New Year — the garland is New Year only; other holidays get just the icon + label. */
    fun garlandOn(holidayToday: Boolean): Boolean = garland && when (effects) {
        SeasonalEffectsMode.ALWAYS -> true
        SeasonalEffectsMode.HOLIDAY -> holidayToday
        else -> false
    }
    fun clamped() = copy(labelAlpha = labelAlpha.coerceIn(LABEL_ALPHA_MIN, 100), labelSize = labelSize.coerceIn(LABEL_SIZE_MIN, LABEL_SIZE_MAX),
        snowCount = snowCount.coerceIn(SNOW_MIN, SNOW_MAX), snowSpeed = snowSpeed.coerceIn(SPEED_MIN, SPEED_MAX), snowSize = snowSize.coerceIn(SIZE_MIN, SIZE_MAX),
        garlandSpeed = garlandSpeed.coerceIn(SPEED_MIN, SPEED_MAX))
    companion object {
        const val LABEL_ALPHA_MIN = 20; const val LABEL_SIZE_MIN = 60; const val LABEL_SIZE_MAX = 200
        const val SNOW_MIN = 10; const val SNOW_MAX = 150
        const val SPEED_MIN = 30; const val SPEED_MAX = 300
        const val SIZE_MIN = 50; const val SIZE_MAX = 200
        val SWATCHES = listOf(0xFFFFFFFFL, 0xFFFF5252L, 0xFFFFA726L, 0xFFFFEB3BL, 0xFF66BB6AL, 0xFF4DD0E1L, 0xFF42A5F5L, 0xFFF48FB1L)
    }
}

// ───────────── «Чип пинга» + «Автопинг» (ABS PingChipStyle / AutoPing, 3.1.5–3.1.6)

@Serializable
data class PingChipStyle(val show: Boolean = true, val size: Int = SIZE_DEF, val bold: Boolean = false, val mono: Boolean = false,
                         val text: Long = 0L, val bg: Long = 0L, val bgPad: Int = PAD_DEF, val alpha: Int = 100) {
    companion object {
        const val SIZE_MIN = 8; const val SIZE_MAX = 18; const val SIZE_DEF = 11
        const val PAD_MIN = 0; const val PAD_MAX = 12; const val PAD_DEF = 4
        fun tone(ms: Long?): Int = when { ms == null || ms < 0 -> 2; ms < 200 -> 0; ms < 500 -> 1; else -> 2 }
        /** «пинг: 131 мс», «пинг: таймаут», «пинг: …» while measuring, «пинг: —» before the first result. */
        fun label(ms: Long?, busy: Boolean): String = "пинг: " + when { busy -> "…"; ms == null -> "—"; ms < 0 -> "таймаут"; else -> "$ms мс" }
    }
}

@Serializable
data class AutoPing(val on: Boolean = true, val intervalSec: Int = INTERVAL_DEF,
                    /** background full-list ping every 30 min while connected → consecutive-failure counters */
                    val autoDelete: Boolean = false, val failsToDelete: Int = AutoDeletePing.FAILS_DEF) {
    val periodMs: Long get() = intervalSec.coerceIn(5, 3600) * 1000L
    companion object { val INTERVALS = listOf(10, 15, 30, 60, 120, 300); const val INTERVAL_DEF = 30
        fun intervalLabel(s: Int) = if (s < 60) "$s с" else "${s / 60} мин" }
}

// ───────────── Маршрут пинга по протоколу (ABS PingRoute, 3.1.7–3.1.9)

object PingRoute {
    /** UDP / QUIC transports: a TCP connect to host:port measures nothing → host ICMP instead. */
    val UDP = setOf("hysteria", "hysteria2", "hy2", "tuic", "wireguard", "awg", "amneziawg", "juicity", "ikev2", "qwdtt")
    fun isUdp(protocol: String?, network: String?, alpn: String?): Boolean {
        val p = protocol.orEmpty().trim().lowercase()
        return p in UDP || (p == "vless" && network.orEmpty().lowercase() == "xhttp" && alpn.orEmpty().contains("h3")) || network.orEmpty().lowercase() == "quic"
    }
    /** Effective method for the «Тип пинга» setting: "tcp" on a UDP transport becomes "icmp". */
    fun method(pingType: String, protocol: String?, network: String?, alpn: String?): String =
        if (pingType == "tcp" && isUdp(protocol, network, alpn)) "icmp" else pingType
}

// ───────────── Автоудаление недоступных (ABS AutoDeletePing + BgListPing)

object AutoDeletePing {
    const val FAILS_DEF = 5
    val FAILS_CHOICES = listOf(3, 5, 10, 15)
    const val BG_PERIOD_MS = 30 * 60_000L
    const val BG_FIRST_DELAY_MS = 60_000L
    const val BG_PARALLEL = 2

    sealed interface Outcome { data class Deleted(val ids: Set<String>) : Outcome; data object Whitelist : Outcome; data object Nothing : Outcome }
    data class Result(val outcome: Outcome, val counts: Map<String, Int>)

    /**
     * [results]: ms ≥ 0 ok, -1 failed, ≤ -2 not applicable. Successes reset to 0; failures count only when the round is trusted
     * (online, not all / not more than half failed, direct net check passes). Removed at [failsToDelete]; [protect] never.
     */
    suspend fun decide(results: Map<String, Long>, protect: Set<String?>, online: Boolean, counts: Map<String, Int>,
                       failsToDelete: Int = FAILS_DEF, netCheck: suspend () -> Boolean): Result {
        if (!online || results.isEmpty()) return Result(Outcome.Nothing, counts)
        val m = counts.toMutableMap()
        results.filterValues { it >= 0 }.keys.forEach { m.remove(it) }
        val measurable = results.filterValues { it >= -1 }
        val failed = measurable.filterValues { it == -1L }.keys
        if (failed.isEmpty() || failed.size == measurable.size || failed.size * 2 > measurable.size) return Result(Outcome.Nothing, m)
        if (!netCheck()) return Result(Outcome.Whitelist, m)
        failed.forEach { m[it] = (m[it] ?: 0) + 1 }
        val del = failed.filter { it !in protect && (m[it] ?: 0) >= failsToDelete }.toSet()
        del.forEach { m.remove(it) }
        return Result(if (del.isEmpty()) Outcome.Nothing else Outcome.Deleted(del), m)
    }
    fun encode(m: Map<String, Int>): String = m.filterValues { it > 0 }.entries.joinToString(";") { "${it.key}=${it.value}" }
    fun decode(s: String?): Map<String, Int> = s.orEmpty().split(';').mapNotNull { e ->
        val k = e.substringBeforeLast('=', ""); val v = e.substringAfterLast('=', "").toIntOrNull(); if (k.isEmpty() || v == null) null else k to v }.toMap()
    val PROBE_URLS = listOf("https://www.google.com/generate_204", "https://cp.cloudflare.com/generate_204", "https://www.gstatic.com/generate_204")
}
