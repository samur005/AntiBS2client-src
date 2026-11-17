package net.antibs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.data.TrafficBarStyle

/** 2.2.1: «Полоса трафика» — height, colour, gradient, with a live preview. */
@Composable
internal fun TrafficBarPage(vm: AppViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    val p = st.adv.trafficBar
    fun set(f: (TrafficBarStyle) -> TrafficBarStyle) {
        vm.setAdv { it.copy(trafficBar = f(it.trafficBar)) }
    }
    val accent = Accent.current
    SettingsScaffold("Полоса трафика", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            HintText("Полоса на карточке подписки (например «0 ГБ / ∞»). По умолчанию — текущий вид: высота 14 dp, цвет и градиент «Авто» (синий–фиолетовый; предупреждение, когда осталось мало). Текст остаётся читаемым при любой высоте: на низкой полоске он рисуется под ней.")
            SettingsCard("Просмотр") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrafficBar(UiLogic.trafficBarLabel(UiLogic.Traffic(0.0, 0.0, true)), 1f, p)
                    TrafficBar(UiLogic.trafficBarLabel(UiLogic.Traffic(12.6 * 1024.0, 150 * 1024.0, true)), 1f - 12.6f / 150f, p)
                    TrafficBar(UiLogic.trafficBarLabel(UiLogic.Traffic(85 * 1024.0, 100 * 1024.0, true)), 0.15f, p)
                }
            }
            SettingsCard("Высота") {
                HeightRow(p.heightDp) { v -> set { it.copy(heightDp = TrafficBarStyle.normalizeHeight(v)) } }
            }
            SettingsCard("Цвет") {
                SwatchRow(
                    selected = TrafficBarStyle.normalizeColor(p.color),
                    extras = listOf(
                        TrafficBarStyle.COLOR_AUTO to AntiBSColors.Blue,
                        TrafficBarStyle.COLOR_ACCENT to accent,
                    ),
                    onPick = { v -> set { it.copy(color = v) } },
                )
                Text(
                    TrafficBarLogic.colorLabel(p.color),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                )
            }
            SettingsCard("Градиент") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrafficBarStyle.gradientOptions.forEach { (id, label) ->
                            val on = TrafficBarStyle.normalizeGradient(p.gradient) == id
                            FilterChip(selected = on, onClick = { set { it.copy(gradient = id) } }, label = { Text(label, maxLines = 1) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                HintText("«Авто» — как сейчас: синий–фиолетовый, жёлтый и красный, когда трафика осталось мало. «Вкл» — выбранный цвет переходит во второй. «Нет» — сплошная заливка.")
            }
            if (TrafficBarStyle.normalizeGradient(p.gradient) == TrafficBarStyle.GRADIENT_ON) {
                SettingsCard("Второй цвет") {
                    SwatchRow(
                        selected = TrafficBarStyle.normalizeColor(p.color2),
                        extras = listOf(
                            TrafficBarStyle.COLOR_AUTO to AntiBSColors.Purple,
                            TrafficBarStyle.COLOR_ACCENT to Accent.deep(accent),
                        ),
                        onPick = { v -> set { it.copy(color2 = v) } },
                    )
                    Text(
                        TrafficBarLogic.colorLabel(p.color2),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeightRow(dp: Int, onChange: (Int) -> Unit) {
    val snapped = TrafficBarStyle.normalizeHeight(dp)
    var v by remember(snapped) { mutableFloatStateOf(snapped.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Высота полосы", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${v.toInt()} dp", color = AntiBSColors.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (v.toInt() != TrafficBarStyle.HEIGHT_DEFAULT) {
                androidx.compose.material3.TextButton(onClick = {
                    v = TrafficBarStyle.HEIGHT_DEFAULT.toFloat()
                    onChange(TrafficBarStyle.HEIGHT_DEFAULT)
                }) { Text("Сбросить", fontSize = 13.sp) }
            }
        }
        Slider(
            value = v,
            onValueChange = {
                val s = TrafficBarStyle.normalizeHeight(it.toInt()).toFloat()
                if (s != v) {
                    v = s
                    onChange(s.toInt())
                }
            },
            valueRange = TrafficBarStyle.HEIGHT_MIN.toFloat()..TrafficBarStyle.HEIGHT_MAX.toFloat(),
            steps = TrafficBarStyle.HEIGHT_MAX - TrafficBarStyle.HEIGHT_MIN - 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("2 dp", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("14 dp", fontSize = 11.sp, color = if (v.toInt() == 14) AntiBSColors.Blue else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (v.toInt() == 14) FontWeight.SemiBold else FontWeight.Normal)
            Text("16 dp", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
