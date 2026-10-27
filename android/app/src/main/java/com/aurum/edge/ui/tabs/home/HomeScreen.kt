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
import com.aurum.edge.ui.components.TradingViewPositionBox
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
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val now by viewModel.clock.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val tradesState by viewModel.trades.collectAsStateWithLifecycle()
    val openTradesList = remember(tradesState) { tradesState.filter { it.isOpen } }
    val recentClosedTrades = remember(tradesState) { tradesState.filter { !it.isOpen }.take(5) }
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val lastMarks by viewModel.lastDisplayMarks.collectAsStateWithLifecycle()
    val scan by viewModel.pairScan.collectAsStateWithLifecycle()
    val strategyReports by viewModel.allSymbolStrategyReports.collectAsStateWithLifecycle()
    val dualReports by viewModel.dualSymbolStrategyReports.collectAsStateWithLifecycle()

    val freshPrices = com.aurum.edge.data.OpenPositionQuotePolicy.prices(openTradesList, liveTicks, now)
    val frozenPrices = openTradesList.mapNotNull { trade ->
        OpenPositionDisplay.forTrade(trade, lastMarks)?.let { trade.symbol to it.price }
    }.toMap()
    val displayPrices = frozenPrices + freshPrices
    val displayPnl = LiveTradeValuation.total(openTradesList, displayPrices)
    val totalMarginUsed = openTradesList.sumOf { it.effectiveMarginUsd }
    val equity = displayPnl?.let { settings.accountBalance + it }
    val freeMargin = equity?.let { (it - totalMarginUsed).coerceAtLeast(0.0) }
    val readyCount = dualReports.values.sumOf { it.actionableReports.size }.takeIf { it > 0 }
        ?: strategyReports.values.count { it.isActionable }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
    ) {
        // ── ۱. نوار وضعیت سریع ربات و استراتژی‌ها (تمام تنظیمات در تب «تنظیمات» است) ────────────
        SectionCard(
            title = "وضعیت موتور ایچیموکو (15m + 5m)",
            subtitle = "${settings.activeStrategiesSummaryFa()} · سیگنال آماده ورود: $readyCount · بدون محدودیت تعداد معامله",
            trailing = {
                Pill(
                    text = "⚙️ تنظیمات",
                    color = AurumColors.Gold,
                    modifier = Modifier.clickable { onOpenSettings() },
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = if (settings.autoPaperTrading) "🟢 معامله خودکار روشن" else "⚪ معامله خودکار خاموش",
                        color = if (settings.autoPaperTrading) AurumColors.Green else AurumColors.TextMuted,
                        modifier = Modifier.clickable { onOpenSettings() },
                    )
                    Pill(
                        text = if (settings.backgroundMonitor) "🟢 بک‌گراند ۲۴/۷" else "⚪ بک‌گراند خاموش",
                        color = if (settings.backgroundMonitor) AurumColors.Cyan else AurumColors.TextMuted,
                        modifier = Modifier.clickable { onOpenSettings() },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("BTC/USDT" to "رمزارز", "XAU/USD" to "طلا", "EUR/USD" to "فارکس").forEach { (sym, lbl) ->
                        val closed = MarketHours.sessionWindowFor(sym, now).closed
                        Pill("$lbl: ${if (closed) "بسته" else "باز"}", if (closed) AurumColors.Red else AurumColors.Green)
                    }
                }
            }
        }

        // ── ۲. خلاصه حساب و آمار پورتفو ───────────────────────────────────
        SectionCard(
            title = "وضعیت حساب و پورتفو",
            subtitle = "وین‌ریت: ${stats.winRate?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"} · کل معاملات: ${stats.total} (${stats.wins} برد / ${stats.losses} باخت) · ریسک هر معامله: $${settings.fixedRiskUsd.toInt()}",
            trailing = {
                Pill("${openTradesList.size} پوزیشن باز (بدون محدودیت)", if (openTradesList.isNotEmpty()) AurumColors.Gold else AurumColors.TextMuted)
            },
        ) {
            val balanceStr = String.format(Locale.US, "$%,.2f", settings.accountBalance)
            val equityStr = equity?.let { String.format(Locale.US, "$%,.2f", it) } ?: balanceStr
            val freeMarginStr = freeMargin?.let { String.format(Locale.US, "$%,.2f", it) } ?: balanceStr
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

        // ── ۴. معاملات باز زنده (با نام استراتژی و تایم ورود) ────────────────────────
        LiveOpenTradesTrackingSection(
            viewModel = viewModel,
            currentMarket = market,
            lastMarks = lastMarks,
            now = now,
            onJournal = onJournal,
            onSelectTrade = onChartSymbol,
        )

        // ── ۵. آخرین معاملات بسته‌شده در خانه (با نام استراتژی و تایم ورود) ──────────
        if (recentClosedTrades.isNotEmpty()) {
            SectionCard(
                title = "آخرین معاملات بسته‌شده (به تفکیک استراتژی)",
                subtitle = "نمایش استراتژی بازکننده و تایم‌فریم ورود هر معامله",
                trailing = {
                    Pill(
                        text = "کل ژورنال ↗",
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { onJournal() },
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentClosedTrades.forEach { trade ->
                        val pnl = trade.pnlUsd ?: 0.0
                        val col = if (pnl > 0.0) AurumColors.Green else if (pnl < 0.0) AurumColors.Red else AurumColors.Gold
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AurumColors.SurfaceAlt, RoundedCornerShape(10.dp))
                                .border(1.dp, col.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .clickable { onChartSymbol(trade.symbol) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(trade.symbol, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AurumColors.Gold)
                                    Pill(if (trade.action == SignalAction.BUY) "خرید" else "فروش", if (trade.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red)
                                    Pill("تایم: ${trade.interval.label}", AurumColors.Gold)
                                }
                                Text(
                                    text = "📌 باز شده با: ${trade.strategyTitleFa}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.Cyan,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "%s$%.2f", if (pnl >= 0) "+" else "", pnl),
                                style = MaterialTheme.typography.bodySmall,
                                color = col,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
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
    val selectedTick = com.aurum.edge.data.OpenPositionQuotePolicy.selectedFallback(trade, currentMarket, now)
    val polledTick = com.aurum.edge.data.OpenPositionQuotePolicy.monitored(trade, liveTicks[trade.symbol], now)
    val verifiedLivePrice = (polledTick ?: selectedTick)?.price ?: lastMark?.price

    TradingViewPositionBox(
        trade = trade,
        currentPrice = verifiedLivePrice,
        onOpenChart = onSelect,
    )
}
