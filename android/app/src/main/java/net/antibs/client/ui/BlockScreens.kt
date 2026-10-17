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
import net.antibs.client.data.AdGuardFilter
import net.antibs.client.data.TrafficPresets
import net.antibs.client.data.UserDomains
import net.antibs.client.engine.AppState

// ───────────────────────── 1.0.40: «Блокировка рекламы» ─────────────────────────

internal fun adBlockSummary(a: net.antibs.client.data.AdvancedSettings) =
    listOfNotNull("реклама".takeIf { a.blockAds }, "трекеры".takeIf { a.blockTrackers }, "AdGuard".takeIf { a.adGuard }, "STUN".takeIf { a.blockStun })
        .joinToString(" · ").ifEmpty { "выкл" }

@Composable
internal fun AdBlockPage(vm: AppViewModel, st: AppState, onBack: () -> Unit) {
    val a = st.adv
    val ag by vm.adGuard.collectAsState()
    val job by vm.adGuardJob.collectAsState()
    var custom by remember(a.blockCustom) { mutableStateOf(a.blockCustom.joinToString("\n")) }
    var allow by remember(a.allowCustom) { mutableStateOf(a.allowCustom.joinToString("\n")) }
    SettingsScaffold("Блокировка рекламы", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionLabel("Встроенные списки") }
            item { SwitchRow("Блокировать рекламу", a.blockAds) { v -> vm.setAdvTunnel { it.copy(blockAds = v) } } }
            item { SwitchRow("Блокировать трекеры и аналитику", a.blockTrackers) { v -> vm.setAdvTunnel { it.copy(blockTrackers = v) } } }
            item { HintText("Реклама — geosite category-ads-all (~114 тыс. доменов; свежая копия — после «Файлы георесурсов» → «Обновить»). Трекеры — Google Analytics, Яндекс Метрика/AppMetrica, AppsFlyer, Adjust, Amplitude, Mixpanel и др.") }

            item { SectionLabel("Фильтр ${AdGuardFilter.NAME}") }
            item {
                SwitchRow("Использовать фильтр AdGuard", a.adGuard && ag.ready) { v ->
                    if (v && !ag.ready) vm.updateAdGuard(enable = true) else vm.setAdvTunnel { it.copy(adGuard = v) }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        if (ag.ready) "Фильтр готов: ${ag.blocked} доменов · исключений ${ag.allowed} · обновлён ${Format.dateTime(ag.updated)}" else "Фильтр ещё не скачан",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.busy) {
                        Text(job.stage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    job.error?.let { Text(it, fontSize = 12.sp, color = AntiBSColors.Err, modifier = Modifier.padding(top = 4.dp)) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.updateAdGuard(enable = !ag.ready) }, enabled = !job.busy) { Text(if (ag.ready) "Обновить" else "Скачать и включить") }
                        if (ag.ready) OutlinedButton(onClick = { vm.deleteAdGuard() }, enabled = !job.busy) { Text("Удалить") }
                    }
                }
            }
            item { HintText("AdGuard DNS filter (~50–60 тыс. доменов, правила «@@» — исключения) скачивается один раз и хранится на устройстве; обновляйте вручную. Списки собираются в один локальный файл при подключении.") }

            item { SectionLabel("Свои списки") }
            item {
                OutlinedTextField(custom, { custom = it }, label = { Text("Блокировать домены") }, minLines = 3,
                    supportingText = { Text("По одному в строке: example.com (с поддоменами), full:host.example.com, ||ads.example^") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                OutlinedTextField(allow, { allow = it }, label = { Text("Исключения (белый список)") }, minLines = 3,
                    supportingText = { Text("Эти домены и их поддомены никогда не блокируются") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                val cParsed = UserDomains.parse(UserDomains.split(custom)).size
                val aParsed = UserDomains.parse(UserDomains.split(allow)).size
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val c = UserDomains.split(custom); val w = UserDomains.split(allow)
                        vm.setAdvTunnel { it.copy(blockCustom = c, allowCustom = w) }
                        vm.message("Сохранено: блок $cParsed, исключений $aParsed")
                    }) { Text("Сохранить списки") }
                    Text("$cParsed / $aParsed", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionLabel("Утечки") }
            item { SwitchRow("Блокировать STUN/WebRTC", a.blockStun) { v -> vm.setAdvTunnel { it.copy(blockStun = v) } } }
            item { HintText("UDP-порты STUN/TURN (3478–3479, 5349, 19302–19309) уходят в blackhole: браузеры не смогут узнать реальный IP через WebRTC. Может мешать видеозвонкам в браузере.") }
            item {
                HintText("Работает в Xray для всех серверов: обычных, цепочек, балансировщиков, профилей маршрутизации, автовыбора панели и своего JSON. Блокировка по доменам (нужен сниффинг) — реклама с тех же доменов, что и контент (YouTube, ВК), не убирается. OpenVPN / OpenConnect работают без Xray — для них блокировка не действует." +
                    if (!a.sniffing) " Сниффинг выключен — блокировка сработает только для DNS-запросов через Xray." else "")
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
                HintText("Источник: github.com/runetfreedom/russia-v2ray-rules-dat (последний релиз, проверка SHA-256). Из ~90 МБ архива сохраняются только нужные списки (~6 МБ): ru-blocked (заблокированное в РФ), ru-available-only-inside, ru-whitelist, свежий category-ads-all. Их использует «.RU без VPN» и блокировка рекламы.")
            }
        }
    }
}

// ───────────────────────── 1.0.40: «Правила трафика» (in «Маршрутизация») ─────────────────────────

internal fun LazyListScope.trafficPresetItems(vm: AppViewModel, st: AppState, rfReady: Boolean, onOpenGeo: () -> Unit, onOpenAdBlock: () -> Unit) {
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
    item { NavRow("Блокировка рекламы", adBlockSummary(a)) { onOpenAdBlock() } }
    item { NavRow("Файлы георесурсов", if (rfReady) "списки РФ готовы" else "встроенные") { onOpenGeo() } }
}
