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
 * Desktop updates from GitHub releases of samur005/AntiBS2client-releases: a release counts for Windows only when it carries
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

    /**
     * 2.0.20: the update for [current] among GitHub release objects (releases/latest + the releases list): drafts and
     * prereleases are skipped, only releases carrying a «…windows…setup.exe» count, the newest version wins.
     */
    fun pickUpdate(releases: List<kotlinx.serialization.json.JsonElement>, current: String): UpdateLogic.Update? =
        releases.asSequence()
            .filter { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@filter false
                fun flag(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                !flag("draft") && !flag("prerelease")
            }
            .mapNotNull { UpdateLogic.parseRelease(it.toString()) }
            .distinctBy { it.tag }
            .mapNotNull { r -> pickSetup(r)?.let { a -> UpdateLogic.Update(r, a, sumsAsset(r)) } }
            .filter { UpdateLogic.isNewer(it.release.version, current) }
            .maxWithOrNull { a, b -> UpdateLogic.compare(a.release.version, b.release.version) }

    const val SCAN = 50

    private fun fetchJson(url: String): kotlinx.serialization.json.JsonElement? {
        val c = open(url, "application/vnd.github+json")
        try {
            val code = c.responseCode
            val headers = c.headerFields.orEmpty().filterKeys { it != null }.mapValues { it.value.lastOrNull().orEmpty() }.mapKeys { it.key!! }
            UpdateLogic.rateLimitMessage(code, headers, System.currentTimeMillis() / 1000)?.let { throw IllegalStateException(it) }
            if (code == 404) return null
            if (code !in 200..299) throw IllegalStateException("GitHub: HTTP $code")
            return kotlinx.serialization.json.Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** releases/latest first (never a prerelease), then the last [SCAN] releases (a Windows setup may sit on an older one). */
    suspend fun check(current: String, override: String): UpdateLogic.Update? = withContext(Dispatchers.IO) {
        val latestUrl = UpdateLogic.apiUrl(override)
        val latest = try { fetchJson(latestUrl) } catch (e: Exception) { if (!latestUrl.endsWith("/releases/latest")) throw e else null }
        val listUrl = if (latestUrl.endsWith("/releases/latest")) latestUrl.removeSuffix("/latest") + "?per_page=$SCAN" else null
        val list = listUrl?.let { u ->
            try { fetchJson(u) as? kotlinx.serialization.json.JsonArray }
            catch (e: Exception) { if (latest == null) throw e else null }
        }.orEmpty()
        val first = (latest as? kotlinx.serialization.json.JsonArray)?.toList() ?: listOfNotNull(latest)
        pickUpdate(first + list, current)
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
