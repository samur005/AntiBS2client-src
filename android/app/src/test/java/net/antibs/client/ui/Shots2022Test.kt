package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import net.antibs.client.data.UpdateLogic
import net.antibs.client.engine.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class Shots2022Test {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File(System.getenv("SHOTS_DIR") ?: "/workspace/polish_shots/2022")
    private val u = UpdateLogic.Update(UpdateLogic.Release("v2.0.22", "2.0.22", "", "", emptyList()), UpdateLogic.Asset("a.apk", "", 74_000_000), null)
    private val dl = AppViewModel.UpdateUi.Downloading(u, 0.42f, 31_100_000, 74_000_000, 2_350_000.0, 0, false)

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue(System.getenv("SNAP") != null)
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(ThemeMode.DARK) { Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) { content() } } }
        rule.mainClock.advanceTimeBy(700)
        out.mkdirs()
        var b: android.graphics.Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)); b = it }
        }
        val bmp = b!!
        File(out, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun full() = shot("update_full") { UpdateDownloadCard(dl, 0.42f, 13, 18, {}, {}) }
    @Test fun mini() = shot("update_minimized") { UpdateDownloadChip(dl, 0.42f, 18, {}) }
    @Test fun ping() = shot("ping_counter") {
        val ids = (1..42).map { "s$it" }.toSet()
        PingCounterPill(ids, ids.drop(30).toSet(), ids.take(30).associateWith { if (it in setOf("s3","s7","s11","s19","s25")) -1L else 80L }, {})
    }
}
