package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onLast
import androidx.compose.ui.unit.dp
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 2.2.0: app-wide line height — previews of non-settings screens. Run with SNAP=1 (SHOTS_DIR overrides the folder). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class AllScreens2200Test {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = System.getenv("SHOTS_DIR") ?: "/workspace/polish_shots/2200/allscreens"

    private fun shot(name: String, theme: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("SNAP") != null)
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(1500)
        val v = (rule.onAllNodes(androidx.compose.ui.test.isRoot()).onLast().fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view.rootView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(if (theme == ThemeMode.LIGHT) 0xFFF3F4F6.toInt() else 0xFF0B0E14.toInt()); v.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun addServers() {
        TvHooks.receiveFromPhone = { _ -> }
        val vm = AppViewModel(rule.activity.application)
        shot("add_servers_dark") { AddServersSheet(vm, emptyList()) {} }
        TvHooks.receiveFromPhone = null
    }
    @Test fun homeBanner() = shot("home_banner_light", ThemeMode.LIGHT) {
        Column(Modifier.padding(12.dp)) {
            ConnectionCard(
                flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality", protoTone = UiLogic.ProtoChipTone.OK,
                active = true, dot = UiLogic.StatusDot.LIVE, downSpeed = "1.2 МБ/с", upSpeed = "84.0 КБ/с", downTotal = "152.4 МБ", upTotal = "9.8 МБ",
                vpnSamples = listOf(0.2f, 0.5f, 0.4f, 0.8f), sampleSeq = 4, upSamples = listOf(0.1f, 0.2f, 0.1f, 0.3f),
                pingMs = 84, directMs = 231, status = "Подключено", statusColor = AntiBSColors.Ok)
            Spacer(Modifier.height(8.dp))
            UrlTestCard(mapOf("google" to 64L, "telegram" to 152L, "instagram" to 245L, "youtube" to 410L), false) {}
        }
    }
    @Test fun speedTest() {
        val vm = AppViewModel(rule.activity.application)
        shot("speed_test_dark") { SpeedTestPage(vm) {} }
    }
    @Test fun shop() {
        val vm = AppViewModel(rule.activity.application)
        shot("shop_light", ThemeMode.LIGHT) { ShopScreen(vm) {} }
    }
    @Test fun lanRecvLight() = shot("lan_recv_light", ThemeMode.LIGHT) { LanRecvContent({ 0 }, {}, LanShotData.recv) }
}
