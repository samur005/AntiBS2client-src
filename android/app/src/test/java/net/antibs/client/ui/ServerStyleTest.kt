package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.ServerEntry
import org.junit.Assert.*
import org.junit.Test

class ServerStyleTest {
    private fun cfg(id: String, sub: String? = null) = ServerEntry(id = id, engine = EngineKind.XRAY, name = id, subscriptionId = sub)
    private val pings = mapOf<String, Long>("a" to 120, "b" to 38, "c" to -1, "e" to 38, "f" to 200)
    private val all = listOf(cfg("a", "s1"), cfg("b", "s1"), cfg("c", "s2"), cfg("d", "s2"), cfg("e", "s2"), cfg("f", "s2"), cfg("g"))

    @Test fun bestPicksLowestMeasured() {
        assertEquals("b", ServerStyleLogic.best(all) { pings[it] }?.id)
        assertNull(ServerStyleLogic.best(listOf(cfg("c"), cfg("d"))) { pings[it] })
    }
    @Test fun bestScope() {
        val r = ServerStyleLogic.bestScope(SubLayout.RIBBON, "s2", all)
        assertEquals("e", ServerStyleLogic.best(r) { pings[it] }?.id)
        assertEquals(all, ServerStyleLogic.bestScope(SubLayout.GROUPS, "s2", all))
    }
    @Test fun filters() {
        assertEquals(listOf("a", "b", "e"), ServerStyleLogic.filter(all, ServerFilter.FAST, emptySet()) { pings[it] }.map { it.id })
        assertEquals(listOf("g"), ServerStyleLogic.filter(all, ServerFilter.FAVORITES, setOf("g")) { pings[it] }.map { it.id })
    }
    @Test fun sort() {
        assertEquals(listOf("b", "e", "a", "f"), ServerStyleLogic.sortServers(all.filter { it.id in "abef" }, StylePrefs.SORT_FASTEST) { pings[it] }.map { it.id })
        assertEquals(listOf("g", "f"), ServerStyleLogic.sortServers(listOf(cfg("g"), cfg("f")), StylePrefs.SORT_ADDED) { null }.map { it.id })
    }
    @Test fun ribbonAndLayout() {
        assertEquals("s1", ServerStyleLogic.validRibbon(ServerStyleLogic.RIBBON_FIRST, listOf("s1", "s2")))
        assertEquals("", ServerStyleLogic.validRibbon("gone", listOf("s1")))
        assertEquals("s1", ServerStyleLogic.stackSelection("", listOf("s1")))
        assertEquals(SubLayout.RIBBON, ServerStyleLogic.effectiveLayout(ServerStyle.STACK, StyleOptions(SubLayout.GROUPS)))
        assertEquals(SubLayout.GROUPS, ServerStyleLogic.effectiveLayout(ServerStyle.NEON, StyleOptions(SubLayout.GROUPS)))
    }
    @Test fun manualGroup() {
        assertTrue(ServerStyleLogic.inGroup(cfg("x", "sub-manual"), ServerStyleLogic.MANUAL_ID))
        assertTrue(ServerStyleLogic.inGroup(cfg("x"), ServerStyleLogic.MANUAL_ID))
        assertFalse(ServerStyleLogic.inGroup(cfg("x", "s1"), ServerStyleLogic.MANUAL_ID))
    }
    @Test fun traffic() {
        assertNull(ServerStyleLogic.remainingFraction(5, 0))
        assertEquals(0.75f, ServerStyleLogic.usedFraction(75, 100)!!, 1e-4f)
        assertEquals(1, ServerStyleLogic.litSegments(1, 1000, 20))
    }
    @Test fun prefsPersistPerStyle() {
        val kv = MapStyleKv()
        val p = StylePrefs(kv)
        assertEquals(ServerStyle.CLASSIC, p.style.value)
        p.setStyle(ServerStyle.CYBER); p.setLayout(ServerStyle.NEON, SubLayout.RIBBON); p.setBestCard(ServerStyle.NEON, false)
        p.toggleFavorite("x"); p.setSort(StylePrefs.SORT_ALPHA); p.setSubNewestFirst(false)
        val q = StylePrefs(kv)
        assertEquals(ServerStyle.CYBER, q.style.value)
        assertEquals(StyleOptions(SubLayout.RIBBON, false), q.options(ServerStyle.NEON))
        assertEquals(ServerStyleLogic.defaultOptions(ServerStyle.CARDS), q.options(ServerStyle.CARDS))
        assertEquals(setOf("x"), q.favorites.value)
        assertEquals(StylePrefs.SORT_ALPHA, q.sort.value); assertFalse(q.subNewestFirst.value)
        assertEquals(ServerStyle.CLASSIC, ServerStyle.of("bogus"))
        assertEquals(7, ServerStyle.entries.size)
    }
}

class SubOrderTest {
    @Test fun move() {
        assertEquals(listOf("b", "a", "c"), SubOrder.move(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("c", "a", "b"), SubOrder.move(listOf("a", "b", "c"), "c", 0))
        assertEquals(listOf("a", "b"), SubOrder.move(listOf("a", "b"), "zz", 0))
    }
    @Test fun storedAndApply() {
        assertEquals(listOf("c", "b", "a"), SubOrder.storedFromDisplay(listOf("a", "b", "c"), false))
        assertEquals(listOf("c", "a", "b", "x"), SubOrder.apply(listOf("a", "b", "x", "c"), listOf("c", "a", "b")) { it })
        assertEquals(listOf("b", "a", "x"), SubOrder.apply(listOf("a", "x", "b"), listOf("b", "a")) { it })
    }
}
