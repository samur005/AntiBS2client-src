package net.antibs.client.ui

import net.antibs.client.data.TrafficBarStyle
import net.antibs.client.data.LinkParser

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.asPaddingValues
import net.antibs.client.data.AppLang
import net.antibs.client.data.I18n
import net.antibs.client.data.ProtocolSupport

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.animation.togetherWith
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.antibs.client.BuildConfig
import net.antibs.client.data.Subscription
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.ThemeMode
import net.antibs.client.engine.UiSettings
import net.antibs.client.engine.VpnState
import net.antibs.client.service.AntiBSVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

private enum class Page {
    // 1.0.8: no bottom navigation. Main is the only root; Routing and Logs live under Settings.
    Main, Routing, Settings, Logs,
    // detail pages
    RoutingEdit, SpeedTest, Interface, PerApp, Fragment, Noise, Mux, Inbounds, Vpn, Excluded, Subs, Ping, Protocols, Hwid,
    GeoFiles, NetScan,
    // 1.0.56: «Сеть» — сотовый мониторинг, Wi‑Fi анализатор, LAN, инструменты
    NetTools,
    // 1.0.50 settings sub-screens
    SetCores, SetNet, SetSubAuto,
    // 2.0.6 (ABS port)
    Seasonal, SubRemain, Schedule,
    // 2.0.42: power-button appearance
    PowerButton,
    // 2.2.1: subscription traffic bar appearance
    TrafficBar,
    // 2.0.12: LAN transfer
    LanSend, LanRecv,
}

/** Pages a detail page may return to when it was opened from them (Per-app is reachable from Settings and Routing). */
private val hubPages = setOf(Page.Main, Page.Settings, Page.Routing, Page.Logs, Page.Vpn, Page.Interface, Page.SetCores, Page.SetNet, Page.SetSubAuto)

private fun parentOf(p: Page): Page = when (p) {
    Page.Main, Page.Settings, Page.SpeedTest, Page.NetTools, Page.LanSend, Page.LanRecv -> Page.Main
    Page.RoutingEdit -> Page.Routing
    Page.Excluded -> Page.Settings
    Page.GeoFiles -> Page.Routing
    Page.PowerButton, Page.TrafficBar -> Page.Interface
    else -> Page.Settings
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    val st by vm.state.collectAsState()
    // Read the collected preference (StateFlow), never a one-shot store.theme() snapshot.
    val stylePrefs = Styles.get(LocalContext.current)
    val appStyle by stylePrefs.style.collectAsState()
    // touch I18n.tag so language changes recompose the tree
    val langTag = I18n.tag
    val layoutDir = if (AppLang.isRtl(langTag)) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
    AntiBSTheme(ui.theme, st.adv.accent, appStyle, st.adv.fontFamily, st.adv.fontScalePct) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides layoutDir) {
    // 1.0.55: every Compose haptic respects «Вибрация» (Настройки интерфейса); the system touch-feedback setting applies too.
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val gatedHaptic = remember(hapticView, st.adv.haptics) { AppHaptics.feedback(hapticView, st.adv.haptics) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalHapticFeedback provides gatedHaptic) {
        var pageName by rememberSaveable { mutableStateOf(Page.Main.name) }
        var routingId by rememberSaveable { mutableStateOf<String?>(null) }
        // Detail pages remember which page opened them.
        var backTo by rememberSaveable { mutableStateOf<String?>(null) }
        val page = Page.valueOf(pageName)
        // 2.0.13: «+» sheet tile (TV «Принять с телефона», phone «Принять с устройства») → receive screen
        var lanSendSub by rememberSaveable { mutableStateOf<String?>(null) }
        var lanSendServerId by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsFlash by remember { mutableStateOf(SettingsFlash()) }
        val tvNow = LocalIsTv.current
        DisposableEffect(tvNow) {
            TvHooks.receiveFromPhone = { _ -> backTo = pageName; pageName = Page.LanRecv.name }
            onDispose { TvHooks.receiveFromPhone = null }
        }
        fun open(p: Page) { backTo = pageName; pageName = p.name }
        fun back() {
            val target = backTo?.let { Page.valueOf(it) }?.takeIf { it != page && (it in hubPages || parentOf(page) == it) } ?: parentOf(page)
            backTo = null
            pageName = target.name
        }
        BackHandler(enabled = page != Page.Main) { back() }
        val payReturn by net.antibs.client.MainActivity.paymentReturn.collectAsState()
        LaunchedEffect(payReturn) { if (payReturn != null) { backTo = null; pageName = Page.Main.name } }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
            // 2.0.11: TV — Main is full-width (side rail + two panes); other pages are a centred column.
            TvColumn(enabled = LocalIsTv.current && page != Page.Main) {
            when (page) {
                Page.Main -> MainScreen(vm, ui, onOpenSettings = { open(Page.Settings) }, onOpenSpeedTest = { open(Page.SpeedTest) }, onOpenNet = { open(Page.NetTools) },
                    onLanSend = { id -> lanSendServerId = null; lanSendSub = id; open(Page.LanSend) },
                    onLanSendServer = { id -> lanSendSub = null; lanSendServerId = id; open(Page.LanSend) })
                Page.LanSend -> LanSendPage(vm, lanSendSub, onBack = { back() }, serverId = lanSendServerId)
                Page.LanRecv -> LanRecvPage(vm, onBack = { back() })
                Page.NetTools -> NetToolsPage(vm, onBack = { back() })
                Page.SpeedTest -> SpeedTestPage(vm, onBack = { back() })
                Page.Settings, Page.Vpn, Page.Interface, Page.SetCores, Page.SetNet, Page.SetSubAuto ->
                    CompositionLocalProvider(LocalSettingsFlash provides settingsFlash) {
                        SettingsHome(
                            vm, ui,
                            onBack = { back() },
                            open = { open(Page.valueOf(it)) },
                            section = page.name,
                            onSearchNavigate = { entry ->
                                expandSettingsCard(entry.card, mainHub = entry.section == "Settings")
                                settingsFlash = SettingsFlash(entry.itemId, System.nanoTime())
                                open(Page.valueOf(entry.section))
                            },
                        )
                    }
                Page.Routing -> RoutingListPage(vm, st, onOpenProfile = { routingId = it; open(Page.RoutingEdit) }, onBack = { back() }, onOpenPage = { open(Page.valueOf(it)) })
                Page.Logs -> LogsTab(vm, onBack = { back() })
                Page.RoutingEdit -> RoutingEditPage(vm, st, routingId ?: "", onBack = { pageName = Page.Routing.name })
                else -> CompositionLocalProvider(LocalSettingsFlash provides settingsFlash) {
                    SettingsDetail(page, vm, ui, onBack = { back() }, onOpen = { open(it) })
                }
            }
            }
            // 2.0.6 (ABS): snow + garland over the main screen (draw-only, no touch handling)
            if (page == Page.Main) {
                val (snowOn, garlandOn) = remember(st.adv.seasonalFx, st.adv.seasonalDecor, st.adv.customHolidays) { seasonalNow(st.adv) }
                SeasonalOverlay(st.adv.seasonalFx, snowOn, garlandOn, androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            }
            UpdateDialog(vm)
            UnreachableDialog(vm)
            FilterDeleteDialog(vm)
        }
        // 1.0.55: one light tick per actual settings / menu page change (not on first show)
        AppHaptics.TickOnChange(pageName, st.adv.haptics)
    }
    }
    }
}

/** 2.0.37: confirmation after long-press «Удалить» on a Home filter chip. */
@Composable
internal fun FilterDeleteDialog(vm: AppViewModel) {
    val p by vm.filterDelete.collectAsState()
    val q = p ?: return
    val n = q.ids.size
    AlertDialog(
        onDismissRequest = { vm.dismissFilterDelete() },
        title = { Text("Удалить $n ${UiLogic.serversWord(n)}?") },
        text = { Text("Будут удалены серверы «${q.label}» из всех подписок. Подключённый сервер не удаляется. Удалённые серверы подписки можно вернуть через ⋮ «Показать удалённые хосты».") },
        confirmButton = { TextButton(onClick = { vm.confirmDeleteChip(q) }) { Text("Удалить", color = AntiBSColors.Err) } },
        dismissButton = { TextButton(onClick = { vm.dismissFilterDelete() }) { Text("Отмена") } },
    )
}

/** 1.0.52: confirmation for ⋮ «Удалить недоступные» (per subscription). */
@Composable
internal fun UnreachableDialog(vm: AppViewModel) {
    val p by vm.unreachable.collectAsState()
    val q = p ?: return
    val n = q.ids.size
    AlertDialog(
        onDismissRequest = { vm.dismissUnreachable() },
        title = { Text("Удалить $n ${net.antibs.client.shop.Panel.plural(n, "недоступный сервер", "недоступных сервера", "недоступных серверов")}?") },
        text = { Text("Последний пинг не прошёл. Подключённый сервер не удаляется. Удалённые серверы подписки можно вернуть через ⋮ «Показать удалённые хосты».") },
        confirmButton = { TextButton(onClick = { vm.deleteUnreachable(q) }) { Text("Удалить", color = AntiBSColors.Err) } },
        dismissButton = { TextButton(onClick = { vm.dismissUnreachable() }) { Text("Отмена") } },
    )
}

/** 1.0.19: update flow — changelog → download (progress, SHA-256) → system installer / permission guide. */
@Composable
internal fun UpdateDialog(vm: AppViewModel) {
    val u by vm.update.collectAsState()
    when (val s = u) {
        is AppViewModel.UpdateUi.Idle, is AppViewModel.UpdateUi.Checking -> {}
        is AppViewModel.UpdateUi.Available -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("Доступна версия ${s.u.release.version}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Установлена ${BuildConfig.VERSION_NAME}. Файл: ${s.u.apk.name}" +
                        (if (s.u.apk.size > 0) " (%.1f МБ)".format(s.u.apk.size / 1048576.0) else ""), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(s.u.release.notes.ifBlank { "Без описания" }, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.downloadUpdate(s.u) }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("Позже") } },
        )
        is AppViewModel.UpdateUi.Downloading -> UpdateDownloadWindow(s,
            onMinimize = { vm.setUpdateMinimized(true) }, onRestore = { vm.setUpdateMinimized(false) }, onCancel = { vm.cancelUpdateDownload() })
        is AppViewModel.UpdateUi.NeedPermission -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("Нужно разрешение") },
            text = { Text("Разрешите AntiBS2client «Установку неизвестных приложений» в настройках, вернитесь и нажмите «Установить».") },
            confirmButton = { TextButton(onClick = { vm.installUpdate(s.u, s.file) }) { Text("Установить") } },
            dismissButton = { TextButton(onClick = { vm.openInstallPermission() }) { Text("Открыть настройки") } },
        )
        is AppViewModel.UpdateUi.Ready -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("Обновление ${s.u.release.version}") },
            text = { Text("Файл загружен и проверен (SHA-256). Если установщик не открылся, нажмите «Установить».") },
            confirmButton = { TextButton(onClick = { vm.installUpdate(s.u, s.file) }) { Text("Установить") } },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("Закрыть") } },
        )
        is AppViewModel.UpdateUi.Error -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("Обновление") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, ui: UiSettings, onOpenSettings: () -> Unit, onOpenSpeedTest: () -> Unit, onOpenNet: () -> Unit = {}, onLanSend: (String?) -> Unit = {}, onLanSendServer: (String) -> Unit = {}) {
    val st by vm.state.collectAsState()
    val vpn by VpnState.status.collectAsState()
    val pings by vm.pings.collectAsState()
    val pinging by vm.pinging.collectAsState()
    val pingBatch by vm.pingBatch.collectAsState()
    val pingStopped by vm.pingStopped.collectAsState()
    val unreachableScan by vm.unreachableScan.collectAsState()
    val tunnelPing by vm.tunnelPing.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val captcha by VpnState.captcha.collectAsState()
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) AntiBSVpnService.connect(ctx)
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun toggle() {
        if (st.adv.haptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        if (vpn.state == ConnState.CONNECTED || vpn.state == ConnState.CONNECTING) {
            AntiBSVpnService.disconnect(ctx); return
        }
        val prep = VpnService.prepare(ctx)
        // 2.0.11: some TV boxes ship without the system VPN-consent activity → report instead of crashing
        if (prep != null) runCatching { launcher.launch(prep) }.onFailure { vm.message("Система не показала запрос VPN: ${it.message ?: it.javaClass.simpleName}") }
        else AntiBSVpnService.connect(ctx)
    }
    // 1.0.40: «Вибрация» when the tunnel comes up
    var wasConnected by remember { mutableStateOf(vpn.state == ConnState.CONNECTED) }
    LaunchedEffect(vpn.state) {
        val c = vpn.state == ConnState.CONNECTED
        if (c && !wasConnected && st.adv.haptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        wasConnected = c
    }
    // 1.0.40: «Подключаться при запуске» — once per app process
    LaunchedEffect(Unit) {
        if (st.adv.autoConnectOnLaunch && st.selectedServerId != null && vpn.state == ConnState.DISCONNECTED && UiLogic.claimLaunchAutoConnect()) {
            val prep = VpnService.prepare(ctx)
            if (prep != null) launcher.launch(prep) else AntiBSVpnService.connect(ctx)
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(vpn.state) { while (true) { delay(1000); tick++ } }
    // 1.0.10: 60 s of EMA-smoothed history; [sampleSeq] drives the smooth scroll animation in the sparkline.
    // 2.0.32: VPN only — ↓ incoming (blue) and ↑ outgoing (red) as two series; direct traffic left the banner.
    val vpnSamples = remember { mutableStateListOf<Float>() }
    val upSamples = remember { mutableStateListOf<Float>() }
    var sampleSeq by remember { mutableIntStateOf(0) }
    // 1.0.24: arrival time of every sample (uptime ms) — the sparkline scrolls by wall-clock, not by sample count.
    val sampleTimes = remember { mutableStateListOf<Long>() }
    LaunchedEffect(tick) {
        if (vpn.state == ConnState.CONNECTED) {
            UiLogic.pushSmoothed(vpnSamples, vpn.downSpeed.toFloat())
            UiLogic.pushSmoothed(upSamples, vpn.upSpeed.toFloat())
            sampleTimes.add(android.os.SystemClock.uptimeMillis())
            while (sampleTimes.size > vpnSamples.size) sampleTimes.removeAt(0)
            sampleSeq++
        }
    }
    val server = st.servers.firstOrNull { it.id == st.selectedServerId }
    val connected = vpn.state == ConnState.CONNECTED
    var showAdd by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    val vpnOn = UiLogic.speedCardWanted(vpn.state)
    var speedCardVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(vpnOn) }
    LaunchedEffect(vpnOn) {
        if (vpnOn) speedCardVisible = true
        else { delay(UiLogic.SPEED_CARD_HIDE_DELAY_MS); speedCardVisible = false }
    }
    // Fixed order: subscription order, auto-select entries hidden (1.0.8).
    val groups = UiLogic.hostGroups(st.subscriptions, st.servers, ui.collapsedSubs)
    val nowSec = System.currentTimeMillis() / 1000
    var pingAsked by remember { mutableStateOf(false) }
    var pingStamp by remember { mutableStateOf(0L) }
    val pingBusy = server != null && server.id in pinging
    LaunchedEffect(pingBusy) { if (!pingBusy && pingAsked) { pingAsked = false; pingStamp = System.currentTimeMillis() } }
    // 1.0.42: bottom navigation «Магазин» · «Главная» · «Серверы» (Главная is the start tab)
    var tabName by rememberSaveable { mutableStateOf(MainTab.Home.name) }
    val tab = MainTab.valueOf(tabName)
    // 2.0.36: Home filters are session-only (no prefs); empty = subscription banners.
    var homeTab by remember { mutableStateOf("") }
    var homeCountry by remember { mutableStateOf("") }
    // 1.0.55: one light tick per actual tab change — navbar tap, back, «Серверы» links, picking a server
    AppHaptics.TickOnChange(tab, st.adv.haptics)
    BackHandler(enabled = tab != MainTab.Home) { tabName = MainTab.Home.name }
    // 1.0.43: back from the payment page (antibs2client://payment-return) → «Магазин»
    val payReturn by net.antibs.client.MainActivity.paymentReturn.collectAsState()
    LaunchedEffect(payReturn) { if (payReturn != null) tabName = MainTab.Shop.name }

    val sp = Styles.get(ctx)
    val sStyle by sp.style.collectAsState()
    val sOpts by sp.options.collectAsState()
    val sFilter by sp.filter.collectAsState()
    val sRibbon by sp.ribbon.collectAsState()
    val sFav by sp.favorites.collectAsState()
    val sSort by sp.sort.collectAsState()
    val sSubNew by sp.subNewestFirst.collectAsState()
    val opts = sOpts[sStyle] ?: ServerStyleLogic.defaultOptions(sStyle)
    val effLayout = ServerStyleLogic.effectiveLayout(sStyle, opts)
    val bg = ssBackground(sStyle)
    val isTv = LocalIsTv.current
    // 2.0.32: «Главная» = banner + power + URL test + the subscriptions/servers list (map/globe and the «Серверы» tab removed)
    val homeList = androidx.compose.foundation.lazy.rememberLazyListState()
    val homeScope = androidx.compose.runtime.rememberCoroutineScope()
    val toTop: () -> Unit = { homeScope.launch { runCatching { homeList.animateScrollToItem(0) } } }
    val vDrag = remember { VReorderState() }
    val dragHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(Modifier.fillMaxSize()) {
    if (isTv) MainSideRail(tab, onSelect = { tabName = it.name })
    Column(Modifier.weight(1f).fillMaxHeight().then(if (bg != null && tab == MainTab.Home) Modifier.background(bg) else Modifier).windowInsetsPadding(WindowInsets.statusBars)) {
        if (tab == MainTab.Home || tab == MainTab.Shop) TopBar(
            theme = ui.theme,
            onTheme = { vm.setTheme(it) },
            onSettings = onOpenSettings,
            onAdd = { showAdd = true },
            onSpeedTest = onOpenSpeedTest,
            onNet = onOpenNet,
            onStyle = { showStyle = true },
        )
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it * dir / 6 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { -it * dir / 6 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "main-tab",
        ) { shown ->
        when (shown) {
        MainTab.Shop -> TvColumn(isTv) { ShopScreen(vm, onOpenServers = { tabName = MainTab.Home.name }) }
        // 2.0.32: «Тест скорости» and «Сеть» became bottom-bar tabs; 2.0.41: speed tab is «Сессия»
        MainTab.Speed -> CompositionLocalProvider(LocalInTabs provides true) { TvColumn(isTv) { SpeedTestPage(vm, onBack = { tabName = MainTab.Home.name }) } }
        MainTab.Net -> CompositionLocalProvider(LocalInTabs provides true) { TvColumn(isTv) { NetToolsPage(vm, onBack = { tabName = MainTab.Home.name }) } }
        MainTab.Home -> {
                val homeOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                DisposableEffect(homeOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.setHomeVisible(true)
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) vm.setHomeVisible(false)
                    }
                    homeOwner.lifecycle.addObserver(obs)
                    if (homeOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) vm.setHomeVisible(true)
                    onDispose { homeOwner.lifecycle.removeObserver(obs); vm.setHomeVisible(false) }
                }
            val ribbonIds = st.subscriptions.map { it.id }
            val ribbonSel = if (sStyle != ServerStyle.CLASSIC && effLayout == SubLayout.RIBBON)
                ServerStyleLogic.validRibbon(sRibbon[sStyle] ?: ServerStyleLogic.RIBBON_FIRST, ribbonIds) else null
            val allVisible = UiLogic.visibleServers(st.servers)
            val filterTabs = HomeFilters.tabs(allVisible)
            val activeTab = HomeFilters.resolveTab(homeTab, filterTabs)
            val countryScope = if (activeTab.isBlank()) allVisible else allVisible.filter { HomeFilters.matchesTab(it, activeTab) }
            val filterCountries = HomeFilters.countries(countryScope, pings)
            val activeCountry = HomeFilters.resolveCountry(homeCountry, filterCountries)
            val filtersOn = HomeFilters.filterActive(activeTab, activeCountry)
            val serverItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                run {
                if (pingBatch.isNotEmpty()) item(key = "ping-counter") { Box(Modifier.animateItem()) { PingCounterPill(pingBatch, pinging, pings, onClose = { vm.clearPingBatch() }, onStop = { vm.stopPingBatch() }, stopped = pingStopped) } }
                if (st.subscriptions.isEmpty() && st.servers.isEmpty()) {
                    item { Text("Нет подписок. Нажмите «+» вверху справа или купите подписку в «Магазине».", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp)) }
                }
                if (filtersOn) {
                    val scoped = HomeFilters.apply(allVisible, activeTab, activeCountry)
                    val sFilt = sFilter[sStyle] ?: ServerFilter.ALL
                    val list = ServerStyleLogic.sortServers(ServerStyleLogic.filter(scoped, sFilt, sFav) { pings[it] }, sSort) { pings[it] }
                    if (list.isEmpty()) item(key = "flat-empty") {
                        Text("Нет серверов", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                    items(list, key = { "flat-" + it.id }) { s ->
                        if (sStyle == ServerStyle.CLASSIC) HostServerRow(s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, onSelected = { toTop() }, onLanSend = { onLanSendServer(s.id) })
                        else StyledHostRow(sStyle, s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, sFav, onSelected = { toTop() }, onLanSend = { onLanSendServer(s.id) })
                    }
                    return@run
                }
                // styles: ribbon / card stack / Material You pick one subscription at a time
                if (sStyle != ServerStyle.CLASSIC && effLayout == SubLayout.RIBBON) {
                    styledServers(sStyle, effLayout, opts.bestCard, sRibbon[sStyle] ?: ServerStyleLogic.RIBBON_FIRST, { sp.setRibbon(sStyle, it) },
                        sFilter[sStyle] ?: ServerFilter.ALL, { sp.setFilter(sStyle, it) }, sFav, sSort, sSubNew,
                        st, ui, pings, pinging, vm, ctx, nowSec, onSelected = { toTop() },
                        onLanSendServer = onLanSendServer, homeTab = activeTab, homeCountry = activeCountry)
                    return@run
                }
                if (sStyle != ServerStyle.CLASSIC && opts.bestCard) item(key = "ss-best") {
                    val pf: (String) -> Long? = { pings[it] }
                    val best = ServerStyleLogic.best(st.servers, pf)
                    val ms = best?.let { ServerStyleLogic.pingMs(pf(it.id)) }
                    Box(Modifier.padding(vertical = 4.dp)) {
                        BestServerCard(sStyle, "Лучший сервер", best?.let { UiLogic.displayName(it.name) } ?: "", best?.let { UiLogic.protocolGroup(it) } ?: "",
                            ms, ms?.let { "$it мс" } ?: "", "Подключить", "Проверьте пинг, чтобы найти лучший сервер",
                            onAction = { best?.let { vm.select(it.id); toTop() } }, onRetest = { vm.pingAll() })
                    }
                }
                val sFilt = sFilter[sStyle] ?: ServerFilter.ALL
                val shownGroups = ServerStyleLogic.orderSubs(groups, sSubNew)
                shownGroups.forEach { g0 ->
                        val scoped = HomeFilters.apply(g0.servers, activeTab, activeCountry)
                        val g = g0.copy(servers = ServerStyleLogic.sortServers(ServerStyleLogic.filter(scoped, sFilt, sFav) { pings[it] }, sSort) { pings[it] })
                        val sub = g.subscription
                        // «Серверы» (manual) is omitted entirely when empty — no blank card/header.
                        if (sub != null && sub.id == ProtocolSupport.MANUAL_SUB_ID && g.total == 0 && st.adv.hideEmptyManual &&
                            st.servers.none { it.subscriptionId == sub.id }) {
                            return@forEach
                        }
                        if (sub != null) {
                            item(key = "sub-${sub.id}") {
                                // 1.0.55: long-press + drag to reorder subscriptions
                                Box(Modifier.vReorderItem(vDrag, homeList, sub.id, dragHaptic) { d, t ->
                                    vm.moveSubscription(d, t, sp.subNewestFirst.value)
                                }) {
                                SubCard(
                                    sub = sub,
                                    barStyle = st.adv.trafficBar,
                                    realServers = UiLogic.realServerCount(st.servers, sub.id),
                                    refreshing = sub.id in refreshing,
                                    pinging = UiLogic.subPinging(st.servers, sub.id, pinging),
                                    nowSec = nowSec,
                                    expanded = g.expanded,
                                    onToggle = { vm.toggleSubCollapsed(sub.id) },
                                    onRefresh = { vm.refresh(sub.id) },
                                    onPing = { vm.pingSubscription(sub.id) },
                                    onDelete = { vm.removeSubscription(sub.id) },
                                    onRestoreHidden = { vm.restoreHiddenHosts(sub.id) },
                                    onDeleteUnreachable = { vm.askDeleteUnreachable(sub.id) },
                                    scanningUnreachable = sub.id in unreachableScan,
                                    onAutoDeleteUnreachable = { on -> vm.setSubscriptionAutoDelete(sub.id, on) },
                                    extraMenu = { close ->
                                        GroupingMenuItems(vm, st.adv.serverGrouping, sub.id, close)
                                        androidx.compose.material3.DropdownMenuItem(text = { Text("Передать на устройство") }, leadingIcon = { MenuIcon(Icons.Filled.SendToMobile) }, onClick = { close(); onLanSend(sub.id) })
                                        // 2.0.11: TV has no long-press drag → move up / down in the ⋮ menu
                                        if (isTv) {
                                            val order = shownGroups.mapNotNull { it.subscription?.id }
                                            val i = order.indexOf(sub.id)
                                            if (i > 0) androidx.compose.material3.DropdownMenuItem(text = { Text("Переместить выше") }, leadingIcon = { MenuIcon(Icons.Filled.ArrowUpward) },
                                                onClick = { close(); vm.moveSubscription(sub.id, order[i - 1], sp.subNewestFirst.value) })
                                            if (i in 0 until order.lastIndex) androidx.compose.material3.DropdownMenuItem(text = { Text("Переместить ниже") }, leadingIcon = { MenuIcon(Icons.Filled.ArrowDownward) },
                                                onClick = { close(); vm.moveSubscription(sub.id, order[i + 1], sp.subNewestFirst.value) })
                                        }
                                    },
                                    onCopy = {
                                        copyText(ctx, sub.url)
                                        vm.message("Ссылка скопирована")
                                    },
                                )
                                }
                            }
                        } else if (g.total > 0 || !g.expanded) {
                            item(key = "sub-orphans") {
                                SectionHeader(
                                    title = "Без подписки",
                                    count = g.total,
                                    expanded = g.expanded,
                                    onToggle = { vm.toggleSubCollapsed(UiLogic.ORPHAN_GROUP) },
                                )
                            }
                        }
                        // 1.0.9: hosts are always a single-column list (the grid toggle was removed;
                        // the legacy persisted «host_grid» flag is still read/written but ignored).
                        val gid = sub?.id ?: UiLogic.ORPHAN_GROUP
                        val sg = st.adv.serverGrouping
                        // 2.0.6 (ABS): «Группировать по странам» — per subscription, with its own override
                        if (sg.isOn(gid) && g.servers.size > 1) {
                            val gs = sg.forSub(gid)
                            net.antibs.client.data.ServerGrouping.group(g.servers, { it.name }, gs.sort, { pings[it.id] }).forEach { (code, list) ->
                                val open = sg.isOpen(gid, code)
                                item(key = "cg-$gid-$code") {
                                    CountryHeader(code, list.size, open, net.antibs.client.data.ServerGrouping.bestPing(list.map { pings[it.id] }), gs.showPing) {
                                        vm.setAdv { it.copy(serverGrouping = it.serverGrouping.toggle(gid, code)) }
                                    }
                                }
                                if (open) items(list, key = { it.id }) { s ->
                                    if (sStyle == ServerStyle.CLASSIC) HostServerRow(s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, onSelected = { toTop() }, onLanSend = { onLanSendServer(s.id) })
                                    else StyledHostRow(sStyle, s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, sFav, onSelected = { toTop() }, onLanSend = { onLanSendServer(s.id) })
                                }
                            }
                        } else
                        items(g.servers, key = { it.id }) { s ->
                            if (sStyle == ServerStyle.CLASSIC) HostServerRow(s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, onSelected = { toTop() }, onLanSend = { onLanSendServer(s.id) })
                            else StyledHostRow(sStyle, s, st, pings, s.id in pinging, ui.compactServers, vm, ctx, sFav, onSelected = { toTop() }, onLanSend = { onLanSendServer(s.id) })
                        }
                    }
                }
            }
            val urlTest: @Composable () -> Unit = {
                HomeMonitorSection(
                    connected = connected,
                    socksPort = net.antibs.client.data.LocalProxy.port(st.adv),
                    showUrl = st.adv.homeUrlTest,
                    haptics = st.adv.haptics,
                )
            }
            val homeTop: @Composable ColumnScope.() -> Unit = {
                // 2.0.8: health → red power button + red banner status
                val vpnPingMs by vm.vpnPingMs.collectAsState()
                val vpnBusy by vm.vpnBusy.collectAsState()
                val directMs by vm.directMs.collectAsState()
                val directBusy by vm.directBusy.collectAsState()
                val vpnFails by vm.vpnFails.collectAsState()
                val directFails by vm.directFails.collectAsState()
                val netOk by vm.netValidated.collectAsState()
                val health = net.antibs.client.engine.NetMonitor.health(
                    active = connected || vpn.state == ConnState.CONNECTING, connected = connected,
                    vpnFails = vpnFails, directFails = directFails, netValidated = netOk)
                val healthText = net.antibs.client.engine.NetMonitor.healthText(health)
                run {
                    // 1.0.31: the host line always stays; only the speeds/graphs section shows as soon as the VPN
                    // connects (or is connecting) and hides 5 s after it goes off (reconnect within 5 s cancels).
                    ConnectionCard(
                        flag = run {
                            val live = (connected || vpn.state == ConnState.CONNECTING) && vpn.serverName.isNotEmpty()
                            if (live && connected && vpn.pickName.contains('→')) UiLogic.exitFlag(vpn.pickName)
                            else UiLogic.flag(if (live) UiLogic.liveHostName(vpn.serverName, vpn.pickName, connected) else server?.name ?: "")
                        },
                        name = run {
                            val live = (connected || vpn.state == ConnState.CONNECTING) && vpn.serverName.isNotEmpty()
                            if (live) UiLogic.liveHostTitle(vpn.serverName, vpn.pickName, connected)
                            else server?.name?.let { UiLogic.displayName(it) } ?: "Сервер не выбран"
                        },
                        proto = UiLogic.activeProtocolLabel(vpn.state, if (connected) vpn.pickProtocol.ifEmpty { vpn.protocol } else vpn.protocol, server),
                        protoTone = UiLogic.protoChipTone(vpn.state),
                        active = connected,
                        downRate = vpn.downSpeed,
                        upRate = vpn.upSpeed,
                        dot = UiLogic.statusDot(vpn.state, tunnelPing),
                        downSpeed = Format.speedFixed(vpn.downSpeed, st.adv.speedUnits == "bits"),
                        upSpeed = Format.speedFixed(vpn.upSpeed, st.adv.speedUnits == "bits"),
                        downTotal = Format.bytesFixed(vpn.downBytes),
                        upTotal = Format.bytesFixed(vpn.upBytes),
                        vpnSamples = vpnSamples,
                        upSamples = upSamples,
                        sampleSeq = sampleSeq,
                        sampleTimes = sampleTimes,
                        onLongPress = onOpenSpeedTest,
                        statsVisible = speedCardVisible,
                        speedTemplate = Format.speedTemplate(st.adv.speedUnits == "bits"),
                        pingMs = vpnPingMs,
                        pingBusy = vpnBusy,
                        directMs = directMs,
                        directBusy = directBusy,
                        onPingDirect = { vm.pingDirect() },
                        haptics = st.adv.haptics,
                        status = healthText ?: when (vpn.state) {
                            ConnState.CONNECTED -> "Подключено"
                            ConnState.CONNECTING -> "Подключение…"
                            ConnState.DISCONNECTING -> "Отключение…"
                            ConnState.ERROR -> "Ошибка подключения"
                            else -> "Не подключено"
                        },
                        statusColor = when {
                            healthText != null || vpn.state == ConnState.ERROR -> AntiBSColors.Err
                            vpn.state == ConnState.CONNECTED -> AntiBSColors.Ok
                            else -> Color.Unspecified
                        },
                        pingStyle = st.adv.pingChip,
                        onPing = { vm.pingCurrent() },
                        subLine = run {
                            val liveId = vm.currentPingId()?.let { id -> st.servers.firstOrNull { it.id == id } }?.subscriptionId
                            val sid = liveId ?: server?.subscriptionId
                            val sub = st.subscriptions.firstOrNull { it.id == sid }
                            val base = net.antibs.client.data.SubLineData.of(sub, System.currentTimeMillis())
                            val accumulating = (vpn.state == ConnState.CONNECTED || vpn.state == ConnState.CONNECTING) &&
                                liveId != null && liveId == sub?.id
                            val sessionBytes = vpn.upBytes + vpn.downBytes
                            val snap = net.antibs.client.data.SubTrafficLiveStore.get(androidx.compose.ui.platform.LocalContext.current)
                                .tick(sub, sessionBytes, accumulating)
                            net.antibs.client.data.SubTrafficLive.apply(base, snap)
                        },
                        subStyle = st.adv.subRemain,
                    )
                }
                // 2.0.33: banner → URL test (if enabled) → power button → status / error
                urlTest()
                if (!isTv && !st.adv.homeUrlTest) Spacer(Modifier.height(BANNER_TRIM))
                PowerToggle(
                    state = vpn.state,
                    since = vpn.since,
                    problem = UiLogic.powerShowError(vpn.state, health, tunnelPing),
                    onClick = ::toggle,
                    haptics = st.adv.haptics,
                    prefs = st.adv.powerButton,
                )
                val engineDetail by net.antibs.client.engine.VpnState.detail.collectAsState()
                if (vpn.state != ConnState.ERROR && vpn.state != ConnState.DISCONNECTED) engineDetail?.let { d ->
                    Text(
                        d,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                // 2.0.4: keep the failure text on screen for ≥10 s (it used to vanish when the state went back to
                // DISCONNECTED); tap copies the error, long-press copies the error + recent log.
                val errCtx = androidx.compose.ui.platform.LocalContext.current
                var shownErr by remember { mutableStateOf<String?>(null) }
                var lastDetail by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(engineDetail) { if (engineDetail != null) lastDetail = engineDetail }
                LaunchedEffect(vpn.state) { if (vpn.state == ConnState.CONNECTING) lastDetail = null }
                LaunchedEffect(vpn.state, vpn.message) {
                    when (vpn.state) {
                        ConnState.ERROR -> shownErr = vpn.message.ifEmpty { lastDetail ?: "Ошибка" }
                        ConnState.CONNECTING, ConnState.CONNECTED -> shownErr = null
                        // failed during connect and went straight back to «off»: keep what the engine last said
                        ConnState.DISCONNECTED -> if (shownErr == null && vpn.message.isNotEmpty()) shownErr = vpn.message
                            else if (shownErr == null && (lastDetail?.contains("ошибка", true) == true || lastDetail?.contains(" — ") == true)) shownErr = lastDetail
                        else -> {}
                    }
                }
                LaunchedEffect(shownErr) {
                    if (shownErr != null) {
                        kotlinx.coroutines.delay(10_000)
                        while (vpn.state == ConnState.ERROR) kotlinx.coroutines.delay(1_000)
                        kotlinx.coroutines.delay(5_000)
                        shownErr = null
                    }
                }
                val errText = if (vpn.state == ConnState.ERROR) vpn.message.ifEmpty { shownErr ?: "Ошибка" } else shownErr
                if (errText != null) {
                    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                    Text(
                        errText + "  ⧉",
                        color = AntiBSColors.Err,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 6.dp).combinedClickable(
                            onClick = { copyText(errCtx, errText); android.widget.Toast.makeText(errCtx, "Ошибка скопирована", android.widget.Toast.LENGTH_SHORT).show() },
                            onLongClick = {
                                val log = net.antibs.client.engine.VpnState.logs.value.takeLast(300).joinToString("\n")
                                copyText(errCtx, errText + "\n\n" + log); android.widget.Toast.makeText(errCtx, "Ошибка и лог скопированы", android.widget.Toast.LENGTH_SHORT).show()
                            },
                        ),
                    )
                }
                if (captcha != null) {
                    run { CaptchaCard(captcha!!) }
                }
            }
            val listHeader: @Composable () -> Unit = {
                val ids = st.subscriptions.map { it.id }
                val curSub = if (effLayout == SubLayout.RIBBON) ServerStyleLogic.validRibbon(sRibbon[sStyle] ?: "", ids).let { id -> st.subscriptions.firstOrNull { it.id == id } } else null
                ServersHeader(
                    style = sStyle, layout = opts.layout, bestCard = opts.bestCard,
                    onStyle = { sp.setStyle(it) }, onLayout = { sp.setLayout(sStyle, it) }, onBestCard = { sp.setBestCard(sStyle, it) },
                    sortMode = sSort, onSortMode = { sp.setSort(it) }, subNewestFirst = sSubNew, onSubOrder = { sp.setSubNewestFirst(it) },
                    filter = sFilter[sStyle] ?: ServerFilter.ALL, onFilter = { sp.setFilter(sStyle, it) },
                    testing = pinging.isNotEmpty(), onTestAll = { vm.pingAll() },
                    onAdd = { showAdd = true },
                    subMenu = curSub,
                    onSubRefresh = { vm.refresh(it.id) }, onSubPing = { vm.pingSubscription(it.id) },
                    onSubCopy = { copyText(ctx, it.url); vm.message("Ссылка скопирована") },
                    onSubDeleteUnreachable = { vm.askDeleteUnreachable(it.id) }, onSubRestoreHidden = { vm.restoreHiddenHosts(it.id) },
                    onSubAutoDelete = { sub, on -> vm.setSubscriptionAutoDelete(sub.id, on) },
                    subExtraMenu = { sb, close -> GroupingMenuItems(vm, st.adv.serverGrouping, sb.id, close) },
                    onSubDelete = { vm.removeSubscription(it.id) },
                    compact = true,
                    holiday = net.antibs.client.data.HolidayStyle.stripHoliday(st.adv.seasonalDecor, st.adv.seasonalFx.showLabel, st.adv.customHolidays),
                    holidayFx = st.adv.seasonalFx,
                )
                if (filterTabs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HomeFiltersBar(
                        tabs = filterTabs,
                        selectedTab = activeTab,
                        onTab = { homeTab = HomeFilters.toggleTab(homeTab, it) },
                        countries = filterCountries,
                        selectedCountry = activeCountry,
                        allCount = filterCountries.size,
                        serverCount = allVisible.size,
                        onCountry = { homeCountry = HomeFilters.toggleCountry(homeCountry, it) },
                        haptics = st.adv.haptics,
                        onPingChip = { tab, country ->
                            vm.pingFiltered(HomeFilters.chipTargets(allVisible, tab, country))
                        },
                        onDeleteChip = { tab, country ->
                            vm.askDeleteChip(
                                HomeFilters.chipTargets(allVisible, tab, country),
                                HomeFilters.chipLabel(tab, country, filterCountries),
                            )
                        },
                    )
                }
            }
            // Pull-to-refresh = refresh all subscriptions + ping hosts.
            PullToRefreshBox(
                isRefreshing = refreshing.isNotEmpty(),
                onRefresh = { vm.refreshAll() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // 2.2.1: only the subscriptions/servers list scrolls. The connection banner, URL test
                // (it sits between banner and power), power button, status/error, holiday strip and
                // filter chips stay pinned. On a short screen the pinned block can scroll internally
                // so the list still has room. Seasonal strip stays pinned because it sits between
                // the power button and the chips.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewportH = maxHeight
                    val tight = viewportH < 560.dp
                    val listMin = 160.dp
                    val maxPinned = (viewportH - listMin).coerceAtLeast(viewportH * 0.5f)
                    if (!isTv) {
                        Column(Modifier.fillMaxSize()) {
                            val headerMod = if (tight) Modifier.fillMaxWidth().heightIn(max = maxPinned).verticalScroll(rememberScrollState())
                            else Modifier.fillMaxWidth()
                            Column(
                                headerMod.padding(start = 12.dp, end = 12.dp, top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                homeTop()
                                listHeader()
                            }
                            LazyColumn(
                                state = homeList,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 3.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                serverItems()
                            }
                        }
                    } else {
                        // TV: left = banner + URL test + power (already its own column), right = pinned chips + scrolling list
                        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(0.44f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Spacer(Modifier.height(6.dp))
                                homeTop()
                            }
                            Column(Modifier.weight(0.56f).fillMaxHeight()) {
                                val headMod = if (tight) Modifier.fillMaxWidth().heightIn(max = viewportH * 0.45f).verticalScroll(rememberScrollState())
                                else Modifier.fillMaxWidth()
                                Column(headMod, verticalArrangement = Arrangement.spacedBy(3.dp)) { listHeader() }
                                LazyColumn(
                                    state = homeList,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentPadding = PaddingValues(top = 3.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    serverItems()
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        }
        if (!isTv) MainBottomBar(tab, onSelect = { tabName = it.name })
    }
    }
    if (showAdd) AddServersSheet(vm = vm, servers = st.servers, onDismiss = { showAdd = false })
    if (showStyle) StyleViewDialog(onDismiss = { showStyle = false })
}

/** 1.0.42: bottom tabs, left → right. 2.0.32: «Серверы» removed (the list lives on «Главная»), + speed / «Сеть». 2.0.33: icons only. 2.0.41: the speed tab is «Сессия». */
internal enum class MainTab(val title: String, val key: String) {
    Shop("Магазин", "tab_shop"), Home("Главная", "tab_home"), Speed("Сессия", "tab_speed"), Net("Сеть", "tab_net");
    val label: String get() = I18n.t(key)
}

@Composable
internal fun MainBottomBar(tab: MainTab, onSelect: (MainTab) -> Unit) {
    // 2.0.33: icons only (tab name = contentDescription / onClickLabel). Height stays ~62dp as in 2.0.30; selected tab sits on the accent pill.
    val pill = if (LocalAppStyle.current == ServerStyle.CLASSIC) AntiBSColors.BlueDeep else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { t ->
                val sel = t == tab
                val scale by androidx.compose.animation.core.animateFloatAsState(
                    if (sel) 1.06f else 1f,
                    androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 450f),
                    label = "tab-scale",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = t.label) { if (!sel) onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 60.dp, height = 32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) pill else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when (t) {
                                MainTab.Shop -> if (sel) Icons.Filled.Storefront else Icons.Outlined.Storefront
                                MainTab.Home -> if (sel) Icons.Filled.Home else Icons.Outlined.Home
                                MainTab.Speed -> if (sel) Icons.Filled.Public else Icons.Outlined.Public
                                MainTab.Net -> Icons.Filled.CellTower
                            },
                            contentDescription = t.label,
                            tint = if (sel) Color.White else muted,
                            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun TopBar(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onSpeedTest: () -> Unit,
    onStyle: () -> Unit = {},
    onNet: () -> Unit = {},
) {
    // 1.0.32: brand on the left (icon + AntiBS2client); ⚙ / + / speedtest stay on the right.
    // Refresh: pull-to-refresh, ↻ / ⋮ «Обновить» on each subscription card, and the auto-update interval.
    val tint = MaterialTheme.colorScheme.onBackground
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = net.antibs.client.R.drawable.brand_icon),
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            "AntiBS2client",
            style = TextStyle(
                brush = AntiBSColors.fabBrush,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.4.sp,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        // 1.0.47: theme toggle in the top bar: Светлая → Тёмная → AMOLED → Светлая (Как в системе → the opposite)
        val effectiveDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        IconButton(modifier = Modifier.size(34.dp), onClick = {
            onTheme(when (theme) {
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.AMOLED
                ThemeMode.AMOLED -> ThemeMode.LIGHT
                else -> if (effectiveDark) ThemeMode.LIGHT else ThemeMode.DARK
            })
        }) {
            Icon(
                when (theme) {
                    ThemeMode.LIGHT -> Icons.Filled.LightMode
                    ThemeMode.AMOLED -> Icons.Filled.Contrast
                    ThemeMode.DARK -> Icons.Filled.DarkMode
                    else -> if (effectiveDark) Icons.Filled.DarkMode else Icons.Filled.LightMode
                },
                contentDescription = "Сменить тему", tint = tint, modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onStyle, modifier = Modifier.size(34.dp)) { Icon(PaletteIcon, "Стиль оформления", tint = tint, modifier = Modifier.size(20.dp)) }
        IconButton(onClick = onSettings, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Settings, "Настройки", tint = tint, modifier = Modifier.size(20.dp)) }
        IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, "Добавить серверы", tint = tint, modifier = Modifier.size(25.dp)) }
    }
}

@Composable
internal fun ConnectionCard(
    flag: String,
    name: String,
    proto: String,
    protoTone: UiLogic.ProtoChipTone,
    active: Boolean,
    dot: UiLogic.StatusDot,
    downSpeed: String,
    speedTemplate: String = Format.SPEED_TEMPLATE,
    upSpeed: String,
    downTotal: String,
    upTotal: String,
    // 2.0.32: direct (non-VPN) speed / totals / graph removed from the banner; kept only for source compatibility.
    @Suppress("UNUSED_PARAMETER") directDownSpeed: String = "",
    @Suppress("UNUSED_PARAMETER") directUpSpeed: String = "",
    @Suppress("UNUSED_PARAMETER") directDownTotal: String = "",
    @Suppress("UNUSED_PARAMETER") directUpTotal: String = "",
    /** ↓ incoming VPN traffic samples (blue). */
    vpnSamples: List<Float>,
    @Suppress("UNUSED_PARAMETER") directSamples: List<Float> = emptyList(),
    sampleSeq: Int,
    /** 2.0.32: ↑ outgoing VPN traffic samples (red). */
    upSamples: List<Float> = emptyList(),
    sampleTimes: List<Long> = emptyList(),
    downRate: Long = 0,
    upRate: Long = 0,
    @Suppress("UNUSED_PARAMETER") directDownRate: Long = 0,
    @Suppress("UNUSED_PARAMETER") directUpRate: Long = 0,
    onLongPress: () -> Unit = {},
    statsVisible: Boolean = true,
    pingMs: Long? = null,
    pingBusy: Boolean = false,
    pingStyle: net.antibs.client.data.PingChipStyle? = null,
    onPing: (() -> Unit)? = null,
    subLine: net.antibs.client.data.SubLineData? = null,
    subStyle: net.antibs.client.data.SubRemainStyle = net.antibs.client.data.SubRemainStyle(),
    status: String = "",
    statusColor: Color = Color.Unspecified,
    directMs: Long? = null,
    directBusy: Boolean = false,
    onPingDirect: (() -> Unit)? = null,
    haptics: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) { // 2.0.11: tighter (power-button gap)
            // 2.0.8: left = dot · flag · name, status under the name; right = small protocol badge, VPN / direct ping
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(dot)
                        Spacer(Modifier.width(8.dp))
                        Text(flag, fontSize = 16.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (status.isNotEmpty()) Text(status, color = if (statusColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else statusColor,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.offset(y = (-3).dp)) {
                    if (proto.isNotEmpty()) {
                        val chipBg = when (protoTone) {
                            UiLogic.ProtoChipTone.OK -> AntiBSColors.ChipOk
                            UiLogic.ProtoChipTone.ERR -> AntiBSColors.Err
                            UiLogic.ProtoChipTone.OFF -> AntiBSColors.ChipOff
                        }
                        Text(
                            proto,
                            color = Color.White,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(chipBg).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    if (pingStyle == null || pingStyle.show) {
                        val view = androidx.compose.ui.platform.LocalView.current
                        Spacer(Modifier.height(3.dp))
                        BannerPing(I18n.t("vpn_ping"), VpnPingLabel, if (active) pingMs else null, pingBusy && active, active, pingStyle) {
                            AppHaptics.perform(view, AppHaptics.Kind.TICK, haptics); onPing?.invoke()
                        }
                        Spacer(Modifier.height(2.dp))
                        BannerPing(I18n.t("vk_ping"), VkPingLabel, directMs, directBusy, true, pingStyle) {
                            AppHaptics.perform(view, AppHaptics.Kind.TICK, haptics); onPingDirect?.invoke()
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = statsVisible,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)) +
                    androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(320), expandFrom = Alignment.Top) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(320)) { -it / 3 },
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240)) +
                    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(320), shrinkTowards = Alignment.Top) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(320)) { -it / 3 },
            ) {
            Column {
            Spacer(Modifier.height(3.dp))
            // 1.0.13: static layout. Every value has a fixed-width, end-aligned slot sized for the widest
            // possible string ("999.9 МБ/с" / "999.9 МБ", measured with the real text style so font scaling is
            // respected), tabular digits, one decimal always — nothing shifts as numbers change. Blue (VPN) and
            // red (direct) groups sit side by side; the sparkline fills the remaining width (≤ 110dp), pinned right.
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val speedW = remember(density, speedTemplate) {
                with(density) { measurer.measure(speedTemplate, SpeedStyle).size.width.toDp() + 1.dp }
            }
            val totalW = remember(density) {
                with(density) { measurer.measure(Format.TOTAL_TEMPLATE, TotalStyle).size.width.toDp() + 2.dp }
            }
            // 1.0.16: each column has its own alive arrows — VPN arrows follow VPN speed only, the arrows in front
            // of the red column follow direct speed only. The VPN total starts right after the VPN speed slot
            // (start-aligned, 2dp gap). The graph is exactly as tall as the two rows (14 + 2 + 14 dp) and top-aligned,
            // so its top is level with the ↓ row and its bottom with the ↑ row.
            // 2.0.32: VPN only — ↓ incoming blue, ↑ outgoing red; the graph stretches from the totals to the card edge.
            // 2.0.35: start = 16.dp matches «Подключено» (StatusDot 8.dp + Spacer 8.dp) so ↓/↑ sit under «П».
            // 2.0.44: the arrow glyph is centred in a 10 dp slot and «П» has its own side bearing, so 16 dp left the
            // arrows ~4 dp to the right. Now the arrow's stem is placed exactly under the left leg of «П», measured
            // from the real glyphs (font, weight and size of both texts) — works for any system font / scale.
            val statusStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium))
            val legX = rememberStemX(status.take(1).ifEmpty { "П" }, statusStyle, longest = false)
            val arrowStemX = rememberStemX("↓", ArrowStyle, longest = true)
            val arrowGlyphW = remember(density) { measurer.measure("↓", ArrowStyle).size.width.toFloat() }
            val arrowStart = with(density) {
                val slot = ArrowSlotW.toPx()
                val inSlot = (slot - arrowGlyphW) / 2f + (arrowStemX ?: arrowGlyphW / 2f)
                val leg = legX ?: (inSlot)
                (16.dp.toPx() + leg - inSlot).coerceAtLeast(0f).toDp()
            }
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = arrowStart)) {
                Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                    AliveArrow(down = true, color = VpnDownColor, rate = downRate, connected = active)
                    AliveArrow(down = false, color = VpnUpColor, rate = upRate, connected = active)
                }
                Spacer(Modifier.width(3.dp))
                Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                    RateCell(VpnDownColor, downSpeed, downTotal, speedW, totalW, speedAlign = TextAlign.Start, totalAlign = TextAlign.Start, gap = 2.dp)
                    RateCell(VpnUpColor, upSpeed, upTotal, speedW, totalW, speedAlign = TextAlign.Start, totalAlign = TextAlign.Start, gap = 2.dp)
                }
                Spacer(Modifier.width(6.dp))
                Sparkline(vpnSamples, upSamples, sampleTimes, active, Modifier.weight(1f).height(RowH * 2 + RowGap).offset(y = (-1.5).dp))
            }
            }
            }
            // 2.0.6 (ABS): «Остаток подписки» — the last row inside the card
            if (subLine != null && subStyle.show) SubRemainRow(subLine, subStyle, Modifier.padding(top = 4.dp))
        }
    }
}

/** 2.0.8: «VPN пинг: N мс» / «VK пинг: N мс» — 2.0.12: label colour fixed (VPN red, VK blue), value by [PingLevel]; tap = ping now. */
@Composable
private fun BannerPing(label: String, labelColor: Color, ms: Long?, busy: Boolean, active: Boolean, style: net.antibs.client.data.PingChipStyle?, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val neutral = cs.onSurfaceVariant
    // 2.0.14/2.0.15: fixed-width label + fixed «9999 мс» value slot → «…» while measuring doesn't move anything.
    val shown = if (active && !busy) ms else null
    val tone = if (shown == null) neutral else pingLevelColor(shown, neutral)
    val fg = if (style != null && style.text != 0L) Color(style.text.toInt()) else tone
    val v = if (busy) "…" else bannerPingText(shown)
    val ts = TabularNums.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.clip(RoundedCornerShape(5.dp)).background(labelColor.copy(alpha = 0.14f))
            .clickable(onClickLabel = "Проверить: $label", onClick = onTap).padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.CenterStart) {
            Text(I18n.t("vpn_ping") + ": ", style = ts, maxLines = 1, modifier = Modifier.alpha(0f))
            Text("$label: ", style = ts, color = labelColor, maxLines = 1)
        }
        // 2.0.15: the value slot is exactly «9999 мс» wide — the pill hugs «vpn пинг: 9999 мс», both pills match;
        // «тайм-аут» (wider) is drawn slightly smaller so it still fits without growing the pill.
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val slotPx = remember(density) { measurer.measure("9999 мс", ts).size.width }
        val vPx = remember(v, density) { measurer.measure(v, ts).size.width }
        val vs = if (vPx > slotPx && vPx > 0) ts.copy(fontSize = ts.fontSize * (slotPx.toFloat() / vPx) * 0.98f) else ts
        Box(Modifier.width(with(density) { slotPx.toDp() + 0.5.dp }), contentAlignment = Alignment.CenterEnd) {
            Text(v, style = vs, color = fg, maxLines = 1, softWrap = false)
        }
    }
}

/** 2.0.14: banner ping value text («—», «тайм-аут», «N мс», capped at 9999). */
internal fun bannerPingText(ms: Long?): String = when { ms == null -> "—"; ms < 0 -> "тайм-аут"; else -> "${ms.coerceAtMost(9999)} мс" }

/** 2.0.12: label colours of the banner pings (readable on dark and light). */
internal val VpnPingLabel = Color(0xFF3B82F6) // 2.0.20: blue like «vk пинг»
internal val VkPingLabel = Color(0xFF3B82F6)

/** 2.0.32: banner VPN traffic colours — ↓ incoming blue, ↑ outgoing red (text, arrows and graph). */
internal val VpnDownColor = AntiBSColors.VpnGraph
internal val VpnUpColor = AntiBSColors.DirectGraph

/** Speed-card row metrics: two 14dp rows with a 2dp gap; the sparkline spans both. */
private val RowH = 14.dp
private val RowGap = 2.dp

/**
 * 1.0.14 header status dot (1.0.19: while connected its colour follows the tunnel ping — CHECKING amber,
 * LIVE green, NO_PING red, all three "live"). LIVE: green, gentle breathing (scale + alpha) and a soft ripple ring expanding
 * out of it; CONNECTING: amber blink; ERROR: red; OFF: static gray. One 8dp Canvas; the infinite transition
 * exists only in animated states and every animated value is read in the draw phase (no recomposition per frame).
 */
@Composable
private fun StatusDot(dot: UiLogic.StatusDot) {
    val gray = MaterialTheme.colorScheme.outline
    val color = when (dot) {
        UiLogic.StatusDot.LIVE -> AntiBSColors.StatusGreen
        UiLogic.StatusDot.CONNECTING, UiLogic.StatusDot.CHECKING -> AntiBSColors.Warn
        UiLogic.StatusDot.ERROR, UiLogic.StatusDot.NO_PING -> AntiBSColors.Err
        UiLogic.StatusDot.OFF -> gray
    }
    val desc = when (dot) {
        UiLogic.StatusDot.LIVE -> "Подключено, пинг есть"
        UiLogic.StatusDot.CHECKING -> "Подключено, проверка пинга"
        UiLogic.StatusDot.NO_PING -> "Подключено, пинга нет"
        UiLogic.StatusDot.CONNECTING -> "Подключение"
        UiLogic.StatusDot.ERROR -> "Ошибка"
        UiLogic.StatusDot.OFF -> "Отключено"
    }
    val breath: State<Float>
    val ripple: State<Float>
    val blink: State<Float>
    when (dot) {
        // 1.0.19: the same live breathing + ripple in green (ping OK), amber (checking) or red (no ping).
        UiLogic.StatusDot.LIVE, UiLogic.StatusDot.CHECKING, UiLogic.StatusDot.NO_PING -> {
            val t = rememberInfiniteTransition(label = "dotLive")
            breath = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
            ripple = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "ripple")
            blink = remember { mutableFloatStateOf(1f) }
        }
        UiLogic.StatusDot.CONNECTING -> {
            val t = rememberInfiniteTransition(label = "dotBusy")
            blink = t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "blink")
            breath = remember { mutableFloatStateOf(0f) }
            ripple = remember { mutableFloatStateOf(-1f) }
        }
        else -> {
            breath = remember { mutableFloatStateOf(0f) }
            ripple = remember { mutableFloatStateOf(-1f) }
            blink = remember { mutableFloatStateOf(1f) }
        }
    }
    Canvas(Modifier.size(8.dp).semantics { contentDescription = desc }) {
        val c = center
        val r = size.minDimension / 2f
        val rp = ripple.value
        if (rp >= 0f) {
            // Ring grows from the dot to ~2.3× its radius while fading out.
            val ease = 1f - (1f - rp) * (1f - rp)
            drawCircle(color.copy(alpha = 0.45f * (1f - rp)), radius = r * (1f + 1.3f * ease), center = c, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(color.copy(alpha = 0.12f * (1f - rp)), radius = r * (1f + 1.3f * ease), center = c)
        }
        val b = breath.value
        val scale = 0.86f + 0.14f * b
        drawCircle(color.copy(alpha = (0.78f + 0.22f * b) * blink.value), radius = r * scale, center = c)
    }
}

private val TabularNums = TextStyle(fontFeatureSettings = "tnum", fontSize = 12.sp, lineHeight = 15.sp)

/**
 * 1.0.15 direction arrow. Idle (no traffic / disconnected): static and dimmed. With traffic: the glyph flows in
 * its direction (↓ down, ↑ up) as a seamless conveyor of two copies that fade in/out at the edges, plus a faint
 * trailing ghost; the flow rate scales with the current speed ([UiLogic.arrowPeriodMs]). The phase advances in a
 * frame loop that runs only while active, and it's read only in the draw phase.
 */
@Composable
private fun AliveArrow(down: Boolean, color: Color, rate: Long, connected: Boolean) {
    val active = UiLogic.arrowActive(rate, connected)
    val period by rememberUpdatedState(UiLogic.arrowPeriodMs(rate))
    val phase = remember { mutableFloatStateOf(0f) }
    val level by animateFloatAsState(if (active) 1f else 0f, animationSpec = tween(350), label = "arrowLevel")
    LaunchedEffect(active) {
        if (!active) { phase.floatValue = 0f; return@LaunchedEffect }
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000f
                last = now
                phase.floatValue = (phase.floatValue + dt / period.coerceAtLeast(1)) % 1f
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val glyph = remember(down) { measurer.measure(if (down) "↓" else "↑", ArrowStyle) }
    Canvas(Modifier.size(width = ArrowSlotW, height = RowH).semantics { contentDescription = if (down) "Загрузка" else "Отдача" }) {
        val gw = glyph.size.width.toFloat()
        val gh = glyph.size.height.toFloat()
        val x = (size.width - gw) / 2f
        val baseY = (size.height - gh) / 2f
        val lv = level
        if (lv <= 0.001f) {
            drawText(glyph, color = color, topLeft = Offset(x, baseY), alpha = 0.45f)
            return@Canvas
        }
        val dir = if (down) 1f else -1f
        val h = size.height
        val p = phase.floatValue
        clipRect {
            for (k in 0..1) {
                val dy = (p - k) * h * 0.9f * lv
                val a = (1f - kotlin.math.abs(dy) / (h * 0.9f)).coerceIn(0f, 1f)
                val y = baseY + dir * dy
                // faint trail just behind the moving glyph
                drawText(glyph, color = color, topLeft = Offset(x, y - dir * 3.dp.toPx()), alpha = 0.22f * a * lv)
                drawText(glyph, color = color, topLeft = Offset(x, y), alpha = (0.45f + 0.55f * lv) * a + 0.45f * (1f - lv) * (if (k == 0) 1f else 0f))
            }
        }
    }
}

private val ArrowStyle = TextStyle(fontSize = 13.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
private val ArrowSlotW = 10.dp

/**
 * 2.0.44: x (px from the text origin) of a vertical stem of [text]'s first glyph drawn with [style]: the first stem
 * (left leg of «П») or, with [longest], the tallest one (the shaft of «↓»). Rendered once into a small bitmap with
 * the same typeface the Text uses; null if the font can't be resolved.
 */
@Composable
private fun rememberStemX(text: String, style: TextStyle, longest: Boolean): Float? {
    val density = LocalDensity.current
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    return remember(text, style, density, longest) {
        try {
            val tf = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal).value as? android.graphics.Typeface
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf ?: android.graphics.Typeface.DEFAULT
                textSize = with(density) { style.fontSize.toPx() }
            }
            val pad = 4
            val w = kotlin.math.ceil(paint.measureText(text)).toInt() + pad * 2
            val h = kotlin.math.ceil(paint.textSize * 2f).toInt()
            if (w <= pad * 2 || h <= 0) return@remember null
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
            android.graphics.Canvas(bmp).drawText(text, pad.toFloat(), paint.textSize * 1.4f, paint)
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            bmp.recycle()
            val cols = IntArray(w) { x -> (0 until h).count { y -> (px[y * w + x] ushr 24) >= 128 } }
            UiLogic.stemCenter(cols, longest)?.minus(pad)
        } catch (_: Throwable) { null }
    }
}

@Composable
private fun ArrowLabel(arrow: String, color: Color) {
    Text(arrow, color = color, style = TabularNums.copy(fontSize = 13.sp, lineHeight = 14.sp), fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
}

private val SpeedStyle = TextStyle(fontFeatureSettings = "tnum", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
private val TotalStyle = TextStyle(fontFeatureSettings = "tnum", fontSize = 10.sp, lineHeight = 14.sp)

/** Speed + session total, each in a fixed-width end-aligned slot (no layout shift). */
@Composable
private fun RateCell(
    color: Color, speed: String, total: String, speedW: Dp, totalW: Dp,
    speedAlign: TextAlign = TextAlign.End, totalAlign: TextAlign = TextAlign.End, gap: Dp = 3.dp,
) {
    // 2.0.35: glyphs sit on the row bottom so the sparkline baseline meets the ↑ counter line.
    Row(Modifier.height(RowH), verticalAlignment = Alignment.Bottom) {
        Text(speed, style = SpeedStyle, color = color, textAlign = speedAlign, maxLines = 1, softWrap = false, modifier = Modifier.width(speedW))
        Spacer(Modifier.width(gap))
        Text(total, style = TotalStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = totalAlign, maxLines = 1, softWrap = false, modifier = Modifier.width(totalW))
    }
}

@Composable
private fun Sparkline(down: List<Float>, up: List<Float>, times: List<Long>, active: Boolean, modifier: Modifier) {
    val downColor = VpnDownColor
    val upColor = VpnUpColor
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    // 1.0.24: frame clock — the graph scrolls continuously at a constant speed (one step per sample interval)
    // and the live head slides between the previous and the newest value. Runs only while connected and the
    // screen is composed/visible (no frames are delivered in the background); off → static picture.
    val clock = remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) withFrameNanos { clock.longValue = android.os.SystemClock.uptimeMillis() }
    }
    val scaleTarget = UiLogic.sparkScale(down.takeLast(UiLogic.SPARK_HISTORY + 2), up.takeLast(UiLogic.SPARK_HISTORY + 2))
    val scale = animateFloatAsState(scaleTarget, animationSpec = tween(900, easing = FastOutSlowInEasing), label = "sparkScale")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val max = scale.value.coerceAtLeast(1f)
        val now = if (active) clock.longValue else (times.lastOrNull()?.plus(UiLogic.SPARK_INTERVAL_MS) ?: 0L)
        val stroke = 2.dp.toPx()
        val dotR = 2.6.dp.toPx()
        val top = stroke + 1f
        val usable = h - top - 1.5f
        // 2.0.35: slightly clearer baseline at the canvas bottom (= ↑ text bottom).
        drawLine(grid.copy(alpha = 0.28f), Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1.25f)
        drawLine(grid, Offset(0f, top + usable / 2f), Offset(w, top + usable / 2f), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        val headX = w - dotR * 2.2f // live end, inset so the dot and its glow stay inside
        val step = headX / UiLogic.SPARK_HISTORY
        fun drawSeries(samples: List<Float>, color: Color) {
            val n = minOf(samples.size, times.size)
            if (n < 2) return
            val vals = samples.takeLast(n)
            val ts = times.takeLast(n)
            val ys = vals.map { v -> h - 1.5f - (v / max).coerceIn(0f, 1.05f) * usable }
            val xs = FloatArray(n) { i -> UiLogic.sparkX(now, ts[i], headX, step) }
            val m = UiLogic.monotoneTangents(ys)
            // The newest sample is still "arriving": the head sits on the last segment at fraction f.
            val f = UiLogic.headFraction(now, ts[n - 1])
            val line = Path()
            line.moveTo(xs[0], ys[0])
            var hx = headX
            var hy = ys[n - 1]
            for (i in 0 until n - 1) {
                val dx = (xs[i + 1] - xs[i]) / 3f
                val c1 = Offset(xs[i] + dx, ys[i] + m[i] / 3f)
                val c2 = Offset(xs[i + 1] - dx, ys[i + 1] - m[i + 1] / 3f)
                if (i == n - 2 && f < 1f) {
                    val seg = UiLogic.bezierPrefix(floatArrayOf(xs[i], ys[i], c1.x, c1.y, c2.x, c2.y, xs[i + 1], ys[i + 1]), f)
                    line.cubicTo(seg[2], seg[3], seg[4], seg[5], seg[6], seg[7])
                    hx = seg[6]; hy = seg[7]
                } else {
                    line.cubicTo(c1.x, c1.y, c2.x, c2.y, xs[i + 1], ys[i + 1])
                    hx = xs[i + 1]; hy = ys[i + 1]
                }
            }
            // Late sample (tick delayed): keep the line flat up to the live end instead of leaving a gap.
            if (hx < headX - 0.5f) { line.lineTo(headX, hy); hx = headX }
            val fill = Path().apply {
                addPath(line)
                lineTo(hx, h)
                lineTo(xs[0], h)
                close()
            }
            drawPath(fill, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)), startY = top, endY = h))
            drawPath(line, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Head dot at the live end with a soft pulsing glow (pulse only while connected).
            val pulse = if (active) UiLogic.headPulse(now) else 0f
            drawCircle(color.copy(alpha = 0.18f + 0.17f * pulse), dotR * (1.7f + 0.6f * pulse), Offset(hx, hy))
            drawCircle(color, dotR, Offset(hx, hy))
            drawCircle(Color.White.copy(alpha = 0.85f), dotR * 0.38f, Offset(hx, hy))
        }
        clipRect(left = 0f, top = 0f, right = w, bottom = h) {
            drawSeries(down, downColor)
            drawSeries(up, upColor)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = AntiBSColors.Section, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("  $count", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, if (expanded) "Свернуть" else "Развернуть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 1.0.20: ping all hosts of the subscription; spins and is disabled while they're being measured. */
@Composable
internal fun SubPingIcon(pinging: Boolean, onPing: () -> Unit) {
    val angle = if (pinging) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "subPing")
        t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)), label = "subPingRot").value
    } else 0f
    IconButton(onClick = onPing, enabled = !pinging, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.NetworkCheck, if (pinging) "Пинг идёт…" else "Проверить пинг",
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle })
    }
}

@Composable
internal fun SubCard(
    sub: Subscription,
    realServers: Int,
    refreshing: Boolean,
    pinging: Boolean,
    nowSec: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRestoreHidden: () -> Unit = {},
    onDeleteUnreachable: () -> Unit = {},
    scanningUnreachable: Boolean = false,
    onAutoDeleteUnreachable: (Boolean) -> Unit = {},
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    barStyle: net.antibs.client.data.TrafficBarStyle = net.antibs.client.data.TrafficBarStyle(),
) {
    var menu by remember { mutableStateOf(ShotHooks.subMenuOpen) }
    val traffic = UiLogic.traffic(sub)
    val uri = LocalUriHandler.current
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        if (expanded) "Свернуть серверы" else "Показать серверы",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val titleText = subTitle(sub)
                val flag = LinkParser.flagOf(titleText)
                if (flag != null) {
                    Text(flag, fontSize = 15.sp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    titleText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(onClick = onToggle),
                )
                if (realServers > 0) SubPingIcon(pinging, onPing)
                if (sub.kind != "manual") {
                    if (refreshing) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                    else IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Refresh, "Обновить", modifier = Modifier.size(20.dp)) }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Меню", modifier = Modifier.size(20.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (sub.kind != "manual") {
                            DropdownMenuItem(text = { Text("Обновить") }, leadingIcon = { MenuIcon(Icons.Filled.Refresh) }, onClick = { menu = false; onRefresh() })
                            DropdownMenuItem(text = { Text("Копировать ссылку") }, leadingIcon = { MenuIcon(Icons.Filled.Link) }, onClick = { menu = false; onCopy() })
                        }
                        DropdownMenuItem(text = { Text("Проверить пинг") }, leadingIcon = { MenuIcon(Icons.Filled.NetworkCheck) }, onClick = { menu = false; onPing() })
                        extraMenu?.invoke { menu = false }
                        DropdownMenuItem(
                            text = { Text("Автоудаление недоступных") },
                            leadingIcon = { MenuIcon(Icons.Filled.DeleteSweep) },
                            trailingIcon = { Checkbox(checked = sub.autoDeleteUnreachable, onCheckedChange = null) },
                            onClick = { onAutoDeleteUnreachable(!sub.autoDeleteUnreachable) },
                        )
                        if (realServers > 0) DropdownMenuItem(
                            text = { Text(if (scanningUnreachable) "Удалить недоступные (пинг…)" else "Удалить недоступные") },
                            leadingIcon = { MenuIcon(Icons.Filled.DeleteSweep) },
                            enabled = !scanningUnreachable,
                            onClick = { menu = false; onDeleteUnreachable() },
                        )
                        if (sub.hiddenHosts.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Показать удалённые хосты (${sub.hiddenHosts.size})") }, leadingIcon = { MenuIcon(Icons.Filled.Visibility) }, onClick = { menu = false; onRestoreHidden() })
                        }
                        if (sub.webPageUrl.isNotBlank()) {
                            DropdownMenuItem(text = { Text("Страница подписки") }, leadingIcon = { MenuIcon(Icons.Filled.Language) }, onClick = { menu = false; runCatching { uri.openUri(sub.webPageUrl) } })
                        }
                        if (sub.supportUrl.isNotBlank()) {
                            DropdownMenuItem(text = { Text("Поддержка") }, leadingIcon = { MenuIcon(Icons.Filled.SupportAgent) }, onClick = { menu = false; runCatching { uri.openUri(sub.supportUrl) } })
                        }
                        DropdownMenuItem(text = { Text(if (sub.kind == "manual") "Очистить" else "Удалить", color = AntiBSColors.Err) }, leadingIcon = { MenuIcon(Icons.Filled.Delete, danger = true) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val note = sub.announce.ifBlank { sub.description }
            if (note.isNotBlank()) {
                Text(note, fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (sub.lastError.isNotEmpty()) Text(sub.lastError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // No «Трафик» label: "used/total" is drawn on the (thicker) bar itself.
            TrafficBar(UiLogic.trafficBarLabel(traffic), UiLogic.trafficRemaining(traffic.usedMb, traffic.limitMb), barStyle) // 2.0.44: shows what is left
            // One compact line under the traffic bar: real hosts (no auto-select/balancer rows) · expiry date.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    UiLogic.subFooterLeft(realServers),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                val right = UiLogic.subFooterRight(sub.expireEpoch)
                if (right.isNotEmpty()) {
                    Text(right, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            }
        }
    }
}

internal val TRAFFIC_BAR_H = TrafficBarStyle.HEIGHT_DEFAULT.dp

@Composable
internal fun TrafficBar(label: String, progress: Float, style: net.antibs.client.data.TrafficBarStyle = net.antibs.client.data.TrafficBarStyle()) {
    val p = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = p, label = "traffic")
    val barH = TrafficBarLogic.heightDp(style).dp
    val fillBrush = TrafficBarLogic.fillBrush(style, p, Accent.current)
    val inside = TrafficBarLogic.labelInside(style.heightDp)
    val font = TrafficBarLogic.labelSize(style.heightDp)
    val barShape = RoundedCornerShape(99.dp)
    val labelStyle = TabularNums.copy(fontSize = font, lineHeight = (font.value + 0.5f).sp, fontWeight = FontWeight.SemiBold,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(barH)
            .clip(barShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (inside) {
            // Text over the empty track (theme colour)…
            Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth())
        }
        if (animated > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(animated.coerceIn(0.03f, 1f))
                    .height(barH)
                    .clip(barShape)
                    .background(fillBrush),
            )
            if (inside) {
                // …and the same text in white, clipped to the filled part, so it stays readable in both themes.
                val frac = animated.coerceIn(0.03f, 1f)
                Text(
                    label,
                    style = labelStyle,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().drawWithContent {
                        clipRect(right = size.width * frac) { this@drawWithContent.drawContent() }
                    },
                )
            }
        }
    }
    if (!inside) {
        Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth())
    }
    }
}

/** 2.2.0: smaller protocol chip on server-list rows (17 → 14 dp tall, 10 → 8.5 sp, 6 → 4 dp padding). */
internal val PROTOCOL_BADGE_H = 14.dp

@Composable
internal fun ProtocolBadge(label: String, selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(PROTOCOL_BADGE_H)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) AntiBSColors.Blue else AntiBSColors.ChipOff)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 8.5.sp,
            lineHeight = 9.sp,
            style = LocalTextStyle.current.copy(lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)),
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun ServerRow(
    flag: String,
    title: String,
    subtitle: String,
    badge: String,
    ping: String,
    pingColor: Color,
    selected: Boolean,
    compact: Boolean,
    pingBusy: Boolean = false,
    onClick: () -> Unit,
    onPing: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    autoExcluded: Boolean = false,
    onToggleAuto: (() -> Unit)? = null,
    onLanSend: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().border(if (selected) 2.dp else 1.dp, if (selected) AntiBSColors.Blue else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape).clickable(onClick = onClick),
    ) {
        // 1.0.32: denser host rows (still ≥ 44dp touch via SwipeToDismiss / clickable).
        Row(Modifier.padding(start = 10.dp, end = 2.dp, top = if (compact) 3.dp else 4.dp, bottom = if (compact) 3.dp else 4.dp).heightIn(min = if (compact) 28.dp else 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(if (selected) AntiBSColors.Blue else Color.Transparent)
                    .border(1.5.dp, if (selected) AntiBSColors.Blue else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(flag, fontSize = if (compact) 15.sp else 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact && subtitle.isNotEmpty()) {
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (badge.isNotEmpty()) {
                    ProtocolBadge(badge, selected)
                }
                if (pingBusy) {
                    Box(Modifier.height(14.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                    }
                } else if (ping.isNotEmpty()) Text(ping, color = pingColor, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (onPing != null || onCopy != null || onLanSend != null) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "Меню", modifier = Modifier.size(18.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (onPing != null) DropdownMenuItem(text = { Text("Пинг") }, leadingIcon = { MenuIcon(Icons.Filled.NetworkCheck) }, onClick = { menu = false; onPing() })
                        if (onCopy != null) DropdownMenuItem(text = { Text("Копировать имя") }, leadingIcon = { MenuIcon(Icons.Filled.ContentCopy) }, onClick = { menu = false; onCopy() })
                        if (onLanSend != null) DropdownMenuItem(text = { Text("Передать на устройство") }, leadingIcon = { MenuIcon(Icons.Filled.SendToMobile) }, onClick = { menu = false; onLanSend() })
                        if (onEdit != null) DropdownMenuItem(text = { Text("Изменить") }, leadingIcon = { MenuIcon(Icons.Filled.Edit) }, onClick = { menu = false; onEdit() })
                        if (onReset != null) DropdownMenuItem(text = { Text("Сбросить изменения") }, leadingIcon = { MenuIcon(Icons.Filled.Restore) }, onClick = { menu = false; onReset() })
                        if (onToggleAuto != null) DropdownMenuItem(text = { Text(if (autoExcluded) "Вернуть в автовыбор" else "Исключить из автовыбора") }, leadingIcon = { MenuIcon(if (autoExcluded) Icons.Filled.AddCircleOutline else Icons.Filled.Block) }, onClick = { menu = false; onToggleAuto() })
                        if (onDelete != null) DropdownMenuItem(text = { Text("Удалить", color = AntiBSColors.Err) }, leadingIcon = { MenuIcon(Icons.Filled.Delete, danger = true) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable
internal fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * 2.0.35: slim segmented «Выкл | Вкл» power card (concept D).
 * The selected segment is orange while connecting, green when connected (healthy),
 * and red on a problem or [ConnState.ERROR] (smooth return to green when the network recovers).
 * Connecting / disconnecting show a pulsing progress shimmer. Tap or a horizontal swipe toggles
 * (disabled while busy). The session timer sits on the right. Same control on phone and TV.
 */
@Composable
internal fun PowerToggle(state: ConnState, since: Long, problem: Boolean = false, onClick: () -> Unit, haptics: Boolean = true, prefs: net.antibs.client.data.PowerButtonStyle = net.antibs.client.data.PowerButtonStyle()) {
    val style = LocalAppStyle.current
    val c = ssColors(style)
    val shape = ssShape(style, 16.dp)
    val on = UiLogic.powerSelectedOn(state)
    val busy = UiLogic.powerBusy(state)
    val connected = state == ConnState.CONNECTED
    val showError = problem || state == ConnState.ERROR
    val isTv = LocalIsTv.current
    val view = androidx.compose.ui.platform.LocalView.current
    val ctx = LocalContext.current
    val motionOk = remember(ctx) { PowerButtonLogic.motionOk(ctx) }
    val animate = PowerButtonLogic.shouldAnimate(prefs, motionOk)
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(true) }
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(obs)
        visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val live = PowerButtonLogic.shouldLive(connected, showError, prefs, motionOk, visible)
    val effect = PowerButtonLogic.effectOf(prefs)
    val accent = AntiBSColors.Blue
    val fillSolid = PowerButtonLogic.fillColor(prefs.color, accent)
    val onText = PowerButtonLogic.onTextColor(prefs.textColor, fillSolid)
    val tvFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var tvFocused by remember { mutableStateOf(false) }
    if (isTv) LaunchedEffect(Unit) { delay(150); runCatching { tvFocus.requestFocus() } }
    val tvScale by animateFloatAsState(if (tvFocused) 1.06f else 1f, if (animate) tween(160) else snap(), label = "tv-power")
    val motion = if (animate) spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) else snap<Float>()
    val colorTween = if (animate) tween<Float>(420) else snap<Float>()

    val pos by animateFloatAsState(if (on) 1f else 0f, animationSpec = motion, label = "seg")
    val thumb = when {
        showError -> UiLogic.PowerThumb.RED
        connected -> UiLogic.PowerThumb.GREEN
        state == ConnState.CONNECTING -> UiLogic.PowerThumb.ORANGE
        else -> UiLogic.PowerThumb.OFF
    }
    val errAmt by animateFloatAsState(if (thumb == UiLogic.PowerThumb.RED) 1f else 0f, animationSpec = colorTween, label = "err")
    val greenAmt by animateFloatAsState(if (thumb == UiLogic.PowerThumb.GREEN) 1f else 0f, animationSpec = colorTween, label = "green")
    val orangeAmt by animateFloatAsState(if (thumb == UiLogic.PowerThumb.ORANGE) 1f else 0f, animationSpec = colorTween, label = "orange")
    val wave: State<Float> = if (busy && animate) {
        rememberInfiniteTransition(label = "pwr-busy").animateFloat(
            0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pwr-wave",
        )
    } else remember { mutableFloatStateOf(1f) }
    val shimmer: State<Float> = if (busy && animate) {
        rememberInfiniteTransition(label = "pwr-shimmer").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "pwr-sh",
        )
    } else remember { mutableFloatStateOf(0f) }
    val livePulse: State<Float> = if (live) {
        val period = if (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_SHIMMER) 2400 else 1800
        rememberInfiniteTransition(label = "pwr-live").animateFloat(
            0f, 1f, infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pwr-pulse",
        )
    } else remember { mutableFloatStateOf(0.5f) }
    val liveFlow: State<Float> = if (live && effect == net.antibs.client.data.PowerButtonStyle.EFFECT_SHIMMER) {
        rememberInfiniteTransition(label = "pwr-flow").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pwr-flow",
        )
    } else remember { mutableFloatStateOf(0f) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected, since) {
        while (connected) {
            now = System.currentTimeMillis()
            delay(1000 - (now - since).mod(1000L).coerceIn(0, 999) + 5)
        }
    }
    val timer = UiLogic.powerTimerText(state, since, now)
    val desc = UiLogic.powerContentDescription(state, problem, timer)
    val muted = c.muted
    val onCard = c.onCard
    val greenBrush = PowerButtonLogic.fillBrush(prefs.color, accent)
    val orangeBrush = Brush.linearGradient(listOf(Color(0xFFFFC14D), Color(0xFFFF8A00)))
    val redBrush = Brush.linearGradient(listOf(Color(0xFFB4232F), AntiBSColors.Err, Color(0xFFFF6B5E)))
    val offPill = if (c.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val borderColor = androidx.compose.ui.graphics.lerp(c.cardBorder, AntiBSColors.Err, errAmt * 0.85f)

    fun fire() {
        if (busy) return
        AppHaptics.perform(view, AppHaptics.Kind.CLICK, haptics)
        onClick()
    }

    Column(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = tvScale; scaleY = tvScale },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.card)
                .border(1.dp, borderColor, shape)
                .then(if (isTv) Modifier.focusRequester(tvFocus).onFocusChanged { tvFocused = it.isFocused } else Modifier)
                .focusable()
                .pointerInput(on, busy) { detectTapGestures(onTap = { fire() }) }
                .pointerInput(on, busy) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            if (UiLogic.powerSwipeToggles(on, drag, 36.dp.toPx())) fire()
                        },
                        onHorizontalDrag = { change, dx -> change.consume(); drag += dx },
                    )
                }
                .semantics {
                    contentDescription = desc
                    role = Role.Switch
                }
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val trackShape = RoundedCornerShape(50)
            Box(
                Modifier.weight(1f).height(40.dp).clip(trackShape).background(c.chipOff),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(3.dp)) {
                    val padPx = 0f
                    val pillW = maxWidth / 2
                    if (live && (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_BREATH || effect == net.antibs.client.data.PowerButtonStyle.EFFECT_GLOW)) {
                        val glowA = if (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_GLOW)
                            0.22f + 0.28f * livePulse.value else 0.12f + 0.16f * livePulse.value
                        val spread = if (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_GLOW)
                            1.06f + 0.05f * livePulse.value else 1.02f + 0.03f * livePulse.value
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .offset { IntOffset(((pillW.toPx() + padPx) * pos).roundToInt(), 0) }
                                .width(pillW)
                                .fillMaxHeight()
                                .graphicsLayer { alpha = glowA; scaleX = spread; scaleY = spread }
                                .background(fillSolid.copy(alpha = 0.85f), trackShape),
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(((pillW.toPx() + padPx) * pos).roundToInt(), 0) }
                            .width(pillW)
                            .fillMaxHeight()
                            .clip(trackShape)
                            .graphicsLayer { alpha = if (busy) 0.72f + 0.28f * wave.value else 1f }
                            .background(offPill),
                    ) {
                        if (orangeAmt > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = orangeAmt }.background(orangeBrush))
                        if (greenAmt > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = greenAmt }.background(greenBrush))
                        if (errAmt > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = errAmt }.background(redBrush))
                        if (busy && animate) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sp = shimmer.value
                                val cx = -0.3f * size.width + sp * 1.6f * size.width
                                val bw = size.width * 0.28f
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                                        start = androidx.compose.ui.geometry.Offset(cx - bw, 0f),
                                        end = androidx.compose.ui.geometry.Offset(cx + bw, size.height),
                                    ),
                                )
                            }
                        } else if (live && effect == net.antibs.client.data.PowerButtonStyle.EFFECT_SHIMMER) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sp = liveFlow.value
                                val cx = -0.35f * size.width + sp * 1.7f * size.width
                                val bw = size.width * 0.34f
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f + 0.10f * livePulse.value), Color.Transparent),
                                        start = androidx.compose.ui.geometry.Offset(cx - bw, 0f),
                                        end = androidx.compose.ui.geometry.Offset(cx + bw, size.height),
                                    ),
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            "Выкл",
                            color = when {
                                !on && showError -> Color.White
                                !on -> onCard
                                else -> muted
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = fixedSp(14f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            "Вкл",
                            color = when {
                                on && connected && !showError -> onText
                                on -> Color.White
                                else -> muted
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = fixedSp(14f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 72.dp)) {
                Text(
                    timer,
                    style = TabularNums.copy(
                        fontSize = fixedSp(16f),
                        lineHeight = fixedSp(18f),
                        fontWeight = FontWeight.SemiBold,
                        color = if (connected) onCard else muted,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    "сессия",
                    color = muted,
                    fontSize = fixedSp(10f),
                    lineHeight = fixedSp(12f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun CaptchaCard(uri: String) {
    var open by remember { mutableStateOf<String?>(null) }
    CaptchaOpener(open) { open = null }
    Surface(shape = RoundedCornerShape(16.dp), color = AntiBSColors.Warn.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("VK просит капчу", fontWeight = FontWeight.Bold)
            Text("Подтвердите, что вы не робот. Токен уйдёт в клиент qWDTT.", fontSize = 13.sp)
            Row {
                TextButton(onClick = { net.antibs.client.engine.QwdttEngine.current?.submitCaptcha("error:cancelled") }) { Text("Отмена") }
                TextButton(onClick = { open = uri }) { Text("Открыть") }
            }
        }
    }
}

@Composable
private fun CaptchaOpener(uri: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(uri) {
        if (uri != null) {
            ctx.startActivity(android.content.Intent(ctx, net.antibs.client.vk.CaptchaActivity::class.java).putExtra("uri", uri))
            onDone()
        }
    }
}

@Composable
private fun AddSubDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая подписка") },
        text = {
            Column {
                Text("Тип определяется автоматически: Remnawave (base64 / share-ссылки) или qWDTT (JSON).", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, label = { Text("URL подписки") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) onAdd(url.trim()) }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SettingsDetail(page: Page, vm: AppViewModel, ui: UiSettings, onBack: () -> Unit, onOpen: (Page) -> Unit) {
    val st by vm.state.collectAsState()
    when (page) {
        Page.PerApp -> PerAppPage(vm, st, onBack)
        Page.Fragment -> FragmentPage(vm, st, onBack)
        Page.Noise -> NoisePage(vm, st, onBack)
        Page.Mux -> MuxPage(vm, st, onBack)
        Page.Inbounds -> InboundsPage(vm, st, onBack)
        Page.GeoFiles -> GeoFilesPage(vm, onBack)
        Page.NetScan -> NetScanPage(vm, onBack)
        Page.Excluded -> ExcludedRoutesPage(vm, st, onBack)
        Page.Subs -> SubsSettings(vm, onBack)
        Page.Ping -> PingPage(vm, st, onBack)
        Page.Seasonal -> SeasonalPage(vm, st, onBack)
        Page.SubRemain -> SubRemainPage(vm, st, onBack)
        Page.Schedule -> SchedulePage(onBack)
        Page.Protocols -> ProtocolsPage(onBack)
        Page.Hwid -> HwidPage(vm, st, onBack)
        Page.PowerButton -> PowerButtonPage(vm, onBack)
        Page.TrafficBar -> TrafficBarPage(vm, onBack)
        else -> SettingsScaffold("Настройки", onBack) { TextButton(onClick = onBack) { Text("Назад") } }
    }
}

@Composable
private fun SubsSettings(vm: AppViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var hwidFor by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    hwidFor?.let { id ->
        val sub = st.subscriptions.firstOrNull { it.id == id }
        if (sub == null) hwidFor = null else SubHwidDialog(sub.title.ifBlank { sub.url }, sub.hwidOverride,
            onSave = { v -> if (vm.setSubscriptionHwid(id, v)) { hwidFor = null; true } else false },
            onDismiss = { hwidFor = null })
    }
    SettingsScaffold("Подписки", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TextButton(onClick = { showAdd = true }) { Text("+ Добавить") } }
            items(st.subscriptions, key = { it.id }) { sub ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(sub.title.ifBlank { sub.url }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub.url, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { vm.refresh(sub.id) }, enabled = sub.id !in refreshing) { Text(if (sub.id in refreshing) "…" else "Обновить") }
                            TextButton(onClick = { copyText(ctx, sub.url); vm.message("Ссылка скопирована") }) { Text("Копировать") }
                            TextButton(onClick = { vm.removeSubscription(sub.id) }) { Text("Удалить") }
                            if (sub.kind != "manual") TextButton(onClick = { hwidFor = sub.id }) { Text(if (sub.hwidOverride.isBlank()) "HWID" else "HWID ✓") }
                        }
                        if (sub.hwidOverride.isNotBlank()) Text("HWID подписки: ${sub.hwidOverride}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub.kind != "manual") Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Авто-маршрутизация из подписки", fontSize = 13.sp)
                                val rp = st.adv.routingProfiles.firstOrNull { it.subscriptionId == sub.id }
                                Text(
                                    when {
                                        rp == null -> "Профиль маршрутизации из подписки (Happ) применяется автоматически"
                                        st.adv.activeRoutingId == rp.id -> "«${rp.name}» — активен"
                                        else -> "«${rp.name}» — загружен"
                                    },
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            androidx.compose.material3.Switch(checked = sub.autoRouting, onCheckedChange = { vm.setSubscriptionAutoRouting(sub.id, it) })
                        }
                    }
                }
            }
        }
    }
    if (showAdd) AddSubDialog(onDismiss = { showAdd = false }, onAdd = { vm.addSubscription(it); showAdd = false })
}

@Composable
internal fun SettingsScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val inTabs = LocalInTabs.current
    val base = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars)
    Column(if (inTabs) base else base.navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            if (inTabs) Spacer(Modifier.width(16.dp))
            else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onBackground) }
            Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(vertical = if (inTabs) 12.dp else 0.dp))
            actions()
        }
        // 2.2.0: tighter lines in settings hints / descriptions. Text that only sets fontSize kept the theme's
        // bodyLarge lineHeight (24 sp), so 12–13 sp hints got ~2× line spacing; a relative lineHeight follows the font size.
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = SETTINGS_LINE_HEIGHT)) { content() }
    }
}

/** 2.2.0: line height of settings texts that don't set their own (relative, ≈ 16 sp for a 13 sp hint). */
internal val SETTINGS_LINE_HEIGHT = 1.25.em

/** Legacy (bottom navigation removed in 1.0.8): always false, every settings page has a back arrow. */
internal val LocalInTabs = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 1.0.50: inside a [SettingsCard] rows get card padding and inset dividers; outside they keep the flat look. */
internal val LocalInCard = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun SectionLabel(text: String) {
    Text(text, color = AntiBSColors.Section, fontWeight = FontWeight.Medium, fontSize = 13.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp))
}

/** 2.0.8: main «Настройки» page → cards start collapsed (except the first); sub-pages start expanded. */
internal val LocalCollapseByDefault = androidx.compose.runtime.compositionLocalOf { false }
private val SettingsOpen = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
private const val FIRST_OPEN = "Настройки интерфейса"

/** Expand a collapsible settings card so a search hit (or deep link) is visible. */
internal fun expandSettingsCard(title: String?, mainHub: Boolean) {
    if (title.isNullOrBlank()) return
    SettingsOpen[(if (mainHub) "main:" else "sub:") + title] = true
}


/** 2.0.8: collapsible, style-aware card grouping settings rows (animated chevron, dividers between rows). */
@Composable
internal fun SettingsCard(title: String? = null, content: @Composable () -> Unit) {
    val style = LocalAppStyle.current
    val c = ssColors(style)
    val shape = ssShape(style, 18.dp)
    val collapse = LocalCollapseByDefault.current
    val key = (if (collapse) "main:" else "sub:") + (title ?: "")
    val open = title == null || (SettingsOpen[key] ?: (!collapse || title == FIRST_OPEN))
    val view = androidx.compose.ui.platform.LocalView.current
    Surface(
        shape = shape, color = c.card,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (title != null) 6.dp else 4.dp)
            .border(1.dp, c.cardBorder, shape),
    ) {
        Column {
            if (title != null) {
                val rot by animateFloatAsState(if (open) 180f else 0f, animationSpec = tween(260), label = "chev")
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(onClickLabel = if (open) "Свернуть" else "Развернуть") {
                            AppHaptics.perform(view, AppHaptics.Kind.TICK, true); SettingsOpen[key] = !open
                        }
                        .padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 2.0.15: leading section icon, same tile as the rows (style accent, style shape)
                    Box(Modifier.size(30.dp).clip(ssShape(style, 9.dp)).background(c.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(sectionIcon(title), null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(title, color = c.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ExpandMore, null, tint = c.muted, modifier = Modifier.graphicsLayer { rotationZ = rot })
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = open,
                enter = androidx.compose.animation.expandVertically(tween(260)) + androidx.compose.animation.fadeIn(tween(220)),
                exit = androidx.compose.animation.shrinkVertically(tween(240)) + androidx.compose.animation.fadeOut(tween(180)),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalInCard provides true) {
                    Column(Modifier.layout { m, cs ->
                        val pl = m.measure(cs)
                        val cut = 1.dp.roundToPx()
                        layout(pl.width, (pl.height - cut).coerceAtLeast(0)) { pl.place(0, 0) }
                    }) {
                        if (title != null) RowDivider()
                        content()
                    }
                }
            }
        }
    }
}

@Composable
internal fun RowDivider() {
    val inCard = LocalInCard.current
    val c = ssColors(LocalAppStyle.current)
    HorizontalDivider(Modifier.padding(start = if (inCard) 60.dp else 0.dp, end = if (inCard) 14.dp else 0.dp), thickness = 1.dp,
        color = c.cardBorder.copy(alpha = if (inCard) 0.9f else 1f))
}

@Composable
private fun RowTexts(title: String, subtitle: String?, modifier: Modifier) {
    val c = ssColors(LocalAppStyle.current)
    Column(modifier) {
        Text(title, color = c.onCard, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = c.muted, fontSize = 12.sp, lineHeight = 14.sp, // 2.2.0: was 15 sp
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 0.dp))
    }
}

/** 2.0.15: leading icon of a collapsible settings section header (main «Настройки» and sub-pages). */
internal fun sectionIcon(title: String): androidx.compose.ui.graphics.vector.ImageVector {
    val t = title.lowercase()
    return when {
        t.startsWith("настройки интерфейса") || t.startsWith("оформлен") -> Icons.Filled.Palette
        t.startsWith("настройки туннеля") -> Icons.Filled.VpnKey
        t.startsWith("блокировка") -> Icons.Filled.Block
        t.startsWith("подписки") || t.startsWith("автообновлен") || t.startsWith("остаток") -> Icons.Filled.Subscriptions
        t.startsWith("конфиденциальн") || t.startsWith("hwid") || t.startsWith("защита") -> Icons.Filled.Lock
        t.startsWith("инструмент") -> Icons.Filled.Build
        t.startsWith("приложение") -> Icons.Filled.Android
        else -> settingsIcon(title)
    }
}

/** 2.0.8 / 2.0.15: leading icon for a settings row, picked by keywords in its title (most specific first). */
internal fun settingsIcon(title: String): androidx.compose.ui.graphics.vector.ImageVector {
    val t = title.lowercase()
    fun has(vararg k: String) = k.any { t.contains(it) }
    return when {
        has("реклам") -> Icons.Filled.Block
        has("трекер", "телеметр", "взросл") -> Icons.Filled.Shield
        has("сезон", "украшен", "снег", "гирлянд", "праздник") -> Icons.Filled.AcUnit
        has("язык") -> Icons.Filled.Translate
        has("акцент", "цвет") -> Icons.Filled.ColorLens
        has("шрифт") && !has("размер") -> Icons.Filled.TextFields
        has("размер шрифта") || (has("размер") && has("шрифт")) -> Icons.Filled.FormatSize
        has("тем", "оформлен", "стиль", "интерфейс") -> Icons.Filled.Palette
        has("вибрац") -> Icons.Filled.Vibration
        has("кнопка питан", "живые эффект", "дыхание", "свечение") -> Icons.Filled.Power
        has("полоса трафик", "шкала трафик") -> Icons.Filled.DataUsage
        has("сессия", "session") -> Icons.Filled.Public
        has("тест скорост", "единиц", "скорост") -> Icons.Filled.Speed
        has("пинг", "url тест") -> Icons.Filled.NetworkCheck
        has("hwid") -> Icons.Filled.Fingerprint
        has("автообнов", "обновлять подписки", "интервал", "автопроверк", "проверить обновлен") -> Icons.Filled.Update
        has("wi‑fi", "wi-fi", "сканер") -> Icons.Filled.Wifi
        has("подписк") -> Icons.Filled.Subscriptions
        has("уведомл", "шторк") -> Icons.Filled.Notifications
        has("расписан") -> Icons.Filled.Schedule
        has("запуск", "надёжн", "фон") -> Icons.Filled.PowerSettingsNew
        has("режим работы") -> Icons.Filled.SettingsEthernet
        has("для выбранных приложен", "приложени") && !has("о приложен") -> Icons.Filled.Apps
        has("маршрут", "исключ") -> Icons.Filled.AltRoute
        has("dns") -> Icons.Filled.Dns
        has("фрагмент") -> Icons.Filled.CallSplit
        has("mux") -> Icons.Filled.MergeType
        has("тип ip", "ipv") -> Icons.Filled.Language
        has("mtu", "пакет", "сниффинг", "шум", "обфускац") -> Icons.Filled.Tune
        has("lan", "локальн", "inbound", "сеть", "сети") -> Icons.Filled.Router
        has("георесурс", "глобус", "карт", "океан") -> Icons.Filled.Public
        has("обход", "блок", "kill", "конфиденц", "безопас", "скрывать ip") -> Icons.Filled.Lock
        has("vpn", "туннел", "ядр", "xray", "протокол", "qwdtt", "mieru", "sing", "mihomo", "turn") -> Icons.Filled.VpnKey
        has("лог") -> Icons.Filled.Article
        has("экспорт") -> Icons.Filled.Upload
        has("импорт") -> Icons.Filled.Download
        has("версия", "о приложен", "о программе") -> Icons.Filled.Info
        has("сервер", "список", "хост", "групп", "стран", "компакт") -> Icons.Filled.ViewList
        has("инструмент", "резерв") -> Icons.Filled.Build
        has("главн", "url") -> Icons.Filled.Public
        else -> Icons.Filled.Tune
    }
}

/** 2.0.8: one frame for every settings row — same height, icon, title/subtitle, trailing control. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsRowFrame(title: String, subtitle: String?, onClick: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit) {
    val style = LocalAppStyle.current
    val c = ssColors(style)
    val id = settingsItemId(title)
    val flash = LocalSettingsFlash.current
    val bring = remember { BringIntoViewRequester() }
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(flash.itemId, flash.token) {
        if (flash.itemId != null && flash.itemId == id) {
            delay(340)
            runCatching { bring.bringIntoView() }
            lit = true
            delay(2200)
            lit = false
        }
    }
    val bg by animateColorAsState(
        if (lit) c.accent.copy(alpha = 0.28f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(450),
        label = "settings-flash",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .background(bg)
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(ssShape(style, 9.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(settingsIcon(title), null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        RowTexts(title, subtitle, Modifier.weight(1f).padding(end = 8.dp))
        trailing()
    }
}

/**
 * Settings row. Chevron row: [value] (or [subtitle]) is the gray line under the title, «>» opens a page.
 * [dropdown]: inline choice — [subtitle] describes the setting, the current [value] is accent-coloured with «↕».
 */
@Composable
internal fun NavRow(title: String, value: String? = null, dropdown: Boolean = false, subtitle: String? = null, chevron: Boolean = true, onClick: () -> Unit) {
    val c = ssColors(LocalAppStyle.current)
    SettingsRowFrame(title, if (dropdown) subtitle else value ?: subtitle, onClick) {
        if (dropdown) {
            if (value != null) Text(value, color = c.accent, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 148.dp))
            Icon(Icons.Filled.UnfoldMore, null, tint = c.accent, modifier = Modifier.padding(start = 2.dp).size(18.dp))
        } else if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted)
    }
    RowDivider()
}

@Composable
internal fun settingsSwitchColors(): SwitchColors {
    val c = ssColors(LocalAppStyle.current)
    return SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White,
        disabledCheckedTrackColor = c.accent.copy(alpha = 0.6f), disabledCheckedThumbColor = Color.White)
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange0: (Boolean) -> Unit) {
    // 1.0.55: toggles give the firmer click
    val hf = androidx.compose.ui.platform.LocalHapticFeedback.current
    val onChange: (Boolean) -> Unit = { hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onChange0(it) }
    SettingsRowFrame(title, subtitle, { onChange(!checked) }) {
        Switch(checked, onChange, colors = settingsSwitchColors())
    }
    RowDivider()
}

/** 1.0.32: a switch that is always on and cannot be turned off (HWID). */
@Composable
internal fun LockedOnRow(title: String, caption: String) {
    SettingsRowFrame(title, caption, null) {
        Switch(checked = true, onCheckedChange = null, enabled = false, colors = settingsSwitchColors())
    }
    RowDivider()
}

@Composable
internal fun ChoiceDialog(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == value, onClick = { onPick(value); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
internal fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AntiBS2client ${BuildConfig.VERSION_NAME}") },
        text = {
            Column {
                Text("Android VPN-клиент: Xray-core и qWDTT (VK TURN-туннель).", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("Ядро Xray: ${BuildConfig.XRAY_VERSION} (github.com/XTLS/Xray-core), AndroidLibXrayLite (MIT). TUN→прокси: hev-socks5-tunnel (MIT).", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Движок qWDTT основан на qWDTT / WDTT и распространяется на условиях GNU GPL v3. Текст — third_party/qwdtt-LICENSE-GPL-3.0.txt.", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Доп. движки: sing-box ${BuildConfig.SINGBOX_VERSION} (GPL-3.0), mieru (GPL-3.0), mihomo ${BuildConfig.MIHOMO_VERSION} (GPL-3.0) + quic-go (MIT). Настройки маршрутизации, per-app, фрагментирования и пинга сделаны по образцу Exclave.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}


@Composable
internal fun HostServerRow(
    s: net.antibs.client.data.ServerEntry,
    st: net.antibs.client.engine.AppState,
    pings: Map<String, Long>,
    pingBusy: Boolean,
    compact: Boolean,
    vm: AppViewModel,
    ctx: Context,
    onSelected: (() -> Unit)? = null,
    onLanSend: (() -> Unit)? = null,
) {
    val ping = pings[s.id]
    val isAuto = UiLogic.isAutoSelectEntry(s)
    // 1.0.34: the per-host exclusion only matters when the subscription's auto-select runs the app's own balancer
    val ownAuto = !isAuto && st.servers.any { it.subscriptionId == s.subscriptionId && net.antibs.client.data.AutoSelect.isSubscriptionAuto(it) }
    val excludedAuto = s.id in st.autoExcludeIds
    val sub = st.subscriptions.firstOrNull { it.id == s.subscriptionId }
    val edited = net.antibs.client.data.HostEdits.isEdited(sub, s.id)
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (editing) HostEditDialog(s, onDismiss = { editing = false }, onSave = { p -> editing = false; vm.editHost(s.id, p) })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить хост «${UiLogic.displayName(s.name)}»?") },
        text = { if (s.subscriptionId != ProtocolSupport.MANUAL_SUB_ID) Text("Хост не вернётся при обновлении подписки (восстановить: ⋮ подписки → «Показать удалённые хосты»).") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteHost(s.id) }) { Text("Удалить", color = AntiBSColors.Err) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
    SwipeHost(
        key = s.id,
        canDelete = !isAuto,
        onPing = { vm.pingOne(s) },
        // 1.0.23: swipe-left asks first (the row has already sprung back); confirm → delete + «Отменить».
        onDelete = { confirmDelete = true },
    ) {
    ServerRow(
        flag = UiLogic.flag(s.name),
        title = UiLogic.displayName(s.name),
        subtitle = if (ownAuto && excludedAuto) "не в автовыборе" else "",
        badge = UiLogic.protocolGroup(s),
        ping = pingText(ping),
        pingColor = pingColor(ping),
        pingBusy = pingBusy,
        selected = s.id == st.selectedServerId,
        compact = compact,
        onClick = { vm.select(s.id); onSelected?.invoke() },
        onPing = { vm.pingOne(s) },
        onCopy = { copyText(ctx, s.name); vm.message("Имя скопировано") },
        onEdit = if (s.xray != null && !isAuto) ({ editing = true }) else null,
        onReset = if (edited) ({ vm.resetHost(s.id) }) else null,
        onDelete = if (!isAuto) ({ confirmDelete = true }) else null,
        autoExcluded = excludedAuto,
        onToggleAuto = if (ownAuto && !isAuto) ({ vm.toggleAutoExclude(s.id) }) else null,
        onLanSend = onLanSend,
    )
    }
}


@Composable
internal fun SeasonalBanner(vm: AppViewModel, st: net.antibs.client.engine.AppState) {
    val mode = st.adv.seasonalDecor
    if (mode == "off") return
    val holiday = when {
        mode == "auto" -> net.antibs.client.data.HolidayCalendar.current(custom = st.adv.customHolidays)
        else -> net.antibs.client.data.HolidayCalendar.byId(mode, st.adv.customHolidays)
    } ?: return
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "season")
    val scale by infinite.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ), label = "pulse",
    )
    val alpha by infinite.animateFloat(
        initialValue = 0.75f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1200),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ), label = "glow",
    )
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                holiday.emoji,
                fontSize = 26.sp,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha },
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(holiday.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (holiday.isMuslim) "Мусульманский праздник" else "Праздник", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
        }
    }
}

internal fun themeLabel(m: ThemeMode) = when (m) {
    ThemeMode.LIGHT -> "Светлая"
    ThemeMode.DARK -> "Тёмная"
    ThemeMode.AMOLED -> "AMOLED (чёрная)"
    ThemeMode.AUTO -> "Как в системе"
}

/** 1.0.40: theme dialog options. */
internal val themeChoices = listOf("light" to "Светлая", "dark" to "Тёмная", "amoled" to "AMOLED (чёрная)", "auto" to "Как в системе")

internal fun themeFrom(raw: String) = when (raw) {
    "light" -> ThemeMode.LIGHT
    "dark" -> ThemeMode.DARK
    "amoled" -> ThemeMode.AMOLED
    else -> ThemeMode.AUTO
}

private fun levelName(l: String) = when (l) { "error" -> "Ошибки"; "info" -> "Инфо"; "debug" -> "Отладка"; "none" -> "Выкл"; else -> "Предупреждения" }

internal fun ipLabel(s: String) = when (s) { "ipv4" -> "IPv4"; "ipv6" -> "IPv6"; else -> "Auto" }

internal fun pingText(ms: Long?): String = when (ms) {
    null -> ""
    -2L -> "н/д"
    -1L -> "таймаут"
    else -> "$ms мс"
}

internal fun pingColor(ms: Long?): Color = when {
    ms == null -> Color.Unspecified
    ms < 0 -> AntiBSColors.Err
    ms < 200 -> AntiBSColors.Ok
    ms < 500 -> AntiBSColors.Warn
    else -> AntiBSColors.Err
}

internal fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("antibs", text))
}


/**
 * 1.0.22: Happ-style swipe on a host row. Right → ping (green), left → delete (red); the row always snaps back
 * (a deleted row simply leaves the list). [canDelete] = false disables the left swipe («Автовыбор сервера»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeHost(key: String, canDelete: Boolean, onPing: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    // 2.0.11: no swipe on TV — «Пинг» / «Удалить» are in the row's ⋮ menu (D-pad reachable)
    if (LocalIsTv.current) { content(); return }
    val ping by rememberUpdatedState(onPing)
    val del by rememberUpdatedState(onDelete)
    // One action per gesture even if the state machine asks twice.
    val last = remember { longArrayOf(0L) }
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            val now = android.os.SystemClock.uptimeMillis()
            if (v != androidx.compose.material3.SwipeToDismissBoxValue.Settled && now - last[0] < 700) return@rememberSwipeToDismissBoxState false
            if (v != androidx.compose.material3.SwipeToDismissBoxValue.Settled) last[0] = now
            when (v) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> ping()
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> del()
                else -> {}
            }
            false // always snap back
        },
        positionalThreshold = { it * 0.35f },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = canDelete,
        backgroundContent = {
            val dir = state.dismissDirection
            val right = dir == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val left = dir == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            val bg = when { right -> AntiBSColors.ChipOk; left -> AntiBSColors.Err; else -> Color.Transparent }
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 18.dp),
                contentAlignment = if (left) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (right) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NetworkCheck, null, tint = Color.White)
                    Spacer(Modifier.width(6.dp)); Text("Пинг", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                if (left) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Удалить", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Delete, null, tint = Color.White)
                }
            }
        },
    ) { content() }
}

/**
 * 1.0.22: «Изменить» — the host's main parameters prefilled. Only the fields shown change; every other
 * parameter of the host is kept (see [net.antibs.client.data.HostEdits.merge]). Custom JSON hosts get a JSON editor.
 */
@Composable
private fun HostEditDialog(s: net.antibs.client.data.ServerEntry, onDismiss: () -> Unit, onSave: (net.antibs.client.data.XrayProfile) -> Unit) {
    val x = s.xray ?: return
    val fields = remember(s.id) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(UiLogic.editFields(x)) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить · ${Format.protocolLabel(x.protocol)}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.subscriptionId != ProtocolSupport.MANUAL_SUB_ID) {
                    Text("Хост из подписки: изменения сохранятся при обновлении. Вернуть исходные — ⋮ «Сбросить изменения».",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                UiLogic.editFields(x).keys.forEach { k ->
                    androidx.compose.material3.OutlinedTextField(
                        value = fields[k] ?: "", onValueChange = { fields[k] = if (k == "port") it.filter { c -> c.isDigit() } else it },
                        label = { Text(UiLogic.editFieldLabel(k)) },
                        singleLine = k != "customJson", minLines = if (k == "customJson") 6 else 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(net.antibs.client.data.HostEdits.merge(x, fields.toMap())) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}


/** 2.0.11: TV single-column pages are centred at [TvMode.TV_COLUMN_DP]. */
@Composable
internal fun TvColumn(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = TvMode.TV_COLUMN_DP.dp).fillMaxSize()) { content() }
    }
}

/** 2.0.11: Home — phone: one column; TV: left = banner + power, right = icon row + URL test + map/globe. */
@Composable
internal fun ColumnScope.HomeSplit(tv: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (!tv) { left(); right(); return }
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.44f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.height(6.dp))
            left()
        }
        Column(Modifier.weight(0.56f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) { right() }
    }
}

/** 2.0.11: TV navigation as a side rail (D-pad ←/→ between rail and content). */
@Composable
internal fun MainSideRail(tab: MainTab, onSelect: (MainTab) -> Unit) {
    androidx.compose.material3.NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)),
    ) {
        Spacer(Modifier.weight(1f))
        MainTab.entries.forEach { t ->
            val sel = t == tab
            androidx.compose.material3.NavigationRailItem(
                selected = sel,
                onClick = { if (!sel) onSelect(t) },
                icon = {
                    Icon(
                        when (t) {
                            MainTab.Shop -> if (sel) Icons.Filled.Storefront else Icons.Outlined.Storefront
                            MainTab.Home -> if (sel) Icons.Filled.Home else Icons.Outlined.Home
                            MainTab.Speed -> if (sel) Icons.Filled.Public else Icons.Outlined.Public
                            MainTab.Net -> Icons.Filled.CellTower
                        },
                        contentDescription = t.label,
                    )
                },
                label = { Text(t.label, textAlign = TextAlign.Center, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp) },
                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = AntiBSColors.Blue,
                    indicatorColor = if (LocalAppStyle.current == ServerStyle.CLASSIC) AntiBSColors.BlueDeep else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** 2.0.11: height removed from the Home banner (paddings 4+4, spacer 3, status 2). */
internal val BANNER_TRIM = 13.dp

/** 2.0.14: leading icon for ⋮ menu items (uniform size/tint; destructive = red). */
@Composable
internal fun MenuIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false) =
    Icon(icon, null, modifier = Modifier.size(20.dp), tint = if (danger) AntiBSColors.Err else MaterialTheme.colorScheme.onSurfaceVariant)
