package net.antibs.client.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * 2.0.29: synthetic TCP bulk-ping — compares old (4) vs new (32) parallelism on local listeners.
 * Not a device benchmark; shows the concurrency change dominates when RTT is the cost.
 */
class PingParallelBenchTest {
    @Test fun widerSemaphoreFinishesFasterOnLocalHosts() = runBlocking {
        val n = 24
        val servers = List(n) { ServerSocket(0).also { it.soTimeout = 2000 } }
        try {
            // accept in background so connect succeeds
            val acceptors = servers.map { ss ->
                Thread {
                    try { while (!ss.isClosed) ss.accept().close() } catch (_: Exception) {}
                }.also { it.isDaemon = true; it.start() }
            }
            suspend fun run(parallel: Int): Long = withContext(Dispatchers.IO) {
                val sem = Semaphore(parallel)
                measureTimeMillis {
                    servers.map { ss ->
                        async {
                            sem.withPermit {
                                // ~80 ms artificial work + connect
                                Thread.sleep(80)
                                Socket().use { it.connect(InetSocketAddress("127.0.0.1", ss.localPort), 1000) }
                            }
                        }
                    }.awaitAll()
                }
            }
            val oldMs = run(4)
            val newMs = run(32)
            // With 24 × 80 ms: 4-wide ≈ 6 waves ≈ 480 ms+; 32-wide ≈ 1 wave ≈ 80 ms+
            assertTrue("old=$oldMs new=$newMs", newMs < oldMs)
            assertTrue("new should be under ~250 ms, was $newMs", newMs < 250)
            println("PingParallelBench: parallel4=${oldMs}ms parallel32=${newMs}ms (n=$n)")
        } finally {
            servers.forEach { runCatching { it.close() } }
        }
    }
}
