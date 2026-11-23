package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AutoExitEvaluation
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.RiskCalculationResult
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class JournalStore(
    context: Context,
    private val file: File = File(context.filesDir, "user_journal_v1.json"),
    private val settingsStore: SettingsStore? = null,
) {
    private val appContext: Context = context.applicationContext
    private val isDefaultJournalFile: Boolean = file.name == "user_journal_v1.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(PaperTrade.serializer())

    init {
        PhoneStorageVaultManager.attachContext(appContext)
        // Ensure legacy auto-strategy journal files stay purged
        runCatching {
            File(context.filesDir, "paper_journal.json").delete()
            File(context.filesDir, "paper_journal.json.bak").delete()
        }
    }

    /**
     * خواندن دفترچه در دو مرحله انجام می‌شود و این عمدی است:
     *
     * ۱) فایل اصلی (`user_journal_v1.json`) همان لحظه و همگام خوانده می‌شود — چند کیلوبایت است و
     *    رابط کاربری بلافاصله معامله‌ها را می‌بیند.
     * ۲) ادغام شاردهای روزانه و بک‌آپ‌ها که صدها فایل و چند مگابایت است، روی رشتهٔ پس‌زمینه
     *    انجام می‌شود و نتیجه‌اش بعداً در همان StateFlow می‌نشیند.
     *
     * دلیل: در نسخهٔ ۳٫۱۲٫۰ همین ادغام داخل سازنده و روی رشتهٔ اصلی اجرا می‌شد و روی گوشی کاربر
     * ۲۶۵۳ میلی‌ثانیه رشتهٔ اصلی را قفل می‌کرد (گزارش STALL در `AppContainer.<init>`). حالا
     * `onCreate` هیچ I/O سنگینی ندارد.
     */
    private val _trades = MutableStateFlow(loadLocalJournal())
    val trades: StateFlow<List<PaperTrade>> = _trades.asStateFlow()

    /** وضعیت ادغام شاردهای پس‌زمینه؛ برای تست و برای شفافیت در گزارش خطا. */
    private val _shardsMerged = MutableStateFlow(false)
    val shardsMerged: StateFlow<Boolean> = _shardsMerged.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (isDefaultJournalFile) {
            runCatching {
                ioScope.launch {
                    runCatching { mergePhoneShardsInBackground() }
                }
            }
        } else {
            _shardsMerged.value = true
        }
    }

    private fun ensureSequentialTradeNumbers(list: List<PaperTrade>): List<PaperTrade> {
        if (list.isEmpty()) return list
        val ascending = list.sortedWith(compareBy<PaperTrade> { it.openedAt }.thenBy { it.id })
        // شماره‌گذاری معامله‌های واقعی نباید با ردیف‌های بک‌تست جابه‌جا شود: ۹۶۷ ردیف بک‌تست
        // می‌توانست سقف شماره را به ۹۶۷ ببرد و معاملهٔ واقعی بعدی «#۹۶۸» شود.
        var maxAssigned = ascending.filterNot { it.isBacktest }.maxOfOrNull { it.tradeNumber } ?: 0
        val numberedById = HashMap<String, Int>(ascending.size)
        if (maxAssigned <= 0) {
            ascending.forEachIndexed { idx, t ->
                numberedById[t.id] = idx + 1
            }
        } else {
            ascending.forEachIndexed { idx, t ->
                if (t.tradeNumber > 0) {
                    numberedById[t.id] = t.tradeNumber
                    if (t.tradeNumber > maxAssigned) maxAssigned = t.tradeNumber
                } else {
                    maxAssigned = maxOf(maxAssigned + 1, idx + 1)
                    numberedById[t.id] = maxAssigned
                }
            }
        }
        return list.map { t ->
            val assigned = numberedById[t.id] ?: t.tradeNumber
            // حد ضرر ممکن است در مرحلهٔ ریسک‌فری/تریل به سود منتقل شده باشد. بازنویسی آن با
            // `initialStopLoss` یعنی پاک‌شدن Preserve Mode در هر بار بارگذاری ژورنال.
            val preservedSl = if (t.note.contains(IchimokuStrategyEngine.BE_TAG_MARK) ||
                t.note.contains(IchimokuStrategyEngine.TRAIL_TAG_MARK) ||
                t.note.contains("ریسک‌فری")
            ) {
                t.stopLoss
            } else {
                t.initialStopLoss ?: t.stopLoss
            }
            val fixedSl = preservedSl
            val clampedRr = if (t.riskReward.isFinite()) t.riskReward.coerceIn(1.50, 3.00) else 2.00
            val clampedOptRr = if (t.optimalRiskReward > 0.0 && t.optimalRiskReward.isFinite()) {
                t.optimalRiskReward.coerceIn(1.50, 3.00)
            } else {
                t.optimalRiskReward
            }
            val needsFixedSlReset = t.stopLoss != fixedSl || t.initialStopLoss == null ||
                t.riskReward != clampedRr || t.optimalRiskReward != clampedOptRr
            when {
                t.tradeNumber != assigned || needsFixedSlReset -> t.copy(
                    tradeNumber = assigned,
                    stopLoss = fixedSl,
                    initialStopLoss = fixedSl,
                    riskReward = clampedRr,
                    optimalRiskReward = clampedOptRr,
                )
                else -> t
            }
        }.sortedByDescending { it.openedAt }
    }

    /** خواندن همگام و سبک فایل اصلی دفترچه (بدون شاردها). */
    @Synchronized
    private fun loadLocalJournal(): List<PaperTrade> = ensureSequentialTradeNumbers(readLocalFile())

    /**
     * ادغام پس‌زمینه‌ای شاردهای روزانه با آنچه همین حالا در حافظه است.
     *
     * اگر در این فاصله کاربر معامله‌ای ثبت/بسته کرده باشد، لیست جاری دوباره با شاردها ادغام
     * می‌شود؛ چیزی از دست نمی‌رود و لیست شاردها جای لیست زنده را نمی‌گیرد.
     */
    @Synchronized
    private fun mergePhoneShardsInBackground() {
        val shardList = runCatching {
            PhoneStorageVaultManager.loadAllTradesFromPhoneShards(appContext)
        }.getOrDefault(emptyList())
        val current = _trades.value
        if (shardList.isEmpty()) {
            _shardsMerged.value = true
            return
        }
        val merged = LinkedHashMap<String, PaperTrade>()
        for (t in current) merged[t.id] = t
        var changed = false
        for (t in shardList) {
            val existing = merged[t.id]
            if (existing == null) {
                merged[t.id] = t
                changed = true
            } else if (!t.isOpen && existing.isOpen && existing.isBacktest == t.isBacktest) {
                // شارد بسته‌شدنِ معامله را می‌داند ولی حافظه نه: نسخهٔ شارد برنده است.
                merged[t.id] = t
                changed = true
            }
        }
        if (changed) {
            _trades.value = ensureSequentialTradeNumbers(merged.values.toList())
        }
        _shardsMerged.value = true
    }

    @Synchronized
    private fun readLocalFile(): List<PaperTrade> = run {
        val candidates = listOf(file, File(file.path + ".bak"))
        var found: List<PaperTrade>? = null
        for (candidate in candidates) {
            if (!candidate.exists()) continue
            val parsed = runCatching {
                json.decodeFromString(listSerializer, candidate.readText())
            }.getOrNull()
            if (parsed != null) {
                found = parsed
                break
            }
        }
        found.orEmpty()
    }

    /**
     * انتظار برای پایان ادغام پس‌زمینهٔ شاردها (حداکثر [timeoutMs]).
     *
     * @return true اگر ادغام تمام شده باشد.
     */
    fun awaitShardMerge(timeoutMs: Long = 5_000): Boolean {
        if (_shardsMerged.value) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_shardsMerged.value) return true
            try {
                Thread.sleep(20)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return _shardsMerged.value
            }
        }
        return _shardsMerged.value
    }

    @Synchronized
    private fun persist(list: List<PaperTrade>) {
        val sorted = ensureSequentialTradeNumbers(list)
        _trades.value = sorted
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = json.encodeToString(listSerializer, sorted)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encoded)
            if (file.exists()) {
                file.copyTo(File(file.path + ".bak"), overwrite = true)
            }
            if (!tmp.renameTo(file)) {
                file.writeText(encoded)
                tmp.delete()
            }
        }
        if (isDefaultJournalFile) {
            runCatching {
                PhoneStorageVaultManager.syncTradesToShardsAndDailyBackup(appContext, sorted)
            }
        }
    }

    /**
     * آیا همین حالا یک معاملهٔ **باز** برای این استراتژی وجود دارد؟
     *
     * قاعدهٔ سیستم: از هر استراتژی در هر لحظه فقط **یک** معاملهٔ باز مجاز است تا تکلیف آن روشن شود
     * (حد سود، حد ضرر یا بستن دستی). این تابع همان وضعیت را برای UI و تست‌ها خوانا می‌کند.
     */
    fun openTradeForStrategy(strategyId: String): PaperTrade? {
        val normalized = com.aurum.edge.core.BitcoinStrategyModel.normalizeStrategyId(strategyId)
        return _trades.value.firstOrNull {
            it.isOpen && !it.isBacktest &&
                com.aurum.edge.core.BitcoinStrategyModel.normalizeStrategyId(it.strategyId) == normalized
        }
    }

    /**
     * آیا همین حالا معاملهٔ بازی روی این **نماد** وجود دارد؟ (از هر استراتژی که باشد)
     *
     * قاعدهٔ دوم کاربر: «از یک نماد وقتی معاملهٔ باز دارم، معاملهٔ تازه روی همان نماد باز نشود.»
     * پیش از این فقط «یک معامله به ازای هر استراتژی» کنترل می‌شد، پس سه استراتژی می‌توانستند
     * هم‌زمان سه پوزیشن روی یک نماد باز کنند. معامله‌های بک‌تست (شبیه‌سازی) شمرده نمی‌شوند.
     */
    fun openTradeForSymbol(symbolInput: String): PaperTrade? {
        val normalized = com.aurum.edge.core.V1Universe.normalize(symbolInput)
        return _trades.value.firstOrNull {
            it.isOpen && !it.isBacktest && com.aurum.edge.core.V1Universe.normalize(it.symbol) == normalized
        }
    }

    /**
     * دلیل فارسیِ بسته‌بودن مسیر ورود؛ `null` یعنی مسیر باز است.
     *
     * این تنها مرجع قاعدهٔ «یک معامله در هر لحظه» است و سه لایه دارد:
     * ۱) همان **نماد** معاملهٔ باز دارد ⇒ هیچ استراتژی روی آن نماد وارد نمی‌شود.
     * ۲) همان **استراتژی** جای دیگری معاملهٔ باز دارد ⇒ روی هیچ نمادی وارد نمی‌شود.
     * ۳) هر دو به `ignoreTradeId` حساس‌اند تا ویرایش/بستن همان معامله خودش را بلاک نکند.
     *
     * چون در خودِ ژورنال است، هیچ مسیری (رادار، ورود دستی، هوش عصبی، بازیابی از دیسک) نمی‌تواند
     * دورش بزند؛ حتی اگر لایهٔ بالاتر اجازه بدهد.
     */
    fun entryBlockReasonFa(
        symbolInput: String?,
        strategyId: String,
        ignoreTradeId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (!symbolInput.isNullOrBlank()) {
            openTradeForSymbol(symbolInput)?.takeIf { it.id != ignoreTradeId }?.let { blocking ->
                return "⛔ روی ${blocking.symbol} معاملهٔ باز ${blocking.displayTradeNumberBadgeFa} دارید؛ " +
                    "تا تکلیفش روشن شود روی همین نماد معاملهٔ تازه باز نمی‌شود"
            }
        }
        openTradeForStrategy(strategyId)?.takeIf { it.id != ignoreTradeId }?.let { blocking ->
            return "⛔ تا تکلیف ${blocking.displayTradeNumberBadgeFa} (${blocking.symbol}) روشن شود، " +
                "استراتژی «${blocking.strategyTitleFa}» معاملهٔ تازه باز نمی‌کند"
        }
        // ── قاعدهٔ چهارم: دورهٔ خنک‌شدن پس از بسته شدن ─────────────────────────────
        // کاربر: «معامله رو بستی و دقیقاً دوباره ادامهٔ همون روند رو باز کردی؛ این اشتباهه.»
        // یک معاملهٔ بسته‌شده یعنی ساختار تست شد و جواب نداد؛ ورود فوری بعدی معمولاً همان
        // روندِ شکست‌خورده را دوباره می‌خرد. جزئیات در `ReentryGuard`.
        if (!symbolInput.isNullOrBlank()) {
            reentryBlockReasonFa(symbolInput, strategyId, now)?.let { return it }
        }
        return null
    }

    /**
     * دلیل فارسی مسدود بودن ورود تازه به‌خاطر دورهٔ خنک‌شدن بعد از بسته شدن معامله؛ `null` = آزاد.
     *
     * قاعده فقط به (نماد، استراتژی) حساس است؛ همین استراتژی روی نماد دیگر یا استراتژی دیگر روی
     * همین نماد آزاد است.
     */
    fun reentryBlockReasonFa(
        symbolInput: String,
        strategyId: String,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val symbolKey = com.aurum.edge.core.V1Universe.normalize(symbolInput)
        val strategyKey = com.aurum.edge.core.BitcoinStrategyModel.normalizeStrategyId(strategyId)
        val list = _trades.value
        val lastClosed = list.asSequence()
            .filter { !it.isOpen && !it.isBacktest }
            .filter { com.aurum.edge.core.V1Universe.normalize(it.symbol) == symbolKey }
            .filter { com.aurum.edge.core.BitcoinStrategyModel.normalizeStrategyId(it.strategyId) == strategyKey }
            .maxByOrNull { it.closedAt ?: 0L }
            ?: return null
        val losses = com.aurum.edge.core.ReentryGuard.consecutiveLosses(list, symbolKey, strategyKey)
        val decision = com.aurum.edge.core.ReentryGuard.evaluate(lastClosed, losses, now)
        return decision.reasonFa.takeIf { decision.blocked }
    }

    /** خلاصهٔ فارسی پنجرهٔ خنک‌شدن برای نمایش در کارت استراتژی؛ `null` یعنی آزاد. */
    fun reentryStatusFa(symbolInput: String, strategyId: String): String? =
        reentryBlockReasonFa(symbolInput, strategyId)

    /**
     * نگه‌داشتن فقط **یک** معاملهٔ باز به ازای هر نماد و هر استراتژی (قدیمی‌ترین برنده است).
     *
     * برای مسیرهایی که فهرست معامله‌ها را یک‌جا وارد می‌کنند (بازیابی پشتیبان، ورود CSV) و
     * نمی‌توانند تک‌تک از گارد ورود رد شوند. معامله‌های بک‌تست و بسته‌ها دست نمی‌خورند.
     */
    private fun singleOpenPerSymbolAndStrategy(trades: List<PaperTrade>): List<PaperTrade> {
        val openBySymbol = HashSet<String>()
        val openByStrategy = HashSet<String>()
        // قدیمی‌ترین بازها اول بررسی می‌شوند تا همان‌ها بمانند و تازه‌ترها رد شوند.
        val openIds = trades
            .filter { it.isOpen && !it.isBacktest }
            .sortedBy { it.openedAt }
            .map { it.id }
            .toSet()
        return trades.filter { trade ->
            if (!trade.isOpen || trade.isBacktest) return@filter true
            val symbolKey = com.aurum.edge.core.V1Universe.normalize(trade.symbol)
            val strategyKey = com.aurum.edge.core.BitcoinStrategyModel.normalizeStrategyId(trade.strategyId)
            if (symbolKey !in openBySymbol && strategyKey !in openByStrategy) {
                openBySymbol += symbolKey
                openByStrategy += strategyKey
                true
            } else {
                trade.id !in openIds
            }
        }
    }

    /**
     * بازسازی اسنپ‌شات سنجش تکنیکال برای معاملاتی که پیش از افزوده شدن این قابلیت ثبت شده‌اند.
     *
     * قاعدهٔ صحت داده: فقط از کندل‌های **بسته‌شدهٔ پیش از لحظهٔ ورود** استفاده می‌شود، پس عددِ
     * بازسازی‌شده همان چیزی است که در لحظهٔ ورود دیده می‌شد (بدون نگاه به آینده). اگر کندلی در
     * دسترس نباشد، همان «ثبت نشد» می‌ماند و هیچ عدد ساختگی ساخته نمی‌شود.
     *
     * @return تعداد معاملاتی که اسنپ‌شاتشان کامل شد.
     */
    @Synchronized
    fun backfillGaugeSnapshots(
        candlesProvider: (String, com.aurum.edge.domain.Interval) -> List<Candle> = { symbol, interval ->
            LiveQuoteFetcher.peekCachedCandles(symbol, interval)
        },
    ): Int {
        val current = _trades.value
        if (current.isEmpty()) return 0
        var changed = 0
        val updated = current.map { trade ->
            if (trade.tech1hBuy >= 0 && trade.tech4hBuy >= 0) return@map trade
            val oneHour = entryInstantRating(trade, com.aurum.edge.domain.Interval.H1, candlesProvider)
            val fourHour = entryInstantRating(trade, com.aurum.edge.domain.Interval.H4, candlesProvider)
            if (oneHour == null && fourHour == null) return@map trade
            val candidate = trade.copy(
                tech1hBuy = oneHour?.buy ?: trade.tech1hBuy,
                tech1hNeutral = oneHour?.neutral ?: trade.tech1hNeutral,
                tech1hSell = oneHour?.sell ?: trade.tech1hSell,
                tech4hBuy = fourHour?.buy ?: trade.tech4hBuy,
                tech4hNeutral = fourHour?.neutral ?: trade.tech4hNeutral,
                tech4hSell = fourHour?.sell ?: trade.tech4hSell,
                techSnapshotRebuilt = true,
            )
            // اگر اجرای دوباره هیچ عددی را عوض نکند، نوشتن روی دیسک لازم نیست.
            if (candidate == trade) return@map trade
            changed++
            candidate
        }
        if (changed > 0) persist(updated)
        return changed
    }

    /** ارزیابی سنجش تکنیکال در «لحظهٔ ورود»؛ فقط کندل‌هایی که پیش از ورود بسته شده‌اند. */
    private fun entryInstantRating(
        trade: PaperTrade,
        interval: com.aurum.edge.domain.Interval,
        candlesProvider: (String, com.aurum.edge.domain.Interval) -> List<Candle>,
    ): com.aurum.edge.core.TechnicalRating? {
        val all = runCatching { candlesProvider(trade.symbol, interval) }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val cutoff = trade.openedAt
        val closedBeforeEntry = all
            .asSequence()
            .filter { it.closed && it.time + interval.millis <= cutoff }
            .sortedBy { it.time }
            .toList()
        if (closedBeforeEntry.size < com.aurum.edge.core.TechnicalRatingEngine.MIN_BARS) return null
        return com.aurum.edge.core.TechnicalRatingEngine.evaluate(closedBeforeEntry.takeLast(150))
    }

    /** پیام فارسی دلیل رد شدن معاملهٔ تازه؛ `null` یعنی مسیر باز است. */
    fun openTradeBlockedByFa(strategyId: String): String? = entryBlockReasonFa(null, strategyId)

    /** همان دلیل، این‌بار با دانستن نماد (قاعدهٔ «یک معامله روی هر نماد»). */
    fun openTradeBlockedByFa(symbolInput: String?, strategyId: String): String? =
        entryBlockReasonFa(symbolInput, strategyId)

    @Synchronized
    fun openTrade(
        calc: RiskCalculationResult,
        note: String = "",
        now: Long = System.currentTimeMillis(),
        entryInterval: com.aurum.edge.domain.Interval = com.aurum.edge.domain.Interval.M15,
        strategyId: String = com.aurum.edge.core.BitcoinStrategyModel.BASE_ICHIMOKU.id,
        strategyTitleFa: String = com.aurum.edge.core.BitcoinStrategyModel.BASE_ICHIMOKU.titleFa,
        optimalRiskReward: Double = 0.0,
        rrBreakdownFa: String = "",
        preEntryCandles: List<Candle> = emptyList(),
        /** کندل‌های ۱ ساعته و ۴ ساعتهٔ لحظهٔ ورود برای سنجش تکنیکال؛ خالی یعنی از کش خوانده شود. */
        gaugeCandles1h: List<Candle> = emptyList(),
        gaugeCandles4h: List<Candle> = emptyList(),
        /** حالت بازار در لحظهٔ ورود (از گزارش استراتژی). خالی = فقط سشن ثبت می‌شود. */
        stateInputs: com.aurum.edge.core.NeuralStateInputs? = null,
        enforceProfitRisk: Boolean = false,
    ): PaperTrade? {
        // ── قاعدهٔ «یک معاملهٔ باز برای هر استراتژی» ─────────────────────────────
        // تا تکلیف معاملهٔ باز روشن نشود، همین استراتژی معاملهٔ تازه نمی‌گیرد. این گارد در خودِ
        // ژورنال است تا هیچ مسیری (خودکار، دستی، رادار ۱۵ دقیقه یا کارت استراتژی) نتواند دورش بزند.
        // `now` باید به گارد برسد؛ وگرنه یک ورود با زمان مشخص (تست/بازپخش) با ساعت دیوار سنجیده
        // می‌شود و دورهٔ خنک‌شدن هرگز تمام‌شده دیده نمی‌شود.
        if (entryBlockReasonFa(calc.symbol, strategyId, now = now) != null) return null
        if (enforceProfitRisk && com.aurum.edge.core.ProfitRiskPolicy.planErrorFa(
                calc, _trades.value, settingsStore?.read()?.accountBalance ?: calc.accountBalance,
            ) != null) return null
        // This synchronous API can be called from a UI action. Use memory-only candles here;
        // parsing the on-device CandleVault is disk I/O and used to freeze the main thread on entry.
        val effectiveCandles = preEntryCandles.ifEmpty {
            LiveQuoteFetcher.peekCachedCandles(calc.symbol, entryInterval)
        }
        val activeModel = com.aurum.edge.core.BitcoinStrategyModel.fromId(strategyId)
        val pre3Context = com.aurum.edge.core.IchimokuStrategyEngine.buildPreEntry3CandleContext(
            candles = effectiveCandles,
            action = calc.action,
            activeModel = activeModel,
        )
        val nextTradeNum = (_trades.value.maxOfOrNull { it.tradeNumber } ?: _trades.value.size) + 1
        val rawTrade = calc.toPaperTrade(
            note = note,
            openedAt = now,
            entryInterval = entryInterval,
            strategyId = strategyId,
            strategyTitleFa = strategyTitleFa,
            optimalRiskReward = optimalRiskReward,
            rrBreakdownFa = rrBreakdownFa,
            stateInputs = stateInputs ?: com.aurum.edge.core.NeuralStateInputs(entryTimeMs = now),
        )
        // ── سنجش تکنیکال در لحظهٔ ورود؛ گیت شواهد نیز می‌تواند از آن استفاده کند ─────────
        // از کش کندل‌های ۱ ساعته و ۴ ساعتهٔ همین نماد خوانده می‌شود. اگر کندلی در کش نبود،
        // عدد `-1` ثبت می‌شود (نامعلوم) — هیچ عدد ساختگی ساخته نمی‌شود.
        val rating1h = com.aurum.edge.core.TechnicalRatingEngine.evaluate(
            gaugeCandles1h.ifEmpty {
                com.aurum.edge.data.LiveQuoteFetcher.peekCachedCandles(calc.symbol, com.aurum.edge.domain.Interval.H1)
            }.filter { it.closed && it.time + 3_600_000L <= now },
        )
        val rating4h = com.aurum.edge.core.TechnicalRatingEngine.evaluate(
            gaugeCandles4h.ifEmpty {
                com.aurum.edge.data.LiveQuoteFetcher.peekCachedCandles(calc.symbol, com.aurum.edge.domain.Interval.H4)
            }.filter { it.closed && it.time + 14_400_000L <= now },
        )
        val trade = rawTrade.copy(
            tradeNumber = nextTradeNum,
            preEntry3CandleCategoryCode = pre3Context.categoryCode,
            preEntry3CandleCategoryFa = pre3Context.categoryBadgeFa,
            preEntry3CandleSummaryFa = pre3Context.summaryFa,
            preEntry3Candles = pre3Context.candles,
            tech1hBuy = rating1h?.buy ?: -1,
            tech1hNeutral = rating1h?.neutral ?: -1,
            tech1hSell = rating1h?.sell ?: -1,
            tech4hBuy = rating4h?.buy ?: -1,
            tech4hNeutral = rating4h?.neutral ?: -1,
            tech4hSell = rating4h?.sell ?: -1,
        )
        persist( listOf(trade) + _trades.value )
        // OPEN snapshot generation is scheduled by the ViewModel after this synchronous journal
        // write returns. Do not render charts or touch MediaStore from this call path.
        val cfg = settingsStore?.read()
        com.aurum.edge.notify.TradeEventAlarmManager.triggerEntryAlarm(
            context = appContext,
            trade = trade,
            enabled = cfg?.notifyOnSignal != false,
            customSoundUri = cfg?.alertSoundUri.orEmpty(),
        )
        return trade
    }

    @Synchronized
    fun addTrade(trade: PaperTrade) {
        // قاعدهٔ یک-معامله-در-هر-لحظه از مسیر واردکردن/بازیابی هم دور زدنی نیست: معاملهٔ بازِ
        // تازه‌ای که نماد یا استراتژی‌اش همین حالا اشغال است، پذیرفته نمی‌شود. به‌روزرسانی خودِ
        // همان معامله (با همان شناسه) آزاد است و معامله‌های بک‌تست از این قاعده مستثنا هستند.
        if (trade.isOpen && !trade.isBacktest &&
            entryBlockReasonFa(
                trade.symbol,
                trade.strategyId,
                ignoreTradeId = trade.id,
                now = trade.openedAt,
            ) != null
        ) {
            return
        }
        val previous = _trades.value.firstOrNull { it.id == trade.id }
        persist(listOf(trade) + _trades.value.filterNot { it.id == trade.id })
        applyBalanceDelta(previous, trade)
    }

    /**
     * قفل حد ضرر ساختاری ثابت (غیرپله‌ای) و حفظ R:R اولیه معامله (`stopLoss = initialStopLoss ?: stopLoss`).
     */
    @Synchronized
    fun updateTradeStopLoss(
        tradeId: String,
        newStopLoss: Double,
        stageNote: String,
        newTakeProfit: Double? = null,
        allowRatchet: Boolean = false,
    ): PaperTrade? {
        if (!newStopLoss.isFinite() || newStopLoss <= 0.0) return null
        val target = _trades.value.firstOrNull { it.id == tradeId && it.isOpen } ?: return null
        val updatedNote = if (stageNote.isBlank() || target.note.contains(stageNote)) {
            target.note
        } else if (target.note.isBlank()) {
            stageNote
        } else {
            "${target.note} · $stageNote"
        }

        // مسیر پیش‌فرض: قفل حد ضرر ساختاری اولیه (بدون تغییر R:R).
        // مسیر `allowRatchet` (Preserve Mode): حد ضرر فقط در جهت سود جابه‌جا می‌شود و
        // حد سود/ R:R اولیه دست‌نخورده می‌ماند.
        if (allowRatchet) {
            // قانون قفل: حد ضرر فقط «بهتر» می‌شود و هرگز عقب برنمی‌گردد.
            val current = target.stopLoss
            val ratcheted = when (target.action) {
                SignalAction.BUY -> newStopLoss > current
                SignalAction.SELL -> newStopLoss < current
                else -> false
            }
            if (!ratcheted) return null
            val modified = target.copy(
                stopLoss = newStopLoss,
                initialStopLoss = target.initialStopLoss ?: target.stopLoss,
                takeProfit = newTakeProfit ?: target.takeProfit,
                note = updatedNote,
            )
            persist(_trades.value.map { if (it.id == tradeId) modified else it })
            return modified
        }

        val fixedInitSl = target.initialStopLoss ?: target.stopLoss
        val modified = target.copy(
            stopLoss = fixedInitSl,
            takeProfit = target.takeProfit,
            riskReward = target.riskReward,
            initialStopLoss = fixedInitSl,
            note = updatedNote,
        )
        persist(_trades.value.map { if (it.id == tradeId) modified else it })
        return modified
    }

    /**
     * اصلاح پلن یک پوزیشن باز با موتور `TradePlanGuard` (حد ضرر و حد سود).
     *
     * برای پوزیشن‌هایی است که با اعداد نسخه‌های قدیمی باز شده‌اند و پلن‌شان بیرون دالان نوسان
     * تایم‌فریم است (نمونه: `۱R = 5.66%` و `TP = 14.13%` روی `BNB/USDT 5m`). جهت معامله و
     * اندازهٔ پوزیشن دست‌نخورده می‌ماند؛ فقط فاصلهٔ حد ضرر و حد سود به دالان همان تایم‌فریم
     * منتقل می‌شود و R:R حفظ می‌گردد.
     */
    @Synchronized
    fun repairOpenTradePlan(
        tradeId: String,
        newStopLoss: Double,
        newTakeProfit: Double,
        reasonFa: String,
    ): PaperTrade? {
        if (!newStopLoss.isFinite() || newStopLoss <= 0.0) return null
        if (!newTakeProfit.isFinite() || newTakeProfit <= 0.0) return null
        val target = _trades.value.firstOrNull { it.id == tradeId && it.isOpen } ?: return null
        val stopDistance = abs(target.entry - newStopLoss)
        val targetDistance = abs(newTakeProfit - target.entry)
        if (stopDistance <= 1e-9 || targetDistance <= 1e-9) return null
        val directionOk = when (target.action) {
            SignalAction.BUY -> newStopLoss < target.entry && newTakeProfit > target.entry
            SignalAction.SELL -> newStopLoss > target.entry && newTakeProfit < target.entry
            else -> false
        }
        if (!directionOk) return null
        val rr = targetDistance / stopDistance
        val updatedNote = when {
            reasonFa.isBlank() || target.note.contains(reasonFa) -> target.note
            target.note.isBlank() -> reasonFa
            else -> "${target.note} · $reasonFa"
        }
        val repaired = target.copy(
            stopLoss = newStopLoss,
            takeProfit = newTakeProfit,
            riskReward = rr,
            initialStopLoss = newStopLoss,
            optimalRiskReward = rr.coerceIn(1.5, 3.0),
            note = updatedNote,
        )
        persist(_trades.value.map { if (it.id == tradeId) repaired else it })
        return repaired
    }

    /**
     * Evaluates an incoming live market tick against all open trades for [symbolInput].
     * Closes the trade immediately when TP (or 3R ceiling) or SL is reached.
     */
    @Synchronized
    fun onLiveTick(symbolInput: String, tick: PriceTick): List<AutoExitEvaluation> {
        val symbol = V1Universe.normalize(symbolInput)
        val current = _trades.value
        if (current.none { it.isOpen && V1Universe.normalize(it.symbol) == symbol }) {
            return emptyList()
        }
        val triggered = mutableListOf<AutoExitEvaluation>()
        val closedTradesForAlarm = mutableListOf<Pair<PaperTrade, Boolean>>()
        val updated = current.map { trade ->
            if (!trade.isOpen || V1Universe.normalize(trade.symbol) != symbol) {
                trade
            } else {
                val hit = RiskAndCalendarEngine.evaluateAutoExit(trade, tick)
                if (hit != null) {
                    triggered += hit
                    val isProfitableClose = hit.kind == com.aurum.edge.core.AutoExitKind.TAKE_PROFIT || hit.netPnlUsd > 0.0
                    val effectiveReason = if (hit.kind == com.aurum.edge.core.AutoExitKind.STOP_LOSS && hit.netPnlUsd > 0.0) {
                        "🛡️ بسته شدن با سود در حد ضرر متحرک هوشمند (${hit.kind.reasonFa})"
                    } else {
                        hit.kind.reasonFa
                    }
                    val closedTrade = trade.copy(
                        closedAt = hit.closedAt,
                        exitPrice = hit.exitPrice,
                        exitReason = effectiveReason,
                        pnlUsd = hit.netPnlUsd,
                    )
                    val explained = explainClosedTrade(closedTrade)
                    closedTradesForAlarm += explained to isProfitableClose
                    explained
                } else {
                    trade
                }
            }
        }
        if (triggered.isNotEmpty()) {
            persist(updated)
            val totalDelta = triggered.sumOf { it.netPnlUsd }
            settingsStore?.adjustBalance(totalDelta)
            val cfg = settingsStore?.read()
            val alarmEnabled = cfg?.notifyOnSignal != false
            val soundUri = cfg?.alertSoundUri.orEmpty()
            closedTradesForAlarm.forEach { (closedTrade, isProfit) ->
                if (isProfit) {
                    com.aurum.edge.notify.TradeEventAlarmManager.triggerTakeProfitAlarm(
                        context = appContext,
                        trade = closedTrade,
                        enabled = alarmEnabled,
                        customSoundUri = soundUri,
                    )
                } else {
                    com.aurum.edge.notify.TradeEventAlarmManager.triggerStopLossAlarm(
                        context = appContext,
                        trade = closedTrade,
                        enabled = alarmEnabled,
                        customSoundUri = soundUri,
                    )
                }
            }
        }
        return triggered
    }

    /**
     * Chronologically evaluates historical OHLC candles (fetched after an offline gap)
     * against all open trades for [symbolInput]. Any trade whose TP or SL was hit during
     * the offline hours is closed at the exact candle timestamp and settled in the journal.
     */
    @Synchronized
    fun onBackfillCandles(symbolInput: String, candles: List<Candle>): List<AutoExitEvaluation> {
        val symbol = V1Universe.normalize(symbolInput)
        val current = _trades.value
        if (candles.isEmpty() || current.none { it.isOpen && V1Universe.normalize(it.symbol) == symbol }) {
            return emptyList()
        }
        val triggered = mutableListOf<AutoExitEvaluation>()
        val closedTradesForAlarm = mutableListOf<Pair<PaperTrade, Boolean>>()
        val updated = current.map { trade ->
            if (!trade.isOpen || V1Universe.normalize(trade.symbol) != symbol) {
                trade
            } else {
                val hit = RiskAndCalendarEngine.evaluateCandleWindow(trade, candles)
                if (hit != null) {
                    triggered += hit
                    val isProfit = hit.kind == com.aurum.edge.core.AutoExitKind.TAKE_PROFIT || hit.netPnlUsd > 0.0
                    val closedTrade = trade.copy(
                        closedAt = hit.closedAt,
                        exitPrice = hit.exitPrice,
                        exitReason = "${hit.kind.reasonFa} · بازیابی ساعات قطعی",
                        pnlUsd = hit.netPnlUsd,
                    )
                    val explained = explainClosedTrade(closedTrade, candles)
                    closedTradesForAlarm += explained to isProfit
                    explained
                } else {
                    trade
                }
            }
        }
        if (triggered.isNotEmpty()) {
            persist(updated)
            val totalDelta = triggered.sumOf { it.netPnlUsd }
            settingsStore?.adjustBalance(totalDelta)
            val cfg = settingsStore?.read()
            val alarmEnabled = cfg?.notifyOnSignal != false
            val soundUri = cfg?.alertSoundUri.orEmpty()
            closedTradesForAlarm.forEach { (closedTrade, isProfit) ->
                if (isProfit) {
                    com.aurum.edge.notify.TradeEventAlarmManager.triggerTakeProfitAlarm(
                        context = appContext,
                        trade = closedTrade,
                        enabled = alarmEnabled,
                        customSoundUri = soundUri,
                    )
                } else {
                    com.aurum.edge.notify.TradeEventAlarmManager.triggerStopLossAlarm(
                        context = appContext,
                        trade = closedTrade,
                        enabled = alarmEnabled,
                        customSoundUri = soundUri,
                    )
                }
            }
        }
        return triggered
    }

    private fun explainClosedTrade(trade: PaperTrade, candles: List<Candle> = LiveQuoteFetcher.peekCachedCandles(trade.symbol, trade.interval)): PaperTrade = runCatching {
        val result = com.aurum.edge.core.TradeOutcomeExplainer.explain(trade, candles)
        trade.copy(outcomeReasonFa = result.textFa(), outcomeTags = result.tags, mfeR = result.mfeR, maeR = result.maeR)
    }.getOrDefault(trade)

    @Synchronized
    fun closeTradeAtPrice(
        tradeId: String,
        exitPrice: Double,
        reason: String = "بستن با قیمت لحظه‌ای",
        now: Long = System.currentTimeMillis(),
    ): PaperTrade? {
        if (!exitPrice.isFinite() || exitPrice <= 0.0) return null
        val target = _trades.value.firstOrNull { it.id == tradeId && it.isOpen } ?: return null
        val (_, netPnl) = RiskAndCalendarEngine.computeExitPnlUsd(target, exitPrice)
        val roundedNet = round(netPnl * 100.0) / 100.0
        var closed = target.copy(
            closedAt = max(now, target.openedAt + 1L),
            exitPrice = exitPrice,
            exitReason = reason,
            pnlUsd = roundedNet,
        )
        // دلیل برد/باخت همین حالا نوشته می‌شود تا در ژورنال و آمار هوش عصبی بمانَد.
        // کندل‌ها فقط از حافظه خوانده می‌شوند (بدون شبکه و بدون دیسک) تا بستن معامله کند نشود.
        closed = runCatching {
            val candles = com.aurum.edge.data.LiveQuoteFetcher
                .peekCachedCandles(closed.symbol, closed.interval)
            val explanation = com.aurum.edge.core.TradeOutcomeExplainer.explain(closed, candles)
            closed.copy(
                outcomeReasonFa = explanation.textFa(),
                outcomeTags = explanation.tags,
                mfeR = explanation.mfeR,
                maeR = explanation.maeR,
            )
        }.getOrDefault(closed)
        persist(_trades.value.map { if (it.id == tradeId) closed else it })
        settingsStore?.adjustBalance(roundedNet)
        val cfg = settingsStore?.read()
        val alarmEnabled = cfg?.notifyOnSignal != false
        val soundUri = cfg?.alertSoundUri.orEmpty()
        if (roundedNet >= 0.0) {
            com.aurum.edge.notify.TradeEventAlarmManager.triggerTakeProfitAlarm(
                context = appContext,
                trade = closed,
                enabled = alarmEnabled,
                customSoundUri = soundUri,
            )
        } else {
            com.aurum.edge.notify.TradeEventAlarmManager.triggerStopLossAlarm(
                context = appContext,
                trade = closed,
                enabled = alarmEnabled,
                customSoundUri = soundUri,
            )
        }
        return closed
    }

    @Synchronized
    fun deleteTrade(tradeId: String) {
        val target = _trades.value.firstOrNull { it.id == tradeId }
        persist(_trades.value.filterNot { it.id == tradeId })
        applyBalanceDelta(target, null)
    }

    /**
     * اثر مالیِ اضافه/حذف/ویرایش یک معاملهٔ **بسته‌شده** را روی موجودی حساب اعمال می‌کند.
     *
     * پیش از این، `deleteTrade` معامله را پاک می‌کرد ولی سود/زیانش در موجودی می‌ماند و
     * `addTrade` هم معاملهٔ بستهٔ دستی را به موجودی اضافه نمی‌کرد؛ نتیجه‌اش همان اختلافی بود
     * که کاربر در صفحهٔ نخست می‌دید (موجودی ≠ سرمایهٔ پایه + سود/زیان تحققیافته).
     */
    private fun applyBalanceDelta(previous: PaperTrade?, next: PaperTrade?) {
        // معاملهٔ بک‌تست سود/زیانش در ژورنال دیده می‌شود ولی **هرگز** به موجودی اضافه نمی‌شود؛
        // درخواست صریح کاربر: «سود هر معامله برود در ژورنال بنشیند، ولی پولش وارد پولم نشود.»
        if (previous?.isBacktest == true || next?.isBacktest == true) return
        val previousPnl = previous?.takeIf { !it.isOpen }?.pnlUsd ?: 0.0
        val nextPnl = next?.takeIf { !it.isOpen }?.pnlUsd ?: 0.0
        val delta = nextPnl - previousPnl
        if (delta.isFinite() && abs(delta) >= 0.01) settingsStore?.adjustBalance(delta)
    }

    @Synchronized
    fun importTrades(
        imported: List<PaperTrade>,
        replaceExisting: Boolean = false,
    ): Int {
        if (imported.isEmpty() && !replaceExisting) return 0
        val cleanImported = imported.distinctBy { it.id }
        val finalList = if (replaceExisting) {
            cleanImported
        } else {
            val importedById = cleanImported.associateBy { it.id }
            val mergedExisting = _trades.value.map { existing ->
                importedById[existing.id] ?: existing
            }
            val existingIds = _trades.value.map { it.id }.toSet()
            // قاعدهٔ «یک معامله در هر لحظه» روی ورودی‌های تازه هم اعمال می‌شود: از فایل پشتیبان
            // هم نمی‌شود دو پوزیشن باز روی یک نماد یا دو پوزیشن باز از یک استراتژی ساخت.
            // قدیمی‌ترین معاملهٔ باز می‌مانَد و بقیه (که هنوز در ژورنال نبودند) رد می‌شوند.
            val brandNew = singleOpenPerSymbolAndStrategy(cleanImported.filterNot { it.id in existingIds })
            brandNew + mergedExisting
        }
        persist(finalList)
        return cleanImported.size
    }

    @Synchronized
    fun clear() {
        runCatching {
            if (file.exists()) file.delete()
            val backup = File(file.path + ".bak")
            if (backup.exists()) backup.delete()
        }
        _trades.value = emptyList()
    }

    fun stats(): JournalStats {
        val closed = _trades.value.filter { !it.isOpen && it.pnlUsd != null }
        val wins = closed.filter { (it.pnlUsd ?: 0.0) > 0 }
        val losses = closed.filter { (it.pnlUsd ?: 0.0) <= 0 }
        val grossWin = wins.sumOf { it.pnlUsd ?: 0.0 }
        val grossLoss = abs(losses.sumOf { it.pnlUsd ?: 0.0 })
        val rMultiples = closed.mapNotNull { it.rMultiple }
        return JournalStats(
            total = closed.size,
            open = _trades.value.count { it.isOpen },
            wins = wins.size,
            losses = losses.size,
            winRate = if (closed.isEmpty()) null else wins.size * 100.0 / closed.size,
            profitFactor = if (grossLoss <= 0.0) null else grossWin / grossLoss,
            netPnl = closed.sumOf { it.pnlUsd ?: 0.0 },
            avgR = if (rMultiples.isEmpty()) null else rMultiples.average(),
        )
    }
}

data class JournalStats(
    val total: Int,
    val open: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double?,
    val profitFactor: Double?,
    val netPnl: Double,
    val avgR: Double?,
)
