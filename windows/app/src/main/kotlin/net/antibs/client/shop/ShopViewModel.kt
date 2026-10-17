package net.antibs.client.shop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 1.0.42: «Магазин» state — tariffs, account, purchase → payment page → polling → auto-import of the subscription URL. */
class ShopViewModel(app: Application) : AndroidViewModel(app) {
    val client = PanelClient(app)

    data class Pending(
        val paymentId: String, val subscriptionId: String?, val planName: String, val payUrl: String?,
        val status: String = "PENDING", val message: String = "Ожидаем оплату…", val done: Boolean = false, val failed: Boolean = false,
    )

    data class Ui(
        val loadingPlans: Boolean = false, val plans: List<Panel.Plan> = emptyList(), val plansError: String? = null,
        val promo: String = "", val loggedIn: Boolean = false, val login: String? = null, val balance: Double? = null,
        val cashiers: Panel.Cashiers? = null, val subs: List<Panel.Sub> = emptyList(), val loadingAccount: Boolean = false,
        val authBusy: Boolean = false, val authError: String? = null, val busyPlanId: String? = null,
        val pending: Pending? = null, val base: String = Panel.DEFAULT_API, val site: String = Panel.DEFAULT_SITE,
    )

    /** One-shot events for the screen: open a payment page, import a subscription URL, show a toast. */
    sealed class Event {
        data class Open(val url: String) : Event()
        data class Import(val url: String, val name: String) : Event()
        data class Toast(val text: String) : Event()
    }

    private val _ui = MutableStateFlow(Ui(loggedIn = client.loggedIn, login = client.login, base = client.base, site = client.site))
    val ui: StateFlow<Ui> = _ui
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events
    private var poll: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        loadPlans()
        if (client.loggedIn) loadAccount()
        client.pending?.split('|', limit = 4)?.takeIf { it.size == 4 && it[0].isNotEmpty() }?.let { p ->
            _ui.update { it.copy(pending = Pending(p[0], p[1].ifEmpty { null }, p[2], p[3].ifEmpty { null })) }
            startPolling()
        }
    }

    fun setPromo(v: String) { _ui.update { it.copy(promo = v) } }

    fun loadPlans() {
        _ui.update { it.copy(loadingPlans = true, plansError = null) }
        viewModelScope.launch {
            try {
                val p = client.plans(_ui.value.promo)
                _ui.update { it.copy(plans = p, loadingPlans = false) }
                if (_ui.value.promo.isNotBlank()) _events.tryEmit(Event.Toast(if (p.any { it.promo }) "Промокод применён" else "Тарифы обновлены"))
            } catch (e: Exception) {
                _ui.update { it.copy(loadingPlans = false, plansError = e.message ?: "Ошибка загрузки тарифов") }
            }
        }
    }

    fun loadAccount() {
        if (!client.loggedIn) return
        _ui.update { it.copy(loadingAccount = true) }
        viewModelScope.launch {
            try {
                val me = client.me()
                val subs = runCatching { client.subs() }.getOrDefault(_ui.value.subs)
                val cash = runCatching { client.cashiers() }.getOrNull()
                _ui.update { it.copy(loggedIn = true, login = client.login ?: me.login, balance = me.balance, subs = subs, cashiers = cash ?: it.cashiers, loadingAccount = false) }
            } catch (e: Panel.ApiException) {
                _ui.update { it.copy(loadingAccount = false, loggedIn = client.loggedIn, login = client.login) }
                if (e.code != 401) _events.tryEmit(Event.Toast(e.message ?: "Ошибка"))
                else _events.tryEmit(Event.Toast(e.message ?: "Войдите снова"))
            } catch (e: Exception) {
                _ui.update { it.copy(loadingAccount = false) }
            }
        }
    }

    fun signIn(id: String, password: String) = auth { client.signIn(id, password) }
    fun signUp(id: String, password: String, confirm: String, referral: String?) {
        val errs = Panel.registerErrors(id, password, confirm)
        if (errs.isNotEmpty()) { _ui.update { it.copy(authError = errs.joinToString("; ")) }; return }
        auth { client.signUp(id, password, confirm, referral) }
    }

    private fun auth(block: suspend () -> Unit) {
        _ui.update { it.copy(authBusy = true, authError = null) }
        viewModelScope.launch {
            try {
                block()
                _ui.update { it.copy(authBusy = false, loggedIn = true, login = client.login) }
                loadAccount()
            } catch (e: Exception) {
                _ui.update { it.copy(authBusy = false, authError = e.message ?: "Ошибка входа") }
            }
        }
    }

    fun signOut() {
        poll?.cancel()
        viewModelScope.launch {
            client.signOut()
            _ui.update { it.copy(loggedIn = false, login = null, balance = null, subs = emptyList(), cashiers = null, pending = null) }
        }
    }

    fun setEndpoints(api: String, site: String) {
        client.base = api; client.site = site
        _ui.update { it.copy(base = client.base, site = client.site) }
        loadPlans()
    }

    fun buy(plan: Panel.Plan, payMethod: String, cashierId: String?) = purchase(plan.id, plan.name) { client.order(plan.id, _ui.value.promo, payMethod, cashierId) }
    fun renew(sub: Panel.Sub) = purchase(sub.planId ?: sub.id, sub.planName) { client.renew(sub.id) }

    private fun purchase(key: String, name: String, block: suspend () -> Panel.OrderResult) {
        if (_ui.value.busyPlanId != null) return
        _ui.update { it.copy(busyPlanId = key) }
        viewModelScope.launch {
            try {
                val r = block()
                _ui.update { it.copy(busyPlanId = null) }
                when {
                    r.provisionOk == false -> {
                        _events.tryEmit(Event.Toast("Оплата прошла, но VPN не активирован: ${r.provisionError ?: "обратитесь в поддержку"}"))
                        loadAccount()
                    }
                    r.paid -> {
                        _ui.update { it.copy(pending = Pending(r.paymentId ?: "", r.subscriptionId, name, null, "SUCCEEDED", "Оплачено, активируем VPN…")) }
                        finishPaid(r.subscriptionId, name)
                    }
                    r.paymentId != null -> {
                        val url = Panel.payPageUrl(r.payUrl, client.site)
                        val p = Pending(r.paymentId, r.subscriptionId, name, url)
                        _ui.update { it.copy(pending = p) }
                        client.pending = listOf(p.paymentId, p.subscriptionId.orEmpty(), name.replace("|", " "), url.orEmpty()).joinToString("|")
                        if (url != null) _events.tryEmit(Event.Open(url)) else _events.tryEmit(Event.Toast("Заказ создан, ожидается оплата"))
                        startPolling()
                    }
                    else -> _events.tryEmit(Event.Toast("Заказ создан, ожидается оплата"))
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busyPlanId = null) }
                _events.tryEmit(Event.Toast("Покупка не удалась: ${e.message}"))
            }
        }
    }

    /** Poll POST /payments/:id/check every 4 s (the panel allows one check per 3 s) for up to 30 min. */
    private fun startPolling() {
        poll?.cancel()
        poll = viewModelScope.launch {
            val until = System.currentTimeMillis() + 30 * 60_000L
            while (System.currentTimeMillis() < until) {
                val p = _ui.value.pending ?: return@launch
                try {
                    val c = client.check(p.paymentId)
                    when (c.status) {
                        "SUCCEEDED" -> {
                            client.pending = null
                            _ui.update { it.copy(pending = p.copy(status = c.status, message = "Оплачено, активируем VPN…", subscriptionId = c.subscriptionId ?: p.subscriptionId)) }
                            finishPaid(c.subscriptionId ?: p.subscriptionId, p.planName)
                            return@launch
                        }
                        "FAILED", "CANCELLED", "REFUNDED" -> {
                            client.pending = null
                            _ui.update { it.copy(pending = p.copy(status = c.status, message = "Платёж: ${Panel.statusLabel(c.status).lowercase()}", failed = true, done = true)) }
                            return@launch
                        }
                    }
                } catch (e: Panel.ApiException) {
                    if (e.code == 401 || e.code == 404) {
                        client.pending = null
                        _ui.update { it.copy(pending = p.copy(message = e.message ?: "Ошибка", failed = true, done = true)) }
                        return@launch
                    }
                } catch (_: Exception) {}
                delay(4_000)
            }
            _ui.value.pending?.let { p -> _ui.update { it.copy(pending = p.copy(message = "Оплата не подтверждена за 30 минут. Нажмите «Проверить» позже.", done = true)) } }
        }
    }

    /** Return from the payment page → check right away. */
    fun recheck() {
        val p = _ui.value.pending ?: return
        if (p.done && !p.failed) return
        _ui.update { it.copy(pending = p.copy(done = false, failed = false, message = "Проверяем оплату…")) }
        startPolling()
    }

    /** 1.0.43: antibs2client://payment-return?order=… — check right away (or find the order's payment after a restart). */
    fun onPaymentReturn(orderId: String) {
        val p = _ui.value.pending
        if (p != null) { recheck(); return }
        if (orderId.isEmpty() || !client.loggedIn) return
        viewModelScope.launch {
            val found = runCatching { Panel.paymentOfOrder(client.orders(), orderId) }.getOrNull() ?: return@launch
            val np = Pending(found.first, null, found.second.ifBlank { "Покупка" }, null, message = "Проверяем оплату…")
            _ui.update { it.copy(pending = np) }
            startPolling()
        }
    }

    fun reopenPayment() { _ui.value.pending?.payUrl?.let { _events.tryEmit(Event.Open(it)) } }

    fun dismissPending() {
        poll?.cancel(); client.pending = null
        _ui.update { it.copy(pending = null) }
    }

    /** Paid → wait (≤ 60 s) for the subscription to become ACTIVE with a subscriptionUrl, then import it. */
    private suspend fun finishPaid(subId: String?, name: String) {
        var sub: Panel.Sub? = null
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            sub = runCatching { if (subId != null) client.sub(subId) else client.subs().firstOrNull { it.status == "ACTIVE" } }.getOrNull()
            if (sub != null && sub.status == "ACTIVE" && sub.subscriptionUrl != null) break
            if (sub != null && sub.status in setOf("BLOCKED", "DISABLED")) break
            delay(3_000)
        }
        val url = sub?.subscriptionUrl
        val p = _ui.value.pending
        if (sub?.status == "ACTIVE" && url != null) {
            _events.tryEmit(Event.Import(url, sub.planName.ifBlank { name }))
            _ui.update { it.copy(pending = p?.copy(status = "SUCCEEDED", message = "Готово: подписка «${sub.planName.ifBlank { name }}» добавлена в «Серверы»", done = true)) }
        } else {
            _ui.update { it.copy(pending = p?.copy(message = "Оплата прошла, но ключ ещё не выдан. Откройте «Мои подписки» чуть позже.", done = true)) }
        }
        loadAccount()
    }

    fun importSub(s: Panel.Sub) { s.subscriptionUrl?.let { _events.tryEmit(Event.Import(it, s.planName)) } }
}

/** Desktop: one shop view model for the app's lifetime (Android scopes it to the activity). */
object ShopVm { val instance by lazy { ShopViewModel(android.app.Application.instance) } }
