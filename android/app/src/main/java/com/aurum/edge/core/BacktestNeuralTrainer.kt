package com.aurum.edge.core

import android.content.Context
import com.aurum.edge.domain.Interval
import java.io.File
import kotlin.math.abs
import kotlin.math.round

/**
 * پل «بک‌تست → هوش عصبی»، در یک **اتاق تمرین جدا**.
 *
 * ## چرا جدا؟
 * شبکهٔ عصبی برنامه یک فایل حالت دارد (`neural_self_learning_v1.json`) که وزن‌ها، دفتر
 * یادگیری، خوشه‌های شرط و پارامترهای تیون‌شدهٔ معاملات **واقعی** در آن است. اگر بک‌تست
 * مستقیم در همان فایل بنویسد، سه چیز خراب می‌شود:
 *  ۱. شمارندهٔ «تعداد معاملهٔ آموخته‌شده» با ژورنال واقعی ناهم‌خوان می‌شود؛
 *  ۲. گیت دوشرطی (۱۰٬۰۰۰ نمونه) با دادهٔ تاریخی الکی باز می‌شود؛
 *  ۳. پارامترهای تیون‌شده بر پایهٔ معاملات واقعی، بی‌سروصدا عوض می‌شوند.
 *
 * پس تمرین در یک نمونهٔ جدا (`NeuralSelfLearningEngine` با فایل مستقل
 * `backtest/backtest_neural_v1.json`) انجام می‌شود و خروجی‌اش فقط **متن خوانا** است:
 * نرخ برد پیش‌بینی‌شده، وزن یادگرفته‌شدهٔ ستون‌های ایچیموکو و پارامترهای پیشنهادی.
 * هیچ‌کدام از این اعداد خودکار روی موتور زنده اعمال نمی‌شوند؛ تصمیمش با کاربر است.
 */
class BacktestNeuralTrainer(context: Context? = null) {

    private val storageFile: File? = context?.let { File(it.filesDir, "backtest/backtest_neural_v1.json") }

    /** خلاصهٔ آنچه شبکه در این اجرا یاد گرفت. */
    data class TrainingReport(
        val samplesFed: Int,
        val wins: Int,
        val losses: Int,
        val avgR: Double,
        /** میانگین احتمال بردِ پیش‌بینی‌شده برای نمونه‌های همین اجرا (۰..۱۰۰). */
        val predictedWinRatePct: Double,
        /** دقت طبقه‌بندی روی نمونه‌هایی که آموزش دیده (نه آزمون کور). */
        val inSampleAccuracyPct: Double,
        val columns: List<ColumnWeight>,
        val parameters: List<ParameterSuggestion>,
        val notesFa: List<String>,
    )

    data class ColumnWeight(val titleFa: String, val weight: Double, val directionFa: String)

    data class ParameterSuggestion(
        val titleFa: String,
        val currentValue: Double,
        val suggestedValue: Double,
        val samplesAnalyzed: Int,
        val basisFa: String,
    )

    /**
     * تغذیهٔ شبکه با معاملات بسته‌شدهٔ بک‌تست.
     *
     * @param trades فقط معامله‌های بسته‌شده استفاده می‌شوند؛ معاملهٔ باز در یادگیری معنایی ندارد.
     * @param reports گزارش استراتژی همان لحظهٔ ورود (کلید = زمان ورود) تا ویژگی‌ها از خود کندل
     *        ساخته شوند، نه از حافظهٔ خزانه که در بک‌تست خالی است.
     */
    fun train(
        trades: List<BacktestReplayEngine.Trade>,
        symbol: String,
        reports: Map<Long, StrategyEvaluationReport> = emptyMap(),
        baseConfig: BaseStrategyConfig = BaseStrategyConfig(),
    ): TrainingReport {
        val closed = trades.filter { !it.isOpen }
        if (closed.isEmpty()) {
            return TrainingReport(
                samplesFed = 0, wins = 0, losses = 0, avgR = 0.0,
                predictedWinRatePct = 0.0, inSampleAccuracyPct = 0.0,
                columns = emptyList(), parameters = emptyList(),
                notesFa = listOf("هیچ معاملهٔ بسته‌شده‌ای در این بک‌تست نبود؛ چیزی برای یادگیری وجود ندارد."),
            )
        }
        // هر اجرا از صفر تمرین می‌شود تا نتیجهٔ یک بازه با بازهٔ دیگر قاطی نشود.
        storageFile?.parentFile?.mkdirs()
        storageFile?.delete()
        val engine = NeuralSelfLearningEngine(context = null, storageFile = storageFile)

        var fed = 0
        closed.forEachIndexed { index, trade ->
            val report = reports[trade.entryTime]
            val pseudo = trade.toPseudoPaperTrade(symbol, report, index)
            val features = extract(engine, pseudo, report)
            engine.recordCompletedTrade(
                trade = pseudo,
                report = report,
                featuresOverride = features?.first?.toDoubleArray(),
                conditionKeyOverride = features?.second,
            )
            fed++
        }

        val wins = closed.count { it.netPnlUsd > 0.0 }
        val avgR = closed.map { it.rMultiple }.average()
        val tuned = runCatching { engine.applyAutoTunedParametersToConfig(baseConfig) }.getOrDefault(baseConfig)

        // دقت درون‌نمونه‌ای و میانگین احتمال برد روی همان نمونه‌هایی که آموزش دیده‌اند.
        var correct = 0
        var counted = 0
        var probSum = 0.0
        closed.forEachIndexed { index, trade ->
            val report = reports[trade.entryTime]
            val pseudo = trade.toPseudoPaperTrade(symbol, report, index)
            val features = extract(engine, pseudo, report) ?: return@forEachIndexed
            val prob = runCatching { engine.predictWinProbability(features.first, trade.strategyId, symbol) }
                .getOrDefault(0.5)
            counted++
            probSum += prob
            if ((prob >= 0.5) == (trade.netPnlUsd > 0.0)) correct++
        }

        val columns = listOf(
            Triple("کیجون/چندتایم‌فریمی (پاتل)", 6, "هم‌گرایی سه تایم‌فریم و تعادل کیجون"),
            Triple("چیکو/سنکو B (تاتل)", 7, "آزادی چیکو و شکست فلت سنکو B"),
            Triple("امواج و زمان (هوسودا)", 8, "هندسهٔ V/N/E و چرخهٔ زمانی"),
            Triple("پلاتوی چندتایم‌فریمی (پلوییل)", 9, "دیوارهای حمایت و مقاومت"),
        ).map { (title, idx, hint) ->
            val w = runCatching { engine.featureHeadWeight(idx) }.getOrDefault(0.0)
            ColumnWeight(
                titleFa = "$title — $hint",
                weight = round(w * 1000.0) / 1000.0,
                directionFa = when {
                    w > 0.08 -> "↑ در بک‌تست با برد همراه بود"
                    w < -0.08 -> "↓ در بک‌تست بیشتر با باخت همراه بود"
                    else -> "≈ در این بازه بی‌اثر"
                },
            )
        }

        val notes = ArrayList<String>()
        notes += "این تمرین در فایل جدا (${storageFile?.name ?: "حافظهٔ موقت"}) انجام شد؛ دفتر یادگیری، " +
            "شمارندهٔ نمونه و پارامترهای معاملات واقعی برنامه دست‌نخورده ماندند."
        if (closed.size < 10) {
            notes += "نمونه کم است (${closed.size} معامله). این اعداد را جدی نگیرید؛ بازهٔ بزرگ‌تری CSV بگیرید."
        }
        val openCount = trades.count { it.isOpen }
        if (openCount > 0) notes += "$openCount معاملهٔ باز در انتهای بازه با قیمت آخر بسته شد و در آموزش آمد."
        if (closed.size != fed) notes += "از ${closed.size} معامله، $fed نمونه پذیرفته شد."

        return TrainingReport(
            samplesFed = fed,
            wins = wins,
            losses = closed.size - wins,
            avgR = round(avgR * 100.0) / 100.0,
            predictedWinRatePct = if (counted == 0) 0.0 else round(probSum * 1000.0 / counted) / 10.0,
            inSampleAccuracyPct = if (counted == 0) 0.0 else round(correct * 1000.0 / counted) / 10.0,
            columns = columns,
            parameters = listOf(
                ParameterSuggestion(
                    titleFa = "حداقل R:R",
                    currentValue = baseConfig.minRiskReward,
                    suggestedValue = tuned.minRiskReward,
                    samplesAnalyzed = fed,
                    basisFa = "توزیع R معامله‌های همین بک‌تست",
                ),
                ParameterSuggestion(
                    titleFa = "بافر ATR حد ضرر",
                    currentValue = baseConfig.slBufferAtr,
                    suggestedValue = tuned.slBufferAtr,
                    samplesAnalyzed = fed,
                    basisFa = "حد ضررهایی که در همین بازه با شدو شکار شدند",
                ),
                ParameterSuggestion(
                    titleFa = "سقف کشیدگی کیجون",
                    currentValue = baseConfig.maxKijunExtensionAtr,
                    suggestedValue = tuned.maxKijunExtensionAtr,
                    samplesAnalyzed = fed,
                    basisFa = "معامله‌هایی که در کشیدگی C-Clamp باز شدند",
                ),
                ParameterSuggestion(
                    titleFa = "حداقل کارایی روند (ER)",
                    currentValue = baseConfig.minEfficiencyRatio,
                    suggestedValue = tuned.minEfficiencyRatio,
                    samplesAnalyzed = fed,
                    basisFa = "تفکیک روند خالص از بازار رِنج در همین بازه",
                ),
            ),
            notesFa = notes,
        )
    }

    private fun extract(
        engine: NeuralSelfLearningEngine,
        trade: PaperTrade,
        report: StrategyEvaluationReport?,
    ): Pair<List<Double>, String>? = runCatching {
        val (features, key, _) = engine.extractConditionFeatures(
            symbolInput = trade.symbol,
            action = trade.action,
            interval = trade.interval,
            strategyId = trade.strategyId,
            optimalRr = trade.effectiveOptimalRr,
            confidence = trade.confidence,
            report = report,
        )
        features to key
    }.getOrNull()

    private fun BacktestReplayEngine.Trade.toPseudoPaperTrade(
        symbol: String,
        report: StrategyEvaluationReport?,
        index: Int,
    ): PaperTrade = PaperTrade(
        id = "BT-$strategyId-$entryTime-$index",
        symbol = report?.symbol?.takeIf { it.isNotBlank() } ?: symbol,
        interval = report?.triggerInterval ?: Interval.M15,
        action = action,
        entry = entryPrice,
        stopLoss = stopLoss,
        takeProfit = takeProfit,
        confidence = confidence,
        riskReward = plannedRiskReward,
        openedAt = entryTime,
        closedAt = exitTime,
        exitPrice = exitPrice,
        exitReason = exitReason.name,
        pnlUsd = netPnlUsd,
        positionOz = 1.0,
        initialStopLoss = stopLoss,
        strategyId = strategyId,
        strategyTitleFa = strategyTitleFa,
        // ریسک دلاری از فاصلهٔ قیمتی ساخته می‌شود تا R درست محاسبه شود، نه از یک عدد ثابت.
        fixedRiskUsd = abs(entryPrice - stopLoss).takeIf { it > 0.0 } ?: 1.0,
        optimalRiskReward = plannedRiskReward.coerceIn(1.5, 3.0),
    )
}
