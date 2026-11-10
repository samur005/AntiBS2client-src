package net.antibs.client.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.engine.NetMonitor
import kotlinx.coroutines.launch

/** 2.0.7 (home variant B): «Мониторинг сети» + «URL Тест» cards between the power button and the world strip. */

@Composable
/** 2.0.12: shared ping colour (banner + URL test), see [net.antibs.client.data.PingLevel]. */
internal fun pingLevelColor(ms: Long?, neutral: Color): Color =
    net.antibs.client.data.PingLevel.argb(net.antibs.client.data.PingLevel.of(ms))?.let { Color(it.toInt()) } ?: neutral

internal fun toneColor(t: NetMonitor.Tone, muted: Color): Color = when (t) {
    NetMonitor.Tone.GOOD -> Color(0xFF22C55E)
    NetMonitor.Tone.MID -> Color(0xFFF59E0B)
    NetMonitor.Tone.BAD -> Color(0xFFEF4444)
    NetMonitor.Tone.NONE -> muted
}

@Composable
private fun MonCard(title: String, badge: (@Composable () -> Unit)? = null, action: (@Composable () -> Unit)? = null, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val style = LocalAppStyle.current
    val c = ssColors(style)
    val shape = ssShape(style, 16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.cardBorder, shape)
            .padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(if (compact) 20.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.onCard, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 15.sp)
            if (badge != null) { Spacer(Modifier.width(8.dp)); badge() }
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

@Composable
private fun LiveBadge() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "live-a")
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x33EF4444)).padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFEF4444).copy(alpha = a)))
        Spacer(Modifier.width(3.dp))
        Text("LIVE", color = Color(0xFFEF4444), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonTile(letter: String, tint: Color, title: String, host: String, ms: Long?, c: SsColors, modifier: Modifier, onTap: () -> Unit) {
    val style = LocalAppStyle.current
    val shape = ssShape(style, 12.dp)
    Row(modifier.clip(shape).background(c.chipOff).clickable(onClick = onTap).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text(letter, color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.muted, fontSize = 12.sp, maxLines = 1)
            Text(host, color = c.onCard, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(NetMonitor.label(ms, "—"), color = pingLevelColor(ms, c.muted), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("ms", color = c.muted, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun UrlTestCard(results: Map<String, Long>, busy: Boolean, onRefresh: () -> Unit) {
    val style = LocalAppStyle.current
    val c = ssColors(style)
    val spin = rememberInfiniteTransition(label = "spin")
    val ang by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin-a")
    // 2.0.32: compact (lower) card — smaller paddings, tiles and icons
    MonCard("URL Тест", compact = true, action = {
        Box(Modifier.size(24.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onRefresh), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Refresh, "Обновить", tint = c.accent, modifier = Modifier.size(18.dp).rotate(if (busy) ang else 0f))
        }
    }) {
        NetMonitor.URL_TARGETS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t -> UrlTile(t, results[t.id], busy && t.id !in results, c, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun UrlTile(t: NetMonitor.Target, ms: Long?, pending: Boolean, c: SsColors, modifier: Modifier) {
    val style = LocalAppStyle.current
    val shape = ssShape(style, 12.dp)
    Row(modifier.clip(shape).background(c.chipOff).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        SiteIcon(t.id)
        Spacer(Modifier.width(6.dp))
        Text(t.title, color = c.onCard, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val col = if (pending) c.muted else pingLevelColor(ms, c.muted)
        if (!pending && ms != null) { Box(Modifier.size(5.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(3.dp)) }
        Text(if (pending) "…" else when { ms == null -> "—"; ms < 0 -> "тайм-аут"; else -> "$ms ms" },
            color = col, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SiteIcon(id: String) {
    val m = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
    when (id) {
        "google" -> Box(m.background(Color.White), contentAlignment = Alignment.Center) {
            Text("G", fontWeight = FontWeight.Bold, fontSize = 14.sp, style = androidx.compose.ui.text.TextStyle(brush = Brush.linearGradient(
                listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853)))))
        }
        "telegram" -> Box(m.clip(CircleShape).background(Color(0xFF2AABEE)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        "instagram" -> Box(m.background(Brush.linearGradient(listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CameraAlt, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        else -> Box(m.background(Color(0xFFFF0000)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Stateful section: live monitor every [NetMonitor.MONITOR_PERIOD_MS] while this composable is on screen AND the
 * activity is resumed; URL test on open / (re)connect / refresh tap.
 */
@Composable
internal fun HomeMonitorSection(connected: Boolean, socksPort: Int, showUrl: Boolean, haptics: Boolean) {
    if (!showUrl) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifeState by life.currentStateFlow.collectAsState()
    val resumed = lifeState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    var urls by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var urlBusy by remember { mutableStateOf(false) }
    var urlRun by remember { mutableIntStateOf(0) }
    if (showUrl) LaunchedEffect(connected, socksPort, urlRun) {
        urlBusy = true; urls = emptyMap()
        val m = mutableMapOf<String, Long>()
        kotlinx.coroutines.coroutineScope {
            val client = if (connected) NetMonitor.vpnClient(socksPort) else null
            NetMonitor.URL_TARGETS.forEach { t ->
                this.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ms = NetMonitor.probe(client ?: NetMonitor.directClient(ctx), t.url)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { m[t.id] = ms; urls = m.toMap() }
                }
            }
        }
        urlBusy = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showUrl) UrlTestCard(urls, urlBusy) { AppHaptics.perform(view, AppHaptics.Kind.TICK, haptics); urlRun++ }
    }
}
