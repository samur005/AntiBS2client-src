package net.antibs.client.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import net.antibs.client.data.Holiday
import net.antibs.client.data.HolidayStyle
import net.antibs.client.data.SeasonalFx

/** True when the system animator/transition scale is 0 (reduced motion). */
internal fun systemReduceMotion(ctx: android.content.Context): Boolean = runCatching {
    val r = ctx.contentResolver
    Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        Settings.Global.getFloat(r, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/**
 * 2.0.34: compact holiday chip for the Home list header (icon + name on a shimmering holiday gradient).
 * Renders nothing useful if [holiday] is null — callers should skip the slot entirely.
 */
@Composable
internal fun SeasonalHolidayStrip(
    holiday: Holiday,
    fx: SeasonalFx = SeasonalFx(),
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val pal = HolidayStyle.palette(holiday.id)
    val colors = pal.colors.map { Color(it.toInt()) }
    val textColor = if (fx.labelColor != 0L) Color(fx.labelColor.toInt()) else Color(pal.text.toInt())
    val reduce = !animate || systemReduceMotion(LocalContext.current)
    val motion = rememberInfiniteTransition(label = "holiday-motion")
    val shiftAnim by motion.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "shift",
    )
    val pulseAnim by motion.animateFloat(
        initialValue = 0.96f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse",
    )
    val bulbsAnim by motion.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "bulbs",
    )
    val shift = if (reduce) 0f else shiftAnim
    val pulse = if (reduce) 1f else pulseAnim
    val blink = if (reduce) 1f else bulbsAnim
    val shape = RoundedCornerShape(16.dp)
    val fs = (13f * fx.labelSize / 100f).coerceIn(11f, 18f).sp
    Box(
        modifier
            .height(32.dp)
            .widthIn(min = 120.dp)
            .clip(shape)
            .graphicsLayer { alpha = (fx.labelAlpha / 100f).coerceIn(0.2f, 1f) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val travel = w * 1.6f
            val x0 = if (reduce) -w * 0.15f else -travel * 0.25f + shift * travel
            drawRect(
                brush = Brush.linearGradient(
                    colors = colors,
                    start = Offset(x0, 0f),
                    end = Offset(x0 + w, size.height),
                    tileMode = TileMode.Mirror,
                ),
            )
            // soft highlight sheen
            val hx = if (reduce) w * 0.35f else (shift * (w + 80f) - 40f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                    start = Offset(hx - 36f, 0f),
                    end = Offset(hx + 36f, size.height),
                ),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                holiday.emoji,
                fontSize = (16f * fx.labelSize / 100f).coerceIn(14f, 22f).sp,
                modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse },
                maxLines = 1,
            )
            Text(
                "  ${holiday.name}",
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = fs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (holiday.id == "ny") {
            val d = LocalDensity.current.density
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp)) {
                val bulbs = listOf(Color(0xFFFF4D4D), Color(0xFFFFC145), Color(0xFF4BE08A), Color(0xFF4DA3FF))
                val n = 7
                val y = size.height * 0.45f
                for (i in 0 until n) {
                    val x = size.width * (0.12f + 0.76f * i / (n - 1).toFloat())
                    val a = if (reduce) 0.95f else (0.4f + 0.6f * ((sin((blink * 6.28f) + i * 1.3f) + 1f) / 2f))
                    val c = bulbs[i % bulbs.size]
                    drawCircle(c.copy(alpha = 0.28f * a), 3.2f * d, Offset(x, y))
                    drawCircle(c.copy(alpha = a), 1.5f * d, Offset(x, y))
                }
            }
        }
    }
}
