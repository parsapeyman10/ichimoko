package com.aurum.edge.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.ui.components.BitcoinIchimokuStrategyCard
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun AurumRoot(viewModel: AurumViewModel) {
    var tab by rememberSaveable { mutableStateOf(AurumTab.Home.name) }
    var started by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val market by viewModel.market.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val tabStates = rememberSaveableStateHolder()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            viewModel.startApp(context)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(toast!!)
            viewModel.consumeToast()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        com.aurum.edge.data.AppNetwork.refreshSystemNetwork()
        if (started) {
            viewModel.resumeVisibleForexFeed()
            viewModel.resumeUpdateInstall()
        }
    }
    val selectedTab = AurumTab.entries.firstOrNull { it.name == tab && it in primaryTabs } ?: primaryTabs.first()
    var chartTicker by rememberSaveable { mutableStateOf(TradingViewSymbols.find(settings.symbol) ?: "BINANCE:BTCUSDT") }

    fun open(destination: AurumTab) {
        if (destination in primaryTabs) tab = destination.name
    }
    fun openChartFor(symbol: String) {
        chartTicker = TradingViewSymbols.find(symbol) ?: symbol
        open(AurumTab.Chart)
    }

    Scaffold(
        containerColor = AurumColors.Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AurumBottomBar(selectedTab, onSelect = ::open)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize()) {
                tabStates.SaveableStateProvider(selectedTab.name) {
                    when (selectedTab) {
                        AurumTab.Home -> HomeScreen(
                            viewModel = viewModel,
                            market = market,
                            onChartSymbol = ::openChartFor,
                            onJournal = { open(AurumTab.Journal) },
                        )
                        AurumTab.Strategy -> Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 14.dp),
                        ) {
                            BitcoinIchimokuStrategyCard(
                                viewModel = viewModel,
                                onOpenChartForSymbol = ::openChartFor,
                            )
                        }
                        AurumTab.Chart -> ChartScreen(
                            initialTicker = chartTicker,
                            onSelectTicker = { chartTicker = it },
                        )
                        AurumTab.Journal -> JournalRiskScreen(
                            viewModel = viewModel,
                            onChartSymbol = ::openChartFor,
                        )
                        AurumTab.Update -> UpdateScreen(viewModel)
                    }
                }
            }
        }
    }
}
