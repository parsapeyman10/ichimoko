package com.aurum.edge.core.peyman

import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * # مدل‌های «پیمان کریپتو حرفه‌ای»
 *
 * این فایل فقط قرارداد داده است: هیچ محاسبه‌ای این‌جا انجام نمی‌شود تا موتورهای تصمیم
 * (Pure Kotlin، بدون Android) بتوانند مستقل تست شوند و خروجی هر تصمیم قابل بازپخش باشد.
 */

/** حالت اجرا. پیش‌فرض `DISABLED` است تا ارتقای اپ هیچ رفتار قبلی را عوض نکند. */
enum class PeymanExecutionMode(val labelFa: String) {
    DISABLED("خاموش"),
    SHADOW("سایه (فقط ثبت تصمیم، بدون معامله)"),
    PAPER_ENABLED("معاملهٔ کاغذی فعال"),
}

/** رژیم بازار — از چند نشانه و با امکان «نامشخص» به‌جای حدس. */
enum class CryptoMarketRegime(val labelFa: String) {
    COMPRESSION("فشردگی"),
    TREND_EXPANSION("انبساط روند"),
    CORRECTION("اصلاح"),
    DISTRIBUTION("توزیع"),
    PANIC_CAPITULATION("هراس/تسلیم"),
    ILLIQUID_SHOCK("شوک بی‌نقدشوندگی"),
    UNKNOWN("نامشخص"),
}

/** ستاپ‌های مجاز. آستانهٔ کیفیت هر ستاپ از همین‌جا خوانده می‌شود. */
enum class PeymanSetupType(val labelFa: String, val minQualityScore: Double) {
    CORRECTION_COMPLETE("پایان اصلاح", 80.0),
    WAVE3_CONTINUATION("ادامهٔ موج سوم", 75.0),
    COMPRESSION_EXPANSION("فشردگی → انبساط", 78.0),
    CAPITULATION_REVERSAL("برگشت پس از تسلیم", 85.0),
    NONE("بدون ستاپ", 101.0),
}

enum class PeymanEntryMode(val labelFa: String) {
    NONE("بدون ورود"),
    DIRECT_BOS("ورود مستقیم روی BOS"),
    WAIT_RETEST("انتظار بازآزمایی"),
    RETEST_CONFIRMED("ورود روی بازآزمایی تأییدشده"),
}

enum class PeymanSetupStatus(val labelFa: String) {
    NONE("بدون ستاپ"),
    FORMING("در حال شکل‌گیری"),
    READY("آماده"),
    BOS_CONFIRMED("BOS تأییدشده"),
    WAITING_RETEST("انتظار بازآزمایی"),
    INVALIDATED("باطل‌شده"),
    EXPIRED("منقضی‌شده"),
    BLOCKED("مسدود"),
}

/** هم‌جهتی شمارش اصلی و جایگزین. */
enum class NeoWaveEnsembleAgreement(val labelFa: String) {
    AGREE("هم‌جهت"),
    CONFLICT("مخالف"),
    SINGLE("تنها یک شمارش معتبر"),
    NONE("بدون شمارش معتبر"),
}

/** دادهٔ زمینهٔ BTC برای آلت‌ها. اگر نباشد، آلت‌ها سخت‌گیرانه‌تر می‌شوند. */
data class BtcMarketContext(
    val regime: CryptoMarketRegime,
    val trendDirection: SignalAction,
    val atrPercentile: Double? = null,
    val relativeStrength: Double? = null,
    val dataTimestamp: Long = 0L,
) {
    val isFresh: Boolean get() = dataTimestamp > 0L
}

/** سلامت داده — پیش‌نیاز هر تصمیم. */
data class PeymanDataHealth(
    val isHealthy: Boolean,
    val reasonsFa: List<String>,
    val triggerBars: Int = 0,
    val strategyBars: Int = 0,
    val macroBars: Int = 0,
    val atr: Double = 0.0,
    val volumeAvailable: Boolean = false,
    val lastBarAgeMinutes: Double = 0.0,
) {
    companion object {
        fun healthy(trigger: Int, strategy: Int, macro: Int, atr: Double, volume: Boolean, ageMin: Double) =
            PeymanDataHealth(true, emptyList(), trigger, strategy, macro, atr, volume, ageMin)

        fun broken(reason: String, trigger: Int = 0, strategy: Int = 0, macro: Int = 0) =
            PeymanDataHealth(false, listOf(reason), trigger, strategy, macro)
    }
}

/**
 * تنظیمات استراتژی پیمان کریپتو.
 *
 * `@Serializable` اجباری است: `BaseStrategyConfig` سریال می‌شود و هر فیلد تازه باید
 * سریالایزر داشته باشد (همان خطایی که در CI برای ماشهٔ فشردگی گرفتیم).
 */
@Serializable
data class PeymanCryptoConfig(
    /** پیش‌فرض خاموش: هیچ رفتار قبلی عوض نمی‌شود. */
    val mode: PeymanExecutionMode = PeymanExecutionMode.DISABLED,
    /** نسخهٔ قواعد؛ با هر تغییرِ معنادار باید عوض شود تا Trace و anti-duplicate معتبر بمانند. */
    val rulesVersion: String = "peyman-crypto-pro-1",
    /** ریسک هر ستاپ عادی بر حسب درصد سرمایه. */
    val riskPercentPerTrade: Double = 0.35,
    /** ریسک ستاپ Reversal حداکثر نصف ستاپ عادی است. */
    val reversalRiskFraction: Double = 0.5,
    /** سقف ضرر روزانه بر حسب درصد سرمایه؛ عبور از آن ورود جدید را می‌بندد. */
    val dailyLossLimitPct: Double = 1.5,
    /** سقف مجموع ریسک باز یک خوشهٔ هم‌جهت (درصد سرمایه). */
    val maxClusterRiskPct: Double = 0.75,
    /** سقف مجموع ریسک باز همهٔ خوشه‌ها. */
    val maxTotalRiskPct: Double = 1.20,
    /** حد ضرر دنباله‌دار بعد از Fake Breakout (تعداد کندل). */
    val cooldownBarsAfterFakeBreakout: Int = 3,
    /** تعداد ضرر متوالی که کول‌داون فعال می‌کند. */
    val consecutiveLossCooldown: Int = 2,
    /** حداقل کندل لازم در هر تایم‌فریم برای تحلیل. */
    val minBars: Int = 100,
    /** حداکثر سن مجاز آخرین کندل ماشه (ضریبی از طول تایم‌فریم). */
    val maxStalenessBars: Double = 3.0,
    /** حداکثر اختلاف مجاز مبنای قیمت بین تایم‌فریم‌ها (نسبت). */
    val maxPriceBasisGap: Double = 0.05,
    /** دامنهٔ زیگزاگ نئوویو بر حسب ATR برای هر رژیم نوسان. */
    val zigzagAmpLowVol: Double = 0.45,
    val zigzagAmpNormal: Double = 0.65,
    val zigzagAmpHighVol: Double = 0.95,
    /** تعداد کندل ساخت سطح ساختاری BOS. */
    val structureLookbackBars: Int = 20,
    /** بافر شکست بر حسب ATR. */
    val breakoutBufferAtr: Double = 0.10,
    /** حجم نسبی لازم برای BOS ادامهٔ روند و برای برگشت. */
    val minRvolContinuation: Double = 1.40,
    val minRvolReversal: Double = 1.60,
    /** بدنهٔ حداقل کندل شکست. */
    val minBodyRatio: Double = 0.50,
    val minCompressionBreakoutBodyRatio: Double = 0.55,
    /** CLV خرید حداقل و فروش حداکثر. */
    val minClvLong: Double = 0.70,
    val maxClvShort: Double = 0.30,
    /** شدوی مخالف مجاز. */
    val maxAdverseWickRatio: Double = 0.35,
    /** انبساط ATR لازم نسبت به کندل قبل شکست. */
    val minAtrExpansionRatio: Double = 1.15,
    /** معیار مکمل: دامنهٔ کندل شکست نسبت به میانهٔ دامنهٔ ناحیه. */
    val minRangeExpansionRatio: Double = 1.50,
    /** فاصلهٔ مجاز از سطح شکست برای ورود مستقیم (ATR). */
    val maxDirectDistanceAtr: Double = 0.80,
    /** کندل بزرگ‌تر از این مقدار (ATR) ورود مستقیم نمی‌دهد. */
    val maxBreakoutRangeAtr: Double = 2.50,
    /** حداکثر انتظار بازآزمایی (کندل بسته). */
    val maxRetestWaitBars: Int = 5,
    val minRetestBodyRatio: Double = 0.45,
    val minRetestVolumeRatio: Double = 0.80,
    /** حداقل R:R مؤثر پس از هزینه. */
    val minRiskReward: Double = 1.5,
    val minRiskRewardReversal: Double = 1.8,
    /** حداقل فضای آزاد تا نزدیک‌ترین مانع ساختاری بر حسب R. */
    val minObstacleSpaceR: Double = 1.5,
    /** فاصلهٔ مجاز از کیجون برای ورود مستقیم (ATR) — سقف تعقیب. */
    val maxKijunDistanceAtr: Double = 3.0,
    /** هزینهٔ رفت‌وبرگشت (درصد قیمت) و اسپرد پایه. */
    val roundTurnFeePct: Double = 0.08,
    val spreadPct: Double = 0.02,
    /** سهم ATR که به‌عنوان Slippage محافظه‌کارانه در R:R کسر می‌شود. */
    val slippageAtrFraction: Double = 0.05,
    /** ستاپ Reversal پیش‌فرض خاموش است. */
    val reversalEnabled: Boolean = false,
    /** حداقل تعداد نمونه برای اینکه یادگیری خودکار مجاز باشد. */
    val learningMinSamples: Int = 100,
    /**
     * تحملِ «لمس سطح» در بازآزمایی بر حسب ATR.
     *
     * چرا قابل تنظیم شد؟ با ۰٫۱۰ ATR عملاً فقط کندل‌هایی که دقیقاً سطح را می‌بوسند
     * شمرده می‌شدند؛ روی دادهٔ واقعی BTC 15m هیچ کندلی به وضعیت «آماده» نرسید
     * (۳۱٬۶۲۹ ارزیابی → ۲ ورود). عدد پیش‌فرض همان رفتار قبلی است.
     */
    val retestTouchAtrFraction: Double = 0.10,
    /**
     * شرط «حجم بازآزمایی کمتر از کندل شکست باشد» را لازم بدان یا نه.
     * پیش‌فرض `true` = رفتار قبلی. در پکیج معاملاتی خاموش می‌شود، چون این شرط
     * داده‌محور نیست و در عمل بازآزمایی‌های سالم را هم می‌بندد.
     */
    val requireRetestVolumeBelowBreakout: Boolean = true,
    /**
     * آستانهٔ حجم نسبیِ ستاپ «فشردگی → انبساط». قبلاً عدد ۱٫۵ در کد هاردکد بود.
     * پیش‌فرض همان ۱٫۵ = رفتار قبلی.
     */
    val compressionMinRvol: Double = 1.50,
    /**
     * در رژیم «نامشخص» فقط ستاپ فشردگی → انبساط ارزیابی شود (به‌جای بلوکه‌کردن کامل).
     * پیش‌فرض `false` = رفتار قبلی (نامشخص ⇒ مسدود).
     */
    val allowUnknownRegimeCompression: Boolean = false,
    /** سقف صدک ATR برای پذیرش «فشردگی» در رژیم نامشخص. */
    val unknownCompressionMaxAtrPercentile: Double = 65.0,
    /** سقف نسبت دامنه برای پذیرش «فشردگی» در رژیم نامشخص. */
    val unknownCompressionMaxRangeRatio: Double = 0.85,
    /** سقف کارایی مسیر (ER14) برای پذیرش «فشردگی» در رژیم نامشخص. */
    val unknownCompressionMaxEr14: Double = 0.45,
    /**
     * مسیر پویا: اگر مسیر سخت‌گیرِ BOS رد شد، «شکستِ محدودهٔ فشرده با کلوز»
     * با آستانه‌های تطبیقی پذیرفته شود. پیش‌فرض `false` = رفتار قبلی.
     */
    val dynamicBreakoutEnabled: Boolean = false,
    /** حداقل حجم نسبی در مسیر پویا (۱٫۰ = بدون وتوی حجم). */
    val dynamicMinRvol: Double = 0.80,
    /** حداقل بدنهٔ کندل شکست در مسیر پویا. */
    val dynamicMinBodyRatio: Double = 0.15,
    /** حداکثر پهنای محدودهٔ قبل از شکست برحسب ATR (پیش‌شرط «رنج بودن»). */
    val rangeBreakoutMaxRangeAtr: Double = 6.0,
    /**
     * ابطال ساختاری: شکست فقط وقتی «ناموفق» است که قیمت به این کسر از محدودهٔ
     * قبلِ شکست برگردد (۰٫۵ = وسط رنج). پیش‌فرض `false` = قاعدهٔ قدیم.
     */
    val useStructuralRetestInvalidation: Boolean = false,
    /** عمقِ برگشت به داخل رنج که «شکست ناموفق» حساب می‌شود (۰٫۵ = میانهٔ رنج). */
    val retestInvalidationRangeFraction: Double = 0.50,
    /** رواداری کلوزِ کندل بازآزمایی نسبت به سطح، برحسب ATR (۰ = بدون رواداری). */
    val retestCloseToleranceAtr: Double = 0.0,
) {
    val isActive: Boolean get() = mode != PeymanExecutionMode.DISABLED
    val canTrade: Boolean get() = mode == PeymanExecutionMode.PAPER_ENABLED

    companion object {
        val SHADOW = PeymanCryptoConfig(mode = PeymanExecutionMode.SHADOW)
        val PAPER = PeymanCryptoConfig(mode = PeymanExecutionMode.PAPER_ENABLED)

        /**
         * پکیج «قابل معامله» برای ۱۵ و ۵ دقیقه — با اندازه‌گیری، نه سلیقه.
         *
         * چه چیزی و چرا (همه با دادهٔ واقعی BTC/USDT اندازه‌گیری شد):
         *  - `minRvolContinuation` ۱٫۴۰ → ۱٫۲۰ : حجم نسبی تنها در ۱۶٪ کندل‌ها از ۱٫۴۰
         *    بیشتر بود، در حالی که ۳٫۶٪ کندل‌ها همهٔ شرط‌های تأیید شکست را با هم داشتند؛
         *    یعنی RVOL گلوگاه واقعی بود.
         *  - تحمل لمس سطح ۰٫۱۰ → ۰٫۲۵ ATR و بدنهٔ بازآزمایی ۰٫۴۵ → ۰٫۳۰ : بازآزمایی
         *    سالم لازم نیست سطح را دقیق ببوسد.
         *  - حجم بازآزمایی ۰٫۸۰ → ۰٫۶۰ و حذف شرط «کمتر از حجم شکست»: شرط قبلی
         *    بازآزمایی‌های سالم پرحجم را بی‌دلیل می‌بست.
         *  - پنجرهٔ انتظار بازآزمایی برای هر تایم‌فریم جدا: ۱۵m → ۱۰ کندل (۱۵۰ دقیقه)،
         *    ۵m → ۱۵ کندل (۷۵ دقیقه). قبلاً ۵ کندل ثابت بود: ۷۵ دقیقه در ۱۵m و فقط
         *    ۲۵ دقیقه در ۵m.
         * وتوهای سختِ امتیاز (Wave3 ۷۵ / Correction ۸۰ / Reversal ۸۵ / Compression ۷۸)
         * و ریسک خوشه و سقف ضرر روزانه **دست‌نخورده** می‌مانند؛ این پکیج فقط
         * «قابل‌شدنِ» ستاپ را عوض می‌کند، نه سخت‌گیریِ خروجی را.
         */
        fun tradingFor(triggerMinutes: Int): PeymanCryptoConfig {
            val waitBars = if (triggerMinutes <= 5) 15 else 10
            return PeymanCryptoConfig(
                mode = PeymanExecutionMode.PAPER_ENABLED,
                rulesVersion = "peyman-crypto-pro-2",
                maxRetestWaitBars = waitBars,
                retestTouchAtrFraction = 0.25,
                minRetestBodyRatio = 0.30,
                minRetestVolumeRatio = 0.60,
                requireRetestVolumeBelowBreakout = false,
                minRvolContinuation = 1.20,
                minRvolReversal = 1.40,
                compressionMinRvol = 1.25,
                allowUnknownRegimeCompression = true,
                dynamicBreakoutEnabled = true,
                rangeBreakoutMaxRangeAtr = 6.0,
                dynamicMinRvol = 0.80,
                dynamicMinBodyRatio = 0.15,
                useStructuralRetestInvalidation = true,
                retestInvalidationRangeFraction = 0.50,
                retestCloseToleranceAtr = 0.25,
            )
        }
    }
}

/** ورودی کامل تصمیم. هیچ داده‌ای خارج از این ساختار خوانده نمی‌شود. */
data class PeymanCryptoInput(
    val symbol: String,
    val macroBars: List<Candle>,
    val strategyBars: List<Candle>,
    val triggerBars: List<Candle>,
    val btcContext: BtcMarketContext? = null,
    val openPositions: List<PaperTrade> = emptyList(),
    val config: PeymanCryptoConfig = PeymanCryptoConfig(),
    val nowMillis: Long = 0L,
    val triggerInterval: Interval = Interval.M15,
    /** ضرر روز جاری بر حسب درصد سرمایه (برای سقف ضرر روزانه). */
    val todayLossPct: Double = 0.0,
    /** تعداد ضرر متوالی و Fake Breakoutهای اخیر (برای کول‌داون). */
    val consecutiveLosses: Int = 0,
    val fakeBreakoutsSinceLastWin: Int = 0,
    /** شناسه‌های ستاپی که قبلاً معامله شده‌اند (ضدتکرار، بین بک‌تست و زنده مشترک). */
    val alreadyEnteredSetupIds: Set<String> = emptySet(),
    /** بی‌طرفی تست: اگر true، مسیر Legacy برای مقایسهٔ A/B مجاز است. */
    val allowLegacyFallback: Boolean = false,
)

/** دلیل رد/تأیید، جدا از متن کلی. */
data class PeymanGateResult(
    val passed: Boolean,
    val code: String,
    val reasonFa: String,
) {
    companion object {
        fun ok(code: String = "OK", reasonFa: String = "") = PeymanGateResult(true, code, reasonFa)
        fun fail(code: String, reasonFa: String) = PeymanGateResult(false, code, reasonFa)
    }
}

/** Trace تصمیم؛ بدون این، هیچ تصمیمی قابل بازپخش و بازبینی نیست. */
data class PeymanDecisionTrace(
    val rulesVersion: String,
    val evaluatedAt: Long,
    val mode: PeymanExecutionMode,
    val regime: CryptoMarketRegime,
    val regimeFa: String,
    val dataHealthFa: String,
    val primaryCountFa: String,
    val alternativeCountFa: String,
    val ensembleAgreement: NeoWaveEnsembleAgreement,
    val invalidationPrice: Double?,
    val setupType: PeymanSetupType,
    val setupStatus: PeymanSetupStatus,
    val bosStatusFa: String,
    val relativeVolume: Double?,
    val atrPercentile: Double?,
    val btcContextFa: String,
    val distanceFromBreakoutAtr: Double?,
    val qualityScore: Double,
    val effectiveRiskReward: Double?,
    val hardGateFailures: List<String>,
    val finalDecisionFa: String,
    val setupId: String?,
)

/** خروجی نهایی. */
data class PeymanCryptoDecision(
    val action: SignalAction,
    val readinessFa: String,
    val setupType: PeymanSetupType,
    val setupStatus: PeymanSetupStatus,
    val entryMode: PeymanEntryMode,
    val qualityScore: Double,
    val entry: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val riskRewardAfterCosts: Double?,
    val invalidationPrice: Double?,
    val setupId: String?,
    val mode: PeymanExecutionMode,
    /** تنها وقتی true است که همهٔ گیت‌های سخت پاس شوند **و** حالت اجرا معامله را مجاز کند. */
    val isTradeAllowed: Boolean,
    val trace: PeymanDecisionTrace,
) {
    val summaryFa: String
        get() = buildString {
            append(trace.regimeFa)
            append(" · ستاپ: ").append(setupType.labelFa)
            append(" · وضعیت: ").append(setupStatus.labelFa)
            if (qualityScore > 0.0) append(" · امتیاز: ").append(num(qualityScore)).append("/۱۰۰")
            append(" · تصمیم: ").append(trace.finalDecisionFa)
        }

    companion object {
        private fun num(v: Double): String = String.format(Locale.US, "%.0f", v)

        /** تصمیم «خاموش» — هیچ ارزیابی‌ای انجام نشده است. */
        fun disabled(reasonFa: String = "استراتژی پیمان کریپتو خاموش است."): PeymanCryptoDecision =
            PeymanCryptoDecision(
                action = SignalAction.NO_TRADE,
                readinessFa = reasonFa,
                setupType = PeymanSetupType.NONE,
                setupStatus = PeymanSetupStatus.NONE,
                entryMode = PeymanEntryMode.NONE,
                qualityScore = 0.0,
                entry = null,
                stopLoss = null,
                takeProfit = null,
                riskRewardAfterCosts = null,
                invalidationPrice = null,
                setupId = null,
                mode = PeymanExecutionMode.DISABLED,
                isTradeAllowed = false,
                trace = emptyTrace(reasonFa, PeymanExecutionMode.DISABLED),
            )

        fun emptyTrace(reasonFa: String, mode: PeymanExecutionMode, evaluatedAt: Long = 0L): PeymanDecisionTrace =
            PeymanDecisionTrace(
                rulesVersion = PeymanCryptoConfig().rulesVersion,
                evaluatedAt = evaluatedAt,
                mode = mode,
                regime = CryptoMarketRegime.UNKNOWN,
                regimeFa = CryptoMarketRegime.UNKNOWN.labelFa,
                dataHealthFa = "",
                primaryCountFa = "",
                alternativeCountFa = "",
                ensembleAgreement = NeoWaveEnsembleAgreement.NONE,
                invalidationPrice = null,
                setupType = PeymanSetupType.NONE,
                setupStatus = PeymanSetupStatus.NONE,
                bosStatusFa = "",
                relativeVolume = null,
                atrPercentile = null,
                btcContextFa = "",
                distanceFromBreakoutAtr = null,
                qualityScore = 0.0,
                effectiveRiskReward = null,
                hardGateFailures = listOf(reasonFa),
                finalDecisionFa = reasonFa,
                setupId = null,
            )
    }
}
