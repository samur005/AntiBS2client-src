package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.ServerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDeleteUnreachableTest {
    private fun srv(id: String, name: String = id) =
        ServerEntry(id = id, engine = EngineKind.XRAY, name = name, subscriptionId = "s1")

    private val hosts = listOf(srv("ok"), srv("dead"), srv("dead2"), srv("live", "Живой"), srv("skip"))

    @Test fun deletesOnlyFailedInBatchWhenSomeSucceeded() {
        val pings = mapOf("ok" to 40L, "dead" to -1L, "dead2" to -1L, "live" to -1L, "skip" to -2L)
        val ids = AutoDeleteUnreachable.idsToDelete(
            hosts, setOf("ok", "dead", "dead2", "live", "skip"), pings,
        )
        assertEquals(listOf("dead", "dead2", "live"), ids)
    }

    @Test fun neverDeletesConnectedOrRoutedHost() {
        val pings = mapOf("ok" to 40L, "dead" to -1L, "live" to -1L)
        val ids = AutoDeleteUnreachable.idsToDelete(
            hosts, setOf("ok", "dead", "live"), pings,
            protectIds = setOf("dead"), protectName = "Живой",
        )
        assertTrue(ids.isEmpty())
    }

    @Test fun skipsWhenStoppedEarly() {
        val pings = mapOf("ok" to 40L, "dead" to -1L)
        assertTrue(
            AutoDeleteUnreachable.idsToDelete(hosts, setOf("ok", "dead"), pings, stoppedEarly = true).isEmpty(),
        )
    }

    @Test fun skipsWhenNetworkOffline() {
        val pings = mapOf("ok" to 40L, "dead" to -1L)
        assertTrue(
            AutoDeleteUnreachable.idsToDelete(hosts, setOf("ok", "dead"), pings, networkOffline = true).isEmpty(),
        )
    }

    @Test fun doesNotWipeWhenEveryHostFailed() {
        val pings = mapOf("ok" to -1L, "dead" to -1L, "dead2" to -1L)
        assertTrue(
            AutoDeleteUnreachable.idsToDelete(hosts, setOf("ok", "dead", "dead2"), pings).isEmpty(),
        )
    }

    @Test fun ignoresHostsNotInTheCompletedBatch() {
        val pings = mapOf("ok" to 40L, "dead" to -1L, "dead2" to -1L)
        // only "dead" was in this batch; dead2 was not pinged this time
        assertEquals(
            listOf("dead"),
            AutoDeleteUnreachable.idsToDelete(hosts, setOf("ok", "dead"), pings),
        )
    }

    @Test fun toggleOffIsCallersJob() {
        // the decide function always applies rules; the VM skips when the toggle is off
        val pings = mapOf("ok" to 12L, "dead" to -1L)
        assertEquals(listOf("dead"), AutoDeleteUnreachable.idsToDelete(hosts, setOf("ok", "dead"), pings))
    }
}
