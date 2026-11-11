package net.antibs.client.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import net.antibs.client.data.PowerButtonStyle

/** Pure helpers for the 2.0.42 power-button appearance (safe to unit-test except [motionOk]). */
object PowerButtonLogic {

    fun motionOk(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        val a = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val t = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        a > 0f && t > 0f
    } catch (_: Throwable) {
        true
    }

    fun shouldAnimate(prefs: PowerButtonStyle, motionOk: Boolean): Boolean = prefs.anim && motionOk

    fun shouldLive(
        connected: Boolean,
        problem: Boolean,
        prefs: PowerButtonStyle,
        motionOk: Boolean,
        visible: Boolean,
    ): Boolean {
        if (!connected || problem || !visible) return false
        if (!shouldAnimate(prefs, motionOk)) return false
        return PowerButtonStyle.normalizeEffect(prefs.effect) != PowerButtonStyle.EFFECT_NONE
    }

    fun effectOf(prefs: PowerButtonStyle): String = PowerButtonStyle.normalizeEffect(prefs.effect)

    fun fillColor(colorKey: String, accent: Color): Color {
        val key = PowerButtonStyle.normalizeColor(colorKey)
        return when (key) {
            PowerButtonStyle.COLOR_AUTO -> Color(0xFF1DBF72)
            PowerButtonStyle.COLOR_ACCENT -> accent
            else -> Color(Accent.argbOf(key).toInt())
        }
    }

    fun fillBrush(colorKey: String, accent: Color): Brush {
        val key = PowerButtonStyle.normalizeColor(colorKey)
        return when (key) {
            PowerButtonStyle.COLOR_AUTO -> Brush.linearGradient(listOf(Color(0xFF3DDC97), Color(0xFF1DBF72)))
            PowerButtonStyle.COLOR_ACCENT -> Brush.linearGradient(listOf(accent, Accent.deep(accent)))
            else -> {
                val c = Color(Accent.argbOf(key).toInt())
                Brush.linearGradient(listOf(c, Accent.deep(c)))
            }
        }
    }

    fun onTextColor(textKey: String, fill: Color): Color {
        val key = PowerButtonStyle.normalizeText(textKey)
        return when (key) {
            PowerButtonStyle.COLOR_AUTO -> Accent.onColor(fill)
            PowerButtonStyle.TEXT_WHITE -> Color.White
            PowerButtonStyle.TEXT_BLACK -> Color(0xFF0F172A)
            else -> Color(Accent.argbOf(key).toInt())
        }
    }

    fun colorLabel(key: String): String = when (PowerButtonStyle.normalizeColor(key)) {
        PowerButtonStyle.COLOR_AUTO -> "Авто"
        PowerButtonStyle.COLOR_ACCENT -> "Акцент"
        else -> Accent.label(key)
    }

    fun textLabel(key: String): String = when (PowerButtonStyle.normalizeText(key)) {
        PowerButtonStyle.COLOR_AUTO -> "Авто"
        PowerButtonStyle.TEXT_WHITE -> "Белый"
        PowerButtonStyle.TEXT_BLACK -> "Чёрный"
        else -> Accent.label(key)
    }
}
