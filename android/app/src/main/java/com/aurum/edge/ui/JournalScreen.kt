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
import com.aurum.edge.core.IctPriceActionRecord
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NobitexMarket
import com.aurum.edge.data.NobitexPracticeTrade
import com.aurum.edge.engine.EvidenceGrade
import com.aurum.edge.engine.PerformanceMetrics
import com.aurum.edge.engine.ResearchEvidence
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
    val reportError by viewModel.reportError.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadError by viewModel.journalError.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmOpportunityClear by remember { mutableStateOf(false) }
    var confirmNobitexClear by remember { mutableStateOf(false) }
    var showCombined by remember { mutableStateOf(false) }
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
            subtitle = "جمع فعالیت دستی و ۹/۹؛ فقط کاغذیِ ذخیره‌شده، نه سود استراتژی یا خط چارت",
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
                StatTile("خالص خامِ کل", "${formatPrice(stats.netPnl)}$",  if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
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
                                Text("AI ${verdict.model} · ${verdict.direction} · ${verdict.evidence.joinToString { it.source }}" +
                                    " · تقویم ${formatDateTime(verdict.calendarCheckedAt)}",
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
                            IctDisclosure(trade.id, trade.priceAction)
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
            SectionCard("معاملات بسته‌شده", "خروج فرضی بر اساس قیمت دریافتی؛ نه اجرای بروکر/هزینهٔ واقعی") {
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
            SectionCard("ژورنال مستقلِ تمرین نوبیتکس · همهٔ رمزارزها", "ask/bid دفتر سفارش عمومیِ زمان‌دار · سود فرضی USDT، جدا از آمار دلاری طلا") {
                nobitexJournalError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red) }
                val closedPractice = nobitexTrades.filterNot { it.isOpen }
                Text("باز ${nobitexTrades.count { it.isOpen }} · بسته ${closedPractice.size} · سود/زیان مشاهده‌ای ${formatPrice(closedPractice.sumOf { it.pnlQuote ?: 0.0 })} USDT",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                nobitexTrades.take(30).forEach { practice ->
                    NobitexPracticeRow(practice, snapshot?.takeIf { it.market.code == practice.symbol &&
                        it.practiceBlocker() == null && it.quote.receivedAt > practice.openedAt &&
                        (it.book?.updatedAt ?: 0L) > practice.openedAt &&
                        (it.book?.updatedAt ?: 0L) > (practice.orderBookUpdatedAt ?: practice.openedAt) } != null,
                        onClose = { viewModel.closeNobitexPractice(practice.id) })
                }
                Text("SL/TP فقط با bid دفتر سفارشِ دارای timestamp جدیدتر از ورود بررسی می‌شود؛ بین دو دریافت ممکن است برخورد دیده نشود. کارمزد/لغزش در این حساب تمرینی صفر فرض شده‌اند؛ این عملکرد قابل معامله نیست. برای بستن دستی، همان رمزارز را در تب رمزارز دوباره انتخاب و نرخ تازه بگیر.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                if (nobitexTrades.isNotEmpty()) OutlinedButton(onClick = { confirmNobitexClear = true }) {
                    Text("پاک کردن فقط تمرین‌های نوبیتکس")
                }
            }
        }
        if (loadError == null) {
            val recorded = trades.filter(ResearchEvidence::hasRecordedNineWay)
            val other = trades.filterNot(ResearchEvidence::hasRecordedNineWay)
            PaperEvidencePanel(recorded, other, settings.spreadPrice, settings.commissionPerOz)
            if (recorded.any { !it.isOpen && it.pnlUsd != null }) PerformancePanel(
                PerformanceMetrics.fromPaper(recorded, settings.accountBalance),
                "فقط کاغذی XAU/USD با ۹ شاهد ثبت‌شده؛ P/L خام بدون کارمزد/لغزش بروکر · فرض موجودی اولیه ${formatPrice(settings.accountBalance)}$")
            if (other.any { !it.isOpen && it.pnlUsd != null }) PerformancePanel(
                PerformanceMetrics.fromPaper(other, settings.accountBalance),
                "معاملات دستی/قدیمی/فاقد شواهد کامل · بدون ادعای عملکرد گیت ۹/۹")
            if (trades.any { !it.isOpen && it.pnlUsd != null }) {
                OutlinedButton(onClick = { showCombined = !showCombined },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(if (showCombined) "بستن آمار کلِ مخلوط" else "نمایش آمار کل ژورنال (مخلوط)")
                }
                if (showCombined) PerformancePanel(PerformanceMetrics.fromPaper(trades, settings.accountBalance),
                    "کل ژورنال: دستی + ۹/۹ مخلوط؛ برای اثبات استراتژی معتبر نیست · حداکثر ۵۰۰ معاملهٔ اخیر")
            }
        }
        reportError?.let { SectionCard("گزارش پژوهش قابل خواندن نیست") {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        } }
        if (reportError == null) reports.firstOrNull()?.let { report ->
            StoredReportCard(report)
            if (ResearchEvidence.stored(report).grade != EvidenceGrade.NO_DATA) {
                PerformanceMetrics.fromStoredReport(report.outOfSample)?.let { performance ->
                    PerformancePanel(performance, "فقط تست فنیِ خارج نمونه · ${report.outOfSample.symbol} · ${report.interval}؛ نه ۹/۹")
                }
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
        text = { Text("تمام تمرین‌های باز و بستهٔ هر رمزارز نوبیتکس از این گوشی پاک می‌شوند؛ معاملات طلا و هشدارها دست‌نخورده باقی می‌مانند.") },
        confirmButton = { TextButton(onClick = { viewModel.clearNobitexPractice(); confirmNobitexClear = false }) { Text("حذف تمرین‌ها") } },
        dismissButton = { TextButton(onClick = { confirmNobitexClear = false }) { Text("انصراف") } })
}

@Composable
private fun PaperEvidencePanel(recorded: List<PaperTrade>, other: List<PaperTrade>,
                               spread: Double, commission: Double) {
    val closed = recorded.count { !it.isOpen && it.pnlUsd != null }
    val cost = ResearchEvidence.paperCostWhatIf(recorded, spread, commission)
    SectionCard("تفکیک شواهد عملکرد کاغذی", "سوابق همین نصب؛ بک‌تست فنی و تمرین رمزارز در این آمار نیستند") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("بستهٔ ۹/۹ با شاهد", "$closed", modifier = Modifier.weight(1f))
            StatTile("دستی/بدون شاهد", "${other.count { !it.isOpen && it.pnlUsd != null }}",
                modifier = Modifier.weight(1f))
            StatTile("۹/۹ باز", "${recorded.count { it.isOpen }}", modifier = Modifier.weight(1f))
        }
        Text(if (closed == 0) "بدون معاملهٔ کاغذی بسته با شواهد کامل، هیچ نرخ برد یا سود ۹/۹ قابل گزارش نیست. تیتر RSS جای AI را نمی‌گیرد."
            else if (closed < ResearchEvidence.CAUTION_MIN_CLOSED)
                "فقط $closed نتیجهٔ بسته با شواهد ثبت‌شده: نمونهٔ کم. آستانهٔ ۳۰ صرفاً هشدار احتیاطی است، نه معناداری آماری یا تضمین سود."
            else "شمار معامله بیشتر است، اما نتایج paper با قیمت مشاهده‌شده، بدون اجرای بروکر و لغزش‌اند؛ هنوز سود واقعی را ثابت نمی‌کنند.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold,
            modifier = Modifier.padding(top = 8.dp))
        if (cost != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatTile("خالص خام", "${formatPrice(cost.rawPnlUsd)}$", modifier = Modifier.weight(1f))
                StatTile("پس از فرض هزینه", "${formatPrice(cost.afterAssumedCostUsd)}$", modifier = Modifier.weight(1f))
                StatTile("هزینهٔ ×۲", "${formatPrice(cost.afterDoubleCostUsd)}$", modifier = Modifier.weight(1f))
            }
            Text("کسر فرضی از همان P/L ژورنال: هر رفت‌وبرگشت اسپرد $spread دلار/انس + ۲ × کمیسیون $commission دلار/انس؛ بر اساس مقدار ثبت‌شدهٔ هر معامله. جمع فرض هزینه ${formatPrice(cost.estimatedCostUsd)}$ است؛ هیچ نتیجهٔ ذخیره‌شده‌ای ویرایش نمی‌شود. تنظیم هزینهٔ صفر آزمون حساسیت نیست؛ لغزش/هزینهٔ واقعی نامعلوم‌اند.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp))
        }
        Text("طبقه‌بندی ۹/۹ فقط از شواهدِ زمان ورودِ ذخیره‌شده خوانده می‌شود؛ خبر تاریخی بازاعتبارسنجی نمی‌شود. تغییر موجودی اولیه و محدودیت ۵۰۰ معاملهٔ اخیر، برداشت از افت سرمایه را تغییر می‌دهد.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp))
    }
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
                Text("خبر ${ai.model} · ${formatDateTime(ai.checkedAt)} · ${ai.evidence.joinToString { it.source }}" +
                    " · بررسی تقویم ${formatDateTime(ai.calendarCheckedAt)}",
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
            IctDisclosure(trade.id, trade.priceAction)
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
        Text("${String.format("%.6f", trade.quantityBtc)} ${trade.symbol.removeSuffix(trade.quoteUnit)} · ورود ask ${formatPrice(trade.entryAsk)} ${trade.quoteUnit} · SL ${formatPrice(trade.stopLoss)} · TP ${formatPrice(trade.takeProfit)}",
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
        Text("کندل ${formatDateTime(item.signalBarTime)} · MTF ${item.mtf.bias} · مدل ${item.newsEvidence.model}" +
            " · تقویم ${formatDateTime(item.newsEvidence.calendarCheckedAt)}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        ConditionDisclosure(item.key, item.conditions)
        IctDisclosure(item.key, item.priceAction)
        item.newsEvidence.evidence.forEach { news ->
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(news.url) } }) {
                Text("شاهد خبر: ${news.source}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun IctDisclosure(key: String, record: IctPriceActionRecord?) {
    if (record == null) {
        Text("شواهد ICT ثبت نشده؛ رکورد دستی/قدیمی ادعای تأیید رنج ندارد.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        return
    }
    var expanded by remember("ict-$key") { mutableStateOf(false) }
    Text("شواهد رنج/ICT هنگام ثبت · ${record.action} · S ${formatPrice(record.support)} / R ${formatPrice(record.resistance)} · ${String.format("%.2f", record.rewardRisk)}R",
        style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
    OutlinedButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "بستن شواهد ICT" else "نمایش نقدینگی، FVG و زمان ICT",
            style = MaterialTheme.typography.labelSmall)
    }
    if (expanded) {
        Text("${record.model} · ${record.feedProvider} · کندل ${record.interval.label} ${formatDateTime(record.barTime)} · ارزیابی ${formatDateTime(record.checkedAt)}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Text("جلسه ${record.nySession} · ${record.nyDate} ${record.nyTime} به وقت America/New_York؛ زمان‌های بعدی به وقت گوشی",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Text("حمایت ${formatPrice(record.support)} (${record.supportTouches} تماس) / مقاومت ${formatPrice(record.resistance)} (${record.resistanceTouches} تماس) · تأیید ${formatDateTime(record.levelsConfirmedAt)} · ATR ${formatPrice(record.atr)}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Text("جاروب/بازپس‌گیری ${formatDateTime(record.sweepAt)} → MSS ${formatDateTime(record.mssAt)} → FVG ${formatPrice(record.fvgLow)}–${formatPrice(record.fvgHigh)} (${formatDateTime(record.fvgAt)}) → بازآزمایی ${formatDateTime(record.retestAt)}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Text("اردربلاک صرفاً نامزدِ OHLC: ${if (record.orderBlockLow == null) "یافت نشد" else "${formatPrice(record.orderBlockLow)}–${formatPrice(record.orderBlockHigh)}"}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Text("قیمت ${formatPrice(record.quote)} · SL ${formatPrice(record.stop)} (حد بیرون جاروب ${formatPrice(record.stopBoundary)}) · TP ${formatPrice(record.target)} (حد پیش از سطح مقابل ${formatPrice(record.opposingLevel)})",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Text("تقریب آموزشی روی کندل بسته؛ سفارش نهادی/سود آینده را تأیید نمی‌کند. مدل قدیمی پس از ثبت دوباره‌نویسی نمی‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
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
    val assessment = ResearchEvidence.stored(report)
    val stress = report.costStressOutOfSample
    SectionCard(
        title = "آخرین تست فنیِ خارج نمونه (گزارش گوشی)",
        subtitle = "${report.interval} · ${report.bars} کندل · ${formatDateTime(report.generatedAt)}",
    ) {
        Text(assessment.title, style = MaterialTheme.typography.bodySmall,
            color = if (assessment.grade == EvidenceGrade.UNFAVORABLE) AurumColors.Red else AurumColors.Gold)
        Text(assessment.detail, style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 5.dp))
        if (assessment.grade != EvidenceGrade.NO_DATA && stress != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("بستهٔ خارج نمونه", "${report.outOfSample.trades.size}", modifier = Modifier.weight(1f))
                StatTile("خالص فرضی", "${formatPrice(report.outOfSample.netPnl)}$", modifier = Modifier.weight(1f))
                StatTile("خالص هزینهٔ ×۲", "${formatPrice(stress.netPnl)}$", modifier = Modifier.weight(1f))
            }
            Text("اسپرد ${report.outOfSample.spreadPrice} و کمیسیون ${report.outOfSample.commissionPerOz} دلار/واحد؛ با فرض ×۲: ${stress.spreadPrice} و ${stress.commissionPerOz}. بسته‌شدهٔ ×۲: ${stress.trades.size}. باز در پایان: عادی ${if (report.outOfSample.openAtEnd) 1 else 0}، ×۲ ${if (stress.openAtEnd) 1 else 0}؛ پوزیشن حل‌نشدهٔ گپ: عادی ${report.outOfSample.unresolvedGap}، ×۲ ${stress.unresolvedGap}. فقط پژوهشِ موتور فنی؛ نه معاملات ۹/۹ کاغذی.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
