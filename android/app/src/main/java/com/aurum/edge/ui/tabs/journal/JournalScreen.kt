package com.aurum.edge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.PaperConditionRecord
import com.aurum.edge.core.IctPriceActionRecord
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperAiReview
import com.aurum.edge.core.FeedLiveness
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TradeReplay
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.MarketState
import com.aurum.edge.engine.EvidenceGrade
import com.aurum.edge.engine.PerformanceMetrics
import com.aurum.edge.engine.ResearchEvidence
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val reportError by viewModel.reportError.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadError by viewModel.journalError.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmOpportunityClear by remember { mutableStateOf(false) }
    var showCombined by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<AssetClass?>(null) }
    var pendingPdf by remember { mutableStateOf<Pair<String, List<PaperTrade>>?>(null) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val request = pendingPdf
        pendingPdf = null
        if (uri != null && request != null) viewModel.exportJournalPdf(uri, request.first, request.second)
    }
    val now = System.currentTimeMillis()
    val livePrice = market.lastPrice?.takeIf {
        it.isFinite() && it > 0 && !market.showingCachedData &&
            market.feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
            FeedLiveness.hasRecentReceipt(market.feed, now) &&
            market.candles.lastOrNull()?.time?.let { at ->
                now - at in 0L..minOf(180_000L, market.interval.millis * 2)
            } == true
    }

    val filteredTrades = remember(trades, selectedCategory) {
        if (selectedCategory == null) trades else trades.filter { it.assetClass == selectedCategory }
    }
    val filteredClosed = remember(filteredTrades) { filteredTrades.filter { !it.isOpen } }
    val filteredOpen = remember(filteredTrades) { filteredTrades.filter { it.isOpen } }

    val categoryWins = filteredClosed.count { (it.pnlUsd ?: 0.0) > 0.0 }
    val categoryLosses = filteredClosed.count { (it.pnlUsd ?: 0.0) < 0.0 }
    val categoryNetPnl = filteredClosed.sumOf { it.pnlUsd ?: 0.0 }
    val categoryWinRate = if (filteredClosed.isNotEmpty()) (categoryWins.toDouble() / filteredClosed.size) * 100.0 else null
    val totalGains = filteredClosed.filter { (it.pnlUsd ?: 0.0) > 0 }.sumOf { it.pnlUsd ?: 0.0 }
    val totalLosses = kotlin.math.abs(filteredClosed.filter { (it.pnlUsd ?: 0.0) < 0 }.sumOf { it.pnlUsd ?: 0.0 })
    val categoryProfitFactor = if (totalLosses > 0.0) totalGains / totalLosses else if (totalGains > 0.0) totalGains else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {

        // ── ۱. تفکیک و فیلتر دسته‌بندی‌های ژورنال (طلا/کالا، فارکس، رمزارز، سهام) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("همه بازارها (${trades.size})", style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AurumColors.Gold.copy(alpha = 0.2f),
                    selectedLabelColor = AurumColors.Gold,
                    labelColor = AurumColors.TextSecondary,
                ),
            )
            listOf(
                AssetClass.COMMODITY to "طلا و کالا",
                AssetClass.FOREX to "فارکس",
                AssetClass.CRYPTO to "رمزارزها",
                AssetClass.STOCK to "سهام بین‌المللی",
            ).forEach { (cls, label) ->
                val countInClass = trades.count { it.assetClass == cls }
                FilterChip(
                    selected = selectedCategory == cls,
                    onClick = { selectedCategory = cls },
                    label = { Text("$label ($countInClass)", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Cyan.copy(alpha = 0.2f),
                        selectedLabelColor = AurumColors.Cyan,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        // ── ۲. آمار و خلاصه عملکرد ژورنال دستهٔ انتخاب‌شده ───────────────
        SectionCard(
            title = "ژورنال معاملات کاغذی · ${selectedCategory?.label ?: "همهٔ بازارها"}",
            subtitle = "آمار نتایج واقعی بر اساس تسویهٔ قیمت‌های بازار در دستهٔ انتخاب‌شده",
        ) {
            loadError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = AurumColors.Red, modifier = Modifier.padding(bottom = 8.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val balanceStr = String.format(java.util.Locale.US, "$%.2f", settings.accountBalance).let {
                    if (it.endsWith(".00")) it.substringBefore(".00") else it
                }
                StatTile("موجودی حساب", balanceStr, AurumColors.Gold, Modifier.weight(1f))
                StatTile("بسته‌شده", "${filteredClosed.size}", AurumColors.TextPrimary, Modifier.weight(1f))
                StatTile("باز", "${filteredOpen.size}", AurumColors.TextPrimary, Modifier.weight(1f))
                StatTile("برد/باخت", "$categoryWins/$categoryLosses", AurumColors.Green, Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                StatTile("نرخ برد", categoryWinRate?.let { "${String.format("%.1f", it)}%" } ?: "—", AurumColors.Green, Modifier.weight(1f))
                StatTile("فاکتور سود", categoryProfitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
                StatTile("خالص سود/زیان", "${formatPrice(categoryNetPnl)}$", if (categoryNetPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            }
            if (filteredClosed.isEmpty() && loadError == null) {
                Text(
                    "هنوز معامله بسته‌شده‌ای در این دسته ثبت نشده است. آمار فقط از نتایج واقعی بازار محاسبه می‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ── ۳. تقویم درآمد و سود/زیان روزانه و ماهانه (PnL Calendar) ───────────
        PnlIncomeCalendarSection(
            closedTrades = filteredClosed,
            categoryLabel = selectedCategory?.label ?: "همهٔ بازارها",
        )

        val filteredOpportunities = remember(opportunities, selectedCategory) {
            if (selectedCategory == null) opportunities else opportunities.filter { AssetClass.of(it.symbol) == selectedCategory }
        }

        if (filteredOpportunities.isNotEmpty() || opportunityError != null) {
            SectionCard(
                title = "فرصت‌های آموزشی بررسی‌شده · ${selectedCategory?.label ?: "همهٔ بازارها"}",
                subtitle = "کاندیداهای اسکن‌شده در دستهٔ انتخاب‌شده (خودِ کاندیدا تا زمان تایید ورود معامله نیست)",
            ) {
                opportunityError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.Red) }
                filteredOpportunities.take(30).forEach { item ->
                    OpportunityRow(item, trades.any { it.id == item.paperTradeId })
                }
                if (filteredOpportunities.size > 30) Text("۳۰ مورد اخیر از ${filteredOpportunities.size} کاندیدای ذخیره‌شده",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                if (filteredOpportunities.isNotEmpty()) OutlinedButton(onClick = { confirmOpportunityClear = true }) {
                    Text("پاک کردن تاریخچهٔ کاندیداها (نه معاملات)")
                }
            }
        }

        val livePrices by viewModel.livePrices.collectAsStateWithLifecycle()
        if (filteredOpen.isNotEmpty()) {
            SectionCard("پوزیشن‌های باز · ${selectedCategory?.label ?: "همهٔ بازارها"}", "ارزش‌گذاری با آخرین قیمت واقعی دریافتی") {
                filteredOpen.forEach { trade ->
                    val currentPrice = livePrices[trade.symbol] ?: (if (trade.symbol == market.symbol) livePrice else null)
                    val unrealized = currentPrice?.let { price ->
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
                                "${formatDateTime(trade.openedAt)} · ${if (trade.autoOpened) "خودکار کاغذی" else if (trade.note.startsWith("ورود دستی")) "دستی" else "کاغذی"} · ${trade.id.take(8)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextMuted,
                            )
                            trade.note.takeIf { it.isNotBlank() }?.let { note ->
                                val isAi = note.contains("هوش مصنوعی") && !note.contains("بدون هوش مصنوعی")
                                Text(
                                    text = if (isAi) "✦ $note" else "ℹ $note",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAi) AurumColors.Gold else AurumColors.TextSecondary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            EntryConditionsLine(trade.entryConditions)
                            trade.newsEvidence?.let { verdict ->
                                Text("★ رویداد/خبر همراه: ${verdict.model} · ${verdict.direction} · ${verdict.evidence.joinToString { it.source }}" +
                                    " · تقویم ${formatDateTime(verdict.calendarCheckedAt)}",
                                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                            }
                            AiReviewDisclosure(trade.aiReview)
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
                                enabled = currentPrice != null,
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text("بستن", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    TradeChartDisclosure(viewModel, trade)
                }
            }
        }

        if (filteredClosed.isNotEmpty()) {
            SectionCard("معاملات بسته‌شده · ${selectedCategory?.label ?: "همهٔ بازارها"}", "خروج فرضی بر اساس قیمت دریافتی؛ نه اجرای بروکر/هزینهٔ واقعی") {
                filteredClosed.forEach { trade: PaperTrade ->
                    TradeRow(trade)
                    TradeChartDisclosure(viewModel, trade)
                }
            }
            Button(
                onClick = { confirmClear = true },
                colors = ButtonDefaults.buttonColors(containerColor = AurumColors.SurfaceAlt, contentColor = AurumColors.TextSecondary),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("پاک کردن ژورنال") }
            OutlinedButton(
                onClick = {
                    val catName = selectedCategory?.name?.lowercase() ?: "all"
                    pendingPdf = "Aurum Edge - Paper Journal ($catName)" to filteredTrades
                    savePdf.launch("aurum_${catName}_journal_${System.currentTimeMillis()}.pdf")
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("خروجی PDF ژورنال") }
        }

        if (loadError == null) {
            val recorded = trades.filter(ResearchEvidence::hasRecordedNineWay)
            val other = trades.filterNot(ResearchEvidence::hasRecordedNineWay)
            PaperEvidencePanel(recorded, other, settings.spreadPrice, settings.commissionPerOz)
            if (recorded.any { !it.isOpen && it.pnlUsd != null }) PerformancePanel(
                PerformanceMetrics.fromPaper(recorded, settings.accountBalance),
                "فقط معاملات کاغذی با ۹ شاهد ثبت‌شده؛ P/L خام بدون کارمزد/لغزش بروکر · فرض موجودی اولیه ${formatPrice(settings.accountBalance)}$")
            if (other.any { !it.isOpen && it.pnlUsd != null }) PerformancePanel(
                PerformanceMetrics.fromPaper(other, settings.accountBalance),
                "معاملات دستی/قدیمی/فاقد شواهد کامل · بدون ادعای عملکرد گیت خبر/فنی")
            if (trades.any { !it.isOpen && it.pnlUsd != null }) {
                OutlinedButton(onClick = { showCombined = !showCombined },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(if (showCombined) "بستن آمار کلِ مخلوط" else "نمایش آمار کل ژورنال (مخلوط)")
                }
                if (showCombined) PerformancePanel(PerformanceMetrics.fromPaper(trades, settings.accountBalance),
                    "کل ژورنال: دستی + سیگنال فنی مخلوط؛ برای اثبات استراتژی معتبر نیست · حداکثر ۵۰۰ معاملهٔ اخیر")
            }
        }
        reportError?.let { SectionCard("گزارش پژوهش قابل خواندن نیست") {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        } }
        if (reportError == null) reports.firstOrNull()?.let { report ->
            StoredReportCard(report)
            if (ResearchEvidence.stored(report).grade != EvidenceGrade.NO_DATA) {
                PerformanceMetrics.fromStoredReport(report.outOfSample)?.let { performance ->
                    PerformancePanel(performance, "فقط تست فنیِ خارج نمونه · ${report.outOfSample.symbol} · ${report.interval}؛ نه گیت خبر")
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
        text = { Text("فقط اعلان‌های آموزشی ذخیره‌شده پاک می‌شوند؛ معاملات ژورنال تغییر نمی‌کنند. اگر کندل هنوز تازه باشد ممکن است دوباره هشدار دریافت کنید.") },
        confirmButton = { TextButton(onClick = { viewModel.clearOpportunityHistory(); confirmOpportunityClear = false }) { Text("حذف کاندیداها") } },
        dismissButton = { TextButton(onClick = { confirmOpportunityClear = false }) { Text("انصراف") } })
}

@Composable
private fun PaperEvidencePanel(recorded: List<PaperTrade>, other: List<PaperTrade>,
                               spread: Double, commission: Double) {
    val closed = recorded.count { !it.isOpen && it.pnlUsd != null }
    val cost = ResearchEvidence.paperCostWhatIf(recorded, spread, commission)
    SectionCard("تفکیک شواهد عملکرد کاغذی", "سوابق همین نصب؛ بک‌تست فنی در این آمار نیست") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("بستهٔ سیگنالی با شاهد", "$closed", modifier = Modifier.weight(1f))
            StatTile("دستی/بدون شاهد", "${other.count { !it.isOpen && it.pnlUsd != null }}",
                modifier = Modifier.weight(1f))
            StatTile("سیگنالی باز", "${recorded.count { it.isOpen }}", modifier = Modifier.weight(1f))
        }
        Text(if (closed == 0) "بدون معاملهٔ کاغذی بسته با شواهد کامل، هیچ نرخ برد یا سود سیگنال قابل گزارش نیست. تیتر RSS جای معیار AI را نمی‌گیرد."
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
        Text("طبقه‌بندی سیگنال فقط از شواهدِ زمان ورودِ ذخیره‌شده خوانده می‌شود؛ خبر تاریخی بازاعتبارسنجی نمی‌شود. تغییر موجودی اولیه و محدودیت ۵۰۰ معاملهٔ اخیر، برداشت از افت سرمایه را تغییر می‌دهد.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EntryConditionsLine(conditions: List<PaperConditionRecord>) {
    val started = conditions.take(8).filter { it.status == "CONFIRMED" }
    if (started.isEmpty()) return
    Text(
        "شروع معامله: " + started.joinToString("، ") { it.name.substringAfter('·').trim() },
        style = MaterialTheme.typography.labelSmall,
        color = AurumColors.Green,
        modifier = Modifier.padding(top = 3.dp),
    )
}

private fun conditionTone(status: String) = when (status) {
    "CONFIRMED" -> AurumColors.Green
    "UNKNOWN" -> AurumColors.Orange
    else -> AurumColors.Red
}

private fun conditionLabel(status: String) = when (status) {
    "CONFIRMED" -> "برقرار"
    "UNKNOWN" -> "احتمالی"
    else -> "دور"
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
            Text("${trade.symbol} · ${if (trade.autoOpened) "خودکار کاغذی" else "کاغذی"} · ${trade.id.take(8)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            Text("باز ${formatDateTime(trade.openedAt)} → بسته ${formatDateTime(trade.closedAt)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            Text(
                "${formatPrice(trade.entry)} → ${formatPrice(trade.exitPrice)}",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextPrimary,
            )
            Text("اهرم ${trade.effectiveLeverage}x · مارجین: $${formatPrice(trade.effectiveMarginUsd)} · کارمزد: $${formatPrice(trade.effectiveCommissionUsd)} · اسپرد: $${formatPrice(trade.effectiveSpreadCostUsd)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            Text("${trade.exitReason ?: "—"} · ${String.format("%.6f", trade.positionOz)} ${trade.unit}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            trade.note.takeIf { it.isNotBlank() }?.let { note ->
                val isAi = note.contains("هوش مصنوعی") && !note.contains("بدون هوش مصنوعی")
                Text(
                    text = if (isAi) "✦ $note" else "ℹ $note",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAi) AurumColors.Gold else AurumColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            EntryConditionsLine(trade.entryConditions)
            trade.newsEvidence?.let { ai ->
                Text("★ رویداد/خبر همراه: ${ai.model} · ${formatDateTime(ai.checkedAt)} · ${ai.evidence.joinToString { it.source }}" +
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
            AiReviewDisclosure(trade.aiReview)
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
        Text("کندل ${formatDateTime(item.signalBarTime)} · MTF ${item.mtf.bias}" +
            (item.newsEvidence?.let { " · مدل ${it.model} · تقویم ${formatDateTime(it.calendarCheckedAt)}" }
                ?: " · خبر نزدیک معتبر برای این کاندیدا ثبت نشد"),
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        ConditionDisclosure(item.key, item.conditions)
        IctDisclosure(item.key, item.priceAction)
        item.newsEvidence?.evidence?.forEach { news ->
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(news.url) } }) {
                Text("شاهد خبر: ${news.source}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * «همین معامله روی چارت»: the stored entry/SL/TP of one journal row drawn over the REAL candles
 * of that period. Bars come from this device's verified cache (or a fresh download with the
 * user's own key); a period the phone never received stays empty and is labelled, never filled in.
 */
@Composable
private fun TradeChartDisclosure(viewModel: AurumViewModel, trade: PaperTrade) {
    val state by viewModel.tradeChart.collectAsStateWithLifecycle()
    val expanded = state.tradeId == trade.id
    OutlinedButton(
        onClick = { viewModel.showTradeChart(trade) },
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            if (expanded) "بستن نمودار این معامله" else "نمودار همین معامله روی کندل واقعی",
            style = MaterialTheme.typography.labelSmall,
        )
    }
    if (!expanded) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        when {
            state.loading -> Text(
                "در حال خواندن کندل‌های ذخیره‌شدهٔ این بازه…",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            )
            state.window.hasChart -> {
                TradeReplayChart(
                    trade = trade,
                    window = state.window,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Text(
                    "${trade.symbol} · ${trade.interval.label} · ورود ${formatDateTime(trade.openedAt)}" +
                        " → ${if (trade.isOpen) "هنوز باز" else "خروج ${formatDateTime(trade.closedAt)}"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    "خط ENTRY/SL/TP/EXIT از همین رکورد ژورنال خوانده شده؛ IN-L/IN-S کندل ورود و OUT کندل خروج ثبت‌شده است." +
                        " موتور امروز دوباره روی گذشته اجرا نمی‌شود و این تصویر سیگنال تازه نیست.",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (state.source.isNotBlank()) Text(
                    "منبع کندل‌ها: ${state.source}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan,
                )
                Text(
                    state.window.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.window.coverage == TradeReplay.Coverage.FULL) AurumColors.TextMuted else AurumColors.Gold,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            else -> Text(
                state.window.detail.ifBlank {
                    "کندلی برای این بازه روی گوشی نیست؛ چیزی ساخته نمی‌شود."
                },
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
            )
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.Red,
                modifier = Modifier.padding(top = 3.dp))
        }
        if (!state.loading && state.window.coverage != TradeReplay.Coverage.FULL) {
            OutlinedButton(
                onClick = { viewModel.downloadTradeChart(trade) },
                enabled = !state.downloading,
                modifier = Modifier.padding(top = 5.dp),
            ) {
                Text(
                    if (state.downloading) "در حال دریافت از ناشر…" else "دریافت کندل‌های این بازه از ناشر",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "دریافت با همان کلید خواندنی Twelve Data و فقط برای نمایش است؛ سقف تاریخچهٔ ناشر ممکن است به این بازه نرسد.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            )
        }
    }
}

@Composable
private fun AiReviewDisclosure(review: PaperAiReview?) {
    if (review == null) {
        Text("نظر AI هنگام بازشدن معامله ثبت نشده؛ اگر می‌خواهی دلیل مدل ذخیره شود، کلید/مدل AI را در تنظیمات وارد کن.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        return
    }
    val color = when (review.verdict) {
        "WORTHY" -> AurumColors.Green
        "RISKY" -> AurumColors.Gold
        "NOT_WORTHY" -> AurumColors.Red
        else -> AurumColors.TextSecondary
    }
    var expanded by remember("ai-review-${review.checkedAt}") { mutableStateOf(false) }
    Text("نظر AI هنگام ورود: ${review.verdictFa()} · ${review.confidence}٪ · ${review.summary}",
        style = MaterialTheme.typography.labelSmall, color = color)
    OutlinedButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "بستن چرایی AI" else "چرایی نظر AI", style = MaterialTheme.typography.labelSmall)
    }
    if (expanded) {
        Text("مدل ${review.model} · ${formatDateTime(review.checkedAt)}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        review.reasons.forEachIndexed { index, reason ->
            Text("دلیل ${index + 1}: $reason", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        }
        review.cautions.forEachIndexed { index, caution ->
            Text("احتیاط ${index + 1}: $caution", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        }
        Text("این نظر فقط تحلیل آموزشی بعد از ثبت معاملهٔ کاغذی است؛ معامله را تأیید/رد یا سفارش واقعی ایجاد نمی‌کند.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
    }
}

private fun PaperAiReview.verdictFa(): String = when (verdict) {
    "WORTHY" -> "شرایط مناسب بوده"
    "RISKY" -> "پرریسک/مرزی بوده"
    "NOT_WORTHY" -> "شرایط کافی نبوده"
    else -> verdict
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
    if (conditions.isEmpty()) return
    var expanded by remember(key) { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "بستن شرط‌ها" else "شرط‌های موتور", style = MaterialTheme.typography.labelSmall)
    }
    if (expanded) conditions.forEachIndexed { index, condition ->
        val tone = conditionTone(condition.status)
        Text(
            "${index + 1}. ${conditionLabel(condition.status)} · ${condition.name}" +
                if (condition.detail.isNotBlank()) " · ${condition.detail}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = tone,
            modifier = Modifier.padding(vertical = 2.dp),
        )
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
            Text("اسپرد ${report.outOfSample.spreadPrice} و کمیسیون ${report.outOfSample.commissionPerOz} دلار/واحد؛ با فرض ×۲: ${stress.spreadPrice} و ${stress.commissionPerOz}. بسته‌شدهٔ ×۲: ${stress.trades.size}. باز در پایان: عادی ${if (report.outOfSample.openAtEnd) 1 else 0}، ×۲ ${if (stress.openAtEnd) 1 else 0}؛ پوزیشن حل‌نشدهٔ گپ: عادی ${report.outOfSample.unresolvedGap}، ×۲ ${stress.unresolvedGap}. فقط پژوهشِ موتور فنی؛ نه معاملات سیگنالی کاغذی.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * Daily and monthly PnL / Income calendar for closed trades.
 * Allows month-by-month navigation, visualizing daily profit/loss with green/red badges,
 * and inspecting individual trades per selected day.
 */
@Composable
private fun PnlIncomeCalendarSection(
    closedTrades: List<PaperTrade>,
    categoryLabel: String,
) {
    var calendarMonthOffset by remember { mutableIntStateOf(0) }
    var selectedDayKey by remember { mutableStateOf<String?>(null) }

    val cal = remember(calendarMonthOffset) {
        Calendar.getInstance().apply {
            add(Calendar.MONTH, calendarMonthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    val currentMonth = cal.get(Calendar.MONTH)
    val currentYear = cal.get(Calendar.YEAR)
    val maxDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 7=Saturday

    val monthLabel = remember(cal) {
        val enFormat = SimpleDateFormat("MMMM yyyy", Locale.US)
        enFormat.format(cal.time)
    }

    // Group trades for this month by day of month
    val monthTrades = remember(closedTrades, currentMonth, currentYear) {
        closedTrades.filter { t ->
            val closedTime = t.closedAt
            if (closedTime == null) {
                false
            } else {
                val tCal = Calendar.getInstance().apply { timeInMillis = closedTime }
                tCal.get(Calendar.MONTH) == currentMonth && tCal.get(Calendar.YEAR) == currentYear
            }
        }
    }

    val dailyPnlMap = remember(monthTrades) {
        val map = mutableMapOf<Int, Double>()
        monthTrades.forEach { t ->
            val day = Calendar.getInstance().apply { timeInMillis = t.closedAt ?: 0L }.get(Calendar.DAY_OF_MONTH)
            map[day] = (map[day] ?: 0.0) + (t.pnlUsd ?: 0.0)
        }
        map
    }

    val dailyTradesMap = remember(monthTrades) {
        val map = mutableMapOf<Int, MutableList<PaperTrade>>()
        monthTrades.forEach { t ->
            val day = Calendar.getInstance().apply { timeInMillis = t.closedAt ?: 0L }.get(Calendar.DAY_OF_MONTH)
            map.getOrPut(day) { mutableListOf() }.add(t)
        }
        map
    }

    val monthNetPnl = monthTrades.sumOf { it.pnlUsd ?: 0.0 }
    val greenDays = dailyPnlMap.values.count { it > 0.0 }
    val redDays = dailyPnlMap.values.count { it < 0.0 }

    SectionCard(
        title = "📅 تقویم درآمد و سود/زیان · $categoryLabel",
        subtitle = "عملکرد روزانه و ماهانه بر اساس تقویم معاملاتی ($monthLabel)",
        trailing = {
            Pill(
                text = (if (monthNetPnl >= 0) "+$" else "-$") + String.format(Locale.US, "%.2f", kotlin.math.abs(monthNetPnl)),
                color = if (monthNetPnl >= 0) AurumColors.Green else AurumColors.Red,
            )
        },
    ) {
        // Month Navigation
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { calendarMonthOffset-- }) {
                Text("◀ ماه قبل", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = monthLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AurumColors.Gold,
            )
            OutlinedButton(onClick = { calendarMonthOffset++ }, enabled = calendarMonthOffset < 0) {
                Text("ماه بعد ▶", style = MaterialTheme.typography.labelSmall)
            }
        }

        // Monthly Stats
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                "سود/زیان ماه",
                (if (monthNetPnl >= 0) "+$" else "-$") + String.format(Locale.US, "%.2f", kotlin.math.abs(monthNetPnl)),
                if (monthNetPnl >= 0) AurumColors.Green else AurumColors.Red,
                Modifier.weight(1f),
            )
            StatTile("معاملات ماه", "${monthTrades.size}", AurumColors.TextPrimary, Modifier.weight(1f))
            StatTile("روزهای سبز/قرمز", "$greenDays / $redDays", if (greenDays >= redDays) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
        }

        // Days of week header
        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            dayNames.forEach { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 7-column Calendar Grid
        val leadingBlanks = (firstDayOfWeek - 1).coerceAtLeast(0)
        val totalCells = leadingBlanks + maxDaysInMonth
        val rows = (totalCells + 6) / 7

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (c in 0 until 7) {
                        val cellIndex = r * 7 + c
                        val dayNumber = cellIndex - leadingBlanks + 1
                        if (dayNumber in 1..maxDaysInMonth) {
                            val pnlForDay = dailyPnlMap[dayNumber]
                            val tradesForDay = dailyTradesMap[dayNumber] ?: emptyList<PaperTrade>()
                            val isPositive = (pnlForDay ?: 0.0) > 0.0
                            val isNegative = (pnlForDay ?: 0.0) < 0.0
                            val dayKey = "$currentYear-$currentMonth-$dayNumber"
                            val isSelected = selectedDayKey == dayKey

                            val cellBg = when {
                                isSelected -> AurumColors.Gold.copy(alpha = 0.25f)
                                isPositive -> AurumColors.Green.copy(alpha = 0.18f)
                                isNegative -> AurumColors.Red.copy(alpha = 0.18f)
                                else -> AurumColors.SurfaceAlt
                            }
                            val borderCol = when {
                                isSelected -> AurumColors.Gold
                                isPositive -> AurumColors.Green.copy(alpha = 0.5f)
                                isNegative -> AurumColors.Red.copy(alpha = 0.5f)
                                else -> AurumColors.Line
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .background(cellBg, RoundedCornerShape(6.dp))
                                    .border(1.dp, borderCol, RoundedCornerShape(6.dp))
                                    .clickable {
                                        selectedDayKey = if (isSelected) null else dayKey
                                    }
                                    .padding(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "$dayNumber",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tradesForDay.isNotEmpty()) AurumColors.TextPrimary else AurumColors.TextMuted,
                                    fontWeight = if (tradesForDay.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (pnlForDay != null) {
                                    Text(
                                        text = (if (pnlForDay >= 0) "+" else "") + String.format(Locale.US, "%.0f$", pnlForDay),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                                        color = if (pnlForDay >= 0) AurumColors.Green else AurumColors.Red,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                } else {
                                    Text("—", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted.copy(alpha = 0.3f))
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f).height(48.dp))
                        }
                    }
                }
            }
        }

        // Selected Day Details
        if (selectedDayKey != null) {
            val parts = selectedDayKey!!.split("-")
            val selDay = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val dayTrades = dailyTradesMap[selDay] ?: emptyList()
            val dayPnl = dailyPnlMap[selDay] ?: 0.0

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "جزئیات روز $selDay $monthLabel (${dayTrades.size} معامله)",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = (if (dayPnl >= 0) "+$" else "-$") + String.format(Locale.US, "%.2f", kotlin.math.abs(dayPnl)),
                        color = if (dayPnl >= 0) AurumColors.Green else AurumColors.Red,
                    )
                }
                if (dayTrades.isEmpty()) {
                    Text(
                        "معامله‌ای در این روز ثبت نشده است.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    dayTrades.forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${t.symbol} · ${t.action.name} (ورود: ${formatPrice(t.entry)} | خروج: ${formatPrice(t.exitPrice)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                            Text(
                                text = "${if ((t.pnlUsd ?: 0.0) >= 0) "+" else ""}${formatPrice(t.pnlUsd)}$",
                                style = MaterialTheme.typography.labelSmall,
                                color = if ((t.pnlUsd ?: 0.0) >= 0) AurumColors.Green else AurumColors.Red,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

