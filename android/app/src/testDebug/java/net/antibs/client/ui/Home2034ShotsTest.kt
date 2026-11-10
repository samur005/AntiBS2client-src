package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import net.antibs.client.data.HolidayCalendar
import net.antibs.client.data.SeasonalFx
import net.antibs.client.data.SnowMode
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0.34 Home previews (ABS_SHOTS=1): holiday strip in the list header (forced holiday id, no debug flags),
 * icons-only 62dp bottom bar, banner → URL test → power.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class Home2034ShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = "/workspace/polish_shots/2034"

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

    @Composable private fun home(holidayId: String? = null, snow: Boolean = false, remain: Boolean = false) = Box(Modifier.fillMaxSize()) {
        val t0 = android.os.SystemClock.uptimeMillis() - 20_000
        val down = listOf(20f, 60f, 45f, 120f, 90f, 160f, 140f, 110f, 180f, 150f, 130f, 170f)
        val up = listOf(5f, 12f, 9f, 20f, 15f, 30f, 22f, 18f, 35f, 25f, 20f, 28f)
        val holiday = holidayId?.let { HolidayCalendar.byId(it) }
        val fx = SeasonalFx(snowMode = if (snow) SnowMode.ON else SnowMode.OFF, snowCount = 50)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ConnectionCard(
                    flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality", protoTone = UiLogic.ProtoChipTone.OK,
                    active = remain, dot = UiLogic.StatusDot.LIVE,
                    downSpeed = "1.2 МБ/с", upSpeed = "84.0 КБ/с", downTotal = "152.4 МБ", upTotal = "9.8 МБ",
                    vpnSamples = down, upSamples = up, sampleSeq = down.size, sampleTimes = List(down.size) { t0 + it * 1000L },
                    statsVisible = true, pingMs = 84, pingStyle = net.antibs.client.data.PingChipStyle(),
                    directMs = 231, status = "Подключено", statusColor = AntiBSColors.Ok,
                    subLine = if (remain) net.antibs.client.data.SubLineData(
                        days = "32 дн.", until = "12 дек", dayFrac = 0.45f,
                        traffic = "12,6 / 150 ГБ", trafficFrac = 0.084f,
                    ) else null,
                )
                UrlTestCard(mapOf("google" to 92L, "youtube" to 140L, "telegram" to 210L, "instagram" to -1L), false) {}
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    PowerToggle(state = net.antibs.client.engine.ConnState.CONNECTED, since = System.currentTimeMillis() - 754_000, onClick = {})
                }
                ServersHeader(
                    style = ServerStyle.CLASSIC, layout = SubLayout.GROUPS, bestCard = false,
                    onStyle = {}, onLayout = {}, onBestCard = {},
                    sortMode = StylePrefs.SORT_ADDED, onSortMode = {}, subNewestFirst = true, onSubOrder = {},
                    filter = ServerFilter.ALL, onFilter = {},
                    testing = false, onTestAll = {},
                    onAdd = {},
                    subMenu = null,
                    onSubRefresh = {}, onSubPing = {}, onSubCopy = {},
                    onSubDeleteUnreachable = {}, onSubRestoreHidden = {}, onSubDelete = {},
                    compact = true,
                    holiday = holiday,
                    holidayFx = fx,
                )
            }
            MainBottomBar(MainTab.Home) { }
        }
        if (snow) SeasonalOverlay(fx, snow = true, garland = holidayId == "ny", topInset = 8.dp)
    }

    @Test fun newYearDark() = shot(ThemeMode.DARK, "home_ny_dark") { home("ny", snow = true) }
    @Test fun newYearLight() = shot(ThemeMode.LIGHT, "home_ny_light") { home("ny", snow = true) }
    @Test fun eidDark() = shot(ThemeMode.DARK, "home_eid_dark") { home("eid_fitr") }
    @Test fun eidLight() = shot(ThemeMode.LIGHT, "home_eid_light") { home("eid_fitr") }
    @Test fun womensDark() = shot(ThemeMode.DARK, "home_womens_dark") { home("womens") }
    @Test fun womensLight() = shot(ThemeMode.LIGHT, "home_womens_light") { home("womens") }
    @Test fun noneDark() = shot(ThemeMode.DARK, "home_none_dark") { home(null) }
    @Test fun noneLight() = shot(ThemeMode.LIGHT, "home_none_light") { home(null) }
    @Test fun remainDark() = shot(ThemeMode.DARK, "banner_remain_dark") { home(null, remain = true) }
    @Test fun remainLight() = shot(ThemeMode.LIGHT, "banner_remain_light") { home(null, remain = true) }
}

