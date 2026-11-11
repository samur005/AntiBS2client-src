package net.antibs.client.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.antibs.client.data.ServerGrouping
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Public-IP / geo snapshot shown on the Session screen. Written ourselves; do not copy third-party session code. */
data class SessionLook(
    val ipv4: String? = null,
    val ipv6: String? = null,
    val asn: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val city: String? = null,
    val region: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val domain: String? = null,
    val viaTunnel: Boolean = false,
)

data class SessionFields(
    val ip: String? = null,
    val asn: String? = null,
    val provider: String? = null,
    val countryName: String? = null,
    val countryCode: String? = null,
    val city: String? = null,
    val region: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val domain: String? = null,
)

object SessionLooks {

    private val IPV4 = Regex("""^(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)$""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isIpv4(s: String): Boolean = IPV4.matches(s.trim())

    fun isIpv6(s: String): Boolean {
        val t = s.trim()
        return t.contains(':') && ' ' !in t && t.length in 2..45 && !isIpv4(t)
    }

    fun coord(v: Double?): String? {
        if (v == null || v.isNaN()) return null
        return String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
    }

    /** «🇨🇦 Canada (CA)» — flag from the same helper used on server chips, then name and ISO code. Region stays plain text. */
    fun countryLine(name: String?, code: String?): String? {
        val cc = code?.trim()?.uppercase(Locale.US)?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
        val n = name?.trim()?.ifEmpty { null }
        val label = when {
            n != null && cc != null -> "$n ($cc)"
            n != null -> n
            cc != null -> cc
            else -> return null
        }
        val flag = cc?.let { ServerGrouping.flag(it) }?.trim().orEmpty()
        return if (flag.isNotEmpty()) "$flag $label" else label
    }

    fun localizeCountryName(name: String?, code: String?): String? {
        val cc = code?.trim()?.uppercase(Locale.US)?.takeIf { it.length == 2 }
        if (cc != null) {
            val ru = ServerGrouping.countryName(cc)
            if (ru.isNotBlank() && ru != ServerGrouping.OTHER && !ru.equals("Другие", ignoreCase = true)) return ru
        }
        return name?.trim()?.ifEmpty { null }
    }

    fun parseIpWho(body: String): SessionFields? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (obj.bool("success") == false) return null
        val conn = obj["connection"] as? JsonObject
        val asn = conn?.text("asn") ?: obj.text("asn")
        val provider = conn?.text("isp") ?: conn?.text("org") ?: obj.text("isp") ?: obj.text("org")
        val domain = conn?.text("domain") ?: obj.text("domain")
        return SessionFields(
            ip = obj.text("ip"),
            asn = asn,
            provider = provider,
            countryName = obj.text("country"),
            countryCode = obj.text("country_code") ?: obj.text("countryCode"),
            city = obj.text("city"),
            region = obj.text("region"),
            latitude = obj.num("latitude"),
            longitude = obj.num("longitude"),
            domain = domain,
        ).takeIf { it.countryCode != null || it.ip != null || it.provider != null }
    }

    fun parseFreeIpApi(body: String): SessionFields? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return SessionFields(
            ip = obj.text("ipAddress") ?: obj.text("ip"),
            asn = obj.text("asn") ?: obj.text("asnNumber"),
            provider = obj.text("asnOrganization") ?: obj.text("isp") ?: obj.text("organization"),
            countryName = obj.text("countryName"),
            countryCode = obj.text("countryCode"),
            city = obj.text("cityName") ?: obj.text("city"),
            region = obj.text("regionName") ?: obj.text("region"),
            latitude = obj.num("latitude"),
            longitude = obj.num("longitude"),
        ).takeIf { it.countryCode != null || it.ip != null }
    }

    fun parseIpApiCo(body: String): SessionFields? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (obj.text("error") == "true" || obj.bool("error") == true) return null
        return SessionFields(
            ip = obj.text("ip"),
            asn = obj.text("asn")?.removePrefix("AS"),
            provider = obj.text("org") ?: obj.text("isp"),
            countryName = obj.text("country_name") ?: obj.text("country"),
            countryCode = obj.text("country_code") ?: obj.text("country"),
            city = obj.text("city"),
            region = obj.text("region"),
            latitude = obj.num("latitude"),
            longitude = obj.num("longitude"),
        ).takeIf { it.countryCode != null || it.ip != null }
    }

    fun toLook(ipv4: String?, ipv6: String?, fields: SessionFields?, viaTunnel: Boolean): SessionLook {
        val f = fields
        val ip4 = ipv4 ?: f?.ip?.takeIf { isIpv4(it) }
        val ip6 = ipv6 ?: f?.ip?.takeIf { isIpv6(it) }
        val cc = f?.countryCode?.trim()?.uppercase(Locale.US)?.takeIf { it.length == 2 }
        val name = localizeCountryName(f?.countryName, cc)
        return SessionLook(
            ipv4 = ip4,
            ipv6 = ip6,
            asn = f?.asn?.trim()?.removePrefix("AS")?.ifEmpty { null },
            provider = f?.provider?.trim()?.ifEmpty { null },
            country = countryLine(name, cc),
            countryCode = cc,
            city = f?.city?.trim()?.ifEmpty { null },
            region = f?.region?.trim()?.ifEmpty { null },
            latitude = coord(f?.latitude),
            longitude = coord(f?.longitude),
            domain = cleanDomain(f?.domain),
            viaTunnel = viaTunnel,
        )
    }


    fun cleanDomain(raw: String?): String? {
        var s = raw?.trim()?.lowercase(Locale.US)?.ifEmpty { null } ?: return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').trim('.', ' ')
        if (s.isEmpty() || ' ' in s || '.' !in s) return null
        if (s == "localhost" || s.endsWith(".local")) return null
        return s
    }

    /**
     * Website to open for the «Провайдер» row: `https://<connection.domain>` when ipwho.is gave a domain,
     * else Hurricane Electric for the ASN, else a web search for the provider name.
     */
    fun providerUrl(domain: String?, asn: String?, provider: String?): String? {
        cleanDomain(domain)?.let { return "https://$it" }
        val n = asn?.trim()?.removePrefix("AS")?.removePrefix("as")?.trim()
        if (!n.isNullOrEmpty() && n.all { it.isDigit() } && n != "0") return "https://bgp.he.net/AS$n"
        val q = provider?.trim()?.ifEmpty { null } ?: return null
        val enc = java.net.URLEncoder.encode(q, Charsets.UTF_8.name()).replace("+", "%20")
        return "https://www.google.com/search?q=$enc"
    }

    suspend fun fetch(ctx: Context, connected: Boolean, socksPort: Int): SessionLook = withContext(Dispatchers.IO) {
        val base = if (connected) SpeedTest.socks(socksPort) else {
            runCatching { SpeedNet.direct(ctx).client }.getOrElse { SpeedTest.direct() }
        }
        val client = base.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        coroutineScope {
            val v6 = async {
                withTimeoutOrNull(5_000) {
                    runCatching { get(client, "https://api6.ipify.org", 5_000) }
                        .getOrNull()?.trim()?.takeIf { isIpv6(it) }
                }
            }
            val v4 = runCatching { get(client, "https://api.ipify.org") }.getOrNull()?.trim()?.takeIf { isIpv4(it) }
            val fields = lookupDetails(client, v4)
            val look = toLook(v4, v6.await(), fields, connected)
            if (look.ipv4 == null && look.ipv6 == null && look.country == null && look.provider == null) {
                error("Не удалось определить IP")
            }
            look
        }
    }

    private fun lookupDetails(client: OkHttpClient, ip: String?): SessionFields? {
        val path = ip?.trim().orEmpty()
        val attempts = listOf(
            "https://ipwho.is/" + path.let { if (it.isEmpty()) "" else it } + "?lang=ru" to ::parseIpWho,
            "https://freeipapi.com/api/json" + path.let { if (it.isEmpty()) "" else "/$it" } to ::parseFreeIpApi,
            "https://ipapi.co/" + (if (path.isEmpty()) "json/" else "$path/json/") to ::parseIpApiCo,
        )
        for ((url, parse) in attempts) {
            val body = runCatching { get(client, url) }.getOrNull() ?: continue
            val fields = runCatching { parse(body) }.getOrNull()
            if (fields != null) return fields
        }
        return null
    }

    private fun get(client: OkHttpClient, url: String, timeoutMs: Long? = null): String {
        val http = if (timeoutMs != null) {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        } else client
        http.newCall(Request.Builder().url(url).header("Accept", "application/json, text/plain").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string()?.trim().orEmpty()
        }
    }

    private fun JsonObject.text(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        val s = p.content.trim()
        return s.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JsonObject.num(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: p.content.trim().toBooleanStrictOrNull()
    }
}
