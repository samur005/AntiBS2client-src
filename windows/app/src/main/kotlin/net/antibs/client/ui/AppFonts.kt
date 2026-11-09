package net.antibs.client.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * 2.0.31: bundled OFL/Apache fonts + «Системный». Decorative script faces are headings-only.
 * Desktop loads TTFs from `/fonts` on the classpath.
 */
object AppFonts {
    const val SYSTEM = "system"

    data class Option(
        val id: String,
        val label: String,
        val headingsOnly: Boolean = false,
    )

    val options: List<Option> = listOf(
        Option(SYSTEM, "Системный"),
        Option("inter", "Inter"),
        Option("roboto", "Roboto"),
        Option("montserrat", "Montserrat"),
        Option("nunito", "Nunito"),
        Option("golos", "Golos Text"),
        Option("jetbrains", "JetBrains Mono"),
        Option("comfortaa", "Comfortaa"),
        Option("unbounded", "Unbounded", headingsOnly = true),
        Option("lobster", "Lobster", headingsOnly = true),
        Option("caveat", "Caveat", headingsOnly = true),
        Option("pacifico", "Pacifico", headingsOnly = true),
    )

    fun normalize(id: String?): String =
        options.firstOrNull { it.id == (id ?: SYSTEM) }?.id ?: SYSTEM

    fun label(id: String?): String = options.firstOrNull { it.id == normalize(id) }?.label ?: "Системный"

    fun option(id: String?): Option = options.first { it.id == normalize(id) }

    private fun resourceFamily(file: String): FontFamily {
        val path = "fonts/$file"
        return FontFamily(
            Font(resource = path, weight = FontWeight.Light),
            Font(resource = path, weight = FontWeight.Normal),
            Font(resource = path, weight = FontWeight.Medium),
            Font(resource = path, weight = FontWeight.SemiBold),
            Font(resource = path, weight = FontWeight.Bold),
            Font(resource = path, weight = FontWeight.ExtraBold),
        )
    }

    fun familyOf(id: String?): FontFamily? = when (normalize(id)) {
        SYSTEM -> null
        "inter" -> resourceFamily("inter_wght.ttf")
        "roboto" -> resourceFamily("roboto_wdth_wght.ttf")
        "montserrat" -> resourceFamily("montserrat_wght.ttf")
        "nunito" -> resourceFamily("nunito_wght.ttf")
        "golos" -> resourceFamily("golostext_wght.ttf")
        "jetbrains" -> resourceFamily("jetbrainsmono_wght.ttf")
        "comfortaa" -> resourceFamily("comfortaa_wght.ttf")
        "unbounded" -> resourceFamily("unbounded_wght.ttf")
        "lobster" -> resourceFamily("lobster_regular.ttf")
        "caveat" -> resourceFamily("caveat_wght.ttf")
        "pacifico" -> resourceFamily("pacifico_regular.ttf")
        else -> null
    }

    fun typography(id: String?): Typography {
        val opt = option(id)
        val ff = familyOf(opt.id) ?: return Typography()
        val base = Typography()
        fun TextStyle.applyFont() = copy(fontFamily = ff)
        return if (opt.headingsOnly) {
            base.copy(
                displayLarge = base.displayLarge.applyFont(),
                displayMedium = base.displayMedium.applyFont(),
                displaySmall = base.displaySmall.applyFont(),
                headlineLarge = base.headlineLarge.applyFont(),
                headlineMedium = base.headlineMedium.applyFont(),
                headlineSmall = base.headlineSmall.applyFont(),
                titleLarge = base.titleLarge.applyFont(),
                titleMedium = base.titleMedium.applyFont(),
                titleSmall = base.titleSmall.applyFont(),
            )
        } else {
            base.copy(
                displayLarge = base.displayLarge.applyFont(),
                displayMedium = base.displayMedium.applyFont(),
                displaySmall = base.displaySmall.applyFont(),
                headlineLarge = base.headlineLarge.applyFont(),
                headlineMedium = base.headlineMedium.applyFont(),
                headlineSmall = base.headlineSmall.applyFont(),
                titleLarge = base.titleLarge.applyFont(),
                titleMedium = base.titleMedium.applyFont(),
                titleSmall = base.titleSmall.applyFont(),
                bodyLarge = base.bodyLarge.applyFont(),
                bodyMedium = base.bodyMedium.applyFont(),
                bodySmall = base.bodySmall.applyFont(),
                labelLarge = base.labelLarge.applyFont(),
                labelMedium = base.labelMedium.applyFont(),
                labelSmall = base.labelSmall.applyFont(),
            )
        }
    }

    const val PREVIEW = "Съешь ещё этих мягких французских булок 123"
}

@Composable
fun rememberAppTypography(fontFamilyId: String?): Typography {
    val id = AppFonts.normalize(fontFamilyId)
    return remember(id) { AppFonts.typography(id) }
}
