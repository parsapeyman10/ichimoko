package com.aurum.edge.core

import com.aurum.edge.core.BacktestSeries.ENGINE_WINDOW_BARS
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * موتور بک‌تست «قدم‌به‌قدم» روی دادهٔ CSV (خروجی dukascopy-node).
 *
 * ## قانون اول: بک‌تست نباید به حساب برنامه دست بزند
 * این موتور **هیچ** چیزی از `JournalStore`، حساب کاغذی، یا فایل‌های ژورنال را نمی‌خواند و
 * نمی‌نویسد. فقط `List<Candle>` می‌گیرد و `BacktestResult` برمی‌گرداند. تنها اثری که می‌گذارد
 * این است که اگر بخواهید، `paperTradesForLearning()` را به دفتر یادگیری عصبی می‌دهید —
 * آن هم با شناسهٔ مستقل `BT-…` و فقط با انتخاب خودتان.
 *
 * ## قانون دوم: هر تصمیم فقط با کندل‌های بسته‌شده
 * در هر قدم، سه تایم‌فریم (ماکرو / استراتژی / ماشه) از `BacktestSeries.closedUpTo` ساخته
 * می‌شوند؛ کندلی که در لحظهٔ `now` هنوز بسته نشده حتی دیده نمی‌شود. پرکردن حد ضرر/حد سود هم
 * از کندل بعد از کندل ماشه انجام می‌شود.
 *
 * ## قانون سوم: محافظه‌کاری در شرایط مبهم
 * اگر در یک کندل هم حد ضرر و هم حد سود لمس شود، **حد ضرر** برنده است (بدترین حالت)، مگر
 * گزینهٔ `optimisticSameBarFill` روشن باشد. کارمزد و اسپرد همان `VenueSpecs` برنامه است.
 */
object BacktestReplayEngine {

    /**
     * کف و سقف ریسک هر معامله.
     *
     * کف ۰٫۰۵٪ جلوی این را می‌گیرد که حجم پوزیشن به صفر گرد شود و بک‌تست معامله‌ای با
     * سود/زیانِ اندازه‌ناپذیر بسازد؛ سقف ۵٪ جلوی یک معاملهٔ تک‌ضربه‌ای می‌ایستد که کل
     * منحنی سرمایه را بی‌معنا کند.
     */
    const val MIN_RISK_PERCENT: Double = 0.05
    const val MAX_RISK_PERCENT: Double = 5.0

    /** پیکربندی اجرای بک‌تست. هیچ‌کدام از این‌ها روی تنظیمات زندهٔ برنامه اثر ندارد. */
    data class Config(
        val strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        val symbol: String = "XAU/USD",
        /** تایم‌فریم‌های موتور؛ پیش‌فرض همان چیدمان استاندارد برنامه. */
        val macroInterval: Interval = Interval.H4,
        val strategyInterval: Interval = Interval.H1,
        val triggerInterval: Interval = Interval.M15,
        /** سرمایهٔ شروع برای محاسبهٔ درصد و افت سرمایه. */
        val startEquityUsd: Double = 1_000.0,
        /** ریسک هر معامله بر حسب درصد سرمایه (۰٫۰۵ تا ۵٪؛ بیرون از این بازه محدود می‌شود). */
        val riskPercent: Double = 0.5,
        /** همهٔ استراتژی‌های روشن به‌جای یک استراتژی. */
        val runAllStrategies: Boolean = false,
        val enabledStrategyIds: Set<String> = BitcoinStrategyModel.activeStrategyIds,
        /** خروج هوشمند/مدیریت معامله هم اجرا شود (همان کد زندهٔ برنامه). */
        val useSmartExit: Boolean = true,
        val usePreserveMode: Boolean = true,
        val useTimeStop: Boolean = true,
        /** حداکثر تعداد کندل نگه‌داری هر معامله؛ `null` = پیش‌فرض موتور زنده. */
        val maxHoldBars: Int? = null,
        /** توقف زمانی خروجی (نشانگر پیشرفت). */
        val maxBars: Int = 0,
        /** در صورت لمس هم‌زمان SL و TP، سود فرض شود (پیش‌فرض: نه). */
        val optimisticSameBarFill: Boolean = false,
        /**
         * لایهٔ «هیدن کراس» (دیوار تاج + ابر مخفی) جزء شرایط بیس باشد.
         * `true` = رفتار فعلی برنامه؛ `false` = بیس خالص چهارگانه. تنها ابزار مقایسهٔ A/B در بک‌تست.
         */
        // پیش‌فرض خاموش، مثل BaseStrategyConfig — با اندازه‌گیری A/B یکسان (۳.۹.۶۵).
        val includeHiddenCrossLayer: Boolean = false,
        /** حداقل کندل لازم برای شروع ارزیابی؛ پیش‌فرض بر اساس دورهٔ سنکو B. */
        val warmupBars: Int = 60,
        /**
         * نگه‌داشتن گزارش کامل استراتژی در لحظهٔ ورود (کلید = زمان ورود).
         * فقط برای آموزش هوش عصبی لازم است و حافظه می‌گیرد؛ برای بک‌تست خالی می‌مانَد.
         */
        val captureEntryReports: Boolean = false,
        /**
         * گیت عصبی که پیش از هر ورود پرسیده می‌شود.
         *
         * `null` یعنی گیت خاموش است و رفتار مو‌به‌مو مثل قبل می‌ماند. برای اینکه کلید
         * «مانع» در بخش هوش عصبی واقعاً اثر بگذارد، درگاه واقعی از `BacktestNeuralTrainer`
         * ساخته و از `BacktestViewModel` اینجا تزریق می‌شود.
         */
        val neuralGate: NeuralGatePolicy.EntryGate? = null,
        /**
         * فعال‌سازی ماشهٔ «فشردگی → انبساط» برای استراتژی بیس.
         *
         * پیش‌فرض `false` است و با آن، نتیجهٔ بک‌تست بیت‌به‌بیت مثل قبل می‌ماند (این ادعا
         * با مقایسهٔ تک‌به‌تک معاملات سنجیده شده است).
         */
        val compressionExpansionEnabled: Boolean = false,
        val compressionExpansionConfig: CompressionExpansionEngine.Config =
            CompressionExpansionEngine.Config(),
        /**
         * کندل‌های تایم‌فریم ماکرو که کاربر جداگانه داده است.
         *
         * اگر خالی باشد، موتور مثل قبل همان سری ماشه را resample می‌کند. اگر پر باشد،
         * **همان دادهٔ کاربر** استفاده می‌شود و هیچ بازسازی مصنوعی انجام نمی‌شود.
         */
        val macroCandlesOverride: List<com.aurum.edge.domain.Candle> = emptyList(),
        /** کندل‌های تایم‌فریم استراتژی که کاربر جداگانه داده است. */
        val strategyCandlesOverride: List<com.aurum.edge.domain.Candle> = emptyList(),
        /**
         * تنظیمات موتور «پیمان کریپتو حرفه‌ای».
         *
         * پیش‌فرض `DISABLED` است: هیچ ارزیابی و هیچ تغییری در نتیجهٔ بک‌تست رخ نمی‌دهد.
         * برای مقایسهٔ A/B کافی است `mode = PAPER_ENABLED` داده شود.
         */
        val peymanCryptoConfig: com.aurum.edge.core.peyman.PeymanCryptoConfig =
            com.aurum.edge.core.peyman.PeymanCryptoConfig(),
        /**
         * ضریب هزینهٔ اجرا: کارمزد + اسپرد مدل‌شدهٔ ونو در همین عدد ضرب می‌شود.
         *
         * چرا لازم است؟ ممیزی داده نشان داد اسپرد واقعیِ خودِ فایل (میانهٔ ask−bid روی
         * XAU/USD) حدود ۰٫۶۲ دلار است، در حالی که هزینهٔ مدل‌شدهٔ بک‌تست کمتر از این است.
         * با `1.0` (پیش‌فرض) رفتار برنامه هیچ تغییری نمی‌کند؛ `3.0` یک آزمون محافظه‌کارانه
         * است تا معلوم شود «آیا سود از هزینه‌های واقعی‌تر جان سالم به در می‌برد؟»
         */
        val costMultiplier: Double = 1.0,
    )

    /** یک معاملهٔ بک‌تست. جدا از `PaperTrade` نگه داشته می‌شود تا هیچ‌جا با ژورنال قاطی نشود. */
    data class Trade(
        val index: Int,
        val strategyId: String,
        val strategyTitleFa: String,
        val action: SignalAction,
        val entryTime: Long,
        val entryPrice: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val confidence: Double,
        val plannedRiskReward: Double,
        val exitTime: Long?,
        val exitPrice: Double?,
        val exitReason: ExitReason,
        val grossPnlUsd: Double,
        val feeUsd: Double,
        val netPnlUsd: Double,
        val rMultiple: Double,
        val barsHeld: Int,
        val equityAfterUsd: Double,
    ) {
        val isOpen: Boolean get() = exitTime == null
        val won: Boolean get() = !isOpen && netPnlUsd > 0.0
    }

    enum class ExitReason(val labelFa: String) {
        STOP_LOSS("حد ضرر"),
        TAKE_PROFIT("حد سود"),
        SMART_EXIT("خروج هوشمند"),
        PRESERVE_MODE("قفل سود (Preserve)"),
        TIME_STOP("توقف زمانی"),
        OPEN("باز"),
    }

    /** آمار یک استراتژی (یا کل اجرا وقتی تک‌استراتژی است). */
    data class Stats(
        val strategyId: String,
        val strategyTitleFa: String,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val openTrades: Int,
        val netPnlUsd: Double,
        val profitFactor: Double,
        val expectancyR: Double,
        val winRatePct: Double,
        val maxDrawdownUsd: Double,
        val maxDrawdownPct: Double,
        val avgBarsHeld: Double,
        val bestTradeUsd: Double,
        val worstTradeUsd: Double,
        val longTrades: Int,
        val shortTrades: Int,
        val longWinRatePct: Double,
        val shortWinRatePct: Double,
        val stopLossExits: Int,
        val takeProfitExits: Int,
        val smartExits: Int,
        val timeStopExits: Int,
        /** انحراف معیار R؛ پایهٔ شارپ سادهٔ بدون نرخ بهره. */
        val stdDevR: Double,
        val sharpePerTrade: Double,
    )

    data class Result(
        val config: Config,
        val stats: List<Stats>,
        val trades: List<Trade>,
        val equityCurve: List<Pair<Long, Double>>,
        val firstTime: Long,
        val lastTime: Long,
        val candlesUsed: Int,
        val steps: Int,
        val elapsedMs: Long,
        /** چند کندل واقعاً ارزیابی شد و چند تا به‌خاطر گرم‌شدن رد شد. */
        val evaluatedSteps: Int,
        val skippedWarmupSteps: Int,
        /** گزارش استراتژی در لحظهٔ ورود هر معامله؛ خالی مگر `captureEntryReports` روشن باشد. */
        val entryReports: Map<Long, StrategyEvaluationReport> = emptyMap(),
        /** تعداد ورودی‌هایی که گیت عصبی مسدود کرد (۰ = گیت خاموش یا هیچ‌چیز مسدود نشد). */
        val neuralBlockedEntries: Int = 0,
        /** دلیل مسدودی گیت → تعداد. برای اینکه بشود دید «مانع» چه چیزی را گرفته است. */
        val neuralBlockReasons: Map<String, Int> = emptyMap(),
        /** بیشترین شرطی که در یک کندل پاس شد؛ سقف واقعی همان بازه. */
        val maxConditionsPassed: Int = 0,
        /**
         * بهترین «هم‌گرایی مکتبی» که در این بازه دیده شد (درصد) و متن توضیحش.
         * چرا لازم است؟ چون «۰ معامله» بدون عدد، کاربر را در ابهام می‌گذارد؛ با این دو
         * مقدار معلوم می‌شود استراتژی چند درصد نزدیک بوده و کدام لایه کم آورده است.
         */
        val bestBookGatePct: Double = 0.0,
        val bestBookGateNoteFa: String = "",
        /** میانگین هم‌گرایی روی کل بازه — برای مقایسهٔ A/B بین دو تنظیم. */
        val averageBookGatePct: Double = 0.0,
        /** شرط‌هایی که بیشترین بار رد شدند (عنوان فارسی → تعداد)، بیشترین اول. */
        val topBlockers: List<Pair<String, Int>> = emptyList(),
        /** گزارش ماه‌به‌ماه (هر خط یک ماه). خالی یعنی اجرا یک‌جا بوده است. */
        val periodSummariesFa: List<String> = emptyList(),
    ) {
        val totals: Stats? = stats.firstOrNull { it.strategyId == "__ALL__" } ?: stats.firstOrNull()
        val finalEquityUsd: Double get() = equityCurve.lastOrNull()?.second ?: config.startEquityUsd
        val netPnlUsd: Double get() = finalEquityUsd - config.startEquityUsd
        val returnPct: Double get() = if (config.startEquityUsd > 0.0) netPnlUsd / config.startEquityUsd * 100.0 else 0.0
    }

    /**
     * برآورد زمان اجرا **پیش از شروع**، تا کاربر روی فایل سنگین غافلگیر نشود.
     *
     * اعداد از اندازه‌گیری واقعی روی همان موتور می‌آید (نه حدس): هر کندل در حالت تک‌استراتژی
     * حدود ۰٫۶۵ و در «همهٔ استراتژی‌ها» حدود ۲٫۱ میلی‌ثانیه. اگر سقف کندل (`maxBars`) گذاشته
     * شده باشد، همان تعداد شبیه‌سازی می‌شود و مبنا هم همان است.
     *
     * این تابع فقط **تخمین** می‌دهد؛ هیچ‌جا اجرا را متوقف نمی‌کند. تصمیم «اجرا یا کوچک‌کردن
     * بازه» با کاربر است، ولی دیگر کورکورانه نیست.
     */
    fun estimateMs(
        barCount: Int,
        runAllStrategies: Boolean,
        maxBars: Int = 0,
    ): Long {
        val effective = if (maxBars > 0) minOf(barCount, maxBars) else barCount
        val perBar = if (runAllStrategies) ALL_STRATEGIES_MS_PER_BAR else SINGLE_MS_PER_BAR
        return (effective * perBar).toLong()
    }

    /** جملهٔ فارسیِ برآورد برای نمایش در UI (بدون عدد نجومی و بدون ادعای دقیق). */
    fun estimateSentenceFa(
        barCount: Int,
        runAllStrategies: Boolean,
        maxBars: Int = 0,
    ): String {
        val effective = if (maxBars > 0) minOf(barCount, maxBars) else barCount
        val sec = (estimateMs(barCount, runAllStrategies, maxBars) / 1000).coerceAtLeast(1)
        val scope = if (runAllStrategies) "همهٔ استراتژی‌ها" else "یک استراتژی"
        val clamped = if (maxBars > 0 && maxBars < barCount) {
            " (فقط ${grouped(maxBars)} کندل آخر شبیه‌سازی می‌شود)"
        } else ""
        return when {
            sec >= 90 -> "≈ ${sec / 60} دقیقه برای $scope روی ${grouped(effective)} کندل$clamped"
            else -> "≈ $sec ثانیه برای $scope روی ${grouped(effective)} کندل$clamped"
        }
    }

    private fun grouped(n: Int): String = "%,d".format(n).replace(',', '٬')

    /** میلی‌ثانیه به‌ازای هر کندل، اندازه‌گیری‌شده روی همین موتور. */
    const val SINGLE_MS_PER_BAR: Double = 0.65
    const val ALL_STRATEGIES_MS_PER_BAR: Double = 2.1

    /**
     * اجرای بک‌تست.
     *
     * پنجرهٔ دید هر کندل به [ENGINE_WINDOW_BARS] محدود است و این روی نتیجه اثری ندارد: موتور
     * بیش از آن را نمی‌خواند. آزمون `HeavyCsvGuardTest` تک‌تک معامله‌ها را با حالت
     * «کل تاریخچه» مقایسه می‌کند. بدون این محدودیت، هزینه با هر کندل جدید رشد می‌کرد
     * (روی ۱۰۰٬۰۰۰ کندل: بیش از ۶۰۰ ثانیه → ۵۷ ثانیه).
     *
     * @param source کندل‌های پایه (بسته‌شده، مرتب، بدون شکاف نگران‌کننده).
     * @param progress هرچند قدم یک‌بار صدا زده می‌شود؛ `0..1`.
     */
    fun run(
        source: List<Candle>,
        config: Config,
        progress: ((Double) -> Unit)? = null,
    ): Result = runWithWindow(source, config, ENGINE_WINDOW_BARS, progress)

    // ─────────────────────── اجرای ماه‌به‌ماه ───────────────────────

    /**
     * از این تعداد کندل به بالا، اجرا **ماه‌به‌ماه** انجام می‌شود.
     *
     * اندازه‌گیری روی XAU ۱۵دقیقه (۴۳٬۸۱۰ کندل): اجرای یک‌جا ۹۹۹ معامله و +۰٫۳۸۰R داد و
     * اجرای ماه‌به‌ماه ۹۶۷ معامله و +۰٫۳۸۰R (اختلاف ۳٪ در شمار معامله، صفر در انتظار R).
     * یعنی ماه‌به‌ماه‌کردن نتیجه را عوض نمی‌کند، ولی حافظه/زمان را قابل‌کنترل و گزارش را
     * ماه‌به‌ماه می‌کند.
     */
    const val MONTHLY_MODE_MIN_BARS: Int = 20_000

    /**
     * اجرای «خودکار»: فایل سنگین ماه‌به‌ماه، فایل سبک یک‌جا. خروجی همیشه [Result] است، پس
     * بقیهٔ برنامه (صفحه، آرشیو، ذخیرهٔ کار، یادگیری عصبی) تفاوتی نمی‌بیند.
     */
    fun runAuto(
        source: List<Candle>,
        config: Config,
        progress: ((Double) -> Unit)? = null,
    ): Result = if (source.size >= MONTHLY_MODE_MIN_BARS) {
        runPeriodic(source, config, progress = progress).toResult(config)
    } else {
        run(source, config, progress)
    }

    /** نتیجهٔ یک ماه. */
    data class PeriodResult(
        val label: String,
        val fromMs: Long,
        val toMs: Long,
        val candles: Int,
        val trades: List<Trade>,
        val stats: Stats,
        val elapsedMs: Long,
    )

    /**
     * نتیجهٔ اجرای ماه‌به‌ماه.
     *
     * چرا این مسیر وجود دارد؟ فایل سنگین (چند صد هزار کندل m5 یا یک سال داده) یک‌جا نه
     * حافظهٔ گوشی را راضی می‌کند نه صبر کاربر را؛ و بدتر: اگر وسط کار قطع شود هیچ گزارشی
     * نمی‌ماند. این‌جا هر ماه یک بازهٔ مستقل است، نتیجه‌اش همان لحظه ساخته می‌شود و بعد
     * ماه بعد.
     */
    data class PeriodicResult(
        val periods: List<PeriodResult>,
        val trades: List<Trade>,
        val stats: List<Stats>,
        val elapsedMs: Long,
        val candlesUsed: Int,
        /** ماه‌هایی که کندل کافی/داده نداشتند — برای شفافیت، نه پنهان‌کاری. */
        val emptyMonths: Int,
    ) {
        val totals: Stats? get() = stats.firstOrNull { it.strategyId == "__ALL__" } ?: stats.firstOrNull()
        val months: Int get() = periods.size

        /** یک خط خلاصه برای هر ماه؛ همان چیزی که در نتیجهٔ بک‌تست دیده می‌شود. */
        val summaryRows: List<String>
            get() = periods.map { p ->
                val st = p.stats
                String.format(
                    java.util.Locale.US,
                    "%s → معامله=%d · برد=%.0f%% · خالص=%+.2f$ · PF=%.2f · انتظار=%+.3fR · کندل=%d",
                    p.label, st.trades, st.winRatePct, st.netPnlUsd, st.profitFactor,
                    st.expectancyR, p.candles,
                )
            }
    }

    /**
     * بک‌تست ماه‌به‌ماه با «گرم‌کردنِ حمل‌شده».
     *
     * هر ماه جداگانه اجرا می‌شود، ولی پنجرهٔ ورودی‌اش از `warmupCarry` کندل **قبل** از شروع
     * ماه شروع می‌شود تا اندیکاتورها (کومو، تنکان/کیجن، ATR) در کندل اول ماه از صفر شروع
     * نکنند؛ بدون این، هر ماه چند معاملهٔ کاذب در روز اول می‌ساخت. انتهای پنجره هم `tailCarry`
     * کندل جلوتر می‌رود تا معامله‌ای که آخر ماه باز شده فرصت بسته‌شدن طبیعی داشته باشد.
     *
     * معامله‌ها **بر اساس زمان ورود** به ماه نسبت داده می‌شوند، پس هیچ معامله‌ای دو بار
     * شمرده نمی‌شود و هیچ معامله‌ای هم بین دو ماه گم نمی‌شود.
     */
    fun runPeriodic(
        source: List<Candle>,
        config: Config,
        warmupCarry: Int = 360,
        tailCarry: Int = 0,
        progress: ((Double) -> Unit)? = null,
    ): PeriodicResult {
        val started = System.currentTimeMillis()
        require(source.size >= config.warmupBars) {
            "کندل کافی نیست: ${source.size} کندل خوانده شد ولی حداقل ${config.warmupBars} لازم است."
        }
        val ordered = source.sortedBy { it.time }
        val zone = java.time.ZoneOffset.UTC
        fun monthOf(ms: Long): java.time.YearMonth =
            java.time.YearMonth.from(java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

        val months = ordered.map { monthOf(it.time) }.distinct()
        val tail = if (tailCarry > 0) {
            tailCarry
        } else {
            maxOf(160, (config.maxHoldBars ?: IchimokuStrategyEngine.DEFAULT_MAX_HOLD_BARS) * 6)
        }
        val times = LongArray(ordered.size) { ordered[it].time }

        val periods = ArrayList<PeriodResult>(months.size)
        val allTrades = ArrayList<Trade>(1024)
        var emptyMonths = 0
        // سرمایه از ماه قبل حمل می‌شود؛ وگرنه هر ماه با ۱۰۰۰$ تازه شروع می‌شد و جمع سود
        // ماه‌ها با اجرای یک‌جا قابل مقایسه نبود (اندازه‌گیری اول: ۱٬۹۴۲$ در برابر ۵٬۳۷۸$).
        var runningEquity = config.startEquityUsd
        months.forEachIndexed { index, month ->
            val fromMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val toMs = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val firstIdx = times.lowerBound(fromMs)
            val lastIdx = times.lowerBound(toMs) // انحصاری
            val candlesInMonth = lastIdx - firstIdx
            val label = String.format(java.util.Locale.US, "%04d-%02d", month.year, month.monthValue)
            if (candlesInMonth <= 0) {
                emptyMonths++
                progress?.invoke((index + 1).toDouble() / months.size)
                return@forEachIndexed
            }
            val windowStart = (firstIdx - warmupCarry).coerceAtLeast(0)
            val windowEnd = (lastIdx + tail).coerceAtMost(ordered.size)
            val window = ordered.subList(windowStart, windowEnd)
            if (window.size < config.warmupBars) {
                // ماه ناقص (ابتدای فایل یا دادهٔ کم) — به‌جای استثنا، ماه خالی ثبت می‌شود.
                emptyMonths++
                periods += PeriodResult(
                    label = label,
                    fromMs = fromMs,
                    toMs = toMs,
                    candles = candlesInMonth,
                    trades = emptyList(),
                    stats = buildStats("__ALL__", "ماه $label", emptyList(), config.startEquityUsd),
                    elapsedMs = 0L,
                )
                progress?.invoke((index + 1).toDouble() / months.size)
                return@forEachIndexed
            }
            val monthStart = System.currentTimeMillis()
            val res = runWithWindow(
                window,
                config.copy(maxBars = 0, startEquityUsd = runningEquity),
                ENGINE_WINDOW_BARS,
                null,
            )
            // فقط معامله‌هایی که **ورودشان** داخل همین ماه است؛ بقیه (گرم‌کردن یا ماه بعد)
            // در ماه خودشان شمرده می‌شوند.
            val monthTrades = res.trades.filter { it.entryTime in fromMs until toMs }
            allTrades += monthTrades
            runningEquity += monthTrades.sumOf { it.netPnlUsd }
            periods += PeriodResult(
                label = label,
                fromMs = fromMs,
                toMs = toMs,
                candles = candlesInMonth,
                trades = monthTrades,
                stats = buildStats("__ALL__", "ماه $label", monthTrades, runningEquity - monthTrades.sumOf { it.netPnlUsd }),
                elapsedMs = System.currentTimeMillis() - monthStart,
            )
            progress?.invoke((index + 1).toDouble() / months.size)
        }

        val byId = allTrades.groupBy { it.strategyId }
        val stats = if (byId.size > 1) {
            byId.map { (id, list) ->
                val title = runCatching { BitcoinStrategyModel.fromId(id).titleFa }.getOrDefault(id)
                buildStats(id, title, list, config.startEquityUsd)
            } + buildStats("__ALL__", "مجموع همهٔ استراتژی‌ها", allTrades, config.startEquityUsd)
        } else {
            listOf(
                buildStats(
                    byId.keys.firstOrNull() ?: config.strategyId,
                    byId.values.firstOrNull()?.firstOrNull()?.strategyTitleFa ?: "استراتژی",
                    allTrades,
                    config.startEquityUsd,
                ),
            )
        }

        return PeriodicResult(
            periods = periods,
            trades = allTrades,
            stats = stats,
            elapsedMs = System.currentTimeMillis() - started,
            candlesUsed = ordered.size,
            emptyMonths = emptyMonths,
        )
    }

    /**
     * تبدیل نتیجهٔ ماه‌به‌ماه به [Result] معمولی.
     *
     * چرا؟ کل برنامه (صفحهٔ بک‌تست، آرشیو، یادگیری عصبی، ذخیرهٔ کار پس‌زمینه) با [Result]
     * کار می‌کند. با این تبدیل، فایل سنگین هم همان مسیر را می‌رود، فقط معامله‌هایش ماه‌به‌ماه
     * ساخته شده‌اند و گزارش ماه‌ها در `periodSummariesFa` می‌آید.
     */
    fun PeriodicResult.toResult(config: Config): Result {
        val closed = trades.sortedBy { it.exitTime ?: Long.MAX_VALUE }
        var equity = config.startEquityUsd
        val curve = ArrayList<Pair<Long, Double>>(closed.size + 1)
        curve += (closed.firstOrNull()?.entryTime ?: System.currentTimeMillis()) to equity
        closed.forEach { trade ->
            equity += trade.netPnlUsd
            curve += (trade.exitTime ?: trade.entryTime) to round2(equity)
        }
        return Result(
            config = config,
            stats = stats,
            trades = trades,
            equityCurve = curve,
            firstTime = periods.firstOrNull()?.fromMs ?: 0L,
            lastTime = periods.lastOrNull()?.toMs ?: 0L,
            candlesUsed = candlesUsed,
            steps = periods.sumOf { it.candles },
            elapsedMs = elapsedMs,
            evaluatedSteps = periods.sumOf { it.candles },
            skippedWarmupSteps = 0,
            maxConditionsPassed = 0,
            periodSummariesFa = summaryRows,
        )
    }

    /** ایندکس اولین عنصری که `>= target` است (جست‌وجوی دودویی روی آرایهٔ مرتب). */
    private fun LongArray.lowerBound(target: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * همان `run` با پنجرهٔ دید قابل‌تنظیم — فقط برای آزمون این‌که بریدن پنجره نتیجه را عوض
     * نمی‌کند. مسیر اصلی همان `ENGINE_WINDOW_BARS` است.
     */
    internal fun runWithWindow(
        source: List<Candle>,
        config: Config,
        windowBars: Int,
        progress: ((Double) -> Unit)? = null,
    ): Result {
        val started = System.currentTimeMillis()
        require(source.size >= config.warmupBars) {
            "کندل کافی نیست: ${source.size} کندل خوانده شد ولی حداقل ${config.warmupBars} لازم است."
        }
        val ordered = source.sortedBy { it.time }
        // ── دادهٔ چند تایم‌فریمی ────────────────────────────────────────────────
        // اگر کاربر دادهٔ ۴ساعته/۱ساعتهٔ واقعی داده باشد، همان استفاده می‌شود؛ در غیر این
        // صورت (رفتار قبلی) سری ماشه resample می‌شود. دلیل: resample از ۱۵m نمی‌تواند
        // های/لوهای واقعی تایم بالاتر را بسازد و سایه‌های همان تایم را از دست می‌دهد.
        val macroBars = if (config.macroCandlesOverride.isNotEmpty()) {
            config.macroCandlesOverride.sortedBy { it.time }
        } else {
            BacktestSeries.resample(ordered, config.macroInterval)
        }
        val strategyBars = if (config.strategyCandlesOverride.isNotEmpty()) {
            config.strategyCandlesOverride.sortedBy { it.time }
        } else {
            BacktestSeries.resample(ordered, config.strategyInterval)
        }
        val triggerBars = BacktestSeries.resample(ordered, config.triggerInterval)

        // دادهٔ بک‌تست تغییرناپذیر است، پس اسنپ‌شات‌های یک کندل را می‌توان بین استراتژی‌ها
        // به اشتراک گذاشت. این حافظه فقط در همین اجرا روشن است و در پایان خاموش می‌شود.
        IchimokuStrategyEngine.enableBacktestSnapshotCache()

        val baseConfig = BaseStrategyConfig().copy(
            macroIntervalLabel = config.macroInterval.label,
            strategyIntervalLabel = config.strategyInterval.label,
            triggerIntervalLabel = config.triggerInterval.label,
            enablePreserveMode = config.usePreserveMode,
            includeHiddenCrossLayer = config.includeHiddenCrossLayer,
            compressionExpansion = if (config.compressionExpansionEnabled) {
                config.compressionExpansionConfig.copy(enabled = true)
            } else {
                CompressionExpansionEngine.Config()
            },
            peymanCrypto = config.peymanCryptoConfig,
        )

        val models: List<BitcoinStrategyModel> = if (config.runAllStrategies) {
            BitcoinStrategyModel.entries
                .filter { it.id in config.enabledStrategyIds && !it.isTemporarilyDisabled }
                .ifEmpty { listOf(BitcoinStrategyModel.fromId(config.strategyId)) }
        } else {
            listOf(BitcoinStrategyModel.fromId(config.strategyId))
        }

        val states = models.associateWith { StrategyRunState(it) }
        val entryReports = LinkedHashMap<Long, StrategyEvaluationReport>()
        // شمارش مسدودی‌های گیت عصبی — تا «مانع» قابل‌اندازه‌گیری باشد، نه ادعا.
        var neuralBlocked = 0
        val neuralBlockReasons = LinkedHashMap<String, Int>()
        // اگر در کل بازه هیچ ورودی ساخته نشد، کاربر باید بداند کدام شرط سد راه بوده،
        // نه این‌که با یک «۰ معامله» بی‌توضیح روبه‌رو شود.
        val conditionFailCounts = LinkedHashMap<String, Int>()
        var maxConditionsPassed = 0
        var bestBookGatePct = 0.0
        var bestBookGateNoteFa = ""
        var bookGateSum = 0.0
        var bookGateSamples = 0
        val allTrades = ArrayList<Trade>(256)
        val equityPoints = ArrayList<Pair<Long, Double>>(1024)
        var equity = config.startEquityUsd
        equityPoints += (ordered.first().time to equity)

        val triggerSize = config.triggerInterval.millis
        val maxBars = if (config.maxBars > 0) min(config.maxBars, triggerBars.size) else triggerBars.size
        val firstEvaluable = max(config.warmupBars, baseConfig.senkouBPeriod + 6)
        var evaluated = 0
        var skipped = 0

        for (i in 0 until maxBars) {
            val bar = triggerBars[i]
            // لحظه‌ای که کندل ماشه بسته می‌شود؛ هیچ کندلی بعد از این لحظه دیده نمی‌شود.
            val now = bar.time + triggerSize

            if (i < firstEvaluable) {
                skipped++
                continue
            }

            // limit عمدی است: موتور بیش از ~۲۰۰ کندل آخر را نمی‌خواند، پس آوردن کل تاریخچه
            // فقط هزینهٔ فیلتر و مرتب‌سازی در هر کندل را به تاریخچه گره می‌زد (رشد درجه‌دو).
            val macroWindow = BacktestSeries.closedUpTo(
                macroBars, config.macroInterval.millis, now, windowBars,
            )
            val strategyWindow = BacktestSeries.closedUpTo(
                strategyBars, config.strategyInterval.millis, now, windowBars,
            )
            val triggerWindow = BacktestSeries.closedUpTo(
                triggerBars, triggerSize, now, windowBars,
            )
            if (macroWindow.size < 30 || strategyWindow.size < 30 || triggerWindow.size < firstEvaluable) {
                skipped++
                continue
            }

            // ۱. مدیریت معاملهٔ باز پیش از تصمیم جدید — با قیمت‌های همین کندل.
            for (state in states.values) {
                val open = state.openTrade ?: continue
                val closed = manageOpenTrade(
                    open = open,
                    bar = bar,
                    now = now,
                    config = config,
                    reportForBar = {
                        IchimokuStrategyEngine.evaluateBitcoinStrategy(
                            activeModel = state.model,
                            config = baseConfig,
                            macroCandles = macroWindow,
                            strategyCandles = strategyWindow,
                            triggerCandles = triggerWindow,
                            livePriceOverride = bar.close,
                            nowMillis = now,
                            symbolInput = config.symbol,
                        )
                    },
                )
                if (closed != null) {
                    state.openTrade = null
                    equity += closed.netPnlUsd
                    // سرمایهٔ بعد از هر معامله در خود معامله هم نوشته می‌شود (قبلاً همیشه ۰ بود و
                    // ستون «سرمایه بعد از معامله» در آرشیو بی‌معنا بود).
                    allTrades += closed.copy(equityAfterUsd = round2(equity))
                    equityPoints += ((closed.exitTime ?: now) to equity)
                }
            }

            // ۲. ارزیابی ورود جدید — فقط اگر همان استراتژی معاملهٔ باز ندارد.
            for (state in states.values) {
                if (state.openTrade != null) continue
                val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
                    activeModel = state.model,
                    config = baseConfig,
                    macroCandles = macroWindow,
                    strategyCandles = strategyWindow,
                    triggerCandles = triggerWindow,
                    livePriceOverride = bar.close,
                    nowMillis = now,
                    symbolInput = config.symbol,
                )
                maxConditionsPassed = maxOf(maxConditionsPassed, report.conditions.count { it.passed })
                if (report.bookGateScorePct > 0.0) {
                    bookGateSum += report.bookGateScorePct
                    bookGateSamples++
                    if (report.bookGateScorePct > bestBookGatePct) {
                        bestBookGatePct = report.bookGateScorePct
                        bestBookGateNoteFa = report.bookGateNoteFa
                    }
                }
                report.conditions.filterNot { it.passed }.forEach { cond ->
                    conditionFailCounts[cond.titleFa] = (conditionFailCounts[cond.titleFa] ?: 0) + 1
                }
                val decision = entryDecision(report, bar.close, equity, config.riskPercent)
                if (decision != null) {
                    // ── گیت عصبی (همان کلید «مانع» بخش هوش عصبی) ──────────────────
                    // پیش از این هیچ‌جای این موتور `NeuralGatePolicy.decide` صدا زده
                    // نمی‌شد، پس روشن‌وخاموش‌کردن «مانع» نتیجهٔ بک‌تست را عوض نمی‌کرد.
                    val gate = config.neuralGate
                    val verdict = gate?.judge(
                        NeuralGatePolicy.GateRequest(
                            strategyId = state.model.id,
                            symbol = config.symbol,
                            isLong = decision.action == SignalAction.BUY,
                            triggerInterval = config.triggerInterval,
                            engineConfidencePct = report.confidence,
                            plannedRiskReward = report.riskReward ?: 0.0,
                            entryTime = decision.entryTime,
                            report = report,
                        ),
                    )
                    if (verdict?.blocks == true) {
                        neuralBlocked++
                        neuralBlockReasons[verdict.reasonFa] = (neuralBlockReasons[verdict.reasonFa] ?: 0) + 1
                    } else {
                        state.openTrade = decision
                        if (config.captureEntryReports) entryReports.putIfAbsent(decision.entryTime, report)
                    }
                }
            }

            evaluated++
            if (progress != null && evaluated % 200 == 0) {
                progress((i + 1).toDouble() / maxBars)
            }
        }

        // ۳. معامله‌های بازِ باقی‌مانده با قیمت آخر بسته می‌شوند تا آمار نیمه‌کاره نماند.
        val lastBar = triggerBars.getOrNull(maxBars - 1) ?: triggerBars.last()
        for (state in states.values) {
            val open = state.openTrade ?: continue
            val closed = closeAt(
                open = open,
                price = lastBar.close,
                time = lastBar.time + triggerSize,
                reason = ExitReason.OPEN,
                barsHeld = open.barsHeld,
                config = config,
            )
            equity += closed.netPnlUsd
            allTrades += closed.copy(equityAfterUsd = round2(equity))
            equityPoints += ((closed.exitTime ?: lastBar.time) to equity)
            state.openTrade = null
        }

        progress?.invoke(1.0)

        val perStrategy = models.map { model ->
            val own = allTrades.filter { it.strategyId == model.id }
            buildStats(model.id, model.titleFa, own, config.startEquityUsd)
        }
        val stats = if (models.size > 1) perStrategy + buildStats("__ALL__", "مجموع همهٔ استراتژی‌ها", allTrades, config.startEquityUsd) else perStrategy

        IchimokuStrategyEngine.disableBacktestSnapshotCache()
        return Result(
            config = config,
            stats = stats,
            // شمارهٔ ردیف برای آرشیو: ترتیب زمانی ورود، از ۱. پیش از این همه ۰ بودند و
            // فهرست معامله‌ها در آرشیو/چارت شمارهٔ قابل ارجاع نداشت.
            trades = allTrades.sortedBy { it.entryTime }.mapIndexed { i, t -> t.copy(index = i + 1) },
            equityCurve = equityPoints,
            firstTime = ordered.first().time,
            lastTime = ordered.last().time,
            candlesUsed = ordered.size,
            steps = maxBars,
            elapsedMs = System.currentTimeMillis() - started,
            evaluatedSteps = evaluated,
            skippedWarmupSteps = skipped,
            entryReports = entryReports,
            neuralBlockedEntries = neuralBlocked,
            neuralBlockReasons = neuralBlockReasons,
            maxConditionsPassed = maxConditionsPassed,
            bestBookGatePct = bestBookGatePct,
            bestBookGateNoteFa = bestBookGateNoteFa,
            averageBookGatePct = if (bookGateSamples > 0) bookGateSum / bookGateSamples else 0.0,
            topBlockers = conditionFailCounts.entries.sortedByDescending { it.value }.take(6).map { it.toPair() },
        )
    }

    // ─────────────────────────── ورود ───────────────────────────

    private fun entryDecision(
        report: StrategyEvaluationReport,
        fillPrice: Double,
        equityUsd: Double,
        riskPercent: Double,
    ): OpenTrade? {
        if (!report.isActionable) return null
        val entry = report.suggestedEntry?.takeIf { it > 0.0 } ?: fillPrice
        val stop = report.suggestedStopLoss?.takeIf { it > 0.0 } ?: return null
        val target = report.primaryTakeProfit?.takeIf { it > 0.0 } ?: return null
        val isBuy = report.action == SignalAction.BUY
        // حد ضرر و حد سود باید در سمت درست قیمت باشند، وگرنه معامله از ابتدا بی‌معناست.
        if (isBuy && !(stop < entry && target > entry)) return null
        if (!isBuy && !(stop > entry && target < entry)) return null
        val oneR = abs(entry - stop)
        if (oneR <= 0.0) return null
        // حجم پوزیشن از «ریسک درصدی سرمایه» می‌آید، نه از یک عدد ثابت. این همان چیزی است که
        // افت سرمایه و منحنی سرمایه را معنادار می‌کند: با ۰٫۵٪ ریسک، هر ۱R تقریباً ۰٫۵٪
        // سرمایه است، مستقل از این‌که قیمت نماد ۱٫۱ باشد یا ۲۴۰۰.
        val riskUsd = equityUsd * (riskPercent.coerceIn(MIN_RISK_PERCENT, MAX_RISK_PERCENT) / 100.0)
        val units = (riskUsd / oneR).coerceAtLeast(1e-8)
        return OpenTrade(
            strategyId = report.strategyId,
            strategyTitleFa = report.strategyTitleFa,
            action = report.action,
            entryTime = report.triggerBarTime,
            entry = entry,
            stopLoss = stop,
            takeProfit = target,
            confidence = report.confidence,
            plannedRiskReward = report.riskReward ?: (abs(target - entry) / oneR),
            barsHeld = 0,
            initialStopLoss = stop,
            positionUnits = units,
        )
    }

    // ─────────────────────── مدیریت معاملهٔ باز ───────────────────────

    internal fun manageOpenTrade(
        open: OpenTrade,
        bar: Candle,
        now: Long,
        config: Config,
        reportForBar: () -> StrategyEvaluationReport,
    ): Trade? {
        val barsHeld = open.barsHeld + 1
        val isBuy = open.action == SignalAction.BUY
        open.barsHeld = barsHeld

        // ۱. آیا حد ضرر یا حد سود در همین کندل لمس شد؟
        val stopHit = if (isBuy) bar.low <= open.stopLoss else bar.high >= open.stopLoss
        val targetHit = if (isBuy) bar.high >= open.takeProfit else bar.low <= open.takeProfit

        if (stopHit && targetHit) {
            val reason = if (config.optimisticSameBarFill) ExitReason.TAKE_PROFIT else ExitReason.STOP_LOSS
            val price = if (config.optimisticSameBarFill) open.takeProfit else open.stopLoss
            return closeAt(open, price, bar.time + config.triggerInterval.millis, reason, barsHeld, config)
        }
        if (stopHit) {
            // پر شدن حد ضرر با همان قیمت حد ضرر (نه قیمت باز/بسته) — محافظه‌کارانه و ساده.
            return closeAt(open, open.stopLoss, bar.time + config.triggerInterval.millis, ExitReason.STOP_LOSS, barsHeld, config)
        }
        if (targetHit) {
            return closeAt(open, open.takeProfit, bar.time + config.triggerInterval.millis, ExitReason.TAKE_PROFIT, barsHeld, config)
        }

        // ۲. قاعدهٔ زمانی — همان تابع زندهٔ برنامه.
        if (config.useTimeStop) {
            val pseudo = open.toPseudoPaperTrade(config)
            val timeStop = IchimokuStrategyEngine.evaluateTimeStop(
                trade = pseudo,
                livePrice = bar.close,
                now = now,
                maxHoldBars = config.maxHoldBars ?: IchimokuStrategyEngine.DEFAULT_MAX_HOLD_BARS,
            )
            if (timeStop.shouldClose) {
                return closeAt(open, bar.close, now, ExitReason.TIME_STOP, barsHeld, config)
            }
        }

        // ۳. خروج هوشمند/Preserve — فقط با کندل بسته و همان موتور زنده.
        if (config.useSmartExit || config.usePreserveMode) {
            val pseudo = open.toPseudoPaperTrade(config)
            val report = reportForBar()
            if (config.useSmartExit) {
                val health = IchimokuStrategyEngine.evaluateTrendHealthAndProfitExit(
                    trade = pseudo,
                    report = report,
                    livePriceOverride = bar.close,
                )
                val oneR = abs(open.entry - open.initialStopLoss).coerceAtLeast(1e-9)
                val currentR = if (isBuy) (bar.close - open.entry) / oneR else (open.entry - bar.close) / oneR
                if (health.shouldExitNowInProfit && currentR >= IchimokuStrategyEngine.MIN_SMART_EXIT_PROFIT_R) {
                    return closeAt(open, bar.close, now, ExitReason.SMART_EXIT, barsHeld, config)
                }
            }
            if (config.usePreserveMode) {
                val preserve = IchimokuStrategyEngine.evaluateOpenTradeManagement(
                    trade = pseudo,
                    report = report,
                    config = BaseStrategyConfig(),
                )
                if (preserve != null) {
                    val newStop = preserve.updatedStopLoss?.takeIf { it > 0.0 }
                    if (newStop != null) {
                        // حد ضرر فقط در جهت سود جابه‌جا می‌شود؛ هرگز عقب نمی‌رود.
                        open.stopLoss = if (isBuy) max(open.stopLoss, newStop) else min(open.stopLoss, newStop)
                    }
                    if (preserve.shouldCloseImmediatelyInProfit) {
                        val exitPrice = preserve.closePrice?.takeIf { it > 0.0 } ?: bar.close
                        return closeAt(open, exitPrice, now, ExitReason.PRESERVE_MODE, barsHeld, config)
                    }
                }
            }
        }
        return null
    }

    internal fun closeAt(
        open: OpenTrade,
        price: Double,
        time: Long,
        reason: ExitReason,
        barsHeld: Int,
        config: Config,
    ): Trade {
        val safePrice = if (price.isFinite() && price > 0.0) price else open.entry
        val pseudo = open.toPseudoPaperTrade(config)
        val oneR = abs(open.entry - open.initialStopLoss).coerceAtLeast(1e-9)
        val riskUsd = oneR * open.positionUnits
        val rawPnl = if (open.action == SignalAction.BUY) safePrice - open.entry else open.entry - safePrice
        // نسبت حرکت قیمت به ارزش قرارداد (همان منطق PaperTrade برای هر دسته دارایی).
        val grossUsd = PaperOrderRules.quotePnlToUsd(config.symbol, rawPnl * pseudo.positionOz, open.entry)
        val fee = VenueSpecs.of(config.symbol).totalRoundTripFeeUsd(open.entry, pseudo.positionOz) *
            config.costMultiplier
        val netUsd = grossUsd - fee
        return Trade(
            index = 0,
            strategyId = open.strategyId,
            strategyTitleFa = open.strategyTitleFa,
            action = open.action,
            entryTime = open.entryTime,
            entryPrice = open.entry,
            stopLoss = open.initialStopLoss,
            takeProfit = open.takeProfit,
            confidence = open.confidence,
            plannedRiskReward = open.plannedRiskReward,
            exitTime = time,
            exitPrice = safePrice,
            exitReason = reason,
            grossPnlUsd = round2(grossUsd),
            feeUsd = round2(fee),
            netPnlUsd = round2(netUsd),
            rMultiple = if (riskUsd > 0.0) round2(netUsd / riskUsd) else 0.0,
            barsHeld = barsHeld,
            equityAfterUsd = 0.0,
        )
    }

    // ─────────────────────── حالت داخلی ───────────────────────

    private class StrategyRunState(val model: BitcoinStrategyModel) {
        var openTrade: OpenTrade? = null
    }

    internal class OpenTrade(
        val strategyId: String,
        val strategyTitleFa: String,
        val action: SignalAction,
        val entryTime: Long,
        val entry: Double,
        var stopLoss: Double,
        val takeProfit: Double,
        val confidence: Double,
        val plannedRiskReward: Double,
        var barsHeld: Int,
        val initialStopLoss: Double,
        /** حجم پوزیشن بر پایهٔ ریسک درصدی سرمایه و فاصلهٔ حد ضرر. */
        val positionUnits: Double,
    ) {
        fun toPseudoPaperTrade(config: Config): PaperTrade = PaperTrade(
            id = "BT-$strategyId-$entryTime",
            symbol = config.symbol,
            interval = config.triggerInterval,
            action = action,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            confidence = confidence,
            riskReward = plannedRiskReward,
            openedAt = entryTime,
            positionOz = positionUnits,
            initialStopLoss = initialStopLoss,
            strategyId = strategyId,
            strategyTitleFa = strategyTitleFa,
            leverage = PaperOrderRules.defaultLeverageFor(config.symbol),
        )
    }

    // ─────────────────────── آمار ───────────────────────

    internal fun buildStats(
        id: String,
        titleFa: String,
        trades: List<Trade>,
        startEquityUsd: Double = 1_000.0,
    ): Stats {
        val closed = trades.filter { !it.isOpen }
        val wins = closed.filter { it.netPnlUsd > 0.0 }
        val losses = closed.filter { it.netPnlUsd <= 0.0 }
        val grossWin = wins.sumOf { it.netPnlUsd }
        val grossLoss = abs(losses.sumOf { it.netPnlUsd })
        val net = closed.sumOf { it.netPnlUsd }
        val rs = closed.map { it.rMultiple }
        val avgR = if (rs.isEmpty()) 0.0 else rs.average()
        val variance = if (rs.size > 1) rs.sumOf { (it - avgR) * (it - avgR) } / (rs.size - 1) else 0.0
        val sd = kotlin.math.sqrt(max(variance, 0.0))

        // ── افت سرمایه روی «سرمایهٔ حساب» ────────────────────────────────────────
        // قبلاً منحنی از صفر شروع می‌شد و مخرج کسر «بیشترین سود انباشته» بود؛ دو نتیجهٔ غلط:
        //   ۱. منحنی‌ای که فقط ضرر می‌دهد هرگز سقف تازه نمی‌سازد، پس افت ۰٪ گزارش می‌شد
        //      (`peak` صفر می‌ماند و شرط `peak > 1e-9` رد می‌شد).
        //   ۲. با سود انباشتهٔ کوچک، افت نسبی منفجر می‌شد: BTC/USD واقعی → «افت ۵۲۷٪».
        // حالا equity و peak هر دو از سرمایهٔ شروع حساب می‌شوند، یعنی همان چیزی که کاربر
        // روی حسابش می‌بیند.
        var equity = startEquityUsd
        var peak = startEquityUsd
        var maxDd = 0.0
        for (trade in closed.sortedBy { it.exitTime ?: Long.MAX_VALUE }) {
            equity += trade.netPnlUsd
            if (equity > peak) peak = equity
            val dd = peak - equity
            if (dd > maxDd) maxDd = dd
        }
        val longs = closed.filter { it.action == SignalAction.BUY }
        val shorts = closed.filter { it.action == SignalAction.SELL }
        return Stats(
            strategyId = id,
            strategyTitleFa = titleFa,
            trades = closed.size,
            wins = wins.size,
            losses = losses.size,
            openTrades = trades.count { it.isOpen },
            netPnlUsd = round2(net),
            profitFactor = if (grossLoss > 1e-9) round2(grossWin / grossLoss) else if (grossWin > 0.0) 99.0 else 0.0,
            expectancyR = round2(avgR),
            winRatePct = if (closed.isEmpty()) 0.0 else round2(wins.size * 100.0 / closed.size),
            maxDrawdownUsd = round2(maxDd),
            maxDrawdownPct = if (peak > 1e-9) round2(maxDd / peak * 100.0) else 0.0,
            avgBarsHeld = if (closed.isEmpty()) 0.0 else round2(closed.map { it.barsHeld }.average()),
            bestTradeUsd = round2(closed.maxOfOrNull { it.netPnlUsd } ?: 0.0),
            worstTradeUsd = round2(closed.minOfOrNull { it.netPnlUsd } ?: 0.0),
            longTrades = longs.size,
            shortTrades = shorts.size,
            longWinRatePct = if (longs.isEmpty()) 0.0 else round2(longs.count { it.netPnlUsd > 0.0 } * 100.0 / longs.size),
            shortWinRatePct = if (shorts.isEmpty()) 0.0 else round2(shorts.count { it.netPnlUsd > 0.0 } * 100.0 / shorts.size),
            stopLossExits = closed.count { it.exitReason == ExitReason.STOP_LOSS },
            takeProfitExits = closed.count { it.exitReason == ExitReason.TAKE_PROFIT },
            smartExits = closed.count { it.exitReason == ExitReason.SMART_EXIT || it.exitReason == ExitReason.PRESERVE_MODE },
            timeStopExits = closed.count { it.exitReason == ExitReason.TIME_STOP },
            stdDevR = round2(sd),
            sharpePerTrade = if (sd > 1e-9) round2(avgR / sd) else 0.0,
        )
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
