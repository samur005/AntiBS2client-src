@file:Suppress("unused", "PackageDirectoryMismatch")
package android.util

object Log {
    fun i(tag: String, msg: String): Int { net.antibs.client.desktop.FilePrefs.log("I/$tag: $msg"); return 0 }
    fun w(tag: String, msg: String, t: Throwable? = null): Int { net.antibs.client.desktop.FilePrefs.log("W/$tag: $msg ${t?.message ?: ""}"); return 0 }
    fun e(tag: String, msg: String, t: Throwable? = null): Int { net.antibs.client.desktop.FilePrefs.log("E/$tag: $msg ${t?.message ?: ""}"); return 0 }
    fun d(tag: String, msg: String): Int = 0
}
