package net.antibs.client.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import net.antibs.client.data.QwdttProfile
import net.antibs.client.data.WgConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * qWDTT engine: runs the qWDTT Go client (SpaceNeuroX/proxy-turn-vk-android go_client, GPL-3.0) as a child
 * process in "-mode vpn". The client fetches VK TURN credentials, builds DTLS/RTP-obfuscated TURN tunnels to the
 * qWDTT server and exposes a local UDP endpoint (127.0.0.1:<port>) for WireGuard. It prints the WireGuard
 * [Interface]/[Peer] config it received from the server inside a ╔══ WireGuard ══╗ … ╚═╝ box. We then feed that
 * WireGuard config into Xray's wireguard outbound so the same TUN → hev-socks5-tunnel → Xray path is used.
 */
class QwdttEngine(
    private val ctx: Context,
    private val profile: QwdttProfile,
    private val globalHashes: String,
    private val dns: String,
    private val obfs: String,
    private val turnTcp: Boolean,
    private val onWgConfig: (WgConfig) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    @Volatile private var stopped = false

    fun hashes(): List<String> = (profile.hashes.ifBlank { globalHashes })
        .split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(12)

    @SuppressLint("HardwareIds")
    fun start() {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libqwdtt.so")
        if (!bin.exists()) throw IllegalStateException("qWDTT binary missing: ${bin.absolutePath}")
        val hs = hashes()
        if (hs.isEmpty()) throw IllegalStateException("Нет VK-хешей: укажите их в Настройках → qWDTT")
        if (profile.password.isBlank()) throw IllegalStateException("В профиле qWDTT нет пароля")
        writeCaptchaProfile()
        val deviceId = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        val maxWorkers = hs.size * 9 * 3
        val cmd = mutableListOf(
            bin.absolutePath,
            "-peer", profile.peer,
            "-vk", hs.joinToString(","),
            "-n", profile.workers.coerceIn(1, maxWorkers).toString(),
            "-listen", "127.0.0.1:${profile.port}",
            "-device-id", deviceId,
            "-password", profile.password,
            "-captcha-mode", "auto",
            "-vk-auth", "anonymous",
            "-vk-anon-path", "vkcalls",
            "-go-dns", dns,
            "-obfs", obfs,
            "-mode", "vpn",
        )
        if (turnTcp) cmd.add("-turn-tcp")
        VpnState.log("[qWDTT] запуск: peer=${profile.peer} hashes=${hs.size} workers=${profile.workers} port=${profile.port}")
        val pb = ProcessBuilder(cmd).directory(ctx.filesDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        val p = pb.start()
        process = p
        stdin = OutputStreamWriter(p.outputStream)
        thread(name = "qwdtt-log", isDaemon = true) { readLoop(p) }
    }

    private fun readLoop(p: Process) {
        var collecting = false
        val cfg = StringBuilder()
        var lastCfg: String? = null
        try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "")
                when {
                    line.contains("╔") && line.contains("WireGuard") -> { collecting = true; cfg.clear() }
                    collecting && line.contains("╚") -> {
                        collecting = false
                        val text = cfg.toString()
                        val wg = WgConfig.parse(text)
                        if (wg == null) VpnState.log("[qWDTT] не удалось разобрать WireGuard-конфиг")
                        else if (text != lastCfg) { lastCfg = text; VpnState.log("[qWDTT] получен WireGuard-конфиг (${wg.addresses.joinToString()}, MTU ${wg.mtu})"); onWgConfig(wg) }
                    }
                    collecting -> cfg.appendLine(line.replace("║", "").trim())
                    line.startsWith("CAPTCHA_SOLVE|") -> {
                        val parts = line.removePrefix("CAPTCHA_SOLVE|").split("|")
                        val uri = parts.getOrNull(1).orEmpty()
                        VpnState.log("[qWDTT] VK запросил капчу — откройте уведомление/приложение, чтобы решить")
                        if (uri.isNotEmpty()) { VpnState.setCaptcha(uri); notifyCaptcha(uri) } else submitCaptcha("error:no uri")
                    }
                    line.contains("FATAL_AUTH") -> { VpnState.log(line); onFatal("qWDTT: ошибка авторизации (пароль/срок/устройство)") }
                    else -> if (!line.contains("STATS|") && line.isNotBlank()) VpnState.log("[qWDTT] ${line.take(300)}")
                }
            }
        } catch (_: Exception) {
        }
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        if (!stopped) onFatal("qWDTT клиент завершился (код $code)")
    }

    private fun notifyCaptcha(uri: String) {
        try {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("antibs_captcha", "VK captcha", android.app.NotificationManager.IMPORTANCE_HIGH))
            val i = android.content.Intent(ctx, net.antibs.client.vk.CaptchaActivity::class.java)
                .putExtra("uri", uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(ctx, 7, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(ctx, "antibs_captcha")
                .setSmallIcon(net.antibs.client.R.drawable.ic_stat_vpn)
                .setContentTitle("qWDTT: VK просит капчу")
                .setContentText("Нажмите, чтобы подтвердить, что вы не робот")
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(7, n)
            try { ctx.startActivity(i) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    fun submitCaptcha(result: String) {
        try { ctx.getSystemService(android.app.NotificationManager::class.java).cancel(7) } catch (_: Exception) {}
        try {
            stdin?.apply { write("CAPTCHA_RESULT|$result\n"); flush() }
        } catch (_: Exception) {}
        VpnState.setCaptcha(null)
    }

    fun stop() {
        stopped = true
        try { stdin?.apply { write("STOP\n"); flush() } } catch (_: Exception) {}
        val p = process ?: return
        thread(isDaemon = true) {
            try {
                Thread.sleep(800)
                p.destroy()
                Thread.sleep(700)
                if (p.isAlive) p.destroyForcibly()
            } catch (_: Exception) {}
        }
        process = null
        VpnState.setCaptcha(null)
    }

    /** vk_profile.json for the Go RJS captcha solver (same format as qWDTT VkCaptchaProfile.writeForGo). */
    private fun writeCaptchaProfile() {
        try {
            val chrome = listOf(124, 126, 128, 130, 131, 133).random()
            val ua = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL ?: "Unknown"}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.${Random.nextInt(0, 99)}.0 Mobile Safari/537.36"
            val m = ctx.resources.displayMetrics
            val w = m.widthPixels.coerceAtLeast(320); val h = m.heightPixels.coerceAtLeast(480)
            val device = JSONObject().apply {
                put("screenWidth", w); put("screenHeight", h); put("screenAvailWidth", w); put("screenAvailHeight", h)
                put("innerWidth", w); put("innerHeight", (h * 0.88).toInt()); put("devicePixelRatio", listOf(2.0, 2.5, 3.0).random())
                put("language", "ru-RU"); put("languages", JSONArray(listOf("ru-RU", "ru", "en-US")))
                put("webdriver", false); put("hardwareConcurrency", 8); put("notificationsPermission", "default")
            }
            val fp = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val prof = JSONObject().apply {
                put("user_agent", ua)
                put("sec_ch_ua", "\"Chromium\";v=\"$chrome\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"$chrome\"")
                put("sec_ch_ua_mobile", "?1"); put("sec_ch_ua_platform", "\"Android\"")
                put("device_json", device.toString()); put("browser_fp", fp)
            }
            File(ctx.filesDir, "vk_profile.json").writeText(prof.toString())
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile var current: QwdttEngine? = null
    }
}
