package net.antibs.client.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.antibs.client.data.NetScanLogic
import net.antibs.client.data.DeviceIdLogic
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * 1.0.41: Wi‑Fi local network scanner. Every socket is bound to the Wi‑Fi [Network], so the
 * scan goes to the real LAN even while our VPN (or another one) is up.
 */
object NetScanner {
    data class WifiState(
        val network: Network?,
        val ssid: String?,          // null = no permission / location off
        val bssid: String?,
        val iface: String?,
        val ip: String?,
        val prefix: Int,
        val gateway: String?,
        val dns: List<String>,
        val linkMbps: Int?,
        val rssi: Int?,
        val freqMhz: Int?,
    ) {
        val range: NetScanLogic.Range? get() = ip?.let { NetScanLogic.range(it, prefix) }
    }

    data class Device(
        val ip: String,
        val latencyMs: Int?,
        val via: String,             // ICMP / TCP / ARP / mDNS
        val mac: String? = null,
        val vendor: String? = null,
        val host: String? = null,
        val hostSource: String? = null,
        val ports: List<Int> = emptyList(),
        val portsScanned: Boolean = false,
        val isGateway: Boolean = false,
        val isSelf: Boolean = false,
        // 1.0.59: identification
        val type: DeviceIdLogic.DevType? = null,
        val name: String? = null,
        val model: String? = null,
        val evidence: List<String> = emptyList(),
    ) {
        val kind: String get() = type?.label ?: NetScanLogic.guessKind(host, vendor, ports, isGateway)
    }

    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        } ?: cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun wifiState(ctx: Context): WifiState {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = wifiNetwork(ctx)
        val lp: LinkProperties? = net?.let { cm?.getLinkProperties(it) }
        val la = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        var info: WifiInfo? = null
        if (Build.VERSION.SDK_INT >= 31) info = net?.let { cm?.getNetworkCapabilities(it)?.transportInfo as? WifiInfo }
        val wm = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java) }.getOrNull()
        val legacy = runCatching { wm?.connectionInfo }.getOrNull()
        if (info == null || info.ssid.isNullOrBlank() || info.ssid == WifiManager.UNKNOWN_SSID) info = legacy ?: info
        val ssid = info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }
        return WifiState(
            network = net, ssid = ssid, bssid = bssid, iface = lp?.interfaceName,
            ip = la?.address?.hostAddress, prefix = la?.prefixLength ?: 24, gateway = gw,
            dns = lp?.dnsServers?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }.orEmpty(),
            linkMbps = info?.linkSpeed?.takeIf { it > 0 }, rssi = info?.rssi?.takeIf { it in -126..0 },
            freqMhz = info?.frequency?.takeIf { it > 0 },
        )
    }

    // ── OUI ──
    @Volatile private var oui: Map<String, String>? = null
    fun vendor(ctx: Context, mac: String?): String? {
        if (mac == null) return null
        if (NetScanLogic.isRandomMac(mac)) return "Случайный MAC"
        val key = NetScanLogic.ouiKey(mac) ?: return null
        val m = oui ?: synchronized(this) {
            oui ?: runCatching {
                GZIPInputStream(ctx.assets.open("oui.txt.gz")).bufferedReader().useLines { s ->
                    s.mapNotNull { l -> val t = l.indexOf('\t'); if (t == 6) l.substring(0, 6) to l.substring(7) else null }.toMap(HashMap(48000))
                }
            }.getOrElse { emptyMap() }.also { oui = it }
        }
        return m[key]
    }

    /** ARP / neighbour table. Android 10+ hides it from apps (SELinux), so this is best-effort. */
    fun neighbours(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching { out += NetScanLogic.parseNeighbours(File("/proc/net/arp").readText()) }
        if (out.isEmpty()) runCatching {
            val p = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText(); p.waitFor()
            out += NetScanLogic.parseNeighbours(t)
        }
        return out
    }

    // ── probes ──
    private fun tcp(net: Network?, ip: String, port: Int, timeout: Int): Pair<Boolean, Boolean> {
        // first = host alive, second = port open
        // 2.0.5: never fall back to the default (VPN) network
        if (net == null) return false to false
        val s = net.socketFactory.createSocket()
        return try {
            s.connect(InetSocketAddress(InetAddress.getByAddress(addr(ip)), port), timeout); true to true
        } catch (e: ConnectException) {
            // ECONNREFUSED = the host answered with RST
            ((e.message ?: "").contains("refused", true)) to false
        } catch (_: Exception) { false to false } finally { runCatching { s.close() } }
    }

    private fun addr(ip: String): ByteArray {
        val v = NetScanLogic.ipToInt(ip)!!
        return byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    private fun udp(net: Network?, ip: String, port: Int, payload: ByteArray, timeout: Int): ByteArray? {
        if (net == null) return null
        val s = DatagramSocket()
        return try {
            net.bindSocket(s)
            s.soTimeout = timeout
            s.send(DatagramPacket(payload, payload.size, InetAddress.getByAddress(addr(ip)), port))
            val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size)
            s.receive(p); buf.copyOf(p.length)
        } catch (_: Exception) { null } finally { s.close() }
    }

    /** Alive check: ICMP (bound to the Wi‑Fi interface) then a few TCP ports. */
    private suspend fun probe(net: Network?, nif: NetworkInterface?, ip: String, timeout: Int): Device? = coroutineScope {
        val t0 = SystemClock.elapsedRealtime()
        // 2.0.5: ICMP via ping pinned to the Wi‑Fi interface (isReachable may fall back to an unbound TCP echo)
        val icmp = nif != null && icmpPing(nif.name, ip, timeout)
        if (icmp) return@coroutineScope Device(ip, (SystemClock.elapsedRealtime() - t0).toInt(), "ICMP")
        val found = NetScanLogic.PROBE_PORTS.map { port ->
            async(Dispatchers.IO) {
                val s = SystemClock.elapsedRealtime()
                val (alive, open) = tcp(net, ip, port, timeout)
                if (alive) Triple(port, open, (SystemClock.elapsedRealtime() - s).toInt()) else null
            }
        }.awaitAll().filterNotNull()
        if (found.isEmpty()) null
        else Device(ip, found.minOf { it.third }, "TCP", ports = found.filter { it.second }.map { it.first }.sorted())
    }

    private fun icmpPing(iface: String, ip: String, timeout: Int): Boolean = runCatching {
        val p = ProcessBuilder("ping", "-c", "1", "-W", "${maxOf(1, (timeout + 999) / 1000)}", "-I", iface, ip).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() == 0 && out.contains("bytes from $ip")
    }.getOrDefault(false)

    fun hostName(net: Network?, st: WifiState, ip: String): Pair<String, String>? {
        val rev = NetScanLogic.reverseName(ip)
        // 1) unicast mDNS straight to the device (Apple, Android, avahi answer it)
        udp(net, ip, 5353, NetScanLogic.dnsQuery(0, rev, unicastResponse = true), 700)?.let { NetScanLogic.parsePtr(it) }?.let { return it to "mDNS" }
        // 2) NetBIOS (Windows / Samba)
        udp(net, ip, 137, NetScanLogic.nbstatQuery(0x4242), 600)?.let { NetScanLogic.parseNbstat(it) }?.let { return it to "NetBIOS" }
        // 3) rDNS via the Wi‑Fi DNS (router usually knows DHCP names)
        for (d in (st.dns + listOfNotNull(st.gateway)).distinct().filter { NetScanLogic.ipToInt(it) != null }.take(2)) {
            udp(net, d, 53, NetScanLogic.dnsQuery(0x5151, rev), 800)?.let { NetScanLogic.parsePtr(it) }?.let { return it to "rDNS" }
        }
        return null
    }

    suspend fun scanPorts(ctx: Context, ip: String, timeout: Int = 500): List<Int> = withContext(Dispatchers.IO) {
        val net = wifiNetwork(ctx)
        NetScanLogic.COMMON_PORTS.map { (p, _) -> async { if (tcp(net, ip, p, timeout).second) p else null } }.awaitAll().filterNotNull().sorted()
    }

    /**
     * Full scan. [onProgress] gets (done, total); [onDevice] each found/updated device.
     */
    suspend fun scan(
        ctx: Context,
        st: WifiState,
        timeoutMs: Int = 400,
        parallel: Int = 64,
        onProgress: (Int, Int) -> Unit,
        onDevice: (Device) -> Unit,
        onWarning: (String) -> Unit = {},
    ): List<Device> = withContext(Dispatchers.IO) {
        val range = st.range ?: return@withContext emptyList()
        val net = st.network ?: wifiNetwork(ctx)
        if (net == null) { onWarning("Сеть Wi‑Fi не найдена — сканирование через VPN дало бы ложные результаты."); return@withContext emptyList() }
        val nif = st.iface?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
        val hosts = NetScanLogic.hosts(range)
        val done = AtomicInteger(0)
        val sem = Semaphore(parallel)
        val found = java.util.concurrent.ConcurrentHashMap<String, Device>()
        // 1.0.59: multicast discovery runs alongside the sweep
        val lock = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("lan-scan")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val mdnsJob = async { runCatching { DeviceIdentifier.mdns(net, 2500) }.getOrDefault(emptyMap()) }
        val ssdpJob = async { runCatching { DeviceIdentifier.ssdp(net, 2200) }.getOrDefault(emptyMap()) }
        // self + gateway are always listed
        st.ip?.let { found[it] = Device(it, 0, "это устройство", isSelf = true) }
        hosts.map { ip ->
            async {
                sem.withPermit {
                    ensureActive()
                    if (ip != st.ip) probe(net, nif, ip, timeoutMs)?.let { d ->
                        val dd = d.copy(isGateway = ip == st.gateway); found[ip] = dd; onDevice(dd)
                    }
                    onProgress(done.incrementAndGet(), hosts.size)
                }
            }
        }.awaitAll()
        // 2.0.5: drop fake answers (identical behaviour across the subnet) and flag an implausible result
        val verdict = NetScanLogic.judge(found.values.filter { !it.isSelf }.map { NetScanLogic.Probe(it.ip, it.latencyMs, it.via, it.ports) },
            hosts.size, setOfNotNull(st.gateway))
        verdict.fake.forEach { found.remove(it) }
        verdict.reason?.let(onWarning)
        // the probes filled the neighbour cache — read it when allowed
        val arp = neighbours()
        for ((ip, mac) in arp) {
            if (NetScanLogic.ipToInt(ip)?.let { it in range.first..range.last } != true) continue
            val d = found[ip] ?: Device(ip, null, "ARP", isGateway = ip == st.gateway)
            found[ip] = d.copy(mac = mac, vendor = vendor(ctx, mac))
        }
        st.gateway?.let { g -> if (found[g] == null && NetScanLogic.ipToInt(g) != null) found[g] = Device(g, null, "шлюз", isGateway = true) }
        found.values.forEach(onDevice)
        // names, in parallel
        val named = found.values.map { d ->
            async { sem.withPermit { (if (d.isSelf) null else hostName(net, st, d.ip))?.let { (h, src) -> d.copy(host = h, hostSource = src) } ?: d } }
        }.awaitAll()
        named.forEach(onDevice)
        // 1.0.59: identify (mDNS + SSDP + ports + HTTP), all in parallel with short timeouts
        val mdns = mdnsJob.await(); val ssdp = ssdpJob.await()
        runCatching { lock?.release() }
        val all = LinkedHashMap<String, Device>(); named.forEach { all[it.ip] = it }
        for (ip in mdns.keys + ssdp.keys) {
            if (ip !in all && NetScanLogic.ipToInt(ip)?.let { it in range.first..range.last } == true)
                all[ip] = Device(ip, null, if (ip in mdns) "mDNS" else "SSDP", isGateway = ip == st.gateway, isSelf = ip == st.ip)
        }
        val ided = all.values.map { d ->
            async { sem.withPermit { runCatching { identify(net, d, mdns[d.ip], ssdp[d.ip]) }.getOrDefault(d) } }
        }.awaitAll()
        ided.forEach(onDevice)
        ided.sortedBy { NetScanLogic.ipToInt(it.ip)?.toLong()?.and(0xffffffffL) ?: 0L }
    }

    private fun httpProbe(net: Network?, ip: String, port: Int): Pair<String?, String?>? = runCatching {
        val url = java.net.URL(if (port == 443) "https://$ip/" else "http://$ip:$port/")
        val c = (net ?: return@runCatching null).openConnection(url) as java.net.HttpURLConnection
        if (c is javax.net.ssl.HttpsURLConnection) { c.sslSocketFactory = DeviceIdentifier.trustAll; c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } }
        c.connectTimeout = 1200; c.readTimeout = 1500; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val server = c.getHeaderField("Server")
            val body = runCatching { (if (c.responseCode >= 400) c.errorStream else c.inputStream)?.use { s -> String(s.readNBytesCompat(32_768), Charsets.UTF_8) } }.getOrNull()
            server to body?.let { DeviceIdLogic.htmlTitle(it) }
        } finally { c.disconnect() }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (out.size() < n) { val r = read(buf, 0, minOf(buf.size, n - out.size())); if (r <= 0) break; out.write(buf, 0, r) }
        return out.toByteArray()
    }

    /** 1.0.59: fold every source of evidence into type / name / model. */
    private suspend fun identify(net: Network?, d: Device, mdns: DeviceIdLogic.MdnsInfo?, ssdp: DeviceIdentifier.Ssdp?): Device = coroutineScope {
        val fp = if (d.isSelf) emptyList() else DeviceIdLogic.FP_PORTS.filter { it !in d.ports }.map { p -> async(Dispatchers.IO) { if (tcp(net, d.ip, p, 350).second) p else null } }.awaitAll().filterNotNull()
        val ports = (d.ports + fp).distinct().sorted()
        val http = listOf(80, 8080, 443).filter { it in ports }.map { p -> async(Dispatchers.IO) { httpProbe(net, d.ip, p) } }.awaitAll().filterNotNull()
        val server = http.firstNotNullOfOrNull { it.first }; val title = http.firstNotNullOfOrNull { it.second }
        val e = DeviceIdLogic.Evidence(
            isGateway = d.isGateway, ports = ports, host = d.host, vendor = d.vendor, mdns = mdns, upnp = ssdp?.upnp, upnpType = ssdp?.st,
            netbios = d.host.takeIf { d.hostSource == "NetBIOS" }, httpTitle = title, httpServer = server,
        )
        val ev = buildList {
            if (d.isGateway) add("шлюз по умолчанию")
            d.host?.let { add("${d.hostSource ?: "имя"}: $it") }
            mdns?.let { m -> add("mDNS: " + listOfNotNull(m.name, m.model, m.services.sorted().joinToString(", ").takeIf { it.isNotEmpty() }).joinToString(" · ")) }
            ssdp?.let { s -> add("SSDP/UPnP: " + listOfNotNull(s.upnp?.friendlyName, s.upnp?.manufacturer, s.upnp?.modelName, s.server).joinToString(" · ").ifEmpty { s.st ?: "ответ" }) }
            if (fp.isNotEmpty() || d.ports.isNotEmpty()) add("порты: " + ports.joinToString(", "))
            title?.let { add("HTTP title: $it") }
            server?.let { add("HTTP Server: $it") }
            d.vendor?.let { add("MAC/OUI: $it") }
        }
        d.copy(ports = ports, type = if (d.isSelf) DeviceIdLogic.DevType.PHONE else DeviceIdLogic.classify(e),
            name = DeviceIdLogic.bestName(e), model = DeviceIdLogic.makeModel(e), evidence = ev)
    }
}
