package net.antibs.client.data

/**
 * 2.0.34: live subscription remaining traffic on the connection banner.
 *
 * Shown remaining = remaining at last userinfo fetch − session (up+down) bytes counted
 * since that fetch, clamped at 0. A new fetch resets the baseline so panel-reported
 * usage is not subtracted twice. The consumed offset is kept across process restarts;
 * if the session counter itself reset (new VPN session / process), [sessionMark] is
 * realigned without losing [consumed].
 */
data class SubTrafficSnapshot(
    val subId: String,
    val lastRefresh: Long,
    val upload: Long,
    val download: Long,
    val total: Long,
    val remainingAtFetch: Long,
    val sessionMark: Long,
    val consumed: Long,
)

object SubTrafficLive {
    fun remainingFromUserinfo(sub: Subscription): Long? {
        if (sub.total <= 0) return null
        return (sub.total - sub.upload - sub.download).coerceAtLeast(0)
    }

    fun sameFetch(prev: SubTrafficSnapshot, sub: Subscription): Boolean =
        prev.subId == sub.id &&
            prev.lastRefresh == sub.lastRefresh &&
            prev.upload == sub.upload &&
            prev.download == sub.download &&
            prev.total == sub.total

    /**
     * @param sessionBytes current VPN session uplink+downlink (not direct/bypass)
     * @param accumulating true only while connected through [sub]
     */
    fun tick(prev: SubTrafficSnapshot?, sub: Subscription, sessionBytes: Long, accumulating: Boolean): SubTrafficSnapshot? {
        val remaining = remainingFromUserinfo(sub) ?: return null
        val sess = sessionBytes.coerceAtLeast(0)
        if (prev == null || !sameFetch(prev, sub)) {
            return SubTrafficSnapshot(
                subId = sub.id,
                lastRefresh = sub.lastRefresh,
                upload = sub.upload,
                download = sub.download,
                total = sub.total,
                remainingAtFetch = remaining,
                sessionMark = if (accumulating) sess else 0L,
                consumed = 0L,
            )
        }
        if (sess < prev.sessionMark) {
            // Session counter restarted (app/process or a new tunnel). Keep consumed.
            return prev.copy(sessionMark = sess)
        }
        if (!accumulating) return prev
        val delta = sess - prev.sessionMark
        return if (delta == 0L) prev else prev.copy(sessionMark = sess, consumed = prev.consumed + delta)
    }

    fun shownRemaining(snap: SubTrafficSnapshot): Long =
        (snap.remainingAtFetch - snap.consumed).coerceAtLeast(0)

    fun shownUsed(snap: SubTrafficSnapshot): Long =
        (snap.total - shownRemaining(snap)).coerceAtLeast(0)

    fun apply(base: SubLineData?, snap: SubTrafficSnapshot?): SubLineData? {
        if (base == null) return null
        if (snap == null) return base
        val used = shownUsed(snap)
        val total = snap.total
        return base.copy(
            traffic = SubLineData.pair(used, total),
            trafficFrac = if (total > 0) SubLineData.remainingFrac(used, total) else base.trafficFrac,
        )
    }
}
