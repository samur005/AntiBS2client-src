package net.antibs.client.engine

import net.antibs.client.data.Hwid
import net.antibs.client.data.RemoteSubscription
import net.antibs.client.data.NestedSubscriptions
import net.antibs.client.data.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a subscription. Uses the qWDTT app's User-Agent ("qWDTT-Subscription/1.0") because:
 *  - the LucX panel only emits the multi-profile qWDTT JSON for that UA prefix (IsQwdttAppClient) and
 *    serves it before the HWID gate when no X-HWID is sent (serveQwdttAppNoHwid);
 *  - Remnawave answers that UA with the plain base64 share-link list (verified).
 * 1.0.18 HWID: when «Отправлять HWID» is on, x-hwid / x-device-os / x-ver-os / x-device-model are sent
 * (Remnawave HWID device limit). Order of attempts: [Hwid.attempts]. When it's off, no HWID/device header
 * is ever sent. HWID-limit refusals are turned into a clear message ([Hwid.problemMessage]).
 */
object SubscriptionFetcher {
    const val UA = "qWDTT-Subscription/1.0"
    /**
     * 1.0.19: neutral UA for the second request. Panels that serve both qWDTT and share links (LucX «subbota»)
     * answer the qWDTT UA with the qWDTT-only JSON; any other UA gets the full link list (vless, trojan, hy2, naive,
     * anytls, mieru, AmneziaWG, … and the same qWDTT hosts as qwdtt:// links).
     */
    const val LINKS_UA = "AntiBS-client"

    data class Raw(val code: Int, val body: String, val headers: Map<String?, String?>)

    /** Every header of one request (pure; used by tests). */
    fun headersFor(hwid: String?, osRelease: String?, model: String?, ua: String = UA): Map<String, String> =
        linkedMapOf("User-Agent" to ua, "Accept" to "application/json, text/plain, */*") + Hwid.requestHeaders(hwid, osRelease, model)

    /**
     * 1.0.19: the qWDTT-only answer vs. the full link list of the same subscription. The link list wins when it
     * has more hosts; its qwdtt:// hosts keep the ids of the matching qWDTT JSON profiles (selection survives) and
     * the qWDTT JSON's title / description / traffic are kept.
     */
    fun mergeQwdttWithLinks(q: RemoteSubscription, links: RemoteSubscription?): RemoteSubscription {
        if (links == null || links.servers.size <= q.servers.size) return q
        val byKey = q.servers.associateBy { "${it.name}|${it.qwdtt?.peer}" }
        val used = HashSet<String>()
        val servers = links.servers.map { s ->
            val match = if (s.engine == net.antibs.client.data.EngineKind.QWDTT) byKey["${s.name}|${s.qwdtt?.peer}"] else null
            if (match != null && used.add(match.id)) s.copy(id = match.id) else s
        }
        return q.copy(
            // keep "qwdtt": the panel answered as a qWDTT (LucX) panel, so later refreshes keep its no-HWID-first order
            kind = q.kind,
            title = q.title ?: links.title,
            servers = servers,
            upload = maxOf(q.upload, links.upload), download = maxOf(q.download, links.download),
            total = maxOf(q.total, links.total), expireEpoch = maxOf(q.expireEpoch, links.expireEpoch),
        )
    }

    /** 1.0.34: UA Remnawave answers with Xray JSON when «serveJsonAtBaseSubscription» is on (Happ-like client). */
    const val JSON_UA = "Happ/3.0.0"

    /** Candidate requests for a subscription's Xray JSON: base URL with a JSON client UA, then Remnawave's /json, /v2ray-json. */
    fun jsonCandidates(url: String): List<Pair<String, String>> {
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val path = url.substring(0, q).trimEnd('/')
        val query = url.substring(q)
        return listOf(url to JSON_UA, "$path/json$query" to UA, "$path/v2ray-json$query" to UA)
    }

    private fun jsonConfigs(url: String, hwid: String?): List<kotlinx.serialization.json.JsonObject>? {
        for ((u, ua) in jsonCandidates(url)) {
            val r = try { get(u, hwid, ua) } catch (_: Exception) { continue }
            if (r.code !in 200..299) continue
            val cfgs = net.antibs.client.data.AutoSelect.parseConfigs(r.body)
            if (net.antibs.client.data.AutoSelect.jsonAutoConfigs(cfgs).isNotEmpty()) return cfgs
        }
        return null
    }

    private fun readLimited(stream: java.io.InputStream, maxBytes: Int): String {
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun get(
        url: String,
        hwid: String?,
        ua: String = UA,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        maxBytes: Int = NestedSubscriptions.MAX_BODY_BYTES,
    ): Raw {
        val req = NestedSubscriptions.requestUrl(url)
        val conn = (URL(req).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            headersFor(hwid, System.getProperty("os.version") ?: "10", "Windows PC", ua).forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readLimited(it, maxBytes) }.orEmpty()
            val headers = conn.headerFields.orEmpty().mapValues { (_, v) -> v?.lastOrNull() }
            return Raw(code, body, headers)
        } finally {
            conn.disconnect()
        }
    }

    /** Child parse only — never expands nested lists (no second level). */
    private fun parseChildDirect(body: String, subscriptionId: String, headers: SubscriptionParser.SubHeaders): RemoteSubscription? {
        if (NestedSubscriptions.exclusiveChildUrls(body) != null) return null
        return SubscriptionParser.parse(body, subscriptionId, headers)?.takeIf { it.servers.isNotEmpty() }
    }

    /**
     * @param hwid HWID to send, or null when sending is disabled (no x-hwid / device headers at all).
     * @param kind stored subscription kind ("qwdtt" keeps the no-HWID-first order).
     */
    suspend fun fetch(url: String, subscriptionId: String, hwid: String?, kind: String = "auto"): Result<RemoteSubscription> = withContext(Dispatchers.IO) {
        try {
            val originalUrl = url.trim()
            require(originalUrl.startsWith("http://", true) || originalUrl.startsWith("https://", true)) { "URL должен начинаться с http(s)://" }
            val u = NestedSubscriptions.requestUrl(originalUrl)
            val plan = Hwid.attempts(kind, hwid)
            var raw: Raw? = null
            var sent = false
            var hwidProblem: String? = null
            for ((i, withHwid) in plan.withIndex()) {
                raw = get(u, if (withHwid) hwid else null)
                sent = withHwid
                val r = raw
                val problem = Hwid.problemMessage(r.code, r.headers, sent, serversEmpty = r.body.isBlank())
                if (r.code in 200..299 && problem == null) break
                if (problem != null && withHwid) { hwidProblem = problem; break }   // panel judged our HWID: don't retry without it
                if (problem != null) hwidProblem = problem
                val last = i == plan.lastIndex
                // qWDTT order retries only on 403/404; HWID-first order retries on any refusal.
                if (!last && plan[0] == false && r.code != 403 && r.code != 404) break
            }
            val r = raw!!
            hwidProblem?.let { if (r.code !in 200..299 || Hwid.limitReached(r.headers)) return@withContext Result.failure(IllegalStateException(it)) }
            if (r.code !in 200..299) return@withContext Result.failure(IllegalStateException("HTTP ${r.code}"))
            val outerHeaders = SubscriptionParser.parseHeaders(r.headers)
            var parsed = SubscriptionParser.parse(r.body, subscriptionId, outerHeaders)
            if (parsed != null && parsed.kind == "qwdtt") {
                // The qWDTT UA may have hidden the panel's other protocols — ask once more for the link list.
                val second = try {
                    val r2 = get(u, if (sent) hwid else null, LINKS_UA)
                    if (r2.code in 200..299) SubscriptionParser.parse(r2.body, subscriptionId, SubscriptionParser.parseHeaders(r2.headers)) else null
                } catch (_: Exception) { null }
                parsed = mergeQwdttWithLinks(parsed, second)
            }
            val p0 = parsed
            if (p0 != null && p0.servers.any { net.antibs.client.data.AutoSelect.isSubscriptionAuto(it) }) {
                // 1.0.34: the link list only carries a placeholder for the panel's auto-select — fetch its Xray JSON
                // (balancer + observatory) so the app runs exactly what the panel defines.
                val cfgs = jsonConfigs(u, if (sent) hwid else null)
                if (cfgs != null) parsed = p0.copy(servers = net.antibs.client.data.AutoSelect.attachJson(p0.servers, cfgs))
            }
            if (parsed == null || parsed.servers.isEmpty()) {
                val nested = NestedSubscriptions.expand(
                    parentUrl = originalUrl,
                    outerBody = r.body,
                    outerHeaders = outerHeaders,
                    fetchChild = { childUrl ->
                        val cr = get(
                            childUrl,
                            if (sent) hwid else null,
                            UA,
                            connectTimeoutMs = NestedSubscriptions.CHILD_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = NestedSubscriptions.CHILD_READ_TIMEOUT_MS,
                        )
                        if (cr.code !in 200..299) null
                        else parseChildDirect(cr.body, subscriptionId, SubscriptionParser.parseHeaders(cr.headers))
                    },
                )
                if (nested != null) parsed = nested
            }
            if (parsed == null || parsed.servers.isEmpty()) {
                Hwid.problemMessage(r.code, r.headers, sent, serversEmpty = true)?.let { return@withContext Result.failure(IllegalStateException(it)) }
            }
            parsed ?: return@withContext Result.failure(IllegalArgumentException("Неизвестный формат подписки"))
            if (parsed.servers.isEmpty()) return@withContext Result.failure(IllegalArgumentException("В подписке нет серверов"))
            val withExpiry = if (parsed.expireEpoch == 0L && parsed.description.isNotEmpty())
                parsed.copy(expireEpoch = SubscriptionParser.expiryFromDescription(parsed.description)) else parsed
            Result.success(withExpiry)
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("Таймаут соединения"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
