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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
    ) {
        // نوار صفحه‌بندی داخلی تب ژورنال و ریسک
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to "📅 تقویم ماهانه (${closedTrades.size})",
                1 to "🧮 ماشین‌حساب و سرمایه",
                2 to "📜 معاملات باز (${openTrades.size}) و گپ",
                3 to "🤖 فیلتر AvalAI",
            ).forEach { (idx, label) ->
                Pill(
                    text = label,
                    color = if (subPage == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { subPage = idx },
                )
            }
        }

        when (subPage) {
            0 -> {
                MonthlyPnlCalendarSection(
                    trades = trades,
                    closedTrades = closedTrades,
                    stats = stats,
                    now = now,
                    onDeleteTrade = viewModel::deleteJournalTrade,
                    onClearAll = viewModel::clearAllJournalTrades,
                    onOpenChart = onChartSymbol,
                )
            }

            1 -> {
                CapitalAndRiskLimitsCard(
                    settings = settings,
                    riskLimits = riskLimits,
                    onSaveSettings = viewModel::saveRiskAndCapitalSettings,
                )
                RiskCalculatorAndAutoTradeCard(
                    activeSymbol = activeSymbol,
                    liveTick = liveTicks[activeSymbol],
                    accountBalance = settings.accountBalance,
                    riskPercent = settings.riskPercent,
                    riskLimits = riskLimits,
                    avalAiEnabled = settings.avalAiEnabled,
                    aiChecking = aiChecking,
                    lastAiVerdict = lastAiVerdict,
                    onSelectSymbol = viewModel::selectRiskSymbol,
                    onRefreshLivePrice = { onResult -> viewModel.refreshSymbolLivePrice(activeSymbol, onResult) },
                    onAskAiOnly = { calc -> viewModel.checkTradeTimingWithAi(calc) },
                    onOpenAutoTrade = { calc, note -> viewModel.openAutoTrackedTrade(calc, note) },
                    onOpenChart = { onChartSymbol(activeSymbol) },
                )
            }

            2 -> {
                AutoMonitoredOpenTradesCard(
                    openTrades = openTrades,
                    liveTicks = liveTicks,
                    lastMarks = lastMarks,
                    onRefreshAll = {
                        openTrades.map { it.symbol }.distinct().forEach { viewModel.refreshSymbolLivePrice(it) }
                    },
                    onCloseAtPrice = viewModel::closeTradeManually,
                    onDelete = viewModel::deleteJournalTrade,
                    onOpenChart = onChartSymbol,
                )
                OfflineGapBackfillCard(
                    activeSymbol = activeSymbol,
                    summary = backfillSummaries[activeSymbol],
                    isBackfilling = isBackfilling,
                    onSyncGap = { hours -> viewModel.syncAllOpenAndActiveSymbolsGap(hoursOverride = hours, notifyOnSuccess = true) },
                )
            }

            3 -> {
                AvalAiPreTradeGateCard(
                    settings = settings,
                    aiChecking = aiChecking,
                    lastVerdict = lastAiVerdict,
                    onToggleEnabled = viewModel::setAvalAiEnabled,
                    onSaveConfig = { key, model, url -> viewModel.saveAvalAiConfig(key, model, url, enableAfterSave = true) },
                )
            }
        }
    }
}

@Composable
private fun CapitalAndRiskLimitsCard(
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
private fun AvalAiPreTradeGateCard(
    settings: com.aurum.edge.core.AppSettings,
    aiChecking: Boolean,
    lastVerdict: AiTradeVerdict?,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveConfig: (String, String, String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var showConfig by rememberSaveable { mutableStateOf(settings.newsAiApiKey.isBlank()) }
    var keyInput by rememberSaveable(settings.newsAiApiKey) { mutableStateOf(settings.newsAiApiKey) }
    var modelInput by rememberSaveable(settings.newsAiModel) {
        mutableStateOf(settings.newsAiModel.ifBlank { AvalAiAdvisor.DEFAULT_MODEL })
    }
    var urlInput by rememberSaveable(settings.newsAiBaseUrl) {
        mutableStateOf(settings.newsAiBaseUrl.ifBlank { AvalAiAdvisor.DEFAULT_BASE_URL })
    }

    SectionCard(
        title = "🤖 هوش مصنوعی AvalAI (فیلتر قبل از خرید و فروش)",
        subtitle = "اگر فعال باشد، قبل از هر خرید یا فروش بررسی می‌کند الان وقت معامله هست یا خیر",
        trailing = {
            Pill(
                text = if (aiChecking) "در حال تحلیل..." else if (settings.avalAiEnabled) "نظر AI فعال ✓" else "غیرفعال",
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
                        "فعال: قبل از باز شدن هر معامله (خرید/فروش)، نظر هوش مصنوعی بررسی می‌شود"
                    } else {
                        "غیرفعال: معاملات بدون استعلام از هوش مصنوعی ثبت می‌شوند"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.avalAiEnabled) AurumColors.Green else AurumColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "مدل انتخابی: ${settings.newsAiModel.ifBlank { AvalAiAdvisor.DEFAULT_MODEL }} · کلید: ${if (settings.newsAiApiKey.isNotBlank()) "ثبت‌شده ✓" else "ثبت‌نشده ⚠️"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = settings.avalAiEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }

        // Last AI Verdict Display (if available)
        if (lastVerdict != null) {
            val vColor = if (lastVerdict.approved) AurumColors.Green else AurumColors.Red
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(vColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, vColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (lastVerdict.approved) "✅ تایید ورود (${lastVerdict.symbol})" else "🛑 رد ورود — وقت معامله نیست (${lastVerdict.symbol})",
                        style = MaterialTheme.typography.labelMedium,
                        color = vColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = "اطمینان ${lastVerdict.confidence}% · ${lastVerdict.modelUsed}",
                        color = vColor,
                    )
                }
                Text(
                    text = lastVerdict.summaryFa,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = lastVerdict.reasonFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
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
                Text("دریافت کلید از chat.avalai.ir ↗", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            OutlinedButton(
                onClick = { showConfig = !showConfig },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (showConfig) "بستن تنظیمات کلید ▲" else "تنظیم کلید API و مدل ▼",
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

    val calcResult = remember(activeSymbol, accountBalance, riskPercent, entryVal, slVal, tpVal, direction) {
        if (entryVal != null && slVal != null && tpVal != null) {
            RiskAndCalendarEngine.calculatePosition(
                symbolInput = activeSymbol,
                accountBalance = accountBalance,
                riskPercent = riskPercent,
                entry = entryVal,
                stopLoss = slVal,
                takeProfit = tpVal,
                preferredAction = direction,
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
private fun OfflineGapBackfillCard(
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
                    val valuation = LiveTradeValuation.from(trade, currentPrice)
                    val isBuy = trade.action == SignalAction.BUY
                    val statusColor = when {
                        valuation == null -> AurumColors.TextMuted
                        valuation.netPnlUsd > 0.0 -> AurumColors.Green
                        valuation.netPnlUsd < 0.0 -> AurumColors.Red
                        else -> AurumColors.Gold
                    }

                    val totalRange = abs(trade.takeProfit - trade.stopLoss).coerceAtLeast(0.000001)
                    val entryFraction = if (isBuy) {
                        ((trade.entry - trade.stopLoss) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
                    } else {
                        ((trade.stopLoss - trade.entry) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AurumColors.Gold,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onOpenChart(trade.symbol) },
                                )
                                Pill(
                                    text = if (isBuy) "خرید (BUY)" else "فروش (SELL)",
                                    color = if (isBuy) AurumColors.Green else AurumColors.Red,
                                )
                                Pill("تایم ورود: ${trade.interval.label}", AurumColors.Gold)
                                Pill("رصد خودکار فعال", AurumColors.Cyan)
                            }
                            Text(
                                text = valuation?.let {
                                    String.format(
                                        Locale.US,
                                        "%s$%.2f (%.2f%%)",
                                        if (it.netPnlUsd >= 0) "+" else "",
                                        it.netPnlUsd,
                                        it.pnlPercent,
                                    )
                                } ?: "در انتظار قیمت...",
                                style = MaterialTheme.typography.titleSmall,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Text(
                            text = "حجم: ${String.format(Locale.US, "%.4f", trade.positionOz)} ${trade.unit} · ریسک: $${String.format(Locale.US, "%.2f", trade.riskUsd)} · R:R = 1:${String.format(Locale.US, "%.2f", trade.riskReward)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )

                        Text(
                            text = "قیمت لحظه‌ای: ${currentPrice?.let { formatPrice(it) } ?: "—"} · ورود: ${formatPrice(trade.entry)} · SL: ${formatPrice(trade.stopLoss)} · TP: ${formatPrice(trade.takeProfit)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )

                        if (trade.note.isNotBlank()) {
                            Text(
                                text = "یادداشت: ${trade.note}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Gold,
                            )
                        }

                        // Progress gauge between SL -> Entry -> TP
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .padding(top = 2.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AurumColors.Surface),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(entryFraction)
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AurumColors.Red.copy(alpha = 0.4f)),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(1f - entryFraction)
                                    .height(7.dp)
                                    .align(Alignment.CenterEnd)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AurumColors.Green.copy(alpha = 0.4f)),
                            )
                            valuation?.let { v ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(v.progress.coerceIn(0.02f, 0.98f))
                                        .height(18.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(statusColor)
                                            .border(2.dp, AurumColors.TextPrimary, CircleShape),
                                    )
                                }
                            }
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
