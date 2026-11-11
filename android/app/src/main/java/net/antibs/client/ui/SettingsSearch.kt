package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect

/** Transient highlight target after picking a settings-search result. */
@Immutable
data class SettingsFlash(val itemId: String? = null, val token: Long = 0L)

internal val LocalSettingsFlash = compositionLocalOf { SettingsFlash() }

/** Stable id for a settings row — matches [SettingsRowFrame] highlight keys. */
internal fun settingsItemId(title: String): String =
    title.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')

internal fun settingsSearchNorm(s: String): String =
    s.lowercase()
        .replace('ё', 'е')
        .replace('‑', '-')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * One searchable settings control.
 * [section] — Page name for SettingsHome hubs (Settings/Interface/…) or a detail Page (Routing, Fragment…).
 * [card] — SettingsCard title to expand on the hub (null for detail pages / actions).
 */
@Immutable
data class SettingsSearchEntry(
    val title: String,
    val path: String,
    val section: String,
    val card: String? = null,
    val keywords: String = "",
    val itemId: String = settingsItemId(title),
)

internal object SettingsSearchCatalog {
    private fun e(
        title: String, path: String, section: String, card: String? = null, keywords: String = "",
    ) = SettingsSearchEntry(title, path, section, card, keywords)

    /** Full catalog of settings the user can jump to. */
    val all: List<SettingsSearchEntry> by lazy {
        val out = ArrayList<SettingsSearchEntry>(120)
        fun add(title: String, path: String, section: String, card: String? = null, kw: String = "") {
            out += e(title, path, section, card, kw)
        }
        // ── Main Settings hub ──
        val m = "Настройки"
        add("Сезонные украшения", "$m → Настройки интерфейса", "Settings", "Настройки интерфейса", "праздник снег гирлянда сезон")
        add("Язык", "$m → Настройки интерфейса", "Settings", "Настройки интерфейса", "язык русский интерфейс")
        add("Тема оформления", "$m → Настройки интерфейса", "Settings", "Настройки интерфейса", "тема светлая тёмная черная система")
        add("Стиль оформления", "$m → Настройки интерфейса", "Settings", "Настройки интерфейса", "стиль неон карточки аврора киберпанк")
        add("Настройки интерфейса", "$m", "Interface", null, "акцент список серверов главный экран уведомление")
        add("Режим работы", "$m → Настройки туннеля", "Settings", "Настройки туннеля", "vpn прокси режим")
        add("Маршрутизация", "$m → Настройки туннеля", "Routing", null, "routing правила трафик")
        add("Прокси для выбранных приложений", "$m → Настройки туннеля", "PerApp", null, "per-app приложения split")
        add("Российские приложения в обход VPN", "$m → Маршрутизация → Приложения", "Routing", null, "банки госуслуги vk яндекс маркетплейсы")
        add("Надёжность и запуск", "$m → Настройки туннеля", "Vpn", null, "автозапуск фон защита")
        add("Ядра и протоколы", "$m → Настройки туннеля", "SetCores", null, "xray qwdtt ядро протокол")
        add("Сеть и DNS", "$m → Настройки туннеля", "SetNet", null, "dns mtu lan сеть")
        add("Использовать фрагментирование", "$m → Настройки туннеля", "Settings", "Настройки туннеля", "fragment dpi tls")
        add("Использовать Mux", "$m → Настройки туннеля", "Settings", "Настройки туннеля", "mux xudp")
        add("Предпочтительный тип IP", "$m → Настройки туннеля", "Settings", "Настройки туннеля", "ipv4 ipv6 ip")
        add("Блокировать рекламу и трекеры", "$m → Блокировка рекламы", "Settings", "Блокировка рекламы", "реклама трекеры adblock")
        add("Трекеры и аналитика", "$m → Блокировка рекламы", "Settings", "Блокировка рекламы", "трекеры аналитика")
        add("Сайты для взрослых", "$m → Блокировка рекламы", "Settings", "Блокировка рекламы", "adult 18+")
        add("Телеметрия Xiaomi / MIUI", "$m → Блокировка рекламы", "Settings", "Блокировка рекламы", "xiaomi miui телеметрия")
        add("Телеметрия Windows", "$m → Блокировка рекламы", "Settings", "Блокировка рекламы", "windows телеметрия")
        add("Телеметрия Google (Crashlytics, Firebase)", "$m → Блокировка рекламы", "Settings", "Блокировка рекламы", "google firebase телеметрия")
        add("ДНС-блокировщик рекламы", "$m → Блокировка рекламы", "Settings", "Блокировка рекламы", "dns adguard mullvad")
        add("Подписки", "$m → Подписки", "Subs", null, "подписки список")
        add("Автоудаление недоступных", "$m → Подписки → ⋮ меню подписки", "Subs", null, "удалять недоступные после пинга автоудаление скрыть пинг")
        add("Удалить недоступные", "$m → Подписки → ⋮ меню подписки", "Subs", null, "удалить недоступные пинг скрыть")
        add("Автообновление", "$m → Подписки", "SetSubAuto", null, "автообновление подписок wifi")
        add("HWID устройства", "$m → Подписки", "Hwid", null, "hwid устройство")
        add("Скрывать IP серверов", "$m → Конфиденциальность", "Settings", "Конфиденциальность", "ip скрыть конфиденциальность")
        add("Логирование", "$m → Конфиденциальность", "Settings", "Конфиденциальность", "логи журнал")
        add("Уровень логов Xray", "$m → Конфиденциальность", "Settings", "Конфиденциальность", "лог уровень xray")
        add("Сессия", "$m → Инструменты", "SpeedTest", null, "сессия session speedtest скорость тест спидтест")
        add("Сканер сети Wi‑Fi", "$m → Инструменты", "NetScan", null, "сканер wifi lan устройства")
        add("Сеть: мониторинг и анализ", "$m → Инструменты", "NetTools", null, "мониторинг соты wifi ping dns")
        add("Логи", "$m → Инструменты", "Logs", null, "логи журнал xray")
        add("Сохранить копию настроек", "$m → Резервная копия настроек", "Settings", "Резервная копия настроек", "экспорт backup json копия")
        add("Поделиться настройками", "$m → Резервная копия настроек", "Settings", "Резервная копия настроек", "поделиться share backup")
        add("Восстановить настройки", "$m → Резервная копия настроек", "Settings", "Резервная копия настроек", "импорт restore json резервная копия")
        add("Версия", "$m → Приложение", "Settings", "Приложение", "версия")
        add("Проверить обновления", "$m → Приложение", "Settings", "Приложение", "обновление update")
        add("Автопроверка обновлений", "$m → Приложение", "Settings", "Приложение", "автопроверка обновлений")
        add("О приложении", "$m → Приложение", "Settings", "Приложение", "about лицензии ядра")

        // ── Interface ──
        val i = "Настройки интерфейса"
        add("Тема оформления", "$i → Оформление", "Interface", "Оформление", "тема")
        add("Акцентный цвет", "$i → Оформление", "Interface", "Оформление", "акцент цвет")
        add("Шрифт", "$i → Оформление", "Interface", "Оформление", "font шрифт inter roboto")
        add("Размер шрифта", "$i → Оформление", "Interface", "Оформление", "font scale размер шрифта")
        add("Вибрация", "$i → Оформление", "Interface", "Оформление", "haptics вибрация")
        add("Единицы скорости", "$i → Оформление", "Interface", "Оформление", "кбит мбит байты")
        add("URL Тест", "$i → Главный экран", "Interface", "Главный экран", "url тест google telegram")
        add("Сортировка серверов", "$i → Список серверов", "Interface", "Список серверов", "сортировка алфавиту быстрые дата добавления")
        add("Порядок подписок", "$i → Список серверов", "Interface", "Список серверов", "подписки новые сверху снизу")
        add("Карточка «Лучший сервер»", "$i → Оформление", "Interface", "Оформление", "лучший сервер пинг")
        add("Вид подписок", "$i → Оформление", "Interface", "Оформление", "лента группы подписок")
        add("Компактный список серверов", "$i → Список серверов", "Interface", "Список серверов", "компактный список")
        add("Подписки развёрнуты", "$i → Список серверов", "Interface", "Список серверов", "подписки раскрыты")
        add("Группировать по странам", "$i → Список серверов", "Interface", "Список серверов", "страны группировка флаг")
        add("Сортировка стран", "$i → Список серверов", "Interface", "Список серверов", "сортировка стран")
        add("Страны раскрыты по умолчанию", "$i → Список серверов", "Interface", "Список серверов", "")
        add("Пинг самого быстрого сервера", "$i → Список серверов", "Interface", "Список серверов", "пинг страны")
        add("Запоминать раскрытые группы", "$i → Список серверов", "Interface", "Список серверов", "")
        add("Скрывать пустую группу «Серверы»", "$i → Список серверов", "Interface", "Список серверов", "ручные серверы")
        add("Остаток подписки", "$i → Главный экран", "SubRemain", null, "остаток дни трафик")
        add("Трафик в уведомлении", "$i → Уведомление VPN", "Interface", "Уведомление VPN", "уведомление трафик")
        add("Обновлять каждые", "$i → Уведомление VPN", "Interface", "Уведомление VPN", "интервал уведомления")

        // ── Vpn ──
        val v = "Надёжность и запуск"
        add("Уведомлять о другой VPN-программе", "$v → Запуск", "Vpn", "Запуск", "revoke другая vpn")
        add("Подключаться при запуске приложения", "$v → Запуск", "Vpn", "Запуск", "автоподключение")
        add("Расписание VPN", "$v → Запуск", "Schedule", null, "расписание время")
        add("Кнопка VPN в шторке", "$v → Запуск", "Vpn", "Запуск", "tile плитка шторка")
        add("Блокировать обход туннеля", "$v → Защита", "Vpn", "Защита", "kill switch обход")

        // ── SetCores ──
        val c = "Ядра и протоколы"
        add("Xray TUN", "$c → Xray", "SetCores", "Xray", "tun xray")
        add("Сниффинг пакетов", "$c → Xray", "SetCores", "Xray", "sniffing")
        add("Шум (Noise)", "$c → Xray", "Noise", null, "noise udp")
        add("DNS для qWDTT", "$c → qWDTT (VK TURN)", "SetCores", "qWDTT (VK TURN)", "qwdtt dns")
        add("Обфускация", "$c → qWDTT (VK TURN)", "SetCores", "qWDTT (VK TURN)", "obfs qwdtt")
        add("TURN по TCP", "$c → qWDTT (VK TURN)", "SetCores", "qWDTT (VK TURN)", "turn tcp")
        add("Поддерживаемые протоколы", "$c → Протоколы", "Protocols", null, "протоколы vless trojan")

        // ── SetNet ──
        val n = "Сеть и DNS"
        add("Настройки пинга", "$n → Пинг серверов", "Ping", null, "пинг timeout")
        add("Обход локальной сети", "$n → Локальная сеть", "SetNet", "Локальная сеть", "bypass lan")
        add("Разрешить подключения из LAN", "$n → Локальная сеть", "SetNet", "Локальная сеть", "allow lan proxy")
        add("Inbounds (локальные прокси)", "$n → Локальная сеть", "Inbounds", null, "socks http inbound")
        add("MTU туннеля", "$n → Локальная сеть", "SetNet", "Локальная сеть", "mtu")
        add("Исключённые маршруты", "$n → Маршруты", "Excluded", null, "excluded routes")
        add("Файлы георесурсов", "$n → Маршруты", "GeoFiles", null, "geoip geosite")
        add("Локальный DNS-сервер", "$n → DNS", "SetNet", "DNS", "local dns")
        add("DNS из JSON", "$n → DNS", "SetNet", "DNS", "dns json")
        add("Резолвить адреса серверов", "$n → DNS", "SetNet", "DNS", "resolve dns")

        // ── SetSubAuto ──
        val s = "Автообновление подписок"
        add("Обновлять подписки автоматически", "$s → Автообновление", "SetSubAuto", "Автообновление", "автообновление")
        add("Интервал", "$s → Автообновление", "SetSubAuto", "Автообновление", "интервал часов")
        add("Только по Wi‑Fi", "$s → Автообновление", "SetSubAuto", "Автообновление", "wifi только")
        add("HWID устройства", "$s → HWID", "Hwid", "HWID", "hwid")

        // Detail pages reachable from switches
        add("Фрагментирование", "Настройки → Фрагментирование", "Fragment", null, "fragment dpi")
        add("Mux", "Настройки → Mux", "Mux", null, "mux")

        out.distinctBy { it.itemId + "|" + it.section + "|" + (it.card ?: "") }
    }

    fun filter(query: String, limit: Int = 80): List<SettingsSearchEntry> {
        val q = settingsSearchNorm(query)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        return all.asSequence()
            .map { entry ->
                val hay = settingsSearchNorm(entry.title + " " + entry.path + " " + entry.keywords)
                val score = when {
                    settingsSearchNorm(entry.title) == q -> 300
                    settingsSearchNorm(entry.title).startsWith(q) -> 200
                    hay.contains(q) -> 100
                    tokens.all { hay.contains(it) } -> 50
                    else -> 0
                }
                entry to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.itemId + it.section }
            .take(limit)
            .toList()
    }
}

@Composable
internal fun SettingsSearchButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Поиск по настройкам" }) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
internal fun SettingsSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onSelect: (SettingsSearchEntry) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val c = ssColors(LocalAppStyle.current)
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query) { SettingsSearchCatalog.filter(query) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Закрыть поиск" }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Поле поиска настроек" },
                singleLine = true,
                placeholder = { Text("Поиск по настройкам") },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Очистить")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
            )
            Spacer(Modifier.width(8.dp))
        }
        when {
            query.isBlank() -> Text(
                "Введите название настройки — например «DNS», «тема», «пинг»",
                color = c.muted, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            results.isEmpty() -> Text(
                "Ничего не найдено",
                color = c.muted, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).semantics { contentDescription = "Ничего не найдено" },
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.itemId + "|" + it.section + "|" + (it.card ?: "") }) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                kb?.hide()
                                onSelect(e)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(settingsIcon(e.title), null, tint = c.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = c.onCard, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(e.path, color = c.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
