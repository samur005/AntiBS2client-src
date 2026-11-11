package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.SessionLook
import net.antibs.client.engine.SessionLooks
import net.antibs.client.engine.SessionSpeedPhase
import net.antibs.client.engine.SessionSpeedProgress
import net.antibs.client.engine.ThemeMode
import net.antibs.client.engine.VpnState
import net.antibs.client.engine.VpnStatus
import org.junit.After
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0.41 Session shots (ABS_SHOTS=1): table with country flag, idle / in-progress / done speed test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h880dp-xxhdpi")
class Home2041ShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = "/workspace/polish_shots/2041"

    @After
    fun resetHooks() {
        ShotHooks.sessionLook = null
        ShotHooks.sessionSpeed = null
        ShotHooks.sessionBusy = false
        VpnState.set(VpnStatus())
    }

    private val fakeLook = SessionLook(
        ipv4 = "185.218.17.95",
        ipv6 = null,
        asn = "400992",
        provider = "ZhouyiSat Communications",
        country = SessionLooks.countryLine("Канада", "CA"),
        countryCode = "CA",
        city = "Ванкувер",
        region = "British Columbia",
        latitude = "49.2827",
        longitude = "-123.1207",
        viaTunnel = true,
    )

    private fun shot(theme: ThemeMode, name: String, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AntiBSTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(80)
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun sessionScreen(look: SessionLook, speed: SessionSpeedProgress) {
        ShotHooks.sessionLook = look
        ShotHooks.sessionSpeed = speed
        ShotHooks.sessionBusy = false
        VpnState.set(VpnStatus(state = ConnState.CONNECTED, serverName = "🇨🇦 Ванкувер"))
        val vm = AppViewModel(rule.activity.application)
        CompositionLocalProvider(LocalInTabs provides true) {
            SessionPage(vm, onBack = {})
        }
    }

    @Test fun sessionDark() {
        shot(ThemeMode.DARK, "session_dark") { sessionScreen(fakeLook, SessionSpeedProgress()) }
    }

    @Test fun sessionLight() {
        shot(ThemeMode.LIGHT, "session_light") { sessionScreen(fakeLook, SessionSpeedProgress()) }
    }

    @Test fun sessionSpeedProgressDark() {
        shot(ThemeMode.DARK, "session_speed_progress_dark") {
            sessionScreen(
                fakeLook,
                SessionSpeedProgress(phase = SessionSpeedPhase.Download, pingMs = 18, downMbps = 42.3, upMbps = null),
            )
        }
    }

    @Test fun sessionSpeedDoneDark() {
        shot(ThemeMode.DARK, "session_speed_done_dark") {
            sessionScreen(
                fakeLook,
                SessionSpeedProgress(phase = SessionSpeedPhase.Done, pingMs = 18, downMbps = 87.4, upMbps = 21.6),
            )
        }
    }
}
