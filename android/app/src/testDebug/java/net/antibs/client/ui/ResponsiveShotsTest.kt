package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.0.10 responsive renders (Home / Servers / Settings) at several screen sizes → /workspace/responsive_shots (run with ABS_SHOTS=1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ResponsiveShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("/workspace/responsive_shots")

    private fun shot(name: String, theme: ThemeMode = ThemeMode.DARK, fontScale: Float? = null, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale ?: d.fontScale)) {
                AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        out.mkdirs()
        val v = rule.activity.window.decorView.findViewById<android.view.View>(android.R.id.content)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val res = mapOf("google" to 212L, "telegram" to 184L, "instagram" to 540L, "youtube" to 96L)

    @Composable private fun home(state: ConnState, status: String, statusColor: Color, vpn: Long?, direct: Long?, problem: Boolean) {
        val connected = state == ConnState.CONNECTED
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ConnectionCard(
                flag = "🇩🇪", name = "Германия · Франкфурт", proto = "VLESS Reality", protoTone = if (problem) UiLogic.ProtoChipTone.ERR else UiLogic.ProtoChipTone.OK,
                active = connected, dot = if (connected) UiLogic.StatusDot.LIVE else UiLogic.StatusDot.OFF,
                downSpeed = "1.2 МБ/с", upSpeed = "84.0 КБ/с", downTotal = "152.4 МБ", upTotal = "9.8 МБ",
                directDownSpeed = "0.0 КБ/с", directUpSpeed = "0.0 КБ/с", directDownTotal = "3.1 МБ", directUpTotal = "0.4 МБ",
                vpnSamples = listOf(0.2f, 0.5f, 0.4f, 0.8f, 0.6f, 0.9f), directSamples = listOf(0.1f, 0.1f, 0.2f, 0.1f, 0.1f, 0.2f), sampleSeq = 6,
                statsVisible = connected, pingMs = vpn, pingStyle = net.antibs.client.data.PingChipStyle(),
                directMs = direct, status = status, statusColor = statusColor,
                subLine = null,
            )
            Spacer(Modifier.height(BANNER_TRIM))
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                PowerToggle(state = state, since = System.currentTimeMillis() - 754_000, problem = problem, onClick = {})
            }
            HomeWorld(
                input = WorldInput(connected, false, "Германия", listOf("Германия", "Нидерланды", "Финляндия"), 10808),
                modifier = Modifier.weight(1f).fillMaxWidth(), bleed = 12.dp, onNet = null, onSpeed = {},
                middle = { UrlTestCard(res, false) {} },
            )
        }
    }


    @Composable private fun settings() = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        CompositionLocalProvider(LocalCollapseByDefault provides true) {
            SettingsCard("Настройки интерфейса") {
                NavRow("Сезонные украшения", "Авто") {}
                NavRow("Тема оформления", "Тёмная", dropdown = true, subtitle = "Светлая, тёмная, чёрная или как в системе") {}
                SwitchRow("Вибрация", true, subtitle = "Отклик на нажатия") {}
                SwitchRow("URL Тест", true, subtitle = "Google, Telegram, Запретgram, YouTube") {}
                SwitchNavRow("Пинг серверов", "Автопинг каждые 30 с", true, {}, {})
            }
            SettingsCard("Настройки туннеля") { NavRow("Ядра и протоколы") {} }
            SettingsCard("Блокировка") { SwitchRow("Kill switch", false) {} }
            SettingsCard("Подписки") { NavRow("Автообновление") {} }
            SettingsCard("Конфиденциальность") { LockedOnRow("HWID", "Всегда включено") }
            SettingsCard("Инструменты") { NavRow("Логи") {} }
            SettingsCard("Приложение") { NavRow("О программе") {} }
        }
    }


    private val rows = listOf(Triple("🇩🇪 Германия · Франкфурт-на-Майне", "VLESS Reality", 38L), Triple("🇳🇱 Amsterdam", "Hysteria2", 72L),
        Triple("🇫🇮 Helsinki", "Trojan TLS", 140L), Triple("🇺🇸 New York", "VMess WS", 230L), Triple("🇯🇵 Tokyo", "TUIC", -1L))
    @Composable private fun servers() {
        val style = ServerStyle.CLASSIC
        val c = ssColors(style)
        Column(Modifier.fillMaxSize()) {
            ServersHeader(style, ServerStyleLogic.defaultOptions(style).layout, false, {}, {}, {}, StylePrefs.SORT_ADDED, {}, true, {}, ServerFilter.ALL, {}, false, {}, {},
                null, {}, {}, {}, {}, {}, {})
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CountryHeader("DE", 3, true, 42, true) {}
                rows.forEachIndexed { i, (n, p, ms) ->
                    Row(Modifier.fillMaxWidth().background(if (i == 0) c.selectedFill else c.card, ssShape(style, 14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n, color = c.onCard, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            ProtoTags(style, p)
                        }
                        SignalBars(ServerStyleLogic.bars(ms), SsPalette.ping(ms, c.dark), c.cardBorder)
                        Spacer(Modifier.width(8.dp))
                        StyledPing(style, ms, "мс", "таймаут")
                        FavoriteStar(i == 1, {}, c.muted)
                    }
                }
            }
        }
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi") fun home_320_mdpi() = shot("320_mdpi_home") { home(ConnState.CONNECTED, "Подключено", AntiBSColors.Blue, 84, 31, false) }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi") fun servers_320_mdpi() = shot("320_mdpi_servers") { servers() }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi") fun settings_320_mdpi() = shot("320_mdpi_settings") { settings() }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi") fun home_360_xhdpi() = shot("360_xhdpi_home") { home(ConnState.CONNECTED, "Подключено", AntiBSColors.Blue, 84, 31, false) }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi") fun servers_360_xhdpi() = shot("360_xhdpi_servers") { servers() }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi") fun settings_360_xhdpi() = shot("360_xhdpi_settings") { settings() }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi") fun home_360_font130() = shot("360_font130_home", fontScale = 1.3f) { home(ConnState.CONNECTED, "Подключено", AntiBSColors.Blue, 84, 31, false) }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi") fun servers_360_font130() = shot("360_font130_servers", fontScale = 1.3f) { servers() }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi") fun settings_360_font130() = shot("360_font130_settings", fontScale = 1.3f) { settings() }
    @Test @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") fun home_411_xxhdpi() = shot("411_xxhdpi_home") { home(ConnState.CONNECTED, "Подключено", AntiBSColors.Blue, 84, 31, false) }
    @Test @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") fun servers_411_xxhdpi() = shot("411_xxhdpi_servers") { servers() }
    @Test @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") fun settings_411_xxhdpi() = shot("411_xxhdpi_settings") { settings() }
    @Test @Config(sdk = [34], qualifiers = "w480dp-h1066dp-xxxhdpi") fun home_480_xxxhdpi() = shot("480_xxxhdpi_home") { home(ConnState.CONNECTED, "Подключено", AntiBSColors.Blue, 84, 31, false) }
    @Test @Config(sdk = [34], qualifiers = "w480dp-h1066dp-xxxhdpi") fun servers_480_xxxhdpi() = shot("480_xxxhdpi_servers") { servers() }
    @Test @Config(sdk = [34], qualifiers = "w480dp-h1066dp-xxxhdpi") fun settings_480_xxxhdpi() = shot("480_xxxhdpi_settings") { settings() }
    @Test @Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi") fun home_tablet_800() = shot("tablet_800_home") { home(ConnState.CONNECTED, "Подключено", AntiBSColors.Blue, 84, 31, false) }
    @Test @Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi") fun servers_tablet_800() = shot("tablet_800_servers") { servers() }
    @Test @Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi") fun settings_tablet_800() = shot("tablet_800_settings") { settings() }
}
