package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PairScanState
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SignalScreen(viewModel: AurumViewModel, market: MarketState, onOpenNews: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    val signal = market.signal
    val positionBlocker = when {
        trades.any { it.symbol == market.symbol && it.isOpen } -> "پوزیشن این نماد هنوز باز است"
        signal != null && trades.any { it.symbol == market.symbol && it.signalBarTime != null &&
            it.signalBarTime == signal.barTime } -> "این کندل قبلاً معامله شده است"
        else -> IctEntryRules.assess(market).reason
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SymbolPickerRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }
        SignalSummaryCard(
            signal = signal,
            onOpenPaperTrade = {},
            entryBlocker = positionBlocker,
            allowManualPaperTrade = false,
        )
        SectionCard("معاملهٔ کاغذی خودکار") {
            Text(
                if (settings.autoPaperTrading) autoStatus else "خاموش",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.autoPaperTrading) AurumColors.TextSecondary else AurumColors.Gold,
            )
        }

        signal?.let { s ->
            SectionCard(
                title = "شرط‌های موتور · ${s.confidence.toInt()}/100 · ${formatTime(s.barTime)}",
            ) {
                if (s.confluence.isEmpty()) {
                    Text("—", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
                } else {
                    s.confluence.take(8).forEach { ConfluenceRow(it) }
                    if (s.confluence.size > 8) {
                        Text("آپشن‌ها", style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
                        s.confluence.drop(8).forEach { ConfluenceRow(it) }
                    }
                }
            }

        } ?: SectionCard(title = "سیگنال") {
            Text("—", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
        }
    }
}

@Composable
private fun MtfCard(snapshot: MtfAnalyzer.Snapshot) {
    val tone = when {
        snapshot.veto -> AurumColors.Red
        snapshot.bias == SignalAction.BUY -> AurumColors.Green
        snapshot.bias == SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.TextSecondary
    }
    SectionCard(
        title = "تراز چندتایم‌فریم (محاسبه روی گوشی)",
        subtitle = "تایم‌فریم‌های بالاتر از همان کندل‌های واقعیِ دریافتی ساخته می‌شوند · آخرین کندل بسته: ${formatTime(snapshot.barTime)}",
        trailing = {
            Pill(
                text = if (snapshot.veto) "وتو" else snapshot.bias.name,
                color = tone,
            )
        },
    ) {
        Text(
            snapshot.advisory,
            style = MaterialTheme.typography.bodySmall,
            color = if (snapshot.veto) AurumColors.Red else AurumColors.TextSecondary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("هم‌جهتی", "${(snapshot.alignment * 100).toInt()}%", tone, Modifier.weight(1f))
            StatTile("صعودی", "${snapshot.buyCount}", AurumColors.Green, Modifier.weight(1f))
            StatTile("نزولی", "${snapshot.sellCount}", AurumColors.Red, Modifier.weight(1f))
            StatTile("خنثی", "${snapshot.neutralCount}", AurumColors.TextSecondary, Modifier.weight(1f))
        }
        snapshot.frames.forEach { frame ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    frame.interval.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.TextPrimary,
                    modifier = Modifier.weight(0.8f),
                )
                Text(
                    frame.bias.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (frame.bias) {
                        SignalAction.BUY -> AurumColors.Green
                        SignalAction.SELL -> AurumColors.Red
                        else -> AurumColors.TextMuted
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "قدرت ${frame.strength}% · وزن ${(frame.weight * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Gold,
                    modifier = Modifier.weight(1.6f),
                )
            }
            Text(
                frame.detail,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        if (snapshot.skippedFrames.isNotEmpty()) {
            Text(
                "تایم‌فریم‌های ${snapshot.skippedFrames.joinToString("، ")} هنوز تاریخ واقعی کافی ندارند؛ درباره‌شان حدس نمی‌زنیم.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** All-pairs radar: one glance over gold + the majors. A tap switches the live chart feed. */
@Composable
internal fun PairRadarCard(scan: PairScanState, onScan: () -> Unit, onSelectSymbol: (String) -> Unit, now: Long) {
    // First visit per process: one honest sweep so the radar is never empty; afterwards the
    // background service (or the button) keeps it fresh. Throttled inside the scanner.
    LaunchedEffect(scan.lastSweepAt) { if (scan.lastSweepAt == null) onScan() }
    SectionCard(
        title = "رادار ۸ جفت‌ارز",
        trailing = {
            Pill(
                when {
                    scan.sweeping -> "در حال اسکن…"
                    scan.lastSweepAt != null -> "آخرین اسکن " + relativeTime(scan.lastSweepAt, now)
                    else -> "اسکن نشده"
                },
                if (scan.sweeping) AurumColors.Cyan else AurumColors.TextMuted,
            )
        },
    ) {
        scan.statuses.forEach { status ->
            val tone = when (status.state) {
                "candidate" -> AurumColors.Green
                "blocked" -> AurumColors.Orange
                "error" -> AurumColors.Red
                "needs_key" -> AurumColors.Orange
                else -> AurumColors.TextMuted
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))
                    .clickable { onSelectSymbol(status.symbol) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(status.symbol, style = MaterialTheme.typography.labelLarge, color = AurumColors.Gold)
                Text(
                    status.price?.let { formatPrice(it) } ?: "—",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                )
                Text(
                    status.detail + (status.lastScanAt?.let { " · " + relativeTime(it, now) } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = tone,
                    modifier = Modifier.weight(1f),
                )
                if (status.technicalScore != null) {
                    Pill("${status.technicalScore}/۸", tone)
                }
            }
        }
        scan.lastError?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onScan, enabled = !scan.sweeping, modifier = Modifier.padding(top = 6.dp)) {
            Text(if (scan.sweeping) "در حال اسکن…" else "اسکن همگانی ۸ جفت‌ارز")
        }
    }
}
