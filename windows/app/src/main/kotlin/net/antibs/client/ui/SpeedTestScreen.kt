package net.antibs.client.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import net.antibs.client.desktop.FlagText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.SpeedMath
import net.antibs.client.engine.SpeedRecord
import net.antibs.client.engine.SpeedTest
import net.antibs.client.engine.VpnState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val GAUGE_START = 150f
private const val GAUGE_SWEEP = 240f
private val GaugeTicks = listOf(0.0, 1.0, 5.0, 10.0, 50.0, 100.0, 250.0, 500.0, 1000.0)

internal fun speedModeColor(mode: String): Color = if (mode == "vpn") AntiBSColors.VpnGraph else AntiBSColors.DirectGraph
internal fun speedModeName(mode: String) = if (mode == "vpn") "Через VPN" else "Напрямую"

private fun phaseName(p: SpeedTest.Phase?) = when (p) {
    SpeedTest.Phase.META -> "Подключение к Cloudflare…"
    SpeedTest.Phase.LATENCY -> "Пинг"
    SpeedTest.Phase.DOWNLOAD -> "Загрузка ↓"
    SpeedTest.Phase.UPLOAD -> "Отдача ↑"
    SpeedTest.Phase.DONE -> "Готово"
    null -> "Готов к тесту"
}

/** 1.0.21: «Тест скорости» — Cloudflare speed test through the tunnel and/or bypassing it, with history. */
@Composable
private fun LegacySpeedTestPage(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.speed.collectAsState()
    val history by vm.speedHistory.collectAsState()
    val vpn by VpnState.status.collectAsState()
    val vpnUp = vpn.state == ConnState.CONNECTED
    var choice by rememberSaveable { mutableStateOf(if (vpnUp) AppViewModel.SpeedChoice.BOTH.name else AppViewModel.SpeedChoice.DIRECT.name) }
    // VPN went down → the VPN-based choices are not available any more.
    LaunchedEffect(vpnUp) { if (!vpnUp && choice != AppViewModel.SpeedChoice.DIRECT.name && !s.running) choice = AppViewModel.SpeedChoice.DIRECT.name }
    val sel = AppViewModel.SpeedChoice.valueOf(choice)
    var confirmClear by remember { mutableStateOf(false) }

    SettingsScaffold("Тест скорости", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                ModeChip("Через VPN", AntiBSColors.VpnGraph, sel == AppViewModel.SpeedChoice.VPN, enabled = vpnUp && !s.running) { choice = AppViewModel.SpeedChoice.VPN.name }
                ModeChip("Напрямую", AntiBSColors.DirectGraph, sel == AppViewModel.SpeedChoice.DIRECT, enabled = !s.running) { choice = AppViewModel.SpeedChoice.DIRECT.name }
                ModeChip("Оба · сравнение", null, sel == AppViewModel.SpeedChoice.BOTH, enabled = vpnUp && !s.running) { choice = AppViewModel.SpeedChoice.BOTH.name }
            }
            Text(
                if (vpnUp) when (sel) {
                    AppViewModel.SpeedChoice.VPN -> "Трафик идёт через туннель (${UiLogic.liveHostTitle(vpn.serverName, vpn.pickName, true)})."
                    AppViewModel.SpeedChoice.DIRECT -> "В обход туннеля: соединения привязаны к физической сети (Wi‑Fi / мобильной)."
                    AppViewModel.SpeedChoice.BOTH -> "Сначала через VPN, затем напрямую — и сравнение результатов."
                } else "VPN выключен — доступен только тест «Напрямую». Включите VPN, чтобы измерить скорость через туннель.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
            Spacer(Modifier.height(10.dp))

            // ---- gauge card ----
            val color = speedModeColor(s.mode.ifEmpty { if (sel == AppViewModel.SpeedChoice.DIRECT) "direct" else "vpn" })
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (s.running && s.steps > 1) {
                        Text("${s.step + 1}/${s.steps} · ${speedModeName(s.mode)}", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else if (s.running) {
                        Text(speedModeName(s.mode), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val transfer = s.phase == SpeedTest.Phase.DOWNLOAD || s.phase == SpeedTest.Phase.UPLOAD
                    // 1.0.22: the needle follows the smoothed live value and springs back to 0 between phases and at the end.
                    val shown = if (s.running && transfer) s.liveMbps else 0.0
                    Gauge(mbps = shown, color = color, phase = if (s.running) phaseName(s.phase) else if (s.results.isNotEmpty()) "Готово" else phaseName(null),
                        upload = s.phase == SpeedTest.Phase.UPLOAD)
                    val total = ((s.step + phaseFraction(s.phase, s.fraction)) / s.steps.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val anim by animateFloatAsState(if (s.running) total else if (s.results.isNotEmpty()) 1f else 0f, tween(400), label = "speedProgress")
                    LinearProgressIndicator(progress = { anim }, color = color, trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(12.dp))
                    val p = s.progress
                    val cur = if (s.running) null else s.results.lastOrNull()
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Пинг", SpeedMath.fmtMs(p?.pingMs ?: cur?.ping), "мс", Modifier.weight(1f))
                        Metric("Джиттер", SpeedMath.fmtMs(p?.jitterMs ?: cur?.jitter), "мс", Modifier.weight(1f))
                        Metric("Загрузка", SpeedMath.fmtMbps(p?.downMbps ?: cur?.down), "Мбит/с", Modifier.weight(1f))
                        Metric("Отдача", SpeedMath.fmtMbps(p?.upMbps ?: cur?.up), "Мбит/с", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (s.running) {
                        Button(onClick = { vm.stopSpeedTest() }, colors = ButtonDefaults.buttonColors(containerColor = AntiBSColors.Err, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
                    } else {
                        Button(onClick = { vm.startSpeedTest(sel) }, enabled = vpnUp || sel == AppViewModel.SpeedChoice.DIRECT,
                            colors = ButtonDefaults.buttonColors(containerColor = AntiBSColors.Blue, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth()) { Text(if (s.results.isEmpty()) "Начать тест" else "Повторить") }
                    }
                }
            }

            // ---- result / comparison ----
            if (s.results.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Card { ResultBlock(s.results, s.running) }
            }

            Text("Сервер: speed.cloudflare.com (ближайший узел Cloudflare). Тест расходует трафик — до ~600 МБ на быстром канале.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))

            // ---- history ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SectionLabel("История (${history.size})")
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty() && !s.running) TextButton(onClick = { confirmClear = true }) { Text("Очистить", fontSize = 13.sp) }
            }
            if (history.isEmpty()) {
                Text("Пока нет измерений.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
            } else {
                Card {
                    Column {
                        history.forEachIndexed { i, r ->
                            HistoryRow(r)
                            if (i < history.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Очистить историю?") },
        confirmButton = { TextButton(onClick = { vm.clearSpeedHistory(); confirmClear = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
    )
}

/** Share of one run already done: meta 3 %, ping 7 %, download 45 %, upload 45 %. */
private fun phaseFraction(p: SpeedTest.Phase?, f: Float): Float = when (p) {
    null -> 0f
    SpeedTest.Phase.META -> 0.03f * f
    SpeedTest.Phase.LATENCY -> 0.03f + 0.07f * f
    SpeedTest.Phase.DOWNLOAD -> 0.10f + 0.45f * f
    SpeedTest.Phase.UPLOAD -> 0.55f + 0.45f * f
    SpeedTest.Phase.DONE -> 1f
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape)) { content() }
}

@Composable
private fun ModeChip(text: String, dot: Color?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg.copy(alpha = if (enabled || selected) 1f else 0.45f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg.copy(alpha = if (enabled || selected) 1f else 0.5f), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Gauge(mbps: Double, color: Color, phase: String, upload: Boolean) {
    val target = SpeedMath.gaugeFraction(mbps)
    val spec = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.85f, stiffness = 60f)
    val frac by animateFloatAsState(target, spec, label = "gauge")
    val shown by animateFloatAsState(mbps.toFloat(), spec, label = "gaugeValue")
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val accent = if (upload) AntiBSColors.Warn else color
    Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(230.dp)) {
            val stroke = 16.dp.toPx()
            val r = size.minDimension / 2 - stroke
            val tl = Offset(center.x - r, center.y - r)
            val arc = Size(r * 2, r * 2)
            drawArc(track, GAUGE_START, GAUGE_SWEEP, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (frac > 0.002f) {
                drawArc(Brush.sweepGradient(listOf(accent.copy(alpha = 0.55f), accent, accent.copy(alpha = 0.55f)), center),
                    GAUGE_START, GAUGE_SWEEP * frac, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val a = Math.toRadians((GAUGE_START + GAUGE_SWEEP * frac).toDouble())
                drawCircle(Color.White, stroke * 0.32f, Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
            }
            // tick labels inside the arc
            GaugeTicks.forEach { v ->
                val a = Math.toRadians((GAUGE_START + GAUGE_SWEEP * SpeedMath.gaugeFraction(v)).toDouble())
                val rr = r - stroke * 1.55f
                val label = if (v >= 1000) "1G" else v.toInt().toString()
                val tl2 = measurer.measure(label, TextStyle(fontSize = 9.sp, color = tickColor))
                drawText(tl2, topLeft = Offset(center.x + rr * cos(a).toFloat() - tl2.size.width / 2f, center.y + rr * sin(a).toFloat() - tl2.size.height / 2f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (shown < 0.005f) "0" else SpeedMath.fmtMbps(shown.toDouble()),
                fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("Мбит/с", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(phase, fontSize = 13.sp, color = accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Metric(title: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun place(r: SpeedRecord): String = listOf(r.colo, r.city).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun ResultBlock(results: List<SpeedRecord>, running: Boolean) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        if (results.size == 1) {
            val r = results.first()
            Text("Результат · ${speedModeName(r.mode)}", fontWeight = FontWeight.SemiBold, color = speedModeColor(r.mode), fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            InfoLine("Узел Cloudflare", place(r).ifEmpty { "—" })
            if (r.ip.isNotEmpty()) InfoLine("Внешний IP", r.ip)
            InfoLine(if (r.mode == "vpn") "Хост VPN" else "Сеть", (if (r.mode == "vpn") r.host else r.network).ifEmpty { "—" })
            r.error?.let { Text(it, color = AntiBSColors.Err, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        } else {
            val v = results.firstOrNull { it.mode == "vpn" }
            val d = results.firstOrNull { it.mode == "direct" }
            Text("Сравнение", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            CompareRow("", "Через VPN", "Напрямую", "Δ VPN", header = true)
            CompareRow("Загрузка, Мбит/с", SpeedMath.fmtMbps(v?.down), SpeedMath.fmtMbps(d?.down), delta(d?.down, v?.down, higherBetter = true))
            CompareRow("Отдача, Мбит/с", SpeedMath.fmtMbps(v?.up), SpeedMath.fmtMbps(d?.up), delta(d?.up, v?.up, higherBetter = true))
            CompareRow("Пинг, мс", SpeedMath.fmtMs(v?.ping), SpeedMath.fmtMs(d?.ping), delta(d?.ping, v?.ping, higherBetter = false))
            CompareRow("Джиттер, мс", SpeedMath.fmtMs(v?.jitter), SpeedMath.fmtMs(d?.jitter), delta(d?.jitter, v?.jitter, higherBetter = false))
            CompareRow("Узел", v?.colo?.ifEmpty { "—" } ?: "—", d?.colo?.ifEmpty { "—" } ?: "—", "")
            Spacer(Modifier.height(6.dp))
            v?.let { InfoLine("Хост VPN", it.host.ifEmpty { "—" }) }
            d?.let { InfoLine("Сеть (напрямую)", it.network.ifEmpty { "—" }) }
            listOfNotNull(v?.error?.let { "VPN: $it" }, d?.error?.let { "Напрямую: $it" }).forEach {
                Text(it, color = AntiBSColors.Err, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Δ of the VPN value vs the direct one, coloured green when VPN is better (or within 5 %). */
private data class Delta(val text: String, val good: Boolean?)

private fun delta(direct: Double?, vpn: Double?, higherBetter: Boolean): Delta {
    val p = SpeedMath.deltaPercent(direct, vpn) ?: return Delta("", null)
    val sign = if (p >= 0) "+" else "−"
    val good = if (higherBetter) p >= -5 else p <= 5
    return Delta("$sign${String.format(Locale.US, "%.0f", kotlin.math.abs(p))}%", good)
}

@Composable
private fun CompareRow(title: String, a: String, b: String, d: Any, header: Boolean = false) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val (dt, dc) = when (d) {
        is Delta -> d.text to when (d.good) { true -> AntiBSColors.Ok; false -> AntiBSColors.Err; null -> dim }
        else -> d.toString() to dim
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, color = dim, modifier = Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a, fontSize = if (header) 11.sp else 14.sp, fontWeight = if (header) FontWeight.Normal else FontWeight.SemiBold,
            color = if (header) AntiBSColors.VpnGraph else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(b, fontSize = if (header) 11.sp else 14.sp, fontWeight = if (header) FontWeight.Normal else FontWeight.SemiBold,
            color = if (header) AntiBSColors.DirectGraph else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(dt, fontSize = 12.sp, color = dc, modifier = Modifier.weight(0.7f))
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(v, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(0.58f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val histFmt = SimpleDateFormat("dd.MM.yy HH:mm", Locale("ru"))

@Composable
private fun HistoryRow(r: SpeedRecord) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(speedModeColor(r.mode)))
            Spacer(Modifier.width(6.dp))
            Text(speedModeName(r.mode), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = speedModeColor(r.mode))
            Spacer(Modifier.width(6.dp))
            Text((if (r.mode == "vpn") r.host else r.network).ifEmpty { "—" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(histFmt.format(Date(r.ts)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(3.dp))
        if (r.down == null && r.up == null && r.error != null) {
            Text(r.error, fontSize = 12.sp, color = AntiBSColors.Err)
        } else {
            Text(
                "↓ ${SpeedMath.fmtMbps(r.down)}  ↑ ${SpeedMath.fmtMbps(r.up)} Мбит/с · пинг ${SpeedMath.fmtMs(r.ping)} мс · джиттер ${SpeedMath.fmtMs(r.jitter)} мс" +
                    (place(r).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + (r.error?.let { " · $it" } ?: ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
