package net.antibs.client.desktop

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TunConfigTest {
    @Test fun tunRoutesEverythingToXraySocksAndExcludesEngines() {
        val c = TunConfig.build(10808, excludeProcesses = listOf("xray.exe", "sing-box.exe"), excludeCidrs = listOf("203.0.113.0/24"))
        val tun = c["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals("true", tun["auto_route"]!!.jsonPrimitive.content)
        assertEquals("true", tun["strict_route"]!!.jsonPrimitive.content)
        val socks = c["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals(10808, socks["server_port"]!!.jsonPrimitive.content.toInt())
        val rules = c["route"]!!.jsonObject["rules"]!!.jsonArray.toString()
        assertTrue(rules.contains("xray.exe") && rules.contains("hijack-dns") && rules.contains("ip_is_private"))
        File("build/tun-test.json").writeText(c.toString())
    }
}
