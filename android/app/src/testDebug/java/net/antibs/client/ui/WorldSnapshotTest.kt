package net.antibs.client.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.antibs.client.engine.GeoPoint
import net.antibs.client.engine.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import java.time.Duration

/** Host-side renders of the real home globe / map composables (Robolectric native graphics). Runs only with SNAP=name. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h500dp-xxhdpi")
class WorldSnapshotTest {
    @get:org.junit.Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<ComponentActivity>()
    private val user = GeoPoint("93.184.0.10", "Москва", "Россия", "RU", 55.7558, 37.6173)
    private val exit = GeoPoint("45.12.0.77", "Франкфурт-на-Майне", "Германия", "DE", 50.1109, 8.6821)
    private val servers = listOf("🇩🇪 Франкфурт", "🇳🇱 Амстердам", "🇫🇮 Хельсинки", "🇸🇪 Стокгольм", "🇺🇸 Нью-Йорк", "🇹🇷 Стамбул", "🇰🇿 Алматы", "🇵🇱 Варшава")

    private fun snap(name: String, mode: String, theme: ThemeMode) {
        assumeTrue(System.getenv("SNAP") == name)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AntiBSTheme(theme) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp)) {
                    WorldSnapshot(user, exit, true, servers, "🇩🇪 Франкфурт", mode, Modifier.fillMaxSize())
                }
            }
        }
        repeat(12) { rule.mainClock.advanceTimeBy(160); Thread.sleep(120); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(16)
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val out = File("build/snapshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mapDark() = snap("map-dark", "map", ThemeMode.DARK)
    @Test fun mapLight() = snap("map-light", "map", ThemeMode.LIGHT)
    @Test fun globeDark() = snap("globe-dark", "globe", ThemeMode.DARK)
    @Test fun globeLight() = snap("globe-light", "globe", ThemeMode.LIGHT)
}
