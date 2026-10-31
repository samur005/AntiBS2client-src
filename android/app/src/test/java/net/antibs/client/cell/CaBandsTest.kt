package net.antibs.client.cell

import org.junit.Assert.*
import org.junit.Test

class CaBandsTest {
    @Test fun formatKnownBands() {
        val c = CellMath.carriers(listOf(15000, 5000), "B3", emptyList(), listOf("B3", "B7"))
        assertEquals("2xCA: B3+B7?, 15+5 МГц", CellMath.caBandsSummary(c, true))
    }
    @Test fun unknownScc() {
        val c = CellMath.carriers(listOf(15000, 5000), "B3", emptyList(), listOf("B3"))
        assertEquals("2xCA: B3+?, 15+5 МГц", CellMath.caBandsSummary(c, true))
    }
    @Test fun fromPhys() {
        val ph = listOf(CellMath.Phys(false, 3100, null, 5000, 12, false), CellMath.Phys(false, 1724, 3, 15000, 301, true), CellMath.Phys(true, 633334, null, 100000, 5, false))
        assertEquals("3xCA: B3+B7+n78, 15+5+100 МГц", CellMath.caBandsSummary(CellMath.carriers(listOf(15000), "B3", ph, emptyList()), true))
    }
    @Test fun parseToString() {
        val t = "{mVoiceRegState=0 ... mPhysicalChannelConfigs=[PhysicalChannelConfig{mConnectionStatus=PrimaryServing,mCellBandwidthDownlinkKhz=15000,mNetworkType=LTE,mDownlinkChannelNumber=1724,mPhysicalCellId=301,mBand=3}, PhysicalChannelConfig{mConnectionStatus=SecondaryServing,mCellBandwidthDownlinkKhz=5000,mNetworkType=LTE,mDownlinkChannelNumber=3100,mPhysicalCellId=12,mBand=2147483647}]}"
        val p = CellMath.parsePhys(t)
        assertEquals(2, p.size); assertEquals(true, p[0].primary); assertEquals(1724, p[0].channel); assertEquals(3, p[0].bandNum); assertNull(p[1].bandNum)
        assertEquals("B7", CellMath.physBand(p[1]))
        assertEquals("2xCA: B3+B7, 15+5 МГц", CellMath.caBandsSummary(CellMath.carriers(listOf(15000, 5000), "B3", p, emptyList()), true))
        assertTrue(CellMath.parsePhys("ServiceState{mChannelNumber=1724}").isEmpty())
    }
    @Test fun singleCarrier() {
        assertEquals("без CA, B7, 20 МГц", CellMath.caBandsSummary(CellMath.carriers(listOf(20000), "B7", emptyList(), emptyList()), false))
        assertEquals(1724 to "B3", 1724 to CellMath.lte(1724)!!.band)
    }
}
