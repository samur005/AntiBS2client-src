package net.antibs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.data.PowerButtonStyle
import net.antibs.client.engine.ConnState

/** 2.0.42: «Кнопка питания» — colour, label colour, living effect, motion toggle. */
@Composable
internal fun PowerButtonPage(vm: AppViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    val p = st.adv.powerButton
    fun set(f: (PowerButtonStyle) -> PowerButtonStyle) {
        vm.setAdv { it.copy(powerButton = f(it.powerButton)) }
    }
    val accent = AntiBSColors.Blue
    SettingsScaffold("Кнопка питания", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            HintText("Цвет и живой эффект относятся к состоянию «подключено». Пока идёт подключение, ползунок остаётся оранжевым, при ошибке — красным. Живые эффекты останавливаются, если кнопка не на экране, VPN выключен, анимация отключена или в системе включено «Убрать анимацию».")
            SettingsCard("Просмотр") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    PowerToggle(
                        state = ConnState.CONNECTED,
                        since = System.currentTimeMillis() - 754_000,
                        onClick = {},
                        haptics = st.adv.haptics,
                        prefs = p,
                    )
                }
            }
            SettingsCard("Цвет кнопки") {
                SwatchRow(
                    selected = PowerButtonStyle.normalizeColor(p.color),
                    extras = listOf(
                        PowerButtonStyle.COLOR_AUTO to Color(0xFF1DBF72),
                        PowerButtonStyle.COLOR_ACCENT to accent,
                    ),
                    onPick = { v -> set { it.copy(color = v) } },
                )
                Text(
                    PowerButtonLogic.colorLabel(p.color),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                )
            }
            SettingsCard("Цвет текста") {
                SwatchRow(
                    selected = PowerButtonStyle.normalizeText(p.textColor),
                    extras = listOf(
                        PowerButtonStyle.COLOR_AUTO to Color.White,
                        PowerButtonStyle.TEXT_WHITE to Color.White,
                        PowerButtonStyle.TEXT_BLACK to Color(0xFF0F172A),
                    ),
                    onPick = { v -> set { it.copy(textColor = v) } },
                )
                Text(
                    PowerButtonLogic.textLabel(p.textColor),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                )
            }
            SettingsCard("Живой эффект") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PowerButtonStyle.effectOptions.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (id, label) ->
                                val on = PowerButtonStyle.normalizeEffect(p.effect) == id
                                FilterChip(selected = on, onClick = { set { it.copy(effect = id) } }, label = { Text(label, maxLines = 1) }, modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                HintText("«Дыхание» — спокойное пульсирующее свечение (по умолчанию). «Свечение» сильнее, «Переливание» — мягкий бегущий блик.")
            }
            SettingsCard("Анимация") {
                SwitchRow(
                    "Анимация кнопки",
                    p.anim,
                    subtitle = "Сдвиг ползунка и переходы цвета. Если выключить, живые эффекты тоже не проигрываются.",
                ) { v -> set { it.copy(anim = v) } }
            }
        }
    }
}

@Composable
internal fun SwatchRow(
    selected: String,
    extras: List<Pair<String, Color>>,
    onPick: (String) -> Unit,
) {
    val items = extras + Accent.presets.map { it.first to Color(it.third.toInt()) }
    val labels = extras.associate { it.first to when (it.first) {
        PowerButtonStyle.COLOR_AUTO -> "Авто"
        PowerButtonStyle.COLOR_ACCENT -> "Акцент"
        PowerButtonStyle.TEXT_WHITE -> "Белый"
        PowerButtonStyle.TEXT_BLACK -> "Чёрный"
        else -> it.first
    } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (key, color) ->
            val on = key == selected
            val label = labels[key] ?: Accent.label(key)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(if (on) 2.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape)
                        .clickable(onClickLabel = label) { onPick(key) },
                )
            }
        }
    }
}
