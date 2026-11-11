package net.antibs.client.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Windows port of Android AbsFeaturesTest.autoDelete — AutoDeletePing decide / encode. */
class AutoDeletePingTest {
    @Test fun autoDelete() = runBlocking {
        val res = mapOf("a" to -1L, "b" to 50L, "c" to 60L, "d" to 70L)
        var counts = emptyMap<String, Int>()
        repeat(4) { counts = AutoDeletePing.decide(res, emptySet(), true, counts, 5) { true }.counts }
        assertEquals(4, counts["a"])
        val r = AutoDeletePing.decide(res, emptySet(), true, counts, 5) { true }
        assertEquals(AutoDeletePing.Outcome.Deleted(setOf("a")), r.outcome)
        assertEquals(AutoDeletePing.Outcome.Whitelist, AutoDeletePing.decide(res, emptySet(), true, counts, 5) { false }.outcome)
        assertEquals(AutoDeletePing.Outcome.Nothing, AutoDeletePing.decide(res, setOf("a"), true, mapOf("a" to 9), 5) { true }.outcome)
        assertEquals(AutoDeletePing.Outcome.Nothing, AutoDeletePing.decide(mapOf("a" to -1L, "b" to -1L), emptySet(), true, emptyMap(), 1) { true }.outcome)
        assertEquals(mapOf("x" to 2, "y" to 1), AutoDeletePing.decode(AutoDeletePing.encode(mapOf("x" to 2, "y" to 1))))
    }
}
