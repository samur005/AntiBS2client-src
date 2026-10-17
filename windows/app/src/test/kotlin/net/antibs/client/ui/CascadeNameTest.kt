package net.antibs.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CascadeNameTest {
    private val route = "🇷🇺\u2009→\u2009🇨🇦"

    @Test fun twoFlagsWithDash() {
        val n = "🇷🇺 —🇨🇦 Канада"
        assertEquals(route, UiLogic.flag(n)); assertEquals("Канада", UiLogic.displayName(n)); assertEquals("🇨🇦", UiLogic.exitFlag(n))
    }
    @Test fun otherSeparators() {
        for (n in listOf("🇷🇺→🇨🇦 Канада", "🇷🇺 - 🇨🇦 Канада", "🇷🇺 -> 🇨🇦 · Канада", "🇷🇺🇨🇦 Канада", "RU - CA Канада", "RU→CA Канада", "RU/CA Канада")) {
            assertEquals(n, route, UiLogic.flag(n)); assertEquals(n, "Канада", UiLogic.displayName(n))
        }
        assertEquals("$route Канада", UiLogic.pickTitle("🇷🇺 —🇨🇦 Канада"))
        assertEquals("CA", WorldLogic.liveCountry("🇷🇺 —🇨🇦 Канада"))
    }
    @Test fun plainHostsUnchanged() {
        assertEquals("🇩🇪", UiLogic.flag("🇩🇪 Германия")); assertEquals("Германия", UiLogic.displayName("🇩🇪 Германия"))
        assertNull(UiLogic.cascade("🇩🇪 Германия"))
        assertNull(UiLogic.cascade("DE Frankfurt"))
        assertNull(UiLogic.cascade("XX - YY test"))      // not ISO codes
        assertNull(UiLogic.cascade("🇩🇪 Германия - 🇫🇷 backup"))  // second flag not right after the first
        assertEquals("🌐", UiLogic.flag("Server"))
    }
    @Test fun routeOnlyNameFallsBackToCountry() {
        assertEquals("CA", UiLogic.cascade("🇷🇺→🇨🇦")!!.exitCc)
    }
}
