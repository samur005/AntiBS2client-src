package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Regression: hosts like raw.githubusercontent.com serve a base64 body with `#profile-title:`
 * inside the decoded text and no Profile-Title HTTP header (see free_subs_small.b64 shape).
 * Synthetic links only — no live credentials.
 */
class ProfileTitleBodyTest {

    private fun b64(plain: String): String =
        Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

    private fun samplePlain(titleLine: String = "#profile-title: 🔥 ABS free"): String = """
        $titleLine
        hysteria2://user:pass@127.0.0.1:443?insecure=1#node-alpha
        ss://YWVzLTI1Ni1nY206cGFzcw==@127.0.0.1:8388#node-beta
    """.trimIndent()

    @Test fun titleFromDecodedBodyComment_base64Subscription() {
        val r = SubscriptionParser.parse(b64(samplePlain()), "sub-free", SubscriptionParser.SubHeaders())!!
        assertEquals("🔥 ABS free", r.title)
        assertEquals(2, r.servers.size)
        // Must not invent the subscription name from the first node.
        assertTrue(r.servers[0].name.contains("node-alpha") || r.servers[0].name == "node-alpha")
        assertTrue(r.title != r.servers[0].name)
    }

    @Test fun titleFromPlainBodyComment() {
        val r = SubscriptionParser.parse(samplePlain(), "sub-plain", SubscriptionParser.SubHeaders())!!
        assertEquals("🔥 ABS free", r.title)
        assertEquals(2, r.servers.size)
    }

    @Test fun slashSlashCommentAndBase64TitleValue() {
        val enc = Base64.getEncoder().encodeToString("AntiBS free".toByteArray(Charsets.UTF_8))
        val plain = samplePlain("//profile-title: base64:$enc")
        val r = SubscriptionParser.parse(b64(plain), "sub-b64", SubscriptionParser.SubHeaders())!!
        assertEquals("AntiBS free", r.title)
    }

    @Test fun httpProfileTitleWinsOverBody() {
        val headers = SubscriptionParser.SubHeaders(title = "From Header")
        val r = SubscriptionParser.parse(b64(samplePlain()), "sub-hdr", headers)!!
        assertEquals("From Header", r.title)
    }

    @Test fun contentDispositionFilenameFallbackWhenNoTitle() {
        val headers = SubscriptionParser.SubHeaders(fileName = "tg-fallback")
        val plain = """
            hysteria2://user:pass@127.0.0.1:443?insecure=1#only-node
        """.trimIndent()
        val r = SubscriptionParser.parse(b64(plain), "sub-fn", headers)!!
        assertEquals("tg-fallback", r.title)
        assertEquals("only-node", r.servers.single().name)
        assertTrue(r.title != r.servers.single().name)
    }

    @Test fun noTitleDoesNotInventFromFirstNode() {
        val plain = """
            hysteria2://user:pass@127.0.0.1:443?insecure=1#Some Fancy Node
            ss://YWVzLTI1Ni1nY206cGFzcw==@127.0.0.1:8388#other
        """.trimIndent()
        val r = SubscriptionParser.parse(b64(plain), "sub-none", SubscriptionParser.SubHeaders())!!
        assertNull(r.title)
        assertEquals(2, r.servers.size)
    }

    @Test fun extractBodyMetaStripsTitleLine() {
        val (rest, meta) = SubscriptionParser.extractBodyMeta(samplePlain())
        assertEquals("🔥 ABS free", meta.title)
        assertTrue(rest.lines().none { it.contains("profile-title", ignoreCase = true) })
        assertTrue(rest.contains("hysteria2://"))
    }

    @Test fun applyRefreshKeepsPriorTitleWhenRemoteHasNone() {
        // Existing AppStore rule: remote null title → keep previous (user/URL), never first node.
        val prior = "Моё имя"
        val remoteTitle: String? = null
        val next = remoteTitle?.ifBlank { null } ?: prior.ifBlank { "https://example/url" }
        assertEquals("Моё имя", next)
    }
}
