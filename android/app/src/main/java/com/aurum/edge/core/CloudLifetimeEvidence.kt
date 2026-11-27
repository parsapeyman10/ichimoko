package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs

/** Observed cloud colour duration and *already projected* twist at the signal bar.
 * This is a descriptive hypothesis, not a veto, forecast or proof of exhaustion. */
object CloudLifetimeEvidence {
    const val LONG_BARS = 26
    const val NEAR_TWIST_BARS = 5
    const val MIN_SAMPLES = 30

    data class Snapshot(val ageBars: Int, val ageCensored: Boolean, val twistAheadBars: Int?, val barTime: Long) {
        val longNearTwist: Boolean get() = ageBars >= LONG_BARS && twistAheadBars != null && twistAheadBars in 1..NEAR_TWIST_BARS
        fun textFa(): String {
            val age = (if (ageCensored) "حداقل " else "") + ageBars + " کندل"
            val future = twistAheadBars?.let { "تغییر رنگ ترسیم‌شده در $it کندل جلوتر" }
                ?: "تغییر رنگ در بخش ترسیم‌شده دیده نشد؛ انتهای ابر نامعلوم"
            val warning = if (longNearTwist) "؛ هشدار بررسی ورود نزدیک انتهای ابر طولانی" else ""
            return "سن رنگ ابر روی تایم ورود: $age؛ $future$warning؛ کندل مرجع ممکن است با قیمت اجرا متفاوت باشد؛ صرفاً مشاهده، نه پیش‌بینی یا منع ورود"
        }
    }

    /** Pure helper: signs are aligned cloud signs from fully available creation points only. */
    internal fun fromSigns(signs: List<Int>, current: Int, barTime: Long): Snapshot? {
        if (current !in signs.indices || signs[current] == 0) return null
        val sign = signs[current]
        var start = current
        while (start > 0 && signs[start - 1] == sign) start--
        val twist = ((current + 1)..signs.lastIndex).firstOrNull { signs[it] != sign }?.minus(current)
        return Snapshot(current - start + 1, start == 0, twist, barTime)
    }

    fun measure(candles: List<Candle>, interval: Interval, signalBarTime: Long, now: Long, config: BaseStrategyConfig): Snapshot? {
        if (signalBarTime <= 0 || now <= 0 || interval.millis <= 0) return null
        val bars = candles.filter { it.closed && it.time <= signalBarTime && it.time <= now - interval.millis }
            .sortedBy { it.time }
        if (bars.isEmpty() || bars.last().time != signalBarTime || bars.map { it.time }.distinct().size != bars.size) return null
        if (bars.any { !it.high.isFinite() || !it.low.isFinite() || it.high < it.low || it.low <= 0.0 }) return null
        // Do not count missing bars as uninterrupted colour, including gaps around a market close.
        if (bars.zipWithNext().any { (a, b) -> b.time - a.time != interval.millis }) return null
        val tenkan = config.tenkanPeriod
        val kijun = config.kijunPeriod
        val senkou = config.senkouBPeriod
        if (minOf(tenkan, kijun, senkou) <= 0) return null
        val first = maxOf(tenkan, kijun, senkou) - 1
        val displacement = (config.displacement - 1).coerceAtLeast(1) // same alignment as computeSnapshot
        val currentSource = bars.lastIndex - displacement
        if (currentSource < first) return null
        fun mid(i: Int, period: Int): Double {
            val window = bars.subList(i - period + 1, i + 1)
            return (window.maxOf { it.high } + window.minOf { it.low }) / 2.0
        }
        val signs = (first..bars.lastIndex).map { i ->
            val a = (mid(i, tenkan) + mid(i, kijun)) / 2.0
            val b = mid(i, senkou)
            val d = a - b
            if (abs(d) <= maxOf(abs(a), abs(b)) * 1e-12) 0 else if (d > 0) 1 else -1
        }
        return fromSigns(signs, currentSource - first, signalBarTime)
    }

    data class Group(val count: Int, val wins: Int, val meanR: Double?)
    data class Lesson(val near: Group, val other: Group) {
        fun textFa(): String {
            if (near.meanR == null || other.meanR == null) return "درس طول ابر: دادهٔ همسان کافی نیست (نزدیک انتها ${near.count}، مقایسه ${other.count}؛ حداقل $MIN_SAMPLES در هر گروه)"
            return "درس مشاهده‌ای طول ابر: نزدیک انتها ${near.count} معامله، ${near.wins} برد، میانگین ${"%+.2f".format(java.util.Locale.US, near.meanR)}R؛ مقایسه ${other.count} معامله، ${other.wins} برد، میانگین ${"%+.2f".format(java.util.Locale.US, other.meanR)}R؛ رابطه علّی یا تضمین نیست"
        }
    }
    fun lesson(trades: List<PaperTrade>, symbol: String, strategy: String, interval: Interval, action: SignalAction, now: Long): Lesson {
        val candidates = trades.filter { t ->
            t.origin == PaperTrade.ORIGIN_LIVE && !t.isOpen && t.openedAt > 0 && t.closedAt != null &&
                t.closedAt >= t.openedAt && t.closedAt <= now - 3_600_000L &&
                V1Universe.normalize(t.symbol) == V1Universe.normalize(symbol) &&
                BitcoinStrategyModel.normalizeStrategyId(t.strategyId) == BitcoinStrategyModel.normalizeStrategyId(strategy) &&
                t.interval == interval && t.action == action && t.cloudAgeBars > 0 && t.cloudTwistAheadBars > 0 &&
                t.cloudSignalBarTime > 0 && t.cloudSignalBarTime <= t.openedAt &&
                t.pnlUsd?.isFinite() == true && t.fixedRiskUsd.isFinite() && t.fixedRiskUsd > 0 &&
                t.exitReason?.contains("بازیابی ساعات قطعی") != true
        }.distinctBy { listOf(it.symbol, it.strategyId, it.interval, it.action, it.openedAt, it.closedAt, it.entry, it.exitPrice, it.pnlUsd) }
        fun group(wantNear: Boolean): Group {
            val sample = candidates.filter { (it.cloudAgeBars >= LONG_BARS && it.cloudTwistAheadBars <= NEAR_TWIST_BARS) == wantNear }
            val rs = sample.mapNotNull { (it.pnlUsd!! / it.fixedRiskUsd).takeIf(Double::isFinite) }
            return Group(rs.size, sample.count { (it.pnlUsd ?: 0.0) > 0 }, rs.takeIf { it.size >= MIN_SAMPLES }?.average())
        }
        return Lesson(group(true), group(false))
    }
}
