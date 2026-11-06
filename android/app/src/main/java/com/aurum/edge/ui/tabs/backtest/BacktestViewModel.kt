package com.aurum.edge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.app.AppContainer
import com.aurum.edge.core.BacktestNeuralTrainer
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.data.BacktestDataStore
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * وضعیت «آزمایشگاه بک‌تست».
 *
 * **مرز جدایی که در کل این صفحه رعایت شده:** هیچ‌جا `JournalStore`، `SettingsStore`، حساب
 * کاغذی یا آپدیت دست نمی‌خورد. تنها چیزی که از بیرون گرفته می‌شود یک نمونهٔ جدا از شبکهٔ
 * عصبی برای تمرین است (`BacktestNeuralTrainer`) که فایل خودش را دارد.
 */
class BacktestViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val store = BacktestDataStore(application)
    private val trainer = BacktestNeuralTrainer(application)

    private val _dataSets = MutableStateFlow<List<BacktestDataStore.DataSet>>(emptyList())
    val dataSets: StateFlow<List<BacktestDataStore.DataSet>> = _dataSets.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _candles = MutableStateFlow<List<Candle>>(emptyList())
    val candles: StateFlow<List<Candle>> = _candles.asStateFlow()

    private val _cursor = MutableStateFlow(0)
    /** کندل تا کدام نقطه به کاربر نشان داده شود (۰ = ابتدا). */
    val cursor: StateFlow<Int> = _cursor.asStateFlow()

    private val _visibleCount = MutableStateFlow(140)
    val visibleCount: StateFlow<Int> = _visibleCount.asStateFlow()

    private val _result = MutableStateFlow<BacktestReplayEngine.Result?>(null)
    val result: StateFlow<BacktestReplayEngine.Result?> = _result.asStateFlow()

    /** نقطه‌ای از چارت که نتیجهٔ فعلی روی آن محاسبه شده است. */
    private val _resultCursor = MutableStateFlow(0)
    val resultCursor: StateFlow<Int> = _resultCursor.asStateFlow()

    /**
     * آیا کاربر بعد از اجرای بک‌تست جلو/عقب رفته است؟
     *
     * بدون این پرچم، آمار و نشانگرهای چارت به بازهٔ قبلی تعلق داشتند ولی کنار کندل‌های تازه
     * دیده می‌شدند — یعنی نمودار دروغ می‌گفت. حالا هر جابه‌جایی، نتیجه را «کهنه» علامت می‌زند
     * و کاربر می‌داند باید دوباره اجرا کند.
     */
    val resultIsStale: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(_result, _resultCursor, _cursor) { res, resCursor, cur ->
        res != null && resCursor != cur
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L), false)

    private val _training = MutableStateFlow<BacktestNeuralTrainer.TrainingReport?>(null)
    val training: StateFlow<BacktestNeuralTrainer.TrainingReport?> = _training.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _statusFa = MutableStateFlow("")
    val statusFa: StateFlow<String> = _statusFa.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: kotlinx.coroutines.flow.SharedFlow<String> = _toast.asSharedFlow()

    /** تنظیمات اجرای بک‌تست (همه در حافظه؛ هیچ‌کدام در تنظیمات برنامه نوشته نمی‌شود). */
    data class Settings(
        val strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        val symbol: String = "",
        val runAllStrategies: Boolean = false,
        val startEquityUsd: Double = 1_000.0,
        val riskPercent: Double = 0.5,
        val useSmartExit: Boolean = true,
        val usePreserveMode: Boolean = true,
        val useTimeStop: Boolean = true,
        val optimisticSameBarFill: Boolean = false,
        val maxBars: Int = 0,
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val storePathFa: String get() = store.storagePathFa()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { store.list() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                _dataSets.value = list
                if (_selectedId.value == null || list.none { it.id == _selectedId.value }) {
                    list.firstOrNull()?.let { select(it.id) }
                }
            }
        }
    }

    fun importCsv(uri: Uri, symbolOverride: String, dropFlatBars: Boolean) {
        if (_busy.value) {
            emit("یک کار در جریان است؛ چند لحظه صبر کنید.")
            return
        }
        _busy.value = true
        _statusFa.value = "خواندن CSV…"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.import(uri, symbolOverride, dropFlatBars) }
                .onSuccess { set ->
                    withContext(Dispatchers.Main) {
                        _statusFa.value = "✅ ${set.displayName}: ${set.candleCount} کندل ${set.intervalLabel} · ${set.symbol}"
                        _settings.update { cur -> cur.copy(symbol = set.symbol) }
                        select(set.id)
                        refresh()
                    }
                }
                .onFailure { err ->
                    withContext(Dispatchers.Main) {
                        _statusFa.value = ""
                        emit("⚠️ ${err.message ?: "فایل خوانده نشد"}")
                    }
                }
            _busy.value = false
        }
    }

    fun select(id: String?) {
        if (id == null) {
            _selectedId.value = null
            _candles.value = emptyList()
            _result.value = null
            _training.value = null
            return
        }
        _selectedId.value = id
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching { store.loadCandles(id) }.getOrDefault(emptyList())
            val set = runCatching { store.list().firstOrNull { it.id == id } }.getOrNull()
            withContext(Dispatchers.Main) {
                _candles.value = loaded
                _settings.update { cur ->
                    cur.copy(symbol = set?.symbol ?: cur.symbol.ifBlank { "XAU/USD" })
                }
                // پیش‌فرض: صفحهٔ آخر داده، تا معامله‌های تازه دیده شوند.
                _cursor.value = (loaded.size / 2).coerceAtLeast(1)
                _result.value = null
                _training.value = null
                _statusFa.value = if (loaded.isEmpty()) "" else
                    "${loaded.size} کندل ${set?.intervalLabel ?: ""} · ${set?.symbol ?: ""} آماده است"
                _busy.value = false
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.delete(id) }
            if (_selectedId.value == id) {
                withContext(Dispatchers.Main) {
                    _selectedId.value = null
                    _candles.value = emptyList()
                    _result.value = null
                    _training.value = null
                }
            }
            refresh()
        }
    }

    fun updateSettings(block: (Settings) -> Settings) {
        _settings.update(block)
    }

    fun setCursor(index: Int) {
        val max = _candles.value.size
        if (max <= 0) return
        _cursor.value = index.coerceIn(1, max)
    }

    fun stepCursor(delta: Int) = setCursor(_cursor.value + delta)

    fun setVisibleCount(count: Int) {
        _visibleCount.value = count.coerceIn(40, 600)
    }

    /** اجرای بک‌تست تا نقطهٔ فعلی نمودار (گذشته‌ای که کاربر جلو آمده است). */
    fun runBacktest() {
        if (_busy.value) return
        val all = _candles.value
        if (all.isEmpty()) {
            emit("اول یک فایل CSV وارد کنید.")
            return
        }
        val cut = all.take(_cursor.value.coerceIn(1, all.size))
        if (cut.size < 80) {
            emit("برای بک‌تست حداقل ۸۰ کندل لازم است؛ جلوتر بروید.")
            return
        }
        val set = _dataSets.value.firstOrNull { it.id == _selectedId.value }
        val s = _settings.value
        _busy.value = true
        _progress.value = 0.0
        _result.value = null
        _training.value = null
        _statusFa.value = "در حال اجرای بک‌تست روی ${cut.size} کندل…"
        viewModelScope.launch(Dispatchers.Default) {
            val config = BacktestReplayEngine.Config(
                strategyId = s.strategyId,
                symbol = s.symbol.ifBlank { set?.symbol ?: "XAU/USD" },
                triggerInterval = set?.interval ?: Interval.M15,
                startEquityUsd = s.startEquityUsd,
                riskPercent = s.riskPercent,
                runAllStrategies = s.runAllStrategies,
                useSmartExit = s.useSmartExit,
                usePreserveMode = s.usePreserveMode,
                useTimeStop = s.useTimeStop,
                optimisticSameBarFill = s.optimisticSameBarFill,
                maxBars = s.maxBars,
                captureEntryReports = true,
            )
            runCatching { BacktestReplayEngine.run(cut, config) { p -> _progress.value = p } }
                .onSuccess { res ->
                    withContext(Dispatchers.Main) {
                        _result.value = res
                        _resultCursor.value = cut.size
                        _progress.value = 1.0
                        _statusFa.value = buildString {
                            append("✅ بک‌تست تمام شد: ${res.trades.size} معامله در ${res.steps} کندل")
                            val stats = res.totals
                            if (stats != null) {
                                append(" · برد ${stats.winRatePct}% · خالص ${fmtUsd(stats.netPnlUsd)}")
                            }
                            append(" (${res.elapsedMs} میلی‌ثانیه)")
                        }
                    }
                }
                .onFailure { err ->
                    withContext(Dispatchers.Main) {
                        _statusFa.value = ""
                        _progress.value = 0.0
                        emit("⚠️ بک‌تست شکست خورد: ${err.message ?: "خطای نامشخص"}")
                    }
                }
            _busy.value = false
        }
    }

    /** آموزش شبکهٔ عصبیِ اتاق تمرین با معامله‌های همین بک‌تست. */
    fun trainNeural() {
        val res = _result.value ?: run {
            emit("اول بک‌تست را اجرا کنید.")
            return
        }
        if (_busy.value) return
        if (res.trades.none { !it.isOpen }) {
            emit("معاملهٔ بسته‌شده‌ای برای آموزش وجود ندارد.")
            return
        }
        _busy.value = true
        _statusFa.value = "آموزش شبکهٔ عصبی در اتاق تمرین…"
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                trainer.train(
                    trades = res.trades,
                    symbol = res.config.symbol,
                    reports = res.entryReports,
                )
            }.onSuccess { report ->
                withContext(Dispatchers.Main) {
                    _training.value = report
                    _statusFa.value = "🧠 تمرین تمام شد: ${report.samplesFed} نمونه · " +
                        "دقت درون‌نمونه‌ای ${report.inSampleAccuracyPct}% (این اعداد روی معاملات واقعی اثر ندارند)"
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _statusFa.value = ""
                    emit("⚠️ آموزش شکست خورد: ${err.message ?: "خطای نامشخص"}")
                }
            }
            _busy.value = false
        }
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.clearAll() }
            withContext(Dispatchers.Main) {
                _selectedId.value = null
                _candles.value = emptyList()
                _result.value = null
                _training.value = null
                _statusFa.value = ""
                emit("داده‌های بک‌تست پاک شد (ژورنال و حساب برنامه دست نخورد).")
            }
            refresh()
        }
    }

    fun clearTraining() {
        _training.value = null
    }

    private fun emit(msg: String) {
        _toast.tryEmit(msg)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = container.appContext as Application
            return BacktestViewModel(app, container) as T
        }
    }
}

internal fun fmtUsd(v: Double): String = String.format(java.util.Locale.US, "%+,.2f$", v)
