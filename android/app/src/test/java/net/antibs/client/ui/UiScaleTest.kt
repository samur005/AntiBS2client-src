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
    @Test fun fontScaleCapped() {
        assertEquals(1.3f, UiScale.fontScale(2f), 0.001f)
        assertEquals(1.15f, UiScale.fontScale(1.15f), 0.001f)
    }
}
