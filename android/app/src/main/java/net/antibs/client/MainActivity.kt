package net.antibs.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.antibs.client.ui.AppRoot
import net.antibs.client.ui.AppViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleShare(intent)
        setContent {
            val vm: AppViewModel = viewModel()
            // 1.0.41: shared / opened .ovpn, olcrtc:// and openconnect:// → import
            val shared by incoming.collectAsState()
            LaunchedEffect(shared) { shared?.let { vm.importRaw(it); incoming.value = null } }
            val snackbar = remember { SnackbarHostState() }
            val toast by vm.toast.collectAsState()
            LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.consumeToast() } }
            LaunchedEffect(Unit) { vm.autoCheckUpdates() }
            // 1.0.25: back to the app → refresh subscriptions older than the auto-update interval.
            androidx.lifecycle.compose.LifecycleStartEffect(vm) {
                vm.refreshStale()
                onStopOrDispose { }
            }
            // 1.0.22: «Хост удалён» + «Отменить».
            val undo by vm.undo.collectAsState()
            LaunchedEffect(undo) {
                val u = undo ?: return@LaunchedEffect
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar("Хост удалён", actionLabel = "Отменить", duration = androidx.compose.material3.SnackbarDuration.Long)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undoDelete(u)
                vm.consumeUndo()
            }
            Box {
                AppRoot(vm)
                SnackbarHost(
                    snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: android.content.Intent?) {
        i ?: return
        // 1.0.43: return from the shop's payment page
        if (i.action == android.content.Intent.ACTION_VIEW) {
            net.antibs.client.shop.Panel.parseReturn(i.data?.toString().orEmpty())?.let { paymentReturn.value = it; return }
        }
        val text: String? = try {
            when (i.action) {
                android.content.Intent.ACTION_VIEW -> i.data?.let { u ->
                    if (u.scheme == "content" || u.scheme == "file") contentResolver.openInputStream(u)?.use { it.readBytes().take(512 * 1024).toByteArray().toString(Charsets.UTF_8) }
                    else u.toString()
                }
                android.content.Intent.ACTION_SEND -> i.getStringExtra(android.content.Intent.EXTRA_TEXT)
                    ?: (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(android.content.Intent.EXTRA_STREAM))
                        ?.let { u -> contentResolver.openInputStream(u)?.use { it.readBytes().take(512 * 1024).toByteArray().toString(Charsets.UTF_8) } }
                else -> null
            }
        } catch (_: Exception) { null }
        if (!text.isNullOrBlank()) incoming.value = text
    }

    companion object {
        val incoming = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        /** Order id from antibs2client://payment-return (consumed by «Магазин»). */
        val paymentReturn = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}
