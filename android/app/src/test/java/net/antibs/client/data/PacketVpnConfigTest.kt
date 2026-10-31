package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketVpnConfigTest {
    @Test fun sstpProfile() {
        val p = PacketVpnConfig.sstp("", "sstp://vpn.example.com:8443/", 443, "u", "p", false, " sni.example ")
        assertEquals("sstp", p.protocol); assertEquals("vpn.example.com", p.server); assertEquals(8443, p.port)
        assertEquals("0", p.olc!!["verify"]); assertEquals("sni.example", p.olc!!["sni"])
        assertTrue(ProtocolSupport.isRunnable("sstp")); assertTrue(ProtocolSupport.isRunnable("zerotier"))
    }

    @Test fun zerotierId() {
        assertTrue(PacketVpnConfig.isNetworkId("8056C2E21C000001"))
        assertFalse(PacketVpnConfig.isNetworkId("8056c2e21c00000"))
        assertEquals("8056c2e21c000001", PacketVpnConfig.zerotier("", "8056C2E21C000001").server)
    }
}
