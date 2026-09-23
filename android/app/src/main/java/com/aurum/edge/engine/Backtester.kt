package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalAction
import kotlin.math.abs

/**
 * Back-test that walks the SAME decision function as the live engine over bars that were
 * actually delivered by the provider.
 *
 * It refuses to invent anything:
 *  - no Monte-Carlo "tuned" projections, no synthetic price history;
 *  - every trade is settled with real OHLC values from the series you downloaded;
 *  - the broker minimum lot (0.01 lot = 1 oz) is respected, so trades that a small account
 *    cannot actually place are counted as skipped instead of silently reported as winners.
 */
object Backtester {

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
        /**
         * First bar the engine may open a trade on. Used by [walkForward] so out-of-sample runs
         * only trade after the split while their indicators stay warmed by the earlier real bars.
         */
        startIndex: Int? = null,
    ): Result {
        val series = SignalEngine.series(candles, interval)
        val bars = series.bars
        val firstIndex = maxOf(SignalEngine.minBars(interval), startIndex ?: 0)
        var balance = initialBalance
        var peak = initialBalance
        var maxDrawdownPct = 0.0
        val trades = mutableListOf<Trade>()
        val equity = mutableListOf(EquityPoint(bars.getOrNull(firstIndex)?.time ?: 0L, initialBalance))
        var skippedMinLot = 0
        var skippedMargin = 0

        var open: OpenPosition? = null
        var index = firstIndex

        while (index < bars.size) {
            val bar = bars[index]
            val current = open
            if (current == null) {
                val signal = SignalEngine.decide(series, index, threshold, spreadPrice, narrative = false)
                if (signal.isActionable && signal.stopLoss != null && signal.takeProfit != null) {
                    val dir = if (signal.action == SignalAction.BUY) 1.0 else -1.0
                    val fill = bar.close + dir * spreadPrice / 2.0
                    val stopDistance = abs(fill - signal.stopLoss)
                    val riskUsd = balance * riskPercent / 100.0
                    var oz = if (stopDistance > 0) riskUsd / stopDistance else 0.0
                    val maxOz = balance * leverage / bar.close
                    if (oz > maxOz) {
                        oz = maxOz
                        skippedMargin++
                    }
                    if (oz < minPositionOz) {
                        // A real broker would reject this order: 0.01 lot on gold = 1 oz.
                        skippedMinLot++
                    } else {
                        open = OpenPosition(
                            side = signal.action,
                            entry = fill,
                            stopLoss = signal.stopLoss,
                            takeProfit = signal.takeProfit,
                            positionOz = oz,
                            entryTime = bar.time,
                            entryBar = index,
                            entrySpot = bar.close,
                            confidence = signal.confidence,
                        )
                    }
                }
            } else {
                // Settle on this bar only (never inside the entry bar itself).
                val dir = if (current.side == SignalAction.BUY) 1.0 else -1.0
                val hitStop = if (dir > 0) bar.low <= current.stopLoss else bar.high >= current.stopLoss
                val hitTarget = if (dir > 0) bar.high >= current.takeProfit else bar.low <= current.takeProfit
                val heldBars = index - current.entryBar
                val kijun = series.ichimoku.kijun.getOrNull(index)
                val kijunBreak = kijun != null && if (dir > 0) bar.close < kijun else bar.close > kijun
                val oppositeCross = if (dir > 0) {
                    SignalEngine.snapshot(series, index)?.bearCross == true
                } else {
                    SignalEngine.snapshot(series, index)?.bullCross == true
                }
                val timeStop = heldBars >= SignalEngine.barsValid(interval) * 2

                var exitSpot: Double? = null
                var reason = ""
                if (hitStop) {
                    exitSpot = current.stopLoss
                    reason = "حد ضرر"
                } else if (hitTarget) {
                    exitSpot = current.takeProfit
                    reason = "حد سود"
                } else if (kijunBreak) {
                    exitSpot = bar.close
                    reason = "شکست کیجون (کندل بسته)"
                } else if (oppositeCross) {
                    exitSpot = bar.close
                    reason = "کراس مخالف"
                } else if (timeStop) {
                    exitSpot = bar.close
                    reason = "پایان زمان مجاز (${SignalEngine.barsValid(interval) * 2} کندل)"
                }

                if (exitSpot != null) {
                    val exitFill = exitSpot - dir * spreadPrice / 2.0
                    val fees = commissionPerOz * current.positionOz * 2.0
                    val pnl = (exitFill - current.entry) * dir * current.positionOz - fees
                    val risk = abs(current.entry - current.stopLoss) * current.positionOz
                    balance += pnl
                    peak = maxOf(peak, balance)
                    val dd = if (peak > 0) (peak - balance) / peak * 100.0 else 0.0
                    if (dd > maxDrawdownPct) maxDrawdownPct = dd
                    trades += Trade(
                        side = current.side,
                        entryTime = current.entryTime,
                        entry = current.entry,
                        exitTime = bar.time,
                        exit = exitFill,
                        stopLoss = current.stopLoss,
                        takeProfit = current.takeProfit,
                        exitReason = reason,
                        positionOz = current.positionOz,
                        pnlUsd = pnl,
                        rMultiple = if (risk > 0) pnl / risk else 0.0,
                        feesUsd = fees + spreadPrice * current.positionOz,
                    )
                    equity += EquityPoint(bar.time, balance)
                    open = null
                }
            }
            index++
        }

        // Close any still-open position at the last real price, marked as such.
        open?.let { pos ->
            val dir = if (pos.side == SignalAction.BUY) 1.0 else -1.0
            val last = bars.last()
            val exitFill = last.close - dir * spreadPrice / 2.0
            val fees = commissionPerOz * pos.positionOz * 2.0
            val pnl = (exitFill - pos.entry) * dir * pos.positionOz - fees
            val risk = abs(pos.entry - pos.stopLoss) * pos.positionOz
            balance += pnl
            peak = maxOf(peak, balance)
            maxDrawdownPct = maxOf(maxDrawdownPct, (peak - balance) / peak * 100.0)
            trades += Trade(
                side = pos.side,
                entryTime = pos.entryTime,
                entry = pos.entry,
                exitTime = last.time,
                exit = exitFill,
                stopLoss = pos.stopLoss,
                takeProfit = pos.takeProfit,
                exitReason = "باز — بسته‌شده روی آخرین قیمت واقعی",
                positionOz = pos.positionOz,
                pnlUsd = pnl,
                rMultiple = if (risk > 0) pnl / risk else 0.0,
                feesUsd = fees + spreadPrice * pos.positionOz,
            )
            equity += EquityPoint(last.time, balance)
        }

        val wins = trades.count { it.pnlUsd > 0 }
        val losses = trades.count { it.pnlUsd <= 0 }
        val grossWin = trades.filter { it.pnlUsd > 0 }.sumOf { it.pnlUsd }
        val grossLoss = abs(trades.filter { it.pnlUsd <= 0 }.sumOf { it.pnlUsd })
        val rList = trades.map { it.rMultiple }

        val note = buildString {
            append("بک‌تست روی ${bars.size} کندل ${interval.label} از $dataSource")
            if (skippedMinLot > 0) {
                append(" — $skippedMinLot سیگنال به‌دلیل حداقل حجم بروکر (0.01 لات = 1 انس) قابل اجرا نبود و رد شد")
            }
            if (skippedMargin > 0) {
                append(" — $skippedMargin ترید به‌دلیل سقف لوریج کوچک‌تر شد")
            }
        }

        return Result(
            symbol = symbol,
            dataSource = dataSource,
            interval = interval,
            fromTime = bars.firstOrNull()?.time ?: 0L,
            toTime = bars.lastOrNull()?.time ?: 0L,
            bars = bars.size,
            initialBalance = initialBalance,
            finalBalance = balance,
            trades = trades,
            equity = equity,
            wins = wins,
            losses = losses,
            winRate = if (trades.isEmpty()) null else wins * 100.0 / trades.size,
            profitFactor = if (grossLoss <= 0.0) null else grossWin / grossLoss,
            expectancyR = if (rList.isEmpty()) null else rList.average(),
            netPnl = balance - initialBalance,
            feesUsd = trades.sumOf { it.feesUsd },
            maxDrawdownPct = maxDrawdownPct,
            skippedMinLot = skippedMinLot,
            skippedMargin = skippedMargin,
            spreadPrice = spreadPrice,
            commissionPerOz = commissionPerOz,
            minPositionOz = minPositionOz,
            note = note,
        )
    }

    /**
     * Walk-forward: the older part of the real series is in-sample, the newer part is
     * out-of-sample. Both halves are settled with the same real bars and the same costs, so the
     * comparison shows whether the rules still hold on data the tuning never saw. The verdict is
     * descriptive — the app never promises a result.
     */
    data class WalkForward(
        val inSample: Result,
        val outOfSample: Result,
        val splitTime: Long,
        val splitIndex: Int,
        val bars: Int,
        val verdict: String,
    )

    fun walkForward(
        candles: List<Candle>,
        interval: Interval,
        symbol: String,
        initialBalance: Double = 100.0,
        riskPercent: Double = 0.5,
        spreadPrice: Double = 0.30,
        commissionPerOz: Double = 0.05,
        leverage: Int = 100,
        minPositionOz: Double = 1.0,
        threshold: Double = 72.0,
        splitFraction: Double = 0.7,
    ): WalkForward {
        val closed = candles.filter { it.closed }
        val splitIndex = (closed.size * splitFraction.coerceIn(0.3, 0.85)).toInt()
            .coerceIn(1, maxOf(1, closed.size - 1))

        val inSample = run(
            candles = closed.take(splitIndex),
            interval = interval,
            symbol = symbol,
            initialBalance = initialBalance,
            riskPercent = riskPercent,
            spreadPrice = spreadPrice,
            commissionPerOz = commissionPerOz,
            leverage = leverage,
            minPositionOz = minPositionOz,
            threshold = threshold,
        )
        val outOfSample = run(
            candles = closed,
            interval = interval,
            symbol = symbol,
            initialBalance = initialBalance,
            riskPercent = riskPercent,
            spreadPrice = spreadPrice,
            commissionPerOz = commissionPerOz,
            leverage = leverage,
            minPositionOz = minPositionOz,
            threshold = threshold,
            startIndex = splitIndex,
        )

        val splitTime = closed.getOrNull(splitIndex)?.time ?: 0L
        val inPf = inSample.profitFactor ?: 0.0
        val outPf = outOfSample.profitFactor ?: 0.0
        val verdict = when {
            outOfSample.trades.isEmpty() ->
                "خارج از نمونه هیچ معامله‌ای ثبت نشد — برای قضاوت، بازه بلندتری لازم است."
            outPf > 1.0 && outPf >= inPf * 0.7 ->
                "خارج از نمونه هم مثبت ماند — شواهد پایداری، نه تضمین سود."
            outPf > 1.0 ->
                "خارج از نمونه سودده است ولی ضعیف‌تر از داخل نمونه."
            else ->
                "خارج از نمونه زیان‌ده است — با این تنظیمات قابل اتکا نیست."
        }

        return WalkForward(
            inSample = inSample,
            outOfSample = outOfSample,
            splitTime = splitTime,
            splitIndex = splitIndex,
            bars = closed.size,
            verdict = verdict,
        )
    }

    private data class OpenPosition(
        val side: SignalAction,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val positionOz: Double,
        val entryTime: Long,
        val entryBar: Int,
        val entrySpot: Double,
        val confidence: Double,
    )
}
