package net.antibs.client.engine

import net.antibs.client.data.OpenConnectConfig
import net.antibs.client.data.OpenVpnConfig
import net.antibs.client.data.XrayProfile
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Desktop: OpenVPN / OpenConnect passwords sealed with a per-user AES-GCM key in %APPDATA%\AntiBS2client («dk1:…»). */
object SecretBox {
    private const val PREFIX = "dk1:"
    private val keyFile get() = File(net.antibs.client.desktop.Paths.home, "secret.key")

    @Synchronized private fun key(): SecretKeySpec {
        val f = keyFile
        val raw = if (f.isFile && f.length() == 32L) f.readBytes() else ByteArray(32).also { SecureRandom().nextBytes(it); f.writeBytes(it) }
        return SecretKeySpec(raw, "AES")
    }

    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty() || plain.startsWith(PREFIX)) return plain
        return try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
            PREFIX + Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
        } catch (e: Exception) { VpnState.log("шифрование недоступно, пароль сохранён как есть: ${e.message}"); plain }
    }

    fun open(v: String?): String {
        if (v.isNullOrEmpty()) return ""
        if (!v.startsWith(PREFIX)) return v
        val b = Base64.getDecoder().decode(v.removePrefix(PREFIX))
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }

    fun sealProfile(p: XrayProfile): XrayProfile =
        if (p.protocol == OpenVpnConfig.PROTOCOL || p.protocol == OpenConnectConfig.PROTOCOL)
            p.copy(password = seal(p.password), privateKeyPassphrase = seal(p.privateKeyPassphrase)) else p
}
