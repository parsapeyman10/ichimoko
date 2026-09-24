package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.aurum.edge.ui.theme.AurumColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatPrice(value: Double?): String =
    if (value == null) "—" else String.format(Locale.US, "%,.2f", value)

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
        FeedMode.POLLING -> AurumColors.Gold to "کندل REST دوره‌ای (نه تیک زنده) — ${status.provider}"
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
            if (status.lastSuccessAt != null && status.mode != FeedMode.OFFLINE) {
                if (isNotEmpty()) append(" · ")
                append("آخرین دریافت ${relativeTime(status.lastSuccessAt)}")
            }
            lastPrice?.let {
                if (isNotEmpty()) append(" · ")
                append("قیمت واقعی ${formatPrice(it)}")
            }
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
fun ConfluenceRow(item: ConfluenceItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (item.status) {
                ConfluenceStatus.CONFIRMED -> "✓"
                ConfluenceStatus.CONFLICT -> "✕"
                ConfluenceStatus.UNKNOWN -> "؟"
            },
            color = when (item.status) {
                ConfluenceStatus.CONFIRMED -> AurumColors.Green
                ConfluenceStatus.CONFLICT -> AurumColors.Red
                ConfluenceStatus.UNKNOWN -> AurumColors.Gold
            },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
            Text(item.detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
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
