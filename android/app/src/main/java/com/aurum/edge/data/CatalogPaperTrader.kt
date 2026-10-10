package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only markets for which the existing open-position monitor can also fetch SAME-identity,
 * timestamped trade ticks. No automatic entries on Yahoo snapshots, Nobitex candle closes,
 * unverified Twelve WS subscriptions or futures substituted for spot.
 */
object CatalogQuotePolicy {
    fun tickMonitored(symbol: String): Boolean = symbol in WatchCatalog.scannerSymbols &&
        (AssetClass.of(symbol) == AssetClass.FOREX || symbol in setOf("XAU/USD", "XAG/USD"))

    fun rejection(symbol: String, tick: PriceTick?, now: Long = System.currentTimeMillis()): String? = when {
        !tickMonitored(symbol) -> "فید تیک تازهٔ هم‌نماد برای ورود و پایش خروج این بازار تعریف نشده است"
        tick == null -> "فید تیک تازهٔ هم‌نماد پاسخ نداد"
        !TradeQuotePolicy.accepts(tick, now) -> "پاسخ، کندل تاریخی یا تیک دیررس است؛ معامله ممنوع"
        MarketHours.closedFor(symbol, now) -> "بازار این نماد بسته است"
        else -> null
    }
}

data class CatalogAutoReview(val trade: PaperTrade? = null, val reason: String? = null)

/** The scanner's *closed-bar* signal is only a trigger to fetch a NEW, independently
 * timestamped tick. Actual authorization and atomic portfolio limits stay in PaperAutoTrader
 * and JournalStore; failed/missing ticks remain read-only radar observations.
 */
class CatalogPaperTrader(
    private val settings: SettingsStore,
    private val trader: PaperAutoTrader,
    private val spot: SpotFallbackClient = SpotFallbackClient(),
) {
    suspend fun review(scanned: MarketState): CatalogAutoReview {
        val config = settings.read()
        if (!config.autoPaperTrading || scanned.symbol == config.symbol ||
            scanned.symbol !in WatchCatalog.scannerSymbols) return CatalogAutoReview()
        if (!CatalogQuotePolicy.tickMonitored(scanned.symbol))
            return CatalogAutoReview(reason = CatalogQuotePolicy.rejection(scanned.symbol, null))
        val tick = try {
            withContext(Dispatchers.IO) { spot.fetchQuote(scanned.symbol) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            return CatalogAutoReview(reason = "قیمت مستقل ${scanned.symbol} دریافت نشد: " +
                (error.message ?: "خطای فید").take(110))
        }
        CatalogQuotePolicy.rejection(scanned.symbol, tick)?.let { return CatalogAutoReview(reason = it) }
        val verified = scanned.copy(
            lastPrice = tick.price, bid = tick.bid, ask = tick.ask,
            // Provider timestamp, NOT scanner download time. This is the same tick contract
            // used for settling all non-crypto open positions in the service/UI.
            feed = FeedStatus(FeedMode.LIVE, "تیک مستقل تأییدشده", tick.at,
                provider = "Swissquote / Gold-API"),
            showingCachedData = false,
        )
        val opened = trader.onMarketUpdate(verified, catalogQuote = tick)
        return if (opened != null) CatalogAutoReview(trade = opened)
            else CatalogAutoReview(reason = trader.status.value)
    }
}
