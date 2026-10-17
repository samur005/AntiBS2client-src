@file:Suppress("unused", "PackageDirectoryMismatch")
/*
 * Tiny desktop stand-ins for the handful of Android framework types the shared Kotlin code touches (Context with
 * filesDir / cacheDir / getSharedPreferences, Application, Log). They let the Android sources be reused nearly
 * verbatim on Windows; nothing here pretends to be more than that.
 */
package android.content

import java.io.File
import java.util.Properties

open class Context(val filesDir: File, val cacheDir: File) {
    open val applicationContext: Context get() = this
    open val packageName: String get() = "fun.antibs.client"

    private val prefsCache = mutableMapOf<String, SharedPreferences>()
    fun getSharedPreferences(name: String, @Suppress("UNUSED_PARAMETER") mode: Int): SharedPreferences =
        synchronized(prefsCache) { prefsCache.getOrPut(name) { FileSharedPreferences(File(filesDir, "prefs-$name.properties")) } }

    companion object { const val MODE_PRIVATE = 0 }
}

interface SharedPreferences {
    fun getString(key: String, def: String?): String?
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun contains(key: String): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, v: String?): Editor
        fun putBoolean(key: String, v: Boolean): Editor
        fun putInt(key: String, v: Int): Editor
        fun putLong(key: String, v: Long): Editor
        fun putFloat(key: String, v: Float): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

private class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val p = Properties().apply { runCatching { file.inputStream().use { load(it) } } }
    @Synchronized private fun raw(k: String): String? = p.getProperty(k)
    override fun getString(key: String, def: String?) = raw(key) ?: def
    override fun getBoolean(key: String, def: Boolean) = raw(key)?.toBooleanStrictOrNull() ?: def
    override fun getInt(key: String, def: Int) = raw(key)?.toIntOrNull() ?: def
    override fun getLong(key: String, def: Long) = raw(key)?.toLongOrNull() ?: def
    override fun getFloat(key: String, def: Float) = raw(key)?.toFloatOrNull() ?: def
    override fun contains(key: String) = raw(key) != null
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val puts = mutableMapOf<String, String?>(); var clear = false
        override fun putString(key: String, v: String?) = apply { puts[key] = v }
        override fun putBoolean(key: String, v: Boolean) = apply { puts[key] = v.toString() }
        override fun putInt(key: String, v: Int) = apply { puts[key] = v.toString() }
        override fun putLong(key: String, v: Long) = apply { puts[key] = v.toString() }
        override fun putFloat(key: String, v: Float) = apply { puts[key] = v.toString() }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(this@FileSharedPreferences) {
            if (clear) p.clear()
            puts.forEach { (k, v) -> if (v == null) p.remove(k) else p.setProperty(k, v) }
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp"); tmp.outputStream().use { p.store(it, null) }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.isSuccess
        }
    }
}
