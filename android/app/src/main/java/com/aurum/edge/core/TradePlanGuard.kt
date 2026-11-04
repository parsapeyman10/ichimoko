package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * نتیجهٔ داوری یک پلن معاملاتی (ورود / حد ضرر / حد سود / اهرم).
 */
enum class TradePlanVerdict(val badgeFa: String) {
    /** پلن در همان محدودهٔ نوسان و ساختار تایم‌فریم است و نیاز به اصلاح نداشت. */
    VALID("✅ تأییدشده"),

    /** پلن از دایرهٔ مجاز بیرون بود و موتور، حد ضرر/حد سود/اهرم را به محدودهٔ سالم برگرداند. */
    ADJUSTED("⚠️ اصلاح‌شده"),

    /** پلن قابل استفاده نیست (سمت اشتباه، دادهٔ قیمتی نامعتبر یا هدف غیرقابل‌دسترس). */
    REJECTED("⛔ رد شد"),
}

/**
 * محدودهٔ مجاز «حد ضرر / حد سود / اهرم» برای یک نماد در یک تایم‌فریم مشخص.
 *
 * مرجع اعداد: نوسان واقعی همان کلاس دارایی روی همان تایم‌فریم (ATR نسبی) و قواعد کارگزاران
 * (سقف اهرم ESMA/BaFin برای CFD و مرجع رمزارز) — نه درصدهای دلبخواه.
 *
 * @property minStopPct کف فاصلهٔ حد ضرر به‌صورت درصدی از قیمت (زیر آن، حد ضرر داخل نویز است).
 * @property maxStopPct سقف فاصلهٔ حد ضرر به‌صورت درصدی از قیمت (بالاتر از آن، ۱R متورم و هدف دست‌نیافتنی می‌شود).
 * @property maxTargetPct سقف فاصلهٔ حد سود به‌صورت درصدی از قیمت تا هدف فقط روی کاغذ نباشد.
 */
data class TradePlanBounds(
    val assetClass: AssetClass,
    val interval: Interval,
    val minStopPct: Double,
    val maxStopPct: Double,
    val maxTargetPct: Double,
    val atrFactorMin: Double,
    val atrFactorMax: Double,
    val maxTargetAtr: Double,
    val venueLeverageCap: Int,
    val liquidationBufferR: Double,
    val maintenanceMarginPct: Double,
) {
    fun describeFa(): String = String.format(
        Locale.US,
        "دالان حد ضرر %s: %.2f%% .. %.2f%% قیمت (%.2f..%.2f ATR) · سقف حد سود %.2f%% · اهرم مرجع ≤ %dx",
        interval.label,
        minStopPct * 100.0,
        maxStopPct * 100.0,
        atrFactorMin,
        atrFactorMax,
        maxTargetPct * 100.0,
        venueLeverageCap,
    )
}

/**
 * پلن نهایی و ایمن‌شدهٔ یک معامله به‌همراه دلیل اصلاح‌ها.
 */
data class GuardedTradePlan(
    val symbol: String,
    val action: SignalAction,
    val interval: Interval,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val stopDistance: Double,
    val targetDistance: Double,
    val riskReward: Double,
    val optimalRiskReward: Double,
    val stopPct: Double,
    val targetPct: Double,
    val atr: Double,
    val recommendedLeverage: Int,
    val liquidationPrice: Double,
    val liquidationBufferR: Double,
    val verdict: TradePlanVerdict,
    val reasonsFa: List<String>,
) {
    val isUsable: Boolean
        get() = verdict != TradePlanVerdict.REJECTED && stopDistance > 0.0 && targetDistance > 0.0

    fun summaryFa(): String = if (reasonsFa.isEmpty()) {
        String.format(
            Locale.US,
            "%s · ۱R = %.2f%% قیمت · هدف %.2f%% · R:R 1:%.2f",
            verdict.badgeFa,
            stopPct * 100.0,
            targetPct * 100.0,
            riskReward,
        )
    } else {
        String.format(
            Locale.US,
            "%s · ۱R = %.2f%% قیمت · هدف %.2f%% · R:R 1:%.2f · %s",
            verdict.badgeFa,
            stopPct * 100.0,
            targetPct * 100.0,
            riskReward,
            reasonsFa.joinToString("؛ "),
        )
    }
}

/**
 * تصمیم اهرم به‌همراه قیمت لیکوییدیشن و دلیل انتخاب.
 */
data class LeverageDecision(
    val leverage: Int,
    val venueCap: Int,
    val liquidationPrice: Double,
    val liquidationBufferR: Double,
    val marginUsd: Double,
    val marginRequiredLeverage: Int,
    val bindingConstraintFa: String,
) {
    fun reasonFa(): String = String.format(
        Locale.US,
        "اهرم %dx = کمینه(سقف مرجع %dx، سقف ضدلیکویید %.1fR) · لیکوییدیشن %s (%.1fR دورتر از حد ضرر) · %s",
        leverage,
        venueCap,
        TradePlanGuard.MIN_LIQUIDATION_BUFFER_R,
        formatPriceSmart(liquidationPrice),
        liquidationBufferR,
        bindingConstraintFa,
    )

    companion object {
        fun formatPriceSmart(value: Double): String {
            if (!value.isFinite() || value <= 0.0) return "-"
            return when {
                value >= 1000.0 -> String.format(Locale.US, "%.2f", value)
                value >= 1.0 -> String.format(Locale.US, "%.3f", value)
                else -> String.format(Locale.US, "%.5f", value)
            }
        }
    }
}

/**
 * گزارش داوری یک پلن (بدون اصلاح) — برای پاسخ به این پرسش که «حد ضرر و حد سود کدام نمادها درست است
 * و کدام نمادها از دایرهٔ نوسان بیرون زده است».
 */
data class TradePlanAudit(
    val symbol: String,
    val interval: Interval,
    val action: SignalAction,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val stopPct: Double,
    val targetPct: Double,
    val riskReward: Double,
    val recommendedLeverage: Int,
    val verdict: TradePlanVerdict,
    val violationsFa: List<String>,
) {
    fun lineFa(): String {
        val sideFa = if (action == SignalAction.BUY) "خرید" else if (action == SignalAction.SELL) "فروش" else "بدون معامله"
        val detail = violationsFa.firstOrNull() ?: "ساختار و نوسان هم‌تراز"
        return String.format(
            Locale.US,
            "%s %s · %s · SL %.2f%% · TP %.2f%% · R:R 1:%.2f · اهرم %dx ← %s",
            symbol,
            interval.label,
            sideFa,
            stopPct * 100.0,
            targetPct * 100.0,
            riskReward,
            recommendedLeverage,
            detail,
        )
    }
}

/**
 * # موتور واحد اعتبارسنجی و ایمن‌سازی «حد ضرر، حد سود و اهرم» (Trade Plan Guard)
 *
 * ## مشکل قبلی
 * ۱) `computeOptimalStructuralStopLoss` داخل دالان مجاز، همیشه **دورترین** سنگر ساختاری را انتخاب می‌کرد
 *    (`minOrNull` برای خرید و `maxOrNull` برای فروش)؛ یعنی ۱R عملاً تا سقف `2.10 × ATR` تورم می‌خورد.
 * ۲) هیچ سقف درصدی نبود؛ ATR متورم (یک کندل گلِیچ، اختلاف اسپات/فیوچرز یا سوئیچ فید) کل مسیر
 *    «حد ضرر ← حد سود» را چند برابر می‌کرد و نتیجه‌ای مثل `SL 5.66% / TP 14.13%` روی چارت ۵ دقیقه می‌ساخت.
 * ۳) مسیر «نقطه‌زنی» (`applyIncomingTick`) پلن را به قیمت لحظه‌ای اجرا لنگر نمی‌انداخت؛ با فاصله گرفتن قیمت
 *    از لنگرِ گزارش، حد ضرر عملاً بی‌معنی می‌شد.
 * ۴) اهرم فقط «سقف مرجع نماد» بود و هیچ نسبتی با فاصلهٔ حد ضرر، لیکوییدیشن و مارجین نداشت.
 *
 * ## متود فعلی (SOTA)
 * - **دالان نوسان تطبیقی**: حد ضرر بین `max(0.80 × ATR، کف نویز نماد)` و `min(2.10 × ATR، سقف درصدی تایم‌فریم)`
 *   قفل می‌شود؛ بنابراین ۱R هرگز از نوسان واقعی همان تایم‌فریم بزرگ‌تر نمی‌شود.
 * - **ATR مقاوم**: ATR ورودی داخل دالان `[maxStopPct/9 .. maxStopPct/1.6]` برش می‌خورد تا یک کندل خراب
 *   نتواند کل پلن را متورم کند.
 * - **هدف قابل‌دسترس**: حد سود با ضریب R:R دلخواه (`۱.۵ .. ۳.۰`) ساخته می‌شود اما هرگز از سقف درصدی/ATR
 *   فراتر نمی‌رود؛ اگر هدف ساختاری نزدیک‌تر بود، حد ضرر به‌جای دور کردن هدف، جمع می‌شود.
 * - **لنگر اجرا (Re-anchor)**: پلن گزارش با اختلاف قیمت لحظه‌ای هم‌تراز می‌شود؛ اگر قیمت بیش از حد از لنگر
 *   فاصله گرفته باشد (`Staleness Guard`) پلن **رد** می‌شود تا با اعداد کهنه معامله باز نشود.
 * - **اهرم متناسب**: `lev = min(سقف مرجع، floor(1 / (stopPct × 2.5 + کارمزد + مارجین نگهداری)))`
 *   یعنی لیکوییدیشن همیشه حداقل `۲.۵R` دورتر از حد ضرر است و مارجین لازم گزارش می‌شود.
 */
object TradePlanGuard {

    const val MIN_RR: Double = 1.5
    const val MAX_RR: Double = 3.0
    const val MIN_LIQUIDATION_BUFFER_R: Double = 2.5
    const val MAX_MARGIN_FRACTION: Double = 0.30
    const val MAINTENANCE_MARGIN_PCT: Double = 0.005
    const val MAX_LEVERAGE: Int = 10

    /** ضرایب نوسان پایه برای تایم‌فریم ۱۵ دقیقه (با √زمان برای بقیهٔ تایم‌فریم‌ها مقیاس می‌شود). */
    private const val CRYPTO_MIN_STOP_PCT_15M = 0.0016
    private const val CRYPTO_MAX_STOP_PCT_15M = 0.0160
    private const val FOREX_MIN_STOP_PCT_15M = 0.0003
    private const val FOREX_MAX_STOP_PCT_15M = 0.0028
    private const val COMMODITY_MIN_STOP_PCT_15M = 0.0007
    private const val COMMODITY_MAX_STOP_PCT_15M = 0.0055
    private const val STOCK_MIN_STOP_PCT_15M = 0.0006
    private const val STOCK_MAX_STOP_PCT_15M = 0.0050

    /** سقف مطلق درصدی برای اینکه هیچ نمادی (حتی با ATR خراب) نتواند ۱R چنددرصدی بسازد. */
    private const val ABSOLUTE_MAX_STOP_PCT = 0.065
    private const val ABSOLUTE_MIN_STOP_PCT = 0.00018

    /**
     * دالان مجاز حد ضرر/حد سود برای نماد و تایم‌فریم.
     */
    fun boundsFor(symbol: String, interval: Interval): TradePlanBounds {
        val assetClass = AssetClass.of(symbol)
        val scale = sqrt(interval.minutes.coerceAtLeast(1).toDouble() / 15.0)
        val rawMin = when (assetClass) {
            AssetClass.CRYPTO -> CRYPTO_MIN_STOP_PCT_15M
            AssetClass.FOREX -> FOREX_MIN_STOP_PCT_15M
            AssetClass.COMMODITY -> COMMODITY_MIN_STOP_PCT_15M
            AssetClass.STOCK -> STOCK_MIN_STOP_PCT_15M
        }
        val rawMax = when (assetClass) {
            AssetClass.CRYPTO -> CRYPTO_MAX_STOP_PCT_15M
            AssetClass.FOREX -> FOREX_MAX_STOP_PCT_15M
            AssetClass.COMMODITY -> COMMODITY_MAX_STOP_PCT_15M
            AssetClass.STOCK -> STOCK_MAX_STOP_PCT_15M
        }
        val maxStopPct = (rawMax * scale).coerceIn(ABSOLUTE_MIN_STOP_PCT * 2.0, ABSOLUTE_MAX_STOP_PCT)
        val minStopPct = (rawMin * scale).coerceIn(ABSOLUTE_MIN_STOP_PCT, maxStopPct * 0.40)
        val venueCap = min(MAX_LEVERAGE, PaperOrderRules.defaultLeverageFor(symbol)).coerceAtLeast(1)
        return TradePlanBounds(
            assetClass = assetClass,
            interval = interval,
            minStopPct = minStopPct,
            maxStopPct = maxStopPct,
            // هدف هرگز بیش از ۱.۸ برابر سقف حد ضرر دور نمی‌شود (پس R:R حداقل ۱.۵ باقی می‌ماند).
            maxTargetPct = maxStopPct * 1.80,
            atrFactorMin = 0.80,
            atrFactorMax = 2.10,
            maxTargetAtr = 3.40,
            venueLeverageCap = venueCap,
            liquidationBufferR = MIN_LIQUIDATION_BUFFER_R,
            maintenanceMarginPct = MAINTENANCE_MARGIN_PCT,
        )
    }

    /**
     * ATR مقاوم: ATR ورودی داخل دالان نوسان همان کلاس/تایم‌فریم برش می‌خورد تا یک کندل خراب
     * یا بیسیس اسپات-فیوچرز، ۱R را چند برابر نکند.
     */
    fun robustAtr(symbol: String, price: Double, atrValue: Double, interval: Interval): Double {
        val safePrice = if (price.isFinite() && price > 0.0) price else return 0.0
        val bounds = boundsFor(symbol, interval)
        val atrFloor = safePrice * bounds.maxStopPct / 9.0
        val atrCeiling = safePrice * bounds.maxStopPct / 1.6
        val fallback = safePrice * bounds.maxStopPct / 2.6
        val raw = if (atrValue.isFinite() && atrValue > 0.0) atrValue else fallback
        return raw.coerceIn(atrFloor, atrCeiling)
    }

    /** کف فاصلهٔ حد ضرر (نویز + ساختار + ATR). */
    fun stopDistanceFloor(
        symbol: String,
        price: Double,
        atr: Double,
        interval: Interval,
        structuralFloorOverride: Double = 0.0,
    ): Double {
        val bounds = boundsFor(symbol, interval)
        val corridorCap = bounds.maxStopPct * price
        val structuralFloor = if (structuralFloorOverride.isFinite() && structuralFloorOverride > 0.0) {
            structuralFloorOverride
        } else {
            IchimokuStrategyEngine.minSafeStructuralStopDistance(
                symbol = symbol,
                referencePrice = price,
                trigAtr14 = atr,
                stratAtr14 = atr,
                triggerInterval = interval,
            )
        }
        // کف اسپرد/تیک مرجع هم داخل دالان نوسان برش می‌خورد؛ اگر اسپرد نماد از سقف نوسان بزرگ‌تر بود،
        // اولویت با سقف نوسان همان تایم‌فریم است (وگرنه ۱R مصنوعی و غیرقابل‌اجرا می‌شد).
        val venueFloor = min(VenueSpecs.minStopDistance(symbol, price) * 2.0, corridorCap)
        return maxOf(
            min(structuralFloor, corridorCap),
            bounds.minStopPct * price,
            bounds.atrFactorMin * atr,
            venueFloor,
        ).coerceAtMost(corridorCap)
    }

    /** سقف فاصلهٔ حد ضرر (سقف نوسان تایم‌فریم و سقف ATR). */
    fun stopDistanceCeiling(symbol: String, price: Double, atr: Double, interval: Interval): Double {
        val bounds = boundsFor(symbol, interval)
        return maxOf(
            bounds.minStopPct * price,
            min(bounds.maxStopPct * price, bounds.atrFactorMax * atr),
        )
    }

    /** حداکثر فاصلهٔ مجاز قیمت لحظه‌ای از لنگر پلن، قبل از اینکه پلن «کهنه» تلقی شود. */
    fun stalenessLimit(symbol: String, price: Double, atr: Double, interval: Interval): Double {
        val bounds = boundsFor(symbol, interval)
        val safeAtr = if (atr.isFinite() && atr > 0.0) atr else price * bounds.maxStopPct / 2.6
        return maxOf(1.25 * safeAtr, 3.00 * bounds.minStopPct * price)
    }

    /**
     * ایمن‌سازی یک پلن خام (حد ضرر ساختاری + هدف موج هوسودا) به پلن قابل اجرا.
     *
     * @param anchorEntryPrice قیمتی که حد ضرر خام نسبت به آن اندازه‌گیری شده (لنگر گزارش)؛
     *        اگر با [entryPrice] فرق داشته باشد، فاصلهٔ ساختاری حفظ و روی قیمت اجرا منتقل می‌شود.
     */
    fun sanitizePlan(
        symbol: String,
        action: SignalAction,
        entryPrice: Double,
        candidateStopLoss: Double?,
        candidateTakeProfit: Double?,
        desiredRiskReward: Double = MIN_RR,
        referenceAtr: Double = 0.0,
        triggerInterval: Interval = Interval.M15,
        strategyAtr: Double = referenceAtr,
        anchorEntryPrice: Double = entryPrice,
    ): GuardedTradePlan {
        val interval = triggerInterval
        val normalizedSymbol = V1Universe.normalize(symbol).ifBlank { symbol }
        val direction = if (action == SignalAction.SELL) -1.0 else 1.0
        val emptyPlan = GuardedTradePlan(
            symbol = normalizedSymbol,
            action = action,
            interval = interval,
            entryPrice = entryPrice,
            stopLoss = 0.0,
            takeProfit = 0.0,
            stopDistance = 0.0,
            targetDistance = 0.0,
            riskReward = 0.0,
            optimalRiskReward = clampRr(desiredRiskReward),
            stopPct = 0.0,
            targetPct = 0.0,
            atr = 0.0,
            recommendedLeverage = 1,
            liquidationPrice = 0.0,
            liquidationBufferR = 0.0,
            verdict = TradePlanVerdict.REJECTED,
            reasonsFa = listOf("قیمت ورود نامعتبر است"),
        )

        if (!entryPrice.isFinite() || entryPrice <= 0.0) return emptyPlan
        if (action == SignalAction.NO_TRADE) return emptyPlan

        val bounds = boundsFor(normalizedSymbol, interval)
        val atr = robustAtr(
            symbol = normalizedSymbol,
            price = entryPrice,
            atrValue = if (referenceAtr.isFinite() && referenceAtr > 0.0) referenceAtr else strategyAtr,
            interval = interval,
        )
        val structuralFloor = IchimokuStrategyEngine.minSafeStructuralStopDistance(
            symbol = normalizedSymbol,
            referencePrice = entryPrice,
            trigAtr14 = atr,
            stratAtr14 = robustAtr(
                symbol = normalizedSymbol,
                price = entryPrice,
                atrValue = if (strategyAtr.isFinite() && strategyAtr > 0.0) strategyAtr else atr,
                interval = interval,
            ),
            triggerInterval = interval,
        )
        val floorDist = stopDistanceFloor(
            symbol = normalizedSymbol,
            price = entryPrice,
            atr = atr,
            interval = interval,
            structuralFloorOverride = structuralFloor,
        )
        val ceilingDist = max(floorDist, stopDistanceCeiling(normalizedSymbol, entryPrice, atr, interval))

        val anchor = if (anchorEntryPrice.isFinite() && anchorEntryPrice > 0.0) anchorEntryPrice else entryPrice
        val rawStopDist = if (candidateStopLoss != null && candidateStopLoss.isFinite() && candidateStopLoss > 0.0) {
            val onCorrectSide = if (direction > 0) candidateStopLoss < anchor else candidateStopLoss > anchor
            if (onCorrectSide) abs(anchor - candidateStopLoss) else 0.0
        } else {
            0.0
        }
        val desiredRr = clampRr(desiredRiskReward)
        // سقف هدف: نه دورتر از سقف درصدی تایم‌فریم و نه دورتر از ضریب ATR مجاز
        // حاشیهٔ ایمنی ۰.۵٪ روی سقف‌ها تا گرد شدن روی تیک، پلن را از دالان بیرون نبرد
        val safety = 0.995
        val targetCeiling = maxOf(
            floorDist * MIN_RR,
            minOf(bounds.maxTargetPct * entryPrice, bounds.maxTargetAtr * atr) * safety,
        )
        // اگر هدف قابل‌دسترس کوچک‌تر از R:R دلخواه باشد، حد ضرر جمع می‌شود (نه اینکه هدف غیرواقعی دور شود)
        val stopCeiling = maxOf(floorDist, minOf(ceilingDist, targetCeiling / desiredRr) * safety)
        val resolvedStopDist = if (rawStopDist > 0.0) rawStopDist else floorDist
        val stopDistance = resolvedStopDist.coerceIn(floorDist, stopCeiling)
        val rawTargetDist = if (candidateTakeProfit != null && candidateTakeProfit.isFinite() && candidateTakeProfit > 0.0) {
            val onCorrectSide = if (direction > 0) candidateTakeProfit > anchor else candidateTakeProfit < anchor
            if (onCorrectSide) abs(candidateTakeProfit - anchor) else 0.0
        } else {
            0.0
        }
        val structuredTargetDist = if (rawTargetDist > 0.0 && rawTargetDist / stopDistance >= MIN_RR - 1e-9) {
            rawTargetDist
        } else {
            stopDistance * desiredRr
        }
        val targetUpperBound = max(stopDistance * MIN_RR, min(stopDistance * MAX_RR, targetCeiling))
        val targetDistance = structuredTargetDist.coerceIn(stopDistance * MIN_RR, targetUpperBound)

        val stopLoss = roundToTick(normalizedSymbol, (entryPrice - direction * stopDistance).coerceAtLeast(0.00001))
        val takeProfit = roundToTick(normalizedSymbol, (entryPrice + direction * targetDistance).coerceAtLeast(0.00001))
        val finalStop = abs(entryPrice - stopLoss).coerceAtLeast(1e-9)
        val finalTarget = abs(takeProfit - entryPrice).coerceAtLeast(1e-9)
        val rr = (finalTarget / finalStop).coerceIn(MIN_RR, MAX_RR)

        val reasons = mutableListOf<String>()
        if (abs(finalStop - resolvedStopDist) / max(resolvedStopDist, 1e-9) > 0.01) {
            val why = if (finalStop > resolvedStopDist) "حد ضرر ساختاری از سقف نوسان این تایم‌فریم دورتر بود و به سقف مجاز جمع شد" else "حد ضرر داخل نویز بود و به کف ایمن گسترده شد"
            reasons += why
        }
        if (abs(finalTarget - structuredTargetDist) / max(structuredTargetDist, 1e-9) > 0.03) {
            reasons += "حد سود به محدودهٔ قابل‌دسترس (سقف درصدی/ATR) قفل شد"
        }
        if (abs(rr - desiredRr) > 0.05) {
            reasons += String.format(Locale.US, "R:R از 1:%.2f به 1:%.2f تنظیم شد", desiredRr, rr)
        }
        if (referenceAtr.isFinite() && referenceAtr > 0.0 && abs(referenceAtr - atr) / atr > 0.25) {
            reasons += "ATR غیرعادی (دادهٔ مشکوک) برش خورد"
        }
        val verdict = if (reasons.isEmpty()) TradePlanVerdict.VALID else TradePlanVerdict.ADJUSTED

        val leverage = decideLeverage(
            symbol = normalizedSymbol,
            action = action,
            entryPrice = entryPrice,
            stopDistance = finalStop,
        )
        return GuardedTradePlan(
            symbol = normalizedSymbol,
            action = action,
            interval = interval,
            entryPrice = roundToTick(normalizedSymbol, entryPrice),
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            stopDistance = finalStop,
            targetDistance = finalTarget,
            riskReward = round2(rr),
            optimalRiskReward = round2(clampRr(max(desiredRr, rr))),
            stopPct = finalStop / entryPrice,
            targetPct = finalTarget / entryPrice,
            atr = atr,
            recommendedLeverage = leverage.leverage,
            liquidationPrice = leverage.liquidationPrice,
            liquidationBufferR = leverage.liquidationBufferR,
            verdict = verdict,
            reasonsFa = reasons,
        )
    }

    /**
     * انتقال پلن گزارش به قیمت لحظه‌ای اجرا + کهنگی‌سنجی.
     * اگر قیمت بازار بیش از [stalenessLimit] از لنگر پلن فاصله گرفته باشد، پلن کهنه است و رد می‌شود.
     */
    fun rebaseToLive(
        symbol: String,
        action: SignalAction,
        liveEntryPrice: Double,
        planAnchorEntry: Double,
        planStopLoss: Double,
        planTakeProfit: Double,
        referenceAtr: Double = 0.0,
        triggerInterval: Interval = Interval.M15,
        strategyAtr: Double = referenceAtr,
        desiredRiskReward: Double = MIN_RR,
    ): GuardedTradePlan {
        if (!liveEntryPrice.isFinite() || liveEntryPrice <= 0.0) {
            return sanitizePlan(
                symbol = symbol,
                action = action,
                entryPrice = liveEntryPrice,
                candidateStopLoss = planStopLoss,
                candidateTakeProfit = planTakeProfit,
                desiredRiskReward = desiredRiskReward,
                referenceAtr = referenceAtr,
                triggerInterval = triggerInterval,
                strategyAtr = strategyAtr,
            )
        }
        val anchor = if (planAnchorEntry.isFinite() && planAnchorEntry > 0.0) planAnchorEntry else liveEntryPrice
        val drift = abs(liveEntryPrice - anchor)
        val atr = robustAtr(symbol, liveEntryPrice, referenceAtr, triggerInterval)
        val limit = stalenessLimit(symbol, liveEntryPrice, atr, triggerInterval)
        if (drift > limit) {
            val pct = drift / liveEntryPrice * 100.0
            return GuardedTradePlan(
                symbol = V1Universe.normalize(symbol).ifBlank { symbol },
                action = action,
                interval = triggerInterval,
                entryPrice = liveEntryPrice,
                stopLoss = 0.0,
                takeProfit = 0.0,
                stopDistance = 0.0,
                targetDistance = 0.0,
                riskReward = 0.0,
                optimalRiskReward = clampRr(desiredRiskReward),
                stopPct = 0.0,
                targetPct = 0.0,
                atr = atr,
                recommendedLeverage = 1,
                liquidationPrice = 0.0,
                liquidationBufferR = 0.0,
                verdict = TradePlanVerdict.REJECTED,
                reasonsFa = listOf(
                    String.format(
                        Locale.US,
                        "پلن کهنه است: قیمت لحظه‌ای %.2f%% از لنگر پلن فاصله گرفته (سقف مجاز %.2f%%)",
                        pct,
                        limit / liveEntryPrice * 100.0,
                    ),
                ),
            )
        }
        val shiftedSl = if (planStopLoss.isFinite() && planStopLoss > 0.0) planStopLoss + (liveEntryPrice - anchor) else Double.NaN
        val shiftedTp = if (planTakeProfit.isFinite() && planTakeProfit > 0.0) planTakeProfit + (liveEntryPrice - anchor) else Double.NaN
        return sanitizePlan(
            symbol = symbol,
            action = action,
            entryPrice = liveEntryPrice,
            candidateStopLoss = shiftedSl,
            candidateTakeProfit = shiftedTp,
            desiredRiskReward = desiredRiskReward,
            referenceAtr = referenceAtr,
            triggerInterval = triggerInterval,
            strategyAtr = strategyAtr,
            anchorEntryPrice = liveEntryPrice,
        )
    }

    /**
     * اهرم متناسب با فاصلهٔ حد ضرر:
     * `lev = min(سقف مرجع نماد، floor(1 / (stopPct × 2.5 + کارمزد رفت‌وبرگشت + مارجین نگهداری)))`
     * تا لیکوییدیشن همیشه حداقل `۲.۵R` دورتر از حد ضرر بماند.
     */
    fun decideLeverage(
        symbol: String,
        action: SignalAction,
        entryPrice: Double,
        stopDistance: Double,
        notionalUsd: Double = 0.0,
        accountBalance: Double = 0.0,
    ): LeverageDecision {
        val venueCap = min(MAX_LEVERAGE, PaperOrderRules.defaultLeverageFor(symbol)).coerceAtLeast(1)
        val spec = VenueSpecs.of(symbol)
        val feePct = (spec.takerFeeBps * 2.0 + spec.spreadBps) / 10_000.0
        val maintenancePct = if (AssetClass.of(symbol) == AssetClass.CRYPTO) MAINTENANCE_MARGIN_PCT else MAINTENANCE_MARGIN_PCT * 0.8
        val safeEntry = if (entryPrice.isFinite() && entryPrice > 0.0) entryPrice else 1.0
        val safeStop = if (stopDistance.isFinite() && stopDistance > 0.0) stopDistance else safeEntry * 0.01
        val stopPct = safeStop / safeEntry
        val denominator = stopPct * MIN_LIQUIDATION_BUFFER_R + maintenancePct + feePct
        val maxByLiquidation = if (denominator > 0.0) floor(1.0 / denominator).toInt() else MAX_LEVERAGE
        val leverage = min(venueCap, maxByLiquidation.coerceAtLeast(1)).coerceAtLeast(1)

        val marginRequiredLeverage = if (
            accountBalance.isFinite() && accountBalance > 0.0 &&
            notionalUsd.isFinite() && notionalUsd > 0.0
        ) {
            ceil(notionalUsd / (accountBalance * MAX_MARGIN_FRACTION)).toInt().coerceAtLeast(1)
        } else {
            1
        }
        val margin = if (leverage > 0) notionalUsd / leverage else 0.0
        val binding = when {
            maxByLiquidation < venueCap -> "سقف ضدلیکویید فعال شد (فاصلهٔ حد ضرر پهن است)"
            marginRequiredLeverage > leverage -> "مارجین لازم از کسر مجاز حساب (۳۰٪) بیشتر است؛ حجم را کوچک‌تر کنید"
            else -> "سقف اهرم مرجع همان بازار فعال است"
        }
        val liquidation = liquidationPrice(action, safeEntry, leverage)
        val bufferR = if (safeStop > 0.0) abs(liquidation - safeEntry) / safeStop else 0.0
        return LeverageDecision(
            leverage = leverage,
            venueCap = venueCap,
            liquidationPrice = liquidation,
            liquidationBufferR = round2(bufferR),
            marginUsd = round2(margin),
            marginRequiredLeverage = marginRequiredLeverage,
            bindingConstraintFa = binding,
        )
    }

    /** قیمت تقریبی لیکوییدیشن با مارجین ایزوله (کارمزد و مارجین نگهداری لحاظ می‌شود). */
    fun liquidationPrice(action: SignalAction, entryPrice: Double, leverage: Int): Double {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return 0.0
        val safeLev = leverage.coerceIn(1, MAX_LEVERAGE)
        val distancePct = (1.0 / safeLev - MAINTENANCE_MARGIN_PCT).coerceIn(0.005, 0.999)
        return if (action == SignalAction.SELL) {
            roundToTick("", entryPrice * (1.0 + distancePct))
        } else {
            roundToTick("", entryPrice * (1.0 - distancePct).coerceAtLeast(0.00001))
        }
    }

    /** حداکثر اهرم ایمن برای یک فاصلهٔ حد ضرر مشخص (مستقل از موجودی حساب). */
    fun maxSafeLeverage(symbol: String, action: SignalAction, entryPrice: Double, stopDistance: Double): Int =
        decideLeverage(symbol = symbol, action = action, entryPrice = entryPrice, stopDistance = stopDistance).leverage

    /**
     * داوری یک پلن موجود و فهرست تخلف‌های آن (بدون تغییر اعداد).
     */
    fun audit(
        symbol: String,
        action: SignalAction,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double,
        referenceAtr: Double = 0.0,
        interval: Interval = Interval.M15,
    ): TradePlanAudit {
        val normalizedSymbol = V1Universe.normalize(symbol).ifBlank { symbol }
        val bounds = boundsFor(normalizedSymbol, interval)
        val violations = mutableListOf<String>()
        var fatal = false

        if (!entryPrice.isFinite() || entryPrice <= 0.0) {
            return TradePlanAudit(
                symbol = normalizedSymbol,
                interval = interval,
                action = action,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
                stopPct = 0.0,
                targetPct = 0.0,
                riskReward = 0.0,
                recommendedLeverage = 1,
                verdict = TradePlanVerdict.REJECTED,
                violationsFa = listOf("قیمت ورود نامعتبر است"),
            )
        }
        val stopPct = if (stopLoss.isFinite() && stopLoss > 0.0) abs(entryPrice - stopLoss) / entryPrice else 0.0
        val targetPct = if (takeProfit.isFinite() && takeProfit > 0.0) abs(takeProfit - entryPrice) / entryPrice else 0.0
        val rr = if (stopPct > 0.0) targetPct / stopPct else 0.0

        val stopOnCorrectSide = when (action) {
            SignalAction.BUY -> stopLoss < entryPrice
            SignalAction.SELL -> stopLoss > entryPrice
            SignalAction.NO_TRADE -> false
        }
        if (!stopOnCorrectSide) {
            violations += "حد ضرر در سمت اشتباه قیمت ورود است"
            fatal = true
        }
        val targetOnCorrectSide = when (action) {
            SignalAction.BUY -> takeProfit > entryPrice
            SignalAction.SELL -> takeProfit < entryPrice
            SignalAction.NO_TRADE -> false
        }
        if (!targetOnCorrectSide) {
            violations += "حد سود در سمت اشتباه قیمت ورود است"
            fatal = true
        }
        if (stopPct > bounds.maxStopPct * 1.02) {
            violations += String.format(Locale.US, "۱R (%.2f%%) از سقف نوسان %s (%.2f%%) بزرگ‌تر است", stopPct * 100.0, interval.label, bounds.maxStopPct * 100.0)
        }
        if (stopPct > 0.0 && stopPct < bounds.minStopPct * 0.85) {
            violations += String.format(Locale.US, "حد ضرر (%.2f%%) داخل نویز %s است", stopPct * 100.0, interval.label)
        }
        if (targetPct > bounds.maxTargetPct * 1.02) {
            violations += String.format(Locale.US, "حد سود (%.2f%%) از سقف قابل‌دسترس (%.2f%%) دورتر است", targetPct * 100.0, bounds.maxTargetPct * 100.0)
        }
        if (rr > 0.0 && (rr < MIN_RR - 1e-6 || rr > MAX_RR + 1e-6)) {
            violations += String.format(Locale.US, "R:R = 1:%.2f بیرون از بازه [1:1.50 .. 1:3.00] است", rr)
        }
        if (referenceAtr.isFinite() && referenceAtr > 0.0) {
            val ratio = referenceAtr / entryPrice
            if (ratio > bounds.maxStopPct / 1.6 * 1.02 || ratio < bounds.maxStopPct / 9.0 * 0.98) {
                violations += String.format(Locale.US, "ATR غیرعادی (%.2f%% قیمت)؛ دادهٔ فید مشکوک است", ratio * 100.0)
            }
        }
        val verdict = when {
            fatal -> TradePlanVerdict.REJECTED
            violations.isEmpty() -> TradePlanVerdict.VALID
            else -> TradePlanVerdict.ADJUSTED
        }
        return TradePlanAudit(
            symbol = normalizedSymbol,
            interval = interval,
            action = action,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            stopPct = round4(stopPct),
            targetPct = round4(targetPct),
            riskReward = round2(rr),
            recommendedLeverage = maxSafeLeverage(normalizedSymbol, action, entryPrice, abs(entryPrice - stopLoss)),
            verdict = verdict,
            violationsFa = violations,
        )
    }

    /**
     * داوری پلنِ همهٔ گزارش‌های استراتژی (نمادهای دارای سیگنال) برای نمایش در برنامه.
     */
    fun auditReports(reports: Collection<StrategyEvaluationReport>): List<TradePlanAudit> =
        reports.mapNotNull { report ->
            val entry = report.suggestedEntry
            val sl = report.suggestedStopLoss
            val tp = report.primaryTakeProfit
            if (entry == null || sl == null || tp == null) {
                null
            } else {
                audit(
                    symbol = report.symbol,
                    action = report.action,
                    entryPrice = entry,
                    stopLoss = sl,
                    takeProfit = tp,
                    referenceAtr = report.triggerSnapshot?.atr14 ?: 0.0,
                    interval = report.triggerInterval,
                )
            }
        }.sortedWith(compareBy({ it.verdict.ordinal }, { it.symbol }))

    /**
     * انتخاب بهترین سنگر ساختاری (نزدیک‌ترین سنگر معتبر بیرون از کف ایمن) از میان کاندیداهای ساختاری —
     * به‌جای «دورترین سنگر» که قبلاً ۱R را تا سقف ATR متورم می‌کرد.
     */
    fun chooseStructuralAnchor(
        isLong: Boolean,
        referenceEntry: Double,
        candidates: List<Double>,
        floorDist: Double,
        ceilingDist: Double,
    ): Double? {
        if (!referenceEntry.isFinite() || referenceEntry <= 0.0) return null
        val safeFloor = floorDist.coerceAtLeast(0.0)
        val safeCeiling = max(safeFloor, ceilingDist)
        val usable = candidates.filter { it.isFinite() && it > 0.0 }.mapNotNull { candidate ->
            val signedDistance = if (isLong) referenceEntry - candidate else candidate - referenceEntry
            if (signedDistance <= 0.0) null else candidate to signedDistance
        }
        if (usable.isEmpty()) return null
        val insideCorridor = usable.filter { it.second in safeFloor..safeCeiling }
        val pool = if (insideCorridor.isNotEmpty()) insideCorridor else usable.filter { it.second >= safeFloor }
        val nearest = pool.minByOrNull { it.second } ?: usable.minByOrNull { it.second } ?: return null
        val clampedDistance = nearest.second.coerceIn(safeFloor, safeCeiling)
        return if (isLong) referenceEntry - clampedDistance else referenceEntry + clampedDistance
    }

    // ─────────────────── بازبینی و اصلاح پلن پوزیشن‌های بازِ قدیمی ───────────────────

    /**
     * نتیجهٔ بازبینی پلن ذخیره‌شدهٔ یک پوزیشن باز (معامله‌ای که با اعداد نسخه‌های قدیمی ثبت شده است).
     */
    data class StoredPlanReview(
        val needsRepair: Boolean,
        val guarded: GuardedTradePlan,
        val previousStopPct: Double,
        val previousTargetPct: Double,
        val previousRiskReward: Double,
        val reasonFa: String,
    )

    /**
     * بازبینی پلن ذخیره‌شدهٔ یک پوزیشن باز با همان دالان نوسان موتور جدید.
     *
     * پوزیشن‌هایی که با نسخه‌های قبلِ این موتور باز شده‌اند (نمونهٔ واقعی: `BNB/USDT 5m`
     * با `۱R = 5.66%` و `TP = 14.13%`) پلن‌شان بیرون دالان نوسان تایم‌فریم است؛ این تابع پلن
     * ساختاری همان معامله را دوباره داخل دالان می‌آورد، جهت و R:R را حفظ می‌کند و در صورت
     * نیاز متن فارسی دلیل اصلاح را برمی‌گرداند.
     */
    fun reviewStoredPlan(
        symbol: String,
        action: SignalAction,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double,
        triggerInterval: Interval,
        referenceAtr14: Double = 0.0,
        strategyAtr: Double = 0.0,
    ): StoredPlanReview {
        val normalized = V1Universe.normalize(symbol).ifBlank { symbol }
        val prevStopPct = if (entryPrice > 0.0 && stopLoss.isFinite()) abs(entryPrice - stopLoss) / entryPrice else 0.0
        val prevTargetPct = if (entryPrice > 0.0 && takeProfit.isFinite()) abs(takeProfit - entryPrice) / entryPrice else 0.0
        val prevRr = if (prevStopPct > 1e-9) prevTargetPct / prevStopPct else MIN_RR
        val desiredRr = if (prevRr.isFinite()) prevRr.coerceIn(MIN_RR, MAX_RR) else MIN_RR
        val guarded = sanitizePlan(
            symbol = normalized,
            action = action,
            entryPrice = entryPrice,
            candidateStopLoss = stopLoss,
            candidateTakeProfit = takeProfit,
            desiredRiskReward = desiredRr,
            referenceAtr = referenceAtr14,
            triggerInterval = triggerInterval,
            strategyAtr = strategyAtr,
        )
        if (!guarded.isUsable) {
            return StoredPlanReview(
                needsRepair = false,
                guarded = guarded,
                previousStopPct = prevStopPct,
                previousTargetPct = prevTargetPct,
                previousRiskReward = prevRr,
                reasonFa = "پلن ذخیره‌شده قابل بازسازی نیست؛ داوری دستی لازم است",
            )
        }
        val bounds = boundsFor(normalized, triggerInterval)
        // آستانهٔ معنادار: تغییر کمتر از ۱۵٪ کف دالان یا ۰.۰۵٪ قیمت، اصلاح محسوب نمی‌شود.
        val tolerancePct = max(bounds.minStopPct * 0.15, 0.0005)
        val stopDeltaPct = abs(guarded.stopLoss - stopLoss) / entryPrice.coerceAtLeast(1e-9)
        val targetDeltaPct = abs(guarded.takeProfit - takeProfit) / entryPrice.coerceAtLeast(1e-9)
        val needsRepair = stopDeltaPct > tolerancePct || targetDeltaPct > tolerancePct
        val reasonFa = if (!needsRepair) {
            String.format(
                Locale.US,
                "پلن ذخیره‌شده داخل دالان %s است (۱R %.2f%% · هدف %.2f%% · R:R 1:%.2f)",
                triggerInterval.label,
                prevStopPct * 100.0,
                prevTargetPct * 100.0,
                prevRr,
            )
        } else {
            String.format(
                Locale.US,
                "اصلاح پلن قدیمی به دالان %s: ۱R از %.2f%% به %.2f%% و هدف از %.2f%% به %.2f%% " +
                    "(R:R ثابت 1:%.2f)",
                triggerInterval.label,
                prevStopPct * 100.0,
                guarded.stopPct * 100.0,
                prevTargetPct * 100.0,
                guarded.targetPct * 100.0,
                guarded.riskReward,
            )
        }
        return StoredPlanReview(
            needsRepair = needsRepair,
            guarded = guarded,
            previousStopPct = prevStopPct,
            previousTargetPct = prevTargetPct,
            previousRiskReward = prevRr,
            reasonFa = reasonFa,
        )
    }

    /** گرد کردن قیمت روی تیک واقعی همان نماد. */
    fun roundToTick(symbol: String, price: Double): Double {
        if (!price.isFinite()) return price
        val tick = VenueSpecs.of(symbol).tickSize.coerceAtLeast(1e-8)
        val snapped = round(price / tick) * tick
        val decimals = when {
            tick >= 0.01 -> 2
            tick >= 0.001 -> 3
            tick >= 0.0001 -> 4
            else -> 5
        }
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return round(snapped * factor) / factor
    }

    private fun clampRr(value: Double): Double =
        if (value.isFinite()) value.coerceIn(MIN_RR, MAX_RR) else MIN_RR

    private fun round2(value: Double): Double = if (value.isFinite()) round(value * 100.0) / 100.0 else 0.0

    private fun round4(value: Double): Double = if (value.isFinite()) round(value * 10_000.0) / 10_000.0 else 0.0
}
