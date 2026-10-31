package net.antibs.client.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.antibs.client.data.OpenConnectConfig
import net.antibs.client.data.OpenVpnConfig
import net.antibs.client.data.XrayProfile
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

/** 1.0.41: OpenVPN / OpenConnect passwords encrypted with an Android Keystore AES-GCM key («ks1:…»). */
object SecretBox {
    private const val ALIAS = "antibs-vpn-secrets"
    private const val PREFIX = "ks1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty() || plain.startsWith(PREFIX)) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) { VpnState.log("Keystore недоступен, пароль сохранён без шифрования: ${e.message}"); plain }
    }

    fun open(v: String?): String {
        if (v.isNullOrEmpty()) return ""
        if (!v.startsWith(PREFIX)) return v
        val b = Base64.decode(v.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }

    fun sealProfile(p: XrayProfile): XrayProfile =
        if (p.protocol == OpenVpnConfig.PROTOCOL || p.protocol == OpenConnectConfig.PROTOCOL || p.protocol == net.antibs.client.data.PacketVpnConfig.SSTP)
            p.copy(password = seal(p.password), privateKeyPassphrase = seal(p.privateKeyPassphrase)) else p
}

/**
 * 1.0.41: OpenVPN 2.6.15 / OpenConnect 9.12 behind libvpnstack.so (management client / script-tun → gVisor netstack →
 * loopback SOCKS5 [port] that Xray dials). Our app (and so these children) is outside our own VPN.
 */
class ClassicVpnEngine(private val ctx: Context, private val profile: XrayProfile, private val port: Int) {
    private var process: Process? = null
    @Volatile private var stopped = false
    @Volatile private var ready = false
    @Volatile var onExit: ((String) -> Unit)? = null
    private val lastLines = ArrayDeque<String>()
    private val label = if (profile.protocol == OpenVpnConfig.PROTOCOL) "OpenVPN" else "OpenConnect"

    fun start(timeoutMs: Long = 60_000) {
        val (cmd, cfg) = prepare(ctx, profile, port, "run", "", 30_000)
        VpnState.log("[$label] запуск: ${hostOf(profile)}, SOCKS 127.0.0.1:$port")
        val pb = ProcessBuilder(cmd).directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        val p = pb.start()
        process = p
        thread(name = "vpnstack-log", isDaemon = true) { readLoop(p, cfg) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ready) { VpnState.log("[$label] подключено"); return }
            if (!p.isAlive) throw IllegalStateException("$label: ${lastError() ?: "процесс завершился (код ${p.exitValue()})"}")
            Thread.sleep(100)
        }
        stop()
        throw IllegalStateException("$label не подключился за ${timeoutMs / 1000} с" + (lastError()?.let { ": $it" } ?: ""))
    }

    private fun lastError(): String? = synchronized(lastLines) {
        lastLines.lastOrNull { it.startsWith("ERR") }?.removePrefix("ERR ")?.take(240)
            ?: lastLines.lastOrNull { it.contains("AUTH_FAILED") || it.contains("error", true) || it.contains("failed", true) }?.take(240)
    }

    private fun readLoop(p: Process, cfg: File) {
        try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                if (line.startsWith("READY")) ready = true
                synchronized(lastLines) { lastLines.addLast(line); while (lastLines.size > 30) lastLines.removeFirst() }
                // cfg.json (with passwords) is no longer needed once the client runs
                if (line.startsWith("STATE") || line.startsWith("READY")) cfg.delete()
                if (!line.contains("MANAGEMENT: CMD")) VpnState.log("[$label] ${line.removePrefix("LOG ").take(300)}")
            }
        } catch (_: Exception) {}
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        cfg.delete()
        if (!stopped) onExit?.invoke("$label завершился (код $code)" + (lastError()?.let { ": $it" } ?: ""))
    }

    fun stop() {
        stopped = true
        val p = process ?: return
        process = null
        thread(isDaemon = true) {
            try {
                try { p.outputStream.apply { write("STOP\n".toByteArray()); flush() } } catch (_: Exception) {}
                if (!p.waitFor(3000, TimeUnit.MILLISECONDS)) { p.destroy(); if (!p.waitFor(1000, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
            } catch (_: Exception) {}
        }
    }

    companion object {
        private val live = java.util.concurrent.CopyOnWriteArrayList<ClassicVpnEngine>()
        fun register(e: ClassicVpnEngine) { live += e }
        fun stopAll() { live.forEach { it.stop() }; live.clear() }
        private val seq = java.util.concurrent.atomic.AtomicInteger(0)

        fun hostOf(p: XrayProfile) = if (p.protocol == OpenConnectConfig.PROTOCOL) OpenConnectConfig.host(p) else "${p.server}:${p.port}/${p.network}"

        /** Writes the helper config (and the sanitized .ovpn) — returns the command line and the cfg file to delete. */
        fun prepare(ctx: Context, p: XrayProfile, port: Int, mode: String, url: String, timeoutMs: Int): Pair<List<String>, File> {
            val lib = ctx.applicationInfo.nativeLibraryDir
            val self = File(lib, "libvpnstack.so")
            if (!self.exists()) throw IllegalStateException("libvpnstack.so не найден")
            val n = seq.getAndIncrement()
            val dir = File(ctx.noBackupFilesDir, "vpn").apply { mkdirs() }
            val cfg = File(dir, "cfg-$port-$n.json")
            val json = if (p.protocol == OpenVpnConfig.PROTOCOL) {
                val text = p.extra ?: throw IllegalStateException("OpenVPN: пустой конфиг")
                val s = OpenVpnConfig.summary(text)
                if (s.errors.isNotEmpty()) throw IllegalStateException("OpenVPN: " + s.errors.first())
                val ovpn = File(dir, "p-$port-$n.ovpn"); ovpn.writeText(OpenVpnConfig.sanitize(text))
                buildJsonObject {
                    put("mode", mode); put("port", port); put("url", url); put("timeout_ms", timeoutMs)
                    put("bin", File(lib, "libopenvpn.so").absolutePath); put("config", ovpn.absolutePath)
                    put("mgmt", File(dir, "m-$port-$n.sock").absolutePath)
                    put("user", p.username.orEmpty()); put("pass", SecretBox.open(p.password)); put("key_pass", SecretBox.open(p.privateKeyPassphrase))
                    put("otp", p.olc?.get("otp").orEmpty())
                }
            } else {
                val o = p.olc.orEmpty()
                buildJsonObject {
                    put("mode", mode); put("port", port); put("url", url); put("timeout_ms", timeoutMs)
                    put("bin", File(lib, "libopenconnect.so").absolutePath); put("self", self.absolutePath)
                    put("server", p.server); put("protocol", o["proto"] ?: "anyconnect")
                    put("user", p.username.orEmpty()); put("pass", SecretBox.open(p.password))
                    put("group", o["group"].orEmpty()); put("cert_pin", o["pin"].orEmpty()); put("user_agent", o["ua"].orEmpty())
                    putJsonArray("answers") { o["answers"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
                }
            }
            cfg.writeText(json.toString())
            val sub = if (p.protocol == OpenVpnConfig.PROTOCOL) "ovpn" else "oc"
            return listOf(self.absolutePath, sub, cfg.absolutePath) to cfg
        }

        /** Real ping: connect a throw-away client, GET [url] through it. -1 = failed, -2 = n/a. Blocking. */
        fun ping(ctx: Context, p: XrayProfile, url: String, timeoutMs: Int): Long {
            if (p.protocol == net.antibs.client.data.PacketVpnConfig.SSTP || p.protocol == net.antibs.client.data.PacketVpnConfig.ZEROTIER) return PacketVpnEngine.ping(p, timeoutMs)
            val port = net.antibs.client.data.XrayConfigBuilder.VPN_BASE_PORT + 50 + (seq.get() and 31)
            val t = timeoutMs.coerceIn(10_000, 40_000)
            val (cmd, cfg) = try { prepare(ctx, p, port, "ping", url, t) } catch (e: Exception) { VpnState.log("пинг: ${e.message}"); return -2L }
            return try {
                val pb = ProcessBuilder(cmd).directory(ctx.cacheDir).redirectErrorStream(true)
                pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
                val proc = pb.start()
                var res = -1L
                val r = thread(isDaemon = true) {
                    try {
                        proc.inputStream.bufferedReader().forEachLine { l ->
                            val s = l.trim()
                            if (s.startsWith("PING ")) res = s.removePrefix("PING ").toLongOrNull()?.coerceAtLeast(1) ?: -1L
                            else if (s.startsWith("ERR ")) VpnState.log("пинг «${p.remarks}»: ${s.removePrefix("ERR ").take(200)}")
                        }
                    } catch (_: Exception) {}
                }
                if (!proc.waitFor(t + 35_000L, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
                r.join(500)
                res
            } catch (e: Exception) { -1L } finally { cfg.delete() }
        }
    }
}
