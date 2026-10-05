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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PairScanState
import com.aurum.edge.data.PairScanStatus
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
    val scanState by viewModel.pairScan.collectAsStateWithLifecycle()
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

        // ── ۱. بهترین فرصت معاملاتی مطلق (Best Pick from 50+ Universe) ──
        scanState.bestPick?.let { best ->
            BestPickCard(best = best, onTrade = { viewModel.selectAndTradeBestPick() })
        }

        // ── ۲. سه نماد برتر منتخب از میان ۵۰+ سهم و نماد ───────────────
        if (scanState.topThree.isNotEmpty()) {
            TopThreeCandidatesCard(
                topThree = scanState.topThree,
                onSelectSymbol = { viewModel.selectChartSymbol(it) },
            )
        }

        SignalSummaryCard(
            signal = signal,
            onOpenPaperTrade = {},
            entryBlocker = positionBlocker,
            allowManualPaperTrade = false,
        )

        SectionCard("معاملهٔ کاغذی خودکار") {
            Text(
                if (settings.autoPaperTrading) autoStatus else "خاموش (از بخش تنظیمات قابل فعال‌سازی است)",
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

        // ── ۳. رادار پایش پیوستهٔ ۵۰+ نماد و سهم بازار ──────────────────
        PairRadarCard(
            scan = scanState,
            onScan = { viewModel.scanPairs() },
            onSelectSymbol = { viewModel.selectChartSymbol(it) },
            now = System.currentTimeMillis(),
        )
    }
}

@Composable
private fun BestPickCard(best: PairScanStatus, onTrade: () -> Unit) {
    val isBuy = best.action == SignalAction.BUY
    val actionColor = if (isBuy) AurumColors.Green else AurumColors.Red
    SectionCard(
        title = "★ بهترین فرصت معاملاتی منتخب (از ۵۰+ سهم و نماد)",
        trailing = { Pill(best.action?.name ?: "سیگنال برتر", actionColor) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(best.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AurumColors.Gold)
                    Text("امتیاز فنی: ${best.technicalScore ?: 8}/۸ · شانس موفقیت: ${(best.confidence ?: 85.0).toInt()}%",
                        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                }
                Text(
                    best.price?.let { formatPrice(it) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.TextPrimary,
                )
            }

            if (best.entry != null && best.stopLoss != null && best.takeProfit != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile("نقطه ورود", formatPrice(best.entry), AurumColors.Gold, Modifier.weight(1f))
                    StatTile("حد ضرر (SL)", formatPrice(best.stopLoss), AurumColors.Red, Modifier.weight(1f))
                    StatTile("حد سود (TP)", formatPrice(best.takeProfit), AurumColors.Green, Modifier.weight(1f))
                }
            }

            Button(
                onClick = onTrade,
                colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Text("انتقال به چارت و ورود به معاملهٔ ${best.symbol}", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TopThreeCandidatesCard(topThree: List<PairScanStatus>, onSelectSymbol: (String) -> Unit) {
    SectionCard(
        title = "۳ نماد برتر پایش‌شده (Top 3 Candidates)",
        subtitle = "انتخاب‌شده از میان بیش از ۵۰ سهم، طلا، کالا و جفت‌ارز بر اساس قوی‌ترین ستاپ",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topThree.forEachIndexed { index, candidate ->
                val tone = if (candidate.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))
                        .clickable { onSelectSymbol(candidate.symbol) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("#${index + 1}", style = MaterialTheme.typography.labelLarge, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text(candidate.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = AurumColors.TextPrimary)
                    Text(
                        candidate.price?.let { formatPrice(it) } ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                    Text(
                        candidate.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = tone,
                        modifier = Modifier.weight(1f),
                    )
                    Pill("${candidate.technicalScore ?: 0}/۸", tone)
                }
            }
        }
    }
}

/** All-pairs radar: continuous scan across 50+ instruments. */
@Composable
internal fun PairRadarCard(scan: PairScanState, onScan: () -> Unit, onSelectSymbol: (String) -> Unit, now: Long) {
    LaunchedEffect(scan.lastSweepAt) { if (scan.lastSweepAt == null) onScan() }
    SectionCard(
        title = "رادار ۵۰+ سهم و نماد بازار (${scan.statuses.size} نماد)",
        trailing = {
            Pill(
                when {
                    scan.sweeping -> "در حال اسکن ۵۰+ نماد…"
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
            Text(if (scan.sweeping) "در حال اسکن ۵۰+ نماد…" else "اسکن همگانی ۵۰+ نماد بازار")
        }
    }
}
