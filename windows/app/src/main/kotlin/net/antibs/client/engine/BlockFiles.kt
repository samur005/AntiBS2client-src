package net.antibs.client.engine

import android.content.Context
import net.antibs.client.data.AdBlock
import net.antibs.client.data.AdBlockCompiler
import net.antibs.client.data.AdGuardFilter
import net.antibs.client.data.AdvancedSettings
import net.antibs.client.data.GeoPack
import net.antibs.client.data.TrafficPresets
import net.antibs.client.data.UserDomains
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
 * AdGuard DNS filter, runetfreedom lists (cut to the needed tags), and the compiled «antibs-block.dat».
 */
object BlockFiles {
    private const val ADGUARD = "adguard-dns.txt"
    private const val ADGUARD_META = "adguard-dns.json"
    private const val RF_META = "antibs-rf.json"
    private const val BLOCK_KEY = "antibs-block.json"
    private const val RF_BASE = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/"

    data class AdGuardStatus(val ready: Boolean, val updated: Long = 0, val blocked: Int = 0, val allowed: Int = 0)
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

    fun adGuardStatus(ctx: Context): AdGuardStatus {
        val f = File(ctx.filesDir, ADGUARD); val m = File(ctx.filesDir, ADGUARD_META)
        if (f.length() <= 0 || !m.exists()) return AdGuardStatus(false)
        return try {
            val j = JSONObject(m.readText())
            AdGuardStatus(true, j.optLong("updated"), j.optInt("blocked"), j.optInt("allowed"))
        } catch (_: Exception) { AdGuardStatus(false) }
    }

    fun downloadAdGuard(ctx: Context, progress: (Long, Long) -> Unit = { _, _ -> }): AdGuardStatus {
        val c = open(AdGuardFilter.URL)
        val bytes = try {
            val total = c.contentLengthLong
            val o = ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(65536); var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    done += n
                    if (done > AdGuardFilter.MAX_BYTES) throw IllegalStateException("фильтр слишком большой")
                    o.write(buf, 0, n); progress(done, total)
                }
            }
            o.toByteArray()
        } finally { c.disconnect() }
        val p = AdGuardFilter.parse(String(bytes, Charsets.UTF_8))
        if (p.block.size < 1000) throw IllegalStateException("в фильтре слишком мало доменов (${p.block.size})")
        writeAtomically(File(ctx.filesDir, ADGUARD), bytes)
        val st = AdGuardStatus(true, System.currentTimeMillis(), p.block.size, p.allow.size)
        File(ctx.filesDir, ADGUARD_META).writeText(JSONObject().put("updated", st.updated).put("blocked", st.blocked).put("allowed", st.allowed).toString())
        return st
    }

    fun deleteAdGuard(ctx: Context) {
        File(ctx.filesDir, ADGUARD).delete(); File(ctx.filesDir, ADGUARD_META).delete()
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

    /** category-ads-all entry: fresh one from the rf file, else the bundled geosite.dat. */
    private fun adsEntry(ctx: Context, rf: RfStatus): ByteArray? {
        val f = if (rf.ready && "CATEGORY-ADS-ALL" in rf.site) File(ctx.filesDir, TrafficPresets.RF_SITE) else File(ctx.filesDir, "geosite.dat")
        if (f.length() <= 0) return null
        return f.inputStream().buffered(262144).use { GeoPack.extract(it, setOf("CATEGORY-ADS-ALL")) }["CATEGORY-ADS-ALL"]
    }

    /**
     * What the block rule uses for this connection. Without AdGuard and whitelist — the plain lists (geosite swapped
     * for the fresh rf copy when present). Otherwise one compiled file, cached by its inputs.
     */
    fun prepare(ctx: Context, a: AdvancedSettings, log: (String) -> Unit = {}): AdBlock.Prepared? {
        if (!AdBlock.enabled(a)) return null
        val rf = rfStatus(ctx)
        val agFile = File(ctx.filesDir, ADGUARD)
        val ag = a.adGuard && agFile.length() > 0
        if (a.adGuard && !ag) log("блокировка: фильтр AdGuard ещё не скачан — пропущен")
        val base = AdBlock.defaultPrepared(a)
        val rfAds = rf.ready && "CATEGORY-ADS-ALL" in rf.site
        if (!ag && a.allowCustom.isEmpty()) {
            if (!rfAds || !a.blockAds) return base
            return base.copy(block = base.block.map { if (it == AdBlock.ADS_GEOSITE) "ext:${TrafficPresets.RF_SITE}:category-ads-all" else it })
        }
        val adsSrc = if (rfAds) File(ctx.filesDir, TrafficPresets.RF_SITE) else File(ctx.filesDir, "geosite.dat")
        val key = JSONObject().put("v", 1)
            .put("ads", if (a.blockAds) "${adsSrc.name}:${adsSrc.length()}:${adsSrc.lastModified()}" else "")
            .put("ag", if (ag) "${agFile.length()}:${agFile.lastModified()}" else "")
            .put("tr", a.blockTrackers).put("custom", JSONArray(a.blockCustom)).put("allow", JSONArray(a.allowCustom)).toString()
        val out = File(ctx.filesDir, AdBlockCompiler.FILE); val keyFile = File(ctx.filesDir, BLOCK_KEY)
        return try {
            val cached = if (keyFile.exists() && out.length() > 0) JSONObject(keyFile.readText()) else null
            if (cached != null && cached.optString("key") == key) {
                val allow = cached.optJSONArray("allow")?.let { j -> (0 until j.length()).map { j.getString(it) } } ?: emptyList()
                log("блокировка: список ${cached.optInt("count")} доменов (кэш)")
                return AdBlock.Prepared(listOf(AdBlockCompiler.REF), base.dns, allow)
            }
            val t0 = System.currentTimeMillis()
            val lists = ArrayList<Collection<Pair<Int, String>>>()
            if (a.blockAds) {
                val e = adsEntry(ctx, rf) ?: throw IllegalStateException("нет category-ads-all")
                lists += GeoPack.domains(e)
                lists += AdBlock.ADS_EXTRA.map { GeoPack.DOMAIN to it.removePrefix("domain:") }
            }
            if (a.blockTrackers) lists += AdBlock.TRACKER_DOMAINS.map { GeoPack.DOMAIN to it.removePrefix("domain:") }
            var agAllow: List<Pair<Int, String>> = emptyList()
            if (ag) {
                val p = AdGuardFilter.parse(agFile.readText())
                lists += p.block.map { GeoPack.DOMAIN to it }
                agAllow = p.allow.map { GeoPack.DOMAIN to it }
            }
            lists += UserDomains.parse(a.blockCustom)
            val r = AdBlockCompiler.compile(lists, UserDomains.parse(a.allowCustom) + agAllow)
            writeAtomically(out, r.bytes)
            keyFile.writeText(JSONObject().put("key", key).put("count", r.count).put("allow", JSONArray(r.allowOverrides)).toString())
            log("блокировка: собран список ${r.count} доменов" + (if (ag) " (с AdGuard)" else "") +
                (if (r.removed > 0) ", исключено ${r.removed}" else "") + " за ${System.currentTimeMillis() - t0} мс")
            AdBlock.Prepared(listOf(AdBlockCompiler.REF), base.dns, r.allowOverrides)
        } catch (e: Throwable) {
            log("блокировка: не удалось собрать список (${e.message}) — встроенные списки")
            base
        }
    }
}
