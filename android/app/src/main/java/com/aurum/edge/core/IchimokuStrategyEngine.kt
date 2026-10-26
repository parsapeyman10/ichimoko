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
}

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

enum class StrategyTriggerType(val titleFa: String) {
    PULLBACK_BOUNCE("ماشه ۱: پولبک به تعادل (Kijun/Tenkan/S-Point) و برگشت بالای تنکان"),
    VALIDATED_BREAKOUT("ماشه ۲: شکست معتبر کومو/کیجون با عبور از سقف/کف چیکو (ضد فیک‌بریک‌اوت فلت SSB)"),
    NONE("در انتظار تشکیل ماشه ورود روی کندل بسته"),
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
) {
    val isActionable: Boolean
        get() = (readiness == StrategyReadinessState.SIGNAL_BUY || readiness == StrategyReadinessState.SIGNAL_SELL) &&
            action != SignalAction.NO_TRADE &&
            suggestedEntry != null && suggestedStopLoss != null && primaryTakeProfit != null

    fun toAiStrategyContext(): String = buildString {
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
        )
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

        // ── Check 1: Current Kumo & Macro 4H Alignment ────────────────────────
        val stratKumoOk = if (isLong) stratSnap.isPriceAboveKumo else stratSnap.isPriceBelowKumo
        val macroKumoOk = if (!effectiveConfig.requireHigherTfAlignment || macroSnap == null) {
            true
        } else {
            if (isLong) !macroSnap.isPriceBelowKumo else !macroSnap.isPriceAboveKumo
        }
        val cond1Passed = stratKumoOk && macroKumoOk
        val cond1Detail = buildString {
            append("${config.strategyInterval.label}: قیمت ")
            append(if (stratSnap.isPriceAboveKumo) "بالای کومو (${stratSnap.kumoTop})" else if (stratSnap.isPriceBelowKumo) "زیر کومو (${stratSnap.kumoBottom})" else "داخل کومو")
            macroSnap?.let { m ->
                append(" · ${config.macroInterval.label}: ")
                append(if (m.isPriceAboveKumo) "بالای کومو" else if (m.isPriceBelowKumo) "زیر کومو" else "داخل کومو")
            }
        }

        // ── Check 2: Tenkan-sen / Kijun-sen Alignment & Slope (Koten / Gyakuten) ──
        val tkAligned = if (isLong) {
            stratSnap.tenkan >= stratSnap.kijun &&
                stratSnap.tenkan >= stratSnap.prevTenkan - stratSnap.atr14 * 0.05 &&
                stratSnap.kijun >= stratSnap.prevKijun - stratSnap.atr14 * 0.02
        } else {
            stratSnap.tenkan <= stratSnap.kijun &&
                stratSnap.tenkan <= stratSnap.prevTenkan + stratSnap.atr14 * 0.05 &&
                stratSnap.kijun <= stratSnap.prevKijun + stratSnap.atr14 * 0.02
        }
        val cond2Detail = "Tenkan=${stratSnap.tenkan} ${if (stratSnap.tenkan >= stratSnap.kijun) "≥" else "<"} Kijun=${stratSnap.kijun} (${if (stratSnap.isKijunFlat) "کیجون فلت/تعادلی" else "کیجون هم‌جهت"})"

        // ── Check 3: Future Kumo (26 periods ahead) & Non-diverging Slope ─────
        val futureKumoOk = if (!effectiveConfig.requireFutureKumoSlope) {
            true
        } else if (isLong) {
            stratSnap.isFutureKumoBullish && stratSnap.isFutureSpanARising && stratSnap.isFutureSpanBNonFalling
        } else {
            stratSnap.isFutureKumoBearish && stratSnap.isFutureSpanAFalling && stratSnap.isFutureSpanBNonRising
        }
        val cond3Detail = "Future SSA=${stratSnap.futureSpanA} vs SSB=${stratSnap.futureSpanB} (${if (stratSnap.isFutureKumoBullish) "ابر آینده صعودی" else "ابر آینده نزولی"})"

        // ── Check 4: Chikou Span "Open Space" (Vertical & 6-Bar Horizontal Corridor) ──
        val chikouOk = if (!effectiveConfig.requireChikouOpenSpace) {
            true
        } else if (isLong) {
            stratSnap.chikouBullishOpenSpace && trigSnap.chikouBullishOpenSpace
        } else {
            stratSnap.chikouBearishOpenSpace && trigSnap.chikouBearishOpenSpace
        }
        val cond4Detail = if (chikouOk) {
            "چیکو در ${config.strategyInterval.label} و ${config.triggerInterval.label} کاملاً در فضای باز (بدون برخورد با کندل/ابر تا ۶ کندل جلوتر)"
        } else {
            "چیکو درگیر کندل‌ها یا ابر ۲۶ دوره قبل است (عدم فضای باز)"
        }

        // ── Check 5: Rubber-Band Anti-Overextension from Kijun-sen ────────────
        val extAtr = stratSnap.distanceToKijunAtr
        val notOverextended = extAtr <= effectiveConfig.maxKijunExtensionAtr
        val cond5Detail = String.format(
            Locale.US,
            "فاصله از کیجون: %.2f برابر ATR (حداکثر مجاز: %.2f ATR)",
            extAtr,
            effectiveConfig.maxKijunExtensionAtr,
        )

        // ── Detect Trigger on 15m (Trigger 1: Pullback Bounce | Trigger 2: Validated Breakout) ──
        val closedTriggerBars = triggerCandles.filter { it.closed }.ifEmpty { triggerCandles }
        val triggerType = detectEntryTrigger(
            direction = candidateDirection,
            bars = closedTriggerBars,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            config = effectiveConfig,
        )

        // ── Compute Technical SL and Hosoda Wave Targets (V, N, E) ────────────
        val slBuffer = trigSnap.atr14 * effectiveConfig.slBufferAtr
        val technicalSl = if (isLong) {
            val baseSupport = min(trigSnap.kijun, stratSnap.kijun.coerceAtLeast(trigSnap.kumoBottom))
            round2(min(baseSupport - slBuffer, livePrice - trigSnap.atr14 * 0.8))
        } else {
            val baseResistance = max(trigSnap.kijun, stratSnap.kijun.coerceAtMost(trigSnap.kumoTop))
            round2(max(baseResistance + slBuffer, livePrice + trigSnap.atr14 * 0.8))
        }

        val wave = computeHosodaWave(strategyCandles, candidateDirection, stratSnap)
        val stopDist = abs(livePrice - technicalSl).coerceAtLeast(trigSnap.atr14 * 0.5)

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

        // ── Check 6: Room to Target / R:R >= minRiskReward ────────────────────
        val effectiveTp = if (nearestObstacle != null) {
            val distToObstacle = abs(nearestObstacle - livePrice)
            if (distToObstacle / stopDist >= effectiveConfig.minRiskReward) {
                // Obstacle is far enough that we can either target it or our wave target before it
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
        val cond6Passed = rr >= effectiveConfig.minRiskReward && obstacleClear
        val cond6Detail = buildString {
            append(String.format(Locale.US, "R:R = 1:%.2f (حداقل 1:%.1f)", rr, effectiveConfig.minRiskReward))
            if (nearestObstacle != null) {
                append(" · سطح فلت پیش‌رو: $nearestObstacle")
            }
        }

        val conditions = listOf(
            StrategyConditionCheck(1, "۱. موقعیت قیمت نسبت به کومو (${config.macroInterval.label}/${config.strategyInterval.label})", cond1Passed, cond1Detail),
            StrategyConditionCheck(2, "۲. چیدمان و شیب Tenkan / Kijun", tkAligned, cond2Detail),
            StrategyConditionCheck(3, "۳. ابر آینده (Future Kumo ۲۶ دوره جلوتر)", futureKumoOk, cond3Detail),
            StrategyConditionCheck(4, "۴. فضای باز چیکو (Chikou Open Space)", chikouOk, cond4Detail),
            StrategyConditionCheck(5, "۵. قانون کش لاستیکی (عدم اشباع فاصله از کیجون)", notOverextended, cond5Detail),
            StrategyConditionCheck(6, "۶. فضای کافی تا دیوار فلت بعدی و R:R", cond6Passed, cond6Detail),
        )

        val allSixPassed = conditions.all { it.passed }
        val triggerFired = triggerType != StrategyTriggerType.NONE
        val passedCount = conditions.count { it.passed }
        val confidence = round2((passedCount * 14.0 + if (triggerFired) 16.0 else 0.0).coerceIn(0.0, 100.0))

        val readiness = when {
            allSixPassed && triggerFired && isLong -> StrategyReadinessState.SIGNAL_BUY
            allSixPassed && triggerFired && !isLong -> StrategyReadinessState.SIGNAL_SELL
            passedCount >= 4 -> StrategyReadinessState.WATCH_SETUP
            else -> StrategyReadinessState.BLOCKED
        }

        val finalAction = when (readiness) {
            StrategyReadinessState.SIGNAL_BUY -> SignalAction.BUY
            StrategyReadinessState.SIGNAL_SELL -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }

        val summaryFa = when (readiness) {
            StrategyReadinessState.SIGNAL_BUY ->
                "✅ سیگنال خرید (Long) روی $normalizedSymbol توسط «$effectiveTitle» صادر شد (${triggerType.titleFa})"
            StrategyReadinessState.SIGNAL_SELL ->
                "✅ سیگنال فروش (Short) روی $normalizedSymbol توسط «$effectiveTitle» صادر شد (${triggerType.titleFa})"
            StrategyReadinessState.WATCH_SETUP ->
                "⏳ $passedCount از ۶ شرط همگرایی روی $normalizedSymbol برقرار است؛ در انتظار بسته شدن کندل ماشه ورود در تایم‌فریم ${config.triggerInterval.label}"
            StrategyReadinessState.BLOCKED -> {
                val firstFailed = conditions.firstOrNull { !it.passed }
                "🛑 ورود روی $normalizedSymbol مجاز نیست: ${firstFailed?.titleFa ?: ""} (${firstFailed?.detailFa ?: ""})"
            }
            StrategyReadinessState.INSUFFICIENT_DATA ->
                "در حال دریافت کندل‌های مولتی‌تایم‌فریم $normalizedSymbol..."
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
            suggestedEntry = round2(livePrice),
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
        )
    }

    /**
     * تشخیص ماشه ورود روی کندل بسته‌شده تایم‌فریم ورود (`15m`):
     * - ماشه ۱ (پولبک): اصلاح به نزدیکی کیجون/تنکان/سقف ابر (نقطه S) بدون شکست کامل کیجون، و بسته شدن کندل جدید در جهت روند بالای/زیر تنکان‌سن.
     * - ماشه ۲ (شکست): خروج کندل از کومو/کیجون با عبور از سقف/کف اخیر چیکو + بافر ضد فیک‌بریک‌اوت در صورت فلت بودن SSB.
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
            }
        } else {
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
