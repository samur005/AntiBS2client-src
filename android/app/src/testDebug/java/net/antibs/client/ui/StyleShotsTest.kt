package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.engine.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the styled Servers top (header, ribbon / stack / plaque, best card, chips, rows) per style into build/styles_shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class StyleShotsTest {
    @get:Rule val rule = createComposeRule()

    private val items = listOf(
        RibbonItem("s1", "AntiBS Premium", "27 дн.", traffic = "12.4GB/100GB", remaining = 0.88f, used = 12_400_000_000, total = 100_000_000_000, servers = 6),
        RibbonItem("s2", "Work VPN", "2 дн.", warn = true, traffic = "41GB/50GB", remaining = 0.18f, used = 41_000_000_000, total = 50_000_000_000, servers = 4),
        RibbonItem("s3", "Free", "без срока", traffic = "3GB/∞", servers = 3),
    )
    private val rows = listOf(Triple("🇩🇪 Frankfurt", "VLESS Reality", 38L), Triple("🇳🇱 Amsterdam", "Hysteria2", 72L),
        Triple("🇫🇮 Helsinki", "Trojan TLS", 140L), Triple("🇺🇸 New York", "VMess WS", 230L), Triple("🇯🇵 Tokyo", "TUIC", -1L))

    @Test fun shots() {
        val out = File(System.getProperty("shotsDir") ?: "build/styles_shots").apply { mkdirs() }
        val state = androidx.compose.runtime.mutableStateOf(ServerStyle.CLASSIC to true)
        rule.mainClock.autoAdvance = false
        rule.setContent { view = androidx.compose.ui.platform.LocalView.current; val (style, dark) = state.value; androidx.compose.runtime.key(style, dark) { Content(style, dark) } }
        state.value = ServerStyle.NEON to false; rule.mainClock.advanceTimeBy(600);
        for (dark in listOf(true, false)) for (style in ServerStyle.entries) {
            state.value = style to dark
            rule.mainClock.advanceTimeBy(600); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(300)
            var bmp: Bitmap? = null
            rule.runOnUiThread { val v = view!!; bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) } }
            val b = bmp!!
            File(out, "${style.ordinal + 1}-${style.key}-${if (dark) "dark" else "light"}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private val tag = "shot"
    private var view: android.view.View? = null

    @androidx.compose.runtime.Composable
    private fun Content(style: ServerStyle, dark: Boolean) {
            AntiBSTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, style = style) {
                val opts = ServerStyleLogic.defaultOptions(style)
                val layout = ServerStyleLogic.effectiveLayout(style, opts)
                val bg = ssBackground(style)
                val c = ssColors(style)
                Column(Modifier.testTag(tag).fillMaxSize().background(MaterialTheme.colorScheme.background).then(if (bg != null) Modifier.background(bg) else Modifier)) {
                    ServersHeader(style, opts.layout, opts.bestCard, {}, {}, {}, StylePrefs.SORT_ADDED, {}, true, {}, ServerFilter.ALL, {}, false, {}, {},
                        null, {}, {}, {}, {}, {}, {})
                    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (style != ServerStyle.CLASSIC && layout == SubLayout.RIBBON) when (style) {
                            ServerStyle.STACK -> SubCardStack(items, "s1", { "$it серв." }, "листайте") {}
                            ServerStyle.MATERIAL -> MaterialTrafficCard("Использовано", "12.4GB", "из 100GB", items, "s1", "Выбрать") {}
                            else -> {
                                SubscriptionRibbon(style, listOf(RibbonItem("", "Все", "13 серв.")) + items, "s1", {})
                                if (style == ServerStyle.CYBER) CyberTrafficCard("AntiBS Premium", "12.4GB", "/ 100GB", "27 дн.", 12_400_000_000, 100_000_000_000)
                            }
                        } else if (style != ServerStyle.CLASSIC || true) {
                            Text("AntiBS Premium · 6 серверов · 12.4GB/100GB", color = c.onCard, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (opts.bestCard && style != ServerStyle.CLASSIC) BestServerCard(style, "Лучший сервер", "🇩🇪 Frankfurt", "VLESS Reality", 38, "38 мс", "Подключить", "", {}, {})
                        rows.forEachIndexed { i, (n, p, ms) ->
                            val sel = i == 0
                            val shape = ssShape(style, 14.dp)
                            Row(Modifier.fillMaxWidth().neonGlow(c.accent, sel && style.glow && c.dark, 14.dp, 10.dp, 0.4f)
                                .then(androidx.compose.ui.Modifier.background(if (sel) c.selectedFill else c.card, shape))
                                .then(androidx.compose.ui.Modifier.padding(horizontal = 14.dp, vertical = 10.dp)), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(n, color = c.onCard, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
        }
    }
