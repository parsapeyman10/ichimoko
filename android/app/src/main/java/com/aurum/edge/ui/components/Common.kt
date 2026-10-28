package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.ui.theme.AurumColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun formatPrice(value: Double?): String {
    if (value == null) return "—"
    val absVal = abs(value)
    val digits = when {
        absVal >= 1000.0 -> 2
        absVal >= 100.0 -> 2
        absVal >= 10.0 -> 3
        absVal >= 1.0 -> 4
        absVal >= 0.001 -> 5
        absVal > 0.0 -> 6
        else -> 2
    }
    return String.format(Locale.US, "%,.${digits}f", value)
}

/**
 * Price with the exact decimals the instrument is actually quoted in.
 * Forex: 5 decimals, JPY: 3 decimals, Gold/Oil: 2 decimals, Silver: 3 decimals, Crypto: 2-6 decimals.
 */
fun formatPriceFor(symbol: String, value: Double?): String =
    if (value == null) "—"
    else String.format(Locale.US, "%,.${com.aurum.edge.data.CryptoCatalog.digitsFor(symbol)}f", value)

fun formatTime(millis: Long?): String {
    if (millis == null || millis <= 0) return "—"
    return SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
}

fun formatDateTime(millis: Long?): String {
    if (millis == null || millis <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}

fun relativeTime(millis: Long?, now: Long = System.currentTimeMillis()): String {
    if (millis == null || millis <= 0) return "—"
    val diff = (now - millis) / 1000
    return when {
        diff < 60 -> "${diff} ثانیه پیش"
        diff < 3600 -> "${diff / 60} دقیقه پیش"
        diff < 86_400 -> "${diff / 3600} ساعت پیش"
        else -> "${diff / 86_400} روز پیش"
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AurumColors.Surface)
            .border(1.dp, AurumColors.Line, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AurumColors.Gold),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            trailing?.invoke()
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            content()
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    valueColor: Color = AurumColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AurumColors.SurfaceAlt)
            .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.42f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * ابزار بصری پوزیشن به سبک تریدینگ‌ویو (TradingView Long / Short Position Tool)
 * برای نمایش دقیق منطقه حد سود (سبز)، خط ورود، منطقه حد ضرر (قرمز)، قیمت لحظه‌ای و R:R
 */
@Composable
fun TradingViewPositionBox(
    trade: PaperTrade,
    currentPrice: Double?,
    onOpenChart: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isBuy = trade.action == SignalAction.BUY
    val slDist = abs(trade.entry - trade.stopLoss).coerceAtLeast(1e-8)
    val tpDist = abs(trade.takeProfit - trade.entry).coerceAtLeast(1e-8)
    val rr = (tpDist / slDist).coerceIn(0.1, 50.0)
    val riskUsd = if (trade.fixedRiskUsd > 0.0) trade.fixedRiskUsd else 5.0
    val rewardUsd = riskUsd * rr
    val tpPct = (tpDist / trade.entry.coerceAtLeast(1e-8)) * 100.0
    val slPct = (slDist / trade.entry.coerceAtLeast(1e-8)) * 100.0

    val valuation = LiveTradeValuation.from(trade, currentPrice)
    val livePnlUsd = valuation?.netPnlUsd
    val liveR = if (livePnlUsd != null && riskUsd > 0.0) livePnlUsd / riskUsd else null

    val tvGreen = Color(0xFF089981)
    val tvRed = Color(0xFFF23645)
    val tvEntry = AurumColors.Gold

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF090D14))
            .border(1.dp, AurumColors.Line, RoundedCornerShape(12.dp))
            .let { base -> if (onOpenChart != null) base.clickable { onOpenChart() } else base }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ۱. هدر ابزار پوزیشن تریدینگ‌ویو (نوع پوزیشن، نماد، تایم‌فریم، استراتژی و R:R)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    text = if (isBuy) "LONG (خرید) ↗" else "SHORT (فروش) ↘",
                    color = if (isBuy) tvGreen else tvRed,
                )
                Pill(
                    text = "${trade.symbol} · ${trade.interval.label}",
                    color = AurumColors.Gold,
                )
                Pill(
                    text = "R:R 1:${String.format(Locale.US, "%.2f", rr)}",
                    color = AurumColors.Cyan,
                )
            }
            if (livePnlUsd != null) {
                val pnlColor = if (livePnlUsd >= 0.0) tvGreen else tvRed
                val sign = if (livePnlUsd >= 0.0) "+" else "-"
                Pill(
                    text = "${sign}$${String.format(Locale.US, "%.2f", abs(livePnlUsd))} (${String.format(Locale.US, "%s%.2fR", if ((liveR ?: 0.0) >= 0.0) "+" else "", liveR ?: 0.0)})",
                    color = pnlColor,
                )
            }
        }

        // ردیف نام استراتژی و دکمه چارت
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "🎯 استراتژی: ${trade.strategyTitleFa}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onOpenChart != null) {
                Text(
                    text = "📈 مشاهده روی چارت تریدینگ‌ویو ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ۲. مستطیل دوکی تریدینگ‌ویو (منطقه سبز TP و منطقه قرمز SL بر اساس جهت BUY/SELL)
        @Composable
        fun ProfitZoneBlock() {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(tvGreen.copy(alpha = 0.18f))
                    .border(1.dp, tvGreen.copy(alpha = 0.65f), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "🎯 حد سود (Take Profit)",
                        style = MaterialTheme.typography.labelSmall,
                        color = tvGreen,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatPriceFor(trade.symbol, trade.takeProfit),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "+$${String.format(Locale.US, "%.2f", rewardUsd)} (+${String.format(Locale.US, "%.2f", rr)}R)",
                        style = MaterialTheme.typography.labelMedium,
                        color = tvGreen,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "+${String.format(Locale.US, "%.2f%%", tpPct)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tvGreen.copy(alpha = 0.9f),
                    )
                }
            }
        }

        @Composable
        fun EntryLineBlock() {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tvEntry.copy(alpha = 0.16f))
                    .border(1.dp, tvEntry.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "⚡ نقطه ورود (Entry): ${formatPriceFor(trade.symbol, trade.entry)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tvEntry,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (currentPrice != null) {
                        "📍 قیمت فعلی: ${formatPriceFor(trade.symbol, currentPrice)}"
                    } else {
                        "در انتظار تیک زنده..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        @Composable
        fun StopZoneBlock() {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(tvRed.copy(alpha = 0.18f))
                    .border(1.dp, tvRed.copy(alpha = 0.65f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "🛑 حد ضرر (Stop Loss)",
                        style = MaterialTheme.typography.labelSmall,
                        color = tvRed,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatPriceFor(trade.symbol, trade.stopLoss),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "-$${String.format(Locale.US, "%.2f", riskUsd)} (-1.00R)",
                        style = MaterialTheme.typography.labelMedium,
                        color = tvRed,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "-${String.format(Locale.US, "%.2f%%", slPct)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tvRed.copy(alpha = 0.9f),
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (isBuy) {
                ProfitZoneBlock()
                EntryLineBlock()
                StopZoneBlock()
            } else {
                // در معامله فروش (SHORT) در تریدینگ‌ویو استاپ بالا و تارگت پایین قرار دارد
                StopZoneBlock()
                EntryLineBlock()
                ProfitZoneBlock()
            }
        }

        // ۳. نوار پیشرفت موقعیت قیمت بین SL و TP
        if (valuation != null) {
            val progress = valuation.progress.coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("🛑 SL", style = MaterialTheme.typography.labelSmall, color = tvRed, fontWeight = FontWeight.Bold)
                    Text(
                        text = "پیشرفت به سمت تارگت: ${(progress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (valuation.netPnlUsd >= 0.0) tvGreen else tvRed,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("🎯 TP", style = MaterialTheme.typography.labelSmall, color = tvGreen, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (valuation.netPnlUsd >= 0.0) tvGreen else tvRed,
                    trackColor = AurumColors.SurfaceAlt,
                )
            }
        }
    }
}

@Composable
fun ConfluenceRow(item: ConfluenceItem) {
    val tone = when (item.status) {
        ConfluenceStatus.CONFIRMED -> AurumColors.Green
        ConfluenceStatus.UNKNOWN, ConfluenceStatus.PARTIAL -> AurumColors.Orange
        ConfluenceStatus.CONFLICT -> AurumColors.Red
    }
    val label = when (item.status) {
        ConfluenceStatus.CONFIRMED -> "برقرار"
        ConfluenceStatus.PARTIAL -> "امتیاز جزئی"
        ConfluenceStatus.UNKNOWN -> "نامعلوم"
        ConfluenceStatus.CONFLICT -> "دور"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tone, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.name, style = MaterialTheme.typography.bodySmall, color = tone)
                item.scorePercent?.let { score ->
                    Text("امتیاز: $score/۲۵", style = MaterialTheme.typography.labelSmall, color = if (item.ok) tone else AurumColors.TextMuted)
                }
            }
            if (item.detail.isNotBlank()) {
                Text(item.detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

/**
 * بنر وضعیت ۳ دسته دارایی (طلا و کالا، جفت‌ارزها، رمزارزها) در قالب Bento متوازن ۳ ستونی
 */
@Composable
fun AssetClass4SlotsBanner(
    openTrades: List<PaperTrade>,
    livePrices: Map<String, Double> = emptyMap(),
    onSelectSymbol: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    lastKnownPrices: Map<String, Double> = emptyMap(),
    entryReasons: Map<com.aurum.edge.core.AssetClass, String> = emptyMap(),
) {
    val categories = listOf(
        com.aurum.edge.core.AssetClass.COMMODITY to "طلا و شاخص",
        com.aurum.edge.core.AssetClass.FOREX to "فارکس",
        com.aurum.edge.core.AssetClass.CRYPTO to "رمزارزها",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "وضعیت معاملات باز به تفکیک بازار",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )
            val openCount = openTrades.size
            Pill("$openCount پوزیشن فعال", if (openCount > 0) AurumColors.Green else AurumColors.TextMuted)
        }

        // ۳ کارت متوازن کنار هم (Bento 3-Column Grid)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categories.forEach { (assetClass, title) ->
                val categoryTrades = openTrades.filter { it.assetClass == assetClass }
                val firstTrade = categoryTrades.firstOrNull()
                val isOpen = categoryTrades.isNotEmpty()
                val borderColor = if (isOpen) AurumColors.Green.copy(alpha = 0.7f) else AurumColors.Line
                val bgColor = if (isOpen) AurumColors.Green.copy(alpha = 0.10f) else AurumColors.Surface

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                        .clickable { firstTrade?.let { onSelectSymbol(it.symbol) } }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isOpen) AurumColors.Green else AurumColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${categoryTrades.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isOpen) AurumColors.Green else AurumColors.TextMuted,
                        )
                    }

                    if (isOpen && firstTrade != null) {
                        val totalCatPnl = categoryTrades.sumOf { t ->
                            LiveTradeValuation.from(
                                t,
                                livePrices[t.symbol] ?: lastKnownPrices[t.symbol],
                            )?.netPnlUsd ?: 0.0
                        }
                        Text(
                            text = "${firstTrade.symbol} (${firstTrade.interval.label})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AurumColors.Gold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = (if (totalCatPnl >= 0.0) "+$" else "-$") +
                                String.format(Locale.US, "%.2f", abs(totalCatPnl)),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (totalCatPnl >= 0.0) AurumColors.Green else AurumColors.Red,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            text = "بدون معامله",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted,
                            maxLines = 1,
                        )
                        Text(
                            text = entryReasons[assetClass] ?: "در حال رصد",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
