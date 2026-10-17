package net.antibs.client.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 1.0.41: olcRTC (openlibrecommunity/olcrtc, WTFPL) — TCP over WebRTC (Jitsi / Яндекс Телемост / WB Stream).
 * Runs as libolcrtc.so (native/olcrtc-lite) with a loopback SOCKS5 that Xray dials, like the other helpers.
 *
 * Stored as an [XrayProfile] with protocol "olcrtc": [XrayProfile.method] = provider, [XrayProfile.network] = transport,
 * [XrayProfile.server] = room id / URL, [XrayProfile.password] = 64-hex key, [XrayProfile.olc] = transport params
 * (URI payload keys), [XrayProfile.username] = optional provider token, [XrayProfile.path] = optional channel.
 *
 * URI convention (docs/uri.md, v1):
 *   olcrtc://<Provider>?<Transport>[<key=value&…>]@<RoomID>#<EncryptionKey>$<Comment>
 */
object Olcrtc {
    const val PROTOCOL = "olcrtc"
    const val BASE_PORT = 11200
    val PROVIDERS = listOf("jitsi", "telemost", "wbstream")
    val TRANSPORTS = listOf("datachannel", "vp8channel", "seichannel", "videochannel")
    val PROVIDER_LABELS = mapOf("jitsi" to "Jitsi Meet", "telemost" to "Яндекс Телемост", "wbstream" to "WB Stream")
    /** URI payload key → JSON field of libolcrtc.so. */
    val PARAMS = linkedMapOf(
        "vp8-fps" to "vp8_fps", "vp8-batch" to "vp8_batch",
        "fps" to "sei_fps", "batch" to "sei_batch", "frag" to "sei_frag", "ack-ms" to "sei_ack_ms",
        "video-w" to "video_w", "video-h" to "video_h", "video-fps" to "video_fps", "video-codec" to "video_codec",
        "video-qr-size" to "video_qr_size", "video-qr-recovery" to "video_qr_recovery",
        "video-tile-module" to "video_tile_module", "video-tile-rs" to "video_tile_rs",
    )
    private val STRING_PARAMS = setOf("video-codec", "video-qr-recovery")
    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    fun isLink(s: String) = s.trim().startsWith("olcrtc://", ignoreCase = true)

    fun keyOk(k: String?) = k != null && hex64.matches(k.trim())

    /** Parses one olcrtc:// URI. Throws IllegalArgumentException with a Russian message on a broken link. */
    fun parse(uri0: String): XrayProfile {
        val uri = uri0.trim()
        require(isLink(uri)) { "не olcrtc:// ссылка" }
        var rest = uri.substring("olcrtc://".length)
        // comment ($) — may contain anything, take the LAST '$' after '#'
        val hash = rest.indexOf('#')
        require(hash > 0) { "olcrtc: нет ключа (#…)" }
        val afterHash = rest.substring(hash + 1)
        val dollar = afterHash.indexOf('$')
        val key = (if (dollar >= 0) afterHash.substring(0, dollar) else afterHash).trim()
        val comment = if (dollar >= 0) pct(afterHash.substring(dollar + 1)).trim() else ""
        rest = rest.substring(0, hash)
        val at = rest.indexOf('@')
        require(at > 0) { "olcrtc: нет комнаты (@…)" }
        val room = pct(rest.substring(at + 1)).trim()
        val head = rest.substring(0, at)
        val q = head.indexOf('?')
        val provider = (if (q >= 0) head.substring(0, q) else head).trim().lowercase()
        var transport = if (q >= 0) head.substring(q + 1) else "datachannel"
        val params = LinkedHashMap<String, String>()
        val lt = transport.indexOf('<')
        if (lt >= 0) {
            val gt = transport.lastIndexOf('>')
            val body = transport.substring(lt + 1, if (gt > lt) gt else transport.length)
            transport = transport.substring(0, lt)
            body.split('&').forEach { kv ->
                val k = kv.substringBefore('=').trim(); val v = kv.substringAfter('=', "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) params[k] = v
            }
        }
        transport = transport.trim().lowercase().ifEmpty { "datachannel" }
        require(provider in PROVIDERS) { "olcrtc: неизвестный провайдер «$provider»" }
        require(transport in TRANSPORTS) { "olcrtc: неизвестный транспорт «$transport»" }
        require(room.isNotEmpty()) { "olcrtc: пустой Room ID" }
        require(keyOk(key)) { "olcrtc: ключ должен быть 64 hex-символа" }
        return XrayProfile(
            remarks = comment.ifEmpty { "olcRTC · ${PROVIDER_LABELS[provider] ?: provider}" },
            protocol = PROTOCOL, server = room, port = 0, method = provider, network = transport, password = key.lowercase(),
            olc = params.filterKeys { it in PARAMS }.takeIf { it.isNotEmpty() },
        )
    }

    private fun pct(s: String) = try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    fun toUri(p: XrayProfile): String = buildString {
        append("olcrtc://").append(p.method ?: "jitsi").append('?').append(p.network.ifBlank { "datachannel" })
        p.olc?.takeIf { it.isNotEmpty() }?.let { m -> append('<').append(m.entries.joinToString("&") { "${it.key}=${it.value}" }).append('>') }
        append('@').append(p.server).append('#').append(p.password.orEmpty())
        if (p.remarks.isNotBlank()) append('$').append(p.remarks)
    }

    /** JSON for `libolcrtc.so run|ping`. */
    fun clientJson(p: XrayProfile, port: Int, dns: String = "8.8.8.8:53", deviceId: String = ""): String = buildJsonObject {
        put("provider", p.method ?: "jitsi")
        put("transport", p.network.ifBlank { "datachannel" })
        put("room", p.server)
        put("key", p.password.orEmpty())
        put("port", port)
        put("dns", dnsHostPort(dns))
        p.username?.takeIf { it.isNotBlank() }?.let { put("token", it) }
        p.path?.takeIf { it.isNotBlank() }?.let { put("channel", it) }
        if (deviceId.isNotBlank()) put("device_id", deviceId)
        p.olc?.forEach { (k, v) ->
            val f = PARAMS[k] ?: return@forEach
            if (k in STRING_PARAMS) put(f, v) else v.toIntOrNull()?.let { put(f, it) }
        }
    }.toString()

    /** «1.1.1.1» / «1.1.1.1:53» / «https://…» → host:port (olcRTC needs plain DNS). */
    fun dnsHostPort(d: String): String {
        val s = d.trim().substringBefore(',').trim()
        if (s.isEmpty() || s.contains("://")) return "8.8.8.8:53"
        if (s.startsWith("[")) return if (s.contains("]:")) s else "$s:53"
        return if (s.count { it == ':' } == 1) s else if (s.contains(':')) "[$s]:53" else "$s:53"
    }

    fun validate(p: XrayProfile): String? = when {
        p.method !in PROVIDERS -> "Выберите провайдера"
        p.network !in TRANSPORTS -> "Выберите транспорт"
        p.server.isBlank() -> "Укажите Room ID / ссылку на комнату"
        !keyOk(p.password) -> "Ключ — 64 hex-символа (openssl rand -hex 32)"
        else -> null
    }
}
