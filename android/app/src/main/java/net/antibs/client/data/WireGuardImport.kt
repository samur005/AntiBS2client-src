package net.antibs.client.data

import java.util.Base64

/** wg-quick / awg-quick single-peer client profiles. Never execute hooks or include values/keys in diagnostics. */
object WireGuardImport {
    val awgNumbers = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "itime")
    val awgLegacy = listOf("j1", "j2", "j3", "itime")
    val awgV3 = linkedMapOf("headerprotectionkey" to "header-protection-key", "contentpaddingaddition" to "content-padding-addition",
        "rekeyaftertime" to "rekey-after-time", "rekeytimeout" to "rekey-timeout", "rejectaftertime" to "reject-after-time",
        "keepalivetimeout" to "keepalive-timeout", "maxhandshakeattempts" to "max-handshake-attempts",
        "randomtrailers" to "random-trailers", "disablecookies" to "disable-cookies")
    val awgKeys = (awgNumbers + listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3") + awgV3.keys).toSet()
    fun looksLike(text: String) = text.lineSequence().any { clean(it).equals("[Interface]", true) }
    private fun clean(s: String) = s.substringBefore('#').trim()
    fun keyOk(s: String?): Boolean = s != null && runCatching { Base64.getDecoder().decode(s).size == 32 }.getOrDefault(false)
    private fun cidr(s: String): Boolean {
        val p = s.split('/')
        if (p.size != 2) return false
        val bits = p[1].toIntOrNull() ?: return false
        return if (':' in p[0]) bits in 0..128 && p[0].matches(Regex("[0-9a-fA-F:]+")) &&
            runCatching { java.net.InetAddress.getByName(p[0]) is java.net.Inet6Address }.getOrDefault(false)
        else bits in 0..32 && p[0].split('.').let { a -> a.size == 4 && a.all { it.toIntOrNull() in 0..255 } }
    }
    private fun uintRange(s: String, max: Long = 4294967295L): Boolean {
        if (s == "(off)") return true
        val a = s.split('-').map { it.toLongOrNull() ?: return false }
        return a.size in 1..2 && a.all { it in 0..max } && a.first() <= a.last()
    }

    /** Validate AWG maps from files, links and stored profiles; no silent dropping of unsupported version fields. */
    fun validateAwg(o: Map<String, String>) {
        require(o.keys.all { it in awgKeys || it == "version" }) { "AWG: неизвестный параметр" }
        for (k in awgNumbers) o[k]?.let { require(it.toLongOrNull() in 0..Int.MAX_VALUE.toLong()) { "AWG: неверный $k" } }
        for (k in listOf("h1", "h2", "h3", "h4") + awgV3.keys.filter { it !in listOf("headerprotectionkey", "randomtrailers", "disablecookies") })
            o[k]?.let { require(uintRange(it)) { "AWG: неверный диапазон $k" } }
        for (k in listOf("randomtrailers", "disablecookies")) o[k]?.let {
            require(it.lowercase() in listOf("true", "false", "1", "0")) { "AWG: неверный $k" }
        }
        o["headerprotectionkey"]?.let { require(keyOk(it)) { "AWG: неверный HeaderProtectionKey (нужен ключ 32 байта Base64)" } }
        val v3 = isV3(o)
        require(o["version"] == null || o["version"]!!.startsWith("3") || awgV3.keys.none { it in o }) { "AWG: версия не соответствует параметрам 3.x" }
        require(!v3 || awgLegacy.none { it in o }) { "AWG: параметры 1.5 (J1–J3/Itime) нельзя смешивать с 3.x" }
        o["version"]?.let { require(it in listOf("1", "1.5", "2", "2.0", "3", "3.0", "3.1")) { "AWG: неподдерживаемая версия" } }
        if ("headerprotectionkey" in o) for (k in listOf("s1", "s2", "s3", "s4"))
            require((o[k]?.toIntOrNull() ?: 0) >= 12) { "AWG 3: для HeaderProtectionKey нужны S1–S4 не меньше 12" }
    }
    fun isV3(o: Map<String, String>) = o["version"]?.startsWith("3") == true || awgV3.keys.any { it in o }

    fun profile(text: String, name: String? = null, forceAwg: Boolean = false): XrayProfile {
        require(text.length <= 1024 * 1024) { "WG/AWG: файл больше 1 МиБ" }
        val iface = linkedMapOf<String, String>()
        val peer = linkedMapOf<String, String>()
        var section = ""; var interfaces = 0; var peers = 0
        for (raw in text.removePrefix("\uFEFF").lines()) {
            val l = clean(raw)
            if (l.isBlank()) continue
            if (l.startsWith('[')) {
                section = l.lowercase()
                when (section) { "[interface]" -> interfaces++; "[peer]" -> peers++; else -> error("WG/AWG: неизвестная секция") }
                continue
            }
            val eq = l.indexOf('='); require(eq > 0) { "WG/AWG: ожидается параметр = значение" }
            val k = l.substring(0, eq).trim().lowercase(); val v = l.substring(eq + 1).trim()
            val m = when (section) { "[interface]" -> iface; "[peer]" -> peer; else -> error("WG/AWG: параметр вне секции") }
            val allowed = if (section == "[interface]") setOf("privatekey", "address", "dns", "mtu", "listenport", "table", "saveconfig") + awgKeys
                else setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive")
            require(k in allowed) { "WG/AWG: неподдерживаемый параметр $k (скрипты не выполняются)" }
            require(v.isNotBlank()) { "WG/AWG: пустой $k" }
            if (k in setOf("address", "dns", "allowedips") && k in m) m[k] = m.getValue(k) + "," + v
            else { require(k !in m) { "WG/AWG: повтор параметра $k" }; m[k] = v }
        }
        require(interfaces == 1 && peers == 1) { "WG/AWG: требуется одна секция Interface и один Peer; несколько Peer пока не поддерживаются" }
        require(keyOk(iface["privatekey"]) && keyOk(peer["publickey"])) { "WG/AWG: PrivateKey/PublicKey должны быть ключами 32 байта Base64" }
        peer["presharedkey"]?.let { require(keyOk(it)) { "WG/AWG: неверный PresharedKey" } }
        fun list(m: Map<String, String>, k: String) = m[k]?.split(',')?.map { it.trim() }.orEmpty()
        val addresses = list(iface, "address"); require(addresses.isNotEmpty() && addresses.all(::cidr)) { "WG/AWG: неверный Address (нужен IP/маска)" }
        val ips = list(peer, "allowedips"); require(ips.isNotEmpty() && ips.all(::cidr)) { "WG/AWG: неверный AllowedIPs" }
        val endpoint = peer["endpoint"] ?: error("WG/AWG: отсутствует Endpoint")
        val e = LinkParser.split("wg://key@$endpoint") ?: error("WG/AWG: неверный Endpoint")
        require(e.host.isNotBlank() && e.port in 1..65535 && !e.host.any { it.isWhitespace() } && e.path.isEmpty()) { "WG/AWG: неверный Endpoint" }
        val mtu = iface["mtu"]?.let { it.toIntOrNull()?.takeIf { n -> n in 576..65535 } ?: error("WG/AWG: неверный MTU") }
        val keep = peer["persistentkeepalive"]?.let { it.toIntOrNull()?.takeIf { n -> n in 0..65535 } ?: error("WG/AWG: PersistentKeepalive — только целое 0–65535; диапазоны ядром mihomo не передаются") }
        val awg = iface.filterKeys { it in awgKeys }
        validateAwg(awg)
        if (forceAwg || awg.isNotEmpty()) require(addresses.count { ':' in it } <= 1 && addresses.count { ':' !in it } <= 1) {
            "AWG: ядро поддерживает один IPv4 и один IPv6 адрес интерфейса"
        }
        val proto = if (forceAwg || awg.isNotEmpty()) "amneziawg" else "wireguard"
        return XrayProfile(remarks = name?.substringBeforeLast('.')?.ifBlank { null } ?: "${if (proto == "amneziawg") "AmneziaWG" else "WireGuard"} · ${e.host}",
            protocol = proto, server = e.host, port = e.port, secretKey = iface.getValue("privatekey"), publicKey = peer.getValue("publickey"),
            preSharedKey = peer["presharedkey"], localAddress = addresses.joinToString(","), mtu = mtu,
            wgAllowedIps = ips, wgKeepAlive = keep, wgDns = list(iface, "dns"), awg = awg.takeIf { proto == "amneziawg" })
    }
}
