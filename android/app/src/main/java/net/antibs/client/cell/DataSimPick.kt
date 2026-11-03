package net.antibs.client.cell

/** Pure selection logic for the default SIM tab in cellular monitoring. */
object DataSimPick {
    private fun valid(id: Int) = id >= 0 && id != Int.MAX_VALUE

    /** subId of the data SIM among [subIds]: default-data subscription first, then the active data network's subId; null if neither matches. */
    fun dataSub(subIds: List<Int>, defaultData: Int, activeData: Int): Int? = when {
        valid(defaultData) && defaultData in subIds -> defaultData
        valid(activeData) && activeData in subIds -> activeData
        else -> null
    }

    /** Tab index to show: explicit user choice (if still present) > data SIM > first tab. */
    fun index(subIds: List<Int>, userSub: Int?, dataSub: Int?): Int {
        if (subIds.isEmpty()) return 0
        userSub?.let { u -> subIds.indexOf(u).takeIf { it >= 0 }?.let { return it } }
        dataSub?.let { d -> subIds.indexOf(d).takeIf { it >= 0 }?.let { return it } }
        return 0
    }
}
