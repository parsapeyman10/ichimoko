package com.aurum.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.engine.EvidenceGrade
import com.aurum.edge.engine.ResearchEvidence
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

@Composable
private fun HalfReport(label: String, accent: Color, result: com.aurum.edge.engine.Backtester.Result) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
        Text(
            "  ${formatDateTime(result.fromTime)} → ${formatDateTime(result.toTime)}",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        StatTile("معاملات", "${result.trades.size}", AurumColors.TextPrimary, Modifier.weight(1f))
        StatTile("نرخ برد", result.winRate?.let { "${String.format("%.1f", it)}%" } ?: "—", AurumColors.Green, Modifier.weight(1f))
        StatTile("فاکتور سود", result.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        StatTile("موجودی نهایی", "${formatPrice(result.finalBalance)}$", if (result.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
        StatTile("حداکثر افت", "${String.format("%.1f", result.maxDrawdownPct)}%", AurumColors.Red, Modifier.weight(1f))
        StatTile("رد‌شده (حداقل لات)", "${result.skippedMinLot}", AurumColors.TextSecondary, Modifier.weight(1f))
    }
}

@Composable
internal fun WalkForwardReport(state: WalkForwardState.Done) {
    val wf = state.result
    val assessment = ResearchEvidence.outOfSample(wf.outOfSample, wf.costStressOutOfSample)
    val stressed = wf.costStressOutOfSample
    SectionCard(
        title = "یک آزمون خارج از نمونه (۷۰/۳۰) ${state.interval.label}",
        subtitle = "${wf.bars} کندل · تقسیم در ${formatDateTime(wf.splitTime)} · فقط قواعد فنی، نه گیت خبر",
    ) {
        Text(assessment.title, style = MaterialTheme.typography.bodySmall,
            color = if (assessment.grade == EvidenceGrade.UNFAVORABLE) AurumColors.Red else AurumColors.Gold)
        Text(assessment.detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
        if (!state.saved) Text("گزارش روی گوشی ذخیره نشد؛ این نتیجه پس از خروج ممکن است از دست برود.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        HalfReport("داخل نمونه (فنی)", AurumColors.TextSecondary, wf.inSample)
        HalfReport("خارج نمونه (فنی)", AurumColors.Gold, wf.outOfSample)
        Text("آزمون همان داده با اسپرد/کمیسیون ×۲ · تکرار موتور فنی با فرض هزینهٔ بیشتر، نه لغزش مشاهده‌شده",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan,
            modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("بسته‌شده ×۲", "${stressed.trades.size}", modifier = Modifier.weight(1f))
            StatTile("خالص فرضی ×۲", "${formatPrice(stressed.netPnl)}$", modifier = Modifier.weight(1f))
            StatTile("PF ×۲", stressed.profitFactor?.let { String.format("%.2f", it) } ?: "—",
                modifier = Modifier.weight(1f))
        }
        Text("پوزیشن باز پایان بازه: عادی ${if (wf.outOfSample.openAtEnd) 1 else 0} · ×۲ ${if (stressed.openAtEnd) 1 else 0}؛ هیچ‌کدام در خالص بسته‌ها نیستند. گپِ ورودِ رد‌شده ${wf.outOfSample.skippedGap} · پوزیشن حل‌نشدهٔ گپ ${wf.outOfSample.unresolvedGap}.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 6.dp))

        if (wf.outOfSample.trades.isNotEmpty()) {
            Text(
                "معاملات نیمه دیده‌نشده (آخرین ${minOf(wf.outOfSample.trades.size, 10)})",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            wf.outOfSample.trades.takeLast(10).reversed().forEach { trade ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        trade.side.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trade.side.name == "BUY") AurumColors.Green else AurumColors.Red,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        "${formatPrice(trade.entry)} → ${formatPrice(trade.exit)} · ${trade.exitReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${if (trade.pnlUsd >= 0) "+" else ""}${formatPrice(trade.pnlUsd)}$",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (trade.pnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                    )
                }
            }
        } else {
            Text(
                "در نیمه دیده‌نشده هیچ معامله‌ای ثبت نشد؛ عدد جعلی برای پر کردن این بخش ساخته نمی‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
internal fun BacktestReport(state: LearnState.Done) {
    val result = state.result
    val assessment = ResearchEvidence.inSample(result)
    SectionCard(
        title = "بک‌تست فرضی ${state.interval.label} · ${result.dataSource}",
        subtitle = "${result.bars} کندل · ${formatDateTime(result.fromTime)} تا ${formatDateTime(result.toTime)}",
    ) {
        Text(assessment.title, style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        Text(assessment.detail, style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("معاملات", "${result.trades.size}", AurumColors.TextPrimary, Modifier.weight(1f))
            StatTile("نرخ برد", result.winRate?.let { "${String.format("%.1f", it)}%" } ?: "—", AurumColors.Green, Modifier.weight(1f))
            StatTile("فاکتور سود", result.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("موجودی نهایی", "${formatPrice(result.finalBalance)}$", if (result.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            StatTile("سود/زیان خالص", "${formatPrice(result.netPnl)}$", if (result.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            StatTile("حداکثر افت", "${String.format("%.1f", result.maxDrawdownPct)}%", AurumColors.Red, Modifier.weight(1f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("انتظار به R", result.expectancyR?.let { String.format("%.2f", it) } ?: "—", AurumColors.TextPrimary, Modifier.weight(1f))
            StatTile("هزینهٔ فرضی کل", "${formatPrice(result.feesUsd)}$",  AurumColors.TextSecondary, Modifier.weight(1f))
            StatTile("رد‌شده (حداقل لات)", "${result.skippedMinLot}", AurumColors.TextSecondary, Modifier.weight(1f))
        }

        if (result.equity.size > 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .padding(top = 12.dp)
                    .background(AurumColors.ChartBg, RoundedCornerShape(8.dp)),
            ) {
                val points = result.equity
                val minV = points.minOf { it.balance }
                val maxV = points.maxOf { it.balance }
                val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
                val stepX = size.width / (points.size - 1).coerceAtLeast(1)
                var previous: Offset? = null
                points.forEachIndexed { index, point ->
                    val x = index * stepX
                    val y = (size.height * (1 - ((point.balance - minV) / span))).toFloat()
                    val current = Offset(x, y)
                    previous?.let { drawLine(AurumColors.Gold, it, current, strokeWidth = 2.5f) }
                    previous = current
                }
            }
            Text(
                "منحنی سرمایه — فقط از نتایج بک‌تست روی کندل‌های انتخاب‌شده",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(result.note, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
        Text("بازِ تسویه‌نشده در انتهای بازه: ${if (result.openAtEnd) 1 else 0} · رد به‌علت گپ زمانی: ${result.skippedGap} · رد به‌علت گپ قیمت: ${result.skippedFill} · پوزیشن حل‌نشدهٔ گپ: ${result.unresolvedGap}. موجودی نهایی فقط بسته‌هاست.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold,
            modifier = Modifier.padding(top = 6.dp))
        Text(
            "فرض‌های هزینه: اسپرد ${result.spreadPrice} و کمیسیون ${result.commissionPerOz}$ بر واحد (رفت و برگشت). حداقل حجم فرضی ${result.minPositionOz} واحد؛ برای طلا ۱ انس ≈ ۰٫۰۱ لات، برای نمادهای دیگر مشخصات بروکر را جدا بررسی کن.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (result.trades.isNotEmpty()) {
        SectionCard("فهرست معاملات پژوهشی", "نمایش ${minOf(result.trades.size, 30)} ترید آخر") {
            result.trades.takeLast(30).reversed().forEach { trade ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        trade.side.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trade.side.name == "BUY") AurumColors.Green else AurumColors.Red,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${formatPrice(trade.entry)} → ${formatPrice(trade.exit)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.TextPrimary,
                        )
                        Text(
                            "${formatDateTime(trade.entryTime)} · ${trade.exitReason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${if (trade.pnlUsd >= 0) "+" else ""}${formatPrice(trade.pnlUsd)}$",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (trade.pnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                        )
                        Text(
                            "${String.format("%.2f", trade.rMultiple)}R",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                        )
                    }
                }
            }
        }
    } else {
        SectionCard("هیچ معامله‌ای بسته نشد", "بدون نتیجهٔ محقق‌شده آمار برد تعریف نشده است") {
            Text(
                if (result.openAtEnd) "یک پوزیشن فرضی در پایان بازه باز ماند و در سود/زیان محقق‌شده شمرده نشد. بازهٔ طولانی‌تر را بررسی کن."
                else "در این بازه سیگنال قابل ورود/تسویه‌ای نماند؛ ممکن است حداقل حجم یا گپ داده مانع شده باشد. صفر معامله را به‌جای صفر درصد برد نخوان.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        }
    }
}
