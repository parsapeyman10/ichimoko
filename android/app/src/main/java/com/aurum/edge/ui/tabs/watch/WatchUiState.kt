package com.aurum.edge.ui

import com.aurum.edge.data.Quote

/** Paginated historical observations owned by the Watch tab. */
data class WatchHistory(
    val symbolId: String = "",
    val sourceId: String = "",
    val entries: List<Quote> = emptyList(),
    val total: Long = 0L,
    val loading: Boolean = false,
)
