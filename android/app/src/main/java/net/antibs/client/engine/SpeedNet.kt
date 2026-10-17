package net.antibs.client.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * 1.0.21: how the speed test reaches Cloudflare.
 *
 *  - «Через VPN»: AntiBS2client itself is always excluded from its own VPN (addDisallowedApplication, so the
 *    cores' sockets never loop back into the TUN). Ordinary sockets of this app therefore would NOT go through
 *    the tunnel — instead the test connects to the core's local SOCKS inbound (127.0.0.1:socksPort), the very
 *    entry point hev-socks5-tunnel / Xray-TUN feed every other app's traffic into (same routing rules).
 *  - «Напрямую»: sockets are created by the physical (non-VPN) [Network]'s socketFactory and DNS is resolved on
 *    that network (Network.getAllByName), so they are bound to Wi‑Fi / mobile even while the VPN is up.
 */
object SpeedNet {
    data class Direct(val client: okhttp3.OkHttpClient, val label: String)

    /**
     * «Напрямую»: OkHttp whose sockets come from the physical network's socketFactory (bound to Wi‑Fi / mobile even
     * while the VPN is up) and whose DNS is resolved on that same network. TLS (SNI/ALPN) is done by OkHttp.
     */
    fun direct(ctx: Context): Direct {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.let { physicalNetwork(it) } ?: return Direct(SpeedTest.direct(), "сеть по умолчанию")
        val label = label(cm.getNetworkCapabilities(net))
        val client = SpeedTest.baseClient()
            .proxy(java.net.Proxy.NO_PROXY)
            .socketFactory(net.socketFactory)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = try {
                    // IPv4 first: some networks advertise IPv6 that doesn't actually route.
                    net.getAllByName(hostname).toList().sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                } catch (e: Exception) { throw java.net.UnknownHostException("$hostname: ${e.message}") }
            })
            .build()
        return Direct(client, label)
    }

    /** The best non-VPN network with internet: the default one if it isn't a VPN, else a validated one. */
    @Suppress("DEPRECATION")
    fun physicalNetwork(cm: ConnectivityManager): Network? {
        fun ok(n: Network): NetworkCapabilities? = cm.getNetworkCapabilities(n)?.takeIf {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.let { if (ok(it) != null) return it }
        val all = try { cm.allNetworks.toList() } catch (_: Exception) { emptyList() }
        val cands = all.mapNotNull { n -> ok(n)?.let { n to it } }
        return (cands.firstOrNull { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } ?: cands.firstOrNull())?.first
    }

    fun label(c: NetworkCapabilities?): String = when {
        c == null -> "сеть"
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "сеть"
    }
}

/** Persisted speed-test history (SharedPreferences, newest first, at most [SpeedHistory.MAX]). */
class SpeedHistoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("speedtest", Context.MODE_PRIVATE)
    fun load(): List<SpeedRecord> = SpeedHistory.decode(prefs.getString("history", null))
    fun save(list: List<SpeedRecord>) { prefs.edit().putString("history", SpeedHistory.encode(list)).apply() }
}
