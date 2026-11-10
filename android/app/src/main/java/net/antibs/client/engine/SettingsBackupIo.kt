package net.antibs.client.engine

import android.content.Context
import net.antibs.client.data.BackupPrefs
import net.antibs.client.data.BackupStyleOpt
import net.antibs.client.data.BackupStyles
import net.antibs.client.data.SettingsBackup
import net.antibs.client.data.VpnScheduleCfg
import java.security.MessageDigest

/** AppState / store glue for [SettingsBackup] (kept out of :data to avoid a package cycle). */
object SettingsBackupIo {
    fun prefsOf(st: AppState) = BackupPrefs(
        theme = st.theme,
        bypassLan = st.bypassLan,
        fragmentation = st.fragmentation,
        mux = st.mux,
        ipType = st.ipType,
        allowLan = st.allowLan,
        autostart = st.autostart,
        xrayTun = st.xrayTun,
        subsExpanded = st.subsExpanded,
        compactServers = st.compactServers,
        sortByPing = st.sortByPing,
        hostGrid = st.hostGrid,
        pingTimeoutMs = st.pingTimeoutMs,
        qwdttDns = st.qwdttDns,
        qwdttObfs = st.qwdttObfs,
        qwdttTurnTcp = st.qwdttTurnTcp,
        autoUpdateCheck = st.autoUpdateCheck,
        subAutoUpdate = st.subAutoUpdate,
        subUpdateHours = st.subUpdateHours,
        subUpdateWifiOnly = st.subUpdateWifiOnly,
        adv = SettingsBackup.sanitizeAdv(st.adv),
        autoDeleteUnreachableByUrl = st.subscriptions
            .filter { it.url.isNotBlank() && it.kind != "manual" }
            .associate { urlKey(it.url) to it.autoDeleteUnreachable },
    )

    fun fromAppState(
        st: AppState,
        appVersion: String,
        styles: BackupStyles,
        schedule: VpnScheduleCfg?,
        exportedAt: String = SettingsBackup.isoNow(),
    ) = SettingsBackup(
        schemaVersion = SettingsBackup.SCHEMA,
        appVersion = appVersion,
        exportedAt = exportedAt,
        prefs = prefsOf(st),
        styles = styles,
        schedule = schedule,
    )

    fun applyPrefs(cur: AppState, p: BackupPrefs): AppState = cur.copy(
        theme = p.theme,
        bypassLan = p.bypassLan,
        fragmentation = p.fragmentation,
        mux = p.mux,
        ipType = p.ipType,
        allowLan = p.allowLan,
        autostart = p.autostart,
        xrayTun = p.xrayTun,
        subsExpanded = p.subsExpanded,
        compactServers = p.compactServers,
        sortByPing = p.sortByPing,
        hostGrid = p.hostGrid,
        pingTimeoutMs = p.pingTimeoutMs,
        qwdttDns = p.qwdttDns,
        qwdttObfs = p.qwdttObfs,
        qwdttTurnTcp = p.qwdttTurnTcp,
        autoUpdateCheck = p.autoUpdateCheck,
        subAutoUpdate = p.subAutoUpdate,
        subUpdateHours = p.subUpdateHours,
        subUpdateWifiOnly = p.subUpdateWifiOnly,
        adv = SettingsBackup.sanitizeAdv(p.adv),
    )

    fun affectsTunnel(before: AppState, after: AppState): Boolean =
        before.fragmentation != after.fragmentation ||
            before.mux != after.mux ||
            before.ipType != after.ipType ||
            before.allowLan != after.allowLan ||
            before.xrayTun != after.xrayTun ||
            before.bypassLan != after.bypassLan ||
            before.qwdttDns != after.qwdttDns ||
            before.qwdttObfs != after.qwdttObfs ||
            before.qwdttTurnTcp != after.qwdttTurnTcp ||
            before.adv != after.adv

    fun parseFlexible(text: String): Result<SettingsBackup> {
        val first = SettingsBackup.parse(text)
        val ex = first.exceptionOrNull()
        if (first.isSuccess) return first
        if (ex?.message != SettingsBackup.LEGACY_APPSTATE) return first
        return runCatching {
            val st = SettingsBackup.json.decodeFromString(AppState.serializer(), text.trim())
            fromAppState(st, appVersion = "", styles = BackupStyles(), schedule = null, exportedAt = "")
        }
    }

    /** Load bundled first-run defaults, or null if the asset is missing / invalid. */
    fun loadAssetDefaults(ctx: Context): SettingsBackup? = runCatching {
        ctx.assets.open(SettingsBackup.ASSET_NAME).bufferedReader().use { it.readText() }
            .let { parseFlexible(it).getOrThrow() }
    }.getOrNull()

    /**
     * Apply [SettingsBackup.ASSET_NAME] from assets only when prefs are not seeded yet.
     * [applyExtras] receives styles + schedule so UI prefs / VpnScheduler stay out of this package.
     */
    fun applyFirstRunDefaults(
        ctx: Context,
        store: AppStore,
        applyExtras: (BackupStyles, VpnScheduleCfg?) -> Unit,
    ) {
        if (store.snapshot().seeded) return
        val b = loadAssetDefaults(ctx) ?: return
        store.update { applyAutoDeleteByUrl(applyPrefs(it, b.prefs), b.prefs.autoDeleteUnreachableByUrl) }
        applyExtras(b.styles, b.schedule)
    }

    /** SHA-256 hex of a normalized subscription URL — used as a backup key so tokens never leave the device. */
    fun urlKey(url: String): String {
        val n = url.trim().trimEnd('/')
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(n.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Apply [autoDeleteUnreachableByUrl] onto matching current subscriptions (by URL hash). Others unchanged. */
    fun applyAutoDeleteByUrl(cur: AppState, byUrl: Map<String, Boolean>): AppState {
        if (byUrl.isEmpty()) return cur
        var changed = false
        val subs = cur.subscriptions.map { s ->
            if (s.url.isBlank() || s.kind == "manual") s
            else {
                val v = byUrl[urlKey(s.url)] ?: return@map s
                if (s.autoDeleteUnreachable == v) s else { changed = true; s.copy(autoDeleteUnreachable = v) }
            }
        }
        return if (changed) cur.copy(subscriptions = subs) else cur
    }
}
