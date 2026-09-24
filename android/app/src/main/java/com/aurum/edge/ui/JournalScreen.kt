package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.PaperConditionRecord
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NobitexMarket
import com.aurum.edge.data.NobitexPracticeTrade
import com.aurum.edge.engine.PerformanceMetrics
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun JournalScreen(viewModel: AurumViewModel, market: MarketState) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val opportunities by viewModel.opportunities.collectAsStateWithLifecycle()
    val opportunityError by viewModel.opportunityError.collectAsStateWithLifecycle()
    val nobitexTrades by viewModel.nobitexTrades.collectAsStateWithLifecycle()
    val nobitexJournalError by viewModel.nobitexJournalError.collectAsStateWithLifecycle()
    val nobitexState by viewModel.nobitex.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadError by viewModel.journalError.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmOpportunityClear by remember { mutableStateOf(false) }
    var confirmNobitexClear by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val livePrice = market.lastPrice?.takeIf {
        it.isFinite() && it > 0 && !market.showingCachedData &&
            market.feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
            market.feed.lastSuccessAt?.let { at -> now - at in 0L..90_000L } == true &&
            market.candles.lastOrNull()?.time?.let { at ->
                now - at in 0L..minOf(180_000L, market.interval.millis * 2)
            } == true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionCard(
            title = "ژورنال معاملات کاغذی",
            subtitle = "فقط پوزیشن‌های کاغذی واقعاً ثبت‌شده؛ خط‌های سیگنال چارت معامله نیستند",
        ) {
            loadError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = AurumColors.Red, modifier = Modifier.padding(bottom = 8.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("بسته‌شده", "${stats.total}", AurumColors.TextPrimary, Modifier.weight(1f))
                StatTile("باز", "${stats.open}", AurumColors.Gold, Modifier.weight(1f))
                StatTile("برد/باخت", "${stats.wins}/${stats.losses}", AurumColors.Green, Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                StatTile("نرخ برد", stats.winRate?.let { "${String.format("%.1f", it)}%" } ?: "—", AurumColors.Green, Modifier.weight(1f))
                StatTile("فاکتور سود", stats.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
                StatTile("سود خالص", "${formatPrice(stats.netPnl)}$", if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            }
            if (stats.total == 0 && loadError == null) {
                Text(
                    "هنوز معامله بسته‌شده‌ای نیست. آمار فقط از نتایج واقعی ساخته می‌شود؛ عدد نمایشی نداریم.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (opportunities.isNotEmpty() || opportunityError != null) {
            SectionCard("فرصت‌های ۹/۹ بررسی‌شده", "بعضی به معاملهٔ کاغذی وصل‌اند؛ خودِ کاندیدا معامله نیست و در آمار محاسبه نمی‌شود") {
                opportunityError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.Red) }
                opportunities.take(30).forEach { item ->
                    OpportunityRow(item, trades.any { it.id == item.paperTradeId })
                }
                if (opportunities.size > 30) Text("۳۰ مورد اخیر از ${opportunities.size} کاندیدای ذخیره‌شده",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                if (opportunities.isNotEmpty()) OutlinedButton(onClick = { confirmOpportunityClear = true }) {
                    Text("پاک کردن تاریخچهٔ کاندیداها (نه معاملات)")
                }
            }
        }

        val open = trades.filter { it.isOpen }
        if (open.isNotEmpty()) {
            SectionCard("پوزیشن‌های باز", "ارزش‌گذاری با آخرین قیمت واقعی دریافتی") {
                open.forEach { trade ->
                    val unrealized = livePrice?.takeIf { trade.symbol == market.symbol }?.let { price ->
                        val perOz = if (trade.action == SignalAction.BUY) price - trade.entry else trade.entry - price
                        perOz * trade.positionOz
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${trade.symbol} · ${if (trade.action == SignalAction.BUY) "LONG" else "SHORT"} ${trade.interval.label} · ${String.format("%.6f", trade.positionOz)} ${trade.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (trade.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                            )
                            Text(
                                "ورود ${formatPrice(trade.entry)} · SL ${formatPrice(trade.stopLoss)} · TP ${formatPrice(trade.takeProfit)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextMuted,
                            )
                            Text(
                                "${formatDateTime(trade.openedAt)} · ${if (trade.autoOpened) "خودکار کاغذی ۹/۹" else if (trade.note.startsWith("ورود دستی")) "دستی؛ بدون سیگنال" else "با تأیید کاربر"} · ${trade.id.take(8)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextMuted,
                            )
                            trade.newsEvidence?.let { verdict ->
                                Text("AI ${verdict.model} · ${verdict.direction} · ${verdict.evidence.joinToString { it.source }}",
                                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                            }
                            trade.mtf?.let { snapshot ->
                                Text(
                                    "تراز چندتایم‌فریم هنگام ورود: ${snapshot.bias} · هم‌جهتی ${(snapshot.alignment * 100).toInt()}%" +
                                        (if (snapshot.veto) " · وتو داشته" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.Gold,
                                )
                            }
                            ConditionDisclosure(trade.id, trade.entryConditions)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                unrealized?.let { "${if (it >= 0) "+" else ""}${formatPrice(it)}$" } ?: "—",
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    unrealized == null -> AurumColors.TextMuted
                                    unrealized >= 0 -> AurumColors.Green
                                    else -> AurumColors.Red
                                },
                            )
                            OutlinedButton(
                                onClick = { viewModel.closePaperTrade(trade) },
                                enabled = livePrice != null && trade.symbol == market.symbol,
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text("بستن", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }

        val closed = trades.filter { !it.isOpen }
        if (closed.isNotEmpty()) {
            SectionCard("معاملات بسته‌شده", "تسویه‌شده روی قیمت واقعی") {
                closed.forEach { trade: PaperTrade -> TradeRow(trade) }
            }
            Button(
                onClick = { confirmClear = true },
                colors = ButtonDefaults.buttonColors(containerColor = AurumColors.SurfaceAlt, contentColor = AurumColors.TextSecondary),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("پاک کردن ژورنال") }
        }
        if (nobitexTrades.isNotEmpty() || nobitexJournalError != null) {
            val snapshot = (nobitexState as? NobitexState.Done)?.snapshot
            SectionCard("ژورنال مستقلِ تمرین نوبیتکس · BTCUSDT", "ask/bid دفتر سفارش عمومیِ زمان‌دار · سود فرضی USDT، جدا از آمار دلاری طلا") {
                nobitexJournalError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
                val closedPractice = nobitexTrades.filterNot { it.isOpen }
                Text("باز ${nobitexTrades.count { it.isOpen }} · بسته ${closedPractice.size} · سود/زیان مشاهده‌ای ${formatPrice(closedPractice.sumOf { it.pnlQuote ?: 0.0 })} USDT",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                nobitexTrades.take(30).forEach { practice ->
                    NobitexPracticeRow(practice, snapshot?.takeIf { it.market == NobitexMarket.BTC_USDT &&
                        it.practiceBlocker() == null && it.quote.receivedAt > practice.openedAt &&
                        (it.book?.updatedAt ?: 0L) > practice.openedAt &&
                        (it.book?.updatedAt ?: 0L) > (practice.orderBookUpdatedAt ?: practice.openedAt) } != null,
                        onClose = { viewModel.closeNobitexPractice(practice.id) })
                }
                Text("SL/TP فقط با bid دفتر سفارشِ دارای timestamp جدیدتر از ورود بررسی می‌شود؛ بین دو دریافت ممکن است برخورد دیده نشود. کارمزد/لغزش در این حساب تمرینی صفر فرض شده‌اند؛ این عملکرد قابل معامله نیست. برای بستن دستی، در تب رمزارز نرخ تازه بگیر.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                if (nobitexTrades.isNotEmpty()) OutlinedButton(onClick = { confirmNobitexClear = true }) {
                    Text("پاک کردن فقط تمرین‌های نوبیتکس")
                }
            }
        }
        PerformancePanel(PerformanceMetrics.fromPaper(trades, settings.accountBalance),
            "حداکثر ۵۰۰ معاملهٔ آخر ژورنال · فرض موجودی اولیه ${formatPrice(settings.accountBalance)}$ (در طول تاریخچه ممکن است تغییر کرده باشد)")
        reports.firstOrNull()?.let { report ->
            StoredReportCard(report)
            PerformanceMetrics.fromStoredReport(report.outOfSample)?.let { performance ->
                PerformancePanel(performance, "آخرین تست خارج از نمونه · ${report.outOfSample.symbol} · ${report.interval}")
            } ?: SectionCard("گزارش قدیمی ناقص", "در نسخهٔ قبلی تنها بخشی از معاملات ذخیره شده بود") {
                Text("برای گزارش ۱۸ شاخص دقیق، تست خارج از نمونه را دوباره اجرا کنید.", color = AurumColors.Gold)
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("حذف قطعی ژورنال کاغذی؟") },
        text = { Text("تمام معاملات کاغذی باز و بسته‌شدهٔ ثبت‌شده روی گوشی پاک می‌شوند؛ بازگشت‌پذیر نیست. تاریخچهٔ کاندیداها جداگانه نگهداری می‌شود.") },
        confirmButton = { TextButton(onClick = { viewModel.clearJournal(); confirmClear = false }) { Text("حذف") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("انصراف") } })
    if (confirmOpportunityClear) AlertDialog(onDismissRequest = { confirmOpportunityClear = false },
        title = { Text("تاریخچهٔ کاندیداهای آموزشی پاک شود؟") },
        text = { Text("فقط اعلان‌های ۹/۹ ذخیره‌شده پاک می‌شوند؛ معاملات ژورنال تغییر نمی‌کنند. اگر کندل هنوز تازه باشد ممکن است دوباره هشدار دریافت کنید.") },
        confirmButton = { TextButton(onClick = { viewModel.clearOpportunityHistory(); confirmOpportunityClear = false }) { Text("حذف کاندیداها") } },
        dismissButton = { TextButton(onClick = { confirmOpportunityClear = false }) { Text("انصراف") } })
    if (confirmNobitexClear) AlertDialog(onDismissRequest = { confirmNobitexClear = false },
        title = { Text("تمرین‌های نوبیتکس حذف شوند؟") },
        text = { Text("تمام تمرین‌های باز و بستهٔ BTCUSDT از این گوشی پاک می‌شوند؛ معاملات طلا و هشدارها دست‌نخورده باقی می‌مانند.") },
        confirmButton = { TextButton(onClick = { viewModel.clearNobitexPractice(); confirmNobitexClear = false }) { Text("حذف تمرین‌ها") } },
        dismissButton = { TextButton(onClick = { confirmNobitexClear = false }) { Text("انصراف") } })
}

@Composable
private fun TradeRow(trade: PaperTrade) {
    val pnl = trade.pnlUsd ?: 0.0
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            trade.action.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (trade.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("${trade.symbol} · ${if (trade.autoOpened) "خودکار کاغذی ۹/۹" else "کاغذی"} · ${trade.id.take(8)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            Text("باز ${formatDateTime(trade.openedAt)} → بسته ${formatDateTime(trade.closedAt)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            Text(
                "${formatPrice(trade.entry)} → ${formatPrice(trade.exitPrice)}",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextPrimary,
            )
            Text("${trade.exitReason ?: "—"} · ${String.format("%.6f", trade.positionOz)} ${trade.unit}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            trade.newsEvidence?.let { ai ->
                Text("خبر ${ai.model} · ${formatDateTime(ai.checkedAt)} · ${ai.evidence.joinToString { it.source }}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                ai.evidence.forEach { evidence ->
                    Text("${evidence.source}: ${evidence.headline.take(90)}",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                    OutlinedButton(onClick = { runCatching { uriHandler.openUri(evidence.url) } }) {
                        Text("شاهد در ناشر", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            trade.mtf?.let { Text("MTF هنگام ورود: ${it.bias} · ${(it.alignment * 100).toInt()}٪ هم‌جهتی",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted) }
            ConditionDisclosure(trade.id, trade.entryConditions)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${if (pnl >= 0) "+" else ""}${formatPrice(pnl)}$",
                style = MaterialTheme.typography.labelMedium,
                color = if (pnl >= 0) AurumColors.Green else AurumColors.Red,
            )
            Text(
                trade.rMultiple?.let { "${String.format("%.2f", it)}R" } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
    }
}

@Composable
private fun NobitexPracticeRow(trade: NobitexPracticeTrade, canClose: Boolean, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text("${trade.symbol} · BUY spot فقط کاغذی · شناسهٔ ${trade.id.take(8)} · ${if (trade.isOpen) "باز" else "بسته"}",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Cyan)
        Text("${String.format("%.6f", trade.quantityBtc)} BTC · ورود ask ${formatPrice(trade.entryAsk)} ${trade.quoteUnit} · SL ${formatPrice(trade.stopLoss)} · TP ${formatPrice(trade.takeProfit)}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
        Text("شرایط ثبت: ${trade.conditionNote} · دریافت آمار روی گوشی ${formatDateTime(trade.quoteReceivedAt)} · دفتر سفارش ${formatDateTime(trade.orderBookUpdatedAt)} · کندل بسته ${formatDateTime(trade.historyBarTime)} (${trade.historyInterval})",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        Text("ثبت ${formatDateTime(trade.openedAt)} · ارزش فرضی ${formatPrice(trade.notionalQuote)} ${trade.quoteUnit}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        if (trade.isOpen) {
            OutlinedButton(onClick = onClose, enabled = canClose) { Text("بستن تمرین با bid تازه") }
        } else {
            Text("خروج bid ${formatPrice(trade.exitBid)} · ${formatDateTime(trade.closedAt)} · ${trade.exitReason} · نتیجهٔ فرضی ${formatPrice(trade.pnlQuote)} ${trade.quoteUnit}",
                style = MaterialTheme.typography.labelSmall,
                color = if ((trade.pnlQuote ?: 0.0) >= 0) AurumColors.Green else AurumColors.Red)
        }
    }
}

@Composable
private fun OpportunityRow(item: PaperOpportunity, tradeStillSaved: Boolean) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text("${item.symbol} ${item.action} · ${item.interval.label} · ${formatDateTime(item.alertedAt)}",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        Text("قیمت دریافت‌شده ${formatPrice(item.priceAtAlert)}$ · SL ${formatPrice(item.stopLoss)} · TP ${formatPrice(item.takeProfit)}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Text(when {
            item.paperTradeId == null -> "فقط کاندیدا؛ اعلان به‌تنهایی پوزیشن کاغذی باز نمی‌کند."
            tradeStillSaved -> "ورود کاغذی جداگانه ثبت شد · شناسهٔ ${item.paperTradeId.take(8)}"
            else -> "رکورد معاملهٔ مرتبط بعداً از ژورنال پاک شده است."
        }, style = MaterialTheme.typography.labelSmall,
            color = if (tradeStillSaved) AurumColors.Green else AurumColors.TextMuted)
        Text("کندل ${formatDateTime(item.signalBarTime)} · MTF ${item.mtf.bias} · مدل ${item.newsEvidence.model}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        ConditionDisclosure(item.key, item.conditions)
        item.newsEvidence.evidence.forEach { news ->
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(news.url) } }) {
                Text("شاهد خبر: ${news.source}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ConditionDisclosure(key: String, conditions: List<PaperConditionRecord>) {
    if (conditions.isEmpty()) {
        Text("شرایط ورود برای این رکورد قدیمی/دستی ذخیره نشده‌اند؛ تأیید ۹/۹ ادعا نمی‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        return
    }
    var expanded by remember(key) { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "بستن شرایط ثبت‌شده" else "نمایش ${conditions.size} شرط هنگام ثبت",
            style = MaterialTheme.typography.labelSmall)
    }
    if (expanded) conditions.forEachIndexed { index, condition ->
        Text("${index + 1}. ${condition.name} · ${condition.status} · ${condition.detail}",
            style = MaterialTheme.typography.labelSmall,
            color = if (condition.status == "CONFIRMED") AurumColors.Green else AurumColors.Red,
            modifier = Modifier.padding(vertical = 2.dp))
    }
}

/**
 * The last walk-forward run kept on this device. It is shown with its own date so the numbers can
 * be re-checked instead of taken on faith.
 */
@Composable
private fun StoredReportCard(report: WalkForwardRecord) {
    val outPf = report.outOfSample.profitFactor ?: 0.0
    SectionCard(
        title = "آخرین تست خارج از نمونه (ذخیره‌شده روی گوشی)",
        subtitle = "${report.interval} · ${report.bars} کندل واقعی · ${formatDateTime(report.generatedAt)}",
    ) {
        Text(
            report.verdict,
            style = MaterialTheme.typography.bodySmall,
            color = if (outPf > 1.0) AurumColors.Green else AurumColors.Red,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("داخل نمونه PF", report.inSample.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.TextSecondary, Modifier.weight(1f))
            StatTile("خارج نمونه PF", report.outOfSample.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
            StatTile("معاملات خارج نمونه", "${report.outOfSample.trades.size}", AurumColors.TextPrimary, Modifier.weight(1f))
        }
        Text(
            "هزینه‌های فرض‌شده: اسپرد ${report.outOfSample.spreadPrice}$ · کمیسیون ${report.outOfSample.commissionPerOz}$ بر انس",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
