package com.aurum.edge.core

import android.content.Context
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
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
class BacktestNeuralTrainer(
    context: Context? = null,
    /**
     * مسیر صریح فایل حافظه — فقط برای آزمون.
     *
     * بدون این پارامتر، آزمون JUnit برای دست‌زدن به فایل به `Context` اندروید نیاز داشت که
     * در تست واحد وجود ندارد؛ نتیجه این بود که منطق «حافظه انباشته» هیچ‌وقت آزموده نمی‌شد.
     */
    storageFileOverride: File? = null,
) {

    private val storageFile: File? = storageFileOverride
        ?: context?.let { File(it.filesDir, "backtest/backtest_neural_v1.json") }

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
        /**
         * نمونه‌هایی که از **بک‌تست‌های قبلی** در حافظه بودند. صفر = این اولین آموزش است.
         *
         * قبلاً همیشه صفر بود، چون فایل حافظه پیش از هر آموزش پاک می‌شد؛ یعنی هر اجرا از
         * صفر شروع می‌شد و «یادگیری» در عمل فقط «اندازه‌گیری همان اجرا» بود.
         */
        val carriedSamples: Int = 0,
        /** کل نمونه‌های آموخته‌شده تا امروز (قبلی + همین اجرا). */
        val totalLearnedSamples: Int = 0,
        /** آیا حافظه با موفقیت ذخیره شد (اگر نه، اجرای بعدی دوباره از صفر شروع می‌شود). */
        val persisted: Boolean = false,
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
        /**
         * آیا پول (دلار) در یادگیری اثر بگذارد؟
         *
         * پیش‌فرض **نه** — و این عمدی است: دانش شبکه باید به «کیفیت ستاپ» وابسته باشد، نه
         * به سرمایهٔ شروع یا درصد ریسکی که کاربر همان روز انتخاب کرده. با `false`، معیار
         * آموزش از فاصلهٔ قیمتی (R) ساخته می‌شود و عوض‌کردن سرمایه، دانش قبلی را بی‌اعتبار
         * نمی‌کند. `true` فقط برای آزمون A/B نگه داشته شده است.
         */
        moneyAffectsLearning: Boolean = false,
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
        // ── حافظهٔ انباشته ────────────────────────────────────────────────────
        // قبلاً اینجا `storageFile.delete()` اجرا می‌شد: حافظه پیش از هر آموزش پاک می‌شد.
        // نتیجه: هیچ‌وقت بیش از نمونه‌های «همین یک اجرا» چیزی یاد گرفته نمی‌شد و بک‌تست
        // قدیمی هیچ کمکی به آموزش نمی‌کرد. حالا موتور، وضعیت ذخیره‌شده را می‌خواند و
        // نمونه‌های تازه روی همان ساخته می‌شوند.
        //
        // چرا اکنون بی‌خطر است؟ شناسهٔ هر نمونه از خودِ معامله ساخته می‌شود (نماد، استراتژی،
        // زمان ورود). پس اجرای دوبارهٔ همان CSV چیزی به دانش اضافه نمی‌کند و مدل را متورم
        // نمی‌کند — همان قاعدهٔ «تکراری نمی‌تواند بنشیند» که برای اعمال سود هم گذاشتیم.
        storageFile?.parentFile?.mkdirs()
        val engine = NeuralSelfLearningEngine(context = null, storageFile = storageFile)
        val carried = runCatching { engine.totalLearnedTradesCount() }.getOrDefault(0)

        var fed = 0
        closed.forEachIndexed { index, trade ->
            val report = reports[trade.entryTime]
            val pseudo = trade.toPseudoPaperTrade(symbol, report, index)
            val features = extract(engine, pseudo, report)
            // ── پول، معیار یادگیری نیست ───────────────────────────────────────
            // شبکه روی «برد/باخت» و «R» آموزش می‌بیند، و این دو با سود دلاری فرق دارند:
            // با سرمایهٔ شروع بزرگ یا ریسک درصدی بالا، همان setups برنده می‌تواند در CSV
            // دیگری دلار منفی بدهد. اگر آموزش به پول دلاری گره بخورد، هر بار که کاربر
            // سرمایهٔ شروع یا ریسک را عوض کند، دانش قبلی «غلط» می‌شود.
            //
            // پس برای آموزش، R و برد/باخت از **فاصلهٔ قیمتی** بازسازی می‌شوند (حد ضرر
            // اولیه، همان کاری که `toPseudoPaperTrade` می‌کند) و اعداد دلاری جای دیگری
            // (آرشیو، آمار بک‌تست) می‌مانند. پارامتر `moneyAffectsLearning` این گزینه را
            // صریح نگه می‌دارد تا اگر روزی کسی بخواهد آموزش وابسته به پول ببیند،
            // بداند که دارد یک فرض را عوض می‌کند.
            val learnPseudo = if (moneyAffectsLearning) pseudo else pseudo.asRiskNormalized()
            engine.recordCompletedTrade(
                trade = learnPseudo,
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
        // حافظهٔ انباشته: کاربر باید ببیند بک‌تست‌های قبلی واقعاً کمک کرده‌اند یا نه.
        if (carried > 0) {
            notes += "🧠 حافظهٔ اتاق تمرین: $carried نمونه از بک‌تست‌های قبلی در حافظه بود و " +
                "$fed نمونهٔ این اجرا روی همان‌ها ساخته شد (جمع: ${carried + fed})."
        } else {
            notes += "🧠 این اولین آموزش است (حافظه خالی بود). اجرای بعدی روی همین دانش ساخته می‌شود."
        }
        if (storageFile?.isFile != true) {
            notes += "⚠️ حافظه ذخیره نشد؛ اجرای بعدی دوباره از صفر شروع می‌شود. فضای ذخیره‌سازی برنامه را بررسی کنید."
        }
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
            carriedSamples = carried,
            totalLearnedSamples = runCatching { engine.totalLearnedTradesCount() }.getOrDefault(carried + fed),
            persisted = storageFile?.isFile == true,
        )
    }

    /**
     * پاک‌کردن حافظهٔ اتاق تمرین — با تصمیم صریح کاربر.
     *
     * چرا لازم است؟ حالا که نمونه‌ها انباشته می‌شوند، یک دورهٔ دادهٔ بد (مثلاً CSV نویزی یا
     * بازار رِنج) برای همیشه در حافظه می‌ماند. بدون این دکمه، راه برگشتی وجود نداشت.
     * فقط فایل تمرین پاک می‌شود؛ حافظهٔ شبکهٔ اصلی برنامه (`neural_self_learning_v1.json`)
     * و ژورنال واقعی هیچ‌وقت لمس نمی‌شوند.
     */
    fun resetMemory(): Boolean {
        val f = storageFile ?: return false
        val existed = f.exists()
        if (existed) f.delete()
        return !f.exists()
    }

    /**
     * همان معامله، اما معیار یادگیری‌اش از قیمت می‌آید نه از دلار.
     *
     * `pnlUsd` تنها جایی است که پول وارد آموزش می‌شود (برد/باخت و R از آن ساخته می‌شوند).
     * با جایگزین‌کردنش با سود/زیان معادلِ R-محور — دقیقاً یک واحد ریسک برای برد و یک واحد
     * برای باخت، به‌علاوهٔ همان نسبت R که در بازار واقعاً اتفاق افتاده — ساختار آموزش
     * تغییر نمی‌کند ولی وابستگی به سرمایه از بین می‌رود.
     *
     * نکتهٔ مهم: اگر R صفر باشد (معامله در نقطهٔ سربه‌سر بسته شده) همان صفر می‌ماند و
     * به‌عنوان باخت شمرده نمی‌شود؛ این عمدی است تا سربه‌سرها آمار را خراب نکنند.
     */
    private fun PaperTrade.asRiskNormalized(): PaperTrade {
        val oneR = abs(entry - (initialStopLoss ?: stopLoss)).coerceAtLeast(1e-9)
        val r = (pnlUsd ?: 0.0) / oneR
        return copy(pnlUsd = r)
    }

    /**
     * درگاه گیت عصبی برای بک‌تست — همان چیزی که کلید «مانع» باید کنترل کند.
     *
     * تا امروز `BacktestReplayEngine` هیچ‌گاه گیت را نمی‌پرسید، پس روشن‌کردن «مانع»
     * نتیجهٔ بک‌تست را مطلقاً عوض نمی‌کرد (همان مشاهدهٔ «با مانع و بدون مانع یکی بود»).
     *
     * @param respectUserActivation اگر `true` (رفتار برنامه)، تا وقتی خود کاربر کلید
     *   «فعال‌سازی گیت» را روشن نکرده باشد هیچ ورودی مسدود نمی‌شود — دقیقاً مثل مسیر زنده.
     *   اگر `false`، فقط برای آزمون A/B، گیت بی‌توجه به کلید اعمال می‌شود.
     */
    fun entryGate(respectUserActivation: Boolean = true): NeuralGatePolicy.EntryGate {
        val file = storageFile ?: return NeuralGatePolicy.EntryGate.ALLOW_ALL
        if (!file.exists()) return NeuralGatePolicy.EntryGate.ALLOW_ALL
        return NeuralGatePolicy.EntryGate { request ->
            runCatching {
                // موتور از همان فایل حافظهٔ انباشته ساخته می‌شود: دانش بک‌تست‌های قبلی
                // همان دانشی است که همین حالا جلوی معاملهٔ نامزد را می‌گیرد.
                val engine = NeuralSelfLearningEngine(context = null, storageFile = file)
                val summary = engine.state.value
                if (respectUserActivation && !summary.gateUserActivationEnabled) {
                    return@runCatching NeuralGatePolicy.GateVerdict(
                        blocks = false,
                        reasonFa = "کلید «فعال‌سازی گیت» در بخش هوش عصبی روشن نیست.",
                        summaryFa = "⚪ مانع خاموش است",
                    )
                }
                val action = if (request.isLong) SignalAction.BUY else SignalAction.SELL
                val (features, key, _) = engine.extractConditionFeatures(
                    symbolInput = request.symbol,
                    action = action,
                    interval = request.triggerInterval,
                    strategyId = request.strategyId,
                    optimalRr = request.plannedRiskReward,
                    confidence = request.engineConfidencePct,
                    report = request.report,
                )
                val evaluation = engine.evaluateFeatures(
                    symbol = request.symbol,
                    features = features.toDoubleArray(),
                    conditionKey = key,
                )
                NeuralGatePolicy.GateVerdict(
                    blocks = evaluation.isBlockingTradeNow,
                    reasonFa = evaluation.noTradeWarningFa ?: evaluation.summaryFa,
                    summaryFa = evaluation.summaryFa,
                )
            }.getOrElse {
                // خطای گیت هرگز نباید بک‌تست را بشکند یا بی‌صدا معامله را حذف کند.
                NeuralGatePolicy.GateVerdict(
                    blocks = false,
                    reasonFa = "گیت عصبی خطا داد: ${it.message ?: it::class.simpleName}",
                    summaryFa = "⚠️ گیت خطا داد و مانع نشد",
                )
            }
        }
    }

    /** چند نمونه در حافظهٔ اتاق تمرین هست (بدون تغییر چیزی). */
    fun memorySize(): Int {
        val f = storageFile ?: return 0
        if (!f.exists()) return 0
        val engine = NeuralSelfLearningEngine(context = null, storageFile = f)
        return runCatching { engine.totalLearnedTradesCount() }.getOrDefault(0)
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
        // ── پول وارد آموزش نمی‌شود ───────────────────────────────────────────
        // قبلاً این‌جا `pnlUsd = netPnlUsd` بود و `fixedRiskUsd` فاصلهٔ قیمتی؛ نتیجه این بود
        // که Rِ داده‌شده به شبکه = «دلار / فاصلهٔ قیمتی» می‌شد، در حالی که دلار خودش با
        // سرمایه و ریسک درصدی مقیاس می‌خورد. پس همان ستاپ با سرمایهٔ ۵٬۰۰۰ و ۵۰٬۰۰۰ دلار
        // دو R کاملاً متفاوت (و بعد از کلیپ [-1.5..4.5] دو برچسب متفاوت) می‌گرفت و شبکه
        // چیز دیگری یاد می‌گرفت. آزمون این را نشان می‌داد ولی به‌جای رفع علت، تلورانس آزمون
        // یک‌بار شل شده بود (۰٫۱۱). حالا معیار آموزش **خودِ R موتور** است (خالص بعد از کارمزد)
        // و چون `fixedRiskUsd = 1.0` است، R نه به سرمایه و نه به ریسک درصدی وابسته نیست.
        pnlUsd = rMultiple,
        positionOz = 1.0,
        initialStopLoss = stopLoss,
        strategyId = strategyId,
        strategyTitleFa = strategyTitleFa,
        fixedRiskUsd = 1.0,
        // `rMultiple` هم‌اکنون همان R خالص است؛ نگه‌داشتنش باعث می‌شود مسیر عادی
        // (`trade.rMultiple ?: pnl / fixedRiskUsd`) در موتور یادگیری دقیقاً همین عدد را ببیند.
        rMultiple = rMultiple,
        optimalRiskReward = plannedRiskReward.coerceIn(1.5, 3.0),
    )
}
