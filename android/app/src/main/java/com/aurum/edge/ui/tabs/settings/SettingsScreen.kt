package com.aurum.edge.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.notify.AlarmTonePreset
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
                kind?.let { "انتخاب صدای ${it.channelNameFa}" } ?: "انتخاب صدای هر ۳ آلارم معامله",
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
                        Pill("✅ اهرم ایمن ≤ 10x در همه نمادها", AurumColors.Gold)
                    }
                }

                // ۱.۳ شبکه عصبی خودآموز (FT-Transformer + SwiGLU + AdamW Online · ۱۰,۰۰۰ معامله)
                NeuralSelfLearningSettingsCard(
                    neuralState = neuralState,
                    onToggleForceActive = viewModel::setNeuralForceActiveBeforeWarmup,
                )

                // ۱.۵ سیستم ۳ آلارم صوتی، تنظیم ولوم، انتخاب ملودی و زنگ دلخواه از گوشی
                SectionCard(
                    title = "🔔 تنظیم صدای آلارم‌ها، ولوم، ملودی و انتخاب زنگ گوشی",
                    subtitle = "تنظیم دقیق بلندی صدا (۰ تا ۱۰۰٪)، پروفایل ملودی، ویبره و صدای اختصاصی برای ورود، حد سود (TP) و حد ضرر (SL)",
                    trailing = {
                        Switch(
                            checked = settings.notifyOnSignal,
                            onCheckedChange = viewModel::setNotifyOnSignal,
                        )
                    },
                ) {
                    // الف) اسلایدر و دکمه‌های تنظیم بلندی صدای آلارم (Volume 0%..100%)
                    val volPct = settings.alarmVolumePercent.coerceIn(0, 100)
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
                                text = "📳 ویبره",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                            Switch(
                                checked = settings.alarmVibrationEnabled,
                                onCheckedChange = viewModel::setAlarmVibrationEnabled,
                            )
                        }
                    }

                    Slider(
                        value = volPct.toFloat(),
                        onValueChange = { viewModel.setAlarmVolumePercent(it.roundToInt()) },
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
                        listOf(0, 25, 50, 75, 90, 100).forEach { pct ->
                            val selected = volPct == pct
                            Pill(
                                text = if (pct == 0) "🔇 بی‌صدا (0%)" else "🔊 $pct%",
                                color = if (selected) AurumColors.Gold else AurumColors.TextMuted,
                                modifier = Modifier.clickable { viewModel.setAlarmVolumePercent(pct) },
                            )
                        }
                    }

                    // ب) انتخاب پروفایل ملودی و نوع آلارم
                    Text(
                        text = "🎼 انتخاب سبک صدای آلارم:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    val activePreset = AlarmTonePreset.fromId(settings.alarmTonePreset)
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AlarmTonePreset.entries.forEach { preset ->
                            val isSelected = activePreset == preset
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) AurumColors.Gold.copy(alpha = 0.14f) else AurumColors.SurfaceAlt)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) AurumColors.Gold else AurumColors.Line,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .clickable { viewModel.setAlarmTonePreset(preset.id) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.titleFa,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) AurumColors.Gold else AurumColors.TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = preset.subtitleFa,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AurumColors.TextSecondary,
                                    )
                                }
                                Pill(
                                    text = if (isSelected) "✅ فعال" else "انتخاب",
                                    color = if (isSelected) AurumColors.Gold else AurumColors.TextMuted,
                                )
                            }
                        }
                    }

                    // ج) انتخاب زنگ اختصاصی از گوشی برای هر یک از ۳ آلارم یا هر ۳ با هم
                    Text(
                        text = "🎵 انتخاب زنگ دلخواه از گوشی برای هر آلارم:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                            .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            Triple(TradeAlarmKind.ENTRY, "🚀 صدای ورود به معامله", settings.entryAlarmSoundName.ifBlank { settings.alertSoundName.ifBlank { "ملودی استاندارد ورود" } }),
                            Triple(TradeAlarmKind.TAKE_PROFIT, "🎯 صدای حد سود (TP)", settings.tpAlarmSoundName.ifBlank { settings.alertSoundName.ifBlank { "ملودی استاندارد حد سود" } }),
                            Triple(TradeAlarmKind.STOP_LOSS, "🛑 صدای حد ضرر (SL)", settings.slAlarmSoundName.ifBlank { settings.alertSoundName.ifBlank { "ملودی استاندارد حد ضرر" } }),
                        ).forEach { (kind, labelFa, activeNameFa) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(labelFa, style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary, fontWeight = FontWeight.Bold)
                                    Text("فعلی: $activeNameFa", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Pill(
                                        text = "🎵 تغییر زنگ",
                                        color = AurumColors.Gold,
                                        modifier = Modifier.clickable { launchRingtonePickerFor(kind) },
                                    )
                                    Pill(
                                        text = "پیش‌فرض",
                                        color = AurumColors.TextMuted,
                                        modifier = Modifier.clickable { viewModel.setCustomAlarmSoundForKind(kind, "", "") },
                                    )
                                }
                            }
                        }
                    }

                    // د) دکمه‌های تست فوری هر ۳ آلارم با ولوم و زنگ فعلی
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = { viewModel.triggerTestTradeAlarm(TradeAlarmKind.ENTRY) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Cyan.copy(alpha = 0.2f),
                                contentColor = AurumColors.Cyan,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("⚡ تست ورود", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewModel.triggerTestTradeAlarm(TradeAlarmKind.TAKE_PROFIT) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Green.copy(alpha = 0.2f),
                                contentColor = AurumColors.Green,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("🎯 تست حد سود", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewModel.triggerTestTradeAlarm(TradeAlarmKind.STOP_LOSS) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Red.copy(alpha = 0.2f),
                                contentColor = AurumColors.Red,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("🛑 تست حد ضرر", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
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
                NeuralSelfLearningSettingsCard(
                    neuralState = neuralState,
                    onToggleForceActive = viewModel::setNeuralForceActiveBeforeWarmup,
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

@Composable
fun NeuralSelfLearningSettingsCard(
    neuralState: NeuralLearningStateSummary,
    onToggleForceActive: (Boolean) -> Unit,
) {
    SectionCard(
        title = "🧠 شبکه عصبی خودآموز پیشرفته (Self-Learning Neural Network)",
        subtitle = "معماری ${neuralState.architectureNameFa} — یادگیری مستمر با هر معامله در هر دسته (کریپتو، طلا/نقره، فارکس، شاخص)؛ اعمال خودکار فیلتر شرایط ممنوعه پس از ۱۰,۰۰۰ معامله",
        trailing = {
            Pill(
                text = if (neuralState.isActiveFilterEnabled) "🛡️ فیلتر فعال" else "🎓 در حال لرن (${neuralState.totalLearnedTrades}/10,000)",
                color = if (neuralState.isActiveFilterEnabled) AurumColors.Green else AurumColors.Cyan,
            )
        },
    ) {
        val progressFraction = (neuralState.totalLearnedTrades.toFloat() / neuralState.warmupTargetTrades.toFloat()).coerceIn(0f, 1f)
        Text(
            text = "پیشرفت فاز یادگیری تا ۱۰,۰۰۰ معامله: ${neuralState.totalLearnedTrades} از ${neuralState.warmupTargetTrades} معامله (${String.format(Locale.US, "%.2f%%", neuralState.warmupProgressPercent)})",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.Gold,
            fontWeight = FontWeight.Bold,
        )
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = if (neuralState.isWarmupCompleted) AurumColors.Green else AurumColors.Cyan,
            trackColor = AurumColors.SurfaceAlt,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "وضعیت فعلی شبکه",
                value = if (neuralState.isWarmupCompleted) "تکمیل ۱۰,۰۰۰ معامله" else "${neuralState.remainingWarmupTrades} معامله تا فعال‌سازی",
                valueColor = if (neuralState.isWarmupCompleted) AurumColors.Green else AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "الگوهای ممنوعه کشف‌شده",
                value = "${neuralState.discoveredNoTradeClustersCount} رژیم پرخطر",
                valueColor = if (neuralState.discoveredNoTradeClustersCount > 0) AurumColors.Red else AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
        }

        // نمایش آمار یادگیری به تفکیک هر ۴ دسته بازار
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            neuralState.categoryStats.forEach { cat ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                        .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${cat.categoryTitleFa} (${cat.learnedTrades} معامله لرن‌شده)",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = if (cat.learnedTrades > 0) "برد: ${String.format(Locale.US, "%.1f%%", cat.winRatePercent)}" else "آماده یادگیری",
                            color = if (cat.winRatePercent >= 50.0) AurumColors.Green else AurumColors.Gold,
                        )
                    }
                    Text(
                        text = "✅ بهترین رژیم: ${cat.bestConditionSummaryFa}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Green,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = "🚫 شرایط ممنوعه تشخیص‌داده‌شده: ${cat.worstNoTradeConditionSummaryFa}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cat.noTradeConditionCount > 0) AurumColors.Red else AurumColors.TextSecondary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "اعمال زودهنگام فیلتر شبکه عصبی (پیش از ۱۰,۰۰۰ معامله)",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (neuralState.forceActiveBeforeWarmup) {
                        "فیلتر هم‌اکنون فعال است و شرایط ممنوعه را مسدود می‌کند"
                    } else {
                        "پیش‌فرض: شبکه تا ۱۰,۰۰۰ معامله فقط لرن می‌کند و بعد از ۱۰,۰۰۰ معامله خودکار وارد عمل می‌شود"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
            Switch(
                checked = neuralState.forceActiveBeforeWarmup,
                onCheckedChange = onToggleForceActive,
            )
        }
    }
}

