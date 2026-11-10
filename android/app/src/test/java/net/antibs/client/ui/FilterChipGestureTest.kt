package net.antibs.client.ui

import net.antibs.client.ui.FilterChipGesture.Event
import net.antibs.client.ui.FilterChipGesture.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterChipGestureTest {
    private val scrollSlop = 18f
    private val dragSlop = 12f

    private fun press(): FilterChipGesture.State = FilterChipGesture.down(10f, 10f, 0L)

    private fun move(s: FilterChipGesture.State, x: Float, y: Float = 10f, allowDrag: Boolean = true) =
        FilterChipGesture.move(s, x, y, scrollSlop, dragSlop, allowDrag)

    @Test fun shortTapIsClick() {
        var s = press()
        val (n, ev) = FilterChipGesture.up(s)
        assertEquals(Event.Click, ev)
        assertEquals(Phase.IDLE, n.phase)
    }

    @Test fun moveBeyondScrollSlopBeforeArmIsRowScroll() {
        var s = press()
        val (n, ev) = move(s, 10f + scrollSlop + 1f)
        assertEquals(Event.BecomeScroll, ev)
        assertEquals(Phase.DEAD, n.phase)
        // later timeout must not arm, release must not open the menu
        val (t, tev) = FilterChipGesture.tick(n, FilterChipGesture.LONG_PRESS_MS)
        assertEquals(Event.None, tev)
        assertEquals(Phase.DEAD, t.phase)
        assertEquals(Event.None, FilterChipGesture.up(t).second)
    }

    @Test fun smallWiggleBeforeArmDoesNotScrollOrClickUntilUp() {
        var s = press()
        val (n, ev) = move(s, 10f + scrollSlop - 1f)
        assertEquals(Event.None, ev)
        assertEquals(Phase.PRESSING, n.phase)
        assertEquals(Event.Click, FilterChipGesture.up(n).second)
    }

    @Test fun armsAt450msWithNoSlop() {
        var s = press()
        assertEquals(Event.None, FilterChipGesture.tick(s, 449L).second)
        val (n, ev) = FilterChipGesture.tick(s, 450L)
        assertEquals(Event.Armed, ev)
        assertEquals(Phase.ARMED, n.phase)
    }

    @Test fun releaseAfterArmOpensMenu() {
        val armed = FilterChipGesture.tick(press(), 450L).first
        assertEquals(Event.OpenMenu, FilterChipGesture.up(armed).second)
    }

    @Test fun horizontalMoveAfterArmStartsDragAndCancelsMenu() {
        var s = FilterChipGesture.tick(press(), 450L).first
        val (n, ev) = move(s, 10f + dragSlop + 1f)
        assertEquals(Event.DragStart, ev)
        assertEquals(Phase.DRAGGING, n.phase)
        val (m, mev) = move(n, 40f)
        assertEquals(Event.DragMove, mev)
        assertEquals(Event.DragEnd, FilterChipGesture.up(m).second)
        // a finished drag does not also open the menu
        assertEquals(Event.None, FilterChipGesture.up(m).let { FilterChipGesture.up(it.first).second })
    }

    @Test fun verticalMoveAfterArmDoesNotStartDrag() {
        var s = FilterChipGesture.tick(press(), 450L).first
        val (n, ev) = move(s, 10f, 10f + 24f)
        assertEquals(Event.None, ev)
        assertEquals(Phase.ARMED, n.phase)
        assertEquals(Event.OpenMenu, FilterChipGesture.up(n).second)
    }

    @Test fun pinnedChipCannotStartDragSoReleaseStillOpensMenu() {
        var s = FilterChipGesture.tick(press(), 450L).first
        val (n, ev) = move(s, 10f + 40f, allowDrag = false)
        assertEquals(Event.None, ev)
        assertEquals(Phase.ARMED, n.phase)
        assertEquals(Event.OpenMenu, FilterChipGesture.up(n).second)
    }

    @Test fun longPressThresholdIs450ms() {
        assertEquals(450L, FilterChipGesture.LONG_PRESS_MS)
        assertEquals(12f, FilterChipGesture.DRAG_SLOP_DP)
    }

    @Test fun targetIndexUsesStartMidpoints() {
        val order = listOf("a", "b", "c")
        val x = mapOf("a" to 0, "b" to 40, "c" to 80)
        val w = mapOf("a" to 40, "b" to 40, "c" to 40)
        assertEquals(0, targetIndex(10f, order, x, w))
        assertEquals(1, targetIndex(50f, order, x, w))
        assertEquals(2, targetIndex(100f, order, x, w))
        assertEquals(2, targetIndex(200f, order, x, w))
    }
}
