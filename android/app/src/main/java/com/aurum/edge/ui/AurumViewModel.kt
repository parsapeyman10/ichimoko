package com.aurum.edge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.core.AppContainer
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.Signal
import com.aurum.edge.data.JournalStats
import com.aurum.edge.engine.Backtester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LearnState {
    data object Idle : LearnState
    data class Loading(val step: String) : LearnState
    data class Done(val result: Backtester.Result, val interval: Interval) : LearnState
    data class Failed(val message: String) : LearnState
}

class AurumViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsStore.settings
    val market = container.market.state
    val trades: StateFlow<List<PaperTrade>> = container.journalStore.trades

    private val _stats = MutableStateFlow(container.journalStore.stats())
    val stats: StateFlow<JournalStats> = _stats.asStateFlow()

    private val _learn = MutableStateFlow<LearnState>(LearnState.Idle)
    val learn: StateFlow<LearnState> = _learn.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        viewModelScope.launch {
            container.journalStore.load()
            _stats.value = container.journalStore.stats()
            container.market.start()
        }
    }

    fun refreshNow() = container.market.refreshNow()

    fun setInterval(interval: Interval) = container.market.setInterval(interval)

    fun saveApiKey(key: String) {
        container.settingsStore.update { it.copy(apiKey = key.trim()) }
        container.market.restart()
    }

    fun saveSymbol(symbol: String) {
        container.settingsStore.update { it.copy(symbol = symbol.trim().ifBlank { "XAU/USD" }) }
        container.market.restart()
    }

    fun saveRiskPercent(value: Double) = container.settingsStore.update { it.copy(riskPercent = value.coerceIn(0.1, 5.0)) }

    fun saveBalance(value: Double) = container.settingsStore.update { it.copy(accountBalance = value.coerceAtLeast(10.0)) }

    fun saveMinConfidence(value: Double) = container.settingsStore.update { it.copy(minConfidence = value.coerceIn(72.0, 95.0)) }

    fun setNotifyOnSignal(enabled: Boolean) = container.settingsStore.update { it.copy(notifyOnSignal = enabled) }

    fun setMonitorFlag(enabled: Boolean) = container.settingsStore.update { it.copy(backgroundMonitor = enabled) }

    fun openPaperTrade(signal: Signal) {
        val price = market.value.lastPrice
        if (price == null || !signal.isActionable) {
            _toast.value = "سیگنال قابل معامله نیست یا قیمت واقعی موجود نیست"
            return
        }
        viewModelScope.launch {
            val s = container.settingsStore.read()
            val trade = container.journalStore.open(signal, price, s.accountBalance, s.riskPercent)
            _stats.value = container.journalStore.stats()
            _toast.value = "پوزیشن کاغذی باز شد: ${trade.action.name} روی قیمت واقعی ${String.format("%.2f", trade.entry)}"
        }
    }

    fun closePaperTrade(trade: PaperTrade) {
        val price = market.value.lastPrice ?: return
        viewModelScope.launch {
            container.journalStore.close(trade.id, price, "بستن دستی روی قیمت واقعی")
            _stats.value = container.journalStore.stats()
        }
    }

    fun clearJournal() {
        viewModelScope.launch {
            container.journalStore.clear()
            _stats.value = container.journalStore.stats()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            container.candleCache.clear()
            _toast.value = "کش دیتای واقعی پاک شد"
            container.market.restart()
        }
    }

    fun runLearn(
        interval: Interval,
        bars: Int,
        balance: Double,
        risk: Double,
        spread: Double,
        commission: Double,
        threshold: Double,
    ) {
        viewModelScope.launch {
            _learn.value = LearnState.Loading("دانلود $bars کندل واقعی ${interval.label} از Twelve Data…")
            try {
                val result = container.runBacktest(interval, bars, balance, risk, spread, commission, threshold)
                _learn.value = LearnState.Done(result, interval)
            } catch (e: Exception) {
                _learn.value = LearnState.Failed(e.message ?: "خطا در دریافت داده واقعی")
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }
}

class AurumViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AurumViewModel(container) as T
}
