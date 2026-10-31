package net.antibs.client.cell

import org.junit.Assert.*
import org.junit.Test

class SignalFixTest {
    @Test fun rssnrScaling() {
        assertEquals(11.0, CellMath.sinrFromRssnr(11)!!, 0.0)
        assertEquals(11.0, CellMath.sinrFromRssnr(110)!!, 0.0)
        assertEquals(-5.5, CellMath.sinrFromRssnr(-55)!!, 0.0)
        assertNull(CellMath.sinrFromRssnr(Int.MAX_VALUE)); assertNull(CellMath.sinrFromRssnr(null)); assertNull(CellMath.sinrFromRssnr(1000))
    }
    @Test fun pickSinrSkipsZero() {
        assertEquals(11.0, CellMath.pickSinr(0.0, null, 11.0)!!, 0.0)
        assertNull(CellMath.pickSinr(0.0, null, 0.0))
    }
    @Test fun rssiEstimate() {
        // B3 20 MHz: RSRP -95, RSRQ -12 → -95 + 12 + 20 = -63
        assertEquals(-63, CellMath.rssiEstimate(-95, -12, 20000))
        assertEquals(-70, CellMath.rssiEstimate(-100, -11, 15000))
    }
    @Test fun validity() {
        assertNull(CellMath.validCqi(0)); assertNull(CellMath.validCqi(Int.MAX_VALUE)); assertEquals(9, CellMath.validCqi(9))
        assertNull(CellMath.validRssi(Int.MAX_VALUE)); assertEquals(-103, CellMath.validRssi(-103))
        assertNull(CellMath.rssiEstimate(-95, -12, 7000)); assertNull(CellMath.rssiEstimate(null, -12, 20000))
        assertEquals(110, CellMath.parseField("CellSignalStrengthLte: rssi=-103 rsrp=-95 rssnr=110 cqi=2147483647", "rssnr"))
        assertEquals(-103, CellMath.parseField("rssi=-103 rsrp=-95", "rssi"))
        assertNull(CellMath.parseField("cqi=2147483647", "cqi"))
    }
}
