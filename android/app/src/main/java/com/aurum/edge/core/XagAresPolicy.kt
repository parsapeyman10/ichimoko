package com.aurum.edge.core

import kotlin.math.abs

/**
 * ALS-5S entry safeguards for silver paper entries.
 *
 * Silver is quoted as spot/CFD ounces with its own contract, tick and spread profile, so this policy
 * deliberately does **not** reuse gold's numbers:
 *  - the contract comes from [VenueSpecs] (COMEX SI 5,000 oz, tick 0.001), never from a hardcoded
 *    "1 lot = 100 oz" assumption;
 *  - the spread cap is a fraction of the *live* ATR, because a fixed cent cap is meaningless when
 *    ATR is 5¢ one week and 40¢ the next;
 *  - the round trip must leave a positive edge: both R:R after costs and cost ÷ risk are gated;
 *  - a trade print is not a spread: a two-sided quote from the same publisher is required.
 *
 * Every refusal carries a reason, because a silent block is indistinguishable from a bug on a phone.
 */
object XagAresPolicy {
    const val SYMBOL = "XAG/USD"
    const val STRATEGY = "ALS-5S"

    /** Live spread cap as a fraction of M5 ATR. Silver spreads widen fast; 12% is the shipped cap. */
    const val MAX_SPREAD_ATR_FRACTION = 0.12

    /** How far the live price may drift from the confirmed entry before the setup is stale. */
    const val MAX_ENTRY_DRIFT_ATR_FRACTION = 0.30

    /** Round-trip cost may not exceed this share of the stop distance. */
    const val MAX_COST_TO_RISK_FRACTION = 0.30

    /** Minimum R:R measured *after* the round trip. */
    const val MIN_NET_REWARD_RISK = 1.5

    fun appliesTo(symbol: String): Boolean = V1Universe.normalize(symbol) == SYMBOL

    /**
     * Round-trip cost for one ounce, in USD: 2 × commission + spread + slippage.
     * [spreadPerOz] must be the observed ask−bid; when it is unknown the venue's nominal spread is
     * used for *reporting only* — [quoteBlocker] still refuses to trade an unknown live spread.
     */
    fun roundTripCostPerOz(price: Double, spreadPerOz: Double?, slippageBps: Double = 2.0): Double {
        if (!price.isFinite() || price <= 0.0) return Double.NaN
        val venue = VenueSpecs.of(SYMBOL)
        val spread = spreadPerOz?.takeIf { it.isFinite() && it >= 0.0 } ?: venue.spreadPrice
        val commission = 2.0 * venue.commissionPerUnit
        val slippage = price * slippageBps / 10_000.0 * 2.0
        return commission + spread + slippage
    }

    /** R:R after the round trip. Negative when the costs are larger than the whole reward. */
    fun netRewardRisk(entry: Double, stop: Double, target: Double, costPerOz: Double): Double {
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() || !costPerOz.isFinite())
            return Double.NaN
        val risk = abs(entry - stop) + costPerOz
        if (risk <= 0.0) return Double.NaN
        return (abs(target - entry) - costPerOz) / risk
    }

    /**
     * Position size in ounces from equity, permitted risk, the structural stop and the round-trip
     * cost, capped by a volatility-aware notional limit so an extreme-ATR day cannot open a position
     * whose notional dwarfs the account. Rounds **down** to the venue quantity step.
     */
    fun positionOz(
        entry: Double,
        stop: Double,
        equityUsd: Double,
        riskPct: Double,
        costPerOz: Double,
        qtyStepOz: Double = 0.01,
        minNotionalUsd: Double = 5.0,
        maxNotionalToEquity: Double = 5.0,
    ): Pair<Double, Double> {
        if (!entry.isFinite() || !stop.isFinite() || entry <= 0.0 || stop <= 0.0 || entry == stop)
            return 0.0 to 0.0
        if (!equityUsd.isFinite() || equityUsd <= 0.0 || !riskPct.isFinite() || riskPct <= 0.0)
            return 0.0 to 0.0
        if (!costPerOz.isFinite() || costPerOz < 0.0) return 0.0 to 0.0
        val riskPerOz = abs(entry - stop) + costPerOz
        if (riskPerOz <= 0.0) return 0.0 to 0.0
        val riskUsd = equityUsd * riskPct / 100.0
        var oz = riskUsd / riskPerOz
        val capOz = equityUsd * maxNotionalToEquity / entry
        if (oz > capOz) oz = capOz
        oz = kotlin.math.floor(oz / qtyStepOz) * qtyStepOz
        oz = kotlin.math.round(oz * 100.0) / 100.0
        if (oz <= 0.0 || oz * entry < minNotionalUsd) return 0.0 to 0.0
        return oz to oz * riskPerOz
    }

    fun quoteBlocker(
        signal: Signal,
        quote: PriceTick?,
        atr: Double?,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val record = signal.als5s ?: return "ALS-5S: شواهد موتور نقره همراه سیگنال نیست"
        if (!appliesTo(record.symbol)) return "ALS-5S فقط برای XAG/USD است"
        if (!record.matches(signal)) return "ALS-5S: شماره‌های سیگنال با گزارش موتور یکی نیست"
        if (atr == null || !atr.isFinite() || atr <= 0.0) return "ALS-5S: ATR معتبر نیست"
        if (quote == null || !TradeQuotePolicy.accepts(quote, now)) return "ALS-5S: تیک تازه نیست"
        val bid = quote.bid
        val ask = quote.ask
        if (bid == null || ask == null || !bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid)
            return "ALS-5S: قیمت دوطرفهٔ واقعی (bid/ask) موجود نیست؛ تیک معامله اسپرد نیست"
        val spread = ask - bid
        if (spread > MAX_SPREAD_ATR_FRACTION * atr)
            return "ALS-5S: اسپرد (${"%.3f".format(java.util.Locale.US, spread)}) بیش از " +
                "${(MAX_SPREAD_ATR_FRACTION * 100).toInt()}٪ ATR است"
        val entry = signal.entry ?: return "ALS-5S: ورود مشخص نیست"
        if (abs(quote.price - entry) > MAX_ENTRY_DRIFT_ATR_FRACTION * atr)
            return "ALS-5S: قیمت از ورود تأییدشده بیش از " +
                "${(MAX_ENTRY_DRIFT_ATR_FRACTION * 100).toInt()}٪ ATR فاصله گرفته"
        if (record.costToRiskFraction > MAX_COST_TO_RISK_FRACTION)
            return "ALS-5S: هزینهٔ رفت‌وبرگشت ${"%.0f".format(record.costToRiskFraction * 100)}٪ " +
                "ریسک است؛ بیش از سقف ${(MAX_COST_TO_RISK_FRACTION * 100).toInt()}٪"
        if ((record.netRewardRisk ?: 0.0) < MIN_NET_REWARD_RISK)
            return "ALS-5S: R:R خالص کمتر از ${MIN_NET_REWARD_RISK} است"
        return null
    }
}
