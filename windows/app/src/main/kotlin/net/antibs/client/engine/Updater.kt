package net.antibs.client.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.antibs.client.data.UpdateLogic
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Desktop updates from GitHub releases of samur005/antibs-client-apk: a release counts for Windows only when it carries
 * a «…windows…setup.exe» asset (SHA256SUMS verified when present). Installing = starting the downloaded setup.
 */
object Updater {
    private const val UA = "AntiBS-client-updater"

    private fun open(url: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            accept?.let { setRequestProperty("Accept", it) }
        }

    fun pickSetup(r: UpdateLogic.Release): UpdateLogic.Asset? =
        r.assets.firstOrNull { it.name.contains("windows", true) && it.name.endsWith("setup.exe", true) }

    /** Windows checksums ride in their own file, so the release's Android SHA256SUMS.txt is never rewritten. */
    fun sumsAsset(r: UpdateLogic.Release): UpdateLogic.Asset? =
        r.assets.firstOrNull { it.name.equals("SHA256SUMS-windows.txt", true) } ?: r.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", true) }

    suspend fun check(current: String, override: String): UpdateLogic.Update? = withContext(Dispatchers.IO) {
        val c = open(UpdateLogic.apiUrl(override).replace("/releases/latest", "/releases?per_page=10"), "application/vnd.github+json")
        try {
            val code = c.responseCode
            val headers = c.headerFields.orEmpty().filterKeys { it != null }.mapValues { it.value.lastOrNull().orEmpty() }.mapKeys { it.key!! }
            UpdateLogic.rateLimitMessage(code, headers, System.currentTimeMillis() / 1000)?.let { throw IllegalStateException(it) }
            if (code !in 200..299) throw IllegalStateException("GitHub: HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray ?: return@withContext null
            arr.asSequence().mapNotNull { UpdateLogic.parseRelease(it.toString()) }
                .mapNotNull { r -> pickSetup(r)?.let { a -> UpdateLogic.Update(r, a, sumsAsset(r)) } }
                .filter { UpdateLogic.isNewer(it.release.version, current) }
                .maxWithOrNull { a, b -> UpdateLogic.compare(a.release.version, b.release.version) }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val b = ByteArray(256 * 1024); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun download(ctx: Context, u: UpdateLogic.Update, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val expected = u.sums?.let { s ->
            val c = open(s.url)
            try { UpdateLogic.parseSums(c.inputStream.bufferedReader().use { it.readText() })[u.apk.name] } finally { c.disconnect() }
        }
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, u.apk.name)
        val c = open(u.apk.url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: u.apk.size
            c.inputStream.use { inp -> out.outputStream().use { o ->
                val b = ByteArray(256 * 1024); var done = 0L
                while (true) { val n = inp.read(b); if (n < 0) break; o.write(b, 0, n); done += n; if (total > 0) progress(done.toFloat() / total) }
            } }
        } finally { c.disconnect() }
        if (expected != null && !sha256(out).equals(expected, true)) { out.delete(); throw IllegalStateException("контрольная сумма не совпала") }
        out
    }

    fun canInstall(@Suppress("UNUSED_PARAMETER") ctx: Context) = true
    fun openInstallPermission(@Suppress("UNUSED_PARAMETER") ctx: Context) {}

    /**
     * Starts the NSIS setup silently (/S); it closes the running app itself, installs over the current per-user
     * install and starts the app again (/RUN). The portable build is not touched: the setup is shown normally.
     */
    fun install(@Suppress("UNUSED_PARAMETER") ctx: Context, setup: File) {
        val portable = System.getProperty("antibs.home") != null
        ProcessBuilder(if (portable) listOf(setup.path) else listOf(setup.path, "/S", "/RUN")).start()
    }
}
