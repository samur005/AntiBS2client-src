package net.antibs.client.engine

import android.annotation.SuppressLint
import android.content.Context
import net.antibs.client.data.QwdttProfile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 1.0.30: real ping for qWDTT hosts. qWDTT has no TCP/ICMP-reachable proxy port and no Xray outbound to
 * URL-test (it works over VK TURN relays), so we run the bundled Go client in its own `-ping-only` mode:
 * VK TURN credentials → TURN allocate → DTLS handshake with the qWDTT server (WRAP key from the password).
 * The reported value is that round trip; a wrong peer/password or a dead server never finishes the handshake.
 */
object QwdttPing {
    const val MIN_TIMEOUT_MS = 12_000
    const val MAX_TIMEOUT_MS = 25_000

    /** The VK credential step needs a few seconds on top of the user's ping timeout. */
    fun timeoutFor(pingTimeoutMs: Int): Int = (pingTimeoutMs + 6_000).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    fun hashes(profile: QwdttProfile, globalHashes: String): List<String> =
        profile.hashes.ifBlank { globalHashes }.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(12)

    fun args(bin: String, profile: QwdttProfile, hashes: List<String>, deviceId: String, dns: String, obfs: String, turnTcp: Boolean): List<String> {
        val cmd = mutableListOf(
            bin, "-ping-only",
            "-peer", profile.peer,
            "-vk", hashes.joinToString(","),
            "-password", profile.password,
            "-device-id", deviceId,
            "-captcha-mode", "auto",
            "-vk-auth", "anonymous",
            "-vk-anon-path", "vkcalls",
            "-go-dns", dns,
            "-obfs", obfs,
        )
        if (turnTcp) cmd.add("-turn-tcp")
        return cmd
    }

    sealed class Line {
        data class Ok(val ms: Long) : Line()
        data class Error(val message: String) : Line()
        data object Captcha : Line()
        data object Other : Line()
    }

    fun parseLine(raw: String): Line {
        val line = raw.trim()
        return when {
            line.startsWith("PING_RESULT|") -> line.removePrefix("PING_RESULT|").trim().toLongOrNull()?.takeIf { it > 0 }
                ?.let { Line.Ok(it) } ?: Line.Error("bad result")
            line.startsWith("PING_ERROR|") -> Line.Error(line.removePrefix("PING_ERROR|").take(200))
            line.startsWith("CAPTCHA_SOLVE|") -> Line.Captcha
            else -> Line.Other
        }
    }

    /** -1 = failed / timed out, -2 = not applicable (no hashes / password), otherwise ms. Blocking. */
    @SuppressLint("HardwareIds")
    fun run(ctx: Context, profile: QwdttProfile, globalHashes: String, dns: String, obfs: String, turnTcp: Boolean, timeoutMs: Int): Long {
        val hs = hashes(profile, globalHashes)
        if (hs.isEmpty() || profile.password.isBlank() || profile.peer.isBlank()) return -2L
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libqwdtt.so")
        if (!bin.exists()) return -2L
        val deviceId = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        val pb = ProcessBuilder(args(bin.absolutePath, profile, hs, deviceId, dns, obfs, turnTcp)).directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        val p = try { pb.start() } catch (e: Exception) { VpnState.log("[qWDTT] пинг: ${e.message}"); return -1L }
        val result = java.util.concurrent.atomic.AtomicLong(-1L)
        val reader = thread(name = "qwdtt-ping", isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { l ->
                    when (val r = parseLine(l.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), ""))) {
                        is Line.Ok -> result.set(r.ms)
                        is Line.Error -> VpnState.log("[qWDTT] пинг «${profile.name}»: ${r.message}")
                        // never pop a captcha for a background ping: decline, the host is reported as failed
                        is Line.Captcha -> try { p.outputStream.apply { write("CAPTCHA_RESULT|error:ping\n".toByteArray()); flush() } } catch (_: Exception) {}
                        else -> Unit
                    }
                }
            } catch (_: Exception) {}
        }
        try {
            if (!p.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                p.destroy()
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            p.destroyForcibly()
        }
        reader.join(500)
        return result.get()
    }
}
