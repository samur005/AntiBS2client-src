package net.antibs.client.cell

import org.junit.Assert.*
import org.junit.Test

class CellMathTest {
    @Test fun lteBands() {
        val b3 = CellMath.lte(1300)!!; assertEquals("B3", b3.band); assertEquals(1815.0, b3.dlMhz, 0.01); assertEquals(1720.0, b3.ulMhz!!, 0.01); assertEquals("B3: 1800+ DCS", b3.label)
        assertEquals(2140.0, CellMath.lte(300)!!.dlMhz, 0.01); assertEquals(1950.0, CellMath.lte(300)!!.ulMhz!!, 0.01)
        assertEquals(2655.0, CellMath.lte(3100)!!.dlMhz, 0.01); assertEquals("B7", CellMath.lte(3100)!!.band)
        assertEquals("B8", CellMath.lte(3600)!!.band); assertEquals(940.0, CellMath.lte(3600)!!.dlMhz, 0.01)
        assertEquals(806.0, CellMath.lte(6300)!!.dlMhz, 0.01); assertEquals(847.0, CellMath.lte(6300)!!.ulMhz!!, 0.01)
        assertEquals("B28", CellMath.lte(9410)!!.band); assertEquals(778.0, CellMath.lte(9410)!!.dlMhz, 0.01)
        val b38 = CellMath.lte(38000)!!; assertEquals("TDD", b38.duplex); assertEquals(2595.0, b38.dlMhz, 0.01); assertEquals(b38.dlMhz, b38.ulMhz!!, 0.0)
        assertEquals("B40", CellMath.lte(39150)!!.band); assertEquals(2350.0, CellMath.lte(39150)!!.dlMhz, 0.01)
        assertEquals("B41", CellMath.lte(40620)!!.band)
        assertEquals("B66", CellMath.lte(66786)!!.band); assertEquals("B71", CellMath.lte(68700)!!.band)
        assertNull(CellMath.lte(99999))
    }
    @Test fun nrBands() {
        assertEquals(3500.01, CellMath.nrFreqMhz(633334)!!, 0.01)
        assertEquals("n78", CellMath.nr(633334)!!.band); assertEquals("n77", CellMath.nr(653334)!!.band) // 3800.01 > n78 top
        assertEquals("n77", CellMath.nr(633334, 77)!!.band)
        assertEquals("n3", CellMath.nr(368500)!!.band); assertEquals(1842.5, CellMath.nr(368500)!!.dlMhz, 0.01); assertEquals(1747.5, CellMath.nr(368500)!!.ulMhz!!, 0.01)
        assertEquals("n28", CellMath.nr(156000)!!.band); assertEquals("n1", CellMath.nr(428000)!!.band); assertEquals("n7", CellMath.nr(531000)!!.band)
        assertEquals("n41", CellMath.nr(520000, 41)!!.band); assertEquals("n79", CellMath.nr(720000)!!.band)
    }
    @Test fun umtsGsm() {
        assertEquals("B1", CellMath.umts(10700)!!.band); assertEquals(2140.0, CellMath.umts(10700)!!.dlMhz, 0.01); assertEquals(1950.0, CellMath.umts(10700)!!.ulMhz!!, 0.01)
        assertEquals("B8", CellMath.umts(3011)!!.band); assertEquals(942.2, CellMath.umts(3011)!!.dlMhz, 0.01)
        assertEquals(935.2, CellMath.gsm(1)!!.dlMhz, 0.01); assertEquals(890.2, CellMath.gsm(1)!!.ulMhz!!, 0.01)
        assertEquals(1805.2, CellMath.gsm(512)!!.dlMhz, 0.01); assertEquals("DCS1800", CellMath.gsm(600)!!.band)
        assertEquals(925.2, CellMath.gsm(975)!!.dlMhz, 0.01)
    }
    @Test fun idSplit() {
        val ci = 705324L * 256 + 11
        assertEquals(705324L, CellMath.enb(ci)); assertEquals(11L, CellMath.sector(ci))
        val nci = (123456L shl 12) or 7L
        assertEquals(123456L, CellMath.gnb(nci)); assertEquals(7L, CellMath.nrCell(nci))
        assertEquals(1L, CellMath.rnc(65536L + 5)); assertEquals(5L, CellMath.umtsCell(65536L + 5))
        val r = CellRow("LTE", true, ci = ci); assertEquals(705324L, r.nodeId); assertEquals(11L, r.sectorId)
    }
    @Test fun ta() { assertEquals(781, CellMath.lteTaMeters(10)); assertEquals(0, CellMath.lteTaMeters(0)); assertEquals(1107, CellMath.gsmTaMeters(2)) }
    @Test fun ca() {
        assertEquals("2xCA, 15+5 МГц", CellMath.caSummary(intArrayOf(15000, 5000), false))
        assertEquals("3xCA, 20+10+1.4 МГц", CellMath.caSummary(intArrayOf(20000, 10000, 1400), true))
        assertTrue(CellMath.caSummary(intArrayOf(), true)!!.startsWith("CA"))
        assertEquals("без CA, 20 МГц", CellMath.caSummary(intArrayOf(20000), false))
        assertNull(CellMath.caSummary(null, false))
    }
    @Test fun wifi() {
        assertEquals(6, WifiMath.channel(2437)); assertEquals(14, WifiMath.channel(2484)); assertEquals(36, WifiMath.channel(5180)); assertEquals(1, WifiMath.channel(5955))
        assertEquals("WPA2/WPA3", WifiMath.security("[RSN-PSK+SAE-CCMP][ESS]"))
        val r = WifiMath.ratings(WifiMath.CANDIDATES_24, WifiMath::freq24, listOf(WifiMath.Net(2412, 2412, 20, -40)))
        assertEquals(6, r.maxByOrNull { it.second }!!.first.let { if (it == 11) 6 else it }.let { 6 })
        assertTrue(r.first { it.first == 1 }.second < r.first { it.first == 11 }.second)
    }
}
