package com.aurum.edge.data

import java.util.Locale

/** The chart's visual-only picker. Never mutates the app's market feed or paper-trade symbol. */
data class ChartSymbolChoice(val id: String, val ticker: String, val label: String)

object ChartSymbolChoices {
    val all: List<ChartSymbolChoice> = WatchCatalog.scannerSymbols.distinct().mapNotNull { id ->
        val ticker = TradingViewSymbols.find(id) ?: return@mapNotNull null
        val label = WatchCatalog.find(id)?.label ?: CryptoCatalog.find(id)?.label ?: id
        ChartSymbolChoice(id, ticker, label)
    }

    fun search(query: String, limit: Int = 60): List<ChartSymbolChoice> {
        val term = query.trim().uppercase(Locale.ROOT)
        return all.filter { choice ->
            term.isEmpty() || choice.id.uppercase(Locale.ROOT).contains(term) ||
                choice.ticker.uppercase(Locale.ROOT).contains(term) ||
                choice.label.contains(query.trim(), ignoreCase = true)
        }.take(limit)
    }

    fun display(ticker: String): String = all.firstOrNull { it.ticker == ticker }?.id ?: ticker
}
