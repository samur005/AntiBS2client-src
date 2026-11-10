package net.antibs.client.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import net.antibs.client.engine.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0.40: every row-1 / country chip (including IPv4, the first protocol chip, and «Все»)
 * can be selected or cleared in any sequence. Uses the same snapshot-capture onTab/onCountry
 * pattern as Home (toggleTab(activeTab, it)) so a stale pointerInput would fail this test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h880dp-xxhdpi")
class HomeFiltersBarClickTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val tabs = listOf(
        HomeFilters.Tab("IPv4", "IPv4", 4),
        HomeFilters.Tab("IPv6", "IPv6", 2),
        HomeFilters.Tab("Антиблок", "Антиблок", 3),
        HomeFilters.Tab("VLESS Reality", "VLESS Reality", 5),
    )
    private val countries = listOf(
        HomeFilters.Country("DE", "🇩🇪", "Германия", 2, 38L),
        HomeFilters.Country("NL", "🇳🇱", "Нидерланды", 1, 52L),
        HomeFilters.Country("FI", "🇫🇮", "Финляндия", 1, 41L),
    )

    @Test fun tabClicksWorkInAnySequenceIncludingIpv4AfterAnotherChip() {
        val homeTab = mutableStateOf("")
        rule.setContent {
            val activeTab = HomeFilters.resolveTab(homeTab.value, tabs)
            AntiBSTheme(ThemeMode.DARK) {
                HomeFiltersBar(
                    tabs = tabs,
                    selectedTab = activeTab,
                    onTab = { homeTab.value = HomeFilters.toggleTab(activeTab, it) },
                    countries = countries,
                    selectedCountry = "",
                    allCount = countries.size,
                    serverCount = 9,
                    haptics = false,
                    onCountry = {},
                )
            }
        }
        fun tap(id: String) {
            val node = rule.onNodeWithTag(HomeFilters.tabTestTag(id), useUnmergedTree = true)
            node.performScrollTo()
            node.performTouchInput { click() }
            rule.waitForIdle()
        }
        fun sel() = HomeFilters.resolveTab(homeTab.value, tabs)

        tap("IPv4"); assertEquals("IPv4", sel())
        tap("VLESS Reality"); assertEquals("VLESS Reality", sel())
        tap("IPv4"); assertEquals("IPv4", sel())
        tap("IPv6"); assertEquals("IPv6", sel())
        tap("IPv4"); assertEquals("IPv4", sel())
        tap("IPv4"); assertEquals("", sel())
        tap("IPv4"); assertEquals("IPv4", sel())
        tap("Антиблок"); assertEquals("Антиблок", sel())
        tap(HomeFilters.TAB_ALL); assertEquals("", sel())
        tap("IPv6"); assertEquals("IPv6", sel())
        tap(HomeFilters.TAB_ALL); assertEquals("", sel())
        // shuffled tails that must always be able to finish on IPv4
        val others = listOf("IPv6", "Антиблок", "VLESS Reality")
        for (i in others.indices) {
            val seq = others.drop(i) + others.take(i)
            for (id in seq) tap(id)
            tap("IPv4")
            assertEquals("failed after $seq then IPv4 (homeTab=${homeTab.value})", "IPv4", sel())
        }
    }

    @Test fun countryClicksWorkInAnySequence() {
        val homeCountry = mutableStateOf("")
        rule.setContent {
            val activeCountry = HomeFilters.resolveCountry(homeCountry.value, countries)
            AntiBSTheme(ThemeMode.DARK) {
                HomeFiltersBar(
                    tabs = tabs,
                    selectedTab = "",
                    onTab = {},
                    countries = countries,
                    selectedCountry = activeCountry,
                    allCount = countries.size,
                    serverCount = 9,
                    haptics = false,
                    onCountry = { homeCountry.value = HomeFilters.toggleCountry(activeCountry, it) },
                )
            }
        }
        fun tap(code: String) {
            val node = rule.onNodeWithTag(HomeFilters.countryTestTag(code), useUnmergedTree = true)
            node.performScrollTo()
            node.performTouchInput { click() }
            rule.waitForIdle()
        }
        fun sel() = HomeFilters.resolveCountry(homeCountry.value, countries)

        tap("DE"); assertEquals("DE", sel())
        tap("NL"); assertEquals("NL", sel())
        tap("DE"); assertEquals("DE", sel())
        tap("FI"); assertEquals("FI", sel())
        tap("DE"); assertEquals("DE", sel())
        tap("DE"); assertEquals("", sel())
        tap("DE"); assertEquals("DE", sel())
        tap(HomeFilters.COUNTRY_ALL); assertEquals("", sel())
        tap("NL"); assertEquals("NL", sel())
        tap(HomeFilters.COUNTRY_ALL); assertEquals("", sel())
        val others = listOf("NL", "FI")
        for (i in others.indices) {
            val seq = others.drop(i) + others.take(i)
            for (code in seq) tap(code)
            tap("DE")
            assertEquals("failed after $seq then DE (homeCountry=${homeCountry.value})", "DE", sel())
        }
    }
}
