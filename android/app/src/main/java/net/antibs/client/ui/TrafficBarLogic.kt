package net.antibs.client.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import net.antibs.client.data.TrafficBarStyle

/** Pure helpers for the 2.2.1 subscription traffic bar (safe to unit-test). */
object TrafficBarLogic {

    fun heightDp(prefs: TrafficBarStyle): Int = TrafficBarStyle.normalizeHeight(prefs.heightDp)

    /** Text fits inside the track only when the bar is tall enough (~10 dp+). */
    fun labelInside(heightDp: Int): Boolean = TrafficBarStyle.normalizeHeight(heightDp) >= 10

    fun labelSize(heightDp: Int): TextUnit {
        val h = TrafficBarStyle.normalizeHeight(heightDp)
        return when {
            !labelInside(h) -> 10.sp
            h >= 15 -> 10.sp
            h >= 13 -> 9.5.sp
            h >= 11 -> 8.5.sp
            else -> 8.sp
        }
    }

    fun colorLabel(key: String): String = when (TrafficBarStyle.normalizeColor(key)) {
        TrafficBarStyle.COLOR_AUTO -> "Авто"
        TrafficBarStyle.COLOR_ACCENT -> "Акцент"
        else -> Accent.label(key)
    }

    fun gradientLabel(key: String): String = TrafficBarStyle.gradientLabel(key)

    fun resolveColor(key: String, accent: Color): Color? {
        return when (TrafficBarStyle.normalizeColor(key)) {
            TrafficBarStyle.COLOR_AUTO -> null
            TrafficBarStyle.COLOR_ACCENT -> accent
            else -> Color(Accent.argbOf(key).toInt())
        }
    }

    fun pairColor(colorKey: String, start: Color, accent: Color): Color {
        val key = TrafficBarStyle.normalizeColor(colorKey)
        return when (key) {
            TrafficBarStyle.COLOR_AUTO -> AntiBSColors.Purple
            TrafficBarStyle.COLOR_ACCENT -> Accent.deep(accent)
            "blue" -> AntiBSColors.Purple
            "purple" -> Color(0xFF4C8DFF)
            "pink" -> Color(0xFF7A5CFF)
            "red" -> Color(0xFFFF6D00)
            "orange" -> Color(0xFFFFC107)
            "amber" -> Color(0xFF0F9D58)
            "green" -> Color(0xFF009688)
            "teal" -> Color(0xFF4C8DFF)
            else -> Accent.deep(start)
        }
    }

    /**
     * Fill colours for the remaining-traffic share [remaining] (0 = empty, 1 = full / unlimited).
     * Default (auto colour + auto gradient) is the 2.2.0 remaining-based Blue→Purple / warn / error look.
     */
    fun fillColors(prefs: TrafficBarStyle, remaining: Float, accent: Color): List<Color> {
        val p = remaining.coerceIn(0f, 1f)
        val mode = TrafficBarStyle.normalizeGradient(prefs.gradient)
        val colorKey = TrafficBarStyle.normalizeColor(prefs.color)
        val start = resolveColor(prefs.color, accent)
        val endCustom = resolveColor(prefs.color2, accent)

        if (colorKey == TrafficBarStyle.COLOR_AUTO && (mode == TrafficBarStyle.GRADIENT_AUTO || mode == TrafficBarStyle.GRADIENT_ON)) {
            return remainingAuto(p)
        }
        if (colorKey == TrafficBarStyle.COLOR_AUTO && mode == TrafficBarStyle.GRADIENT_OFF) {
            return listOf(remainingSolid(p))
        }

        val c1 = start ?: AntiBSColors.Blue
        if (mode == TrafficBarStyle.GRADIENT_OFF) return listOf(c1)
        val c2 = endCustom ?: pairColor(colorKey, c1, accent)
        return listOf(c1, c2)
    }

    fun fillBrush(prefs: TrafficBarStyle, remaining: Float, accent: Color): Brush {
        val colors = fillColors(prefs, remaining, accent)
        return if (colors.size == 1) Brush.horizontalGradient(listOf(colors[0], colors[0]))
        else Brush.horizontalGradient(colors)
    }

    private fun remainingAuto(p: Float): List<Color> = when {
        p <= 0.1f -> listOf(AntiBSColors.Warn, AntiBSColors.Err)
        p <= 0.3f -> listOf(AntiBSColors.Blue, AntiBSColors.Warn)
        else -> listOf(AntiBSColors.Blue, AntiBSColors.Purple)
    }

    private fun remainingSolid(p: Float): Color = when {
        p <= 0.1f -> AntiBSColors.Err
        p <= 0.3f -> AntiBSColors.Warn
        else -> AntiBSColors.Blue
    }
}
