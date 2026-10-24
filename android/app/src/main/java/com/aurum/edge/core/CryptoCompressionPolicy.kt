package com.aurum.edge.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** Explicit paper-entry safeguards for the new crypto-only compression strategy.
 * London and New York DST are evaluated by their own IANA clocks; no Asian session.
 * A trade print alone is NOT an observable spread; missing real bid/ask fails closed.
 */
object CryptoCompressionPolicy {
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private val start = LocalTime.of(8, 0)
    private val end = LocalTime.of(17, 0)

    fun londonOrNewYork(at: Long): Boolean {
        if (at <= 0L) return false
        val instant = Instant.ofEpochMilli(at)
        return listOf(london, newYork).any { zone ->
            val time = instant.atZone(zone)
            time.dayOfWeek.value <= 5 && !time.toLocalTime().isBefore(start) &&
                time.toLocalTime().isBefore(end)
        }
    }

    fun quoteBlocker(signal: Signal, quote: PriceTick?, now: Long = System.currentTimeMillis()): String? {
        val proof = signal.cryptoBreakout ?: return "شواهد شکست رمزارز موجود نیست"
        if (!proof.matches(signal) || signal.ichimokuEntry?.matches(signal) != true)
            return "کراس، فشردگی یا طرح ۱:۳ رمزارز معتبر نیست"
        if (quote == null || !TradeQuotePolicy.accepts(quote, now) ||
            !listOfNotNull(quote.bid, quote.ask).all { it.isFinite() } ||
            quote.bid == null || quote.ask == null || quote.bid <= 0 || quote.ask < quote.bid)
            return "اسپرد واقعی و تیک تازهٔ هم‌صرافی تأیید نشده است"
        if (quote.spread == null || quote.spread!! >= 0.1 * proof.atr)
            return "اسپرد نماد بیشتر از ۰٫۱ ATR است"
        if (signal.entry?.let { abs(quote.price - it) <= 0.25 * proof.atr } != true)
            return "تیک ورود از کندل شکست بیش از ۰٫۲۵ ATR فاصله گرفته است"
        if (!londonOrNewYork(now)) return "ورود رمزارز فقط در سشن لندن یا نیویورک مجاز است"
        return null
    }
}
