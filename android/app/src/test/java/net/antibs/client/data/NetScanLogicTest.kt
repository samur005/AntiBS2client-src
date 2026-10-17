package net.antibs.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.41: Wi‑Fi scanner helpers. */
class NetScanLogicTest {
    @Test fun ranges() {
        val r = NetScanLogic.range("192.168.1.57", 24)!!
        assertEquals("192.168.1.0/24", r.cidr); assertEquals(254, r.count)
        assertEquals("192.168.1.1", NetScanLogic.intToIp(r.first)); assertEquals("192.168.1.254", NetScanLogic.intToIp(r.last))
        val big = NetScanLogic.range("10.20.30.40", 16)!!
        assertEquals("10.20.28.0/22", big.cidr); assertEquals(1022, big.count)
        assertEquals(1022, NetScanLogic.range("172.16.5.9", 22)!!.count)
        assertNull(NetScanLogic.range("x", 24))
        assertEquals(254, NetScanLogic.hosts(r).size)
    }

    @Test fun ptrRoundTrip() {
        assertEquals("4.3.2.1.in-addr.arpa", NetScanLogic.reverseName("1.2.3.4"))
        val q = NetScanLogic.dnsQuery(7, "4.3.2.1.in-addr.arpa")
        // fake answer: header with 1 question + 1 answer, compressed name pointer to the question
        val name = byteArrayOf(9) + "my-laptop".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        val resp = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } +
            byteArrayOf(0xC0.toByte(), 12, 0, 12, 0, 1, 0, 0, 0, 60, 0, name.size.toByte()) + name
        assertEquals("my-laptop", NetScanLogic.parsePtr(resp))
        assertNull(NetScanLogic.parsePtr(q))
    }

    @Test fun nbstat() {
        val q = NetScanLogic.nbstatQuery(1)
        assertEquals(50, q.size)
        val names = ByteArray(1 + 18 * 2)
        names[0] = 2
        fun put(i: Int, n: String, suffix: Int, group: Boolean) {
            val b = n.padEnd(15, ' ').toByteArray(Charsets.ISO_8859_1); System.arraycopy(b, 0, names, 1 + i * 18, 15)
            names[1 + i * 18 + 15] = suffix.toByte(); names[1 + i * 18 + 16] = if (group) 0x80.toByte() else 0
        }
        put(0, "WORKGROUP", 0, true); put(1, "DESKTOP-42", 0, false)
        val resp = ByteArray(12) + byteArrayOf(32) + ByteArray(32) { 'C'.code.toByte() } + byteArrayOf(0, 0, 0x21, 0, 1, 0, 0, 0, 0, 0, names.size.toByte()) + names
        assertEquals("DESKTOP-42", NetScanLogic.parseNbstat(resp))
    }

    @Test fun macs() {
        assertEquals("aa:bb:cc:00:11:22", NetScanLogic.normMac("AA-BB-CC-00-11-22"))
        assertNull(NetScanLogic.normMac("00:00:00:00:00:00"))
        assertTrue(NetScanLogic.isRandomMac("da:a1:19:00:00:01"))
        assertFalse(NetScanLogic.isRandomMac("00:1a:11:00:00:01"))
        assertEquals("001A11", NetScanLogic.ouiKey("00:1a:11:aa:bb:cc"))
        val arp = """IP address       HW type     Flags       HW address            Mask     Device
192.168.1.1      0x1         0x2         c8:3a:35:01:02:03     *        wlan0
192.168.1.9      0x1         0x0         00:00:00:00:00:00     *        wlan0"""
        assertEquals(mapOf("192.168.1.1" to "c8:3a:35:01:02:03"), NetScanLogic.parseNeighbours(arp))
        val neigh = "192.168.1.20 dev wlan0 lladdr 3c:22:fb:aa:bb:cc REACHABLE\n192.168.1.21 dev wlan0  FAILED"
        assertEquals(mapOf("192.168.1.20" to "3c:22:fb:aa:bb:cc"), NetScanLogic.parseNeighbours(neigh))
    }

    @Test fun kinds() {
        assertEquals("Роутер", NetScanLogic.guessKind(null, null, emptyList(), true))
        assertEquals("iPhone/iPad", NetScanLogic.guessKind(null, null, listOf(62078), false))
        assertEquals("Принтер", NetScanLogic.guessKind(null, null, listOf(9100), false))
    }
}
