package net.antibs.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the styled Servers screen; both styles have a dark and a light variant. */
data class SsColors(
    val dark: Boolean,
    val card: Color,
    val cardBorder: Color,
    val accent: Color,
    val selectedFill: Color,
    val onCard: Color,
    val muted: Color,
    val chipOff: Color,
)

object SsPalette {
    val NeonCyan = Color(0xFF35E0FF)
    val NeonCyanLight = Color(0xFF0891B2)
    val NeonHues = listOf(Color(0xFFFF4FD8), Color(0xFFFFA94D), Color(0xFFA78BFA), Color(0xFF34D399), Color(0xFF60A5FA), Color(0xFFF472B6))
    val PingGood = Color(0xFF34D399)
    val PingMid = Color(0xFFFBBF24)
    val PingBad = Color(0xFFF87171)
    val PingNone = Color(0xFF5B6478)

    /** A stable neon hue per subscription. */
    fun hue(id: String): Color = NeonHues[(id.hashCode() and 0x7fffffff) % NeonHues.size]

    fun ping(p: Long?, dark: Boolean): Color {
        val ms = ServerStyleLogic.pingMs(p) ?: return PingNone
        val c = when { ms < 70 -> PingGood; ms < 150 -> PingMid; else -> PingBad }
        // darker shades for the light theme so the digits stay readable on white
        return if (dark) c else when (c) { PingGood -> Color(0xFF059669); PingMid -> Color(0xFFB45309); else -> Color(0xFFDC2626) }
    }
}

@Composable
fun ssColors(style: ServerStyle): SsColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cs = MaterialTheme.colorScheme
    return when (style) {
        ServerStyle.NEON -> if (dark) SsColors(true, Color(0xFF111727), Color(0xFF1D2438), SsPalette.NeonCyan, Color(0xFF0D1A24),
            Color(0xFFE6EAF2), Color(0xFF7D879C), Color(0xFF0D1220))
        else SsColors(false, Color.White, Color(0xFFDCE3F0), SsPalette.NeonCyanLight, Color(0xFFE6FAFF),
            Color(0xFF0F172A), Color(0xFF64748B), Color(0xFFF1F5F9))
        ServerStyle.AURORA -> if (dark) SsColors(true, Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.12f), Color(0xFF5EEAD4),
            Color.White.copy(alpha = 0.16f), Color(0xFFF1F5F9), Color(0xFFB4BFD6), Color.White.copy(alpha = 0.08f))
        else SsColors(false, Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.9f), Color(0xFF0D9488),
            Color.White.copy(alpha = 0.92f), Color(0xFF0F172A), Color(0xFF475569), Color.White.copy(alpha = 0.55f))
        ServerStyle.CYBER -> if (dark) SsColors(true, Color(0xFF140A22), Color(0xFF3B1E54), Color(0xFF00F0FF), Color(0xFF0A1E2A),
            Color(0xFFF5E9FF), Color(0xFF9D83B8), Color(0xFF1A0D2B))
        else SsColors(false, Color.White, Color(0xFFF0C8F5), Color(0xFFC026D3), Color(0xFFFDF0FF),
            Color(0xFF2A0A3A), Color(0xFF7E5A90), Color(0xFFFAF0FC))
        ServerStyle.MATERIAL -> if (dark) SsColors(true, Color(0xFF2A2521), Color(0xFF3D352E), Color(0xFFFFB77C), Color(0xFF5A3A12),
            Color(0xFFF3E6DA), Color(0xFFBFAE9E), Color(0xFF332C26))
        else SsColors(false, Color.White, Color(0xFFF0E2D3), Color(0xFF8B5A00), Color(0xFFFFDDB8),
            Color(0xFF231A12), Color(0xFF6E5C4B), Color(0xFFF6EBDF))
        else -> if (dark) SsColors(true, Color(0xFF171E31), Color(0xFF26304A), Color(0xFF6CA0FF), cs.primaryContainer,
            Color(0xFFE6EAF2), Color(0xFF8B95AB), Color(0xFF171E31))
        else SsColors(false, Color.White, Color(0xFFE2E8F0), Color(0xFF2557D6), Color(0xFFE3ECFF),
            Color(0xFF0F172A), Color(0xFF64748B), Color(0xFFF1F5F9))
    }
}

/** Row / chip shape: cut corners for the cyberpunk look. */
fun ssShape(style: ServerStyle, r: Dp = 14.dp): androidx.compose.ui.graphics.Shape =
    if (style == ServerStyle.CYBER) androidx.compose.foundation.shape.CutCornerShape(topStart = r * 0.7f, bottomEnd = r * 0.7f)
    else RoundedCornerShape(if (style == ServerStyle.MATERIAL) r + 4.dp else r)

/** Screen background behind the styled list (null = theme background). */
@Composable
fun ssBackground(style: ServerStyle): Brush? {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (style) {
        ServerStyle.AURORA -> if (dark) Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0F3B4C), Color(0xFF3B0D3A), Color(0xFF0B0F1C)))
            else Brush.linearGradient(listOf(Color(0xFFE0E7FF), Color(0xFFCCFBF1), Color(0xFFFCE7F3)))
        ServerStyle.CYBER -> if (dark) Brush.verticalGradient(listOf(Color(0xFF0B0614), Color(0xFF12061F))) else Brush.verticalGradient(listOf(Color(0xFFFFF7FE), Color(0xFFF5F0FF)))
        ServerStyle.MATERIAL -> if (dark) Brush.verticalGradient(listOf(Color(0xFF1E1A17), Color(0xFF1E1A17))) else Brush.verticalGradient(listOf(Color(0xFFFFF8F2), Color(0xFFFFF8F2)))
        ServerStyle.NEON -> if (dark) Brush.verticalGradient(listOf(Color(0xFF070A12), Color(0xFF070A12))) else null
        else -> null
    }
}

/** Soft coloured glow around a rounded rect (Android shadow layer). */
fun Modifier.neonGlow(color: Color, enabled: Boolean, corner: Dp = 14.dp, radius: Dp = 12.dp, alpha: Float = 0.55f): Modifier =
    if (!enabled) this else drawBehind {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(radius.toPx(), 0f, 0f, color.copy(alpha = alpha).toArgb())
            }
            val r = corner.toPx()
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }

@Composable
fun SignalBars(bars: Int, color: Color, off: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 4) {
            Box(Modifier.width(3.5.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i < bars) color else off))
        }
    }
}

@Composable
fun FavoriteStar(on: Boolean, onToggle: () -> Unit, idle: Color) {
    Icon(
        if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder,
        contentDescription = null,
        tint = if (on) Color(0xFFFFA94D) else idle.copy(alpha = 0.6f),
        modifier = Modifier.clip(CircleShape).clickable { onToggle() }.padding(5.dp).size(19.dp)
    )
}

/** «Все / Быстрые / Избранное» chips. */
@Composable
fun ServerFilterChips(style: ServerStyle, current: ServerFilter, labels: Map<ServerFilter, String>, onPick: (ServerFilter) -> Unit) {
    val c = ssColors(style)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ServerFilter.entries.forEach { f ->
            val on = f == current
            val shape = if (style == ServerStyle.CYBER) ssShape(style, 10.dp) else RoundedCornerShape(20.dp)
            val m = when {
                on && style.glow -> Modifier.neonGlow(c.accent, c.dark, 20.dp, 8.dp, 0.4f).clip(shape)
                    .background(c.accent.copy(alpha = 0.14f)).border(1.dp, c.accent, shape)
                on -> Modifier.clip(shape).background(c.accent)
                else -> Modifier.clip(shape).background(c.chipOff).border(1.dp, c.cardBorder, shape)
            }
            Text(
                labels[f] ?: f.key,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = when { on && style.glow -> c.accent; on -> Color.White; else -> c.muted },
                maxLines = 1,
                modifier = m.clickable { onPick(f) }.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

/** One chip of the horizontal subscription ribbon. */
data class RibbonItem(val id: String, val title: String, val subtitle: String, val warn: Boolean = false,
    val traffic: String = "", val remaining: Float? = null, val used: Long = 0, val total: Long = 0, val servers: Int = 0)

@Composable
fun SubscriptionRibbon(style: ServerStyle, items: List<RibbonItem>, selected: String, onPick: (String) -> Unit, onReorder: ((List<String>) -> Unit)? = null) {
    val c = ssColors(style)
    if (style == ServerStyle.AURORA) { AuroraTiles(items, selected, onPick, onReorder); return }
    val movable = items.map { it.id }.filter { it.isNotEmpty() && it != ServerStyleLogic.MANUAL_ID }
    val rs = androidx.compose.runtime.remember(movable) { HReorderState(movable) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val spPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    val byId = items.associateBy { it.id }
    val shown = items.filter { it.id !in movable }.filter { it.id.isEmpty() } + rs.order.mapNotNull { byId[it] } + items.filter { it.id !in movable && it.id.isNotEmpty() }
    fun Modifier.drag(id: String) = if (onReorder == null) this else hReorderItem(rs, id, spPx, haptic) { onReorder(it) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { it ->
            val on = it.id == selected
            val shape = ssShape(style, 14.dp)
            val hue = if (style.glow && it.id.isNotEmpty()) SsPalette.hue(it.id) else c.accent
            val m = when {
                on && style.glow -> Modifier.neonGlow(hue, c.dark, 14.dp, 10.dp, 0.45f).clip(shape)
                    .background(c.card).border(1.dp, hue, shape)
                on -> Modifier.clip(shape).background(Brush.linearGradient(listOf(c.accent, c.accent.copy(alpha = 0.8f)))).border(1.dp, c.accent, shape)
                else -> Modifier.clip(shape).background(c.card).border(1.dp, c.cardBorder, shape)
            }
            Column(Modifier.drag(it.id).then(m).clickable { onPick(it.id) }.padding(horizontal = 14.dp, vertical = 8.dp).widthIn(max = 180.dp)) {
                Text(it.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when { on && style == ServerStyle.CARDS -> Color.White; style.glow && on -> hue; else -> c.onCard })
                if (it.subtitle.isNotEmpty()) Text(it.subtitle, fontSize = 10.sp, maxLines = 1,
                    color = when { it.warn -> SsPalette.PingBad; on && style == ServerStyle.CARDS -> Color.White.copy(alpha = 0.85f); else -> c.muted })
            }
        }
    }
}

/** «Лучший сервер» hero card. */
@Composable
fun BestServerCard(
    style: ServerStyle,
    title: String,
    name: String,
    detail: String,
    pingMs: Int?,
    pingLabel: String,
    actionLabel: String,
    emptyLabel: String,
    onAction: () -> Unit,
    onRetest: () -> Unit,
) {
    val c = ssColors(style)
    val shape = RoundedCornerShape(22.dp)
    val neon = style.glow
    val base = if (neon) Modifier.neonGlow(c.accent, true, 22.dp, 14.dp, if (c.dark) 0.45f else 0.25f).clip(shape).background(if (c.dark) Color(0xFF0D1A24) else Color.White).border(1.dp, c.accent, shape)
    else Modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF2557D6))))
    val onBg = if (neon) c.onCard else Color.White
    Column(base.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 1.5.sp, color = if (neon) c.accent else Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(androidx.compose.material.icons.Icons.Outlined.Public, contentDescription = null, tint = onBg.copy(alpha = 0.85f),
                modifier = Modifier.clip(CircleShape).clickable { onRetest() }.padding(4.dp).size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (pingMs == null) {
            Text(emptyLabel, color = onBg.copy(alpha = 0.85f), fontSize = 13.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = onBg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = onBg.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(pingLabel, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (neon) SsPalette.ping(pingMs.toLong(), c.dark) else Color.White)
            }
            Spacer(Modifier.height(12.dp))
            val bShape = RoundedCornerShape(14.dp)
            Row(
                (if (neon) Modifier.neonGlow(c.accent, c.dark, 14.dp, 10.dp, 0.6f).clip(bShape).background(c.accent)
                else Modifier.clip(bShape).background(Color.White.copy(alpha = 0.95f)))
                    .fillMaxWidth().clickable { onAction() }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = if (neon) Color(0xFF071018) else Color(0xFF0F3A5F), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(actionLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (neon) Color(0xFF071018) else Color(0xFF0F3A5F))
            }
        }
    }
}

enum class NavKind { SHOP, SERVERS, HOME, SPEED, SETTINGS }

fun navOutlineIcon(k: NavKind): ImageVector = when (k) {
    NavKind.SHOP -> Icons.Outlined.ShoppingBag
    NavKind.SERVERS -> Icons.Outlined.Dns
    NavKind.HOME -> Icons.Outlined.Home
    NavKind.SPEED -> Icons.Outlined.Public
    NavKind.SETTINGS -> Icons.Outlined.Settings
}

/** Bottom bar icon in the NEON (glow bar above + glowing icon) / CARDS (pill) styles. */
@Composable
fun StyledNavIcon(style: ServerStyle, kind: NavKind, selected: Boolean) {
    val c = ssColors(style)
    if (style.glow) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(26.dp).height(3.dp).then(
                if (selected) Modifier.neonGlow(c.accent, true, 2.dp, 6.dp, 0.9f).clip(RoundedCornerShape(2.dp)).background(c.accent) else Modifier))
            Spacer(Modifier.height(5.dp))
            Icon(navOutlineIcon(kind), contentDescription = null, tint = if (selected) c.accent else c.muted,
                modifier = Modifier.size(24.dp).neonGlow(c.accent, selected && c.dark, 12.dp, 8.dp, 0.5f))
        }
    } else {
        Box(
            Modifier.width(58.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                .background(if (selected) c.accent.copy(alpha = if (c.dark) 0.22f else 0.14f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(navOutlineIcon(kind), contentDescription = null, tint = if (selected) (if (c.dark) Color(0xFF9EC1FF) else c.accent) else c.muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun styledNavColors(style: ServerStyle): NavigationBarItemColors {
    val c = ssColors(style)
    return NavigationBarItemDefaults.colors(
        indicatorColor = Color.Transparent,
        selectedTextColor = if (style.glow) c.accent else c.onCard,
        unselectedTextColor = c.muted,
        selectedIconColor = Color.Unspecified,
        unselectedIconColor = Color.Unspecified,
    )
}

@Composable
fun styledNavContainer(style: ServerStyle): Color {
    val c = ssColors(style)
    return when {
        style.glow && c.dark -> Color(0xFF0B0F1A)
        style.glow -> Color.White
        c.dark -> Color(0xFF161D2E)
        else -> Color.White
    }
}

/** Remaining-traffic ring (∞ when unlimited). */
@Composable
fun TrafficRing(remaining: Float?, color: Color, track: Color, label: String, labelColor: Color, size: Dp = 46.dp) {
    Box(Modifier.size(size).drawBehind {
        val sw = 4.dp.toPx()
        val inset = sw / 2
        val arc = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, false, tl, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawArc(color, -90f, 360f * (remaining ?: 1f), false, tl, arc,
            style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }, contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = labelColor, maxLines = 1)
    }
}

private fun ringColor(rem: Float?): Color = when {
    rem == null -> Color(0xFF5EEAD4)
    rem >= 0.5f -> Color(0xFF34D399)
    rem >= 0.2f -> Color(0xFFFBBF24)
    else -> Color(0xFFF87171)
}

/** «Аврора»: glass tiles with a traffic ring per subscription. */
@Composable
fun AuroraTiles(items: List<RibbonItem>, selected: String, onPick: (String) -> Unit, onReorder: ((List<String>) -> Unit)? = null) {
    val c = ssColors(ServerStyle.AURORA)
    val movable = items.map { it.id }.filter { it.isNotEmpty() && it != ServerStyleLogic.MANUAL_ID }
    val rs = androidx.compose.runtime.remember(movable) { HReorderState(movable) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val spPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    val byId = items.associateBy { it.id }
    val shown = items.filter { it.id !in movable }.filter { it.id.isEmpty() } + rs.order.mapNotNull { byId[it] } + items.filter { it.id !in movable && it.id.isNotEmpty() }
    fun Modifier.drag(id: String) = if (onReorder == null) this else hReorderItem(rs, id, spPx, haptic) { onReorder(it) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { it ->
            val on = it.id == selected
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier.drag(it.id).width(112.dp).clip(shape).background(if (on) c.selectedFill else c.card)
                    .border(if (on) 1.5.dp else 1.dp, if (on) c.accent else c.cardBorder, shape)
                    .clickable { onPick(it.id) }.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val pct = it.remaining?.let { r -> "${(r * 100).toInt()}%" } ?: "∞"
                TrafficRing(it.remaining, ringColor(it.remaining), c.cardBorder, pct, c.onCard)
                Spacer(Modifier.height(6.dp))
                Text(it.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (it.subtitle.isNotEmpty()) Text(it.subtitle, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (it.warn) SsPalette.PingBad else c.muted)
            }
        }
    }
}

/** «Киберпанк»: segmented traffic counter of the selected subscription. */
@Composable
fun CyberTrafficCard(title: String, bigValue: String, ofValue: String, right: String, used: Long, total: Long) {
    val c = ssColors(ServerStyle.CYBER)
    val mag = if (c.dark) Color(0xFFFF2BD6) else Color(0xFFC026D3)
    val shape = ssShape(ServerStyle.CYBER, 16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, mag.copy(alpha = 0.6f), shape).padding(14.dp)) {
        Text("// TRAFFIC_" + title.uppercase(), fontSize = 10.sp, letterSpacing = 1.5.sp, color = c.accent, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bigValue, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = mag, modifier = Modifier.neonGlow(mag, c.dark, 6.dp, 10.dp, 0.35f))
            Text(" $ofValue", fontSize = 13.sp, color = c.onCard, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Text(right, fontSize = 11.sp, color = c.accent, modifier = Modifier.padding(bottom = 5.dp), maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        val n = 20
        val lit = ServerStyleLogic.litSegments(used, total, n)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until n) Box(Modifier.weight(1f).height(7.dp).background(
                if (total <= 0) c.accent.copy(alpha = 0.5f) else if (i < lit) mag else c.cardBorder,
                androidx.compose.foundation.shape.CutCornerShape(2.dp)))
        }
    }
}

/** «Стопка карт»: subscriptions as a swipeable stack; the front card's servers are listed below. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SubCardStack(items: List<RibbonItem>, selected: String, serversLabel: (Int) -> String, hint: String, onPick: (String) -> Unit) {
    if (items.isEmpty()) return
    val c = ssColors(ServerStyle.STACK)
    val start = items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { items.size }
    androidx.compose.runtime.LaunchedEffect(pager.settledPage) {
        items.getOrNull(pager.settledPage)?.let { if (it.id != selected) onPick(it.id) }
    }
    // 1.0.55: one light tick per settled card change
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val lastPage = androidx.compose.runtime.remember { intArrayOf(start) }
    androidx.compose.runtime.LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != lastPage[0]) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        lastPage[0] = pager.settledPage
    }
    val grads = listOf(
        listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)), listOf(Color(0xFFEA580C), Color(0xFFDB2777)),
        listOf(Color(0xFF0D9488), Color(0xFF2563EB)), listOf(Color(0xFF9333EA), Color(0xFFDB2777))
    )
    Column {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 28.dp),
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth().height(138.dp)
        ) { page ->
            val it = items[page]
            val off = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier.fillMaxSize().graphicsLayerCompat(1f - 0.1f * off, 1f - 0.35f * off)
                    .clip(shape).background(Brush.linearGradient(grads[page % grads.size])).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(serversLabel(it.servers), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(it.traffic, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(ServerStyleLogic.usedFraction(it.used, it.total) ?: 0f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color.White))
                }
                if (it.subtitle.isNotEmpty()) Text(it.subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            items.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 2.dp).height(5.dp).width(if (i == pager.currentPage) 16.dp else 5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(if (i == pager.currentPage) c.accent else c.cardBorder))
            }
            if (items.size > 1) Text("  $hint", fontSize = 10.sp, color = c.muted)
        }
    }
}

private fun Modifier.graphicsLayerCompat(scale: Float, alpha: Float): Modifier =
    this.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }

/** «Material You»: traffic plaque; tapping the subscription name opens a picker that switches the shown subscription. */
@Composable
fun MaterialTrafficCard(
    caption: String, bigValue: String, unitLabel: String, items: List<RibbonItem>, selected: String,
    pickLabel: String, onPick: (String) -> Unit
) {
    val c = ssColors(ServerStyle.MATERIAL)
    val bg = if (c.dark) Color(0xFF4A3420) else Color(0xFFFFDDB8)
    val on = if (c.dark) Color(0xFFFFDCBE) else Color(0xFF2C1600)
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val cur = items.firstOrNull { it.id == selected } ?: items.firstOrNull()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(18.dp)) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(cur?.title ?: caption, color = on, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(androidx.compose.material.icons.Icons.Rounded.ArrowDropDown, contentDescription = pickLabel, tint = on)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp)) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.title, fontWeight = if (item.id == selected) FontWeight.Bold else FontWeight.Normal)
                                if (item.subtitle.isNotEmpty()) Text(item.subtitle, fontSize = 11.sp, color = if (item.warn) SsPalette.PingBad else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { open = false; onPick(item.id) }
                    )
                }
            }
        }
        Text(caption, color = on.copy(alpha = 0.75f), fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bigValue, color = on, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            Text(" $unitLabel", color = on.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        cur?.let {
            val f = ServerStyleLogic.usedFraction(it.used, it.total)
            if (f != null) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(on.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(c.accent))
                }
            }
        }
    }
}

@Composable
fun StyledPing(style: ServerStyle, ping: Long?, msLabel: String, timeoutLabel: String, testing: Boolean = false) {
    val c = ssColors(style)
    when {
        testing -> CircularProgressIndicator(strokeWidth = 2.dp, color = c.accent, modifier = Modifier.size(14.dp))
        ping == null -> Text("—", fontSize = 12.sp, color = c.muted)
        else -> {
            val col = SsPalette.ping(ping, c.dark)
            val txt = ServerStyleLogic.pingMs(ping)?.let { "$it $msLabel" } ?: timeoutLabel
            Text(txt, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = col, maxLines = 1,
                modifier = Modifier.neonGlow(col, style.glow && c.dark, 6.dp, 6.dp, 0.35f))
        }
    }
}

/** Small protocol tags (VLESS · Reality) under the server name. */
@Composable
fun ProtoTags(style: ServerStyle, label: String) {
    val c = ssColors(style)
    val parts = label.split(' ', '·', '/').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
    when (style) {
        ServerStyle.AURORA, ServerStyle.STACK -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp)) {
            parts.forEach { p ->
                Box(
                    Modifier.height(16.dp).clip(RoundedCornerShape(4.dp)).background(c.onCard.copy(alpha = 0.08f)).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p, fontSize = 10.sp, lineHeight = 12.sp, color = c.muted, maxLines = 1, softWrap = false)
                }
            }
        }
        ServerStyle.CYBER -> Text(parts.joinToString(" · ").uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted, maxLines = 1)
        else -> Text(parts.joinToString(" · "), fontSize = 10.sp, color = c.muted, maxLines = 1)
    }
}

/** Plaque of the «Ручные серверы» pseudo-subscription in the expandable-groups layout (no traffic / days). */
@Composable
fun ManualGroupPlaque(style: ServerStyle, title: String, info: String) {
    val c = ssColors(style)
    val shape = ssShape(style, 16.dp)
    val hue = if (style.glow) SsPalette.hue(ServerStyleLogic.MANUAL_ID) else c.accent
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).background(c.card)
            .border(1.dp, if (style.glow) hue.copy(alpha = 0.5f) else c.cardBorder, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(androidx.compose.material.icons.Icons.Filled.Edit, null, tint = hue, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (style.glow) hue else c.onCard, modifier = Modifier.weight(1f), maxLines = 1)
        Text(info, fontSize = 11.sp, color = c.muted)
    }
}

/** Material «palette» icon (the extended icon set is not bundled). */
internal val PaletteIcon: ImageVector by lazy {
    ImageVector.Builder("Palette", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5L16,16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"
        ).toNodes(), fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
    }.build()
}
