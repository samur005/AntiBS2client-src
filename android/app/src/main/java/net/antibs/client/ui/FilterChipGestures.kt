package net.antibs.client.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pointer handler that feeds [FilterChipGesture]. Does not consume events unless the long-press
 * has already opened the menu, so a pre-arm move still scrolls the parent row.
 *
 * [onClick] / [onOpenMenu] are read through [rememberUpdatedState] so a `pointerInput` that stays
 * keyed only on the chip id still invokes the latest callback (2.0.40: selecting IPv4 after
 * another tab was broken because the handler kept a stale `activeTab` snapshot).
 */
fun Modifier.filterChipGestures(
    key: Any,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
): Modifier = composed {
    val latestClick = rememberUpdatedState(onClick)
    val latestOpen = rememberUpdatedState(onOpenMenu)
    pointerInput(key) {
        val scrollSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var st = FilterChipGesture.down(down.position.x, down.position.y, down.uptimeMillis)
            val first = withTimeoutOrNull(FilterChipGesture.LONG_PRESS_MS) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "lost"
                    if (!ch.pressed) return@withTimeoutOrNull "up"
                    val (n, evnt) = FilterChipGesture.move(st, ch.position.x, ch.position.y, scrollSlop)
                    st = n
                    if (evnt == FilterChipGesture.Event.BecomeScroll) return@withTimeoutOrNull "scroll"
                }
            }
            when (first) {
                "up" -> {
                    if (FilterChipGesture.up(st).second == FilterChipGesture.Event.Click) latestClick.value()
                    return@awaitEachGesture
                }
                "scroll", "lost" -> return@awaitEachGesture
                null -> {
                    val still = currentEvent.changes.any { it.id == down.id && it.pressed }
                    if (!still) {
                        if (FilterChipGesture.up(st).second == FilterChipGesture.Event.Click) latestClick.value()
                        return@awaitEachGesture
                    }
                    val (n, evnt) = FilterChipGesture.tick(st, st.downUptimeMs + FilterChipGesture.LONG_PRESS_MS)
                    st = n
                    if (evnt == FilterChipGesture.Event.OpenMenu) latestOpen.value()
                }
            }
            var tracking = st.phase == FilterChipGesture.Phase.MENU
            while (tracking) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null || !ch.pressed) tracking = false
                else ch.consume()
            }
        }
    }
}
