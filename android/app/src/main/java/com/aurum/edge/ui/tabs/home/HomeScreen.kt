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
import com.aurum.edge.core.HomeReadout
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatQuotePrice
import com.aurum.edge.ui.components.formatSpread
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * صفحه اصلی (Home Screen):
 * ۱. در بالای صفحه وضعیت باز یا بسته بودن بازار
 * ۲. ترکینگ زنده و لحظه‌ای تمامی معاملات باز پورتفو با نمایش تراز SL -> قیمت ورود -> TP
 * ۳. پنل معاملهٔ خودکار و آمار کلی پورتفو
 * ۴. آخرین قیمت و اسپرد بازار
 */
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
                Column {
                    Text("وضعیت فعالیت بازار", color = AurumColors.Gold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (session.closed) "بازار اکنون بسته است" else "بازار اکنون باز و فعال است",
                        color = if (session.closed) AurumColors.Red else AurumColors.Green,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
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
        }

        // ── ۲. ترکینگ زنده و کامل تمامی معاملات باز پورتفو ──────────────────
        LiveOpenTradesTrackingSection(
            viewModel = viewModel,
            currentMarket = market,
            onJournal = onJournal,
            onSelectTrade = { symbol ->
                viewModel.selectChartSymbol(symbol)
                onChart()
            },
        )

        // ── ۳. پنل معاملهٔ خودکار کاغذی و تخصیص ۴ بازار ────────────────────
        AutoPaperCard(viewModel, onJournal)

        // ── ۴. قیمت لحظه‌ای و اسپرد بازار ──────────────────────────────────
        SectionCard(
            title = "قیمت لحظه‌ای بازار · ${market.symbol}",
            subtitle = "فقط آخرین عدد واقعی دریافت‌شده؛ بدون داده‌های شبیه‌سازی‌شده",
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
 * بخش ترکینگ زندهٔ همه معاملات باز (حداکثر ۴ پوزیشن در ۴ بازار)
 */
@Composable
private fun LiveOpenTradesTrackingSection(
    viewModel: AurumViewModel,
    currentMarket: MarketState,
    onJournal: () -> Unit,
    onSelectTrade: (String) -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val openTrades = remember(trades) { trades.filter { it.isOpen } }

    SectionCard(
        title = "⚡ ترکینگ لحظه‌ای معاملات باز",
        subtitle = "رهگیری زنده حرکت قیمت میان حد ضرر (SL)، نقطه ورود و حد سود (TP)",
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

    // Calculate progression on scale from SL (0%) to TP (100%)
    val totalRange = abs(target - stop).coerceAtLeast(0.000001)
    val progress = if (isBuy) {
        ((livePrice - stop) / totalRange).toFloat().coerceIn(0f, 1f)
    } else {
        ((stop - livePrice) / totalRange).toFloat().coerceIn(0f, 1f)
    }

    val entryFraction = if (isBuy) {
        ((entry - stop) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
    } else {
        ((stop - entry) / totalRange).toFloat().coerceIn(0.05f, 0.95f)
    }

    val isProfitable = unrealizedPnlUsd >= 0.0
    val statusColor = when {
        unrealizedPnlUsd > 0.0 -> AurumColors.Green
        unrealizedPnlUsd < 0.0 -> AurumColors.Red
        else -> AurumColors.Gold
    }

    val movementLabel = when {
        unrealizedPnlUsd > 0.0 -> "↗ در مسیر حد سود (TP)"
        unrealizedPnlUsd < 0.0 -> "↘ در مسیر حد ضرر (SL)"
        else -> "⚪ در نقطه ورود (Entry)"
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

        // Live status & movement
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = movementLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "قیمت لحظه‌ای: ${formatPrice(livePrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
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

                // Live Active Moving Pointer
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0.02f, 0.98f))
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

            // Bottom Info: R:R ratio & quick chart navigate
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ریسک به ریوارد: 1:${String.format(java.util.Locale.US, "%.1f", trade.riskReward)} · حجم: ${String.format("%.4f", trade.positionOz)} ${trade.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
                Text(
                    "نمایش روی چارت ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * پنل معاملهٔ خودکار کاغذی در صفحه اصلی
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
