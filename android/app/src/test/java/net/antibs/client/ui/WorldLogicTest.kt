package net.antibs.client.ui

import net.antibs.client.data.GeoCountries
import net.antibs.client.engine.GeoLocate
import net.antibs.client.engine.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WorldLogicTest {
    private fun pt(lat: Double, lon: Double, ip: String = "1.2.3.4") = GeoPoint(ip, "", "", "", lat, lon)

    @Test fun countriesFromFlags() {
        assertEquals("DE", GeoCountries.ccOfName("🇩🇪 Франкфурт"))
        assertNull(GeoCountries.ccOfName("Без флага"))
        assertEquals(listOf("DE", "NL"), WorldLogic.serverCountries(listOf("🇩🇪 A", "🇳🇱 B", "🇩🇪 C", "auto")))
        assertEquals("DE", WorldLogic.liveCountry("🇷🇺→🇩🇪 Германия"))
        assertEquals("FI", WorldLogic.liveCountry("🇫🇮 Хельсинки"))
        val de = GeoCountries.point("DE")!!
        assertTrue(de.lat in 47.0..55.0 && de.lon in 6.0..15.0)
        assertNotNull(GeoCountries.point("gb")); assertNotNull(GeoCountries.point("UK")); assertNotNull(GeoCountries.point("TW"))
        assertNotNull(GeoCountries.point("SG")); assertNotNull(GeoCountries.point("HK"))
    }

    @Test fun parsers() {
        val a = GeoLocate.parseIpWhoIs("""{"ip":"5.6.7.8","success":true,"country":"Germany","country_code":"DE","city":"Frankfurt","latitude":50.11,"longitude":8.68}""")!!
        assertEquals("5.6.7.8", a.ip); assertEquals("DE", a.cc); assertEquals(50.11, a.lat, 1e-9)
        assertNull(GeoLocate.parseIpWhoIs("""{"success":false,"message":"rate"}"""))
        val b = GeoLocate.parseFreeIpApi("""{"ipAddress":"9.9.9.9","latitude":52.37,"longitude":4.9,"countryName":"Netherlands","countryCode":"NL","cityName":"Amsterdam"}""")!!
        assertEquals("Amsterdam", b.city)
        val c = GeoLocate.parseIpApiCo("""{"ip":"1.1.1.1","city":"Helsinki","country_name":"Finland","country_code":"FI","latitude":"60.17","longitude":24.94}""")!!
        assertEquals(60.17, c.lat, 1e-9)
        assertNull(GeoLocate.parseIpApiCo("""{"error":true,"reason":"RateLimited"}"""))
        assertNull(GeoLocate.parseIpWhoIs("not json"))
        assertNull(GeoLocate.parseIpWhoIs("""{"ip":"x","latitude":0,"longitude":0}"""))
        val f = GeoLocate.ofCountry("NL")!!
        assertTrue(f.approx); assertEquals("NL", f.cc)
    }

    @Test fun secured() {
        val home = pt(55.7, 37.6, "10.0.0.1"); val exit = pt(50.1, 8.7, "5.6.7.8")
        assertTrue(WorldLogic.secured(true, exit, home))
        assertFalse(WorldLogic.secured(true, exit.copy(ip = "10.0.0.1"), home))
        assertFalse(WorldLogic.secured(false, exit, home))
        assertFalse(WorldLogic.secured(true, GeoLocate.ofCountry("DE"), home))
    }

    @Test fun projectionCentresFocus() {
        val (yaw, pitch) = WorldLogic.focusAngles(50.0, 8.0)
        val v = WorldLogic.view(WorldLogic.sphere(50.0, 8.0), yaw, pitch)
        assertEquals(0.0, v[0], 1e-9); assertEquals(0.0, v[1], 1e-9); assertEquals(1.0, v[2], 1e-9)
        // east of the focus is to the right, north is up
        val e = WorldLogic.view(WorldLogic.sphere(50.0, 20.0), yaw, pitch); assertTrue(e[0] > 0)
        val n = WorldLogic.view(WorldLogic.sphere(60.0, 8.0), yaw, pitch); assertTrue(n[1] > 0)
    }

    @Test fun arcAndMidpoint() {
        val a = pt(55.75, 37.62); val b = pt(52.37, 4.9)
        val arc = WorldLogic.arc(a, b)
        assertEquals(65, arc.size)
        val s = WorldLogic.sphere(a.lat, a.lon)
        assertEquals(s[0], arc[0][0], 1e-9); assertEquals(s[2], arc[0][2], 1e-9)
        val midLen = Math.sqrt(arc[32].sumOf { it * it }); assertTrue(midLen > 1.0)
        val (mlat, mlon) = WorldLogic.midpoint(a, b)
        assertTrue(mlat in 52.0..58.0 && mlon in 15.0..25.0)
        assertTrue(abs(WorldLogic.nearest(3.0 + 2 * Math.PI, 3.0) - 3.0) < 1e-9)
        assertTrue(WorldLogic.angle(a, b) in 0.3..0.5)
    }

    @Test fun mapCalibrationAndBubble() {
        assertEquals(0f, WorldLogic.mapY(91.75, 430f), 1e-3f)
        assertEquals(430f, WorldLogic.mapY(-58.22, 430f), 1e-3f)
        assertEquals(512f, WorldLogic.mapX(0.0, 1024f), 1e-3f)
        val pl = WorldLogic.placeBubble(200f, 200f, 100f, 50f, 400f, 400f, listOf(androidx.compose.ui.geometry.Offset(200f, 200f)), 24f, 6f)
        assertEquals(WorldLogic.Side.ABOVE, pl.side)
        // near the top edge it can't go above → below
        val p2 = WorldLogic.placeBubble(200f, 20f, 100f, 50f, 400f, 400f, listOf(androidx.compose.ui.geometry.Offset(200f, 20f)), 24f, 6f)
        assertEquals(WorldLogic.Side.BELOW, p2.side)
    }

    @Test fun fastAtan2Accurate() {
        var worst = 0.0
        val r = java.util.Random(7)
        repeat(20000) {
            val y = (r.nextDouble() * 2 - 1).toFloat(); val x = (r.nextDouble() * 2 - 1).toFloat()
            worst = maxOf(worst, abs(fastAtan2(y, x) - Math.atan2(y.toDouble(), x.toDouble())))
        }
        assertTrue("worst=$worst", worst < 0.0002)
    }

    @Test fun bundledAssets() {
        val mask = java.io.File("src/main/assets/land_mask.bin").readBytes()
        assertEquals(2048 * 1024 / 8, mask.size)
        // Europe is land, mid-Pacific is sea (bit layout: row-major, MSB first)
        fun land(lat: Double, lon: Double): Boolean {
            val u = ((lon + 180) / 360 * 2048).toInt().coerceIn(0, 2047); val v = ((90 - lat) / 180 * 1024).toInt().coerceIn(0, 1023)
            val i = v * 2048 + u
            return (mask[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1
        }
        assertTrue(land(50.0, 10.0)); assertTrue(land(-25.0, 135.0)); assertFalse(land(0.0, -150.0)); assertFalse(land(40.0, -40.0))
        val lines = java.io.File("src/main/assets/world_map_paths.txt").readLines().filter { it.isNotBlank() }
        assertTrue(lines.size > 100 && lines.all { it.trim().startsWith("M") })
    }
}
