package net.antibs.client.data

import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostEditsTest {
    private fun vless(name: String, server: String = "a.example", port: Int = 443) =
        XrayProfile(name, "vless", server, port, id = "uuid-1", security = "reality", sni = "sni.example", publicKey = "pbk", shortId = "ab",
            network = "xhttp", extra = """{"x":1}""", path = "/p")

    private fun entry(id: String, sub: String, p: XrayProfile) = ServerEntry(id, EngineKind.XRAY, p.remarks, sub, xray = p)

    @Test fun refreshSkipsHiddenAndAppliesOverrides() {
        val fresh = listOf(entry("h1", "s", vless("one")), entry("h2", "s", vless("two")), entry("h3", "s", vless("three")))
        val edited = vless("mine", server = "b.example", port = 8443)
        val sub = Subscription("s", "https://x", hiddenHosts = listOf("h2"), hostOverrides = mapOf("h3" to HostOverride(edited, vless("three"))))
        val out = HostEdits.applyToRefresh(sub, fresh)
        assertEquals(listOf("h1", "h3"), out.map { it.id })
        assertEquals("mine", out[1].name)
        assertEquals("b.example", out[1].xray!!.server)
        assertEquals(fresh, HostEdits.applyToRefresh(Subscription("s", "u"), fresh))
        assertTrue(HostEdits.isEdited(sub, "h3")); assertFalse(HostEdits.isEdited(sub, "h1"))
    }

    @Test fun refreshUpdatesOriginalForReset() {
        val newer = vless("three-v2", server = "c.example")
        val sub = Subscription("s", "u", hostOverrides = mapOf("h3" to HostOverride(vless("mine"), vless("three"))))
        val o = HostEdits.refreshOriginals(sub, listOf(entry("h3", "s", newer)))
        assertEquals("c.example", o["h3"]!!.original.server)
        assertEquals("mine", o["h3"]!!.profile.remarks)
    }

    @Test fun mergeKeepsUnshownParameters() {
        val base = vless("one")
        val fields = UiLogic.editFields(base)
        assertEquals("one", fields["remarks"]); assertEquals("443", fields["port"]); assertEquals("uuid-1", fields["id"])
        assertTrue("publicKey" in fields && "sni" in fields)
        fields["server"] = " new.example "; fields["port"] = "2053"; fields["sni"] = ""; fields["remarks"] = "Новый"
        val m = HostEdits.merge(base, fields)
        assertEquals("new.example", m.server); assertEquals(2053, m.port); assertNull(m.sni); assertEquals("Новый", m.remarks)
        // not in the dialog → untouched
        assertEquals("""{"x":1}""", m.extra); assertEquals("xhttp", m.network); assertEquals("ab", m.shortId)
        // invalid port / blank name keep the old values
        val bad = HostEdits.merge(base, mapOf("port" to "99999", "remarks" to " "))
        assertEquals(443, bad.port); assertEquals("one", bad.remarks)
    }

    @Test fun customJsonHostsEditOnlyJson() {
        val c = XrayProfile("my json", "custom", "", 0, customJson = "{}")
        assertEquals(listOf("remarks", "customJson"), UiLogic.editFields(c).keys.toList())
        assertEquals("""{"a":1}""", HostEdits.merge(c, mapOf("customJson" to """{"a":1}""")).customJson)
    }
}
