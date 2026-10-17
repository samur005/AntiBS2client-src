package net.antibs.client.engine

import net.antibs.client.data.QwdttProfile
import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwdttPingTest {
    private val p = QwdttProfile("NL", "nl.example.com:56000", hashes = "", workers = 16, port = 9000, password = "pw")

    @Test fun parsesClientOutput() {
        assertEquals(QwdttPing.Line.Ok(836), QwdttPing.parseLine("PING_RESULT|836"))
        assertTrue(QwdttPing.parseLine("PING_ERROR|All hashes failed. Last error: handshake error") is QwdttPing.Line.Error)
        assertTrue(QwdttPing.parseLine("PING_RESULT|0") is QwdttPing.Line.Error)
        assertEquals(QwdttPing.Line.Captcha, QwdttPing.parseLine("CAPTCHA_SOLVE|1|https://id.vk.com/not_robot"))
        assertEquals(QwdttPing.Line.Other, QwdttPing.parseLine("[КЛИЕНТ] VK auth mode: anonymous"))
    }

    @Test fun commandUsesPingOnlyModeWithProfileAndSettings() {
        val hs = QwdttPing.hashes(p, "g1, g2")
        assertEquals(listOf("g1", "g2"), hs)                                    // global hashes when the host has none
        assertEquals(listOf("h1"), QwdttPing.hashes(p.copy(hashes = "h1"), "g1"))
        val a = QwdttPing.args("/lib/libqwdtt.so", p, hs, "dev", "yandex", "audio", turnTcp = true)
        assertEquals("-ping-only", a[1])
        fun v(k: String) = a[a.indexOf(k) + 1]
        assertEquals("nl.example.com:56000", v("-peer")); assertEquals("g1,g2", v("-vk")); assertEquals("pw", v("-password"))
        assertEquals("yandex", v("-go-dns")); assertEquals("audio", v("-obfs"))
        assertTrue("-turn-tcp" in a)
        assertFalse("-listen" in a)                                            // no tunnel, no local port taken
        assertFalse("-turn-tcp" in QwdttPing.args("b", p, hs, "d", "yandex", "audio", false))
    }

    @Test fun timeoutLeavesRoomForVkCredentials() {
        assertEquals(12_000, QwdttPing.timeoutFor(5_000))
        assertEquals(16_000, QwdttPing.timeoutFor(10_000))
        assertEquals(21_000, QwdttPing.timeoutFor(15_000))
        assertEquals(25_000, QwdttPing.timeoutFor(60_000))
    }

    @Test fun speedCardOnlyWhileConnectedOrConnecting() {
        assertTrue(UiLogic.speedCardWanted(ConnState.CONNECTED))
        assertTrue(UiLogic.speedCardWanted(ConnState.CONNECTING))
        assertFalse(UiLogic.speedCardWanted(ConnState.DISCONNECTED))
        assertFalse(UiLogic.speedCardWanted(ConnState.DISCONNECTING))
        assertFalse(UiLogic.speedCardWanted(ConnState.ERROR))
        assertEquals(5_000L, UiLogic.SPEED_CARD_HIDE_DELAY_MS)
    }
}
