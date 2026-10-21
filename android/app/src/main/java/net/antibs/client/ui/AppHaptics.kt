package net.antibs.client.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalView

/**
 * 1.0.55: all app haptics in one place — a light premium tick for navigation (tab / page / pager / drag pick-up) and a
 * slightly firmer click for toggles / confirm. Never a long one-shot buzz. Respects «Вибрация» and the system
 * touch-feedback setting (USAGE_TOUCH on Android 13+, so the system touch intensity applies).
 */
object AppHaptics {
    enum class Kind { TICK, CLICK }

    /** Pure rule (unit tested): tick only when the value really changed and «Вибрация» is on. */
    fun shouldTick(prev: Any?, next: Any?, enabled: Boolean): Boolean = enabled && prev != null && prev != next

    /** Compose types → our two kinds: LongPress (power button, long-press confirm) = CLICK, everything else = TICK. */
    fun kindOf(t: HapticFeedbackType): Kind = if (t == HapticFeedbackType.LongPress) Kind.CLICK else Kind.TICK

    /** Composition primitive scale per kind (API 30+). */
    fun scale(k: Kind): Float = if (k == Kind.TICK) 0.6f else 0.7f

    fun perform(view: View, kind: Kind, enabled: Boolean) {
        if (!enabled) return
        val ctx = view.context
        val sysOn = runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 }.getOrDefault(true)
        if (!sysOn) return
        if (vibrate(ctx, kind)) return
        view.performHapticFeedback(if (kind == Kind.TICK) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private fun vibrate(ctx: Context, kind: Kind): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) return false
        if (ctx.checkSelfPermission(android.Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) return false
        val v = vibrator(ctx)?.takeIf { it.hasVibrator() } ?: return false
        val effect: VibrationEffect = if (Build.VERSION.SDK_INT >= 30) {
            val prim = when {
                kind == Kind.CLICK && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) -> VibrationEffect.Composition.PRIMITIVE_CLICK
                kind == Kind.TICK && Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                kind == Kind.TICK && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) -> VibrationEffect.Composition.PRIMITIVE_TICK
                else -> -1
            }
            if (prim >= 0) {
                // LOW_TICK is softer by design, so it gets a bit more scale
                val sc = if (prim == 7 /* PRIMITIVE_LOW_TICK */) 0.8f else scale(kind)
                VibrationEffect.startComposition().addPrimitive(prim, sc).compose()
            } else VibrationEffect.createPredefined(if (kind == Kind.TICK) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK)
        } else VibrationEffect.createPredefined(if (kind == Kind.TICK) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
        else v.vibrate(effect)
        true
    }.getOrDefault(false)

    /** Replaces LocalHapticFeedback app-wide so every Compose haptic goes through [perform]. */
    fun feedback(view: View, enabled: Boolean): HapticFeedback = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = perform(view, kindOf(hapticFeedbackType), enabled)
    }

    /** One tick each time [key] changes after the first composition. */
    @Composable
    fun TickOnChange(key: Any, enabled: Boolean) {
        val view = LocalView.current
        val last = remember { arrayOfNulls<Any>(1) }
        LaunchedEffect(key) {
            if (shouldTick(last[0], key, enabled)) perform(view, Kind.TICK, true)
            last[0] = key
        }
    }
}
