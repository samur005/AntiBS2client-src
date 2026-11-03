package net.antibs.client.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.antibs.client.BuildConfig
import net.antibs.client.data.AdvancedSettings
import net.antibs.client.data.Cidr
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.RoutingImport
import net.antibs.client.data.RoutingProfile
import net.antibs.client.data.RoutingRule
import net.antibs.client.data.parseRange
import net.antibs.client.engine.AppState
import net.antibs.client.engine.VpnState

// ───────────────────────── shared bits ─────────────────────────

@Composable
internal fun HintText(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** Switch with a tappable title that opens a details page (title/subtitle + «>» opens, switch toggles). */
@Composable
internal fun SwitchNavRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, onOpen: () -> Unit) {
    val c = ssColors(LocalAppStyle.current)
    SettingsRowFrame(title, subtitle, onOpen) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Настроить", tint = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
        Switch(checked, onChange, colors = settingsSwitchColors())
    }
    RowDivider()
}

/** Text field that commits every valid edit. [valid] = null → no validation. */
@Composable
internal fun SettingField(
    label: String, value: String, onCommit: (String) -> Unit, numeric: Boolean = false,
    valid: ((String) -> Boolean)? = null, hint: String? = null, singleLine: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(value) }
    val ok = valid?.invoke(text) ?: true
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            if (valid?.invoke(v) != false) onCommit(v)
        },
        label = { Text(label) },
        singleLine = singleLine,
        isError = !ok,
        supportingText = when {
            !ok -> ({ Text("Неверное значение", color = AntiBSColors.Err) })
            hint != null -> ({ Text(hint) })
            else -> null
        },
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

private fun rangeOk(s: String) = parseRange(s) != null
internal fun portOk(s: String) = s.toIntOrNull()?.let { it in 1024..65535 } == true

@Composable
internal fun Segmented(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, l) ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (v == current) AntiBSColors.Blue else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f).clickable { onPick(v) },
            ) {
                Text(l, modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(), color = if (v == current) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// ───────────────────────── Tunnel: fragment / noise / mux / MTU / inbounds ─────────────────────────

@Composable
internal fun FragmentPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val a = st.adv
    SettingsScaffold("Фрагментирование", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsCard {
                SwitchRow("Использовать фрагментирование", st.fragmentation, subtitle = "Дробить TLS ClientHello к серверу — обход DPI") { vm.setFragmentation(it) }
                HintText("Xray режет TLS ClientHello на куски (finalmask «fragment»). Применяется к TCP-транспортам с TLS/REALITY; для REALITY пакеты «tlshello» заменяются на «1-3».")
            }
            SettingsCard("Параметры") {
            Segmented(listOf("xray" to "Xray"), a.fragType) { }
            SettingField("Пакеты (packets)", a.fragPackets, { v -> vm.setAdvTunnel { it.copy(fragPackets = v.trim()) } },
                valid = { it.trim() == "tlshello" || rangeOk(it) }, hint = "tlshello или диапазон, например 1-3")
            SettingField("Длина (length)", a.fragLength, { v -> vm.setAdvTunnel { it.copy(fragLength = v.trim()) } }, valid = ::rangeOk, hint = "например 50-100")
            SettingField("Интервал, мс (delay)", a.fragDelay, { v -> vm.setAdvTunnel { it.copy(fragDelay = v.trim()) } }, valid = ::rangeOk, hint = "например 10-20")
            SettingField("Макс. разбиений (maxSplit)", a.fragMaxSplit, { v -> vm.setAdvTunnel { it.copy(fragMaxSplit = v.trim()) } }, valid = ::rangeOk, hint = "например 100-200")
            TextButton(onClick = { vm.setAdvTunnel { it.copy(fragPackets = "tlshello", fragLength = "50-100", fragDelay = "10-20", fragMaxSplit = "100-200") } },
                modifier = Modifier.padding(horizontal = 8.dp)) { Text("Сбросить по умолчанию") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun NoisePage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val a = st.adv
    SettingsScaffold("Шум (Noise)", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsCard {
                SwitchRow("Использовать шум", a.noise, subtitle = "UDP: Hysteria2 / mKCP") { v -> vm.setAdvTunnel { it.copy(noise = v) } }
                HintText("Перед UDP-рукопожатием (Hysteria2 / mKCP) Xray шлёт мусорные пакеты (finalmask «noise»). Не включается, если у сервера уже есть salamander/udphop.")
            }
            SettingsCard("Тип пакета") {
            Segmented(listOf("array" to "array", "hex" to "hex", "str" to "str", "base64" to "base64"), a.noiseType) { v -> vm.setAdvTunnel { it.copy(noiseType = v) } }
            SettingField("Пакет (packet)", a.noisePacket, { v -> vm.setAdvTunnel { it.copy(noisePacket = v) } },
                hint = if (a.noiseType == "array") "байты hex через запятую: 1A,2B,3C" else "пусто — без фиксированного пакета")
            SettingField("Задержка, мс (delay)", a.noiseDelay, { v -> vm.setAdvTunnel { it.copy(noiseDelay = v.trim()) } }, valid = ::rangeOk, hint = "например 10-16")
            SettingField("Случайные байты (rand)", a.noiseRand, { v -> vm.setAdvTunnel { it.copy(noiseRand = v.trim()) } }, valid = { it.isBlank() || rangeOk(it) }, hint = "длина, например 1-8192; пусто — не слать")
            SettingField("Диапазон значений (randRange)", a.noiseRandRange, { v -> vm.setAdvTunnel { it.copy(noiseRandRange = v.trim()) } }, valid = ::rangeOk, hint = "0-255")
            Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun MuxPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val a = st.adv
    SettingsScaffold("Mux", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsCard {
                SwitchRow("Использовать Mux", st.mux, subtitle = "Сервер должен поддерживать mux") { vm.setMux(it) }
                HintText("Мультиплексирование соединений Xray. Не применяется к XTLS Vision, Hysteria, WireGuard, HTTP/SOCKS и протоколам вторых движков.")
            }
            SettingsCard("Соединения") {
            SettingField("TCP-соединений (concurrency)", a.muxConcurrency.toString(), { v -> v.toIntOrNull()?.let { n -> vm.setAdvTunnel { it.copy(muxConcurrency = n) } } },
                numeric = true, valid = { it.toIntOrNull()?.let { n -> n in -1..1024 } == true }, hint = "1–1024, -1 = не мультиплексировать TCP")
            SettingField("XUDP-соединений (xudpConcurrency)", a.muxXudpConcurrency.toString(), { v -> v.toIntOrNull()?.let { n -> vm.setAdvTunnel { it.copy(muxXudpConcurrency = n) } } },
                numeric = true, valid = { it.toIntOrNull()?.let { n -> n in -1..1024 } == true }, hint = "1–1024, -1 = UDP без XUDP")
            Spacer(Modifier.height(8.dp))
            }
            SettingsCard("QUIC (UDP/443) через Mux") {
            Segmented(listOf("reject" to "Отклонять", "allow" to "Разрешить", "skip" to "Мимо Mux"), a.muxXudpQuic) { v -> vm.setAdvTunnel { it.copy(muxXudpQuic = v) } }
            HintText("«Отклонять» заставляет браузеры откатиться с QUIC на TCP — обычно быстрее через Mux.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun InboundsPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val a = st.adv
    SettingsScaffold("Inbounds", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            HintText("Локальные входы Xray. SOCKS нужен туннелю hev-socks5 и режиму «Прокси»; HTTP — для приложений, которые умеют только HTTP-прокси.")
            SettingsCard("Порты") {
            Spacer(Modifier.height(6.dp))
            SettingField("Порт SOCKS5", a.socksPort.toString(), { v -> v.toIntOrNull()?.let { n -> vm.setAdvTunnel { it.copy(socksPort = n) } } }, numeric = true, valid = ::portOk, hint = "1024–65535, по умолчанию 10808")
            SwitchRow("HTTP-прокси", a.httpInbound) { v -> vm.setAdvTunnel { it.copy(httpInbound = v) } }
            if (a.httpInbound) SettingField("Порт HTTP", a.httpPort.toString(), { v -> v.toIntOrNull()?.let { n -> vm.setAdvTunnel { it.copy(httpPort = n) } } }, numeric = true, valid = ::portOk)
            SwitchRow("Разрешить подключения из LAN", st.allowLan) { vm.setAllowLan(it) }
            }
            HintText("Адрес: ${if (st.allowLan) "0.0.0.0" else "127.0.0.1"}:${a.socksPort}${if (a.httpInbound) " (SOCKS), :${a.httpPort} (HTTP)" else ""}. Без пароля — LAN включайте только в доверенной сети.")
        }
    }
}

@Composable
internal fun MtuDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val ok = text.toIntOrNull()?.let { it in 1280..9000 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MTU туннеля") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() } }, singleLine = true, isError = !ok, label = { Text("1280–9000") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(1280, 1400, 1500, 9000).forEach { m -> Pill("$m", text == "$m") { text = "$m" } }
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onPick(text.toInt()); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ───────────────────────── Per-app proxy ─────────────────────────

private data class AppInfoRow(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

@Composable
internal fun PerAppPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val a = st.adv
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val apps by produceState<List<AppInfoRow>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).filter { it.packageName != ctx.packageName }.map {
                AppInfoRow(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName),
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0, it)
            }.sortedBy { it.label.lowercase() }
        }
    }
    val selected = a.perAppSelected.toSet()
    SettingsScaffold("Прокси для приложений", onBack) {
        Segmented(listOf("off" to "ВЫКЛ", "include" to "ВКЛ", "bypass" to "Обход"), a.perAppMode) { v -> vm.setAdvTunnel { it.copy(perAppMode = v) }; vm.reconnectIfConnected() }
        HintText(when (a.perAppMode) {
            "include" -> "Через VPN идут только отмеченные приложения, остальные — напрямую. Если ничего не отмечено, проксируется всё."
            "bypass" -> "Отмеченные приложения идут мимо VPN напрямую, остальные — через прокси."
            else -> "Через VPN идут все приложения. Само AntiBS2client всегда в обходе, чтобы ядра не зацикливались."
        })
        if (a.perAppMode != "off") {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Поиск приложений") },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, null) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(showSystem, { showSystem = it }); Text("Системные", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("Выбрано: ${selected.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    vm.setAdvTunnel { if (it.perAppMode == "bypass") it.copy(perAppBypass = emptyList()) else it.copy(perAppInclude = emptyList()) }
                }) { Text("Сбросить") }
            }
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val q = query.trim().lowercase()
                val shown = list.filter { (showSystem || !it.system || it.pkg in selected) && (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q)) }
                    .sortedByDescending { it.pkg in selected }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.pkg }) { app ->
                        val on = app.pkg in selected
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.setAdvTunnel { s ->
                                    if (s.perAppMode == "bypass") s.copy(perAppBypass = if (on) s.perAppBypass - app.pkg else s.perAppBypass + app.pkg)
                                    else s.copy(perAppInclude = if (on) s.perAppInclude - app.pkg else s.perAppInclude + app.pkg)
                                }
                            }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(ctx, app.info)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(on, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(ctx: Context, info: ApplicationInfo) {
    val bmp by produceState<ImageBitmap?>(null, info.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(36.dp))
    else Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
}

// ───────────────────────── Routing ─────────────────────────

@Composable
internal fun RoutingListPage(vm: AppViewModel, st: AppState, onOpenProfile: (String) -> Unit, onBack: () -> Unit, onOpenPage: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val a = st.adv
    val rf by vm.rf.collectAsState()
    var menu by remember { mutableStateOf(false) }
    SettingsScaffold("Маршрутизация", onBack, actions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Добавить профиль маршрутизации") }, onClick = {
                    menu = false
                    val p = vm.newRoutingProfile(); vm.upsertRouting(p); onOpenProfile(p.id)
                })
                DropdownMenuItem(text = { Text("Импортировать профиль из буфера") }, onClick = {
                    menu = false
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    vm.importRouting(text)
                })
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SwitchRow("Обход локальной сети", st.bypassLan) { vm.setBypassLan(it) } }
            trafficPresetItems(vm, st, rf.ready, onOpenGeo = { onOpenPage("GeoFiles") }, onOpenAdBlock = { onOpenPage("AdBlock") })
            item { SectionLabel("Профили маршрутизации") }
            item {
                RoutingRow("Без профиля", "Весь трафик через выбранный сервер", a.activeRoutingId == null, onSelect = { vm.setActiveRouting(null) }, onOpen = null)
            }
            val groups = a.routingProfiles.groupBy { it.group }
            groups.forEach { (group, list) ->
                item { SectionLabel(group) }
                items(list, key = { it.id }) { p ->
                    val cnt = p.rules.size
                    val servers = p.proxyServerIds.mapNotNull { id -> st.servers.firstOrNull { it.id == id }?.name }
                    RoutingRow(
                        p.name.ifBlank { "Без имени" },
                        "Правил: $cnt" + (if (!p.globalProxy) " · остальное напрямую" else "") + (if (servers.isNotEmpty()) " · прокси: ${UiLogic.displayName(servers.first())}" else ""),
                        a.activeRoutingId == p.id,
                        onSelect = { vm.setActiveRouting(p.id) },
                        onOpen = { onOpenProfile(p.id) },
                    )
                }
            }
            if (a.routingProfiles.isEmpty()) item {
                HintText("Профилей нет. ⋮ → «Добавить профиль маршрутизации» или «Импортировать профиль из буфера» (Happ JSON, happ://routing/…). Профиль из заголовка «routing» подписки появляется здесь автоматически.")
            }
            item { HintText("Правила работают в Xray: домены (domain:, full:, geosite:), IP/CIDR (geoip:), порты. Правила по приложениям — только в режиме Xray TUN.") }
        }
    }
}

@Composable
private fun RoutingRow(title: String, subtitle: String, active: Boolean, onSelect: () -> Unit, onOpen: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable { onOpen?.invoke() ?: onSelect() }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(active, onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onOpen != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
}

private val actionLabels = listOf("direct" to "Прямое соединение", "block" to "Блокировка", "proxy" to "Прокси")
private val kindLabels = listOf("domain" to "Домен", "ip" to "IP / CIDR", "process" to "Приложение", "port" to "Порт")

@Composable
internal fun RoutingEditPage(vm: AppViewModel, st: AppState, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = st.adv.routingProfiles.firstOrNull { it.id == id }
    if (p == null) { SettingsScaffold("Профиль", onBack) { HintText("Профиль удалён.") }; return }
    var addFor by remember { mutableStateOf<String?>(null) }
    var pickServers by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun save(np: RoutingProfile) { vm.upsertRouting(np); if (st.adv.activeRoutingId == np.id && VpnState.status.value.state == net.antibs.client.engine.ConnState.CONNECTED) vm.message("Переподключитесь, чтобы применить") }
    SettingsScaffold("Профиль маршрутизации", onBack, actions = {
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить") }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SettingField("Имя", p.name, { v -> save(p.copy(name = v)) }) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (st.adv.activeRoutingId == p.id) "✓ Активен" else "Сделать активным", st.adv.activeRoutingId == p.id) { vm.setActiveRouting(p.id) }
                    Pill("Копировать JSON", false) { copyText(ctx, RoutingImport.exportHapp(p)); vm.message("Профиль скопирован (формат Happ)") }
                }
            }
            item { SwitchRow("Остальной трафик через прокси", p.globalProxy) { save(p.copy(globalProxy = it)) } }
            item {
                SectionLabel("Стратегия доменов")
                Segmented(listOf("AsIs" to "AsIs", "IPIfNonMatch" to "IPIfNonMatch", "IPOnDemand" to "IPOnDemand"), p.domainStrategy) { save(p.copy(domainStrategy = it)) }
            }
            actionLabels.forEach { (action, label) ->
                val enabled = when (action) { "direct" -> p.directEnabled; "block" -> p.blockEnabled; else -> p.proxyEnabled }
                item(key = "sw-$action") {
                    SwitchRow(label, enabled) { v ->
                        save(when (action) { "direct" -> p.copy(directEnabled = v); "block" -> p.copy(blockEnabled = v); else -> p.copy(proxyEnabled = v) })
                    }
                }
                if (action == "proxy") item(key = "srv") {
                    val names = p.proxyServerIds.mapNotNull { sid -> st.servers.firstOrNull { it.id == sid }?.name }
                    NavRow("Серверы для «Прокси»", if (names.isEmpty()) "выбранный" else UiLogic.displayName(names.first()) + if (names.size > 1) " +${names.size - 1}" else "") { pickServers = true }
                }
                val rules = p.rules.withIndex().filter { it.value.action == action }
                items(rules, key = { "r-$action-${it.index}-${it.value.value}" }) { (idx, r) ->
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(kindLabels.firstOrNull { it.first == r.kind }?.second ?: r.kind, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(78.dp))
                        Text(r.value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { save(p.copy(rules = p.rules.filterIndexed { i, _ -> i != idx })) }) { Icon(Icons.Filled.Close, "Удалить", Modifier.size(18.dp)) }
                    }
                }
                item(key = "add-$action") {
                    TextButton(onClick = { addFor = action }, modifier = Modifier.padding(start = 20.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Добавить правило")
                    }
                }
            }
            item { HintText("Порядок: блокировка → прокси → прямое. Примеры: geosite:category-ads-all, domain:ru, full:example.com, geoip:ru, 10.0.0.0/8, port:25.") }
        }
    }
    addFor?.let { action ->
        AddRuleDialog(action, onDismiss = { addFor = null }) { rules -> save(p.copy(rules = p.rules + rules)); addFor = null }
    }
    if (pickServers) {
        val candidates = st.servers.filter { s -> s.xray?.let { x -> x.protocol !in setOf("chain", "balancer", "custom") && x.unsupportedReason.isNullOrBlank() } == true }
        var chosen by remember { mutableStateOf(p.proxyServerIds) }
        AlertDialog(
            onDismissRequest = { pickServers = false },
            title = { Text("Серверы для «Прокси»") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Правила «Прокси» пойдут через первый отмеченный сервер (не текущий). Пусто — через выбранный на главной.", fontSize = 12.sp)
                    candidates.forEach { s ->
                        val on = s.id in chosen
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - s.id else chosen + s.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, null); Text(UiLogic.displayName(s.name), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { save(p.copy(proxyServerIds = chosen)); pickServers = false }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { pickServers = false }) { Text("Отмена") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить «${p.name}»?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteRouting(p.id); onBack() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}

@Composable
private fun AddRuleDialog(action: String, onDismiss: () -> Unit, onAdd: (List<RoutingRule>) -> Unit) {
    var kind by remember { mutableStateOf("domain") }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Правило: " + (actionLabels.firstOrNull { it.first == action }?.second ?: action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { kindLabels.forEach { (k, l) -> Pill(l, kind == k) { kind = k } } }
                OutlinedTextField(value, { value = it }, label = {
                    Text(when (kind) { "domain" -> "домены, по строке или через запятую"; "ip" -> "IP, CIDR, geoip:…"; "process" -> "имя пакета (com.example.app)"; else -> "порты: 25,465-587" })
                }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val items = value.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
                val rules = if (kind == "port") listOf(RoutingRule(action, kind, items.joinToString(","))).filter { it.value.isNotEmpty() }
                else items.map { RoutingRule(action, kind, it) }
                if (rules.isNotEmpty()) onAdd(rules) else onDismiss()
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ───────────────────────── VPN / DNS / excluded routes / background ─────────────────────────

@Composable
internal fun BackgroundActivityRow(ctx: Context) {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
    NavRow("Работа в фоне", if (ignoring) "без ограничений" else "ограничена") {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { launcher.launch(if (ignoring) fallback else direct) }.onFailure { runCatching { launcher.launch(fallback) } }
    }
    HintText("Разрешите работу без ограничений батареи, иначе система может остановить VPN в фоне. На Xiaomi/Huawei/Samsung дополнительно включите автозапуск и «Без ограничений» в настройках приложения.")
}

@Composable
internal fun ExcludedRoutesPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val list = st.adv.excludedRoutes
    var text by remember { mutableStateOf("") }
    fun valid(s: String): Boolean {
        val t = s.trim()
        if (Cidr.parseV4(t) != null) return true
        val ip = t.substringBefore('/')
        val len = t.substringAfter('/', "128").toIntOrNull() ?: return false
        return ip.contains(':') && len in 0..128 && ip.all { it.isLetterOrDigit() || it == ':' || it == '.' }
    }
    SettingsScaffold("Исключённые маршруты", onBack) {
        HintText("Адреса, которые идут мимо VPN напрямую (например, корпоративная сеть). Android 13+: IPv4 и IPv6; Android 8–12: только IPv4 (маршруты разбиваются автоматически).")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("CIDR, напр. 192.168.100.0/24") }, isError = text.isNotBlank() && !valid(text), modifier = Modifier.weight(1f))
            IconButton(enabled = valid(text), onClick = {
                val v = text.trim().let { if (it.contains('/')) it else if (it.contains(':')) "$it/128" else "$it/32" }
                vm.setAdvTunnel { a -> a.copy(excludedRoutes = (a.excludedRoutes + v).distinct()) }; text = ""
            }) { Icon(Icons.Filled.Add, "Добавить") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it }) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.setAdvTunnel { a -> a.copy(excludedRoutes = a.excludedRoutes - r) } }) { Icon(Icons.Filled.Delete, "Удалить") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }
}

// ───────────────────────── Ping ─────────────────────────

@Composable
internal fun PingPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val a = st.adv
    var timeout by remember(a.pingTimeoutSec) { mutableFloatStateOf(a.pingTimeoutSec.toFloat()) }
    SettingsScaffold("Пинг", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SwitchRow("Автопинг серверов", a.autoPingServers, subtitle = "При открытии приложения и после обновления подписок") { v ->
                vm.setPingSettings { it.copy(autoPingServers = v) }
            }
            HintText("Выключено по умолчанию. Ручной пинг (кнопки «Пинговать все» и на карточках) работает всегда.")
            AutoPingSection(vm, a)
            SectionLabel("Тип пинга")
            listOf(
                "get" to "GET-запрос через прокси", "head" to "HEAD-запрос через прокси",
                "tcp" to "TCP-подключение к серверу", "icmp" to "ICMP (ping)",
            ).forEach { (k, l) ->
                Row(Modifier.fillMaxWidth().clickable { vm.setPingSettings { it.copy(pingType = k) } }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(a.pingType == k, { vm.setPingSettings { it.copy(pingType = k) } }); Text(l)
                }
            }
            if (a.pingType == "get" || a.pingType == "head") {
                SectionLabel("Режим")
                Segmented(listOf("default" to "По умолчанию", "double" to "Двойной", "keepalive" to "Keep-Alive"), a.pingMode) { v -> vm.setPingSettings { it.copy(pingMode = v) } }
                HintText(when (a.pingMode) {
                    "double" -> "Два запроса без keep-alive в одном экземпляре Xray, засчитывается лучший — близко к реальному открытию сайта."
                    "keepalive" -> "Первый запрос прогревает соединение, засчитывается второй — чистая задержка без рукопожатий."
                    else -> "Каждый запрос через новый экземпляр Xray (полное рукопожатие), лучший из двух."
                })
                SettingField("URL для теста", a.pingUrl, { v -> vm.setPingSettings { it.copy(pingUrl = v.trim()) } },
                    valid = { it.trim().startsWith("http://") || it.trim().startsWith("https://") })
                TextButton(onClick = { vm.setPingSettings { it.copy(pingUrl = AdvancedSettings.DEFAULT_PING_URL) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("По умолчанию: generate_204") }
            }
            SectionLabel("Таймаут: ${timeout.toInt()} с")
            Slider(
                value = timeout, onValueChange = { timeout = it }, valueRange = 5f..15f, steps = 9,
                onValueChangeFinished = { vm.setPingSettings { it.copy(pingTimeoutSec = timeout.toInt()) } },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SectionLabel("Отображение результата")
            Segmented(listOf("time" to "Время"), "time") { }
            HintText("GET/HEAD считаются через Xray и для протоколов sing-box / mieru / mihomo (они запускаются только при подключении) заменяются на TCP-пинг. qWDTT не пингуется («н/д»).")
            Button(onClick = { vm.pingAll() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Пинговать все") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────────── Interface ─────────────────────────

internal fun levelLabel(l: String) = when (l) { "error" -> "Ошибки"; "info" -> "Инфо"; "debug" -> "Отладка"; "none" -> "Выкл"; else -> "Предупреждения" }

// ───────────────────────── Logs ─────────────────────────

private val logFilters = listOf(
    "all" to "Все", "xray" to "Xray", "sing-box" to "sing-box", "mieru" to "mieru", "mihomo" to "mihomo", "qwdtt" to "qWDTT", "app" to "Приложение",
)

private fun logMatches(line: String, f: String): Boolean {
    val body = line.substringAfter(' ', line)
    return when (f) {
        "all" -> true
        "xray" -> body.startsWith("[xray")
        "sing-box" -> body.startsWith("[sing-box]")
        "mieru" -> body.startsWith("[mieru]")
        "mihomo" -> body.startsWith("[mihomo]")
        "qwdtt" -> body.startsWith("[qwdtt]", ignoreCase = true) || body.startsWith("[qWDTT]")
        else -> !body.startsWith("[")
    }
}

@Composable
internal fun LogsTab(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val logs by VpnState.logs.collectAsState()
    val st by vm.state.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var follow by remember { mutableStateOf(true) }
    // 1.0.40: «Скрывать IP серверов» — masked on screen and in «Копировать»
    val hosts = remember(st.servers, st.adv.hideServerIp) { if (st.adv.hideServerIp) UiLogic.serverHosts(st.servers) else emptySet() }
    val shown = remember(logs, filter, hosts, st.adv.hideServerIp) {
        logs.filter { logMatches(it, filter) }.let { l -> if (st.adv.hideServerIp) l.map { UiLogic.maskLine(it, hosts) } else l }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }
    SettingsScaffold("Логи", onBack, actions = {
        TextButton(onClick = { copyText(ctx, shown.joinToString("\n")); vm.message("Скопировано строк: ${shown.size}") }) { Text("Копировать") }
        TextButton(onClick = { VpnState.clearLogs() }) { Text("Очистить") }
    }) {
        LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(logFilters) { (k, l) -> Pill(l, filter == k) { filter = k } }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(follow, { follow = it }); Text("Автопрокрутка", fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(if (st.adv.logging) "Xray: ${levelLabel(st.adv.logLevel)}" else "Логи Xray выключены", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.isEmpty()) HintText("Пусто. Логи Xray, sing-box, mieru и mihomo появляются здесь в реальном времени после подключения.")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown) { line ->
                val err = line.contains("[Error]", true) || line.contains("ошибка", true) || line.contains("failed", true) || line.contains("ERROR")
                val warn = line.contains("[Warning]", true) || line.contains("WARN")
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                    color = when { err -> AntiBSColors.Err; warn -> AntiBSColors.Warn; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

// ───────────────────────── Protocols ─────────────────────────

@Composable
internal fun ProtocolsPage(onBack: () -> Unit) {
    SettingsScaffold("Протоколы", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { HintText("Все протоколы ниже реально подключаются. Xray ${BuildConfig.XRAY_VERSION} — основное ядро; sing-box ${BuildConfig.SINGBOX_VERSION}, mieru и mihomo ${BuildConfig.MIHOMO_VERSION} запускаются отдельными процессами и подключаются к Xray через локальный SOCKS, поэтому маршрутизация, статистика и DNS остаются в Xray.") }
            val groups = ProtocolSupport.MATRIX.groupBy { it.engine }
            groups.forEach { (engine, rows) ->
                item { SectionLabel(engine) }
                items(rows) { r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✓", color = AntiBSColors.Ok, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                        Text(r.label, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        if (r.note.isNotEmpty()) Text(r.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
            }
            item { SectionLabel("qWDTT") }
            item { HintText("qWDTT (WireGuard через VK TURN) — отдельный движок, профили из подписки qWDTT.") }
            item { HintText("Не поддерживаются: Juicity, HTTP/2-CONNECT без Naive, TrustTunnel-ссылки tt:// в бинарном формате (создайте вручную).") }
        }
    }
}

@Suppress("unused")
private fun isApi33() = Build.VERSION.SDK_INT >= 33


// ───────────────────────── 1.0.18: HWID ─────────────────────────

@Composable
internal fun HwidPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val current = st.customHwid.ifBlank { vm.deviceHwid }
    var custom by remember(st.customHwid) { mutableStateOf(st.customHwid) }
    val customOk = custom.isBlank() || net.antibs.client.data.Hwid.isValid(custom.trim())
    SettingsScaffold("HWID", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 1.0.32: always on — Remnawave / qWDTT device limits need the headers.
            LockedOnRow("Отправлять HWID", "Всегда включено")
            HintText("При обновлении подписок всегда отправляются заголовки x-hwid, x-device-os (Android), x-ver-os (${Build.VERSION.RELEASE}) и x-device-model (${Build.MODEL}) — для лимита устройств Remnawave и qWDTT.")
            SectionLabel("Текущий HWID")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(current, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text(if (st.customHwid.isBlank()) "HWID устройства (по умолчанию)" else "Свой HWID", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { copyText(ctx, current); vm.message("HWID скопирован") }) { Text("Копировать") }
            }
            SectionLabel("Свой HWID")
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("HWID (пусто = по умолчанию)") },
                singleLine = true,
                isError = !customOk,
                supportingText = { Text(if (customOk) "10–64 символа: латиница, цифры, «=», «-»" else "Неверный HWID: 10–64 символа, только A–Z, a–z, 0–9, «=», «-»", color = if (customOk) MaterialTheme.colorScheme.onSurfaceVariant else AntiBSColors.Err) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (vm.setCustomHwid(custom)) vm.message(if (custom.isBlank()) "HWID сброшен на HWID устройства" else "HWID сохранён. Обновите подписки") }, enabled = customOk && custom.trim() != st.customHwid) { Text("Сохранить") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.generateHwid() }) { Text("Сгенерировать новый") }
                OutlinedButton(onClick = { vm.resetHwid() }, enabled = st.customHwid.isNotBlank()) { Text("Сбросить по умолчанию") }
            }
            HintText("HWID устройства постоянный для этой установки. Свой HWID можно перенести экспортом настроек (HWID устройства не экспортируется). Для отдельной подписки HWID задаётся в Настройки → Подписки → HWID.")
        }
    }
}

@Composable
internal fun SubHwidDialog(title: String, value: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val ok = text.isBlank() || net.antibs.client.data.Hwid.isValid(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HWID подписки") },
        text = {
            Column {
                Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, isError = !ok,
                    label = { Text("Пусто = общий HWID") },
                    supportingText = { if (!ok) Text("10–64 символа: A–Z, a–z, 0–9, «=», «-»", color = AntiBSColors.Err) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = ok) { Text("Сохранить") } },
        dismissButton = {
            Row {
                TextButton(onClick = { text = net.antibs.client.data.Hwid.generate() }) { Text("Новый") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
