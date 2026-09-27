package com.aurum.edge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.data.NobitexMarket
import com.aurum.edge.engine.Backtester
import com.aurum.edge.engine.IctRangeAnalyzer
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CHART_MARKETS = listOf(NobitexMarket.BTC_USDT, NobitexMarket.ETH_USDT, NobitexMarket.SOL_USDT,
    NobitexMarket.XRP_USDT, NobitexMarket.DOGE_USDT, NobitexMarket.ADA_USDT)
/** Nobitex's published base-tier ("Regular") USDT-market taker fee; real, cited, not invented. */
private const val NOBITEX_BASE_TAKER_FEE = 0.0013

/**
 * "دقیقاً مثل TradingView": real candlestick chart (pan/zoom, Ichimoku cloud, EMA200, VWAP,
 * drawn entry/SL/TP lines) for any of the 6 vetted Nobitex assets, using the EXACT SAME
 * [com.aurum.edge.engine.SignalEngine] rules the gold chart uses — plus real order / paper
 * journal / historical backtest actions on the same real candles. Reuses [CandleChart], the
 * same native chart the XAU/USD screen already uses; nothing here is a separate/lesser engine.
 */
@Composable
fun NobitexChartScreen(viewModel: AurumViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.enableNobitexAlerts(context) }
    val state by viewModel.nobitex.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var market by remember { mutableStateOf(NobitexMarket.BTC_USDT) }
    var interval by remember { mutableStateOf(Interval.H1) }
    var confirmJournal by remember { mutableStateOf<Triple<Signal, Double, NobitexMarket>?>(null) }
    var backtestResult by remember { mutableStateOf<Backtester.Result?>(null) }
    var backtestBusy by remember { mutableStateOf(false) }
    var backtestError by remember { mutableStateOf<String?>(null) }

    val current = (state as? NobitexState.Done)?.snapshot?.takeIf { it.market == market && it.interval == interval }
    val signal = if (current != null) viewModel.nobitexJournalSignal() else null
    val symbol = viewModel.nobitexJournalSymbol(market)
    val structure = remember(current?.candles, current?.interval) {
        current?.let { IctRangeAnalyzer.analyze(it.candles, it.interval) }
    }

    SectionCard("چارت نوبیتکس · $symbol", "کندل واقعی + ابر ایچیموکو/EMA200/VWAP + خط ورود/SL/TP همان قوانین طلا") {
        Text("رمزارز و بازه را انتخاب و دریافت کنید؛ همان SignalEngine طلا (ایچیموکو+VWAP+EMA200+RSI+ATR+MACD/ADX) روی این کندل‌ها اجرا می‌شود.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CHART_MARKETS.forEach { choice ->
                FilterChip(selected = market == choice, onClick = { market = choice; backtestResult = null; backtestError = null },
                    label = { Text(choice.srcCurrency.uppercase() + "/USDT") })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Interval.M5, Interval.M15, Interval.H1, Interval.H4).forEach { item ->
                FilterChip(selected = interval == item, onClick = { interval = item; backtestResult = null; backtestError = null },
                    label = { Text(item.label) })
            }
        }
        Button(onClick = { viewModel.downloadNobitex(market, interval) },
            enabled = state != NobitexState.Loading, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(if (state == NobitexState.Loading) "در حال دریافت…" else "دریافت کندل واقعی $symbol")
        }
        when (val result = state) {
            NobitexState.Idle -> Text("برای شروع، دریافت را بزنید.", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            NobitexState.Loading -> Unit
            is NobitexState.Failed -> Text("دریافت ناموفق: ${result.message}", style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
            is NobitexState.Done -> Unit
        }
    }

    if (current != null) {
        CandleChart(
            candles = current.candles, interval = current.interval, signal = signal, structure = structure,
            modifier = Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Tenkan", AurumColors.Cyan)
            Legend("Kijun", AurumColors.Purple)
            Legend("VWAP", AurumColors.Gold)
            Legend("EMA200", AurumColors.TextSecondary)
        }
        SectionCard("حمایت/مقاومت ICT روی $symbol", "همان تحلیل ساختار کندل‌های بسته که XAU/USD دارد") {
            val level = structure?.range
            if (level == null) Text("رنجِ دوطرفهٔ تأییدشده یافت نشد.", style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            else Text("حمایت ${formatPrice(level.support)} (${level.supportTouches} برخورد) · مقاومت ${formatPrice(level.resistance)} (${level.resistanceTouches} برخورد)",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Purple)
            structure?.let {
                Text("BUY: ${ictStatus(it.buy.state)} · SELL: ${ictStatus(it.sell.state)}", style = MaterialTheme.typography.bodySmall,
                    color = if (it.buy.ready || it.sell.ready) AurumColors.Gold else AurumColors.TextSecondary)
            }
        }

        val signalBlocker = when {
            !market.supportsPractice -> "این نماد برای معاملهٔ کاغذی تأیید نشده است"
            trades.any { it.symbol == symbol && it.isOpen } -> "پوزیشن این نماد هنوز باز است"
            signal != null && trades.any { it.symbol == symbol && it.signalBarTime == signal.barTime } -> "این کندل قبلاً معامله شده است"
            else -> null
        }
        SignalSummaryCard(
            signal = signal,
            onOpenPaperTrade = {
                val price = current.quote.latest
                if (signal != null && price.isFinite() && price > 0) confirmJournal = Triple(signal, price, market)
            },
            entryBlocker = signalBlocker,
        )

        SectionCard("معاملهٔ واقعی روی $symbol", "همین چارت؛ کلید API خودتان در بخش قرمز پایین لازم است") {
            Text("برای سفارش واقعی روی همین نماد، دکمهٔ زیر رمزارز بخش قرمز «معاملهٔ واقعی نوبیتکس» را روی $symbol تنظیم می‌کند؛ خودِ سفارش همان‌جا با تأیید صریح شما ارسال می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            OutlinedButton(onClick = { viewModel.setNobitexLiveMarket(market) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("تنظیم بخش معاملهٔ واقعی روی $symbol")
            }
        }

        SectionCard("بک‌تست روی همین کندل‌های واقعی دریافتی", "همان موتور بک‌تست طلا؛ حداکثر ${current.candles.size} کندل، نمونهٔ کوچک") {
            Text("کارمزد پایهٔ نوبیتکس برای بازار تتری (سطح Regular، تیکر) ٪۰٫۱۳ است؛ چون این موتور کارمزد را عدد ثابت به‌جای نرخ پویا می‌گیرد، همان ٪۰٫۱۳ ضربدر آخرین قیمت به‌عنوان تقریب ثابت استفاده می‌شود — برای دارایی با نوسان زیاد طی بازه، فقط یک تقریب است، نه محاسبهٔ دقیق هر معامله.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            Button(onClick = {
                backtestBusy = true; backtestError = null; backtestResult = null
                val candles = current.candles
                val price = current.quote.latest.takeIf { it.isFinite() && it > 0 } ?: candles.lastOrNull()?.close ?: 0.0
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.Default) {
                            Backtester.run(candles, current.interval, symbol,
                                dataSource = "نوبیتکس عمومی (واقعی، حداکثر ۳۲۰ کندل)",
                                initialBalance = 100.0, riskPercent = 1.0,
                                spreadPrice = 0.0, commissionPerOz = price * NOBITEX_BASE_TAKER_FEE,
                                leverage = 1, minPositionOz = 0.000001, threshold = settings.minConfidence)
                        }
                        backtestResult = result
                    } catch (e: Exception) {
                        backtestError = e.message ?: "بک‌تست ناموفق بود"
                    } finally {
                        backtestBusy = false
                    }
                }
            }, enabled = !backtestBusy && current.candles.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (backtestBusy) "در حال محاسبه…" else "اجرای بک‌تست روی $symbol")
            }
            backtestError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red, modifier = Modifier.padding(top = 6.dp)) }
            backtestResult?.let { r ->
                if (!r.hasTrades) {
                    Text("در این نمونهٔ کوچک هیچ معاملهٔ فرضی بسته‌ای رخ نداد؛ نمونه خیلی کوچک است یا سیگنالی فعال نبود.",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text("${r.trades.size} معاملهٔ فرضی بسته · وین‌ریت ${r.winRate?.let { String.format("%.0f", it * 100) + "٪" } ?: "—"} · " +
                        "PF ${r.profitFactor?.let { String.format("%.2f", it) } ?: "—"} · انتظار R ${r.expectancyR?.let { String.format("%.2f", it) } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary, modifier = Modifier.padding(top = 6.dp))
                    Text("برد ${r.wins} · باخت ${r.losses} · بیشینه افت ${String.format("%.1f", r.maxDrawdownPct)}٪ · خالص ${String.format("%.4f", r.netPnl)} USDT (روی موجودی فرضی ۱۰۰)",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                    Text(r.note, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
                }
                Text("این یک بازپخش تاریخیِ فقط قواعد فنی روی نمونهٔ کوچک ${current.candles.size} کندلی است؛ ادعای عملکرد آینده یا اجرای واقعی نیست.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    confirmJournal?.let { (sig, price, m) ->
        val dialogSymbol = viewModel.nobitexJournalSymbol(m)
        AlertDialog(onDismissRequest = { confirmJournal = null },
            title = { Text("ثبت کاغذی $dialogSymbol در ژورنال طلا؟") },
            text = { Text("${sig.action} · ورود تقریبی ${formatPrice(price)} · SL ${sig.stopLoss?.let { formatPrice(it) }} " +
                "· TP ${sig.takeProfit?.let { formatPrice(it) }} USDT.\nهیچ سفارشی به نوبیتکس ارسال نمی‌شود؛ فقط رکورد کاغذی مشترک با ژورنال طلا.") },
            confirmButton = { TextButton(onClick = {
                confirmJournal = null
                viewModel.openNobitexJournalTrade(sig, price, m)
            }) { Text("ثبت در ژورنال") } },
            dismissButton = { TextButton(onClick = { confirmJournal = null }) { Text("انصراف") } })
    }
}
