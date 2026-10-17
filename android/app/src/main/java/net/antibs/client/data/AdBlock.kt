package net.antibs.client.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 1.0.39/1.0.40: «Блокировка рекламы» — Xray routing rules (→ blackhole) prepended to every config the app runs, plus DNS
 * hosts entries that answer NXDOMAIN («#3») for the listed domains when the query reaches Xray's DNS.
 *
 * The bundled geosite.dat (Loyalsoldier v2ray-rules-dat) has «category-ads-all» (~114k domains, already including
 * most ad-SDK analytics), but no separate analytics/tracker category («category-public-tracker» / «trackernetwork»
 * are BitTorrent / gaming trackers, «win-spy» blocks Microsoft CDNs) — so trackers ship as a small domain list.
 */
object AdBlock {
    const val ADS_GEOSITE = "geosite:category-ads-all"
    /** Ad networks missing from category-ads-all. */
    val ADS_EXTRA = listOf("domain:adfox.ru")
    /** Analytics / telemetry endpoints (subdomains included). Deep-link, push and login hosts are deliberately absent. */
    val TRACKER_DOMAINS = listOf(
        "google-analytics.com", "analytics.google.com", "googletagmanager.com", "app-measurement.com", "crashlytics.com",
        "mc.yandex.ru", "mc.yandex.com", "mc.yandex.by", "mc.yandex.kz", "mc.webvisor.org", "mc.webvisor.com",
        "appmetrica.yandex.net", "appmetrica.yandex.com",
        "adjust.com", "adjust.io", "appsflyer.com", "appsflyersdk.com", "kochava.com", "flurry.com",
        "api.amplitude.com", "api2.amplitude.com", "api.mixpanel.com", "api-js.mixpanel.com", "api.segment.io", "cdn.segment.com",
        "hotjar.com", "hotjar.io", "clarity.ms", "scorecardresearch.com", "quantserve.com", "chartbeat.com", "chartbeat.net",
        "statcounter.com", "top-fwz1.mail.ru", "tns-counter.ru", "counter.yadro.ru", "bam.nr-data.net", "js-agent.newrelic.com",
        "events.data.microsoft.com", "vortex.data.microsoft.com", "tracking.miui.com", "data.mistat.xiaomi.com",
        "analytics.tiktok.com",
    ).map { "domain:$it" }

    /** STUN/TURN ports (WebRTC IP leak). */
    const val STUN_PORTS = "3478-3479,5349,19302-19309"

    const val BLOCK_TAG = "block"
    const val FALLBACK_TAG = "adblock"
    const val DIRECT_FALLBACK_TAG = "antibs-direct"
    /** Inbounds that carry user traffic; probe-*, sb-bridge-*, dns-in and api stay unfiltered. */
    val USER_INBOUNDS = setOf("socks", "http", "tun")
    private val OUR_RULE_TAGS = setOf("antibs-adblock", "antibs-allow", "antibs-stun", "antibs-preset")

    /** What to block: routing domain list ([block]), explicit names for DNS NXDOMAIN ([dns]), allow overrides ([allow]). */
    data class Prepared(val block: List<String>, val dns: List<String>, val allow: List<String> = emptyList())

    fun enabled(a: AdvancedSettings): Boolean = a.blockAds || a.blockTrackers || a.adGuard || a.blockCustom.isNotEmpty()

    /** Domain list for the enabled toggles (ads first). */
    fun domains(a: AdvancedSettings): List<String> = defaultPrepared(a).block

    /** Without downloaded/compiled files: bundled geosite + explicit lists, whitelist applied to the explicit ones. */
    fun defaultPrepared(a: AdvancedSettings): Prepared {
        val wl = UserDomains.parse(a.allowCustom)
        val wlNames = wl.map { it.second }.toSet()
        fun allowed(x: String): Boolean {
            val v = x.substringAfter(':')
            return wlNames.any { v == it || v.endsWith(".$it") }
        }
        val explicit = ((if (a.blockAds) ADS_EXTRA else emptyList()) + (if (a.blockTrackers) TRACKER_DOMAINS else emptyList()) +
            UserDomains.parse(a.blockCustom).map { UserDomains.toXray(it) }).distinct().filterNot(::allowed)
        val block = (if (a.blockAds) listOf(ADS_GEOSITE) else emptyList()) + explicit
        return Prepared(block, explicit, if (block.isEmpty()) emptyList() else wl.map { UserDomains.toXray(it) })
    }

    private fun inbArr(inb: List<String>) = JsonArray(inb.map { JsonPrimitive(it) })

    /** The block rule, or null when nothing is blocked. */
    fun rule(a: AdvancedSettings, inboundTags: List<String>, outboundTag: String = BLOCK_TAG, prep: Prepared? = null): JsonObject? {
        val d = (prep ?: defaultPrepared(a)).block
        if (d.isEmpty() || inboundTags.isEmpty() || !enabled(a)) return null
        return buildJsonObject {
            put("type", "field")
            put("inboundTag", inbArr(inboundTags))
            putJsonArray("domain") { d.forEach { add(JsonPrimitive(it)) } }
            put("outboundTag", outboundTag)
            put("ruleTag", "antibs-adblock")
        }
    }

    /**
     * Rules that go on top of routing.rules: whitelist overrides (→ [proxy], the config's normal route), the block
     * rule, STUN/WebRTC block.
     */
    fun leadingRules(a: AdvancedSettings, inb: List<String>, blockTag: String, proxy: Pair<String, String>, prep: Prepared? = null): List<JsonObject> {
        if (inb.isEmpty()) return emptyList()
        val p = prep ?: defaultPrepared(a)
        val out = ArrayList<JsonObject>()
        val block = rule(a, inb, blockTag, p)
        if (block != null && p.allow.isNotEmpty()) out += buildJsonObject {
            put("type", "field"); put("inboundTag", inbArr(inb))
            putJsonArray("domain") { p.allow.forEach { add(JsonPrimitive(it)) } }
            put(proxy.first, proxy.second); put("ruleTag", "antibs-allow")
        }
        block?.let { out += it }
        if (a.blockStun) out += buildJsonObject {
            put("type", "field"); put("inboundTag", inbArr(inb))
            put("network", "udp"); put("port", STUN_PORTS); put("outboundTag", blockTag); put("ruleTag", "antibs-stun")
        }
        return out
    }

    /**
     * dns.hosts with NXDOMAIN («#3») for the explicit blocked domains; an existing key in [dns] wins. Geosite/ext
     * lists stay out of hosts: Xray builds a second big matcher for them (+60 MB RSS measured on Xray 26).
     */
    fun withDnsHosts(dns: JsonObject, a: AdvancedSettings, prep: Prepared? = null): JsonObject {
        if (!enabled(a)) return dns
        val d = (prep ?: defaultPrepared(a)).dns.filterNot { it.startsWith("geosite:") || it.startsWith("ext:") }
        if (d.isEmpty()) return dns
        val hosts = (dns["hosts"] as? JsonObject).orEmpty().toMutableMap()
        d.forEach { if (!hosts.containsKey(it)) hosts[it] = JsonPrimitive("#3") }
        return JsonObject(dns + ("hosts" to JsonObject(hosts)))
    }

    private fun str(o: Any?, k: String) = ((o as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull

    /**
     * A full (panel / custom) Xray config: our rules prepended to routing.rules for the app's user inbounds
     * (whitelist → block → STUN → traffic preset), blackhole / freedom outbounds reused or added, DNS hosts merged
     * when the config has a dns section. Nothing else changes; idempotent.
     */
    fun patch(
        cfg: JsonObject, a: AdvancedSettings, prep: Prepared? = null,
        geoExtra: Map<String, Set<String>> = emptyMap(), bundled: Map<String, Set<String>>? = null,
    ): JsonObject {
        val preset = TrafficPresets.active(a)
        if (!enabled(a) && !a.blockStun && !preset) return cfg
        val inTags = (cfg["inbounds"] as? JsonArray).orEmpty().mapNotNull { str(it, "tag") }.filter { it in USER_INBOUNDS }
        if (inTags.isEmpty()) return cfg
        var outs = (cfg["outbounds"] as? JsonArray).orEmpty().toList()
        val existing = outs.firstOrNull { str(it, "tag") == BLOCK_TAG }
        val blockTag = if (existing == null || str(existing, "protocol")?.lowercase() == "blackhole") BLOCK_TAG else FALLBACK_TAG
        val routing = (cfg["routing"] as? JsonObject).orEmpty()
        val oldRules = (routing["rules"] as? JsonArray).orEmpty().filterNot { str(it, "ruleTag") in OUR_RULE_TAGS }
        // the config's normal route: its catch-all rule, else Xray's default = first outbound
        val catchAll = oldRules.firstOrNull { r ->
            val o = r as? JsonObject ?: return@firstOrNull false
            (o["balancerTag"] != null || o["outboundTag"] != null) && o.keys.all { it in setOf("type", "network", "balancerTag", "outboundTag", "ruleTag") }
        }
        val proxy: Pair<String, String> = when {
            catchAll != null && str(catchAll, "balancerTag") != null -> "balancerTag" to str(catchAll, "balancerTag")!!
            catchAll != null && str(catchAll, "outboundTag") != null -> "outboundTag" to str(catchAll, "outboundTag")!!
            else -> "outboundTag" to (str(outs.firstOrNull(), "tag") ?: "proxy")
        }
        val lead = leadingRules(a, inTags, blockTag, proxy, prep)
        var directTag = "direct"
        val presetRules = if (!preset) emptyList() else {
            val freedom = outs.firstOrNull { str(it, "tag") == "direct" && str(it, "protocol")?.lowercase() == "freedom" }
                ?: outs.firstOrNull { str(it, "protocol")?.lowercase() == "freedom" && str(it, "tag") != null }
            directTag = str(freedom, "tag") ?: if (outs.any { str(it, "tag") == "direct" }) DIRECT_FALLBACK_TAG else "direct"
            TrafficPresets.rules(a, inTags, proxy, directTag, geoExtra, bundled)
        }
        val added = lead + presetRules
        if (added.isEmpty()) return cfg
        if (added.any { str(it, "outboundTag") == blockTag } && outs.none { str(it, "tag") == blockTag }) outs = outs + blackhole(blockTag)
        if (presetRules.isNotEmpty() && outs.none { str(it, "tag") == directTag }) outs = outs + buildJsonObject {
            put("tag", directTag); put("protocol", "freedom")
        }
        var out = JsonObject(cfg + ("outbounds" to JsonArray(outs)))
        out = JsonObject(out + ("routing" to JsonObject(routing + ("rules" to JsonArray(added + oldRules)))))
        (out["dns"] as? JsonObject)?.let { out = JsonObject(out + ("dns" to withDnsHosts(it, a, prep))) }
        return out
    }

    fun blackhole(tag: String): JsonObject = buildJsonObject {
        put("tag", tag); put("protocol", "blackhole"); putJsonObject("settings") { putJsonObject("response") { put("type", "http") } }
    }
}
