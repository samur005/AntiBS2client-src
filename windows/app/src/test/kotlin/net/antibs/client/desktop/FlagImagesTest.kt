package net.antibs.client.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagImagesTest {
    @Test fun singleFlag() {
        val p = FlagImages.split("🇩🇪 Германия")
        assertEquals("de", p[0].image); assertEquals(" Германия", p[1].text)
    }
    @Test fun severalFlagsAndOddPrefix() {
        // the row seen on a real PC: «🇷🇺 —🇨🇦 Канада» → two pictures, the dash stays text
        val p = FlagImages.split("🇷🇺 —🇨🇦 Канада")
        assertEquals(listOf("ru", null, "ca", null), p.map { it.image })
        assertEquals(" —", p[1].text)
        assertEquals(listOf("ru", "arrow", "de", null), FlagImages.split("🇷🇺→🇩🇪 Германия").map { it.image })
        assertEquals(listOf("ru", "arrow", "ca"), FlagImages.split("🇷🇺\u2009→\u2009🇨🇦").map { it.image })
    }
    @Test fun variationSelectorAndGlobe() {
        assertEquals(listOf("fi", null), FlagImages.split("🇫🇮\uFE0F Finland").map { it.image })
        assertEquals("globe", FlagImages.split("🌐").single().image)
    }
    @Test fun unknownPairAndLoneIndicatorStayText() {
        assertTrue(FlagImages.split("🇦🇦 x").all { it.image == null })
        assertTrue(FlagImages.split("🇦 x").all { it.image == null })
        assertEquals("plain", FlagImages.split("plain").single().text)
    }
    @Test fun everyIsoCountryHasAPicture() {
        val missing = java.util.Locale.getISOCountries().filter { !FlagImages.has(it.lowercase()) }
        assertTrue("missing: $missing", missing.isEmpty())
        assertEquals("de", FlagImages.isoOf("🇩🇪")); assertNull(FlagImages.isoOf("DE"))
    }
}
