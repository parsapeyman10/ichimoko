package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * «آزمایشگاه استاپ» — فاز ۰ از نقشهٔ راه کالیبراسیون (`docs/CALIBRATION_PHASES_FA.md`).
 *
 * ## این ماژول چه کاری می‌کند و چه کاری نمی‌کند
 * معاملات **واقعیِ** ژورنال را برمی‌دارد و می‌پرسد: «اگر حد ضرر این معامله به‌جای قاعدهٔ فعلی،
 * پشت فرکتال ۵ کندلی یا پشت کیجون + فرکتال (و با بافر متفاوت) گذاشته می‌شد، چه می‌شد؟»
 *
 * - **هیچ رفتاری را تغییر نمی‌دهد.** نه ژورنال، نه موتور استراتژی، نه قرارداد ریسک را دست نمی‌زند.
 * - هیچ کندلی از آیندهٔ لحظهٔ ورود در محاسبهٔ استاپ استفاده نمی‌شود (تست اختصاصی دارد).
 * - بستن معامله در شبیه‌سازی، **محافظه‌کارانه** است: اگر در یک کندل هم حد ضرر و هم حد سود لمس شود،
 *   حد ضرر برنده است.
 * - سود/زیان بر اساس همان قرارداد پول برنامه گزارش می‌شود: ضرر کامل = [RISK_USD] دلار و
 *   سود هدف = `planR × RISK_USD`؛ فاصلهٔ هدف همیشه `planR × فاصلهٔ استاپ` است (انتخاب کاربر:
 *   «همان مضرب R»، نه هدف ساختاری).
 *
 * ## محدودیت صادقانه
 * نمونهٔ آماری همان معاملاتی است که برنامه **واقعاً** گرفته است؛ پس دربارهٔ «معاملاتی که با قاعدهٔ
 * جدید اصلاً گرفته می‌شدند یا نه» چیزی نمی‌گوید. برای آن پرسش، بک‌تست کامل لازم است.
 */
object StopAnchorCalibration {

    /** قرارداد پول برنامه: ضرر خالص هر معاملهٔ استاپ‌خورده. */
    const val RISK_USD: Double = 5.0

    /** آستانهٔ «استاپ سریع» — همان ۱۵ دقیقه‌ای که در نکته‌های دستیار استفاده شده است. */
    const val FAST_STOP_WINDOW_MS: Long = 900_000L

    /** افق شبیه‌سازی: بعد از این تعداد کندل، معامله با کلوز کندل بسته می‌شود. */
    const val DEFAULT_MAX_BARS: Int = 96

    enum class AnchorMode(val id: String, val labelFa: String) {
        /** استاپ واقعیِ همان معامله (قاعدهٔ فعلی موتور) — مبنا برای مقایسه. */
        ENGINE_CURRENT("ENGINE", "استاپ فعلی موتور"),

        /** فقط پایین‌ترین/بالاترین کف ۵ کندل بسته‌شدهٔ قبل از ورود + بافر. */
        FIVE_BAR_FRACTAL("FRACTAL5", "فرکتال ۵ کندلی + بافر"),

        /** دورترِ کیجون و فرکتال ۵ کندلی + بافر. */
        KIJUN_PLUS_FRACTAL("KIJUN26_F5", "کیجون + فرکتال ۵ + بافر"),

        /** دورترِ سه سطح: کیجون، فرکتال ۵ کندلی، و شدوی C-2..C0 + بافر. */
        KIJUN_PLUS_FRACTAL_PLUS_WICK("KIJUN26_F5_WICK3", "کیجون + فرکتال ۵ + شدوی ۳ کندل + بافر"),
        ;

        companion object {
            fun fromId(id: String): AnchorMode? = entries.firstOrNull { it.id == id }

            /** حالت‌هایی که با تغییر بافر سنجیده می‌شوند. */
            val swept: List<AnchorMode> = listOf(FIVE_BAR_FRACTAL, KIJUN_PLUS_FRACTAL, KIJUN_PLUS_FRACTAL_PLUS_WICK)
        }
    }

    /** یک معاملهٔ واقعیِ بسته‌شده که برای بازپخش انتخاب می‌شود. */
    data class Entry(
        val tradeId: String,
        val tradeNumber: Int,
        val strategyId: String,
        val strategyTitleFa: String,
        val symbol: String,
        val interval: Interval,
        val entryTime: Long,
        val entryPrice: Double,
        val isBuy: Boolean,
        val plannedRiskReward: Double,
        val engineStopLoss: Double,
        val actualNetPnlUsd: Double,
        val actualExitReasonFa: String,
    )

    enum class OutcomeKind(val labelFa: String) {
        STOPPED("حد ضرر"),
        TARGET("حد سود"),
        TIMEOUT("بستن زمانی"),
        NO_DATA("بدون داده"),
    }

    data class Outcome(
        val kind: OutcomeKind,
        val rMultiple: Double,
        val minutesToExit: Long,
    )

    /** یک سطر گزارش: (استراتژی × قاعدهٔ استاپ × بافر). */
    data class Row(
        val strategyId: String,
        val strategyTitleFa: String,
        val anchor: AnchorMode,
        val bufferAtr: Double,
        val samples: Int,
        val stopped: Int,
        val targets: Int,
        val timeouts: Int,
        val winRatePct: Double,
        val expectancyR: Double,
        val expectancyUsd: Double,
        val profitFactor: Double,
        val fastStopPct: Double,
        val avgStopDistanceAtr: Double,
        /** معامله‌هایی که با قاعدهٔ فعلی استاپ می‌خوردند ولی با این قاعده زنده ماندند. */
        val rescuedFromStop: Int,
        /** معامله‌هایی که با قاعدهٔ فعلی به هدف می‌رسیدند ولی با این قاعده استاپ می‌خورند. */
        val lostTargets: Int,
    ) {
        /** کمتر از ۲۰ نمونه، شاهد ضعیف است؛ گزارش هم همین را می‌نویسد. */
        val sampleEnough: Boolean get() = samples >= 20
    }

    data class Report(
        val rows: List<Row>,
        val entriesUsed: Int,
        val entriesSkipped: Int,
        val days: Int,
        val buffers: List<Double>,
        val generatedAt: Long,
    ) {
        val hasData: Boolean get() = rows.isNotEmpty()
        fun rowsFor(strategyId: String): List<Row> = rows.filter { it.strategyId == strategyId }
        val strategyIds: List<String> get() = rows.map { it.strategyId }.distinct()
    }

    /** دورهٔ کیجون بر اساس تایم‌فریم — تصمیم کاربر: ۲۶ برای ۵/۱۵ دقیقه و ۳۰ برای ۱/۴ ساعته. */
    fun kijunPeriod(interval: Interval): Int = when (interval) {
        Interval.M5, Interval.M15 -> 26
        else -> 30
    }

    /** ATR سادهٔ ۱۴ کندل (میانگین دامنهٔ واقعی) از کندل‌های بسته‌شده. */
    fun atr(bars: List<Candle>, period: Int = 14): Double? {
        if (bars.size < period + 1) return null
        val window = bars.takeLast(period + 1)
        var sum = 0.0
        for (i in 1 until window.size) {
            val high = window[i].high
            val low = window[i].low
            val prevClose = window[i - 1].close
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            if (!tr.isFinite()) return null
            sum += tr
        }
        val value = sum / period
        return if (value.isFinite() && value > 0.0) value else null
    }

    /**
     * کندل‌هایی که **کامل بسته شده‌اند و پیش از لحظهٔ ورود** هستند.
     * هیچ کندلی که بعد از ورود بسته می‌شود در این لیست نیست — پایهٔ صحت کل این ماژول.
     */
    fun closedBeforeEntry(bars: List<Candle>, interval: Interval, entryTime: Long): List<Candle> {
        val duration = interval.millis
        return bars.asSequence()
            .filter { it.time < entryTime && it.time + duration <= entryTime }
            .sortedBy { it.time }
            .toList()
    }

    /**
     * فاصلهٔ استاپ پیشنهادی (به دلار قیمت) برای یک معامله بر اساس قاعدهٔ انتخابی.
     * `null` یعنی داده کافی نبود و هیچ عددی ساخته نمی‌شود.
     */
    fun anchorStopDistance(
        entry: Entry,
        bars: List<Candle>,
        mode: AnchorMode,
        bufferAtr: Double,
    ): Double? {
        if (mode == AnchorMode.ENGINE_CURRENT) {
            val distance = abs(entry.entryPrice - entry.engineStopLoss)
            return distance.takeIf { it.isFinite() && it > 0.0 }
        }
        val closed = closedBeforeEntry(bars, entry.interval, entry.entryTime)
        val kijunPeriodValue = kijunPeriod(entry.interval)
        if (closed.size < max(kijunPeriodValue, 20)) return null
        val atrValue = atr(closed) ?: return null
        val recent = closed.takeLast(kijunPeriodValue)
        val kijun = (recent.maxOf { it.high } + recent.minOf { it.low }) / 2.0
        val fiveBars = closed.takeLast(5)
        val fractal5 = if (entry.isBuy) fiveBars.minOf { it.low } else fiveBars.maxOf { it.high }
        val threeBars = closed.takeLast(3)
        val wick3 = if (entry.isBuy) threeBars.minOf { it.low } else threeBars.maxOf { it.high }

        val base = when (mode) {
            AnchorMode.FIVE_BAR_FRACTAL -> fractal5
            AnchorMode.KIJUN_PLUS_FRACTAL -> if (entry.isBuy) min(kijun, fractal5) else max(kijun, fractal5)
            AnchorMode.KIJUN_PLUS_FRACTAL_PLUS_WICK ->
                if (entry.isBuy) min(min(kijun, fractal5), wick3) else max(max(kijun, fractal5), wick3)

            AnchorMode.ENGINE_CURRENT -> return null
        }
        val buffer = bufferAtr.coerceIn(0.0, 2.0) * atrValue
        val stop = if (entry.isBuy) base - buffer else base + buffer
        val distance = if (entry.isBuy) entry.entryPrice - stop else stop - entry.entryPrice
        return distance.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * شبیه‌سازی یک معامله با استاپ و هدف مشخص.
     *
     * شروع پرش از اولین کندلی است که **بعد از** لحظهٔ ورود بسته می‌شود؛ کندل ورود (که در لحظهٔ
     * ورود بسته شده) دوباره شبیه‌سازی نمی‌شود.
     */
    fun simulate(
        entry: Entry,
        bars: List<Candle>,
        stopDistance: Double,
        planR: Double,
        maxBars: Int = DEFAULT_MAX_BARS,
    ): Outcome {
        if (!stopDistance.isFinite() || stopDistance <= 0.0) {
            return Outcome(OutcomeKind.NO_DATA, 0.0, 0L)
        }
        val rMultiplePlanned = planR.coerceIn(0.5, 5.0)
        val targetDistance = stopDistance * rMultiplePlanned
        val stopPrice = if (entry.isBuy) entry.entryPrice - stopDistance else entry.entryPrice + stopDistance
        val targetPrice = if (entry.isBuy) entry.entryPrice + targetDistance else entry.entryPrice - targetDistance
        val duration = entry.interval.millis
        val forward = bars.asSequence()
            .filter { it.time + duration > entry.entryTime }
            .sortedBy { it.time }
            .toList()
        if (forward.isEmpty()) return Outcome(OutcomeKind.NO_DATA, 0.0, 0L)

        var lastClose = entry.entryPrice
        for ((index, bar) in forward.withIndex()) {
            val minutes = ((bar.time + duration) - entry.entryTime) / 60_000L
            val stopped = if (entry.isBuy) bar.low <= stopPrice else bar.high >= stopPrice
            val targeted = if (entry.isBuy) bar.high >= targetPrice else bar.low <= targetPrice
            // محافظه‌کاری: ابهام یک کندل به سود «حد ضرر» حل می‌شود.
            if (stopped) return Outcome(OutcomeKind.STOPPED, -1.0, minutes)
            if (targeted) return Outcome(OutcomeKind.TARGET, rMultiplePlanned, minutes)
            lastClose = bar.close
            if (index + 1 >= maxBars) {
                val r = if (entry.isBuy) {
                    (lastClose - entry.entryPrice) / stopDistance
                } else {
                    (entry.entryPrice - lastClose) / stopDistance
                }
                return Outcome(OutcomeKind.TIMEOUT, r, minutes)
            }
        }
        val last = forward.last()
        val minutes = ((last.time + duration) - entry.entryTime) / 60_000L
        val r = if (entry.isBuy) {
            (lastClose - entry.entryPrice) / stopDistance
        } else {
            (entry.entryPrice - lastClose) / stopDistance
        }
        return Outcome(OutcomeKind.TIMEOUT, r, minutes)
    }

    /** یک سطر تجمیعی برای (استراتژی، قاعده، بافر) از نتایج یک‌به‌یک معامله‌ها. */
    private class Accumulator(
        val strategyId: String,
        val strategyTitleFa: String,
        val anchor: AnchorMode,
        val bufferAtr: Double,
    ) {
        var samples = 0
        var wins = 0
        var stopped = 0
        var targets = 0
        var timeouts = 0
        var fastStops = 0
        var rescued = 0
        var lostTargets = 0
        var sumR = 0.0
        var grossWin = 0.0
        var grossLoss = 0.0
        var sumStopAtr = 0.0

        fun add(
            outcome: Outcome,
            stopDistance: Double,
            atrValue: Double,
            baseline: Outcome?,
        ) {
            samples++
            sumR += outcome.rMultiple
            if (outcome.rMultiple > 0.0) {
                wins++
                grossWin += outcome.rMultiple
            } else {
                grossLoss += -outcome.rMultiple
            }
            when (outcome.kind) {
                OutcomeKind.STOPPED -> {
                    stopped++
                    if (outcome.minutesToExit <= FAST_STOP_WINDOW_MS / 60_000L) fastStops++
                }

                OutcomeKind.TARGET -> targets++
                OutcomeKind.TIMEOUT -> timeouts++
                OutcomeKind.NO_DATA -> Unit
            }
            if (atrValue > 0.0) sumStopAtr += stopDistance / atrValue
            if (baseline != null) {
                if (baseline.kind == OutcomeKind.STOPPED && outcome.kind != OutcomeKind.STOPPED) rescued++
                if (baseline.kind == OutcomeKind.TARGET && outcome.kind == OutcomeKind.STOPPED) lostTargets++
            }
        }

        fun toRow(): Row {
            val winRate = if (samples > 0) wins * 100.0 / samples else 0.0
            return Row(
                strategyId = strategyId,
                strategyTitleFa = strategyTitleFa,
                anchor = anchor,
                bufferAtr = bufferAtr,
                samples = samples,
                stopped = stopped,
                targets = targets,
                timeouts = timeouts,
                winRatePct = round(winRate * 10.0) / 10.0,
                expectancyR = if (samples > 0) round(sumR / samples * 1000.0) / 1000.0 else 0.0,
                expectancyUsd = if (samples > 0) round(sumR / samples * RISK_USD * 100.0) / 100.0 else 0.0,
                profitFactor = when {
                    grossLoss <= 1e-9 -> if (grossWin > 0.0) 99.0 else 0.0
                    else -> round(grossWin / grossLoss * 100.0) / 100.0
                },
                fastStopPct = if (samples > 0) round(fastStops * 1000.0 / samples) / 10.0 else 0.0,
                avgStopDistanceAtr = if (samples > 0) round(sumStopAtr / samples * 100.0) / 100.0 else 0.0,
                rescuedFromStop = rescued,
                lostTargets = lostTargets,
            )
        }

    }

    /**
     * اجرای کامل آزمایشگاه: هر معاملهٔ ورودی با هر (قاعده × بافر) شبیه‌سازی می‌شود.
     *
     * @param candlesBySymbol تأمین‌کنندهٔ کندل‌های تاریخی هر (نماد، تایم‌فریم).
     */
    fun sweep(
        entries: List<Entry>,
        candlesBySymbol: (String, Interval) -> List<Candle>,
        buffers: List<Double> = listOf(0.26, 0.32, 0.38, 0.45, 0.52),
        modes: List<AnchorMode> = AnchorMode.swept,
        maxBars: Int = DEFAULT_MAX_BARS,
        nowMillis: Long = System.currentTimeMillis(),
        days: Int = 30,
    ): Report {
        val accumulators = LinkedHashMap<String, Accumulator>()
        fun acc(strategyId: String, titleFa: String, anchor: AnchorMode, buffer: Double): Accumulator =
            accumulators.getOrPut("$strategyId|${anchor.id}|$buffer") {
                Accumulator(strategyId, titleFa, anchor, buffer)
            }

        var used = 0
        var skipped = 0
        val candleCache = HashMap<String, List<Candle>>()

        for (entry in entries) {
            val key = "${entry.symbol}|${entry.interval.label}"
            val bars = candleCache.getOrPut(key) { candlesBySymbol(entry.symbol, entry.interval) }
            if (bars.size < 30) {
                skipped++
                continue
            }
            val planR = if (entry.plannedRiskReward.isFinite() && entry.plannedRiskReward > 0.0) {
                entry.plannedRiskReward
            } else {
                2.0
            }
            val engineDistance = abs(entry.entryPrice - entry.engineStopLoss)
            if (!engineDistance.isFinite() || engineDistance <= 0.0) {
                skipped++
                continue
            }
            val baseline = simulate(entry, bars, engineDistance, planR, maxBars)
            if (baseline.kind == OutcomeKind.NO_DATA) {
                skipped++
                continue
            }
            used++
            val closed = closedBeforeEntry(bars, entry.interval, entry.entryTime)
            val atrValue = atr(closed) ?: 0.0

            // مبنا: استاپ فعلی موتور (بافر معنایی ندارد؛ فقط برای مقایسه ثبت می‌شود).
            acc(entry.strategyId, entry.strategyTitleFa, AnchorMode.ENGINE_CURRENT, 0.0)
                .add(baseline, engineDistance, atrValue, baseline)

            for (buffer in buffers) {
                for (mode in modes) {
                    val distance = anchorStopDistance(entry, bars, mode, buffer) ?: continue
                    val outcome = simulate(entry, bars, distance, planR, maxBars)
                    if (outcome.kind == OutcomeKind.NO_DATA) continue
                    acc(entry.strategyId, entry.strategyTitleFa, mode, buffer)
                        .add(outcome, distance, atrValue, baseline)
                }
            }
        }

        val rows = accumulators.values
            .map { it.toRow() }
            .sortedWith(compareBy({ it.strategyTitleFa }, { it.anchor.ordinal }, { it.bufferAtr }))
        return Report(
            rows = rows,
            entriesUsed = used,
            entriesSkipped = skipped,
            days = days,
            buffers = buffers,
            generatedAt = nowMillis,
        )
    }
}
