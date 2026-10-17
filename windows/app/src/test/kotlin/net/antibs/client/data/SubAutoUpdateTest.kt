package net.antibs.client.data

import kotlinx.serialization.json.Json
import net.antibs.client.engine.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAutoUpdateTest {
    private val H = 3_600_000L
    private val now = 1_800_000_000_000L
    private fun sub(id: String, last: Long, kind: String = "auto", url: String = "https://x/$id", header: Int = 0) =
        Subscription(id = id, url = url, kind = kind, lastRefresh = last, updateIntervalHours = header)

    @Test fun upgradingUsersGetDefaultOnEvery6h() {
        // a 1.0.24 save has none of the new keys
        val old = """{"subscriptions":[],"autoUpdateCheck":true,"rev":5}"""
        val st = Json { ignoreUnknownKeys = true }.decodeFromString(AppState.serializer(), old)
        assertTrue(st.subAutoUpdate)
        assertEquals(6, st.subUpdateHours)
        assertFalse(st.subUpdateWifiOnly)
        assertTrue(AppState().subAutoUpdate)
        assertEquals(6, AppState().subUpdateHours)
    }

    @Test fun hoursAreNormalized() {
        assertEquals(6, SubAutoUpdate.normalizeHours(0))
        assertEquals(6, SubAutoUpdate.normalizeHours(-3))
        assertEquals(168, SubAutoUpdate.normalizeHours(1000))
        assertEquals(12, SubAutoUpdate.normalizeHours(12))
        assertEquals(6 * H, SubAutoUpdate.intervalMs(6))
        assertEquals(listOf(1, 3, 6, 12, 24), SubAutoUpdate.PRESETS)
        assertEquals(48, SubAutoUpdate.parseCustomHours(" 48 "))
        assertNull(SubAutoUpdate.parseCustomHours("0"))
        assertNull(SubAutoUpdate.parseCustomHours("169"))
        assertNull(SubAutoUpdate.parseCustomHours("abc"))
        assertEquals("Каждый час", SubAutoUpdate.label(1))
        assertEquals("Каждые 6 ч", SubAutoUpdate.label(6))
        assertTrue(SubAutoUpdate.isPreset(24)); assertFalse(SubAutoUpdate.isPreset(48))
    }

    @Test fun dueAfterIntervalWithSlack() {
        assertTrue(SubAutoUpdate.isDue(0, now, 6))                      // never fetched
        assertFalse(SubAutoUpdate.isDue(now - 5 * H, now, 6))
        assertTrue(SubAutoUpdate.isDue(now - 6 * H, now, 6))
        assertTrue(SubAutoUpdate.isDue(now - 6 * H + 5 * 60_000, now, 6))  // periodic run fired 5 min early
        assertFalse(SubAutoUpdate.isDue(now - 6 * H + 20 * 60_000, now, 6))
        assertTrue(SubAutoUpdate.isDue(now + H, now, 6))                // clock moved back
        assertTrue(SubAutoUpdate.isDue(now - 2 * H, now, 1))
    }

    @Test fun dueSubscriptionsSkipManualAndHonourGlobalSetting() {
        val subs = listOf(
            sub("old", now - 7 * H),
            sub("fresh", now - H, header = 1),          // panel says hourly, user says 6 h → user wins
            sub("manual", 0, kind = "manual", url = ""),
            sub("blank", 0, url = ""),
        )
        assertEquals(listOf("old"), SubAutoUpdate.dueSubscriptions(subs, now, true, 6).map { it.id })
        assertEquals(listOf("old", "fresh"), SubAutoUpdate.dueSubscriptions(subs, now, true, 1).map { it.id })
        assertTrue(SubAutoUpdate.dueSubscriptions(subs, now, false, 1).isEmpty())
        assertEquals(now - H, SubAutoUpdate.lastUpdate(subs))
        assertEquals(0L, SubAutoUpdate.lastUpdate(emptyList()))
    }

    @Test fun headerIsOnlyAHint() {
        assertNull(SubAutoUpdate.headerHint(listOf(sub("a", 0))))
        val h = SubAutoUpdate.headerHint(listOf(sub("a", 0, header = 12), sub("b", 0, header = 1), sub("c", 0, header = 12)))!!
        assertTrue(h, h.contains("каждый час / каждые 12 ч"))
    }

    @Test fun schedulingPlan() {
        assertNull(SubAutoUpdate.plan(false, 6, false))
        assertEquals(SubAutoUpdate.Plan(6, false), SubAutoUpdate.plan(true, 6, false))
        assertEquals(SubAutoUpdate.Plan(3, true), SubAutoUpdate.plan(true, 3, true))
        assertEquals(SubAutoUpdate.Plan(6, false), SubAutoUpdate.plan(true, 0, false))
        assertTrue(SubAutoUpdate.mayRunNow(false, false))
        assertTrue(SubAutoUpdate.mayRunNow(true, true))
        assertFalse(SubAutoUpdate.mayRunNow(true, false))
    }

    @Test fun reconnectOnlyWhenNeeded() {
        val p = XrayProfile(remarks = "Москва", protocol = "vless", server = "1.2.3.4", port = 443, id = "u1")
        val e = ServerEntry("s1", EngineKind.XRAY, "Москва", "sub", xray = p)
        assertFalse(SubAutoUpdate.hostConfigChanged(e, e.copy()))
        // renamed only (e.g. traffic in the name) → no reconnect
        assertFalse(SubAutoUpdate.hostConfigChanged(e, e.copy(name = "Москва 5 ГБ", xray = p.copy(remarks = "Москва 5 ГБ"))))
        assertTrue(SubAutoUpdate.hostConfigChanged(e, e.copy(xray = p.copy(server = "5.6.7.8"))))
        assertTrue(SubAutoUpdate.hostConfigChanged(e, null))       // host removed by the panel
        assertFalse(SubAutoUpdate.hostConfigChanged(null, e))
        assertFalse(SubAutoUpdate.shouldReconnect(false, true, true))
        assertFalse(SubAutoUpdate.shouldReconnect(true, false, false))
        assertTrue(SubAutoUpdate.shouldReconnect(true, true, false))
        assertTrue(SubAutoUpdate.shouldReconnect(true, false, true))
    }
}
