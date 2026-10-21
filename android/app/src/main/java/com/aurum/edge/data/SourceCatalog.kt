package com.aurum.edge.data

/**
 * 1-to-1 dedicated public watch sources:
 * Every symbol in WatchCatalog has its own unique SourceDef so that no two symbols
 * share the same sourceId or block each other.
 */
object SourceCatalog {
    val goldApiXau = SourceDef(
        id = "gold_api_xau",
        title = "Gold-API · طلای جهانی (XAU/USD)",
        subtitle = "منبع اختصاصی انس طلا، بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://api.gold-api.com/price/{symbol}",
        pricePath = "price",
        changeMode = ChangeMode.NONE,
        timestampPath = "updatedAt",
        timestampMode = SourceTime.UTC_DATETIME,
        unit = "$",
        symbols = listOf(SymbolDef("XAU", "طلای جهانی (هر انس)")),
    )

    val goldApiXag = SourceDef(
        id = "gold_api_xag",
        title = "Gold-API · نقرهٔ جهانی (XAG/USD)",
        subtitle = "منبع اختصاصی انس نقره، بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://api.gold-api.com/price/{symbol}",
        pricePath = "price",
        changeMode = ChangeMode.NONE,
        timestampPath = "updatedAt",
        timestampMode = SourceTime.UTC_DATETIME,
        unit = "$",
        symbols = listOf(SymbolDef("XAG", "نقرهٔ جهانی (هر انس)")),
    )

    val yahooDow = SourceDef(
        id = "yahoo_dow",
        title = "Yahoo Index · شاخص داوجونز (DOW)",
        subtitle = "منبع اختصاصی شاخص داوجونز آمریکا بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=5m&range=5d&includePrePost=false",
        pricePath = "chart.result[0].meta.regularMarketPrice",
        changePath = "chart.result[0].meta.chartPreviousClose",
        changeMode = ChangeMode.PREV_CLOSE,
        sparkPath = "chart.result[0].indicators.quote[0].close",
        volumePath = "chart.result[0].indicators.quote[0].volume",
        timestampPath = "chart.result[0].meta.regularMarketTime",
        timestampMode = SourceTime.UNIX_SECONDS,
        unit = "$",
        symbols = listOf(SymbolDef("^DJI", "شاخص داوجونز آمریکا")),
    )

    val yahooEurusd = SourceDef(
        id = "yahoo_eurusd",
        title = "Yahoo FX · EUR/USD",
        subtitle = "منبع اختصاصی یورو / دلار بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=5m&range=1d&includePrePost=false",
        pricePath = "chart.result[0].meta.regularMarketPrice",
        changePath = "chart.result[0].meta.chartPreviousClose",
        changeMode = ChangeMode.PREV_CLOSE,
        sparkPath = "chart.result[0].indicators.quote[0].close",
        volumePath = "chart.result[0].indicators.quote[0].volume",
        timestampPath = "chart.result[0].meta.regularMarketTime",
        timestampMode = SourceTime.UNIX_SECONDS,
        unit = "$",
        symbols = listOf(SymbolDef("EURUSD=X", "یورو / دلار")),
    )

    val swissquoteGbpusd = SourceDef(
        id = "swissquote_gbpusd",
        title = "Swissquote BBO · GBP/USD",
        subtitle = "منبع اختصاصی پوند / دلار",
        kind = SourceKind.SPOT_BBO_QUOTE,
        urlTemplate = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/{symbol}",
        unit = "$",
        symbols = listOf(SymbolDef("GBP/USD", "پوند / دلار")),
    )

    val yahooUsdjpy = SourceDef(
        id = "yahoo_usdjpy",
        title = "Yahoo FX · USD/JPY",
        subtitle = "منبع اختصاصی دلار / ین ژاپن بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://query2.finance.yahoo.com/v8/finance/chart/{symbol}?interval=5m&range=1d&includePrePost=false",
        pricePath = "chart.result[0].meta.regularMarketPrice",
        changePath = "chart.result[0].meta.chartPreviousClose",
        changeMode = ChangeMode.PREV_CLOSE,
        sparkPath = "chart.result[0].indicators.quote[0].close",
        volumePath = "chart.result[0].indicators.quote[0].volume",
        timestampPath = "chart.result[0].meta.regularMarketTime",
        timestampMode = SourceTime.UNIX_SECONDS,
        unit = "JPY",
        symbols = listOf(SymbolDef("JPY=X", "دلار / ین ژاپن")),
    )

    val swissquoteEurjpy = SourceDef(
        id = "swissquote_eurjpy",
        title = "Swissquote BBO · EUR/JPY",
        subtitle = "منبع اختصاصی یورو / ین ژاپن",
        kind = SourceKind.SPOT_BBO_QUOTE,
        urlTemplate = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/{symbol}",
        unit = "JPY",
        symbols = listOf(SymbolDef("EUR/JPY", "یورو / ین ژاپن")),
    )

    val yahooGbpjpy = SourceDef(
        id = "yahoo_gbpjpy",
        title = "Yahoo FX · GBP/JPY",
        subtitle = "منبع اختصاصی پوند / ین ژاپن بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=5m&range=1d&includePrePost=false",
        pricePath = "chart.result[0].meta.regularMarketPrice",
        changePath = "chart.result[0].meta.chartPreviousClose",
        changeMode = ChangeMode.PREV_CLOSE,
        sparkPath = "chart.result[0].indicators.quote[0].close",
        volumePath = "chart.result[0].indicators.quote[0].volume",
        timestampPath = "chart.result[0].meta.regularMarketTime",
        timestampMode = SourceTime.UNIX_SECONDS,
        unit = "JPY",
        symbols = listOf(SymbolDef("GBPJPY=X", "پوند / ین ژاپن")),
    )

    val swissquoteAudusd = SourceDef(
        id = "swissquote_audusd",
        title = "Swissquote BBO · AUD/USD",
        subtitle = "منبع اختصاصی دلار استرالیا / دلار",
        kind = SourceKind.SPOT_BBO_QUOTE,
        urlTemplate = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/{symbol}",
        unit = "$",
        symbols = listOf(SymbolDef("AUD/USD", "دلار استرالیا / دلار")),
    )

    val swissquoteUsdchf = SourceDef(
        id = "swissquote_usdchf",
        title = "Swissquote BBO · USD/CHF",
        subtitle = "منبع اختصاصی دلار / فرانک سوئیس",
        kind = SourceKind.SPOT_BBO_QUOTE,
        urlTemplate = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/{symbol}",
        unit = "CHF",
        symbols = listOf(SymbolDef("USD/CHF", "دلار / فرانک سوئیس")),
    )

    val binanceBtcSpot = SourceDef(
        id = "binance_btc_spot",
        title = "Binance Spot · BTC/USDT",
        subtitle = "جایگزین فقط‌خواندنی · مرجع رمزارز: نوبیتکس — منبع اختصاصی بیت‌کوین در بایننس",
        kind = SourceKind.CRYPTO_SPOT_TRADE,
        urlTemplate = "https://data-api.binance.vision/api/v3/aggTrades?symbol={symbol}",
        unit = "$",
        symbols = listOf(SymbolDef("BTC/USDT", "بیت‌کوین / تتر")),
    )

    val okxEthSpot = SourceDef(
        id = "okx_eth_spot",
        title = "OKX Spot · ETH/USDT",
        subtitle = "جایگزین فقط‌خواندنی · مرجع رمزارز: نوبیتکس — منبع اختصاصی اتریوم در OKX",
        kind = SourceKind.CRYPTO_SPOT_TRADE,
        urlTemplate = "https://www.okx.com/api/v5/market/trades?instId={symbol}",
        unit = "$",
        symbols = listOf(SymbolDef("ETH/USDT", "اتریوم / تتر")),
    )

    val gateSolSpot = SourceDef(
        id = "gate_sol_spot",
        title = "Gate.io Spot · SOL/USDT",
        subtitle = "جایگزین فقط‌خواندنی · مرجع رمزارز: نوبیتکس — منبع اختصاصی سولانا در Gate.io",
        kind = SourceKind.CRYPTO_SPOT_TRADE,
        urlTemplate = "https://api.gateio.ws/api/v4/spot/trades?currency_pair={symbol}",
        unit = "$",
        symbols = listOf(SymbolDef("SOL/USDT", "سولانا / تتر")),
    )

    val mexcBnbSpot = SourceDef(
        id = "mexc_bnb_spot",
        title = "MEXC Spot · BNB/USDT",
        subtitle = "جایگزین فقط‌خواندنی · مرجع رمزارز: نوبیتکس — منبع اختصاصی بی‌ان‌بی در MEXC",
        kind = SourceKind.CRYPTO_SPOT_TRADE,
        urlTemplate = "https://api.mexc.com/api/v3/trades?symbol={symbol}",
        unit = "$",
        symbols = listOf(SymbolDef("BNB/USDT", "بی‌ان‌بی / تتر")),
    )

    val krakenXrpSpot = SourceDef(
        id = "kraken_xrp_spot",
        title = "Kraken Spot · XRP/USDT",
        subtitle = "جایگزین فقط‌خواندنی · مرجع رمزارز: نوبیتکس — منبع اختصاصی ریپل در Kraken",
        kind = SourceKind.CRYPTO_SPOT_TRADE,
        urlTemplate = "https://api.kraken.com/0/public/Trades?pair={symbol}",
        unit = "$",
        symbols = listOf(SymbolDef("XRP/USDT", "ریپل / تتر")),
    )

    // Backward-compatible references for tests/helpers
    val yahoo: SourceDef get() = yahooEurusd

    val twelveData = SourceDef(
        id = "twelve_data_quote",
        title = "Twelve Data",
        subtitle = "آخرین قیمت ارائه‌دهنده",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://api.twelvedata.com/quote?symbol={symbol}&timezone=UTC&apikey={api_key}",
        pricePath = "close|price",
        changePath = "percent_change",
        changeMode = ChangeMode.PERCENT,
        timestampPath = "datetime",
        timestampMode = SourceTime.UTC_DATETIME,
        unit = "$",
        requiresKey = true,
    )

    /** Exactly 15 dedicated sources — 1-to-1 with the 15 symbols in WatchCatalog. */
    val all: List<SourceDef> = listOf(
        goldApiXau,
        goldApiXag,
        yahooDow,
        yahooEurusd,
        swissquoteGbpusd,
        yahooUsdjpy,
        swissquoteEurjpy,
        yahooGbpjpy,
        swissquoteAudusd,
        swissquoteUsdchf,
        binanceBtcSpot,
        okxEthSpot,
        gateSolSpot,
        mexcBnbSpot,
        krakenXrpSpot,
    )

    fun find(id: String): SourceDef? = all.firstOrNull { it.id == id }
        ?: if (id == twelveData.id) twelveData else null
}
