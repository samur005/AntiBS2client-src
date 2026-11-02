package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import net.antibs.client.data.*
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Off-device renders for the 2.0.6 ABS features → /workspace/abs_feat_shots (run with ABS_SHOTS=1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class AbsShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("/workspace/abs_feat_shots")

    private fun shot(name: String, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(ThemeMode.DARK) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(1600)
        out.mkdirs()
        val v = rule.activity.window.decorView.findViewById<android.view.View>(android.R.id.content)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val GB = 1024L * 1024 * 1024

    @Test fun banner() = shot("1_banner_remaining_ping") {
        val now = System.currentTimeMillis()
        val sub = Subscription(id = "s", url = "u", download = (1.4 * GB).toLong(), total = 150 * GB, expireEpoch = now / 1000 + 122 * 86400 + 60)
        Box(Modifier.padding(12.dp)) {
            ConnectionCard(flag = "🇫🇮", name = "Helsinki · ABS", proto = "VLESS", protoTone = UiLogic.ProtoChipTone.OK, active = true,
                dot = UiLogic.StatusDot.LIVE, downSpeed = "2.4 MB/s", upSpeed = "310 KB/s", downTotal = "1.2 GB", upTotal = "84 MB",
                directDownSpeed = "0 B/s", directUpSpeed = "0 B/s", directDownTotal = "12 MB", directUpTotal = "3 MB",
                vpnSamples = List(30) { (it % 7).toFloat() }, directSamples = List(30) { 0.5f }, sampleSeq = 30,
                pingMs = 131, pingStyle = PingChipStyle(), onPing = {}, subLine = SubLineData.of(sub, now), subStyle = SubRemainStyle())
        }
    }

    @Test fun countries() = shot("2_country_groups") {
        Column(Modifier.padding(12.dp)) {
            CountryHeader("FI", 4, true, 42, true) {}
            CountryHeader("DE", 3, false, 87, true) {}
            CountryHeader("NL", 2, false, null, true) {}
            CountryHeader(ServerGrouping.OTHER, 1, false, null, true) {}
        }
    }

    @Test fun schedule() = shot("3_schedule_settings") { SchedulePage {} }

    @Test fun snow() = shot("4_snow_garland") {
        Box(Modifier.fillMaxWidth().height(500.dp).background(Color(0xFF101828))) {
            SeasonalOverlay(SeasonalFx(snowCount = 60), snow = true, garland = true, topInset = 0.dp)
            SeasonalLabelText("🎄", "Новый год", SeasonalFx(labelColor = 0xFFFFC107L), Modifier.align(androidx.compose.ui.Alignment.Center), base = 18f)
        }
    }
}
