package com.aurum.edge.data

import com.aurum.edge.core.V1Universe

/**
 * Strict 1-to-1 source routing:
 * Every symbol in the 15-symbol catalog comes from exactly one dedicated source,
 * and no two symbols share the same source. All history is fetched only on M5 (1500 candles),
 * while M15 and H1 are derived by resampling from M5.
 */
internal object SourceRouting {
    enum class DedicatedSource(
        val symbol: String,
        val title: String,
        val historySource: HistorySource?,
        val cryptoVenue: CryptoRadarCandleClient.Venue?,
    ) {
        DUKASCOPY_XAU("XAU/USD", "Dukascopy Swiss · XAU/USD", HistorySource.DUKASCOPY_XAU, null),
        DUKASCOPY_XAG("XAG/USD", "Dukascopy Metal · XAG/USD", HistorySource.DUKASCOPY_XAG, null),
        YAHOO_DOW("DOW", "Yahoo Index · ^DJI (DOW)", HistorySource.YAHOO_DOW, null),
        YAHOO_EURUSD("EUR/USD", "Yahoo FX · EUR/USD", HistorySource.YAHOO_EURUSD, null),
        DUKASCOPY_GBPUSD("GBP/USD", "Dukascopy FX · GBP/USD", HistorySource.DUKASCOPY_GBPUSD, null),
        YAHOO_USDJPY("USD/JPY", "Yahoo FX · USD/JPY", HistorySource.YAHOO_USDJPY, null),
        DUKASCOPY_EURJPY("EUR/JPY", "Dukascopy Cross · EUR/JPY", HistorySource.DUKASCOPY_EURJPY, null),
        YAHOO_GBPJPY("GBP/JPY", "Yahoo Cross · GBP/JPY", HistorySource.YAHOO_GBPJPY, null),
        DUKASCOPY_AUDUSD("AUD/USD", "Dukascopy FX · AUD/USD", HistorySource.DUKASCOPY_AUDUSD, null),
        YAHOO_USDCHF("USD/CHF", "Yahoo FX · USD/CHF", HistorySource.YAHOO_USDCHF, null),
        BINANCE_BTC("BTC/USDT", "Binance Spot · BTC/USDT", null, CryptoRadarCandleClient.Venue.BINANCE),
        OKX_ETH("ETH/USDT", "OKX Spot · ETH/USDT", null, CryptoRadarCandleClient.Venue.OKX),
        GATE_SOL("SOL/USDT", "Gate.io Spot · SOL/USDT", null, CryptoRadarCandleClient.Venue.GATE),
        MEXC_BNB("BNB/USDT", "MEXC Spot · BNB/USDT", null, CryptoRadarCandleClient.Venue.MEXC),
        KRAKEN_XRP("XRP/USDT", "Kraken Spot · XRP/USDT", null, CryptoRadarCandleClient.Venue.KRAKEN),
    }

    enum class HistorySource(
        val symbol: String?,
        val providerLabel: String,
        val usesYahoo: Boolean = false,
        val usesDukascopy: Boolean = false,
        val usesTwelveData: Boolean = false,
    ) {
        DUKASCOPY_XAU("XAU/USD", "Dukascopy Swiss · XAU/USD", usesDukascopy = true),
        DUKASCOPY_XAG("XAG/USD", "Dukascopy Metal · XAG/USD", usesDukascopy = true),
        YAHOO_DOW("DOW", "Yahoo Index · ^DJI", usesYahoo = true),
        YAHOO_EURUSD("EUR/USD", "Yahoo FX · EUR/USD", usesYahoo = true),
        DUKASCOPY_GBPUSD("GBP/USD", "Dukascopy FX · GBP/USD", usesDukascopy = true),
        YAHOO_USDJPY("USD/JPY", "Yahoo FX · USD/JPY", usesYahoo = true),
        DUKASCOPY_EURJPY("EUR/JPY", "Dukascopy Cross · EUR/JPY", usesDukascopy = true),
        YAHOO_GBPJPY("GBP/JPY", "Yahoo Cross · GBP/JPY", usesYahoo = true),
        DUKASCOPY_AUDUSD("AUD/USD", "Dukascopy FX · AUD/USD", usesDukascopy = true),
        YAHOO_USDCHF("USD/CHF", "Yahoo FX · USD/CHF", usesYahoo = true),
        // Legacy adapter enum values retained for test/helper compatibility
        YAHOO(null, "Yahoo Finance", usesYahoo = true),
        DUKASCOPY(null, "Dukascopy", usesDukascopy = true),
        TWELVE_DATA(null, "Twelve Data", usesTwelveData = true);
    }

    private val bySymbol: Map<String, DedicatedSource> =
        DedicatedSource.entries.associateBy { it.symbol }

    fun dedicatedFor(symbol: String): DedicatedSource? =
        bySymbol[V1Universe.normalize(symbol)]

    fun cryptoVenueFor(symbol: String): CryptoRadarCandleClient.Venue? =
        dedicatedFor(symbol)?.cryptoVenue

    fun historySources(symbol: String, keyed: Boolean = false): List<HistorySource> {
        val dedicated = dedicatedFor(symbol) ?: return emptyList()
        return listOfNotNull(dedicated.historySource)
    }

    fun fromProvider(provider: String?): HistorySource? {
        if (provider == null) return null
        DedicatedSource.entries.firstOrNull {
            it.historySource != null && provider.contains(it.symbol, ignoreCase = true)
        }?.historySource?.let { return it }
        return when {
            provider.startsWith("Yahoo Finance") || provider.startsWith("Yahoo") -> HistorySource.YAHOO
            provider.startsWith("Dukascopy") -> HistorySource.DUKASCOPY
            provider.startsWith("Twelve Data") -> HistorySource.TWELVE_DATA
            else -> null
        }
    }

    fun matchesSource(provider: String?, source: HistorySource): Boolean {
        if (provider == null) return false
        return when {
            source.usesYahoo -> provider.startsWith("Yahoo")
            source.usesDukascopy -> provider.startsWith("Dukascopy")
            source.usesTwelveData -> provider.startsWith("Twelve Data")
            else -> false
        }
    }

    fun historyPlan(symbol: String, keyed: Boolean = false): String {
        val dedicated = dedicatedFor(symbol)
            ?: return "منبع تاریخچهٔ دقیق M5 تعریف نشده؛ بررسی فقط خطا نشان می‌دهد"
        return "منبع اختصاصی تک‌سورس M5 (۱۵۰۰ کندل): ${dedicated.title} · تبدیل M15 و H1 از کندل ۵ دقیقه"
    }
}
