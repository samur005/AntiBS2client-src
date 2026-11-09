package net.antibs.client.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import net.antibs.client.R

/**
 * 2.0.31: bundled OFL/Apache fonts + «Системный». Decorative script faces are headings-only
 * so body copy stays readable.
 */
object AppFonts {
    const val SYSTEM = "system"

    data class Option(
        val id: String,
        val label: String,
        /** Script / display faces → titles & headlines only. */
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

    private fun varFamily(resId: Int): FontFamily = FontFamily(
        Font(resId, FontWeight.Light),
        Font(resId, FontWeight.Normal),
        Font(resId, FontWeight.Medium),
        Font(resId, FontWeight.SemiBold),
        Font(resId, FontWeight.Bold),
        Font(resId, FontWeight.ExtraBold),
    )

    private fun staticFamily(resId: Int): FontFamily = FontFamily(
        Font(resId, FontWeight.Normal),
        Font(resId, FontWeight.Medium),
        Font(resId, FontWeight.Bold),
    )

    fun familyOf(id: String?): FontFamily? = when (normalize(id)) {
        SYSTEM -> null
        "inter" -> varFamily(R.font.inter_wght)
        "roboto" -> varFamily(R.font.roboto_wdth_wght)
        "montserrat" -> varFamily(R.font.montserrat_wght)
        "nunito" -> varFamily(R.font.nunito_wght)
        "golos" -> varFamily(R.font.golostext_wght)
        "jetbrains" -> varFamily(R.font.jetbrainsmono_wght)
        "comfortaa" -> varFamily(R.font.comfortaa_wght)
        "unbounded" -> varFamily(R.font.unbounded_wght)
        "lobster" -> staticFamily(R.font.lobster_regular)
        "caveat" -> varFamily(R.font.caveat_wght)
        "pacifico" -> staticFamily(R.font.pacifico_regular)
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

/** Remembered [AppFonts.typography] for the current preference id. */
@Composable
fun rememberAppTypography(fontFamilyId: String?): Typography {
    val id = AppFonts.normalize(fontFamilyId)
    return remember(id) { AppFonts.typography(id) }
}
