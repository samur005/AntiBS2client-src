package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val release = """{"tag_name":"v1.0.20","name":"AntiBS client 1.0.20","draft":false,"prerelease":false,
        "html_url":"https://github.com/samur005/antibs-client-apk/releases/tag/v1.0.20","body":"## 1.0.20\n- исправления",
        "assets":[
          {"name":"AntiBS-client-1.0.20-arm64-v8a.apk","size":41000000,"browser_download_url":"https://github.com/samur005/antibs-client-apk/releases/download/v1.0.20/AntiBS-client-1.0.20-arm64-v8a.apk"},
          {"name":"AntiBS-client-1.0.20-universal.apk","size":80000000,"browser_download_url":"https://github.com/samur005/antibs-client-apk/releases/download/v1.0.20/AntiBS-client-1.0.20-universal.apk"},
          {"name":"SHA256SUMS.txt","size":200,"browser_download_url":"https://github.com/samur005/antibs-client-apk/releases/download/v1.0.20/SHA256SUMS.txt"}]}"""

    @Test fun versionCompare() {
        assertTrue(UpdateLogic.isNewer("v1.0.20", "1.0.19"))
        assertTrue(UpdateLogic.isNewer("1.1", "1.0.99"))
        assertTrue(UpdateLogic.isNewer("v1.0.100", "1.0.99"))
        assertFalse(UpdateLogic.isNewer("v1.0.19", "1.0.19"))
        assertFalse(UpdateLogic.isNewer("1.0.18", "1.0.19"))
        assertEquals(0, UpdateLogic.compare("1.0", "1.0.0"))
        assertFalse(UpdateLogic.isNewer("latest", "1.0.19"))
        assertTrue(UpdateLogic.isNewer("v2.0.0-beta", "1.9.9"))
    }

    @Test fun releaseParsingAndAssetChoice() {
        val r = UpdateLogic.parseRelease(release)!!
        assertEquals("1.0.20", r.version); assertTrue(r.notes.contains("исправления")); assertEquals(3, r.assets.size)
        assertEquals("AntiBS-client-1.0.20-arm64-v8a.apk", UpdateLogic.pickApk(r, listOf("arm64-v8a", "armeabi-v7a"))!!.name)
        assertEquals("AntiBS-client-1.0.20-universal.apk", UpdateLogic.pickApk(r, listOf("x86_64", "x86"))!!.name)
        assertEquals("SHA256SUMS.txt", UpdateLogic.sumsAsset(r)!!.name)
        val u = UpdateLogic.findUpdate(r, "1.0.19", listOf("arm64-v8a"))
        assertNotNull(u); assertEquals(41000000L, u!!.apk.size)
        assertNull(UpdateLogic.findUpdate(r, "1.0.20", listOf("arm64-v8a")))
        assertNull(UpdateLogic.parseRelease("""{"message":"Not Found"}"""))
        assertNull(UpdateLogic.parseRelease("not json"))
    }

    @Test fun sums() {
        val a = "a".repeat(64); val b = "B".repeat(64)
        val m = UpdateLogic.parseSums("$a  AntiBS-client-1.0.20-arm64-v8a.apk\n$b *out/AntiBS-client-1.0.20-universal.apk\njunk\n")
        assertEquals(a, m["AntiBS-client-1.0.20-arm64-v8a.apk"]); assertEquals("b".repeat(64), m["AntiBS-client-1.0.20-universal.apk"])
        assertEquals(2, m.size)
    }

    @Test fun urlsIntervalAndRateLimit() {
        assertEquals(UpdateLogic.DEFAULT_URL, UpdateLogic.apiUrl(""))
        assertEquals("https://api.github.com/repos/samur005/antibs-client-apk/releases/latest", UpdateLogic.DEFAULT_URL)
        assertEquals("https://api.github.com/repos/a/b/releases/latest", UpdateLogic.apiUrl("a/b"))
        assertEquals("https://api.github.com/repos/a/b/releases/latest", UpdateLogic.apiUrl("https://github.com/a/b/"))
        assertEquals("https://x.example/latest.json", UpdateLogic.apiUrl("https://x.example/latest.json"))
        val day = UpdateLogic.AUTO_INTERVAL_MS
        assertTrue(UpdateLogic.shouldAutoCheck(true, 0, 1000))
        assertFalse(UpdateLogic.shouldAutoCheck(true, 1000, 1000 + day - 1))
        assertTrue(UpdateLogic.shouldAutoCheck(true, 1000, 1000 + day))
        assertFalse(UpdateLogic.shouldAutoCheck(false, 0, 1000))
        val msg = UpdateLogic.rateLimitMessage(403, mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1600"), 1000)
        assertEquals("Лимит запросов GitHub исчерпан (60 в час) — повторите через 10 мин.", msg)
        assertNotNull(UpdateLogic.rateLimitMessage(429, emptyMap(), 0))
        assertNull(UpdateLogic.rateLimitMessage(403, mapOf("x-ratelimit-remaining" to "12"), 0))
        assertNull(UpdateLogic.rateLimitMessage(200, emptyMap(), 0))
    }

    @Test fun assetMatchingIgnoresTheNamePrefix() {
        val r = UpdateLogic.parseRelease(release.replace("AntiBS-client-", "AntiBS2client-"))!!
        assertEquals("AntiBS2client-1.0.20-arm64-v8a.apk", UpdateLogic.pickApk(r, listOf("arm64-v8a"))!!.name)
        assertEquals("AntiBS2client-1.0.20-universal.apk", UpdateLogic.pickApk(r, listOf("armeabi-v7a"))!!.name)
    }
}
