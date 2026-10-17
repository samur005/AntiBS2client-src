package net.antibs.client.engine

import net.antibs.client.data.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.16: no built-in subscriptions; user subscriptions survive the first-run/migration step. */
class NoDefaultSubsTest {
    @Test fun freshInstallStartsEmpty() {
        val st = AppStore.initialised(AppState(), { "hw" })
        assertTrue(st.subscriptions.isEmpty())
        assertTrue(st.servers.isEmpty())
        assertTrue(st.seeded)
        assertEquals("hw", st.hwid)
    }

    @Test fun existingSubscriptionsAreKept() {
        val mine = Subscription(id = "s1", url = "https://sub.example.com/x", kind = "remnawave", title = "Mine")
        val st = AppStore.initialised(AppState(subscriptions = listOf(mine), hwid = "keep"), { "new" })
        assertEquals(listOf(mine), st.subscriptions)
        assertEquals("keep", st.hwid)
    }
}
