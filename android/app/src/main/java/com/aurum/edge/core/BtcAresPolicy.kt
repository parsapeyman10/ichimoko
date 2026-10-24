package com.aurum.edge.core

import kotlin.math.abs

/**
 * Paper-entry safeguards for the BTC-ARES channel.
 *
 * The compression strategy is still the only entry model for the other crypto pairs, so this
 * policy exists separately instead of loosening [CryptoCompressionPolicy] for everyone:
 *  - the BTC record must match the signal it travels with (engine, level, stop, target, bar);
 *  - a fresh, same-venue, two-sided quote must exist and its spread must stay small relative to
 *    ATR (a trade print is not a spread);
 *  - the quote must not have run away from the confirmed entry;
 *  - BTC trades 24/7, so no session window is imposed; the session is a *feature*, not a gate.
 *
 * Every rejection returns the reason, because "blocked without a reason" is indistinguishable
 * from a bug on the phone.
 */
object BtcAresPolicy {
    const val SYMBOL = "BTC/USDT"

    /** Real spread cap as a fraction of ATR; above this the setup is not worth its costs. */
    const val MAX_SPREAD_ATR_FRACTION = 0.10

    /** How far the live price may drift from the confirmed entry before the setup is stale. */
    const val MAX_ENTRY_DRIFT_ATR_FRACTION = 0.30

    fun appliesTo(symbol: String): Boolean = V1Universe.normalize(symbol) == SYMBOL

    fun quoteBlocker(
        signal: Signal,
        quote: PriceTick?,
        atr: Double?,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val record = signal.btcAres ?: return "BTC-ARES: شواهد موتور بیت‌کوین همراه سیگنال نیست"
        if (!appliesTo(record.symbol)) return "BTC-ARES فقط برای BTC/USDT است"
        if (!record.matches(signal)) return "BTC-ARES: شماره‌های سیگنال با گزارش موتور یکی نیست"
        if (atr == null || !atr.isFinite() || atr <= 0.0) return "BTC-ARES: ATR معتبر نیست"
        if (quote == null || !TradeQuotePolicy.accepts(quote, now)) return "BTC-ARES: تیک تازه نیست"
        val bid = quote.bid
        val ask = quote.ask
        if (bid == null || ask == null || !bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid)
            return "BTC-ARES: قیمت دوطرفهٔ واقعی (bid/ask) موجود نیست؛ تیک معامله اسپرد نیست"
        val spread = ask - bid
        if (spread > MAX_SPREAD_ATR_FRACTION * atr)
            return "BTC-ARES: اسپرد (${"%.2f".format(spread)}) بیش از ${(MAX_SPREAD_ATR_FRACTION * 100).toInt()}٪ ATR است"
        val entry = signal.entry ?: return "BTC-ARES: ورود مشخص نیست"
        if (abs(quote.price - entry) > MAX_ENTRY_DRIFT_ATR_FRACTION * atr)
            return "BTC-ARES: قیمت از ورود تأییدشده بیش از ${(MAX_ENTRY_DRIFT_ATR_FRACTION * 100).toInt()}٪ ATR فاصله گرفته"
        return null
    }
}
