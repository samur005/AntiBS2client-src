package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import java.io.File
import net.antibs.client.data.EngineKind
import net.antibs.client.data.HolidayCalendar
import net.antibs.client.data.QwdttProfile
import net.antibs.client.data.SeasonalFx
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.SnowMode
import net.antibs.client.data.Subscription
import net.antibs.client.data.XrayProfile
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.ThemeMode
import org.junit.After
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0.36 Home shots (power colours, no header icons, optional filters / flat list) (ABS_SHOTS=1): СЕТЬ-style filters, banner arrow/graph align, subscription ⋮ toggle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h880dp-xxhdpi")
class Home2036ShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = "/workspace/polish_shots/2036"

    @After fun resetHooks() { ShotHooks.subMenuOpen = false }

    private fun shot(theme: ThemeMode, name: String, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(80)
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val sub = Subscription(id = "s1", url = "https://example/sub", title = "AntiBS Premium", expireEpoch = 1_766_448_000)

    private fun xray(id: String, name: String, protocol: String = "vless", security: String? = null, network: String = "tcp", server: String = "example.com") =
        ServerEntry(id, EngineKind.XRAY, name, "s1", xray = XrayProfile(name, protocol, server, 443, network = network, security = security))

    private fun qwdtt(id: String, name: String) =
        ServerEntry(id, EngineKind.QWDTT, name, "s1", qwdtt = QwdttProfile(name, "1.2.3.4"))

    private val allServers = listOf(
        xray("de1", "🇩🇪 Франкфурт", security = "reality"),
        xray("de2", "🇩🇪 Берлин", security = "reality"),
        xray("de3", "🇩🇪 Мюнхен", protocol = "trojan"),
        xray("de4", "🇩🇪 Гамбург", protocol = "trojan"),
        qwdtt("de5", "🇩🇪 Дюссельдорф 4G"),
        xray("nl1", "🇳🇱 Амстердам", security = "reality"),
        qwdtt("nl2", "🇳🇱 Амстердам WDTT"),
        xray("nl3", "🇳🇱 Роттердам", protocol = "vless", network = "ws"),
        xray("fr1", "🇫🇷 Лион 4G"),
        qwdtt("pl1", "🇵🇱 Варшава 4G"),
        qwdtt("lv1", "🇱🇻 Рига WDTT"),
        xray("fi1", "🇫🇮 Хельсинки IPv6", security = "reality", server = "2001:db8::10"),
        xray("us1", "🇺🇸 Нью-Йорк", protocol = "shadowsocks"),
        xray("gb1", "🇬🇧 Лондон", protocol = "hysteria"),
        xray("jp1", "🇯🇵 Токио", protocol = "wireguard"),
        xray("sg1", "🇸🇬 Сингапур", protocol = "tuic"),
        xray("at1", "🇦🇹 Вена", protocol = "vless", network = "grpc"),
        xray("ch1", "🇨🇭 Цюрих", protocol = "vless", network = "xhttp"),
        xray("it1", "🇮🇹 Милан", security = "reality"),
        xray("es1", "🇪🇸 Мадрид", protocol = "vless", network = "ws"),
        xray("cz1", "🇨🇿 Прага", security = "reality"),
        xray("se1", "🇸🇪 Стокгольм IPv6", server = "2001:db8::20"),
        xray("no1", "🇳🇴 Осло IPv6", protocol = "trojan", server = "2001:db8::30"),
        xray("ua1", "🇺🇦 Киев", security = "reality"),
        xray("tr1", "🇹🇷 Стамбул", protocol = "vmess"),
    )

    private val pings = mapOf(
        "de1" to 38L, "de2" to 44L, "de3" to 51L, "de4" to 57L, "de5" to 63L,
        "nl1" to 48L, "nl2" to 52L, "nl3" to 55L, "fr1" to 81L, "pl1" to 70L, "lv1" to 88L,
        "fi1" to 42L, "us1" to 120L, "gb1" to 66L, "jp1" to 210L, "sg1" to 180L,
        "at1" to 49L, "ch1" to 47L, "it1" to 72L, "es1" to 90L, "cz1" to 54L,
        "se1" to 41L, "no1" to 39L, "ua1" to 95L, "tr1" to 110L,
    )

    @Composable
    private fun home(
        tab: String,
        country: String,
        theme: ThemeMode,
        menu: Boolean = false,
        headerMenu: Boolean = false,
        snow: Boolean = true,
        vpn: ConnState = ConnState.CONNECTED,
        problem: Boolean = false,
    ) {
        val t0 = android.os.SystemClock.uptimeMillis() - 20_000
        val down = listOf(20f, 60f, 45f, 120f, 90f, 160f, 140f, 110f, 180f, 150f, 130f, 170f)
        val up = listOf(5f, 12f, 9f, 20f, 15f, 30f, 22f, 18f, 35f, 25f, 20f, 28f)
        val holiday = HolidayCalendar.byId("ny")
        val fx = SeasonalFx(snowMode = if (snow && theme == ThemeMode.DARK) SnowMode.ON else SnowMode.OFF, snowCount = 50)
        val tabs = HomeFilters.tabs(allServers)
        val selectedTab = HomeFilters.resolveTab(tab, tabs)
        val countryScope = if (selectedTab.isBlank()) allServers else allServers.filter { HomeFilters.matchesTab(it, selectedTab) }
        val countries = HomeFilters.countries(countryScope, pings)
        val selectedCountry = HomeFilters.resolveCountry(country, countries)
        val filtersOn = HomeFilters.filterActive(selectedTab, selectedCountry)
        val shown = HomeFilters.apply(allServers, selectedTab, selectedCountry)
        val connected = vpn == ConnState.CONNECTED
        val (status, statusColor, dot, active) = when {
            problem || vpn == ConnState.ERROR -> Quad("Нет сети", AntiBSColors.Err, UiLogic.StatusDot.ERROR, connected)
            vpn == ConnState.CONNECTED -> Quad("Подключено", AntiBSColors.Ok, UiLogic.StatusDot.LIVE, true)
            vpn == ConnState.CONNECTING -> Quad("Подключение…", Color.Unspecified, UiLogic.StatusDot.CONNECTING, false)
            else -> Quad("Не подключено", Color.Unspecified, UiLogic.StatusDot.OFF, false)
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConnectionCard(
                        flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality",
                        protoTone = if (connected) UiLogic.ProtoChipTone.OK else UiLogic.ProtoChipTone.OFF,
                        active = active, dot = dot,
                        downSpeed = if (connected) "1.2 МБ/с" else "0 Б/с",
                        upSpeed = if (connected) "84.0 КБ/с" else "0 Б/с",
                        downTotal = if (connected) "152.4 МБ" else "0 Б",
                        upTotal = if (connected) "9.8 МБ" else "0 Б",
                        vpnSamples = if (connected) down else List(down.size) { 0f },
                        upSamples = if (connected) up else List(up.size) { 0f },
                        sampleSeq = down.size, sampleTimes = List(down.size) { t0 + it * 1000L },
                        downRate = if (connected) 1_258_291 else 0,
                        upRate = if (connected) 86_016 else 0,
                        statsVisible = true, pingMs = 38, pingStyle = net.antibs.client.data.PingChipStyle(),
                        directMs = 231, status = status, statusColor = statusColor,
                        subLine = net.antibs.client.data.SubLineData(
                            days = "32 дн.", until = "12 дек", dayFrac = 0.45f,
                            traffic = "12,6 / 150 ГБ", trafficFrac = 0.084f,
                        ),
                    )
                    UrlTestCard(mapOf("google" to 92L, "youtube" to 140L, "telegram" to 210L, "instagram" to -1L), false) {}
                    PowerToggle(
                        state = vpn,
                        since = if (connected) System.currentTimeMillis() - 754_000 else 0L,
                        problem = problem,
                        onClick = {},
                    )
                    Box(Modifier.fillMaxWidth()) {
                        ServersHeader(
                            style = ServerStyle.CLASSIC, layout = SubLayout.GROUPS, bestCard = false,
                            onStyle = {}, onLayout = {}, onBestCard = {},
                            sortMode = StylePrefs.SORT_ADDED, onSortMode = {}, subNewestFirst = true, onSubOrder = {},
                            filter = ServerFilter.ALL, onFilter = {},
                            testing = false, onTestAll = {},
                            onAdd = {},
                            subMenu = if (headerMenu) sub else null,
                            onSubRefresh = {}, onSubPing = {}, onSubCopy = {},
                            onSubDeleteUnreachable = {}, onSubRestoreHidden = {}, onSubDelete = {},
                            compact = true,
                            holiday = holiday,
                            holidayFx = fx,
                        )
                        if (headerMenu) {
                            ShotOverflowMenu(Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 0.dp), header = true)
                        }
                    }
                    HomeFiltersBar(
                        tabs = tabs, selectedTab = selectedTab, onTab = {},
                        countries = countries, selectedCountry = selectedCountry,
                        allCount = countries.size, onCountry = {},
                    )
                    val meta = when (selectedTab) {
                        HomeFilters.TAB_ANTIBLOCK -> "${shown.size} ${UiLogic.serversWord(shown.size)} · qWDTT / 4G"
                        else -> "${shown.size} ${UiLogic.serversWord(shown.size)}"
                    }
                    if (filtersOn) {
                        ShotSubHead(
                            if (selectedCountry.isNotEmpty()) {
                                val ctry = countries.first { it.code == selectedCountry }
                                listOf(selectedTab, ctry.name).filter { it.isNotBlank() }.joinToString(" · ")
                            } else selectedTab,
                            meta,
                        )
                        shown.take(8).forEachIndexed { i, s ->
                            ShotHostRow(s, pings[s.id], selected = i == 0)
                        }
                    } else {
                        ShotSubHead("AntiBS Premium", "${allServers.size} ${UiLogic.serversWord(allServers.size)}")
                        if (menu) {
                            Box(Modifier.fillMaxWidth()) {
                                SubCard(
                                    sub = sub, realServers = allServers.size, refreshing = false, pinging = false,
                                    nowSec = 1_762_732_800, expanded = true,
                                    onToggle = {}, onRefresh = {}, onPing = {}, onDelete = {}, onCopy = {},
                                )
                                ShotOverflowMenu(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 6.dp), header = false)
                            }
                        } else {
                            SubCard(
                                sub = sub, realServers = allServers.size, refreshing = false, pinging = false,
                                nowSec = 1_762_732_800, expanded = true,
                                onToggle = {}, onRefresh = {}, onPing = {}, onDelete = {}, onCopy = {},
                            )
                        }
                        shown.take(4).forEachIndexed { i, s ->
                            ShotHostRow(s, pings[s.id], selected = i == 0)
                        }
                    }
                }
                MainBottomBar(MainTab.Home) { }
            }
            if (fx.snowMode == SnowMode.ON) SeasonalOverlay(fx, snow = true, garland = true, topInset = 8.dp)
        }
    }

    private data class Quad(val status: String, val statusColor: Color, val dot: UiLogic.StatusDot, val active: Boolean)

    @Composable private fun ShotSubHead(title: String, meta: String) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }

    @Composable private fun ShotHostRow(s: ServerEntry, ping: Long?, selected: Boolean) {
        val shape = RoundedCornerShape(14.dp)
        val badge = HomeFilters.protocolTab(s) ?: if (HomeFilters.isAntiblock(s)) {
            if (s.engine == EngineKind.QWDTT && !s.name.contains("4G", true)) "qWDTT" else "4G"
        } else UiLogic.protocolGroup(s)
        Surface(
            shape = shape,
            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(if (selected) 2.dp else 1.dp, if (selected) AntiBSColors.Blue else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape),
        ) {
            Row(Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(if (selected) AntiBSColors.Blue else Color.Transparent)
                        .border(1.5.dp, if (selected) AntiBSColors.Blue else MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(9.dp))
                Text(UiLogic.flag(s.name), fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(UiLogic.displayName(s.name), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        badge,
                        color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(AntiBSColors.ChipOff).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    if (ping != null) Text("$ping мс", color = if (ping < 80) AntiBSColors.Ok else Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Filled.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(start = 2.dp))
            }
        }
    }


    @Composable private fun ShotOverflowMenu(modifier: Modifier = Modifier, header: Boolean = false) {
        val shape = RoundedCornerShape(12.dp)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = modifier.width(280.dp).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                @Composable fun RowItem(label: String, trailing: @Composable (() -> Unit)? = null) {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        if (trailing != null) trailing()
                    }
                }
                RowItem("Обновить")
                if (header) {
                    RowItem("Проверить пинг")
                    RowItem("Скопировать ссылку")
                } else {
                    RowItem("Копировать ссылку")
                    RowItem("Проверить пинг")
                }
                RowItem("Автоудаление недоступных") { Checkbox(checked = true, onCheckedChange = null) }
                RowItem("Удалить недоступные")
                RowItem("Удалить")
            }
        }
    }

    private fun saveBannerCrop(full: java.io.File) {
        val bmp = android.graphics.BitmapFactory.decodeFile(full.absolutePath) ?: return
        val w = 1056
        val h = 344
        val x = ((bmp.width - w) / 2).coerceAtLeast(0)
        val y = 24.coerceAtMost(bmp.height - 2)
        val cw = minOf(w, bmp.width - x)
        val ch = minOf(h, bmp.height - y)
        val cropped = Bitmap.createBitmap(bmp, x, y, cw, ch)
        val scaled = Bitmap.createScaledBitmap(cropped, cw * 2, ch * 2, true)
        java.io.File(dir, "banner_arrows_crop.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun connectingDark() = shot(ThemeMode.DARK, "home_connecting_dark") {
        home("", "", ThemeMode.DARK, vpn = ConnState.CONNECTING)
    }
    @Test fun connectedDark() = shot(ThemeMode.DARK, "home_connected_dark") {
        home("", "", ThemeMode.DARK) // no filter, subscription banner, green power
    }
    @Test fun problemDark() = shot(ThemeMode.DARK, "home_problem_dark") {
        home("", "", ThemeMode.DARK, problem = true)
    }
    @Test fun noFilterDark() = shot(ThemeMode.DARK, "home_no_filter_dark") {
        home("", "", ThemeMode.DARK)
    }
    @Test fun ipv4FlatDark() = shot(ThemeMode.DARK, "home_ipv4_flat_dark") {
        home("IPv4", "", ThemeMode.DARK)
    }
    @Test fun ipv4DeFlatDark() = shot(ThemeMode.DARK, "home_ipv4_de_flat_dark") {
        home("IPv4", "DE", ThemeMode.DARK)
    }
}
