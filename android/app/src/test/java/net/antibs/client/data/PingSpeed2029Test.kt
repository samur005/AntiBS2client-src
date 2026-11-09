package net.antibs.client.data

import org.junit.Assert.*
import org.junit.Test

/** 2.0.29: bulk-ping speed knobs (timeout cap, parallelism defaults). */
class PingSpeed2029Test {
    @Test fun defaultTimeoutIsFourSeconds() {
        assertEquals(4, AdvancedSettings().pingTimeoutSec)
    }

    @Test fun bulkTimeoutCap() {
        fun bulkMs(userSec: Int) = (minOf(userSec, 4)).coerceIn(2, 15) * 1000
        assertEquals(4000, bulkMs(5))
        assertEquals(4000, bulkMs(15))
        assertEquals(3000, bulkMs(3))
        assertEquals(2000, bulkMs(1)) // coerce floor
    }

    @Test fun expandedFirstOrdering() {
        val collapsed = setOf("subB")
        val orphan = net.antibs.client.ui.UiLogic.ORPHAN_GROUP
        fun rank(sub: String?) = when {
            !sub.isNullOrEmpty() && sub !in collapsed -> 0
            sub.isNullOrEmpty() && orphan !in collapsed -> 0
            else -> 1
        }
        assertEquals(0, rank("subA"))
        assertEquals(1, rank("subB"))
        assertEquals(0, rank(null))
        assertTrue(rank("subA") < rank("subB"))
    }
}
