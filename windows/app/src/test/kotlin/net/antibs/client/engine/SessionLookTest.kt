package net.antibs.client.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLookTest {

    @Test fun ipv4AcceptsDotted() {
        assertTrue(SessionLooks.isIpv4("185.218.17.95"))
        assertTrue(SessionLooks.isIpv4("0.0.0.0"))
        assertFalse(SessionLooks.isIpv4("999.1.1.1"))
        assertFalse(SessionLooks.isIpv4("2001:db8::1"))
        assertFalse(SessionLooks.isIpv4(""))
    }

    @Test fun ipv6NeedsColon() {
        assertTrue(SessionLooks.isIpv6("2001:db8::1"))
        assertTrue(SessionLooks.isIpv6("::1"))
        assertFalse(SessionLooks.isIpv6("185.218.17.95"))
        assertFalse(SessionLooks.isIpv6("not an ip"))
    }

    @Test fun countryLinePutsFlagBeforeNameAndCode() {
        assertEquals("🇨🇦 Canada (CA)", SessionLooks.countryLine("Canada", "CA"))
        assertEquals("🇨🇦 Canada (CA)", SessionLooks.countryLine("Canada", "ca"))
        assertEquals("🇩🇪 Германия (DE)", SessionLooks.countryLine("Германия", "DE"))
        assertTrue(SessionLooks.countryLine("Canada", "CA")!!.startsWith("🇨🇦 "))
        assertNull(SessionLooks.countryLine(null, null))
        assertEquals("🇨🇦 CA", SessionLooks.countryLine(null, "CA"))
    }

    @Test fun regionIsNotFoldedIntoCountryLine() {
        val line = SessionLooks.countryLine("Канада", "CA")!!
        assertFalse(line.contains("British Columbia"))
        assertFalse(line.contains("регион", ignoreCase = true))
    }

    @Test fun coordTrimsTrailingZeros() {
        assertEquals("49.2827", SessionLooks.coord(49.2827))
        assertEquals("-123.12", SessionLooks.coord(-123.120000))
        assertEquals("0", SessionLooks.coord(0.0))
        assertNull(SessionLooks.coord(null))
    }

    @Test fun parseIpWhoDetails() {
        val body = """
            {"ip":"185.218.17.95","success":true,"country":"Canada","country_code":"CA",
             "city":"Vancouver","region":"British Columbia","latitude":49.2827,"longitude":-123.1207,
             "connection":{"asn":400992,"isp":"ZhouyiSat Communications","org":"ZhouyiSat"}}
        """.trimIndent()
        val f = SessionLooks.parseIpWho(body)!!
        assertEquals("185.218.17.95", f.ip)
        assertEquals("400992", f.asn)
        assertEquals("ZhouyiSat Communications", f.provider)
        assertEquals("Canada", f.countryName)
        assertEquals("CA", f.countryCode)
        assertEquals("Vancouver", f.city)
        assertEquals("British Columbia", f.region)
        assertEquals(49.2827, f.latitude!!, 1e-6)
        val look = SessionLooks.toLook("185.218.17.95", null, f, viaTunnel = true)
        assertEquals("🇨🇦 Канада (CA)", look.country)
        assertEquals("British Columbia", look.region)
        assertEquals("185.218.17.95", look.ipv4)
        assertNull(look.ipv6)
        assertTrue(look.viaTunnel)
    }

    @Test fun parseIpWhoFailureIsNull() {
        assertNull(SessionLooks.parseIpWho("""{"success":false,"message":"Invalid"}"""))
    }

    @Test fun parseFreeIpApiAndIpApiCo() {
        val free = SessionLooks.parseFreeIpApi(
            """{"ipAddress":"1.2.3.4","countryName":"Germany","countryCode":"DE","cityName":"Berlin",
                "regionName":"Berlin","latitude":52.52,"longitude":13.4,"asn":3320,"asnOrganization":"Deutsche Telekom"}"""
        )!!
        assertEquals("DE", free.countryCode)
        assertEquals("Deutsche Telekom", free.provider)
        assertEquals("3320", free.asn)
        val co = SessionLooks.parseIpApiCo(
            """{"ip":"8.8.8.8","country_name":"United States","country_code":"US","city":"Mountain View",
                "region":"California","latitude":37.4,"longitude":-122.08,"asn":"AS15169","org":"Google LLC"}"""
        )!!
        assertEquals("15169", SessionLooks.toLook(null, null, co, false).asn)
        assertEquals("🇺🇸 Соединенные Штаты (US)", SessionLooks.toLook(null, null, co, false).country)
        assertNull(SessionLooks.parseIpApiCo("""{"error":true,"reason":"RateLimited"}"""))
    }

    @Test fun ipv6DoesNotFailTheLookWhenOnlyV4DetailsExist() {
        val f = SessionLooks.parseIpWho("""{"ip":"1.1.1.1","success":true,"country":"Australia","country_code":"AU"}""")!!
        val look = SessionLooks.toLook("1.1.1.1", null, f, false)
        assertEquals("1.1.1.1", look.ipv4)
        assertNull(look.ipv6)
        assertEquals("🇦🇺 Австралия (AU)", look.country)
    }

    @Test fun providerUrlPrefersHttpsDomain() {
        assertEquals("https://as400992.net", SessionLooks.providerUrl("as400992.net", "400992", "ZhouyiSat Communications"))
        assertEquals("https://isp.example.com", SessionLooks.providerUrl("https://isp.example.com/foo", "1", "X"))
        assertEquals(null, SessionLooks.cleanDomain("localhost"))
        assertEquals(null, SessionLooks.cleanDomain("not a domain"))
    }

    @Test fun providerUrlFallsBackToAsnThenSearch() {
        assertEquals("https://bgp.he.net/AS400992", SessionLooks.providerUrl(null, "400992", "ZhouyiSat Communications"))
        assertEquals("https://bgp.he.net/AS15169", SessionLooks.providerUrl(null, "AS15169", "Google"))
        val q = SessionLooks.providerUrl(null, null, "ZhouyiSat Communications")!!
        assertTrue(q.startsWith("https://www.google.com/search?q="))
        assertTrue(q.contains("ZhouyiSat"))
        assertNull(SessionLooks.providerUrl(null, null, null))
        assertNull(SessionLooks.providerUrl(" ", "0", "  "))
    }

    @Test fun parseIpWhoKeepsConnectionDomain() {
        val f = SessionLooks.parseIpWho("""{"ip":"1.2.3.4","success":true,"country":"Canada","country_code":"CA","connection":{"asn":400992,"isp":"ZhouyiSat","domain":"as400992.net"}}""")!!
        assertEquals("as400992.net", f.domain)
        val look = SessionLooks.toLook("1.2.3.4", null, f, true)
        assertEquals("as400992.net", look.domain)
        assertEquals("https://as400992.net", SessionLooks.providerUrl(look.domain, look.asn, look.provider))
    }
}
