package net.antibs.client.engine

import android.content.Context
import net.antibs.client.data.GeoPack
import net.antibs.client.data.TrafficPresets
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * 1.0.40: downloadable block/geo files in filesDir (= Xray asset dir, so «ext:<file>:<tag>» works):
 * runetfreedom lists (cut to the needed tags). 2.0.15: the AdGuard filter / compiled block list are gone (ABS ad blocking).
 */
object BlockFiles {
    private const val ADGUARD = "adguard-dns.txt"
    private const val ADGUARD_META = "adguard-dns.json"
    private const val RF_META = "antibs-rf.json"
    private const val BLOCK_KEY = "antibs-block.json"
    private const val RF_BASE = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/"

    data class RfStatus(val ready: Boolean, val updated: Long = 0, val site: Set<String> = emptySet(), val ip: Set<String> = emptySet(), val bytes: Long = 0)

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", SubscriptionFetcher.LINKS_UA)
        if (c.responseCode !in 200..299) { c.disconnect(); throw IllegalStateException("HTTP ${c.responseCode}") }
        return c
    }

    private fun writeAtomically(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    fun rfStatus(ctx: Context): RfStatus {
        val m = File(ctx.filesDir, RF_META)
        val site = File(ctx.filesDir, TrafficPresets.RF_SITE); val ip = File(ctx.filesDir, TrafficPresets.RF_IP)
        if (!m.exists() || site.length() <= 0 || ip.length() <= 0) return RfStatus(false)
        return try {
            val j = JSONObject(m.readText())
            fun set(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            RfStatus(true, j.optLong("updated"), set("site"), set("ip"), site.length() + ip.length())
        } catch (_: Exception) { RfStatus(false) }
    }

    /** Tags available in the rf files, for TrafficPresets / XrayConfigBuilder.Options.geoExtra. */
    fun geoExtra(ctx: Context): Map<String, Set<String>> {
        val st = rfStatus(ctx)
        return if (!st.ready) emptyMap() else mapOf("site" to st.site, "ip" to st.ip)
    }

    private fun expectedSha(url: String): String? = try {
        val c = open("$url.sha256sum")
        try { c.inputStream.bufferedReader().readText().trim().split(Regex("\\s+")).first().lowercase().takeIf { it.length == 64 } } finally { c.disconnect() }
    } catch (_: Exception) { null }

    /** Streams a remote geo file, keeps only [tags], verifies SHA-256 of the whole download. */
    private fun cut(name: String, tags: List<String>, stage: (String, Long, Long) -> Unit): Map<String, ByteArray> {
        val url = RF_BASE + name
        val sha = expectedSha(url)
        val c = open(url)
        try {
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            val dig = DigestInputStream(c.inputStream, md)
            val counting = object : FilterInputStream(dig) {
                override fun read(): Int { val r = super.read(); if (r >= 0 && ++done % 262144 == 0L) stage(name, done, total); return r }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val r = super.read(b, off, len)
                    if (r > 0) { val before = done; done += r; if (before / 262144 != done / 262144) stage(name, done, total) }
                    return r
                }
                // skip must go through read() so the digest sees every byte
                override fun skip(n: Long): Long {
                    val buf = ByteArray(minOf(n, 65536L).toInt().coerceAtLeast(1)); var left = n
                    while (left > 0) { val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (r < 0) break; left -= r }
                    return n - left
                }
            }
            val out = BufferedInputStream(counting, 262144).use { inp ->
                val m = GeoPack.extract(inp, tags.toSet())
                // drain the rest for the checksum
                val buf = ByteArray(65536); while (inp.read(buf) >= 0) { }
                m
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != null && sha != got) throw IllegalStateException("$name: SHA-256 не совпал")
            if (out.isEmpty()) throw IllegalStateException("$name: нужных списков нет")
            return out
        } finally { c.disconnect() }
    }

    fun updateRf(ctx: Context, stage: (String, Long, Long) -> Unit = { _, _, _ -> }): RfStatus {
        val site = cut("geosite.dat", TrafficPresets.RF_SITE_TAGS, stage)
        val ip = cut("geoip.dat", TrafficPresets.RF_IP_TAGS, stage)
        writeAtomically(File(ctx.filesDir, TrafficPresets.RF_SITE), GeoPack.list(site.values))
        writeAtomically(File(ctx.filesDir, TrafficPresets.RF_IP), GeoPack.list(ip.values))
        File(ctx.filesDir, RF_META).writeText(JSONObject().put("updated", System.currentTimeMillis())
            .put("site", JSONArray(site.keys.toList())).put("ip", JSONArray(ip.keys.toList())).toString())
        File(ctx.filesDir, BLOCK_KEY).delete()
        return rfStatus(ctx)
    }

    fun deleteRf(ctx: Context) {
        listOf(TrafficPresets.RF_SITE, TrafficPresets.RF_IP, RF_META, BLOCK_KEY).forEach { File(ctx.filesDir, it).delete() }
    }

    /** 2.0.15: ABS ad blocking replaced the AdGuard file / compiled list — remove what 1.0.40–2.0.14 left in filesDir. */
    fun cleanupLegacy(ctx: Context) {
        for (n in listOf(ADGUARD, ADGUARD_META, BLOCK_KEY, "antibs-block.dat")) try { File(ctx.filesDir, n).delete() } catch (_: Throwable) {}
    }
}
