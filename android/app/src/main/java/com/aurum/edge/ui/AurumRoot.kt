package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.ui.components.FeedBanner
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatSigned
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

enum class AurumTab(val label: String, val icon: ImageVector) {
    Home("خانه", Icons.Filled.Home),
    Chart("چارت", Icons.Filled.ShowChart),
    Signal("معامله", Icons.Filled.Bolt),
    Watch("دیده‌بان", Icons.Filled.ViewList),
    News("خبر", Icons.Filled.Article),
    Crypto("رمزارز", Icons.Filled.TrendingUp),
    Learn("یادگیری", Icons.Filled.School),
    Journal("ژورنال", Icons.Filled.Bookmarks),
    Settings("تنظیمات", Icons.Filled.Settings),
}

/** The five frequent destinations stay readable on small phones; the others remain one tap away. */
internal val primaryTabs = listOf(AurumTab.Home, AurumTab.Chart, AurumTab.Signal, AurumTab.News, AurumTab.Crypto)
internal val moreTabs = listOf(AurumTab.Watch, AurumTab.Learn, AurumTab.Journal, AurumTab.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurumRoot(viewModel: AurumViewModel) {
    var tab by remember { mutableStateOf(AurumTab.Home) }
    var showMore by remember { mutableStateOf(false) }
    val market by viewModel.market.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        val message = toast
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        containerColor = AurumColors.Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = AurumColors.Surface) {
                primaryTabs.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label, modifier = Modifier.size(20.dp)) },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        alwaysShowLabel = true,
                    )
                }
                NavigationBarItem(
                    selected = tab in moreTabs,
                    onClick = { showMore = true },
                    icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "بخش‌های دیگر", modifier = Modifier.size(20.dp)) },
                    label = { Text("بیشتر", style = MaterialTheme.typography.labelSmall) },
                    alwaysShowLabel = true,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (tab == AurumTab.Chart || tab == AurumTab.Signal) {
                AppHeader(
                    symbol = market.symbol,
                    price = market.lastPrice,
                    interval = market.interval,
                    lastUpdate = market.feed.lastSuccessAt,
                    onRefresh = { viewModel.refreshNow() },
                )
                FeedBanner(
                    status = market.feed,
                    lastPrice = market.lastPrice,
                    lastBarTime = market.candles.lastOrNull()?.time,
                    showingCache = market.showingCachedData,
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    AurumTab.Home -> HomeScreen(viewModel, market,
                        onChart = { tab = AurumTab.Chart }, onSignal = { tab = AurumTab.Signal },
                        onNews = { tab = AurumTab.News }, onLearn = { tab = AurumTab.Learn },
                        onJournal = { tab = AurumTab.Journal }, onSettings = { tab = AurumTab.Settings })
                    AurumTab.Chart -> ChartScreen(viewModel, market,
                        onOpenSettings = { tab = AurumTab.Settings }, onOpenJournal = { tab = AurumTab.Journal })
                    AurumTab.Signal -> SignalScreen(viewModel, market, onOpenNews = { tab = AurumTab.News })
                    AurumTab.Watch -> MarketWatchScreen(viewModel, onOpenSettings = { tab = AurumTab.Settings })
                    AurumTab.News -> PersianNewsScreen(viewModel, onOpenSettings = { tab = AurumTab.Settings })
                    AurumTab.Crypto -> CryptoScreen(viewModel, onOpenSettings = { tab = AurumTab.Settings })
                    AurumTab.Learn -> LearnScreen(viewModel)
                    AurumTab.Journal -> JournalScreen(viewModel, market)
                    AurumTab.Settings -> SettingsScreen(viewModel, settings)
                }
            }
        }
    }
    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }, containerColor = AurumColors.Surface) {
            Text("بخش‌های دیگر", style = MaterialTheme.typography.titleMedium,
                color = AurumColors.TextPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            moreTabs.forEach { destination ->
                ListItem(
                    headlineContent = { Text(destination.label) },
                    leadingContent = { Icon(destination.icon, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        tab = destination
                        showMore = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AppHeader(
    symbol: String,
    price: Double?,
    interval: com.aurum.edge.core.Interval,
    lastUpdate: Long?,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(AurumColors.Gold.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("Au", color = AurumColors.Gold, style = MaterialTheme.typography.titleSmall)
        }
        Column(modifier = Modifier.weight(1f)) {
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
                if (price == null) "بدون داده واقعی" else formatSigned(price),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی", tint = AurumColors.Gold)
        }
    }
}

@Composable
fun ModePill(text: String, color: Color) = Pill(text = text, color = color)
