package net.antibs.client.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterPickTest {
    private fun rel(tag: String, vararg assets: String, pre: Boolean = false, draft: Boolean = false): JsonElement {
        val a = assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://x/$tag/$it","size":10}""" }
        return Json.parseToJsonElement("""{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"","html_url":"h","assets":[$a]}""")
    }
    private val sums = "SHA256SUMS.txt"

    @Test fun picksNewestNonPrereleaseWithSetup() {
        val list = listOf(
            rel("v2.0.21-test", "AntiBS2client-2.0.21-windows-x64-setup.exe", pre = true),
            rel("v2.0.20", "AntiBS2client-2.0.20-arm64-v8a.apk", "AntiBS2client-2.0.20-windows-x64-setup.exe", sums),
            rel("v2.0.15", "AntiBS2client-2.0.15-arm64-v8a.apk", sums),
            rel("v1.0.53", "AntiBS2client-1.0.53-windows-x64-setup.exe"),
        )
        val u = Updater.pickUpdate(list, "1.0.53")!!
        assertEquals("v2.0.20", u.release.tag)
        assertEquals("AntiBS2client-2.0.20-windows-x64-setup.exe", u.apk.name)
        assertEquals(sums, u.sums?.name)
    }

    @Test fun skipsDraftsAndAndroidOnlyLatest() {
        val list = listOf(
            rel("v2.0.30", "AntiBS2client-2.0.30-windows-x64-setup.exe", draft = true),
            rel("v2.0.25", "AntiBS2client-2.0.25-universal.apk"),
            rel("v2.0.20", "AntiBS2client-2.0.20-windows-x64-setup.exe"),
        )
        assertEquals("v2.0.20", Updater.pickUpdate(list, "1.0.53")!!.release.tag)
    }

    @Test fun nothingNewer() {
        val list = listOf(rel("v2.0.20", "AntiBS2client-2.0.20-windows-x64-setup.exe"), rel("v2.0.21-beta", "AntiBS2client-2.0.21-windows-x64-setup.exe", pre = true))
        assertNull(Updater.pickUpdate(list, "2.0.20"))
    }

    @Test fun duplicateLatestAndListOk() {
        val r = rel("v2.0.20", "AntiBS2client-2.0.20-windows-x64-setup.exe", "SHA256SUMS-windows.txt", sums)
        val u = Updater.pickUpdate(listOf(r, r), "2.0.15")!!
        assertEquals("SHA256SUMS-windows.txt", u.sums?.name)
    }
}
