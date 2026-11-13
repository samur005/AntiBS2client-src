package net.antibs.client.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 2.2.0: «direct» requests (Session IP, speed test, «Прямой» ping, geo) with our VPN off use the system route. */
class SpeedNetPathTest {
    @Test fun ourVpnUpBindsToPhysical() {
        assertEquals(SpeedNet.Path.BOUND_PHYSICAL, SpeedNet.path(ownVpnUp = true, activeIsVpn = false))
        assertEquals(SpeedNet.Path.BOUND_PHYSICAL, SpeedNet.path(ownVpnUp = true, activeIsVpn = true))
    }

    @Test fun ourVpnOffForeignVpnUsesItsRoute() {
        assertEquals(SpeedNet.Path.FOREIGN_VPN, SpeedNet.path(ownVpnUp = false, activeIsVpn = true))
    }

    @Test fun ourVpnOffNoVpnUsesDefault() {
        assertEquals(SpeedNet.Path.SYSTEM_DEFAULT, SpeedNet.path(ownVpnUp = false, activeIsVpn = false))
    }

    @Test fun disconnectedStateIsNotOwnVpn() {
        // default VpnState in a fresh JVM is DISCONNECTED
        if (VpnState.status.value.state == ConnState.DISCONNECTED) assertFalse(SpeedNet.ownVpnUp())
    }
}
