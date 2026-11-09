package net.antibs.client.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar

class AbsFeaturesTest {
    private val GB = 1024L * 1024 * 1024

    @Test fun subLine() {
        val now = 1_700_000_000_000L
        val sub = Subscription(id = "s", url = "u", upload = 0, download = (1.4 * GB).toLong(), total = 150 * GB, expireEpoch = now / 1000 + 122 * 86400 + 100)
        val d = SubLineData.of(sub, now)!!
        assertEquals("дня 122", d.days)
        val sub115 = sub.copy(expireEpoch = now / 1000 + 115 * 86400 + 100)
        assertEquals("дней 115", SubLineData.of(sub115, now)!!.days)
        assertTrue(Regex("""до \d{2}\.\d{2}\.\d{4}""").matches(SubLineData.of(sub115, now)!!.until!!))
        assertEquals("1,4 / 150 ГБ", d.traffic)
        assertTrue(d.until!!.startsWith("до "))
        assertTrue("until should include year: ${d.until}", Regex("""до \d{2}\.\d{2}\.\d{4}""").matches(d.until!!))
        assertEquals("05.03.2027".length, "05.03.2027".length) // format sanity
        assertEquals(10, net.antibs.client.data.SubLineData.ddMMyyyy(1_730_000_000L).length) // dd.MM.yyyy
        assertNull(SubLineData.of(Subscription(id = "x", url = "u"), now))
        assertEquals("∞", SubLineData.of(Subscription(id = "x", url = "u", download = GB), now)!!.days)
    }

    @Test fun grouping() {
        val names = listOf("🇫🇮 Helsinki", "🇩🇪 Berlin", "plain", "🇫🇮 Espoo")
        assertEquals("FI", ServerGrouping.countryCode(names[0]))
        assertNull(ServerGrouping.countryCode("plain"))
        val g = ServerGrouping.group(names, { it }, ServerGrouping.SORT_COUNT)
        assertEquals(listOf("FI", "DE", ServerGrouping.OTHER), g.map { it.first })
        val sg = ServerGrouping(on = true)
        assertTrue(sg.isOn("s"))
        val t = sg.toggle("s", "FI")
        assertNotEquals(sg.isOpen("s", "FI"), t.isOpen("s", "FI"))
        assertEquals(42L, ServerGrouping.bestPing(listOf(-1, null, 42, 90)))
    }

    @Test fun schedule() {
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 7, 0, 0); set(Calendar.MILLISECOND, 0) } // Monday
        val r = ScheduleRule(id = 1, hour = 8, minute = 0, days = 1) // Mon only
        val cfg = VpnScheduleCfg(on = true, rules = listOf(r))
        val (at, _) = cfg.next(c.timeInMillis)!!
        assertEquals(3_600_000L, at - c.timeInMillis)
        assertNotNull(cfg.due(at + 30_000))
        assertNull(cfg.due(at - 30_000))
        assertNull(VpnScheduleCfg(on = false, rules = listOf(r)).next(c.timeInMillis))
    }

    @Test fun seasonal() {
        val fx = SeasonalFx()
        assertTrue(fx.snowOn(LocalDate.of(2026, 12, 20), "auto"))
        assertFalse(fx.snowOn(LocalDate.of(2026, 7, 1), "auto"))
        assertTrue(fx.garlandOn(true))
        assertFalse(fx.copy(effects = SeasonalEffectsMode.OFF).garlandOn(true))
    }

    @Test fun ping() {
        assertEquals("пинг: 131 мс", PingChipStyle.label(131, false))
        assertEquals("пинг: таймаут", PingChipStyle.label(-1, false))
        assertEquals(0, PingChipStyle.tone(131)); assertEquals(1, PingChipStyle.tone(300)); assertEquals(2, PingChipStyle.tone(900))
        assertEquals("icmp", PingRoute.method("tcp", "hysteria2", null, null))
        assertEquals("tcp", PingRoute.method("tcp", "vless", "tcp", null))
        assertEquals("url", PingRoute.method("url", "hysteria2", null, null))
    }

    @Test fun autoDelete() = runBlocking {
        val res = mapOf("a" to -1L, "b" to 50L, "c" to 60L, "d" to 70L)
        var counts = emptyMap<String, Int>()
        repeat(4) { counts = AutoDeletePing.decide(res, emptySet(), true, counts, 5) { true }.counts }
        assertEquals(4, counts["a"])
        val r = AutoDeletePing.decide(res, emptySet(), true, counts, 5) { true }
        assertEquals(AutoDeletePing.Outcome.Deleted(setOf("a")), r.outcome)
        // protected / net check failed / everything failed → not counted
        assertEquals(AutoDeletePing.Outcome.Whitelist, AutoDeletePing.decide(res, emptySet(), true, counts, 5) { false }.outcome)
        assertEquals(AutoDeletePing.Outcome.Nothing, AutoDeletePing.decide(res, setOf("a"), true, mapOf("a" to 9), 5) { true }.outcome)
        assertEquals(AutoDeletePing.Outcome.Nothing, AutoDeletePing.decide(mapOf("a" to -1L, "b" to -1L), emptySet(), true, emptyMap(), 1) { true }.outcome)
        assertEquals(mapOf("x" to 2, "y" to 1), AutoDeletePing.decode(AutoDeletePing.encode(mapOf("x" to 2, "y" to 1))))
    }
}
