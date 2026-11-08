import com.aurum.edge.core.*
import com.aurum.edge.domain.*

/**
 * آزمون «هزینهٔ اجرا در برابر ۱R» روی نمادهای مختلف با همان شکل سری.
 * چرا؟ چون هندسهٔ معامله بر حسب ATR است ولی هزینه درصدی از قیمت؛ کیفیت خالص
 * معامله به نسبت این دو بستگی دارد، نه به تلورانس آستانه‌ها.
 */
fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 6000
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 11L
    for ((symbol, base) in listOf("BTC/USDT" to 62000.0, "XAU/USD" to 2400.0, "EUR/USD" to 1.0850)) {
        val series = genSeries(bars, seed, startPrice = base)
        val cfg = BacktestReplayEngine.Config(
            strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id, symbol = symbol,
            warmupBars = 120, includeHiddenCrossLayer = true,
        )
        val res = BacktestReplayEngine.run(series, cfg)
        val st = res.stats.firstOrNull() ?: continue
        val feeR = res.trades.map { it.feeUsd / (kotlin.math.abs(it.entryPrice - it.stopLoss) * (1000.0 * 0.005 / kotlin.math.abs(it.entryPrice - it.stopLoss))) }
        println(String.format(java.util.Locale.US,
            "%-9s معامله=%2d · برد=%.0f%% · خالص=%+.2f$ · PF=%.2f · انتظار=%+.2fR · میانهٔ کارمزد=%.2fR",
            symbol, res.trades.size, st.winRatePct, st.netPnlUsd, st.profitFactor, st.expectancyR,
            feeR.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }))
    }
}
