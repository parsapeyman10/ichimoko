package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurum.edge.engine.PerformanceReport
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

/** Full, explicitly defined performance breakdown; missing denominator = '—', never infinity. */
@Composable
fun PerformancePanel(report: PerformanceReport, subtitle: String, modifier: Modifier = Modifier) {
    fun number(value: Double?, suffix: String = ""): String =
        value?.let { String.format(Locale.US, "%.2f", it) + suffix } ?: "—"
    val duration = report.averageDurationMillis?.let { ms ->
        val minutes = ms / 60_000L
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    } ?: "—"
    val metrics = listOf(
        "بسته‌شده" to "${report.total}",
        "برد" to "${report.wins}",
        "باخت/سربه‌سر" to "${report.losses}",
        "خرید (Long)" to "${report.longCount}",
        "فروش (Short)" to "${report.shortCount}",
        "نرخ برد" to number(report.winRatePct, "%"),
        "سود ناخالص" to "${formatPrice(report.grossProfitUsd)}$",
        "زیان ناخالص" to "${formatPrice(report.grossLossUsd)}$",
        "خالص P/L" to "${formatPrice(report.netPnlUsd)}$",
        "میانگین برد" to number(report.averageWinUsd, "$"),
        "میانگین باخت" to number(report.averageLossUsd, "$"),
        "فاکتور سود" to number(report.profitFactor),
        "Sharpe معاملاتی" to number(report.tradeSharpe),
        "انتظار به R" to number(report.expectancyR),
        "میانگین مدت" to duration,
        "بیشترین برد پیاپی" to "${report.longestWinningStreak}",
        "بیشترین باخت پیاپی" to "${report.longestLosingStreak}",
        "حداکثر افت" to number(report.maxDrawdownPct, "%"),
    )
    SectionCard("گزارش کامل ${metrics.size} شاخص", subtitle, modifier = modifier) {
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { (label, value) ->
                    val tone = if (label == "خالص P/L") {
                        if (report.netPnlUsd >= 0) AurumColors.Green else AurumColors.Red
                    } else AurumColors.TextPrimary
                    StatTile(label, value, tone, Modifier.weight(1f))
                }
                if (row.size == 1) StatTile("", "", modifier = Modifier.weight(1f))
            }
        }
        Text("Sharpe به‌ازای معاملهٔ بسته و بدون سالانه‌سازی، با نرخ بدون ریسک صفر و انحراف معیار نمونه است؛ معادل Sharpe روزانه نیست. افت سرمایه بر پایهٔ موجودی اولیه و سود/زیان معاملات بسته محاسبه می‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp))
        if (report.total == 0) Text("بدون معاملهٔ بسته، نسبت‌ها تعریف نشده‌اند (—).",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
    }
}
