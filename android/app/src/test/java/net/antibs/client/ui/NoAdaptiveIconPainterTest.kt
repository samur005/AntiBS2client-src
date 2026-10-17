package net.antibs.client.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.0.33 regression: painterResource() cannot load <adaptive-icon> XML (mipmap/ic_launcher on API 26+) → crash on launch. */
class NoAdaptiveIconPainterTest {
    @Test fun composeNeverPaintsMipmapLauncher() {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val bad = dir.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> Regex("""painterResource\([^)]*R\.mipmap\.""").containsMatchIn(f.readText()) }.map { it.name }.toList()
        assertTrue("painterResource(R.mipmap…) in $bad", bad.isEmpty())
    }
}
