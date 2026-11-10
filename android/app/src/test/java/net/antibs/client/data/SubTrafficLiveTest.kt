package net.antibs.client.data

import org.junit.Assert.*
import org.junit.Test

class SubTrafficLiveTest {
    private fun sub(
        id: String = "s1",
        upload: Long = 10L * 1024 * 1024,
        download: Long = 40L * 1024 * 1024,
        total: Long = 150L * 1024 * 1024,
        lastRefresh: Long = 1000L,
    ) = Subscription(id = id, url = "https://example", upload = upload, download = download, total = total, lastRefresh = lastRefresh)

    @Test fun remainingFromUserinfoIsTotalMinusUsed() {
        assertEquals(100L * 1024 * 1024, SubTrafficLive.remainingFromUserinfo(sub())!!)
        assertNull(SubTrafficLive.remainingFromUserinfo(sub(total = 0)))
        assertEquals(0L, SubTrafficLive.remainingFromUserinfo(sub(upload = 80, download = 80, total = 100)))
    }

    @Test fun sessionBytesDecreaseShownRemainingAndClampAtZero() {
        val s = sub()
        var snap = SubTrafficLive.tick(null, s, sessionBytes = 0, accumulating = true)!!
        assertEquals(100L * 1024 * 1024, SubTrafficLive.shownRemaining(snap))
        snap = SubTrafficLive.tick(snap, s, sessionBytes = 5L * 1024 * 1024, accumulating = true)!!
        assertEquals(95L * 1024 * 1024, SubTrafficLive.shownRemaining(snap))
        snap = SubTrafficLive.tick(snap, s, sessionBytes = 200L * 1024 * 1024, accumulating = true)!!
        assertEquals(0L, SubTrafficLive.shownRemaining(snap))
    }

    @Test fun newUserinfoFetchResetsBaseline() {
        val first = sub(upload = 10, download = 40, total = 150, lastRefresh = 1)
        var snap = SubTrafficLive.tick(null, first, 20, accumulating = true)!!
        snap = SubTrafficLive.tick(snap, first, 30, accumulating = true)!!
        assertEquals(10L, snap.consumed)
        val refreshed = first.copy(upload = 10, download = 60, total = 150, lastRefresh = 2)
        val next = SubTrafficLive.tick(snap, refreshed, 30, accumulating = true)!!
        assertEquals(0L, next.consumed)
        assertEquals(80L, next.remainingAtFetch)
        assertEquals(80L, SubTrafficLive.shownRemaining(next))
    }

    @Test fun disconnectedKeepsConsumedAndDoesNotAccumulate() {
        val s = sub()
        var snap = SubTrafficLive.tick(null, s, 0, accumulating = true)!!
        snap = SubTrafficLive.tick(snap, s, 8, accumulating = true)!!
        val parked = SubTrafficLive.tick(snap, s, 8, accumulating = false)!!
        assertEquals(8L, parked.consumed)
        val later = SubTrafficLive.tick(parked, s, 99, accumulating = false)!!
        assertEquals(8L, later.consumed)
        assertEquals(SubTrafficLive.remainingFromUserinfo(s)!! - 8, SubTrafficLive.shownRemaining(later))
    }

    @Test fun sessionCounterRestartRealignsMarkWithoutLosingConsumed() {
        val s = sub()
        var snap = SubTrafficLive.tick(null, s, 0, accumulating = true)!!
        snap = SubTrafficLive.tick(snap, s, 50, accumulating = true)!!
        assertEquals(50L, snap.consumed)
        snap = SubTrafficLive.tick(snap, s, 0, accumulating = true)!! // new session
        assertEquals(50L, snap.consumed)
        assertEquals(0L, snap.sessionMark)
        snap = SubTrafficLive.tick(snap, s, 7, accumulating = true)!!
        assertEquals(57L, snap.consumed)
    }

    @Test fun applyRewritesTrafficPairOnlyForThisSnap() {
        val s = sub()
        val base = SubLineData.of(s, 0)!!
        val snap = SubTrafficLive.tick(null, s, 10L * 1024 * 1024, accumulating = true)!!
        val shown = SubTrafficLive.apply(base, snap)!!
        assertEquals(SubLineData.pair(SubTrafficLive.shownUsed(snap), s.total), shown.traffic)
        assertEquals(base.days, shown.days)
        assertEquals(base, SubTrafficLive.apply(base, null))
    }

    @Test fun otherSubDoesNotReuseSnapshot() {
        val a = sub(id = "a")
        val snapA = SubTrafficLive.tick(null, a, 10, accumulating = true)!!
        val b = sub(id = "b", upload = 0, download = 0, total = 1000)
        val snapB = SubTrafficLive.tick(snapA, b, 10, accumulating = true)!!
        assertEquals("b", snapB.subId)
        assertEquals(0L, snapB.consumed)
        assertEquals(1000L, snapB.remainingAtFetch)
    }
}
