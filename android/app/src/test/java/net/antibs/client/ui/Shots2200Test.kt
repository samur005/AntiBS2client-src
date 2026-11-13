package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.engine.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 2.2.0 previews: lower subscription traffic bar, tighter settings hints, smaller protocol chips. Run with SNAP=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class Shots2200Test {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File(System.getenv("SHOTS_DIR") ?: "/workspace/polish_shots/2200")

    private fun shot(name: String, dark: Boolean = false, pad: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue(System.getenv("SNAP") != null)
        rule.mainClock.autoAdvance = false
        rule.setContent { AntiBSTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(if (pad) 12.dp else 0.dp)) { content() } } }
        rule.mainClock.advanceTimeBy(700)
        out.mkdirs()
        var b: android.graphics.Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)); b = it }
        }
        File(out, "$name.png").outputStream().use { b!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val subs: @androidx.compose.runtime.Composable () -> Unit = {
        val now = System.currentTimeMillis() / 1000
        val MB = 1024L * 1024; val GB = 1024 * MB
        fun s(id: String, title: String, used: Long, total: Long, n: Int) = net.antibs.client.data.Subscription(
            id = id, url = "https://example.com/$id", title = title, download = used, total = total, expireEpoch = now + 86400 * 37, serverCount = n)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(s("a", "AntiBS Premium", 195 * MB, 0, 18), s("b", "Резерв", 27 * MB + 820 * 1024, 0, 6), s("c", "Lucx 150 ГБ", 42 * GB, 150 * GB, 12))
                .forEach { SubCard(it, it.serverCount, false, false, now, false, {}, {}, {}, {}, {}) }
        }
    }

    private val servers: @androidx.compose.runtime.Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Triple("🇷🇺 Москва qWDTT", "qWDTT", 41L), Triple("🇫🇮 Финляндия", "VLESS Reality", 92L), Triple("🇩🇪 Германия", "Hysteria2", 118L),
                Triple("🇳🇱 Нидерланды", "Snell v6", 135L), Triple("⚡ Автовыбор", "Авто", 77L)).forEachIndexed { i, (n, b, p) ->
                ServerRow(flag = n.substringBefore(' '), title = n.substringAfter(' '), subtitle = "", badge = b, ping = "$p мс",
                    pingColor = Color(0xFF22C55E), selected = i == 1, compact = false, onClick = {}, onPing = {})
            }
        }
    }

    private val settings: @androidx.compose.runtime.Composable () -> Unit = {
        SettingsScaffold("Блокировка рекламы", onBack = {}) {
            Column {
                SettingsCard {
                    SwitchRow("Блокировать рекламу", true, "Работает в туннеле Xray. Списки вредоносных, азартных сайтов и майнеров в базе приложения отсутствуют.") {}
                    SwitchRow("Блокировать телеметрию", false, "Блокирует сбор данных через туннель Xray.") {}
                }
                HintText("Работает в туннеле Xray. Списки вредоносных, азартных сайтов и майнеров в базе приложения отсутствуют, поэтому их здесь нет.")
                HintText("Пока блокировщик включён, он заменяет удалённый DNS из раздела «Сеть и DNS» (и профиля маршрутизации) для Xray. Выключите его — снова будет действовать выбранный там DNS.")
                Text("Обычный поясняющий текст без своего межстрочного интервала: раньше брал 24 sp из темы, теперь — 1.25 от размера шрифта.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    }

    @Test fun subsLight() = shot("subs_traffic_light", content = subs)
    @Test fun subsDark() = shot("subs_traffic_dark", dark = true, content = subs)
    @Test fun serversLight() = shot("server_chips_light", content = servers)
    @Test fun serversDark() = shot("server_chips_dark", dark = true, content = servers)
    @Test fun settingsLight() = shot("settings_hints_light", pad = false, content = settings)
    @Test fun settingsDark() = shot("settings_hints_dark", dark = true, pad = false, content = settings)
}
