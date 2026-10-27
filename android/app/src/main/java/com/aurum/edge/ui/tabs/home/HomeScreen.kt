package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs

@Composable
fun HomeScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onChartSymbol: (String) -> Unit,
    onJournal: () -> Unit,
) {
    val context = LocalContext.current
    val now by viewModel.clock.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val tradesState by viewModel.trades.collectAsStateWithLifecycle()
    val openTradesList = remember(tradesState) { tradesState.filter { it.isOpen } }
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val lastMarks by viewModel.lastDisplayMarks.collectAsStateWithLifecycle()
    val scan by viewModel.pairScan.collectAsStateWithLifecycle()
    val strategyReports by viewModel.allSymbolStrategyReports.collectAsStateWithLifecycle()
    val isBackfilling by viewModel.isBackfilling.collectAsStateWithLifecycle()

    val freshPrices = com.aurum.edge.data.OpenPositionQuotePolicy.prices(openTradesList, liveTicks, now)
    val frozenPrices = openTradesList.mapNotNull { trade ->
        OpenPositionDisplay.forTrade(trade, lastMarks)?.let { trade.symbol to it.price }
    }.toMap()
    val displayPrices = frozenPrices + freshPrices
    val displayPnl = LiveTradeValuation.total(openTradesList, displayPrices)
    val totalMarginUsed = openTradesList.sumOf { it.effectiveMarginUsd }
    val equity = displayPnl?.let { settings.accountBalance + it }
    val freeMargin = equity?.let { (it - totalMarginUsed).coerceAtLeast(0.0) }
    val readyCount = strategyReports.values.count { it.isActionable }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
    ) {
        // ── ۱. پنل کنترل سریع: معامله خودکار + پایش بک‌گراند ۲۴/۷ ────────────
        SectionCard(
            title = "ربات معامله‌گر و پایش بک‌گراند ۲۴/۷",
            subtitle = "${settings.activeBitcoinStrategyTitleFa()} · اسکن ۱۵ نماد (15m) · آماده ورود: $readyCount/15",
            trailing = {
                Pill(
                    text = if (settings.autoPaperTrading || settings.backgroundMonitor) "بک‌گراند فعال" else "متوقف",
                    color = if (settings.autoPaperTrading || settings.backgroundMonitor) AurumColors.Green else AurumColors.TextMuted,
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("معامله خودکار", color = AurumColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(if (settings.autoPaperTrading) "ورود خودکار روشن" else "خاموش", color = if (settings.autoPaperTrading) AurumColors.Green else AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = settings.autoPaperTrading,
                        onCheckedChange = viewModel::setAutoPaperTrading,
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("پایش بک‌گراند", color = AurumColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(if (settings.backgroundMonitor) "سرویس ۲۴/۷ روشن" else "خاموش", color = if (settings.backgroundMonitor) AurumColors.Cyan else AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = settings.backgroundMonitor,
                        onCheckedChange = { viewModel.setBackgroundMonitor(it, context) },
                    )
                }
            }

            // خلاصه وضعیت سشن ۳ بازار اصلی + دکمه Gap-Backfill در یک ردیف فشرده
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("BTC/USDT" to "رمزارز", "XAU/USD" to "طلا", "EUR/USD" to "فارکس").forEach { (sym, lbl) ->
                        val closed = MarketHours.sessionWindowFor(sym, now).closed
                        Pill("$lbl: ${if (closed) "بسته" else "باز"}", if (closed) AurumColors.Red else AurumColors.Green)
                    }
                }
                Pill(
                    text = if (isBackfilling) "در حال همگام‌سازی..." else "همگام‌سازی گپ 🔄",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable(enabled = !isBackfilling) {
                        viewModel.syncAllOpenAndActiveSymbolsGap(notifyOnSuccess = true)
                    },
                )
            }
        }

        // ── ۲. خلاصه حساب و آمار پورتفو ───────────────────────────────────
        SectionCard(
            title = "وضعیت حساب و پورتفو",
            subtitle = "وین‌ریت: ${stats.winRate?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"} · کل معاملات: ${stats.total} (${stats.wins} برد / ${stats.losses} باخت)",
            trailing = {
                Pill("${openTradesList.size}/۴ پوزیشن باز", if (openTradesList.isNotEmpty()) AurumColors.Gold else AurumColors.TextMuted)
            },
        ) {
            val balanceStr = String.format(Locale.US, "$%.2f", settings.accountBalance)
            val equityStr = equity?.let { String.format(Locale.US, "$%.2f", it) } ?: balanceStr
            val freeMarginStr = freeMargin?.let { String.format(Locale.US, "$%.2f", it) } ?: balanceStr
            val netPnlStr = (if (stats.netPnl >= 0) "+" else "") + String.format(Locale.US, "%.2f$", stats.netPnl)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("موجودی", balanceStr, modifier = Modifier.weight(1f))
                StatTile(
                    "اکوئیتی زنده",
                    equityStr,
                    if (displayPnl == null) AurumColors.TextPrimary else if (displayPnl >= 0) AurumColors.Green else AurumColors.Red,
                    Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("مارجین آزاد", freeMarginStr, AurumColors.Cyan, Modifier.weight(1f))
                StatTile(
                    "سود/زیان کل",
                    netPnlStr,
                    if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red,
                    Modifier.weight(1f),
                )
            }
        }

        // ── ۳. بنر ۴ دسته دارایی ──────────────────────────────────────────
        com.aurum.edge.ui.components.AssetClass4SlotsBanner(
            openTrades = openTradesList,
            livePrices = freshPrices,
            lastKnownPrices = frozenPrices,
            onSelectSymbol = onChartSymbol,
            entryReasons = AssetClass.entries.associateWith { asset ->
                com.aurum.edge.data.MarketEntryReason.forClass(
                    asset,
                    scan,
                    settings.autoPaperTrading,
                    now,
                )
            },
        )

        // ── ۴. معاملات باز زنده (SL -> Entry -> TP) ────────────────────────
        LiveOpenTradesTrackingSection(
            viewModel = viewModel,
            currentMarket = market,
            lastMarks = lastMarks,
            now = now,
            onJournal = onJournal,
            onSelectTrade = onChartSymbol,
        )
    }
}

@Composable
private fun LiveOpenTradesTrackingSection(
    viewModel: AurumViewModel,
    currentMarket: MarketState,
    lastMarks: Map<String, DisplayMark>,
    now: Long,
    onJournal: () -> Unit,
    onSelectTrade: (String) -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val openTrades = remember(trades) { trades.filter { it.isOpen } }

    SectionCard(
        title = "معاملات باز زنده",
        subtitle = "پایش لحظه‌ای حد سود (TP)، حد ضرر (SL) و سیو سود ایچیموکو در بک‌گراند",
        trailing = {
            Pill(
                text = "${openTrades.size} فعال",
                color = if (openTrades.isNotEmpty()) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        if (openTrades.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "معامله بازی وجود ندارد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    text = "مشاهده سوابق در ژورنال ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { onJournal() },
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                openTrades.forEach { trade ->
                    LiveTradeGaugeCard(
                        trade = trade,
                        currentMarket = currentMarket,
                        liveTicks = liveTicks,
                        lastMark = OpenPositionDisplay.forTrade(trade, lastMarks),
                        now = now,
                        onSelect = { onSelectTrade(trade.symbol) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTradeGaugeCard(
    trade: PaperTrade,
    currentMarket: MarketState,
    liveTicks: Map<String, PriceTick>,
    lastMark: DisplayMark?,
    now: Long,
    onSelect: () -> Unit,
) {
    val isBuy = trade.action == SignalAction.BUY
    val assetClass = AssetClass.of(trade.symbol)
    val selectedTick = com.aurum.edge.data.OpenPositionQuotePolicy.selectedFallback(trade, currentMarket, now)
    val polledTick = com.aurum.edge.data.OpenPositionQuotePolicy.monitored(trade, liveTicks[trade.symbol], now)
    val verifiedLivePrice = (polledTick ?: selectedTick)?.price
    val observedAt = (polledTick ?: selectedTick)?.at ?: lastMark?.tickAt
    val valuation = LiveTradeValuation.from(trade, verifiedLivePrice ?: lastMark?.price)

    val entry = trade.entry
    val stop = trade.stopLoss
    val target = trade.takeProfit
    val totalRange = abs(target - stop).coerceAtLeast(0.000001)
    val entryFraction = if (isBuy) {
        ((entry - stop) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
    } else {
        ((stop - entry) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
    }

    val statusColor = when {
        valuation == null -> AurumColors.TextMuted
        valuation.netPnlUsd > 0.0 -> AurumColors.Green
        valuation.netPnlUsd < 0.0 -> AurumColors.Red
        else -> AurumColors.Gold
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp)
            .clickable { onSelect() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trade.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
                Pill(assetClass.label, AurumColors.Cyan)
                Pill(if (isBuy) "خرید" else "فروش", if (isBuy) AurumColors.Green else AurumColors.Red)
            }
            Text(
                text = valuation?.let {
                    "${if (it.netPnlUsd >= 0) "+" else ""}${String.format(Locale.US, "%.2f", it.netPnlUsd)}$ (${String.format(Locale.US, "%.2f", it.pnlPercent)}%)"
                } ?: "—",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor,
            )
        }

        Text(
            text = valuation?.let {
                "قیمت فعلی: ${formatPrice(it.price)}" + (observedAt?.let { at -> " · ${formatDateTime(at)}" } ?: "")
            } ?: "در انتظار تیک زنده...",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )

        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("SL: ${formatPrice(stop)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                Text("Entry: ${formatPrice(entry)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
                Text("TP: ${formatPrice(target)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(top = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AurumColors.Surface),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(entryFraction)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AurumColors.Red.copy(alpha = 0.4f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(1f - entryFraction)
                        .height(7.dp)
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AurumColors.Green.copy(alpha = 0.4f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(entryFraction)
                        .height(13.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(13.dp)
                            .background(AurumColors.Gold),
                    )
                }
                valuation?.let { last ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(last.progress.coerceIn(0.02f, 0.98f))
                            .height(16.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                                .border(2.dp, AurumColors.TextPrimary, CircleShape),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "R:R 1:${String.format(Locale.US, "%.1f", trade.riskReward)} · حجم: ${String.format(Locale.US, "%.4f", trade.positionOz)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
                Text(
                    "چارت ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
