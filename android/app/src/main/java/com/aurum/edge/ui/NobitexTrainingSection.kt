package com.aurum.edge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Interval
import com.aurum.edge.data.NobitexMarket
import com.aurum.edge.data.NobitexPracticeRules
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

private data class PracticeRequest(val market: NobitexMarket, val amount: Double,
    val stopPct: Double, val targetPct: Double, val ask: Double, val quoteAt: Long)

/** Explicitly isolated from XAU's signal engine, ninth AI condition, and live trade execution. */
@Composable
fun NobitexTrainingSection(viewModel: AurumViewModel) {
    val state by viewModel.nobitex.collectAsStateWithLifecycle()
    val trades by viewModel.nobitexTrades.collectAsStateWithLifecycle()
    val journalError by viewModel.nobitexJournalError.collectAsStateWithLifecycle()
    var market by remember { mutableStateOf(NobitexMarket.BTC_USDT) }
    var interval by remember { mutableStateOf(Interval.M5) }
    var amountText by remember { mutableStateOf("100") }
    var stopText by remember { mutableStateOf("2") }
    var targetText by remember { mutableStateOf("4") }
    var pending by remember { mutableStateOf<PracticeRequest?>(null) }
    var showRiskDetails by remember { mutableStateOf(false) }
    var pendingCsv by remember { mutableStateOf<com.aurum.edge.data.NobitexSnapshot?>(null) }
    val uriHandler = LocalUriHandler.current
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val snapshot = pendingCsv
        pendingCsv = null
        if (uri != null && snapshot != null) viewModel.exportNobitexHistory(uri, snapshot)
    }
    val current = (state as? NobitexState.Done)?.snapshot?.takeIf {
        it.market == market && it.interval == interval
    }

    SectionCard("نوبیتکس · آموزش و صحه‌سنجی با دادهٔ عمومی", "مستقیم از API رسمی، بدون کلید صرافی، بدون ارسال سفارش") {
        Text("فعلاً فقط حساب کاغذی؛ آمار/دفتر عمومی، مستقل از موتور طلا.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
        Text("این بخش سفارش واقعی نمی‌فرستد؛ برای معاملهٔ واقعی به بخش قرمز «معاملهٔ واقعی نوبیتکس» در پایین صفحه بروید.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NobitexMarket.entries.forEach { choice ->
                FilterChip(selected = market == choice, onClick = { market = choice; pending = null },
                    label = { Text(choice.code) }, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Interval.M5, Interval.M15, Interval.H1, Interval.H4).forEach { item ->
                FilterChip(selected = interval == item, onClick = { interval = item; pending = null },
                    label = { Text(item.label) })
            }
        }
        Button(onClick = { viewModel.downloadNobitex(market, interval); pending = null },
            enabled = state != NobitexState.Loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state == NobitexState.Loading) "در حال دریافت…" else "دریافت رایگان کندل و آمار نوبیتکس")
        }
        when (val result = state) {
            NobitexState.Idle -> Text("برای شروع، نماد و بازه را انتخاب و دادهٔ واقعی را دریافت کنید.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            NobitexState.Loading -> Text("دادهٔ قبلی هنگام دریافت دوباره معتبر فرض نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            is NobitexState.Failed -> Text("دریافت ناموفق: ${result.message}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
            is NobitexState.Done -> Unit
        }
        OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://apidocs.nobitex.ir/market_data/%D8%AF%D8%B1%DB%8C%D8%A7%D9%81%D8%AA-%D8%AF%D8%A7%D8%AF%D9%87-%D9%87%D8%A7%DB%8C-ohlc") } }) {
            Text("مستند رسمی API عمومی نوبیتکس")
        }
    }

    NobitexSpotScanSection(viewModel)

    current?.let { snapshot ->
        val quote = snapshot.quote
        val unit = if (market == NobitexMarket.BTC_USDT) "USDT" else "ریال (آمار بازار)"
        SectionCard("صحه‌سنجی ${market.code}", "${snapshot.candles.size} کندل دریافتی · ${snapshot.candles.count { it.closed }} بسته · صرفاً دادهٔ مشاهده‌شده") {
            Text("قیمت اخیر ${formatPrice(quote.latest)} $unit · بهترین خرید ${formatPrice(quote.bestBuy)} · بهترین فروش ${formatPrice(quote.bestSell)}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
            Text("آمار بازار: ${if (quote.isClosed) "بسته" else "باز"} · تغییر روزانه ${quote.dayChangePct?.let { "${formatPrice(it)}٪" } ?: "نامشخص"} · دریافت روی گوشی: ${formatDateTime(quote.receivedAt)}؛ زمان مستقل قیمت توسط stats داده نشده است.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            snapshot.book?.let { book ->
                Text("دفتر سفارش عمومی v3: bid ${formatPrice(book.bestBid)} · ask ${formatPrice(book.bestAsk)} $unit · آخرین به‌روزرسانی خودِ صرافی: ${formatDateTime(book.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Cyan)
            } ?: Text("دفتر سفارش با timestamp معتبر در دسترس نیست (${snapshot.bookError ?: "پاسخ نامشخص"})؛ کندل/آمار فقط برای مطالعه نمایش داده شده‌اند و تمرین بسته است.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
            snapshot.lastClosed?.let { bar ->
                Text("آخرین کندل بسته: ${formatDateTime(bar.time)} (زمان آغاز کندل) · Close خام ${formatPrice(bar.close)} ${if (market == NobitexMarket.BTC_IRT) "[واحد تاریخی نامشخص]" else "USDT"}",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            }
            if (market == NobitexMarket.BTC_IRT) {
                Text("هشدار واحد: در بررسی زنده، OHLC بیت‌کوین ریالی تقریباً یک‌دهم stats ریالی بود. واحد قیمت تاریخی در مستند OHLC صریح نیست؛ تبدیل فرضی ۱۰× نکردیم. تمرین خرید/سود و ادغام چارت برای این جفت مسدود است.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Red,
                    modifier = Modifier.padding(top = 8.dp))
            }
            snapshot.candles.takeLast(4).forEach { bar ->
                Text("${formatDateTime(bar.time)} · O ${formatPrice(bar.open)} H ${formatPrice(bar.high)} L ${formatPrice(bar.low)} C ${formatPrice(bar.close)} · ${if (bar.closed) "بسته" else "در حال شکل‌گیری"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
            OutlinedButton(onClick = {
                pendingCsv = snapshot
                saveCsv.launch("nobitex_${snapshot.market.code}_${snapshot.interval.label}_${snapshot.downloadedAt}.csv")
            }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("ذخیرهٔ ${snapshot.candles.size} کندل دریافتی به CSV")
            }
            Text("تاریخچه تا ۳۲۰ کندل در هر درخواست؛ این فایل مشاهدات عمومی است، نه رونوشت حساب یا سفارش. BTCIRT در CSV با واحد نامعتبر صریح برچسب می‌خورد.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
    }

    SectionCard("تمرین Spot نوبیتکس · فقط BTCUSDT", "BUY کاغذی با ask دفتر سفارش زمان‌دار، خروج فرضی با bid تازه؛ بدون شورت/سفارش واقعی") {
        val blocker = if (current == null) "ابتدا BTCUSDT و کندل‌های معتبرِ تازه را دریافت کنید"
            else current.practiceBlocker()
        journalError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
        if (blocker != null) Text("فعلاً تمرین متوقف: $blocker",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        val preview = if (blocker == null && current != null) runCatching {
            NobitexPracticeRules.preview(current, amountText.toDoubleOrNull() ?: 0.0,
                stopText.toDoubleOrNull() ?: 0.0, targetText.toDoubleOrNull() ?: 0.0)
        } else null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = amountText, onValueChange = { amountText = it; pending = null },
                label = { Text("بودجهٔ فرضی USDT") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = stopText, onValueChange = { stopText = it; pending = null },
                label = { Text("حد ضرر ٪") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = targetText, onValueChange = { targetText = it; pending = null },
                label = { Text("حد سود ٪") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        val draft = preview?.getOrNull()
        if (draft != null) {
            Text("حجم ${String.format("%.6f", draft.quantityBtc)} BTC · ارزش ${formatPrice(draft.notional)} USDT · ریسک تا SL ${formatPrice(draft.riskQuote)} USDT",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
            Text("ask دفتر سفارش ${formatPrice(draft.ask)} · SL ${formatPrice(draft.stop)} · TP ${formatPrice(draft.target)} USDT",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        } else if (blocker == null) {
            Text("برگهٔ تمرین نامعتبر: ${preview?.exceptionOrNull()?.message ?: "حدود یا بودجه را بررسی کنید"}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
        }
        val open = trades.any { it.isOpen && it.symbol == NobitexMarket.BTC_USDT.code }
        Button(onClick = {
            val s = current
            if (s != null && draft != null) pending = PracticeRequest(s.market,
                amountText.toDouble(), stopText.toDouble(), targetText.toDouble(),
                draft.ask, s.quote.receivedAt)
        }, enabled = !open && journalError == null && draft != null,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (open) "تمرین BTCUSDT باز دارید" else "بررسی و تأیید خرید کاغذی Spot")
        }
        Text("تمرین دستی و بدون سفارش؛ کارمزد/لغزش لحاظ نشده و گیت ۹/۹ طلا به BTC تعمیم داده نشده.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        OutlinedButton(onClick = { showRiskDetails = !showRiskDetails }) {
            Text(if (showRiskDetails) "بستن جزئیات ریسک" else "جزئیات ریسک تمرین")
        }
        if (showRiskDetails) Text("حد ضرر/سود فقط با bid زمان‌دار دفتر سفارش پس از ثبت بررسی می‌شود؛ عبور بین دریافت‌ها ممکن است دیده نشود. سود/زیان واقعی نیست؛ نتیجه در ژورنال مستقل ثبت می‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
    }

    pending?.let { request ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text("تأیید خرید فقط کاغذی BTCUSDT؟") },
            text = { Text("${formatPrice(request.amount)} USDT بودجهٔ فرضی · ورود تقریبی ask دفتر سفارش ${formatPrice(request.ask)} USDT · SL ${formatPrice(request.ask * (1 - request.stopPct / 100))} · TP ${formatPrice(request.ask * (1 + request.targetPct / 100))}.\nهیچ سفارشی به نوبیتکس ارسال نمی‌شود. قیمت هنگام ثبت دوباره چک می‌شود؛ خروج فقط با bid دفتر سفارشِ دارای زمان جدید پس از ورود است.") },
            confirmButton = { TextButton(onClick = {
                pending = null
                viewModel.openNobitexPractice(request.market, request.amount, request.stopPct,
                    request.targetPct, request.ask, request.quoteAt)
            }) { Text("ثبت تمرین در ژورنال") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("انصراف") } })
    }
}
