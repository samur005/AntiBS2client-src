package net.antibs.client.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 2.0.12+/2.0.25: LAN subscription transfer over the shared wire format used by peer apps on the same Wi‑Fi/hotspot.
 *
 * Wire (must stay literal — changing them breaks discovery/interop with existing receivers/senders):
 *  - TCP 45571: sender writes one UTF-8 JSON body until EOF, no mandatory ACK.
 *    Plain: {"code":"482913","subs":[{…subscription metadata field names as on the wire…}]}
 *    Optional sealed (AntiBS2client QR only): {"v":2,"iv":…,"ct":…} AES-256-GCM, AAD from [SCHEME].
 *  - UDP 45572: ask [DISC_ASK], reply [DISC_REPLY]+model(max 60).
 *  - QR session URI scheme [SCHEME] (AntiBS2client↔AntiBS2client encrypted path). Peer apps that only show
 *    IP+PIN and do not scan this QR still accept the plain TCP body with the 6-digit code.
 *
 * Our optional extension (ignored if the peer closed after the write): after half-close, receiver may answer
 * {"ok":true,"n":k} / {"ok":false,"err":…}. Send-write success alone does NOT prove the peer imported.
 */
object LanProtocol {
    const val PORT = 45571
    const val DISC_PORT = 45572
    // Wire literals required for interop — do not rename for branding.
    const val DISC_ASK = "ABS_DISCOVER_V1"
    const val DISC_REPLY = "ABS_HERE_V1|"
    const val SCHEME = "abs-lan:1"
    const val TTL_SEC = 600L
    const val MAX_WRONG = 5
    const val MAX_BODY = 2 * 1024 * 1024

    private val rnd = SecureRandom()
    private val b64e = java.util.Base64.getUrlEncoder().withoutPadding()
    private val b64d = java.util.Base64.getUrlDecoder()

    class Session(val ip: String, val port: Int, val code: String, val key: ByteArray, val expires: Long) {
        fun expired(nowSec: Long = System.currentTimeMillis() / 1000) = nowSec > expires
    }

    fun newCode(): String = (100000 + rnd.nextInt(900000)).toString()
    fun newKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }
    fun newSession(ip: String, code: String, nowSec: Long = System.currentTimeMillis() / 1000) =
        Session(ip, PORT, code, newKey(), nowSec + TTL_SEC)

    fun payload(s: Session): String = "$SCHEME?h=${s.ip}&p=${s.port}&c=${s.code}&k=${b64e.encodeToString(s.key)}&e=${s.expires}"

    /** Validate receiver QR: host chars, port range, non-blank code, 32-byte key. */
    fun parseQr(text: String): Session? = runCatching {
        val t = text.trim()
        if (!t.startsWith("$SCHEME?")) return null
        val q = t.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val ip = q["h"].orEmpty(); val port = q["p"]?.toIntOrNull() ?: return null
        val code = q["c"].orEmpty(); val key = b64d.decode(q["k"].orEmpty()); val exp = q["e"]?.toLongOrNull() ?: return null
        if (ip.isBlank() || ip.length > 64 || ip.any { !(it.isLetterOrDigit() || it == '.' || it == ':') } || port !in 1..65535 ||
            code.isBlank() || key.size != 32) return null
        Session(ip, port, code, key, exp)
    }.getOrNull()

    fun seal(key: ByteArray, plain: String, iv: ByteArray = ByteArray(12).also { rnd.nextBytes(it) }): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        c.updateAAD(SCHEME.toByteArray())
        return buildJsonObject {
            put("v", 2); put("iv", b64e.encodeToString(iv)); put("ct", b64e.encodeToString(c.doFinal(plain.toByteArray())))
        }.toString()
    }

    fun open(key: ByteArray, body: JsonObject): String? = runCatching {
        val iv = b64d.decode(body["iv"]!!.jsonPrimitive.content); val ct = b64d.decode(body["ct"]!!.jsonPrimitive.content)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        c.updateAAD(SCHEME.toByteArray())
        String(c.doFinal(ct))
    }.getOrNull()

    // ---- subscription JSON (shared wire field names) ----
    fun toAbs(s: Subscription): JsonObject = buildJsonObject {
        put("id", s.id); put("name", s.title.ifBlank { s.url }); put("url", s.url)
        put("used", s.upload + s.download); put("total", s.total); put("expire", s.expireEpoch)
        put("lastUpdated", s.lastRefresh); put("title", s.title); put("interval", s.updateIntervalHours); put("updateAlways", false)
        put("supportUrl", s.supportUrl); put("webPageUrl", s.webPageUrl); put("announce", s.announce); put("announceUrl", "")
        put("routing", s.routing); put("hideSettings", false); put("deviceLimit", false); put("hwidActive", false)
        put("lastCount", s.serverCount); put("lastReceived", 0); put("lastSkipped", 0)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L


    private val serverJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val SERVER_PREFIX = "antibs-server:1,"

    /** Encode one local server so the existing LAN «subs» wire can carry it. */
    fun serverAsSub(e: ServerEntry): Subscription {
        val raw = serverJson.encodeToString(ServerEntry.serializer(), e.copy(subscriptionId = ProtocolSupport.MANUAL_SUB_ID))
        val url = SERVER_PREFIX + b64e.encodeToString(raw.toByteArray(Charsets.UTF_8))
        return Subscription(id = e.id, url = url, title = e.name.ifBlank { "Сервер" }, kind = "server")
    }

    fun parseServerWire(url: String): ServerEntry? {
        if (!url.startsWith(SERVER_PREFIX)) return null
        return runCatching {
            val raw = String(b64d.decode(url.removePrefix(SERVER_PREFIX)), Charsets.UTF_8)
            serverJson.decodeFromString(ServerEntry.serializer(), raw)
                .copy(id = "m-" + java.util.UUID.randomUUID().toString().take(8), subscriptionId = ProtocolSupport.MANUAL_SUB_ID)
        }.getOrNull()
    }

    /** null for an entry without an http(s) url. */
    fun fromAbs(o: JsonObject, id: String): Subscription? {
        val url = o.str("url").trim()
        if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return null
        val title = o.str("name").ifBlank { o.str("title") }.ifBlank { url }
        return Subscription(
            id = id, url = url, title = title, download = o.long("used"), total = o.long("total"), expireEpoch = o.long("expire"),
            updateIntervalHours = o.long("interval").toInt(), supportUrl = o.str("supportUrl"), webPageUrl = o.str("webPageUrl"),
            announce = o.str("announce"), routing = o.str("routing"),
        )
    }

    fun plainBody(code: String, subs: List<Subscription>): String = buildJsonObject {
        put("code", code); put("subs", JsonArray(subs.map { toAbs(it) }))
    }.toString()

    sealed class Incoming {
        data class Ok(val subs: List<JsonObject>, val sealed: Boolean) : Incoming()
        data class Rejected(val reason: String, val wrongCode: Boolean = false) : Incoming()
    }

    /** Decode one received body against the current [code] / optional QR [session]. */
    fun decode(text: String, code: String, session: Session?, nowSec: Long = System.currentTimeMillis() / 1000): Incoming {
        val raw = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Incoming.Rejected("bad json")
        val sealed = raw.containsKey("ct")
        val o = if (!sealed) raw else {
            val s = session ?: return Incoming.Rejected("no session")
            if (s.expired(nowSec)) return Incoming.Rejected("expired")
            val p = open(s.key, raw) ?: return Incoming.Rejected("bad key")
            runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() ?: return Incoming.Rejected("bad json")
        }
        if (o.str("code") != code) return Incoming.Rejected("code", wrongCode = true)
        val arr = (o["subs"] as? JsonArray) ?: return Incoming.Rejected("no subs")
        return Incoming.Ok(arr.mapNotNull { it as? JsonObject }, sealed)
    }

    fun ack(n: Int) = buildJsonObject { put("ok", true); put("n", n) }.toString()
    fun nack(err: String) = buildJsonObject { put("ok", false); put("err", err) }.toString()
    /** true/false from our receiver's answer; null = no answer (peer closed) or unreadable. */
    fun parseAck(text: String): Pair<Boolean, Int>? = runCatching {
        val o = Json.parseToJsonElement(text.trim()).jsonObject
        val ok = o["ok"]!!.jsonPrimitive.boolean
        ok to ((o["n"] as? JsonPrimitive)?.intOrNull ?: 0)
    }.getOrNull()

    // ---- discovery / addresses ----
    fun parseReply(text: String): String? =
        if (text.startsWith(DISC_REPLY)) text.removePrefix(DISC_REPLY).trim().take(60).ifEmpty { "Устройство" } else null
    fun reply(model: String) = DISC_REPLY + model.ifBlank { "AntiBS2client" }.take(60)

    fun isPrivate(a: String) = a.startsWith("192.168.") || a.startsWith("10.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(a)

    /** Best LAN IPv4: private only, VPN tun skipped; hotspot > Wi-Fi > wired/USB/BT > other. */
    fun pickLanIp(candidates: List<Pair<String, String>>): String? {
        val ok = candidates.filter { (n, a) -> a.isNotBlank() && isPrivate(a) && !n.startsWith("tun") && a != "10.10.0.2" }
        return ok.minByOrNull { rank(it.first) }?.second
    }

    fun rank(n: String) = when {
        n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 0
        n.startsWith("wlan") -> 1
        n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("bt-pan") -> 2
        else -> 3
    }

    /** Hosts of the /24 around [ip] (own address excluded). Wider prefixes are not scanned. */
    fun subnetHosts(ip: String): List<String> {
        val p = ip.split('.')
        if (p.size != 4 || p.any { it.toIntOrNull() !in 0..255 }) return emptyList()
        val base = "${p[0]}.${p[1]}.${p[2]}."
        return (1..254).map { base + it }.filter { it != ip }
    }

    /** Interfaces discovery may broadcast on: Wi-Fi, hotspot, Ethernet/USB tethering only (never cellular rmnet/ccmni, never tun). */
    fun isLanIface(n: String) = rank(n) <= 2

    /** "192.168.1.5" / "192.168.1.5:45571" → host to port; null when malformed. */
    fun parseAddress(s: String): Pair<String, Int>? {
        val t = s.trim(); if (t.isEmpty()) return null
        val host = t.substringBefore(':'); val port = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return null else PORT
        if (port !in 1..65535 || host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        return host to port
    }
}
