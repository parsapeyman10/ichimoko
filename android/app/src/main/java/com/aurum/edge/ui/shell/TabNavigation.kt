package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.edge.ui.theme.AurumColors

enum class AurumTab(val label: String, val icon: ImageVector) {
    Home("خانه", Icons.Filled.Home),
    Strategy("رادار", Icons.Filled.TrackChanges),
    Chart("چارت", Icons.Filled.ShowChart),
    Journal("ژورنال", Icons.Filled.ReceiptLong),
    Settings("تنظیمات", Icons.Filled.Settings),
    Update("آپدیت", Icons.Filled.Refresh),
}

internal val primaryTabs = listOf(
    AurumTab.Home,
    AurumTab.Strategy,
    AurumTab.Chart,
    AurumTab.Journal,
    AurumTab.Settings,
    AurumTab.Update,
)

@Composable
internal fun AurumBottomBar(
    selected: AurumTab,
    openTradeCount: Int = 0,
    onSelect: (AurumTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.Surface)
            .border(0.5.dp, AurumColors.Line)
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        primaryTabs.forEach { entry ->
            val isSelected = selected == entry
            val showBadge = openTradeCount > 0 && (entry == AurumTab.Chart || entry == AurumTab.Home)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) AurumColors.Gold.copy(alpha = 0.16f) else Color.Transparent,
                    )
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) AurumColors.Gold.copy(alpha = 0.55f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(entry) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = entry.label,
                        tint = if (isSelected) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.size(19.dp),
                    )
                    if (showBadge) {
                        Box(
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(AurumColors.Green),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = openTradeCount.coerceAtMost(99).toString(),
                                color = Color.Black,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (isSelected) AurumColors.Gold else AurumColors.TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
