package net.antibs.client.ui

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 2.0.37: distinguishes a tap, a row-scroll, a long-press menu, and a drag-to-reorder
 * so those gestures don't false-trigger each other.
 *
 * - Long-press arms at [LONG_PRESS_MS] (~450 ms) and should play a haptic.
 * - Movement beyond [scrollSlopPx] *before* arming is a normal row scroll (no menu, no drag).
 * - After arming, horizontal movement beyond [dragSlopPx] (~12 dp) starts a drag and cancels the menu.
 * - Release while armed, without that drag movement, opens the Пинг/Удалить menu.
 * - A short press with no slop is a click (toggle the chip).
 */
object FilterChipGesture {
    const val LONG_PRESS_MS = 450L
    const val DRAG_SLOP_DP = 12f

    enum class Phase { IDLE, PRESSING, ARMED, DRAGGING, DEAD }

    enum class Event { None, Click, Armed, OpenMenu, DragStart, DragMove, DragEnd, BecomeScroll }

    data class State(
        val phase: Phase = Phase.IDLE,
        val downX: Float = 0f,
        val downY: Float = 0f,
        val x: Float = 0f,
        val y: Float = 0f,
        val downUptimeMs: Long = 0L,
    ) {
        val dx: Float get() = x - downX
        val dy: Float get() = y - downY
        val distance: Float get() = hypot(dx, dy)
    }

    fun down(x: Float, y: Float, uptimeMs: Long): State =
        State(Phase.PRESSING, downX = x, downY = y, x = x, y = y, downUptimeMs = uptimeMs)

    /**
     * @param allowDrag false for a pinned chip («Все страны»): after arming, movement never starts a drag,
     *        so a release still opens the menu.
     */
    fun move(
        s: State,
        x: Float,
        y: Float,
        scrollSlopPx: Float,
        dragSlopPx: Float,
        allowDrag: Boolean = true,
    ): Pair<State, Event> {
        val n = s.copy(x = x, y = y)
        return when (s.phase) {
            Phase.IDLE, Phase.DEAD -> n.copy(phase = s.phase) to Event.None
            Phase.PRESSING ->
                if (n.distance > scrollSlopPx) n.copy(phase = Phase.DEAD) to Event.BecomeScroll
                else n to Event.None
            Phase.ARMED ->
                if (allowDrag && abs(n.dx) > dragSlopPx) n.copy(phase = Phase.DRAGGING) to Event.DragStart
                else n to Event.None
            Phase.DRAGGING -> n to Event.DragMove
        }
    }

    fun tick(s: State, uptimeMs: Long): Pair<State, Event> {
        if (s.phase == Phase.PRESSING && uptimeMs - s.downUptimeMs >= LONG_PRESS_MS) {
            return s.copy(phase = Phase.ARMED) to Event.Armed
        }
        return s to Event.None
    }

    fun up(s: State): Pair<State, Event> {
        val ev = when (s.phase) {
            Phase.PRESSING -> Event.Click
            Phase.ARMED -> Event.OpenMenu
            Phase.DRAGGING -> Event.DragEnd
            else -> Event.None
        }
        return State() to ev
    }

    fun cancel(s: State): Pair<State, Event> {
        val ev = if (s.phase == Phase.DRAGGING) Event.DragEnd else Event.None
        return State() to ev
    }
}
