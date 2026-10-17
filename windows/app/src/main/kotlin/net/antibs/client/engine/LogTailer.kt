package net.antibs.client.engine

import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/** Follows Xray's error/access log files and forwards new lines into [VpnState] (Логи screen). */
class LogTailer(private val files: List<Pair<File, String>>) {
    @Volatile private var running = true
    private var t: Thread? = null

    fun start() {
        t = thread(name = "xray-log-tail", isDaemon = true) {
            val pos = HashMap<File, Long>()
            files.forEach { (f, _) -> pos[f] = 0L }
            while (running) {
                for ((f, tag) in files) {
                    try {
                        if (!f.exists()) continue
                        val len = f.length()
                        var p = pos[f] ?: 0L
                        if (len < p) p = 0L
                        if (len == p) continue
                        RandomAccessFile(f, "r").use { raf ->
                            raf.seek(p)
                            val buf = ByteArray((len - p).coerceAtMost(256 * 1024).toInt())
                            raf.readFully(buf)
                            p += buf.size
                            String(buf, Charsets.UTF_8).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                                .takeLast(200).forEach { VpnState.log("$tag ${stripTs(it).take(400)}") }
                        }
                        pos[f] = p
                        // keep files small
                        if (p > 2 * 1024 * 1024) { f.writeText(""); pos[f] = 0L }
                    } catch (_: Exception) {}
                }
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }
    }

    /** "2026/10/09 12:00:01.123456 [Warning] …" → "[Warning] …" (VpnState adds its own time). */
    private fun stripTs(s: String): String = s.replace(Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s+"""), "")

    fun stop() { running = false; t?.interrupt() }

    companion object {
        @Volatile var current: LogTailer? = null
    }
}
