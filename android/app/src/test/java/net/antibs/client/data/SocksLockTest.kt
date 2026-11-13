package net.antibs.client.data

import kotlinx.serialization.json.Json
import net.antibs.client.engine.AppState
import net.antibs.client.engine.AppStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 2.0.43: «Зафиксировать логин, пароль и порт» — nothing but «Сгенерировать заново» replaces the credentials. */
class SocksLockTest {
    private val fixed = AdvancedSettings(socksUser = "uMine", socksPass = "secretPass123", socksPort = 34567,
        hardening = LocalProxy.HARDENING_VERSION)

    @Test fun lockedByDefault() {
        assertTrue(AdvancedSettings().socksLocked)
        // settings saved before the switch existed decode as locked
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<AdvancedSettings>("""{"socksPort":34567}""")
        assertTrue(old.socksLocked)
    }

    @Test fun freePortIsKept() {
        assertEquals(LocalProxy.PortCheck.Ok(34567), LocalProxy.resolvePort(fixed, Random(1)) { true })
        assertEquals(LocalProxy.PortCheck.Ok(34567), LocalProxy.resolvePort(fixed.copy(socksLocked = false), Random(1)) { true })
    }

    @Test fun lockedBusyPortIsAnErrorAndChangesNothing() {
        val r = LocalProxy.resolvePort(fixed, Random(1)) { it != 34567 }
        assertEquals(LocalProxy.PortCheck.BusyLocked(34567), r)
    }

    @Test fun unlockedBusyPortPicksAnotherFreePortButKeepsCredentials() {
        val a = fixed.copy(socksLocked = false)
        val r = LocalProxy.resolvePort(a, Random(1)) { it != 34567 }
        r as LocalProxy.PortCheck.Reassigned
        assertEquals(34567, r.old)
        assertNotEquals(34567, r.port)
        assertTrue(r.port in LocalProxy.PORT_MIN..LocalProxy.PORT_MAX)
        assertEquals(r.port, r.settings.socksPort)
        assertEquals("uMine", r.settings.socksUser); assertEquals("secretPass123", r.settings.socksPass)
        assertEquals(a.copy(socksPort = r.port), r.settings)
    }

    @Test fun restartAndMigrationNeverRegenerate() {
        // already hardened: migration / AppStore.hardened (load, save, DataStore restore) are no-ops
        assertSame(fixed, LocalProxy.migrate(fixed, Random(2)) { true })
        val st = AppState(rev = 3, adv = fixed)
        assertSame(st, AppStore.hardened(st))
        // a backup / old copy without the marker but with own credentials and port keeps them
        val noMarker = fixed.copy(hardening = 0)
        val m = LocalProxy.migrate(noMarker, Random(3)) { true }
        assertEquals("uMine", m.socksUser); assertEquals("secretPass123", m.socksPass); assertEquals(34567, m.socksPort)
        // repeated JSON round trips (restart) keep everything
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val back = json.decodeFromString<AppState>(json.encodeToString(AppState.serializer(), st))
        assertSame(back, AppStore.hardened(back).let { if (it == back) back else it })
        assertEquals(fixed, back.adv)
    }

    @Test fun onlyRegenerateReplacesCredentials() {
        val g = LocalProxy.regenerate(fixed, Random(4)) { true }
        assertNotEquals("uMine", g.socksUser); assertNotEquals("secretPass123", g.socksPass); assertNotEquals(34567, g.socksPort)
        assertTrue(g.socksLocked)
    }
}
