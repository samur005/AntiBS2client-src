package net.antibs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import net.antibs.client.desktop.FlagText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.data.Olcrtc
import net.antibs.client.data.XrayProfile
import java.security.SecureRandom

/** Transport parameters shown per transport (URI payload keys). */
private val OLC_TRANSPORT_PARAMS = mapOf(
    "vp8channel" to listOf("vp8-fps" to "FPS", "vp8-batch" to "Кадров за тик"),
    "seichannel" to listOf("fps" to "FPS", "batch" to "Кадров за тик", "frag" to "Фрагмент, байт", "ack-ms" to "ACK, мс"),
    "videochannel" to listOf("video-w" to "Ширина", "video-h" to "Высота", "video-fps" to "FPS", "video-codec" to "Кодек (qrcode / tile)",
        "video-qr-size" to "QR-фрагмент, байт", "video-qr-recovery" to "Коррекция (low…highest)", "video-tile-module" to "Tile, px", "video-tile-rs" to "Reed-Solomon, %"),
)

/** 1.0.41: «Создать вручную» → olcRTC (link or form). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OlcrtcFormDialog(initial: XrayProfile? = null, onDismiss: () -> Unit, onSave: (XrayProfile) -> Unit) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initial?.remarks.orEmpty()) }
    var provider by remember { mutableStateOf(initial?.method ?: "telemost") }
    var transport by remember { mutableStateOf(initial?.network ?: "datachannel") }
    var room by remember { mutableStateOf(initial?.server.orEmpty()) }
    var key by remember { mutableStateOf(initial?.password.orEmpty()) }
    var token by remember { mutableStateOf(initial?.username.orEmpty()) }
    val params = remember { mutableStateMapOf<String, String>().apply { initial?.olc?.let { putAll(it) } } }
    val fromLink = remember(link) { if (Olcrtc.isLink(link)) runCatching { Olcrtc.parse(link) } else null }

    fun build(): XrayProfile = XrayProfile(
        remarks = name.ifBlank { "olcRTC · ${Olcrtc.PROVIDER_LABELS[provider] ?: provider}" }, protocol = Olcrtc.PROTOCOL,
        server = room.trim(), port = 0, method = provider, network = transport, password = key.trim().lowercase(),
        username = token.trim().ifEmpty { null },
        olc = params.filter { (k, v) -> v.isNotBlank() && OLC_TRANSPORT_PARAMS[transport].orEmpty().any { it.first == k } }.takeIf { it.isNotEmpty() },
    )
    val error = if (fromLink != null) fromLink.exceptionOrNull()?.message else Olcrtc.validate(build())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("olcRTC") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TCP поверх WebRTC: трафик выглядит как видеозвонок в Яндекс Телемосте, Jitsi или WB Stream. Нужен свой olcRTC‑сервер (mode: srv) в той же комнате и общий ключ.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(link, { link = it.trim() }, label = { Text("Ссылка olcrtc://… (необязательно)") }, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth())
                fromLink?.getOrNull()?.let { p ->
                    Text("Распознано: ${Olcrtc.PROVIDER_LABELS[p.method] ?: p.method} · ${p.network} · ${p.server.take(40)}", fontSize = 12.sp, color = AntiBSColors.Ok)
                }
                if (fromLink == null) {
                    OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Провайдер", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Olcrtc.PROVIDERS.forEach { p -> FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(Olcrtc.PROVIDER_LABELS[p] ?: p) }) }
                    }
                    Text("Транспорт", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Olcrtc.TRANSPORTS.forEach { t -> FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(t) }) }
                    }
                    Text(when {
                        provider == "jitsi" && transport == "datachannel" -> "Рекомендуемая связка: стабильно, без регистрации (нужен Jitsi‑сервер без обязательного токена)."
                        provider == "wbstream" && transport == "datachannel" -> "WB Stream + datachannel работает только с токеном модератора; для гостя выберите vp8channel."
                        provider == "telemost" && transport == "vp8channel" -> "Телемост + vp8channel по документации olcRTC не работает — выберите datachannel или videochannel."
                        transport == "videochannel" -> "videochannel — экспериментальный, медленный."
                        else -> ""
                    }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(room, { room = it }, label = { Text("Room ID / ссылка на комнату") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it.trim() }, label = { Text("Ключ (64 hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Тот же, что crypto.key на сервере") })
                    TextButton(onClick = { val b = ByteArray(32); SecureRandom().nextBytes(b); key = b.joinToString("") { "%02x".format(it) } }) { Text("Сгенерировать ключ") }
                    OutlinedTextField(token, { token = it }, label = { Text("Токен провайдера (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OLC_TRANSPORT_PARAMS[transport]?.let { list ->
                        Text("Параметры $transport (пусто = по умолчанию)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        list.forEach { (k, label) ->
                            OutlinedTextField(params[k].orEmpty(), { params[k] = it.trim() }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (error != null && (link.isNotBlank() || room.isNotBlank() || key.isNotBlank())) Text(error, color = AntiBSColors.Err, fontSize = 12.sp)
                Text("Пинг: временный клиент заходит в комнату и делает HTTP‑запрос через туннель (дольше обычного).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { onSave(fromLink?.getOrNull() ?: build()) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** 1.0.41: «Создать вручную» → OpenVPN (.ovpn file / clipboard / text / form) and OpenConnect (form or openconnect:// link). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VpnProtoFormDialog(proto: String, vm: AppViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    // OpenVPN
    var ovpn by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(if (proto == "openvpn") "1194" else "443") }
    var transport by remember { mutableStateOf("udp") }
    var cipher by remember { mutableStateOf("AES-256-GCM") }
    var auth by remember { mutableStateOf("SHA256") }
    var ca by remember { mutableStateOf("") }
    var cert by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var tlsCrypt by remember { mutableStateOf("") }
    var tlsAuth by remember { mutableStateOf("") }
    var keyDir by remember { mutableStateOf("1") }
    var userPass by remember { mutableStateOf(false) }
    // OpenConnect
    var ocProto by remember { mutableStateOf("anyconnect") }
    var group by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf("") }
    var ua by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }

    fun pickOvpn() {
        val f = Desk.openFile("Файл .ovpn") ?: return
        runCatching { f.readBytes().take(512 * 1024).toByteArray().toString(Charsets.UTF_8) }.getOrNull()?.let { t ->
            ovpn = t
            if (name.isBlank()) name = f.name.substringBeforeLast('.')
        }
    }
    val form = net.antibs.client.data.OpenVpnConfig.Form(server, port.toIntOrNull() ?: 0, transport, cipher, auth, ca, cert, key, tlsCrypt, tlsAuth, keyDir, userPass)
    val summary = remember(ovpn) { if (ovpn.isBlank()) null else net.antibs.client.data.OpenVpnConfig.summary(ovpn) }
    val ocLink = remember(link) { if (net.antibs.client.data.OpenConnectConfig.isLink(link)) runCatching { net.antibs.client.data.OpenConnectConfig.parse(link) } else null }
    val errors: List<String> = when {
        proto == "openvpn" && manual -> net.antibs.client.data.OpenVpnConfig.formErrors(form) + (if (userPass && user.isBlank()) listOf("логин") else emptyList())
        proto == "openvpn" -> summary?.errors.orEmpty() + (if (summary?.needsAuth == true && user.isBlank()) listOf("сервер требует логин/пароль (auth-user-pass)") else emptyList()) +
            (if (summary == null) listOf("вставьте или выберите .ovpn") else emptyList())
        ocLink != null -> listOfNotNull(ocLink.exceptionOrNull()?.message)
        else -> if (server.isBlank()) listOf("сервер") else emptyList()
    }

    fun save() {
        val p = when {
            proto == "openvpn" && manual -> net.antibs.client.data.OpenVpnConfig.profile(net.antibs.client.data.OpenVpnConfig.build(form), name, user, pass, keyPass)
            proto == "openvpn" -> net.antibs.client.data.OpenVpnConfig.profile(ovpn, name, user, pass, keyPass)
            ocLink?.getOrNull() != null -> ocLink.getOrNull()!!.let { l -> if (pass.isNotBlank()) l.copy(password = pass) else l }
            else -> net.antibs.client.data.OpenConnectConfig.profile(server, ocProto, user, pass, group, pin, answers, ua, name)
        }
        vm.addManualProfile(p)
        onDone()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (proto == "openvpn") "OpenVPN" else "OpenConnect") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (proto == "openvpn") "OpenVPN 2.6 (OpenSSL 3): tls-crypt / tls-auth, сертификаты, логин/пароль. Трафик идёт через Xray, поэтому маршрутизация, блокировка рекламы и «Приложения» работают как обычно."
                    else "OpenConnect 9.12: Cisco AnyConnect / ocserv, GlobalProtect, Pulse, Juniper, F5, Fortinet, Array. Трафик идёт через Xray (маршрутизация и блокировка рекламы работают).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (proto == "openvpn") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !manual, onClick = { manual = false }, label = { Text("Файл .ovpn") })
                        FilterChip(selected = manual, onClick = { manual = true }, label = { Text("Вручную") })
                    }
                    if (!manual) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { pickOvpn() }) { Text("Выбрать файл") }
                            TextButton(onClick = { clip.getText()?.text?.let { ovpn = it } }) { Text("Из буфера") }
                        }
                        OutlinedTextField(ovpn, { ovpn = it }, label = { Text("Содержимое .ovpn") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                        summary?.takeIf { it.remote.isNotBlank() }?.let { s ->
                            Text("Сервер: ${s.remote}:${s.port}/${s.proto}" + (if (s.needsAuth) " · нужен логин/пароль" else "") + (if (s.keyEncrypted) " · ключ с паролем" else ""), fontSize = 12.sp, color = AntiBSColors.Ok)
                        }
                    } else {
                        OutlinedTextField(server, { server = it.trim() }, label = { Text("Сервер") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Порт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("udp", "tcp").forEach { t -> FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(t.uppercase()) }) }
                        }
                        OutlinedTextField(cipher, { cipher = it.trim() }, label = { Text("Шифр (cipher)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(auth, { auth = it.trim() }, label = { Text("auth (HMAC)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(ca, { ca = it }, label = { Text("CA (PEM)") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(cert, { cert = it }, label = { Text("Сертификат клиента (PEM, необязательно)") }, minLines = 1, maxLines = 5, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(key, { key = it }, label = { Text("Ключ клиента (PEM, необязательно)") }, minLines = 1, maxLines = 5, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(tlsCrypt, { tlsCrypt = it }, label = { Text("tls-crypt ключ (необязательно)") }, minLines = 1, maxLines = 5, modifier = Modifier.fillMaxWidth())
                        if (tlsCrypt.isBlank()) {
                            OutlinedTextField(tlsAuth, { tlsAuth = it }, label = { Text("tls-auth ключ (необязательно)") }, minLines = 1, maxLines = 5, modifier = Modifier.fillMaxWidth())
                            if (tlsAuth.isNotBlank()) OutlinedTextField(keyDir, { keyDir = it.filter(Char::isDigit).take(1) }, label = { Text("key-direction") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        FlowRow { FilterChip(selected = userPass, onClick = { userPass = !userPass }, label = { Text("Логин и пароль (auth-user-pass)") }) }
                    }
                    if (manual && userPass || !manual && summary?.needsAuth == true) {
                        OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    }
                    if (!manual && summary?.keyEncrypted == true) OutlinedTextField(keyPass, { keyPass = it }, label = { Text("Пароль ключа") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                } else {
                    OutlinedTextField(link, { link = it.trim() }, label = { Text("Ссылка openconnect://… (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (ocLink == null) {
                        OutlinedTextField(server, { server = it.trim() }, label = { Text("Сервер (vpn.example.com[:порт][/группа])") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            net.antibs.client.data.OpenConnectConfig.PROTOCOLS.forEach { (k, v) -> FilterChip(selected = ocProto == k, onClick = { ocProto = k }, label = { Text(v.substringBefore(" /")) }) }
                        }
                        OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    if (ocLink == null) {
                        OutlinedTextField(group, { group = it }, label = { Text("Группа (authgroup, необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pin, { pin = it.trim() }, label = { Text("Пин сертификата (pin-sha256:…, необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Нужен для самоподписанного сертификата ocserv") })
                        OutlinedTextField(answers, { answers = it }, label = { Text("Ответы на доп. запросы (OTP и т.п.), по строке") }, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(ua, { ua = it }, label = { Text("User-Agent (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (errors.isNotEmpty() && (ovpn.isNotBlank() || server.isNotBlank() || link.isNotBlank())) Text("Проверьте: " + errors.joinToString(", "), color = AntiBSColors.Err, fontSize = 12.sp)
                Text("Пароли хранятся зашифрованными (Android Keystore).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = errors.isEmpty(), onClick = { save() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
