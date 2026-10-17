package net.antibs.client.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.antibs.client.data.GeoDat
import net.antibs.client.data.RoutingProfile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 1.0.19: custom geoip/geosite files from a routing profile (Happ Geoipurl / Geositeurl), cached in filesDir. */
object GeoFiles {
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun fileName(url: String, kind: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        return "geo$kind-$h.dat"
    }

    @Volatile private var bundledCache: Map<String, Set<String>>? = null

    /** Tags of the stock geoip.dat / geosite.dat (null if they can't be read). */
    fun bundledTags(ctx: Context): Map<String, Set<String>>? {
        bundledCache?.let { return it }
        return try {
            val m = mapOf(
                "ip" to GeoDat.tags(File(ctx.filesDir, "geoip.dat").readBytes()).toSet(),
                "site" to GeoDat.tags(File(ctx.filesDir, "geosite.dat").readBytes()).toSet(),
            )
            if (m.values.any { it.isEmpty() }) null else m.also { bundledCache = it }
        } catch (_: Throwable) { null }
    }

    private fun download(url: String, dest: File): List<String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "AntiBS-client")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val tmp = File(dest.parentFile, dest.name + ".part")
            var total = 0L
            c.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        total += n; if (total > MAX_BYTES) throw IllegalStateException("файл слишком большой")
                        out.write(buf, 0, n)
                    }
                }
            }
            val tags = GeoDat.tags(tmp.readBytes())
            if (tags.isEmpty()) throw IllegalStateException("пустой файл")
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
            return tags
        } finally { c.disconnect() }
    }

    /** Downloads what [p] still lacks; returns the profile with file names + tags filled in. */
    suspend fun ensure(ctx: Context, p: RoutingProfile): RoutingProfile = withContext(Dispatchers.IO) {
        var out = p
        if (p.geoipUrl.isNotBlank() && p.geoipFile.isBlank()) {
            val f = File(ctx.filesDir, fileName(p.geoipUrl, "ip"))
            val tags = if (f.length() > 0) runCatching { GeoDat.tags(f.readBytes()) }.getOrNull() else null
            out = out.copy(geoipFile = f.name, geoipTags = tags ?: download(p.geoipUrl, f))
        }
        if (p.geositeUrl.isNotBlank() && p.geositeFile.isBlank()) {
            val f = File(ctx.filesDir, fileName(p.geositeUrl, "site"))
            val tags = if (f.length() > 0) runCatching { GeoDat.tags(f.readBytes()) }.getOrNull() else null
            out = out.copy(geositeFile = f.name, geositeTags = tags ?: download(p.geositeUrl, f))
        }
        out
    }

    /** Removes downloaded geo files no profile refers to. */
    fun prune(ctx: Context, profiles: List<RoutingProfile>) {
        val keep = profiles.flatMap { listOf(it.geoipFile, it.geositeFile) }.filter { it.isNotBlank() }.toSet()
        ctx.filesDir.listFiles()?.filter { (it.name.startsWith("geoip-") || it.name.startsWith("geosite-")) && it.name.endsWith(".dat") && it.name !in keep }
            ?.forEach { it.delete() }
    }
}
