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
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.Smart15mSyncPlanner
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.SymbolPollTier
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.AurumViewModel
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlinx.coroutines.delay

private data class SelectableStrategyItem(
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
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val report by viewModel.btcStrategyReport.collectAsStateWithLifecycle()
    val allReports by viewModel.allSymbolStrategyReports.collectAsStateWithLifecycle()
    val focusedSymbol by viewModel.focusedStrategySymbol.collectAsStateWithLifecycle()
    val bandwidthStats by viewModel.bandwidthStats.collectAsStateWithLifecycle()
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningBtcStrategy.collectAsStateWithLifecycle()
    val aiChecking by viewModel.aiChecking.collectAsStateWithLifecycle()

    // 0 = رادار ۱۵ نماد, 1 = چک‌لیست ۱۲ شرطی, 2 = انتخاب استراتژی, 3 = تنظیمات بیس
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
    val secLeft = Smart15mSyncPlanner.secondsUntilNextBoundary(nowMs, currentConfig.triggerInterval)
    val countdownStr = String.format(Locale.US, "%02d:%02d", secLeft / 60L, secLeft % 60L)

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
        title = "رادار ۱۵ نماد و استراتژی ۱۲ شرطی",
        subtitle = "$activeTitle · کندل ${currentConfig.triggerIntervalLabel} بعدی: $countdownStr · کش: ${bandwidthStats.totalRequestsSaved}",
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

        // نوار ۴ تب داخلی برای صفحه‌بندی تمیز
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to "📡 رادار ۱۵ نماد (${allReports.size}/15)",
                1 to "✅ چک‌لیست ۱۲ شرطی ($focusedSymbol)",
                2 to "🎯 انتخاب استراتژی (${allItems.size})",
                3 to "⚙️ تنظیمات بیس",
            ).forEach { (idx, title) ->
                Pill(
                    text = title,
                    color = if (subTab == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { subTab = idx },
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (subTab) {
            0 -> All15Symbols15mRadarPage(
                focusedSymbol = focusedSymbol,
                allReports = allReports,
                liveTicks = liveTicks,
                openSymbolSet = openSymbolSet,
                now = nowMs,
                onSelectSymbolForChecklist = { sym ->
                    viewModel.selectFocusedStrategySymbol(sym)
                    subTab = 1
                },
                onOpenChart = onOpenChartForSymbol,
            )

            1 -> Live12ConditionChecklistPage(
                report = report,
                focusedSymbol = focusedSymbol,
                aiChecking = aiChecking,
                avalAiEnabled = settings.avalAiEnabled,
                onSelectSymbol = { sym -> viewModel.selectFocusedStrategySymbol(sym) },
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
                    )
                    if (calc != null) {
                        viewModel.openAutoTrackedTrade(
                            calc = calc,
                            note = "⚡ ${evalReport.strategyTitleFa} · ${evalReport.symbol} · ${evalReport.triggerType.titleFa}",
                            strategyContext = evalReport.toAiStrategyContext(),
                        )
                    }
                },
            )

            2 -> ExclusiveStrategySelectorPage(
                allItems = allItems,
                selectedId = selectedId,
                executionEnabled = executionEnabled,
                showAddModelForm = showAddModelForm,
                onToggleAddForm = { showAddModelForm = !showAddModelForm },
                onSelectAndActivate = { id ->
                    viewModel.activateSingleBitcoinStrategy(id, enableExecution = true)
                },
                onToggleExecution = viewModel::setAutoPaperTrading,
                onAddModel = { title, summary, baseModel ->
                    viewModel.addCustomStrategyModel(title, summary, baseModel, activateImmediately = true)
                    showAddModelForm = false
                },
                onDeleteCustom = viewModel::deleteCustomStrategyModel,
            )

            3 -> BaseStrategyRefinementPanel(
                config = currentConfig,
                onSaveConfig = { updated ->
                    viewModel.updateBaseStrategyConfig(updated)
                    subTab = 1
                },
            )
        }
    }
}

@Composable
private fun All15Symbols15mRadarPage(
    focusedSymbol: String,
    allReports: Map<String, StrategyEvaluationReport>,
    liveTicks: Map<String, PriceTick>,
    openSymbolSet: Set<String>,
    now: Long,
    onSelectSymbolForChecklist: (String) -> Unit,
    onOpenChart: ((String) -> Unit)?,
) {
    val buySignals = allReports.values.count { it.readiness == StrategyReadinessState.SIGNAL_BUY }
    val sellSignals = allReports.values.count { it.readiness == StrategyReadinessState.SIGNAL_SELL }
    val watchSetups = allReports.values.count { it.readiness == StrategyReadinessState.WATCH_SETUP }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile("سیگنال خرید", buySignals.toString(), AurumColors.Green, Modifier.weight(1f))
            StatTile("سیگنال فروش", sellSignals.toString(), AurumColors.Red, Modifier.weight(1f))
            StatTile("آماده ماشه", watchSetups.toString(), AurumColors.Gold, Modifier.weight(1f))
        }

        V1Universe.allSymbols.forEach { sym ->
            val rep = allReports[sym]
            val tick = liveTicks[sym]
            val watchInfo = WatchCatalog.find(sym)
            val isSelected = sym == focusedSymbol
            val marketClosed = MarketHours.closedFor(sym, now)
            val hasOpen = sym in openSymbolSet
            val tier = Smart15mSyncPlanner.classifySymbolTier(
                symbol = sym,
                hasOpenTrade = hasOpen,
                isFocusedSymbol = isSelected,
                readiness = rep?.readiness,
                nowMillis = now,
            )
            val passedCount = rep?.conditions?.count { it.passed } ?: 0
            val totalConds = rep?.conditions?.size ?: 12
            val displayPrice = tick?.price ?: rep?.livePrice

            val statusColor = when {
                rep?.readiness == StrategyReadinessState.SIGNAL_BUY -> AurumColors.Green
                rep?.readiness == StrategyReadinessState.SIGNAL_SELL -> AurumColors.Red
                rep?.readiness == StrategyReadinessState.WATCH_SETUP -> AurumColors.Gold
                marketClosed -> AurumColors.TextMuted
                else -> AurumColors.Cyan
            }

            val statusLabel = when {
                rep?.readiness == StrategyReadinessState.SIGNAL_BUY -> "🟢 خرید ($passedCount/$totalConds)"
                rep?.readiness == StrategyReadinessState.SIGNAL_SELL -> "🔴 فروش ($passedCount/$totalConds)"
                rep?.readiness == StrategyReadinessState.WATCH_SETUP -> "🟡 آماده ($passedCount/$totalConds)"
                marketClosed -> "⏸ بسته ($passedCount/$totalConds)"
                rep != null && rep.conditions.isNotEmpty() -> "$passedCount/$totalConds شرط"
                else -> "⏳..."
            }

            val pollBadge = when (tier) {
                SymbolPollTier.OPEN_POSITION -> "15s"
                SymbolPollTier.WATCH_OR_FOCUSED -> "60s"
                SymbolPollTier.ACTIVE_15M_RADAR -> "15m"
                SymbolPollTier.MARKET_CLOSED_PAUSED -> "بسته"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) AurumColors.SurfaceAlt else AurumColors.Surface,
                        RoundedCornerShape(10.dp),
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) AurumColors.Gold else statusColor.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelectSymbolForChecklist(sym) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                    if (displayPrice != null && displayPrice > 0.0) {
                        Text(
                            text = "قیمت: ${formatPrice(displayPrice)}" +
                                (rep?.riskReward?.let { " · R:R 1:$it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(text = statusLabel, color = statusColor)
                    if (onOpenChart != null) {
                        Pill(
                            text = "چارت ↗",
                            color = AurumColors.Cyan,
                            modifier = Modifier.clickable { onOpenChart(sym) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Live12ConditionChecklistPage(
    report: StrategyEvaluationReport?,
    focusedSymbol: String,
    aiChecking: Boolean,
    avalAiEnabled: Boolean,
    onSelectSymbol: (String) -> Unit,
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

        if (report == null) {
            Text(
                text = "در حال محاسبه چک‌لیست ۱۲ شرطی $focusedSymbol...",
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
                Pill(text = "${report.symbol} · ${report.readiness.labelFa}", color = statusColor)
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
                    Text("Entry: ${formatPrice(report.suggestedEntry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text("SL (Kijun): ${formatPrice(report.suggestedStopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                    Text("R:R = 1:${report.riskReward ?: 0.0}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
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
private fun ExclusiveStrategySelectorPage(
    allItems: List<SelectableStrategyItem>,
    selectedId: String,
    executionEnabled: Boolean,
    showAddModelForm: Boolean,
    onToggleAddForm: () -> Unit,
    onSelectAndActivate: (String) -> Unit,
    onToggleExecution: (Boolean) -> Unit,
    onAddModel: (titleFa: String, summaryFa: String, baseModel: BitcoinStrategyModel) -> Unit,
    onDeleteCustom: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "فقط ۱ استراتژی همزمان فعال است",
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
            val isRunning = isSelected && executionEnabled
            val borderColor = when {
                isRunning -> AurumColors.Green
                isSelected -> AurumColors.Gold
                else -> AurumColors.TextMuted.copy(alpha = 0.25f)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) AurumColors.SurfaceAlt else AurumColors.Surface,
                        RoundedCornerShape(10.dp),
                    )
                    .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                    .clickable { onSelectAndActivate(item.id) }
                    .padding(10.dp),
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
                                        isSelected -> AurumColors.Gold
                                        else -> Color.Transparent
                                    },
                                )
                                .border(2.dp, if (isSelected) AurumColors.TextPrimary else AurumColors.TextMuted, CircleShape),
                        )
                        Column {
                            Text(
                                text = item.titleFa,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) AurumColors.Gold else AurumColors.TextPrimary,
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
                                isRunning -> "🟢 فعال"
                                isSelected -> "متوقف"
                                else -> "فعال‌سازی"
                            },
                            color = when {
                                isRunning -> AurumColors.Green
                                isSelected -> AurumColors.Gold
                                else -> AurumColors.TextMuted
                            },
                            modifier = Modifier.clickable {
                                if (isRunning) onToggleExecution(false) else onSelectAndActivate(item.id)
                            },
                        )
                    }
                }
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
private fun BaseStrategyRefinementPanel(
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
