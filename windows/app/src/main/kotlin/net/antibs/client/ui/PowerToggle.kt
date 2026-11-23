package net.antibs.client.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.antibs.client.engine.ConnState

private val TabularNums = TextStyle(fontFeatureSettings = "tnum", fontSize = 12.sp, lineHeight = 15.sp)

@Composable
internal fun PowerToggle(state: ConnState, since: Long, problem: Boolean = false, onClick: () -> Unit, haptics: Boolean = true, prefs: net.antibs.client.data.PowerButtonStyle = net.antibs.client.data.PowerButtonStyle()) {
    val style = LocalAppStyle.current
    val c = ssColors(style)
    val shape = ssShape(style, 16.dp)
    val on = UiLogic.powerSelectedOn(state)
    val busy = UiLogic.powerBusy(state)
    val connected = state == ConnState.CONNECTED
    val showError = problem || state == ConnState.ERROR
    val isTv = false
    val motionOk = PowerButtonLogic.motionOk()
    val animate = PowerButtonLogic.shouldAnimate(prefs, motionOk)
    val visible = true
    val live = PowerButtonLogic.shouldLive(connected, showError, prefs, motionOk, visible)
    val effect = PowerButtonLogic.effectOf(prefs)
    val accent = AntiBSColors.Blue
    val fillSolid = PowerButtonLogic.fillColor(prefs.color, accent)
    val onText = PowerButtonLogic.onTextColor(prefs.textColor, fillSolid)
    val tvFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var tvFocused by remember { mutableStateOf(false) }
    if (isTv) LaunchedEffect(Unit) { delay(150); runCatching { tvFocus.requestFocus() } }
    val tvScale by animateFloatAsState(if (tvFocused) 1.06f else 1f, if (animate) tween(160) else snap(), label = "tv-power")
    val motion = if (animate) spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) else snap<Float>()
    val colorTween = if (animate) tween<Float>(420) else snap<Float>()

    val pos by animateFloatAsState(if (on) 1f else 0f, animationSpec = motion, label = "seg")
    val thumb = when {
        showError -> UiLogic.PowerThumb.RED
        connected -> UiLogic.PowerThumb.GREEN
        state == ConnState.CONNECTING -> UiLogic.PowerThumb.ORANGE
        else -> UiLogic.PowerThumb.OFF
    }
    val errAmt by animateFloatAsState(if (thumb == UiLogic.PowerThumb.RED) 1f else 0f, animationSpec = colorTween, label = "err")
    val greenAmt by animateFloatAsState(if (thumb == UiLogic.PowerThumb.GREEN) 1f else 0f, animationSpec = colorTween, label = "green")
    val orangeAmt by animateFloatAsState(if (thumb == UiLogic.PowerThumb.ORANGE) 1f else 0f, animationSpec = colorTween, label = "orange")
    val wave: State<Float> = if (busy && animate) {
        rememberInfiniteTransition(label = "pwr-busy").animateFloat(
            0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pwr-wave",
        )
    } else remember { mutableFloatStateOf(1f) }
    val shimmer: State<Float> = if (busy && animate) {
        rememberInfiniteTransition(label = "pwr-shimmer").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "pwr-sh",
        )
    } else remember { mutableFloatStateOf(0f) }
    val livePulse: State<Float> = if (live) {
        val period = if (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_SHIMMER) 2400 else 1800
        rememberInfiniteTransition(label = "pwr-live").animateFloat(
            0f, 1f, infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pwr-pulse",
        )
    } else remember { mutableFloatStateOf(0.5f) }
    val liveFlow: State<Float> = if (live && effect == net.antibs.client.data.PowerButtonStyle.EFFECT_SHIMMER) {
        rememberInfiniteTransition(label = "pwr-flow").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pwr-flow",
        )
    } else remember { mutableFloatStateOf(0f) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected, since) {
        while (connected) {
            now = System.currentTimeMillis()
            delay(1000 - (now - since).mod(1000L).coerceIn(0, 999) + 5)
        }
    }
    val timer = UiLogic.powerTimerText(state, since, now)
    val desc = UiLogic.powerContentDescription(state, problem, timer)
    val muted = c.muted
    val onCard = c.onCard
    val greenBrush = PowerButtonLogic.fillBrush(prefs.color, accent)
    val orangeBrush = Brush.linearGradient(listOf(Color(0xFFFFC14D), Color(0xFFFF8A00)))
    val redBrush = Brush.linearGradient(listOf(Color(0xFFB4232F), AntiBSColors.Err, Color(0xFFFF6B5E)))
    val offPill = if (c.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val borderColor = androidx.compose.ui.graphics.lerp(c.cardBorder, AntiBSColors.Err, errAmt * 0.85f)

    fun fire() {
        if (busy) return
        onClick()
    }

    Column(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = tvScale; scaleY = tvScale },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.card)
                .border(1.dp, borderColor, shape)
                .then(if (isTv) Modifier.focusRequester(tvFocus).onFocusChanged { tvFocused = it.isFocused } else Modifier)
                .focusable()
                .pointerInput(on, busy) { detectTapGestures(onTap = { fire() }) }
                .pointerInput(on, busy) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            if (UiLogic.powerSwipeToggles(on, drag, 36.dp.toPx())) fire()
                        },
                        onHorizontalDrag = { change, dx -> change.consume(); drag += dx },
                    )
                }
                .semantics {
                    contentDescription = desc
                    role = Role.Switch
                }
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val trackShape = RoundedCornerShape(50)
            Box(
                Modifier.weight(1f).height(40.dp).clip(trackShape).background(c.chipOff),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(3.dp)) {
                    val padPx = 0f
                    val pillW = maxWidth / 2
                    if (live && (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_BREATH || effect == net.antibs.client.data.PowerButtonStyle.EFFECT_GLOW)) {
                        val glowA = if (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_GLOW)
                            0.22f + 0.28f * livePulse.value else 0.12f + 0.16f * livePulse.value
                        val spread = if (effect == net.antibs.client.data.PowerButtonStyle.EFFECT_GLOW)
                            1.06f + 0.05f * livePulse.value else 1.02f + 0.03f * livePulse.value
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .offset { IntOffset(((pillW.toPx() + padPx) * pos).roundToInt(), 0) }
                                .width(pillW)
                                .fillMaxHeight()
                                .graphicsLayer { alpha = glowA; scaleX = spread; scaleY = spread }
                                .background(fillSolid.copy(alpha = 0.85f), trackShape),
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(((pillW.toPx() + padPx) * pos).roundToInt(), 0) }
                            .width(pillW)
                            .fillMaxHeight()
                            .clip(trackShape)
                            .graphicsLayer { alpha = if (busy) 0.72f + 0.28f * wave.value else 1f }
                            .background(offPill),
                    ) {
                        if (orangeAmt > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = orangeAmt }.background(orangeBrush))
                        if (greenAmt > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = greenAmt }.background(greenBrush))
                        if (errAmt > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = errAmt }.background(redBrush))
                        if (busy && animate) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sp = shimmer.value
                                val cx = -0.3f * size.width + sp * 1.6f * size.width
                                val bw = size.width * 0.28f
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                                        start = androidx.compose.ui.geometry.Offset(cx - bw, 0f),
                                        end = androidx.compose.ui.geometry.Offset(cx + bw, size.height),
                                    ),
                                )
                            }
                        } else if (live && effect == net.antibs.client.data.PowerButtonStyle.EFFECT_SHIMMER) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sp = liveFlow.value
                                val cx = -0.35f * size.width + sp * 1.7f * size.width
                                val bw = size.width * 0.34f
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f + 0.10f * livePulse.value), Color.Transparent),
                                        start = androidx.compose.ui.geometry.Offset(cx - bw, 0f),
                                        end = androidx.compose.ui.geometry.Offset(cx + bw, size.height),
                                    ),
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            "Выкл",
                            color = when {
                                !on && showError -> Color.White
                                !on -> onCard
                                else -> muted
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = fixedSp(14f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            "Вкл",
                            color = when {
                                on && connected && !showError -> onText
                                on -> Color.White
                                else -> muted
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = fixedSp(14f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 72.dp)) {
                Text(
                    timer,
                    style = TabularNums.copy(
                        fontSize = fixedSp(16f),
                        lineHeight = fixedSp(18f),
                        fontWeight = FontWeight.SemiBold,
                        color = if (connected) onCard else muted,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    "сессия",
                    color = muted,
                    fontSize = fixedSp(10f),
                    lineHeight = fixedSp(12f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
