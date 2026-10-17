@file:Suppress("unused", "PackageDirectoryMismatch")
package androidx.lifecycle

/** Desktop AndroidViewModel: a multiplatform ViewModel (viewModelScope on the Swing main dispatcher) holding the app context. */
open class AndroidViewModel(private val app: android.app.Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : android.app.Application> getApplication(): T = app as T
}
