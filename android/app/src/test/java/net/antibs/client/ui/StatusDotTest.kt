package net.antibs.client.ui

import net.antibs.client.engine.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import net.antibs.client.ui.UiLogic.StatusDot
import net.antibs.client.ui.UiLogic.TunnelPing

/** 1.0.14 header status dot states; 1.0.19: while connected the dot follows the tunnel ping. */
class StatusDotTest {
    @Test fun statusDotFollowsConnectionStateWhenNotConnected() {
        TunnelPing.values().forEach { p ->
            assertEquals(StatusDot.CONNECTING, UiLogic.statusDot(ConnState.CONNECTING, p))
            assertEquals(StatusDot.CONNECTING, UiLogic.statusDot(ConnState.DISCONNECTING, p))
            assertEquals(StatusDot.ERROR, UiLogic.statusDot(ConnState.ERROR, p))
            assertEquals(StatusDot.OFF, UiLogic.statusDot(ConnState.DISCONNECTED, p))
        }
    }

    @Test fun connectedDotColorFollowsTunnelPing() {
        assertEquals(StatusDot.LIVE, UiLogic.statusDot(ConnState.CONNECTED, TunnelPing.OK))
        assertEquals(StatusDot.NO_PING, UiLogic.statusDot(ConnState.CONNECTED, TunnelPing.FAIL))
        assertEquals(StatusDot.CHECKING, UiLogic.statusDot(ConnState.CONNECTED, TunnelPing.CHECKING))
        assertEquals(StatusDot.CHECKING, UiLogic.statusDot(ConnState.CONNECTED, TunnelPing.UNKNOWN))
    }

    @Test fun tunnelPingValueForHostList() {
        assertEquals(123L, UiLogic.tunnelPingValue(123L))
        assertEquals(-1L, UiLogic.tunnelPingValue(null))
        assertEquals(-1L, UiLogic.tunnelPingValue(-1L))
        assertEquals(-1L, UiLogic.tunnelPingValue(0L))
    }

    @Test fun sessionKeyChangesPerSession() {
        assertNull(UiLogic.tunnelSessionKey(ConnState.CONNECTING, 5, "a"))
        assertNull(UiLogic.tunnelSessionKey(ConnState.DISCONNECTED, 0, ""))
        assertNull(UiLogic.tunnelSessionKey(ConnState.ERROR, 5, "a"))
        val a = UiLogic.tunnelSessionKey(ConnState.CONNECTED, 1000, "🇩🇪 DE")
        assertEquals(a, UiLogic.tunnelSessionKey(ConnState.CONNECTED, 1000, "🇩🇪 DE"))
        // host switch = new CONNECTED with a new start time / host
        assertNotEquals(a, UiLogic.tunnelSessionKey(ConnState.CONNECTED, 2000, "🇳🇱 NL"))
        assertNotEquals(a, UiLogic.tunnelSessionKey(ConnState.CONNECTED, 1000, "🇳🇱 NL"))
    }

    @Test fun tunnelPingMethodAndInterval() {
        assertEquals("HEAD", UiLogic.tunnelPingMethod("head"))
        assertEquals("GET", UiLogic.tunnelPingMethod("get"))
        assertEquals("GET", UiLogic.tunnelPingMethod("tcp"))
        assertEquals("GET", UiLogic.tunnelPingMethod("icmp"))
        assertEquals(30_000L, UiLogic.TUNNEL_PING_INTERVAL_MS)
    }
}
