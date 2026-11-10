package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.0.32/2.0.33 home redesign previews (2.0.33: URL test → power → banner; icon-only bottom bar) (ABS_SHOTS=1): VPN-only banner (↓ blue / ↑ red, wide graph), compact URL test, text-only tabs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class Home2032ShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = "/workspace/polish_shots/2033"

    private fun shot(theme: ThemeMode, name: String, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(900)
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun home(tab: MainTab = MainTab.Home) = Column(Modifier.fillMaxSize()) {
        val t0 = android.os.SystemClock.uptimeMillis() - 20_000
        val down = listOf(20f, 60f, 45f, 120f, 90f, 160f, 140f, 110f, 180f, 150f, 130f, 170f)
        val up = listOf(5f, 12f, 9f, 20f, 15f, 30f, 22f, 18f, 35f, 25f, 20f, 28f)
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UrlTestCard(mapOf("google" to 92L, "youtube" to 140L, "telegram" to 210L, "instagram" to -1L), false) {}
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                PowerToggle(state = net.antibs.client.engine.ConnState.CONNECTED, since = System.currentTimeMillis() - 754_000, onClick = {})
            }
            ConnectionCard(
                flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality", protoTone = UiLogic.ProtoChipTone.OK,
                active = false, dot = UiLogic.StatusDot.LIVE,
                downSpeed = "1.2 МБ/с", upSpeed = "84.0 КБ/с", downTotal = "152.4 МБ", upTotal = "9.8 МБ",
                vpnSamples = down, upSamples = up, sampleSeq = down.size, sampleTimes = List(down.size) { t0 + it * 1000L },
                statsVisible = true, pingMs = 84, pingStyle = net.antibs.client.data.PingChipStyle(),
                directMs = 231, status = "Подключено", statusColor = AntiBSColors.Ok,
            )
        }
        MainBottomBar(tab) { }
    }

    @Test fun homeDark() = shot(ThemeMode.DARK, "home_dark") { home() }
    @Test fun homeLight() = shot(ThemeMode.LIGHT, "home_light") { home() }
    @Test fun barSpeedDark() = shot(ThemeMode.DARK, "bar_speed_selected_dark") { home(MainTab.Speed) }
    @Test fun barNetLight() = shot(ThemeMode.LIGHT, "bar_net_selected_light") { home(MainTab.Net) }
}
