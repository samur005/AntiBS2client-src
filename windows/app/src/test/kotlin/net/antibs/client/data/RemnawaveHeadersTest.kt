package net.antibs.client.data

import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.TimeZone

/** Remnawave 3.4.4 response headers, sample (subscription URL anonymised). */
class RemnawaveHeadersTest {
    private val live: Map<String?, String?> = mapOf(
        null to "HTTP/1.1 200 OK",
        "Content-Disposition" to "attachment; filename=tg8643159152",
        "subscription-userinfo" to "upload=0; download=13272202103; total=161061273600; expire=1793649001",
        "routing" to "",
        "announce" to "base64:4oaW77iPINCS0LDRiNCwINC/0L7QtNC/0LjRgdC60LAgfCDQndC1INGA0LDQsdC+0YLQsNC10YI/INC+0LHQvdC+0LLQuNGC0LUg0L/QvtC00L/QuNGB0LrRgyDwn5SECvCfm6HvuI/Qn9C+0LTQv9C40YHQutCwOiB0Zzg2NDMxNTkxNTIgfCDQntGB0YLQsNC70L7RgdGMINC00L3QtdC5OiAyNA==",
        "support-url" to "https://t.me/buba_05",
        "Profile-Title" to "base64:8J+agEFudGlCUzJ2cG4gKFJXKQ==",
        "profile-web-page-url" to "https://sub.example.com/abcdEFGH1234",
        "profile-update-interval" to "2",
    )

    private val links: List<String> by lazy {
        javaClass.classLoader.getResourceAsStream("remnawave_links.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
    }

    @Test fun liveHeadersParse() {
        val h = SubscriptionParser.parseHeaders(live)
        assertEquals(0L, h.userInfo.upload)
        assertEquals(13272202103L, h.userInfo.download)
        assertEquals(161061273600L, h.userInfo.total)
        assertEquals(1793649001L, h.userInfo.expire)
        assertEquals("🚀AntiBS2vpn (RW)", h.title)
        assertEquals(2, h.updateIntervalHours)
        assertEquals("https://sub.example.com/abcdEFGH1234", h.webPageUrl)
        assertEquals("https://t.me/buba_05", h.supportUrl)
        assertTrue(h.announce, h.announce.startsWith("↖️ Ваша подписка | Не работает?"))
        assertTrue(h.announce.contains("tg8643159152"))
        assertTrue(h.announce.contains("Осталось дней: 24"))
        assertEquals("", h.routing)
        assertEquals("tg8643159152", h.fileName)
    }

    @Test fun headersFlowIntoRemoteSubscription() {
        val body = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())
        val r = SubscriptionParser.parse(body, "s1", SubscriptionParser.parseHeaders(live))!!
        assertEquals("🚀AntiBS2vpn (RW)", r.title)
        assertEquals(2, r.updateIntervalHours)
        assertEquals("https://t.me/buba_05", r.supportUrl)
        assertEquals("https://sub.example.com/abcdEFGH1234", r.webPageUrl)
        assertEquals("tg8643159152", r.fileName)
        assertTrue(r.announce.isNotEmpty())
        assertEquals(161061273600L, r.total)
        assertEquals(1793649001L, r.expireEpoch)
    }

    @Test fun unlimitedAndMissing() {
        val h = SubscriptionParser.parseHeaders(mapOf("subscription-userinfo" to "upload=5; download=7"))
        assertEquals(5L, h.userInfo.upload); assertEquals(7L, h.userInfo.download)
        assertEquals(0L, h.userInfo.total); assertEquals(0L, h.userInfo.expire)
        assertNull(h.title)
        assertEquals(0, h.updateIntervalHours)
        assertEquals("", h.announce); assertEquals("", h.supportUrl); assertEquals("", h.fileName)
        val empty = SubscriptionParser.parseHeaders(emptyMap())
        assertEquals(SubscriptionParser.SubHeaders(), empty)
    }

    @Test fun plainValuesAndVariants() {
        val h = SubscriptionParser.parseHeaders(mapOf(
            "profile-title" to "My%20VPN",
            "announce" to "Плановые работы в 03:00",
            "routing" to "base64:" + Base64.getEncoder().encodeToString("happ://routing/onadd/xyz".toByteArray()),
            "content-disposition" to "attachment; filename=\"a b.txt\"; filename*=UTF-8''%D0%BF%D0%BE%D0%B4%D0%BF.txt",
            "profile-update-interval" to " 12 ",
        ))
        assertEquals("My VPN", h.title)
        assertEquals("Плановые работы в 03:00", h.announce)
        assertEquals("happ://routing/onadd/xyz", h.routing)
        assertEquals("подп.txt", h.fileName)
        assertEquals(12, h.updateIntervalHours)
        assertEquals("a b.txt", SubscriptionParser.parseContentDispositionFilename("attachment; filename=\"a b.txt\""))
    }

    @Test fun realServerCountExcludesSynthetic() {
        val servers = links.mapNotNull { LinkParser.parse(it, "sub-remnawave") }
        assertEquals(17, servers.size)
        assertEquals(16, UiLogic.realServerCount(servers, "sub-remnawave"))
        assertTrue(UiLogic.isSyntheticServer("🇪🇺 🎲Автовыбор сервера"))
        assertTrue(UiLogic.isSyntheticServer("sub remnawave"))
        assertTrue(UiLogic.isSyntheticServer("sub-qwdtt"))
        assertTrue(UiLogic.isSyntheticServer("Balancer"))
        assertFalse(UiLogic.isSyntheticServer("🇩🇪 Германия 4G"))
        assertFalse(UiLogic.isSyntheticServer("🇷🇺→🇫🇮 Финляндия"))
    }

    @Test fun footerText() {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        try {
            assertEquals("17 серверов · подписка до 02.11.2026", UiLogic.subFooter(17, 1793649001L))
            assertEquals("1 сервер", UiLogic.subFooter(1, 0))
            assertEquals("3 сервера", UiLogic.subFooterLeft(3))
            assertEquals("11 серверов", UiLogic.subFooterLeft(11))
            assertEquals("21 сервер", UiLogic.subFooterLeft(21))
            assertEquals("", UiLogic.subFooterRight(0))
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun staleByUpdateInterval() {
        val s = Subscription(id = "x", url = "u", lastRefresh = 1_000_000L, updateIntervalHours = 2)
        assertFalse(UiLogic.isStale(s, 1_000_000L + 3_600_000L))
        assertTrue(UiLogic.isStale(s, 1_000_000L + 7_200_000L))
        assertFalse(UiLogic.isStale(s.copy(updateIntervalHours = 0), Long.MAX_VALUE))
    }
}
