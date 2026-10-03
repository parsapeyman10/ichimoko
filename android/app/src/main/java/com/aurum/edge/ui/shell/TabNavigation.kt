package com.aurum.edge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aurum.edge.ui.theme.AurumColors

enum class AurumTab(val label: String, val icon: ImageVector) {
    Home("خانه", Icons.Filled.Home),
    Chart("چارت", Icons.Filled.ShowChart),
    Signal("معامله", Icons.Filled.Bolt),
    Watch("دیده‌بان", Icons.Filled.ViewList),
    News("خبر", Icons.Filled.Article),
    Engine("موتور سرور", Icons.Filled.Dns),
    Learn("یادگیری", Icons.Filled.School),
    Journal("ژورنال", Icons.Filled.Bookmarks),
    Update("بروزرسانی", Icons.Filled.Refresh),
    Settings("تنظیمات", Icons.Filled.Settings),
    Api("APIها", Icons.Filled.Settings),
}

internal val primaryTabs = listOf(
    AurumTab.Home, AurumTab.Chart, AurumTab.Signal, AurumTab.Watch, AurumTab.News,
)

internal val moreTabs = listOf(
    AurumTab.Engine, AurumTab.Learn, AurumTab.Journal, AurumTab.Update, AurumTab.Settings, AurumTab.Api,
)

@Composable
internal fun AurumBottomBar(selected: AurumTab, onSelect: (AurumTab) -> Unit, onMore: () -> Unit) {
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
        NavigationBarItem(
            selected = selected in moreTabs,
            onClick = onMore,
            icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "بخش‌های دیگر", modifier = Modifier.size(20.dp)) },
            label = { Text("بیشتر", style = MaterialTheme.typography.labelSmall) },
            alwaysShowLabel = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreTabsSheet(onDismiss: () -> Unit, onOpen: (AurumTab) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AurumColors.Surface) {
        Text(
            "بخش‌های دیگر",
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        moreTabs.forEach { destination ->
            ListItem(
                headlineContent = { Text(destination.label) },
                leadingContent = { Icon(destination.icon, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable { onOpen(destination) },
            )
        }
    }
}
