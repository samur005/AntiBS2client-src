package net.antibs.client.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.antibs.client.data.RemoteSubscription
import net.antibs.client.data.SubAutoUpdate
import net.antibs.client.data.SubscriptionRouting
import net.antibs.client.data.Hwid
import java.util.concurrent.ConcurrentHashMap

/**
 * Same refresh path as on Android (HWID headers, overrides in [AppStore.applyRefresh], auto-routing incl. geo files,
 * reconnect only when needed). Desktop: the periodic «Автообновление подписок» is an in-process timer instead of
 * WorkManager, and the routing notice goes to the snackbar / log only.
 */
object SubscriptionSync {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    /** Set by the desktop app: reconnect the running tunnel. */
    @Volatile var reconnector: ((String) -> Unit)? = null

    class Fetched(val result: Result<RemoteSubscription>, val applied: SubscriptionRouting.Applied?, val hostChanged: Boolean)

    suspend fun fetchAndApply(store: AppStore, subId: String): Fetched? {
        val sub = store.subscriptions().firstOrNull { it.id == subId } ?: return null
        if (!SubAutoUpdate.isRemote(sub)) return null
        if (!inFlight.add(subId)) return null
        try {
            val st = store.snapshot()
            val hwid = Hwid.effective(st.sendHwid, st.customHwid, store.hwid, sub.hwidOverride)
            val r = SubscriptionFetcher.fetch(sub.url, sub.id, hwid, sub.kind)
            val before = store.selectedServer()
            val applied = store.applyRefresh(subId, r.getOrNull(), r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            val after = store.selectedServer()
            val changed = r.isSuccess && before?.subscriptionId == subId && SubAutoUpdate.hostConfigChanged(before, after)
            return Fetched(r, applied, changed)
        } finally {
            inFlight.remove(subId)
        }
    }

    class RoutingResult(val text: String?, val failed: Boolean, val affectsTunnel: Boolean)

    suspend fun handleRouting(ctx: Context, store: AppStore, a: SubscriptionRouting.Applied?): RoutingResult {
        if (a == null) return RoutingResult(null, false, false)
        var outcome = a.outcome
        val prof = a.profile
        var geoChanged = false
        if (prof != null && prof.needsGeoDownload) {
            try {
                val withGeo = GeoFiles.ensure(ctx, prof)
                store.update { st -> st.copy(adv = st.adv.copy(routingProfiles = st.adv.routingProfiles.map { if (it.id == prof.id) withGeo else it })) }
                geoChanged = true
                VpnState.log("маршрутизация «${prof.name}»: geo-файлы загружены (geoip ${withGeo.geoipTags.size}, geosite ${withGeo.geositeTags.size} категорий)")
            } catch (e: Throwable) {
                VpnState.log("маршрутизация «${prof.name}»: не удалось скачать geo-файлы: ${e.message}")
                if (outcome !is SubscriptionRouting.Outcome.Failed && outcome != null)
                    outcome = SubscriptionRouting.Outcome.Failed(prof.name, "не удалось скачать geoip/geosite (${e.message?.take(60)}); правила с ними пропущены")
            }
        }
        runCatching { GeoFiles.prune(ctx, store.snapshot().adv.routingProfiles) }
        val activeNow = store.snapshot().adv.activeRoutingId
        val affects = prof != null && activeNow == prof.id && (outcome != null || geoChanged)
        val o = outcome ?: return RoutingResult(null, false, affects)
        val text = SubscriptionRouting.message(o)
        VpnState.log(text)
        return RoutingResult(text, o is SubscriptionRouting.Outcome.Failed, affects)
    }

    fun isConnected(): Boolean = VpnState.status.value.state == ConnState.CONNECTED

    fun reconnect(@Suppress("UNUSED_PARAMETER") ctx: Context, why: String) {
        VpnState.log("переподключение: $why")
        try { reconnector?.invoke(why) } catch (e: Throwable) { VpnState.log("переподключение не удалось: ${e.message}") }
    }

    /** Desktop networks are treated as unmetered. */
    fun unmeteredNow(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timer: Job? = null

    /** Periodic refresh while the app runs (checked every 15 min against each subscription's age). */
    @Synchronized
    fun schedule(ctx: Context, st: AppState) {
        timer?.cancel(); timer = null
        val plan = SubAutoUpdate.plan(st.subAutoUpdate, st.subUpdateHours, st.subUpdateWifiOnly) ?: return
        timer = scope.launch {
            while (true) {
                delay(15 * 60_000L)
                runCatching { refreshDue(ctx, AppStore(ctx)) }.onFailure { VpnState.log("автообновление подписок: ${it.message}") }
                if (plan.hours <= 0) break
            }
        }
    }

    suspend fun refreshDue(ctx: Context, store: AppStore): Int {
        val st = store.snapshot()
        val due = SubAutoUpdate.dueSubscriptions(st.subscriptions, System.currentTimeMillis(), st.subAutoUpdate, st.subUpdateHours)
        var n = 0; var hostChanged = false; var routing = false
        for (s in due) {
            val f = fetchAndApply(store, s.id) ?: continue
            n++
            hostChanged = hostChanged || f.hostChanged
            routing = handleRouting(ctx, store, f.applied).affectsTunnel || routing
            f.result.exceptionOrNull()?.let { VpnState.log("автообновление «${s.title.ifBlank { s.url }}»: ${it.message}") }
        }
        if (n > 0) VpnState.log("автообновление подписок: обновлено $n")
        if (SubAutoUpdate.shouldReconnect(isConnected(), hostChanged, routing))
            reconnect(ctx, if (hostChanged) "параметры выбранного сервера изменились" else "маршрутизация изменилась")
        return n
    }
}
