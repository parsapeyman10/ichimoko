package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlin.math.abs

/**
 * Historical replay of the TECHNICAL SignalEngine only, on actual provider/imported OHLC.
 * This is NOT a replay of the nine-way AI news/ICT/MTF paper-entry gate: historical verdicts for
 * those gates are unavailable. Entries/exits are hypothetical OHLC fills, not broker executions.
 */
object Backtester {
    const val EXECUTION_MODEL = "NEXT_OPEN_OHLC_V2"

    data class Trade(
        val side: SignalAction,
        val entryTime: Long,
        val entry: Double,
        val exitTime: Long,
        val exit: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val exitReason: String,
        val positionOz: Double,
        val pnlUsd: Double,
        val rMultiple: Double,
        val feesUsd: Double,
    )

    data class EquityPoint(val time: Long, val balance: Double)

    data class Result(
        val symbol: String,
        val dataSource: String,
        val interval: Interval,
        val fromTime: Long,
        val toTime: Long,
        val bars: Int,
        val initialBalance: Double,
        val finalBalance: Double,
        val trades: List<Trade>,
        val equity: List<EquityPoint>,
        val wins: Int,
        val losses: Int,
        val winRate: Double?,
        val profitFactor: Double?,
        val expectancyR: Double?,
        val netPnl: Double,
        val feesUsd: Double,
        val maxDrawdownPct: Double,
        val skippedMinLot: Int,
        val skippedMargin: Int,
        val spreadPrice: Double,
        val commissionPerOz: Double,
        val minPositionOz: Double,
        val note: String,
        val skippedGap: Int = 0,
        val skippedFill: Int = 0,
        /** A position crossed missing OHLC bars; its outcome cannot be reconstructed. */
        val unresolvedGap: Int = 0,
        /** Not included in netPnl, finalBalance, win rate, drawdown, or the list of closed trades. */
        val openAtEnd: Boolean = false,
    ) {
        val hasTrades: Boolean get() = trades.isNotEmpty()
    }

    fun run(
        candles: List<Candle>,
        interval: Interval,
        symbol: String,
        dataSource: String = "Twelve Data (دیتای واقعی)",
        initialBalance: Double = 100.0,
        riskPercent: Double = 0.5,
        spreadPrice: Double = 0.30,
        commissionPerOz: Double = 0.05,
        leverage: Int = 100,
        minPositionOz: Double = 1.0,
        threshold: Double = 72.0,
        /** First signal bar permitted by walk-forward. Indicators may use earlier real bars. */
        startIndex: Int? = null,
    ): Result {
        require(threshold.isFinite()) { "آستانهٔ سیگنال معتبر نیست" }
        return replay(candles, interval, symbol, dataSource, initialBalance, riskPercent,
            spreadPrice, commissionPerOz, leverage, minPositionOz, startIndex) { series, index ->
            SignalEngine.decide(series, index, threshold, spreadPrice, narrative = false)
        }
    }

    /** Test seam only. UI and production walk-forward always call [run] with the real decision. */
    internal fun runWithDecisions(
        candles: List<Candle>, interval: Interval, decision: (Int) -> Signal,
        spreadPrice: Double = 0.30, commissionPerOz: Double = 0.05,
    ): Result = replay(candles, interval, "XAU/USD", "JVM fixture only", 10_000.0, 1.0,
        spreadPrice, commissionPerOz, 100, 0.01, null) { _, index -> decision(index) }

    private fun replay(
        candles: List<Candle>, interval: Interval, symbol: String, dataSource: String,
        initialBalance: Double, riskPercent: Double, spreadPrice: Double,
        commissionPerOz: Double, leverage: Int, minPositionOz: Double, startIndex: Int?,
        decideAt: (SignalEngine.Series, Int) -> Signal,
    ): Result {
        require(initialBalance.isFinite() && initialBalance > 0.0 &&
            riskPercent.isFinite() && riskPercent > 0.0 && riskPercent <= 100.0 &&
            spreadPrice.isFinite() && spreadPrice >= 0.0 &&
            commissionPerOz.isFinite() && commissionPerOz >= 0.0 &&
            minPositionOz.isFinite() && minPositionOz > 0.0 && leverage > 0) {
            "موجودی، ریسک، حجم و هزینه‌های فرضی باید مثبت/معتبر باشند"
        }
        val series = SignalEngine.series(candles, interval)
        val bars = series.bars
        require(bars.all { bar ->
            bar.time > 0L && listOf(bar.open, bar.high, bar.low, bar.close).all {
                it.isFinite() && it > 0.0
            } && bar.high >= maxOf(bar.open, bar.close) &&
                bar.low <= minOf(bar.open, bar.close) && bar.low <= bar.high
        } && bars.zipWithNext().all { (a, b) -> b.time > a.time }) {
            "قیمت، OHLC یا ترتیب کندل‌ها معتبر نیست"
        }
        val firstIndex = maxOf(SignalEngine.minBars(interval), startIndex ?: 0)
        var balance = initialBalance
        var peak = initialBalance
        var maxDrawdownPct = 0.0
        val trades = mutableListOf<Trade>()
        val equity = mutableListOf(EquityPoint(bars.getOrNull(firstIndex)?.time ?: 0L, initialBalance))
        var skippedMinLot = 0
        var skippedMargin = 0
        var skippedGap = 0
        var skippedFill = 0
        var unresolvedGap = 0
        var open: OpenPosition? = null
        var index = firstIndex

        while (index < bars.size) {
            val bar = bars[index]
            if (open != null && index > 0 && bar.time - bars[index - 1].time != interval.millis) {
                // Missing bars may have touched either exit. Do not invent a winning/losing fill.
                unresolvedGap++
                open = null
                index++
                continue
            }
            val current = open
            if (current == null) {
                // This decision uses the close of bar[index]; it can NEVER fill on that close.
                if (index < bars.lastIndex && balance > 0.0) {
                    val signal = decideAt(series, index)
                    val stop = signal.stopLoss
                    val target = signal.takeProfit
                    if (signal.isActionable && stop != null && target != null) {
                        val next = bars[index + 1]
                        val entry = BarFillRules.nextOpen(bar, next, interval, signal.action,
                            stop, target, spreadPrice)
                        if (entry == null) {
                            if (next.time - bar.time != interval.millis) skippedGap++ else skippedFill++
                        } else {
                            val distance = abs(entry.fill - stop)
                            val riskUsd = balance * riskPercent / 100.0
                            var oz = riskUsd / distance
                            val maxOz = balance * leverage / next.open
                            if (oz > maxOz) {
                                oz = maxOz
                                skippedMargin++
                            }
                            if (!oz.isFinite() || oz < minPositionOz) {
                                // A real broker would reject less than the chosen minimum quantity.
                                skippedMinLot++
                            } else {
                                open = OpenPosition(signal.action, entry.fill, stop, target, oz,
                                    entry.time, index + 1)
                            }
                        }
                    }
                }
            } else {
                // The bar of entry is eligible for SL/TP. An ambiguous bar hits the stop first.
                // A Kijun/cross/time signal is known only at close, so fill it at NEXT open.
                val fill = current.pendingExitReason?.let {
                    BarFillRules.nextOpenExit(bar, current.side, spreadPrice, it)
                } ?: BarFillRules.protectiveExit(bar, current.side, current.stopLoss,
                    current.takeProfit, spreadPrice)
                if (fill != null) {
                    val dir = if (current.side == SignalAction.BUY) 1.0 else -1.0
                    val fees = commissionPerOz * current.positionOz * 2.0
                    val pnl = (fill.fill - current.entry) * dir * current.positionOz - fees
                    val risk = abs(current.entry - current.stopLoss) * current.positionOz
                    balance += pnl
                    peak = maxOf(peak, balance)
                    if (peak > 0.0) maxDrawdownPct = maxOf(maxDrawdownPct, (peak - balance) / peak * 100.0)
                    trades += Trade(current.side, current.entryTime, current.entry, bar.time, fill.fill,
                        current.stopLoss, current.takeProfit, fill.reason, current.positionOz, pnl,
                        if (risk > 0) pnl / risk else 0.0,
                        fees + spreadPrice * current.positionOz)
                    equity += EquityPoint(bar.time, balance)
                    open = null
                } else {
                    val dir = if (current.side == SignalAction.BUY) 1.0 else -1.0
                    val kijun = series.ichimoku.kijun.getOrNull(index)
                    val kijunBreak = kijun != null &&
                        (if (dir > 0) bar.close < kijun else bar.close > kijun)
                    val snap = SignalEngine.snapshot(series, index)
                    val oppositeCross = if (dir > 0) snap?.bearCross == true else snap?.bullCross == true
                    val heldBars = index - current.entryBar
                    val reason = when {
                        kijunBreak -> "شکست کیجون (خروج در open کندل بعد)"
                        oppositeCross -> "کراس مخالف (خروج در open کندل بعد)"
                        heldBars >= SignalEngine.barsValid(interval) * 2 ->
                            "پایان زمان مجاز (خروج در open کندل بعد)"
                        else -> null
                    }
                    if (reason != null) open = current.copy(pendingExitReason = reason)
                }
            }
            index++
        }
        // Never force-close a position at the final close; that return was not executable here.
        val wins = trades.count { it.pnlUsd > 0 }
        val losses = trades.count { it.pnlUsd <= 0 }
        val grossWin = trades.filter { it.pnlUsd > 0 }.sumOf { it.pnlUsd }
        val grossLoss = abs(trades.filter { it.pnlUsd <= 0 }.sumOf { it.pnlUsd })
        val note = buildString {
            append("بازپخش فنی روی ${bars.size} کندل ${interval.label} از $dataSource؛ بدون بازپخش شرط نهم AI/خبر، ICT و MTF")
            append("؛ ورود open کندل بعد، خروج دستورِ close در open بعد، برخورد SL/TP به نفع حد ضرر")
            if (open != null) append("؛ یک پوزیشن انتهای بازه باز ماند و از سود/زیان محقق‌شده حذف شد")
            if (skippedGap > 0) append("؛ $skippedGap ورود روی گپ زمانی رد شد")
            if (skippedFill > 0) append("؛ $skippedFill ورود با گپ قیمتی نامعتبر شد")
            if (unresolvedGap > 0) append("؛ $unresolvedGap پوزیشن هنگام فقدان کندل حل‌نشده از آمار حذف شد")
            if (skippedMinLot > 0) append("؛ $skippedMinLot سیگنال زیر حداقل حجم فرضی $minPositionOz واحد رد شد")
            if (skippedMargin > 0) append("؛ $skippedMargin حجم به‌دلیل سقف لوریج فرضی کاهش یافت")
            append("؛ اسپرد/کمیسیون فرضی‌اند، لغزش واقعی و نقدشوندگی شبیه‌سازی نشده‌اند")
        }
        return Result(symbol, dataSource, interval, bars.firstOrNull()?.time ?: 0L,
            bars.lastOrNull()?.time ?: 0L, bars.size, initialBalance, balance,
            trades, equity, wins, losses, if (trades.isEmpty()) null else wins * 100.0 / trades.size,
            if (grossLoss <= 0.0) null else grossWin / grossLoss,
            trades.takeIf { it.isNotEmpty() }?.map { it.rMultiple }?.average(),
            balance - initialBalance, trades.sumOf { it.feesUsd }, maxDrawdownPct,
            skippedMinLot, skippedMargin, spreadPrice, commissionPerOz, minPositionOz,
            note, skippedGap, skippedFill, unresolvedGap, open != null)
    }

    /** One chronological 70/30 split, NOT a statistical validation of profitability. */
    data class WalkForward(
        val inSample: Result,
        val outOfSample: Result,
        val splitTime: Long,
        val splitIndex: Int,
        val bars: Int,
        val verdict: String,
        val costStressOutOfSample: Result,
    )

    fun walkForward(
        candles: List<Candle>, interval: Interval, symbol: String,
        initialBalance: Double = 100.0, riskPercent: Double = 0.5,
        spreadPrice: Double = 0.30, commissionPerOz: Double = 0.05,
        leverage: Int = 100, minPositionOz: Double = 1.0,
        threshold: Double = 72.0, splitFraction: Double = 0.7,
    ): WalkForward {
        val closed = candles.filter { it.closed }
        val splitIndex = (closed.size * splitFraction.coerceIn(0.3, 0.85)).toInt()
            .coerceIn(1, maxOf(1, closed.size - 1))
        val inSample = run(closed.take(splitIndex), interval, symbol, initialBalance = initialBalance,
            riskPercent = riskPercent, spreadPrice = spreadPrice, commissionPerOz = commissionPerOz,
            leverage = leverage, minPositionOz = minPositionOz, threshold = threshold)
        val outOfSample = run(closed, interval, symbol, initialBalance = initialBalance,
            riskPercent = riskPercent, spreadPrice = spreadPrice, commissionPerOz = commissionPerOz,
            leverage = leverage, minPositionOz = minPositionOz, threshold = threshold, startIndex = splitIndex)
        // Re-run on these EXACT SAME closed bars; changing costs can change fills AND which
        // technical setups remain eligible. This is sensitivity analysis, not observed slippage.
        val stressed = run(closed, interval, symbol, initialBalance = initialBalance,
            riskPercent = riskPercent, spreadPrice = spreadPrice * 2.0,
            commissionPerOz = commissionPerOz * 2.0, leverage = leverage,
            minPositionOz = minPositionOz, threshold = threshold, startIndex = splitIndex)
        return WalkForward(inSample, outOfSample, closed.getOrNull(splitIndex)?.time ?: 0L,
            splitIndex, closed.size,
            ResearchEvidence.outOfSample(outOfSample, stressed).title, stressed)
    }

    private data class OpenPosition(
        val side: SignalAction,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val positionOz: Double,
        val entryTime: Long,
        val entryBar: Int,
        val pendingExitReason: String? = null,
    )
}
