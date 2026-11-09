package net.antibs.client.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AppLangTest {
    @Test fun systemUnsupportedFallsToEn() {
        assertEquals("en", AppLang.resolve("system", Locale.JAPAN))
        assertEquals("en", AppLang.resolve("system", Locale.forLanguageTag("ja-JP")))
    }
    @Test fun systemSupported() {
        assertEquals("de", AppLang.resolve("system", Locale.GERMANY))
        assertEquals("ru", AppLang.resolve("system", Locale.forLanguageTag("ru-RU")))
    }
    @Test fun explicit() {
        assertEquals("uk", AppLang.resolve("uk"))
        assertEquals("system", AppLang.normalize(""))
        assertEquals("system", AppLang.normalize("system"))
        assertFalse(AppLang.TAGS.contains("fa"))
    }
    @Test fun i18nFallback() {
        I18n.use("en")
        assertEquals("Home", I18n.t("tab_home"))
        I18n.use("ru")
        assertEquals("Главная", I18n.t("tab_home"))
    }
}
