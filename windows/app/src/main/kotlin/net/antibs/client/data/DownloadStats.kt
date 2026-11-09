package net.antibs.client.data

/** Pure helpers for the update download window: smoothed speed, ETA and Russian formatting. */
object DownloadStats {
    /** Exponential moving average of speed (bytes/s); [prev] <= 0 means no estimate yet. */
    fun smooth(prev: Double, sample: Double, alpha: Double = 0.25): Double = if (prev <= 0.0) sample else prev + alpha * (sample - prev)

    /** Seconds left, or null when unknown. */
    fun etaSec(done: Long, total: Long, bps: Double): Long? =
        if (total <= 0 || bps <= 1.0 || done >= total) (if (total > 0 && done >= total) 0L else null)
        else ((total - done) / bps).toLong()

    fun mmss(sec: Long?): String {
        if (sec == null || sec < 0) return "--:--"
        val s = sec.coerceAtMost(99 * 60 + 59)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    fun mb(bytes: Long): String = "%.1f МБ".format(java.util.Locale.US, bytes.coerceAtLeast(0) / 1048576.0).replace('.', ',')

    fun speed(bps: Double): String = when {
        bps <= 0 -> "— КБ/с"
        bps < 1024 * 1024 -> "%.0f КБ/с".format(java.util.Locale.US, bps / 1024)
        else -> "%.1f МБ/с".format(java.util.Locale.US, bps / 1048576).replace('.', ',')
    }

    /** Ping batch counter: (total, pinged, unavailable). Unavailable = finished with -1. */
    fun pingCounts(batch: Set<String>, pinging: Set<String>, pings: Map<String, Long>): Triple<Int, Int, Int> {
        val done = batch.filter { it !in pinging }
        return Triple(batch.size, done.size, done.count { pings[it] == -1L })
    }
    /** 2.0.29: ABS PingCounts — total / done / ok / failed. */
    fun pingCounts4(batch: Set<String>, pinging: Set<String>, pings: Map<String, Long>): Quad {
        val doneIds = batch.filter { it !in pinging }
        val failed = doneIds.count { pings[it] == -1L }
        val ok = doneIds.count { val v = pings[it]; v != null && v >= 0L }
        return Quad(batch.size, doneIds.size, ok, failed)
    }
    data class Quad(val total: Int, val done: Int, val ok: Int, val failed: Int)
}
