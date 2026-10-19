package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** 1.0.38: no «Источник обновлений» setting; updates always come from samur005/AntiBS2client-releases. */
class UpdateSourceFixedTest {
    private fun src(rel: String) = listOf(File("src/main/java/net/antibs/client/$rel"), File("app/src/main/java/net/antibs/client/$rel")).first { it.exists() }.readText()

    @Test fun settingsHaveNoSourceOverride() {
        val s = src("ui/SettingsScreens.kt")
        assertFalse(s.contains("Источник обновлений"))
        assertFalse(s.contains("Релизы берутся с GitHub"))
        assertFalse(src("ui/AppViewModel.kt").contains("setUpdateUrl"))
    }

    @Test fun checkUsesDefaultRepo() {
        assertEquals("https://api.github.com/repos/samur005/AntiBS2client-releases/releases/latest", UpdateLogic.apiUrl(""))
        val vm = src("ui/AppViewModel.kt")
        assertFalse(vm.contains("snapshot().updateUrl"))
        // any previously saved override is dropped on save / seeding
        val store = src("engine/AppStore.kt")
        assert(store.contains("updateUrl = \"\""))
    }
}
