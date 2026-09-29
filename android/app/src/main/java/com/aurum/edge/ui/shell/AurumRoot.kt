package com.aurum.edge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.ui.components.FeedBanner
import com.aurum.edge.ui.theme.AurumColors

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
        if (started) {
            viewModel.resumeVisibleForexFeed()
            // Returning from Android's "install unknown apps" settings should continue a
            // previously downloaded update without making the user find the update tab again.
            viewModel.resumeUpdateInstall()
        }
    }
    val selectedTab = AurumTab.entries.firstOrNull { it.name == tab && it in primaryTabs + moreTabs } ?: primaryTabs.first()
    fun open(destination: AurumTab) { if (destination in primaryTabs + moreTabs) tab = destination.name }

    Scaffold(containerColor = AurumColors.Bg, snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AurumBottomBar(selectedTab, onSelect = ::open, onMore = { showMore = true })
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
                    AurumTab.Update -> UpdateScreen(viewModel)
                    AurumTab.Settings -> SettingsScreen(viewModel, settings)
                    AurumTab.Api -> ApiMenuScreen(settings, onForexSettings = { open(AurumTab.Settings) })
                }
            }
        }
    }
    if (showMore && moreTabs.isNotEmpty()) {
        MoreTabsSheet(
            onDismiss = { showMore = false },
            onOpen = { destination -> open(destination); showMore = false },
        )
    }
}
