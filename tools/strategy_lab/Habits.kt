import com.aurum.edge.core.*
import com.aurum.edge.domain.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

/**
 * # آزمایشگاه «ضرر نکن» — لایهٔ عادت‌های معاملاتی روی دادهٔ ساختگی
 *
 * سؤال این فایل یک چیز است: **با قواعد کتاب Trading Habits، حساب چقدر می‌تواند ببازد؟**
 *
 * روش:
 *  ۱. پنج رژیم ساختگی (روند صعودی، روند نزولی، رِنج، نوسان بالا، گپ‌دار) در چند `seed` مستقل؛
 *     عمداً بدترین حالت هم ساخته می‌شود، چون یک سری خوش‌شانس چیزی را اثبات نمی‌کند.
 *  ۲. موتور واقعی اپ (`BacktestReplayEngine` + `IchimokuStrategyEngine`) روی **هر ۵ مدل
 *     باقی‌مانده** اجرا می‌شود؛ همان کدی که در APK می‌دود.
 *  ۳. برای هر معامله، «بیشترین سود شناور» (MFE) از کندل‌های بین ورود و خروج حساب می‌شود تا
 *     قاعدهٔ R37 قابل اندازه‌گیری شود: «هرگز نگذار سود معنادار به ضرر تبدیل شود».
 *  ۴. سه حالت بازپخش روی **حساب ثابت ۱۰۰٬۰۰۰ دلاری با ریسک ۱٪ از سرمایهٔ اولیه** (بدون
 *     مرکب‌سازی، تا اعداد قابل مقایسه و خوانا بمانند):
 *       الف) بی‌قاعده — هر معامله با ۱٪، بدون فیلتر رفتاری (رفتار «لایه خاموش»).
 *       ب) با عادت‌ها — `TradingHabitsPolicy` روی هر معامله: R:R≥۲، حد ضرر بیرون از نویز،
 *          سقف ۱٪، درجهٔ ستاپ، سری باخت، افت از قله.
 *       ج) عادت‌ها + قفل سود — همان ب، به‌علاوهٔ R37: معامله‌ای که ≥۱R سود شناور دید و بعد
 *          برگشت، حداکثر در سربه‌سر بسته می‌شود (سود تحقق‌نیافته به ضرر تبدیل نمی‌شود).
 *  ۵. مونت‌کارلو: ترتیب معامله‌ها ۲۰۰۰ بار بازآرایی می‌شود تا «بدترین دنباله» هم دیده شود.
 *  ۶. جاروب `کف R:R × حداقل ATR` — تا عددی انتخاب شود که در **همهٔ** رژیم‌ها ضرر را محدود کند.
 *
 * اجرا: `tools/strategy_lab/run.sh <bars> <seed> habits`
 */

private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v)

private fun lcg(seed: Long): () -> Double {
    var s = seed
    return {
        s = s * 6364136223846793005L + 1442695040888963407L
        ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }
}

/** رژیم‌های ساختگی — از جمله بدترین حالتی که یک سیستم خرید در آن می‌بازد. */
enum class Regime(val labelFa: String) {
    TREND_UP("روند صعودی"),
    TREND_DOWN("روند نزولی"),
    RANGE("رِنج"),
    HIGH_VOL("نوسان بالا"),
    GAP("گپ‌دار"),
}

private fun genRegime(bars: Int, seed: Long, regime: Regime, startPrice: Double = 2_400.0): List<Candle> {
    val r = lcg(seed * 7919L + regime.ordinal * 104_729L)
    val m15 = Interval.M15.millis
    var price = startPrice
    val out = ArrayList<Candle>(bars)
    for (i in 0 until bars) {
        val drift = when (regime) {
            Regime.TREND_UP -> 0.00055
            Regime.TREND_DOWN -> -0.00055
            Regime.RANGE -> 0.0
            Regime.HIGH_VOL -> 0.00005
            Regime.GAP -> 0.00012
        }
        val vol = when (regime) {
            Regime.RANGE -> 0.0007
            Regime.HIGH_VOL -> 0.0042
            Regime.GAP -> 0.0018
            else -> 0.0016
        }
        val pull = when (regime) {
            Regime.TREND_UP -> if (i % 29 in 24..26) -0.0016 else 0.0
            Regime.TREND_DOWN -> if (i % 29 in 24..26) 0.0016 else 0.0
            else -> 0.0
        }
        val gap = if (regime == Regime.GAP && i % 96 == 0 && i > 0) r() * 0.010 else 0.0
        val ret = drift + pull + gap + r() * vol
        val open = price * (1.0 + gap)
        val close = open * (1.0 + ret)
        val hi = max(open, close) * (1.0 + abs(r()) * vol * 0.8)
        val lo = min(open, close) * (1.0 - abs(r()) * vol * 0.8)
        out += Candle(
            time = i * m15, open = open, high = hi, low = lo, close = close,
            volume = 900.0 * (0.6 + abs(r())), closed = true,
        )
        price = close
    }
    return out
}

/** ATR(14) روی سری ماشه — برای قاعدهٔ R39. */
private fun atrSeries(candles: List<Candle>, period: Int = 14): DoubleArray {
    val out = DoubleArray(candles.size)
    var sum = 0.0
    for (i in candles.indices) {
        val prevClose = if (i > 0) candles[i - 1].close else candles[i].open
        val tr = maxOf(
            candles[i].high - candles[i].low,
            abs(candles[i].high - prevClose),
            abs(candles[i].low - prevClose),
        )
        sum += tr
        if (i >= period) {
            val prevClose2 = if (i - period > 0) candles[i - period - 1].close else candles[i - period].open
            val oldTr = maxOf(
                candles[i - period].high - candles[i - period].low,
                abs(candles[i - period].high - prevClose2),
                abs(candles[i - period].low - prevClose2),
            )
            sum -= oldTr
        }
        out[i] = if (i >= period) sum / period else if (i > 0) sum / (i + 1) else 0.0
    }
    return out
}

private fun indexAtTime(candles: List<Candle>, time: Long): Int {
    if (candles.isEmpty()) return 0
    var lo = 0
    var hi = candles.size - 1
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (candles[mid].time <= time) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** یک معاملهٔ خام موتور، غنی‌شده با ATR و سود شناور حداکثری (برای R37). */
private data class RawTrade(
    val regime: Regime,
    val action: SignalAction,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val rMultiple: Double,
    /** بیشترین سودی که این معامله در طول عمرش لمس کرد، بر حسب R. */
    val mfeR: Double,
    val atr: Double,
    val entryTime: Long,
    val strategyId: String,
)

private class Account(val startEquity: Double) {
    var equity: Double = startEquity
    var peak: Double = startEquity
    var maxDdPct: Double = 0.0
    var streak: Int = 0
    var maxStreak: Int = 0
    val rs = ArrayList<Double>()
    var pnlUsd: Double = 0.0

    fun applyPnl(pnl: Double, r: Double) {
        pnlUsd += pnl
        equity += pnl
        rs += r
        if (equity > peak) peak = equity
        val dd = if (peak > 0.0) (peak - equity) / peak * 100.0 else 0.0
        if (dd > maxDdPct) maxDdPct = dd
        if (pnl < 0.0) { streak++; if (streak > maxStreak) maxStreak = streak } else streak = 0
    }
}

private data class Replay(
    val label: String,
    val taken: Int,
    val skipped: Int,
    val winRatePct: Double,
    val netPct: Double,
    val expectancyR: Double,
    val profitFactor: Double,
    val maxDrawdownPct: Double,
    val maxLossStreak: Int,
    val lockedWinners: Int,
)

private fun summarize(
    label: String,
    acc: Account,
    taken: Int,
    skipped: Int,
    locked: Int,
): Replay {
    val rs = acc.rs
    val wins = rs.count { it > 0.0 }
    val grossWin = rs.filter { it > 0.0 }.sum()
    val grossLoss = abs(rs.filter { it < 0.0 }.sum())
    return Replay(
        label = label,
        taken = taken,
        skipped = skipped,
        winRatePct = if (rs.isEmpty()) 0.0 else wins * 100.0 / rs.size,
        netPct = (acc.equity / acc.startEquity - 1.0) * 100.0,
        expectancyR = if (rs.isEmpty()) 0.0 else rs.average(),
        profitFactor = if (grossLoss <= 0.0) if (grossWin > 0.0) 99.0 else 0.0 else grossWin / grossLoss,
        maxDrawdownPct = acc.maxDdPct,
        maxLossStreak = acc.maxStreak,
        lockedWinners = locked,
    )
}

/**
 * بازپخش. ریسک هر معامله = `riskPct`٪ از **سرمایهٔ اولیه** (بدون مرکب‌سازی) تا اعداد
 * قابل مقایسه باشند؛ برای مقایسهٔ بدترین حالت، همین کافی و صادقانه‌تر است.
 */
private fun replay(
    trades: List<RawTrade>,
    limits: TradingHabitsPolicy.Limits?,
    riskPct: Double,
    applyProfitLock: Boolean,
    startEquity: Double = 100_000.0,
    label: String = "",
): Replay {
    val acc = Account(startEquity)
    val riskUsd = startEquity * riskPct / 100.0
    var skipped = 0
    var taken = 0
    var locked = 0
    trades.sortedBy { it.entryTime }.forEach { t ->
        var r = t.rMultiple
        if (applyProfitLock && t.mfeR >= TradingHabitsPolicy.PROFIT_LOCK_AT_R && r < 0.0) {
            r = 0.0 // R37: سود ≥۱R دیده شد → خروج حداکثر سربه‌سر
            locked++
        }
        if (limits != null) {
            val d = TradingHabitsPolicy.evaluate(
                TradingHabitsPolicy.EntryRequest(
                    symbol = "XAU/USD",
                    action = t.action,
                    entry = t.entry,
                    stopLoss = t.stopLoss,
                    takeProfit = t.takeProfit,
                    atr = t.atr,
                    equityUsd = startEquity + acc.pnlUsd,
                    peakEquityUsd = startEquity + max(0.0, acc.pnlUsd),
                    requestedRiskPct = riskPct,
                    openRiskPct = 0.0,
                    lossStreak = acc.streak,
                    setupScorePct = when {
                        t.strategyId.isNotBlank() && t.atr > 0.0 && abs(t.entry - t.stopLoss) >= 1.5 * t.atr -> 92.0
                        abs(t.entry - t.stopLoss) >= t.atr -> 70.0
                        else -> 45.0
                    },
                    livePrice = t.entry,
                    openTrades = emptyList(),
                    hasQuantifiedSignal = true,
                    limits = limits,
                ),
            )
            if (!d.allowed) { skipped++; return@forEach }
            // ریسک واقعی از درصد مؤثر لایه (۱٪ سقف + ضریب‌ها) و سرمایهٔ ثابت.
            acc.applyPnl(r * (startEquity * d.effectiveRiskPct / 100.0), r)
        } else {
            acc.applyPnl(r * riskUsd, r)
        }
        taken++
    }
    val base = summarize(label, acc, taken, skipped, locked)
    return base
}

/** بیشترین افت در بدترین رژیم — معیار «در هیچ رژیمی ضرر نکند». */
private fun worstRegimeDd(trades: List<RawTrade>, limits: TradingHabitsPolicy.Limits?, riskPct: Double): Double {
    var worst = 0.0
    Regime.entries.forEach { rg ->
        val list = trades.filter { it.regime == rg }
        if (list.isEmpty()) return@forEach
        val rep = replay(list, limits, riskPct, applyProfitLock = false)
        if (rep.maxDrawdownPct > worst) worst = rep.maxDrawdownPct
    }
    return worst
}

/** مونت‌کارلو: بازآرایی تصادفی ترتیب معامله‌ها. خروجی: (افت>۲۰٪٪, میانهٔ افت, ورشکستگی٪) */
private fun monteCarlo(
    trades: List<RawTrade>,
    limits: TradingHabitsPolicy.Limits?,
    riskPct: Double,
    runs: Int,
    seed: Long,
    applyProfitLock: Boolean = false,
): Triple<Double, Double, Double> {
    val riskUsd = 100_000.0 * riskPct / 100.0
    val ddList = ArrayList<Double>(runs)
    var deep = 0
    var ruin = 0
    repeat(runs) { run ->
        val shuffled = trades.sortedBy { it.entryTime }.shuffled(kotlin.random.Random(seed + run))
        val acc = Account(100_000.0)
        shuffled.forEach { t ->
            var r = t.rMultiple
            if (applyProfitLock && t.mfeR >= TradingHabitsPolicy.PROFIT_LOCK_AT_R && r < 0.0) r = 0.0
            val effectiveRiskPct = if (limits == null) {
                riskPct
            } else {
                val d = TradingHabitsPolicy.evaluate(
                    TradingHabitsPolicy.EntryRequest(
                        symbol = "XAU/USD", action = t.action, entry = t.entry, stopLoss = t.stopLoss,
                        takeProfit = t.takeProfit, atr = t.atr, equityUsd = 100_000.0 + acc.pnlUsd,
                        peakEquityUsd = 100_000.0 + max(0.0, acc.pnlUsd), requestedRiskPct = riskPct,
                        lossStreak = acc.streak, livePrice = t.entry, openTrades = emptyList(), limits = limits,
                    ),
                )
                if (!d.allowed) return@forEach
                d.effectiveRiskPct
            }
            val pnl = r * (100_000.0 * effectiveRiskPct / 100.0)
            acc.applyPnl(pnl, r)
        }
        ddList += acc.maxDdPct
        if (acc.maxDdPct > 20.0) deep++
        if (acc.equity <= 50_000.0) ruin++
    }
    ddList.sort()
    val median = if (ddList.isEmpty()) 0.0 else ddList[ddList.size / 2]
    return Triple(deep * 100.0 / runs, median, ruin * 100.0 / runs)
}

fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 2_000
    val seed0 = args.getOrNull(1)?.toLongOrNull() ?: 11L
    val seeds = listOf(seed0, seed0 + 17, seed0 + 41)
    val regimes = Regime.entries
    val models = BitcoinStrategyModel.activeStrategyIds.toList()
    val startEquity = 100_000.0

    println("=".repeat(104))
    println("آزمایشگاه «ضرر نکن» · ${models.size} مدل × ${regimes.size} رژیم × ${seeds.size} سری · هر سری $bars کندل ۱۵دقیقه‌ای")
    println("سرمایهٔ شروع ${f2(startEquity)}$ · ریسک مرجع ۱٪ از سرمایهٔ اولیه (بدون مرکب‌سازی) · مونت‌کارلو ۲۰۰۰ اجرا")
    println("=".repeat(104))

    // ── ۱) جمع‌آوری معامله‌های موتور واقعی ────────────────────────────────────
    val allTrades = ArrayList<RawTrade>()
    val perRegime = LinkedHashMap<Regime, MutableList<RawTrade>>()
    regimes.forEach { perRegime[it] = ArrayList() }

    for (regime in regimes) {
        for (seed in seeds) {
            val series = genRegime(bars, seed, regime)
            val atr = atrSeries(series)
            for (model in models) {
                val cfg = BacktestReplayEngine.Config(
                    strategyId = model,
                    symbol = "XAU/USD",
                    warmupBars = 120,
                    riskPercent = 1.0,
                    runAllStrategies = false,
                    includeHiddenCrossLayer = false,
                )
                val res = BacktestReplayEngine.run(series, cfg)
                res.trades.filter { !it.isOpen && it.exitTime != null }.forEach { t ->
                    val iEntry = indexAtTime(series, t.entryTime)
                    val iExit = indexAtTime(series, t.exitTime ?: t.entryTime)
                    val stopDist = abs(t.entryPrice - t.stopLoss)
                    var mfe = 0.0
                    if (stopDist > 1e-9 && iExit >= iEntry) {
                        var best = 0.0
                        for (k in iEntry..iExit) {
                            val c = series[k]
                            val favorable = if (t.action == SignalAction.BUY) c.high - t.entryPrice
                            else t.entryPrice - c.low
                            if (favorable > best) best = favorable
                        }
                        mfe = best / stopDist
                    }
                    val raw = RawTrade(
                        regime = regime,
                        action = t.action,
                        entry = t.entryPrice,
                        stopLoss = t.stopLoss,
                        takeProfit = t.takeProfit,
                        rMultiple = t.rMultiple,
                        mfeR = mfe,
                        atr = if (iEntry in atr.indices) atr[iEntry] else 0.0,
                        entryTime = t.entryTime,
                        strategyId = model,
                    )
                    allTrades += raw
                    perRegime[regime]?.add(raw)
                }
            }
        }
    }

    println("\n── موتور: ${allTrades.size} معاملهٔ بسته‌شده از ${models.size} مدل و ${regimes.size} رژیم")
    if (allTrades.isEmpty()) { println("بدون معامله؛ bars را بالا ببر."); return }

    val book = TradingHabitsPolicy.Limits()

    // ── ۲) سه حالت ───────────────────────────────────────────────────────────
    val naive = replay(allTrades, null, 1.0, false, startEquity, "بی‌قاعده (۱٪ ثابت)")
    val habits = replay(allTrades, book, 1.0, false, startEquity, "با عادت‌ها")
    val habitsLock = replay(allTrades, book, 1.0, true, startEquity, "عادت‌ها + قفل سود R37")

    println("\n${"حالت".padEnd(26)}${"معامله".padStart(8)}${"رد".padStart(6)}${"برد".padStart(8)}${"خالص".padStart(11)}${"PF".padStart(7)}${"انتظار".padStart(9)}${"افت".padStart(8)}${"سری‌باخت".padStart(9)}")
    println("-".repeat(104))
    listOf(naive, habits, habitsLock).forEach { r ->
        println(
            r.label.padEnd(26) + r.taken.toString().padStart(8) + r.skipped.toString().padStart(6) +
                pct(r.winRatePct).padStart(8) + (f2(r.netPct) + "%").padStart(11) +
                f2(r.profitFactor).padStart(7) + (f2(r.expectancyR) + "R").padStart(9) +
                pct(r.maxDrawdownPct).padStart(8) + r.maxLossStreak.toString().padStart(9),
        )
    }
    println("قفل سود R37 روی ${habitsLock.lockedWinners} معاملهٔ برنده که به ضرر برمی‌گشتند فعال شد.")

    // ── ۳) به تفکیک رژیم ─────────────────────────────────────────────────────
    println("\n── به تفکیک رژیم · ستون «خالص» برای هر سه حالت")
    println("${"رژیم".padEnd(16)}${"معامله".padStart(8)}${"برد".padStart(7)}${"بی‌قاعده".padStart(12)}${"با عادت‌ها".padStart(13)}${"با قفل سود".padStart(13)}${"افت بدترین".padStart(12)}")
    println("-".repeat(104))
    perRegime.forEach { (regime, list) ->
        if (list.isEmpty()) { println(regime.labelFa.padEnd(16) + "— بدون معامله"); return@forEach }
        val a = replay(list, null, 1.0, false)
        val b = replay(list, book, 1.0, false)
        val c = replay(list, book, 1.0, true)
        println(
            regime.labelFa.padEnd(16) + a.taken.toString().padStart(8) + pct(a.winRatePct).padStart(7) +
                (f2(a.netPct) + "%").padStart(12) + (f2(b.netPct) + "%").padStart(13) +
                (f2(c.netPct) + "%").padStart(13) + pct(b.maxDrawdownPct).padStart(12),
        )
    }

    // ── ۴) مونت‌کارلو ────────────────────────────────────────────────────────
    println("\n── مونت‌کارلو (۲۰۰۰ بازآرایی ترتیب معامله‌ها)")
    val mcNaive = monteCarlo(allTrades, null, 1.0, 2000, 7L)
    val mcHabits = monteCarlo(allTrades, book, 1.0, 2000, 7L)
    val mcLock = monteCarlo(allTrades, book, 1.0, 2000, 7L, applyProfitLock = true)
    fun mcLine(name: String, mc: Triple<Double, Double, Double>) =
        String.format(Locale.US, "%-28s افت>20%%: %5.1f%%   میانهٔ بیشترین افت: %5.1f%%   ورشکستگی: %4.1f%%", name, mc.first, mc.second, mc.third)
    println(mcLine("بی‌قاعده", mcNaive))
    println(mcLine("با عادت‌ها", mcHabits))
    println(mcLine("عادت‌ها + قفل سود", mcLock))

    // ── ۴-ب) آزمون بدبینانه: لغزش و عمیق‌تر خوردن حد ضرر ────────────────────
    //
    // چرا؟ دادهٔ ساختگی ممکن است خوش‌بین باشد. این‌جا هر برد ۱۵٪ کمتر و هر باخت ۲۵٪
    // بدتر حساب می‌شود (لغزش + گپ + عمیق‌تر خوردن stop) و دوباره همان دو حالت سنجیده می‌شود.
    println("\n── آزمون بدبینانه (هر برد ×۰٫۸۵ · هر باخت ×۱٫۲۵)")
    fun pessimistic(list: List<RawTrade>): List<RawTrade> = list.map { t ->
        t.copy(rMultiple = if (t.rMultiple > 0.0) t.rMultiple * 0.85 else t.rMultiple * 1.25)
    }
    val pessTrades = pessimistic(allTrades)
    val pessNaive = replay(pessTrades, null, 1.0, false, startEquity, "بی‌قاعده · بدبینانه")
    val pessHabits = replay(pessTrades, book, 1.0, true, startEquity, "با عادت‌ها · بدبینانه")
    listOf(pessNaive, pessHabits).forEach { r ->
        println(
            r.label.padEnd(26) + r.taken.toString().padStart(8) + pct(r.winRatePct).padStart(8) +
                (f2(r.netPct) + "%").padStart(11) + f2(r.profitFactor).padStart(7) +
                (f2(r.expectancyR) + "R").padStart(9) + pct(r.maxDrawdownPct).padStart(8),
        )
    }

    // ── ۴-ج) آزمون «لبهٔ معکوس»: آیا این لایه ضرر را محدود می‌کند یا سود می‌سازد؟ ──
    //
    // اگر R همهٔ معامله‌ها را منفی کنیم (سیستمی با لبهٔ بدتر از تصادفی)، هیچ لایهٔ ریسکی
    // نباید سود بسازد؛ فقط باید **سرعت خون‌ریزی** را کم کند. اگر این‌جا سود دیدیم، یعنی
    // شبیه‌سازی اشکال دارد.
    println("\n── آزمون لبهٔ معکوس (نقش لایه: محدودکردن ضرر، نه ساختن سود)")
    // mfeR صفر می‌شود، وگرنه قاعدهٔ قفل سود روی معامله‌های معکوس هم اعمال می‌شود و آزمون را
    // بی‌معنا می‌کند (باگ اندازه‌گیری‌شده در اجرای اول: لبهٔ معکوس سود ۴۶۲٪ نشان می‌داد).
    val inverted = allTrades.map { it.copy(rMultiple = -it.rMultiple, mfeR = 0.0) }
    val invNaive = replay(inverted, null, 1.0, false, startEquity, "بی‌قاعده · لبهٔ معکوس")
    val invHabits = replay(inverted, book, 1.0, false, startEquity, "با عادت‌ها · لبهٔ معکوس")
    val invHabitsLock = replay(inverted, book, 1.0, true, startEquity, "با عادت‌ها + قفل · لبهٔ معکوس")
    listOf(invNaive, invHabits, invHabitsLock).forEach { r ->
        println(
            r.label.padEnd(26) + r.taken.toString().padStart(8) + (f2(r.netPct) + "%").padStart(11) +
                f2(r.profitFactor).padStart(7) + (f2(r.expectancyR) + "R").padStart(9) +
                pct(r.maxDrawdownPct).padStart(8),
        )
    }

    // ── ۵) جاروب پارامترها ───────────────────────────────────────────────────
    println("\n── جاروب پارامتر: کف R:R × حداقل فاصلهٔ حد ضرر بر حسب ATR")
    println("${"R:R".padStart(5)}${"ATR×".padStart(7)}${"معامله".padStart(9)}${"رد".padStart(7)}${"برد".padStart(7)}${"خالص".padStart(11)}${"PF".padStart(7)}${"انتظار".padStart(9)}${"افت".padStart(8)}${"وری".padStart(7)}")
    println("-".repeat(104))
    data class Sweep(val rr: Double, val atr: Double, val rep: Replay, val mc: Triple<Double, Double, Double>)
    val sweeps = ArrayList<Sweep>()
    for (rr in listOf(1.5, 2.0, 2.5, 3.0)) {
        for (atrMult in listOf(0.0, 1.0, 1.25, 1.5)) {
            val lim = book.copy(minRiskReward = rr, minStopAtrMultiple = atrMult)
            val rep = replay(allTrades, lim, 1.0, true)
            val mc = monteCarlo(allTrades, lim, 1.0, 500, 13L, applyProfitLock = true)
            sweeps += Sweep(rr, atrMult, rep, mc)
            println(
                f1(rr).padStart(5) + f1(atrMult).padStart(7) + rep.taken.toString().padStart(9) +
                    rep.skipped.toString().padStart(7) + pct(rep.winRatePct).padStart(7) +
                    (f2(rep.netPct) + "%").padStart(11) + f2(rep.profitFactor).padStart(7) +
                    (f2(rep.expectancyR) + "R").padStart(9) + pct(rep.maxDrawdownPct).padStart(8) +
                    pct(mc.third).padStart(7),
            )
        }
    }

    // ── ۶) حکم نهایی ─────────────────────────────────────────────────────────
    println("\n" + "=".repeat(104))
    val minSample = max(50, allTrades.size / 20)
    val eligible = sweeps.filter { it.rep.taken >= minSample && it.rep.expectancyR > 0.0 }
    val best = eligible.minWithOrNull(compareBy<Sweep> { it.rep.maxDrawdownPct }.thenByDescending { it.rep.expectancyR })
    if (best != null) {
        println("بهترین ترکیب با نمونهٔ کافی (≥$minSample معامله و انتظار مثبت): " +
            "R:R ≥ ${f1(best.rr)} · حد ضرر ≥ ${f1(best.atr)}×ATR" +
            " · ${best.rep.taken} معامله · انتظار ${f2(best.rep.expectancyR)}R · افت ${pct(best.rep.maxDrawdownPct)}" +
            " · ورشکستگی ${pct(best.mc.third)}")
    } else {
        println("⚠️ هیچ ترکیبی با نمونهٔ کافی و انتظار مثبت پیدا نشد — یعنی با این داده، سخت‌گیری بیشتر از این معامله‌ها را صفر می‌کند.")
    }

    val worstRg = Regime.entries.maxOfOrNull { rg ->
        val list = perRegime[rg].orEmpty()
        if (list.isEmpty()) 0.0 else replay(list, book, 1.0, true).maxDrawdownPct
    } ?: 0.0
    val noRuin = mcLock.third <= 0.0
    val ddOk = habitsLock.maxDrawdownPct <= 20.0
    val worstRegimeOk = worstRg <= 25.0
    val notWorseThanNaive = habitsLock.maxDrawdownPct <= naive.maxDrawdownPct + 0.01
    println("\nمعیارهای «ضرر نکن»:")
    println("  ${if (noRuin) "✅" else "❌"} احتمال ورشکستگی در مونت‌کارلو: ${pct(mcLock.third)}")
    println("  ${if (ddOk) "✅" else "❌"} بیشترین افت کل: ${pct(habitsLock.maxDrawdownPct)} (سقف ۲۰٪)")
    println("  ${if (worstRegimeOk) "✅" else "❌"} بیشترین افت در بدترین رژیم: ${pct(worstRg)} (سقف ۲۵٪)")
    println("  ${if (notWorseThanNaive) "✅" else "❌"} لایهٔ عادت‌ها بدتر از بی‌قاعده نیست: افت ${pct(habitsLock.maxDrawdownPct)} در برابر ${pct(naive.maxDrawdownPct)}")
    println("  ${if (pessHabits.expectancyR > 0.0) "✅" else "❌"} زیر لغزش بدبینانه انتظار هنوز مثبت است: ${f2(pessHabits.expectancyR)}R (افت ${pct(pessHabits.maxDrawdownPct)})")
    println("  ${if (invHabits.netPct < 0.0) "✅" else "❌"} با لبهٔ معکوس، لایه سود نمی‌سازد (${f2(invHabits.netPct)}%) — یعنی نقشش محدودکردن ضرر است، نه ساختن سود")
    println("=".repeat(104))
}
