package net.antibs.client.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.SessionLook
import net.antibs.client.engine.SessionLooks
import net.antibs.client.engine.SessionSpeed
import net.antibs.client.engine.SessionSpeedPhase
import net.antibs.client.engine.SessionSpeedProgress
import net.antibs.client.engine.SpeedNet
import net.antibs.client.engine.SpeedTest
import net.antibs.client.engine.VpnState

/** Optional overrides used by screenshot tests. */
internal object SessionShotHooks {
    var look: SessionLook? = null
    var speed: SessionSpeedProgress? = null
    var busy: Boolean = false
}

/** 2.0.41: Session tab — public IP / geo through the tunnel, plus a Cloudflare speed test. */
@Composable
internal fun SessionPage(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by vm.state.collectAsState()
    val vpn by VpnState.status.collectAsState()
    val connected = vpn.state == ConnState.CONNECTED
    val socks = st.adv.socksPort
    val style = LocalAppStyle.current
    val c = ssColors(style)
    val shape = ssShape(style, 16.dp)

    val shotLook = SessionShotHooks.look
    val shotSpeed = SessionShotHooks.speed
    val shotLocked = shotLook != null || shotSpeed != null

    var look by remember { mutableStateOf(shotLook) }
    var loading by remember { mutableStateOf(if (shotLocked) SessionShotHooks.busy else true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(shotSpeed ?: SessionSpeedProgress()) }
    val scope = rememberCoroutineScope()
    var speedJob by remember { mutableStateOf<Job?>(null) }
    val running = speed.phase == SessionSpeedPhase.Ping ||
        speed.phase == SessionSpeedPhase.Download ||
        speed.phase == SessionSpeedPhase.Upload

    LaunchedEffect(refreshTick) {
        if (shotLook != null) {
            look = shotLook
            loading = SessionShotHooks.busy
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            look = SessionLooks.fetch(ctx, connected, socks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            look = null
            error = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось получить сведения о сессии"
        } finally {
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { speedJob?.cancel() }
    }

    fun toggleSpeed() {
        if (shotLocked) return
        if (speedJob?.isActive == true) {
            speedJob?.cancel()
            return
        }
        val job = scope.launch {
            val client = if (connected) SpeedTest.socks(socks)
            else runCatching { SpeedNet.direct(ctx).client }.getOrElse { SpeedTest.direct() }
            try {
                SessionSpeed.run(client, onUpdate = { speed = it }, cancelled = { !isActive })
            } catch (_: CancellationException) {
                speed = speed.copy(phase = SessionSpeedPhase.Idle, error = null)
            }
        }
        speedJob = job
        job.invokeOnCompletion { speedJob = null }
    }

    SettingsScaffold("Сессия", onBack, actions = {
        IconButton(
            onClick = { if (!loading && !shotLocked) refreshTick++ },
            enabled = !loading,
        ) { Icon(Icons.Filled.Refresh, "Обновить", tint = MaterialTheme.colorScheme.onBackground) }
    }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = c.accent,
                    trackColor = c.accent.copy(alpha = 0.18f),
                )
                Spacer(Modifier.height(8.dp))
            }

            Surface(
                shape = shape,
                color = c.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .border(1.dp, c.cardBorder, shape),
            ) {
                val rows = listOf(
                    "IPv4" to (look?.ipv4),
                    "IPv6" to (look?.ipv6),
                    "ASN" to (look?.asn),
                    "Провайдер" to (look?.provider),
                    "Страна" to (look?.country),
                    "Город" to (look?.city),
                    "Регион" to (look?.region),
                    "Широта" to (look?.latitude),
                    "Долгота" to (look?.longitude),
                )
                Column {
                    rows.forEachIndexed { i, (label, value) ->
                        val providerUrl = if (label == "Провайдер") SessionLooks.providerUrl(look?.domain, look?.asn, look?.provider) else null
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (providerUrl != null) Modifier.clickable(onClickLabel = "Открыть сайт провайдера") {
                                        Desk.openUrl(providerUrl)
                                    } else Modifier
                                )
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, color = c.muted, fontSize = 13.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                value?.takeIf { it.isNotBlank() } ?: "—",
                                color = if (providerUrl != null) c.accent else c.onCard,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.End,
                                textDecoration = if (providerUrl != null) TextDecoration.Underline else TextDecoration.None,
                                modifier = Modifier.weight(1f),
                            )
                            if (providerUrl != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.OpenInNew, "Открыть сайт провайдера", tint = c.accent, modifier = Modifier.size(14.dp))
                            }
                        }
                        if (i < rows.lastIndex) {
                            HorizontalDivider(thickness = 0.6.dp, color = c.cardBorder)
                        }
                    }
                }
            }

            val caption = if (!connected)
                "VPN не подключён: сведения получены напрямую, не через туннель. Запрос выполняется только при открытии этого экрана или по кнопке обновления — в фоне данные не собираются."
            else
                "Сведения получены через текущий VPN-туннель. Запрос выполняется только при открытии этого экрана или по кнопке обновления — в фоне данные не собираются."
            Text(
                caption,
                color = c.muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
            )
            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                )
            }

            SectionLabel("ТЕСТ СКОРОСТИ")
            Surface(
                shape = shape,
                color = c.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .border(1.dp, c.cardBorder, shape),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val phaseFill = when (speed.phase) {
                        SessionSpeedPhase.Download -> speed.downProgress ?: 0f
                        SessionSpeedPhase.Upload -> speed.upProgress ?: 0f
                        SessionSpeedPhase.Done -> 1f
                        else -> null
                    }
                    if (running) {
                        if (speed.phase == SessionSpeedPhase.Ping) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                                color = c.accent,
                                trackColor = c.accent.copy(alpha = 0.18f),
                            )
                        } else {
                            val f = phaseFill ?: 0f
                            LinearProgressIndicator(
                                progress = { f },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                                color = c.accent,
                                trackColor = c.accent.copy(alpha = 0.18f),
                            )
                        }
                    }
                    val pingFill = when {
                        speed.phase == SessionSpeedPhase.Ping -> null
                        speed.pingMs != null -> 1f
                        else -> 0f
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SpeedCell("Пинг", SessionSpeed.formatPing(speed.pingMs), "мс", c, fill = pingFill, indeterminate = speed.phase == SessionSpeedPhase.Ping)
                        SpeedCell("Загрузка", SessionSpeed.dashOrMbps(speed.downMbps), "Мбит/с", c, fill = speed.downProgress ?: 0f)
                        SpeedCell("Отдача", SessionSpeed.dashOrMbps(speed.upMbps), "Мбит/с", c, fill = speed.upProgress ?: 0f)
                    }
                    Spacer(Modifier.height(16.dp))
                    val onBtn = if (c.accent.luminance() > 0.45f) Color(0xFF0F172A) else Color.White
                    Button(
                        onClick = { toggleSpeed() },
                        enabled = true,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = shape,
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = onBtn),
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = onBtn,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Стоп", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        } else {
                            Text("Тест скорости", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                    val err = speed.error
                    if (speed.phase == SessionSpeedPhase.Failed && !err.isNullOrBlank()) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                        )
                    }
                    if (!connected && !running) {
                        Text(
                            "VPN не подключён: тест пойдёт напрямую, минуя туннель.",
                            color = c.muted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedCell(title: String, value: String, unit: String, c: DeskSsColors, fill: Float? = 0f, indeterminate: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
        Text(title, color = c.muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = c.onCard, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(unit, color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        val barMod = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
        if (indeterminate) {
            LinearProgressIndicator(modifier = barMod, color = c.accent, trackColor = c.accent.copy(alpha = 0.18f))
        } else {
            val f = fill?.coerceIn(0f, 1f) ?: 0f
            LinearProgressIndicator(progress = { f }, modifier = barMod, color = c.accent, trackColor = c.accent.copy(alpha = 0.18f))
        }
    }
}

