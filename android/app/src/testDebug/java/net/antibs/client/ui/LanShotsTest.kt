package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import net.antibs.client.data.LanProtocol
import net.antibs.client.data.Subscription
import net.antibs.client.engine.LanTransfer
import net.antibs.client.engine.ThemeMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onLast
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal object LanShotData {
    val subs = listOf(
        Subscription(id = "s1", url = "https://panel.example/sub/a", title = "Мой VPN"),
        Subscription(id = "s2", url = "https://vpn.example/s/b", title = "Резерв · Нидерланды"),
    )
    val peers = listOf(LanTransfer.Peer("192.168.1.23", "Pixel 8"), LanTransfer.Peer("192.168.1.40", "Xiaomi TV Box S"))
    val recv = LanTransfer.RecvState(running = true, ip = "192.168.1.40", code = "482913",
        session = LanProtocol.Session("192.168.1.40", 45571, "482913", ByteArray(32) { it.toByte() }, Long.MAX_VALUE))

    fun shot(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, androidx.activity.ComponentActivity>, dir: String, name: String,
             theme: ThemeMode = ThemeMode.DARK, crop: Int? = null, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(900)
        val out = File(dir).also { it.mkdirs() }
        val v = rule.activity.window.decorView.findViewById<android.view.View>(android.R.id.content)
        var bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        if (crop != null) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, minOf(crop, bmp.height))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class LanShotsPhoneTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = "/workspace/lan_shots"

    @Composable private fun banner(vpn: Long?, vk: Long?) = Column(Modifier.padding(12.dp)) {
        ConnectionCard(
            flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality", protoTone = UiLogic.ProtoChipTone.OK,
            active = true, dot = UiLogic.StatusDot.LIVE,
            downSpeed = "1.2 МБ/с", upSpeed = "84.0 КБ/с", downTotal = "152.4 МБ", upTotal = "9.8 МБ",
            directDownSpeed = "0.0 КБ/с", directUpSpeed = "0.0 КБ/с", directDownTotal = "3.1 МБ", directUpTotal = "0.4 МБ",
            vpnSamples = listOf(0.2f, 0.5f, 0.4f, 0.8f), directSamples = listOf(0.1f, 0.1f, 0.2f, 0.1f), sampleSeq = 4,
            statsVisible = true, pingMs = vpn, pingStyle = net.antibs.client.data.PingChipStyle(),
            directMs = vk, status = "Подключено", statusColor = AntiBSColors.Ok, subLine = null,
        )
        Spacer(Modifier.height(8.dp))
        UrlTestCard(mapOf("google" to 64L, "telegram" to 152L, "instagram" to 245L, "youtube" to 410L), false) {}
    }

    @Test fun bannerDark() = LanShotData.shot(rule, dir, "banner_dark", crop = 1150) { banner(84, 231) }
    @Test fun bannerLight() = LanShotData.shot(rule, dir, "banner_light", ThemeMode.LIGHT, crop = 1150) { banner(142, 37) }
    @Test fun addSheetPhone() {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        TvHooks.receiveFromPhone = { _ -> }
        val vm = AppViewModel(rule.activity.application)
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(ThemeMode.DARK) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AddServersSheet(vm, emptyList()) {} } } }
        rule.mainClock.advanceTimeBy(1500)
        val v = (rule.onAllNodes(androidx.compose.ui.test.isRoot()).onLast().fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view.rootView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF0B0E14.toInt()); v.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs(); File(dir, "add_sheet_phone.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        TvHooks.receiveFromPhone = null
    }
    @Test fun sendPhone() = LanShotData.shot(rule, dir, "send_phone") { LanSendContent(LanShotData.subs, {}, LanShotData.peers, preselect = "s2") }
    @Test fun recvPhone() = LanShotData.shot(rule, dir, "recv_phone") { LanRecvContent({ 0 }, {}, LanShotData.recv) }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class LanShotsTvTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = "/workspace/lan_shots"
    @Test fun recvTv() = LanShotData.shot(rule, dir, "recv_tv") { TvColumn(true) { LanRecvContent({ 0 }, {}, LanShotData.recv) } }
    @Test fun sendTv() = LanShotData.shot(rule, dir, "send_tv") { TvColumn(true) { LanSendContent(LanShotData.subs, {}, LanShotData.peers) } }
}
