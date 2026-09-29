package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.Interval
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun AppHeader(
    symbol: String,
    price: Double?,
    interval: Interval,
    lastUpdate: Long?,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(AurumColors.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(AurumColors.Gold.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol.take(3), color = AurumColors.Gold, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(symbol, style = MaterialTheme.typography.titleMedium, color = AurumColors.TextPrimary)
            Text(
                "${interval.label} · آخرین دریافت ${relativeTime(lastUpdate)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatPrice(price),
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (price == null) "بدون دادهٔ تازه" else "آخرین مشاهده · وضعیت فید پایین صفحه",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی", tint = AurumColors.Gold)
        }
    }
}
