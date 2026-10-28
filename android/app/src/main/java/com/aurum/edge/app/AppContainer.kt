package com.aurum.edge.app

import android.content.Context
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.NeuralSelfLearningEngine
import com.aurum.edge.data.AppNetwork
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Process-wide wiring for the three remaining tabs (Home, Chart, Update).
 * All legacy strategy engines, scanners, and stored method data are purged on startup.
 */
class AppContainer(context: Context) {

    companion object {
        private const val CLOCK_PERIOD_MS = 20_000L
    }

    val appContext: Context = context.applicationContext

    init {
        purgeLegacyData(appContext)
        AppNetwork.start(appContext)
    }

    val settingsStore = SettingsStore(appContext)
    val journalStore = JournalStore(appContext, settingsStore = settingsStore)
    val neuralEngine = NeuralSelfLearningEngine(appContext)
    val updater = AppUpdateRepository(appContext)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CLOCK_PERIOD_MS)
        }
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), System.currentTimeMillis())

    private val _verifiedMarket = MutableStateFlow(MarketState(symbol = settingsStore.read().symbol))
    val verifiedMarket: StateFlow<MarketState> = _verifiedMarket.asStateFlow()

    private fun purgeLegacyData(context: Context) {
        runCatching {
            val legacyFiles = listOf(
                "paper_journal.json",
                "paper_journal.json.bak",
                "paper_opportunities.json",
                "paper_opportunities.json.bak",
                "decision_log.json",
                "decision_log.json.bak",
                "quote_history.json",
                "quote_history.json.bak",
            )
            legacyFiles.forEach { name ->
                File(context.filesDir, name).delete()
            }
            context.filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("candles_") ||
                    file.name.startsWith("scan_") ||
                    file.name.startsWith("candle_cache")
                ) {
                    file.deleteRecursively()
                }
            }
            context.getSharedPreferences("aurum_watch_settings", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("aurum_source_promotion", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
