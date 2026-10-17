package net.antibs.client.data

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * 1.0.40: minimal streaming reader/writer of v2ray geosite.dat / geoip.dat (protobuf GeoSiteList / GeoIPList).
 * Used to cut the needed tags out of big remote files (runetfreedom, ~70 MB) and to write our compiled block list.
 */
object GeoPack {
    const val PLAIN = 0
    const val REGEX = 1
    const val DOMAIN = 2
    const val FULL = 3

    private fun readVarint(inp: InputStream): Long {
        var r = 0L; var s = 0
        while (s <= 63) {
            val c = inp.read()
            if (c < 0) { if (s == 0) throw EOFException(); throw IllegalArgumentException("geo: обрезанный varint") }
            r = r or ((c and 0x7f).toLong() shl s); s += 7
            if (c < 0x80) return r
        }
        throw IllegalArgumentException("geo: битый varint")
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var i = 0
        while (i < n) { val r = inp.read(b, i, n - i); if (r < 0) throw IllegalArgumentException("geo: обрезанный файл"); i += r }
        return b
    }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
        var r = 0L; var s = 0; var i = start
        while (i < b.size && s <= 63) {
            val c = b[i++].toInt() and 0xff
            r = r or ((c and 0x7f).toLong() shl s); s += 7
            if (c < 0x80) return r to i
        }
        throw IllegalArgumentException("geo: битый varint")
    }

    /** country_code (field 1) of a GeoSite / GeoIP entry, if it is the first field. */
    fun tagOf(entry: ByteArray): String? {
        val (k, i) = varint(entry, 0)
        if (k != 10L) return null
        val (len, j) = varint(entry, i)
        return String(entry, j, len.toInt(), Charsets.UTF_8)
    }

    private inline fun forEachEntry(inp: InputStream, f: (ByteArray) -> Unit) {
        while (true) {
            val key = try { readVarint(inp) } catch (_: EOFException) { return }
            if (key != 10L) throw IllegalArgumentException("geo: неожиданное поле $key")
            val len = readVarint(inp)
            if (len < 0 || len > Int.MAX_VALUE) throw IllegalArgumentException("geo: битая длина")
            f(readFully(inp, len.toInt()))
        }
    }

    /** Entries whose tag (case-insensitive) is in [wanted]; key = uppercase tag. Streams, keeps only matches. */
    fun extract(inp: InputStream, wanted: Set<String>): Map<String, ByteArray> {
        val w = wanted.map { it.uppercase() }.toSet()
        val out = LinkedHashMap<String, ByteArray>()
        forEachEntry(inp) { e -> val t = tagOf(e)?.uppercase(); if (t != null && t in w) out[t] = e }
        return out
    }

    fun tags(inp: InputStream): List<String> {
        val out = ArrayList<String>()
        forEachEntry(inp) { e -> tagOf(e)?.let { out += it.uppercase() } }
        return out
    }

    /** (type, value) of every Domain in a GeoSite entry. */
    fun domains(entry: ByteArray): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < entry.size) {
            val (k, a) = varint(entry, i)
            val field = (k shr 3).toInt()
            when ((k and 7).toInt()) {
                0 -> i = varint(entry, a).second
                1 -> i = a + 8
                5 -> i = a + 4
                2 -> {
                    val (len, b) = varint(entry, a)
                    val end = b + len.toInt()
                    if (field == 2) {
                        var j = b; var type = 0; var value: String? = null
                        while (j < end) {
                            val (k2, c) = varint(entry, j)
                            val f2 = (k2 shr 3).toInt()
                            when ((k2 and 7).toInt()) {
                                0 -> { val (v, d) = varint(entry, c); if (f2 == 1) type = v.toInt(); j = d }
                                1 -> j = c + 8
                                5 -> j = c + 4
                                2 -> { val (l2, d) = varint(entry, c); if (f2 == 2) value = String(entry, d, l2.toInt(), Charsets.UTF_8); j = d + l2.toInt() }
                                else -> throw IllegalArgumentException("geo: тип поля")
                            }
                        }
                        value?.let { out += type to it }
                    }
                    i = end
                }
                else -> throw IllegalArgumentException("geo: тип поля")
            }
        }
        return out
    }

    private fun writeVarint(o: ByteArrayOutputStream, v0: Long) {
        var v = v0
        while (v and 0x7fL.inv() != 0L) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        o.write(v.toInt())
    }

    private fun writeBytes(o: ByteArrayOutputStream, field: Int, b: ByteArray) {
        writeVarint(o, ((field shl 3) or 2).toLong()); writeVarint(o, b.size.toLong()); o.write(b)
    }

    fun siteEntry(tag: String, domains: Collection<Pair<Int, String>>): ByteArray {
        val o = ByteArrayOutputStream(domains.size * 24 + 16)
        writeBytes(o, 1, tag.uppercase().toByteArray())
        for ((type, value) in domains) {
            val d = ByteArrayOutputStream(value.length + 4)
            if (type != 0) { writeVarint(d, 8); writeVarint(d, type.toLong()) }
            writeBytes(d, 2, value.toByteArray())
            writeBytes(o, 2, d.toByteArray())
        }
        return o.toByteArray()
    }

    /** GeoSiteList / GeoIPList of [entries] (field 1). */
    fun list(entries: Collection<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream(entries.sumOf { it.size + 6 })
        entries.forEach { writeBytes(o, 1, it) }
        return o.toByteArray()
    }
}
