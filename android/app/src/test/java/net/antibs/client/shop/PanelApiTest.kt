package net.antibs.client.shop

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.42: «Магазин» — panel API shapes (synthetic ids, no real tokens). */
class PanelApiTest {
    private val plans = """[
      {"id":"p1","name":"Обычный ","slug":"start","description":null,"price":150,"currency":"RUB","durationDays":30,"trafficLimitBytes":161061273600,
       "trafficLimitGb":150,"deviceLimit":3,"providerType":"REMNAWAVE","isActive":true,"isPopular":false,"isArchived":false,"isPromo":false,
       "promoOldPrice":null,"vpnServer":{"id":"v1","name":"NL-1","country":"NL","providerType":"REMNAWAVE"}},
      {"id":"p2","name":"Обходняк 4G","price":"250.00","currency":"RUB","durationDays":30,"trafficLimitGb":null,"deviceLimit":5,"isPopular":true,
       "isPromo":true,"promoOldPrice":300,"promoEndsAt":"2026-11-01T00:00:00.000Z","providerType":"THREE_X_UI","vpnServer":null},
      {"id":"p3","name":"Архив","price":1,"durationDays":1,"isArchived":true}
    ]"""

    @Test fun parsesPlans() {
        val p = Panel.parsePlans(plans)
        assertEquals(listOf("p1", "p2"), p.map { it.id })
        assertEquals("Обычный", p[0].name)
        assertEquals(150.0, p[0].price, 0.0); assertEquals(150.0, p[0].trafficGb!!, 0.0); assertEquals("NL", p[0].country)
        assertEquals(250.0, p[1].price, 0.0); assertNull(p[1].trafficGb); assertTrue(p[1].popular && p[1].promo); assertEquals(300.0, p[1].oldPrice!!, 0.0)
        assertEquals("150 ₽", Panel.money(150.0, "RUB")); assertEquals("99.50 ₽", Panel.money(99.5, "RUB"))
        assertEquals("1 месяц", Panel.days(30)); assertEquals("3 месяца", Panel.days(90)); assertEquals("1 год", Panel.days(365)); assertEquals("7 дней", Panel.days(7)); assertEquals("21 день", Panel.days(21))
        assertEquals("Безлимит", Panel.traffic(null)); assertEquals("150 ГБ", Panel.traffic(150.0)); assertEquals("1 ТБ", Panel.traffic(1024.0))
    }

    @Test fun identityAndBodies() {
        assertEquals("email" to "a@b.ru", Panel.identity(" a@b.ru "))
        assertEquals("phone" to "+79991234567", Panel.identity("+7 (999) 123-45-67"))
        assertEquals("username" to "ivanov", Panel.identity("ivanov"))
        val lb = Panel.parse(Panel.loginBody("ivanov", "pw")).jsonObject
        assertEquals("ivanov", lb["username"]!!.jsonPrimitive.content); assertEquals("pw", lb["password"]!!.jsonPrimitive.content)
        val rb = Panel.parse(Panel.registerBody("a@b.ru", "12345678", "12345678", " REF ")).jsonObject
        assertEquals("a@b.ru", rb["email"]!!.jsonPrimitive.content); assertEquals("REF", rb["referralCode"]!!.jsonPrimitive.content)
        assertTrue(Panel.registerErrors("ivanov", "12345678", "12345678").isEmpty())
        assertEquals(3, Panel.registerErrors("x", "123", "124").size)
        val ob = Panel.parse(Panel.orderBody("p1", "", "card", "c1")).jsonObject
        assertEquals(setOf("planId", "payMethod", "cashierId", "returnTo"), ob.keys)
        assertEquals("app", ob["returnTo"]!!.jsonPrimitive.content)
        assertFalse(Panel.parse(Panel.orderBody("p1", "", "card", null, fromApp = false)).jsonObject.containsKey("returnTo"))
        assertEquals("app", Panel.parse(Panel.renewBody()).jsonObject["returnTo"]!!.jsonPrimitive.content)
        assertEquals("{}", Panel.renewBody(false))
        assertFalse(Panel.parse(Panel.orderBody("p1", "SALE", "balance", "c1")).jsonObject.containsKey("cashierId"))
    }

    @Test fun tokensMeErrors() {
        val t = Panel.parseTokens("""{"accessToken":"A","refreshToken":"R","expiresIn":"15m","user":{"id":"u","email":"a@b.ru","role":"USER","username":null}}""", "x")
        assertEquals(Panel.Tokens("A", "R", "a@b.ru"), t)
        assertEquals(Panel.Me("ivanov", 12.5), Panel.parseMe("""{"id":"u","email":null,"username":"ivanov","balance":12.5}"""))
        assertEquals("Неверный логин или пароль", Panel.errorMessage(401, """{"message":"Неверный логин или пароль","error":"Unauthorized","statusCode":401}"""))
        assertEquals("a; b", Panel.errorMessage(400, """{"message":["a","b"],"statusCode":400}"""))
        assertEquals("Ошибка сервера панели (502)", Panel.errorMessage(502, "<html>bad gateway</html>"))
        assertEquals("https://x.ru", Panel.normBase(" x.ru/ "))
    }

    @Test fun orderPaymentSubscription() {
        val card = Panel.parseOrder("""{"order":{"id":"o1","status":"PENDING","amount":150,"subscriptionId":null},
            "payment":{"id":"pay1","status":"PENDING","amount":150,"payUrl":"https://yoomoney.ru/checkout/payments/v2/contract?orderId=x"},
            "subscription":null,"balance":0,"payMethod":"card"}""")
        assertFalse(card.paid); assertEquals("pay1", card.paymentId)
        assertEquals("https://yoomoney.ru/checkout/payments/v2/contract?orderId=x", Panel.payPageUrl(card.payUrl, "https://panel"))
        val bal = Panel.parseOrder("""{"order":{"id":"o2","status":"PAID","subscriptionId":"s1"},"payment":{"id":"pay2","status":"SUCCEEDED","amount":150},
            "subscription":{"id":"s1","status":"ACTIVE"},"provisionOk":true}""")
        assertTrue(bal.paid); assertEquals("s1", bal.subscriptionId); assertEquals(true, bal.provisionOk)
        assertEquals("https://panel/subscriptions", Panel.payPageUrl("/subscriptions", "https://panel"))
        assertNull(Panel.payPageUrl("javascript:alert(1)", "https://panel"))
        val c = Panel.parseCheck("""{"paymentId":"pay1","status":"SUCCEEDED","orderStatus":"PAID","subscriptionId":"s1","subscription":{"id":"s1","status":"ACTIVE","expiresAt":null}}""")
        assertEquals(Panel.PayCheck("SUCCEEDED", "PAID", "s1", "ACTIVE", null), c)
        val subs = Panel.parseSubs("""[{"id":"s1","status":"ACTIVE","planId":"p1","planNameSnapshot":"Обычный","expiresAt":"2026-11-15T10:00:00.000Z","daysRemaining":30,
            "trafficLimitBytes":161061273600,"plan":{"id":"p1","name":"Обычный"},"vpnAccount":{"subscriptionUrl":"https://sub.example/abc","trafficUsedBytes":1073741824}},
            {"id":"s2","status":"PENDING","plan":{"name":"Обходняк"},"vpnAccount":null}]""")
        assertEquals("https://sub.example/abc", subs[0].subscriptionUrl); assertEquals(1073741824L, subs[0].trafficUsedBytes); assertEquals(30, subs[0].daysRemaining)
        assertEquals("Обходняк", subs[1].planName); assertNull(subs[1].subscriptionUrl)
        assertEquals(2, Panel.parseCashiers("""{"cashiers":[{"id":"c1","name":"Карта","providerType":"YOOKASSA","isDefault":true},{"id":"c2","name":"СБП","providerType":"PLATEGA"}],"defaultCashierId":"c1","cardAvailable":true}""").list.size)
    }

    @Test fun sameUrl() {
        assertTrue(Panel.sameUrl("https://SUB.example/abc/", "https://sub.example/abc"))
        assertFalse(Panel.sameUrl("https://sub.example/abc", "https://sub.example/ABC"))
    }

    @Test fun paymentReturnLink() {
        assertEquals("cmabc123", Panel.parseReturn("antibs2client://payment-return?order=cmabc123"))
        assertEquals("cmabc123", Panel.parseReturn("antibs2client://payment-return?order=cmabc123&status=failed"))
        assertEquals("x1script", Panel.parseReturn("antibs2client://payment-return?order=x1%22%3Cscript"))
        assertEquals("", Panel.parseReturn("antibs2client://payment-return"))
        assertEquals(null, Panel.parseReturn("olcrtc://jitsi?datachannel@r#k"))
        assertTrue(Panel.rejectsReturnTo(Panel.ApiException(400, "property returnTo should not exist")))
        assertFalse(Panel.rejectsReturnTo(Panel.ApiException(400, "Неверный промокод")))
        val orders = """[{"id":"o1","plan":{"name":"Обычный"},"payments":[{"id":"pa","createdAt":"2026-10-16T05:00:00Z"},{"id":"pb","createdAt":"2026-10-16T06:00:00Z"}]},{"id":"o2","payments":[]}]"""
        assertEquals("pb" to "Обычный", Panel.paymentOfOrder(orders, "o1"))
        assertNull(Panel.paymentOfOrder(orders, "o2")); assertNull(Panel.paymentOfOrder(orders, "zz"))
    }

    @Test fun publicTextHidesProviders() {
        assertEquals("Безлимит, 3 устройства", Panel.publicText("Безлимит, Remnawave, 3 устройства"))
        assertEquals("Быстрый VPN", Panel.publicText("Быстрый VPN (3x-ui)"))
        assertEquals("3 устройства", Panel.publicText("Провайдер: Lucx, 3 устройства"))
        assertEquals("Для всех", Panel.publicText("Для всех\nПанель: REMNAWAVE\nСервер: NL-01", "NL-01"))
        assertEquals("Тариф на месяц", Panel.publicText("Тариф на месяц, nl-01", "NL-01"))
        assertEquals(null, Panel.publicText("3X-UI"))
        assertEquals("Lucxury", Panel.publicText("Lucxury"))
        assertEquals("Месяц", Panel.publicName("Месяц Remnawave"))
        assertEquals("Remnawave", Panel.publicName("Remnawave"))
    }
}
