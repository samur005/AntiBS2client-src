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
    private val dir = "/workspace/polish_shots"

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
}
