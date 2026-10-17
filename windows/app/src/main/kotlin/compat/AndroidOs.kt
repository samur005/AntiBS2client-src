@file:Suppress("unused", "PackageDirectoryMismatch")
package android.os

object SystemClock {
    fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
