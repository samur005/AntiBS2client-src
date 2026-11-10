package net.antibs.client.ui

import kotlinx.coroutines.isActive
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.antibs.client.data.I18n
import net.antibs.client.data.AdvancedSettings
import net.antibs.client.data.EngineKind
import net.antibs.client.data.RoutingImport
import net.antibs.client.data.RoutingProfile
import net.antibs.client.data.XrayConfigBuilder
import net.antibs.client.engine.XrayCore
import net.antibs.client.engine.ConnState
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.antibs.client.data.SettingsBackup
import net.antibs.client.engine.SettingsBackupIo
import net.antibs.client.service.VpnScheduler
import net.antibs.client.data.Hwid
import net.antibs.client.data.ImportOutcome
import net.antibs.client.data.ImportText
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.XrayProfile
import net.antibs.client.engine.AppState
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.SubscriptionFetcher
import net.antibs.client.engine.ThemeMode
import net.antibs.client.engine.UiSettings
import net.antibs.client.engine.UserPrefsStore
import net.antibs.client.engine.VpnState
import net.antibs.client.service.AntiBSVpnService
import net.antibs.client.engine.toUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import net.antibs.client.engine.TunnelProbe
import java.net.InetSocketAddress
import java.net.Socket


class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = AppStore(app).also {
        SettingsBackupIo.applyFirstRunDefaults(app, it) { styles, schedule ->
            Styles.get(app).importBackup(styles)
            if (schedule != null) {
                VpnScheduler.save(app, schedule)
                VpnScheduler.arm(app, schedule)
            }
        }
        it.ensureSeeded()
    }
    private val userPrefs = UserPrefsStore(app)
    val state: StateFlow<AppState> = store.state

    /** Applied synchronously on every edit so Compose does not wait for DataStore disk IO. */
    private val _ui = MutableStateFlow(store.snapshot().toUi())
    val ui: StateFlow<UiSettings> = _ui
    private var uiTouched = false

    init {
        viewModelScope.launch {
            try {
                val disk = userPrefs.readOrNull()
                val local = store.snapshot().toUi()
                if (!uiTouched && disk != null && disk.rev > local.rev) {
                    store.syncSettings(disk)
                    _ui.value = store.snapshot().toUi()
                } else if (disk == null || disk != store.snapshot().toUi()) {
                    userPrefs.write(store.snapshot().toUi())
                }
            } catch (e: Exception) {
                net.antibs.client.engine.VpnState.log("настройки: ${e.message}")
            }
        }
    }

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pings: StateFlow<Map<String, Long>> = _pings
    /** Server ids whose ping is queued or being measured right now (UI shows a spinner instead of the value). */
    private val _pinging = MutableStateFlow<Set<String>>(emptySet())
    val pinging: StateFlow<Set<String>> = _pinging
    /** 2.0.22: ids of the current/last bulk ping (ping-all / subscription) for the «Всего · Пропинговано · Недоступно» counter. */
    private val _pingBatch = MutableStateFlow<Set<String>>(emptySet())
    val pingBatch: StateFlow<Set<String>> = _pingBatch
    /** 2.0.29: bulk ping was stopped by the user («Проверка остановлена» until the bar hides). */
    private val _pingStopped = MutableStateFlow(false)
    val pingStopped: StateFlow<Boolean> = _pingStopped
    private val pingJobs = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<kotlinx.coroutines.Job>())

    // Declared before the init that auto-pings. Shared gates so concurrent auto-pings (several subscriptions refreshing at once) stay bounded.
    /** 2.0.29: ABS uses 10; we allow 16 concurrent URL/Xray probes for faster ping-all. */
    private val urlPingGate = Semaphore(16)
    /** 2.0.29: TCP/ICMP can run wider — no core per server. */
    private val sockPingGate = Semaphore(32)
    /** 1.0.30: qWDTT runs over VK TURN; at most 2 handshake pings at a time (each is a short-lived Go process). */
    private val qwdttPingGate = Semaphore(2)


    private val _toast = MutableStateFlow<String?>(null)

    // ---- 1.0.19: ping through the active tunnel drives the header status dot ----
    private val _tunnelPing = MutableStateFlow(UiLogic.TunnelPing.UNKNOWN)
    /** Result of the latest check through the running tunnel (see [UiLogic.statusDot]). */
    val tunnelPing: StateFlow<UiLogic.TunnelPing> = _tunnelPing
    // 2.0.8: banner «VPN пинг» / «VK пинг» + health (red power button)
    private val _vpnPingMs = MutableStateFlow<Long?>(null); val vpnPingMs: StateFlow<Long?> = _vpnPingMs
    private val _vpnFails = MutableStateFlow(0); val vpnFails: StateFlow<Int> = _vpnFails
    private val _directMs = MutableStateFlow<Long?>(null); val directMs: StateFlow<Long?> = _directMs
    private val _directFails = MutableStateFlow(0); val directFails: StateFlow<Int> = _directFails
    private val _netValidated = MutableStateFlow(true); val netValidated: StateFlow<Boolean> = _netValidated
    private val _vpnBusy = MutableStateFlow(false); val vpnBusy: StateFlow<Boolean> = _vpnBusy
    private val _directBusy = MutableStateFlow(false); val directBusy: StateFlow<Boolean> = _directBusy
    private val directKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var directJob: kotlinx.coroutines.Job? = null
    private fun noteVpn(ms: Long?) { _vpnPingMs.value = ms ?: -1L; _vpnFails.value = net.antibs.client.engine.NetMonitor.nextFails(_vpnFails.value, ms != null) }

    /** Home on screen + app resumed → direct ping loop (auto-ping interval, 30 s when auto-ping is off). */
    fun setHomeVisible(visible: Boolean) {
        if (!visible) { directJob?.cancel(); directJob = null; return }
        if (directJob?.isActive == true) return
        directJob = viewModelScope.launch {
            while (true) {
                measureDirect()
                val ap = store.snapshot().adv.autoPing
                val period = if (ap.on) ap.periodMs else 30_000L
                if (withTimeoutOrNull(period) { directKick.receive() } != null) delay(800)
            }
        }
    }

    private suspend fun measureDirect() {
        _directBusy.value = true
        try {
            val app = getApplication<Application>()
            val ms = withContext(kotlinx.coroutines.Dispatchers.IO) {
                net.antibs.client.engine.NetMonitor.probe(net.antibs.client.engine.NetMonitor.directClient(app), net.antibs.client.engine.NetMonitor.DIRECT_URL)
            }
            _netValidated.value = net.antibs.client.engine.NetMonitor.netValidated(app)
            _directMs.value = ms
            _directFails.value = net.antibs.client.engine.NetMonitor.nextFails(_directFails.value, ms >= 0)
        } finally { _directBusy.value = false }
    }

    /** Tap on «VK пинг». */
    fun pingDirect() { if (!_directBusy.value) viewModelScope.launch { measureDirect() } }
    private val tunnelKick = Channel<Unit>(Channel.CONFLATED)
    private var tunnelJob: Job? = null
    @Volatile private var tunnelKey: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // 1.0.36: notices from the VPN service (auto-select switched) → snackbar
        viewModelScope.launch {
            VpnState.notice.collect { n -> if (n != null) { _toast.value = n; VpnState.consumeNotice() } }
        }
        // Every connected session (connect, host switch = new session) gets an immediate check, then one per
        // TUNNEL_PING_INTERVAL_MS; leaving CONNECTED stops the loop and resets the dot state.
        viewModelScope.launch {
            VpnState.status.collect { s ->
                val key = UiLogic.tunnelSessionKey(s.state, s.since, s.serverName)
                if (key == tunnelKey) return@collect
                tunnelKey = key
                tunnelJob?.cancel()
                tunnelJob = null
                _vpnFails.value = 0; _vpnPingMs.value = null
                directKick.trySend(Unit)
                if (key == null) {
                    _tunnelPing.value = UiLogic.TunnelPing.UNKNOWN
                } else {
                    _tunnelPing.value = UiLogic.TunnelPing.CHECKING
                    val serverId = sessionServerId(s.serverName)
                    tunnelJob = viewModelScope.launch { launch { bgListPingLoop() }; tunnelLoop(serverId) }
                }
            }
        }
        watchNetwork()
    }

    private fun sessionServerId(serverName: String): String? {
        val st = store.snapshot()
        val sel = UiLogic.effectiveSelection(st.selectedServerId, st.servers, st.subscriptions)
        val bySel = st.servers.firstOrNull { it.id == sel }
        if (bySel != null && (serverName.isEmpty() || bySel.name == serverName)) return bySel.id
        return st.servers.firstOrNull { it.name == serverName }?.id ?: bySel?.id
    }

    private suspend fun tunnelLoop(serverId: String?) {
        while (tunnelKick.tryReceive().isSuccess) { /* drop kicks from before this session */ }
        delay(UiLogic.TUNNEL_PING_FIRST_DELAY_MS)
        var last: UiLogic.TunnelPing? = null
        while (true) {
            _vpnBusy.value = true
            val ms = try { tunnelCheck(serverId) } finally { _vpnBusy.value = false }
            noteVpn(ms)
            if (ms == null) directKick.trySend(Unit)   // tell «no internet» from «server down»
            val now = if (ms != null) UiLogic.TunnelPing.OK else UiLogic.TunnelPing.FAIL
            _tunnelPing.value = now
            val ap = store.snapshot().adv.autoPing
            if (serverId != null && ap.on) _pings.update { it + (serverId to UiLogic.tunnelPingValue(ms)) }
            if (now != last) VpnState.log(if (ms != null) "пинг через туннель: $ms мс" else "пинг через туннель: нет ответа")
            last = now
            // Next check after the interval, or sooner (~1.5 s after) when the device network changes.
            val period = store.snapshot().adv.autoPing.let { if (it.on) it.periodMs else UiLogic.TUNNEL_PING_INTERVAL_MS }
            if (withTimeoutOrNull(period) { tunnelKick.receive() } != null) {
                delay(1_500)
                while (tunnelKick.tryReceive().isSuccess) { }
            }
        }
    }

    /** 2.0.6 (ABS): tap on «пинг: N мс» — measure the current server right now (through the tunnel when connected). */
    fun pingCurrent() {
        val st = store.snapshot()
        val vs = VpnState.status.value
        if (tunnelKey != null) {
            val id = sessionServerId(vs.serverName) ?: return
            if (id in _pinging.value) return
            _pinging.update { it + id }
            viewModelScope.launch {
                try {
                    _vpnBusy.value = true
                    val ms = try { tunnelCheck(id) } finally { _vpnBusy.value = false }
                    noteVpn(ms)
                    _pings.update { it + (id to UiLogic.tunnelPingValue(ms)) }
                    _tunnelPing.value = if (ms != null) UiLogic.TunnelPing.OK else UiLogic.TunnelPing.FAIL
                } finally { _pinging.update { it - id } }
            }
        } else {
            val sel = UiLogic.effectiveSelection(st.selectedServerId, st.servers, st.subscriptions)
            st.servers.firstOrNull { it.id == sel }?.let { if (it.id !in _pinging.value) pingOne(it) }
        }
    }

    /** Server whose ping the home chip shows: the live session host, else the selection. */
    fun currentPingId(): String? = if (tunnelKey != null) sessionServerId(VpnState.status.value.serverName)
        else store.snapshot().let { UiLogic.effectiveSelection(it.selectedServerId, it.servers, it.subscriptions) }

    private val failPrefs by lazy { getApplication<Application>().getSharedPreferences("autodelete_ping", android.content.Context.MODE_PRIVATE) }

    /** 2.0.6 (ABS BgListPing): while connected, ping the whole list every 30 min (2 at a time) → auto-delete rule. */
    private suspend fun bgListPingLoop() {
        delay(net.antibs.client.data.AutoDeletePing.BG_FIRST_DELAY_MS)
        while (true) {
            runCatching { bgListPingRound() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            delay(net.antibs.client.data.AutoDeletePing.BG_PERIOD_MS)
        }
    }

    private suspend fun bgListPingRound() {
        val ap = store.snapshot().adv.autoPing
        if (!ap.autoDelete || VpnState.status.value.state != ConnState.CONNECTED) return
        val list = store.servers().filterNot { UiLogic.isAutoSelectEntry(it) }
        if (list.isEmpty()) return
        val sem = kotlinx.coroutines.sync.Semaphore(net.antibs.client.data.AutoDeletePing.BG_PARALLEL)
        val results = kotlinx.coroutines.coroutineScope {
            list.map { s -> async { s.id to sem.withPermit { runCatching { measure(s) }.getOrDefault(-1L) } } }.map { it.await() }.toMap()
        }
        _pings.update { it + results.filterValues { v -> v >= -1 } }
        if (VpnState.status.value.state != ConnState.CONNECTED) return
        val (protect, name) = connectedProtect()
        val prot: Set<String?> = protect + list.filter { name.isNotBlank() && it.name == name }.map { it.id } + currentPingId()
        val res = net.antibs.client.data.AutoDeletePing.decide(results, prot, true,
            net.antibs.client.data.AutoDeletePing.decode(failPrefs.getString("counts", "")), ap.failsToDelete) { directNetOk() }
        failPrefs.edit().putString("counts", net.antibs.client.data.AutoDeletePing.encode(res.counts)).apply()
        when (val o = res.outcome) {
            is net.antibs.client.data.AutoDeletePing.Outcome.Deleted -> {
                var n = 0
                o.ids.forEach { id -> if (store.deleteHost(id) != null) { _pings.update { it - id }; n++ } }
                if (n > 0) { _toast.value = "Удалено $n (${ap.failsToDelete} неудачных проверок подряд)"; VpnState.log("автоудаление: $n") }
            }
            net.antibs.client.data.AutoDeletePing.Outcome.Whitelist -> _toast.value = "Похоже на белые списки — серверы не удалены"
            else -> {}
        }
    }

    /** Direct (app sockets bypass the tunnel) generate_204 check: false = whitelist / bad network → nothing deleted. */
    private suspend fun directNetOk(): Boolean = withContext(Dispatchers.IO) {
        net.antibs.client.data.AutoDeletePing.PROBE_URLS.any { u -> runCatching {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            try { c.requestMethod = "HEAD"; c.connectTimeout = 2500; c.readTimeout = 2500; c.instanceFollowRedirects = false; c.responseCode in 200..399 } finally { c.disconnect() }
        }.getOrDefault(false) }
    }

    /** One check (with a single retry on failure): delay in ms, or null when the tunnel did not answer. */
    private suspend fun tunnelCheck(serverId: String?): Long? {
        val st = store.snapshot()
        val a = st.adv
        val port = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT
        val url = a.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL }
        val timeoutMs = a.pingTimeoutSec.coerceIn(2, 15) * 1000
        val method = UiLogic.tunnelPingMethod(a.pingType)
        repeat(2) { attempt ->
            if (attempt > 0) delay(UiLogic.TUNNEL_PING_RETRY_MS)
            when (val r = withContext(Dispatchers.IO) { TunnelProbe.check(port, url, method, timeoutMs) }) {
                is TunnelProbe.Result.Ok -> return r.ms
                is TunnelProbe.Result.NoInbound -> {
                    // No local SOCKS inbound (e.g. a custom JSON config): fall back to the regular host ping.
                    val s = serverId?.let { id -> st.servers.firstOrNull { it.id == id } } ?: return null
                    val ms = measure(s)
                    if (ms > 0) return ms
                }
                is TunnelProbe.Result.Failed -> Unit
            }
        }
        return null
    }

    /** Re-check soon after the device's default network changes (Wi-Fi ↔ mobile, reconnect). */
    private fun watchNetwork() {
        try {
            val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { directKick.trySend(Unit); if (tunnelKey != null) tunnelKick.trySend(Unit) }
                override fun onLost(network: Network) { directKick.trySend(Unit); if (tunnelKey != null) tunnelKick.trySend(Unit) }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            VpnState.log("сеть: ${e.message}")
        }
    }

    override fun onCleared() {
        speedTest?.cancel()
        netCallback?.let { cb ->
            try { getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        netCallback = null
        super.onCleared()
    }

    // Auto-ping on open only when enabled in settings.
    init {
        XrayCore.installCertPins()
        net.antibs.client.engine.SubscriptionSync.schedule(getApplication(), store.snapshot())
        refreshStale()
        if (store.snapshot().adv.autoPingServers) autoPingOnOpen()
    }
    val toast: StateFlow<String?> = _toast
    fun consumeToast() { _toast.value = null }
    fun message(text: String) { _toast.value = text }

    private fun push(block: (AppState) -> AppState) {
        uiTouched = true
        val next = store.update(block)
        _ui.value = next.toUi()
        viewModelScope.launch {
            try { userPrefs.write(next.toUi()) } catch (e: Exception) {
                net.antibs.client.engine.VpnState.log("DataStore: ${e.message}")
            }
        }
    }

    private fun pushTunnel(block: (AppState) -> AppState) {
        push(block)
        _toast.value = "Применится при следующем подключении"
    }

    fun setTheme(m: ThemeMode) = push { it.copy(theme = m.name.lowercase()) }
    fun setFragmentation(v: Boolean) = pushTunnel { it.copy(fragmentation = v) }
    fun setMux(v: Boolean) = pushTunnel { it.copy(mux = v) }
    fun setIpType(v: String) = pushTunnel { it.copy(ipType = v) }
    fun setAllowLan(v: Boolean) = pushTunnel { it.copy(allowLan = v) }
    fun setBypassLan(v: Boolean) = pushTunnel { it.copy(bypassLan = v) }
    fun setAutostart(@Suppress("UNUSED_PARAMETER") v: Boolean) = push { it.copy(autostart = false) }   // 2.0.9: removed
    fun setXrayTun(v: Boolean) {
        push { it.copy(xrayTun = v) }
        _toast.value = if (v) "Xray TUN: применится при следующем подключении" else "Туннель hev-socks5: применится при следующем подключении"
    }
    fun setSubsExpanded(v: Boolean) = push { it.copy(subsExpanded = v) }
    fun setCompact(v: Boolean) = push { it.copy(compactServers = v) }
    fun toggleSubCollapsed(id: String) = push {
        val cur = it.collapsedSubs.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        it.copy(collapsedSubs = cur.sorted())
    }
    fun setPingTimeout(ms: Int) = push { it.copy(pingTimeoutMs = ms, adv = it.adv.copy(pingTimeoutSec = (ms / 1000).coerceIn(2, 15))) }

    // ---- 1.0.6 advanced settings ----
    /** Tunnel-affecting edit: saved now, used on the next connect. */
    fun setAdv(block: (AdvancedSettings) -> AdvancedSettings) = push { it.copy(adv = block(it.adv)) }
    fun setAdvTunnel(block: (AdvancedSettings) -> AdvancedSettings) {
        push { it.copy(adv = block(it.adv)) }
        if (VpnState.status.value.state == ConnState.CONNECTED) _toast.value = "Применится при следующем подключении"
    }
    fun setPingSettings(block: (AdvancedSettings) -> AdvancedSettings) = push {
        val a = block(it.adv)
        it.copy(adv = a, pingTimeoutMs = a.pingTimeoutSec.coerceIn(2, 15) * 1000)
    }
    fun setHideEmptyManual(v: Boolean) = push { it.copy(adv = it.adv.copy(hideEmptyManual = v)) }

    // ---- 1.0.40: «Файлы георесурсов» (runetfreedom), QS tile ----
    data class FileJob(val busy: Boolean = false, val stage: String = "", val error: String? = null)
    private val _rf = MutableStateFlow(net.antibs.client.engine.BlockFiles.rfStatus(getApplication()))
    val rf: StateFlow<net.antibs.client.engine.BlockFiles.RfStatus> = _rf
    private val _rfJob = MutableStateFlow(FileJob())
    val rfJob: StateFlow<FileJob> = _rfJob

    private fun mb(n: Long) = String.format(java.util.Locale.US, "%.1f МБ", n / 1048576.0)

    // ---- 2.0.15: ABS ad-block counter (the service adds hits to adblock_counts.json) ----
    private val countsFile get() = java.io.File(getApplication<Application>().filesDir, net.antibs.client.data.AdBlockCounter.COUNTS_NAME)
    private val _adCounts = MutableStateFlow(net.antibs.client.data.AdBlockCounter.Counts())
    val adCounts: StateFlow<net.antibs.client.data.AdBlockCounter.Counts> = _adCounts
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { net.antibs.client.engine.BlockFiles.cleanupLegacy(getApplication()) } catch (_: Throwable) {}
            while (true) {
                _adCounts.value = net.antibs.client.data.AdBlockCounter.load(countsFile)
                kotlinx.coroutines.delay(2000)
            }
        }
    }
    fun resetAdCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            net.antibs.client.data.AdBlockCounter.reset(countsFile)
            _adCounts.value = net.antibs.client.data.AdBlockCounter.load(countsFile)
        }
    }

    fun updateGeoFiles() {
        if (_rfJob.value.busy) return
        _rfJob.value = FileJob(true, "Проверяем версию…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val st = net.antibs.client.engine.BlockFiles.updateRf(getApplication()) { name, done, total ->
                    _rfJob.value = FileJob(true, "$name: ${mb(done)}" + if (total > 0) " из ${mb(total)}" else "")
                }
                _rf.value = st
                _rfJob.value = FileJob()
                _toast.value = if (VpnState.status.value.state == ConnState.CONNECTED) "Файлы обновлены — применятся при следующем подключении" else "Файлы георесурсов обновлены"
            } catch (e: Exception) {
                _rfJob.value = FileJob(error = "Не удалось обновить: ${e.message}")
            }
        }
    }

    fun deleteGeoFiles() {
        net.antibs.client.engine.BlockFiles.deleteRf(getApplication())
        _rf.value = net.antibs.client.engine.BlockFiles.rfStatus(getApplication())
    }

    fun setTrafficPreset(p: String) = setAdvTunnel { it.copy(trafficPreset = p) }

    /** Android 13+: system dialog «Добавить кнопку в шторку». */
    fun requestQsTile(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT < 33) { _toast.value = "Откройте шторку → ✎ и перетащите плитку «AntiBS»"; return }
        val sbm = activity.getSystemService(android.app.StatusBarManager::class.java) ?: return
        sbm.requestAddTileService(
            android.content.ComponentName(activity, net.antibs.client.service.VpnTileService::class.java),
            "AntiBS", android.graphics.drawable.Icon.createWithResource(activity, net.antibs.client.R.drawable.ic_stat_vpn),
            activity.mainExecutor,
        ) { r ->
            _toast.value = when (r) {
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Кнопка добавлена в шторку"
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Кнопка уже в шторке"
                else -> "Кнопка не добавлена"
            }
        }
    }

    /** Reconnect so a routing / per-app change takes effect immediately. */
    fun reconnectIfConnected() {
        if (VpnState.status.value.state == ConnState.CONNECTED) AntiBSVpnService.connect(getApplication())
    }

    fun upsertRouting(p: RoutingProfile) = push { st ->
        val list = st.adv.routingProfiles.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        st.copy(adv = st.adv.copy(routingProfiles = list))
    }

    fun newRoutingProfile(): RoutingProfile =
        RoutingProfile(id = "route-" + java.util.UUID.randomUUID().toString().take(8), name = "Новый профиль")

    fun deleteRouting(id: String) = push { st ->
        st.copy(adv = st.adv.copy(
            routingProfiles = st.adv.routingProfiles.filterNot { it.id == id },
            activeRoutingId = st.adv.activeRoutingId?.takeIf { it != id },
        ))
    }

    fun setActiveRouting(id: String?) {
        push { it.copy(adv = it.adv.copy(activeRoutingId = id)) }
        reconnectIfConnected()
    }

    fun importRouting(text: String): Boolean {
        val id = "route-" + java.util.UUID.randomUUID().toString().take(8)
        val p = RoutingImport.parse(text, id)
        if (p == null) { _toast.value = "В буфере нет профиля маршрутизации (Happ JSON / happ://routing/…)"; return false }
        upsertRouting(p)
        _toast.value = "Импортирован «${p.name}»: правил ${p.rules.size}"
        return true
    }

    /**
     * 2.0.34: versioned settings-only backup (no subscriptions, servers, secrets, LAN keys or traffic caches).
     */
    fun exportSettings(): String {
        val ctx = getApplication<Application>()
        return SettingsBackup.toJson(
            SettingsBackupIo.fromAppState(
                store.snapshot(),
                appVersion = net.antibs.client.BuildConfig.VERSION_NAME,
                styles = Styles.get(ctx).exportBackup(),
                schedule = VpnScheduler.load(ctx),
            )
        )
    }

    fun parseSettingsBackup(text: String) = SettingsBackupIo.parseFlexible(text)

    fun applySettingsBackup(b: SettingsBackup) {
        val ctx = getApplication<Application>()
        val before = store.snapshot()
        push { SettingsBackupIo.applyAutoDeleteByUrl(SettingsBackupIo.applyPrefs(it, b.prefs), b.prefs.autoDeleteUnreachableByUrl) }
        Styles.get(ctx).importBackup(b.styles)
        b.schedule?.let { VpnScheduler.save(ctx, it); VpnScheduler.arm(ctx, it) }
        if (SettingsBackupIo.affectsTunnel(before, store.snapshot())) reconnectIfConnected()
        _toast.value = "Настройки восстановлены"
    }

    fun importSettings(text: String) {
        val b = parseSettingsBackup(text).getOrElse {
            _toast.value = "Не удалось прочитать файл: ${it.message?.take(80)}"; return
        }
        applySettingsBackup(b)
    }
    fun setQwdtt(vkHashes: String? = null, dns: String? = null, obfs: String? = null, turnTcp: Boolean? = null) = push {
        it.copy(
            vkHashes = vkHashes ?: it.vkHashes,
            qwdttDns = dns ?: it.qwdttDns,
            qwdttObfs = obfs ?: it.qwdttObfs,
            qwdttTurnTcp = turnTcp ?: it.qwdttTurnTcp,
        )
    }

    fun select(id: String) {
        val cur = store.snapshot()
        val changed = cur.autoSelect || cur.selectedServerId != id
        store.selectServer(id)
        if (UiLogic.shouldHotSwitch(VpnState.status.value.state, changed)) {
            AntiBSVpnService.connect(getApplication())
        }
    }

    fun refresh(id: String, silent: Boolean = false) {
        val sub = store.subscriptions().firstOrNull { it.id == id } ?: return
        if (sub.kind == "manual" || sub.url.isBlank()) return
        if (id in _refreshing.value) return
        _refreshing.value = _refreshing.value + id
        viewModelScope.launch {
            val f = try { net.antibs.client.engine.SubscriptionSync.fetchAndApply(store, id) } finally { _refreshing.value = _refreshing.value - id }
            if (f == null) return@launch  // the background auto-update is refreshing it right now
            val r = f.result
            // Fresh host list → measure only if auto-ping enabled.
            if (store.snapshot().adv.autoPingServers) {
                pingServers(UiLogic.pingTargets(store.servers(), id, _pinging.value))
            }
            if (!silent || r.isFailure) _toast.value = r.fold({ r0 -> "«${r0.title ?: sub.title}»: ${UiLogic.subFooterLeft(r0.servers.count { s -> !UiLogic.isSyntheticServer(s.name) })}" }, { "Ошибка обновления: ${it.message}" })
            // after the summary so the routing message is the one the snackbar shows
            val rr = net.antibs.client.engine.SubscriptionSync.handleRouting(getApplication(), store, f.applied)
            rr.text?.let { _toast.value = it }
            if (net.antibs.client.data.SubAutoUpdate.shouldReconnect(net.antibs.client.engine.SubscriptionSync.isConnected(), f.hostChanged, rr.affectsTunnel))
                net.antibs.client.engine.SubscriptionSync.reconnect(getApplication(), if (f.hostChanged) "параметры выбранного сервера изменились" else "маршрутизация изменилась")
        }
    }

    /**
     * Top-bar ↻ and pull-to-refresh: refresh every remote subscription (each pings its hosts when the
     * fetch finishes) and ping hosts that have nothing to refresh (manual «Серверы», orphans) immediately.
     */
    fun refreshAll() {
        val remote = store.subscriptions().filter { it.kind != "manual" && it.url.isNotBlank() }
        remote.forEach { refresh(it.id) }
        val pending = _refreshing.value + remote.map { it.id }
        if (store.snapshot().adv.autoPingServers) {
            pingServers(UiLogic.pingTargetsNotRefreshing(store.servers(), pending, _pinging.value))
        }
    }

    /** App open: ping every visible host whose subscription is not being refreshed right now. */
    private fun autoPingOnOpen() {
        pingServers(UiLogic.pingTargetsNotRefreshing(store.servers(), _refreshing.value, _pinging.value))
    }

    /**
     * 1.0.25 app open: «Автообновление подписок» — refresh subscriptions older than the user's interval
     * (the global setting wins over the panel's profile-update-interval). «Только по Wi‑Fi» is honoured.
     */
    fun refreshStale() {
        val st = store.snapshot()
        if (!st.subAutoUpdate) return
        if (!net.antibs.client.data.SubAutoUpdate.mayRunNow(st.subUpdateWifiOnly, net.antibs.client.engine.SubscriptionSync.unmeteredNow(getApplication()))) return
        net.antibs.client.data.SubAutoUpdate.dueSubscriptions(st.subscriptions, System.currentTimeMillis(), true, st.subUpdateHours)
            .forEach { refresh(it.id, silent = true) }
    }

    // ───────── 1.0.25: «Автообновление подписок» ─────────
    fun setSubAutoUpdate(v: Boolean) { push { it.copy(subAutoUpdate = v) }; reschedule() }
    fun setSubUpdateHours(h: Int) { push { it.copy(subUpdateHours = net.antibs.client.data.SubAutoUpdate.normalizeHours(h)) }; reschedule() }
    fun setSubUpdateWifiOnly(v: Boolean) { push { it.copy(subUpdateWifiOnly = v) }; reschedule() }
    private fun reschedule() = net.antibs.client.engine.SubscriptionSync.schedule(getApplication(), store.snapshot())

    fun addSubscription(url: String) {
        val id = store.addSubscription(url)
        refresh(id)
    }

    /** 2.0.12: LAN receive — map wire-format entries, dedupe by url, then refresh each new one from its url. */
    fun receiveLanSubs(entries: List<kotlinx.serialization.json.JsonObject>): Int {
        val subs = ArrayList<net.antibs.client.data.Subscription>()
        val servers = ArrayList<net.antibs.client.data.ServerEntry>()
        for (o in entries) {
            val asSub = net.antibs.client.data.LanProtocol.fromAbs(o, "sub-" + java.util.UUID.randomUUID().toString().take(8))
            if (asSub != null) { subs += asSub; continue }
            val url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val asServer = net.antibs.client.data.LanProtocol.parseServerWire(url)
            if (asServer != null) servers += asServer
        }
        val ids = store.addReceivedSubscriptions(subs)
        ids.forEach { refresh(it) }
        if (servers.isNotEmpty()) store.addManualServers(servers)
        val n = ids.size + servers.size
        _toast.value = when {
            n == 0 -> "Уже есть или не удалось принять"
            servers.isNotEmpty() && ids.isEmpty() -> "Получено серверов: ${servers.size}"
            servers.isEmpty() -> "Получено подписок: ${ids.size}"
            else -> "Получено: подписок ${ids.size}, серверов ${servers.size}"
        }
        return n
    }

    fun importRaw(text: String) {
        when (val r = ImportText.classify(text, ProtocolSupport.MANUAL_SUB_ID)) {
            is ImportOutcome.SubscriptionUrl -> addSubscription(r.url)
            is ImportOutcome.Servers -> {
                store.addManualServers(r.entries)
                _toast.value = "Добавлено: ${r.entries.size}"
            }
            is ImportOutcome.Failed -> _toast.value = r.message
        }
    }

    /** 1.0.26: qWDTT host created by hand — same ServerEntry shape as a qwdtt://config import. */
    fun addManualQwdtt(q: net.antibs.client.data.QwdttProfile) {
        val id = "m-" + java.util.UUID.randomUUID().toString().take(8)
        store.addManualServers(listOf(ServerEntry(id, EngineKind.QWDTT, q.name, ProtocolSupport.MANUAL_SUB_ID, qwdtt = q)))
        _toast.value = "Сохранено в «${ProtocolSupport.MANUAL_TITLE}»"
    }

    fun addManualProfile(profile: XrayProfile) {
        store.addManualProfile(net.antibs.client.engine.SecretBox.sealProfile(profile))
        _toast.value = "Сохранено в «${ProtocolSupport.MANUAL_TITLE}»"
    }

    fun removeSubscription(id: String) = store.removeSubscription(id)

    /** 1.0.55: drag-reorder on the Servers screen; [displayIds] is the order as shown. Stored in AppState.subscriptions (used everywhere). */
    fun moveSubscription(dragId: String, targetId: String, newestFirst: Boolean) {
        val display = ServerStyleLogic.orderSubs(store.snapshot().subscriptions.map { it.id }, newestFirst)
        val to = display.indexOf(targetId); if (to < 0) return
        reorderSubscriptions(SubOrder.move(display, dragId, to), newestFirst)
    }

    fun reorderSubscriptions(displayIds: List<String>, newestFirst: Boolean) {
        val order = SubOrder.storedFromDisplay(displayIds, newestFirst)
        push { st -> st.copy(subscriptions = SubOrder.apply(st.subscriptions, order) { it.id }) }
    }

    // ---- 1.0.18: HWID ----
    /** Per-install device ID (default HWID). */
    val deviceHwid: String get() = store.hwid

    fun setSendHwid(@Suppress("UNUSED_PARAMETER") v: Boolean) = push { it.copy(sendHwid = true) } // 1.0.32: always on

    /** Empty = back to the device ID. Returns false (and changes nothing) for an invalid HWID. */
    fun setCustomHwid(value: String): Boolean {
        val v = value.trim()
        if (v.isNotEmpty() && !Hwid.isValid(v)) return false
        push { it.copy(customHwid = v) }
        return true
    }

    fun generateHwid() {
        push { it.copy(customHwid = Hwid.generate()) }
        _toast.value = "Новый HWID задан. Обновите подписки"
    }

    fun resetHwid() {
        push { it.copy(customHwid = "") }
        _toast.value = "HWID сброшен на HWID устройства"
    }

    // ───────── 1.0.19: updates (GitHub releases) ─────────
    sealed class UpdateUi {
        data object Idle : UpdateUi()
        data object Checking : UpdateUi()
        data class Available(val u: net.antibs.client.data.UpdateLogic.Update) : UpdateUi()
        data class Downloading(
            val u: net.antibs.client.data.UpdateLogic.Update, val progress: Float,
            val done: Long = 0, val total: Long = 0, val bps: Double = 0.0,
            val startedAt: Long = System.currentTimeMillis(), val minimized: Boolean = false,
        ) : UpdateUi()
        data class Ready(val u: net.antibs.client.data.UpdateLogic.Update, val file: java.io.File) : UpdateUi()
        data class NeedPermission(val u: net.antibs.client.data.UpdateLogic.Update, val file: java.io.File) : UpdateUi()
        data class Error(val message: String) : UpdateUi()
    }
    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update

    fun setAutoUpdateCheck(v: Boolean) = push { it.copy(autoUpdateCheck = v) }

    /** Launch: at most once per 24 h, silent on errors and when up to date. */
    fun autoCheckUpdates() {
        val st = store.snapshot()
        if (!net.antibs.client.data.UpdateLogic.shouldAutoCheck(st.autoUpdateCheck, st.lastUpdateCheck, System.currentTimeMillis())) return
        checkUpdates(manual = false)
    }

    fun checkUpdates(manual: Boolean = true) {
        if (_update.value is UpdateUi.Checking || _update.value is UpdateUi.Downloading) return
        if (manual) _update.value = UpdateUi.Checking
        viewModelScope.launch {
            val r = runCatching { net.antibs.client.engine.Updater.check(net.antibs.client.BuildConfig.VERSION_NAME, "") /* 1.0.38: always samur005/AntiBS2client-releases */ }
            push { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            r.fold({ u ->
                when {
                    u != null -> _update.value = UpdateUi.Available(u)
                    manual -> { _update.value = UpdateUi.Idle; _toast.value = I18n.t("latest_installed", net.antibs.client.BuildConfig.VERSION_NAME) }
                }
            }, { e ->
                VpnState.log("обновления: ${e.message}")
                if (manual) _update.value = UpdateUi.Error(e.message ?: e.javaClass.simpleName)
            })
        }
    }

    fun downloadUpdate(u: net.antibs.client.data.UpdateLogic.Update) {
        val t0 = System.currentTimeMillis()
        _update.value = UpdateUi.Downloading(u, 0f, total = u.apk.size, startedAt = t0)
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            var lastT = System.nanoTime(); var lastB = 0L
            runCatching {
                net.antibs.client.engine.Updater.download(getApplication(), u) { done, total ->
                    if (!isActive) return@download
                    val now = System.nanoTime(); val dt = (now - lastT) / 1e9
                    val prev = _update.value as? UpdateUi.Downloading
                    val bps = if (dt > 0.05) net.antibs.client.data.DownloadStats.smooth(prev?.bps ?: 0.0, (done - lastB) / dt).also { lastT = now; lastB = done } else prev?.bps ?: 0.0
                    _update.value = UpdateUi.Downloading(u, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        done, total, bps, t0, prev?.minimized ?: false)
                }
            }.fold({ f -> installUpdate(u, f) }, { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _update.value = UpdateUi.Error("Загрузка: ${e.message}")
            })
        }
    }

    private var downloadJob: kotlinx.coroutines.Job? = null

    /** 1.0.54: «Отмена» on the download plate — abort, delete the partial file, hide; the next check offers it again. */
    fun cancelUpdateDownload() {
        downloadJob?.cancel(); downloadJob = null
        runCatching { net.antibs.client.engine.Updater.clearPartial(getApplication()) }
        _update.value = UpdateUi.Idle
    }

    fun installUpdate(u: net.antibs.client.data.UpdateLogic.Update, f: java.io.File) {
        val ctx = getApplication<Application>()
        if (!net.antibs.client.engine.Updater.canInstall(ctx)) { _update.value = UpdateUi.NeedPermission(u, f); return }
        _update.value = UpdateUi.Ready(u, f)
        runCatching { net.antibs.client.engine.Updater.install(ctx, f) }.onFailure { _update.value = UpdateUi.Error("Установка: ${it.message}") }
    }

    fun openInstallPermission() = runCatching { net.antibs.client.engine.Updater.openInstallPermission(getApplication()) }
    /** 2.0.22: collapse / restore the download window; the download keeps running. */
    fun setUpdateMinimized(m: Boolean) { _update.update { (it as? UpdateUi.Downloading)?.copy(minimized = m) ?: it } }


    fun dismissUpdate() { _update.value = UpdateUi.Idle }

    /** 1.0.19: per-subscription «Авто-маршрутизация из подписки». */
    fun setSubscriptionAutoRouting(subId: String, on: Boolean) {
        push { st -> st.copy(subscriptions = st.subscriptions.map { if (it.id == subId) it.copy(autoRouting = on) else it }) }
        if (on) refresh(subId, silent = true)
    }

    /** 2.0.35: ⋮ «Автоудаление недоступных» — per subscription auto-hide after ping, default on. */
    fun setSubscriptionAutoDelete(subId: String, on: Boolean) {
        push { st -> st.copy(subscriptions = st.subscriptions.map { if (it.id == subId) it.copy(autoDeleteUnreachable = on) else it }) }
    }

    /**
     * 1.0.19: routing profile delivered by a subscription fetch → fetch its custom geo files, reconnect if it is
     * the active profile and the VPN is up, then tell the user (notification + snackbar).
     */
    fun setSubscriptionHwid(subId: String, value: String): Boolean {
        val v = value.trim()
        if (v.isNotEmpty() && !Hwid.isValid(v)) return false
        push { st -> st.copy(subscriptions = st.subscriptions.map { if (it.id == subId) it.copy(hwidOverride = v) else it }) }
        return true
    }

    /** Settings «Пинговать все». */
    fun pingAll() {
        val servers = store.servers()
        if (servers.isEmpty()) { _toast.value = "Нет серверов для пинга"; return }
        _toast.value = "Пинг серверов…"
        startBatch(servers)
        pingServers(servers)
    }

    /** Subscription ⋮ «Проверить пинг» and (1.0.20) the ping icon on the card. Hosts already being pinged are skipped. */
    fun pingSubscription(subId: String) {
        if (UiLogic.subPinging(store.servers(), subId, _pinging.value)) return
        val targets = UiLogic.pingTargets(store.servers(), subId, _pinging.value)
        if (targets.isEmpty()) { _toast.value = "Нет серверов для пинга"; return }
        val title = store.subscriptions().firstOrNull { it.id == subId }?.title?.ifBlank { null }
        _toast.value = if (title != null) "Пинг «$title»…" else "Пинг серверов…"
        startBatch(targets)
        pingServers(targets)
    }

    /** 2.0.37: ping only the current Home filter's flat list. Auto-delete follows each host's subscription toggle and the usual safety rules. */
    fun pingFiltered(servers: List<ServerEntry>) {
        val targets = UiLogic.pingTargets(servers, busy = _pinging.value)
        if (targets.isEmpty()) {
            if (servers.isEmpty()) _toast.value = "Нет серверов для пинга"
            return
        }
        _toast.value = "Пинг фильтра…"
        startBatch(targets)
        pingServers(targets)
    }

    /** 1.0.51: ping from the chain / balancer picker — the given hosts (all or one group); ones already measuring are skipped. */
    fun pingHosts(servers: List<ServerEntry>) {
        val targets = servers.filter { it.id !in _pinging.value }
        if (targets.isEmpty()) return
        pingServers(targets)
    }

    private fun startBatch(servers: List<ServerEntry>) {
        val ids = servers.map { it.id }.toSet()
        if (_pingStopped.value) { _pingStopped.value = false; _pingBatch.value = emptySet() }
        val running = _pingBatch.value.any { it in _pinging.value }
        _pingBatch.value = if (running) _pingBatch.value + ids else ids
    }

    fun clearPingBatch() { _pingBatch.value = emptySet(); _pingStopped.value = false }

    /** 2.0.29: cancel the running ping-all / subscription ping; keep measured results. */
    fun stopPingBatch(): Boolean {
        val list = synchronized(pingJobs) { pingJobs.toList() }
        list.forEach { it.cancel() }
        if (list.isNotEmpty() || _pingBatch.value.isNotEmpty()) _pingStopped.value = true
        // unfinished probes drop their «Testing» mark; measured values stay
        _pinging.update { it - _pingBatch.value }
        return list.isNotEmpty()
    }

    /** 2.0.29: expanded subscriptions first so the visible list fills sooner. */
    private fun prioritizeVisible(servers: List<ServerEntry>): List<ServerEntry> {
        val collapsed = store.snapshot().collapsedSubs.toSet()
        return servers.sortedBy { s ->
            val sub = s.subscriptionId.orEmpty()
            when {
                sub.isNotEmpty() && sub !in collapsed -> 0
                sub.isEmpty() && net.antibs.client.ui.UiLogic.ORPHAN_GROUP !in collapsed -> 0
                else -> 1
            }
        }
    }

    private val pingResultBuf = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()
    private val pingDoneBuf = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var pingFlushJob: Job? = null

    private fun flushPingUi() {
        val results = mutableListOf<Pair<String, Long>>()
        while (true) { val x = pingResultBuf.poll() ?: break; results.add(x) }
        val done = mutableSetOf<String>()
        while (true) { val x = pingDoneBuf.poll() ?: break; done.add(x) }
        if (results.isNotEmpty()) _pings.update { it + results }
        if (done.isNotEmpty()) _pinging.update { it - done }
    }

    private fun schedulePingFlush() {
        if (pingFlushJob?.isActive == true) return
        pingFlushJob = viewModelScope.launch {
            delay(80)
            flushPingUi()
        }
    }

    private fun pingServers(servers: List<ServerEntry>, bulk: Boolean = true) {
        if (servers.isEmpty()) return
        val a = store.snapshot().adv
        val gate = if (a.pingType == "get" || a.pingType == "head") urlPingGate else sockPingGate
        val ordered = if (bulk) prioritizeVisible(servers) else servers
        val ids = ordered.map { it.id }.toSet()
        _pinging.update { it + ids }
        val job = viewModelScope.launch {
            try {
                ordered.map { s -> launch { gate.withPermit { recordPing(s, bulk = bulk) } } }.joinAll()
            } finally {
                flushPingUi()
                _pinging.update { it - ids }
                maybeAutoDeleteAfterPing(ids)
            }
        }
        pingJobs += job
        job.invokeOnCompletion { pingJobs -= job }
    }

    fun pingOne(s: ServerEntry) {
        _pinging.update { it + s.id }
        viewModelScope.launch { recordPing(s, bulk = false) }
    }

    private suspend fun recordPing(s: ServerEntry, bulk: Boolean) {
        _pinging.update { it + s.id }
        try {
            val ms = measure(s, bulk = bulk)
            pingResultBuf.add(s.id to ms)
            pingDoneBuf.add(s.id)
            schedulePingFlush()
        } catch (_: Throwable) {
            pingDoneBuf.add(s.id)
            schedulePingFlush()
        }
    }

    /** -1 = failed, -2 = not applicable. Type/mode/timeout/URL come from «Пинг» settings. */
    private suspend fun measure(s: ServerEntry, bulk: Boolean = false): Long {
        val st = store.snapshot()
        val a = st.adv
        // 2.0.29: bulk ping caps per-server wait at 4 s (ABS default) so dead hosts don't stall the batch.
        val timeoutMs = (if (bulk) minOf(a.pingTimeoutSec, 4) else a.pingTimeoutSec).coerceIn(2, 15) * 1000
        // 1.0.18: auto-select / balancer / chain entries have no single host — always measure them through Xray.
        if (s.xray?.protocol in setOf("balancer", "chain") || net.antibs.client.data.AutoSelect.isAnyAuto(s)) return urlTest(s, st, a, timeoutMs, bulk) ?: -2L
        // 1.0.30: qWDTT has no TCP/ICMP port or Xray outbound to test — its own handshake ping (or the tunnel).
        if (s.engine == EngineKind.QWDTT) return qwdttPing(s, st, timeoutMs)
        // 1.0.41: olcRTC has no host:port — join the room with a throw-away client and GET through it.
        if (s.xray?.protocol == net.antibs.client.data.Olcrtc.PROTOCOL) return olcrtcPing(s, st, timeoutMs)
        // 1.0.41: OpenVPN / OpenConnect — TCP ping if asked (TCP remotes), otherwise a real handshake + GET through the tunnel
        // 2.0.1: SSTP — TCP connect to the server; ZeroTier has no single host (n/a)
        if (s.xray?.protocol in setOf(net.antibs.client.data.PacketVpnConfig.SSTP, net.antibs.client.data.PacketVpnConfig.ZEROTIER))
            return withContext(Dispatchers.IO) { net.antibs.client.engine.PacketVpnEngine.ping(s.xray!!, timeoutMs) }
        if (s.xray?.protocol in setOf("openvpn", "openconnect")) {
            val x = s.xray!!
            if (a.pingType == "tcp" && (x.protocol == "openconnect" || x.network == "tcp")) {
                val host = if (x.protocol == "openconnect") net.antibs.client.data.OpenConnectConfig.host(x) else x.server
                return withContext(Dispatchers.IO) {
                    try { val t0 = System.nanoTime(); Socket().use { it.connect(InetSocketAddress(host, x.port), timeoutMs) }; ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1) } catch (_: Exception) { -1L }
                }
            }
            return olcrtcPing(s, st, timeoutMs, classic = true)
        }
        val route = net.antibs.client.data.PingRoute.method(a.pingType, s.xray?.protocol, s.xray?.network, s.xray?.alpn)
        return when (route) {
            "tcp" -> tcpPing(s, timeoutMs)
            "icmp" -> icmpPing(s, timeoutMs)
            else -> urlTest(s, st, a, timeoutMs, bulk) ?: tcpPing(s, timeoutMs)
        }
    }

    private val olcPingGate = kotlinx.coroutines.sync.Semaphore(2)

    private suspend fun olcrtcPing(s: ServerEntry, st: AppState, timeoutMs: Int, classic: Boolean = false): Long {
        val p = s.xray ?: return -2L
        val a = st.adv
        if (VpnState.status.value.state == ConnState.CONNECTED && st.selectedServerId == s.id) {
            val port = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT
            val r = withContext(Dispatchers.IO) {
                TunnelProbe.check(port, a.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL }, UiLogic.tunnelPingMethod(a.pingType), timeoutMs)
            }
            if (r is TunnelProbe.Result.Ok) return r.ms
            if (r is TunnelProbe.Result.Failed) return -1L
        }
        return olcPingGate.withPermit {
            withContext(Dispatchers.IO) {
                if (classic) net.antibs.client.engine.ClassicVpnEngine.ping(getApplication(), p, a.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL }, timeoutMs + 10_000)
                else net.antibs.client.engine.OlcrtcEngine.ping(getApplication(), p, net.antibs.client.engine.OlcrtcEngine.dnsFor(st.qwdttDns),
                    a.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL }, timeoutMs + 10_000)
            }
        }
    }

    private suspend fun qwdttPing(s: ServerEntry, st: AppState, timeoutMs: Int): Long {
        val q = s.qwdtt ?: return -2L
        // Connected through this very host → measure through the running tunnel instead of a second TURN session.
        if (VpnState.status.value.state == ConnState.CONNECTED && st.selectedServerId == s.id) {
            val a = st.adv
            val port = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT
            val r = withContext(Dispatchers.IO) {
                TunnelProbe.check(port, a.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL }, UiLogic.tunnelPingMethod(a.pingType), timeoutMs)
            }
            if (r is TunnelProbe.Result.Ok) return r.ms
            if (r is TunnelProbe.Result.Failed) return -1L
        }
        return qwdttPingGate.withPermit {
            withContext(Dispatchers.IO) {
                net.antibs.client.engine.QwdttPing.run(getApplication(), q, st.vkHashes, st.qwdttDns, st.qwdttObfs, st.qwdttTurnTcp,
                    net.antibs.client.engine.QwdttPing.timeoutFor(timeoutMs))
            }
        }
    }

    /** GET/HEAD through a throw-away Xray instance. Null → not possible here (helper engine / qWDTT), caller falls back to TCP. */
    private suspend fun urlTest(s: ServerEntry, st: AppState, a: AdvancedSettings, timeoutMs: Int, bulk: Boolean = false): Long? = withContext(Dispatchers.IO) {
        if (s.engine != EngineKind.XRAY) return@withContext null
        val x = s.xray ?: return@withContext -1L
        if (!x.unsupportedReason.isNullOrBlank()) return@withContext -2L
        val plan = try {
            XrayConfigBuilder.plan(s, st.servers, XrayConfigBuilder.Options(
                mux = st.mux, fragmentation = st.fragmentation, ipType = st.ipType, adv = a.copy(httpInbound = false, localDns = false, blockAds = false, adCats = emptyList(), adDnsOn = false, trafficPreset = "off"),
                logLevel = "none", autoExclude = st.autoExcludeIds.toSet(),
            ))
        } catch (_: Exception) { return@withContext -1L }
        if (plan.singBox != null || plan.mieruInstances.isNotEmpty() || plan.mihomo != null) return@withContext null
        // 2.0.29 bulk: one request / one core (ABS attempts=1). Manual ping keeps the user's double/keepalive mode.
        val mode = when {
            bulk -> 1 // one core; native still does best-of-2 on that core — cheaper than mode 0 (two fresh cores)
            a.pingMode == "double" -> 1
            a.pingMode == "keepalive" -> 2
            else -> 0
        }
        XrayCore.measureDelayEx(getApplication(), plan.xray, a.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL },
            if (a.pingType == "head") "HEAD" else "GET", mode, timeoutMs)
            .let { if (it <= 0) -1L else it }
    }

    private fun hostPort(s: ServerEntry): Pair<String, Int>? {
        val x = s.xray ?: return null
        if (x.protocol in setOf("chain", "balancer", "custom") || !x.unsupportedReason.isNullOrBlank() || x.server.isBlank() || x.port <= 0) return null
        return x.server to x.port
    }

    private suspend fun icmpPing(s: ServerEntry, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        if (s.engine == EngineKind.QWDTT) return@withContext -2L
        val (host, _) = hostPort(s) ?: return@withContext -2L
        try {
            val sec = (timeoutMs / 1000).coerceAtLeast(1)
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "$sec", host).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Regex("""time[=<]([\d.]+)\s*ms""").find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()?.coerceAtLeast(1) ?: -1L
        } catch (_: Exception) { -1L }
    }

    private suspend fun tcpPing(s: ServerEntry, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        if (s.engine == EngineKind.QWDTT) return@withContext -2L
        val (host, port) = hostPort(s) ?: return@withContext -2L
        try {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
        } catch (_: Exception) { -1L }
    }

    // ---- 1.0.21: built-in Cloudflare speed test ----
    enum class SpeedChoice { VPN, DIRECT, BOTH }

    data class SpeedUi(
        val running: Boolean = false,
        /** "vpn" / "direct" of the run in progress (or the last one). */
        val mode: String = "",
        val phase: net.antibs.client.engine.SpeedTest.Phase? = null,
        val fraction: Float = 0f,
        val liveMbps: Double = 0.0,
        val progress: net.antibs.client.engine.SpeedTest.Progress? = null,
        val step: Int = 0,
        val steps: Int = 1,
        /** Finished records of the latest run (1 entry, or 2 for «Оба» → comparison). */
        val results: List<net.antibs.client.engine.SpeedRecord> = emptyList(),
    )

    private val speedStore = net.antibs.client.engine.SpeedHistoryStore(app)
    private val _speed = MutableStateFlow(SpeedUi())
    val speed: StateFlow<SpeedUi> = _speed
    private val _speedHistory = MutableStateFlow(speedStore.load())
    val speedHistory: StateFlow<List<net.antibs.client.engine.SpeedRecord>> = _speedHistory
    @Volatile private var speedTest: net.antibs.client.engine.SpeedTest? = null
    private var speedJob: Job? = null

    fun startSpeedTest(choice: SpeedChoice) {
        if (_speed.value.running) return
        val vpnUp = VpnState.status.value.state == ConnState.CONNECTED
        val modes = when (choice) {
            SpeedChoice.VPN -> listOf("vpn")
            SpeedChoice.DIRECT -> listOf("direct")
            SpeedChoice.BOTH -> listOf("vpn", "direct")
        }
        if ("vpn" in modes && !vpnUp) { _toast.value = "Включите VPN для теста «Через VPN»"; return }
        val group = System.currentTimeMillis()
        _speed.value = SpeedUi(running = true, mode = modes.first(), steps = modes.size)
        speedJob = viewModelScope.launch {
            val done = mutableListOf<net.antibs.client.engine.SpeedRecord>()
            for ((i, mode) in modes.withIndex()) {
                if (i > 0 && speedTest?.isCancelled == true) break
                _speed.update { it.copy(mode = mode, step = i, phase = null, fraction = 0f, liveMbps = 0.0, progress = null) }
                val rec = withContext(Dispatchers.IO) { runSpeedOnce(mode, group) }
                done += rec
                _speedHistory.value = net.antibs.client.engine.SpeedHistory.add(_speedHistory.value, rec).also { speedStore.save(it) }
                _speed.update { it.copy(results = done.toList()) }
                if (rec.error == "Остановлено") break
            }
            speedTest = null
            _speed.update { it.copy(running = false, phase = null) }
        }
    }

    private fun runSpeedOnce(mode: String, group: Long): net.antibs.client.engine.SpeedRecord {
        val ctx = getApplication<Application>()
        val vs = VpnState.status.value
        val host = if (vs.state == ConnState.CONNECTED) UiLogic.liveHostTitle(vs.serverName, vs.pickName, true) else ""
        val a = store.snapshot().adv
        val (client, network) = if (mode == "vpn") {
            val port = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT
            // Fail fast with a clear message when the core has no local SOCKS inbound (custom JSON configs).
            val reachable = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1500) }; true } catch (_: Exception) { false }
            if (!reachable) return net.antibs.client.engine.SpeedRecord(ts = System.currentTimeMillis(), mode = mode, host = host,
                error = "Локальный SOCKS-вход 127.0.0.1:$port недоступен", group = group)
            net.antibs.client.engine.SpeedTest.socks(port) to ""
        } else net.antibs.client.engine.SpeedNet.direct(ctx).let { it.client to it.label }
        val t = net.antibs.client.engine.SpeedTest(client) { p ->
            _speed.update { it.copy(phase = p.phase, fraction = p.fraction, liveMbps = p.mbps, progress = p) }
        }
        speedTest = t
        VpnState.log("тест скорости (${if (mode == "vpn") "через VPN" else "напрямую"}) — speed.cloudflare.com")
        val r = t.run()
        VpnState.log("тест скорости: ↓ ${net.antibs.client.engine.SpeedMath.fmtMbps(r.downMbps)} ↑ ${net.antibs.client.engine.SpeedMath.fmtMbps(r.upMbps)} Мбит/с, пинг ${net.antibs.client.engine.SpeedMath.fmtMs(r.pingMs)} мс" +
            (r.meta.colo.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + (r.error?.let { " · $it" } ?: ""))
        return net.antibs.client.engine.SpeedRecord(
            ts = System.currentTimeMillis(), mode = mode, host = host, network = network,
            down = r.downMbps, up = r.upMbps, ping = r.pingMs, jitter = r.jitterMs,
            colo = r.meta.colo, city = r.meta.city, ip = r.meta.ip, error = r.error, group = group,
        )
    }

    fun stopSpeedTest() { speedTest?.cancel() }

    fun clearSpeedHistory() {
        _speedHistory.value = emptyList()
        speedStore.save(emptyList())
    }

    // ---- 1.0.22: delete / edit hosts ----
    data class UndoDelete(val deleted: AppStore.DeletedHost, val seq: Long = System.nanoTime())
    private val _undo = MutableStateFlow<UndoDelete?>(null)
    /** Last deleted host — MainActivity shows «Хост удалён» with «Отменить». */
    val undo: StateFlow<UndoDelete?> = _undo
    fun consumeUndo() { _undo.value = null }

    fun deleteHost(id: String) {
        val st = store.snapshot()
        val e = st.servers.firstOrNull { it.id == id } ?: return
        if (UiLogic.isAutoSelectEntry(e)) { _toast.value = "«Автовыбор сервера» удалить нельзя"; return }
        // The connected host is going away: disconnect first (with a notice).
        val vs = VpnState.status.value
        val live = vs.state == ConnState.CONNECTED || vs.state == ConnState.CONNECTING
        val connectedHere = live && st.selectedServerId == id
        if (connectedHere) AntiBSVpnService.disconnect(getApplication())
        val d = store.deleteHost(id) ?: return
        _pings.update { it - id }
        _undo.value = UndoDelete(d)
        if (connectedHere) VpnState.log("хост «${e.name}» удалён — VPN отключён")
    }

    fun undoDelete(u: UndoDelete) {
        store.undoDelete(u.deleted)
        _toast.value = if (u.deleted.wasSelected) "Хост восстановлен (VPN был отключён — подключитесь снова)" else null
    }

    // ---- 1.0.52: ⋮ «Удалить недоступные» (per subscription) ----
    data class UnreachablePrompt(val subId: String, val ids: List<String>)
    private val _unreachable = MutableStateFlow<UnreachablePrompt?>(null)
    /** Confirmation «Удалить N недоступных серверов?» for the subscription card menu. */
    val unreachable: StateFlow<UnreachablePrompt?> = _unreachable
    private val _unreachableScan = MutableStateFlow<Set<String>>(emptySet())
    /** Subscriptions whose hosts are being pinged before «Удалить недоступные». */
    val unreachableScan: StateFlow<Set<String>> = _unreachableScan
    fun dismissUnreachable() { _unreachable.value = null }

    data class FilterDeletePrompt(val ids: List<String>, val label: String)
    private val _filterDelete = MutableStateFlow<FilterDeletePrompt?>(null)
    /** Confirmation «Удалить N серверов?» after a long-press on a Home filter chip. */
    val filterDelete: StateFlow<FilterDeletePrompt?> = _filterDelete
    fun dismissFilterDelete() { _filterDelete.value = null }

    /** Ask to delete every host this chip matches, except the connected / auto-select ones. */
    fun askDeleteChip(servers: List<ServerEntry>, label: String) {
        val (protect, name) = connectedProtect()
        val ids = UiLogic.deletableHostIds(servers, protect, name)
        if (ids.isEmpty()) { _toast.value = "Нечего удалять"; return }
        _filterDelete.value = FilterDeletePrompt(ids, label)
    }

    fun confirmDeleteChip(p: FilterDeletePrompt) {
        _filterDelete.value = null
        val (protect, name) = connectedProtect()
        var n = 0
        p.ids.forEach { id ->
            val e = store.servers().firstOrNull { it.id == id } ?: return@forEach
            if (id in protect || (name.isNotBlank() && e.name == name) || UiLogic.isAutoSelectEntry(e)) return@forEach
            if (store.deleteHost(id) != null) { _pings.update { it - id }; n++ }
        }
        _toast.value = if (n > 0) "Удалено серверов: $n" else "Ничего не удалено"
    }

    private fun connectedProtect(): Pair<Set<String>, String> {
        val vs = VpnState.status.value
        val live = vs.state == ConnState.CONNECTED || vs.state == ConnState.CONNECTING
        if (!live) return emptySet<String>() to ""
        return setOfNotNull(store.snapshot().selectedServerId) to vs.pickName
    }

    /** Pings the subscription's hosts that have no result yet (waits for ones already measuring), then asks to delete the failed ones. */
    fun askDeleteUnreachable(subId: String) {
        if (subId in _unreachableScan.value) return
        val hosts = UiLogic.pingTargets(store.servers(), subId).filterNot { UiLogic.isAutoSelectEntry(it) }
        if (hosts.isEmpty()) { _toast.value = "Нет серверов"; return }
        viewModelScope.launch {
            _unreachableScan.update { it + subId }
            try {
                val todo = hosts.filter { it.id !in _pings.value && it.id !in _pinging.value }
                if (todo.isNotEmpty() || hosts.any { it.id in _pinging.value }) {
                    _toast.value = "Пинг серверов…"
                    val a = store.snapshot().adv
                    val gate = if (a.pingType == "get" || a.pingType == "head") urlPingGate else sockPingGate
                    _pinging.update { it + todo.map { s -> s.id } }
                    todo.map { s -> launch { gate.withPermit { recordPing(s, bulk = true) } } }.forEach { it.join() }
                    withTimeoutOrNull(60_000) { while (hosts.any { it.id in _pinging.value }) delay(200) }
                }
                val (ids, name) = connectedProtect()
                val failed = UiLogic.unreachableIds(store.servers(), subId, _pings.value, ids, name)
                    .filter { id -> hosts.any { it.id == id } }
                if (failed.isEmpty()) _toast.value = "Недоступных серверов нет"
                else _unreachable.value = UnreachablePrompt(subId, failed)
            } finally {
                _unreachableScan.update { it - subId }
            }
        }
    }

    /** Confirmed: delete like a swipe (subscription hosts are remembered as deleted); the connected host is never touched. */
    fun deleteUnreachable(p: UnreachablePrompt) {
        _unreachable.value = null
        val (protect, name) = connectedProtect()
        var n = 0
        p.ids.forEach { id ->
            val e = store.servers().firstOrNull { it.id == id } ?: return@forEach
            if (id in protect || (name.isNotBlank() && e.name == name) || UiLogic.isAutoSelectEntry(e)) return@forEach
            if (store.deleteHost(id) != null) { _pings.update { it - id }; n++ }
        }
        _toast.value = if (n > 0) "Удалено недоступных серверов: $n" else "Ничего не удалено"
    }

    /** ⋮ «Показать удалённые хосты»: forget the subscription's deleted hosts and fetch them again. */
    private fun networkOffline(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(n) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 2.0.35: after ping-all / subscription ping, auto-hide unreachable hosts when the toggle is on. */
    private fun maybeAutoDeleteAfterPing(ids: Set<String>) {
        val batch = _pingBatch.value
        if (batch.isEmpty() || ids.none { it in batch }) return
        val stopped = _pingStopped.value
        val offline = networkOffline()
        val (protect, name) = connectedProtect()
        val pings = _pings.value
        val servers = store.servers()
        val bySub = servers.filter { it.id in ids }.mapNotNull { it.subscriptionId }.toSet()
        var n = 0
        for (subId in bySub) {
            val sub = store.subscriptions().firstOrNull { it.id == subId } ?: continue
            if (!sub.autoDeleteUnreachable) continue
            val hosts = UiLogic.pingTargets(store.servers(), subId)
            val del = AutoDeleteUnreachable.idsToDelete(
                hosts, batchIds = ids, pings = pings,
                protectIds = protect, protectName = name,
                stoppedEarly = stopped, networkOffline = offline,
            )
            del.forEach { id ->
                if (store.deleteHost(id) != null) { _pings.update { it - id }; n++ }
            }
        }
        if (n > 0) _toast.value = "Удалено недоступных серверов: $n"
    }

    fun restoreHiddenHosts(subId: String) {
        val n = store.subscriptions().firstOrNull { it.id == subId }?.hiddenHosts?.size ?: 0
        if (n == 0) { _toast.value = "Удалённых хостов нет"; return }
        store.clearHidden(subId)
        _toast.value = "Восстанавливаю хосты: $n"
        refresh(subId)
    }

    fun editHost(id: String, profile: XrayProfile) {
        store.editHost(id, profile)
        _pings.update { it - id }
        val manual = store.servers().firstOrNull { it.id == id }?.subscriptionId == ProtocolSupport.MANUAL_SUB_ID
        _toast.value = if (manual) "Хост изменён" else "Хост изменён (изменения сохранятся при обновлении подписки)"
        if (store.snapshot().selectedServerId == id && VpnState.status.value.state == ConnState.CONNECTED) AntiBSVpnService.connect(getApplication())
    }

    /** 1.0.34: «Исключить из автовыбора» / «Вернуть в автовыбор» — the app's own balancer (no panel JSON) skips the host. */
    fun toggleAutoExclude(id: String) {
        val cur = store.snapshot()
        val excluded = id in cur.autoExcludeIds
        push { st -> st.copy(autoExcludeIds = if (excluded) st.autoExcludeIds - id else (st.autoExcludeIds + id).distinct()) }
        val host = cur.servers.firstOrNull { it.id == id }
        val sel = cur.servers.firstOrNull { it.id == cur.selectedServerId }
        val affectsTunnel = sel != null && host != null && net.antibs.client.data.AutoSelect.isSubscriptionAuto(sel) && sel.subscriptionId == host.subscriptionId
        if (affectsTunnel && VpnState.status.value.state == ConnState.CONNECTED) AntiBSVpnService.connect(getApplication())
        _toast.value = if (excluded) "Хост снова участвует в автовыборе" else "Хост исключён из автовыбора"
    }

    fun resetHost(id: String) {
        store.resetHost(id)
        _pings.update { it - id }
        _toast.value = "Изменения сброшены"
        if (store.snapshot().selectedServerId == id && VpnState.status.value.state == ConnState.CONNECTED) AntiBSVpnService.connect(getApplication())
    }

    /** 2.0.24 (ABS AladhanDates): confirmed Muslim holiday dates for this year and the next, stored with their year. */
    /** 0 idle, 1 busy, 2 ok, 3 failed — shown under the refresh button. */
    val muslimStatus = androidx.compose.runtime.mutableIntStateOf(0)
    fun muslimStatusText(): String {
        val t = net.antibs.client.data.MuslimDates.updatedAt
        val upd = if (t == 0L) "ещё не обновлялось" else "обновлено " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru")).format(java.util.Date(t))
        return when (muslimStatus.intValue) { 1 -> "Обновляем…"; 3 -> "Не удалось обновить · $upd"; 2 -> "Готово · $upd"; else -> upd.replaceFirstChar { it.uppercase() } }
    }
    fun refreshMuslimHolidays(silent: Boolean = false) {
        if (muslimStatus.intValue == 1) return
        if (!silent) muslimStatus.intValue = 1
        viewModelScope.launch {
            val dir = getApplication<Application>().filesDir
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { net.antibs.client.data.MuslimDates.refresh(dir) }.getOrDefault(false) }
            if (!silent) muslimStatus.intValue = if (ok) 2 else 3
            if (!silent) message(if (ok) "Мусульманские праздники обновлены" else "Не удалось обновить даты (используется расчёт по календарю)")
        }
    }

    /** At launch: load stored dates; refresh in the background when older than 30 days or from another year. */
    fun autoRefreshMuslimHolidays() {
        val dir = getApplication<Application>().filesDir
        net.antibs.client.data.MuslimDates.load(dir)
        if (net.antibs.client.data.MuslimDates.stale()) refreshMuslimHolidays(silent = true)
    }

}
