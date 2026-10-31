package net.antibs.client.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex

/** 1.0.55: pure reorder helpers (unit tested). */
object SubOrder {
    /** Move [id] to [toIndex] in [ids]. */
    fun move(ids: List<String>, id: String, toIndex: Int): List<String> {
        val from = ids.indexOf(id); if (from < 0) return ids
        val m = ids.toMutableList(); m.removeAt(from); m.add(toIndex.coerceIn(0, m.size), id); return m
    }
    /** Stored order from a displayed order («Новые снизу» shows the stored list reversed). */
    fun storedFromDisplay(display: List<String>, newestFirst: Boolean): List<String> = if (newestFirst) display else display.reversed()
    /** Apply [order] to [items] (ids absent from order keep their relative position at the end). */
    fun <T> apply(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
        val pos = order.withIndex().associate { it.value to it.index }
        return items.withIndex().sortedWith(compareBy({ pos[id(it.value)] ?: (order.size + it.index) })).map { it.value }
    }
}

/** Horizontal long-press-drag reorder of a Row (ribbon / tiles). Only ids in [order] move; others are fixed. */
class HReorderState(initial: List<String>) {
    var order by mutableStateOf(initial)
    var dragId by mutableStateOf<String?>(null)
    var dx by mutableFloatStateOf(0f)
    val widths = mutableMapOf<String, Int>()
}

fun Modifier.hReorderItem(st: HReorderState, id: String, spacingPx: Float, haptic: HapticFeedback, onDone: (List<String>) -> Unit): Modifier =
    if (id !in st.order) this else this
        .onGloballyPositioned { st.widths[id] = it.size.width }
        .zIndex(if (st.dragId == id) 1f else 0f)
        .graphicsLayer {
            if (st.dragId == id) { translationX = st.dx; scaleX = 1.06f; scaleY = 1.06f; shadowElevation = 12f }
        }
        .pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); st.dragId = id; st.dx = 0f },
                onDragEnd = { val o = st.order; st.dragId = null; st.dx = 0f; onDone(o) },
                onDragCancel = { st.dragId = null; st.dx = 0f },
                onDrag = { ch, amt ->
                    ch.consume()
                    st.dx += amt.x
                    val i = st.order.indexOf(id)
                    if (st.dx > 0 && i < st.order.lastIndex) {
                        val w = (st.widths[st.order[i + 1]] ?: 0) + spacingPx
                        if (st.dx > w / 2) { st.order = SubOrder.move(st.order, id, i + 1); st.dx -= w }
                    } else if (st.dx < 0 && i > 0) {
                        val w = (st.widths[st.order[i - 1]] ?: 0) + spacingPx
                        if (-st.dx > w / 2) { st.order = SubOrder.move(st.order, id, i - 1); st.dx += w }
                    }
                },
            )
        }

/** Vertical long-press-drag of subscription cards inside a LazyColumn; items are keyed "sub-<id>". */
class VReorderState { var dragId by mutableStateOf<String?>(null); var anchor by mutableFloatStateOf(0f); var ty by mutableFloatStateOf(0f) }

fun Modifier.vReorderItem(st: VReorderState, list: LazyListState, id: String, haptic: HapticFeedback, onMove: (dragId: String, targetId: String) -> Unit): Modifier = this
    .zIndex(if (st.dragId == id) 1f else 0f)
    .graphicsLayer { if (st.dragId == id) { translationY = st.ty; scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 16f } }
    .pointerInput(id) {
        fun info() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "sub-$id" }
        detectDragGesturesAfterLongPress(
            onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); st.dragId = id; st.anchor = (info()?.offset ?: 0).toFloat(); st.ty = 0f },
            onDragEnd = { st.dragId = null; st.ty = 0f },
            onDragCancel = { st.dragId = null; st.ty = 0f },
            onDrag = { ch, amt ->
                ch.consume()
                st.anchor += amt.y
                val me = info() ?: return@detectDragGesturesAfterLongPress
                st.ty = st.anchor - me.offset
                val center = st.anchor + me.size / 2f
                val target = list.layoutInfo.visibleItemsInfo.firstOrNull { o ->
                    val k = o.key as? String ?: return@firstOrNull false
                    k.startsWith("sub-") && k != "sub-$id" && k != "sub-orphans" && center > o.offset && center < o.offset + o.size &&
                        // only swap when the centre passes the target's middle
                        (if (o.offset > me.offset) center > o.offset + o.size / 2f else center < o.offset + o.size / 2f)
                }
                if (target != null) onMove(id, (target.key as String).removePrefix("sub-"))
            },
        )
    }
