import com.aurum.edge.core.*
import com.aurum.edge.domain.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

/**
 * # آزمون «درک مطلب» — دادهای که **باید** تشخیص داده شود
 *
 * پرسش کاربر: «در امتحانات و تستهایی که دادی، دادهای دادی که مطمئنی باید گرفته شود و آن
 * نتوانست تشخیص دهد — یا دادهای را اشتباه نگرفت؟»
 *
 * این فایل همان امتحان است. برای هر سناریو، یک سری ساختگی **طراحیشده** ساخته می‌شود که وضعیتش
 * در کتاب صریح است، و بعد ادعا بررسی می‌شود:
 *
 * | سناریو | ادعا |
 * |---|---|
 * | روند صعودی تمیز + پولبک به کیجون + کندل ماشه | موتور **باید** در همان پنجره سیگنال خرید بدهد |
 * | همان سری، آینهٔ نزولی | موتور **باید** سیگنال فروش بدهد |
 * | بازار رِنج بدون جهت | موتور **نباید** سیگنال بدهد (معاملهٔ بی‌سیگنال = تخلف R21) |
 * | روند نزولی + یک پامپ صعودی چندکندلی | موتور **نباید** گول بخورد و خرید بدهد |
 * | روند صعودی که با ضربه به زیر ابر رسیده | موتور **نباید** خرید بدهد (شرط ۱: رژیم کومو) |
 *
 * برای هر شکست، «کدام شرط سد کرد» چاپ می‌شود تا معلوم شود باگ است یا کمبود واقعی در داده.
 *
 * اجرا: `tools/strategy_lab/run.sh 2400 11 conform`
 */

private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)

private fun lcg(seed: Long): () -> Double {
    var s = seed
    return {
        s = s * 6364136223846793005L + 1442695040888963407L
        ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }
}

private class Builder(private val m15: Long = Interval.M15.millis) {
    var price: Double = 2_400.0
    private var t: Long = 0
    val out = ArrayList<Candle>()

    /** کیجون ۲۶ دوره‌ای روی همان کندل‌های ساخته‌شده (تایم ماشه). */
    fun kijun(period: Int = 26): Double {
        if (out.size < period) return price
        val slice = out.subList(out.size - period, out.size)
        return (slice.maxOf { it.high } + slice.minOf { it.low }) / 2.0
    }

    /** ATR سادهٔ ۱۴ دوره‌ای روی کندل‌های ساخته‌شده. */
    fun atr(period: Int = 14): Double {
        if (out.size < 2) return price * 0.002
        var sum = 0.0
        var n = 0
        for (i in max(1, out.size - period) until out.size) {
            val pc = out[i - 1].close
            sum += maxOf(out[i].high - out[i].low, abs(out[i].high - pc), abs(out[i].low - pc))
            n++
        }
        return if (n == 0) price * 0.002 else sum / n
    }

    /**
     * پولبک واقعی تا **خودِ کیجون**: در هر کندل ۱۵٪ فاصله تا کیجون را می‌بندد.
     * (نسخهٔ قبلی این آزمون فقط ۰٫۹٪ افت می‌داد و قیمت ۶٫۸ ATR بالای کیجون می‌ماند — یعنی
     * داده «پولبک به تعادل» نبود و انتظار «باید سیگنال بدهد» غلط بود.)
     */
    fun pullToKijun(maxBars: Int, seed: Long, noise: Double = 0.00035) {
        val r = lcg(seed)
        repeat(maxBars) {
            val gap = kijun() - price
            if (abs(gap) < atr() * 0.15) return
            val ret = (gap / price) * 0.15 + r() * noise
            val open = price
            val close = open * (1.0 + ret)
            val hi = max(open, close) * (1.0 + abs(r()) * noise * 0.5)
            val lo = min(open, close) * (1.0 - abs(r()) * noise * 0.5)
            out += Candle(
                time = t, open = open, high = hi, low = lo, close = close,
                volume = 900.0 * (0.7 + abs(r()) * 0.5), closed = true,
            )
            price = close
            t += m15
        }
    }

    /**
     * پولبک تا **کیجون تایم‌فریم استراتژی (H1)**: ۲۶ کندل ۱ ساعته = ۱۰۴ کندل ۱۵ دقیقه‌ای.
     *
     * چرا این هم لازم شد؟ موتور، «تعادل» را روی تایم استراتژی (H1) می‌سنجد (شرط ۳)، نه روی
     * ماشه. پولبک تا کیجون ۲۶×۱۵ دقیقه‌ای فقط ۶٫۵ ساعت است و شرط ۳ شیب H1 را قانع نمی‌کند.
     */
    fun pullToStrategyKijun(maxBars: Int, seed: Long, periodM15: Int = 104, noise: Double = 0.00035) {
        val r = lcg(seed)
        repeat(maxBars) {
            val slice = out.takeLast(periodM15)
            val kj = if (slice.size < periodM15) kijun() else (slice.maxOf { it.high } + slice.minOf { it.low }) / 2.0
            val gap = kj - price
            if (abs(gap) < atr() * 0.2) return
            val ret = (gap / price) * 0.12 + r() * noise
            val open = price
            val close = open * (1.0 + ret)
            val hi = max(open, close) * (1.0 + abs(r()) * noise * 0.5)
            val lo = min(open, close) * (1.0 - abs(r()) * noise * 0.5)
            out += Candle(
                time = t, open = open, high = hi, low = lo, close = close,
                volume = 900.0 * (0.7 + abs(r()) * 0.5), closed = true,
            )
            price = close
            t += m15
        }
    }

    /**
     * ماشهٔ واقعی: کندل‌های پرقدرت تا وقتی کلوز از **سقف ۲۶ کندل قبل** (مرجع چیکو) بگذرد.
     * این همان «شکست ساختاری چیکو» شرط ۷ تاتل است.
     */
    fun breakoutUp(maxBars: Int, seed: Long, step: Double = 0.0011, volumeMul: Double = 2.2): Int {
        val r = lcg(seed)
        var done = 0
        repeat(maxBars) {
            if (done > 0 && out.size >= 27) {
                val chikouRef = out[out.size - 27].high
                if (price > chikouRef) return done
            }
            val open = price
            val close = open * (1.0 + step + r() * 0.0002)
            val hi = close * (1.0 + abs(r()) * 0.0002)
            val lo = open * (1.0 - abs(r()) * 0.00015)
            out += Candle(time = t, open = open, high = hi, low = lo, close = close,
                volume = 1_000.0 * volumeMul * (0.9 + abs(r()) * 0.4), closed = true)
            price = close
            t += m15
            done++
        }
        return done
    }

    /** آینهٔ نزولی ماشه. */
    fun breakoutDown(maxBars: Int, seed: Long, step: Double = 0.0011, volumeMul: Double = 2.2): Int {
        val r = lcg(seed)
        var done = 0
        repeat(maxBars) {
            if (done > 0 && out.size >= 27) {
                val chikouRef = out[out.size - 27].low
                if (price < chikouRef) return done
            }
            val open = price
            val close = open * (1.0 - step + r() * 0.0002)
            val hi = open * (1.0 + abs(r()) * 0.00015)
            val lo = close * (1.0 - abs(r()) * 0.0002)
            out += Candle(time = t, open = open, high = hi, low = lo, close = close,
                volume = 1_000.0 * volumeMul * (0.9 + abs(r()) * 0.4), closed = true)
            price = close
            t += m15
            done++
        }
        return done
    }

    /** فاز با دریفت و نویز ثابت. `volumeMul` برای ساخت کندل‌های پرقدرت ماشه. */
    fun phase(bars: Int, drift: Double, vol: Double, seed: Long, volumeMul: Double = 1.0, bodyBias: Double = 0.0) {
        val r = lcg(seed)
        repeat(bars) {
            val ret = drift + bodyBias + r() * vol
            val open = price
            val close = open * (1.0 + ret)
            val hi = max(open, close) * (1.0 + abs(r()) * vol * 0.6)
            val lo = min(open, close) * (1.0 - abs(r()) * vol * 0.6)
            out += Candle(
                time = t, open = open, high = hi, low = lo, close = close,
                volume = 1_000.0 * volumeMul * (0.7 + abs(r()) * 0.6),
                closed = true,
            )
            price = close
            t += m15
        }
    }
}

private data class Scenario(
    val name: String,
    val candles: List<Candle>,
    /** بازهٔ کندل‌هایی که ادعا در آن‌ها بررسی می‌شود. */
    val fromBar: Int,
    val toBar: Int,
    val expect: SignalAction?, // null = نباید هیچ سیگنالی بدهد
    val noteFa: String,
    /** ادعا نیست؛ فقط برای گزارش شمرده می‌شود (در درصد قبولی حساب نمی‌شود). */
    val informational: Boolean = false,
)

/** سناریوهای «باید بگیرد / نباید بگیرد». */
private fun scenarios(bars: Int, seed: Long): List<Scenario> {
    val list = ArrayList<Scenario>()
    val warm = bars / 4
    val trend = bars / 3

    // ۱) روند صعودی واقعی + **پولبک تا خود کیجون** + ماشهٔ شکست ساختاری چیکو
    val upSeries = run {
        val b = Builder()
        b.phase(warm, 0.00002, 0.0007, seed)          // پایهٔ آرام برای ساخت ابر
        b.phase(trend, 0.00030, 0.0008, seed + 1)     // روند صعودی
        b.pullToKijun(maxBars = 200, seed = seed + 2) // پولبک واقعی به کیجون (تایم ماشه)
        val triggerStart = b.out.size
        val trigBars = b.breakoutUp(maxBars = 12, seed = seed + 3)
        b.phase(bars / 6, 0.00028, 0.0008, seed + 4)
        Triple(b.out, triggerStart, min(b.out.size, triggerStart + trigBars + 12))
    }
    list += Scenario(
        name = "روند صعودی + پولبک به کیجون + شکست چیکو",
        candles = upSeries.first,
        fromBar = upSeries.second,
        toBar = upSeries.third,
        expect = SignalAction.BUY,
        noteFa = "کتاب (پاتل/تاتل): پولبک به تعادل در روند هم‌جهت + شکست ساختاری = ستاپ خرید",
    )

    // ۲) آینهٔ **دقیق** سری بالا: P' = 2·P0 − P. اگر موتور متقارن باشد باید در همان کندل SELL بدهد.
    run {
        val pivot = 2.0 * upSeries.first.first().close
        val mirrored = upSeries.first.map {
            Candle(
                time = it.time, open = pivot - it.open, high = pivot - it.low,
                low = pivot - it.high, close = pivot - it.close, volume = it.volume, closed = it.closed,
            )
        }
        list += Scenario(
            name = "آینهٔ دقیق همان سری (آزمون تقارن جهت)",
            candles = mirrored,
            fromBar = upSeries.second, toBar = upSeries.third,
            expect = SignalAction.SELL,
            noteFa = "اگر موتور جهت‌کور نباشد، در همان کندل سیگنال مخالف می‌دهد",
        )
    }

    // ۳) کنترل منفی: سری **بی‌جهت** (دریفت صفر، نوسان کوچک) — چاپ لازم R21
    run {
        val b = Builder()
        b.phase(bars, 0.0, 0.0006, seed + 20)
        list += Scenario(
            name = "سری بی‌جهت (دریفت صفر)",
            candles = b.out, fromBar = bars / 4, toBar = bars - 5,
            expect = null,
            noteFa = "هیچ روندی وجود ندارد → هر سیگنالی مثبت کاذب است (R21)",
        )
    }

    // ۳ب) رِنج نوسانی (موج سینوسی): **اطلاعاتی، نه ادعا**
    run {
        val b = Builder()
        val r = lcg(seed + 21)
        repeat(bars) {
            val wave = kotlin.math.sin(it / 34.0) * 0.0035 + kotlin.math.sin(it / 11.0) * 0.0012
            val ret = wave * 0.25 + r() * 0.0009
            val open = b.price
            val close = open * (1.0 + ret)
            val hi = max(open, close) * (1.0 + abs(r()) * 0.00045)
            val lo = min(open, close) * (1.0 - abs(r()) * 0.00045)
            b.out += Candle(
                time = it * Interval.M15.millis, open = open, high = hi, low = lo, close = close,
                volume = 900.0 * (0.7 + abs(r()) * 0.5), closed = true,
            )
            b.price = close
        }
        list += Scenario(
            name = "موج سینوسی (نیم‌چرخه‌ها روند کوتاه‌اند) — اطلاعاتی",
            candles = b.out, fromBar = bars / 3, toBar = bars - 5,
            expect = null, informational = true,
            noteFa = "هر نیم‌چرخه یک روند ۱۵–۲۰ کندلی است؛ معامله در آن تخلف نیست، فقط شمرده می‌شود",
        )
    }

    // ۴) روند نزولی + پامپ صعودی چندکندلی (تلهٔ فیک‌بریک)
    run {
        val b = Builder()
        b.phase(warm, -0.00003, 0.0007, seed + 30)
        b.phase(trend, -0.00030, 0.0008, seed + 31)
        val start = b.out.size
        b.phase(12, 0.0012, 0.0004, seed + 32, volumeMul = 2.0)
        b.phase(bars / 6, -0.00025, 0.0008, seed + 33)
        list += Scenario(
            name = "روند نزولی + پامپ تله",
            candles = b.out, fromBar = start, toBar = start + 14,
            expect = null,
            noteFa = "خرید در پامپ خلاف روند = «معاملهٔ بد» R21/R14",
        )
    }

    // ۵) روند صعودی که با ضربه به زیر ابر رسیده (رژیم کومو شکسته)
    run {
        val b = Builder()
        b.phase(warm, 0.00002, 0.0007, seed + 40)
        b.phase(trend, 0.00030, 0.0008, seed + 41)
        val start = b.out.size
        b.phase(40, -0.0011, 0.0012, seed + 42, volumeMul = 1.5)  // ریزش به زیر ابر
        b.phase(20, 0.0006, 0.0006, seed + 43, volumeMul = 1.3)    // بازگشت جزئی
        b.phase(bars / 8, -0.0001, 0.0009, seed + 44)
        list += Scenario(
            name = "شکست رژیم کومو (ریزش زیر ابر)",
            candles = b.out, fromBar = start + 40, toBar = start + 60,
            expect = null,
            noteFa = "شرط ۱: قیمت زیر ابر → خرید ممنوع",
        )
    }
    return list
}

private fun runScenario(sc: Scenario, bars: Int): Boolean {
    val macro = BacktestSeries.resample(sc.candles, Interval.H4)
    val strat = BacktestSeries.resample(sc.candles, Interval.H1)
    val trigger = Interval.M15.millis
    val cfg = BaseStrategyConfig()
    val model = BitcoinStrategyModel.BASE_ICHIMOKU

    var fired = 0
    var episodes = 0
    var lastFireBar = -999
    var firstFireBar = -1
    var firstFireAction: SignalAction? = null
    val blockers = LinkedHashMap<String, Int>()
    var evaluated = 0

    for (i in sc.fromBar until min(sc.toBar, sc.candles.size)) {
        val now = sc.candles[i].time + trigger
        val mw = BacktestSeries.closedUpTo(macro, Interval.H4.millis, now)
        val sw = BacktestSeries.closedUpTo(strat, Interval.H1.millis, now)
        val tw = BacktestSeries.closedUpTo(sc.candles, trigger, now)
        if (mw.size < 30 || sw.size < 30 || tw.size < 60) continue
        val rep = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = model,
            config = cfg,
            macroCandles = mw, strategyCandles = sw, triggerCandles = tw,
            livePriceOverride = sc.candles[i].close,
            nowMillis = now, symbolInput = "XAU/USD",
        )
        evaluated++
        if (System.getenv("CONFORM_TRACE") == "2") {
            println("      · کندل $i readiness=${rep.readiness.name} action=${rep.action.name} pass=${rep.conditions.count { it.passed }}/${rep.conditions.size} gate=${rep.bookGateScorePct}%")
        }
        if (rep.readiness == StrategyReadinessState.SIGNAL_BUY || rep.readiness == StrategyReadinessState.SIGNAL_SELL) {
            fired++
            if (i - lastFireBar > 5) episodes++
            lastFireBar = i
            if (firstFireBar < 0) { firstFireBar = i; firstFireAction = rep.action }
        } else {
            rep.conditions.filter { !it.passed }.forEach { c ->
                blockers[c.titleFa] = (blockers[c.titleFa] ?: 0) + 1
            }
        }
    }

    if (System.getenv("CONFORM_TRACE") != null) {
        // جزئیات کامل شرط‌ها روی «بهترین» کندل بازه (بیشترین شرط پاس‌شده)
        var bestBar = -1; var bestPass = -1
        var bestRep: StrategyEvaluationReport? = null
        for (i in sc.fromBar until min(sc.toBar, sc.candles.size)) {
            val now = sc.candles[i].time + trigger
            val mw = BacktestSeries.closedUpTo(macro, Interval.H4.millis, now)
            val sw = BacktestSeries.closedUpTo(strat, Interval.H1.millis, now)
            val tw = BacktestSeries.closedUpTo(sc.candles, trigger, now)
            if (mw.size < 30 || sw.size < 30 || tw.size < 60) continue
            val rep = IchimokuStrategyEngine.evaluateBitcoinStrategy(
                activeModel = model, config = cfg,
                macroCandles = mw, strategyCandles = sw, triggerCandles = tw,
                livePriceOverride = sc.candles[i].close, nowMillis = now, symbolInput = "XAU/USD",
            )
            val p = rep.conditions.count { it.passed }
            if (p > bestPass) { bestPass = p; bestBar = i; bestRep = rep }
        }
        if (bestRep != null) {
            println("   ── ردپا (کندل $bestBar · ${bestPass}/${bestRep.conditions.size} شرط · readiness=${bestRep.readiness.name})")
            bestRep.conditions.forEach { c ->
                println("      ${if (c.passed) "✓" else "✗"} ${c.index}. ${c.titleFa} — ${c.detailFa}")
            }
        }
    }

    val ok = when (sc.expect) {
        null -> fired == 0
        else -> firstFireAction == sc.expect
    }
    val mark = when { sc.informational -> "ℹ️"; ok -> "✅"; else -> "❌" }
    println("\n$mark ${sc.name}  ·  ${sc.noteFa}")
    println("   ارزیابی=$evaluated · سیگنال=$fired · ستاپ‌های مستقل=$episodes" + if (firstFireBar >= 0) " · اولین در کندل $firstFireBar (${firstFireAction?.name})" else "")
    if (!ok) {
        println("   سدهای غالب: " + blockers.entries.sortedByDescending { it.value }.take(6)
            .joinToString(" · ") { "${it.value}× ${it.key}" })
    }
    return if (sc.informational) true else ok
}

/**
 * آزمون **تقارن جهت** با آینهٔ دقیق قیمت.
 *
 * اگر موتور نسبت به جهت بی‌طرف باشد، سریِ آینه‌شده (`P' = 2·P0 − P`) باید در همان کندل،
 * همان شرط‌ها را پاس کند و سیگنال **مخالف** بدهد. هر اختلاف، باگ عدم‌تقارن است.
 */
private fun mirrorAudit(bars: Int, seed: Long): Boolean {
    val b = Builder()
    b.phase(bars / 4, 0.00002, 0.0007, seed)
    b.phase(bars / 3, 0.00030, 0.0008, seed + 1)
    b.pullToKijun(maxBars = 200, seed = seed + 2)
    val from = b.out.size
    val trigBars = b.breakoutUp(maxBars = 12, seed = seed + 3, step = 0.0011, volumeMul = 2.2)
    b.phase(bars / 6, 0.00028, 0.0008, seed + 4)
    val to = min(b.out.size, from + trigBars + 12)

    val pivot = 2.0 * b.out.first().close
    val mirrored = b.out.map {
        Candle(
            time = it.time, open = pivot - it.open, high = pivot - it.low,
            low = pivot - it.high, close = pivot - it.close, volume = it.volume, closed = it.closed,
        )
    }

    fun evalSeries(candles: List<Candle>, i: Int): StrategyEvaluationReport? {
        val macro = BacktestSeries.resample(candles, Interval.H4)
        val strat = BacktestSeries.resample(candles, Interval.H1)
        val now = candles[i].time + Interval.M15.millis
        val mw = BacktestSeries.closedUpTo(macro, Interval.H4.millis, now)
        val sw = BacktestSeries.closedUpTo(strat, Interval.H1.millis, now)
        val tw = BacktestSeries.closedUpTo(candles, Interval.M15.millis, now)
        if (mw.size < 30 || sw.size < 30 || tw.size < 60) return null
        return IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.BASE_ICHIMOKU,
            config = BaseStrategyConfig(),
            macroCandles = mw, strategyCandles = sw, triggerCandles = tw,
            livePriceOverride = candles[i].close, nowMillis = now, symbolInput = "XAU/USD",
        )
    }

    println("=".repeat(96))
    println("آزمون تقارن جهت با آینهٔ دقیق قیمت  (کندل $from..$to)")
    println("=".repeat(96))
    println(String.format(Locale.US, "%-5s | %-14s %-6s | %-14s %-6s | شرط‌های ناهمخوان", "کندل", "صعودی", "پاس", "آینه (نزولی)", "پاس"))
    var buyFire = -1
    var sellFire = -1
    var worstAsym = 0
    var asymDetails = ""
    for (i in from until to) {
        val up = evalSeries(b.out, i) ?: continue
        val dn = evalSeries(mirrored, i) ?: continue
        val upPass = up.conditions.count { it.passed }
        val dnPass = dn.conditions.count { it.passed }
        val diff = up.conditions.filter { c ->
            val d = dn.conditions.firstOrNull { it.index == c.index }
            d != null && d.passed != c.passed
        }.map { "#${it.index}" }
        if (diff.size > worstAsym) { worstAsym = diff.size; asymDetails = "کندل $i: ${diff.joinToString(",")}" }
        if (buyFire < 0 && up.readiness == StrategyReadinessState.SIGNAL_BUY) buyFire = i
        if (sellFire < 0 && dn.readiness == StrategyReadinessState.SIGNAL_SELL) sellFire = i
        println(String.format(Locale.US, "%-5d | %-14s %-6d | %-14s %-6d | %s",
            i, up.action.name, upPass, dn.action.name, dnPass,
            if (diff.isEmpty()) "—" else diff.joinToString(",")))
    }
    val symOk = buyFire == sellFire && buyFire >= 0
    println("\nنتیجهٔ تقارن: " + if (symOk) "✅ متقارن (سیگنال خرید در کندل $buyFire و فروش در همان کندل)"
        else "❌ نامتقارن — خرید در کندل $buyFire · فروش در کندل $sellFire")
    println("بیشترین اختلاف شرط بین سری و آینهٔ آن: $worstAsym ($asymDetails)")
    return symOk
}

fun main(args: Array<String>) {
    val bars = args.getOrNull(0)?.toIntOrNull() ?: 2_400
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 11L
    println("=".repeat(96))
    println("آزمون درک مطلب: آیا موتور داده‌ای را که **باید** ببیند، می‌بیند؟  (bars=$bars seed=$seed)")
    println("=".repeat(96))
    if (System.getenv("CONFORM_MIRROR") != null) {
        mirrorAudit(bars, seed)
        return
    }
    val only = System.getenv("CONFORM_ONLY")?.toIntOrNull()
    val all = scenarios(bars, seed)
    val chosen = if (only != null) all.filterIndexed { i, _ -> i == only - 1 } else all
    val results = chosen.map { runScenario(it, bars) }
    val passed = results.count { it }
    println("\n" + "=".repeat(96))
    println("نتیجه: $passed از ${results.size} سناریو مطابق انتظار بود")
    println("=".repeat(96))
}
