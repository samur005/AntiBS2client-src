package net.antibs.client.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConnectWatchdogTest {
    @Test fun timeoutIsFiveSeconds() {
        assertEquals(5_000L, ConnectWatchdog.TIMEOUT_MS)
        assertEquals("Ошибка подключения", ConnectWatchdog.MESSAGE)
    }

    @Test fun firesWhenNotEstablishedInTime() {
        val fired = AtomicInteger()
        val wd = ConnectWatchdog(60) { fired.incrementAndGet() }.start()
        Thread.sleep(250)
        assertEquals(1, fired.get()); assertTrue(wd.timedOut)
        assertFalse(wd.established())
    }

    @Test fun doesNotFireAfterEstablished() {
        val fired = AtomicInteger()
        val wd = ConnectWatchdog(80) { fired.incrementAndGet() }.start()
        Thread.sleep(10)
        assertTrue(wd.established())
        Thread.sleep(250)
        assertEquals(0, fired.get()); assertFalse(wd.timedOut)
    }

    @Test fun cancelStopsTimerAndRefusesLateConnected() {
        val fired = AtomicInteger()
        val wd = ConnectWatchdog(60) { fired.incrementAndGet() }.start()
        wd.cancel()
        Thread.sleep(250)
        assertEquals(0, fired.get()); assertFalse(wd.established())
    }

    @Test fun exactlyOneWinnerUnderRace() {
        repeat(50) {
            val fired = AtomicInteger()
            val wd = ConnectWatchdog(5) { fired.incrementAndGet() }.start()
            Thread.sleep(4)
            val ok = wd.established()
            Thread.sleep(30)
            assertEquals(if (ok) 0 else 1, fired.get())
        }
    }
}
