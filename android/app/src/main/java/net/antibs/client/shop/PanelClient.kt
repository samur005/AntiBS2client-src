package net.antibs.client.shop

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.antibs.client.BuildConfig
import net.antibs.client.engine.SecretBox
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 1.0.42: HTTP side of «Магазин». Tokens are sealed with the Android Keystore key ([SecretBox]); the access token
 * (15 min) is refreshed transparently on 401. Requests come from the app uid, which is outside our own VPN.
 */
class PanelClient(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("shop", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val refreshLock = Mutex()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // 1.0.44: endpoints are fixed to the defaults (no UI to change them); old overrides are ignored.
    var base: String
        get() = Panel.DEFAULT_API.trimEnd('/')
        set(@Suppress("UNUSED_PARAMETER") v) { prefs.edit().remove("base").apply() }
    var site: String
        get() = Panel.DEFAULT_SITE.trimEnd('/')
        set(@Suppress("UNUSED_PARAMETER") v) { prefs.edit().remove("site").apply() }

    val login: String? get() = prefs.getString("login", null)?.takeIf { loggedIn }
    val loggedIn: Boolean get() = !prefs.getString("refresh", null).isNullOrEmpty() || !prefs.getString("access", null).isNullOrEmpty()

    private fun access(): String? = prefs.getString("access", null)?.let { runCatching { SecretBox.open(it) }.getOrNull() }
    private fun refreshToken(): String? = prefs.getString("refresh", null)?.let { runCatching { SecretBox.open(it) }.getOrNull() }

    fun save(t: Panel.Tokens) {
        prefs.edit().putString("access", SecretBox.seal(t.access)).putString("refresh", SecretBox.seal(t.refresh.ifEmpty { null }))
            .putString("login", t.login).apply()
    }

    fun clear() { prefs.edit().remove("access").remove("refresh").remove("login").remove("pending").apply() }

    /** Pending card payment survives process death: paymentId|subscriptionId|planName|payUrl. */
    var pending: String?
        get() = prefs.getString("pending", null)
        set(v) { prefs.edit().apply { if (v == null) remove("pending") else putString("pending", v) }.apply() }

    private fun build(path: String, method: String, body: String?, token: String?): Request {
        val b = Request.Builder().url(base + path).header("Accept", "application/json")
            .header("User-Agent", "AntiBS2client/${BuildConfig.VERSION_NAME} (Android)")
        if (token != null) b.header("Authorization", "Bearer $token")
        val rb = when {
            body != null -> body.toRequestBody(jsonType)
            method == "POST" -> "{}".toRequestBody(jsonType)
            else -> null
        }
        return b.method(method, rb).build()
    }

    private fun exec(r: Request): Pair<Int, String> = http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }

    suspend fun call(path: String, method: String = "GET", body: String? = null, auth: Boolean = true): String = withContext(Dispatchers.IO) {
        var (code, text) = try { exec(build(path, method, body, if (auth) access() else null)) }
        catch (e: java.io.IOException) { throw Panel.ApiException(-1, "Нет связи с панелью: ${e.message ?: e.javaClass.simpleName}") }
        if (code == 401 && auth && refresh()) {
            val r = exec(build(path, method, body, access())); code = r.first; text = r.second
        }
        if (code == 401 && auth) { clear(); throw Panel.ApiException(401, "Сессия истекла — войдите снова") }
        if (code !in 200..299) throw Panel.ApiException(code, Panel.errorMessage(code, text))
        text
    }

    private suspend fun refresh(): Boolean = refreshLock.withLock {
        val rt = refreshToken() ?: return false
        val body = kotlinx.serialization.json.buildJsonObject { put("refreshToken", kotlinx.serialization.json.JsonPrimitive(rt)) }.toString()
        val (code, text) = try { exec(build("/auth/refresh", "POST", body, null)) } catch (_: Exception) { return false }
        if (code !in 200..299) return false
        runCatching { save(Panel.parseTokens(text, login ?: "")) }.isSuccess
    }

    suspend fun signIn(id: String, password: String) { save(Panel.parseTokens(call("/auth/login", "POST", Panel.loginBody(id, password), auth = false), id.trim())) }
    suspend fun signUp(id: String, password: String, confirm: String, referral: String?) {
        save(Panel.parseTokens(call("/auth/register", "POST", Panel.registerBody(id, password, confirm, referral), auth = false), id.trim()))
    }
    suspend fun signOut() {
        val rt = refreshToken()
        runCatching { call("/auth/logout", "POST", rt?.let { kotlinx.serialization.json.buildJsonObject { put("refreshToken", kotlinx.serialization.json.JsonPrimitive(it)) }.toString() }) }
        clear()
    }

    suspend fun plans(promo: String?): List<Panel.Plan> =
        Panel.parsePlans(call("/plans" + (promo?.trim()?.takeIf { it.isNotEmpty() }?.let { "?promo=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""), auth = false))
    suspend fun me(): Panel.Me = Panel.parseMe(call("/me"))
    suspend fun cashiers(): Panel.Cashiers = Panel.parseCashiers(call("/payments/cashiers"))
    suspend fun subs(): List<Panel.Sub> = Panel.parseSubs(call("/subscriptions"))
    suspend fun sub(id: String): Panel.Sub = Panel.parseSubOne(call("/subscriptions/$id"))
    suspend fun order(planId: String, promo: String?, payMethod: String, cashierId: String?): Panel.OrderResult = try {
        Panel.parseOrder(call("/orders", "POST", Panel.orderBody(planId, promo, payMethod, cashierId)))
    } catch (e: Exception) {
        if (!Panel.rejectsReturnTo(e)) throw e
        Panel.parseOrder(call("/orders", "POST", Panel.orderBody(planId, promo, payMethod, cashierId, fromApp = false)))
    }
    suspend fun renew(subId: String): Panel.OrderResult = try {
        Panel.parseOrder(call("/subscriptions/$subId/renew", "POST", Panel.renewBody()))
    } catch (e: Exception) {
        if (!Panel.rejectsReturnTo(e)) throw e
        Panel.parseOrder(call("/subscriptions/$subId/renew", "POST", Panel.renewBody(false)))
    }
    suspend fun orders(): String = call("/orders")
    suspend fun check(paymentId: String): Panel.PayCheck = Panel.parseCheck(call("/payments/$paymentId/check", "POST"))
}
