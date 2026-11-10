package net.antibs.client.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.Subscription
import net.antibs.client.engine.AppState

/**
 * Styled picker header: logo (Material You: title) on the left;
 * 🎨 view popup (style / subscription view / «Лучший сервер»), ⇅ sort+filter+bulk, + add, ⋮ subscription menu.
 */
@Composable
fun ServersHeader(
    style: ServerStyle,
    layout: SubLayout, bestCard: Boolean,
    onStyle: (ServerStyle) -> Unit, onLayout: (SubLayout) -> Unit, onBestCard: (Boolean) -> Unit,
    sortMode: String, onSortMode: (String) -> Unit, subNewestFirst: Boolean, onSubOrder: (Boolean) -> Unit,
    filter: ServerFilter, onFilter: (ServerFilter) -> Unit,
    testing: Boolean, onTestAll: () -> Unit,
    onAdd: () -> Unit,
    subMenu: Subscription?,
    onSubRefresh: (Subscription) -> Unit, onSubPing: (Subscription) -> Unit, onSubCopy: (Subscription) -> Unit,
    onSubDeleteUnreachable: (Subscription) -> Unit, onSubRestoreHidden: (Subscription) -> Unit, onSubDelete: (Subscription) -> Unit,
    subExtraMenu: (@Composable (Subscription, () -> Unit) -> Unit)? = null,
    viewOpenInitially: Boolean = false,
    /** 2.0.32: list header on «Главная» (the app top bar stays above): small title, no logo and no «+». */
    compact: Boolean = false,
) {
    val c = ssColors(style)
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val iconTint = if (style.glow) c.accent else c.onCard.copy(alpha = 0.8f)
    val menuShape = RoundedCornerShape(16.dp)
    @Composable
    fun HeadIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
        val hf = androidx.compose.ui.platform.LocalHapticFeedback.current
        Icon(icon, contentDescription = desc, tint = iconTint,
            modifier = Modifier.clip(CircleShape).clickable { hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onClick() }.padding(7.dp).size(21.dp))
    }
    @Composable
    fun Item(icon: ImageVector?, label: String, checked: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current) } },
            trailingIcon = if (checked) { { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } } else null,
            enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(42.dp), onClick = onClick
        )
    }
    @Composable
    fun Section(t: String) = Text(t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))

    Row(Modifier.fillMaxWidth().height(if (compact) 38.dp else 46.dp).padding(start = if (compact) 4.dp else 12.dp, end = if (compact) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            Text("Подписки и серверы", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onCard, maxLines = 1)
        } else if (style == ServerStyle.MATERIAL) {
            Text("Серверы", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.onCard, modifier = Modifier.padding(start = 4.dp))
        } else {
            androidx.compose.foundation.Image(painterResource(net.antibs.client.R.drawable.brand_icon), null,
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(9.dp))
            Text("AntiBS2client", style = TextStyle(brush = AntiBSColors.fabBrush, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp), maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Box {
            var viewOpen by remember { mutableStateOf(viewOpenInitially) }
            HeadIcon(PaletteIcon, "Вид") { viewOpen = true }
            DropdownMenu(viewOpen, { viewOpen = false }, shape = menuShape, modifier = Modifier.widthIn(min = 240.dp)) {
                Section("Стиль")
                ServerStyle.entries.forEach { st -> Item(null, st.title, checked = st == style) { onStyle(st) } }
                if (style.fixedLayout == null) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Section("Подписки")
                    Item(null, "Лента сверху", checked = layout == SubLayout.RIBBON) { onLayout(SubLayout.RIBBON) }
                    Item(null, "Раскрывающиеся группы", checked = layout == SubLayout.GROUPS) { onLayout(SubLayout.GROUPS) }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth().clickable { onBestCard(!bestCard) }.padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Карточка «Лучший сервер»", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(bestCard, onCheckedChange = { onBestCard(it) }, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        Box {
            HeadIcon(Icons.Filled.SwapVert, "Сортировка") { sortOpen = true }
            DropdownMenu(sortOpen, { sortOpen = false }, shape = menuShape) {
                Section("Серверы")
                listOf(StylePrefs.SORT_ALPHA to "По алфавиту", StylePrefs.SORT_FASTEST to "Сначала быстрые", StylePrefs.SORT_ADDED to "По дате добавления").forEach { (m, l) ->
                    Item(null, l, checked = sortMode == m) { sortOpen = false; onSortMode(m) }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Section("Подписки")
                Item(null, "Новые сверху", checked = subNewestFirst) { sortOpen = false; onSubOrder(true) }
                Item(null, "Новые снизу", checked = !subNewestFirst) { sortOpen = false; onSubOrder(false) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Item(Icons.Rounded.Star, "Только избранные", checked = filter == ServerFilter.FAVORITES) {
                    sortOpen = false; onFilter(if (filter == ServerFilter.FAVORITES) ServerFilter.ALL else ServerFilter.FAVORITES)
                }
                Item(Icons.Rounded.Bolt, "Только быстрые", checked = filter == ServerFilter.FAST) {
                    sortOpen = false; onFilter(if (filter == ServerFilter.FAST) ServerFilter.ALL else ServerFilter.FAST)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Item(Icons.Filled.Speed, if (testing) "Проверка…" else "Проверить все", enabled = !testing) { sortOpen = false; onTestAll() }
            }
        }
        if (!compact) HeadIcon(Icons.Filled.Add, "Добавить серверы") { onAdd() }
        if (subMenu != null) Box {
            val sub = subMenu
            HeadIcon(Icons.Filled.MoreVert, "Меню подписки") { moreOpen = true }
            DropdownMenu(moreOpen, { moreOpen = false }, shape = menuShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))) {
                Item(Icons.Filled.Refresh, "Обновить") { moreOpen = false; onSubRefresh(sub) }
                Item(Icons.Filled.NetworkCheck, "Проверить пинг") { moreOpen = false; onSubPing(sub) }
                Item(Icons.Filled.ContentCopy, "Скопировать ссылку", enabled = sub.url.isNotBlank()) { moreOpen = false; onSubCopy(sub) }
                if (sub.hiddenHosts.isNotEmpty()) Item(Icons.Filled.Restore, "Показать удалённые хосты") { moreOpen = false; onSubRestoreHidden(sub) }
                Item(Icons.Filled.TimerOff, "Удалить недоступные") { moreOpen = false; onSubDeleteUnreachable(sub) }
                subExtraMenu?.invoke(sub) { moreOpen = false }
                Item(Icons.Filled.DeleteForever, "Удалить", danger = true) { moreOpen = false; onSubDelete(sub) }
            }
        }
    }
}

internal fun subTitle(s: Subscription): String = s.title.ifBlank { s.fileName.ifBlank { if (UiLogic.isManualSub(s.id)) "Серверы" else s.url.substringAfter("://").substringBefore('/') } }

internal fun ribbonItem(s: Subscription, servers: Int, nowSec: Long): RibbonItem {
    val t = UiLogic.traffic(s)
    val used = (t.usedMb * 1048576.0).toLong(); val total = (t.limitMb * 1048576.0).toLong()
    val days = if (s.expireEpoch > 0) UiLogic.daysLeft(s.expireEpoch, nowSec) else -1
    val sub = when { UiLogic.isManualSub(s.id) -> "$servers серв."; days < 0 -> "без срока"; days == 0 -> "истекает"; else -> "$days дн." }
    return RibbonItem(s.id, subTitle(s), sub, warn = days in 0..3, traffic = UiLogic.trafficBarLabel(t),
        remaining = ServerStyleLogic.remainingFraction(used, total), used = used, total = total, servers = servers)
}

/** Styled Servers list body (all styles except Classic's groups mode are drawn here). */
internal fun LazyListScope.styledServers(
    style: ServerStyle, layout: SubLayout, bestCard: Boolean, ribbonSel: String, onRibbon: (String) -> Unit,
    filter: ServerFilter, onFilter: (ServerFilter) -> Unit, favorites: Set<String>,
    sortMode: String, subNewestFirst: Boolean,
    st: AppState, ui: net.antibs.client.engine.UiSettings, pings: Map<String, Long>, pinging: Set<String>, vm: AppViewModel, ctx: Context,
    nowSec: Long, onSelected: () -> Unit, onLanSendServer: (String) -> Unit = {},
) {
    val all = UiLogic.hostGroups(st.subscriptions, st.servers, emptySet()).filter { it.subscription != null && (it.servers.isNotEmpty() || !UiLogic.isManualSub(it.subscription.id)) }
    val ordered = ServerStyleLogic.orderSubs(all, subNewestFirst)
    val items = ordered.map { ribbonItem(it.subscription!!, it.servers.size, nowSec) }
    val ids = items.map { it.id }
    val ping: (String) -> Long? = { pings[it] }
    val sel = if (style.fixedLayout != null) ServerStyleLogic.stackSelection(ServerStyleLogic.validRibbon(ribbonSel, ids), ids) else ServerStyleLogic.validRibbon(ribbonSel, ids)
    val shown = if (sel == ServerStyleLogic.RIBBON_ALL) ordered.flatMap { it.servers } else ordered.firstOrNull { it.subscription?.id == sel }?.servers.orEmpty()
    val list = ServerStyleLogic.sortServers(ServerStyleLogic.filter(shown, filter, favorites, ping), sortMode, ping)
    item(key = "ss-top") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) {
            val ribbonItems = if (style.fixedLayout == null) listOf(RibbonItem(ServerStyleLogic.RIBBON_ALL, "Все", "${st.servers.size} серв.")) + items else items
            val cur = items.firstOrNull { it.id == sel }
            when (style) {
                ServerStyle.STACK -> SubCardStack(items, sel, { "$it серв." }, "листайте", onRibbon)
                ServerStyle.MATERIAL -> {
                    val tot = cur?.let { ServerStyleLogic.Totals(it.used, it.total, 1) }
                    MaterialTrafficCard("Использовано", UiLogic.compactSize((tot?.used ?: 0).toDouble()), cur?.total?.takeIf { it > 0 }?.let { "из " + UiLogic.compactSize(it.toDouble()) } ?: "из ∞", items, sel, "Выбрать подписку", onRibbon)
                }
                else -> {
                    SubscriptionRibbon(style, ribbonItems, sel, onRibbon) { order -> vm.reorderSubscriptions(order, Styles.get(ctx).subNewestFirst.value) }
                    if (style == ServerStyle.CYBER && cur != null) CyberTrafficCard(cur.title, UiLogic.compactSize(cur.used.toDouble()),
                        "/ " + (if (cur.total > 0) UiLogic.compactSize(cur.total.toDouble()) else "∞"), cur.subtitle, cur.used, cur.total)
                }
            }
            if (bestCard) {
                val best = ServerStyleLogic.best(ServerStyleLogic.bestScope(SubLayout.RIBBON, sel, ordered.flatMap { it.servers }), ping)
                val ms = best?.let { ServerStyleLogic.pingMs(ping(it.id)) }
                BestServerCard(style, "Лучший сервер", best?.let { UiLogic.displayName(it.name) } ?: "", best?.let { UiLogic.protocolGroup(it) } ?: "",
                    ms, ms?.let { "$it мс" } ?: "", "Подключить", "Проверьте пинг, чтобы найти лучший сервер",
                    onAction = { best?.let { vm.select(it.id); onSelected() } }, onRetest = { vm.pingHosts(shown) })
            }
        }
    }
    if (list.isEmpty()) item(key = "ss-empty") { Text("Нет серверов", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
    // 2.0.6 (ABS): country groups inside the chosen subscription (or «Все»)
    val sg = st.adv.serverGrouping
    if (sg.isOn(sel) && list.size > 1) {
        val gs = sg.forSub(sel)
        net.antibs.client.data.ServerGrouping.group(list, { it.name }, gs.sort, { pings[it.id] }).forEach { (code, l) ->
            val open = sg.isOpen(sel, code)
            item(key = "ss-cg-$sel-$code") {
                CountryHeader(code, l.size, open, net.antibs.client.data.ServerGrouping.bestPing(l.map { pings[it.id] }), gs.showPing) {
                    vm.setAdv { it.copy(serverGrouping = it.serverGrouping.toggle(sel, code)) }
                }
            }
            if (open) items(l, key = { "ss-" + it.id }) { s -> StyledHostRow(style, s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, favorites, onSelected, onLanSend = { onLanSendServer(s.id) }) }
        }
        return
    }
    items(list, key = { "ss-" + it.id }) { s -> StyledHostRow(style, s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, favorites, onSelected, onLanSend = { onLanSendServer(s.id) }) }
}

/** Server row in a styled card: glow/border per style, favourite star; the row itself is the classic one (menus, swipe, edit). */
@Composable
internal fun StyledHostRow(style: ServerStyle, s: ServerEntry, st: AppState, pings: Map<String, Long>, busy: Boolean, compact: Boolean,
                           vm: AppViewModel, ctx: Context, favorites: Set<String>, onSelected: () -> Unit, onLanSend: (() -> Unit)? = null) {
    val c = ssColors(style)
    val sel = s.id == st.selectedServerId
    val shape = ssShape(style, 14.dp)
    val prefs = Styles.get(ctx)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .neonGlow(c.accent, sel && style.glow && c.dark, 14.dp, 10.dp, 0.4f)
            .clip(shape).background(if (sel) c.selectedFill else c.card)
            .border(if (sel) 1.5.dp else 1.dp, if (sel) c.accent else c.cardBorder, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { HostServerRow(s, st, pings, busy, compact, vm, ctx, onSelected = onSelected, onLanSend = onLanSend) }
        FavoriteStar(s.id in favorites, { prefs.toggleFavorite(s.id) }, c.muted)
        Spacer(Modifier.width(4.dp))
    }
}


/** Style picker opened from the 🎨 icon of the Home top bar (same choices as the Servers view popup). */
@Composable
internal fun StyleViewDialog(onDismiss: () -> Unit) {
    val sp = Styles.get(androidx.compose.ui.platform.LocalContext.current)
    val cur by sp.style.collectAsState()
    ChoiceDialog("Стиль оформления", ServerStyle.entries.map { it.key to it.title }, cur.key, { sp.setStyle(ServerStyle.of(it)) }, onDismiss)
}

/** Settings block «Стиль оформления»: style grid, subscription view and «Лучший сервер» (per style). */
@Composable
internal fun StyleSettingsSection() {
    val sp = Styles.get(androidx.compose.ui.platform.LocalContext.current)
    val ssStyle by sp.style.collectAsState()
    val ssOptsMap by sp.options.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Стиль оформления", style = MaterialTheme.typography.titleMedium)
        Text("Цвета, карточки и панели всего приложения; список серверов получает свой вид. Светлая/тёмная тема — по настройке темы",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ServerStyle.entries.chunked(3).forEach { rowStyles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowStyles.forEach { st -> StyleOption(st.title, ssStyle == st, { sp.setStyle(st) }, Modifier.weight(1f)) }
                repeat(3 - rowStyles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (ssStyle != ServerStyle.CLASSIC) {
            val o = ssOptsMap[ssStyle] ?: ServerStyleLogic.defaultOptions(ssStyle)
            val lay = ServerStyleLogic.effectiveLayout(ssStyle, o)
            if (ssStyle.fixedLayout == null) {
                Text("Подписки", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StyleOption("Лента подписок", o.layout == SubLayout.RIBBON, { sp.setLayout(ssStyle, SubLayout.RIBBON) }, Modifier.weight(1f))
                    StyleOption("Раскрывающиеся подписки", o.layout == SubLayout.GROUPS, { sp.setLayout(ssStyle, SubLayout.GROUPS) }, Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { sp.setBestCard(ssStyle, !o.bestCard) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Карточка «Лучший сервер»", style = MaterialTheme.typography.bodyLarge)
                    Text(if (lay == SubLayout.RIBBON) "Сервер с наименьшим пингом в выбранной подписке" else "Сервер с наименьшим пингом среди всех подписок",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = o.bestCard, onCheckedChange = { sp.setBestCard(ssStyle, it) })
            }
        }
    }
}

@Composable
private fun StyleOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .clickable { onClick() }.padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
