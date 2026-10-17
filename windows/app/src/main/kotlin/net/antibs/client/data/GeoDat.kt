package net.antibs.client.data

/**
 * 1.0.19: minimal reader for Xray geoip.dat / geosite.dat (protobuf GeoIPList / GeoSiteList): lists the
 * country_code / category tags a file contains, so routing rules can be pointed at the file that has them.
 */
object GeoDat {
    private fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
        var r = 0L; var s = 0; var i = start
        while (true) {
            if (i >= b.size || s > 63) throw IllegalArgumentException("geo: битый varint")
            val c = b[i++].toInt() and 0xff
            r = r or ((c and 0x7f).toLong() shl s); s += 7
            if (c < 0x80) return r to i
        }
    }

    /** Upper-cased tags in file order; throws on a file that isn't a geo list. */
    fun tags(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < b.size) {
            val (key, i1) = varint(b, i)
            if (key != 10L) throw IllegalArgumentException("geo: неожиданное поле $key")
            val (len, i2) = varint(b, i1)
            val end = i2 + len.toInt()
            if (len < 0 || end > b.size) throw IllegalArgumentException("geo: обрезанный файл")
            // entry: field 1 (string) = tag
            val (k2, j1) = varint(b, i2)
            if (k2 == 10L) {
                val (l2, j2) = varint(b, j1)
                if (j2 + l2.toInt() <= end) out += String(b, j2, l2.toInt(), Charsets.UTF_8).uppercase()
            }
            i = end
        }
        return out
    }

    /** "geosite:whitelist" → ("site", "WHITELIST"); "geoip:!ru" → ("ip", "RU"); else null. */
    fun ref(value: String): Pair<String, String>? {
        val v = value.trim()
        val (kind, rest) = when {
            v.startsWith("geosite:", true) -> "site" to v.substring(8)
            v.startsWith("geoip:", true) -> "ip" to v.substring(6)
            else -> return null
        }
        val tag = rest.removePrefix("!").substringBefore('@').trim()
        return if (tag.isEmpty()) null else kind to tag.uppercase()
    }

    /**
     * Rewrites geosite:/geoip: references of [p] to «ext:<file>:<tag>» when the profile's own file has the tag.
     * References no available file knows ([bundled] = tags of the stock files, null = unknown) are dropped so
     * Xray doesn't refuse to start; they're returned as the second value for the log.
     */
    fun resolve(value: String, p: RoutingProfile, bundled: Map<String, Set<String>>?): String? {
        val r = ref(value) ?: return value
        val (kind, tag) = r
        val file = if (kind == "site") p.geositeFile else p.geoipFile
        val tags = if (kind == "site") p.geositeTags else p.geoipTags
        val v = value.trim()
        val prefixLen = if (kind == "site") 8 else 6
        if (file.isNotBlank() && tags.any { it.equals(tag, true) }) {
            val rest = v.substring(prefixLen) // keeps «!» and «@attr»
            return "ext:$file:$rest"
        }
        val known = bundled?.get(kind) ?: return value
        return if (tag in known) value else null
    }
}
