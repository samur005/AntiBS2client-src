package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Subscription body parser. Auto-detects:
 *  - qWDTT JSON (LucX panel internal/sub/qwdtt_app_sub.go → tunnel.QwdttSubscription):
 *      {"subscriptionName":"…","description":"Подписка · до 31.12.2026","trafficUsedMb":12.5,
 *       "trafficLimitMb":51200,"version":1,"updatedAt":"2026-10-08",
 *       "profiles":[{"name":"NL","peer":"host:port","hashes":"…","workers":9,"port":9000,"password":"…"}]}
 *    also a bare JSON array of profiles, "servers" instead of "profiles", base64-wrapped JSON,
 *    and qwdtt://config?… links (single or one per line).
 *  - Remnawave / v2ray style: base64 or plain text, one share link per line.
 */
object SubscriptionParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class UserInfo(val upload: Long = 0, val download: Long = 0, val total: Long = 0, val expire: Long = 0)

    /** subscription-userinfo: upload=123; download=456; total=789; expire=1700000000 */
    fun parseUserInfo(header: String?): UserInfo {
        if (header.isNullOrBlank()) return UserInfo()
        val m = header.split(';').mapNotNull { part ->
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else null
        }.toMap()
        fun l(k: String) = m[k]?.toBigDecimalOrNull()?.toLong() ?: 0L
        return UserInfo(l("upload"), l("download"), l("total"), l("expire"))
    }

    /** "base64:<b64>" -> decoded UTF-8, otherwise the trimmed value. Null/blank -> "". */
    fun decodeHeaderValue(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val h = raw.trim()
        if (h.startsWith("base64:", ignoreCase = true)) return LinkParser.b64Decode(h.substring(7))?.trim().orEmpty()
        return h
    }

    /** profile-title may be plain (optionally %-encoded) or "base64:<b64>" */
    fun parseProfileTitle(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val h = header.trim()
        val v = if (h.startsWith("base64:", ignoreCase = true)) LinkParser.b64Decode(h.substring(7))?.trim() else LinkParser.pctDecode(h).trim()
        return v?.ifBlank { null }
    }

    /** content-disposition: attachment; filename="x" | filename=x | filename*=UTF-8''x */
    fun parseContentDispositionFilename(header: String?): String {
        if (header.isNullOrBlank()) return ""
        var plain = ""
        var ext = ""
        header.split(';').forEach { part ->
            val kv = part.trim().split('=', limit = 2)
            if (kv.size != 2) return@forEach
            val k = kv[0].trim().lowercase()
            val v = kv[1].trim().trim('"')
            when (k) {
                "filename" -> plain = v
                "filename*" -> ext = LinkParser.pctDecode(v.substringAfter("''", v))
            }
        }
        return ext.ifBlank { plain }.trim()
    }

    /**
     * All Remnawave (3.4.x) subscription response headers we understand:
     * subscription-userinfo, profile-title, profile-update-interval, profile-web-page-url,
     * support-url, announce, routing, content-disposition.
     */
    data class SubHeaders(
        val userInfo: UserInfo = UserInfo(),
        val title: String? = null,
        val updateIntervalHours: Int = 0,
        val webPageUrl: String = "",
        val supportUrl: String = "",
        val announce: String = "",
        val routing: String = "",
        val fileName: String = "",
    )

    /** Header names are matched case-insensitively; null keys (HTTP status line) are ignored. */
    fun parseHeaders(headers: Map<String?, String?>): SubHeaders {
        val h = HashMap<String, String>()
        headers.forEach { (k, v) -> if (k != null && v != null) h[k.trim().lowercase()] = v }
        return SubHeaders(
            userInfo = parseUserInfo(h["subscription-userinfo"]),
            title = parseProfileTitle(h["profile-title"]),
            updateIntervalHours = h["profile-update-interval"]?.trim()?.toBigDecimalOrNull()?.toInt()?.coerceAtLeast(0) ?: 0,
            webPageUrl = decodeHeaderValue(h["profile-web-page-url"]),
            supportUrl = decodeHeaderValue(h["support-url"]),
            announce = decodeHeaderValue(h["announce"]),
            routing = decodeHeaderValue(h["routing"]),
            fileName = parseContentDispositionFilename(h["content-disposition"]),
        )
    }

    fun parse(body: String, subscriptionId: String?, userInfoHeader: String? = null, titleHeader: String? = null): RemoteSubscription? =
        parse(body, subscriptionId, SubHeaders(userInfo = parseUserInfo(userInfoHeader), title = parseProfileTitle(titleHeader)))

    fun parse(body: String, subscriptionId: String?, headers: SubHeaders): RemoteSubscription? =
        parseBody(body, subscriptionId, headers)?.let { it.copy(
            announce = headers.announce,
            supportUrl = headers.supportUrl,
            webPageUrl = headers.webPageUrl,
            updateIntervalHours = headers.updateIntervalHours,
            // header wins; otherwise routing found in the body (happ://routing line, #routing meta, Xray JSON routing)
            routing = headers.routing.ifBlank { it.routing },
            fileName = headers.fileName,
        ) }

    private fun parseBody(body: String, subscriptionId: String?, headers: SubHeaders): RemoteSubscription? {
        val ui = headers.userInfo
        val title = headers.title
        var text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return null
        var bodyRouting: String? = null
        SubscriptionRouting.extractFromBody(text).let { (t, r) -> text = t.trim(); bodyRouting = r }
        if (!looksLikePlain(text)) {
            val dec = LinkParser.b64Decode(text)
            if (dec != null && looksLikePlain(dec.trim())) text = dec.trim()
        }
        SubscriptionRouting.extractFromBody(text).let { (t, r) -> text = t.trim(); if (bodyRouting == null) bodyRouting = r }
        if (text.isEmpty()) return null
        if (text.startsWith("{") || text.startsWith("[")) {
            parseQwdttJson(text, subscriptionId)?.let { q ->
                return q.copy(
                    title = q.title ?: title,
                    upload = ui.upload, download = ui.download, total = ui.total, expireEpoch = ui.expire,
                )
            }
            // 1.0.19: Xray JSON subscription (3x-ui / Remnawave / LucX «json»): an array of full Xray configs
            // (or one config) — each becomes a «Свой JSON» host named by its "remarks".
            val xs = parseXrayJsonConfigs(text, subscriptionId)
            if (xs.isEmpty()) return null
            val xr = bodyRouting ?: runCatching {
                val root = json.parseToJsonElement(text)
                val cfgs = when (root) { is JsonArray -> root.mapNotNull { it as? JsonObject }; is JsonObject -> listOf(root); else -> emptyList() }
                SubscriptionRouting.fromXrayConfigs(cfgs, title)
            }.getOrNull()
            return RemoteSubscription(
                kind = "remnawave", title = title, description = "",
                upload = ui.upload, download = ui.download, total = ui.total, expireEpoch = ui.expire,
                trafficUsedMb = 0.0, trafficLimitMb = 0.0, servers = uniqueIds(xs), routing = xr.orEmpty(),
            )
        }
        val servers = uniqueIds(text.lines().mapNotNull { LinkParser.parse(it, subscriptionId) })
        if (servers.isEmpty()) return null
        val kind = if (servers.all { it.engine == EngineKind.QWDTT }) "qwdtt" else "remnawave"
        return RemoteSubscription(
            kind = kind, title = title, description = "",
            upload = ui.upload, download = ui.download, total = ui.total, expireEpoch = ui.expire,
            trafficUsedMb = 0.0, trafficLimitMb = 0.0, servers = servers, routing = bodyRouting.orEmpty(),
        )
    }

    /** Identical lines would give identical ids (the list and the selection need unique ones): suffix repeats. */
    fun uniqueIds(list: List<ServerEntry>): List<ServerEntry> {
        val seen = HashMap<String, Int>()
        return list.map { s ->
            val n = (seen[s.id] ?: 0) + 1
            seen[s.id] = n
            if (n == 1) s else s.copy(id = "${s.id}-$n")
        }
    }

    private val PLUMBING = setOf("freedom", "blackhole", "dns", "loopback")

    /**
     * Xray JSON subscription → hosts. A config whose only real outbound is a plain proxy (no dialerProxy /
     * proxySettings chaining) is reduced to that outbound, so the app's own inbounds, TUN, routing and DNS
     * apply exactly as for share links; anything more complex is kept as the full config.
     */
    fun parseXrayJsonConfigs(text: String, subscriptionId: String?): List<ServerEntry> {
        val root = try { json.parseToJsonElement(text) } catch (_: Exception) { return emptyList() }
        val configs = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }.filter { it["outbounds"] is JsonArray }
        return configs.mapIndexedNotNull { idx, cfg ->
            val outs = (cfg["outbounds"] as JsonArray).mapNotNull { it as? JsonObject }
            val real = outs.filter { (it["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase() !in PLUMBING }
            if (real.isEmpty()) return@mapIndexedNotNull null
            val main = real.firstOrNull { (it["tag"] as? JsonPrimitive)?.contentOrNull == "proxy" } ?: real.first()
            val chained = real.size > 1 || outs.any { o ->
                val so = (o["streamSettings"] as? JsonObject)?.get("sockopt") as? JsonObject
                so?.get("dialerProxy") != null || o["proxySettings"] != null
            }
            val proto = (main["protocol"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val (server, port) = XrayConfigBuilder.outboundAddress(main)
            val name = cfg.str("remarks", "name", "tag")?.ifBlank { null } ?: listOf(proto.uppercase(), server).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "JSON ${idx + 1}" }
            if (!chained) plaintextProfile(main, name)?.let { prof ->
                if (ProtocolSupport.needsPlaintextHelper(prof)) return@mapIndexedNotNull ServerEntry(
                    id = LinkParser.stableId("j", "${subscriptionId ?: ""}|$main"),
                    engine = EngineKind.XRAY, name = name, subscriptionId = subscriptionId, xray = prof,
                )
            }
            val body = if (chained) {
                JsonObject(cfg + ("outbounds" to JsonArray(outs.map { fixTls(it) }))).toString()
            } else fixTls(main).toString()
            ServerEntry(
                id = LinkParser.stableId("j", "${subscriptionId ?: ""}|$body"),
                engine = EngineKind.XRAY, name = name, subscriptionId = subscriptionId,
                xray = XrayProfile(remarks = name, protocol = "custom", server = server, port = port, customJson = body, network = "tcp",
                    // 1.0.34: panel auto-select (balancer) config — remember which host each outbound tag is
                    balancerTagNames = if (chained && AutoSelect.balancerTagOf(cfg) != null) AutoSelect.tagNames(cfg, configs) else emptyMap()),
            )
        }
    }

    /**
     * 1.0.19: a VLESS / Trojan outbound without TLS as a native profile (Xray 26 refuses it to public addresses;
     * the app dials it through sing-box, see [ProtocolSupport.needsPlaintextHelper]). Null for anything else.
     */
    fun plaintextProfile(o: JsonObject, name: String): XrayProfile? {
        val proto = (o["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return null
        if (proto != "vless" && proto != "trojan") return null
        val ss = o["streamSettings"] as? JsonObject
        val sec = (ss?.get("security") as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
        if (sec.isNotEmpty() && sec != "none") return null
        val (host, port) = XrayConfigBuilder.outboundAddress(o)
        if (host.isEmpty() || port <= 0) return null
        val st = o["settings"] as? JsonObject ?: return null
        val first = ((st["vnext"] ?: st["servers"]) as? JsonArray)?.firstOrNull() as? JsonObject ?: st
        val user = ((first["users"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: first
        fun JsonObject.p(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
        val net = (ss?.p("network") ?: "tcp").lowercase().let { if (it == "raw") "tcp" else it }
        val ws = ss?.get("wsSettings") as? JsonObject
        val hu = ss?.get("httpupgradeSettings") as? JsonObject
        val grpc = ss?.get("grpcSettings") as? JsonObject
        val tcpHeader = ((ss?.get("tcpSettings") as? JsonObject)?.get("header") as? JsonObject)?.p("type")
        val wsHost = ws?.p("host") ?: ((ws?.get("headers") as? JsonObject)?.p("Host"))
        return XrayProfile(
            remarks = name, protocol = proto, server = host, port = port,
            id = if (proto == "vless") user.p("id") else null,
            encryption = if (proto == "vless") user.p("encryption") ?: "none" else null,
            flow = if (proto == "vless") user.p("flow") else null,
            password = if (proto == "trojan") user.p("password") else null,
            network = net,
            path = ws?.p("path") ?: hu?.p("path"),
            host = wsHost ?: hu?.p("host"),
            serviceName = grpc?.p("serviceName"),
            headerType = tcpHeader?.takeIf { it != "none" },
        )
    }

    /**
     * LucX's JSON nests the flag as tlsSettings.settings.allowInsecure, which Xray ignores (then TLS to a server
     * with an SNI-mismatched / self-signed cert fails). Hoist it to tlsSettings.allowInsecure, as share links do.
     */
    fun fixTls(o: JsonObject): JsonObject {
        val ss = o["streamSettings"] as? JsonObject ?: return o
        val tls = ss["tlsSettings"] as? JsonObject ?: return o
        val nested = (tls["settings"] as? JsonObject)?.get("allowInsecure") as? JsonPrimitive ?: return o
        if (tls.containsKey("allowInsecure")) return o
        val fixedTls = JsonObject(tls - "settings" + ("allowInsecure" to nested))
        return JsonObject(o + ("streamSettings" to JsonObject(ss + ("tlsSettings" to fixedTls))))
    }

    private fun looksLikePlain(t: String): Boolean =
        t.startsWith("{") || t.startsWith("[") || t.contains("://") || t.startsWith("qwdtt:")

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            val c = v.contentOrNull ?: continue
            return c
        }
        return null
    }

    private fun JsonObject.dbl(vararg keys: String): Double? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            v.doubleOrNull?.let { return it }
            v.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.int(vararg keys: String): Int? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            v.intOrNull?.let { return it }
            v.contentOrNull?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** Mirrors SpaceNeuroX SubscriptionImport.parseProfileObject + LucX QwdttSubProfile json tags. */
    fun parseQwdttProfile(o: JsonObject): QwdttProfile? {
        val peer = o.str("peer")?.trim().orEmpty()
        if (peer.isEmpty()) return null
        return QwdttProfile(
            name = o.str("name")?.ifBlank { null } ?: "qWDTT",
            peer = peer,
            hashes = o.str("hashes", "vkHashes").orEmpty(),
            // LucX emits 0 for "not set" (strconv.Atoi failure) -> fall back to app defaults
            workers = o.int("workers", "workersPerHash")?.takeIf { it > 0 } ?: 9,
            port = o.int("port", "listenPort")?.takeIf { it in 1..65535 } ?: 9000,
            password = o.str("password", "pass").orEmpty(),
        )
    }

    fun parseQwdttJson(text: String, subscriptionId: String?): RemoteSubscription? {
        val root: JsonElement = try { json.parseToJsonElement(text) } catch (_: Exception) { return null }
        val (meta, arr) = when (root) {
            is JsonObject -> root to ((root["profiles"] as? JsonArray) ?: (root["servers"] as? JsonArray) ?: return null)
            is JsonArray -> (root.firstOrNull() as? JsonObject) to root
            else -> return null
        }
        val profiles = arr.mapNotNull { (it as? JsonObject)?.let(::parseQwdttProfile) }
        if (profiles.isEmpty()) return null
        val servers = profiles.mapIndexed { idx, p ->
            ServerEntry(
                id = LinkParser.stableId("q", "${subscriptionId ?: ""}|$idx|${p.name}|${p.peer}"),
                engine = EngineKind.QWDTT, name = p.name, subscriptionId = subscriptionId, qwdtt = p,
            )
        }
        val m = meta ?: JsonObject(emptyMap())
        val title = if (root is JsonObject) m.str("subscriptionName", "groupName")?.ifBlank { null } else m.str("groupName")?.ifBlank { null }
        return RemoteSubscription(
            kind = "qwdtt",
            title = title,
            description = if (root is JsonObject) m.str("description", "info").orEmpty() else "",
            upload = 0, download = 0, total = 0, expireEpoch = 0,
            trafficUsedMb = if (root is JsonObject) m.dbl("trafficUsedMb", "trafficMb") ?: 0.0 else 0.0,
            trafficLimitMb = if (root is JsonObject) m.dbl("trafficLimitMb", "trafficLimit") ?: 0.0 else 0.0,
            servers = servers,
        )
    }

    /** "Подписка · до 31.12.2026" → epoch seconds of that date (end of day, UTC+7 as in LucX), else 0. */
    fun expiryFromDescription(desc: String): Long {
        val m = Regex("(\\d{2})\\.(\\d{2})\\.(\\d{4})").find(desc) ?: return 0
        val (d, mo, y) = m.destructured
        return try {
            java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(23, 59, 59)
                .toEpochSecond(java.time.ZoneOffset.ofHours(7))
        } catch (_: Exception) { 0 }
    }
}
