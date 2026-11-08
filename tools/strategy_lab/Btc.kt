import com.aurum.edge.core.*
import com.aurum.edge.domain.*

/** کدام بستر زمانی برای BTC هزینهٔ اجرا را قابل‌تحمل می‌کند؟ */
fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 12000
    for ((trig, strat, macro) in listOf(
        Triple(Interval.M15, Interval.H1, Interval.H4),
        Triple(Interval.M30, Interval.H1, Interval.H4),
        Triple(Interval.H1, Interval.H4, Interval.D1),
    )) {
        val rows = listOf(11L, 23L, 37L).map { seed ->
            val series = genSeries(bars, seed, startPrice = 62000.0)
            val cfg = BacktestReplayEngine.Config(
                strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id, symbol = "BTC/USDT",
                triggerInterval = trig, strategyInterval = strat, macroInterval = macro,
                warmupBars = 150, includeHiddenCrossLayer = true,
            )
            val res = BacktestReplayEngine.run(series, cfg)
            val st = res.stats.firstOrNull()
            val costR = res.trades.map { it.feeUsd / (kotlin.math.abs(it.entryPrice - it.stopLoss) * (1000.0 * 0.005 / kotlin.math.abs(it.entryPrice - it.stopLoss))) }
            Triple(res.trades.size, st?.expectancyR ?: 0.0, costR.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] })
        }
        println(String.format(java.util.Locale.US,
            "%s/%s/%s → معامله=%d · میانگین انتظار=%+.2fR · میانهٔ کارمزد=%.2fR · %s",
            macro.label, strat.label, trig.label, rows.sumOf { it.first },
            rows.map { it.second }.average(), rows.map { it.third }.average(),
            rows.joinToString(" ") { String.format(java.util.Locale.US, "%d/%+.2fR", it.first, it.second) }))
    }
}
