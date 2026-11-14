package com.aurum.edge.core

/**
 * # سیاست گیت عصبی «پیش‌رونده» (Progressive Neural Gate)
 *
 * ## چه چیزی خراب بود
 * گیت پیشین فقط وقتی وارد مدار می‌شد که **۱۰٬۰۰۰** معاملهٔ یادگیری‌شده ثبت شده باشد
 * (`WARMUP_TARGET_TRADES`). در عمل یعنی گیت هرگز جلوی هیچ معامله‌ای را نگرفت: شبکه ۱۵ تلهٔ
 * ممنوعه را کشف می‌کرد، روی هر ۳۰ ردیف رادار «⛔ شرایط پرخطر» می‌نوشت و همان معاملات اجرا می‌شدند.
 *
 * ## سیاست جدید
 * گیت از **همان اولین نمونه** (و با فعال‌سازی صریح کاربر) وارد مدار می‌شود و با رشد داده
 * سخت‌گیرتر می‌شود — نه سهل‌گیرتر. سه پله:
 *
 * | پله | نمونه | فلسفه |
 * |---|---|---|
 * | `PROTECTIVE` | ۱ تا ۲۹ | فقط تله‌های واضح را می‌گیرد (احتمال برد خیلی پایین یا زیان انتظاری منفی) تا دادهٔ کافی جمع شود |
 * | `BALANCED` | ۳۰ تا ۱۴۹ | آستانهٔ برد مثبت و امید ریاضی نامنفی |
 * | `STRICT` | ۱۵۰+ | همان آستانهٔ تیون‌شدهٔ شبکه (مثلاً ۶۲٪) با حاشیهٔ محافظه‌کارانه |
 *
 * قاعدهٔ آهنین: هر خوشهٔ شرطی که شبکه صریحاً «ممنوعه» علامت زده و دست‌کم [MIN_CLUSTER_SAMPLES]
 * نمونه دارد، در **همهٔ** پله‌ها مسدود می‌شود (شواهد مستقیم بر آستانهٔ مدل مقدم است).
 */
object NeuralGatePolicy {

    /** کمینهٔ نمونه برای فعال‌شدن گیت؛ «از همان ابتدا با هر عددی» = ۱. */
    const val MIN_SAMPLES_FOR_GATE: Int = 1

    /** کمینهٔ نمونهٔ یک خوشهٔ شرطی برای اینکه «ممنوعه» بودنش به‌تنهایی کافی باشد. */
    const val MIN_CLUSTER_SAMPLES: Int = 3

    /** پلهٔ سخت‌گیری گیت بر اساس تعداد معاملات یادگیری‌شده. */
    enum class Stage(val labelFa: String, val minSamples: Int, val winProbFloorPct: Double, val expectedRFloor: Double) {
        PROTECTIVE("پلهٔ ۱ · محافظ (داده کم)", 1, 28.0, -0.10),
        BALANCED("پلهٔ ۲ · متعادل", 30, 40.0, 0.00),
        STRICT("پلهٔ ۳ · سخت‌گیر", 150, 46.0, 0.05);

        companion object {
            fun forSamples(learnedTotal: Int): Stage = when {
                learnedTotal >= STRICT.minSamples -> STRICT
                learnedTotal >= BALANCED.minSamples -> BALANCED
                else -> PROTECTIVE
            }
        }
    }

    /**
     * نتیجهٔ داوری گیت برای یک سیگنال.
     *
     * @property isActive گیت در مدار است (کاربر فعال کرده و دست‌کم یک نمونه داریم).
     * @property blocksTrade آیا همین سیگنال باید مسدود شود.
     */
    data class Decision(
        val stage: Stage,
        val isActive: Boolean,
        val blocksTrade: Boolean,
        val winProbFloorPct: Double,
        val expectedRFloor: Double,
        val reasonFa: String,
        val summaryFa: String,
    )

    /**
     * داوری ورود.
     *
     * @param learnedTotal تعداد معاملات یادگیری‌شده.
     * @param userActivationEnabled فعال‌سازی صریح گیت توسط کاربر (شرط دوم قبلی؛ حفظ شده).
     * @param winProbabilityPct احتمال برد ترکیبی شبکه/باندیت برای همین شرایط (۰..۱۰۰).
     * @param expectedR امید ریاضی ترکیبی همین شرایط بر حسب R.
     * @param clusterFlaggedNoTrade خوشهٔ شرطی جاری «منطقهٔ ممنوعه» علامت خورده است.
     * @param clusterSamples تعداد نمونه‌های همان خوشه.
     * @param tunedGatePct آستانهٔ آموخته‌شدهٔ گیت (مثلاً ۶۲.۰)؛ ۰ = بی‌اثر.
     */
    fun decide(
        learnedTotal: Int,
        userActivationEnabled: Boolean,
        winProbabilityPct: Double,
        expectedR: Double,
        clusterFlaggedNoTrade: Boolean,
        clusterSamples: Int,
        tunedGatePct: Double,
    ): Decision {
        val stage = Stage.forSamples(learnedTotal)
        val active = userActivationEnabled && learnedTotal >= MIN_SAMPLES_FOR_GATE
        val safeGate = if (tunedGatePct.isFinite() && tunedGatePct > 0.0) tunedGatePct else 0.0

        // آستانهٔ برد مؤثر: کف پله، اما هرگز سخاوتمندانه‌تر از آستانهٔ تیون‌شده (با حاشیهٔ ۰٫۸۴).
        val stageFloor = stage.winProbFloorPct
        val tunedFloor = if (safeGate > 0.0) safeGate * 0.84 else 0.0
        val winFloor = if (tunedFloor > 0.0) maxOf(stageFloor, tunedFloor) else stageFloor
        val rFloor = stage.expectedRFloor

        val directClusterTrap = clusterFlaggedNoTrade && clusterSamples >= MIN_CLUSTER_SAMPLES
        val lowWin = winProbabilityPct.isFinite() && winProbabilityPct < winFloor
        val badR = expectedR.isFinite() && expectedR < rFloor

        if (!active) {
            return Decision(
                stage = stage,
                isActive = false,
                blocksTrade = false,
                winProbFloorPct = winFloor,
                expectedRFloor = rFloor,
                reasonFa = "گیت با فعال‌سازی شما روشن نشده است؛ فقط هشدار داده می‌شود.",
                summaryFa = "⚪ گیت غیرفعال · ${stage.labelFa}",
            )
        }

        val reasons = buildList {
            if (directClusterTrap) {
                add("این خوشهٔ شرطی با ${clusterSamples} نمونه «منطقهٔ ممنوعه» علامت خورده است")
            }
            if (lowWin) {
                add("احتمال برد ${fmtPct(winProbabilityPct)} کمتر از کف ${fmtPct(winFloor)} است")
            }
            if (badR) {
                add("امید ریاضی ${fmtR(expectedR)} کمتر از کف ${fmtR(rFloor)} است")
            }
        }
        val blocks = reasons.isNotEmpty()
        return Decision(
            stage = stage,
            isActive = true,
            blocksTrade = blocks,
            winProbFloorPct = winFloor,
            expectedRFloor = rFloor,
            reasonFa = if (blocks) reasons.joinToString(" · ") else "شرایط از کف ${stage.labelFa} عبور کرد.",
            summaryFa = buildString {
                append(if (blocks) "🛑 گیت ${stage.labelFa}" else "🟢 گیت ${stage.labelFa}")
                append(" · کف برد ${fmtPct(winFloor)} · کف R ${fmtR(rFloor)}")
                append(" · نمونه ${learnedTotal}")
            },
        )
    }

    // ─────────────────────── درگاه بک‌تست ───────────────────────

    /**
     * خواستهٔ گیت برای یک ورودِ نامزد در بک‌تست.
     *
     * این کلاس در فایل «خالص» (بدون وابستگی اندروید) نگه داشته می‌شود تا
     * `BacktestReplayEngine` بتواند در محیط آزمایشی JVM هم کامپایل و آزمون شود.
     * پیاده‌سازی واقعی — که شبکهٔ عصبی را می‌پرسد — در `BacktestNeuralTrainer` است.
     */
    data class GateRequest(
        val strategyId: String,
        val symbol: String,
        /** جهت معامله؛ برای نمایش و کلید خوشه. */
        val isLong: Boolean,
        /** تایم‌فریم ماشه؛ بخشی از بردار ویژگی شبکه است. */
        val triggerInterval: com.aurum.edge.domain.Interval,
        /** اعتماد موتور استراتژی در همین کندل (۰..۱۰۰). */
        val engineConfidencePct: Double,
        val plannedRiskReward: Double,
        val entryTime: Long,
        /**
         * گزارش کامل استراتژی در همین کندل.
         *
         * بدون این، گیت مجبور می‌شد ویژگی‌ها را از هوا بسازد. با آن، همان بردار ویژگی‌ای
         * ساخته می‌شود که هنگام آموزش از معاملهٔ بسته‌شده ساخته می‌شد — پس گیت دقیقاً
         * همان چیزی را می‌بیند که شبکه یاد گرفته است.
         */
        val report: StrategyEvaluationReport? = null,
    )

    /** پاسخ گیت به یک ورودِ نامزد. */
    data class GateVerdict(
        val blocks: Boolean,
        val reasonFa: String,
        val summaryFa: String,
    ) {
        companion object {
            val ALLOW = GateVerdict(false, "گیت مانع نشد.", "🟢 مانع ورود ندارد")
        }
    }

    /**
     * درگاهی که بک‌تست قبل از بازکردن هر معامله از آن می‌پرسد.
     *
     * چرا این لازم بود: کلید «مانع» در بخش هوش عصبی تا امروز **هیچ اثری در بک‌تست
     * نداشت**، چون `BacktestReplayEngine` هیچ‌جا `NeuralGatePolicy.decide` را صدا
     * نمی‌زد. نتیجه همان چیزی بود که کاربر دید: «با مانع و بدون مانع یکی بود».
     */
    fun interface EntryGate {
        fun judge(request: GateRequest): GateVerdict

        companion object {
            /** گیت خاموش: هیچ ورودی مسدود نمی‌شود (رفتار پیش‌فرض و سازگار با قبل). */
            val ALLOW_ALL: EntryGate = EntryGate { GateVerdict.ALLOW }
        }
    }

    private fun fmtPct(v: Double): String = "${(kotlin.math.round(v * 10.0) / 10.0)}%"

    private fun fmtR(v: Double): String = String.format(java.util.Locale.US, "%+.2fR", v)
}
