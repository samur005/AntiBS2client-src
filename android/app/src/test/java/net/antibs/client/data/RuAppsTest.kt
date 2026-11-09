package net.antibs.client.data

import org.junit.Assert.*
import org.junit.Test

class RuAppsTest {
    private val self = "fun.antibs.client"

    @Test fun bundledIdsAreWellFormedAndUnique() {
        assertTrue(RuApps.BUNDLED.size >= 70)
        assertTrue(RuApps.BUNDLED.all { RuApps.isValidPackage(it) })
        assertTrue("ru.sberbankmobile" in RuApps.BUNDLED && "ru.rostel" in RuApps.BUNDLED && "ru.ozon.app.android" in RuApps.BUNDLED)
        assertFalse(self in RuApps.BUNDLED)
    }

    @Test fun onlyInstalledAppsFromTheListAreShown() {
        val installed = setOf("ru.sberbankmobile", "org.telegram.messenger", "ru.ozon.app.android", self)
        assertEquals(listOf("ru.ozon.app.android", "ru.sberbankmobile"), RuApps.installedFromList(installed, emptySet(), emptySet(), self))
        assertFalse(self in RuApps.installedFromList(installed, setOf(self), emptySet(), self))
    }

    @Test fun manualAddAndRemoveSurviveABundledListChange() {
        var (a, r) = RuApps.add("org.example.local", emptySet(), emptySet())
        RuApps.remove("ru.sberbankmobile", a, r).let { a = it.first; r = it.second }
        assertEquals(setOf("org.example.local"), a)
        assertEquals(setOf("ru.sberbankmobile"), r)
        val eff = RuApps.effective(a, r)
        assertTrue("org.example.local" in eff); assertFalse("ru.sberbankmobile" in eff)
        RuApps.add("ru.sberbankmobile", a, r).let { assertEquals(setOf("org.example.local"), it.first); assertTrue(it.second.isEmpty()) }
        assertEquals(a to r, RuApps.add("not a package", a, r))
    }

    @Test fun planOffAndBypassAddRuApps() {
        val ru = listOf("ru.sberbankmobile", "ru.ozon.app.android")
        val off = RuApps.plan("off", emptyList(), emptyList(), self, true, ru)
        assertEquals(listOf(self) + ru, off.disallowed)
        assertTrue(off.allowed.isEmpty())
        val bypass = RuApps.plan("bypass", emptyList(), listOf("org.example.a"), self, true, ru)
        assertTrue("org.example.a" in bypass.disallowed && self in bypass.disallowed && "ru.sberbankmobile" in bypass.disallowed)
    }

    @Test fun includeModeUnchangedByRuApps() {
        val ru = listOf("ru.sberbankmobile", "ru.ozon.app.android")
        val p = RuApps.plan("include", listOf("ru.sberbankmobile", "org.example.a"), emptyList(), self, true, ru)
        assertEquals(listOf("ru.sberbankmobile", "org.example.a"), p.allowed)
        assertTrue(p.disallowed.isEmpty())
        val empty = RuApps.plan("include", emptyList(), emptyList(), self, true, ru)
        assertEquals(listOf(self), empty.disallowed)
    }

    @Test fun perAppPlanMergesNote() {
        val plan = PerApp.plan("bypass", emptyList(), listOf("org.example.a"), self, true, listOf("ru.sberbankmobile"))
        assertTrue(self in plan.disallowed && "ru.sberbankmobile" in plan.disallowed)
        assertTrue(plan.note.contains("российские"))
    }
}
