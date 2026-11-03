package net.antibs.client.cell

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

/** 1.0.56: one measured cell (serving or neighbour). Unknown values are null. */
data class CellRow(
    val rat: String,
    val registered: Boolean,
    val plmn: String? = null,
    val tac: Int? = null,
    val ci: Long? = null,
    val pci: Int? = null,
    val channel: Int? = null,
    val band: CellMath.Band? = null,
    val bwKhz: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val rssi: Int? = null,
    val rssiEstimated: Boolean = false,
    val sinr: Double? = null,
    val cqi: Int? = null,
    val ta: Int? = null,
    val taMeters: Int? = null,
    val level: Int? = null,
    val operator: String? = null,
) {
    val nodeId: Long? get() = ci?.let { when (rat) { "LTE" -> CellMath.enb(it); "NR" -> CellMath.gnb(it); "UMTS" -> CellMath.rnc(it); else -> null } }
    val sectorId: Long? get() = ci?.let { when (rat) { "LTE" -> CellMath.sector(it); "NR" -> CellMath.nrCell(it); "UMTS" -> CellMath.umtsCell(it); else -> null } }
    /** main strength for sorting / colouring (RSRP for LTE/NR, RSCP/RSSI otherwise). */
    val strength: Int? get() = rsrp ?: rssi
}

data class SimSnapshot(
    val subId: Int,
    val slot: Int,
    val carrier: String,
    val networkType: String,
    val overrideType: String?,
    val roaming: Boolean,
    val caText: String?,
    val bandwidthsKhz: List<Int>,
    val nrState: String?,
    val duplex: String?,
    val ssChannel: Int?,
    val serving: List<CellRow>,
    val neighbours: List<CellRow>,
    val nsaNr: CellRow?,
    val physChannels: List<String>?,
    val time: Long,
)

/** Collects per-SIM cellular state through public telephony APIs only (no root, no privileged permissions). */
@SuppressLint("MissingPermission")
class CellMonitor(private val ctx: Context) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executor { main.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val sims = MutableStateFlow<List<SimSnapshot>>(emptyList())
    val rateDown = MutableStateFlow(0L) // bit/s
    val rateUp = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    /** subId of the SIM used for mobile data, or null when unknown. */
    val dataSub = MutableStateFlow<Int?>(null)
    private var dataRx: android.content.BroadcastReceiver? = null

    private fun activeDataSubId(): Int = runCatching {
        if (Build.VERSION.SDK_INT < 30) return@runCatching -1
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching -1
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching -1
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return@runCatching -1
        (caps.networkSpecifier as? android.net.TelephonyNetworkSpecifier)?.subscriptionId ?: -1
    }.getOrDefault(-1)

    private fun updateDataSub() {
        val def = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        dataSub.value = DataSimPick.dataSub(subs.map { it.subId }, def, activeDataSubId())
    }

    private class Sub(val subId: Int, val slot: Int, val name: String, val tm: TelephonyManager) {
        var cells: List<CellInfo> = emptyList()
        var ss: ServiceState? = null
        var sig: SignalStrength? = null
        var override: Int = 0
        var displayNet: Int = 0
        var pcc: List<String>? = null
        var phys: List<CellMath.Phys> = emptyList()
        val cleanups = ArrayList<() -> Unit>()
    }
    private val subs = ArrayList<Sub>()

    private fun has(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    fun start() {
        stop()
        val base = app.getSystemService(TelephonyManager::class.java) ?: run { error.value = "Нет модуля телефонии"; return }
        val list = if (has(Manifest.permission.READ_PHONE_STATE)) runCatching {
            app.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
        }.getOrDefault(emptyList()) else emptyList()
        if (list.isEmpty()) subs += Sub(SubscriptionManager.getDefaultSubscriptionId(), 0, base.networkOperatorName.ifBlank { "SIM" }, base)
        else list.sortedBy { it.simSlotIndex }.forEach { si ->
            val tm = base.createForSubscriptionId(si.subscriptionId)
            subs += Sub(si.subscriptionId, si.simSlotIndex, (si.carrierName?.toString() ?: si.displayName?.toString()).orEmpty().ifBlank { tm.networkOperatorName }, tm)
        }
        subs.forEach { listen(it) }
        updateDataSub()
        runCatching {
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) { updateDataSub() }
            }
            ContextCompat.registerReceiver(app, rx, android.content.IntentFilter(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED).apply { addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED") }, ContextCompat.RECEIVER_EXPORTED)
            dataRx = rx
        }
        scope.launch { while (isActive) { subs.forEach { refresh(it) }; delay(2000) } }
        scope.launch {
            var rx = TrafficStats.getMobileRxBytes(); var tx = TrafficStats.getMobileTxBytes(); var t = System.nanoTime()
            while (isActive) {
                delay(1000)
                val r2 = TrafficStats.getMobileRxBytes(); val t2x = TrafficStats.getMobileTxBytes(); val n = System.nanoTime()
                val dt = (n - t) / 1e9
                if (r2 >= 0 && rx >= 0 && dt > 0) { rateDown.value = ((r2 - rx) * 8 / dt).toLong().coerceAtLeast(0); rateUp.value = ((t2x - tx) * 8 / dt).toLong().coerceAtLeast(0) }
                rx = r2; tx = t2x; t = n
            }
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        subs.forEach { s -> s.cleanups.forEach { runCatching { it() } } }
        subs.clear()
        dataRx?.let { r -> runCatching { app.unregisterReceiver(r) } }
        dataRx = null
    }

    private fun refresh(s: Sub) {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) { error.value = "Нужен доступ к точной геолокации"; return }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) s.tm.requestCellInfoUpdate(exec, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) { s.cells = cellInfo; publish() }
            }) else { s.cells = s.tm.allCellInfo.orEmpty(); publish() }
        }.onFailure { runCatching { s.cells = s.tm.allCellInfo.orEmpty(); publish() } }
        if (s.ss == null && has(Manifest.permission.READ_PHONE_STATE)) runCatching { s.ss = s.tm.serviceState }
        if (s.sig == null && Build.VERSION.SDK_INT >= 28) runCatching { s.sig = s.tm.signalStrength }
    }

    private fun listen(s: Sub) {
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener, TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DisplayInfoListener, TelephonyCallback.ServiceStateListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) { s.cells = cellInfo; publish() }
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) { s.sig = signalStrength; publish() }
                override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) { s.override = d.overrideNetworkType; s.displayNet = d.networkType; publish() }
                override fun onServiceStateChanged(serviceState: ServiceState) { s.ss = serviceState; publish() }
            }
            runCatching { s.tm.registerTelephonyCallback(exec, cb); s.cleanups += { s.tm.unregisterTelephonyCallback(cb) } }
                .onFailure { error.value = "Слушатель телефонии: ${it.message}" }
            // PhysicalChannelConfig needs READ_PRECISE_PHONE_STATE (privileged / carrier apps) — try, expect SecurityException.
            val pc = object : TelephonyCallback(), TelephonyCallback.PhysicalChannelConfigListener {
                override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
                    s.phys = configs.map { c -> CellMath.Phys(c.networkType == TelephonyManager.NETWORK_TYPE_NR, c.downlinkChannelNumber.takeIf { it > 0 && it != Int.MAX_VALUE },
                        runCatching { c.band }.getOrNull()?.takeIf { it > 0 }, c.cellBandwidthDownlinkKhz.takeIf { it > 0 }, c.physicalCellId.takeIf { it in 0..1007 },
                        c.connectionStatus == PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING) }
                    s.pcc = configs.map { c ->
                        val st = if (c.connectionStatus == PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING) "PCC" else "SCC"
                        "$st ${TelephonyManager::class.java.let { _ -> netName(c.networkType) }} ch ${c.downlinkChannelNumber} PCI ${c.physicalCellId} ${CellMath.mhz(c.cellBandwidthDownlinkKhz)} МГц"
                    }; publish()
                }
            }
            runCatching { s.tm.registerTelephonyCallback(exec, pc); s.cleanups += { s.tm.unregisterTelephonyCallback(pc) } }
        } else {
            main.post {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("") override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) { s.cells = cellInfo.orEmpty(); publish() }
                    @Deprecated("") override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) { s.sig = signalStrength; publish() }
                    @Deprecated("") override fun onServiceStateChanged(serviceState: ServiceState?) { s.ss = serviceState; publish() }
                    @Deprecated("") override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) {
                        if (Build.VERSION.SDK_INT >= 30) { s.override = d.overrideNetworkType; s.displayNet = d.networkType; publish() }
                    }
                }
                @Suppress("DEPRECATION")
                var ev = PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 30) ev = ev or PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
                runCatching { @Suppress("DEPRECATION") s.tm.listen(l, ev) }.recoverCatching {
                    @Suppress("DEPRECATION") s.tm.listen(l, ev and PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED.inv())
                }
                @Suppress("DEPRECATION")
                s.cleanups += { s.tm.listen(l, PhoneStateListener.LISTEN_NONE) }
            }
        }
    }

    private fun publish() { sims.value = subs.map { snapshot(it) } }

    private fun snapshot(s: Sub): SimSnapshot {
        val rows = s.cells.mapNotNull { row(it) }
        val simPlmn = runCatching { s.tm.simOperator }.getOrNull()
        val reg = rows.filter { it.registered }
        val mine = reg.filter { simPlmn.isNullOrBlank() || it.plmn == null || it.plmn == simPlmn }.ifEmpty { reg }
        val ss = s.ss
        val bws = if (Build.VERSION.SDK_INT >= 28) runCatching { ss?.cellBandwidths?.toList() }.getOrNull().orEmpty() else emptyList()
        val lteCa = s.override == 1 /* LTE_CA */
        val nrState = ss?.let { nrStateOf(it) }
        val net = runCatching { if (has(Manifest.permission.READ_PHONE_STATE)) s.tm.dataNetworkType else s.displayNet }.getOrDefault(s.displayNet)
        val sigNr = if (Build.VERSION.SDK_INT >= 29) s.sig?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.firstOrNull() else null
        val nsa = if (sigNr != null && mine.none { it.rat == "NR" }) CellRow("NR", true, rsrp = sigNr.ssRsrp.ok(), rsrq = sigNr.ssRsrq.ok(),
            sinr = CellMath.pickSinr(sigNr.ssSinr.ok()?.takeIf { it in -23..40 }?.toDouble(), if (Build.VERSION.SDK_INT >= 29) sigNr.csiSinr.ok()?.takeIf { it in -23..23 }?.toDouble() else null), level = sigNr.level) else null
        // merge signal from SignalStrength into the serving LTE row when CellInfo lacks it
        val sigLte = if (Build.VERSION.SDK_INT >= 29) s.sig?.getCellSignalStrengths(CellSignalStrengthLte::class.java)?.firstOrNull() else null
        val serving = mine.mapIndexed { i, r -> if (i == 0 && r.rat == "LTE") mergeLte(r, s.sig, sigLte, bws) else r }
        return SimSnapshot(
            subId = s.subId, slot = s.slot, carrier = s.name,
            networkType = netName(net), overrideType = overrideName(s.override),
            roaming = runCatching { ss?.roaming ?: s.tm.isNetworkRoaming }.getOrDefault(false),
            caText = if (serving.any { it.rat == "LTE" || it.rat == "NR" }) CellMath.caBandsSummary(CellMath.carriers(bws, serving.firstOrNull()?.band?.band,
                s.phys.ifEmpty { CellMath.parsePhys(ss?.toString()) }, (rows - mine.toSet()).filter { it.rat == "LTE" && it.band != null && it.plmn.let { p -> p == null || simPlmn.isNullOrBlank() || p == simPlmn } }.sortedByDescending { it.rsrp ?: -200 }.map { it.band!!.band }), lteCa) else null,
            bandwidthsKhz = bws, nrState = nrState,
            duplex = if (Build.VERSION.SDK_INT >= 28) when (runCatching { ss?.duplexMode }.getOrNull()) { 1 -> "FDD"; 2 -> "TDD"; else -> null } else null,
            ssChannel = if (Build.VERSION.SDK_INT >= 28) runCatching { ss?.channelNumber }.getOrNull()?.takeIf { it in 0 until Int.MAX_VALUE } else null,
            serving = serving, neighbours = rows - mine.toSet(), nsaNr = nsa, physChannels = s.pcc, time = System.currentTimeMillis(),
        )
    }

    companion object {
        /** Serving LTE: take best valid value across CellInfoLte, SignalStrength (same subId), legacy getters and toString. */
        private fun mergeLte(r: CellRow, sig: SignalStrength?, sl: CellSignalStrengthLte?, bws: List<Int>): CellRow {
            fun refl(n: String): Int? = sig?.let { o -> runCatching { o.javaClass.getMethod(n).invoke(o) as Int }.getOrNull() }
            val txt = (sl?.toString() ?: "") + " " + (sig?.toString() ?: "")
            val rsrp = CellMath.validRsrp(r.rsrp) ?: CellMath.validRsrp(sl?.rsrp) ?: CellMath.validRsrp(refl("getLteRsrp")) ?: CellMath.validRsrp(CellMath.parseField(txt, "rsrp"))
            val rsrq = CellMath.validRsrq(r.rsrq) ?: CellMath.validRsrq(sl?.rsrq) ?: CellMath.validRsrq(refl("getLteRsrq")) ?: CellMath.validRsrq(CellMath.parseField(txt, "rsrq"))
            val sinr = CellMath.pickSinr(r.sinr, CellMath.sinrFromRssnr(sl?.rssnr), CellMath.sinrFromRssnr(refl("getLteRssnr")), CellMath.sinrFromRssnr(CellMath.parseField(txt, "rssnr")))
            val bw = r.bwKhz ?: bws.firstOrNull()?.takeIf { it > 0 }
            var rssi = CellMath.validRssi(r.rssi) ?: (if (Build.VERSION.SDK_INT >= 29) CellMath.validRssi(sl?.rssi) else null)
                ?: CellMath.validRssi(refl("getLteSignalStrength")?.let { if (it in 0..31) -113 + 2 * it else it }) ?: CellMath.validRssi(CellMath.parseField(txt, "rssi"))
            var est = false
            if (rssi == null) { rssi = CellMath.validRssi(CellMath.rssiEstimate(rsrp, rsrq, bw)); est = rssi != null }
            return r.copy(rsrp = rsrp, rsrq = rsrq, sinr = sinr, rssi = rssi, rssiEstimated = est,
                cqi = CellMath.validCqi(r.cqi) ?: CellMath.validCqi(sl?.cqi) ?: CellMath.validCqi(CellMath.parseField(txt, "cqi")), bwKhz = bw)
        }
        private fun Int.ok(): Int? = takeIf { it != CellInfo.UNAVAILABLE && it != Int.MIN_VALUE }
        private fun Long.okL(): Long? = takeIf { it != CellInfo.UNAVAILABLE_LONG && it != Long.MAX_VALUE && it >= 0 }

        fun nrStateOf(ss: ServiceState): String? {
            runCatching {
                val v = ServiceState::class.java.getMethod("getNrState").invoke(ss) as Int
                return when (v) { 1 -> "RESTRICTED"; 2 -> "NOT_RESTRICTED"; 3 -> "CONNECTED"; else -> "NONE" }
            }
            return Regex("nrState=([A-Z_]+)").find(ss.toString())?.groupValues?.get(1)
        }

        fun overrideName(t: Int): String? = when (t) { 1 -> "LTE_CA"; 2 -> "LTE_ADVANCED_PRO"; 3 -> "NR_NSA"; 4 -> "NR_NSA_MMWAVE"; 5 -> "NR_ADVANCED"; else -> null }

        fun netName(t: Int): String = when (t) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"; TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"; TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"; TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"; TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"; TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"; TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"; 19 -> "LTE-A"; TelephonyManager.NETWORK_TYPE_NR -> "NR"; TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"; TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO"
            0 -> "—"; else -> "тип $t"
        }

        fun row(c: CellInfo): CellRow? = runCatching {
            when (c) {
                is CellInfoLte -> {
                    val id = c.cellIdentity; val sg = c.cellSignalStrength
                    val ch = id.earfcn.ok()
                    CellRow("LTE", c.isRegistered, plmn = plmn(id.mccString, id.mncString), tac = id.tac.ok(), ci = id.ci.ok()?.toLong(), pci = id.pci.ok(),
                        channel = ch, band = CellMath.band("LTE", ch), bwKhz = if (Build.VERSION.SDK_INT >= 28) id.bandwidth.ok()?.takeIf { it > 0 } else null,
                        rsrp = sg.rsrp.ok(), rsrq = sg.rsrq.ok(), rssi = if (Build.VERSION.SDK_INT >= 29) CellMath.validRssi(sg.rssi.ok()) else null,
                        sinr = CellMath.sinrFromRssnr(sg.rssnr), cqi = CellMath.validCqi(sg.cqi), ta = sg.timingAdvance.ok(),
                        taMeters = sg.timingAdvance.ok()?.let(CellMath::lteTaMeters), level = sg.level,
                        operator = if (Build.VERSION.SDK_INT >= 28) id.operatorAlphaShort?.toString() else null)
                }
                is CellInfoGsm -> {
                    val id = c.cellIdentity; val sg = c.cellSignalStrength; val ch = id.arfcn.ok()
                    CellRow("GSM", c.isRegistered, plmn = plmn(id.mccString, id.mncString), tac = id.lac.ok(), ci = id.cid.ok()?.toLong(), pci = id.bsic.ok(),
                        channel = ch, band = CellMath.band("GSM", ch), rssi = sg.dbm.ok(), ta = sg.timingAdvance.ok(),
                        taMeters = sg.timingAdvance.ok()?.let(CellMath::gsmTaMeters), level = sg.level,
                        operator = if (Build.VERSION.SDK_INT >= 28) id.operatorAlphaShort?.toString() else null)
                }
                is CellInfoWcdma -> {
                    val id = c.cellIdentity; val sg = c.cellSignalStrength; val ch = id.uarfcn.ok()
                    CellRow("UMTS", c.isRegistered, plmn = plmn(id.mccString, id.mncString), tac = id.lac.ok(), ci = id.cid.ok()?.toLong(), pci = id.psc.ok(),
                        channel = ch, band = CellMath.band("UMTS", ch), rssi = sg.dbm.ok(),
                        rsrq = if (Build.VERSION.SDK_INT >= 30) sg.ecNo.ok() else null, level = sg.level,
                        operator = if (Build.VERSION.SDK_INT >= 28) id.operatorAlphaShort?.toString() else null)
                }
                else -> if (Build.VERSION.SDK_INT >= 29 && c is CellInfoNr) {
                    val id = c.cellIdentity as CellIdentityNr; val sg = c.cellSignalStrength as CellSignalStrengthNr
                    val ch = id.nrarfcn.ok()
                    val hint = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull() else null
                    CellRow("NR", c.isRegistered, plmn = plmn(id.mccString, id.mncString), tac = id.tac.ok(), ci = id.nci.okL(), pci = id.pci.ok(),
                        channel = ch, band = CellMath.band("NR", ch, hint), rsrp = sg.ssRsrp.ok(), rsrq = sg.ssRsrq.ok(), sinr = CellMath.pickSinr(sg.ssSinr.ok()?.takeIf { it in -23..40 }?.toDouble(), sg.csiSinr.ok()?.takeIf { it in -23..23 }?.toDouble()),
                        cqi = if (Build.VERSION.SDK_INT >= 31) sg.csiCqiReport.firstOrNull() else null,
                        ta = if (Build.VERSION.SDK_INT >= 34) sg.timingAdvanceMicros.ok() else null, level = sg.level,
                        operator = id.operatorAlphaShort?.toString())
                } else null
            }
        }.getOrNull()

        private fun plmn(mcc: String?, mnc: String?) = if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) null else mcc + mnc
    }
}
