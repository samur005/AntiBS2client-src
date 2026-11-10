package net.antibs.client.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pointer handler that feeds [FilterChipGesture]. Does not consume events unless a drag has started,
 * so a pre-arm move still scrolls the parent row.
 */
fun Modifier.filterChipGestures(
    key: Any,
    reorderable: Boolean = true,
    onClick: () -> Unit,
    onArmed: () -> Unit,
    onOpenMenu: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
): Modifier = pointerInput(key, reorderable) {
    val scrollSlop = viewConfiguration.touchSlop
    val dragSlop = FilterChipGesture.DRAG_SLOP_DP.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var st = FilterChipGesture.down(down.position.x, down.position.y, down.uptimeMillis)
        val first = withTimeoutOrNull(FilterChipGesture.LONG_PRESS_MS) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "lost"
                if (!ch.pressed) return@withTimeoutOrNull "up"
                val (n, evnt) = FilterChipGesture.move(st, ch.position.x, ch.position.y, scrollSlop, dragSlop, reorderable)
                st = n
                if (evnt == FilterChipGesture.Event.BecomeScroll) return@withTimeoutOrNull "scroll"
            }
        }
        when (first) {
            "up" -> {
                val ev = FilterChipGesture.up(st).second
                if (ev == FilterChipGesture.Event.Click) onClick()
                return@awaitEachGesture
            }
            "scroll", "lost" -> return@awaitEachGesture
            null -> {
                val still = currentEvent.changes.any { it.id == down.id && it.pressed }
                if (!still) {
                    if (FilterChipGesture.up(st).second == FilterChipGesture.Event.Click) onClick()
                    return@awaitEachGesture
                }
                val (n, evnt) = FilterChipGesture.tick(st, st.downUptimeMs + FilterChipGesture.LONG_PRESS_MS)
                st = n
                if (evnt == FilterChipGesture.Event.Armed) onArmed()
            }
        }
        var tracking = true
        while (tracking) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id }
            if (ch == null) {
                val evnt = FilterChipGesture.cancel(st).second
                if (evnt == FilterChipGesture.Event.DragEnd) onDragEnd()
                tracking = false
            } else if (!ch.pressed) {
                val evnt = FilterChipGesture.up(st).second
                when (evnt) {
                    FilterChipGesture.Event.OpenMenu -> onOpenMenu()
                    FilterChipGesture.Event.DragEnd -> onDragEnd()
                    else -> {}
                }
                tracking = false
            } else {
                val (n, evnt) = FilterChipGesture.move(st, ch.position.x, ch.position.y, scrollSlop, dragSlop, reorderable)
                st = n
                when (evnt) {
                    FilterChipGesture.Event.DragStart -> {
                        ch.consume()
                        onDragStart()
                        onDrag(n.dx, n.dy)
                    }
                    FilterChipGesture.Event.DragMove -> {
                        ch.consume()
                        onDrag(n.dx, n.dy)
                    }
                    else -> {}
                }
            }
        }
    }
}
