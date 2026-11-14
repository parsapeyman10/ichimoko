import com.aurum.edge.core.*
import com.aurum.edge.domain.*
import java.io.File
import java.util.Locale

/**
 * آزمایشگاه «دادهٔ واقعی» — همان موتور استراتژی بیس، روی فایل CSV واقعی.
 *
 * چرا؟ هارنس `Lab.kt` روی سری مصنوعی کار می‌کند و برای A/B کافی است، ولی هیچ عددی
 * از آن، اعتبار عدد روی طلای واقعی نیست. این فایل CSV واقعی را با **همان خوانندهٔ
 * خود برنامه** (`BacktestCsvReader`) می‌خواند تا تفسیر ستون‌ها، حذف کندل‌های تخت و
 * تشخیص تایم‌فریم، مو‌به‌مو همان چیزی باشد که APK انجام می‌دهد.
 *
 * سه سؤال را با عدد جواب می‌دهد:
 *  ۱. با این داده، استراتژی بیس چند معامله می‌گیرد و نتیجه‌اش چیست؟
 *  ۲. آیا تایم‌فریم‌های بالا از روی همان سری بازسازی شوند (resample) یا فایل واقعی
 *     ۴ ساعته/۱ ساعته داده شود، تفاوت واقعی دارد؟
 *  ۳. لایهٔ «هیدن کراس» جزء شرط‌ها باشد یا نه (A/B واقعی، نه حدس).
 *
 * استفاده:
 *   tools/strategy_lab/run.sh 0 0 csv <trigger.csv> [strategy-1h.csv] [macro-4h.csv] [SYMBOL]
 *   مثال: tools/strategy_lab/run.sh 0 0 csv /tmp/xau/m15.csv /tmp/xau/h1.csv /tmp/xau/h4.csv XAU/USD
 */
private fun readCsv(path: String, labelFa: String): BacktestCsvReader.ParseResult {
    val f = File(path)
    require(f.isFile) { "$labelFa پیدا نشد: $path" }
    val parsed = BacktestCsvReader.parse(raw = f.readText(), fileName = f.name)
    println(
        String.format(
            Locale.US,
            "[%s] ردیف=%d · حذف‌شده=%d · تخت=%d · نوع=%s · تایم‌فریم=%s · بازه=%.1f روز · %d کندل",
            labelFa, parsed.totalRows, parsed.skippedRows, parsed.flatRowsDropped,
            parsed.sourceKind, parsed.inferredInterval.label, parsed.spanDays, parsed.candles.size,
        ),
    )
    return parsed
}

/** میانهٔ دامنهٔ کندل بر حسب بیسیس‌پوینت — جانشین صادق برای «هزینهٔ» ناشناخته. */
private fun medianRangeBp(candles: List<Candle>): Double {
    if (candles.isEmpty()) return 0.0
    val bp = candles.mapNotNull { c ->
        if (c.close <= 0.0) null else (c.high - c.low) / c.close * 10_000.0
    }.sorted()
    return if (bp.isEmpty()) 0.0 else bp[bp.size / 2]
}

private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)

/** هر معامله را در یک CSV می‌ریزد تا بشود پرسید «کدام ساعت/سشن/دلیل خروج پول می‌سوزاند». */
private fun dumpTrades(labelFa: String, symbol: String, res: BacktestReplayEngine.Result) {
    val safe = symbol.replace('/', '_').replace(" ", "")
    val file = File("/tmp/lab_trades_${safe}_${labelFa.replace('/', '_')}.csv")
    val sb = StringBuilder("entryTime,exitTime,action,entry,stopLoss,takeProfit,plannedRR,exitReason,rMultiple,confidence,barsHeld\n")
    for (t in res.trades) {
        sb.append(t.entryTime).append(',')
            .append(t.exitTime ?: 0L).append(',')
            .append(t.action).append(',')
            .append(f2(t.entryPrice)).append(',')
            .append(f2(t.stopLoss)).append(',')
            .append(f2(t.takeProfit)).append(',')
            .append(f2(t.plannedRiskReward)).append(',')
            .append(t.exitReason).append(',')
            .append(f2(t.rMultiple)).append(',')
            .append(f2(t.confidence)).append(',')
            .append(t.barsHeld).append('\n')
    }
    file.writeText(sb.toString())
    println("        معامله‌ها برای تحلیل: ${file.path}")
}

private fun runOne(labelFa: String, candles: List<Candle>, base: BacktestReplayEngine.Config) {
    val started = System.currentTimeMillis()
    val res = BacktestReplayEngine.run(candles, base)
    val st = res.stats.firstOrNull()
    val ms = System.currentTimeMillis() - started
    if (st == null) {
        println("[$labelFa] هیچ آماری برنگشت (کندل کافی نبود؟)")
        return
    }
    println(
        String.format(
            Locale.US,
            "[%s] معامله=%d (خرید %d / فروش %d) · برد=%.1f%% · PF=%.2f · انتظار=%+.3fR · خالص=%+.2f$ · حداکثر افت=%.2f%% · نگه‌داری=%.1f کندل",
            labelFa, st.trades, st.longTrades, st.shortTrades, st.winRatePct, st.profitFactor,
            st.expectancyR, st.netPnlUsd, st.maxDrawdownPct, st.avgBarsHeld,
        ),
    )
    dumpTrades(labelFa, base.symbol, res)
    println(
        String.format(
            Locale.US,
            "        خروج‌ها: حدضرر=%d · حدسود=%d · هوشمند=%d · زمانی=%d · باز=%d · ارزیابی‌شده=%d کندل · %.0fms",
            st.stopLossExits, st.takeProfitExits, st.smartExits, st.timeStopExits, st.openTrades,
            res.evaluatedSteps, ms.toDouble(),
        ),
    )
}

fun main(args: Array<String>) {
    // قرارداد run.sh: آرگومان اول bars و دوم seed است؛ از سوم به بعد مال ماست.
    val triggerPath = args.getOrNull(2)
    require(!triggerPath.isNullOrBlank()) {
        "مسیر CSV ماشه (۱۵ دقیقه) لازم است: run.sh 0 0 csv <trigger.csv> [1h.csv] [4h.csv] [SYMBOL]"
    }
    val strategyPath = args.getOrNull(3)?.takeIf { it.isNotBlank() && it != "-" }
    val macroPath = args.getOrNull(4)?.takeIf { it.isNotBlank() && it != "-" }
    val symbolArg = args.getOrNull(5)?.takeIf { it.isNotBlank() }

    val trigger = readCsv(triggerPath, "ماشه")
    val strategy = strategyPath?.let { readCsv(it, "استراتژی") }
    val macro = macroPath?.let { readCsv(it, "ماکرو") }

    val symbol = symbolArg ?: trigger.suggestedSymbol.ifBlank { "XAU/USD" }
    println(
        String.format(
            Locale.US,
            "نماد=%s · دسته=%s · میانهٔ دامنهٔ کندل ماشه=%.1f بیسیس‌پوینت · ارز قیمت=%.2f",
            symbol, AssetClass.of(symbol), medianRangeBp(trigger.candles),
            trigger.candles.lastOrNull()?.close ?: 0.0,
        ),
    )

    // ۱) پایه: تایم‌فریم‌های بالا از روی همان سری ماشه بازسازی می‌شوند (رفتار فعلی اپ).
    runOne(
        "بازسازی-HT F",
        trigger.candles,
        BacktestReplayEngine.Config(
            symbol = symbol,
            macroInterval = Interval.H4,
            strategyInterval = Interval.H1,
            triggerInterval = Interval.M15,
            includeHiddenCrossLayer = true,
            captureEntryReports = false,
        ),
    )

    // ۳) تایم‌فریم‌های بالا از فایل واقعی خودشان (اگر کاربر داده باشد).
    //    این تنها حالتی است که «چند تایم‌فریمی» واقعاً چند تایم‌فریمی است.
    if (strategy != null && macro != null) {
        runOne(
            "HTF-واقعی",
            trigger.candles,
            BacktestReplayEngine.Config(
                symbol = symbol,
                includeHiddenCrossLayer = true,
                strategyCandlesOverride = strategy.candles,
                macroCandlesOverride = macro.candles,
            ),
        )    } else {
        println("[HTF-واقعی] اجرا نشد — فایل ۱ ساعته و ۴ ساعته داده نشد.")
    }
}
