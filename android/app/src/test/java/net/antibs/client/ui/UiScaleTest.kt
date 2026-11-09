package net.antibs.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleTest {
    @Test fun baselineIsOne() = assertEquals(1f, UiScale.scale(392, 850), 0.001f)
    @Test fun smallPhonesShrinkClamped() {
        assertEquals(360f / 392f, UiScale.scale(360, 800), 0.001f)
        assertEquals(UiScale.MIN, UiScale.scale(300, 600), 0.001f)
    }
    @Test fun wideAndLandscapeClamped() {
        assertEquals(UiScale.MAX, UiScale.scale(480, 1060), 0.001f)
        assertEquals(UiScale.MAX, UiScale.scale(1280, 800), 0.001f)
        assertEquals(360f / 392f, UiScale.scale(800, 360), 0.001f)
    }
    @Test fun fontScaleRespectsSystemAndUserPct() {
        assertEquals(1.0f, UiScale.fontScale(1f, 100), 0.001f)
        assertEquals(0.8f, UiScale.fontScale(1f, 80), 0.001f)
        assertEquals(1.3f, UiScale.fontScale(1f, 130), 0.001f)
        assertEquals(1.56f, UiScale.fontScale(1.2f, 130), 0.001f)
        assertEquals(UiScale.MAX_FONT_SCALE, UiScale.fontScale(2f, 130), 0.001f)
    }
    @Test fun snapFontPct() {
        assertEquals(100, UiScale.snapFontPct(100))
        assertEquals(110, UiScale.snapFontPct(112))
        assertEquals(80, UiScale.snapFontPct(79))
        assertEquals(130, UiScale.snapFontPct(200))
    }
}
