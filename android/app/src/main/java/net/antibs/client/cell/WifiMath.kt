package net.antibs.client.cell

import kotlin.math.max
import kotlin.math.min

/** 1.0.56: Wi‑Fi channel maths for the analyzer. */
object WifiMath {
    fun channel(freq: Int): Int? = when (freq) {
        2484 -> 14
        in 2412..2472 -> (freq - 2407) / 5
        in 5160..5885 -> (freq - 5000) / 5
        5935 -> 2
        in 5955..7115 -> (freq - 5950) / 5
        else -> null
    }
    fun band(freq: Int): String = when { freq < 3000 -> "2.4 ГГц"; freq < 5925 -> "5 ГГц"; else -> "6 ГГц" }
    /** ScanResult.channelWidth constants → MHz. */
    fun widthMhz(cw: Int): Int = when (cw) { 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320; else -> 20 }
    fun standard(std: Int): String = when (std) {
        1 -> "Legacy (a/b/g)"; 4 -> "Wi‑Fi 4 (n)"; 5 -> "Wi‑Fi 5 (ac)"; 6 -> "Wi‑Fi 6 (ax)"; 7 -> "WiGig (ad)"; 8 -> "Wi‑Fi 7 (be)"; else -> "—"
    }
    fun security(caps: String): String = when {
        "SAE" in caps && "PSK" in caps -> "WPA2/WPA3"
        "SAE" in caps -> "WPA3"
        "OWE" in caps -> "OWE"
        "EAP" in caps -> if ("RSN" in caps) "WPA2-Enterprise" else "WPA-Enterprise"
        "RSN" in caps || "WPA2" in caps -> "WPA2"
        "WPA" in caps -> "WPA"
        "WEP" in caps -> "WEP"
        else -> "Открытая"
    }

    data class Net(val freq: Int, val center: Int, val widthMhz: Int, val rssi: Int)

    /** Interference on a 20 MHz channel at [fc]: each overlapping network weighs (rssi+100)². */
    fun interference(fc: Int, nets: List<Net>): Double = nets.sumOf { n ->
        val c = if (n.center > 0) n.center else n.freq
        val lo = c - n.widthMhz / 2; val hi = c + n.widthMhz / 2
        val ov = min(hi, fc + 10) - max(lo, fc - 10)
        if (ov <= 0) 0.0 else { val w = (n.rssi + 100).coerceAtLeast(0).toDouble(); w * w * ov / 20.0 }
    }

    val CANDIDATES_24 = listOf(1, 6, 11)
    val CANDIDATES_5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165)

    /** Channel → rating 0..10 (10 = free). */
    fun ratings(candidates: List<Int>, freqOf: (Int) -> Int, nets: List<Net>): List<Pair<Int, Double>> {
        val raw = candidates.map { it to interference(freqOf(it), nets) }
        val worst = raw.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: return raw.map { it.first to 10.0 }
        return raw.map { it.first to (10.0 * (1 - it.second / worst) * 0.9 + if (it.second == 0.0) 1.0 else 0.0).coerceIn(0.0, 10.0) }
    }
    fun freq24(ch: Int) = if (ch == 14) 2484 else 2407 + 5 * ch
    fun freq5(ch: Int) = 5000 + 5 * ch
}
