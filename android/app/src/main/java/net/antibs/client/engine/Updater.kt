package net.antibs.client.engine

import kotlinx.coroutines.ensureActive
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.antibs.client.data.UpdateLogic
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 1.0.19: GitHub-releases updater (check → download to cache with SHA-256 check → package installer). */
object Updater {
    private const val UA = "AntiBS-client-updater"

    private fun open(url: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            accept?.let { setRequestProperty("Accept", it) }
        }

    /** null = up to date; throws with a user-readable message on errors. */
    suspend fun check(current: String, override: String): UpdateLogic.Update? = withContext(Dispatchers.IO) {
        val c = open(UpdateLogic.apiUrl(override), "application/vnd.github+json")
        try {
            val code = c.responseCode
            val headers = c.headerFields.orEmpty().filterKeys { it != null }.mapValues { it.value.lastOrNull().orEmpty() }.mapKeys { it.key!! }
            UpdateLogic.rateLimitMessage(code, headers, System.currentTimeMillis() / 1000)?.let { throw IllegalStateException(it) }
            if (code == 404) throw IllegalStateException("Релизы не найдены")
            if (code !in 200..299) throw IllegalStateException("GitHub: HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val r = UpdateLogic.parseRelease(body) ?: throw IllegalStateException("Не удалось разобрать ответ GitHub")
            UpdateLogic.findUpdate(r, current, Build.SUPPORTED_ABIS.toList())
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val b = ByteArray(256 * 1024); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads the APK into cacheDir/updates and verifies it against SHA256SUMS (when the release has one). */
    suspend fun download(ctx: Context, u: UpdateLogic.Update, progress: (done: Long, total: Long) -> Unit): File = withContext(Dispatchers.IO) {
        val expected = u.sums?.let { s ->
            val c = open(s.url)
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("SHA256SUMS: HTTP ${c.responseCode}")
                UpdateLogic.parseSums(c.inputStream.bufferedReader().use { it.readText() })[u.apk.name]
                    ?: throw IllegalStateException("В SHA256SUMS нет ${u.apk.name}")
            } finally { c.disconnect() }
        }
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, u.apk.name)
        val c = open(u.apk.url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Загрузка: HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: u.apk.size
            var done = 0L
            var lastEmit = 0L
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val b = ByteArray(128 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()  // 1.0.54: «Отмена»
                        val n = inp.read(b); if (n < 0) break
                        o.write(b, 0, n); done += n
                        val now = System.nanoTime()
                        if (now - lastEmit > 100_000_000L) { lastEmit = now; progress(done, total) }
                    }
                }
            }
            progress(done, total)
        } catch (e: Throwable) { out.delete(); throw e } finally { c.disconnect() }
        if (expected != null) {
            val got = sha256(out)
            if (!got.equals(expected, true)) { out.delete(); throw IllegalStateException("SHA-256 не совпадает — файл повреждён или подменён") }
        }
        out
    }

    /** 1.0.54: drop a partial / cancelled download. */
    fun clearPartial(ctx: Context) { File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() } }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** «Установка неизвестных приложений» for this app. */
    fun openInstallPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
