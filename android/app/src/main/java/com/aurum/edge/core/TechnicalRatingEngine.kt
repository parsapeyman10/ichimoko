package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * «نوار سنجش تکنیکال» — همان چیزی که در صفحهٔ نماد به شکل Sell / Neutral / Buy دیده می‌شود،
 * ولی محاسبه‌شده داخل خود برنامه از کندل‌های واقعی نماد.
 *
 * سنجش در لحظهٔ ورود ثبت می‌شود. گیت آماری مستقل می‌تواند از نمونه‌های قبلی برای
 * تصمیم ورود استفاده کند؛ قوانین ایچیموکو، اندازهٔ پوزیشن و حدها را تغییر نمی‌دهد.
 *
 * ترکیب رأی‌ها = همان ساختار استاندارد سنجش تکنیکال:
 *  • ۱۱ اسیلاتور (RSI, Stoch, CCI, ADX, Awesome, Momentum, MACD, Stoch RSI, Williams %R,
 *    Bull/Bear Power, Ultimate) — هر کدام -۱ / ۰ / +۱
 *  • ۱۵ میانگین متحرک (EMA 10/20/50/100/200، SMA 10/20/50/100/200، تنکان، کیجون،
 *    سنکو A، سنکو B، VWMA(20)) — قیمت بالاتر = خرید، پایین‌تر = فروش، چسبیده = خنثی
 *
 * جمع کل رأی‌ها = ۲۶ (مثل همان شمارنده‌ای که کاربر در تصویر نشان داد).
 */
data class RatingVote(
    val nameFa: String,
    val groupFa: String,
    val vote: Int,
)

enum class RatingBucketKey(val titleFa: String) {
    STRONG_BUY("خرید قوی"),
    BUY("خرید"),
    NEUTRAL("خنثی"),
    SELL("فروش"),
    STRONG_SELL("فروش قوی"),
}

data class TechnicalRating(
    val buy: Int,
    val neutral: Int,
    val sell: Int,
    val votes: List<RatingVote> = emptyList(),
) {
    val total: Int get() = buy + neutral + sell
    val available: Boolean get() = total > 0

    /** امتیاز خالص: مثبت = فشار خرید، منفی = فشار فروش. */
    val score: Int get() = buy - sell

    val bucket: RatingBucketKey
        get() = when {
            !available -> RatingBucketKey.NEUTRAL
            score >= STRONG_SCORE -> RatingBucketKey.STRONG_BUY
            score > 0 -> RatingBucketKey.BUY
            score == 0 -> RatingBucketKey.NEUTRAL
            score > -STRONG_SCORE -> RatingBucketKey.SELL
            else -> RatingBucketKey.STRONG_SELL
        }

    val labelFa: String get() = if (available) bucket.titleFa else "—"

    companion object {
        const val STRONG_SCORE = 10
        val UNKNOWN = TechnicalRating(0, 0, 0)
    }
}

/** رأی فعلی دو تایم‌فریم یک نماد. */
data class SymbolTechnicalRating(
    val oneHour: TechnicalRating = TechnicalRating.UNKNOWN,
    val fourHour: TechnicalRating = TechnicalRating.UNKNOWN,
) {
    val hasAny: Boolean get() = oneHour.available || fourHour.available
}

object TechnicalRatingEngine {

    /** کمترین کندل لازم برای این‌که همهٔ اندیکاتورها معنی داشته باشند (EMA200 در ۱۱۰ کندل نیست). */
    const val MIN_BARS = 60

    private const val NEUTRAL_BAND_PCT = 0.03

    fun evaluate(candles: List<Candle>): TechnicalRating? {
        if (candles.size < MIN_BARS) return null
        val bars = candles.sortedBy { it.time }
        val closes = bars.map { it.close }
        val highs = bars.map { it.high }
        val lows = bars.map { it.low }
        val volumes = bars.map { it.volume }
        val price = closes.last()
        if (!price.isFinite() || price <= 0.0) return null

        val votes = ArrayList<RatingVote>(26)
        fun add(name: String, group: String, vote: Int) {
            votes += RatingVote(name, group, vote)
        }

        // ── ۱. اسیلاتورها ───────────────────────────────────────────────────────
        val rsi = rsi(closes, 14)
        if (rsi != null) add("RSI(14)", OSC, if (rsi > 70.0) SELL else if (rsi < 30.0) BUY else NEUTRAL)

        val stoch = stochastic(highs, lows, closes, 14, 3)
        if (stoch != null) add("Stochastic %K(14,3)", OSC, if (stoch > 80.0) SELL else if (stoch < 20.0) BUY else NEUTRAL)

        val cci = cci(highs, lows, closes, 20)
        if (cci != null) add("CCI(20)", OSC, if (cci > 100.0) BUY else if (cci < -100.0) SELL else NEUTRAL)

        val adxPair = adxWithDirection(highs, lows, closes, 14)
        if (adxPair != null) {
            val (adxValue, plusDi, minusDi) = adxPair
            val vote = when {
                adxValue < 20.0 -> NEUTRAL
                plusDi > minusDi -> BUY
                else -> SELL
            }
            add("ADX(14) ±DI", OSC, vote)
        }

        val ao = awesomeOscillator(highs, lows)
        if (ao != null) add("Awesome Oscillator(5,34)", OSC, if (ao > 0.0) BUY else SELL)

        val momentum = momentum(closes, 10)
        if (momentum != null) add("Momentum(10)", OSC, if (momentum > 0.0) BUY else SELL)

        val macd = macdVote(closes)
        if (macd != null) add("MACD(12,26,9)", OSC, macd)

        val stochRsi = stochRsi(closes, 14)
        if (stochRsi != null) add("Stoch RSI(14)", OSC, if (stochRsi > 80.0) SELL else if (stochRsi < 20.0) BUY else NEUTRAL)

        val williams = williamsR(highs, lows, closes, 14)
        if (williams != null) add("Williams %R(14)", OSC, if (williams > -20.0) SELL else if (williams < -80.0) BUY else NEUTRAL)

        val bullBear = bullBearPower(highs, lows, closes, 13)
        if (bullBear != null) add("Bull/Bear Power(13)", OSC, if (bullBear > 0.0) BUY else SELL)

        val ultimate = ultimateOscillator(highs, lows, closes)
        if (ultimate != null) add("Ultimate Oscillator", OSC, if (ultimate > 70.0) SELL else if (ultimate < 30.0) BUY else NEUTRAL)

        // ── ۲. میانگین‌های متحرک ────────────────────────────────────────────────
        fun voteAgainstMa(name: String, ma: Double?) {
            if (ma == null || !ma.isFinite() || ma <= 0.0) return
            val diffPct = (price - ma) / ma * 100.0
            add(name, MA, when {
                abs(diffPct) < NEUTRAL_BAND_PCT -> NEUTRAL
                diffPct > 0.0 -> BUY
                else -> SELL
            })
        }

        for (period in listOf(10, 20, 50, 100, 200)) voteAgainstMa("EMA($period)", ema(closes, period))
        for (period in listOf(10, 20, 50, 100, 200)) voteAgainstMa("SMA($period)", sma(closes, period))

        voteAgainstMa("تنکان (۹)", (highs.takeLast(9).maxOrNull()!! + lows.takeLast(9).minOrNull()!!) / 2.0)
        voteAgainstMa("کیجون (۲۶)", (highs.takeLast(26).maxOrNull()!! + lows.takeLast(26).minOrNull()!!) / 2.0)

        val senkouA = run {
            val tenkan = (highs.takeLast(9).maxOrNull()!! + lows.takeLast(9).minOrNull()!!) / 2.0
            val kijun = (highs.takeLast(26).maxOrNull()!! + lows.takeLast(26).minOrNull()!!) / 2.0
            (tenkan + kijun) / 2.0
        }
        val senkouB = if (bars.size >= 52) {
            (highs.takeLast(52).maxOrNull()!! + lows.takeLast(52).minOrNull()!!) / 2.0
        } else {
            null
        }
        voteAgainstMa("سنکو A", senkouA)
        voteAgainstMa("سنکو B", senkouB)

        voteAgainstMa("VWMA(20)", vwma(closes, volumes, 20))

        if (votes.isEmpty()) return null
        val buy = votes.count { it.vote == BUY }
        val sell = votes.count { it.vote == SELL }
        val neutral = votes.count { it.vote == NEUTRAL }
        return TechnicalRating(buy = buy, neutral = neutral, sell = sell, votes = votes)
    }

    private const val BUY = 1
    private const val NEUTRAL = 0
    private const val SELL = -1
    private const val OSC = "اسیلاتور"
    private const val MA = "میانگین متحرک"

    // ── محاسبه‌های خالص ────────────────────────────────────────────────────────

    fun sma(values: List<Double>, period: Int): Double? {
        if (period <= 0 || values.size < period) return null
        return values.takeLast(period).average()
    }

    fun ema(values: List<Double>, period: Int): Double? {
        if (period <= 0 || values.size < period) return null
        val k = 2.0 / (period + 1.0)
        var value = values.take(period).average()
        for (i in period until values.size) {
            value = values[i] * k + value * (1.0 - k)
        }
        return value
    }

    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val delta = closes[i] - closes[i - 1]
            if (delta >= 0.0) gain += delta else loss -= delta
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until closes.size) {
            val delta = closes[i] - closes[i - 1]
            val g = if (delta > 0.0) delta else 0.0
            val l = if (delta < 0.0) -delta else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
        }
        if (avgLoss <= 1e-12) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun stochastic(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14, smooth: Int = 3): Double? {
        if (closes.size < period + smooth) return null
        val raw = ArrayList<Double>(smooth)
        for (offset in smooth - 1 downTo 0) {
            val end = closes.size - offset
            if (end - period < 0) return null
            val hh = highs.subList(end - period, end).maxOrNull() ?: return null
            val ll = lows.subList(end - period, end).minOrNull() ?: return null
            val range = hh - ll
            raw += if (range <= 1e-12) 50.0 else (closes[end - 1] - ll) / range * 100.0
        }
        return raw.average()
    }

    fun cci(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 20): Double? {
        if (closes.size < period) return null
        val tp = ArrayList<Double>(period)
        for (i in closes.size - period until closes.size) {
            tp += (highs[i] + lows[i] + closes[i]) / 3.0
        }
        val mean = tp.average()
        val dev = tp.sumOf { abs(it - mean) } / period
        if (dev <= 1e-12) return 0.0
        return (tp.last() - mean) / (0.015 * dev)
    }

    /** ADX با جهت (+DI/−DI) — همان چیزی که سنجش تکنیکال لازم دارد. */
    fun adxWithDirection(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
        period: Int = 14,
    ): Triple<Double, Double, Double>? {
        if (closes.size < period * 2 + 1) return null
        val tr = ArrayList<Double>()
        val plusDm = ArrayList<Double>()
        val minusDm = ArrayList<Double>()
        for (i in 1 until closes.size) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            plusDm += if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusDm += if (downMove > upMove && downMove > 0.0) downMove else 0.0
            tr += max(highs[i] - lows[i], max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1])))
        }
        if (tr.size < period) return null
        var atr = tr.take(period).average()
        var plusSmoothed = plusDm.take(period).average()
        var minusSmoothed = minusDm.take(period).average()
        val dxList = ArrayList<Double>()
        for (i in period until tr.size) {
            atr = (atr * (period - 1) + tr[i]) / period
            plusSmoothed = (plusSmoothed * (period - 1) + plusDm[i]) / period
            minusSmoothed = (minusSmoothed * (period - 1) + minusDm[i]) / period
            if (atr > 1e-12) {
                val plusDi = plusSmoothed / atr * 100.0
                val minusDi = minusSmoothed / atr * 100.0
                val sum = plusDi + minusDi
                dxList += if (sum <= 1e-12) 0.0 else abs(plusDi - minusDi) / sum * 100.0
            }
        }
        if (dxList.size < period) return null
        var adx = dxList.take(period).average()
        for (i in period until dxList.size) {
            adx = (adx * (period - 1) + dxList[i]) / period
        }
        val plusDi = if (atr > 1e-12) plusSmoothed / atr * 100.0 else 0.0
        val minusDi = if (atr > 1e-12) minusSmoothed / atr * 100.0 else 0.0
        return Triple(adx, plusDi, minusDi)
    }

    fun awesomeOscillator(highs: List<Double>, lows: List<Double>): Double? {
        if (highs.size < 34) return null
        val median = highs.indices.map { (highs[it] + lows[it]) / 2.0 }
        val fast = sma(median, 5) ?: return null
        val slow = sma(median, 34) ?: return null
        return fast - slow
    }

    fun momentum(closes: List<Double>, period: Int = 10): Double? {
        if (closes.size <= period) return null
        return closes.last() - closes[closes.size - 1 - period]
    }

    fun macdVote(closes: List<Double>): Int? {
        val macdLine = macdLine(closes) ?: return null
        return if (macdLine >= 0.0) BUY else SELL
    }

    fun macdLine(closes: List<Double>): Double? {
        val fast = ema(closes, 12) ?: return null
        val slow = ema(closes, 26) ?: return null
        return fast - slow
    }

    fun stochRsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size < period * 2 + 1) return null
        val rsiSeries = ArrayList<Double>()
        for (end in closes.size - period until closes.size) {
            rsi(closes.subList(0, end), period)?.let { rsiSeries += it }
        }
        if (rsiSeries.size < period) return null
        val window = rsiSeries.takeLast(period)
        val hh = window.maxOrNull() ?: return null
        val ll = window.minOrNull() ?: return null
        val range = hh - ll
        return if (range <= 1e-12) 50.0 else (rsiSeries.last() - ll) / range * 100.0
    }

    fun williamsR(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): Double? {
        if (closes.size < period) return null
        val hh = highs.takeLast(period).maxOrNull() ?: return null
        val ll = lows.takeLast(period).minOrNull() ?: return null
        val range = hh - ll
        if (range <= 1e-12) return null
        return (hh - closes.last()) / range * -100.0
    }

    fun bullBearPower(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 13): Double? {
        val emaClose = ema(closes, period) ?: return null
        val bull = (highs.last() - emaClose)
        val bear = (lows.last() - emaClose)
        return bull + bear
    }

    fun ultimateOscillator(highs: List<Double>, lows: List<Double>, closes: List<Double>): Double? {
        if (closes.size < 29) return null
        fun avg(period: Int): Double? {
            if (closes.size < period + 1) return null
            var bp = 0.0
            var tr = 0.0
            for (i in closes.size - period until closes.size) {
                val trueLow = min(lows[i], closes[i - 1])
                val trueHigh = max(highs[i], closes[i - 1])
                bp += closes[i] - trueLow
                tr += trueHigh - trueLow
            }
            return if (tr <= 1e-12) null else bp / tr
        }
        val a = avg(7) ?: return null
        val b = avg(14) ?: return null
        val c = avg(28) ?: return null
        return 100.0 * (4 * a + 2 * b + c) / 7.0
    }

    fun vwma(closes: List<Double>, volumes: List<Double>, period: Int = 20): Double? {
        if (closes.size < period || volumes.size != closes.size) return null
        var pv = 0.0
        var vol = 0.0
        for (i in closes.size - period until closes.size) {
            pv += closes[i] * volumes[i]
            vol += volumes[i]
        }
        return if (vol <= 1e-12) sma(closes, period) else pv / vol
    }
}
