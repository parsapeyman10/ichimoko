package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.HomeReadout
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatQuotePrice
import com.aurum.edge.ui.components.formatSpread
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Home: Live Open Trades Tracking, Auto Paper Status, and Market Overview. */
@Composable
fun HomeScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onChart: () -> Unit,
    onSignal: () -> Unit,
    onNews: () -> Unit,
    onLearn: () -> Unit,
    onJournal: () -> Unit,
    onSettings: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            now = System.currentTimeMillis()
        }
    }
    val session = MarketHours.sessionWindowFor(market.symbol, now)
    val price = HomeReadout.from(market, now)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val configuredSpread = settings.spreadPrice.takeIf { it.isFinite() && it > 0.0 && price.value != null }
    val displayBid = market.bid ?: configuredSpread?.let { spread -> price.value?.minus(spread / 2.0) }
    val displayAsk = market.ask ?: configuredSpread?.let { spread -> price.value?.plus(spread / 2.0) }
    val spread = displayBid?.let { bid -> displayAsk?.let { ask -> ask - bid } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 14.dp)) {

        // ── ۱. پوزیشن‌های باز و ترکینگ لحظه‌ای ─────────────────────────────
        LiveOpenTradesCard(
            viewModel = viewModel,
            currentMarket = market,
            onJournal = onJournal,
            onSelectTrade = { symbol ->
                viewModel.selectChartSymbol(symbol)
                onChart()
            },
        )

        // ── ۲. وضعیت بازار و سشن معاملاتی ──────────────────────────────────
        Column(
            Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .background(
                    Brush.horizontalGradient(listOf(AurumColors.SurfaceAlt, AurumColors.Surface)),
                    RoundedCornerShape(18.dp),
                )
                .border(
                    1.dp,
                    (if (session.closed) AurumColors.Red else AurumColors.Green).copy(alpha = 0.35f),
                    RoundedCornerShape(18.dp),
                )
                .padding(18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text("وضعیت بازار · ${market.symbol}", color = AurumColors.Gold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (session.closed) "بازار بسته است" else "بازار باز و فعال است",
                        color = if (session.closed) AurumColors.Red else AurumColors.Green,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Pill(
                    text = if (session.closed) "CLOSED" else "OPEN 24/7",
                    color = if (session.closed) AurumColors.Red else AurumColors.Green,
                )
            }
            Text(
                "${session.nextChangeLabel}: ${formatDateTime(session.nextChangeAt)}",
                color = AurumColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                session.newYorkTimeLabel,
                color = AurumColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                session.detail,
                color = AurumColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ── ۳. پنل معامله خودکار کاغذی ─────────────────────────────────────
        AutoPaperCard(viewModel, onJournal)

        // ── ۴. بهترین فرصت منتخب از ۵۰+ سهم و نماد ────────────────────────
        val scanState by viewModel.pairScan.collectAsStateWithLifecycle()
        scanState.bestPick?.let { best ->
            val assetClass = AssetClass.of(best.symbol)
            SectionCard(
                title = "★ بهترین فرصت شکارشده · ${best.symbol} (${assetClass.label})",
                subtitle = "انتخاب‌شده از میان ۵۰+ سهم و نماد بر اساس بالاترین نسبت R:R و آزادی چیکو اسپن",
                trailing = {
                    Pill(
                        text = if (best.action == SignalAction.BUY) "خرید LONG" else "فروش SHORT",
                        color = if (best.action == SignalAction.BUY) AurumColors.Green else AurumColors.Red,
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Pill(assetClass.label, AurumColors.Gold)
                                Pill("شانس ${(best.confidence ?: 85.0).toInt()}%", AurumColors.Green)
                                best.riskReward?.let { rr ->
                                    Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                                }
                            }
                            if (best.entry != null) {
                                Text(
                                    "ورود: ${formatPrice(best.entry)} · SL: ${formatPrice(best.stopLoss)} · TP: ${formatPrice(best.takeProfit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AurumColors.Gold,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        Text(
                            "مشاهده در چارت",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.Cyan,
                            modifier = Modifier
                                .background(AurumColors.Cyan.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .clickable {
                                    viewModel.selectChartSymbol(best.symbol)
                                    onChart()
                                },
                        )
                    }
                    Text(
                        best.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
            }
        }

        // ── ۵. قیمت لحظه‌ای بازار ──────────────────────────────────────────
        SectionCard(
            title = "قیمت لحظه‌ای بازار · ${market.symbol}",
            subtitle = "فقط آخرین عدد واقعی دریافت‌شده؛ بدون سیگنال، خبر یا آمار اضافه",
            trailing = {
                Pill(
                    when {
                        price.current -> "زنده/تازه"
                        price.value != null -> "قبلی/کش"
                        else -> "بدون داده"
                    },
                    if (price.current) AurumColors.Cyan else AurumColors.Gold,
                )
            },
        ) {
            Text(
                formatPrice(price.value),
                style = MaterialTheme.typography.headlineMedium,
                color = if (price.current) AurumColors.TextPrimary else AurumColors.TextMuted,
            )
            Text(
                if (price.current) "${price.label} · دریافت ${formatDateTime(price.observedAt)}"
                else "${price.label} · آخرین مشاهده ${formatDateTime(price.observedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (price.current) AurumColors.Cyan else AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("BID", formatQuotePrice(displayBid), if (price.current) AurumColors.Green else AurumColors.TextMuted, Modifier.weight(1f))
                StatTile("ASK", formatQuotePrice(displayAsk), if (price.current) AurumColors.Red else AurumColors.TextMuted, Modifier.weight(1f))
                StatTile("SPREAD", formatSpread(spread), AurumColors.Gold, Modifier.weight(1f))
            }
            market.feed.detail.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * کارت ترکینگ لحظه‌ای ۲ پوزیشن باز در صفحه اصلی (Home Screen)
 */
@Composable
private fun LiveOpenTradesCard(
    viewModel: AurumViewModel,
    currentMarket: MarketState,
    onJournal: () -> Unit,
    onSelectTrade: (String) -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val openTrades = remember(trades) { trades.filter { it.isOpen }.take(2) }

    SectionCard(
        title = "⚡ ترکینگ لحظه‌ای معاملات باز",
        subtitle = "رهگیری زندهٔ پوزیشن‌های فعال پورتفو (حداکثر ۴ سهم مجزا در ۴ دسته دارایی)",
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
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "در حال حاضر هیچ معاملهٔ بازی در پورتفو وجود ندارد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    text = "موتور به شکل خودکار ۵۰+ نماد را در دسته‌های رمزارز، فارکس، کالا و سهام اسکن می‌کند تا بهترین فرصت‌های دارای بیشترین R:R را باز کند.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                openTrades.forEach { trade ->
                    LiveTradeTrackerRow(
                        trade = trade,
                        currentMarket = currentMarket,
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
                        "مدیریت و مشاهده کامل همه پوزیشن‌ها در ژورنال",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Cyan,
                        modifier = Modifier.clickable { onJournal() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTradeTrackerRow(
    trade: PaperTrade,
    currentMarket: MarketState,
    onSelect: () -> Unit,
) {
    val isBuy = trade.action == SignalAction.BUY
    val assetClass = AssetClass.of(trade.symbol)
    val livePrice = if (currentMarket.symbol == trade.symbol) currentMarket.lastPrice ?: trade.entry else trade.entry

    val entry = trade.entry
    val stop = trade.stopLoss
    val target = trade.takeProfit

    val pnlPerUnit = if (isBuy) livePrice - entry else entry - livePrice
    val unrealizedPnlUsd = pnlPerUnit * trade.positionOz
    val pnlPercent = if (entry > 0) (pnlPerUnit / entry) * 100.0 else 0.0

    val totalRange = abs(target - stop).coerceAtLeast(0.00001)
    val currentProgress = if (isBuy) {
        ((livePrice - stop) / totalRange).toFloat().coerceIn(0f, 1f)
    } else {
        ((stop - livePrice) / totalRange).toFloat().coerceIn(0f, 1f)
    }

    val statusColor = when {
        unrealizedPnlUsd > 0.0 -> AurumColors.Green
        unrealizedPnlUsd < 0.0 -> AurumColors.Red
        else -> AurumColors.Gold
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.SurfaceAlt.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .border(1.dp, statusColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AurumColors.Gold,
                )
                Pill(assetClass.label, AurumColors.Surface)
                Pill(
                    text = if (isBuy) "خرید LONG" else "فروش SHORT",
                    color = if (isBuy) AurumColors.Green else AurumColors.Red,
                )
            }
            Text(
                text = "${if (unrealizedPnlUsd >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", unrealizedPnlUsd)}$ (${String.format(java.util.Locale.US, "%.2f", pnlPercent)}%)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("قیمت ورود", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text(formatPrice(entry), style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
            }
            Column {
                Text("قیمت لحظه‌ای", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text(formatPrice(livePrice), style = MaterialTheme.typography.bodySmall, color = statusColor, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("حد ضرر (SL)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Red.copy(alpha = 0.8f))
                Text(formatPrice(stop), style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
            }
            Column {
                Text("تارگت سود (TP)", style = MaterialTheme.typography.labelSmall, color = AurumColors.Green.copy(alpha = 0.8f))
                Text(formatPrice(target), style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
            }
        }

        // Progress bar between SL, Entry and TP
        Column(modifier = Modifier.padding(top = 8.dp)) {
            LinearProgressIndicator(
                progress = { currentProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = if (unrealizedPnlUsd >= 0) AurumColors.Green else AurumColors.Red,
                trackColor = AurumColors.Surface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("SL: ${formatPrice(stop)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text("R:R 1:${String.format(java.util.Locale.US, "%.1f", trade.riskReward)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                Text("TP: ${formatPrice(target)}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

/**
 * Automatic paper trading card on Home Screen.
 */
@Composable
private fun AutoPaperCard(viewModel: AurumViewModel, onJournal: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val status by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val enabled = settings.autoPaperTrading
    val lastTrade = trades.maxByOrNull { it.openedAt }

    SectionCard(
        title = "معاملهٔ خودکار کاغذی",
        subtitle = "تخصیص متوازن در ۴ دسته: ۱ رمزارز · ۱ فارکس · ۱ کالا · ۱ سهام",
        trailing = {
            Pill(
                if (enabled) "روشن" else "خاموش",
                if (enabled) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (enabled) "موتور هوشمند بازار را رصد و تا ۴ موقعیت برتر (هر بازار ۱ معامله) را شکار می‌کند."
                else "خاموش است؛ هیچ معاملهٔ خودکاری ثبت نمی‌شود.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
            Switch(checked = enabled, onCheckedChange = viewModel::setAutoPaperTrading)
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val balanceStr = String.format(java.util.Locale.US, "$%.2f", settings.accountBalance).let {
                if (it.endsWith(".00")) it.substringBefore(".00") else it
            }
            StatTile("موجودی حساب", balanceStr, modifier = Modifier.weight(1f))
            StatTile(
                "سود/ضرر",
                (if (stats.netPnl >= 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", stats.netPnl) + "$",
                if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red,
                Modifier.weight(1f),
            )
            StatTile("پوزیشن‌های باز", "${stats.open}/۴", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("کل معاملات", stats.total.toString(), modifier = Modifier.weight(1f))
            StatTile("برد/باخت", "${stats.wins}/${stats.losses}", modifier = Modifier.weight(1f))
            StatTile(
                "وین‌ریت",
                stats.winRate?.let { String.format("%.0f%%", it) } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }

        if (lastTrade != null) {
            val lastClass = AssetClass.of(lastTrade.symbol)
            Text(
                "آخرین معامله: ${lastTrade.symbol} (${lastClass.label}) · ${relativeTime(lastTrade.openedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (enabled) {
            Text(
                "وضعیت موتور: $status",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp),
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
