package net.antibs.client.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.antibs.client.data.Hwid
import net.antibs.client.data.RemoteSubscription
import net.antibs.client.data.SubAutoUpdate
import net.antibs.client.data.SubscriptionRouting
import net.antibs.client.service.AntiBSVpnService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 1.0.25: one subscription refresh path shared by the UI (↻, pull-to-refresh, app open) and the
 * background «Автообновление подписок» worker: HWID headers, deleted/edited host overrides (in
 * [AppStore.applyRefresh]), auto-routing incl. geo files and notification, and a reconnect only
 * when the tunnel is up and the selected host or the active routing actually changed.
 */
object SubscriptionSync {
    private const val ROUTING_CHANNEL = "antibs_routing"
    private const val ROUTING_NOTIFY_ID = 4107
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    class Fetched(
        val result: Result<RemoteSubscription>,
        val applied: SubscriptionRouting.Applied?,
        val hostChanged: Boolean,
    )

    /** Fetch + apply one subscription; null when it isn't remote or is already being refreshed. */
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

    /** Geo files, log + notification for an auto-routing outcome; tells whether the running tunnel is affected. */
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
        val failed = o is SubscriptionRouting.Outcome.Failed
        VpnState.log(text)
        notifyRouting(ctx, text, failed)
        return RoutingResult(text, failed, affects)
    }

    fun isConnected(): Boolean = VpnState.status.value.state == ConnState.CONNECTED

    fun reconnect(ctx: Context, why: String) {
        VpnState.log("переподключение: $why")
        try { AntiBSVpnService.connect(ctx) } catch (e: Throwable) { VpnState.log("переподключение не удалось: ${e.message}") }
    }

    /** Physical (non-VPN) network is unmetered — «Только по Wi‑Fi». */
    fun unmeteredNow(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm?.let { SpeedNet.physicalNetwork(it) }
        n != null && cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (_: Throwable) { false }

    private fun notifyRouting(ctx: Context, text: String, error: Boolean) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
            nm.createNotificationChannel(android.app.NotificationChannel(ROUTING_CHANNEL, "Маршрутизация из подписок", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open = android.app.PendingIntent.getActivity(ctx, 0,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName), android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(ctx, ROUTING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle(if (error) "Ошибка маршрутизации" else "Маршрутизация")
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(ROUTING_NOTIFY_ID, n)
        } catch (e: Throwable) {
            VpnState.log("уведомление: ${e.message}")
        }
    }

    // ───────── WorkManager ─────────

    /** (Re)schedule or cancel the periodic job from the current settings. UPDATE keeps the running period. */
    fun schedule(ctx: Context, st: AppState) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            val plan = SubAutoUpdate.plan(st.subAutoUpdate, st.subUpdateHours, st.subUpdateWifiOnly)
            if (plan == null) { wm.cancelUniqueWork(SubAutoUpdate.WORK_NAME); return }
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (plan.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<SubAutoUpdateWorker>(plan.hours.toLong(), TimeUnit.HOURS)
                .setConstraints(c)
                .build()
            wm.enqueueUniquePeriodicWork(SubAutoUpdate.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (e: Throwable) {
            VpnState.log("автообновление подписок: планировщик: ${e.message}")
        }
    }

    /** Refresh every due subscription (worker); one reconnect at most. Returns how many were refreshed. */
    suspend fun refreshDue(ctx: Context, store: AppStore): Int {
        val st = store.snapshot()
        val due = SubAutoUpdate.dueSubscriptions(st.subscriptions, System.currentTimeMillis(), st.subAutoUpdate, st.subUpdateHours)
        var n = 0
        var hostChanged = false
        var routing = false
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

class SubAutoUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = AppStore(applicationContext)
        if (!store.snapshot().subAutoUpdate) return Result.success()
        return try {
            SubscriptionSync.refreshDue(applicationContext, store)
            Result.success()
        } catch (e: Throwable) {
            VpnState.log("автообновление подписок: ${e.message}")
            Result.success()
        }
    }
}
