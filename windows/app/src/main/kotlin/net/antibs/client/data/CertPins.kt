package net.antibs.client.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 1.0.19: Xray-core 26 removed tlsSettings.allowInsecure (config fails: "The feature "allowInsecure" has been
 * removed and migrated to "pinnedPeerCertSha256""). Hosts marked insecure (allowInsecure=1 / insecure=1 links,
 * allowInsecure in JSON) are now pinned to the certificate the server presents right now — the same migration
 * v2rayNG does. [fetcher] is installed by the Android side (libv2ray FetchTlsCertSha256 / FetchQuicCertSha256).
 */
object CertPins {
    /** (host, port, serverName, quic) → hex SHA-256 of the leaf certificate, or null. */
    @Volatile var fetcher: ((String, Int, String?, Boolean) -> String?)? = null
    private val cache = ConcurrentHashMap<String, String>()

    fun pinFor(host: String, port: Int, serverName: String?, quic: Boolean): String? {
        if (host.isBlank() || port !in 1..65535) return null
        val key = "$host|$port|${serverName.orEmpty()}|$quic"
        cache[key]?.let { return it }
        val v = try { fetcher?.invoke(host, port, serverName, quic) } catch (_: Throwable) { null }
        val hex = v?.trim()?.lowercase()?.replace(":", "")?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) } ?: return null
        cache[key] = hex
        return hex
    }

    fun clear() = cache.clear()
}
