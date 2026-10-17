package net.antibs.client.engine

import kotlinx.serialization.json.Json
import net.antibs.client.data.AdvancedSettings

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

