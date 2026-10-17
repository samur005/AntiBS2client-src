package net.antibs.client.shop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 1.0.42: «Магазин» — Antibs VPN Panel REST API (NestJS, JWT). Endpoints used:
 *  GET  /plans[?promo=]                 public tariffs
 *  POST /auth/login | /auth/register     {username|email|phone, password[, confirmPassword]} → access/refresh tokens
 *  POST /auth/refresh                    {refreshToken}
 *  GET  /me                              balance, login
 *  GET  /payments/cashiers               enabled cashiers (no secrets)
 *  POST /orders                          {planId, promoCode?, payMethod card|balance, cashierId?} → order + payment.payUrl
 *  POST /payments/:id/check              live status (asks YooKassa/Platega) → status, subscriptionId
 *  GET  /subscriptions[/:id]             vpnAccount.subscriptionUrl = the link imported into the app
 *  POST /subscriptions/:id/renew         same answer as /orders
 * This file: models + pure JSON parsing (unit-tested). HTTP lives in [PanelClient].
 */
object Panel {
    const val DEFAULT_API = "https://vpnpanel-api.antibs.fun"
    const val DEFAULT_SITE = "https://vpnpanel.antibs.fun"

    data class Plan(
        val id: String, val name: String, val description: String?, val price: Double, val currency: String,
        val durationDays: Int, val trafficGb: Double?, val deviceLimit: Int, val popular: Boolean, val promo: Boolean,
        val oldPrice: Double?, val promoEndsAt: String?, val provider: String, val country: String?, val serverName: String?,
    )
    data class Tokens(val access: String, val refresh: String, val login: String)
    data class Me(val login: String, val balance: Double)
    data class Cashier(val id: String, val name: String, val provider: String, val isDefault: Boolean)
    data class Cashiers(val list: List<Cashier>, val defaultId: String?, val cardAvailable: Boolean)
    data class Sub(
        val id: String, val status: String, val planName: String, val planId: String?, val expiresAt: String?, val daysRemaining: Int?,
        val trafficLimitBytes: Long?, val trafficUsedBytes: Long, val deviceLimit: Int?, val subscriptionUrl: String?,
    )
    data class OrderResult(
        val orderId: String?, val orderStatus: String?, val paymentId: String?, val paymentStatus: String?, val payUrl: String?,
        val amount: Double, val subscriptionId: String?, val subscriptionStatus: String?, val provisionOk: Boolean?, val provisionError: String?,
    ) {
        val paid: Boolean get() = paymentStatus == "SUCCEEDED" || orderStatus == "PAID" || (amount <= 0 && paymentId != null)
    }
    data class PayCheck(val status: String, val orderStatus: String?, val subscriptionId: String?, val subscriptionStatus: String?, val payUrl: String?)

    class ApiException(val code: Int, message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.booleanOrNull }
    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    fun normBase(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return DEFAULT_API
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    /** NestJS error body: {message: "…" | ["…", …], statusCode}. */
    fun errorMessage(code: Int, body: String?): String {
        val m = runCatching {
            val o = parse(body ?: "").jsonObject
            when (val v = o["message"]) {
                is JsonArray -> v.joinToString("; ") { (it as? JsonPrimitive)?.content ?: it.toString() }
                is JsonPrimitive -> v.content
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return m ?: when (code) {
            401 -> "Требуется вход"
            403 -> "Доступ запрещён"
            404 -> "Не найдено"
            429 -> "Слишком много запросов, подождите"
            in 500..599 -> "Ошибка сервера панели ($code)"
            else -> "Ошибка $code"
        }
    }

    /** Login field: «@» → email, digits/+ → phone, otherwise username (the panel accepts any of the three). */
    fun identity(id: String): Pair<String, String> {
        val t = id.trim()
        return when {
            t.contains('@') -> "email" to t
            Regex("^\\+?[0-9 ()\\-]{7,20}$").matches(t) -> "phone" to t.filter { it.isDigit() || it == '+' }
            else -> "username" to t
        }
    }

    fun loginBody(id: String, password: String): String = buildJsonObject {
        val (k, v) = identity(id); put(k, v); put("password", password)
    }.toString()

    fun registerBody(id: String, password: String, confirm: String, referral: String?): String = buildJsonObject {
        val (k, v) = identity(id); put(k, v); put("password", password); put("confirmPassword", confirm)
        if (!referral.isNullOrBlank()) put("referralCode", referral.trim())
    }.toString()

    fun registerErrors(id: String, password: String, confirm: String): List<String> = buildList {
        val (k, v) = identity(id)
        if (v.isBlank()) add("укажите логин, почту или телефон")
        else if (k == "username" && !Regex("^[a-zA-Z0-9_][a-zA-Z0-9_.-]{2,31}$").matches(v)) add("логин: 3–32 символа, латиница, цифры, _ . -")
        if (password.length < 8) add("пароль: минимум 8 символов")
        if (password != confirm) add("пароли не совпадают")
    }

    fun orderBody(planId: String, promo: String?, payMethod: String, cashierId: String?, fromApp: Boolean = true): String = buildJsonObject {
        put("planId", planId)
        if (!promo.isNullOrBlank()) put("promoCode", promo.trim())
        put("payMethod", payMethod)
        if (payMethod == "card" && !cashierId.isNullOrBlank()) put("cashierId", cashierId)
        // 1.0.43: the cashier returns to <site>/pay/return → antibs2client://payment-return
        if (fromApp) put("returnTo", "app")
    }.toString()

    fun renewBody(fromApp: Boolean = true): String = if (fromApp) """{"returnTo":"app"}""" else "{}"

    /** A panel without the 1.0.43 «returnTo» field rejects it (forbidNonWhitelisted) → retry without it. */
    fun rejectsReturnTo(e: Throwable): Boolean = e is ApiException && e.code == 400 && (e.message ?: "").contains("returnTo")

    const val RETURN_SCHEME = "antibs2client"
    const val RETURN_HOST = "payment-return"

    /** antibs2client://payment-return?order=<id>[&status=failed] → order id ("" when absent), null for other links. */
    fun parseReturn(uri: String): String? {
        val u = uri.trim()
        if (!u.startsWith("$RETURN_SCHEME://$RETURN_HOST", ignoreCase = true)) return null
        val q = u.substringAfter('?', "")
        return q.split('&').firstOrNull { it.startsWith("order=") }?.substringAfter('=')
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }?.take(64) ?: ""
    }

    /** GET /orders → the newest payment id of [orderId]. */
    fun paymentOfOrder(text: String, orderId: String): Pair<String, String>? {
        val o = (parse(text) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.s("id") == orderId } ?: return null
        val pays = (o["payments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val p = pays.maxByOrNull { it.s("createdAt").orEmpty() } ?: return null
        val name = o.o("plan")?.s("name")?.trim().orEmpty()
        return (p.s("id") ?: return null) to name
    }

    fun parseTokens(text: String, fallbackLogin: String): Tokens {
        val o = parse(text).jsonObject
        val access = o.s("accessToken") ?: throw ApiException(0, "Панель не вернула токен")
        val u = o.o("user")
        val login = u?.s("username") ?: u?.s("email") ?: fallbackLogin
        return Tokens(access, o.s("refreshToken").orEmpty(), login)
    }

    fun parseMe(text: String): Me {
        val o = parse(text).jsonObject
        return Me(o.s("username") ?: o.s("email") ?: o.s("phone") ?: o.s("displayName") ?: "", o.d("balance") ?: 0.0)
    }

    fun parsePlans(text: String): List<Plan> {
        val arr = when (val e = parse(text)) { is JsonArray -> e; is JsonObject -> e["items"] as? JsonArray ?: JsonArray(emptyList()); else -> JsonArray(emptyList()) }
        return arr.mapNotNull { it as? JsonObject }.filter { it.b("isActive") != false && it.b("isArchived") != true }.map { o ->
            val gb = o.d("trafficLimitGb") ?: o.l("trafficLimitBytes")?.let { it / 1073741824.0 }
            val srv = o.o("vpnServer")
            Plan(
                id = o.s("id").orEmpty(), name = o.s("name").orEmpty().trim(), description = o.s("description")?.trim()?.ifEmpty { null },
                price = o.d("price") ?: 0.0, currency = o.s("currency") ?: "RUB", durationDays = o.i("durationDays") ?: 0,
                trafficGb = gb, deviceLimit = o.i("deviceLimit") ?: 0, popular = o.b("isPopular") == true, promo = o.b("isPromo") == true,
                oldPrice = o.d("promoOldPrice"), promoEndsAt = o.s("promoEndsAt"), provider = o.s("providerType").orEmpty(),
                country = srv?.s("country"), serverName = srv?.s("name"),
            )
        }.filter { it.id.isNotEmpty() }
    }

    fun parseCashiers(text: String): Cashiers {
        val o = parse(text).jsonObject
        val list = (o["cashiers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
            Cashier(it.s("id").orEmpty(), it.s("name").orEmpty(), it.s("providerType").orEmpty(), it.b("isDefault") == true)
        }
        return Cashiers(list, o.s("defaultCashierId"), o.b("cardAvailable") ?: list.isNotEmpty())
    }

    fun parseSub(o: JsonObject): Sub {
        val acc = o.o("vpnAccount"); val plan = o.o("plan")
        return Sub(
            id = o.s("id").orEmpty(), status = o.s("status") ?: "PENDING",
            planName = (o.s("planNameSnapshot") ?: plan?.s("name") ?: "Подписка").trim(), planId = o.s("planId") ?: plan?.s("id"),
            expiresAt = o.s("expiresAt") ?: acc?.s("expireAt"), daysRemaining = o.i("daysRemaining"),
            trafficLimitBytes = o.l("trafficLimitBytes") ?: acc?.l("trafficLimitBytes"), trafficUsedBytes = acc?.l("trafficUsedBytes") ?: 0,
            deviceLimit = o.i("deviceLimit"), subscriptionUrl = acc?.s("subscriptionUrl")?.trim()?.ifEmpty { null },
        )
    }

    fun parseSubs(text: String): List<Sub> = (parse(text) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { parseSub(it) }
    fun parseSubOne(text: String): Sub = parseSub(parse(text).jsonObject)

    fun parseOrder(text: String): OrderResult {
        val o = parse(text).jsonObject
        val order = o.o("order"); val pay = o.o("payment"); val sub = o.o("subscription")
        return OrderResult(
            orderId = order?.s("id"), orderStatus = order?.s("status"), paymentId = pay?.s("id"), paymentStatus = pay?.s("status"),
            payUrl = pay?.s("payUrl")?.ifBlank { null }, amount = pay?.d("amount") ?: order?.d("amount") ?: 0.0,
            subscriptionId = sub?.s("id") ?: order?.s("subscriptionId"), subscriptionStatus = sub?.s("status"),
            provisionOk = o.b("provisionOk"), provisionError = o.s("provisionError"),
        )
    }

    fun parseCheck(text: String): PayCheck {
        val o = parse(text).jsonObject
        return PayCheck(o.s("status") ?: "PENDING", o.s("orderStatus"), o.s("subscriptionId"), o.o("subscription")?.s("status"), o.s("payUrl"))
    }

    /** Only http(s) payment pages are opened (a mock provider may return a panel-relative path). */
    fun payPageUrl(payUrl: String?, site: String): String? {
        val u = payUrl?.trim().orEmpty()
        if (u.isEmpty()) return null
        if (u.startsWith("https://") || u.startsWith("http://")) return u
        if (u.startsWith("/")) return site.trimEnd('/') + u
        return null
    }

    fun money(v: Double, currency: String): String {
        val n = if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v)
        val c = when (currency.uppercase()) { "RUB" -> "₽"; "USD" -> "$"; "EUR" -> "€"; "XTR" -> "⭐"; else -> currency }
        return "$n $c"
    }

    fun days(d: Int): String {
        if (d > 0 && d % 365 == 0) return (d / 365).let { "$it ${plural(it, "год", "года", "лет")}" }
        if (d > 0 && d % 30 == 0) return (d / 30).let { "$it ${plural(it, "месяц", "месяца", "месяцев")}" }
        return "$d ${plural(d, "день", "дня", "дней")}"
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n) % 100; val b = a % 10
        return when { a in 11..14 -> many; b == 1 -> one; b in 2..4 -> few; else -> many }
    }

    fun traffic(gb: Double?): String = when {
        gb == null || gb <= 0 -> "Безлимит"
        gb >= 1024 && gb % 1024 == 0.0 -> "${(gb / 1024).toInt()} ТБ"
        gb % 1.0 == 0.0 -> "${gb.toInt()} ГБ"
        else -> "%.1f ГБ".format(gb)
    }

    fun statusLabel(s: String): String = when (s.uppercase()) {
        "ACTIVE" -> "Активна"; "EXPIRED" -> "Истекла"; "DISABLED" -> "Отключена"; "BLOCKED" -> "Заблокирована"; "PENDING" -> "Ожидает"
        "SUCCEEDED" -> "Оплачен"; "FAILED" -> "Ошибка"; "CANCELLED" -> "Отменён"; "REFUNDED" -> "Возврат"
        else -> s
    }

    fun providerLabel(p: String): String = when (p.uppercase()) {
        "YOOKASSA" -> "ЮKassa"; "PLATEGA" -> "Platega"; "TBANK" -> "Т-Банк"; "CLOUDPAYMENTS" -> "CloudPayments"; "CRYPTO" -> "Криптовалюта"
        "TELEGRAM_STARS" -> "Telegram Stars"; "MOCK" -> "Тест"; else -> p
    }

    /** 1.0.44: VPN panel / provider names never shown on plan cards. */
    private val providerWords = Regex(
        "(?iu)(?<![\\p{L}\\d])(remna\\s*wave|3\\s*x[-\\s]?ui|x[-\\s]?ui|lucx|marzban|hiddify|marzneshin|outline\\s+server|wg[-\\s]?easy|amnezia\\s*panel)(?![\\p{L}\\d])"
    )
    private val danglingLabel = Regex("(?iu)^\\s*[-–—•·*]?\\s*(провайдер|поставщик|панель|provider|panel|тип|type|backend|бэкенд|сервер|server|нода|node|узел)\\s*[:=\\-–—]?\\s*$")

    /** Plan description without provider panels / server names; null when nothing user-facing is left. */
    fun publicText(text: String?, vararg extra: String?): String? {
        if (text.isNullOrBlank()) return null
        val names = extra.filterNotNull().map { it.trim() }.filter { it.length >= 3 }
        val lines = text.lines().mapNotNull { line ->
            var l = providerWords.replace(line, "")
            for (n in names) l = l.replace(n, "", ignoreCase = true)
            l = l.replace(Regex("(?iu)(провайдер|поставщик|панель|provider|panel|сервер|server)\\s*[:=]\\s*(?=[,;·|]|$)"), "")
                .replace(Regex("\\(\\s*[,;/·|+&-]*\\s*\\)"), "")           // empty brackets
                .replace(Regex("\\s*([,;/·|+&])\\s*(?=[,;/·|+&])"), "")           // doubled separators
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(Regex("\\s+([,.;:!?])"), "$1")
                .trim().trim(',', ';', '/', '·', '|', '+', '&', '-', '–', '—', ':').trim()
            l.takeUnless { it.isBlank() || danglingLabel.matches(it) || l.none { c -> c.isLetterOrDigit() } }
        }
        return lines.joinToString("\n").trim().ifEmpty { null }
    }

    /** Plan title without provider panel names (falls back to the original if nothing is left). */
    fun publicName(name: String): String = publicText(name)?.lines()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: name

    /** Subscription URL already in the app? (trailing slash / case of the host ignored). */
    fun sameUrl(a: String, b: String): Boolean {
        fun n(s: String) = s.trim().trimEnd('/').let { u -> val i = u.indexOf("://"); if (i < 0) u else u.substring(0, i + 3) + u.substring(i + 3).let { r -> r.substringBefore('/').lowercase() + (if (r.contains('/')) "/" + r.substringAfter('/') else "") } }
        return n(a) == n(b)
    }
}
