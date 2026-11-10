package net.antibs.client.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 2.0.34: versioned settings backup — user preferences only.
 *
 * Excluded: subscriptions, servers, selected server, HWID / custom HWID, VK hashes,
 * shop/session tokens, LAN pairing keys, traffic counters and caches, favourite/ribbon
 * ids that point at hosts or subscriptions.
 *
 * First-run defaults: place a file produced by [toJson] at
 * `android/app/src/main/assets/default_settings.json` (see default_settings.json.example).
 * It is applied only when the app has no seeded prefs yet.
 */
@Serializable
data class SettingsBackup(
    val schemaVersion: Int = SCHEMA,
    val appVersion: String = "",
    val exportedAt: String = "",
    val prefs: BackupPrefs = BackupPrefs(),
    val styles: BackupStyles = BackupStyles(),
    val schedule: VpnScheduleCfg? = null,
) {
    companion object {
        const val SCHEMA = 1
        const val ASSET_NAME = "default_settings.json"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun fileName(epochMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
            "antibs-settings-${Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()}.json"

        fun isoNow(epochMs: Long = System.currentTimeMillis()): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

        fun toJson(b: SettingsBackup): String = json.encodeToString(serializer(), b)

        fun parse(text: String): Result<SettingsBackup> = runCatching {
            val raw = text.trim()
            require(raw.isNotEmpty()) { "Пустой файл" }
            val el = json.parseToJsonElement(raw)
            require(el is JsonObject) { "Ожидался JSON-объект" }
            when {
                el.containsKey("schemaVersion") || el.containsKey("prefs") -> {
                    val b = json.decodeFromJsonElement(serializer(), el)
                    require(b.schemaVersion >= 1) { "Неизвестная версия схемы: ${b.schemaVersion}" }
                    b
                }
                // Legacy full-state export is converted in SettingsBackupIo (needs AppState).
                el.containsKey("theme") || el.containsKey("adv") || el.containsKey("subscriptions") ->
                    error(LEGACY_APPSTATE)
                else -> error("Это не файл настроек AntiBS2client")
            }
        }

        const val LEGACY_APPSTATE = "LEGACY_APPSTATE"

        fun sanitizeAdv(a: AdvancedSettings): AdvancedSettings = a.copy(
            routingProfiles = a.routingProfiles.map {
                it.copy(
                    subscriptionId = null,
                    proxyServerIds = emptyList(),
                    geoipFile = "",
                    geositeFile = "",
                    geoipTags = emptyList(),
                    geositeTags = emptyList(),
                )
            },
        )
    }
}

/** Preference subset of [AppState] — no servers, subscriptions or secrets. */
@Serializable
data class BackupPrefs(
    val theme: String = "auto",
    val bypassLan: Boolean = true,
    val fragmentation: Boolean = false,
    val mux: Boolean = false,
    val ipType: String = "auto",
    val allowLan: Boolean = false,
    val autostart: Boolean = false,
    val xrayTun: Boolean = false,
    val subsExpanded: Boolean = true,
    val compactServers: Boolean = false,
    val sortByPing: Boolean = false,
    val hostGrid: Boolean = false,
    val pingTimeoutMs: Int = 3000,
    val qwdttDns: String = "yandex",
    val qwdttObfs: String = "audio",
    val qwdttTurnTcp: Boolean = false,
    val autoUpdateCheck: Boolean = true,
    val subAutoUpdate: Boolean = true,
    val subUpdateHours: Int = SubAutoUpdate.DEFAULT_HOURS,
    val subUpdateWifiOnly: Boolean = false,
    val adv: AdvancedSettings = AdvancedSettings(),
    /**
     * 2.0.35: per-subscription «Автоудаление недоступных» (auto-hide after ping), keyed by SHA-256 of the
     * subscription URL (the URL itself is not stored — it may contain a token).
     */
    val autoDeleteUnreachableByUrl: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class BackupStyleOpt(val layout: String = "groups", val bestCard: Boolean = true)

@Serializable
data class BackupStyles(
    val style: String = "classic",
    val sort: String = "added",
    val subNewestFirst: Boolean = true,
    val options: Map<String, BackupStyleOpt> = emptyMap(),
    val filter: Map<String, String> = emptyMap(),
    /** 2.0.35: Home filter tab id (IPv4 / Антиблок / protocol) and country code; empty = default / all. */
    val homeFilterTab: String = "",
    val homeFilterCountry: String = "",
    /** 2.0.37: user-arranged Home filter chip ids; missing ids are appended in default order. */
    val tabOrder: List<String> = emptyList(),
    val countryOrder: List<String> = emptyList(),
)
