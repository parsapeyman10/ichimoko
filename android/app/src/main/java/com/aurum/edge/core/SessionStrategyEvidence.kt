package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import kotlinx.serialization.Serializable

/** Descriptive, read-only analysis. Never used to allow, block or size a trade. */
object SessionStrategyEvidence {
    val sessions = listOf("ASIA", "LONDON", "LONDON_NY", "NEW_YORK", "LATE_NY")
    @Serializable
    data class Row(
        val session: String, val count: Int, val wins: Int, val losses: Int, val breakeven: Int,
        val netUsd: Double, val grossProfitUsd: Double, val grossLossUsd: Double,
        val profitFactor: Double?, val rSamples: Int, val meanR: Double?, val medianR: Double?,
        val maxDrawdownR: Double?, val losingStreak: Int, val averageDurationMinutes: Double?,
        val neuralEntryCount: Int,
    )
    fun build(trades: List<PaperTrade>, symbol: String, strategy: String, interval: Interval, direction: SignalAction?): List<Row> {
        val relevant = trades.filter { !it.isOpen && !it.isBacktest && it.pnlUsd?.isFinite() == true &&
            it.openedAt > 0 && (it.closedAt ?: 0L) >= it.openedAt && V1Universe.normalize(it.symbol) == V1Universe.normalize(symbol) &&
            it.strategyId == strategy && it.interval == interval && (direction == null || it.action == direction) }
        return sessions.map { session ->
            val ts = relevant.filter { NeuralStateFeatures.sessionCodeOf(it.openedAt) == session }
                .sortedWith(compareBy<PaperTrade> { it.closedAt }.thenBy { it.id })
            val pnls = ts.mapNotNull { it.pnlUsd }; val gross = pnls.filter { it > 0 }.sum(); val loss = -pnls.filter { it < 0 }.sum()
            val rs = ts.mapNotNull { t -> t.fixedRiskUsd.takeIf { it.isFinite() && it > 0.0 }?.let { t.pnlUsd!! / it }?.takeIf { it.isFinite() } }
            var eq = 0.0; var peak = 0.0; var dd = 0.0; var streak = 0; var worst = 0
            ts.forEach { t ->
                val r = t.fixedRiskUsd.takeIf { it.isFinite() && it > 0.0 }?.let { t.pnlUsd!! / it }?.takeIf { it.isFinite() }
                if (r != null) { eq += r; peak = maxOf(peak, eq); dd = maxOf(dd, peak - eq) }
                streak = if (t.pnlUsd!! < 0) streak + 1 else 0; worst = maxOf(worst, streak)
            }
            val sorted = rs.sorted(); val median = if (sorted.isEmpty()) null else if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            Row(session, ts.size, pnls.count { it > 0 }, pnls.count { it < 0 }, pnls.count { it == 0.0 }, pnls.sum(), gross, loss,
                if (loss > 0) gross / loss else null, rs.size, rs.takeIf { it.isNotEmpty() }?.average(), median,
                if (rs.size == ts.size && ts.isNotEmpty()) dd else null, worst,
                ts.takeIf { it.isNotEmpty() }?.map { (it.closedAt!! - it.openedAt) / 60000.0 }?.average(),
                ts.count { it.neuralEntryObservation?.isUsableFor(it.openedAt) == true })
        }
    }
    @Serializable
    data class Profile(val symbol: String, val strategyId: String, val timeframe: String, val direction: String, val sessions: List<Row>)
    fun profiles(trades: List<PaperTrade>, extraStrategyIds: List<String> = emptyList()): List<Profile> {
        val live = trades.filter { !it.isBacktest }
        val groups = live.groupBy { Triple(V1Universe.normalize(it.symbol), it.strategyId, it.interval) }
        val symbols = (V1Universe.allSymbols + live.map { V1Universe.normalize(it.symbol) }).filter { it.isNotBlank() }.distinct().sorted()
        val strategies = (BitcoinStrategyModel.entries.map { it.id } + extraStrategyIds + live.map { it.strategyId })
            .filter { it.isNotBlank() }.distinct().sorted()
        return symbols.flatMap { symbol -> strategies.flatMap { strategy -> Interval.entries.flatMap { interval ->
            val bucket = groups[Triple(symbol, strategy, interval)].orEmpty()
            listOf(null, SignalAction.BUY, SignalAction.SELL).map { direction ->
                Profile(symbol, strategy, interval.label, direction?.name ?: "BOTH", build(bucket, symbol, strategy, interval, direction))
            }
        } } }
    }
    /** Re-evaluates saved prices with the current paper cost model, not a historical execution proof. */
    fun planReviewFa(t: PaperTrade): String {
        val sl = t.initialStopLoss ?: return "حد اولیه نامعلوم؛ بررسی پلن ممکن نیست"
        val dir = when (t.action) { SignalAction.BUY -> 1.0; SignalAction.SELL -> -1.0; else -> return "جهت نامعلوم" }
        if (listOf(t.entry, sl, t.takeProfit, t.fixedRiskUsd, t.positionOz).any { !it.isFinite() || it <= 0.0 }) return "قیمت، حجم یا ریسک نامعتبر/نامعلوم"
        if ((t.entry - sl) * dir <= 0 || (t.takeProfit - t.entry) * dir <= 0) return "هشدار: جهت حد اولیه یا هدف با جهت ورود ناسازگار است"
        if (!t.netSettlementEnabled) return "تسویهٔ قدیمی؛ مدل خالص ورود و درستی تاریخی نامعلوم"
        val slNet = RiskAndCalendarEngine.computeExitPnlUsd(t, sl).second
        val tpNet = RiskAndCalendarEngine.computeExitPnlUsd(t, t.takeProfit).second
        if (!slNet.isFinite() || !tpNet.isFinite()) return "محاسبهٔ هزینه یا سود خالص نامعلوم"
        val ratio = tpNet / t.fixedRiskUsd
        return if (kotlin.math.abs(slNet + t.fixedRiskUsd) > 0.005 || ratio < 2.0 - 1e-8 || ratio > 3.0 + 1e-8)
            "هشدار بازبینی: حد اولیه/هدف در مدل فعلی هزینه خارج از ریسک ثبت‌شده یا هدف ۲–۳R است؛ مقایسهٔ تاریخی قطعی نیست"
        else "پلن با مدل فعلی هزینه و هدف ۲–۳R سازگار است؛ اجرای واقعی و مسیر قفل ثابت نشده"
    }
}
