package net.antibs.client.data

import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Random

/**
 * 2.0.43: the app's local SOCKS5 / HTTP inbound is no longer an open proxy. Detectors (e.g. RKNHardering) and any
 * other app on the phone — including apps excluded from the VPN — could connect to 127.0.0.1:10808 without a
 * password and learn (or use) the VPN exit. Now the inbound has a login + password (RFC 1929) and a random high port,
 * both generated once ([migrate]) and stored in [AdvancedSettings] so the user can see, copy and change them.
 * Every internal client (URL test, speed test, geo lookup, session info, AutoPin probes) authenticates through
 * [install] — a process-wide [Authenticator] that answers only SOCKS requests to loopback.
 */
object LocalProxy {
    const val HARDENING_VERSION = 1
    const val PORT_MIN = 20000
    const val PORT_MAX = 59999
    private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun port(a: AdvancedSettings): Int = a.socksPort.takeIf { it in 1024..65535 } ?: XrayConfigBuilder.SOCKS_PORT

    fun hasAuth(a: AdvancedSettings): Boolean = a.socksUser.isNotEmpty() && a.socksPass.isNotEmpty()

    /** Login / password accepted by Xray accounts, hev-socks5-tunnel and RFC 1929 (1–255 bytes, no ':' for HTTP basic). */
    fun validCred(s: String): Boolean = s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size <= 255 && ':' !in s && s.none { it.isWhitespace() || it.code < 0x20 }

    fun token(len: Int, rnd: Random = SecureRandom()): String = buildString { repeat(len) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }
    fun newUser(rnd: Random = SecureRandom()): String = "u" + token(9, rnd)
    fun newPass(rnd: Random = SecureRandom()): String = token(24, rnd)

    /** A random port in [PORT_MIN]..[PORT_MAX] that [isFree] accepts (up to 64 tries, then the last candidate). */
    fun newPort(rnd: Random = SecureRandom(), isFree: (Int) -> Boolean = ::isPortFree): Int {
        var p = PORT_MIN
        repeat(64) {
            p = PORT_MIN + rnd.nextInt(PORT_MAX - PORT_MIN + 1)
            if (p != XrayConfigBuilder.SOCKS_PORT && isFree(p)) return p
        }
        return p
    }

    fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: Exception) { false }

    /** «Сгенерировать заново»: new login, password and port. */
    fun regenerate(a: AdvancedSettings, rnd: Random = SecureRandom(), isFree: (Int) -> Boolean = ::isPortFree): AdvancedSettings =
        a.copy(socksUser = newUser(rnd), socksPass = newPass(rnd), socksPort = newPort(rnd, isFree))

    /**
     * One-time hardening for new installs and upgrades (pure; [AdvancedSettings.hardening] marks it done, so a later
     * user choice — «Блокировать обход туннеля» off, an own port or login — is never overwritten):
     *  - «Блокировать обход туннеля» on (no VpnService.Builder.allowBypass);
     *  - SOCKS login + password generated when missing;
     *  - the well-known port 10808 replaced by a random free high port (a port the user set themselves stays).
     */
    fun migrate(a: AdvancedSettings, rnd: Random = SecureRandom(), isFree: (Int) -> Boolean = ::isPortFree): AdvancedSettings {
        if (a.hardening >= HARDENING_VERSION) return a
        var n = a.copy(tunnelBindBlock = true, hardening = HARDENING_VERSION)
        if (!hasAuth(n)) n = n.copy(socksUser = newUser(rnd), socksPass = newPass(rnd))
        if (n.socksPort == XrayConfigBuilder.SOCKS_PORT || n.socksPort !in 1024..65535) n = n.copy(socksPort = newPort(rnd, isFree))
        return n
    }

    // ───────── runtime: credentials for the app's own clients ─────────

    @Volatile private var user: String = ""
    @Volatile private var pass: String = ""
    @Volatile private var installed = false

    /** Current credentials (empty = inbound without auth). */
    fun credentials(): Pair<String, String>? = if (user.isNotEmpty() && pass.isNotEmpty()) user to pass else null

    /** Remembers [a]'s credentials and installs the loopback-only SOCKS [Authenticator] once per process. */
    fun install(a: AdvancedSettings) {
        user = a.socksUser; pass = a.socksPass
        if (installed) return
        synchronized(this) {
            if (installed) return
            Authenticator.setDefault(LoopbackSocksAuthenticator)
            installed = true
        }
    }

    /** True for the requests [LoopbackSocksAuthenticator] answers: SOCKS (java.net SocksSocketImpl) to loopback. */
    fun answers(protocol: String?, host: String?, addr: InetAddress?): Boolean {
        if (protocol == null || !protocol.startsWith("SOCKS", ignoreCase = true)) return false
        return addr?.isLoopbackAddress == true || host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private object LoopbackSocksAuthenticator : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (!answers(requestingProtocol, requestingHost, requestingSite)) return null
            val c = credentials() ?: return null
            return PasswordAuthentication(c.first, c.second.toCharArray())
        }
    }
}
