package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Nested subscription lists (Mahsa shape) — synthetic fixtures only, no live credentials.
 */
class NestedSubscriptionsTest {

    private fun b64(plain: String): String =
        Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

    private fun vless(tag: String) =
        "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?encryption=none&security=tls&type=ws&path=%2F&host=example.com&sni=example.com#$tag"

    private fun ss(tag: String) =
        "ss://YWVzLTI1Ni1nY206cGFzcw==@127.0.0.1:8388#$tag"

    private fun childPlain(vararg links: String) = links.joinToString("\n")
    private fun childB64(vararg links: String) = b64(links.joinToString("\n"))

    private fun mahsaOuter(vararg childUrls: String, title: String = "Mahsa") = buildString {
        appendLine("#profile-title: $title")
        appendLine("#profile-update-interval: 24")
        appendLine()
        childUrls.forEach { appendLine(it) }
    }

    private fun parseChild(body: String, id: String = "c"): RemoteSubscription? {
        if (NestedSubscriptions.exclusiveChildUrls(body) != null) return null // no 2nd level
        return SubscriptionParser.parse(body, id, SubscriptionParser.SubHeaders())?.takeIf { it.servers.isNotEmpty() }
    }

    @Test fun exclusiveDetectsMahsaShape() {
        val body = mahsaOuter(
            "https://example.com/nested/a#A",
            "https://example.com/nested/b",
        )
        val urls = NestedSubscriptions.exclusiveChildUrls(body)!!
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://example.com/nested/a"))
    }

    @Test fun exclusiveRejectsShareLinksAndMixed() {
        assertNull(NestedSubscriptions.exclusiveChildUrls(vless("x") + "\n" + ss("y")))
        assertNull(
            NestedSubscriptions.exclusiveChildUrls(
                mahsaOuter("https://example.com/a") + "\n" + vless("n"),
            ),
        )
        // HTTP proxy share-link must not look like a nested subscription URL list.
        assertNull(NestedSubscriptions.exclusiveChildUrls("https://user:pass@127.0.0.1:8443"))
        assertNotNull(LinkParser.parse("https://user:pass@127.0.0.1:8443"))
    }

    @Test fun selectChildrenDedupesSelfCyclesAndCaps() {
        val parent = "https://example.com/parent#Mahsa"
        val kids = listOf(
            "https://example.com/parent", // self (fragment-free)
            "https://example.com/parent#Other", // self via canonical
            "https://example.com/a#1",
            "https://example.com/a#2", // dup
            "https://example.com/b/",
            "https://example.com/b", // dup after slash trim
        ) + (1..30).map { "https://example.com/x$it" }
        val selected = NestedSubscriptions.selectChildren(parent, kids, maxChildren = 5)
        assertEquals(5, selected.size)
        assertFalse(selected.any { NestedSubscriptions.canonicalUrl(it).equals(NestedSubscriptions.canonicalUrl(parent), true) })
        assertEquals(1, selected.count { it.contains("/a") })
        assertTrue(selected.all { !it.contains("#") })
    }

    @Test fun expandMergesPlainAndBase64ChildrenSkipsEmptyErrors() {
        val childA = "https://example.test/a"
        val childB = "https://example.test/b"
        val childEmpty = "https://example.test/empty"
        val childErr = "https://example.test/err"
        val childNested = "https://example.test/nested-again"
        val outer = mahsaOuter(childA, childB, childEmpty, childErr, childNested)
        val bodies = mapOf(
            NestedSubscriptions.requestUrl(childA) to childPlain(vless("a1"), ss("a2")),
            NestedSubscriptions.requestUrl(childB) to childB64(vless("b1")),
            NestedSubscriptions.requestUrl(childEmpty) to "",
            NestedSubscriptions.requestUrl(childErr) to "not-a-subscription",
            // 2nd-level nested list — must not be followed
            NestedSubscriptions.requestUrl(childNested) to mahsaOuter("https://example.test/deep"),
        )
        var fetches = 0
        val merged = NestedSubscriptions.expand(
            parentUrl = "https://example.test/mahsa#Mahsa",
            outerBody = outer,
            outerHeaders = SubscriptionParser.SubHeaders(),
            fetchChild = { url ->
                fetches++
                val body = bodies[url]
                if (body == null) null else parseChild(body)
            },
        )!!
        assertEquals("Mahsa", merged.title)
        assertEquals(3, merged.servers.size) // a1,a2,b1
        assertEquals(5, fetches) // one level only; deep not fetched
        assertTrue(merged.servers.none { it.name.contains("deep", true) })
    }

    @Test fun expandRespectsMaxChildren() {
        val urls = (1..25).map { "https://example.test/c$it" }
        val outer = mahsaOuter(*urls.toTypedArray())
        var fetches = 0
        val merged = NestedSubscriptions.expand(
            parentUrl = "https://example.test/root",
            outerBody = outer,
            outerHeaders = SubscriptionParser.SubHeaders(),
            maxChildren = 3,
            fetchChild = { url ->
                fetches++
                parseChild(childPlain(vless(url.substringAfterLast('/'))))
            },
        )!!
        assertEquals(3, fetches)
        assertEquals(3, merged.servers.size)
    }

    @Test fun headerTitleWinsOverBodyAndFragmentUnusedInResolve() {
        val body = mahsaOuter("https://example.test/a", title = "BodyTitle")
        val headers = SubscriptionParser.SubHeaders(title = "HeaderTitle")
        assertEquals("HeaderTitle", NestedSubscriptions.resolveTitle(headers, body, "https://x#Frag"))
        assertEquals("BodyTitle", NestedSubscriptions.resolveTitle(SubscriptionParser.SubHeaders(), body, "https://x#Frag"))
        assertNull(NestedSubscriptions.resolveTitle(SubscriptionParser.SubHeaders(), "vless://x", "https://x#Frag"))
        assertEquals("Frag", NestedSubscriptions.fragmentTitle("https://x#Frag"))
    }

    @Test fun applyRefreshTitleKeepsUserNameUsesFragmentFallback() {
        // Mirrors AppStore rule without needing Android context.
        fun nextTitle(resultTitle: String?, priorTitle: String, url: String): String =
            resultTitle?.ifBlank { null }
                ?: priorTitle.takeUnless { it.isBlank() || it == url.trim() }
                ?: NestedSubscriptions.fragmentTitle(url)
                ?: priorTitle.ifBlank { url }

        val url = "https://example.test/mahsa#Mahsa"
        assertEquals("Mahsa", nextTitle(null, url, url))
        assertEquals("Моё", nextTitle(null, "Моё", url))
        assertEquals("FromRemote", nextTitle("FromRemote", "Моё", url))
    }

    @Test fun patternihaPlainVlessStillParsesWithoutNested() {
        val body = buildString {
            appendLine("#profile-title: Free-Configs")
            appendLine("#profile-update-interval: 1")
            repeat(30) { i -> appendLine(vless("n$i")) }
        }
        assertNull(NestedSubscriptions.exclusiveChildUrls(body))
        val r = SubscriptionParser.parse(body, "pat", SubscriptionParser.SubHeaders())!!
        assertEquals(30, r.servers.size)
        assertEquals("Free-Configs", r.title)
        // expand must not run / would return null because not exclusive nested
        assertNull(
            NestedSubscriptions.expand(
                parentUrl = "https://example.test/configs.txt#Patterniha",
                outerBody = body,
                outerHeaders = SubscriptionParser.SubHeaders(),
                fetchChild = { error("must not fetch children for patterniha-shaped body") },
            ),
        )
    }

    @Test fun ordinaryShareLinkBodyNotNested() {
        val body = childPlain(vless("one"), ss("two"))
        assertNull(NestedSubscriptions.exclusiveChildUrls(body))
        val r = SubscriptionParser.parse(body, "s", SubscriptionParser.SubHeaders())!!
        assertEquals(2, r.servers.size)
    }
}
