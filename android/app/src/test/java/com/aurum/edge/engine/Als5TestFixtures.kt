package com.aurum.edge.engine

import com.aurum.edge.core.Als5OpportunityRecord
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlin.math.abs

/** Deterministic proof fixture for journal-contract tests; not a market signal or test result. */
internal fun asConfirmedAls5TestSignal(input: Signal): Signal {
    val side = input.action
    require(side == SignalAction.BUY || side == SignalAction.SELL)
    val entry = requireNotNull(input.entry)
    val stop = requireNotNull(input.stopLoss)
    val target = requireNotNull(input.takeProfit)
    val rr = abs(target - entry) / abs(entry - stop)
    val scoreItems = listOf(
        "رژیم و ساختار بازار · ALS-5",
        "کیفیت ستاپ و سطح نقدینگی · ALS-5",
        "تأیید ورود و شکست ریزساختار · ALS-5",
        "نسبت ریسک به ریوارد و شرایط اجرا · ALS-5",
    ).map { ConfluenceItem(it, true, "fixture only", ConfluenceStatus.CONFIRMED, 25) }
    val opportunity = Als5OpportunityRecord(
        state = side.name,
        setupType = "ENGINE_A_TREND",
        direction = side,
        structuralLevel = if (side == SignalAction.BUY) entry - 1.0 else entry + 1.0,
        regime = if (side == SignalAction.BUY) "BULLISH_TREND" else "BEARISH_TREND",
        satisfiedConditions = listOf("fixture-only structural confirmation"),
        missingConditions = emptyList(),
        invalidationLevel = if (side == SignalAction.BUY) stop + 0.1 else stop - 0.1,
        candidateEntry = entry,
        estimatedStopLoss = stop,
        estimatedTakeProfit = target,
        rewardToRisk = rr,
        candidateSinceBarTime = input.barTime,
        expirationBarsRemaining = 0,
        scoreOutOf10 = 10,
        structureScore = 2,
        setupLevelScore = 2,
        confirmationScore = 2,
        rrScore = 2,
        executionScore = 2,
        positionLots = 0.10,
        positionOz = 10.0,
        setupKey = "TEST|XAU/USD|${input.barTime}|$side",
        barTime = input.barTime,
    )
    return input.copy(
        confidence = 100.0,
        riskReward = rr,
        confluence = scoreItems,
        als5Opportunity = opportunity,
    )
}
