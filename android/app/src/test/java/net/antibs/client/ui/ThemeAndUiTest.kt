package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.LinkParser
import net.antibs.client.data.QwdttProfile
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.Subscription
import net.antibs.client.data.XrayConfigBuilder
import net.antibs.client.data.XrayProfile
import net.antibs.client.engine.AppState
import net.antibs.client.engine.ConnState
import net.antibs.client.engine.NetMonitor
import net.antibs.client.engine.ThemeMode
import net.antibs.client.engine.UiSettings
import net.antibs.client.engine.themeModeOf
import net.antibs.client.engine.toUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAndUiTest {
    @Test fun themePreferenceRecomputesColors() {
        var stored = "auto"
        assertTrue(UiLogic.darkFromStored(stored, systemDark = true))
        assertFalse(UiLogic.darkFromStored(stored, systemDark = false))
        stored = "light"
        assertFalse(UiLogic.darkFromStored(stored, systemDark = true))
        assertEquals(ThemeResolver.lightBackgroundArgb, ThemeResolver.backgroundArgb(themeModeOf(stored), systemDark = true))
        stored = "dark"
        assertTrue(UiLogic.darkFromStored(stored, systemDark = false))
        assertEquals(ThemeResolver.darkBackgroundArgb, ThemeResolver.backgroundArgb(themeModeOf(stored), systemDark = false))
        assertNotEquals(ThemeResolver.legacyVioletArgb, ThemeResolver.darkBackgroundArgb)
        assertTrue(ThemeResolver.darkTextIsReadable())
        assertTrue(ThemeResolver.luminance(ThemeResolver.darkOnBackgroundArgb) > ThemeResolver.luminance(ThemeResolver.darkBackgroundArgb))
        assertTrue(ThemeResolver.luminance(ThemeResolver.lightBackgroundArgb) > ThemeResolver.luminance(ThemeResolver.lightOnBackgroundArgb))
    }

    @Test fun settingsStateCopyAppliesThemeImmediately() {
        var ui = UiSettings(theme = ThemeMode.AUTO)
        assertTrue(ThemeResolver.isDark(ui.theme, systemDark = true))
        ui = ui.copy(theme = ThemeMode.LIGHT)
        assertFalse(ThemeResolver.isDark(ui.theme, systemDark = true))
        ui = ui.copy(theme = ThemeMode.DARK)
        assertTrue(ThemeResolver.isDark(ui.theme, systemDark = false))
        assertEquals(ThemeResolver.darkBackgroundArgb, ThemeResolver.backgroundArgb(ui.theme, systemDark = false))
    }

    @Test fun daysAndProtocolGroups() {
        val now = 1_700_000_000L
        assertEquals(-1, UiLogic.daysLeft(0, now))
        assertEquals(0, UiLogic.daysLeft(now - 10, now))
        assertEquals(25, UiLogic.daysLeft(now + 25L * 86400 - 10, now))
        assertEquals(0f, UiLogic.expiryProgress(0, now))
        assertEquals(1f, UiLogic.expiryProgress(now + 30L * 86400, now))
        val ss = server("shadowsocks", null)
        val reality = server("vless", "reality")
        val q = ServerEntry("q", EngineKind.QWDTT, "qwdtt", qwdtt = QwdttProfile("n", "peer"))
        assertEquals("Shadowsocks", UiLogic.protocolGroup(ss))
        assertEquals("VLESS Reality", UiLogic.protocolGroup(reality))
        assertEquals("qWDTT", UiLogic.protocolGroup(q))
        assertEquals("Test", UiLogic.displayName("🇨🇦 Test"))
    }

    @Test fun activeProtocolUsesLiveOutbound() {
        val reality = server("vless", "reality")
        val ss = server("shadowsocks", null)
        assertEquals("VLESS Reality", UiLogic.activeProtocolLabel(ConnState.DISCONNECTED, "", reality))
        assertEquals("Shadowsocks", UiLogic.activeProtocolLabel(ConnState.CONNECTED, "Shadowsocks", reality))
        assertEquals("qWDTT", UiLogic.activeProtocolLabel(ConnState.CONNECTING, "qWDTT", ss))
        assertEquals("VLESS Reality", UiLogic.activeProtocolLabel(ConnState.DISCONNECTED, "Shadowsocks", reality))
        assertEquals("qWDTT", UiLogic.protocolGroup(ServerEntry("q", EngineKind.QWDTT, "qwdtt", qwdtt = QwdttProfile("n", "peer"))))
        assertEquals(UiLogic.ProtoChipTone.OK, UiLogic.protoChipTone(ConnState.CONNECTED))
        assertEquals(UiLogic.ProtoChipTone.ERR, UiLogic.protoChipTone(ConnState.ERROR))
        assertEquals(UiLogic.ProtoChipTone.OFF, UiLogic.protoChipTone(ConnState.DISCONNECTED))
        assertEquals(UiLogic.ProtoChipTone.OFF, UiLogic.protoChipTone(ConnState.CONNECTING))
    }

    @Test fun hotSwitchOnlyWhileTunnelIsUp() {
        assertTrue(UiLogic.shouldHotSwitch(ConnState.CONNECTED, true))
        assertTrue(UiLogic.shouldHotSwitch(ConnState.CONNECTING, true))
        assertFalse(UiLogic.shouldHotSwitch(ConnState.CONNECTED, false))
        assertFalse(UiLogic.shouldHotSwitch(ConnState.DISCONNECTED, true))
        assertFalse(UiLogic.shouldHotSwitch(ConnState.DISCONNECTING, true))
        assertFalse(UiLogic.shouldHotSwitch(ConnState.ERROR, true))
    }

    @Test fun collapsedSubscriptionHidesOnlyItsHosts() {
        val a = Subscription(id = "a", url = "u", title = "A")
        val b = Subscription(id = "b", url = "u", title = "B")
        val sa = server("vless", "reality").copy(id = "s1", subscriptionId = "a", name = "A1")
        val sb = server("shadowsocks", null).copy(id = "s2", subscriptionId = "b", name = "B1")
        val collapsed = UiLogic.hostGroups(listOf(a, b), listOf(sa, sb), setOf("a"))
        assertEquals(listOf("a", "b"), collapsed.map { it.subscription!!.id })
        assertFalse(collapsed[0].expanded)
        assertTrue(collapsed[0].servers.isEmpty())
        assertEquals(1, collapsed[0].total)
        assertTrue(collapsed[1].expanded)
        assertEquals(listOf("s2"), collapsed[1].servers.map { it.id })
        val open = UiLogic.hostGroups(listOf(a, b), listOf(sa, sb), emptySet())
        assertEquals(listOf("s1"), open[0].servers.map { it.id })
        assertEquals(listOf("s2"), open[1].servers.map { it.id })
    }

    @Test fun defaultsStayCompatibleAndFlagsWireIn() {
        val link = javaClass.classLoader.getResourceAsStream("remnawave_links.txt")!!.bufferedReader().readLines().first { it.contains("reality") }
        val parsed = LinkParser.parseXray(link)!!
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.forProfile(parsed)).jsonObject
        assertEquals("UseIPv4", cfg["dns"]!!.jsonObject["queryStrategy"]!!.jsonPrimitive.content)
        val inbound = cfg["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("127.0.0.1", inbound["listen"]!!.jsonPrimitive.content)
        val ob = cfg["outbounds"]!!.jsonArray[0].jsonObject
        assertFalse(ob["mux"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertNull(ob["streamSettings"]!!.jsonObject["sockopt"])

        val ss = XrayProfile(remarks = "s", protocol = "shadowsocks", server = "1.2.3.4", port = 8388, password = "p", method = "aes-256-gcm", network = "tcp")
        val wired = Json.parseToJsonElement(
            XrayConfigBuilder.forProfile(ss, XrayConfigBuilder.Options(mux = true, fragmentation = true, allowLan = true, ipType = "ipv6"))
        ).jsonObject
        val wob = wired["outbounds"]!!.jsonArray[0].jsonObject
        assertTrue(wob["mux"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        // 1.0.6: fragment is an Xray finalmask and only makes sense on TLS/REALITY → plain SS gets none
        assertNull(wob["streamSettings"]!!.jsonObject["finalmask"])
        assertEquals("0.0.0.0", wired["inbounds"]!!.jsonArray[0].jsonObject["listen"]!!.jsonPrimitive.content)
        assertEquals("UseIPv6", wired["dns"]!!.jsonObject["queryStrategy"]!!.jsonPrimitive.content)

        val vision = XrayProfile(
            remarks = "v", protocol = "vless", server = "h.example", port = 443,
            id = "11111111-1111-1111-1111-111111111111", flow = "xtls-rprx-vision",
            network = "tcp", security = "reality",
        )
        val vob = Json.parseToJsonElement(
            XrayConfigBuilder.forProfile(vision, XrayConfigBuilder.Options(mux = true, fragmentation = true))
        ).jsonObject["outbounds"]!!.jsonArray[0].jsonObject
        assertFalse(vob["mux"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertNull(vob["streamSettings"]!!.jsonObject["sockopt"])
        // REALITY: fragment packets "1-3" (v2rayNG/Exclave convention)
        assertEquals("1-3", vob["streamSettings"]!!.jsonObject["finalmask"]!!.jsonObject["tcp"]!!.jsonArray[0].jsonObject["settings"]!!.jsonObject["packets"]!!.jsonPrimitive.content)
    }

    private fun server(protocol: String, security: String?) = ServerEntry(
        id = "$protocol-$security",
        engine = EngineKind.XRAY,
        name = "🇨🇦 Test",
        xray = XrayProfile(remarks = "t", protocol = protocol, server = "h", port = 1, security = security, network = "tcp"),
    )

    @Test fun hostGroupsHidesEmptyManual() {
        val manual = Subscription(id = ProtocolSupport.MANUAL_SUB_ID, url = "", kind = "manual", title = ProtocolSupport.MANUAL_TITLE)
        val remna = Subscription(id = "sub-remnawave", url = "https://x", kind = "remnawave", title = "AntiBS")
        val empty = UiLogic.hostGroups(listOf(manual, remna), emptyList(), emptySet())
        assertTrue(empty.none { it.subscription?.id == ProtocolSupport.MANUAL_SUB_ID })
        assertEquals(1, empty.size)
        val host = ServerEntry("m1", EngineKind.XRAY, "Mine", ProtocolSupport.MANUAL_SUB_ID,
            xray = XrayProfile("Mine", "socks", "1.1.1.1", 1080))
        val with = UiLogic.hostGroups(listOf(manual, remna), listOf(host), emptySet())
        assertEquals(ProtocolSupport.MANUAL_TITLE, with.first { it.subscription?.id == ProtocolSupport.MANUAL_SUB_ID }.subscription!!.title)
        assertEquals(1, with.first { it.subscription?.id == ProtocolSupport.MANUAL_SUB_ID }.total)
    }

    @Test fun protocolSupportEngines() {
        assertEquals(ProtocolSupport.ENGINE_XRAY, ProtocolSupport.engineFor("vless"))
        assertEquals(ProtocolSupport.ENGINE_SINGBOX, ProtocolSupport.engineFor("tuic"))
        assertTrue(ProtocolSupport.isRunnable("hysteria1"))
        assertTrue(ProtocolSupport.isRunnable("snell"))
        assertNull(ProtocolSupport.unsupportedReason("naive"))
        assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineFor("mieru"))
        assertTrue(ProtocolSupport.isRunnable("http3"))
        assertNull(ProtocolSupport.unsupportedReason("http3"))
        assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineFor("http3"))
        assertEquals(ProtocolSupport.ENGINE_MIHOMO, ProtocolSupport.engineFor("ssr"))
        assertEquals("Серверы", ProtocolSupport.MANUAL_TITLE)
    }


    @Test fun trafficBarLabelUsedSlashTotal() {
        assertEquals("10GB/90GB", UiLogic.trafficBarLabel(UiLogic.Traffic(10 * 1024.0, 90 * 1024.0, false)))
        assertEquals("12.4GB/150GB", UiLogic.trafficBarLabel(UiLogic.Traffic(12.4 * 1024.0, 150 * 1024.0, true)))
        assertEquals("263MB/50GB", UiLogic.trafficBarLabel(UiLogic.Traffic(263.0, 50 * 1024.0, true)))
        assertEquals("1.5GB/∞", UiLogic.trafficBarLabel(UiLogic.Traffic(1536.0, 0.0, false)))
        assertEquals("0GB/∞", UiLogic.trafficBarLabel(UiLogic.Traffic(0.0, 0.0, true)))
    }

    @Test fun trafficFromSubscriptionBytes() {
        val sub = Subscription(id = "x", url = "u", upload = 1L shl 30, download = 9L shl 30, total = 90L shl 30)
        assertEquals("10GB/90GB", UiLogic.trafficBarLabel(UiLogic.traffic(sub)))
    }

    @Test fun hostOrderIsFixedSubscriptionOrder() {
        val a = Subscription(id = "a", url = "u", title = "A")
        val b = Subscription(id = "b", url = "u", title = "B")
        val s1 = server("vless", "reality").copy(id = "s1", subscriptionId = "a", name = "Z slow")
        val s2 = server("shadowsocks", null).copy(id = "s2", subscriptionId = "a", name = "A fast")
        val s3 = server("trojan", null).copy(id = "s3", subscriptionId = "b", name = "B1")
        // Servers arrive interleaved; groups follow subscription order, hosts keep their subscription order.
        val groups = UiLogic.hostGroups(listOf(b, a), listOf(s1, s3, s2), emptySet())
        assertEquals(listOf("b", "a"), groups.map { it.subscription!!.id })
        assertEquals(listOf("s3"), groups[0].servers.map { it.id })
        assertEquals(listOf("s1", "s2"), groups[1].servers.map { it.id })
    }

    @Test fun autoSelectEntriesAreShownAgain() {
        // 1.0.18: subscription auto-select / balancer entries are listed under their subscription again.
        val remna = Subscription(id = "sub-remnawave", url = "https://x", kind = "remnawave", title = "AntiBS")
        val auto = server("vless", "reality").copy(id = "auto", subscriptionId = "sub-remnawave", name = "🇪🇺 🎲Автовыбор сервера")
        val bal = ServerEntry("bal", EngineKind.XRAY, "Fastest", "sub-remnawave", xray = XrayProfile("Fastest", "balancer", "", 0))
        val real = server("vless", "reality").copy(id = "de", subscriptionId = "sub-remnawave", name = "🇩🇪 Германия")
        val manualBal = ServerEntry("mb", EngineKind.XRAY, "Мой балансировщик", ProtocolSupport.MANUAL_SUB_ID,
            xray = XrayProfile("Мой балансировщик", "balancer", "", 0))
        assertTrue(UiLogic.isAutoSelectEntry(auto))
        assertTrue(UiLogic.isAutoSelectEntry(bal))
        assertFalse(UiLogic.isAutoSelectEntry(real))
        assertFalse(UiLogic.isAutoSelectEntry(manualBal))
        val groups = UiLogic.hostGroups(listOf(remna), listOf(auto, bal, real), emptySet())
        assertEquals(listOf("auto", "bal", "de"), groups.single().servers.map { it.id })
        assertEquals(3, groups.single().total)
        assertEquals(listOf("auto", "de", "bal", "mb"), UiLogic.visibleServers(listOf(auto, real, bal, manualBal)).map { it.id })
    }

    @Test fun autoSelectEntryStaysSelected() {
        val a = Subscription(id = "a", url = "u", title = "A")
        val b = Subscription(id = "b", url = "u", title = "B")
        val auto = server("vless", "reality").copy(id = "auto", subscriptionId = "a", name = "🎲Автовыбор сервера")
        val a1 = server("vless", "reality").copy(id = "a1", subscriptionId = "a", name = "A1")
        val b1 = server("shadowsocks", null).copy(id = "b1", subscriptionId = "b", name = "B1")
        // Legacy «Автовыбор» mode flag is dropped, the stored host is kept.
        val st1 = UiLogic.normalizeSelection(AppState(subscriptions = listOf(a, b), servers = listOf(b1, auto, a1), selectedServerId = "b1", autoSelect = true, autoTargetId = "b1"))
        assertFalse(st1.autoSelect)
        assertEquals("b1", st1.selectedServerId)
        // 1.0.18: the subscription's auto-select entry is a normal selection — no fallback to another host.
        val onAuto = AppState(subscriptions = listOf(a, b), servers = listOf(b1, auto, a1), selectedServerId = "auto")
        assertTrue(onAuto === UiLogic.normalizeSelection(onAuto))
        // Unknown id → first host in display order (subscription A first; its auto entry is listed first).
        val gone = UiLogic.normalizeSelection(AppState(subscriptions = listOf(a, b), servers = listOf(b1, auto, a1), selectedServerId = "zzz"))
        assertEquals("auto", gone.selectedServerId)
        // No hosts yet: keep the stored id, just drop the mode.
        val empty = UiLogic.normalizeSelection(AppState(selectedServerId = "x", autoSelect = true))
        assertEquals("x", empty.selectedServerId)
        assertFalse(empty.autoSelect)
    }

    @Test fun powerToggleShowsTimerWhenConnected() {
        val since = 1_000_000L
        assertEquals("00:00:08", UiLogic.toggleCaption(ConnState.CONNECTED, since, since + 8_400))
        assertEquals("01:02:03", UiLogic.toggleCaption(ConnState.CONNECTED, since, since + 3_723_000))
        // Connected but the service has not stamped «since» yet: zero, never a negative/epoch value.
        assertEquals("00:00:00", UiLogic.toggleCaption(ConnState.CONNECTED, 0, since))
        assertEquals("00:00:00", UiLogic.toggleCaption(ConnState.CONNECTED, since + 5_000, since))
        assertEquals("ВКЛ", UiLogic.toggleCaption(ConnState.CONNECTING, since, since + 8_000))
        for (st in listOf(ConnState.DISCONNECTED, ConnState.ERROR, ConnState.DISCONNECTING)) {
            assertEquals("ВЫКЛ", UiLogic.toggleCaption(st, since, since + 8_000))
        }
    }

    @Test fun elapsedLabelStaysCompact() {
        assertEquals("99:59:59", UiLogic.elapsedLabel(100L * 3600 - 1))
        assertEquals("4д 04:00", UiLogic.elapsedLabel(100L * 3600))
        assertEquals("00:00:00", UiLogic.elapsedLabel(-5))
    }

    @Test fun powerCardLogic() {
        val since = 1_000_000L
        assertTrue(UiLogic.powerSelectedOn(ConnState.CONNECTED))
        assertTrue(UiLogic.powerSelectedOn(ConnState.CONNECTING))
        assertFalse(UiLogic.powerSelectedOn(ConnState.DISCONNECTED))
        assertFalse(UiLogic.powerSelectedOn(ConnState.ERROR))
        assertFalse(UiLogic.powerSelectedOn(ConnState.DISCONNECTING))
        assertTrue(UiLogic.powerBusy(ConnState.CONNECTING))
        assertTrue(UiLogic.powerBusy(ConnState.DISCONNECTING))
        assertFalse(UiLogic.powerBusy(ConnState.CONNECTED))
        assertEquals("00:12:34", UiLogic.powerTimerText(ConnState.CONNECTED, since, since + 754_000))
        assertEquals("00:00:00", UiLogic.powerTimerText(ConnState.CONNECTED, 0, since))
        assertEquals("…", UiLogic.powerTimerText(ConnState.CONNECTING, since, since + 8_000))
        assertEquals("00:00:00", UiLogic.powerTimerText(ConnState.DISCONNECTED, since, since + 8_000))
        assertEquals("00:00:00", UiLogic.powerTimerText(ConnState.ERROR, since, since + 8_000))
        assertEquals("Отключить VPN, подключено 00:12:34", UiLogic.powerContentDescription(ConnState.CONNECTED, false, "00:12:34"))
        assertEquals("Отключить VPN, нет сети, сессия 00:12:34", UiLogic.powerContentDescription(ConnState.CONNECTED, true, "00:12:34"))
        assertEquals("Подключение VPN", UiLogic.powerContentDescription(ConnState.CONNECTING, false, "…"))
        assertEquals("Отключение VPN", UiLogic.powerContentDescription(ConnState.DISCONNECTING, false, "00:00:00"))
        assertEquals("Ошибка VPN, подключить снова", UiLogic.powerContentDescription(ConnState.ERROR, false, "00:00:00"))
        assertEquals("Подключить VPN", UiLogic.powerContentDescription(ConnState.DISCONNECTED, false, "00:00:00"))
        assertTrue(UiLogic.powerSwipeToggles(on = false, dxPx = 40f, thresholdPx = 36f))
        assertFalse(UiLogic.powerSwipeToggles(on = false, dxPx = 10f, thresholdPx = 36f))
        assertFalse(UiLogic.powerSwipeToggles(on = false, dxPx = -40f, thresholdPx = 36f))
        assertTrue(UiLogic.powerSwipeToggles(on = true, dxPx = -40f, thresholdPx = 36f))
        assertFalse(UiLogic.powerSwipeToggles(on = true, dxPx = 40f, thresholdPx = 36f))
    }

    @Test fun powerCardTurnsRedFromHealthAndPing() {
        val ok = NetMonitor.Health.OK
        val noNet = NetMonitor.Health.NO_INTERNET
        val down = NetMonitor.Health.SERVER_DOWN
        // Connected, healthy, ping ok / unknown / checking → accent
        assertFalse(UiLogic.powerShowError(ConnState.CONNECTED, ok, UiLogic.TunnelPing.OK))
        assertFalse(UiLogic.powerShowError(ConnState.CONNECTED, ok, UiLogic.TunnelPing.UNKNOWN))
        assertFalse(UiLogic.powerShowError(ConnState.CONNECTED, ok, UiLogic.TunnelPing.CHECKING))
        // No ping while connected
        assertTrue(UiLogic.powerShowError(ConnState.CONNECTED, ok, UiLogic.TunnelPing.FAIL))
        // No internet / network lost (health from directFails + !netValidated)
        assertTrue(UiLogic.powerShowError(ConnState.CONNECTED, noNet, UiLogic.TunnelPing.OK))
        assertTrue(UiLogic.powerShowError(ConnState.CONNECTING, noNet, UiLogic.TunnelPing.UNKNOWN))
        assertEquals(noNet, NetMonitor.health(active = true, connected = true, vpnFails = 0, directFails = 1, netValidated = false))
        // Server down (health from vpnFails >= FAILS_TO_ALERT)
        assertTrue(UiLogic.powerShowError(ConnState.CONNECTED, down, UiLogic.TunnelPing.CHECKING))
        assertEquals(down, NetMonitor.health(active = true, connected = true, vpnFails = NetMonitor.FAILS_TO_ALERT, directFails = 0, netValidated = true))
        // Connection error
        assertTrue(UiLogic.powerShowError(ConnState.ERROR, ok, UiLogic.TunnelPing.OK))
        // Off / disconnecting: not red even if stale health
        assertFalse(UiLogic.powerShowError(ConnState.DISCONNECTED, noNet, UiLogic.TunnelPing.FAIL))
        assertFalse(UiLogic.powerShowError(ConnState.DISCONNECTING, down, UiLogic.TunnelPing.FAIL))
        // Recovery: ping and internet come back → accent
        assertEquals(ok, NetMonitor.health(active = true, connected = true, vpnFails = 0, directFails = 0, netValidated = true))
        assertFalse(UiLogic.powerShowError(ConnState.CONNECTED, ok, UiLogic.TunnelPing.OK))
        // 2.0.36 thumb colours
        assertEquals(UiLogic.PowerThumb.GREEN, UiLogic.powerThumb(ConnState.CONNECTED, ok, UiLogic.TunnelPing.OK))
        assertEquals(UiLogic.PowerThumb.ORANGE, UiLogic.powerThumb(ConnState.CONNECTING, ok, UiLogic.TunnelPing.UNKNOWN))
        assertEquals(UiLogic.PowerThumb.RED, UiLogic.powerThumb(ConnState.CONNECTED, noNet, UiLogic.TunnelPing.OK))
        assertEquals(UiLogic.PowerThumb.RED, UiLogic.powerThumb(ConnState.CONNECTED, ok, UiLogic.TunnelPing.FAIL))
        assertEquals(UiLogic.PowerThumb.RED, UiLogic.powerThumb(ConnState.CONNECTED, down, UiLogic.TunnelPing.CHECKING))
        assertEquals(UiLogic.PowerThumb.RED, UiLogic.powerThumb(ConnState.ERROR, ok, UiLogic.TunnelPing.OK))
        assertEquals(UiLogic.PowerThumb.OFF, UiLogic.powerThumb(ConnState.DISCONNECTED, ok, UiLogic.TunnelPing.UNKNOWN))
        assertEquals(UiLogic.PowerThumb.GREEN, UiLogic.powerThumb(ConnState.CONNECTED, ok, UiLogic.TunnelPing.OK))
    }

    @Test fun legacyHostGridFlagIsHarmless() {
        // 1.0.9 removed the grid toggle; an old persisted host_grid=true must still load untouched.
        val legacy = AppState(hostGrid = true)
        assertTrue(legacy.toUi().hostGrid)
        assertEquals(legacy.selectedServerId, UiLogic.normalizeSelection(legacy).selectedServerId)
    }
}
