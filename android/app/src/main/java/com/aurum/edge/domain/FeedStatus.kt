package com.aurum.edge.domain

/**
 * Feed status returned by a data provider.
 * Lives in [domain] so both [core] and [data] can depend on it without a cycle.
 */
data class FeedStatus(
    val mode: FeedMode,
    val detail: String = "",
    val lastSuccessAt: Long? = null,
    val provider: String = "Twelve Data",
)
