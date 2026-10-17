package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 1.0.19: subscription «🎲Автовыбор сервера».
 *
 * Remnawave's link (base64) subscription has no balancer format, so its auto-select host arrives as a stub link —
 * e.g. `ss://2022-blake3-aes-256-gcm:…@web.max.ru:1234#🇪🇺 🎲Автовыбор сервера` — that only means something in the
 * panel's JSON (Happ) template. 1.0.18 showed it again but connected to it as a plain Shadowsocks host, so the
 * tunnel never carried traffic. Now such an entry is turned into a client-side Xray balancer (observatory +
 * leastPing) over the other hosts of the same subscription.
 */
object AutoSelect {
    const val LABEL = "Автовыбор"
    const val STRATEGY = "leastping"

    /** Same name rules as [net.antibs.client.ui.UiLogic.isAutoSelectEntry]. */
    fun nameIsAuto(name: String): Boolean {
        val flag = LinkParser.flagOf(name)
        val base = (if (flag != null) name.replace(flag, "").trim().ifEmpty { name } else name)
        val n = base.lowercase().replace('-', ' ').replace('_', ' ').trim()
        if (n.contains("автовыбор") || n.contains("авто выбор") || n.contains("autoselect") || n.contains("auto select")) return true
        return n == "auto" || n.startsWith("auto ")
    }

    /** A subscription's auto-select stub (not a user-built «Балансировщик», not a host in «Серверы»). */
    fun isSubscriptionAuto(s: ServerEntry): Boolean {
        val sub = s.subscriptionId ?: return false
        if (sub == ProtocolSupport.MANUAL_SUB_ID) return false
        if (s.engine != EngineKind.XRAY) return false
        val p = s.xray ?: return false
        if (p.protocol in setOf("balancer", "chain", "custom")) return false
        return nameIsAuto(s.name)
    }

    /**
     * Balancer members: the other hosts of the same subscription that plain Xray can dial by itself. Other
     * auto-select stubs, chains/balancers/custom JSON, unsupported hosts and hosts that need a helper engine
     * (sing-box / mieru / mihomo) or qWDTT are left out.
     */
    fun members(auto: ServerEntry, all: List<ServerEntry>, exclude: Set<String> = emptySet()): List<ServerEntry> = all.filter { s ->
        val p = s.xray
        s.id !in exclude && s.id != auto.id && s.subscriptionId == auto.subscriptionId && s.engine == EngineKind.XRAY && p != null &&
            !nameIsAuto(s.name) && p.unsupportedReason.isNullOrBlank() &&
            p.protocol !in setOf("balancer", "chain", "custom") && ProtocolSupport.isRunnable(p.protocol) &&
            ProtocolSupport.engineOf(p) == ProtocolSupport.ENGINE_XRAY
    }

    /** The entry the engine should actually run: a leastPing balancer for an auto-select stub, else [entry] itself. */
    fun resolve(entry: ServerEntry, all: List<ServerEntry>, exclude: Set<String> = emptySet()): ServerEntry {
        if (!isSubscriptionAuto(entry)) return entry
        val ids = members(entry, all, exclude).map { it.id }
        if (ids.isEmpty()) throw IllegalStateException("В подписке нет серверов для автовыбора")
        val remarks = entry.xray?.remarks?.ifBlank { null } ?: entry.name
        return entry.copy(xray = XrayProfile(remarks = remarks, protocol = "balancer", server = "", port = 0, network = "tcp",
            balancerIds = ids, balancerStrategy = STRATEGY))
    }

    // ── 1.0.34: the panel's own auto-select (Remnawave «Balanser» Xray JSON template) ─────────────────────────────

    /** The stub runs the panel's Xray JSON as-is: protocol "custom" whose config has routing.balancers. */
    fun isJsonAuto(s: ServerEntry): Boolean {
        val p = s.xray ?: return false
        return p.protocol == "custom" && nameIsAuto(s.name) && balancerTagOf(p.customJson) != null
    }

    /** Stub (link list) or panel JSON — any subscription auto-select row. */
    fun isAnyAuto(s: ServerEntry): Boolean = isSubscriptionAuto(s) || isJsonAuto(s)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Tag of the first routing balancer of an Xray JSON config, null when there is none. */
    fun balancerTagOf(cfgJson: String?): String? {
        if (cfgJson.isNullOrBlank() || !cfgJson.contains("balancers")) return null
        val o = try { json.parseToJsonElement(cfgJson) as? JsonObject } catch (_: Exception) { null } ?: return null
        return balancerTagOf(o)
    }

    fun balancerTagOf(cfg: JsonObject): String? {
        val bals = (cfg["routing"] as? JsonObject)?.get("balancers") as? JsonArray ?: return null
        return bals.firstNotNullOfOrNull { (it as? JsonObject)?.s("tag")?.ifBlank { null } }
    }

    private val PLUMBING = setOf("freedom", "blackhole", "dns", "loopback")

    /** An outbound without its tag (and the outbound-level remarks some panels add) — for matching hosts. */
    private fun key(o: JsonObject): String = JsonObject(o - "tag" - "remarks").toString()

    /**
     * Outbound tag → host name of the auto-select config: each balancer outbound is matched with the single-host
     * config of the same JSON answer that has the identical outbound («🇷🇺→🇩🇪 Германия»). Outbounds with no config
     * of their own (hosts hidden in the panel, e.g. Trojan) get «<flag> <Страна> <Protocol>» from [addressLabel].
     */
    fun tagNames(auto: JsonObject, configs: List<JsonObject>, hosts: List<ServerEntry> = emptyList()): Map<String, String> {
        val byKey = HashMap<String, String>()
        val byAddr = LinkedHashMap<String, MutableList<String>>()
        configs.forEach { c ->
            if (c === auto) return@forEach
            val name = c.s("remarks")?.ifBlank { null } ?: return@forEach
            val outs = (c["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                .filter { it.s("protocol")?.lowercase() !in PLUMBING }
            if (outs.size != 1) return@forEach
            byKey.putIfAbsent(key(outs[0]), name)
            endpointKeys(outs[0]).forEach { byAddr.getOrPut(it) { mutableListOf() }.add(name) }
        }
        val res = LinkedHashMap<String, String>()
        members(auto).forEach { o ->
            val tag = o.s("tag")!!
            res[tag] = cleanLabel(byKey[key(o)] ?: addressLabel(o, hosts, byAddr), protoOf(o, tag))
        }
        return res
    }

    /**
     * 1.0.37: names for the running auto-select, computed now from what is stored (never a stale cached label):
     * a stored name is kept only when a host of that name still exists in the subscription; everything else is
     * matched again by address / SNI against the subscription's hosts, then by the domain's country prefix.
     */
    fun liveNames(entry: ServerEntry, all: List<ServerEntry>): Map<String, String> {
        val p = entry.xray ?: return emptyMap()
        val cfg = try { json.parseToJsonElement(p.customJson ?: return emptyMap()) as? JsonObject } catch (_: Exception) { null } ?: return emptyMap()
        val hosts = all.filter { it.subscriptionId == entry.subscriptionId && it.id != entry.id && it.xray != null && !nameIsAuto(it.name) }
        val hostNames = hosts.map { it.name.trim() }.toSet()
        val res = LinkedHashMap<String, String>()
        members(cfg).forEach { o ->
            val tag = o.s("tag")!!
            val stored = p.balancerTagNames[tag]?.trim()
            res[tag] = cleanLabel(if (stored != null && stored in hostNames) stored else addressLabel(o, hosts, emptyMap()), protoOf(o, tag))
        }
        return res
    }

    private fun members(cfg: JsonObject): List<JsonObject> = (cfg["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        .filter { it.s("tag") != null && it.s("protocol")?.lowercase() !in PLUMBING }

    private fun protoOf(o: JsonObject, tag: String) = net.antibs.client.ui.Format.protocolLabel(o.s("protocol").orEmpty()).ifBlank { tag }

    /** Server address + TLS/REALITY serverName + ws/httpupgrade/xhttp Host of an outbound, lowercased. */
    fun endpointKeys(o: JsonObject): Set<String> {
        val res = LinkedHashSet<String>()
        XrayConfigBuilder.outboundAddress(o).first.trim().lowercase().ifEmpty { null }?.let { res.add(it) }
        val ss = o["streamSettings"] as? JsonObject
        listOf("tlsSettings", "realitySettings").forEach { k -> (ss?.get(k) as? JsonObject)?.s("serverName")?.trim()?.lowercase()?.ifEmpty { null }?.let { res.add(it) } }
        listOf("wsSettings", "httpupgradeSettings", "xhttpSettings").forEach { k ->
            val t = ss?.get(k) as? JsonObject
            (t?.s("host") ?: ((t?.get("headers") as? JsonObject)?.s("Host")))?.trim()?.lowercase()?.ifEmpty { null }?.let { res.add(it) }
        }
        return res
    }

    private fun hostKeys(h: ServerEntry): Set<String> {
        val x = h.xray ?: return emptySet()
        return listOfNotNull(x.server, x.sni, x.host).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * «<flag> <Страна> <Protocol>» for an outbound without its own config: country of a single (non-cascade) config
     * ([byAddr]) or subscription host on the same address / SNI / Host, else from the domain's first label
     * (fin → Финляндия, kan → Канада, …); just «<Protocol>» when nothing fits. Never the domain.
     */
    fun addressLabel(o: JsonObject, hosts: List<ServerEntry>, byAddr: Map<String, List<String>>): String {
        val proto = protoOf(o, o.s("tag").orEmpty())
        val keys = endpointKeys(o)
        val fromConfigs = keys.firstNotNullOfOrNull { k -> countryFrom(byAddr[k].orEmpty()) }
        val fromHosts = fromConfigs ?: countryFrom(hosts.filter { h -> hostKeys(h).any { it in keys } }.map { it.name })
        val country = fromHosts ?: keys.firstNotNullOfOrNull { prefixCountry(it) }
        return if (country != null) "$country $proto" else proto
    }

    /** Words that describe transport / protocol / access, not the country («Германия 4G», «NL (VLESS-TCP)»). */
    private val SUFFIX = setOf("4g", "5g", "lte", "3g", "wifi", "cdn", "vless", "vmess", "trojan", "ss", "shadowsocks", "hysteria", "hysteria2",
        "hy2", "tuic", "xhttp", "ws", "grpc", "tcp", "udp", "reality", "tls", "h2", "httpupgrade", "naive", "anytls", "mieru", "wireguard", "wg", "awg")

    /** Country names → flag (names arriving without their flag emoji). */
    private val COUNTRY_FLAGS = linkedMapOf(
        "канада" to "🇨🇦", "canada" to "🇨🇦", "германия" to "🇩🇪", "germany" to "🇩🇪", "финляндия" to "🇫🇮", "finland" to "🇫🇮",
        "гонконг" to "🇭🇰", "hong kong" to "🇭🇰", "hongkong" to "🇭🇰", "нидерланды" to "🇳🇱", "netherlands" to "🇳🇱", "голландия" to "🇳🇱",
        "сша" to "🇺🇸", "usa" to "🇺🇸", "united states" to "🇺🇸", "россия" to "🇷🇺", "russia" to "🇷🇺", "польша" to "🇵🇱", "poland" to "🇵🇱",
        "франция" to "🇫🇷", "france" to "🇫🇷", "великобритания" to "🇬🇧", "англия" to "🇬🇧", "uk" to "🇬🇧", "швеция" to "🇸🇪", "sweden" to "🇸🇪",
        "турция" to "🇹🇷", "turkey" to "🇹🇷", "казахстан" to "🇰🇿", "kazakhstan" to "🇰🇿", "латвия" to "🇱🇻", "литва" to "🇱🇹", "эстония" to "🇪🇪",
        "япония" to "🇯🇵", "japan" to "🇯🇵", "сингапур" to "🇸🇬", "singapore" to "🇸🇬", "швейцария" to "🇨🇭", "австрия" to "🇦🇹", "испания" to "🇪🇸",
        "италия" to "🇮🇹", "чехия" to "🇨🇿", "румыния" to "🇷🇴", "молдова" to "🇲🇩", "армения" to "🇦🇲", "грузия" to "🇬🇪", "оаэ" to "🇦🇪",
    )

    /** Last-resort: first label of a domain → country (fin → 🇫🇮 Финляндия). */
    private val PREFIX_COUNTRY = mapOf(
        "fin" to "🇫🇮 Финляндия", "fi" to "🇫🇮 Финляндия", "finland" to "🇫🇮 Финляндия",
        "kan" to "🇨🇦 Канада", "ca" to "🇨🇦 Канада", "can" to "🇨🇦 Канада", "canada" to "🇨🇦 Канада",
        "g" to "🇩🇪 Германия", "de" to "🇩🇪 Германия", "ger" to "🇩🇪 Германия", "germany" to "🇩🇪 Германия",
        "nider" to "🇳🇱 Нидерланды", "nl" to "🇳🇱 Нидерланды", "neth" to "🇳🇱 Нидерланды", "netherlands" to "🇳🇱 Нидерланды",
        "usa" to "🇺🇸 США", "us" to "🇺🇸 США",
        "gonkong" to "🇭🇰 Гонконг", "hk" to "🇭🇰 Гонконг", "hongkong" to "🇭🇰 Гонконг",
        "ru" to "🇷🇺 Россия", "ruru" to "🇷🇺 Россия", "msk" to "🇷🇺 Россия",
        "pl" to "🇵🇱 Польша", "fr" to "🇫🇷 Франция", "uk" to "🇬🇧 Великобритания", "gb" to "🇬🇧 Великобритания",
        "se" to "🇸🇪 Швеция", "tr" to "🇹🇷 Турция", "kz" to "🇰🇿 Казахстан", "jp" to "🇯🇵 Япония", "sg" to "🇸🇬 Сингапур",
    )

    fun prefixCountry(host: String): String? {
        if (!host.contains('.') || host.matches(Regex("""[\d.]+"""))) return null
        val first = host.substringBefore('.').lowercase().trimEnd { it.isDigit() || it == '-' }
        return PREFIX_COUNTRY[first]
    }

    /**
     * «<flag> <Страна>» from host names on the same address: cascades (🇷🇺→X — their address is the entry hop, not
     * X) are skipped, the first remaining name wins; a name without a flag emoji gets it from the country name.
     */
    fun countryFrom(names: List<String>): String? {
        for (n in names) {
            if (n.contains('→') || n.contains("->")) continue
            val flag0 = LinkParser.flagOf(n)
            var rest = (if (flag0 != null) n.replace(flag0, " ") else n).replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), " ")
            rest = rest.split(Regex("""[\s|·•,/_-]+""")).filter { it.isNotBlank() && it.lowercase() !in SUFFIX && !it.matches(Regex("""\d+""")) && !it.contains('.') }.joinToString(" ")
            if (rest.isBlank()) continue
            val flag = flag0 ?: COUNTRY_FLAGS.entries.firstOrNull { rest.lowercase().contains(it.key) }?.value
            return if (flag != null) "$flag $rest" else rest
        }
        return null
    }

    private val DOMAIN = Regex("""(?<![\p{L}\d])(?:[a-zA-Z0-9-]+\.)+[a-zA-Z][a-zA-Z0-9-]*(?::\d+)?|\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?""")

    /** 1.0.37: no label ever shows a server domain / IP; what's left of an emptied label is [fallback]. */
    fun cleanLabel(label: String, fallback: String): String {
        val t = label.replace(DOMAIN, " ").split('·').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
            .replace(Regex("""\s+"""), " ").trim()
        return t.ifEmpty { fallback }
    }

    /** The auto-select config(s) of an Xray JSON subscription answer: remarks look like auto-select and a balancer exists. */
    fun jsonAutoConfigs(configs: List<JsonObject>): List<JsonObject> =
        configs.filter { c -> c["outbounds"] is JsonArray && balancerTagOf(c) != null && nameIsAuto(c.s("remarks").orEmpty()) }

    /**
     * Link-list subscription + the same subscription's Xray JSON answer → every auto-select stub (placeholder ss://)
     * becomes the panel's balancer config, run as-is ("custom"); the stub keeps its id so the selection survives.
     * Stubs without a matching JSON config are returned unchanged (the app's own balancer is the fallback).
     */
    fun attachJson(servers: List<ServerEntry>, configs: List<JsonObject>): List<ServerEntry> {
        val autos = jsonAutoConfigs(configs)
        if (autos.isEmpty()) return servers
        return servers.map { s ->
            if (!isSubscriptionAuto(s)) return@map s
            val cfg = autos.firstOrNull { it.s("remarks")?.trim() == s.name.trim() } ?: autos.first()
            val outs = (cfg["outbounds"] as JsonArray).map { o -> (o as? JsonObject)?.let { SubscriptionParser.fixTls(it) } ?: o }
            val body = JsonObject(cfg + ("outbounds" to JsonArray(outs))).toString()
            val remarks = s.xray?.remarks?.ifBlank { null } ?: s.name
            s.copy(xray = XrayProfile(remarks = remarks, protocol = "custom", server = "", port = 0, network = "tcp",
                customJson = body, balancerTagNames = tagNames(cfg, configs, servers)))
        }
    }

    /** Parses a subscription body that may be an Xray JSON answer (array of configs or one config). */
    fun parseConfigs(body: String): List<JsonObject> {
        val t = body.trim().removePrefix("\uFEFF")
        if (!t.startsWith("[") && !t.startsWith("{")) return emptyList()
        val root = try { json.parseToJsonElement(t) } catch (_: Exception) { return emptyList() }
        return when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
    }
}
