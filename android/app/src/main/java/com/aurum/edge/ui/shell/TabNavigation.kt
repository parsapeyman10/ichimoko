package com.aurum.edge.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aurum.edge.ui.theme.AurumColors

enum class AurumTab(val label: String, val icon: ImageVector) {
    Home("پورتفو", Icons.Filled.Home),
    Strategy("رادار ۱۵ نماد", Icons.Filled.TrackChanges),
    Chart("چارت", Icons.Filled.ShowChart),
    Journal("ژورنال و ریسک", Icons.Filled.ReceiptLong),
    Update("بروزرسانی", Icons.Filled.Refresh),
}

internal val primaryTabs = listOf(
    AurumTab.Home,
    AurumTab.Strategy,
    AurumTab.Chart,
    AurumTab.Journal,
    AurumTab.Update,
)

@Composable
internal fun AurumBottomBar(selected: AurumTab, onSelect: (AurumTab) -> Unit) {
    NavigationBar(containerColor = AurumColors.Surface) {
        primaryTabs.forEach { entry ->
            NavigationBarItem(
                selected = selected == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = entry.label, modifier = Modifier.size(20.dp)) },
                label = { Text(entry.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                alwaysShowLabel = true,
            )
        }
    }
}
