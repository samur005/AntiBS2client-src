package net.antibs.client.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import net.antibs.client.engine.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host-side renders of the real settings / shop composables. Runs only with SNAP=name. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class SettingsSnapshotTest {
    @get:org.junit.Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<ComponentActivity>()

    private fun snap(name: String, theme: ThemeMode, body: @androidx.compose.runtime.Composable (AppViewModel) -> Unit) {
        assumeTrue(System.getenv("SNAP") == name)
        val vm = AppViewModel(rule.activity.application)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AntiBSTheme(theme) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { body(vm) } }
        }
        repeat(10) { rule.mainClock.advanceTimeBy(200); Thread.sleep(80); shadowOf(Looper.getMainLooper()).idle() }
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val out = File("build/snapshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settings(name: String, section: String, theme: ThemeMode = ThemeMode.DARK) =
        snap(name, theme) { vm -> SettingsHome(vm, vm.ui.value, {}, {}, section) }

    @Config(qualifiers = "w400dp-h2200dp-xxhdpi")
    @Test fun settingsMain() = settings("settings-main-dark", "Settings")
    @Config(qualifiers = "w400dp-h2200dp-xxhdpi")
    @Test fun settingsMainLight() = settings("settings-main-light", "Settings", ThemeMode.LIGHT)
    @Test fun settingsInterface() = settings("settings-interface-dark", "Interface")
    @Test fun settingsCores() = settings("settings-cores-dark", "SetCores")
    @Test fun settingsNet() = settings("settings-net-dark", "SetNet")
    @Test fun shopLoggedOut() = snap("shop-loggedout-dark", ThemeMode.DARK) { vm -> ShopScreen(vm) {} }
}
