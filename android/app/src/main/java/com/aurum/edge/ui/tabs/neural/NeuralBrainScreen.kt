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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.ImprovementNotePriority
import com.aurum.edge.core.MasterIchimokuTradingAssistant
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.TradeSnapshotArchiveGallerySection
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

@Composable
fun NeuralBrainScreen(
    viewModel: AurumViewModel,
) {
    val neuralState by viewModel.neuralState.collectAsStateWithLifecycle()
    val dualReports by viewModel.dualSymbolStrategyReports.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pinnedNotes by MasterIchimokuTradingAssistant.pinnedNotes.collectAsStateWithLifecycle()

    var assistantSymbol by rememberSaveable { mutableStateOf(V1Universe.allSymbols.first()) }
    var assistantTfLabel by rememberSaveable { mutableStateOf(Interval.M15.label) }
    var matrixFilter by rememberSaveable { mutableIntStateOf(0) } // 0 = همه ۲۴ استراتژی, 1 = ۹ کتاب درایو, 2 = ۱۵ ترکیب پایه

    val assistantInterval = if (assistantTfLabel == Interval.M5.label) Interval.M5 else Interval.M15
    val coaching = remember(assistantSymbol, assistantInterval, dualReports) {
        MasterIchimokuTradingAssistant.analyzeSymbolWithMasterMethod(
            symbol = assistantSymbol,
            dualReport = dualReports[assistantSymbol],
            focusedInterval = assistantInterval,
        )
    }
    val improvementNotes = remember(trades, dualReports, settings.baseStrategyConfig, pinnedNotes) {
        MasterIchimokuTradingAssistant.generateActionableImprovementNotes(
            trades = trades,
            dualReports = dualReports,
            currentConfig = settings.baseStrategyConfig,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── ۰. دستیار ترید با بهترین متود ایچیموکو جهان (گوایچی هوسودا + ۹ کتاب درایو) ──────
        SectionCard(
            title = "🧭 دستیار ترید با بهترین متود ایچیموکو جهان (هوسودا + پاتل + ۹ کتاب درایو)",
            subtitle = "تحلیل ۴ ستون اصیل گوایچی هوسودا (تعادل، امواج I/V/N/P/S، چرخه زمان 9/17/26/33 و اهداف V/N/E/NT) + تله‌متری زنده ۹ کتاب درایو",
            initiallyExpanded = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // انتخاب نماد و تایم‌فریم دستیار
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    V1Universe.allSymbols.forEach { sym ->
                        Pill(
                            text = sym,
                            color = if (sym == assistantSymbol) AurumColors.Gold else AurumColors.TextMuted,
                            modifier = Modifier.clickable { assistantSymbol = sym },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        text = "⏱ تحلیل دستیار در تایم 15m",
                        color = if (assistantTfLabel == Interval.M15.label) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable { assistantTfLabel = Interval.M15.label },
                    )
                    Pill(
                        text = "⏱ تحلیل دستیار در تایم 5m",
                        color = if (assistantTfLabel == Interval.M5.label) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable { assistantTfLabel = Interval.M5.label },
                    )
                }

                if (coaching == null) {
                    Text(
                        text = "در حال دریافت کندل‌های $assistantSymbol برای تحلیل دستیار استاد ایچیموکو...",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.TextMuted,
                    )
                } else {
                    val gradeColor = when {
                        coaching.worldClassIchimokuScore >= 76 -> AurumColors.Green
                        coaching.worldClassIchimokuScore >= 60 -> AurumColors.Gold
                        else -> AurumColors.Red
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(1.dp, gradeColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill(
                                text = "${coaching.symbol} [${coaching.timeframeLabel}] · ${if (coaching.direction == SignalAction.BUY) "سوگیری خرید (LONG)" else "سوگیری فروش (SHORT)"}",
                                color = if (coaching.direction == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                            )
                            Pill(
                                text = "امتیاز متود جهانی: ${coaching.worldClassIchimokuScore}/100",
                                color = gradeColor,
                            )
                        }
                        Text(
                            text = coaching.masterGradeFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = gradeColor,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = coaching.masterVerdictFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.TextPrimary,
                        )
                        Text(
                            text = "🌊 نظریه امواج هوسودا (Hado Ron): ${coaching.hosodaWaveTypeFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Cyan,
                        )
                        Text(
                            text = "⏳ نظریه زمان هوسودا (Kihon Suchi): ${coaching.hosodaTimeCycleStatusFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Gold,
                        )
                        Text(
                            text = "🎯 نظریه اهداف قیمتی (Keisanchi): ${coaching.keisanchiTargetsSummaryFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Green,
                        )
                        Text(
                            text = "🤝 ${coaching.multiStrategyAgreementSummaryFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "📐 برنامه عملیاتی ۵ گامی ترید بر اساس متود استاد ایچیموکو:",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                        coaching.executionPlanStepsFa.forEach { step ->
                            Text(
                                text = "• $step",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "⚠️ هشدارها و نکات احتیاطی فعلی روی این چارت:",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.Red,
                            fontWeight = FontWeight.Bold,
                        )
                        coaching.activePitfallsFa.forEach { p ->
                            Text(
                                text = "• $p",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "📚 خلاصه تله‌متری زنده استراتژی‌های درایو روی ${coaching.symbol}:",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.Cyan,
                            fontWeight = FontWeight.Bold,
                        )
                        coaching.driveBooksTelemetryFa.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        // ── ۰-ب. لیست نکات قابل اصلاح و ارتقاء بعدی دستیار ایچیموکو ("بتونه نکاتی بگه که بشه بعدا درستش کرد") ──
        SectionCard(
            title = "🛠️ نکات قابل اصلاح و ارتقاء بعدی از نگاه دستیار ایچیموکو (${improvementNotes.size} نکته)",
            subtitle = "عیب‌یابی خودکار معاملات، استاپ‌ها و گلوگاه‌های ۲۴ استراتژی به همراه دستور دقیق اصلاح برای آپدیت‌های بعدی یا اعمال فوری",
            initiallyExpanded = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.copyAssistantImprovementNotes(improvementNotes) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Gold,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("📋 کپی و ذخیره همه نکات برای اصلاح بعدی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                improvementNotes.forEach { note ->
                    val prColor = when (note.priority) {
                        ImprovementNotePriority.CRITICAL -> AurumColors.Red
                        ImprovementNotePriority.HIGH -> AurumColors.Gold
                        ImprovementNotePriority.OPTIMIZATION -> AurumColors.Cyan
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(1.dp, prColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill(text = note.priority.badgeFa, color = prColor)
                            Pill(
                                text = if (note.isPinnedForNextUpdate) "📌 پین‌شده برای اصلاح بعدی" else "📌 پین کردن",
                                color = if (note.isPinnedForNextUpdate) AurumColors.Green else AurumColors.TextMuted,
                                modifier = Modifier.clickable { viewModel.togglePinAssistantImprovementNote(note) },
                            )
                        }
                        Text(
                            text = "${note.categoryFa} · ${note.targetStrategyTitleFa}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "🎯 دامنه هدف: ${note.targetScopeFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Cyan,
                        )
                        Text(
                            text = "🔍 مشکل/گلوگاه شناسایی‌شده: ${note.problemDetectedFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                        Text(
                            text = "📘 علت بر اساس متود استاد ایچیموکو: ${note.ichimokuMasterReasonFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AurumColors.Bg)
                                .border(0.8.dp, AurumColors.Green.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "🛠️ دستور دقیق اصلاح (برای اعمال الان یا در نسخه بعدی):",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Green,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = note.exactFixInstructionFa,
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                            if (note.suggestedSlBufferAtr != null || note.suggestedMaxKijunExtAtr != null || note.suggestedMinRr != null) {
                                OutlinedButton(
                                    onClick = { viewModel.applyAssistantNoteConfigPatch(note) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "⚡ اعمال خودکار این تنظیم پیشنهادی دستیار روی استراتژی",
                                        fontSize = 11.sp,
                                        color = AurumColors.Gold,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── ۱. کارت وضعیت کلی و بلوغ ۱۰,۰۰۰ معامله + ذخیره/بازخوانی از گوشی ──────
        SectionCard(
            title = "🧠 مغز شبکه عصبی ترید (FT-Attention + SwiGLU + AdamW)",
            subtitle = "سیستم هوش عصبی اختصاصی ترید ایچیموکو: یادگیری الگوهای سودده، تله‌های استاپ‌هانت، بهترین استراتژی هر نماد و سشن‌های طلایی",
            initiallyExpanded = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatTile(
                        label = "معاملات یادگرفته‌شده",
                        value = String.format(Locale.US, "%,d / %,d", neuralState.totalLearnedTrades, neuralState.warmupTargetTrades),
                        valueColor = AurumColors.Gold,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "ضریب سود (Profit Factor)",
                        value = String.format(Locale.US, "%.2f", neuralState.tradeForensics.profitFactor),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "امید ریاضی هر ترید",
                        value = String.format(Locale.US, "%+.2fR", neuralState.tradeForensics.expectancyPerTradeR),
                        valueColor = if (neuralState.tradeForensics.expectancyPerTradeR >= 0.0) AurumColors.Cyan else AurumColors.Red,
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.SurfaceAlt)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (neuralState.isWarmupCompleted) {
                                "✅ بلوغ ۱۰,۰۰۰ معامله تکمیل شده — فیلتر هوشمند ترید فعال است"
                            } else {
                                "⏳ فاز یادگیری عمیق تا ۱۰,۰۰۰ معامله (${String.format(Locale.US, "%.2f%%", neuralState.warmupProgressPercent)})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (neuralState.isWarmupCompleted) AurumColors.Green else AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = "دقت آنلاین: ${String.format(Locale.US, "%.1f%%", neuralState.runningAccuracyPct)}",
                            color = AurumColors.Cyan,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (neuralState.warmupProgressPercent / 100.0).toFloat().coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (neuralState.isWarmupCompleted) AurumColors.Green else AurumColors.Gold,
                        trackColor = AurumColors.Bg,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "فعال‌سازی زودهنگام فیلتر مسدودسازی تله‌های ضررده (قبل از ۱۰,۰۰۰ معامله)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = neuralState.forceActiveBeforeWarmup,
                            onCheckedChange = { viewModel.setNeuralForceActiveBeforeWarmup(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AurumColors.Green,
                                checkedTrackColor = AurumColors.Green.copy(alpha = 0.35f),
                            ),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.backupNeuralBrainToPhoneNow() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Gold,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("💾 ذخیره دانش شبکه در گوشی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.restoreAllDataAndSnapshotsFromPhone() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("📂 بازخوانی دیتا و آرشیو از گوشی", fontSize = 11.sp, color = AurumColors.Cyan)
                    }
                }
            }
        }

        // ── ۱-ب. کالبدشکافی تریدی علل تارگت و استاپ (Trade Outcome Forensics) ──
        SectionCard(
            title = "🔬 کالبدشکافی تریدی علل تارگت (TP) و استاپ (SL) توسط هوش عصبی",
            subtitle = "تحلیل آماری عوامل اصلی موفقیت معاملات، دلایل اصلی استاپ خوردن و قوانین طلایی استخراج‌شده برای ترید",
            initiallyExpanded = true,
        ) {
            val tf = neuralState.tradeForensics
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "⏱ مقایسه عملکرد تایم‌فریم‌ها: ${tf.bestTimeframeSummaryFa}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(AurumColors.SurfaceAlt)
                        .border(0.8.dp, AurumColors.Green.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "✅ ۴ عامل اصلی معاملات برنده (TP Drivers):",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Green,
                        fontWeight = FontWeight.Bold,
                    )
                    tf.topWinDriversFa.forEach { d ->
                        Text(text = "• $d", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(AurumColors.SurfaceAlt)
                        .border(0.8.dp, AurumColors.Red.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "⛔ ۴ علت اصلی استاپ خوردن معاملات (SL Forensics):",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                    )
                    tf.topLossCausesFa.forEach { c ->
                        Text(text = "• $c", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(AurumColors.SurfaceAlt)
                        .border(0.8.dp, AurumColors.Gold.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "📌 قوانین عملیاتی ترید استخراج‌شده توسط شبکه عصبی:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    tf.actionableTradingRulesFa.forEach { r ->
                        Text(text = r, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
                    }
                }
            }
        }

        // ── ۱-ج. ماتریس برتری ترید به تفکیک ۲۴ استراتژی (۹ کتاب درایو + ۱۵ ترکیب پایه) ──
        SectionCard(
            title = "🏆 ماتریس برتری ترید ۲۴ استراتژی (نرخ برد، Profit Factor و امید ریاضی E[R])",
            subtitle = "رتبه‌بندی تریدی تمام ۱۵ ترکیب ایچیموکو و ۹ کتاب PDF گوگل درایو بر اساس بازدهی واقعی و امتیاز لبه ترید",
            initiallyExpanded = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(
                        text = "همه (۲۴)",
                        color = if (matrixFilter == 0) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { matrixFilter = 0 },
                    )
                    Pill(
                        text = "📚 ۹ کتاب درایو",
                        color = if (matrixFilter == 1) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable { matrixFilter = 1 },
                    )
                    Pill(
                        text = "⚡ ۱۵ ترکیب پایه",
                        color = if (matrixFilter == 2) AurumColors.Green else AurumColors.TextMuted,
                        modifier = Modifier.clickable { matrixFilter = 2 },
                    )
                }

                val filteredMatrix = remember(neuralState.strategyEdgeMatrix, matrixFilter) {
                    when (matrixFilter) {
                        1 -> neuralState.strategyEdgeMatrix.filter { it.isDriveBookStrategy }
                        2 -> neuralState.strategyEdgeMatrix.filter { !it.isDriveBookStrategy }
                        else -> neuralState.strategyEdgeMatrix
                    }
                }

                filteredMatrix.forEach { item ->
                    val scoreColor = when {
                        item.edgeScore >= 75 -> AurumColors.Green
                        item.edgeScore >= 55 -> AurumColors.Gold
                        else -> AurumColors.Red
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.8.dp, scoreColor.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.strategyBadgeFa,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.isDriveBookStrategy) AurumColors.Cyan else AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            Pill(
                                text = "Edge: ${item.edgeScore}/100 · E[R]=${String.format(Locale.US, "%+.2fR", item.expectancyR)}",
                                color = scoreColor,
                            )
                        }
                        Text(
                            text = item.strategyTitleFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "معاملات: %d · برد: %.1f%% · PF: %.2f · بهترین تایم: %s · بهترین نماد: %s",
                                item.tradesCount,
                                item.winRatePercent,
                                item.profitFactor,
                                item.bestTimeframeLabel,
                                item.bestSymbol,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                        Text(
                            text = item.tradingVerdictFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = scoreColor,
                        )
                    }
                }
            }
        }

        // ── ۱-د. هوش سشن‌های معاملاتی (آسیا، لندن، هم‌پوشانی لندن-نیویورک، نیویورک) ──
        SectionCard(
            title = "🌍 هوش سشن‌های معاملاتی (Session Trading Edge)",
            subtitle = "عملکرد و استراتژی پیشنهادی شبکه عصبی در هر یک از ۴ سشن اصلی بازار جهانی",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neuralState.sessionEdges.forEach { ses ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.8.dp, AurumColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ses.sessionTitleFa,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            Pill(
                                text = "برد: ${ses.winRatePercent}% · ${String.format(Locale.US, "%+.2fR", ses.avgR)}",
                                color = AurumColors.Green,
                            )
                        }
                        Text(
                            text = "ساعت جهانی: ${ses.utcHoursLabel} · تعداد معاملات ثبت‌شده: ${ses.tradesCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Cyan,
                        )
                        Text(
                            text = "💡 استراتژی بهینه این سشن: ${ses.sessionAdviceFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                    }
                }
            }
        }

        // ── ۲. گزارش زنده «آنچه شبکه عصبی یاد گرفته و فهمیده است» ──────────────
        SectionCard(
            title = "💡 خلاصه آموخته‌ها و برداشت‌های شبکه عصبی به زبان ساده",
            subtitle = "توضیح کامل قواعدی که هوش عصبی از رفتار کندل‌ها، ایچیموکو و نتایج معاملات استخراج کرده است",
            initiallyExpanded = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neuralState.learnedInsightsFeedFa.forEach { insight ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.7.dp, AurumColors.Line, RoundedCornerShape(9.dp))
                            .padding(9.dp),
                    ) {
                        Text(
                            text = insight,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.TextPrimary,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }

        // ── ۳. وضعیت یادگیری به تفکیک ۴ دسته بازار (رمزارز، طلا/نقره، فارکس، شاخص) ──
        SectionCard(
            title = "📊 دانش شبکه به تفکیک ۴ دسته بازار (Crypto / Gold-Silver / Forex / Dow)",
            subtitle = "بهترین رژیم سودده و بدترین تله ممنوعه کشف‌شده در هر دسته دارایی",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neuralState.categoryStats.forEach { cat ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.8.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = cat.categoryTitleFa,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            Pill(
                                text = "${cat.learnedTrades} معامله · برد ${String.format(Locale.US, "%.1f%%", cat.winRatePercent)}",
                                color = if (cat.winRatePercent >= 50.0) AurumColors.Green else AurumColors.Cyan,
                            )
                        }
                        Text(
                            text = "✅ بهترین رژیم سودده: ${cat.bestConditionSummaryFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Green,
                        )
                        Text(
                            text = "⛔ پرخطرترین تله (No-Trade): ${cat.worstNoTradeConditionSummaryFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cat.noTradeConditionCount > 0) AurumColors.Red else AurumColors.TextSecondary,
                        )
                    }
                }
            }
        }

        // ── ۴. رژیم‌های ممنوعه ورود (No-Trade Regimes) و الگوهای طلایی سودده ──────
        SectionCard(
            title = "🛑 تله‌های ممنوعه (No-Trade) و 🎯 الگوهای طلایی کشف‌شده",
            subtitle = "خوشه‌های شرایطی که شبکه با آمار بیزین (Thompson Beta Posterior) دسته‌بندی کرده است",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "⛔ رژیم‌های ممنوعه ورود (${neuralState.noTradeClusters.size} مورد):",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Red,
                    fontWeight = FontWeight.Bold,
                )
                if (neuralState.noTradeClusters.isEmpty()) {
                    Text(
                        text = "هنوز خوشه ضررده پرتکراری ثبت نشده است؛ با بسته شدن معاملات، تله‌های بازار اینجا فهرست می‌شوند.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                    )
                } else {
                    neuralState.noTradeClusters.forEach { cl ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AurumColors.Red.copy(alpha = 0.10f))
                                .border(0.8.dp, AurumColors.Red.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                text = "⛔ ${cl.conditionDescriptionFa}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.Red,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "تعداد: %d معامله (%d برد / %d باخت) · احتمال برد بیزین: %.0f%% · میانگین بازده: %+.2fR",
                                    cl.totalCount,
                                    cl.winCount,
                                    cl.lossCount,
                                    cl.posteriorWinProb * 100.0,
                                    cl.avgR,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🎯 الگوهای طلایی سودده (${neuralState.winningClusters.size} مورد):",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Green,
                    fontWeight = FontWeight.Bold,
                )
                if (neuralState.winningClusters.isEmpty()) {
                    Text(
                        text = "در انتظار ثبت اولین معاملات برنده برای استخراج الگوهای طلایی.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                    )
                } else {
                    neuralState.winningClusters.forEach { cl ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AurumColors.Green.copy(alpha = 0.10f))
                                .border(0.8.dp, AurumColors.Green.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                text = "✅ ${cl.conditionDescriptionFa}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.Green,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "تعداد: %d معامله (%d برد / %d باخت) · احتمال برد بیزین: %.0f%% · میانگین بازده: %+.2fR",
                                    cl.totalCount,
                                    cl.winCount,
                                    cl.lossCount,
                                    cl.posteriorWinProb * 100.0,
                                    cl.avgR,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }

        // ── ۵. وزن و اهمیت ۱۶ ویژگی ورودی شبکه (FT-Transformer Self-Attention) ──
        SectionCard(
            title = "⚖️ کالبدشکافی ۱۶ ویژگی ایچیموکو در مغز شبکه (FT-Attention Weights)",
            subtitle = "کدام شروط ایچیموکو و پرایس‌اکشن بیشترین وزن را در تصمیم‌گیری شبکه عصبی دارند؟",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                neuralState.featureInsights.forEach { feat ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.SurfaceAlt)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = feat.titleFa,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Pill(
                                text = "توجه: ${feat.attentionPercent}%",
                                color = AurumColors.Gold,
                            )
                        }
                        Text(
                            text = feat.impactLabelFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (feat.netWeightImpact >= 0.0) AurumColors.Green else AurumColors.Red,
                        )
                        Text(
                            text = feat.explanationFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                }
            }
        }

        // ── ۶. ارزیابی زنده ۱۵ نماد توسط شبکه عصبی در همین لحظه ──────────────────
        SectionCard(
            title = "📡 ارزیابی لحظه‌ای ۱۵ نماد از نگاه شبکه عصبی (15m + 5m)",
            subtitle = "پیش‌بینی احتمال برد، امید ریاضی R و تشخیص تله ممنوعه برای شرایط فعلی هر نماد",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                V1Universe.allSymbols.forEach { sym ->
                    val dual = dualReports[sym]
                    val rep15 = dual?.report15m
                    val rep5 = dual?.report5m
                    val eval15 = rep15?.let { viewModel.evaluateSignalWithNeuralNet(it) }
                    val eval5 = rep5?.let { viewModel.evaluateSignalWithNeuralNet(it) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.SurfaceAlt)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = sym,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            val bad = (eval15?.isNoTradeCondition == true) || (eval5?.isNoTradeCondition == true)
                            Pill(
                                text = if (bad) "⛔ هشدار رژیم پرخطر" else "🟢 رژیم مجاز",
                                color = if (bad) AurumColors.Red else AurumColors.Green,
                            )
                        }
                        if (eval15 != null) {
                            Text(
                                text = "⏱ 15m: ${eval15.summaryFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                        }
                        if (eval5 != null) {
                            Text(
                                text = "⏱ 5m: ${eval5.summaryFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        // ── ۷. دفترچه درس‌های اخیر که شبکه از تک‌تک معاملات یاد گرفته است ─────────
        SectionCard(
            title = "📖 تاریخچه درس‌های استخراج‌شده از معاملات (${neuralState.recentLessons.size} درس اخیر)",
            subtitle = "هر معامله بسته‌شده چه درسی به شبکه عصبی داده و کدام وزن‌ها را تغییر داده است",
            initiallyExpanded = false,
        ) {
            if (neuralState.recentLessons.isEmpty()) {
                Text(
                    text = "هنوز معامله بسته‌شده‌ای در حافظه تجربه شبکه ثبت نشده است. به محض بسته شدن هر معامله (با سود یا ضرر)، درس استخراج‌شده در اینجا شرح داده می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextMuted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    neuralState.recentLessons.forEach { lesson ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AurumColors.SurfaceAlt)
                                .border(
                                    0.8.dp,
                                    if (lesson.won) AurumColors.Green.copy(alpha = 0.4f) else AurumColors.Red.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${lesson.symbol} [${lesson.timeframeLabel}] · ${if (lesson.won) "برد ✅" else "باخت ❌"} (${String.format(Locale.US, "%+.2fR", lesson.achievedR)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (lesson.won) AurumColors.Green else AurumColors.Red,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = formatDateTime(lesson.timestampMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.TextMuted,
                                )
                            }
                            Text(
                                text = lesson.lessonLearnedFa,
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }

        // ── ۸. گالری آرشیو تصویری معاملات بازشده (اسکرین‌شات ایچیموکو + SL/TP) ──
        TradeSnapshotArchiveGallerySection(
            viewModel = viewModel,
            initiallyExpanded = false,
        )
    }
}
