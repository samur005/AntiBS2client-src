package net.antibs.client.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** 2.0.11: Android TV support — same APK, TV mode is detected at runtime. */
object TvMode {
    fun isTv(ctx: Context): Boolean {
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = ctx.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.software.leanback_only")
    }
    fun isTvConfig(c: Configuration): Boolean = (c.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    /** UI scale on TV: designed for the 960×540 dp (1080p / 4K) canvas, slightly larger for viewing distance. */
    const val TV_SCALE_BASE = 1.05f
    fun scale(widthDp: Int, heightDp: Int): Float = (minOf(heightDp, widthDp) / 540f * TV_SCALE_BASE).coerceIn(0.9f, 1.4f)
    /** Single-column pages (Servers / Settings / Shop) on TV are centred at this width. */
    const val TV_COLUMN_DP = 720
}

/** Entry points for later features (kept empty for now). */
object TvHooks {
    /** «Принять с телефона» (LAN / QR transfer, upcoming). When non-null an extra tile appears in «Добавить серверы». */
    var receiveFromPhone: ((Context) -> Unit)? = null
}

val LocalIsTv = staticCompositionLocalOf { false }

/**
 * TV focus ring: one overlay for the whole app that follows the focused element (anything clickable /
 * toggleable / Material buttons) — style-aware accent border + soft glow, slightly inflated («pop»).
 * Draw-only, it never takes input.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusOverlay(accent: Color, content: @Composable () -> Unit) {
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rect by remember { mutableStateOf<Rect?>(null) }
    val shown by animateFloatAsState(if (rect != null) 1f else 0f, tween(160), label = "tv-focus")
    var last by remember { mutableStateOf(Rect.Zero) }
    rect?.let { last = it }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { root = it }
            .onFocusedBoundsChanged { c ->
                val r = root
                rect = if (c == null || r == null || !c.isAttached || !r.isAttached) null else runCatching { r.localBoundingBoxOf(c, false) }.getOrNull()
                    ?.takeIf { it.width > 4f && it.height > 4f && !(it.width >= (r.size.width - 2) && it.height >= (r.size.height - 2)) }
            }
            .drawWithContent {
                drawContent()
                if (shown <= 0f) return@drawWithContent
                val pop = TvFocus.popPx(this.density)
                val b = last.inflate(pop)
                val rad = CornerRadius(minOf(16.dp.toPx() + pop, b.height / 2f))
                for (k in 3 downTo 1) {
                    val g = 3.dp.toPx() * k
                    drawRoundRect(accent.copy(alpha = shown * 0.10f * (4 - k)), Offset(b.left - g, b.top - g), Size(b.width + 2 * g, b.height + 2 * g),
                        CornerRadius(rad.x + g), style = Stroke(3.dp.toPx()))
                }
                drawRoundRect(accent.copy(alpha = shown), b.topLeft, b.size, rad, style = Stroke(2.5.dp.toPx()))
            },
    ) { content() }
}

internal object TvFocus {
    fun popPx(density: Float) = 3f * density
    fun shouldShowRing(isTv: Boolean) = isTv
}
