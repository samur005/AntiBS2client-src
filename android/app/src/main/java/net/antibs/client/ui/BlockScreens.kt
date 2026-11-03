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
import net.antibs.client.data.AdBlockCounter
import net.antibs.client.data.TrafficPresets
import net.antibs.client.engine.AppState

// ───────────────────────── 2.0.15: «Блокировка рекламы» (ABS 3.1.27 AdBlockSheet port) ─────────────────────────

internal fun adBlockSummary(a: net.antibs.client.data.AdvancedSettings): String {
    val cats = AdBlock.CATS.filter { it.id in a.adCats }
    return listOfNotNull(
        "в туннеле".takeIf { a.blockAds },
        cats.filter { !it.telemetry }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.title.lowercase() },
        "телеметрия".takeIf { cats.any { it.telemetry } },
        (AdBlock.findDns(a.adDnsUrl)?.first?.name ?: "свой ДНС").takeIf { AdBlock.dnsUrl(a) != null }?.let { "ДНС: $it" },
    ).joinToString(" · ").ifEmpty { "выкл" }
}

/** ABS AdBlockCountSection: «Сегодня» / «Всего» from adblock_counts.json, «н/д» until a counted session ran. */
@Composable
internal fun AdBlockCountTiles(c: AdBlockCounter.Counts, onReset: (() -> Unit)?) {
    val style = LocalAppStyle.current
    val sc = ssColors(style)
    val day = AdBlockCounter.dayKey()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((label, v) in listOf("Сегодня" to c.todayFor(day), "Всего" to c.total)) {
                Column(Modifier.weight(1f).clip(ssShape(style, 14.dp)).background(sc.accent.copy(alpha = 0.10f)).padding(12.dp)) {
                    Text(label, fontSize = 12.sp, color = sc.muted)
                    Text(AdBlockCounter.show(c.measured, v), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = sc.accent, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
        if (onReset != null) OutlinedButton(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) { Text("Сбросить") }
    }
}

@Composable
internal fun AdBlockPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val a = st.adv
    val counts by vm.adCounts.collectAsState()
    val sc = ssColors(LocalAppStyle.current)
    fun cat(id: String, on: Boolean) = vm.setAdvTunnel { it.copy(adCats = if (on) (it.adCats + id).distinct() else it.adCats - id) }
    val tunnelOn = AdBlock.enabled(a)
    val dnsOn = AdBlock.dnsUrl(a) != null
    SettingsScaffold("Блокировка рекламы", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SettingsCard("Заблокировано") {
                    AdBlockCountTiles(counts, if (counts.measured) ({ vm.resetAdCounts() }) else null)
                    HintText((if (!tunnelOn) "Включите блокировку в туннеле — тогда запросы будут считаться."
                        else "Реальные запросы к рекламе и трекерам, отброшенные в туннеле (журнал Xray). Для OpenVPN / OpenConnect и других ядер без Xray подсчёт недоступен — н/д.") +
                        if (dnsOn) " Блокировка через DNS не считается (н/д)." else "")
                }
            }
            item {
                SettingsCard("В туннеле") {
                    SwitchRow("Блокировать рекламу и трекеры", a.blockAds, subtitle = "Отбрасывает известные рекламные и отслеживающие домены внутри туннеля") { v -> vm.setAdvTunnel { it.copy(blockAds = v) } }
                }
            }
            item {
                SettingsCard("Категории") {
                    AdBlock.CATS.filter { !it.telemetry }.forEach { c -> SwitchRow(c.title, c.id in a.adCats) { cat(c.id, it) } }
                    HintText("Работает в туннеле Xray. Списки вредоносных, азартных сайтов и майнеров в базе приложения отсутствуют, поэтому их здесь нет.")
                }
            }
            item {
                SettingsCard("Телеметрия") {
                    AdBlock.CATS.filter { it.telemetry }.forEach { c -> SwitchRow("Телеметрия ${c.title}", c.id in a.adCats) { cat(c.id, it) } }
                    HintText("Блокирует сбор данных через туннель Xray. Для Samsung в базе нет отдельного списка телеметрии. Блокировка Windows может мешать некоторым службам Microsoft.")
                }
            }
            item {
                SettingsCard("ДНС-блокировщик рекламы") {
                    SwitchRow("ДНС-блокировщик рекламы", a.adDnsOn, subtitle = "Резолвер, который отсекает рекламу и трекеры") { v ->
                        vm.setAdvTunnel { it.copy(adDnsOn = v, adDnsUrl = if (v && it.adDnsUrl.isBlank()) AdBlock.DNS_PRESETS.first().variants[1].url else it.adDnsUrl) }
                    }
                    if (a.adDnsOn) {
                        val sel = AdBlock.findDns(a.adDnsUrl)
                        AdBlock.DNS_PRESETS.forEach { pr ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(pr.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (sel?.first == pr) sc.accent else sc.onCard)
                                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pr.variants.forEach { v ->
                                        FilterChip(selected = sel?.second == v, onClick = { vm.setAdvTunnel { it.copy(adDnsUrl = v.url) } }, label = { Text(v.kind) },
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = sc.accent.copy(alpha = 0.22f), selectedLabelColor = sc.accent))
                                    }
                                }
                            }
                        }
                        SettingField("Свой адрес (udp, https://, quic://)", if (sel == null) a.adDnsUrl else "", { v -> if (v.isNotBlank()) vm.setAdvTunnel { it.copy(adDnsUrl = v.trim()) } },
                            valid = { it.isBlank() || AdBlock.dnsUrlOk(it) })
                    }
                    HintText("Пока блокировщик включён, он заменяет удалённый DNS из раздела «Сеть и DNS» (и профиля маршрутизации) для Xray. Выключите его — снова будет действовать выбранный там DNS.")
                }
            }
        }
    }
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
    listOf(TrafficPresets.OFF, TrafficPresets.RU, TrafficPresets.AI, TrafficPresets.SOCIAL).forEach { p ->
        item {
            Row(Modifier.fillMaxWidth().clickable { vm.setTrafficPreset(p) }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(a.trafficPreset == p || (p == TrafficPresets.OFF && !TrafficPresets.active(a)), { vm.setTrafficPreset(p) })
                Column(Modifier.weight(1f)) {
                    Text(if (p == TrafficPresets.OFF) "Без пресета" else TrafficPresets.title(p), fontSize = 16.sp)
                    val sub = if (p == TrafficPresets.OFF) "Работают профиль маршрутизации и правила подписки" else TrafficPresets.summary(p)
                    Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
