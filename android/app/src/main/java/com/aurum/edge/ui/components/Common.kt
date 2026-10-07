package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.data.NewsClassification
import com.aurum.edge.data.NewsDirection
import com.aurum.edge.data.NewsImportance
import com.aurum.edge.ui.theme.AurumColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rule-based (NOT AI) importance/direction badges for a real, already-fetched headline.
 * Purely orientational for a human reader — never a trading signal or the server's AI news gate. */
@Composable
fun NewsClassificationRow(classification: NewsClassification, modifier: Modifier = Modifier) {
    val importanceColor = when (classification.importance) {
        NewsImportance.HIGH -> AurumColors.Red
        NewsImportance.MEDIUM -> AurumColors.Gold
        NewsImportance.LOW -> AurumColors.TextMuted
    }
    val importanceLabel = when (classification.importance) {
        NewsImportance.HIGH -> "اهمیت بالا"
        NewsImportance.MEDIUM -> "اهمیت متوسط"
        NewsImportance.LOW -> "اهمیت کم"
    }
    val directionColor = when (classification.direction) {
        NewsDirection.BULLISH -> AurumColors.Green
        NewsDirection.BEARISH -> AurumColors.Red
        NewsDirection.NEUTRAL -> AurumColors.TextMuted
    }
    val directionLabel = when (classification.direction) {
        NewsDirection.BULLISH -> "جهت صعودی (تخمینی)"
        NewsDirection.BEARISH -> "جهت نزولی (تخمینی)"
        NewsDirection.NEUTRAL -> "جهت خنثی/نامشخص"
    }
    Row(modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(importanceLabel, importanceColor)
        Pill(directionLabel, directionColor)
    }
}

fun formatPrice(value: Double?): String {
    if (value == null) return "—"
    val abs = kotlin.math.abs(value)
    val digits = when {
        abs >= 1000.0 -> 2
        abs >= 100.0 -> 2
        abs >= 10.0 -> 3
        abs >= 1.0 -> 4
        abs >= 0.001 -> 5
        abs > 0.0 -> 6
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

fun formatQuotePrice(value: Double?): String {
    if (value == null) return "—"
    val abs = kotlin.math.abs(value)
    val digits = when {
        abs >= 1000.0 -> 2
        abs >= 100.0 -> 3
        abs >= 10.0 -> 4
        else -> 5
    }
    return String.format(Locale.US, "%,.${digits}f", value)
}

fun formatSpread(value: Double?): String {
    if (value == null) return "—"
    val abs = kotlin.math.abs(value)
    val digits = when {
        abs >= 1.0 -> 2
        abs >= 0.01 -> 4
        else -> 5
    }
    return String.format(Locale.US, "%,.${digits}f", value)
}

fun formatSigned(value: Double?, digits: Int = 2): String {
    if (value == null) return "—"
    val sign = if (value >= 0) "+" else "−"
    return sign + String.format(Locale.US, "%,.${digits}f", kotlin.math.abs(value))
}

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
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(AurumColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, AurumColors.Line, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = AurumColors.TextPrimary)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
                }
            }
            trailing?.invoke()
        }
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 2.dp)) {
            content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, valueColor: Color = AurumColors.TextPrimary, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))
            .border(1.dp, AurumColors.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun FeedBanner(status: FeedStatus, lastPrice: Double?, lastBarTime: Long?, showingCache: Boolean) {
    val (color, title) = when (status.mode) {
        FeedMode.LIVE -> AurumColors.Green to "زنده — ${status.provider}"
        FeedMode.POLLING -> AurumColors.Gold to "کندل/تاریخچه آنلاین دوره‌ای (نه تیک زنده) — ${status.provider}"
        FeedMode.MARKET_CLOSED -> AurumColors.Gold to "بازار طبق برنامهٔ معمول بسته است — دریافت متوقف"
        FeedMode.DELAYED -> AurumColors.Gold to "دادهٔ بازار دیررس/نامعلوم — ${status.provider}"
        FeedMode.CONNECTING -> AurumColors.Cyan to "در حال اتصال…"
        FeedMode.OFFLINE -> AurumColors.Red to "آفلاین — داده ساختگی نمایش داده نمی‌شود"
        FeedMode.NO_KEY -> AurumColors.Red to "کلید API لازم است"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.background(color, RoundedCornerShape(50)).padding(4.dp))
            Text(title, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.SemiBold)
        }
        val detail = buildString {
            if (status.detail.isNotBlank()) append(status.detail)
            if (showingCache && lastBarTime != null) {
                if (isNotEmpty()) append(" · ")
                append("آخرین کندل واقعی: ${formatDateTime(lastBarTime)}")
            }
            if (status.lastSuccessAt != null) {
                if (isNotEmpty()) append(" · ")
                append("آخرین دریافت ${relativeTime(status.lastSuccessAt)}")
            }
            lastPrice?.let {
                if (isNotEmpty()) append(" · ")
                append("${if (status.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) && !showingCache) "قیمت دریافت‌شده" else "قیمت قبلی (نه آنلاین)"} ${formatPrice(it)}")
            }
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
fun ConfluenceRow(item: ConfluenceItem) {
    val tone = when (item.status) {
        ConfluenceStatus.CONFIRMED -> AurumColors.Green
        ConfluenceStatus.UNKNOWN -> AurumColors.Orange
        ConfluenceStatus.CONFLICT -> AurumColors.Red
    }
    val label = when (item.status) {
        ConfluenceStatus.CONFIRMED -> "برقرار"
        ConfluenceStatus.UNKNOWN -> "احتمالی"
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
                    Text("وزن: $score٪", style = MaterialTheme.typography.labelSmall, color = if (item.ok) tone else AurumColors.TextMuted)
                }
            }
            if (item.detail.isNotBlank()) {
                Text(item.detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 18.dp)
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
            .border(1.dp, AurumColors.Line, RoundedCornerShape(12.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AurumColors.TextPrimary)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * بنر وضعیت ۴ دسته دارایی (طلا/کالا، فارکس، رمزارز، سهام)
 * نمایش در ۴ مستطیل بالا؛ در صورت وجود معامله باز سبز و در غیر این صورت قرمز.
 */
@Composable
fun AssetClass4SlotsBanner(
    openTrades: List<com.aurum.edge.core.PaperTrade>,
    livePrices: Map<String, Double> = emptyMap(),
    onSelectSymbol: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val categories = listOf(
        com.aurum.edge.core.AssetClass.COMMODITY to "طلا و کالا",
        com.aurum.edge.core.AssetClass.FOREX to "جفت‌ارزها",
        com.aurum.edge.core.AssetClass.CRYPTO to "رمزارزها",
        com.aurum.edge.core.AssetClass.STOCK to "سهام جهانی",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "تخصیص متوازن ۴ بازار (حداکثر ۱ پوزیشن در هر دسته)",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )
            val openCount = openTrades.size
            Pill("$openCount از ۴ فعال", if (openCount > 0) AurumColors.Green else AurumColors.TextMuted)
        }

        // ۴ مستطیل به صورت گرید دو در دو
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val pairs = categories.chunked(2)
            pairs.forEach { rowCategories ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowCategories.forEach { (assetClass, title) ->
                        val trade = openTrades.firstOrNull { it.assetClass == assetClass }
                        val isOpen = trade != null
                        val defaultLev = com.aurum.edge.core.PaperOrderRules.defaultLeverageFor(
                            when (assetClass) {
                                com.aurum.edge.core.AssetClass.COMMODITY -> "XAU/USD"
                                com.aurum.edge.core.AssetClass.FOREX -> "EUR/USD"
                                com.aurum.edge.core.AssetClass.CRYPTO -> "BTCUSDT"
                                com.aurum.edge.core.AssetClass.STOCK -> "AAPL"
                            }
                        )

                        val borderColor = if (isOpen) AurumColors.Green else AurumColors.Red.copy(alpha = 0.5f)
                        val bgColor = if (isOpen) AurumColors.Green.copy(alpha = 0.12f) else AurumColors.Red.copy(alpha = 0.06f)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(bgColor, RoundedCornerShape(10.dp))
                                .border(1.2.dp, borderColor, RoundedCornerShape(10.dp))
                                .clickable { trade?.let { onSelectSymbol(it.symbol) } }
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
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
                                )
                                Pill(
                                    text = if (isOpen) "● فعال" else "○ غیرفعال",
                                    color = if (isOpen) AurumColors.Green else AurumColors.Red,
                                )
                            }

                            if (isOpen && trade != null) {
                                val currentPrice = livePrices[trade.symbol] ?: trade.entry
                                val pnlPerUnit = if (trade.action == com.aurum.edge.core.SignalAction.BUY) currentPrice - trade.entry else trade.entry - currentPrice
                                val grossPnl = trade.positionOz * pnlPerUnit
                                val netPnl = grossPnl - (trade.effectiveCommissionUsd + trade.effectiveSpreadCostUsd)

                                Text(
                                    text = "${trade.symbol} · ${if (trade.action == com.aurum.edge.core.SignalAction.BUY) "BUY ↗" else "SELL ↘"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AurumColors.Gold,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("اهرم ${trade.effectiveLeverage}x", style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                                    Text(
                                        (if (netPnl >= 0) "+$" else "-$") + String.format(Locale.US, "%.2f", kotlin.math.abs(netPnl)),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (netPnl >= 0) AurumColors.Green else AurumColors.Red,
                                    )
                                }
                            } else {
                                Text(
                                    text = "آماده ورود خودکار",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.TextMuted,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("اهرم: ${defaultLev}x", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                                    Text("پایش ۵۰+ نماد", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
