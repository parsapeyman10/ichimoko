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
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.ui.components.FeedBanner
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

enum class AurumTab(val label: String, val icon: ImageVector) {
    Home("خانه", Icons.Filled.Home),
    Chart("چارت", Icons.Filled.ShowChart),
    Signal("معامله", Icons.Filled.Bolt),
    Watch("دیده‌بان", Icons.Filled.ViewList),
    News("خبر", Icons.Filled.Article),
    Learn("یادگیری", Icons.Filled.School),
    Journal("ژورنال", Icons.Filled.Bookmarks),
    Settings("تنظیمات", Icons.Filled.Settings),
    Api("APIها", Icons.Filled.Settings),
}

internal val primaryTabs = listOf(
    AurumTab.Home, AurumTab.Chart, AurumTab.Signal, AurumTab.Watch, AurumTab.News,
)

internal val moreTabs = listOf(
    AurumTab.Learn, AurumTab.Journal, AurumTab.Settings, AurumTab.Api,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurumRoot(viewModel: AurumViewModel) {
    var tab by rememberSaveable { mutableStateOf(AurumTab.Home.name) }
    var showMore by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val market by viewModel.market.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // After process recreation Compose may restore a visible screen, but the new app
    // container has no running feed. Start it as soon as the UI is visible again.
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            viewModel.startApp(context)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(toast!!)
            viewModel.consumeToast()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pauseInvisibleForexFeed() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (started) viewModel.resumeVisibleForexFeed()
    }
    val selectedTab = AurumTab.entries.firstOrNull { it.name == tab && it in primaryTabs + moreTabs } ?: primaryTabs.first()
    fun open(destination: AurumTab) { if (destination in primaryTabs + moreTabs) tab = destination.name }

    Scaffold(containerColor = AurumColors.Bg, snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = AurumColors.Surface) {
                primaryTabs.forEach { entry ->
                    NavigationBarItem(selected = selectedTab == entry, onClick = { open(entry) },
                        icon = { Icon(entry.icon, contentDescription = entry.label, modifier = Modifier.size(20.dp)) },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        alwaysShowLabel = true)
                }
                NavigationBarItem(selected = selectedTab in moreTabs,
                    onClick = { showMore = true },
                    icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "بخش‌های دیگر", modifier = Modifier.size(20.dp)) },
                    label = { Text("بیشتر", style = MaterialTheme.typography.labelSmall) },
                    alwaysShowLabel = true)
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selectedTab == AurumTab.Chart || selectedTab == AurumTab.Signal) {
                AppHeader(symbol = market.symbol, price = market.lastPrice, interval = market.interval,
                    lastUpdate = market.feed.lastSuccessAt, onRefresh = viewModel::refreshNow)
                FeedBanner(status = market.feed, lastPrice = market.lastPrice,
                    lastBarTime = market.candles.lastOrNull()?.time, showingCache = market.showingCachedData)
            }
            Box(Modifier.fillMaxSize()) {
                when (selectedTab) {
                    AurumTab.Home -> HomeScreen(viewModel, market,
                        onChart = { open(AurumTab.Chart) }, onSignal = { open(AurumTab.Signal) },
                        onNews = { open(AurumTab.News) }, onLearn = { open(AurumTab.Learn) },
                        onJournal = { open(AurumTab.Journal) }, onSettings = { open(AurumTab.Settings) })
                    AurumTab.Chart -> ChartScreen(viewModel, market,
                        onOpenSettings = { open(AurumTab.Settings) }, onOpenJournal = { open(AurumTab.Journal) })
                    AurumTab.Signal -> SignalScreen(viewModel, market, onOpenNews = { open(AurumTab.News) })
                    AurumTab.Watch -> MarketWatchScreen(viewModel, onOpenSettings = { open(AurumTab.Settings) })
                    AurumTab.News -> PersianNewsScreen(viewModel, onOpenSettings = { open(AurumTab.Settings) })
                    AurumTab.Learn -> LearnScreen(viewModel)
                    AurumTab.Journal -> JournalScreen(viewModel, market)
                    AurumTab.Settings -> SettingsScreen(viewModel, settings)
                    AurumTab.Api -> ApiMenuScreen(settings, onForexSettings = { open(AurumTab.Settings) })
                }
            }
        }
    }
    if (showMore && moreTabs.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showMore = false }, containerColor = AurumColors.Surface) {
            Text("بخش‌های دیگر",
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.TextPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            moreTabs.forEach { destination ->
                ListItem(headlineContent = { Text(destination.label) },
                    leadingContent = { Icon(destination.icon, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { open(destination); showMore = false })
            }
        }
    }
}

@Composable
private fun AppHeader(symbol: String, price: Double?, interval: com.aurum.edge.core.Interval,
                      lastUpdate: Long?, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(AurumColors.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).background(AurumColors.Gold.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center) {
            Text(symbol.take(3), color = AurumColors.Gold, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(symbol, style = MaterialTheme.typography.titleMedium, color = AurumColors.TextPrimary)
            Text("${interval.label} · آخرین دریافت ${relativeTime(lastUpdate)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatPrice(price), style = MaterialTheme.typography.titleMedium,
                color = AurumColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text(if (price == null) "بدون دادهٔ تازه" else "آخرین مشاهده · وضعیت فید پایین صفحه",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی", tint = AurumColors.Gold)
        }
    }
}

@Composable
fun ModePill(text: String, color: Color) = Pill(text = text, color = color)
