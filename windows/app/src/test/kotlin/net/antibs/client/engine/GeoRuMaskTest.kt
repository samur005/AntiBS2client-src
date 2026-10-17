package net.antibs.client.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoRuMaskTest {
    @Test fun maskIpv4() {
        assertEquals("185.***.***.111", GeoLocate.mask("185.22.33.111"))
        assertEquals("10.***.***.1", GeoLocate.mask(" 10.0.0.1 "))
    }

    @Test fun maskIpv6() {
        assertEquals("2a01:***:7334", GeoLocate.mask("2a01:4f8:c0c:1234::7334"))
        assertEquals("2001:***:1", GeoLocate.mask("2001:db8:0:0:0:0:0:1"))
    }

    @Test fun maskLeavesOddInputAlone() {
        assertEquals("—", GeoLocate.mask("—"))
        assertEquals("", GeoLocate.mask(""))
    }

    private val de = GeoPoint("185.22.33.111", "Frankfurt am Main", "Germany", "DE", 50.11, 8.68)

    @Test fun countryInRussianFromCode() {
        val r = GeoLocate.russify(de, null)
        assertEquals("Германия", r.country)
        assertEquals("Frankfurt am Main", r.city) // no Russian city name available → kept
        assertEquals("Нидерланды", GeoLocate.russify(de.copy(cc = "NL", country = "Netherlands"), null).country)
    }

    @Test fun cityReplacedOnlyByCyrillic() {
        assertEquals("Франкфурт-на-Майне", GeoLocate.russify(de, "Франкфурт-на-Майне").city)
        assertEquals("Frankfurt am Main", GeoLocate.russify(de, "Frankfurt").city)
        assertTrue(GeoLocate.hasCyrillic("Москва")); assertTrue(!GeoLocate.hasCyrillic("Moscow"))
    }

    @Test fun ipwhoisRussianPayloadParses() {
        val p = GeoLocate.parseIpWhoIs("""{"ip":"185.22.33.111","success":true,"country":"Германия","country_code":"DE","city":"Франкфурт-на-Майне","latitude":50.11,"longitude":8.68}""")!!
        assertEquals("Франкфурт-на-Майне", GeoLocate.russify(p, null).city)
        assertEquals("Германия", GeoLocate.russify(p, null).country)
    }
}
