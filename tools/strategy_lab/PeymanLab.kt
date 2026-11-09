import com.aurum.edge.core.BacktestCsvReader
import com.aurum.edge.core.peyman.PeymanCryptoConfig
import com.aurum.edge.core.peyman.PeymanCryptoEngine
import com.aurum.edge.core.peyman.PeymanCryptoInput
import com.aurum.edge.core.peyman.PeymanSetupStatus
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import java.io.File
import java.util.Locale

/**
 * چرتکهٔ کامل «پیمان کریپتو» روی دادهٔ واقعی — روی **هر** کندل، نه فقط کندل‌هایی که
 * یک معامله باز شده است.
 *
 * چرا لازم بود؟ نسخهٔ اول آمار را از `entryReports` می‌گرفت، یعنی فقط کندل‌هایی که
 * موتور استراتژی روی آن‌ها معامله باز کرده بود؛ نتیجه اینکه «۲ ورود مجاز از ۲۰۴»
 * گزارش می‌شد در حالی که ۲۰۴ خودش تعداد معامله‌های *بیس* بود. برای «بهینه‌سازی اصولی»
 * باید بدانیم در کل ۶۴ هزار کندل، کدام گیت چند بار بسته است.
 *
 * استفاده:
 *   tools/strategy_lab/run.sh 0 0 pey <trigger.csv> <h1.csv> <h4.csv> [SYMBOL] [step]
 *   step = هر چند کندل یک‌بار ارزیابی شود (پیش‌فرض ۱ = همهٔ کندل‌ها)
 */
private fun readCsv(path: String): List<Candle> {
    val f = File(path)
    require(f.isFile) { "فایل پیدا نشد: $path" }
    return BacktestCsvReader.parse(raw = f.readText(), fileName = f.name).candles
}

private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)

fun main(args: Array<String>) {
    val triggerPath = args.getOrNull(2) ?: error("مسیر CSV ماشه لازم است")
    val strategyPath = args.getOrNull(3) ?: error("مسیر CSV ۱ساعته لازم است")
    val macroPath = args.getOrNull(4) ?: error("مسیر CSV ۴ساعته لازم است")
    val symbol = args.getOrNull(5) ?: "BTC/USDT"
    val step = args.getOrNull(6)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    val trigger = readCsv(triggerPath)
    val strategy = readCsv(strategyPath)
    val macro = readCsv(macroPath)
    // اختیاری: پنجرهٔ انتظار بازآزمایی (کندل) — برای سنجش حساسیت، نه تغییر پیش‌فرض.
    val waitOverride = args.getOrNull(7)?.toIntOrNull()
    // آرگومان هشتم: دقیقه‌های تایم‌فریم ماشه (۵ یا ۱۵). نهم: پکیج قواعد.
    val triggerMinutes = args.getOrNull(8)?.toIntOrNull() ?: 15
    val preset = args.getOrNull(9)?.lowercase() ?: "paper"
    val triggerInterval = when (triggerMinutes) {
        5 -> Interval.M5
        15 -> Interval.M15
        30 -> Interval.M30
        60 -> Interval.H1
        else -> Interval.M15
    }
    println("نماد=$symbol · ماشه=${trigger.size} · ۱ساعته=${strategy.size} · ۴ساعته=${macro.size} · گام=$step" +
        (if (waitOverride != null) " · پنجرهٔ بازآزمایی=$waitOverride" else ""))

    val baseCfg = if (preset == "trading") PeymanCryptoConfig.tradingFor(triggerMinutes)
    else PeymanCryptoConfig.PAPER
    val cfg = if (waitOverride != null) baseCfg.copy(maxRetestWaitBars = waitOverride) else baseCfg
    println("  پکیج=$preset · تایم‌فریم ماشه=${triggerInterval.label} · rulesVersion=${cfg.rulesVersion} · پنجرهٔ بازآزمایی=${cfg.maxRetestWaitBars}")
    var evaluated = 0
    var allowed = 0
    val byStatus = LinkedHashMap<String, Int>()
    val bySetup = LinkedHashMap<String, Int>()
    val byDecision = LinkedHashMap<String, Int>()
    val byGate = LinkedHashMap<String, Int>()
    val byRegime = LinkedHashMap<String, Int>()
    val rrNear = ArrayList<Double>()
    // فقط لحظه‌هایی که ستاپ «آماده» است: اینجا آخرین دروازه‌ها حرف می‌زنند.
    val readyGates = LinkedHashMap<String, Int>()
    val readyScores = ArrayList<Double>()
    val readyRr = ArrayList<Double>()
    val byBos = LinkedHashMap<String, Int>()

    val start = minOf(trigger.size - 1, 300)
    // دو اشاره‌گر یک‌طرفه: برای هر کندل ماشه، آخرین کندل بسته‌شدهٔ ۱ساعته/۴ساعته.
    // (فیلتر کردن کل لیست در هر تکرار O(n²) می‌شد و روی ۶۴ هزار کندل عملاً نمی‌رسید.)
    var sIdx = 0
    var mIdx = 0
    for (i in start until trigger.size step step) {
        val t = trigger[i].time
        while (sIdx < strategy.size && strategy[sIdx].time <= t) sIdx++
        while (mIdx < macro.size && macro[mIdx].time <= t) mIdx++
        // فقط کندل‌های بسته‌شدهٔ همان لحظه — مثل اجرای زنده، نه آینده.
        val trig = trigger.subList(maxOf(0, i - 299), i + 1)
        val strat = strategy.subList(maxOf(0, sIdx - 300), sIdx)
        val mac = macro.subList(maxOf(0, mIdx - 300), mIdx)
        if (strat.size < cfg.minBars || mac.size < cfg.minBars || trig.size < cfg.minBars) continue

        val decision = PeymanCryptoEngine.decide(
            PeymanCryptoInput(
                symbol = symbol,
                macroBars = mac,
                strategyBars = strat,
                triggerBars = trig,
                openPositions = emptyList(),
                config = cfg,
                nowMillis = t + triggerInterval.millis,
                triggerInterval = triggerInterval,
            ),
        )
        evaluated++
        if (decision.isTradeAllowed) allowed++
        byStatus[decision.setupStatus.name] = (byStatus[decision.setupStatus.name] ?: 0) + 1
        byBos[decision.trace.bosStatusFa.take(44)] = (byBos[decision.trace.bosStatusFa.take(44)] ?: 0) + 1
        bySetup[decision.setupType.labelFa] = (bySetup[decision.setupType.labelFa] ?: 0) + 1
        byDecision[decision.trace.finalDecisionFa] = (byDecision[decision.trace.finalDecisionFa] ?: 0) + 1
        byRegime[decision.trace.regimeFa] = (byRegime[decision.trace.regimeFa] ?: 0) + 1
        for (g in decision.trace.hardGateFailures) byGate[g.take(60)] = (byGate[g.take(60)] ?: 0) + 1
        if (decision.qualityScore >= 60.0) decision.riskRewardAfterCosts?.let { rrNear.add(it) }
        if (decision.setupStatus == PeymanSetupStatus.READY) {
            readyScores.add(decision.qualityScore)
            decision.riskRewardAfterCosts?.let { readyRr.add(it) }
            for (g in decision.trace.hardGateFailures) readyGates[g.take(60)] = (readyGates[g.take(60)] ?: 0) + 1
        }
    }

    println("ارزیابی‌شده=$evaluated · ورودِ مجاز=$allowed (${f1(allowed * 100.0 / maxOf(1, evaluated))}٪)")
    fun dump(title: String, map: Map<String, Int>, take: Int = 8) {
        println("  $title: " + map.entries.sortedByDescending { it.value }.take(take)
            .joinToString(" · ") { "${it.key}=${it.value}" })
    }
    dump("وضعیت ستاپ", byStatus)
    dump("وضعیت BOS", byBos, 8)
    dump("نوع ستاپ", bySetup)
    dump("تصمیم نهایی", byDecision, 10)
    dump("رژیم", byRegime, 6)
    dump("گیت‌های سخت", byGate, 10)
    if (readyScores.isNotEmpty()) {
        val sc = readyScores.sorted()
        val rr = readyRr.sorted()
        println("  ستاپِ «آماده»=${sc.size} · امتیاز: میانه=${f1(sc[sc.size / 2])} بیشینه=${f1(sc.last())}" +
            (if (rr.isEmpty()) "" else " · R:R مؤثر: میانه=${f1(rr[rr.size / 2])} بیشینه=${f1(rr.last())}"))
        dump("گیت‌های سخت در لحظهٔ آماده", readyGates, 10)
    }
    if (rrNear.isNotEmpty()) {
        val s = rrNear.sorted()
        println("  R:R مؤثرِ نزدیک‌به‌ورود (امتیاز≥۶۰): n=${s.size} · میانه=${f1(s[s.size / 2])} · بیشینه=${f1(s.last())}")
    }
}
