package net.antibs.client.data

/**
 * 2.0.1: SSTP and ZeroTier profiles (both run in-process and feed libvpnstack.so «pkt»).
 * SSTP     → XrayProfile(protocol="sstp", server, port = 443, username/password, olc = {verify: "1"/"0", sni}).
 * ZeroTier → XrayProfile(protocol="zerotier", server = 16-hex Network ID, port = 9993).
 */
object PacketVpnConfig {
    const val SSTP = "sstp"
    const val ZEROTIER = "zerotier"

    fun isNetworkId(s: String): Boolean = s.trim().lowercase().matches(Regex("[0-9a-f]{16}"))

    fun hostOf(p: XrayProfile): String = if (p.protocol == SSTP) "${p.server}:${p.port}" else "сеть ${p.server}"

    fun sstp(name: String, host: String, port: Int, user: String, password: String, verify: Boolean, sni: String): XrayProfile {
        val h = host.trim().removePrefix("sstp://").removePrefix("https://").substringBefore('/')
        val (server, prt) = if (h.count { it == ':' } == 1) h.substringBefore(':') to (h.substringAfter(':').toIntOrNull() ?: port) else h to port
        return XrayProfile(protocol = SSTP, server = server, port = if (prt in 1..65535) prt else 443,
            remarks = name.ifBlank { server }, username = user, password = password,
            olc = buildMap { put("verify", if (verify) "1" else "0"); if (sni.isNotBlank()) put("sni", sni.trim()) })
    }

    fun zerotier(name: String, networkId: String): XrayProfile {
        val id = networkId.trim().lowercase()
        return XrayProfile(protocol = ZEROTIER, server = id, port = 9993, remarks = name.ifBlank { "ZeroTier $id" })
    }
}
