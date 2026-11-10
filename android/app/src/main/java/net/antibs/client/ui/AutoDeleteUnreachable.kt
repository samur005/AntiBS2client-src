package net.antibs.client.ui

import net.antibs.client.data.ServerEntry

/**
 * 2.0.35: after a completed subscription ping (or ping-all), which hosts of that
 * subscription should be hidden. Pure rules, unit-tested.
 *
 * Never runs when the ping was stopped early or the network is offline.
 * If every considered host failed (or none succeeded), nothing is deleted —
 * that guards against wiping the list when the whole network is down.
 * The currently connected host ([protectIds] / [protectName]) is never removed.
 * Only hosts that were in the completed [batchIds] and actually failed (-1) are candidates.
 */
object AutoDeleteUnreachable {
    fun idsToDelete(
        hosts: List<ServerEntry>,
        batchIds: Set<String>,
        pings: Map<String, Long>,
        protectIds: Set<String> = emptySet(),
        protectName: String = "",
        stoppedEarly: Boolean = false,
        networkOffline: Boolean = false,
    ): List<String> {
        if (stoppedEarly || networkOffline) return emptyList()
        val considered = hosts.filter { it.id in batchIds && !UiLogic.isAutoSelectEntry(it) }
        if (considered.isEmpty()) return emptyList()
        val applicable = considered.map { it to pings[it.id] }.filter { it.second != null && it.second != -2L }
        if (applicable.isEmpty()) return emptyList()
        val successes = applicable.count { (it.second ?: -1L) >= 0L }
        if (successes == 0) return emptyList()
        return applicable.filter { (s, p) ->
            p == -1L && s.id !in protectIds && (protectName.isBlank() || s.name != protectName)
        }.map { it.first.id }
    }
}
