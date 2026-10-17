package net.antibs.client.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 1.0.46: where an IP is (for the home map / globe). [approx] = only the country is known (label point). */
data class GeoPoint(
    val ip: String,
    val city: String,
    val country: String,
    val cc: String,
    val lat: Double,
    val lon: Double,
    val approx: Boolean = false,
)

/**
 * IP → location via public no-key endpoints (ipwho.is → freeipapi.com → ipapi.co).
 * «direct» uses the physical network (Wi‑Fi / mobile, even while the VPN is up), «tunnel» the core's local SOCKS
 * inbound, i.e. it shows the VPN exit. Nothing is sent except the request itself; results are cached in memory.
 */
object GeoLocate {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile var lastDirect: GeoPoint? = null
        private set

    /** Desktop: «direct» = the system network without our proxy (in TUN mode the core excludes its own process only, so
     *  this may show the VPN exit while connected — the caller decides). */
    suspend fun direct(): GeoPoint? = withContext(Dispatchers.IO) {
        val c = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(9, TimeUnit.SECONDS).build()
        lookup(c)?.let { russify(it, null) }?.also { lastDirect = it }
    }

    suspend fun tunnel(socksPort: Int): GeoPoint? = withContext(Dispatchers.IO) {
        lookup(SpeedTest.socks(socksPort).newBuilder().callTimeout(9, TimeUnit.SECONDS).build())?.let { russify(it, null) }
    }

    val RU: java.util.Locale = java.util.Locale.forLanguageTag("ru")
    fun hasCyrillic(s: String) = s.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }

    /** Pure part of [ru]: Russian country name by code; city replaced by [cityRu] only if that one is Cyrillic. */
    fun russify(p: GeoPoint, cityRu: String?): GeoPoint {
        val country = if (p.cc.length == 2) net.antibs.client.data.GeoCountries.name(p.cc, RU).takeIf { it != p.cc } ?: p.country else p.country
        val city = cityRu?.trim()?.takeIf { it.isNotEmpty() && hasCyrillic(it) } ?: p.city
        return p.copy(city = city, country = country)
    }

    /** 1.0.49: VPN exit IP in the bubble — IPv4 «185.***.***.111», IPv6 first and last group with *** between. */
    fun mask(ip: String): String {
        val s = ip.trim()
        if (s.contains(':')) {
            val g = s.split(':').filter { it.isNotEmpty() }
            return if (g.size >= 2) "${g.first()}:***:${g.last()}" else s
        }
        val o = s.split('.')
        return if (o.size == 4) "${o[0]}.***.***.${o[3]}" else s
    }

    private fun lookup(c: OkHttpClient): GeoPoint? {
        for ((url, parse) in listOf<Pair<String, (String) -> GeoPoint?>>(
            "https://ipwho.is/?lang=ru" to ::parseIpWhoIs,
            "https://freeipapi.com/api/json" to ::parseFreeIpApi,
            "https://ipapi.co/json/" to ::parseIpApiCo,
        )) {
            val body = try {
                c.newCall(Request.Builder().url(url).header("User-Agent", "AntiBS2client").header("Accept", "application/json").header("Accept-Language", "ru").build())
                    .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (_: Exception) { null } ?: continue
            parse(body)?.let { return it }
        }
        return null
    }

    private fun obj(body: String): JsonObject? = try { json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

    private fun point(ip: String?, city: String?, country: String?, cc: String?, lat: Double?, lon: Double?): GeoPoint? {
        if (ip.isNullOrBlank() || lat == null || lon == null) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return GeoPoint(ip, city.orEmpty(), country.orEmpty(), cc.orEmpty().uppercase(), lat, lon)
    }

    fun parseIpWhoIs(body: String): GeoPoint? = obj(body)?.let { o ->
        if ((o["success"] as? JsonPrimitive)?.content == "false") null
        else point(o.str("ip"), o.str("city"), o.str("country"), o.str("country_code"), o.num("latitude"), o.num("longitude"))
    }

    fun parseFreeIpApi(body: String): GeoPoint? = obj(body)?.let { o ->
        point(o.str("ipAddress"), o.str("cityName"), o.str("countryName"), o.str("countryCode"), o.num("latitude"), o.num("longitude"))
    }

    fun parseIpApiCo(body: String): GeoPoint? = obj(body)?.let { o ->
        if ((o["error"] as? JsonPrimitive)?.content == "true") null
        else point(o.str("ip"), o.str("city"), o.str("country_name"), o.str("country_code") ?: o.str("country"), o.num("latitude"), o.num("longitude"))
    }

    /** Offline fallback: the label point of the server's country (from its flag). */
    fun ofCountry(cc: String?, ip: String = ""): GeoPoint? {
        val g = net.antibs.client.data.GeoCountries.point(cc) ?: return null
        return GeoPoint(ip, "", net.antibs.client.data.GeoCountries.name(cc!!), cc.uppercase(), g.lat, g.lon, approx = true)
    }
}
