package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.CandleBackfillSummary
import com.aurum.edge.core.DayPnlSummary
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.AiTradeVerdict
import com.aurum.edge.data.AvalAiAdvisor
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.TradingViewPositionBox
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

private val WEEKDAY_HEADERS_FA = listOf("ش", "ی", "د", "س", "چ", "پ", "ج")
private val RISK_PRESETS = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0)
private val RR_PRESETS = listOf(1.5, 2.0, 3.0)

@Composable
fun JournalRiskScreen(
    viewModel: AurumViewModel,
    onChartSymbol: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val lastMarks by viewModel.lastDisplayMarks.collectAsStateWithLifecycle()
    val backfillSummaries by viewModel.backfillSummaries.collectAsStateWithLifecycle()
    val isBackfilling by viewModel.isBackfilling.collectAsStateWithLifecycle()
    val aiChecking by viewModel.aiChecking.collectAsStateWithLifecycle()
    val lastAiVerdict by viewModel.lastAiVerdict.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.activeRiskSymbol.collectAsStateWithLifecycle()
    val now by viewModel.clock.collectAsStateWithLifecycle()

    val openTrades = remember(trades) { trades.filter { it.isOpen } }
    val closedTrades = remember(trades) { trades.filter { !it.isOpen } }

    val riskLimits = remember(settings, trades, now) {
        RiskAndCalendarEngine.evaluateRiskLimits(
            accountBalance = settings.accountBalance,
            initialCapital = settings.initialCapital,
            dayOpeningEquity = viewModel.dayOpeningEquity(now),
            dailyLossLimitPercent = settings.dailyLossLimitPercent,
            maxDrawdownLimitPercent = settings.maxDrawdownLimitPercent,
            trades = trades,
            now = now,
        )
    }

    LaunchedEffect(activeSymbol) {
        viewModel.refreshSymbolLivePrice(activeSymbol)
    }

    var subPage by rememberSaveable { mutableIntStateOf(0) }
    var selectedStrategyFilter by rememberSaveable { mutableStateOf("ALL") }

    val strategyCategories = remember(trades, settings.customStrategies) {
        RiskAndCalendarEngine.categorizeTradesByStrategy(trades, settings.customStrategies)
    }

    val filteredTrades = remember(trades, selectedStrategyFilter) {
        if (selectedStrategyFilter == "ALL") trades else trades.filter { it.strategyId == selectedStrategyFilter }
    }
    val filteredOpenTrades = remember(filteredTrades) { filteredTrades.filter { it.isOpen } }
    val filteredClosedTrades = remember(filteredTrades) { filteredTrades.filter { !it.isOpen } }
    val filteredStats = remember(filteredTrades, stats, selectedStrategyFilter) {
        if (selectedStrategyFilter == "ALL") {
            stats
        } else {
            val closed = filteredClosedTrades.filter { it.pnlUsd != null }
            val wins = closed.filter { (it.pnlUsd ?: 0.0) > 0.0 }
            val losses = closed.filter { (it.pnlUsd ?: 0.0) <= 0.0 }
            val grossWin = wins.sumOf { it.pnlUsd ?: 0.0 }
            val grossLoss = abs(losses.sumOf { it.pnlUsd ?: 0.0 })
            val rMultiples = closed.mapNotNull { it.rMultiple }
            com.aurum.edge.data.JournalStats(
                total = closed.size,
                open = filteredOpenTrades.size,
                wins = wins.size,
                losses = losses.size,
                winRate = if (closed.isEmpty()) null else wins.size * 100.0 / closed.size,
                profitFactor = if (grossLoss <= 0.0) null else grossWin / grossLoss,
                netPnl = closed.sumOf { it.pnlUsd ?: 0.0 },
                avgR = if (rMultiples.isEmpty()) null else rMultiples.average(),
            )
        }
    }

    val totalR = remember(filteredClosedTrades) {
        filteredClosedTrades.sumOf { it.rMultiple ?: 0.0 }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
    ) {
        // ── ۱. هدر مدرن کارنامه کلی ژورنال معاملاتی (Executive Journal Summary) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AurumColors.Surface)
                .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = if (selectedStrategyFilter == "ALL") "کارنامه کل استراتژی‌ها" else "کارنامه استراتژی انتخابی",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    val netSign = if (filteredStats.netPnl >= 0.0) "+" else "-"
                    Text(
                        text = "$netSign$${String.format(Locale.US, "%,.2f", abs(filteredStats.netPnl))}",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (filteredStats.netPnl >= 0.0) AurumColors.Green else AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = String.format(Locale.US, "%s%.2f R", if (totalR >= 0.0) "+" else "", totalR),
                        color = if (totalR >= 0.0) AurumColors.Green else AurumColors.Red,
                    )
                    if (onOpenSettings != null) {
                        Pill(
                            text = "⚙️ تنظیمات",
                            color = AurumColors.Gold,
                            modifier = Modifier.clickable { onOpenSettings() },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatTile(
                    label = "نرخ برد (Win Rate)",
                    value = filteredStats.winRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    valueColor = AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "برد / باخت",
                    value = "${filteredStats.wins}W / ${filteredStats.losses}L",
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "پرافیت فاکتور",
                    value = filteredStats.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    valueColor = if ((filteredStats.profitFactor ?: 0.0) >= 1.2) AurumColors.Green else AurumColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "معاملات باز",
                    value = "${filteredOpenTrades.size}",
                    valueColor = if (filteredOpenTrades.isNotEmpty()) AurumColors.Green else AurumColors.TextMuted,
                    modifier = Modifier.weight(0.85f),
                )
            }
        }

        // ── ۲. نوار تب‌های داخلی صفحه ژورنال ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to "📅 تقویم و دسته‌بندی (${filteredClosedTrades.size})",
                1 to "📜 معاملات باز (${filteredOpenTrades.size})",
                2 to "🧮 ماشین‌حساب حجم",
            ).forEach { (idx, label) ->
                Pill(
                    text = label,
                    color = if (subPage == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { subPage = idx },
                )
            }
        }

        // ── ۳. فیلتر و دسته‌بندی استراتژی‌ها ───────────────────────────────
        StrategyJournalCategorizationCard(
            categories = strategyCategories,
            selectedStrategyFilter = selectedStrategyFilter,
            onSelectStrategyFilter = { selectedStrategyFilter = it },
        )

        when (subPage) {
            0 -> {
                MonthlyPnlCalendarSection(
                    trades = filteredTrades,
                    closedTrades = filteredClosedTrades,
                    stats = filteredStats,
                    now = now,
                    onDeleteTrade = viewModel::deleteJournalTrade,
                    onClearAll = viewModel::clearAllJournalTrades,
                    onOpenChart = onChartSymbol,
                )
            }

            1 -> {
                AutoMonitoredOpenTradesCard(
                    openTrades = filteredOpenTrades,
                    liveTicks = liveTicks,
                    lastMarks = lastMarks,
                    onRefreshAll = {
                        filteredOpenTrades.map { it.symbol }.distinct().forEach { viewModel.refreshSymbolLivePrice(it) }
                    },
                    onCloseAtPrice = viewModel::closeTradeManually,
                    onDelete = viewModel::deleteJournalTrade,
                    onOpenChart = onChartSymbol,
                )
            }

            else -> {
                RiskCalculatorAndAutoTradeCard(
                    activeSymbol = activeSymbol,
                    liveTick = liveTicks[activeSymbol],
                    accountBalance = settings.accountBalance,
                    riskPercent = settings.riskPercent,
                    fixedRiskUsdOverride = if (settings.useFixedDollarRisk) settings.fixedRiskUsd else null,
                    riskLimits = riskLimits,
                    avalAiEnabled = settings.avalAiEnabled,
                    aiChecking = aiChecking,
                    lastAiVerdict = lastAiVerdict,
                    onSelectSymbol = viewModel::selectRiskSymbol,
                    onRefreshLivePrice = { onResult -> viewModel.refreshSymbolLivePrice(activeSymbol, onResult) },
                    onAskAiOnly = { calc -> viewModel.checkTradeTimingWithAi(calc) },
                    onOpenAutoTrade = { calc, note ->
                        viewModel.openAutoTrackedTrade(
                            calc = calc,
                            note = note,
                            strategyId = settings.selectedBitcoinStrategyId,
                            strategyTitleFa = settings.activeBitcoinStrategyTitleFa(),
                        )
                    },
                    onOpenChart = { onChartSymbol(activeSymbol) },
                )
            }
        }
    }
}

@Composable
private fun StrategyJournalCategorizationCard(
    categories: List<com.aurum.edge.core.StrategyJournalCategoryStats>,
    selectedStrategyFilter: String,
    onSelectStrategyFilter: (String) -> Unit,
) {
    SectionCard(
        title = "📂 دسته‌بندی ژورنال بر اساس استراتژی‌ها (هم‌زمان فعال)",
        subtitle = "عملکرد و معاملات هر استراتژی به‌طور مجزا ثبت و تفکیک شده است؛ روی هر استراتژی بزنید تا ژورنال فیلتر شود",
        trailing = {
            Pill(
                text = if (selectedStrategyFilter == "ALL") "نمایش همه ✓" else "نمایش همه استراتژی‌ها",
                color = if (selectedStrategyFilter == "ALL") AurumColors.Gold else AurumColors.Cyan,
                modifier = Modifier.clickable { onSelectStrategyFilter("ALL") },
            )
        },
    ) {
        // نوار فیلتر سریع استراتژی‌ها
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill(
                text = "همه استراتژی‌ها (${categories.sumOf { it.totalCount }})",
                color = if (selectedStrategyFilter == "ALL") AurumColors.Gold else AurumColors.TextMuted,
                modifier = Modifier.clickable { onSelectStrategyFilter("ALL") },
            )
            categories.forEach { cat ->
                val shortName = cat.strategyTitleFa.substringBefore('(').trim()
                Pill(
                    text = "$shortName (${cat.totalCount})",
                    color = if (selectedStrategyFilter == cat.strategyId) AurumColors.Cyan else AurumColors.TextMuted,
                    modifier = Modifier.clickable { onSelectStrategyFilter(cat.strategyId) },
                )
            }
        }

        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categories.forEach { cat ->
                val isSelected = selectedStrategyFilter == cat.strategyId
                val pnlColor = when {
                    cat.netPnlUsd > 0.0 -> AurumColors.Green
                    cat.netPnlUsd < 0.0 -> AurumColors.Red
                    else -> AurumColors.Gold
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) AurumColors.SurfaceAlt else AurumColors.Surface,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            1.dp,
                            if (isSelected) AurumColors.Cyan else AurumColors.Line.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            onSelectStrategyFilter(if (isSelected) "ALL" else cat.strategyId)
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cat.strategyTitleFa,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) AurumColors.Cyan else AurumColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "باز: ${cat.openCount} · بسته: ${cat.closedCount} (برد: ${cat.winsCount} / باخت: ${cat.lossesCount} · وین‌ریت: ${cat.winRate.toInt()}%) · [15m: ${cat.count15m} | 5m: ${cat.count5m}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s$%.2f",
                                if (cat.netPnlUsd >= 0.0) "+" else "",
                                cat.netPnlUsd,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = pnlColor,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s%.2fR",
                                if (cat.totalR >= 0.0) "+" else "",
                                cat.totalR,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = pnlColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CapitalAndRiskLimitsCard(
    settings: com.aurum.edge.core.AppSettings,
    riskLimits: com.aurum.edge.core.RiskLimitStatus,
    onSaveSettings: (Double, Double, Double, Double, Double) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var balanceText by rememberSaveable(settings.accountBalance) {
        mutableStateOf(String.format(Locale.US, "%.2f", settings.accountBalance))
    }
    var initialCapText by rememberSaveable(settings.initialCapital) {
        mutableStateOf(String.format(Locale.US, "%.2f", settings.initialCapital))
    }
    var riskPctText by rememberSaveable(settings.riskPercent) {
        mutableStateOf(String.format(Locale.US, "%.2f", settings.riskPercent).trimEnd('0').trimEnd('.'))
    }
    var dailyLimitText by rememberSaveable(settings.dailyLossLimitPercent) {
        mutableStateOf(String.format(Locale.US, "%.1f", settings.dailyLossLimitPercent).trimEnd('0').trimEnd('.'))
    }
    var maxDdText by rememberSaveable(settings.maxDrawdownLimitPercent) {
        mutableStateOf(String.format(Locale.US, "%.1f", settings.maxDrawdownLimitPercent).trimEnd('0').trimEnd('.'))
    }

    SectionCard(
        title = "🛡️ مدیریت ریسک و سرمایه",
        subtitle = "تنظیم سرمایه، درصد ریسک هر معامله و سقف ضرر روزانه و کلی",
        trailing = {
            Pill(
                text = if (editing) "بستن ویرایش" else "تنظیم سرمایه و ریسک",
                color = AurumColors.Gold,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = !editing },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "موجودی فعلی حساب",
                value = String.format(Locale.US, "$%.2f", settings.accountBalance),
                valueColor = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "ریسک هر معامله",
                value = "${String.format(Locale.US, "%.2f", settings.riskPercent).trimEnd('0').trimEnd('.')}% ($${String.format(Locale.US, "%.2f", settings.accountBalance * settings.riskPercent / 100.0)})",
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val todayColor = when {
                riskLimits.todayRealizedPnlUsd > 0.0 -> AurumColors.Green
                riskLimits.todayRealizedPnlUsd < 0.0 -> AurumColors.Red
                else -> AurumColors.TextPrimary
            }
            StatTile(
                label = "سود/زیان امروز",
                value = String.format(
                    Locale.US,
                    "%s$%.2f",
                    if (riskLimits.todayRealizedPnlUsd >= 0.0) "+" else "",
                    riskLimits.todayRealizedPnlUsd,
                ),
                valueColor = todayColor,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "ریسک باز فعلی",
                value = String.format(Locale.US, "$%.2f", riskLimits.openTradesRiskUsd),
                valueColor = if (riskLimits.openTradesRiskUsd > 0.0) AurumColors.Orange else AurumColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        // Daily & Total Drawdown progress bars
        val dailyUsedRatio = if (riskLimits.dailyLossLimitUsd > 0.0) {
            ((riskLimits.dailyLossLimitUsd - riskLimits.remainingDailyRiskUsd) / riskLimits.dailyLossLimitUsd)
                .toFloat().coerceIn(0f, 1f)
        } else 0f

        val overallUsedRatio = if (riskLimits.maxDrawdownLimitUsd > 0.0) {
            ((riskLimits.maxDrawdownLimitUsd - riskLimits.remainingDrawdownUsd) / riskLimits.maxDrawdownLimitUsd)
                .toFloat().coerceIn(0f, 1f)
        } else 0f

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "سقف ضرر روزانه (${String.format(Locale.US, "%.1f", settings.dailyLossLimitPercent)}% = $${String.format(Locale.US, "%.2f", riskLimits.dailyLossLimitUsd)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    "باقی‌مانده: $${String.format(Locale.US, "%.2f", riskLimits.remainingDailyRiskUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (riskLimits.isDailyLimitBreached) AurumColors.Red else AurumColors.Green,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { dailyUsedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (dailyUsedRatio >= 0.85f) AurumColors.Red else AurumColors.Gold,
                trackColor = AurumColors.Surface,
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "سقف ضرر کلی (${String.format(Locale.US, "%.1f", settings.maxDrawdownLimitPercent)}% = $${String.format(Locale.US, "%.2f", riskLimits.maxDrawdownLimitUsd)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    "باقی‌مانده: $${String.format(Locale.US, "%.2f", riskLimits.remainingDrawdownUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (riskLimits.isOverallDrawdownBreached) AurumColors.Red else AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { overallUsedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (overallUsedRatio >= 0.85f) AurumColors.Red else AurumColors.Cyan,
                trackColor = AurumColors.Surface,
            )
        }

        // Quick Risk Percent Selector Chips
        Text(
            "انتخاب سریع درصد ریسک در هر معامله:",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RISK_PRESETS.forEach { pct ->
                val selected = abs(settings.riskPercent - pct) < 0.01
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                        .border(
                            1.dp,
                            if (selected) AurumColors.Gold else AurumColors.Line,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            riskPctText = pct.toString()
                            onSaveSettings(
                                settings.accountBalance,
                                settings.initialCapital,
                                pct,
                                settings.dailyLossLimitPercent,
                                settings.maxDrawdownLimitPercent,
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "${pct}%",
                        color = if (selected) AurumColors.Gold else AurumColors.TextPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (editing) AurumColors.Cyan.copy(alpha = 0.2f) else AurumColors.SurfaceAlt)
                    .border(1.dp, AurumColors.Cyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable { editing = !editing }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (editing) "بستن پنل تنظیمات ▲" else "ویرایش سرمایه و سقف ضرر ▼",
                    color = AurumColors.Cyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = balanceText,
                        onValueChange = { balanceText = it },
                        label = { Text("موجودی فعلی ($)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = initialCapText,
                        onValueChange = { initialCapText = it },
                        label = { Text("سرمایه اولیه ($)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = riskPctText,
                        onValueChange = { riskPctText = it },
                        label = { Text("ریسک هر معامله (%)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dailyLimitText,
                        onValueChange = { dailyLimitText = it },
                        label = { Text("سقف ضرر روزانه (%)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxDdText,
                        onValueChange = { maxDdText = it },
                        label = { Text("سقف ضرر کل (%)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        val bal = balanceText.trim().toDoubleOrNull() ?: settings.accountBalance
                        val initCap = initialCapText.trim().toDoubleOrNull() ?: bal
                        val risk = riskPctText.trim().toDoubleOrNull() ?: settings.riskPercent
                        val daily = dailyLimitText.trim().toDoubleOrNull() ?: settings.dailyLossLimitPercent
                        val maxDd = maxDdText.trim().toDoubleOrNull() ?: settings.maxDrawdownLimitPercent
                        onSaveSettings(bal, initCap, risk, daily, maxDd)
                        editing = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = Color(0xFF14100A),
                    ),
                ) {
                    Text("ذخیره تنظیمات سرمایه و ریسک", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun AvalAiPreTradeGateCard(
    settings: com.aurum.edge.core.AppSettings,
    aiChecking: Boolean,
    lastVerdict: AiTradeVerdict?,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveConfig: (String, String, String) -> Unit,
    onTestYesNo: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val effectiveSavedKey = settings.newsAiApiKey.ifBlank { AvalAiAdvisor.PRECONFIGURED_USER_KEY }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var keyInput by rememberSaveable(effectiveSavedKey) { mutableStateOf(effectiveSavedKey) }
    var modelInput by rememberSaveable(settings.newsAiModel) {
        mutableStateOf(settings.newsAiModel.ifBlank { AvalAiAdvisor.DEFAULT_MODEL })
    }
    var urlInput by rememberSaveable(settings.newsAiBaseUrl) {
        mutableStateOf(settings.newsAiBaseUrl.ifBlank { AvalAiAdvisor.DEFAULT_BASE_URL })
    }
    val (keyValid, keyStatusMsg) = remember(keyInput) {
        AvalAiAdvisor.validateApiKeyFormat(keyInput)
    }

    SectionCard(
        title = "🤖 هوش مصنوعی AvalAI (یک سوال با پاسخ بله / نه)",
        subtitle = "سوال از هوش مصنوعی: «الان با این شرایط مدل استراتژی معامله کنم یا نه؟» ← پاسخ: «بله» یا «نه»",
        trailing = {
            Pill(
                text = if (aiChecking) "در حال پرسش..." else if (settings.avalAiEnabled) "فعال (بله/نه) ✓" else "غیرفعال",
                color = if (settings.avalAiEnabled) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        // Activation Toggle Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = if (settings.avalAiEnabled) {
                        "فعال: قبل از باز شدن هر معامله، شرایط مدل استراتژی ارسال و پاسخ «بله» یا «نه» گرفته می‌شود"
                    } else {
                        "غیرفعال: معاملات استراتژی بدون پرسش از هوش مصنوعی ثبت می‌شوند"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.avalAiEnabled) AurumColors.Green else AurumColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "وضعیت کلید: $keyStatusMsg · مدل: ${settings.newsAiModel.ifBlank { AvalAiAdvisor.DEFAULT_MODEL }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (keyValid) AurumColors.Cyan else AurumColors.Red,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = settings.avalAiEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }

        // Last AI Verdict Display (Yes / No)
        if (lastVerdict != null) {
            val vColor = if (lastVerdict.approved) AurumColors.Green else AurumColors.Red
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(vColor.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                    .border(1.dp, vColor.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "پاسخ هوش مصنوعی (${lastVerdict.symbol}): ${lastVerdict.yesNoLabelFa}",
                        style = MaterialTheme.typography.titleSmall,
                        color = vColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = "${lastVerdict.confidence}% · ${lastVerdict.modelUsed}",
                        color = vColor,
                    )
                }
                Text(
                    text = lastVerdict.reasonFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextPrimary,
                )
            }
        }

        if (onTestYesNo != null) {
            Button(
                onClick = onTestYesNo,
                enabled = !aiChecking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.Gold,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    text = if (aiChecking) "در حال استعلام از AvalAI..." else "⚡ تست زنده کلید: الان با شرایط استراتژی معامله کنم یا نه؟",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Buttons to open AvalAI API Keys page or toggle settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    runCatching { uriHandler.openUri(AvalAiAdvisor.API_KEYS_PORTAL_URL) }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("پنل کلید chat.avalai.ir ↗", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            OutlinedButton(
                onClick = { showConfig = !showConfig },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (showConfig) "بستن تنظیمات کلید ▲" else "مشاهده / ویرایش کلید API ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                )
            }
        }

        if (showConfig) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("کلید API سایت AvalAI (از chat.avalai.ir/platform/api-keys)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "انتخاب سریع مدل هوش مصنوعی AvalAI:",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AvalAiAdvisor.POPULAR_MODELS.forEach { presetModel ->
                        val selected = modelInput.trim() == presetModel
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.Surface)
                                .border(1.dp, if (selected) AurumColors.Gold else AurumColors.Line, RoundedCornerShape(8.dp))
                                .clickable { modelInput = presetModel }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = presetModel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) AurumColors.Gold else AurumColors.TextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("نام مدل (Model)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("آدرس پایه (Base URL)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Button(
                    onClick = {
                        onSaveConfig(keyInput, modelInput, urlInput)
                        showConfig = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = Color(0xFF14100A),
                    ),
                ) {
                    Text("ذخیره کلید API و فعال‌سازی هوش مصنوعی", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RiskCalculatorAndAutoTradeCard(
    activeSymbol: String,
    liveTick: PriceTick?,
    accountBalance: Double,
    riskPercent: Double,
    fixedRiskUsdOverride: Double?,
    riskLimits: com.aurum.edge.core.RiskLimitStatus,
    avalAiEnabled: Boolean,
    aiChecking: Boolean,
    lastAiVerdict: AiTradeVerdict?,
    onSelectSymbol: (String) -> Unit,
    onRefreshLivePrice: (((PriceTick?) -> Unit)?) -> Unit,
    onAskAiOnly: (com.aurum.edge.core.RiskCalculationResult) -> Unit,
    onOpenAutoTrade: (com.aurum.edge.core.RiskCalculationResult, String) -> Unit,
    onOpenChart: () -> Unit,
) {
    var selectedDirection by rememberSaveable { mutableStateOf(SignalAction.BUY.name) }
    val direction = if (selectedDirection == SignalAction.SELL.name) SignalAction.SELL else SignalAction.BUY

    var entryText by rememberSaveable(activeSymbol) { mutableStateOf("") }
    var slText by rememberSaveable(activeSymbol) { mutableStateOf("") }
    var tpText by rememberSaveable(activeSymbol) { mutableStateOf("") }
    var noteText by rememberSaveable { mutableStateOf("") }

    // Auto-fill entry/SL/TP when a symbol's first live tick arrives and fields are still blank
    LaunchedEffect(activeSymbol, liveTick?.price) {
        val p = liveTick?.price
        if (p != null && entryText.isBlank() && slText.isBlank() && tpText.isBlank()) {
            entryText = formatPrice(p)
            RiskAndCalendarEngine.suggestSlTp(activeSymbol, p, direction, 2.0)?.let { (sl, tp) ->
                slText = formatPrice(sl)
                tpText = formatPrice(tp)
            }
        }
    }

    val entryVal = entryText.replace(',', '.').trim().toDoubleOrNull()
    val slVal = slText.replace(',', '.').trim().toDoubleOrNull()
    val tpVal = tpText.replace(',', '.').trim().toDoubleOrNull()

    val calcResult = remember(activeSymbol, accountBalance, riskPercent, fixedRiskUsdOverride, entryVal, slVal, tpVal, direction) {
        if (entryVal != null && slVal != null && tpVal != null) {
            RiskAndCalendarEngine.calculatePosition(
                symbolInput = activeSymbol,
                accountBalance = accountBalance,
                riskPercent = riskPercent,
                entry = entryVal,
                stopLoss = slVal,
                takeProfit = tpVal,
                preferredAction = direction,
                fixedRiskUsdOverride = fixedRiskUsdOverride,
            )
        } else null
    }

    SectionCard(
        title = "🧮 ماشین‌حساب حجم و ثبت خودکار معامله",
        subtitle = "ورود، SL و TP را وارد کنید؛ حجم خودکار محاسبه و تا برخورد به TP یا SL رصد می‌شود",
        trailing = {
            Pill(
                text = "نمایش چارت ↗",
                color = AurumColors.Cyan,
            )
        },
    ) {
        // Symbol Selector Horizontal Chips
        Text(
            "۱. انتخاب نماد معاملاتی:",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WatchCatalog.symbols.forEach { sym ->
                val isSelected = sym.id == activeSymbol
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                        .border(
                            1.dp,
                            if (isSelected) AurumColors.Gold else AurumColors.Line,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelectSymbol(sym.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sym.id,
                            color = if (isSelected) AurumColors.Gold else AurumColors.TextPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            text = sym.label,
                            color = AurumColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // Live market price banner + 1-tap fill button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "قیمت لحظه‌ای بازار ($activeSymbol): " +
                        (liveTick?.let { formatPrice(it.price) } ?: "در حال دریافت..."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (liveTick != null) AurumColors.Green else AurumColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = liveTick?.let {
                        "منبع: ${it.provider ?: "بازار"} · ${formatDateTime(it.at)}"
                    } ?: "برای پر کردن خودکار قیمت ورود و SL/TP دکمهٔ روبرو را بزنید",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
            OutlinedButton(
                onClick = {
                    onRefreshLivePrice { fetched ->
                        val p = (fetched ?: liveTick)?.price ?: return@onRefreshLivePrice
                        entryText = formatPrice(p)
                        RiskAndCalendarEngine.suggestSlTp(activeSymbol, p, direction, 2.0)?.let { (sl, tp) ->
                            slText = formatPrice(sl)
                            tpText = formatPrice(tp)
                        }
                    }
                },
            ) {
                Text("قیمت لحظه‌ای ⚡", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
        }

        // Direction toggle (BUY / SELL) + Quick R:R ratio presets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isBuy = direction == SignalAction.BUY
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isBuy) AurumColors.Green.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                    .border(1.dp, if (isBuy) AurumColors.Green else AurumColors.Line, RoundedCornerShape(10.dp))
                    .clickable {
                        selectedDirection = SignalAction.BUY.name
                        val e = entryVal ?: liveTick?.price
                        if (e != null) {
                            RiskAndCalendarEngine.suggestSlTp(activeSymbol, e, SignalAction.BUY, 2.0)?.let { (sl, tp) ->
                                slText = formatPrice(sl)
                                tpText = formatPrice(tp)
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "خرید (Long / BUY) ↗",
                    color = if (isBuy) AurumColors.Green else AurumColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (!isBuy) AurumColors.Red.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                    .border(1.dp, if (!isBuy) AurumColors.Red else AurumColors.Line, RoundedCornerShape(10.dp))
                    .clickable {
                        selectedDirection = SignalAction.SELL.name
                        val e = entryVal ?: liveTick?.price
                        if (e != null) {
                            RiskAndCalendarEngine.suggestSlTp(activeSymbol, e, SignalAction.SELL, 2.0)?.let { (sl, tp) ->
                                slText = formatPrice(sl)
                                tpText = formatPrice(tp)
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "فروش (Short / SELL) ↘",
                    color = if (!isBuy) AurumColors.Red else AurumColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Entry, SL, TP Inputs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = entryText,
                onValueChange = { entryText = it },
                label = { Text("قیمت ورود (Entry)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = slText,
                onValueChange = { slText = it },
                label = { Text("حد ضرر (SL)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = tpText,
                onValueChange = { tpText = it },
                label = { Text("حد سود (TP)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }

        // Quick R:R buttons to auto-adjust TP from Entry & SL
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "تنظیم خودکار TP بر اساس R:R:",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            RR_PRESETS.forEach { rr ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AurumColors.SurfaceAlt)
                        .border(1.dp, AurumColors.Line, RoundedCornerShape(6.dp))
                        .clickable {
                            if (entryVal != null && slVal != null && abs(entryVal - slVal) > 1e-8) {
                                val dist = abs(entryVal - slVal) * rr
                                val newTp = if (slVal < entryVal) entryVal + dist else entryVal - dist
                                if (newTp > 0.0) tpText = formatPrice(newTp)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "1:$rr",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "چارت $activeSymbol ↗",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                modifier = Modifier.clickable { onOpenChart() },
            )
        }

        OutlinedTextField(
            value = noteText,
            onValueChange = { noteText = it },
            label = { Text("یادداشت / ستاپ معاملاتی (اختیاری)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        // Calculated Position Size, Lot, Risk $, Reward $, R:R Box
        if (calcResult != null) {
            val withinLimits = riskLimits.canOpenTrade(calcResult.riskBudgetUsd)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        if (withinLimits) AurumColors.Gold.copy(alpha = 0.45f) else AurumColors.Red,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "نتیجهٔ محاسبهٔ خودکار مدیریت ریسک",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = if (calcResult.action == SignalAction.BUY) "خرید (BUY)" else "فروش (SELL)",
                        color = if (calcResult.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = "حجم پوزیشن / لات",
                        value = calcResult.lotDescription,
                        valueColor = AurumColors.Gold,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "نسبت ریسک به ریوارد",
                        value = String.format(Locale.US, "1 : %.2f", calcResult.riskReward),
                        valueColor = if (calcResult.riskReward >= 1.5) AurumColors.Green else AurumColors.Orange,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = "مقدار دلار در ریسک (SL)",
                        value = String.format(Locale.US, "-$%.2f (%.1f%%)", calcResult.riskBudgetUsd, calcResult.riskPercent),
                        valueColor = AurumColors.Red,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "سود خالص در هدف (TP)",
                        value = String.format(Locale.US, "+$%.2f", calcResult.netProfitAtTpUsd),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "مارجین مورد نیاز: $${String.format(Locale.US, "%.2f", calcResult.marginRequiredUsd)} (اهرم ${calcResult.leverage}x) · کارمزد و اسپرد: $${String.format(Locale.US, "%.2f", calcResult.commissionUsd + calcResult.spreadCostUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )

                if (!withinLimits) {
                    Text(
                        text = "⚠️ هشدار مدیریت ریسک: ریسک این معامله ($${String.format(Locale.US, "%.2f", calcResult.riskBudgetUsd)}) بیشتر از سقف ضرر مجاز باقی‌ماندهٔ روزانه ($${String.format(Locale.US, "%.2f", riskLimits.remainingDailyRiskUsd)}) یا کلی است.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onAskAiOnly(calcResult) },
                        enabled = !aiChecking,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (aiChecking) "در حال پرسش از AI..." else "🤖 نظر هوش مصنوعی",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Button(
                        onClick = {
                            onOpenAutoTrade(calcResult, noteText)
                            noteText = ""
                        },
                        enabled = !aiChecking,
                        modifier = Modifier.weight(1.6f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (calcResult.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                            contentColor = Color(0xFF07090D),
                        ),
                    ) {
                        Text(
                            text = if (avalAiEnabled) {
                                "⚡ بررسی AI و ثبت معامله"
                            } else {
                                "⚡ ثبت و رصد خودکار TP/SL"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        } else {
            Text(
                text = "جهت محاسبهٔ خودکار حجم، لات و نسبت R:R، قیمت ورود، حد ضرر (SL) و حد سود (TP) معتبر وارد کنید (در خرید: SL < Entry < TP و در فروش: TP < Entry < SL).",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Orange,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
internal fun OfflineGapBackfillCard(
    activeSymbol: String,
    summary: CandleBackfillSummary?,
    isBackfilling: Boolean,
    onSyncGap: (Int?) -> Unit,
) {
    SectionCard(
        title = "🔄 بازیابی دیتای ساعات قطعی (Backfill)",
        subtitle = "دریافت یکجای کندل‌های ساعاتی که آفلاین بودید و بررسی خودکار برخورد High/Low به TP و SL",
        trailing = {
            Pill(
                text = if (isBackfilling) "در حال دریافت..." else if (summary != null) "همگام تا این لحظه ✓" else "آماده همگام‌سازی",
                color = if (isBackfilling) AurumColors.Gold else if (summary != null) AurumColors.Green else AurumColors.Cyan,
            )
        },
    ) {
        if (summary != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "نماد ${summary.symbol} · ${summary.candlesCount} کندل بازیابی شد (${summary.provider})",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "بازهٔ پوشش: از ${formatDateTime(summary.fromMillis)} تا ${formatDateTime(summary.toMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(
                        label = "بالاترین قیمت بازه (High)",
                        value = formatPrice(summary.periodHigh),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "پایین‌ترین قیمت بازه (Low)",
                        value = formatPrice(summary.periodLow),
                        valueColor = AurumColors.Red,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "آخرین کلوز",
                        value = formatPrice(summary.lastClose),
                        valueColor = AurumColors.Cyan,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Text(
                text = "به محض اتصال، کندل‌های ۱ دقیقه/۵ دقیقهٔ ساعات قطعی برای نماد انتخابی و معاملات باز دریافت و به ترتیب زمانی روی TP و SL بررسی می‌شوند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { onSyncGap(6) },
                enabled = !isBackfilling,
                modifier = Modifier.weight(1f),
            ) {
                Text("بازیابی ۶ ساعت اخیر", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            OutlinedButton(
                onClick = { onSyncGap(24) },
                enabled = !isBackfilling,
                modifier = Modifier.weight(1f),
            ) {
                Text("بازیابی ۲۴ ساعت اخیر", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            }
            OutlinedButton(
                onClick = { onSyncGap(null) },
                enabled = !isBackfilling,
                modifier = Modifier.weight(1f),
            ) {
                Text("از آخرین اتصال", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
            }
        }
    }
}

@Composable
private fun AutoMonitoredOpenTradesCard(
    openTrades: List<PaperTrade>,
    liveTicks: Map<String, PriceTick>,
    lastMarks: Map<String, DisplayMark>,
    onRefreshAll: () -> Unit,
    onCloseAtPrice: (String, Double, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenChart: (String) -> Unit,
) {
    SectionCard(
        title = "📡 معاملات باز در حال رصد خودکار (${openTrades.size})",
        subtitle = "قیمت لحظه‌ای بازار پیوسته چک می‌شود؛ با رسیدن به TP یا SL معامله خودکار بسته و در تقویم ثبت می‌شود",
        trailing = {
            Pill(
                text = "بروزرسانی قیمت ↻",
                color = AurumColors.Cyan,
            )
        },
    ) {
        if (openTrades.isEmpty()) {
            Text(
                text = "در حال حاضر معاملهٔ بازی وجود ندارد. از ماشین‌حساب بالا معاملهٔ جدید ثبت کنید تا به‌طور خودکار رصد شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRefreshAll() }
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "دریافت فوری آخرین قیمت همهٔ پوزیشن‌ها ↻",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                openTrades.forEach { trade ->
                    val tick = liveTicks[V1Universe.normalize(trade.symbol)]
                    val mark = OpenPositionDisplay.forTrade(trade, lastMarks)
                    val currentPrice = tick?.price ?: mark?.price

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                            .border(1.dp, AurumColors.Line, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TradingViewPositionBox(
                            trade = trade,
                            currentPrice = currentPrice,
                            onOpenChart = { onOpenChart(trade.symbol) },
                        )

                        if (trade.note.isNotBlank()) {
                            Text(
                                text = "یادداشت: ${trade.note}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Gold,
                            )
                        }

                        // Action buttons: Close at live price, Close at TP, Close at SL, Delete
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val exitP = currentPrice ?: trade.entry
                                    onCloseAtPrice(trade.id, exitP, "بستن با قیمت لحظه‌ای بازار")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("بستن با قیمت لحظه‌ای", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                            }
                            OutlinedButton(
                                onClick = {
                                    onCloseAtPrice(trade.id, trade.takeProfit, "برخورد به حد سود (TP)")
                                },
                            ) {
                                Text("TP ✓", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
                            }
                            OutlinedButton(
                                onClick = {
                                    onCloseAtPrice(trade.id, trade.stopLoss, "برخورد به حد ضرر (SL)")
                                },
                            ) {
                                Text("SL ✕", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                            }
                            OutlinedButton(
                                onClick = { onDelete(trade.id) },
                            ) {
                                Text("حذف", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyPnlCalendarSection(
    trades: List<PaperTrade>,
    closedTrades: List<PaperTrade>,
    stats: com.aurum.edge.data.JournalStats,
    now: Long,
    onDeleteTrade: (String) -> Unit,
    onClearAll: () -> Unit,
    onOpenChart: (String) -> Unit,
) {
    val currentYm = remember(now) {
        YearMonth.from(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
    }
    var selectedYear by rememberSaveable { mutableIntStateOf(currentYm.year) }
    var selectedMonth by rememberSaveable { mutableIntStateOf(currentYm.monthValue) }
    var selectedDayOfMonth by rememberSaveable { mutableIntStateOf(0) } // 0 = show all month trades

    val calendar = remember(selectedYear, selectedMonth, trades) {
        RiskAndCalendarEngine.buildMonthlyCalendar(
            year = selectedYear,
            month = selectedMonth,
            trades = trades,
        )
    }

    SectionCard(
        title = "📅 تقویم ماهانه سود و زیان (روزشمار)",
        subtitle = "برآیند روزانه و ماهانه معاملات بسته‌شده بر اساس روز؛ برای فیلتر روی هر روز کلیک کنید",
        trailing = {
            val monthColor = when {
                calendar.monthNetPnlUsd > 0.0 -> AurumColors.Green
                calendar.monthNetPnlUsd < 0.0 -> AurumColors.Red
                else -> AurumColors.Gold
            }
            Pill(
                text = String.format(
                    Locale.US,
                    "%s$%.2f",
                    if (calendar.monthNetPnlUsd >= 0.0) "+" else "",
                    calendar.monthNetPnlUsd,
                ),
                color = monthColor,
            )
        },
    ) {
        // Month navigation bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ ماه قبل",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val prev = YearMonth.of(selectedYear, selectedMonth).minusMonths(1)
                    selectedYear = prev.year
                    selectedMonth = prev.monthValue
                    selectedDayOfMonth = 0
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = calendar.monthTitleFa,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                if (selectedYear != currentYm.year || selectedMonth != currentYm.monthValue) {
                    Text(
                        text = "(امروز)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable {
                            selectedYear = currentYm.year
                            selectedMonth = currentYm.monthValue
                            selectedDayOfMonth = 0
                        },
                    )
                }
            }
            Text(
                text = "ماه بعد ›",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val next = YearMonth.of(selectedYear, selectedMonth).plusMonths(1)
                    selectedYear = next.year
                    selectedMonth = next.monthValue
                    selectedDayOfMonth = 0
                },
            )
        }

        // Monthly KPI tiles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "برآیند ماه",
                value = String.format(
                    Locale.US,
                    "%s$%.2f",
                    if (calendar.monthNetPnlUsd >= 0.0) "+" else "",
                    calendar.monthNetPnlUsd,
                ),
                valueColor = if (calendar.monthNetPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "روزهای سودده / ضررده",
                value = "${calendar.winningDays} سبز / ${calendar.losingDays} قرمز",
                valueColor = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "وین‌ریت ماه (${calendar.totalClosedTrades})",
                value = calendar.monthWinRate?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
        }

        // Weekday header row (Saturday to Friday)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WEEKDAY_HEADERS_FA.forEach { dayName ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Calendar grid (rows of 7 cells)
        val totalCells = calendar.firstDayOffsetSaturdayStart + calendar.daysInMonth
        val rows = (totalCells + 6) / 7

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (c in 0 until 7) {
                        val cellIndex = r * 7 + c
                        val dayNumber = cellIndex - calendar.firstDayOffsetSaturdayStart + 1
                        val daySummary = if (dayNumber in 1..calendar.daysInMonth) {
                            calendar.days[dayNumber - 1]
                        } else null

                        if (daySummary == null) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                            )
                        } else {
                            CalendarDayCell(
                                summary = daySummary,
                                isSelected = selectedDayOfMonth == daySummary.dayOfMonth,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    selectedDayOfMonth = if (selectedDayOfMonth == daySummary.dayOfMonth) 0
                                    else daySummary.dayOfMonth
                                },
                            )
                        }
                    }
                }
            }
        }

        // Overall Journal Stats Summary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "📊 آمار کل ژورنال معاملاتی",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "برآیند کل",
                    value = String.format(Locale.US, "%s$%.2f", if (stats.netPnl >= 0) "+" else "", stats.netPnl),
                    valueColor = if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "وین‌ریت کل",
                    value = stats.winRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    valueColor = AurumColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "پرافیت فاکتور",
                    value = stats.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    valueColor = AurumColors.Gold,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Filtered closed trades list (either selected day or all month's trades)
        val displayedTrades = remember(calendar, selectedDayOfMonth, closedTrades) {
            if (selectedDayOfMonth in 1..calendar.daysInMonth) {
                calendar.days[selectedDayOfMonth - 1].trades
            } else {
                calendar.days.flatMap { it.trades }.sortedByDescending { it.closedAt ?: 0L }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectedDayOfMonth > 0) {
                    "معاملات روز $selectedDayOfMonth ${calendar.monthTitleFa} (${displayedTrades.size})"
                } else {
                    "معاملات بسته‌شده در ${calendar.monthTitleFa} (${displayedTrades.size})"
                },
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            if (selectedDayOfMonth > 0) {
                Text(
                    text = "نمایش کل ماه",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { selectedDayOfMonth = 0 },
                )
            } else if (closedTrades.isNotEmpty()) {
                Text(
                    text = "پاک کردن کل تاریخچه",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Red,
                    modifier = Modifier.clickable { onClearAll() },
                )
            }
        }

        if (displayedTrades.isEmpty()) {
            Text(
                text = if (selectedDayOfMonth > 0) {
                    "در این روز هیچ معاملهٔ بسته‌شده‌ای ثبت نشده است."
                } else {
                    "در این ماه هنوز معاملهٔ بسته‌شده‌ای ثبت نشده است."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                displayedTrades.forEach { trade ->
                    ClosedJournalTradeRow(
                        trade = trade,
                        onDelete = { onDeleteTrade(trade.id) },
                        onOpenChart = { onOpenChart(trade.symbol) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    summary: DayPnlSummary,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hasTrades = summary.closedTradesCount > 0
    val bgColor = when {
        !hasTrades -> AurumColors.SurfaceAlt.copy(alpha = 0.45f)
        summary.netPnlUsd > 0.0 -> AurumColors.Green.copy(alpha = 0.20f)
        summary.netPnlUsd < 0.0 -> AurumColors.Red.copy(alpha = 0.20f)
        else -> AurumColors.Gold.copy(alpha = 0.15f)
    }
    val borderColor = when {
        isSelected -> AurumColors.Gold
        !hasTrades -> AurumColors.Line.copy(alpha = 0.5f)
        summary.netPnlUsd > 0.0 -> AurumColors.Green.copy(alpha = 0.65f)
        summary.netPnlUsd < 0.0 -> AurumColors.Red.copy(alpha = 0.65f)
        else -> AurumColors.Gold.copy(alpha = 0.5f)
    }
    val pnlTextColor = when {
        !hasTrades -> AurumColors.TextMuted
        summary.netPnlUsd > 0.0 -> AurumColors.Green
        summary.netPnlUsd < 0.0 -> AurumColors.Red
        else -> AurumColors.Gold
    }

    Column(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = summary.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (hasTrades) AurumColors.TextPrimary else AurumColors.TextMuted,
            fontWeight = FontWeight.Bold,
        )
        if (hasTrades) {
            Text(
                text = String.format(
                    Locale.US,
                    "%s%.1f$",
                    if (summary.netPnlUsd >= 0.0) "+" else "",
                    summary.netPnlUsd,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = pnlTextColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = "${summary.closedTradesCount} معامله",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
                maxLines = 1,
            )
        } else {
            Text(
                text = "—",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ClosedJournalTradeRow(
    trade: PaperTrade,
    onDelete: () -> Unit,
    onOpenChart: () -> Unit,
) {
    val pnl = trade.pnlUsd ?: 0.0
    val isWin = pnl > 0.0
    val isBuy = trade.action == SignalAction.BUY
    val color = if (isWin) AurumColors.Green else if (pnl < 0.0) AurumColors.Red else AurumColors.Gold

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = trade.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onOpenChart() },
                )
                Pill(
                    text = if (isBuy) "خرید" else "فروش",
                    color = if (isBuy) AurumColors.Green else AurumColors.Red,
                )
                Pill(
                    text = "تایم ورود: ${trade.interval.label}",
                    color = AurumColors.Gold,
                )
                Pill(
                    text = trade.strategyTitleFa.substringBefore('(').trim(),
                    color = AurumColors.Cyan,
                )
                trade.rMultiple?.let { r ->
                    Pill(
                        text = String.format(Locale.US, "%s%.2fR", if (r >= 0) "+" else "", r),
                        color = color,
                    )
                }
            }
            Text(
                text = String.format(Locale.US, "%s$%.2f", if (pnl >= 0) "+" else "", pnl),
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = "ورود: ${formatPrice(trade.entry)} · خروج: ${trade.exitPrice?.let { formatPrice(it) } ?: "—"} · SL: ${formatPrice(trade.stopLoss)} · TP: ${formatPrice(trade.takeProfit)}",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextPrimary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${trade.exitReason ?: "بسته‌شده"} · ${formatDateTime(trade.closedAt ?: trade.openedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "حذف",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Red,
                modifier = Modifier.clickable { onDelete() },
            )
        }

        if (trade.note.isNotBlank()) {
            Text(
                text = "یادداشت: ${trade.note}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
            )
        }
    }
}
