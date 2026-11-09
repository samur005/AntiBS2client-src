package net.antibs.client.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tunnel / VPN / ping / interface settings added in 1.0.6 (Happ/Exclave-style screens).
 * Persisted inside AppState (SharedPreferences, read synchronously by the VPN service) and mirrored to DataStore.
 */
@Serializable
data class CustomHoliday(
    val id: String,
    val name: String,
    val emoji: String,
    val month: Int,
    val day: Int,
    val endMonth: Int = 0,
    val endDay: Int = 0,
)

@Serializable
data class AdvancedSettings(
    /** 1.0.55: «Единицы скорости» on the speed card: "bytes" (КБ/с, default, also for old installs) or "bits" (кбит/с). */
    val speedUnits: String = "bytes",
    // Fragment (Xray finalmask "fragment", TCP)
    val fragType: String = "xray",
    val fragPackets: String = "tlshello",
    val fragLength: String = "50-100",
    val fragDelay: String = "10-20",
    val fragMaxSplit: String = "100-200",
    // Noise (Xray finalmask "noise", UDP)
    val noise: Boolean = false,
    val noiseType: String = "array",
    val noisePacket: String = "1A,2B,3C",
    val noiseDelay: String = "10-16",
    val noiseRand: String = "1-8192",
    val noiseRandRange: String = "0-255",
    // Mux
    val muxConcurrency: Int = 8,
    val muxXudpConcurrency: Int = 8,
    /** reject | allow | skip */
    val muxXudpQuic: String = "reject",
    // Inbounds
    val socksPort: Int = 10808,
    val httpInbound: Boolean = false,
    val httpPort: Int = 10809,
    // TUN
    val tunMtu: Int = 1500,
    /** "Блок привязки к туннелю": true = apps can't bind around the VPN (no allowBypass). */
    val tunnelBindBlock: Boolean = false,
    // VPN
    val localDns: Boolean = false,
    val localDnsPort: Int = 10853,
    val dnsFromJson: Boolean = false,
    val dnsJson: String = "",
    val sniffing: Boolean = true,
    /** vpn | proxy */
    val mode: String = "vpn",
    val resolveServers: Boolean = false,
    val excludedRoutes: List<String> = emptyList(),
    // Ping
    /** get | head | tcp | icmp */
    val pingType: String = "get",
    /** default | double | keepalive */
    val pingMode: String = "default",
    val pingTimeoutSec: Int = 4,
    val pingUrl: String = DEFAULT_PING_URL,
    /** time | color */
    val pingDisplay: String = "time",
    /** Автопинг серверов при открытии приложения и после обновления подписок. */
    val autoPingServers: Boolean = false,
    // Interface
    val hideEmptyManual: Boolean = true,
    val logging: Boolean = true,
    /** none | error | warning | info | debug */
    val logLevel: String = "warning",
    val language: String = "system", // 2.0.30: follow device; unsupported → en
    // Per-app proxy
    /** off | include | bypass */
    val perAppMode: String = "off",
    val perAppInclude: List<String> = emptyList(),
    val perAppBypass: List<String> = emptyList(),
    /** 2.0.29: «Российские приложения в обход VPN» (ABS RuApps). Off until the user confirms. */
    val ruAppsOn: Boolean = false,
    val ruAppsAdded: List<String> = emptyList(),
    val ruAppsRemoved: List<String> = emptyList(),
    // Routing
    val routingProfiles: List<RoutingProfile> = emptyList(),
    val activeRoutingId: String? = null,
    // 2.0.15: «Блокировка рекламы» — ABS 3.1.27 port (replaces the 1.0.39–1.0.40 lists / AdGuard file / STUN)
    /** In-tunnel blocking: geosite category-ads-all + ABS ad hosts → «block-ads» (counted), QUIC (UDP 443) → block. */
    val blockAds: Boolean = true,
    /** ABS «Категории» / «Телеметрия» ids, see [AdBlock.CATS]. */
    val adCats: List<String> = emptyList(),
    /** ABS «ДНС-блокировщик рекламы»: when on, [adDnsUrl] replaces the remote DNS. */
    val adDnsOn: Boolean = false,
    val adDnsUrl: String = "",
    /** off | ru | ai | social */
    val trafficPreset: String = "off",
    val ruDns: String = "77.88.8.8",
    // 1.0.40: «Настройки интерфейса» → «Общее»
    val accent: String = "blue",
    val autoConnectOnLaunch: Boolean = false,
    val haptics: Boolean = true,
    /** off | speed | total | both */
    val notifTraffic: String = "both",
    val notifRefreshSec: Int = 2,
    val hideServerIp: Boolean = false,
    /** off | auto | specific-id */
    val seasonalDecor: String = "auto",
    /** User-defined holidays: id, name, emoji, month, day (Gregorian). */
    val customHolidays: List<CustomHoliday> = emptyList(),
    // 2.0.6 (ABS port)
    val seasonalFx: SeasonalFx = SeasonalFx(),
    val subRemain: SubRemainStyle = SubRemainStyle(),
    val serverGrouping: ServerGrouping = ServerGrouping(),
    val pingChip: PingChipStyle = PingChipStyle(),
    val autoPing: AutoPing = AutoPing(),
    // 2.0.7: home variant B cards
    /** 2.0.8: notification when another VPN app takes over (onRevoke). */
    val revokeNotify: Boolean = true,
    val homeUrlTest: Boolean = true,
) {
    val perAppSelected: List<String> get() = if (perAppMode == "bypass") perAppBypass else perAppInclude

    companion object {
        const val DEFAULT_PING_URL = "https://www.gstatic.com/generate_204"
    }
}

/** Range "a-b" or "a" → (a, b); null when malformed. */
fun parseRange(s: String): Pair<Int, Int>? {
    val t = s.trim()
    if (t.isEmpty()) return null
    val parts = t.split('-').map { it.trim() }
    val a = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val b = if (parts.size > 1) parts[1].toIntOrNull() ?: return null else a
    if (a < 0 || b < a) return null
    return a to b
}

@Serializable
data class RoutingRule(
    /** direct | block | proxy */
    val action: String,
    /** domain | ip | process | port */
    val kind: String,
    val value: String,
)

@Serializable
data class RoutingProfile(
    val id: String,
    val name: String,
    /** Group title in the list: subscription name, or «Мои профили». */
    val group: String = MY_GROUP,
    /** Subscription that delivered this profile (routing header), null for manual/imported. */
    val subscriptionId: String? = null,
    val directEnabled: Boolean = true,
    val blockEnabled: Boolean = true,
    val proxyEnabled: Boolean = true,
    /** Server ids the «Прокси» rules go through (first runnable is used; empty = selected server). */
    val proxyServerIds: List<String> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
    /** true: everything not matched → proxy; false: → direct. */
    val globalProxy: Boolean = true,
    /** AsIs | IPIfNonMatch | IPOnDemand */
    val domainStrategy: String = "IPIfNonMatch",
    // 1.0.19: the rest of the Happ routing JSON
    /** Remote (proxied) DNS: DoH URL or IP; empty = app DNS settings. */
    val remoteDns: String = "",
    /** Domestic DNS used for «Напрямую» domains: DoH URL or IP. */
    val domesticDns: String = "",
    val dnsHosts: Map<String, String> = emptyMap(),
    /** Custom geoip.dat / geosite.dat (Happ Geoipurl / Geositeurl). */
    val geoipUrl: String = "",
    val geositeUrl: String = "",
    /** Downloaded copies in filesDir (Xray «ext:<file>:<tag>»), with the tags each file contains. */
    val geoipFile: String = "",
    val geositeFile: String = "",
    val geoipTags: List<String> = emptyList(),
    val geositeTags: List<String> = emptyList(),
    /** Happ LastUpdated (panel-side version stamp). */
    val lastUpdated: String = "",
) {
    /** Same rules/DNS/geo sources — ignores the user's toggles and the downloaded-file bookkeeping. */
    fun sameContentAs(o: RoutingProfile): Boolean =
        name == o.name && rules == o.rules && globalProxy == o.globalProxy && domainStrategy == o.domainStrategy &&
            remoteDns == o.remoteDns && domesticDns == o.domesticDns && dnsHosts == o.dnsHosts &&
            geoipUrl == o.geoipUrl && geositeUrl == o.geositeUrl

    val needsGeoDownload: Boolean
        get() = (geoipUrl.isNotBlank() && geoipFile.isBlank()) || (geositeUrl.isNotBlank() && geositeFile.isBlank())

    companion object { const val MY_GROUP = "Мои профили" }
}

object RoutingImport {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val ipv6 = Regex("""^[0-9a-f]*:[0-9a-f:.]*(/\d{1,3})?$""")

    /** Guess the rule kind of a free-form value (used for AntiBS JSON without kinds and the rule dialog). */
    fun kindOf(value: String): String {
        val v = value.trim().lowercase()
        return when {
            v.startsWith("geoip:") -> "ip"
            v.startsWith("app:") || v.startsWith("process:") || v.startsWith("package:") -> "process"
            v.startsWith("port:") -> "port"
            ipv4.matches(v) || ipv6.matches(v) -> "ip"
            else -> "domain"
        }
    }

    /** Clipboard / header text → profile: happ://routing/add|onadd/BASE64, raw Happ JSON, base64 JSON, or AntiBS JSON. */
    fun parse(text: String, id: String, group: String = RoutingProfile.MY_GROUP, subscriptionId: String? = null): RoutingProfile? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val body = when {
            t.lowercase().startsWith("happ://routing/") -> t.substringAfter("happ://routing/").substringAfter('/')
            else -> t
        }
        val decoded = if (body.startsWith("{")) body else LinkParser.b64Decode(body.trim())?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val obj = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull() ?: return null
        // AntiBS native export
        if (obj.containsKey("rules") && obj["rules"] is JsonArray) {
            return runCatching { json.decodeFromString(RoutingProfile.serializer(), decoded) }.getOrNull()
                ?.copy(id = id, group = group, subscriptionId = subscriptionId)
        }
        fun str(vararg k: String): String? = k.firstNotNullOfOrNull { key ->
            obj.entries.firstOrNull { it.key.equals(key, true) }?.value?.let { v -> (v as? JsonPrimitive)?.contentOrNull }
        }
        fun list(vararg k: String): List<String> = k.flatMap { key ->
            val v = obj.entries.firstOrNull { it.key.equals(key, true) }?.value
            when (v) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
                is JsonPrimitive -> v.contentOrNull?.split(',', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                else -> emptyList()
            }
        }
        val rules = mutableListOf<RoutingRule>()
        list("BlockSites").forEach { rules += RoutingRule("block", "domain", it) }
        list("BlockIp", "BlockIps").forEach { rules += RoutingRule("block", "ip", it) }
        list("DirectSites").forEach { rules += RoutingRule("direct", "domain", it) }
        list("DirectIp", "DirectIps").forEach { rules += RoutingRule("direct", "ip", it) }
        list("ProxySites").forEach { rules += RoutingRule("proxy", "domain", it) }
        list("ProxyIp", "ProxyIps").forEach { rules += RoutingRule("proxy", "ip", it) }
        if (rules.isEmpty() && str("Name") == null) return null
        val global = str("GlobalProxy")?.let { it.equals("true", true) || it == "1" } ?: true
        fun dns(prefix: String, plainKey: String): String {
            val type = str("${prefix}DNSType").orEmpty().lowercase()
            val domain = str("${prefix}DNSDomain").orEmpty().trim()
            val ip = str("${prefix}DNSIP").orEmpty().trim()
            val plain = str(plainKey).orEmpty().trim()
            return when {
                type == "doh" && domain.startsWith("https://", true) -> domain
                type == "dot" && ip.isNotEmpty() -> "tcp://$ip" // Xray has no DoT; plain TCP to the same server
                ip.isNotEmpty() -> ip
                else -> plain
            }
        }
        val hosts = (obj.entries.firstOrNull { it.key.equals("DnsHosts", true) }?.value as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }?.let { k.trim() to it } }
            ?.filter { it.first.isNotEmpty() }?.toMap().orEmpty()
        return RoutingProfile(
            id = id,
            name = str("Name", "name", "Remarks")?.ifBlank { null } ?: "Профиль",
            group = group,
            subscriptionId = subscriptionId,
            rules = rules,
            globalProxy = global,
            domainStrategy = when (str("DomainStrategy")?.lowercase()) {
                "asis" -> "AsIs"; "iponDemand".lowercase() -> "IPOnDemand"; else -> "IPIfNonMatch"
            },
            remoteDns = dns("Remote", "RemoteDns"),
            domesticDns = dns("Domestic", "DomesticDns"),
            dnsHosts = hosts,
            geoipUrl = str("Geoipurl", "GeoipUrl", "GeoIPUrl").orEmpty().trim().takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }.orEmpty(),
            geositeUrl = str("Geositeurl", "GeositeUrl").orEmpty().trim().takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }.orEmpty(),
            lastUpdated = str("LastUpdated").orEmpty(),
        )
    }

    fun export(p: RoutingProfile): String = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(RoutingProfile.serializer(), p)

    /** Happ-compatible JSON (so profiles can be shared back). */
    fun exportHapp(p: RoutingProfile): String {
        fun pick(action: String, kind: String) = p.rules.filter { it.action == action && it.kind == kind }.map { it.value }
        val o = buildMap<String, Any> {
            put("Name", p.name)
            put("GlobalProxy", p.globalProxy.toString())
            put("DomainStrategy", p.domainStrategy)
            put("DirectSites", pick("direct", "domain")); put("DirectIp", pick("direct", "ip"))
            put("ProxySites", pick("proxy", "domain")); put("ProxyIp", pick("proxy", "ip"))
            put("BlockSites", pick("block", "domain")); put("BlockIp", pick("block", "ip"))
            if (p.remoteDns.isNotBlank()) put("RemoteDns", p.remoteDns)
            if (p.domesticDns.isNotBlank()) put("DomesticDns", p.domesticDns)
            if (p.geoipUrl.isNotBlank()) put("Geoipurl", p.geoipUrl)
            if (p.geositeUrl.isNotBlank()) put("Geositeurl", p.geositeUrl)
        }
        val el = JsonObject(o.mapValues { (_, v) ->
            when (v) {
                is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
                else -> JsonPrimitive(v.toString())
            }
        })
        return el.toString()
    }

    @Suppress("unused")
    private fun prim(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull
}

/** IPv4 CIDR math for «Исключённые маршруты» on Android < 13 (no Builder.excludeRoute). */
object Cidr {
    data class V4(val addr: Long, val prefix: Int) {
        override fun toString(): String = "${(addr shr 24) and 255}.${(addr shr 16) and 255}.${(addr shr 8) and 255}.${addr and 255}/$prefix"
    }

    fun parseV4(s: String): V4? {
        val t = s.trim()
        val ip = t.substringBefore('/')
        val prefix = if (t.contains('/')) t.substringAfter('/').toIntOrNull() ?: return null else 32
        if (prefix !in 0..32) return null
        val oct = ip.split('.')
        if (oct.size != 4) return null
        var a = 0L
        for (o in oct) { val v = o.toIntOrNull() ?: return null; if (v !in 0..255) return null; a = (a shl 8) or v.toLong() }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return V4(a and mask, prefix)
    }

    private fun contains(a: V4, b: V4): Boolean {
        if (a.prefix > b.prefix) return false
        val mask = if (a.prefix == 0) 0L else (0xFFFFFFFFL shl (32 - a.prefix)) and 0xFFFFFFFFL
        return (b.addr and mask) == a.addr
    }

    /** 0.0.0.0/0 minus [excluded] as a minimal list of CIDRs. */
    fun complement(excluded: List<V4>): List<V4> {
        val out = mutableListOf<V4>()
        fun walk(n: V4) {
            if (excluded.any { contains(it, n) }) return
            if (excluded.none { contains(n, it) }) { out += n; return }
            if (n.prefix >= 32) return
            val p = n.prefix + 1
            walk(V4(n.addr, p))
            walk(V4(n.addr or (1L shl (32 - p)), p))
        }
        walk(V4(0, 0))
        return out
    }
}

/** Per-app proxy → VpnService.Builder calls (pure, unit-tested). */
object PerApp {
    data class Plan(val allowed: List<String>, val disallowed: List<String>, val note: String)

    fun plan(
        mode: String,
        include: List<String>,
        bypass: List<String>,
        own: String,
        ruOn: Boolean = false,
        ruInstalled: Collection<String> = emptyList(),
    ): Plan {
        val r = RuApps.plan(mode, include, bypass, own, ruOn, ruInstalled)
        return Plan(r.allowed, r.disallowed, RuApps.planNote(mode, r, ruOn, ruInstalled.size))
    }
}
