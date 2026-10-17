package net.antibs.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import net.antibs.client.MainActivity
import net.antibs.client.R
import net.antibs.client.data.EngineKind
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.WgConfig
import net.antibs.client.data.XrayConfigBuilder
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.QwdttEngine
import net.antibs.client.engine.SingBoxEngine
import net.antibs.client.engine.MieruEngine
import net.antibs.client.engine.MihomoEngine
import net.antibs.client.engine.LogTailer
import net.antibs.client.data.AdvancedSettings
import net.antibs.client.data.Cidr
import net.antibs.client.data.XrayProfile
import android.net.ConnectivityManager
import android.net.IpPrefix
import java.net.InetAddress
import java.net.InetSocketAddress
import net.antibs.client.engine.VpnState
import net.antibs.client.engine.VpnStatus
import net.antibs.client.engine.XrayCore
import net.antibs.client.ui.UiLogic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.concurrent.thread

/**
 * Device-wide VPN: TUN → hev-socks5-tunnel (userspace tun2socks) → local SOCKS → Xray-core,
 * or (setting «Xray TUN») TUN fd → Xray's own tun inbound. «Режим: Прокси» runs Xray inbounds only (no TUN).
 * Helper engines (sing-box / mieru / mihomo) sit behind loopback SOCKS slots that Xray dials.
 * For qWDTT profiles Xray's proxy outbound is a WireGuard tunnel to the local qWDTT client process.
 */
class AntiBSVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var statsThread: Thread? = null
    @Volatile private var running = false
    private var currentEntry: ServerEntry? = null
    /** 1.0.19: hosts behind outbounds b-0, b-1, … when the current entry is a balancer / auto-select. */
    @Volatile private var balancerMembers: List<ServerEntry> = emptyList()
    /** 1.0.34: the panel's auto-select JSON is running — its balancer pick is read from the Xray API. */
    @Volatile private var jsonAuto: net.antibs.client.engine.JsonAutoPick? = null
    /** 1.0.36: sticky auto-select (ping all → pin fastest → check pinned every 30 s → fail over). */
    private class PinSetup(val apiPort: Int, val balancerTag: String, val probes: Map<String, Int>,
                           val names: Map<String, String>, val protocols: Map<String, String>)
    @Volatile private var pinSetup: PinSetup? = null
    private var autoThread: Thread? = null
    private val sessionGate = Any()
    private var session = 0

    private fun bumpSession(): Int = synchronized(sessionGate) { ++session }
    private fun isCurrent(gen: Int): Boolean = synchronized(sessionGate) { gen == session }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startForegroundCompat("Подключение…")
                thread(name = "vpn-connect") { connect() }
            }
            ACTION_DISCONNECT -> thread(name = "vpn-disconnect") { disconnect() }
            else -> if (!running) stopSelf()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        thread { disconnect() }
        super.onRevoke()
    }

    override fun onDestroy() {
        thread { disconnect() }
        super.onDestroy()
    }

    private fun connect() {
        XrayCore.installCertPins()
        val gen = bumpSession()
        val switching = running
        if (switching) {
            VpnState.update { it.copy(state = ConnState.CONNECTING, message = "Переключение…") }
            VpnState.log("переключение сервера")
        }
        stopAll()
        if (!isCurrent(gen)) return
        val store = AppStore(this)
        val snap = store.snapshot()
        // 1.0.8: no auto-select mode; AppStore normalizes the selection to a real host.
        val entry = store.selectedServer()
        if (!isCurrent(gen)) return
        if (entry == null) { VpnState.set(VpnStatus(ConnState.ERROR, message = "Сервер не выбран")); stopSelf(); return }
        val a = snap.adv
        VpnState.log("опции: mux=${snap.mux} frag=${snap.fragmentation} noise=${a.noise} ip=${snap.ipType} lan=${snap.allowLan} bypass=${snap.bypassLan} " +
            "режим=${a.mode} туннель=${if (snap.xrayTun) "Xray TUN" else "hev-socks5"} mtu=${a.tunMtu} per-app=${a.perAppMode}" +
            (activeRouting(snap)?.let { " маршрутизация=«${it.name}»" } ?: ""))
        currentEntry = entry
        val protocol = UiLogic.protocolGroup(entry)
        balancerMembers = emptyList()
        jsonAuto = null
        pinSetup = null
        VpnState.set(VpnStatus(ConnState.CONNECTING, serverName = entry.name, protocol = protocol, message = if (switching) "Переключение…" else "Подключение…"))
        VpnState.log("подключение к «${entry.name}» ($protocol)")
        try {
            when (entry.engine) {
                EngineKind.XRAY -> {
                    val reason = entry.xray?.unsupportedReason
                    if (!reason.isNullOrBlank()) throw IllegalStateException(reason)
                    if (entry.xray != null && !ProtocolSupport.isRunnable(entry.xray!!.protocol)) {
                        throw IllegalStateException(ProtocolSupport.unsupportedReason(entry.xray!!.protocol)
                            ?: "Протокол ${entry.xray!!.protocol} не поддерживается")
                    }
                    val dns = SingBoxEngine.underlyingDns(this)
                    var all = store.servers()
                    var target = entry
                    if (a.resolveServers) {
                        all = all.map { resolveEntry(it) }
                        target = all.firstOrNull { it.id == entry.id } ?: resolveEntry(entry)
                    }
                    var o = opts(store)
                    val panelAuto = net.antibs.client.data.AutoSelect.isJsonAuto(target)
                    val ownAuto = net.antibs.client.data.AutoSelect.isSubscriptionAuto(target)
                    if (panelAuto) o = o.copy(apiPort = net.antibs.client.engine.XrayApi.freePort())
                    val plan0 = XrayConfigBuilder.plan(target, all, o, singBoxDns = dns)
                    var xrayCfg = plan0.xray
                    VpnState.log("движок: ${plan0.engineLabel}")
                    if (panelAuto || ownAuto) {
                        // 1.0.36: the balancer stays exactly as configured; the app only adds per-member probe inbounds
                        // + the API and pins the chosen member with OverrideBalancerTarget.
                        val cfgObj = net.antibs.client.engine.AutoPin.parse(xrayCfg)
                        val balTag = cfgObj?.let { net.antibs.client.engine.AutoPin.firstBalancerTag(it) }
                        val tags = if (cfgObj != null && balTag != null) net.antibs.client.engine.AutoPin.balancerMembers(cfgObj, balTag) else emptyList()
                        if (balTag != null && tags.isNotEmpty()) {
                            val api = o.apiPort.takeIf { it > 0 } ?: net.antibs.client.engine.XrayApi.freePort()
                            val probes = tags.associateWith { net.antibs.client.engine.XrayApi.freePort() }.filterValues { it > 0 }
                            xrayCfg = net.antibs.client.engine.AutoPin.withProbes(xrayCfg, api, probes)
                            val names: Map<String, String> = if (panelAuto) net.antibs.client.data.AutoSelect.liveNames(target, all) else {
                                val m = XrayConfigBuilder.balancerMembers(target, all, o.autoExclude)
                                tags.mapNotNull { t -> net.antibs.client.engine.BalancerPick.indexOf(t)?.let { i -> m.getOrNull(i)?.let { t to it.name } } }.toMap()
                            }
                            pinSetup = PinSetup(api, balTag, probes, names, net.antibs.client.engine.JsonAutoPick.outboundProtocols(xrayCfg))
                            VpnState.log("автовыбор: ${if (panelAuto) "конфиг панели как есть" else "свой балансировщик"} («$balTag», серверов ${tags.size}), закрепление по пингу")
                        }
                    } else {
                        balancerMembers = XrayConfigBuilder.balancerMembers(target, all, o.autoExclude)
                        if (balancerMembers.isNotEmpty()) VpnState.log("балансировщик (leastPing): серверов ${balancerMembers.size}")
                    }
                    val plan = plan0.copy(xray = xrayCfg)
                    if (plan.singBox != null) {
                        startSingBox(plan.singBox, plan.singBoxSlots.map { it.port }, entry.name, gen)
                    }
                    plan.mieruInstances.forEach { (cfg, port) -> startMieru(cfg, port, entry.name, gen) }
                    if (plan.mihomo != null) startMihomo(plan.mihomo, plan.mihomoPorts, entry.name, gen)
                    plan.olcInstances.forEach { (prof, port) -> startOlcrtc(prof, port, store.snapshot().qwdttDns, entry.name, gen) }
                    plan.vpnInstances.forEach { (prof, port) -> startClassicVpn(prof, port, entry.name, gen) }
                    startXray(plan.xray, entry.name, protocol, gen)
                }
                EngineKind.QWDTT -> startQwdtt(store, entry, protocol, gen)
            }
        } catch (e: Throwable) {
            if (!isCurrent(gen)) return
            VpnState.log("ошибка: ${e.message}")
            VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = e.message ?: "ошибка"))
            stopAll()
            stopSelf()
        }
    }

    private fun activeRouting(s: net.antibs.client.engine.AppState) =
        s.adv.activeRoutingId?.let { id -> s.adv.routingProfiles.firstOrNull { it.id == id } }

    private fun useXrayTun(s: net.antibs.client.engine.AppState) = s.xrayTun && s.adv.mode != "proxy"

    private fun opts(store: AppStore): XrayConfigBuilder.Options {
        val s = store.snapshot()
        val a = s.adv
        val routing = activeRouting(s)?.let { r ->
            // a downloaded geo file that's gone (cleared data) → fall back to the stock files for those refs
            fun ok(f: String) = f.isNotBlank() && File(filesDir, f).length() > 0
            r.copy(geoipFile = r.geoipFile.takeIf { ok(it) }.orEmpty(), geositeFile = r.geositeFile.takeIf { ok(it) }.orEmpty())
        }
        XrayCore.prepareAssets(this)
        val geoBundled = net.antibs.client.engine.GeoFiles.bundledTags(this)
        if (routing != null && geoBundled != null) {
            val dropped = routing.rules.mapNotNull { r -> r.value.takeIf { net.antibs.client.data.GeoDat.resolve(it, routing, geoBundled) == null } }
            if (dropped.isNotEmpty()) VpnState.log("маршрутизация «${routing.name}»: нет в geo-файлах, пропущено: ${dropped.joinToString()}")
        }
        val xtun = useXrayTun(s)
        val uids = HashMap<String, Int>()
        if (routing != null && xtun) {
            routing.rules.filter { it.kind == "process" }.forEach { r ->
                val pkg = r.value.trim().substringAfter(':', r.value.trim())
                if (pkg.toIntOrNull() == null) try {
                    uids[pkg] = packageManager.getApplicationInfo(pkg, 0).uid
                } catch (_: Exception) { VpnState.log("маршрутизация: приложение $pkg не найдено") }
            }
        } else if (routing != null && routing.rules.any { it.kind == "process" }) {
            VpnState.log("маршрутизация: правила по приложениям работают только с Xray TUN — пропущены")
        }
        val logDir = File(filesDir, "logs").apply { mkdirs() }
        return XrayConfigBuilder.Options(
            socksPort = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT,
            bypassLan = s.bypassLan,
            mux = s.mux,
            fragmentation = s.fragmentation,
            ipType = s.ipType,
            allowLan = s.allowLan,
            sniffing = a.sniffing,
            logLevel = a.logLevel.takeIf { it in setOf("none", "error", "warning", "info", "debug") } ?: "warning",
            adv = a,
            xrayTun = xtun,
            logDir = logDir.absolutePath,
            routing = routing,
            packageUids = uids,
            geoBundled = geoBundled,
            autoExclude = s.autoExcludeIds.toSet(),
            // 1.0.40: AdGuard / whitelist / own lists compiled into one file; rf lists for presets
            adBlock = net.antibs.client.engine.BlockFiles.prepare(this, a) { VpnState.log(it) },
            geoExtra = net.antibs.client.engine.BlockFiles.geoExtra(this),
        ).also {
            if (net.antibs.client.data.TrafficPresets.active(a)) {
                VpnState.log("правила трафика: «${net.antibs.client.data.TrafficPresets.title(a.trafficPreset)}»" +
                    (if (routing != null) " — профиль маршрутизации «${routing.name}» не применяется" else ""))
            }
        }
    }

    /** «Резолвить адреса серверов»: dial by IP, keep the hostname as SNI / Host so TLS still verifies. */
    private fun resolveEntry(e: ServerEntry): ServerEntry {
        val p = e.xray ?: return e
        if (p.protocol in setOf("chain", "balancer", "custom", "wireguard")) return e
        val host = p.server
        if (host.isBlank() || host == "—" || host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || host.contains(':')) return e
        return try {
            val ip = InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address }?.hostAddress
                ?: InetAddress.getByName(host).hostAddress ?: return e
            val tls = p.security == "tls" || p.security == "reality" || p.protocol in setOf("hysteria", "hysteria1", "tuic", "anytls", "naive", "http3", "trusttunnel", "shadowquic")
            val q: XrayProfile = p.copy(
                server = ip,
                sni = if (tls) (p.sni?.ifBlank { null } ?: host) else p.sni,
                host = if (p.network in setOf("ws", "httpupgrade", "xhttp", "h2") && p.host.isNullOrBlank()) host else p.host,
            )
            e.copy(xray = q)
        } catch (_: Exception) { e }
    }

    private fun startQwdtt(store: AppStore, entry: ServerEntry, protocol: String, gen: Int) {
        val p = entry.qwdtt!!
        val st = store.snapshot()
        val engine = QwdttEngine(
            ctx = this, profile = p, globalHashes = st.vkHashes, dns = st.qwdttDns, obfs = st.qwdttObfs, turnTcp = st.qwdttTurnTcp,
            onWgConfig = wg@{ wg ->
                if (!isCurrent(gen)) return@wg
                try {
                    startXray(XrayConfigBuilder.forQwdtt(wg, opts(store)), entry.name, protocol, gen)
                } catch (e: Throwable) {
                    if (!isCurrent(gen)) return@wg
                    VpnState.log("ошибка Xray: ${e.message}")
                    VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = e.message ?: "ошибка"))
                    stopAll(); stopSelf()
                }
            },
            onFatal = fatal@{ msg ->
                if (!isCurrent(gen)) return@fatal
                if (running || VpnState.status.value.state == ConnState.CONNECTING) {
                    VpnState.log(msg)
                    VpnState.set(VpnStatus(ConnState.ERROR, serverName = entry.name, message = msg))
                    stopAll(); stopSelf()
                }
            },
        )
        QwdttEngine.current = engine
        engine.start()
    }

    private fun startMieru(config: String, port: Int, name: String, gen: Int) {
        if (!isCurrent(gen)) return
                val engine = MieruEngine(this, config, port)
        engine.onExit = exit@{ msg ->
            if (!isCurrent(gen)) return@exit
            if (running || VpnState.status.value.state == ConnState.CONNECTING) {
                VpnState.log(msg)
                VpnState.set(VpnStatus(ConnState.ERROR, serverName = name, message = msg))
                stopAll(); stopSelf()
            }
        }
        MieruEngine.register(engine)
        engine.start()
        if (!isCurrent(gen)) { engine.stop() }
    }

    /** 1.0.41: olcRTC helper (libolcrtc.so) behind loopback SOCKS [port]. */
    private fun startOlcrtc(p: net.antibs.client.data.XrayProfile, port: Int, dnsKey: String, name: String, gen: Int) {
        if (!isCurrent(gen)) return
        val engine = net.antibs.client.engine.OlcrtcEngine(this, p, port, net.antibs.client.engine.OlcrtcEngine.dnsFor(dnsKey))
        engine.onExit = exit@{ msg ->
            if (!isCurrent(gen)) return@exit
            if (running || VpnState.status.value.state == ConnState.CONNECTING) {
                VpnState.log(msg)
                VpnState.set(VpnStatus(ConnState.ERROR, serverName = name, message = msg))
                stopAll(); stopSelf()
            }
        }
        net.antibs.client.engine.OlcrtcEngine.register(engine)
        engine.start()
        if (!isCurrent(gen)) engine.stop()
    }

    /** 1.0.41: OpenVPN / OpenConnect helper (libvpnstack.so) behind loopback SOCKS [port]. */
    private fun startClassicVpn(p: net.antibs.client.data.XrayProfile, port: Int, name: String, gen: Int) {
        if (!isCurrent(gen)) return
        val engine = net.antibs.client.engine.ClassicVpnEngine(this, p, port)
        engine.onExit = exit@{ msg ->
            if (!isCurrent(gen)) return@exit
            if (running || VpnState.status.value.state == ConnState.CONNECTING) {
                VpnState.log(msg)
                VpnState.set(VpnStatus(ConnState.ERROR, serverName = name, message = msg))
                stopAll(); stopSelf()
            }
        }
        net.antibs.client.engine.ClassicVpnEngine.register(engine)
        engine.start()
        if (!isCurrent(gen)) engine.stop()
    }

    private fun startMihomo(config: String, ports: List<Int>, name: String, gen: Int) {
        if (!isCurrent(gen)) return
        MihomoEngine.current?.stop()
        val engine = MihomoEngine(this, config, ports)
        engine.onExit = exit@{ msg ->
            if (!isCurrent(gen)) return@exit
            if (running || VpnState.status.value.state == ConnState.CONNECTING) {
                VpnState.log(msg)
                VpnState.set(VpnStatus(ConnState.ERROR, serverName = name, message = msg))
                stopAll(); stopSelf()
            }
        }
        MihomoEngine.current = engine
        engine.start()
        if (!isCurrent(gen)) { engine.stop(); MihomoEngine.current = null; return }
    }

    private fun startSingBox(config: String, ports: List<Int>, name: String, gen: Int) {
        if (!isCurrent(gen)) return
        SingBoxEngine.current?.stop()
        val engine = SingBoxEngine(this, config, ports)
        engine.onExit = exit@{ msg ->
            if (!isCurrent(gen)) return@exit
            if (running || VpnState.status.value.state == ConnState.CONNECTING) {
                VpnState.log(msg)
                VpnState.set(VpnStatus(ConnState.ERROR, serverName = name, message = msg))
                stopAll(); stopSelf()
            }
        }
        SingBoxEngine.current = engine
        engine.start()
        if (!isCurrent(gen)) { engine.stop(); SingBoxEngine.current = null; return }
    }

    private fun startLogTail() {
        LogTailer.current?.stop()
        val dir = File(filesDir, "logs")
        val files = listOf(File(dir, "xray-error.log") to "[xray]", File(dir, "xray-access.log") to "[xray-access]")
        files.forEach { (f, _) -> try { f.writeText("") } catch (_: Exception) {} }
        LogTailer.current = LogTailer(files).also { it.start() }
    }

    private val processFinder = object : libv2ray.ProcessFinder {
        override fun findProcessByConnection(network: String?, srcIP: String?, srcPort: Long, destIP: String?, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < 29) return -1
            return try {
                val cm = getSystemService(ConnectivityManager::class.java)
                val proto = if (network == "udp") android.system.OsConstants.IPPROTO_UDP else android.system.OsConstants.IPPROTO_TCP
                cm.getConnectionOwnerUid(proto, InetSocketAddress(srcIP, srcPort.toInt()), InetSocketAddress(destIP, destPort.toInt())).toLong()
            } catch (_: Throwable) { -1 }
        }
    }

    private fun startXray(config: String, name: String, protocol: String, gen: Int) {
        if (!isCurrent(gen)) return
        val snap = AppStore(this).snapshot()
        val a = snap.adv
        val proxyOnly = a.mode == "proxy"
        val xtun = useXrayTun(snap)
        startLogTail()
        if (proxyOnly) {
            XrayCore.start(this, config)
            if (!isCurrent(gen)) { XrayCore.stop(); return }
            VpnState.log("[xray] ${XrayCore.version()}")
            VpnState.log("режим прокси: SOCKS 127.0.0.1:${a.socksPort}" + (if (a.httpInbound) ", HTTP 127.0.0.1:${a.httpPort}" else "") + " (без VPN-интерфейса)")
        } else if (xtun) {
            // Xray TUN: open the interface first, Xray reads the fd itself (gvisor stack, process routing available)
            val pfd = openTun(a)
            if (!isCurrent(gen)) { try { pfd.close() } catch (_: Throwable) {}; return }
            tun = pfd
            XrayCore.start(this, config, tunFd = pfd.fd, finder = processFinder)
            if (!isCurrent(gen)) { XrayCore.stop(); return }
            VpnState.log("[xray] ${XrayCore.version()} · TUN fd=${pfd.fd} mtu=${a.tunMtu}")
        } else {
            XrayCore.start(this, config)
            if (!isCurrent(gen)) { XrayCore.stop(); return }
            VpnState.log("[xray] ${XrayCore.version()}")
            val pfd = openTun(a)
            if (!isCurrent(gen)) {
                try { pfd.close() } catch (_: Throwable) {}
                XrayCore.stop()
                return
            }
            tun = pfd
            val cfg = File(filesDir, "hev-socks5-tunnel.yaml")
            val port = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT
            cfg.writeText(TProxyService.buildConfig(port, a.tunMtu.coerceIn(1280, 9000), "10.10.14.1", "fc00::10:10:14:1"))
            if (!TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)) throw IllegalStateException("hev-socks5-tunnel не запустился")
        }
        val stale = synchronized(sessionGate) {
            if (gen != session) true else {
                running = true
                false
            }
        }
        if (stale) { stopAll(); return }
        startForegroundCompat("Подключено: $name")
        VpnState.set(VpnStatus(ConnState.CONNECTED, serverName = name, protocol = protocol, since = System.currentTimeMillis(), message = "Подключено"))
        VpnState.log("подключено")
        pinSetup?.let { startAutoPin(it, name, gen) }
        startStats()
    }

    private fun openTun(a: AdvancedSettings): ParcelFileDescriptor {
        val mtu = a.tunMtu.coerceIn(1280, 9000)
        val b = Builder()
            .setSession("AntiBS2client")
            .setMtu(mtu)
            .addAddress("10.10.14.1", 30)
            .addAddress("fc00::10:10:14:1", 126)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        // «Исключённые маршруты»
        val excluded = a.excludedRoutes.mapNotNull { it.trim().ifEmpty { null } }
        val v4Excluded = excluded.mapNotNull { Cidr.parseV4(it) }
        if (excluded.isNotEmpty() && Build.VERSION.SDK_INT >= 33) {
            b.addRoute("0.0.0.0", 0).addRoute("::", 0)
            excluded.forEach { r ->
                try {
                    val ip = r.substringBefore('/')
                    val len = r.substringAfter('/', if (ip.contains(':')) "128" else "32").toInt()
                    b.excludeRoute(IpPrefix(InetAddress.getByName(ip), len))
                } catch (e: Exception) { VpnState.log("исключённый маршрут «$r» пропущен: ${e.message}") }
            }
            VpnState.log("исключено маршрутов: ${excluded.size}")
        } else if (v4Excluded.isNotEmpty()) {
            val routes = Cidr.complement(v4Excluded)
            routes.forEach { b.addRoute(it.toString().substringBefore('/'), it.prefix) }
            b.addRoute("::", 0)
            VpnState.log("исключено IPv4-маршрутов: ${v4Excluded.size} (маршрутов в VPN: ${routes.size})")
        } else {
            b.addRoute("0.0.0.0", 0).addRoute("::", 0)
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        // «Блок привязки к туннелю» off → apps may bind sockets to the underlying network (Builder.allowBypass)
        if (!a.tunnelBindBlock) b.allowBypass()
        // Per-app proxy (addAllowed / addDisallowed can't be mixed; AntiBS itself always bypasses)
        val plan = net.antibs.client.data.PerApp.plan(a.perAppMode, a.perAppInclude, a.perAppBypass, packageName)
        var allowedOk = 0
        plan.allowed.forEach { pkg -> try { b.addAllowedApplication(pkg); allowedOk++ } catch (_: Exception) { VpnState.log("per-app: $pkg не найдено") } }
        if (plan.allowed.isNotEmpty() && allowedOk == 0) throw IllegalStateException("Ни одно приложение из списка «ВКЛ» не установлено")
        plan.disallowed.forEach { pkg -> try { b.addDisallowedApplication(pkg) } catch (_: Exception) { VpnState.log("per-app: $pkg не найдено") } }
        if (plan.note.isNotEmpty()) VpnState.log(plan.note)
        return b.establish() ?: throw IllegalStateException("Не удалось создать VPN-интерфейс")
    }

    /** 1.0.40: last status text, so the traffic updates keep it as the title line. */
    @Volatile private var notifText: String = ""

    private fun buildNotification(text: String, sub: String?): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AntiBSVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("AntiBS2client")
            .setContentText(if (sub != null) "$text · $sub" else text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
        if (sub != null) b.setStyle(Notification.BigTextStyle().bigText("$text\n$sub"))
        return b.build()
    }

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
        notifText = text
        val n = buildNotification(text, null)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        VpnTileService.refresh(this)
    }

    /** 1.0.40: «Трафик в уведомлении». */
    private fun updateTrafficNotification(mode: String) {
        val st = VpnState.status.value
        if (st.state != ConnState.CONNECTED) return
        val line = UiLogic.notificationTraffic(mode, st.downSpeed + st.directDownSpeed, st.upSpeed + st.directUpSpeed,
            st.downBytes + st.directDownBytes, st.upBytes + st.directUpBytes) ?: return
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(notifText, line)) } catch (_: Exception) { }
    }

    private fun startStats() {
        statsThread = thread(name = "vpn-stats", isDaemon = true) {
            var prevT = System.currentTimeMillis()
            var prevDevRx = TrafficStats.getTotalRxBytes()
            var prevDevTx = TrafficStats.getTotalTxBytes()
            var loggedSource = false
            val members = balancerMembers
            val pick = if (members.isNotEmpty()) net.antibs.client.engine.BalancerPick() else null
            val ja = jsonAuto
            val jaTraffic = HashMap<String, Long>()
            var tick = 0
            val nAdv = AppStore(this).snapshot().adv
            val nMode = nAdv.notifTraffic
            val nEvery = nAdv.notifRefreshSec.coerceIn(1, 30)
            var nTick = 0
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (!running) break
                val delta = XrayCore.queryTrafficDelta()
                var up = delta.proxyUp
                var down = delta.proxyDown
                var dUp = delta.directUp
                var dDown = delta.directDown
                val devRx = TrafficStats.getTotalRxBytes()
                val devTx = TrafficStats.getTotalTxBytes()
                if (!delta.fromXray && devRx != TrafficStats.UNSUPPORTED.toLong() && devTx != TrafficStats.UNSUPPORTED.toLong()) {
                    // Approximation: blue stays the VPN counters (unknown here → 0) and red is
                    // device total minus whatever we already counted as VPN this tick.
                    val rx = (devRx - prevDevRx).coerceAtLeast(0)
                    val tx = (devTx - prevDevTx).coerceAtLeast(0)
                    val vpnSum = up + down
                    val rest = (rx + tx - vpnSum).coerceAtLeast(0)
                    dDown = rx.coerceAtMost(rest)
                    dUp = (rest - dDown).coerceAtLeast(0)
                }
                prevDevRx = devRx
                prevDevTx = devTx
                if (!loggedSource) {
                    loggedSource = true
                    VpnState.log(if (delta.fromXray) "трафик: Xray proxy/direct" else "трафик: оценка TrafficStats (direct ≈ устройство − VPN)")
                }
                val now = System.currentTimeMillis()
                val dt = (now - prevT).coerceAtLeast(1) / 1000.0
                var s = VpnState.status.value
                if (pick != null && delta.balancer.isNotEmpty()) {
                    pick.add(delta.balancer)?.let { tag ->
                        net.antibs.client.engine.BalancerPick.indexOf(tag)?.let { members.getOrNull(it) }?.let { m ->
                            VpnState.log("автовыбор: «${m.name}» (${UiLogic.protocolGroup(m)})")
                            s = s.copy(pickName = m.name, pickProtocol = UiLogic.protocolGroup(m))
                        }
                    }
                }
                if (ja != null) {
                    delta.balancer.forEach { (k, v) -> jaTraffic[k] = (jaTraffic[k] ?: 0L) + v }
                    // first look right away, then every 3 s
                    if (tick++ % 3 == 0) net.antibs.client.engine.XrayApi.balancerTargets(ja.apiPort, ja.balancerTag)?.let { targets ->
                        ja.update(targets, jaTraffic)?.let { tag ->
                            val name = ja.nameOf(tag)
                            val proto = ja.protocols[tag]?.let { net.antibs.client.ui.Format.protocolLabel(it) }.orEmpty()
                            val delay = net.antibs.client.engine.XrayApi.outboundStatus(ja.apiPort)?.firstOrNull { it.tag == tag }?.delayMs
                            VpnState.log("автовыбор (Xray): «$name» [$tag]" + (delay?.takeIf { it > 0 }?.let { ", $it мс" } ?: ""))
                            s = s.copy(pickName = name, pickProtocol = proto)
                        }
                    }
                }
                val picked = s
                // 1.0.36: atomic update — the auto-pin thread may set pickName concurrently
                VpnState.update { c -> c.copy(
                    upBytes = c.upBytes + up, downBytes = c.downBytes + down,
                    upSpeed = (up / dt).toLong(), downSpeed = (down / dt).toLong(),
                    directUpBytes = c.directUpBytes + dUp, directDownBytes = c.directDownBytes + dDown,
                    directUpSpeed = (dUp / dt).toLong(), directDownSpeed = (dDown / dt).toLong(),
                    pickName = if (picked.pickName != c.pickName && pick != null) picked.pickName else c.pickName,
                    pickProtocol = if (picked.pickProtocol != c.pickProtocol && pick != null) picked.pickProtocol else c.pickProtocol,
                ) }
                prevT = now
                if (nMode != "off" && ++nTick % nEvery == 0) updateTrafficNotification(nMode)
            }
        }
    }

    private fun startAutoPin(p: PinSetup, entryName: String, gen: Int) {
        val a = AppStore(this).snapshot().adv
        val url = a.pingUrl.ifBlank { AdvancedSettings.DEFAULT_PING_URL }
        val timeout = (a.pingTimeoutSec.coerceIn(5, 10)) * 1000
        val pin = net.antibs.client.engine.AutoPin(p.probes.keys.toList())
        fun alive() = running && isCurrent(gen)
        fun label(tag: String) = net.antibs.client.data.AutoSelect.cleanLabel(p.names[tag] ?: p.protocols[tag]?.let { net.antibs.client.ui.Format.protocolLabel(it) } ?: tag, "Сервер")
        fun sweep(): Map<String, Long> {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            try {
                val fut = p.probes.mapValues { (_, port) -> pool.submit<Long> { net.antibs.client.engine.XrayApi.probe(port, url, timeout) } }
                return fut.mapValues { (_, f) -> try { f.get() } catch (_: Exception) { -1L } }
            } finally { pool.shutdownNow() }
        }
        autoThread = thread(name = "auto-pin", isDaemon = true) {
            try {
                Thread.sleep(800)
                var first = true
                while (alive()) {
                    if (pin.needsSweep) {
                        val r = sweep()
                        if (!alive()) break
                        val ok = r.filterValues { it > 0 }
                        VpnState.log("автовыбор: пинг всех — отвечают ${ok.size}/${r.size}" +
                            (ok.entries.sortedBy { it.value }.take(3).joinToString(prefix = " (", postfix = ")") { "${label(it.key)} ${it.value} мс" }.takeIf { ok.isNotEmpty() } ?: ""))
                        val prev = pin.pinned
                        val next = pin.onSweep(r)
                        if (next != null) {
                            val done = net.antibs.client.engine.XrayApi.overrideBalancer(p.apiPort, p.balancerTag, next)
                            val lbl = label(next)
                            VpnState.log("автовыбор: закреплён «$lbl» [$next] ${r[next]} мс" + if (done) "" else " — API не принял, работает стратегия балансировщика")
                            VpnState.update { it.copy(pickName = lbl, pickProtocol = p.protocols[next]?.let { pr -> net.antibs.client.ui.Format.protocolLabel(pr) }.orEmpty()) }
                            startForegroundCompat("Подключено: ${UiLogic.liveHostTitle(entryName, lbl, true)}")
                            if (!first || prev != null) VpnState.notice("Автовыбор: переключено на ${UiLogic.liveHostTitle(entryName, lbl, true).removeSuffix(" · авто")}")
                        } else if (pin.pinned == null || pin.needsSweep) {
                            VpnState.log("автовыбор: ни один сервер не ответил — повтор через ${net.antibs.client.engine.AutoPin.CHECK_SEC} с")
                        }
                        first = false
                    }
                    Thread.sleep(net.antibs.client.engine.AutoPin.CHECK_SEC * 1000L)
                    if (!alive() || pin.needsSweep) continue
                    val tag = pin.pinned ?: continue
                    var step = pin.onCheck(net.antibs.client.engine.XrayApi.probe(p.probes[tag]!!, url, timeout) > 0)
                    if (step == net.antibs.client.engine.AutoPin.Step.RECHECK && alive()) {
                        VpnState.log("автовыбор: «${label(tag)}» не ответил — повторная проверка")
                        step = pin.onCheck(net.antibs.client.engine.XrayApi.probe(p.probes[tag]!!, url, timeout) > 0)
                    }
                    if (step == net.antibs.client.engine.AutoPin.Step.SWEEP) VpnState.log("автовыбор: «${label(tag)}» недоступен — ищу замену")
                }
            } catch (_: InterruptedException) {}
        }
    }

    private fun stopAll() {
        running = false
        statsThread?.interrupt()
        autoThread?.interrupt(); autoThread = null
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        XrayCore.stop()
        SingBoxEngine.current?.stop(); SingBoxEngine.current = null
        MieruEngine.stopAll()
        net.antibs.client.engine.OlcrtcEngine.stopAll()
        net.antibs.client.engine.ClassicVpnEngine.stopAll()
        MihomoEngine.current?.stop(); MihomoEngine.current = null
        LogTailer.current?.stop(); LogTailer.current = null
        QwdttEngine.current?.stop(); QwdttEngine.current = null
    }

    private fun disconnect() {
        if (!running && VpnState.status.value.state == ConnState.DISCONNECTED) return
        val gen = bumpSession()
        VpnState.update { it.copy(state = ConnState.DISCONNECTING) }
        stopAll()
        if (!isCurrent(gen)) return
        VpnState.set(VpnStatus(ConnState.DISCONNECTED))
        VpnState.log("отключено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnTileService.refresh(this)
    }

    companion object {
        const val ACTION_CONNECT = "net.antibs.client.CONNECT"
        const val ACTION_DISCONNECT = "net.antibs.client.DISCONNECT"
        private const val CHANNEL = "antibs_vpn"
        private const val NOTIF_ID = 1

        fun connect(ctx: Context) {
            val i = Intent(ctx, AntiBSVpnService::class.java).setAction(ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun disconnect(ctx: Context) {
            ctx.startService(Intent(ctx, AntiBSVpnService::class.java).setAction(ACTION_DISCONNECT))
        }

        /** For debugging/tests: serialized selected entry. */
        @Suppress("unused")
        fun entryJson(e: ServerEntry): String = Json.encodeToString(e)

        @Suppress("unused")
        fun wgFromText(t: String): WgConfig? = WgConfig.parse(t)
    }
}
