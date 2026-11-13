import com.aurum.edge.core.*
import com.aurum.edge.domain.*
import kotlin.math.abs
import kotlin.math.max

/**
 * آزمایشگاه استراتژی بیس — همان کدی که در APK اجرا می‌شود، فقط روی JVM.
 *
 * سه چیز را اندازه می‌گیرد و هیچ‌کدام را «حس» نمی‌کند:
 *  ۱. نرخ پاس هر شرط و اینکه وقتی رژیم درست است، چه چیزی جلوی ورود را می‌گیرد.
 *  ۲. تعداد سیگنال واقعی (همان چیزی که بک‌تست به معامله تبدیل می‌کند).
 *  ۳. سرعت و سود/زیان روی همان سری.
 *
 * سری مصنوعی عمداً «واقع‌نما» است: رژیم‌های روند/رنج/نزولی، پولبک، نویز، و حجم
 * ساعت‌محور (لندن/نیویورک). این جای CSV واقعی را نمی‌گیرد ولی برای مقایسهٔ
 * A/B قبل/بعد کافی و قابل تکرار است.
 */

private fun lcg(seed: Long): () -> Double {
    var s = seed
    return {
        s = s * 6364136223846793005L + 1442695040888963407L
        ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }
}

fun genSeries(bars: Int, seed: Long, startPrice: Double = 62000.0): List<Candle> {
    val r = lcg(seed)
    val out = ArrayList<Candle>(bars)
    var price = startPrice
    var drift = 0.0
    var vol = 0.0018
    val m15 = Interval.M15.millis
    var regimeLeft = 0
    var regimeKind = 0
    for (i in 0 until bars) {
        if (regimeLeft-- <= 0) {
            regimeKind = (abs(r()) * 4.0).toInt().coerceIn(0, 3)
            regimeLeft = 120 + (abs(r()) * 260).toInt()
            drift = when (regimeKind) {
                0 -> 0.00045
                1 -> 0.0
                2 -> -0.00040
                else -> 0.00018
            }
            vol = if (regimeKind == 1) 0.0006 else 0.0015 + abs(r()) * 0.0012
        }
        val cycle = i % 31
        val pull = if (cycle in 26..28) -drift * 3.2 else 0.0
        val ret = drift + pull + r() * vol
        val open = price
        val close = open * (1.0 + ret)
        val hi = max(open, close) * (1.0 + abs(r()) * vol * 0.75)
        val lo = minOf(open, close) * (1.0 - abs(r()) * vol * 0.75)
        val t = i * m15
        val hour = ((t / 3_600_000L) % 24).toInt()
        val volBase = when (hour) { in 7..10 -> 1.6; in 13..16 -> 1.8; in 0..4 -> 0.6; else -> 1.0 }
        out += Candle(
            time = t, open = open, high = hi, low = lo, close = close,
            volume = 800.0 * volBase * (0.6 + abs(r()) * 0.9), closed = true,
        )
        price = close
    }
    return out
}

private fun pct(a: Int, b: Int): String = String.format(java.util.Locale.US, "%d%%", a * 100 / max(1, b))
private fun f2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

fun diagnose(candles: List<Candle>, label: String, warmup: Int = 120, includeHidden: Boolean = true) {
    val macro = BacktestSeries.resample(candles, Interval.H4)
    val strat = BacktestSeries.resample(candles, Interval.H1)
    val triggerSize = Interval.M15.millis
    val cfg = BaseStrategyConfig(includeHiddenCrossLayer = includeHidden)

    val pass = IntArray(18)
    val failTitle = LinkedHashMap<String, Int>()
    val failWhenRegime = LinkedHashMap<String, Int>()
    var evaluated = 0
    var signals = 0
    var watch = 0
    val scoreDist = HashMap<Int, Int>()
    var gateSum = 0.0
    var gateN = 0

    val t0 = System.currentTimeMillis()
    for (i in warmup until candles.size) {
        val now = candles[i].time + triggerSize
        val mw = BacktestSeries.closedUpTo(macro, Interval.H4.millis, now)
        val sw = BacktestSeries.closedUpTo(strat, Interval.H1.millis, now)
        val tw = BacktestSeries.closedUpTo(candles, triggerSize, now)
        if (mw.size < 30 || sw.size < 30 || tw.size < warmup) continue
        val rep = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.BASE_ICHIMOKU,
            config = cfg,
            macroCandles = mw, strategyCandles = sw, triggerCandles = tw,
            livePriceOverride = candles[i].close, nowMillis = now, symbolInput = "BTC/USDT",
        )
        evaluated++
        val passed = rep.conditions.count { it.passed }
        scoreDist[passed] = (scoreDist[passed] ?: 0) + 1
        if (rep.bookGateScorePct > 0.0) { gateSum += rep.bookGateScorePct; gateN++ }
        val regimeOk = rep.conditions.filter { it.index in setOf(1, 3, 4, 6) }.all { it.passed }
        rep.conditions.forEach { c ->
            if (c.passed) pass[c.index] = pass[c.index] + 1
            else {
                failTitle[c.titleFa] = (failTitle[c.titleFa] ?: 0) + 1
                if (regimeOk) failWhenRegime[c.titleFa] = (failWhenRegime[c.titleFa] ?: 0) + 1
            }
        }
        if (rep.readiness == StrategyReadinessState.SIGNAL_BUY || rep.readiness == StrategyReadinessState.SIGNAL_SELL) signals++
        if (rep.readiness == StrategyReadinessState.WATCH_SETUP) watch++
    }
    val dt = System.currentTimeMillis() - t0
    println("── $label · $evaluated ارزیابی · ${dt}ms · ${f2(dt.toDouble() / max(1, evaluated))}ms/ارزیابی")
    println("   سیگنال actionable=$signals (${pct(signals, evaluated)}) · watch=$watch")
    if (gateN > 0) println("   میانگین امتیاز هم‌گرایی مکتبی = ${f2(gateSum / gateN)}%")
    println("   نرخ پاس شرط‌ها: " + (1..17).joinToString(" ") { "$it:${pct(pass[it], evaluated)}" })
    println("   توزیع تعداد شرط پاس‌شده: " + scoreDist.toSortedMap().entries.joinToString(" ") { "${it.key}:${it.value}" })
    println("   وقتی رژیم (۱/۳/۴/۶) درست است، سدها:")
    failWhenRegime.entries.sortedByDescending { it.value }.take(9).forEach { println("      ${it.value}× ${it.key}") }
}

fun backtest(candles: List<Candle>, label: String, includeHidden: Boolean = true) {
    val cfg = BacktestReplayEngine.Config(
        strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        symbol = "BTC/USDT",
        warmupBars = 120,
        includeHiddenCrossLayer = includeHidden,
    )
    val t0 = System.currentTimeMillis()
    val res = BacktestReplayEngine.run(candles, cfg)
    val dt = System.currentTimeMillis() - t0
    val st = res.stats.firstOrNull()
    println("== $label · ${candles.size} کندل · ${dt}ms · معامله=${res.trades.size} · maxCond=${res.maxConditionsPassed}")
    if (st != null) {
        println("   برد ${f2(st.winRatePct)}% · خالص ${st.netPnlUsd}$ · PF ${f2(st.profitFactor)} · انتظار ${f2(st.expectancyR)}R · ${st.trades} معامله")
    }
    res.topBlockers.take(5).forEach { println("   سد: ${it.second}× ${it.first}") }
}

fun backtestDetail(candles: List<Candle>, label: String, smart: Boolean, preserve: Boolean, timeStop: Boolean, hidden: Boolean, maxHold: Int? = null) {
    val cfg = BacktestReplayEngine.Config(
        strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        symbol = "BTC/USDT", warmupBars = 120,
        includeHiddenCrossLayer = hidden,
        useSmartExit = smart, usePreserveMode = preserve, useTimeStop = timeStop,
        maxHoldBars = maxHold,
    )
    val res = BacktestReplayEngine.run(candles, cfg)
    val st = res.stats.firstOrNull() ?: return
    val byReason = res.trades.groupBy { it.exitReason }
    val rr = res.trades.map { it.rMultiple }.sorted()
    val med = if (rr.isEmpty()) 0.0 else rr[rr.size / 2]
    println("== $label | smart=$smart preserve=$preserve time=$timeStop hidden=$hidden")
    println("   ${res.trades.size} معامله · برد ${f2(st.winRatePct)}% · خالص ${f2(st.netPnlUsd)}$ · PF ${f2(st.profitFactor)} · انتظار ${f2(st.expectancyR)}R · میانهR ${f2(med)} · بزرگ‌ترین ${f2(rr.lastOrNull() ?: 0.0)}R")
    println("   خروج‌ها: " + byReason.entries.sortedByDescending { it.value.size }
        .joinToString(" · ") { "${it.key.name}:${it.value.size}(میانگین ${f2(it.value.map { t -> t.rMultiple }.average())}R)" })
}

/**
 * آمار «کیفیت» به تفکیک ماشه و فاز موج — همان چیزی که کتاب‌ها می‌گویند باید تفاوت کند:
 * ورود در تولد موج (Genesis / پولبک به تعادل) versus تعقیب شکست.
 */
fun qualityReport(candles: List<Candle>, label: String, hidden: Boolean = true) {
    val cfg = BacktestReplayEngine.Config(
        strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        symbol = "BTC/USDT", warmupBars = 120,
        includeHiddenCrossLayer = hidden, captureEntryReports = true,
    )
    val res = BacktestReplayEngine.run(candles, cfg)
    if (res.trades.isEmpty()) { println("== $label: بدون معامله"); return }
    fun agg(key: String, groups: List<Pair<String, List<BacktestReplayEngine.Trade>>>) {
        println("   ── $key")
        groups.forEach { (name, ts) ->
            if (ts.isEmpty()) return@forEach
            val runs = ts.map { it.rMultiple }
            val wins = ts.count { it.netPnlUsd > 0.0 }
            println(String.format(java.util.Locale.US,
                "      %-22s n=%2d  برد=%3.0f%%  انتظار=%+.2fR  مجموع=%+.1fR",
                name, ts.size, wins * 100.0 / ts.size, runs.average(), runs.sum()))
        }
    }
    val byTrigger = res.trades.groupBy { t ->
        res.entryReports[t.entryTime]?.triggerType?.name ?: "?"
    }.map { it.key to it.value }
    val byPhase = res.trades.groupBy { t ->
        res.entryReports[t.entryTime]?.trendPhase?.name ?: "?"
    }.map { it.key to it.value }
    val byScore = res.trades.groupBy { t ->
        val sc = res.entryReports[t.entryTime]?.bookGateScorePct ?: 0.0
        when { sc >= 92 -> "≥92"; sc >= 85 -> "85..92"; sc >= 78 -> "78..85"; else -> "<78" }
    }.map { it.key to it.value }
    val byRr = res.trades.groupBy { t ->
        when { t.plannedRiskReward >= 2.4 -> "RR≥2.4"; t.plannedRiskReward >= 2.0 -> "RR2.0..2.4"; else -> "RR<2.0" }
    }.map { it.key to it.value }
    println("== $label · ${res.trades.size} معامله")
    agg("ماشه", byTrigger)
    agg("فاز موج", byPhase)
    agg("امتیاز دروازه", byScore)
    agg("R:R برنامه‌ریزی‌شده", byRr)
}

fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 8000
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 11L
    val series = genSeries(bars, seed)
    val days = bars * 15.0 / 60.0 / 24.0
    println("سری: $bars کندل ۱۵دقیقه‌ای (~${f2(days)} روز) · seed=$seed")
    if (args.getOrNull(2) == "quality") {
        qualityReport(series, "کیفیت معامله‌ها", hidden = true)
        return
    }
    if (args.getOrNull(2) == "hold") {
        for (h in listOf(20, 30, 40, 60, 80, 120)) {
            backtestDetail(series, "maxHold=$h", true, true, true, hidden = true, maxHold = h)
        }
        backtestDetail(series, "timeStop=off", true, true, false, hidden = true)
        return
    }
    if (args.getOrNull(2) == "grid") {
        for (smart in listOf(true, false)) for (preserve in listOf(true, false)) for (ts in listOf(true, false)) {
            backtestDetail(series, "شبکهٔ خروج", smart, preserve, ts, hidden = true)
        }
        return
    }
    diagnose(series, "بیس + لایهٔ هیدن کراس", includeHidden = true)
    diagnose(series, "بیس خالص چهارگانه", includeHidden = false)
    backtest(series, "معامله با لایهٔ هیدن کراس", includeHidden = true)
    backtest(series, "معامله بیس خالص", includeHidden = false)
}
