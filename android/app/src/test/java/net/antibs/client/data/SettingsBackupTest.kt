package net.antibs.client.data

import java.time.ZoneId
import net.antibs.client.engine.AppState
import net.antibs.client.engine.SettingsBackupIo
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private val secretSub = Subscription(
        id = "sub-secret",
        url = "https://panel.example/secret-token-xyz",
        title = "MySub",
        upload = 1_000,
        download = 2_000,
        total = 10_000,
    )
    private val secretServer = ServerEntry(
        id = "srv-1",
        engine = EngineKind.XRAY,
        name = "Frankfurt",
        subscriptionId = "sub-secret",
    )
    private val dirty = AppState(
        subscriptions = listOf(secretSub),
        servers = listOf(secretServer),
        selectedServerId = "srv-1",
        theme = "dark",
        bypassLan = false,
        fragmentation = true,
        mux = true,
        ipType = "ipv6",
        hwid = "device-hwid-should-not-leak",
        customHwid = "custom-hwid-secret",
        vkHashes = "vk-hash-secret",
        sendHwid = true,
        seeded = true,
        adv = AdvancedSettings(
            language = "ru",
            seasonalDecor = "ny",
            routingProfiles = listOf(
                RoutingProfile(
                    id = "rp1",
                    name = "Work",
                    subscriptionId = "sub-secret",
                    proxyServerIds = listOf("srv-1"),
                    geoipFile = "/data/geoip.dat",
                    geositeFile = "/data/geosite.dat",
                    geoipTags = listOf("cn"),
                    geositeTags = listOf("ads"),
                    remoteDns = "https://dns.google/dns-query",
                )
            ),
        ),
    )

    private fun backupOf(st: AppState = dirty) = SettingsBackupIo.fromAppState(
        st,
        appVersion = "2.0.34",
        styles = BackupStyles(style = "neon", sort = "ping", subNewestFirst = false),
        schedule = VpnScheduleCfg(on = true),
        exportedAt = "2026-11-10T08:00:00Z",
    )

    @Test fun fileNameUsesDate() {
        val name = SettingsBackup.fileName(1_762_732_800_000L, ZoneId.of("Asia/Novosibirsk")) // 2025-11-10? check
        assertTrue(name.startsWith("antibs-settings-"))
        assertTrue(name.endsWith(".json"))
        assertFalse(name.contains(" "))
    }

    @Test fun roundTripPreservesPrefsAndStyles() {
        val json = SettingsBackup.toJson(backupOf())
        val back = SettingsBackup.parse(json).getOrThrow()
        assertEquals(SettingsBackup.SCHEMA, back.schemaVersion)
        assertEquals("2.0.34", back.appVersion)
        assertEquals("dark", back.prefs.theme)
        assertFalse(back.prefs.bypassLan)
        assertTrue(back.prefs.fragmentation)
        assertTrue(back.prefs.mux)
        assertEquals("ipv6", back.prefs.ipType)
        assertEquals("ru", back.prefs.adv.language)
        assertEquals("ny", back.prefs.adv.seasonalDecor)
        assertEquals("neon", back.styles.style)
        assertEquals("ping", back.styles.sort)
        assertFalse(back.styles.subNewestFirst)
        assertEquals(true, back.schedule?.on)
        assertEquals("https://dns.google/dns-query", back.prefs.adv.routingProfiles.single().remoteDns)
    }

    @Test fun jsonExcludesServersSubscriptionsAndSecrets() {
        val json = SettingsBackup.toJson(backupOf())
        assertFalse(json.contains("sub-secret"))
        assertFalse(json.contains("srv-1"))
        assertFalse(json.contains("https://panel.example/secret-token-xyz"))
        assertFalse(json.contains("device-hwid-should-not-leak"))
        assertFalse(json.contains("custom-hwid-secret"))
        assertFalse(json.contains("vk-hash-secret"))
        assertFalse(json.contains("\"subscriptions\""))
        assertFalse(json.contains("\"servers\""))
        assertFalse(json.contains("\"hwid\""))
        assertFalse(json.contains("\"customHwid\""))
        assertFalse(json.contains("\"vkHashes\""))
        assertFalse(json.contains("\"selectedServerId\""))
    }

    @Test fun applyLeavesServersAndSubscriptionsUntouched() {
        val b = backupOf()
        val already = AppState(
            subscriptions = listOf(secretSub),
            servers = listOf(secretServer),
            selectedServerId = "srv-1",
            hwid = "keep-hwid",
            customHwid = "keep-custom",
            vkHashes = "keep-vk",
            theme = "auto",
        )
        val next = SettingsBackupIo.applyPrefs(already, b.prefs)
        assertEquals(already.subscriptions, next.subscriptions)
        assertEquals(already.servers, next.servers)
        assertEquals("srv-1", next.selectedServerId)
        assertEquals("keep-hwid", next.hwid)
        assertEquals("keep-custom", next.customHwid)
        assertEquals("keep-vk", next.vkHashes)
        assertEquals("dark", next.theme)
        assertTrue(next.fragmentation)
    }

    @Test fun routingSanitizeDropsHostAndFileBindings() {
        val rp = backupOf().prefs.adv.routingProfiles.single()
        assertNull(rp.subscriptionId)
        assertTrue(rp.proxyServerIds.isEmpty())
        assertEquals("", rp.geoipFile)
        assertEquals("", rp.geositeFile)
        assertTrue(rp.geoipTags.isEmpty())
        assertTrue(rp.geositeTags.isEmpty())
        assertEquals("Work", rp.name)
    }

    @Test fun unknownKeysAreIgnored() {
        val json = """
            {"schemaVersion":1,"appVersion":"2.0.34","prefs":{"theme":"light","futureFlag":true},"styles":{},"extraTop":1}
        """.trimIndent()
        val b = SettingsBackup.parse(json).getOrThrow()
        assertEquals("light", b.prefs.theme)
    }

    @Test fun schemaTooLowIsRejected() {
        val r = SettingsBackup.parse("""{"schemaVersion":0,"prefs":{}}""")
        assertTrue(r.isFailure)
    }

    @Test fun emptyAndGarbageAreRejected() {
        assertTrue(SettingsBackup.parse("").isFailure)
        assertTrue(SettingsBackup.parse("[]").isFailure)
        assertTrue(SettingsBackup.parse("not json").isFailure)
    }

    @Test fun legacyFullStateExportsPrefsOnly() {
        val legacy = SettingsBackup.json.encodeToString(AppState.serializer(), dirty)
        assertTrue(legacy.contains("sub-secret"))
        val b = SettingsBackupIo.parseFlexible(legacy).getOrThrow()
        assertEquals("dark", b.prefs.theme)
        assertTrue(b.prefs.fragmentation)
        val next = SettingsBackupIo.applyPrefs(
            AppState(subscriptions = listOf(secretSub), servers = listOf(secretServer), hwid = "keep"),
            b.prefs,
        )
        assertEquals(listOf(secretSub), next.subscriptions)
        assertEquals(listOf(secretServer), next.servers)
        assertEquals("keep", next.hwid)
        assertFalse(SettingsBackup.toJson(b).contains("sub-secret"))
    }

    @Test fun affectsTunnelDetectsVpnFields() {
        val a = AppState()
        val b = a.copy(fragmentation = true)
        assertTrue(SettingsBackupIo.affectsTunnel(a, b))
        assertFalse(SettingsBackupIo.affectsTunnel(a, a.copy(theme = "dark")))
    }

    @Test fun autoDeleteToggleIsBackedUpByUrlHash() {
        val off = secretSub.copy(autoDeleteUnreachable = false)
        val json = SettingsBackup.toJson(SettingsBackupIo.fromAppState(
            dirty.copy(subscriptions = listOf(off)),
            appVersion = "2.0.35",
            styles = BackupStyles(homeFilterTab = "IPv4", homeFilterCountry = "DE"),
            schedule = null,
            exportedAt = "2026-11-10T10:00:00Z",
        ))
        assertFalse(json.contains("secret-token-xyz"))
        assertFalse(json.contains(off.url))
        val key = SettingsBackupIo.urlKey(off.url)
        assertTrue(key.length == 64)
        assertTrue(json.contains(key))
        val parsed = SettingsBackup.parse(json).getOrThrow()
        assertEquals(false, parsed.prefs.autoDeleteUnreachableByUrl[key])
        assertEquals("IPv4", parsed.styles.homeFilterTab)
        assertEquals("DE", parsed.styles.homeFilterCountry)

        val already = AppState(subscriptions = listOf(secretSub.copy(autoDeleteUnreachable = true)))
        val next = SettingsBackupIo.applyAutoDeleteByUrl(already, parsed.prefs.autoDeleteUnreachableByUrl)
        assertEquals(false, next.subscriptions.single().autoDeleteUnreachable)
        // unknown URL hashes do not touch other subscriptions
        val other = Subscription(id = "other", url = "https://other.example/sub")
        val mixed = SettingsBackupIo.applyAutoDeleteByUrl(
            AppState(subscriptions = listOf(secretSub, other)),
            parsed.prefs.autoDeleteUnreachableByUrl,
        )
        assertEquals(false, mixed.subscriptions[0].autoDeleteUnreachable)
        assertEquals(true, mixed.subscriptions[1].autoDeleteUnreachable)
    }
}
