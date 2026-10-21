package com.aurum.edge.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.floor

/**
 * EUR-ARES execution policy for EUR/USD paper entries.
 *
 * Everything is in **pips**, because EUR/USD's whole edge is decided at that scale: on an M5 bar
 * the ATR is 2–5 pips, the raw spread is 0.0–0.3 pip, and $3.50/side per 100k lot is 0.7 pip round
 * trip. A model that stores costs as "a percentage of price" rounds that to zero.
 *
 * Non-negotiables:
 *  - a real two-sided quote (bid and ask) is required; a last-trade print is not a spread;
 *  - the spread cap is the *tighter* of an absolute pip cap and a fraction of the M5 ATR, because
 *    1.5 pips is normal on a news peak and catastrophic on a 3-pip-ATR afternoon;
 *  - cost ÷ stop distance is capped: when the round trip eats the setup, the setup is refused
 *    rather than made smaller;
 *  - the rollover window (17:00 New York) blocks new entries: spreads widen and swaps are booked;
 *  - swap is never invented. Unknown financing is reported as unknown, and 0.0 is labelled as such.
 */
object EurAresPolicy {
    const val SYMBOL = "EUR/USD"
    const val STRATEGY = "EUR-ARES"

    const val PIP = EurPips.PIP
    const val PIPETTE = EurPips.PIPETTE

    /** One standard lot: 100,000 base units. Pip value in USD = 10.0 for a USD-quoted pair. */
    const val UNITS_PER_LOT = 100_000.0
    const val PIP_VALUE_USD_PER_LOT = 10.0
    const val LOT_STEP = 0.01
    const val MIN_UNITS = 1_000.0

    /** Absolute spread cap, in pips. */
    const val MAX_SPREAD_PIPS = 1.5

    /** …and the same cap as a fraction of the M5 ATR. The tighter of the two wins. */
    const val MAX_SPREAD_ATR_FRACTION = 0.25

    /** Round-trip cost may not exceed this share of the stop distance. */
    const val MAX_COST_TO_RISK_FRACTION = 0.30

    /** Minimum R:R measured *after* the round trip. */
    const val MIN_NET_REWARD_RISK = 1.5

    /** A stop tighter than this is inside ordinary EUR/USD noise and is refused. */
    const val MIN_STOP_PIPS = 4.0
    const val MAX_STOP_PIPS = 40.0

    /** How far the live price may drift from the confirmed entry before the setup is stale. */
    const val MAX_ENTRY_DRIFT_ATR_FRACTION = 0.30

    /** Rollover: the first minutes after 17:00 in New York, when swaps are booked. */
    const val ROLLOVER_MINUTES_AFTER_5PM = 10

    private val LONDON: ZoneId = ZoneId.of("Europe/London")
    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    private val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")

    fun appliesTo(symbol: String): Boolean = V1Universe.normalize(symbol) == SYMBOL

    /** Local clocks at [nowMs]; used both by the engine and by the tests (DST is a real bug class). */
    fun clocks(nowMs: Long): Triple<ZonedDateTime, ZonedDateTime, ZonedDateTime> {
        val instant = Instant.ofEpochMilli(nowMs)
        return Triple(
            instant.atZone(LONDON),
            instant.atZone(NEW_YORK),
            instant.atZone(TOKYO),
        )
    }

    /**
     * EUR/USD session from the *local* clocks, so daylight-saving changes move the boundaries with
     * the market instead of leaving the overlap mislabelled for half the year.
     *
     *  - ASIA     : Tokyo 09:00–18:00 (JST has no DST)
     *  - LONDON   : London 08:00–17:00
     *  - OVERLAP  : London 13:00–17:00 ∩ New York 08:00–17:00
     *  - NEW_YORK : New York 08:00–17:00 outside the overlap
     *  - ROLLOVER : New York 17:00 for the first [ROLLOVER_MINUTES_AFTER_5PM] minutes
     */
    fun sessionAt(nowMs: Long): EurAresSessionInfo {
        val (london, ny, tokyo) = clocks(nowMs)
        val lh = london.hour
        val nh = ny.hour
        val th = tokyo.hour
        val inLondon = lh in 8..16
        val inNewYork = nh in 8..16
        val inTokyo = th in 9..17
        val rollover = nh == 17 && ny.minute < ROLLOVER_MINUTES_AFTER_5PM
        val session = when {
            rollover -> "ROLLOVER"
            inLondon && inNewYork -> "OVERLAP"
            inLondon -> "LONDON"
            inNewYork -> "NEW_YORK"
            inTokyo -> "ASIA"
            else -> "OFF"
        }
        return EurAresSessionInfo(
            session = session,
            sessionLabelFa = sessionLabel(session),
            londonClock = "%02d:%02d".format(lh, london.minute),
            newYorkClock = "%02d:%02d".format(nh, ny.minute),
            rolloverWindow = rollover,
            sessionVolRatio = 1.0,
        )
    }

    fun sessionLabel(session: String): String = when (session) {
        "ASIA" -> "آسیا (نقدینگی کم)"
        "LONDON" -> "لندن"
        "OVERLAP" -> "هم‌پوشانی لندن/نیویورک"
        "NEW_YORK" -> "نیویورک"
        "ROLLOVER" -> "پنجرهٔ رول‌اور ۱۷:۰۰ نیویورک"
        else -> "خارج از سشن اصلی"
    }

    /** Sessions where EUR/USD liquidity is deep enough for a cost-aware M5 entry. */
    fun isTradableSession(session: String): Boolean =
        session == "LONDON" || session == "OVERLAP" || session == "NEW_YORK"

    /**
     * Round-trip cost in pips: **spread + 2 × commission + 2 × slippage**.
     * [spreadPips] must be the observed ask−bid; when it is unknown the venue's nominal spread is
     * used for *reporting only* — [quoteBlocker] still refuses to trade an unknown live spread.
     */
    fun roundTripCostPips(
        price: Double,
        spreadPips: Double?,
        commissionPerUnit: Double = VenueSpecs.of(SYMBOL).commissionPerUnit,
        slippagePips: Double = 0.2,
    ): Double {
        if (!price.isFinite() || price <= 0.0) return Double.NaN
        val spread = spreadPips?.takeIf { it.isFinite() && it >= 0.0 }
            ?: (VenueSpecs.of(SYMBOL).spreadPrice / PIP)
        val commission = 2.0 * commissionPerUnit / PIP
        val slippage = 2.0 * slippagePips
        return spread + commission + slippage
    }

    /** Opening cost only (one side of the spread + one commission + one slippage). */
    fun entryCostPips(
        price: Double,
        spreadPips: Double?,
        commissionPerUnit: Double = VenueSpecs.of(SYMBOL).commissionPerUnit,
        slippagePips: Double = 0.2,
    ): Double {
        val total = roundTripCostPips(price, spreadPips, commissionPerUnit, slippagePips)
        return if (total.isFinite()) total / 2.0 else Double.NaN
    }

    /** R:R after the round trip. Negative when the costs are larger than the whole reward. */
    fun netRewardRisk(entry: Double, stop: Double, target: Double, costPips: Double): Double {
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() || !costPips.isFinite())
            return Double.NaN
        val riskPips = abs(entry - stop) / PIP + costPips
        if (riskPips <= 0.0) return Double.NaN
        val rewardPips = abs(target - entry) / PIP - costPips
        return rewardPips / riskPips
    }

    /**
     * Position size in standard lots from equity, permitted risk, the structural stop (pips) and
     * the round-trip cost. Never a constant lot size, and never scaled up to meet a broker minimum:
     * a size below the minimum is a refusal, not a bigger risk.
     */
    fun positionLots(
        entry: Double,
        stopPips: Double,
        equityUsd: Double,
        riskPct: Double,
        costPips: Double,
        pipValueUsdPerLot: Double = PIP_VALUE_USD_PER_LOT,
        lotStep: Double = LOT_STEP,
        minUnits: Double = MIN_UNITS,
        maxLeverage: Int = 30,
    ): Triple<Double, Double, Double> {
        if (!entry.isFinite() || entry <= 0.0) return Triple(0.0, 0.0, 0.0)
        if (!stopPips.isFinite() || stopPips <= 0.0) return Triple(0.0, 0.0, 0.0)
        if (!equityUsd.isFinite() || equityUsd <= 0.0) return Triple(0.0, 0.0, 0.0)
        if (!riskPct.isFinite() || riskPct <= 0.0) return Triple(0.0, 0.0, 0.0)
        if (!costPips.isFinite() || costPips < 0.0) return Triple(0.0, 0.0, 0.0)
        if (!pipValueUsdPerLot.isFinite() || pipValueUsdPerLot <= 0.0) return Triple(0.0, 0.0, 0.0)
        val riskPerLot = (stopPips + costPips) * pipValueUsdPerLot
        if (riskPerLot <= 0.0) return Triple(0.0, 0.0, 0.0)
        val riskUsd = equityUsd * riskPct / 100.0
        var lots = riskUsd / riskPerLot
        val maxLots = equityUsd * maxLeverage / (entry * UNITS_PER_LOT)
        if (lots > maxLots) lots = maxLots
        lots = floor(lots / lotStep) * lotStep
        lots = kotlin.math.round(lots * 100.0) / 100.0
        val units = lots * UNITS_PER_LOT
        if (lots <= 0.0 || units < minUnits) return Triple(0.0, 0.0, 0.0)
        val actualRisk = lots * riskPerLot
        return Triple(lots, units, actualRisk)
    }

    /**
     * Entry gate. Returns a Persian reason, or null when the setup may be opened.
     * Every refusal is explicit: a silent block is indistinguishable from a bug on a phone.
     */
    fun quoteBlocker(
        signal: Signal,
        quote: PriceTick?,
        atr: Double?,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val record = signal.eurAres ?: return "EUR-ARES: شواهد موتور یورو/دلار همراه سیگنال نیست"
        if (!appliesTo(record.symbol)) return "EUR-ARES فقط برای EUR/USD است"
        if (!record.matches(signal)) return "EUR-ARES: شماره‌های سیگنال با گزارش موتور یکی نیست"
        if (atr == null || !atr.isFinite() || atr <= 0.0) return "EUR-ARES: ATR معتبر نیست"
        if (quote == null || !TradeQuotePolicy.accepts(quote, now)) return "EUR-ARES: تیک تازه نیست"
        val bid = quote.bid
        val ask = quote.ask
        if (bid == null || ask == null || !bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid)
            return "EUR-ARES: قیمت دوطرفهٔ واقعی (bid/ask) موجود نیست؛ تیک معامله اسپرد نیست"
        val spreadPips = (ask - bid) / PIP
        val atrPips = atr / PIP
        val spreadCap = minOf(MAX_SPREAD_PIPS, MAX_SPREAD_ATR_FRACTION * atrPips)
        if (spreadPips > spreadCap + 1e-9)
            return "EUR-ARES: اسپرد ${EurPips.format(spreadPips)} پیپ از سقف " +
                "${EurPips.format(spreadCap)} پیپ (${(MAX_SPREAD_ATR_FRACTION * 100).toInt()}٪ ATR) بیشتر است"
        val entry = signal.entry ?: return "EUR-ARES: ورود مشخص نیست"
        if (abs(quote.price - entry) > MAX_ENTRY_DRIFT_ATR_FRACTION * atr)
            return "EUR-ARES: قیمت از ورود تأییدشده بیش از " +
                "${(MAX_ENTRY_DRIFT_ATR_FRACTION * 100).toInt()}٪ ATR فاصله گرفته"
        if (record.costToRiskFraction > MAX_COST_TO_RISK_FRACTION + 1e-9)
            return "EUR-ARES: هزینهٔ رفت‌وبرگشت ${"%.0f".format(record.costToRiskFraction * 100)}٪ " +
                "حد ضرر است؛ بیش از سقف ${(MAX_COST_TO_RISK_FRACTION * 100).toInt()}٪"
        if ((record.netRewardRisk ?: 0.0) < MIN_NET_REWARD_RISK)
            return "EUR-ARES: R:R خالص کمتر از $MIN_NET_REWARD_RISK است"
        val session = sessionAt(now)
        if (session.rolloverWindow)
            return "EUR-ARES: پنجرهٔ رول‌اور نیویورک است؛ اسپرد و سواپ در حال تسویه‌اند"
        if (!isTradableSession(session.session))
            return "EUR-ARES: سشن ${session.sessionLabelFa} برای ورود مجاز نیست"
        return null
    }
}
