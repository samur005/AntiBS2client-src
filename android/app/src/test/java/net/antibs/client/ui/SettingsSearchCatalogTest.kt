package net.antibs.client.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchCatalogTest {
    @Test fun findsDnsByKeyword() {
        val r = SettingsSearchCatalog.filter("dns")
        assertTrue(r.any { it.title.contains("DNS", ignoreCase = true) || it.keywords.contains("dns") })
    }

    @Test fun findsTheme() {
        val r = SettingsSearchCatalog.filter("тема")
        assertTrue(r.any { it.title.contains("Тема") })
    }

    @Test fun emptyQueryYieldsNothing() {
        assertTrue(SettingsSearchCatalog.filter("").isEmpty())
        assertTrue(SettingsSearchCatalog.filter("   ").isEmpty())
    }

    @Test fun unknownYieldsEmpty() {
        assertTrue(SettingsSearchCatalog.filter("zzzqqqxxxyyy").isEmpty())
    }

    @Test fun findsServerSort() {
        val r = SettingsSearchCatalog.filter("сортировка серверов")
        assertTrue(r.any { it.title == "Сортировка серверов" })
        val r2 = SettingsSearchCatalog.filter("порядок подписок")
        assertTrue(r2.any { it.title == "Порядок подписок" })
    }

    @Test fun findsBackup() {
        val r = SettingsSearchCatalog.filter("резервная копия")
        assertTrue(r.any { it.card == "Резервная копия настроек" || it.title.contains("копи", ignoreCase = true) })
        val r2 = SettingsSearchCatalog.filter("восстановить")
        assertTrue(r2.any { it.title.contains("Восстановить") })
    }

    @Test fun itemIdStable() {
        assertEquals(settingsItemId("Тема оформления"), settingsItemId("Тема оформления"))
        assertEquals("тема-оформления", settingsItemId("Тема оформления"))
    }
}
