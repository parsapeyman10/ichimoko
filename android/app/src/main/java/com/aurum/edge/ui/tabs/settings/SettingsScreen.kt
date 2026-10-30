package com.aurum.edge.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.TradePlanGuard
import com.aurum.edge.core.TradePlanVerdict
import com.aurum.edge.notify.TradeAlarmKind
import com.aurum.edge.ui.components.BaseStrategyRefinementPanel
import com.aurum.edge.ui.components.ExclusiveStrategySelectorPage
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SelectableStrategyItem
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val FIXED_RISK_USD_PRESETS = listOf(5.0, 10.0, 25.0, 50.0, 100.0)

@Composable
fun SettingsScreen(
    viewModel: AurumViewModel,
    onOpenNeuralBrain: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val now by viewModel.clock.collectAsStateWithLifecycle()
    val aiChecking by viewModel.aiChecking.collectAsStateWithLifecycle()
    val lastAiVerdict by viewModel.lastAiVerdict.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.activeRiskSymbol.collectAsStateWithLifecycle()
    val backfillSummaries by viewModel.backfillSummaries.collectAsStateWithLifecycle()
    val isBackfilling by viewModel.isBackfilling.collectAsStateWithLifecycle()
    val neuralState by viewModel.neuralState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddModelForm by rememberSaveable { mutableStateOf(false) }
    var pendingRingtoneTarget by remember { mutableStateOf<TradeAlarmKind?>(null) }

    val showDesc = settings.showSettingsDescriptions
    fun descOrNull(text: String): String? = if (showDesc) text else null

    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val pickedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (pickedUri != null) {
                val ringtoneTitle = runCatching {
                    RingtoneManager.getRingtone(context, pickedUri)?.getTitle(context)
                }.getOrNull().orEmpty().ifBlank { pickedUri.lastPathSegment ?: "زنگ انتخابی" }
                viewModel.setCustomAlarmSoundForKind(
                    kind = pendingRingtoneTarget,
                    uri = pickedUri.toString(),
                    name = ringtoneTitle,
                )
            } else {
                viewModel.setCustomAlarmSoundForKind(
                    kind = pendingRingtoneTarget,
                    uri = "",
                    name = "",
                )
            }
        }
    }

    fun launchRingtonePickerFor(kind: TradeAlarmKind?) {
        pendingRingtoneTarget = kind
        val currentUriStr = when (kind) {
            TradeAlarmKind.ENTRY -> settings.entryAlarmSoundUri
            TradeAlarmKind.TAKE_PROFIT -> settings.tpAlarmSoundUri
            TradeAlarmKind.STOP_LOSS -> settings.slAlarmSoundUri
            null -> settings.alertSoundUri
        }
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TITLE,
                kind?.let { "انتخاب نوت آلارم ${it.channelNameFa}" } ?: "انتخاب نوت آلارم",
            )
            if (currentUriStr.isNotBlank()) {
                runCatching { Uri.parse(currentUriStr) }.getOrNull()?.let {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, it)
                }
            }
        }
        runCatching { ringtoneLauncher.launch(intent) }
    }

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

    // داوری پلن SL/TP/اهرم همهٔ نمادهای تحت رصد (کدام نماد سالم است و کدام از دالان نوسان بیرون زده)
    val symbolStrategyReports by viewModel.allSymbolStrategyReports.collectAsStateWithLifecycle()
    val symbolPlanAudits = remember(symbolStrategyReports) {
        TradePlanGuard.auditReports(symbolStrategyReports.values)
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
        // نوار بالای تنظیمات: ۴ بخش + دکمه کمرنگ مخفی/غیرمخفی کردن نوشته‌های توضیحی (پیش‌فرض: غیرمخفی)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    0 to "⚡ ربات، صدا و استراتژی‌ها",
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
            Spacer(Modifier.width(6.dp))
            // آپشن کمرنگ مخفی / غیرمخفی کردن نوشته‌های توضیحی تنظیمات (پیش‌فرض: غیرمخفی)
            Box(
                modifier = Modifier
                    .alpha(if (showDesc) 0.72f else 0.48f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(AurumColors.SurfaceAlt)
                    .border(0.8.dp, AurumColors.Line, RoundedCornerShape(999.dp))
                    .clickable { viewModel.setShowSettingsDescriptions(!showDesc) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (showDesc) "👁 توضیحات: آشکار" else "🙈 توضیحات: مخفی",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (showDesc) AurumColors.TextSecondary else AurumColors.TextMuted,
                    fontSize = 10.sp,
                )
            }
        }

        when (subTab) {
            0 -> {
                // ۱. تنظیمات ربات معامله‌گر خودکار و پایش بک‌گراند ۲۴/۷
                SectionCard(
                    title = "🤖 تنظیمات ربات معامله‌گر و پایش بک‌گراند ۲۴/۷",
                    subtitle = descOrNull("محدودیت تعداد معاملات هم‌زمان و محدودیت باخت متوالی کاملاً برداشته شده است تا همه استراتژی‌ها با حداکثر سرعت تست شوند"),
                    initiallyExpanded = false,
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
                                AnimatedVisibility(visible = showDesc) {
                                    Text(
                                        text = if (settings.autoPaperTrading) "ورود خودکار روشن" else "خاموش",
                                        color = (if (settings.autoPaperTrading) AurumColors.Green else AurumColors.TextMuted).copy(alpha = 0.78f),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
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
                                AnimatedVisibility(visible = showDesc) {
                                    Text(
                                        text = if (settings.backgroundMonitor) "سرویس ۲۴/۷ روشن" else "خاموش",
                                        color = (if (settings.backgroundMonitor) AurumColors.Cyan else AurumColors.TextMuted).copy(alpha = 0.78f),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
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
                        Pill("✅ اهرم ایمن ≤ 10x در همه نمادها", AurumColors.Gold)
                    }
                }

                // ۱.۳ کارت انتقال مستقیم به صفحه اختصاصی شبکه عصبی خودآموز
                NeuralDirectNavigationCard(
                    neuralState = neuralState,
                    showDescriptions = showDesc,
                    onOpenNeuralBrain = onOpenNeuralBrain,
                )

                // ۱.۵ تنظیمات صدا دقیقاً به سبک آلارم گوشی (Phone Clock Alarm UI) — ۱ اعلان و ۱ صدا برای هر مرحله
                PhoneStyleTradeAlarmsCard(
                    settings = settings,
                    showDescriptions = showDesc,
                    onToggleMaster = viewModel::setNotifyOnSignal,
                    onToggleStage = viewModel::setAlarmStageEnabled,
                    onPickNoteForStage = ::launchRingtonePickerFor,
                    onResetDefaultNote = { kind ->
                        viewModel.setCustomAlarmSoundForKind(kind, "", kind.defaultRingtoneTitle)
                    },
                    onTestStage = viewModel::triggerTestTradeAlarm,
                    onVolumeChange = viewModel::setAlarmVolumePercent,
                    onVibrationChange = viewModel::setAlarmVibrationEnabled,
                )

                // ۲. انتخاب و فعال‌سازی هم‌زمان استراتژی‌ها
                SectionCard(
                    title = "🎯 مدیریت و فعال‌سازی هم‌زمان ۱۶ استراتژی ایچیموکو",
                    subtitle = descOrNull("هر ۱۶ استراتژی ایچیموکو (۱۵ ترکیب ۴ کتاب + استراتژی درایو جامع) را هم‌زمان روی تایم‌فریم‌های 15m و 5m فعال نگه دارید"),
                    initiallyExpanded = false,
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
                    subtitle = descOrNull("هر باخت = -$${String.format(Locale.US, "%.2f", settings.fixedRiskUsd)} · هر برد = +$${String.format(Locale.US, "%.2f", settings.fixedRiskUsd)} × R:R"),
                    initiallyExpanded = false,
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

                    AnimatedVisibility(visible = showDesc) {
                        Text(
                            text = "انتخاب سریع مبلغ ریسک ثابت (ضرر در SL) برای هر معامله:",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary.copy(alpha = 0.78f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
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

                // داوری خودکار پلن حد ضرر / حد سود / اهرم برای همهٔ نمادها
                SectionCard(
                    title = "🛡️ داوری پلن حد ضرر · حد سود · اهرم (SL/TP/Leverage)",
                    subtitle = descOrNull("بررسی می‌کند ۱R هر نماد متناسب با نوسان همان تایم‌فریم باشد، هدف قابل‌دسترس باشد و اهرم لیکوییدیشن را از حد ضرر دور نگه دارد"),
                    initiallyExpanded = false,
                    trailing = {
                        val validCount = symbolPlanAudits.count { it.verdict == TradePlanVerdict.VALID }
                        Pill(
                            text = if (symbolPlanAudits.isEmpty()) "در انتظار اسکن" else "$validCount/${symbolPlanAudits.size} سالم",
                            color = if (symbolPlanAudits.isEmpty()) {
                                AurumColors.TextMuted
                            } else if (validCount == symbolPlanAudits.size) {
                                AurumColors.Green
                            } else {
                                AurumColors.Orange
                            },
                        )
                    },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val validCount = symbolPlanAudits.count { it.verdict == TradePlanVerdict.VALID }
                        val adjustedCount = symbolPlanAudits.count { it.verdict == TradePlanVerdict.ADJUSTED }
                        val rejectedCount = symbolPlanAudits.count { it.verdict == TradePlanVerdict.REJECTED }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile(
                                label = "✅ سالم",
                                value = validCount.toString(),
                                valueColor = AurumColors.Green,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "⚠️ نیازمند اصلاح",
                                value = adjustedCount.toString(),
                                valueColor = AurumColors.Orange,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "⛔ نامعتبر",
                                value = rejectedCount.toString(),
                                valueColor = AurumColors.Red,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (symbolPlanAudits.isEmpty()) {
                            Text(
                                text = "هنوز گزارش استراتژی نمادها محاسبه نشده است؛ پس از یک بار رصد/chart باز شدن، فهرست اینجا پر می‌شود.",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        } else {
                            symbolPlanAudits.forEach { audit ->
                                Text(
                                    text = "${audit.verdict.badgeFa} ${audit.lineFa()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (audit.verdict) {
                                        TradePlanVerdict.VALID -> AurumColors.Green
                                        TradePlanVerdict.ADJUSTED -> AurumColors.Orange
                                        TradePlanVerdict.REJECTED -> AurumColors.Red
                                    },
                                )
                            }
                            Text(
                                text = "دالان نمونه · " + TradePlanGuard.boundsFor("BTC/USDT", com.aurum.edge.domain.Interval.M15).describeFa(),
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextMuted,
                            )
                        }
                    }
                }
            }

            2 -> {
                SectionCard(
                    title = "🛠️ کالیبراسیون پارامترهای استراتژی ایچیموکو",
                    subtitle = descOrNull("تنظیم حداقل R:R، ضریب کشیدگی کیجون (ATR)، فیلترهای چیکو و کومو و حالت حفظ سود"),
                    initiallyExpanded = false,
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
                NeuralDirectNavigationCard(
                    neuralState = neuralState,
                    showDescriptions = showDesc,
                    onOpenNeuralBrain = onOpenNeuralBrain,
                )
                AvalAiPreTradeGateCard(
                    settings = settings,
                    aiChecking = aiChecking,
                    lastVerdict = lastAiVerdict,
                    onToggleEnabled = viewModel::setAvalAiEnabled,
                    onSaveConfig = { key, model, url -> viewModel.saveAvalAiConfig(key, model, url, enableAfterSave = true) },
                    onTestYesNo = viewModel::testAvalAiYesNoQuestion,
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

/**
 * کارت انتقال مستقیم از تنظیمات به صفحه اختصاصی «شبکه عصبی خودآموز» (`NeuralBrainScreen`)
 */
@Composable
fun NeuralDirectNavigationCard(
    neuralState: NeuralLearningStateSummary,
    showDescriptions: Boolean,
    onOpenNeuralBrain: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        AurumColors.Surface,
                        AurumColors.SurfaceAlt,
                    ),
                ),
            )
            .border(1.2.dp, AurumColors.Cyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clickable { onOpenNeuralBrain() }
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🧠 شبکه عصبی خودآموز و دستیار ترید",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        text = String.format(
                            java.util.Locale.US,
                            "%,d/%,d · %s",
                            neuralState.totalLearnedTrades,
                            neuralState.warmupTargetTrades,
                            when {
                                neuralState.isGateActive -> "گیت فعال"
                                neuralState.volumeConditionMet -> "منتظر اجازهٔ شما"
                                else -> "در حال یادگیری"
                            },
                        ),
                        color = if (neuralState.isGateActive) AurumColors.Green else AurumColors.Cyan,
                    )
                }
                Pill(
                    text = "ورود به صفحه شبکه عصبی ↗",
                    color = AurumColors.Gold,
                    modifier = Modifier.clickable { onOpenNeuralBrain() },
                )
            }
            AnimatedVisibility(visible = showDescriptions) {
                Text(
                    text = "شبکه عصبی صفحه اختصاصی خود را دارد؛ برای مشاهده آموخته‌ها، کالبدشکافی معاملات، ماتریس ۲۴ استراتژی و تنظیمات کامل مغز عصبی ضربه بزنید.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/**
 * بخش تنظیمات صدا دقیقاً به سبک ساعت و آلارم گوشی (`Phone Clock Alarm Style`):
 * - گرافیک مدرن، دینامیک و کاملاً منظم بدون هیچ شلوغی اضافه
 * - دقیقاً ۱ اعلان و ۱ صدا برای ورود (`ENTRY`)
 * - دقیقاً ۱ اعلان و ۱ صدا برای رسیدن به پروفیت تارگت (`TAKE_PROFIT`)
 * - دقیقاً ۱ اعلان و ۱ صدا برای خروج / حد ضرر (`STOP_LOSS`)
 */
@Composable
private fun PhoneStyleTradeAlarmsCard(
    settings: com.aurum.edge.core.AppSettings,
    showDescriptions: Boolean,
    onToggleMaster: (Boolean) -> Unit,
    onToggleStage: (TradeAlarmKind, Boolean) -> Unit,
    onPickNoteForStage: (TradeAlarmKind) -> Unit,
    onResetDefaultNote: (TradeAlarmKind) -> Unit,
    onTestStage: (TradeAlarmKind) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
) {
    val activeCount = listOf(
        settings.entryAlarmEnabled,
        settings.tpAlarmEnabled,
        settings.slAlarmEnabled,
    ).count { it }

    SectionCard(
        title = "⏰ آلارم‌های مراحل معامله (مدل آلارم گوشی · ۱ اعلان + ۱ صدا)",
        subtitle = if (showDescriptions) {
            "دقیقاً ۱ اعلان و ۱ صدای نوت انتخابی برای ورود، ۱ اعلان و ۱ صدا برای پروفیت تارگت و ۱ اعلان و ۱ صدا برای خروج"
        } else null,
        initiallyExpanded = false,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    text = if (settings.notifyOnSignal) "$activeCount/3 روشن" else "خاموش",
                    color = if (settings.notifyOnSignal && activeCount > 0) AurumColors.Green else AurumColors.TextMuted,
                )
                Switch(
                    checked = settings.notifyOnSignal,
                    onCheckedChange = onToggleMaster,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // کارت ۱: آلارم ورود به معامله (Entry)
            PhoneAlarmStageItemCard(
                stageNumber = "01",
                stageTitleFa = "ورود به معامله (Trade Entry)",
                stageSubtitleFa = "پخش دقیقاً ۱ اعلان + ۱ نوت در لحظه باز شدن پوزیشن جدید",
                activeNoteName = settings.entryAlarmSoundName.ifBlank { "Ding-Dong" },
                defaultNoteName = "Ding-Dong",
                accentColor = AurumColors.Cyan,
                enabled = settings.notifyOnSignal && settings.entryAlarmEnabled,
                showDescriptions = showDescriptions,
                onToggleEnabled = { onToggleStage(TradeAlarmKind.ENTRY, it) },
                onPickRingtone = { onPickNoteForStage(TradeAlarmKind.ENTRY) },
                onResetDefault = { onResetDefaultNote(TradeAlarmKind.ENTRY) },
                onPreviewAlarm = { onTestStage(TradeAlarmKind.ENTRY) },
            )

            // کارت ۲: آلارم رسیدن به پروفیت تارگت (Profit Target)
            PhoneAlarmStageItemCard(
                stageNumber = "02",
                stageTitleFa = "رسیدن به پروفیت تارگت (Profit Target)",
                stageSubtitleFa = "پخش دقیقاً ۱ اعلان + ۱ نوت در لحظه ثبت سود و رسیدن به تارگت (TP)",
                activeNoteName = settings.tpAlarmSoundName.ifBlank { "Red Dwarf" },
                defaultNoteName = "Red Dwarf",
                accentColor = AurumColors.Green,
                enabled = settings.notifyOnSignal && settings.tpAlarmEnabled,
                showDescriptions = showDescriptions,
                onToggleEnabled = { onToggleStage(TradeAlarmKind.TAKE_PROFIT, it) },
                onPickRingtone = { onPickNoteForStage(TradeAlarmKind.TAKE_PROFIT) },
                onResetDefault = { onResetDefaultNote(TradeAlarmKind.TAKE_PROFIT) },
                onPreviewAlarm = { onTestStage(TradeAlarmKind.TAKE_PROFIT) },
            )

            // کارت ۳: آلارم خروج معامله / حد ضرر (Exit / Stop-Loss)
            PhoneAlarmStageItemCard(
                stageNumber = "03",
                stageTitleFa = "خروج از معامله (Exit / Stop-Loss)",
                stageSubtitleFa = "پخش دقیقاً ۱ اعلان + ۱ نوت در لحظه بسته شدن در حد ضرر (SL)",
                activeNoteName = settings.slAlarmSoundName.ifBlank { "Latch" },
                defaultNoteName = "Latch",
                accentColor = AurumColors.Red,
                enabled = settings.notifyOnSignal && settings.slAlarmEnabled,
                showDescriptions = showDescriptions,
                onToggleEnabled = { onToggleStage(TradeAlarmKind.STOP_LOSS, it) },
                onPickRingtone = { onPickNoteForStage(TradeAlarmKind.STOP_LOSS) },
                onResetDefault = { onResetDefaultNote(TradeAlarmKind.STOP_LOSS) },
                onPreviewAlarm = { onTestStage(TradeAlarmKind.STOP_LOSS) },
            )

            // پنل یکپارچه بلندی صدای زنگ و ویبره آلارم گوشی
            val volPct = settings.alarmVolumePercent.coerceIn(0, 100)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AurumColors.SurfaceAlt)
                    .border(1.dp, AurumColors.Line, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🔊 بلندی صدای آلارم: $volPct%",
                        style = MaterialTheme.typography.labelLarge,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "📳 ویبره هم‌زمان",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                        Switch(
                            checked = settings.alarmVibrationEnabled,
                            onCheckedChange = onVibrationChange,
                        )
                    }
                }

                Slider(
                    value = volPct.toFloat(),
                    onValueChange = { onVolumeChange(it.roundToInt()) },
                    valueRange = 0f..100f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(25, 50, 75, 90, 100).forEach { pct ->
                        val selected = volPct == pct
                        Pill(
                            text = "🔊 $pct%",
                            color = if (selected) AurumColors.Gold else AurumColors.TextMuted,
                            modifier = Modifier.clickable { onVolumeChange(pct) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneAlarmStageItemCard(
    stageNumber: String,
    stageTitleFa: String,
    stageSubtitleFa: String,
    activeNoteName: String,
    defaultNoteName: String,
    accentColor: Color,
    enabled: Boolean,
    showDescriptions: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onPickRingtone: () -> Unit,
    onResetDefault: () -> Unit,
    onPreviewAlarm: () -> Unit,
) {
    val cardAlpha = if (enabled) 1f else 0.62f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (enabled) 0.14f else 0.04f),
                        AurumColors.SurfaceAlt,
                    ),
                ),
            )
            .border(
                width = if (enabled) 1.3.dp else 0.8.dp,
                color = if (enabled) accentColor.copy(alpha = 0.65f) else AurumColors.Line,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ردیف بالا: شماره کلاک آلارم + عنوان مرحله + سوییچ روشن/خاموش آلارم گوشی
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.20f))
                        .border(1.dp, accentColor, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stageNumber,
                        style = MaterialTheme.typography.titleMedium,
                        color = accentColor,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stageTitleFa,
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(
                            text = "🎵 نوت: $activeNoteName",
                            color = accentColor,
                            modifier = Modifier.clickable { onPickRingtone() },
                        )
                        Pill(
                            text = "۱ اعلان + ۱ صدا",
                            color = AurumColors.Gold,
                        )
                    }
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accentColor,
                    checkedTrackColor = accentColor.copy(alpha = 0.32f),
                ),
            )
        }

        AnimatedVisibility(visible = showDescriptions) {
            Text(
                text = stageSubtitleFa,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary.copy(alpha = 0.72f),
            )
        }

        // نوار دکمه‌های انتخاب نوت از گوشی، پخش تست و بازگشت به نوت پیش‌فرض
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.Bg)
                    .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .clickable { onPickRingtone() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "🎵 انتخاب نوت از گوشی",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.18f))
                    .border(1.dp, accentColor, RoundedCornerShape(10.dp))
                    .clickable { onPreviewAlarm() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▶ تست آلارم",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!activeNoteName.equals(defaultNoteName, ignoreCase = true)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Bg)
                        .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                        .clickable { onResetDefault() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "پیش‌فرض ($defaultNoteName)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                    )
                }
            }
        }
    }
}
