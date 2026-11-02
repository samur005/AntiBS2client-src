package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** Off-device renders of the 2.0.7 home cards → /workspace/home_b_shots (run with ABS_SHOTS=1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h500dp-xxhdpi")
class HomeBShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("/workspace/home_b_shots")

    private fun shot(name: String, theme: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(600)
        out.mkdirs()
        val v = rule.activity.window.decorView.findViewById<android.view.View>(android.R.id.content)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val res = mapOf("google" to 212L, "telegram" to 184L, "instagram" to 540L, "youtube" to -1L)

    @Composable private fun cards(vpn: Long?, direct: Long?, busy: Boolean = false) =
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NetMonitorCard(vpn, direct)
            UrlTestCard(if (busy) mapOf("google" to 212L) else res, busy) {}
        }

    @Test fun connectedDark() = shot("1_connected_dark") { cards(861, 406) }
    @Test fun disconnectedDark() = shot("2_disconnected_dark") { cards(null, 95, busy = true) }
    @Test fun connectedLight() = shot("3_connected_light", ThemeMode.LIGHT) { cards(140, 406) }
}
