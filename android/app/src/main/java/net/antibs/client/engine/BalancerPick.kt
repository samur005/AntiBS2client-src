package net.antibs.client.engine

/**
 * 1.0.19: which balancer member Xray is actually using. Xray's leastPing choice isn't exposed by the core API,
 * so it is inferred from per-outbound traffic: over the last [window] one-second ticks the member that carried at
 * least [minBytes] and at least [minShare] of all member traffic is the current pick. Observatory probes touch
 * every member about equally, so probe-only periods never reach the share threshold and the previous pick stays.
 */
class BalancerPick(private val window: Int = 5, private val minBytes: Long = 4096, private val minShare: Double = 0.6) {
    private val ticks = ArrayDeque<Map<String, Long>>()
    var current: String? = null
        private set

    /** Adds one tick; returns the new pick when it changed, else null. */
    fun add(tick: Map<String, Long>): String? {
        ticks.addLast(tick)
        while (ticks.size > window) ticks.removeFirst()
        val sum = HashMap<String, Long>()
        ticks.forEach { t -> t.forEach { (k, v) -> sum[k] = (sum[k] ?: 0L) + v } }
        val total = sum.values.sum()
        val top = sum.maxByOrNull { it.value } ?: return null
        if (top.value < minBytes || total <= 0 || top.value.toDouble() / total < minShare) return null
        if (top.key == current) return null
        current = top.key
        return top.key
    }

    companion object {
        /** "b-3" → 3; null for any other tag. */
        fun indexOf(tag: String): Int? = tag.removePrefix("b-").takeIf { tag.startsWith("b-") }?.toIntOrNull()
    }
}
