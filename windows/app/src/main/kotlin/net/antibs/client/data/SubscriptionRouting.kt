package net.antibs.client.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 1.0.19: Happ-style routing delivered by a subscription — `routing` response header, `happ://routing/…` or
 * `#routing` lines in the body, or the `routing` section of an Xray JSON subscription — turned into one routing
 * profile per subscription (id «route-<subId>», refreshed in place, never duplicated).
 *
 *  - happ://routing/onadd/… → activated only when the profile is first added;
 *  - happ://routing/add/…, raw JSON, Xray JSON routing → activated (and updated) on every fetch.
 */
object SubscriptionRouting {

    const val MODE_ONADD = "onadd"
    const val MODE_ADD = "add"

    fun profileId(subId: String) = "route-$subId"

    fun mode(raw: String): String {
        val t = raw.trim().lowercase()
        return if (t.startsWith("happ://routing/onadd/")) MODE_ONADD else MODE_ADD
    }

    private val metaLine = Regex("""^(?:#|//)\s*routing\s*[:=]?\s*(\S.*)$""", RegexOption.IGNORE_CASE)

    /** Routing lines in a (decoded) link-list body → (body without them, routing text or null). */
    fun extractFromBody(text: String): Pair<String, String?> {
        if (!text.contains("routing", ignoreCase = true)) return text to null
        var found: String? = null
        val kept = text.lines().filter { line ->
            val t = line.trim()
            val v = when {
                t.startsWith("happ://routing/", true) -> t
                else -> metaLine.find(t)?.groupValues?.get(1)?.trim()
            }
            if (v != null && v.isNotEmpty()) { if (found == null) found = v; false } else true
        }
        return kept.joinToString("\n") to found
    }

    /**
     * Xray JSON subscription → Happ JSON for its `routing` section (first config that has non-trivial rules).
     * Rules sending traffic to freedom/blackhole outbounds become «Напрямую»/«Блок», other outbounds «Прокси».
     * A lone catch-all (`network: tcp,udp` → proxy) carries nothing, so it yields null.
     */
    fun fromXrayConfigs(configs: List<JsonObject>, name: String?): String? {
        for (c in configs) {
            val routing = c["routing"] as? JsonObject ?: continue
            val rules = routing["rules"] as? JsonArray ?: continue
            val kinds = (c["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associate { o ->
                val tag = (o["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                tag to when ((o["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                    "freedom" -> "direct"; "blackhole" -> "block"; "dns" -> "dns"; else -> "proxy"
                }
            }
            val out = mutableListOf<RoutingRule>()
            var finalDirect = false
            for (r in rules.mapNotNull { it as? JsonObject }) {
                val tag = (r["outboundTag"] as? JsonPrimitive)?.contentOrNull
                val action = when {
                    tag == null -> if (r.containsKey("balancerTag")) "proxy" else continue
                    else -> kinds[tag] ?: when (tag.lowercase()) { "direct" -> "direct"; "block" -> "block"; else -> "proxy" }
                }
                if (action == "dns") continue
                fun strs(k: String) = (r[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
                val domains = strs("domain"); val ips = strs("ip")
                val port = (r["port"] as? JsonPrimitive)?.contentOrNull
                domains.forEach { out += RoutingRule(action, "domain", it) }
                ips.forEach { out += RoutingRule(action, "ip", it) }
                if (domains.isEmpty() && ips.isEmpty() && port != null && port != "53" && !r.containsKey("inboundTag")) out += RoutingRule(action, "port", port)
                val catchAll = domains.isEmpty() && ips.isEmpty() && port == null && !r.containsKey("inboundTag") && !r.containsKey("protocol")
                if (catchAll && action == "direct") finalDirect = true
            }
            if (out.isEmpty()) continue
            val ds = (routing["domainStrategy"] as? JsonPrimitive)?.contentOrNull ?: "AsIs"
            val p = RoutingProfile(id = "x", name = name?.ifBlank { null } ?: "Маршрутизация подписки", rules = out,
                globalProxy = !finalDirect, domainStrategy = ds)
            return RoutingImport.exportHapp(p)
        }
        return null
    }

    sealed class Outcome {
        abstract val name: String
        /** New or changed profile that is now the active one. */
        data class Installed(override val name: String, val profileId: String) : Outcome()
        /** Changed but left inactive («onadd» after the first time, or another profile is active). */
        data class Updated(override val name: String, val profileId: String) : Outcome()
        data class Failed(override val name: String, val error: String) : Outcome()
    }

    data class Applied(val adv: AdvancedSettings, val outcome: Outcome?, val profile: RoutingProfile?)

    /**
     * Pure merge of a fetched routing text into [adv]. [autoRouting] off → nothing changes.
     * The user's toggles / assigned servers / downloaded geo files (same URL) are kept on refresh.
     */
    fun apply(adv: AdvancedSettings, subId: String, title: String, routing: String?, autoRouting: Boolean = true): Applied {
        val raw = routing?.trim().orEmpty()
        if (raw.isEmpty() || !autoRouting) return Applied(adv, null, null)
        val pid = profileId(subId)
        val prof = RoutingImport.parse(raw, pid, group = title, subscriptionId = subId)
            ?: return Applied(adv, Outcome.Failed(title, "не удалось декодировать маршрутизацию"), null)
        val old = adv.routingProfiles.firstOrNull { it.id == pid }
        val merged = if (old != null) prof.copy(
            directEnabled = old.directEnabled, blockEnabled = old.blockEnabled, proxyEnabled = old.proxyEnabled,
            proxyServerIds = old.proxyServerIds,
            geoipFile = if (old.geoipUrl == prof.geoipUrl) old.geoipFile else "",
            geoipTags = if (old.geoipUrl == prof.geoipUrl) old.geoipTags else emptyList(),
            geositeFile = if (old.geositeUrl == prof.geositeUrl) old.geositeFile else "",
            geositeTags = if (old.geositeUrl == prof.geositeUrl) old.geositeTags else emptyList(),
        ) else prof
        val changed = old == null || !old.sameContentAs(merged)
        val activate = when (mode(raw)) {
            MODE_ONADD -> old == null
            else -> adv.activeRoutingId != pid
        }
        val list = if (old == null) adv.routingProfiles + merged else adv.routingProfiles.map { if (it.id == pid) merged else it }
        val active = if (activate) pid else adv.activeRoutingId
        val outcome = when {
            activate -> Outcome.Installed(merged.name, pid)
            changed && active == pid -> Outcome.Installed(merged.name, pid)
            changed -> Outcome.Updated(merged.name, pid)
            else -> null
        }
        return Applied(adv.copy(routingProfiles = list, activeRoutingId = active), outcome, merged)
    }

    fun message(o: Outcome): String = when (o) {
        is Outcome.Installed -> "Маршрутизация «${o.name}» загружена из подписки и установлена"
        is Outcome.Updated -> "Маршрутизация «${o.name}» обновлена из подписки"
        is Outcome.Failed -> "Не удалось загрузить маршрутизацию из подписки «${o.name}»: ${o.error}"
    }
}
