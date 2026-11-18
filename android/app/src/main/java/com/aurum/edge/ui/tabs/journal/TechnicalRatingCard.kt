package com.aurum.edge.ui.tabs.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.RatingBucketKey
import com.aurum.edge.core.SymbolTechnicalRating
import com.aurum.edge.core.TechnicalRatingInsight
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

/**
 * نوار سنجش تکنیکال: رأی خرید/خنثی/فروش برای ۱ ساعته و ۴ ساعتهٔ همهٔ نمادها + جدول
 * «نرخ برد در هر وضعیت».
 *
 * این کارت فقط عدد نشان می‌دهد و هیچ تصمیمی نمی‌گیرد؛ نه اندازهٔ پوزیشن، نه حد ضرر و نه
 * ورود/خروج به آن وابسته نیست.
 */
@Composable
internal fun TechnicalRatingCard(
    symbols: List<String>,
    ratings: Map<String, SymbolTechnicalRating>,
    trades: List<PaperTrade>,
) {
    val insight = remember(trades) { TechnicalRatingInsight.build(trades) }
    SectionCard(
        title = "🧭 سنجش تکنیکال (۱H · ۴H)",
        trailing = {
            Pill(
                text = if (insight.hasEvidence) "دادهٔ ${insight.fourHour.tradesWithData} معامله" else "در انتظار معامله",
                color = if (insight.hasEvidence) AurumColors.Green else AurumColors.Cyan,
            )
        },
        initiallyExpanded = false,
    ) {
        // ── جدول زندهٔ نمادها ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("نماد", color = AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("۱ ساعته", color = AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.4f))
            Text("۴ ساعته", color = AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.4f))
        }
        symbols.forEach { symbol ->
            val row = ratings[symbol]
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(symbol, color = AurumColors.TextPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                RatingCell(row?.oneHour, Modifier.weight(1.4f))
                RatingCell(row?.fourHour, Modifier.weight(1.4f))
            }
        }

        // ── جدول نرخ برد در هر وضعیت ────────────────────────────────────────────
        listOf(insight.oneHour, insight.fourHour).forEach { dimension ->
            Text(
                text = "نرخ برد در وضعیت " + dimension.timeframeFa + " (" + dimension.tradesWithData + " از " + dimension.tradesTotal + " معامله دارای داده)",
                color = AurumColors.Gold,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            if (dimension.buckets.isEmpty()) {
                Text(
                    text = "هنوز معاملهٔ بسته‌ای با این داده ثبت نشده",
                    color = AurumColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                dimension.buckets.forEach { bucket -> BucketRow(bucket) }
            }
        }
    }
}

@Composable
private fun RatingCell(rating: com.aurum.edge.core.TechnicalRating?, modifier: Modifier = Modifier) {
    if (rating == null || !rating.available) {
        Text("—", color = AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = modifier)
        return
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${rating.buy}", color = AurumColors.Green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("${rating.neutral}", color = AurumColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        Text("${rating.sell}", color = AurumColors.Red, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BucketRow(bucket: com.aurum.edge.core.RatingBucketStat) {
    val color = bucketColor(bucket.key)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AurumColors.SurfaceAlt)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = bucket.key.titleFa,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "معامله",
            value = bucket.trades.toString(),
            valueColor = AurumColors.TextPrimary,
            modifier = Modifier.weight(0.8f),
        )
        StatTile(
            label = "نرخ برد",
            value = String.format(Locale.US, "%.0f%%", bucket.winRatePct),
            valueColor = if (bucket.winRatePct >= 50.0) AurumColors.Green else AurumColors.Red,
            modifier = Modifier.weight(0.9f),
        )
        StatTile(
            label = "میانگین R",
            value = String.format(Locale.US, "%+.2f", bucket.avgR),
            valueColor = if (bucket.avgR >= 0.0) AurumColors.Green else AurumColors.Red,
            modifier = Modifier.weight(0.9f),
        )
    }
}

private fun bucketColor(key: RatingBucketKey) = when (key) {
    RatingBucketKey.STRONG_BUY -> AurumColors.Green
    RatingBucketKey.BUY -> AurumColors.Cyan
    RatingBucketKey.NEUTRAL -> AurumColors.TextSecondary
    RatingBucketKey.SELL -> AurumColors.Orange
    RatingBucketKey.STRONG_SELL -> AurumColors.Red
}
