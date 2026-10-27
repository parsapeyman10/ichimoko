package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.serialization.Serializable

/**
 * مدل‌های استراتژی ایچیموکوی بیت‌کوین (`BTC/USDT`).
 * قانون کلیدی سیستم: در هر لحظه **فقط یک متود (استراتژی)** می‌تواند فعال باشد؛
 * به محض فعال‌سازی هر استراتژی، استراتژی قبلی به‌صورت خودکار غیرفعال می‌شود.
 */
enum class BitcoinStrategyModel(
    val id: String,
    val titleFa: String,
    val badgeFa: String,
    val authorRefFa: String,
    val summaryFa: String,
) {
    BASE_ICHIMOKU(
        id = "BASE_ICHIMOKU",
        titleFa = "استراتژی بیس (جامع ۴ کتاب ایچیموکو)",
        badgeFa = "استراتژی بیس · پایه قابل ترمیم",
        authorRefFa = "الیوت + تاتل + پاتل + پلویل",
        summaryFa = "معماری ۳ تایم‌فریمی (4H/1H/15m)، چک‌لیست ۶ شرطی (ابر فعلی، شیب TK، ابر آینده، فضای باز چیکو، کش لاستیکی کیجون، فضای تارگت)، ماشه پولبک/شکست با فیلتر فلت SSB، اهداف موجی هوسودا (V/N/E) و حالت حفظ سود (Preserve Mode).",
    ),
    PATEL_KIJUN_PULLBACK(
        id = "PATEL_KIJUN_PULLBACK",
        titleFa = "مدل پولبک تعادلی کیجون + Preserve Mode",
        badgeFa = "مدل منش پاتل",
        authorRefFa = "Manesh Patel (Wiley 2010)",
        summaryFa = "تمرکز صرف بر پولبک قیمت به خط تعادل Kijun-sen در روند هم‌جهت ۳ تایم‌فریم، ورود پس از بسته شدن کندل بالای Tenkan-sen و قفل کردن سود با Preserve Mode روی تنکان.",
    ),
    TUTTLE_KUMO_BREAKOUT(
        id = "TUTTLE_KUMO_BREAKOUT",
        titleFa = "مدل شکست کومو با فیلتر سقف چیکو",
        badgeFa = "مدل مارک تاتل",
        authorRefFa = "Mark L. Tuttle (Kumo Trader)",
        summaryFa = "شکار شکست‌های پرقدرت ابر کومو با شرط هم‌جهتی ابر آینده و عبور قطعی از آخرین قله/دره خط Chikou Span برای حذف شکست‌های فیک ناشی از جاذبه فلت Senkou Span B.",
    ),
    ELLIOTT_HOSODA_WAVE(
        id = "ELLIOTT_HOSODA_WAVE",
        titleFa = "مدل امواج N هوسودا و اعداد زمانی",
        badgeFa = "مدل نیکول الیوت",
        authorRefFa = "Nicole Elliott (Harriman House)",
        summaryFa = "سوار شدن بر موج سوم هوسودا (موج N از ساختار A-B-C) روی نقطه S (تبدیل سقف قبلی به حمایت) با محاسبه دقیق اهداف قیمتی V، N، E و پایش اعداد زمانی 9، 17، 26 و 33.",
    ),
    PELOILLE_MTF_PLATEAU(
        id = "PELOILLE_MTF_PLATEAU",
        titleFa = "مدل پلاتوهای افقی SSB/Kijun سه‌تایم‌فریمی",
        badgeFa = "مدل کارن پلویل",
        authorRefFa = "Karen Péloille (Trading with Ichimoku)",
        summaryFa = "استخراج دیوارهای حمایت و مقاومت از هم‌راستایی فلت‌های Kijun و Senkou Span B در سه تایم‌فریم با الزام بسته شدن کامل کندل و آزادی کامل حرکتی چیکو.",
    );

    companion object {
        fun fromId(raw: String?): BitcoinStrategyModel =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: BASE_ICHIMOKU
    }
}

/**
 * پارامترهای قابل تنظیم و ترمیم برای «استراتژی بیس» (Base Strategy) و مدل‌های مشتق‌شده.
 */
@Serializable
data class BaseStrategyConfig(
    val macroIntervalLabel: String = "4H",
    val strategyIntervalLabel: String = "1H",
    val triggerIntervalLabel: String = "15m",
    val tenkanPeriod: Int = 9,
    val kijunPeriod: Int = 26,
    val senkouBPeriod: Int = 52,
    val displacement: Int = 26,
    val maxKijunExtensionAtr: Double = 1.8,
    val slBufferAtr: Double = 0.25,
    val breakoutBufferAtr: Double = 0.15,
    val minRiskReward: Double = 1.5,
    val enablePullbackTrigger: Boolean = true,
    val enableBreakoutTrigger: Boolean = true,
    val enablePreserveMode: Boolean = true,
    val requireHigherTfAlignment: Boolean = true,
    val requireChikouOpenSpace: Boolean = true,
    val requireFutureKumoSlope: Boolean = true,
) {
    val macroInterval: Interval get() = Interval.fromLabel(macroIntervalLabel)
    val strategyInterval: Interval get() = Interval.fromLabel(strategyIntervalLabel)
    val triggerInterval: Interval get() = Interval.fromLabel(triggerIntervalLabel)
}

/**
 * اسلات استراتژی سفارشی برای مدل‌هایی که کاربر در مراحل بعدی اضافه یا شخصی‌سازی می‌کند.
 */
@Serializable
data class CustomStrategySlot(
    val id: String,
    val titleFa: String,
    val badgeFa: String = "مدل سفارشی",
    val summaryFa: String,
    val baseModelId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
    val config: BaseStrategyConfig = BaseStrategyConfig(),
)

/**
 * خروجی محاسبه دقیق ۵ خط ایچیموکو + ATR روی یک تایم‌فریم مشخص.
 */
data class IchimokuBarSnapshot(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val tenkan: Double,
    val kijun: Double,
    val prevTenkan: Double,
    val prevKijun: Double,
    val currentSpanA: Double,
    val currentSpanB: Double,
    val futureSpanA: Double,
    val futureSpanB: Double,
    val prevFutureSpanA: Double,
    val prevFutureSpanB: Double,
    val atr14: Double,
    val chikouBullishOpenSpace: Boolean,
    val chikouBearishOpenSpace: Boolean,
    val chikouResistancePeak: Double,
    val chikouSupportTrough: Double,
    val isCurrentSpanBFlat: Boolean,
    val isKijunFlat: Boolean,
    val isTenkanFlat: Boolean,
    val flatEquilibriumLevels: List<Double>,
    val candleBodyRatio: Double = 0.5,
    val closeLocationValue: Double = 0.5,
    val volumeRatio20: Double = 1.0,
    val kijunSlopeAtrPerBar: Double = 0.0,
    val tenkanSlopeAtrPerBar: Double = 0.0,
    val futureKumoThicknessAtr: Double = 0.2,
    val currentKumoThicknessAtr: Double = 0.2,
    val isCClampWarning: Boolean = false,
    val isKatanaEquilibrium: Boolean = false,
    val threeCandleBullishImpulse: Boolean = false,
    val threeCandleBearishImpulse: Boolean = false,
    val efficiencyRatio14: Double = 0.5,
    val upperWickRatio: Double = 0.1,
    val lowerWickRatio: Double = 0.1,
    val recentKatanaRelease: Boolean = false,
) {
    val kumoTop: Double get() = max(currentSpanA, currentSpanB)
    val kumoBottom: Double get() = min(currentSpanA, currentSpanB)
    val kumoThickness: Double get() = abs(currentSpanA - currentSpanB)
    val isPriceAboveKumo: Boolean get() = close > kumoTop
    val isPriceBelowKumo: Boolean get() = close < kumoBottom
    val isPriceInsideKumo: Boolean get() = !isPriceAboveKumo && !isPriceBelowKumo
    val isFutureKumoBullish: Boolean get() = futureSpanA > futureSpanB
    val isFutureKumoBearish: Boolean get() = futureSpanA < futureSpanB
    val isFutureSpanARising: Boolean get() = futureSpanA >= prevFutureSpanA - 1e-8
    val isFutureSpanAFalling: Boolean get() = futureSpanA <= prevFutureSpanA + 1e-8
    val isFutureSpanBNonFalling: Boolean get() = futureSpanB >= prevFutureSpanB - 1e-8
    val isFutureSpanBNonRising: Boolean get() = futureSpanB <= prevFutureSpanB + 1e-8
    val distanceToKijun: Double get() = abs(close - kijun)
    val distanceToKijunAtr: Double get() = if (atr14 > 0.0) distanceToKijun / atr14 else 0.0
    val distanceToTenkan: Double get() = abs(close - tenkan)
    val sanyakuBullishOrder: Boolean
        get() = close > kumoTop && tenkan >= kijun - atr14 * 0.05 && kijun >= kumoBottom - atr14 * 0.10
    val sanyakuBearishOrder: Boolean
        get() = close < kumoBottom && tenkan <= kijun + atr14 * 0.05 && kijun <= kumoTop + atr14 * 0.10
}

data class IchimokuChartBarOverlay(
    val index: Int,
    val candle: Candle,
    val tenkan: Double,
    val kijun: Double,
    val currentSpanA: Double,
    val currentSpanB: Double,
    val chikouPrice: Double?,
)

data class FutureKumoPoint(
    val stepAhead: Int,
    val spanA: Double,
    val spanB: Double,
)

data class IchimokuChartSeries(
    val bars: List<IchimokuChartBarOverlay>,
    val futureKumo: List<FutureKumoPoint>,
)

/**
 * ساختار موج ۳ نقطه‌ای هوسودا (A -> B -> C) و ۴ هدف قیمتی (V, N, E, NT) + شمارش زمانی.
 */
data class HosodaWaveProjection(
    val direction: SignalAction,
    val pointA: Double,
    val pointB: Double,
    val pointC: Double,
    val sPointHeld: Boolean,
    val targetV: Double,
    val targetN: Double,
    val targetE: Double,
    val targetNT: Double,
    val barsSinceWaveStart: Int,
    val nearestTimeCycleNumber: Int,
    val isNearTimeCycleChangeDay: Boolean,
)

enum class TrendPhase(val titleFa: String, val badgeFa: String) {
    GENESIS("تولد اصل روند (شروع موج ۱/۳)", "🚀 شروع اصل روند"),
    CORE_EXPANSION("متن اصلی روند (گسترش موج N)", "🔥 قلب روند اصلی"),
    LATE_EXHAUSTION("اشباع و انتهای موج (ممنوعیت ورود جدید)", "⚠️ اشباع انتهای روند"),
    CHOP_RANGE("فشردگی / رِنج فرسایشی (فیلتر خطا)", "🛑 رِنج و نوسان کاذب"),
}

enum class StrategyTriggerType(val titleFa: String) {
    EARLY_KATANA_GENESIS("ماشه ۰ (شکار اول روند): فوران کاتانا/کومو در لحظه تولد موج اصلی"),
    PULLBACK_BOUNCE("ماشه ۱: پولبک به تعادل (Kijun/Tenkan/S-Point) و کلوز بالای تنکان"),
    VALIDATED_BREAKOUT("ماشه ۲: شکست معتبر کومو/کیجون با عبور از سقف/کف چیکو و بافر فلت SSB"),
    SANYAKU_TK_CONTINUATION("ماشه ۳: ادامه موج سوم سانیاکو با تایید ۳ کندلی کارن پلویل"),
    NONE("در انتظار کندل بسته ماشه"),
}

enum class StrategyReadinessState(val labelFa: String) {
    SIGNAL_BUY("سیگنال خرید (Long) آماده شلیک"),
    SIGNAL_SELL("سیگنال فروش (Short) آماده شلیک"),
    WATCH_SETUP("همگرایی تایم‌فریم بالا برقرار؛ در انتظار ماشه ورود"),
    BLOCKED("قفل توسط فیلترهای استراتژی"),
    INSUFFICIENT_DATA("در حال دریافت کندل‌های مولتی‌تایم‌فریم"),
}

/**
 * برنامه‌ریز هوشمند زمان‌بندی بررسی مجدد و بهینه‌سازی مصرف اینترنت برای تایم‌فریم معامله ۱۵ دقیقه (`15m`)
 * روی هر ۱۵ نماد داخل اپ (`V1Universe.allSymbols`):
 *
 * ۱. همگام‌سازی دقیق با بسته شدن کندل ۱۵ دقیقه (`:00`، `:15`، `:30`، `:45` + ۳ ثانیه فرصت تسویه صرافی):
 *    هیچ کندل بسته‌شده‌ای از دست نمی‌رود و بین دو کندل ۱۵ دقیقه‌ای دانلود تکراری تاریخچه انجام نمی‌شود.
 * ۲. دریافت فقط کندل‌های جدید (`Delta Candle Sync`):
 *    پس از بارگذاری اولیه، در هر مرز ۱۵ دقیقه فقط کندل‌های جدید (`۳ تا ۵ کندل`) دانلود و به کش اضافه می‌شوند
 *    و کندل‌های `1H` و `4H` فقط سر ساعت و هر ۴ ساعت بروزرسانی می‌گردند.
 * ۳. سطح‌بندی هوشمند تیک قیمت زنده (`4-Tier Adaptive Polling`):
 *    - سطح ۱ (نمادهای دارای معامله باز برای رصد TP/SL و Preserve Mode): هر ۱۵ ثانیه
 *    - سطح ۲ (نماد منتخب یا نمادهای در آستانه ماشه `WATCH_SETUP`): هر ۶۰ ثانیه + چک میان‌کندلی در دقیقه ۷:۳۰
 *    - سطح ۳ (سایر نمادهای باز از ۱۵ نماد): اسکن کندل در مرز ۱۵ دقیقه + تیک قیمت هر ۱۸۰ ثانیه (۳ دقیقه)
 *    - سطح ۴ (نمادهای در ساعت تعطیلی بازار مثل فارکس/طلا در آخر هفته): توقف کامل (`۰ کیلوبایت`)
 */
enum class SymbolPollTier(
    val titleFa: String,
    val tickIntervalMs: Long,
    val descriptionFa: String,
) {
    OPEN_POSITION(
        titleFa = "سطح ۱ · معامله باز (TP/SL)",
        tickIntervalMs = 15_000L,
        descriptionFa = "بررسی هر ۱۵ ثانیه برای حفظ دقیق حد سود/ضرر و Preserve Mode",
    ),
    WATCH_OR_FOCUSED(
        titleFa = "سطح ۲ · آماده ماشه / منتخب",
        tickIntervalMs = 60_000L,
        descriptionFa = "بررسی هر ۶۰ ثانیه + چک میان‌کندلی ۷.۵ دقیقه",
    ),
    ACTIVE_15M_RADAR(
        titleFa = "سطح ۳ · رادار ۱۵ دقیقه",
        tickIntervalMs = 180_000L,
        descriptionFa = "اسکن کامل راس بسته شدن کندل ۱۵m (:00/:15/:30/:45) + تیک هر ۳ دقیقه",
    ),
    MARKET_CLOSED_PAUSED(
        titleFa = "سطح ۴ · سشن بسته (توقف دیتا)",
        tickIntervalMs = Long.MAX_VALUE,
        descriptionFa = "توقف کامل درخواست شبکه در ساعات تعطیلی بازار برای جلوگیری از مصرف اینترنت",
    ),
}

object Smart15mSyncPlanner {
    const val CANDLE_SETTLE_DELAY_MS = 3_000L
    const val OPEN_POSITION_TICK_MS = 15_000L
    const val WATCH_SYMBOL_TICK_MS = 60_000L
    const val RADAR_SYMBOL_TICK_MS = 180_000L
    const val MID_BAR_CHECK_OFFSET_MS = 450_000L // 7m 30s inside a 15m candle

    /**
     * شماره باکت کندل فعلی بر اساس تایم‌فریم (با احتساب ۳ ثانیه زمان نهایی شدن کندل در سرور).
     */
    fun currentCandleBucket(nowMillis: Long, interval: Interval): Long {
        val adjusted = (nowMillis - CANDLE_SETTLE_DELAY_MS).coerceAtLeast(0L)
        return adjusted / interval.millis
    }

    /**
     * زمان دقیق (بر حسب میلی‌ثانیه) بسته شدن کندل بعدی تایم‌فریم معامله (پیش‌فرض `15m`) به علاوه ۳ ثانیه.
     */
    fun nextTriggerBoundaryCheckMillis(
        nowMillis: Long,
        triggerInterval: Interval = Interval.M15,
    ): Long {
        val period = triggerInterval.millis
        val currentBucket = currentCandleBucket(nowMillis, triggerInterval)
        return (currentBucket + 1L) * period + CANDLE_SETTLE_DELAY_MS
    }

    /**
     * زمان چک میان‌کندلی (دقیقه ۷:۳۰ برای کندل ۱۵ دقیقه) جهت نمادهایی که ۴ یا ۵ شرط از ۶ شرط را دارند.
     */
    fun nextMidBarOrBoundaryCheckMillis(
        nowMillis: Long,
        triggerInterval: Interval = Interval.M15,
    ): Long {
        val period = triggerInterval.millis
        val bucketStart = currentCandleBucket(nowMillis, triggerInterval) * period
        val midPoint = bucketStart + (period / 2L) + CANDLE_SETTLE_DELAY_MS
        return if (nowMillis < midPoint) {
            midPoint
        } else {
            nextTriggerBoundaryCheckMillis(nowMillis, triggerInterval)
        }
    }

    /**
     * ثانیه‌های باقی‌مانده تا بسته شدن کندل ۱۵ دقیقه‌ای بعدی.
     */
    fun secondsUntilNextBoundary(
        nowMillis: Long,
        triggerInterval: Interval = Interval.M15,
    ): Long = ((nextTriggerBoundaryCheckMillis(nowMillis, triggerInterval) - nowMillis) / 1000L).coerceAtLeast(0L)

    /**
     * آیا برای این تایم‌فریم کندل جدیدی بسته شده است که نیاز به درخواست شبکه داشته باشد؟
     * اگر در همان باکت زمانی باشیم و حداقل ۷۵ کندل در کش داشته باشیم، نیازی به دانلود مجدد نیست.
     */
    fun shouldFetchCandlesFromNetwork(
        lastCachedBucket: Long?,
        cachedBarCount: Int,
        nowMillis: Long,
        interval: Interval,
        minRequiredBars: Int = 75,
    ): Boolean {
        if (lastCachedBucket == null || cachedBarCount < minRequiredBars) return true
        val nowBucket = currentCandleBucket(nowMillis, interval)
        return nowBucket > lastCachedBucket
    }

    /**
     * محاسبه تعداد کندل موردنیاز برای دانلود (Delta Sync):
     * اگر کش قبلی موجود باشد، فقط کندل‌های جدیدِ تشکیل‌شده از آخرین کندل بسته‌شده درخواست می‌شود
     * تا هیچ دیتایی از دست نرود و مصرف اینترنت بیش از ۹۵٪ کاهش یابد.
     */
    fun computeOptimalFetchLimit(
        lastClosedBarTimeMs: Long?,
        cachedBarCount: Int,
        nowMillis: Long,
        interval: Interval,
        fullLimit: Int = 110,
        minRequiredBars: Int = 75,
    ): Int {
        if (lastClosedBarTimeMs == null || cachedBarCount < minRequiredBars) return fullLimit
        val elapsedMs = (nowMillis - lastClosedBarTimeMs).coerceAtLeast(0L)
        val missedBars = (elapsedMs / interval.millis).toInt()
        if (missedBars <= 0) return 3
        return (missedBars + 3).coerceIn(4, fullLimit)
    }

    /**
     * تعیین سطح اولویت و فاصله زمانی بررسی مجدد تیک قیمت برای هر یک از ۱۵ نماد.
     */
    fun classifySymbolTier(
        symbol: String,
        hasOpenTrade: Boolean,
        isFocusedSymbol: Boolean,
        readiness: StrategyReadinessState?,
        nowMillis: Long = System.currentTimeMillis(),
    ): SymbolPollTier {
        val normalized = V1Universe.normalize(symbol)
        val isClosed = MarketHours.closedFor(normalized, nowMillis)
        if (isClosed && !hasOpenTrade) {
            return SymbolPollTier.MARKET_CLOSED_PAUSED
        }
        if (hasOpenTrade) {
            return SymbolPollTier.OPEN_POSITION
        }
        if (isFocusedSymbol ||
            readiness == StrategyReadinessState.SIGNAL_BUY ||
            readiness == StrategyReadinessState.SIGNAL_SELL ||
            readiness == StrategyReadinessState.WATCH_SETUP
        ) {
            return SymbolPollTier.WATCH_OR_FOCUSED
        }
        return SymbolPollTier.ACTIVE_15M_RADAR
    }

    /**
     * آیا زمان دریافت تیک قیمت زنده برای این نماد فرا رسیده است؟
     */
    fun isTickPollDue(
        lastTickAtMs: Long?,
        tier: SymbolPollTier,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (tier == SymbolPollTier.MARKET_CLOSED_PAUSED) return lastTickAtMs == null
        if (lastTickAtMs == null || lastTickAtMs <= 0L) return true
        return (nowMillis - lastTickAtMs) >= tier.tickIntervalMs
    }
}

data class StrategyConditionCheck(
    val index: Int,
    val titleFa: String,
    val passed: Boolean,
    val detailFa: String,
)

data class PreserveModeEvaluation(
    val tradeId: String,
    val newStopLoss: Double,
    val stageTitleFa: String,
    val reasonFa: String,
)

data class StrategyEvaluationReport(
    val symbol: String = IchimokuStrategyEngine.BTC_SYMBOL,
    val strategyId: String,
    val strategyTitleFa: String,
    val strategyBadgeFa: String,
    val macroInterval: Interval,
    val strategyInterval: Interval,
    val triggerInterval: Interval,
    val readiness: StrategyReadinessState,
    val action: SignalAction,
    val triggerType: StrategyTriggerType,
    val confidence: Double,
    val livePrice: Double,
    val suggestedEntry: Double?,
    val suggestedStopLoss: Double?,
    val targetV: Double?,
    val targetN: Double?,
    val targetE: Double?,
    val primaryTakeProfit: Double?,
    val riskReward: Double?,
    val macroSnapshot: IchimokuBarSnapshot?,
    val strategySnapshot: IchimokuBarSnapshot?,
    val triggerSnapshot: IchimokuBarSnapshot?,
    val waveProjection: HosodaWaveProjection?,
    val conditions: List<StrategyConditionCheck>,
    val summaryFa: String,
    val triggerBarTime: Long,
    val evaluatedAt: Long = System.currentTimeMillis(),
    val trendPhase: TrendPhase = TrendPhase.CORE_EXPANSION,
    val coreTrendScore: Double = 0.0,
    val sniperOptimalEntry: Double? = null,
    val sniperZoneLow: Double? = null,
    val sniperZoneHigh: Double? = null,
    val sniperEntryScore: Double = 0.0,
    val isSniperArmedPendingRetest: Boolean = false,
) {
    val entryTimeframeBadgeFa: String
        get() = "تایم ورود: ${triggerInterval.label}"

    fun readinessShortFa(): String {
        val passed = conditions.count { it.passed }
        val total = conditions.size.takeIf { it > 0 } ?: 16
        return when {
            readiness == StrategyReadinessState.SIGNAL_BUY -> "🟢 خرید ($passed/$total)"
            readiness == StrategyReadinessState.SIGNAL_SELL -> "🔴 فروش ($passed/$total)"
            isSniperArmedPendingRetest -> "🎯 مسلح ($passed/$total)"
            readiness == StrategyReadinessState.WATCH_SETUP -> "🟡 آماده ($passed/$total)"
            conditions.isNotEmpty() -> "$passed/$total"
            else -> "..."
        }
    }

    fun isPriceInSniperWindow(price: Double): Boolean {
        val low = sniperZoneLow ?: return false
        val high = sniperZoneHigh ?: return false
        return price in low..high
    }
    val isActionable: Boolean
        get() = (readiness == StrategyReadinessState.SIGNAL_BUY || readiness == StrategyReadinessState.SIGNAL_SELL) &&
            action != SignalAction.NO_TRADE &&
            suggestedEntry != null && suggestedStopLoss != null && primaryTakeProfit != null

    fun toAiStrategyContext(): String = buildString {
        append("تایم‌فریم ورود: ").append(triggerInterval.label).append(" | ")
        append("استراتژی فعال: ").append(strategyTitleFa).append(" (").append(strategyId).append(") | ")
        append("نماد: ").append(symbol).append(" | ")
        append("تایم‌فریم‌ها: ").append(macroInterval.label).append("/")
            .append(strategyInterval.label).append("/").append(triggerInterval.label).append(" | ")
        append("ماشه: ").append(triggerType.titleFa).append(" | ")
        waveProjection?.let { w ->
            append("امواج هوسودا A=").append(round2(w.pointA))
                .append(", B=").append(round2(w.pointB))
                .append(", C=").append(round2(w.pointC))
                .append(" -> TP1(V)=").append(round2(w.targetV))
                .append(", TP2(N)=").append(round2(w.targetN))
                .append(", TP3(E)=").append(round2(w.targetE))
                .append(", کندل موج=").append(w.barsSinceWaveStart)
                .append(" (چرخه زمانی نزدیک: ").append(w.nearestTimeCycleNumber).append(") | ")
        }
        val passedCount = conditions.count { it.passed }
        append("شروط برقرار: ").append(passedCount).append("/").append(conditions.size).append(" — ")
        append(conditions.joinToString("؛ ") { "${it.titleFa}: ${if (it.passed) "تایید" else "رد"} (${it.detailFa})" })
    }
}

/**
 * گزارش هم‌زمان دو تایم‌فریم ورود (`15m` و `5m`) برای یک نماد با اشتراک‌گذاری کندل‌های `4H`/`1H`/`15m` بدون مصرف اضافه دیتا.
 */
data class DualTimeframeSymbolReport(
    val symbol: String,
    val report15m: StrategyEvaluationReport,
    val report5m: StrategyEvaluationReport,
    val reports15mByStrategy: Map<String, StrategyEvaluationReport> = mapOf(report15m.strategyId to report15m),
    val reports5mByStrategy: Map<String, StrategyEvaluationReport> = mapOf(report5m.strategyId to report5m),
) {
    fun reportFor(interval: Interval, strategyId: String? = null): StrategyEvaluationReport {
        val map = if (interval == Interval.M5) reports5mByStrategy else reports15mByStrategy
        if (strategyId != null) {
            map[strategyId]?.let { return it }
        }
        return if (interval == Interval.M5) report5m else report15m
    }

    val allReports15m: List<StrategyEvaluationReport>
        get() = reports15mByStrategy.values.toList().ifEmpty { listOf(report15m) }

    val allReports5m: List<StrategyEvaluationReport>
        get() = reports5mByStrategy.values.toList().ifEmpty { listOf(report5m) }

    val primaryReport: StrategyEvaluationReport
        get() = when {
            report15m.isActionable && report5m.isActionable ->
                if (report5m.coreTrendScore > report15m.coreTrendScore + 5.0) report5m else report15m
            report15m.isActionable -> report15m
            report5m.isActionable -> report5m
            report15m.isSniperArmedPendingRetest -> report15m
            report5m.isSniperArmedPendingRetest -> report5m
            report15m.readiness == StrategyReadinessState.WATCH_SETUP -> report15m
            report5m.readiness == StrategyReadinessState.WATCH_SETUP -> report5m
            else -> if (report5m.coreTrendScore > report15m.coreTrendScore) report5m else report15m
        }

    val actionableReports: List<StrategyEvaluationReport>
        get() = (allReports15m + allReports5m)
            .filter { it.isActionable }
            .sortedByDescending { it.coreTrendScore }

    val sniperArmedReports: List<StrategyEvaluationReport>
        get() = (allReports15m + allReports5m)
            .filter { it.isActionable || it.isSniperArmedPendingRetest }
            .sortedByDescending { it.coreTrendScore }

    val combinedSummaryFa: String
        get() = "[15m: ${report15m.readinessShortFa()}] · [5m: ${report5m.readinessShortFa()}]"
}

object IchimokuStrategyEngine {

    const val BTC_SYMBOL = "BTC/USDT"

    /** اعداد پایه و ترکیبی چرخه زمانی گوایچی هوسودا (Jikan Ron / Kihon Suchi) */
    val HOSODA_TIME_CYCLES = listOf(9, 17, 26, 33, 42, 65, 76, 129, 172)

    /**
     * محاسبه دقیق اسنپ‌شات ایچیموکو روی آخرین کندل بسته‌شده یک تایم‌فریم.
     * قانون کارن پلویل: همیشه روی کندل‌های بسته‌شده (Closed) تصمیم‌گیری می‌شود تا از سیگنال‌های لحظه‌ای کاذب جلوگیری شود.
     */
    fun computeSnapshot(
        candlesInput: List<Candle>,
        config: BaseStrategyConfig = BaseStrategyConfig(),
    ): IchimokuBarSnapshot? {
        val ordered = candlesInput
            .filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.high >= it.low && it.close > 0.0 }
            .sortedBy { it.time }
        val closedBars = ordered.filter { it.closed }.ifEmpty { ordered }
        val minBarsNeeded = max(config.senkouBPeriod + 5, 35)
        if (closedBars.size < minBarsNeeded) return null

        val lastIdx = closedBars.lastIndex
        val prevIdx = (lastIdx - 1).coerceAtLeast(0)

        fun midpoint(fromIdx: Int, toIdx: Int): Double {
            val start = fromIdx.coerceIn(0, toIdx)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (k in start..toIdx) {
                if (closedBars[k].high > hh) hh = closedBars[k].high
                if (closedBars[k].low < ll) ll = closedBars[k].low
            }
            return (hh + ll) / 2.0
        }

        fun tenkanAt(idx: Int): Double = midpoint(idx - (config.tenkanPeriod - 1), idx)
        fun kijunAt(idx: Int): Double = midpoint(idx - (config.kijunPeriod - 1), idx)
        fun spanBAtCreation(idx: Int): Double = midpoint(idx - (config.senkouBPeriod - 1), idx)
        fun spanAAtCreation(idx: Int): Double = (tenkanAt(idx) + kijunAt(idx)) / 2.0

        val disp = (config.displacement - 1).coerceAtLeast(1)
        fun currentSpanAAt(idx: Int): Double {
            val src = (idx - disp).coerceAtLeast(0)
            return spanAAtCreation(src)
        }
        fun currentSpanBAt(idx: Int): Double {
            val src = (idx - disp).coerceAtLeast(0)
            return spanBAtCreation(src)
        }

        val tenkan = tenkanAt(lastIdx)
        val kijun = kijunAt(lastIdx)
        val prevTenkan = tenkanAt(prevIdx)
        val prevKijun = kijunAt(prevIdx)

        val currentSpanA = currentSpanAAt(lastIdx)
        val currentSpanB = currentSpanBAt(lastIdx)

        val futureSpanA = spanAAtCreation(lastIdx)
        val futureSpanB = spanBAtCreation(lastIdx)
        val prevFutureSpanA = spanAAtCreation(prevIdx)
        val prevFutureSpanB = spanBAtCreation(prevIdx)

        // ATR(14) calculation
        val atrPeriod = min(14, closedBars.size - 1).coerceAtLeast(1)
        var trSum = 0.0
        for (i in (lastIdx - atrPeriod + 1)..lastIdx) {
            val cur = closedBars[i]
            val prevClose = closedBars[(i - 1).coerceAtLeast(0)].close
            val tr = maxOf(
                cur.high - cur.low,
                abs(cur.high - prevClose),
                abs(cur.low - prevClose),
            )
            trSum += tr
        }
        val atr14 = (trSum / atrPeriod).coerceAtLeast(closedBars[lastIdx].close * 0.0005)

        // Chikou Span Open Space (26 periods back + 6-bar forward corridor for horizontal freedom)
        val closeNow = closedBars[lastIdx].close
        val chikouIdx = (lastIdx - disp).coerceAtLeast(0)
        val corridorEnd = min(lastIdx, chikouIdx + 6)
        var chikouBullishOpen = true
        var chikouBearishOpen = true
        for (k in chikouIdx..corridorEnd) {
            val barK = closedBars[k]
            val kumoTopK = max(currentSpanAAt(k), currentSpanBAt(k))
            val kumoBotK = min(currentSpanAAt(k), currentSpanBAt(k))
            val tkTopK = max(tenkanAt(k), kijunAt(k))
            val tkBotK = min(tenkanAt(k), kijunAt(k))
            if (closeNow <= max(barK.high, max(kumoTopK, tkTopK))) {
                chikouBullishOpen = false
            }
            if (closeNow >= min(barK.low, min(kumoBotK, tkBotK))) {
                chikouBearishOpen = false
            }
        }

        // Local Chikou S/R Peak and Trough (Mark Tuttle's rule for flat SSB breakout confirmation)
        val lookbackStart = (lastIdx - config.kijunPeriod).coerceAtLeast(1)
        var chikouPeak = closedBars[lookbackStart].close
        var chikouTrough = closedBars[lookbackStart].close
        for (k in lookbackStart until lastIdx) {
            val c = closedBars[k].close
            if (c > chikouPeak) chikouPeak = c
            if (c < chikouTrough) chikouTrough = c
        }

        // Flatness detection (within 0.03% of price or 0.05 * ATR over 3 bars)
        val flatTol = max(closeNow * 0.0003, atr14 * 0.04)
        val spanB2 = currentSpanBAt((lastIdx - 2).coerceAtLeast(0))
        val isCurrentSpanBFlat = abs(currentSpanB - currentSpanBAt(prevIdx)) <= flatTol &&
            abs(currentSpanB - spanB2) <= flatTol
        val isKijunFlat = abs(kijun - prevKijun) <= flatTol
        val isTenkanFlat = abs(tenkan - prevTenkan) <= flatTol

        // Extract horizontal equilibrium plateaus (Kijun & SSB flat for >= 3 bars) — Karen Péloille
        val rawPlateaus = mutableListOf<Double>()
        for (i in max(3, lastIdx - 80)..lastIdx) {
            val kj = kijunAt(i)
            if (abs(kj - kijunAt(i - 1)) <= flatTol && abs(kj - kijunAt(i - 2)) <= flatTol) {
                rawPlateaus += kj
            }
            val ssb = currentSpanBAt(i)
            if (abs(ssb - currentSpanBAt(i - 1)) <= flatTol && abs(ssb - currentSpanBAt(i - 2)) <= flatTol) {
                rawPlateaus += ssb
            }
        }
        val mergedPlateaus = mergeNearbyLevels(rawPlateaus, max(atr14 * 0.35, closeNow * 0.0015))

        val cur = closedBars[lastIdx]
        val barRange = (cur.high - cur.low).coerceAtLeast(1e-8)
        val bodyRatio = abs(cur.close - cur.open) / barRange
        val clv = ((cur.close - cur.low) / barRange).coerceIn(0.0, 1.0)

        val volStart = (lastIdx - 19).coerceAtLeast(0)
        val avgVol20 = closedBars.subList(volStart, lastIdx + 1).map { it.volume }.average()
        val volRatio20 = if (avgVol20 > 0.0 && cur.volume > 0.0) cur.volume / avgVol20 else 1.0

        val kj3Idx = (lastIdx - 3).coerceAtLeast(0)
        val kijunSlopeAtr = (kijun - kijunAt(kj3Idx)) / (3.0 * atr14)
        val tenkanSlopeAtr = (tenkan - prevTenkan) / atr14
        val futThicknessAtr = abs(futureSpanA - futureSpanB) / atr14
        val curThicknessAtr = abs(currentSpanA - currentSpanB) / atr14
        val distKijunAtr = abs(cur.close - kijun) / atr14
        val cClampWarn = isKijunFlat && isCurrentSpanBFlat && distKijunAtr > config.maxKijunExtensionAtr * 1.05
        val katana = abs(tenkan - kijun) <= flatTol * 1.2

        val b1 = closedBars[(lastIdx - 1).coerceAtLeast(0)]
        val b2 = closedBars[(lastIdx - 2).coerceAtLeast(0)]
        val bullImpulse3 = listOf(b2, b1, cur).count { it.close > it.open } >= 2 && cur.close >= max(b1.close, b2.close)
        val bearImpulse3 = listOf(b2, b1, cur).count { it.close < it.open } >= 2 && cur.close <= min(b1.close, b2.close)

        // Kaufman Efficiency Ratio (ER14) — measures pure trend vs choppy sideways noise
        val erPeriod = min(14, lastIdx).coerceAtLeast(1)
        val netDisp = abs(cur.close - closedBars[lastIdx - erPeriod].close)
        var pathLen = 0.0
        for (i in (lastIdx - erPeriod + 1)..lastIdx) {
            pathLen += abs(closedBars[i].close - closedBars[i - 1].close)
        }
        val er14 = if (pathLen > 1e-8) (netDisp / pathLen).coerceIn(0.0, 1.0) else 0.5

        val candleTopBody = max(cur.open, cur.close)
        val candleBotBody = min(cur.open, cur.close)
        val upperWick = ((cur.high - candleTopBody) / barRange).coerceIn(0.0, 1.0)
        val lowerWick = ((candleBotBody - cur.low) / barRange).coerceIn(0.0, 1.0)

        // Detect recent Katana release (Tenkan & Kijun tightly compressed 1..3 bars ago, now expanding)
        val prev2Idx = (lastIdx - 2).coerceAtLeast(0)
        val prev3Idx = (lastIdx - 3).coerceAtLeast(0)
        val compressedRecently = abs(prevTenkan - prevKijun) <= atr14 * 0.18 ||
            abs(tenkanAt(prev2Idx) - kijunAt(prev2Idx)) <= atr14 * 0.18 ||
            abs(tenkanAt(prev3Idx) - kijunAt(prev3Idx)) <= atr14 * 0.18
        val katanaRelease = compressedRecently && abs(tenkan - kijun) >= abs(prevTenkan - prevKijun) && bodyRatio >= 0.32

        return IchimokuBarSnapshot(
            time = cur.time,
            open = cur.open,
            high = cur.high,
            low = cur.low,
            close = cur.close,
            tenkan = round2(tenkan),
            kijun = round2(kijun),
            prevTenkan = round2(prevTenkan),
            prevKijun = round2(prevKijun),
            currentSpanA = round2(currentSpanA),
            currentSpanB = round2(currentSpanB),
            futureSpanA = round2(futureSpanA),
            futureSpanB = round2(futureSpanB),
            prevFutureSpanA = round2(prevFutureSpanA),
            prevFutureSpanB = round2(prevFutureSpanB),
            atr14 = round2(atr14),
            chikouBullishOpenSpace = chikouBullishOpen,
            chikouBearishOpenSpace = chikouBearishOpen,
            chikouResistancePeak = round2(chikouPeak),
            chikouSupportTrough = round2(chikouTrough),
            isCurrentSpanBFlat = isCurrentSpanBFlat,
            isKijunFlat = isKijunFlat,
            isTenkanFlat = isTenkanFlat,
            flatEquilibriumLevels = mergedPlateaus.map(::round2),
            candleBodyRatio = round2(bodyRatio),
            closeLocationValue = round2(clv),
            volumeRatio20 = round2(volRatio20),
            kijunSlopeAtrPerBar = round2(kijunSlopeAtr),
            tenkanSlopeAtrPerBar = round2(tenkanSlopeAtr),
            futureKumoThicknessAtr = round2(futThicknessAtr),
            currentKumoThicknessAtr = round2(curThicknessAtr),
            isCClampWarning = cClampWarn,
            isKatanaEquilibrium = katana,
            threeCandleBullishImpulse = bullImpulse3,
            threeCandleBearishImpulse = bearImpulse3,
            efficiencyRatio14 = round2(er14),
            upperWickRatio = round2(upperWick),
            lowerWickRatio = round2(lowerWick),
            recentKatanaRelease = katanaRelease,
        )
    }

    /**
     * ساخت سری کامل خطوط ایچیموکو و ابر آینده (۲۶ دوره جلوتر) برای رسم چارت بومی تعاملی.
     */
    fun computeChartSeries(
        candlesInput: List<Candle>,
        config: BaseStrategyConfig = BaseStrategyConfig(),
    ): IchimokuChartSeries {
        val bars = candlesInput
            .filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.high >= it.low && it.close > 0.0 }
            .sortedBy { it.time }
        if (bars.isEmpty()) return IchimokuChartSeries(emptyList(), emptyList())

        fun midpoint(fromIdx: Int, toIdx: Int): Double {
            val start = fromIdx.coerceIn(0, toIdx)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (k in start..toIdx) {
                if (bars[k].high > hh) hh = bars[k].high
                if (bars[k].low < ll) ll = bars[k].low
            }
            return (hh + ll) / 2.0
        }
        fun tenkanAt(idx: Int): Double = midpoint(idx - (config.tenkanPeriod - 1), idx)
        fun kijunAt(idx: Int): Double = midpoint(idx - (config.kijunPeriod - 1), idx)
        fun spanBAtCreation(idx: Int): Double = midpoint(idx - (config.senkouBPeriod - 1), idx)
        fun spanAAtCreation(idx: Int): Double = (tenkanAt(idx) + kijunAt(idx)) / 2.0

        val disp = (config.displacement - 1).coerceAtLeast(1)
        val overlays = bars.mapIndexed { idx, candle ->
            val src = (idx - disp).coerceAtLeast(0)
            val chikou = if (idx + disp <= bars.lastIndex) bars[idx + disp].close else null
            IchimokuChartBarOverlay(
                index = idx,
                candle = candle,
                tenkan = tenkanAt(idx),
                kijun = kijunAt(idx),
                currentSpanA = spanAAtCreation(src),
                currentSpanB = spanBAtCreation(src),
                chikouPrice = chikou,
            )
        }

        val futurePoints = mutableListOf<FutureKumoPoint>()
        val startFutureSrc = (bars.lastIndex - disp + 1).coerceAtLeast(0)
        var step = 1
        for (srcIdx in startFutureSrc..bars.lastIndex) {
            futurePoints += FutureKumoPoint(
                stepAhead = step++,
                spanA = spanAAtCreation(srcIdx),
                spanB = spanBAtCreation(srcIdx),
            )
        }
        return IchimokuChartSeries(bars = overlays, futureKumo = futurePoints)
    }

    /**
     * استخراج ساختار موج N گوایچی هوسودا (A -> B -> C)، بررسی نقطه S و محاسبه ۴ هدف قیمتی V, N, E, NT
     * به همراه فاصله تا اعداد زمانی پایه هوسودا (9, 17, 26, 33, 42, 65, 76).
     */
    fun computeHosodaWave(
        candlesInput: List<Candle>,
        direction: SignalAction,
        snapshot: IchimokuBarSnapshot,
    ): HosodaWaveProjection? {
        val bars = candlesInput.filter { it.closed }.ifEmpty { candlesInput }.takeLast(65)
        if (bars.size < 12 || direction == SignalAction.NO_TRADE) return null
        val lastIdx = bars.lastIndex

        if (direction == SignalAction.BUY) {
            // Find recent pullback low C in the last 6 bars, swing high B before C, and impulse origin low A before B
            val cSearchStart = (lastIdx - 6).coerceAtLeast(4)
            var idxC = cSearchStart
            for (i in cSearchStart..lastIdx) {
                if (bars[i].low <= bars[idxC].low) idxC = i
            }
            val bSearchStart = (idxC - 22).coerceAtLeast(2)
            var idxB = bSearchStart
            for (i in bSearchStart until idxC) {
                if (bars[i].high >= bars[idxB].high) idxB = i
            }
            val aSearchStart = (idxB - 45).coerceAtLeast(0)
            var idxA = aSearchStart
            for (i in aSearchStart..idxB) {
                if (bars[i].low <= bars[idxA].low) idxA = i
            }

            val rawA = bars[idxA].low
            val rawB = max(bars[idxB].high, snapshot.close)
            val rawC = min(bars[idxC].low, snapshot.tenkan)
            val waveHeight = max(rawB - rawA, max(snapshot.distanceToKijun * 2.4, snapshot.atr14 * 3.0))
            val pointA = rawB - waveHeight
            val pointB = rawB
            val pointC = rawC.coerceIn(pointA + snapshot.atr14 * 0.3, pointB - snapshot.atr14 * 0.3)

            val targetV = pointB + (pointB - pointC)
            val targetN = pointC + (pointB - pointA)
            val targetE = pointB + (pointB - pointA)
            val targetNT = pointC + (pointC - pointA)

            val barsSinceA = (lastIdx - idxA + 1).coerceAtLeast(1)
            val nearestCycle = HOSODA_TIME_CYCLES.minByOrNull { abs(it - barsSinceA) } ?: 9
            val nearChangeDay = abs(nearestCycle - barsSinceA) <= 1

            return HosodaWaveProjection(
                direction = SignalAction.BUY,
                pointA = round2(pointA),
                pointB = round2(pointB),
                pointC = round2(pointC),
                sPointHeld = pointC >= snapshot.kumoBottom,
                targetV = round2(max(targetV, snapshot.close + snapshot.atr14 * 1.5)),
                targetN = round2(max(targetN, snapshot.close + snapshot.atr14 * 2.2)),
                targetE = round2(max(targetE, snapshot.close + snapshot.atr14 * 3.0)),
                targetNT = round2(max(targetNT, snapshot.close + snapshot.atr14 * 1.2)),
                barsSinceWaveStart = barsSinceA,
                nearestTimeCycleNumber = nearestCycle,
                isNearTimeCycleChangeDay = nearChangeDay,
            )
        } else {
            // SELL wave A (high) -> B (low) -> C (pullback high)
            val cSearchStart = (lastIdx - 6).coerceAtLeast(4)
            var idxC = cSearchStart
            for (i in cSearchStart..lastIdx) {
                if (bars[i].high >= bars[idxC].high) idxC = i
            }
            val bSearchStart = (idxC - 22).coerceAtLeast(2)
            var idxB = bSearchStart
            for (i in bSearchStart until idxC) {
                if (bars[i].low <= bars[idxB].low) idxB = i
            }
            val aSearchStart = (idxB - 45).coerceAtLeast(0)
            var idxA = aSearchStart
            for (i in aSearchStart..idxB) {
                if (bars[i].high >= bars[idxA].high) idxA = i
            }

            val rawA = bars[idxA].high
            val rawB = min(bars[idxB].low, snapshot.close)
            val rawC = max(bars[idxC].high, snapshot.tenkan)
            val waveHeight = max(rawA - rawB, max(snapshot.distanceToKijun * 2.4, snapshot.atr14 * 3.0))
            val pointA = rawB + waveHeight
            val pointB = rawB
            val pointC = rawC.coerceIn(pointB + snapshot.atr14 * 0.3, pointA - snapshot.atr14 * 0.3)

            val targetV = pointB - (pointC - pointB)
            val targetN = pointC - (pointA - pointB)
            val targetE = pointB - (pointA - pointB)
            val targetNT = pointC - (pointA - pointC)

            val barsSinceA = (lastIdx - idxA + 1).coerceAtLeast(1)
            val nearestCycle = HOSODA_TIME_CYCLES.minByOrNull { abs(it - barsSinceA) } ?: 9
            val nearChangeDay = abs(nearestCycle - barsSinceA) <= 1

            return HosodaWaveProjection(
                direction = SignalAction.SELL,
                pointA = round2(pointA),
                pointB = round2(pointB),
                pointC = round2(pointC),
                sPointHeld = pointC <= snapshot.kumoTop,
                targetV = round2(min(targetV, snapshot.close - snapshot.atr14 * 1.5).coerceAtLeast(0.00001)),
                targetN = round2(min(targetN, snapshot.close - snapshot.atr14 * 2.2).coerceAtLeast(0.00001)),
                targetE = round2(min(targetE, snapshot.close - snapshot.atr14 * 3.0).coerceAtLeast(0.00001)),
                targetNT = round2(min(targetNT, snapshot.close - snapshot.atr14 * 1.2).coerceAtLeast(0.00001)),
                barsSinceWaveStart = barsSinceA,
                nearestTimeCycleNumber = nearestCycle,
                isNearTimeCycleChangeDay = nearChangeDay,
            )
        }
    }

    /**
     * ارزیابی کامل هر یک از ۱۵ نماد داخل اپ (`V1Universe.allSymbols`) بر اساس تنها استراتژی فعال انتخاب‌شده.
     */
    fun evaluateBitcoinStrategy(
        activeModel: BitcoinStrategyModel,
        config: BaseStrategyConfig,
        macroCandles: List<Candle>,
        strategyCandles: List<Candle>,
        triggerCandles: List<Candle>,
        livePriceOverride: Double? = null,
        customTitleOverride: String? = null,
        customIdOverride: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        symbolInput: String = BTC_SYMBOL,
    ): StrategyEvaluationReport {
        val normalizedSymbol = V1Universe.normalize(symbolInput).takeIf { V1Universe.valid(it) } ?: BTC_SYMBOL
        val effectiveId = customIdOverride ?: activeModel.id
        val effectiveTitle = customTitleOverride ?: activeModel.titleFa

        val macroSnap = computeSnapshot(macroCandles, config)
        val stratSnap = computeSnapshot(strategyCandles, config)
        val trigSnap = computeSnapshot(triggerCandles, config)

        if (stratSnap == null || trigSnap == null) {
            return StrategyEvaluationReport(
                symbol = normalizedSymbol,
                strategyId = effectiveId,
                strategyTitleFa = effectiveTitle,
                strategyBadgeFa = activeModel.badgeFa,
                macroInterval = config.macroInterval,
                strategyInterval = config.strategyInterval,
                triggerInterval = config.triggerInterval,
                readiness = StrategyReadinessState.INSUFFICIENT_DATA,
                action = SignalAction.NO_TRADE,
                triggerType = StrategyTriggerType.NONE,
                confidence = 0.0,
                livePrice = livePriceOverride ?: trigSnap?.close ?: stratSnap?.close ?: 0.0,
                suggestedEntry = null,
                suggestedStopLoss = null,
                targetV = null,
                targetN = null,
                targetE = null,
                primaryTakeProfit = null,
                riskReward = null,
                macroSnapshot = macroSnap,
                strategySnapshot = stratSnap,
                triggerSnapshot = trigSnap,
                waveProjection = null,
                conditions = emptyList(),
                summaryFa = "در حال تکمیل کندل‌های ۳ تایم‌فریم (${config.macroInterval.label}/${config.strategyInterval.label}/${config.triggerInterval.label}) برای $normalizedSymbol...",
                triggerBarTime = trigSnap?.time ?: 0L,
                evaluatedAt = nowMillis,
            )
        }

        // Apply model-specific overrides onto config if a specialized book model is selected
        val effectiveConfig = when (activeModel) {
            BitcoinStrategyModel.BASE_ICHIMOKU -> config
            BitcoinStrategyModel.PATEL_KIJUN_PULLBACK -> config.copy(
                enablePullbackTrigger = true,
                enableBreakoutTrigger = false,
                requireChikouOpenSpace = true,
                requireFutureKumoSlope = true,
            )
            BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT -> config.copy(
                enablePullbackTrigger = false,
                enableBreakoutTrigger = true,
                requireChikouOpenSpace = true,
            )
            BitcoinStrategyModel.ELLIOTT_HOSODA_WAVE -> config.copy(
                enablePullbackTrigger = true,
                enableBreakoutTrigger = true,
                minRiskReward = max(config.minRiskReward, 1.8),
            )
            BitcoinStrategyModel.PELOILLE_MTF_PLATEAU -> config.copy(
                requireHigherTfAlignment = true,
                requireChikouOpenSpace = true,
                enablePullbackTrigger = true,
                enableBreakoutTrigger = true,
            )
        }

        val livePrice = livePriceOverride?.takeIf { it.isFinite() && it > 0.0 } ?: trigSnap.close

        // Determine candidate bias from Strategy Timeframe (1H) + Macro Timeframe (4H)
        val bullishBias = stratSnap.isPriceAboveKumo ||
            (macroSnap?.isPriceAboveKumo == true && trigSnap.isPriceAboveKumo)
        val bearishBias = stratSnap.isPriceBelowKumo ||
            (macroSnap?.isPriceBelowKumo == true && trigSnap.isPriceBelowKumo)

        val candidateDirection = when {
            bullishBias && !bearishBias -> SignalAction.BUY
            bearishBias && !bullishBias -> SignalAction.SELL
            stratSnap.tenkan >= stratSnap.kijun -> SignalAction.BUY
            else -> SignalAction.SELL
        }

        val isLong = candidateDirection == SignalAction.BUY

        // ── Check 1: 3-TF Kumo Regime Alignment (4H / 1H / 15m) ───────────────
        val stratKumoOk = if (isLong) stratSnap.isPriceAboveKumo else stratSnap.isPriceBelowKumo
        val trigKumoOk = if (isLong) trigSnap.isPriceAboveKumo else trigSnap.isPriceBelowKumo
        val macroKumoOk = if (!effectiveConfig.requireHigherTfAlignment || macroSnap == null) {
            true
        } else {
            if (isLong) !macroSnap.isPriceBelowKumo else !macroSnap.isPriceAboveKumo
        }
        val cond1Passed = stratKumoOk && trigKumoOk && macroKumoOk
        val cond1Detail = buildString {
            append("${config.triggerInterval.label}/${config.strategyInterval.label}: ")
            append(if (stratSnap.isPriceAboveKumo) "بالای کومو (${stratSnap.kumoTop})" else if (stratSnap.isPriceBelowKumo) "زیر کومو (${stratSnap.kumoBottom})" else "داخل کومو")
            macroSnap?.let { m ->
                append(" · ${config.macroInterval.label}: ")
                append(if (m.isPriceAboveKumo) "بالای کومو" else if (m.isPriceBelowKumo) "زیر کومو" else "داخل کومو")
            }
        }

        // ── Check 2: Sanyaku 5-Line Order (Price > Tenkan >= Kijun > Kumo) ─────
        val sanyakuOk = if (isLong) {
            stratSnap.sanyakuBullishOrder && trigSnap.sanyakuBullishOrder
        } else {
            stratSnap.sanyakuBearishOrder && trigSnap.sanyakuBearishOrder
        }
        val cond2Detail = if (isLong) {
            "قیمت > تنکان (${trigSnap.tenkan}) ≥ کیجون (${trigSnap.kijun}) > کومو (${trigSnap.kumoTop})"
        } else {
            "قیمت < تنکان (${trigSnap.tenkan}) ≤ کیجون (${trigSnap.kijun}) < کومو (${trigSnap.kumoBottom})"
        }

        // ── Check 3: Tenkan / Kijun Koten-Gyakuten & Dynamic Equilibrium Slope ──
        val tkAligned = if (isLong) {
            stratSnap.tenkan >= stratSnap.kijun &&
                stratSnap.tenkan >= stratSnap.prevTenkan - stratSnap.atr14 * 0.05 &&
                stratSnap.kijun >= stratSnap.prevKijun - stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan >= trigSnap.prevTenkan - trigSnap.atr14 * 0.05
        } else {
            stratSnap.tenkan <= stratSnap.kijun &&
                stratSnap.tenkan <= stratSnap.prevTenkan + stratSnap.atr14 * 0.05 &&
                stratSnap.kijun <= stratSnap.prevKijun + stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan <= trigSnap.prevTenkan + trigSnap.atr14 * 0.05
        }
        val cond3Detail = String.format(
            Locale.US,
            "شیب تنکان: %+.2f ATR · شیب کیجون: %+.2f ATR",
            trigSnap.tenkanSlopeAtrPerBar,
            trigSnap.kijunSlopeAtrPerBar,
        )

        // ── Check 4: Future Kumo Direction & Non-diverging Span B (26 bars ahead) ──
        val futureKumoOk = if (!effectiveConfig.requireFutureKumoSlope) {
            true
        } else if (isLong) {
            stratSnap.isFutureKumoBullish && stratSnap.isFutureSpanARising && stratSnap.isFutureSpanBNonFalling &&
                trigSnap.isFutureKumoBullish
        } else {
            stratSnap.isFutureKumoBearish && stratSnap.isFutureSpanAFalling && stratSnap.isFutureSpanBNonRising &&
                trigSnap.isFutureKumoBearish
        }
        val cond4Detail = "Future SSA=${stratSnap.futureSpanA} vs SSB=${stratSnap.futureSpanB} (${if (stratSnap.isFutureKumoBullish) "صعودی" else "نزولی"})"

        // ── Check 5: Future Kumo Thickness & Twist Immunity (الیوت + پلویل) ────
        val futureThicknessOk = stratSnap.futureKumoThicknessAtr >= 0.05 && trigSnap.futureKumoThicknessAtr >= 0.04
        val cond5Detail = String.format(
            Locale.US,
            "ضخامت ابر آینده: %.2f ATR (${config.strategyInterval.label}) · %.2f ATR (${config.triggerInterval.label})",
            stratSnap.futureKumoThicknessAtr,
            trigSnap.futureKumoThicknessAtr,
        )

        // ── Check 6: Chikou Span 3D Open Space (26 bars back + 6-bar corridor) ──
        val chikouOk = if (!effectiveConfig.requireChikouOpenSpace) {
            true
        } else if (isLong) {
            stratSnap.chikouBullishOpenSpace && trigSnap.chikouBullishOpenSpace
        } else {
            stratSnap.chikouBearishOpenSpace && trigSnap.chikouBearishOpenSpace
        }
        val cond6Detail = if (chikouOk) {
            "چیکو در ${config.strategyInterval.label} و ${config.triggerInterval.label} آزاد از کندل، TK و ابر کومو"
        } else {
            "چیکو درگیر کندل‌ها یا ابر ۲۶ دوره قبل است"
        }

        // ── Check 7: Chikou Structural Peak / Trough Clearance (مارک تاتل) ─────
        val chikouPeakClear = if (isLong) {
            trigSnap.close >= trigSnap.chikouResistancePeak - trigSnap.atr14 * 0.15
        } else {
            trigSnap.close <= trigSnap.chikouSupportTrough + trigSnap.atr14 * 0.15
        }
        val cond7Detail = if (isLong) {
            "کلوز=${trigSnap.close} در برابر سقف چیکو=${trigSnap.chikouResistancePeak}"
        } else {
            "کلوز=${trigSnap.close} در برابر کف چیکو=${trigSnap.chikouSupportTrough}"
        }

        // ── Check 8: Kijun Rubber-Band & Anti-C-Clamp Overextension Filter ─────
        val extAtr = stratSnap.distanceToKijunAtr
        val notOverextended = extAtr <= effectiveConfig.maxKijunExtensionAtr &&
            !stratSnap.isCClampWarning && !trigSnap.isCClampWarning
        val cond8Detail = String.format(
            Locale.US,
            "فاصله از کیجون: %.2f ATR (سقف: %.2f ATR)%s",
            extAtr,
            effectiveConfig.maxKijunExtensionAtr,
            if (stratSnap.isCClampWarning || trigSnap.isCClampWarning) " · هشدار C-Clamp" else "",
        )

        // ── Check 9: Flat SSB Gravity & Katana Equilibrium Filter (تاتل + پلویل) ──
        val reqGravityClearance = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            trigSnap.atr14 * effectiveConfig.breakoutBufferAtr
        } else {
            0.0
        }
        val flatSsbGravityCleared = if (isLong) {
            trigSnap.close >= trigSnap.kumoTop + reqGravityClearance
        } else {
            trigSnap.close <= trigSnap.kumoBottom - reqGravityClearance
        }
        val cond9Detail = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            "SSB فلت شناسایی شد؛ عبور با بافر امن انجام شد"
        } else {
            "بدون تله جاذبه SSB فلت (${if (trigSnap.isKatanaEquilibrium) "هم‌پوشانی کاتانا" else "تفکیک استاندارد TK"})"
        }

        // ── Detect Trigger on 15m (Pullback / Breakout / 3-Candle Sanyaku) ────
        val closedTriggerBars = triggerCandles.filter { it.closed }.ifEmpty { triggerCandles }
        val triggerType = detectEntryTrigger(
            direction = candidateDirection,
            bars = closedTriggerBars,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            config = effectiveConfig,
        )

        // ── Compute Sniper Entry Zone & Tight Structural SL ───────────────────
        val slBuffer = trigSnap.atr14 * effectiveConfig.slBufferAtr
        val candleGoldenPocket = if (isLong) {
            trigSnap.low + (trigSnap.high - trigSnap.low) * 0.55
        } else {
            trigSnap.high - (trigSnap.high - trigSnap.low) * 0.55
        }
        val sniperOptimal = if (isLong) {
            round2(max(trigSnap.tenkan, min(livePrice, candleGoldenPocket)))
        } else {
            round2(min(trigSnap.tenkan, max(livePrice, candleGoldenPocket)))
        }
        val sniperLow = if (isLong) {
            round2(min(trigSnap.tenkan, sniperOptimal) - trigSnap.atr14 * 0.12)
        } else {
            round2(min(sniperOptimal, livePrice) - trigSnap.atr14 * 0.35)
        }
        val sniperHigh = if (isLong) {
            round2(max(sniperOptimal, livePrice) + trigSnap.atr14 * 0.35)
        } else {
            round2(max(trigSnap.tenkan, sniperOptimal) + trigSnap.atr14 * 0.12)
        }
        val distToSniperAtr = abs(livePrice - sniperOptimal) / trigSnap.atr14
        val sniperEntryScore = round2((100.0 - distToSniperAtr * 55.0).coerceIn(15.0, 100.0))

        val technicalSl = if (isLong) {
            val kumoKijunFloor = min(trigSnap.kijun, stratSnap.kijun.coerceAtLeast(trigSnap.kumoBottom)) - slBuffer
            val microStructureFloor = min(trigSnap.low, trigSnap.kijun) - trigSnap.atr14 * 0.18
            val tightStructuralSl = max(kumoKijunFloor, microStructureFloor)
            round2(min(tightStructuralSl, sniperOptimal - trigSnap.atr14 * 0.45))
        } else {
            val kumoKijunCeiling = max(trigSnap.kijun, stratSnap.kijun.coerceAtMost(trigSnap.kumoTop)) + slBuffer
            val microStructureCeiling = max(trigSnap.high, trigSnap.kijun) + trigSnap.atr14 * 0.18
            val tightStructuralSl = min(kumoKijunCeiling, microStructureCeiling)
            round2(max(tightStructuralSl, sniperOptimal + trigSnap.atr14 * 0.45))
        }

        val wave = computeHosodaWave(strategyCandles, candidateDirection, stratSnap)
        val stopDist = abs(sniperOptimal - technicalSl).coerceAtLeast(trigSnap.atr14 * 0.4)

        // ── Check 10: Hosoda N-Wave S-Point & Kihon Suchi Time Cycle Guard ────
        val sPointOk = wave?.sPointHeld != false
        val timeCycleExhausted = wave?.isNearTimeCycleChangeDay == true && trigSnap.isTenkanFlat
        val cond10Passed = sPointOk && !timeCycleExhausted
        val cond10Detail = if (wave != null) {
            "موج (${wave.pointA}→${wave.pointB}→${wave.pointC}) · کندل ${wave.barsSinceWaveStart} (چرخه هوسودا: ${wave.nearestTimeCycleNumber})"
        } else {
            "در حال تکمیل ساختار موج هوسودا"
        }

        // ── Check 11: Price Action Candle Body, Close Location & Volume Momentum ──
        val directionalCandleOk = if (isLong) {
            trigSnap.close > trigSnap.open && trigSnap.closeLocationValue >= 0.45 && trigSnap.candleBodyRatio >= 0.22
        } else {
            trigSnap.close < trigSnap.open && trigSnap.closeLocationValue <= 0.55 && trigSnap.candleBodyRatio >= 0.22
        }
        val volumeMomentumOk = trigSnap.volumeRatio20 >= 0.60
        val cond11Passed = directionalCandleOk && volumeMomentumOk
        val cond11Detail = String.format(
            Locale.US,
            "بدنه کندل: %.0f%% · CLV: %.0f%% · حجم به میانگین ۲۰: %.2fx",
            trigSnap.candleBodyRatio * 100.0,
            trigSnap.closeLocationValue * 100.0,
            trigSnap.volumeRatio20,
        )

        val candidateTargets = if (wave != null) {
            listOf(wave.targetV, wave.targetN, wave.targetE).sortedBy { abs(it - livePrice) }
        } else {
            emptyList()
        }
        val rawPrimaryTp = candidateTargets.firstOrNull {
            abs(it - livePrice) / stopDist >= effectiveConfig.minRiskReward
        } ?: candidateTargets.lastOrNull() ?: if (isLong) {
            livePrice + stopDist * max(effectiveConfig.minRiskReward, 2.0)
        } else {
            (livePrice - stopDist * max(effectiveConfig.minRiskReward, 2.0)).coerceAtLeast(0.00001)
        }

        // Check nearest HTF horizontal plateau (Karen Péloille's Flat SSB/Kijun wall)
        val allPlateaus = ((macroSnap?.flatEquilibriumLevels ?: emptyList()) + stratSnap.flatEquilibriumLevels).distinct().sorted()
        val nearestObstacle = if (isLong) {
            allPlateaus.firstOrNull { it > livePrice + trigSnap.atr14 * 0.4 }
        } else {
            allPlateaus.lastOrNull { it < livePrice - trigSnap.atr14 * 0.4 }
        }

        // ── Check 12: Room to HTF Plateau & Hosoda Wave R:R >= minRiskReward ──
        val effectiveTp = if (nearestObstacle != null) {
            val distToObstacle = abs(nearestObstacle - livePrice)
            if (distToObstacle / stopDist >= effectiveConfig.minRiskReward) {
                if (isLong) min(rawPrimaryTp, nearestObstacle) else max(rawPrimaryTp, nearestObstacle)
            } else {
                rawPrimaryTp
            }
        } else {
            rawPrimaryTp
        }

        val rr = round2(abs(effectiveTp - livePrice) / stopDist)
        val obstacleClear = if (nearestObstacle == null) {
            true
        } else {
            (abs(nearestObstacle - livePrice) / stopDist) >= effectiveConfig.minRiskReward
        }
        val cond12Passed = rr >= effectiveConfig.minRiskReward && obstacleClear
        val cond12Detail = buildString {
            append(String.format(Locale.US, "R:R = 1:%.2f (حداقل 1:%.1f)", rr, effectiveConfig.minRiskReward))
            if (nearestObstacle != null) {
                append(" · سطح فلت پیش‌رو: $nearestObstacle")
            }
        }

        // ── Check 13: Directional Efficiency Ratio (Kaufman ER) & Anti-Chop Filter ──
        val erStrat = stratSnap.efficiencyRatio14
        val erTrig = trigSnap.efficiencyRatio14
        val dualFlatChop = stratSnap.isKijunFlat && stratSnap.isCurrentSpanBFlat &&
            trigSnap.isKijunFlat && trigSnap.isCurrentSpanBFlat && erStrat < 0.20
        val htfKijunCounterTrap = if (isLong) {
            trigSnap.close > trigSnap.kijun && stratSnap.close < stratSnap.kijun - stratSnap.atr14 * 0.25
        } else {
            trigSnap.close < trigSnap.kijun && stratSnap.close > stratSnap.kijun + stratSnap.atr14 * 0.25
        }
        val cond13Passed = (erStrat >= 0.20 || erTrig >= 0.22) && !dualFlatChop && !htfKijunCounterTrap
        val cond13Detail = String.format(
            Locale.US,
            "کارایی روند (ER14): %.0f%% (${config.strategyInterval.label}) · %.0f%% (${config.triggerInterval.label})%s",
            erStrat * 100.0,
            erTrig * 100.0,
            if (htfKijunCounterTrap) " · تله خلاف کیجون 1H" else if (dualFlatChop) " · رِنج افقی" else " · روند خالص",
        )

        // ── Check 14: Anti-Fakeout Wick Guard & Liquidity Sweep Filter ────────
        val adverseWick = if (isLong) trigSnap.upperWickRatio else trigSnap.lowerWickRatio
        val favorableTail = if (isLong) trigSnap.lowerWickRatio else trigSnap.upperWickRatio
        val cond14Passed = adverseWick <= 0.45 && (trigSnap.candleBodyRatio >= 0.22 || favorableTail >= 0.25)
        val cond14Detail = String.format(
            Locale.US,
            "شدوی مخالف: %.0f%% (سقف مجاز ۴۵%%) · شدوی تایید: %.0f%%",
            adverseWick * 100.0,
            favorableTail * 100.0,
        )

        // ── Check 15: Core-Trend Phase Classification (Genesis / Core vs Exhaustion) ──
        val waveBars = wave?.barsSinceWaveStart ?: 12
        val trendPhase = when {
            !cond13Passed || dualFlatChop -> TrendPhase.CHOP_RANGE
            !notOverextended || (waveBars > 55 && trigSnap.isTenkanFlat && stratSnap.isTenkanFlat) -> TrendPhase.LATE_EXHAUSTION
            waveBars <= 18 || trigSnap.recentKatanaRelease || triggerType == StrategyTriggerType.EARLY_KATANA_GENESIS -> TrendPhase.GENESIS
            else -> TrendPhase.CORE_EXPANSION
        }
        val cond15Passed = trendPhase == TrendPhase.GENESIS || trendPhase == TrendPhase.CORE_EXPANSION
        val cond15Detail = "${trendPhase.badgeFa} · کندل $waveBars موج · فاصله کیجون: ${String.format(Locale.US, "%.2f", extAtr)} ATR"

        // ── Check 16: Sniper Entry Precision & Anti-Chase Pocket ──────────────
        val distFromTenkanAtr = trigSnap.distanceToTenkan / trigSnap.atr14
        val cond16Passed = distToSniperAtr <= 0.65 && distFromTenkanAtr <= 1.15
        val cond16Detail = String.format(
            Locale.US,
            "ورود بهینه: %s · بازه نقطه‌زنی: [%s .. %s] · دقت نقطه‌زنی: %.0f%%",
            round2(sniperOptimal).toString(),
            round2(sniperLow).toString(),
            round2(sniperHigh).toString(),
            sniperEntryScore,
        )

        val conditions = listOf(
            StrategyConditionCheck(1, "۱. رژیم کوموی ۳ تایم‌فریم (${config.macroInterval.label}/${config.strategyInterval.label}/${config.triggerInterval.label})", cond1Passed, cond1Detail),
            StrategyConditionCheck(2, "۲. چیدمان ۵ خط سانیاکو (Sanyaku Order)", sanyakuOk, cond2Detail),
            StrategyConditionCheck(3, "۳. کراس و شیب پویا Tenkan / Kijun", tkAligned, cond3Detail),
            StrategyConditionCheck(4, "۴. جهت و شیب ابر آینده (۲۶ کندل جلوتر)", futureKumoOk, cond4Detail),
            StrategyConditionCheck(5, "۵. ضخامت ابر آینده و ایمنی از پیچش (Twist)", futureThicknessOk, cond5Detail),
            StrategyConditionCheck(6, "۶. رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل", chikouOk, cond6Detail),
            StrategyConditionCheck(7, "۷. شکست سقف/کف ساختاری چیکو (فیلتر تاتل)", chikouPeakClear, cond7Detail),
            StrategyConditionCheck(8, "۸. قانون کش لاستیکی کیجون و نبود C-Clamp", notOverextended, cond8Detail),
            StrategyConditionCheck(9, "۹. فیلتر جاذبه فلت SSB و تعادل کاتانا", flatSsbGravityCleared, cond9Detail),
            StrategyConditionCheck(10, "۱۰. هندسه موج N هوسودا، نقطه S و چرخه زمانی", cond10Passed, cond10Detail),
            StrategyConditionCheck(11, "۱۱. قدرت پرایس‌اکشن کندل ماشه و حجم (CLV/Vol)", cond11Passed, cond11Detail),
            StrategyConditionCheck(12, "۱۲. فضای باز تا فلت 4H/1H و R:R تارگت هوسودا", cond12Passed, cond12Detail),
            StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14)", cond13Passed, cond13Detail),
            StrategyConditionCheck(14, "۱۴. فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس", cond14Passed, cond14Detail),
            StrategyConditionCheck(15, "۱۵. شکار اصل روند (فاز Genesis/Core و عدم اشباع)", cond15Passed, cond15Detail),
            StrategyConditionCheck(16, "۱۶. نقطه‌زنی میلی‌متری ورود (Sniper Entry Pocket)", cond16Passed, cond16Detail),
        )

        val requiredIndicesForModel: Set<Int> = when (activeModel) {
            BitcoinStrategyModel.BASE_ICHIMOKU -> (1..16).toSet()
            BitcoinStrategyModel.PATEL_KIJUN_PULLBACK -> setOf(1, 2, 3, 4, 6, 8, 11, 13, 14, 15, 16)
            BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT -> setOf(1, 4, 6, 7, 8, 9, 11, 12, 13, 14, 15, 16)
            BitcoinStrategyModel.ELLIOTT_HOSODA_WAVE -> setOf(1, 2, 5, 6, 10, 11, 12, 13, 14, 15, 16)
            BitcoinStrategyModel.PELOILLE_MTF_PLATEAU -> setOf(1, 2, 3, 6, 8, 9, 11, 12, 13, 14, 15, 16)
        }
        val modelCoreConditions = conditions.filter { it.index in requiredIndicesForModel }
        val allSixteenPassed = modelCoreConditions.all { it.passed }
        val firstFifteenPassed = modelCoreConditions.filter { it.index != 16 }.all { it.passed }
        val triggerFired = triggerType != StrategyTriggerType.NONE
        val passedCount = conditions.count { it.passed }
        val phaseBonus = when (trendPhase) {
            TrendPhase.GENESIS -> 8.0
            TrendPhase.CORE_EXPANSION -> 5.0
            TrendPhase.LATE_EXHAUSTION -> -15.0
            TrendPhase.CHOP_RANGE -> -25.0
        }
        val erBonus = ((erStrat + erTrig) * 5.0).coerceIn(0.0, 10.0)
        val rrBonus = (rr * 2.0).coerceIn(0.0, 8.0)
        val sniperBonus = (sniperEntryScore * 0.06).coerceIn(0.0, 6.0)
        val confidence = round2(((passedCount.toDouble() / conditions.size.toDouble()) * 84.0 + if (triggerFired) 16.0 else 0.0).coerceIn(0.0, 100.0))
        val coreTrendScore = round2((confidence * 0.74 + phaseBonus + erBonus + rrBonus + sniperBonus).coerceIn(0.0, 100.0))

        val sniperArmedPendingRetest = firstFifteenPassed && triggerFired && !cond16Passed

        val readiness = when {
            allSixteenPassed && triggerFired && isLong -> StrategyReadinessState.SIGNAL_BUY
            allSixteenPassed && triggerFired && !isLong -> StrategyReadinessState.SIGNAL_SELL
            sniperArmedPendingRetest || passedCount >= 11 -> StrategyReadinessState.WATCH_SETUP
            else -> StrategyReadinessState.BLOCKED
        }

        val finalAction = when (readiness) {
            StrategyReadinessState.SIGNAL_BUY -> SignalAction.BUY
            StrategyReadinessState.SIGNAL_SELL -> SignalAction.SELL
            else -> if (sniperArmedPendingRetest) {
                if (isLong) SignalAction.BUY else SignalAction.SELL
            } else {
                SignalAction.NO_TRADE
            }
        }

        val tfTag = "[تایم ${config.triggerInterval.label}]"
        val summaryFa = when {
            readiness == StrategyReadinessState.SIGNAL_BUY ->
                "$tfTag 🎯 خرید نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            readiness == StrategyReadinessState.SIGNAL_SELL ->
                "$tfTag 🎯 فروش نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            sniperArmedPendingRetest ->
                "$tfTag 🎯 مسلح برای نقطه‌زنی ($normalizedSymbol · $effectiveTitle): در کمین لمس $sniperOptimal"
            readiness == StrategyReadinessState.WATCH_SETUP ->
                "$tfTag ⏳ $passedCount/۱۶ شرط (${trendPhase.badgeFa})؛ منتظر ماشه در ${config.triggerInterval.label}"
            readiness == StrategyReadinessState.BLOCKED -> {
                val firstFailed = modelCoreConditions.firstOrNull { !it.passed } ?: conditions.firstOrNull { !it.passed }
                "$tfTag 🛑 $passedCount/۱۶ شرط · مانع: ${firstFailed?.titleFa ?: ""}"
            }
            else -> "$tfTag در حال دریافت کندل‌های $normalizedSymbol..."
        }

        return StrategyEvaluationReport(
            symbol = normalizedSymbol,
            strategyId = effectiveId,
            strategyTitleFa = effectiveTitle,
            strategyBadgeFa = activeModel.badgeFa,
            macroInterval = config.macroInterval,
            strategyInterval = config.strategyInterval,
            triggerInterval = config.triggerInterval,
            readiness = readiness,
            action = finalAction,
            triggerType = triggerType,
            confidence = confidence,
            livePrice = round2(livePrice),
            suggestedEntry = sniperOptimal,
            suggestedStopLoss = technicalSl,
            targetV = wave?.targetV,
            targetN = wave?.targetN,
            targetE = wave?.targetE,
            primaryTakeProfit = round2(effectiveTp),
            riskReward = rr,
            macroSnapshot = macroSnap,
            strategySnapshot = stratSnap,
            triggerSnapshot = trigSnap,
            waveProjection = wave,
            conditions = conditions,
            summaryFa = summaryFa,
            triggerBarTime = trigSnap.time,
            evaluatedAt = nowMillis,
            trendPhase = trendPhase,
            coreTrendScore = coreTrendScore,
            sniperOptimalEntry = sniperOptimal,
            sniperZoneLow = sniperLow,
            sniperZoneHigh = sniperHigh,
            sniperEntryScore = sniperEntryScore,
            isSniperArmedPendingRetest = sniperArmedPendingRetest,
        )
    }

    /**
     * ارزیابی هم‌زمان تمام استراتژی‌های فعال روی هر دو تایم‌فریم ورود `15m` (`4H -> 1H -> 15m`) و `5m` (`1H -> 15m -> 5m`)
     * با اشتراک‌گذاری کندل‌های `4H`/`1H`/`15m`/`5m` برای جلوگیری از مصرف اضافه اینترنت.
     */
    fun evaluateDualTimeframeStrategy(
        activeModel: BitcoinStrategyModel,
        config: BaseStrategyConfig,
        candles4h: List<Candle>,
        candles1h: List<Candle>,
        candles15m: List<Candle>,
        candles5m: List<Candle>,
        livePriceOverride: Double? = null,
        customTitleOverride: String? = null,
        customIdOverride: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        symbolInput: String = BTC_SYMBOL,
        runAllSimultaneously: Boolean = true,
        enabledStrategyIds: Set<String> = BitcoinStrategyModel.entries.map { it.id }.toSet(),
        customStrategies: List<CustomStrategySlot> = emptyList(),
    ): DualTimeframeSymbolReport {
        val config15m = config.copy(
            macroIntervalLabel = Interval.H4.label,
            strategyIntervalLabel = Interval.H1.label,
            triggerIntervalLabel = Interval.M15.label,
        )
        val config5m = config.copy(
            macroIntervalLabel = Interval.H1.label,
            strategyIntervalLabel = Interval.M15.label,
            triggerIntervalLabel = Interval.M5.label,
        )

        val map15m = LinkedHashMap<String, StrategyEvaluationReport>()
        val map5m = LinkedHashMap<String, StrategyEvaluationReport>()

        if (runAllSimultaneously) {
            val activeBuiltIns = BitcoinStrategyModel.entries.filter { it.id in enabledStrategyIds }
                .ifEmpty { BitcoinStrategyModel.entries }
            for (model in activeBuiltIns) {
                map15m[model.id] = evaluateBitcoinStrategy(
                    activeModel = model,
                    config = config15m,
                    macroCandles = candles4h,
                    strategyCandles = candles1h,
                    triggerCandles = candles15m,
                    livePriceOverride = livePriceOverride,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
                map5m[model.id] = evaluateBitcoinStrategy(
                    activeModel = model,
                    config = config5m,
                    macroCandles = candles1h,
                    strategyCandles = candles15m,
                    triggerCandles = candles5m,
                    livePriceOverride = livePriceOverride,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
            }
            for (slot in customStrategies.filter { it.id in enabledStrategyIds }) {
                val baseMod = BitcoinStrategyModel.fromId(slot.baseModelId)
                map15m[slot.id] = evaluateBitcoinStrategy(
                    activeModel = baseMod,
                    config = slot.config.copy(
                        macroIntervalLabel = Interval.H4.label,
                        strategyIntervalLabel = Interval.H1.label,
                        triggerIntervalLabel = Interval.M15.label,
                    ),
                    macroCandles = candles4h,
                    strategyCandles = candles1h,
                    triggerCandles = candles15m,
                    livePriceOverride = livePriceOverride,
                    customTitleOverride = slot.titleFa,
                    customIdOverride = slot.id,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
                map5m[slot.id] = evaluateBitcoinStrategy(
                    activeModel = baseMod,
                    config = slot.config.copy(
                        macroIntervalLabel = Interval.H1.label,
                        strategyIntervalLabel = Interval.M15.label,
                        triggerIntervalLabel = Interval.M5.label,
                    ),
                    macroCandles = candles1h,
                    strategyCandles = candles15m,
                    triggerCandles = candles5m,
                    livePriceOverride = livePriceOverride,
                    customTitleOverride = slot.titleFa,
                    customIdOverride = slot.id,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
            }
        }

        val focusedId = customIdOverride ?: activeModel.id
        val rep15m = map15m[focusedId]
            ?: map15m.values.firstOrNull { it.isActionable }
            ?: evaluateBitcoinStrategy(
                activeModel = activeModel,
                config = config15m,
                macroCandles = candles4h,
                strategyCandles = candles1h,
                triggerCandles = candles15m,
                livePriceOverride = livePriceOverride,
                customTitleOverride = customTitleOverride,
                customIdOverride = customIdOverride,
                nowMillis = nowMillis,
                symbolInput = symbolInput,
            ).also { map15m[it.strategyId] = it }

        val rep5m = map5m[focusedId]
            ?: map5m.values.firstOrNull { it.isActionable }
            ?: evaluateBitcoinStrategy(
                activeModel = activeModel,
                config = config5m,
                macroCandles = candles1h,
                strategyCandles = candles15m,
                triggerCandles = candles5m,
                livePriceOverride = livePriceOverride,
                customTitleOverride = customTitleOverride,
                customIdOverride = customIdOverride,
                nowMillis = nowMillis,
                symbolInput = symbolInput,
            ).also { map5m[it.strategyId] = it }

        val best15m = map15m.values.firstOrNull { it.isActionable } ?: rep15m
        val best5m = map5m.values.firstOrNull { it.isActionable } ?: rep5m

        return DualTimeframeSymbolReport(
            symbol = best15m.symbol,
            report15m = best15m,
            report5m = best5m,
            reports15mByStrategy = map15m,
            reports5mByStrategy = map5m,
        )
    }

    /**
     * تشخیص ۴ ماشه ورود برای شکار اصل روند از لحظه تولد (`EARLY_KATANA_GENESIS`) تا قلب موج (`PULLBACK / BREAKOUT / SANYAKU`).
     */
    private fun detectEntryTrigger(
        direction: SignalAction,
        bars: List<Candle>,
        trigSnap: IchimokuBarSnapshot,
        stratSnap: IchimokuBarSnapshot,
        config: BaseStrategyConfig,
    ): StrategyTriggerType {
        if (bars.size < 5) return StrategyTriggerType.NONE
        val cur = bars.last()
        val prev1 = bars[bars.lastIndex - 1]
        val prev2 = bars[bars.lastIndex - 2]
        val prev3 = bars[bars.lastIndex - 3]

        val isLong = direction == SignalAction.BUY
        val breakoutBuf = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            trigSnap.atr14 * config.breakoutBufferAtr
        } else {
            trigSnap.atr14 * 0.03
        }

        if (isLong) {
            // Trigger 0: Early Katana Genesis (captures the very birth of the core trend right as TK compression releases)
            if (config.enablePullbackTrigger || config.enableBreakoutTrigger) {
                if (trigSnap.recentKatanaRelease && cur.close > cur.open &&
                    cur.close > max(trigSnap.tenkan, trigSnap.kijun) &&
                    cur.close > trigSnap.kumoTop &&
                    trigSnap.chikouBullishOpenSpace &&
                    trigSnap.distanceToKijunAtr <= 1.15
                ) {
                    return StrategyTriggerType.EARLY_KATANA_GENESIS
                }
            }

            // Trigger 1: Pullback bounce off Kijun / Tenkan / Kumo Top (S-point) and close above Tenkan
            if (config.enablePullbackTrigger) {
                val recentTouchedEquilibrium = listOf(prev1, prev2, prev3, cur).any { b ->
                    b.low <= max(trigSnap.tenkan, trigSnap.kijun) + trigSnap.atr14 * 0.35 &&
                        b.close >= trigSnap.kijun - trigSnap.atr14 * 0.20
                }
                val bullishCandleCloseAboveTenkan = cur.close > cur.open &&
                    cur.close >= trigSnap.tenkan &&
                    cur.close > trigSnap.kumoTop
                if (recentTouchedEquilibrium && bullishCandleCloseAboveTenkan) {
                    return StrategyTriggerType.PULLBACK_BOUNCE
                }
            }

            // Trigger 2: Validated Kumo / Kijun breakout with Chikou resistance peak clearance
            if (config.enableBreakoutTrigger) {
                val crossedUpRecently = (prev1.close <= trigSnap.kumoTop || prev2.close <= trigSnap.kumoTop || prev1.close <= trigSnap.kijun) &&
                    cur.close > max(trigSnap.kumoTop, trigSnap.kijun) + breakoutBuf
                val chikouClearedPeak = cur.close >= trigSnap.chikouResistancePeak + breakoutBuf
                if (crossedUpRecently && chikouClearedPeak && cur.close > cur.open) {
                    return StrategyTriggerType.VALIDATED_BREAKOUT
                }
                if (trigSnap.sanyakuBullishOrder && trigSnap.threeCandleBullishImpulse &&
                    cur.close >= max(prev1.high, prev2.high) && trigSnap.distanceToKijunAtr <= config.maxKijunExtensionAtr * 0.85
                ) {
                    return StrategyTriggerType.SANYAKU_TK_CONTINUATION
                }
            }
        } else {
            // Short Trigger 0: Early Katana Genesis (captures the very birth of a bearish core trend)
            if (config.enablePullbackTrigger || config.enableBreakoutTrigger) {
                if (trigSnap.recentKatanaRelease && cur.close < cur.open &&
                    cur.close < min(trigSnap.tenkan, trigSnap.kijun) &&
                    cur.close < trigSnap.kumoBottom &&
                    trigSnap.chikouBearishOpenSpace &&
                    trigSnap.distanceToKijunAtr <= 1.15
                ) {
                    return StrategyTriggerType.EARLY_KATANA_GENESIS
                }
            }

            // Short Trigger 1: Pullback bounce off Kijun / Tenkan / Kumo Bottom and close below Tenkan
            if (config.enablePullbackTrigger) {
                val recentTouchedEquilibrium = listOf(prev1, prev2, prev3, cur).any { b ->
                    b.high >= min(trigSnap.tenkan, trigSnap.kijun) - trigSnap.atr14 * 0.35 &&
                        b.close <= trigSnap.kijun + trigSnap.atr14 * 0.20
                }
                val bearishCandleCloseBelowTenkan = cur.close < cur.open &&
                    cur.close <= trigSnap.tenkan &&
                    cur.close < trigSnap.kumoBottom
                if (recentTouchedEquilibrium && bearishCandleCloseBelowTenkan) {
                    return StrategyTriggerType.PULLBACK_BOUNCE
                }
            }

            // Short Trigger 2: Validated Kumo / Kijun breakdown with Chikou support trough clearance
            if (config.enableBreakoutTrigger) {
                val crossedDownRecently = (prev1.close >= trigSnap.kumoBottom || prev2.close >= trigSnap.kumoBottom || prev1.close >= trigSnap.kijun) &&
                    cur.close < min(trigSnap.kumoBottom, trigSnap.kijun) - breakoutBuf
                val chikouClearedTrough = cur.close <= trigSnap.chikouSupportTrough - breakoutBuf
                if (crossedDownRecently && chikouClearedTrough && cur.close < cur.open) {
                    return StrategyTriggerType.VALIDATED_BREAKOUT
                }
                if (trigSnap.sanyakuBearishOrder && trigSnap.threeCandleBearishImpulse &&
                    cur.close <= min(prev1.low, prev2.low) && trigSnap.distanceToKijunAtr <= config.maxKijunExtensionAtr * 0.85
                ) {
                    return StrategyTriggerType.SANYAKU_TK_CONTINUATION
                }
            }
        }

        return StrategyTriggerType.NONE
    }

    /**
     * مدیریت پویا و هوشمند معاملات باز بیت‌کوین (`BTC/USDT`):
     * ۱. ریسک‌فری خودکار (Break-Even) پس از رسیدن سود به `1R`
     * ۲. تریل استاندارد استاپ پشت `Kijun-sen`
     * ۳. ورود به حالت حفظ سود (`Preserve Mode` منش پاتل + چرخه زمانی هوسودا) و انتقال استاپ به پشت `Tenkan-sen`
     */
    fun evaluateOpenTradeManagement(
        trade: PaperTrade,
        report: StrategyEvaluationReport,
        config: BaseStrategyConfig,
    ): PreserveModeEvaluation? {
        if (!trade.isOpen || V1Universe.normalize(trade.symbol) != V1Universe.normalize(report.symbol)) return null
        val trigSnap = report.triggerSnapshot ?: return null
        val stratSnap = report.strategySnapshot ?: trigSnap
        val livePrice = report.livePrice.takeIf { it > 0.0 } ?: return null

        val initialStop = trade.initialStopLoss ?: trade.stopLoss
        val oneRDistance = abs(trade.entry - initialStop).coerceAtLeast(trigSnap.atr14 * 0.4)
        val isBuy = trade.action == SignalAction.BUY

        val currentProfitDist = if (isBuy) livePrice - trade.entry else trade.entry - livePrice
        val currentRMultiple = currentProfitDist / oneRDistance

        val nearTimeCycleAndFlatTenkan = report.waveProjection?.isNearTimeCycleChangeDay == true &&
            (trigSnap.isTenkanFlat || stratSnap.isTenkanFlat)

        val overextendedFromKijun = stratSnap.distanceToKijunAtr >= config.maxKijunExtensionAtr

        // Stage 3: Preserve Mode (profit >= 2R & overextended OR Hosoda Time Cycle with flat Tenkan)
        if (config.enablePreserveMode && currentRMultiple >= 1.2 &&
            ((currentRMultiple >= 2.0 && overextendedFromKijun) || nearTimeCycleAndFlatTenkan)
        ) {
            val tenkanBuffer = trigSnap.atr14 * 0.15
            val candidateStop = if (isBuy) {
                round2(trigSnap.tenkan - tenkanBuffer)
            } else {
                round2(trigSnap.tenkan + tenkanBuffer)
            }
            val improvesStop = if (isBuy) {
                candidateStop > trade.stopLoss + trigSnap.atr14 * 0.05 && candidateStop < livePrice - trigSnap.atr14 * 0.1
            } else {
                candidateStop < trade.stopLoss - trigSnap.atr14 * 0.05 && candidateStop > livePrice + trigSnap.atr14 * 0.1
            }
            if (improvesStop) {
                val reason = if (nearTimeCycleAndFlatTenkan) {
                    "چرخه زمانی هوسودا (${report.waveProjection?.nearestTimeCycleNumber} کندل) و فلت شدن تنکان"
                } else {
                    String.format(Locale.US, "سود %.1fR و اشباع فاصله از کیجون (Preserve Mode منش پاتل)", currentRMultiple)
                }
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = candidateStop,
                    stageTitleFa = "🛡️ Preserve Mode (قفل سود پشت تنکان‌سن)",
                    reasonFa = reason,
                )
            }
        }

        // Stage 2: Kijun-sen Trailing once profit >= 1.3R
        if (currentRMultiple >= 1.3) {
            val kijunBuffer = trigSnap.atr14 * config.slBufferAtr
            val candidateStop = if (isBuy) {
                round2(trigSnap.kijun - kijunBuffer)
            } else {
                round2(trigSnap.kijun + kijunBuffer)
            }
            val improvesBeyondEntry = if (isBuy) {
                candidateStop >= trade.entry && candidateStop > trade.stopLoss + trigSnap.atr14 * 0.05 && candidateStop < livePrice
            } else {
                candidateStop <= trade.entry && candidateStop < trade.stopLoss - trigSnap.atr14 * 0.05 && candidateStop > livePrice
            }
            if (improvesBeyondEntry) {
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = candidateStop,
                    stageTitleFa = "📈 تریل استاپ با کیجون‌سن",
                    reasonFa = String.format(Locale.US, "سود %.1fR؛ انتقال حد ضرر پشت Kijun-sen", currentRMultiple),
                )
            }
        }

        // Stage 1: Break-Even at >= 1.0R
        if (currentRMultiple >= 1.0) {
            val feeOffset = max(trade.entry * 0.0008, trigSnap.atr14 * 0.05)
            val breakEvenStop = if (isBuy) round2(trade.entry + feeOffset) else round2(trade.entry - feeOffset)
            val notYetBreakEven = if (isBuy) {
                trade.stopLoss < trade.entry && breakEvenStop < livePrice
            } else {
                trade.stopLoss > trade.entry && breakEvenStop > livePrice
            }
            if (notYetBreakEven) {
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = breakEvenStop,
                    stageTitleFa = "🔒 ریسک‌فری خودکار (Break-Even در 1R)",
                    reasonFa = "عبور سود از 1R؛ انتقال حد ضرر به نقطه ورود",
                )
            }
        }

        return null
    }

    private fun mergeNearbyLevels(levels: List<Double>, tolerance: Double): List<Double> {
        if (levels.isEmpty()) return emptyList()
        val sorted = levels.sorted()
        val merged = mutableListOf<Double>()
        var clusterSum = sorted[0]
        var clusterCount = 1
        for (i in 1 until sorted.size) {
            val prevAvg = clusterSum / clusterCount
            if (abs(sorted[i] - prevAvg) <= tolerance) {
                clusterSum += sorted[i]
                clusterCount++
            } else {
                merged += clusterSum / clusterCount
                clusterSum = sorted[i]
                clusterCount = 1
            }
        }
        merged += clusterSum / clusterCount
        return merged
    }
}

private fun round2(v: Double): Double {
    if (!v.isFinite()) return 0.0
    val a = abs(v)
    val factor = when {
        a >= 500.0 -> 100.0
        a >= 10.0 -> 1_000.0
        a >= 0.01 -> 100_000.0
        else -> 1_000_000.0
    }
    return round(v * factor) / factor
}
