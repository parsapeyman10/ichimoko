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
        val enabled = prefs.getString("sources_${symbol.id}", null)?.split(',')
            ?.filter { it in symbol.providerCodes }?.distinct() ?: symbol.defaultSources
        val preferred = prefs.getString("preferred_${symbol.id}", null)
            ?.takeIf { it in enabled } ?: enabled.firstOrNull().orEmpty()
        return WatchSelection(enabled, preferred)
    }

    fun selectSource(symbolId: String, sourceId: String, enabled: Boolean) {
        val symbol = WatchCatalog.find(symbolId) ?: return
        if (sourceId !in symbol.providerCodes) return
        val current = _selections.value[symbolId] ?: read(symbol)
        val updated = symbol.providerCodes.keys.filter { id ->
            if (id == sourceId) enabled else id in current.enabledSources
        }
        val preferred = current.preferredSourceId.takeIf { it in updated } ?: updated.firstOrNull().orEmpty()
        prefs.edit().putString("sources_$symbolId", updated.joinToString(","))
            .putString("preferred_$symbolId", preferred).apply()
        _selections.value = _selections.value + (symbolId to WatchSelection(updated, preferred))
    }

    fun setPreferred(symbolId: String, sourceId: String) {
        val current = _selections.value[symbolId] ?: return
        if (sourceId !in current.enabledSources) return
        prefs.edit().putString("preferred_$symbolId", sourceId).apply()
        _selections.value = _selections.value + (symbolId to current.copy(preferredSourceId = sourceId))
    }

    /** A blank override uses the chart's read-only Twelve Data key, if any. */
    fun apiKeyFor(symbolId: String, chartKey: String): String =
        prefs.getString("key_$symbolId", null)?.takeIf { it.isNotBlank() } ?: chartKey

    fun keyOverride(symbolId: String): String =
        if (WatchCatalog.find(symbolId) == null) "" else prefs.getString("key_$symbolId", "").orEmpty()

    fun setKeyOverride(symbolId: String, key: String) {
        if (WatchCatalog.find(symbolId)?.providerCodes?.containsKey(SourceCatalog.twelveData.id) != true) return
        prefs.edit().putString("key_$symbolId", key.trim()).apply()
    }
}
