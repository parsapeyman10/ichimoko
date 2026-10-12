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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.data.PairScanState
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * صفحه اصلی (Home Screen):
 * ۱. در بالای صفحه وضعیت باز یا بسته بودن بازار
 * ۲. ترکینگ زنده و لحظه‌ای تمامی معاملات باز پورتفو با نمایش تراز SL -> قیمت ورود -> TP
 * ۳. پنل معاملهٔ خودکار و آمار کلی پورتفو
 */
@Composable
fun HomeScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onChartSymbol: (String) -> Unit,
    onJournal: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            now = System.currentTimeMillis()
        }
    }
    val session = MarketHours.sessionWindowFor(market.symbol, now)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 14.dp)) {

        // ── ۱. وضعیت باز یا بسته بودن بازار (بالاترین بخش صفحه) ────────────
        Column(
            Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)
                .background(
                    Brush.horizontalGradient(listOf(AurumColors.SurfaceAlt, AurumColors.Surface)),
                    RoundedCornerShape(18.dp),
                )
                .border(
                    1.dp,
                    (if (session.closed) AurumColors.Red else AurumColors.Green).copy(alpha = 0.35f),
                    RoundedCornerShape(18.dp),
                )
                .padding(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ساعت برنامه‌ای ${AssetClass.of(market.symbol).label} · ${market.symbol}", color = AurumColors.Gold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (session.closed) "سشن این نماد بسته است" else "طبق برنامه باز؛ فید را بررسی کنید",
                        color = if (session.closed) AurumColors.Red else AurumColors.Green,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Pill(
                    text = if (session.closed) "بسته" else if (AssetClass.of(market.symbol) == AssetClass.CRYPTO) "۲۴/۷" else "ساعت مجاز",
                    color = if (session.closed) AurumColors.Red else AurumColors.Green,
                )
            }
            Text(
                "${session.nextChangeLabel}: ${formatDateTime(session.nextChangeAt)}",
                color = AurumColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                session.newYorkTimeLabel,
                color = AurumColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                session.detail,
                color = AurumColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            listOf("EUR/USD" to "فارکس", "XAU/USD" to "طلا/کالا",
                "AAPL" to "سهام آمریکا", "BTC/USDT" to "رمزارز").forEach { (symbol, label) ->
                val scheduled = MarketHours.sessionWindowFor(symbol, now)
                val note = if (symbol == "BTC/USDT") "۲۴/۷" else "برای قیمت/تعطیلی خاص، فید همان نماد لازم است"
                Text("$label: ${if (scheduled.closed) "سشن بسته" else "طبق برنامه باز"} · $note",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (scheduled.closed) AurumColors.Red else AurumColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ── ۲. بنر وضعیت ۴ دسته دارایی (رمزارز، فارکس، طلا/کالا، سهام) در ۴ مستطیل بالا ──
        val tradesState by viewModel.trades.collectAsStateWithLifecycle()
        val openTradesList = remember(tradesState) { tradesState.filter { it.isOpen } }
        val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
        val lastMarks by viewModel.lastDisplayMarks.collectAsStateWithLifecycle()
        val freshPrices = com.aurum.edge.data.OpenPositionQuotePolicy.prices(openTradesList, liveTicks, now)
        // Historical display marks never enter freshPrices, account equity or risk checks.
        val frozenPrices = openTradesList.mapNotNull { trade ->
            OpenPositionDisplay.forTrade(trade, lastMarks)?.let { trade.symbol to it.price }
        }.toMap()

        val scan by viewModel.pairScan.collectAsStateWithLifecycle()
        val homeSettings by viewModel.settings.collectAsStateWithLifecycle()
        com.aurum.edge.ui.components.AssetClass4SlotsBanner(
                openTrades = openTradesList,
                livePrices = freshPrices,
                lastKnownPrices = frozenPrices,
                onSelectSymbol = onChartSymbol,
                entryReasons = AssetClass.entries.associateWith { asset ->
                    com.aurum.edge.data.MarketEntryReason.forClass(asset, scan,
                        homeSettings.autoPaperTrading, now)
                },
            )
        if (openTradesList.isNotEmpty()) {
            LiveOpenTradesTrackingSection(
                viewModel = viewModel,
                currentMarket = market,
                lastMarks = lastMarks,
                now = now,
                onJournal = onJournal,
                onSelectTrade = onChartSymbol,
            )
        } else {
            // Instead of four large empty position cards and a second empty tracking card,
            // fill the space with the actual per-market scanner progress and provenance.
            HomeRadarCard(scan, market.symbol, now, onChartSymbol)
        }

        // ── ۴. پنل معاملهٔ خودکار کاغذی و تخصیص ۴ بازار ────────────────────
        AutoPaperCard(viewModel, onJournal, now)
    }
}

/** Closed-bar radar is read-only. It never presents a scanner candle as a live trade price. */
@Composable
private fun HomeRadarCard(
    scan: PairScanState,
    selectedSymbol: String,
    now: Long,
    onChartSymbol: (String) -> Unit,
) {
    val selected = scan.statuses.firstOrNull { it.symbol == selectedSymbol }
    val categories = listOf(
        AssetClass.COMMODITY to "کالا", AssetClass.FOREX to "فارکس",
        AssetClass.CRYPTO to "رمزارز", AssetClass.STOCK to "سهام",
    )
    SectionCard(
        title = "رادار بازارها · بدون پوزیشن باز",
        subtitle = "پیشرفت واقعی اسکن کندل‌های بسته؛ نه قیمت لحظه‌ای یا وعدهٔ ورود",
        trailing = { Pill("${scan.checkedCount}/${scan.totalCount}",
            if (scan.sweeping) AurumColors.Cyan else AurumColors.TextSecondary) },
    ) {
        categories.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (asset, label) ->
                    val rows = scan.statuses.filter { it.assetClass == asset }
                    StatTile(label, "${rows.count { it.state != "pending" }}/${rows.size} بررسی",
                        modifier = Modifier.weight(1f))
                }
            }
        }
        Text(
            when {
                scan.sweeping -> "اسکن در جریان است؛ نتیجهٔ هر نماد بعد از دریافت خودش نمایش داده می‌شود."
                scan.lastSweepAt != null -> "آخرین دور کامل: ${relativeTime(scan.lastSweepAt)}"
                else -> "هنوز دور کاملی تمام نشده؛ خطای شبکه یا بسته‌بودن بازار را بررسی کنید."
            }, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
        )
        scan.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.Red) }
        selected?.let { row ->
            val label = when (row.state) {
                "pending" -> "در صف"; "closed" -> "سشن بسته"; "error" -> "خطای فید"
                "partial" -> "داده ناقص"; "no_signal" -> "بدون سیگنال"
                "blocked" -> "مسدود"; "observed" -> "فقط مشاهده"
                "candidate" -> "کاندیدا"; "opened" -> "معامله ثبت شد"
                else -> "نامعلوم"
            }
            Text("${row.symbol} · $label: ${row.detail}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary,
                modifier = Modifier.padding(top = 8.dp))
            Text("کندل پایه: ${row.baseSource ?: "هنوز دریافت نشده"} · بسته‌شدن: " +
                (row.lastBarAt?.let { formatDateTime(it) } ?: "نامعلوم") +
                " · بررسی: " + (row.lastScanAt?.let { relativeTime(it) } ?: "هنوز نه"),
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp))
        }
        val best = com.aurum.edge.data.ScanRanking.rank(scan.statuses,
            com.aurum.edge.core.Interval.M5, now).firstOrNull()
        if (best != null) {
            Text("مورد فنیِ این دور: ${best.symbol} · ${best.interval?.label ?: "—"} · مشاهده چارت ↗",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Cyan,
                modifier = Modifier.padding(top = 8.dp).clickable { onChartSymbol(best.symbol) })
        }
    }
}

/**
 * بخش ترکینگ زندهٔ همه معاملات باز (حداکثر ۴ پوزیشن در ۴ بازار)
 */
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
        title = "⚡ ترکینگ معاملات باز",
        subtitle = "آخرین قیمت تأییدشده میان حد ضرر (SL)، ورود و حد سود (TP)",
        trailing = {
            Pill(
                text = "${openTrades.size} از ۴ فعال",
                color = if (openTrades.isNotEmpty()) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        if (openTrades.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "در حال حاضر هیچ معاملهٔ بازی در پورتفو وجود ندارد.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    text = "سیستم به شکل پیوسته بیش از ۵۰ نماد را در ۴ دسته دارایی (رمزارز، فارکس، کالا و سهام) پایش می‌کند تا معاملات دارای بیشترین نسبت R:R را شکار کند.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "مدیریت، بستن و مشاهده تاریخچه کامل در ژورنال",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { onJournal() },
                    )
                }
            }
        }
    }
}

/**
 * کارت ترکینگ زنده معامله با گیج متحرک بین: SL -> Entry -> TP
 */
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
    // A frozen mark is only a previously verified display snapshot for this exact trade.
    // It is never put back into the live tick map or used for settling/risk/equity.
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
    val movementLabel = when {
        valuation == null -> "در انتظار اولین قیمت"
        (if (isBuy) valuation.price - entry else entry - valuation.price) > 0.0 -> "↗ در مسیر حد سود (TP)"
        (if (isBuy) valuation.price - entry else entry - valuation.price) < 0.0 -> "↘ در مسیر حد ضرر (SL)"
        else -> "⚪ آخرین قیمت روی نقطه ورود (Entry)"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .clickable { onSelect() },
    ) {
        // Top Header: Symbol, Class, Direction, PnL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = trade.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(assetClass.label, AurumColors.Cyan)
                    Pill(if (isBuy) "خرید" else "فروش",
                        if (isBuy) AurumColors.Green else AurumColors.Red)
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(
                    text = valuation?.let {
                        "${if (it.netPnlUsd >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", it.netPnlUsd)}$"
                    } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Text(
                    text = valuation?.let {
                        "سود/زیان آخرین قیمت" +
                            " (${String.format(java.util.Locale.US, "%.2f", it.pnlPercent)}%)"
                    } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
        }

        // Financial Details: Leverage, Margin, Volume, Commission & Spread
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(AurumColors.Surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                "اهرم: ${trade.effectiveLeverage}x · مارجین: $${formatPrice(trade.effectiveMarginUsd)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
            )
            Text(
                "کارمزد: $${formatPrice(trade.effectiveCommissionUsd)} · اسپرد: $${formatPrice(trade.effectiveSpreadCostUsd)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        // Live status & movement
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                text = movementLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = valuation?.let {
                    "آخرین قیمت: " + formatPrice(it.price) +
                        (observedAt?.let { at -> " · ${formatDateTime(at)}" } ?: "")
                } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        // ── Visual Interactive SL -> Entry -> TP Tracker Bar ──────────────
        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {

            // Labels above the bar: SL | Entry | TP
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text("حد ضرر (SL)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red, fontWeight = FontWeight.Bold)
                    Text(formatPrice(stop), style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("قیمت ورود", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold, fontWeight = FontWeight.Bold)
                    Text(formatPrice(entry), style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("حد سود (TP)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green, fontWeight = FontWeight.Bold)
                    Text(formatPrice(target), style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
                }
            }

            // The Physical Range Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Background Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AurumColors.Surface),
                )

                // Loss Zone (SL to Entry) in Red
                Box(
                    modifier = Modifier
                        .fillMaxWidth(entryFraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AurumColors.Red.copy(alpha = 0.4f)),
                )

                // Profit Zone (Entry to TP) in Green
                Box(
                    modifier = Modifier
                        .fillMaxWidth(1f - entryFraction)
                        .height(8.dp)
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AurumColors.Green.copy(alpha = 0.4f)),
                )

                // Entry Divider Marker
                Box(
                    modifier = Modifier
                        .fillMaxWidth(entryFraction)
                        .height(14.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(14.dp)
                            .background(AurumColors.Gold),
                    )
                }

                // The last verified pointer stays in place when frozen; the gold divider
                // above is the saved ENTRY marker, not a substitute current price.
                valuation?.let { last ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(last.progress.coerceIn(0.02f, 0.98f))
                            .height(20.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                                .border(2.dp, AurumColors.TextPrimary, CircleShape),
                        )
                    }
                }
            }

            // Bottom Info: R:R ratio & quick chart navigate
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    "ریسک به ریوارد: 1:${String.format(java.util.Locale.US, "%.1f", trade.riskReward)} · حجم: ${String.format("%.4f", trade.positionOz)} ${trade.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
                Text("نمایش روی چارت ↗", style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/**
 * پنل معاملهٔ خودکار کاغذی در صفحه اصلی
 */
@Composable
private fun AutoPaperCard(viewModel: AurumViewModel, onJournal: () -> Unit, now: Long) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val liveTicks by viewModel.liveTicks.collectAsStateWithLifecycle()
    val lastMarks by viewModel.lastDisplayMarks.collectAsStateWithLifecycle()
    val enabled = settings.autoPaperTrading
    val openTrades = remember(trades) { trades.filter { it.isOpen } }
    val freshPrices = com.aurum.edge.data.OpenPositionQuotePolicy.prices(openTrades, liveTicks, now)
    val lastTrade = trades.maxByOrNull { it.openedAt }

    val totalMarginUsed = openTrades.sumOf { it.effectiveMarginUsd }
    // Screen-only valuation. Never reuse these remembered prices for risk, fills or settlement.
    val displayPrices = openTrades.mapNotNull { trade ->
        OpenPositionDisplay.forTrade(trade, lastMarks)?.let { trade.symbol to it.price }
    }.toMap() + freshPrices
    val displayPnl = LiveTradeValuation.total(openTrades, displayPrices)
    val equity = displayPnl?.let { settings.accountBalance + it }
    val freeMargin = equity?.let { (it - totalMarginUsed).coerceAtLeast(0.0) }

    SectionCard(
        title = "مدیریت حساب و معاملهٔ خودکار کاغذی",
        subtitle = "سقف ۴ معامله؛ حداکثر ۲ در هر دسته با کنترل ریسک",
        trailing = {
            Pill(
                if (enabled) "روشن" else "خاموش",
                if (enabled) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (enabled) "اسکن کندل‌های بسته با نظارت مستقلِ پوزیشن‌های باز؛ ورود فقط با تیک واقعی و عبور از ریسک."
                else "خاموش است؛ هیچ معاملهٔ خودکاری ثبت نمی‌شود.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
            Switch(checked = enabled, onCheckedChange = viewModel::setAutoPaperTrading)
        }

        val balanceStr = String.format(java.util.Locale.US, "$%.2f", settings.accountBalance)
        val equityStr = equity?.let { String.format(java.util.Locale.US, "$%.2f", it) } ?: "—"
        val freeMarginStr = freeMargin?.let { String.format(java.util.Locale.US, "$%.2f", it) } ?: "—"
        val usedMarginStr = String.format(java.util.Locale.US, "$%.2f", totalMarginUsed)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("موجودی", balanceStr, modifier = Modifier.weight(1f))
            StatTile("برآورد اکوئیتی", equityStr,
                if (displayPnl == null) AurumColors.TextMuted
                else if (displayPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("برآورد مارجین آزاد", freeMarginStr, AurumColors.Cyan, Modifier.weight(1f))
            StatTile("مارجین درگیر", usedMarginStr, AurumColors.Gold, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("سود/زیان محقق‌شده",
                (if (stats.netPnl >= 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", stats.netPnl) + "$",
                if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            StatTile("پوزیشن‌های باز", "${openTrades.size}/۴", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("کل معاملات", stats.total.toString(), modifier = Modifier.weight(1f))
            StatTile("برد/باخت", "${stats.wins}/${stats.losses}", modifier = Modifier.weight(1f))
        }
        Text("وین‌ریت ثبت‌شده: ${stats.winRate?.let { String.format(java.util.Locale.US, "%.0f%%", it) } ?: "—"}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 6.dp))

        if (lastTrade != null) {
            val lastClass = AssetClass.of(lastTrade.symbol)
            Text(
                "آخرین معامله: ${lastTrade.symbol} (${lastClass.label}) · ${relativeTime(lastTrade.openedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Text(
            "دیدن همهٔ معاملات در تب ژورنال",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Cyan,
            modifier = Modifier.padding(top = 8.dp).clickable { onJournal() },
        )
    }
}
