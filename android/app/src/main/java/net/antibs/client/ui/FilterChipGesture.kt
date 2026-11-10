package net.antibs.client.ui

import kotlin.math.hypot

/**
 * 2.0.38: tap vs row-scroll vs long-press menu on a Home filter chip.
 *
 * - A short press with no slop is a click (toggle the chip).
 * - Movement beyond [scrollSlopPx] *before* the long-press fires is a normal row scroll (no menu).
 * - At [LONG_PRESS_MS] (~450 ms) the menu opens (caller plays a haptic). There is no drag-to-reorder.
 */
object FilterChipGesture {
    const val LONG_PRESS_MS = 450L

    enum class Phase { IDLE, PRESSING, MENU, DEAD }

    enum class Event { None, Click, OpenMenu, BecomeScroll }

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

    fun move(s: State, x: Float, y: Float, scrollSlopPx: Float): Pair<State, Event> {
        val n = s.copy(x = x, y = y)
        return when (s.phase) {
            Phase.PRESSING ->
                if (n.distance > scrollSlopPx) n.copy(phase = Phase.DEAD) to Event.BecomeScroll
                else n to Event.None
            else -> n.copy(phase = s.phase) to Event.None
        }
    }

    fun tick(s: State, uptimeMs: Long): Pair<State, Event> {
        if (s.phase == Phase.PRESSING && uptimeMs - s.downUptimeMs >= LONG_PRESS_MS) {
            return s.copy(phase = Phase.MENU) to Event.OpenMenu
        }
        return s to Event.None
    }

    fun up(s: State): Pair<State, Event> {
        val ev = if (s.phase == Phase.PRESSING) Event.Click else Event.None
        return State() to ev
    }

    fun cancel(s: State): Pair<State, Event> = State() to Event.None
}
