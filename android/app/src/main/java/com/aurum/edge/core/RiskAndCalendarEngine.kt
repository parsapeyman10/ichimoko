package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

enum class AutoExitKind(val reasonFa: String) {
    TAKE_PROFIT("برخورد خودکار به حد سود (TP)"),
    STOP_LOSS("برخورد خودکار به حد ضرر (SL)"),
}

data class AutoExitEvaluation(
    val tradeId: String,
    val symbol: String,
    val kind: AutoExitKind,
    val exitPrice: Double,
    val closedAt: Long,
    val grossPnlUsd: Double,
    val netPnlUsd: Double,
    val fromBackfill: Boolean = false,
)

data class CandleBackfillSummary(
    val symbol: String,
    val fromMillis: Long,
    val toMillis: Long,
    val candlesCount: Int,
    val periodHigh: Double,
    val periodLow: Double,
    val lastClose: Double,
    val provider: String,
    val syncedAt: Long,
)

data class RiskCalculationResult(
    val symbol: String,
    val action: SignalAction,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val accountBalance: Double,
    val riskPercent: Double,
    val riskBudgetUsd: Double,
    val stopDistance: Double,
    val targetDistance: Double,
    val riskReward: Double,
    val positionUnits: Double,
    val unitLabel: String,
    val standardLots: Double,
    val lotDescription: String,
    val leverage: Int,
    val notionalUsd: Double,
    val marginRequiredUsd: Double,
    val commissionUsd: Double,
    val spreadCostUsd: Double,
    val grossProfitAtTpUsd: Double,
    val netProfitAtTpUsd: Double,
    val grossLossAtSlUsd: Double,
    val netLossAtSlUsd: Double,
) {
    fun toPaperTrade(
        note: String = "",
        openedAt: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): PaperTrade = PaperTrade(
        id = id,
        symbol = symbol,
        interval = Interval.M5,
        action = action,
        entry = entry,
        stopLoss = stopLoss,
        takeProfit = takeProfit,
        confidence = 100.0,
        riskReward = round2(riskReward),
        openedAt = openedAt,
        positionOz = positionUnits,
        note = note.trim(),
        positionUnit = unitLabel,
        autoOpened = false,
        leverage = leverage,
        marginUsd = round2(marginRequiredUsd),
        commissionUsd = round2(commissionUsd),
        spreadCostUsd = round2(spreadCostUsd),
        initialStopLoss = stopLoss,
    )
}

data class RiskLimitStatus(
    val accountBalance: Double,
    val initialCapital: Double,
    val dayOpeningEquity: Double,
    val dailyLossLimitPercent: Double,
    val dailyLossLimitUsd: Double,
    val todayRealizedPnlUsd: Double,
    val openTradesRiskUsd: Double,
    val remainingDailyRiskUsd: Double,
    val maxDrawdownLimitPercent: Double,
    val maxDrawdownLimitUsd: Double,
    val currentDrawdownUsd: Double,
    val remainingDrawdownUsd: Double,
    val isDailyLimitBreached: Boolean,
    val isOverallDrawdownBreached: Boolean,
) {
    fun canOpenTrade(proposedRiskUsd: Double): Boolean =
        !isDailyLimitBreached &&
            !isOverallDrawdownBreached &&
            proposedRiskUsd <= remainingDailyRiskUsd + 0.01 &&
            proposedRiskUsd <= remainingDrawdownUsd + 0.01
}

data class DayPnlSummary(
    val date: LocalDate,
    val dayOfMonth: Int,
    val closedTradesCount: Int,
    val wins: Int,
    val losses: Int,
    val netPnlUsd: Double,
    val trades: List<PaperTrade>,
)

data class MonthlyPnlCalendar(
    val year: Int,
    val month: Int,
    val monthTitleFa: String,
    val firstDayOffsetSaturdayStart: Int,
    val daysInMonth: Int,
    val days: List<DayPnlSummary>,
    val monthNetPnlUsd: Double,
    val winningDays: Int,
    val losingDays: Int,
    val totalClosedTrades: Int,
    val monthWinRate: Double?,
    val bestDayPnlUsd: Double?,
    val worstDayPnlUsd: Double?,
)

object RiskAndCalendarEngine {

    private val PERSIAN_GREGORIAN_MONTHS = listOf(
        "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
        "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر",
    )

    fun calculatePosition(
        symbolInput: String,
        accountBalance: Double,
        riskPercent: Double,
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        preferredAction: SignalAction? = null,
    ): RiskCalculationResult? {
        val symbol = V1Universe.normalize(symbolInput).ifBlank { "XAU/USD" }
        if (!accountBalance.isFinite() || accountBalance <= 0.0) return null
        if (!riskPercent.isFinite() || riskPercent <= 0.0) return null
        if (!entry.isFinite() || entry <= 0.0) return null
        if (!stopLoss.isFinite() || stopLoss <= 0.0) return null
        if (!takeProfit.isFinite() || takeProfit <= 0.0) return null

        val stopDistance = abs(entry - stopLoss)
        val targetDistance = abs(takeProfit - entry)
        if (stopDistance <= 1e-8 || targetDistance <= 1e-8) return null

        val inferredAction = when {
            stopLoss < entry && takeProfit > entry -> SignalAction.BUY
            stopLoss > entry && takeProfit < entry -> SignalAction.SELL
            else -> preferredAction ?: return null
        }

        // Validate directional consistency of SL and TP with action
        if (inferredAction == SignalAction.BUY && !(stopLoss < entry && takeProfit > entry)) return null
        if (inferredAction == SignalAction.SELL && !(stopLoss > entry && takeProfit < entry)) return null

        val riskBudgetUsd = accountBalance * (riskPercent / 100.0)
        val perUnitStopUsd = PaperOrderRules.quotePnlToUsd(symbol, stopDistance, entry)
        if (!perUnitStopUsd.isFinite() || perUnitStopUsd <= 1e-9) return null

        val rawUnits = riskBudgetUsd / perUnitStopUsd
        val positionUnits = round4(max(0.0001, rawUnits))
        val unitLabel = PaperOrderRules.unitFor(symbol)
        val (standardLots, lotDesc) = computeStandardLots(symbol, positionUnits, unitLabel)

        val spec = VenueSpecs.of(symbol)
        val leverage = PaperOrderRules.defaultLeverageFor(symbol).coerceAtLeast(1)
        val notionalUsd = PaperOrderRules.quotePnlToUsd(symbol, positionUnits * entry, entry)
        val marginRequiredUsd = notionalUsd / leverage
        val commissionUsd = spec.commissionUsd(entry, positionUnits) * 2.0
        val spreadCostUsd = spec.spreadCostUsd(entry, positionUnits)

        val grossProfitUsd = PaperOrderRules.quotePnlToUsd(symbol, targetDistance * positionUnits, takeProfit)
        val grossLossUsd = PaperOrderRules.quotePnlToUsd(symbol, stopDistance * positionUnits, stopLoss)
        val netProfitUsd = grossProfitUsd - commissionUsd - spreadCostUsd
        val netLossUsd = grossLossUsd + commissionUsd + spreadCostUsd
        val riskReward = targetDistance / stopDistance

        return RiskCalculationResult(
            symbol = symbol,
            action = inferredAction,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            accountBalance = accountBalance,
            riskPercent = riskPercent,
            riskBudgetUsd = round2(riskBudgetUsd),
            stopDistance = stopDistance,
            targetDistance = targetDistance,
            riskReward = round2(riskReward),
            positionUnits = positionUnits,
            unitLabel = unitLabel,
            standardLots = round4(standardLots),
            lotDescription = lotDesc,
            leverage = leverage,
            notionalUsd = round2(notionalUsd),
            marginRequiredUsd = round2(marginRequiredUsd),
            commissionUsd = round2(commissionUsd),
            spreadCostUsd = round2(spreadCostUsd),
            grossProfitAtTpUsd = round2(grossProfitUsd),
            netProfitAtTpUsd = round2(netProfitUsd),
            grossLossAtSlUsd = round2(grossLossUsd),
            netLossAtSlUsd = round2(netLossUsd),
        )
    }

    /**
     * Suggests default SL and TP around a live entry price for quick one-tap prefill.
     */
    fun suggestSlTp(
        symbolInput: String,
        entryPrice: Double,
        action: SignalAction = SignalAction.BUY,
        rewardRatio: Double = 2.0,
    ): Pair<Double, Double>? {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return null
        val symbol = V1Universe.normalize(symbolInput)
        val defaultStopPct = when (AssetClass.of(symbol)) {
            AssetClass.FOREX -> 0.0030 // 0.30% (~30 pips on majors)
            AssetClass.COMMODITY -> 0.0050 // 0.50% (~$13 on Gold)
            AssetClass.STOCK -> 0.0050 // 0.50% on Dow
            AssetClass.CRYPTO -> 0.0100 // 1.00% on Crypto
        }
        val stopDist = max(entryPrice * defaultStopPct, VenueSpecs.minStopDistance(symbol, entryPrice) * 3.0)
        val tpDist = stopDist * rewardRatio.coerceAtLeast(1.0)
        val decimals = priceDecimals(symbol, entryPrice)
        val sl = if (action == SignalAction.BUY) entryPrice - stopDist else entryPrice + stopDist
        val tp = if (action == SignalAction.BUY) entryPrice + tpDist else entryPrice - tpDist
        return roundToDecimals(max(0.00001, sl), decimals) to roundToDecimals(max(0.00001, tp), decimals)
    }

    fun evaluateAutoExit(trade: PaperTrade, tick: PriceTick): AutoExitEvaluation? {
        if (!trade.isOpen) return null
        val price = tick.price
        if (!price.isFinite() || price <= 0.0) return null

        val hitKind: AutoExitKind? = when (trade.action) {
            SignalAction.BUY -> when {
                price >= trade.takeProfit -> AutoExitKind.TAKE_PROFIT
                price <= trade.stopLoss -> AutoExitKind.STOP_LOSS
                else -> null
            }
            SignalAction.SELL -> when {
                price <= trade.takeProfit -> AutoExitKind.TAKE_PROFIT
                price >= trade.stopLoss -> AutoExitKind.STOP_LOSS
                else -> null
            }
            SignalAction.NO_TRADE -> null
        }

        val kind = hitKind ?: return null
        val exitPrice = when (kind) {
            AutoExitKind.TAKE_PROFIT -> trade.takeProfit
            AutoExitKind.STOP_LOSS -> trade.stopLoss
        }
        val (gross, net) = computeExitPnlUsd(trade, exitPrice)
        return AutoExitEvaluation(
            tradeId = trade.id,
            symbol = trade.symbol,
            kind = kind,
            exitPrice = exitPrice,
            closedAt = max(tick.at, trade.openedAt + 1L),
            grossPnlUsd = round2(gross),
            netPnlUsd = round2(net),
            fromBackfill = false,
        )
    }

    /**
     * Chronologically replays historical OHLC candles (e.g. from an offline gap of 6–24 hours)
     * against an open trade's SL and TP. If any candle's High/Low crossed TP or SL after the trade
     * was opened, returns the exact exit evaluation at that candle's timestamp.
     */
    fun evaluateCandleWindow(trade: PaperTrade, candles: List<Candle>): AutoExitEvaluation? {
        if (!trade.isOpen || candles.isEmpty()) return null
        val ordered = candles
            .filter {
                it.time >= trade.openedAt - 60_000L &&
                    it.high.isFinite() && it.low.isFinite() &&
                    it.high > 0.0 && it.low > 0.0 && it.high >= it.low
            }
            .sortedBy { it.time }

        for (bar in ordered) {
            val hitKind: AutoExitKind? = when (trade.action) {
                SignalAction.BUY -> {
                    val slHit = bar.low <= trade.stopLoss
                    val tpHit = bar.high >= trade.takeProfit
                    when {
                        slHit && tpHit -> {
                            if (abs(bar.open - trade.stopLoss) <= abs(trade.takeProfit - bar.open)) {
                                AutoExitKind.STOP_LOSS
                            } else {
                                AutoExitKind.TAKE_PROFIT
                            }
                        }
                        slHit -> AutoExitKind.STOP_LOSS
                        tpHit -> AutoExitKind.TAKE_PROFIT
                        else -> null
                    }
                }
                SignalAction.SELL -> {
                    val slHit = bar.high >= trade.stopLoss
                    val tpHit = bar.low <= trade.takeProfit
                    when {
                        slHit && tpHit -> {
                            if (abs(trade.stopLoss - bar.open) <= abs(bar.open - trade.takeProfit)) {
                                AutoExitKind.STOP_LOSS
                            } else {
                                AutoExitKind.TAKE_PROFIT
                            }
                        }
                        slHit -> AutoExitKind.STOP_LOSS
                        tpHit -> AutoExitKind.TAKE_PROFIT
                        else -> null
                    }
                }
                SignalAction.NO_TRADE -> null
            }

            if (hitKind != null) {
                val exitPrice = when (hitKind) {
                    AutoExitKind.TAKE_PROFIT -> trade.takeProfit
                    AutoExitKind.STOP_LOSS -> trade.stopLoss
                }
                val (gross, net) = computeExitPnlUsd(trade, exitPrice)
                return AutoExitEvaluation(
                    tradeId = trade.id,
                    symbol = trade.symbol,
                    kind = hitKind,
                    exitPrice = exitPrice,
                    closedAt = max(bar.time, trade.openedAt + 1L),
                    grossPnlUsd = round2(gross),
                    netPnlUsd = round2(net),
                    fromBackfill = true,
                )
            }
        }
        return null
    }

    fun summarizeBackfill(
        symbolInput: String,
        requestedFromMillis: Long,
        candles: List<Candle>,
        provider: String,
        syncedAt: Long = System.currentTimeMillis(),
    ): CandleBackfillSummary? {
        val valid = candles.filter {
            it.high.isFinite() && it.low.isFinite() && it.close.isFinite() &&
                it.high > 0.0 && it.low > 0.0 && it.close > 0.0
        }.sortedBy { it.time }
        if (valid.isEmpty()) return null
        return CandleBackfillSummary(
            symbol = V1Universe.normalize(symbolInput),
            fromMillis = minOf(requestedFromMillis, valid.first().time),
            toMillis = max(valid.last().time, syncedAt),
            candlesCount = valid.size,
            periodHigh = valid.maxOf { it.high },
            periodLow = valid.minOf { it.low },
            lastClose = valid.last().close,
            provider = provider,
            syncedAt = syncedAt,
        )
    }

    fun computeExitPnlUsd(trade: PaperTrade, exitPrice: Double): Pair<Double, Double> {
        val diffPerUnit = if (trade.action == SignalAction.BUY) {
            exitPrice - trade.entry
        } else {
            trade.entry - exitPrice
        }
        val gross = PaperOrderRules.quotePnlToUsd(trade.symbol, diffPerUnit * trade.positionOz, exitPrice)
        val net = gross - trade.effectiveCommissionUsd - trade.effectiveSpreadCostUsd
        return gross to net
    }

    fun evaluateRiskLimits(
        accountBalance: Double,
        initialCapital: Double,
        dayOpeningEquity: Double,
        dailyLossLimitPercent: Double,
        maxDrawdownLimitPercent: Double,
        trades: List<PaperTrade>,
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RiskLimitStatus {
        val safeBalance = accountBalance.coerceAtLeast(0.0)
        val safeInitial = max(initialCapital, safeBalance).coerceAtLeast(10.0)
        val safeDayOpen = dayOpeningEquity.takeIf { it > 0.0 } ?: safeBalance
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()

        val todayRealized = trades.filter { !it.isOpen && it.closedAt != null && it.pnlUsd != null }
            .filter { Instant.ofEpochMilli(it.closedAt!!).atZone(zoneId).toLocalDate() == today }
            .sumOf { it.pnlUsd ?: 0.0 }

        val openRisk = trades.filter { it.isOpen }.sumOf { it.riskUsd }
        val dailyLossLimitUsd = safeDayOpen * (dailyLossLimitPercent.coerceIn(0.5, 50.0) / 100.0)
        val todayLossUsed = max(0.0, -todayRealized) + openRisk
        val remainingDailyRiskUsd = max(0.0, dailyLossLimitUsd - todayLossUsed)

        val maxDrawdownLimitUsd = safeInitial * (maxDrawdownLimitPercent.coerceIn(1.0, 80.0) / 100.0)
        val currentDrawdownUsd = max(0.0, safeInitial - safeBalance) + openRisk
        val remainingDrawdownUsd = max(0.0, maxDrawdownLimitUsd - currentDrawdownUsd)

        return RiskLimitStatus(
            accountBalance = round2(safeBalance),
            initialCapital = round2(safeInitial),
            dayOpeningEquity = round2(safeDayOpen),
            dailyLossLimitPercent = dailyLossLimitPercent,
            dailyLossLimitUsd = round2(dailyLossLimitUsd),
            todayRealizedPnlUsd = round2(todayRealized),
            openTradesRiskUsd = round2(openRisk),
            remainingDailyRiskUsd = round2(remainingDailyRiskUsd),
            maxDrawdownLimitPercent = maxDrawdownLimitPercent,
            maxDrawdownLimitUsd = round2(maxDrawdownLimitUsd),
            currentDrawdownUsd = round2(currentDrawdownUsd),
            remainingDrawdownUsd = round2(remainingDrawdownUsd),
            isDailyLimitBreached = remainingDailyRiskUsd <= 0.01,
            isOverallDrawdownBreached = remainingDrawdownUsd <= 0.01,
        )
    }

    fun buildMonthlyCalendar(
        year: Int,
        month: Int,
        trades: List<PaperTrade>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): MonthlyPnlCalendar {
        val ym = YearMonth.of(year, month.coerceIn(1, 12))
        val firstDate = ym.atDay(1)
        // DayOfWeek: MONDAY=1 .. SATURDAY=6, SUNDAY=7.
        // Convert to Saturday-first index (Saturday = 0, Sunday = 1, Monday = 2, ..., Friday = 6)
        val saturdayFirstOffset = (firstDate.dayOfWeek.value + 1) % 7
        val daysInMonth = ym.lengthOfMonth()

        val closedInMonth = trades.filter { !it.isOpen && it.closedAt != null && it.pnlUsd != null }
            .mapNotNull { trade ->
                val localDate = Instant.ofEpochMilli(trade.closedAt!!).atZone(zoneId).toLocalDate()
                if (localDate.year == ym.year && localDate.monthValue == ym.monthValue) {
                    localDate.dayOfMonth to trade
                } else {
                    null
                }
            }
            .groupBy({ it.first }, { it.second })

        val days = (1..daysInMonth).map { dayNum ->
            val dayTrades = (closedInMonth[dayNum] ?: emptyList()).sortedByDescending { it.closedAt ?: 0L }
            val net = round2(dayTrades.sumOf { it.pnlUsd ?: 0.0 })
            val wins = dayTrades.count { (it.pnlUsd ?: 0.0) > 0.0 }
            val losses = dayTrades.count { (it.pnlUsd ?: 0.0) <= 0.0 }
            DayPnlSummary(
                date = ym.atDay(dayNum),
                dayOfMonth = dayNum,
                closedTradesCount = dayTrades.size,
                wins = wins,
                losses = losses,
                netPnlUsd = net,
                trades = dayTrades,
            )
        }

        val activeDays = days.filter { it.closedTradesCount > 0 }
        val allMonthTrades = days.flatMap { it.trades }
        val totalWins = allMonthTrades.count { (it.pnlUsd ?: 0.0) > 0.0 }

        return MonthlyPnlCalendar(
            year = ym.year,
            month = ym.monthValue,
            monthTitleFa = "${PERSIAN_GREGORIAN_MONTHS[ym.monthValue - 1]} ${ym.year}",
            firstDayOffsetSaturdayStart = saturdayFirstOffset,
            daysInMonth = daysInMonth,
            days = days,
            monthNetPnlUsd = round2(days.sumOf { it.netPnlUsd }),
            winningDays = activeDays.count { it.netPnlUsd > 0.0 },
            losingDays = activeDays.count { it.netPnlUsd < 0.0 },
            totalClosedTrades = allMonthTrades.size,
            monthWinRate = if (allMonthTrades.isEmpty()) null else round2(totalWins * 100.0 / allMonthTrades.size),
            bestDayPnlUsd = activeDays.maxOfOrNull { it.netPnlUsd },
            worstDayPnlUsd = activeDays.minOfOrNull { it.netPnlUsd },
        )
    }

    private fun computeStandardLots(
        symbol: String,
        units: Double,
        unitLabel: String,
    ): Pair<Double, String> {
        val s = symbol.uppercase(Locale.ROOT)
        return when {
            s.startsWith("XAU") -> {
                val lots = units / 100.0
                lots to String.format(Locale.US, "%.3f لات طلا (%.2f انس)", lots, units)
            }
            s.startsWith("XAG") -> {
                val lots = units / 5000.0
                lots to String.format(Locale.US, "%.3f لات نقره (%.2f انس)", lots, units)
            }
            AssetClass.of(s) == AssetClass.FOREX -> {
                val lots = units / 100_000.0
                lots to String.format(Locale.US, "%.3f لات استاندارد (%.0f %s)", lots, units, unitLabel)
            }
            AssetClass.of(s) == AssetClass.CRYPTO -> {
                units to String.format(Locale.US, "%.4f %s", units, s.substringBefore('/'))
            }
            else -> {
                units to String.format(Locale.US, "%.3f قرارداد/واحد", units)
            }
        }
    }

    private fun priceDecimals(symbol: String, price: Double): Int {
        val s = symbol.uppercase(Locale.ROOT)
        return when {
            s.endsWith("JPY") -> 3
            AssetClass.of(s) == AssetClass.FOREX -> 5
            price >= 1000.0 -> 2
            price >= 1.0 -> 3
            else -> 4
        }
    }

    private fun roundToDecimals(value: Double, decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return round(value * factor) / factor
    }
}

private fun round2(v: Double): Double = round(v * 100.0) / 100.0
private fun round4(v: Double): Double = round(v * 10_000.0) / 10_000.0
