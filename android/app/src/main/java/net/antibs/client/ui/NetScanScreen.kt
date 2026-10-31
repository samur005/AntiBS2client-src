package net.antibs.client.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.antibs.client.data.NetScanLogic
import net.antibs.client.engine.NetScanner
import net.antibs.client.data.DeviceIdLogic.DevType
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateMapOf as stateMap
import androidx.compose.ui.graphics.vector.ImageVector

/** 1.0.59: user names for LAN devices, persisted by IP. */
private object DeviceNames {
    private fun prefs(ctx: android.content.Context) = ctx.applicationContext.getSharedPreferences("lan_device_names", android.content.Context.MODE_PRIVATE)
    fun all(ctx: android.content.Context): Map<String, String> = prefs(ctx).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    fun set(ctx: android.content.Context, ip: String, name: String?) {
        prefs(ctx).edit().apply { if (name.isNullOrBlank()) remove(ip) else putString(ip, name.trim()) }.apply()
    }
}

private fun typeIcon(t: DevType): ImageVector = when (t) {
    DevType.ROUTER -> Icons.Filled.Router
    DevType.PHONE -> Icons.Filled.Smartphone
    DevType.PC -> Icons.Filled.Computer
    DevType.TV -> Icons.Filled.Tv
    DevType.PRINTER -> Icons.Filled.Print
    DevType.CAMERA -> Icons.Filled.Videocam
    DevType.NAS -> Icons.Filled.Storage
    DevType.SPEAKER -> Icons.Filled.Speaker
    DevType.IOT -> Icons.Filled.Lightbulb
    DevType.UNKNOWN -> Icons.Filled.DevicesOther
}

private fun NetScanner.Device.devType(): DevType = type ?: when {
    isGateway -> DevType.ROUTER
    isSelf -> DevType.PHONE
    else -> DevType.UNKNOWN
}

/** 1.0.41: «Сканер Wi‑Fi сети» (Инструменты). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NetScanPage(@Suppress("UNUSED_PARAMETER") vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var wifi by remember { mutableStateOf<NetScanner.WifiState?>(null) }
    val devices = remember { mutableStateMapOf<String, NetScanner.Device>() }
    var progress by remember { mutableStateOf(0 to 0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var portJobs by remember { mutableStateOf(setOf<String>()) }
    var finished by remember { mutableStateOf(false) }
    val custom = remember { stateMap<String, String>().apply { putAll(DeviceNames.all(ctx)) } }
    var renaming by remember { mutableStateOf<String?>(null) }
    val locPerm = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasLoc() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var locGranted by remember { mutableStateOf(hasLoc()) }
    suspend fun refreshInfo() { wifi = withContext(Dispatchers.IO) { runCatching { NetScanner.wifiState(ctx) }.getOrNull() } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locGranted = hasLoc(); scope.launch { refreshInfo() }
    }
    LaunchedEffect(Unit) { refreshInfo() }
    val running = job?.isActive == true
    var warning by remember { mutableStateOf<String?>(null) }

    fun start() {
        val st = wifi ?: return
        job?.cancel(); devices.clear(); finished = false; warning = null; expanded = null
        job = scope.launch {
            refreshInfo()
            val s = wifi ?: st
            val res = NetScanner.scan(ctx, s, onProgress = { d, t -> progress = d to t }, onDevice = { d ->
                scope.launch(Dispatchers.Main) { val old = devices[d.ip]; devices[d.ip] = if (old != null && old.portsScanned) d.copy(ports = old.ports, portsScanned = true) else d }
            }, onWarning = { w -> scope.launch(Dispatchers.Main) { warning = w } })
            // 2.0.5: fake (tunnelled) answers are dropped from the final list
            withContext(Dispatchers.Main) { val keep = res.map { it.ip }.toSet(); devices.keys.filter { it !in keep }.forEach { devices.remove(it) } }
            finished = true
        }
    }

    fun reportText(): String = buildString {
        val w = wifi
        appendLine("Wi‑Fi: ${w?.ssid ?: "—"} · IP ${w?.ip ?: "—"}/${w?.prefix ?: ""} · шлюз ${w?.gateway ?: "—"}")
        sorted(devices.values).forEach { d ->
            append(d.ip); append("  [${d.kind}]"); (custom[d.ip] ?: d.name)?.let { append("  «$it»") }; d.model?.let { append("  $it") }; d.host?.let { append("  $it") }; d.mac?.let { append("  $it") }; d.vendor?.let { append("  ($it)") }
            d.latencyMs?.let { append("  ${it} мс") }; if (d.ports.isNotEmpty()) append("  порты ${d.ports.joinToString(",")}")
            appendLine()
        }
    }

    SettingsScaffold("Сканер Wi‑Fi сети", onBack = onBack, actions = {
        TextButton(onClick = { copyText(ctx, reportText()) }, enabled = devices.isNotEmpty()) { Text("Копировать") }
    }) {
        renaming?.let { ip ->
            var txt by remember(ip) { mutableStateOf(custom[ip] ?: devices[ip]?.let { it.name ?: it.host } ?: "") }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Имя устройства") },
                text = {
                    Column {
                        Text(ip, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        OutlinedTextField(txt, { txt = it.take(60) }, singleLine = true, label = { Text("Название") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                },
                confirmButton = { TextButton(onClick = {
                    DeviceNames.set(ctx, ip, txt); if (txt.isBlank()) custom.remove(ip) else custom[ip] = txt.trim(); renaming = null
                }) { Text("Сохранить") } },
                dismissButton = { Row {
                    if (custom[ip] != null) TextButton(onClick = { DeviceNames.set(ctx, ip, null); custom.remove(ip); renaming = null }) { Text("Сбросить") }
                    TextButton(onClick = { renaming = null }) { Text("Отмена") }
                } },
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                val w = wifi
                Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
                    if (w?.network == null) {
                        Text("Wi‑Fi не подключён", fontWeight = FontWeight.SemiBold)
                        Text("Подключитесь к Wi‑Fi сети, чтобы просканировать устройства в ней.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(w.ssid ?: if (locGranted) "Wi‑Fi (имя скрыто — включите геолокацию)" else "Wi‑Fi", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        InfoLine("IP", "${w.ip ?: "—"}/${w.prefix}")
                        InfoLine("Шлюз", w.gateway ?: "—")
                        InfoLine("Подсеть", w.range?.let { "${it.cidr} · ${it.count} адресов" + if (w.prefix < NetScanLogic.MAX_PREFIX_BITS) " (сеть /${w.prefix} — сканируется /22 вокруг вас)" else "" } ?: "—")
                        if (w.dns.isNotEmpty()) InfoLine("DNS", w.dns.joinToString(", "))
                        w.bssid?.let { InfoLine("BSSID", it) }
                        val radio = listOfNotNull(w.freqMhz?.let { if (it > 5900) "6 ГГц" else if (it > 4900) "5 ГГц" else "2,4 ГГц" }, w.linkMbps?.let { "$it Мбит/с" }, w.rssi?.let { "$it dBm" })
                        if (radio.isNotEmpty()) InfoLine("Радио", radio.joinToString(" · "))
                        if (!locGranted) {
                            Text("Имя сети (SSID) Android показывает только с разрешением на геолокацию.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            TextButton(onClick = { permLauncher.launch(locPerm) }) { Text("Разрешить") }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running) OutlinedButton(onClick = { job?.cancel(); job = null }) { Text("Остановить") }
                    else Button(onClick = { start() }, enabled = wifi?.network != null) { Text(if (devices.isEmpty()) "Сканировать" else "Обновить") }
                    OutlinedButton(onClick = { scope.launch { refreshInfo() } }, enabled = !running) { Text("Обновить сведения") }
                }
                if (running || (progress.second > 0 && !finished)) {
                    val (d, t) = progress
                    LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    Text(if (d < t) "Проверено $d из $t · найдено ${devices.size}" else "Определяю имена, модели и типы устройств…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                } else if (finished) {
                    Text("Найдено устройств: ${devices.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                }
                warning?.let { Text("⚠ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                Text(
                    "Сканирование идёт напрямую через Wi‑Fi, в обход VPN (ICMP + быстрые TCP‑пробы, имена и модели через mDNS/Bonjour, SSDP/UPnP, NetBIOS, rDNS роутера, HTTP и отпечаток портов). Нажмите на устройство для подробностей, удерживайте — чтобы переименовать. " +
                        "MAC‑адреса других устройств Android 10+ приложениям не отдаёт — производитель виден, только если система разрешила чтение ARP. " +
                        "Телефоны часто не отвечают на пробы (энергосбережение) и используют случайный MAC.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
            }
            items(sorted(devices.values), key = { it.ip }) { d ->
                val open = expanded == d.ip
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .combinedClickable(onLongClick = { renaming = d.ip }) { expanded = if (open) null else d.ip }.padding(12.dp).animateContentSize(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(typeIcon(d.devType()), contentDescription = d.kind, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp).size(28.dp))
                        Column(Modifier.weight(1f)) {
                            val title = custom[d.ip] ?: d.name ?: d.host
                            Text(title ?: d.kind, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(d.ip + when { d.isSelf -> " · это устройство"; d.isGateway -> " · роутер"; else -> "" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(listOfNotNull(d.kind.takeIf { title != null }, d.model ?: d.vendor).joinToString(" · ").ifEmpty { if (d.type == null) "определяю…" else "тип не определён" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(d.latencyMs?.let { "$it мс" } ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open) {
                        Spacer(Modifier.height(6.dp))
                        InfoLine("Тип", d.kind)
                        custom[d.ip]?.let { InfoLine("Ваше имя", it) }
                        InfoLine("Имя", d.name ?: d.host?.let { "$it (${d.hostSource})" } ?: "не определено")
                        d.model?.let { InfoLine("Модель", it) }
                        if (d.host != null && d.name != null && d.name != d.host) InfoLine("DNS‑имя", "${d.host} (${d.hostSource})")
                        InfoLine("MAC", d.mac ?: "недоступен (ограничение Android 10+)")
                        d.vendor?.let { InfoLine("Производитель", it) }
                        InfoLine("Обнаружен", d.via)
                        val names = NetScanLogic.COMMON_PORTS.toMap()
                        InfoLine("Порты", if (d.ports.isEmpty()) (if (d.portsScanned) "открытых не найдено" else "—") else d.ports.joinToString(", ") { p -> names[p]?.let { "$p $it" } ?: "$p" })
                        if (d.evidence.isNotEmpty()) {
                            Text("Признаки", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                            d.evidence.forEach { Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TextButton(onClick = { renaming = d.ip }) { Text("Переименовать") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val busy = d.ip in portJobs
                            TextButton(enabled = !busy, onClick = {
                                portJobs = portJobs + d.ip
                                scope.launch {
                                    val ports = runCatching { NetScanner.scanPorts(ctx, d.ip) }.getOrDefault(emptyList())
                                    devices[d.ip]?.let { devices[d.ip] = it.copy(ports = (ports + it.ports).distinct().sorted(), portsScanned = true) }
                                    portJobs = portJobs - d.ip
                                }
                            }) { Text(if (busy) "Сканирую порты…" else "Сканировать порты") }
                            TextButton(onClick = { copyText(ctx, d.ip) }) { Text("Копировать IP") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun sorted(c: Collection<NetScanner.Device>) = c.sortedBy { NetScanLogic.ipToInt(it.ip)?.toLong()?.and(0xffffffffL) ?: 0L }

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(v, fontSize = 12.sp)
    }
}
