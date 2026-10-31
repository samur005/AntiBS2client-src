package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetScanJudgeTest {
    @Test fun tunnelledSubnetIsFake() {
        val alive = (2..254).map { NetScanLogic.Probe("192.168.1.$it", 40 + it % 3, "TCP", listOf(80, 443)) }
        val v = NetScanLogic.judge(alive, 253, setOf("192.168.1.1"))
        assertEquals(253, v.fake.size); assertTrue(v.unreliable)
    }
    @Test fun normalLanIsFine() {
        val alive = listOf(NetScanLogic.Probe("192.168.1.1", 3, "TCP", listOf(80)), NetScanLogic.Probe("192.168.1.10", 12, "ICMP", emptyList()))
        val v = NetScanLogic.judge(alive, 253)
        assertTrue(v.fake.isEmpty()); assertFalse(v.unreliable)
    }
}
