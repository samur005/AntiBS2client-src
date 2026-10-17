package net.antibs.client.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 1.0.34: tiny gRPC client for the running Xray's API (config "api": {"listen": "127.0.0.1:port"}), over cleartext
 * HTTP/2 with hand-rolled protobuf — enough to ask which outbound a balancer picked ([balancerTargets]) and the
 * observatory's view of every outbound ([outboundStatus]). No grpc-java dependency.
 */
object XrayApi {
    data class OutboundStatus(val tag: String, val alive: Boolean, val delayMs: Long)

    private val client by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
    }
    private val GRPC = "application/grpc".toMediaType()

    /** Unary gRPC call; returns the response message bytes or null. */
    fun call(port: Int, method: String, msg: ByteArray): ByteArray? = try {
        val frame = ByteArray(5 + msg.size).also { f ->
            f[1] = (msg.size ushr 24).toByte(); f[2] = (msg.size ushr 16).toByte(); f[3] = (msg.size ushr 8).toByte(); f[4] = msg.size.toByte()
            System.arraycopy(msg, 0, f, 5, msg.size)
        }
        val req = Request.Builder().url("http://127.0.0.1:$port/$method")
            .header("te", "trailers").header("content-type", "application/grpc")
            .post(frame.toRequestBody(GRPC)).build()
        client.newCall(req).execute().use { r ->
            val b = r.body?.bytes()
            if (!r.isSuccessful || b == null || b.size < 5 || b[0].toInt() != 0) null else {
                val n = ((b[1].toInt() and 0xff) shl 24) or ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 8) or (b[4].toInt() and 0xff)
                if (n < 0 || 5 + n > b.size) null else b.copyOfRange(5, 5 + n)
            }
        }
    } catch (_: Throwable) { null }

    /** Outbound tag(s) the balancer currently routes to (override first, then principle targets); null = API failed. */
    fun balancerTargets(port: Int, balancerTag: String): List<String>? {
        val resp = call(port, "xray.app.router.command.RoutingService/GetBalancerInfo", Proto.string(1, balancerTag)) ?: return null
        return parseBalancerInfo(resp)
    }

    /** Pins [balancerTag] to [target] (empty target = back to the strategy). True when Xray accepted it. */
    fun overrideBalancer(port: Int, balancerTag: String, target: String): Boolean =
        call(port, "xray.app.router.command.RoutingService/OverrideBalancerTarget", Proto.string(1, balancerTag) + Proto.string(2, target)) != null

    /** One GET of [url] through the loopback SOCKS inbound on [socksPort]; ms or -1. */
    fun probe(socksPort: Int, url: String, timeoutMs: Int): Long = try {
        val c = OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        val t0 = System.nanoTime()
        c.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (r.code in 200..399) ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1) else -1L
        }
    } catch (_: Throwable) { -1L }

    fun outboundStatus(port: Int): List<OutboundStatus>? {
        val resp = call(port, "xray.core.app.observatory.command.ObservatoryService/GetOutboundStatus", ByteArray(0)) ?: return null
        return parseOutboundStatus(resp)
    }

    /** GetBalancerInfoResponse{1: BalancerMsg{5: OverrideInfo{2: target}, 6: PrincipleTargetInfo{1: repeated tag}}}. */
    fun parseBalancerInfo(resp: ByteArray): List<String> {
        val res = ArrayList<String>()
        Proto.fields(resp).filter { it.no == 1 }.forEach { bal ->
            val b = bal.bytes ?: return@forEach
            Proto.fields(b).forEach { f ->
                val v = f.bytes ?: return@forEach
                when (f.no) {
                    5 -> Proto.fields(v).filter { it.no == 2 }.forEach { t -> t.bytes?.let { String(it) }?.takeIf { it.isNotEmpty() }?.let { res.add(0, it) } }
                    6 -> Proto.fields(v).filter { it.no == 1 }.forEach { t -> t.bytes?.let { res.add(String(it)) } }
                }
            }
        }
        return res.distinct()
    }

    /** GetOutboundStatusResponse{1: ObservationResult{1: repeated OutboundStatus{1 alive, 2 delay, 4 outbound_tag}}}. */
    fun parseOutboundStatus(resp: ByteArray): List<OutboundStatus> {
        val res = ArrayList<OutboundStatus>()
        Proto.fields(resp).filter { it.no == 1 }.forEach { r ->
            Proto.fields(r.bytes ?: return@forEach).filter { it.no == 1 }.forEach { st ->
                var alive = false; var delay = 0L; var tag = ""
                Proto.fields(st.bytes ?: return@forEach).forEach { f ->
                    when (f.no) { 1 -> alive = f.varint != 0L; 2 -> delay = f.varint; 4 -> tag = f.bytes?.let { String(it) }.orEmpty() }
                }
                if (tag.isNotEmpty()) res.add(OutboundStatus(tag, alive, delay))
            }
        }
        return res
    }

    /** Minimal protobuf wire format. */
    object Proto {
        class Field(val no: Int, val varint: Long = 0, val bytes: ByteArray? = null)

        fun string(no: Int, v: String): ByteArray = bytes(no, v.toByteArray())

        fun bytes(no: Int, v: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            writeVarint(this, ((no shl 3) or 2).toLong()); writeVarint(this, v.size.toLong()); write(v)
        }.toByteArray()

        fun varint(no: Int, v: Long): ByteArray = ByteArrayOutputStream().apply {
            writeVarint(this, (no shl 3).toLong()); writeVarint(this, v)
        }.toByteArray()

        private fun writeVarint(o: ByteArrayOutputStream, v0: Long) {
            var v = v0
            while (v and 0x7fL.inv() != 0L) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
            o.write(v.toInt())
        }

        fun fields(b: ByteArray): List<Field> {
            val res = ArrayList<Field>()
            var i = 0
            fun rv(): Long {
                var r = 0L; var sh = 0
                while (i < b.size) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl sh); if (x < 0x80) return r; sh += 7 }
                throw IllegalArgumentException("truncated")
            }
            try {
                while (i < b.size) {
                    val key = rv(); val no = (key ushr 3).toInt()
                    when ((key and 7).toInt()) {
                        0 -> res.add(Field(no, varint = rv()))
                        1 -> { i += 8 }
                        2 -> { val n = rv().toInt(); if (n < 0 || i + n > b.size) break; res.add(Field(no, bytes = b.copyOfRange(i, i + n))); i += n }
                        5 -> { i += 4 }
                        else -> break
                    }
                }
            } catch (_: Exception) {}
            return res
        }
    }

    fun freePort(): Int = try { java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort } } catch (_: Exception) { 0 }
}
