package net.antibs.client.data

import android.content.Context
import org.json.JSONObject

/** Persists the live remaining-traffic offset so it survives process death. */
class SubTrafficLiveStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sub_traffic_live", Context.MODE_PRIVATE)
    private val snaps = HashMap<String, SubTrafficSnapshot>()

    init {
        sp.all.forEach { (k, v) ->
            val s = (v as? String)?.let { decode(it) } ?: return@forEach
            snaps[k] = s
        }
    }

    @Synchronized
    fun tick(sub: Subscription?, sessionBytes: Long, accumulating: Boolean): SubTrafficSnapshot? {
        if (sub == null) return null
        val next = SubTrafficLive.tick(snaps[sub.id], sub, sessionBytes, accumulating)
        if (next == null) {
            if (snaps.remove(sub.id) != null) sp.edit().remove(sub.id).apply()
        } else if (snaps[sub.id] != next) {
            snaps[sub.id] = next
            sp.edit().putString(sub.id, encode(next)).apply()
        }
        return next
    }

    private fun encode(s: SubTrafficSnapshot): String = JSONObject().apply {
        put("subId", s.subId)
        put("lastRefresh", s.lastRefresh)
        put("upload", s.upload)
        put("download", s.download)
        put("total", s.total)
        put("remainingAtFetch", s.remainingAtFetch)
        put("sessionMark", s.sessionMark)
        put("consumed", s.consumed)
    }.toString()

    private fun decode(raw: String): SubTrafficSnapshot? = runCatching {
        val o = JSONObject(raw)
        SubTrafficSnapshot(
            subId = o.getString("subId"),
            lastRefresh = o.getLong("lastRefresh"),
            upload = o.getLong("upload"),
            download = o.getLong("download"),
            total = o.getLong("total"),
            remainingAtFetch = o.getLong("remainingAtFetch"),
            sessionMark = o.getLong("sessionMark"),
            consumed = o.getLong("consumed"),
        )
    }.getOrNull()

    companion object {
        @Volatile private var inst: SubTrafficLiveStore? = null
        fun get(ctx: Context): SubTrafficLiveStore = inst ?: synchronized(this) {
            inst ?: SubTrafficLiveStore(ctx).also { inst = it }
        }
        /** Tests. */
        fun reset() { inst = null }
    }
}
