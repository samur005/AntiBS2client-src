package net.antibs.client.desktop

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The generated TUN config must pass the real `sing-box check` (same 1.14.2 schema as the bundled Windows build). */
class TunConfigCheckTest {
    @Test fun singBoxAcceptsTunConfig() {
        val sb = Engines.file("sing-box")
        assumeTrue(sb.canExecute())
        for (ipv6 in listOf(true, false)) {
            val f = File.createTempFile("tun", ".json").apply { writeText(TunConfig.build(10808, ipv6 = ipv6, excludeProcesses = listOf("xray.exe", "sing-box.exe", "AntiBS2client.exe"), excludeCidrs = listOf("203.0.113.7/32")).toString()) }
            val p = ProcessBuilder(sb.path, "check", "-c", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("sing-box check: $out", 0, p.waitFor())
        }
    }
}
