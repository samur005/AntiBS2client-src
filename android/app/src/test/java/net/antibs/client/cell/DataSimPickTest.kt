package net.antibs.client.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataSimPickTest {
    private val ids = listOf(1, 2)
    @Test fun defaultDataWins() { assertEquals(2, DataSimPick.dataSub(ids, 2, 1)) }
    @Test fun fallbackActive() { assertEquals(1, DataSimPick.dataSub(ids, -1, 1)) }
    @Test fun invalidOrUnknown() {
        assertNull(DataSimPick.dataSub(ids, -1, -1))
        assertNull(DataSimPick.dataSub(ids, 7, Int.MAX_VALUE))
        assertEquals(1, DataSimPick.dataSub(ids, 9, 1))
    }
    @Test fun indexPrefersUser() { assertEquals(0, DataSimPick.index(ids, 1, 2)) }
    @Test fun indexDataSim() { assertEquals(1, DataSimPick.index(ids, null, 2)) }
    @Test fun indexStaleUserFallsBack() { assertEquals(1, DataSimPick.index(ids, 5, 2)) }
    @Test fun singleAndEmpty() {
        assertEquals(0, DataSimPick.index(listOf(3), null, null))
        assertEquals(0, DataSimPick.index(emptyList(), null, 2))
    }
}
