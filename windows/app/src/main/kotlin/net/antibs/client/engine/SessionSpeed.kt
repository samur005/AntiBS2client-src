package net.antibs.client.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

enum class SessionSpeedPhase { Idle, Ping, Download, Upload, Done, Failed }

data class SessionSpeedProgress(
    val phase: SessionSpeedPhase = SessionSpeedPhase.Idle,
    val pingMs: Int? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val error: String? = null,
    /** 0–1 fill of the download 8 s window; 1 when that phase is finished. */
    val downProgress: Float? = null,
    /** 0–1 fill of the upload 8 s window; 1 when finished. */
    val upProgress: Float? = null,
)

/**
 * Cloudflare speed test used on the Session screen.
 *
 * Own implementation: ping is the minimum of four empty downloads; download and upload each run
 * four parallel streams for 8 s, ignore the first 1.5 s as warmup, and push a live rate every 250 ms.
 */
object SessionSpeed {
    const val HOST = "https://speed.cloudflare.com"
    const val PHASE_MS = 8_000L
    const val WARMUP_MS = 1_500L
    const val STREAMS = 4
    const val UP_CHUNK = 1 * 1024 * 1024
    const val DOWN_BYTES = 25_000_000L
    const val RATE_PUSH_MS = 250L
    const val PING_COUNT = 4

    data class RateWindow(
        var total: Long = 0,
        var base: Long = -1,
        var baseMs: Long = 0,
        var lastPushMs: Long = 0,
    )

    /** Mbit/s for [bytes] transferred over [elapsedMs] milliseconds. */
    fun rateMbps(bytes: Long, elapsedMs: Long): Double {
        if (bytes <= 0L || elapsedMs <= 0L) return 0.0
        return bytes * 8.0 / 1000.0 / elapsedMs
    }

    /** After warmup, rate is computed on the delta from the baseline snapshot. */
    fun rateAfterWarmup(totalBytes: Long, elapsedMs: Long, baseBytes: Long, baseMs: Long): Double {
        if (baseBytes < 0L || elapsedMs <= baseMs) return 0.0
        return rateMbps(totalBytes - baseBytes, elapsedMs - baseMs)
    }

    fun maybeArmWarmup(window: RateWindow, elapsedMs: Long, warmupMs: Long = WARMUP_MS): Boolean {
        if (window.base < 0 && elapsedMs >= warmupMs) {
            window.base = window.total
            window.baseMs = elapsedMs
            return true
        }
        return false
    }

    fun addBytes(window: RateWindow, n: Long) {
        if (n > 0) window.total += n
    }

    /** Returns a new rate when a 250 ms tick is due and warmup has finished, otherwise null. */
    fun maybePushRate(window: RateWindow, elapsedMs: Long, pushEvery: Long = RATE_PUSH_MS): Double? {
        if (window.base < 0 || elapsedMs <= window.baseMs) return null
        if (elapsedMs - window.lastPushMs < pushEvery && window.lastPushMs != 0L) return null
        window.lastPushMs = elapsedMs
        return rateAfterWarmup(window.total, elapsedMs, window.base, window.baseMs)
    }

    fun formatMbps(v: Double): String {
        if (v.isNaN() || v.isInfinite() || v < 0) return "—"
        return if (v >= 100.0) v.roundToInt().toString()
        else {
            val x = (v * 10.0).roundToInt() / 10.0
            if (x == x.toLong().toDouble()) x.toLong().toString() + ".0"
            else x.toString()
        }
    }

    fun formatPing(ms: Int?): String = if (ms == null) "—" else ms.toString()

    fun dashOrMbps(v: Double?): String = if (v == null) "—" else formatMbps(v)

    fun phaseFill(elapsedMs: Long, windowMs: Long = PHASE_MS): Float {
        if (windowMs <= 0L) return 0f
        return (elapsedMs.toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
    }

    fun wrapClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun run(
        client: OkHttpClient,
        onUpdate: (SessionSpeedProgress) -> Unit,
        cancelled: () -> Boolean = { false },
    ): SessionSpeedProgress = withContext(Dispatchers.IO) {
        val http = wrapClient(client)
        var cur = SessionSpeedProgress(phase = SessionSpeedPhase.Ping)
        onUpdate(cur)
        try {
            val rtts = ArrayList<Int>(PING_COUNT)
            repeat(PING_COUNT) {
                check(!cancelled()) { "cancelled" }
                coroutineContext.ensureActive()
                val t0 = System.nanoTime()
                http.newCall(Request.Builder().url("$HOST/__down?bytes=0").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body?.close()
                }
                rtts += ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(0)
            }
            cur = cur.copy(phase = SessionSpeedPhase.Download, pingMs = rtts.minOrNull() ?: 0, downProgress = 0f, upProgress = 0f)
            onUpdate(cur)

            val down = measureRate(http, download = true, cancelled) { mbps, fill ->
                cur = cur.copy(downMbps = mbps, downProgress = fill, upProgress = 0f)
                onUpdate(cur)
            }
            if (down <= 0.0) error("Нет данных загрузки")
            cur = cur.copy(phase = SessionSpeedPhase.Upload, downMbps = down, downProgress = 1f, upProgress = 0f)
            onUpdate(cur)

            val up = measureRate(http, download = false, cancelled) { mbps, fill ->
                cur = cur.copy(upMbps = mbps, upProgress = fill, downProgress = 1f)
                onUpdate(cur)
            }
            if (up <= 0.0) error("Нет данных отдачи")
            cur = cur.copy(phase = SessionSpeedPhase.Done, upMbps = up, error = null, downProgress = 1f, upProgress = 1f)
            onUpdate(cur)
            cur
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled() || e.message == "cancelled") {
                onUpdate(cur.copy(phase = SessionSpeedPhase.Idle, error = null))
                cur.copy(phase = SessionSpeedPhase.Idle, error = null)
            } else {
                val failed = cur.copy(
                    phase = SessionSpeedPhase.Failed,
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить тест скорости",
                )
                onUpdate(failed)
                failed
            }
        }
    }

    private suspend fun measureRate(
        client: OkHttpClient,
        download: Boolean,
        cancelled: () -> Boolean,
        onRate: (Double, Float) -> Unit,
    ): Double {
        val total = AtomicLong(0)
        val start = System.nanoTime()
        fun elapsed(): Long = (System.nanoTime() - start) / 1_000_000L
        val window = RateWindow()
        val stop = AtomicBoolean(false)
        var last = 0.0
        coroutineScope {
            val ticker = launch {
                while (isActive && !cancelled() && !stop.get() && elapsed() < PHASE_MS) {
                    delay(RATE_PUSH_MS)
                    val ms = elapsed()
                    window.total = total.get()
                    maybeArmWarmup(window, ms)
                    maybePushRate(window, ms)?.let { last = it }
                    onRate(last, phaseFill(ms))
                }
            }
            val until = { stop.get() || cancelled() || elapsed() >= PHASE_MS }
            repeat(STREAMS) {
                launch(Dispatchers.IO) {
                    try {
                        if (download) downloadLoop(client, { n -> total.addAndGet(n.toLong()) }, until)
                        else uploadLoop(client, { n -> total.addAndGet(n.toLong()) }, until)
                    } catch (_: IOException) {
                    } catch (_: kotlinx.coroutines.CancellationException) {
                    }
                }
            }
            while (elapsed() < PHASE_MS && !cancelled() && isActive) delay(40)
            stop.set(true)
            ticker.cancel()
        }
        val ms = elapsed()
        window.total = total.get()
        maybeArmWarmup(window, ms)
        last = if (window.base >= 0 && ms > window.baseMs) {
            rateAfterWarmup(window.total, ms, window.base, window.baseMs)
        } else rateMbps(window.total, ms.coerceAtLeast(1))
        onRate(last, 1f)
        return last
    }

    private fun downloadLoop(client: OkHttpClient, onBytes: (Int) -> Unit, until: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        while (!until()) {
            val call = client.newCall(Request.Builder().url("$HOST/__down?bytes=$DOWN_BYTES").build())
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val src = r.body?.byteStream() ?: return
                    while (!until()) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        onBytes(n)
                    }
                }
            } finally {
                call.cancel()
            }
        }
    }

    private fun uploadLoop(client: OkHttpClient, onBytes: (Int) -> Unit, until: () -> Boolean) {
        val chunk = UPLOAD_ZEROS
        while (!until()) {
            val body = object : RequestBody() {
                override fun contentType() = OCTET
                override fun contentLength() = UP_CHUNK.toLong()
                override fun writeTo(sink: BufferedSink) {
                    var left = UP_CHUNK
                    while (left > 0) {
                        if (until()) throw IOException("cancelled")
                        val n = minOf(left, chunk.size)
                        sink.write(chunk, 0, n)
                        onBytes(n)
                        left -= n
                    }
                    sink.flush()
                }
            }
            val call = client.newCall(Request.Builder().url("$HOST/__up").post(body).build())
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body?.close()
                }
            } finally {
                call.cancel()
            }
        }
    }

    private val OCTET = "application/octet-stream".toMediaType()
    private val UPLOAD_ZEROS = ByteArray(64 * 1024)
}
