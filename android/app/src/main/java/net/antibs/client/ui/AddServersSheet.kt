package net.antibs.client.ui

import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.saveable.rememberSaveable
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.XrayProfile
import java.util.EnumMap

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddServersSheet(
    vm: AppViewModel,
    servers: List<ServerEntry>,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var form by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val isTvSheet = LocalIsTv.current

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        text = if (text.isBlank()) raw else text.trimEnd() + "\n" + raw
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text("Добавить серверы", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            // 1.0.27: import tiles + «Получить подписку» block (same card language as the protocol grid); 1.0.51: Telegram bot only.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportTile(Modifier.weight(1f), Icons.Filled.ContentPaste, androidx.compose.ui.graphics.Color(0xFF4C8DFF), "Из буфера", "Вставить ссылки") {
                    val t = clip.getText()?.text.orEmpty()
                    if (t.isBlank()) vm.message("Буфер пуст") else text = t
                }
                // 2.0.13: TV — «Принять с телефона» tile in the row (no camera tile there)
                if (isTvSheet) TvHooks.receiveFromPhone?.let { recv ->
                    ImportTile(Modifier.weight(1f), Icons.Filled.PhoneAndroid, androidx.compose.ui.graphics.Color(0xFF8E6BFF), "Принять с телефона", "По Wi‑Fi") { recv(ctx) }
                }
                if (ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))
                ImportTile(Modifier.weight(1f), Icons.Filled.QrCodeScanner, androidx.compose.ui.graphics.Color(0xFF1FA89A), "QR-камера", "Сканировать код") {
                    scanLauncher.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Наведите камеру на QR-код").setBeepEnabled(false).setOrientationLocked(true)
                            // 1.0.11: own full-screen scanner (dimmed overlay, scan line, torch, «QR из галереи»).
                            .setCaptureActivity(QrScanActivity::class.java),
                    )
                }
            }
            // 2.0.13: phone — full-width «Принять с устройства» (works with no subscriptions)
            if (!isTvSheet) TvHooks.receiveFromPhone?.let { recv ->
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    ImportTile(Modifier.weight(1f), Icons.Filled.PhoneAndroid, androidx.compose.ui.graphics.Color(0xFF8E6BFF), "Принять с устройства", "По Wi‑Fi с другого устройства") { recv(ctx) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("ПОЛУЧИТЬ ПОДПИСКУ", fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SubscriptionCard(
                emoji = "🔐", title = "🔐 Получить подписку", subtitle = "Telegram-бот",
                brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF37AEE2), androidx.compose.ui.graphics.Color(0xFF1E88D0))),
            ) { openSubscriptionBot(ctx) { vm.message(it) } }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = {
                    Text(
                        "vless://, hy2://, tuic://, olcrtc://, ssr://, snell://… или URL подписки",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                },
                minLines = 3,
                maxLines = 8,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(10.dp))
            val canImport = text.isNotBlank()
            Button(
                onClick = { pendingImport = text },
                enabled = canImport,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text("Импорт", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(18.dp))
            // 1.0.41: collapsible «Создать вручную» (collapsed by default, animated).
            var manualOpen by rememberSaveable { mutableStateOf(false) }
            val chevron by androidx.compose.animation.core.animateFloatAsState(if (manualOpen) 180f else 0f, label = "manualChevron")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { manualOpen = !manualOpen }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Создать вручную", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${ProtoCatalog.groups.sumOf { it.items.size }} протоколов · форма для каждого", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = if (manualOpen) "Свернуть" else "Развернуть",
                    modifier = Modifier.graphicsLayer { rotationZ = chevron }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = manualOpen,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    ProtoGrid { form = it }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    pendingImport?.let { raw ->
        val outcome = remember(raw) { net.antibs.client.data.ImportText.classify(raw, net.antibs.client.data.ProtocolSupport.MANUAL_SUB_ID) }
        val summary = when (outcome) {
            is net.antibs.client.data.ImportOutcome.SubscriptionUrl -> {
                val host = runCatching { java.net.URI(outcome.url).host }.getOrNull().orEmpty().ifBlank { "…" }
                "Подписка:\n$host\n\nURL сам не открывается — подтвердите импорт."
            }
            is net.antibs.client.data.ImportOutcome.Servers -> "Серверов к добавлению: ${outcome.entries.size}"
            is net.antibs.client.data.ImportOutcome.Failed -> "Не распознано:\n${outcome.message}"
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Подтвердить импорт") },
            text = { Text(summary) },
            confirmButton = {
                TextButton(
                    enabled = outcome !is net.antibs.client.data.ImportOutcome.Failed,
                    onClick = {
                        vm.importRaw(raw)
                        pendingImport = null
                        onDismiss()
                    },
                ) { Text("Импорт") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Отмена") } },
        )
    }
    form?.let { proto ->
        if (proto == "olcrtc") {
            OlcrtcFormDialog(onDismiss = { form = null }, onSave = { profile -> vm.addManualProfile(profile); form = null; onDismiss() })
            return@let
        }
        if (proto == "sstp" || proto == "zerotier") {
            PacketVpnFormDialog(proto, vm, onDismiss = { form = null }, onDone = { form = null; onDismiss() })
            return@let
        }
        if (proto == "openvpn" || proto == "openconnect") {
            VpnProtoFormDialog(proto, vm, onDismiss = { form = null }, onDone = { form = null; onDismiss() })
            return@let
        }
        ManualFormDialog(
            protocol = proto,
            servers = servers,
            vm = vm,
            onDismiss = { form = null },
            onSave = { profile ->
                vm.addManualProfile(profile)
                form = null
                onDismiss()
            },
            onSaveQwdtt = { q ->
                vm.addManualQwdtt(q)
                form = null
                onDismiss()
            },
        )
    }
}

const val SUBSCRIPTION_BOT_URL = "https://t.me/bskarf_bot"


/** Telegram app if installed (it handles t.me links), otherwise the browser. */
fun openSubscriptionBot(ctx: android.content.Context, onError: (String) -> Unit) {
    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SUBSCRIPTION_BOT_URL))
        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (_: android.content.ActivityNotFoundException) {
        onError("Нет приложения для ссылки $SUBSCRIPTION_BOT_URL — откройте её вручную")
    } catch (e: Exception) { onError("Не удалось открыть $SUBSCRIPTION_BOT_URL: ${e.message}") }
}

/** 1.0.25: grouped 3-column grid of protocol cards (monogram badge, name, subtitle; press = scale + ripple). */
@Composable
internal fun ProtoGrid(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtoCatalog.groups.forEach { g ->
            val accent = androidx.compose.ui.graphics.Color(g.family.argb)
            Row(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(g.title.uppercase(), fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ProtoCatalog.rows(g.items).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        if (item == null) Spacer(Modifier.weight(1f))
                        else ProtoCard(item, Modifier.weight(1f)) { onPick(item.protocol) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoCard(item: ProtoCatalog.Item, modifier: Modifier, onClick: () -> Unit) {
    val accent = androidx.compose.ui.graphics.Color(item.family.argb)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.94f else 1f, androidx.compose.animation.core.spring(stiffness = 600f), label = "protoScale")
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .height(86.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .border(1.dp, if (pressed) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(color = accent), onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(28.dp).background(
                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.12f))),
                    RoundedCornerShape(9.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.monogram, color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
            }
            Column {
                Text(item.label, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(item.subtitle, fontSize = 10.5.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ImportTile(modifier: Modifier, icon: ImageVector, accent: androidx.compose.ui.graphics.Color, title: String, subtitle: String, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.95f else 1f, androidx.compose.animation.core.spring(stiffness = 600f), label = "tileScale")
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .height(72.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .border(1.dp, if (pressed) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(color = accent), onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(
                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.12f))),
                    RoundedCornerShape(12.dp),
                ),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(subtitle, fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

/** Wide gradient card: emoji badge, title, subtitle, chevron. White text reads on both themes. */
@Composable
private fun SubscriptionCard(emoji: String, title: String, subtitle: String, brush: androidx.compose.ui.graphics.Brush, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.97f else 1f, androidx.compose.animation.core.spring(stiffness = 600f), label = "subScale")
    val shape = RoundedCornerShape(18.dp)
    val white = androidx.compose.ui.graphics.Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (pressed) 2f else 8f; this.shape = shape; clip = true }
            .background(brush, shape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(color = white), onClick = onClick),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(white.copy(alpha = 0.22f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Text(emoji, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = white, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, color = white.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = white, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun ManualFormDialog(
    protocol: String,
    servers: List<ServerEntry>,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onSave: (XrayProfile) -> Unit,
    onSaveQwdtt: (net.antibs.client.data.QwdttProfile) -> Unit = {},
) {
    val reason = ProtocolSupport.unsupportedReason(protocol)
    var remarks by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(when (protocol) {
        "hysteria", "hysteria1", "http", "https", "http3", "tuic", "anytls", "naive", "mieru" -> "443"
        "socks", "socks4" -> "1080"; "wireguard", "amneziawg" -> "51820"; "ssh" -> "22"; "snell" -> "440"; "ssr" -> "8388"; else -> "443"
    }) }
    var uuid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(when (protocol) { "shadowsocks" -> "aes-256-gcm"; "ssr" -> "aes-256-cfb"; "snell" -> ""; else -> "auto" }) }
    var network by remember { mutableStateOf("tcp") }
    var security by remember { mutableStateOf(when (protocol) {
        "hysteria", "hysteria1", "trojan", "https", "http3", "tuic", "anytls", "naive" -> "tls"
        else -> ""
    }) }
    var congestion by remember { mutableStateOf("bbr") }
    var udpRelay by remember { mutableStateOf("native") }
    var snellVer by remember { mutableStateOf("4") }
    var privateKeyText by remember { mutableStateOf("") }
    var mieruTransport by remember { mutableStateOf("TCP") }
    var ssrProto by remember { mutableStateOf("origin") }
    var ssrProtoParam by remember { mutableStateOf("") }
    var ssrObfs by remember { mutableStateOf("plain") }
    var ssrObfsParam by remember { mutableStateOf("") }
    var socks4a by remember { mutableStateOf(true) }
    var alpn by remember { mutableStateOf("") }
    var insecure by remember { mutableStateOf(false) }
    var ttQuic by remember { mutableStateOf(false) }
    var sqUdpStream by remember { mutableStateOf(true) }
    var sqZeroRtt by remember { mutableStateOf(false) }
    var sni by remember { mutableStateOf("") }
    var fp by remember { mutableStateOf("chrome") }
    var pbk by remember { mutableStateOf("") }
    var sid by remember { mutableStateOf("") }
    var flow by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var localAddress by remember { mutableStateOf("172.16.0.2/32") }
    var obfs by remember { mutableStateOf("") }
    var json by remember { mutableStateOf("") }
    var chainIds by remember { mutableStateOf(listOf<String>()) }
    var balIds by remember { mutableStateOf(listOf<String>()) }
    var balStrategy by remember { mutableStateOf("leastPing") }
    // 1.0.26: WireGuard/AmneziaWG extras, qWDTT, Amnezia key, Mieru v3, Shadowsocks plugin
    var psk by remember { mutableStateOf("") }
    var mtu by remember { mutableStateOf("") }
    var reserved by remember { mutableStateOf("") }
    val awg = remember { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(net.antibs.client.data.ManualForms.AWG_DEFAULTS) } }
    var qPeer by remember { mutableStateOf("") }
    var qHashes by remember { mutableStateOf("") }
    var qWorkers by remember { mutableStateOf("9") }
    var qPort by remember { mutableStateOf("9000") }
    var akText by remember { mutableStateOf("") }
    var mieruMux by remember { mutableStateOf("") }
    var mieruHs by remember { mutableStateOf("") }
    var ssObfs by remember { mutableStateOf("") }
    val formErrors: List<String> = when (protocol) {
        "amneziawg" -> net.antibs.client.data.ManualForms.amneziaWgErrors(server, port, secretKey, pbk, psk, localAddress, mtu, awg.toMap())
        "qwdtt" -> net.antibs.client.data.ManualForms.qwdttErrors(qPeer, qWorkers, qPort, password)
        "amneziakey" -> if (akText.isBlank()) listOf("ключ") else if (net.antibs.client.data.ManualForms.amneziaKey(akText) == null) listOf("ключ vpn:// не распознан") else emptyList()
        "wireguard" -> buildList {
            if (secretKey.isNotBlank() && !net.antibs.client.data.ManualForms.isWgKey(secretKey)) add("secret key")
            if (pbk.isNotBlank() && !net.antibs.client.data.ManualForms.isWgKey(pbk)) add("public key")
            if (psk.isNotBlank() && !net.antibs.client.data.ManualForms.isWgKey(psk)) add("PSK")
            if (reserved.isNotBlank() && reserved.split(',').map { it.trim().toIntOrNull() }.let { r -> r.size != 3 || r.any { it == null || it !in 0..255 } }) add("reserved (три числа 0–255)")
        }
        else -> emptyList()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Format.protocolLabel(protocol)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reason != null) {
                    Text(reason, color = AntiBSColors.Warn, fontSize = 13.sp)
                    Text("Можно сохранить карточку, но подключение будет отклонено.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (protocol == "snell") {
                    Text("v4/v6 — sing-box, v1–v3 — mihomo.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (ProtocolSupport.isSingBox(protocol)) {
                    Text("Через sing-box (второй движок).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (ProtocolSupport.isMihomo(protocol)) {
                    Text(if (protocol == "http3") "HTTP/3 CONNECT (quic-go, как в Exclave). Только TCP." else "Через mihomo (третий движок).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (formErrors.isNotEmpty() && listOf(server, secretKey, pbk, qPeer, akText, password).any { it.isNotBlank() })
                    Text("Проверьте: " + formErrors.joinToString(", "), color = AntiBSColors.Err, fontSize = 12.sp)
                when (protocol) {
                    "qwdtt" -> {
                        Text("qWDTT: WireGuard через VK TURN (Go-клиент). VK-хеши можно не указывать — тогда берутся из Настройки → qWDTT.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(remarks, { remarks = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(qPeer, { qPeer = it.trim() }, label = { Text("Peer (хост:порт сервера)") }, placeholder = { Text("nl.example.com:56000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(qHashes, { qHashes = it }, label = { Text("VK-хеши (через запятую, необязательно)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(qWorkers, { qWorkers = it.filter(Char::isDigit) }, label = { Text("Потоки (по умолчанию 9)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(qPort, { qPort = it.filter(Char::isDigit) }, label = { Text("Локальный порт (по умолчанию 9000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "amneziakey" -> {
                        Text("Ключ AmneziaVPN (vpn://…): AmneziaWG, WireGuard или Xray — разбирается так же, как при импорте.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(akText, { akText = it }, label = { Text("vpn://…") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(remarks, { remarks = it }, label = { Text("Имя (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        net.antibs.client.data.ManualForms.amneziaKey(akText)?.let { p ->
                            Text("Распознано: ${Format.protocolLabel(p.protocol)} · ${p.server}${if (p.port > 0) ":${p.port}" else ""}", fontSize = 12.sp, color = AntiBSColors.Ok)
                        }
                    }
                    "custom" -> OutlinedTextField(json, { json = it }, label = { Text("Outbound / полный JSON") }, minLines = 6, modifier = Modifier.fillMaxWidth())
                    "chain", "balancer" -> {
                        Text(
                            if (protocol == "chain") "Выберите серверы по порядку (последний — выход)."
                            else "Выберите серверы для балансировщика.",
                            fontSize = 13.sp,
                        )
                        if (protocol == "balancer") {
                            Row {
                                TextButton(onClick = { balStrategy = "leastPing" }) {
                                    Text(if (balStrategy == "leastPing") "● leastPing" else "○ leastPing")
                                }
                                TextButton(onClick = { balStrategy = "random" }) {
                                    Text(if (balStrategy == "random") "● random" else "○ random")
                                }
                            }
                        }
                        val pickable = servers.filter {
                            val x = it.xray
                            x != null && x.protocol !in setOf("chain", "balancer", "custom") && x.unsupportedReason.isNullOrBlank()
                        }
                        // 1.0.51: hosts grouped by subscription (collapsible), ping all / per group, results per host
                        ServerPicker(
                            vm = vm,
                            pickable = pickable,
                            chain = protocol == "chain",
                            picked = if (protocol == "chain") chainIds else balIds,
                            onToggle = { id ->
                                if (protocol == "chain") chainIds = if (id in chainIds) chainIds - id else chainIds + id
                                else balIds = if (id in balIds) balIds - id else balIds + id
                            },
                        )
                        OutlinedTextField(remarks, { remarks = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedTextField(remarks, { remarks = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(server, { server = it }, label = { Text("Адрес") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text("Порт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        when (protocol) {
                            "vless", "vmess" -> OutlinedTextField(uuid, { uuid = it }, label = { Text("UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            "trojan", "hysteria", "hysteria1", "shadowsocks", "tuic", "anytls", "snell", "naive", "ssh", "ssr" ->
                                OutlinedTextField(password, { password = it }, label = { Text(when (protocol) { "hysteria" -> "Auth"; "snell" -> "PSK"; else -> "Пароль" }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            "socks4" -> OutlinedTextField(username, { username = it }, label = { Text("User ID (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            "socks", "http", "https", "http3", "mieru", "trusttunnel", "shadowquic" -> {
                                OutlinedTextField(username, { username = it }, label = { Text(if (protocol == "mieru") "Имя пользователя" else "Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            "wireguard", "amneziawg" -> {
                                OutlinedTextField(secretKey, { secretKey = it.trim() }, label = { Text("Приватный ключ (PrivateKey)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(pbk, { pbk = it.trim() }, label = { Text("Публичный ключ сервера (PublicKey)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(psk, { psk = it.trim() }, label = { Text("PresharedKey (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(localAddress, { localAddress = it }, label = { Text("Address (через запятую)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(mtu, { mtu = it.filter(Char::isDigit) }, label = { Text(if (protocol == "amneziawg") "MTU (1100–1500, необязательно)" else "MTU (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                if (protocol == "wireguard") OutlinedTextField(reserved, { reserved = it }, label = { Text("reserved (напр. 1,2,3; необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                if (protocol == "amneziawg") {
                                    Text("Параметры обфускации (из конфига сервера: Jc, Jmin, Jmax, S1–S4, H1–H4, I1–I5). H1–H4 — число или диапазон AWG 2.0.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    (net.antibs.client.data.ManualForms.AWG_INT_KEYS + net.antibs.client.data.ManualForms.AWG_STR_KEYS).chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            pair.forEach { k ->
                                                OutlinedTextField(awg[k].orEmpty(), { v -> awg[k] = v.trim() }, label = { Text(k.replaceFirstChar { it.uppercase() }) }, singleLine = true, modifier = Modifier.weight(1f))
                                            }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        if (protocol == "shadowsocks") {
                            Text("Метод", fontSize = 13.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf(
                                    listOf("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305"),
                                    listOf("2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm"),
                                    listOf("2022-blake3-chacha20-poly1305", "xchacha20-ietf-poly1305", "none"),
                                ).forEach { OptionRow(it, method) { m -> method = m } }
                            }
                            if (method.startsWith("2022-")) Text("SS 2022: пароль — base64-ключ (16 байт для aes-128, 32 — для остальных).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Плагин", fontSize = 13.sp)
                            OptionRow(listOf("" to "нет", "http" to "obfs-local (http)"), ssObfs) { ssObfs = it }
                            if (ssObfs.isNotBlank()) OutlinedTextField(host, { host = it }, label = { Text("obfs-host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "vmess") {
                            OutlinedTextField(method, { method = it }, label = { Text(if (protocol == "vmess") "Security" else "Method") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol in setOf("vless", "vmess", "trojan", "shadowsocks")) {
                            OutlinedTextField(network, { network = it }, label = { Text("Transport") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(security, { security = it }, label = { Text("Security (tls/reality)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(sni, { sni = it }, label = { Text("SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(fp, { fp = it }, label = { Text("fp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(path, { path = it }, label = { Text("path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(host, { host = it }, label = { Text("host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (security == "reality" || protocol == "vless") {
                                OutlinedTextField(pbk, { pbk = it }, label = { Text("pbk") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(sid, { sid = it }, label = { Text("sid") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(flow, { flow = it }, label = { Text("flow") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (protocol == "hysteria" || protocol == "hysteria1") {
                            OutlinedTextField(sni, { sni = it }, label = { Text("SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(obfs, { obfs = it }, label = { Text(if (protocol == "hysteria1") "obfs (xplus)" else "obfs-password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "tuic") {
                            OutlinedTextField(uuid, { uuid = it }, label = { Text("UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(sni, { sni = it }, label = { Text("SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(congestion, { congestion = it }, label = { Text("congestion_control") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(udpRelay, { udpRelay = it }, label = { Text("udp_relay_mode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "anytls" || protocol == "naive") {
                            OutlinedTextField(sni, { sni = it }, label = { Text("SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (protocol == "naive") {
                                OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (protocol == "ssh") {
                            OutlinedTextField(username, { username = it }, label = { Text("Пользователь") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(privateKeyText, { privateKeyText = it }, label = { Text("Приватный ключ (PEM)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "snell") {
                            Text("Версия", fontSize = 13.sp)
                            OptionRow(listOf("1", "2", "3", "4", "5", "6"), snellVer) { snellVer = it }
                            Text("obfs", fontSize = 13.sp)
                            OptionRow(listOf("" to "нет", "http" to "http", "tls" to "tls"), method) { method = it }
                            if (method.isNotBlank()) OutlinedTextField(host, { host = it }, label = { Text("obfs-host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "https") {
                            OutlinedTextField(sni, { sni = it }, label = { Text("SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "http3") {
                            OutlinedTextField(path, { path = it }, label = { Text("Путь (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(host, { host = it }, label = { Text("Host / :authority (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(sni, { sni = it }, label = { Text("SNI (по умолчанию — адрес)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            CheckLine("Не проверять сертификат", insecure) { insecure = it }
                        }
                        if (protocol == "mieru") {
                            Text("Транспорт", fontSize = 13.sp)
                            OptionRow(listOf("TCP", "UDP"), mieruTransport) { mieruTransport = it }
                            OutlinedTextField(mtu, { mtu = it.filter(Char::isDigit) }, label = { Text("MTU (для UDP, необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("Мультиплексирование", fontSize = 13.sp)
                            OptionRow(listOf("" to "по умолч.", "MULTIPLEXING_OFF" to "off", "MULTIPLEXING_LOW" to "low", "MULTIPLEXING_MIDDLE" to "mid", "MULTIPLEXING_HIGH" to "high"), mieruMux) { mieruMux = it }
                            Text("Handshake (mieru v3)", fontSize = 13.sp)
                            OptionRow(listOf("" to "по умолч.", "HANDSHAKE_STANDARD" to "standard", "HANDSHAKE_NO_WAIT" to "no-wait"), mieruHs) { mieruHs = it }
                            Text("Движок: mieru 1.15 (отдельный процесс → локальный SOCKS).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (protocol == "socks4") {
                            CheckLine("SOCKS4A (имя хоста резолвит сервер)", socks4a) { socks4a = it }
                        }
                        if (protocol == "ssr") {
                            OutlinedTextField(method, { method = it }, label = { Text("Метод шифрования") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("rc4-md5, aes-128/192/256-cfb|ctr, chacha20-ietf, salsa20, none…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(ssrProto, { ssrProto = it }, label = { Text("Протокол (origin, auth_aes128_md5, auth_chain_a…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(ssrProtoParam, { ssrProtoParam = it }, label = { Text("protocol-param") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(ssrObfs, { ssrObfs = it }, label = { Text("obfs (plain, http_simple, tls1.2_ticket_auth…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(ssrObfsParam, { ssrObfsParam = it }, label = { Text("obfs-param") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "trusttunnel" || protocol == "shadowquic") {
                            OutlinedTextField(sni, { sni = it }, label = { Text("SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(alpn, { alpn = it }, label = { Text("ALPN (через запятую)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(congestion, { congestion = it }, label = { Text("Congestion control (bbr/cubic/new_reno)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (protocol == "trusttunnel") {
                            CheckLine("HTTP/3 (QUIC)", ttQuic) { ttQuic = it }
                            CheckLine("Не проверять сертификат", insecure) { insecure = it }
                        }
                        if (protocol == "shadowquic") {
                            CheckLine("UDP поверх потока (иначе datagram)", sqUdpStream) { sqUdpStream = it }
                            CheckLine("0-RTT", sqZeroRtt) { sqZeroRtt = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = formErrors.isEmpty(), onClick = {
                val name = remarks.ifBlank { Format.protocolLabel(protocol) }
                val portNum = port.toIntOrNull() ?: 0
                if (formErrors.isNotEmpty()) return@TextButton
                if (protocol == "qwdtt") {
                    onSaveQwdtt(net.antibs.client.data.ManualForms.qwdtt(remarks, qPeer, qHashes, qWorkers, qPort, password))
                    return@TextButton
                }
                if (protocol == "amneziakey") {
                    net.antibs.client.data.ManualForms.amneziaKey(akText, remarks)?.let(onSave)
                    return@TextButton
                }
                val profile = when (protocol) {
                    "amneziawg" -> net.antibs.client.data.ManualForms.amneziaWg(remarks, server, portNum, secretKey, pbk, psk, localAddress, mtu, awg.toMap())
                    "custom" -> XrayProfile(name, "custom", "", 0, customJson = json, unsupportedReason = if (json.isBlank()) "Пустой JSON" else null)
                    "chain" -> XrayProfile(name, "chain", "", 0, chainIds = chainIds,
                        unsupportedReason = if (chainIds.isEmpty()) "Выберите серверы" else null)
                    "balancer" -> XrayProfile(name, "balancer", "", 0, balancerIds = balIds, balancerStrategy = balStrategy,
                        unsupportedReason = if (balIds.isEmpty()) "Выберите серверы" else null)
                    "wireguard" -> XrayProfile(name, "wireguard", server, portNum, secretKey = secretKey, publicKey = pbk, localAddress = localAddress,
                        preSharedKey = psk.ifBlank { null }, mtu = mtu.toIntOrNull(), reserved = reserved.replace(" ", "").ifBlank { null },
                        unsupportedReason = reason)
                    "hysteria" -> XrayProfile(name, "hysteria", server, portNum, password = password, network = "hysteria",
                        security = "tls", sni = sni.ifBlank { null }, alpn = "h3", obfsPassword = obfs.ifBlank { null },
                        unsupportedReason = reason)
                    "socks" -> XrayProfile(name, "socks", server, portNum, username = username.ifBlank { null }, password = password.ifBlank { null },
                        unsupportedReason = reason)
                    "http" -> XrayProfile(name, "http", server, portNum, username = username.ifBlank { null }, password = password.ifBlank { null },
                        security = null, unsupportedReason = reason)
                    "https" -> XrayProfile(name, "https", server, portNum, username = username.ifBlank { null }, password = password.ifBlank { null },
                        security = "tls", sni = sni.ifBlank { null } ?: server, unsupportedReason = reason)
                    "http3" -> XrayProfile(name, "http3", server, portNum, username = username.ifBlank { null }, password = password.ifBlank { null },
                        network = "h3", security = "tls", sni = sni.ifBlank { null } ?: server, insecure = insecure,
                        path = path.ifBlank { null }, host = host.ifBlank { null })
                    "socks4" -> XrayProfile(name, "socks4", server, portNum, username = username.ifBlank { null }, network = "tcp",
                        method = if (socks4a) "4a" else "4")
                    "ssr" -> XrayProfile(name, "ssr", server, portNum, password = password, method = method.ifBlank { "aes-256-cfb" }, network = "tcp",
                        ssrProtocol = ssrProto.ifBlank { "origin" }, ssrProtocolParam = ssrProtoParam.ifBlank { null },
                        ssrObfs = ssrObfs.ifBlank { "plain" }, ssrObfsParam = ssrObfsParam.ifBlank { null })
                    "trusttunnel" -> XrayProfile(name, "trusttunnel", server, portNum, username = username.ifBlank { null }, password = password.ifBlank { null },
                        network = "tcp", security = "tls", sni = sni.ifBlank { null }, alpn = alpn.ifBlank { null }, insecure = insecure,
                        quic = ttQuic, congestionControl = congestion.ifBlank { null }?.takeIf { ttQuic })
                    "shadowquic" -> XrayProfile(name, "shadowquic", server, portNum, username = username.ifBlank { null }, password = password.ifBlank { null },
                        network = "udp", security = "tls", sni = sni.ifBlank { null }, alpn = alpn.ifBlank { null },
                        udpOverStream = sqUdpStream, zeroRtt = sqZeroRtt, congestionControl = congestion.ifBlank { null })
                    "mieru" -> XrayProfile(name, "mieru", server, portNum, username = username.ifBlank { null }, password = password.ifBlank { null },
                        network = "tcp", mieruTransport = mieruTransport.ifBlank { "TCP" }, mtu = mtu.toIntOrNull(),
                        mieruMultiplexing = mieruMux.ifBlank { null }, mieruHandshake = mieruHs.ifBlank { null })
                    "vless" -> XrayProfile(name, "vless", server, portNum, id = uuid, encryption = "none", flow = flow.ifBlank { null },
                        network = network.ifBlank { "tcp" }, security = security.ifBlank { null }, sni = sni.ifBlank { null },
                        fingerprint = fp.ifBlank { null }, publicKey = pbk.ifBlank { null }, shortId = sid.ifBlank { null },
                        path = path.ifBlank { null }, host = host.ifBlank { null }, unsupportedReason = reason)
                    "vmess" -> XrayProfile(name, "vmess", server, portNum, id = uuid, method = method, network = network.ifBlank { "tcp" },
                        security = security.ifBlank { null }, sni = sni.ifBlank { null }, fingerprint = fp.ifBlank { null },
                        path = path.ifBlank { null }, host = host.ifBlank { null }, unsupportedReason = reason)
                    "trojan" -> XrayProfile(name, "trojan", server, portNum, password = password, network = network.ifBlank { "tcp" },
                        security = security.ifBlank { "tls" }, sni = sni.ifBlank { null }, fingerprint = fp.ifBlank { null },
                        path = path.ifBlank { null }, host = host.ifBlank { null }, unsupportedReason = reason)
                    "shadowsocks" -> XrayProfile(name, "shadowsocks", server, portNum, method = method, password = password,
                        headerType = ssObfs.ifBlank { null }, host = host.ifBlank { null }?.takeIf { ssObfs.isNotBlank() },
                        unsupportedReason = reason)
                    "hysteria1" -> XrayProfile(name, "hysteria1", server, portNum, password = password, network = "udp",
                        security = "tls", sni = sni.ifBlank { null }, alpn = "hysteria",
                        method = if (obfs.isNotBlank()) "xplus" else null, obfsPassword = obfs.ifBlank { null })
                    "tuic" -> XrayProfile(name, "tuic", server, portNum, id = uuid.ifBlank { null }, password = password,
                        network = "udp", security = "tls", sni = sni.ifBlank { null }, alpn = "h3",
                        congestionControl = congestion.ifBlank { "bbr" }, udpRelayMode = udpRelay.ifBlank { "native" })
                    "anytls" -> XrayProfile(name, "anytls", server, portNum, password = password, network = "tcp",
                        security = "tls", sni = sni.ifBlank { null }, fingerprint = fp.ifBlank { "chrome" })
                    "ssh" -> XrayProfile(name, "ssh", server, portNum, username = username.ifBlank { "root" },
                        password = password.ifBlank { null }, network = "tcp",
                        privateKey = privateKeyText.ifBlank { null })
                    "snell" -> {
                        val v = snellVer.toIntOrNull() ?: 4
                        XrayProfile(name, "snell", server, portNum, password = password, network = "tcp",
                            headerType = method.ifBlank { null }, host = host.ifBlank { null }, protoVersion = v)
                    }
                    "naive" -> XrayProfile(name, "naive", server, portNum, username = username.ifBlank { null },
                        password = password.ifBlank { null }, network = "tcp", security = "tls", sni = sni.ifBlank { null })
                    else -> XrayProfile(name, protocol, server, portNum, password = password.ifBlank { null }, username = username.ifBlank { null },
                        unsupportedReason = reason ?: ProtocolSupport.unsupportedReason(protocol))
                }
                if (protocol !in setOf("custom", "chain", "balancer") && reason == null && (server.isBlank() || portNum <= 0)) {
                    return@TextButton
                }
                if (protocol == "custom" && json.isBlank()) return@TextButton
                if (protocol == "chain" && chainIds.isEmpty()) return@TextButton
                if (protocol == "balancer" && balIds.isEmpty()) return@TextButton
                onSave(profile)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun OptionRow(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = current == v, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@JvmName("optionRowPlain")
@Composable
private fun OptionRow(options: List<String>, current: String, onPick: (String) -> Unit) = OptionRow(options.map { it to it }, current, onPick)

@Composable
private fun CheckLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked, onChange)
        Text(text, fontSize = 14.sp)
    }
}


/**
 * 1.0.51: server picker of «Прокси цепь» / «Балансировщик». Hosts are grouped by subscription like on «Серверы»
 * (own hosts in «Свои серверы»); a section header has a chevron, the host count and its own ping icon; «Пинг всех»
 * measures every host. Results come from the same ping as the host list (shared cache), colours included.
 * Chain: a tap adds the host as the next hop (its number is shown) or removes it; balancer: multi-select.
 */
@Composable
private fun ServerPicker(
    vm: AppViewModel,
    pickable: List<ServerEntry>,
    chain: Boolean,
    picked: List<String>,
    onToggle: (String) -> Unit,
) {
    val st by vm.state.collectAsState()
    val pings by vm.pings.collectAsState()
    val pinging by vm.pinging.collectAsState()
    val groups = remember(pickable, st.subscriptions) { UiLogic.pickerGroups(pickable, st.subscriptions) }
    val grouped = groups.size > 1
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (picked.isEmpty()) "Серверов: ${pickable.size}" else "Выбрано: ${picked.size} из ${pickable.size}",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        val all = pickable.isNotEmpty() && pickable.all { it.id in pinging }
        TextButton(onClick = { vm.pingHosts(pickable) }, enabled = pickable.isNotEmpty() && !all) {
            Icon(Icons.Filled.NetworkCheck, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (pickable.any { it.id in pinging }) "Пинг…" else "Пинг всех")
        }
    }
    if (pickable.isEmpty()) {
        Text("Нет подходящих серверов.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    @Composable
    fun hostRow(s: ServerEntry) {
        val idx = picked.indexOf(s.id)
        val selected = idx >= 0
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                .clickable { onToggle(s.id) }.padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Text(
                when { chain && selected -> "${idx + 1}."; selected -> "✓"; else -> "○" },
                fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(26.dp),
            )
            Text(s.name, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            if (s.id in pinging) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else pings[s.id]?.let { ms -> Text(pingText(ms), color = pingColor(ms), fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        }
    }

    if (!grouped) {
        pickable.forEach { hostRow(it) }
        return
    }
    groups.forEach { g ->
        val open = expanded[g.id] ?: g.servers.any { it.id in picked }
        val sel = g.servers.count { it.id in picked }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .clickable { expanded[g.id] = !open }.padding(start = 4.dp, end = 0.dp),
        ) {
            Icon(
                if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (open) "Свернуть" else "Развернуть", tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(UiLogic.flag(g.title), fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(g.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(
                "  ${g.servers.size}" + if (sel > 0) " · выбрано $sel" else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            }
            SubPingIcon(pinging = g.servers.all { it.id in pinging }, onPing = { vm.pingHosts(g.servers) })
        }
        if (open) Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { g.servers.forEach { hostRow(it) } }
    }
}
