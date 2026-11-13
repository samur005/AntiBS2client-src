package net.antibs.client.ui

import androidx.compose.material.icons.filled.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.data.*
import net.antibs.client.service.VpnScheduler
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// ════════════════ «Остаток подписки» row (bottom of the connection card)

@Composable
private fun darkUi() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
private fun ThinBar(frac: Float, color: Color) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Box(Modifier.size(30.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable
internal fun SubRemainRow(data: SubLineData, style: SubRemainStyle, modifier: Modifier = Modifier) {
    if (!style.show) return
    val dark = darkUi()
    val dText = if (style.daysText != 0L) Color(style.daysText.toInt()) else if (dark) Color(0xFFD7A06E) else Color(0xFFB8733A)
    val dBar = if (style.daysBar != 0L) Color(style.daysBar.toInt()) else dText
    val tText = if (style.trafficText != 0L) Color(style.trafficText.toInt()) else if (dark) Color(0xFF5FC2BC) else Color(0xFF2E8C86)
    val tBar = if (style.trafficBar != 0L) Color(style.trafficBar.toInt()) else tText
    val fs = style.size.sp
    val fw = if (style.bold) FontWeight.Bold else FontWeight.Medium
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = when (style.align) { SubRemainStyle.ALIGN_START -> Arrangement.Start; SubRemainStyle.ALIGN_END -> Arrangement.End; else -> Arrangement.Center }) {
        // 2.0.30: full «дней 115 • до 05.03.2027» keeps intrinsic width; traffic side may shrink
        Text(
            data.days + (data.until?.let { " • $it" } ?: ""),
            color = dText, fontSize = fs, fontWeight = fw, maxLines = 1, softWrap = false,
        )
        if (data.dayFrac != null) { Spacer(Modifier.width(4.dp)); ThinBar(data.dayFrac, dBar) }
        Spacer(Modifier.width(10.dp))
        Text(
            data.traffic, color = tText, fontSize = fs, fontWeight = fw, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (data.trafficFrac != null) { Spacer(Modifier.width(6.dp)); ThinBar(data.trafficFrac, tBar) }
    }
}

// ════════════════ «пинг: 131 мс» chip under the protocol badge (auto-ping + tap)

@Composable
internal fun HomePingChip(ms: Long?, busy: Boolean, style: PingChipStyle, active: Boolean, onPing: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (!style.show) return
    val cs = MaterialTheme.colorScheme
    val tone = when {
        !active || busy || ms == null -> cs.onSurfaceVariant
        else -> when (PingChipStyle.tone(ms)) { 0 -> Color(0xFF1FA463); 1 -> Color(0xFFF59E0B); else -> Color(0xFFE5484D) }
    }
    val fg = if (style.text != 0L) Color(style.text.toInt()) else tone
    val bg = if (style.bg != 0L) Color(style.bg.toInt()) else cs.surfaceVariant.copy(alpha = 0.7f)
    Text(PingChipStyle.label(ms, busy), color = fg, fontSize = (style.size * 0.82f).sp, lineHeight = (style.size + 2).sp * 0.82f, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.SemiBold, fontFamily = if (style.mono) FontFamily.Monospace else null,
        modifier = modifier.alpha((style.alpha / 100f) * (if (active) 1f else 0.55f)).clip(RoundedCornerShape(5.dp)).background(bg)
            .then(if (onPing != null) Modifier.clickable(onClickLabel = "Проверить пинг текущего сервера") { onPing() } else Modifier)
            .padding(horizontal = (style.bgPad + 1).dp, vertical = (style.bgPad / 4f).dp))
}

// ════════════════ Country group header (ABS CountryHeader)

@Composable
internal fun CountryHeader(code: String, count: Int, open: Boolean, best: Long?, showPing: Boolean, onToggle: () -> Unit) {
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "cg")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (code == ServerGrouping.OTHER) "🌐" else ServerGrouping.flag(code), fontSize = 17.sp)
        Spacer(Modifier.width(8.dp))
        Text(ServerGrouping.countryName(code), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
        if (!open && showPing && best != null) {
            val c = when (PingChipStyle.tone(best)) { 0 -> AntiBSColors.Ok; 1 -> AntiBSColors.Warn; else -> AntiBSColors.Err }
            Text("$best мс", color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Icon(Icons.Filled.ExpandMore, if (open) "Свернуть" else "Раскрыть", Modifier.rotate(rot), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Grouping menu items, shared by the ⇅ menu, the ⋮ subscription menu and the SubCard menu. */
@Composable
internal fun GroupingMenuItems(vm: AppViewModel, g: ServerGrouping, subId: String?, close: () -> Unit) {
    if (subId == null) {
        DropdownMenuItem(text = { Text((if (g.on) "✓ " else "") + "Группировать по странам") }, leadingIcon = { MenuIcon(androidx.compose.material.icons.Icons.Filled.Public) }, onClick = { close(); vm.setAdv { it.copy(serverGrouping = g.copy(on = !g.on)) } })
    } else {
        val cur = g.subs[subId]?.on
        listOf(null to "Группы: как в настройках", true to "Группы по странам: вкл", false to "Группы по странам: выкл").forEach { (v, l) ->
            val ic = when (v) { null -> androidx.compose.material.icons.Icons.Filled.Tune; true -> androidx.compose.material.icons.Icons.Filled.Flag; false -> androidx.compose.material.icons.Icons.Filled.ViewList }
            DropdownMenuItem(text = { Text((if (cur == v) "✓ " else "") + l) }, leadingIcon = { MenuIcon(ic) }, onClick = { close(); vm.setAdv { it.copy(serverGrouping = g.withSub(subId) { s -> s.copy(on = v) }) } })
        }
    }
}

// ════════════════ Seasonal overlay: snow + garland (ABS SeasonalUi)

private class Flake(var x: Float, var y: Float, val r: Float, val v: Float, val sway: Float, val phase: Float)

@Composable
internal fun SeasonalOverlay(fx: SeasonalFx, snow: Boolean, garland: Boolean, topInset: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    if (!snow && !garland) return
    val f = fx.clamped()
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { var last = 0L; while (true) { withFrameNanos { n -> if (last != 0L) t += (n - last) / 1e9f; last = n } } }
    val flakes = remember(f.snowCount, f.snowSize) { val rnd = Random(7); List(f.snowCount) { Flake(rnd.nextFloat(), rnd.nextFloat(), (1.2f + rnd.nextFloat() * 2.2f) * f.snowSize / 100f, 0.04f + rnd.nextFloat() * 0.07f, 6f + rnd.nextFloat() * 14f, rnd.nextFloat() * 6.28f) } }
    val density = LocalDensity.current
    val snowColor = Color(f.snowColor.toInt())
    Canvas(modifier.fillMaxSize()) {
        val time = t
        if (snow) drawFlakes(flakes, time, f.snowSpeed / 100f, snowColor, density.density)
        if (garland) drawGarland(time, f.garlandBlink, f.garlandSpeed / 100f, with(density) { topInset.toPx() }, density.density)
    }
}

private fun DrawScope.drawFlakes(fl: List<Flake>, t: Float, speed: Float, color: Color, d: Float) {
    for (p in fl) {
        val y = ((p.y + t * p.v * speed) % 1.1f) * size.height * 1.0f - 8 * d
        val x = p.x * size.width + sin(t * 0.8f + p.phase) * p.sway * d
        drawCircle(color.copy(alpha = 0.85f), p.r * d, Offset(x, y))
    }
}

private val BULBS = listOf(Color(0xFFFF4D4D), Color(0xFFFFC145), Color(0xFF4BE08A), Color(0xFF4DA3FF), Color(0xFFE065FF))

internal fun DrawScope.drawGarland(t: Float, blink: Boolean, speed: Float, top: Float, d: Float) {
    val seg = 84 * d; val sag = 12 * d
    val n = (size.width / seg).toInt() + 1
    val wire = Path().apply { moveTo(0f, top); for (i in 0 until n) { val x0 = i * seg; quadraticBezierTo(x0 + seg / 2, top + sag * 2, x0 + seg, top) } }
    drawPath(wire, Color(0xFF2E3B2E), style = Stroke(1.4f * d))
    var k = 0
    for (i in 0 until n) for (j in 1..4) {
        val u = j / 5f; val x = i * seg + seg * u
        val y = top + 2 * sag * 2 * u * (1 - u) + 3 * d
        val c = BULBS[k % BULBS.size]
        val a = if (blink) (0.45f + 0.55f * ((sin(t * 2.4f * speed + k * 1.7f) + 1) / 2)) else 1f
        drawCircle(c.copy(alpha = 0.25f * a), 9 * d, Offset(x, y))
        drawCircle(c.copy(alpha = a), 3.6f * d, Offset(x, y))
        k++
    }
}

/** Snow / garland decision for today (used by AppRoot and the preview). */
internal fun seasonalNow(a: AdvancedSettings, date: LocalDate = LocalDate.now()): Pair<Boolean, Boolean> {
    val holiday = runCatching {
        if (a.seasonalDecor == "off") null else if (a.seasonalDecor == "auto") HolidayCalendar.current(date, a.customHolidays) else HolidayCalendar.byId(a.seasonalDecor, a.customHolidays)
    }.getOrNull()
    return a.seasonalFx.snowOn(date, a.seasonalDecor) to a.seasonalFx.garlandOn(holiday?.id == "ny")
}

// ════════════════ Settings pages

@Composable
private fun SliderRow(title: String, value: Int, range: IntRange, suffix: String = "%", onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row { Text(title, Modifier.weight(1f), fontSize = 15.sp); Text("${v.toInt()}$suffix", color = AntiBSColors.Blue, fontSize = 14.sp) }
        Slider(v, { v = it }, valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = { onChange(v.toInt()) })
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, String>>, sel: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, fontSize = 15.sp)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (k, l) -> FilterChip(selected = sel == k, onClick = { onPick(k) }, label = { Text(l, fontSize = 13.sp) }) }
        }
    }
}

@Composable
private fun ColorRow(title: String, value: Long, swatches: List<Long> = SeasonalFx.SWATCHES, onPick: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, fontSize = 15.sp)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).border(2.dp, if (value == 0L) AntiBSColors.Blue else MaterialTheme.colorScheme.outline, CircleShape).clickable { onPick(0L) },
                contentAlignment = Alignment.Center) { Text("A", fontSize = 11.sp) }
            swatches.forEach { c ->
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c.toInt())).border(2.dp, if (value == c) AntiBSColors.Blue else Color.Transparent, CircleShape).clickable { onPick(c) })
            }
        }
    }
}

@Composable
internal fun SeasonalPage(vm: AppViewModel, st: net.antibs.client.engine.AppState, onBack: () -> Unit) {
    val a = st.adv; val f = a.seasonalFx
    fun set(b: (SeasonalFx) -> SeasonalFx) = vm.setAdv { it.copy(seasonalFx = b(it.seasonalFx).clamped()) }
    var addOpen by remember { mutableStateOf(false) }
    SettingsScaffold("Сезонные украшения", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            // preview
            Box(Modifier.fillMaxWidth().padding(12.dp).height(120.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF16222E))) {
                SeasonalOverlay(f, f.snowMode != SnowMode.OFF, f.garland && f.effects != SeasonalEffectsMode.OFF, 6.dp)
                val h = HolidayCalendar.current(custom = a.customHolidays) ?: HolidayCalendar.all(a.customHolidays).firstOrNull()
                if (h != null && f.showLabel) SeasonalLabelText(h.emoji, h.name, f, Modifier.align(Alignment.Center))
            }
            SettingsCard("Праздник") {
                ChoiceRow("Режим", listOf("auto" to "Авто", "off" to "Выкл"), if (a.seasonalDecor == "auto" || a.seasonalDecor == "off") a.seasonalDecor else "") { v -> vm.setAdv { it.copy(seasonalDecor = v) } }
                HolidayCalendar.all(a.customHolidays).forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable { vm.setAdv { it.copy(seasonalDecor = h.id) } }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(a.seasonalDecor == h.id, null); Text("${h.emoji}  ${h.name}" + if (h.custom) " (${h.day}.${h.month})" else "", Modifier.weight(1f).padding(start = 6.dp), fontSize = 14.sp)
                        if (h.custom) IconButton(onClick = { vm.setAdv { adv -> adv.copy(customHolidays = adv.customHolidays.filterNot { c -> c.id == h.id }) } }) { Icon(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.error) }
                    }
                }
                NavRow("Добавить свой праздник", chevron = false) { addOpen = true }
                NavRow("Обновить мусульманские праздники", chevron = false) { vm.refreshMuslimHolidays() }
                Text(vm.muslimStatusText(), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = if (vm.muslimStatus.intValue == 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsCard("Надпись") {
                SwitchRow("Показывать название праздника", f.showLabel) { v -> set { it.copy(showLabel = v) } }
                if (f.showLabel) {
                    ColorRow("Цвет", f.labelColor) { c -> set { it.copy(labelColor = c) } }
                    SliderRow("Прозрачность", f.labelAlpha, SeasonalFx.LABEL_ALPHA_MIN..100) { v -> set { it.copy(labelAlpha = v) } }
                    SliderRow("Размер", f.labelSize, SeasonalFx.LABEL_SIZE_MIN..SeasonalFx.LABEL_SIZE_MAX) { v -> set { it.copy(labelSize = v) } }
                    NavRow("Сбросить надпись", chevron = false) { set { it.copy(labelColor = 0L, labelAlpha = 100, labelSize = 100) } }
                }
            }
            SettingsCard("Снег") {
                ChoiceRow("Снег", listOf(SnowMode.OFF to "Выкл", SnowMode.AUTO to "Зимой", SnowMode.ON to "Всегда"), f.snowMode) { v -> set { it.copy(snowMode = v) } }
                HintText("«Зимой» — с 1 декабря по 28 марта, когда праздники в режиме «Авто».")
                if (f.snowMode != SnowMode.OFF) {
                    SliderRow("Количество снежинок", f.snowCount, SeasonalFx.SNOW_MIN..SeasonalFx.SNOW_MAX, "") { v -> set { it.copy(snowCount = v) } }
                    SliderRow("Скорость", f.snowSpeed, SeasonalFx.SPEED_MIN..SeasonalFx.SPEED_MAX) { v -> set { it.copy(snowSpeed = v) } }
                    SliderRow("Размер снежинок", f.snowSize, SeasonalFx.SIZE_MIN..SeasonalFx.SIZE_MAX) { v -> set { it.copy(snowSize = v) } }
                    ColorRow("Цвет снега", f.snowColor) { c -> set { it.copy(snowColor = if (c == 0L) 0xFFFFFFFFL else c) } }
                }
            }
            SettingsCard("Гирлянда") {
                ChoiceRow("Эффекты", listOf(SeasonalEffectsMode.OFF to "Выкл", SeasonalEffectsMode.HOLIDAY to "В праздник", SeasonalEffectsMode.ALWAYS to "Всегда"), f.effects) { v -> set { it.copy(effects = v) } }
                SwitchRow("Гирлянда", f.garland) { v -> set { it.copy(garland = v) } }
                if (f.garland) {
                    SwitchRow("Мигание", f.garlandBlink) { v -> set { it.copy(garlandBlink = v) } }
                    if (f.garlandBlink) SliderRow("Скорость мигания", f.garlandSpeed, SeasonalFx.SPEED_MIN..SeasonalFx.SPEED_MAX) { v -> set { it.copy(garlandSpeed = v) } }
                }
            }
        }
    }
    if (addOpen) AddHolidayDialog(vm) { addOpen = false }
}

@Composable
internal fun SeasonalLabelText(emoji: String, name: String, f: SeasonalFx, modifier: Modifier = Modifier, base: Float = 13f, defColor: Color = Color.White) {
    Text("$emoji $name", modifier.alpha(f.labelAlpha / 100f), color = if (f.labelColor != 0L) Color(f.labelColor.toInt()) else defColor,
        fontSize = (base * f.labelSize / 100f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
private fun AddHolidayDialog(vm: AppViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf("") }; var emoji by remember { mutableStateOf("🎉") }
    var m by remember { mutableStateOf("1") }; var d by remember { mutableStateOf("1") }
    AlertDialog(onDismissRequest = close, title = { Text("Новый праздник") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
            OutlinedTextField(emoji, { emoji = it }, label = { Text("Эмодзи") }, singleLine = true)
            Row { OutlinedTextField(m, { m = it }, label = { Text("Месяц") }, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); OutlinedTextField(d, { d = it }, label = { Text("День") }, modifier = Modifier.weight(1f)) }
        }
    }, confirmButton = { TextButton(onClick = {
        val mm = m.toIntOrNull()?.coerceIn(1, 12) ?: 1; val dd = d.toIntOrNull()?.coerceIn(1, 31) ?: 1
        if (name.isNotBlank()) vm.setAdv { it.copy(customHolidays = it.customHolidays + CustomHoliday("custom-" + java.util.UUID.randomUUID().toString().take(6), name.trim(), emoji.ifBlank { "🎉" }, mm, dd, mm, dd)) }
        close() }) { Text("Добавить") } }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}

@Composable
internal fun SubRemainPage(vm: AppViewModel, st: net.antibs.client.engine.AppState, onBack: () -> Unit) {
    val s = st.adv.subRemain
    fun set(b: (SubRemainStyle) -> SubRemainStyle) = vm.setAdv { it.copy(subRemain = b(it.subRemain)) }
    val tones = listOf(0xFFD7A06EL, 0xFF5FC2BCL, 0xFF1FA463L, 0xFFF59E0BL, 0xFFE5484DL, 0xFF42A5F5L, 0xFFFFFFFFL, 0xFF9AA4B2L)
    SettingsScaffold("Остаток подписки", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                SubRemainRow(SubLineData("дней 115", "до 05.03.2027", 1f, "1,4 ГБ / 150 ГБ", 0.99f), s.copy(show = true))
            }
            SettingsCard("Остаток подписки") {
                SwitchRow("Показывать", s.show, subtitle = "Дни и трафик внизу карточки подключения") { v -> set { it.copy(show = v) } }
                SliderRow("Размер текста", s.size, SubRemainStyle.SIZE_MIN..SubRemainStyle.SIZE_MAX, " sp") { v -> set { it.copy(size = v) } }
                SwitchRow("Жирный", s.bold) { v -> set { it.copy(bold = v) } }
                ChoiceRow("Выравнивание", listOf("0" to "Слева", "1" to "По центру", "2" to "Справа"), s.align.toString()) { v -> set { it.copy(align = v.toInt()) } }
                ColorRow("Дни: цвет текста", s.daysText, tones) { c -> set { it.copy(daysText = c) } }
                ColorRow("Дни: цвет полоски", s.daysBar, tones) { c -> set { it.copy(daysBar = c) } }
                ColorRow("Трафик: цвет текста", s.trafficText, tones) { c -> set { it.copy(trafficText = c) } }
                ColorRow("Трафик: цвет полоски", s.trafficBar, tones) { c -> set { it.copy(trafficBar = c) } }
                NavRow("Сбросить", chevron = false) { set { SubRemainStyle() } }
            }
        }
    }
}

/** «Автопинг» + «Чип пинга» + «Автоудаление» — injected at the top of «Пинг» settings. */
@Composable
internal fun AutoPingSection(vm: AppViewModel, a: AdvancedSettings) {
    val ap = a.autoPing; val pc = a.pingChip
    fun setAp(b: (AutoPing) -> AutoPing) = vm.setAdv { it.copy(autoPing = b(it.autoPing)) }
    fun setPc(b: (PingChipStyle) -> PingChipStyle) = vm.setAdv { it.copy(pingChip = b(it.pingChip)) }
    SectionLabel("Автопинг")
    SwitchRow("Автопинг", ap.on, subtitle = "Пинговать текущий сервер и проверять туннель с этим интервалом (так же определяется «нет пинга» и восстановление)") { v -> setAp { it.copy(on = v) } }
    if (ap.on) ChoiceRow("Интервал", AutoPing.INTERVALS.map { it.toString() to AutoPing.intervalLabel(it) }, ap.intervalSec.toString()) { v -> setAp { it.copy(intervalSec = v.toInt()) } }
    SectionLabel("Чип пинга")
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { HomePingChip(131, false, pc.copy(show = true), true, null) }
    SwitchRow("Показывать пинг под протоколом", pc.show, subtitle = "Реальный пинг текущего сервера; нажмите, чтобы перепинговать") { v -> setPc { it.copy(show = v) } }
    if (pc.show) {
        SliderRow("Размер", pc.size, PingChipStyle.SIZE_MIN..PingChipStyle.SIZE_MAX, " sp") { v -> setPc { it.copy(size = v) } }
        SwitchRow("Жирный", pc.bold) { v -> setPc { it.copy(bold = v) } }
        SwitchRow("Моноширинный шрифт", pc.mono) { v -> setPc { it.copy(mono = v) } }
        ColorRow("Цвет текста", pc.text) { c -> setPc { it.copy(text = c) } }
        ColorRow("Цвет фона", pc.bg, listOf(0x33FFFFFFL, 0x66000000L, 0xFF1F2A36L, 0xFF1FA463L, 0xFF42A5F5L)) { c -> setPc { it.copy(bg = c) } }
        SliderRow("Размер фона", pc.bgPad, PingChipStyle.PAD_MIN..PingChipStyle.PAD_MAX, "") { v -> setPc { it.copy(bgPad = v) } }
        SliderRow("Прозрачность", pc.alpha, 10..100) { v -> setPc { it.copy(alpha = v) } }
    }
    SectionLabel("Автоудаление")
    SwitchRow("Автоудаление недоступных при проверке пинга", ap.autoDelete,
        subtitle = "Пока VPN подключён, раз в 30 мин пингуются все серверы. Сервер удаляется после ${ap.failsToDelete} неудачных проверок подряд. Выбранный и подключённый — никогда") { v -> setAp { it.copy(autoDelete = v) } }
    if (ap.autoDelete) ChoiceRow("Неудач подряд", AutoDeletePing.FAILS_CHOICES.map { it.toString() to it.toString() }, ap.failsToDelete.toString()) { v -> setAp { it.copy(failsToDelete = v.toInt()) } }
    HintText("Не удаляет, если нет интернета, не ответили все или больше половины серверов, или похоже на белые списки. TCP-пинг для UDP-протоколов (Hysteria2, TUIC, WireGuard, AmneziaWG, XHTTP/H3) автоматически заменяется на ICMP.")
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SchedulePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(VpnScheduler.load(ctx)) }
    var exact by remember { mutableStateOf(VpnScheduler.canExact(ctx)) }
    var edit by remember { mutableStateOf<ScheduleRule?>(null) }
    fun save(c: VpnScheduleCfg) { cfg = c; VpnScheduler.save(ctx, c) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) { val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { exact = VpnScheduler.canExact(ctx); VpnScheduler.arm(ctx) } }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) } }
    SettingsScaffold("Расписание VPN", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsCard {
                SwitchRow("Работать по расписанию", cfg.on, subtitle = "Включать и выключать VPN в заданное время") { save(cfg.copy(on = it)) }
                SwitchRow("Уведомлять о каждом действии", cfg.notify) { save(cfg.copy(notify = it)) }
            }
            if (cfg.on) cfg.next(System.currentTimeMillis())?.let { (at, r) ->
                HintText("Следующее: " + (if (r.connect) "включить" else "выключить") + " — " + java.text.SimpleDateFormat("EE, dd.MM HH:mm", java.util.Locale("ru")).format(java.util.Date(at)))
            }
            if (!exact && android.os.Build.VERSION.SDK_INT >= 31) {
                HintText("Без разрешения на точные будильники Android может сдвигать срабатывание на несколько минут.")
                TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + ctx.packageName))) } },
                    modifier = Modifier.padding(horizontal = 8.dp)) { Text("Разрешить точные будильники") }
            }
            SettingsCard("Правила") {
                if (cfg.rules.isEmpty()) HintText("Нет правил. Например: включать в 08:00 по будням, выключать в 23:00.")
                cfg.rules.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { edit = r }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("%02d:%02d · %s".format(r.hour, r.minute, if (r.connect) "Включить VPN" else "Выключить VPN"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                color = if (r.connect) AntiBSColors.Ok else AntiBSColors.Err)
                            Text(VpnScheduleCfg.daysLabel(r.days), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(r.on, { v -> save(cfg.copy(rules = cfg.rules.map { if (it.id == r.id) it.copy(on = v) else it })) })
                    }
                    RowDivider()
                }
                NavRow("+ Добавить правило", chevron = false) { edit = ScheduleRule(System.currentTimeMillis(), connect = cfg.rules.size % 2 == 0, hour = if (cfg.rules.size % 2 == 0) 8 else 23) }
            }
        }
    }
    edit?.let { r0 ->
        var r by remember(r0) { mutableStateOf(r0) }
        val isNew = cfg.rules.none { it.id == r0.id }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (isNew) "Новое правило" else "Правило") }, text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.connect, { r = r.copy(connect = true) }, { Text("Включить") })
                    FilterChip(!r.connect, { r = r.copy(connect = false) }, { Text("Выключить") })
                }
                val tp = rememberTimePickerState(r.hour, r.minute, true)
                LaunchedEffect(tp.hour, tp.minute) { r = r.copy(hour = tp.hour, minute = tp.minute) }
                TimeInput(tp)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    ScheduleRule.DAY_NAMES.forEachIndexed { i, n -> val on = r.days and (1 shl i) != 0
                        Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) AntiBSColors.Blue else MaterialTheme.colorScheme.surfaceVariant).clickable { r = r.copy(days = r.days xor (1 shl i)) },
                            contentAlignment = Alignment.Center) { Text(n, fontSize = 12.sp, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ScheduleRule.ALL to "Каждый день", ScheduleRule.WORKDAYS to "Будни", ScheduleRule.WEEKEND to "Выходные").forEach { (m, l) -> TextButton(onClick = { r = r.copy(days = m) }) { Text(l, fontSize = 12.sp) } }
                }
            }
        }, confirmButton = { TextButton(onClick = { save(cfg.copy(rules = if (isNew) cfg.rules + r else cfg.rules.map { if (it.id == r.id) r else it })); edit = null }) { Text("Сохранить") } },
            dismissButton = { Row { if (!isNew) TextButton(onClick = { save(cfg.copy(rules = cfg.rules.filterNot { it.id == r0.id })); edit = null }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { edit = null }) { Text("Отмена") } } })
    }
}
