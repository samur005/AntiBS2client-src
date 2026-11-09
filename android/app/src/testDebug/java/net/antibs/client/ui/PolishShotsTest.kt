package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.io.File
import net.antibs.client.data.ServerGrouping
import net.antibs.client.data.Subscription
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class PolishShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private var dir = "/workspace/polish_shots"

    @Composable private fun banner(vpn: Long?, vk: Long?, busy: Boolean) = Column(Modifier.padding(12.dp)) {
        ConnectionCard(
            flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality", protoTone = UiLogic.ProtoChipTone.OK,
            active = true, dot = UiLogic.StatusDot.LIVE,
            downSpeed = "1.2 МБ/с", upSpeed = "84.0 КБ/с", downTotal = "152.4 МБ", upTotal = "9.8 МБ",
            directDownSpeed = "0.0 КБ/с", directUpSpeed = "0.0 КБ/с", directDownTotal = "3.1 МБ", directUpTotal = "0.4 МБ",
            vpnSamples = listOf(0.2f, 0.5f, 0.4f, 0.8f), directSamples = listOf(0.1f, 0.1f, 0.2f, 0.1f), sampleSeq = 4,
            statsVisible = true, pingMs = vpn, pingStyle = net.antibs.client.data.PingChipStyle(),
            directMs = vk, status = "Подключено", statusColor = AntiBSColors.Ok, subLine = null,
            pingBusy = busy, directBusy = busy,
        )
    }

    private fun save(name: String, crop: Int? = null, popup: Boolean = false) {
        val v = if (popup) (rule.onAllNodes(isRoot()).onLast().fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view.rootView
                else rule.activity.window.decorView
        var bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        if (crop != null) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, minOf(crop, bmp.height))
        File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(theme: ThemeMode, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(900)
    }

    @Test fun bannerDark() { show(ThemeMode.DARK) { banner(84, 231, false) }; save("banner_dark", 700) }
    @Test fun bannerLight() { show(ThemeMode.LIGHT) { banner(142, 37, false) }; save("banner_light", 700) }
    @Test fun bannerPinging() { show(ThemeMode.DARK) { banner(84, 231, true) }; save("banner_pinging_dark", 700) }

    @Test fun subMenu() {
        val vm = AppViewModel(rule.activity.application)
        val sub = Subscription(id = "s1", url = "https://panel.example/sub/a", title = "Мой VPN",
            hiddenHosts = listOf("h1"), webPageUrl = "https://panel.example", supportUrl = "https://t.me/x")
        show(ThemeMode.DARK) {
            Column(Modifier.padding(12.dp)) {
                SubCard(sub, 12, false, false, 0, true, {}, {}, {}, {}, {}, extraMenu = { close ->
                    GroupingMenuItems(vm, ServerGrouping(), sub.id, close)
                    DropdownMenuItem(text = { Text("Передать на устройство") }, leadingIcon = { MenuIcon(Icons.Filled.SendToMobile) }, onClick = close)
                })
            }
        }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("Меню").performClick()
        rule.waitForIdle()
        save("sub_menu_dark", popup = true)
    }

    // ───────── 2.0.15 ─────────
    private fun d15() { dir = "/workspace/polish_shots/2015" }
    private fun live(theme: ThemeMode, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.waitForIdle()
    }
    @Test fun s15BannerIdle() { dir = "/workspace/polish_shots/2016"; show(ThemeMode.DARK) { banner(84, 231, false) }; save("banner_idle_dark", 700) }
    @Test fun s15BannerPinging() { dir = "/workspace/polish_shots/2016"; show(ThemeMode.DARK) { banner(84, 231, true) }; save("banner_pinging_dark", 700) }
    @Test fun s15BannerTimeout() { dir = "/workspace/polish_shots/2016"; show(ThemeMode.DARK) { banner(-1, 9999, false) }; save("banner_timeout_dark", 700) }
    @Config(qualifiers = "w400dp-h2600dp-xxhdpi")
    @Test fun s16AdBlockInline() {
        dir = "/workspace/polish_shots/2016"
        val f = File(rule.activity.filesDir, net.antibs.client.data.AdBlockCounter.COUNTS_NAME)
        net.antibs.client.data.AdBlockCounter.save(f, net.antibs.client.data.AdBlockCounter.Counts(net.antibs.client.data.AdBlockCounter.dayKey(), 137, 4821, true))
        val vm = AppViewModel(rule.activity.application)
        vm.setAdvTunnel { it.copy(blockAds = true, adCats = listOf("tm_windows"), adDnsOn = true, adDnsUrl = net.antibs.client.data.AdBlock.DNS_PRESETS.first().variants[1].url) }
        live(ThemeMode.DARK) { SettingsHome(vm, vm.ui.value, {}, {}, "Settings") }
        Thread.sleep(2500); rule.waitForIdle()
        save("settings_collapsed_dark")
        rule.onNodeWithText("Блокировка рекламы").performClick(); rule.waitForIdle()
        save("adblock_expanded_full_dark")
    }
    @Test fun s15SettingsCollapsed() {
        d15(); val vm = AppViewModel(rule.activity.application)
        live(ThemeMode.DARK) { SettingsHome(vm, vm.ui.value, {}, {}, "Settings") }
        save("settings_collapsed_dark")
        rule.onNodeWithText("Блокировка рекламы").performClick(); rule.waitForIdle()
        save("settings_expanded_dark")
    }

    // ───────── 2.0.30 ─────────
    @Config(qualifiers = "w400dp-h2800dp-xxhdpi")
    @Test fun s30AdBlockAndSettings() {
        dir = "/workspace/polish_shots/2030"
        val f = File(rule.activity.filesDir, net.antibs.client.data.AdBlockCounter.COUNTS_NAME)
        net.antibs.client.data.AdBlockCounter.save(f, net.antibs.client.data.AdBlockCounter.Counts(net.antibs.client.data.AdBlockCounter.dayKey(), 42, 1280, true))
        val vm = AppViewModel(rule.activity.application)
        vm.setAdvTunnel { it.copy(blockAds = true, adCats = listOf("trackers", "tm_windows"), adDnsOn = false) }
        live(ThemeMode.DARK) { SettingsHome(vm, vm.ui.value, {}, {}, "Settings") }
        Thread.sleep(1500); rule.waitForIdle()
        save("settings_collapsed_dark")
        rule.onNodeWithText("Блокировка рекламы").performClick(); rule.waitForIdle(); Thread.sleep(400)
        save("adblock_section_dark")
        rule.onNodeWithText("Настройки туннеля").performClick(); rule.waitForIdle(); Thread.sleep(300)
        save("tunnel_section_dark")
        rule.onNodeWithText("Настройки интерфейса").performClick(); rule.waitForIdle(); Thread.sleep(300)
        save("interface_section_dark")
        // denser layout: adblock + tunnel + interface already captured above
    }
    @Config(qualifiers = "w400dp-h2200dp-xxhdpi")
    @Test fun s30InterfaceSubpage() {
        dir = "/workspace/polish_shots/2030"
        val vm = AppViewModel(rule.activity.application)
        live(ThemeMode.DARK) { SettingsHome(vm, vm.ui.value, {}, {}, "Interface") }
        Thread.sleep(800); rule.waitForIdle()
        save("interface_subpage_dark")
    }
}
