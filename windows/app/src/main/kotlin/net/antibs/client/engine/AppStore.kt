package net.antibs.client.engine

import net.antibs.client.data.AdvancedSettings
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.RoutingImport
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.Subscription
import net.antibs.client.data.XrayProfile
import net.antibs.client.ui.UiLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ThemeMode { AUTO, LIGHT, DARK, AMOLED }

fun themeModeOf(raw: String?): ThemeMode = when (raw) {
    "light" -> ThemeMode.LIGHT
    "dark" -> ThemeMode.DARK
    "amoled" -> ThemeMode.AMOLED
    else -> ThemeMode.AUTO
}


@Serializable
data class AppState(
    val subscriptions: List<Subscription> = emptyList(),
    val servers: List<ServerEntry> = emptyList(),
    val selectedServerId: String? = null,
    val theme: String = "auto",
    val bypassLan: Boolean = true,
    val seeded: Boolean = false,
    /** Per-install device ID (default HWID). Never exported. */
    val hwid: String = "",
    /** 1.0.18: send x-hwid + device headers with subscription requests («Отправлять HWID»). */
    val sendHwid: Boolean = true,
    /** 1.0.18: user-set HWID (empty = use [hwid]). Exported/imported when set. */
    val customHwid: String = "",
    val vkHashes: String = "",
    val qwdttDns: String = "yandex",
    val qwdttObfs: String = "audio",
    val qwdttTurnTcp: Boolean = false,
    val fragmentation: Boolean = false,
    val mux: Boolean = false,
    /** auto | ipv4 | ipv6 */
    val ipType: String = "auto",
    val allowLan: Boolean = false,
    val autostart: Boolean = false,
    /** Xray's own TUN inbound instead of hev-socks5-tunnel (1.0.6: live). */
    val xrayTun: Boolean = false,
    val subsExpanded: Boolean = true,
    val compactServers: Boolean = false,
    /** Legacy (≤1.0.7 «По пингу»): kept for JSON compatibility, no longer used — hosts keep subscription order. */
    val sortByPing: Boolean = false,
    /** True = two-column host grid; false = single-column list. */
    val hostGrid: Boolean = false,
    /** Subscription ids whose server rows are hidden. */
    val collapsedSubs: List<String> = emptyList(),
    val pingTimeoutMs: Int = 3000,
    /** Legacy (≤1.0.7 «Автовыбор сервера»): kept for JSON compatibility, always normalized to false. */
    val autoSelect: Boolean = false,
    val autoTargetId: String? = null,
    /** 1.0.34: hosts left out of the app's own auto-select balancer (only used when the panel gives no JSON balancer). */
    val autoExcludeIds: List<String> = emptyList(),
    /** 1.0.6 tunnel / VPN / ping / per-app / routing settings. */
    val adv: AdvancedSettings = AdvancedSettings(),
    /** 1.0.19: «Автопроверка обновлений» (GitHub releases, at most once per 24 h on launch). */
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** Empty = github.com/samur005/AntiBS2client-releases; "owner/repo" or an API URL otherwise. */
    val updateUrl: String = "",
    /** 1.0.25: «Автообновление подписок». Missing in older saves → defaults (ON, every 6 h). */
    val subAutoUpdate: Boolean = true,
    val subUpdateHours: Int = net.antibs.client.data.SubAutoUpdate.DEFAULT_HOURS,
    val subUpdateWifiOnly: Boolean = false,
    /** Bumped on every settings edit so DataStore and this blob can be reconciled. */
    val rev: Int = 0,
)

// 1.0.16: no built-in subscriptions. A fresh install starts empty («+» to add); subscriptions saved by
// earlier versions are kept as they are.

/** SharedPreferences-backed store (synchronous, survives process death). */
class AppStore(private val prefs: net.antibs.client.desktop.FilePrefs = net.antibs.client.desktop.FilePrefs.store) {
    constructor(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context) : this(net.antibs.client.desktop.FilePrefs.store)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 1.0.25: one state per process — the UI, the VPN service and the background auto-update worker
    // all see (and write) the same AppState, so none of them overwrites the others with a stale copy.
    private val _state: MutableStateFlow<AppState> = synchronized(LOCK) { shared ?: MutableStateFlow(load()).also { shared = it } }
    val state: StateFlow<AppState> get() = _state

    private fun load(): AppState = try {
        val raw = prefs.getString("state", null)?.let { json.decodeFromString<AppState>(it) } ?: AppState()
        UiLogic.normalizeSelection(pruneManualGroup(raw))
    } catch (_: Exception) { AppState() }

    private fun save(p: AppState) = synchronized(LOCK) {
        // 1.0.32: never persist sendHwid=false
        // 1.0.38: the update source is fixed (samur005/AntiBS2client-releases) — any saved override is dropped
        val next = UiLogic.normalizeSelection(pruneManualGroup(p.copy(sendHwid = true, updateUrl = "")))
        prefs.edit().putString("state", json.encodeToString(next)).apply()
        _state.value = next
    }

    fun snapshot(): AppState = _state.value

    /**
     * First launch / migration: only marks the state as initialised and creates the HWID.
     * 1.0.16: no subscriptions are pre-filled any more, and existing ones are never removed.
     */
    fun ensureSeeded() {
        val cur = snapshot()
        // 1.0.32: «Отправлять HWID» is always on — force it for upgrades that had it off.
        val fixed = if (cur.sendHwid && cur.updateUrl.isEmpty()) cur else cur.copy(sendHwid = true, updateUrl = "")
        if (fixed.seeded) { save(fixed); return }  // prune + migrate titles/reasons
        save(initialised(fixed, ::newHwid))
    }

    companion object {
        private val LOCK = Any()
        @Volatile private var shared: MutableStateFlow<AppState>? = null
        /** Pure part of [ensureSeeded]: never adds or removes subscriptions. */
        fun initialised(cur: AppState, newHwid: () -> String): AppState =
            cur.copy(seeded = true, hwid = cur.hwid.ifEmpty { newHwid() })
    }

    private fun newHwid(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    val hwid: String get() = snapshot().hwid.ifEmpty { newHwid().also { save(snapshot().copy(hwid = it)) } }

    fun theme(): ThemeMode = themeModeOf(snapshot().theme)
    fun setTheme(mode: ThemeMode) = save(snapshot().copy(theme = mode.name.lowercase()))

    fun bypassLan(): Boolean = snapshot().bypassLan
    fun setBypassLan(v: Boolean) = save(snapshot().copy(bypassLan = v))

    fun setQwdttOptions(vkHashes: String? = null, dns: String? = null, obfs: String? = null, turnTcp: Boolean? = null) {
        val c = snapshot()
        save(c.copy(
            vkHashes = vkHashes ?: c.vkHashes, qwdttDns = dns ?: c.qwdttDns,
            qwdttObfs = obfs ?: c.qwdttObfs, qwdttTurnTcp = turnTcp ?: c.qwdttTurnTcp,
        ))
    }

    fun subscriptions(): List<Subscription> = snapshot().subscriptions
    fun servers(): List<ServerEntry> = snapshot().servers
    fun selectedServer(): ServerEntry? = snapshot().servers.firstOrNull { it.id == snapshot().selectedServerId }

    fun selectServer(id: String) = save(snapshot().copy(selectedServerId = id, autoSelect = false))

    /** Settings edit. Increments [AppState.rev] so the DataStore copy can win across process restarts. */
    fun update(block: (AppState) -> AppState): AppState = synchronized(LOCK) {
        val base = snapshot()
        val next = block(base).copy(rev = base.rev + 1)
        save(next)
        next
    }

    fun syncSettings(s: UiSettings) {
        val c = snapshot()
        save(c.copy(
            theme = s.theme.name.lowercase(),
            bypassLan = s.bypassLan,
            fragmentation = s.fragmentation,
            mux = s.mux,
            ipType = s.ipType,
            allowLan = s.allowLan,
            autostart = s.autostart,
            xrayTun = s.xrayTun,
            subsExpanded = s.subsExpanded,
            compactServers = s.compactServers,
            sortByPing = s.sortByPing,
            hostGrid = s.hostGrid,
            collapsedSubs = s.collapsedSubs.sorted(),
            pingTimeoutMs = s.pingTimeoutMs,
            adv = s.adv,
            rev = s.rev,
        ))
    }

    fun addSubscription(url: String, kind: String = "auto"): String {
        val id = "sub-" + java.util.UUID.randomUUID().toString().take(8)
        val sub = Subscription(id = id, url = url.trim(), kind = kind, title = url.trim())
        save(snapshot().copy(subscriptions = snapshot().subscriptions + sub))
        return id
    }


    /** Drop the group when empty, restamp title, clear legacy "not supported" reasons. */
    fun pruneManualGroup(state: AppState = snapshot()): AppState {
        val n = state.servers.count { it.subscriptionId == ProtocolSupport.MANUAL_SUB_ID }
        var subs = state.subscriptions.map { s ->
            if (s.id == ProtocolSupport.MANUAL_SUB_ID) s.copy(title = ProtocolSupport.MANUAL_TITLE, serverCount = n, kind = "manual")
            else s
        }
        if (n == 0 && state.adv.hideEmptyManual) subs = subs.filterNot { it.id == ProtocolSupport.MANUAL_SUB_ID }
        else if (subs.none { it.id == ProtocolSupport.MANUAL_SUB_ID }) {
            subs = listOf(Subscription(id = ProtocolSupport.MANUAL_SUB_ID, url = "", kind = "manual", title = ProtocolSupport.MANUAL_TITLE, serverCount = n)) + subs
        }
        val servers = state.servers.map { ProtocolSupport.migrate(it) }
        return if (subs == state.subscriptions && servers == state.servers) state else state.copy(subscriptions = subs, servers = servers)
    }

    fun addManualServers(entries: List<ServerEntry>) {
        if (entries.isEmpty()) return
        val cur = snapshot()
        val tagged = entries.map { e ->
            if (e.subscriptionId == ProtocolSupport.MANUAL_SUB_ID) e
            else e.copy(subscriptionId = ProtocolSupport.MANUAL_SUB_ID)
        }
        val byId = LinkedHashMap<String, ServerEntry>()
        cur.servers.forEach { byId[it.id] = it }
        tagged.forEach { byId[it.id] = it }
        val servers = byId.values.toList()
        val n = servers.count { it.subscriptionId == ProtocolSupport.MANUAL_SUB_ID }
        val sel = if (servers.any { it.id == cur.selectedServerId }) cur.selectedServerId else servers.firstOrNull()?.id
        var subs = cur.subscriptions.toMutableList()
        val idx = subs.indexOfFirst { it.id == ProtocolSupport.MANUAL_SUB_ID }
        val shell = Subscription(id = ProtocolSupport.MANUAL_SUB_ID, url = "", kind = "manual", title = ProtocolSupport.MANUAL_TITLE, serverCount = n)
        if (idx < 0) subs.add(0, shell) else subs[idx] = shell
        save(cur.copy(servers = servers, selectedServerId = sel, subscriptions = subs))
    }

    fun addManualProfile(profile: XrayProfile): String {
        val id = "m-" + java.util.UUID.randomUUID().toString().take(8)
        val e = ServerEntry(id, net.antibs.client.data.EngineKind.XRAY, profile.remarks, ProtocolSupport.MANUAL_SUB_ID, xray = profile)
        addManualServers(listOf(e))
        return id
    }

    fun removeSubscription(id: String) {
        if (id == ProtocolSupport.MANUAL_SUB_ID) {
            // Drop hosts AND the empty «Серверы» card (recreated on next manual add).
            val cur = snapshot()
            val servers = cur.servers.filterNot { it.subscriptionId == id }
            val sel = if (servers.any { it.id == cur.selectedServerId }) cur.selectedServerId else servers.firstOrNull()?.id
            save(cur.copy(servers = servers, selectedServerId = sel, subscriptions = cur.subscriptions.filterNot { it.id == id }))
            return
        }
        val cur = snapshot()
        val servers = cur.servers.filterNot { it.subscriptionId == id }
        val sel = if (servers.any { it.id == cur.selectedServerId }) cur.selectedServerId else servers.firstOrNull()?.id
        val profiles = cur.adv.routingProfiles.filterNot { it.subscriptionId == id }
        val active = cur.adv.activeRoutingId?.takeIf { a -> profiles.any { it.id == a } }
        save(cur.copy(
            subscriptions = cur.subscriptions.filterNot { it.id == id }, servers = servers, selectedServerId = sel,
            adv = cur.adv.copy(routingProfiles = profiles, activeRoutingId = active),
        ))
    }

    /** Returns what happened to the subscription's routing profile (null: nothing to report). */
    fun applyRefresh(id: String, result: net.antibs.client.data.RemoteSubscription?, error: String?): net.antibs.client.data.SubscriptionRouting.Applied? = synchronized(LOCK) {
        val cur = snapshot()
        val subs = cur.subscriptions.map { s ->
            if (s.id != id) s else if (result == null) s.copy(lastRefresh = System.currentTimeMillis(), lastError = error ?: "ошибка")
            else s.copy(
                kind = result.kind,
                title = result.title?.ifBlank { null } ?: s.title.ifBlank { s.url },
                description = result.description,
                upload = result.upload, download = result.download, total = result.total, expireEpoch = result.expireEpoch,
                trafficUsedMb = result.trafficUsedMb, trafficLimitMb = result.trafficLimitMb,
                lastRefresh = System.currentTimeMillis(), lastError = "", serverCount = result.servers.size,
                announce = result.announce, supportUrl = result.supportUrl, webPageUrl = result.webPageUrl,
                updateIntervalHours = result.updateIntervalHours, routing = result.routing, fileName = result.fileName,
            )
        }
        val others = cur.servers.filterNot { it.subscriptionId == id }
        // 1.0.22: hosts the user deleted stay hidden, edited hosts keep the user's parameters.
        val before = subs.firstOrNull { it.id == id }
        val fresh = result?.servers.orEmpty()
        val servers = if (result != null) others + net.antibs.client.data.HostEdits.applyToRefresh(before, fresh) else cur.servers
        val sel = if (servers.any { it.id == cur.selectedServerId }) cur.selectedServerId else servers.firstOrNull()?.id
        val title = subs.firstOrNull { it.id == id }?.title ?: id
        val auto = subs.firstOrNull { it.id == id }?.autoRouting ?: true
        val applied = if (result != null) runCatching {
            net.antibs.client.data.SubscriptionRouting.apply(cur.adv, id, title, result.routing, auto)
        }.getOrElse { e -> net.antibs.client.data.SubscriptionRouting.Applied(cur.adv,
            net.antibs.client.data.SubscriptionRouting.Outcome.Failed(title, e.message ?: e.javaClass.simpleName), null) } else null
        val subs2 = if (result != null) subs.map { s -> if (s.id == id && s.hostOverrides.isNotEmpty()) s.copy(hostOverrides = net.antibs.client.data.HostEdits.refreshOriginals(s, fresh)) else s } else subs
        save(cur.copy(subscriptions = subs2, servers = servers, selectedServerId = sel, adv = applied?.adv ?: cur.adv))
        applied
    }

    /** 1.0.22: what [deleteHost] removed, for «Отменить». */
    data class DeletedHost(val entry: ServerEntry, val index: Int, val wasSelected: Boolean)

    /**
     * Delete one host. Manual hosts are removed for real; subscription hosts are also remembered in the
     * subscription's hiddenHosts so a refresh doesn't bring them back.
     */
    fun deleteHost(id: String): DeletedHost? {
        val cur = snapshot()
        val idx = cur.servers.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val e = cur.servers[idx]
        val servers = cur.servers.filterNot { it.id == id }
        val subs = cur.subscriptions.map { s ->
            if (s.id == e.subscriptionId && s.id != ProtocolSupport.MANUAL_SUB_ID && id !in s.hiddenHosts) s.copy(hiddenHosts = s.hiddenHosts + id) else s
        }
        val wasSel = cur.selectedServerId == id
        val sel = if (servers.any { it.id == cur.selectedServerId }) cur.selectedServerId else servers.firstOrNull()?.id
        save(pruneManualGroup(cur.copy(servers = servers, subscriptions = subs, selectedServerId = sel)))
        return DeletedHost(e, idx, wasSel)
    }

    fun undoDelete(d: DeletedHost) {
        val cur = snapshot()
        if (cur.servers.any { it.id == d.entry.id }) return
        val servers = cur.servers.toMutableList().apply { add(d.index.coerceIn(0, size), d.entry) }
        var subs = cur.subscriptions.map { s -> if (s.id == d.entry.subscriptionId) s.copy(hiddenHosts = s.hiddenHosts - d.entry.id) else s }
        if (d.entry.subscriptionId == ProtocolSupport.MANUAL_SUB_ID && subs.none { it.id == ProtocolSupport.MANUAL_SUB_ID }) {
            subs = listOf(Subscription(id = ProtocolSupport.MANUAL_SUB_ID, url = "", kind = "manual", title = ProtocolSupport.MANUAL_TITLE)) + subs
        }
        save(pruneManualGroup(cur.copy(servers = servers, subscriptions = subs, selectedServerId = if (d.wasSelected) d.entry.id else cur.selectedServerId)))
    }

    /** Forget the subscription's deleted hosts (the caller refreshes the subscription to fetch them again). */
    fun clearHidden(subId: String) {
        val cur = snapshot()
        save(cur.copy(subscriptions = cur.subscriptions.map { if (it.id == subId) it.copy(hiddenHosts = emptyList()) else it }))
    }

    /** Save an edit: manual hosts are changed in place, subscription hosts get an override that survives refresh. */
    fun editHost(id: String, profile: net.antibs.client.data.XrayProfile) {
        val cur = snapshot()
        val e = cur.servers.firstOrNull { it.id == id } ?: return
        val orig = e.xray ?: return
        val servers = cur.servers.map { if (it.id == id) it.copy(name = profile.remarks, xray = profile) else it }
        val subs = cur.subscriptions.map { s ->
            if (s.id == e.subscriptionId && s.id != ProtocolSupport.MANUAL_SUB_ID) {
                val original = s.hostOverrides[id]?.original ?: orig
                s.copy(hostOverrides = s.hostOverrides + (id to net.antibs.client.data.HostOverride(profile, original)))
            } else s
        }
        save(cur.copy(servers = servers, subscriptions = subs))
    }

    /** «Сбросить изменения»: back to what the subscription delivered. */
    fun resetHost(id: String) {
        val cur = snapshot()
        val e = cur.servers.firstOrNull { it.id == id } ?: return
        val sub = cur.subscriptions.firstOrNull { it.id == e.subscriptionId } ?: return
        val o = sub.hostOverrides[id] ?: return
        val servers = cur.servers.map { if (it.id == id) it.copy(name = o.original.remarks, xray = o.original) else it }
        val subs = cur.subscriptions.map { if (it.id == sub.id) it.copy(hostOverrides = it.hostOverrides - id) else it }
        save(cur.copy(servers = servers, subscriptions = subs))
    }
}
