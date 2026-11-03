package net.antibs.client.ui

/*
 * 1.0.46: map / globe on «Главная» (home screen map view).
 *
 *  • «Карта»: flat equirectangular world map. The land asset (assets/world_map_paths.txt) is NetGuard's
 *    world_map_clean.xml (Apache-2.0, see third_party/world-map-NOTICE.md); the look — light land gradient with a thin
 *    coastline, dotted ocean, graticule, dashed user → server arc that grows on connect, pulsing server marker,
 *    an IP bubble with a tail that never covers a marker, a camera that zooms to fit both ends — follows it.
 *  • «Глобус»: an orthographic globe ray-cast in software from a 2048×1024 land mask (Natural Earth, public domain),
 *    Lambert-shaded with a soft halo; drag rotates it, it turns to the location / the middle of the arc by itself.
 *    It is re-rendered only when the rotation changes, on Dispatchers.Default in parallel, double-buffered.
 *
 * Data: our own servers (country of the flag in the name) as small markers, the selected / live host's country
 * highlighted; the real location (direct GeoIP, physical network) and the VPN exit (GeoIP through the local SOCKS
 * inbound, falls back to the host's country when offline) with the arc between them while connected.
 */

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Speed
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.antibs.client.data.GeoCountries
import net.antibs.client.engine.GeoLocate
import net.antibs.client.engine.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────────────────── shared state / data ─────────────────────────────

/** Survives tab switches (the screen is re-composed when «Главная» is shown again). */
private object WorldCache {
    @Volatile var user: GeoPoint? = null
    @Volatile var device: GeoPoint? = null
    @Volatile var exit: GeoPoint? = null
    @Volatile var exitKey: String = ""
}

/** What the view shows. [liveName]: the host the tunnel runs through right now (or the selected one when off). */
data class WorldInput(
    val connected: Boolean,
    val connecting: Boolean,
    val liveName: String,
    val serverNames: List<String>,
    val socksPort: Int,
)

internal object WorldLogic {
    /** Distinct countries of our servers (flag in the name), in first-seen order. */
    fun serverCountries(names: List<String>): List<String> = names.mapNotNull { GeoCountries.ccOfName(it) }.distinct()

    /** Country of the live host: exit of a chain («🇷🇺 A → 🇩🇪 B») is the last flag. */
    fun liveCountry(name: String): String? {
        if (name.contains('→')) GeoCountries.ccOfName(name.substringAfterLast('→'))?.let { return it }
        return GeoCountries.ccOfName(name)
    }

    /** Same address = the tunnel isn't actually changing the IP («IP открыт»). */
    fun secured(connected: Boolean, exit: GeoPoint?, user: GeoPoint?): Boolean =
        connected && exit != null && !exit.approx && exit.ip.isNotBlank() && (user == null || user.ip != exit.ip)

    // equirectangular calibration of world_map_paths.txt (viewport 1024 × 430, lat 91.75 … −58.22)
    const val MAP_N = 91.75
    const val MAP_S = -58.22
    const val VB_W = 1024f
    const val VB_H = 430f
    fun mapX(lon: Double, w: Float) = ((lon + 180.0) / 360.0 * w).toFloat()
    fun mapY(lat: Double, h: Float) = ((MAP_N - lat) / (MAP_N - MAP_S) * h).toFloat()

    /** Unit-sphere point: x → east (lon 90), y → north, z → towards the viewer at lon 0. */
    fun sphere(lat: Double, lon: Double): DoubleArray {
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        return doubleArrayOf(cos(la) * sin(lo), sin(la), cos(la) * cos(lo))
    }

    /** Rotate a globe-space point to view space: spin around y by −[yaw], then tilt around x by [pitch]. */
    fun view(p: DoubleArray, yaw: Double, pitch: Double): DoubleArray {
        val cy = cos(yaw); val sy = sin(yaw)
        val x1 = p[0] * cy - p[2] * sy
        val z1 = p[0] * sy + p[2] * cy
        val cp = cos(pitch); val sp = sin(pitch)
        val y2 = p[1] * cp - z1 * sp
        val z2 = p[1] * sp + z1 * cp
        return doubleArrayOf(x1, y2, z2)
    }

    /** Yaw / pitch that bring (lat, lon) to the centre of the disc. */
    fun focusAngles(lat: Double, lon: Double): Pair<Double, Double> = Math.toRadians(lon) to Math.toRadians(lat)

    /** Great-circle arc a → b lifted above the surface (n+1 points, unit sphere = surface). */
    fun arc(a: GeoPoint, b: GeoPoint, n: Int = 64): Array<DoubleArray> {
        val p = sphere(a.lat, a.lon); val q = sphere(b.lat, b.lon)
        val d = (p[0] * q[0] + p[1] * q[1] + p[2] * q[2]).coerceIn(-1.0, 1.0)
        val ang = kotlin.math.acos(d)
        val s = sin(ang)
        val lift = 0.04 + 0.18 * (ang / Math.PI)
        return Array(n + 1) { i ->
            val t = i.toDouble() / n
            val (w1, w2) = if (s < 1e-6) (1 - t) to t else sin((1 - t) * ang) / s to sin(t * ang) / s
            var x = w1 * p[0] + w2 * q[0]; var y = w1 * p[1] + w2 * q[1]; var z = w1 * p[2] + w2 * q[2]
            val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-9)
            val k = (1 + lift * sin(Math.PI * t)) / len
            x *= k; y *= k; z *= k
            doubleArrayOf(x, y, z)
        }
    }

    /** Angular distance (radians) between two points. */
    fun angle(a: GeoPoint, b: GeoPoint): Double {
        val p = sphere(a.lat, a.lon); val q = sphere(b.lat, b.lon)
        return kotlin.math.acos((p[0] * q[0] + p[1] * q[1] + p[2] * q[2]).coerceIn(-1.0, 1.0))
    }

    /** Middle of the great circle a → b as (lat, lon). */
    fun midpoint(a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
        val p = sphere(a.lat, a.lon); val q = sphere(b.lat, b.lon)
        val x = p[0] + q[0]; val y = p[1] + q[1]; val z = p[2] + q[2]
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1e-6) return a.lat to a.lon
        return Math.toDegrees(kotlin.math.asin(y / len)) to Math.toDegrees(kotlin.math.atan2(x / len, z / len))
    }

    /** Nearest equivalent of [target] to [current] (so the globe turns the short way round). */
    fun nearest(target: Double, current: Double): Double {
        var t = target
        while (t - current > Math.PI) t -= 2 * Math.PI
        while (t - current < -Math.PI) t += 2 * Math.PI
        return t
    }

    enum class Side { ABOVE, BELOW, LEFT, RIGHT }
    data class Place(val x: Float, val y: Float, val side: Side)

    /** Puts a w×h bubble next to (ax, ay) inside the area, keeping [gap] px away from every point in [avoid]. */
    fun placeBubble(ax: Float, ay: Float, w: Float, h: Float, areaW: Float, areaH: Float, avoid: List<Offset>, gap: Float, edge: Float): Place {
        fun clear(x: Float, y: Float): Float = avoid.minOfOrNull { p ->
            val dx = maxOf(x - p.x, p.x - (x + w), 0f); val dy = maxOf(y - p.y, p.y - (y + h), 0f); hypot(dx, dy)
        } ?: Float.MAX_VALUE
        val cands = listOf(
            Place(ax - w / 2, ay - gap - h, Side.ABOVE), Place(ax - w / 2, ay + gap, Side.BELOW),
            Place(ax + gap, ay - h / 2, Side.RIGHT), Place(ax - gap - w, ay - h / 2, Side.LEFT),
        )
        var best: Place? = null; var bestC = -1f
        for (c in cands) {
            val x = c.x.coerceIn(edge, maxOf(edge, areaW - w - edge)); val y = c.y.coerceIn(edge, maxOf(edge, areaH - h - edge))
            val cl = clear(x, y)
            if (cl >= gap - 0.5f) return Place(x, y, c.side)
            if (cl > bestC) { bestC = cl; best = Place(x, y, c.side) }
        }
        return best!!
    }
}

// ───────────────────────────── palette ─────────────────────────────

private val MapPrimary = Color(0xFF2557D6)
private val ShadowInk = Color(0xFF03253B)
private fun liveGreen(dark: Boolean) = if (dark) Color(0xFF1FD18B) else Color(0xFF0B8F53)

// ───────────────────────────── entry point ─────────────────────────────

/** Share of the «Главная» world block taken by the map (anchored to the bottom); the icon row sits on its top edge. */
private const val MAP_FRACTION = 0.85f
/** 1.0.50b: the globe is centred a little lower. */
private val GLOBE_DROP = 20.dp
/** Icon pills are 32 dp + 2×3 dp padding tall; gap between their bottom edge and the map border. */
private val PILL_H = 38.dp
private val ICON_GAP = 5.dp

/** 1.0.48: ocean (map) / sphere-ocean (globe) opacity 0–100 %, live StateFlows persisted in "ui_world". */
object WorldOpacity {
    private var prefs: android.content.SharedPreferences? = null
    val map = kotlinx.coroutines.flow.MutableStateFlow(100)
    val globe = kotlinx.coroutines.flow.MutableStateFlow(100)
    fun init(ctx: android.content.Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("ui_world", android.content.Context.MODE_PRIVATE)
        prefs = p
        map.value = p.getInt("map_opacity", 100).coerceIn(0, 100)
        globe.value = p.getInt("globe_opacity", 100).coerceIn(0, 100)
    }
    fun set(isMap: Boolean, v: Int, persist: Boolean) {
        val f = if (isMap) map else globe
        f.value = v.coerceIn(0, 100)
        if (persist) prefs?.edit()?.putInt(if (isMap) "map_opacity" else "globe_opacity", f.value)?.apply()
    }
}

/** 1.0.48: state of the «current server» ping button. [ms] null = never measured, -1 = no answer. */
data class WorldPing(val busy: Boolean = false, val ms: Long? = null, val stamp: Long = 0L)

/** «Глобус» / «Карта» block of «Главная». [onServers]: tap on the bubble opens «Серверы». */
@Composable
fun HomeWorld(input: WorldInput, modifier: Modifier = Modifier, bleed: androidx.compose.ui.unit.Dp = 0.dp, onServers: () -> Unit = {},
              ping: WorldPing = WorldPing(), onPing: (() -> Unit)? = null, onNet: (() -> Unit)? = null, onSpeed: (() -> Unit)? = null, holiday: net.antibs.client.data.Holiday? = null,
              seasonalFx: net.antibs.client.data.SeasonalFx = net.antibs.client.data.SeasonalFx(),
              /** 2.0.8: when set, the icon row sits on top, then [middle] (URL test), then the map/globe fills the rest. */
              middle: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    remember { WorldOpacity.init(ctx); 0 }
    var panel by remember { mutableStateOf<String?>(null) }
    val prefs = remember { ctx.getSharedPreferences("ui_world", android.content.Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "globe") ?: "globe") }
    // 1.0.48: location button — ON = the device's own position (Android location), OFF = position of the device's IP
    var geo by remember { mutableStateOf(prefs.getBoolean("devloc", false) && net.antibs.client.engine.DeviceLocation.granted(ctx)) }
    var rationale by remember { mutableStateOf(false) }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val ok = res.values.any { it } || net.antibs.client.engine.DeviceLocation.granted(ctx)
        geo = ok; prefs.edit().putBoolean("devloc", ok).apply()
        if (!ok) android.widget.Toast.makeText(ctx, "Нет доступа к геолокации — показываю место по IP", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun toggleGeo() {
        if (geo) { geo = false; prefs.edit().putBoolean("devloc", false).apply(); return }
        if (net.antibs.client.engine.DeviceLocation.granted(ctx)) { geo = true; prefs.edit().putBoolean("devloc", true).apply() }
        else rationale = true
    }
    if (rationale) androidx.compose.material3.AlertDialog(
        onDismissRequest = { rationale = false },
        title = { Text("Моё местоположение") },
        text = { Text("Чтобы показать на глобусе и карте, где вы находитесь на самом деле, нужен доступ к геолокации. Позиция определяется один раз, пока открыт экран «Главная», и никуда не отправляется. Без доступа используется место по вашему IP.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            rationale = false
            permLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION))
        }) { Text("Разрешить") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { rationale = false }) { Text("Отмена") } },
    )
    // one-shot refresh each time the screen comes back (no background tracking)
    var resumes by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // ---- locations ----
    var ipUser by remember { mutableStateOf(WorldCache.user) }
    var devUser by remember { mutableStateOf(WorldCache.device) }
    var exit by remember { mutableStateOf(if (input.connected && WorldCache.exitKey == input.liveName) WorldCache.exit else null) }
    LaunchedEffect(Unit) {
        if (ipUser != null) return@LaunchedEffect
        repeat(3) {
            val l = GeoLocate.direct(ctx)
            if (l != null) { ipUser = l; WorldCache.user = l; return@LaunchedEffect }
            delay(1500)
        }
    }
    LaunchedEffect(geo, resumes) {
        if (!geo) return@LaunchedEffect
        if (!net.antibs.client.engine.DeviceLocation.granted(ctx)) { geo = false; prefs.edit().putBoolean("devloc", false).apply(); return@LaunchedEffect }
        val fix = net.antibs.client.engine.DeviceLocation.current(ctx)
        if (fix == null) {
            if (devUser == null) android.widget.Toast.makeText(ctx, "Не удалось определить местоположение — показываю место по IP", android.widget.Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        val place = net.antibs.client.engine.DeviceLocation.place(ctx, fix.latitude, fix.longitude)
        val ip = ipUser
        val p = GeoPoint(ip?.ip ?: "", place?.first?.ifBlank { null } ?: "Моё местоположение", place?.second?.ifBlank { null } ?: ip?.country.orEmpty(),
            ip?.cc.orEmpty(), fix.latitude, fix.longitude)
        devUser = p; WorldCache.device = p
    }
    val user = if (geo) devUser?.let { d -> ipUser?.let { d.copy(ip = it.ip, cc = d.cc.ifEmpty { it.cc }) } ?: d } ?: ipUser else ipUser
    LaunchedEffect(input.connected, input.liveName) {
        if (!input.connected) { exit = null; return@LaunchedEffect }
        val fallback = GeoLocate.ofCountry(WorldLogic.liveCountry(input.liveName))
        if (WorldCache.exitKey == input.liveName && WorldCache.exit != null) { exit = WorldCache.exit; return@LaunchedEffect }
        exit = fallback
        delay(1200)
        repeat(8) { attempt ->
            val l = GeoLocate.tunnel(ctx, input.socksPort)
            if (l != null) { exit = l; WorldCache.exit = l; WorldCache.exitKey = input.liveName; return@LaunchedEffect }
            delay(minOf(1500L + attempt * 700L, 6000L))
        }
    }
    val countries = remember(input.serverNames) { WorldLogic.serverCountries(input.serverNames) }
    val hot = WorldLogic.liveCountry(input.liveName)
    val shown: GeoPoint? = if (input.connected) exit else user
    val data = WorldData(user, exit, shown, input.connected, WorldLogic.secured(input.connected, exit, user), countries, hot)

    val modePill: @Composable (Modifier) -> Unit = { m ->
        ModePill(mode, geo,
            onMode = { mode = it; prefs.edit().putString("mode", it).apply() },
            onGeo = { toggleGeo() },
            onLong = { k -> mode = k; prefs.edit().putString("mode", k).apply(); panel = k },
            modifier = m)
    }
    val tools: @Composable (Modifier) -> Unit = { m ->
        if (onPing != null || onNet != null || onSpeed != null) Row(m, verticalAlignment = Alignment.CenterVertically) {
            var first = true
            @Composable fun gap() { if (!first) Spacer(Modifier.width(8.dp)); first = false }
            if (onNet != null) {
                gap()
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                        .clickable(onClickLabel = "Мониторинг сети", onClick = onNet).padding(3.dp)
                        .size(36.dp, 32.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.CellTower, "Сеть", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            }
            if (onSpeed != null) {
                gap()
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                        .clickable(onClickLabel = "Тест скорости", onClick = onSpeed).padding(3.dp)
                        .size(36.dp, 32.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Speed, "Тест скорости", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            }
            if (onPing != null) { gap(); PingPill(ping, onPing, Modifier) }
        }
    }
    if (middle != null) {
        Column(modifier) {
            Box(Modifier.fillMaxWidth().height(PILL_H)) {
                tools(Modifier.align(Alignment.CenterStart))
                modePill(Modifier.align(Alignment.CenterEnd))
            }
            Spacer(Modifier.height(6.dp))
            middle()
            Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.animation.Crossfade(mode, animationSpec = androidx.compose.animation.core.tween(380), label = "world-mode") { m ->
                    if (m == "map") Box(Modifier.fillMaxSize()) {
                        MapView(data, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight().bleedX(bleed), onServers)
                    }
                    else GlobeView(data, Modifier.fillMaxSize().padding(bottom = 4.dp).offset(y = GLOBE_DROP), onServers)
                }
                if (holiday != null && seasonalFx.showLabel) Row(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeasonalLabelText(holiday.emoji, holiday.name, seasonalFx, base = 14f, defColor = Color.White.copy(alpha = 0.92f))
                }
                panel?.let { k -> OpacityPanel(k == "map", 4.dp) { panel = null } }
            }
        }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // 1.0.50c: icon row sits just above the map's top border (bottom edge ICON_GAP above it)
        val iconsBottom = (maxHeight * (1f - MAP_FRACTION) - ICON_GAP).coerceAtLeast(PILL_H)
        androidx.compose.animation.Crossfade(mode, animationSpec = androidx.compose.animation.core.tween(380), label = "world-mode") { m ->
            // the map runs edge to edge and takes ~85 % of the free height, anchored to the bottom
            if (m == "map") Box(Modifier.fillMaxSize()) {
                MapView(data, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(MAP_FRACTION).bleedX(bleed), onServers)
            }
            else GlobeView(data, Modifier.fillMaxSize().padding(bottom = 8.dp).offset(y = GLOBE_DROP), onServers)
        }
        modePill(Modifier.align(Alignment.TopEnd).padding(top = iconsBottom - PILL_H))
        tools(Modifier.align(Alignment.TopStart).padding(top = iconsBottom - PILL_H))
        if (holiday != null && seasonalFx.showLabel) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = (iconsBottom - PILL_H - 40.dp).coerceAtLeast(4.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 2.0.6 (ABS): label colour / opacity / size from «Сезонные украшения»
                SeasonalLabelText(holiday.emoji, holiday.name, seasonalFx, base = 14f, defColor = Color.White.copy(alpha = 0.92f))
            }
        }
        panel?.let { k -> OpacityPanel(k == "map", iconsBottom + 4.dp) { panel = null } }
    }
}

private class WorldData(
    val user: GeoPoint?, val exit: GeoPoint?, val shown: GeoPoint?, val connected: Boolean, val secured: Boolean,
    val countries: List<String>, val hot: String?,
)

/** Lay out wider than the parent by [bleed] on both sides (edge-to-edge map inside a padded column). */
private fun Modifier.bleedX(bleed: androidx.compose.ui.unit.Dp): Modifier = if (bleed.value <= 0f) this else this.then(
    Modifier.layout { m, c ->
        val b = bleed.roundToPx()
        val w = c.maxWidth + 2 * b
        val p = m.measure(c.copy(minWidth = w, maxWidth = w))
        layout(c.maxWidth, p.height) { p.place(-b, 0) }
    }
)

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ModePill(mode: String, geo: Boolean, onMode: (String) -> Unit, onGeo: () -> Unit, onLong: (String) -> Unit, modifier: Modifier) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("globe" to Icons.Filled.Public, "map" to Icons.Filled.Map).forEach { (k, ic) ->
            val sel = mode == k
            Box(
                Modifier.size(40.dp, 32.dp).clip(RoundedCornerShape(50))
                    .background(if (sel) Brush.linearGradient(listOf(AntiBSColors.BlueDeep, AntiBSColors.Purple)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .combinedClickable(onClick = { onMode(k) }, onLongClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLong(k)
                    }, onLongClickLabel = "Прозрачность"),
                contentAlignment = Alignment.Center,
            ) { Icon(ic, if (k == "map") "Карта" else "Глобус", tint = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        }
        Box(Modifier.size(36.dp, 32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onGeo), contentAlignment = Alignment.Center) {
            Icon(if (geo) Icons.Filled.MyLocation else Icons.Filled.Language,
                if (geo) "Моё местоположение (геолокация устройства). Нажмите — место по IP" else "Место по IP. Нажмите — моё местоположение",
                tint = if (geo) AntiBSColors.Ok else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
        }
    }
}

/** 1.0.48: left-edge ping of the current server: spinner while checking, then the result for a few seconds. */
@Composable
private fun PingPill(ping: WorldPing, onPing: () -> Unit, modifier: Modifier) {
    var showUntil by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ping.stamp, ping.busy) {
        if (!ping.busy && ping.stamp > 0L) {
            showUntil = System.currentTimeMillis() + 4000L
            while (System.currentTimeMillis() < showUntil) { now = System.currentTimeMillis(); delay(250) }
            now = System.currentTimeMillis()
        }
    }
    val show = !ping.busy && ping.stamp > 0L && now < showUntil
    val ms = ping.ms
    val tone = when {
        ms == null || ms < 0 -> AntiBSColors.Err
        ms < 200 -> AntiBSColors.Ok
        ms < 500 -> AntiBSColors.Warn
        else -> AntiBSColors.Err
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .clickable(enabled = !ping.busy, onClickLabel = "Проверить пинг текущего сервера", onClick = onPing).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp, 32.dp), contentAlignment = Alignment.Center) {
            if (ping.busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AntiBSColors.Blue)
            else Icon(Icons.Filled.NetworkCheck, "Пинг текущего сервера", tint = if (show) tone else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        androidx.compose.animation.AnimatedVisibility(show) {
            Text(if (ms == null || ms < 0) "нет ответа" else "$ms мс", color = tone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.padding(end = 10.dp))
        }
    }
}

/** 1.0.48: long press on the globe / map icon — floating «Прозрачность» slider, applied live, saved on release. */
@Composable
private fun OpacityPanel(isMap: Boolean, top: androidx.compose.ui.unit.Dp = 0.dp, onClose: () -> Unit) {
    val value by (if (isMap) WorldOpacity.map else WorldOpacity.globe).collectAsState()
    val gap = with(LocalDensity.current) { top.roundToPx() }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, gap),
        onDismissRequest = { WorldOpacity.set(isMap, value, true); onClose() },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        androidx.compose.material3.Surface(
            Modifier.width(264.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp, shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isMap) "Прозрачность карты" else "Прозрачность глобуса", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text("$value%", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AntiBSColors.Blue, modifier = Modifier.padding(end = 6.dp))
                }
                Text("Прозрачность океанов: суша, линии и маркеры остаются непрозрачными", fontSize = 11.sp, lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 6.dp))
                androidx.compose.material3.Slider(
                    value = value.toFloat(), valueRange = 0f..100f,
                    onValueChange = { WorldOpacity.set(isMap, it.roundToInt(), false) },
                    onValueChangeFinished = { WorldOpacity.set(isMap, (if (isMap) WorldOpacity.map else WorldOpacity.globe).value, true) },
                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = { WorldOpacity.set(isMap, value, true); onClose() }) { Text("Готово") }
                }
            }
        }
    }
}

// ───────────────────────────── IP bubble ─────────────────────────────

@Composable
private fun IpBubble(l: GeoPoint, secured: Boolean, connected: Boolean, scale: Float) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val live by animateColorAsState(if (secured) liveGreen(dark) else if (connected) AntiBSColors.Warn else AntiBSColors.Blue, tween(420), label = "bubble-live")
    val ink = if (dark) Color.White else Color(0xFF121722)
    Column(Modifier.padding(start = (8f * scale).dp, end = (12f * scale).dp, top = (6f * scale).dp, bottom = (7f * scale).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(live, scale)
            Spacer(Modifier.width((4f * scale).dp))
            Text(
                when { secured -> "IP защищён"; connected -> if (l.approx) "Через VPN" else "IP не изменился"; else -> "IP открыт" },
                color = live, fontWeight = FontWeight.Bold, fontSize = (10f * scale).sp, lineHeight = (13f * scale).sp, maxLines = 1,
            )
        }
        val flag = if (l.cc.length == 2) l.cc.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("") else ""
        // 1.0.49: own IP in full, VPN exit IP (connected bubble) masked; smaller text
        if (l.ip.isNotBlank()) Text(if (connected) GeoLocate.mask(l.ip) else l.ip, color = ink, fontWeight = FontWeight.Bold,
            fontSize = (11.5f * scale).sp, lineHeight = (15f * scale).sp, maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
        Text(
            listOf(flag, l.city.ifBlank { null } ?: l.country.ifBlank { GeoCountries.name(l.cc) }).filter { it.isNotBlank() }.joinToString(" "),
            color = ink.copy(alpha = 0.82f), fontWeight = FontWeight.SemiBold, fontSize = (10f * scale).sp, lineHeight = (14f * scale).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PulseDot(color: Color, scale: Float) {
    val t by rememberInfiniteTransition(label = "pdot").animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "pdot-t")
    Box(Modifier.size((15f * scale).dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size((15f * scale).dp).graphicsLayer { val s = 0.4f + 0.6f * t; scaleX = s; scaleY = s; alpha = (1f - t) * 0.6f }
            .background(Brush.radialGradient(listOf(color, Color.Transparent)), CircleShape))
        Box(Modifier.size((6f * scale).dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun bubbleColors(): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = AntiBSColors.Blue
    return (if (dark) Color(0xFF0B1424).copy(alpha = 0.96f) else Color.White.copy(alpha = 0.97f)) to
        (if (dark) lerp(accent, Color.Black, 0.45f) else lerp(accent, Color.White, 0.34f))
}

// ───────────────────────────── markers ─────────────────────────────

private fun DrawScope.userDot(c: Offset, color: Color, dp: Float, a: Float = 1f, underlay: Boolean) {
    if (underlay) drawCircle(ShadowInk.copy(alpha = 0.55f * a), 5.2f * dp, c)
    drawCircle(color.copy(alpha = 0.10f * a), 11f * dp, c)
    drawCircle(color.copy(alpha = 0.20f * a), 6.5f * dp, c)
    drawCircle(color.copy(alpha = 0.92f * a), 3.4f * dp, c)
    drawCircle(Color.White.copy(alpha = 0.85f * a), 1.2f * dp, c)
}

private fun DrawScope.serverPulse(c: Offset, color: Color, pulse: Float, dp: Float, underlay: Boolean) {
    if (underlay) drawCircle(ShadowInk.copy(alpha = 0.55f), 6.4f * dp, c)
    val r = 9f * dp * (1f + pulse * 1.6f)
    drawCircle(color.copy(alpha = (1f - pulse) * 0.26f), r, c)
    drawCircle(color.copy(alpha = (1f - pulse) * 0.55f), r, c, style = Stroke(1.2f * dp))
    drawCircle(color.copy(alpha = 0.14f), 14f * dp, c)
    drawCircle(color.copy(alpha = 0.28f), 8f * dp, c)
    drawCircle(color, 4.4f * dp, c)
    drawCircle(Color.White.copy(alpha = 0.92f), 1.7f * dp, c)
}

/** One of our servers' countries: small ring; the selected / live country is bigger and accent-coloured. */
private fun DrawScope.countryDot(c: Offset, hot: Boolean, accent: Color, dp: Float, a: Float = 1f) {
    if (hot) {
        drawCircle(accent.copy(alpha = 0.22f * a), 7.5f * dp, c)
        drawCircle(Color.White.copy(alpha = 0.95f * a), 3.6f * dp, c)
        drawCircle(accent.copy(alpha = a), 2.6f * dp, c)
    } else {
        drawCircle(ShadowInk.copy(alpha = 0.35f * a), 3.2f * dp, c)
        drawCircle(Color.White.copy(alpha = 0.85f * a), 2.2f * dp, c)
        drawCircle(accent.copy(alpha = 0.85f * a), 1.4f * dp, c)
    }
}

private fun DrawScope.bubbleTail(pl: WorldLogic.Place, a: Offset, pw: Float, ph: Float, bg: Color, border: Color, dp: Float) {
    val half = 7f * dp; val len = 10f * dp; val inset = 18f * dp
    val t = Path()
    when (pl.side) {
        WorldLogic.Side.ABOVE -> { val x = a.x.coerceIn(pl.x + inset, pl.x + pw - inset); val y = pl.y + ph - dp
            t.moveTo(x - half, y); t.lineTo(x + half, y); t.lineTo(x + (a.x - x).coerceIn(-half, half) * 0.5f, y + len + dp) }
        WorldLogic.Side.BELOW -> { val x = a.x.coerceIn(pl.x + inset, pl.x + pw - inset); val y = pl.y + dp
            t.moveTo(x - half, y); t.lineTo(x + half, y); t.lineTo(x + (a.x - x).coerceIn(-half, half) * 0.5f, y - len - dp) }
        WorldLogic.Side.LEFT -> { val y = a.y.coerceIn(pl.y + inset, pl.y + ph - inset); val x = pl.x + pw - dp
            t.moveTo(x, y - half); t.lineTo(x, y + half); t.lineTo(x + len + dp, y + (a.y - y).coerceIn(-half, half) * 0.5f) }
        WorldLogic.Side.RIGHT -> { val y = a.y.coerceIn(pl.y + inset, pl.y + ph - inset); val x = pl.x + dp
            t.moveTo(x, y - half); t.lineTo(x, y + half); t.lineTo(x - len - dp, y + (a.y - y).coerceIn(-half, half) * 0.5f) }
    }
    t.close()
    drawPath(t, bg)
    drawPath(t, border, style = Stroke(1.4f * dp, join = StrokeJoin.Round))
}

// ───────────────────────────── «Карта» ─────────────────────────────

private object WorldPaths {
    @Volatile private var cached: List<Path>? = null
    fun peek(): List<Path>? = cached
    fun get(ctx: android.content.Context): List<Path> = cached ?: synchronized(this) {
        cached ?: run {
            val parser = PathParser()
            ctx.assets.open("world_map_paths.txt").bufferedReader().useLines { ls ->
                ls.filter { it.isNotBlank() }.map { parser.parsePathString(it.trim()).toPath().apply { fillType = PathFillType.EvenOdd } }.toList()
            }
        }.also { cached = it }
    }
}

@Composable
private fun MapView(d: WorldData, modifier: Modifier, onServers: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val mapPct by WorldOpacity.map.collectAsState()
    val mapOcean = mapPct / 100f
    var paths by remember { mutableStateOf(WorldPaths.peek()) }
    LaunchedEffect(Unit) { if (paths == null) paths = withContext(Dispatchers.Default) { WorldPaths.get(ctx) } }
    val arcOn = d.connected && d.user != null && d.exit != null
    val grow = remember { Animatable(0f) }
    LaunchedEffect(arcOn) { if (arcOn) grow.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) else grow.snapTo(0f) }
    // animations only run while connected (idle map = no per-frame work)
    val inf = if (d.connected) rememberInfiniteTransition(label = "map") else null
    val dashS = inf?.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "map-dash")
    val pulseS = inf?.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing)), label = "map-pulse")
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val intro by animateFloatAsState(if (appeared) 1f else 0f, tween(550), label = "map-intro")
    val (popBg, popBorder) = bubbleColors()
    val accent = AntiBSColors.Blue

    val oceanTop = if (dark) Color(0xFF061526) else Color(0xFFDCEBFA)
    val oceanBottom = if (dark) Color(0xFF020914) else Color(0xFFBBD3EC)
    val landTop = Color(0xFF0B5E94); val landBottom = Color(0xFF0A8C8C); val coast = Color(0xFF03253B)
    val dotsColor = if (dark) Color.White.copy(alpha = 0.17f) else Color(0xFF0B3D63).copy(alpha = 0.24f)
    val grid = if (dark) Color(0xFF8FB8FF).copy(alpha = 0.22f) else MapPrimary.copy(alpha = 0.30f)
    val userC = if (dark) Color(0xFF6FB4FF) else MapPrimary
    val live = liveGreen(dark)
    val glow = if (dark) Color(0xFF39E6FF) else MapPrimary
    val arcPath = remember { Path() }

    BoxWithConstraints(modifier.clipToBounds().graphicsLayer { alpha = intro }) {
        val dp = with(density) { 1.dp.toPx() }
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val ui = (maxWidth.value / 330f).coerceIn(1f, 1.3f)
        var pop by remember { mutableStateOf(IntSize.Zero) }
        // camera: land fills the box, follows the point(s), zooms out to fit both ends of the arc
        val bw = wPx; val bh = wPx / (WorldLogic.VB_W / WorldLogic.VB_H)
        val a0 = (if (d.connected) d.exit ?: d.user else d.user)
        val ax = a0?.let { WorldLogic.mapX(it.lon, bw) } ?: bw * 0.52f
        val ay = a0?.let { WorldLogic.mapY(it.lat, bh) } ?: bh * 0.40f
        val bx = if (arcOn) WorldLogic.mapX(d.user!!.lon, bw) else ax
        val by = if (arcOn) WorldLogic.mapY(d.user!!.lat, bh) else ay
        val margin = 64f * dp
        var z = (hPx / bh).coerceIn(1f, 3.6f)
        if (abs(ax - bx) > 1f) z = minOf(z, (wPx - 2 * margin) / abs(ax - bx))
        if (abs(ay - by) > 1f) z = minOf(z, (hPx - 2 * margin) / abs(ay - by))
        z = z.coerceIn(1f, 3.6f)
        val fx = (ax + bx) / 2f; val fy = (ay + by) / 2f
        val txT = (wPx / 2f - fx * z).coerceIn(wPx - bw * z, 0f)
        val tyT = if (bh * z <= hPx) (hPx - bh * z) / 2f else (hPx * 0.56f - fy * z).coerceIn(hPx - bh * z, 0f)
        val cam = tween<Float>(1000, easing = FastOutSlowInEasing)
        val zA by animateFloatAsState(z, cam, label = "cz")
        val txA by animateFloatAsState(txT, cam, label = "cx")
        val tyA by animateFloatAsState(tyT, cam, label = "cy")
        fun px(l: GeoPoint) = Offset(WorldLogic.mapX(l.lon, bw) * zA + txA, WorldLogic.mapY(l.lat, bh) * zA + tyA)
        fun pxCc(cc: String) = GeoCountries.point(cc)?.let { Offset(WorldLogic.mapX(it.lon, bw) * zA + txA, WorldLogic.mapY(it.lat, bh) * zA + tyA) }
        fun place(): WorldLogic.Place? {
            val l = d.shown ?: return null
            val a = px(l)
            val pts = mutableListOf(a)
            if (arcOn) { pts.add(px(d.user!!)); pts.add(px(d.exit!!)) }
            val pw = if (pop.width > 0) pop.width.toFloat() else 130f * dp
            val ph = if (pop.height > 0) pop.height.toFloat() else 58f * dp
            return WorldLogic.placeBubble(a.x, a.y, pw, ph, wPx, hPx, pts, 24f * dp, 6f * dp)
        }

        Canvas(Modifier.fillMaxSize().drawWithCache {
            val ocean = Brush.verticalGradient(listOf(oceanTop, oceanBottom))
            val step = 14.dp.toPx()
            val pts = ArrayList<Offset>()
            var yy = step / 2; while (yy < size.height) { var xx = step / 2; while (xx < size.width) { pts.add(Offset(xx, yy)); xx += step }; yy += step }
            val land = Brush.verticalGradient(listOf(landTop, landBottom), startY = 0f, endY = WorldLogic.VB_H)
            val vignette = Brush.radialGradient(0.78f to Color.Transparent, 1f to Color.Black.copy(alpha = if (dark) 0.16f else 0.03f),
                center = Offset(size.width / 2, size.height / 2), radius = size.maxDimension * 0.8f)
            onDrawBehind {
                drawRect(ocean, alpha = 0.85f * mapOcean)
                drawPoints(pts, PointMode.Points, dotsColor, 1.5f * dp, StrokeCap.Round)
                var lon = -150; while (lon <= 150) { val x = WorldLogic.mapX(lon.toDouble(), bw) * zA + txA; drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); lon += 30 }
                var lat = -30; while (lat <= 60) { val y = WorldLogic.mapY(lat.toDouble(), bh) * zA + tyA; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); lat += 30 }
                val s = bw * zA / WorldLogic.VB_W
                withTransform({ translate(txA, tyA); scale(s, s, pivot = Offset.Zero) }) {
                    val ps = paths.orEmpty()
                    for (p in ps) drawPath(p, land)
                    val sw = 1.1f * dp / s
                    for (p in ps) drawPath(p, coast, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawRect(vignette)
            }
        }) {}

        Canvas(Modifier.fillMaxSize()) {
            val dash = dashS?.value ?: 0f
            val pulse = pulseS?.value ?: 0f
            for (cc in d.countries) if (cc != d.hot || !d.connected) pxCc(cc)?.let { countryDot(it, cc == d.hot, accent, dp) }
            val u = d.user; val e = d.exit; val p = grow.value
            if (arcOn && p > 0f) {
                val uo = px(u!!); val so = px(e!!)
                val mid = (uo.y + so.y) / 2f
                val hArc = -minOf(minOf(hypot(so.x - uo.x, so.y - uo.y) * 0.28f, size.height * 0.34f), (mid - 10f * dp).coerceAtLeast(8f * dp))
                val steps = 60
                arcPath.rewind()
                for (i in 0..(steps * p).toInt()) {
                    val t = i.toFloat() / steps
                    val x = uo.x + (so.x - uo.x) * t; val y = uo.y + (so.y - uo.y) * t + hArc * 4f * t * (1f - t)
                    if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
                }
                drawPath(arcPath, glow.copy(alpha = (if (dark) 0.14f else 0.07f) * p), style = Stroke(16f * dp, cap = StrokeCap.Round))
                drawPath(arcPath, glow.copy(alpha = (if (dark) 0.30f else 0.14f) * p), style = Stroke(8f * dp, cap = StrokeCap.Round))
                drawPath(arcPath, ShadowInk.copy(alpha = 0.55f * p), style = Stroke(3.6f * dp, cap = StrokeCap.Round))
                drawPath(arcPath, Color.White.copy(alpha = 0.95f * p), style = Stroke(1.8f * dp, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * dp, 4f * dp), -dash * 10f * dp)))
                if (p >= 1f) {
                    val t = dash
                    val c = Offset(uo.x + (so.x - uo.x) * t, uo.y + (so.y - uo.y) * t + hArc * 4f * t * (1f - t))
                    drawCircle(live.copy(alpha = 0.22f), 7f * dp, c); drawCircle(Color.White, 2.2f * dp, c)
                }
                userDot(uo, userC, dp, underlay = dark)
                serverPulse(so, live, pulse, dp, underlay = dark)
            } else if (d.connected && e != null) serverPulse(px(e), live, pulse, dp, underlay = dark)
            else if (u != null) userDot(px(u), userC, dp, underlay = dark)
            val l = d.shown; val pl = place()
            if (l != null && pl != null && pop.width > 0) bubbleTail(pl, px(l), pop.width.toFloat(), pop.height.toFloat(), popBg, popBorder, dp)
        }

        d.shown?.let { l ->
            Card(
                Modifier.widthIn(max = (200f * ui).dp).onSizeChanged { pop = it }
                    .absoluteOffset { place()?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } ?: IntOffset.Zero }
                    .clickable(onClick = onServers),
                shape = RoundedCornerShape(14.dp), border = BorderStroke(1.4.dp, popBorder),
                colors = CardDefaults.cardColors(containerColor = popBg), elevation = CardDefaults.cardElevation(0.dp),
            ) { IpBubble(l, d.secured, d.connected, ui) }
        }
    }
}

// ───────────────────────────── «Глобус» ─────────────────────────────

/** 2048×1024 1-bit land mask (MSB first), unpacked to one byte (0 / 1) per cell. */
private object LandMask {
    const val W = 2048
    const val H = 1024
    @Volatile private var data: ByteArray? = null
    fun get(ctx: android.content.Context): ByteArray = data ?: synchronized(this) {
        data ?: run {
            val packed = ctx.assets.open("land_mask.bin").use { it.readBytes() }
            val out = ByteArray(W * H)
            for (i in out.indices) out[i] = ((packed[i ushr 3].toInt() ushr (7 - (i and 7))) and 1).toByte()
            out
        }.also { data = it }
    }
}

/** Per-size precomputation: view-space normal and static shading of every pixel inside the disc. */
private class Disc(val n: Int) {
    val r = n / 2f * 0.86f
    val count: Int
    val pix: IntArray
    val nx: FloatArray; val ny: FloatArray; val nz: FloatArray
    val light: FloatArray; val edgeA: FloatArray
    init {
        val c = n / 2f
        val tmp = ArrayList<Int>()
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + 0.5f - c) / r; val dy = (c - y - 0.5f) / r
            if (dx * dx + dy * dy <= 1f) tmp.add(y * n + x)
        }
        count = tmp.size
        pix = tmp.toIntArray()
        nx = FloatArray(count); ny = FloatArray(count); nz = FloatArray(count); light = FloatArray(count); edgeA = FloatArray(count)
        // light from the upper left, slightly in front
        val lx = -0.45f; val ly = 0.55f; val lz = 0.70f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (k in 0 until count) {
            val p = pix[k]
            val dx = ((p % n) + 0.5f - c) / r; val dy = (c - (p / n) - 0.5f) / r
            val d2 = dx * dx + dy * dy
            val dz = sqrt((1f - d2).coerceAtLeast(0f))
            nx[k] = dx; ny[k] = dy; nz[k] = dz
            val lam = ((dx * lx + dy * ly + dz * lz) / ll).coerceAtLeast(0f)
            val rim = sqrt(minOf(1f, (1f - d2) * 3f))
            light[k] = (0.30f + 0.70f * lam) * (0.55f + 0.45f * rim)
            edgeA[k] = ((1f - sqrt(d2)) * r * 1.6f).coerceIn(0f, 1f)
        }
    }
}

private class GlobePalette(val ocean: Int, val land: Int, val coast: Int)

private fun renderGlobe(out: IntArray, d: Disc, mask: ByteArray, yaw: Float, pitch: Float, pal: GlobePalette, part: Int, parts: Int, oceanA: Float = 1f) {
    val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
    val oR = (pal.ocean shr 16) and 255; val oG = (pal.ocean shr 8) and 255; val oB = pal.ocean and 255
    val lR = (pal.land shr 16) and 255; val lG = (pal.land shr 8) and 255; val lB = pal.land and 255
    val cR = (pal.coast shr 16) and 255; val cG = (pal.coast shr 8) and 255; val cB = pal.coast and 255
    val W = LandMask.W; val H = LandMask.H
    val toU = W / (2.0 * Math.PI).toFloat(); val toV = H / Math.PI.toFloat()
    var k = part
    while (k < d.count) {
        // inverse view rotation: un-tilt around x, then un-spin around y
        val vx = d.nx[k]; val vy = d.ny[k]; val vz = d.nz[k]
        val y1 = vy * cp + vz * sp
        val z1 = -vy * sp + vz * cp
        val x0 = vx * cy + z1 * sy
        val z0 = -vx * sy + z1 * cy
        val yc = if (y1 > 1f) 1f else if (y1 < -1f) -1f else y1
        val lat = fastAtan2(yc, sqrt(1f - yc * yc))
        val lon = fastAtan2(x0, z0)
        // bilinear land coverage
        val u = (lon + Math.PI.toFloat()) * toU - 0.5f
        val v = (Math.PI.toFloat() / 2f - lat) * toV - 0.5f
        val u0 = kotlin.math.floor(u).toInt(); val v0 = kotlin.math.floor(v).toInt()
        val fu = u - u0; val fv = v - v0
        val ua = ((u0 % W) + W) % W; val ub = (ua + 1) % W
        val va = v0.coerceIn(0, H - 1); val vb = (v0 + 1).coerceIn(0, H - 1)
        val m00 = mask[va * W + ua]; val m10 = mask[va * W + ub]; val m01 = mask[vb * W + ua]; val m11 = mask[vb * W + ub]
        val top = m00 + (m10 - m00) * fu; val bot = m01 + (m11 - m01) * fu
        val cov = top + (bot - top) * fv
        val sea = 1f - cov
        val edge = cov * sea * 4f
        val sh = d.light[k]
        val r = ((oR * sea + lR * cov) * sh + cR * edge * 0.6f).toInt().coerceAtMost(255)
        val g = ((oG * sea + lG * cov) * sh + cG * edge * 0.6f).toInt().coerceAtMost(255)
        val b = ((oB * sea + lB * cov) * sh + cB * edge * 0.6f).toInt().coerceAtMost(255)
        val a = (d.edgeA[k] * 255f * (sea * oceanA + cov)).toInt().coerceIn(0, 255)
        out[d.pix[k]] = (a shl 24) or (r shl 16) or (g shl 8) or b
        k += parts
    }
}

/** atan2 via an 11th-order odd polynomial for atan on [0, 1] (max error ≈ 1e-5 rad). */
internal fun fastAtan2(y: Float, x: Float): Float {
    val ax = abs(x); val ay = abs(y)
    if (ax == 0f && ay == 0f) return 0f
    val swap = ay > ax
    val a = if (swap) ax / ay else ay / ax
    val q = a * a
    var r = a * (0.99997726f + q * (-0.33262347f + q * (0.19354346f + q * (-0.11643287f + q * (0.05265332f - 0.0117212f * q)))))
    if (swap) r = 1.5707964f - r
    if (x < 0f) r = 3.1415927f - r
    return if (y < 0f) -r else r
}

private val CORES = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

@Composable
private fun GlobeView(d: WorldData, modifier: Modifier, onServers: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scope = rememberCoroutineScope()
    val accent = AntiBSColors.Blue
    val oceanPct by WorldOpacity.globe.collectAsState()
    val (popBg, popBorder) = bubbleColors()
    val live = liveGreen(dark)
    val userC = if (dark) Color(0xFF6FB4FF) else accent
    val halo = if (dark) Color(0xFF5E7FE8) else Color(0xFF4A6CC8)
    val pal = if (dark) GlobePalette(0xFF14306A.toInt(), 0xFF5A86E6.toInt(), 0xFFB9D4FF.toInt())
    else GlobePalette(0xFFA9CBF2.toInt(), 0xFF3F6FC4.toInt(), 0xFF1E3A66.toInt())

    val yaw = remember { Animatable(0f) }
    val pitch = remember { Animatable(0.35f) }
    var first by remember { mutableStateOf(true) }
    val arcOn = d.connected && d.user != null && d.exit != null
    val grow = remember { Animatable(0f) }
    LaunchedEffect(arcOn) { if (arcOn) grow.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) else grow.snapTo(0f) }
    val arc = remember(d.user, d.exit, arcOn) { if (arcOn) WorldLogic.arc(d.user!!, d.exit!!) else null }
    val focus: Pair<Double, Double>? = when {
        arcOn && WorldLogic.angle(d.user!!, d.exit!!) < 2.4 -> WorldLogic.midpoint(d.user, d.exit)
        d.shown != null -> d.shown.lat to d.shown.lon
        d.hot != null -> GeoCountries.point(d.hot)?.let { it.lat to it.lon }
        else -> null
    }
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        val (ty, tp) = WorldLogic.focusAngles(f.first, f.second)
        val y = WorldLogic.nearest(ty, yaw.value.toDouble()).toFloat()
        val p = tp.toFloat().coerceIn(-1.3f, 1.3f)
        if (first) { first = false; yaw.snapTo(y); pitch.snapTo(p) }
        else coroutineScope {
            launch { yaw.animateTo(y, tween(900, easing = FastOutSlowInEasing)) }
            launch { pitch.animateTo(p, tween(900, easing = FastOutSlowInEasing)) }
        }
    }
    val inf = if (d.connected) rememberInfiniteTransition(label = "globe") else null
    val dash = inf?.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "g-dash")
    val pulse = inf?.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing)), label = "g-pulse")
    var appeared by remember { mutableStateOf(false) }
    val arcRun = remember { Path() }
    val intro by animateFloatAsState(if (appeared) 1f else 0f, tween(550), label = "g-intro")
    val introS by animateFloatAsState(if (appeared) 1f else 0.96f, tween(550, easing = FastOutSlowInEasing), label = "g-intro-s")

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val sidePx = with(density) { side.toPx() }
        val c = sidePx / 2f
        val rad = sidePx / 2f * 0.86f
        val ui = (side.value / 330f).coerceIn(1f, 1.3f)
        val n = remember(sidePx) { sidePx.roundToInt().coerceIn(96, 640) }
        val bmps = remember(n) { Array(2) { android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888) } }
        val imgs = remember(n) { Array(2) { bmps[it].asImageBitmap() } }
        var frame by remember(n) { mutableStateOf<ImageBitmap?>(null) }
        var shownYaw by remember { mutableStateOf(Float.NaN) }
        var shownPitch by remember { mutableStateOf(0f) }
        var pop by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(n, dark) {
            val disc = withContext(Dispatchers.Default) { LandMask.get(ctx); Disc(n) }
            val mask = LandMask.get(ctx)
            val buf = IntArray(n * n)
            var front = 0
            snapshotFlow { Triple(yaw.value, pitch.value, oceanPct / 100f) }.conflate().collect { (y, p, oa) ->
                val back = 1 - front
                withContext(Dispatchers.Default) {
                    coroutineScope { (0 until CORES).forEach { part -> launch { renderGlobe(buf, disc, mask, y, p, pal, part, CORES, oa) } } }
                    bmps[back].setPixels(buf, 0, n, 0, 0, n, n)
                }
                front = back
                frame = imgs[back]
                shownYaw = y; shownPitch = p
                appeared = true
            }
        }
        fun proj(lat: Double, lon: Double): Triple<Float, Float, Float> {
            val yv = if (shownYaw.isNaN()) yaw.value else shownYaw
            val pv = if (shownYaw.isNaN()) pitch.value else shownPitch
            val v = WorldLogic.view(WorldLogic.sphere(lat, lon), yv.toDouble(), pv.toDouble())
            return Triple(c + rad * v[0].toFloat(), c - rad * v[1].toFloat(), v[2].toFloat())
        }

        Box(
            Modifier.size(side)
                .graphicsLayer { alpha = intro; scaleX = introS; scaleY = introS }
                .pointerInput(rad) {
                    detectDragGestures { ch, drag ->
                        ch.consume()
                        scope.launch {
                            yaw.snapTo(yaw.value - drag.x / rad)
                            pitch.snapTo((pitch.value + drag.y / rad).coerceIn(-1.3f, 1.3f))
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val rr = rad * 1.30f
                drawCircle(Brush.radialGradient(
                    0.735f to halo.copy(alpha = 0f), 0.775f to halo.copy(alpha = 0.26f), 0.84f to halo.copy(alpha = 0.14f),
                    0.91f to halo.copy(alpha = 0.05f), 1f to halo.copy(alpha = 0f), center = Offset(c, c), radius = rr), rr, Offset(c, c))
                frame?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(n, n), dstOffset = IntOffset.Zero,
                    dstSize = IntSize(sidePx.roundToInt(), sidePx.roundToInt()), filterQuality = FilterQuality.Low) }
            }
            Canvas(Modifier.fillMaxSize()) {
                val dp = 1.dp.toPx()
                for (cc in d.countries) {
                    if (d.connected && cc == d.hot && d.exit != null) continue
                    val g = GeoCountries.point(cc) ?: continue
                    val (x, y, z) = proj(g.lat, g.lon)
                    if (z > 0.02f) countryDot(Offset(x, y), cc == d.hot, accent, dp, (z / 0.2f).coerceIn(0f, 1f))
                }
                val p = grow.value
                if (arc != null && p > 0f) {
                    val yv = if (shownYaw.isNaN()) yaw.value else shownYaw
                    val pv = if (shownYaw.isNaN()) pitch.value else shownPitch
                    val pts = arc.map { WorldLogic.view(it, yv.toDouble(), pv.toDouble()) }
                    val vis = pts.map { v ->
                        val front = if (v[2] > 0.12) 1f else if (v[2] > 0) (v[2] / 0.12).toFloat() else 0f
                        val out = (sqrt(v[0] * v[0] + v[1] * v[1]) - 1.0)
                        val behindButOutside = if (v[2] <= 0) ((out / 0.04).coerceIn(0.0, 1.0) * 0.9).toFloat() else 0f
                        maxOf(front, behindButOutside)
                    }
                    val sx = pts.map { c + rad * it[0].toFloat() }; val sy = pts.map { c - rad * it[1].toFloat() }
                    val maxI = ((pts.size - 1) * p).toInt()
                    val ph = (dash?.value ?: 0f) * 10f * dp
                    // 1.0.49: same layers / widths / colours / dash speed as the map arc, drawn as continuous paths
                    // (runs of equal visibility) so dashes and glow flow exactly like on the map; back side fades out.
                    val lenAt = FloatArray(pts.size)
                    for (i in 1 until pts.size) lenAt[i] = lenAt[i - 1] + hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
                    fun q(i: Int) = (minOf(vis[i], vis[i + 1]) * 10f).roundToInt() / 10f
                    val g = if (dark) Color(0xFF39E6FF) else MapPrimary
                    var i0 = 0
                    while (i0 < maxI) {
                        val qa = q(i0)
                        var i1 = i0 + 1
                        while (i1 < maxI && q(i1) == qa) i1++
                        if (qa >= 0.05f) {
                            val a = qa * p
                            arcRun.rewind(); arcRun.moveTo(sx[i0], sy[i0])
                            for (k in i0 + 1..i1) arcRun.lineTo(sx[k], sy[k])
                            drawPath(arcRun, g.copy(alpha = (if (dark) 0.14f else 0.07f) * a), style = Stroke(16f * dp, cap = StrokeCap.Round))
                            drawPath(arcRun, g.copy(alpha = (if (dark) 0.30f else 0.14f) * a), style = Stroke(8f * dp, cap = StrokeCap.Round))
                            drawPath(arcRun, ShadowInk.copy(alpha = 0.55f * a), style = Stroke(3.6f * dp, cap = StrokeCap.Round))
                            drawPath(arcRun, Color.White.copy(alpha = 0.95f * a), style = Stroke(1.8f * dp, cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * dp, 4f * dp), lenAt[i0] - ph)))
                        }
                        i0 = i1
                    }
                    if (p >= 1f && dash != null) {
                        val f = dash.value * (pts.size - 1); val i = f.toInt().coerceIn(0, pts.size - 2); val fr = f - i
                        val a = minOf(vis[i], vis[i + 1])
                        if (a > 0.05f) {
                            val o = Offset(sx[i] + (sx[i + 1] - sx[i]) * fr, sy[i] + (sy[i + 1] - sy[i]) * fr)
                            drawCircle(live.copy(alpha = 0.22f * a), 7f * dp, o); drawCircle(Color.White.copy(alpha = a), 2.2f * dp, o)
                        }
                    }
                    d.user?.let { u -> val (x, y, z) = proj(u.lat, u.lon); if (z > 0.02f) userDot(Offset(x, y), userC, dp, (z / 0.2f).coerceIn(0f, 1f), dark) }
                }
                d.shown?.let { l ->
                    val (x, y, z) = proj(l.lat, l.lon)
                    if (z > 0f) {
                        val m = Offset(x, y)
                        if (d.connected) serverPulse(m, live, pulse?.value ?: 0f, dp, dark) else userDot(m, userC, dp, 1f, dark)
                        drawLine(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.75f)), startY = y - rad * 0.16f, endY = y),
                            Offset(x, y - rad * 0.16f), m, 2f)
                    }
                }
            }
            d.shown?.let { l ->
                Card(
                    Modifier.widthIn(max = (200f * ui).dp).onSizeChanged { pop = it }
                        .absoluteOffset {
                            val (x, y, _) = proj(l.lat, l.lon)
                            val pw = if (pop.width > 0) pop.width.toFloat() else 130f * density.density
                            val ph = if (pop.height > 0) pop.height.toFloat() else 58f * density.density
                            IntOffset((x - pw / 2f).roundToInt(), (y - ph - rad * 0.16f - sidePx * 0.01f).roundToInt())
                        }
                        .graphicsLayer { alpha = (proj(l.lat, l.lon).third / 0.25f).coerceIn(0f, 1f) * intro }
                        .clickable(onClick = onServers),
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.4.dp, popBorder),
                    colors = CardDefaults.cardColors(containerColor = popBg), elevation = CardDefaults.cardElevation(0.dp),
                ) { IpBubble(l, d.secured, d.connected, ui) }
            }
        }
    }
}

/** Fixed-data entry for host-side previews (no network, no permissions). */
@Composable
internal fun WorldSnapshot(user: GeoPoint?, exit: GeoPoint?, connected: Boolean, serverNames: List<String>, liveName: String, mode: String, modifier: Modifier = Modifier) {
    val shown = if (connected) exit else user
    val d = WorldData(user, exit, shown, connected, WorldLogic.secured(connected, exit, user), WorldLogic.serverCountries(serverNames), WorldLogic.liveCountry(liveName))
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // 1.0.50c: icon row sits just above the map's top border (bottom edge ICON_GAP above it)
        val iconsBottom = (maxHeight * (1f - MAP_FRACTION) - ICON_GAP).coerceAtLeast(PILL_H)
        if (mode == "map") Box(Modifier.fillMaxSize()) { MapView(d, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(MAP_FRACTION), {}) }
        else GlobeView(d, Modifier.fillMaxSize().padding(bottom = 8.dp).offset(y = GLOBE_DROP), {})
        ModePill(mode, false, {}, {}, {}, Modifier.align(Alignment.TopEnd).padding(top = iconsBottom - PILL_H))
        PingPill(WorldPing(ms = 84, stamp = System.currentTimeMillis()), {}, Modifier.align(Alignment.TopStart).padding(top = iconsBottom - PILL_H))
    }
}
