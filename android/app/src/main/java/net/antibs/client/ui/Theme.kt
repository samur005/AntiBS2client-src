package net.antibs.client.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import net.antibs.client.engine.ThemeMode

/**
 * Dark theme is charcoal / navy with blue accents (VPS-style), not a full-screen blue-violet gradient.
 * [ThemeResolver] is pure so unit tests can prove a preference change flips the palette.
 */
object ThemeResolver {
    const val darkBackgroundArgb: Long = 0xFF0E1116
    const val lightBackgroundArgb: Long = 0xFFF3F4F6
    /** Old 1.0.0 dark canvas — must not come back as the dark background. */
    const val legacyVioletArgb: Long = 0xFF1A1B4B
    const val darkOnBackgroundArgb: Long = 0xFFE8EAED
    const val lightOnBackgroundArgb: Long = 0xFF1A1C1F
    const val darkOnSurfaceArgb: Long = 0xFFE8EAED
    const val lightOnSurfaceArgb: Long = 0xFF1A1C1F
    const val darkOnSurfaceVariantArgb: Long = 0xFF9AA3B2
    const val lightOnSurfaceVariantArgb: Long = 0xFF5C6570
    const val darkSurfaceArgb: Long = 0xFF1A1F27
    const val lightSurfaceArgb: Long = 0xFFFFFFFF

    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> systemDark
    }

    fun backgroundArgb(mode: ThemeMode, systemDark: Boolean): Long = when {
        mode == ThemeMode.AMOLED -> amoledBackgroundArgb
        isDark(mode, systemDark) -> darkBackgroundArgb
        else -> lightBackgroundArgb
    }

    /** 1.0.40: «AMOLED (чёрная)» — true black canvas, near-black cards. */
    const val amoledBackgroundArgb: Long = 0xFF000000
    const val amoledSurfaceArgb: Long = 0xFF0B0C0F

    /** Relative luminance 0..1. Dark-theme body text must stay well above the canvas. */
    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun darkTextIsReadable(): Boolean =
        luminance(darkOnBackgroundArgb) > 0.65 &&
            luminance(darkOnSurfaceArgb) > 0.65 &&
            luminance(darkOnSurfaceVariantArgb) > 0.30 &&
            luminance(darkBackgroundArgb) < 0.15 &&
            luminance(lightOnBackgroundArgb) < 0.20 &&
            luminance(lightOnSurfaceArgb) < 0.20 &&
            darkOnBackgroundArgb != 0xFF000000L &&
            darkOnSurfaceArgb != 0xFF000000L
}

/** 1.0.40: accent colour presets («Акцентный цвет»); "system" = Material You (Android 12+). */
object Accent {
    const val DEFAULT = "blue"
    val presets: List<Triple<String, String, Long>> = listOf(
        Triple(DEFAULT, "Синий", 0xFF4C8DFF), Triple("purple", "Фиолетовый", 0xFF7A5CFF), Triple("pink", "Розовый", 0xFFE91E63),
        Triple("red", "Красный", 0xFFE53935), Triple("orange", "Оранжевый", 0xFFFF6D00), Triple("amber", "Янтарный", 0xFFFFC107),
        Triple("green", "Зелёный", 0xFF0F9D58), Triple("teal", "Бирюзовый", 0xFF009688),
    )
    fun argbOf(key: String): Long = presets.firstOrNull { it.first == key }?.third ?: presets.first().third
    fun label(key: String): String = if (key == "system") "Как в системе (Material You)" else presets.firstOrNull { it.first == key }?.second ?: "Синий"

    var current by mutableStateOf(Color(0xFF4C8DFF))
    var isDefault by mutableStateOf(true)

    fun deep(c: Color): Color = Color(red = c.red * 0.82f, green = c.green * 0.82f, blue = c.blue * 0.88f, alpha = 1f)
    fun onColor(c: Color): Color = if (ThemeResolver.luminance(argbLong(c)) > 0.62) Color(0xFF1A1C1F) else Color.White
    private fun argbLong(c: Color): Long = (0xFFL shl 24) or ((c.red * 255).toLong() shl 16) or ((c.green * 255).toLong() shl 8) or (c.blue * 255).toLong()
}

object AntiBSColors {
    val BgDark = Color(ThemeResolver.darkBackgroundArgb)
    val BgLight = Color(ThemeResolver.lightBackgroundArgb)
    val Blue: Color get() = Accent.current
    val BlueDeep: Color get() = if (Accent.isDefault) Color(0xFF2F6BFF) else Accent.deep(Accent.current)
    val Purple = Color(0xFF7A5CFF)
    val Section = Color(0xFF8B7CFF)
    val Ok = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFB020)
    /** ↓ arrow on the speed card (1.0.13): yellow on dark, deeper amber on light surfaces. */
    val ArrowYellow = Color(0xFFF5C518)
    val ArrowYellowLight = Color(0xFFC99700)
    val Err = Color(0xFFFF5C7A)
    val Down = Color(0xFF5B8CFF)
    val Up = Color(0xFFFF8A4C)
    /** Sparkline: traffic that goes through the VPN proxy outbound. */
    val VpnGraph = Color(0xFF3D7EFF)
    /** Sparkline: traffic routed direct, bypassing the VPN proxy. */
    val DirectGraph = Color(0xFFFF3B4A)
    val FabStopped = Color(0xFF7D848E)
    /** Protocol chip when the VPN is off: neutral gray, white label (readable in both themes). */
    val ChipOff = Color(0xFF5F6670)
    /** Protocol chip when connected: deeper green than [Ok] so the white label stays readable. */
    val ChipOk = Color(0xFF1C9262)
    /** Live status dot (1.0.14): the protocol-chip green, a touch brighter so the small dot reads well. */
    val StatusGreen = Color(0xFF22A971)
    val fabBrush: Brush get() = if (Accent.isDefault) Brush.linearGradient(listOf(Color(0xFF4C8DFF), Color(0xFF6A5CFF), Color(0xFF9B4DFF)))
        else Brush.linearGradient(listOf(Accent.current, Accent.deep(Accent.current)))
}

private fun darkScheme(amoled: Boolean) = darkColorScheme(
    primary = AntiBSColors.Blue,
    onPrimary = Accent.onColor(AntiBSColors.Blue),
    secondary = AntiBSColors.Purple,
    onSecondary = Color.White,
    tertiary = AntiBSColors.Section,
    background = if (amoled) Color(ThemeResolver.amoledBackgroundArgb) else AntiBSColors.BgDark,
    onBackground = Color(ThemeResolver.darkOnBackgroundArgb),
    surface = Color(if (amoled) ThemeResolver.amoledSurfaceArgb else ThemeResolver.darkSurfaceArgb),
    onSurface = Color(ThemeResolver.darkOnSurfaceArgb),
    surfaceVariant = Color(if (amoled) 0xFF16181D else 0xFF2A3140),
    onSurfaceVariant = Color(ThemeResolver.darkOnSurfaceVariantArgb),
    outline = Color(if (amoled) 0xFF2A2D33 else 0xFF3A4250),
    error = AntiBSColors.Err,
)

private fun lightScheme() = lightColorScheme(
    primary = AntiBSColors.BlueDeep,
    onPrimary = Accent.onColor(AntiBSColors.BlueDeep),
    secondary = Color(0xFF6D5BD0),
    onSecondary = Color.White,
    tertiary = AntiBSColors.Section,
    background = AntiBSColors.BgLight,
    onBackground = Color(ThemeResolver.lightOnBackgroundArgb),
    surface = Color(ThemeResolver.lightSurfaceArgb),
    onSurface = Color(ThemeResolver.lightOnSurfaceArgb),
    surfaceVariant = Color(0xFFE6E8EC),
    onSurfaceVariant = Color(ThemeResolver.lightOnSurfaceVariantArgb),
    outline = Color(0xFFD5D8DE),
    error = AntiBSColors.Err,
)

@Composable
fun AntiBSTheme(mode: ThemeMode, accent: String = Accent.DEFAULT, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val ctx = LocalContext.current
    val accentColor = if (accent == "system" && android.os.Build.VERSION.SDK_INT >= 31) {
        (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)).primary
    } else Color(Accent.argbOf(accent))
    Accent.current = accentColor
    Accent.isDefault = accent == Accent.DEFAULT || (accent == "system" && android.os.Build.VERSION.SDK_INT < 31)
    val scheme = if (dark) darkScheme(mode == ThemeMode.AMOLED) else lightScheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    // Material3 does not set LocalContentColor, so it stays Color.Black and body text
    // disappears on the dark canvas. Force onBackground for both themes.
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

@Composable
fun isDark(mode: ThemeMode): Boolean = ThemeResolver.isDark(mode, isSystemInDarkTheme())
