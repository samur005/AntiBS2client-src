package net.antibs.client.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import net.antibs.client.data.LanProtocol
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.engine.LanTransfer

/** 2.0.12: QR bitmap (black on white, 1px per module, scaled without filtering). */
internal fun lanQrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}

@Composable
private fun LanCard(content: @Composable ColumnScope.() -> Unit) {
    val c = ssColors(LocalAppStyle.current)
    Column(Modifier.fillMaxWidth().clip(ssShape(LocalAppStyle.current, 14.dp)).background(c.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** «Принять с устройства»: code + QR + address; the receiver runs only while this screen is shown. */
@Composable
internal fun LanRecvPage(vm: AppViewModel, onBack: () -> Unit) = LanRecvContent({ vm.receiveLanSubs(it) }, onBack)

@Composable
internal fun LanRecvContent(onReceive: (List<kotlinx.serialization.json.JsonObject>) -> Int, onBack: () -> Unit, preview: LanTransfer.RecvState? = null) {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    if (preview == null) DisposableEffect(Unit) {
        LanTransfer.startReceiver(ctx, onReceive)
        onDispose { LanTransfer.stop() }
    }
    val live by LanTransfer.recv.collectAsState()
    val st = preview ?: live
    val c = ssColors(LocalAppStyle.current)
    SettingsScaffold("Принять с устройства", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val session = st.session
            val qr = remember(session?.let { LanProtocol.payload(it) }) { session?.let { runCatching { lanQrBitmap(LanProtocol.payload(it)) }.getOrNull() } }
            if (st.ip.isBlank()) Text("Нет подключения к Wi‑Fi или точке доступа. Подключите оба устройства к одной сети.", color = AntiBSColors.Err, fontSize = 14.sp)
            if (st.error.isNotBlank()) Text(st.error, color = AntiBSColors.Err, fontSize = 13.sp)
            val qrPart: @Composable () -> Unit = {
            LanCard {
                Text("Отсканируйте этот QR в AntiBS2client на другом устройстве — передача будет зашифрована. Устройства, которые показывают только адрес и код (без сканера этого QR), принимают вручную по IP и PIN; тогда трафик идёт открытым текстом по локальной сети.", color = c.muted, fontSize = 13.sp)
                if (qr != null) Image(qr.asImageBitmap(), "QR для передачи", filterQuality = FilterQuality.None,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(if (isTv) 300.dp else 220.dp).clip(RoundedCornerShape(8.dp)))
                Text("QR действует 10 минут и один раз", color = c.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            }
            val manPart: @Composable () -> Unit = {
            LanCard {
                Text("Или вручную", color = c.muted, fontSize = 13.sp)
                Text("Адрес", color = c.muted, fontSize = 12.sp)
                Text(if (st.ip.isBlank()) "—" else "${st.ip}:${LanProtocol.PORT}", color = c.onCard, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Text("Код", color = c.muted, fontSize = 12.sp)
                Text(st.code.chunked(3).joinToString(" "), color = c.accent, fontSize = if (isTv) 44.sp else 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            }
            if (isTv) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { qrPart() }
                Column(Modifier.weight(1f)) { manPart() }
            } else { qrPart(); manPart() }
            if (st.lastInfo.isNotBlank()) Text(st.lastInfo, color = AntiBSColors.Ok, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Экран должен оставаться открытым до окончания передачи.", color = c.muted, fontSize = 12.sp)
            OutlinedButton(onClick = { LanTransfer.refreshSession() }) { Text("Новый QR-код") }
        }
    }
}

/** «Передать на устройство»: QR scan / discovered devices / manual address + code, subscription checkboxes. */
@Composable
internal fun LanSendPage(vm: AppViewModel, preselect: String?, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    LanSendContent(st.subscriptions, onBack, preselect = preselect)
}

@Composable
internal fun LanSendContent(all: List<net.antibs.client.data.Subscription>, onBack: () -> Unit, previewPeers: List<LanTransfer.Peer>? = null, preselect: String? = null) {
    val ctx = LocalContext.current
    val subs = remember(all) { all.filter { it.kind != "manual" && it.id != ProtocolSupport.MANUAL_SUB_ID && it.url.startsWith("http") } }
    val checked = remember(preselect) { mutableStateMapOf<String, Boolean>().also { m -> if (preselect != null) all.forEach { m[it.id] = it.id == preselect } } }
    val peers = remember { mutableStateListOf<LanTransfer.Peer>().also { l -> previewPeers?.let { l.addAll(it) } } }
    var searching by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<LanProtocol.Session?>(null) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val c = ssColors(LocalAppStyle.current)
    val hasLan = remember { previewPeers != null || LanTransfer.hasLan() }
    fun search() {
        if (previewPeers != null) return
        searching = true; peers.clear()
        scope.launch {
            runCatching {
                LanTransfer.discover(ctx) { p ->
                    scope.launch {
                        if (peers.none { it.ip == p.ip }) peers.add(p)
                        else {
                            val i = peers.indexOfFirst { it.ip == p.ip }
                            if (i >= 0 && peers[i].name == peers[i].ip && p.name != p.ip) peers[i] = p
                        }
                    }
                }
            }
            searching = false
        }
    }
    LaunchedEffect(Unit) { search() }
    val camera = remember { ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val s = r.contents?.let { LanProtocol.parseQr(it) }
        when {
            r.contents == null -> {}
            s == null -> status = "Это не QR-код приёма подписок"
            s.expired() -> status = "QR-код устарел — обновите его на принимающем устройстве"
            else -> { qr = s; host = "${s.ip}:${s.port}"; code = s.code; status = "QR распознан: ${s.ip}" }
        }
    }
    SettingsScaffold("Передать на устройство", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasLan) Text("Нет Wi‑Fi: подключите оба устройства к одной сети Wi‑Fi или точке доступа.", color = AntiBSColors.Err, fontSize = 13.sp)
            if (camera) Button(onClick = {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Наведите на QR-код приёма")
                    .setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(QrScanActivity::class.java))
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Сканировать QR-код") }
            LanCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Устройства в сети", color = c.onCard, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { search() }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Искать снова") }
                }
                if (peers.isEmpty() && !searching) Text("Не найдено. Откройте «Принять с устройства» на другом устройстве.", color = c.muted, fontSize = 13.sp)
                peers.forEach { p ->
                    val sel = qr == null && host.substringBefore(':') == p.ip
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) c.selectedFill else c.chipOff)
                        .clickable { qr = null; host = p.ip }.padding(10.dp)) {
                        Text(p.name, color = c.onCard, modifier = Modifier.weight(1f)); Text(p.ip, color = c.muted, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            LanCard {
                Text("Адрес и код", color = c.onCard, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(host, { host = it.trim(); qr = null }, label = { Text("IP-адрес (например 192.168.1.5)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6); qr = null }, label = { Text("Код (6 цифр)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (qr != null) Text("🔒 Передача будет зашифрована ключом из QR", color = AntiBSColors.Ok, fontSize = 12.sp)
                else Text("⚠ Без QR передача идёт открытым текстом по локальной сети (PIN защищает только от чужого импорта). Доверяйте только своей Wi‑Fi / точке доступа; VPN-интерфейсы не используются.", color = AntiBSColors.Err, fontSize = 12.sp)
            }
            LanCard {
                Text("Подписки", color = c.onCard, fontWeight = FontWeight.SemiBold)
                if (subs.isEmpty()) Text("Нет подписок для передачи", color = c.muted, fontSize = 13.sp)
                subs.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { checked[s.id] = !(checked[s.id] ?: true) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked[s.id] ?: true, { checked[s.id] = it })
                        Text(s.title.ifBlank { s.url }, color = c.onCard, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
            val chosen = subs.filter { checked[it.id] ?: true }
            val addr = LanProtocol.parseAddress(host)
            Button(enabled = !sending && addr != null && code.length == 6 && chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                val a = addr ?: return@Button
                sending = true; status = "Отправка…"
                scope.launch {
                    val key = qr?.takeIf { !it.expired() }?.key
                    status = when (val r = LanTransfer.send(a.first, a.second, code, chosen, key)) {
                        is LanTransfer.SendResult.Confirmed -> "Готово: принято ${r.n} из ${chosen.size}"
                        LanTransfer.SendResult.Unknown -> "Запись на сокет прошла (${chosen.size}), но подтверждения импорта нет — проверьте на принимающем устройстве"
                        is LanTransfer.SendResult.Refused -> if (r.reason == "code") "Неверный код" else "Отклонено: ${r.reason}"
                        is LanTransfer.SendResult.Failed -> "Не удалось подключиться: ${r.message}"
                    }
                    if (key != null) qr = null
                    sending = false
                }
            }) { Text("Передать (${chosen.size})") }
            if (status.isNotBlank()) Text(status, color = c.onCard, fontSize = 14.sp)
        }
    }
}
