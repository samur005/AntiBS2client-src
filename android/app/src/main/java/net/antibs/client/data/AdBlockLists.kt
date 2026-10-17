package net.antibs.client.data

/**
 * 1.0.40: downloadable AdGuard DNS filter, user block/allow lists and the compiler that merges everything into one
 * geosite file (one Xray matcher, whitelist really removes entries). Parsing logic follows Etonify (GPL-3.0-or-later).
 */
object AdGuardFilter {
    const val NAME = "AdGuard DNS filter"
    const val URL = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
    const val MAX_BYTES = 16 * 1024 * 1024

    data class Parsed(val block: Set<String>, val allow: Set<String>)

    private val hostsLine = Regex("""^(?:0\.0\.0\.0|127\.0\.0\.1|::|::1)\s+([A-Za-z0-9.\-_]+)$""", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("""^\d+\.\d+\.\d+\.\d+$""")
    private val okChars = Regex("""^[a-z0-9.\-]+$""")

    fun parse(text: String): Parsed {
        val block = HashSet<String>(); val allow = HashSet<String>()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty() || l[0] == '!' || l[0] == '#' || l[0] == '[') continue
            val d = extract(l) ?: continue
            (if (l.startsWith("@@")) allow else block).add(d)
        }
        block.removeAll(allow)
        return Parsed(block, allow)
    }

    /** Domain of one adblock / hosts rule, or null for cosmetic, regex and other unsupported rules. */
    fun extract(line0: String): String? {
        var s = line0.trim().removePrefix("@@")
        if ("\$badfilter" in s || s.startsWith("/") || "##" in s || "#@#" in s || "#?#" in s) return null
        hostsLine.find(s)?.let { return normalize(it.groupValues[1]) }
        val dollar = s.indexOf('$')
        if (dollar >= 0) s = s.substring(0, dollar)
        s = s.trim().removePrefix("||")
        s = Regex("^\\|https?://", RegexOption.IGNORE_CASE).replaceFirst(s, "")
        s = Regex("^https?://", RegexOption.IGNORE_CASE).replaceFirst(s, "").removePrefix("|")
        if (s.startsWith("*.")) s = s.substring(2)
        s = s.trimStart('.')
        var end = s.length
        for (c in charArrayOf('^', '/', '|', '*', '?', ',', '=')) { val i = s.indexOf(c); if (i in 0 until end) end = i }
        var d = s.substring(0, end).trim()
        if (d.isEmpty()) return null
        val colon = d.indexOf(':')
        if (colon > 0 && colon < d.length - 1 && d.substring(colon + 1).all { it.isDigit() }) d = d.substring(0, colon)
        return normalize(d)
    }

    fun normalize(s: String): String? {
        val d = s.trim().lowercase().trim('.')
        if (d.isEmpty() || '.' !in d || ".." in d || ':' in d || ipv4.matches(d) || !okChars.matches(d)) return null
        if (d.split('.').any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
        return d
    }
}

object UserDomains {
    /** «full:host», «domain:host», plain host or adblock rule → (GeoPack type, domain). */
    fun parse(lines: List<String>): List<Pair<Int, String>> = lines.mapNotNull { raw ->
        val l = raw.trim().substringBefore(' ').substringBefore('#')
        when {
            l.isEmpty() -> null
            l.startsWith("full:", true) -> AdGuardFilter.normalize(l.substring(5))?.let { GeoPack.FULL to it }
            l.startsWith("domain:", true) -> AdGuardFilter.normalize(l.substring(7))?.let { GeoPack.DOMAIN to it }
            else -> AdGuardFilter.extract(l)?.let { GeoPack.DOMAIN to it }
        }
    }.distinct()

    fun split(text: String): List<String> = text.split('\n', ',', ';', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun toXray(d: Pair<Int, String>) = (if (d.first == GeoPack.FULL) "full:" else "domain:") + d.second
}

object AdBlockCompiler {
    const val FILE = "antibs-block.dat"
    const val TAG = "BLOCK"
    const val REF = "ext:$FILE:block"

    data class Result(val bytes: ByteArray, val count: Int, val allowOverrides: List<String>, val removed: Int)

    /**
     * [lists] = domain lists to block (category-ads-all, trackers, AdGuard, user). Whitelisted domains and their
     * subdomains are removed; a whitelisted domain under a blocked parent becomes an «allow override» (routed
     * normally by a rule placed before the block rule).
     */
    fun compile(lists: List<Collection<Pair<Int, String>>>, whitelist: List<Pair<Int, String>>): Result {
        val wl = whitelist.map { it.second }.toSet()
        fun whitelisted(v: String): Boolean {
            if (v in wl) return true
            var i = v.indexOf('.')
            while (i >= 0) { if (v.substring(i + 1) in wl) return true; i = v.indexOf('.', i + 1) }
            return false
        }
        val out = LinkedHashSet<Pair<Int, String>>()
        var removed = 0
        val blockedDomains = HashSet<String>()
        for (l in lists) for (d in l) {
            if ((d.first == GeoPack.DOMAIN || d.first == GeoPack.FULL) && wl.isNotEmpty() && whitelisted(d.second)) { removed++; continue }
            if (out.add(d) && d.first == GeoPack.DOMAIN) blockedDomains += d.second
        }
        val overrides = whitelist.filter { (_, w) ->
            var i = w.indexOf('.'); var hit = false
            while (i >= 0 && !hit) { if (w.substring(i + 1) in blockedDomains) hit = true; i = w.indexOf('.', i + 1) }
            hit
        }.map { UserDomains.toXray(it) }
        val bytes = GeoPack.list(listOf(GeoPack.siteEntry(TAG, out)))
        return Result(bytes, out.size, overrides, removed)
    }
}
