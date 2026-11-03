package net.antibs.client.engine

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import net.antibs.client.data.AdvancedSettings

private val Context.antibsSettingsStore by preferencesDataStore(name = "antibs_ui_settings")

/** UI preferences mirrored to DataStore. AppState remains the synchronous copy the VPN service reads. */
data class UiSettings(
    val theme: ThemeMode = ThemeMode.AUTO,
    val fragmentation: Boolean = false,
    val mux: Boolean = false,
    val ipType: String = "auto",
    val allowLan: Boolean = false,
    val autostart: Boolean = false,
    val xrayTun: Boolean = false,
    val bypassLan: Boolean = true,
    val subsExpanded: Boolean = true,
    val compactServers: Boolean = false,
    val sortByPing: Boolean = false,
    val hostGrid: Boolean = false,
    val collapsedSubs: Set<String> = emptySet(),
    val pingTimeoutMs: Int = 3000,
    val adv: AdvancedSettings = AdvancedSettings(),
    val rev: Int = 0,
)

private val advJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun AdvancedSettings.toJsonString(): String = advJson.encodeToString(AdvancedSettings.serializer(), this)

fun advancedFromJson(s: String?): AdvancedSettings =
    s?.let { runCatching { advJson.decodeFromString(AdvancedSettings.serializer(), it) }.getOrNull() } ?: AdvancedSettings()

fun AppState.toUi(): UiSettings = UiSettings(
    theme = themeModeOf(theme),
    fragmentation = fragmentation,
    mux = mux,
    ipType = ipType,
    allowLan = allowLan,
    autostart = autostart,
    xrayTun = xrayTun,
    bypassLan = bypassLan,
    subsExpanded = subsExpanded,
    compactServers = compactServers,
    sortByPing = sortByPing,
    hostGrid = hostGrid,
    collapsedSubs = collapsedSubs.toSet(),
    pingTimeoutMs = pingTimeoutMs,
    adv = adv,
    rev = rev,
)

class UserPrefsStore(context: Context) {
    private val ds = context.applicationContext.antibsSettingsStore

    suspend fun readOrNull(): UiSettings? {
        val p = ds.data.first()
        if (p[Keys.REV] == null && p[Keys.THEME] == null) return null
        return decode(p)
    }

    suspend fun write(s: UiSettings) {
        ds.edit { p ->
            p[Keys.THEME] = s.theme.name.lowercase()
            p[Keys.FRAG] = s.fragmentation
            p[Keys.MUX] = s.mux
            p[Keys.IP] = s.ipType
            p[Keys.LAN] = s.allowLan
            p[Keys.AUTO] = s.autostart
            p[Keys.XTUN] = s.xrayTun
            p[Keys.BYPASS] = s.bypassLan
            p[Keys.SUBS] = s.subsExpanded
            p[Keys.COMPACT] = s.compactServers
            p[Keys.SORT] = s.sortByPing
            p[Keys.GRID] = s.hostGrid
            p[Keys.COLLAPSED] = s.collapsedSubs
            p[Keys.PING] = s.pingTimeoutMs
            p[Keys.ADV] = s.adv.toJsonString()
            p[Keys.REV] = s.rev
        }
    }

    private fun decode(p: Preferences) = UiSettings(
        theme = themeModeOf(p[Keys.THEME]),
        fragmentation = p[Keys.FRAG] ?: false,
        mux = p[Keys.MUX] ?: false,
        ipType = p[Keys.IP] ?: "auto",
        allowLan = p[Keys.LAN] ?: false,
        autostart = false,   // 2.0.9: start-on-boot removed; old saved value ignored
        xrayTun = p[Keys.XTUN] ?: false,
        bypassLan = p[Keys.BYPASS] ?: true,
        subsExpanded = p[Keys.SUBS] ?: true,
        compactServers = p[Keys.COMPACT] ?: false,
        sortByPing = p[Keys.SORT] ?: false,
        hostGrid = p[Keys.GRID] ?: false,
        collapsedSubs = p[Keys.COLLAPSED] ?: emptySet(),
        pingTimeoutMs = p[Keys.PING] ?: 3000,
        adv = advancedFromJson(p[Keys.ADV]),
        rev = p[Keys.REV] ?: 0,
    )

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FRAG = booleanPreferencesKey("fragmentation")
        val MUX = booleanPreferencesKey("mux")
        val IP = stringPreferencesKey("ip_type")
        val LAN = booleanPreferencesKey("allow_lan")
        val AUTO = booleanPreferencesKey("autostart")
        val XTUN = booleanPreferencesKey("xray_tun")
        val BYPASS = booleanPreferencesKey("bypass_lan")
        val SUBS = booleanPreferencesKey("subs_expanded")
        val COMPACT = booleanPreferencesKey("compact_servers")
        val SORT = booleanPreferencesKey("sort_by_ping")
        val GRID = booleanPreferencesKey("host_grid")
        val COLLAPSED = stringSetPreferencesKey("collapsed_subs")
        val PING = intPreferencesKey("ping_timeout_ms")
        val ADV = stringPreferencesKey("advanced_json")
        val REV = intPreferencesKey("rev")
    }
}
