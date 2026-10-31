package net.antibs.client.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.*
import org.junit.Test

class AppHapticsTest {
    @Test fun tickOnlyOnRealChange() {
        assertFalse(AppHaptics.shouldTick(null, MainTab.Home, true))       // first show
        assertFalse(AppHaptics.shouldTick(MainTab.Home, MainTab.Home, true)) // same tab (no double)
        assertTrue(AppHaptics.shouldTick(MainTab.Home, MainTab.Servers, true))
        assertFalse(AppHaptics.shouldTick(MainTab.Home, MainTab.Servers, false)) // «Вибрация» off
    }
    @Test fun kindsAndScale() {
        assertEquals(AppHaptics.Kind.CLICK, AppHaptics.kindOf(HapticFeedbackType.LongPress))
        assertEquals(AppHaptics.Kind.TICK, AppHaptics.kindOf(HapticFeedbackType.TextHandleMove))
        assertTrue(AppHaptics.scale(AppHaptics.Kind.TICK) in 0.5f..0.7f)
        assertTrue(AppHaptics.scale(AppHaptics.Kind.CLICK) >= AppHaptics.scale(AppHaptics.Kind.TICK))
    }
}
