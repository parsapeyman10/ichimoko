package com.aurum.edge.core.peyman

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # ریاضیات مشترک موتور پیمان
 *
 * همهٔ توابع این فایل Pure و بدون حالت‌اند. `endIndex` همیشه شامل خود کندل جاری است،
 * اما هیچ تابعی حق ندارد کندل بعد از `endIndex` را ببیند (قاعدهٔ ضد‌نگاه‌به‌آینده).
 */
internal object PeymanMath {

    /** کندل‌های بستهٔ معتبر؛ کندل در حال تشکیل هرگز وارد تحلیل نمی‌شود. */
    fun closed(bars: List<Candle>): List<Candle> = bars.filter { it.closed }

    fun isFinitePositive(v: Double): Boolean = v.isFinite() && v > 0.0

    /** میانگین دامنهٔ واقعی ۱۴ کندل تا `endIndex` (شامل). */
    fun atr(bars: List<Candle>, endIndex: Int, period: Int = 14): Double {
        if (bars.size < 2 || endIndex < 1) return 0.0
        val last = min(endIndex, bars.size - 1)
        val first = max(1, last - period + 1)
        var sum = 0.0
        var n = 0
        for (k in first..last) {
            val prevClose = bars[k - 1].close
            val tr = max(
                bars[k].high - bars[k].low,
                max(abs(bars[k].high - prevClose), abs(bars[k].low - prevClose)),
            )
            if (tr.isFinite()) {
                sum += tr
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    fun range(c: Candle): Double = (c.high - c.low).coerceAtLeast(0.0)

    /** بدنه نسبت به دامنه (۰..۱). */
    fun bodyRatio(c: Candle): Double {
        val r = range(c)
        return if (r <= 0.0) 0.0 else abs(c.close - c.open) / r
    }

    /** CLV: محل بسته‌شدن در دامنهٔ کندل (۰ = کف، ۱ = سقف). */
    fun clv(c: Candle): Double {
        val r = range(c)
        return if (r <= 0.0) 0.5 else (c.close - c.low) / r
    }

    /** شدوی بالایی نسبت به دامنه. */
    fun upperWickRatio(c: Candle): Double {
        val r = range(c)
        return if (r <= 0.0) 0.0 else (c.high - max(c.open, c.close)) / r
    }

    /** شدوی پایینی نسبت به دامنه. */
    fun lowerWickRatio(c: Candle): Double {
        val r = range(c)
        return if (r <= 0.0) 0.0 else (min(c.open, c.close) - c.low) / r
    }

    fun bullish(c: Candle): Boolean = c.close > c.open

    fun bearish(c: Candle): Boolean = c.close < c.open

    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }

    /** صدک (۰..۱۰۰) مقدار `value` نسبت به سری تاریخی؛ فقط دادهٔ «قبل» به آن داده می‌شود. */
    fun percentileOf(value: Double, history: List<Double>): Double {
        if (history.isEmpty()) return 50.0
        val below = history.count { it <= value }
        return below * 100.0 / history.size
    }

    /**
     * Efficiency Ratio کافمن: |خالص حرکت| تقسیم بر مجموع حرکت مطلق.
     * ۱ = روند خالص، ۰ = نویز کامل.
     */
    fun efficiencyRatio(bars: List<Candle>, endIndex: Int, period: Int): Double {
        if (endIndex < period || endIndex >= bars.size) return 0.0
        val net = abs(bars[endIndex].close - bars[endIndex - period].close)
        var path = 0.0
        for (k in endIndex - period + 1..endIndex) {
            path += abs(bars[k].close - bars[k - 1].close)
        }
        return if (path <= 0.0) 0.0 else (net / path).coerceIn(0.0, 1.0)
    }

    /** حجم نسبی کندل جاری به میانگین ۲۰ کندل قبل (بدون احتساب خودش). */
    fun relativeVolume(bars: List<Candle>, endIndex: Int, lookback: Int = 20): Double? {
        if (endIndex < 1 || endIndex >= bars.size) return null
        val from = max(0, endIndex - lookback)
        val hist = bars.subList(from, endIndex).map { it.volume }.filter { it > 0.0 }
        if (hist.size < 5) return null
        val avg = mean(hist)
        if (avg <= 0.0) return null
        return bars[endIndex].volume / avg
    }

    /** آیا دادهٔ حجم قابل‌اعتماد است؟ (همه مثبت و همه یکسان نباشند) */
    fun volumeAvailable(bars: List<Candle>): Boolean {
        if (bars.isEmpty()) return false
        if (bars.any { !it.volume.isFinite() || it.volume <= 0.0 }) return false
        val distinct = bars.map { it.volume }.distinct().size
        return distinct > 1
    }

    /** کلوزِ آخرین کندل بسته. */
    fun lastClose(bars: List<Candle>): Double = bars.lastOrNull()?.close ?: 0.0

    fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    /** ارقام فارسی برای متن رابط کاربری. */
    fun faDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    '0' -> '۰'
                    '1' -> '۱'
                    '2' -> '۲'
                    '3' -> '۳'
                    '4' -> '۴'
                    '5' -> '۵'
                    '6' -> '۶'
                    '7' -> '۷'
                    '8' -> '۸'
                    '9' -> '۹'
                    '.' -> '٫'
                    else -> ch
                },
            )
        }
        return sb.toString()
    }
}

/**
 * # سلامت داده و Kill Switch
 *
 * تصمیم‌گیری روی دادهٔ کهنه یا ناسالم، بدترین حالت است: به نظر معتبر می‌آید ولی نیست.
 * پس هر نقصی این‌جا به «ورود ممنوع» تبدیل می‌شود، نه به یک برچسب تزئینی.
 */
object CryptoDataHealthGate {

    fun check(input: PeymanCryptoInput): PeymanDataHealth {
        val cfg = input.config
        val trigger = PeymanMath.closed(input.triggerBars)
        val strategy = PeymanMath.closed(input.strategyBars)
        val macro = PeymanMath.closed(input.macroBars)

        if (trigger.size < cfg.minBars) {
            return PeymanDataHealth.broken(
                "کندل بستهٔ کافی در تایم ماشه نیست (${trigger.size} از ${cfg.minBars}).",
                trigger.size, strategy.size, macro.size,
            )
        }
        if (strategy.size < cfg.minBars) {
            return PeymanDataHealth.broken(
                "کندل بستهٔ کافی در تایم استراتژی نیست (${strategy.size} از ${cfg.minBars}).",
                trigger.size, strategy.size, macro.size,
            )
        }
        if (macro.size < 30) {
            return PeymanDataHealth.broken(
                "کندل بستهٔ کافی در تایم ماکرو نیست (${macro.size} از ۳۰).",
                trigger.size, strategy.size, macro.size,
            )
        }

        // ترتیب زمانی و نبود تکرار.
        if (!isMonotonic(trigger) || !isMonotonic(strategy) || !isMonotonic(macro)) {
            return PeymanDataHealth.broken("ترتیب زمانی کندل‌ها معیوب است یا کندل تکراری وجود دارد.")
        }

        // قیمت‌های متناهی و مثبت.
        for (bars in listOf(trigger, strategy, macro)) {
            if (bars.any { !it.high.isFinite() || !it.low.isFinite() || !it.close.isFinite() || it.close <= 0.0 || it.high < it.low }) {
                return PeymanDataHealth.broken("قیمت نامعتبر (NaN/Inf یا high<low) در داده وجود دارد.")
            }
        }

        val atr = PeymanMath.atr(trigger, trigger.size - 1)
        if (!PeymanMath.isFinitePositive(atr)) {
            return PeymanDataHealth.broken("ATR صفر یا نامعتبر است؛ تحلیل و ورود ممکن نیست.")
        }

        // تازگی داده: کندل آخر نباید از سه تایم‌فریم قدیمی‌تر باشد.
        val ageMin = if (input.nowMillis > 0L) {
            (input.nowMillis - trigger.last().time).toDouble() / 60_000.0
        } else {
            0.0
        }
        val allowedAge = input.triggerInterval.minutes * cfg.maxStalenessBars
        if (ageMin > allowedAge) {
            return PeymanDataHealth.broken(
                "دادهٔ ماشه کهنه است (${PeymanMath.faDigits("%.0f".format(java.util.Locale.US, ageMin))} دقیقه).",
                trigger.size, strategy.size, macro.size,
            )
        }

        // اختلاف مبنای قیمت بین تایم‌فریم‌ها: تحلیل روی دو مبنای مختلف بی‌معناست.
        val trigClose = trigger.last().close
        val stratClose = strategy.last().close
        val macroClose = macro.last().close
        if (relativeGap(trigClose, stratClose) > cfg.maxPriceBasisGap ||
            relativeGap(trigClose, macroClose) > cfg.maxPriceBasisGap
        ) {
            return PeymanDataHealth.broken(
                "مبنای قیمت بین تایم‌فریم‌ها نمی‌خواند (اختلاف بیش از " +
                    "${PeymanMath.faDigits("%.0f".format(java.util.Locale.US, cfg.maxPriceBasisGap * 100))}٪).",
                trigger.size, strategy.size, macro.size,
            )
        }

        val volume = PeymanMath.volumeAvailable(trigger)
        return PeymanDataHealth.healthy(trigger.size, strategy.size, macro.size, atr, volume, ageMin)
    }

    private fun relativeGap(a: Double, b: Double): Double {
        if (a <= 0.0 || b <= 0.0) return 1.0
        return abs(a / b - 1.0)
    }

    private fun isMonotonic(bars: List<Candle>): Boolean {
        for (i in 1 until bars.size) {
            if (bars[i].time <= bars[i - 1].time) return false
        }
        return true
    }
}

/** تصویر رژیم بازار همراه با نشانه‌های عددی که تصمیم را ساخته‌اند. */
data class CryptoRegimeSnapshot(
    val regime: CryptoMarketRegime,
    val atrPercentile: Double,
    val efficiencyRatio14: Double,
    val efficiencyRatio50: Double,
    val rangeRatio: Double,
    val relativeVolume: Double?,
    val shockBar: Boolean,
    val reasonsFa: List<String>,
) {
    val reasonsJoinedFa: String get() = reasonsFa.joinToString("، ")
}

/**
 * # موتور رژیم بازار
 *
 * رژیم با یک اندیکاتور تعیین نمی‌شود: ATR-صدک + Efficiency Ratio + نسبت دامنه +
 * حجم نسبی + کندل شوک. اگر نشانه‌ها متناقض باشند، `UNKNOWN` صادقانه‌تر از حدس است.
 */
object CryptoMarketRegimeEngine {

    fun detect(bars: List<Candle>, config: PeymanCryptoConfig = PeymanCryptoConfig()): CryptoRegimeSnapshot {
        val closed = PeymanMath.closed(bars)
        if (closed.size < config.minBars) {
            return CryptoRegimeSnapshot(
                CryptoMarketRegime.UNKNOWN, 50.0, 0.0, 0.0, 1.0, null, false,
                listOf("کندل کافی برای تشخیص رژیم نیست."),
            )
        }
        val i = closed.size - 1
        val atr = PeymanMath.atr(closed, i)
        // صدک ATR فقط از تاریخ «قبل» ساخته می‌شود (بدون نگاه به آینده).
        val atrHistory = ArrayList<Double>(100)
        var k = i - 100
        if (k < 15) k = 15
        while (k < i) {
            val v = PeymanMath.atr(closed, k)
            if (PeymanMath.isFinitePositive(v)) atrHistory.add(v)
            k++
        }
        val atrPct = PeymanMath.percentileOf(atr, atrHistory)
        val er14 = PeymanMath.efficiencyRatio(closed, i, 14)
        val er50 = PeymanMath.efficiencyRatio(closed, i, min(50, i))
        val rangeRecent = highLow(closed, i, 20)
        val rangePrev = highLow(closed, i, 100)
        val rangeRatio = if (rangePrev <= 0.0) 1.0 else rangeRecent / rangePrev
        val rvol = PeymanMath.relativeVolume(closed, i)
        val last = closed[i]
        val shock = PeymanMath.range(last) >= 3.0 * atr

        val notes = ArrayList<String>()
        notes.add("صدک ATR ${PeymanMath.faDigits("%.0f".format(java.util.Locale.US, atrPct))}٪")
        notes.add("ER۱۴ ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, er14))}")
        notes.add("نسبت دامنه ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, rangeRatio))}")
        rvol?.let { notes.add("حجم نسبی ${PeymanMath.faDigits("%.2f".format(java.util.Locale.US, it))}×") }
        if (shock) notes.add("کندل شوک (دامنه ≥ ۳×ATR)")

        // شوک با دامنهٔ غیرعادی و حجم انفجاری.
        if (shock && (rvol ?: 1.0) >= 2.5) {
            val drop = last.close < last.open
            val regime = if (drop) CryptoMarketRegime.PANIC_CAPITULATION else CryptoMarketRegime.DISTRIBUTION
            return CryptoRegimeSnapshot(regime, atrPct, er14, er50, rangeRatio, rvol, true, notes)
        }

        val compression = atrPct <= 30.0 && rangeRatio <= 0.65 && er14 <= 0.32
        val trend = er14 >= 0.45 && rangeRatio >= 1.00 && atrPct >= 35.0
        val correction = !compression && er50 >= 0.35 && er14 <= 0.35
        val distribution = !trend && atrPct >= 60.0 && lowerHighs(closed, i, 20)

        val hits = listOf(compression, trend, correction, distribution).count { it }
        val regime = when {
            compression -> CryptoMarketRegime.COMPRESSION
            trend -> CryptoMarketRegime.TREND_EXPANSION
            correction -> CryptoMarketRegime.CORRECTION
            distribution -> CryptoMarketRegime.DISTRIBUTION
            hits > 1 -> CryptoMarketRegime.UNKNOWN
            else -> CryptoMarketRegime.UNKNOWN
        }
        if (regime == CryptoMarketRegime.UNKNOWN) notes.add("نشانه‌ها هم‌سو نیستند؛ رژیم نامشخص اعلام شد.")
        return CryptoRegimeSnapshot(regime, atrPct, er14, er50, rangeRatio, rvol, shock, notes)
    }

    private fun highLow(bars: List<Candle>, endIndex: Int, lookback: Int): Double {
        val from = max(0, endIndex - lookback + 1)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (k in from..endIndex) {
            if (bars[k].high > hi) hi = bars[k].high
            if (bars[k].low < lo) lo = bars[k].low
        }
        return if (hi.isFinite() && lo.isFinite()) hi - lo else 0.0
    }

    /** آیا سقف‌های اخیر پایین‌تر شده‌اند (نشانهٔ توزیع)؟ */
    private fun lowerHighs(bars: List<Candle>, endIndex: Int, lookback: Int): Boolean {
        val from = max(0, endIndex - lookback + 1)
        val thirds = (endIndex - from + 1) / 3
        if (thirds < 3) return false
        val a = bars.subList(from, from + thirds).maxOf { it.high }
        val b = bars.subList(from + thirds, from + 2 * thirds).maxOf { it.high }
        val c = bars.subList(from + 2 * thirds, endIndex + 1).maxOf { it.high }
        return a > b && b > c
    }
}

/** زمینهٔ BTC برای آلت‌ها؛ بدون دادهٔ تازه، آلت رد می‌شود (نه «خنثی»). */
object BtcContextGate {

    fun evaluate(
        symbol: String,
        isAlt: Boolean,
        context: BtcMarketContext?,
        direction: SignalAction,
        nowMillis: Long,
    ): PeymanGateResult {
        if (!isAlt) return PeymanGateResult.ok("BTC_SELF", "")
        if (context == null || !context.isFresh) {
            return PeymanGateResult.fail(
                "BTC_CONTEXT_MISSING",
                "دادهٔ زمینهٔ BTC برای این آلت در دسترس نیست؛ ورود مسدود است.",
            )
        }
        val ageMin = if (nowMillis > 0L) (nowMillis - context.dataTimestamp) / 60_000.0 else 0.0
        if (ageMin > 30.0) {
            return PeymanGateResult.fail(
                "BTC_CONTEXT_STALE",
                "دادهٔ زمینهٔ BTC کهنه است (${PeymanMath.faDigits("%.0f".format(java.util.Locale.US, ageMin))} دقیقه).",
            )
        }
        if (direction == SignalAction.BUY && context.regime == CryptoMarketRegime.PANIC_CAPITULATION) {
            return PeymanGateResult.fail("BTC_PANIC_LONG", "BTC در هراس است؛ خرید آلت مجاز نیست.")
        }
        if (direction == SignalAction.SELL && context.regime == CryptoMarketRegime.TREND_EXPANSION &&
            context.trendDirection == SignalAction.BUY
        ) {
            return PeymanGateResult.fail("BTC_PUMP_SHORT", "BTC در پامپ قوی است؛ فروش آلت سخت‌گیرانه رد شد.")
        }
        return PeymanGateResult.ok("BTC_OK", "")
    }

    fun describe(context: BtcMarketContext?): String = when {
        context == null -> "BTC: دادهٔ زمینه موجود نیست"
        !context.isFresh -> "BTC: دادهٔ زمینه نامعتبر"
        else -> "BTC: ${context.regime.labelFa} · جهت ${context.trendDirection.name}"
    }
}
