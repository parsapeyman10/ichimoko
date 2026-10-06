package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.SignalProfile
import com.aurum.edge.core.StrategyKind
import com.aurum.edge.core.ConfluenceStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
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

    private val newYorkZone: ZoneId = ZoneId.of("America/New_York")

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
        val spanBFlatBars: Int,
        val spanBFlatValue: Double?,
        val rangeBreakoutUp: Boolean,
        val rangeBreakoutDown: Boolean,
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

        val rawSpanB = series.ichimoku.senkouB.getOrNull(i)
        val flatTolerance = max(atrValue * 0.02, bars[i].close * 0.00005)
        var spanBFlatBars = 0
        if (rawSpanB != null) {
            var j = i
            while (j >= 0 && spanBFlatBars < 40) {
                val b = series.ichimoku.senkouB.getOrNull(j) ?: break
                if (abs(b - rawSpanB) > flatTolerance) break
                spanBFlatBars++
                j--
            }
        }
        val rangeStart = max(0, i - 20)
        val recent = if (rangeStart < i) bars.subList(rangeStart, i) else emptyList()
        val rangeHigh = recent.maxOfOrNull { it.high } ?: bars[i].high
        val rangeLow = recent.minOfOrNull { it.low } ?: bars[i].low
        val rangeBreakoutUp = bars[i].close > rangeHigh + 0.08 * atrValue
        val rangeBreakoutDown = bars[i].close < rangeLow - 0.08 * atrValue

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
            spanBFlatBars = spanBFlatBars,
            spanBFlatValue = rawSpanB,
            rangeBreakoutUp = rangeBreakoutUp,
            rangeBreakoutDown = rangeBreakoutDown,
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
        profile: SignalProfile = SignalProfile.BASE,
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

        val crossDirection = when {
            snap.bullCross -> SignalAction.BUY
            snap.bearCross -> SignalAction.SELL
            else -> null
        }
        val flatLong = snap.spanBFlatBars >= 8 && snap.spanBFlatValue != null &&
            snap.price > snap.spanBFlatValue + 0.10 * snap.atr &&
            (snap.rangeBreakoutUp || (snap.adx ?: 0.0) >= 20.0)
        val flatShort = snap.spanBFlatBars >= 8 && snap.spanBFlatValue != null &&
            snap.price < snap.spanBFlatValue - 0.10 * snap.atr &&
            (snap.rangeBreakoutDown || (snap.adx ?: 0.0) >= 20.0)
        val flatDirection = when {
            flatLong -> SignalAction.BUY
            flatShort -> SignalAction.SELL
            else -> null
        }
        // A profile option is an additional AND-gate, not an alternate entry rule. Direction must
        // always come from the base Tenkan/Kijun cross so enabled options cannot bypass core rules.
        val direction = crossDirection
        val long = direction == SignalAction.BUY
        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        val confluence = mutableListOf<ConfluenceItem>()
        val minThreshold = threshold.coerceAtLeast(72.0)
        fun conditionStatus(ok: Boolean, likely: Boolean = false): ConfluenceStatus = when {
            ok -> ConfluenceStatus.CONFIRMED
            likely -> ConfluenceStatus.UNKNOWN
            else -> ConfluenceStatus.CONFLICT
        }
        fun near(value: Double, target: Double, atrMultiple: Double): Boolean =
            abs(value - target) <= snap.atr * atrMultiple

        var score = 20.0
        if (direction != null) {
            if (narrative) reasons += when {
                crossDirection != null -> if (long) "کراس تازه صعودی تنکان/کیجون" else "کراس تازه نزولی تنکان/کیجون"
                else -> "جهت سیگنال تأیید شد"
            }
        } else if (narrative) {
            blockers += if (profile.flatSpanB)
                "نه کراس تنکان/کیجون داریم، نه شکست معتبر از تختی SpanB52"
            else "کراس تازه تنکان/کیجون شکل نگرفته — ورود ممنوع"
        }
        if (narrative) {
            val crossOk = crossDirection != null
            val crossLikely = !crossOk && near(snap.tenkan, snap.kijun, 0.18)
            confluence += ConfluenceItem(
                "۱ · کراس تنکان/کیجون",
                crossOk,
                "T ${fmt(snap.tenkan)} / K ${fmt(snap.kijun)}",
                status = conditionStatus(crossOk, crossLikely),
                scorePercent = if (crossOk) 20 else null,
            )
        }

        var flatSpanBOptionOk = !profile.flatSpanB
        if (profile.flatSpanB && direction != null) {
            flatSpanBOptionOk = flatDirection == direction
            if (flatSpanBOptionOk) {
                score += 18
                if (narrative) reasons += "آپشن SpanB52 هم‌جهت با سیگنال پایه تأیید شد"
            } else if (narrative) {
                blockers += "آپشن SpanB52 فعال است و باید هم‌جهت با کراس پایه تأیید شود"
            }
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
        if (narrative) {
            val cloudTarget = when (direction) {
                SignalAction.BUY -> snap.cloudTop + clearance
                SignalAction.SELL -> snap.cloudBottom - clearance
                else -> null
            }
            val cloudLikely = !cloudOk && cloudTarget != null && near(snap.price, cloudTarget, 0.20)
            confluence += ConfluenceItem(
                "۲ · قیمت خارج ابر",
                cloudOk,
                "${fmt(snap.cloudTop)} — ${fmt(snap.cloudBottom)}",
                status = conditionStatus(cloudOk, cloudLikely),
                scorePercent = if (cloudOk) 18 else null,
            )
        }

        val spanOk = when (direction) {
            SignalAction.BUY -> snap.spanA > snap.spanB
            SignalAction.SELL -> snap.spanA < snap.spanB
            else -> false
        }
        if (spanOk) {
            score += 10
            if (narrative) reasons += "ابر آینده هم‌جهت"
        } else if (direction != null && narrative) blockers += "ابر آینده مخالف جهت معامله"
        if (narrative) confluence += ConfluenceItem(
            "۳ · هم‌جهتی Senkou A/B",
            spanOk,
            "A ${fmt(snap.spanA)} / B ${fmt(snap.spanB)}",
            status = conditionStatus(spanOk, direction != null && !spanOk && near(snap.spanA, snap.spanB, 0.15)),
            scorePercent = if (spanOk) 10 else null,
        )

        val chikouOk = !profile.chikouConfirmation || when (direction) {
            SignalAction.BUY -> snap.chikouBuyClear
            SignalAction.SELL -> snap.chikouSellClear
            else -> false
        }
        if (!profile.chikouConfirmation) {
            if (narrative) reasons += "تایید Chikou در این پروفایل خاموش است"
        } else if (chikouOk) {
            score += 10
            if (narrative) reasons += "تایید چیکو نسبت به ساختار ${series.setting.kijun} کندل قبل"
        } else if (direction != null && narrative) blockers += "چیکو تایید نمی‌کند — ساختار قبلی نقض می‌شود"
        if (narrative) {
            val chikouIndex = snap.index - series.setting.kijun
            val chikouLevel = if (direction != null && chikouIndex >= 0) {
                if (long) bars[chikouIndex].high else bars[chikouIndex].low
            } else null
            val chikouLikely = profile.chikouConfirmation && !chikouOk && chikouLevel != null &&
                near(snap.price, chikouLevel, 0.20)
            confluence += ConfluenceItem(
                "۴ · تایید Chikou",
                chikouOk,
                if (profile.chikouConfirmation) {
                    if (long) "close بالای high قبلی" else "close زیر low قبلی"
                } else "خاموش در پروفایل",
                status = if (profile.chikouConfirmation) conditionStatus(chikouOk, chikouLikely)
                    else ConfluenceStatus.UNKNOWN,
                scorePercent = if (profile.chikouConfirmation && chikouOk) 10 else null,
            )
        }

        val emaOk = when (direction) {
            SignalAction.BUY -> snap.price > snap.ema200
            SignalAction.SELL -> snap.price < snap.ema200
            else -> false
        }
        if (emaOk) {
            score += 15
            if (narrative) reasons += "هم‌جهت با EMA200"
        } else if (direction != null && narrative) blockers += "خلاف روند EMA200"
        if (narrative) confluence += ConfluenceItem(
            "۵ · EMA200",
            emaOk,
            fmt(snap.ema200),
            status = conditionStatus(emaOk, direction != null && !emaOk && near(snap.price, snap.ema200, 0.15)),
            scorePercent = if (emaOk) 15 else null,
        )

        val vwapOk = when (direction) {
            SignalAction.BUY -> snap.price > snap.vwap
            SignalAction.SELL -> snap.price < snap.vwap
            else -> false
        }
        if (vwapOk) {
            score += 12
            if (narrative) reasons += "سمت درست VWAP جلسه"
        } else if (direction != null && narrative) blockers += "سمت اشتباه VWAP جلسه"
        if (narrative) confluence += ConfluenceItem(
            "۶ · VWAP جلسه",
            vwapOk,
            fmt(snap.vwap),
            status = conditionStatus(vwapOk, direction != null && !vwapOk && near(snap.price, snap.vwap, 0.12)),
            scorePercent = if (vwapOk) 12 else null,
        )

        val rsiOk = when (direction) {
            SignalAction.BUY -> snap.rsi in 52.0..72.0
            SignalAction.SELL -> snap.rsi in 28.0..48.0
            else -> false
        }
        if (rsiOk) {
            score += 10
            if (narrative) reasons += "RSI7 در ناحیه سالم (${fmt(snap.rsi)})"
        } else if (direction != null && narrative) blockers += "RSI7 اشباع یا بی‌مومنتوم (${fmt(snap.rsi)})"
        if (narrative) {
            val rsiLikely = when (direction) {
                SignalAction.BUY -> snap.rsi in 48.0..76.0
                SignalAction.SELL -> snap.rsi in 24.0..52.0
                else -> false
            }
            confluence += ConfluenceItem(
                "۷ · RSI7",
                rsiOk,
                fmt(snap.rsi),
                status = conditionStatus(rsiOk, !rsiOk && rsiLikely),
                scorePercent = if (rsiOk) 10 else null,
            )
        }

        val hist = snap.macdHist ?: 0.0
        val momentumOk = (if (long) hist > 0 else hist < 0) && (snap.adx ?: 0.0) >= 20.0
        if (momentumOk) {
            score += 5
            if (narrative) reasons += "مومنتوم MACD و ADX تایید می‌کند"
        } else if (direction != null && narrative) blockers += "مومنتوم کافی نیست (MACD/ADX)"
        if (narrative) {
            val histLikely = snap.macdHist?.let { if (long) it > -0.03 * snap.atr else it < 0.03 * snap.atr } == true
            val adxLikely = (snap.adx ?: 0.0) >= 16.0
            confluence += ConfluenceItem(
                "۸ · مومنتوم MACD/ADX",
                momentumOk,
                "hist ${snap.macdHist?.let { fmt(it) } ?: "—"} / ADX ${snap.adx?.let { fmt(it) } ?: "—"}",
                status = conditionStatus(momentumOk, direction != null && !momentumOk && histLikely && adxLikely),
                scorePercent = if (momentumOk) 5 else null,
            )
            confluence += ConfluenceItem(
                "کنترل اضافه · نوسان ATR در محدوده",
                !snap.atrShock,
                "ATR ${fmt(snap.atr)}" + if (snap.atrShock) " — شوک نوسان" else "",
            )
            val flatOk = flatDirection == direction && direction != null
            confluence += ConfluenceItem(
                "آپشن افزوده · تختی SpanB52",
                flatOk,
                "${snap.spanBFlatBars} کندل تخت · B ${snap.spanBFlatValue?.let { fmt(it) } ?: "—"} · شکست ${if (snap.rangeBreakoutUp) "بالا" else if (snap.rangeBreakoutDown) "پایین" else "ندارد"}",
                status = if (profile.flatSpanB) conditionStatus(flatOk) else ConfluenceStatus.UNKNOWN,
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
        val volumeOk = snap.relVolume?.let { it >= 0.6 }
        if (narrative) {
            confluence += ConfluenceItem(
                "حجم نسبی ۳۰ کندل",
                volumeOk != false,
                snap.relVolume?.let { "${fmt(it)}×" } ?: "—",
                status = when (volumeOk) {
                    true -> ConfluenceStatus.CONFIRMED
                    false -> ConfluenceStatus.CONFLICT
                    null -> ConfluenceStatus.UNKNOWN
                },
            )
        }
        if (profile.momentumVolume && direction != null) {
            if (!momentumOk) blockers += "پروفایل مومنتوم/حجم: MACD/ADX باید هم‌جهت و قوی باشد"
            if (volumeOk == false) blockers += "پروفایل مومنتوم/حجم: حجم نسبی کمتر از حداقل است"
            if (momentumOk && volumeOk != false) score += 8
        }

        var additiveFiltersOk = (!profile.momentumVolume || (momentumOk && volumeOk != false)) && flatSpanBOptionOk
        fun applyGuard(name: String, guard: GuardResult) {
            if (narrative) confluence += ConfluenceItem(name, guard.ok, guard.detail)
            if (direction != null && !guard.ok) {
                additiveFiltersOk = false
                if (narrative) blockers += guard.blocker
            }
        }
        if (direction != null && profile.rangeChopFilter) {
            applyGuard("فیلتر ضد رنج/Chop", rangeChopGuard(series, snap))
        }
        if (direction != null && profile.higherTimeframeFilter) {
            applyGuard("تأیید تایم بالاتر", higherTimeframeGuard(series, snap, direction))
        }
        if (direction != null && profile.fakeBreakoutFilter) {
            applyGuard("ضد فیک‌بریک‌اوت", fakeBreakoutGuard(series, snap, direction))
        }
        if (direction != null && profile.dynamicSpreadFilter) {
            applyGuard("اسپرد/نقدشوندگی پویا", dynamicSpreadGuard(snap, spread))
        }
        if (direction != null && profile.riskyTimingFilter) {
            applyGuard("زمان خطرناک", riskyTimingGuard(snap.barTime))
        }
        if (direction != null && profile.structureRiskFilter) {
            applyGuard("ریسک ساختار/حدضرر", structureRiskGuard(series, snap, direction))
        }
        if (direction != null && profile.cooldownFilter) {
            applyGuard("کول‌داون شکست", cooldownGuard(series, snap, direction))
        }

        if (direction == null) score = min(score, 69.0)
        score = score.coerceIn(0.0, 100.0)
        val conf = score

        val coreConditionsOk = direction != null && cloudOk && spanOk && chikouOk && emaOk &&
            vwapOk && rsiOk && momentumOk
        val actionable = direction != null && coreConditionsOk && conf >= minThreshold &&
            !snap.atrShock && !spreadBlocked && additiveFiltersOk
        if (!actionable) {
            if (narrative && direction != null && !coreConditionsOk) {
                blockers += "همهٔ ۸ شرط پایه باید هم‌زمان برقرار باشند"
            }
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
    fun evaluate(
        candles: List<Candle>,
        interval: Interval,
        threshold: Double = 72.0,
        spread: Double? = null,
        profile: SignalProfile = SignalProfile.BASE,
        strategy: StrategyKind = StrategyKind.SUPER_PLUS,
    ): Signal {
        if (strategy == StrategyKind.SUPER_PLUS && candles.count { it.closed } >= 75) {
            return evaluateSuperPlus(candles, interval)
        }
        val s = series(candles, interval)
        return decide(s, s.lastIndex, threshold, spread, profile = profile)
    }

    /**
     * Institutional M5 Ichimoku Engine (Engineering Grade):
     * Tenkan 8, Kijun 24, Senkou Span B 72, Displacement 24
     * + H1 Multi-Timeframe Kumo confirmation
     * + ADX(14) > 22.0
     * + Volume > SMA(Volume, 20)
     * + Session VWAP
     * + Kijun Elasticity (< 3.5 ATR)
     * + SL = Kijun +- 0.5 ATR, TP = 1.8 R:R
     */
    fun evaluateSuperPlus(
        candles: List<Candle>,
        interval: Interval,
    ): Signal {
        val bars = candles.filter { it.closed }
        if (bars.size < 75) return Signal(
            action = SignalAction.NO_TRADE,
            confidence = 0.0,
            interval = interval,
            barTime = bars.lastOrNull()?.time ?: 0L,
            blockers = listOf("کندل‌های کافی برای ایچیموکو نهادی M5 وجود ندارد"),
        )
        val lastIdx = bars.size - 1
        val lastBar = bars[lastIdx]
        val prevBar = bars[lastIdx - 1]
        val close = lastBar.close

        fun donchian(len: Int, idx: Int): Double {
            val sub = bars.subList(max(0, idx - len + 1), idx + 1)
            val l = sub.minOf { it.low }
            val h = sub.maxOf { it.high }
            return (l + h) / 2.0
        }

        val tenkan8 = donchian(8, lastIdx)
        val kijun24 = donchian(24, lastIdx)
        val spanA = (tenkan8 + kijun24) / 2.0
        val spanB72 = donchian(72, lastIdx)

        val prevTenkan = donchian(8, lastIdx - 1)
        val prevKijun = donchian(24, lastIdx - 1)

        val adxList = Indicators.adx(bars, 14)
        val currentAdx = adxList.lastOrNull() ?: 0.0
        val adxFilter = currentAdx > 22.0

        val atrList = Indicators.atr(bars, 14)
        val currentAtr = (atrList.lastOrNull() ?: (0.01 * close)).coerceAtLeast(1e-6)

        val volSma20 = if (bars.isNotEmpty()) bars.takeLast(20).map { it.volume }.average() else 0.0
        val volFilter = lastBar.volume >= volSma20

        val vwapList = Indicators.sessionVwap(bars)
        val currentVwap = vwapList.lastOrNull() ?: close
        val vwapBull = close > currentVwap
        val vwapBear = close < currentVwap

        val distKijun = abs(close - kijun24)
        val elasticityOk = distKijun < (currentAtr * 3.5)

        val priceAboveCloud = close > max(spanA, spanB72)
        val priceBelowCloud = close < min(spanA, spanB72)

        val longTrigger = (prevTenkan <= prevKijun && tenkan8 > kijun24) ||
            (tenkan8 > kijun24 && prevBar.close <= prevTenkan && close > tenkan8)
        val shortTrigger = (prevTenkan >= prevKijun && tenkan8 < kijun24) ||
            (tenkan8 < kijun24 && prevBar.close >= prevTenkan && close < tenkan8)

        // H1 Multi-Timeframe security
        val h1Bars = if (bars.size >= 60) MtfAnalyzer.resample(bars, Interval.H1, interval) else emptyList()
        val (h1Bullish, h1Bearish) = if (h1Bars.size >= 52) {
            val h1Last = h1Bars.last()
            val h1Idx = h1Bars.size - 1
            fun h1Donchian(len: Int): Double {
                val sub = h1Bars.subList(max(0, h1Idx - len + 1), h1Idx + 1)
                return (sub.minOf { it.low } + sub.maxOf { it.high }) / 2.0
            }
            val h1Tenkan = h1Donchian(9)
            val h1Kijun = h1Donchian(26)
            val h1SpanA = (h1Tenkan + h1Kijun) / 2.0
            val h1SpanB = h1Donchian(52)
            val bull = h1Last.close > max(h1SpanA, h1SpanB) && h1Tenkan > h1Kijun
            val bear = h1Last.close < min(h1SpanA, h1SpanB) && h1Tenkan < h1Kijun
            Pair(bull, bear)
        } else {
            Pair(true, true)
        }

        val longCondition = h1Bullish && adxFilter && vwapBull && elasticityOk && priceAboveCloud && longTrigger && volFilter
        val shortCondition = h1Bearish && adxFilter && vwapBear && elasticityOk && priceBelowCloud && shortTrigger && volFilter

        val confluence = listOf(
            ConfluenceItem(
                name = "روند کلان H1 (ایچیموکو)",
                ok = if (longCondition) h1Bullish else if (shortCondition) h1Bearish else (h1Bullish || h1Bearish),
                detail = if (h1Bullish) "H1 صعودی بالای ابر کومو" else if (h1Bearish) "H1 نزولی زیر ابر کومو" else "روند H1 خنثی/نامشخص",
                status = if (h1Bullish || h1Bearish) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
            ),
            ConfluenceItem(
                name = "قدرت روند ADX > 22",
                ok = adxFilter,
                detail = "ADX فعلی: ${String.format(java.util.Locale.US, "%.1f", currentAdx)} (حداقل ۲۲)",
                status = if (adxFilter) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
            ),
            ConfluenceItem(
                name = "جایگاه نسبت به VWAP",
                ok = if (longCondition) vwapBull else if (shortCondition) vwapBear else true,
                detail = "قیمت ${close} نسبت به VWAP ${String.format(java.util.Locale.US, "%.2f", currentVwap)}",
                status = if (vwapBull || vwapBear) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
            ),
            ConfluenceItem(
                name = "کشسانی کیجون (Elasticity < 3.5 ATR)",
                ok = elasticityOk,
                detail = "فاصله تا کیجون: ${String.format(java.util.Locale.US, "%.2f", distKijun)} (سقف مجاز: ${String.format(java.util.Locale.US, "%.2f", currentAtr * 3.5)})",
                status = if (elasticityOk) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
            ),
            ConfluenceItem(
                name = "موقعیت نسبت به ابر M5 (8/24/72)",
                ok = if (longCondition) priceAboveCloud else if (shortCondition) priceBelowCloud else (priceAboveCloud || priceBelowCloud),
                detail = if (priceAboveCloud) "بالای ابر SpanA/SpanB" else if (priceBelowCloud) "زیر ابر SpanA/SpanB" else "داخل ابر کومو",
                status = if (priceAboveCloud || priceBelowCloud) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
            ),
            ConfluenceItem(
                name = "حجم تاییدیه (Vol > SMA20)",
                ok = volFilter,
                detail = "حجم کندل: ${String.format(java.util.Locale.US, "%.1f", lastBar.volume)} / میانگین: ${String.format(java.util.Locale.US, "%.1f", volSma20)}",
                status = if (volFilter) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
            ),
            ConfluenceItem(
                name = "تریگر کراس تنکان/کیجون M5",
                ok = longTrigger || shortTrigger,
                detail = if (longTrigger) "کراس صعودی تنکان ۸ از کیجون ۲۴" else if (shortTrigger) "کراس نزولی تنکان ۸ از کیجون ۲۴" else "بدون تریگر کراس",
                status = if (longTrigger || shortTrigger) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
            ),
        )

        val blockers = mutableListOf<String>()
        if (!adxFilter) blockers.add("قدرت روند ADX (${String.format(java.util.Locale.US, "%.1f", currentAdx)}) زیر ۲۲ است")
        if (!elasticityOk) blockers.add("فاصله از کیجون زیاد است (خطر تله بازگشت به میانگین)")
        if (!volFilter) blockers.add("حجم معاملات از میانگین ۲۰ کندل کمتر است")
        if (!priceAboveCloud && !priceBelowCloud) blockers.add("قیمت داخل ابر کومو ۸/۲۴/۷۲ قرار دارد")

        if (longCondition) {
            val stopLoss = kijun24 - (currentAtr * 0.5)
            val risk = close - stopLoss
            val takeProfit = close + (risk * 1.8)
            return Signal(
                action = SignalAction.BUY,
                confidence = 92.0,
                entry = close,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
                riskReward = 1.8,
                reasons = listOf("تایید سوپر پلاس M5", "روند H1 صعودی", "ADX بالای ۲۲", "کراس تنکان ۸ / کیجون ۲۴"),
                blockers = emptyList(),
                confluence = confluence,
                interval = interval,
                barTime = lastBar.time,
            )
        } else if (shortCondition) {
            val stopLoss = kijun24 + (currentAtr * 0.5)
            val risk = stopLoss - close
            val takeProfit = close - (risk * 1.8)
            return Signal(
                action = SignalAction.SELL,
                confidence = 92.0,
                entry = close,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
                riskReward = 1.8,
                reasons = listOf("تایید سوپر پلاس M5", "روند H1 نزولی", "ADX بالای ۲۲", "کراس تنکان ۸ / کیجون ۲۴"),
                blockers = emptyList(),
                confluence = confluence,
                interval = interval,
                barTime = lastBar.time,
            )
        }

        return Signal(
            action = SignalAction.NO_TRADE,
            confidence = if (confluence.count { it.ok } >= 5) 60.0 else 30.0,
            entry = null,
            stopLoss = null,
            takeProfit = null,
            riskReward = 1.8,
            reasons = emptyList(),
            blockers = blockers.ifEmpty { listOf("شرایط ۷ گانهٔ ورود سوپر پلاس هنوز تکمیل نشده است") },
            confluence = confluence,
            interval = interval,
            barTime = lastBar.time,
        )
    }

    private data class GuardResult(val ok: Boolean, val detail: String, val blocker: String)

    private fun rangeChopGuard(series: Series, snap: Snapshot): GuardResult {
        val bars = series.bars
        val from = max(0, snap.index - 20)
        val window = bars.subList(from, snap.index + 1)
        val rangeWidth = (window.maxOfOrNull { it.high } ?: snap.price) -
            (window.minOfOrNull { it.low } ?: snap.price)
        val bbWidth = if (snap.bbUpper != null && snap.bbLower != null) snap.bbUpper - snap.bbLower else null
        val adx = snap.adx ?: 0.0
        val compressed = rangeWidth <= 2.2 * snap.atr || (bbWidth != null && bbWidth <= 1.35 * snap.atr)
        val noBreakout = !snap.rangeBreakoutUp && !snap.rangeBreakoutDown
        val choppy = adx < 18.0 && compressed && noBreakout
        val detail = "ADX ${fmt(adx)} · رنج۲۰ ${fmt(rangeWidth)} · ATR ${fmt(snap.atr)}"
        return GuardResult(!choppy, detail, "فیلتر رنج/Chop: بازار فشرده و بی‌روند است")
    }

    private fun higherTimeframeGuard(series: Series, snap: Snapshot, direction: SignalAction): GuardResult {
        val lookback = when (series.interval) {
            Interval.M1 -> 45
            Interval.M5 -> 36
            Interval.M15 -> 32
            else -> 24
        }
        val past = series.ema200.getOrNull(max(0, snap.index - lookback)) ?: return GuardResult(
            true, "برای شیب تایم بالاتر دادهٔ کافی نداریم؛ سخت‌گیری اعمال نشد", "")
        val slope = snap.ema200 - past
        val tolerance = 0.10 * snap.atr
        val ok = when (direction) {
            SignalAction.BUY -> snap.price >= snap.ema200 - 0.05 * snap.atr && slope >= -tolerance
            SignalAction.SELL -> snap.price <= snap.ema200 + 0.05 * snap.atr && slope <= tolerance
            else -> true
        }
        val detail = "EMA200 ${fmt(snap.ema200)} · شیب ${fmt(slope)} در $lookback کندل"
        return GuardResult(ok, detail, "فیلتر تایم بالاتر: سیگنال خلاف روند غالب/شیب EMA200 است")
    }

    private fun fakeBreakoutGuard(series: Series, snap: Snapshot, direction: SignalAction): GuardResult {
        val bars = series.bars
        val bar = bars[snap.index]
        val candleRange = max(bar.high - bar.low, snap.atr * 0.05)
        val upperWick = bar.high - max(bar.open, bar.close)
        val lowerWick = min(bar.open, bar.close) - bar.low
        val closeStrength = if (direction == SignalAction.BUY) {
            (bar.close - bar.low) / candleRange
        } else {
            (bar.high - bar.close) / candleRange
        }
        val wickOk = if (direction == SignalAction.BUY) upperWick <= candleRange * 0.45 else lowerWick <= candleRange * 0.45
        val from = max(0, snap.index - 8)
        val previous = if (from < snap.index) bars.subList(from, snap.index) else emptyList()
        val previousHigh = previous.maxOfOrNull { it.high } ?: bar.high
        val previousLow = previous.minOfOrNull { it.low } ?: bar.low
        val broke = if (direction == SignalAction.BUY) {
            bar.close > previousHigh + 0.03 * snap.atr || snap.rangeBreakoutUp
        } else {
            bar.close < previousLow - 0.03 * snap.atr || snap.rangeBreakoutDown
        }
        val retested = if (direction == SignalAction.BUY) {
            bar.low <= snap.kijun + 0.20 * snap.atr && bar.close > snap.kijun
        } else {
            bar.high >= snap.kijun - 0.20 * snap.atr && bar.close < snap.kijun
        }
        val ok = closeStrength >= 0.55 && wickOk && (broke || retested)
        val detail = "قدرت بسته‌شدن ${fmt(closeStrength * 100)}٪ · ویک ${if (wickOk) "سالم" else "مشکوک"} · ${if (broke) "شکست" else if (retested) "ری‌تست" else "بدون شکست/ری‌تست"}"
        return GuardResult(ok, detail, "فیلتر فیک‌بریک‌اوت: بسته‌شدن/ویک/شکست یا ری‌تست کافی نیست")
    }

    private fun dynamicSpreadGuard(snap: Snapshot, spread: Double?): GuardResult {
        if (spread == null) return GuardResult(
            true, "اسپرد لحظه‌ای/فرضی در دسترس نیست؛ عددی ساخته نشد", "")
        val ratio = spread / snap.atr
        val ok = spread <= 0.60 && ratio <= 0.15
        val detail = "اسپرد ${fmt(spread)} · ${fmt(ratio * 100)}٪ ATR"
        return GuardResult(ok, detail, "فیلتر اسپرد پویا: هزینهٔ ورود نسبت به ATR زیاد است")
    }

    private fun riskyTimingGuard(barTime: Long): GuardResult {
        val ny = Instant.ofEpochMilli(barTime).atZone(newYorkZone)
        val minute = ny.hour * 60 + ny.minute
        val detail = when {
            ny.dayOfWeek == DayOfWeek.FRIDAY && minute >= 15 * 60 + 30 ->
                "نزدیک بسته‌شدن جمعه نیویورک"
            ny.dayOfWeek == DayOfWeek.SUNDAY && minute in (18 * 60)..(19 * 60 + 10) ->
                "شروع بازار یکشنبه/طلا؛ نقدشوندگی می‌تواند نامنظم باشد"
            minute in (16 * 60 + 55)..(18 * 60 + 5) ->
                "پنجره رول‌اور نیویورک"
            minute in (8 * 60 + 25)..(8 * 60 + 45) || minute in (9 * 60 + 55)..(10 * 60 + 10) ->
                "پنجره معمول خبرهای سنگین آمریکا؛ تقویم واقعی خبر جدا لازم است"
            else -> null
        }
        return GuardResult(detail == null, detail ?: "زمان کندل در پنجره‌های پرریسک ثابت نیست", "فیلتر زمان خطرناک: ${detail ?: "پنجره پرریسک"}")
    }

    private fun structureRiskGuard(series: Series, snap: Snapshot, direction: SignalAction): GuardResult {
        val bars = series.bars
        val local = bars.subList(max(0, snap.index - 5), snap.index + 1)
        val structure = if (direction == SignalAction.BUY) {
            local.minOf { it.low } - 0.15 * snap.atr
        } else {
            local.maxOf { it.high } + 0.15 * snap.atr
        }
        val rawDistance = abs(snap.price - structure)
        val distanceOk = rawDistance in (0.45 * snap.atr)..(2.20 * snap.atr)
        val wider = bars.subList(max(0, snap.index - 20), snap.index + 1)
        val recentHigh = wider.dropLast(1).maxOfOrNull { it.high } ?: snap.price
        val recentLow = wider.dropLast(1).minOfOrNull { it.low } ?: snap.price
        val blockedByOppositeWall = if (direction == SignalAction.BUY) {
            recentHigh > snap.price && recentHigh - snap.price < 0.25 * snap.atr && !snap.rangeBreakoutUp
        } else {
            recentLow < snap.price && snap.price - recentLow < 0.25 * snap.atr && !snap.rangeBreakoutDown
        }
        val ok = distanceOk && !blockedByOppositeWall
        val detail = "فاصله ساختار ${fmt(rawDistance)} (${fmt(rawDistance / snap.atr)}×ATR)"
        return GuardResult(ok, detail, "فیلتر ساختار: حد ضرر/دیوار مقابل نسبت به ATR منطقی نیست")
    }

    private fun cooldownGuard(series: Series, snap: Snapshot, direction: SignalAction): GuardResult {
        val from = max(2, snap.index - max(4, barsValid(series.interval)))
        var flips = 0
        var previousSign = 0
        var oppositeCross = false
        for (j in from..snap.index) {
            val t = series.ichimoku.tenkan.getOrNull(j) ?: continue
            val k = series.ichimoku.kijun.getOrNull(j) ?: continue
            val sign = when {
                t > k -> 1
                t < k -> -1
                else -> 0
            }
            if (previousSign != 0 && sign != 0 && sign != previousSign) flips++
            if (j < snap.index && previousSign != 0 && sign != 0 && sign != previousSign) {
                if (direction == SignalAction.BUY && sign < 0) oppositeCross = true
                if (direction == SignalAction.SELL && sign > 0) oppositeCross = true
            }
            if (sign != 0) previousSign = sign
        }
        val ok = !oppositeCross && flips < 3
        val detail = "$flips چرخش تنکان/کیجون در ${snap.index - from + 1} کندل اخیر"
        return GuardResult(ok, detail, "کول‌داون: بازار اخیراً رفت‌وبرگشتی/کراس مخالف داشته است")
    }

    private fun fmt(value: Double): String = String.format("%.2f", value)
}
