package net.antibs.client.cell

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import net.antibs.client.engine.NetScanner
import java.net.Inet4Address

/** 1.0.56: Wi‑Fi analyzer data source (current link + scan list + RSSI history). */
@SuppressLint("MissingPermission")
class WifiAnalyzer(ctx: Context) {
    private val app = ctx.applicationContext
    private val wm = app.getSystemService(WifiManager::class.java)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class Link(
        val ssid: String?, val bssid: String?, val rssi: Int?, val linkMbps: Int?, val txMbps: Int?, val rxMbps: Int?,
        val freq: Int?, val channel: Int?, val widthMhz: Int?, val standard: String, val ip: String?, val gateway: String?,
        val dns: List<String>, val security: String?, val vendor: String?,
    )
    data class Ap(
        val ssid: String, val bssid: String, val vendor: String?, val rssi: Int, val freq: Int, val center: Int, val channel: Int?,
        val widthMhz: Int, val security: String, val band: String, val standard: String, val connected: Boolean,
    )

    val link = MutableStateFlow<Link?>(null)
    val aps = MutableStateFlow<List<Ap>>(emptyList())
    val status = MutableStateFlow("")
    /** BSSID → recent RSSI samples (one per scan / per second for the connected AP). */
    val history = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    private val scanTimes = ArrayDeque<Long>()

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { readResults(i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)) }
    }

    fun start() {
        ContextCompat.registerReceiver(app, rx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        readResults(true); scan()
        scope.launch { while (isActive) { readLink(); delay(1000) } }
        scope.launch { while (isActive) { delay(30_000); scan() } }
    }
    fun stop() { scope.coroutineContext.cancelChildren(); runCatching { app.unregisterReceiver(rx) } }

    /** Android allows a foreground app 4 scans per 2 minutes; we keep our own budget so the button explains itself. */
    fun scan() {
        val now = System.currentTimeMillis()
        while (scanTimes.isNotEmpty() && now - scanTimes.first() > 120_000) scanTimes.removeFirst()
        if (scanTimes.size >= 4) {
            status.value = "Лимит Android: 4 сканирования за 2 мин. Следующее через ${(120 - (now - scanTimes.first()) / 1000)} с — показаны последние результаты"
            return
        }
        @Suppress("DEPRECATION")
        val ok = runCatching { wm?.startScan() == true }.getOrDefault(false)
        if (ok) { scanTimes.addLast(now); status.value = "Сканирование…" }
        else status.value = "Система отклонила сканирование (ограничение частоты или выключен Wi‑Fi) — показаны кэшированные результаты"
    }

    private fun readResults(fresh: Boolean) {
        val list = runCatching { wm?.scanResults.orEmpty() }.getOrElse { status.value = "Нет разрешения на результаты сканирования"; emptyList() }
        val cur = link.value?.bssid
        aps.value = list.map { r -> ap(r, cur) }.sortedByDescending { it.rssi }
        val h = history.value.toMutableMap()
        list.forEach { r -> h[r.BSSID] = (h[r.BSSID].orEmpty() + r.level).takeLast(60) }
        history.value = h
        if (fresh) status.value = "Найдено сетей: ${list.size}"
    }

    private fun ap(r: ScanResult, cur: String?): Ap {
        @Suppress("DEPRECATION")
        val ssid = (if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.trim('"') else r.SSID).orEmpty().ifBlank { "<скрытая>" }
        return Ap(ssid, r.BSSID, NetScanner.vendor(app, r.BSSID), r.level, r.frequency, r.centerFreq0.takeIf { it > 0 } ?: r.frequency,
            WifiMath.channel(r.frequency), WifiMath.widthMhz(r.channelWidth), WifiMath.security(r.capabilities.orEmpty()),
            WifiMath.band(r.frequency), if (Build.VERSION.SDK_INT >= 30) WifiMath.standard(r.wifiStandard) else "—", r.BSSID.equals(cur, true))
    }

    private fun readLink() {
        val net = NetScanner.wifiNetwork(app)
        @Suppress("DEPRECATION")
        val wi: WifiInfo? = (net?.let { cm?.getNetworkCapabilities(it)?.transportInfo as? WifiInfo }.takeIf { Build.VERSION.SDK_INT >= 29 }
            ?.takeIf { it.bssid != null && it.bssid != "02:00:00:00:00:00" }) ?: runCatching { wm?.connectionInfo }.getOrNull()
        if (net == null || wi == null || wi.networkId == -1 && wi.rssi < -126) { link.value = null; return }
        val lp = cm?.getLinkProperties(net)
        val bssid = wi.bssid?.takeIf { it != "02:00:00:00:00:00" }
        val match = aps.value.firstOrNull { it.bssid.equals(bssid, true) }
        @Suppress("DEPRECATION")
        val ssid = wi.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
        link.value = Link(
            ssid = ssid, bssid = bssid, rssi = wi.rssi.takeIf { it in -126..0 }, linkMbps = wi.linkSpeed.takeIf { it > 0 },
            txMbps = if (Build.VERSION.SDK_INT >= 29) wi.txLinkSpeedMbps.takeIf { it > 0 } else null,
            rxMbps = if (Build.VERSION.SDK_INT >= 29) wi.rxLinkSpeedMbps.takeIf { it > 0 } else null,
            freq = wi.frequency.takeIf { it > 0 }, channel = WifiMath.channel(wi.frequency), widthMhz = match?.widthMhz,
            standard = if (Build.VERSION.SDK_INT >= 30) WifiMath.standard(wi.wifiStandard) else "—",
            ip = lp?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress,
            gateway = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress,
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            security = match?.security ?: if (Build.VERSION.SDK_INT >= 31) secName(wi.currentSecurityType) else null,
            vendor = bssid?.let { NetScanner.vendor(app, it) },
        )
        if (bssid != null && wi.rssi in -126..0) {
            val h = history.value.toMutableMap(); h["link"] = (h["link"].orEmpty() + wi.rssi).takeLast(120); history.value = h
        }
        @Suppress("UNUSED_VARIABLE") val unused = NetworkCapabilities.TRANSPORT_WIFI
    }

    private fun secName(t: Int) = when (t) { 0 -> "Открытая"; 1 -> "WEP"; 2 -> "WPA/WPA2"; 3 -> "Enterprise"; 4 -> "WPA3"; 6 -> "OWE"; 5, 9 -> "WPA3-Enterprise"; else -> null }
}
