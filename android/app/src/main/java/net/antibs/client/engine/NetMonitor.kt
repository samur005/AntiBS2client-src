package net.antibs.client.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 2.0.7: home «Мониторинг сети» + «URL Тест» (variant B).
 *
 * AntiBS2client is excluded from its own VPN (see [SpeedNet]), so:
 *  - «VPN · gstatic» / URL tests while connected go through the core's loopback SOCKS inbound (127.0.0.1:socksPort),
 *    i.e. the same path every tunnelled app uses;
 *  - «Прямой · vk.ru» uses sockets + DNS of the physical (non-VPN) network ([SpeedNet.direct]).
 * One measurement = wall time of a single HTTP GET on a fresh connection (DNS + TCP + TLS + first response),
 * redirects not followed — any HTTP status counts as reachable.
 */
object NetMonitor {
    const val VPN_URL = "https://connectivitycheck.gstatic.com/generate_204"
    const val DIRECT_URL = "https://vk.ru/"
    const val TIMEOUT_MS = 5_000
    const val MONITOR_PERIOD_MS = 4_000L

    data class Target(val id: String, val title: String, val url: String)

    val URL_TARGETS = listOf(
        Target("google", "Google", "https://www.google.com/generate_204"),
        Target("telegram", "Telegram", "https://web.telegram.org/"),
        Target("instagram", "Запретgram", "https://www.instagram.com/"),
        Target("youtube", "YouTube", "https://www.youtube.com/generate_204"),
    )

    enum class Tone { GOOD, MID, BAD, NONE }

    /** null = not measured / not applicable («—»), negative = failed («тайм-аут»). */
    fun tone(ms: Long?): Tone = when {
        ms == null -> Tone.NONE
        ms < 0 -> Tone.BAD
        ms < 300 -> Tone.GOOD
        ms < 800 -> Tone.MID
        else -> Tone.BAD
    }

    fun label(ms: Long?, timeout: String = "тайм-аут"): String = when {
        ms == null -> "—"
        ms < 0 -> timeout
        else -> ms.toString()
    }

    private fun tune(b: okhttp3.OkHttpClient.Builder, timeoutMs: Int) = b
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    fun vpnClient(socksPort: Int, timeoutMs: Int = TIMEOUT_MS) = tune(SpeedTest.socks(socksPort).newBuilder(), timeoutMs)
    fun directClient(ctx: Context, timeoutMs: Int = TIMEOUT_MS) = tune(SpeedNet.direct(ctx).client.newBuilder(), timeoutMs)

    /** ms of one GET, or -1 on any failure. */
    fun probe(client: okhttp3.OkHttpClient, url: String): Long = try {
        val t0 = System.nanoTime()
        client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) AntiBS2client").get().build())
            .execute().use { it.code }
        ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
    } catch (_: Throwable) { -1L }

    data class Monitor(val vpn: Long?, val direct: Long?)

    suspend fun monitor(ctx: Context, connected: Boolean, socksPort: Int): Monitor = withContext(Dispatchers.IO) {
        coroutineScope {
            val v = async { if (connected) probe(vpnClient(socksPort), VPN_URL) else null }
            val d = async { probe(directClient(ctx), DIRECT_URL) }
            Monitor(v.await(), d.await())
        }
    }

    suspend fun urlTest(ctx: Context, connected: Boolean, socksPort: Int): Map<String, Long> = withContext(Dispatchers.IO) {
        val client = if (connected) vpnClient(socksPort) else directClient(ctx)
        coroutineScope { URL_TARGETS.map { t -> async { t.id to probe(client, t.url) } }.awaitAll().toMap() }
    }

    // ───── 2.0.8: connection health → red power button + red banner status
    enum class Health { OK, NO_INTERNET, SERVER_DOWN }
    const val FAILS_TO_ALERT = 2

    /**
     * [active] = CONNECTED or CONNECTING. [vpnFails]/[directFails] = consecutive failed pings (0 after a success).
     * «Нет интернета»: direct ping fails AND the physical network is not validated. «Сервер недоступен»: internet
     * is fine but the tunnel ping failed [FAILS_TO_ALERT]× in a row. Any successful VPN ping clears the alert.
     */
    fun health(active: Boolean, connected: Boolean, vpnFails: Int, directFails: Int, netValidated: Boolean): Health = when {
        !active -> Health.OK
        directFails >= 1 && !netValidated -> Health.NO_INTERNET
        connected && vpnFails >= FAILS_TO_ALERT -> Health.SERVER_DOWN
        else -> Health.OK
    }

    fun healthText(h: Health): String? = when (h) {
        Health.NO_INTERNET -> "Нет интернета"
        Health.SERVER_DOWN -> "Сервер недоступен"
        Health.OK -> null
    }

    fun nextFails(prev: Int, ok: Boolean): Int = if (ok) 0 else (prev + 1).coerceAtMost(99)

    fun netValidated(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val n = cm?.let { SpeedNet.physicalNetwork(it) }
        n != null && cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    } catch (_: Throwable) { true }
}
