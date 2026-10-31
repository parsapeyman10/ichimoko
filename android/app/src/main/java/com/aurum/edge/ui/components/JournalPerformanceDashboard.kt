package com.aurum.edge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.TradingPerformanceAnalytics
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * «کارنامهٔ کلی» بازطراحی‌شدهٔ ژورنال.
 *
 * طراحی بر پایهٔ چهار پرسش تصمیم‌ساز است، نه فهرست بلند اعداد:
 * ۱. **آیا سیستم سودده است؟** (خالص، امید ریاضی، پرافیت‌فاکتور، verdict سلامت)
 * ۲. **ریسک چقدر است؟** (حداکثر افت سرمایه، فاکتور بازیابی، بدترین معامله، توالی باخت)
 * ۳. **کجا پول درمی‌آید؟** (تفکیک دستهٔ بازار / نماد / تایم‌فریم / جهت)
 * ۴. **کیفیت خروج‌ها چگونه است؟** (توزیع R و نسبت پرداخت)
 *
 * همهٔ متن‌ها فارسی و همهٔ اعداد از [TradingPerformanceAnalytics] می‌آید تا UI هیچ
 * محاسبهٔ موازی و متناقضی نداشته باشد.
 */
@Composable
fun JournalPerformanceDashboard(
    report: TradingPerformanceAnalytics.PerformanceReport,
    scopeLabelFa: String,
    onOpenExport: () -> Unit = {},
    onOpenArchive: () -> Unit = {},
) {
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
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── ۱) سرصفحه و حکم سلامت ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "کارنامهٔ کلی (تحلیل کمّی)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
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
                )
                Text(
                    text = scopeLabelFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Pill(text = report.healthVerdictFa, color = verdictColor)
                Text(
                    text = "${report.closedTrades} بسته · ${report.openTrades} باز",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
        }

        if (report.headlineFa.isNotBlank()) {
            Text(
                text = report.headlineFa,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )
        }

        // ── ۲) منحنی سرمایه ──
        EquityCurveStrip(report = report)

        // ── ۳) عدد‌های تصمیم‌ساز ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = "مجموع R",
                value = String.format(Locale.US, "%+.2f R", report.totalR),
                valueColor = if (report.totalR >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "میانگین R",
                value = String.format(Locale.US, "%+.2f", report.avgRPerTrade),
                valueColor = if (report.avgRPerTrade >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "امید ریاضی",
                value = String.format(Locale.US, "%+.2f$", report.expectancyUsdPerTrade),
                valueColor = if (report.expectancyUsdPerTrade >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "پرافیت فاکتور",
                value = report.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                valueColor = if ((report.profitFactor ?: 0.0) >= 1.2) AurumColors.Green else AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = "نرخ برد",
                value = String.format(Locale.US, "%.1f%%", report.winRatePct),
                valueColor = AurumColors.Cyan,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "پرداخت/ریسک",
                value = report.payoffRatio?.let { String.format(Locale.US, "1 : %.2f", it) } ?: "—",
                valueColor = AurumColors.Purple,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "حداکثر افت",
                value = String.format(Locale.US, "-%.1f%%", report.maxDrawdownPctOfPeak),
                valueColor = AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "فاکتور بازیابی",
                value = report.recoveryFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                valueColor = if ((report.recoveryFactor ?: 0.0) >= 1.0) AurumColors.Green else AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = "بهترین معامله",
                value = String.format(Locale.US, "%+.2f$ (%+.2fR)", report.bestTradeUsd, report.bestTradeR),
                valueColor = AurumColors.Green,
                modifier = Modifier.weight(1.35f),
            )
            StatTile(
                label = "بدترین معامله",
                value = String.format(Locale.US, "%+.2f$ (%+.2fR)", report.worstTradeUsd, report.worstTradeR),
                valueColor = AurumColors.Red,
                modifier = Modifier.weight(1.35f),
            )
            StatTile(
                label = "توالی فعلی",
                value = when {
                    report.currentStreak > 0 -> "${report.currentStreak} برد پیاپی"
                    report.currentStreak < 0 -> "${-report.currentStreak} باخت پیاپی"
                    else -> "—"
                },
                valueColor = if (report.currentStreak >= 0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1.3f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = "۷ روز اخیر",
                value = String.format(Locale.US, "%+.2f$", report.last7DaysPnlUsd),
                valueColor = if (report.last7DaysPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "۳۰ روز اخیر",
                value = String.format(Locale.US, "%+.2f$", report.last30DaysPnlUsd),
                valueColor = if (report.last30DaysPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "میانگین نگه‌داری",
                value = formatHoldFa(report.avgHoldMinutes),
                valueColor = AurumColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "بیشترین باخت پیاپی",
                value = "${report.maxConsecutiveLosses}",
                valueColor = AurumColors.Orange,
                modifier = Modifier.weight(1f),
            )
        }

        // ── ۴) توزیع R ──
        RDistributionBar(report = report.rDistribution)

        // ── ۵) کجا پول درمی‌آید؟ ──
        GroupTable(
            titleFa = "بر اساس دستهٔ بازار",
            rows = report.byAssetClass,
        )
        GroupTable(
            titleFa = "بر اساس نماد (۶ نماد برتر)",
            rows = report.bySymbol.take(6),
        )
        GroupTable(
            titleFa = "بر اساس تایم‌فریم",
            rows = report.byTimeframe,
        )
        GroupTable(
            titleFa = "بر اساس جهت معامله",
            rows = report.byDirection,
        )

        // ── ۶) دسترسی سریع ──
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
                text = "📸 آرشیو تصویری معاملات",
                color = AurumColors.Gold,
                modifier = Modifier.clickable { onOpenArchive() },
            )
        }
    }
}

/** منحنی سرمایهٔ کوچک با سایهٔ افت سرمایه. */
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "منحنی سرمایه (بر پایهٔ سود/زیان انباشته)",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AurumColors.ChartBg)
                .border(1.dp, AurumColors.Grid, RoundedCornerShape(10.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(78.dp).padding(6.dp)) {
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
                // خط صفر (سربه‌سر)
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
                val last = points.last()
                drawCircle(
                    color = lineColor,
                    radius = 3.2f,
                    center = Offset(w, yOf(last.cumulativePnlUsd)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = String.format(Locale.US, "کمترین: %+,.2f$", minV),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            Text(
                text = String.format(Locale.US, "افت حداکثر: -%s$", formatNumberFa(report.maxDrawdownUsd)),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Red,
            )
            Text(
                text = String.format(Locale.US, "بیشترین: %+,.2f$", maxV),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
    }
}

/** هیستوگرام افقی توزیع R. */
@Composable
private fun RDistributionBar(report: TradingPerformanceAnalytics.RDistribution) {
    val total = report.counts.sum()
    if (total <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "توزیع نتیجهٔ معاملات بر حسب R (کیفیت ورود/خروج)",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
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
                    modifier = Modifier.width(52.dp),
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
                    modifier = Modifier.width(24.dp),
                )
            }
        }
    }
}

/** جدول کوچک عملکرد یک گروه (دسته/نماد/تایم‌فریم/جهت). */
@Composable
private fun GroupTable(
    titleFa: String,
    rows: List<TradingPerformanceAnalytics.GroupPerformance>,
) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = titleFa,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Gold,
            fontWeight = FontWeight.Bold,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "گروه",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.weight(1.5f),
            )
            Text(
                text = "معامله",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.weight(0.6f),
            )
            Text(
                text = "برد",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.weight(0.6f),
            )
            Text(
                text = "PF",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.weight(0.6f),
            )
            Text(
                text = "R",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.weight(0.7f),
            )
            Text(
                text = "سود/زیان",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.weight(1.1f),
            )
        }
        rows.forEach { row ->
            val pnlColor = if (row.netPnlUsd >= 0.0) AurumColors.Green else AurumColors.Red
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.labelFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextPrimary,
                    modifier = Modifier.weight(1.5f),
                )
                Text(
                    text = "${row.trades}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.weight(0.6f),
                )
                Text(
                    text = String.format(Locale.US, "%.0f%%", row.winRatePct),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    modifier = Modifier.weight(0.6f),
                )
                Text(
                    text = row.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.weight(0.6f),
                )
                Text(
                    text = String.format(Locale.US, "%+.2f", row.totalR),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.totalR >= 0.0) AurumColors.Green else AurumColors.Red,
                    modifier = Modifier.weight(0.7f),
                )
                Text(
                    text = String.format(Locale.US, "%+,.2f$", row.netPnlUsd),
                    style = MaterialTheme.typography.labelSmall,
                    color = pnlColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1.1f),
                )
            }
        }
    }
}

private fun formatNumberFa(value: Double): String =
    String.format(Locale.US, "%,.2f", abs(value))

private fun formatHoldFa(minutes: Double): String = when {
    minutes <= 0.0 -> "—"
    minutes < 60.0 -> String.format(Locale.US, "%.0f دقیقه", minutes)
    minutes < 60.0 * 24 -> String.format(Locale.US, "%.1f ساعت", minutes / 60.0)
    else -> String.format(Locale.US, "%.1f روز", minutes / (60.0 * 24))
}

/** رنگ‌ها را از تم می‌گیرد تا اگر تم روشن اضافه شد، کارنامه هماهنگ بماند. */
internal fun verdictColorOf(key: String): Color = when (key) {
    "GOOD" -> AurumColors.Green
    "BAD" -> AurumColors.Red
    else -> AurumColors.Gold
}
