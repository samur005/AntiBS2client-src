package net.antibs.client.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpeedTestTest {
    @Test fun mbpsFromBytesAndNanos() {
        // 1 250 000 bytes in 1 s = 10 Mbit/s
        assertEquals(10.0, SpeedMath.mbps(1_250_000, 1_000_000_000), 1e-9)
        assertEquals(80.0, SpeedMath.mbps(5_000_000, 500_000_000), 1e-9)
        assertEquals(0.0, SpeedMath.mbps(1000, 0), 0.0)
        assertEquals(0.0, SpeedMath.mbps(0, 1_000_000), 0.0)
    }

    @Test fun percentileInterpolates() {
        val v = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        assertEquals(30.0, SpeedMath.median(v)!!, 1e-9)
        assertEquals(46.0, SpeedMath.percentile(v, 0.9)!!, 1e-9)
        assertEquals(10.0, SpeedMath.percentile(v.reversed(), 0.0)!!, 1e-9)
        assertEquals(50.0, SpeedMath.percentile(v, 1.0)!!, 1e-9)
        assertEquals(25.0, SpeedMath.median(listOf(30.0, 20.0))!!, 1e-9)
        assertNull(SpeedMath.percentile(emptyList(), 0.5))
        assertEquals(7.0, SpeedMath.percentile(listOf(7.0), 0.9)!!, 0.0)
    }

    @Test fun jitterIsMeanAbsoluteConsecutiveDifference() {
        assertEquals(5.0, SpeedMath.jitter(listOf(10.0, 15.0, 10.0, 15.0))!!, 1e-9)
        assertEquals(0.0, SpeedMath.jitter(listOf(20.0, 20.0, 20.0))!!, 1e-9)
        assertNull(SpeedMath.jitter(listOf(20.0)))
    }

    /**
     * 1.0.23 regression: bursty parallel streams. True aggregate = 100 Mbit/s (12.5 MB/s) but bytes arrive in
     * bursts (each 200 ms sample is either 0 or 2.5× the mean). p90 of samples (the 1.0.21–1.0.22 method)
     * overstated it ×2.5; the window aggregate must give ≈ 100.
     */
    @Test fun burstyDownloadWindowAggregateMatchesTrueRate() {
        val ms = 1_000_000L
        val trueBps = 12_500_000.0 // bytes/s = 100 Mbit/s
        var bytes = 0L
        val timeline = mutableListOf(0L to 0L)
        val samples = mutableListOf<Double>()
        val rnd = java.util.Random(7)
        for (i in 1..50) { // 10 s of 200 ms samples
            val burst = if (rnd.nextDouble() < 0.4) (trueBps * 0.2 * 2.5).toLong() else 0L
            bytes += burst
            timeline += (i * 200 * ms) to bytes
            samples += SpeedMath.mbps(burst, 200 * ms)
        }
        val real = SpeedMath.mbps(bytes, 10_000 * ms)
        val oldP90 = SpeedMath.percentile(samples.filter { it > 0 }, 0.9)!!
        assertTrue("old method overstated: $oldP90 vs $real", oldP90 > real * 1.8)
        val (t0, b0) = timeline[8] // after 1.6 s warm-up
        val (t1, b1) = timeline.last()
        val w = SpeedMath.windowRate(t0, b0, t1, b1)!!
        assertEquals(real, w, real * 0.15)
        assertEquals(100.0, w, 20.0)
    }

    /**
     * Upload: the socket accepts a 4 MB burst instantly (send buffers) — counting written bytes overstates. Only
     * acknowledged requests count, spread over their own duration → true 20 Mbit/s.
     */
    @Test fun uploadCountsOnlyAcknowledgedBytes() {
        val ms = 1_000_000L
        val rate = 2_500_000.0 // bytes/s = 20 Mbit/s for the 4 streams together
        val reqs = mutableListOf<SpeedMath.Req>()
        for (stream in 0 until 4) {
            var t = stream * 50L * ms
            val size = 1_000_000L
            while (t < 10_000 * ms) {
                val dur = (size / (rate / 4) * 1e9).toLong()
                reqs += SpeedMath.Req(t, t + dur, size)
                t += dur + 20 * ms // small gap between requests
            }
        }
        val r = SpeedMath.completedRate(reqs, 1_500 * ms)!!
        assertEquals(20.0, r, 1.5)
        // what 1.0.22 effectively did: written bytes incl. a 4 MB buffer burst in the first 200 ms window
        val inflated = SpeedMath.mbps(4_000_000, 200 * ms)
        assertTrue(inflated > 100)
        assertNull(SpeedMath.completedRate(emptyList(), 0))
        assertNull(SpeedMath.windowRate(-1, 0, 10, 10))
    }

    @Test fun emaAndBackoff() {
        assertEquals(10.0, SpeedMath.ema(null, 10.0), 0.0)
        assertEquals(13.0, SpeedMath.ema(10.0, 20.0, 0.3), 1e-9)
        assertEquals(2, SpeedMath.backoffSizeIndex(4, limited = true))
        assertEquals(3, SpeedMath.backoffSizeIndex(4, limited = false))
        assertEquals(0, SpeedMath.backoffSizeIndex(1, limited = true))
    }

    @Test fun requestSizesGrowWhileFast() {
        assertEquals(1, SpeedMath.nextSizeIndex(5, 0, 200, 1000))
        assertEquals(2, SpeedMath.nextSizeIndex(5, 2, 1500, 1000))
        assertEquals(4, SpeedMath.nextSizeIndex(5, 4, 10, 1000))
    }

    @Test fun latencySubtractsServerTiming() {
        assertEquals(30.0, SpeedMath.serverTimingMs("cfSpeedEdge;dur=5, cfSpeedWorker;dur=25"), 1e-9)
        assertEquals(12.5, SpeedMath.serverTimingMs("cfRequestDuration;dur=12.5"), 1e-9)
        assertEquals(0.0, SpeedMath.serverTimingMs(null), 0.0)
        assertEquals(45.0, SpeedMath.latencyMs(75_000_000, "cfSpeedEdge;dur=5, cfSpeedWorker;dur=25"), 1e-9)
        // server time ≥ TTFB (clock granularity) → floor at 10 % of TTFB, never ≤ 0
        assertEquals(3.0, SpeedMath.latencyMs(30_000_000, "cfSpeedWorker;dur=31"), 1e-9)
        assertEquals(30.0, SpeedMath.latencyMs(30_000_000, null), 1e-9)
    }

    @Test fun traceAndLocationsParsing() {
        val kv = SpeedMath.parseTrace("fl=1\nh=speed.cloudflare.com\nip=203.0.113.7\ncolo=DME\nloc=RU\n")
        assertEquals("DME", kv["colo"]); assertEquals("203.0.113.7", kv["ip"]); assertEquals("RU", kv["loc"])
        val loc = """[{"iata":"ZRH","city":"Zurich"},{"iata":"DME","lat":55.4,"city":"Moscow","cca2":"RU"}]"""
        assertEquals("Moscow", SpeedMath.cityForColo(loc, "dme"))
        assertNull(SpeedMath.cityForColo(loc, "IAD"))
        assertNull(SpeedMath.cityForColo("{}", "DME"))
    }

    @Test fun gaugeAndFormatting() {
        assertEquals(0f, SpeedMath.gaugeFraction(0.0), 0f)
        assertEquals(1f, SpeedMath.gaugeFraction(5000.0), 0f)
        assertTrue(SpeedMath.gaugeFraction(10.0) in 0.3f..0.4f)
        assertTrue(SpeedMath.gaugeFraction(100.0) > SpeedMath.gaugeFraction(10.0))
        assertEquals("—", SpeedMath.fmtMbps(null))
        assertEquals("7.42", SpeedMath.fmtMbps(7.4213))
        assertEquals("87.3", SpeedMath.fmtMbps(87.26))
        assertEquals("412", SpeedMath.fmtMbps(412.4))
        assertEquals(-50.0, SpeedMath.deltaPercent(100.0, 50.0)!!, 1e-9)
        assertNull(SpeedMath.deltaPercent(null, 50.0))
    }

    @Test fun historyKeepsNewestTwentyAndRoundTrips() {
        var h = emptyList<SpeedRecord>()
        for (i in 1..25) h = SpeedHistory.add(h, SpeedRecord(ts = i * 1000L, mode = if (i % 2 == 0) "vpn" else "direct", host = "h$i", down = i.toDouble()))
        assertEquals(SpeedHistory.MAX, h.size)
        assertEquals(25_000L, h.first().ts)
        assertEquals(6_000L, h.last().ts)
        val back = SpeedHistory.decode(SpeedHistory.encode(h))
        assertEquals(h, back)
        assertEquals(emptyList<SpeedRecord>(), SpeedHistory.decode("garbage"))
    }

    /** Live check against Cloudflare from the build machine: SPEEDTEST_LIVE=1 ./gradlew testReleaseUnitTest */
    @Test fun liveCloudflare() {
        assumeTrue(System.getenv("SPEEDTEST_LIVE") == "1")
        val phases = mutableListOf<SpeedTest.Phase>()
        val t = SpeedTest(SpeedTest.direct(), SpeedTest.Config(downMs = 6_000, upMs = 5_000, latencyCount = 10)) { p ->
            if (phases.lastOrNull() != p.phase) phases += p.phase
        }
        val r = t.run()
        println("LIVE: $r phases=$phases")
        assertNull(r.error)
        assertNotNull(r.downMbps); assertNotNull(r.upMbps); assertNotNull(r.pingMs)
        assertTrue(r.meta.colo.isNotEmpty())
        assertEquals(listOf(SpeedTest.Phase.META, SpeedTest.Phase.LATENCY, SpeedTest.Phase.DOWNLOAD, SpeedTest.Phase.UPLOAD, SpeedTest.Phase.DONE), phases)
    }

    /** Same through a local SOCKS5 proxy (the «Через VPN» path): SPEEDTEST_SOCKS=<port>. */
    @Test fun liveCloudflareViaSocks() {
        val port = System.getenv("SPEEDTEST_SOCKS")?.toIntOrNull()
        assumeTrue(port != null)
        val r = SpeedTest(SpeedTest.socks(port!!), SpeedTest.Config(downMs = 6_000, upMs = 5_000, latencyCount = 8)).run()
        println("LIVE-SOCKS: $r phases-ok")
        assertNull(r.error)
        assertNotNull(r.downMbps); assertNotNull(r.upMbps)
    }

    @Test fun socksDialerReportsMissingInbound() {
        val free = java.net.ServerSocket(0).use { it.localPort }
        val r = SpeedTest(SpeedTest.socks(free), SpeedTest.Config(downMs = 500, upMs = 500)).run()
        assertNotNull(r.error); assertNull(r.downMbps)
    }

    @Test fun cancelBeforeRunStopsImmediately() {
        val t = SpeedTest(SpeedTest.direct())
        t.cancel()
        assertEquals("Остановлено", t.run().error)
    }

    /** Cross-check vs curl through a rate-limited SOCKS proxy: SPEEDTEST_CMP_SOCKS=<port>. */
    @Test fun liveCompareViaThrottledSocks() {
        val port = System.getenv("SPEEDTEST_CMP_SOCKS")?.toIntOrNull()
        assumeTrue(port != null)
        val cfg = SpeedTest.Config(downMs = 10_000, upMs = 10_000, latencyCount = 5, maxDownBytes = Long.MAX_VALUE, maxUpBytes = Long.MAX_VALUE)
        val r = SpeedTest(SpeedTest.socks(port!!), cfg).run()
        println("CMP: down=${r.downMbps} up=${r.upMbps} err=${r.error} bytes=${r.downBytes}/${r.upBytes}")
        assertNull(r.error)
    }
}
