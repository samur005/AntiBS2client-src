package net.antibs.client.data

/**
 * 1.0.18 HWID support (Remnawave «HWID device limit», https://docs.rw/features/hwid-device-limit).
 * Request headers: x-hwid (required; must match ^[a-zA-Z0-9=-]{10,64}$ or the panel ignores it),
 * x-device-os, x-ver-os, x-device-model; User-Agent stays the app's. Response flags:
 * x-hwid-active, x-hwid-not-supported, x-hwid-max-devices-reached, x-hwid-limit.
 */
object Hwid {
    private val VALID = Regex("^[a-zA-Z0-9=-]{10,64}$")

    fun isValid(hwid: String): Boolean = VALID.matches(hwid)

    /** New random HWID: 32 hex chars (same shape as the per-install device ID). */
    fun generate(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /**
     * HWID to send for [sub], or null when sending is off. Priority: per-subscription override →
     * custom HWID from settings → per-install device ID. Invalid overrides are skipped.
     */
    fun effective(sendHwid: Boolean, customHwid: String, deviceId: String, subOverride: String = ""): String? {
        if (!sendHwid) return null
        return listOf(subOverride.trim(), customHwid.trim(), deviceId.trim()).firstOrNull { it.isNotEmpty() && isValid(it) }
            ?: deviceId.trim().ifEmpty { null }
    }

    /** Device headers sent together with x-hwid. Empty map when [hwid] is null (sending disabled). */
    fun requestHeaders(hwid: String?, osRelease: String?, model: String?): Map<String, String> {
        if (hwid.isNullOrBlank()) return emptyMap()
        return linkedMapOf(
            "x-hwid" to hwid,
            "x-device-os" to "Android",
            "x-ver-os" to (osRelease ?: "").ifBlank { "unknown" },
            "x-device-model" to (model ?: "").ifBlank { "unknown" },
        )
    }

    private fun flag(headers: Map<String?, String?>, name: String): Boolean =
        headers.entries.any { (k, v) -> k.equals(name, ignoreCase = true) && v?.trim()?.equals("true", ignoreCase = true) == true }

    /** True when the panel reports that the device limit was hit. */
    fun limitReached(headers: Map<String?, String?>): Boolean =
        flag(headers, "x-hwid-max-devices-reached") || flag(headers, "x-hwid-limit")

    /**
     * Clear Russian message for HWID-related refusals, or null when the response isn't about HWID.
     * [sent] = whether x-hwid was sent with the request that produced [headers].
     */
    fun problemMessage(code: Int, headers: Map<String?, String?>, sent: Boolean, serversEmpty: Boolean = true): String? = when {
        limitReached(headers) ->
            "Достигнут лимит устройств (HWID) для этой подписки. Удалите старое устройство в личном кабинете или задайте прежний HWID в Настройки → HWID."
        flag(headers, "x-hwid-not-supported") && !sent ->
            "Подписка требует HWID. Включите «Отправлять HWID» в Настройки → HWID."
        flag(headers, "x-hwid-not-supported") ->
            "Панель не приняла HWID: нужен 10–64 символа (латиница, цифры, «=», «-»). Проверьте HWID в Настройки → HWID."
        flag(headers, "x-hwid-active") && (code == 404 || (code in 200..299 && serversEmpty)) && !sent ->
            "Подписка ограничена по HWID. Включите «Отправлять HWID» в Настройки → HWID."
        flag(headers, "x-hwid-active") && (code == 404 || (code in 200..299 && serversEmpty)) ->
            "Панель отклонила устройство по HWID (лимит устройств или HWID не зарегистрирован)."
        else -> null
    }

    /**
     * Request plan: list of «send HWID?» flags, tried in order.
     * qWDTT (LucX) subscriptions keep the historical behaviour — first without X-HWID (the panel serves the
     * qWDTT JSON before its HWID gate), then with it on 403/404. Others send HWID first and fall back to a
     * request without it if the panel refuses for a non-HWID reason. Sending disabled → one plain request.
     */
    fun attempts(kind: String, hwid: String?): List<Boolean> = when {
        hwid == null -> listOf(false)
        kind == "qwdtt" -> listOf(false, true)
        else -> listOf(true, false)
    }
}
