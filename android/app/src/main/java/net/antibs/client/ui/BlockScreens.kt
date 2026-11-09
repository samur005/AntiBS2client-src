package net.antibs.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.antibs.client.data.AdBlock
import net.antibs.client.data.I18n
import net.antibs.client.data.AdBlockCounter
import net.antibs.client.data.TrafficPresets
import net.antibs.client.engine.AppState

// ───────────────────────── 2.0.15: «Блокировка рекламы» (ABS 3.1.27 AdBlockSheet port) ─────────────────────────

/** ABS AdBlockCountSection: «Сегодня» / «Всего» from adblock_counts.json, «н/д» until a counted session ran. */
@Composable
internal fun AdBlockCountTiles(c: AdBlockCounter.Counts, onReset: (() -> Unit)?) {
    val style = LocalAppStyle.current
    val sc = ssColors(style)
    val day = AdBlockCounter.dayKey()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((label, v) in listOf(I18n.t("today") to c.todayFor(day), I18n.t("total") to c.total)) {
                Column(Modifier.weight(1f).clip(ssShape(style, 12.dp)).background(sc.accent.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(label, fontSize = 12.sp, color = sc.muted)
                    Text(AdBlockCounter.show(c.measured, v), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = sc.accent, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
        if (onReset != null) OutlinedButton(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) { Text(I18n.t("reset")) }
    }
}

/** 2.0.16: every ad-block item inline in the Settings «Блокировка рекламы» section — counters first. */
@Composable
internal fun AdBlockItems(vm: AppViewModel, st: AppState) {
    val a = st.adv
    val counts by vm.adCounts.collectAsState()
    val sc = ssColors(LocalAppStyle.current)
    fun cat(id: String, on: Boolean) { vm.setAdvTunnel { it.copy(adCats = if (on) (it.adCats + id).distinct() else it.adCats - id) }; vm.reconnectIfConnected() }
    val tunnelOn = AdBlock.enabled(a)
    val dnsOn = AdBlock.dnsUrl(a) != null
    SectionLabel(I18n.t("blocked"))
    AdBlockCountTiles(counts, if (counts.measured) ({ vm.resetAdCounts() }) else null)
    HintText((if (!tunnelOn) "Включите блокировку в туннеле — тогда запросы будут считаться."
        else "Реальные запросы к рекламе и трекерам, отброшенные в туннеле (журнал Xray). Для OpenVPN / OpenConnect и других ядер без Xray подсчёт недоступен — н/д.") +
        if (dnsOn) " Блокировка через DNS не считается (н/д)." else "")
    SectionLabel(I18n.t("in_tunnel"))
    SwitchRow(I18n.t("block_ads"), a.blockAds, subtitle = I18n.t("block_ads_sub")) { v -> vm.setAdvTunnel { it.copy(blockAds = v) }; vm.reconnectIfConnected() }
    SectionLabel(I18n.t("categories"))
    AdBlock.CATS.filter { !it.telemetry }.forEach { c -> SwitchRow(c.title, c.id in a.adCats) { cat(c.id, it) } }
    HintText("Работает в туннеле Xray. Списки вредоносных, азартных сайтов и майнеров в базе приложения отсутствуют, поэтому их здесь нет.")
    SectionLabel(I18n.t("telemetry"))
    AdBlock.CATS.filter { it.telemetry }.forEach { c -> SwitchRow("Телеметрия ${c.title}", c.id in a.adCats) { cat(c.id, it) } }
    HintText("Блокирует сбор данных через туннель Xray. Для Samsung в базе нет отдельного списка телеметрии. Блокировка Windows может мешать некоторым службам Microsoft.")
    SectionLabel(I18n.t("ad_dns"))
    SwitchRow(I18n.t("ad_dns"), a.adDnsOn, subtitle = I18n.t("ad_dns_sub")) { v ->
        vm.setAdvTunnel { it.copy(adDnsOn = v, adDnsUrl = if (v && it.adDnsUrl.isBlank()) AdBlock.DNS_PRESETS.first().variants[1].url else it.adDnsUrl) }
        vm.reconnectIfConnected()
    }
    if (a.adDnsOn) {
        val sel = AdBlock.findDns(a.adDnsUrl)
        var dnsMenu by remember { mutableStateOf(false) }
        val dnsLabel = sel?.let { "${it.first.name} · ${it.second.kind}" } ?: if (a.adDnsUrl.isNotBlank()) "Свой" else "Выберите"
        androidx.compose.foundation.layout.Box {
            NavRow(I18n.t("resolver"), dnsLabel, dropdown = true, subtitle = "AdGuard, Mullvad, ControlD или свой адрес") { dnsMenu = true }
            androidx.compose.material3.DropdownMenu(expanded = dnsMenu, onDismissRequest = { dnsMenu = false }) {
                AdBlock.DNS_PRESETS.forEach { pr ->
                    pr.variants.forEach { v ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("${pr.name} · ${v.kind}") },
                            onClick = {
                                dnsMenu = false
                                vm.setAdvTunnel { it.copy(adDnsUrl = v.url) }
                                vm.reconnectIfConnected()
                            },
                        )
                    }
                }
            }
        }
        SettingField("Свой адрес (udp, https://, quic://)", if (sel == null) a.adDnsUrl else "", { v -> if (v.isNotBlank()) vm.setAdvTunnel { it.copy(adDnsUrl = v.trim()) }; vm.reconnectIfConnected() },
            valid = { it.isBlank() || AdBlock.dnsUrlOk(it) })
    }
    HintText("Пока блокировщик включён, он заменяет удалённый DNS из раздела «Сеть и DNS» (и профиля маршрутизации) для Xray. Выключите его — снова будет действовать выбранный там DNS.")
}

// ───────────────────────── 1.0.40: «Файлы георесурсов» ─────────────────────────

@Composable
internal fun GeoFilesPage(vm: AppViewModel, onBack: () -> Unit) {
    val rf by vm.rf.collectAsState()
    val job by vm.rfJob.collectAsState()
    SettingsScaffold("Файлы георесурсов", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionLabel("Встроенные") }
            item { HintText("geoip.dat / geosite.dat (Loyalsoldier v2ray-rules-dat) входят в приложение и обновляются вместе с ним.") }
            item { SectionLabel("Списки РФ (runetfreedom)") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        if (rf.ready) "Готово · обновлено ${Format.dateTime(rf.updated)} · ${String.format(java.util.Locale.US, "%.1f", rf.bytes / 1048576.0)} МБ\n" +
                            "geosite: ${rf.site.joinToString { it.lowercase() }}\ngeoip: ${rf.ip.joinToString { it.lowercase() }}"
                        else "Не скачаны",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.busy) {
                        Text(job.stage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    job.error?.let { Text(it, fontSize = 12.sp, color = AntiBSColors.Err, modifier = Modifier.padding(top = 4.dp)) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.updateGeoFiles() }, enabled = !job.busy) { Text(if (rf.ready) "Обновить" else "Скачать") }
                        if (rf.ready) OutlinedButton(onClick = { vm.deleteGeoFiles() }, enabled = !job.busy) { Text("Удалить") }
                    }
                }
            }
            item {
                HintText("Источник: github.com/runetfreedom/russia-v2ray-rules-dat (последний релиз, проверка SHA-256). Из ~90 МБ архива сохраняются только нужные списки (~6 МБ): ru-blocked (заблокированное в РФ), ru-available-only-inside, ru-whitelist. Их использует «.RU без VPN».")
            }
        }
    }
}

// ───────────────────────── 1.0.40: «Правила трафика» (in «Маршрутизация») ─────────────────────────

internal fun LazyListScope.trafficPresetItems(vm: AppViewModel, st: AppState, rfReady: Boolean, onOpenGeo: () -> Unit) {
    val a = st.adv
    item { SectionLabel("Правила трафика") }
    item {
        val cur = if (!TrafficPresets.active(a)) TrafficPresets.OFF else a.trafficPreset
        val label = if (cur == TrafficPresets.OFF || !TrafficPresets.active(a)) "Без пресета" else TrafficPresets.title(cur)
        var menu by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            NavRow("Пресет", label, dropdown = true, subtitle = if (!TrafficPresets.active(a)) "Работают профиль маршрутизации и правила подписки" else TrafficPresets.summary(a.trafficPreset)) { menu = true }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(TrafficPresets.OFF, TrafficPresets.RU, TrafficPresets.AI, TrafficPresets.SOCIAL).forEach { p ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (p == TrafficPresets.OFF) "Без пресета" else TrafficPresets.title(p)) },
                        onClick = { menu = false; vm.setTrafficPreset(p) },
                    )
                }
            }
        }
    }
    if (a.trafficPreset == TrafficPresets.RU) {
        item {
            SettingField("DNS для .RU без VPN", a.ruDns, { v -> vm.setAdvTunnel { it.copy(ruDns = v.trim()) } },
                valid = { it.isBlank() || Regex("""^(udp://|tcp://|https://)?[A-Za-z0-9.:\-\[\]/]+$""").matches(it.trim()) },
                hint = "Только для .ru/.su/.рф, по умолчанию ${TrafficPresets.DEFAULT_RU_DNS} (Яндекс)")
        }
        if (!rfReady) item {
            HintText("Скачайте «Файлы георесурсов» — тогда заблокированные в РФ сайты (в т.ч. на .ru) останутся в VPN, а сервисы, доступные только из России, пойдут напрямую.")
        }
    }
    if (TrafficPresets.active(a)) item {
        HintText("Пресет действует для всех серверов Xray, включая автовыбор панели (его правила добавляются сверху). Профиль маршрутизации ниже, пока выбран пресет, не применяется.")
    }
    item { NavRow("Файлы георесурсов", if (rfReady) "списки РФ готовы" else "встроенные") { onOpenGeo() } }
}
