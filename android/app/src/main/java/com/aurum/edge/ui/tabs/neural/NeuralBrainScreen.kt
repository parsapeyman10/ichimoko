package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Interval
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── ۱. کارت وضعیت کلی و بلوغ ۱۰,۰۰۰ معامله + ذخیره/بازخوانی از گوشی ──────
        SectionCard(
            title = "🧠 مغز شبکه عصبی خودآموز (FT-Attention + SwiGLU + AdamW)",
            subtitle = "گزارش زنده از تمام الگوها، قوانین، شرایط سودده و تله‌های ممنوعه‌ای که شبکه از معاملات یاد گرفته و فهمیده است",
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
                        label = "دقت آنلاین پیش‌بینی",
                        value = String.format(Locale.US, "%.1f%%", neuralState.runningAccuracyPct),
                        valueColor = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "تله‌های ممنوعه کشف‌شده",
                        value = "${neuralState.discoveredNoTradeClustersCount} رژیم",
                        valueColor = if (neuralState.discoveredNoTradeClustersCount > 0) AurumColors.Red else AurumColors.Cyan,
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
                                "✅ بلوغ ۱۰,۰۰۰ معامله تکمیل شده — فیلتر هوشمند فعال است"
                            } else {
                                "⏳ فاز یادگیری عمیق تا ۱۰,۰۰۰ معامله (${String.format(Locale.US, "%.2f%%", neuralState.warmupProgressPercent)})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (neuralState.isWarmupCompleted) AurumColors.Green else AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = "گام AdamW: ${neuralState.adamStepCount}",
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "اعمال فیلتر مسدودسازی شرایط پرخطر قبل از ۱۰,۰۰۰ معامله",
                                style = MaterialTheme.typography.labelMedium,
                                color = AurumColors.TextPrimary,
                            )
                            Text(
                                text = if (neuralState.isActiveFilterEnabled) {
                                    "فعال: شبکه عصبی همین الان از ورود در شرایط ممنوعه جلوگیری می‌کند"
                                } else {
                                    "یادگیری خالص (Shadow Mode): شبکه همه چیز را یاد می‌گیرد و گزارش می‌دهد اما تا ۱۰,۰۰۰ معامله مسدود نمی‌کند"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                        }
                        Switch(
                            checked = neuralState.forceActiveBeforeWarmup,
                            onCheckedChange = { viewModel.setNeuralForceActiveBeforeWarmup(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AurumColors.Gold),
                        )
                    }
                }

                // دکمه‌های ذخیره و بازخوانی حافظه شبکه عصبی و آرشیو در حافظه گوشی
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.backupNeuralBrainToPhoneNow() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Gold.copy(alpha = 0.22f),
                            contentColor = AurumColors.Gold,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("💾 ذخیره حافظه در گوشی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.restoreAllDataAndSnapshotsFromPhone() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("🔄 بازخوانی دیتا از گوشی", fontSize = 11.sp, color = AurumColors.Cyan)
                    }
                }
            }
        }

        // ── ۲. هر آنچه شبکه عصبی تا این لحظه یاد گرفته و فهمیده است (کشویی — پیش‌فرض باز) ──
        SectionCard(
            title = "🗣️ هر آنچه شبکه عصبی تا این لحظه یاد گرفته و فهمیده است",
            subtitle = "توضیح شفاف و خط‌به‌خط استنتاج‌های شبکه عصبی از معاملات، وزن‌ها و شرایط بازار",
            initiallyExpanded = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neuralState.learnedInsightsFeedFa.forEach { insight ->
                    val borderCol = when {
                        insight.startsWith("⛔") -> AurumColors.Red.copy(alpha = 0.55f)
                        insight.startsWith("✅") -> AurumColors.Green.copy(alpha = 0.55f)
                        insight.startsWith("🧠") -> AurumColors.Gold.copy(alpha = 0.55f)
                        else -> AurumColors.Cyan.copy(alpha = 0.45f)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.Bg)
                            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                            .padding(10.dp),
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

        // ── ۳. تفکیک یادگیری بر اساس ۴ دسته بازار + شرایط طلایی و ممنوعه (کشویی) ──
        SectionCard(
            title = "📊 دریافت و فهم شبکه به تفکیک ۴ دسته بازار (Crypto / Gold / Forex / Dow)",
            subtitle = "بهترین شرایط سودده و بدترین تله‌های ممنوعه (No-Trade) کشف‌شده در هر دسته",
            initiallyExpanded = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neuralState.categoryStats.forEach { cat ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.SurfaceAlt)
                            .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = cat.categoryTitleFa,
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.Gold,
                                fontWeight = FontWeight.Bold,
                            )
                            Pill(
                                text = "${cat.learnedTrades} معامله · برد ${String.format(Locale.US, "%.0f%%", cat.winRatePercent)}",
                                color = if (cat.winRatePercent >= 50.0) AurumColors.Green else AurumColors.Cyan,
                            )
                        }
                        Text(
                            text = "✅ بهترین رژیم سودده: ${cat.bestConditionSummaryFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Green,
                        )
                        Text(
                            text = "⛔ شرایط ممنوعه کشف‌شده (${cat.noTradeConditionCount} مورد): ${cat.worstNoTradeConditionSummaryFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cat.noTradeConditionCount > 0) AurumColors.Red else AurumColors.TextSecondary,
                        )
                    }
                }

                if (neuralState.noTradeClusters.isNotEmpty()) {
                    Text(
                        text = "🛑 فهرست کامل شرایط ممنوعه معامله (No-Trade Regimes) کشف‌شده توسط شبکه:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Red,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    neuralState.noTradeClusters.forEach { cluster ->
                        Text(
                            text = "• [${cluster.categoryCode}] ${cluster.conditionDescriptionFa} ← برد ${(cluster.posteriorWinProb * 100).toInt()}% (${cluster.winCount} برد / ${cluster.lossCount} باخت · میانگین ${String.format(Locale.US, "%+.2fR", cluster.avgR)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                    }
                }

                if (neuralState.winningClusters.isNotEmpty()) {
                    Text(
                        text = "🏆 فهرست شرایط طلایی و پرواگرایی سودده کشف‌شده:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Green,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    neuralState.winningClusters.take(8).forEach { cluster ->
                        Text(
                            text = "• [${cluster.categoryCode}] ${cluster.conditionDescriptionFa} ← برد ${(cluster.posteriorWinProb * 100).toInt()}% (${cluster.winCount} برد / ${cluster.lossCount} باخت · میانگین ${String.format(Locale.US, "%+.2fR", cluster.avgR)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                        )
                    }
                }
            }
        }

        // ── ۴. وزن‌های توجه (FT-Transformer Self-Attention) روی ۱۶ ویژگی ایچیموکو (کشویی) ──
        SectionCard(
            title = "⚖️ وزن‌های توجه شبکه عصبی روی ۱۶ ویژگی ایچیموکو (FT-Attention Weights)",
            subtitle = "نشان می‌دهد شبکه عصبی کدام ویژگی‌های چارت را مهم‌تر و کدام را خطرناک تشخیص داده است",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neuralState.featureInsights.forEach { feat ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurumColors.Bg)
                            .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = feat.titleFa,
                                style = MaterialTheme.typography.labelMedium,
                                color = AurumColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Pill(
                                text = "توجه: ${String.format(Locale.US, "%.1f%%", feat.attentionPercent)}",
                                color = AurumColors.Gold,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (feat.attentionPercent / 20.0).toFloat().coerceIn(0.05f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (feat.netWeightImpact >= 0.0) AurumColors.Green else AurumColors.Red,
                            trackColor = AurumColors.SurfaceAlt,
                        )
                        Text(
                            text = "${feat.impactLabelFa} · ${feat.explanationFa}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                }
            }
        }

        // ── ۵. نظر لحظه‌ای شبکه عصبی درباره شرایط فعلی ۱۵ نماد بازار (کشویی) ────
        SectionCard(
            title = "📡 نظر لحظه‌ای شبکه عصبی درباره شرایط فعلی ۱۵ نماد (15m + 5m)",
            subtitle = "پیش‌بینی احتمال برد و تشخیص تله‌های ممنوعه روی کندل‌های زنده همین لحظه",
            initiallyExpanded = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                V1Universe.allSymbols.forEach { sym ->
                    val dual = dualReports[sym]
                    val rep15 = dual?.report15m
                    val rep5 = dual?.report5m
                    if (rep15 != null) {
                        val eval15 = viewModel.evaluateReportWithNeuralEngine(rep15)
                        val eval5 = rep5?.let { viewModel.evaluateReportWithNeuralEngine(it) }
                        val isTrap = eval15.isNoTradeCondition || (eval5?.isNoTradeCondition == true)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AurumColors.Bg)
                                .border(
                                    1.dp,
                                    if (isTrap) AurumColors.Red.copy(alpha = 0.5f) else AurumColors.Green.copy(alpha = 0.35f),
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sym,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AurumColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Pill(
                                    text = if (isTrap) "⛔ هشدار تله / عدم معامله" else "🟢 شرایط سالم (${(eval15.predictedWinProb * 100).toInt()}% برد)",
                                    color = if (isTrap) AurumColors.Red else AurumColors.Green,
                                )
                            }
                            Text(
                                text = "15m: ${eval15.summaryFa}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextSecondary,
                            )
                            eval5?.let { e5 ->
                                Text(
                                    text = "5m: ${e5.summaryFa}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── ۶. دفترچه یادگیری معامله‌به‌معامله (Recent Trade Lessons) (کشویی) ───
        SectionCard(
            title = "📝 دفترچه درس‌های استخراج‌شده از معاملات اخیر (${neuralState.recentLessons.size} مورد)",
            subtitle = "توضیح دقیق اینکه شبکه عصبی از هر معامله بسته‌شده چه نکته‌ای یاد گرفت",
            initiallyExpanded = false,
        ) {
            if (neuralState.recentLessons.isEmpty()) {
                Text(
                    text = "به محض بسته شدن هر معامله (با حد سود، حد ضرر یا خروج هوشمند در سود)، درس استخراج‌شده و تغییر وزن‌های شبکه عصبی در این بخش نوشته می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    neuralState.recentLessons.forEach { lesson ->
                        val col = if (lesson.won) AurumColors.Green else AurumColors.Red
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AurumColors.Bg)
                                .border(1.dp, col.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${if (lesson.won) "✅ برد" else "🛑 باخت"} ${lesson.symbol} [${lesson.timeframeLabel}] (${String.format(Locale.US, "%+.2fR", lesson.achievedR)})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = col,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = formatDateTime(lesson.timestampMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.TextSecondary,
                                )
                            }
                            Text(
                                text = lesson.lessonLearnedFa,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }

        // ── ۷. آرشیو تصویری معاملات ایچیموکو در صفحه هوش عصبی (کشویی) ──────────
        TradeSnapshotArchiveGallerySection(
            viewModel = viewModel,
            initiallyExpanded = true,
        )
    }
}
