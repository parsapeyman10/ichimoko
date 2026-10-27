package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.DualTimeframeSymbolReport
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.Smart15mSyncPlanner
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.SymbolPollTier
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.AurumViewModel
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlinx.coroutines.delay

internal data class SelectableStrategyItem(
    val id: String,
    val titleFa: String,
    val badgeFa: String,
    val authorRefFa: String,
    val summaryFa: String,
    val isCustom: Boolean,
)

@Composable
fun BitcoinIchimokuStrategyCard(
    viewModel: AurumViewModel,
    onApplySignalToCalculator: ((entry: Double, sl: Double, tp: Double, action: SignalAction) -> Unit)? = null,
    onOpenChartForSymbol: ((String) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val report by viewModel.btcStrategyReport.collectAsStateWithLifecycle()
    val allReports by viewModel.allSymbolStrategyReports.collectAsStateWithLifecycle()
    val dualReports by viewModel.dualSymbolStrategyReports.collectAsStateWithLifecycle()
    val focusedSymbol by viewModel.focusedStrategySymbol.collectAsStateWithLifecycle()
    val focusedInterval by viewModel.focusedStrategyInterval.collectAsStateWithLifecycle()
    val bandwidthStats by viewModel.bandwidthStats.collectAsStateWithLifecycle()
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningBtcStrategy.collectAsStateWithLifecycle()
    val aiChecking by viewModel.aiChecking.collectAsStateWithLifecycle()

    // 0 = رادار ۱۵ نماد (15m+5m), 1 = چک‌لیست ۱۶ شرطی, 2 = انتخاب استراتژی, 3 = تنظیمات بیس
    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddModelForm by rememberSaveable { mutableStateOf(false) }

    val selectedId = settings.selectedBitcoinStrategyId
    val executionEnabled = settings.autoPaperTrading
    val activeTitle = settings.activeBitcoinStrategyTitleFa()
    val currentConfig = settings.activeBitcoinStrategyConfig()
    val openSymbolSet = remember(trades) {
        trades.filter { it.isOpen }.map { V1Universe.normalize(it.symbol) }.toSet()
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val secLeft15m = Smart15mSyncPlanner.secondsUntilNextBoundary(nowMs, Interval.M15)
    val countdown15m = String.format(Locale.US, "%02d:%02d", secLeft15m / 60L, secLeft15m % 60L)
    val secLeft5m = Smart15mSyncPlanner.secondsUntilNextBoundary(nowMs, Interval.M5)
    val countdown5m = String.format(Locale.US, "%02d:%02d", secLeft5m / 60L, secLeft5m % 60L)

    val allItems = remember(settings.customStrategies) {
        val builtIns = BitcoinStrategyModel.entries.map { m ->
            SelectableStrategyItem(
                id = m.id,
                titleFa = m.titleFa,
                badgeFa = m.badgeFa,
                authorRefFa = m.authorRefFa,
                summaryFa = m.summaryFa,
                isCustom = false,
            )
        }
        val customs = settings.customStrategies.map { c ->
            SelectableStrategyItem(
                id = c.id,
                titleFa = c.titleFa,
                badgeFa = c.badgeFa,
                authorRefFa = BitcoinStrategyModel.fromId(c.baseModelId).badgeFa,
                summaryFa = c.summaryFa,
                isCustom = true,
            )
        }
        builtIns + customs
    }

    SectionCard(
        title = "رادار هم‌زمان 15m و 5m · استراتژی ۱۶ شرطی نقطه‌زنی",
        subtitle = "$activeTitle · کندل 15m: $countdown15m · کندل 5m: $countdown5m · کش: ${bandwidthStats.totalRequestsSaved}",
        trailing = {
            Pill(
                text = if (isScanning) "اسکن..." else "بازسنجی 🔄",
                color = AurumColors.Cyan,
                modifier = Modifier.clickable(enabled = !isScanning) {
                    viewModel.scanAndEvaluateBitcoinStrategyNow(userInitiated = true)
                },
            )
        },
    ) {
        if (isScanning || aiChecking) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = AurumColors.Gold,
            )
        }

        // نوار ۲ تب داخلی رادار + دکمه انتقال به صفحه تنظیمات
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to "📡 رادار 15m + 5m (${allReports.size}/15)",
                1 to "🎯 چک‌لیست ۱۶ شرط ($focusedSymbol · ${focusedInterval.label})",
            ).forEach { (idx, title) ->
                Pill(
                    text = title,
                    color = if (subTab == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { subTab = idx },
                )
            }
            if (onOpenSettings != null) {
                Pill(
                    text = "⚙️ تنظیمات استراتژی‌ها ↗",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { onOpenSettings() },
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (subTab) {
            0 -> All15Symbols15mRadarPage(
                focusedSymbol = focusedSymbol,
                allReports = allReports,
                dualReports = dualReports,
                liveTicks = liveTicks,
                openSymbolSet = openSymbolSet,
                now = nowMs,
                onSelectSymbolForChecklist = { sym, interval ->
                    viewModel.selectFocusedStrategySymbol(sym, intervalOverride = interval)
                    subTab = 1
                },
                onOpenChart = onOpenChartForSymbol,
            )

            else -> Live12ConditionChecklistPage(
                report = dualReports[focusedSymbol]?.reportFor(focusedInterval, selectedId) ?: report,
                focusedSymbol = focusedSymbol,
                focusedInterval = focusedInterval,
                selectedStrategyId = selectedId,
                allItems = allItems,
                dualReport = dualReports[focusedSymbol],
                aiChecking = aiChecking,
                avalAiEnabled = settings.avalAiEnabled,
                onSelectSymbol = { sym -> viewModel.selectFocusedStrategySymbol(sym) },
                onSelectInterval = { interval -> viewModel.selectFocusedStrategyInterval(interval) },
                onSelectStrategy = { stratId -> viewModel.activateSingleBitcoinStrategy(stratId, enableExecution = settings.autoPaperTrading) },
                onOpenChart = onOpenChartForSymbol,
                onExecuteSignalTrade = { evalReport ->
                    val entry = evalReport.suggestedEntry ?: return@Live12ConditionChecklistPage
                    val sl = evalReport.suggestedStopLoss ?: return@Live12ConditionChecklistPage
                    val tp = evalReport.primaryTakeProfit ?: return@Live12ConditionChecklistPage
                    onApplySignalToCalculator?.invoke(entry, sl, tp, evalReport.action)
                    val calc = RiskAndCalendarEngine.calculatePosition(
                        symbolInput = evalReport.symbol,
                        accountBalance = settings.accountBalance,
                        riskPercent = settings.riskPercent,
                        entry = entry,
                        stopLoss = sl,
                        takeProfit = tp,
                        preferredAction = evalReport.action,
                        fixedRiskUsdOverride = if (settings.useFixedDollarRisk) settings.fixedRiskUsd else null,
                    )
                    if (calc != null) {
                        viewModel.openAutoTrackedTrade(
                            calc = calc,
                            note = "⚡ [تایم ${evalReport.triggerInterval.label}] · [${evalReport.strategyTitleFa}] · ${evalReport.symbol} · ${evalReport.triggerType.titleFa}",
                            strategyContext = evalReport.toAiStrategyContext(),
                            entryInterval = evalReport.triggerInterval,
                            strategyId = evalReport.strategyId,
                            strategyTitleFa = evalReport.strategyTitleFa,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun All15Symbols15mRadarPage(
    focusedSymbol: String,
    allReports: Map<String, StrategyEvaluationReport>,
    dualReports: Map<String, DualTimeframeSymbolReport>,
    liveTicks: Map<String, PriceTick>,
    openSymbolSet: Set<String>,
    now: Long,
    onSelectSymbolForChecklist: (String, Interval) -> Unit,
    onOpenChart: ((String) -> Unit)?,
) {
    val buy15 = dualReports.values.count { it.report15m.readiness == StrategyReadinessState.SIGNAL_BUY }
    val buy5 = dualReports.values.count { it.report5m.readiness == StrategyReadinessState.SIGNAL_BUY }
    val sell15 = dualReports.values.count { it.report15m.readiness == StrategyReadinessState.SIGNAL_SELL }
    val sell5 = dualReports.values.count { it.report5m.readiness == StrategyReadinessState.SIGNAL_SELL }
    val watch15 = dualReports.values.count { it.report15m.readiness == StrategyReadinessState.WATCH_SETUP }
    val watch5 = dualReports.values.count { it.report5m.readiness == StrategyReadinessState.WATCH_SETUP }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile("خرید (15m / 5m)", "$buy15 / $buy5", AurumColors.Green, Modifier.weight(1f))
            StatTile("فروش (15m / 5m)", "$sell15 / $sell5", AurumColors.Red, Modifier.weight(1f))
            StatTile("آماده (15m / 5m)", "$watch15 / $watch5", AurumColors.Gold, Modifier.weight(1f))
        }

        V1Universe.allSymbols.forEach { sym ->
            val dual = dualReports[sym]
            val rep15m = dual?.report15m ?: allReports[sym]
            val rep5m = dual?.report5m
            val primaryRep = dual?.primaryReport ?: rep15m
            val tick = liveTicks[sym]
            val watchInfo = WatchCatalog.find(sym)
            val isSelected = sym == focusedSymbol
            val marketClosed = MarketHours.closedFor(sym, now)
            val hasOpen = sym in openSymbolSet
            val tier = Smart15mSyncPlanner.classifySymbolTier(
                symbol = sym,
                hasOpenTrade = hasOpen,
                isFocusedSymbol = isSelected,
                readiness = primaryRep?.readiness,
                nowMillis = now,
            )
            val displayPrice = tick?.price ?: primaryRep?.livePrice

            val color15m = when {
                rep15m?.readiness == StrategyReadinessState.SIGNAL_BUY -> AurumColors.Green
                rep15m?.readiness == StrategyReadinessState.SIGNAL_SELL -> AurumColors.Red
                rep15m?.readiness == StrategyReadinessState.WATCH_SETUP -> AurumColors.Gold
                marketClosed -> AurumColors.TextMuted
                else -> AurumColors.Cyan
            }
            val color5m = when {
                rep5m?.readiness == StrategyReadinessState.SIGNAL_BUY -> AurumColors.Green
                rep5m?.readiness == StrategyReadinessState.SIGNAL_SELL -> AurumColors.Red
                rep5m?.readiness == StrategyReadinessState.WATCH_SETUP -> AurumColors.Gold
                marketClosed -> AurumColors.TextMuted
                else -> AurumColors.Cyan
            }
            val borderCol = when {
                isSelected -> AurumColors.Gold
                primaryRep?.readiness == StrategyReadinessState.SIGNAL_BUY -> AurumColors.Green.copy(alpha = 0.5f)
                primaryRep?.readiness == StrategyReadinessState.SIGNAL_SELL -> AurumColors.Red.copy(alpha = 0.5f)
                else -> color15m.copy(alpha = 0.3f)
            }

            val label15m = "15m: ${rep15m?.readinessShortFa() ?: "⏳"}"
            val label5m = "5m: ${rep5m?.readinessShortFa() ?: "⏳"}"

            val pollBadge = when (tier) {
                SymbolPollTier.OPEN_POSITION -> "15s"
                SymbolPollTier.WATCH_OR_FOCUSED -> "60s"
                SymbolPollTier.ACTIVE_15M_RADAR -> "5m/15m"
                SymbolPollTier.MARKET_CLOSED_PAUSED -> "بسته"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) AurumColors.SurfaceAlt else AurumColors.Surface,
                        RoundedCornerShape(10.dp),
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = borderCol,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable {
                        val bestTf = if (rep5m?.isActionable == true && rep15m?.isActionable != true) Interval.M5 else Interval.M15
                        onSelectSymbolForChecklist(sym, bestTf)
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = sym,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) AurumColors.Gold else AurumColors.TextPrimary,
                        )
                        Text(
                            text = watchInfo?.label ?: AssetClass.of(sym).label,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                        Pill(pollBadge, AurumColors.TextMuted)
                    }
                    if (onOpenChart != null) {
                        Pill(
                            text = "چارت ↗",
                            color = AurumColors.Cyan,
                            modifier = Modifier.clickable { onOpenChart(sym) },
                        )
                    }
                }

                if (displayPrice != null && displayPrice > 0.0) {
                    Text(
                        text = "قیمت: ${formatPrice(displayPrice)}" +
                            (primaryRep?.let { " · [${it.triggerInterval.label}] ${it.trendPhase.badgeFa} · امتیاز ${it.coreTrendScore.toInt()}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextPrimary,
                    )
                }

                // نمایش واضح و مجزای وضعیت ورود در هر دو تایم‌فریم 15m و 5m
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        text = label15m,
                        color = color15m,
                        modifier = Modifier.clickable { onSelectSymbolForChecklist(sym, Interval.M15) },
                    )
                    Pill(
                        text = label5m,
                        color = color5m,
                        modifier = Modifier.clickable { onSelectSymbolForChecklist(sym, Interval.M5) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Live12ConditionChecklistPage(
    report: StrategyEvaluationReport?,
    focusedSymbol: String,
    focusedInterval: Interval,
    selectedStrategyId: String,
    allItems: List<SelectableStrategyItem>,
    dualReport: DualTimeframeSymbolReport?,
    aiChecking: Boolean,
    avalAiEnabled: Boolean,
    onSelectSymbol: (String) -> Unit,
    onSelectInterval: (Interval) -> Unit,
    onSelectStrategy: (String) -> Unit,
    onOpenChart: ((String) -> Unit)?,
    onExecuteSignalTrade: (StrategyEvaluationReport) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // نوار افقی انتخاب سریع نماد
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            V1Universe.allSymbols.forEach { sym ->
                Pill(
                    text = sym,
                    color = if (sym == focusedSymbol) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { onSelectSymbol(sym) },
                )
            }
        }

        // نوار افقی مشاهده چک‌لیست هر یک از استراتژی‌های فعال
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            allItems.forEach { item ->
                val repStrat = dualReport?.reportFor(focusedInterval, item.id)
                val shortState = repStrat?.readinessShortFa() ?: ""
                Pill(
                    text = if (shortState.isNotBlank()) "${item.titleFa.substringBefore('(').trim()} ($shortState)" else item.titleFa.substringBefore('(').trim(),
                    color = if (item.id == selectedStrategyId) AurumColors.Cyan else AurumColors.TextMuted,
                    modifier = Modifier.clickable { onSelectStrategy(item.id) },
                )
            }
        }

        // سوییچ سریع بین تایم‌فریم ورود ۱۵ دقیقه (15m) و ۵ دقیقه (5m) با نمایش وضعیت هر دو
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val short15 = dualReport?.reportFor(Interval.M15, selectedStrategyId)?.readinessShortFa() ?: "..."
            val short5 = dualReport?.reportFor(Interval.M5, selectedStrategyId)?.readinessShortFa() ?: "..."
            Pill(
                text = "⏱ تایم ورود 15m ($short15)",
                color = if (focusedInterval == Interval.M15) AurumColors.Gold else AurumColors.TextMuted,
                modifier = Modifier.clickable { onSelectInterval(Interval.M15) },
            )
            Pill(
                text = "⏱ تایم ورود 5m ($short5)",
                color = if (focusedInterval == Interval.M5) AurumColors.Gold else AurumColors.TextMuted,
                modifier = Modifier.clickable { onSelectInterval(Interval.M5) },
            )
        }

        if (report == null) {
            Text(
                text = "در حال محاسبه چک‌لیست ۱۶ شرطی $focusedSymbol (${focusedInterval.label})...",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            return@Column
        }

        val statusColor = when (report.readiness) {
            StrategyReadinessState.SIGNAL_BUY -> AurumColors.Green
            StrategyReadinessState.SIGNAL_SELL -> AurumColors.Red
            StrategyReadinessState.WATCH_SETUP -> AurumColors.Gold
            StrategyReadinessState.BLOCKED -> AurumColors.Red
            StrategyReadinessState.INSUFFICIENT_DATA -> AurumColors.TextMuted
        }

        val passed = report.conditions.count { it.passed }
        val total = report.conditions.size

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(text = "${report.symbol} · [${report.entryTimeframeBadgeFa}] · ${report.readiness.labelFa}", color = statusColor)
                Pill(text = "$passed/$total شرط · ${String.format(Locale.US, "%.0f%%", report.confidence)}", color = AurumColors.Gold)
            }
            if (onOpenChart != null) {
                Pill(
                    text = "چارت ${report.symbol} ↗",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { onOpenChart(report.symbol) },
                )
            }
        }

        // سطوح ورود، حد ضرر کیجون و اهداف موجی هوسودا
        if (report.suggestedEntry != null && report.suggestedStopLoss != null && report.primaryTakeProfit != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("🎯 Sniper Entry: ${formatPrice(report.suggestedEntry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text("SL ساختاری: ${formatPrice(report.suggestedStopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                    Text("R:R = 1:${report.riskReward ?: 0.0}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
                }
                if (report.sniperZoneLow != null && report.sniperZoneHigh != null) {
                    Text(
                        text = "محدوده نقطه‌زنی: [${formatPrice(report.sniperZoneLow)} .. ${formatPrice(report.sniperZoneHigh)}] · دقت ورود: ${report.sniperEntryScore.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        fontWeight = FontWeight.Bold,
                    )
                }
                report.waveProjection?.let { w ->
                    Text(
                        text = "TP1(V): ${formatPrice(w.targetV)} · TP2(N): ${formatPrice(w.targetN)} · TP3(E): ${formatPrice(w.targetE)} · NT: ${formatPrice(w.targetNT)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Green,
                    )
                    Text(
                        text = "چرخه زمانی هوسودا: کندل ${w.barsSinceWaveStart} (عدد پایه: ${w.nearestTimeCycleNumber}${if (w.isNearTimeCycleChangeDay) " ⚠️ روز چرخش" else ""}) · S-Point: ${if (w.sPointHeld) "معتبر" else "خیر"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }

                if (report.isActionable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { onExecuteSignalTrade(report) },
                        enabled = !aiChecking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (report.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (avalAiEnabled) {
                                "🤖 ثبت معامله ${if (report.action == SignalAction.BUY) "خرید" else "فروش"} ${report.symbol} (AvalAI)"
                            } else {
                                "⚡ ثبت فوری معامله ${if (report.action == SignalAction.BUY) "خرید" else "فروش"} ${report.symbol}"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // چک‌لیست ۱۲ شرطی بدون توضیحات اضافه
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            report.conditions.forEach { cond ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (cond.passed) "✅" else "❌",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cond.titleFa,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (cond.passed) AurumColors.Green else AurumColors.TextPrimary,
                        )
                        Text(
                            text = cond.detailFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExclusiveStrategySelectorPage(
    allItems: List<SelectableStrategyItem>,
    selectedId: String,
    runAllSimultaneously: Boolean,
    enabledStrategyIds: Set<String>,
    executionEnabled: Boolean,
    showAddModelForm: Boolean,
    onToggleAddForm: () -> Unit,
    onToggleRunAll: (Boolean) -> Unit,
    onToggleStrategyInMulti: (String, Boolean) -> Unit,
    onSelectAndActivate: (String) -> Unit,
    onToggleExecution: (Boolean) -> Unit,
    onAddModel: (titleFa: String, summaryFa: String, baseModel: BitcoinStrategyModel) -> Unit,
    onDeleteCustom: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // سوییچ اصلی اجرای هم‌زمان همه استراتژی‌ها و تفکیک در ژورنال
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .border(1.dp, if (runAllSimultaneously) AurumColors.Green else AurumColors.Gold, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "⚡ فعال‌سازی هم‌زمان همه استراتژی‌ها و دسته‌بندی در ژورنال",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (runAllSimultaneously) AurumColors.Green else AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "تمام استراتژی‌ها روی تایم 15m و 5m هم‌زمان رصد می‌شوند و هر معامله با نام استراتژی در ژورنال ثبت می‌گردد",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
            Switch(
                checked = runAllSimultaneously,
                onCheckedChange = onToggleRunAll,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (runAllSimultaneously) "همه استراتژی‌های تیک‌خورده هم‌زمان فعال‌اند" else "فقط ۱ استراتژی همزمان فعال است",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )
            Pill(
                text = if (showAddModelForm) "بستن فرم ✕" else "➕ مدل جدید",
                color = AurumColors.Gold,
                modifier = Modifier.clickable { onToggleAddForm() },
            )
        }

        if (showAddModelForm) {
            AddCustomStrategyModelPanel(onAdd = onAddModel)
        }

        allItems.forEach { item ->
            val isSelected = item.id == selectedId
            val isEnabledInMulti = if (runAllSimultaneously) (item.id in enabledStrategyIds || enabledStrategyIds.isEmpty()) else isSelected
            val isRunning = isEnabledInMulti && executionEnabled
            val borderColor = when {
                isRunning -> AurumColors.Green
                isEnabledInMulti -> AurumColors.Gold
                else -> AurumColors.TextMuted.copy(alpha = 0.25f)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isEnabledInMulti) AurumColors.SurfaceAlt else AurumColors.Surface,
                        RoundedCornerShape(10.dp),
                    )
                    .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                    .clickable {
                        if (runAllSimultaneously) {
                            onToggleStrategyInMulti(item.id, !isEnabledInMulti)
                        } else {
                            onSelectAndActivate(item.id)
                        }
                    }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isRunning -> AurumColors.Green
                                        isEnabledInMulti -> AurumColors.Gold
                                        else -> Color.Transparent
                                    },
                                )
                                .border(2.dp, if (isEnabledInMulti) AurumColors.TextPrimary else AurumColors.TextMuted, CircleShape),
                        )
                        Column {
                            Text(
                                text = item.titleFa,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabledInMulti) AurumColors.Gold else AurumColors.TextPrimary,
                            )
                            Text(
                                text = "${item.badgeFa} · ${item.authorRefFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Cyan,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.isCustom) {
                            Pill(
                                text = "حذف",
                                color = AurumColors.Red,
                                modifier = Modifier.clickable { onDeleteCustom(item.id) },
                            )
                        }
                        Pill(
                            text = when {
                                isRunning -> "🟢 فعال هم‌زمان"
                                isEnabledInMulti -> "🟢 آماده رصد"
                                else -> "غیرفعال"
                            },
                            color = when {
                                isRunning -> AurumColors.Green
                                isEnabledInMulti -> AurumColors.Gold
                                else -> AurumColors.TextMuted
                            },
                            modifier = Modifier.clickable {
                                if (runAllSimultaneously) {
                                    onToggleStrategyInMulti(item.id, !isEnabledInMulti)
                                } else {
                                    if (isRunning) onToggleExecution(false) else onSelectAndActivate(item.id)
                                }
                            },
                        )
                    }
                }
                Text(
                    text = item.summaryFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun AddCustomStrategyModelPanel(
    onAdd: (titleFa: String, summaryFa: String, baseModel: BitcoinStrategyModel) -> Unit,
) {
    var titleInput by rememberSaveable { mutableStateOf("") }
    var summaryInput by rememberSaveable { mutableStateOf("") }
    var selectedBase by rememberSaveable { mutableStateOf(BitcoinStrategyModel.BASE_ICHIMOKU.id) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
            .border(1.dp, AurumColors.Gold.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = titleInput,
            onValueChange = { titleInput = it },
            label = { Text("نام استراتژی") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = summaryInput,
            onValueChange = { summaryInput = it },
            label = { Text("توضیح کوتاه") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BitcoinStrategyModel.entries.forEach { m ->
                Pill(
                    text = m.badgeFa,
                    color = if (selectedBase == m.id) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { selectedBase = m.id },
                )
            }
        }
        Button(
            onClick = {
                onAdd(titleInput, summaryInput, BitcoinStrategyModel.fromId(selectedBase))
                titleInput = ""
                summaryInput = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = AurumColors.Gold, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("افزودن و فعال‌سازی", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun BaseStrategyRefinementPanel(
    config: BaseStrategyConfig,
    onSaveConfig: (BaseStrategyConfig) -> Unit,
) {
    var tfTriplet by rememberSaveable(config) {
        mutableStateOf("${config.macroIntervalLabel}/${config.strategyIntervalLabel}/${config.triggerIntervalLabel}")
    }
    var minRr by rememberSaveable(config) { mutableStateOf(config.minRiskReward) }
    var maxKijunExt by rememberSaveable(config) { mutableStateOf(config.maxKijunExtensionAtr) }
    var requireHtf by rememberSaveable(config) { mutableStateOf(config.requireHigherTfAlignment) }
    var requireChikou by rememberSaveable(config) { mutableStateOf(config.requireChikouOpenSpace) }
    var requireFutureKumo by rememberSaveable(config) { mutableStateOf(config.requireFutureKumoSlope) }
    var enablePullback by rememberSaveable(config) { mutableStateOf(config.enablePullbackTrigger) }
    var enableBreakout by rememberSaveable(config) { mutableStateOf(config.enableBreakoutTrigger) }
    var enablePreserve by rememberSaveable(config) { mutableStateOf(config.enablePreserveMode) }

    val tfPresets = listOf(
        "4H/1H/15m" to Triple("4H", "1H", "15m"),
        "1D/4H/1H" to Triple("1D", "4H", "1H"),
        "1H/15m/5m" to Triple("1H", "15m", "5m"),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("تایم‌فریم‌ها:", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tfPresets.forEach { (label, _) ->
                    Pill(
                        text = label,
                        color = if (tfTriplet == label) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { tfTriplet = label },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("حداقل R:R:", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.2, 1.5, 2.0, 2.5).forEach { v ->
                    Pill(
                        text = "1:$v",
                        color = if (minRr == v) AurumColors.Green else AurumColors.TextMuted,
                        modifier = Modifier.clickable { minRr = v },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("حداکثر فاصله کیجون:", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.4, 1.8, 2.2, 2.8).forEach { v ->
                    Pill(
                        text = "${v}x ATR",
                        color = if (maxKijunExt == v) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable { maxKijunExt = v },
                    )
                }
            }
        }

        RuleToggleRow("هم‌جهتی تایم‌فریم کلان (4H)", requireHtf) { requireHtf = it }
        RuleToggleRow("فضای باز سه‌بعدی چیکو (Chikou 3D)", requireChikou) { requireChikou = it }
        RuleToggleRow("شیب و ضخامت ابر آینده (Future Kumo)", requireFutureKumo) { requireFutureKumo = it }
        RuleToggleRow("ماشه ۱: پولبک کیجون/تنکان/S-Point", enablePullback) { enablePullback = it }
        RuleToggleRow("ماشه ۲: شکست کومو و سقف چیکو", enableBreakout) { enableBreakout = it }
        RuleToggleRow("سیو سود خودکار (Preserve Mode)", enablePreserve) { enablePreserve = it }

        Button(
            onClick = {
                val chosenTfs = tfPresets.firstOrNull { it.first == tfTriplet }?.second
                    ?: Triple(config.macroIntervalLabel, config.strategyIntervalLabel, config.triggerIntervalLabel)
                onSaveConfig(
                    config.copy(
                        macroIntervalLabel = chosenTfs.first,
                        strategyIntervalLabel = chosenTfs.second,
                        triggerIntervalLabel = chosenTfs.third,
                        minRiskReward = minRr,
                        maxKijunExtensionAtr = maxKijunExt,
                        requireHigherTfAlignment = requireHtf,
                        requireChikouOpenSpace = requireChikou,
                        requireFutureKumoSlope = requireFutureKumo,
                        enablePullbackTrigger = enablePullback,
                        enableBreakoutTrigger = enableBreakout,
                        enablePreserveMode = enablePreserve,
                    ),
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = AurumColors.Cyan, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("ذخیره تنظیمات استراتژی بیس", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RuleToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
