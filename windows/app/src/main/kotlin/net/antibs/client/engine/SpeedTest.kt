package net.antibs.client.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Built-in internet speed test against Cloudflare (speed.cloudflare.com), modelled on the official speedtest.
 *
 * 1.0.22: rewritten on OkHttp (HTTP/1.1 only so every stream is its own TCP connection, connection pool reuse,
 * proper SNI/TLS via the platform, SOCKS proxy for «Через VPN», network-bound socket factory + DNS for «Напрямую»).
 *  - latency: [Config.latencyCount] `__down?bytes=0` on a warm keep-alive connection, header time minus the
 *    server's own processing (Server-Timing); jitter = mean |Δ| of consecutive samples;
 *  - download / upload: [Config.downStreams]/[Config.upStreams] parallel workers looping requests of growing size
 *    for a fixed time budget; a failed / timed-out / 403 / 429 request never aborts the phase — the worker backs off
 *    (smaller size, short pause) and continues until the budget ends;
 *  - throughput: aggregate bytes sampled every [Config.sampleMs]; live value = EMA of samples, final = p90 of the
 *    samples after the TCP warm-up.
 * Blocking — call from a background thread; [cancel] aborts promptly.
 */
class SpeedTest(
    private val client: OkHttpClient,
    private val cfg: Config = Config(),
    private val listener: (Progress) -> Unit = {},
) {
    data class Config(
        val host: String = "speed.cloudflare.com",
        val latencyCount: Int = 20,
        /** __down rejects ≥ 100 MB (HTTP 403), so the largest step is 50 MB. */
        val downSizes: List<Long> = listOf(100_000, 1_000_000, 10_000_000, 25_000_000, 50_000_000),
        val upSizes: List<Long> = listOf(100_000, 1_000_000, 10_000_000, 25_000_000),
        val downStreams: Int = 6,
        val upStreams: Int = 4,
        val downMs: Long = 10_000,
        val upMs: Long = 10_000,
        /** Escalate a stream to the next size while a request takes less than this. */
        val targetRequestMs: Long = 1_200,
        val sampleMs: Long = 200,
        /** TCP slow-start warm-up excluded from the result. */
        val warmupMs: Long = 1_500,
        /** Live needle: rolling aggregate over this window. */
        val liveWindowMs: Long = 1_000,
        /** After the upload budget: wait this long for in-flight requests to be acknowledged. */
        val uploadGraceMs: Long = 4_000,
        /** Data cap per phase: a fast link ends the phase early instead of burning mobile data. */
        val maxDownBytes: Long = 400_000_000,
        val maxUpBytes: Long = 200_000_000,
    )

    enum class Phase { META, LATENCY, DOWNLOAD, UPLOAD, DONE }

    /** Live state; [pingMs]/[jitterMs]/[downMbps]/[upMbps] are the final values of phases already finished. */
    data class Progress(
        val phase: Phase, val fraction: Float, val mbps: Double = 0.0, val pingMs: Double? = null,
        val jitterMs: Double? = null, val downMbps: Double? = null, val upMbps: Double? = null,
    )

    data class Meta(val colo: String = "", val city: String = "", val country: String = "", val ip: String = "", val asn: String = "")

    data class Result(
        val downMbps: Double?,
        val upMbps: Double?,
        val pingMs: Double?,
        val jitterMs: Double?,
        val meta: Meta,
        val error: String? = null,
        val downBytes: Long = 0,
        val upBytes: Long = 0,
    )

    @Volatile private var cancelled = false
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val base = "https://${cfg.host}"

    fun cancel() {
        cancelled = true
        synchronized(calls) { calls.toList() }.forEach { it.cancel() }
    }

    val isCancelled get() = cancelled

    @Volatile private var ping: Double? = null
    @Volatile private var jitter: Double? = null
    @Volatile private var down: Double? = null
    @Volatile private var up: Double? = null

    private fun emit(phase: Phase, fraction: Float, mbps: Double = 0.0, livePing: Double? = ping) =
        listener(Progress(phase, fraction, mbps, livePing, jitter, down, up))

    fun run(): Result {
        var meta = Meta()
        var downBytes = 0L
        var upBytes = 0L
        try {
            emit(Phase.META, 0f)
            meta = fetchMeta()
            checkCancel()
            emit(Phase.LATENCY, 0f)
            get("/__down?bytes=0") // warm the connection (TCP + TLS handshake not counted)
            val lat = mutableListOf<Double>()
            var fails = 0
            for (i in 0 until cfg.latencyCount) {
                checkCancel()
                try {
                    val t0 = System.nanoTime()
                    val r = get("/__down?bytes=0", t0Holder = t0)
                    lat += SpeedMath.latencyMs(r.headerNanos - t0, r.serverTiming)
                    if (meta.colo.isEmpty()) r.colo?.let { meta = meta.copy(colo = it) }
                } catch (e: IOException) {
                    if (cancelled || ++fails > 5) throw e
                }
                emit(Phase.LATENCY, (i + 1f) / cfg.latencyCount, livePing = SpeedMath.median(lat))
            }
            ping = SpeedMath.median(lat)
            jitter = SpeedMath.jitter(lat)
            checkCancel()
            val d = transfer(Phase.DOWNLOAD, cfg.downSizes, cfg.downStreams, cfg.downMs, cfg.maxDownBytes) { size, counter -> download(size, counter) }
            down = d.first; downBytes = d.second
            emit(Phase.DOWNLOAD, 1f, 0.0) // needle back to 0 between phases
            checkCancel()
            val u = transfer(Phase.UPLOAD, cfg.upSizes, cfg.upStreams, cfg.upMs, cfg.maxUpBytes) { size, counter -> upload(size, counter) }
            up = u.first; upBytes = u.second
            checkCancel()
            emit(Phase.DONE, 1f, 0.0)
            return Result(down, up, ping, jitter, meta, if (down == null && up == null) "Нет данных — сервер не отвечает" else null, downBytes, upBytes)
        } catch (e: Exception) {
            val msg = if (cancelled) "Остановлено" else SpeedMath.errorText(e)
            return Result(down, up, ping, jitter, meta, msg, downBytes, upBytes)
        } finally {
            synchronized(calls) { calls.toList() }.forEach { it.cancel() }
        }
    }

    private fun checkCancel() { if (cancelled) throw IOException("cancelled") }

    private class Head(val code: Int, val headerNanos: Long, val serverTiming: String?, val colo: String?, val body: String?)

    private fun <T> exec(req: Request, block: (okhttp3.Response) -> T): T {
        val call = client.newCall(req)
        calls += call
        try {
            if (cancelled) throw IOException("cancelled")
            return call.execute().use(block)
        } finally { calls -= call }
    }

    private fun get(path: String, keepBody: Boolean = false, t0Holder: Long = 0, extra: Map<String, String> = emptyMap()): Head {
        val rb = Request.Builder().url(base + path).header("User-Agent", "AntiBS2client")
        extra.forEach { (k, v) -> rb.header(k, v) }
        return exec(rb.build()) { r ->
            val t = System.nanoTime()
            if (!r.isSuccessful) throw HttpStatus(r.code)
            val body = if (keepBody) r.body?.string() else { r.body?.source()?.let { while (!it.exhausted()) it.skip(it.buffer.size.coerceAtLeast(1)) }; null }
            Head(r.code, t, r.headers("server-timing").joinToString(", "), r.header("colo") ?: r.header("cf-meta-colo"), body)
        }
    }

    class HttpStatus(val code: Int) : IOException("HTTP $code")

    private fun download(size: Long, counter: (Long) -> Unit) {
        exec(Request.Builder().url("$base/__down?bytes=$size").header("User-Agent", "AntiBS2client").build()) { r ->
            if (!r.isSuccessful) throw HttpStatus(r.code)
            val ins: InputStream = r.body?.byteStream() ?: throw IOException("пустой ответ")
            val buf = ByteArray(128 * 1024)
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val n = ins.read(buf)
                if (n < 0) break
                counter(n.toLong())
            }
        }
    }

    private fun upload(size: Long, counter: (Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                var left = size
                while (left > 0) {
                    if (cancelled) throw IOException("cancelled")
                    val n = minOf(left, UPLOAD_CHUNK.size.toLong()).toInt()
                    sink.write(UPLOAD_CHUNK, 0, n)
                    sink.flush() // count what actually left the app, not what sits in okio's buffer
                    counter(n.toLong())
                    left -= n
                }
            }
        }
        exec(Request.Builder().url("$base/__up").header("User-Agent", "AntiBS2client").post(body).build()) { r ->
            if (!r.isSuccessful) throw HttpStatus(r.code)
        }
    }

    private fun fetchMeta(): Meta {
        val trace = get("/cdn-cgi/trace", keepBody = true)
        val kv = SpeedMath.parseTrace(trace.body.orEmpty())
        var m = Meta(colo = kv["colo"] ?: "", country = kv["loc"] ?: "", ip = kv["ip"] ?: "")
        // Server city: /locations maps the colo (IATA code) to a city; it needs the official page as Referer.
        if (m.colo.isNotEmpty()) try {
            val loc = get("/locations", keepBody = true, extra = mapOf("Referer" to "$base/"))
            SpeedMath.cityForColo(loc.body.orEmpty(), m.colo)?.let { m = m.copy(city = it) }
        } catch (e: IOException) { if (cancelled) throw e }
        return m
    }

    /**
     * One throughput phase. Workers loop requests until the time budget / data cap; failures back off and retry
     * (403/429 → smaller size + longer pause).
     *
     * 1.0.23 — final value = AGGREGATE AVERAGE over the stable window (after [Config.warmupMs]), never a percentile
     * of short samples (1.0.21–1.0.22 took p90 of 200 ms samples, which picks the bursts and overstated results):
     *  - download: bytes actually read from the sockets in the window ÷ window wall-clock time;
     *  - upload: only bytes the server acknowledged (request finished with HTTP 200) count, spread linearly over
     *    each request's own start…end; when the budget ends no new requests start, in-flight ones may finish
     *    within [Config.uploadGraceMs], so nothing that is merely sitting in socket/OkHttp buffers is counted.
     * Live needle = bytes of the last ~1 s ÷ 1 s (for upload: bytes handed to the socket, display only).
     * Returns (Mbps or null, bytes counted).
     */
    private fun transfer(phase: Phase, sizes: List<Long>, streams: Int, durationMs: Long, maxBytes: Long, req: (Long, (Long) -> Unit) -> Unit): Pair<Double?, Long> {
        emit(phase, 0f)
        val upload = phase == Phase.UPLOAD
        val total = AtomicLong()
        val counter: (Long) -> Unit = { n -> total.addAndGet(n) }
        val done = Collections.synchronizedList(mutableListOf<SpeedMath.Req>())
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        val lastError = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        fun live() = !cancelled && !stop.get() && System.nanoTime() < deadline && total.get() < maxBytes
        val workers = (0 until streams.coerceAtLeast(1)).map { idx ->
            Thread({
                var sizeIdx = 0
                var backoff = 250L
                try {
                    Thread.sleep(idx * 50L) // stagger slow-start
                    while (live()) {
                        val t0 = System.nanoTime()
                        try {
                            val size = sizes[sizeIdx]
                            req(size, counter)
                            val t1 = System.nanoTime()
                            done += SpeedMath.Req(t0, t1, size)
                            backoff = 250L
                            sizeIdx = SpeedMath.nextSizeIndex(sizes.size, sizeIdx, (t1 - t0) / 1_000_000L, cfg.targetRequestMs)
                        } catch (e: Exception) {
                            if (!live()) break
                            lastError.set(e)
                            val limited = e is HttpStatus && (e.code == 403 || e.code == 429)
                            sizeIdx = SpeedMath.backoffSizeIndex(sizeIdx, limited)
                            Thread.sleep(if (limited) backoff * 2 else backoff)
                            backoff = (backoff * 2).coerceAtMost(2_000)
                        }
                    }
                } catch (_: InterruptedException) {}
            }, "speedtest-$phase-$idx").apply { isDaemon = true; start() }
        }
        val warmEnd = start + cfg.warmupMs * 1_000_000L
        var w0T = -1L
        var w0B = 0L
        val roll = ArrayDeque<Pair<Long, Long>>().apply { addLast(start to 0L) }
        var endT = start
        var endB = 0L
        while (!cancelled) {
            Thread.sleep(cfg.sampleMs)
            val now = System.nanoTime()
            val b = total.get()
            if (w0T < 0 && now >= warmEnd) { w0T = now; w0B = b }
            roll.addLast(now to b)
            while (roll.size > 2 && now - roll[1].first >= cfg.liveWindowMs * 1_000_000L) roll.removeFirst()
            val liveMbps = SpeedMath.mbps(b - roll.first().second, now - roll.first().first)
            val frac = max((now - start).toFloat() / (deadline - start), b.toFloat() / maxBytes).coerceIn(0f, 1f)
            emit(phase, frac, mbps = liveMbps)
            endT = now; endB = b
            if (now >= deadline || b >= maxBytes || workers.none { it.isAlive }) break
        }
        stop.set(true)
        if (upload) {
            // Let in-flight uploads finish (they are only counted once the server confirms them).
            val graceEnd = System.currentTimeMillis() + cfg.uploadGraceMs
            workers.forEach { it.join((graceEnd - System.currentTimeMillis()).coerceAtLeast(1)) }
        }
        // Abort whatever is still in flight (a 50 MB request must not run past the phase).
        synchronized(calls) { calls.toList() }.forEach { it.cancel() }
        workers.forEach { it.join(2_000) }
        checkCancel()
        val reqs = synchronized(done) { done.toList() }
        val counted = if (upload) reqs.sumOf { it.bytes } else total.get()
        if (counted == 0L) { lastError.get()?.let { throw it }; return null to 0L }
        val mbps = if (upload) SpeedMath.completedRate(reqs, if (w0T > 0) w0T else start)
            ?: SpeedMath.completedRate(reqs, start)
        else SpeedMath.windowRate(w0T, w0B, endT, endB) ?: SpeedMath.mbps(endB, endT - start)
        return mbps to counted
    }

    companion object {
        private val UPLOAD_CHUNK = ByteArray(64 * 1024).also { java.util.Random(21).nextBytes(it) }
        private val OCTET = "application/octet-stream".toMediaType()

        /** Common OkHttp setup: HTTP/1.1 only (one TCP connection per stream), pooled, sane per-request timeouts. */
        fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool(10, 1, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(false)

        /** Default route (JVM / tests). */
        fun direct(): OkHttpClient = baseClient().proxy(Proxy.NO_PROXY).build()

        /** Through a local SOCKS5 inbound (the VPN core); the host name is resolved on the far side. */
        fun socks(port: Int, socksHost: String = "127.0.0.1"): OkHttpClient =
            baseClient().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, port))).build()

        /** Through a local HTTP proxy inbound. */
        fun httpProxy(port: Int, host: String = "127.0.0.1"): OkHttpClient =
            baseClient().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))).build()
    }
}

/** Pure math / parsing helpers of the speed test (unit-tested). */
object SpeedMath {
    /** Megabits per second for [bytes] transferred in [nanos]. */
    fun mbps(bytes: Long, nanos: Long): Double = if (nanos <= 0 || bytes <= 0) 0.0 else bytes * 8.0 / (nanos / 1e9) / 1e6

    /** Linear-interpolated percentile (p in 0..1) of [values]; null for an empty list. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val pos = p.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun median(values: List<Double>): Double? = percentile(values, 0.5)

    /** Mean absolute difference between consecutive latency samples (Cloudflare's jitter definition). */
    fun jitter(values: List<Double>): Double? =
        if (values.size < 2) null else values.zipWithNext { a, b -> abs(b - a) }.average()

    /** Next request-size index: grow while requests finish faster than [targetMs], never past the last size. */
    fun nextSizeIndex(count: Int, current: Int, lastMs: Long, targetMs: Long): Int =
        if (lastMs < targetMs && current < count - 1) current + 1 else current

    /** A finished request: wall-clock start/end (ns) and the bytes it transferred (acknowledged by the server). */
    data class Req(val start: Long, val end: Long, val bytes: Long)

    /** Aggregate rate between two (time, total bytes) points; null when the window is empty/invalid. */
    fun windowRate(t0: Long, b0: Long, t1: Long, b1: Long): Double? =
        if (t0 <= 0 || t1 <= t0 || b1 < b0) null else mbps(b1 - b0, t1 - t0)

    /**
     * Rate from completed requests only: each request's bytes are spread evenly over its own start…end, the part
     * after [from] is summed and divided by (last completion − from). Nothing unacknowledged is counted.
     */
    fun completedRate(reqs: List<Req>, from: Long): Double? {
        val end = reqs.maxOfOrNull { it.end } ?: return null
        if (end <= from) return null
        var bytes = 0.0
        for (r in reqs) {
            if (r.end <= from) continue
            val dur = (r.end - r.start).coerceAtLeast(1)
            val inside = r.end - maxOf(r.start, from)
            bytes += r.bytes.toDouble() * inside / dur
        }
        return bytes * 8.0 / ((end - from) / 1e9) / 1e6
    }

    /** Exponential moving average step (live needle smoothing). */
    fun ema(prev: Double?, x: Double, alpha: Double = 0.3): Double = if (prev == null) x else prev + alpha * (x - prev)

    /** After a failed request: rate-limited (403/429) → two sizes down, other errors → one size down. */
    fun backoffSizeIndex(current: Int, limited: Boolean): Int = (current - if (limited) 2 else 1).coerceAtLeast(0)

    /** Sum of every `dur=` in a Server-Timing header (server-side processing time, ms). */
    fun serverTimingMs(header: String?): Double =
        header?.let { h -> Regex("""dur=([0-9.]+)""").findAll(h).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 } } ?: 0.0

    /** Network latency of one tiny request: time to first byte minus server processing, never below 10% of TTFB. */
    fun latencyMs(ttfbNanos: Long, serverTiming: String?): Double {
        val ttfb = ttfbNanos / 1e6
        return max(ttfb - serverTimingMs(serverTiming), ttfb * 0.1)
    }

    /** key=value lines of /cdn-cgi/trace. */
    fun parseTrace(body: String): Map<String, String> =
        body.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()

    /** City for an IATA colo code from the /locations JSON array. */
    fun cityForColo(json: String, colo: String): String? = try {
        Json.parseToJsonElement(json).jsonArray.firstOrNull { it.jsonObject["iata"]?.jsonPrimitive?.content.equals(colo, true) }
            ?.jsonObject?.get("city")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /**
     * Gauge position 0..1 on a log-like scale (0, 1, 10, 100, 1000 Mbps evenly spaced) — slow links still move
     * the needle visibly, gigabit links don't pin it.
     */
    fun gaugeFraction(mbps: Double, maxMbps: Double = 1000.0): Float {
        if (mbps <= 0) return 0f
        val v = ln(1 + mbps) / ln(1 + maxMbps)
        return v.toFloat().coerceIn(0f, 1f)
    }

    /** "87.3" / "7.42" / "0.85": 3 significant digits, at most one decimal from 100 Mbps. */
    fun fmtMbps(v: Double?): String = when {
        v == null -> "—"
        v >= 100 -> String.format(Locale.US, "%.0f", v)
        v >= 10 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.2f", v)
    }

    fun fmtMs(v: Double?): String = if (v == null) "—" else if (v >= 10) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    /** Relative change b vs a in percent (null when either is missing or a is 0). */
    fun deltaPercent(a: Double?, b: Double?): Double? = if (a == null || b == null || a <= 0) null else (b - a) / a * 100.0

    fun errorText(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "Таймаут"
        is java.net.UnknownHostException -> "DNS: не удалось найти ${e.message ?: "сервер"}"
        is javax.net.ssl.SSLException -> "TLS: ${e.message ?: "ошибка"}"
        else -> e.message ?: e.javaClass.simpleName
    }

    internal fun readLine(ins: InputStream, firstByte: Int = -2, max: Int = 8192): String? {
        val sb = StringBuilder()
        var c = if (firstByte == -2) ins.read() else firstByte
        if (c < 0) return null
        while (sb.length < max) {
            if (c < 0) return sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            c = ins.read()
        }
        return sb.toString()
    }
}

/** One persisted speed-test run (history keeps the newest [SpeedHistory.MAX]). */
@Serializable
data class SpeedRecord(
    val ts: Long,
    /** "vpn" or "direct". */
    val mode: String,
    /** VPN host name at test time ("" when the VPN was off). */
    val host: String = "",
    /** Physical network label for «Напрямую» (Wi‑Fi / Мобильная сеть / …). */
    val network: String = "",
    val down: Double? = null,
    val up: Double? = null,
    val ping: Double? = null,
    val jitter: Double? = null,
    val colo: String = "",
    val city: String = "",
    val ip: String = "",
    val error: String? = null,
    /** Runs started together by «Оба» share a group id (start time of the pair). */
    val group: Long = 0,
)

object SpeedHistory {
    const val MAX = 20
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun add(list: List<SpeedRecord>, r: SpeedRecord): List<SpeedRecord> = (listOf(r) + list).sortedByDescending { it.ts }.take(MAX)
    fun encode(list: List<SpeedRecord>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SpeedRecord.serializer()), list)
    fun decode(s: String?): List<SpeedRecord> = if (s.isNullOrBlank()) emptyList() else try {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SpeedRecord.serializer()), s).take(MAX)
    } catch (_: Exception) { emptyList() }
}
