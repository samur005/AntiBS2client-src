package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
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
import java.io.File
import net.antibs.client.data.EngineKind
import net.antibs.client.data.HolidayCalendar
import net.antibs.client.data.SeasonalFx
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.SnowMode
import net.antibs.client.data.Subscription
import net.antibs.client.data.TrafficBarStyle
import net.antibs.client.data.XrayProfile
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.2.1 shots (ABS_SHOTS=1): pinned Home header, traffic-bar settings and styles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h880dp-xxhdpi")
class Home221ShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = "/workspace/polish_shots/221"

    private fun shot(theme: ThemeMode, name: String, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(80)
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val gb = 1_000_000_000L
    private val sub = Subscription(
        id = "s1", url = "https://example/sub", title = "AntiBS Premium",
        expireEpoch = 1_766_448_000, download = 12_600_000_000L, total = 150L * gb,
    )
    private val sub2 = Subscription(
        id = "s2", url = "https://example/work", title = "Work VPN",
        expireEpoch = 1_766_448_000, download = 41L * gb, total = 50L * gb,
    )

    private fun xray(id: String, name: String, protocol: String = "vless", security: String? = "reality", subId: String = "s1") =
        ServerEntry(id, EngineKind.XRAY, name, subId, xray = XrayProfile(name, protocol, "example.com", 443, network = "tcp", security = security))

    private val allServers = listOf(
        xray("de1", "🇩🇪 Франкфурт"),
        xray("de2", "🇩🇪 Берлин"),
        xray("de3", "🇩🇪 Мюнхен", protocol = "trojan", security = null),
        xray("de4", "🇩🇪 Гамбург", protocol = "trojan", security = null),
        xray("nl1", "🇳🇱 Амстердам"),
        xray("nl2", "🇳🇱 Роттердам"),
        xray("fr1", "🇫🇷 Лион"),
        xray("at1", "🇦🇹 Вена"),
        xray("ch1", "🇨🇭 Цюрих"),
        xray("it1", "🇮🇹 Милан"),
        xray("gb1", "🇬🇧 Лондон", subId = "s2"),
        xray("gb2", "🇬🇧 Манчестер", subId = "s2"),
        xray("us1", "🇺🇸 Нью-Йорк", protocol = "shadowsocks", security = null, subId = "s2"),
        xray("jp1", "🇯🇵 Токио", protocol = "wireguard", security = null, subId = "s2"),
        xray("sg1", "🇸🇬 Сингапур", protocol = "tuic", security = null, subId = "s2"),
    )
    private val pings = mapOf(
        "de1" to 38L, "de2" to 44L, "de3" to 51L, "de4" to 57L, "nl1" to 48L, "nl2" to 55L,
        "fr1" to 81L, "at1" to 49L, "ch1" to 47L, "it1" to 72L, "gb1" to 66L, "gb2" to 70L,
        "us1" to 120L, "jp1" to 210L, "sg1" to 180L,
    )

    @Composable
    private fun pinnedHome(scrollFrom: Int) {
        val t0 = android.os.SystemClock.uptimeMillis() - 20_000
        val down = listOf(20f, 60f, 45f, 120f, 90f, 160f, 140f, 110f, 180f, 150f, 130f, 170f)
        val up = listOf(5f, 12f, 9f, 20f, 15f, 30f, 22f, 18f, 35f, 25f, 20f, 28f)
        val holiday = HolidayCalendar.byId("ny")
        val fx = SeasonalFx(snowMode = SnowMode.OFF)
        val tabs = HomeFilters.tabs(allServers)
        val countries = HomeFilters.countries(allServers, pings)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = scrollFrom)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ConnectionCard(
                    flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality",
                    protoTone = UiLogic.ProtoChipTone.OK, active = true, dot = UiLogic.StatusDot.LIVE,
                    downSpeed = "1.2 МБ/с", upSpeed = "84.0 КБ/с", downTotal = "152.4 МБ", upTotal = "9.8 МБ",
                    vpnSamples = down, upSamples = up, sampleSeq = down.size,
                    sampleTimes = List(down.size) { t0 + it * 1000L },
                    downRate = 1_258_291, upRate = 86_016, statsVisible = true,
                    pingMs = 38, pingStyle = net.antibs.client.data.PingChipStyle(),
                    directMs = 231, status = "Подключено", statusColor = AntiBSColors.Ok,
                    subLine = net.antibs.client.data.SubLineData("32 дн.", "12 дек", 0.45f, "12,6 / 150 ГБ", 0.916f),
                )
                PowerToggle(state = ConnState.CONNECTED, since = System.currentTimeMillis() - 754_000, onClick = {})
                ServersHeader(
                    style = ServerStyle.CLASSIC, layout = SubLayout.GROUPS, bestCard = false,
                    onStyle = {}, onLayout = {}, onBestCard = {},
                    sortMode = StylePrefs.SORT_ADDED, onSortMode = {}, subNewestFirst = true, onSubOrder = {},
                    filter = ServerFilter.ALL, onFilter = {},
                    testing = false, onTestAll = {}, onAdd = {},
                    subMenu = null,
                    onSubRefresh = {}, onSubPing = {}, onSubCopy = {},
                    onSubDeleteUnreachable = {}, onSubRestoreHidden = {}, onSubDelete = {},
                    compact = true, holiday = holiday, holidayFx = fx,
                )
                HomeFiltersBar(
                    tabs = tabs, selectedTab = "", onTab = {},
                    countries = countries, selectedCountry = "",
                    allCount = countries.size, serverCount = allServers.size, onCountry = {},
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 3.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item("sub-s1") {
                    SubCard(sub, realServers = 10, refreshing = false, pinging = false, nowSec = 1_762_732_800, expanded = true,
                        onToggle = {}, onRefresh = {}, onPing = {}, onDelete = {}, onCopy = {})
                }
                items(allServers.filter { it.subscriptionId == "s1" }, key = { it.id }) { s ->
                    ShotHostRow(s, pings[s.id], selected = s.id == "de1")
                }
                item("sub-s2") {
                    SubCard(sub2, realServers = 5, refreshing = false, pinging = false, nowSec = 1_762_732_800, expanded = true,
                        onToggle = {}, onRefresh = {}, onPing = {}, onDelete = {}, onCopy = {},
                        barStyle = TrafficBarStyle(heightDp = 16, color = "green", gradient = "on", color2 = "teal"))
                }
                items(allServers.filter { it.subscriptionId == "s2" }, key = { it.id }) { s ->
                    ShotHostRow(s, pings[s.id], selected = false)
                }
            }
            MainBottomBar(MainTab.Home) { }
        }
    }

    @Composable private fun ShotHostRow(s: ServerEntry, ping: Long?, selected: Boolean) {
        val badge = HomeFilters.protocolTab(s) ?: if (HomeFilters.isAntiblock(s)) {
            if (s.engine == EngineKind.QWDTT && !s.name.contains("4G", true)) "qWDTT" else "4G"
        } else UiLogic.protocolGroup(s)
        val shape = RoundedCornerShape(14.dp)
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
                    ProtocolBadge(badge, selected)
                    if (ping != null) Text("$ping мс", color = if (ping < 80) AntiBSColors.Ok else Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Filled.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(start = 2.dp))
            }
        }
    }

    @Test fun homePinnedScrollDark() {
        // Mid-list: first subscription servers have scrolled away; pinned banner / power / chips stay.
        shot(ThemeMode.DARK, "home_pinned_scroll_dark") { pinnedHome(scrollFrom = 8) }
    }

    @Test fun trafficBarSettingsDark() {
        shot(ThemeMode.DARK, "traffic_bar_settings_dark") {
            val vm = AppViewModel(rule.activity.application)
            TrafficBarPage(vm, onBack = {})
        }
    }

    @Test fun trafficBarStylesDark() {
        shot(ThemeMode.DARK, "traffic_bar_styles_dark") {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("По умолчанию (14 dp, авто)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                SubCard(sub, realServers = 10, refreshing = false, pinging = false, nowSec = 1_762_732_800, expanded = true,
                    onToggle = {}, onRefresh = {}, onPing = {}, onDelete = {}, onCopy = {})
                Text("16 dp, зелёный → бирюзовый", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                SubCard(sub2, realServers = 5, refreshing = false, pinging = false, nowSec = 1_762_732_800, expanded = true,
                    onToggle = {}, onRefresh = {}, onPing = {}, onDelete = {}, onCopy = {},
                    barStyle = TrafficBarStyle(heightDp = 16, color = "green", gradient = "on", color2 = "teal"))
                Text("6 dp, оранжевый, без градиента (подпись под полосой)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                SubCard(
                    Subscription(id = "s3", url = "u", title = "Free", download = 3L * gb, total = 0),
                    realServers = 3, refreshing = false, pinging = false, nowSec = 1_762_732_800, expanded = true,
                    onToggle = {}, onRefresh = {}, onPing = {}, onDelete = {}, onCopy = {},
                    barStyle = TrafficBarStyle(heightDp = 6, color = "orange", gradient = "off"),
                )
            }
        }
    }
}
