package net.antibs.client.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import net.antibs.client.cell.*
import kotlin.math.exp

private enum class NetTab(val title: String) { Cell("Сотовая"), Wifi("Wi‑Fi"), Lan("LAN"), Tools("Утилиты") }

/** quality grade 0..4 → colour (bad red … excellent green). */
internal fun gradeColor(g: Int?): Color = when (g) { 4 -> Color(0xFF2ECC71); 3 -> Color(0xFF9BD34A); 2 -> Color(0xFFF5C518); 1 -> Color(0xFFFF8A3D); 0 -> Color(0xFFFF4D5E); else -> Color(0xFF7D848E) }

/** 1.0.56: «Сеть» — сотовый мониторинг, Wi‑Fi анализатор, LAN сканер, утилиты. */
@Composable
internal fun NetToolsPage(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(NetTab.Cell.name) }
    val perms = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    fun missing() = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED && it != Manifest.permission.ACCESS_COARSE_LOCATION }
    var miss by remember { mutableStateOf(missing()) }
    var locOn by remember { mutableStateOf(NetLocation.enabled(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { miss = missing(); locOn = NetLocation.enabled(ctx) }
    val monitor = remember { CellMonitor(ctx) }
    DisposableEffect(miss) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    SettingsScaffold("Сеть", onBack) {
        Column(Modifier.fillMaxSize()) {
            if (miss.isNotEmpty() || !locOn) PermissionCard(miss, locOn, onGrant = { launcher.launch(perms) },
                onLocation = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); locOn = NetLocation.enabled(ctx) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NetTab.entries.forEach { t -> FilterChip(selected = tab == t.name, onClick = { tab = t.name }, label = { Text(t.title, fontSize = 13.sp) }) }
            }
            when (NetTab.valueOf(tab)) {
                NetTab.Cell -> CellSection(monitor)
                NetTab.Wifi -> WifiSection()
                NetTab.Lan -> CompositionLocalProvider(LocalInTabs provides true) { NetScanPage(vm, onBack) }
                NetTab.Tools -> ToolsSection()
            }
        }
    }
}

@Composable
private fun PermissionCard(miss: List<String>, locOn: Boolean, onGrant: () -> Unit, onLocation: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text("Нужны разрешения", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(buildString {
                if (Manifest.permission.ACCESS_FINE_LOCATION in miss) append("• Геолокация — Android выдаёт данные о сотах и Wi‑Fi сетях только с ней.\n")
                if (Manifest.permission.READ_PHONE_STATE in miss) append("• Состояние телефона — список SIM, тип сети, агрегация несущих.\n")
                if (Build.VERSION.SDK_INT >= 33 && Manifest.permission.NEARBY_WIFI_DEVICES in miss) append("• Устройства Wi‑Fi поблизости — список сетей для анализатора.\n")
                if (!locOn) append("• Включите службу геолокации — без неё список сот и сетей Wi‑Fi пуст.\n")
                append("Данные остаются на устройстве и никуда не отправляются.")
            }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (miss.isNotEmpty()) Button(onClick = onGrant) { Text("Разрешить") }
                if (!locOn) OutlinedButton(onClick = onLocation) { Text("Включить геолокацию") }
            }
        }
    }
}

// ───────────────────────── Сотовая ─────────────────────────

@Composable
private fun CellSection(monitor: CellMonitor) {
    Box(Modifier.fillMaxSize()) { MonitorTab(monitor) }
}

private enum class NSort(val title: String) { None("Без сортировки"), Signal("По сигналу"), Pci("По PCI"), Freq("По частоте") }

@Composable
private fun MonitorTab(monitor: CellMonitor) {
    val sims by monitor.sims.collectAsState()
    val down by monitor.rateDown.collectAsState()
    val up by monitor.rateUp.collectAsState()
    val err by monitor.error.collectAsState()
    val dataSub by monitor.dataSub.collectAsState()
    // Explicit tab choice (subId) for this session; reset when the data SIM changes.
    var userSub by androidx.compose.runtime.saveable.rememberSaveable(dataSub) { mutableStateOf<Int?>(null) }
    val sel = net.antibs.client.cell.DataSimPick.index(sims.map { it.subId }, userSub, dataSub)
    var sort by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(NSort.Signal.name) }
    val sim = sims.getOrNull(sel) ?: sims.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Мониторинг", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(sims.joinToString("  ·  ") { "SIM${it.slot + 1}: ${it.carrier}" }.ifBlank { err ?: "Ожидание данных модема…" },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sims.size > 1) item {
            TabRow(selectedTabIndex = sims.indexOf(sim).coerceAtLeast(0), containerColor = Color.Transparent) {
                sims.forEachIndexed { i, s -> Tab(selected = sim == s, onClick = { userSub = s.subId }, text = { Text("SIM${s.slot + 1} · ${s.carrier}" + if (s.subId == dataSub) " · данные" else "") }) }
            }
        }
        if (sim == null) return@LazyColumn
        val c = sim.serving.firstOrNull()
        item { ServingCard(sim, c, down, up) }
        sim.nsaNr?.let { nr -> item { NsaCard(nr) } }
        if (sim.serving.size > 1) item {
            Panel { Text("Другие обслуживающие соты (CA / двойная регистрация)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                sim.serving.drop(1).forEach { NeighbourRow(it) } }
        }
        sim.physChannels?.let { pcs -> item { Panel { Text("Физические каналы (PhysicalChannelConfig)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp); pcs.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } } } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Соседние соты (${sim.neighbours.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }) { Text(NSort.valueOf(sort).title); Icon(Icons.Filled.ArrowDropDown, null) }
                    DropdownMenu(open, { open = false }) { NSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.title) }, leadingIcon = { MenuIcon(when (s) { NSort.None -> Icons.Filled.FormatListBulleted; NSort.Signal -> Icons.Filled.SignalCellularAlt; NSort.Pci -> Icons.Filled.Tag; NSort.Freq -> Icons.Filled.GraphicEq }) }, onClick = { sort = s.name; open = false }) } }
                }
            }
        }
        val nb = when (NSort.valueOf(sort)) {
            NSort.None -> sim.neighbours
            NSort.Signal -> sim.neighbours.sortedByDescending { it.strength ?: -999 }
            NSort.Pci -> sim.neighbours.sortedBy { it.pci ?: Int.MAX_VALUE }
            NSort.Freq -> sim.neighbours.sortedBy { it.band?.dlMhz ?: Double.MAX_VALUE }
        }
        items(nb) { NeighbourRow(it) }
        if (nb.isEmpty()) item { Text("Модем не сообщает соседние соты", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

private fun v(x: Any?) = x?.toString() ?: "—"

internal fun rate(bps: Long): String = when { bps >= 1_000_000 -> "%.1f".format(bps / 1e6); else -> "%.2f".format(bps / 1e6) }

@Composable
private fun ServingCard(sim: SimSnapshot, c: CellRow?, down: Long, up: Long) {
    Panel {
        val ratLabel = when {
            c == null -> sim.networkType
            c.rat == "LTE" && (sim.caText?.contains("CA") == true && !sim.caText.startsWith("без")) -> "LTE-A " + (c.band?.duplex ?: sim.duplex ?: "")
            c.rat == "LTE" && sim.overrideType == "NR_NSA" -> "LTE + 5G NSA"
            else -> CellMath.ratName(c.rat) + (c.band?.duplex?.let { " $it" } ?: "")
        }
        Text(if (c == null) "Нет обслуживающей соты" else "БС: ${v(c.nodeId)}; сектор: ${v(c.sectorId)} — $ratLabel" + (c.band?.let { " · ${it.band}" } ?: ""), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        val caLine = sim.caText?.let { if (it.startsWith("без")) "CA: нет, ${it.removePrefix("без CA, ")}" else "LTE-A: активно, $it" }
        Text(listOfNotNull(caLine, "сеть ${sim.networkType}", sim.overrideType?.let { "индикация $it" }, sim.nrState?.let { "NR: $it" },
            if (sim.roaming) "роуминг" else null).joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Gauge("RSRP", c.rsrp?.toString(), "дБм", c.rsrp?.let(CellMath::gradeRsrp), c.rsrp?.let { (it + 140) / 97f })
                Gauge("SINR", c.sinr?.let { "%.1f".format(it) }, "дБ", c.sinr?.let(CellMath::gradeSinr), c.sinr?.let { ((it + 10) / 40).toFloat() })
                Gauge("RSSI", c.rssi?.let { (if (c.rssiEstimated) "≈" else "") + it }, "дБм", c.rssi?.let(CellMath::gradeRssi), c.rssi?.let { (it + 113) / 62f })
                Gauge("RSRQ", c.rsrq?.toString(), "дБ", c.rsrq?.let(CellMath::gradeRsrq), c.rsrq?.let { (it + 25) / 22f })
            }
            KvRow(listOf("PLMN" to v(c.plmn), (if (c.rat == "GSM" || c.rat == "UMTS") "LAC" else "TAC") to v(c.tac), "CELL ID" to v(c.ci),
                (if (c.rat == "UMTS") "PSC" else if (c.rat == "GSM") "BSIC" else "PCI") to v(c.pci), "TA" to (c.taMeters?.let { "${c.ta} (${it} м)" } ?: v(c.ta))))
            val fcn = when (c.rat) { "LTE" -> "EARFCN"; "NR" -> "NRARFCN"; "UMTS" -> "UARFCN"; else -> "ARFCN" }
            KvRow(listOf(fcn to (c.channel?.let { ch -> "$ch" + (c.band?.let { " · ${it.band}" } ?: "") } ?: "—"), "Диапазон" to (c.band?.label ?: "—")))
            KvRow(listOf("DL" to (c.band?.dlMhz?.let { "$it МГц" } ?: "—"), "UL" to (c.band?.ulMhz?.let { "$it МГц" } ?: "—"),
                "BW" to (c.bwKhz?.let { CellMath.mhz(it) + " МГц" } ?: sim.bandwidthsKhz.filter { it > 0 }.takeIf { it.isNotEmpty() }?.joinToString("+") { CellMath.mhz(it) }?.plus(" МГц") ?: "—"),
                "THR Мбит/с" to "${rate(down)} ↓ ${rate(up)} ↑"))
            KvRow(listOf("CQI" to v(c.cqi), "Уровень" to (c.level?.let { "$it/4" } ?: "—"), "Оператор" to (c.operator ?: sim.carrier), "Дуплекс" to (c.band?.duplex ?: sim.duplex ?: "—")))
        }
    }
}

@Composable
private fun NsaCard(nr: CellRow) {
    Panel {
        Text("5G NR (NSA, вторичная несущая)", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Gauge("SS-RSRP", nr.rsrp?.toString(), "дБм", nr.rsrp?.let(CellMath::gradeRsrp), nr.rsrp?.let { (it + 140) / 97f })
            Gauge("SS-SINR", nr.sinr?.let { "%.0f".format(it) }, "дБ", nr.sinr?.let(CellMath::gradeSinr), nr.sinr?.let { ((it + 10) / 40).toFloat() })
            Gauge("SS-RSRQ", nr.rsrq?.toString(), "дБ", nr.rsrq?.let(CellMath::gradeRsrq), nr.rsrq?.let { (it + 25) / 22f })
        }
    }
}

@Composable
private fun KvRow(items: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp)) {
        items.forEach { (k, value) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(k, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Gauge(label: String, value: String?, unit: String, grade: Int?, frac: Float?) {
    val col = gradeColor(grade)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val st = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 135f, 270f, false, style = st)
                if (frac != null) drawArc(col, 135f, 270f * frac.coerceIn(0.02f, 1f), false, style = st)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value ?: "—", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else col)
                Text(unit, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NeighbourRow(c: CellRow) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(gradeColor(c.rsrp?.let(CellMath::gradeRsrp) ?: c.rssi?.let(CellMath::gradeRssi))))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("${CellMath.ratName(c.rat)} · PCI ${v(c.pci)} · ${c.band?.label ?: "ch ${v(c.channel)}"}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(listOfNotNull(c.channel?.let { "FCN $it" + (c.band?.let { b -> " · ${b.band}" } ?: "") }, c.band?.dlMhz?.let { "$it МГц" }, c.ci?.let { "CI $it" }, c.plmn).joinToString(" · "),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(listOfNotNull(c.rsrp?.let { "$it дБм" } ?: c.rssi?.let { "$it дБм" }, c.rsrq?.let { "RSRQ $it" }).joinToString("\n"), fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

// ───────────────────────── Wi‑Fi ─────────────────────────

@Composable
private fun WifiSection() {
    val ctx = LocalContext.current
    val an = remember { WifiAnalyzer(ctx) }
    DisposableEffect(Unit) { an.start(); onDispose { an.stop() } }
    val link by an.link.collectAsState()
    val aps by an.aps.collectAsState()
    val status by an.status.collectAsState()
    val hist by an.history.collectAsState()
    var view by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var band by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("2.4 ГГц") }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = view, containerColor = Color.Transparent) {
            listOf("Подключение", "Сети", "Каналы", "График").forEachIndexed { i, t -> Tab(selected = view == i, onClick = { view = i }, text = { Text(t, fontSize = 12.sp) }) }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { an.scan() }) { Icon(Icons.Filled.Refresh, "Сканировать") }
        }
        when (view) {
            0 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                val l = link
                if (l == null) Text("Wi‑Fi не подключён") else Panel {
                    Text(l.ssid ?: "SSID скрыт (нужна геолокация)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(l.bssid, l.vendor).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Gauge("RSSI", l.rssi?.toString(), "дБм", l.rssi?.let(CellMath::gradeRssi), l.rssi?.let { (it + 100) / 70f })
                        Gauge("Скорость", l.linkMbps?.toString(), "Мбит/с", 3, l.linkMbps?.let { it / 1200f })
                    }
                    KvRow(listOf("TX" to (l.txMbps?.let { "$it Мбит/с" } ?: "—"), "RX" to (l.rxMbps?.let { "$it Мбит/с" } ?: "—"), "Стандарт" to l.standard))
                    KvRow(listOf("Частота" to (l.freq?.let { "$it МГц" } ?: "—"), "Канал" to v(l.channel), "Ширина" to (l.widthMhz?.let { "$it МГц" } ?: "—"), "Защита" to v(l.security)))
                    KvRow(listOf("IP" to v(l.ip), "Шлюз" to v(l.gateway)))
                    KvRow(listOf("DNS" to l.dns.joinToString(", ").ifBlank { "—" }))
                }
            }
            1 -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(aps, key = { it.bssid }) { a ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(if (a.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(gradeColor(CellMath.gradeRssi(a.rssi + 10))))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.ssid + if (a.connected) "  (подключено)" else "", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("${a.bssid}${a.vendor?.let { " · $it" } ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${a.band} · кан. ${v(a.channel)} · ${a.freq} МГц · ${a.widthMhz} МГц · ${a.security} · ${a.standard}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${a.rssi} дБм", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            2 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("2.4 ГГц", "5 ГГц", "6 ГГц").forEach { b -> FilterChip(band == b, { band = b }, { Text(b) }) } }
                ChannelGraph(aps.filter { it.band == band }, band)
                ChannelRating(aps, band)
            }
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text("Уровень сигнала во времени", fontWeight = FontWeight.SemiBold)
                SignalHistory(hist, aps)
            }
        }
    }
}

private val palette = listOf(0xFF4C8DFF, 0xFFFF8A4C, 0xFF3DDC97, 0xFFFF5C7A, 0xFFB77CFF, 0xFFF5C518, 0xFF39C5E0, 0xFFE07BD8, 0xFF9BD34A, 0xFFFFB020).map { Color(it) }

@Composable
private fun ChannelGraph(aps: List<WifiAnalyzer.Ap>, band: String) {
    val (lo, hi) = when (band) { "2.4 ГГц" -> 2400 to 2495; "5 ГГц" -> 5150 to 5895; else -> 5925 to 7125 }
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Panel {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height - 18.dp.toPx()
                fun x(f: Double) = ((f - lo) / (hi - lo) * w).toFloat()
                fun y(r: Int) = (h * (1 - (r + 100) / 70f)).coerceIn(0f, h)
                for (r in listOf(-90, -70, -50, -30)) drawLine(grid, Offset(0f, y(r)), Offset(w, y(r)))
                val p = android.graphics.Paint().apply { color = label.toArgb(); textSize = 9.sp.toPx(); isAntiAlias = true }
                val chans = when (band) { "2.4 ГГц" -> (1..13).toList(); "5 ГГц" -> WifiMath.CANDIDATES_5.filterIndexed { i, _ -> i % 2 == 0 }; else -> (1..233 step 16).toList() }
                chans.forEach { ch ->
                    val f = when (band) { "2.4 ГГц" -> WifiMath.freq24(ch); "5 ГГц" -> WifiMath.freq5(ch); else -> 5950 + 5 * ch }
                    drawContext.canvas.nativeCanvas.drawText(ch.toString(), x(f.toDouble()) - 6, size.height - 4, p)
                }
                aps.forEachIndexed { i, a ->
                    val col = palette[i % palette.size]
                    val c = a.center.toDouble(); val half = a.widthMhz / 2.0
                    val path = Path()
                    var first = true
                    var f = c - half - 2
                    while (f <= c + half + 2) {
                        val t = (f - c) / half
                        val yy = h - (h - y(a.rssi)) * exp(-3.0 * t * t * t * t).toFloat()
                        if (first) { path.moveTo(x(f), h); path.lineTo(x(f), yy); first = false } else path.lineTo(x(f), yy)
                        f += 1.0
                    }
                    path.lineTo(x(c + half + 2), h)
                    drawPath(path, col.copy(alpha = 0.18f))
                    drawPath(path, col, style = Stroke(2.dp.toPx()))
                    p.color = col.toArgb(); drawContext.canvas.nativeCanvas.drawText(a.ssid.take(14), x(c) - 20, y(a.rssi) - 4, p)
                }
            }
        }
        Text("Ось Y: RSSI −100…−30 дБм; ширина колокола = ширина канала", fontSize = 10.sp, color = label)
    }
}

@Composable
private fun ChannelRating(aps: List<WifiAnalyzer.Ap>, band: String) {
    val nets = aps.filter { it.band == band }.map { WifiMath.Net(it.freq, it.center, it.widthMhz, it.rssi) }
    val r = when (band) {
        "2.4 ГГц" -> WifiMath.ratings(WifiMath.CANDIDATES_24, WifiMath::freq24, nets)
        "5 ГГц" -> WifiMath.ratings(WifiMath.CANDIDATES_5, WifiMath::freq5, nets)
        else -> WifiMath.ratings(listOf(1, 5, 21, 37, 53, 69, 85, 101), { 5950 + 5 * it }, nets)
    }
    val best = r.maxByOrNull { it.second }
    Spacer(Modifier.height(8.dp))
    Panel {
        Text("Рейтинг каналов", fontWeight = FontWeight.SemiBold)
        best?.let { Text("Рекомендуется канал ${it.first}", color = AntiBSColors.Ok, fontWeight = FontWeight.SemiBold) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.forEach { (ch, s) ->
                Column(Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$ch", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("%.1f".format(s), fontSize = 11.sp, color = gradeColor((s / 2.5).toInt().coerceIn(0, 4)))
                }
            }
        }
        Text("10 — канал свободен; учитываются перекрытия по ширине каналов и уровень сигнала соседей", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignalHistory(hist: Map<String, List<Int>>, aps: List<WifiAnalyzer.Ap>) {
    val top = listOf("link" to "Текущее подключение") + aps.take(6).map { it.bssid to it.ssid }
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Panel {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            fun y(r: Int) = (size.height * (1 - (r + 100) / 70f)).coerceIn(0f, size.height)
            for (r in listOf(-90, -70, -50, -30)) drawLine(grid, Offset(0f, y(r)), Offset(size.width, y(r)))
            top.forEachIndexed { i, (k, _) ->
                val s = hist[k] ?: return@forEachIndexed
                if (s.size < 2) return@forEachIndexed
                val n = if (k == "link") 120 else 60
                val path = Path()
                s.forEachIndexed { j, r -> val xx = size.width * (n - s.size + j) / (n - 1f); if (j == 0) path.moveTo(xx, y(r)) else path.lineTo(xx, y(r)) }
                drawPath(path, palette[i % palette.size], style = Stroke(if (k == "link") 3.dp.toPx() else 1.5.dp.toPx()))
            }
        }
        top.forEachIndexed { i, (k, name) ->
            if (hist[k] != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(palette[i % palette.size])); Spacer(Modifier.width(6.dp))
                Text("$name: ${hist[k]?.lastOrNull()} дБм", fontSize = 11.sp)
            }
        }
        Text("Подключение — раз в секунду (2 мин); остальные сети — по сканированиям", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── Утилиты ─────────────────────────

@Composable
private fun ToolsSection() {
    val scope = rememberCoroutineScope()
    var host by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("8.8.8.8") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun go(block: suspend () -> Unit) { if (!busy) scope.launch { busy = true; out = ""; runCatching { block() }; busy = false } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(host, { host = it }, label = { Text("Хост или IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { go { out = NetTools.ping(host) } }) { Text("Ping") }
            Button(onClick = { go { NetTools.traceroute(host) { l -> out += l + "\n" } } }) { Text("Трассировка") }
            Button(onClick = { go { out = NetTools.dns(host) } }) { Text("DNS") }
        }
        OutlinedButton(onClick = { go { out = "Публичный IP: " + NetTools.publicIp() + "\n(при включённом VPN — адрес выхода VPN)" } }) { Text("Мой публичный IP") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Panel { Text(out.ifBlank { "Результат появится здесь" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Suppress("unused") private val keepSize = Size.Zero
