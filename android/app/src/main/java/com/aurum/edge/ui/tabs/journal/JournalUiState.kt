package com.aurum.edge.ui

import com.aurum.edge.core.TradeReplay

/** One expanded trade chart at a time; owned by the Journal tab. */
data class TradeChartState(
    val tradeId: String = "",
    val loading: Boolean = false,
    val downloading: Boolean = false,
    val window: TradeReplay.Window = TradeReplay.Window(),
    val source: String = "",
    val error: String? = null,
)
