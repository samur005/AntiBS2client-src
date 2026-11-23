package net.antibs.client.ui

import net.antibs.client.data.PowerButtonStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerButtonLogicTest {
    private val def = PowerButtonStyle()

    @Test fun defaultsAreCalmBreathAndMotionOn() {
        assertEquals(PowerButtonStyle.COLOR_AUTO, def.color)
        assertEquals(PowerButtonStyle.COLOR_AUTO, def.textColor)
        assertEquals(PowerButtonStyle.EFFECT_BREATH, def.effect)
        assertTrue(def.anim)
    }

    @Test fun normalizeFallsBackSafely() {
        assertEquals("auto", PowerButtonStyle.normalizeColor(null))
        assertEquals("accent", PowerButtonStyle.normalizeColor(" Accent "))
        assertEquals("teal", PowerButtonStyle.normalizeColor("teal"))
        assertEquals("auto", PowerButtonStyle.normalizeColor("magenta"))
        assertEquals("white", PowerButtonStyle.normalizeText("WHITE"))
        assertEquals("breath", PowerButtonStyle.normalizeEffect(null))
        assertEquals("none", PowerButtonStyle.normalizeEffect("none"))
        assertEquals("shimmer", PowerButtonStyle.normalizeEffect("shimmer"))
        assertEquals("Дыхание", PowerButtonStyle.effectLabel("breath"))
    }

    @Test fun livingEffectOnlyWhenConnectedHealthyVisibleAndAnimated() {
        assertTrue(PowerButtonLogic.shouldLive(true, false, def, motionOk = true, visible = true))
        assertFalse(PowerButtonLogic.shouldLive(false, false, def, true, true))
        assertFalse(PowerButtonLogic.shouldLive(true, true, def, true, true))
        assertFalse(PowerButtonLogic.shouldLive(true, false, def.copy(anim = false), true, true))
        assertFalse(PowerButtonLogic.shouldLive(true, false, def, motionOk = false, visible = true))
        assertFalse(PowerButtonLogic.shouldLive(true, false, def, true, visible = false))
        assertFalse(PowerButtonLogic.shouldLive(true, false, def.copy(effect = PowerButtonStyle.EFFECT_NONE), true, true))
        assertTrue(PowerButtonLogic.shouldLive(true, false, def.copy(effect = PowerButtonStyle.EFFECT_GLOW), true, true))
    }

    @Test fun animationsFollowToggleAndSystemFlag() {
        assertTrue(PowerButtonLogic.shouldAnimate(def, true))
        assertFalse(PowerButtonLogic.shouldAnimate(def, false))
        assertFalse(PowerButtonLogic.shouldAnimate(def.copy(anim = false), true))
    }
}
