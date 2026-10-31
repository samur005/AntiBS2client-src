package kittoku.osc

import android.util.Log

/** SSTP progress / failure log (logcat tag SSTP) plus the last failure for the UI. */
object SstpLog {
    data class Failure(val where: String, val result: String, val detail: String?)
    @Volatile var lastFailure: Failure? = null; private set
    @Volatile var listener: ((String) -> Unit)? = null
    private val lines = java.util.concurrent.ConcurrentLinkedDeque<String>()
    val recent: List<String> get() = lines.toList()

    /** Last PPP config frame from the server per stage (LCP/IPCP) — for specific error texts. */
    val lastPpp = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun reset() { lastFailure = null; lines.clear(); lastPpp.clear() }

    fun i(msg: String) {
        runCatching { Log.i("SSTP", msg) }
        lines.add(msg); while (lines.size > 400) lines.pollFirst()
        listener?.invoke(msg)
    }


    internal fun fail(where: String, result: String, detail: String?) {
        if (lastFailure == null) lastFailure = Failure(where, result, detail?.take(300))
        i("FAIL $where: $result" + (detail?.let { " — " + it.take(300) } ?: ""))
    }
}
