package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.LinkParser
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.Subscription
import net.antibs.client.engine.AppState
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.NetMonitor
import net.antibs.client.engine.themeModeOf

object UiLogic {
    /**
     * 2.0.44: centre x of a vertical stem in a glyph's per-column ink counts: the first run of columns at least 60 %
     * as tall as the tallest column (left leg of «П»), or with [longest] the run that contains the tallest column
     * (shaft of «↓»). Null for an empty glyph.
     */
    fun stemCenter(cols: IntArray, longest: Boolean): Float? {
        val max = cols.maxOrNull() ?: return null
        if (max <= 0) return null
        val thr = if (longest) max else maxOf(1, (max * 0.6f).toInt())
        val start = cols.indexOfFirst { it >= thr }
        if (start < 0) return null
        var end = start
        val runThr = maxOf(1, (max * 0.6f).toInt())
        var s0 = start
        while (s0 > 0 && cols[s0 - 1] >= runThr && longest) s0--
        while (end + 1 < cols.size && cols[end + 1] >= runThr) end++
        return (s0 + end + 1) / 2f
    }

    fun protocolGroup(s: ServerEntry): String = if (net.antibs.client.data.AutoSelect.isAnyAuto(s)) net.antibs.client.data.AutoSelect.LABEL else when (s.engine) {
        EngineKind.QWDTT -> "qWDTT"
        EngineKind.XRAY -> {
            val p = s.xray
            when {
                p == null -> "Xray"
                p.security == "reality" && p.protocol == "vless" -> "VLESS Reality"
                p.security == "reality" -> "${Format.protocolLabel(p.protocol)} Reality"
                p.protocol == "shadowsocks" -> "Shadowsocks"
                p.protocol == "hysteria" -> "Hysteria2"
                !p.unsupportedReason.isNullOrBlank() -> Format.protocolLabel(p.protocol)
                else -> Format.protocolLabel(p.protocol)
            }
        }
    }

    /**
     * Auto-select entries (hidden in 1.0.8–1.0.17, shown again since 1.0.18):
     * remnawave «🎲Автовыбор сервера» hosts, "auto"/"autoselect" rows and balancers that came from a
     * subscription. Hosts in «Серверы» (incl. a «Балансировщик» built in the add-sheet) stay visible.
     */
    fun isAutoSelectEntry(s: ServerEntry): Boolean {
        // Hosts the user added by hand («Серверы») are never hidden, whatever their name.
        if (s.subscriptionId == ProtocolSupport.MANUAL_SUB_ID) return false
        val n = displayName(s.name).lowercase().replace('-', ' ').replace('_', ' ').trim()
        if (n.contains("автовыбор") || n.contains("авто выбор") || n.contains("autoselect") || n.contains("auto select")) return true
        if (n == "auto" || n.startsWith("auto ")) return true
        if (s.xray?.protocol == "balancer") return true
        return false
    }

    /**
     * Hosts shown on the main screen, in subscription order (no sorting, no protocol filter).
     * 1.0.18: subscription auto-select / balancer entries are shown again (selectable, connectable, pingable);
     * [isAutoSelectEntry] is kept only to label them.
     */
    fun visibleServers(servers: List<ServerEntry>): List<ServerEntry> = servers

    /**
     * The host that should be selected: the stored one when it is a real (visible) host,
     * otherwise the first real host. Null when there is nothing to select.
     */
    fun effectiveSelection(selectedId: String?, servers: List<ServerEntry>, subs: List<Subscription> = emptyList()): String? {
        val order = subs.mapIndexed { i, sub -> sub.id to i }.toMap()
        // "First" = first host as listed on the main screen (subscription order, hosts without a subscription last).
        val real = visibleServers(servers).sortedBy { order[it.subscriptionId] ?: Int.MAX_VALUE }
        return real.firstOrNull { it.id == selectedId }?.id ?: real.firstOrNull()?.id
    }

    /**
     * Drops the legacy auto-select mode: a state that was on «Автовыбор сервера» (or pointed at a hidden
     * auto-select entry) falls back to the first real host. With no hosts loaded yet the stored id is kept.
     */
    fun normalizeSelection(st: AppState): AppState {
        if (st.servers.isEmpty()) return if (st.autoSelect) st.copy(autoSelect = false) else st
        val sel = effectiveSelection(st.selectedServerId, st.servers, st.subscriptions)
        return if (!st.autoSelect && sel == st.selectedServerId) st else st.copy(autoSelect = false, selectedServerId = sel)
    }

    /** -1 if the subscription has no expiry. */
    fun daysLeft(expireEpoch: Long, nowSec: Long): Int {
        if (expireEpoch <= 0) return -1
        val delta = expireEpoch - nowSec
        if (delta <= 0) return 0
        return ((delta + 86399) / 86400).toInt()
    }

    fun expiryProgress(expireEpoch: Long, nowSec: Long): Float {
        val d = daysLeft(expireEpoch, nowSec)
        if (d <= 0) return 0f
        return (d / 30f).coerceAtMost(1f)
    }

    /**
     * Synthetic rows that are not real hosts: the auto-select / balancer entry
     * ("🎲Автовыбор сервера", "balancer", "auto") and placeholder rows named after the
     * built-in subscriptions ("sub remnawave", "sub-qwdtt", …).
     */
    fun isSyntheticServer(name: String): Boolean {
        val n = displayName(name).lowercase().replace('-', ' ').replace('_', ' ').trim()
        if (n.contains("автовыбор") || n.contains("авто выбор") || n.contains("balancer") || n.contains("балансир")) return true
        if (n == "auto" || n.startsWith("auto ") || n.contains("autoselect") || n.contains("auto select")) return true
        if (Regex("^sub\\s+(remnawave|qwdtt)\\b").containsMatchIn(n)) return true
        return false
    }

    fun realServerCount(servers: List<ServerEntry>, subscriptionId: String?): Int =
        servers.count { it.subscriptionId == subscriptionId && !isSyntheticServer(it.name) }

    /** 1 сервер, 2 сервера, 5 серверов, 21 сервер … */
    fun serversWord(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> "серверов"
            m10 == 1 -> "сервер"
            m10 in 2..4 -> "сервера"
            else -> "серверов"
        }
    }

    /** "17 серверов · подписка до 02.11.2026"; the date half only when expire is known. */
    fun subFooterLeft(count: Int): String = "$count ${serversWord(count)}"

    fun subFooterRight(expireEpoch: Long): String =
        if (expireEpoch > 0) "подписка до ${Format.date(expireEpoch)}" else ""

    fun subFooter(count: Int, expireEpoch: Long): String {
        val r = subFooterRight(expireEpoch)
        return if (r.isEmpty()) subFooterLeft(count) else "${subFooterLeft(count)} · $r"
    }

    /** Refresh when the panel's profile-update-interval (hours) has elapsed since the last fetch. */
    fun isStale(sub: Subscription, nowMs: Long): Boolean {
        if (sub.lastRefresh <= 0) return false
        val h = sub.updateIntervalHours
        if (h <= 0) return false
        return nowMs - sub.lastRefresh >= h * 3_600_000L
    }

    fun trafficProgress(used: Double, limit: Double): Float =
        if (limit <= 0.0) 0f else (used / limit).toFloat().coerceIn(0f, 1f)

    /** 2.0.44: fill of the subscription traffic bar = what is left (shrinks with use); unlimited = full. */
    fun trafficRemaining(used: Double, limit: Double): Float =
        if (limit <= 0.0) 1f else ((limit - used) / limit).toFloat().coerceIn(0f, 1f)

    data class Traffic(val usedMb: Double, val limitMb: Double, val fromBytes: Boolean)

    /** Size for the traffic bar / cards: "12.4 ГБ", "150 ГБ", "512 МБ" (2.0.44: readable units with a space). */
    fun compactSize(bytes: Double): String {
        if (bytes <= 0.0) return "0 МБ"
        val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var v = bytes
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        val num = if (v >= 100.0 || i == 0) Math.round(v).toString()
        else String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
        return num + " " + units[i]
    }

    /** Text drawn on the subscription traffic bar: "used/total", "used/∞" when unlimited. */
    fun trafficBarLabel(t: Traffic): String {
        val used = compactSize(t.usedMb * 1048576.0)
        val total = if (t.limitMb > 0) compactSize(t.limitMb * 1048576.0) else "∞"
        return "$used / $total"
    }


    fun traffic(sub: Subscription): Traffic {
        if (sub.trafficLimitMb > 0 || sub.trafficUsedMb > 0) {
            return Traffic(sub.trafficUsedMb, sub.trafficLimitMb, false)
        }
        val used = (sub.upload + sub.download) / 1048576.0
        val limit = if (sub.total > 0) sub.total / 1048576.0 else 0.0
        return Traffic(used, limit, true)
    }

    fun displayName(name: String): String {
        val flag = LinkParser.flagOf(name) ?: return name
        return name.replace(flag, "").trim().ifEmpty { name }
    }

    fun flag(name: String): String = LinkParser.flagOf(name) ?: "🌐"

    /** 1.0.51: one section of the chain / balancer server picker. */
    data class PickGroup(val id: String, val title: String, val servers: List<ServerEntry>)

    /**
     * 1.0.51: hosts of the chain / balancer picker grouped like «Серверы»: subscriptions in their list order, then
     * own (manually added) hosts, then anything whose subscription is gone. Empty groups are dropped.
     */
    fun pickerGroups(servers: List<ServerEntry>, subs: List<Subscription>): List<PickGroup> {
        val bySub = servers.groupBy { it.subscriptionId }
        val out = mutableListOf<PickGroup>()
        val known = mutableSetOf<String>()
        subs.filter { it.id != ProtocolSupport.MANUAL_SUB_ID }.forEach { sub ->
            known += sub.id
            bySub[sub.id]?.takeIf { it.isNotEmpty() }?.let { out += PickGroup(sub.id, sub.title.ifBlank { "Подписка" }, it) }
        }
        known += ProtocolSupport.MANUAL_SUB_ID
        bySub[ProtocolSupport.MANUAL_SUB_ID]?.takeIf { it.isNotEmpty() }?.let { out += PickGroup(ProtocolSupport.MANUAL_SUB_ID, "Свои серверы", it) }
        servers.filter { it.subscriptionId !in known }.takeIf { it.isNotEmpty() }?.let { out += PickGroup("other", "Другие", it) }
        return out
    }

    fun protoShort(s: ServerEntry): String = when (s.engine) {
        EngineKind.QWDTT -> "qWDTT"
        EngineKind.XRAY -> when {
            s.xray?.security == "reality" -> "Reality"
            s.xray?.protocol == "shadowsocks" -> "SS"
            else -> Format.protocolLabel(s.xray?.protocol ?: "")
        }
    }

    fun protoLine(s: ServerEntry): String = when (s.engine) {
        EngineKind.QWDTT -> "qWDTT"
        EngineKind.XRAY -> {
            val p = s.xray ?: return ""
            buildString {
                append(Format.protocolLabel(p.protocol))
                if (p.security == "reality") append(" Reality")
                else if (!p.security.isNullOrEmpty() && p.security != "none") append(" ").append(p.security)
            }
        }
    }

    /** What the composable must do: stored theme string -> dark flag, including the system bit for auto. */
    fun darkFromStored(raw: String, systemDark: Boolean): Boolean =
        ThemeResolver.isDark(themeModeOf(raw), systemDark)

    /** While the tunnel is up (or still coming up), a new host tap must reconnect onto it. */
    fun shouldHotSwitch(state: ConnState, selectionChanged: Boolean): Boolean =
        selectionChanged && (state == ConnState.CONNECTED || state == ConnState.CONNECTING)

    /**
     * Chip beside the timer. While the tunnel is up or switching, prefer the protocol recorded
     * for the outbound that was actually started (balancer included).
     */
    fun activeProtocolLabel(state: ConnState, liveProtocol: String, selected: ServerEntry?): String {
        val live = state == ConnState.CONNECTED || state == ConnState.CONNECTING || state == ConnState.DISCONNECTING
        if (live && liveProtocol.isNotEmpty()) return liveProtocol
        return selected?.let { protocolGroup(it) }.orEmpty()
    }

    /** Chip beside the timer: green when up, red on error, gray when the VPN is off or still moving. */
    enum class ProtoChipTone { OFF, OK, ERR }

    fun protoChipTone(state: ConnState): ProtoChipTone = when (state) {
        ConnState.CONNECTED -> ProtoChipTone.OK
        ConnState.ERROR -> ProtoChipTone.ERR
        else -> ProtoChipTone.OFF
    }

    /**
     * Elapsed-time label shown on the power switch track (1.0.9): HH:MM:SS, tabular digits;
     * after 100 h it switches to «Nд HH:MM» so the label never outgrows the track.
     */
    fun elapsedLabel(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        if (h >= 100) return "%dд %02d:%02d".format(h / 24, h % 24, (s % 3600) / 60)
        return "%02d:%02d:%02d".format(h, (s % 3600) / 60, s % 60)
    }

    /**
     * Caption on the power switch track: the session timer while connected, «ВКЛ» while connecting
     * (thumb already on the right), «ВЫКЛ» otherwise (off, error, disconnecting).
     */
    fun toggleCaption(state: ConnState, sinceMs: Long, nowMs: Long): String = when (state) {
        ConnState.CONNECTED -> elapsedLabel(if (sinceMs > 0) (nowMs - sinceMs) / 1000 else 0)
        ConnState.CONNECTING -> "ВКЛ"
        else -> "ВЫКЛ"
    }

    /** 2.0.35: segmented power card — «Вкл» is selected while connected or connecting. */
    fun powerSelectedOn(state: ConnState): Boolean =
        state == ConnState.CONNECTED || state == ConnState.CONNECTING

    fun powerBusy(state: ConnState): Boolean =
        state == ConnState.CONNECTING || state == ConnState.DISCONNECTING

    /** Timer to the right of the segments: live session, «…» while connecting, zeros when off. */
    fun powerTimerText(state: ConnState, sinceMs: Long, nowMs: Long): String = when (state) {
        ConnState.CONNECTED -> elapsedLabel(if (sinceMs > 0) (nowMs - sinceMs) / 1000 else 0)
        ConnState.CONNECTING -> "…"
        else -> "00:00:00"
    }

    fun powerContentDescription(state: ConnState, problem: Boolean, timer: String): String = when (state) {
        ConnState.CONNECTED ->
            if (problem) "Отключить VPN, нет сети, сессия $timer"
            else "Отключить VPN, подключено $timer"
        ConnState.CONNECTING -> "Подключение VPN"
        ConnState.DISCONNECTING -> "Отключение VPN"
        ConnState.ERROR -> "Ошибка VPN, подключить снова"
        else -> "Подключить VPN"
    }

    /** Swipe right turns on, swipe left turns off, once the drag passes [thresholdPx]. */
    fun powerSwipeToggles(on: Boolean, dxPx: Float, thresholdPx: Float): Boolean =
        if (on) dxPx <= -thresholdPx else dxPx >= thresholdPx

    /**
     * 2.0.35: the power card turns red on [ConnState.ERROR], [NetMonitor.Health.NO_INTERNET]
     * (network lost / no internet), [NetMonitor.Health.SERVER_DOWN], or a failed [TunnelPing]
     * while connected. Returns to the accent as soon as health is OK and the tunnel ping is not FAIL.
     * [NetMonitor.health] is computed from vpnFails / directFails / netValidated.
     */
    fun powerShowError(
        state: ConnState,
        health: NetMonitor.Health = NetMonitor.Health.OK,
        tunnelPing: TunnelPing = TunnelPing.UNKNOWN,
    ): Boolean {
        if (state == ConnState.ERROR) return true
        if (!powerSelectedOn(state)) return false
        if (health != NetMonitor.Health.OK) return true
        return state == ConnState.CONNECTED && tunnelPing == TunnelPing.FAIL
    }

    /** 2.0.36: segmented «Вкл» fill — orange while connecting, green when connected and healthy, red on problems, else off. */
    enum class PowerThumb { OFF, ORANGE, GREEN, RED }

    fun powerThumb(
        state: ConnState,
        health: NetMonitor.Health = NetMonitor.Health.OK,
        tunnelPing: TunnelPing = TunnelPing.UNKNOWN,
    ): PowerThumb = when {
        powerShowError(state, health, tunnelPing) -> PowerThumb.RED
        state == ConnState.CONNECTED -> PowerThumb.GREEN
        state == ConnState.CONNECTING -> PowerThumb.ORANGE
        else -> PowerThumb.OFF
    }

    const val ORPHAN_GROUP = "__orphans__"

    data class HostGroup(
        val subscription: Subscription?,
        val servers: List<ServerEntry>,
        val total: Int,
        val expanded: Boolean,
    )

    /**
     * Servers nested under their subscription, in subscription order (fixed; auto-select entries hidden).
     * A collapsed subscription contributes an empty [HostGroup.servers] list so the UI hides its hosts.
     */
    fun hostGroups(
        subs: List<Subscription>,
        servers: List<ServerEntry>,
        collapsedIds: Set<String>,
    ): List<HostGroup> {
        val known = subs.map { it.id }.toSet()
        val buckets = LinkedHashMap<String, MutableList<ServerEntry>>()
        subs.forEach { buckets[it.id] = mutableListOf() }
        val orphans = mutableListOf<ServerEntry>()
        for (s in visibleServers(servers)) {
            val id = s.subscriptionId
            if (id != null && id in known) buckets.getValue(id).add(s) else orphans.add(s)
        }
        fun visible(id: String, list: List<ServerEntry>): HostGroup {
            val expanded = id !in collapsedIds
            val filtered = list
            val sub = subs.firstOrNull { it.id == id }
            return HostGroup(sub, if (expanded) filtered else emptyList(), filtered.size, expanded)
        }
        val groups = subs.map { visible(it.id, buckets.getValue(it.id)) }
            // Never emit an empty «Серверы» card — prune keeps it out of [subs], this is defense-in-depth.
            .filterNot { g ->
                g.subscription?.id == ProtocolSupport.MANUAL_SUB_ID &&
                    g.total == 0 &&
                    servers.none { it.subscriptionId == ProtocolSupport.MANUAL_SUB_ID }
            }
        if (orphans.isEmpty()) return groups
        val expanded = ORPHAN_GROUP !in collapsedIds
        val filtered = orphans
        return groups + HostGroup(null, if (expanded) filtered else emptyList(), filtered.size, expanded)
    }

    // ---- 1.0.10: auto-ping targets ----

    /**
     * Hosts to ping automatically: visible hosts only (hidden auto-select / balancer entries are skipped),
     * optionally limited to one subscription, minus hosts whose ping is already queued.
     */
    /** 1.0.20: a subscription's ping icon spins (and is disabled) while any of its visible hosts is being pinged. */
    fun subPinging(servers: List<ServerEntry>, subscriptionId: String, pinging: Set<String>): Boolean =
        pinging.isNotEmpty() && visibleServers(servers).any { it.subscriptionId == subscriptionId && it.id in pinging }

    fun pingTargets(servers: List<ServerEntry>, subscriptionId: String? = null, busy: Set<String> = emptySet()): List<ServerEntry> =
        visibleServers(servers).filter { (subscriptionId == null || it.subscriptionId == subscriptionId) && it.id !in busy }

    /** Hosts that a bulk delete may remove: never auto-select, never the connected / routed host. */
    fun deletableHostIds(
        servers: List<ServerEntry>,
        protectIds: Set<String> = emptySet(),
        protectName: String = "",
    ): List<String> = servers
        .filter { !isAutoSelectEntry(it) && it.id !in protectIds && (protectName.isBlank() || it.name != protectName) }
        .map { it.id }

    /**
     * 1.0.52: ⋮ «Удалить недоступные» — hosts of [subscriptionId] whose last ping failed (-1) or gave no result.
     * Never the auto-select entry, hosts marked н/д (-2), the [protectIds] (connected host) or the host named
     * [protectName] (the one auto-select / a balancer routes through right now).
     */
    fun unreachableIds(
        servers: List<ServerEntry>, subscriptionId: String, pings: Map<String, Long>,
        protectIds: Set<String> = emptySet(), protectName: String = "",
    ): List<String> = pingTargets(servers, subscriptionId)
        .filter { !isAutoSelectEntry(it) && it.id !in protectIds && (protectName.isBlank() || it.name != protectName) }
        .filter { val p = pings[it.id]; p == null || p == -1L }
        .map { it.id }

    /**
     * Hosts pinged right away on app open / pull-to-refresh: those whose subscription will not be refreshed
     * now (manual «Серверы», orphans, subscriptions not being fetched). Refreshed subscriptions ping their
     * hosts after the fetch completes.
     */
    fun pingTargetsNotRefreshing(servers: List<ServerEntry>, refreshingSubIds: Set<String>, busy: Set<String> = emptySet()): List<ServerEntry> =
        pingTargets(servers, busy = busy).filter { it.subscriptionId == null || it.subscriptionId !in refreshingSubIds }

    // ---- 1.0.10: sparkline smoothing ----

    /** Seconds of traffic history kept for the connection-card sparkline (one sample per second). */
    const val SPARK_HISTORY = 60
    /** 1.0.24: nominal time between sparkline samples (the speed card ticks once per second). */
    const val SPARK_INTERVAL_MS = 1000L

    /**
     * 1.0.24: x of a sample that arrived at [t] when the clock reads [now]. A sample enters at the live end
     * ([headX]) exactly one interval after it arrived (while it "arrives", the head slides towards it) and then
     * moves left at a constant [step] per interval — continuous scrolling independent of tick jitter.
     */
    fun sparkX(now: Long, t: Long, headX: Float, step: Float, intervalMs: Long = SPARK_INTERVAL_MS): Float =
        headX - ((now - t).toFloat() / intervalMs - 1f) * step

    /** 0…1 progress of the head along the newest segment (eased), 1 once the interval has passed. */
    fun headFraction(now: Long, newest: Long, intervalMs: Long = SPARK_INTERVAL_MS): Float {
        val x = ((now - newest).toFloat() / intervalMs).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x) // smoothstep: the head eases out of / into each sample
    }

    /** Head glow pulse 0…1 (1.4 s period). */
    fun headPulse(now: Long): Float = (0.5f + 0.5f * kotlin.math.sin(now / 1400.0 * 2 * Math.PI)).toFloat()

    /**
     * First part (0…[t]) of a cubic Bézier given as [x0,y0,x1,y1,x2,y2,x3,y3] (de Casteljau). Returns the same layout;
     * the last point is the curve at [t] — the head sits exactly on the line.
     */
    fun bezierPrefix(p: FloatArray, t: Float): FloatArray {
        fun lerp(a: Float, b: Float) = a + (b - a) * t
        val ax = lerp(p[0], p[2]); val ay = lerp(p[1], p[3])
        val bx = lerp(p[2], p[4]); val by = lerp(p[3], p[5])
        val cx = lerp(p[4], p[6]); val cy = lerp(p[5], p[7])
        val dx = lerp(ax, bx); val dy = lerp(ay, by)
        val ex = lerp(bx, cx); val ey = lerp(by, cy)
        val fx = lerp(dx, ex); val fy = lerp(dy, ey)
        return floatArrayOf(p[0], p[1], ax, ay, dx, dy, fx, fy)
    }

    /** Exponential moving average step; [prev] null = first sample. */
    fun emaNext(prev: Float?, raw: Float, alpha: Float = 0.35f): Float {
        val r = if (raw.isFinite()) raw.coerceAtLeast(0f) else 0f
        return if (prev == null) r else prev + alpha.coerceIn(0f, 1f) * (r - prev)
    }

    /** Appends an EMA-smoothed sample, keeping at most [cap] values. */
    fun pushSmoothed(buf: MutableList<Float>, raw: Float, cap: Int = SPARK_HISTORY + 2, alpha: Float = 0.35f) {
        buf.add(emaNext(buf.lastOrNull(), raw, alpha))
        while (buf.size > cap) buf.removeAt(0)
    }

    /**
     * Fritsch–Carlson monotone cubic tangents for equally spaced samples (dy per index step).
     * The resulting Hermite curve never overshoots between samples, so a traffic line never dips
     * below zero or spikes above a local peak.
     */
    fun monotoneTangents(y: List<Float>): FloatArray {
        val n = y.size
        val m = FloatArray(n)
        if (n < 2) return m
        val d = FloatArray(n - 1) { y[it + 1] - y[it] }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / kotlin.math.sqrt(h)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /** Sparkline vertical scale: peak of the visible samples with a little headroom, never below 1 B/s. */
    fun sparkScale(vararg series: List<Float>): Float =
        (series.maxOfOrNull { s -> s.maxOrNull() ?: 0f } ?: 0f).times(1.15f).coerceAtLeast(1f)

    // ---- 1.0.14: status dot in the speed-card header ----
    /**
     * OFF = gray static, CONNECTING = amber blinking (also while disconnecting), ERROR = red static.
     * Connected (1.0.19, driven by the tunnel ping): CHECKING = amber breathing + ripple (first check running),
     * LIVE = green breathing + ripple (ping through the tunnel succeeded), NO_PING = red breathing + ripple (failed / timeout).
     */
    enum class StatusDot { OFF, CONNECTING, CHECKING, LIVE, NO_PING, ERROR }

    /** 1.0.19: host shown in the card — the host auto-select / a balancer is really using, once known. */
    fun liveHostName(serverName: String, pickName: String, connected: Boolean): String =
        if (connected && pickName.isNotEmpty()) pickName else serverName

    /** Card title: «Германия · авто» while auto-select routes through «🇩🇪 Германия», else the connected host. */
    fun liveHostTitle(serverName: String, pickName: String, connected: Boolean): String =
        if (connected && pickName.isNotEmpty()) "${pickTitle(net.antibs.client.data.AutoSelect.cleanLabel(pickName, "Сервер"))} · авто" else displayName(serverName)

    /** 1.0.34: a cascade pick keeps its whole route («🇷🇺→🇩🇪 Германия»); a plain host drops its leading flag. */
    fun pickTitle(name: String): String = if (name.contains('→')) name.trim() else displayName(name)

    /** 1.0.34: exit-country flag of a host name — the last flag («🇷🇺→🇩🇪 Германия» → 🇩🇪); 🌐 when there is none. */
    fun exitFlag(name: String): String {
        val cps = name.codePoints().toArray()
        for (i in cps.size - 2 downTo 0) {
            if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) return String(cps, i, 2)
        }
        return "🌐"
    }

    /** 1.0.19: result of the periodic ping through the active tunnel. UNKNOWN = no session / not checked yet. */
    enum class TunnelPing { UNKNOWN, CHECKING, OK, FAIL }

    /** Seconds between tunnel pings while connected (first one right after connect / host switch). */
    const val TUNNEL_PING_INTERVAL_MS = 30_000L
    /** Delay after CONNECTED before the first check (lets the core finish wiring the inbound). */
    const val TUNNEL_PING_FIRST_DELAY_MS = 700L
    /** A failed check is retried once after this pause before the dot turns red. */
    const val TUNNEL_PING_RETRY_MS = 2_000L

    fun statusDot(state: ConnState, ping: TunnelPing): StatusDot = when (state) {
        ConnState.CONNECTED -> when (ping) {
            TunnelPing.OK -> StatusDot.LIVE
            TunnelPing.FAIL -> StatusDot.NO_PING
            TunnelPing.CHECKING, TunnelPing.UNKNOWN -> StatusDot.CHECKING
        }
        ConnState.CONNECTING, ConnState.DISCONNECTING -> StatusDot.CONNECTING
        ConnState.ERROR -> StatusDot.ERROR
        else -> StatusDot.OFF
    }

    /** Ping value written to the host list from a tunnel check: delay in ms, -1 (shown as «таймаут») on failure. */
    fun tunnelPingValue(ms: Long?): Long = if (ms != null && ms > 0) ms else -1L

    /** Identifies one connected session (a host switch restarts the service → new [since]); null when not connected. */
    fun tunnelSessionKey(state: ConnState, since: Long, serverName: String): String? =
        if (state == ConnState.CONNECTED) "$since|$serverName" else null

    /** Ping type actually used through the tunnel: HEAD when chosen in «Пинг», GET otherwise (TCP/ICMP can't test the tunnel). */
    fun tunnelPingMethod(pingType: String): String = if (pingType == "head") "HEAD" else "GET"

    // ---- 1.0.15: alive direction arrows ----
    /** Below this (B/s) the direction counts as idle — keep-alives shouldn't animate the arrow. */
    const val ARROW_IDLE_BPS = 512L

    fun arrowActive(rateBps: Long, connected: Boolean): Boolean = connected && rateBps >= ARROW_IDLE_BPS

    /** One flow cycle: ~1500 ms at 1 KB/s down to ~550 ms at ≥ 10 MB/s (log scale); 0 when idle. */
    fun arrowPeriodMs(rateBps: Long): Int {
        if (rateBps < ARROW_IDLE_BPS) return 0
        val lo = kotlin.math.log10(1024.0)
        val hi = kotlin.math.log10(10.0 * 1024 * 1024)
        val t = ((kotlin.math.log10(rateBps.toDouble()) - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return (1500 - t * 950).toInt()
    }

    /**
     * 1.0.22: fields of the «Изменить» dialog for a host (key → current value). Name always; address/port for
     * real servers; protocol credentials; transport/TLS fields that are set. Custom JSON → the JSON only.
     */
    fun editFields(x: net.antibs.client.data.XrayProfile): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["remarks"] = x.remarks
        when (x.protocol) {
            "custom" -> { m["customJson"] = x.customJson.orEmpty(); return m }
            "chain", "balancer" -> return m
        }
        m["server"] = x.server
        m["port"] = x.port.toString()
        fun add(k: String, v: String?, always: Boolean = false) { if (always || !v.isNullOrBlank()) m[k] = v.orEmpty() }
        add("id", x.id, x.protocol in setOf("vless", "vmess", "tuic"))
        add("password", x.password, x.protocol in setOf("trojan", "shadowsocks", "hysteria", "hysteria1", "tuic", "anytls", "naive", "ssh", "ssr", "snell"))
        add("username", x.username)
        add("method", x.method, x.protocol == "shadowsocks")
        if (x.protocol in setOf("vless", "vmess", "trojan", "shadowsocks")) add("network", x.network, true)
        add("security", x.security)
        add("sni", x.sni, x.security == "tls" || x.security == "reality")
        add("fingerprint", x.fingerprint)
        add("host", x.host)
        add("path", x.path)
        add("serviceName", x.serviceName)
        add("flow", x.flow)
        add("publicKey", x.publicKey, x.security == "reality")
        add("shortId", x.shortId)
        add("alpn", x.alpn)
        add("obfsPassword", x.obfsPassword)
        return m
    }

    fun editFieldLabel(k: String): String = when (k) {
        "remarks" -> "Имя"; "server" -> "Адрес"; "port" -> "Порт"; "id" -> "UUID"; "password" -> "Пароль"
        "username" -> "Логин"; "method" -> "Метод / security"; "network" -> "Транспорт"; "security" -> "Security (tls/reality)"
        "sni" -> "SNI"; "fingerprint" -> "fp"; "host" -> "host"; "path" -> "path"; "serviceName" -> "serviceName (gRPC)"
        "flow" -> "flow"; "publicKey" -> "pbk (public key)"; "shortId" -> "sid"; "alpn" -> "ALPN"; "obfsPassword" -> "obfs-password"
        "customJson" -> "JSON"; else -> k
    }

    // ---- 1.0.30: speed/graph card auto-hide ----
    const val SPEED_CARD_HIDE_DELAY_MS = 5_000L

    /** The card is wanted while the tunnel is up or coming up; it is hidden [SPEED_CARD_HIDE_DELAY_MS] after that ends. */
    fun speedCardWanted(state: net.antibs.client.engine.ConnState): Boolean =
        state == net.antibs.client.engine.ConnState.CONNECTED || state == net.antibs.client.engine.ConnState.CONNECTING

    // ───── 1.0.40: notification traffic line, «Скрывать IP серверов», «Подключаться при запуске» ─────

    fun notificationTraffic(mode: String, downSpeed: Long, upSpeed: Long, downTotal: Long, upTotal: Long): String? {
        val speed = "↓ ${Format.speed(downSpeed)}  ↑ ${Format.speed(upSpeed)}"
        val total = "Всего: ↓ ${Format.bytes(downTotal)}  ↑ ${Format.bytes(upTotal)}"
        return when (mode) { "speed" -> speed; "total" -> total; "both" -> "$speed · $total"; else -> null }
    }

    private val ipv4Re = Regex("""(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\d.])""")
    /** ≥3 colons or «::» — so log timestamps (12:34:56) are never taken for IPv6. */
    private val ipv6Re = Regex("""(?<![0-9A-Fa-f:])(?:(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}|[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)(?![0-9A-Fa-f:])""")

    private fun keepIp(a: Int, b: Int) = a == 127 || a == 10 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
        (a == 169 && b == 254) || (a == 198 && b in 18..19)

    /** Public IPs and the given server hosts → «•••»; private / loopback / fake ranges stay. */
    fun maskLine(line: String, hosts: Collection<String>): String {
        var out = ipv4Re.replace(line) { m ->
            val a = m.groupValues[1].toIntOrNull() ?: 256; val b = m.groupValues[2].toIntOrNull() ?: 256
            if (keepIp(a, b)) m.value else "•••"
        }
        out = ipv6Re.replace(out) { m -> val v = m.value.lowercase(); if (v.startsWith("fe80") || v.startsWith("fd")) m.value else "•••" }
        for (h in hosts) if (h.length >= 4 && out.contains(h, true)) out = out.replace(h, "•••", true)
        return out
    }

    fun serverHosts(servers: List<net.antibs.client.data.ServerEntry>): Set<String> = servers.mapNotNull { s ->
        s.xray?.server?.trim()?.takeIf { it.isNotEmpty() && it != "—" && !ipv4Re.matches(it) && !it.contains(':') }
    }.toSet()

    @Volatile private var launchAutoConnectDone = false
    @Synchronized fun claimLaunchAutoConnect(): Boolean { if (launchAutoConnectDone) return false; launchAutoConnectDone = true; return true }
}
