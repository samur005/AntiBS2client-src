package net.antibs.client.cell

import kotlin.math.roundToInt

/** 1.0.56: pure 3GPP helpers — channel numbers → band / DL / UL MHz, cell id splits, TA, quality grades. */
object CellMath {
    data class Band(
        val rat: String, val band: String, val name: String, val dlMhz: Double, val ulMhz: Double?, val duplex: String,
    ) {
        val label: String get() = "$band: $name"
    }

    // ── LTE (TS 36.101 table 5.7.3-1): band, F_DL_low, N_Offs-DL, N_DL_max, F_UL_low, N_Offs-UL, name ──
    private class LteRow(val b: Int, val fdl: Double, val noff: Int, val nmax: Int, val ful: Double?, val noffUl: Int, val name: String)
    private val LTE = listOf(
        LteRow(1, 2110.0, 0, 599, 1920.0, 18000, "2100 IMT"),
        LteRow(2, 1930.0, 600, 1199, 1850.0, 18600, "1900 PCS"),
        LteRow(3, 1805.0, 1200, 1949, 1710.0, 19200, "1800+ DCS"),
        LteRow(4, 2110.0, 1950, 2399, 1710.0, 19950, "AWS-1"),
        LteRow(5, 869.0, 2400, 2649, 824.0, 20400, "850 CLR"),
        LteRow(6, 875.0, 2650, 2749, 830.0, 20650, "850 UMTS"),
        LteRow(7, 2620.0, 2750, 3449, 2500.0, 20750, "2600 IMT-E"),
        LteRow(8, 925.0, 3450, 3799, 880.0, 21450, "900 E-GSM"),
        LteRow(9, 1844.9, 3800, 4149, 1749.9, 21800, "1800 Japan"),
        LteRow(10, 2110.0, 4150, 4749, 1710.0, 22150, "AWS"),
        LteRow(11, 1475.9, 4750, 4949, 1427.9, 22750, "1500 Lower"),
        LteRow(12, 729.0, 5010, 5179, 699.0, 23010, "700 a"),
        LteRow(13, 746.0, 5180, 5279, 777.0, 23180, "700 c"),
        LteRow(14, 758.0, 5280, 5379, 788.0, 23280, "700 PS"),
        LteRow(17, 734.0, 5730, 5849, 704.0, 23730, "700 b"),
        LteRow(18, 860.0, 5850, 5999, 815.0, 23850, "800 Lower"),
        LteRow(19, 875.0, 6000, 6149, 830.0, 24000, "800 Upper"),
        LteRow(20, 791.0, 6150, 6449, 832.0, 24150, "800 DD"),
        LteRow(21, 1495.9, 6450, 6599, 1447.9, 24450, "1500 Upper"),
        LteRow(22, 3510.0, 6600, 7399, 3410.0, 24600, "3500"),
        LteRow(24, 1525.0, 7700, 8039, 1626.5, 25700, "1600 L"),
        LteRow(25, 1930.0, 8040, 8689, 1850.0, 26040, "1900+"),
        LteRow(26, 859.0, 8690, 9039, 814.0, 26690, "850+"),
        LteRow(27, 852.0, 9040, 9209, 807.0, 27040, "800 SMR"),
        LteRow(28, 758.0, 9210, 9659, 703.0, 27210, "700 APT"),
        LteRow(29, 717.0, 9660, 9769, null, 0, "700 d SDL"),
        LteRow(30, 2350.0, 9770, 9869, 2305.0, 27660, "2300 WCS"),
        LteRow(31, 462.5, 9870, 9919, 452.5, 27760, "450"),
        LteRow(32, 1452.0, 9920, 10359, null, 0, "1500 L-band SDL"),
        LteRow(33, 1900.0, 36000, 36199, 1900.0, 36000, "TD 1900"),
        LteRow(34, 2010.0, 36200, 36349, 2010.0, 36200, "TD 2000"),
        LteRow(35, 1850.0, 36350, 36949, 1850.0, 36350, "TD PCS Lower"),
        LteRow(36, 1930.0, 36950, 37549, 1930.0, 36950, "TD PCS Upper"),
        LteRow(37, 1910.0, 37550, 37749, 1910.0, 37550, "TD PCS Center"),
        LteRow(38, 2570.0, 37750, 38249, 2570.0, 37750, "TD 2600"),
        LteRow(39, 1880.0, 38250, 38649, 1880.0, 38250, "TD 1900+"),
        LteRow(40, 2300.0, 38650, 39649, 2300.0, 38650, "TD 2300"),
        LteRow(41, 2496.0, 39650, 41589, 2496.0, 39650, "TD 2500"),
        LteRow(42, 3400.0, 41590, 43589, 3400.0, 41590, "TD 3500"),
        LteRow(43, 3600.0, 43590, 45589, 3600.0, 43590, "TD 3700"),
        LteRow(44, 703.0, 45590, 46589, 703.0, 45590, "TD 700"),
        LteRow(45, 1447.0, 46590, 46789, 1447.0, 46590, "TD 1500"),
        LteRow(46, 5150.0, 46790, 54539, 5150.0, 46790, "TD 5 ГГц LAA"),
        LteRow(47, 5855.0, 54540, 55239, 5855.0, 54540, "TD 5.9 ГГц V2X"),
        LteRow(48, 3550.0, 55240, 56739, 3550.0, 55240, "TD 3600 CBRS"),
        LteRow(65, 2110.0, 65536, 66435, 1920.0, 131072, "2100+"),
        LteRow(66, 2110.0, 66436, 67335, 1710.0, 131972, "AWS-3"),
        LteRow(67, 738.0, 67336, 67535, null, 0, "700 EU SDL"),
        LteRow(68, 753.0, 67536, 67835, 698.0, 132672, "700 ME"),
        LteRow(69, 2570.0, 67836, 68335, null, 0, "2600 SDL"),
        LteRow(70, 1995.0, 68336, 68585, 1695.0, 132972, "AWS-4"),
        LteRow(71, 617.0, 68586, 68935, 663.0, 133122, "600"),
    )
    private val TDD_LTE = 33..48

    fun lteBandNumber(earfcn: Int): Int? = LTE.firstOrNull { earfcn in it.noff..it.nmax }?.b

    fun lte(earfcn: Int): Band? {
        val r = LTE.firstOrNull { earfcn in r(it) } ?: return null
        val dl = r.fdl + 0.1 * (earfcn - r.noff)
        val tdd = r.b in TDD_LTE
        val ul = when { tdd -> dl; r.ful == null -> null; else -> r.ful + 0.1 * (earfcn - r.noff) }
        return Band("LTE", "B${r.b}", r.name, round1(dl), ul?.let(::round1), if (tdd) "TDD" else if (r.ful == null) "SDL" else "FDD")
    }
    private fun r(it: LteRow) = it.noff..it.nmax

    // ── NR (TS 38.104 5.4.2.1 global raster) ──
    fun nrFreqMhz(nrarfcn: Int): Double? = when (nrarfcn) {
        in 0 until 600000 -> 0.005 * nrarfcn
        in 600000..2016666 -> 3000.0 + 0.015 * (nrarfcn - 600000)
        in 2016667..3279165 -> 24250.08 + 0.06 * (nrarfcn - 2016667)
        else -> null
    }

    private class NrRow(val n: Int, val dl: ClosedFloatingPointRange<Double>, val ulLow: Double?, val duplex: String, val name: String)
    private val NR = listOf(
        NrRow(1, 2110.0..2170.0, 1920.0, "FDD", "2100"),
        NrRow(2, 1930.0..1990.0, 1850.0, "FDD", "1900 PCS"),
        NrRow(3, 1805.0..1880.0, 1710.0, "FDD", "1800"),
        NrRow(5, 869.0..894.0, 824.0, "FDD", "850"),
        NrRow(7, 2620.0..2690.0, 2500.0, "FDD", "2600"),
        NrRow(8, 925.0..960.0, 880.0, "FDD", "900"),
        NrRow(20, 791.0..821.0, 832.0, "FDD", "800 DD"),
        NrRow(28, 758.0..803.0, 703.0, "FDD", "700 APT"),
        NrRow(66, 2110.0..2200.0, 1710.0, "FDD", "AWS-3"),
        NrRow(71, 617.0..652.0, 663.0, "FDD", "600"),
        NrRow(38, 2570.0..2620.0, null, "TDD", "TD 2600"),
        NrRow(40, 2300.0..2400.0, null, "TDD", "TD 2300"),
        NrRow(41, 2496.0..2690.0, null, "TDD", "TD 2500"),
        NrRow(78, 3300.0..3800.0, null, "TDD", "3500 C-band"),
        NrRow(77, 3300.0..4200.0, null, "TDD", "3700 C-band"),
        NrRow(79, 4400.0..5000.0, null, "TDD", "4700"),
        NrRow(258, 24250.0..27500.0, null, "TDD", "26 ГГц"),
        NrRow(257, 26500.0..29500.0, null, "TDD", "28 ГГц"),
        NrRow(260, 37000.0..40000.0, null, "TDD", "39 ГГц"),
    )

    /** [bandHint] = band reported by CellIdentityNr.getBands() (API30), preferred when the frequency fits. */
    fun nr(nrarfcn: Int, bandHint: Int? = null): Band? {
        val f = nrFreqMhz(nrarfcn) ?: return null
        val row = NR.firstOrNull { it.n == bandHint && f in it.dl } ?: NR.firstOrNull { f in it.dl } ?: return null
        val ul = row.ulLow?.let { it + (f - row.dl.start) } ?: f
        return Band("NR", "n${row.n}", row.name, round1(f), round1(ul), row.duplex)
    }

    // ── UMTS (TS 25.101 5.4.3) ──
    private class UmtsRow(val b: Int, val n: IntRange, val dlOff: Double, val dup: Double, val name: String)
    private val UMTS = listOf(
        UmtsRow(1, 10562..10838, 0.0, 190.0, "2100 IMT"),
        UmtsRow(2, 9662..9938, 0.0, 80.0, "1900 PCS"),
        UmtsRow(3, 1162..1513, 1575.0, 95.0, "1800 DCS"),
        UmtsRow(4, 1537..1738, 1805.0, 400.0, "AWS"),
        UmtsRow(5, 4357..4458, 0.0, 45.0, "850"),
        UmtsRow(8, 2937..3088, 340.0, 45.0, "900"),
    )
    fun umts(uarfcn: Int): Band? {
        val r = UMTS.firstOrNull { uarfcn in it.n } ?: return null
        val dl = r.dlOff + uarfcn / 5.0
        return Band("UMTS", "B${r.b}", r.name, round1(dl), round1(dl - r.dup), "FDD")
    }

    // ── GSM (TS 45.005 2) — 512..810 assumed DCS 1800 (PCS 1900 shares the numbers) ──
    fun gsm(arfcn: Int): Band? {
        val (name, ul, dup) = when (arfcn) {
            in 0..124 -> Triple("900 P-GSM", 890.0 + 0.2 * arfcn, 45.0)
            in 975..1023 -> Triple("900 E-GSM", 890.0 + 0.2 * (arfcn - 1024), 45.0)
            in 128..251 -> Triple("850", 824.2 + 0.2 * (arfcn - 128), 45.0)
            in 512..885 -> Triple("1800 DCS", 1710.2 + 0.2 * (arfcn - 512), 95.0)
            else -> return null
        }
        val b = when (arfcn) { in 128..251 -> "GSM850"; in 512..885 -> "DCS1800"; else -> "GSM900" }
        return Band("GSM", b, name, round1(ul + dup), round1(ul), "FDD")
    }

    fun band(rat: String, ch: Int?, hint: Int? = null): Band? = if (ch == null || ch < 0 || ch == Int.MAX_VALUE) null else when (rat) {
        "LTE" -> lte(ch); "NR" -> nr(ch, hint); "UMTS", "WCDMA", "TDSCDMA" -> umts(ch); "GSM" -> gsm(ch); else -> null
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    // ── cell id splits ──
    fun enb(ci: Long) = ci ushr 8
    fun sector(ci: Long) = ci and 0xFF
    /** NR: NCI = gNB id (22..32 bits) + cell id; [gnbBits] 24 is the common operator choice. */
    fun gnb(nci: Long, gnbBits: Int = 24) = nci ushr (36 - gnbBits)
    fun nrCell(nci: Long, gnbBits: Int = 24) = nci and ((1L shl (36 - gnbBits)) - 1)
    fun rnc(cid: Long) = cid ushr 16
    fun umtsCell(cid: Long) = cid and 0xFFFF

    /** LTE TA step = 16·Ts·c/2 ≈ 78.12 m; GSM TA step ≈ 553.5 m. */
    fun lteTaMeters(ta: Int): Int = (ta * 78.12).roundToInt()
    fun gsmTaMeters(ta: Int): Int = (ta * 553.5).roundToInt()

    // ── quality grades 0 (bad) .. 4 (excellent) ──
    fun gradeRsrp(v: Int) = when { v >= -80 -> 4; v >= -90 -> 3; v >= -100 -> 2; v >= -110 -> 1; else -> 0 }
    fun gradeSinr(v: Double) = when { v >= 20 -> 4; v >= 13 -> 3; v >= 5 -> 2; v >= 0 -> 1; else -> 0 }
    fun gradeRsrq(v: Int) = when { v >= -10 -> 4; v >= -15 -> 3; v >= -20 -> 2; else -> 1 }
    fun gradeRssi(v: Int) = when { v >= -65 -> 4; v >= -75 -> 3; v >= -85 -> 2; v >= -95 -> 1; else -> 0 }

    fun mhz(khz: Int): String = if (khz % 1000 == 0) (khz / 1000).toString() else "%.1f".format(java.util.Locale.US, khz / 1000.0)

    /** «2xCA, 15+5 МГц». Only bandwidths > 0 count as component carriers. */
    fun caSummary(bandwidthsKhz: IntArray?, overrideLteCa: Boolean): String? {
        val bw = bandwidthsKhz?.filter { it > 0 }.orEmpty()
        return when {
            bw.size >= 2 -> "${bw.size}xCA, ${bw.joinToString("+") { mhz(it) }} МГц"
            overrideLteCa && bw.size == 1 -> "CA, ${mhz(bw[0])} МГц (+ доп. несущие, ширина не сообщается)"
            overrideLteCa -> "CA (число несущих не сообщается)"
            bw.size == 1 -> "без CA, ${mhz(bw[0])} МГц"
            else -> null
        }
    }

    fun ratName(rat: String) = when (rat) { "NR" -> "5G NR"; "LTE" -> "LTE"; "UMTS", "WCDMA" -> "3G UMTS"; "TDSCDMA" -> "3G TD-SCDMA"; "GSM" -> "2G GSM"; "CDMA" -> "CDMA"; else -> rat }

    // ---- LTE signal validity / fallbacks (1.0.57) ----
    private const val UNAV = Int.MAX_VALUE
    /** rssnr → SINR dB. API29+ reports dB (-20..30); older modems 0.1 dB (-200..300). Invalid → null. */
    fun sinrFromRssnr(raw: Int?): Double? {
        if (raw == null || raw == UNAV || raw == Int.MIN_VALUE) return null
        return when (raw) { in -20..30 -> raw.toDouble(); in -200..300 -> raw / 10.0; else -> null }
    }
    fun validRssi(v: Int?): Int? = v?.takeIf { it in -120..-25 }
    fun validRsrp(v: Int?): Int? = v?.takeIf { it in -156..-31 }
    fun validRsrq(v: Int?): Int? = v?.takeIf { it in -34..3 }
    fun validCqi(v: Int?): Int? = v?.takeIf { it in 1..15 }
    /** first non-null, preferring non-zero (raw 0 is often "unfilled"); all zero → null. */
    fun pickSinr(vararg c: Double?): Double? = c.firstOrNull { it != null && it != 0.0 }
    fun nRb(bwKhz: Int?): Int? = when (bwKhz) { 1400 -> 6; 3000 -> 15; 5000 -> 25; 10000 -> 50; 15000 -> 75; 20000 -> 100; else -> null }
    /** RSRQ = N·RSRP/RSSI ⇒ RSSI = RSRP − RSRQ + 10·log10(N_RB). */
    fun rssiEstimate(rsrp: Int?, rsrq: Int?, bwKhz: Int?): Int? {
        val r = validRsrp(rsrp) ?: return null; val q = validRsrq(rsrq) ?: return null; val n = nRb(bwKhz) ?: return null
        return Math.round(r - q + 10 * Math.log10(n.toDouble())).toInt()
    }
    /** last-resort parse of SignalStrength/CellSignalStrengthLte.toString(): "rssnr=110", "rssi=-103". */
    fun parseField(text: String?, name: String): Int? =
        text?.let { Regex("\\b" + name + "=(-?\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it != UNAV && it != Int.MIN_VALUE }

    // ---- per-carrier CA bands (1.0.58) ----
    data class Phys(val nr: Boolean, val channel: Int?, val bandNum: Int?, val bwKhz: Int?, val pci: Int?, val primary: Boolean?)
    data class Carrier(val band: String?, val bwKhz: Int?, val guessed: Boolean = false)

    /** "B3" / "n78" from a PhysicalChannelConfig-like entry (band number wins, else derived from channel). */
    fun physBand(p: Phys): String? = p.bandNum?.takeIf { it in 1..512 }?.let { if (p.nr) "n$it" else "B$it" }
        ?: p.channel?.let { if (p.nr) nr(it)?.band else lte(it)?.band }

    /** Parse PhysicalChannelConfig entries that some OEMs put into ServiceState/callback toString(). */
    fun parsePhys(text: String?): List<Phys> {
        if (text.isNullOrBlank()) return emptyList()
        val parts = text.split(Regex("(?=PhysicalChannelConfig\\s*[:{])|(?=\\{mConnectionStatus)")).filter { "mDownlinkChannelNumber" in it || "mCellBandwidthDownlinkKhz" in it || "mBand=" in it }
        return parts.map { t ->
            val st = Regex("mConnectionStatus=(\\w+)").find(t)?.groupValues?.get(1)
            val rat = Regex("mNetworkType=(\\w+)").find(t)?.groupValues?.get(1).orEmpty()
            Phys(nr = rat.equals("NR", true) || rat == "20", channel = parseField(t, "mDownlinkChannelNumber"), bandNum = parseField(t, "mBand"),
                bwKhz = parseField(t, "mCellBandwidthDownlinkKhz")?.takeIf { it > 0 }, pci = parseField(t, "mPhysicalCellId"),
                primary = st?.let { it.contains("Primary", true) || it == "1" })
        }
    }

    /** PCC first; SCC bands from phys configs, else guessed from neighbour bands (marked), else unknown. */
    fun carriers(bws: List<Int>, pccBand: String?, phys: List<Phys>, neighbourBands: List<String>): List<Carrier> {
        val ph = phys.filter { it.bwKhz != null || it.channel != null }.sortedBy { if (it.primary == true) 0 else 1 }
        if (ph.size >= 2) return ph.map { Carrier(physBand(it), it.bwKhz) }
        val bw = bws.filter { it > 0 }
        if (bw.isEmpty()) return emptyList()
        val cands = neighbourBands.distinct().filter { it != pccBand }.toMutableList()
        return bw.mapIndexed { i, w -> if (i == 0) Carrier(pccBand, w) else cands.removeFirstOrNull()?.let { Carrier(it, w, true) } ?: Carrier(null, w) }
    }

    /** «2xCA: B3+B7, 15+5 МГц»; guessed band → «B7?», unknown → «?». */
    fun caBandsSummary(c: List<Carrier>, overrideLteCa: Boolean): String? {
        if (c.size < 2) {
            // 2.0.5: always name the primary band, with or without CA
            val b = c.firstOrNull()?.band
            val base = caSummary(c.mapNotNull { it.bwKhz }.toIntArray(), overrideLteCa) ?: return b?.let { "без CA, $it" }
            return when {
                base.startsWith("без CA") -> if (b != null) base.replaceFirst("без CA", "без CA, $b") else base
                base.startsWith("CA") -> base.replaceFirst("CA", "CA: ${b ?: "?"}+?")
                else -> base
            }
        }
        val bands = c.joinToString("+") { (it.band ?: "?") + if (it.guessed && it.band != null) "?" else "" }
        val bw = c.mapNotNull { it.bwKhz }.takeIf { it.size == c.size }?.joinToString("+") { mhz(it) }
        return "${c.size}xCA: $bands" + (bw?.let { ", $it МГц" } ?: "")
    }
}
