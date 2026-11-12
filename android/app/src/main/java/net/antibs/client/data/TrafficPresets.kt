package net.antibs.client.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 1.0.40: «Правила трафика» — one-tap routing presets (idea from Etonify, GPL-3.0-or-later):
 * «.RU без VPN», «Нейросети через VPN», «Соцсети через VPN». Rules apply to the app's user inbounds only and are
 * placed on top of the config (built and panel/custom JSON alike). A preset replaces the routing profile.
 */
object TrafficPresets {
    const val OFF = "off"
    const val RU = "ru"
    const val AI = "ai"
    const val SOCIAL = "social"
    const val DEFAULT_RU_DNS = "77.88.8.8"

    /** Files cut from runetfreedom/russia-v2ray-rules-dat (see engine/BlockFiles). */
    const val RF_SITE = "antibs-rf-site.dat"
    const val RF_IP = "antibs-rf-ip.dat"
    val RF_SITE_TAGS = listOf("RU-BLOCKED", "RU-AVAILABLE-ONLY-INSIDE", "CATEGORY-ADS-ALL")
    val RF_IP_TAGS = listOf("RU-BLOCKED", "RU-WHITELIST")

    fun title(p: String) = when (p) {
        RU -> ".RU без VPN"
        AI -> "Нейросети через VPN"
        SOCIAL -> "Соцсети через VPN"
        else -> "Не выбрано"
    }

    fun summary(p: String) = when (p) {
        RU -> "Российские сервисы, .ru/.su/.рф и российские IP — напрямую; заблокированное в РФ и всё остальное — через VPN."
        AI -> "ChatGPT, Claude, Gemini, Grok, Perplexity, Copilot и др. — через VPN, остальное напрямую."
        SOCIAL -> "Telegram, YouTube, Instagram, Facebook, X, TikTok, Discord, WhatsApp и др. — через VPN, остальное напрямую."
        else -> ""
    }

    fun active(a: AdvancedSettings) = a.trafficPreset in setOf(RU, AI, SOCIAL)

    /** 2.0.43: servers offered for «.RU» traffic — single runnable Xray-family leaves (no chain / balancer / JSON / auto stub). */
    fun ruViaCandidate(e: ServerEntry): Boolean {
        val p = e.xray ?: return false
        return p.unsupportedReason.isNullOrBlank() && p.protocol !in setOf("chain", "balancer", "custom") &&
            ProtocolSupport.isRunnable(p.protocol) && !AutoSelect.isSubscriptionAuto(e) && !AutoSelect.isJsonAuto(e)
    }

    /** Russian services outside .ru/.su/.рф (from Etonify's curated list + domain-list-community). */
    val RU_SERVICES = listOf("2gis.com", "2gis.kz", "2gis.by", "5post.market", "avito.st", "boosty.to", "donationalerts.com", "giga.chat", "jivo.chat", "jivosite.com", "megamarket.tech", "mradx.net", "okko.tv", "okko.sport", "ozon.com", "ozon.kz", "ozon.by", "ozonusercontent.com", "paywb.com", "perekrestok.com", "sber.pro", "sberbank.com", "tbank-online.com", "tinkoff-group.com", "tamtam.chat", "x5.com", "x5static.net", "youla.io", "zvuk.com", "static-storage.net", "clstorage.net", "turbopages.org", "ya.cc", "yandex.cloud", "yandexgo.com", "yango.com", "mycdn.me", "userapi.com", "sourcecraft.dev", "shedevrum.ai", "emias.info", "naydex.net", "yastat.net", "yandexwebcache.net", "vk.com", "vk.me", "vkuser.net", "vkuservideo.net", "mail.ru", "ok.ru", "max.ru", "oneme.ru")
    val RU_SUFFIXES = listOf("domain:ru", "domain:su", "domain:xn--p1ai")

    val AI_SITES = listOf("geosite:category-ai-!cn", "geosite:openai", "geosite:anthropic", "geosite:google-gemini", "geosite:perplexity", "geosite:xai", "geosite:poe", "geosite:huggingface", "geosite:cursor", "domain:gemini.google.com", "domain:aistudio.google.com", "domain:notebooklm.google.com", "domain:copilot.microsoft.com", "domain:mistral.ai", "domain:character.ai", "domain:midjourney.com", "domain:suno.com", "domain:elevenlabs.io", "domain:grok.com", "domain:x.ai", "domain:claude.ai", "domain:anthropic.com", "domain:chatgpt.com", "domain:openai.com", "domain:oaistatic.com", "domain:oaiusercontent.com", "domain:sora.com")
    val SOCIAL_SITES = listOf("geosite:category-social-media-!cn", "geosite:telegram", "geosite:youtube", "geosite:instagram", "geosite:facebook", "geosite:meta", "geosite:twitter", "geosite:tiktok", "geosite:discord", "geosite:whatsapp", "geosite:linkedin", "geosite:signal", "geosite:reddit", "geosite:threads")
    val SOCIAL_IPS = listOf("geoip:telegram", "geoip:facebook", "geoip:twitter")

    private fun rule(inb: List<String>, key: String, values: List<String>, target: Pair<String, String>) = buildJsonObject {
        put("type", "field")
        putJsonArray("inboundTag") { inb.forEach { add(JsonPrimitive(it)) } }
        putJsonArray(key) { values.forEach { add(JsonPrimitive(it)) } }
        put(target.first, target.second)
        put("ruleTag", "antibs-preset")
    }

    /**
     * Preset rules. [proxy] = the config's normal route («outboundTag»/«balancerTag» to tag), [direct] = freedom tag,
     * [extra] = tags available in the rf files ("site"/"ip"), [bundled] = tags of the bundled geo files (unknown refs dropped).
     */
    fun rules(
        a: AdvancedSettings, inb: List<String>, proxy: Pair<String, String>, direct: String,
        extra: Map<String, Set<String>> = emptyMap(), bundled: Map<String, Set<String>>? = null,
        /** 2.0.43: «.RU без VPN» via a chosen server (route key to tag) instead of [direct]; private ranges stay direct. */
        ruVia: Pair<String, String>? = null,
    ): List<JsonObject> {
        if (!active(a) || inb.isEmpty()) return emptyList()
        val d = "outboundTag" to direct
        fun known(v: String): Boolean {
            val r = GeoDat.ref(v) ?: return true
            return bundled?.get(r.first)?.contains(r.second) ?: true
        }
        val site = extra["site"].orEmpty(); val ip = extra["ip"].orEmpty()
        val out = ArrayList<JsonObject>()
        when (a.trafficPreset) {
            RU -> {
                if ("RU-BLOCKED" in site) out += rule(inb, "domain", listOf("ext:$RF_SITE:ru-blocked"), proxy)
                if ("RU-BLOCKED" in ip) out += rule(inb, "ip", listOf("ext:$RF_IP:ru-blocked"), proxy)
                val ruT = ruVia ?: d
                if (ruVia != null) {
                    // LAN / private stays on the phone's own network; only Russian destinations go via the chosen server
                    listOf("geosite:private").filter(::known).takeIf { it.isNotEmpty() }?.let { out += rule(inb, "domain", it, d) }
                    out += rule(inb, "ip", listOf("geoip:private").filter(::known), d)
                }
                val dom = RU_SUFFIXES + listOf("geosite:category-ru", "geosite:category-gov-ru").filter(::known) +
                    (if (ruVia == null) listOf("geosite:private").filter(::known) else emptyList()) +
                    (if ("RU-AVAILABLE-ONLY-INSIDE" in site) listOf("ext:$RF_SITE:ru-available-only-inside") else emptyList()) +
                    RU_SERVICES.map { "domain:$it" }
                out += rule(inb, "domain", dom, ruT)
                out += rule(inb, "ip", ((if (ruVia == null) listOf("geoip:private", "geoip:ru") else listOf("geoip:ru")).filter(::known) +
                    (if ("RU-WHITELIST" in ip) listOf("ext:$RF_IP:ru-whitelist") else emptyList())), ruT)
            }
            AI, SOCIAL -> {
                val dom = (if (a.trafficPreset == AI) AI_SITES else SOCIAL_SITES).filter(::known)
                out += rule(inb, "domain", dom, proxy)
                if (a.trafficPreset == SOCIAL) SOCIAL_IPS.filter(::known).takeIf { it.isNotEmpty() }?.let { out += rule(inb, "ip", it, proxy) }
                out += rule(inb, "ip", listOf("geoip:private").filter(::known), d)
                out += buildJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { inb.forEach { add(JsonPrimitive(it)) } }
                    put("network", "tcp,udp"); put("outboundTag", direct); put("ruleTag", "antibs-preset")
                }
            }
        }
        return out.filter { r -> (r["domain"] ?: r["ip"])?.let { it.toString() != "[]" } ?: true }
    }

    /** Queries to the RU DNS server itself go direct (Xray's internal DNS traffic has no user inbound tag). */
    fun ruDnsRule(a: AdvancedSettings, direct: String): JsonObject? {
        if (a.trafficPreset != RU) return null
        val addr = a.ruDns.trim().removePrefix("udp://").ifEmpty { DEFAULT_RU_DNS }
        val host = addr.substringBefore(':').takeIf { addr.count { it == ':' } <= 1 } ?: return null
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) return null
        val port = addr.substringAfter(':', "53")
        return buildJsonObject {
            put("type", "field"); putJsonArray("ip") { add(JsonPrimitive(host)) }; put("port", port)
            put("outboundTag", direct); put("ruleTag", "antibs-preset")
        }
    }

    /** DNS server for .ru/.su/.рф when the RU preset is on (built configs only). */
    fun ruDnsServer(a: AdvancedSettings): JsonObject? {
        if (a.trafficPreset != RU) return null
        val addr = a.ruDns.trim().removePrefix("udp://").ifEmpty { DEFAULT_RU_DNS }
        return buildJsonObject {
            put("address", addr)
            putJsonArray("domains") { RU_SUFFIXES.forEach { add(JsonPrimitive(it)) } }
            put("skipFallback", true)
        }
    }
}
