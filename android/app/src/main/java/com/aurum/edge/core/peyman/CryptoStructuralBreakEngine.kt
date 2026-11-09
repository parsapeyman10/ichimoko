package com.aurum.edge.core.peyman

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # ساخت سطح، BOS، بازآزمایی و منع تعقیب
 *
 * سه قاعدهٔ غیرقابل‌مذاکره:
 * 1. سطح ساختاری **فقط** از کندل‌های بستهٔ قبل ساخته می‌شود؛ کندل جاری سطح خودش را نمی‌سازد.
 * 2. شکست فقط با **کلوز** پذیرفته می‌شود؛ شدو به‌تنهایی هرگز.
 * 3. اگر قیمت از سطح دور شد، ورود مستقیم ممنوع است و فقط بازآزمایی مجاز است.
 */
data class StructuralBreakResult(
    val status: PeymanSetupStatus,
    val entryMode: PeymanEntryMode,
    val direction: SignalAction,
    /** سطح ساختاری که شکسته شده (سقف/کف ناحیه). */
    val structuralLevel: Double?,
    val breakoutLevel: Double?,
    val breakoutBarTime: Long?,
    val breakoutBarIndex: Int?,
    val breakoutAtr: Double?,
    val distanceFromLevelAtr: Double?,
    val relativeVolume: Double?,
    val atrExpansionRatio: Double,
    val bodyRatio: Double,
    val clv: Double,
    val barsSinceBreakout: Int,
    val setupId: String?,
    val bosNoteFa: String,
    val reasonsFa: List<String>,
) {
    val isBosConfirmed: Boolean
        get() = status == PeymanSetupStatus.BOS_CONFIRMED || status == PeymanSetupStatus.WAITING_RETEST ||
            status == PeymanSetupStatus.READY

    val isEntryCandidate: Boolean
        get() = entryMode == PeymanEntryMode.DIRECT_BOS || entryMode == PeymanEntryMode.RETEST_CONFIRMED

    companion object {
        fun none(direction: SignalAction, reasonFa: String, status: PeymanSetupStatus = PeymanSetupStatus.NONE) =
            StructuralBreakResult(
                status = status,
                entryMode = PeymanEntryMode.NONE,
                direction = direction,
                structuralLevel = null,
                breakoutLevel = null,
                breakoutBarTime = null,
                breakoutBarIndex = null,
                breakoutAtr = null,
                distanceFromLevelAtr = null,
                relativeVolume = null,
                atrExpansionRatio = 0.0,
                bodyRatio = 0.0,
                clv = 0.0,
                barsSinceBreakout = 0,
                setupId = null,
                bosNoteFa = reasonFa,
                reasonsFa = listOf(reasonFa),
            )
    }
}

object CryptoStructuralBreakEngine {

    /**
     * @param bars کندل‌های بستهٔ تایم ماشه، آخرین عضو = کندل جاری.
     * @param minRvol آستانهٔ حجم نسبی؛ برای برگشت سخت‌گیرانه‌تر از ادامهٔ روند است.
     * @param minBodyRatio آستانهٔ بدنه؛ برای شکست فشردگی ۰٫۵۵ و بقیه ۰٫۵۰.
     * @param noiseFloorPrice کف نویز/هزینه (هم‌مبنا با TradePlanGuard) تا بافر دوبار تبدیل نشود.
     * @param referenceAtr مبنای ATR برای سقف «تعقیب نکردن» (فاصله و اندازهٔ کندل).
     *
     * چرا مبنای جداگانه؟ اندازه‌گیری روی دادهٔ ساختگی نشان داد قاعده‌های «فاصله ≤ ۰٫۸ ATR» و
     * «انبساط ATR14 ≥ ۱٫۱۵×» روی یک تایم‌فریم **هم‌زمان قابل ارضا نیستند**: برای اینکه ATR14
     * به ۱٫۱۵ برابر برسد، دامنهٔ کندل شکست باید ≥ ۳×ATR قبلی باشد و همان کندل فاصله را به
     * چند ATR می‌برد. پس انبساط روی تایم ماشه سنجیده می‌شود (همان‌جا که فشردگی رخ داده) و
     * «تعقیب نکردن» با ATR تایم استراتژی (نوسان معاملاتی واقعی) اندازه می‌شود.
     */
    fun detect(
        bars: List<Candle>,
        symbol: String,
        rulesVersion: String,
        intervalLabel: String,
        direction: SignalAction,
        config: PeymanCryptoConfig,
        minRvol: Double = config.minRvolContinuation,
        minBodyRatio: Double = config.minBodyRatio,
        noiseFloorPrice: Double = 0.0,
        referenceAtr: Double = 0.0,
    ): StructuralBreakResult {
        val closed = PeymanMath.closed(bars)
        val isLong = direction == SignalAction.BUY
        val lookback = config.structureLookbackBars
        if (closed.size < lookback + 3) {
            return StructuralBreakResult.none(direction, "کندل کافی برای ساخت سطح ساختاری نیست.")
        }
        val i = closed.size - 1
        val atr = PeymanMath.atr(closed, i)
        if (!PeymanMath.isFinitePositive(atr)) {
            return StructuralBreakResult.none(direction, "ATR نامعتبر است؛ BOS قابل سنجش نیست.")
        }

        // ۱) سطح ساختاری از کندل‌های [i-lookback .. i-1] — کندل جاری هیچ نقشی ندارد.
        fun levelAt(index: Int): Double {
            val from = max(0, index - lookback)
            val to = index - 1
            if (to < from) return Double.NaN
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (k in from..to) {
                if (closed[k].high > hi) hi = closed[k].high
                if (closed[k].low < lo) lo = closed[k].low
            }
            return if (isLong) hi else lo
        }

        // ۲) جست‌وجوی شکست: کندل جاری، و اگر نبود، کندل‌های قبلی برای مسیر بازآزمایی.
        // +۲ تا ستاپِ منقضی هم پیدا و «EXPIRED» گزارش شود، نه اینکه بی‌صدا ناپدید شود.
        val maxScan = config.maxRetestWaitBars + 2
        var breakoutIdx = -1
        var breakoutLevel = Double.NaN
        var breakoutAtr = Double.NaN
        var failureReason: String? = null
        var failureStatus = PeymanSetupStatus.NONE

        var back = 0
        while (back < maxScan && breakoutIdx < 0) {
            val j = i - back
            if (j < lookback + 1) break
            val lvl = levelAt(j)
            if (lvl.isNaN()) break
            val bar = closed[j]
            val barAtr = PeymanMath.atr(closed, j)
            val buffer = max(config.breakoutBufferAtr * barAtr, noiseFloorPrice)
            val beyond = if (isLong) bar.close > lvl + buffer else bar.close < lvl - buffer

            if (!beyond) {
                if (back == 0) {
                    // گزارش شفاف: آیا فقط شدو بیرون زده است؟
                    val wickOnly = if (isLong) bar.high > lvl + buffer && bar.close <= lvl + buffer
                    else bar.low < lvl - buffer && bar.close >= lvl - buffer
                    failureReason = if (wickOnly) {
                        "شکست فقط با شدو بود؛ کلوز آن‌طرف سطح نرسید."
                    } else {
                        "کلوز هنوز سطح ساختاری را نشکسته است."
                    }
                    failureStatus = PeymanSetupStatus.FORMING
                }
                back++
                continue
            }

            // ۳) فیلترهای کندل شکست.
            val body = PeymanMath.bodyRatio(bar)
            val clv = PeymanMath.clv(bar)
            val rvol = PeymanMath.relativeVolume(closed, j)
            val dirOk = if (isLong) PeymanMath.bullish(bar) else PeymanMath.bearish(bar)
            val clvOk = if (isLong) clv >= config.minClvLong else clv <= config.maxClvShort
            val wickOk = if (isLong) {
                PeymanMath.upperWickRatio(bar) <= config.maxAdverseWickRatio
            } else {
                PeymanMath.lowerWickRatio(bar) <= config.maxAdverseWickRatio
            }
            val bodyOk = body >= minBodyRatio && dirOk
            val rvolOk = rvol != null && rvol >= minRvol
            val expansionRatio = if (PeymanMath.isFinitePositive(barAtr)) {
                val prevAtr = PeymanMath.atr(closed, max(1, j - 1))
                if (PeymanMath.isFinitePositive(prevAtr)) barAtr / prevAtr else 0.0
            } else {
                0.0
            }
            val rangeExpansion = rangeExpansionRatio(closed, j, lookback)
            val expansionOk = expansionRatio >= config.minAtrExpansionRatio ||
                rangeExpansion >= config.minRangeExpansionRatio

            val failures = ArrayList<String>()
            if (!bodyOk) failures.add(if (!dirOk) "کندل شکست هم‌جهت نیست." else "بدنهٔ کندل شکست ضعیف است (${pct(body)}).")
            if (!clvOk) failures.add("CLV کندل شکست کافی نیست (${pct(clv)}).")
            if (!rvolOk) failures.add("حجم نسبی کافی نیست (حداقل ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, minRvol))}×).")
            if (!wickOk) failures.add("شدوی مخالف کندل شکست بیش از حد است.")
            if (!expansionOk) failures.add("انبساط نوسان/ATR رخ نداده است.")

            if (failures.isNotEmpty()) {
                if (back == 0) {
                    failureReason = failures.joinToString(" ")
                    failureStatus = PeymanSetupStatus.FORMING
                }
                back++
                continue
            }

            breakoutIdx = j
            breakoutLevel = lvl
            breakoutAtr = barAtr
        }

        if (breakoutIdx < 0) {
            return StructuralBreakResult.none(direction, failureReason ?: "شکست معتبری رخ نداده است.", failureStatus)
        }

        val breakoutBar = closed[breakoutIdx]
        val buffer = max(config.breakoutBufferAtr * breakoutAtr, noiseFloorPrice)
        val execLevel = if (isLong) breakoutLevel + buffer else breakoutLevel - buffer
        // مبنای «تعقیب نکردن»: ATR تایم استراتژی اگر داده شده باشد، وگرنه ATR ماشه.
        val chaseAtr = if (PeymanMath.isFinitePositive(referenceAtr)) referenceAtr else breakoutAtr
        val distanceAtr = abs(breakoutBar.close - breakoutLevel) / chaseAtr
        val barRangeAtr = PeymanMath.range(breakoutBar) / chaseAtr
        val rvolAtBreakout = PeymanMath.relativeVolume(closed, breakoutIdx)
        val setupId = buildSetupId(
            symbol = symbol,
            rulesVersion = rulesVersion,
            interval = intervalLabel,
            direction = direction,
            breakoutBarTime = breakoutBar.time,
            level = breakoutLevel,
        )

        val base = StructuralBreakResult(
            status = PeymanSetupStatus.BOS_CONFIRMED,
            entryMode = PeymanEntryMode.DIRECT_BOS,
            direction = direction,
            structuralLevel = breakoutLevel,
            breakoutLevel = execLevel,
            breakoutBarTime = breakoutBar.time,
            breakoutBarIndex = breakoutIdx,
            breakoutAtr = breakoutAtr,
            distanceFromLevelAtr = distanceAtr,
            relativeVolume = rvolAtBreakout,
            atrExpansionRatio = ratio(closed, breakoutIdx),
            bodyRatio = PeymanMath.bodyRatio(breakoutBar),
            clv = PeymanMath.clv(breakoutBar),
            barsSinceBreakout = i - breakoutIdx,
            setupId = setupId,
            bosNoteFa = "BOS با کلوز تأیید شد.",
            reasonsFa = emptyList(),
        )

        // ۴) اگر کندل شکست خودِ کندل جاری است: ورود مستقیم یا انتظار بازآزمایی.
        if (breakoutIdx == i) {
            val tooFar = distanceAtr > config.maxDirectDistanceAtr
            val tooBig = barRangeAtr > config.maxBreakoutRangeAtr
            return if (!tooFar && !tooBig) {
                base.copy(
                    status = PeymanSetupStatus.BOS_CONFIRMED,
                    entryMode = PeymanEntryMode.DIRECT_BOS,
                    bosNoteFa = "BOS با کلوز تأیید شد؛ فاصله ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, distanceAtr))} ATR ⇒ ورود مستقیم مجاز.",
                )
            } else {
                base.copy(
                    status = PeymanSetupStatus.WAITING_RETEST,
                    entryMode = PeymanEntryMode.WAIT_RETEST,
                    bosNoteFa = "کندل شکست بیش‌ازحد بزرگ/دور است؛ تعقیب ممنوع ⇒ انتظار بازآزمایی.",
                    reasonsFa = listOf("حرکت بدون ورود به پایان رسید؛ تعقیب ممنوع."),
                )
            }
        }

        // ۵) شکست قبلی: بازآزمایی یا ابطال یا انقضا.
        val wait = i - breakoutIdx
        if (wait > config.maxRetestWaitBars) {
            return base.copy(
                status = PeymanSetupStatus.EXPIRED,
                entryMode = PeymanEntryMode.NONE,
                bosNoteFa = "بیش از ${PeymanMath.faDigits(config.maxRetestWaitBars.toString())} کندل از شکست گذشت؛ ستاپ منقضی شد.",
                reasonsFa = listOf("حرکت بدون ورود به پایان رسید؛ تعقیب ممنوع."),
            )
        }

        // بسته‌شدن عمیق داخل ساختار شکسته ⇒ ابطال.
        for (k in breakoutIdx + 1..i) {
            val deep = if (isLong) closed[k].close < execLevel - buffer else closed[k].close > execLevel + buffer
            if (deep) {
                return base.copy(
                    status = PeymanSetupStatus.INVALIDATED,
                    entryMode = PeymanEntryMode.NONE,
                    bosNoteFa = "بازگشت عمیق داخل ساختار شکسته؛ ستاپ باطل شد.",
                    reasonsFa = listOf("بازگشت عمیق داخل ناحیه؛ شکست ناموفق."),
                )
            }
        }

        val retest = closed[i]
        val touched = if (isLong) retest.low <= execLevel + 0.10 * breakoutAtr
        else retest.high >= execLevel - 0.10 * breakoutAtr
        val closedBack = if (isLong) retest.close >= execLevel else retest.close <= execLevel
        val bodyOk = PeymanMath.bodyRatio(retest) >= config.minRetestBodyRatio
        val dirOk = if (isLong) PeymanMath.bullish(retest) else PeymanMath.bearish(retest)
        val retestRvol = PeymanMath.relativeVolume(closed, i)
        val volOk = retestRvol != null && retestRvol >= config.minRetestVolumeRatio
        val volLessThanBreakout = retestRvol == null || rvolAtBreakout == null || retestRvol < rvolAtBreakout
        val invertedLevel = if (isLong) retest.close < breakoutLevel else retest.close > breakoutLevel

        return when {
            invertedLevel -> base.copy(
                status = PeymanSetupStatus.INVALIDATED,
                entryMode = PeymanEntryMode.NONE,
                bosNoteFa = "قیمت به سمت مخالف ناحیه برگشت؛ ستاپ باطل شد.",
                reasonsFa = listOf("عبور به سمت مخالف ناحیه؛ ستاپ باطل."),
            )
            touched && closedBack && bodyOk && dirOk && volOk && volLessThanBreakout -> base.copy(
                status = PeymanSetupStatus.READY,
                entryMode = PeymanEntryMode.RETEST_CONFIRMED,
                barsSinceBreakout = i - breakoutIdx,
                bosNoteFa = "بازآزمایی سطح با بدنه و حجم معتبر تأیید شد.",
                reasonsFa = emptyList(),
            )
            else -> {
                val notes = ArrayList<String>()
                if (!touched) notes.add("بازآزمایی هنوز سطح را لمس نکرده است.")
                if (!closedBack) notes.add("کلوز هنوز به آن‌طرف سطح برنگشته است.")
                if (!bodyOk) notes.add("بدنهٔ کندل بازآزمایی کافی نیست.")
                if (!dirOk) notes.add("کندل بازآزمایی هم‌جهت نیست.")
                if (!volOk) notes.add("حجم بازآزمایی کافی نیست.")
                if (volOk && !volLessThanBreakout) notes.add("حجم بازآزمایی از کندل شکست کمتر نیست.")
                base.copy(
                    status = PeymanSetupStatus.WAITING_RETEST,
                    entryMode = PeymanEntryMode.WAIT_RETEST,
                    bosNoteFa = notes.joinToString(" "),
                    reasonsFa = notes,
                )
            }
        }
    }

    fun buildSetupId(
        symbol: String,
        rulesVersion: String,
        interval: String,
        direction: SignalAction,
        breakoutBarTime: Long,
        level: Double,
    ): String = listOf(
        symbol.uppercase(java.util.Locale.ROOT),
        rulesVersion,
        interval,
        direction.name,
        breakoutBarTime.toString(),
        PeymanMath.round2(level).toString(),
    ).joinToString("|")

    private fun ratio(bars: List<Candle>, index: Int): Double {
        val cur = PeymanMath.atr(bars, index)
        val prev = PeymanMath.atr(bars, max(1, index - 1))
        return if (PeymanMath.isFinitePositive(prev)) cur / prev else 0.0
    }

    /** دامنهٔ کندل نسبت به میانهٔ دامنهٔ کندل‌های قبل (معیار مکمل انبساط). */
    private fun rangeExpansionRatio(bars: List<Candle>, index: Int, lookback: Int): Double {
        val from = max(0, index - lookback)
        if (index <= from) return 0.0
        val med = PeymanMath.median(bars.subList(from, index).map { PeymanMath.range(it) })
        if (med <= 0.0) return 0.0
        return PeymanMath.range(bars[index]) / med
    }

    private fun pct(v: Double): String = PeymanMath.faDigits("%.0f".format(java.util.Locale.US, v * 100)) + "٪"
}
