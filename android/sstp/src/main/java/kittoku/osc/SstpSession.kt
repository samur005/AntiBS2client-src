package kittoku.osc

import android.content.Context
import android.content.SharedPreferences
import kittoku.osc.control.Controller
import kittoku.osc.control.LogWriter
import kittoku.osc.preference.AUTH_PROTOCOL_EAP_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOL_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOl_PAP
import kittoku.osc.preference.OscPrefKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** IP parameters negotiated by IPCP; the host turns them into a packet pipe (framed: 2-byte length + packet). */
data class SstpTunnel(val ipv4: String, val dns: String?, val mtu: Int)

/**
 * AntiBS2client glue: runs the Open SSTP Client state machine without its own VpnService.
 * [protect] keeps the server socket outside the VPN; [openTunnel] gets the negotiated IP and returns the packet streams.
 */
class SstpSession(
    private val ctx: Context,
    internal val protect: (Socket) -> Boolean,
    internal val openTunnel: (SstpTunnel) -> Pair<InputStream, OutputStream>,
    private val onClosed: (String?) -> Unit,
) {
    data class Params(val host: String, val port: Int, val user: String, val password: String, val verifyCert: Boolean, val sni: String = "")

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val context: Context get() = ctx
    internal val logWriter: LogWriter? = null
    private var controller: Controller? = null
    @Volatile var connected = false; internal set
    @Volatile private var closed = false

    /** IP bytes through the tunnel: tx = app → server (upload), rx = server → app (download). */
    val txBytes = AtomicLong()
    val rxBytes = AtomicLong()

    fun start(p: Params) {
        SstpLog.reset()
        writePrefs(prefs(ctx), p)
        controller = Controller(SharedBridge(this)).also { it.launchJobMain() }
    }

    fun stop() {
        if (closed) return
        controller?.disconnect()
    }

    internal fun notifyError(message: String) { SstpLog.i(message) }
    internal fun launchJobReconnect() = close()
    internal fun close() {
        if (closed) return
        closed = true; connected = false
        scope.cancel()
        onClosed(SstpLog.lastFailure?.let { errorText(it) })
    }

    companion object {
        internal fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("sstp_client", Context.MODE_PRIVATE)

        /** Ints are stored as strings (the upstream client uses EditTextPreference). */
        internal fun writePrefs(sp: SharedPreferences, p: Params) {
            sp.edit()
                .putString(OscPrefKey.HOME_HOSTNAME.name, p.host.trim())
                .putString(OscPrefKey.HOME_USERNAME.name, p.user)
                .putString(OscPrefKey.HOME_PASSWORD.name, p.password)
                .putString(OscPrefKey.SSL_PORT.name, (if (p.port in 1..65535) p.port else 443).toString())
                .putString(OscPrefKey.SSL_VERSION.name, "DEFAULT")
                .putString(OscPrefKey.PPP_MTU.name, "1400")
                .putString(OscPrefKey.PPP_MRU.name, "1400")
                .putString(OscPrefKey.PPP_AUTH_TIMEOUT.name, "10")
                .putBoolean(OscPrefKey.RECONNECTION_ENABLED.name, false)
                .putBoolean(OscPrefKey.SSL_DO_SPECIFY_CERT.name, false)
                .putBoolean(OscPrefKey.PROXY_DO_USE_PROXY.name, false)
                .putBoolean(OscPrefKey.LOG_DO_SAVE_LOG.name, false)
                .putBoolean(OscPrefKey.SSL_DO_VERIFY.name, p.verifyCert)
                .putBoolean(OscPrefKey.SSL_DO_USE_CUSTOM_SNI.name, p.sni.isNotBlank())
                .putString(OscPrefKey.SSL_CUSTOM_SNI.name, p.sni.trim())
                .putStringSet(OscPrefKey.PPP_AUTH_PROTOCOLS.name, setOf(AUTH_PROTOCOl_PAP, AUTH_PROTOCOL_MSCHAPv2, AUTH_PROTOCOL_EAP_MSCHAPv2))
                .putBoolean(OscPrefKey.PPP_IPv4_ENABLED.name, true)
                .putBoolean(OscPrefKey.PPP_IPv6_ENABLED.name, false)
                .putBoolean(OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE.name, true)
                .putBoolean(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE.name, false)
                .putBoolean(OscPrefKey.ROUTE_DO_ADD_CUSTOM_ROUTES.name, false)
                .putBoolean(OscPrefKey.DNS_DO_REQUEST_ADDRESS.name, true)
                .putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.name, false)
                .commit()
        }

        private fun lastPpp(stage: String) = SstpLog.lastPpp[stage]?.let { " (последний ответ сервера: ${it.take(120)})" } ?: " (сервер не прислал ни одного кадра $stage)"

        /** Russian error text for the last failure. */
        fun errorText(f: SstpLog.Failure): String = when {
            f.result == "ERR_AUTHENTICATION_FAILED" -> "SSTP: неверный логин или пароль"
            f.where == "SSL" && f.result == "ERR_VERIFICATION_FAILED" -> "SSTP: сертификат сервера не прошёл проверку (можно отключить проверку)"
            f.where == "SSL" || f.where == "CERT" -> "SSTP: ошибка TLS-подключения" + (f.detail?.let { " — ${it.lineSequence().first().take(160)}" } ?: "")
            f.where == "LCP" && f.result == "ERR_TIMEOUT" -> "SSTP: LCP — нет ответа от сервера за 30 с" + lastPpp("LCP")
            f.where == "LCP" && f.result == "ERR_COUNT_EXHAUSTED" -> "SSTP: LCP — согласование не сошлось за 10 попыток" + lastPpp("LCP")
            f.where == "LCP_MRU" -> "SSTP: LCP — сервер отклонил опцию MRU"
            f.where == "LCP_AUTH" || (f.where == "LCP" && f.result == "ERR_OPTION_REJECTED") -> "SSTP: LCP — сервер требует неподдерживаемый способ авторизации" + lastPpp("LCP")
            f.where == "LCP" && f.result == "ERR_TERMINATE_REQUESTED" -> "SSTP: LCP — сервер разорвал согласование (Terminate-Request)"
            f.where == "LCP" && f.result == "ERR_PROTOCOL_REJECTED" -> "SSTP: LCP — сервер отверг протокол (Protocol-Reject)"
            f.where == "IPCP" && f.result == "ERR_TIMEOUT" -> "SSTP: IPCP — сервер не выдал IP-адрес за 30 с" + lastPpp("IPCP")
            f.where == "IPCP" && f.result == "ERR_COUNT_EXHAUSTED" -> "SSTP: IPCP — согласование адреса не сошлось" + lastPpp("IPCP")
            f.where in setOf("CHAP", "MSCHAPV2", "PAP", "EAP") && f.result == "ERR_TIMEOUT" -> "SSTP: авторизация (${f.where}) — сервер не ответил"
            f.result == "ERR_TIMEOUT" -> "SSTP: сервер не ответил (${f.where})"
            f.where == "SSTP_CONTROL" && f.result == "ERR_ABORT_REQUESTED" -> "SSTP: сервер прервал соединение (Call-Abort) на этапе после ${SstpLog.recent.lastOrNull { it.startsWith("stage ") } ?: "SSTP"}"
            f.result == "ERR_UNEXPECTED" -> "SSTP: внутренняя ошибка — ${f.detail?.lineSequence()?.first()?.take(160)}"
            else -> "SSTP: ${f.where} ${f.result}" + (f.detail?.let { " — ${it.lineSequence().first().take(160)}" } ?: "")
        }
    }
}
