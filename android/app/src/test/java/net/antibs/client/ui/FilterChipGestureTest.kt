package net.antibs.client.ui

import net.antibs.client.ui.FilterChipGesture.Event
import net.antibs.client.ui.FilterChipGesture.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterChipGestureTest {
    private val scrollSlop = 18f

    private fun press(): FilterChipGesture.State = FilterChipGesture.down(10f, 10f, 0L)

    private fun move(s: FilterChipGesture.State, x: Float, y: Float = 10f) =
        FilterChipGesture.move(s, x, y, scrollSlop)

    @Test fun shortTapIsClick() {
        val (n, ev) = FilterChipGesture.up(press())
        assertEquals(Event.Click, ev)
        assertEquals(Phase.IDLE, n.phase)
    }

    @Test fun moveBeyondScrollSlopBeforeArmIsRowScroll() {
        val (n, ev) = move(press(), 10f + scrollSlop + 1f)
        assertEquals(Event.BecomeScroll, ev)
        assertEquals(Phase.DEAD, n.phase)
        val (t, tev) = FilterChipGesture.tick(n, FilterChipGesture.LONG_PRESS_MS)
        assertEquals(Event.None, tev)
        assertEquals(Phase.DEAD, t.phase)
        assertEquals(Event.None, FilterChipGesture.up(t).second)
    }

    @Test fun smallWiggleBeforeArmDoesNotScrollOrClickUntilUp() {
        val (n, ev) = move(press(), 10f + scrollSlop - 1f)
        assertEquals(Event.None, ev)
        assertEquals(Phase.PRESSING, n.phase)
        assertEquals(Event.Click, FilterChipGesture.up(n).second)
    }

    @Test fun longPressAt450msOpensMenu() {
        assertEquals(Event.None, FilterChipGesture.tick(press(), 449L).second)
        val (n, ev) = FilterChipGesture.tick(press(), 450L)
        assertEquals(Event.OpenMenu, ev)
        assertEquals(Phase.MENU, n.phase)
        // release after the menu is already open is not a click and does not re-open
        assertEquals(Event.None, FilterChipGesture.up(n).second)
    }

    @Test fun movementAfterMenuIsOpenDoesNotCancelIt() {
        val open = FilterChipGesture.tick(press(), 450L).first
        val (n, ev) = move(open, 80f)
        assertEquals(Event.None, ev)
        assertEquals(Phase.MENU, n.phase)
        assertEquals(Event.None, FilterChipGesture.up(n).second)
    }

    @Test fun longPressThresholdIs450ms() {
        assertEquals(450L, FilterChipGesture.LONG_PRESS_MS)
    }
}
