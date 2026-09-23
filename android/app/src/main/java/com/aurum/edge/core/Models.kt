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
    /** What the multi-timeframe engine said on the phone when this paper trade was opened. */
    val mtf: MtfSnapshotRecord? = null,
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

@Serializable
data class MtfFrameRecord(
    val interval: String,
    val bias: String,
    val strength: Int,
    val detail: String,
    val weight: Double,
)

@Serializable
data class MtfSnapshotRecord(
    val baseInterval: String,
    val bias: String,
    val alignment: Double,
    val buyCount: Int,
    val sellCount: Int,
    val neutralCount: Int,
    val veto: Boolean,
    val advisory: String,
    val barTime: Long,
    val frames: List<MtfFrameRecord> = emptyList(),
    val skippedFrames: List<String> = emptyList(),
) {
    companion object {
        fun from(snapshot: com.aurum.edge.engine.MtfAnalyzer.Snapshot): MtfSnapshotRecord = MtfSnapshotRecord(
            baseInterval = snapshot.baseInterval.label,
            bias = snapshot.bias.name,
            alignment = snapshot.alignment,
            buyCount = snapshot.buyCount,
            sellCount = snapshot.sellCount,
            neutralCount = snapshot.neutralCount,
            veto = snapshot.veto,
            advisory = snapshot.advisory,
            barTime = snapshot.barTime,
            frames = snapshot.frames.map {
                MtfFrameRecord(
                    interval = it.interval.label,
                    bias = it.bias.name,
                    strength = it.strength,
                    detail = it.detail,
                    weight = it.weight,
                )
            },
            skippedFrames = snapshot.skippedFrames,
        )
    }
}

@Serializable
data class BacktestTradeRecord(
    val side: String,
    val entryTime: Long,
    val exitTime: Long,
    val entry: Double,
    val exit: Double,
    val positionOz: Double,
    val pnlUsd: Double,
    val rMultiple: Double,
    val exitReason: String,
)

@Serializable
data class BacktestRecord(
    val interval: String,
    val fromTime: Long,
    val toTime: Long,
    val bars: Int,
    val initialBalance: Double,
    val finalBalance: Double,
    val netPnl: Double,
    val wins: Int,
    val losses: Int,
    val winRate: Double? = null,
    val profitFactor: Double? = null,
    val expectancyR: Double? = null,
    val maxDrawdownPct: Double,
    val feesUsd: Double,
    val skippedMinLot: Int,
    val skippedMargin: Int,
    val spreadPrice: Double,
    val commissionPerOz: Double,
    val note: String,
    val trades: List<BacktestTradeRecord> = emptyList(),
) {
    companion object {
        fun from(result: com.aurum.edge.engine.Backtester.Result): BacktestRecord = BacktestRecord(
            interval = result.interval.label,
            fromTime = result.fromTime,
            toTime = result.toTime,
            bars = result.bars,
            initialBalance = result.initialBalance,
            finalBalance = result.finalBalance,
            netPnl = result.netPnl,
            wins = result.wins,
            losses = result.losses,
            winRate = result.winRate,
            profitFactor = result.profitFactor,
            expectancyR = result.expectancyR,
            maxDrawdownPct = result.maxDrawdownPct,
            feesUsd = result.feesUsd,
            skippedMinLot = result.skippedMinLot,
            skippedMargin = result.skippedMargin,
            spreadPrice = result.spreadPrice,
            commissionPerOz = result.commissionPerOz,
            note = result.note,
            trades = result.trades.takeLast(80).map {
                BacktestTradeRecord(
                    side = it.side.name,
                    entryTime = it.entryTime,
                    exitTime = it.exitTime,
                    entry = it.entry,
                    exit = it.exit,
                    positionOz = it.positionOz,
                    pnlUsd = it.pnlUsd,
                    rMultiple = it.rMultiple,
                    exitReason = it.exitReason,
                )
            },
        )
    }
}

@Serializable
data class WalkForwardRecord(
    val interval: String,
    val bars: Int,
    val splitTime: Long,
    val splitIndex: Int,
    val verdict: String,
    val generatedAt: Long,
    val inSample: BacktestRecord,
    val outOfSample: BacktestRecord,
) {
    companion object {
        fun from(result: com.aurum.edge.engine.Backtester.WalkForward, generatedAt: Long = System.currentTimeMillis()): WalkForwardRecord =
            WalkForwardRecord(
                interval = result.outOfSample.interval.label,
                bars = result.bars,
                splitTime = result.splitTime,
                splitIndex = result.splitIndex,
                verdict = result.verdict,
                generatedAt = generatedAt,
                inSample = BacktestRecord.from(result.inSample),
                outOfSample = BacktestRecord.from(result.outOfSample),
            )
    }
}

data class AppSettings(
    val apiKey: String = "",
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val riskPercent: Double = 0.5,
    val accountBalance: Double = 100.0,
    val minConfidence: Double = 72.0,
    /** Cost assumptions in USD. They must match your broker; every report states them. */
    val spreadPrice: Double = 0.30,
    val commissionPerOz: Double = 0.05,
    val backgroundMonitor: Boolean = false,
    val notifyOnSignal: Boolean = true,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()
}
