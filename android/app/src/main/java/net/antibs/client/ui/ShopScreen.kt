package net.antibs.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.antibs.client.shop.Panel
import net.antibs.client.shop.ShopViewModel

/** 1.0.42: opens a payment page in a Custom Tab (browser fallback). */
internal fun openPayPage(ctx: android.content.Context, url: String, onError: (String) -> Unit) {
    val uri = android.net.Uri.parse(url)
    try {
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, uri)
    } catch (_: Exception) {
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { onError("Не удалось открыть страницу оплаты: ${e.message}") }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ShopScreen(app: AppViewModel, onOpenServers: () -> Unit) {
    val vm: ShopViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val st by app.state.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ShopViewModel.Event.Open -> openPayPage(ctx, e.url) { app.message(it) }
                is ShopViewModel.Event.Toast -> app.message(e.text)
                is ShopViewModel.Event.Import -> {
                    if (app.state.value.subscriptions.none { Panel.sameUrl(it.url, e.url) }) {
                        app.addSubscription(e.url)
                        app.message("Подписка «${e.name}» добавлена в «Серверы»")
                    } else app.message("Подписка «${e.name}» уже есть в «Серверы»")
                }
            }
        }
    }
    // 1.0.43: deep link from the panel's /pay/return page
    val ret by net.antibs.client.MainActivity.paymentReturn.collectAsState()
    LaunchedEffect(ret) { ret?.let { vm.start(); vm.onPaymentReturn(it); net.antibs.client.MainActivity.paymentReturn.value = null } }
    // back from the payment page → check right away
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.recheck()
        onPauseOrDispose { }
    }
    var buyPlan by remember { mutableStateOf<Panel.Plan?>(null) }
    // 1.0.44: no ↻/⚙ in the header — pull down to refresh plans and the account.
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = ui.loadingPlans || ui.loadingAccount,
        onRefresh = { vm.loadPlans(); if (ui.loggedIn) vm.loadAccount() },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "hero") { ShopHero(ui, onSignOut = { vm.signOut() }) }
        ui.pending?.let { p -> item(key = "pending") { PendingCard(p, onReopen = vm::reopenPayment, onCheck = vm::recheck, onClose = vm::dismissPending, onServers = onOpenServers) } }
        // 1.0.50: logged out → only the sign-in / sign-up form; tariffs and subscriptions appear after login
        if (!ui.loggedIn) {
            item(key = "auth-h") {
                Text("Войдите, чтобы увидеть тарифы", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
            item(key = "auth") { AuthCard(ui, onSignIn = vm::signIn, onSignUp = vm::signUp, onForgot = { openPayPage(ctx, ui.site + "/forgot-password") { app.message(it) } }) }
        } else {
            item(key = "plans-h") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShopLabel("Тарифы", Modifier.weight(1f))
                    if (ui.loadingPlans) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            item(key = "promo") { PromoRow(ui.promo, vm::setPromo, onApply = vm::loadPlans) }
            ui.plansError?.let { err ->
                item(key = "plans-err") {
                    Card(colors = CardDefaults.cardColors(containerColor = AntiBSColors.Err.copy(alpha = 0.12f))) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("Не удалось загрузить тарифы", fontWeight = FontWeight.SemiBold)
                            Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = vm::loadPlans) { Text("Повторить") }
                        }
                    }
                }
            }
            if (!ui.loadingPlans && ui.plansError == null && ui.plans.isEmpty()) {
                item(key = "plans-empty") { Text("Сейчас нет доступных тарифов.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp)) }
            }
            items(ui.plans, key = { "p-" + it.id }) { p ->
                PlanCard(p, busy = ui.busyPlanId == p.id, onBuy = {
                    if (!ui.loggedIn) app.message("Войдите или зарегистрируйтесь, чтобы купить тариф") else buyPlan = p
                })
            }
            if (ui.loggedIn && ui.subs.isNotEmpty()) {
                item(key = "subs-h") { ShopLabel("Мои подписки") }
                items(ui.subs, key = { "s-" + it.id }) { s ->
                    val added = s.subscriptionUrl != null && st.subscriptions.any { Panel.sameUrl(it.url, s.subscriptionUrl) }
                    MySubCard(s, added, busy = ui.busyPlanId != null, onImport = { vm.importSub(s) }, onRenew = { vm.renew(s) }, onServers = onOpenServers,
                        onCopy = { s.subscriptionUrl?.let { copyText(ctx, it); app.message("Ссылка скопирована") } })
                }
            }
            item(key = "foot") {
                Text(
                    "Оплата открывается на странице кассы; после оплаты подписка сама добавится в «Серверы».",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
    }
    buyPlan?.let { p ->
        BuyDialog(p, ui, onDismiss = { buyPlan = null }, onPay = { method, cashier -> buyPlan = null; vm.buy(p, method, cashier) })
    }
}

@Composable
private fun ShopLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(start = 4.dp, top = 2.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AntiBSColors.Section)
}

private val heroBrush: Brush get() = Brush.linearGradient(listOf(AntiBSColors.BlueDeep, AntiBSColors.Purple))

@Composable
private fun ShopHero(ui: ShopViewModel.Ui, onSignOut: () -> Unit) {
    // 1.0.44: a bit slimmer (padding 16→12 dp vertical, icon 40→34 dp, no action buttons).
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(heroBrush).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Storefront, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Магазин AntiBS VPN", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(if (ui.loggedIn) "Вы вошли как ${ui.login ?: "—"}" else "Подписки VPN прямо в приложении", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
            AnimatedVisibility(ui.loggedIn) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Баланс: " + (ui.balance?.let { Panel.money(it, "RUB") } ?: "…"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (ui.loadingAccount) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text("Выйти", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingCard(p: ShopViewModel.Pending, onReopen: () -> Unit, onCheck: () -> Unit, onClose: () -> Unit, onServers: () -> Unit) {
    val ok = p.done && !p.failed && p.status == "SUCCEEDED"
    val tone = when { p.failed -> AntiBSColors.Err; ok -> AntiBSColors.Ok; else -> AntiBSColors.Blue }
    Card(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, tone.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.10f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    p.failed -> Icon(Icons.Filled.Error, null, tint = tone)
                    ok -> Icon(Icons.Filled.CheckCircle, null, tint = tone)
                    else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tone)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.planName.ifBlank { "Покупка" }, fontWeight = FontWeight.SemiBold)
                    Text(p.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!p.done) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = tone)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!p.done && p.payUrl != null) TextButton(onClick = onReopen) { Text("Открыть оплату") }
                if (!ok) TextButton(onClick = onCheck) { Text("Проверить") }
                if (ok) TextButton(onClick = onServers) { Text("К серверам") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(if (p.done) "Закрыть" else "Отменить") }
            }
        }
    }
}

@Composable
private fun AuthCard(ui: ShopViewModel.Ui, onSignIn: (String, String) -> Unit, onSignUp: (String, String, String, String?) -> Unit, onForgot: () -> Unit) {
    var register by rememberSaveable { mutableStateOf(false) }
    var id by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var ref by rememberSaveable { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val eye: @Composable () -> Unit = {
        IconButton(onClick = { showPass = !showPass }) {
            Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showPass) "Скрыть пароль" else "Показать пароль")
        }
    }
    Card(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(3.dp)) {
                listOf(false to "Вход", true to "Регистрация").forEach { (r, t) ->
                    val sel = register == r
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) heroBrush else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                        .clickable { register = r }.heightIn(min = 40.dp).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(t, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            OutlinedTextField(id, { id = it }, label = { Text("Логин, почта или телефон") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = eye, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            AnimatedVisibility(register, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(pass2, { pass2 = it }, label = { Text("Повторите пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = eye, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(ref, { ref = it }, label = { Text("Реферальный код (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            ui.authError?.let { Text(it, color = AntiBSColors.Err, fontSize = 12.sp) }
            Button(
                onClick = { if (register) onSignUp(id, pass, pass2, ref) else onSignIn(id, pass) },
                enabled = !ui.authBusy && id.isNotBlank() && pass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AntiBSColors.BlueDeep),
            ) {
                if (ui.authBusy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (register) "Создать аккаунт" else "Войти")
            }
            if (!register) TextButton(onClick = onForgot) { Text("Забыли пароль?", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PromoRow(promo: String, onChange: (String) -> Unit, onApply: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(promo, { onChange(it.uppercase().trim()) }, label = { Text("Промокод") }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onApply, shape = RoundedCornerShape(12.dp)) { Text("Применить") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(p: Panel.Plan, busy: Boolean, onBuy: () -> Unit) {
    val border = if (p.popular || p.promo) Modifier.border(1.5.dp, heroBrush, RoundedCornerShape(16.dp)) else Modifier
    Card(Modifier.fillMaxWidth().then(border), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Panel.publicName(p.name), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (p.popular) Badge("Популярный", AntiBSColors.Purple)
                if (p.promo) { Spacer(Modifier.width(6.dp)); Badge("Акция", AntiBSColors.Warn) }
            }
            Panel.publicText(p.description, p.serverName)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(Panel.money(p.price, p.currency), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = AntiBSColors.Blue)
                Text(" / " + Panel.days(p.durationDays), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                p.oldPrice?.takeIf { it > p.price }?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(Panel.money(it, p.currency), fontSize = 13.sp, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Feature(Icons.Filled.CalendarMonth, Panel.days(p.durationDays))
                Feature(Icons.Filled.DataUsage, Panel.traffic(p.trafficGb))
                Feature(Icons.Filled.Devices, "${p.deviceLimit} ${Panel.plural(p.deviceLimit, "устройство", "устройства", "устройств")}")
            }
            p.promoEndsAt?.let { Text("Акция до " + it.take(10).split('-').reversed().joinToString("."), fontSize = 11.sp, color = AntiBSColors.Warn, modifier = Modifier.padding(top = 4.dp)) }
            Button(onClick = onBuy, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 40.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AntiBSColors.BlueDeep)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("Купить")
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AntiBSColors.Section, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun MySubCard(s: Panel.Sub, added: Boolean, busy: Boolean, onImport: () -> Unit, onRenew: () -> Unit, onServers: () -> Unit, onCopy: () -> Unit) {
    val active = s.status == "ACTIVE"
    val tone = if (active) AntiBSColors.Ok else if (s.status == "PENDING") AntiBSColors.Warn else AntiBSColors.Err
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.planName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Badge(Panel.statusLabel(s.status), tone)
            }
            val left = s.daysRemaining?.let { if (it > 0) "осталось ${Panel.days(it)}" else "срок истёк" }
            val until = s.expiresAt?.take(10)?.split('-')?.reversed()?.joinToString(".")
            Text(listOfNotNull(until?.let { "до $it" }, left).joinToString(" · ").ifEmpty { "—" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.trafficLimitBytes?.takeIf { it > 0 }?.let { lim ->
                val f = (s.trafficUsedBytes.toFloat() / lim).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = if (f > 0.9f) AntiBSColors.Err else AntiBSColors.Blue)
                Text("${Format.bytesFixed(s.trafficUsedBytes)} из ${Format.bytesFixed(lim)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    s.subscriptionUrl == null -> Text("Ключ ещё не выдан", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    added -> TextButton(onClick = onServers) { Icon(Icons.Filled.CheckCircle, null, tint = AntiBSColors.Ok, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("В приложении") }
                    else -> TextButton(onClick = onImport) { Text("Добавить в приложение") }
                }
                if (s.subscriptionUrl != null) TextButton(onClick = onCopy) { Text("Копировать") }
                Spacer(Modifier.weight(1f))
                if (s.status != "BLOCKED") TextButton(onClick = onRenew, enabled = !busy) { Text("Продлить") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuyDialog(p: Panel.Plan, ui: ShopViewModel.Ui, onDismiss: () -> Unit, onPay: (String, String?) -> Unit) {
    val cash = ui.cashiers
    val canBalance = (ui.balance ?: 0.0) >= p.price && p.price > 0
    val cards = cash?.list.orEmpty()
    val cardOk = cash?.cardAvailable ?: true
    var method by remember { mutableStateOf(if (!cardOk && canBalance) "balance" else "card") }
    var cashier by remember { mutableStateOf(cash?.defaultId ?: cards.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Покупка «${Panel.publicName(p.name)}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Panel.money(p.price, p.currency)} · ${Panel.days(p.durationDays)} · ${Panel.traffic(p.trafficGb)} · ${p.deviceLimit} устр.", fontSize = 13.sp)
                if (ui.promo.isNotBlank()) Text("Промокод: ${ui.promo}", fontSize = 12.sp, color = AntiBSColors.Section)
                Text("Способ оплаты", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (cards.isEmpty() && cardOk) FilterChip(selected = method == "card", onClick = { method = "card" }, label = { Text("Картой / СБП") })
                    cards.forEach { c ->
                        val same = cards.count { it.provider == c.provider } > 1
                        FilterChip(selected = method == "card" && cashier == c.id, onClick = { method = "card"; cashier = c.id },
                            label = { Text(Panel.providerLabel(c.provider) + if (same) " · ${c.name}" else "") })
                    }
                    if (p.price <= 0 || canBalance) FilterChip(selected = method == "balance", onClick = { method = "balance" },
                        label = { Text("С баланса (${Panel.money(ui.balance ?: 0.0, "RUB")})") })
                }
                if (!cardOk && !canBalance && p.price > 0) Text("Оплата картой временно недоступна, а на балансе недостаточно средств.", color = AntiBSColors.Err, fontSize = 12.sp)
                Text("После оплаты вернитесь в приложение — подписка добавится автоматически.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = (method == "card" && cardOk) || (method == "balance" && (canBalance || p.price <= 0)),
                onClick = { onPay(method, if (method == "card") cashier else null) }) { Text(if (method == "balance") "Оплатить" else "К оплате") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

