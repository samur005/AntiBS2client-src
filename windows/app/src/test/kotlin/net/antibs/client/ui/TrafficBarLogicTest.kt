package net.antibs.client.ui

import androidx.compose.ui.graphics.Color
import net.antibs.client.data.TrafficBarStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficBarLogicTest {
    private val def = TrafficBarStyle()
    private val accent = Color(0xFF4C8DFF)

    @Test fun defaultsMatchCurrentLook() {
        assertEquals(14, def.heightDp)
        assertEquals("auto", def.color)
        assertEquals("auto", def.gradient)
        assertEquals("auto", def.color2)
        assertEquals(14, TrafficBarLogic.heightDp(def))
        assertTrue(TrafficBarLogic.labelInside(14))
    }

    @Test fun heightClampsAndLabelPlacement() {
        assertEquals(2, TrafficBarStyle.normalizeHeight(0))
        assertEquals(16, TrafficBarStyle.normalizeHeight(99))
        assertEquals(9, TrafficBarStyle.normalizeHeight(9))
        assertFalse(TrafficBarLogic.labelInside(2))
        assertFalse(TrafficBarLogic.labelInside(9))
        assertTrue(TrafficBarLogic.labelInside(10))
        assertTrue(TrafficBarLogic.labelInside(16))
    }

    @Test fun normalizeColorAndGradient() {
        assertEquals("auto", TrafficBarStyle.normalizeColor(null))
        assertEquals("accent", TrafficBarStyle.normalizeColor(" Accent "))
        assertEquals("teal", TrafficBarStyle.normalizeColor("teal"))
        assertEquals("auto", TrafficBarStyle.normalizeColor("magenta"))
        assertEquals("auto", TrafficBarStyle.normalizeGradient(null))
        assertEquals("off", TrafficBarStyle.normalizeGradient("OFF"))
        assertEquals("on", TrafficBarStyle.normalizeGradient("on"))
        assertEquals("Авто", TrafficBarStyle.gradientLabel("auto"))
        assertEquals("Нет", TrafficBarStyle.gradientLabel("off"))
        assertEquals("Авто", TrafficBarLogic.colorLabel("auto"))
        assertEquals("Акцент", TrafficBarLogic.colorLabel("accent"))
        assertEquals("Синий", TrafficBarLogic.colorLabel("blue"))
    }

    @Test fun defaultGradientIsRemainingBased() {
        val full = TrafficBarLogic.fillColors(def, 0.8f, accent)
        assertEquals(2, full.size)
        assertEquals(AntiBSColors.Blue, full[0])
        assertEquals(AntiBSColors.Purple, full[1])
        val warn = TrafficBarLogic.fillColors(def, 0.25f, accent)
        assertEquals(AntiBSColors.Blue, warn[0])
        assertEquals(AntiBSColors.Warn, warn[1])
        val low = TrafficBarLogic.fillColors(def, 0.05f, accent)
        assertEquals(AntiBSColors.Warn, low[0])
        assertEquals(AntiBSColors.Err, low[1])
    }

    @Test fun solidCustomAndTwoColorGradient() {
        val solid = TrafficBarStyle(color = "green", gradient = "off")
        val s = TrafficBarLogic.fillColors(solid, 0.8f, accent)
        assertEquals(1, s.size)
        assertEquals(Color(Accent.argbOf("green").toInt()), s[0])

        val grad = TrafficBarStyle(color = "blue", gradient = "on", color2 = "teal")
        val g = TrafficBarLogic.fillColors(grad, 0.8f, accent)
        assertEquals(2, g.size)
        assertEquals(Color(Accent.argbOf("blue").toInt()), g[0])
        assertEquals(Color(Accent.argbOf("teal").toInt()), g[1])
    }
}
