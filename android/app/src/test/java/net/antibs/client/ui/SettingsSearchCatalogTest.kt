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

    @Test fun itemIdStable() {
        assertEquals(settingsItemId("Тема оформления"), settingsItemId("Тема оформления"))
        assertEquals("тема-оформления", settingsItemId("Тема оформления"))
    }
}
