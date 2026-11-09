package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private val out = File(System.getenv("SHOTS_DIR") ?: "/workspace/polish_shots/2029")
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
        // ABS card mid-run: Проверено 10 из 65 · доступны 3 · недоступны 7 + stop
        val doneOk = (1..3).map { "ok$it" }
        val doneBad = (1..7).map { "bad$it" }
        val pending = (1..55).map { "p$it" }
        val batch = (doneOk + doneBad + pending).toSet()
        val pinging = pending.toSet()
        val pings = doneOk.associateWith { 80L } + doneBad.associateWith { -1L }
        PingCounterPill(batch, pinging, pings, onClose = {}, onStop = {}, stopped = false)
    }
    @Test fun pingDone() = shot("ping_counter_done") {
        val batch = (1..18).map { "s$it" }.toSet()
        val pings = batch.associateWith { if (it in setOf("s3","s7","s11","s19")) -1L else 80L }
        PingCounterPill(batch, emptySet(), pings, onClose = {}, stopped = false)
    }
    @Test fun ruAppsSettings() = shot("ru_apps_settings") {
        val a = net.antibs.client.data.AdvancedSettings(ruAppsOn = true)
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            Text("Маршрутизация", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Приложения", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Российские приложения в обход VPN", fontSize = 15.sp)
                    Text("Банки, Госуслуги, VK, Яндекс, маркетплейсы и операторы — мимо VPN", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = true, onCheckedChange = null)
            }
            Text("Найдено на устройстве: 12", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Text("Изменить список", color = AntiBSColors.Blue, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }

    private fun holiday(name: String, date: java.time.LocalDate) = shot(name) {
        val a = net.antibs.client.data.AdvancedSettings()
        val h = net.antibs.client.data.HolidayCalendar.current(date)!!
        val (snow, garland) = seasonalNow(a, date)
        Box(Modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Color(0xFF16222E))) {
            SeasonalOverlay(a.seasonalFx, snow, garland, 8.dp)
            SeasonalLabelText(h.emoji, h.name + "  ($date)", a.seasonalFx, Modifier.align(androidx.compose.ui.Alignment.Center))
        }
    }
    @Test fun holidayEid() = holiday("holiday_eid_fitr_2027-03-09", java.time.LocalDate.of(2027, 3, 9))
    @Test fun holidayVictory() = holiday("holiday_victory", java.time.LocalDate.of(2026, 5, 9))
    @Test fun holidayNy() = holiday("holiday_newyear", java.time.LocalDate.of(2027, 1, 1))
}
