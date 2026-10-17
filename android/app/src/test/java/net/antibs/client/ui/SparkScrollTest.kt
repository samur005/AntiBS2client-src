package net.antibs.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkScrollTest {
    @Test fun samplesScrollContinuouslyAtConstantSpeed() {
        val step = 10f
        // a sample sits one step beyond the head when it arrives, at the head one interval later, then moves left
        assertEquals(110f, UiLogic.sparkX(0, 0, 100f, step), 1e-4f)
        assertEquals(100f, UiLogic.sparkX(1000, 0, 100f, step), 1e-4f)
        assertEquals(95f, UiLogic.sparkX(1500, 0, 100f, step), 1e-4f)
        // constant speed: equal time → equal distance, regardless of when the next tick comes
        val a = UiLogic.sparkX(2000, 0, 100f, step) - UiLogic.sparkX(2016, 0, 100f, step)
        val b = UiLogic.sparkX(5000, 0, 100f, step) - UiLogic.sparkX(5016, 0, 100f, step)
        assertEquals(a, b, 1e-4f)
    }

    @Test fun headFractionEasesAndClamps() {
        assertEquals(0f, UiLogic.headFraction(1000, 1000), 0f)
        assertEquals(0.5f, UiLogic.headFraction(1500, 1000), 1e-4f)
        assertEquals(1f, UiLogic.headFraction(5000, 1000), 0f)
        assertTrue(UiLogic.headFraction(1100, 1000) < 0.1f) // eased start
        assertTrue(UiLogic.headPulse(12345) in 0f..1f)
    }

    @Test fun bezierPrefixEndsOnTheCurve() {
        val c = floatArrayOf(0f, 0f, 1f, 3f, 2f, 3f, 3f, 0f)
        val full = UiLogic.bezierPrefix(c, 1f)
        assertEquals(3f, full[6], 1e-5f); assertEquals(0f, full[7], 1e-5f)
        val zero = UiLogic.bezierPrefix(c, 0f)
        assertEquals(0f, zero[6], 1e-5f); assertEquals(0f, zero[7], 1e-5f)
        val half = UiLogic.bezierPrefix(c, 0.5f)
        // B(0.5) = (1.5, 2.25) for this curve
        assertEquals(1.5f, half[6], 1e-5f); assertEquals(2.25f, half[7], 1e-5f)
    }
}
