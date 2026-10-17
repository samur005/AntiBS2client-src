package net.antibs.client.ui

import net.antibs.client.data.EngineKind
import net.antibs.client.data.ProtocolSupport
import net.antibs.client.data.ServerEntry
import net.antibs.client.data.Subscription
import net.antibs.client.data.XrayProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerGroupsTest {
    private fun s(id: String, sub: String?) = ServerEntry(id, EngineKind.XRAY, "🇩🇪 $id", sub, xray = XrayProfile(id, "vless", "h", 443))

    @Test fun groupsFollowSubscriptionOrderThenOwnThenOther() {
        val subs = listOf(Subscription("b", "u", title = "Вторая"), Subscription("a", "u", title = ""), Subscription("empty", "u", title = "Пустая"))
        val servers = listOf(s("1", "a"), s("2", ProtocolSupport.MANUAL_SUB_ID), s("3", "b"), s("4", "gone"), s("5", "a"))
        val g = UiLogic.pickerGroups(servers, subs)
        assertEquals(listOf("b", "a", ProtocolSupport.MANUAL_SUB_ID, "other"), g.map { it.id })
        assertEquals(listOf("Вторая", "Подписка", "Свои серверы", "Другие"), g.map { it.title })
        assertEquals(listOf("1", "5"), g[1].servers.map { it.id })
    }

    @Test fun singleSubscriptionIsOneGroup() {
        val g = UiLogic.pickerGroups(listOf(s("1", "a"), s("2", "a")), listOf(Subscription("a", "u", title = "X")))
        assertEquals(1, g.size)
    }
}
