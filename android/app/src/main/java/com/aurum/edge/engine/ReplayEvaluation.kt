package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.ReplayDecision

/**
 * Evaluates one recorded replay decision only through the current cursor.
 *
 * This is deliberately not the live-paper settlement path. A decision is filled at the next
 * revealed bar's real open, then its stop/target is checked only on bars already exposed by replay.
 * If a time gap appears, the result becomes DATA_GAP rather than guessing an interpolation or a
 * hidden fill. When both levels are touched in one bar, stop-first is the conservative rule used
 * by the batch backtester too.
 */
object ReplayEvaluation {
    const val DECISION_ONLY = "DECISION_ONLY"
    const val PENDING_ENTRY = "PENDING_ENTRY"
    const val OPEN = "OPEN"
    const val OPEN_AT_END = "OPEN_AT_END"
    const val WIN = "WIN"
    const val LOSS = "LOSS"
    const val DATA_GAP = "DATA_GAP"
    const val NO_LEVELS = "NO_LEVELS"

    data class Result(
        val status: String,
        val fillBarTime: Long? = null,
        val fillPrice: Double? = null,
        val outcomeBarTime: Long? = null,
        val outcomePrice: Double? = null,
        val reason: String? = null,
    )

    fun evaluate(
        decision: ReplayDecision,
        candles: List<Candle>,
        interval: Interval,
        cursor: Int,
    ): Result {
        val bars = candles.filter { it.closed }.sortedBy { it.time }
        if (bars.isEmpty()) return Result(DATA_GAP, reason = "هیچ کندل بستهٔ واقعی برای replay وجود ندارد")
        val decisionIndex = bars.indexOfFirst { it.time == decision.barTime }
        if (decisionIndex < 0) return Result(DATA_GAP, reason = "کندل تصمیم در دیتای replay موجود نیست")
        val end = cursor.coerceIn(0, bars.lastIndex)
        if (end < decisionIndex) return Result(DECISION_ONLY)
        if (end == decisionIndex) return Result(PENDING_ENTRY)
        val stopLoss = decision.stopLoss
            ?: return Result(NO_LEVELS, reason = "تصمیم بدون جهت یا SL/TP کامل است")
        val takeProfit = decision.takeProfit
            ?: return Result(NO_LEVELS, reason = "تصمیم بدون جهت یا SL/TP کامل است")
        if (decision.action !in setOf("BUY", "SELL")) {
            return Result(NO_LEVELS, reason = "تصمیم بدون جهت یا SL/TP کامل است")
        }

        val fillIndex = decisionIndex + 1
        if (fillIndex > end) return Result(PENDING_ENTRY)
        if (bars[fillIndex].time - bars[decisionIndex].time > interval.millis) {
            return Result(DATA_GAP, reason = "بین تصمیم و اولین open یک gap واقعی وجود دارد")
        }
        val fill = bars[fillIndex]
        val isBuy = decision.action == "BUY"
        for (index in fillIndex..end) {
            val previous = bars.getOrNull(index - 1)
            val bar = bars[index]
            if (previous != null && bar.time - previous.time > interval.millis) {
                return Result(
                    DATA_GAP,
                    fillBarTime = fill.time,
                    fillPrice = fill.open,
                    reason = "بین fill و نتیجه gap واقعی وجود دارد؛ نتیجه حدس زده نشد",
                )
            }
            val stopHit = if (isBuy) bar.low <= stopLoss else bar.high >= stopLoss
            val targetHit = if (isBuy) bar.high >= takeProfit else bar.low <= takeProfit
            when {
                stopHit && targetHit -> return Result(
                    LOSS,
                    fillBarTime = fill.time,
                    fillPrice = fill.open,
                    outcomeBarTime = bar.time,
                    outcomePrice = stopLoss,
                    reason = "در یک کندل هر دو سطح لمس شد؛ قانون محافظه‌کارانه stop-first اعمال شد",
                )
                stopHit -> return Result(
                    LOSS,
                    fillBarTime = fill.time,
                    fillPrice = fill.open,
                    outcomeBarTime = bar.time,
                    outcomePrice = stopLoss,
                    reason = "حد ضرر در کندل آشکارشده لمس شد",
                )
                targetHit -> return Result(
                    WIN,
                    fillBarTime = fill.time,
                    fillPrice = fill.open,
                    outcomeBarTime = bar.time,
                    outcomePrice = takeProfit,
                    reason = "هدف در کندل آشکارشده لمس شد",
                )
            }
        }
        return Result(
            if (end == bars.lastIndex) OPEN_AT_END else OPEN,
            fillBarTime = fill.time,
            fillPrice = fill.open,
            reason = if (end == bars.lastIndex) "تا پایان دیتای واقعی باز ماند" else "هنوز سطح خروج در کندل‌های آشکارشده لمس نشده است",
        )
    }
}
