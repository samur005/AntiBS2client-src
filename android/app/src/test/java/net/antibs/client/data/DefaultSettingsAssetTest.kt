package net.antibs.client.data

import java.io.File
import net.antibs.client.engine.AppState
import net.antibs.client.engine.SettingsBackupIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSettingsAssetTest {
    private fun asset(): File {
        val candidates = listOf(
            File("src/main/assets/default_settings.json"),
            File("android/app/src/main/assets/default_settings.json"),
            File("../src/main/assets/default_settings.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("default_settings.json not found; cwd=${File(".").canonicalPath}")
    }

    @Test fun assetParsesAndAppliesToFreshInstall() {
        val text = asset().readText()
        assertFalse(text.contains("\"subscriptions\""))
        assertFalse(text.contains("\"servers\""))
        assertFalse(text.contains("\"hwid\""))
        assertFalse(text.contains("\"customHwid\""))
        assertFalse(text.contains("\"vkHashes\""))
        assertFalse(text.contains("\"selectedServerId\""))
        assertFalse(Regex("""[0-9a-f]{64}""").containsMatchIn(text))
        assertFalse(text.contains("route-sub-"))
        assertFalse(text.contains("vlessfo"))
        assertFalse(text.contains("hydraponique"))

        val b = SettingsBackup.parse(text).getOrThrow()
        assertEquals(SettingsBackup.SCHEMA, b.schemaVersion)
        assertEquals("2.0.37", b.appVersion)
        assertTrue(b.prefs.autoDeleteUnreachableByUrl.isEmpty())
        assertTrue(b.prefs.adv.routingProfiles.isEmpty())
        assertNull(b.prefs.adv.activeRoutingId)
        assertTrue(b.prefs.adv.serverGrouping.open.isEmpty())
        assertTrue(b.prefs.adv.serverGrouping.subs.isEmpty())
        assertEquals("", b.styles.homeFilterTab)
        assertEquals("", b.styles.homeFilterCountry)

        val fresh = AppState()
        assertTrue(fresh.servers.isEmpty())
        assertTrue(fresh.subscriptions.isEmpty())
        val applied = SettingsBackupIo.applyAutoDeleteByUrl(
            SettingsBackupIo.applyPrefs(fresh, b.prefs),
            b.prefs.autoDeleteUnreachableByUrl,
        )
        assertTrue(applied.servers.isEmpty())
        assertTrue(applied.subscriptions.isEmpty())
        assertEquals(fresh.hwid, applied.hwid)
        assertEquals(fresh.selectedServerId, applied.selectedServerId)

        // Every BackupPrefs field must land on a fresh AppState.
        val round = SettingsBackupIo.prefsOf(applied)
        assertEquals(b.prefs.copy(autoDeleteUnreachableByUrl = emptyMap()), round)

        assertEquals("auto", applied.theme)
        assertTrue(applied.xrayTun)
        assertEquals(5000, applied.pingTimeoutMs)
        assertEquals("ru", applied.adv.language)
        assertEquals("purple", applied.adv.accent)
        assertEquals("roboto", applied.adv.fontFamily)
        assertEquals("bits", applied.adv.speedUnits)
        assertFalse(applied.adv.logging)
        assertTrue(applied.adv.hideServerIp)
        assertFalse(applied.adv.homeUrlTest)
        assertEquals("bypass", applied.adv.perAppMode)
        assertEquals(listOf("com.tanksblitz"), applied.adv.perAppBypass)
        assertTrue(applied.adv.autoPing.on)
        assertEquals(5, applied.adv.pingTimeoutSec)
        assertFalse(b.styles.subNewestFirst)
        assertEquals("added", b.styles.sort)
        assertEquals("classic", b.styles.style)
        assertEquals("groups", b.styles.options["classic"]?.layout)
        assertEquals(false, b.schedule?.on)
        assertEquals(true, b.schedule?.notify)

        // New subscriptions still default to Автоудаление недоступных = on.
        val sub = Subscription(id = "new", url = "https://example.invalid/sub")
        assertTrue(sub.autoDeleteUnreachable)
    }
}
