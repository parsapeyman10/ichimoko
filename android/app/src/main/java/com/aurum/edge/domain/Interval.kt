package com.aurum.edge.domain

/**
 * Candle timeframe with API label and minute size.
 * Lives in [domain] so both [core] and [data] can depend on it without a cycle.
 */
enum class Interval(val api: String, val label: String, val minutes: Int) {
    M1("1min", "1m", 1),
    M3("3min", "3m", 3),
    M5("5min", "5m", 5),
    M15("15min", "15m", 15),
    M30("30min", "30m", 30),
    H1("1h", "1H", 60),
    H4("4h", "4H", 240),
    D1("1day", "1D", 1440);

    val millis: Long get() = minutes * 60_000L

    companion object {
        fun fromLabel(label: String): Interval = entries.firstOrNull { it.label == label } ?: M5
    }
}
