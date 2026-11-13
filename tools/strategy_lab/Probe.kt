import com.aurum.edge.core.*
import com.aurum.edge.domain.*

fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 8000
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 11L
    val series = genSeries(bars, seed)
    val cfg = BacktestReplayEngine.Config(
        strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id, symbol = "BTC/USDT",
        warmupBars = 120, includeHiddenCrossLayer = true, captureEntryReports = true,
    )
    val res = BacktestReplayEngine.run(series, cfg)
    println("معامله‌ها=${res.trades.size}")
    res.trades.take(8).forEach { t ->
        val rep = res.entryReports[t.entryTime]
        val oneR = kotlin.math.abs(t.entryPrice - t.stopLoss)
        val pct = oneR / t.entryPrice * 100
        println(String.format(java.util.Locale.US,
            "%s %s | ورود=%.2f SL=%.2f 1R=%.2f (%.2f%%) TP=%.2f RR=%.2f | خروج=%s @%.2f | R=%.2f$ fee=%.2f gate=%.0f%%",
            java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(t.entryTime)),
            t.action.name, t.entryPrice, t.stopLoss, oneR, pct, t.takeProfit, t.plannedRiskReward,
            t.exitReason.name, t.exitPrice ?: 0.0, t.rMultiple, t.feeUsd,
            rep?.bookGateScorePct ?: 0.0))
    }
}
