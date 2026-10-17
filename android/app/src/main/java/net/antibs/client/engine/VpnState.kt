package net.antibs.client.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

data class VpnStatus(
    val state: ConnState = ConnState.DISCONNECTED,
    val serverName: String = "",
    /** Protocol of the outbound the service actually started (not the auto-select placeholder). */
    val protocol: String = "",
    val since: Long = 0,
    val message: String = "",
    val upBytes: Long = 0,
    val downBytes: Long = 0,
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    /** Xray "direct" outbound (freedom), i.e. traffic that bypasses the VPN proxy. */
    val directUpBytes: Long = 0,
    val directDownBytes: Long = 0,
    val directUpSpeed: Long = 0,
    val directDownSpeed: Long = 0,
    /** 1.0.19: auto-select / balancer — host (name) and protocol group Xray is routing through right now; empty until known. */
    val pickName: String = "",
    val pickProtocol: String = "",
)

object VpnState {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> get() = _status

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> get() = _logs

    /** Pending VK captcha request from the qWDTT engine (redirect URI), null if none. */
    private val _captcha = MutableStateFlow<String?>(null)
    val captcha: StateFlow<String?> get() = _captcha

    /** 1.0.36: one-shot notices from the VPN service for the UI («Автовыбор: переключено на …»). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> get() = _notice
    fun notice(text: String) { _notice.value = text }
    fun consumeNotice() { _notice.value = null }

    fun update(f: (VpnStatus) -> VpnStatus) { _status.update(f) }
    fun set(s: VpnStatus) { _status.value = s }

    private val logLock = Any()

    fun log(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(logLock) {
            val l = _logs.value
            _logs.value = (if (l.size > 1500) l.takeLast(1200) else l) + "$ts $line"
        }
        try { android.util.Log.i("AntiBS", line) } catch (_: Throwable) {}
    }

    fun clearLogs() { _logs.value = emptyList() }
    fun setCaptcha(uri: String?) { _captcha.value = uri }
}
