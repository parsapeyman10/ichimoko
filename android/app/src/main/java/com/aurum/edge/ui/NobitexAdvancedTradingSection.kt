package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.NobitexLiveOrder
import com.aurum.edge.data.NobitexMarket
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

private data class LiveOrderRequest(val side: SignalAction, val src: String, val dst: String,
    val amount: String, val price: String?, val execution: String, val estimatedPrice: Double)

/**
 * REAL Nobitex order execution with the user's OWN API token and REAL money. Strongly, visually
 * separated (red border) from every paper/practice section. Hard client-side notional cap and a
 * mandatory typed confirmation are the only safety rails this app can add — there is no public
 * Nobitex endpoint for per-market amount/price precision, so this never guesses rounding; any
 * rejection Nobitex returns is shown to the user exactly as received.
 */
@Composable
fun NobitexLiveTradingSection(viewModel: AurumViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.nobitexLiveBusy.collectAsStateWithLifecycle()
    val balance by viewModel.nobitexBalance.collectAsStateWithLifecycle()
    val orders by viewModel.nobitexLiveOrders.collectAsStateWithLifecycle()
    val liveError by viewModel.nobitexLiveError.collectAsStateWithLifecycle()
    val liveMarket by viewModel.nobitexLiveMarket.collectAsStateWithLifecycle()
    val catalog by viewModel.nobitexCatalog.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.ensureNobitexCatalog() }
    val browser = LocalUriHandler.current
    var tokenInput by remember { mutableStateOf("") }
    var capInput by remember { mutableStateOf(settings.nobitexLiveOrderCapUsdt.toString()) }
    var acknowledged by remember { mutableStateOf(false) }
    var liveMarketQuery by remember { mutableStateOf("") }
    var side by remember { mutableStateOf(SignalAction.BUY) }
    var amountText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var execution by remember { mutableStateOf("limit") }
    var confirmRequest by remember { mutableStateOf<LiveOrderRequest?>(null) }
    var typedConfirm by remember { mutableStateOf("") }

    Column(Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .background(AurumColors.Surface, RoundedCornerShape(12.dp))
        .border(2.dp, AurumColors.Red, RoundedCornerShape(12.dp))
        .padding(14.dp)) {
        Text("معاملهٔ واقعی نوبیتکس · پول واقعی", style = MaterialTheme.typography.titleMedium, color = AurumColors.Red)
        Text("این بخش سفارش واقعی با پول واقعی حساب شما به نوبیتکس ارسال می‌کند؛ جدا از هر بخش کاغذی/تمرینی است.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Red, modifier = Modifier.padding(top = 4.dp))
        Text("کلید API فقط از حساب خودتان و فقط روی همین گوشی ذخیره می‌شود؛ هرگز جای دیگری ارسال نمی‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        OutlinedButton(onClick = { runCatching { browser.openUri("https://nobitex.ir/panel/profile/api-services/") } },
            modifier = Modifier.padding(top = 6.dp)) { Text("ساخت/مدیریت کلید API در سایت نوبیتکس") }

        if (!settings.hasNobitexKey) {
            OutlinedTextField(value = tokenInput, onValueChange = { tokenInput = it },
                label = { Text("کلید API نوبیتکس خودتان را اینجا وارد کنید") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = {
                if (viewModel.saveNobitexApiToken(tokenInput)) { tokenInput = "" }
            }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("ذخیرهٔ کلید روی همین گوشی") }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("کلید ذخیره شده است.", style = MaterialTheme.typography.bodySmall, color = AurumColors.Green,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.clearNobitexApiToken() }) { Text("حذف کلید") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = capInput, onValueChange = { capInput = it }, singleLine = true,
                    label = { Text("سقف ایمنی هر سفارش (USDT)") }, modifier = Modifier.weight(1f))
                Button(onClick = { capInput.toDoubleOrNull()?.let { viewModel.saveNobitexOrderCap(it) } }) { Text("ذخیره سقف") }
            }
            Text("سقف فعلی: ${settings.nobitexLiveOrderCapUsdt} USDT · یک محافظ محلی در برابر اشتباه تایپی، نه محدودیت صرافی.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)

            Button(onClick = { viewModel.checkNobitexBalance("usdt") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("بررسی موجودی واقعی USDT") }
            balance?.let { (currency, amount) ->
                Text("موجودی واقعی $currency: $amount", style = MaterialTheme.typography.bodySmall, color = AurumColors.Cyan)
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                Text("می‌دانم این معاملهٔ واقعی و با پول واقعی من است؛ هیچ حد ضرر خودکاری در این نسخه ارسال نمی‌شود.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            }

            if (acknowledged) {
                Text("رمزارز سفارش واقعی · از میان ${catalog.markets.size.takeIf { it > 0 } ?: "…"} بازار تتری زندهٔ نوبیتکس",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(value = liveMarketQuery, onValueChange = { liveMarketQuery = it.take(15) }, singleLine = true,
                    label = { Text("جست‌وجوی نماد") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                val liveChoices = remember(liveMarketQuery, catalog.markets) {
                    val pool = catalog.markets.map { it.market }.ifEmpty { listOf(NobitexMarket.BTC_USDT) }
                    if (liveMarketQuery.isBlank()) pool.take(10)
                    else pool.filter { it.srcCurrency.contains(liveMarketQuery.trim(), ignoreCase = true) }.take(20)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    liveChoices.forEach { choice ->
                        FilterChip(selected = liveMarket == choice, onClick = { viewModel.setNobitexLiveMarket(choice) },
                            label = { Text(choice.srcCurrency.uppercase() + "/USDT") })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = side == SignalAction.BUY, onClick = { side = SignalAction.BUY },
                        label = { Text("خرید ${liveMarket.srcCurrency.uppercase()}") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = side == SignalAction.SELL, onClick = { side = SignalAction.SELL },
                        label = { Text("فروش ${liveMarket.srcCurrency.uppercase()}") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = execution == "limit", onClick = { execution = "limit" },
                        label = { Text("Limit") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = execution == "market", onClick = { execution = "market" },
                        label = { Text("Market") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = amountText, onValueChange = { amountText = it },
                        label = { Text("حجم ${liveMarket.srcCurrency.uppercase()}") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = priceText, onValueChange = { priceText = it },
                        label = { Text(if (execution == "market") "سقف/کف قیمت (اختیاری)" else "قیمت USDT") },
                        singleLine = true, modifier = Modifier.weight(1f))
                }
                val qty = amountText.toDoubleOrNull()
                val px = priceText.toDoubleOrNull()
                val notional = if (qty != null && px != null) qty * px else null
                notional?.let {
                    Text("ارزش تقریبی سفارش: ${String.format("%.2f", it)} USDT (سقف ${settings.nobitexLiveOrderCapUsdt})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it > settings.nobitexLiveOrderCapUsdt) AurumColors.Red else AurumColors.TextSecondary)
                }
                Button(onClick = {
                    val amount = amountText.trim()
                    val price = priceText.trim().ifBlank { null }
                    val estimate = px ?: 0.0
                    if (amount.isNotBlank()) confirmRequest = LiveOrderRequest(side, liveMarket.srcCurrency, liveMarket.destination, amount, price, execution, estimate)
                }, enabled = !busy && amountText.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (busy) "در حال ارسال…" else "بررسی و تأیید سفارش واقعی")
                }
            }
        }
        liveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red, modifier = Modifier.padding(top = 6.dp)) }

        if (orders.isNotEmpty()) {
            Text("سفارش‌های واقعی ارسال‌شده از این اپ", style = MaterialTheme.typography.titleSmall,
                color = AurumColors.TextPrimary, modifier = Modifier.padding(top = 12.dp))
            orders.take(15).forEach { order -> LiveOrderRow(order, viewModel) }
            TextButton(onClick = { viewModel.clearNobitexLiveOrders() }) { Text("پاک کردن دفتر محلی (فقط محلی؛ خود سفارش‌ها در نوبیتکس می‌مانند)") }
        }
    }

    confirmRequest?.let { request ->
        AlertDialog(onDismissRequest = { confirmRequest = null; typedConfirm = "" },
            title = { Text("ارسال سفارش واقعی به نوبیتکس؟") },
            text = {
                Column {
                    Text("${if (request.side == SignalAction.BUY) "خرید" else "فروش"} ${request.amount} ${request.src.uppercase()}/${request.dst.uppercase()} · " +
                        "${request.execution} ${request.price?.let { "قیمت $it" } ?: "بدون سقف قیمت"}")
                    Text("این سفارش واقعی است و با پول واقعی حساب شما اجرا می‌شود. Nobitex ممکن است آن را رد کند؛ پیام دقیق آن‌ها نمایش داده خواهد شد.",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(value = typedConfirm, onValueChange = { typedConfirm = it },
                        label = { Text("برای تأیید نهایی تایپ کنید: ارسال") }, singleLine = true,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = {
                confirmRequest = null
                viewModel.placeNobitexLiveOrder(request.side, request.src, request.dst, request.amount,
                    request.price, request.execution, request.estimatedPrice)
                typedConfirm = ""
            }, enabled = typedConfirm.trim() == "ارسال") { Text("ارسال سفارش واقعی") } },
            dismissButton = { TextButton(onClick = { confirmRequest = null; typedConfirm = "" }) { Text("انصراف") } })
    }
}

@Composable
private fun LiveOrderRow(order: NobitexLiveOrder, viewModel: AurumViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${formatDateTime(order.submittedAt)} · ${order.side} ${order.requestedAmount} ${order.symbol} · ${order.execution}",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
        Text("وضعیت: ${order.lastStatus}" + (order.exchangeOrderId?.let { " · شناسه نوبیتکس $it" } ?: "") +
            (order.lastMatchedAmount?.let { " · پرشده $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = if (order.lastStatus == "ثبت نشد") AurumColors.Red else AurumColors.TextSecondary)
        order.errorMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.Red) }
        if (!order.isTerminal && order.exchangeOrderId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.refreshNobitexLiveOrderStatus(order) }) { Text("بررسی وضعیت") }
                TextButton(onClick = { viewModel.cancelNobitexLiveOrder(order) }) { Text("لغو سفارش") }
            }
        }
    }
}
