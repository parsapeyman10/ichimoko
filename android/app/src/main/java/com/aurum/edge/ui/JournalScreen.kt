package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun JournalScreen(viewModel: AurumViewModel, market: MarketState) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val livePrice = market.lastPrice

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionCard(
            title = "ژورنال معاملات کاغذی",
            subtitle = "همه ورود/خروج روی قیمت واقعی بازار ثبت و تسویه می‌شود",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("بسته‌شده", "${stats.total}", AurumColors.TextPrimary, Modifier.weight(1f))
                StatTile("باز", "${stats.open}", AurumColors.Gold, Modifier.weight(1f))
                StatTile("برد/باخت", "${stats.wins}/${stats.losses}", AurumColors.Green, Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                StatTile("نرخ برد", stats.winRate?.let { "${String.format("%.1f", it)}%" } ?: "—", AurumColors.Green, Modifier.weight(1f))
                StatTile("فاکتور سود", stats.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
                StatTile("سود خالص", "${formatPrice(stats.netPnl)}$", if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            }
            if (stats.total == 0) {
                Text(
                    "هنوز معامله بسته‌شده‌ای نیست. آمار فقط از نتایج واقعی ساخته می‌شود؛ عدد نمایشی نداریم.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        val open = trades.filter { it.isOpen }
        if (open.isNotEmpty()) {
            SectionCard("پوزیشن‌های باز", "ارزش‌گذاری با آخرین قیمت واقعی دریافتی") {
                open.forEach { trade ->
                    val unrealized = livePrice?.let { price ->
                        val perOz = if (trade.action == SignalAction.BUY) price - trade.entry else trade.entry - price
                        perOz * trade.positionOz
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${trade.action.name} ${trade.interval.label} · ${String.format("%.3f", trade.positionOz)} oz",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (trade.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                            )
                            Text(
                                "ورود ${formatPrice(trade.entry)} · SL ${formatPrice(trade.stopLoss)} · TP ${formatPrice(trade.takeProfit)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextMuted,
                            )
                            Text(
                                formatDateTime(trade.openedAt) + " · امتیاز ${trade.confidence.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.TextMuted,
                            )
                            trade.mtf?.let { snapshot ->
                                Text(
                                    "تراز چندتایم‌فریم هنگام ورود: ${snapshot.bias} · هم‌جهتی ${(snapshot.alignment * 100).toInt()}%" +
                                        (if (snapshot.veto) " · وتو داشته" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.Gold,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                unrealized?.let { "${if (it >= 0) "+" else ""}${formatPrice(it)}$" } ?: "—",
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    unrealized == null -> AurumColors.TextMuted
                                    unrealized >= 0 -> AurumColors.Green
                                    else -> AurumColors.Red
                                },
                            )
                            OutlinedButton(
                                onClick = { viewModel.closePaperTrade(trade) },
                                enabled = livePrice != null,
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text("بستن", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }

        val closed = trades.filter { !it.isOpen }
        if (closed.isNotEmpty()) {
            SectionCard("معاملات بسته‌شده", "تسویه‌شده روی قیمت واقعی") {
                closed.forEach { trade: PaperTrade -> TradeRow(trade) }
            }
            reports.firstOrNull()?.let { report -> StoredReportCard(report) }

            Button(
                onClick = viewModel::clearJournal,
                colors = ButtonDefaults.buttonColors(containerColor = AurumColors.SurfaceAlt, contentColor = AurumColors.TextSecondary),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("پاک کردن ژورنال") }
        }
    }
}

@Composable
private fun TradeRow(trade: PaperTrade) {
    val pnl = trade.pnlUsd ?: 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            trade.action.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (trade.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${formatPrice(trade.entry)} → ${formatPrice(trade.exitPrice)}",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextPrimary,
            )
            Text(
                "${formatDateTime(trade.closedAt)} · ${trade.exitReason ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${if (pnl >= 0) "+" else ""}${formatPrice(pnl)}$",
                style = MaterialTheme.typography.labelMedium,
                color = if (pnl >= 0) AurumColors.Green else AurumColors.Red,
            )
            Text(
                trade.rMultiple?.let { "${String.format("%.2f", it)}R" } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
    }
}

/**
 * The last walk-forward run kept on this device. It is shown with its own date so the numbers can
 * be re-checked instead of taken on faith.
 */
@Composable
private fun StoredReportCard(report: WalkForwardRecord) {
    val outPf = report.outOfSample.profitFactor ?: 0.0
    SectionCard(
        title = "آخرین تست خارج از نمونه (ذخیره‌شده روی گوشی)",
        subtitle = "${report.interval} · ${report.bars} کندل واقعی · ${formatDateTime(report.generatedAt)}",
    ) {
        Text(
            report.verdict,
            style = MaterialTheme.typography.bodySmall,
            color = if (outPf > 1.0) AurumColors.Green else AurumColors.Red,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("داخل نمونه PF", report.inSample.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.TextSecondary, Modifier.weight(1f))
            StatTile("خارج نمونه PF", report.outOfSample.profitFactor?.let { String.format("%.2f", it) } ?: "—", AurumColors.Gold, Modifier.weight(1f))
            StatTile("معاملات خارج نمونه", "${report.outOfSample.trades.size}", AurumColors.TextPrimary, Modifier.weight(1f))
        }
        Text(
            "هزینه‌های فرض‌شده: اسپرد ${report.outOfSample.spreadPrice}$ · کمیسیون ${report.outOfSample.commissionPerOz}$ بر انس",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
