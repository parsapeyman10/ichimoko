package com.aurum.edge.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.ui.components.BaseStrategyRefinementPanel
import com.aurum.edge.ui.components.ExclusiveStrategySelectorPage
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SelectableStrategyItem
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs

private val FIXED_RISK_USD_PRESETS = listOf(5.0, 10.0, 25.0, 50.0, 100.0)

@Composable
fun SettingsScreen(
    viewModel: AurumViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val now by viewModel.clock.collectAsStateWithLifecycle()
    val aiChecking by viewModel.aiChecking.collectAsStateWithLifecycle()
    val lastAiVerdict by viewModel.lastAiVerdict.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.activeRiskSymbol.collectAsStateWithLifecycle()
    val backfillSummaries by viewModel.backfillSummaries.collectAsStateWithLifecycle()
    val isBackfilling by viewModel.isBackfilling.collectAsStateWithLifecycle()

    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddModelForm by rememberSaveable { mutableStateOf(false) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
    ) {
        // نوار ۴ بخش صفحه تنظیمات یکپارچه
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to "⚡ ربات و استراتژی‌ها (${allItems.size})",
                1 to "💰 موجودی و ریسک ($5)",
                2 to "🛠️ پارامترهای ایچیموکو",
                3 to "🤖 هوش مصنوعی و دیتا",
            ).forEach { (idx, title) ->
                Pill(
                    text = title,
                    color = if (subTab == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { subTab = idx },
                )
            }
        }

        when (subTab) {
            0 -> {
                // ۱. تنظیمات ربات معامله‌گر خودکار و پایش بک‌گراند ۲۴/۷
                SectionCard(
                    title = "🤖 تنظیمات ربات معامله‌گر و پایش بک‌گراند ۲۴/۷",
                    subtitle = "محدودیت تعداد معاملات هم‌زمان و محدودیت باخت متوالی کاملاً برداشته شده است تا همه استراتژی‌ها با حداکثر سرعت تست شوند",
                    trailing = {
                        Pill(
                            text = if (settings.autoPaperTrading) "🟢 فعال" else "متوقف",
                            color = if (settings.autoPaperTrading) AurumColors.Green else AurumColors.TextMuted,
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("معامله خودکار", color = AurumColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(if (settings.autoPaperTrading) "ورود خودکار روشن" else "خاموش", color = if (settings.autoPaperTrading) AurumColors.Green else AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(
                                checked = settings.autoPaperTrading,
                                onCheckedChange = viewModel::setAutoPaperTrading,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("پایش بک‌گراند", color = AurumColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(if (settings.backgroundMonitor) "سرویس ۲۴/۷ روشن" else "خاموش", color = if (settings.backgroundMonitor) AurumColors.Cyan else AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(
                                checked = settings.backgroundMonitor,
                                onCheckedChange = viewModel::setBackgroundMonitor,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Pill("✅ بدون سقف تعداد معامله باز", AurumColors.Green)
                        Pill("✅ بدون توقف در باخت‌های متوالی", AurumColors.Cyan)
                    }
                }

                // ۲. انتخاب و فعال‌سازی هم‌زمان استراتژی‌ها
                SectionCard(
                    title = "🎯 مدیریت و فعال‌سازی هم‌زمان استراتژی‌ها",
                    subtitle = "هر ۵ استراتژی ایچیموکو و استراتژی‌های سفارشی را هم‌زمان روی تایم‌فریم‌های 15m و 5m فعال نگه دارید",
                ) {
                    ExclusiveStrategySelectorPage(
                        allItems = allItems,
                        selectedId = settings.selectedBitcoinStrategyId,
                        runAllSimultaneously = settings.runAllStrategiesSimultaneously,
                        enabledStrategyIds = settings.enabledStrategyIds,
                        executionEnabled = settings.autoPaperTrading,
                        showAddModelForm = showAddModelForm,
                        onToggleAddForm = { showAddModelForm = !showAddModelForm },
                        onToggleRunAll = viewModel::setRunAllStrategiesSimultaneously,
                        onToggleStrategyInMulti = viewModel::toggleStrategyEnabledInMultiMode,
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
                }
            }

            1 -> {
                // تنظیمات موجودی ۱۰۰,۰۰۰ دلار و ریسک ثابت ۵ دلار در هر معامله
                SectionCard(
                    title = "💰 تنظیمات موجودی (۱۰۰,۰۰۰$) و ریسک ثابت دلاری (۵$)",
                    subtitle = "هر باخت = -$${String.format(Locale.US, "%.2f", settings.fixedRiskUsd)} · هر برد = +$${String.format(Locale.US, "%.2f", settings.fixedRiskUsd)} × R:R",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatTile(
                            label = "موجودی فعلی حساب",
                            value = String.format(Locale.US, "$%,.2f", settings.accountBalance),
                            valueColor = AurumColors.Gold,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "ریسک ثابت هر معامله",
                            value = "-$${settings.fixedRiskUsd.toInt()} باخت / +$${settings.fixedRiskUsd.toInt()}×R:R برد",
                            valueColor = AurumColors.Cyan,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(
                        text = "انتخاب سریع مبلغ ریسک ثابت (ضرر در SL) برای هر معامله:",
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
                        FIXED_RISK_USD_PRESETS.forEach { usd ->
                            val selected = settings.useFixedDollarRisk && abs(settings.fixedRiskUsd - usd) < 0.01
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) AurumColors.Gold.copy(alpha = 0.22f) else AurumColors.SurfaceAlt)
                                    .border(1.dp, if (selected) AurumColors.Gold else AurumColors.Line, RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.saveRiskAndCapitalSettings(
                                            accountBalance = settings.accountBalance,
                                            initialCapital = settings.initialCapital,
                                            riskPercent = settings.riskPercent,
                                            dailyLossLimitPercent = 100.0,
                                            maxDrawdownLimitPercent = 100.0,
                                            targetCapital = settings.targetCapital,
                                            useFixedDollarRisk = true,
                                            fixedRiskUsd = usd,
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = "$${usd.toInt()} در هر معامله",
                                    color = if (selected) AurumColors.Gold else AurumColors.TextPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.saveRiskAndCapitalSettings(
                                accountBalance = 100_000.0,
                                initialCapital = 100_000.0,
                                riskPercent = 0.5,
                                dailyLossLimitPercent = 100.0,
                                maxDrawdownLimitPercent = 100.0,
                                targetCapital = 100_000.0,
                                useFixedDollarRisk = true,
                                fixedRiskUsd = 5.0,
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Gold,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(
                            text = "⚡ تنظیم فوری موجودی روی ۱۰۰,۰۰۰ دلار + هر باخت ۵ دلار",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                CapitalAndRiskLimitsCard(
                    settings = settings,
                    riskLimits = riskLimits,
                    onSaveSettings = { bal, initCap, riskPct, dailyLim, maxDd ->
                        viewModel.saveRiskAndCapitalSettings(
                            accountBalance = bal,
                            initialCapital = initCap,
                            riskPercent = riskPct,
                            dailyLossLimitPercent = dailyLim,
                            maxDrawdownLimitPercent = maxDd,
                            targetCapital = settings.targetCapital,
                            useFixedDollarRisk = settings.useFixedDollarRisk,
                            fixedRiskUsd = settings.fixedRiskUsd,
                        )
                    },
                )
            }

            2 -> {
                SectionCard(
                    title = "🛠️ کالیبراسیون پارامترهای استراتژی ایچیموکو",
                    subtitle = "تنظیم حداقل R:R، ضریب کشیدگی کیجون (ATR)، فیلترهای چیکو و کومو و حالت حفظ سود",
                ) {
                    BaseStrategyRefinementPanel(
                        config = settings.activeBitcoinStrategyConfig(),
                        onSaveConfig = { updated ->
                            viewModel.updateBaseStrategyConfig(updated)
                        },
                    )
                }
            }

            3 -> {
                AvalAiPreTradeGateCard(
                    settings = settings,
                    aiChecking = aiChecking,
                    lastVerdict = lastAiVerdict,
                    onToggleEnabled = viewModel::setAvalAiEnabled,
                    onSaveConfig = { key, model, url -> viewModel.saveAvalAiConfig(key, model, url, enableAfterSave = true) },
                )
                OfflineGapBackfillCard(
                    activeSymbol = activeSymbol,
                    summary = backfillSummaries[activeSymbol],
                    isBackfilling = isBackfilling,
                    onSyncGap = { hours -> viewModel.syncAllOpenAndActiveSymbolsGap(hoursOverride = hours, notifyOnSuccess = true) },
                )
            }
        }
    }
}
