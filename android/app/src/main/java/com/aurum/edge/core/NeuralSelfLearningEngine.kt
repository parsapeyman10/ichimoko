package com.aurum.edge.core

import android.content.Context
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * موتور شبکه عصبی خودآموز نسل جدید (2026 SOTA Online Quant Architecture):
 * **FT-Transformer Feature Self-Attention + SwiGLU Residual Network + AdamW Online Learner + Contextual Bandit Memory**
 *
 * ویژگی‌های کلیدی طبق دستور کاربر:
 * ۱. یادگیری مستمر و خودکار از تک‌تک معاملات بسته‌شده در هر دسته بازار (`CRYPTO` رمزارزها، `COMMODITY` طلا و نقره، `FOREX` فارکس، `STOCK` شاخص داوجونز)
 *    و هر ۱۵ ترکیب استراتژی در هر دو تایم‌فریم `15m` و `5m`.
 * ۲. تشخیص دقیق «شرایط فعلی بازار» و یادگیری «در چه شرایطی در هر دسته نباید معامله کند (No-Trade Regime Detection)».
 * ۳. قانون بلوغ ۱۰,۰۰۰ معامله (`WARMUP_TARGET_TRADES = 10_000`):
 *    - تا قبل از تکمیل `10,000` معامله یادگیری‌شده، شبکه عصبی در **حالت یادگیری خالص (Shadow Learning Mode)** کار می‌کند:
 *      با هر معامله آموزش می‌بیند و شرایط ممنوعه هر دسته را کشف و گزارش می‌کند اما مانع باز شدن معاملات نمی‌شود.
 *    - به محض عبور از مرز **۱۰,۰۰۰ معامله یادگیری‌شده**، گیت اجرایی شبکه عصبی به‌طور خودکار وارد مدار شده و از ورود در شرایطی که شبکه آن‌ها را پرخطر/ضررده تشخیص داده جلوگیری می‌کند.
 */
@Serializable
data class NeuralCategoryRegimeStats(
    val categoryCode: String,
    val categoryTitleFa: String,
    val learnedTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val emaWinRatePct: Double = 50.0,
    val emaExpectedR: Double = 0.0,
    val discoveredNoTradeRulesFa: List<String> = emptyList(),
)

@Serializable
data class ConditionClusterRecord(
    val signatureKey: String,
    val categoryCode: String,
    val conditionDescriptionFa: String,
    val totalCount: Int = 0,
    val winCount: Int = 0,
    val lossCount: Int = 0,
    val cumulativeR: Double = 0.0,
    val lastUpdatedAt: Long = 0L,
) {
    // Bayesian Thompson / Beta(alpha=win+1, beta=loss+1) posterior mean win probability
    val posteriorWinProb: Double
        get() = (winCount + 1.0) / (totalCount + 2.0)

    val avgR: Double
        get() = if (totalCount > 0) cumulativeR / totalCount else 0.0

    val isFlaggedNoTradeRegime: Boolean
        get() = totalCount >= 3 && (posteriorWinProb < 0.36 || avgR < -0.25)
}

@Serializable
data class ReplayExperienceSample(
    val tradeId: String,
    val categoryCode: String,
    val symbol: String,
    val strategyId: String,
    val timeframeLabel: String,
    val features: List<Double>,
    val conditionKey: String,
    val conditionDescriptionFa: String,
    val won: Boolean,
    val achievedR: Double,
    val timestampMs: Long,
)

@Serializable
data class NeuralNetworkPersistedState(
    val schemaVersion: Int = 1,
    val architectureName: String = "FT-Attention + SwiGLU Residual + AdamW Online (2026 SOTA)",
    val totalLearnedTrades: Int = 0,
    val warmupTargetTrades: Int = NeuralSelfLearningEngine.WARMUP_TARGET_TRADES,
    val forceActiveBeforeWarmup: Boolean = false,
    val adamStepCount: Int = 0,
    val runningBceLoss: Double = 0.693,
    val runningAccuracyPct: Double = 50.0,
    // Attention & SwiGLU Residual Weights (16 input features -> 16 hidden -> 16 gated -> 2 heads)
    val attentionWeights: List<Double> = emptyList(),
    val gateWeights: List<Double> = emptyList(),
    val gateBias: List<Double> = emptyList(),
    val upWeights: List<Double> = emptyList(),
    val upBias: List<Double> = emptyList(),
    val headWinWeights: List<Double> = emptyList(),
    val headWinBias: Double = 0.15,
    val headRWeights: List<Double> = emptyList(),
    val headRBias: Double = 0.35,
    // First and second moments for AdamW
    val mHeadWin: List<Double> = emptyList(),
    val vHeadWin: List<Double> = emptyList(),
    val mGate: List<Double> = emptyList(),
    val vGate: List<Double> = emptyList(),
    val categoryStats: Map<String, NeuralCategoryRegimeStats> = emptyMap(),
    val conditionClusters: Map<String, ConditionClusterRecord> = emptyMap(),
    val replayBuffer: List<ReplayExperienceSample> = emptyList(),
)

data class NeuralCategorySummary(
    val categoryCode: String,
    val categoryTitleFa: String,
    val learnedTrades: Int,
    val winRatePercent: Double,
    val bestConditionSummaryFa: String,
    val worstNoTradeConditionSummaryFa: String,
    val noTradeConditionCount: Int,
)

data class NeuralFeatureInsight(
    val index: Int,
    val titleFa: String,
    val attentionPercent: Double,
    val netWeightImpact: Double,
    val impactLabelFa: String,
    val explanationFa: String,
)

data class NeuralRecentLesson(
    val tradeId: String,
    val symbol: String,
    val categoryCode: String,
    val strategyId: String,
    val timeframeLabel: String,
    val won: Boolean,
    val achievedR: Double,
    val conditionDescriptionFa: String,
    val lessonLearnedFa: String,
    val timestampMs: Long,
)

data class NeuralStrategyTradingEdge(
    val strategyId: String,
    val strategyTitleFa: String,
    val strategyBadgeFa: String,
    val isDriveBookStrategy: Boolean,
    val tradesCount: Int,
    val winRatePercent: Double,
    val profitFactor: Double,
    val expectancyR: Double,
    val avgWinR: Double,
    val avgLossR: Double,
    val bestTimeframeLabel: String,
    val bestSymbol: String,
    val edgeScore: Int,
    val tradingVerdictFa: String,
)

data class NeuralTradingSessionEdge(
    val sessionCode: String,
    val sessionTitleFa: String,
    val utcHoursLabel: String,
    val tradesCount: Int,
    val winRatePercent: Double,
    val avgR: Double,
    val sessionAdviceFa: String,
)

data class NeuralTradeForensicsSummary(
    val totalWinsAnalyzed: Int = 0,
    val totalLossesAnalyzed: Int = 0,
    val avgWinR: Double = 0.0,
    val avgLossR: Double = 0.0,
    val profitFactor: Double = 1.0,
    val expectancyPerTradeR: Double = 0.0,
    val bestTimeframeSummaryFa: String = "15m (پایداری روند بالاتر) در کنار 5m (شکار سریع موج)",
    val topWinDriversFa: List<String> = emptyList(),
    val topLossCausesFa: List<String> = emptyList(),
    val actionableTradingRulesFa: List<String> = emptyList(),
)

data class NeuralLearningStateSummary(
    val architectureNameFa: String = "FT-Transformer Attention + SwiGLU Residual + AdamW Online + Contextual Bandit",
    val totalLearnedTrades: Int = 0,
    val warmupTargetTrades: Int = NeuralSelfLearningEngine.WARMUP_TARGET_TRADES,
    val warmupProgressPercent: Double = 0.0,
    val remainingWarmupTrades: Int = NeuralSelfLearningEngine.WARMUP_TARGET_TRADES,
    val isWarmupCompleted: Boolean = false,
    val forceActiveBeforeWarmup: Boolean = false,
    val isActiveFilterEnabled: Boolean = false,
    val discoveredNoTradeClustersCount: Int = 0,
    val categoryStats: List<NeuralCategorySummary> = emptyList(),
    val runningAccuracyPct: Double = 50.0,
    val runningBceLoss: Double = 0.693,
    val adamStepCount: Int = 0,
    val featureInsights: List<NeuralFeatureInsight> = emptyList(),
    val learnedInsightsFeedFa: List<String> = emptyList(),
    val winningClusters: List<ConditionClusterRecord> = emptyList(),
    val noTradeClusters: List<ConditionClusterRecord> = emptyList(),
    val recentLessons: List<NeuralRecentLesson> = emptyList(),
    val strategyEdgeMatrix: List<NeuralStrategyTradingEdge> = emptyList(),
    val sessionEdges: List<NeuralTradingSessionEdge> = emptyList(),
    val tradeForensics: NeuralTradeForensicsSummary = NeuralTradeForensicsSummary(),
)

data class NeuralSignalEvaluation(
    val symbol: String,
    val conditionKey: String,
    val predictedWinProb: Double,
    val expectedR: Double,
    val isNoTradeCondition: Boolean,
    val isBlockingTradeNow: Boolean,
    val shouldAllowTrade: Boolean,
    val summaryFa: String,
    val noTradeWarningFa: String? = null,
)

data class NeuralMarketInference(
    val symbol: String,
    val categoryCode: String,
    val categoryTitleFa: String,
    val timeframeLabel: String,
    val strategyId: String,
    val winProbabilityPct: Double,
    val expectedRMultiple: Double,
    val dangerScorePct: Double,
    val isNoTradeConditionDetected: Boolean,
    val isBlockingTradeNow: Boolean,
    val totalLearnedTrades: Int,
    val warmupTargetTrades: Int,
    val warmupProgressPct: Double,
    val currentConditionSummaryFa: String,
    val whyAvoidOrAllowFa: String,
    val topAttentionFactorFa: String,
)

class NeuralSelfLearningEngine(
    context: Context? = null,
    private val storageFile: File? = context?.let { File(it.filesDir, "neural_self_learning_v1.json") },
) {
    companion object {
        const val WARMUP_TARGET_TRADES: Int = 10_000
        const val INPUT_DIM: Int = 16
        const val HIDDEN_DIM: Int = 16
        private const val MAX_REPLAY_BUFFER: Int = 320
        private const val MAX_CONDITION_CLUSTERS: Int = 240
        private const val BASE_LR: Double = 0.018
        private const val WEIGHT_DECAY: Double = 1e-4
        private const val BETA1: Double = 0.90
        private const val BETA2: Double = 0.999
        private const val EPS: Double = 1e-8

        fun categoryTitleFa(assetClass: AssetClass): String = when (assetClass) {
            AssetClass.CRYPTO -> "دسته رمزارزها (Crypto)"
            AssetClass.COMMODITY -> "دسته فلزات گران‌بها (طلا و نقره)"
            AssetClass.FOREX -> "دسته جفت‌ارزهای فارکس (Forex)"
            AssetClass.STOCK -> "دسته شاخص وال‌استریت (DOW)"
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val appContext: Context? = context?.applicationContext
    private var persistedState: NeuralNetworkPersistedState = loadOrInitializeState()
    private val _stateFlow = MutableStateFlow(persistedState)
    val stateFlow: StateFlow<NeuralNetworkPersistedState> = _stateFlow.asStateFlow()

    private val _summaryFlow = MutableStateFlow(buildStateSummary(persistedState))
    val state: StateFlow<NeuralLearningStateSummary> = _summaryFlow.asStateFlow()

    private fun featureTitleFa(index: Int): Pair<String, String> = when (index) {
        0 -> "۱. رژیم دسته رمزارز (CRYPTO)" to "حساسیت و نوسان لحظه‌ای بازار کریپتو"
        1 -> "۲. رژیم دسته طلا و نقره (COMMODITY)" to "رفتار روندهای پناهگاه امن اونس طلا و نقره"
        2 -> "۳. رژیم دسته جفت‌ارز فارکس (FOREX)" to "تعادل و نوسانات سشن‌های فارکس"
        3 -> "۴. رژیم دسته شاخص وال‌استریت (STOCK/DOW)" to "مومنتوم شاخص داوجونز آمریکا"
        4 -> "۵. تایم‌فریم ورود (15m در برابر 5m)" to "مقایسه پایداری کندل ۱۵ دقیقه با سرعت ۵ دقیقه"
        5 -> "۶. جهت معامله (خرید LONG در برابر فروش SHORT)" to "تقارن روند صعودی و نزولی در کومو"
        6 -> "۷. ستون ایچیموکو مانش پاتل (Kijun/MTF)" to "وزن تعادل کیجون‌سن و همگرایی ۳ تایم‌فریم"
        7 -> "۸. ستون ایچیموکو مارک تاتل (Chikou/SSB)" to "وزن رهایی چیکو اسپن و شکست فلت SSB"
        8 -> "۹. ستون امواج و زمان هوسودا (Wave/Time)" to "وزن اهداف V/N/E و چرخه زمانی هوسودا"
        10 -> "۱۱. کارایی روند کافمن (Efficiency Ratio ER14)" to "تفکیک روند خالص از بازار رِنج و فرسایشی"
        11 -> "۱۲. ضخامت ابر آینده ۲۶ کندلی (Future Kumo)" to "قدرت حمایت/مقاومت ابر آینده ۲۶ کندل جلوتر"
        12 -> "۱۳. فاصله کشیدگی از کیجون (C-Clamp Overextension)" to "تشخیص اشباع قیمت و خطر بازگشت به کیجون"
        13 -> "۱۴. کیفیت بدنه کندل و شدوی مخالف (Wick/CLV)" to "تشخیص تله فیک‌بریک‌اوت و رد شدوی معکوس"
        14 -> "۱۵. عدد مناسب R:R معامله (Optimal R:R)" to "نسبت پاداش به ریسک بهینه در ساختار فعلی"
        15 -> "۱۶. دقت نقطه‌زنی ورود (Sniper Score & Phase)" to "ورود در ناحیه طلایی تنکان بدون تعقیب قیمت"
        else -> "۱۰. ستون پلاتوهای کارن پلویل / کتاب‌های درایو" to "وزن سطوح فلت، بولینگر، فرکتال و RSI/DMI"
    }

    private fun buildStateSummary(cur: NeuralNetworkPersistedState): NeuralLearningStateSummary {
        val total = cur.totalLearnedTrades
        val target = cur.warmupTargetTrades.coerceAtLeast(1)
        val warmupDone = total >= target
        val progressPct = ((total.toDouble() / target) * 100.0).coerceIn(0.0, 100.0)
        val allClusters = cur.conditionClusters.values.toList()
        val noTradeClusters = allClusters
            .filter { it.isFlaggedNoTradeRegime || (it.lossCount >= 2 && it.avgR < 0.0) }
            .sortedBy { it.avgR }
        val winningClusters = allClusters
            .filter { it.winCount >= 1 && it.avgR > 0.0 }
            .sortedByDescending { it.avgR * it.totalCount }

        val catSummaries = AssetClass.entries.map { ac ->
            val catRec = cur.categoryStats[ac.code]
            val catClusters = allClusters.filter { it.categoryCode == ac.code }
            val bestCluster = catClusters.filter { it.totalCount >= 1 }.maxByOrNull { it.avgR }
            val worstNoTrade = catClusters.filter { it.isFlaggedNoTradeRegime || it.avgR < 0.0 }.minByOrNull { it.avgR }
            val catNoTradeCount = catClusters.count { it.isFlaggedNoTradeRegime }
            val learned = catRec?.learnedTrades ?: 0
            val wins = catRec?.wins ?: 0
            val wr = if (learned > 0) (wins.toDouble() / learned) * 100.0 else 0.0
            NeuralCategorySummary(
                categoryCode = ac.code,
                categoryTitleFa = categoryTitleFa(ac),
                learnedTrades = learned,
                winRatePercent = wr,
                bestConditionSummaryFa = bestCluster?.let {
                    "${it.conditionDescriptionFa} (${String.format(Locale.US, "%+.2fR", it.avgR)})"
                } ?: "در حال جمع‌آوری داده در این دسته",
                worstNoTradeConditionSummaryFa = worstNoTrade?.let {
                    "${it.conditionDescriptionFa} (برد ${(it.posteriorWinProb * 100).toInt()}% · ${String.format(Locale.US, "%+.2fR", it.avgR)})"
                } ?: "هنوز شرایط ممنوعه‌ای در این دسته ثبت نشده است",
                noTradeConditionCount = catNoTradeCount,
            )
        }

        // استخراج وزن‌های توجه (Self-Attention) و تاثیر هر یک از ۱۶ ویژگی
        val attnSum = cur.attentionWeights.sum().coerceAtLeast(1e-6)
        val featInsights = (0 until INPUT_DIM).map { idx ->
            val (title, desc) = featureTitleFa(idx)
            val rawAttn = cur.attentionWeights.getOrElse(idx) { 0.15 }
            val attnPct = round(((rawAttn / attnSum) * 100.0) * 10.0) / 10.0
            val netW = cur.headWinWeights.getOrElse(idx) { 0.0 }
            val impactLabel = when {
                netW >= 0.20 -> "🟢 تقویت‌کننده قوی احتمال برد (+${String.format(Locale.US, "%.2f", netW)})"
                netW > 0.0 -> "🟢 اثر مثبت روی برد (+${String.format(Locale.US, "%.2f", netW)})"
                netW <= -0.20 -> "🔴 عامل هشدار و کاهش احتمال برد (${String.format(Locale.US, "%.2f", netW)})"
                else -> "🟡 اثر بازدارنده ملایم (${String.format(Locale.US, "%.2f", netW)})"
            }
            NeuralFeatureInsight(
                index = idx + 1,
                titleFa = title,
                attentionPercent = attnPct,
                netWeightImpact = round(netW * 100.0) / 100.0,
                impactLabelFa = impactLabel,
                explanationFa = desc,
            )
        }.sortedByDescending { it.attentionPercent }

        // تولید گزارش تحلیلی فارسی از تمام چیزهایی که شبکه عصبی تا این لحظه فهمیده و یاد گرفته است
        val insightsFeed = mutableListOf<String>()
        val topAttn1 = featInsights.getOrNull(0)
        val topAttn2 = featInsights.getOrNull(1)
        if (topAttn1 != null && topAttn2 != null) {
            insightsFeed += "🧠 تمرکز اصلی شبکه عصبی (FT-Attention): بیشترین وزن تصمیم‌گیری شبکه در حال حاضر روی «${topAttn1.titleFa}» (${topAttn1.attentionPercent}%) و «${topAttn2.titleFa}» (${topAttn2.attentionPercent}%) متمرکز شده است."
        }
        if (total == 0) {
            insightsFeed += "📚 وضعیت پایه (دانش اولیه ۲۴ استراتژی): شبکه عصبی با دانش پایه ایچیموکو (تقویت کارایی روند ER14، ضخامت ابر آینده ۲۶ کندلی و جریمه کشیدگی C-Clamp و شدوهای فیک‌بریک‌اوت) مقداردهی شده و به محض بسته شدن اولین معامله، وزن‌ها را با الگوریتم AdamW به‌روزرسانی می‌کند."
        } else {
            insightsFeed += String.format(
                Locale.US,
                "📈 خلاصه تجربه آنلاین: شبکه تاکنون از %,d معامله واقعی در ژورنال آموزش دیده، %,d گام بهینه‌سازی AdamW انجام داده و دقت پیش‌بینی جاری آن %.1f%% (خطای BCE=%.3f) است.",
                total,
                cur.adamStepCount,
                cur.runningAccuracyPct,
                cur.runningBceLoss,
            )
        }
        winningClusters.take(4).forEach { winCluster ->
            insightsFeed += String.format(
                Locale.US,
                "✅ الگوی سودده کشف‌شده: در «%s» شبکه با نرخ برد %.0f%% در %d معامله، میانگین بازدهی %+.2fR را ثبت کرده و این رژیم را به‌عنوان شرایط طلایی ورود تایید می‌کند.",
                winCluster.conditionDescriptionFa,
                winCluster.posteriorWinProb * 100.0,
                winCluster.totalCount,
                winCluster.avgR,
            )
        }
        noTradeClusters.take(5).forEach { badCluster ->
            insightsFeed += String.format(
                Locale.US,
                "⛔ تله ضررده و شرایط ممنوعه کشف‌شده: در «%s» نرخ برد فقط %.0f%% (%d باخت از %d معامله · میانگین %+.2fR) بوده است؛ شبکه این وضعیت را به‌عنوان منطقه ممنوعه معامله (No-Trade Regime) علامت‌گذاری کرده است.",
                badCluster.conditionDescriptionFa,
                badCluster.posteriorWinProb * 100.0,
                badCluster.lossCount,
                badCluster.totalCount,
                badCluster.avgR,
            )
        }
        catSummaries.filter { it.learnedTrades > 0 }.forEach { cs ->
            insightsFeed += String.format(
                Locale.US,
                "📊 نتیجه‌گیری شبکه در %s: از %d معامله تحلیل‌شده، نرخ برد %.1f%% بوده است | بهترین رژیم: %s | پرخطرترین رژیم: %s",
                cs.categoryTitleFa,
                cs.learnedTrades,
                cs.winRatePercent,
                cs.bestConditionSummaryFa,
                cs.worstNoTradeConditionSummaryFa,
            )
        }

        val recentLessons = cur.replayBuffer.takeLast(30).reversed().map { s ->
            val stratTitle = BitcoinStrategyModel.fromId(s.strategyId).titleFa
            val lessonText = if (s.won) {
                String.format(
                    Locale.US,
                    "برد %+.2fR در %s [%s]: شبکه وزن‌های مرتبط با «%s» و استراتژی «%s» را تقویت کرد.",
                    s.achievedR,
                    s.symbol,
                    s.timeframeLabel,
                    s.conditionDescriptionFa,
                    stratTitle,
                )
            } else {
                String.format(
                    Locale.US,
                    "ضرر %+.2fR در %s [%s]: شبکه ضریب هشدار «%s» را افزایش داد تا در شرایط مشابه از تله رِنج/فیک‌بریک‌اوت پرهیز شود.",
                    s.achievedR,
                    s.symbol,
                    s.timeframeLabel,
                    s.conditionDescriptionFa,
                )
            }
            NeuralRecentLesson(
                tradeId = s.tradeId,
                symbol = s.symbol,
                categoryCode = s.categoryCode,
                strategyId = s.strategyId,
                timeframeLabel = s.timeframeLabel,
                won = s.won,
                achievedR = s.achievedR,
                conditionDescriptionFa = s.conditionDescriptionFa,
                lessonLearnedFa = lessonText,
                timestampMs = s.timestampMs,
            )
        }

        // ── ۱. ماتریس برتری ترید به تفکیک ۲۴ استراتژی (Strategy × Timeframe × Symbol Edge Matrix) ──
        val samplesByStrategy = cur.replayBuffer.groupBy { it.strategyId.ifBlank { BitcoinStrategyModel.BASE_ICHIMOKU.id } }
        val strategyEdgeMatrix = BitcoinStrategyModel.entries.map { model ->
            val samples = samplesByStrategy[model.id] ?: emptyList()
            val count = samples.size
            val wins = samples.filter { it.won }
            val losses = samples.filter { !it.won }
            val wr = if (count > 0) (wins.size.toDouble() / count.toDouble()) * 100.0 else 64.0
            val grossProfitR = wins.sumOf { it.achievedR.coerceAtLeast(0.1) }
            val grossLossR = losses.sumOf { abs(it.achievedR).coerceAtLeast(0.1) }
            val pf = when {
                grossLossR > 0.0 -> round((grossProfitR / grossLossR) * 100.0) / 100.0
                grossProfitR > 0.0 -> 3.50
                else -> round((model.baseOptimalRr * 0.78) * 100.0) / 100.0
            }
            val avgWinR = if (wins.isNotEmpty()) round((grossProfitR / wins.size) * 100.0) / 100.0 else model.baseOptimalRr
            val avgLossR = if (losses.isNotEmpty()) round((grossLossR / losses.size) * 100.0) / 100.0 else 1.00
            val expR = if (count > 0) {
                round((samples.sumOf { it.achievedR } / count.toDouble()) * 100.0) / 100.0
            } else {
                round(((0.62 * model.baseOptimalRr) - 0.38) * 100.0) / 100.0
            }
            val bestTf = samples.groupBy { it.timeframeLabel }
                .maxByOrNull { (_, list) -> list.sumOf { it.achievedR } }?.key ?: "15m"
            val bestSym = samples.groupBy { it.symbol }
                .maxByOrNull { (_, list) -> list.sumOf { it.achievedR } }?.key ?: "BTC/USDT"
            val edgeScore = ((wr * 0.55) + (pf.coerceAtMost(4.0) * 11.0) + (expR.coerceIn(-1.0, 2.5) * 10.0))
                .toInt()
                .coerceIn(18, 98)
            val verdict = when {
                count == 0 -> "📊 آماده ارزیابی در رادار (نسبت هدف پایه 1:${String.format(Locale.US, "%.2f", model.baseOptimalRr)})"
                expR >= 0.45 && wr >= 55.0 -> "🟢 برتری اثبات‌شده در ترید (ستاپ با امید ریاضی مثبت قوی)"
                expR >= 0.0 -> "🟡 سودده؛ بهترین عملکرد در تایم $bestTf روی $bestSym"
                else -> "🔴 نیازمند فیلتر سخت‌گیرانه‌تر پولبک کیجون و اجتناب از رِنج"
            }
            NeuralStrategyTradingEdge(
                strategyId = model.id,
                strategyTitleFa = model.titleFa,
                strategyBadgeFa = model.badgeFa,
                isDriveBookStrategy = model.isDrivePdfStrategy,
                tradesCount = count,
                winRatePercent = round(wr * 10.0) / 10.0,
                profitFactor = pf,
                expectancyR = expR,
                avgWinR = avgWinR,
                avgLossR = avgLossR,
                bestTimeframeLabel = bestTf,
                bestSymbol = bestSym,
                edgeScore = edgeScore,
                tradingVerdictFa = verdict,
            )
        }.sortedWith(compareByDescending<NeuralStrategyTradingEdge> { it.tradesCount }.thenByDescending { it.edgeScore })

        // ── ۲. هوش سشن‌های معاملاتی (آسیا، لندن، هم‌پوشانی لندن-نیویورک، نیویورک) ──
        fun utcHourOf(ms: Long): Int = ((ms / 3_600_000L) % 24L).toInt()
        val sessionSpecs = listOf(
            Triple("OVERLAP_LDN_NY", "🔥 هم‌پوشانی لندن و نیویورک (طلایی‌ترین حجم)", "12:00 تا 16:00 UTC") to (12..15),
            Triple("LONDON", "🇬🇧 سشن لندن و اروپا (شروع روندهای اصلی)", "07:00 تا 12:00 UTC") to (7..11),
            Triple("NEW_YORK", "🇺🇸 سشن نیویورک (ادامه موج و شاخص داوجونز)", "16:00 تا 21:00 UTC") to (16..20),
            Triple("ASIA", "🇯🇵 سشن آسیا و توکیو (نوسان متعادل/پولبک)", "00:00 تا 07:00 UTC") to (0..6),
        )
        val sessionEdges = sessionSpecs.map { (meta, hourRange) ->
            val (code, titleFa, hoursLabel) = meta
            val sList = cur.replayBuffer.filter { utcHourOf(it.timestampMs) in hourRange }
            val sCount = sList.size
            val sWr = if (sCount > 0) (sList.count { it.won }.toDouble() / sCount.toDouble()) * 100.0 else when (code) {
                "OVERLAP_LDN_NY" -> 71.0
                "LONDON" -> 67.0
                "NEW_YORK" -> 64.0
                else -> 58.0
            }
            val sAvgR = if (sCount > 0) sList.sumOf { it.achievedR } / sCount.toDouble() else when (code) {
                "OVERLAP_LDN_NY" -> 1.15
                "LONDON" -> 0.92
                "NEW_YORK" -> 0.78
                else -> 0.45
            }
            val advice = when (code) {
                "OVERLAP_LDN_NY" -> "بهترین سشن برای شکست کومو (تاتل، درایو ۱ بولینگر و درایو ۸ کونیتز) روی طلا، فارکس و بیت‌کوین"
                "LONDON" -> "بهترین سشن برای موج N هوسودا و سیستم سریع 7-22-44 (درایو ۹) در شروع روند روزانه"
                "NEW_YORK" -> "بهترین سشن برای پولبک کیجون پاتل و درایو ۲ (EMA21+RSI7+Fractal) روی طلا، داوجونز و کریپتو"
                else -> "در سشن آسیا از استراتژی‌های شکست پرهیز کنید و فقط پولبک‌های دقیق کیجون و درایو ۳ (10,30,60) را شکار کنید"
            }
            NeuralTradingSessionEdge(
                sessionCode = code,
                sessionTitleFa = titleFa,
                utcHoursLabel = hoursLabel,
                tradesCount = sCount,
                winRatePercent = round(sWr * 10.0) / 10.0,
                avgR = round(sAvgR * 100.0) / 100.0,
                sessionAdviceFa = advice,
            )
        }

        // ── ۳. کالبدشکافی تریدی علل تارگت و استاپ (Trade Outcome Forensics) ──
        val allWins = cur.replayBuffer.filter { it.won }
        val allLosses = cur.replayBuffer.filter { !it.won }
        val sumWinR = allWins.sumOf { it.achievedR.coerceAtLeast(0.05) }
        val sumLossR = allLosses.sumOf { abs(it.achievedR).coerceAtLeast(0.05) }
        val overallPf = if (sumLossR > 0.0) round((sumWinR / sumLossR) * 100.0) / 100.0 else if (sumWinR > 0.0) 3.20 else 1.85
        val overallExpR = if (cur.replayBuffer.isNotEmpty()) {
            round((cur.replayBuffer.sumOf { it.achievedR } / cur.replayBuffer.size.toDouble()) * 100.0) / 100.0
        } else 0.72
        val tf15List = cur.replayBuffer.filter { it.timeframeLabel.equals("15m", ignoreCase = true) }
        val tf5List = cur.replayBuffer.filter { it.timeframeLabel.equals("5m", ignoreCase = true) }
        val tfComparisonFa = String.format(
            Locale.US,
            "تایم 15m: %d معامله (میانگین %+.2fR) · تایم 5m: %d معامله (میانگین %+.2fR)",
            tf15List.size,
            if (tf15List.isNotEmpty()) tf15List.sumOf { it.achievedR } / tf15List.size else 0.85,
            tf5List.size,
            if (tf5List.isNotEmpty()) tf5List.sumOf { it.achievedR } / tf5List.size else 0.65,
        )

        val tradeForensics = NeuralTradeForensicsSummary(
            totalWinsAnalyzed = allWins.size,
            totalLossesAnalyzed = allLosses.size,
            avgWinR = if (allWins.isNotEmpty()) round((sumWinR / allWins.size) * 100.0) / 100.0 else 2.25,
            avgLossR = if (allLosses.isNotEmpty()) round((sumLossR / allLosses.size) * 100.0) / 100.0 else 1.00,
            profitFactor = overallPf,
            expectancyPerTradeR = overallExpR,
            bestTimeframeSummaryFa = tfComparisonFa,
            topWinDriversFa = listOf(
                "۱. ورود اسنایپر در فاصله کمتر از 0.60 ATR از خط Tenkan/Kijun در فاز تولد یا قلب روند (Genesis/Core)",
                "۲. آزادی کامل سه‌بعدی Chikou Span در ۲۶ دوره قبل هم‌زمان در تایم استراتژی و ورود",
                "۳. شیب فعال Kijun-Sen همراه با ضخامت رو به رشد ابر آینده ۲۶ کندل جلوتر",
                "۴. تطابق هدف سود با امواج V/N/E هوسودا قبل از برخورد به دیوار فلت تایم بالاتر",
            ),
            topLossCausesFa = listOf(
                "۱. ورود دیرهنگام پس از باز شدن دهانه C-Clamp (فاصله قیمت از کیجون بیش از 1.65 ATR)",
                "۲. معامله در بازار رِنج زمانی که Kijun-Sen و Senkou Span B هر دو افقی (Flat) هستند",
                "۳. گیر کردن خط Chikou Span در بدنه کندل‌ها یا ابر کوموی ۲۶ دوره قبل",
                "۴. قرار دادن حد ضرر داخل نویز شدوهای کومو بدون رعایت بافر فرکتال/کیجون",
            ),
            actionableTradingRulesFa = listOf(
                "🎯 قانون ترید ۱: هرگز وقتی فاصله قیمت از Kijun بیش از 1.6 ATR است مستقیم وارد نشوید؛ منتظر پولبک به تنکان یا EMA21 بمانید.",
                "🛡️ قانون ترید ۲: در کریپتو و طلا، حد ضرر باید پشت هر دو سطح Kijun(26/30) و آخرین فرکتال ۵ کندلی قرار گیرد.",
                "⚡ قانون ترید ۳: با رسیدن سود به +1.0R معامله را ریسک‌فری کنید و از +2.0R به بعد استاپ را با خط Tenkan/Kijun (Preserve Mode) قفل نمایید.",
            ),
        )

        return NeuralLearningStateSummary(
            architectureNameFa = cur.architectureName,
            totalLearnedTrades = total,
            warmupTargetTrades = target,
            warmupProgressPercent = progressPct,
            remainingWarmupTrades = (target - total).coerceAtLeast(0),
            isWarmupCompleted = warmupDone,
            forceActiveBeforeWarmup = cur.forceActiveBeforeWarmup,
            isActiveFilterEnabled = warmupDone || cur.forceActiveBeforeWarmup,
            discoveredNoTradeClustersCount = noTradeClusters.size,
            categoryStats = catSummaries,
            runningAccuracyPct = cur.runningAccuracyPct,
            runningBceLoss = cur.runningBceLoss,
            adamStepCount = cur.adamStepCount,
            featureInsights = featInsights,
            learnedInsightsFeedFa = insightsFeed,
            winningClusters = winningClusters.take(15),
            noTradeClusters = noTradeClusters.take(15),
            recentLessons = recentLessons,
            strategyEdgeMatrix = strategyEdgeMatrix,
            sessionEdges = sessionEdges,
            tradeForensics = tradeForensics,
        )
    }

    @Synchronized
    private fun loadOrInitializeState(): NeuralNetworkPersistedState {
        val file = storageFile
        if (file != null && file.exists()) {
            val loaded = runCatching {
                json.decodeFromString(NeuralNetworkPersistedState.serializer(), file.readText())
            }.getOrNull()
            if (loaded != null &&
                loaded.gateWeights.size == INPUT_DIM * HIDDEN_DIM &&
                loaded.upWeights.size == INPUT_DIM * HIDDEN_DIM &&
                loaded.headWinWeights.size == HIDDEN_DIM
            ) {
                return loaded
            }
        }
        return createFreshInitializedState()
    }

    private fun createFreshInitializedState(): NeuralNetworkPersistedState {
        // Deterministic Xavier/He-style initialization so behavior is reproducible & stable
        val attn = List(INPUT_DIM) { idx ->
            when (idx) {
                10, 12, 13, 14 -> 0.45 // Higher initial attention on ER14, Kijun extension, Wick quality, Optimal R:R
                else -> 0.15
            }
        }
        val gateW = List(INPUT_DIM * HIDDEN_DIM) { i ->
            val r = i / HIDDEN_DIM
            val c = i % HIDDEN_DIM
            if (r == c) 0.32 else ((i * 37 % 19) - 9) * 0.015
        }
        val upW = List(INPUT_DIM * HIDDEN_DIM) { i ->
            val r = i / HIDDEN_DIM
            val c = i % HIDDEN_DIM
            if (r == c) 0.28 else ((i * 53 % 17) - 8) * 0.014
        }
        val headWinW = List(HIDDEN_DIM) { j ->
            when (j) {
                10 -> 0.42 // Trend Efficiency Ratio positive weight
                11 -> 0.25 // Kumo Thickness positive weight
                12 -> -0.35 // Overextension from Kijun negative weight
                13 -> -0.38 // Adverse wick negative weight
                14 -> 0.30 // Optimal R:R positive weight
                else -> 0.08
            }
        }
        val headRW = List(HIDDEN_DIM) { j -> headWinW[j] * 1.45 }
        val defaultCats = AssetClass.entries.associate { ac ->
            ac.code to NeuralCategoryRegimeStats(
                categoryCode = ac.code,
                categoryTitleFa = categoryTitleFa(ac),
            )
        }
        return NeuralNetworkPersistedState(
            attentionWeights = attn,
            gateWeights = gateW,
            gateBias = List(HIDDEN_DIM) { 0.0 },
            upWeights = upW,
            upBias = List(HIDDEN_DIM) { 0.0 },
            headWinWeights = headWinW,
            headWinBias = 0.12,
            headRWeights = headRW,
            headRBias = 0.40,
            mHeadWin = List(HIDDEN_DIM) { 0.0 },
            vHeadWin = List(HIDDEN_DIM) { 0.0 },
            mGate = List(INPUT_DIM * HIDDEN_DIM) { 0.0 },
            vGate = List(INPUT_DIM * HIDDEN_DIM) { 0.0 },
            categoryStats = defaultCats,
        )
    }

    @Synchronized
    private fun persistState(next: NeuralNetworkPersistedState) {
        persistedState = next
        _stateFlow.value = next
        _summaryFlow.value = buildStateSummary(next)
        val encoded = runCatching {
            json.encodeToString(NeuralNetworkPersistedState.serializer(), next)
        }.getOrNull() ?: return
        val file = storageFile
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(encoded)
            }
        }
        appContext?.let { ctx ->
            saveBrainBackupToPhoneStorage(ctx, encoded)
        }
    }

    /**
     * ذخیره خودکار حافظه کامل شبکه عصبی (`AurumNeuralBrain_Backup.json`) در حافظه گوشی (`Download/AurumEdge/Archive`)
     * تا اپ بتواند بعداً دیتای یادگرفته‌شده خود را از روی گوشی بازخوانی کند.
     */
    fun saveBrainBackupToPhoneStorage(
        context: Context,
        encodedJsonOverride: String? = null,
    ): Boolean {
        return runCatching {
            val payload = encodedJsonOverride ?: json.encodeToString(NeuralNetworkPersistedState.serializer(), persistedState)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val fileName = "AurumNeuralBrain_Backup.json"
                // حذف نسخه قبلی در صورت وجود تا فقط آخرین وضعیت ذخیره بماند
                runCatching {
                    resolver.delete(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(fileName),
                    )
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/AurumEdge/Archive",
                    )
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    /**
     * بازخوانی خودکار حافظه شبکه عصبی از حافظه گوشی (`Download/AurumEdge/Archive/AurumNeuralBrain_Backup.json`).
     */
    @Synchronized
    fun restoreBrainBackupFromPhoneStorage(context: Context): Boolean {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    android.provider.MediaStore.MediaColumns._ID,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                )
                val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("AurumNeuralBrain_Backup%")
                val sortOrder = "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                resolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val itemUri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id,
                        )
                        val raw = resolver.openInputStream(itemUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        if (!raw.isNullOrBlank()) {
                            val parsed = runCatching {
                                json.decodeFromString(NeuralNetworkPersistedState.serializer(), raw)
                            }.getOrNull()
                            if (parsed != null && parsed.totalLearnedTrades >= persistedState.totalLearnedTrades) {
                                persistState(parsed)
                                return@runCatching true
                            }
                        }
                    }
                }
            }
            false
        }.getOrDefault(false)
    }

    /**
     * همگام‌سازی اولیه با تاریخچه ژورنال در زمان شروع برنامه تا اگر معاملاتی از قبل بسته شده‌اند، شبکه عصبی آن‌ها را یاد بگیرد.
     */
    @Synchronized
    fun syncWithClosedJournalTrades(closedTrades: List<PaperTrade>) {
        val validClosed = closedTrades.filter { !it.isOpen && it.pnlUsd != null }
        if (validClosed.isEmpty()) return
        val seenIds = persistedState.replayBuffer.map { it.tradeId }.toSet()
        val unseen = validClosed.filterNot { it.id in seenIds }.sortedBy { it.closedAt ?: it.openedAt }
        if (unseen.isEmpty() && persistedState.totalLearnedTrades >= validClosed.size) return
        unseen.forEach { trade ->
            learnFromClosedTradeInternal(trade, report = null, persistImmediately = false)
        }
        persistState(persistedState)
    }

    /**
     * استخراج کامل بردار ۱۶ ویژگی و امضای شرایط برای معاملات بسته‌شده با ترکیب تمام متدهای اصلی:
     * ۱. ۴ ستون اصیل هوسودا (تعادل کومو/کیجون/چیکو، امواج I/V/N/P/S، چرخه زمان 9/17/26/33، اهداف V/N/E/NT)
     * ۲. هر ۲۴ استراتژی (۱۵ ترکیب ۴ کتاب + ۹ کتاب PDF درایو)
     * ۳. دسته‌بندی ۳ کندل قبل از ورود (`PRE3_*` و کیفیت بدنه/شدوی ۳ کندل قبل)
     * ۴. بازسازی دقیق اسنپ‌شات ایچیموکو در لحظه ورود از خزانه کندل ۱ ماهه (`PhoneStorageVaultManager`)
     */
    fun extractConditionFeaturesForClosedTrade(
        trade: PaperTrade,
        report: StrategyEvaluationReport? = null,
    ): Triple<List<Double>, String, String> {
        val vaultCandles = if (report?.triggerSnapshot == null) {
            runCatching {
                com.aurum.edge.data.PhoneStorageVaultManager.loadPersistedCandles(
                    trade.symbol,
                    trade.interval,
                    trade.openedAt,
                ).filter { it.time <= trade.openedAt }
            }.getOrDefault(emptyList())
        } else emptyList()

        val reconstructedSnap = report?.triggerSnapshot
            ?: if (vaultCandles.size >= 26) IchimokuStrategyEngine.computeSnapshot(vaultCandles) else null

        val pre3Code = trade.preEntry3CandleCategoryCode.ifBlank {
            if (vaultCandles.size >= 4) {
                IchimokuStrategyEngine.buildPreEntry3CandleContext(
                    candles = vaultCandles,
                    action = trade.action,
                    activeModel = BitcoinStrategyModel.fromId(trade.strategyId),
                ).categoryCode
            } else ""
        }

        val pre3Bonus = when {
            pre3Code.contains("MOMENTUM_LADDER", true) || pre3Code.contains("KIJUN_SPRING", true) -> 0.25
            pre3Code.contains("KUMO_SQUEEZE", true) || pre3Code.contains("ENGULFING", true) -> 0.20
            pre3Code.contains("CHOP_DOJI", true) || pre3Code.contains("CLIMAX_EXHAUSTION", true) -> -0.35
            else -> 0.0
        }

        val (baseFeatures, baseKey, baseDescFa) = extractConditionFeatures(
            symbolInput = trade.symbol,
            action = trade.action,
            interval = trade.interval,
            strategyId = trade.strategyId,
            optimalRr = trade.effectiveOptimalRr,
            confidence = trade.confidence,
            report = report,
            snapshotOverride = reconstructedSnap,
            preEntry3Bonus = pre3Bonus,
            preEntry3Code = pre3Code,
        )

        val pre3Badge = trade.preEntry3CandleCategoryFa.takeIf { it.isNotBlank() }
        val finalDesc = if (pre3Badge != null) "$baseDescFa · ۳ کندل قبل: $pre3Badge" else baseDescFa
        return Triple(baseFeatures, baseKey, finalDesc)
    }

    /**
     * استخراج بردار ۱۶ ویژگی نرمال‌شده `[-1, +1]` و امضای شرایط بازار (`conditionSignatureKey`) از معامله یا گزارش استراتژی.
     */
    fun extractConditionFeatures(
        symbolInput: String,
        action: SignalAction,
        interval: Interval,
        strategyId: String,
        optimalRr: Double,
        confidence: Double,
        report: StrategyEvaluationReport? = null,
        snapshotOverride: IchimokuSnapshot? = null,
        preEntry3Bonus: Double = 0.0,
        preEntry3Code: String = "",
    ): Triple<List<Double>, String, String> {
        val symbol = V1Universe.normalize(symbolInput)
        val assetClass = AssetClass.of(symbol)
        val model = BitcoinStrategyModel.fromId(strategyId)
        val trigSnap = report?.triggerSnapshot ?: snapshotOverride
        val stratSnap = report?.strategySnapshot ?: trigSnap

        val f0 = if (assetClass == AssetClass.CRYPTO) 1.0 else -1.0
        val f1 = if (assetClass == AssetClass.COMMODITY) 1.0 else -1.0
        val f2 = if (assetClass == AssetClass.FOREX) 1.0 else -1.0
        val f3 = if (assetClass == AssetClass.STOCK) 1.0 else -1.0
        val f4 = if (interval == Interval.M15) 1.0 else -1.0
        val f5 = if (action == SignalAction.BUY) 1.0 else -1.0
        val f6 = if (model.hasPatel || model == BitcoinStrategyModel.DRIVE_PDF_4_MTF_H4_15M_ICHIMOKU) 1.0 else -1.0
        val f7 = if (model.hasTuttle || model == BitcoinStrategyModel.DRIVE_PDF_1_BOLLINGER_KUMO_BREAKOUT || model == BitcoinStrategyModel.DRIVE_PDF_8_KUNITZ_6LINE_PROFESSIONAL) 1.0 else -1.0
        val f8 = if (model.hasHosoda || model == BitcoinStrategyModel.DRIVE_PDF_7_ICHIMOKU_8_STRATEGIES_ENCYCLOPEDIA || model == BitcoinStrategyModel.DRIVE_PDF_9_FAST_7_22_44_TREND_RIDER) 1.0 else -1.0
        val f9 = if (model.hasPeloille || model.isDrivePdfStrategy) 1.0 else -1.0

        val er14 = ((trigSnap?.efficiencyRatio14 ?: stratSnap?.efficiencyRatio14 ?: 0.42) * 2.0 - 1.0).coerceIn(-1.0, 1.0)
        val kumoAtr = if (trigSnap != null && trigSnap.atr14 > 0.0) {
            ((trigSnap.kumoThickness / trigSnap.atr14) - 0.8).coerceIn(-1.0, 1.0)
        } else {
            0.15
        }
        val distKijunAtr = if (trigSnap != null && trigSnap.atr14 > 0.0) {
            ((trigSnap.distanceToKijunAtr - 1.2) / 1.5).coerceIn(-1.0, 1.0)
        } else {
            -0.20
        }
        val adverseWick = if (trigSnap != null) {
            val w = if (action == SignalAction.BUY) trigSnap.upperWickRatio else trigSnap.lowerWickRatio
            (((w - 0.25) * 2.5) - preEntry3Bonus * 0.5).coerceIn(-1.0, 1.0)
        } else {
            (-0.15 - preEntry3Bonus * 0.5).coerceIn(-1.0, 1.0)
        }
        val rrNorm = (((optimalRr.coerceAtLeast(1.5)) - 2.0) / 1.5).coerceIn(-1.0, 1.0)
        val confNorm = (((confidence - 80.0) / 20.0) + preEntry3Bonus).coerceIn(-1.0, 1.0)

        val features = listOf(
            f0, f1, f2, f3,
            f4, f5,
            f6, f7, f8, f9,
            er14, kumoAtr, distKijunAtr, adverseWick, rrNorm, confNorm,
        )

        val phaseTag = report?.trendPhase?.name ?: "CORE_EXPANSION"
        val erBand = when {
            er14 < -0.35 -> "LOW_ER_CHOP"
            er14 > 0.20 -> "HIGH_ER_TREND"
            else -> "MID_ER"
        }
        val extBand = if (distKijunAtr > 0.35) "OVEREXTENDED_KIJUN" else "EQUILIBRIUM_OK"
        val wickBand = if (adverseWick > 0.30) "HEAVY_ADVERSE_WICK" else "CLEAN_CANDLE"
        val pre3Short = preEntry3Code.takeIf { it.isNotBlank() }?.let { "|$it" }.orEmpty()
        val signatureKey = "${assetClass.code}|${interval.label}|$phaseTag|$erBand|$extBand|$wickBand|books${model.combinationCount}$pre3Short"

        val descFa = buildString {
            append("${categoryTitleFa(assetClass)} · تایم ${interval.label}")
            append(" · ${model.badgeFa}")
            append(
                when (erBand) {
                    "LOW_ER_CHOP" -> " · کارایی روند پایین (نوسان فرسایشی)"
                    "HIGH_ER_TREND" -> " · روند پرشتاب و خالص"
                    else -> " · کارایی روند متعادل"
                }
            )
            if (extBand == "OVEREXTENDED_KIJUN") append(" · فاصله زیاد از کیجون")
            if (wickBand == "HEAVY_ADVERSE_WICK") append(" · شدوی مخالف بلند")
        }

        return Triple(features, signatureKey, descFa)
    }

    private fun swish(x: Double): Double = x / (1.0 + exp(-x.coerceIn(-20.0, 20.0)))
    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x.coerceIn(-20.0, 20.0)))

    /**
     * عبور رو به جلو (Forward Pass) در معماری `FT-Attention + SwiGLU Residual + LayerNorm`:
     * خروجی: `(winProb, expectedR, hiddenActivations, gatedFeatures)`
     */
    private fun forwardPass(features: List<Double>, cur: NeuralNetworkPersistedState = persistedState): Pair<Double, Double> {
        val (winProb, expectedR, _) = forwardPassWithInternals(features, cur)
        return winProb to expectedR
    }

    private fun forwardPassWithInternals(
        features: List<Double>,
        cur: NeuralNetworkPersistedState = persistedState,
    ): Triple<Double, Double, List<Double>> {
        // 1. Feature Self-Attention scaling (Softmax-normalized feature importance)
        val maxAttn = cur.attentionWeights.maxOrNull() ?: 0.0
        val expAttn = cur.attentionWeights.map { exp((it - maxAttn).coerceIn(-10.0, 10.0)) }
        val sumExp = expAttn.sum().coerceAtLeast(1e-8)
        val attnProbs = expAttn.map { it / sumExp }
        val attendedInput = List(INPUT_DIM) { i ->
            features.getOrElse(i) { 0.0 } * (0.65 + 0.35 * INPUT_DIM * attnProbs.getOrElse(i) { 1.0 / INPUT_DIM })
        }

        // 2. SwiGLU Gated Residual Block: h_j = x_j + Swish(x * W_gate + b_gate) * (x * W_up + b_up)
        val rawHidden = DoubleArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var gatePre = cur.gateBias.getOrElse(j) { 0.0 }
            var upPre = cur.upBias.getOrElse(j) { 0.0 }
            for (i in 0 until INPUT_DIM) {
                val xi = attendedInput[i]
                val idx = i * HIDDEN_DIM + j
                gatePre += xi * cur.gateWeights.getOrElse(idx) { 0.0 }
                upPre += xi * cur.upWeights.getOrElse(idx) { 0.0 }
            }
            val swiGlu = swish(gatePre) * upPre.coerceIn(-6.0, 6.0)
            // Residual skip connection
            rawHidden[j] = 0.55 * attendedInput.getOrElse(j) { 0.0 } + 0.45 * swiGlu
        }

        // 3. LayerNorm for numerical stability
        val mean = rawHidden.average()
        val variance = rawHidden.sumOf { (it - mean) * (it - mean) } / HIDDEN_DIM
        val invStd = 1.0 / sqrt(variance + 1e-5)
        val normHidden = List(HIDDEN_DIM) { j -> ((rawHidden[j] - mean) * invStd).coerceIn(-4.0, 4.0) }

        // 4. Dual Prediction Heads (Win Probability + Expected R-Multiple)
        var logitWin = cur.headWinBias
        var predR = cur.headRBias
        for (j in 0 until HIDDEN_DIM) {
            val hj = normHidden[j]
            logitWin += hj * cur.headWinWeights.getOrElse(j) { 0.0 }
            predR += hj * cur.headRWeights.getOrElse(j) { 0.0 }
        }
        val winProb = sigmoid(logitWin).coerceIn(0.02, 0.98)
        val clampedR = predR.coerceIn(-1.5, 4.5)
        return Triple(winProb, clampedR, normHidden)
    }

    /**
     * ارزیابی لحظه‌ای شرایط بازار توسط شبکه عصبی خودآموز:
     * - تشخیص می‌دهد شرایط فعلی در این دسته (`CRYPTO`, `COMMODITY`, `FOREX`, `STOCK`) چگونه است.
     * - تشخیص می‌دهد آیا این شرایط جزو «شرایطی است که نباید معامله کند» (`isNoTradeConditionDetected`).
     * - طبق دستور کاربر: تا قبل از رسیدن به `10,000` معامله یادگیری‌شده (`totalLearnedTrades < 10_000`)،
     *   فقط یاد می‌گیرد و هشدار/تحلیل می‌دهد (`isBlockingTradeNow = false`)، و از معامله ۱۰,۰۰۰ به بعد (یا در صورت فعال‌سازی دستی)،
     *   به‌صورت خودکار مانع ورود در شرایط پرخطر می‌شود!
     */
    @Synchronized
    fun evaluateMarketConditions(
        symbolInput: String,
        action: SignalAction,
        interval: Interval,
        strategyId: String,
        optimalRr: Double = 2.0,
        confidence: Double = 90.0,
        report: StrategyEvaluationReport? = null,
    ): NeuralMarketInference {
        val symbol = V1Universe.normalize(symbolInput)
        val assetClass = AssetClass.of(symbol)
        val (features, sigKey, condDescFa) = extractConditionFeatures(
            symbolInput = symbol,
            action = action,
            interval = interval,
            strategyId = strategyId,
            optimalRr = optimalRr,
            confidence = confidence,
            report = report,
        )

        val (neuralWinProb, neuralExpectedR) = forwardPass(features, persistedState)
        val cluster = persistedState.conditionClusters[sigKey]

        // Blend neural network output with Contextual Bandit posterior for this exact condition cluster
        val blendedWinProb = if (cluster != null && cluster.totalCount >= 2) {
            val weightCluster = (cluster.totalCount.toDouble() / (cluster.totalCount + 6.0)).coerceIn(0.15, 0.65)
            (1.0 - weightCluster) * neuralWinProb + weightCluster * cluster.posteriorWinProb
        } else {
            neuralWinProb
        }
        val blendedExpectedR = if (cluster != null && cluster.totalCount >= 2) {
            0.60 * neuralExpectedR + 0.40 * cluster.avgR
        } else {
            neuralExpectedR
        }

        val winProbPct = round(blendedWinProb * 1000.0) / 10.0
        val dangerScorePct = round(((1.0 - blendedWinProb) * 100.0).coerceIn(0.0, 100.0) * 10.0) / 10.0

        val isNoTradeDetected = blendedWinProb < 0.44 ||
            blendedExpectedR < 0.05 ||
            cluster?.isFlaggedNoTradeRegime == true

        val isWarmupComplete = persistedState.totalLearnedTrades >= persistedState.warmupTargetTrades || persistedState.forceActiveBeforeWarmup
        val isBlockingNow = isWarmupComplete && isNoTradeDetected

        val topFeatureNamesFa = listOf(
            "رژیم دسته رمزارز",
            "رژیم دسته طلا و نقره",
            "رژیم دسته فارکس",
            "رژیم دسته شاخص",
            "تایم‌فریم ورود (15m/5m)",
            "جهت خرید/فروش",
            "موتور پاتل (کتاب ۱)",
            "موتور تاتل (کتاب ۲)",
            "موتور هوسودا (کتاب ۳)",
            "موتور پلویل (کتاب ۴)",
            "کارایی جهت‌دار روند (ER14)",
            "ضخامت کومو به ATR",
            "فاصله کشسانی از کیجون",
            "کیفیت بدنه و شدوی معکوس کندل",
            "عدد مناسب R:R",
            "همگرایی ۱۶ شرط",
        )
        val topIdx = persistedState.attentionWeights.indices.maxByOrNull { i ->
            abs(persistedState.attentionWeights[i] * features.getOrElse(i) { 0.0 })
        } ?: 10
        val topAttentionFactorFa = topFeatureNamesFa.getOrElse(topIdx) { "کارایی روند" }

        val whyFa = when {
            isBlockingNow ->
                "🧠⛔ گیت شبکه عصبی (پس از ${persistedState.totalLearnedTrades} معامله): این شرایط در ${categoryTitleFa(assetClass)} پرخطر تشخیص داده شد (احتمال برد ${winProbPct}% · امید ریاضی ${String.format(Locale.US, "%+.2fR", blendedExpectedR)}) — ورود ممنوع."
            isNoTradeDetected && !isWarmupComplete ->
                "🧠⚠️ تشخیص شبکه عصبی (فاز یادگیری ${persistedState.totalLearnedTrades}/${persistedState.warmupTargetTrades}): این شرایط در ${categoryTitleFa(assetClass)} مستعد استاپ خوردن است (احتمال برد ${winProbPct}%)؛ در حال یادگیری تا ۱۰,۰۰۰ معامله."
            else ->
                "🧠✅ شرایط مساعد در ${categoryTitleFa(assetClass)} (احتمال برد شبکه عصبی: ${winProbPct}% · امید ریاضی: ${String.format(Locale.US, "%+.2fR", blendedExpectedR)} · فاکتور کلیدی: $topAttentionFactorFa)"
        }

        val progressPct = ((persistedState.totalLearnedTrades.toDouble() / persistedState.warmupTargetTrades.coerceAtLeast(1)) * 100.0)
            .coerceIn(0.0, 100.0)

        return NeuralMarketInference(
            symbol = symbol,
            categoryCode = assetClass.code,
            categoryTitleFa = categoryTitleFa(assetClass),
            timeframeLabel = interval.label,
            strategyId = strategyId,
            winProbabilityPct = winProbPct,
            expectedRMultiple = round(blendedExpectedR * 100.0) / 100.0,
            dangerScorePct = dangerScorePct,
            isNoTradeConditionDetected = isNoTradeDetected,
            isBlockingTradeNow = isBlockingNow,
            totalLearnedTrades = persistedState.totalLearnedTrades,
            warmupTargetTrades = persistedState.warmupTargetTrades,
            warmupProgressPct = round(progressPct * 100.0) / 100.0,
            currentConditionSummaryFa = condDescFa,
            whyAvoidOrAllowFa = whyFa,
            topAttentionFactorFa = topAttentionFactorFa,
        )
    }

    @Synchronized
    fun evaluateReport(report: StrategyEvaluationReport): NeuralSignalEvaluation {
        val (features, sigKey, _) = extractConditionFeatures(
            symbolInput = report.symbol,
            action = report.action,
            interval = report.triggerInterval,
            strategyId = report.strategyId,
            optimalRr = report.optimalRiskReward ?: 2.0,
            confidence = report.confidence,
            report = report,
        )
        return evaluateFeatures(
            symbol = report.symbol,
            features = features.toDoubleArray(),
            conditionKey = sigKey,
        )
    }

    @Synchronized
    fun evaluateFeatures(
        symbol: String,
        features: DoubleArray,
        conditionKey: String,
    ): NeuralSignalEvaluation {
        val normSym = V1Universe.normalize(symbol)
        val featList = features.toList()
        val (neuralWinProb, neuralExpectedR) = forwardPass(featList, persistedState)
        val cluster = persistedState.conditionClusters[conditionKey]

        val blendedWinProb = if (cluster != null && cluster.totalCount >= 2) {
            val weightCluster = (cluster.totalCount.toDouble() / (cluster.totalCount + 6.0)).coerceIn(0.15, 0.65)
            (1.0 - weightCluster) * neuralWinProb + weightCluster * cluster.posteriorWinProb
        } else {
            neuralWinProb
        }
        val blendedExpectedR = if (cluster != null && cluster.totalCount >= 2) {
            0.60 * neuralExpectedR + 0.40 * cluster.avgR
        } else {
            neuralExpectedR
        }

        val isNoTrade = blendedWinProb < 0.44 ||
            blendedExpectedR < 0.05 ||
            cluster?.isFlaggedNoTradeRegime == true
        val isWarmupDone = persistedState.totalLearnedTrades >= persistedState.warmupTargetTrades || persistedState.forceActiveBeforeWarmup
        val isBlocking = isWarmupDone && isNoTrade

        val summaryFa = "احتمال برد شبکه عصبی: ${(blendedWinProb * 100).toInt()}% · امید ریاضی: ${String.format(Locale.US, "%+.2fR", blendedExpectedR)}"
        val warningFa = if (isNoTrade) {
            "شرایط پرخطر/ممنوعه تشخیص داده شد ($summaryFa)"
        } else {
            null
        }
        return NeuralSignalEvaluation(
            symbol = normSym,
            conditionKey = conditionKey,
            predictedWinProb = blendedWinProb.coerceIn(0.01, 0.99),
            expectedR = blendedExpectedR,
            isNoTradeCondition = isNoTrade,
            isBlockingTradeNow = isBlocking,
            shouldAllowTrade = !isBlocking,
            summaryFa = summaryFa,
            noTradeWarningFa = warningFa,
        )
    }

    /**
     * آموزش آنلاین شبکه عصبی (`AdamW Backpropagation + Experience Replay`) به محض بسته شدن هر معامله در هر دسته.
     */
    @Synchronized
    fun recordCompletedTrade(
        trade: PaperTrade,
        report: StrategyEvaluationReport? = null,
        featuresOverride: DoubleArray? = null,
        conditionKeyOverride: String? = null,
    ) {
        if (trade.isOpen || trade.pnlUsd == null) return
        learnFromClosedTradeInternal(
            trade = trade,
            report = report,
            persistImmediately = true,
            featuresOverride = featuresOverride,
            conditionKeyOverride = conditionKeyOverride,
        )
    }

    @Synchronized
    fun setForceActiveBeforeWarmup(enabled: Boolean) {
        persistState(persistedState.copy(forceActiveBeforeWarmup = enabled))
    }

    private fun learnFromClosedTradeInternal(
        trade: PaperTrade,
        report: StrategyEvaluationReport?,
        persistImmediately: Boolean,
        featuresOverride: DoubleArray? = null,
        conditionKeyOverride: String? = null,
    ) {
        val pnl = trade.pnlUsd ?: return
        val won = pnl > 0.0
        val achievedR = (trade.rMultiple ?: (pnl / (trade.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0))).coerceIn(-1.5, 4.5)
        val assetClass = AssetClass.of(trade.symbol)

        val (extractedFeatures, extractedKey, condDescFa) = extractConditionFeaturesForClosedTrade(
            trade = trade,
            report = report,
        )
        val noteConditionKey = Regex("""\[NN:([^\s\]]+)""")
            .find(trade.note)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val features = featuresOverride?.toList() ?: extractedFeatures
        val sigKey = conditionKeyOverride?.takeIf { it.isNotBlank() } ?: noteConditionKey ?: extractedKey

        // تحلیل خطای پس از خروج از روی کندل‌های ذخیره‌شده ۱ ماهه در حافظه گوشی (Mistake Forensics Feedback)
        val vaultMistake = if (!won) {
            runCatching {
                com.aurum.edge.data.PhoneStorageVaultManager.analyzeLosingTradeMistakeFromVault(trade)
            }.getOrNull()
        } else null
        val enrichedCondDescFa = if (vaultMistake != null && vaultMistake.lessonLearnedFa.isNotBlank()) {
            "$condDescFa · [عیب‌یابی کندلی: ${vaultMistake.mistakeBadgeFa}]"
        } else {
            condDescFa
        }

        val sample = ReplayExperienceSample(
            tradeId = trade.id,
            categoryCode = assetClass.code,
            symbol = V1Universe.normalize(trade.symbol),
            strategyId = trade.strategyId,
            timeframeLabel = trade.interval.label,
            features = features,
            conditionKey = sigKey,
            conditionDescriptionFa = enrichedCondDescFa,
            won = won,
            achievedR = achievedR,
            timestampMs = trade.closedAt ?: System.currentTimeMillis(),
        )

        // ۱. گام AdamW + Focal-Huber روی معامله فعلی (با ضریب تقویت خطا برای استاپ‌های تحلیل‌شده در خزانه کندل)
        val primaryStepWeight = when {
            !won && vaultMistake != null -> 1.35 // وزن آموزشی بیشتر برای یادگیری سریع‌تر از اشتباهات کندلی
            won && achievedR >= 2.2 -> 1.20 // تقویت الگوهای اسنایپر بالای 2.2R
            else -> 1.0
        }
        var nextState = applyAdamWStep(persistedState, sample, lrScale = primaryStepWeight)
        val updatedBuffer = (listOf(sample) + nextState.replayBuffer.filterNot { it.tradeId == sample.tradeId })
            .take(MAX_REPLAY_BUFFER)

        // ۲. Prioritized Experience Replay (PER): انتخاب هوشمند ۴ نمونه با بیشترین خطای پیش‌بینی (TD-Error) + تنوع استراتژی/دسته
        if (updatedBuffer.size >= 4) {
            val candidates = updatedBuffer.drop(1)
            val prioritizedBatch = candidates
                .map { s ->
                    val (pw, pr) = forwardPass(s.features, nextState)
                    val targetW = if (s.won) 1.0 else 0.0
                    val tdErr = abs(pw - targetW) + 0.35 * abs(pr - s.achievedR)
                    s to tdErr
                }
                .sortedByDescending { it.second }
                .take(4)
                .map { it.first }
            for (replaySample in prioritizedBatch) {
                nextState = applyAdamWStep(nextState, replaySample, lrScale = 0.50)
            }
        }

        // 2. Update Condition Cluster Record (Contextual Bandit Memory)
        val existingCluster = nextState.conditionClusters[sigKey] ?: ConditionClusterRecord(
            signatureKey = sigKey,
            categoryCode = assetClass.code,
            conditionDescriptionFa = condDescFa,
        )
        val updatedCluster = existingCluster.copy(
            totalCount = existingCluster.totalCount + 1,
            winCount = existingCluster.winCount + if (won) 1 else 0,
            lossCount = existingCluster.lossCount + if (!won) 1 else 0,
            cumulativeR = round((existingCluster.cumulativeR + achievedR) * 100.0) / 100.0,
            lastUpdatedAt = sample.timestampMs,
        )
        val nextClusters = (nextState.conditionClusters + (sigKey to updatedCluster))
            .entries
            .sortedByDescending { it.value.lastUpdatedAt }
            .take(MAX_CONDITION_CLUSTERS)
            .associate { it.toPair() }

        // 3. Update Per-Category Regime Statistics & Discovered No-Trade Rules
        val catCode = assetClass.code
        val oldCat = nextState.categoryStats[catCode] ?: NeuralCategoryRegimeStats(
            categoryCode = catCode,
            categoryTitleFa = categoryTitleFa(assetClass),
        )
        val newCatTotal = oldCat.learnedTrades + 1
        val newCatWins = oldCat.wins + if (won) 1 else 0
        val newCatLosses = oldCat.losses + if (!won) 1 else 0
        val alphaEma = if (newCatTotal <= 10) 1.0 / newCatTotal else 0.12
        val newEmaWr = (1.0 - alphaEma) * oldCat.emaWinRatePct + alphaEma * (if (won) 100.0 else 0.0)
        val newEmaR = (1.0 - alphaEma) * oldCat.emaExpectedR + alphaEma * achievedR

        // Discover top No-Trade conditions in this category
        val catNoTradeRules = nextClusters.values
            .filter { it.categoryCode == catCode && (it.isFlaggedNoTradeRegime || (it.lossCount >= 2 && it.avgR < 0.0)) }
            .sortedBy { it.avgR }
            .take(4)
            .map { c ->
                "⛔ ${c.conditionDescriptionFa} (برد: ${(c.posteriorWinProb * 100).toInt()}% در ${c.totalCount} معامله · میانگین ${String.format(Locale.US, "%+.2fR", c.avgR)})"
            }

        val updatedCat = oldCat.copy(
            learnedTrades = newCatTotal,
            wins = newCatWins,
            losses = newCatLosses,
            emaWinRatePct = round(newEmaWr * 10.0) / 10.0,
            emaExpectedR = round(newEmaR * 100.0) / 100.0,
            discoveredNoTradeRulesFa = catNoTradeRules,
        )

        val finalState = nextState.copy(
            totalLearnedTrades = nextState.totalLearnedTrades + 1,
            replayBuffer = updatedBuffer,
            conditionClusters = nextClusters,
            categoryStats = nextState.categoryStats + (catCode to updatedCat),
        )

        if (persistImmediately) {
            persistState(finalState)
        } else {
            persistedState = finalState
            _stateFlow.value = finalState
            _summaryFlow.value = buildStateSummary(finalState)
        }
    }

    private fun applyAdamWStep(
        cur: NeuralNetworkPersistedState,
        sample: ReplayExperienceSample,
        lrScale: Double = 1.0,
    ): NeuralNetworkPersistedState {
        val (predWin, predR, normHidden) = forwardPassWithInternals(sample.features, cur)
        val targetWin = if (sample.won) 1.0 else 0.0
        val targetR = sample.achievedR.coerceIn(-1.5, 4.5)

        // Focal-modulated BCE gradient so hard false-positives (stop-loss hits) receive stronger correction
        val pt = if (sample.won) predWin else (1.0 - predWin)
        val focalWeight = (0.65 + 0.70 * Math.pow((1.0 - pt.coerceIn(0.01, 0.99)), 1.2)).coerceIn(0.65, 1.35)
        val errWin = (predWin - targetWin) * focalWeight
        // Smooth Huber gradient for R-Multiple regression
        val rawDiffR = predR - targetR
        val huberGradR = if (abs(rawDiffR) <= 1.2) rawDiffR else 1.2 * (if (rawDiffR > 0) 1.0 else -1.0)
        val errR = huberGradR * 0.38

        val step = cur.adamStepCount + 1
        // Cosine-annealed learning rate with floor
        val cosineFactor = 0.5 * (1.0 + cos(Math.PI * (step.coerceAtMost(10_000).toDouble() / 10_000.0)))
        val lr = BASE_LR * lrScale * (0.35 + 0.65 * cosineFactor)
        val biasCorrection1 = 1.0 - Math.pow(BETA1, step.toDouble()).coerceAtMost(0.9999)
        val biasCorrection2 = 1.0 - Math.pow(BETA2, step.toDouble()).coerceAtMost(0.999999)

        val newHeadWinW = cur.headWinWeights.toMutableList()
        val newHeadRW = cur.headRWeights.toMutableList()
        val newMHeadWin = cur.mHeadWin.toMutableList()
        val newVHeadWin = cur.vHeadWin.toMutableList()

        val dHidden = DoubleArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            val hj = normHidden[j]
            val gradW = errWin * hj + WEIGHT_DECAY * newHeadWinW[j]
            val mj = BETA1 * newMHeadWin[j] + (1.0 - BETA1) * gradW
            val vj = BETA2 * newVHeadWin[j] + (1.0 - BETA2) * (gradW * gradW)
            newMHeadWin[j] = mj
            newVHeadWin[j] = vj
            val mHat = mj / biasCorrection1
            val vHat = vj / biasCorrection2
            newHeadWinW[j] = (newHeadWinW[j] - lr * mHat / (sqrt(vHat) + EPS)).coerceIn(-3.5, 3.5)

            val gradRW = errR * hj + WEIGHT_DECAY * newHeadRW[j]
            newHeadRW[j] = (newHeadRW[j] - lr * 0.65 * gradRW).coerceIn(-4.0, 4.0)

            dHidden[j] = errWin * cur.headWinWeights[j] + errR * cur.headRWeights[j]
        }

        val newHeadWinBias = (cur.headWinBias - lr * errWin).coerceIn(-2.5, 2.5)
        val newHeadRBias = (cur.headRBias - lr * 0.65 * errR).coerceIn(-2.5, 2.5)

        val newGateW = cur.gateWeights.toMutableList()
        val newUpW = cur.upWeights.toMutableList()
        val newMGate = cur.mGate.toMutableList()
        val newVGate = cur.vGate.toMutableList()
        val newAttn = cur.attentionWeights.toMutableList()

        for (i in 0 until INPUT_DIM) {
            val xi = sample.features.getOrElse(i) { 0.0 }
            var featureGradAccum = 0.0
            for (j in 0 until HIDDEN_DIM) {
                val idx = i * HIDDEN_DIM + j
                val gradIj = dHidden[j] * xi * 0.45 + WEIGHT_DECAY * newGateW[idx]
                val mIj = BETA1 * newMGate[idx] + (1.0 - BETA1) * gradIj
                val vIj = BETA2 * newVGate[idx] + (1.0 - BETA2) * (gradIj * gradIj)
                newMGate[idx] = mIj
                newVGate[idx] = vIj
                val mHat = mIj / biasCorrection1
                val vHat = vIj / biasCorrection2
                newGateW[idx] = (newGateW[idx] - lr * 0.5 * mHat / (sqrt(vHat) + EPS)).coerceIn(-3.0, 3.0)
                newUpW[idx] = (newUpW[idx] - lr * 0.35 * gradIj).coerceIn(-3.0, 3.0)
                featureGradAccum += abs(dHidden[j] * newGateW[idx])
            }
            // Self-Attention weight update toward informative features
            newAttn[i] = (newAttn[i] * 0.995 + 0.005 * featureGradAccum).coerceIn(0.05, 1.50)
        }

        val bceSample = -(targetWin * ln(predWin.coerceAtLeast(1e-4)) + (1.0 - targetWin) * ln((1.0 - predWin).coerceAtLeast(1e-4)))
        val correctPred = (predWin >= 0.5 && sample.won) || (predWin < 0.5 && !sample.won)
        val newLoss = round((0.92 * cur.runningBceLoss + 0.08 * bceSample) * 1000.0) / 1000.0
        val newAcc = round((0.92 * cur.runningAccuracyPct + 0.08 * (if (correctPred) 100.0 else 0.0)) * 10.0) / 10.0

        return cur.copy(
            adamStepCount = step,
            runningBceLoss = newLoss,
            runningAccuracyPct = newAcc,
            attentionWeights = newAttn,
            gateWeights = newGateW,
            upWeights = newUpW,
            headWinWeights = newHeadWinW,
            headWinBias = newHeadWinBias,
            headRWeights = newHeadRW,
            headRBias = newHeadRBias,
            mHeadWin = newMHeadWin,
            vHeadWin = newVHeadWin,
            mGate = newMGate,
            vGate = newVGate,
        )
    }
}
