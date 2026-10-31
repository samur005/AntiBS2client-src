package net.antibs.client.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.0.53: connect timeout (5 s). Started when a connect attempt begins; exactly one of
 * [established] (the attempt finished in time) / timeout / [cancel] wins. On timeout [onTimeout] runs once on the
 * watchdog thread; the caller stops the started cores and reports [MESSAGE]. A late «connected» after the timeout
 * gets `false` from [established] and must be torn down instead of being reported.
 */
class ConnectWatchdog(private val timeoutMs: Long = TIMEOUT_MS, private val onTimeout: () -> Unit) {
    private val state = AtomicInteger(PENDING)
    private var thread: Thread? = null

    val timedOut: Boolean get() = state.get() == TIMED_OUT
    val pending: Boolean get() = state.get() == PENDING

    fun start(): ConnectWatchdog {
        thread = Thread({
            try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) { return@Thread }
            if (state.compareAndSet(PENDING, TIMED_OUT)) onTimeout()
        }, "connect-watchdog").apply { isDaemon = true; start() }
        return this
    }

    /** The connection is up. `true` = report it; `false` = the timeout (or a cancel) already won. */
    fun established(): Boolean = state.compareAndSet(PENDING, DONE).also { if (it) thread?.interrupt() }

    /** User cancel / new attempt / disconnect: stop the timer silently. */
    fun cancel() { if (state.compareAndSet(PENDING, CANCELLED)) thread?.interrupt() }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val MESSAGE = "Ошибка подключения"
        private const val PENDING = 0
        private const val DONE = 1
        private const val TIMED_OUT = 2
        private const val CANCELLED = 3
    }
}
