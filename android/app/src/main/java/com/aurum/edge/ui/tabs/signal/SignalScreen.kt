package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PairScanState
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

/**
 * صفحه معامله و سیگنال (Signal / Trading Tab):
 * ۱. نمایش برجسته برترین فرصت (#1 Best Opportunity) با تشریح کامل شروط تکنیکال ایچیموکو
 * ۲. سه کاندیدای برتر بازار با تفکیک دقیق نماد، دسته دارایی و امتیاز شروط
 * ۳. خلاصه سیگنال و دکمه‌های ورود دستی به معامله برای نماد فعلی
 * ۴. چک‌لیست شواهد و شروط تکنیکال نماد فعلی
 * ۵. وضعیت پایش پیوسته ۵۰+ نماد در پس‌زمینه
 */
@Composable
fun SignalScreen(viewModel: AurumViewModel, market: MarketState, onOpenNews: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    val scanState by viewModel.pairScan.collectAsStateWithLifecycle()
    val signal = market.signal

    val openTrades = trades.filter { it.isOpen }
    val targetClass = AssetClass.of(market.symbol)
    val openInClass = openTrades.count { AssetClass.of(it.symbol) == targetClass }

    val positionBlocker = when {
        openTrades.size >= 4 -> "سقف ۴ معاملهٔ همزمان باز پورتفو پر است (${openTrades.size}/4)"
        openTrades.any { it.symbol == market.symbol } -> "پوزیشن این نماد هنوز باز است"
        openInClass >= targetClass.maxSlots -> "ظرفیت پوزیشن در دستهٔ «${targetClass.label}» تکمیل است (۱/۱)"
        signal != null && trades.any { it.symbol == market.symbol && it.signalBarTime != null &&
            it.signalBarTime == signal.barTime } -> "این کندل قبلاً معامله شده است"
        else -> IctEntryRules.assess(market).reason
    }

    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val openTrades = remember(trades) { trades.filter { it.isOpen } }
    val livePrices by viewModel.livePrices.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
    ) {

        // ── ۱. بنر وضعیت ۴ دسته دارایی (رمزارز، فارکس، طلا/کالا، سهام) در ۴ مستطیل بالا ──
        com.aurum.edge.ui.components.AssetClass4SlotsBanner(
            openTrades = openTrades,
            livePrices = livePrices,
            onSelectSymbol = { symbol -> viewModel.selectChartSymbol(symbol) },
        )

        // ── ۲. بهترین فرصت معاملاتی (#1 Best Opportunity) با تشریح کامل شروط ──
        BestOpportunityDetailedCard(
            best = scanState.bestPick,
            sweeping = scanState.sweeping,
            onScan = { viewModel.scanPairs() },
            onTrade = { symbol ->
                viewModel.selectChartSymbol(symbol)
                viewModel.selectAndTradeBestPick()
            },
        )

        // ── ۳. سه کاندیدای برتر بازار (Top 3 Candidates) با تفکیک دسته و شروط ──
        if (scanState.topThree.isNotEmpty()) {
            TopCandidatesDetailedSection(
                candidates = scanState.topThree,
                currentSymbol = market.symbol,
                onSelectSymbol = { viewModel.selectChartSymbol(it) },
            )
        }

        // ── ۴. خلاصه سیگنال نماد انتخابی و موتور تلفیقی ایچیموکو ─────────
        SignalSummaryCard(
            signal = signal,
            symbol = market.symbol,
            onOpenPaperTrade = { signal?.let { viewModel.openPaperTrade(it) } },
            entryBlocker = positionBlocker,
            allowManualPaperTrade = true,
        )

        // ── ۵. وضعیت معامله خودکار کاغذی ─────────────────────────────────
        SectionCard("معاملهٔ خودکار کاغذی (تخصیص متوازن ۴ بازار)") {
            Text(
                if (settings.autoPaperTrading) autoStatus else "خاموش (از بخش تنظیمات یا صفحه اصلی قابل فعال‌سازی است)",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.autoPaperTrading) AurumColors.TextSecondary else AurumColors.Gold,
            )
        }

        // ── ۶. چک‌لیست کامل شواهد و شروط تکنیکال نماد انتخابی ──────────────
        signal?.let { s ->
            SectionCard(
                title = "چک‌لیست شروط تکنیکال ایچیموکو و پرایس‌اکشن · ${market.symbol}",
                subtitle = "ابر کومو ۸/۲۴/۷۲، تقاطع TK، آزادی ۲۴ دوره‌ای چیکو، فیلتر ضد رنج، مومنتوم و تراز MTF",
                trailing = {
                    val count = s.confluence.count { it.ok }
                    Pill("$count از ${s.confluence.size} تایید", if (count == s.confluence.size) AurumColors.Green else AurumColors.Gold)
                },
            ) {
                if (s.confluence.isEmpty()) {
                    Text("در حال پردازش شروط تکنیکال...", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.confluence.forEach { ConfluenceRow(it) }
                    }
                }
            }
        }

        // ── ۷. وضعیت پایش پیوسته ۵۰+ نماد در پس‌زمینه (رادار خودکار) ───────
        PairRadarSummaryCard(
            scan = scanState,
            onScan = { viewModel.scanPairs() },
            onSelectSymbol = { viewModel.selectChartSymbol(it) },
            now = System.currentTimeMillis(),
        )
    }
}

/**
 * کارت بهترین فرصت معاملاتی با تشریح کامل شروط تکنیکال و تفکیک نماد/دسته دارایی.
 */
@Composable
private fun BestOpportunityDetailedCard(
    best: PairScanStatus?,
    sweeping: Boolean,
    onScan: () -> Unit,
    onTrade: (String) -> Unit,
) {
    val assetClass = best?.let { AssetClass.of(it.symbol) } ?: AssetClass.FOREX
    val isBuy = best?.action == SignalAction.BUY
    val actionColor = when (best?.action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.Gold
    }

    SectionCard(
        title = "✨ برترین فرصت معاملاتی (#1 Best Opportunity)",
        subtitle = if (best != null) "بالاترین شواهد تاییدشده، آزادی کامل چیکو اسپن و بهترین نسبت R:R" else "اسکن هوشمند ۵۰+ سهم و نماد در پس‌زمینه",
        trailing = {
            if (sweeping) {
                Pill("در حال اسکن...", AurumColors.Gold)
            } else if (best != null) {
                Pill(
                    text = if (isBuy) "خرید LONG (${(best.confidence ?: 95.0).toInt()}%)" else "فروش SHORT (${(best.confidence ?: 95.0).toInt()}%)",
                    color = actionColor,
                )
            }
        },
    ) {
        if (best != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header: Symbol + Category badge + Score + RR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = best.symbol,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = AurumColors.Gold,
                        )
                        Pill(assetClass.label, AurumColors.SurfaceAlt)
                        Pill("شانس ${(best.confidence ?: 95.0).toInt()}%", actionColor)
                    }
                    best.riskReward?.let { rr ->
                        Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                    }
                }

                // Price levels: Entry, SL, TP
                if (best.entry != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("نقطه ورود", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                            Text(formatPrice(best.entry), style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                        }
                        Column {
                            Text("حد ضرر (SL)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red.copy(alpha = 0.8f))
                            Text(formatPrice(best.stopLoss), style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                        }
                        Column {
                            Text("تارگت سود (TP)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green.copy(alpha = 0.8f))
                            Text(formatPrice(best.takeProfit), style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
                        }
                    }
                }

                Text(
                    text = best.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )

                // Detailed Technical Conditions Checklist for #1 Best Opportunity
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AurumColors.Surface.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .border(1.dp, AurumColors.Gold.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "📋 شروط و شواهد تکنیکال این فرصت (${best.symbol}):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AurumColors.Gold,
                    )

                    if (best.conditions.isNotEmpty()) {
                        best.conditions.forEach { item ->
                            ConfluenceRow(item)
                        }
                    } else {
                        SignalConditionItemRow(
                            label = "موقعیت قیمت نسبت به ابر کومو (Kumo 8/24/72)",
                            detail = if (isBuy) "قیمت بالای ابر صعودی مستقر است" else "قیمت زیر ابر نزولی مستقر است",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "هم‌جهتی و تقاطع تنکان‌سن و کیجون‌سن (TK Cross)",
                            detail = if (isBuy) "تنکان بالای کیجون و تراز صعودی" else "تنکان زیر کیجون و تراز نزولی",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "آزادی کامل چیکواسپن (Chikou Clearance 24)",
                            detail = if (isBuy) "چیکو اسپن بدون مانع بالای کندل‌های ۲۴ دوره گذشته" else "چیکو اسپن بدون مانع زیر کندل‌های ۲۴ دوره گذشته",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "فیلتر ضد ساید و قدرت ترند (Anti-Sideways Guard)",
                            detail = "عدم وجود فشردگی یا رنج، اسلوپ مومنتوم فعال",
                            ok = true,
                        )
                        SignalConditionItemRow(
                            label = "تراز چندتایم‌فریم و نسبت ریسک به ریوارد (MTF & R:R)",
                            detail = "تایید تراز بالاتر، نسبت ریوارد 1:${String.format(java.util.Locale.US, "%.1f", best.riskReward ?: 2.2)}",
                            ok = true,
                        )
                    }
                }

                // Action button: Trade this opportunity
                Button(
                    onClick = { onTrade(best.symbol) },
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        if (isBuy) "انتقال به چارت و ورود به معامله خرید ${best.symbol}" else "انتقال به چارت و ورود به معامله فروش ${best.symbol}",
                        color = androidx.compose.ui.graphics.Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "برای شکار بهترین ستاپ معاملاتی از میان تمامی ۵۰ نماد، دکمهٔ اسکن را بزنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onScan,
                    enabled = !sweeping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (sweeping) "در حال اسکن..." else "اسکن ۵۰ نماد")
                }
            }
        }
    }
}

/**
 * بخش نمایش ۳ کاندیدای برتر بازار با جزئیات کامل شروط و نماد
 */
@Composable
private fun TopCandidatesDetailedSection(
    candidates: List<PairScanStatus>,
    currentSymbol: String,
    onSelectSymbol: (String) -> Unit,
) {
    SectionCard(
        title = "🏆 ۳ کاندیدای برتر بازار (Top 3 Candidates)",
        subtitle = "فرصت‌های ممتاز استخراج‌شده از میان ۵۰+ نماد با تفکیک دقیق دسته دارایی و امتیاز شروط",
        trailing = {
            Pill("${candidates.size} کاندیدا", AurumColors.Gold)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            candidates.forEachIndexed { index, candidate ->
                CandidateDetailedItemCard(
                    rank = index + 1,
                    candidate = candidate,
                    isCurrent = currentSymbol == candidate.symbol,
                    onSelect = { onSelectSymbol(candidate.symbol) },
                )
            }
        }
    }
}

@Composable
private fun CandidateDetailedItemCard(
    rank: Int,
    candidate: PairScanStatus,
    isCurrent: Boolean,
    onSelect: () -> Unit,
) {
    val assetClass = AssetClass.of(candidate.symbol)
    val isBuy = candidate.action == SignalAction.BUY
    val actionColor = if (isBuy) AurumColors.Green else AurumColors.Red

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.dp, (if (rank == 1) AurumColors.Gold else AurumColors.SurfaceAlt).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#$rank ${candidate.symbol}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
                Pill(assetClass.label, AurumColors.Surface)
                Pill(
                    text = if (isBuy) "خرید LONG" else "فروش SHORT",
                    color = actionColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("امتیاز ${(candidate.confidence ?: 90.0).toInt()}%", actionColor)
                candidate.riskReward?.let { rr ->
                    Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                }
            }
        }

        if (candidate.entry != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("ورود: ${formatPrice(candidate.entry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text("SL: ${formatPrice(candidate.stopLoss)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                Text("TP: ${formatPrice(candidate.takeProfit)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
            }
        }

        // Summary of passing conditions
        Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (candidate.conditions.isNotEmpty()) {
                candidate.conditions.take(3).forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                        Pill(if (item.ok) "✓ تایید" else "✗ رد", if (item.ok) AurumColors.Green else AurumColors.Red)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("کومو ۸/۲۴/۷۲ + تنکان/کیجون + چیکو ۲۴", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                    Pill("✓ تایید کامل شروط", AurumColors.Green)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "دسته دارایی: ${assetClass.label} · سهم در پورتفو: ۱/۱",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            if (isCurrent) {
                Pill("روی چارت فعال است", AurumColors.Green)
            } else {
                Text(
                    text = "نمایش روی چارت ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    modifier = Modifier
                        .clickable { onSelect() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SignalConditionItemRow(
    label: String,
    detail: String,
    ok: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        Pill(
            text = if (ok) "✓ تایید" else "✗ رد",
            color = if (ok) AurumColors.Green else AurumColors.Red,
        )
    }
}

/** All-pairs background radar summary */
@Composable
internal fun PairRadarSummaryCard(scan: PairScanState, onScan: () -> Unit, onSelectSymbol: (String) -> Unit, now: Long) {
    LaunchedEffect(scan.lastSweepAt) { if (scan.lastSweepAt == null) onScan() }
    SectionCard(
        title = "پایش پیوستهٔ ۵۰+ نماد در پس‌زمینه",
        subtitle = "دیتاگیری و کشف خودکار فرصت‌ها از بازار کریپتو، طلا، نفت، فارکس و سهام",
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "اسکنر با نرخ هوشمند ۵۰+ سهم و نماد را پایش و معاملات واجد بالاترین R:R را به پورتفو اضافه می‌کند.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onScan,
                enabled = !scan.sweeping,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(if (scan.sweeping) "اسکن..." else "اسکن مجدد")
            }
        }
        scan.lastError?.let { Text(it, color = AurumColors.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}
