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

enum class ConfluenceStatus { CONFIRMED, CONFLICT, UNKNOWN }

data class ConfluenceItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val status: ConfluenceStatus = if (ok) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
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
data class PaperNewsEvidence(
    val id: String,
    val source: String,
    val headline: String,
    val url: String,
    val publishedAt: Long,
)

@Serializable
data class PaperNewsRecord(
    val model: String,
    val direction: String,
    val confidence: Double,
    val checkedAt: Long,
    val evidence: List<PaperNewsEvidence>,
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
    /** Historical JSON field name; quantity is in [unit], not necessarily troy ounces. */
    val positionOz: Double = 1.0,
    val note: String = "paper روی قیمت واقعی",
    /** Empty for older journal records; infer from the symbol on read. */
    val positionUnit: String = "",
    /** What the multi-timeframe engine said on the phone when this paper trade was opened. */
    val mtf: MtfSnapshotRecord? = null,
    /** Defaults keep older journal JSON readable. Auto is always PAPER, never a broker fill. */
    val autoOpened: Boolean = false,
    val signalBarTime: Long? = null,
    val newsEvidence: PaperNewsRecord? = null,
    /** Snapshot at the moment the paper position was actually saved; never recompute on read. */
    val entryConditions: List<PaperConditionRecord> = emptyList(),
) {
    val isOpen: Boolean get() = closedAt == null
    val unit: String get() = positionUnit.ifBlank { PaperOrderRules.unitFor(symbol) }

    val riskPerOz: Double get() = kotlin.math.abs(entry - stopLoss)

    val rMultiple: Double?
        get() {
            val pnl = pnlUsd ?: return null
            val risk = riskPerOz * positionOz
            return if (risk <= 0.0) null else pnl / risk
        }
}

@Serializable
data class PaperConditionRecord(val name: String, val status: String, val detail: String) {
    companion object {
        fun from(item: ConfluenceItem) = PaperConditionRecord(item.name, item.status.name, item.detail)
    }
}

/** An eligible alert is NOT a trade. Stored separately from paper positions and their statistics. */
@Serializable
data class PaperOpportunity(
    val key: String,
    val symbol: String,
    val interval: Interval,
    val action: SignalAction,
    val signalBarTime: Long,
    val priceAtAlert: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val alertedAt: Long,
    val conditions: List<PaperConditionRecord>,
    val mtf: MtfSnapshotRecord,
    val newsEvidence: PaperNewsRecord,
    val paperTradeId: String? = null,
) {
    companion object {
        fun from(signal: Signal, symbol: String, price: Double, mtf: MtfSnapshotRecord,
                 news: PaperNewsRecord, now: Long = System.currentTimeMillis()): PaperOpportunity {
            require(symbol == "XAU/USD" && signal.isActionable && signal.barTime > 0 &&
                signal.confluence.take(9).size == 9 &&
                signal.confluence.take(9).all { it.ok && it.status == ConfluenceStatus.CONFIRMED } &&
                price.isFinite() && price > 0 && signal.stopLoss != null && signal.takeProfit != null) {
                "فرصت آموزشی معتبر نیست"
            }
            return PaperOpportunity(
                key = "$symbol|${signal.interval.label}|${signal.barTime}|${signal.action}",
                symbol = symbol, interval = signal.interval, action = signal.action,
                signalBarTime = signal.barTime, priceAtAlert = price,
                stopLoss = signal.stopLoss, takeProfit = signal.takeProfit,
                alertedAt = now, conditions = signal.confluence.take(9).map(PaperConditionRecord::from),
                mtf = mtf, newsEvidence = news,
            )
        }
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
    val symbol: String = "XAU/USD",
    val dataSource: String = "Twelve Data (دیتای واقعی)",
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
            symbol = result.symbol,
            dataSource = result.dataSource,
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
            trades = result.trades.map {
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
    /** Persistable SAF content Uri; blank uses the device's system notification tone. */
    val alertSoundUri: String = "",
    val alertSoundName: String = "",
    /** Optional HTTPS URL of this project's backend (licensed Persian news). */
    val newsBaseUrl: String = "",
    /** Applies to NEW paper entries; real orders remain disabled independently. */
    val pauseOnNews: Boolean = false,
    /** Explicit opt-in; automatic orders here are local paper records, never broker orders. */
    val autoPaperTrading: Boolean = false,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()
}
