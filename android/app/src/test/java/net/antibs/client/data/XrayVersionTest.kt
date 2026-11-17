package net.antibs.client.data

import java.io.File
import net.antibs.client.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayVersionTest {
    @Test fun buildConfigIs26119() {
        assertEquals("26.11.9", BuildConfig.XRAY_VERSION)
    }

    @Test fun androidLibXrayLitePinsOfficial26119Commit() {
        val candidates = listOf(
            File("src/main/../../native/AndroidLibXrayLite/go.mod"),
            File("../native/AndroidLibXrayLite/go.mod"),
            File("android/native/AndroidLibXrayLite/go.mod"),
            File("../android/native/AndroidLibXrayLite/go.mod"),
        )
        val goMod = candidates.firstOrNull { it.isFile }
            ?: error("go.mod not found; cwd=${File(".").canonicalPath}")
        val text = goMod.readText()
        // Official Xray-core v26.11.9 tag is 676eaf66f8f0 (2026-11-09).
        assertTrue(text.contains("github.com/xtls/xray-core"))
        assertTrue(
            "expected v26.11.9 commit 676eaf66f8f0 in $goMod",
            text.contains("676eaf66f8f0"),
        )
    }
}
