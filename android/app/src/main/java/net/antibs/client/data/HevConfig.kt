package net.antibs.client.data

/** hev-socks5-tunnel YAML config (TUN fd → the Xray SOCKS inbound). */
object HevConfig {
    /** YAML single-quoted scalar ('' escapes a quote). */
    fun yq(s: String): String = "'" + s.replace("'", "''") + "'"

    /**
     * 2.0.43: [user]/[pass] → RFC 1929 auth to the Xray SOCKS inbound (no longer an open proxy);
     * `icmp: 'off'` — no locally faked ping replies (detectors flag a 0.2 ms RTT to a far host as a tunnel).
     */
    fun build(socksPort: Int, mtu: Int, ipv4: String, ipv6: String?, user: String = "", pass: String = ""): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $mtu")
        appendLine("  ipv4: $ipv4")
        if (ipv6 != null) appendLine("  ipv6: '$ipv6'")
        appendLine("  icmp: 'off'")
        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: 127.0.0.1")
        appendLine("  udp: 'udp'")
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            appendLine("  username: ${yq(user)}")
            appendLine("  password: ${yq(pass)}")
        }
        appendLine("misc:")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  log-level: warn")
    }
}
