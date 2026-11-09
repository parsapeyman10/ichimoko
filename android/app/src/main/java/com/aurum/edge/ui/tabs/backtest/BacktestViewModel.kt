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
import com.aurum.edge.core.BacktestTradeArchive
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.data.BacktestArchiveStore
import com.aurum.edge.data.BacktestDataStore
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

    fun select(id: String?) {
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
                _settings.update { cur ->
                    cur.copy(symbol = set?.symbol ?: cur.symbol.ifBlank { "XAU/USD" })
                }
                // پیش‌فرض: صفحهٔ آخر داده، تا معامله‌های تازه دیده شوند.
                _cursor.value = (loaded.size / 2).coerceAtLeast(1)
                _result.value = null
                _archive.value = null
                _archiveFiles.value = null
                _appliedEntry.value = null
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
                BacktestReplayEngine.run(cut, config) { p ->
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
