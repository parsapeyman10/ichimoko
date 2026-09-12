package com.aurum.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Interval
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * The ONLY place where "learning" happens: the strategy is replayed over real bars
 * downloaded from the provider. No Monte-Carlo, no synthetic history, no invented win rate.
 */
@Composable
fun LearnScreen(viewModel: AurumViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val learn by viewModel.learn.collectAsStateWithLifecycle()

    var interval by remember { mutableStateOf(settings.interval) }
    var bars by remember { mutableStateOf(1000) }
    var balance by remember { mutableStateOf(settings.accountBalance.toString()) }
    var risk by remember { mutableStateOf(settings.riskPercent.toString()) }
    var spread by remember { mutableStateOf("0.30") }
    var commission by remember { mutableStateOf("0.05") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionCard(
            title = "یادگیری روی دیتای اصلی",
            subtitle = "استراتژی روی کندل‌های واقعی Twelve Data اجرا می‌شود و نتیجه واقعی گزارش می‌شود",
        ) {
            Text(
                "تنها «دموی» این اپ همین است: همان موتور زنده، روی همان کندل‌های واقعی، از گذشته به آینده اجرا می‌شود تا ببینی در ادامه چطور رفتار می‌کند. هیچ عدد شبیه‌سازی‌شده یا مونت‌کارلویی ساخته نمی‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Interval.entries.forEach { entry ->
                    FilterChip(
                        selected = interval == entry,
                        onClick = { interval = entry },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                            selectedLabelColor = AurumColors.Gold,
                            labelColor = AurumColors.TextSecondary,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(500, 1000, 2000, 5000).forEach { count ->
                    FilterChip(
                        selected = bars == count,
                        onClick = { bars = count },
                        label = { Text("$count کندل", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AurumColors.Cyan.copy(alpha = 0.18f),
                            selectedLabelColor = AurumColors.Cyan,
                            labelColor = AurumColors.TextSecondary,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("موجودی $") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = risk,
                    onValueChange = { risk = it },
                    label = { Text("ریسک %") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = spread,
                    onValueChange = { spread = it },
                    label = { Text("اسپرد (فرض)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = commission,
                    onValueChange = { commission = it },
                    label = { Text("کمیسیون/انس (فرض)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "اسپرد و کمیسیون باید از بروکر خودت گرفته شود؛ این دو، فرض‌های هزینه‌اند و در گزارش شفاف نشان داده می‌شوند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = {
                    viewModel.runLearn(
                        interval = interval,
                        bars = bars,
                        balance = balance.toDoubleOrNull() ?: settings.accountBalance,
                        risk = (risk.toDoubleOrNull() ?: settings.riskPercent).coerceIn(0.1, 5.0),
                        spread = spread.toDoubleOrNull() ?: 0.30,
                        commission = commission.toDoubleOrNull() ?: 0.05,
                        threshold = settings.minConfidence,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("دانلود دیتای واقعی و اجرای استراتژی", fontWeight = FontWeight.Bold)
            }
        }

        when (val state = learn) {
            is LearnState.Idle -> SectionCard("نتیجه‌ای هنوز نیست", "بازه را انتخاب کن و اجرا بزن") {
                Text(
                    "خروجی شامل تعداد معاملات، نرخ برد، فاکتور سود، افت سرمایه و فهرست تریدها روی همان کندل‌های واقعی است.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
            }

            is LearnState.Loading -> SectionCard("در حال دریافت دیتای واقعی", state.step) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = AurumColors.Gold, strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                    Text("صبر کن — بدون دیتای واقعی هیچ نتیجه‌ای ساخته نمی‌شود.", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                }
            }

            is LearnState.Failed -> SectionCard("اجرا نشد", "خطای منبع داده") {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                Text(
                    "این پیام یعنی داده واقعی دریافت نشد. عمداً هیچ نتیجه جایگزینی تولید نمی‌کنیم.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            is LearnState.Done -> BacktestReport(state)
        }
    }
}

@Composable
private fun BacktestReport(state: LearnState.Done) {
    val result = state.result
    SectionCard(
        title = "گزارش روی دیتای واقعی ${state.interval.label}",
        subtitle = "${result.bars} کندل · ${formatDateTime(result.fromTime)} تا ${formatDateTime(result.toTime)}",
    ) {
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
            StatTile("کارمزد کل", "${formatPrice(result.feesUsd)}$", AurumColors.TextSecondary, Modifier.weight(1f))
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
                "منحنی سرمایه — فقط از نتایج واقعی همان کندل‌ها",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(result.note, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
        Text(
            "فرض‌های هزینه: اسپرد ${result.spreadPrice} و کمیسیون ${result.commissionPerOz}$ بر انس (رفت و برگشت محاسبه شده). حداقل حجم 0.01 لات = 1 انس.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (result.trades.isNotEmpty()) {
        SectionCard("فهرست معاملات واقعی", "نمایش ${minOf(result.trades.size, 30)} ترید آخر") {
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
        SectionCard("هیچ معامله‌ای شکل نگرفت", "این هم یک نتیجه واقعی است") {
            Text(
                "در این بازه، موتور حتی یک سیگنال واجد شرایط پیدا نکرد. نتیجه‌ای ساخته نمی‌شود تا عدد قشنگ‌تری ببینی — بازه بزرگ‌تر یا تایم‌فریم دیگری را امتحان کن.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        }
    }
}
