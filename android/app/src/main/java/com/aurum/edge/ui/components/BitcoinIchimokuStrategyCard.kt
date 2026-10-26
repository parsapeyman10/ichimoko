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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.CustomStrategySlot
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.AurumViewModel
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

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
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val report by viewModel.btcStrategyReport.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningBtcStrategy.collectAsStateWithLifecycle()
    val aiChecking by viewModel.aiChecking.collectAsStateWithLifecycle()

    var showAllModels by rememberSaveable { mutableStateOf(true) }
    var showTuneBasePanel by rememberSaveable { mutableStateOf(false) }
    var showAddModelForm by rememberSaveable { mutableStateOf(false) }

    val selectedId = settings.selectedBitcoinStrategyId
    val executionEnabled = settings.autoPaperTrading
    val activeTitle = settings.activeBitcoinStrategyTitleFa()
    val currentConfig = settings.activeBitcoinStrategyConfig()

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
                authorRefFa = "مبتنی بر ${BitcoinStrategyModel.fromId(c.baseModelId).titleFa}",
                summaryFa = c.summaryFa,
                isCustom = true,
            )
        }
        builtIns + customs
    }

    SectionCard(
        title = "⚡ موتور استراتژی ایچیموکوی بیت‌کوین (BTC/USDT)",
        subtitle = "انتخابگر انحصاری متودها — فقط یک استراتژی در هر لحظه قابل اجراست",
        trailing = {
            Pill(
                text = if (executionEnabled) "🟢 ۱ متود فعال" else "⚪ متوقف",
                color = if (executionEnabled) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        // ── ۱. بنر وضعیت استراتژی فعال فعلی و کلید روشن/خاموش ─────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    (if (executionEnabled) AurumColors.Green else AurumColors.Gold).copy(alpha = 0.45f),
                    RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "استراتژی انتخاب‌شده فعلی (فقط روی BTC/USDT):",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                    )
                    Text(
                        text = activeTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AurumColors.Gold,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = if (executionEnabled) {
                            "اجرای خودکار روشن است؛ بقیه استراتژی‌ها غیرفعال هستند."
                        } else {
                            "اجرای خودکار خاموش است؛ با روشن کردن کلید، فقط همین استراتژی اجرا می‌شود."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = executionEnabled,
                    onCheckedChange = viewModel::setAutoPaperTrading,
                )
            }

            Text(
                text = "🔒 قانون انحصاری سیستم: هر زمان استراتژی دیگری را از لیست زیر فعال کنید، استراتژی قبلی به‌طور خودکار غیرفعال می‌شود و فقط متود جدید اجرا خواهد شد.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ── ۲. لیست انتخاب انحصاری استراتژی‌ها (Single-Active Selector) ───────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "📋 لیست استراتژی‌ها (${allItems.size} مدل ثبت‌شده)",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    text = if (showAllModels) "بستن لیست ▲" else "انتخاب استراتژی ▼",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { showAllModels = !showAllModels },
                )
                Pill(
                    text = if (showAddModelForm) "بستن فرم ✕" else "➕ افزودن مدل جدید",
                    color = AurumColors.Gold,
                    modifier = Modifier.clickable { showAddModelForm = !showAddModelForm },
                )
            }
        }

        if (showAddModelForm) {
            AddCustomStrategyModelPanel(
                onAdd = { title, summary, baseModel ->
                    viewModel.addCustomStrategyModel(title, summary, baseModel, activateImmediately = true)
                    showAddModelForm = false
                },
            )
        }

        if (showAllModels) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                                RoundedCornerShape(12.dp),
                            )
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.activateSingleBitcoinStrategy(item.id, enableExecution = true)
                            }
                            .padding(11.dp),
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
                                // Exclusive Radio Indicator Dot
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isRunning -> AurumColors.Green
                                                isSelected -> AurumColors.Gold
                                                else -> Color.Transparent
                                            },
                                        )
                                        .border(
                                            2.dp,
                                            if (isSelected) AurumColors.TextPrimary else AurumColors.TextMuted,
                                            CircleShape,
                                        ),
                                )
                                Column {
                                    Text(
                                        text = item.titleFa,
                                        style = MaterialTheme.typography.bodyMedium,
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

                            Pill(
                                text = when {
                                    isRunning -> "🟢 فعال"
                                    isSelected -> "انتخاب‌شده (متوقف)"
                                    else -> "غیرفعال · انتخاب"
                                },
                                color = when {
                                    isRunning -> AurumColors.Green
                                    isSelected -> AurumColors.Gold
                                    else -> AurumColors.TextMuted
                                },
                                modifier = Modifier.clickable {
                                    if (isRunning) {
                                        viewModel.setAutoPaperTrading(false)
                                    } else {
                                        viewModel.activateSingleBitcoinStrategy(item.id, enableExecution = true)
                                    }
                                },
                            )
                        }

                        Text(
                            text = item.summaryFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )

                        if (item.isCustom) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Pill(
                                    text = "🗑️ حذف این مدل سفارشی",
                                    color = AurumColors.Red,
                                    modifier = Modifier.clickable {
                                        viewModel.deleteCustomStrategyModel(item.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── ۳. دکمه باز و بسته کردن پنل ترمیم «استراتژی بیس» ──────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { showTuneBasePanel = !showTuneBasePanel },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (showTuneBasePanel) "بستن پنل ترمیم استراتژی بیس ▲"
                    else "🛠️ ترمیم و تنظیم پارامترهای استراتژی بیس (${currentConfig.macroIntervalLabel}/${currentConfig.strategyIntervalLabel}/${currentConfig.triggerIntervalLabel})",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (showTuneBasePanel) {
            BaseStrategyRefinementPanel(
                config = currentConfig,
                onSaveConfig = { updated ->
                    viewModel.updateBaseStrategyConfig(updated)
                    showTuneBasePanel = false
                },
            )
        }

        // ── ۴. رادار زنده ۳ تایم‌فریمی و چک‌لیست ۶ شرطی بیت‌کوین ────────────────
        Spacer(modifier = Modifier.height(10.dp))
        LiveBtcStrategyReportBox(
            report = report,
            isScanning = isScanning,
            aiChecking = aiChecking,
            avalAiEnabled = settings.avalAiEnabled,
            accountBalance = settings.accountBalance,
            riskPercent = settings.riskPercent,
            onScanNow = { viewModel.scanAndEvaluateBitcoinStrategyNow(userInitiated = true) },
            onExecuteSignalTrade = { evalReport ->
                val entry = evalReport.suggestedEntry ?: return@LiveBtcStrategyReportBox
                val sl = evalReport.suggestedStopLoss ?: return@LiveBtcStrategyReportBox
                val tp = evalReport.primaryTakeProfit ?: return@LiveBtcStrategyReportBox
                onApplySignalToCalculator?.invoke(entry, sl, tp, evalReport.action)
                val calc = RiskAndCalendarEngine.calculatePosition(
                    symbolInput = IchimokuStrategyEngine.BTC_SYMBOL,
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
                        note = "⚡ ${evalReport.strategyTitleFa} · ${evalReport.triggerType.titleFa}",
                        strategyContext = evalReport.toAiStrategyContext(),
                    )
                }
            },
        )
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
            .padding(top = 8.dp)
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
            .border(1.dp, AurumColors.Gold.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "➕ تعریف مدل استراتژی جدید در لیست انحصاری",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = AurumColors.Gold,
        )
        Text(
            text = "با ذخیره و فعال‌سازی این مدل، استراتژی قبلی خودکار غیرفعال شده و این مدل روی BTC/USDT فعال می‌شود.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
        )
        OutlinedTextField(
            value = titleInput,
            onValueChange = { titleInput = it },
            label = { Text("نام استراتژی (مثلاً: استراتژی شخصی ۱)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = summaryInput,
            onValueChange = { summaryInput = it },
            label = { Text("توضیح یا قواعد کلیدی مدل (اختیاری)") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "موتور پایه محاسباتی:",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
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
            colors = ButtonDefaults.buttonColors(
                containerColor = AurumColors.Gold,
                contentColor = Color.Black,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("✅ افزودن به لیست و فعال‌سازی انحصاری", fontWeight = FontWeight.Bold)
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
            .padding(top = 8.dp)
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
            .border(1.dp, AurumColors.Cyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "🛠️ ترمیم و شخصی‌سازی «استراتژی بیس» (Base Strategy)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = AurumColors.Cyan,
        )

        Text("ترکیب ۳ تایم‌فریم (کلان / استراتژی / ماشه ورود):", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tfPresets.forEach { (label, _) ->
                Pill(
                    text = label,
                    color = if (tfTriplet == label) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { tfTriplet = label },
                )
            }
        }

        Text("حداقل ریسک به ریوارد مجاز (Min R:R):", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1.2, 1.5, 2.0, 2.5).forEach { v ->
                Pill(
                    text = "1:$v",
                    color = if (minRr == v) AurumColors.Green else AurumColors.TextMuted,
                    modifier = Modifier.clickable { minRr = v },
                )
            }
        }

        Text("حداکثر فاصله مجاز از کیجون‌سن (فیلتر کش لاستیکی به ضریب ATR):", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1.4, 1.8, 2.2, 2.8).forEach { v ->
                Pill(
                    text = "${v}x ATR",
                    color = if (maxKijunExt == v) AurumColors.Cyan else AurumColors.TextMuted,
                    modifier = Modifier.clickable { maxKijunExt = v },
                )
            }
        }

        RuleToggleRow("الزام هم‌جهتی تایم‌فریم کلان (ابر بالاتر)", requireHtf) { requireHtf = it }
        RuleToggleRow("الزام فضای باز چیکو (Chikou Open Space)", requireChikou) { requireChikou = it }
        RuleToggleRow("الزام شیب هم‌جهت ابر آینده (Future Kumo)", requireFutureKumo) { requireFutureKumo = it }
        RuleToggleRow("ماشه ۱: ورود در پولبک به تعادل (Kijun/Tenkan/S-Point)", enablePullback) { enablePullback = it }
        RuleToggleRow("ماشه ۲: ورود در شکست کومو با عبور از سقف/کف چیکو", enableBreakout) { enableBreakout = it }
        RuleToggleRow("حالت حفظ سود (Preserve Mode منش پاتل + چرخه زمانی هوسودا)", enablePreserve) { enablePreserve = it }

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
                        enablePreserveMode = maxOf(enablePreserve, false),
                    ),
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = AurumColors.Cyan,
                contentColor = Color.Black,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("ذخیره تغییرات استراتژی بیس و بازسنجی BTC/USDT", fontWeight = FontWeight.Bold)
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

@Composable
private fun LiveBtcStrategyReportBox(
    report: StrategyEvaluationReport?,
    isScanning: Boolean,
    aiChecking: Boolean,
    avalAiEnabled: Boolean,
    accountBalance: Double,
    riskPercent: Double,
    onScanNow: () -> Unit,
    onExecuteSignalTrade: (StrategyEvaluationReport) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "📊 رادار زنده BTC/USDT با استراتژی فعال",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
                if (report != null) {
                    Text(
                        text = "قیمت لحظه‌ای بیت‌کوین: $${formatPrice(report.livePrice)} · تایم‌فریم‌ها: ${report.macroInterval.label}/${report.strategyInterval.label}/${report.triggerInterval.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
            }
            Pill(
                text = if (isScanning) "در حال اسکن..." else "🔄 بازسنجی فوری",
                color = AurumColors.Cyan,
                modifier = Modifier.clickable(enabled = !isScanning) { onScanNow() },
            )
        }

        if (isScanning || aiChecking) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AurumColors.Gold,
            )
        }

        if (report == null) {
            Text(
                text = "در حال واکشی کندل‌های مولتی‌تایم‌فریم بیت‌کوین (4H / 1H / 15m)...",
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(text = report.readiness.labelFa, color = statusColor)
            Pill(text = "امتیاز همگرایی: ${String.format(Locale.US, "%.0f%%", report.confidence)}", color = AurumColors.Gold)
        }

        Text(
            text = report.summaryFa,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            fontWeight = FontWeight.SemiBold,
        )

        // 6-Condition Checklist
        if (report.conditions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "چک‌لیست ۶ شرطی استراتژی فعال:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
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

        // Hosoda Wave Targets & Suggested SL/TP
        if (report.suggestedEntry != null && report.suggestedStopLoss != null && report.primaryTakeProfit != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "📐 سطوح محاسبه‌شده ایچیموکو و اهداف موجی هوسودا (Keisanchi):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Cyan,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("ورود: $${formatPrice(report.suggestedEntry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text("حد ضرر (SL کیجون): $${formatPrice(report.suggestedStopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                    Text("R:R = 1:${report.riskReward ?: 0.0}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
                }
                report.waveProjection?.let { w ->
                    Text(
                        text = "امواج هوسودا: TP1(V)=$${formatPrice(w.targetV)} · TP2(N)=$${formatPrice(w.targetN)} · TP3(E)=$${formatPrice(w.targetE)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Green,
                    )
                    Text(
                        text = "چرخه زمانی هوسودا (Jikan Ron): کندل شماره ${w.barsSinceWaveStart} موج (نزدیک‌ترین عدد پایه: ${w.nearestTimeCycleNumber}${if (w.isNearTimeCycleChangeDay) " ⚠️ روز چرخش زمانی!" else ""})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (w.isNearTimeCycleChangeDay) AurumColors.Gold else AurumColors.TextSecondary,
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
                                "🤖 ثبت معامله ${if (report.action == SignalAction.BUY) "خرید" else "فروش"} BTC/USDT (با تایید AvalAI)"
                            } else {
                                "⚡ ثبت فوری معامله ${if (report.action == SignalAction.BUY) "خرید" else "فروش"} BTC/USDT در ژورنال"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
