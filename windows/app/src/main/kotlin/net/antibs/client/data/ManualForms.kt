package net.antibs.client.data

/**
 * 1.0.26: pure builders/validators for «Создать вручную» forms that must produce exactly the model the
 * importer produces (AmneziaWG ↔ amneziawg://, qWDTT ↔ qwdtt://config, Amnezia key ↔ vpn://).
 */
object ManualForms {
    val AWG_INT_KEYS = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
    val AWG_STR_KEYS = listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")

    /** AmneziaWG defaults commonly used by AmneziaVPN servers (user should paste the server's values). */
    val AWG_DEFAULTS = mapOf("jc" to "4", "jmin" to "40", "jmax" to "70", "s1" to "0", "s2" to "0", "h1" to "1", "h2" to "2", "h3" to "3", "h4" to "4")

    /** WireGuard key: base64 of 32 bytes. */
    fun isWgKey(s: String): Boolean = try {
        java.util.Base64.getDecoder().decode(s.trim()).size == 32
    } catch (_: IllegalArgumentException) { false }

    fun isPort(s: String): Boolean = s.trim().toIntOrNull()?.let { it in 1..65535 } == true

    /** Interface address list: "10.8.0.2/32, fd00::2/128" (at least one entry, each with an IP). */
    fun isAddressList(s: String): Boolean {
        val parts = s.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { p ->
            val ip = p.substringBefore('/')
            val pref = p.substringAfter('/', "")
            (ip.matches(Regex("""(\d{1,3}\.){3}\d{1,3}""")) || (ip.contains(':') && ip.all { it.isLetterOrDigit() || it == ':' })) &&
                (pref.isEmpty() || pref.toIntOrNull()?.let { it in 0..128 } == true)
        }
    }

    /** jc/jmin/… must be integers; h1–h4 an integer or an AWG 2.0 range "a-b"; i1–i5 free text. */
    fun awgErrors(awg: Map<String, String>): List<String> {
        val bad = mutableListOf<String>()
        for (k in AWG_INT_KEYS) awg[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.toIntOrNull() == null) bad += k }
        for (k in listOf("h1", "h2", "h3", "h4")) awg[k]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!it.matches(Regex("""\d+(-\d+)?"""))) bad += k
        }
        val jmin = awg["jmin"]?.trim()?.toIntOrNull(); val jmax = awg["jmax"]?.trim()?.toIntOrNull()
        if (jmin != null && jmax != null && jmin > jmax) bad += "jmax"
        return bad
    }

    fun amneziaWg(
        name: String, server: String, port: Int, privateKey: String, publicKey: String,
        preSharedKey: String, address: String, mtu: String, awg: Map<String, String>,
    ): XrayProfile = XrayProfile(
        remarks = name.ifBlank { server }, protocol = "amneziawg", server = server.trim(), port = port,
        secretKey = privateKey.trim(), publicKey = publicKey.trim(),
        preSharedKey = preSharedKey.trim().ifEmpty { null },
        localAddress = address.trim().ifEmpty { null },
        mtu = mtu.trim().toIntOrNull(), network = "tcp",
        awg = (AWG_INT_KEYS + AWG_STR_KEYS).mapNotNull { k -> awg[k]?.trim()?.ifEmpty { null }?.let { k to it } }.toMap(),
    )

    fun amneziaWgErrors(server: String, port: String, privateKey: String, publicKey: String, psk: String, address: String, mtu: String, awg: Map<String, String>): List<String> {
        val e = mutableListOf<String>()
        if (server.isBlank()) e += "адрес"
        if (!isPort(port)) e += "порт"
        if (!isWgKey(privateKey)) e += "приватный ключ"
        if (!isWgKey(publicKey)) e += "публичный ключ"
        if (psk.isNotBlank() && !isWgKey(psk)) e += "PSK"
        if (!isAddressList(address)) e += "адрес интерфейса"
        if (mtu.isNotBlank() && mtu.trim().toIntOrNull()?.let { it in 1100..1500 } != true) e += "MTU"
        e += awgErrors(awg)
        return e
    }

    fun qwdtt(name: String, peer: String, hashes: String, workers: String, port: String, password: String): QwdttProfile = QwdttProfile(
        name = name.ifBlank { "qWDTT" }, peer = peer.trim(),
        hashes = hashes.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
        workers = workers.trim().toIntOrNull()?.takeIf { it > 0 } ?: 9,
        port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 9000,
        password = password,
    )

    /** peer = host:port of the qWDTT server; password required; workers 1–108; local port 1024–65535. */
    fun qwdttErrors(peer: String, workers: String, port: String, password: String): List<String> {
        val e = mutableListOf<String>()
        val p = peer.trim()
        val hp = p.substringAfterLast(':', "")
        if (p.isEmpty() || p.contains(' ') || hp.isEmpty() || !isPort(hp) || p.substringBeforeLast(':').isBlank()) e += "peer (хост:порт)"
        if (password.isBlank()) e += "пароль"
        if (workers.isNotBlank() && workers.trim().toIntOrNull()?.let { it in 1..108 } != true) e += "потоки"
        if (port.isNotBlank() && port.trim().toIntOrNull()?.let { it in 1024..65535 } != true) e += "локальный порт"
        return e
    }

    /** vpn:// AmneziaVPN key → the same profile as import (AmneziaWG / WireGuard / Xray JSON). */
    fun amneziaKey(text: String, name: String = ""): XrayProfile? {
        val t = text.trim()
        if (!t.lowercase().startsWith("vpn://")) return null
        val p = LinkParser.parseAmneziaVpn(t) ?: return null
        return if (name.isBlank()) p else p.copy(remarks = name.trim())
    }
}
