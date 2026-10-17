package net.antibs.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File

/** Desktop replacements for Android intents: clipboard, browser, file pickers, exit. */
object Desk {
    @Volatile var window: java.awt.Frame? = null
    @Volatile var exitHook: (() -> Unit)? = null

    fun copy(text: String) { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }
    fun paste(): String? = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()

    fun openUrl(url: String): Boolean = runCatching {
        if (net.antibs.client.desktop.Platform.current.id == "windows") ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
        else if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        else ProcessBuilder("xdg-open", url).start()
        true
    }.getOrDefault(false)

    fun openFolder(dir: File) { runCatching { if (net.antibs.client.desktop.Platform.current.id == "windows") ProcessBuilder("explorer", dir.path).start() else java.awt.Desktop.getDesktop().open(dir) } }

    fun saveFile(title: String, name: String): File? {
        val d = java.awt.FileDialog(window, title, java.awt.FileDialog.SAVE); d.file = name; d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    fun openFile(title: String): File? {
        val d = java.awt.FileDialog(window, title, java.awt.FileDialog.LOAD); d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    fun exit() { exitHook?.invoke() ?: kotlin.system.exitProcess(0) }
}

internal fun copyText(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context, text: String) = Desk.copy(text)

/** Esc = Android «Назад»: the newest enabled handler wins. */
object BackDispatcher {
    private val stack = mutableListOf<Pair<() -> Boolean, () -> Unit>>()
    fun push(e: Pair<() -> Boolean, () -> Unit>) = synchronized(stack) { stack.add(e) }
    fun remove(e: Pair<() -> Boolean, () -> Unit>) = synchronized(stack) { stack.remove(e) }
    fun back(): Boolean = synchronized(stack) { stack.lastOrNull { it.first() } }?.let { it.second(); true } ?: false
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val en = rememberUpdatedState(enabled)
    val cb = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val e: Pair<() -> Boolean, () -> Unit> = ({ en.value } to { cb.value() })
        BackDispatcher.push(e)
        onDispose { BackDispatcher.remove(e) }
    }
}
