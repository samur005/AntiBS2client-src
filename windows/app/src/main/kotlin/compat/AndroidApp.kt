@file:Suppress("unused", "PackageDirectoryMismatch")
package android.app

/** The single desktop "application" context: files in %APPDATA%\AntiBS2client. */
open class Application(home: java.io.File) : android.content.Context(home, java.io.File(home, "cache").apply { mkdirs() }) {
    companion object {
        val instance: Application by lazy { Application(net.antibs.client.desktop.Paths.home) }
    }
}
