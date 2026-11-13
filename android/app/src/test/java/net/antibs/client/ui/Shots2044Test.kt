package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import net.antibs.client.engine.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 2.0.44 previews: lower ping-check card, speed arrows under the left leg of «П». Run with SNAP=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class Shots2044Test {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File(System.getenv("SHOTS_DIR") ?: "/workspace/polish_shots/2044")

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue(System.getenv("SNAP") != null)
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) { content() } } }
        rule.mainClock.advanceTimeBy(700)
        out.mkdirs()
        var b: android.graphics.Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)); b = it }
        }
        File(out, "$name.png").outputStream().use { b!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val ping: @androidx.compose.runtime.Composable () -> Unit = {
        val ok = (1..15).map { "ok$it" }
        val pending = (1..3).map { "p$it" }
        PingCounterPill((ok + pending).toSet(), pending.toSet(), ok.associateWith { 80L }, onClose = {}, onStop = {})
    }
    private val card: @androidx.compose.runtime.Composable () -> Unit = {
        ConnectionCard(flag = "🇫🇮", name = "Финляндия", proto = "VLESS Reality", protoTone = UiLogic.ProtoChipTone.OK, active = true,
            dot = UiLogic.StatusDot.LIVE, downSpeed = "0.0 кбит/с", upSpeed = "0.0 кбит/с", downTotal = "1.2 МБ", upTotal = "0.3 МБ",
            vpnSamples = listOf(0f, 2f, 1f, 3f, 2f), sampleSeq = 5, upSamples = listOf(0f, 1f, 0f, 1f, 1f),
            status = "Подключено", statusColor = Color(0xFF22C55E), pingMs = 92, directMs = 231)
    }

    private val traffic: @androidx.compose.runtime.Composable () -> Unit = {
        val st = net.antibs.client.data.SubRemainStyle(show = true)
        val now = System.currentTimeMillis()
        val MB = 1024L * 1024; val GB = 1024 * MB
        fun sub(used: Long, total: Long) = net.antibs.client.data.Subscription(id = "s$used", url = "u", download = used, total = total, expireEpoch = now / 1000 + 86400 + 3600)
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubRemainRow(net.antibs.client.data.SubLineData.of(sub(195 * MB, 0), now)!!, st)
            SubRemainRow(net.antibs.client.data.SubLineData.of(sub(10 * GB, 100 * GB), now)!!, st)
            SubRemainRow(net.antibs.client.data.SubLineData.of(sub(85 * GB, 100 * GB), now)!!, st)
            TrafficBar(UiLogic.trafficBarLabel(UiLogic.Traffic(195.0, 0.0, true)), UiLogic.trafficRemaining(195.0, 0.0))
            TrafficBar(UiLogic.trafficBarLabel(UiLogic.Traffic(10 * 1024.0, 100 * 1024.0, true)), UiLogic.trafficRemaining(10 * 1024.0, 100 * 1024.0))
            TrafficBar(UiLogic.trafficBarLabel(UiLogic.Traffic(85 * 1024.0, 100 * 1024.0, true)), UiLogic.trafficRemaining(85 * 1024.0, 100 * 1024.0))
        }
    }
    @Test fun trafficLight() = shot("traffic_left_light", content = traffic)
    @Test fun trafficDark() = shot("traffic_left_dark", dark = true, content = traffic)
    @Test fun pingLight() = shot("ping_counter_light", content = ping)
    @Test fun pingDark() = shot("ping_counter_dark", dark = true, content = ping)
    @Test fun cardLight() = shot("home_banner_arrows_light", content = card)
    @Test fun cardDark() = shot("home_banner_arrows_dark", dark = true, content = card)
}

class StemCenterTest {
    @org.junit.Test fun firstLegOfPe() {
        // «П»: leg (cols 2–3), top bar (cols 4–7), leg (cols 8–9)
        val c = intArrayOf(0, 0, 10, 10, 2, 2, 2, 2, 10, 10, 0)
        org.junit.Assert.assertEquals(3f, UiLogic.stemCenter(c, longest = false)!!, 0.001f)
    }
    @org.junit.Test fun shaftOfArrow() {
        // «↓»: head wings shorter, shaft (cols 4–5) tallest
        val c = intArrayOf(0, 2, 3, 5, 12, 12, 5, 3, 2, 0)
        org.junit.Assert.assertEquals(5f, UiLogic.stemCenter(c, longest = true)!!, 0.001f)
    }
    @org.junit.Test fun empty() { org.junit.Assert.assertNull(UiLogic.stemCenter(IntArray(5), longest = true)) }
}
