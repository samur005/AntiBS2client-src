package net.antibs.client.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 1.0.36: sticky auto-select. On start every balancer member is pinged and the fastest working one is pinned
 * (Xray RoutingService.OverrideBalancerTarget); afterwards only the pinned one is checked every [CHECK_SEC] s.
 * A failed check is re-tried at once; two failures in a row → all members are pinged again and the fastest working
 * one (other than the failed one, unless it's the only one) is pinned. A faster member alone never causes a switch.
 * When nothing answers the pin stays and the sweep is repeated every [CHECK_SEC] s, forever.
 */
class AutoPin(val members: List<String>) {
    enum class Step { OK, RECHECK, SWEEP }

    var pinned: String? = null
        private set
    /** Next tick must ping every member (start, after a failover, or while everything is down). */
    var needsSweep = true
        private set
    private var fails = 0
    private var failedTag: String? = null

    /**
     * Result of pinging every member (tag → ms, ≤0 = failed). Returns the tag to pin when it changed, else null.
     */
    fun onSweep(results: Map<String, Long>): String? {
        val alive = members.mapNotNull { t -> results[t]?.takeIf { it > 0 }?.let { t to it } }
        if (alive.isEmpty()) { needsSweep = true; return null }
        val pool = alive.filter { it.first != failedTag }.ifEmpty { alive }
        val best = pool.minByOrNull { it.second }!!.first
        needsSweep = false
        fails = 0
        failedTag = null
        if (best == pinned) return null
        pinned = best
        return best
    }

    /** Periodic / immediate re-check of the pinned member. */
    fun onCheck(ok: Boolean): Step {
        if (ok) { fails = 0; return Step.OK }
        fails++
        if (fails < 2) return Step.RECHECK
        fails = 0
        failedTag = pinned
        needsSweep = true
        return Step.SWEEP
    }

    companion object {
        const val CHECK_SEC = 30
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        /** Outbound tags matched by the balancer's selector (Xray: tag prefix match), in config order. */
        fun balancerMembers(cfg: JsonObject, balancerTag: String): List<String> {
            val bal = ((cfg["routing"] as? JsonObject)?.get("balancers") as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.s("tag") == balancerTag } ?: return emptyList()
            val sel = (bal["selector"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return emptyList()
            return (cfg["outbounds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("tag") }
                .filter { t -> sel.any { t.startsWith(it) } }
        }

        fun firstBalancerTag(cfg: JsonObject): String? =
            ((cfg["routing"] as? JsonObject)?.get("balancers") as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.s("tag") }

        /**
         * Adds what pinning needs to a running config, leaving everything else as-is: one loopback SOCKS inbound
         * per member routed straight to that outbound (to ping exactly that server) and the Xray API.
         */
        fun withProbes(config: String, apiPort: Int, probes: Map<String, Int>): String {
            val cfg = json.parseToJsonElement(config) as JsonObject
            val inb = (cfg["inbounds"] as? JsonArray).orEmpty() + probes.map { (tag, port) ->
                buildJsonObject {
                    put("tag", "probe-$tag"); put("listen", "127.0.0.1"); put("port", port); put("protocol", "socks")
                    putJsonObject("settings") { put("auth", "noauth"); put("udp", false) }
                }
            }
            val routing = (cfg["routing"] as? JsonObject).orEmpty()
            val rules = probes.keys.map { tag ->
                buildJsonObject { put("type", "field"); putJsonArray("inboundTag") { add("probe-$tag") }; put("outboundTag", tag) }
            } + (routing["rules"] as? JsonArray).orEmpty()
            var out = JsonObject(cfg + ("inbounds" to JsonArray(inb)) + ("routing" to JsonObject(routing + ("rules" to JsonArray(rules)))))
            if (!out.containsKey("api") && apiPort > 0) out = JsonObject(out + ("api" to buildJsonObject {
                put("tag", "api"); put("listen", "127.0.0.1:$apiPort")
                putJsonArray("services") { add("RoutingService"); add("ObservatoryService"); add("StatsService") }
            }))
            return out.toString()
        }

        fun parse(config: String): JsonObject? = try { json.parseToJsonElement(config) as? JsonObject } catch (_: Exception) { null }
    }
}
