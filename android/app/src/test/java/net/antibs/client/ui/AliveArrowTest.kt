package net.antibs.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.15: alive arrow activity and speed scaling. */
class AliveArrowTest {
    @Test fun arrowActiveOnlyWithTrafficWhileConnected() {
        assertFalse(UiLogic.arrowActive(1_000_000, connected = false))
        assertFalse(UiLogic.arrowActive(0, connected = true))
        assertFalse(UiLogic.arrowActive(UiLogic.ARROW_IDLE_BPS - 1, connected = true))
        assertTrue(UiLogic.arrowActive(UiLogic.ARROW_IDLE_BPS, connected = true))
    }

    @Test fun arrowPeriodShrinksWithSpeed() {
        assertEquals(0, UiLogic.arrowPeriodMs(0))
        val slow = UiLogic.arrowPeriodMs(1024)
        val mid = UiLogic.arrowPeriodMs(512L * 1024)
        val fast = UiLogic.arrowPeriodMs(50L * 1024 * 1024)
        assertEquals(1500, slow)
        assertTrue(mid in (fast + 1) until slow)
        assertEquals(550, fast)
    }
}
