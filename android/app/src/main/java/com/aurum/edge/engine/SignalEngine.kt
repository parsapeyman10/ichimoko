package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Closed-bar Ichimoku + VWAP + EMA200 + RSI + ATR fusion, ported from the project specification.
 *
 * Differences from the old web "demo" engine:
 *  - evaluation runs ONLY on real closed bars received from the provider;
 *  - every branch that used to synthesise a direction to keep the screen busy is gone;
 *  - when conditions are not met the engine returns NO_TRADE plus the exact blockers.
 *
 * The engine works on a pre-computed [Series] so the live tick path and the historical
 * back-test share one single implementation (what you test is what you run).
 */
object SignalEngine {

    data class IchiSetting(val tenkan: Int, val kijun: Int, val spanB: Int)

    /** All indicator columns for one candle series; value at index i depends only on bars 0..i. */
    class Series(
        val bars: List<Candle>,
        val interval: Interval,
        val setting: IchiSetting,
        val ichimoku: Ichimoku,
        val ema200: List<Double?>,
        val vwap: List<Double>,
        val rsi: List<Double?>,
        val atr: List<Double?>,
        val macd: List<Double?>,
        val adx: List<Double?>,
        val relVolume: List<Double?>,
        val bbUpper: List<Double?>,
        val bbLower: List<Double?>,
    ) {
        val size: Int get() = bars.size
        val lastIndex: Int get() = bars.size - 1
    }

    fun ichimokuSetting(interval: Interval): IchiSetting = when (interval) {
        Interval.M1 -> IchiSetting(7, 22, 44)
        else -> IchiSetting(9, 26, 52)
    }

    /** Bars a signal stays valid before it must be re-confirmed. */
    fun barsValid(interval: Interval): Int = when (interval) {
        Interval.M1 -> 8
        Interval.M5 -> 6
        else -> 4
    }

    fun minBars(interval: Interval): Int {
        val s = ichimokuSetting(interval)
        return max(s.spanB + s.kijun + 20, 210)
    }

    fun series(candles: List<Candle>, interval: Interval): Series {
        val setting = ichimokuSetting(interval)
        val bars = candles.filter { it.closed }
        val closes = bars.map { it.close }
        return Series(
            bars = bars,
            interval = interval,
            setting = setting,
            ichimoku = Ichimoku.compute(bars, setting.tenkan, setting.kijun, setting.spanB, setting.kijun),
            ema200 = Indicators.ema(closes, 200),
            vwap = Indicators.sessionVwap(bars),
            rsi = Indicators.rsi(bars, 7),
            atr = Indicators.atr(bars, 14),
            macd = Indicators.macdHistogram(bars),
            adx = Indicators.adx(bars, 14),
            relVolume = Indicators.relativeVolume(bars, 30),
            bbUpper = Indicators.bollinger(bars, 20, 2.0).first,
            bbLower = Indicators.bollinger(bars, 20, 2.0).third,
        )
    }

    data class Snapshot(
        val index: Int,
        val barTime: Long,
        val price: Double,
        val tenkan: Double,
        val kijun: Double,
        val cloudTop: Double,
        val cloudBottom: Double,
        val spanA: Double,
        val spanB: Double,
        val ema200: Double,
        val vwap: Double,
        val rsi: Double,
        val atr: Double,
        val atrShock: Boolean,
        val macdHist: Double?,
        val adx: Double?,
        val relVolume: Double?,
        val bbUpper: Double?,
        val bbLower: Double?,
        val chikouBuyClear: Boolean,
        val chikouSellClear: Boolean,
        val bullCross: Boolean,
        val bearCross: Boolean,
    )

    fun snapshot(series: Series, index: Int): Snapshot? {
        val bars = series.bars
        if (index < minBars(series.interval) - 1 || index !in bars.indices) return null
        val i = index
        val tenkan = series.ichimoku.tenkan.getOrNull(i) ?: return null
        val kijun = series.ichimoku.kijun.getOrNull(i) ?: return null
        val cloud = series.ichimoku.cloudAt(i) ?: return null
        val atrValue = series.atr.getOrNull(i) ?: return null
        if (atrValue <= 0.0) return null
        val emaValue = series.ema200.getOrNull(i) ?: return null
        val rsiValue = series.rsi.getOrNull(i) ?: return null

        val prevTenkan = series.ichimoku.tenkan.getOrNull(i - 1)
        val prevKijun = series.ichimoku.kijun.getOrNull(i - 1)
        val prev2Tenkan = series.ichimoku.tenkan.getOrNull(i - 2)
        val prev2Kijun = series.ichimoku.kijun.getOrNull(i - 2)

        val crossedUpNow = prevTenkan != null && prevKijun != null && prevTenkan <= prevKijun && tenkan > kijun
        val crossedUpBefore = prev2Tenkan != null && prev2Kijun != null && prev2Tenkan <= prev2Kijun &&
            prevTenkan != null && prevKijun != null && prevTenkan > prevKijun && tenkan > kijun
        val crossedDownNow = prevTenkan != null && prevKijun != null && prevTenkan >= prevKijun && tenkan < kijun
        val crossedDownBefore = prev2Tenkan != null && prev2Kijun != null && prev2Tenkan >= prev2Kijun &&
            prevTenkan != null && prevKijun != null && prevTenkan < prevKijun && tenkan < kijun

        val atrWindow = series.atr.subList(max(0, i - 60), i).filterNotNull().sorted()
        val atrMedian = if (atrWindow.isEmpty()) atrValue else atrWindow[atrWindow.size / 2]

        val chikouIndex = i - series.setting.kijun
        val chikouBuyClear = chikouIndex >= 0 && bars[i].close > bars[chikouIndex].high
        val chikouSellClear = chikouIndex >= 0 && bars[i].close < bars[chikouIndex].low

        return Snapshot(
            index = i,
            barTime = bars[i].time,
            price = bars[i].close,
            tenkan = tenkan,
            kijun = kijun,
            cloudTop = max(cloud.first, cloud.second),
            cloudBottom = min(cloud.first, cloud.second),
            spanA = cloud.first,
            spanB = cloud.second,
            ema200 = emaValue,
            vwap = series.vwap.getOrNull(i) ?: bars[i].close,
            rsi = rsiValue,
            atr = atrValue,
            atrShock = atrValue > atrMedian * 2.5,
            macdHist = series.macd.getOrNull(i),
            adx = series.adx.getOrNull(i),
            relVolume = series.relVolume.getOrNull(i),
            bbUpper = series.bbUpper.getOrNull(i),
            bbLower = series.bbLower.getOrNull(i),
            chikouBuyClear = chikouBuyClear,
            chikouSellClear = chikouSellClear,
            bullCross = crossedUpNow || crossedUpBefore,
            bearCross = crossedDownNow || crossedDownBefore,
        )
    }

    /**
     * Decision for the bar at [index]. Uses only information available up to and including that bar.
     * [narrative] adds Persian reason/blocker strings (used by the UI; skipped inside hot loops).
     */
    fun decide(
        series: Series,
        index: Int,
        threshold: Double = 72.0,
        spread: Double? = null,
        narrative: Boolean = true,
    ): Signal {
        val interval = series.interval
        val bars = series.bars
        val minRequired = minBars(interval)
        if (bars.size < minRequired) {
            return Signal(
                action = SignalAction.NO_TRADE,
                confidence = 0.0,
                blockers = if (narrative) listOf("داده کافی نیست — $minRequired کندل بسته لازم است، ${bars.size} کندل واقعی موجود است") else emptyList(),
                interval = interval,
            )
        }
        val snap = snapshot(series, index) ?: return Signal(
            action = SignalAction.NO_TRADE,
            confidence = 0.0,
            blockers = if (narrative) listOf("محاسبه اندیکاتورها روی این کندل ممکن نیست") else emptyList(),
            interval = interval,
            barTime = bars.getOrNull(index)?.time ?: 0L,
        )

        val direction = when {
            snap.bullCross -> SignalAction.BUY
            snap.bearCross -> SignalAction.SELL
            else -> null
        }
        val long = direction == SignalAction.BUY
        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        val confluence = mutableListOf<ConfluenceItem>()
        val minThreshold = threshold.coerceAtLeast(72.0)

        var score = 20.0
        if (direction != null) {
            if (narrative) reasons += if (long) "کراس تازه صعودی تنکان/کیجون" else "کراس تازه نزولی تنکان/کیجون"
        } else if (narrative) {
            blockers += "کراس تازه تنکان/کیجون شکل نگرفته — ورود ممنوع"
        }
        if (narrative) {
            confluence += ConfluenceItem(
                "کراس تنکان/کیجون (${series.setting.tenkan}/${series.setting.kijun})",
                direction != null,
                "T ${fmt(snap.tenkan)} / K ${fmt(snap.kijun)}",
            )
        }

        val clearance = 0.08 * snap.atr
        val cloudOk = when (direction) {
            SignalAction.BUY -> snap.price > snap.cloudTop + clearance
            SignalAction.SELL -> snap.price < snap.cloudBottom - clearance
            else -> false
        }
        if (cloudOk) {
            score += 18
            if (narrative) reasons += "قیمت فراتر از ابر کومو با تلورانس 0.08×ATR"
        } else if (direction != null && narrative) {
            blockers += if (long) "قیمت داخل/نزدیک ابر — شکست صعودی تایید نشده" else "قیمت داخل/نزدیک ابر — شکست نزولی تایید نشده"
        }
        if (narrative) confluence += ConfluenceItem("قبول قیمت خارج ابر", cloudOk, "${fmt(snap.cloudTop)} — ${fmt(snap.cloudBottom)}")

        val spanOk = when (direction) {
            SignalAction.BUY -> snap.spanA > snap.spanB
            SignalAction.SELL -> snap.spanA < snap.spanB
            else -> false
        }
        if (spanOk) {
            score += 10
            if (narrative) reasons += "ابر آینده هم‌جهت"
        } else if (direction != null && narrative) blockers += "ابر آینده مخالف جهت معامله"
        if (narrative) confluence += ConfluenceItem("هم‌جهتی Senkou A/B", spanOk, "A ${fmt(snap.spanA)} / B ${fmt(snap.spanB)}")

        val chikouOk = when (direction) {
            SignalAction.BUY -> snap.chikouBuyClear
            SignalAction.SELL -> snap.chikouSellClear
            else -> false
        }
        if (chikouOk) {
            score += 10
            if (narrative) reasons += "تایید چیکو نسبت به ساختار ${series.setting.kijun} کندل قبل"
        } else if (direction != null && narrative) blockers += "چیکو تایید نمی‌کند — ساختار قبلی نقض می‌شود"
        if (narrative) confluence += ConfluenceItem("تایید Chikou", chikouOk, if (long) "close بالای high قبلی" else "close زیر low قبلی")

        val emaOk = when (direction) {
            SignalAction.BUY -> snap.price > snap.ema200
            SignalAction.SELL -> snap.price < snap.ema200
            else -> false
        }
        if (emaOk) {
            score += 15
            if (narrative) reasons += "هم‌جهت با EMA200"
        } else if (direction != null && narrative) blockers += "خلاف روند EMA200"
        if (narrative) confluence += ConfluenceItem("EMA200", emaOk, fmt(snap.ema200))

        val vwapOk = when (direction) {
            SignalAction.BUY -> snap.price > snap.vwap
            SignalAction.SELL -> snap.price < snap.vwap
            else -> false
        }
        if (vwapOk) {
            score += 12
            if (narrative) reasons += "سمت درست VWAP جلسه"
        } else if (direction != null && narrative) blockers += "سمت اشتباه VWAP جلسه"
        if (narrative) confluence += ConfluenceItem("VWAP جلسه", vwapOk, fmt(snap.vwap))

        val rsiOk = when (direction) {
            SignalAction.BUY -> snap.rsi in 52.0..72.0
            SignalAction.SELL -> snap.rsi in 28.0..48.0
            else -> false
        }
        if (rsiOk) {
            score += 10
            if (narrative) reasons += "RSI7 در ناحیه سالم (${fmt(snap.rsi)})"
        } else if (direction != null && narrative) blockers += "RSI7 اشباع یا بی‌مومنتوم (${fmt(snap.rsi)})"
        if (narrative) confluence += ConfluenceItem("RSI7", rsiOk, fmt(snap.rsi))

        val hist = snap.macdHist ?: 0.0
        val momentumOk = (if (long) hist > 0 else hist < 0) && (snap.adx ?: 0.0) >= 20.0
        if (momentumOk) {
            score += 5
            if (narrative) reasons += "مومنتوم MACD و ADX تایید می‌کند"
        } else if (direction != null && narrative) blockers += "مومنتوم کافی نیست (MACD/ADX)"
        if (narrative) {
            confluence += ConfluenceItem(
                "مومنتوم MACD/ADX",
                momentumOk,
                "hist ${snap.macdHist?.let { fmt(it) } ?: "—"} / ADX ${snap.adx?.let { fmt(it) } ?: "—"}",
            )
            confluence += ConfluenceItem(
                "نوسان ATR در محدوده",
                !snap.atrShock,
                "ATR ${fmt(snap.atr)}" + if (snap.atrShock) " — شوک نوسان" else "",
            )
        }
        if (snap.atrShock) {
            if (narrative) blockers += "شوک نوسان: ATR بیش از ۲.۵ برابر میانه — ورود متوقف"
        }

        var spreadBlocked = false
        if (spread != null) {
            spreadBlocked = spread > 0.60
            if (spreadBlocked && narrative) blockers += "اسپرد غیرعادی (${fmt(spread)}) — اجرا متوقف"
            if (narrative) confluence += ConfluenceItem("اسپرد", !spreadBlocked, fmt(spread))
        }
        if (narrative) {
            snap.relVolume?.let { rv -> confluence += ConfluenceItem("حجم نسبی ۳۰ کندل", rv >= 0.6, "${fmt(rv)}×") }
        }

        if (direction == null) score = min(score, 69.0)
        score = score.coerceIn(0.0, 100.0)
        val conf = score

        val actionable = direction != null && conf >= minThreshold && !snap.atrShock && !spreadBlocked
        if (!actionable) {
            if (narrative && conf < minThreshold && direction != null) {
                blockers += "امتیاز همگرایی ${fmt(conf)} کمتر از آستانه ${fmt(minThreshold)}"
            }
            return Signal(
                action = SignalAction.NO_TRADE,
                confidence = conf,
                reasons = reasons,
                blockers = blockers,
                confluence = confluence,
                interval = interval,
                barTime = snap.barTime,
            )
        }

        val entry = snap.price
        val structure = if (long) {
            bars.subList(max(0, snap.index - 5), snap.index + 1).minOf { it.low } - 0.15 * snap.atr
        } else {
            bars.subList(max(0, snap.index - 5), snap.index + 1).maxOf { it.high } + 0.15 * snap.atr
        }
        val rawDistance = abs(entry - structure)
        val stopDistance = rawDistance.coerceIn(0.90 * snap.atr, 1.40 * snap.atr)
        val stopLoss = if (long) entry - stopDistance else entry + stopDistance
        val reward = if (score >= 85.0) 2.0 else 1.8
        val takeProfit = if (long) entry + stopDistance * reward else entry - stopDistance * reward

        return Signal(
            action = direction,
            confidence = conf,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            riskReward = reward,
            reasons = reasons,
            blockers = blockers,
            confluence = confluence,
            interval = interval,
            barTime = snap.barTime,
        )
    }

    /** Convenience for the live path: evaluate the newest closed bar. */
    fun evaluate(candles: List<Candle>, interval: Interval, threshold: Double = 72.0, spread: Double? = null): Signal {
        val s = series(candles, interval)
        return decide(s, s.lastIndex, threshold, spread)
    }

    private fun fmt(value: Double): String = String.format("%.2f", value)
}
