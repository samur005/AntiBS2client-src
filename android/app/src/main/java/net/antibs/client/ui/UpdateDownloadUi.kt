package net.antibs.client.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import net.antibs.client.data.DownloadStats as DS

/** 2.0.22: animated update download window (ring + shimmer bar + stats) with minimize to a floating chip. */
@Composable
internal fun UpdateDownloadWindow(s: AppViewModel.UpdateUi.Downloading, onMinimize: () -> Unit, onRestore: () -> Unit, onCancel: () -> Unit) {
    val p by animateFloatAsState(s.progress, tween(350, easing = FastOutSlowInEasing), label = "dl")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val elapsed = ((now - s.startedAt) / 1000).coerceAtLeast(0)
    val eta = DS.etaSec(s.done, s.total, s.bps)
    if (s.minimized) {
        Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -260)) { UpdateDownloadChip(s, p, eta, onRestore) }
        return
    }
    Dialog(onDismissRequest = onMinimize, properties = DialogProperties(dismissOnClickOutside = false)) { UpdateDownloadCard(s, p, elapsed, eta, onMinimize, onCancel) }
}

@Composable
internal fun UpdateDownloadChip(s: AppViewModel.UpdateUi.Downloading, p: Float, eta: Long?, onRestore: () -> Unit) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 8.dp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onRestore)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { p }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = AntiBSColors.Blue, strokeCap = StrokeCap.Round)
                        Icon(Icons.Filled.CloudDownload, null, Modifier.size(14.dp), tint = AntiBSColors.Blue)
                    }
                    Column {
                        Text("Обновление ${s.u.release.version} · ${(p * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${DS.speed(s.bps)} · осталось ${DS.mmss(eta)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
}

@Composable
internal fun UpdateDownloadCard(s: AppViewModel.UpdateUi.Downloading, p: Float, elapsed: Long, eta: Long?, onMinimize: () -> Unit, onCancel: () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Загрузка ${s.u.release.version}", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onMinimize) { Icon(Icons.Filled.KeyboardArrowDown, "Свернуть") }
                }
                Spacer(Modifier.height(8.dp))
                ProgressRing(p, s.progress >= 1f)
                Spacer(Modifier.height(14.dp))
                ShimmerBar(p)
                Spacer(Modifier.height(14.dp))
                StatGrid(listOf(
                    "Скорость" to DS.speed(s.bps), "Размер" to DS.mb(s.total),
                    "Загружено" to DS.mb(s.done), "Осталось" to DS.mb(s.total - s.done),
                    "Прошло" to DS.mmss(elapsed), "Осталось времени" to DS.mmss(eta),
                ))
                Spacer(Modifier.height(8.dp))
                Text("После загрузки — проверка SHA-256 и установка", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Отмена")
                    }
                    Button(onClick = onMinimize, modifier = Modifier.weight(1f)) { Text("Свернуть") }
                }
            }
        }
}

@Composable
private fun ProgressRing(p: Float, done: Boolean) {
    val inf = rememberInfiniteTransition(label = "ring")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "rot")
    val pulse by inf.animateFloat(0.85f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val c1 = AntiBSColors.Blue; val c2 = AntiBSColors.Purple
    Box(Modifier.size(140.dp).drawBehind {
        val w = 12.dp.toPx(); val inset = w / 2
        val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
        drawArc(Brush.sweepGradient(listOf(c1, c2, c1)), -90f, 360f * p, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        if (!done) { // orbiting glow dot
            val a = Math.toRadians((rot - 90).toDouble()); val r = (size.width - w) / 2
            drawCircle(c2.copy(alpha = 0.35f * pulse), w * 0.9f, Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat()))
        }
    }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(p * 100).toInt()}%", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp), tint = c1.copy(alpha = pulse))
        }
    }
}

@Composable
private fun ShimmerBar(p: Float) {
    val inf = rememberInfiniteTransition(label = "shimmer")
    val x by inf.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val c1 = AntiBSColors.Blue; val c2 = AntiBSColors.Purple
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(track).drawBehind {
        val fw = size.width * p
        drawRect(Brush.horizontalGradient(listOf(c1, c2), 0f, fw.coerceAtLeast(1f)), size = androidx.compose.ui.geometry.Size(fw, size.height))
        val sx = size.width * x
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), sx - 60f, sx + 60f),
            size = androidx.compose.ui.geometry.Size(fw, size.height))
    })
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 2.0.29: ABS PingProgressBar — «Проверено X из N · доступны A · недоступны B» + stop; accent gradient bar. */
@Composable
internal fun PingCounterPill(
    batch: Set<String>,
    pinging: Set<String>,
    pings: Map<String, Long>,
    onClose: () -> Unit,
    onStop: () -> Unit = {},
    stopped: Boolean = false,
    summaryMs: Long = 2500,
) {
    val (total, done, ok, failed) = DS.pingCounts4(batch, pinging, pings)
    val running = total > 0 && done < total
    LaunchedEffect(batch, running) { if (batch.isNotEmpty() && !running) { delay(summaryMs); onClose() } }
    val cs = MaterialTheme.colorScheme
    val frac by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, label = "pingFrac")
    androidx.compose.animation.AnimatedVisibility(
        visible = batch.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Line 1: status + stop; line 2: available / unavailable — never one cramped row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        running -> "Проверено $done из $total"
                        stopped -> "Проверка остановлена"
                        else -> "Проверено $total"
                    },
                    color = cs.onSurface, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    IconButton(onClick = onStop, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = "Остановить проверку", tint = cs.error, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("доступны $ok", color = cs.primary, fontSize = 13.sp, maxLines = 1, softWrap = false)
                Text("недоступны $failed", color = cs.error, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            // accent → lighter accent gradient, ~7 dp, rounded ends
            val accent = cs.primary
            val light = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.45f)
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)).background(cs.onSurface.copy(alpha = 0.12f))) {
                Box(
                    Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(accent, light))),
                )
            }
        }
    }
}

