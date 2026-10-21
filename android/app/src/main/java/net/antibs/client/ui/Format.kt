package net.antibs.client.ui

import net.antibs.client.data.LinkParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(v: Long): String = when {
        v < 1024 -> "$v Б"
        v < 1024L * 1024 -> "%.1f КБ".format(v / 1024.0)
        v < 1024L * 1024 * 1024 -> "%.1f МБ".format(v / 1048576.0)
        else -> "%.2f ГБ".format(v / 1073741824.0)
    }

    fun speed(v: Long): String = bytes(v) + "/с"

    // ---- 1.0.13: fixed-format values for the speed card (static column widths) ----
    private val FIXED_UNITS = listOf("КБ", "МБ", "ГБ", "ТБ")

    /** Always one decimal, number ≤ 999.9, unit КБ…ТБ: "0.0 КБ", "12.3 МБ", "999.9 МБ", "1.0 ГБ". */
    fun bytesFixed(v: Long): String {
        var x = v.coerceAtLeast(0) / 1024.0
        var i = 0
        while (x >= 999.95 && i < FIXED_UNITS.lastIndex) { x /= 1024.0; i++ }
        return String.format(Locale.US, "%.1f %s", x.coerceAtMost(999.9), FIXED_UNITS[i])
    }

    fun speedFixed(v: Long): String = bytesFixed(v) + "/с"

    // ---- 1.0.55: «Единицы скорости» = Биты: decimal bits per second, same fixed shape ("0.0 кбит/с" … "999.9 Тбит/с") ----
    private val BIT_UNITS = listOf("кбит/с", "Мбит/с", "Гбит/с", "Тбит/с")
    fun speedFixedBits(bytesPerSec: Long): String {
        var x = bytesPerSec.coerceAtLeast(0).toDouble() * 8.0 / 1000.0
        var i = 0
        while (x >= 999.95 && i < BIT_UNITS.lastIndex) { x /= 1000.0; i++ }
        return String.format(Locale.US, "%.1f %s", x.coerceAtMost(999.9), BIT_UNITS[i])
    }
    const val SPEED_TEMPLATE_BITS = "999.9 Мбит/с"
    fun speedFixed(v: Long, bits: Boolean): String = if (bits) speedFixedBits(v) else speedFixed(v)
    fun speedTemplate(bits: Boolean): String = if (bits) SPEED_TEMPLATE_BITS else SPEED_TEMPLATE

    /** Widest strings [speedFixed] / [bytesFixed] can produce (М is the widest unit letter) — sizes the columns. */
    const val SPEED_TEMPLATE = "999.9 МБ/с"
    const val TOTAL_TEMPLATE = "999.9 МБ"

    fun mb(v: Double): String = when {
        v <= 0 -> "0"
        v >= 1024 -> "%.1f ГБ".format(v / 1024.0)
        else -> "%.1f МБ".format(v)
    }

    fun date(epochSec: Long): String =
        if (epochSec <= 0) "—" else SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(epochSec * 1000))

    fun dateLong(epochSec: Long): String =
        if (epochSec <= 0) "—" else SimpleDateFormat("d MMM yyyy 'г.'", Locale("ru")).format(Date(epochSec * 1000))

    fun dateTime(epochMs: Long): String =
        if (epochMs <= 0) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(epochMs))

    fun timer(since: Long): String {
        if (since <= 0) return "00:00:00"
        val s = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun flag(name: String): String = LinkParser.flagOf(name) ?: "🌐"

    fun protocolLabel(p: String): String = when (p) {
        "vless" -> "VLESS"; "vmess" -> "VMess"; "shadowsocks" -> "Shadowsocks"; "trojan" -> "Trojan"
        "wireguard" -> "WireGuard"; "hysteria" -> "Hysteria2"; "hysteria1" -> "Hysteria"
        "socks" -> "SOCKS5"; "http" -> "HTTP"; "https" -> "HTTPS"; "http3" -> "HTTP/3"
        "tuic" -> "TUIC"; "anytls" -> "AnyTLS"
        "ssh" -> "SSH"; "snell" -> "Snell"; "naive" -> "Naive"; "mieru" -> "Mieru"
        "socks4" -> "SOCKS4"; "ssr" -> "ShadowsocksR"; "trusttunnel" -> "TrustTunnel"; "shadowquic" -> "ShadowQUIC"
        "amneziawg" -> "AmneziaWG"; "qwdtt" -> "qWDTT"; "amneziakey" -> "Amnezia (vpn://)"
        "olcrtc" -> "olcRTC"; "openvpn" -> "OpenVPN"; "openconnect" -> "OpenConnect"
        "chain" -> "Цепь"; "balancer" -> "Балансировщик"; "custom" -> "JSON"
        else -> p.uppercase()
    }
}
