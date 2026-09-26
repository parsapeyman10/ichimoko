package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.NobitexLiveOrder
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * "متودم همون متود طلاست": the SAME SignalEngine (Ichimoku + VWAP + EMA200 + RSI + ATR + MACD/ADX
 * confluence) and the SAME JournalStore that gold uses, applied to Nobitex BTC/USDT candles.
 * Entries here are manual (no Forex/USD news gate — that gate is specific to XAU/USD) but every
 * other rule (risk sizing, ATR-based stop/target, settlement against real closed candles,
 * win-rate statistics) is identical to the gold journal.
 */
@Composable
fun NobitexJournalSignalSection(viewModel: AurumViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.enableNobitexAlerts(context) }
    val nobitexState by viewModel.nobitex.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmJournal by remember { mutableStateOf<Pair<Signal, Double>?>(null) }
    // Recomputed on every recomposition from the latest downloaded snapshot — cheap, pure.
    val signal = if (nobitexState is NobitexState.Done) viewModel.nobitexJournalSignal() else null
    val snapshot = (nobitexState as? NobitexState.Done)?.snapshot

    SectionCard("سیگنال BTC/USDT با متود طلا", "همان SignalEngine و همان ژورنال کاغذی طلا؛ فقط ورود دستی، بدون گیت خبر USD") {
        Text("این بخش، نه معاملهٔ ساده‌شدهٔ درصدی، بلکه دقیقاً همان قوانین ایچیموکو/VWAP/EMA200/RSI/ATR/MACD طلاست.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
        if (snapshot == null) {
            Text("ابتدا از کارت بالا کندل BTCUSDT را دریافت کنید.", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted)
        } else if (signal == null) {
            Text("برای این سیگنال حداقل ۲۱۰ کندل بستهٔ BTCUSDT لازم است یا محاسبه ناموفق بود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        } else {
            val color = when (signal.action) {
                SignalAction.BUY -> AurumColors.Green
                SignalAction.SELL -> AurumColors.Red
                SignalAction.NO_TRADE -> AurumColors.TextMuted
            }
            Text("جهت: ${signal.action} · امتیاز همگرایی ${String.format("%.1f", signal.confidence)}",
                style = MaterialTheme.typography.bodyMedium, color = color)
            if (signal.isActionable) {
                Text("ورود ${signal.entry?.let { formatPrice(it) } ?: "—"} · SL ${signal.stopLoss?.let { formatPrice(it) } ?: "—"} " +
                    "· TP ${signal.takeProfit?.let { formatPrice(it) } ?: "—"} USDT · R:R ${signal.riskReward?.let { String.format("%.2f", it) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
            } else {
                Text("زیر آستانهٔ ${String.format("%.0f", settings.minConfidence)} یا شرایط ورود کامل نیست.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
            signal.reasons.take(4).forEach {
                Text("✓ $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            }
            signal.blockers.take(4).forEach {
                Text("✗ $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
            }
            val existingOpen = viewModel.trades.collectAsStateWithLifecycle().value.any { it.isOpen && it.symbol == "BTC/USDT" }
            Button(onClick = {
                val price = snapshot.quote.latest
                if (price.isFinite() && price > 0) confirmJournal = signal to price
            }, enabled = signal.isActionable && !existingOpen, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(if (existingOpen) "پوزیشن باز BTC/USDT در ژورنال دارید" else "ثبت در همان ژورنال طلا (کاغذی)")
            }
            Text("ورود کاغذی است، نه سفارش واقعی؛ SL/TP با همان قانون تسویهٔ کندل بستهٔ طلا بررسی می‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
    }

    confirmJournal?.let { (sig, price) ->
        AlertDialog(onDismissRequest = { confirmJournal = null },
            title = { Text("ثبت کاغذی BTC/USDT در ژورنال طلا؟") },
            text = { Text("${sig.action} · ورود تقریبی ${formatPrice(price)} · SL ${sig.stopLoss?.let { formatPrice(it) }} " +
                "· TP ${sig.takeProfit?.let { formatPrice(it) }} USDT.\nهیچ سفارشی به نوبیتکس ارسال نمی‌شود؛ فقط رکورد کاغذی مشترک با ژورنال طلا.") },
            confirmButton = { TextButton(onClick = {
                confirmJournal = null
                viewModel.openNobitexJournalTrade(sig, price)
            }) { Text("ثبت در ژورنال") } },
            dismissButton = { TextButton(onClick = { confirmJournal = null } ) { Text("انصراف") } })
    }
}

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
    val browser = LocalUriHandler.current
    var tokenInput by remember { mutableStateOf("") }
    var capInput by remember { mutableStateOf(settings.nobitexLiveOrderCapUsdt.toString()) }
    var acknowledged by remember { mutableStateOf(false) }
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
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = side == SignalAction.BUY, onClick = { side = SignalAction.BUY },
                        label = { Text("خرید BTC") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = side == SignalAction.SELL, onClick = { side = SignalAction.SELL },
                        label = { Text("فروش BTC") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = execution == "limit", onClick = { execution = "limit" },
                        label = { Text("Limit") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = execution == "market", onClick = { execution = "market" },
                        label = { Text("Market") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = amountText, onValueChange = { amountText = it },
                        label = { Text("حجم BTC") }, singleLine = true, modifier = Modifier.weight(1f))
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
                    if (amount.isNotBlank()) confirmRequest = LiveOrderRequest(side, "btc", "usdt", amount, price, execution, estimate)
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
                    Text("${if (request.side == SignalAction.BUY) "خرید" else "فروش"} ${request.amount} BTC/USDT · " +
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
