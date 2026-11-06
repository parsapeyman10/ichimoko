package com.aurum.edge.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * # موتور معامله‌گر خودکار (AI Trader Engine)
 *
 * پیش از این، سه بخش از هم جدا بودند و هیچ‌کدام «تصمیم» نمی‌گرفتند:
 *
 * ۱. **دستیار ترید** ([MasterIchimokuTradingAssistant]) فقط توضیح می‌داد و کارت آموزشی می‌ساخت.
 * ۲. **نکات قابل اصلاح** (`IchimokuImprovementNote`) ساخته می‌شدند، پین می‌شدند و همان‌جا می‌ماندند.
 * ۳. **شبکهٔ عصبی** پارامترها را تیون می‌کرد ولی گلوگاه‌های واقعیِ شرط‌ها را نمی‌دید.
 *
 * این فایل آن شکاف را می‌بندد و همان چیزی را می‌سازد که کاربر خواست: **معامله‌گر، نه دستیار.**
 * سه وظیفه دارد:
 *
 * | وظیفه | ورودی | خروجی | اثر |
 * |---|---|---|---|
 * | **۱. خبری (Pre-emptive News)** | همهٔ گزارش‌های نمادها | شرایط نوظهور هر نماد **پیش از** رسیدن به سیگنال | پیش‌دستی، نه واکنش |
 * | **۲. رفع خودکار گلوگاه** | شرط‌های ردشده در معاملات باخته | [SelfHealingAction] با نمونه و اندازهٔ اصلاح | نکته به تغییر پارامتر تبدیل می‌شود |
 * | **۳. ماتریس برتری** | ماتریس ۱۹×N استراتژی/نماد | سهم بودجه و وزن هر استراتژی | بودجه به جایی می‌رود که لبه دارد |
 *
 * ## سه اصل غیرقابل‌مذاکره
 *
 * ۱. **هیچ عددی بدون نمونه ساخته نمی‌شود.** هر اقدام خودترمیم با `samples` و `noteFa` می‌آید؛
 *    زیر آستانهٔ نمونه، اقدام صادر نمی‌شود و دلیلش صریح گفته می‌شود.
 * ۲. **اصلاح‌ها فقط داخل دالان امن موتور حرکت می‌کنند.** R:R در `[1.50..2.20]`، بافر حد ضرر
 *    در `[0.15..0.55]`، کشیدگی کیجون در `[1.25..2.50]`؛ هیچ اصلاحی نمی‌تواند از این‌ها بیرون بزند.
 * ۳. **همه‌چیز قابل بازگشت است.** هر اقدام یک `factor` و `previousValue` دارد و با
 *    [revertActions] می‌شود به وضعیت قبل برگشت.
 */
object AiTraderEngine {

    /** آستانه‌های ایمنی؛ همان بازه‌هایی که موتور ارزیابی هم استفاده می‌کند. */
    const val MIN_RR: Double = 1.50
    const val MAX_RR: Double = 2.20
    const val MIN_SL_BUFFER: Double = 0.15
    const val MAX_SL_BUFFER: Double = 0.55
    const val MIN_KIJUN_EXT: Double = 1.25
    const val MAX_KIJUN_EXT: Double = 2.50
    const val MIN_EFFICIENCY: Double = 0.14
    const val MAX_EFFICIENCY: Double = 0.45

    /** حداقل نمونه برای اینکه یک «گلوگاه» واقعی شمرده شود، نه نویز. */
    const val MIN_SAMPLES_FOR_ACTION: Int = 4

    /** حداکثر نسبت اصلاح برای هر بار (۲۰٪) تا سیستم پرش نکند. */
    const val MAX_STEP_FACTOR: Double = 0.20

    /** آستانهٔ هشدار شوک خبری (قدرت مطلق شوک) برای اضافه‌شدن هشدار به پیش‌بینی نماد. */
    const val NEWS_SHOCK_WARN: Double = 0.35

    /** آستانهٔ احساس خبری نماد که «جهت‌دهنده» شمرده می‌شود. */
    const val NEWS_SENTIMENT_WARN: Double = 0.25

    /** اگر یک شرط در بیش از این سهم از معاملات باخته رد شده باشد، «گلوگاه سیستماتیک» است. */
    const val BOTTLENECK_SHARE: Double = 0.55

    // ─────────────────────────── مدل‌های خبری ───────────────────────────

    /**
     * یک «شرایط نوظهور» برای یک نماد: چه چیزی در حال ساخته‌شدن است، **قبل از** اینکه سیگنال شود.
     *
     * @param emergingPassed شرط‌هایی که همین حالا پاس‌اند.
     * @param blockers شرط‌های باقی‌مانده به‌ترتیب سختی.
     * @param hoursToTrigger تخمین فاصلهٔ زمانی تا فعال‌شدن سیگنال (بر پایهٔ سرعت بسته‌شدن شرط‌ها).
     */
    data class SymbolConditionsForecast(
        val symbol: String,
        /** استراتژی‌ای که بیشترین شانس رسیدن به سیگنال را دارد. */
        val bestStrategyId: String,
        val bestStrategyTitleFa: String,
        val emergingPassed: Int,
        val totalConditions: Int,
        val readinessFa: String,
        /** ۰..۱۰۰ — چقدر نزدیک به سیگنال کامل است. */
        val proximityPercent: Double,
        val blockersFa: List<String>,
        /** «الان چه اتفاقی می‌افتد» به زبان معامله‌گر. */
        val emergingConditionsFa: List<String>,
        val forecastEtaFa: String,
        /** اقدام پیشنهادی معامله‌گر برای همین حالا. */
        val preemptiveActionFa: String,
        val isActionable: Boolean,
    )

    /**
     * @param reportsBySymbol همهٔ گزارش‌های تازه، کلید = نماد نرمال‌شده.
     * @param nowMillis زمان مرجع.
     */
    fun forecastEmergingConditions(
        reportsBySymbol: Map<String, List<StrategyEvaluationReport>>,
        maxSymbols: Int = 15,
        nowMillis: Long = System.currentTimeMillis(),
        /** وضعیت خبری/ماکرو اخیر؛ اگر خالی باشد رفتار دقیقاً مثل قبل است. */
        newsSnapshot: NeuralMacroNewsSnapshot? = null,
        /** انتساب علیّ بازار برای هر نماد (خروجی موتور عصبی). */
        attributions: List<NeuralMarketCausalAttribution> = emptyList(),
    ): List<SymbolConditionsForecast> {
        val out = ArrayList<SymbolConditionsForecast>()
        for ((symbol, reports) in reportsBySymbol) {
            val valid = reports.filter { it.conditions.isNotEmpty() && it.triggerSnapshot != null }
            if (valid.isEmpty()) continue
            // استراتژی با بیشترین پیشروی (بیشترین شرط پاس + بالاترین امتیاز روند)
            val best = valid.maxWithOrNull(
                compareBy<StrategyEvaluationReport> { it.conditions.count { c -> c.passed } }
                    .thenBy { it.coreTrendScore }
                    .thenBy { it.riskReward ?: 0.0 },
            ) ?: continue
            val passed = best.conditions.count { it.passed }
            val total = best.conditions.size.coerceAtLeast(1)
            val failed = best.conditions.filter { !it.passed }
            val proximity = round((passed.toDouble() / total.toDouble()) * 100.0 * 10.0) / 10.0

            val blockers = failed.take(3).map { "${it.titleFa} — ${it.detailFa}" }
            val technicalLines = buildList {
                if (best.riskReward != null && best.riskReward >= MIN_RR) {
                    add(
                        String.format(
                            java.util.Locale.US,
                            "پلن آماده است: R:R=1:%.2f با حد ضرر %.2f و حد سود %.2f",
                            best.riskReward,
                            best.suggestedStopLoss ?: 0.0,
                            best.primaryTakeProfit ?: 0.0,
                        ),
                    )
                }
                best.triggerSnapshot?.let { snap ->
                    if (snap.isHiddenCloudBalanced) {
                        add(
                            String.format(
                                java.util.Locale.US,
                                "ابر مخفی باریک است (%.2f ATR) — ناحیهٔ تعادل تنکان/کیجون آمادهٔ واکنش",
                                snap.hiddenCloudThicknessAtr,
                            ),
                        )
                    }
                    if (snap.senkouSpanBFlatBars >= 10) {
                        add("SSB ${snap.senkouSpanBFlatBars} کندل تخت است — آستانهٔ چرخش نزدیک")
                    }
                    if (snap.isBollingerSqueeze) add("فشردگی بولینگر فعال است — احتمال انفجار نوسان")
                    if (snap.nearestCrownLevel > 0.0) {
                        add(
                            String.format(
                                java.util.Locale.US,
                                "دیوار تاج هیدن کراس روی %.2f (%.2f ATR) در مسیر",
                                snap.nearestCrownLevel,
                                snap.nearestCrownDistanceAtr,
                            ),
                        )
                    }
                }
                if (best.triggerType != StrategyTriggerType.NONE) {
                    add("ماشه فعال است: ${best.triggerType.titleFa}")
                }
            }

            // اخبار به‌صورت فهرست جدا ساخته می‌شوند تا اگر هیچ خبری نبود، خروجی تکنیکال
            // دست‌نخورده و بدون هیچ خط اضافه‌ای بماند.
            val newsLines = ArrayList<String>()
            // ── لایهٔ خبری: شرایط نماد **پیش از** معامله باید از خبر هم اثر بگیرد ──
            // تا اینجا پیش‌بینی فقط تکنیکال بود. کاربر خواست «شرایط هر نماد بر اساس هوش خبری
            // پیش از نزدیک شدن معامله ساخته شود». هرچه اینجا اضافه می‌شود متن اندازه‌گیری‌شدهٔ
            // موتور عصبی است، نه حدس: اگر خبری نباشد، هیچ خطی اضافه نمی‌شود.
            newsSnapshot?.let { snap ->
                val attribution = attributions.firstOrNull { V1Universe.normalize(it.symbol) == V1Universe.normalize(symbol) }
                if (snap.isHighImpactNewsWindow) {
                    newsLines += "📰 پنجرهٔ خبری پراثر فعال است — نوسان ناگهانی و گپ ممکن است پلن را بشکند"
                }
                if (abs(snap.shockIntensity) >= NEWS_SHOCK_WARN) {
                    newsLines += String.format(
                        java.util.Locale.US,
                        "🌊 شدت شوک خبری %.2f (آستانهٔ هشدار %.2f) — اندازهٔ پوزیشن باید محافظه‌کارانه باشد",
                        snap.shockIntensity,
                        NEWS_SHOCK_WARN,
                    )
                }
                if (snap.activeMacroCatalystFa.isNotBlank() &&
                    !snap.activeMacroCatalystFa.contains("بدون شوک")
                ) {
                    newsLines += "🗞️ کاتالیز فعال: ${snap.activeMacroCatalystFa}"
                }
                attribution?.let { attr ->
                    if (abs(attr.newsSentimentScore) >= NEWS_SENTIMENT_WARN) {
                        val dirFa = if (attr.newsSentimentScore > 0) "مثبت" else "منفی"
                        newsLines += String.format(
                            java.util.Locale.US,
                            "📊 احساس خبری %s %.2f برای همین نماد — %s",
                            dirFa,
                            abs(attr.newsSentimentScore),
                            if (attr.recommendedTacticalActionFa.isNotBlank()) attr.recommendedTacticalActionFa
                            else "جهت خبر با جهت تکنیکال سنجیده شود",
                        )
                    }
                }
            }
            // خبر بعد از شواهد تکنیکال می‌آید تا ترتیب «چه می‌بینم → چه خبری هست» حفظ شود.
            val emerging = technicalLines + newsLines

            // گام بعدی معامله‌گر بر پایهٔ آمادگی
            val action = when {
                best.readiness == StrategyReadinessState.SIGNAL_BUY || best.readiness == StrategyReadinessState.SIGNAL_SELL ->
                    "🟢 همین حالا قابل ورود است؛ منتظر کندل بستهٔ بعدی نمان — شرط‌ها کامل‌اند"
                passed >= total - 2 ->
                    "🟠 یک قدم تا سیگنال؛ آماده‌سازی سفارش در ${best.suggestedEntry ?: 0.0}"
                passed >= total / 2 ->
                    "🟡 نیمهٔ راه؛ پایش فعال شد. برای پیش‌دستی، سطح ${best.suggestedEntry ?: 0.0} را علامت بزن"
                else -> "⚪ ساختار هنوز دور است؛ فقط رصد"
            }
            // تخمین ETA: هر شرط باقی‌مانده تقریباً ۲ کندل از تایم‌فریم ماشه
            val barsNeeded = failed.size * 2
            val etaMinutes = barsNeeded * best.triggerInterval.millis / 60_000L
            val eta = when {
                failed.isEmpty() -> "همین حالا"
                etaMinutes <= 0L -> "نامشخص"
                etaMinutes < 60L -> "حدود $etaMinutes دقیقه"
                etaMinutes < 1_440L -> "حدود ${etaMinutes / 60} ساعت"
                else -> "بیش از یک روز"
            }

            out += SymbolConditionsForecast(
                symbol = symbol,
                bestStrategyId = best.strategyId,
                bestStrategyTitleFa = best.strategyTitleFa,
                emergingPassed = passed,
                totalConditions = total,
                readinessFa = best.readiness.labelFa,
                proximityPercent = proximity,
                blockersFa = blockers,
                emergingConditionsFa = emerging,
                forecastEtaFa = eta,
                preemptiveActionFa = action,
                isActionable = passed >= total - 2 || best.readiness == StrategyReadinessState.SIGNAL_BUY ||
                    best.readiness == StrategyReadinessState.SIGNAL_SELL,
            )
        }
        return out.sortedWith(
            compareByDescending<SymbolConditionsForecast> { it.isActionable }
                .thenByDescending { it.proximityPercent },
        ).take(maxSymbols)
    }

    // ─────────────────────────── خودترمیمی ───────────────────────────

    /** دسته‌بندی اقدام خودترمیم. */
    enum class HealingKind(val labelFa: String) {
        R_R_CORRIDOR("دالان R:R"),
        STOP_BUFFER("بافر ضد استاپ‌هانت"),
        KIJUN_STRETCH("سقف کشیدگی کیجون"),
        EFFICIENCY_FILTER("فیلتر کارایی روند"),
        VOLUME_FILTER("فیلتر حجم"),
        STRATEGY_WEIGHT("وزن استراتژی"),
    }

    /**
     * یک تغییر مشخص و قابل بازگشت در پارامترها.
     *
     * @param conditionIndex شاخص شرطی که این تغییر را ایجاد کرد (۱..۱۶)، ۰ = سیستمی.
     * @param previousValue مقدار فعلی؛ برای بازگشت.
     * @param newValue مقدار پیشنهادی (همیشه داخل دالان امن).
     */
    data class SelfHealingAction(
        val id: String,
        val kind: HealingKind,
        val strategyId: String,
        val targetFa: String,
        val conditionIndex: Int,
        val conditionTitleFa: String,
        val problemFa: String,
        val reasonFa: String,
        val samples: Int,
        val previousValue: Double,
        val newValue: Double,
        val createdAtMs: Long,
    ) {
        val delta: Double get() = newValue - previousValue
        val isNoOp: Boolean get() = abs(delta) < 1e-9
        val summaryFa: String
            get() = String.format(
                java.util.Locale.US,
                "%s: %.2f → %.2f (Δ%+.2f) · %d نمونه",
                kind.labelFa,
                previousValue,
                newValue,
                delta,
                samples,
            )
    }

    /**
     * نتیجهٔ تحلیل گلوگاه‌ها و اقدام‌های خودترمیم.
     *
     * @param blockedReasons توزیع دلیل‌های بستن معامله (کلید فارسی → تعداد).
     * @param notes نکات قابل اصلاح تولیدشده توسط دستیار.
     */
    fun healSelf(
        strategyId: String,
        strategyTitleFa: String,
        config: BaseStrategyConfig,
        blockedReasons: Map<String, Int>,
        notes: List<IchimokuImprovementNote>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SelfHealingAction> {
        val actions = ArrayList<SelfHealingAction>()
        val totalBlocked = blockedReasons.values.sum()

        /** گلوگاه شمارهٔ N چقدر تکرار شده؟ (بر پایهٔ متن نکته یا دلیل) */
        fun samplesFor(patterns: List<String>): Int = blockedReasons.entries.sumOf { (reason, count) ->
            if (patterns.any { reason.contains(it, ignoreCase = true) }) count else 0
        }

        // ── ۱. حد ضرر: اگر باخت‌هایی از «شدو/استاپ‌هانت» آمده، بافر را کمی باز کن ──
        val wickSamples = samplesFor(listOf("شدو", "استاپ‌هانت", "wick", "سایه"))
        if (wickSamples >= MIN_SAMPLES_FOR_ACTION) {
            val share = wickSamples.toDouble() / max(totalBlocked, 1).toDouble()
            if (share >= BOTTLENECK_SHARE) {
                val current = config.slBufferAtr
                val proposed = (current * (1.0 + MAX_STEP_FACTOR)).coerceIn(MIN_SL_BUFFER, MAX_SL_BUFFER)
                if (abs(proposed - current) > 1e-6) {
                    actions += SelfHealingAction(
                        id = "heal_sl_buffer",
                        kind = HealingKind.STOP_BUFFER,
                        strategyId = strategyId,
                        targetFa = strategyTitleFa,
                        conditionIndex = 14,
                        conditionTitleFa = "فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس",
                        problemFa = "$wickSamples از ${max(totalBlocked, 1)} باخت با شدوی مخالف بسته شده است",
                        reasonFa = "بافر حد ضرر ۲۰٪ بازتر می‌شود تا استاپ‌هانت شدو، معاملهٔ درست را نبندد " +
                            "(سقف ایمنی ${MAX_SL_BUFFER} ATR).",
                        samples = wickSamples,
                        previousValue = current,
                        newValue = round(proposed * 100.0) / 100.0,
                        createdAtMs = nowMillis,
                    )
                }
            }
        }

        // ── ۲. کشیدگی کیجون: ورودهای دیرِ کشیده ──
        val stretchSamples = samplesFor(listOf("کشیدگی", "کیجون", "C-Clamp", "دیر"))
        if (stretchSamples >= MIN_SAMPLES_FOR_ACTION) {
            val current = config.maxKijunExtensionAtr
            val proposed = (current * (1.0 - MAX_STEP_FACTOR)).coerceIn(MIN_KIJUN_EXT, MAX_KIJUN_EXT)
            if (abs(proposed - current) > 1e-6) {
                actions += SelfHealingAction(
                    id = "heal_kijun_stretch",
                    kind = HealingKind.KIJUN_STRETCH,
                    strategyId = strategyId,
                    targetFa = strategyTitleFa,
                    conditionIndex = 8,
                    conditionTitleFa = "قانون کش لاستیکی کیجون و نبود C-Clamp",
                    problemFa = "$stretchSamples باخت با ورود کشیده از کیجون شناسایی شد",
                    reasonFa = "سقف فاصلهٔ مجاز از کیجون ۲۰٪ سخت‌گیرانه‌تر می‌شود تا ورود دیر و بازگشت به میانگین حذف شود.",
                    samples = stretchSamples,
                    previousValue = current,
                    newValue = round(proposed * 100.0) / 100.0,
                    createdAtMs = nowMillis,
                )
            }
        }

        // ── ۳. فیلتر کارایی: اگر باخت‌ها در رِنج بوده‌اند، کف ER را بالا ببر ──
        val chopSamples = samplesFor(listOf("رِنج", "چاپ", "افقی", "کارایی"))
        if (chopSamples >= MIN_SAMPLES_FOR_ACTION &&
            (chopSamples.toDouble() / max(totalBlocked, 1).toDouble()) >= BOTTLENECK_SHARE
        ) {
            val current = config.minEfficiencyRatio
            val proposed = (current + 0.03).coerceIn(MIN_EFFICIENCY, MAX_EFFICIENCY)
            if (abs(proposed - current) > 1e-6) {
                actions += SelfHealingAction(
                    id = "heal_efficiency",
                    kind = HealingKind.EFFICIENCY_FILTER,
                    strategyId = strategyId,
                    targetFa = strategyTitleFa,
                    conditionIndex = 13,
                    conditionTitleFa = "کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14)",
                    problemFa = "$chopSamples باخت در بازار رِنج/بی‌جهت ثبت شده است",
                    reasonFa = "کف کارایی روند ۳ صدم بالا می‌رود؛ ورود فقط در بازار جهت‌دار مجاز می‌شود.",
                    samples = chopSamples,
                    previousValue = current,
                    newValue = round(proposed * 100.0) / 100.0,
                    createdAtMs = nowMillis,
                )
            }
        }

        // ── ۴. از نکات دستیار: هر نکته‌ای که پارامتر پیشنهادی دارد ──
        notes.forEach { note ->
            note.suggestedSlBufferAtr?.let { v ->
                val proposed = v.coerceIn(MIN_SL_BUFFER, MAX_SL_BUFFER)
                if (abs(proposed - config.slBufferAtr) > 1e-6) {
                    actions += SelfHealingAction(
                        id = "note_${note.id}_sl",
                        kind = HealingKind.STOP_BUFFER,
                        strategyId = strategyId,
                        targetFa = note.targetStrategyTitleFa,
                        conditionIndex = 0,
                        conditionTitleFa = note.categoryFa,
                        problemFa = note.problemDetectedFa,
                        reasonFa = note.exactFixInstructionFa,
                        samples = MIN_SAMPLES_FOR_ACTION,
                        previousValue = config.slBufferAtr,
                        newValue = round(proposed * 100.0) / 100.0,
                        createdAtMs = nowMillis,
                    )
                }
            }
            note.suggestedMaxKijunExtAtr?.let { v ->
                val proposed = v.coerceIn(MIN_KIJUN_EXT, MAX_KIJUN_EXT)
                if (abs(proposed - config.maxKijunExtensionAtr) > 1e-6) {
                    actions += SelfHealingAction(
                        id = "note_${note.id}_kijun",
                        kind = HealingKind.KIJUN_STRETCH,
                        strategyId = strategyId,
                        targetFa = note.targetStrategyTitleFa,
                        conditionIndex = 0,
                        conditionTitleFa = note.categoryFa,
                        problemFa = note.problemDetectedFa,
                        reasonFa = note.exactFixInstructionFa,
                        samples = MIN_SAMPLES_FOR_ACTION,
                        previousValue = config.maxKijunExtensionAtr,
                        newValue = round(proposed * 100.0) / 100.0,
                        createdAtMs = nowMillis,
                    )
                }
            }
            note.suggestedMinRr?.let { v ->
                val proposed = v.coerceIn(MIN_RR, MAX_RR)
                if (abs(proposed - config.minRiskReward) > 1e-6) {
                    actions += SelfHealingAction(
                        id = "note_${note.id}_rr",
                        kind = HealingKind.R_R_CORRIDOR,
                        strategyId = strategyId,
                        targetFa = note.targetStrategyTitleFa,
                        conditionIndex = 12,
                        conditionTitleFa = note.categoryFa,
                        problemFa = note.problemDetectedFa,
                        reasonFa = note.exactFixInstructionFa,
                        samples = MIN_SAMPLES_FOR_ACTION,
                        previousValue = config.minRiskReward,
                        newValue = round(proposed * 100.0) / 100.0,
                        createdAtMs = nowMillis,
                    )
                }
            }
        }

        // یک تغییر برای هر پارامتر کافی است: بزرگ‌ترین بهبود را نگه دار.
        return actions
            .filterNot { it.isNoOp }
            .sortedByDescending { abs(it.delta) }
            .distinctBy { it.kind }
    }

    /**
     * اقدام‌های خودترمیم را روی کانفیگ اعمال می‌کند.
     *
     * @return کانفیگ جدید + فهرست اقدام‌هایی که واقعاً اعمال شدند.
     */
    fun applyHealing(
        config: BaseStrategyConfig,
        actions: List<SelfHealingAction>,
    ): Pair<BaseStrategyConfig, List<SelfHealingAction>> {
        var next = config
        val applied = ArrayList<SelfHealingAction>()
        actions.forEach { action ->
            when (action.kind) {
                HealingKind.STOP_BUFFER -> {
                    if (abs(next.slBufferAtr - action.previousValue) < 1e-6) {
                        next = next.copy(slBufferAtr = action.newValue.coerceIn(MIN_SL_BUFFER, MAX_SL_BUFFER))
                        applied += action
                    }
                }
                HealingKind.KIJUN_STRETCH -> {
                    if (abs(next.maxKijunExtensionAtr - action.previousValue) < 1e-6) {
                        next = next.copy(maxKijunExtensionAtr = action.newValue.coerceIn(MIN_KIJUN_EXT, MAX_KIJUN_EXT))
                        applied += action
                    }
                }
                HealingKind.EFFICIENCY_FILTER -> {
                    if (abs(next.minEfficiencyRatio - action.previousValue) < 1e-6) {
                        next = next.copy(minEfficiencyRatio = action.newValue.coerceIn(MIN_EFFICIENCY, MAX_EFFICIENCY))
                        applied += action
                    }
                }
                HealingKind.R_R_CORRIDOR -> {
                    if (abs(next.minRiskReward - action.previousValue) < 1e-6) {
                        next = next.copy(minRiskReward = action.newValue.coerceIn(MIN_RR, MAX_RR))
                        applied += action
                    }
                }
                HealingKind.VOLUME_FILTER, HealingKind.STRATEGY_WEIGHT -> Unit
            }
        }
        return next to applied
    }

    /** برگشت کامل: هر اقدام به مقدار قبلی خودش. */
    fun revertActions(
        config: BaseStrategyConfig,
        actions: List<SelfHealingAction>,
    ): BaseStrategyConfig {
        var next = config
        actions.forEach { action ->
            when (action.kind) {
                HealingKind.STOP_BUFFER -> next = next.copy(slBufferAtr = action.previousValue)
                HealingKind.KIJUN_STRETCH -> next = next.copy(maxKijunExtensionAtr = action.previousValue)
                HealingKind.EFFICIENCY_FILTER -> next = next.copy(minEfficiencyRatio = action.previousValue)
                HealingKind.R_R_CORRIDOR -> next = next.copy(minRiskReward = action.previousValue)
                HealingKind.VOLUME_FILTER, HealingKind.STRATEGY_WEIGHT -> Unit
            }
        }
        return next
    }

    // ─────────────────────────── ماتریس برتری ───────────────────────────

    /**
     * یک سطر ماتریس: استراتژی × وضعیت لبه.
     *
     * @param allocationShare سهم پیشنهادی از بودجهٔ ریسک (۰..۱، مجموع = ۱).
     */
    data class EdgeMatrixRow(
        val strategyId: String,
        val strategyTitleFa: String,
        val tradesCount: Int,
        val winRatePercent: Double,
        val expectancyR: Double,
        val profitFactor: Double,
        val edgeScore: Double,
        /** ضریب اطمینان آماری بر پایهٔ تعداد نمونه (۰..۱). */
        val confidence: Double,
        /** لبهٔ «تعدیل‌شده با نمونه» = edgeScore × confidence. */
        val adjustedEdge: Double,
        val allocationShare: Double,
        val verdictFa: String,
    )

    /**
     * ماتریس برتری را می‌سازد و بودجهٔ ریسک را بین استراتژی‌ها توزیع می‌کند.
     *
     * روش: **Thompson-style shrinkage** — لبهٔ خام هر استراتژی با تعداد نمونه‌اش وزن می‌گیرد
     * (`confidence = n / (n + 10)`). استراتژی با ۲ معامله نمی‌تواند بودجهٔ استراتژی با ۵۰ معامله را
     * بگیرد، حتی اگر نرخ بردش بالاتر باشد. بودجهٔ هر استراتژی صفر یا مثبت است و مجموع دقیقاً ۱.
     */
    fun buildEdgeMatrix(
        edges: List<NeuralStrategyTradingEdge>,
        totalSampleSoftCap: Int = 10,
    ): List<EdgeMatrixRow> {
        if (edges.isEmpty()) return emptyList()
        val cap = totalSampleSoftCap.coerceAtLeast(1)
        val rows = edges.map { e ->
            val n = e.tradesCount.coerceAtLeast(0)
            val confidence = n.toDouble() / (n.toDouble() + cap.toDouble())
            val rawEdge = when {
                n == 0 -> 0.0
                // امید ریاضی مثبت مهم‌ترین معیار است؛ نرخ برد و PF تعدیل‌کنندهٔ آن‌اند.
                else -> e.expectancyR * 40.0 + (e.winRatePercent - 50.0) * 0.4 +
                    (e.profitFactor.coerceIn(0.0, 4.0) - 1.0) * 8.0
            }
            val adjusted = rawEdge * confidence
            EdgeMatrixRow(
                strategyId = e.strategyId,
                strategyTitleFa = e.strategyTitleFa,
                tradesCount = n,
                winRatePercent = e.winRatePercent,
                expectancyR = e.expectancyR,
                profitFactor = e.profitFactor,
                edgeScore = e.edgeScore.toDouble(),
                confidence = round(confidence * 100.0) / 100.0,
                adjustedEdge = round(adjusted * 100.0) / 100.0,
                allocationShare = 0.0,
                verdictFa = "",
            )
        }
        // سهم بودجه فقط برای لبهٔ مثبت؛ اگر هیچ‌کدام مثبت نبود، تقسیم مساوی (سیستم خاموش نماند).
        val positives = rows.filter { it.adjustedEdge > 0.0 }
        val positiveSum = positives.sumOf { it.adjustedEdge }
        val evenShare = 1.0 / rows.size.toDouble()
        return rows.map { row ->
            val share = when {
                positiveSum > 0.0 && row.adjustedEdge > 0.0 -> row.adjustedEdge / positiveSum
                positiveSum == 0.0 -> evenShare
                else -> 0.0
            }
            val verdict = when {
                row.tradesCount == 0 -> "⚪ بدون نمونه — بودجهٔ آزمایشی حداقلی"
                row.tradesCount < MIN_SAMPLES_FOR_ACTION -> "🟡 نمونهٔ کم (${row.tradesCount}) — نتیجه‌گیری زود است"
                row.adjustedEdge >= 12.0 -> "🟢 لبهٔ قوی و اندازه‌گیری‌شده — بودجهٔ کامل"
                row.adjustedEdge > 0.0 -> "🟢 لبهٔ مثبت — بودجهٔ متناسب"
                else -> "🔴 لبهٔ منفی — بودجه صفر تا اصلاح"
            }
            row.copy(
                allocationShare = round(share * 1000.0) / 1000.0,
                verdictFa = verdict,
            )
        }.sortedWith(
            compareByDescending<EdgeMatrixRow> { it.adjustedEdge }
                .thenByDescending { it.tradesCount },
        )
    }

    /**
     * بودجهٔ ریسک یک استراتژی برای یک معاملهٔ مشخص.
     *
     * استراتژی با سهم صفر، بودجهٔ «آزمایشی» کوچک می‌گیرد تا داده جمع شود — بدون آن، استراتژی
     * هرگز نمی‌تواند خودش را ثابت کند.
     */
    fun riskBudgetFor(
        row: EdgeMatrixRow?,
        totalRiskBudgetUsd: Double,
        minTrialShare: Double = 0.05,
    ): Double {
        if (totalRiskBudgetUsd <= 0.0) return 0.0
        val share = row?.allocationShare ?: (1.0 / 19.0)
        val effective = if (share <= 0.0) minTrialShare else share
        return round(totalRiskBudgetUsd * effective * 100.0) / 100.0
    }

    // ─────────────────────────── خلاصهٔ فارسی ───────────────────────────

    /** گزارش خوانا برای کارت «معامله‌گر هوشمند» در UI. */
    fun buildTraderSummaryFa(
        forecasts: List<SymbolConditionsForecast>,
        healings: List<SelfHealingAction>,
        matrix: List<EdgeMatrixRow>,
    ): List<String> = buildList {
        val actionable = forecasts.filter { it.isActionable }
        add(
            "🔍 پیش‌بینی خبری: ${actionable.size} نماد در آستانهٔ سیگنال از ${forecasts.size} نماد رصدشده",
        )
        actionable.take(5).forEach { f ->
            add(
                String.format(
                    java.util.Locale.US,
                    "• %s — %s · %d/%d (%.0f%%) · ETA: %s · %s",
                    f.symbol,
                    f.bestStrategyTitleFa,
                    f.emergingPassed,
                    f.totalConditions,
                    f.proximityPercent,
                    f.forecastEtaFa,
                    f.preemptiveActionFa,
                ),
            )
        }
        add("🛠️ خودترمیمی: ${healings.size} اصلاح آماده است")
        healings.take(6).forEach { h -> add("• ${h.summaryFa} — ${h.problemFa}") }
        val withEdge = matrix.filter { it.allocationShare > 0.0 }
        add(
            "📊 ماتریس برتری: ${withEdge.size} استراتژی بودجهٔ ریسک دارند از ${matrix.size} استراتژی",
        )
        matrix.filter { it.allocationShare > 0.0 }.take(5).forEach { m ->
            add(
                String.format(
                    java.util.Locale.US,
                    "• %s — سهم %.1f%% · لبهٔ تعدیل‌شده %.2f · %s",
                    m.strategyTitleFa,
                    m.allocationShare * 100.0,
                    m.adjustedEdge,
                    m.verdictFa,
                ),
            )
        }
        matrix.filter { it.allocationShare <= 0.0 && it.tradesCount > 0 }.take(3).forEach { m ->
            add("• ⛔ ${m.strategyTitleFa} — بودجه صفر (لبهٔ منفی اندازه‌گیری‌شده)")
        }
    }
}
