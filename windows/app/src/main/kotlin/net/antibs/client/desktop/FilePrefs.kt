package net.antibs.client.desktop

import java.io.File

/** Where the Windows build keeps its data: %APPDATA%\AntiBS2client (or ~/.antibs2client elsewhere). */
object Paths {
    val home: File by lazy {
        val base = System.getProperty("antibs.home")?.let(::File)   // portable build: <app>\data
            ?: System.getenv("APPDATA")?.let { File(it, "AntiBS2client") } ?: File(System.getProperty("user.home"), ".antibs2client")
        base.apply { mkdirs() }
    }
    val run: File get() = File(home, "run").apply { mkdirs() }
    val logs: File get() = File(home, "logs").apply { mkdirs() }
    /** Directory the app was started from (xray.exe / sing-box.exe live in its «engines» subfolder). */
    val appDir: File by lazy {
        System.getProperty("antibs.appdir")?.let(::File)
            ?: runCatching { File(Paths::class.java.protectionDomain.codeSource.location.toURI()).parentFile.parentFile }.getOrNull()
            ?: File(".")
    }
    val engines: File get() = System.getProperty("antibs.engines")?.let(::File)
        ?: File(appDir, "engines").takeIf { it.isDirectory } ?: File(File("."), "engines")
}

/** Minimal SharedPreferences replacement: one key → string file, written atomically. */
class FilePrefs(private val dir: File) {
    fun getString(key: String, def: String?): String? = File(dir, "$key.json").takeIf { it.isFile }?.readText() ?: def
    fun edit() = Editor()
    inner class Editor {
        private val puts = mutableMapOf<String, String>()
        fun putString(k: String, v: String) = apply { puts[k] = v }
        fun apply() = puts.forEach { (k, v) ->
            val tmp = File(dir, "$k.json.tmp"); tmp.writeText(v)
            val dst = File(dir, "$k.json")
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
        }
        fun commit() = apply()
    }

    companion object {
        val store by lazy { FilePrefs(Paths.home) }
        private val logFile by lazy { File(Paths.logs, "app.log") }
        fun log(line: String) {
            runCatching {
                if (logFile.length() > 2_000_000) logFile.writeText("")
                logFile.appendText(java.time.LocalTime.now().withNano(0).toString() + " " + line + "\n")
            }
        }
    }
}
