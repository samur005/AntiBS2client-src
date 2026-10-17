package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.engine.BalancerPick
import net.antibs.client.ui.UiLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.0.19: the subscription «🎲Автовыбор сервера» stub runs as an Xray leastPing balancer. */
class AutoSelectBalancerTest {
    private val sub = "sub-test"
    private val autoLink = "ss://MjAyMi1ibGFrZTMtYWVzLTI1Ni1nY206QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQT06UkVSRVJFUkVSRVJFUkVSRVJFUkVSRVJFUkVSRVJFUkVSRVJFUkVSRVJFUT0=@web.max.ru:1234#%F0%9F%87%AA%F0%9F%87%BA%20%F0%9F%8E%B2%D0%90%D0%B2%D1%82%D0%BE%D0%B2%D1%8B%D0%B1%D0%BE%D1%80%20%D1%81%D0%B5%D1%80%D0%B2%D0%B5%D1%80%D0%B0"
    private val links = listOf(
        autoLink,
        "vless://11111111-1111-1111-1111-111111111111@g.example.com:8443?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality&sni=g.example.com&fp=firefox&pbk=0qf1yqC8cClcVweSYlke84Ken7BROmrO3TDjJviNfL0&sid=0bd951f54f6907f0#%F0%9F%87%A9%F0%9F%87%AA%20DE",
        "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388#%F0%9F%87%B3%F0%9F%87%B1%20NL",
        // needs the sing-box helper → left out of the auto balancer
        "tuic://11111111-1111-1111-1111-111111111111:pw@t.example.com:443?sni=t.example.com&congestion_control=bbr#TUIC",
    )
    private val servers = links.map { LinkParser.parse(it, sub)!! }
    private val auto = servers[0]

    @Test fun stubIsRecognised() {
        assertTrue(AutoSelect.isSubscriptionAuto(auto))
        assertEquals("shadowsocks", auto.xray!!.protocol)
        servers.drop(1).forEach { assertFalse(it.name, AutoSelect.isSubscriptionAuto(it)) }
        // the same name in «Серверы» (manual) is a normal host
        assertFalse(AutoSelect.isSubscriptionAuto(auto.copy(subscriptionId = ProtocolSupport.MANUAL_SUB_ID)))
        assertEquals("Автовыбор", UiLogic.protocolGroup(auto))
    }

    @Test fun membersExcludeStubAndHelperEngines() {
        val m = AutoSelect.members(auto, servers)
        assertEquals(listOf(servers[1].id, servers[2].id), m.map { it.id })
        assertEquals(m.map { it.id }, XrayConfigBuilder.balancerMembers(auto, servers).map { it.id })
    }

    @Test fun balancerConfigForMixedSubscription() {
        val plan = XrayConfigBuilder.plan(auto, servers)
        assertNull(plan.singBox); assertTrue(plan.mieruInstances.isEmpty()); assertNull(plan.mihomo)
        File("build/xray-configs-auto").mkdirs()
        File("build/xray-configs-auto/auto_balancer.json").writeText(plan.xray)
        val root = Json.parseToJsonElement(plan.xray).jsonObject
        val outs = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val tags = outs.map { it["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("b-0", "b-1"), tags.filter { it.startsWith("b-") })
        val b0 = outs.first { it["tag"]!!.jsonPrimitive.content == "b-0" }
        val b1 = outs.first { it["tag"]!!.jsonPrimitive.content == "b-1" }
        assertEquals("vless", b0["protocol"]!!.jsonPrimitive.content)
        assertEquals("reality", b0["streamSettings"]!!.jsonObject["security"]!!.jsonPrimitive.content)
        assertEquals("shadowsocks", b1["protocol"]!!.jsonPrimitive.content)
        assertFalse("stub host must not be dialed", plan.xray.contains("web.max.ru"))
        assertFalse(plan.xray.contains("t.example.com"))
        val routing = root["routing"]!!.jsonObject
        val bal = routing["balancers"]!!.jsonArray.single().jsonObject
        assertEquals("bal", bal["tag"]!!.jsonPrimitive.content)
        assertEquals(listOf("b-"), bal["selector"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("leastping", bal["strategy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("b-0", bal["fallbackTag"]!!.jsonPrimitive.content)
        // every selector-matched tag exists, and the catch-all rule points at the balancer
        val rules = routing["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rules.any { it["balancerTag"]?.jsonPrimitive?.content == "bal" && it["network"]?.jsonPrimitive?.content == "tcp,udp" })
        rules.mapNotNull { it["outboundTag"]?.jsonPrimitive?.content }.forEach { assertTrue(it, it in tags) }
        val obs = root["observatory"]!!.jsonObject
        assertEquals(listOf("b-"), obs["subjectSelector"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(AdvancedSettings.DEFAULT_PING_URL, obs["probeURL"]!!.jsonPrimitive.content)
        // socks inbound for hev-socks5-tunnel / the tunnel ping stays in place
        assertTrue((root["inbounds"] as JsonArray).any { (it as JsonObject)["tag"]!!.jsonPrimitive.content == "socks" })
    }

    @Test fun noMembersIsAClearError() {
        val e = runCatching { XrayConfigBuilder.plan(auto, listOf(auto)) }.exceptionOrNull()
        assertEquals("В подписке нет серверов для автовыбора", e?.message)
    }

    @Test fun normalHostPlanUnchanged() {
        val plan = XrayConfigBuilder.plan(servers[1], servers)
        assertFalse(plan.xray.contains("\"balancers\""))
        assertTrue(plan.xray.contains("\"tag\": \"proxy\""))
    }

    @Test fun pickFollowsTraffic() {
        val p = BalancerPick()
        // probes only: about equal on both members → no pick
        assertNull(p.add(mapOf("b-0" to 3000L, "b-1" to 3200L)))
        assertNull(p.current)
        repeat(3) { p.add(mapOf("b-1" to 50_000L)) }
        assertEquals("b-1", p.current)
        assertNull(p.add(mapOf("b-1" to 60_000L)))   // unchanged → no event
        var switched: String? = null
        repeat(6) { p.add(mapOf("b-0" to 80_000L))?.let { switched = it } }
        assertEquals("b-0", switched)
        assertEquals(3, BalancerPick.indexOf("b-3")); assertNull(BalancerPick.indexOf("block"))
    }
}
