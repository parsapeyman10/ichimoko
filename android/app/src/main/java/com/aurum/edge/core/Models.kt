package com.aurum.edge.core

import kotlinx.serialization.Serializable

/**
 * Real market data contracts.
 *
 * Rule of this app: every price that reaches the UI originates from a real provider
 * (Twelve Data REST/WebSocket) or from a local cache of previously received real prices.
 * There is no generator, no random walk, no seeded simulation anywhere in this module.
 */
@Serializable
data class Candle(
    /** Bar open time, epoch millis (UTC). */
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
    /** false only for the bar that is still forming right now. */
    val closed: Boolean = true,
) {
}

enum class Interval(val api: String, val label: String, val minutes: Int) {
    M1("1min", "1m", 1),
    M5("5min", "5m", 5),
    M15("15min", "15m", 15),
    H1("1h", "1H", 60),
    H4("4h", "4H", 240);

    val millis: Long get() = minutes * 60_000L

    companion object {
        fun fromLabel(label: String): Interval = entries.firstOrNull { it.label == label } ?: M5
    }
}

data class PriceTick(val price: Double, val at: Long)

enum class SignalAction { BUY, SELL, NO_TRADE }

data class ConfluenceItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

data class Signal(
    val action: SignalAction,
    val confidence: Double,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val riskReward: Double? = null,
    val reasons: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val confluence: List<ConfluenceItem> = emptyList(),
    val interval: Interval = Interval.M5,
    val barTime: Long = 0L,
    val evaluatedAt: Long = System.currentTimeMillis(),
) {
    val isActionable: Boolean get() = action != SignalAction.NO_TRADE
}

/** Connection truth for the UI. OFFLINE is a first-class state — never replaced by invented data. */
enum class FeedMode(val label: String) {
    NO_KEY("کلید API وارد نشده"),
    CONNECTING("در حال اتصال"),
    LIVE("زنده — WebSocket"),
    POLLING("زنده — REST هر ۶۰ ثانیه"),
    OFFLINE("آفلاین"),
}

data class FeedStatus(
    val mode: FeedMode,
    val detail: String = "",
    val lastSuccessAt: Long? = null,
    val provider: String = "Twelve Data",
)

@Serializable
data class PaperTrade(
    val id: String,
    val symbol: String,
    val interval: Interval,
    val action: SignalAction,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val confidence: Double,
    val riskReward: Double,
    val openedAt: Long,
    val closedAt: Long? = null,
    val exitPrice: Double? = null,
    val exitReason: String? = null,
    val pnlUsd: Double? = null,
    val positionOz: Double = 1.0,
    val note: String = "paper روی قیمت واقعی",
) {
    val isOpen: Boolean get() = closedAt == null

    val riskPerOz: Double get() = kotlin.math.abs(entry - stopLoss)

    val rMultiple: Double?
        get() {
            val pnl = pnlUsd ?: return null
            val risk = riskPerOz * positionOz
            return if (risk <= 0.0) null else pnl / risk
        }
}

data class AppSettings(
    val apiKey: String = "",
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val riskPercent: Double = 0.5,
    val accountBalance: Double = 100.0,
    val minConfidence: Double = 72.0,
    val backgroundMonitor: Boolean = false,
    val notifyOnSignal: Boolean = true,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()
}
