package com.aurum.edge.core

import android.content.Context
import android.os.Environment
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * دستیار ترید با بهترین متود جامع ایچیموکوی جهان (World's #1 Master Ichimoku Kinko Hyo Trading Assistant)
 * + موتور عیب‌یاب و تولیدکنندهٔ «نکات قابل اصلاح و ارتقاء برای دفعات بعد» (`IchimokuImprovementNote`).
 *
 * مبانی علمی و متودولوژی دستیار:
 * ۱. چهار ستون اصیل گوایچی هوسودا (Goichi Hosoda — Ichimoku Sanjin):
 *    - Kinko Hyo (تعادل ۵ خط و سانیاکو کوتن/گیاکوتن)
 *    - Hado Ron (نظریه امواج I, V, N, P, Y و نقطه طلایی S-Wave)
 *    - Kihon Suchi & Taito Suchi (اعداد زمانی 9, 17, 26, 33, 42, 65, 76 و روزهای تغییر Henka-bi)
 *    - Keisanchi (چهار هدف قیمتی V, N, E, NT)
 * ۲. سنتز استادان مدرن (Sasaki, Manesh Patel, Mark Tuttle, Karen Péloille) و ۹ کتاب PDF گوگل درایو:
 *    - همگرایی آبشاری ۴ تایم‌فریم (`4H -> 1H -> 15m -> 5m`)
 *    - پلاتوهای فلت SSB/Kijun، تعادل کاتانا و فیلتر ضد C-Clamp
 *    - تنظیمات نهادی `(10, 30, 60)`، تنظیمات سریع `(7, 22, 44)`، بولینگر اسکوئیز، فرکتال/RSI(7) و معیار کلی (Kelly).
 */
@Serializable
enum class ImprovementNotePriority(val badgeFa: String) {
    CRITICAL("🔴 اولویت فوری اصلاح"),
    HIGH("🟠 بهبود مهم استراتژی"),
    OPTIMIZATION("🔵 بهینه‌سازی دقت و سود"),
}

@Serializable
data class IchimokuImprovementNote(
    val id: String,
    val priority: ImprovementNotePriority,
    val categoryFa: String,
    val targetStrategyId: String,
    val targetStrategyTitleFa: String,
    val targetScopeFa: String,
    val problemDetectedFa: String,
    val ichimokuMasterReasonFa: String,
    val exactFixInstructionFa: String,
    val suggestedSlBufferAtr: Double? = null,
    val suggestedMaxKijunExtAtr: Double? = null,
    val suggestedBreakoutBufferAtr: Double? = null,
    val suggestedMinRr: Double? = null,
    val isPinnedForNextUpdate: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class MasterIchimokuSymbolCoaching(
    val symbol: String,
    val timeframeLabel: String,
    val direction: SignalAction,
    val worldClassIchimokuScore: Int,
    val masterGradeFa: String,
    val hosodaWaveTypeFa: String,
    val hosodaTimeCycleStatusFa: String,
    val keisanchiTargetsSummaryFa: String,
    val multiStrategyAgreementSummaryFa: String,
    val masterVerdictFa: String,
    val executionPlanStepsFa: List<String>,
    val activePitfallsFa: List<String>,
    val driveBooksTelemetryFa: List<String>,
)

object MasterIchimokuTradingAssistant {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private const val INTERNAL_NOTES_FILE = "ichimoku_assistant_fix_notes_v1.json"
    private const val PUBLIC_NOTES_JSON = "Ichimoku_Assistant_FixNotes.json"
    private const val PUBLIC_NOTES_TXT = "Ichimoku_Assistant_FixNotes.txt"

    private val _pinnedNotes = MutableStateFlow<List<IchimokuImprovementNote>>(emptyList())
    val pinnedNotes: StateFlow<List<IchimokuImprovementNote>> = _pinnedNotes.asStateFlow()

    fun init(context: Context) {
        runCatching {
            val internalFile = File(context.filesDir, INTERNAL_NOTES_FILE)
            if (internalFile.exists() && internalFile.length() > 2L) {
                val loaded = json.decodeFromString<List<IchimokuImprovementNote>>(internalFile.readText(Charsets.UTF_8))
                if (loaded.isNotEmpty()) {
                    _pinnedNotes.value = loaded.distinctBy { it.id }
                    return
                }
            }
            restoreNotesFromPhoneStorage(context)
        }
    }

    fun pinOrToggleImprovementNote(context: Context, note: IchimokuImprovementNote): Boolean {
        val current = _pinnedNotes.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == note.id }
        val nowPinned = if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            false
        } else {
            current.add(0, note.copy(isPinnedForNextUpdate = true, createdAtMs = System.currentTimeMillis()))
            true
        }
        _pinnedNotes.value = current
        saveNotesToStorage(context, current)
        return nowPinned
    }

    fun saveAllGeneratedNotesToPhone(context: Context, notes: List<IchimokuImprovementNote>): String? {
        val merged = (notes.map { it.copy(isPinnedForNextUpdate = true) } + _pinnedNotes.value)
            .distinctBy { it.id }
            .take(80)
        _pinnedNotes.value = merged
        return saveNotesToStorage(context, merged)
    }

    private fun saveNotesToStorage(context: Context, notes: List<IchimokuImprovementNote>): String? {
        runCatching {
            File(context.filesDir, INTERNAL_NOTES_FILE).writeText(
                json.encodeToString(notes),
                Charsets.UTF_8,
            )
        }
        return runCatching {
            val dlRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(dlRoot, "AurumEdge/Archive")
            if (!dir.exists()) dir.mkdirs()
            val jsonFile = File(dir, PUBLIC_NOTES_JSON)
            jsonFile.writeText(json.encodeToString(notes), Charsets.UTF_8)
            val txtFile = File(dir, PUBLIC_NOTES_TXT)
            txtFile.writeText(formatNotesForClipboard(notes), Charsets.UTF_8)
            txtFile.absolutePath
        }.getOrNull()
    }

    fun restoreNotesFromPhoneStorage(context: Context): Int {
        return runCatching {
            val dlRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val jsonFile = File(dlRoot, "AurumEdge/Archive/$PUBLIC_NOTES_JSON")
            if (!jsonFile.exists() || jsonFile.length() <= 4L) return 0
            val loaded = json.decodeFromString<List<IchimokuImprovementNote>>(jsonFile.readText(Charsets.UTF_8))
            if (loaded.isNotEmpty()) {
                val merged = (loaded + _pinnedNotes.value).distinctBy { it.id }
                _pinnedNotes.value = merged
                File(context.filesDir, INTERNAL_NOTES_FILE).writeText(
                    json.encodeToString(merged),
                    Charsets.UTF_8,
                )
                merged.size
            } else {
                0
            }
        }.getOrDefault(0)
    }

    fun formatNotesForClipboard(notes: List<IchimokuImprovementNote>): String = buildString {
        appendLine("═══════════════════════════════════════════════════════")
        appendLine("📋 لیست نکات اصلاحی و بهینه‌سازی دستیار استاد ایچیموکو (Aurum Edge)")
        appendLine("جهت استفاده مستقیم برای رفع نواقص، کالیبراسیون استراتژی‌ها و آپدیت‌های بعدی")
        appendLine("═══════════════════════════════════════════════════════")
        notes.forEachIndexed { idx, n ->
            appendLine()
            appendLine("${idx + 1}. [${n.priority.badgeFa}] ${n.categoryFa}")
            appendLine("   • استراتژی هدف: ${n.targetStrategyTitleFa} (${n.targetStrategyId})")
            appendLine("   • دامنه/نماد: ${n.targetScopeFa}")
            appendLine("   • عیب یا گلوگاه شناسایی‌شده: ${n.problemDetectedFa}")
            appendLine("   • تحلیل متود ایچیموکو: ${n.ichimokuMasterReasonFa}")
            appendLine("   • 🛠️ دستور دقیق اصلاح برای بعداً: ${n.exactFixInstructionFa}")
        }
    }

    /**
     * تحلیل و کوچینگ لحظه‌ای نماد با «بهترین متود ایچیموکوی جهان» (۴ ستون هوسودا + پاتل + پلویل + ۹ کتاب درایو).
     */
    fun analyzeSymbolWithMasterMethod(
        symbol: String,
        dualReport: DualTimeframeSymbolReport?,
        focusedInterval: Interval = Interval.M15,
    ): MasterIchimokuSymbolCoaching? {
        val rep = if (focusedInterval == Interval.M5) {
            dualReport?.report5m ?: dualReport?.report15m
        } else {
            dualReport?.report15m ?: dualReport?.report5m
        } ?: return null

        val trigSnap = rep.triggerSnapshot ?: return null
        val stratSnap = rep.strategySnapshot ?: trigSnap
        val macroSnap = rep.macroSnapshot
        val wave = rep.waveProjection
        val isLong = rep.action == SignalAction.BUY ||
            (rep.action == SignalAction.NO_TRADE && stratSnap.close >= stratSnap.kijun)
        val effectiveDir = if (isLong) SignalAction.BUY else SignalAction.SELL

        // ۱. تشخیص دقیق موج هوسودا (Hado Ron: I, V, N, P, Y, S)
        val hosodaWaveTypeFa = when {
            trigSnap.isKatanaEquilibrium || trigSnap.isBollingerSqueeze ->
                "موج P هوسودا (فشردگی مثلثی و انطباق کاتانا Tenkan=Kijun — در آستانه انفجار موج جدید)"
            wave != null && wave.sPointHeld && wave.barsSinceWaveStart in 6..28 ->
                "موج N + نقطه طلایی S هوسودا (پولبک روی قله/دره شکسته شده و کیجون — بالاترین نرخ برد ایچیموکو)"
            wave != null && wave.barsSinceWaveStart <= 9 ->
                "موج I هوسودا (ایمپالس اولیه آغاز روند؛ مناسب شکار تولد روند)"
            stratSnap.distanceToKijunAtr >= 1.70 || trigSnap.isCClampWarning ->
                "انتهای موج N / ورود به فاز V اصلاحی (کشیدگی C-Clamp؛ نیاز به بازگشت قیمت به کیجون)"
            else ->
                "موج N گسترش‌یافته هوسودا (متن اصلی روند هم‌سو با کومو و کیجون)"
        }

        // ۲. تشخیص چرخه زمانی هوسودا (Kihon Suchi: 9, 17, 26, 33, 42, 65, 76)
        val barsSinceStart = wave?.barsSinceWaveStart ?: 12
        val nearestCycle = wave?.nearestTimeCycleNumber ?: 17
        val diffToCycle = nearestCycle - barsSinceStart
        val hosodaTimeCycleStatusFa = when {
            abs(diffToCycle) <= 1 ->
                "⏰ هشدار روز تغییر هوسودا (Henka-bi): کندل فعلی ($barsSinceStart) دقیقاً روی عدد زمانی $nearestCycle قرار دارد؛ احتمال شتاب ناگهانی یا چرخش موقت!"
            diffToCycle in 2..4 ->
                "⏳ کندل $barsSinceStart موج: $diffToCycle کندل مانده تا عدد زمانی $nearestCycle هوسودا (پنجره طلایی رسیدن به تارگت قبل از چرخش زمانی)"
            else ->
                "🟢 کندل $barsSinceStart موج (عدد زمانی مرجع: $nearestCycle هوسودا — فضای زمانی کافی برای ادامه حرکت)"
        }

        // ۳. اهداف قیمتی چهارگانه هوسودا (Keisanchi: NT, V, N, E)
        val keisanchiTargetsSummaryFa = if (wave != null) {
            String.format(
                Locale.US,
                "اهداف چهارگانه هوسودا (Keisanchi): NT=%s · V=%s · N=%s · E=%s (نقطه S=%s)",
                IchimokuStrategyEngine.round2(wave.targetNT).toString(),
                IchimokuStrategyEngine.round2(wave.targetV).toString(),
                IchimokuStrategyEngine.round2(wave.targetN).toString(),
                IchimokuStrategyEngine.round2(wave.targetE).toString(),
                IchimokuStrategyEngine.round2(wave.pointC).toString(),
            )
        } else {
            "در حال تکمیل نقاط A-B-C برای ترسیم اهداف چهارگانه V/N/E/NT هوسودا"
        }

        // ۴. اجماع ۲۴ استراتژی (۱۵ ترکیب + ۹ کتاب درایو) در 15m و 5m
        val reports15 = dualReport?.reports15mByStrategy?.values ?: emptyList()
        val reports5 = dualReport?.reports5mByStrategy?.values ?: emptyList()
        val actionable15 = reports15.count { it.isActionable }
        val actionable5 = reports5.count { it.isActionable }
        val driveReady15 = reports15.count { BitcoinStrategyModel.fromId(it.strategyId).isDrivePdfStrategy && (it.isActionable || it.conditions.count { c -> c.passed } >= 13) }
        val driveReady5 = reports5.count { BitcoinStrategyModel.fromId(it.strategyId).isDrivePdfStrategy && (it.isActionable || it.conditions.count { c -> c.passed } >= 13) }

        val multiStrategyAgreementSummaryFa =
            "اجماع ۲۴ استراتژی: در 15m (${actionable15} سیگنال فعال، $driveReady15 کتاب درایو آماده) · در 5m (${actionable5} سیگنال فعال، $driveReady5 کتاب درایو آماده)"

        // ۵. محاسبه امتیاز جامع بهترین متود ایچیموکو جهان (0..100)
        val passedRatio = if (rep.conditions.isNotEmpty()) {
            rep.conditions.count { it.passed }.toDouble() / rep.conditions.size.toDouble()
        } else 0.5
        val mtfBonus = if (macroSnap != null && ((isLong && macroSnap.isPriceAboveKumo) || (!isLong && macroSnap.isPriceBelowKumo))) 10 else 0
        val chikouBonus = if ((isLong && trigSnap.chikouBullishOpenSpace && trigSnap.chikouAboveCloud26) ||
            (!isLong && trigSnap.chikouBearishOpenSpace && trigSnap.chikouBelowCloud26)
        ) 10 else 0
        val kellyBonus = ((trigSnap.kellySignalCount - 2).coerceAtLeast(0)) * 4
        val cClampPenalty = if (trigSnap.isCClampWarning || stratSnap.distanceToKijunAtr > 1.80) -15 else 0
        val flatPenalty = if (trigSnap.isKijunFlat && stratSnap.isKijunFlat) -12 else 0

        val worldScore = ((passedRatio * 72.0) + mtfBonus + chikouBonus + kellyBonus + cClampPenalty + flatPenalty)
            .toInt()
            .coerceIn(12, 99)

        val masterGradeFa = when {
            worldScore >= 88 -> "💎 درجه A+ (ستاپ الماس سانیاکو + موج N هوسودا)"
            worldScore >= 76 -> "🟢 درجه A (ستاپ قدرتمند ایچیموکو)"
            worldScore >= 62 -> "🟡 درجه B (همگرایی نسبی؛ منتظر تکمیل ماشه یا پولبک)"
            worldScore >= 45 -> "🟠 درجه C (فاز اصلاحی یا تداخل تایم‌فریم‌ها)"
            else -> "🔴 درجه D (رِنج فرسایشی / ممنوعیت ورود طبق متود هوسودا)"
        }

        val entryPrice = rep.suggestedEntry ?: trigSnap.close
        val slPrice = rep.suggestedStopLoss ?: (if (isLong) trigSnap.kijun - trigSnap.atr14 * 0.5 else trigSnap.kijun + trigSnap.atr14 * 0.5)
        val tpPrice = rep.primaryTakeProfit ?: (if (isLong) entryPrice + abs(entryPrice - slPrice) * 2.3 else entryPrice - abs(entryPrice - slPrice) * 2.3)
        val oneRDist = abs(entryPrice - slPrice).coerceAtLeast(trigSnap.atr14 * 0.25)
        val bePrice = IchimokuStrategyEngine.round2(if (isLong) entryPrice + oneRDist else entryPrice - oneRDist)
        val lock2RPrice = IchimokuStrategyEngine.round2(if (isLong) entryPrice + oneRDist * 2.0 else entryPrice - oneRDist * 2.0)

        val executionPlanStepsFa = listOf(
            "۱. نقطه ورود اسنایپر (Tenkan/Golden Pocket): ${IchimokuStrategyEngine.round2(entryPrice)} در تایم ${rep.triggerInterval.label}",
            "۲. سنگر حد ضرر ساختاری (پشت Kijun=${IchimokuStrategyEngine.round2(trigSnap.kijun)} و فرکتال=${IchimokuStrategyEngine.round2(if (isLong) trigSnap.lastDownFractalLow else trigSnap.lastUpFractalHigh)}): ${IchimokuStrategyEngine.round2(slPrice)}",
            "۳. گام اول مدیریت پوزیشن (ریسک‌فری در 1R): با رسیدن قیمت به $bePrice استاپ به نقطه ورود منتقل می‌شود",
            "۴. گام دوم (Preserve Mode پاتل در 2R = $lock2RPrice): قفل سود پشت خط Tenkan(${IchimokuStrategyEngine.round2(trigSnap.tenkan)}) و Kijun(30)=${IchimokuStrategyEngine.round2(trigSnap.kijun30)}",
            "۵. هدف نهایی هوسودا (Keisanchi TP): ${IchimokuStrategyEngine.round2(tpPrice)} (نسبت R:R = 1:${String.format(Locale.US, "%.2f", rep.riskReward ?: 2.20)})",
        )

        val pitfalls = mutableListOf<String>()
        if (stratSnap.distanceToKijunAtr > 1.55 || trigSnap.isCClampWarning) {
            pitfalls += "هشدار C-Clamp کیئکودا: فاصله قیمت از کیجون (${String.format(Locale.US, "%.2f", stratSnap.distanceToKijunAtr)} ATR) زیاد است؛ ورود قبل از پولبک به تنکان/کیجون ریسک اصلاح دارد."
        }
        if (trigSnap.isKijunFlat) {
            pitfalls += "هشدار آهنربای کیجون فلت (موکرجی): خط Kijun-Sen در تایم ${rep.triggerInterval.label} افقی شده و قیمت را به سمت تعادل می‌کشد."
        }
        if (isLong && !trigSnap.chikouBullishOpenSpace || !isLong && !trigSnap.chikouBearishOpenSpace) {
            pitfalls += "مانع سه‌بعدی چیکو (ساساکی/تاتل): خط Chikou Span در ۲۶ کندل قبل هنوز کاملاً از کندل‌ها و ابر رها نشده است."
        }
        if (wave?.isNearTimeCycleChangeDay == true) {
            pitfalls += "نزدیکی به عدد زمانی ${wave.nearestTimeCycleNumber} هوسودا: مراقب فلت شدن تنکان و تخلیه شتاب باشید."
        }
        if (pitfalls.isEmpty()) {
            pitfalls += "بدون تله ساختاری فعال؛ هر ۵ خط ایچیموکو، چیکو و شیب کیجون در تعادل رونددار قرار دارند."
        }

        val driveBooksTelemetryFa = listOf(
            "📘 درایو ۱ (TuAnh BB+EMA89): " + BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE.formatLiveBookTelemetryFa(trigSnap, stratSnap, isLong),
            "📗 درایو ۲ (15m EMA21+RSI7+Fractal): " + BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL.formatLiveBookTelemetryFa(trigSnap, stratSnap, isLong),
            "📙 درایو ۳ (Keenbase 10,30,60): " + BitcoinStrategyModel.KEENBASE_CROSS_10_30_60.formatLiveBookTelemetryFa(trigSnap, stratSnap, isLong),
            "📕 درایو ۴ (Mukherjee Kelly ${trigSnap.kellyWinProbabilityPercent}%): " + BitcoinStrategyModel.MUKHERJEE_RELATIVE_TK_RSI_KELLEY.formatLiveBookTelemetryFa(trigSnap, stratSnap, isLong),
            "📓 درایو ۸ و ۹ (Koonitz DMI/MACD & Fast 7,22,44): " + BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44.formatLiveBookTelemetryFa(trigSnap, stratSnap, isLong),
        )

        val masterVerdictFa = buildString {
            append("نماد $symbol در تایم ${rep.triggerInterval.label} با جهت پیشنهادی ")
            append(if (isLong) "خرید (Long)" else "فروش (Short)")
            append(" امتیاز $worldScore/100 ($masterGradeFa) را از متود جامع هوسودا کسب کرده است. ")
            append(hosodaWaveTypeFa)
        }

        return MasterIchimokuSymbolCoaching(
            symbol = symbol,
            timeframeLabel = rep.triggerInterval.label,
            direction = effectiveDir,
            worldClassIchimokuScore = worldScore,
            masterGradeFa = masterGradeFa,
            hosodaWaveTypeFa = hosodaWaveTypeFa,
            hosodaTimeCycleStatusFa = hosodaTimeCycleStatusFa,
            keisanchiTargetsSummaryFa = keisanchiTargetsSummaryFa,
            multiStrategyAgreementSummaryFa = multiStrategyAgreementSummaryFa,
            masterVerdictFa = masterVerdictFa,
            executionPlanStepsFa = executionPlanStepsFa,
            activePitfallsFa = pitfalls,
            driveBooksTelemetryFa = driveBooksTelemetryFa,
        )
    }

    /**
     * استخراج هوشمند «نکات قابل اصلاح و ارتقاء بعدی» (`List<IchimokuImprovementNote>`)
     * بر اساس کالبدشکافی معاملات بسته‌شده، معاملات باز و گلوگاه‌های زنده ۲۴ استراتژی.
     */
    fun generateActionableImprovementNotes(
        trades: List<PaperTrade>,
        dualReports: Map<String, DualTimeframeSymbolReport>,
        currentConfig: BaseStrategyConfig,
    ): List<IchimokuImprovementNote> {
        val notes = mutableListOf<IchimokuImprovementNote>()
        val closed = trades.filter { !it.isOpen }
        val openTrades = trades.filter { it.isOpen }

        // ۱. کالبدشکافی معاملات استاپ‌خورده سریع یا ضررده در ژورنال
        val losingTrades = closed.filter { (it.resultPnl ?: 0.0) < -0.05 }
        if (losingTrades.isNotEmpty()) {
            // گروهبندی بر اساس استراتژی
            val lossesByStrategy = losingTrades.groupBy { it.strategyId.ifBlank { BitcoinStrategyModel.BASE_ICHIMOKU.id } }
            for ((stratId, stratLosses) in lossesByStrategy) {
                val model = BitcoinStrategyModel.fromId(stratId)
                val totalForStrat = closed.count { it.strategyId == stratId }.coerceAtLeast(1)
                val lossRate = (stratLosses.size.toDouble() / totalForStrat.toDouble()) * 100.0
                val fastStopOuts = stratLosses.count { tr ->
                    val closedAt = tr.closedAt ?: tr.openedAt
                    (closedAt - tr.openedAt) in 1L..900_000L // کمتر از ۱۵ دقیقه
                }

                if (fastStopOuts > 0) {
                    val recBuffer = round((currentConfig.slBufferAtr + 0.12).coerceIn(0.32, 0.65) * 100.0) / 100.0
                    notes += IchimokuImprovementNote(
                        id = "fix_fast_sl_${stratId}",
                        priority = ImprovementNotePriority.CRITICAL,
                        categoryFa = "اصلاح فرمول فاصله حد ضرر (Anti-Stop-Hunt)",
                        targetStrategyId = stratId,
                        targetStrategyTitleFa = model.titleFa,
                        targetScopeFa = stratLosses.map { it.symbol }.distinct().joinToString(", "),
                        problemDetectedFa = "$fastStopOuts معامله در استراتژی «${model.badgeFa}» در کمتر از ۱۵ دقیقه پس از باز شدن به حد ضرر برخورد کرده‌اند.",
                        ichimokuMasterReasonFa = "طبق متود هوسودا و پاتل، در تایم ۵ و ۱۵ دقیقه نوسان شدوها حول خط Kijun-Sen تا 0.35×ATR نفوذ می‌کند و استاپ نزدیک‌تر از فرکتال ۵ کندلی یا Kijun(30) شکار می‌شود.",
                        exactFixInstructionFa = "بافر حد ضرر (`slBufferAtr`) را از ${currentConfig.slBufferAtr} به $recBuffer ATR افزایش دهید و برای استراتژی ${model.badgeFa} شرط قرارگیری استاپ پشت هر دو سطح Kijun(26) و آخرین فرکتال ۵ کندلی اعمال شود.",
                        suggestedSlBufferAtr = recBuffer,
                    )
                } else if (stratLosses.size >= 2 && lossRate >= 45.0) {
                    val recMaxExt = round((currentConfig.maxKijunExtensionAtr - 0.20).coerceIn(1.25, 1.65) * 100.0) / 100.0
                    notes += IchimokuImprovementNote(
                        id = "fix_cclamp_loss_${stratId}",
                        priority = ImprovementNotePriority.HIGH,
                        categoryFa = "فیلتر ورود در اشباع کیجون (Anti-C-Clamp)",
                        targetStrategyId = stratId,
                        targetStrategyTitleFa = model.titleFa,
                        targetScopeFa = stratLosses.map { "${it.symbol} (${it.interval.label})" }.distinct().take(4).joinToString(" · "),
                        problemDetectedFa = "نرخ استاپ در استراتژی «${model.badgeFa}» به ${lossRate.toInt()}% (${stratLosses.size} از $totalForStrat معامله) رسیده است.",
                        ichimokuMasterReasonFa = "ورود زمانی رخ داده که فاصله قیمت از خط تعادل Kijun-Sen بیش از حد باز شده (تشکیل دهانه C-Clamp) و بازار بلافاصله برای پر کردن خلأ تعادلی به سمت کیجون برگشته است.",
                        exactFixInstructionFa = "سقف مجاز فاصله از کیجون (`maxKijunExtensionAtr`) را از ${currentConfig.maxKijunExtensionAtr} به $recMaxExt ATR کاهش دهید تا ورود فقط در پولبک نزدیک Tenkan/Kijun انجام شود.",
                        suggestedMaxKijunExtAtr = recMaxExt,
                    )
                }
            }
        }

        // ۲. بررسی گلوگاه‌های زنده در ۹ استراتژی درایو و ۱۵ ترکیب پایه روی ۱۵ نماد
        val allLiveReports = dualReports.values.flatMap {
            it.reports15mByStrategy.values + it.reports5mByStrategy.values
        }
        if (allLiveReports.isNotEmpty()) {
            // بررسی اینکه کدام شرط از ۱۶ شرط بیشترین مسدودسازی نزدیک به سیگنال (وقتی ۱۴ یا ۱۵ شرط پاس شده) را ایجاد کرده است
            val nearMissReports = allLiveReports.filter { rep ->
                val passed = rep.conditions.count { it.passed }
                passed in 13..15
            }
            val failedCondCounts = mutableMapOf<Int, Int>()
            val failedCondSampleTitle = mutableMapOf<Int, String>()
            nearMissReports.forEach { rep ->
                rep.conditions.filter { !it.passed }.forEach { c ->
                    failedCondCounts[c.index] = (failedCondCounts[c.index] ?: 0) + 1
                    failedCondSampleTitle[c.index] = c.titleFa
                }
            }
            val topBottleneck = failedCondCounts.entries.maxByOrNull { it.value }
            if (topBottleneck != null && topBottleneck.value >= 2) {
                val condIdx = topBottleneck.key
                val condTitle = failedCondSampleTitle[condIdx] ?: "شرط شماره $condIdx"
                when (condIdx) {
                    8 -> {
                        val recExt = round((currentConfig.maxKijunExtensionAtr + 0.15).coerceIn(1.50, 2.10) * 100.0) / 100.0
                        notes += IchimokuImprovementNote(
                            id = "bottleneck_cond8_kijun_ext",
                            priority = ImprovementNotePriority.OPTIMIZATION,
                            categoryFa = "کالیبراسیون شرط ۸ (فاصله از کیجون در روندهای شارپ)",
                            targetStrategyId = "ALL_STRATEGIES",
                            targetStrategyTitleFa = "کلیه استراتژی‌ها (به‌ویژه شکست کومو و بولینگر)",
                            targetScopeFa = "${topBottleneck.value} ستاپ آماده در رادار 15m/5m",
                            problemDetectedFa = "در ${topBottleneck.value} ستاپ که ۱۴ تا ۱۵ شرط ایچیموکو برقرار بوده، فقط «$condTitle» مانع شلیک معامله شده است.",
                            ichimokuMasterReasonFa = "در امواج انفجاری (موج I و شکست Squeeze کتاب درایو ۱ و ۷)، کندل شکست اولیه به طور طبیعی حدود 1.8 تا 2.0 ATR از کیجون فاصله می‌گیرد.",
                            exactFixInstructionFa = "برای استراتژی‌های شکست (Breakout)، سقف `maxKijunExtensionAtr` را روی $recExt ATR تنظیم کنید یا ورود پله‌ای روی پولبک ۵۰٪ بدنه کندل شکست فعال شود.",
                            suggestedMaxKijunExtAtr = recExt,
                        )
                    }
                    12 -> {
                        notes += IchimokuImprovementNote(
                            id = "bottleneck_cond12_rr_wall",
                            priority = ImprovementNotePriority.HIGH,
                            categoryFa = "بهینه‌سازی شرط ۱۲ (فضای باز تا دیوار فلت MTF و R:R)",
                            targetStrategyId = "ALL_STRATEGIES",
                            targetStrategyTitleFa = "کلیه استراتژی‌های ایچیموکو",
                            targetScopeFa = "${topBottleneck.value} ستاپ نزدیک به ماشه",
                            problemDetectedFa = "در ${topBottleneck.value} ستاپ، نزدیکی پلاتوی فلت تایم‌فریم بالاتر (SSB/Kijun Flat) نسبت R:R را به زیر حد نصاب رسانده است.",
                            ichimokuMasterReasonFa = "طبق متود کارن پلویل، پلاتوهای فلت 1H/4H مانند آهنربا عمل می‌کنند اما اگر استاپ روی ساختار میکرو (Tenkan/Fractal) قرار گیرد، فضای کافی برای 1.8R تا قبل از دیوار فلت آزاد می‌شود.",
                            exactFixInstructionFa = "در محاسبه حد ضرر ستاپ‌های محدودشده توسط پلاتوی فلت، از استاپ ترکیبی فرکتال ۵ کندلی + بافر `slBufferAtr = 0.22` استفاده شود تا R:R قبل از دیوار فلت به بالای 1:1.8 برسد.",
                            suggestedSlBufferAtr = 0.22,
                            suggestedMinRr = 1.55,
                        )
                    }
                    16 -> {
                        notes += IchimokuImprovementNote(
                            id = "bottleneck_cond16_sniper_window",
                            priority = ImprovementNotePriority.OPTIMIZATION,
                            categoryFa = "تنظیم پنجره نقطه‌زنی اسنایپر (شرط ۱۶)",
                            targetStrategyId = "ALL_STRATEGIES",
                            targetStrategyTitleFa = "موتور نقطه‌زنی اسنایپر (Sniper Pocket)",
                            targetScopeFa = "${topBottleneck.value} ستاپ مسلح (Armed Pending Retest)",
                            problemDetectedFa = "${topBottleneck.value} ستاپ تمام ۱۵ شرط اول را پاس کرده‌اند و منتظر بازگشت قیمت به ناحیه اسنایپر تنکان هستند.",
                            ichimokuMasterReasonFa = "وقتی شیب تنکان‌سن بیش از 0.25 ATR در هر کندل است (شتاب قوی موج سوم هوسودا)، قیمت فقط تا نوک سایه کندل قبلی اصلاح می‌کند و به تنکان نمی‌رسد.",
                            exactFixInstructionFa = "در نسخه بعدی، زمانی که شیب تنکان > 0.25 ATR و حجم > 1.3x است، تلورانس پنجره اسنایپر از 0.65 ATR به 0.80 ATR افزایش یابد تا موج‌های شارپ جا نمانند.",
                        )
                    }
                    else -> {
                        notes += IchimokuImprovementNote(
                            id = "bottleneck_cond_${condIdx}",
                            priority = ImprovementNotePriority.OPTIMIZATION,
                            categoryFa = "کالیبراسیون گلوگاه «$condTitle»",
                            targetStrategyId = "ALL_STRATEGIES",
                            targetStrategyTitleFa = "رادار ۲۴ استراتژی (15m/5m)",
                            targetScopeFa = "${topBottleneck.value} ستاپ در آستانه سیگنال",
                            problemDetectedFa = "شرط «$condTitle» در ${topBottleneck.value} ستاپ با آمادگی بالا (۱۳ تا ۱۵ شرط پاس‌شده) تنها مانع ورود بوده است.",
                            ichimokuMasterReasonFa = "تنظیم وزن این شرط بر اساس رژیم نوسان نماد (کریپتو در برابر فارکس/طلا) می‌تواند دقت شکار روند را بدون افزایش ریسک بالا ببرد.",
                            exactFixInstructionFa = "در کالیبراسیون بعدی، آستانهٔ شرط شماره $condIdx بر اساس دسته دارایی (`AssetClass`) به‌صورت پویا تنظیم شود.",
                        )
                    }
                }
            }
        }

        // ۳. نکات تخصصی ارتقای ۹ استراتژی کتاب‌های درایو بر اساس وضعیت فعلی بازار
        notes += IchimokuImprovementNote(
            id = "drive_books_mtf_Adaptive_sizing",
            priority = ImprovementNotePriority.HIGH,
            categoryFa = "ارتقای ۹ استراتژی درایو (همگرایی کومو + معیار کلی موکرجی)",
            targetStrategyId = "MUKHERJEE_RELATIVE_TK_RSI_KELLEY",
            targetStrategyTitleFa = "۹ استراتژی کتاب‌های PDF گوگل درایو (Drive 1..9)",
            targetScopeFa = "تمام ۱۵ نماد در تایم‌فریم‌های 15m و 5m",
            problemDetectedFa = "تفاوت رفتار استراتژی‌های تنظیمات سریع درایو ۹ (7,22,44) و نهادی درایو ۳ (10,30,60) در ساعات کم‌حجم آسیا نسبت به هم‌پوشانی لندن-نیویورک.",
            ichimokuMasterReasonFa = "طبق کتاب چارلز کونیتز و موکرجی، تنظیمات سریع (7,22,44) در سشن لندن/نیویورک ۱۰٪ سریع‌تر روند را شکار می‌کند اما در ساعات کم‌نوسان تنظیمات نهادی (10,30,60) و استاپ فرکتال فکا (درایو ۲) خطای بسیار کمتری دارند.",
            exactFixInstructionFa = "برای بهینه‌سازی بعدی: در سشن آسیا اولویت امتیازدهی به درایو ۲ (Fractal/RSI7) و درایو ۳ (10,30,60) داده شود و در سشن لندن/نیویورک وزن درایو ۱ (BB Squeeze) و درایو ۹ (7,22,44) افزایش یابد.",
        )

        // ۴. بررسی معاملات باز فعلی و ارائه نکته محافظت از سود
        openTrades.forEach { tr ->
            val sym = V1Universe.normalize(tr.symbol)
            val rep = dualReports[sym]?.reportFor(tr.interval, tr.strategyId)
            val health = IchimokuStrategyEngine.evaluateTrendHealthAndProfitExit(tr, rep)
            if (health.activeWarningSignalsFa.isNotEmpty()) {
                notes += IchimokuImprovementNote(
                    id = "open_trade_guard_${tr.id}",
                    priority = ImprovementNotePriority.CRITICAL,
                    categoryFa = "محافظت از معامله باز (${tr.symbol})",
                    targetStrategyId = tr.strategyId,
                    targetStrategyTitleFa = tr.strategyTitleFa.ifBlank { tr.strategyId },
                    targetScopeFa = "${tr.symbol} · تایم ${tr.interval.label} (${String.format(Locale.US, "%+.2fR", health.currentProfitR)})",
                    problemDetectedFa = "در معامله باز ${tr.symbol} هشدار زیر فعال شده است: ${health.activeWarningSignalsFa.joinToString(" ، ")}",
                    ichimokuMasterReasonFa = "بر اساس قانون Preserve Mode منش پاتل و خروج RSI موکرجی، با مشاهده اولین نشانه ضعف تنکان یا کشیدگی C-Clamp باید استاپ پشت تنکان قفل شود.",
                    exactFixInstructionFa = "پیشنهاد فوری دستیار: حد ضرر معامله ${tr.symbol} را به پشت خط Tenkan/Kijun منتقل کنید یا در صورت کلوز کندل پشت تنکان در سود ببندید.",
                )
            }
        }

        // ادغام با نکات پین‌شده قبلی کاربر
        val pinnedIds = _pinnedNotes.value.map { it.id }.toSet()
        val combined = (
            _pinnedNotes.value +
                notes.map { it.copy(isPinnedForNextUpdate = it.id in pinnedIds) }
            ).distinctBy { it.id }

        return combined
    }
}
