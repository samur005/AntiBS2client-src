package net.antibs.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import net.antibs.client.desktop.FlagText as Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.antibs.client.BuildConfig
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.SubAutoUpdate
import net.antibs.client.data.TrafficPresets
import net.antibs.client.engine.UiSettings
import net.antibs.client.data.AppLang
import net.antibs.client.data.AppLocale
import net.antibs.client.data.I18n

/**
 * 1.0.41: one settings screen, grouped by topic. Everything that used to sit behind «Расширенные настройки» →
 * «Настройки VPN» / «Настройки интерфейса» is here; only list editors and long forms remain separate pages.
 * [open] takes a Page name.
 */
@Composable
internal fun SettingsHome(vm: AppViewModel, ui: UiSettings, onBack: () -> Unit, open: (String) -> Unit, section: String = "Settings") {
    val st by vm.state.collectAsState()
    val a = st.adv
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val upd by vm.update.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var hashes by remember(st.vkHashes) { mutableStateOf(st.vkHashes) }
    fun exportFile() = scope.launch {
        val f = Desk.saveFile("Экспорт настроек", "antibs-settings-${BuildConfig.VERSION_NAME}.json") ?: return@launch
        val ok = withContext(Dispatchers.IO) { runCatching { f.writeText(vm.exportSettings()) }.isSuccess }
        vm.message(if (ok) "Настройки сохранены: ${f.name}" else "Не удалось сохранить файл")
    }
    fun importFile() = scope.launch {
        val f = Desk.openFile("Импорт настроек") ?: return@launch
        val text = withContext(Dispatchers.IO) { runCatching { f.readText() }.getOrNull() }
        if (text != null) vm.importSettings(text) else vm.message("Не удалось прочитать файл")
    }
    val plat = net.antibs.client.desktop.Platform.current
    var autostartOn by remember { mutableStateOf(plat.autostart.isEnabled()) }
    var trayClose by remember { mutableStateOf(UiPrefs.getBoolean("tray_on_close", true)) }
    val title = when (section) {
        "Interface" -> "Настройки интерфейса"; "Vpn" -> "Надёжность и запуск"; "SetCores" -> "Ядра и протоколы"
        "SetNet" -> "Сеть и DNS"; "SetSubAuto" -> "Автообновление подписок"; else -> "Настройки"
    }
    val routingValue = if (TrafficPresets.active(a)) TrafficPresets.title(a.trafficPreset)
        else a.activeRoutingId?.let { id -> a.routingProfiles.firstOrNull { it.id == id }?.name } ?: "Без правил"
    val tunnelDesc = if (a.mode == "proxy") "Системный прокси Windows: HTTP 127.0.0.1:${a.httpPort}, SOCKS :${a.socksPort} — без прав администратора"
        else "TUN (wintun, sing-box ${BuildConfig.SINGBOX_VERSION}) → Xray ${BuildConfig.XRAY_VERSION} · нужны права администратора"
    val mapPct by WorldOpacity.map.collectAsState()
    val globePct by WorldOpacity.globe.collectAsState()
    remember { WorldOpacity.init(); 0 }
    SettingsScaffold(title, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (section) {
                // ───────────── main screen: only the most important items, details behind «>»
                "Settings" -> androidx.compose.runtime.CompositionLocalProvider(LocalCollapseByDefault provides true) {
                    SettingsCard("Настройки интерфейса") {
                        NavRow(I18n.t("language"), if (AppLang.normalize(a.language) == AppLang.SYSTEM) I18n.t("lang_system") else AppLang.nativeLabel(a.language), dropdown = true, subtitle = I18n.t("language_sub")) { dialog = "lang" }
                        NavRow("Тема оформления", themeLabel(ui.theme), dropdown = true, subtitle = "Светлая, тёмная, чёрная или как в системе") { dialog = "theme" }
                        NavRow("Настройки интерфейса", subtitle = "Акцент, список серверов, главный экран, уведомление") { open("Interface") }
                    }
                    SettingsCard("Настройки туннеля") {
                        NavRow("Режим работы", if (a.mode == "proxy") "Прокси" else "VPN", dropdown = true, subtitle = tunnelDesc) { dialog = "mode" }
                        NavRow("Маршрутизация", routingValue) { open("Routing") }
                        NavRow("Надёжность и запуск", listOfNotNull(
                            if (autostartOn) "Автозапуск" else null, if (a.autoConnectOnLaunch) "Подключение при запуске" else null,
                            if (trayClose) "Трей" else null).joinToString(" · ").ifEmpty { "Автозапуск с Windows, трей" }) { open("Vpn") }
                        NavRow("Ядра и протоколы", "Xray ${BuildConfig.XRAY_VERSION} · sing-box ${BuildConfig.SINGBOX_VERSION}") { open("SetCores") }
                        NavRow("Сеть и DNS", listOfNotNull("MTU ${a.tunMtu}", if (a.localDns) "локальный DNS" else null, if (st.bypassLan) "обход LAN" else null).joinToString(" · ")) { open("SetNet") }
                        SwitchNavRow("Использовать фрагментирование", "Дробить TLS ClientHello к серверу — обход DPI · ${a.fragPackets} · ${a.fragLength} · ${a.fragDelay} мс",
                            ui.fragmentation, { vm.setFragmentation(it) }) { open("Fragment") }
                        SwitchNavRow("Использовать Mux", "TCP ${a.muxConcurrency} · XUDP ${a.muxXudpConcurrency} · QUIC: ${a.muxXudpQuic}. Сервер должен поддерживать mux",
                            ui.mux, { vm.setMux(it) }) { open("Mux") }
                        NavRow("Предпочтительный тип IP", ipLabel(ui.ipType), dropdown = true, subtitle = "Какие адреса использовать, если у домена есть и IPv4, и IPv6") { dialog = "ip" }
                    }
                    SettingsCard("Блокировка рекламы") { AdBlockItems(vm, st) }
                    SettingsCard("Подписки") {
                        NavRow("Подписки", "${st.subscriptions.size} шт.") { open("Subs") }
                        NavRow("Автообновление", if (st.subAutoUpdate) SubAutoUpdate.label(st.subUpdateHours)  else "Выключено") { open("SetSubAuto") }
                        NavRow("HWID устройства", if (st.customHwid.isNotBlank()) "Свой · ${st.customHwid.take(8)}…" else "По умолчанию · ${vm.deviceHwid.take(8)}…") { open("Hwid") }
                    }
                    SettingsCard("Конфиденциальность") {
                        SwitchRow("Скрывать IP серверов", a.hideServerIp, subtitle = "Адреса и хосты заменяются на «•••» в логах и при копировании") { v -> vm.setAdv { it.copy(hideServerIp = v) } }
                        SwitchRow("Логирование", a.logging, subtitle = "Журнал Xray и приложения на компьютере") { v -> vm.setAdvTunnel { it.copy(logging = v) } }
                        if (a.logging) NavRow("Уровень логов Xray", levelLabel(a.logLevel), dropdown = true) { dialog = "level" }
                    }
                    SettingsCard("Инструменты") {
                        NavRow("Тест скорости", subtitle = "Скорость и задержка через текущий сервер") { open("SpeedTest") }
                        NavRow("Логи", if (a.logging) levelLabel(a.logLevel) else "Логирование Xray выключено") { open("Logs") }
                        NavRow("Экспорт настроек", subtitle = "Настройки, маршрутизация, подписки и ручные серверы (без HWID)") { exportFile() }
                        NavRow("Импорт настроек", subtitle = "Добавляет недостающие подписки/серверы и заменяет настройки") { importFile() }
                    }
                    SettingsCard("Приложение") {
                        NavRow("Версия", BuildConfig.VERSION_NAME, chevron = false) {}
                        NavRow("Проверить обновления", if (upd is AppViewModel.UpdateUi.Checking) "Проверка…" else null) { vm.checkUpdates(manual = true) }
                        SwitchRow("Автопроверка обновлений", st.autoUpdateCheck) { vm.setAutoUpdateCheck(it) }
                        NavRow("О приложении", subtitle = "Версии ядер, лицензии") { dialog = "about" }
                    }
                    HintText("AntiBS2client не логирует ваш трафик. Настройки DNS, исключений и маршрутизации меняют то, что идёт мимо VPN — неверные значения могут раскрыть реальный IP.")
                }

                // ───────────── «Настройки интерфейса»
                "Interface" -> {
                    SettingsCard("Оформление") {
                        NavRow("Тема оформления", themeLabel(ui.theme), dropdown = true, subtitle = "Светлая, тёмная, чёрная или как в системе") { dialog = "theme" }
                        NavRow("Акцентный цвет", Accent.label(a.accent), dropdown = true, subtitle = "Цвет кнопок, переключателей и значений") { dialog = "accent" }
                        NavRow("Шрифт", AppFonts.label(a.fontFamily), dropdown = true, subtitle = "Системный или один из встроенных (кириллица)") { dialog = "font" }
                        FontScaleRow(a.fontScalePct) { pct -> vm.setAdv { it.copy(fontScalePct = UiScale.snapFontPct(pct)) } }
                    }
                    SettingsCard("Список серверов") {
                        SwitchRow("Компактный список серверов", ui.compactServers) { vm.setCompact(it) }
                        SwitchRow("Подписки развёрнуты", ui.subsExpanded, subtitle = "Группы подписок открыты при запуске") { vm.setSubsExpanded(it) }
                        SwitchRow("Скрывать пустую группу «Серверы»", a.hideEmptyManual) { vm.setHideEmptyManual(it) }
                    }
                }

                // ───────────── «Надёжность и запуск»
                "Vpn" -> {
                    SettingsCard("Запуск") {
                        SwitchRow("Запускать вместе с Windows", autostartOn, subtitle = if (plat.autostart.supported) "Свернуто в трей, при входе в систему" else "Недоступно в этой сборке") { v ->
                            plat.autostart.set(v).onSuccess { autostartOn = v; vm.setAutostart(v) }.onFailure { vm.message("Автозапуск: ${it.message}") }
                        }
                        SwitchRow("Подключаться при запуске приложения", a.autoConnectOnLaunch) { v -> vm.setAdv { it.copy(autoConnectOnLaunch = v) } }
                    }
                    SettingsCard("Окно") {
                        SwitchRow("Сворачивать в трей при закрытии", trayClose, subtitle = if (trayClose) "Крестик прячет окно, VPN продолжает работать" else "Крестик закрывает приложение и отключает VPN") { v ->
                            trayClose = v; UiPrefs.edit().putBoolean("tray_on_close", v).apply()
                        }
                    }
                    SettingsCard("Права администратора") {
                        NavRow("Режим VPN (TUN)", if (plat.isElevated()) "Есть права администратора" else "Нет прав — нажмите, чтобы перезапустить", chevron = !plat.isElevated()) {
                            if (!plat.isElevated()) { if (plat.relaunchElevated()) Desk.exit() else vm.message("Не удалось перезапустить с правами администратора") }
                        }
                    }
                }

                // ───────────── «Ядра и протоколы»
                "SetCores" -> {
                    SettingsCard("Xray") {
                        SwitchRow("Сниффинг пакетов", a.sniffing, subtitle = "http/tls/quic — нужен для доменных правил и блокировки рекламы") { v -> vm.setAdvTunnel { it.copy(sniffing = v) } }
                        SwitchNavRow("Шум (Noise)", "UDP: Hysteria2 / mKCP", a.noise, { v -> vm.setAdvTunnel { it.copy(noise = v) } }) { open("Noise") }
                        NavRow("Пинг серверов", when (a.pingType) { "head" -> "HEAD"; "tcp" -> "TCP"; "icmp" -> "ICMP"; else -> "GET" } + " · таймаут ${a.pingTimeoutSec} с") { open("Ping") }
                    }
                    SettingsCard("Движки") {
                        NavRow("Xray-core", BuildConfig.XRAY_VERSION, chevron = false) {}
                        NavRow("sing-box", "${BuildConfig.SINGBOX_VERSION} · TUN и Hysteria2/TUIC/…", chevron = false) {}
                        NavRow("qWDTT, olcRTC, OpenVPN, OpenConnect, mieru, mihomo", "Пока только в Android-версии", chevron = false) {}
                    }
                    SettingsCard("Протоколы") {
                        NavRow("Поддерживаемые протоколы", "${ProtocolSupport.MATRIX.size} · VLESS, VMess, Trojan, SS, Hysteria2, TUIC, WireGuard, OpenVPN, OpenConnect, olcRTC…") { open("Protocols") }
                    }
                }

                // ───────────── «Сеть и DNS»
                "SetNet" -> {
                    SettingsCard("Локальная сеть") {
                        SwitchRow("Обход локальной сети", st.bypassLan, subtitle = "Адреса LAN идут мимо VPN") { vm.setBypassLan(it) }
                        SwitchRow("Разрешить подключения из LAN", ui.allowLan, subtitle = "Другие устройства могут использовать локальные прокси") { vm.setAllowLan(it) }
                        NavRow("Inbounds (локальные прокси)", "SOCKS ${a.socksPort}" + if (a.httpInbound) " · HTTP ${a.httpPort}" else "") { open("Inbounds") }
                        NavRow("MTU туннеля", "${a.tunMtu}", dropdown = true) { dialog = "mtu" }
                    }
                    SettingsCard("Маршруты") {
                        NavRow("Исключённые маршруты", if (a.excludedRoutes.isEmpty()) "Нет" else "${a.excludedRoutes.size}") { open("Excluded") }
                        NavRow("Файлы георесурсов", subtitle = "geoip / geosite") { open("GeoFiles") }
                    }
                    SettingsCard("DNS") {
                        SwitchRow("Локальный DNS-сервер", a.localDns) { v -> vm.setAdvTunnel { it.copy(localDns = v) } }
                        if (a.localDns) SettingField("Порт локального DNS", a.localDnsPort.toString(), { v -> v.toIntOrNull()?.let { n -> vm.setAdvTunnel { it.copy(localDnsPort = n) } } }, numeric = true, valid = ::portOk, hint = "127.0.0.1:порт → DNS Xray (по умолчанию 10853)")
                        SwitchRow("DNS из JSON", a.dnsFromJson) { v -> vm.setAdvTunnel { it.copy(dnsFromJson = v) } }
                        if (a.dnsFromJson) {
                            val ok = a.dnsJson.isBlank() || runCatching { kotlinx.serialization.json.Json.parseToJsonElement(a.dnsJson) }.isSuccess
                            SettingField("Секция dns Xray (JSON)", a.dnsJson, { v -> vm.setAdvTunnel { it.copy(dnsJson = v) } }, singleLine = false,
                                hint = if (ok) "объект {\"servers\":[…]} или массив серверов" else "JSON не разбирается — будет использован DNS по умолчанию")
                        }
                        SwitchRow("Резолвить адреса серверов", a.resolveServers, subtitle = "Имя сервера → IP перед подключением (SNI/Host сохраняются). DNS для .RU — в «Маршрутизация»") { v -> vm.setAdvTunnel { it.copy(resolveServers = v) } }
                    }
                }

                // ───────────── «Автообновление подписок»
                "SetSubAuto" -> {
                    SettingsCard("Автообновление") {
                        SwitchRow("Обновлять подписки автоматически", st.subAutoUpdate) { vm.setSubAutoUpdate(it) }
                        if (st.subAutoUpdate) {
                            NavRow("Интервал", SubAutoUpdate.label(st.subUpdateHours), dropdown = true) { dialog = "subInterval" }
                        }
                    }
                    SettingsCard("HWID") {
                        LockedOnRow("Отправлять HWID", "Всегда включено")
                        NavRow("HWID устройства", if (st.customHwid.isNotBlank()) "Свой · ${st.customHwid.take(8)}…" else "По умолчанию · ${vm.deviceHwid.take(8)}…") { open("Hwid") }
                    }
                    val last = SubAutoUpdate.lastUpdate(st.subscriptions)
                    val hint = SubAutoUpdate.headerHint(st.subscriptions)
                    HintText("Последнее обновление: " + (if (last > 0) Format.dateTime(last) else "ещё не было") + (if (hint != null) "\n$hint" else ""))
                }
            }
        }
    }
    when (dialog) {
        "ip" -> ChoiceDialog("Предпочтительный тип IP", listOf("auto" to "Auto", "ipv4" to "IPv4", "ipv6" to "IPv6"), ui.ipType, { vm.setIpType(it) }, { dialog = null })
        "mtu" -> MtuDialog(a.tunMtu, { m -> vm.setAdvTunnel { it.copy(tunMtu = m) } }, { dialog = null })
        "theme" -> ChoiceDialog("Тема оформления", themeChoices, ui.theme.name.lowercase(), { vm.setTheme(themeFrom(it)) }, { dialog = null })
        "accent" -> ChoiceDialog("Акцентный цвет",
            Accent.presets.map { it.first to it.second },
            a.accent, { v -> vm.setAdv { it.copy(accent = v) } }, { dialog = null })
        "font" -> FontChoiceDialog(AppFonts.normalize(a.fontFamily), { v -> vm.setAdv { it.copy(fontFamily = v) } }, { dialog = null })
        "notif" -> ChoiceDialog("Трафик в уведомлении", listOf("off", "speed", "total", "both").map { it to notifLabel(it) }, a.notifTraffic,
            { v -> vm.setAdvTunnel { it.copy(notifTraffic = v) } }, { dialog = null })
        "notifEvery" -> ChoiceDialog("Обновлять каждые", listOf(1, 2, 5, 10).map { "$it" to "$it с" }, "${a.notifRefreshSec}",
            { v -> vm.setAdvTunnel { it.copy(notifRefreshSec = v.toInt()) } }, { dialog = null })
        "level" -> ChoiceDialog("Уровень логов Xray", listOf("error" to "Ошибки", "warning" to "Предупреждения", "info" to "Инфо (+ access-лог)", "debug" to "Отладка"), a.logLevel,
            { v -> vm.setAdvTunnel { it.copy(logLevel = v) } }, { dialog = null })
        "about" -> AboutDialog { dialog = null }
        "mode" -> ChoiceDialog("Режим работы", listOf("vpn" to "VPN (TUN) — весь трафик Windows, нужны права администратора", "proxy" to "Системный прокси — браузеры и приложения, без прав администратора"), a.mode,
            { v -> vm.setAdvTunnel { it.copy(mode = v) } }, { dialog = null })
        "qdns" -> ChoiceDialog("DNS для qWDTT", listOf("yandex" to "Яндекс", "cloudflare" to "Cloudflare", "google" to "Google"), st.qwdttDns,
            { vm.setQwdtt(dns = it) }, { dialog = null })
        "qobfs" -> ChoiceDialog("Обфускация qWDTT", listOf("audio" to "audio", "video" to "video"), st.qwdttObfs, { vm.setQwdtt(obfs = it) }, { dialog = null })
        "opGlobe", "opMap" -> {
            val isMap = dialog == "opMap"
            ChoiceDialog(if (isMap) "Прозрачность океана на карте" else "Прозрачность океана на глобусе",
                listOf(100, 80, 60, 40, 20, 0).map { "$it" to "$it%" }, "${if (isMap) mapPct else globePct}",
                { WorldOpacity.set(isMap, it.toInt(), true) }, { dialog = null })
        }
        "subInterval" -> {
            val presets = SubAutoUpdate.PRESETS
            val cur = SubAutoUpdate.normalizeHours(st.subUpdateHours)
            ChoiceDialog("Интервал обновления",
                presets.map { it.toString() to SubAutoUpdate.label(it) } + ("custom" to if (cur in presets) "Свой…" else "Свой: ${SubAutoUpdate.label(cur)}"),
                if (cur in presets) cur.toString() else "custom",
                { v -> if (v == "custom") dialog = "subCustom" else { vm.setSubUpdateHours(v.toInt()); dialog = null } }, { if (dialog == "subInterval") dialog = null })
        }
        "subCustom" -> {
            var text by remember { mutableStateOf(SubAutoUpdate.normalizeHours(st.subUpdateHours).toString()) }
            val parsed = SubAutoUpdate.parseCustomHours(text)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Свой интервал") },
                text = {
                    OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, singleLine = true, isError = parsed == null, label = { Text("Часов (1–168)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                },
                confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { vm.setSubUpdateHours(it) }; dialog = null }) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
            )
        }
    }
}

internal fun notifLabel(m: String) = when (m) { "off" -> "Выкл"; "speed" -> "Скорость"; "total" -> "Всего за сессию"; else -> "Скорость и всего" }


@Composable
internal fun FontScaleRow(pct: Int, onChange: (Int) -> Unit) {
    val snapped = UiScale.snapFontPct(pct)
    var v by remember(snapped) { mutableStateOf(snapped.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Размер шрифта", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${v.toInt()}%", color = AntiBSColors.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (v.toInt() != UiScale.FONT_PCT_DEFAULT) {
                TextButton(onClick = { v = UiScale.FONT_PCT_DEFAULT.toFloat(); onChange(UiScale.FONT_PCT_DEFAULT) }) {
                    Text("Сбросить", fontSize = 13.sp)
                }
            }
        }
        Slider(
            value = v,
            onValueChange = {
                val s = UiScale.snapFontPct(it.toInt()).toFloat()
                if (s != v) { v = s; onChange(s.toInt()) }
            },
            valueRange = UiScale.FONT_PCT_MIN.toFloat()..UiScale.FONT_PCT_MAX.toFloat(),
            steps = ((UiScale.FONT_PCT_MAX - UiScale.FONT_PCT_MIN) / UiScale.FONT_PCT_STEP) - 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("80%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("100%", fontSize = 11.sp, color = if (v.toInt() == 100) AntiBSColors.Blue else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (v.toInt() == 100) FontWeight.SemiBold else FontWeight.Normal)
            Text("130%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            AppFonts.PREVIEW,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    RowDivider()
}

@Composable
internal fun FontChoiceDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Шрифт") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                AppFonts.options.forEach { opt ->
                    val ff = AppFonts.familyOf(opt.id)
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(opt.id); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == opt.id, onClick = { onPick(opt.id); onDismiss() })
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(opt.label, fontFamily = ff, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (opt.headingsOnly) {
                                Text("Только заголовки", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
