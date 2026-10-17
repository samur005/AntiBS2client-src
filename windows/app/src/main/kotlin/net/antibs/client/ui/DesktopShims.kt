package net.antibs.client.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import net.antibs.client.desktop.Paths
import java.io.File
import java.io.InputStream
import java.util.Properties

/** Small key/value UI preferences (Android's SharedPreferences "ui_world" etc.) in %APPDATA%\AntiBS2client\ui.properties. */
object UiPrefs {
    private val file get() = File(Paths.home, "ui.properties")
    private val props: Properties by lazy { Properties().apply { runCatching { file.inputStream().use { load(it) } } } }
    @Synchronized fun getString(k: String, def: String?): String? = props.getProperty(k) ?: def
    fun getBoolean(k: String, def: Boolean) = getString(k, null)?.toBooleanStrictOrNull() ?: def
    fun getInt(k: String, def: Int) = getString(k, null)?.toIntOrNull() ?: def
    fun edit() = Editor()
    class Editor {
        private val m = mutableMapOf<String, String>()
        fun putString(k: String, v: String) = apply { m[k] = v }
        fun putBoolean(k: String, v: Boolean) = apply { m[k] = v.toString() }
        fun putInt(k: String, v: Int) = apply { m[k] = v.toString() }
        fun apply() = synchronized(UiPrefs) {
            m.forEach { (k, v) -> props.setProperty(k, v) }
            runCatching { file.outputStream().use { props.store(it, "AntiBS2client UI") } }
            Unit
        }
    }
}

/** Snackbar messages from anywhere (Android used Toasts). */
object UiMessages {
    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val flow: SharedFlow<String> get() = _flow
    fun show(text: String) { _flow.tryEmit(text) }
}

internal fun resource(name: String): InputStream =
    UiPrefs::class.java.getResourceAsStream("/$name") ?: error("resource $name missing")

/** ARGB (non-premultiplied) pixels → Compose image (Skia N32 = BGRA on x86, i.e. little-endian ARGB ints). */
internal fun argbToImage(buf: IntArray, n: Int): ImageBitmap {
    val bytes = java.nio.ByteBuffer.allocate(buf.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(buf)
    val info = org.jetbrains.skia.ImageInfo(n, n, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
    return org.jetbrains.skia.Image.makeRaster(info, bytes.array(), n * 4).toComposeImageBitmap()
}

/**
 * Device position for the «моё местоположение» button. Windows: the system location service via .NET
 * GeoCoordinateWatcher (one-shot, only while the window is shown); the place name via OpenStreetMap Nominatim (ru).
 */
object DeviceLocation {
    class Fix(val latitude: Double, val longitude: Double)

    fun granted(): Boolean = true

    suspend fun current(): Fix? = withContext(Dispatchers.IO) {
        if (net.antibs.client.desktop.Platform.current.id != "windows") return@withContext null
        val ps = """
            Add-Type -AssemblyName System.Device
            ${'$'}w = New-Object System.Device.Location.GeoCoordinateWatcher('Default')
            ${'$'}null = ${'$'}w.TryStart(${'$'}false, [TimeSpan]::FromSeconds(10))
            ${'$'}t = 0
            while ((${'$'}w.Status -ne 'Ready' -or ${'$'}w.Position.Location.IsUnknown) -and ${'$'}t -lt 100) { Start-Sleep -Milliseconds 100; ${'$'}t++ }
            ${'$'}l = ${'$'}w.Position.Location
            if (${'$'}l.IsUnknown) { 'unknown' } else { ${'$'}l.Latitude.ToString([cultureinfo]::InvariantCulture) + ';' + ${'$'}l.Longitude.ToString([cultureinfo]::InvariantCulture) }
        """.trimIndent()
        val (code, out) = net.antibs.client.desktop.run("powershell", "-NoProfile", "-NonInteractive", "-Command", ps, timeoutSec = 25)
        if (code != 0) return@withContext null
        val parts = out.trim().lineSequence().lastOrNull()?.split(';') ?: return@withContext null
        val la = parts.getOrNull(0)?.toDoubleOrNull(); val lo = parts.getOrNull(1)?.toDoubleOrNull()
        if (la == null || lo == null) null else Fix(la, lo)
    }

    suspend fun place(lat: Double, lon: Double): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = okhttp3.OkHttpClient.Builder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&accept-language=ru&lat=$lat&lon=$lon"
            val body = c.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "AntiBS2client-desktop").build()).execute().use { it.body?.string() }
                ?: return@runCatching null
            val a = kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }["address"] as? kotlinx.serialization.json.JsonObject
                ?: return@runCatching null
            fun f(k: String) = (a[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            (f("city") ?: f("town") ?: f("village") ?: f("state") ?: "") to (f("country") ?: "")
        }.getOrNull()
    }
}
