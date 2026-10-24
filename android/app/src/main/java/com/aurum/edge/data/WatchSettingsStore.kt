package com.aurum.edge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local, read-only provider preferences per instrument. No broker/exchange secret is accepted. */
data class WatchSelection(val enabledSources: List<String>, val preferredSourceId: String)

class WatchSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("watch_preferences", Context.MODE_PRIVATE)
    private val _selections = MutableStateFlow(WatchCatalog.symbols.associate { it.id to read(it) })
    val selections: StateFlow<Map<String, WatchSelection>> = _selections.asStateFlow()

    private fun read(symbol: WatchSymbol): WatchSelection {
        val saved = prefs.getString("sources_${symbol.id}", null)
        // Migrate old single-source defaults to a verified multi-source watch.
        // Other explicit source combinations remain the user's own choice.
        val obsoleteDefault = (symbol.id.endsWith("/USDT") &&
            saved in listOf("binance_spot_agg_trades", "nobitex_public_trades")) ||
            (symbol.id == "XAU/USD" && saved == "gold_api_public,stocks_yahoo") ||
            (symbol.providerCodes.containsKey("swissquote_bbo") && saved == "stocks_yahoo")
        val normalized = if (obsoleteDefault) null else saved
        val enabled = normalized?.split(',')
            ?.map { if (it == "nobitex_public_trades") "binance_spot_agg_trades" else it }
            ?.filter { it in symbol.providerCodes }?.distinct()?.takeIf { it.isNotEmpty() }
            ?: symbol.defaultSources
        val preferred = (if (normalized == null && saved != null) null
            else prefs.getString("preferred_${symbol.id}", null))
            ?.let { if (it == "nobitex_public_trades") "binance_spot_agg_trades" else it }
            ?.takeIf { it in enabled } ?: enabled.firstOrNull().orEmpty()
        return WatchSelection(enabled, preferred)
    }

    /** A blank override uses the chart's read-only Twelve Data key, if any. */
    fun apiKeyFor(symbolId: String, chartKey: String): String =
        prefs.getString("key_$symbolId", null)?.takeIf { it.isNotBlank() } ?: chartKey

    fun keyOverride(symbolId: String): String =
        if (WatchCatalog.find(symbolId) == null) "" else prefs.getString("key_$symbolId", "").orEmpty()

    /** Explicit blank removes this per-symbol override; the chart key remains untouched. */
    @Synchronized
    fun setKeyOverride(symbolId: String, key: String): Boolean {
        if (WatchCatalog.find(symbolId) == null) return false
        val normalized = key.trim()
        if (normalized.any { it.isWhitespace() }) return false
        val saved = prefs.edit().putString("key_$symbolId", normalized).commit()
        return saved && prefs.getString("key_$symbolId", null) == normalized
    }
}
