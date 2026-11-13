package net.antibs.client.data

/**
 * 2.0.45: Snell (v1–v6) from the formats it is actually distributed in, besides `snell://` links:
 *  - Surge proxy lines: `Name = snell, host, port, psk=…, version=6, reuse=true, obfs=http, obfs-host=…`;
 *  - Clash / mihomo YAML `proxies:` entries with `type: snell` (flow `- {…}` or block style, nested `obfs-opts`).
 * Only Snell entries are taken from YAML; other proxy types in the same file are ignored here.
 */
object SnellFormats {
    private val SURGE = Regex("""^\s*([^=,]+?)\s*=\s*snell\s*,(.*)$""", RegexOption.IGNORE_CASE)

    fun isSurgeLine(line: String): Boolean = SURGE.matches(line.trim())

    /** One Surge `[Proxy]` line → profile (null when it isn't a usable Snell line). */
    fun surgeLine(line: String): XrayProfile? {
        val m = SURGE.matchEntire(line.trim()) ?: return null
        val name = m.groupValues[1].trim()
        val parts = m.groupValues[2].split(',').map { it.trim() }
        if (parts.size < 2) return null
        val host = parts[0].removePrefix("[").removeSuffix("]")
        val port = parts[1].toIntOrNull() ?: return null
        val kv = parts.drop(2).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim().trim('"') } }.toMap()
        return build(name, host, port, kv["psk"], kv["version"], kv["obfs"], kv["obfs-host"], kv["mode"], kv["reuse"])
    }

    /** `proxies:` entries of a Clash / mihomo YAML with `type: snell`. Empty when there are none. */
    fun clashYaml(text: String): List<XrayProfile> = MiniYaml.proxies(text).mapNotNull { clashProxy(it) }

    fun looksLikeClash(text: String): Boolean = text.lineSequence().any { it.trimEnd() == "proxies:" || it.startsWith("proxies:") }

    fun clashProxy(m: Map<String, Any?>): XrayProfile? {
        if ((m["type"] as? String)?.lowercase() != "snell") return null
        val host = (m["server"] as? String)?.removeSurrounding("[", "]") ?: return null
        val port = (m["port"] as? String)?.toIntOrNull() ?: return null
        @Suppress("UNCHECKED_CAST") val obfs = m["obfs-opts"] as? Map<String, Any?>
        return build((m["name"] as? String) ?: host, host, port, m["psk"] as? String, m["version"] as? String,
            obfs?.get("mode") as? String, obfs?.get("host") as? String, m["mode"] as? String, m["reuse"] as? String)
    }

    private fun build(name: String, host: String, port: Int, psk: String?, version: String?, obfs: String?, obfsHost: String?,
                      mode: String?, reuse: String?): XrayProfile? {
        if (host.isBlank() || port !in 1..65535 || psk.isNullOrEmpty()) return null
        val v = version?.toIntOrNull() ?: 4
        val o = obfs?.lowercase()?.takeIf { it == "http" || it == "tls" }
        return XrayProfile(
            remarks = name.ifBlank { host }, protocol = "snell", server = host, port = port, password = psk, network = "tcp",
            headerType = if (v >= 6) null else o, host = if (v >= 6 || o == null) null else obfsHost?.ifBlank { null },
            protoVersion = v,
            snellMode = mode?.lowercase()?.takeIf { v >= 6 && it in ProtocolSupport.SNELL_V6_MODES && it != "default" },
            snellReuse = reuse.equals("true", true) || reuse == "1",
        )
    }
}

/**
 * Tiny YAML reader for the `proxies:` list of Clash configs: list items as flow maps (`- {a: 1, b: {c: d}}`) or block
 * maps (`- name: x` + indented keys, nested maps one level deeper). Scalars come back as strings (quotes removed).
 */
internal object MiniYaml {
    fun proxies(text: String): List<Map<String, Any?>> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimEnd() == "proxies:" || (it.startsWith("proxies:") && it.substringAfter(':').isBlank()) }
        if (start < 0) {
            // `proxies: [ {…}, {…} ]` on one line
            val one = lines.firstOrNull { it.startsWith("proxies:") } ?: return emptyList()
            val v = flow(one.substringAfter(':').trim()) as? List<*> ?: return emptyList()
            @Suppress("UNCHECKED_CAST") return v.filterIsInstance<Map<String, Any?>>()
        }
        val out = ArrayList<Map<String, Any?>>()
        var i = start + 1
        var itemIndent = -1
        var cur: LinkedHashMap<String, Any?>? = null
        var nested: LinkedHashMap<String, Any?>? = null
        var nestedIndent = -1
        fun flush() { cur?.let { out.add(it) }; cur = null; nested = null }
        while (i < lines.size) {
            val raw = lines[i++]
            val line = stripComment(raw)
            if (line.isBlank()) continue
            val ind = raw.length - raw.trimStart().length
            if (ind == 0 && !line.trimStart().startsWith("-")) break // next top-level key
            val t = line.trim()
            if (t.startsWith("- ") || t == "-") {
                if (itemIndent < 0) itemIndent = ind
                if (ind != itemIndent) continue
                flush()
                val body = t.removePrefix("-").trim()
                if (body.startsWith("{")) { @Suppress("UNCHECKED_CAST") (flow(body) as? Map<String, Any?>)?.let { out.add(it) }; continue }
                cur = LinkedHashMap()
                if (body.isNotEmpty()) keyValue(body)?.let { (k, v) -> cur!![k] = v }
                continue
            }
            val c = cur ?: continue
            val kv = keyValue(t) ?: continue
            if (nested != null && ind > nestedIndent) { nested!![kv.first] = kv.second; continue }
            nested = null
            if (kv.second == null) { val n = LinkedHashMap<String, Any?>(); c[kv.first] = n; nested = n; nestedIndent = ind }
            else c[kv.first] = kv.second
        }
        flush()
        return out
    }

    private fun stripComment(s: String): String {
        var q: Char? = null
        for ((i, ch) in s.withIndex()) {
            if (q != null) { if (ch == q) q = null; continue }
            if (ch == '"' || ch == '\'') q = ch
            else if (ch == '#' && (i == 0 || s[i - 1].isWhitespace())) return s.substring(0, i)
        }
        return s
    }

    /** `key: value` → (key, scalar / flow value, or null for a nested block). */
    private fun keyValue(t: String): Pair<String, Any?>? {
        val c = t.indexOf(':').takeIf { it > 0 } ?: return null
        val k = unquote(t.substring(0, c).trim())
        val v = t.substring(c + 1).trim()
        return k to if (v.isEmpty()) null else flow(v)
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\''))) s.substring(1, s.length - 1) else s

    /** Flow value: `{…}`, `[…]` or a scalar. */
    fun flow(s: String): Any? {
        val p = intArrayOf(0)
        return value(s.trim(), p)
    }

    private fun value(s: String, p: IntArray): Any? {
        skip(s, p)
        if (p[0] >= s.length) return ""
        return when (s[p[0]]) {
            '{' -> { p[0]++; val m = LinkedHashMap<String, Any?>()
                while (true) { skip(s, p); if (p[0] >= s.length) break; if (s[p[0]] == '}') { p[0]++; break }
                    if (s[p[0]] == ',') { p[0]++; continue }
                    val k = scalar(s, p, ":").trim(); if (p[0] < s.length && s[p[0]] == ':') p[0]++
                    m[unquote(k)] = value(s, p) }
                m }
            '[' -> { p[0]++; val l = ArrayList<Any?>()
                while (true) { skip(s, p); if (p[0] >= s.length) break; if (s[p[0]] == ']') { p[0]++; break }
                    if (s[p[0]] == ',') { p[0]++; continue }; l.add(value(s, p)) }
                l }
            else -> unquote(scalar(s, p, ",}]").trim())
        }
    }

    private fun scalar(s: String, p: IntArray, stops: String): String {
        val b = StringBuilder()
        var q: Char? = null
        while (p[0] < s.length) {
            val ch = s[p[0]]
            if (q != null) { b.append(ch); p[0]++; if (ch == q) q = null; continue }
            if (ch == '"' || ch == '\'') { q = ch; b.append(ch); p[0]++; continue }
            // «key: value» — ':' ends a key only when followed by a space (keeps «[::1]» / URLs intact)
            if (ch in stops && (ch != ':' || p[0] + 1 >= s.length || s[p[0] + 1] == ' ')) break
            b.append(ch); p[0]++
        }
        return b.toString()
    }

    private fun skip(s: String, p: IntArray) { while (p[0] < s.length && s[p[0]].isWhitespace()) p[0]++ }
}
