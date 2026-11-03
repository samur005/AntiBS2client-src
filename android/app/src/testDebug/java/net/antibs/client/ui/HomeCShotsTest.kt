package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.ThemeMode
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Off-device renders of the 2.0.8 home + settings → /workspace/home_c_shots (run with ABS_SHOTS=1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class HomeCShotsTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("/workspace/home_c_shots")

    private fun shot(name: String, theme: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        Assume.assumeTrue(System.getenv("ABS_SHOTS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
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

    @Test fun connected() = shot("1_home_connected") { home(ConnState.CONNECTED, "Подключено", AntiBSColors.Blue, 84, 31, false) }
    @Test fun serverDown() = shot("2_home_server_down") { home(ConnState.CONNECTED, "Сервер недоступен", AntiBSColors.Err, -1, 35, true) }
    @Test fun noInternet() = shot("3_home_no_internet") { home(ConnState.CONNECTED, "Нет интернета", AntiBSColors.Err, -1, -1, true) }
    @Test fun disconnected() = shot("4_home_disconnected") { home(ConnState.DISCONNECTED, "Не подключено", Color.Unspecified, null, 29, false) }

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

    @Test fun settingsDark() = shot("5_settings") { settings() }
    @Test fun settingsLight() = shot("6_settings_light", ThemeMode.LIGHT) { settings() }
}
