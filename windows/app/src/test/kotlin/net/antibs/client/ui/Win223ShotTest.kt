package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import net.antibs.client.data.PowerButtonStyle
import net.antibs.client.data.TrafficBarStyle
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.SessionLooks
import net.antibs.client.engine.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class Win223ShotTest {
    private val dir = File("/workspace/polish_shots/win223").also { it.mkdirs() }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.png(): ByteArray {
        val img = captureToImage().asSkiaBitmap()
        return Image.makeFromBitmap(img).encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @Test fun sessionScreen() = runComposeUiTest {
        val lookCountry = SessionLooks.countryLine("Нидерланды", "NL")
        setContent {
            AntiBSTheme(ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val c = ssColors(LocalAppStyle.current)
                    val shape = ssShape(LocalAppStyle.current, 16.dp)
                    Column(Modifier.width(720.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        Text("Сессия", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(16.dp))
                        Surface(shape = shape, color = c.card, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).border(1.dp, c.cardBorder, shape)) {
                            val rows = listOf(
                                "IPv4" to "185.248.193.42",
                                "IPv6" to "2a0b:4140:5a::15",
                                "ASN" to "210644",
                                "Провайдер" to "AEZA INTERNATIONAL LTD",
                                "Страна" to lookCountry,
                                "Город" to "Амстердам",
                                "Регион" to "North Holland",
                            )
                            Column {
                                rows.forEachIndexed { i, (label, value) ->
                                    val link = label == "Провайдер"
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, color = c.muted, fontSize = 13.sp)
                                        androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                                        Text(
                                            value ?: "—",
                                            color = if (link) c.accent else c.onCard,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            textAlign = TextAlign.End,
                                            textDecoration = if (link) TextDecoration.Underline else TextDecoration.None,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (i < rows.lastIndex) androidx.compose.material3.HorizontalDivider(thickness = 0.6.dp, color = c.cardBorder)
                                }
                            }
                        }
                        Text(
                            "Сведения получены через текущий VPN-туннель. Запрос выполняется только при открытии этого экрана или по кнопке обновления — в фоне данные не собираются.",
                            color = c.muted, fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
                        )
                        SectionLabel("ТЕСТ СКОРОСТИ")
                        Surface(shape = shape, color = c.card, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).border(1.dp, c.cardBorder, shape)) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.LinearProgressIndicator(progress = { 0.62f }, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), color = c.accent, trackColor = c.accent.copy(alpha = 0.18f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
                                        Text("Пинг", color = c.muted, fontSize = 12.sp)
                                        Text("18", color = c.onCard, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                        Text("мс", color = c.muted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
                                        Text("Загрузка", color = c.muted, fontSize = 12.sp)
                                        Text("86.4", color = c.onCard, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                        Text("Мбит/с", color = c.muted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
                                        Text("Отдача", color = c.muted, fontSize = 12.sp)
                                        Text("—", color = c.onCard, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                        Text("Мбит/с", color = c.muted, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                androidx.compose.material3.Button(onClick = {}, modifier = Modifier.fillMaxWidth().height(52.dp), shape = shape) {
                                    Text("Стоп", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        dir.resolve("session_screen.png").writeBytes(onRoot().png())
    }

    @Test fun powerButtonSettings() = runComposeUiTest {
        mainClock.autoAdvance = false
        val since = System.currentTimeMillis() - 754_000
        setContent {
            AntiBSTheme(ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.width(720.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        Text("Кнопка питания", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(16.dp))
                        SettingsCard("Просмотр") {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                PowerButtonStyle.effectOptions.forEach { (id, label) ->
                                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                                    PowerToggle(
                                        state = ConnState.CONNECTED,
                                        since = since,
                                        onClick = {},
                                        haptics = false,
                                        prefs = PowerButtonStyle(effect = id),
                                    )
                                }
                            }
                        }
                        SettingsCard("Живой эффект") {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PowerButtonStyle.effectOptions.forEach { (id, label) ->
                                        FilterChip(selected = id == PowerButtonStyle.EFFECT_BREATH, onClick = {}, label = { Text(label, maxLines = 1) }, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(900)
        waitForIdle()
        dir.resolve("power_button_settings.png").writeBytes(onRoot().png())
    }

    @Test fun trafficBarSettings() = runComposeUiTest {
        val unlimited = UiLogic.Traffic(0.0, 0.0, true)
        val mid = UiLogic.Traffic(12.6 * 1024.0, 150 * 1024.0, true)
        val low = UiLogic.Traffic(85 * 1024.0, 100 * 1024.0, true)
        setContent {
            AntiBSTheme(ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.width(720.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        Text("Полоса трафика", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(16.dp))
                        SettingsCard("Просмотр · авто 14 dp") {
                            val p = TrafficBarStyle()
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TrafficBar(UiLogic.trafficBarLabel(unlimited), 1f, p)
                                TrafficBar(UiLogic.trafficBarLabel(mid), 1f - 12.6f / 150f, p)
                                TrafficBar(UiLogic.trafficBarLabel(low), 0.15f, p)
                            }
                        }
                        SettingsCard("Низкая полоса 6 dp, текст под ней") {
                            val p = TrafficBarStyle(heightDp = 6, color = "teal", gradient = TrafficBarStyle.GRADIENT_ON)
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TrafficBar(UiLogic.trafficBarLabel(mid), 1f - 12.6f / 150f, p)
                            }
                        }
                        SettingsCard("Сплошная · акцент 16 dp") {
                            val p = TrafficBarStyle(heightDp = 16, color = TrafficBarStyle.COLOR_ACCENT, gradient = TrafficBarStyle.GRADIENT_OFF)
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                TrafficBar(UiLogic.trafficBarLabel(mid), 1f - 12.6f / 150f, p)
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        dir.resolve("traffic_bar_settings.png").writeBytes(onRoot().png())
        dir.resolve("traffic_bar_styles.png").writeBytes(onRoot().png())
    }

    @Test fun homePinnedHeader() = runComposeUiTest {
        mainClock.autoAdvance = false
        val since = System.currentTimeMillis() - 3_600_000
        setContent {
            AntiBSTheme(ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.width(720.dp).height(900.dp)) {
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("🇳🇱 Amsterdam · AEZA", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text("VLESS Reality  ·  ↓ 12.4 МБ/с   ↑ 1.1 МБ/с", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            PowerToggle(state = ConnState.CONNECTED, since = since, onClick = {}, haptics = false, prefs = PowerButtonStyle())
                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Подписки и серверы", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("Проверить все", fontSize = 13.sp, color = AntiBSColors.Blue)
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "AntiBS · 0 ГБ / ∞" to 1f,
                                "Личная · 42 ГБ / 200 ГБ" to 0.79f,
                                "Work · 18 ГБ / 50 ГБ" to 0.64f,
                                "NL #12" to null,
                                "DE #3" to null,
                                "FI #8" to null,
                            ).forEach { (title, bar) ->
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(title, fontWeight = FontWeight.SemiBold)
                                        if (bar != null) TrafficBar(title.substringAfter("· ").trim(), bar)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(900)
        waitForIdle()
        dir.resolve("home_pinned.png").writeBytes(onRoot().png())
    }

    @Test fun importTiles() = runComposeUiTest {
        setContent {
            AntiBSTheme(ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.width(720.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Добавить серверы", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ShotTile(Modifier.weight(1f), Icons.Filled.ContentPaste, Color(0xFF4C8DFF), "Из буфера", "WG / AWG .conf, ссылки")
                            ShotTile(Modifier.weight(1f), Icons.Filled.InsertDriveFile, Color(0xFF5D8E67), "Из файла", "WG / AWG .conf, OpenVPN, ссылки")
                            ShotTile(Modifier.weight(1f), Icons.Filled.QrCodeScanner, Color(0xFF1FA89A), "QR из файла", "Картинка с кодом")
                        }
                    }
                }
            }
        }
        waitForIdle()
        dir.resolve("import_wg_awg.png").writeBytes(onRoot().png())
    }
}

@androidx.compose.runtime.Composable
private fun ShotTile(modifier: Modifier, icon: ImageVector, accent: Color, title: String, subtitle: String) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent)
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
