package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import net.antibs.client.engine.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsUiShotTest {
    private val dir = File("/workspace/win_shots/2030").also { it.mkdirs() }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.png(): ByteArray {
        val img = captureToImage().asSkiaBitmap()
        return Image.makeFromBitmap(img).encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @Test fun settingsChrome() = runComposeUiTest {
        setContent {
            AntiBSTheme(ThemeMode.DARK) {
                CompositionLocalProvider(LocalCollapseByDefault provides true) {
                    Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Column(Modifier.width(720.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                            SettingsCard("Настройки интерфейса") {
                                NavRow("Тема оформления", "Тёмная", dropdown = true, subtitle = "Светлая, тёмная, чёрная или как в системе") {}
                                NavRow("Язык", "Русский", dropdown = true, subtitle = "Язык интерфейса приложения") {}
                                SwitchRow("Компактный список серверов", true) {}
                            }
                            SettingsCard("Настройки туннеля") {
                                NavRow("Режим работы", "VPN", dropdown = true, subtitle = "TUN → Xray") {}
                                NavRow("MTU туннеля", "1500", dropdown = true) {}
                                SwitchRow("Локальный DNS", false, subtitle = "Резолвить имена внутри туннеля") {}
                            }
                            SettingsCard("Блокировка рекламы") {
                                SectionLabel("Заблокировано")
                                AdBlockCountTiles(
                                    net.antibs.client.data.AdBlockCounter.Counts(
                                        net.antibs.client.data.AdBlockCounter.dayKey(), 42, 1280, true
                                    ),
                                    null,
                                )
                                SwitchRow("Блокировать рекламу и трекеры", true, subtitle = "Отбрасывает известные рекламные и отслеживающие домены внутри туннеля") {}
                                SwitchRow("Трекеры и аналитика", true) {}
                                NavRow("Резолвер", "AdGuard · DoH", dropdown = true, subtitle = "AdGuard, Mullvad, ControlD или свой адрес") {}
                            }
                            SettingsCard("Конфиденциальность") {
                                SwitchRow("Скрывать IP серверов", false) {}
                                SwitchRow("Логирование", true) {}
                                NavRow("Уровень логов Xray", "warning", dropdown = true) {}
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        File(dir, "settings_collapsed.png").writeBytes(onRoot().png())
        onNodeWithText("Блокировка рекламы").performClick(); waitForIdle()
        File(dir, "adblock_section.png").writeBytes(onRoot().png())
        onNodeWithText("Настройки туннеля").performClick(); waitForIdle()
        File(dir, "tunnel_section.png").writeBytes(onRoot().png())
        onNodeWithText("Настройки интерфейса").performClick(); waitForIdle()
        File(dir, "interface_section.png").writeBytes(onRoot().png())
        onNodeWithText("Конфиденциальность").performClick(); waitForIdle()
        File(dir, "privacy_section.png").writeBytes(onRoot().png())
    }


    @Test fun fontScale80() = runComposeUiTest {
        val out = File("/workspace/win_shots/2031").also { it.mkdirs() }
        setContent {
            AntiBSTheme(ThemeMode.DARK, fontFamily = "montserrat", fontScalePct = 80) {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.width(720.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        SettingsCard("Оформление") {
                            NavRow("Шрифт", "Montserrat", dropdown = true, subtitle = "Системный или один из встроенных (кириллица)") {}
                            FontScaleRow(80) {}
                        }
                        Text(AppFonts.PREVIEW, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
        waitForIdle()
        out.resolve("font_scale_80.png").writeBytes(onRoot().png())
    }

    @Test fun fontScale130() = runComposeUiTest {
        val out = File("/workspace/win_shots/2031").also { it.mkdirs() }
        setContent {
            AntiBSTheme(ThemeMode.DARK, fontFamily = "montserrat", fontScalePct = 130) {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.width(720.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        SettingsCard("Оформление") {
                            NavRow("Шрифт", "Montserrat", dropdown = true, subtitle = "Системный или один из встроенных (кириллица)") {}
                            FontScaleRow(130) {}
                        }
                        Text(AppFonts.PREVIEW, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
        waitForIdle()
        out.resolve("font_scale_130.png").writeBytes(onRoot().png())
    }

}
