package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * # لایهٔ رفتاری «عادت‌های معاملاتی» (Trading Habits)
 *
 * منبع: کتاب *Trading Habits* نوشتهٔ Steve Burns و Holly Burns — ۳۹ قاعده (Rule 1..Rule 39).
 * سند انتقال دانش آن کتاب (`Trading Habits — سند انتقال دانش v3`) هر قاعده را به یک
 * «کارت قاعده» تبدیل کرده و برای هر کارت چهار سطح درک مطلب خواسته است: تحت‌اللفظی، استنباطی،
 * کاربردی و مرزی. این فایل همان کارت‌ها را از «متن» به «کدِ اجراشدنی» تبدیل می‌کند.
 *
 * ## چرا این فایل وجود دارد؟
 *
 * موتور استراتژی (`IchimokuStrategyEngine`) خیلی خوب می‌گوید **کجا** وارد شویم، اما «چقدر»
 * و «کی نبايد وارد شویم» جای دیگری بود: سقف ریسک هر معامله، مجموع ریسک باز، فاصلهٔ حد ضرر از
 * نویز، افت سرمایه از **قله** (نه از سرمایهٔ اولیه)، سری باخت‌های پیاپی، میانگین‌گیری روی
 * ضرر و اندازهٔ پله‌ای بر اساس کیفیت ستاپ. کتاب دقیقاً همین‌ها را می‌گوید و در کد ما پراکنده یا
 * غایب بودند.
 *
 * ## برچسب‌گذاری (همان قرارداد سند)
 *
 * - `[BOOK-EXPLICIT]` = عدد یا قاعده‌ای که مستقیماً در کتاب آمده است.
 * - `[ENGINEERING-ADDON]` = افزودهٔ مهندسی برای اجرایی‌شدن؛ ادعای کتاب نیست و باید بک‌تست شود.
 * - `[UNVERIFIED]` = بدون پشتوانهٔ کافی؛ در پیش‌فرض‌ها استفاده نشده است.
 *
 * ## جای این لایه در معماری
 *
 * ```
 * موتور استراتژی ─► TradePlanGuard (دالان نوسان/لنگر) ─► EntrySafetyGate (سقف/سشن/کهنگی)
 *                                                     └─► TradingHabitsPolicy  ← این فایل
 *                                                          ├─ ریسک هر معامله ≤ ۱٪        (R32)
 *                                                          ├─ مجموع ریسک باز ≤ ۳٪        (R34)
 *                                                          ├─ R:R ≥ 2                     (R03)
 *                                                          ├─ حد ضرر بیرون از نویز ATR    (R39)
 *                                                          ├─ ممنوعیت میانگین‌گیری روی ضرر (R36)
 *                                                          ├─ کوچک‌کردن سایز در سری باخت    (R35)
 *                                                          ├─ کوچک‌کردن سایز در افت از قله (R23)
 *                                                          ├─ پلهٔ سایز بر اساس کیفیت ستاپ  (R31)
 *                                                          └─ ضربه‌گیر جهش نوسان ×۱٫۵      (R38)
 * ```
 *
 * این object هیچ وابستگی اندرویدی ندارد و در آزمایشگاه JVM (`tools/strategy_lab`) روی دادهٔ
 * ساختگی اجرا می‌شود؛ همان کدی که در APK می‌دود.
 */
object TradingHabitsPolicy {

    // ───────────────────────────────────────────────────────────────────────────
    // ۱) ثابت‌ها — هر کدام به قاعدهٔ کتابش وصل است
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * R32 — «هرگز بیش از ۱٪ کل سرمایه را در یک معامله از دست نده».
     *
     * کتاب صریح است: ۱٪ یعنی **ضرر خالص** هر معامله ≤ ۱٪ حساب، نه «با ۱٪ سرمایه معامله کن» و
     * نه «حد ضرر ۱٪ قیمت سهم». [BOOK-EXPLICIT]
     */
    const val MAX_RISK_PER_TRADE_PCT: Double = 1.0

    /**
     * R34 — «هرگز بیش از ۳٪ سرمایه را در بدترین روز از دست نده»: حداکثر سه معاملهٔ هم‌زمان با
     * ریسک ۱٪. [BOOK-EXPLICIT]
     *
     * در کد ما این سقف با **ریسک دلاری** سنجیده می‌شود، نه با «تعداد پوزیشن». کاربر قبلاً سقف
     * تعداد پوزیشن را برداشته بود؛ این قاعده آن تصمیم را برنمی‌گرداند — می‌توانی ده پوزیشن
     * کوچک داشته باشی، ولی مجموع ریسکشان از ۳٪ بیرون نمی‌زند.
     */
    const val MAX_OPEN_RISK_PCT: Double = 3.0

    /**
     * R03 — «پاداش باید دو تا سه برابر ریسک باشد» (Richard Weissman). کتاب «دو یا سه برابر»
     * می‌گوید؛ کف را ۲ می‌گیریم. [BOOK-EXPLICIT]
     */
    const val MIN_RISK_REWARD: Double = 2.0

    /**
     * R39 — «stop را خارج از نویز بگذار تا فقط وقتی اشتباه می‌کنی بخورد»؛ تست خود کتاب:
     * «stop زیر ATR×۱ → رد». [BOOK-EXPLICIT] (ضریب ۱ همان تست کتاب است)
     */
    const val MIN_STOP_ATR_MULTIPLE: Double = 1.0

    /**
     * ضریب پیش‌فرض ATR برای فاصلهٔ حد ضرر. سند بخش ۴ می‌گوید k پیش‌فرض ۱٫۵ تا ۲ و **باید
     * بک‌تست شود**. [ENGINEERING-ADDON] — در آزمایشگاه `Habits.kt` روی چند مقدار سنجیده شده.
     */
    const val DEFAULT_STOP_ATR_MULTIPLE: Double = 1.5

    /** هزینهٔ لغزش رفت‌وبرگشت فرضی (پایه) برای «ریسک مؤثر». [ENGINEERING-ADDON] */
    const val DEFAULT_SLIPPAGE_BPS: Double = 2.0

    /**
     * R38 — «ناپایداری را بشناس و سایز را برای جهش نوسان تنظیم کن»: کتاب می‌گوید دامنهٔ ۱۰ روز
     * اخیر × ۱٫۵ را مبنا بگیر تا ۵۰٪ افزایش نوسان ضرر مخرب نسازد. [BOOK-EXPLICIT]
     */
    const val RANGE_SHOCK_MULTIPLIER: Double = 1.5

    /**
     * R37 — «هرگز اجازه نده سود تحقق‌نیافتهٔ معنادار به ضرر تحقق‌یافته تبدیل شود».
     * «معنادار» در سند به [ENGINEERING-ADDON] با پیش‌فرض ۱R برچسب خورده و باید بک‌تست شود.
     */
    const val PROFIT_LOCK_AT_R: Double = 1.0

    /**
     * R35 — «وقتی بد معامله می‌کنم سایز را کم می‌کنم» (Paul Tudor Jones). آستانه عددی در کتاب
     * نیست؛ ۳ باخت پیاپی و ضریب ۰٫۵ افزودهٔ مهندسی است. [ENGINEERING-ADDON]
     */
    const val LOSS_STREAK_TRIGGER: Int = 3

    /** ضریب سایز پس از رسیدن به آستانهٔ سری باخت. [ENGINEERING-ADDON] */
    const val LOSS_STREAK_SIZE_FACTOR: Double = 0.5

    /** آستانهٔ سخت‌تر: ۵ باخت پیاپی → سایز یک‌چهارم. [ENGINEERING-ADDON] */
    const val HARD_LOSS_STREAK: Int = 5
    const val HARD_LOSS_STREAK_SIZE_FACTOR: Double = 0.25

    /** شروع کوچک‌کردن سایز بر اساس افت سرمایه از قله. [ENGINEERING-ADDON، هم‌راستا با R23] */
    const val DRAWDOWN_HALVE_AT_PCT: Double = 5.0

    /** افت ۱۰٪ → سایز یک‌چهارم. [ENGINEERING-ADDON] */
    const val DRAWDOWN_QUARTER_AT_PCT: Double = 10.0

    /**
     * R23 — «سیستم را بر اساس دردی که تحمل می‌کنی بساز». اگر افت از این حد بگذرد، لایه ورود
     * خودکار را قفل می‌کند تا کاربر تصمیم بگیرد (نه اینکه سیستم در دراودان رها شود).
     * [ENGINEERING-ADDON] با پیش‌فرض محافظه‌کار ۱۵٪ و قابل تنظیم.
     */
    const val DEFAULT_HARD_STOP_DRAWDOWN_PCT: Double = 15.0

    /**
     * R38 — «سایز را برای بدترین سناریو بده». اگر ATR همان تایم‌فریم از این درصد قیمت بیشتر
     * باشد، بازار «پرنوسان» شمرده می‌شود و سایز نصف می‌شود.
     *
     * عدد ۰٫۳۰٪ از اندازه‌گیری آزمایشگاه آمد، نه از کتاب: در رژیم ساختگی «نوسان بالا»
     * (ATR/قیمت ≈ ۰٫۴۲٪) این تنها رژیمی بود که انتظار مثبت نداشت؛ در رژیم‌های عادی
     * ATR/قیمت ≈ ۰٫۱۶٪ است. پس آستانه وسط این دو گذاشته شد. [ENGINEERING-ADDON]
     */
    const val HIGH_VOLATILITY_ATR_PCT: Double = 0.30

    /** ضریب سایز در بازار پرنوسان (R38 + R34: «ریسک ۰٪ در بازار پرنوسان مجاز است»). */
    const val HIGH_VOLATILITY_SIZE_FACTOR: Double = 0.5

    /** همبستگی فرضی دو پوزیشن هم‌خانواده برای «حرارت پورتفو». [ENGINEERING-ADDON] */
    const val DEFAULT_CORRELATION: Double = 0.8

    /** آستانهٔ درجهٔ A ستاپ (بخش ۴ سند: A ≥ ۸۰ · B ۶۰–۷۹ · C < ۶۰). [ENGINEERING-ADDON] */
    const val GRADE_A_MIN: Double = 80.0
    const val GRADE_B_MIN: Double = 60.0

    // ───────────────────────────────────────────────────────────────────────────
    // ۲) ریاضیات کتاب
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * R01 — حداقل نرخ برد لازم برای نقطهٔ سربه‌سر: `1 ÷ (1 + R)`.
     *
     * جدول کتاب: R:R 1:1 → >۵۰٪ · 1:2 → >۳۳٪ · 1:3 → >۲۵٪ · 1:5 → >۱۷٪. این تابع همان
     * جدول را تولید می‌کند و در سند به‌درستی [ENGINEERING-ADDON، هم‌خوان با جدول کتاب] برچسب
     * خورده است.
     */
    fun neededWinRatePct(riskReward: Double): Double {
        if (!riskReward.isFinite() || riskReward <= 0.0) return 100.0
        return 100.0 / (1.0 + riskReward)
    }

    /**
     * R29 — «افت ۵۰٪ به ۱۰۰٪ بازده برای جبران نیاز دارد»: `1/(1−d) − 1`.
     * ورودی درصد افت (۵۰ یعنی ۵۰٪)، خروجی درصد بازده لازم. [ENGINEERING-ADDON، هم‌خوان با مثال کتاب]
     */
    fun recoveryReturnPct(drawdownPct: Double): Double {
        val d = drawdownPct / 100.0
        if (!d.isFinite() || d <= 0.0) return 0.0
        if (d >= 1.0) return Double.POSITIVE_INFINITY
        return (1.0 / (1.0 - d) - 1.0) * 100.0
    }

    /**
     * «حرارت پورتفو» به‌جای جمع سادهٔ ریسک‌ها: `Heat = √(ΣΣ ρij·ri·rj)`.
     *
     * سند (بخش ۴) مثال می‌زند: دو معاملهٔ ۱٪ با همبستگی ۰٫۸ → √(1+1+2·۰٫۸) ≈ ۱٫۸۹٪ — نه ۲٪.
     * این تابع همان عدد را می‌دهد. [ENGINEERING-ADDON]
     */
    fun portfolioHeatPct(risksPct: List<Double>, correlation: Double = DEFAULT_CORRELATION): Double {
        val clean = risksPct.filter { it.isFinite() && it > 0.0 }
        if (clean.isEmpty()) return 0.0
        val rho = correlation.coerceIn(-1.0, 1.0)
        var sumSquares = 0.0
        for (r in clean) sumSquares += r * r
        var cross = 0.0
        for (i in clean.indices) for (j in clean.indices) if (i != j) cross += clean[i] * clean[j]
        val heat = sqrt(max(0.0, sumSquares + rho * cross))
        return if (heat.isFinite()) heat else 0.0
    }

    /**
     * R23 — افت سرمایه **از آخرین قله** سنجیده می‌شود، نه از سرمایهٔ اولیه.
     * مثال کتاب: ۱۰۰ → ۱۱۰ → ۹۹ یعنی افت ۱۰٪ (نه ۱٪).
     *
     * `RiskAndCalendarEngine.evaluateRiskLimits` افت را از `initialCapital` حساب می‌کند (رفتار
     * قدیمی برای «سقف افت» کاربر). این تابع معیار کتاب را جدا نگه می‌دارد تا سایز معامله با
     * عدد درست کوچک شود.
     */
    fun drawdownFromPeakPct(equityUsd: Double, peakEquityUsd: Double): Double {
        if (!equityUsd.isFinite() || !peakEquityUsd.isFinite()) return 0.0
        if (peakEquityUsd <= 0.0) return 0.0
        return max(0.0, (peakEquityUsd - equityUsd) / peakEquityUsd * 100.0)
    }

    /**
     * قلهٔ سرمایه با پیمایش زمانی معاملات بسته‌شده (خروجی `pnlUsd`).
     * ورودی می‌تواند شامل معاملات باز باشد؛ آن‌ها نادیده گرفته می‌شوند.
     */
    fun peakEquityUsd(trades: List<PaperTrade>, initialEquityUsd: Double): Double {
        var equity = initialEquityUsd
        var peak = initialEquityUsd
        trades.filter { !it.isOpen && it.pnlUsd != null }
            .sortedBy { it.closedAt ?: 0L }
            .forEach { t ->
                equity += (t.pnlUsd ?: 0.0) - (t.commissionUsd + t.spreadCostUsd) * 0.0
                if (equity > peak) peak = equity
            }
        return peak
    }

    /** R35 — تعداد باخت‌های پیاپی از آخرین معاملهٔ بسته‌شده به عقب. */
    fun lossStreak(trades: List<PaperTrade>): Int {
        var streak = 0
        trades.filter { !it.isOpen && it.pnlUsd != null }
            .sortedByDescending { it.closedAt ?: 0L }
            .forEach { t ->
                if (streak == 0 && (t.pnlUsd ?: 0.0) < 0.0) streak = 1
                else if (streak > 0 && (t.pnlUsd ?: 0.0) < 0.0) streak++
                else if (streak > 0) return streak
            }
        return streak
    }

    /** مجموع درصد ریسک معاملات باز نسبت به سرمایه. */
    fun openRiskPct(openTrades: List<PaperTrade>, equityUsd: Double): Double {
        if (!equityUsd.isFinite() || equityUsd <= 0.0) return 0.0
        val riskUsd = openTrades.filter { it.isOpen }.sumOf { it.riskUsd }
        return riskUsd / equityUsd * 100.0
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ۳) اندازه‌گیری حد ضرر: نویز، نوسان و جهش
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * R39 — آیا حد ضرر داخل نویز است؟ (تست کتاب: `stop زیر ATR×۱ → رد`)
     *
     * @param stopDistance فاصلهٔ مطلق ورود تا حد ضرر (به قیمت)
     * @param atr میانگین دامنهٔ واقعی همان تایم‌فریم؛ صفر یا منفی = نامعلوم → سنجیده نمی‌شود
     */
    fun stopInsideNoise(
        stopDistance: Double,
        atr: Double,
        multiple: Double = MIN_STOP_ATR_MULTIPLE,
    ): Boolean {
        if (!atr.isFinite() || atr <= 0.0) return false
        if (!stopDistance.isFinite() || stopDistance <= 0.0) return true
        return stopDistance < atr * multiple
    }

    /**
     * «ریسک مؤثر» هر واحد بر اساس بخش ۴ سند:
     * `|Entry − Stop| × ۱٫۵ (ضربه‌گیر نوسان) + لغزش`.
     *
     * ضریب ۱٫۵ همان R38 است [BOOK-EXPLICIT] و لغزش [ENGINEERING-ADDON]. با این کار سایز کمی
     * کوچک‌تر از محاسبهٔ ساده می‌شود و اگر دامنه ۵۰٪ جهش کند، ضرر واقعی هنوز حول ۱٪ می‌ماند.
     */
    fun effectiveRiskPerUnit(
        entry: Double,
        stopLoss: Double,
        slippageBps: Double = DEFAULT_SLIPPAGE_BPS,
        applyVolatilityBuffer: Boolean = true,
    ): Double {
        val dist = abs(entry - stopLoss)
        if (!dist.isFinite() || dist <= 0.0) return 0.0
        val shocked = if (applyVolatilityBuffer) dist * RANGE_SHOCK_MULTIPLIER else dist
        val slip = if (entry.isFinite() && entry > 0.0) entry * (slippageBps / 10_000.0) else 0.0
        return shocked + slip
    }

    /** درجهٔ ستاپ بر اساس امتیاز ۰..۱۰۰ (بخش ۴ سند). */
    fun gradeFor(setupScorePct: Double): SetupGrade = when {
        setupScorePct >= GRADE_A_MIN -> SetupGrade.A
        setupScorePct >= GRADE_B_MIN -> SetupGrade.B
        else -> SetupGrade.C
    }

    /** R31 — سه سطح سایز: بهترین ستاپ‌ها بیشترین سایز، ستاپ ضعیف کوچک‌ترین. [BOOK-EXPLICIT + ADDON] */
    enum class SetupGrade(val labelFa: String, val sizeFactor: Double) {
        A("الف — ستاپ ممتاز", 1.0),
        B("ب — ستاپ معمول", 0.5),
        C("ج — ستاپ ضعیف", 0.25),
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ۴) دروازهٔ ورود: ترکیب همهٔ قواعد
    // ───────────────────────────────────────────────────────────────────────────

    data class Limits(
        val maxRiskPerTradePct: Double = MAX_RISK_PER_TRADE_PCT,
        val maxOpenRiskPct: Double = MAX_OPEN_RISK_PCT,
        val minRiskReward: Double = MIN_RISK_REWARD,
        val minStopAtrMultiple: Double = MIN_STOP_ATR_MULTIPLE,
        val hardStopDrawdownPct: Double = DEFAULT_HARD_STOP_DRAWDOWN_PCT,
        val slippageBps: Double = DEFAULT_SLIPPAGE_BPS,
        val blockAveragingDown: Boolean = true,
        val applyVolatilityBuffer: Boolean = true,
        val applySetupGradeTiers: Boolean = true,
        val applyLossStreakScaling: Boolean = true,
        val applyDrawdownScaling: Boolean = true,
        val applyHighVolatilityScaling: Boolean = true,
        val highVolatilityAtrPct: Double = HIGH_VOLATILITY_ATR_PCT,
        val highVolatilitySizeFactor: Double = HIGH_VOLATILITY_SIZE_FACTOR,
        /** وقتی `false` کل لایه خاموش است (کلید کاربر) و رفتار مو‌به‌مو قبلی می‌ماند. */
        val enabled: Boolean = true,
    )

    data class EntryRequest(
        val symbol: String,
        val action: SignalAction,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        /** ATR تایم‌فریم ماشه؛ ۰ = نامعلوم (قاعدهٔ نویز سنجیده نمی‌شود). */
        val atr: Double = 0.0,
        val equityUsd: Double,
        val peakEquityUsd: Double = equityUsd,
        /** ریسکی که موتور پیشنهاد داده، به‌صورت درصد حساب. */
        val requestedRiskPct: Double,
        /** مجموع ریسک پوزیشن‌های باز (درصد حساب) — پیش از این معامله. */
        val openRiskPct: Double = 0.0,
        val lossStreak: Int = 0,
        val setupScorePct: Double = 75.0,
        /** قیمت لحظه‌ای برای تشخیص «میانگین‌گیری روی ضرر» (R36). */
        val livePrice: Double = entry,
        val openTrades: List<PaperTrade> = emptyList(),
        /** سیگنال کمّی وجود دارد؟ (R02/R22: ورود بدون سیگنال ممنوع) */
        val hasQuantifiedSignal: Boolean = true,
        val limits: Limits = Limits(),
    )

    data class Decision(
        val allowed: Boolean,
        /** دلیل‌های **رد** (خالی = مجاز). */
        val reasonsFa: List<String>,
        /** یادداشت‌های **کوچک‌کردن سایز** که ورود را رد نمی‌کنند. */
        val notesFa: List<String> = emptyList(),
        /** ضریب نهایی سایز (۰..۱) که باید روی بودجهٔ ریسک پیشنهادی اعمال شود. */
        val sizeMultiplier: Double,
        /** ریسک مؤثر نهایی بر حسب درصد حساب (بعد از سقف ۱٪ و ضریب‌ها). */
        val effectiveRiskPct: Double,
        val riskUsd: Double,
        val grade: SetupGrade,
        val riskReward: Double,
        val neededWinRatePct: Double,
        val drawdownFromPeakPct: Double,
        val heatPct: Double,
        val stopAtrRatio: Double,
        /** ATR همان تایم‌فریم به‌صورت درصد قیمت (۰ = نامعلوم). */
        val atrPctOfPrice: Double,
        val summaryFa: String,
    ) {
        val blockedFa: Boolean get() = !allowed
    }

    /**
     * داوری ورود خودکار بر پایهٔ ۳۹ قاعدهٔ کتاب.
     *
     * ترتیب بررسی‌ها آگاهانه است: اول «آیا اصلاً سیگنال و پلن معتبری هست؟» (R02/R03/R39)، بعد
     * «آیا حساب اجازه می‌دهد؟» (R32/R34/R23)، بعد «چقدر؟» (R31/R35/R38). هر دلیل رد با شمارهٔ
     * قاعده‌اش برگردانده می‌شود تا در ژورنال و توست، قابل‌ردیابی باشد.
     */
    fun evaluate(request: EntryRequest): Decision {
        val limits = request.limits
        val reasons = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val stopDistance = abs(request.entry - request.stopLoss)
        val targetDistance = abs(request.takeProfit - request.entry)
        val rr = if (stopDistance > 0.0) targetDistance / stopDistance else 0.0
        val needed = neededWinRatePct(rr)
        val atrRatio = if (request.atr > 0.0) stopDistance / request.atr else 0.0
        val atrPct = if (request.atr > 0.0 && request.entry > 0.0) request.atr / request.entry * 100.0 else 0.0
        val dd = drawdownFromPeakPct(request.equityUsd, request.peakEquityUsd)
        val grade = gradeFor(request.setupScorePct)

        // ریسک باز فعلی؛ اگر صفر پاس داده شده، از خود لیست معاملات هم می‌سنجیم (کم‌ترین حالت).
        val openRisk = max(
            request.openRiskPct,
            openRiskPct(request.openTrades, request.equityUsd),
        )
        val requested = if (request.requestedRiskPct.isFinite()) max(0.0, request.requestedRiskPct) else 0.0

        val base = Decision(
            allowed = true,
            reasonsFa = emptyList(),
            notesFa = emptyList(),
            sizeMultiplier = 1.0,
            effectiveRiskPct = requested,
            riskUsd = request.equityUsd * requested / 100.0,
            grade = grade,
            riskReward = rr,
            neededWinRatePct = needed,
            drawdownFromPeakPct = dd,
            heatPct = openRisk,
            stopAtrRatio = atrRatio,
            atrPctOfPrice = atrPct,
            summaryFa = "",
        )

        if (!limits.enabled) {
            return base.copy(
                allowed = true,
                summaryFa = "🎩 عادت‌های معاملاتی: لایه خاموش است (بدون داوری رفتاری)",
            )
        }

        // اعتبار پایه — بدون این‌ها هیچ قاعده‌ای معنا ندارد.
        if (request.equityUsd <= 0.0 || request.entry <= 0.0) {
            reasons += "پارامترهای حساب/قیمت نامعتبر است"
        }
        // R02 / R22 — ورود بدون سیگنال کمّی ممنوع.
        if (!request.hasQuantifiedSignal) {
            reasons += "R02: سیگنال کمّی وجود ندارد؛ «به نظرم بالا می‌رود» ورود نیست"
        }
        // R03 — پاداش ≥ ۲ برابر ریسک.
        if (rr + 1e-9 < limits.minRiskReward) {
            reasons += String.format(
                Locale.US,
                "R03: R:R برابر ۱:%.2f است و کف کتاب ۱:%.1f — برای سربه‌سر شدن %.0f%% برد لازم است",
                rr, limits.minRiskReward, neededWinRatePct(rr),
            )
        }
        // R39 — حد ضرر بیرون از نویز.
        if (request.atr > 0.0 && stopInsideNoise(stopDistance, request.atr, limits.minStopAtrMultiple)) {
            reasons += String.format(
                Locale.US,
                "R39: فاصلهٔ حد ضرر %.0f٪ ATR است (کف %.1f×ATR) — داخل نویز، قبل از اثبات اشتباه‌بودن می‌خورد",
                atrRatio * 100.0, limits.minStopAtrMultiple,
            )
        }
        // R36 — میانگین‌گیری روی ضرر ممنوع.
        if (limits.blockAveragingDown && request.openTrades.isNotEmpty() && request.livePrice > 0.0) {
            val sameSideLosers = request.openTrades.filter { t ->
                t.isOpen &&
                    V1Universe.normalize(t.symbol) == V1Universe.normalize(request.symbol) &&
                    t.action == request.action &&
                    ((t.action == SignalAction.BUY && request.livePrice < t.entry) ||
                        (t.action == SignalAction.SELL && request.livePrice > t.entry))
            }
            if (sameSideLosers.isNotEmpty()) {
                reasons += "R36: روی ${request.symbol} پوزیشن ${request.action.name} در ضرر باز است — " +
                    "افزودن/تکرار در جهت ضرر یعنی میانگین‌گیری روی بازنده (ممنوع)"
            }
        }
        // R23 — افت از قله بیش از تحمل: توقف ورود خودکار.
        if (dd >= limits.hardStopDrawdownPct) {
            reasons += String.format(
                Locale.US,
                "R23: افت سرمایه از قله %.1f%% از حد تحمل %.1f%% گذشته — ورود خودکار قفل است",
                dd, limits.hardStopDrawdownPct,
            )
        }
        // R32 — سقف ریسک هر معامله.
        val cappedRiskPct = min(requested, limits.maxRiskPerTradePct)
        if (requested > limits.maxRiskPerTradePct + 1e-9) {
            // سقف، رد نمی‌کند؛ کوچک می‌کند (R32: «۱٪ زیاد است؟ مشکل حجم حساب است»).
            notes += String.format(
                Locale.US,
                "R32: ریسک درخواستی %.2f%% از سقف ۱%% بیشتر بود → به %.2f%% کاهش یافت",
                requested, cappedRiskPct,
            )
        }

        // ضریب‌های سایز — هیچ‌کدام معامله را رد نمی‌کنند، فقط کوچک می‌کنند.
        //
        // قرارداد: `sizeMultiplier` نسبتی از **همان ریسکی** است که موتور پیشنهاد داده بود؛
        // یعنی ریسک نهایی = requested × sizeMultiplier. سقف ۱٪ هم یک ضریب است، نه یک
        // کسر جدا، وگرنه دو بار اعمال می‌شد (باگ اندازه‌گیری‌شده در تست R32).
        val capFactor = if (requested > 0.0) (cappedRiskPct / requested).coerceIn(0.0, 1.0) else 1.0
        var multiplier = capFactor

        if (limits.applySetupGradeTiers && grade.sizeFactor < 1.0) {
            multiplier *= grade.sizeFactor
            notes += String.format(
                Locale.US,
                "R31: کیفیت ستاپ %.0f٪ → درجهٔ %s (سایز ×%.2f)",
                request.setupScorePct, grade.labelFa, grade.sizeFactor,
            )
        }
        if (limits.applyLossStreakScaling && request.lossStreak >= LOSS_STREAK_TRIGGER) {
            val f = if (request.lossStreak >= HARD_LOSS_STREAK) HARD_LOSS_STREAK_SIZE_FACTOR else LOSS_STREAK_SIZE_FACTOR
            multiplier *= f
            notes += String.format(
                Locale.US,
                "R35: %d باخت پیاپی → سایز ×%.2f (شاید رژیم بازار عوض شده)",
                request.lossStreak, f,
            )
        }
        if (limits.applyDrawdownScaling) {
            when {
                dd >= DRAWDOWN_QUARTER_AT_PCT -> {
                    multiplier *= 0.25
                    notes += String.format(Locale.US, "R23: افت %.1f%% از قله → سایز ×۰٫۲۵", dd)
                }
                dd >= DRAWDOWN_HALVE_AT_PCT -> {
                    multiplier *= 0.5
                    notes += String.format(Locale.US, "R23: افت %.1f%% از قله → سایز ×۰٫۵", dd)
                }
            }
        }

        if (limits.applyHighVolatilityScaling && atrPct >= limits.highVolatilityAtrPct && atrPct > 0.0) {
            multiplier *= limits.highVolatilitySizeFactor
            notes += String.format(
                Locale.US,
                "R38: ATR %.2f%% قیمت (آستانه %.2f%%) → بازار پرنوسان، سایز ×%.2f",
                atrPct, limits.highVolatilityAtrPct, limits.highVolatilitySizeFactor,
            )
        }

        val effectiveRiskPct = (requested * multiplier).coerceAtMost(cappedRiskPct)
        val riskUsd = request.equityUsd * effectiveRiskPct / 100.0
        val afterThisTradePct = openRisk + effectiveRiskPct

        // R34 — مجموع ریسک باز (پس از این معامله) نباید از ۳٪ بگذرد.
        if (afterThisTradePct > limits.maxOpenRiskPct + 1e-9) {
            reasons += String.format(
                Locale.US,
                "R34: با این معامله مجموع ریسک باز %.2f%% می‌شود (سقف ۳%%) — بدترین روز نباید بیش از ۳%% بدهد",
                afterThisTradePct,
            )
        }

        val allowed = reasons.isEmpty() && afterThisTradePct <= limits.maxOpenRiskPct + 1e-9

        val summary = if (allowed) {
            String.format(
                Locale.US,
                "🎩 عادت‌های معاملاتی: مجاز · ریسک %.2f%% (%.2f$) · درجهٔ %s · حرارت باز %.2f%%/%.0f%% · برد لازم %.0f%%",
                effectiveRiskPct, riskUsd, grade.labelFa, afterThisTradePct,
                limits.maxOpenRiskPct, needed,
            )
        } else {
            "🚫 لایهٔ عادت‌های معاملاتی مسدود کرد: " + reasons.joinToString(" · ")
        }

        return base.copy(
            allowed = allowed,
            reasonsFa = reasons,
            notesFa = notes,
            sizeMultiplier = multiplier,
            effectiveRiskPct = effectiveRiskPct,
            riskUsd = riskUsd,
            grade = grade,
            heatPct = afterThisTradePct,
            summaryFa = summary,
        )
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ۵) مدیریت معاملهٔ باز (R28/R30/R37)
    // ───────────────────────────────────────────────────────────────────────────

    data class TrailPlan(
        val shouldMove: Boolean,
        val newStopLoss: Double?,
        val lockBreakeven: Boolean,
        val noteFa: String,
    ) {
        companion object {
            val NONE = TrailPlan(false, null, false, "")
        }
    }

    /**
     * R28 + R37 — قفل سود و حد ضرر متحرک.
     *
     * - پیش از ۱R: حد ضرر جابه‌جا نمی‌شود (R30: حد ضرر «حک‌شده» است).
     * - پس از ۱R: حد ضرر حداکثر به نقطهٔ سربه‌سر می‌آید (R37: سود معنادار نباید به ضرر تبدیل شود).
     * - پس از آن، اگر EMA5 در جهت معامله باشد، حد ضرر پشت آن می‌رود (R28: trailing با EMA5).
     *
     * هیچ حالتی حد ضرر را **دورتر** نمی‌برد؛ این تابع فقط نزدیک می‌کند.
     */
    fun trailStop(
        action: SignalAction,
        entry: Double,
        currentStopLoss: Double,
        currentR: Double,
        ema5: Double = 0.0,
        lockAtR: Double = PROFIT_LOCK_AT_R,
    ): TrailPlan {
        if (!entry.isFinite() || entry <= 0.0) return TrailPlan.NONE
        if (!currentR.isFinite() || currentR < lockAtR) return TrailPlan.NONE
        val isBuy = action == SignalAction.BUY

        var candidate = entry // سربه‌سر
        var note = "R37: سود ≥ ۱R → حد ضرر روی نقطهٔ سربه‌سر"
        if (ema5.isFinite() && ema5 > 0.0) {
            val emaBetter = if (isBuy) ema5 > candidate else ema5 < candidate
            if (emaBetter) {
                candidate = ema5
                note = "R28: حد ضرر متحرک روی EMA5 (سود ≥ ۱R)"
            }
        }

        // هرگز دورتر نشود (R30).
        val improves = if (isBuy) candidate > currentStopLoss else candidate < currentStopLoss
        if (!improves) return TrailPlan.NONE
        return TrailPlan(
            shouldMove = true,
            newStopLoss = candidate,
            lockBreakeven = abs(candidate - entry) < 1e-9,
            noteFa = note,
        )
    }

    /** R30 — آیا حرکت پیشنهادی حد ضرر، آن را دورتر می‌کند؟ (تخلف) */
    fun movesStopAway(action: SignalAction, currentStopLoss: Double, proposedStopLoss: Double): Boolean {
        if (!currentStopLoss.isFinite() || !proposedStopLoss.isFinite()) return false
        return if (action == SignalAction.BUY) proposedStopLoss < currentStopLoss
        else proposedStopLoss > currentStopLoss
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ۶) ممیزی معاملات بسته‌شده (لایهٔ ژورنال/یادگیری)
    // ───────────────────────────────────────────────────────────────────────────

    data class ClosedAudit(
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val winRatePct: Double,
        val avgR: Double,
        val totalR: Double,
        val maxLossR: Double,
        val maxWinR: Double,
        val maxLossStreak: Int,
        val avgNeededWinRatePct: Double,
        val expectancyPositive: Boolean,
        val violationsFa: List<String>,
    )

    /**
     * ممیزی «آیا معامله‌های انجام‌شده با قواعد کتاب سازگار بوده‌اند؟»
     *
     * برای هر معاملهٔ بسته، R واقعی از سود خالص تقسیم بر ریسک اولیه (`fixedRiskUsd`) حساب
     * می‌شود. سه تخلف اندازه‌گیری می‌شود: ضرر بزرگ‌تر از ۱R (نشت ریسک)، نرخ برد کمتر از حد
     * لازم برای همان R:R، و سری باخت طولانی.
     */
    fun auditClosed(trades: List<PaperTrade>): ClosedAudit {
        val closed = trades.filter { !it.isOpen && it.pnlUsd != null }
            .sortedBy { it.closedAt ?: 0L }
        if (closed.isEmpty()) {
            return ClosedAudit(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, false, emptyList())
        }
        val rs = closed.map { t ->
            val risk = t.fixedRiskUsd.takeIf { it > 0.0 } ?: 0.0
            if (risk > 0.0) (t.pnlUsd ?: 0.0) / risk else 0.0
        }
        val wins = closed.count { (it.pnlUsd ?: 0.0) > 0.0 }
        val losses = closed.size - wins
        var streak = 0
        var maxStreak = 0
        closed.forEach { t ->
            if ((t.pnlUsd ?: 0.0) < 0.0) { streak++; if (streak > maxStreak) maxStreak = streak } else streak = 0
        }
        val needed = closed.map { neededWinRatePct(if (it.riskReward > 0.0) it.riskReward else 1.0) }
        val winRate = wins * 100.0 / closed.size
        val violations = mutableListOf<String>()
        val overRisk = closed.filter { t ->
            val risk = t.fixedRiskUsd
            risk > 0.0 && (t.pnlUsd ?: 0.0) < -risk * 1.10
        }
        if (overRisk.isNotEmpty()) {
            violations += "${overRisk.size} معامله بیش از ۱٫۱R ضرر داد (R32: ضرر خالص نباید از ۱٪ حساب بگذرد)"
        }
        val avgNeeded = needed.average()
        if (winRate + 1e-9 < avgNeeded) {
            violations += String.format(
                Locale.US,
                "نرخ برد %.1f%% کمتر از حد لازم %.1f%% برای همین R:R بود (R01)",
                winRate, avgNeeded,
            )
        }
        if (maxStreak >= HARD_LOSS_STREAK) {
            violations += "$maxStreak باخت پیاپی — R35 می‌گوید سایز باید کوچک می‌شد"
        }
        return ClosedAudit(
            trades = closed.size,
            wins = wins,
            losses = losses,
            winRatePct = winRate,
            avgR = rs.average(),
            totalR = rs.sum(),
            maxLossR = rs.minOrNull() ?: 0.0,
            maxWinR = rs.maxOrNull() ?: 0.0,
            maxLossStreak = maxStreak,
            avgNeededWinRatePct = avgNeeded,
            expectancyPositive = rs.average() > 0.0,
            violationsFa = violations,
        )
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ۷) ساختار قابل‌نمایش برای UI
    // ───────────────────────────────────────────────────────────────────────────

    /** خلاصهٔ یک‌خطی برای کارت‌ها و توست‌ها. */
    fun summaryLineFa(
        equityUsd: Double,
        peakEquityUsd: Double,
        openTrades: List<PaperTrade>,
        lossStreak: Int,
    ): String {
        val dd = drawdownFromPeakPct(equityUsd, peakEquityUsd)
        val openPct = openRiskPct(openTrades, equityUsd)
        val heat = portfolioHeatPct(
            openTrades.filter { it.isOpen }.map {
                if (equityUsd > 0.0) it.riskUsd / equityUsd * 100.0 else 0.0
            },
        )
        return String.format(
            Locale.US,
            "🎩 عادت‌ها: ریسک باز %.2f%%/%.0f%% · حرارت %.2f%% · افت از قله %.1f%% (بازده لازم %.1f%%) · سری باخت %d",
            openPct, MAX_OPEN_RISK_PCT, heat, dd, recoveryReturnPct(dd), lossStreak,
        )
    }

    /** گرد کردن استاندارد درصد/دلار برای نمایش. */
    fun round2(v: Double): Double = if (v.isFinite()) round(v * 100.0) / 100.0 else 0.0
}
