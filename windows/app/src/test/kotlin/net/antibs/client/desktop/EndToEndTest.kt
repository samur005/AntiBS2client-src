package net.antibs.client.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.SubscriptionSync
import net.antibs.client.engine.TunnelProbe
import net.antibs.client.engine.VpnState
import net.antibs.client.engine.XrayCore
import net.antibs.client.service.AntiBSVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Real engines, no internet: a local Xray VLESS server, a local HTTP «subscription» and a local HTTP target.
 * Subscription import → server list → URL-test ping → connect (proxy mode) → request through the local
 * SOCKS and HTTP inbounds → traffic counters → disconnect.
 */
class EndToEndTest {
    private fun http(port: Int, body: (String) -> Pair<Int, String>): com.sun.net.httpserver.HttpServer =
        com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext("/") { ex -> val (c, b) = body(ex.requestURI.path); val bytes = b.toByteArray(); ex.sendResponseHeaders(c, if (bytes.isEmpty()) -1 else bytes.size.toLong()); if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }; ex.close() }
            start()
        }

    @Test fun subscriptionPingConnectDisconnect() = runBlocking {
        assumeTrue("engines missing", Engines.available("xray"))
        val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
        val srvPort = Engines.freePort(); val target = Engines.freePort(); val subPort = Engines.freePort()
        val srvCfg = File(Paths.run, "e2e-server.json").apply { writeText("""
            {"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$srvPort,"protocol":"vless","settings":{"clients":[{"id":"$uuid"}],"decryption":"none"},"streamSettings":{"network":"tcp","security":"none"}}],
             "outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}""".trimIndent()) }
        val server = Engines.start("xray", listOf("run", "-c", srvCfg.path), File(Paths.logs, "e2e-server.log"))
        val web = http(target) { 204 to "" }
        val links = "vless://$uuid@127.0.0.1:$srvPort?type=tcp&security=none&encryption=none#%F0%9F%87%A9%F0%9F%87%AA%20Local%20DE\n" +
            "vless://$uuid@127.0.0.1:1?type=tcp&security=none&encryption=none#%F0%9F%87%B3%F0%9F%87%B1%20Dead%20NL\n"
        val sub = http(subPort) { 200 to Base64.getEncoder().encodeToString(links.toByteArray()) }
        try {
            assertTrue(Engines.waitPort(srvPort, server, 5000))
            val app = android.app.Application.instance
            val store = AppStore(app)
            store.update { it.copy(bypassLan = false, adv = it.adv.copy(mode = "proxy", socksPort = Engines.freePort(), httpPort = Engines.freePort(),
                pingUrl = "http://127.0.0.1:$target/generate_204", logLevel = "warning")) }
            val id = store.addSubscription("http://127.0.0.1:$subPort/sub")
            val f = SubscriptionSync.fetchAndApply(store, id)!!
            assertTrue("fetch: ${f.result.exceptionOrNull()}", f.result.isSuccess)
            val hosts = store.servers().filter { it.subscriptionId == id && it.xray?.protocol == "vless" }
            assertEquals(2, hosts.size)
            val good = hosts.first { it.name.contains("Local") }
            val bad = hosts.first { it.name.contains("Dead") }
            // real URL test through a throw-away xray
            val plan = net.antibs.client.data.XrayConfigBuilder.plan(good, store.servers(), net.antibs.client.data.XrayConfigBuilder.Options(bypassLan = false))
            val ms = XrayCore.measureDelayEx(app, plan.xray, "http://127.0.0.1:$target/generate_204", "GET", 0, 4000)
            assertTrue("ping $ms", ms > 0)
            val planBad = net.antibs.client.data.XrayConfigBuilder.plan(bad, store.servers(), net.antibs.client.data.XrayConfigBuilder.Options(bypassLan = false))
            assertEquals(-1L, XrayCore.measureDelayEx(app, planBad.xray, "http://127.0.0.1:$target/generate_204", "GET", 0, 3000))
            // connect
            store.selectServer(good.id)
            AntiBSVpnService.connect(app)
            var waited = 0
            while (VpnState.status.value.state != ConnState.CONNECTED && waited < 100) { delay(100); waited++ }
            assertEquals(VpnState.status.value.message, ConnState.CONNECTED, VpnState.status.value.state)
            val a = store.snapshot().adv
            val r = TunnelProbe.check(a.socksPort, "http://127.0.0.1:$target/x", "GET", 4000)
            assertTrue("socks: $r", r is TunnelProbe.Result.Ok)
            val viaHttp = java.net.URL("http://127.0.0.1:$target/y").openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", a.httpPort))) as java.net.HttpURLConnection
            assertEquals(204, viaHttp.responseCode)
            delay(2500)
            assertTrue("traffic counters: ${VpnState.status.value}", VpnState.status.value.upBytes + VpnState.status.value.downBytes > 0)
            AntiBSVpnService.disconnect(app)
            waited = 0
            while (VpnState.status.value.state != ConnState.DISCONNECTED && waited < 50) { delay(100); waited++ }
            assertEquals(ConnState.DISCONNECTED, VpnState.status.value.state)
        } finally {
            AntiBSVpnService.shutdown(); server.destroy(); web.stop(0); sub.stop(0)
        }
    }
}
