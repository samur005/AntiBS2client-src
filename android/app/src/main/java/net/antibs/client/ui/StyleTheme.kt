package net.antibs.client.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 2.3.30 (t139): the chosen style themes the whole app; screens can read it for extra accents. */
val LocalAppStyle = staticCompositionLocalOf { ServerStyle.CLASSIC }

/**
 * App-wide colour scheme of a style. CLASSIC returns [base] untouched (the current look).
 * Every style has a dark and a light variant and follows the theme mode setting; AMOLED keeps pure black
 * backgrounds. Design intent: Неон / Киберпанк are dark-first, Material You is light-first, the rest look the same in both.
 */
object StyleTheme {
    fun colors(style: ServerStyle, dark: Boolean, amoled: Boolean, base: ColorScheme): ColorScheme {
        val s = when (style) {
            ServerStyle.CLASSIC -> return base
            ServerStyle.NEON -> if (dark) base.copy(
                primary = Color(0xFF35E0FF), onPrimary = Color(0xFF041A22), primaryContainer = Color(0xFF0D2A36), onPrimaryContainer = Color(0xFFC8F6FF),
                secondary = Color(0xFFFF4FD8), onSecondary = Color(0xFF2A0522), secondaryContainer = Color(0xFF2A1030), onSecondaryContainer = Color(0xFFFFD6F5),
                tertiary = Color(0xFF34D399), background = Color(0xFF070A12), surface = Color(0xFF0B0F1A), surfaceVariant = Color(0xFF141A2A),
                onSurfaceVariant = Color(0xFF8B95AB), surfaceContainer = Color(0xFF0B0F1A), surfaceContainerLow = Color(0xFF0A0E18),
                surfaceContainerHigh = Color(0xFF111727), surfaceContainerHighest = Color(0xFF161D2E), outline = Color(0xFF1F3A48), outlineVariant = Color(0xFF16202E),
                surfaceTint = Color(0xFF35E0FF)
            ) else base.copy(
                primary = Color(0xFF0891B2), primaryContainer = Color(0xFFD6F6FD), onPrimaryContainer = Color(0xFF023544),
                secondary = Color(0xFFC026D3), secondaryContainer = Color(0xFFFBE3FF), onSecondaryContainer = Color(0xFF3B0A44),
                background = Color(0xFFF2F8FB), surfaceTint = Color(0xFF0891B2), outline = Color(0xFFB4D4DE)
            )
            ServerStyle.CARDS -> if (dark) base.copy(
                primary = Color(0xFF6CA0FF), primaryContainer = Color(0xFF1D2A4A), secondaryContainer = Color(0xFF171E31),
                tertiary = Color(0xFF2DD4BF), background = Color(0xFF0E1322), surface = Color(0xFF171E31), surfaceVariant = Color(0xFF1D2537)
            ) else base.copy(
                primary = Color(0xFF2557D6), primaryContainer = Color(0xFFE3ECFF), secondaryContainer = Color(0xFFEEF3FF),
                tertiary = Color(0xFF0F766E), background = Color(0xFFF3F6FB)
            )
            ServerStyle.AURORA -> if (dark) base.copy(
                primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2A), primaryContainer = Color(0xFF173C4A), onPrimaryContainer = Color(0xFFCCFBF1),
                secondary = Color(0xFFC4B5FD), secondaryContainer = Color(0xFF2A2456), onSecondaryContainer = Color(0xFFEDE9FE),
                tertiary = Color(0xFFF0ABFC), background = Color(0xFF14123A), surface = Color(0xFF1C1A48), surfaceVariant = Color(0xFF26235A),
                onSurfaceVariant = Color(0xFFB4BFD6), surfaceContainer = Color(0xFF1C1A48), surfaceContainerHigh = Color(0xFF242156),
                surfaceContainerHighest = Color(0xFF2B2862), outline = Color(0xFF3F3A7A), surfaceTint = Color(0xFF5EEAD4)
            ) else base.copy(
                primary = Color(0xFF0D9488), primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF042F2A),
                secondary = Color(0xFF7C3AED), secondaryContainer = Color(0xFFEDE9FE), onSecondaryContainer = Color(0xFF2E1065),
                tertiary = Color(0xFFDB2777), background = Color(0xFFEEF2FF), surfaceVariant = Color(0xFFE6EEFA), surfaceTint = Color(0xFF0D9488)
            )
            ServerStyle.CYBER -> if (dark) base.copy(
                primary = Color(0xFF00F0FF), onPrimary = Color(0xFF00242A), primaryContainer = Color(0xFF0A2A35), onPrimaryContainer = Color(0xFFC6FCFF),
                secondary = Color(0xFFFF2BD6), onSecondary = Color(0xFF2A0024), secondaryContainer = Color(0xFF2E0B3E), onSecondaryContainer = Color(0xFFFFD1F6),
                tertiary = Color(0xFFFFE600), background = Color(0xFF0B0614), surface = Color(0xFF140A22), surfaceVariant = Color(0xFF1E1030),
                onSurfaceVariant = Color(0xFF9D83B8), surfaceContainer = Color(0xFF140A22), surfaceContainerHigh = Color(0xFF1A0D2B),
                surfaceContainerHighest = Color(0xFF221238), outline = Color(0xFF3B1E54), outlineVariant = Color(0xFF2A1640), surfaceTint = Color(0xFFFF2BD6)
            ) else base.copy(
                primary = Color(0xFFC026D3), primaryContainer = Color(0xFFFBE3FF), onPrimaryContainer = Color(0xFF3B0A44),
                secondary = Color(0xFF0891B2), secondaryContainer = Color(0xFFD6F6FD), onSecondaryContainer = Color(0xFF023544),
                background = Color(0xFFFFF7FE), surfaceVariant = Color(0xFFF7E8FA), outline = Color(0xFFE5BFEB), surfaceTint = Color(0xFFC026D3)
            )
            ServerStyle.STACK -> if (dark) base.copy(
                primary = Color(0xFF8B8CFF), primaryContainer = Color(0xFF2A2A66), onPrimaryContainer = Color(0xFFE0E0FF),
                secondary = Color(0xFFF472B6), secondaryContainer = Color(0xFF3A1A33), tertiary = Color(0xFFFB923C)
            ) else base.copy(
                primary = Color(0xFF4F46E5), primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
                secondary = Color(0xFFDB2777), secondaryContainer = Color(0xFFFCE7F3), tertiary = Color(0xFFEA580C), background = Color(0xFFF1F2FB)
            )
            ServerStyle.MATERIAL -> if (dark) base.copy(
                primary = Color(0xFFFFB77C), onPrimary = Color(0xFF4A2800), primaryContainer = Color(0xFF5A3A12), onPrimaryContainer = Color(0xFFFFDCBE),
                secondary = Color(0xFFE3C0A5), secondaryContainer = Color(0xFF4A3B30), onSecondaryContainer = Color(0xFFFFDCC4),
                tertiary = Color(0xFFC1CC8F), background = Color(0xFF1E1A17), onBackground = Color(0xFFEDE0D6), surface = Color(0xFF26211D),
                onSurface = Color(0xFFEDE0D6), surfaceVariant = Color(0xFF332C26), onSurfaceVariant = Color(0xFFD6C3B3), surfaceContainer = Color(0xFF26211D),
                surfaceContainerHigh = Color(0xFF2E2823), surfaceContainerHighest = Color(0xFF39322C), outline = Color(0xFF6E5C4B), outlineVariant = Color(0xFF4A3F35),
                surfaceTint = Color(0xFFFFB77C)
            ) else base.copy(
                primary = Color(0xFF8B5A00), onPrimary = Color.White, primaryContainer = Color(0xFFFFDDB8), onPrimaryContainer = Color(0xFF2C1600),
                secondary = Color(0xFF725A42), secondaryContainer = Color(0xFFFDDDC0), onSecondaryContainer = Color(0xFF291806),
                tertiary = Color(0xFF58633A), background = Color(0xFFFFF8F2), onBackground = Color(0xFF231A12), surface = Color(0xFFFFFBF7),
                onSurface = Color(0xFF231A12), surfaceVariant = Color(0xFFF6EBDF), onSurfaceVariant = Color(0xFF52443A), surfaceContainer = Color(0xFFFBF0E6),
                surfaceContainerLow = Color(0xFFFFF5EC), surfaceContainerHigh = Color(0xFFF7EADD), surfaceContainerHighest = Color(0xFFF1E4D6),
                outline = Color(0xFF85736A), outlineVariant = Color(0xFFE0CFC2), surfaceTint = Color(0xFF8B5A00)
            )
        }
        return if (dark && amoled) s.copy(background = Color.Black, surface = Color(0xFF05060A), surfaceContainerLowest = Color.Black) else s
    }

    /** Card / dialog / button shapes: cut corners for Киберпанк, rounder for Material You and Стопка карт. */
    fun shapes(style: ServerStyle): Shapes = when (style) {
        ServerStyle.CYBER -> Shapes(
            extraSmall = CutCornerShape(4.dp), small = CutCornerShape(6.dp), medium = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
            large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp), extraLarge = CutCornerShape(topStart = 22.dp, bottomEnd = 22.dp)
        )
        ServerStyle.MATERIAL, ServerStyle.STACK -> Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)
        )
        ServerStyle.AURORA, ServerStyle.CARDS -> Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)
        )
        else -> Shapes()
    }
}
