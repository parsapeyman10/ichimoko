package com.aurum.edge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.app.AppContainer
import com.aurum.edge.core.BacktestNeuralTrainer
import com.aurum.edge.core.BacktestCsvReader
import com.aurum.edge.core.BacktestReplayEngine
import com.aurum.edge.core.BacktestTradeArchive
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.data.BacktestArchiveStore
import com.aurum.edge.data.BacktestDataStore
import com.aurum.edge.data.BacktestJob
import com.aurum.edge.data.BacktestJobSpec
import com.aurum.edge.data.BacktestJobStore
import com.aurum.edge.data.BacktestSession
import com.aurum.edge.data.BacktestSessionStore
import com.aurum.edge.service.BacktestJobWorker
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val archiveStore = BacktestArchiveStore(application)
    private val trainer = BacktestNeuralTrainer(application)
    private val jobStore = BacktestJobStore(application)
    private val sessionStore = BacktestSessionStore(application)

    /**
     * فهرست کارهای بک‌تستِ پس‌زمینه.
     *
     * ⚠️ این دو ویژگی **باید** پیش از بلوک `init` اعلان شوند: `init` تابع `refresh()` را
     * صدا می‌زند و آن هم `refreshJobs()` را؛ اگر `_jobs` پایین‌ترِ کلاس اعلان شده باشد،
     * در آن لحظه مقدارش `null` است و باز کردن تب بک‌تست به NullPointerException و
     * بسته‌شدن اپ می‌انجامد (همان دستهٔ خطای `heavyRunFa` که قبلاً رخ داد).
     */
    private val _jobs = MutableStateFlow<List<BacktestJob>>(emptyList())
    val jobs: StateFlow<List<BacktestJob>> = _jobs.asStateFlow()

    /**
     * خواندن فهرست کارها از حافظه — روی رشتهٔ IO، نه رشتهٔ اصلی.
     * خواندن فایل روی رشتهٔ اصلی هنگام باز شدن تب، هم قفل‌شدن لحظه‌ای می‌سازد و هم
     * اگر فایل نیمه‌نوشته باشد می‌تواند استثنا بدهد؛ هر دو با `runCatching` + IO حل شده‌اند.
     */
    fun refreshJobs() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { jobStore.list() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { _jobs.value = list }
        }
    }

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

    /** آرشیو معاملاتی آخرین بک‌تست (در پایان هر اجرا ساخته می‌شود). */
    private val _archive = MutableStateFlow<BacktestTradeArchive.Archive?>(null)
    val archive: StateFlow<BacktestTradeArchive.Archive?> = _archive.asStateFlow()

    /** فایل‌های CSV/JSON همان آرشیو روی حافظهٔ داخلی. */
    private val _archiveFiles = MutableStateFlow<BacktestArchiveStore.ArchiveFiles?>(null)
    val archiveFiles: StateFlow<BacktestArchiveStore.ArchiveFiles?> = _archiveFiles.asStateFlow()

    /**
     * ── «سود هر معامله برود در ژورنال، ولی پولش وارد پولم نشود» ──────────────
     *
     * هر معاملهٔ آرشیو با `origin = BACKTEST` در ژورنال ثبت می‌شود:
     *  • سود/زیان، R، دلیل خروج و نمادِ همین معامله در ژورنال دیده می‌شود،
     *  • موجودی حساب **تغییر نمی‌کند** (`applyBalanceDelta` ردیف‌های بک‌تست را رد می‌کند)،
     *  • چون ژورنال تغییر می‌کند، موتور یادگیری خودکار یک درس از هر معامله می‌گیرد و
     *    پارامترهای تیون‌شده را در پیکربندی بعدی اعمال می‌کند (همان «با هر معامله یک درس»).
     *
     * شناسهٔ هر ردیف از هش محتوای آرشیو + شمارهٔ معامله ساخته می‌شود، پس ثبت دوبارهٔ همان
     * آرشیو ردیف تکراری نمی‌سازد.
     */
    fun recordArchiveToJournal() {
        if (_busy.value) return
        val archive = _archive.value ?: run {
            emit("اول بک‌تست را اجرا کنید تا آرشیو ساخته شود.")
            return
        }
        if (archive.rows.isEmpty()) {
            emit("این آرشیو معامله‌ای ندارد؛ چیزی برای ثبت نیست.")
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val interval = _dataSets.value.firstOrNull { it.id == archive.dataSetId }?.interval ?: Interval.M15
            val strategyId = _settings.value.strategyId
            val strategyTitle = archive.strategyLabel
            val base = archive.contentHash.take(10)
            val trades = archive.rows.map { row ->
                val risk = if (row.rMultiple != 0.0) kotlin.math.abs(row.netPnlUsd / row.rMultiple) else 0.0
                val rr = runCatching {
                    val riskDist = kotlin.math.abs(row.entryPrice - row.stopLoss)
                    if (riskDist > 0.0) kotlin.math.abs(row.takeProfit - row.entryPrice) / riskDist else 2.0
                }.getOrDefault(2.0)
                com.aurum.edge.core.PaperTrade(
                    id = "bt_${base}_${row.tradeNumber}",
                    symbol = archive.symbol,
                    interval = interval,
                    action = row.action,
                    entry = row.entryPrice,
                    stopLoss = row.stopLoss,
                    takeProfit = row.takeProfit,
                    confidence = 0.0,
                    riskReward = rr.coerceIn(1.5, 3.0),
                    openedAt = row.entryTime,
                    closedAt = row.exitTime,
                    exitPrice = row.exitPrice,
                    exitReason = row.exitReasonFa,
                    pnlUsd = row.netPnlUsd,
                    positionOz = 1.0,
                    positionUnit = "",
                    autoOpened = true,
                    note = "ثبت از بک‌تست ${archive.dataSetName} · بدون اثر روی موجودی · $strategyTitle",
                    initialStopLoss = row.stopLoss,
                    strategyId = strategyId,
                    strategyTitleFa = strategyTitle,
                    // ریسک ثابت از خودِ معامله گرفته می‌شود تا R ژورنال با R بک‌تست یکی باشد.
                    fixedRiskUsd = risk,
                    optimalRiskReward = rr.coerceIn(1.5, 3.0),
                    tradeNumber = row.tradeNumber,
                    origin = com.aurum.edge.core.PaperTrade.ORIGIN_BACKTEST,
                    lessonFa = buildLessonFa(row),
                )
            }
            val inserted = runCatching { container.journalStore.importTrades(trades) }.getOrDefault(0)
            val learned = runCatching {
                container.neuralEngine.syncWithClosedJournalTrades(container.journalStore.trades.value)
            }.getOrNull()
            val applied = if ((learned?.newlyLearnedThisRun ?: 0) > 0) {
                runCatching {
                    val refreshed = container.neuralEngine
                        .applyAutoTunedParametersToConfig(container.settingsStore.read().baseStrategyConfig)
                    container.settingsStore.update { cur -> cur.copy(baseStrategyConfig = refreshed) }
                    true
                }.getOrDefault(false)
            } else {
                false
            }
            val balance = runCatching { container.settingsStore.read().accountBalance }.getOrDefault(0.0)
            withContext(Dispatchers.Main) {
                _busy.value = false
                val learnedNow = learned?.newlyLearnedThisRun ?: 0
                emit(
                    "📓 ${trades.size} معامله در ژورنال ثبت شد (تازه: $inserted) · " +
                        "درس گرفته‌شده: $learnedNow" +
                        if (applied) " · پیکربندی هوش با آموخته‌های تازه به‌روز شد" else "" +
                        " · موجودی دست‌نخورده: ${fmtUsd(balance)}",
                )
            }
        }
    }

    /** یک درس کوتاه و قابل‌فهم از خودِ معامله — همان چیزی که در ژورنال کنار ردیف می‌نشیند. */
    private fun buildLessonFa(row: com.aurum.edge.core.BacktestTradeArchive.Row): String {
        val outcome = if (row.won) "سود" else "ضرر"
        val rStr = String.format(java.util.Locale.US, "%+.2f", row.rMultiple)
        val timeFa = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(row.entryTime))
        val directionFa = if (row.action == com.aurum.edge.domain.SignalAction.BUY) "خرید" else "فروش"
        val why = when {
            row.exitReasonFa.contains("حد ضرر") -> "حد ضرر خورد؛ ورود در جهت اشتباه یا حد خیلی نزدیک بود."
            row.exitReasonFa.contains("حد سود") -> "به حد سود رسید؛ همان الگو تکرارشدنی است."
            row.barsHeld > 20 -> "بیش از ۲۰ کندل طول کشید؛ نگه‌داشتن طولانی، هزینهٔ فرصت دارد."
            else -> "خروج طبق قاعدهٔ هوشمند انجام شد."
        }
        return "$timeFa · $directionFa · $outcome $rStr R (${row.exitReasonFa}) — $why"
    }

    /** اگر آرشیو فعلی قبلاً اعمال شده باشد، سطر دفتر؛ وگرنه `null`. */
    private val _appliedEntry = MutableStateFlow<BacktestArchiveStore.AppliedEntry?>(null)
    val appliedEntry: StateFlow<BacktestArchiveStore.AppliedEntry?> = _appliedEntry.asStateFlow()

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

    /**
     * کندل‌های تایم‌فریم بالای **واقعی** (۱ ساعته/۴ ساعته) که کاربر جداگانه وارد کرده است.
     *
     * چرا لازم بود؟ موتور از قبل `macroCandlesOverride`/`strategyCandlesOverride` را
     * پشتیبانی می‌کرد ولی هیچ‌جای اپ آن‌ها را پر نمی‌کرد؛ یعنی «۴ساعت → ۱ساعت → ۱۵دقیقه»
     * در عمل از بازنمونه‌گیری همان فایل ۱۵دقیقه‌ای ساخته می‌شد. اگر کاربر فایل واقعی بدهد
     * همان استفاده می‌شود، و اگر ندهد این نگاشت خالی می‌مانَد و رفتار **دقیقاً** مثل قبل است.
     */
    private val _htfCandles = MutableStateFlow<Map<Interval, List<Candle>>>(emptyMap())
    val htfCandles: StateFlow<Map<Interval, List<Candle>>> = _htfCandles.asStateFlow()

    private val _training = MutableStateFlow<BacktestNeuralTrainer.TrainingReport?>(null)
    val training: StateFlow<BacktestNeuralTrainer.TrainingReport?> = _training.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    /** زمان تقریبی باقی‌مانده به فارسی؛ خالی = هنوز قابل تخمین نیست. */
    private val _etaFa = MutableStateFlow("")
    val etaFa: StateFlow<String> = _etaFa.asStateFlow()

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
        /** لایهٔ هیدن کراس در «بیس» فعال باشد (برای مقایسهٔ A/B). */
        val includeHiddenCrossLayer: Boolean = true,
        /**
         * ماشهٔ «فشردگی → انبساط» برای «بیس» فعال باشد.
         *
         * پیش‌فرض خاموش است تا خروجی بیس دقیقاً مثل قبل بماند؛ روشن‌کردنش مسیر تازهٔ
         * ورود (شکست تأییدشده / بازآزمایی تأییدشده) را فعال می‌کند.
         */
        val compressionExpansion: Boolean = false,
        /**
         * موتور «پیمان کریپتو» در حالت معاملهٔ کاغذی.
         * خاموش = `DISABLED` (هیچ ارزیابی‌ای انجام نمی‌شود و رفتار قبلی دست‌نخورده است).
         */
        val peymanCryptoPaper: Boolean = false,
        /**
         * هزینهٔ محافظه‌کارانه: کارمزد + اسپرد × ۳.
         *
         * «اگر به سودده‌بودن کمک می‌کند انجام بده.» — کم‌کردن هزینه سود را *بیشتر* نشان می‌دهد،
         * ولی دروغ است؛ این کلید عمداً هزینه را سه برابر می‌کند تا معلوم شود سود از استراتژی
         * می‌آید یا از تخمینِ خوش‌بینانهٔ هزینه. اگر با ×۳ هم مثبت ماند، سود واقعی است.
         * کاربر انتخاب کرد که **پیش‌فرض روشن** باشد؛ عدد قبلی (×۱) با یک ضربه در دسترس است.
         */
        val conservativeCosts: Boolean = true,
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /**
     * آیا این اجرا «سنگین» است؟ آستانه از اندازه‌گیری واقعی می‌آید: بالای ۵ دقیقه یعنی کاربر
     * باید بداند و بتواند بازه را کوچک کند، نه این‌که ۱۰ دقیقه به نوار پیشرفت خیره شود.
     *
     * جای این ویژگی عمدی است: باید **بعد از** `_settings` تعریف شود، چون مقدار اولیهٔ
     * ویژگی‌ها به ترتیب اعلان اجرا می‌شود و اگر بالا باشد، دسترسی به `_settings` هنوز
     * مقدار نگرفته است (`Variable '_settings' must be initialized`).
     */
    val heavyRunFa: StateFlow<String> = combine(_candles, _cursor, _settings) { all, cursor, s ->
        val n = all.take(cursor.coerceIn(1, all.size.coerceAtLeast(1))).size
        val ms = BacktestReplayEngine.estimateMs(n, s.runAllStrategies, s.maxBars)
        when {
            n < 200 -> ""
            ms >= HEAVY_RUN_MS -> "این اجرا سنگین است: ${
                BacktestReplayEngine.estimateSentenceFa(n, s.runAllStrategies, s.maxBars)
            }. می‌توانید «حداکثر کندل» را کم کنید تا سریع‌تر تمام شود؛ نتیجهٔ بازهٔ کمتر هم معتبر است، فقط معامله‌های کمتر."
            ms >= SLOW_RUN_MS -> "اجرای متوسط: ${
                BacktestReplayEngine.estimateSentenceFa(n, s.runAllStrategies, s.maxBars)
            }."
            else -> ""
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val storePathFa: String get() = store.storagePathFa()

    /**
     * خلاصهٔ آخرین اجرا، پس از بازگشت به صفحه.
     *
     * چرا فقط متن و نه خودِ `Result`؟ ذخیرهٔ صدها معامله در JSON هم کند است و هم تکراری؛
     * آرشیو کامل همان معامله‌ها در `BacktestArchiveStore` هست. آن‌چه کاربر گم می‌کرد —
     * «چه فایلی، با چه تنظیماتی، چه نتیجه‌ای» — این‌جا می‌مانَد.
     */
    private val _restoredLinesFa = MutableStateFlow<List<String>>(emptyList())
    val restoredLinesFa: StateFlow<List<String>> = _restoredLinesFa.asStateFlow()

    private val _restoredAtMs = MutableStateFlow(0L)
    val restoredAtMs: StateFlow<Long> = _restoredAtMs.asStateFlow()

    /** ذخیرهٔ نشست با کمی تأخیر تا کشیدنِ چارت صد فایل‌نوشتن پشت‌سرهم نسازد. */
    private var persistJob: kotlinx.coroutines.Job? = null

    init {
        restoreSession()
        refresh()
    }

    /**
     * خواندن نشست قبلی: فایل داده، نقطهٔ چارت، همهٔ کلیدها و خلاصهٔ آخرین اجرا.
     * کندل‌ها این‌جا خوانده نمی‌شوند؛ `refresh()` بعد از آمدن فهرست، همان فایل را بار می‌کند.
     */
    private fun restoreSession() {
        val session = runCatching { sessionStore.read() }.getOrNull() ?: return
        _settings.update { cur ->
            cur.copy(
                strategyId = session.strategyId.ifBlank { cur.strategyId },
                symbol = session.symbol,
                runAllStrategies = session.runAllStrategies,
                startEquityUsd = session.startEquityUsd.takeIf { it > 0.0 } ?: cur.startEquityUsd,
                riskPercent = session.riskPercent.takeIf { it > 0.0 } ?: cur.riskPercent,
                useSmartExit = session.useSmartExit,
                usePreserveMode = session.usePreserveMode,
                useTimeStop = session.useTimeStop,
                optimisticSameBarFill = session.optimisticSameBarFill,
                maxBars = session.maxBars,
                includeHiddenCrossLayer = session.includeHiddenCrossLayer,
                compressionExpansion = session.compressionExpansion,
                peymanCryptoPaper = session.peymanCryptoPaper,
                conservativeCosts = session.conservativeCosts,
            )
        }
        _selectedId.value = session.dataSetId.takeIf { it.isNotBlank() }
        _cursor.value = session.cursorIndex.coerceAtLeast(0)
        _restoredLinesFa.value = session.lastRunLinesFa
        _restoredAtMs.value = session.lastRunAtMs
    }

    /** عکسِ لحظه‌ای از نشست فعلی؛ سمت IO نوشته می‌شود. */
    fun persistSession(debounceMs: Long = 700L) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            val s = _settings.value
            val session = BacktestSession(
                dataSetId = _selectedId.value.orEmpty(),
                cursorIndex = _cursor.value,
                strategyId = s.strategyId,
                symbol = s.symbol,
                runAllStrategies = s.runAllStrategies,
                startEquityUsd = s.startEquityUsd,
                riskPercent = s.riskPercent,
                useSmartExit = s.useSmartExit,
                usePreserveMode = s.usePreserveMode,
                useTimeStop = s.useTimeStop,
                optimisticSameBarFill = s.optimisticSameBarFill,
                maxBars = s.maxBars,
                includeHiddenCrossLayer = s.includeHiddenCrossLayer,
                compressionExpansion = s.compressionExpansion,
                peymanCryptoPaper = s.peymanCryptoPaper,
                conservativeCosts = s.conservativeCosts,
                lastRunLinesFa = _restoredLinesFa.value,
                lastRunAtMs = _restoredAtMs.value,
            )
            runCatching { sessionStore.save(session) }
        }
    }

    fun refresh() {
        refreshJobs()
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { store.list() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                _dataSets.value = list
                val wanted = _selectedId.value
                if (wanted == null || list.none { it.id == wanted }) {
                    list.firstOrNull()?.let { select(it.id) }
                } else if (_candles.value.isEmpty()) {
                    // نشست قبلی همین فایل را باز داشت؛ کندل‌ها بدون ریست نقطهٔ چارت برمی‌گردند.
                    select(wanted, keepPosition = true)
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
                        val msg = err.message ?: "فایل خوانده نشد"
                        // پیام فایل سنگین چند خطی و راهنمادار است؛ Toast اندروید آن را
                        // می‌بُرد. پس متن روی صفحه هم می‌ماند (قابل اسکرول و خواندن) و
                        // Toast فقط تذکر کوتاه است.
                        _statusFa.value = "⚠️ $msg"
                        emit("⚠️ فایل وارد نشد")
                    }
                }
            _busy.value = false
        }
    }

    /**
     * وارد کردن فایل تایم‌فریم بالای **واقعی** (۱ ساعته/۴ ساعته) برای همان نماد.
     *
     * اعتبارسنجی عمدی: اگر تایم‌فریم تشخیص‌داده‌شدهٔ فایل با نقشی که کاربر انتخاب کرده یکی
     * نباشد، فایل **رد** می‌شود. دلیل: جفت‌شدن بی‌صدای m30 به‌جای h1 نتیجهٔ بک‌تست را عوض
     * می‌کند و کاربر هیچ‌وقت نمی‌فهمد چرا.
     */
    fun importHtfCsv(uri: Uri, role: Interval, dropFlatBars: Boolean) {
        if (_busy.value) {
            emit("یک کار در جریان است؛ چند لحظه صبر کنید.")
            return
        }
        _busy.value = true
        _statusFa.value = "خواندن فایل ${role.label}…"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("فایل باز نشد؛ دسترسی به مسیر فایل داده نشد.")
                BacktestCsvReader.parse(raw = text, fileName = "", dropFlatBars = dropFlatBars)
            }.onSuccess { parsed ->
                withContext(Dispatchers.Main) {
                    if (parsed.inferredInterval != role) {
                        _statusFa.value = "⚠️ این فایل ${parsed.inferredInterval.label} است، نه ${role.label}؛ " +
                            "برای جلوگیری از نتیجهٔ اشتباه وارد نشد."
                        emit("⚠️ تایم‌فریم فایل نمی‌خوانَد")
                    } else {
                        _htfCandles.update { it + (role to parsed.candles) }
                        _result.value = null
                        _statusFa.value = "✅ ${role.label} واقعی: ${parsed.candles.size} کندل · " +
                            String.format(java.util.Locale.US, "%.1f", parsed.spanDays) + " روز"
                        emit("✅ تایم‌فریم ${role.label} اضافه شد")
                    }
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _statusFa.value = "⚠️ " + (err.message ?: "فایل تایم‌فریم بالا خوانده نشد")
                    emit("⚠️ فایل اضافه نشد")
                }
            }
            _busy.value = false
        }
    }

    /** حذف فایل تایم‌فریم بالا؛ بلافاصله رفتار به «بازنمونه‌گیری از فایل ماشه» برمی‌گردد. */
    fun clearHtf(role: Interval) {
        _htfCandles.update { it - role }
        _result.value = null
        _statusFa.value = "تایم‌فریم ${role.label} حذف شد؛ از این به بعد از فایل ماشه ساخته می‌شود."
    }

    /**
     * @param keepPosition وقتی نشستِ ذخیره‌شده بازیابی می‌شود: نقطهٔ چارت و نمادِ کاربر
     *   دست‌نخورده می‌مانند تا «برگشتن به صفحه» همان چیزی باشد که رفته بود.
     */
    fun select(id: String?, keepPosition: Boolean = false) {
        if (id == null) {
            _selectedId.value = null
            _candles.value = emptyList()
            _result.value = null
            _archive.value = null
            _archiveFiles.value = null
            _appliedEntry.value = null
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
                if (!keepPosition) {
                    _settings.update { cur ->
                        cur.copy(symbol = set?.symbol ?: cur.symbol.ifBlank { "XAU/USD" })
                    }
                }
                // پیش‌فرض: صفحهٔ آخر داده، تا معامله‌های تازه دیده شوند. در بازیابی نشست،
                // همان کندلی که کاربر رویش بود برمی‌گردد (و اگر از داده بیرون زده، آخر داده).
                _cursor.value = if (keepPosition && _cursor.value > 0) {
                    _cursor.value.coerceIn(1, loaded.size.coerceAtLeast(1))
                } else {
                    (loaded.size / 2).coerceAtLeast(1)
                }
                _result.value = null
                _archive.value = null
                _archiveFiles.value = null
                _appliedEntry.value = null
                _training.value = null
                _statusFa.value = if (loaded.isEmpty()) "" else
                    "${loaded.size} کندل ${set?.intervalLabel ?: ""} · ${set?.symbol ?: ""} آماده است"
                _busy.value = false
                persistSession(debounceMs = 0)
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
        // «برمی‌گردم و همه‌چیز از اول» نباید برای کلیدها هم رخ بدهد.
        persistSession()
    }

    // ─────────────────────────── بک‌تست پس‌زمینه ───────────────────────────
    /**
     * کارِ پس‌زمینه یعنی: «من از اپ بیرون می‌روم، تو کار کن و نتیجه را نگه دار.»
     *
     * چرا این‌طور پیاده شد؟ ViewModel با بسته‌شدن صفحه می‌میرد، پس اجرای داخل آن
     * با خروج کاربر متوقف می‌شد. کار به `WorkManager` سپرده می‌شود (پایدار، حتی بعد
     * از ری‌استارت گوشی) و تنظیماتِ کاملِ همان اجرا در `BacktestJobStore` ذخیره
     * می‌شود؛ بنابراین بعداً می‌توانی فقط یک پارامتر را عوض کنی و دوباره اجرا.
     */

    fun queueBackgroundRun(noteFa: String = "") {
        val all = _candles.value
        if (all.isEmpty()) {
            emit("اول یک فایل CSV وارد کنید.")
            return
        }
        val set = _dataSets.value.firstOrNull { it.id == _selectedId.value }
        if (set == null) {
            emit("دادهٔ انتخاب‌شده پیدا نشد.")
            return
        }
        val s = _settings.value
        val id = "job-" + System.currentTimeMillis().toString(36)
        val spec = BacktestJobSpec(
            id = id,
            dataSetId = set.id,
            dataSetName = set.displayName,
            createdAt = System.currentTimeMillis(),
            strategyId = s.strategyId,
            symbol = s.symbol.ifBlank { set.symbol },
            runAllStrategies = s.runAllStrategies,
            startEquityUsd = s.startEquityUsd,
            riskPercent = s.riskPercent,
            useSmartExit = s.useSmartExit,
            usePreserveMode = s.usePreserveMode,
            useTimeStop = s.useTimeStop,
            optimisticSameBarFill = s.optimisticSameBarFill,
            maxBars = s.maxBars,
            includeHiddenCrossLayer = s.includeHiddenCrossLayer,
            compressionExpansion = s.compressionExpansion,
            peymanCryptoPaper = s.peymanCryptoPaper,
            conservativeCosts = s.conservativeCosts,
            cursorIndex = _cursor.value,
            noteFa = noteFa,
        )
        jobStore.upsert(BacktestJob(spec = spec))
        BacktestJobWorker.enqueue(getApplication(), id)
        refreshJobs()
        emit("بک‌تست در پس‌زمینه صف شد (${set.displayName}). می‌توانی از اپ بیرون بروی؛ نتیجه ذخیره می‌شود.")
    }

    fun cancelBackgroundJob(id: String) {
        BacktestJobWorker.cancel(getApplication(), id)
        jobStore.update(id) {
            it.copy(status = BacktestJob.STATUS_CANCELLED, messageFa = "لغو شد.")
        }
        refreshJobs()
        emit("کار پس‌زمینه لغو شد.")
    }

    fun deleteBackgroundJob(id: String) {
        jobStore.remove(id)
        refreshJobs()
    }

    /** تنظیمات یک کار را داخل فرم می‌ریزد تا کاربر عوض کند و دوباره اجرا کند. */
    fun loadJobSettings(id: String) {
        val job = jobStore.find(id) ?: return
        val spec = job.spec
        if (_dataSets.value.any { it.id == spec.dataSetId }) select(spec.dataSetId)
        _settings.update {
            it.copy(
                strategyId = spec.strategyId,
                symbol = spec.symbol,
                runAllStrategies = spec.runAllStrategies,
                startEquityUsd = spec.startEquityUsd,
                riskPercent = spec.riskPercent,
                useSmartExit = spec.useSmartExit,
                usePreserveMode = spec.usePreserveMode,
                useTimeStop = spec.useTimeStop,
                optimisticSameBarFill = spec.optimisticSameBarFill,
                maxBars = spec.maxBars,
                includeHiddenCrossLayer = spec.includeHiddenCrossLayer,
                compressionExpansion = spec.compressionExpansion,
                peymanCryptoPaper = spec.peymanCryptoPaper,
                conservativeCosts = spec.conservativeCosts,
            )
        }
        if (spec.cursorIndex in 1.._candles.value.size) setCursor(spec.cursorIndex)
        emit("تنظیمات کار «${spec.noteFa.ifBlank { spec.dataSetName }}» در فرم نشست؛ تغییر بده و اجرا کن.")
    }

    fun openJobResult(id: String) {
        val job = jobStore.find(id) ?: return
        val r = job.result
        if (r == null) {
            emit("این کار هنوز نتیجه‌ای ندارد (${job.messageFa}).")
            return
        }
        _statusFa.value = "نتیجهٔ کار: ${r.summaryFa}\n" +
            "کندل=${r.candlesUsed} · مدت=${r.elapsedMs / 1000}ثانیه\n" +
            "آرشیو: ${r.archiveCsvPath}"
        emit(r.summaryFa)
    }

    fun clearFinishedJobs() {
        jobStore.clearFinished()
        refreshJobs()
    }

    fun setCursor(index: Int) {
        val max = _candles.value.size
        if (max <= 0) return
        _cursor.value = index.coerceIn(1, max)
        persistSession()
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
        _etaFa.value = ""
        _result.value = null
        _training.value = null
        // برآورد پیش از شروع: کاربر پیش از انتظار می‌داند چه چیزی در انتظارش است و اگر
        // «همهٔ استراتژی‌ها» روی یک فایل سنگین انتخاب کرده، همان‌جا اخطار می‌گیرد.
        val estimateFa = BacktestReplayEngine.estimateSentenceFa(
            barCount = cut.size,
            runAllStrategies = s.runAllStrategies,
            maxBars = s.maxBars,
        )
        _statusFa.value = "در حال اجرای بک‌تست روی ${cut.size} کندل… ($estimateFa)"
        viewModelScope.launch(Dispatchers.Default) {
            // تایم‌فریم‌های بالای واقعی؛ نگاشت خالی = بازنمونه‌گیری از فایل ماشه (رفتار قبلی).
            val htfNow = _htfCandles.value
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
                includeHiddenCrossLayer = s.includeHiddenCrossLayer,
                macroCandlesOverride = htfNow[Interval.H4].orEmpty(),
                strategyCandlesOverride = htfNow[Interval.H1].orEmpty(),
                compressionExpansionEnabled = s.compressionExpansion,
                peymanCryptoConfig = if (s.peymanCryptoPaper) {
                    com.aurum.edge.core.peyman.PeymanCryptoConfig.PAPER
                } else {
                    com.aurum.edge.core.peyman.PeymanCryptoConfig()
                },
                costMultiplier = if (s.conservativeCosts) 3.0 else 1.0,
                captureEntryReports = true,
                // ── گیت عصبی = همان کلید «مانع» در بخش هوش عصبی ──────────────────
                // پیش از این، بک‌تست هیچ‌گاه گیت را نمی‌پرسید؛ بنابراین روشن‌وخاموش
                // کردن «مانع» نتیجه را عوض نمی‌کرد و کاربر به‌درستی می‌گفت
                // «با مانع و بدون مانع یکی بود». حالا هر ورود نامزد از شبکه می‌پرسد.
                neuralGate = trainer.entryGate(),
            )
            // پیشرفت + زمان باقی‌مانده. قبلاً فقط نوار پیشرفت بود و کاربر نمی‌دانست
            // «۳۰٪» یعنی ۱۰ ثانیه یا ۵ دقیقه؛ روی «همهٔ استراتژی‌ها» همین ابهام بزرگ بود.
            val startedAt = System.currentTimeMillis()
            _etaFa.value = ""
            runCatching {
                // «runAuto»: فایل سنگین ماه‌به‌ماه اجرا می‌شود (گزارش هر ماه جدا) و فایل سبک
                // مثل قبل یک‌جا؛ خروجی در هر دو حالت یک `Result` است.
                BacktestReplayEngine.runAuto(cut, config) { p ->
                    _progress.value = p
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (p > 0.03 && elapsed > 900) {
                        val totalMs = elapsed / p
                        val remainSec = ((totalMs - elapsed) / 1000.0).toInt().coerceAtLeast(0)
                        _etaFa.value = when {
                            remainSec >= 60 -> "≈ ${remainSec / 60} دقیقه و ${remainSec % 60} ثانیه باقی مانده"
                            else -> "≈ $remainSec ثانیه باقی مانده"
                        }
                    }
                }
            }
                .onSuccess { res ->
                    // آرشیو در همان لحظه ساخته و نوشته می‌شود: کاربر نباید برای دیدن فهرست
                    // معامله‌ها منتظر کار دیگری بماند، و اگر برنامه بسته شود آرشیو از دست نرود.
                    val built = runCatching {
                        val ds = _dataSets.value.firstOrNull { it.id == _selectedId.value }
                        BacktestTradeArchive.build(
                            result = res,
                            dataSetId = ds?.id ?: _selectedId.value.orEmpty(),
                            dataSetName = ds?.displayName ?: "—",
                            candles = cut,
                        )
                    }.getOrNull()
                    val files = built?.let { runCatching { archiveStore.save(it.archive, it) }.getOrNull() }
                    val alreadyApplied = built?.let {
                        runCatching { archiveStore.findApplied(it.archive.contentHash) }.getOrNull()
                    }
                    withContext(Dispatchers.Main) {
                        _result.value = res
                        _archive.value = built?.archive
                        _archiveFiles.value = files
                        _appliedEntry.value = alreadyApplied
                        _resultCursor.value = cut.size
                        _progress.value = 1.0
                        _etaFa.value = ""
                        _statusFa.value = buildString {
                            append("✅ بک‌تست تمام شد: ${res.trades.size} معامله در ${res.steps} کندل")
                            val stats = res.totals
                            if (stats != null) {
                                append(" · برد ${stats.winRatePct}% · خالص ${fmtUsd(stats.netPnlUsd)}")
                            }
                            // ── اثر «مانع» باید دیده شود، نه فرض شود ──────────────────
                            // پیش از این، روشن‌بودن گیت هیچ عددی در نتیجه نداشت و کاربر
                            // به‌درستی می‌گفت «با مانع و بدون مانع یکی بود». حالا هم
                            // تعداد ورودهای مسدودشده می‌آید و هم دلیلش.
                            if (res.neuralBlockedEntries > 0) {
                                append(" · 🛑 مانع عصبی ${res.neuralBlockedEntries} ورود را گرفت")
                                res.neuralBlockReasons.entries
                                    .maxByOrNull { it.value }
                                    ?.let { append(" (بیشترین دلیل: ${it.key} ×${it.value})") }
                            }
                            append(" (${res.elapsedMs} میلی‌ثانیه)")
                        }
                        // خلاصهٔ همین اجرا در نشست ذخیره می‌شود تا بعد از رفتن و برگشتن،
                        // «چه فایلی با چه تنظیماتی چه نتیجه‌ای داد» از دست نرود.
                        val headLines = buildList {
                            add(_statusFa.value.substringAfter(": ").replace("\n", " "))
                            addAll(res.periodSummariesFa)
                        }
                        _restoredLinesFa.value = headLines.take(64)
                        _restoredAtMs.value = System.currentTimeMillis()
                        persistSession(debounceMs = 0)
                    }
                }
                .onFailure { err ->
                    withContext(Dispatchers.Main) {
                        _statusFa.value = ""
                        _progress.value = 0.0
                        _etaFa.value = ""
                        emit("⚠️ بک‌تست شکست خورد: ${err.message ?: "خطای نامشخص"}")
                    }
                }
            _busy.value = false
        }
    }

    /**
     * اعمال سود/زیان آرشیو روی موجودی واقعی برنامه — **دقیقاً یک‌بار**.
     *
     * سه نگهبان پشت سر هم:
     * ۱. آرشیو باید وجود داشته باشد و حداقل یک معاملهٔ بسته‌شده داشته باشد.
     * ۲. شناسهٔ محتوایی نباید قبلاً در دفتر باشد (اگر بود، فقط پیام و سطر دفتر برمی‌گردد).
     * ۳. تغییر موجودی از مسیر رسمی `SettingsStore.adjustBalance` انجام می‌شود تا سقف ضرر
     *    روزانه و «سرمایهٔ آغاز روز» همان‌طور که در جای دیگر محاسبه می‌شود، حفظ شود.
     *
     * @param day این‌جا همیشه «کل آرشیو» اعمال می‌شود؛ محدودکردن به یک روز از پنل انجام
     *   می‌شود (با `day = null`)، چون سود یک روز به‌تنهایی واقعیت حساب را نشان نمی‌دهد.
     */
    fun applyArchiveToBalance() {
        if (_busy.value) return
        val archive = _archive.value ?: run {
            emit("اول بک‌تست را اجرا کنید تا آرشیو ساخته شود.")
            return
        }
        if (archive.metrics.trades <= 0) {
            emit("این آرشیو معاملهٔ بسته‌شده ندارد؛ چیزی برای اعمال نیست.")
            return
        }
        if (!archive.metrics.netUsd.isFinite() || kotlin.math.abs(archive.metrics.netUsd) < 0.01) {
            emit("سود خالص این آرشیو صفر است؛ اعمال لازم نیست.")
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val existing = runCatching { archiveStore.findApplied(archive.contentHash) }.getOrNull()
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    _appliedEntry.value = existing
                    _busy.value = false
                    emit(
                        "⛔️ این آرشیو قبلاً در ${BacktestTradeArchive.dayOf(existing.appliedAt)} روی حساب " +
                            "اعمال شده است (${fmtUsd(existing.netUsd)}). دوباره اعمال نمی‌شود.",
                    )
                }
                return@launch
            }
            val settings = container.settingsStore
            val before = runCatching { settings.read().accountBalance }.getOrDefault(0.0)
            val ok = runCatching { settings.adjustBalance(archive.metrics.netUsd) }.isSuccess
            val after = runCatching { settings.read().accountBalance }.getOrDefault(before)
            if (!ok) {
                withContext(Dispatchers.Main) {
                    _busy.value = false
                    emit("⚠️ اعمال سود انجام نشد؛ موجودی تغییر نکرد.")
                }
                return@launch
            }
            val entry = BacktestArchiveStore.AppliedEntry(
                contentHash = archive.contentHash,
                fileNameBase = archive.suggestedFileNameBase,
                symbol = archive.symbol,
                intervalLabel = archive.intervalLabel,
                trades = archive.metrics.trades,
                netUsd = archive.metrics.netUsd,
                balanceBefore = before,
                balanceAfter = after,
                appliedAt = System.currentTimeMillis(),
            )
            val recorded = runCatching { archiveStore.recordApplied(entry) }.getOrNull()
            withContext(Dispatchers.Main) {
                _busy.value = false
                if (recorded == null) {
                    // مسابقهٔ دو کلیک: یکی ثبت شد، دومی باید برگردد. موجودی را جبران می‌کنیم.
                    runCatching { settings.adjustBalance(-archive.metrics.netUsd) }
                    emit("⛔️ همین آرشیو یک لحظه پیش اعمال شد؛ تغییر دوم برگشت داده شد.")
                } else {
                    _appliedEntry.value = entry
                    emit("✅ ${fmtUsd(archive.metrics.netUsd)} روی موجودی اعمال شد · موجودی: ${fmtUsd(after)}")
                }
            }
        }
    }

    /**
     * مسیر «آرشیو به ژورنال واقعی» عمداً وجود ندارد.
     *
     * درخواست کاربر «اعمال سود» است، نه ساختن معاملهٔ کاغذی از گذشته: اگر این معامله‌ها وارد
     * ژورنال شوند، آمار واقعی یادگیری/عصبی با دادهٔ مصنوعی گذشته آلوده می‌شود. بنابراین
     * آرشیو فایل مستقل خودش را دارد و فقط موجودی حساب تعدیل می‌شود.
     */
    /**
     * اشتراک‌گذاری فایل آرشیو از Share Sheet اندروید (تلگرام/درایو/ایمیل).
     *
     * چرا لازم است؟ آرشیو داخل حافظهٔ خصوصی برنامه است و کاربر بدون این مسیر نمی‌تواند
     * فایل CSV را به کامپیوتر یا اکسل ببرد. همان FileProvider موجود استفاده می‌شود.
     */
    fun shareArchive(csv: Boolean) {
        val files = _archiveFiles.value ?: run {
            emit("اول بک‌تست را اجرا کنید تا آرشیو ساخته شود.")
            return
        }
        val file = if (csv) files.csv else files.json
        if (!file.isFile) {
            emit("فایل آرشیو پیدا نشد؛ بک‌تست را دوباره اجرا کنید.")
            return
        }
        val app = getApplication<Application>()
        val mime = if (csv) "text/csv" else "application/json"
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.updateprovider", file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Aurum Edge — آرشیو بک‌تست")
                putExtra(
                    android.content.Intent.EXTRA_TEXT,
                    "آرشیو معاملات بک‌تست Aurum Edge (${_archive.value?.symbol ?: ""} " +
                        "${_archive.value?.intervalLabel ?: ""}) — ${file.name}",
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(
                send, "ارسال/ذخیرهٔ آرشیو (${if (csv) "CSV" else "JSON"})",
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            app.startActivity(chooser)
        }.onFailure { err ->
            emit("⚠️ اشتراک‌گذاری ممکن نشد: ${err.message}")
        }
    }

    /** بازکردن آرشیو در برنامهٔ پیش‌فرض گوشی (اکسل/مرورگر). */
    fun openArchive(csv: Boolean) {
        val files = _archiveFiles.value ?: return
        val file = if (csv) files.csv else files.json
        if (!file.isFile) return
        val app = getApplication<Application>()
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.updateprovider", file,
            )
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (csv) "text/csv" else "application/json")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(view)
        }.onFailure { err ->
            emit("⚠️ برنامه‌ای برای باز کردن این فایل نبود: ${err.message}")
        }
    }

    fun archiveNoteFa(): String = "آرشیو فقط خواندنی است و ژورنال/یادگیری واقعی را تغییر نمی‌دهد."

    /** تعداد نمونه‌های ذخیره‌شده در حافظهٔ اتاق تمرین (برای نمایش). */
    fun neuralMemorySize(): Int = runCatching { trainer.memorySize() }.getOrDefault(0)

    /**
     * پاک‌کردن حافظهٔ اتاق تمرین — با تصمیم صریح کاربر.
     *
     * حالا که نمونه‌ها انباشته می‌شوند، یک دورهٔ دادهٔ بد (CSV نویزی، بازار رِنج) برای همیشه
     * در حافظه می‌ماند. این راه برگشت است. فقط فایل تمرین پاک می‌شود؛ حافظهٔ شبکهٔ اصلی
     * برنامه و ژورنال واقعی دست نمی‌خورند.
     */
    fun resetNeuralMemory() {
        val ok = runCatching { trainer.resetMemory() }.getOrDefault(false)
        if (ok) {
            _training.value = null
            emit("🧠 حافظهٔ اتاق تمرین پاک شد؛ آموزش بعدی از صفر شروع می‌شود.")
        } else {
            emit("⚠️ پاک‌کردن حافظه ناموفق بود.")
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
                    // کاربر باید ببیند «۲۰ نمونهٔ این اجرا» تنها چیزی نیست که یاد گرفته شده؛
                    // اگر حافظهٔ قبلی داشته باشیم، جمع هم گفته می‌شود.
                    val memoryFa = if (report.carriedSamples > 0) {
                        " · حافظه: ${report.carriedSamples} نمونهٔ قبلی + ${report.samplesFed} تازه = ${report.totalLearnedSamples}"
                    } else {
                        " · اولین آموزش (حافظه خالی بود)"
                    }
                    // جملهٔ قبلی «این اعداد روی معاملات واقعی اثر ندارند» بود که هم مبهم بود
                    // هم نیمی از واقعیت را پنهان می‌کرد: دانش همین‌جاست و با تیک «فعال‌سازی
                    // گیت» می‌تواند ورود واقعی را مسدود کند. حالا صریح گفته می‌شود.
                    _statusFa.value = "🧠 تمرین تمام شد: ${report.samplesFed} نمونه$memoryFa · " +
                        "دقت درون‌نمونه‌ای ${report.inSampleAccuracyPct}% · دانش ذخیره شد و با " +
                        "روشن‌بودن «فعال‌سازی گیت» روی معاملات واقعی اثر می‌گذارد"
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

    companion object {
        /** بالای این زمان، اجرا «کند» اعلام می‌شود. */
        const val SLOW_RUN_MS: Long = 90_000

        /** بالای این زمان، اجرا «سنگین» اعلام می‌شود و پیشنهاد کوچک‌کردن بازه داده می‌شود. */
        const val HEAVY_RUN_MS: Long = 300_000
    }
}

/** قالب دلاری با علامت؛ در صفحهٔ بک‌تست و پیام‌هایش مشترک است. */
internal fun fmtUsd(v: Double): String = String.format(java.util.Locale.US, "%+,.2f$", v)
