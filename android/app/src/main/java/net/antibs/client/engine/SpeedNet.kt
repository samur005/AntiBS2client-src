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
 *    2.2.0: only while OUR VPN is up — otherwise the system default route is used (it may be another VPN app).
 */
object SpeedNet {
    data class Direct(val client: okhttp3.OkHttpClient, val label: String)

    /**
     * «Напрямую»: OkHttp whose sockets come from the physical network's socketFactory (bound to Wi‑Fi / mobile even
     * while the VPN is up) and whose DNS is resolved on that same network. TLS (SNI/ALPN) is done by OkHttp.
     */
    fun direct(ctx: Context): Direct {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        // 2.2.0: binding to the physical network is only needed to step around OUR tunnel. With our VPN off the OS
        // default route already is «direct» — or another VPN app's tunnel. Binding to Wi‑Fi / mobile then bypasses
        // that foreign VPN, which a non-bypassable / «always-on + block without VPN» VPN forbids (EPERM / timeouts):
        // the Session screen showed «Не удалось определить IP» and the speed test failed.
        val activeCaps = cm?.let { m -> runCatching { m.activeNetwork?.let { m.getNetworkCapabilities(it) } }.getOrNull() }
        val activeVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        when (path(ownVpnUp(), activeVpn)) {
            Path.FOREIGN_VPN -> return Direct(SpeedTest.direct(), FOREIGN_VPN_LABEL)
            Path.SYSTEM_DEFAULT -> return Direct(SpeedTest.direct(), label(activeCaps))
            Path.BOUND_PHYSICAL -> Unit
        }
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

    /** 2.2.0: how a «direct» request leaves the phone. */
    enum class Path { BOUND_PHYSICAL, SYSTEM_DEFAULT, FOREIGN_VPN }

    const val FOREIGN_VPN_LABEL = "сторонний VPN"

    /**
     * Our VPN up → bind to the physical network (this app is excluded from its own tunnel anyway, the binding keeps
     * the «direct» path off it explicitly). Our VPN off → plain system default route; when that route is a VPN it
     * belongs to another app (we never route ourselves into our own TUN), so requests go through it.
     */
    fun path(ownVpnUp: Boolean, activeIsVpn: Boolean): Path = when {
        ownVpnUp -> Path.BOUND_PHYSICAL
        activeIsVpn -> Path.FOREIGN_VPN
        else -> Path.SYSTEM_DEFAULT
    }

    /** Our tunnel is up or being brought up / down (its TUN may already own the default route). */
    fun ownVpnUp(): Boolean = VpnState.status.value.state.let { it == ConnState.CONNECTED || it == ConnState.CONNECTING || it == ConnState.DISCONNECTING }

    /** Another VPN app's tunnel is the default route while ours is off. */
    fun foreignVpnActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        !ownVpnUp() && cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Throwable) { false }

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
