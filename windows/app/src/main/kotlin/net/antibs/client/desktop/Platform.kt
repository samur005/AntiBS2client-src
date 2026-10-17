package net.antibs.client.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OS-specific bits behind small interfaces (system proxy, TUN privileges, autostart, engine binaries), so the shared
 * Compose/Kotlin code never touches the OS directly. Windows is the target; [LinuxDev] exists only for running the
 * app and tests on the build machine.
 */
interface DesktopPlatform {
    val id: String
    /** File name of an engine executable on this OS ("xray" → xray.exe). */
    fun exe(name: String): String
    /** TUN needs administrator rights. */
    fun isElevated(): Boolean
    /** Restart the app with admin rights (UAC prompt). true = a new elevated instance was requested. */
    fun relaunchElevated(args: List<String> = emptyList()): Boolean
    val systemProxy: SystemProxy
    val autostart: Autostart
    /** Names of our own engine processes that must bypass the TUN (sing-box process rules). */
    val engineProcessNames: List<String> get() = listOf(exe("xray"), exe("sing-box")) + listOfNotNull(ownJvmName())
}

/**
 * The packaged build runs on a renamed copy of javaw (runtime\bin\AntiBS2client-jvm.exe), so the app's own direct
 * requests (subscription refresh, «Мой IP», speed test «напрямую», updates) can bypass the TUN by process name
 * without excluding every other Java program on the PC. A plain java/javaw is never excluded.
 */
/** LAN and loopback bypass the system proxy (RFC 1918 ranges spelled out: «172.2*» would also catch public 172.2.x). */
internal val PROXY_OVERRIDE = (listOf("localhost", "127.*", "10.*") + (16..31).map { "172.$it.*" } + listOf("192.168.*", "169.254.*", "<local>")).joinToString(";")

internal fun ownJvmName(): String? {
    val name = ProcessHandle.current().info().command().orElse(null)?.let { File(it).name } ?: return null
    return name.takeIf { it.startsWith("AntiBS", ignoreCase = true) }
}

interface SystemProxy {
    val supported: Boolean
    /** Point the OS (WinINET / browsers) at our HTTP inbound. Remembers the previous settings. */
    fun enable(host: String, httpPort: Int, socksPort: Int): Result<Unit>
    /** Restore what was there before [enable] (also after a crash: a marker file survives restarts). */
    fun restore(): Result<Unit>
}

interface Autostart {
    val supported: Boolean
    fun isEnabled(): Boolean
    fun set(enabled: Boolean): Result<Unit>
}

object Platform {
    val current: DesktopPlatform by lazy {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) WindowsPlatform else LinuxDev
    }
}

internal fun run(vararg cmd: String, timeoutSec: Long = 15): Pair<Int, String> = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 to out } else p.exitValue() to out
} catch (e: Exception) { -1 to (e.message ?: "") }

object WindowsPlatform : DesktopPlatform {
    override val id = "windows"
    override fun exe(name: String) = "$name.exe"

    override fun isElevated(): Boolean = run("fltmc", timeoutSec = 5).first == 0

    override fun relaunchElevated(args: List<String>): Boolean {
        val launcher = System.getProperty("antibs.launcher")?.takeIf { File(it).isFile }
        val (file, argList) = if (launcher != null) launcher to args else {
            val java = File(System.getProperty("java.home"), "bin/javaw.exe").path
            val cp = System.getProperty("java.class.path")
            java to (listOf("-cp", cp, "net.antibs.client.desktop.MainKt") + args)
        }
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val ps = "Start-Process -FilePath ${q(file)} -Verb RunAs" + if (argList.isEmpty()) "" else " -ArgumentList " + argList.joinToString(",") { q(if (' ' in it) "\"$it\"" else it) }
        return run("powershell", "-NoProfile", "-NonInteractive", "-Command", ps, timeoutSec = 60).first == 0
    }

    private const val INET = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    /** Tells WinINET clients (Edge, Chrome, system apps) that the proxy settings changed. */
    private fun refreshInet() {
        val code = """
            ${'$'}sig = '[DllImport("wininet.dll")] public static extern bool InternetSetOption(System.IntPtr h, int o, System.IntPtr b, int l);'
            ${'$'}t = Add-Type -MemberDefinition ${'$'}sig -Name W -Namespace AntiBS -PassThru
            ${'$'}t::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0) | Out-Null
            ${'$'}t::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0) | Out-Null
        """.trimIndent()
        run("powershell", "-NoProfile", "-NonInteractive", "-Command", code, timeoutSec = 20)
    }

    private fun regQuery(name: String): String? {
        val (c, out) = run("reg", "query", INET, "/v", name)
        if (c != 0) return null
        return out.lineSequence().firstOrNull { it.trim().startsWith(name) }?.trim()?.split(Regex("\\s{2,}|\\t"))?.getOrNull(2)
    }

    override val systemProxy = object : SystemProxy {
        override val supported = true
        private val marker get() = File(Paths.home, "proxy-backup.txt")

        override fun enable(host: String, httpPort: Int, socksPort: Int): Result<Unit> = runCatching {
            if (!marker.isFile) {
                val en = regQuery("ProxyEnable") ?: "0x0"
                val srv = regQuery("ProxyServer") ?: ""
                val ov = regQuery("ProxyOverride") ?: ""
                marker.writeText("$en\n$srv\n$ov\n")
            }
            val server = "$host:$httpPort"
            check(run("reg", "add", INET, "/v", "ProxyServer", "/t", "REG_SZ", "/d", server, "/f").first == 0) { "не удалось записать ProxyServer" }
            run("reg", "add", INET, "/v", "ProxyOverride", "/t", "REG_SZ", "/d",
                PROXY_OVERRIDE, "/f")
            check(run("reg", "add", INET, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f").first == 0) { "не удалось включить прокси" }
            refreshInet()
        }

        override fun restore(): Result<Unit> = runCatching {
            val lines = marker.takeIf { it.isFile }?.readLines() ?: return@runCatching
            val en = if (lines.getOrNull(0)?.lowercase()?.endsWith("1") == true) "1" else "0"
            val srv = lines.getOrNull(1).orEmpty()
            val ov = lines.getOrNull(2).orEmpty()
            run("reg", "add", INET, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", en, "/f")
            if (srv.isNotEmpty()) run("reg", "add", INET, "/v", "ProxyServer", "/t", "REG_SZ", "/d", srv, "/f")
            else run("reg", "delete", INET, "/v", "ProxyServer", "/f")
            if (ov.isNotEmpty()) run("reg", "add", INET, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", ov, "/f")
            else run("reg", "delete", INET, "/v", "ProxyOverride", "/f")
            refreshInet()
            marker.delete()
        }
    }

    private const val RUN = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    override val autostart = object : Autostart {
        override val supported = true
        override fun isEnabled() = run("reg", "query", RUN, "/v", "AntiBS2client").first == 0
        override fun set(enabled: Boolean): Result<Unit> = runCatching {
            if (enabled) {
                val launcher = System.getProperty("antibs.launcher") ?: error("автозапуск доступен только из установленной/портативной версии")
                check(run("reg", "add", RUN, "/v", "AntiBS2client", "/t", "REG_SZ", "/d", "\"$launcher\" --minimized", "/f").first == 0)
            } else run("reg", "delete", RUN, "/v", "AntiBS2client", "/f")
        }
    }
}

/** Build-machine stand-in: no system proxy / TUN, engines from ./engines-linux. */
object LinuxDev : DesktopPlatform {
    override val id = "linux"
    override fun exe(name: String) = name
    override fun isElevated() = false
    override fun relaunchElevated(args: List<String>) = false
    override val systemProxy = object : SystemProxy {
        override val supported = false
        override fun enable(host: String, httpPort: Int, socksPort: Int) = Result.success(Unit)
        override fun restore() = Result.success(Unit)
    }
    override val autostart = object : Autostart {
        override val supported = false
        override fun isEnabled() = false
        override fun set(enabled: Boolean) = Result.failure<Unit>(UnsupportedOperationException())
    }
}
