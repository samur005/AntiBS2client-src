package net.antibs.client.ui

import net.antibs.client.data.ServerEntry
import net.antibs.client.data.ProtocolSupport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 2.3.30: look of the Servers screen. CLASSIC is the old (default, safe) layout. */
enum class ServerStyle(val key: String, val glow: Boolean = false, val fixedLayout: SubLayout? = null) {
    CLASSIC("classic"), NEON("neon", glow = true), CARDS("cards"),
    // 2.3.30 (t138u): four more looks
    AURORA("aurora"), CYBER("cyber", glow = true), STACK("stack", fixedLayout = SubLayout.RIBBON), MATERIAL("material", fixedLayout = SubLayout.RIBBON);
    companion object { fun of(key: String?): ServerStyle = entries.firstOrNull { it.key == key } ?: CLASSIC }
}

/** How subscriptions are shown in the NEON / CARDS styles. */
enum class SubLayout(val key: String) {
    RIBBON("ribbon"), GROUPS("groups");
    companion object { fun of(key: String?, def: SubLayout = GROUPS): SubLayout = entries.firstOrNull { it.key == key } ?: def }
}

/** Filter chips «Все / Быстрые / Избранное». */
enum class ServerFilter(val key: String) {
    ALL("all"), FAST("fast"), FAVORITES("fav");
    companion object { fun of(key: String?): ServerFilter = entries.firstOrNull { it.key == key } ?: ALL }
}

/** Per-style settings. */
data class StyleOptions(val layout: SubLayout = SubLayout.GROUPS, val bestCard: Boolean = true)

/** Pure logic of the styled Servers screen (unit tested). */
object ServerStyleLogic {
    /** «Быстрые»: a measured ping below this. */
    const val FAST_MS = 150
    /** Ribbon chip «Все». */
    const val RIBBON_ALL = ""

    fun pingMs(p: Long?): Int? = p?.takeIf { it >= 0 }?.toInt()

    /** Lowest measured ping among [candidates]; locked / unmeasured / failed ones are skipped; ties keep list order. */
    fun best(candidates: List<ServerEntry>, ping: (String) -> Long?): ServerEntry? {
        var bestCfg: ServerEntry? = null
        var bestMs = Int.MAX_VALUE
        for (c in candidates) {
                        val ms = pingMs(ping(c.id)) ?: continue
            if (ms < bestMs) { bestMs = ms; bestCfg = c }
        }
        return bestCfg
    }

    /** Which servers the «Лучший сервер» card looks at: ribbon = the selected subscription, groups = everything. */
    fun bestScope(layout: SubLayout, ribbonSub: String, all: List<ServerEntry>): List<ServerEntry> =
        if (layout == SubLayout.RIBBON && ribbonSub != RIBBON_ALL) all.filter { inGroup(it, ribbonSub) } else all

    /** Subscriptions as stored are newest first; «Новые снизу» reverses (same pref as Classic's subSortDesc). */
    fun <T> orderSubs(stored: List<T>, newestFirst: Boolean): List<T> = if (newestFirst) stored else stored.reversed()


    /** Defaults that match the mockups (Аврора / Киберпанк open on the ribbon; the hero card only where the mockup has it). */
    fun defaultOptions(s: ServerStyle): StyleOptions = when (s) {
        ServerStyle.AURORA, ServerStyle.CYBER -> StyleOptions(SubLayout.RIBBON, false)
        ServerStyle.STACK, ServerStyle.MATERIAL -> StyleOptions(SubLayout.RIBBON, false)
        else -> StyleOptions(SubLayout.GROUPS, true)
    }

    /** Whether the hand-added servers are listed: groups mode (all styles) = when the group is expanded; in ribbon mode for «Все» / «Ручные серверы». */
    fun manualRowsVisible(styled: Boolean, ribbonMode: Boolean, ribbonSel: String, groupOpen: Boolean): Boolean =
        if (styled && ribbonMode) ribbonSel == RIBBON_ALL || ribbonSel == MANUAL_ID
        else groupOpen  // 2.3.33: groups mode (incl. Classic) — collapsible like any subscription

    /** Pseudo-subscription «Ручные серверы»: servers added by hand (no subscription). */
    const val MANUAL_ID = "__manual__"

    /** ⇅ order of servers inside a group. */
    fun sortServers(list: List<ServerEntry>, mode: String, ping: (String) -> Long?): List<ServerEntry> = when (mode) {
        StylePrefs.SORT_ALPHA -> list.sortedBy { it.name.lowercase() }
        StylePrefs.SORT_FASTEST -> list.sortedBy { pingMs(ping(it.id)) ?: Int.MAX_VALUE }
        else -> list
    }

    fun inGroup(c: ServerEntry, groupId: String): Boolean =
        if (groupId == MANUAL_ID) UiLogic.isManualSub(c.subscriptionId) else c.subscriptionId == groupId

    /** Ids the ribbon / card stack / picker offer: the subscriptions, then «Ручные серверы» when there are any. */
    fun groupIds(subIds: List<String>, configs: List<ServerEntry>): List<String> =
        if (configs.any { UiLogic.isManualSub(it.subscriptionId) }) subIds + MANUAL_ID else subIds

    fun filter(list: List<ServerEntry>, f: ServerFilter, favorites: Set<String>, ping: (String) -> Long?): List<ServerEntry> =
        when (f) {
            ServerFilter.ALL -> list
            ServerFilter.FAST -> list.filter { (pingMs(ping(it.id)) ?: Int.MAX_VALUE) < FAST_MS }
            ServerFilter.FAVORITES -> list.filter { it.id in favorites }
        }

    /** Stored before the user picked anything: open on the first subscription, like the mockups. */
    const val RIBBON_FIRST = "__first__"

    /** Ribbon selection that still exists (a deleted subscription falls back to «Все»; nothing picked yet = the first group). */
    fun validRibbon(sel: String, subIds: Collection<String>): String = when {
        sel == RIBBON_FIRST -> subIds.firstOrNull() ?: RIBBON_ALL
        sel == RIBBON_ALL || sel in subIds -> sel
        else -> RIBBON_ALL
    }

    /** Layout actually used: card stack / Material You always pick one subscription at a time. */
    fun effectiveLayout(style: ServerStyle, opts: StyleOptions): SubLayout = style.fixedLayout ?: opts.layout

    /** Card stack has no «Все» page: an «all» selection falls to the first subscription. */
    fun stackSelection(sel: String, subIds: List<String>): String =
        if (sel != RIBBON_ALL && sel in subIds) sel else subIds.firstOrNull() ?: RIBBON_ALL

    /** Remaining share 0..1 of a subscription quota, null = unlimited. */
    fun remainingFraction(used: Long, total: Long): Float? =
        if (total <= 0) null else ((total - used).coerceAtLeast(0L).toDouble() / total).toFloat().coerceIn(0f, 1f)

    /** Used share 0..1, null = unlimited. */
    fun usedFraction(used: Long, total: Long): Float? = remainingFraction(used, total)?.let { 1f - it }

    /** Lit segments of the cyberpunk traffic counter (used share, rounded up so any use shows one segment). */
    fun litSegments(used: Long, total: Long, segments: Int): Int {
        val f = usedFraction(used, total) ?: return 0
        if (used <= 0) return 0
        return kotlin.math.ceil(f * segments).toInt().coerceIn(1, segments)
    }

    /** Sum over subscriptions: used bytes and quota (quota 0 = at least one is unlimited). */
    data class Totals(val used: Long, val total: Long, val count: Int)
    fun totals(subs: List<Pair<Long, Long>>): Totals {
        val used = subs.sumOf { it.first.coerceAtLeast(0) }
        val total = if (subs.any { it.second <= 0 }) 0L else subs.sumOf { it.second }
        return Totals(used, total, subs.size)
    }

    /** 0..4 signal bars for a ping. */
    fun bars(p: Long?): Int {
        val ms = pingMs(p) ?: return 0
        return when { ms < 60 -> 4; ms < 100 -> 3; ms < 170 -> 2; else -> 1 }
    }
}

/** Tiny key-value seam so the prefs can be tested without Android. */
interface StyleKv {
    fun getString(k: String): String?
    fun putString(k: String, v: String)
    fun getSet(k: String): Set<String>?
    fun putSet(k: String, v: Set<String>)
}

class MapStyleKv : StyleKv {
    val m = HashMap<String, Any>()
    override fun getString(k: String) = m[k] as? String
    override fun putString(k: String, v: String) { m[k] = v }
    @Suppress("UNCHECKED_CAST") override fun getSet(k: String) = m[k] as? Set<String>
    override fun putSet(k: String, v: Set<String>) { m[k] = v.toSet() }
}

/** Servers-screen style prefs; layout / best card / filter / ribbon selection are stored per style. Favourites are shared. */
class StylePrefs(private val kv: StyleKv) {
    companion object { const val SORT_ADDED = "added"; const val SORT_ALPHA = "alpha"; const val SORT_FASTEST = "fastest" }
    private fun k(style: ServerStyle, name: String) = "ss_${style.key}_$name"

    private val _style = MutableStateFlow(ServerStyle.of(kv.getString("ss_style")))
    val style: StateFlow<ServerStyle> = _style.asStateFlow()
    fun setStyle(s: ServerStyle) { kv.putString("ss_style", s.key); _style.value = s }

    private val _options = MutableStateFlow(ServerStyle.entries.associateWith { readOptions(it) })
    val options: StateFlow<Map<ServerStyle, StyleOptions>> = _options.asStateFlow()
    private fun readOptions(s: ServerStyle) = StyleOptions(
        layout = SubLayout.of(kv.getString(k(s, "layout")), ServerStyleLogic.defaultOptions(s).layout),
        bestCard = kv.getString(k(s, "best"))?.let { it != "0" } ?: ServerStyleLogic.defaultOptions(s).bestCard
    )
    fun options(s: ServerStyle): StyleOptions = _options.value[s] ?: ServerStyleLogic.defaultOptions(s)
    fun setLayout(s: ServerStyle, l: SubLayout) { kv.putString(k(s, "layout"), l.key); _options.value = _options.value + (s to options(s).copy(layout = l)) }
    fun setBestCard(s: ServerStyle, on: Boolean) { kv.putString(k(s, "best"), if (on) "1" else "0"); _options.value = _options.value + (s to options(s).copy(bestCard = on)) }

    private val _filter = MutableStateFlow(ServerStyle.entries.associateWith { ServerFilter.of(kv.getString(k(it, "filter"))) })
    val filter: StateFlow<Map<ServerStyle, ServerFilter>> = _filter.asStateFlow()
    fun setFilter(s: ServerStyle, f: ServerFilter) { kv.putString(k(s, "filter"), f.key); _filter.value = _filter.value + (s to f) }

    private val _ribbon = MutableStateFlow(ServerStyle.entries.associateWith { kv.getString(k(it, "ribbon")) ?: ServerStyleLogic.RIBBON_FIRST })
    val ribbon: StateFlow<Map<ServerStyle, String>> = _ribbon.asStateFlow()
    fun setRibbon(s: ServerStyle, subId: String) { kv.putString(k(s, "ribbon"), subId); _ribbon.value = _ribbon.value + (s to subId) }

    // AntiBS2client: ⇅ server sort and subscription order (shared by all styles)
    private val _sort = MutableStateFlow(kv.getString("ss_sort") ?: SORT_ADDED)
    val sort: StateFlow<String> = _sort.asStateFlow()
    fun setSort(m: String) { kv.putString("ss_sort", m); _sort.value = m }
    private val _subNewest = MutableStateFlow(kv.getString("ss_subnew") != "0")
    val subNewestFirst: StateFlow<Boolean> = _subNewest.asStateFlow()
    fun setSubNewestFirst(on: Boolean) { kv.putString("ss_subnew", if (on) "1" else "0"); _subNewest.value = on }

    private val _favorites = MutableStateFlow(kv.getSet("ss_favorites") ?: emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()
    fun toggleFavorite(id: String) {
        val cur = _favorites.value
        val next = if (id in cur) cur - id else cur + id
        kv.putSet("ss_favorites", next); _favorites.value = next
    }
}

/** Test-only hooks for screenshot rendering (never set in the app). */
internal object ShotHooks { @JvmField var viewMenuOpen: Boolean = false }

/** AntiBS2client: hand-added servers live in the «Серверы» pseudo-subscription. */
internal fun UiLogic.isManualSub(id: String?): Boolean = id == null || id == ProtocolSupport.MANUAL_SUB_ID

/** App-wide style prefs (SharedPreferences "styles"); initialised from AppRoot. */
object Styles {
    @Volatile private var p: StylePrefs? = null
    fun get(ctx: android.content.Context): StylePrefs = p ?: synchronized(this) {
        p ?: StylePrefs(SpStyleKv(ctx.getSharedPreferences("styles", android.content.Context.MODE_PRIVATE))).also { p = it }
    }
    /** Tests / screenshots. */
    fun install(prefs: StylePrefs) { p = prefs }
}

class SpStyleKv(private val sp: android.content.SharedPreferences) : StyleKv {
    override fun getString(k: String) = sp.getString(k, null)
    override fun putString(k: String, v: String) { sp.edit().putString(k, v).apply() }
    override fun getSet(k: String) = sp.getStringSet(k, null)
    override fun putSet(k: String, v: Set<String>) { sp.edit().putStringSet(k, v).apply() }
}

/** Russian names shown in the picker. */
val ServerStyle.title: String get() = when (this) {
    ServerStyle.CLASSIC -> "Классический"; ServerStyle.NEON -> "Неон"; ServerStyle.CARDS -> "Карточки"
    ServerStyle.AURORA -> "Аврора"; ServerStyle.CYBER -> "Киберпанк"; ServerStyle.STACK -> "Стопка карт"; ServerStyle.MATERIAL -> "Material You"
}
