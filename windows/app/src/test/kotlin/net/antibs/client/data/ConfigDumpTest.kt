package net.antibs.client.data

import org.junit.Test
import java.io.File

/** Dumps generated Xray configs to app/build/xray-configs so they can be validated with `xray run -test`. */
class ConfigDumpTest {
    @Test fun dumpConfigs() {
        val out = File("build/xray-configs").apply { deleteRecursively(); mkdirs() }
        val links = javaClass.classLoader.getResourceAsStream("remnawave_links.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        links.forEachIndexed { i, l ->
            val p = LinkParser.parseXray(l)!!
            val kind = "${p.protocol}-${p.network}-${p.security ?: "none"}"
            File(out, "remnawave_%02d_%s.json".format(i, kind)).writeText(XrayConfigBuilder.forProfile(p))
        }
        val synthetic = mapOf(
            "synthetic_vless_xhttp" to "vless://11111111-1111-1111-1111-111111111111@x.example.com:443?encryption=none&type=xhttp&path=%2Fxh&host=x.example.com&mode=stream-one&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%2C%22noGRPCHeader%22%3Afalse%7D&security=tls&sni=x.example.com&fp=chrome&alpn=h2#XHTTP",
            "synthetic_vless_xhttp_reality" to "vless://11111111-1111-1111-1111-111111111111@x.example.com:443?encryption=none&type=xhttp&path=%2Fxh&mode=auto&security=reality&sni=www.example.com&fp=chrome&pbk=SpT9krTmosNSVD1pCsTfrlDUE4Cyht4q0jWkVnP0FsU&sid=9b69070be71f1046#XHTTP-REALITY",
            "synthetic_vless_ws" to "vless://11111111-1111-1111-1111-111111111111@w.example.com:443?encryption=none&type=ws&path=%2Fws%3Fed%3D2048&host=w.example.com&security=tls&sni=w.example.com#WS",
            "synthetic_vless_grpc" to "vless://11111111-1111-1111-1111-111111111111@g.example.com:443?encryption=none&type=grpc&serviceName=grpcsvc&mode=multi&security=tls&sni=g.example.com#GRPC",
            "synthetic_vless_httpupgrade" to "vless://11111111-1111-1111-1111-111111111111@u.example.com:443?encryption=none&type=httpupgrade&path=%2Fup&host=u.example.com&security=tls#HU",
            "synthetic_vmess_ws" to "vmess://eyJhZGQiOiJob3N0LmV4YW1wbGUiLCJwb3J0IjoiNDQzIiwiaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEiLCJhaWQiOiIwIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJob3N0LmV4YW1wbGUiLCJwYXRoIjoiL3dzIiwidGxzIjoidGxzIiwic24iOiJob3N0LmV4YW1wbGUiLCJwcyI6IlZNZXNzIFdTIn0=",
            "synthetic_trojan_tcp" to "trojan://secret@t.example.com:443?type=tcp&security=tls&sni=t.example.com#Trojan",
            "synthetic_ss_aes" to "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388#SS",
            "synthetic_wireguard" to "wireguard://yAnz5TF%2BlXXJte14tji3zlMNq%2BhdUo7QWi3zZvaaNmU%3D@wg.example.com:51820?publickey=xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg%3D&address=10.0.0.2%2F32&mtu=1280&reserved=1%2C2%2C3#WG",
        )
        synthetic.forEach { (name, l) -> File(out, "$name.json").writeText(XrayConfigBuilder.forProfile(LinkParser.parseXray(l)!!)) }
        val wg = WgConfig.parse("""
            [Interface]
            PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hdUo7QWi3zZvaaNmU=
            Address = 10.70.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            [Peer]
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 127.0.0.1:9000
            PersistentKeepalive = 25
        """.trimIndent())!!
        File(out, "qwdtt_wireguard_via_turn.json").writeText(XrayConfigBuilder.forQwdtt(wg))

        // 1.0.6 settings: fragment/noise/mux/inbounds/local DNS/routing/log
        val adv = AdvancedSettings(noise = true, muxConcurrency = 16, muxXudpConcurrency = 8, muxXudpQuic = "reject", httpInbound = true,
            localDns = true, socksPort = 10808)
        val routing = RoutingProfile("r", "T", globalProxy = false, rules = listOf(
            RoutingRule("block", "domain", "geosite:category-ads-all"), RoutingRule("direct", "domain", "yandex.ru"),
            RoutingRule("direct", "ip", "geoip:ru"), RoutingRule("proxy", "port", "443"), RoutingRule("direct", "ip", "10.0.0.0/8"),
        ))
        val opt = XrayConfigBuilder.Options(mux = true, fragmentation = true, adv = adv, routing = routing, logDir = "/tmp/antibs-logs", logLevel = "info")
        val ws = LinkParser.parseXray(synthetic["synthetic_vless_ws"]!!)!!
        File(out, "v106_vless_ws_frag_mux_routing.json").writeText(XrayConfigBuilder.forProfile(ws, opt))
        val reality = LinkParser.parseXray(synthetic["synthetic_vless_xhttp_reality"]!!)!!
        File(out, "v106_vless_reality_frag.json").writeText(XrayConfigBuilder.forProfile(reality, opt))
        val hy2 = LinkParser.parseXray("hy2://pw@h.example.com:443?sni=h.example.com#H")!!
        File(out, "v106_hy2_noise.json").writeText(XrayConfigBuilder.forProfile(hy2, opt))
        val e = ServerEntry("w", EngineKind.XRAY, "W", xray = ws)
        val e2 = ServerEntry("r", EngineKind.XRAY, "R", xray = reality)
        val rp = routing.copy(proxyServerIds = listOf("r"), globalProxy = true)
        File(out, "v106_routing_proxy_server.json").writeText(XrayConfigBuilder.plan(e, listOf(e, e2), opt.copy(routing = rp)).xray)
        val http = ServerEntry("h", EngineKind.XRAY, "H", xray = XrayProfile("H", "http", "p.example.com", 8080, username = "u", password = "p"))
        File(out, "v106_http_dns_tcp.json").writeText(XrayConfigBuilder.plan(http, listOf(http), opt).xray)
        val snell = ServerEntry("s", EngineKind.XRAY, "S", xray = LinkParser.parseXray("snell://psk@s.example.com:9102?version=2#S")!!)
        File(out, "v106_snell2_via_mihomo.json").writeText(XrayConfigBuilder.plan(snell, listOf(snell), opt).xray)
        File(out, "v106_dns_json.json").writeText(XrayConfigBuilder.forProfile(ws, opt.copy(adv = adv.copy(dnsFromJson = true,
            dnsJson = "{\"servers\":[\"https://1.1.1.1/dns-query\",{\"address\":\"8.8.8.8\",\"domains\":[\"geosite:google\"]}]}"))))
        File("build/mhx-configs").apply { deleteRecursively(); mkdirs() }.let { dir ->
            File(dir, "snell2.json").writeText(XrayConfigBuilder.plan(snell, listOf(snell), opt).mihomo!!)
            val all = listOf(
                "ssr" to XrayProfile("SSR", "ssr", "ssr.example.com", 8388, password = "p", method = "aes-256-cfb", ssrProtocol = "auth_aes128_md5", ssrObfs = "tls1.2_ticket_auth", ssrObfsParam = "cdn.example.com"),
                "tt" to XrayProfile("TT", "trusttunnel", "tt.example.com", 443, username = "u", password = "p", sni = "tt.example.com"),
                "sq" to XrayProfile("SQ", "shadowquic", "sq.example.com", 443, username = "u", password = "p", alpn = "h3"),
                "h3" to XrayProfile("H3", "http3", "h3.example.com", 443, username = "u", password = "p", sni = "h3.example.com"),
                "snell3" to XrayProfile("S3", "snell", "s3.example.com", 443, password = "psk", protoVersion = 3, headerType = "http", host = "bing.com"),
            )
            File(dir, "all.json").writeText(MihomoConfigBuilder.toJson(all.mapIndexed { i, (_, p) -> MihomoConfigBuilder.Slot(i, p) }))
        }
    }
}
