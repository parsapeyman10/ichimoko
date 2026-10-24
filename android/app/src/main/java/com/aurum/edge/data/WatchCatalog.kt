package com.aurum.edge.data

/** Each mapping identifies the exact instrument and its single dedicated source (1-to-1). */
data class WatchSymbol(
    val id: String,
    val label: String,
    val unit: String,
    val providerCodes: Map<String, String>,
    val defaultSources: List<String>,
    val maxAgeMillis: Long,
    val tolerancePct: Double,
)

object WatchCatalog {
    val symbols: List<WatchSymbol> = listOf(
        // ── طلا و نقره ────────────────────────────────────────────────────
        WatchSymbol(
            id = "XAU/USD",
            label = "طلای جهانی (هر انس)",
            unit = "$",
            providerCodes = linkedMapOf("gold_api_xau" to "XAU"),
            defaultSources = listOf("gold_api_xau"),
            maxAgeMillis = 10 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "XAG/USD",
            label = "نقرهٔ جهانی (هر انس)",
            unit = "$",
            providerCodes = linkedMapOf("gold_api_xag" to "XAG"),
            defaultSources = listOf("gold_api_xag"),
            maxAgeMillis = 10 * 60_000L,
            tolerancePct = 0.5,
        ),

        // ── شاخص داوجونز ──────────────────────────────────────────────────
        WatchSymbol(
            id = "DOW",
            label = "شاخص داوجونز آمریکا",
            unit = "$",
            providerCodes = linkedMapOf("yahoo_dow" to "^DJI"),
            defaultSources = listOf("yahoo_dow"),
            maxAgeMillis = 10 * 60_000L,
            tolerancePct = 0.5,
        ),

        // ── جفت‌ارزهای مناسب ایچیموکو ────────────────────────────────────
        WatchSymbol(
            id = "EUR/USD",
            label = "یورو / دلار",
            unit = "$",
            providerCodes = linkedMapOf("yahoo_eurusd" to "EURUSD=X"),
            defaultSources = listOf("yahoo_eurusd"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "GBP/USD",
            label = "پوند / دلار",
            unit = "$",
            providerCodes = linkedMapOf("swissquote_gbpusd" to "GBP/USD"),
            defaultSources = listOf("swissquote_gbpusd"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "USD/JPY",
            label = "دلار / ین ژاپن",
            unit = "JPY",
            providerCodes = linkedMapOf("yahoo_usdjpy" to "JPY=X"),
            defaultSources = listOf("yahoo_usdjpy"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "EUR/JPY",
            label = "یورو / ین ژاپن",
            unit = "JPY",
            providerCodes = linkedMapOf("swissquote_eurjpy" to "EUR/JPY"),
            defaultSources = listOf("swissquote_eurjpy"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "GBP/JPY",
            label = "پوند / ین ژاپن",
            unit = "JPY",
            providerCodes = linkedMapOf("yahoo_gbpjpy" to "GBPJPY=X"),
            defaultSources = listOf("yahoo_gbpjpy"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "AUD/USD",
            label = "دلار استرالیا / دلار",
            unit = "$",
            providerCodes = linkedMapOf("swissquote_audusd" to "AUD/USD"),
            defaultSources = listOf("swissquote_audusd"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "USD/CHF",
            label = "دلار / فرانک سوئیس",
            unit = "CHF",
            providerCodes = linkedMapOf("swissquote_usdchf" to "USD/CHF"),
            defaultSources = listOf("swissquote_usdchf"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),

        // ── ۵ رمزارز پرحجم و پرمعامله ─────────────────────────────────────
        WatchSymbol(
            id = "BTC/USDT",
            label = "بیت‌کوین / تتر",
            unit = "$",
            providerCodes = linkedMapOf("binance_btc_spot" to "BTC/USDT"),
            defaultSources = listOf("binance_btc_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "ETH/USDT",
            label = "اتریوم / تتر",
            unit = "$",
            providerCodes = linkedMapOf("okx_eth_spot" to "ETH/USDT"),
            defaultSources = listOf("okx_eth_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "SOL/USDT",
            label = "سولانا / تتر",
            unit = "$",
            providerCodes = linkedMapOf("gate_sol_spot" to "SOL/USDT"),
            defaultSources = listOf("gate_sol_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "BNB/USDT",
            label = "بی‌ان‌بی / تتر",
            unit = "$",
            providerCodes = linkedMapOf("mexc_bnb_spot" to "BNB/USDT"),
            defaultSources = listOf("mexc_bnb_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "XRP/USDT",
            label = "ریپل / تتر",
            unit = "$",
            providerCodes = linkedMapOf("kraken_xrp_spot" to "XRP/USDT"),
            defaultSources = listOf("kraken_xrp_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
    )

    val scannerSymbols: List<String> = symbols.map { it.id }

    fun scanUniverse(activeWatchlist: List<String>): List<String> =
        (activeWatchlist + scannerSymbols).filter(com.aurum.edge.core.V1Universe::valid).distinct()

    fun find(id: String): WatchSymbol? {
        val normalized = com.aurum.edge.core.V1Universe.normalize(id)
        return symbols.firstOrNull { it.id == normalized }
    }

    val chartSymbols: List<String> = symbols.map { it.id }
}
