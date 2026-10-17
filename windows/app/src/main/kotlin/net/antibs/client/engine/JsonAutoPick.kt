package net.antibs.client.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 1.0.34: the panel's auto-select config is running — which outbound its balancer uses right now comes from Xray
 * itself (RoutingService.GetBalancerInfo via [XrayApi]), never from traffic guesses or the first hop's address.
 */
class JsonAutoPick(
    val apiPort: Int,
    val balancerTag: String,
    /** outbound tag → host name («🇷🇺→🇩🇪 Германия»); unknown tags are shown as the tag itself. */
    val names: Map<String, String>,
    /** outbound tag → Xray protocol (vless, trojan, …). */
    val protocols: Map<String, String>,
) {
    var current: String? = null
        private set

    fun nameOf(tag: String): String = names[tag] ?: tag

    /**
     * New pick from the balancer's targets (override / principle targets). leastPing gives one target; strategies
     * that keep several (leastLoad) are narrowed by this session's traffic per outbound. Returns the tag when it changed.
     */
    fun update(targets: List<String>, traffic: Map<String, Long>): String? {
        val t = choose(targets, traffic, current) ?: return null
        if (t == current) return null
        current = t
        return t
    }

    companion object {
        fun choose(targets: List<String>, traffic: Map<String, Long>, current: String?): String? {
            if (targets.isEmpty()) return null
            if (targets.size == 1) return targets[0]
            val best = targets.maxByOrNull { traffic[it] ?: 0L }!!
            if ((traffic[best] ?: 0L) > 0L) return best
            return current?.takeIf { it in targets } ?: targets[0]
        }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun outboundProtocols(cfgJson: String?): Map<String, String> {
            val o = try { cfgJson?.let { json.parseToJsonElement(it) as? JsonObject } } catch (_: Exception) { null } ?: return emptyMap()
            return (o["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { ob ->
                val tag = (ob["tag"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val p = (ob["protocol"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                tag to p
            }.toMap()
        }
    }
}
