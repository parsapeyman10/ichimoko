package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.ui.AurumViewModel
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

/**
 * کارت «معامله‌گر هوشمند» — همان چیزی که کاربر خواست: نه دستیارِ توضیح‌دهنده، بلکه سیستمی که
 * شرایط نوظهور را پیش از سیگنال تشخیص می‌دهد، نکتهٔ قابل اصلاح را خودش به تغییر پارامتر تبدیل
 * می‌کند و بودجهٔ ریسک را بر پایهٔ لبهٔ اندازه‌گیری‌شده توزیع می‌کند.
 *
 * سه بخش دارد و همه از موتور معامله‌گر (`AiTraderEngine`) می‌آیند؛ UI هیچ محاسبهٔ موازی ندارد:
 * ۱. پیش‌بینی خبری نمادها · ۲. اقدام‌های خودترمیمی · ۳. ماتریس برتری
 */
@Composable
fun AiTraderDashboardSection(
    viewModel: AurumViewModel,
    initiallyExpanded: Boolean = true,
) {
    val forecasts by viewModel.aiTraderForecasts.collectAsStateWithLifecycle()
    val healings by viewModel.aiTraderHealings.collectAsStateWithLifecycle()
    val matrix by viewModel.aiTraderMatrix.collectAsStateWithLifecycle()
    val summary by viewModel.aiTraderSummaryFa.collectAsStateWithLifecycle()
    val memory by viewModel.aiTraderMemory.collectAsStateWithLifecycle()

    val actionable = forecasts.filter { it.isActionable }

    SectionCard(
        title = "🤖 معامله‌گر هوشمند (نه دستیار)",
        subtitle = "شرایط نوظهور هر نماد پیش از رسیدن به سیگنال · اصلاح خودکار نکات · توزیع بودجه بر پایهٔ لبهٔ اندازه‌گیری‌شده",
        onCopy = { viewModel.copyAiTraderLessons() },
        onCopyLabel = "📋 کپی درس‌ها",
        initiallyExpanded = initiallyExpanded,
        trailing = {
            Pill(
                text = "${actionable.size} آماده",
                color = if (actionable.isNotEmpty()) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatTile(
                label = "نماد در آستانه",
                value = actionable.size.toString(),
                valueColor = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "اصلاح آماده",
                value = healings.size.toString(),
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "استراتژی با بودجه",
                value = matrix.count { it.allocationShare > 0.0 }.toString(),
                valueColor = AurumColors.Green,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.runAiTraderCycleNow() },
                modifier = Modifier.weight(1f),
            ) { Text("🔄 همین حالا تحلیل کن", style = MaterialTheme.typography.labelMedium) }
            OutlinedButton(
                onClick = { viewModel.refreshAiTraderMemory() },
                modifier = Modifier.weight(1f),
            ) { Text("💾 حافظهٔ گوشی", style = MaterialTheme.typography.labelSmall) }
        }

        // ── ۱. پیش‌بینی خبری ────────────────────────────────────────────────
        if (forecasts.isEmpty()) {
            Text(
                text = "هنوز گزارشی از نمادها نرسیده است؛ پس از اولین اسکن رادار، شرایط نوظهور همین‌جا ساخته می‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Text(
                text = "🔍 پیش‌بینی شرایط (قبل از سیگنال)",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
            forecasts.take(6).forEach { f ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (f.isActionable) AurumColors.Green.copy(alpha = 0.10f)
                            else AurumColors.SurfaceAlt,
                        )
                        .border(
                            0.8.dp,
                            if (f.isActionable) AurumColors.Green.copy(alpha = 0.55f)
                            else AurumColors.Line.copy(alpha = 0.45f),
                            RoundedCornerShape(9.dp),
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
                            text = f.symbol,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.Gold,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = String.format(Locale.US, "%.0f%%", f.proximityPercent),
                            color = if (f.isActionable) AurumColors.Green else AurumColors.Cyan,
                        )
                    }
                    Text(
                        text = "${f.bestStrategyTitleFa} · ${f.emergingPassed}/${f.totalConditions} شرط · ETA: ${f.forecastEtaFa}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        text = f.preemptiveActionFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextPrimary,
                    )
                    f.emergingConditionsFa.take(2).forEach { item ->
                        Text(
                            text = "• $item",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                        )
                    }
                    f.blockersFa.take(2).forEach { item ->
                        Text(
                            text = "⛔ $item",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Red.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }

        // ── ۲. خودترمیمی ─────────────────────────────────────────────────────
        Text(
            text = "🛠️ اصلاح‌هایی که خودش آماده کرده",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.Cyan,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (healings.isEmpty()) {
            Text(
                text = "با دادهٔ فعلی، هیچ گلوگاه تکرارشونده‌ای پیدا نشد. (اصلاح بدون نمونه ساخته نمی‌شود.)",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            healings.forEach { h ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(AurumColors.SurfaceAlt)
                        .border(0.8.dp, AurumColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = h.kind.labelFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.Cyan,
                            fontWeight = FontWeight.Bold,
                        )
                        Pill(
                            text = String.format(Locale.US, "%.2f → %.2f", h.previousValue, h.newValue),
                            color = if (h.delta > 0) AurumColors.Green else AurumColors.Gold,
                        )
                    }
                    Text(
                        text = h.problemFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextPrimary,
                    )
                    Text(
                        text = h.reasonFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                    )
                    Text(
                        text = "${h.samples} نمونه · ${h.targetFa}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
            }
        }

        if (healings.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    val last = healings.maxByOrNull { it.createdAtMs }
                    if (last != null) viewModel.revertSelfHealing(last)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    text = "↩️ برگرداندن آخرین اصلاح (قرار گرفتن پارامتر به مقدار قبلی)",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // ── ۳. ماتریس برتری ──────────────────────────────────────────────────
        Text(
            text = "📊 ماتریس برتری — توزیع بودجهٔ ریسک",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.Gold,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (matrix.isEmpty()) {
            Text(
                text = "ماتریس پس از اولین معاملات بسته‌شده ساخته می‌شود؛ بدون نمونه، بودجه‌ای توزیع نمی‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            matrix.take(8).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.strategyTitleFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextPrimary,
                            maxLines = 1,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%d معامله · برد %.0f%% · E[R] %+.2fR · لبهٔ تعدیل‌شده %.2f",
                                row.tradesCount,
                                row.winRatePercent,
                                row.expectancyR,
                                row.adjustedEdge,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                        )
                    }
                    Pill(
                        text = String.format(Locale.US, "%.0f%%", row.allocationShare * 100.0),
                        color = when {
                            row.allocationShare <= 0.0 -> AurumColors.Red
                            row.allocationShare >= 0.15 -> AurumColors.Green
                            else -> AurumColors.Gold
                        },
                    )
                }
            }
        }

        // ── ۴. حافظهٔ همیشگی روی گوشی ────────────────────────────────────────
        Text(
            text = "💾 حافظهٔ گوشی",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.TextSecondary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = if (memory.lessonLinesFa.isEmpty()) {
                "هنوز چیزی ذخیره نشده است؛ اولین چرخه پس از اسکن رادار آن را می‌نویسد."
            } else {
                "${memory.lessonLinesFa.size} خط درس · ${memory.appliedActions.size} اصلاح ثبت‌شده · " +
                    "${memory.totalHealCount} اصلاح در کل"
            },
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (memory.lastSummaryFa.isNotBlank()) {
            Text(
                text = memory.lastSummaryFa,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "مسیر: ${viewModel.aiTraderMemoryPath()}",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.copyAiTraderLessons() },
                modifier = Modifier.weight(1f),
            ) { Text("📋 کپی خلاصه", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { viewModel.clearAiTraderMemory() },
                modifier = Modifier.weight(1f),
            ) { Text("🗑️ پاک‌کردن", style = MaterialTheme.typography.labelSmall) }
        }

        // خلاصهٔ متنی کامل (همان چیزی که در حافظه ذخیره می‌شود)
        if (summary.isNotEmpty()) {
            Text(
                text = "خلاصهٔ ذخیره‌شده:",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp),
            )
            summary.take(14).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }
    }
}
