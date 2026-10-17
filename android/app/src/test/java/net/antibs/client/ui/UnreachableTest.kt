package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.ServerEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreachableTest {
    private fun srv(id: String, sub: String, name: String = id) =
        ServerEntry(id = id, engine = EngineKind.XRAY, name = name, subscriptionId = sub)

    @Test fun picksFailedAndUnpinged() {
        val servers = listOf(srv("a", "s1"), srv("b", "s1"), srv("c", "s1"), srv("d", "s1"), srv("e", "s2"), srv("f", "s1", "Живой"))
        val pings = mapOf("a" to 120L, "b" to -1L, "d" to -2L, "e" to -1L, "f" to -1L)
        // b failed, c no result; a ok, d н/д, e other sub
        assertEquals(listOf("b", "c", "f"), UiLogic.unreachableIds(servers, "s1", pings))
        // connected host and the host auto-select routes through are protected
        assertEquals(listOf("c"), UiLogic.unreachableIds(servers, "s1", pings, protectIds = setOf("b"), protectName = "Живой"))
    }
}
