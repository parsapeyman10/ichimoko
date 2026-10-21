package com.aurum.edge.domain

/**
 * Trade direction: BUY, SELL or NO_TRADE.
 * Lives in [domain] so both [core] and [data] can depend on it without a cycle.
 */
enum class SignalAction { BUY, SELL, NO_TRADE }
