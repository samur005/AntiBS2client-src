package net.antibs.client.data

/**
 * 1.0.41: OpenVPN (2.6.15) and OpenConnect (9.12) profiles. Both run as helpers behind a loopback SOCKS5
 * (libvpnstack.so: the VPN client's packets go into a userspace gVisor netstack), so Xray keeps the TUN,
 * routing, ad block and per-app rules.
 *
 * OpenVPN  → XrayProfile(protocol="openvpn", server/port/network = first «remote», extra = full .ovpn text,
 *            username/password = auth-user-pass, privateKeyPassphrase = key password).
 * OpenConnect → XrayProfile(protocol="openconnect", server = URL/host, port = 443, username/password,
 *            olc = {proto, group, pin, ua, answers}).
 */
object OpenVpnConfig {
    const val PROTOCOL = "openvpn"

    data class Summary(
        val remote: String, val port: Int, val proto: String, val needsAuth: Boolean, val keyEncrypted: Boolean,
        val errors: List<String>, val name: String?,
    )

    /** Directives we never pass through: scripts, management, logging, daemon mode, device node. */
    private val DROP = setOf(
        "management", "management-client", "management-hold", "management-query-passwords", "management-client-user",
        "management-client-group", "management-external-key", "management-external-cert", "management-forget-disconnect",
        "management-signal", "management-up-down", "up", "down", "route-up", "route-pre-down", "ipchange", "tls-verify",
        "learn-address", "client-connect", "client-disconnect", "auth-user-pass-verify", "script-security", "daemon",
        "log", "log-append", "syslog", "writepid", "dev-node", "cd", "chroot", "user", "group", "status", "plugin",
        "service", "register-dns", "block-outside-dns", "route-method", "ip-win32", "tap-sleep", "dhcp-release", "dhcp-renew",
        "show-net-up", "up-delay", "down-pre", "iproute", "ifconfig-noexec", "route-noexec", "machine-readable-output",
        "verb", "mute", "dev-type", "windows-driver", "auth-retry", "echo",
    )
    private val INLINE = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs", "pkcs12", "secret", "crl-verify", "dh", "http-proxy-user-pass", "auth-user-pass")
    private val FILE_ARG = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs", "pkcs12", "secret", "crl-verify")

    fun looksLike(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("{") || t.contains("://") && !t.contains("\nremote")) return false
        val lines = t.lineSequence().map { it.trim().lowercase() }
        var remote = false; var client = false
        for (l in lines) {
            if (l.startsWith("remote ")) remote = true
            if (l == "client" || l.startsWith("tls-client") || l.startsWith("<ca>") || l.startsWith("dev tun")) client = true
        }
        return remote && client
    }

    private fun words(l: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var quote = false
        for (c in l) {
            when {
                c == '"' -> quote = !quote
                c.isWhitespace() && !quote -> { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun summary(text: String): Summary {
        var remote = ""; var port = 1194; var proto = "udp"; var needsAuth = false; var keyEnc = false; var name: String? = null
        val errors = ArrayList<String>()
        val inlined = HashSet<String>()
        var block: String? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (block != null) {
                if (l.equals("</$block>", true)) { inlined += block!!; block = null }
                else if (block == "key" && (l.contains("ENCRYPTED") || l.startsWith("Proc-Type: 4,ENCRYPTED"))) keyEnc = true
                continue
            }
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) {
                if (name == null && l.startsWith("#") && l.contains("name", true)) name = l.substringAfter(':', "").trim().ifEmpty { null }
                continue
            }
            val open = Regex("^<([a-z0-9-]+)>$", RegexOption.IGNORE_CASE).find(l)
            if (open != null) { block = open.groupValues[1].lowercase(); continue }
            val w = words(l); if (w.isEmpty()) continue
            when (w[0].lowercase()) {
                "remote" -> if (remote.isEmpty()) {
                    remote = w.getOrElse(1) { "" }
                    w.getOrNull(2)?.toIntOrNull()?.let { port = it }
                    w.getOrNull(3)?.let { proto = normProto(it) }
                }
                "port", "rport" -> w.getOrNull(1)?.toIntOrNull()?.let { port = it }
                "proto" -> w.getOrNull(1)?.let { proto = normProto(it) }
                "auth-user-pass" -> needsAuth = true
                "askpass" -> keyEnc = true
            }
            val d = w[0].lowercase()
            if (d in FILE_ARG && w.size > 1 && w[1] != "[inline]") errors += "«$d ${w[1]}» ссылается на файл — вставьте его в конфиг блоком <$d>…</$d>"
        }
        if (remote.isEmpty()) errors += "нет строки «remote»"
        return Summary(remote, port, proto, needsAuth, keyEnc, errors, name)
    }

    private fun normProto(p: String) = if (p.lowercase().startsWith("tcp")) "tcp" else "udp"

    /** Config for the helper: dangerous directives removed, auth-user-pass via management, Android-safe options. */
    fun sanitize(text: String): String {
        val out = StringBuilder()
        var block: String? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (block != null) {
                out.append(raw.trimEnd()).append('\n')
                if (l.equals("</$block>", true)) block = null
                continue
            }
            val open = Regex("^<([a-z0-9-]+)>$", RegexOption.IGNORE_CASE).find(l)
            if (open != null) {
                val b = open.groupValues[1].lowercase()
                if (b in INLINE || b == "connection") { block = b; out.append(l).append('\n') }
                continue
            }
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) continue
            val w = words(l); if (w.isEmpty()) continue
            val d = w[0].lowercase()
            if (d in DROP) continue
            if (d == "auth-user-pass") { out.append("auth-user-pass\n"); continue }
            if (d == "dev") { out.append("dev tun\n"); continue }
            out.append(l).append('\n')
        }
        if (!out.contains("\nclient\n") && !out.startsWith("client\n") && !out.contains("tls-client")) out.insert(0, "client\n")
        if (!Regex("(?m)^dev ").containsMatchIn(out)) out.append("dev tun\n")
        out.append("pull-filter ignore \"block-outside-dns\"\n")
        return out.toString()
    }

    fun profile(text: String, name: String = "", user: String = "", pass: String = "", keyPass: String = ""): XrayProfile {
        val s = summary(text)
        return XrayProfile(
            remarks = name.ifBlank { s.name ?: "OpenVPN · ${s.remote}" }, protocol = PROTOCOL, server = s.remote, port = s.port,
            network = s.proto, extra = text.trim() + "\n", username = user.ifBlank { null }, password = pass.ifBlank { null },
            privateKeyPassphrase = keyPass.ifBlank { null },
        )
    }

    data class Form(
        val server: String, val port: Int, val proto: String = "udp", val cipher: String = "AES-256-GCM", val auth: String = "SHA256",
        val ca: String = "", val cert: String = "", val key: String = "", val tlsCrypt: String = "", val tlsAuth: String = "",
        val keyDirection: String = "1", val userPass: Boolean = false, val compLzo: Boolean = false,
    )

    /** «Создать вручную» form → .ovpn text. */
    fun build(f: Form): String = buildString {
        appendLine("client"); appendLine("dev tun"); appendLine("proto ${f.proto}")
        appendLine("remote ${f.server.trim()} ${f.port}")
        appendLine("nobind"); appendLine("persist-key"); appendLine("remote-cert-tls server")
        appendLine("resolv-retry infinite")
        val c = f.cipher.trim().ifEmpty { "AES-256-GCM" }
        appendLine("data-ciphers $c:AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305")
        appendLine("data-ciphers-fallback $c")
        if (f.auth.isNotBlank()) appendLine("auth ${f.auth.trim()}")
        if (f.compLzo) appendLine("comp-lzo no")
        if (f.userPass) appendLine("auth-user-pass")
        fun inl(tag: String, v: String) { if (v.isNotBlank()) { appendLine("<$tag>"); appendLine(v.trim()); appendLine("</$tag>") } }
        inl("ca", f.ca); inl("cert", f.cert); inl("key", f.key)
        if (f.tlsCrypt.isNotBlank()) inl("tls-crypt", f.tlsCrypt)
        else if (f.tlsAuth.isNotBlank()) { appendLine("key-direction ${f.keyDirection}"); inl("tls-auth", f.tlsAuth) }
    }

    fun formErrors(f: Form): List<String> = buildList {
        if (f.server.isBlank()) add("сервер")
        if (f.port !in 1..65535) add("порт")
        if (!f.ca.contains("BEGIN CERTIFICATE")) add("CA-сертификат")
        if (f.cert.isNotBlank() != f.key.isNotBlank()) add("сертификат и ключ клиента — оба или ни одного")
        if (f.cert.isBlank() && !f.userPass) add("нужен сертификат клиента или логин/пароль")
    }
}

object OpenConnectConfig {
    const val PROTOCOL = "openconnect"
    /** openconnect --protocol values. */
    val PROTOCOLS = linkedMapOf(
        "anyconnect" to "Cisco AnyConnect / ocserv", "gp" to "Palo Alto GlobalProtect", "pulse" to "Pulse / Ivanti",
        "nc" to "Juniper Network Connect", "f5" to "F5 BIG-IP", "fortinet" to "Fortinet", "array" to "Array Networks",
    )

    fun isLink(s: String) = s.trim().lowercase().let { it.startsWith("openconnect://") || it.startsWith("anyconnect://") || it.startsWith("ocserv://") }

    /** openconnect://user:pass@host[:port][/path]?protocol=gp&group=G&pin=pin-sha256:…&ua=…#Name */
    fun parse(link: String): XrayProfile {
        val p = LinkParser.split(link.trim()) ?: throw IllegalArgumentException("openconnect: неверная ссылка")
        val scheme = link.substringBefore("://").lowercase()
        val user = p.userInfo?.substringBefore(':')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val pass = p.userInfo?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        require(p.host.isNotBlank()) { "openconnect: нет сервера" }
        val proto = (p.query["protocol"] ?: p.query["proto"] ?: "anyconnect").lowercase().let { if (it == "ocserv") "anyconnect" else it }
        require(proto in PROTOCOLS) { "openconnect: неизвестный протокол «$proto»" }
        val port = if (p.port > 0) p.port else 443
        val url = "https://" + p.host + (if (port != 443) ":$port" else "") + p.path
        val extra = buildMap {
            put("proto", proto)
            p.query["group"]?.let { put("group", it) } ?: p.query["authgroup"]?.let { put("group", it) }
            (p.query["pin"] ?: p.query["servercert"])?.let { put("pin", it) }
            (p.query["ua"] ?: p.query["useragent"])?.let { put("ua", it) }
        }
        return XrayProfile(remarks = p.fragment.ifEmpty { "${PROTOCOLS[proto]?.substringBefore(' ') ?: "OpenConnect"} · ${p.host}" }, protocol = PROTOCOL,
            server = url, port = port, username = user, password = pass, olc = extra.ifEmpty { null }, network = scheme.let { "tcp" })
    }

    fun host(p: XrayProfile): String = p.server.substringAfter("://").substringBefore('/').substringBefore(':')

    fun profile(server: String, proto: String, user: String, pass: String, group: String, pin: String, answers: String, ua: String, name: String): XrayProfile {
        val url = server.trim().let { if (it.contains("://")) it else "https://$it" }
        val hostPort = url.substringAfter("://").substringBefore('/')
        val port = hostPort.substringAfter(':', "443").toIntOrNull() ?: 443
        val extra = buildMap {
            put("proto", proto)
            if (group.isNotBlank()) put("group", group.trim())
            if (pin.isNotBlank()) put("pin", normPin(pin))
            if (ua.isNotBlank()) put("ua", ua.trim())
            if (answers.isNotBlank()) put("answers", answers.trim())
        }
        return XrayProfile(remarks = name.ifBlank { "${PROTOCOLS[proto]?.substringBefore(' ') ?: "OpenConnect"} · ${hostPort.substringBefore(':')}" },
            protocol = PROTOCOL, server = url, port = port, network = "tcp", username = user.ifBlank { null }, password = pass.ifBlank { null }, olc = extra)
    }

    /** «sha256:…», bare base64 or hex → openconnect --servercert value. */
    fun normPin(pin: String): String {
        val t = pin.trim()
        return when {
            t.startsWith("pin-sha256:") || t.startsWith("sha1:") || t.startsWith("sha256:") -> t
            Regex("^[A-Za-z0-9+/]{43}=$").matches(t) -> "pin-sha256:$t"
            Regex("^[0-9a-fA-F:]{40,}$").matches(t) -> "sha256:" + t.replace(":", "").lowercase()
            else -> t
        }
    }
}
