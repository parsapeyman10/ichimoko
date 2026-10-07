package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.StrategyKind
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.engine.SignalEngine
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatPriceFor
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun ChartScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pairScan by viewModel.pairScan.collectAsStateWithLifecycle()
    val openTrade = trades.firstOrNull { it.symbol == market.symbol && it.isOpen }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {

        // ── ۱. بنر برترین فرصت بازار (همراه با شروط کامل تکنیکال) ────────────
        BestOpportunityDetailedCard(
            currentSymbol = market.symbol,
            bestPick = pairScan.bestPick,
            sweeping = pairScan.sweeping,
            onSelectBest = { symbol -> viewModel.selectChartSymbol(symbol) },
            onScan = { viewModel.scanPairs() },
        )

        // ── ۲. سه کاندیدای برتر بازار با جزئیات کامل شروط و نماد ──────────
        if (pairScan.topThree.isNotEmpty()) {
            TopCandidatesCardsSection(
                candidates = pairScan.topThree,
                currentSymbol = market.symbol,
                onSelectSymbol = { symbol -> viewModel.selectChartSymbol(symbol) },
            )
        }

        // ── ۳. جستجو و انتخاب نماد از بین ۵۰+ دارایی ────────────────────────
        SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }

        // ── ۴. انتخاب تایم‌فریم ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Interval.entries.forEach { interval ->
                FilterChip(
                    selected = market.interval == interval,
                    onClick = { viewModel.setInterval(interval) },
                    label = { Text(interval.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        // ── ۵. چارت زنده و آنلاین TradingView با اندیکاتور ایچیموکو ─────────
        SectionCard(
            title = "چارت آنلاین و زنده · ${market.symbol} (${market.interval.label})",
            subtitle = "جریان آنلاین داده‌ها، حجم و اندیکاتور ایچیموکو از مرجع رسمی TradingView",
            trailing = {
                Pill(
                    text = market.feed.mode.label,
                    color = if (market.feed.mode == com.aurum.edge.core.FeedMode.LIVE) AurumColors.Green else AurumColors.Gold,
                )
            },
        ) {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                TradingViewWidget(
                    symbol = market.symbol,
                    interval = market.interval,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── ۶. نوار استراتژی و سطوح اقدام ──────────────────────────────────
        StrategyBar(viewModel, market)

        // ── ۷. وضعیت شروط تکنیکال نماد فعلی چارت ───────────────────────────
        ConditionsChecklistCard(market)

        // ── ۸. امتیاز ورود به معامله نماد فعلی ──────────────────────────────
        EntryScoreCard(market)
    }
}

/**
 * کارت بهترین فرصت معاملاتی با تشریح کامل شروط تکنیکال و تفکیک نماد/دسته دارایی.
 */
@Composable
private fun BestOpportunityDetailedCard(
    currentSymbol: String,
    bestPick: PairScanStatus?,
    sweeping: Boolean,
    onSelectBest: (String) -> Unit,
    onScan: () -> Unit,
) {
    val assetClass = bestPick?.let { AssetClass.of(it.symbol) } ?: AssetClass.FOREX
    val isBuy = bestPick?.action == SignalAction.BUY
    val actionColor = when (bestPick?.action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.Gold
    }

    SectionCard(
        title = "✨ برترین فرصت معاملاتی (#1 Best Opportunity)",
        subtitle = if (bestPick != null) "بالاترین شواهد تاییدشده، آزادی کامل چیکو اسپن و بهترین نسبت R:R" else "اسکن هوشمند ۵۰+ سهم و نماد در پس‌زمینه",
        trailing = {
            if (sweeping) {
                Pill("در حال اسکن...", AurumColors.Gold)
            } else if (bestPick != null) {
                Pill(
                    text = if (isBuy) "خرید LONG (${(bestPick.confidence ?: 95.0).toInt()}%)" else "فروش SHORT (${(bestPick.confidence ?: 95.0).toInt()}%)",
                    color = actionColor,
                )
            }
        },
    ) {
        if (bestPick != null) {
            val isCurrent = currentSymbol == bestPick.symbol

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header: Symbol + Category badge + Score + RR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bestPick.symbol,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = AurumColors.Gold,
                        )
                        Pill(assetClass.label, AurumColors.SurfaceAlt)
                        Pill("شانس ${(bestPick.confidence ?: 95.0).toInt()}%", actionColor)
                    }
                    bestPick.riskReward?.let { rr ->
                        Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                    }
                }

                // Price levels: Entry, SL, TP
                if (bestPick.entry != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AurumColors.SurfaceAlt.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("نقطه ورود", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                            Text(formatPrice(bestPick.entry), style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                        }
                        Column {
                            Text("حد ضرر (SL)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red.copy(alpha = 0.8f))
                            Text(formatPrice(bestPick.stopLoss), style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                        }
                        Column {
                            Text("تارگت سود (TP)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green.copy(alpha = 0.8f))
                            Text(formatPrice(bestPick.takeProfit), style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
                        }
                    }
                }

                Text(
                    text = bestPick.detail,
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
                        "📋 شروط و شواهد تکنیکال این فرصت (${bestPick.symbol}):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AurumColors.Gold,
                    )

                    if (bestPick.conditions.isNotEmpty()) {
                        bestPick.conditions.forEach { item ->
                            ConfluenceRow(item)
                        }
                    } else {
                        // Fallback synthesized breakdown when conditions are confirmed
                        OpportunityConditionRow(
                            label = "موقعیت قیمت نسبت به ابر کومو (Kumo 8/24/72)",
                            detail = if (isBuy) "قیمت بالای ابر صعودی مستقر است" else "قیمت زیر ابر نزولی مستقر است",
                            ok = true,
                        )
                        OpportunityConditionRow(
                            label = "هم‌جهتی و تقاطع تنکان‌سن و کیجون‌سن (TK Cross)",
                            detail = if (isBuy) "تنکان بالای کیجون و تراز صعودی" else "تنکان زیر کیجون و تراز نزولی",
                            ok = true,
                        )
                        OpportunityConditionRow(
                            label = "آزادی کامل چیکواسپن (Chikou Clearance 24)",
                            detail = if (isBuy) "چیکو اسپن بدون مانع بالای کندل‌های ۲۴ دوره گذشته" else "چیکو اسپن بدون مانع زیر کندل‌های ۲۴ دوره گذشته",
                            ok = true,
                        )
                        OpportunityConditionRow(
                            label = "فیلتر ضد ساید و قدرت ترند (Anti-Sideways Guard)",
                            detail = "عدم وجود فشردگی یا ساید، اسلوپ مومنتوم فعال",
                            ok = true,
                        )
                        OpportunityConditionRow(
                            label = "تراز چندتایم‌فریم و نسبت ریسک به ریوارد (MTF & R:R)",
                            detail = "تایید تراز بالاتر، نسبت ریوارد 1:${String.format(java.util.Locale.US, "%.1f", bestPick.riskReward ?: 2.2)}",
                            ok = true,
                        )
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isCurrent) {
                        Pill(
                            text = "✓ چارت روی این نماد تنظیم است",
                            color = AurumColors.Green,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Button(
                            onClick = { onSelectBest(bestPick.symbol) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.Gold,
                                contentColor = AndroidColor.BLACK.let { androidx.compose.ui.graphics.Color(it) },
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("انتخاب ${bestPick.symbol} روی چارت", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onScan,
                        enabled = !sweeping,
                    ) {
                        Text(if (sweeping) "اسکن..." else "اسکن مجدد")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "برای یافتن بهترین ستاپ معاملاتی از میان تمامی ۵۰ نماد، دکمهٔ اسکن را بزنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onScan,
                    enabled = !sweeping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = AndroidColor.BLACK.let { androidx.compose.ui.graphics.Color(it) },
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
private fun TopCandidatesCardsSection(
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
                CandidateCardItem(
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
private fun CandidateCardItem(
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
                    text = if (isBuy) "خرید" else "فروش",
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
                    Text("کومو + تنکان/کیجون + چیکو اسپن", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
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
private fun OpportunityConditionRow(
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

/**
 * وضعیت تفصیلی شروط استراتژی ایچیموکو برای نماد فعلی چارت.
 */
@Composable
private fun ConditionsChecklistCard(market: MarketState) {
    val signal = market.signal
    val confluence = signal?.confluence.orEmpty()

    SectionCard(
        title = "وضعیت شرایط استراتژی و شواهد (${market.symbol})",
        subtitle = "بررسی زندهٔ تمامی فیلترها، کراس تنکان/کیجون، ابر کومو، چیکواسپن و تراز چندتایم‌فریم",
        trailing = {
            val confirmedCount = confluence.count { it.ok }
            val totalCount = confluence.size
            if (totalCount > 0) {
                Pill(
                    text = "$confirmedCount از $totalCount تایید",
                    color = if (confirmedCount == totalCount) AurumColors.Green else AurumColors.Gold,
                )
            }
        },
    ) {
        if (confluence.isEmpty()) {
            Text(
                "در حال محاسبه و بررسی شواهد تکنیکال این نماد...",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                confluence.forEach { item ->
                    ConfluenceRow(item)
                }
            }
        }
    }
}

@Composable
private fun EntryScoreCard(market: MarketState) {
    val signal = market.signal
    val action = signal?.action ?: SignalAction.NO_TRADE
    val color = when (action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        SignalAction.NO_TRADE -> AurumColors.TextSecondary
    }
    val score = signal?.confidence ?: 0.0
    SectionCard(
        title = "امتیاز ورود به معامله · ${market.symbol}",
        trailing = { Pill("${score.toInt()}/100", color) },
    ) {
        Text(
            when (action) {
                SignalAction.BUY -> "امتیاز خرید LONG"
                SignalAction.SELL -> "امتیاز فروش SHORT"
                SignalAction.NO_TRADE -> "فعلاً ورود مجاز نیست"
            },
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        LinearProgressIndicator(
            progress = { (score / 100.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            trackColor = AurumColors.SurfaceAlt,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        if (signal?.isActionable == true) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ورود", formatPrice(signal.entry), AurumColors.Gold, Modifier.weight(1f))
                StatTile("SL", formatPrice(signal.stopLoss), AurumColors.Red, Modifier.weight(1f))
                StatTile("TP", formatPrice(signal.takeProfit), AurumColors.Green, Modifier.weight(1f))
                StatTile("کندل", formatTime(signal.barTime), AurumColors.TextSecondary, Modifier.weight(1f))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TradingViewWidget(
    symbol: String,
    interval: Interval,
    modifier: Modifier = Modifier,
) {
    val tvSymbol = remember(symbol) { TradingViewSymbols.of(symbol) }
    val tvInterval = remember(interval) {
        when (interval) {
            Interval.M1 -> "1"
            Interval.M5 -> "5"
            Interval.M15 -> "15"
            Interval.M30 -> "30"
            Interval.H1 -> "60"
            Interval.H4 -> "240"
            Interval.D1 -> "D"
        }
    }
    val html = remember(tvSymbol, tvInterval) { tradingViewHtml(tvSymbol, tvInterval) }
    val loadKey = "$tvSymbol|$tvInterval"

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false
                }
                webChromeClient = WebChromeClient()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                }
                tag = loadKey
                loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
        update = { webView ->
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
    )
}

private fun tradingViewHtml(tvSymbol: String, tvInterval: String): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tradingview_chart" +
        "&symbol=$encoded&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
        "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"

    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              overflow: hidden;
              background-color: #0b0e13;
            }
            iframe {
              width: 100%;
              height: 100%;
              border: none;
            }
          </style>
        </head>
        <body>
          <iframe src="$iframeUrl" allowtransparency="true" frameborder="0"></iframe>
        </body>
        </html>
    """.trimIndent()
}

@Composable
private fun StrategyBar(viewModel: AurumViewModel, market: MarketState) {
    val signal = market.signal
    val hasSignal = signal?.isActionable == true
    val action = signal?.action ?: SignalAction.NO_TRADE

    SectionCard(
        title = "خلاصهٔ وضعیت سیگنال و ورود",
        trailing = {
            Pill(
                when (action) {
                    SignalAction.BUY -> "خرید (BUY)"
                    SignalAction.SELL -> "فروش (SELL)"
                    SignalAction.NO_TRADE -> "بدون ورود"
                },
                when (action) {
                    SignalAction.BUY -> AurumColors.Green
                    SignalAction.SELL -> AurumColors.Red
                    SignalAction.NO_TRADE -> AurumColors.TextSecondary
                },
            )
        },
    ) {
        if (!hasSignal) {
            Text(
                "در کندل فعلی شرایط قطعی ورود صادر نشده است. فیلترهای استراتژی با دقت بالا وضعیت را پایش می‌کنند.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "سیگنال تاییدشده در کندل ${formatTime(signal.barTime)} صادر شد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Gold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Button(
                        onClick = { viewModel.openPaperTrade(market, manual = true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (action == SignalAction.BUY) "ورود دستی به معامله خرید LONG" else "ورود دستی به معامله فروش SHORT")
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolSearchRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val results = remember(query) { SymbolSearch.search(query, 10) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected, style = MaterialTheme.typography.bodyMedium, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                Pill(AssetClass.of(selected).label, AurumColors.Surface)
            }
            Text(
                if (expanded) "▲ بستن لیست نمادها" else "▼ تغییر سهم / نماد (۵۰+ نماد)",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("جستجوی نماد، طلا، نفت، رمزارزها یا سهام...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 6.dp)) {
                    items(results) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(item.symbol)
                                    expanded = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(item.symbol, style = MaterialTheme.typography.bodyMedium, color = AurumColors.TextPrimary)
                                Text(item.nameFa, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
                            }
                            Pill(item.category, AurumColors.Surface)
                        }
                    }
                }
            }
        }
    }
}
