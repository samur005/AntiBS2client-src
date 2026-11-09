package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 2.0.15: «Блокировка рекламы» — port of ABS 3.1.27 (ConfigBuilder adBlock + AdCategories + AdBlockCounter + DNS blocker).
 *  • In the tunnel: geosite:category-ads-all + ABS ad hosts → blackhole «block-ads» (its own tag, so every hit is one
 *    Xray access-log line → counter), QUIC (UDP 443) → «block» so apps fall back to TCP where the domain is sniffed.
 *  • Categories / telemetry: geosite categories → «block-ads», each switch on its own.
 *  • ДНС-блокировщик: an ad-blocking resolver replaces the remote DNS (not counted — «н/д»).
 */
object AdBlock {
    const val TAG = "block-ads"
    const val BLOCK_TAG = "block"
    const val DIRECT_FALLBACK_TAG = "antibs-direct"
    const val ADS_GEOSITE = "geosite:category-ads-all"
    /** Inbounds that carry user traffic; probe-*, sb-bridge-*, dns-in and api stay unfiltered. */
    val USER_INBOUNDS = setOf("socks", "http", "tun")
    private val OUR_RULE_TAGS = setOf("antibs-adblock", "antibs-adcats", "antibs-quic", "antibs-allow", "antibs-stun", "antibs-preset")

    /** ABS ConfigBuilder.AdHosts. */
    val AD_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adservice.google.com", "app-measurement.com", "amazon-adsystem.com", "adnxs.com", "adsrvr.org",
        "bidswitch.net", "rubiconproject.com", "pubmatic.com", "casalemedia.com", "openx.net", "smartadserver.com", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "sharethrough.com", "33across.com", "teads.tv", "media.net", "adform.net",
        "serving-sys.com", "flashtalking.com", "revcontent.com", "mgid.com", "zedo.com", "advertising.com", "yieldmo.com",
        "gumgum.com", "indexww.com", "moatads.com", "adsafeprotected.com", "doubleverify.com", "scorecardresearch.com",
        "quantserve.com", "demdex.net", "omtrdc.net", "everesttech.net", "2o7.net", "bluekai.com", "krxd.net", "agkn.com",
        "exelator.com", "rlcdn.com", "crwdcntrl.net", "mc.yandex.ru", "clarity.ms", "hotjar.com", "fullstory.com",
        "mouseflow.com", "inspectlet.com", "optimizely.com", "mixpanel.com", "amplitude.com", "segment.io", "segment.com",
        "branch.io", "appsflyer.com", "adjust.com", "kochava.com", "singular.net", "tenjin.io", "flurry.com", "braze.com",
        "clevertap.com", "leanplum.com", "applovin.com", "ironsrc.com", "supersonicads.com", "unityads.unity3d.com", "vungle.com",
        "chartboost.com", "adcolony.com", "inmobi.com", "mopub.com", "tapjoy.com", "startappservice.com", "smaato.com",
        "pubnative.net", "mobfox.com", "analytics.tiktok.com", "ads.tiktok.com", "ads.linkedin.com", "ads.yahoo.com", "3lift.com",
        "adition.com", "adzerk.net", "bidr.io", "contextweb.com", "districtm.io", "emxdgt.com", "lijit.com", "mathtag.com",
        "sitescout.com", "spotxchange.com", "stickyadstv.com", "tremorhub.com", "undertone.com", "zemanta.com", "sonobi.com",
        "improvedigital.com", "adsymptotic.com", "themoneytizer.com", "adthrive.com", "mediavine.com", "sovrn.com", "onetag.io",
        "adkernel.com", "adpushup.com", "yieldlab.net", "adtelligent.com", "loopme.com", "smartyads.com", "adhigh.net",
        "adriver.ru", "adspirit.de", "rtbhouse.com", "onaudience.com", "id5-sync.com", "adlooxtracking.com", "adsco.re",
        "bttrack.com", "tracking-protection.com", "trackjs.com", "krux.net", "parsely.com", "chartbeat.com", "chartbeat.net",
        "dpm.demdex.net", "sc-static.net", "adsmoloco.com", "liftoff.io", "fyber.com", "digitalturbine.com", "pangle.io",
        "pangolin-sdk-toutiao.com", "mintegral.com", "adsgreat.com", "zqtk.net", "trafficjunky.com", "exoclick.com",
        "juicyads.com", "popads.net", "propellerads.com", "adsterra.com", "hilltopads.net", "clickadu.com", "adcash.com",
        "trafficstars.com", "ad.gt", "adnium.com", "adsupply.com", "bidvertiser.com", "infolinks.com", "smartlook.com", "heap.io",
        "heapanalytics.com", "pendo.io", "mouseflow.net", "matomo.cloud", "plausible.io", "statcounter.com", "histats.com",
        "googleads.g.doubleclick.net", "pagead2.googlesyndication.com", "securepubads.g.doubleclick.net",
        "adservice.google.co.uk", "ad.doubleclick.net", "stats.g.doubleclick.net", "cdn.branch.io", "api2.branch.io",
        "metrics.apple.com", "ads.mopub.com", "yektanet.com", "yn-cdn.com", "sabavision.com", "clickyab.com", "adivery.com",
        "tapsell.ir", "tapsell.com", "tapsell.net", "adad.ir", "adro.co", "mediaad.org", "anetwork.ir", "netbina.com",
        "adnegah.net", "zarpop.com", "metrix.ir", "adtrace.io", "pushe.co", "najva.com", "chabok.io", "webgozar.com",
        "webgozar.ir", "ads.aparat.com", "biz.varzesh3.com", "biz-cdn.varzesh3.com", "adnxs-simple.com", "adnexus.net",
        "advertserve.com", "adtech.de", "adtechus.com", "atdmt.com", "bat.bing.com", "clicktale.net", "decibelinsights.com",
        "quantcount.com", "adroll.com", "adroll.net", "rfihub.com", "turn.com", "mediamath.com", "adsymptotic.net", "simpli.fi",
        "eyeota.net", "semasio.net", "weborama.fr", "liadm.com", "tapad.com", "drawbridge.com", "nexac.com", "owneriq.net",
        "addthis.com", "sharethis.com", "po.st", "disqusads.com", "adform.com", "adotmob.com", "adux.com", "smartclip.net",
        "spotx.tv", "spotxcdn.com", "vidoomy.com", "viralize.com", "connatix.com", "primis.tech", "playwire.com", "adsninja.ca",
        "ezoic.net", "ezojs.com", "adsense.com", "adperium.com", "trackingsoft.com", "trkn.us", "tremorvideo.com",
        "yieldoptimizer.com", "onesignal.com", "pushwoosh.com", "pushengage.com", "izooto.com", "truepush.com", "webpushr.com",
        "sendpulse.com", "vwo.com", "crazyegg.com", "luckyorange.com", "usabilla.com", "qualtrics.com", "surveymonkey.com",
        "typekit.net", "kissmetrics.com", "woopra.com", "gosquared.com", "keen.io", "countly.com",
    )

    data class Cat(val id: String, val title: String, val geosite: String, val telemetry: Boolean)
    /** ABS AdCategories. */
    val CATS = listOf(
        Cat("trackers", "Трекеры и аналитика", "geosite:category-telemetry", false),
        Cat("adult", "Сайты для взрослых", "geosite:category-porn", false),
        Cat("tm_xiaomi", "Xiaomi / MIUI", "geosite:xiaomi@telemetry", true),
        Cat("tm_windows", "Windows", "geosite:win-spy", true),
        Cat("tm_google", "Google (Crashlytics, Firebase)", "geosite:google@telemetry", true),
    )
    val CAT_IDS = CATS.map { it.id }.toSet()

    data class DnsVariant(val kind: String, val url: String)
    data class DnsPreset(val id: String, val name: String, val variants: List<DnsVariant>)
    /** ABS DnsPresets.AD_BLOCK. */
    val DNS_PRESETS = listOf(
        DnsPreset("adg", "AdGuard", listOf(DnsVariant("UDP", "94.140.14.14"), DnsVariant("DoH", "https://dns.adguard-dns.com/dns-query"), DnsVariant("DoQ", "quic://dns.adguard-dns.com"))),
        DnsPreset("adg_fam", "AdGuard (семейный)", listOf(DnsVariant("UDP", "94.140.14.15"), DnsVariant("DoH", "https://family.adguard-dns.com/dns-query"), DnsVariant("DoQ", "quic://family.adguard-dns.com"))),
        DnsPreset("mullvad_ad", "Mullvad (без рекламы)", listOf(DnsVariant("DoH", "https://adblock.dns.mullvad.net/dns-query"))),
        DnsPreset("ctrld_ads", "ControlD (реклама и вредоносные)", listOf(DnsVariant("UDP", "76.76.2.2"), DnsVariant("DoH", "https://freedns.controld.com/p2"), DnsVariant("DoQ", "quic://p2.freedns.controld.com"))),
    )
    private fun norm(u: String) = u.trim().lowercase().removePrefix("udp://").removeSuffix("/")
    fun findDns(url: String): Pair<DnsPreset, DnsVariant>? {
        if (url.isBlank()) return null
        val n = norm(url)
        for (p in DNS_PRESETS) for (v in p.variants) if (norm(v.url) == n) return p to v
        return null
    }
    /** udp ip[:port], https://, quic:// (what Xray's DNS takes). */
    fun dnsUrlOk(u: String): Boolean {
        val s = u.trim()
        if (s.startsWith("https://") || s.startsWith("quic://") || s.startsWith("https+local://")) return s.length > 10 && !s.contains(' ')
        return Regex("""^(udp://)?\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?$""").matches(s)
    }
    /** The ad-blocking resolver, when on. */
    fun dnsUrl(a: AdvancedSettings): String? = a.adDnsUrl.trim().takeIf { a.adDnsOn && it.isNotEmpty() && dnsUrlOk(it) }?.removePrefix("udp://")

    /** Anything routed to «block-ads»? (= the counter is meaningful) */
    fun enabled(a: AdvancedSettings): Boolean = a.blockAds || a.adCats.any { it in CAT_IDS }

    /** Is a geosite ref present in the bundled file? null [bundled] = don't check. */
    private fun known(ref: String, bundled: Map<String, Set<String>>?): Boolean {
        val site = bundled?.get("site") ?: return true
        return ref.removePrefix("geosite:").substringBefore('@').uppercase() in site
    }

    fun catDomains(a: AdvancedSettings, bundled: Map<String, Set<String>>? = null): List<String> =
        CATS.filter { it.id in a.adCats }.map { it.geosite }.filter { known(it, bundled) }

    fun adDomains(bundled: Map<String, Set<String>>? = null): List<String> {
        // 2.0.30: keep geosite:category-ads-all whenever the bundled index is unknown; only drop it when
        // the index is present and clearly lacks the tag (otherwise Xray would refuse to start).
        val geo = when {
            bundled == null -> listOf(ADS_GEOSITE)
            known(ADS_GEOSITE, bundled) -> listOf(ADS_GEOSITE)
            else -> emptyList()
        }
        return geo + AD_HOSTS.map { "domain:$it" }
    }

    private fun inbArr(inb: List<String>) = JsonArray(inb.map { JsonPrimitive(it) })

    /** ABS order: categories → «block-ads», ads → «block-ads», UDP 443 → «block». For the user-facing inbounds only. */
    fun leadingRules(a: AdvancedSettings, inb: List<String>, blockTag: String = BLOCK_TAG, bundled: Map<String, Set<String>>? = null): List<JsonObject> {
        if (inb.isEmpty()) return emptyList()
        val out = ArrayList<JsonObject>()
        catDomains(a, bundled).takeIf { it.isNotEmpty() }?.let { d ->
            out += buildJsonObject {
                put("type", "field"); put("inboundTag", inbArr(inb))
                putJsonArray("domain") { d.forEach { add(JsonPrimitive(it)) } }
                put("outboundTag", TAG); put("ruleTag", "antibs-adcats")
            }
        }
        if (a.blockAds) {
            out += buildJsonObject {
                put("type", "field"); put("inboundTag", inbArr(inb))
                putJsonArray("domain") { adDomains(bundled).forEach { add(JsonPrimitive(it)) } }
                put("outboundTag", TAG); put("ruleTag", "antibs-adblock")
            }
            out += buildJsonObject {
                put("type", "field"); put("inboundTag", inbArr(inb))
                put("network", "udp"); put("port", "443"); put("outboundTag", blockTag); put("ruleTag", "antibs-quic")
            }
        }
        return out
    }

    private fun str(o: Any?, k: String) = ((o as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull

    /**
     * A full (panel / custom) Xray config: our rules prepended to routing.rules for the app's user inbounds
     * (ad block → traffic preset), «block-ads» / blackhole / freedom outbounds reused or added, sniffing forced on the
     * user inbounds while blocking (domain rules need it). Nothing else changes; idempotent.
     */
    fun patch(
        cfg: JsonObject, a: AdvancedSettings,
        geoExtra: Map<String, Set<String>> = emptyMap(), bundled: Map<String, Set<String>>? = null,
    ): JsonObject {
        val preset = TrafficPresets.active(a)
        if (!enabled(a) && !preset) return cfg
        val inbs = (cfg["inbounds"] as? JsonArray).orEmpty()
        val inTags = inbs.mapNotNull { str(it, "tag") }.filter { it in USER_INBOUNDS }
        if (inTags.isEmpty()) return cfg
        var outs = (cfg["outbounds"] as? JsonArray).orEmpty().toList()
        val existing = outs.firstOrNull { str(it, "tag") == BLOCK_TAG }
        val blockTag = if (existing == null || str(existing, "protocol")?.lowercase() == "blackhole") BLOCK_TAG else TAG
        val routing = (cfg["routing"] as? JsonObject).orEmpty()
        val oldRules = (routing["rules"] as? JsonArray).orEmpty().filterNot { str(it, "ruleTag") in OUR_RULE_TAGS }
        val catchAll = oldRules.firstOrNull { r ->
            val o = r as? JsonObject ?: return@firstOrNull false
            (o["balancerTag"] != null || o["outboundTag"] != null) && o.keys.all { it in setOf("type", "network", "balancerTag", "outboundTag", "ruleTag") }
        }
        val proxy: Pair<String, String> = when {
            catchAll != null && str(catchAll, "balancerTag") != null -> "balancerTag" to str(catchAll, "balancerTag")!!
            catchAll != null && str(catchAll, "outboundTag") != null -> "outboundTag" to str(catchAll, "outboundTag")!!
            else -> "outboundTag" to (str(outs.firstOrNull(), "tag") ?: "proxy")
        }
        val lead = leadingRules(a, inTags, blockTag, bundled)
        var directTag = "direct"
        val presetRules = if (!preset) emptyList() else {
            val freedom = outs.firstOrNull { str(it, "tag") == "direct" && str(it, "protocol")?.lowercase() == "freedom" }
                ?: outs.firstOrNull { str(it, "protocol")?.lowercase() == "freedom" && str(it, "tag") != null }
            directTag = str(freedom, "tag") ?: if (outs.any { str(it, "tag") == "direct" }) DIRECT_FALLBACK_TAG else "direct"
            TrafficPresets.rules(a, inTags, proxy, directTag, geoExtra, bundled)
        }
        val added = lead + presetRules
        if (added.isEmpty()) return cfg
        if (added.any { str(it, "outboundTag") == TAG } && outs.none { str(it, "tag") == TAG }) outs = outs + blackhole(TAG)
        if (added.any { str(it, "outboundTag") == blockTag } && outs.none { str(it, "tag") == blockTag }) outs = outs + blackhole(blockTag)
        if (presetRules.isNotEmpty() && outs.none { str(it, "tag") == directTag }) outs = outs + buildJsonObject {
            put("tag", directTag); put("protocol", "freedom")
        }
        var out = JsonObject(cfg + ("outbounds" to JsonArray(outs)))
        if (lead.isNotEmpty()) out = JsonObject(out + ("inbounds" to JsonArray(inbs.map { i ->
            val o = i as? JsonObject ?: return@map i
            if (str(o, "tag") !in USER_INBOUNDS) o else JsonObject(o + ("sniffing" to sniffingOn(o["sniffing"] as? JsonObject))) })))
        out = JsonObject(out + ("routing" to JsonObject(routing + ("rules" to JsonArray(added + oldRules)))))
        return out
    }

    /** ABS: blocking needs sniffing (http / tls / quic) so domain rules see the host. */
    fun sniffingOn(s: JsonObject?): JsonObject {
        val cur = s.orEmpty()
        val dest = ((cur["destOverride"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()).toMutableList()
        listOf("http", "tls", "quic").forEach { if (it !in dest) dest += it }
        return JsonObject(cur + ("enabled" to JsonPrimitive(true)) + ("destOverride" to JsonArray(dest.map { JsonPrimitive(it) })))
    }

    fun blackhole(tag: String): JsonObject = buildJsonObject {
        put("tag", tag); put("protocol", "blackhole"); putJsonObject("settings") { putJsonObject("response") { put("type", "http") } }
    }
}

/**
 * 2.0.15: ABS AdBlockCounter — real count of requests sent to [AdBlock.TAG]. Xray writes one access-log line per
 * routed connection («… [tun -> block-ads]»); the service drains the log and adds the hits. Nothing is estimated:
 * DNS-level blocking never reaches Xray → «н/д».
 */
object AdBlockCounter {
    const val LOG_NAME = "adblock_access.log"
    const val COUNTS_NAME = "adblock_counts.json"
    const val MAX_LOG = 256 * 1024L
    /**
     * Xray 26 access lines look like «… accepted tcp:host:443 [socks -> block-ads]» or «[tun >> block-ads]».
     * Also accept «outbound/block-ads» / bare «->block-ads» so a format tweak does not zero the counter.
     */
    private val HIT = Regex("""((->|>>)\s*block-ads\]?|outbound/block-ads|\[.*block-ads\])""", RegexOption.IGNORE_CASE)

    fun count(text: String): Int = text.lineSequence().count { HIT.containsMatchIn(it) }

    /** Does this Xray config route anything to «block-ads»? */
    fun configCounts(cfg: JsonObject): Boolean {
        val outs = (cfg["outbounds"] as? JsonArray).orEmpty()
        val has = outs.any { ((it as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull == AdBlock.TAG }
        val rules = ((cfg["routing"] as? JsonObject)?.get("rules") as? JsonArray).orEmpty()
        return has && rules.any { ((it as? JsonObject)?.get("outboundTag") as? JsonPrimitive)?.contentOrNull == AdBlock.TAG }
    }
    fun configCounts(json: String): Boolean = runCatching { configCounts(Json.parseToJsonElement(json).jsonObject) }.getOrDefault(false)

    /**
     * 2.0.30: always write a dedicated [path] for the counter (never share Логи's xray-access.log —
     * LogTailer truncates that file). If loglevel is «none», bump it to «warning» so Xray actually
     * writes the access file (confirmed: loglevel none → no access log on Xray 26).
     */
    fun withAccessLog(cfg: JsonObject, path: String): Pair<JsonObject, String?> {
        if (!configCounts(cfg)) return cfg to null
        val log = (cfg["log"] as? JsonObject).orEmpty().toMutableMap()
        val lvl = (log["loglevel"] as? JsonPrimitive)?.contentOrNull
        if (lvl.isNullOrBlank() || lvl == "none") log["loglevel"] = JsonPrimitive("warning")
        log["access"] = JsonPrimitive(path)
        return JsonObject(cfg + ("log" to JsonObject(log))) to path
    }

    data class Counts(val day: String = "", val today: Long = 0, val total: Long = 0, val measured: Boolean = false) {
        fun add(n: Int, nowDay: String): Counts = Counts(nowDay, (if (day == nowDay) today else 0) + n, total + n, true)
        fun todayFor(nowDay: String): Long = if (day == nowDay) today else 0
    }

    fun dayKey(ms: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(ms))

    fun show(measured: Boolean, v: Long, na: String = "н/д"): String = if (measured) v.toString() else na

    /** Reads what Xray appended since [offset]; returns (new offset, blocked). Truncates our own file past [MAX_LOG]. */
    fun drain(f: java.io.File, offset: Long, truncate: Boolean = true): Pair<Long, Int> {
        if (!f.exists()) return 0L to 0
        val len = f.length()
        val from = if (len < offset) 0L else offset
        if (len == from) return from to 0
        return java.io.RandomAccessFile(f, "rw").use { r ->
            r.seek(from)
            val buf = ByteArray((len - from).coerceAtMost(4L * 1024 * 1024).toInt()); r.readFully(buf)
            val lastNl = buf.indexOfLast { it == '\n'.code.toByte() }
            if (lastNl < 0) return@use from to 0
            val c = count(String(buf, 0, lastNl + 1, Charsets.UTF_8))
            val next = from + lastNl + 1
            if (truncate && next >= MAX_LOG && next == len) { r.setLength(0); 0L to c } else next to c
        }
    }

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    @kotlinx.serialization.Serializable private data class Stored(val day: String = "", val today: Long = 0, val total: Long = 0, val measured: Boolean = false)
    fun load(f: java.io.File): Counts = runCatching {
        val s = json.decodeFromString(Stored.serializer(), f.readText()); Counts(s.day, s.today, s.total, s.measured)
    }.getOrDefault(Counts())
    fun save(f: java.io.File, c: Counts) = synchronized(lock) {
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored(c.day, c.today, c.total, c.measured)))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
    fun add(f: java.io.File, n: Int, day: String = dayKey()) = synchronized(lock) { save(f, load(f).add(n, day)) }
    fun markMeasured(f: java.io.File) = synchronized(lock) { load(f).let { if (!it.measured) save(f, it.copy(measured = true)) } }
    fun reset(f: java.io.File) = save(f, Counts())
}
