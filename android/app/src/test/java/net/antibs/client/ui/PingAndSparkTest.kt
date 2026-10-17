package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.XrayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.10: auto-ping targets and sparkline smoothing helpers. */
class PingAndSparkTest {
    private fun host(id: String, sub: String?, name: String = id, proto: String = "vless") =
        ServerEntry(id, EngineKind.XRAY, name, sub, xray = XrayProfile(name, proto, "h", 443))

    private val servers = listOf(
        host("a1", "subA"), host("a2", "subA"),
        host("auto", "subA", "🎲Автовыбор сервера"),
        host("bal", "subA", "Fastest", proto = "balancer"),
        host("b1", "subB"),
        host("m1", ProtocolSupport.MANUAL_SUB_ID),
        host("o1", null),
    )

    @Test fun pingTargetsIncludeAutoSelectAndSkipBusy() {
        // 1.0.18: auto-select / balancer entries are listed again, so they're pinged too.
        assertEquals(listOf("a1", "a2", "auto", "bal"), UiLogic.pingTargets(servers, "subA").map { it.id })
        assertEquals(listOf("a2", "auto", "bal"), UiLogic.pingTargets(servers, "subA", busy = setOf("a1")).map { it.id })
        assertEquals(listOf("a1", "a2", "auto", "bal", "b1", "m1", "o1"), UiLogic.pingTargets(servers).map { it.id })
    }

    @Test fun subscriptionPingIconBusyOnlyForItsOwnHosts() {
        // 1.0.20: the card's ping icon spins/disables while any host of THAT subscription is measured
        assertTrue(UiLogic.subPinging(servers, "subA", setOf("a2")))
        assertFalse(UiLogic.subPinging(servers, "subA", setOf("b1", "m1")))
        assertFalse(UiLogic.subPinging(servers, "subA", emptySet()))
        assertTrue(UiLogic.subPinging(servers, "subB", setOf("b1")))
    }

    @Test fun pingTargetsNotRefreshingDefersRefreshedSubs() {
        // subA and subB are being fetched → only manual + orphan hosts are pinged right away.
        assertEquals(listOf("m1", "o1"), UiLogic.pingTargetsNotRefreshing(servers, setOf("subA", "subB")).map { it.id })
        assertEquals(listOf("b1", "m1", "o1"), UiLogic.pingTargetsNotRefreshing(servers, setOf("subA")).map { it.id })
    }

    @Test fun emaSmoothsAndClamps() {
        assertEquals(10f, UiLogic.emaNext(null, 10f), 0f)
        assertEquals(13.5f, UiLogic.emaNext(10f, 20f, 0.35f), 1e-4f)
        assertEquals(0f, UiLogic.emaNext(null, -5f), 0f)
        assertEquals(0f, UiLogic.emaNext(null, Float.NaN), 0f)
        val buf = mutableListOf<Float>()
        repeat(100) { UiLogic.pushSmoothed(buf, 1000f) }
        assertEquals(UiLogic.SPARK_HISTORY + 2, buf.size)
        assertTrue(buf.last() <= 1000f && buf.last() > 999f)
    }

    @Test fun monotoneTangentsDoNotOvershoot() {
        val y = listOf(0f, 0f, 10f, 10f, 3f, 3f)
        val m = UiLogic.monotoneTangents(y)
        // Flat runs and local extrema get zero slope → the curve cannot dip below 0 or above a peak.
        assertEquals(0f, m[0], 0f); assertEquals(0f, m[1], 0f)
        assertEquals(0f, m[2], 0f); assertEquals(0f, m[3], 0f)
        // Every Hermite segment stays within its endpoints.
        for (i in 0 until y.size - 1) {
            val lo = minOf(y[i], y[i + 1]); val hi = maxOf(y[i], y[i + 1])
            for (k in 1..9) {
                val t = k / 10f
                val h00 = 2 * t * t * t - 3 * t * t + 1; val h10 = t * t * t - 2 * t * t + t
                val h01 = -2 * t * t * t + 3 * t * t; val h11 = t * t * t - t * t
                val v = h00 * y[i] + h10 * m[i] + h01 * y[i + 1] + h11 * m[i + 1]
                assertTrue("segment $i t=$t v=$v", v >= lo - 1e-3f && v <= hi + 1e-3f)
            }
        }
        assertEquals(0, UiLogic.monotoneTangents(emptyList()).size)
    }

    @Test fun sparkScaleHasHeadroomAndFloor() {
        assertEquals(1f, UiLogic.sparkScale(emptyList(), emptyList()), 0f)
        assertEquals(115f, UiLogic.sparkScale(listOf(10f, 100f), listOf(50f)), 1e-3f)
    }
}
