package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Share-link parser (vless, vmess, ss, trojan, wireguard, hy2, socks/http — Xray; hysteria, tuic, anytls, ssh, snell,
 * naive — sing-box; qwdtt) modelled after v2rayNG / NekoBox / sing-box share formats,
 * but written without android.* so it can be unit-tested on the JVM.
 */
object LinkParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Percent-decoding that keeps '+' literal (share links use encodeURIComponent semantics). */
    fun pctDecode(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte() && i + 2 < bytes.size) {
                val hex = String(bytes, i + 1, 2, StandardCharsets.US_ASCII)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    out.write(v); i += 3; continue
                }
            }
            out.write(b.toInt()); i++
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    /** Lenient base64 (standard or URL-safe, with or without padding / line breaks). */
    fun b64Decode(input: String): String? {
        val s = input.trim().replace("\r", "").replace("\n", "").replace(" ", "")
        if (s.isEmpty()) return null
        val norm = s.replace('-', '+').replace('_', '/')
        val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class Parts(
        val scheme: String,
        val userInfo: String?,
        val host: String,
        val port: Int,
        val path: String,
        val query: Map<String, String>,
        val fragment: String,
    )

    /** Manual URI split tolerant to raw unicode / spaces in fragments and unescaped JSON in query. */
    fun split(link: String): Parts? {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = link.substring(0, schemeEnd).lowercase()
        var rest = link.substring(schemeEnd + 3)
        var fragment = ""
        val hashIdx = rest.indexOf('#')
        if (hashIdx >= 0) {
            fragment = pctDecode(rest.substring(hashIdx + 1)); rest = rest.substring(0, hashIdx)
        }
        var rawQuery = ""
        val qIdx = rest.indexOf('?')
        if (qIdx >= 0) {
            rawQuery = rest.substring(qIdx + 1); rest = rest.substring(0, qIdx)
        }
        var path = ""
        val atIdx = rest.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) rest.substring(0, atIdx) else null
        var hostPort = if (atIdx >= 0) rest.substring(atIdx + 1) else rest
        val slashIdx = hostPort.indexOf('/')
        if (slashIdx >= 0) {
            path = hostPort.substring(slashIdx); hostPort = hostPort.substring(0, slashIdx)
        }
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: -1
        } else {
            val c = hostPort.lastIndexOf(':')
            if (c >= 0) {
                host = hostPort.substring(0, c); port = hostPort.substring(c + 1).toIntOrNull() ?: -1
            } else {
                host = hostPort; port = -1
            }
        }
        val query = LinkedHashMap<String, String>()
        if (rawQuery.isNotEmpty()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) query[pctDecode(pair)] = "" else query[pctDecode(pair.substring(0, eq))] = pctDecode(pair.substring(eq + 1))
            }
        }
        return Parts(scheme, userInfo, pctDecode(host), port, path, query, fragment)
    }

    fun stableId(prefix: String, raw: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return prefix + md.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Parse one line into a ServerEntry (or null if unsupported / malformed). */
    fun parse(line: String, subscriptionId: String? = null): ServerEntry? {
        val link = line.trim()
        if (link.isEmpty() || link.startsWith("#")) return null
        val lower = link.lowercase()
        return try {
            when {
                lower.startsWith("qwdtt://config") || lower.startsWith("qwdtt:config") -> {
                    val q = parseQwdttUri(link) ?: return null
                    ServerEntry(stableId("q", (subscriptionId ?: "") + link), EngineKind.QWDTT, q.name, subscriptionId, qwdtt = q)
                }
                link.startsWith("{") -> {
                    val p = parseCustomJson(link) ?: return null
                    ServerEntry(stableId("j", (subscriptionId ?: "") + link), EngineKind.XRAY, p.remarks, subscriptionId, xray = p)
                }
                else -> {
                    val p = parseXray(link) ?: return null
                    ServerEntry(stableId("x", (subscriptionId ?: "") + link), EngineKind.XRAY, p.remarks, subscriptionId, xray = p)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseXray(link: String): XrayProfile? {
        val lower = link.lowercase()
        return when {
            lower.startsWith("vless://") -> parseVless(link)
            lower.startsWith("vmess://") -> parseVmess(link)
            lower.startsWith("ss://") -> parseShadowsocks(link)
            lower.startsWith("trojan://") -> parseTrojan(link)
            lower.startsWith("wireguard://") || lower.startsWith("wg://") -> parseWireguard(link)
            lower.startsWith("hy2://") || lower.startsWith("hysteria2://") -> parseHysteria2(link)
            lower.startsWith("hysteria://") || lower.startsWith("hy://") || lower.startsWith("hy1://") -> parseHysteria1(link)
            lower.startsWith("tuic://") -> parseTuic(link)
            lower.startsWith("anytls://") -> parseAnyTls(link)
            lower.startsWith("ssh://") -> parseSsh(link)
            lower.startsWith("snell://") -> parseSnell(link)
            SnellFormats.isSurgeLine(link) -> SnellFormats.surgeLine(link)
            lower.startsWith("naive+https://") || lower.startsWith("naive+quic://") || lower.startsWith("naive://") -> parseNaive(link)
            lower.startsWith("mieru://") -> parseMieru(link)
            // 1.0.19: mieru's own simple share link (mierus://user:pass@host?port=…&protocol=…&profile=…)
            lower.startsWith("mierus://") -> parseMierus(link)
            lower.startsWith("amneziawg://") || lower.startsWith("awg://") -> parseAmneziaWg(link)
            lower.startsWith("vpn://") -> parseAmneziaVpn(link)
            lower.startsWith("http3://") || lower.startsWith("h3://") || lower.startsWith("quic://") -> parseHttp3(link)
            lower.startsWith("socks4://") || lower.startsWith("socks4a://") -> parseSocks4(link)
            lower.startsWith("socks5://") || lower.startsWith("socks://") -> parseSocks(link)
            lower.startsWith("ssr://") -> parseSsr(link)
            lower.startsWith("sq://") || lower.startsWith("shadowquic://") -> parseShadowQuic(link)
            lower.startsWith("trusttunnel://") -> parseTrustTunnel(link)
            lower.startsWith("olcrtc://") -> Olcrtc.parse(link)
            OpenConnectConfig.isLink(link) -> OpenConnectConfig.parse(link)
            lower.startsWith("https://") || lower.startsWith("http://") -> {
                val parts = split(link)
                if (parts != null && (!parts.userInfo.isNullOrEmpty() || parts.path.isEmpty()) && parts.port > 0) parseHttp(link)
                else null
            }
            else -> null
        }
    }

    private fun normSecurity(s: String?): String? = when (s?.lowercase()) {
        "tls" -> "tls"; "reality" -> "reality"; "xtls" -> "tls"; else -> null
    }

    private fun normNetwork(s: String?): String = when (s?.lowercase()) {
        null, "", "raw" -> "tcp"
        "splithttp" -> "xhttp"
        "http", "h2" -> "h2"
        else -> s.lowercase()
    }

    private fun boolParam(q: Map<String, String>): Boolean =
        listOf("insecure", "allowInsecure", "allow_insecure").any { q[it] == "1" || q[it].equals("true", true) }

    private fun fromQuery(base: XrayProfile, q: Map<String, String>, defaultSecurity: String? = null): XrayProfile {
        val network = normNetwork(q["type"])
        return base.copy(
            network = network,
            headerType = q["headerType"],
            host = q["host"]?.ifEmpty { null },
            path = q["path"]?.ifEmpty { null },
            serviceName = q["serviceName"]?.ifEmpty { null },
            authority = q["authority"]?.ifEmpty { null },
            mode = q["mode"]?.ifEmpty { null },
            extra = q["extra"]?.ifEmpty { null },
            security = if (q.containsKey("security")) normSecurity(q["security"]) else defaultSecurity,
            sni = (q["sni"] ?: q["peer"])?.ifEmpty { null },
            fingerprint = q["fp"]?.ifEmpty { null },
            alpn = q["alpn"]?.ifEmpty { null },
            publicKey = q["pbk"]?.ifEmpty { null },
            shortId = q["sid"]?.ifEmpty { null },
            spiderX = q["spx"]?.ifEmpty { null },
            insecure = boolParam(q),
            flow = q["flow"]?.ifEmpty { null } ?: base.flow,
        )
    }

    fun parseVless(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.port <= 0 || p.host.isEmpty() || p.userInfo.isNullOrEmpty()) return null
        val base = XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "vless", server = p.host, port = p.port,
            id = pctDecode(p.userInfo), encryption = p.query["encryption"]?.ifEmpty { null } ?: "none",
        )
        return fromQuery(base, p.query)
    }

    fun parseTrojan(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.port <= 0 || p.host.isEmpty() || p.userInfo.isNullOrEmpty()) return null
        val base = XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "trojan", server = p.host, port = p.port,
            password = pctDecode(p.userInfo),
        )
        return fromQuery(base, p.query, defaultSecurity = "tls")
    }

    fun parseShadowsocks(link: String): XrayProfile? {
        val p = split(link)
        // SIP002: ss://userinfo@host:port[/][?plugin=...]#tag
        if (p != null && p.userInfo != null && p.port > 0 && p.host.isNotEmpty()) {
            val ui = pctDecode(p.userInfo)
            val decoded = if (ui.contains(':')) ui else (b64Decode(ui) ?: return null)
            val idx = decoded.indexOf(':')
            if (idx <= 0) return null
            val method = decoded.substring(0, idx)
            val password = decoded.substring(idx + 1)
            var prof = XrayProfile(
                remarks = p.fragment.ifEmpty { p.host }, protocol = "shadowsocks", server = p.host, port = p.port,
                method = method.lowercase(), password = password, network = "tcp",
            )
            val plugin = p.query["plugin"]
            if (plugin != null && plugin.contains("obfs=http")) {
                val kv = plugin.split(';').mapNotNull { s -> s.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                prof = prof.copy(headerType = "http", host = kv["obfs-host"], path = kv["path"])
            }
            return prof
        }
        // Legacy: ss://base64(method:password@host:port)#tag
        var body = link.substring(5)
        var tag = ""
        val h = body.indexOf('#')
        if (h >= 0) { tag = pctDecode(body.substring(h + 1)); body = body.substring(0, h) }
        val q = body.indexOf('?'); if (q >= 0) body = body.substring(0, q)
        val dec = b64Decode(pctDecode(body).trimEnd('/')) ?: return null
        val at = dec.lastIndexOf('@'); if (at < 0) return null
        val cred = dec.substring(0, at); val hp = dec.substring(at + 1)
        val ci = cred.indexOf(':'); val hi = hp.lastIndexOf(':')
        if (ci <= 0 || hi <= 0) return null
        val host = hp.substring(0, hi).removePrefix("[").removeSuffix("]")
        val port = hp.substring(hi + 1).toIntOrNull() ?: return null
        return XrayProfile(
            remarks = tag.ifEmpty { host }, protocol = "shadowsocks", server = host, port = port,
            method = cred.substring(0, ci).lowercase(), password = cred.substring(ci + 1),
        )
    }

    fun parseVmess(link: String): XrayProfile? {
        val body = link.substring(8)
        if (body.contains('@') && body.contains('?')) {
            // VMess "standard" URI form
            val p = split(link) ?: return null
            if (p.port <= 0 || p.userInfo.isNullOrEmpty()) return null
            val base = XrayProfile(
                remarks = p.fragment.ifEmpty { p.host }, protocol = "vmess", server = p.host, port = p.port,
                id = pctDecode(p.userInfo), method = p.query["encryption"]?.ifEmpty { null } ?: "auto",
            )
            return fromQuery(base, p.query)
        }
        val raw = body.substringBefore('#')
        val txt = b64Decode(raw) ?: return null
        val o: JsonObject = json.parseToJsonElement(txt).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val add = s("add") ?: return null
        val port = s("port")?.toIntOrNull() ?: return null
        val id = s("id") ?: return null
        val net = normNetwork(s("net"))
        val type = s("type")
        val tls = s("tls")
        return XrayProfile(
            remarks = s("ps") ?: add, protocol = "vmess", server = add, port = port, id = id,
            method = s("scy") ?: "auto", network = net,
            headerType = if (net == "tcp") type else null,
            host = s("host"), path = s("path"),
            serviceName = if (net == "grpc") s("path") else null,
            authority = if (net == "grpc") s("host") else null,
            mode = if (net == "grpc") (if (type == "multi") "multi" else "gun") else if (net == "xhttp") type else null,
            security = normSecurity(tls), sni = s("sni"), alpn = s("alpn"), fingerprint = s("fp"),
            insecure = s("allowInsecure") == "1" || s("allowInsecure") == "true",
        )
    }


    /** hy2:// / hysteria2:// — Xray hysteria outbound, version 2. */
    fun parseHysteria2(link: String): XrayProfile? {
        val p = split(link) ?: return null
        val ep = hysteriaEndpoint(link, p)
        val host = ep.first
        if (host.isEmpty()) return null
        val auth = p.userInfo?.let { pctDecode(it) }?.ifEmpty { null } ?: p.query["auth"]?.ifEmpty { null } ?: ""
        val hop = ep.third ?: p.query["mport"]?.ifEmpty { null }
        return XrayProfile(
            remarks = p.fragment.ifEmpty { host },
            protocol = "hysteria",
            server = host,
            port = ep.second,
            password = auth,
            network = "hysteria",
            security = "tls",
            sni = (p.query["sni"] ?: p.query["peer"])?.ifEmpty { null },
            alpn = p.query["alpn"]?.ifEmpty { null } ?: "h3",
            insecure = boolParam(p.query),
            fingerprint = p.query["fp"]?.ifEmpty { null },
            method = p.query["obfs"]?.ifEmpty { null },
            obfsPassword = (p.query["obfs-password"] ?: p.query["obfsPassword"])?.ifEmpty { null },
            portHopping = hop,
            pinnedPeerCertSha256 = (p.query["pinSHA256"] ?: p.query["pin"])?.ifEmpty { null },
            bandwidthUp = (p.query["up"] ?: p.query["upmbps"])?.ifEmpty { null },
            bandwidthDown = (p.query["down"] ?: p.query["downmbps"])?.ifEmpty { null },
        )
    }

    /**
     * Host / first port / optional port-hop spec ("443,5000-6000").
     * Plain split() drops a non-integer port token.
     */
    private fun hysteriaEndpoint(link: String, p: Parts): Triple<String, Int, String?> {
        val raw = rawHostPort(link)
        val host: String
        val spec: String
        if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end < 0) return Triple(p.host, if (p.port > 0) p.port else 443, null)
            host = raw.substring(1, end)
            spec = raw.substring(end + 1).removePrefix(":")
        } else {
            val c = raw.lastIndexOf(':')
            if (c >= 0 && raw.substring(c + 1).firstOrNull()?.isDigit() == true) {
                host = raw.substring(0, c)
                spec = raw.substring(c + 1)
            } else {
                host = raw
                spec = ""
            }
        }
        if (spec.isEmpty()) return Triple(pctDecode(host).ifEmpty { p.host }, if (p.port > 0) p.port else 443, null)
        val first = spec.substringBefore(',').substringBefore('-').toIntOrNull()
        val hop = if (spec.contains(',') || spec.contains('-')) spec else null
        return Triple(pctDecode(host), first ?: 443, hop)
    }

    private fun rawHostPort(link: String): String {
        var rest = link.substringAfter("://").substringBefore('#').substringBefore('?')
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        return rest.substringBefore('/')
    }

    fun parseSocks(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty() || p.port <= 0) return null
        val (user, pass) = userPass(p.userInfo)
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "socks", server = p.host, port = p.port,
            username = user, password = pass, network = "tcp",
        )
    }

    fun parseHttp(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty() || p.port <= 0) return null
        val (user, pass) = userPass(p.userInfo)
        val tls = p.scheme == "https"
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host },
            protocol = if (tls) "https" else "http",
            server = p.host, port = p.port,
            username = user, password = pass, network = "tcp",
            security = if (tls) "tls" else null,
            sni = if (tls) (p.query["sni"]?.ifEmpty { null } ?: p.host) else null,
            insecure = boolParam(p.query),
        )
    }

    /**
     * HTTP/3 CONNECT proxy: http3://user:pass@host:443/path?sni=&host=&insecure=1 (also h3://, Exclave quic://).
     * Dialed by libmhx.so (quic-go http3 CONNECT), TCP only.
     */
    fun parseHttp3(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty()) return null
        val (user, pass) = userPass(p.userInfo)
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "http3", server = p.host, port = if (p.port > 0) p.port else 443,
            username = user, password = pass, network = "h3", security = "tls",
            sni = q(p, "sni", "peer") ?: p.host, insecure = boolParam(p.query),
            host = q(p, "host", "authority"),
            path = p.path.ifEmpty { null }?.takeIf { it != "/" } ?: q(p, "path"),
        )
    }

    /** socks4://[user@]host:port#name, socks4a:// — sing-box socks v4/v4a. */
    fun parseSocks4(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty() || p.port <= 0) return null
        val (user, _) = userPass(p.userInfo)
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "socks4", server = p.host, port = p.port,
            username = user, network = "tcp", method = if (p.scheme == "socks4") "4" else "4a",
        )
    }

    /** ssr://base64url(host:port:protocol:method:obfs:base64pass/?obfsparam=&protoparam=&remarks=) — via mihomo. */
    fun parseSsr(link: String): XrayProfile? {
        val raw = link.substringAfter("://").trim()
        val dec = b64Decode(raw.substringBefore('#')) ?: return null
        val main = dec.substringBefore("/?").substringBefore('?')
        val query = if (dec.contains('?')) dec.substringAfter('?') else ""
        // host may be IPv6 with colons: take the last 5 fields from the right
        val parts = main.split(':')
        if (parts.size < 6) return null
        val pass64 = parts[parts.size - 1]
        val obfs = parts[parts.size - 2]
        val method = parts[parts.size - 3]
        val proto = parts[parts.size - 4]
        val port = parts[parts.size - 5].toIntOrNull() ?: return null
        val host = parts.subList(0, parts.size - 5).joinToString(":").removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return null
        val qm = query.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        fun b(k: String) = qm[k]?.let { b64Decode(it) }?.ifEmpty { null }
        val remarks = b("remarks") ?: link.substringAfter('#', "").let { pctDecode(it) }.ifEmpty { host }
        return XrayProfile(
            remarks = remarks, protocol = "ssr", server = host, port = port,
            password = b64Decode(pass64) ?: pass64, method = method, network = "tcp",
            ssrProtocol = proto, ssrProtocolParam = b("protoparam"),
            ssrObfs = if (obfs == "tls1.2_ticket_fastauth") "tls1.2_ticket_auth" else obfs, ssrObfsParam = b("obfsparam"),
        )
    }

    /** Exclave ShadowQUIC: sq://user:pass@host:port?sni=&udp_mode=stream|datagram&zero_rtt=true&alpn=&cc= */
    fun parseShadowQuic(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty() || p.port <= 0) return null
        val (user, pass) = userPass(p.userInfo)
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "shadowquic", server = p.host, port = p.port,
            username = user ?: q(p, "username", "user"), password = pass ?: q(p, "password"),
            network = "udp", security = "tls", sni = q(p, "sni", "peer"), alpn = q(p, "alpn"),
            udpOverStream = q(p, "udp_mode", "udp-mode")?.let { it != "datagram" },
            zeroRtt = q(p, "zero_rtt", "zero-rtt", "0rtt").let { it == "1" || it.equals("true", true) },
            congestionControl = q(p, "congestion_control", "cc", "congestion-controller"),
        )
    }

    /** trusttunnel://user:pass@host:443?sni=&alpn=&insecure=1&quic=1&fp=chrome (simple URI; Exclave tt:// TLV isn't parsed). */
    fun parseTrustTunnel(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty()) return null
        val (user, pass) = userPass(p.userInfo)
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "trusttunnel", server = p.host, port = if (p.port > 0) p.port else 443,
            username = user, password = pass, network = "tcp", security = "tls",
            sni = q(p, "sni", "peer"), alpn = q(p, "alpn"), insecure = boolParam(p.query),
            fingerprint = q(p, "fp", "fingerprint"),
            quic = q(p, "quic", "http3").let { it == "1" || it.equals("true", true) },
            congestionControl = q(p, "congestion_control", "cc"),
        )
    }

    /**
     * mieru:// — official share is base64(protobuf ClientConfig); also accept a simple form
     * mieru://user:pass@host:port?protocol=UDP&mtu=1400#name (NekoBox-style convenience).
     */
    fun parseMieru(link: String): XrayProfile? {
        val body = link.removePrefix("mieru://").removePrefix("MIERU://")
        // Simple userinfo@host form (no protobuf)
        if (body.contains('@') && !body.all { it.isLetterOrDigit() || it in "+/=" }) {
            val p = split(link) ?: return null
            if (p.host.isEmpty()) return null
            val (user, pass) = userPass(p.userInfo)
            if (user.isNullOrEmpty() || pass.isNullOrEmpty()) return null
            val transport = (q(p, "protocol", "transport") ?: "TCP").uppercase().let { if (it == "UDP") "UDP" else "TCP" }
            return XrayProfile(
                remarks = p.fragment.ifEmpty { p.host }, protocol = "mieru", server = p.host,
                port = if (p.port > 0) p.port else 443,
                username = user, password = pass, network = "tcp",
                mieruTransport = transport,
                mtu = q(p, "mtu")?.toIntOrNull(),
            )
        }
        // Official: mieru://BASE64(protobuf ClientConfig)
        val b64 = body.substringBefore('#').substringBefore('?').trim().replace("\r","").replace("\n","").replace(" ","")
        if (b64.isEmpty()) return null
        val bytes = try {
            val norm = b64.replace('-', '+').replace('_', '/')
            val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) { return null }
        val strings = extractProtoStrings(bytes)
        // Heuristic: protobuf of ClientConfig usually has profileName, user name, password, ip/domain, ports as strings/ints
        // Prefer stashing the original share link for MieruConfigBuilder.
        val frag = link.substringAfter('#', "").let { pctDecode(it) }
        return XrayProfile(
            remarks = frag.ifEmpty { "Mieru" },
            protocol = "mieru",
            server = strings.firstOrNull { it.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || ('.' in it && ' ' !in it && it.length in 4..253) } ?: "—",
            port = 0, // filled from JSON at connect / from form
            username = strings.getOrNull(1),
            password = strings.getOrNull(2),
            network = "tcp",
            mieruTransport = "TCP",
            customJson = link.trim(), // keep official URL for the engine
        )
    }

    /** Best-effort length-delimited protobuf string field harvest (wire type 2). */
    private fun extractProtoStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < bytes.size) {
            val key = bytes[i].toInt() and 0xff
            i++
            val wt = key and 0x7
            when (wt) {
                0 -> { // varint
                    while (i < bytes.size && (bytes[i].toInt() and 0x80) != 0) i++
                    if (i < bytes.size) i++
                }
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    var len = 0; var shift = 0
                    while (i < bytes.size) {
                        val b = bytes[i].toInt() and 0xff; i++
                        len = len or ((b and 0x7f) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                        if (shift > 28) break
                    }
                    if (len < 0 || i + len > bytes.size) break
                    val slice = bytes.copyOfRange(i, i + len)
                    i += len
                    val t = runCatching { String(slice, Charsets.UTF_8) }.getOrNull()
                    if (t != null && t.isNotEmpty() && t.all { c -> c.code in 32..126 || c.isLetterOrDigit() } && t.length in 1..256) {
                        out += t
                    }
                }
                else -> break
            }
        }
        return out
    }


    private fun userPass(userInfo: String?): Pair<String?, String?> {
        if (userInfo.isNullOrEmpty()) return null to null
        val ui = pctDecode(userInfo)
        val cred = if (ui.contains(':')) ui else (b64Decode(ui) ?: ui)
        val i = cred.indexOf(':')
        return if (i >= 0) cred.substring(0, i).ifEmpty { null } to cred.substring(i + 1) else cred.ifEmpty { null } to null
    }

    private fun q(p: Parts, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> p.query[k]?.ifEmpty { null } }

    /** hysteria:// (v1) — sing-box "hysteria" outbound. hysteria://host:port?auth=&peer=&upmbps=&downmbps=&obfs=xplus&obfsParam=&alpn=&mport= */
    fun parseHysteria1(link: String): XrayProfile? {
        val p = split(link) ?: return null
        val ep = hysteriaEndpoint(link, p)
        if (ep.first.isEmpty()) return null
        val auth = q(p, "auth", "auth_str", "auth-str") ?: p.userInfo?.let { pctDecode(it) }?.ifEmpty { null }
        val obfsParam = q(p, "obfsParam", "obfs-param", "obfs_param", "obfs-password")
        val obfsType = q(p, "obfs")
        return XrayProfile(
            remarks = p.fragment.ifEmpty { ep.first }, protocol = "hysteria1", server = ep.first, port = ep.second,
            password = auth, network = "udp", security = "tls",
            sni = q(p, "peer", "sni"),
            alpn = q(p, "alpn") ?: "hysteria",
            insecure = boolParam(p.query),
            // Hysteria 1 "xplus" obfs: the password lives in obfsParam (older links put it into obfs itself)
            method = if (obfsType != null || obfsParam != null) "xplus" else null,
            obfsPassword = obfsParam ?: obfsType?.takeIf { !it.equals("xplus", true) && !it.equals("none", true) },
            portHopping = ep.third ?: q(p, "mport"),
            bandwidthUp = q(p, "upmbps", "up"),
            bandwidthDown = q(p, "downmbps", "down"),
        )
    }

    /** tuic://uuid:password@host:port?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=&allow_insecure=1 (v5). */
    fun parseTuic(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty() || p.port <= 0 || p.userInfo.isNullOrEmpty()) return null
        val ui = pctDecode(p.userInfo)
        val i = ui.indexOf(':')
        val uuid = if (i >= 0) ui.substring(0, i) else ui
        val pass = if (i >= 0) ui.substring(i + 1) else (q(p, "password") ?: "")
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "tuic", server = p.host, port = p.port,
            id = uuid, password = pass, network = "udp", security = "tls",
            sni = q(p, "sni", "peer"),
            alpn = q(p, "alpn") ?: "h3",
            insecure = boolParam(p.query),
            congestionControl = q(p, "congestion_control", "congestion-control", "cc") ?: "bbr",
            udpRelayMode = q(p, "udp_relay_mode", "udp-relay-mode") ?: "native",
        )
    }

    /** anytls://password@host:port/?sni=&insecure=1&fp=chrome */
    fun parseAnyTls(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty() || p.port <= 0) return null
        val pass = p.userInfo?.let { pctDecode(it) }?.ifEmpty { null } ?: q(p, "password") ?: return null
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "anytls", server = p.host, port = p.port,
            password = pass, network = "tcp", security = "tls",
            sni = q(p, "sni", "peer"), insecure = boolParam(p.query),
            fingerprint = q(p, "fp", "fingerprint"), alpn = q(p, "alpn"),
        )
    }

    /** ssh://user:password@host:22?private_key=<pem>&host_key=<key>&passphrase= (NekoBox / v2rayN style). */
    fun parseSsh(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty()) return null
        val (user, pass) = userPass(p.userInfo)
        val pk = q(p, "private_key", "privateKey", "pk", "key")?.let { k ->
            if (k.contains("BEGIN")) k else (b64Decode(k)?.takeIf { it.contains("BEGIN") } ?: k)
        }
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "ssh", server = p.host, port = if (p.port > 0) p.port else 22,
            username = user ?: q(p, "user", "username") ?: "root",
            password = pass ?: q(p, "password"),
            network = "tcp",
            privateKey = pk,
            privateKeyPassphrase = q(p, "private_key_passphrase", "passphrase"),
            hostKey = q(p, "host_key", "hostKey", "hk", "public_key"),
        )
    }

    /** snell://psk@host:port?version=4&obfs=http&obfs-host=bing.com (userinfo may be base64 "method:psk"); v6: &mode=unshaped&reuse=true. */
    fun parseSnell(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty() || p.port <= 0) return null
        var psk = q(p, "psk")
        if (psk == null && !p.userInfo.isNullOrEmpty()) {
            val ui = pctDecode(p.userInfo)
            psk = if (ui.contains(':')) ui.substringAfter(':')
            else b64Decode(ui)?.takeIf { it.contains(':') && it.all { c -> c.code in 32..126 } }?.substringAfter(':') ?: ui
        }
        if (psk.isNullOrEmpty()) return null
        // obfs can also arrive SIP003-style: plugin=obfs-local;obfs=http;obfs-host=...
        val plugin = q(p, "plugin")?.split(';')?.mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()
        val obfs = (q(p, "obfs", "obfs_mode") ?: plugin["obfs"])?.lowercase()?.takeIf { it != "none" && it != "off" }
        val ver = q(p, "version", "v")?.toIntOrNull() ?: 4
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "snell", server = p.host, port = p.port,
            password = psk, network = "tcp",
            // 2.0.45: Snell v6 has no simple-obfs (PSK-derived shaping); `mode` / `reuse` go to mihomo
            headerType = if (ver >= 6) null else obfs,
            host = if (ver >= 6) null else q(p, "obfs-host", "obfs_host", "host") ?: plugin["obfs-host"],
            protoVersion = ver,
            snellMode = q(p, "mode")?.lowercase()?.takeIf { ver >= 6 && it in ProtocolSupport.SNELL_V6_MODES && it != "default" },
            snellReuse = q(p, "reuse").let { it.equals("true", true) || it == "1" },
        )
    }

    /** naive+https://user:pass@host:443?padding=true&extra-headers=... / naive+quic:// — sing-box naive (cronet). */
    fun parseNaive(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty()) return null
        val (user, pass) = userPass(p.userInfo)
        val quic = p.scheme == "naive+quic" || q(p, "quic") == "1" || q(p, "quic").equals("true", true)
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "naive", server = p.host, port = if (p.port > 0) p.port else 443,
            username = user, password = pass, network = if (quic) "quic" else "tcp", security = "tls",
            sni = q(p, "sni", "peer"),
            extra = q(p, "extra-headers", "extra_headers"),
        )
    }

    /** Parsed so the chip/form can show it, but [XrayProfile.unsupportedReason] blocks connect. */
    fun parseUnsupported(link: String, protocol: String): XrayProfile? {
        val p = split(link)
        val host = p?.host?.ifEmpty { null } ?: "—"
        val port = p?.port?.takeIf { it > 0 } ?: 0
        val (user, pass) = userPass(p?.userInfo)
        return XrayProfile(
            remarks = p?.fragment?.ifEmpty { null } ?: host,
            protocol = protocol,
            server = host,
            port = port,
            username = user,
            password = pass ?: p?.query?.get("password") ?: p?.query?.get("psk"),
            network = "tcp",
            sni = p?.query?.get("sni")?.ifEmpty { null },
            unsupportedReason = ProtocolSupport.unsupportedReason(protocol)
                ?: "Протокол не поддерживается ядром",
        )
    }

    /** A single Xray outbound or a full config object. */
    fun parseCustomJson(text: String): XrayProfile? {
        val el = try { json.parseToJsonElement(text.trim()) } catch (_: Exception) { return null }
        if (el !is JsonObject) return null
        if (!el.containsKey("protocol") && !el.containsKey("outbounds")) return null
        fun s(k: String) = el[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val name = s("remarks") ?: s("tag") ?: "Свой JSON"
        return XrayProfile(remarks = name, protocol = "custom", server = "", port = 0, customJson = text.trim(), network = "tcp")
    }

    /** mierus://user:pass@host?profile=NAME&mtu=1400&multiplexing=MULTIPLEXING_LOW&port=20100&protocol=TCP#NAME */
    fun parseMierus(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.host.isEmpty()) return null
        val (user, pass) = userPass(p.userInfo)
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) return null
        val q = p.query
        // port may be "20100" or a range "20100-20200" (first port is used)
        val port = if (p.port > 0) p.port else q["port"]?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
        if (port !in 1..65535) return null
        val transport = (q["protocol"] ?: "TCP").uppercase().let { if (it == "UDP") "UDP" else "TCP" }
        val name = p.fragment.ifEmpty { q["profile"]?.replace('+', ' ').orEmpty() }.ifEmpty { p.host }
        return XrayProfile(
            remarks = name, protocol = "mieru", server = p.host, port = port,
            username = user, password = pass, network = "tcp",
            mieruTransport = transport, mtu = q["mtu"]?.toIntOrNull(),
            mieruMultiplexing = q["multiplexing"]?.ifEmpty { null },
            mieruHandshake = q["handshake-mode"]?.ifEmpty { null },
        )
    }

    private val AWG_KEYS = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")

    /** amneziawg://PRIVATE_KEY@host:port?publickey=…&presharedkey=…&address=…&mtu=…&jc=…&h1=…#name */
    fun parseAmneziaWg(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.port <= 0 || p.host.isEmpty() || p.userInfo.isNullOrEmpty()) return null
        val q = p.query.mapKeys { it.key.lowercase() }
        val awg = AWG_KEYS.mapNotNull { k -> q[k]?.trim()?.ifEmpty { null }?.let { k to it } }.toMap()
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "amneziawg", server = p.host, port = p.port,
            secretKey = pctDecode(p.userInfo).replace(' ', '+'),
            publicKey = (q["publickey"] ?: q["pbk"])?.replace(' ', '+'),
            preSharedKey = (q["presharedkey"] ?: q["psk"])?.replace(' ', '+')?.ifEmpty { null },
            localAddress = (q["address"] ?: q["ip"])?.ifEmpty { null },
            mtu = q["mtu"]?.toIntOrNull(),
            network = "tcp",
            awg = awg,
        )
    }

    /**
     * AmneziaVPN share key: vpn:// + base64url(qCompress(JSON)) — 4-byte big-endian length + zlib. Supported
     * containers: amnezia-awg / amnezia-wireguard (wg-quick text in last_config.config) and amnezia-xray
     * (full Xray JSON in last_config → «Свой JSON»).
     */
    fun parseAmneziaVpn(link: String): XrayProfile? {
        val b64 = link.trim().substring(6).substringBefore('#').trim()
        val raw = try {
            val norm = b64.replace('-', '+').replace('_', '/')
            Base64.getDecoder().decode(norm + "=".repeat((4 - norm.length % 4) % 4))
        } catch (_: IllegalArgumentException) { return null }
        val text = inflateQt(raw) ?: return null
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val frag = link.substringAfter('#', "").let { pctDecode(it) }
        val host = root.s("hostName").orEmpty()
        val containers = (root["containers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val def = root.s("defaultContainer")
        val ordered = containers.sortedBy { if (it.s("container") == def) 0 else 1 }
        for (c in ordered) {
            val kind = c.s("container").orEmpty()
            val inner = (c["awg"] ?: c["wireguard"] ?: c["xray"]) as? JsonObject ?: continue
            val lastRaw = inner.s("last_config") ?: continue
            val last = try { json.parseToJsonElement(lastRaw) as? JsonObject } catch (_: Exception) { null } ?: continue
            if (kind.contains("xray")) {
                val name = frag.ifEmpty { root.s("description")?.ifBlank { null } ?: host.ifEmpty { "Amnezia Xray" } }
                return XrayProfile(remarks = name, protocol = "custom", server = host, port = 0, customJson = lastRaw.trim(), network = "tcp")
            }
            val conf = last.s("config") ?: continue
            val wg = WgConfig.parse(conf) ?: continue
            val ini = conf.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }.toMap()
            val awg = AWG_KEYS.mapNotNull { k -> ini[k]?.ifEmpty { null }?.let { k to it } }.toMap()
            val ep = wg.endpoint
            val epHost = ep.substringBeforeLast(':').removePrefix("[").removeSuffix("]").ifEmpty { host }
            val epPort = ep.substringAfterLast(':').toIntOrNull() ?: inner.s("port")?.toIntOrNull() ?: 0
            val proto = if (awg.isNotEmpty() || kind.contains("awg")) "amneziawg" else "wireguard"
            val name = frag.ifEmpty { listOfNotNull(root.s("description")?.ifBlank { null }, host.ifBlank { null }).joinToString(" · ").ifEmpty { "AmneziaWG" } }
            return XrayProfile(
                remarks = name, protocol = proto, server = epHost, port = epPort,
                secretKey = wg.privateKey, publicKey = wg.publicKey, preSharedKey = wg.preSharedKey?.ifEmpty { null },
                localAddress = wg.addresses.joinToString(",").ifEmpty { null }, mtu = wg.mtu,
                network = "tcp", awg = if (proto == "amneziawg") awg else null,
            )
        }
        return null
    }

    /** Qt qCompress payload: 4-byte big-endian size + zlib stream; plain zlib accepted too. */
    private fun inflateQt(raw: ByteArray): String? {
        fun inflate(b: ByteArray): String? = try {
            val inf = java.util.zip.Inflater()
            inf.setInput(b)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            out.toString("UTF-8").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
        if (raw.size > 4) inflate(raw.copyOfRange(4, raw.size))?.let { return it }
        return inflate(raw)
    }

    fun parseWireguard(link: String): XrayProfile? {
        val p = split(link) ?: return null
        if (p.port <= 0 || p.host.isEmpty() || p.userInfo.isNullOrEmpty()) return null
        val q = p.query
        return XrayProfile(
            remarks = p.fragment.ifEmpty { p.host }, protocol = "wireguard", server = p.host, port = p.port,
            secretKey = pctDecode(p.userInfo),
            publicKey = q["publickey"] ?: q["publicKey"] ?: q["pbk"],
            preSharedKey = (q["presharedkey"] ?: q["preSharedKey"] ?: q["psk"])?.ifEmpty { null },
            localAddress = (q["address"] ?: q["ip"])?.ifEmpty { null },
            reserved = q["reserved"]?.ifEmpty { null },
            mtu = q["mtu"]?.toIntOrNull(),
            network = "tcp",
        )
    }

    /** qwdtt://config?name=..&peer=..&hashes=..&workers=..&port=..&pass=.. (SpaceNeuroX client / LucX ClientURI). */
    fun parseQwdttUri(link: String): QwdttProfile? {
        val norm = link.trim().replaceFirst(Regex("^qwdtt:config", RegexOption.IGNORE_CASE), "qwdtt://config")
        val qIdx = norm.indexOf('?'); if (qIdx < 0) return null
        val q = LinkedHashMap<String, String>()
        for (pair in norm.substring(qIdx + 1).substringBefore('#').split('&')) {
            val eq = pair.indexOf('='); if (eq <= 0) continue
            // url.Values.Encode() uses '+' for spaces -> decode it here (form encoding)
            q[pair.substring(0, eq)] = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }
        val peer = q["peer"]?.trim().orEmpty(); if (peer.isEmpty()) return null
        return QwdttProfile(
            name = q["name"]?.ifBlank { null } ?: "qWDTT",
            peer = peer,
            hashes = q["hashes"].orEmpty(),
            workers = q["workers"]?.toIntOrNull()?.takeIf { it > 0 } ?: 9,
            port = q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 9000,
            password = q["pass"] ?: q["password"] ?: "",
        )
    }

    /** Extract flag emoji (regional-indicator pair) from a name, if any. */
    fun flagOf(name: String): String? {
        val cps = name.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) {
                return String(cps, i, 2)
            }
        }
        return null
    }
}
