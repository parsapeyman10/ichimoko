package com.aurum.edge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.TradingPerformanceAnalytics
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * «کارنامهٔ کلی» ژورنال — طراحی جمع‌وجور و تب‌بندی‌شده (نسخهٔ ۳.۹.۲۹).
 *
 * چرا بازنویسی شد: نسخهٔ قبلی همهٔ اعداد را پشت سر هم در یک ستون بلند می‌ریخت؛ روی گوشی،
 * برچسب‌های فارسی در ردیف‌های چهارستونی می‌شکستند، دو سرصفحهٔ تکراری وجود داشت و صفحه
 * «به‌هم‌ریخته» می‌شد. اکنون:
 *
 *  - سرصفحهٔ واحد (سود خالص + حکم سلامت + دامنهٔ فیلتر)؛
 *  - ردیف چهار شاخص کلیدی در **شبکهٔ ۲×۲** که در عرض گوشی نمی‌شکند؛
 *  - بقیهٔ تحلیل‌ها در چهار تب کوتاه: خلاصه · ریسک · تفکیک · توزیع R؛
 *  - هر عدد کارنامه از یک موتور خالص (`TradingPerformanceAnalytics`) می‌آید.
 */
@Composable
fun JournalPerformanceDashboard(
    report: TradingPerformanceAnalytics.PerformanceReport,
    scopeLabelFa: String,
    onOpenExport: () -> Unit = {},
    onOpenArchive: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val verdictColor = when (report.healthColorKey) {
        "GOOD" -> AurumColors.Green
        "BAD" -> AurumColors.Red
        else -> AurumColors.Gold
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AurumColors.Surface)
            .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── سرصفحه ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "کارنامهٔ کلی",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%s$%,.2f",
                        if (report.netPnlUsd >= 0.0) "+" else "-",
                        abs(report.netPnlUsd),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (report.netPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = scopeLabelFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Pill(text = report.healthVerdictFa, color = verdictColor)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${report.closedTrades} بسته · ${report.openTrades} باز",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    maxLines = 1,
                )
            }
        }

        // ── شبکهٔ ۲×۲ شاخص‌های کلیدی ─────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile(
                label = "مجموع R",
                value = String.format(Locale.US, "%+.2f R", report.totalR),
                valueColor = if (report.totalR >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "نرخ برد",
                value = String.format(Locale.US, "%.1f%%", report.winRatePct),
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile(
                label = "پرافیت فاکتور",
                value = report.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                valueColor = if ((report.profitFactor ?: 0.0) >= 1.2) AurumColors.Green else AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "حداکثر افت",
                value = String.format(Locale.US, "-%.1f%%", report.maxDrawdownPctOfPeak),
                valueColor = if (report.maxDrawdownPctOfPeak > 12.0) AurumColors.Red else AurumColors.Orange,
                modifier = Modifier.weight(1f),
            )
        }

        // ── تب‌های تحلیل ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to "📈 خلاصه",
                1 to "🛡️ ریسک",
                2 to "🧩 تفکیک",
                3 to "📊 توزیع R",
            ).forEach { (idx, label) ->
                Pill(
                    text = label,
                    color = if (tab == idx) AurumColors.Gold else AurumColors.TextMuted,
                    modifier = Modifier.clickable { tab = idx },
                )
            }
        }

        AnimatedVisibility(visible = tab == 0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EquityCurveStrip(report)
                MetricPair(
                    "امید ریاضی هر معامله" to String.format(Locale.US, "%+.2f$", report.expectancyUsdPerTrade),
                    "پرداخت/ریسک" to (report.payoffRatio?.let { String.format(Locale.US, "1 : %.2f", it) } ?: "—"),
                )
                MetricPair(
                    "۷ روز اخیر" to String.format(Locale.US, "%+.2f$", report.last7DaysPnlUsd),
                    "۳۰ روز اخیر" to String.format(Locale.US, "%+.2f$", report.last30DaysPnlUsd),
                )
                if (report.headlineFa.isNotBlank()) {
                    Text(
                        text = report.headlineFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
            }
        }

        AnimatedVisibility(visible = tab == 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricPair(
                    "حداکثر افت (دلار)" to String.format(Locale.US, "-%,.2f$", report.maxDrawdownUsd),
                    "حداکثر افت (درصد)" to String.format(Locale.US, "-%.1f%%", report.maxDrawdownPctOfPeak),
                )
                MetricPair(
                    "فاکتور بازیابی" to (report.recoveryFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—"),
                    "میانگین نگه‌داری" to formatHoldFa(report.avgHoldMinutes),
                )
                MetricPair(
                    "بهترین معامله" to String.format(Locale.US, "%+.2f$ (%+.2fR)", report.bestTradeUsd, report.bestTradeR),
                    "بدترین معامله" to String.format(Locale.US, "%+.2f$ (%+.2fR)", report.worstTradeUsd, report.worstTradeR),
                )
                MetricPair(
                    "بیشترین برد پیاپی" to "${report.maxConsecutiveWins}",
                    "بیشترین باخت پیاپی" to "${report.maxConsecutiveLosses}",
                )
                MetricPair(
                    "توالی فعلی" to when {
                        report.currentStreak > 0 -> "${report.currentStreak} برد پیاپی"
                        report.currentStreak < 0 -> "${-report.currentStreak} باخت پیاپی"
                        else -> "—"
                    },
                    "میانگین R" to String.format(Locale.US, "%+.2f", report.avgRPerTrade),
                )
            }
        }

        AnimatedVisibility(visible = tab == 2) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupTable("دستهٔ بازار", report.byAssetClass)
                GroupTable("نماد (۶ مورد برتر)", report.bySymbol.take(6))
                GroupTable("تایم‌فریم", report.byTimeframe, compact = true)
                GroupTable("جهت معامله", report.byDirection, compact = true)
            }
        }

        AnimatedVisibility(visible = tab == 3) {
            RDistributionBar(report.rDistribution)
        }

        // ── میان‌برهای پایین کارت ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill(
                text = "📊 خروجی و کارنامهٔ کوانت",
                color = AurumColors.Cyan,
                modifier = Modifier.clickable { onOpenExport() },
            )
            Pill(
                text = "📸 آرشیو تصویری",
                color = AurumColors.Gold,
                modifier = Modifier.clickable { onOpenArchive() },
            )
        }
    }
}

/** دو عدد کنار هم در یک ردیف؛ برای جلوگیری از شکستن متن، هر سلول ۵۰٪ عرض دارد. */
@Composable
private fun MetricPair(left: Pair<String, String>, right: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile(
            label = left.first,
            value = left.second,
            valueColor = valueColorFor(left.second),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = right.first,
            value = right.second,
            valueColor = valueColorFor(right.second),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun valueColorFor(value: String): androidx.compose.ui.graphics.Color = when {
    value.startsWith("-") && value.any { it.isDigit() } -> AurumColors.Red
    value.startsWith("+") -> AurumColors.Green
    else -> AurumColors.TextPrimary
}

/** منحنی سرمایهٔ فشرده با خط سربه‌سر. */
@Composable
private fun EquityCurveStrip(report: TradingPerformanceAnalytics.PerformanceReport) {
    val points = report.equityCurve
    if (points.size < 2) {
        Text(
            text = "منحنی سرمایه پس از ثبت حداقل دو معاملهٔ بسته‌شده رسم می‌شود.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
        return
    }
    val minV = points.minOf { it.cumulativePnlUsd }
    val maxV = points.maxOf { it.cumulativePnlUsd }
    val span = max(maxV - minV, 1e-6)
    val lineColor = if (points.last().cumulativePnlUsd >= 0.0) AurumColors.Green else AurumColors.Red
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "منحنی سرمایه",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AurumColors.ChartBg)
                .border(1.dp, AurumColors.Grid, RoundedCornerShape(10.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(72.dp).padding(6.dp)) {
                val w = size.width
                val h = size.height
                val stepX = w / (points.size - 1).toFloat()
                fun yOf(v: Double): Float = (h - ((v - minV) / span * h)).toFloat()
                val path = Path()
                points.forEachIndexed { idx, p ->
                    val x = idx * stepX
                    val y = yOf(p.cumulativePnlUsd)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (0.0 in minV..maxV) {
                    val zeroY = yOf(0.0)
                    drawLine(
                        color = AurumColors.Line,
                        start = Offset(0f, zeroY),
                        end = Offset(w, zeroY),
                        strokeWidth = 1f,
                    )
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = 2.2f))
                drawCircle(
                    color = lineColor,
                    radius = 3.2f,
                    center = Offset(w, yOf(points.last().cumulativePnlUsd)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = String.format(Locale.US, "کمترین %+,.0f$", minV),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                maxLines = 1,
            )
            Text(
                text = String.format(Locale.US, "بیشترین %+,.0f$", maxV),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                maxLines = 1,
            )
        }
    }
}

/** هیستوگرام افقی توزیع R. */
@Composable
private fun RDistributionBar(report: TradingPerformanceAnalytics.RDistribution) {
    if (report.counts.sum() <= 0) {
        Text(
            text = "برای نمایش توزیع R باید معاملهٔ بسته‌شده وجود داشته باشد.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "توزیع نتیجه بر حسب R",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Gold,
            fontWeight = FontWeight.Bold,
        )
        report.bucketsFa.forEachIndexed { idx, label ->
            val count = report.counts.getOrElse(idx) { 0 }
            val fraction = if (report.maxCount <= 0) 0f else count.toFloat() / report.maxCount.toFloat()
            val color = when {
                label.startsWith("≤") -> AurumColors.Red
                label.startsWith("-1R") -> AurumColors.Orange
                label.startsWith("0") -> AurumColors.TextMuted
                label.startsWith("1R") -> AurumColors.Cyan
                label.startsWith("2R") -> AurumColors.Green
                else -> AurumColors.Gold
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.width(56.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(AurumColors.SurfaceAlt),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(9.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(color.copy(alpha = 0.85f)),
                    )
                }
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(22.dp),
                )
            }
        }
    }
}

/** جدول فشردهٔ عملکرد یک گروه؛ در حالت compact فقط نام/تعداد/R/سود نمایش داده می‌شود. */
@Composable
private fun GroupTable(
    titleFa: String,
    rows: List<TradingPerformanceAnalytics.GroupPerformance>,
    compact: Boolean = false,
) {
    if (rows.isEmpty()) {
        Text(
            text = "$titleFa: داده‌ای برای تفکیک نیست.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = titleFa,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Gold,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = row.labelFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.4f),
                )
                Text(
                    text = "${row.trades}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(26.dp),
                )
                if (!compact) {
                    Text(
                        text = String.format(Locale.US, "%.0f%%", row.winRatePct),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(34.dp),
                    )
                }
                Text(
                    text = String.format(Locale.US, "%+.2f", row.totalR),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.totalR >= 0.0) AurumColors.Green else AurumColors.Red,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(46.dp),
                )
                Text(
                    text = String.format(Locale.US, "%+,.1f$", row.netPnlUsd),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.netPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun formatHoldFa(minutes: Double): String = when {
    minutes <= 0.0 -> "—"
    minutes < 60.0 -> String.format(Locale.US, "%.0f دقیقه", minutes)
    minutes < 60.0 * 24 -> String.format(Locale.US, "%.1f ساعت", minutes / 60.0)
    else -> String.format(Locale.US, "%.1f روز", minutes / (60.0 * 24))
}
