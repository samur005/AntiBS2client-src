package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 1.0.19: app updates from the public GitHub repo samur005/AntiBS2client-releases (releases/latest, no auth).
 * Pure logic — version compare, release / SHA256SUMS parsing, asset choice, rate-limit messages.
 */
object UpdateLogic {
    const val DEFAULT_REPO = "samur005/AntiBS2client-releases"
    const val DEFAULT_URL = "https://api.github.com/repos/$DEFAULT_REPO/releases/latest"
    const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val version: String, val notes: String, val htmlUrl: String, val assets: List<Asset>)
    data class Update(val release: Release, val apk: Asset, val sums: Asset?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** "v1.0.19" / "1.0.19-beta" → [1, 0, 19]; null when it has no numeric part. */
    fun parts(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+').trim()
        if (core.isEmpty()) return null
        val p = core.split('.').map { it.toIntOrNull() ?: return null }
        return p.ifEmpty { null }
    }

    /** <0: a older than b, 0 equal, >0 newer. Unparseable versions compare as equal (never offer them). */
    fun compare(a: String, b: String): Int {
        val x = parts(a) ?: return 0
        val y = parts(b) ?: return 0
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(remote: String, current: String) = compare(remote, current) > 0

    /** Manual override: empty → default; "owner/repo" → its releases/latest; any https URL as is. */
    fun apiUrl(override: String): String {
        val o = override.trim().removeSuffix("/")
        return when {
            o.isEmpty() -> DEFAULT_URL
            o.startsWith("https://github.com/", true) -> {
                val repo = o.substring(19).split('/').take(2).joinToString("/")
                "https://api.github.com/repos/$repo/releases/latest"
            }
            o.startsWith("https://", true) || o.startsWith("http://", true) -> o
            Regex("""^[\w.-]+/[\w.-]+$""").matches(o) -> "https://api.github.com/repos/$o/releases/latest"
            else -> DEFAULT_URL
        }
    }

    fun parseRelease(text: String): Release? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val tag = s("tag_name").ifEmpty { return null }
        if ((o["draft"] as? JsonPrimitive)?.contentOrNull == "true") return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            val name = (ao["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val url = (ao["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Asset(name, url, (ao["size"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }
        return Release(tag, tag.removePrefix("v").removePrefix("V"), s("body").trim(), s("html_url"), assets)
    }

    /** APK for the first supported ABI, else the universal one. */
    fun pickApk(r: Release, abis: List<String>): Asset? {
        val apks = r.assets.filter { it.name.endsWith(".apk", true) }
        for (abi in abis) apks.firstOrNull { it.name.endsWith("-$abi.apk", true) }?.let { return it }
        return apks.firstOrNull { it.name.endsWith("-universal.apk", true) }
    }

    fun sumsAsset(r: Release): Asset? =
        r.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", true) || it.name.equals("SHA256SUMS", true) }
            ?: r.assets.firstOrNull { it.name.lowercase().let { n -> n.contains("sha256") && (n.endsWith(".txt") || !n.contains('.')) } }

    /** `sha256sum` output ("<hex>  <name>" or "<hex> *<name>") → name → lower-case hex. */
    fun parseSums(text: String): Map<String, String> = text.lines().mapNotNull { line ->
        val m = Regex("""^\s*([0-9a-fA-F]{64})\s+\*?(\S.*?)\s*$""").find(line) ?: return@mapNotNull null
        m.groupValues[2].substringAfterLast('/') to m.groupValues[1].lowercase()
    }.toMap()

    fun findUpdate(r: Release, current: String, abis: List<String>): Update? {
        if (!isNewer(r.version, current)) return null
        val apk = pickApk(r, abis) ?: return null
        return Update(r, apk, sumsAsset(r))
    }

    fun shouldAutoCheck(enabled: Boolean, last: Long, now: Long): Boolean =
        enabled && (last <= 0 || now - last >= AUTO_INTERVAL_MS || now < last)

    /**
     * GitHub's unauthenticated limit is 60 requests/hour per IP: 403/429 with x-ratelimit-remaining: 0.
     * Returns a user message, or null when the response isn't a rate-limit one.
     */
    fun rateLimitMessage(code: Int, headers: Map<String, String>, nowSec: Long): String? {
        val h = headers.mapKeys { it.key.lowercase() }
        val limited = (code == 403 || code == 429) && (h["x-ratelimit-remaining"] == "0" || h.containsKey("retry-after") || code == 429)
        if (!limited) return null
        val reset = h["x-ratelimit-reset"]?.toLongOrNull()
        val retry = h["retry-after"]?.toLongOrNull()
        val waitSec = when { reset != null -> reset - nowSec; retry != null -> retry; else -> null }
        val mins = waitSec?.let { ((it + 59) / 60).coerceAtLeast(1) }
        return "Лимит запросов GitHub исчерпан (60 в час)" + (mins?.let { " — повторите через $it мин." } ?: ", повторите позже.")
    }
}
