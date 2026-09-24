package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.engine.IctRangeAnalyzer
import kotlin.math.abs

/** Additional, conservative PAPER-only gate. Never replaces the 8 technical + AI-news checks. */
object IctEntryRules {
    data class Decision(
        val reason: String?,
        val snapshot: IctRangeAnalyzer.Snapshot? = null,
        val setup: IctRangeAnalyzer.Setup? = null,
    ) {
        val allowed: Boolean get() = reason == null
    }

    /**
     * Plan an SL beyond the swept wick and TP *before* the opposing range line. This
     * changes only a PAPER signal plan shown on the chart; the original 9 confluence
     * items and fresh-price/news requirements are never changed. If a safe plan cannot
     * keep >=1.5R, the original is retained and [assess] blocks its entry.
     */
    fun withSafePlan(market: MarketState): MarketState {
        val signal = market.signal ?: return market
        if (!signal.isActionable || signal.entry == null || signal.stopLoss == null || signal.takeProfit == null)
            return market
        val snapshot = IctRangeAnalyzer.analyze(market.candles, market.interval)
        if (snapshot.barTime != signal.barTime || signal.interval != market.interval) return market
        val setup = when (signal.action) {
            SignalAction.BUY -> snapshot.buy
            SignalAction.SELL -> snapshot.sell
            SignalAction.NO_TRADE -> return market
        }
        if (!setup.ready) return market
        val boundaryStop = setup.proposedStop ?: return market
        val boundaryTarget = setup.opposingLevel ?: return market
        val stop = if (signal.action == SignalAction.BUY) minOf(signal.stopLoss, boundaryStop)
                   else maxOf(signal.stopLoss, boundaryStop)
        val target = if (signal.action == SignalAction.BUY) minOf(signal.takeProfit, boundaryTarget)
                     else maxOf(signal.takeProfit, boundaryTarget)
        val risk = abs(signal.entry - stop)
        val reward = abs(target - signal.entry)
        val aligned = if (signal.action == SignalAction.BUY) stop < signal.entry && target > signal.entry
                      else stop > signal.entry && target < signal.entry
        if (!aligned || !risk.isFinite() || !reward.isFinite() || risk <= 0.0 ||
            reward / risk !in 1.5..5.0 || stop <= 0.0 || target <= 0.0) return market
        return market.copy(signal = signal.copy(
            stopLoss = stop, takeProfit = target, riskReward = reward / risk,
            reasons = signal.reasons + "طرح SL بیرون جاروب و TP پیش از سطح مقابل رنج؛ فقط کاغذی",
        ))
    }

    /** With missing, discontinuous or invalid bars the default is BLOCK, never BUY. */
    fun assess(market: MarketState, now: Long = System.currentTimeMillis()): Decision {
        val signal = market.signal ?: return Decision("سیگنال موجود نیست")
        if (market.showingCachedData || market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING) ||
            market.feed.lastSuccessAt?.let { now - it in 0L..90_000L } != true)
            return Decision("گیت ICT: قیمت زنده/تازه در دسترس نیست")
        if (signal.action == SignalAction.NO_TRADE || signal.interval != market.interval ||
            signal.barTime != market.candles.lastOrNull { it.closed }?.time ||
            now - (signal.barTime + market.interval.millis) !in 0L..90_000L)
            return Decision("گیت ICT: سیگنالِ همین کندل بستهٔ تازه وجود ندارد")
        val snapshot = IctRangeAnalyzer.analyze(market.candles, market.interval)
        if (!snapshot.valid || snapshot.barTime != signal.barTime)
            return Decision("گیت ICT: کندل بستهٔ کافی/پیوسته و معتبر موجود نیست", snapshot)
        val range = snapshot.range ?: return Decision("گیت ICT: محدودهٔ دوطرفهٔ حمایت/مقاومت تأیید نشده", snapshot)
        val setup = if (signal.action == SignalAction.BUY) snapshot.buy else snapshot.sell
        if (!setup.ready) {
            val reason = when (setup.state) {
                IctRangeAnalyzer.State.WAIT_SWEEP -> "لمس حمایت/مقاومت یا وسط رنج، بدون جاروب و بازپس‌گیری کافی نیست"
                IctRangeAnalyzer.State.WAIT_MSS -> "تغییر ساختار و حرکت قوی پس از جاروب تأیید نشده"
                IctRangeAnalyzer.State.WAIT_FVG -> "شکاف ارزش منصفانهٔ سه‌کندلی FVG تأیید نشده"
                IctRangeAnalyzer.State.WAIT_RETEST -> "بازآزمایی ناحیه در لبهٔ رنج تأیید نشده"
                IctRangeAnalyzer.State.OUTSIDE_SESSION -> "بیرون پنجرهٔ لندن/نیویورک به وقت نیویورک"
                IctRangeAnalyzer.State.POOR_REWARD_RISK -> "فضای تا سطح مقابل نسبت به ریسک کمتر از ۱٫۵ است"
                else -> "محدوده شکسته یا الگوی رنج هنوز تأیید نشده"
            }
            return Decision("گیت ICT: $reason", snapshot, setup)
        }
        val price = market.lastPrice
        val stop = signal.stopLoss
        val target = signal.takeProfit
        val barClose = market.candles.lastOrNull { it.closed }?.close
        if (price == null || !price.isFinite() || stop == null || !stop.isFinite() ||
            target == null || !target.isFinite() || barClose == null ||
            abs(price - barClose) > 0.25 * range.atr)
            return Decision("گیت ICT: قیمت از بازآزمایی کندل بسته دور شده است", snapshot, setup)
        val stopLimit = setup.proposedStop ?: return Decision("گیت ICT: حدضررِ خارج جاروب محاسبه نشد", snapshot, setup)
        val targetLimit = setup.opposingLevel ?: return Decision("گیت ICT: سطح مقابل محاسبه نشد", snapshot, setup)
        val isBuy = signal.action == SignalAction.BUY
        val inEdge = if (isBuy) price >= range.support + 0.06 * range.atr &&
            price <= range.support + 0.45 * range.width
        else price <= range.resistance - 0.06 * range.atr &&
            price >= range.resistance - 0.45 * range.width
        if (!inEdge) return Decision("گیت ICT: قیمت در لبهٔ تأییدشدهٔ رنج نیست", snapshot, setup)
        val risk = if (isBuy) price - stop else stop - price
        val reward = if (isBuy) target - price else price - target
        val room = if (isBuy) targetLimit - price else price - targetLimit
        val beyondWick = if (isBuy) stop <= stopLimit else stop >= stopLimit
        val insideRangeTarget = if (isBuy) target <= targetLimit else target >= targetLimit
        if (!beyondWick || !insideRangeTarget || risk <= 0.0 || reward <= 0.0 ||
            !risk.isFinite() || !reward.isFinite() || reward / risk !in 1.5..5.0 ||
            room / risk < 1.5)
            return Decision("گیت ICT: SL باید بیرون جاروب و TP قبل سطح مقابل با حداقل ۱٫۵R باشد", snapshot, setup)
        return Decision(null, snapshot, setup)
    }

    /** Capture approved evidence from THIS quote/bar, not a recomputation after a restart. */
    fun approvedEvidence(market: MarketState, now: Long = System.currentTimeMillis()): IctPriceActionRecord? {
        val decision = assess(market, now)
        if (!decision.allowed) return null
        val snap = decision.snapshot ?: return null
        val range = snap.range ?: return null
        val setup = decision.setup ?: return null
        val fvg = setup.fvg ?: return null
        val window = snap.window ?: return null
        val signal = market.signal ?: return null
        val price = market.lastPrice ?: return null
        val stop = signal.stopLoss ?: return null
        val target = signal.takeProfit ?: return null
        val risk = if (signal.action == SignalAction.BUY) price - stop else stop - price
        val reward = if (signal.action == SignalAction.BUY) target - price else price - target
        val record = IctPriceActionRecord(
            symbol = market.symbol, interval = market.interval, barTime = snap.barTime ?: return null,
            action = signal.action, feedProvider = market.feed.provider, checkedAt = now,
            nyDate = window.date.toString(), nySession = window.session.name, nyTime = window.localTime,
            support = range.support, resistance = range.resistance, atr = range.atr,
            supportTouches = range.supportTouches, resistanceTouches = range.resistanceTouches,
            levelsConfirmedAt = range.confirmedAt, sweepAt = setup.sweepAt ?: return null,
            mssAt = setup.shiftAt ?: return null, fvgAt = fvg.at,
            fvgLow = fvg.low, fvgHigh = fvg.high,
            orderBlockLow = setup.orderBlock?.low, orderBlockHigh = setup.orderBlock?.high,
            retestAt = setup.retestAt ?: return null,
            quote = price, stop = stop, target = target,
            stopBoundary = setup.proposedStop ?: return null,
            opposingLevel = setup.opposingLevel ?: return null,
            rewardRisk = reward / risk,
        )
        return record.takeIf { it.matches(signal, market.symbol, price) }
    }
}
