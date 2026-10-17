package net.antibs.client.engine

import android.content.Context
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/** Thin wrapper around AndroidLibXrayLite's CoreController (Xray-core in-process via gomobile). */
object XrayCore {
    private var controller: CoreController? = null
    @Volatile private var envReady = false

    fun version(): String = try { Libv2ray.checkVersionX() } catch (e: Throwable) { "?" }

    @Synchronized
    private fun ensureEnv(ctx: Context) {
        if (envReady) return
        val dir = ctx.filesDir.absolutePath
        copyGeoFiles(ctx)
        Libv2ray.initCoreEnv(dir, "")
        envReady = true
    }

    /**
     * Xray 26.x stats the asset path before opening it, so libv2ray's APK-asset fallback never fires:
     * geoip.dat / geosite.dat must exist in filesDir for geoip:/geosite: routing rules. Copied once per APK update.
     */
    /** 1.0.19: stock geo files in filesDir before a config is built (their tags decide which geo refs are kept). */
    fun prepareAssets(ctx: Context) = try { copyGeoFiles(ctx) } catch (_: Throwable) {}

    @Synchronized
    private fun copyGeoFiles(ctx: Context) {
        val stamp = java.io.File(ctx.filesDir, "geo.stamp")
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString() } catch (_: Throwable) { "0" }
        val names = listOf("geoip.dat", "geosite.dat")
        if (stamp.exists() && stamp.readText() == ver && names.all { java.io.File(ctx.filesDir, it).length() > 0 }) return
        for (n in names) {
            try {
                val tmp = java.io.File(ctx.filesDir, "$n.tmp")
                ctx.assets.open(n).use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
                tmp.renameTo(java.io.File(ctx.filesDir, n))
            } catch (e: Throwable) {
                VpnState.log("geo: не удалось распаковать $n: ${e.message}")
            }
        }
        stamp.writeText(ver)
    }

    /** [tunFd] > 0 → Xray's own TUN inbound reads that fd; [finder] enables «process» routing rules. */
    @Synchronized
    fun start(ctx: Context, config: String, tunFd: Int = 0, finder: libv2ray.ProcessFinder? = null) {
        ensureEnv(ctx)
        stop()
        val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun onEmitStatus(p0: Long, p1: String?): Long { p1?.let { VpnState.log("[xray] $it") }; return 0 }
            override fun shutdown(): Long = 0
            override fun startup(): Long = 0
        })
        try { c.registerProcessFinder(finder) } catch (_: Throwable) {}
        // tunFd = 0: the TUN is handled by hev-socks5-tunnel, Xray only serves the local SOCKS inbound
        c.startLoop(config, tunFd)
        if (!c.isRunning) throw IllegalStateException("Xray core failed to start")
        controller = c
    }

    @Synchronized
    fun stop() {
        try { controller?.stopLoop() } catch (_: Throwable) {}
        controller = null
    }

    fun isRunning(): Boolean = controller?.isRunning == true

    /** 1.0.19: certificate pinning for «insecure» hosts (Xray 26 dropped allowInsecure), see [net.antibs.client.data.CertPins]. */
    fun installCertPins() {
        if (net.antibs.client.data.CertPins.fetcher != null) return
        net.antibs.client.data.CertPins.fetcher = { host, port, sni, quic ->
            val req = org.json.JSONObject().put("address", host).put("port", port).put("serverName", sni ?: "").put("timeoutMs", 5000).toString()
            val res = try { if (quic) Libv2ray.fetchQuicCertSha256(req) else Libv2ray.fetchTlsCertSha256(req) } catch (_: Throwable) { null }
            val o = res?.let { try { org.json.JSONObject(it) } catch (_: Exception) { null } }
            val sha = o?.optString("sha256").orEmpty()
            if (sha.isEmpty()) VpnState.log("сертификат $host:$port: ${o?.optString("error")?.ifEmpty { null } ?: "нет ответа"}") else VpnState.log("сертификат $host:$port закреплён (небезопасный TLS)")
            sha.ifEmpty { null }
        }
    }

    /**
     * Bytes since the previous call (core counters reset on read).
     * [fromXray] is false when the core returned no stats string — caller may approximate.
     * proxy = traffic through the VPN outbound; direct = freedom outbound (bypass).
     */
    data class TrafficDelta(
        val proxyUp: Long,
        val proxyDown: Long,
        val directUp: Long,
        val directDown: Long,
        val fromXray: Boolean,
        /** 1.0.19: bytes (up + down) per balancer member outbound "b-N" this tick — used to show the host auto-select picked. */
        val balancer: Map<String, Long> = emptyMap(),
    )

    /** 1.0.34: any outbound except plumbing counts as VPN traffic (full JSON configs name proxies freely). */
    fun isProxyTag(tag: String): Boolean = tag.isNotEmpty() && tag !in setOf("direct", "block", "dns-out", "api", "blackhole", "freedom", "dns")

    fun queryTrafficDelta(): TrafficDelta {
        val s = try { controller?.queryAllOutboundTrafficStats().orEmpty() } catch (_: Throwable) { "" }
        if (s.isBlank()) return TrafficDelta(0, 0, 0, 0, fromXray = false)
        var proxyUp = 0L; var proxyDown = 0L; var directUp = 0L; var directDown = 0L
        val bal = HashMap<String, Long>()
        s.split(';').forEach { item ->
            val f = item.split(',')
            if (f.size != 3) return@forEach
            val v = f[2].toLongOrNull() ?: 0
            val up = f[1] == "uplink"
            val down = f[1] == "downlink"
            when (f[0]) {
                "proxy" -> {
                    if (up) proxyUp += v else if (down) proxyDown += v
                    if (up || down) bal["proxy"] = (bal["proxy"] ?: 0L) + v
                }
                "direct" -> if (up) directUp += v else if (down) directDown += v
                // Balancer members (b-N, or the panel JSON's proxy-2, proxy-3, …) carry the VPN traffic too.
                else -> if (isProxyTag(f[0])) {
                    if (up) proxyUp += v else if (down) proxyDown += v
                    if (up || down) bal[f[0]] = (bal[f[0]] ?: 0L) + v
                }
            }
        }
        return TrafficDelta(proxyUp, proxyDown, directUp, directDown, fromXray = true, balancer = bal)
    }

    /** URL-test through a running config without the VPN (used for "real ping"). */
    fun measureDelay(config: String, url: String = "https://www.gstatic.com/generate_204"): Long =
        try { Libv2ray.measureOutboundDelay(config, url) } catch (e: Throwable) { -1 }

    /**
     * URL test through a throw-away Xray instance: [method] GET|HEAD, [mode] 0 default (best of 2 fresh),
     * 1 double (two requests, no keep-alive), 2 keep-alive (second request on the warmed connection).
     */
    fun measureDelayEx(ctx: Context, config: String, url: String, method: String, mode: Int, timeoutMs: Int): Long {
        ensureEnv(ctx)
        return try { Libv2ray.measureOutboundDelayEx(config, url, method, mode, timeoutMs) } catch (e: Throwable) { -1 }
    }
}
