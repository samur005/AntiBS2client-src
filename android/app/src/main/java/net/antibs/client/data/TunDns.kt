package net.antibs.client.data

/**
 * 2.0.43: DNS servers announced on the VPN interface. Hard-coded 1.1.1.1 / 8.8.8.8 differ from the mobile / Wi-Fi
 * network's own resolvers, which detectors flag («DNS заменён при активном VPN»). The tunnel now announces the
 * underlying network's resolvers instead. Queries still enter the tunnel (default route) and Xray's port-53 rule
 * hands them to its own DNS ("dns-out"), so nothing is resolved outside the VPN because of this.
 */
object TunDns {
    val FALLBACK = listOf("1.1.1.1", "8.8.8.8")
    private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val V6 = Regex("""^[0-9A-Fa-f:.]+$""")

    /** Usable addresses from [found] (literal IPv4 / global IPv6, no loopback / link-local / any), max 4; else [FALLBACK]. */
    fun forTun(found: List<String>): List<String> {
        val ok = found.map { it.trim().substringBefore('%') }.filter { a ->
            val m = V4.matchEntire(a)
            when {
                m != null -> m.groupValues.drop(1).all { it.toInt() in 0..255 } && !a.startsWith("127.") && !a.startsWith("169.254.") && a != "0.0.0.0"
                a.contains(':') && V6.matches(a) -> { val l = a.lowercase(); l != "::" && l != "::1" && !l.startsWith("fe80") }
                else -> false
            }
        }.distinct().take(4)
        return ok.ifEmpty { FALLBACK }
    }
}
