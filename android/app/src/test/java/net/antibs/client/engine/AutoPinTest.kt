package net.antibs.client.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 1.0.36: sticky auto-select state machine. */
class AutoPinTest {
    private val tags = listOf("proxy", "proxy-2", "proxy-3")

    @Test fun initialPickIsLowestWorkingPing() {
        val p = AutoPin(tags)
        assertTrue(p.needsSweep)
        assertEquals("proxy-2", p.onSweep(mapOf("proxy" to 300L, "proxy-2" to 120L, "proxy-3" to -1L)))
        assertEquals("proxy-2", p.pinned)
        assertFalse(p.needsSweep)
    }

    @Test fun stickyWhileChecksPass() {
        val p = AutoPin(tags)
        p.onSweep(mapOf("proxy" to 300L, "proxy-2" to 120L))
        repeat(5) { assertEquals(AutoPin.Step.OK, p.onCheck(true)) }
        assertEquals("proxy-2", p.pinned)
        assertFalse(p.needsSweep)   // a faster member never triggers a sweep by itself
    }

    @Test fun oneFailureIsRecheckedNotSwitched() {
        val p = AutoPin(tags)
        p.onSweep(mapOf("proxy" to 300L, "proxy-2" to 120L))
        assertEquals(AutoPin.Step.RECHECK, p.onCheck(false))
        assertEquals(AutoPin.Step.OK, p.onCheck(true))
        assertEquals(AutoPin.Step.RECHECK, p.onCheck(false))   // counter was reset by the success
        assertEquals("proxy-2", p.pinned)
    }

    @Test fun twoFailuresFailOverToFastestOther() {
        val p = AutoPin(tags)
        p.onSweep(mapOf("proxy" to 300L, "proxy-2" to 120L, "proxy-3" to 500L))
        p.onCheck(false)
        assertEquals(AutoPin.Step.SWEEP, p.onCheck(false))
        assertTrue(p.needsSweep)
        // the failed one answers fast again in the sweep, but another working one is preferred
        assertEquals("proxy", p.onSweep(mapOf("proxy" to 300L, "proxy-2" to 90L, "proxy-3" to 500L)))
        assertFalse(p.needsSweep)
    }

    @Test fun failedOneKeptWhenItIsTheOnlyWorking() {
        val p = AutoPin(tags)
        p.onSweep(mapOf("proxy-2" to 120L))
        p.onCheck(false); p.onCheck(false)
        assertNull(p.onSweep(mapOf("proxy-2" to 150L)))
        assertEquals("proxy-2", p.pinned)
        assertFalse(p.needsSweep)
    }

    @Test fun allDownKeepsRetrying() {
        val p = AutoPin(tags)
        assertNull(p.onSweep(mapOf("proxy" to -1L, "proxy-2" to -1L)))
        assertNull(p.pinned); assertTrue(p.needsSweep)
        assertNull(p.onSweep(emptyMap())); assertTrue(p.needsSweep)
        assertEquals("proxy-3", p.onSweep(mapOf("proxy-3" to 700L)))
        // later everything goes down after a pin: pin stays, sweeps repeat
        p.onCheck(false); p.onCheck(false)
        assertNull(p.onSweep(mapOf("proxy" to -1L, "proxy-2" to -1L, "proxy-3" to -1L)))
        assertEquals("proxy-3", p.pinned); assertTrue(p.needsSweep)
        assertEquals("proxy", p.onSweep(mapOf("proxy" to 400L)))
    }

    @Test fun neverPinsOutsideMembers() {
        val p = AutoPin(tags)
        assertNull(p.onSweep(mapOf("direct" to 5L, "other" to 1L)))
        assertEquals("proxy", p.onSweep(mapOf("direct" to 5L, "proxy" to 50L)))
    }

    private val cfg = """{"inbounds":[{"tag":"socks","port":10808,"protocol":"socks"}],
        "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"proxy-2","protocol":"trojan"},{"tag":"x-proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
        "routing":{"rules":[{"type":"field","network":"tcp,udp","balancerTag":"B"}],"balancers":[{"tag":"B","selector":["proxy"],"strategy":{"type":"leastPing"}}]},
        "burstObservatory":{"subjectSelector":["proxy"]}}"""

    @Test fun membersFollowSelectorPrefixes() {
        val o = AutoPin.parse(cfg)!!
        assertEquals("B", AutoPin.firstBalancerTag(o))
        assertEquals(listOf("proxy", "proxy-2"), AutoPin.balancerMembers(o, "B"))
    }

    @Test fun probesAddedConfigOtherwiseUnchanged() {
        val out = AutoPin.parse(AutoPin.withProbes(cfg, 47000, mapOf("proxy" to 47001, "proxy-2" to 47002)))!!
        val src = AutoPin.parse(cfg)!!
        assertEquals(src["outbounds"], out["outbounds"])
        assertEquals(src["burstObservatory"], out["burstObservatory"])
        assertEquals(src["routing"]!!.jsonObject["balancers"], out["routing"]!!.jsonObject["balancers"])
        val rules = out["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("proxy", rules[0]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("probe-proxy", rules[0]["inboundTag"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(src["routing"]!!.jsonObject["rules"]!!.jsonArray, kotlinx.serialization.json.JsonArray(rules.drop(2)))
        val inb = out["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, inb.size)
        assertEquals("127.0.0.1", inb[1]["listen"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1:47000", out["api"]!!.jsonObject["listen"]!!.jsonPrimitive.content)
    }

    @Test fun overrideRequestEncoding() {
        val b = XrayApi.Proto.string(1, "B") + XrayApi.Proto.string(2, "proxy-2")
        val f = XrayApi.Proto.fields(b)
        assertEquals(listOf(1, 2), f.map { it.no })
        assertEquals("proxy-2", String(f[1].bytes!!))
    }
}
