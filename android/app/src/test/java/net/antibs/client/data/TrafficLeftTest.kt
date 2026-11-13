package net.antibs.client.data

import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.0.44: traffic bars show what is LEFT (shrink with use, full when unlimited); used amounts always carry units. */
class TrafficLeftTest {
    private val MB = 1024L * 1024; private val GB = 1024 * MB

    @Test fun subLineBarShrinksWithUse() {
        assertEquals(1f, SubLineData.remainingFrac(0, 100 * GB), 1e-4f)
        assertEquals(0.75f, SubLineData.remainingFrac(25 * GB, 100 * GB), 1e-4f)
        assertEquals(0f, SubLineData.remainingFrac(120 * GB, 100 * GB), 1e-4f)
        assertEquals(1f, SubLineData.remainingFrac(195 * MB, 0), 1e-4f) // unlimited → full
        val now = 1_700_000_000_000L
        val lim = SubLineData.of(Subscription(id = "a", url = "u", download = 30 * GB, total = 120 * GB, expireEpoch = now / 1000 + 86400 * 3), now)!!
        assertEquals(0.75f, lim.trafficFrac!!, 1e-4f)
        val unl = SubLineData.of(Subscription(id = "b", url = "u", download = 195 * MB, expireEpoch = now / 1000 + 86400 * 3), now)!!
        assertEquals(1f, unl.trafficFrac!!, 1e-4f)
        assertEquals("195 МБ · безлимит", unl.traffic)
    }

    @Test fun usedAlwaysHasUnits() {
        assertEquals("195 МБ / 150 ГБ", SubLineData.pair(195 * MB, 150 * GB))
        assertEquals("114 ГБ / 150 ГБ", SubLineData.pair(114 * GB, 150 * GB))
        assertEquals("0 Б / 150 ГБ", SubLineData.pair(0, 150 * GB))
        assertEquals("195 МБ / ∞", UiLogic.trafficBarLabel(UiLogic.Traffic(195.0, 0.0, true)))
    }

    @Test fun cardBarIsRemaining() {
        assertEquals(1f, UiLogic.trafficRemaining(195.0, 0.0), 1e-4f)
        assertEquals(0.25f, UiLogic.trafficRemaining(75.0, 100.0), 1e-4f)
        assertEquals(0f, UiLogic.trafficRemaining(150.0, 100.0), 1e-4f)
    }
}
