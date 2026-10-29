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
import com.aurum.edge.core.BitcoinStrategyModel
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
            initiallyExpanded = false,
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
            initiallyExpanded = false,
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
            initiallyExpanded = false,
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

        // ── ۱-الف. درک زنده وضعیت هر بازار و تشخیص علت وقوع حرکت فعلی (Causal Market Attribution) ──
        SectionCard(
            title = "🌍 درک زنده وضعیت هر بازار و علت حرکت فعلی (Causal Market Attribution)",
            subtitle = "تشخیص لحظه‌ای این‌که در طلا، نقره، کریپتو، فارکس و داوجونز چه اتفاقی در جریان است و علت آن مربوط به چیست",
            initiallyExpanded = false,
        ) {
            val focusedReport = dualReports[assistantSymbol]?.primaryReport
            val liveAttr = remember(assistantSymbol, focusedReport, neuralState.liveMarketAttributions) {
                neuralState.liveMarketAttributions.firstOrNull { it.symbol == assistantSymbol }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                val attributionsToShow = if (neuralState.liveMarketAttributions.isNotEmpty()) {
                    neuralState.liveMarketAttributions
                } else {
                    emptyList()
                }

                if (attributionsToShow.isEmpty()) {
                    Text(
                        text = "در حال دریافت کندل‌ها برای استخراج علیت بازار؛ دکمه اسکن یا به‌روزرسانی اخبار را بزنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.TextMuted,
                    )
                } else {
                    val orderedList = attributionsToShow.sortedByDescending { it.symbol == assistantSymbol }
                    orderedList.forEach { attr ->
                        val isSelected = attr.symbol == assistantSymbol
                        val borderColor = if (isSelected) AurumColors.Gold else AurumColors.Cyan.copy(alpha = 0.35f)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AurumColors.SurfaceAlt)
                                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Pill(
                                    text = "${attr.symbol} [${attr.timeframeLabel}] · ${attr.categoryTitleFa}",
                                    color = AurumColors.Gold,
                                )
                                Pill(
                                    text = "اطمینان علیت: ${String.format(Locale.US, "%.1f%%", attr.causalConfidencePct)}",
                                    color = AurumColors.Green,
                                )
                            }
                            Text(
                                text = "🔍 علت اصلی اتفاق فعلی: ${attr.primaryCauseBadgeFa}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.Cyan,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "📊 وضعیت فعلی بازار: ${attr.marketRegimeStateFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                            Text(
                                text = "📌 چه اتفاقی در حال رخ دادن است: ${attr.whatIsHappeningFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextPrimary,
                            )
                            Text(
                                text = "🧠 این اتفاق مربوط به چیه: ${attr.whyItIsHappeningFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Gold,
                            )
                            Text(
                                text = "📰 اثر اخبار روی این بازار: ${attr.newsImpactSummaryFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                            Text(
                                text = "📚 متود جامع ۹ کتاب درایو: ${attr.unified9BookSummaryFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Green,
                            )
                            Text(
                                text = "🎯 دستور تاکتیکی شبکه عصبی: ${attr.recommendedTacticalActionFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Cyan,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // ── ۱-ب. هوش خبری کلان و تقویم اقتصادی متصل به شبکه عصبی (Live Macro & News Intelligence) ──
        SectionCard(
            title = "📰 هوش خبری کلان و تقویم اقتصادی متصل به شبکه عصبی",
            subtitle = "پایش شوک‌های خبری نرخ بهره، تورم (CPI)، اشتغال (NFP)، تقاضای پناهگاه امن طلا و جریان ETF کریپتو",
            initiallyExpanded = false,
        ) {
            val news = neuralState.latestNewsSnapshot
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        text = if (news.isHighImpactNewsWindow) "⚠️ پنجره خبر مهم فعال" else "✅ وضعیت خبری پایدار",
                        color = if (news.isHighImpactNewsWindow) AurumColors.Red else AurumColors.Green,
                    )
                    Pill(
                        text = "شدت شوک خبری: ${(news.shockIntensity * 100).toInt()}%",
                        color = AurumColors.Gold,
                    )
                }
                Text(
                    text = news.activeMacroCatalystFa,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                news.categoryNewsSummaryFa.values.forEach { catSum ->
                    Text(
                        text = "• $catSum",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
                news.headlines.forEach { h ->
                    val sentColor = when {
                        h.sentimentScore >= 0.10 -> AurumColors.Green
                        h.sentimentScore <= -0.10 -> AurumColors.Red
                        else -> AurumColors.Gold
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.8.dp, sentColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Pill(text = "${h.source} · ${h.impactLevel}", color = sentColor)
                            Pill(text = h.causeTagFa, color = AurumColors.Cyan)
                        }
                        Text(
                            text = h.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                    }
                }
                Button(
                    onClick = { viewModel.refreshNeuralMacroNewsNow() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Cyan,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("🔄 به‌روزرسانی اخبار و علیت بازار", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── ۱-پ. فاز تیونینگ خودکار پارامترها پس از آموزش (Post-Training Parameter Auto-Tuning Phase) ──
        SectionCard(
            title = "🎛️ مرحله تیونینگ خودکار پارامترها پس از آموزش (CEM + Bayesian Auto-Tuner)",
            subtitle = "کالیبراسیون خودکار پارامترهای استراتژی و گیت شبکه عصبی در پایان هر سیکل آموزشی برای شکار دقیق‌ترین معاملات سودده",
            initiallyExpanded = false,
        ) {
            val tuned = neuralState.autoTunedParameters
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    text = neuralState.currentTrainingPhaseBadgeFa,
                    color = AurumColors.Gold,
                )
                Text(
                    text = "🔬 متود تیونینگ روز: ${tuned.methodBadgeFa} (نسل #${tuned.tuningGeneration})",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatTile(
                        label = "حداقل R:R تیون‌شده",
                        value = String.format(Locale.US, "1:%.2f", tuned.minRiskReward),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "سقف کشیدگی کیجون",
                        value = String.format(Locale.US, "%.2f ATR", tuned.maxKijunExtensionAtr),
                        valueColor = AurumColors.Gold,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "اجماع ۹ کتاب درایو",
                        value = "${tuned.minDriveBooksConsensus}/9 کتاب",
                        valueColor = AurumColors.Cyan,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatTile(
                        label = "بافر امن استاپ",
                        value = String.format(Locale.US, "%.2f ATR", tuned.slBufferAtr),
                        valueColor = AurumColors.Gold,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "نرخ برد مورد انتظار",
                        value = String.format(Locale.US, "%.1f%%", tuned.expectedWinRatePct),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "کاهش خطای ورود",
                        value = String.format(Locale.US, "-%.1f%%", tuned.falsePositiveReductionPct),
                        valueColor = AurumColors.Cyan,
                        modifier = Modifier.weight(1f),
                    )
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
                        text = "📐 گزارش کالیبراسیون پارامترها توسط شبکه عصبی:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    tuned.parameterChangeLogFa.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                    }
                }
                Button(
                    onClick = { viewModel.runNeuralParameterAutoTuningNow() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Green,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "🎛️ اجرای تیونینگ خودکار پارامترها و ۱۶ ویژگی برای تمام ۱۶ استراتژی",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ── ۱-پ-۲. تیونینگ اختصاصی تمام ۱۶ ویژگی یادگرفته‌شده شبکه عصبی به تفکیک هر استراتژی ──
        SectionCard(
            title = "🧬 تیونینگ اختصاصی تمام ۱۶ ویژگی شبکه عصبی برای هر استراتژی",
            subtitle = "تنظیم مستقل وزن، ضریب اهمیت و آستانه هر ۱۶ ویژگی یادگرفته‌شده شبکه عصبی + پارامترهای ساختاری مختص هر یک از ۱۶ استراتژی",
            initiallyExpanded = false,
        ) {
            var selectedStratId by rememberSaveable {
                mutableStateOf(BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER.id)
            }
            var showParamsDrawer by rememberSaveable { mutableStateOf(false) }
            var showFeaturesDrawer by rememberSaveable { mutableStateOf(false) }

            val allStrategies = BitcoinStrategyModel.entries
            val selectedModel = BitcoinStrategyModel.fromId(selectedStratId)
            val stratProfile = neuralState.autoTunedParameters.perStrategyProfiles[selectedModel.id]

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "انتخاب استراتژی جهت مشاهده و تیونینگ اختصاصی ۱۶ ویژگی شبکه عصبی:",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    allStrategies.forEach { model ->
                        val isSel = model.id == selectedModel.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AurumColors.Gold else AurumColors.SurfaceAlt)
                                .border(
                                    0.8.dp,
                                    if (isSel) AurumColors.Gold else AurumColors.Border,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { selectedStratId = model.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = model.badgeFa,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) Color.Black else AurumColors.TextPrimary,
                            )
                        }
                    }
                }

                if (stratProfile != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.8.dp, AurumColors.Cyan.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill(text = stratProfile.strategyBadgeFa, color = AurumColors.Gold)
                            Pill(
                                text = "نسل تیونینگ #${stratProfile.tuningGeneration} · ${stratProfile.featureTunings.size} ویژگی فعال",
                                color = AurumColors.Cyan,
                            )
                        }
                        Text(
                            text = stratProfile.strategyTitleFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stratProfile.tuningSummaryFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Green,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatTile(
                                label = "حداقل R:R استراتژی",
                                value = String.format(Locale.US, "1:%.2f", stratProfile.minRiskReward),
                                valueColor = AurumColors.Green,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "سقف کیجون",
                                value = String.format(Locale.US, "%.2f ATR", stratProfile.maxKijunExtensionAtr),
                                valueColor = AurumColors.Gold,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "گیت برد عصبی",
                                value = String.format(Locale.US, "%.0f%%", stratProfile.neuralWinProbGatePct),
                                valueColor = AurumColors.Cyan,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Button(
                            onClick = { viewModel.runSingleStrategyAutoTuningNow(selectedModel.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Gold,
                                contentColor = Color.Black,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "⚡ تیونینگ خودکار اختصاصی «${selectedModel.badgeFa}» (هر ۱۶ ویژگی + پارامترها)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // کشویی ۱: تیونینگ پارامترهای ساختاری اختصاصی همین استراتژی (پیش‌فرض بسته)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.8.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showParamsDrawer = !showParamsDrawer },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "🛠️ تیونینگ پارامترهای ساختاری اختصاصی «${selectedModel.badgeFa}»",
                                style = MaterialTheme.typography.labelMedium,
                                color = AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (showParamsDrawer) "▲ بستن" else "▼ باز کردن",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Cyan,
                            )
                        }
                        if (showParamsDrawer) {
                            PerStrategyParamTuneRow(
                                labelFa = "حداقل R:R مجاز (قانون پایه ≥ 1:1.5)",
                                valueText = String.format(Locale.US, "1:%.2f", stratProfile.minRiskReward),
                                onMinus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        minRiskReward = (stratProfile.minRiskReward - 0.10).coerceAtLeast(1.50),
                                    )
                                },
                                onPlus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        minRiskReward = (stratProfile.minRiskReward + 0.10).coerceAtMost(3.00),
                                    )
                                },
                            )
                            PerStrategyParamTuneRow(
                                labelFa = "سقف فاصله مجاز از کیجون (ضد C-Clamp)",
                                valueText = String.format(Locale.US, "%.2f ATR", stratProfile.maxKijunExtensionAtr),
                                onMinus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        maxKijunExtensionAtr = (stratProfile.maxKijunExtensionAtr - 0.05).coerceAtLeast(1.30),
                                    )
                                },
                                onPlus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        maxKijunExtensionAtr = (stratProfile.maxKijunExtensionAtr + 0.05).coerceAtMost(2.30),
                                    )
                                },
                            )
                            PerStrategyParamTuneRow(
                                labelFa = "بافر امن حد ضرر ساختاری (SL Buffer)",
                                valueText = String.format(Locale.US, "%.2f ATR", stratProfile.slBufferAtr),
                                onMinus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        slBufferAtr = (stratProfile.slBufferAtr - 0.02).coerceAtLeast(0.16),
                                    )
                                },
                                onPlus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        slBufferAtr = (stratProfile.slBufferAtr + 0.02).coerceAtMost(0.45),
                                    )
                                },
                            )
                            PerStrategyParamTuneRow(
                                labelFa = "حداقل کارایی روند کافمن (ER14)",
                                valueText = String.format(Locale.US, "%.0f%%", stratProfile.minEfficiencyRatio * 100.0),
                                onMinus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        minEfficiencyRatio = (stratProfile.minEfficiencyRatio - 0.02).coerceAtLeast(0.15),
                                    )
                                },
                                onPlus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        minEfficiencyRatio = (stratProfile.minEfficiencyRatio + 0.02).coerceAtMost(0.40),
                                    )
                                },
                            )
                            PerStrategyParamTuneRow(
                                labelFa = "حداکثر نسبت شدوی مخالف کندل ورود",
                                valueText = String.format(Locale.US, "%.0f%%", stratProfile.maxAdverseWickRatio * 100.0),
                                onMinus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        maxAdverseWickRatio = (stratProfile.maxAdverseWickRatio - 0.03).coerceAtLeast(0.28),
                                    )
                                },
                                onPlus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        maxAdverseWickRatio = (stratProfile.maxAdverseWickRatio + 0.03).coerceAtMost(0.55),
                                    )
                                },
                            )
                            PerStrategyParamTuneRow(
                                labelFa = "گیت احتمال برد شبکه عصبی برای این استراتژی",
                                valueText = String.format(Locale.US, "%.0f%%", stratProfile.neuralWinProbGatePct),
                                onMinus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        neuralWinProbGatePct = (stratProfile.neuralWinProbGatePct - 2.0).coerceAtLeast(45.0),
                                    )
                                },
                                onPlus = {
                                    viewModel.tuneStrategyParametersNow(
                                        strategyId = selectedModel.id,
                                        neuralWinProbGatePct = (stratProfile.neuralWinProbGatePct + 2.0).coerceAtMost(75.0),
                                    )
                                },
                            )
                        }
                    }

                    // کشویی ۲: تیونینگ تک‌تک ۱۶ ویژگی یادگرفته‌شده شبکه عصبی مختص همین استراتژی (پیش‌فرض بسته)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(0.8.dp, AurumColors.Green.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFeaturesDrawer = !showFeaturesDrawer },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "🧠 تیونینگ تک‌تک ۱۶ ویژگی یادگرفته‌شده در «${selectedModel.badgeFa}»",
                                style = MaterialTheme.typography.labelMedium,
                                color = AurumColors.Green,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (showFeaturesDrawer) "▲ بستن" else "▼ باز کردن (۱۶ ویژگی)",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.Cyan,
                            )
                        }
                        if (showFeaturesDrawer) {
                            stratProfile.featureTunings.forEach { feat ->
                                var expandedFeat by rememberSaveable(selectedModel.id, feat.featureIndex) {
                                    mutableStateOf(false)
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AurumColors.Bg)
                                        .border(
                                            0.8.dp,
                                            if (feat.isManuallyTuned) AurumColors.Gold.copy(alpha = 0.6f) else AurumColors.Border,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { expandedFeat = !expandedFeat },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "#${feat.featureIndex + 1} ${feat.featureTitleFa}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AurumColors.TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Pill(
                                            text = "ضریب ${String.format(Locale.US, "%.2fx", feat.tuningMultiplier)} · وزن ${String.format(Locale.US, "%+.2f", feat.learnedWeight)}",
                                            color = if (feat.learnedWeight >= 0.0) AurumColors.Green else AurumColors.Gold,
                                        )
                                    }
                                    if (expandedFeat) {
                                        Text(
                                            text = feat.featureDescriptionFa,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AurumColors.TextSecondary,
                                        )
                                        PerStrategyParamTuneRow(
                                            labelFa = "ضریب تاثیر ویژگی در ${selectedModel.badgeFa}",
                                            valueText = String.format(Locale.US, "%.2fx (توجه %.1f%%)", feat.tuningMultiplier, feat.attentionSharePct),
                                            onMinus = {
                                                viewModel.tuneStrategyFeatureNow(
                                                    strategyId = selectedModel.id,
                                                    featureIndex = feat.featureIndex,
                                                    tuningMultiplier = (feat.tuningMultiplier - 0.10).coerceAtLeast(0.20),
                                                )
                                            },
                                            onPlus = {
                                                viewModel.tuneStrategyFeatureNow(
                                                    strategyId = selectedModel.id,
                                                    featureIndex = feat.featureIndex,
                                                    tuningMultiplier = (feat.tuningMultiplier + 0.10).coerceAtMost(2.50),
                                                )
                                            },
                                        )
                                        PerStrategyParamTuneRow(
                                            labelFa = "وزن یادگرفته‌شده شبکه عصبی (Learned Weight)",
                                            valueText = String.format(Locale.US, "%+.2f", feat.learnedWeight),
                                            onMinus = {
                                                viewModel.tuneStrategyFeatureNow(
                                                    strategyId = selectedModel.id,
                                                    featureIndex = feat.featureIndex,
                                                    learnedWeight = (feat.learnedWeight - 0.08).coerceAtLeast(-3.50),
                                                )
                                            },
                                            onPlus = {
                                                viewModel.tuneStrategyFeatureNow(
                                                    strategyId = selectedModel.id,
                                                    featureIndex = feat.featureIndex,
                                                    learnedWeight = (feat.learnedWeight + 0.08).coerceAtMost(3.50),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── ۱-ت. کالبدشکافی تریدی علل تارگت و استاپ (Trade Outcome Forensics) ──
        SectionCard(
            title = "🔬 کالبدشکافی تریدی علل تارگت (TP) و استاپ (SL) توسط هوش عصبی",
            subtitle = "تحلیل آماری عوامل اصلی موفقیت معاملات، دلایل اصلی استاپ خوردن و قوانین طلایی استخراج‌شده برای ترید",
            initiallyExpanded = false,
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

        // ── ۱-ج. ماتریس برتری ترید به تفکیک ۱۶ استراتژی (درایو جامع + ۱۵ ترکیب پایه) ──
        SectionCard(
            title = "🏆 ماتریس برتری ترید ۱۶ استراتژی (نرخ برد، Profit Factor و امید ریاضی E[R])",
            subtitle = "رتبه‌بندی تریدی تمام ۱۵ ترکیب ایچیموکو و استراتژی درایو جامع بر اساس بازدهی واقعی و امتیاز لبه ترید",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(
                        text = "همه (۱۶)",
                        color = if (matrixFilter == 0) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { matrixFilter = 0 },
                    )
                    Pill(
                        text = "📚 درایو جامع",
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
            initiallyExpanded = false,
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
                    val eval15 = rep15?.let { viewModel.evaluateReportWithNeuralEngine(it) }
                    val eval5 = rep5?.let { viewModel.evaluateReportWithNeuralEngine(it) }
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

@Composable
private fun PerStrategyParamTuneRow(
    labelFa: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(AurumColors.Bg)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = labelFa,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AurumColors.SurfaceAlt)
                    .border(0.8.dp, AurumColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onMinus() }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(text = "−", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AurumColors.Gold)
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AurumColors.SurfaceAlt)
                    .border(0.8.dp, AurumColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onPlus() }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(text = "+", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AurumColors.Green)
            }
        }
    }
}

