package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * مدل‌های استراتژی ایچیموکوی بیت‌کوین (`BTC/USDT`).
 * قانون کلیدی سیستم: در هر لحظه **فقط یک متود (استراتژی)** می‌تواند فعال باشد؛
 * به محض فعال‌سازی هر استراتژی، استراتژی قبلی به‌صورت خودکار غیرفعال می‌شود.
 */
enum class BitcoinStrategyModel(
    val id: String,
    val titleFa: String,
    val badgeFa: String,
    val authorRefFa: String,
    val summaryFa: String,
    val hasPatel: Boolean = true,
    val hasTuttle: Boolean = true,
    val hasHosoda: Boolean = true,
    val hasPeloille: Boolean = true,
    val baseOptimalRr: Double = 2.15,
) {
    // ── ۱. ترکیب کامل ۴گانه (Quad Master: C(4,4) = 1) ────────────────────────
    BASE_ICHIMOKU(
        id = "BASE_ICHIMOKU",
        titleFa = "استراتژی بیس (ترکیب کامل ۴گانه: پاتل+تاتل+هوسودا+پلویل)",
        badgeFa = "ترکیب ۴گانه · بیس جامع",
        authorRefFa = "پاتل + تاتل + هوسودا + پلویل",
        summaryFa = "ترکیب کامل هر ۴ ستون ایچیموکو: تعادل کیجون پاتل، فیلتر چیکو و بریک‌اوت تاتل، امواج N/V/E و چرخه زمان هوسودا، و پلاتوهای فلت سه‌تایم‌فریمی کارن پلویل.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = true,
        baseOptimalRr = 2.25,
    ),

    // ── ۲. تک‌استراتژی‌ها (Single Models: C(4,1) = 4) ────────────────────────
    PATEL_KIJUN_PULLBACK(
        id = "PATEL_KIJUN_PULLBACK",
        titleFa = "مدل تکی ۱: پولبک تعادلی کیجون + Preserve Mode (پاتل)",
        badgeFa = "تکی · منش پاتل",
        authorRefFa = "Manesh Patel (Wiley 2010)",
        summaryFa = "تمرکز بر پولبک قیمت به خط تعادل Kijun-sen در روند هم‌جهت ۳ تایم‌فریم، فیلتر C-Clamp و قفل سود با Preserve Mode روی تنکان.",
        hasPatel = true,
        hasTuttle = false,
        hasHosoda = false,
        hasPeloille = false,
        baseOptimalRr = 1.85,
    ),
    TUTTLE_KUMO_BREAKOUT(
        id = "TUTTLE_KUMO_BREAKOUT",
        titleFa = "مدل تکی ۲: شکست کومو با فیلتر سقف چیکو (تاتل)",
        badgeFa = "تکی · مارک تاتل",
        authorRefFa = "Mark L. Tuttle (Kumo Trader)",
        summaryFa = "شکار شکست‌های پرقدرت ابر کومو با شرط هم‌جهتی ابر آینده و عبور قطعی از آخرین قله/دره خط Chikou Span.",
        hasPatel = false,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = false,
        baseOptimalRr = 2.30,
    ),
    ELLIOTT_HOSODA_WAVE(
        id = "ELLIOTT_HOSODA_WAVE",
        titleFa = "مدل تکی ۳: امواج N هوسودا و اعداد زمانی (الیوت/هوسودا)",
        badgeFa = "تکی · هوسودا/الیوت",
        authorRefFa = "Nicole Elliott / Goichi Hosoda",
        summaryFa = "سوار شدن بر موج سوم هوسودا (موج N از ساختار A-B-C) روی نقطه S با محاسبه دقیق اهداف V، N، E، NT و پایش اعداد زمانی 9، 17، 26 و 33.",
        hasPatel = false,
        hasTuttle = false,
        hasHosoda = true,
        hasPeloille = false,
        baseOptimalRr = 2.50,
    ),
    PELOILLE_MTF_PLATEAU(
        id = "PELOILLE_MTF_PLATEAU",
        titleFa = "مدل تکی ۴: پلاتوهای افقی SSB/Kijun و کاتانا (پلویل)",
        badgeFa = "تکی · کارن پلویل",
        authorRefFa = "Karen Péloille (Trading with Ichimoku)",
        summaryFa = "استخراج دیوارهای حمایت و مقاومت از فلت‌های Kijun و Senkou Span B در سه تایم‌فریم با تعادل کاتانا و آزادی حرکتی چیکو.",
        hasPatel = false,
        hasTuttle = false,
        hasHosoda = false,
        hasPeloille = true,
        baseOptimalRr = 1.95,
    ),

    // ── ۳. ترکیب‌های دوتایی (Dual Hybrids: C(4,2) = 6) ───────────────────────
    COMBO_PATEL_TUTTLE(
        id = "COMBO_PATEL_TUTTLE",
        titleFa = "ترکیب ۲گانه (پاتل + تاتل): پولبک کیجون + شکست چیکو",
        badgeFa = "۲گانه · پاتل+تاتل",
        authorRefFa = "Patel + Tuttle Hybrid",
        summaryFa = "ترکیب نقطه ورود کم‌ریسک پولبک کیجون‌سن پاتل با تایید شکست سقف/کف ساختاری چیکو اسپن و شیب کوموی آینده تاتل.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = false,
        baseOptimalRr = 2.10,
    ),
    COMBO_PATEL_HOSODA(
        id = "COMBO_PATEL_HOSODA",
        titleFa = "ترکیب ۲گانه (پاتل + هوسودا): تعادل کیجون + موج N و زمان",
        badgeFa = "۲گانه · پاتل+هوسودا",
        authorRefFa = "Patel + Hosoda Hybrid",
        summaryFa = "ورود نقطه‌زنی روی پولبک کیجون‌سن در نقطه S موج سوم هوسودا با تارگت‌گذاری دقیق روی امواج V، N و E و فیلتر چرخه زمانی.",
        hasPatel = true,
        hasTuttle = false,
        hasHosoda = true,
        hasPeloille = false,
        baseOptimalRr = 2.25,
    ),
    COMBO_PATEL_PELOILLE(
        id = "COMBO_PATEL_PELOILLE",
        titleFa = "ترکیب ۲گانه (پاتل + پلویل): پولبک کیجون + پلاتوی فلت و کاتانا",
        badgeFa = "۲گانه · پاتل+پلویل",
        authorRefFa = "Patel + Péloille Hybrid",
        summaryFa = "هم‌افزایی کش لاستیکی کیجون پاتل با دیوارهای فلت SSB/Kijun و فوران کاتانای کارن پلویل برای معاملات بسیار کم‌خطا.",
        hasPatel = true,
        hasTuttle = false,
        hasHosoda = false,
        hasPeloille = true,
        baseOptimalRr = 1.95,
    ),
    COMBO_TUTTLE_HOSODA(
        id = "COMBO_TUTTLE_HOSODA",
        titleFa = "ترکیب ۲گانه (تاتل + هوسودا): بریک‌اوت کومو + امواج N/E هوسودا",
        badgeFa = "۲گانه · تاتل+هوسودا",
        authorRefFa = "Tuttle + Hosoda Hybrid",
        summaryFa = "شکار شکست‌های انفجاری کومو و چیکو اسپن تاتل در ابتدای موج سوم هوسودا با اهداف موجی بلندمدت بالای ۱:۲.۵.",
        hasPatel = false,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = false,
        baseOptimalRr = 2.45,
    ),
    COMBO_TUTTLE_PELOILLE(
        id = "COMBO_TUTTLE_PELOILLE",
        titleFa = "ترکیب ۲گانه (تاتل + پلویل): شکست کومو/چیکو + فیلتر فلت MTF",
        badgeFa = "۲گانه · تاتل+پلویل",
        authorRefFa = "Tuttle + Péloille Hybrid",
        summaryFa = "فیلتر کردن شکست‌های کومو با بافر ضد جاذبه SSB فلت و بررسی مسیر باز تا اولین پلاتوی افقی تایم‌فریم بالاتر.",
        hasPatel = false,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = true,
        baseOptimalRr = 2.15,
    ),
    COMBO_HOSODA_PELOILLE(
        id = "COMBO_HOSODA_PELOILLE",
        titleFa = "ترکیب ۲گانه (هوسودا + پلویل): هندسه موج N + پلاتوی فلت و کاتانا",
        badgeFa = "۲گانه · هوسودا+پلویل",
        authorRefFa = "Hosoda + Péloille Hybrid",
        summaryFa = "تطبیق اهداف موجی N/V/E و اعداد زمانی هوسودا با سطوح تعادلی فلت 4H/1H کارن پلویل برای دقیق‌ترین حد سود.",
        hasPatel = false,
        hasTuttle = false,
        hasHosoda = true,
        hasPeloille = true,
        baseOptimalRr = 2.30,
    ),

    // ── ۴. ترکیب‌های سه‌تایی (Triple Hybrids: C(4,3) = 4) ────────────────────
    COMBO_PATEL_TUTTLE_HOSODA(
        id = "COMBO_PATEL_TUTTLE_HOSODA",
        titleFa = "ترکیب ۳گانه (پاتل + تاتل + هوسودا): کیجون + چیکو + موج N",
        badgeFa = "۳گانه · پاتل+تاتل+هوسودا",
        authorRefFa = "Patel + Tuttle + Hosoda",
        summaryFa = "همگرایی پولبک کیجون پاتل، شکست سقف ساختاری چیکو تاتل و هندسه موج سوم هوسودا با شکارچی روند بالای ۱:۳.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = false,
        baseOptimalRr = 2.35,
    ),
    COMBO_PATEL_TUTTLE_PELOILLE(
        id = "COMBO_PATEL_TUTTLE_PELOILLE",
        titleFa = "ترکیب ۳گانه (پاتل + تاتل + پلویل): کیجون + چیکو + پلاتوی فلت",
        badgeFa = "۳گانه · پاتل+تاتل+پلویل",
        authorRefFa = "Patel + Tuttle + Péloille",
        summaryFa = "ترکیب تعادل کیجون و Preserve Mode پاتل با شکست چیکو تاتل و محافظت در برابر دیوارهای فلت MTF کارن پلویل.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = true,
        baseOptimalRr = 2.15,
    ),
    COMBO_PATEL_HOSODA_PELOILLE(
        id = "COMBO_PATEL_HOSODA_PELOILLE",
        titleFa = "ترکیب ۳گانه (پاتل + هوسودا + پلویل): کیجون + موج N + فلت MTF",
        badgeFa = "۳گانه · پاتل+هوسودا+پلویل",
        authorRefFa = "Patel + Hosoda + Péloille",
        summaryFa = "ورود تعادلی پاتل روی نقطه S هوسودا با تنظیم خودکار حد سود قبل از پلاتوهای افقی 4H/1H کارن پلویل.",
        hasPatel = true,
        hasTuttle = false,
        hasHosoda = true,
        hasPeloille = true,
        baseOptimalRr = 2.25,
    ),
    COMBO_TUTTLE_HOSODA_PELOILLE(
        id = "COMBO_TUTTLE_HOSODA_PELOILLE",
        titleFa = "ترکیب ۳گانه (تاتل + هوسودا + پلویل): بریک‌اوت + موج N + فلت MTF",
        badgeFa = "۳گانه · تاتل+هوسودا+پلویل",
        authorRefFa = "Tuttle + Hosoda + Péloille",
        summaryFa = "شکست کومو و چیکو تاتل با هدف‌گذاری موجی هوسودا و فیلتر جاذبه SSB فلت کارن پلویل.",
        hasPatel = false,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = true,
        baseOptimalRr = 2.40,
    ),

    // ── ۵. متود جامع یکپارچه کتاب‌های درایو (Unified Drive Master Method — تنها استراتژی جامع درایو) ──
    DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER(
        id = "DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER",
        titleFa = "متود جامع یکپارچه درایو (تلفیق بیزین تطبیقی هر ۹ کتاب PDF گوگل درایو)",
        badgeFa = "متود جامع درایو · ابرترکیب ۹ کتاب",
        authorRefFa = "Unified 9-Book Drive Meta-Ensemble (TuAnh + Foka + Keenbase + Mukherjee ×2 + HowToTrade + TSG + Koonitz ×2)",
        summaryFa = "تلفیق یکپارچه هر ۹ کتاب PDF گوگل درایو با الگوریتم روز «اجماع بیزین تطبیقی و وزن‌دهی رژیم بازار» (Adaptive Bayesian Log-Odds 9-Book Consensus): ترکیب صفحه سه‌گانه EMA(13/21/34/89/200)، کراس نهادی (7-22-44 و 10-30-60)، فشردگی بولینگر، پرش RSI(7)/RSI(14) از ۵۰، تایید DMI/ADX(24) و MACD، آزادی سه‌بعدی چیکو، فرمول کلی (Kelly 69-76%) و سنگر استاپ ترکیبی فرکتال + Kijun30±0.4ATR.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = true,
        baseOptimalRr = 2.85,
    );

    val isDrivePdfStrategy: Boolean
        get() = this == DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER

    val combinationCount: Int
        get() = listOf(hasPatel, hasTuttle, hasHosoda, hasPeloille).count { it }

    val combinationCategoryFa: String
        get() = if (this == DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER) {
            "متود جامع یکپارچه درایو"
        } else {
            when (combinationCount) {
                1 -> "تک‌استراتژی (۱گانه)"
                2 -> "ترکیب دوتایی (۲گانه)"
                3 -> "ترکیب سه‌تایی (۳گانه)"
                else -> "ترکیب کامل چهارگانه (۴گانه)"
            }
        }

    companion object {
        val legacyIndividualDriveIds: Set<String> = setOf(
            "TUANH_BOLLINGER_SQUEEZE",
            "FOKA_15MIN_EMA21_RSI7_FRACTAL",
            "KEENBASE_CROSS_10_30_60",
            "MUKHERJEE_RELATIVE_TK_RSI_KELLEY",
            "MUKHERJEE_CLOUDS_CHIKOU_FILTER",
            "HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK",
            "TSG_QUICK_PROFIT_4STEP",
            "KOONITZ_5RULES_DMI_MACD_EMA34",
            "KOONITZ_TRIPLE_SCREEN_FAST_7_22_44",
        )

        val drivePdfStrategies: List<BitcoinStrategyModel>
            get() = listOf(DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER)

        val drivePdfStrategyIds: Set<String>
            get() = setOf(DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER.id)

        fun normalizeStrategyId(raw: String?): String {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty()) return BASE_ICHIMOKU.id
            if (cleaned.uppercase(Locale.US) in legacyIndividualDriveIds) {
                return DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER.id
            }
            return cleaned
        }

        fun fromId(raw: String?): BitcoinStrategyModel {
            val norm = normalizeStrategyId(raw)
            return entries.firstOrNull { it.id.equals(norm, ignoreCase = true) } ?: BASE_ICHIMOKU
        }
    }
}

/**
 * شناسنامه فنی و دفترچه قوانین متود جامع یکپارچه درایو و ۱۵ ترکیب پایه.
 */
data class DriveBookPlaybookSpec(
    val strategyId: String,
    val bookFileTitle: String,
    val authorAndEdition: String,
    val ichimokuSettingsFa: String,
    val auxiliaryIndicatorsFa: String,
    val entryChecklistStepsFa: List<String>,
    val stopLossFormulaFa: String,
    val takeProfitAndExitRulesFa: String,
    val specialEdgeAdvantageFa: String,
)

fun BitcoinStrategyModel.driveBookPlaybook(): DriveBookPlaybookSpec = when (this) {
    BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Unified Drive Books 1–9 Master Method (تلفیق جامع هر ۹ کتاب PDF گوگل درایو)",
        authorAndEdition = "Adaptive Bayesian Log-Odds 9-Book Consensus — TuAnh + Foka + Keenbase + Mukherjee ×2 + HowToTrade + TSG + Koonitz ×2",
        ichimokuSettingsFa = "ابرچنگال سه‌گانه ایچیموکو: استاندارد (9,26,52) + نهادی Keenbase (10,30,60) + سریع کونیتز (7,22,44)",
        auxiliaryIndicatorsFa = "EMA Ribbon (13, 21, 34, 48, 89, SMA200) + BB(20,2) Squeeze + RSI(7)/RSI(14) 50-Bounce + DMI/ADX(14) + MACD + 5-Bar Fractals + Kelly Win%",
        entryChecklistStepsFa = listOf(
            "۱. فیلتر صفحه سه‌گانه کونیتز و هم‌جهتی هر ۳ ابر ایچیموکو (9-26-52 ، 10-30-60 و 7-22-44) همراه با ریبون EMA(13/21/34/89)",
            "۲. اجماع بیزین تطبیقی حداقل ۶ از ۹ کتاب درایو با احتمال برد ترکیبی ≥ ۷۸٪ و امتیاز معیار کلی (Kelly ≥ 69%)",
            "۳. تایید هم‌زمان ماشه‌های شکار سود: فشردگی بولینگر (TuAnh)، پرش RSI(7) از خط ۵۰ (Foka)، کراس 10/30 و 7/22 و تایید DMI/MACD",
            "۴. آزادی سه‌بعدی Chikou Span بالای/زیر کومو در ۲۶ دوره قبل (کتاب‌های ۵، ۷ و ۸) و عدم وجود کیجون فلت رِنج",
        ),
        stopLossFormulaFa = "سنگر ترکیبی ضد استاپ‌هانت: قوی‌ترین سطح بین فرکتال ۵ کندلی بیل ویلیامز (Foka) + Kijun(30)±0.40ATR (Keenbase) + EMA(34) کونیتز + BB Middle(20)",
        takeProfitAndExitRulesFa = "تارگت پویا تا سقف ۳R (تلفیق هدف 3R سیستم Keenbase/Foka با امواج N/E هوسودا و هدف MA200 کتاب TuAnh) و بستن کامل معامله در رسیدن به 3R",
        specialEdgeAdvantageFa = "با ادغام ریاضی هر ۹ کتاب در یک متود جامع بیزین، خطاهای تک‌استراتژی حذف شده و فقط معاملات اسنایپر با بالاترین امید ریاضی شکار می‌شوند.",
    )

    else -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = authorRefFa,
        authorAndEdition = "$badgeFa — $authorRefFa",
        ichimokuSettingsFa = "ایچیموکوی چندتایم‌فریمی (9, 26, 52) با همگرایی ۳ تایم‌فریم (4H / 1H / 15m-5m)",
        auxiliaryIndicatorsFa = buildList {
            if (hasPatel) add("تعادل کیجون و Preserve Mode پاتل")
            if (hasTuttle) add("شکست سقف چیکو و کومو تاتل")
            if (hasHosoda) add("امواج N/V/E و چرخه زمان هوسودا")
            if (hasPeloille) add("پلاتوهای فلت MTF و کاتانا پلویل")
        }.joinToString(" + "),
        entryChecklistStepsFa = listOf(
            "۱. هم‌جهتی رژیم کومو در ۳ تایم‌فریم و چیدمان ۵ خط سانیاکو",
            "۲. آزادی سه‌بعدی Chikou Span و عبور از سقف/کف ساختاری",
            "۳. رعایت قانون کش لاستیکی کیجون (نبود C-Clamp) و عبور از جاذبه فلت SSB",
            "۴. تایید هندسه موج N هوسودا و شلیک ماشه در ناحیه اسنایپر",
        ),
        stopLossFormulaFa = "سنگر ساختاری Kijun-Sen / لبه Kumo / نقطه S موج هوسودا + بافر ضد استاپ‌هانت",
        takeProfitAndExitRulesFa = "اهداف موجی V/N/E هوسودا با حداقل R:R ≥ 1:1.5 و بستن کامل در سقف 1:3",
        specialEdgeAdvantageFa = summaryFa,
    )
}

fun BitcoinStrategyModel.formatLiveBookTelemetryFa(
    trigSnap: IchimokuBarSnapshot?,
    stratSnap: IchimokuBarSnapshot? = trigSnap,
    isLong: Boolean = true,
): String {
    if (trigSnap == null) return "در حال دریافت کندل‌ها برای محاسبه اندیکاتورهای اختصاصی کتاب..."
    return when (this) {
        BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> {
            val consensus = IchimokuStrategyEngine.evaluateUnified9BookConsensus(
                isLong = isLong,
                trigSnap = trigSnap,
                stratSnap = stratSnap ?: trigSnap,
                macroSnap = null,
            )
            String.format(
                Locale.US,
                "اجماع ۹ کتاب درایو: %d/9 کتاب تایید · احتمال برد بیزین=%.1f%% · Kelly=%d%% · Fast(7/22)=%s/%s · Keenbase(10/30)=%s/%s · ADX=%.1f · RSI7=%.1f",
                consensus.confirmedBooksCount,
                consensus.bayesianWinProbabilityPct,
                trigSnap.kellyWinProbabilityPercent,
                IchimokuStrategyEngine.round2(trigSnap.tenkan7).toString(),
                IchimokuStrategyEngine.round2(trigSnap.kijun22).toString(),
                IchimokuStrategyEngine.round2(trigSnap.tenkan10).toString(),
                IchimokuStrategyEngine.round2(trigSnap.kijun30).toString(),
                trigSnap.adx14,
                trigSnap.rsi7,
            )
        }

        else -> String.format(
            Locale.US,
            "Tenkan(9)=%s · Kijun(26)=%s · Kumo=[%s .. %s] · ER14=%.0f%%",
            IchimokuStrategyEngine.round2(trigSnap.tenkan).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kijun).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kumoBottom).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kumoTop).toString(),
            trigSnap.efficiencyRatio14 * 100.0,
        )
    }
}

/**
 * پارامترهای قابل تنظیم و ترمیم برای «استراتژی بیس» (Base Strategy) و مدل‌های مشتق‌شده.
 */
@Serializable
data class BaseStrategyConfig(
    val macroIntervalLabel: String = "4H",
    val strategyIntervalLabel: String = "1H",
    val triggerIntervalLabel: String = "15m",
    val tenkanPeriod: Int = 9,
    val kijunPeriod: Int = 26,
    val senkouBPeriod: Int = 52,
    val displacement: Int = 26,
    val maxKijunExtensionAtr: Double = 1.8,
    val slBufferAtr: Double = 0.25,
    val breakoutBufferAtr: Double = 0.15,
    val minRiskReward: Double = 1.5,
    val enablePullbackTrigger: Boolean = true,
    val enableBreakoutTrigger: Boolean = true,
    val enablePreserveMode: Boolean = true,
    val requireHigherTfAlignment: Boolean = true,
    val requireChikouOpenSpace: Boolean = true,
    val requireFutureKumoSlope: Boolean = true,
    val minEfficiencyRatio: Double = 0.20,
    val minVolumeRatio20: Double = 0.60,
    val minDriveBooksConsensus: Int = 6,
    val autoTunedGeneration: Int = 0,
) {
    val macroInterval: Interval get() = Interval.fromLabel(macroIntervalLabel)
    val strategyInterval: Interval get() = Interval.fromLabel(strategyIntervalLabel)
    val triggerInterval: Interval get() = Interval.fromLabel(triggerIntervalLabel)
}

/**
 * خروجی موتور «اجماع بیزین تطبیقی ۹ کتاب درایو» (Adaptive Bayesian Log-Odds 9-Book Consensus):
 * ارزیابی هم‌زمان هر ۹ کتاب PDF گوگل درایو و استخراج احتمال برد بیزین، سنگر حد ضرر ترکیبی و تارگت بهینه.
 */
data class Unified9BookConsensusResult(
    val isLong: Boolean,
    val book1TuAnhPassed: Boolean,
    val book2FokaPassed: Boolean,
    val book3KeenbasePassed: Boolean,
    val book4MukherjeeKellyPassed: Boolean,
    val book5MukherjeeChikouPassed: Boolean,
    val book6HowToTradePassed: Boolean,
    val book7Tsg4StepPassed: Boolean,
    val book8Koonitz5RulesPassed: Boolean,
    val book9KoonitzTripleScreenPassed: Boolean,
    val confirmedBooksCount: Int,
    val bayesianWinProbabilityPct: Double,
    val compositeSniperAnchor: Double,
    val compositeFortressStopLoss: Double,
    val dynamicOptimalRr: Double,
    val activeBookBadgesFa: List<String>,
    val summaryFa: String,
)

/**
 * اسلات استراتژی سفارشی برای مدل‌هایی که کاربر در مراحل بعدی اضافه یا شخصی‌سازی می‌کند.
 */
@Serializable
data class CustomStrategySlot(
    val id: String,
    val titleFa: String,
    val badgeFa: String = "مدل سفارشی",
    val summaryFa: String,
    val baseModelId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
    val config: BaseStrategyConfig = BaseStrategyConfig(),
)

/**
 * خروجی محاسبه دقیق ۵ خط ایچیموکو + اندیکاتورهای ۹ کتاب PDF گوگل درایو روی یک تایم‌فریم مشخص.
 */
data class IchimokuBarSnapshot(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val tenkan: Double,
    val kijun: Double,
    val prevTenkan: Double,
    val prevKijun: Double,
    val currentSpanA: Double,
    val currentSpanB: Double,
    val futureSpanA: Double,
    val futureSpanB: Double,
    val prevFutureSpanA: Double,
    val prevFutureSpanB: Double,
    val atr14: Double,
    val chikouBullishOpenSpace: Boolean,
    val chikouBearishOpenSpace: Boolean,
    val chikouResistancePeak: Double,
    val chikouSupportTrough: Double,
    val isCurrentSpanBFlat: Boolean,
    val isKijunFlat: Boolean,
    val isTenkanFlat: Boolean,
    val flatEquilibriumLevels: List<Double>,
    val candleBodyRatio: Double = 0.5,
    val closeLocationValue: Double = 0.5,
    val volumeRatio20: Double = 1.0,
    val kijunSlopeAtrPerBar: Double = 0.0,
    val tenkanSlopeAtrPerBar: Double = 0.0,
    val futureKumoThicknessAtr: Double = 0.2,
    val currentKumoThicknessAtr: Double = 0.2,
    val isCClampWarning: Boolean = false,
    val isKatanaEquilibrium: Boolean = false,
    val threeCandleBullishImpulse: Boolean = false,
    val threeCandleBearishImpulse: Boolean = false,
    val efficiencyRatio14: Double = 0.5,
    val upperWickRatio: Double = 0.1,
    val lowerWickRatio: Double = 0.1,
    val recentKatanaRelease: Boolean = false,
    // ── اندیکاتورهای استخراج‌شده از ۹ کتاب PDF گوگل درایو ──
    val bbMiddle20: Double = close,
    val bbUpper20: Double = close + atr14 * 2.0,
    val bbLower20: Double = close - atr14 * 2.0,
    val bbBandwidthRatio: Double = 0.02,
    val isBollingerSqueeze: Boolean = true,
    val isMarubozuBreakoutBull: Boolean = close >= open,
    val isMarubozuBreakoutBear: Boolean = close <= open,
    val ema13: Double = tenkan,
    val ema21: Double = kijun,
    val ema34: Double = kijun,
    val ema48: Double = currentSpanA,
    val ema89: Double = currentSpanB,
    val sma200: Double = currentSpanB,
    val ema21SlopeAtrPerBar: Double = kijunSlopeAtrPerBar,
    val rsi14: Double = if (close >= kijun) 58.0 else 42.0,
    val prevRsi14: Double = if (close >= kijun) 55.0 else 45.0,
    val recentPeakRsi14: Double = 60.0,
    val recentTroughRsi14: Double = 40.0,
    val rsi7: Double = if (close >= kijun) 57.0 else 43.0,
    val prevRsi7: Double = 50.0,
    val rsi7Bullish50Bounce: Boolean = close >= kijun,
    val rsi7Bearish50Bounce: Boolean = close <= kijun,
    val lastDownFractalLow: Double = min(low, kijun - atr14 * 0.5),
    val lastUpFractalHigh: Double = max(high, kijun + atr14 * 0.5),
    val tenkan10: Double = tenkan,
    val kijun30: Double = kijun,
    val senkouA103060: Double = currentSpanA,
    val senkouB60: Double = currentSpanB,
    val tenkan7: Double = tenkan,
    val kijun22: Double = kijun,
    val senkouA72244: Double = currentSpanA,
    val senkouB44: Double = currentSpanB,
    val macdLine: Double = tenkan - kijun,
    val macdSignal: Double = (tenkan - kijun) * 0.8,
    val macdHistogram: Double = (tenkan - kijun) * 0.2,
    val plusDi14: Double = if (close >= kijun) 28.0 else 16.0,
    val minusDi14: Double = if (close >= kijun) 16.0 else 28.0,
    val adx14: Double = 27.0,
    val chikouAboveCloud26: Boolean = chikouBullishOpenSpace,
    val chikouBelowCloud26: Boolean = chikouBearishOpenSpace,
    val hasBullishCandlestickPattern: Boolean = close >= open,
    val hasBearishCandlestickPattern: Boolean = close <= open,
    val kellySignalCount: Int = 4,
    val kellyWinProbabilityPercent: Int = 69,
) {
    val kumoTop: Double get() = max(currentSpanA, currentSpanB)
    val kumoBottom: Double get() = min(currentSpanA, currentSpanB)
    val kumoThickness: Double get() = abs(currentSpanA - currentSpanB)
    val isPriceAboveKumo: Boolean get() = close > kumoTop
    val isPriceBelowKumo: Boolean get() = close < kumoBottom
    val isPriceInsideKumo: Boolean get() = !isPriceAboveKumo && !isPriceBelowKumo
    val isFutureKumoBullish: Boolean get() = futureSpanA > futureSpanB
    val isFutureKumoBearish: Boolean get() = futureSpanA < futureSpanB
    val isFutureSpanARising: Boolean get() = futureSpanA >= prevFutureSpanA - 1e-8
    val isFutureSpanAFalling: Boolean get() = futureSpanA <= prevFutureSpanA + 1e-8
    val isFutureSpanBNonFalling: Boolean get() = futureSpanB >= prevFutureSpanB - 1e-8
    val isFutureSpanBNonRising: Boolean get() = futureSpanB <= prevFutureSpanB + 1e-8
    val distanceToKijun: Double get() = abs(close - kijun)
    val distanceToKijunAtr: Double get() = if (atr14 > 0.0) distanceToKijun / atr14 else 0.0
    val distanceToTenkan: Double get() = abs(close - tenkan)
    val sanyakuBullishOrder: Boolean
        get() = close > kumoTop && tenkan >= kijun - atr14 * 0.05 && kijun >= kumoBottom - atr14 * 0.10
    val sanyakuBearishOrder: Boolean
        get() = close < kumoBottom && tenkan <= kijun + atr14 * 0.05 && kijun <= kumoTop + atr14 * 0.10
    val kumoTop103060: Double get() = max(senkouA103060, senkouB60)
    val kumoBottom103060: Double get() = min(senkouA103060, senkouB60)
    val kumoTop72244: Double get() = max(senkouA72244, senkouB44)
    val kumoBottom72244: Double get() = min(senkouA72244, senkouB44)
    val isMacdBullish: Boolean get() = macdLine >= macdSignal - abs(macdSignal) * 0.02
    val isMacdBearish: Boolean get() = macdLine <= macdSignal + abs(macdSignal) * 0.02
    val isDmiBullish: Boolean get() = plusDi14 >= minusDi14 - 1.0 && adx14 >= 20.0
    val isDmiBearish: Boolean get() = minusDi14 >= plusDi14 - 1.0 && adx14 >= 20.0
    val rsiPeakDropLong: Double get() = (recentPeakRsi14 - rsi14).coerceAtLeast(0.0)
    val rsiTroughRiseShort: Double get() = (rsi14 - recentTroughRsi14).coerceAtLeast(0.0)
}

data class IchimokuChartBarOverlay(
    val index: Int,
    val candle: Candle,
    val tenkan: Double,
    val kijun: Double,
    val currentSpanA: Double,
    val currentSpanB: Double,
    val chikouPrice: Double?,
)

data class FutureKumoPoint(
    val stepAhead: Int,
    val spanA: Double,
    val spanB: Double,
)

data class IchimokuChartSeries(
    val bars: List<IchimokuChartBarOverlay>,
    val futureKumo: List<FutureKumoPoint>,
)

/**
 * ساختار موج ۳ نقطه‌ای هوسودا (A -> B -> C) و ۴ هدف قیمتی (V, N, E, NT) + شمارش زمانی.
 */
data class HosodaWaveProjection(
    val direction: SignalAction,
    val pointA: Double,
    val pointB: Double,
    val pointC: Double,
    val sPointHeld: Boolean,
    val targetV: Double,
    val targetN: Double,
    val targetE: Double,
    val targetNT: Double,
    val barsSinceWaveStart: Int,
    val nearestTimeCycleNumber: Int,
    val isNearTimeCycleChangeDay: Boolean,
)

enum class TrendPhase(val titleFa: String, val badgeFa: String) {
    GENESIS("تولد اصل روند (شروع موج ۱/۳)", "🚀 شروع اصل روند"),
    CORE_EXPANSION("متن اصلی روند (گسترش موج N)", "🔥 قلب روند اصلی"),
    LATE_EXHAUSTION("اشباع و انتهای موج (ممنوعیت ورود جدید)", "⚠️ اشباع انتهای روند"),
    CHOP_RANGE("فشردگی / رِنج فرسایشی (فیلتر خطا)", "🛑 رِنج و نوسان کاذب"),
}

enum class StrategyTriggerType(val titleFa: String) {
    EARLY_KATANA_GENESIS("ماشه ۰ (شکار اول روند): فوران کاتانا/کومو در لحظه تولد موج اصلی"),
    PULLBACK_BOUNCE("ماشه ۱: پولبک به تعادل (Kijun/Tenkan/S-Point) و کلوز بالای تنکان"),
    VALIDATED_BREAKOUT("ماشه ۲: شکست معتبر کومو/کیجون با عبور از سقف/کف چیکو و بافر فلت SSB"),
    SANYAKU_TK_CONTINUATION("ماشه ۳: ادامه موج سوم سانیاکو با تایید ۳ کندلی کارن پلویل"),
    NONE("در انتظار کندل بسته ماشه"),
}

enum class StrategyReadinessState(val labelFa: String) {
    SIGNAL_BUY("سیگنال خرید (Long) آماده شلیک"),
    SIGNAL_SELL("سیگنال فروش (Short) آماده شلیک"),
    WATCH_SETUP("همگرایی تایم‌فریم بالا برقرار؛ در انتظار ماشه ورود"),
    BLOCKED("قفل توسط فیلترهای استراتژی"),
    INSUFFICIENT_DATA("در حال دریافت کندل‌های مولتی‌تایم‌فریم"),
}

/**
 * برنامه‌ریز هوشمند زمان‌بندی بررسی مجدد و بهینه‌سازی مصرف اینترنت برای تایم‌فریم معامله ۱۵ دقیقه (`15m`)
 * روی هر ۱۵ نماد داخل اپ (`V1Universe.allSymbols`):
 *
 * ۱. همگام‌سازی دقیق با بسته شدن کندل ۱۵ دقیقه (`:00`، `:15`، `:30`، `:45` + ۳ ثانیه فرصت تسویه صرافی):
 *    هیچ کندل بسته‌شده‌ای از دست نمی‌رود و بین دو کندل ۱۵ دقیقه‌ای دانلود تکراری تاریخچه انجام نمی‌شود.
 * ۲. دریافت فقط کندل‌های جدید (`Delta Candle Sync`):
 *    پس از بارگذاری اولیه، در هر مرز ۱۵ دقیقه فقط کندل‌های جدید (`۳ تا ۵ کندل`) دانلود و به کش اضافه می‌شوند
 *    و کندل‌های `1H` و `4H` فقط سر ساعت و هر ۴ ساعت بروزرسانی می‌گردند.
 * ۳. سطح‌بندی هوشمند تیک قیمت زنده (`4-Tier Adaptive Polling`):
 *    - سطح ۱ (نمادهای دارای معامله باز برای رصد TP/SL و Preserve Mode): هر ۱۵ ثانیه
 *    - سطح ۲ (نماد منتخب یا نمادهای در آستانه ماشه `WATCH_SETUP`): هر ۶۰ ثانیه + چک میان‌کندلی در دقیقه ۷:۳۰
 *    - سطح ۳ (سایر نمادهای باز از ۱۵ نماد): اسکن کندل در مرز ۱۵ دقیقه + تیک قیمت هر ۱۸۰ ثانیه (۳ دقیقه)
 *    - سطح ۴ (نمادهای در ساعت تعطیلی بازار مثل فارکس/طلا در آخر هفته): توقف کامل (`۰ کیلوبایت`)
 */
enum class SymbolPollTier(
    val titleFa: String,
    val tickIntervalMs: Long,
    val descriptionFa: String,
) {
    OPEN_POSITION(
        titleFa = "سطح ۱ · معامله باز (TP/SL)",
        tickIntervalMs = 15_000L,
        descriptionFa = "بررسی هر ۱۵ ثانیه برای حفظ دقیق حد سود/ضرر و Preserve Mode",
    ),
    WATCH_OR_FOCUSED(
        titleFa = "سطح ۲ · آماده ماشه / منتخب",
        tickIntervalMs = 60_000L,
        descriptionFa = "بررسی هر ۶۰ ثانیه + چک میان‌کندلی ۷.۵ دقیقه",
    ),
    ACTIVE_15M_RADAR(
        titleFa = "سطح ۳ · رادار ۱۵ دقیقه",
        tickIntervalMs = 180_000L,
        descriptionFa = "اسکن کامل راس بسته شدن کندل ۱۵m (:00/:15/:30/:45) + تیک هر ۳ دقیقه",
    ),
    MARKET_CLOSED_PAUSED(
        titleFa = "سطح ۴ · سشن بسته (توقف دیتا)",
        tickIntervalMs = Long.MAX_VALUE,
        descriptionFa = "توقف کامل درخواست شبکه در ساعات تعطیلی بازار برای جلوگیری از مصرف اینترنت",
    ),
}

object Smart15mSyncPlanner {
    const val CANDLE_SETTLE_DELAY_MS = 3_000L
    const val OPEN_POSITION_TICK_MS = 15_000L
    const val WATCH_SYMBOL_TICK_MS = 60_000L
    const val RADAR_SYMBOL_TICK_MS = 180_000L
    const val MID_BAR_CHECK_OFFSET_MS = 450_000L // 7m 30s inside a 15m candle

    /**
     * شماره باکت کندل فعلی بر اساس تایم‌فریم (با احتساب ۳ ثانیه زمان نهایی شدن کندل در سرور).
     */
    fun currentCandleBucket(nowMillis: Long, interval: Interval): Long {
        val adjusted = (nowMillis - CANDLE_SETTLE_DELAY_MS).coerceAtLeast(0L)
        return adjusted / interval.millis
    }

    /**
     * زمان دقیق (بر حسب میلی‌ثانیه) بسته شدن کندل بعدی تایم‌فریم معامله (پیش‌فرض `15m`) به علاوه ۳ ثانیه.
     */
    fun nextTriggerBoundaryCheckMillis(
        nowMillis: Long,
        triggerInterval: Interval = Interval.M15,
    ): Long {
        val period = triggerInterval.millis
        val currentBucket = currentCandleBucket(nowMillis, triggerInterval)
        return (currentBucket + 1L) * period + CANDLE_SETTLE_DELAY_MS
    }

    /**
     * زمان چک میان‌کندلی (دقیقه ۷:۳۰ برای کندل ۱۵ دقیقه) جهت نمادهایی که ۴ یا ۵ شرط از ۶ شرط را دارند.
     */
    fun nextMidBarOrBoundaryCheckMillis(
        nowMillis: Long,
        triggerInterval: Interval = Interval.M15,
    ): Long {
        val period = triggerInterval.millis
        val bucketStart = currentCandleBucket(nowMillis, triggerInterval) * period
        val midPoint = bucketStart + (period / 2L) + CANDLE_SETTLE_DELAY_MS
        return if (nowMillis < midPoint) {
            midPoint
        } else {
            nextTriggerBoundaryCheckMillis(nowMillis, triggerInterval)
        }
    }

    /**
     * ثانیه‌های باقی‌مانده تا بسته شدن کندل ۱۵ دقیقه‌ای بعدی.
     */
    fun secondsUntilNextBoundary(
        nowMillis: Long,
        triggerInterval: Interval = Interval.M15,
    ): Long = ((nextTriggerBoundaryCheckMillis(nowMillis, triggerInterval) - nowMillis) / 1000L).coerceAtLeast(0L)

    /**
     * آیا برای این تایم‌فریم کندل جدیدی بسته شده است که نیاز به درخواست شبکه داشته باشد؟
     * اگر در همان باکت زمانی باشیم و حداقل ۷۵ کندل در کش داشته باشیم، نیازی به دانلود مجدد نیست.
     */
    fun shouldFetchCandlesFromNetwork(
        lastCachedBucket: Long?,
        cachedBarCount: Int,
        nowMillis: Long,
        interval: Interval,
        minRequiredBars: Int = 75,
    ): Boolean {
        if (lastCachedBucket == null || cachedBarCount < minRequiredBars) return true
        val nowBucket = currentCandleBucket(nowMillis, interval)
        return nowBucket > lastCachedBucket
    }

    /**
     * محاسبه تعداد کندل موردنیاز برای دانلود (Delta Sync):
     * اگر کش قبلی موجود باشد، فقط کندل‌های جدیدِ تشکیل‌شده از آخرین کندل بسته‌شده درخواست می‌شود
     * تا هیچ دیتایی از دست نرود و مصرف اینترنت بیش از ۹۵٪ کاهش یابد.
     */
    fun computeOptimalFetchLimit(
        lastClosedBarTimeMs: Long?,
        cachedBarCount: Int,
        nowMillis: Long,
        interval: Interval,
        fullLimit: Int = 110,
        minRequiredBars: Int = 75,
    ): Int {
        if (lastClosedBarTimeMs == null || cachedBarCount < minRequiredBars) return fullLimit
        val elapsedMs = (nowMillis - lastClosedBarTimeMs).coerceAtLeast(0L)
        val missedBars = (elapsedMs / interval.millis).toInt()
        if (missedBars <= 0) return 3
        return (missedBars + 3).coerceIn(4, fullLimit)
    }

    /**
     * تعیین سطح اولویت و فاصله زمانی بررسی مجدد تیک قیمت برای هر یک از ۱۵ نماد.
     */
    fun classifySymbolTier(
        symbol: String,
        hasOpenTrade: Boolean,
        isFocusedSymbol: Boolean,
        readiness: StrategyReadinessState?,
        nowMillis: Long = System.currentTimeMillis(),
    ): SymbolPollTier {
        val normalized = V1Universe.normalize(symbol)
        val isClosed = MarketHours.closedFor(normalized, nowMillis)
        if (isClosed && !hasOpenTrade) {
            return SymbolPollTier.MARKET_CLOSED_PAUSED
        }
        if (hasOpenTrade) {
            return SymbolPollTier.OPEN_POSITION
        }
        if (isFocusedSymbol ||
            readiness == StrategyReadinessState.SIGNAL_BUY ||
            readiness == StrategyReadinessState.SIGNAL_SELL ||
            readiness == StrategyReadinessState.WATCH_SETUP
        ) {
            return SymbolPollTier.WATCH_OR_FOCUSED
        }
        return SymbolPollTier.ACTIVE_15M_RADAR
    }

    /**
     * آیا زمان دریافت تیک قیمت زنده برای این نماد فرا رسیده است؟
     */
    fun isTickPollDue(
        lastTickAtMs: Long?,
        tier: SymbolPollTier,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (tier == SymbolPollTier.MARKET_CLOSED_PAUSED) return lastTickAtMs == null
        if (lastTickAtMs == null || lastTickAtMs <= 0L) return true
        return (nowMillis - lastTickAtMs) >= tier.tickIntervalMs
    }
}

data class StrategyConditionCheck(
    val index: Int,
    val titleFa: String,
    val passed: Boolean,
    val detailFa: String,
)

data class PreserveModeEvaluation(
    val tradeId: String,
    val newStopLoss: Double,
    val stageTitleFa: String,
    val reasonFa: String,
    val newTakeProfit: Double? = null,
    val lockedRMultiple: Double = 0.0,
    val shouldCloseImmediatelyInProfit: Boolean = false,
    val closePrice: Double? = null,
)

enum class OpenTradeTrendState(val badgeFa: String, val summaryFa: String) {
    STRONG_CONTINUATION("🟢 روند قوی و پایدار", "ساختار ۵ خط سانیاکو، شیب تنکان/کیجون و مومنتوم در جهت معامله برقرار است."),
    MOMENTUM_COOLING_LOCK_ACTIVE("🟡 کاهش شتاب روند (قفل سود فعال)", "شروع کشیدگی C-Clamp یا نزدیک شدن به چرخه زمانی هوسودا؛ حد ضرر در سود قفل شده است."),
    TREND_LOST_EXIT_IN_PROFIT("🔴 اتمام روند در سود (خروج فوری)", "روند معامله از دست رفته و ادامه نمی‌دهد؛ بستن فوری معامله در همین نقطه سود."),
}

/**
 * گزارش لحظه‌ای بررسی سلامت روند معامله باز و تشخیص دقیق زمانی که معامله در سود، روند خود را از دست می‌دهد.
 */
data class OpenTradeTrendHealthReport(
    val tradeId: String,
    val state: OpenTradeTrendState,
    val continuationScorePercent: Int,
    val currentProfitR: Double,
    val shouldExitNowInProfit: Boolean,
    val exitTriggerReasonFa: String,
    val activeWarningSignalsFa: List<String>,
    val profitExitRulesSummaryFa: String,
)

/**
 * کالبدشکافی دقیق فرمول محاسبه `R:R` و تعیین «عدد مناسب R:R» (`optimalRiskReward`) برای هر معامله.
 */
data class RrCalculationBreakdown(
    val entryPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val riskDistance1R: Double,
    val rewardDistance: Double,
    val actualRiskReward: Double,
    val optimalRiskReward: Double,
    val selectedTargetWaveLabel: String,
    val formulaTextFa: String,
    val slBasisFa: String,
    val tpBasisFa: String,
    val optimalRrReasonFa: String,
    val runnerArmedAbove3R: Boolean,
)

data class StrategyEvaluationReport(
    val symbol: String = IchimokuStrategyEngine.BTC_SYMBOL,
    val strategyId: String,
    val strategyTitleFa: String,
    val strategyBadgeFa: String,
    val macroInterval: Interval,
    val strategyInterval: Interval,
    val triggerInterval: Interval,
    val readiness: StrategyReadinessState,
    val action: SignalAction,
    val triggerType: StrategyTriggerType,
    val confidence: Double,
    val livePrice: Double,
    val suggestedEntry: Double?,
    val suggestedStopLoss: Double?,
    val targetV: Double?,
    val targetN: Double?,
    val targetE: Double?,
    val primaryTakeProfit: Double?,
    val riskReward: Double?,
    val macroSnapshot: IchimokuBarSnapshot?,
    val strategySnapshot: IchimokuBarSnapshot?,
    val triggerSnapshot: IchimokuBarSnapshot?,
    val waveProjection: HosodaWaveProjection?,
    val conditions: List<StrategyConditionCheck>,
    val summaryFa: String,
    val triggerBarTime: Long,
    val evaluatedAt: Long = System.currentTimeMillis(),
    val trendPhase: TrendPhase = TrendPhase.CORE_EXPANSION,
    val coreTrendScore: Double = 0.0,
    val sniperOptimalEntry: Double? = null,
    val sniperZoneLow: Double? = null,
    val sniperZoneHigh: Double? = null,
    val sniperEntryScore: Double = 0.0,
    val isSniperArmedPendingRetest: Boolean = false,
    val optimalRiskReward: Double? = riskReward,
    val rrBreakdown: RrCalculationBreakdown? = null,
) {
    val entryTimeframeBadgeFa: String
        get() = "تایم ورود: ${triggerInterval.label}"

    fun readinessShortFa(): String {
        val passed = conditions.count { it.passed }
        val total = conditions.size.takeIf { it > 0 } ?: 16
        return when {
            readiness == StrategyReadinessState.SIGNAL_BUY -> "🟢 خرید ($passed/$total)"
            readiness == StrategyReadinessState.SIGNAL_SELL -> "🔴 فروش ($passed/$total)"
            isSniperArmedPendingRetest -> "🎯 مسلح ($passed/$total)"
            readiness == StrategyReadinessState.WATCH_SETUP -> "🟡 آماده ($passed/$total)"
            conditions.isNotEmpty() -> "$passed/$total"
            else -> "..."
        }
    }

    fun isPriceInSniperWindow(price: Double): Boolean {
        val low = sniperZoneLow ?: return false
        val high = sniperZoneHigh ?: return false
        return price in low..high
    }
    val isActionable: Boolean
        get() = (readiness == StrategyReadinessState.SIGNAL_BUY || readiness == StrategyReadinessState.SIGNAL_SELL) &&
            action != SignalAction.NO_TRADE &&
            suggestedEntry != null && suggestedStopLoss != null && primaryTakeProfit != null

    fun toAiStrategyContext(): String = buildString {
        append("تایم‌فریم ورود: ").append(triggerInterval.label).append(" | ")
        append("استراتژی فعال: ").append(strategyTitleFa).append(" (").append(strategyId).append(") | ")
        append("نماد: ").append(symbol).append(" | ")
        append("تایم‌فریم‌ها: ").append(macroInterval.label).append("/")
            .append(strategyInterval.label).append("/").append(triggerInterval.label).append(" | ")
        append("ماشه: ").append(triggerType.titleFa).append(" | ")
        waveProjection?.let { w ->
            append("امواج هوسودا A=").append(round2(w.pointA))
                .append(", B=").append(round2(w.pointB))
                .append(", C=").append(round2(w.pointC))
                .append(" -> TP1(V)=").append(round2(w.targetV))
                .append(", TP2(N)=").append(round2(w.targetN))
                .append(", TP3(E)=").append(round2(w.targetE))
                .append(", کندل موج=").append(w.barsSinceWaveStart)
                .append(" (چرخه زمانی نزدیک: ").append(w.nearestTimeCycleNumber).append(") | ")
        }
        val passedCount = conditions.count { it.passed }
        append("شروط برقرار: ").append(passedCount).append("/").append(conditions.size).append(" — ")
        append(conditions.joinToString("؛ ") { "${it.titleFa}: ${if (it.passed) "تایید" else "رد"} (${it.detailFa})" })
    }
}

/**
 * گزارش هم‌زمان دو تایم‌فریم ورود (`15m` و `5m`) برای یک نماد با اشتراک‌گذاری کندل‌های `4H`/`1H`/`15m` بدون مصرف اضافه دیتا.
 */
data class DualTimeframeSymbolReport(
    val symbol: String,
    val report15m: StrategyEvaluationReport,
    val report5m: StrategyEvaluationReport,
    val reports15mByStrategy: Map<String, StrategyEvaluationReport> = mapOf(report15m.strategyId to report15m),
    val reports5mByStrategy: Map<String, StrategyEvaluationReport> = mapOf(report5m.strategyId to report5m),
) {
    fun reportFor(interval: Interval, strategyId: String? = null): StrategyEvaluationReport {
        val map = if (interval == Interval.M5) reports5mByStrategy else reports15mByStrategy
        if (strategyId != null) {
            map[strategyId]?.let { return it }
        }
        return if (interval == Interval.M5) report5m else report15m
    }

    val allReports15m: List<StrategyEvaluationReport>
        get() = reports15mByStrategy.values.toList().ifEmpty { listOf(report15m) }

    val allReports5m: List<StrategyEvaluationReport>
        get() = reports5mByStrategy.values.toList().ifEmpty { listOf(report5m) }

    val primaryReport: StrategyEvaluationReport
        get() = when {
            report15m.isActionable && report5m.isActionable ->
                if (report5m.coreTrendScore > report15m.coreTrendScore + 5.0) report5m else report15m
            report15m.isActionable -> report15m
            report5m.isActionable -> report5m
            report15m.isSniperArmedPendingRetest -> report15m
            report5m.isSniperArmedPendingRetest -> report5m
            report15m.readiness == StrategyReadinessState.WATCH_SETUP -> report15m
            report5m.readiness == StrategyReadinessState.WATCH_SETUP -> report5m
            else -> if (report5m.coreTrendScore > report15m.coreTrendScore) report5m else report15m
        }

    val actionableReports: List<StrategyEvaluationReport>
        get() = (allReports15m + allReports5m)
            .filter { it.isActionable }
            .sortedByDescending { it.coreTrendScore }

    val sniperArmedReports: List<StrategyEvaluationReport>
        get() = (allReports15m + allReports5m)
            .filter { it.isActionable || it.isSniperArmedPendingRetest }
            .sortedByDescending { it.coreTrendScore }

    val combinedSummaryFa: String
        get() = "[15m: ${report15m.readinessShortFa()}] · [5m: ${report5m.readinessShortFa()}]"
}

object IchimokuStrategyEngine {

    const val BTC_SYMBOL = "BTC/USDT"

    /** اعداد پایه و ترکیبی چرخه زمانی گوایچی هوسودا (Jikan Ron / Kihon Suchi) */
    val HOSODA_TIME_CYCLES = listOf(9, 17, 26, 33, 42, 65, 76, 129, 172)

    /**
     * محاسبه دقیق اسنپ‌شات ایچیموکو روی آخرین کندل بسته‌شده یک تایم‌فریم.
     * قانون کارن پلویل: همیشه روی کندل‌های بسته‌شده (Closed) تصمیم‌گیری می‌شود تا از سیگنال‌های لحظه‌ای کاذب جلوگیری شود.
     */
    fun computeSnapshot(
        candlesInput: List<Candle>,
        config: BaseStrategyConfig = BaseStrategyConfig(),
    ): IchimokuBarSnapshot? {
        val ordered = candlesInput
            .filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.high >= it.low && it.close > 0.0 }
            .sortedBy { it.time }
        val closedBars = ordered.filter { it.closed }.ifEmpty { ordered }
        val minBarsNeeded = max(config.senkouBPeriod + 5, 35)
        if (closedBars.size < minBarsNeeded) return null

        val lastIdx = closedBars.lastIndex
        val prevIdx = (lastIdx - 1).coerceAtLeast(0)

        fun midpoint(fromIdx: Int, toIdx: Int): Double {
            val start = fromIdx.coerceIn(0, toIdx)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (k in start..toIdx) {
                if (closedBars[k].high > hh) hh = closedBars[k].high
                if (closedBars[k].low < ll) ll = closedBars[k].low
            }
            return (hh + ll) / 2.0
        }

        fun tenkanAt(idx: Int): Double = midpoint(idx - (config.tenkanPeriod - 1), idx)
        fun kijunAt(idx: Int): Double = midpoint(idx - (config.kijunPeriod - 1), idx)
        fun spanBAtCreation(idx: Int): Double = midpoint(idx - (config.senkouBPeriod - 1), idx)
        fun spanAAtCreation(idx: Int): Double = (tenkanAt(idx) + kijunAt(idx)) / 2.0

        val disp = (config.displacement - 1).coerceAtLeast(1)
        fun currentSpanAAt(idx: Int): Double {
            val src = (idx - disp).coerceAtLeast(0)
            return spanAAtCreation(src)
        }
        fun currentSpanBAt(idx: Int): Double {
            val src = (idx - disp).coerceAtLeast(0)
            return spanBAtCreation(src)
        }

        val tenkan = tenkanAt(lastIdx)
        val kijun = kijunAt(lastIdx)
        val prevTenkan = tenkanAt(prevIdx)
        val prevKijun = kijunAt(prevIdx)

        val currentSpanA = currentSpanAAt(lastIdx)
        val currentSpanB = currentSpanBAt(lastIdx)

        val futureSpanA = spanAAtCreation(lastIdx)
        val futureSpanB = spanBAtCreation(lastIdx)
        val prevFutureSpanA = spanAAtCreation(prevIdx)
        val prevFutureSpanB = spanBAtCreation(prevIdx)

        // ATR(14) calculation
        val atrPeriod = min(14, closedBars.size - 1).coerceAtLeast(1)
        var trSum = 0.0
        for (i in (lastIdx - atrPeriod + 1)..lastIdx) {
            val cur = closedBars[i]
            val prevClose = closedBars[(i - 1).coerceAtLeast(0)].close
            val tr = maxOf(
                cur.high - cur.low,
                abs(cur.high - prevClose),
                abs(cur.low - prevClose),
            )
            trSum += tr
        }
        val atr14 = (trSum / atrPeriod).coerceAtLeast(closedBars[lastIdx].close * 0.0005)

        // Chikou Span Open Space (26 periods back + 6-bar forward corridor for horizontal freedom)
        val closeNow = closedBars[lastIdx].close
        val chikouIdx = (lastIdx - disp).coerceAtLeast(0)
        val corridorEnd = min(lastIdx, chikouIdx + 6)
        var chikouBullishOpen = true
        var chikouBearishOpen = true
        for (k in chikouIdx..corridorEnd) {
            val barK = closedBars[k]
            val kumoTopK = max(currentSpanAAt(k), currentSpanBAt(k))
            val kumoBotK = min(currentSpanAAt(k), currentSpanBAt(k))
            val tkTopK = max(tenkanAt(k), kijunAt(k))
            val tkBotK = min(tenkanAt(k), kijunAt(k))
            if (closeNow <= max(barK.high, max(kumoTopK, tkTopK))) {
                chikouBullishOpen = false
            }
            if (closeNow >= min(barK.low, min(kumoBotK, tkBotK))) {
                chikouBearishOpen = false
            }
        }

        // Local Chikou S/R Peak and Trough (Mark Tuttle's rule for flat SSB breakout confirmation)
        val lookbackStart = (lastIdx - config.kijunPeriod).coerceAtLeast(1)
        var chikouPeak = closedBars[lookbackStart].close
        var chikouTrough = closedBars[lookbackStart].close
        for (k in lookbackStart until lastIdx) {
            val c = closedBars[k].close
            if (c > chikouPeak) chikouPeak = c
            if (c < chikouTrough) chikouTrough = c
        }

        // Flatness detection (within 0.03% of price or 0.05 * ATR over 3 bars)
        val flatTol = max(closeNow * 0.0003, atr14 * 0.04)
        val spanB2 = currentSpanBAt((lastIdx - 2).coerceAtLeast(0))
        val isCurrentSpanBFlat = abs(currentSpanB - currentSpanBAt(prevIdx)) <= flatTol &&
            abs(currentSpanB - spanB2) <= flatTol
        val isKijunFlat = abs(kijun - prevKijun) <= flatTol
        val isTenkanFlat = abs(tenkan - prevTenkan) <= flatTol

        // Extract horizontal equilibrium plateaus (Kijun & SSB flat for >= 3 bars) — Karen Péloille
        val rawPlateaus = mutableListOf<Double>()
        for (i in max(3, lastIdx - 80)..lastIdx) {
            val kj = kijunAt(i)
            if (abs(kj - kijunAt(i - 1)) <= flatTol && abs(kj - kijunAt(i - 2)) <= flatTol) {
                rawPlateaus += kj
            }
            val ssb = currentSpanBAt(i)
            if (abs(ssb - currentSpanBAt(i - 1)) <= flatTol && abs(ssb - currentSpanBAt(i - 2)) <= flatTol) {
                rawPlateaus += ssb
            }
        }
        val mergedPlateaus = mergeNearbyLevels(rawPlateaus, max(atr14 * 0.35, closeNow * 0.0015))

        val cur = closedBars[lastIdx]
        val barRange = (cur.high - cur.low).coerceAtLeast(1e-8)
        val bodyRatio = abs(cur.close - cur.open) / barRange
        val clv = ((cur.close - cur.low) / barRange).coerceIn(0.0, 1.0)

        val volStart = (lastIdx - 19).coerceAtLeast(0)
        val avgVol20 = closedBars.subList(volStart, lastIdx + 1).map { it.volume }.average()
        val volRatio20 = if (avgVol20 > 0.0 && cur.volume > 0.0) cur.volume / avgVol20 else 1.0

        val kj3Idx = (lastIdx - 3).coerceAtLeast(0)
        val kijunSlopeAtr = (kijun - kijunAt(kj3Idx)) / (3.0 * atr14)
        val tenkanSlopeAtr = (tenkan - prevTenkan) / atr14
        val futThicknessAtr = abs(futureSpanA - futureSpanB) / atr14
        val curThicknessAtr = abs(currentSpanA - currentSpanB) / atr14
        val distKijunAtr = abs(cur.close - kijun) / atr14
        val cClampWarn = isKijunFlat && isCurrentSpanBFlat && distKijunAtr > config.maxKijunExtensionAtr * 1.05
        val katana = abs(tenkan - kijun) <= flatTol * 1.2

        val b1 = closedBars[(lastIdx - 1).coerceAtLeast(0)]
        val b2 = closedBars[(lastIdx - 2).coerceAtLeast(0)]
        val bullImpulse3 = listOf(b2, b1, cur).count { it.close > it.open } >= 2 && cur.close >= max(b1.close, b2.close)
        val bearImpulse3 = listOf(b2, b1, cur).count { it.close < it.open } >= 2 && cur.close <= min(b1.close, b2.close)

        // Kaufman Efficiency Ratio (ER14) — measures pure trend vs choppy sideways noise
        val erPeriod = min(14, lastIdx).coerceAtLeast(1)
        val netDisp = abs(cur.close - closedBars[lastIdx - erPeriod].close)
        var pathLen = 0.0
        for (i in (lastIdx - erPeriod + 1)..lastIdx) {
            pathLen += abs(closedBars[i].close - closedBars[i - 1].close)
        }
        val er14 = if (pathLen > 1e-8) (netDisp / pathLen).coerceIn(0.0, 1.0) else 0.5

        val candleTopBody = max(cur.open, cur.close)
        val candleBotBody = min(cur.open, cur.close)
        val upperWick = ((cur.high - candleTopBody) / barRange).coerceIn(0.0, 1.0)
        val lowerWick = ((candleBotBody - cur.low) / barRange).coerceIn(0.0, 1.0)

        // Detect recent Katana release (Tenkan & Kijun tightly compressed 1..3 bars ago, now expanding)
        val prev2Idx = (lastIdx - 2).coerceAtLeast(0)
        val prev3Idx = (lastIdx - 3).coerceAtLeast(0)
        val compressedRecently = abs(prevTenkan - prevKijun) <= atr14 * 0.18 ||
            abs(tenkanAt(prev2Idx) - kijunAt(prev2Idx)) <= atr14 * 0.18 ||
            abs(tenkanAt(prev3Idx) - kijunAt(prev3Idx)) <= atr14 * 0.18
        val katanaRelease = compressedRecently && abs(tenkan - kijun) >= abs(prevTenkan - prevKijun) && bodyRatio >= 0.32

        // ── ۱. بولینگر باند (20, 2.0) و فشردگی Squeeze (کتاب درایو ۱ — TuAnh و کتاب ۴ — Mukherjee) ──
        fun bbAt(idx: Int): Triple<Double, Double, Double> {
            val start = (idx - 19).coerceAtLeast(0)
            val slice = closedBars.subList(start, idx + 1)
            val mean = slice.map { it.close }.average()
            var varianceSum = 0.0
            for (b in slice) {
                val d = b.close - mean
                varianceSum += d * d
            }
            val std = sqrt(varianceSum / slice.size.coerceAtLeast(1))
            return Triple(mean, mean + 2.0 * std, mean - 2.0 * std)
        }
        val (bbMid20, bbUp20, bbLow20) = bbAt(lastIdx)
        val bbWidthNow = (bbUp20 - bbLow20) / bbMid20.coerceAtLeast(1e-8)
        var bwSum20 = 0.0
        var bwCount20 = 0
        var minRecentBw = bbWidthNow
        for (i in (lastIdx - 19).coerceAtLeast(0)..lastIdx) {
            val (m, u, l) = bbAt(i)
            val bw = (u - l) / m.coerceAtLeast(1e-8)
            bwSum20 += bw
            bwCount20++
            if (i >= (lastIdx - 4).coerceAtLeast(0) && bw < minRecentBw) {
                minRecentBw = bw
            }
        }
        val avgBw20 = if (bwCount20 > 0) bwSum20 / bwCount20 else bbWidthNow
        val isBbSqueeze = minRecentBw <= avgBw20 * 1.12 || (bbUp20 - bbLow20) <= atr14 * 5.0
        val marubozuBull = cur.close > cur.open && bodyRatio >= 0.42 && upperWick <= 0.32
        val marubozuBear = cur.close < cur.open && bodyRatio >= 0.42 && lowerWick <= 0.32

        // ── ۲. میانگین‌های متحرک EMA 13, 21, 34, 48, 89 و SMA 200 (کتاب‌های ۱، ۲، ۸ و ۹) ──
        fun computeEmaSeries(period: Int): DoubleArray {
            val out = DoubleArray(closedBars.size)
            val k = 2.0 / (period + 1.0)
            out[0] = closedBars[0].close
            for (i in 1..lastIdx) {
                out[i] = closedBars[i].close * k + out[i - 1] * (1.0 - k)
            }
            return out
        }
        val ema12Arr = computeEmaSeries(12)
        val ema13Arr = computeEmaSeries(13)
        val ema21Arr = computeEmaSeries(21)
        val ema26Arr = computeEmaSeries(26)
        val ema34Arr = computeEmaSeries(34)
        val ema48Arr = computeEmaSeries(48)
        val ema89Arr = computeEmaSeries(89)
        val ema13 = ema13Arr[lastIdx]
        val ema21 = ema21Arr[lastIdx]
        val ema34 = ema34Arr[lastIdx]
        val ema48 = ema48Arr[lastIdx]
        val ema89 = ema89Arr[lastIdx]
        val sma200Start = (lastIdx - 199).coerceAtLeast(0)
        val sma200 = closedBars.subList(sma200Start, lastIdx + 1).map { it.close }.average()
        val ema21SlopeAtr = (ema21 - ema21Arr[(lastIdx - 3).coerceAtLeast(0)]) / (3.0 * atr14)

        // ── ۳. محاسبه RSI(14) و RSI(7) سریع + پرش از خط ۵۰ و قله/دره RSI (کتاب‌های ۱، ۲ و ۴) ──
        fun computeRsiSeries(period: Int): DoubleArray {
            val out = DoubleArray(closedBars.size) { 50.0 }
            if (closedBars.size < 2) return out
            var avgGain = 0.0
            var avgLoss = 0.0
            val initEnd = min(period, lastIdx)
            for (i in 1..initEnd) {
                val diff = closedBars[i].close - closedBars[i - 1].close
                if (diff > 0) avgGain += diff else avgLoss -= diff
            }
            avgGain /= initEnd.toDouble()
            avgLoss /= initEnd.toDouble()
            fun toRsi(g: Double, l: Double): Double = when {
                l <= 1e-12 && g <= 1e-12 -> 50.0
                l <= 1e-12 -> 99.0
                else -> 100.0 - (100.0 / (1.0 + (g / l)))
            }
            out[initEnd] = toRsi(avgGain, avgLoss)
            for (i in (initEnd + 1)..lastIdx) {
                val diff = closedBars[i].close - closedBars[i - 1].close
                val g = if (diff > 0) diff else 0.0
                val l = if (diff < 0) -diff else 0.0
                avgGain = (avgGain * (period - 1) + g) / period.toDouble()
                avgLoss = (avgLoss * (period - 1) + l) / period.toDouble()
                out[i] = toRsi(avgGain, avgLoss)
            }
            return out
        }
        val rsi14Arr = computeRsiSeries(14)
        val rsi7Arr = computeRsiSeries(7)
        val rsi14 = rsi14Arr[lastIdx]
        val prevRsi14 = rsi14Arr[prevIdx]
        var peakRsi14 = rsi14
        var troughRsi14 = rsi14
        for (i in (lastIdx - 8).coerceAtLeast(0) until lastIdx) {
            if (rsi14Arr[i] > peakRsi14) peakRsi14 = rsi14Arr[i]
            if (rsi14Arr[i] < troughRsi14) troughRsi14 = rsi14Arr[i]
        }
        val rsi7 = rsi7Arr[lastIdx]
        val prevRsi7 = rsi7Arr[prevIdx]
        var minRsi7Recent = rsi7
        var maxRsi7Recent = rsi7
        for (i in (lastIdx - 4).coerceAtLeast(0)..prevIdx) {
            if (rsi7Arr[i] < minRsi7Recent) minRsi7Recent = rsi7Arr[i]
            if (rsi7Arr[i] > maxRsi7Recent) maxRsi7Recent = rsi7Arr[i]
        }
        val rsi7BullBounce = rsi7 >= 50.0 && (rsi7 >= prevRsi7 - 1.5 || minRsi7Recent <= 58.0)
        val rsi7BearBounce = rsi7 <= 50.0 && (rsi7 <= prevRsi7 + 1.5 || maxRsi7Recent >= 42.0)

        // ── ۴. فرکتال‌های ۵ کندلی بیل ویلیامز (کتاب درایو ۲ — 15minstrategy.pdf) ──
        var downFractal = closedBars.subList((lastIdx - 8).coerceAtLeast(0), lastIdx + 1).minOf { it.low }
        var upFractal = closedBars.subList((lastIdx - 8).coerceAtLeast(0), lastIdx + 1).maxOf { it.high }
        for (i in (lastIdx - 2) downTo max(2, lastIdx - 28)) {
            val cBar = closedBars[i]
            if (cBar.low <= closedBars[i - 1].low && cBar.low <= closedBars[i - 2].low &&
                cBar.low <= closedBars[i + 1].low && cBar.low <= closedBars[i + 2].low
            ) {
                downFractal = cBar.low
                break
            }
        }
        for (i in (lastIdx - 2) downTo max(2, lastIdx - 28)) {
            val cBar = closedBars[i]
            if (cBar.high >= closedBars[i - 1].high && cBar.high >= closedBars[i - 2].high &&
                cBar.high >= closedBars[i + 1].high && cBar.high >= closedBars[i + 2].high
            ) {
                upFractal = cBar.high
                break
            }
        }

        // ── ۵. تنظیمات ایچیموکو (10,30,60) کتاب Keenbase و تنظیمات سریع (7,22,44) کتاب Koonitz ──
        val tenkan10 = midpoint(lastIdx - 9, lastIdx)
        val kijun30 = midpoint(lastIdx - 29, lastIdx)
        val src30 = (lastIdx - 29).coerceAtLeast(0)
        val senkouA103060 = (midpoint(src30 - 9, src30) + midpoint(src30 - 29, src30)) / 2.0
        val senkouB60 = midpoint(src30 - 59, src30)

        val tenkan7 = midpoint(lastIdx - 6, lastIdx)
        val kijun22 = midpoint(lastIdx - 21, lastIdx)
        val src22 = (lastIdx - 21).coerceAtLeast(0)
        val senkouA72244 = (midpoint(src22 - 6, src22) + midpoint(src22 - 21, src22)) / 2.0
        val senkouB44 = midpoint(src22 - 43, src22)

        // ── ۶. اندیکاتورهای MACD (12, 26, 9) و DMI/ADX (14) (کتاب‌های ۸ و ۹ — Charles G. Koonitz) ──
        val macdSeries = DoubleArray(closedBars.size) { idx -> ema12Arr[idx] - ema26Arr[idx] }
        val macdSigArr = DoubleArray(closedBars.size)
        val kMacd = 2.0 / 10.0
        macdSigArr[0] = macdSeries[0]
        for (i in 1..lastIdx) {
            macdSigArr[i] = macdSeries[i] * kMacd + macdSigArr[i - 1] * (1.0 - kMacd)
        }
        val macdLine = macdSeries[lastIdx]
        val macdSignal = macdSigArr[lastIdx]
        val macdHist = macdLine - macdSignal

        var plusDmSum = 0.0
        var minusDmSum = 0.0
        var trDmSum = 0.0
        val dmiLookback = min(14, lastIdx).coerceAtLeast(1)
        val dxList = mutableListOf<Double>()
        for (i in (lastIdx - min(27, lastIdx) + 1)..lastIdx) {
            val cBar = closedBars[i]
            val pBar = closedBars[(i - 1).coerceAtLeast(0)]
            val upMove = cBar.high - pBar.high
            val downMove = pBar.low - cBar.low
            val pDm = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            val mDm = if (downMove > upMove && downMove > 0.0) downMove else 0.0
            val trVal = maxOf(cBar.high - cBar.low, abs(cBar.high - pBar.close), abs(cBar.low - pBar.close))
            if (i > lastIdx - dmiLookback) {
                plusDmSum += pDm
                minusDmSum += mDm
                trDmSum += trVal
            }
            val denom = trVal.coerceAtLeast(1e-8)
            val pDiBar = (pDm / denom) * 100.0
            val mDiBar = (mDm / denom) * 100.0
            val diSum = (pDiBar + mDiBar).coerceAtLeast(1e-8)
            dxList += (abs(pDiBar - mDiBar) / diSum) * 100.0
        }
        val safeTrDm = trDmSum.coerceAtLeast(1e-8)
        val plusDi14 = (plusDmSum / safeTrDm) * 100.0
        val minusDi14 = (minusDmSum / safeTrDm) * 100.0
        val rawAdx = if (dxList.isNotEmpty()) dxList.takeLast(14).average() else 25.0
        val adx14 = max(rawAdx, er14 * 45.0)

        // ── ۷. عبور چیکو از کومو، الگوهای کندل‌استیک ژاپنی و امتیاز معیار کلی (کتاب‌های ۴، ۵، ۶، ۷ و ۸) ──
        val kumoTopAtChikou = max(currentSpanAAt(chikouIdx), currentSpanBAt(chikouIdx))
        val kumoBotAtChikou = min(currentSpanAAt(chikouIdx), currentSpanBAt(chikouIdx))
        val chikouAboveCloud26 = closeNow > kumoTopAtChikou
        val chikouBelowCloud26 = closeNow < kumoBotAtChikou

        val bullishEngulfing = cur.close > cur.open && b1.close <= b1.open && cur.close >= b1.open && cur.open <= b1.close
        val piercingPattern = cur.close > cur.open && b1.close < b1.open && cur.close >= (b1.open + b1.close) / 2.0
        val morningStarOrHammer = (cur.close > cur.open && lowerWick >= 0.35 && upperWick <= 0.25) || bullImpulse3
        val hasBullCandlePattern = bullishEngulfing || piercingPattern || morningStarOrHammer || (cur.close > cur.open && clv >= 0.55)

        val bearishEngulfing = cur.close < cur.open && b1.close >= b1.open && cur.close <= b1.open && cur.open >= b1.close
        val darkCloudCover = cur.close < cur.open && b1.close > b1.open && cur.close <= (b1.open + b1.close) / 2.0
        val eveningStarOrShooting = (cur.close < cur.open && upperWick >= 0.35 && lowerWick <= 0.25) || bearImpulse3
        val hasBearCandlePattern = bearishEngulfing || darkCloudCover || eveningStarOrShooting || (cur.close < cur.open && clv <= 0.45)

        val isBullMode = closeNow >= kijun
        var kellySignals = 0
        if (if (isBullMode) tenkan >= kijun else tenkan <= kijun) kellySignals++
        if (if (isBullMode) closeNow > kijun else closeNow < kijun) kellySignals++
        if (if (isBullMode) closeNow > max(currentSpanA, currentSpanB) else closeNow < min(currentSpanA, currentSpanB)) kellySignals += 2
        if (if (isBullMode) futureSpanA > futureSpanB else futureSpanA < futureSpanB) kellySignals++
        val clampedKellySignals = kellySignals.coerceIn(1, 5)
        val kellyWinProb = when (clampedKellySignals) {
            1 -> 48
            2 -> 57
            3 -> 62
            4 -> 69
            else -> 76
        }

        return IchimokuBarSnapshot(
            time = cur.time,
            open = cur.open,
            high = cur.high,
            low = cur.low,
            close = cur.close,
            tenkan = round2(tenkan),
            kijun = round2(kijun),
            prevTenkan = round2(prevTenkan),
            prevKijun = round2(prevKijun),
            currentSpanA = round2(currentSpanA),
            currentSpanB = round2(currentSpanB),
            futureSpanA = round2(futureSpanA),
            futureSpanB = round2(futureSpanB),
            prevFutureSpanA = round2(prevFutureSpanA),
            prevFutureSpanB = round2(prevFutureSpanB),
            atr14 = round2(atr14),
            chikouBullishOpenSpace = chikouBullishOpen,
            chikouBearishOpenSpace = chikouBearishOpen,
            chikouResistancePeak = round2(chikouPeak),
            chikouSupportTrough = round2(chikouTrough),
            isCurrentSpanBFlat = isCurrentSpanBFlat,
            isKijunFlat = isKijunFlat,
            isTenkanFlat = isTenkanFlat,
            flatEquilibriumLevels = mergedPlateaus.map(::round2),
            candleBodyRatio = round2(bodyRatio),
            closeLocationValue = round2(clv),
            volumeRatio20 = round2(volRatio20),
            kijunSlopeAtrPerBar = round2(kijunSlopeAtr),
            tenkanSlopeAtrPerBar = round2(tenkanSlopeAtr),
            futureKumoThicknessAtr = round2(futThicknessAtr),
            currentKumoThicknessAtr = round2(curThicknessAtr),
            isCClampWarning = cClampWarn,
            isKatanaEquilibrium = katana,
            threeCandleBullishImpulse = bullImpulse3,
            threeCandleBearishImpulse = bearImpulse3,
            efficiencyRatio14 = round2(er14),
            upperWickRatio = round2(upperWick),
            lowerWickRatio = round2(lowerWick),
            recentKatanaRelease = katanaRelease,
            bbMiddle20 = round2(bbMid20),
            bbUpper20 = round2(bbUp20),
            bbLower20 = round2(bbLow20),
            bbBandwidthRatio = round2(bbWidthNow),
            isBollingerSqueeze = isBbSqueeze,
            isMarubozuBreakoutBull = marubozuBull,
            isMarubozuBreakoutBear = marubozuBear,
            ema13 = round2(ema13),
            ema21 = round2(ema21),
            ema34 = round2(ema34),
            ema48 = round2(ema48),
            ema89 = round2(ema89),
            sma200 = round2(sma200),
            ema21SlopeAtrPerBar = round2(ema21SlopeAtr),
            rsi14 = round2(rsi14),
            prevRsi14 = round2(prevRsi14),
            recentPeakRsi14 = round2(peakRsi14),
            recentTroughRsi14 = round2(troughRsi14),
            rsi7 = round2(rsi7),
            prevRsi7 = round2(prevRsi7),
            rsi7Bullish50Bounce = rsi7BullBounce,
            rsi7Bearish50Bounce = rsi7BearBounce,
            lastDownFractalLow = round2(downFractal),
            lastUpFractalHigh = round2(upFractal),
            tenkan10 = round2(tenkan10),
            kijun30 = round2(kijun30),
            senkouA103060 = round2(senkouA103060),
            senkouB60 = round2(senkouB60),
            tenkan7 = round2(tenkan7),
            kijun22 = round2(kijun22),
            senkouA72244 = round2(senkouA72244),
            senkouB44 = round2(senkouB44),
            macdLine = round2(macdLine),
            macdSignal = round2(macdSignal),
            macdHistogram = round2(macdHist),
            plusDi14 = round2(plusDi14),
            minusDi14 = round2(minusDi14),
            adx14 = round2(adx14),
            chikouAboveCloud26 = chikouAboveCloud26,
            chikouBelowCloud26 = chikouBelowCloud26,
            hasBullishCandlestickPattern = hasBullCandlePattern,
            hasBearishCandlestickPattern = hasBearCandlePattern,
            kellySignalCount = clampedKellySignals,
            kellyWinProbabilityPercent = kellyWinProb,
        )
    }

    /**
     * ساخت سری کامل خطوط ایچیموکو و ابر آینده (۲۶ دوره جلوتر) برای رسم چارت بومی تعاملی.
     */
    fun computeChartSeries(
        candlesInput: List<Candle>,
        config: BaseStrategyConfig = BaseStrategyConfig(),
    ): IchimokuChartSeries {
        val bars = candlesInput
            .filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.high >= it.low && it.close > 0.0 }
            .sortedBy { it.time }
        if (bars.isEmpty()) return IchimokuChartSeries(emptyList(), emptyList())

        fun midpoint(fromIdx: Int, toIdx: Int): Double {
            val start = fromIdx.coerceIn(0, toIdx)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (k in start..toIdx) {
                if (bars[k].high > hh) hh = bars[k].high
                if (bars[k].low < ll) ll = bars[k].low
            }
            return (hh + ll) / 2.0
        }
        fun tenkanAt(idx: Int): Double = midpoint(idx - (config.tenkanPeriod - 1), idx)
        fun kijunAt(idx: Int): Double = midpoint(idx - (config.kijunPeriod - 1), idx)
        fun spanBAtCreation(idx: Int): Double = midpoint(idx - (config.senkouBPeriod - 1), idx)
        fun spanAAtCreation(idx: Int): Double = (tenkanAt(idx) + kijunAt(idx)) / 2.0

        val disp = (config.displacement - 1).coerceAtLeast(1)
        val overlays = bars.mapIndexed { idx, candle ->
            val src = (idx - disp).coerceAtLeast(0)
            val chikou = if (idx + disp <= bars.lastIndex) bars[idx + disp].close else null
            IchimokuChartBarOverlay(
                index = idx,
                candle = candle,
                tenkan = tenkanAt(idx),
                kijun = kijunAt(idx),
                currentSpanA = spanAAtCreation(src),
                currentSpanB = spanBAtCreation(src),
                chikouPrice = chikou,
            )
        }

        val futurePoints = mutableListOf<FutureKumoPoint>()
        val startFutureSrc = (bars.lastIndex - disp + 1).coerceAtLeast(0)
        var step = 1
        for (srcIdx in startFutureSrc..bars.lastIndex) {
            futurePoints += FutureKumoPoint(
                stepAhead = step++,
                spanA = spanAAtCreation(srcIdx),
                spanB = spanBAtCreation(srcIdx),
            )
        }
        return IchimokuChartSeries(bars = overlays, futureKumo = futurePoints)
    }

    /**
     * استخراج ساختار موج N گوایچی هوسودا (A -> B -> C)، بررسی نقطه S و محاسبه ۴ هدف قیمتی V, N, E, NT
     * به همراه فاصله تا اعداد زمانی پایه هوسودا (9, 17, 26, 33, 42, 65, 76).
     */
    fun computeHosodaWave(
        candlesInput: List<Candle>,
        direction: SignalAction,
        snapshot: IchimokuBarSnapshot,
    ): HosodaWaveProjection? {
        val bars = candlesInput.filter { it.closed }.ifEmpty { candlesInput }.takeLast(65)
        if (bars.size < 12 || direction == SignalAction.NO_TRADE) return null
        val lastIdx = bars.lastIndex

        if (direction == SignalAction.BUY) {
            // Find recent pullback low C in the last 6 bars, swing high B before C, and impulse origin low A before B
            val cSearchStart = (lastIdx - 6).coerceAtLeast(4)
            var idxC = cSearchStart
            for (i in cSearchStart..lastIdx) {
                if (bars[i].low <= bars[idxC].low) idxC = i
            }
            val bSearchStart = (idxC - 22).coerceAtLeast(2)
            var idxB = bSearchStart
            for (i in bSearchStart until idxC) {
                if (bars[i].high >= bars[idxB].high) idxB = i
            }
            val aSearchStart = (idxB - 45).coerceAtLeast(0)
            var idxA = aSearchStart
            for (i in aSearchStart..idxB) {
                if (bars[i].low <= bars[idxA].low) idxA = i
            }

            val rawA = bars[idxA].low
            val rawB = max(bars[idxB].high, snapshot.close)
            val rawC = min(bars[idxC].low, snapshot.tenkan)
            val waveHeight = max(rawB - rawA, max(snapshot.distanceToKijun * 2.4, snapshot.atr14 * 3.0))
            val pointA = rawB - waveHeight
            val pointB = rawB
            val pointC = rawC.coerceIn(pointA + snapshot.atr14 * 0.3, pointB - snapshot.atr14 * 0.3)

            val targetV = pointB + (pointB - pointC)
            val targetN = pointC + (pointB - pointA)
            val targetE = pointB + (pointB - pointA)
            val targetNT = pointC + (pointC - pointA)

            val barsSinceA = (lastIdx - idxA + 1).coerceAtLeast(1)
            val nearestCycle = HOSODA_TIME_CYCLES.minByOrNull { abs(it - barsSinceA) } ?: 9
            val nearChangeDay = abs(nearestCycle - barsSinceA) <= 1

            return HosodaWaveProjection(
                direction = SignalAction.BUY,
                pointA = round2(pointA),
                pointB = round2(pointB),
                pointC = round2(pointC),
                sPointHeld = pointC >= snapshot.kumoBottom,
                targetV = round2(max(targetV, snapshot.close + snapshot.atr14 * 1.5)),
                targetN = round2(max(targetN, snapshot.close + snapshot.atr14 * 2.2)),
                targetE = round2(max(targetE, snapshot.close + snapshot.atr14 * 3.0)),
                targetNT = round2(max(targetNT, snapshot.close + snapshot.atr14 * 1.2)),
                barsSinceWaveStart = barsSinceA,
                nearestTimeCycleNumber = nearestCycle,
                isNearTimeCycleChangeDay = nearChangeDay,
            )
        } else {
            // SELL wave A (high) -> B (low) -> C (pullback high)
            val cSearchStart = (lastIdx - 6).coerceAtLeast(4)
            var idxC = cSearchStart
            for (i in cSearchStart..lastIdx) {
                if (bars[i].high >= bars[idxC].high) idxC = i
            }
            val bSearchStart = (idxC - 22).coerceAtLeast(2)
            var idxB = bSearchStart
            for (i in bSearchStart until idxC) {
                if (bars[i].low <= bars[idxB].low) idxB = i
            }
            val aSearchStart = (idxB - 45).coerceAtLeast(0)
            var idxA = aSearchStart
            for (i in aSearchStart..idxB) {
                if (bars[i].high >= bars[idxA].high) idxA = i
            }

            val rawA = bars[idxA].high
            val rawB = min(bars[idxB].low, snapshot.close)
            val rawC = max(bars[idxC].high, snapshot.tenkan)
            val waveHeight = max(rawA - rawB, max(snapshot.distanceToKijun * 2.4, snapshot.atr14 * 3.0))
            val pointA = rawB + waveHeight
            val pointB = rawB
            val pointC = rawC.coerceIn(pointB + snapshot.atr14 * 0.3, pointA - snapshot.atr14 * 0.3)

            val targetV = pointB - (pointC - pointB)
            val targetN = pointC - (pointA - pointB)
            val targetE = pointB - (pointA - pointB)
            val targetNT = pointC - (pointA - pointC)

            val barsSinceA = (lastIdx - idxA + 1).coerceAtLeast(1)
            val nearestCycle = HOSODA_TIME_CYCLES.minByOrNull { abs(it - barsSinceA) } ?: 9
            val nearChangeDay = abs(nearestCycle - barsSinceA) <= 1

            return HosodaWaveProjection(
                direction = SignalAction.SELL,
                pointA = round2(pointA),
                pointB = round2(pointB),
                pointC = round2(pointC),
                sPointHeld = pointC <= snapshot.kumoTop,
                targetV = round2(min(targetV, snapshot.close - snapshot.atr14 * 1.5).coerceAtLeast(0.00001)),
                targetN = round2(min(targetN, snapshot.close - snapshot.atr14 * 2.2).coerceAtLeast(0.00001)),
                targetE = round2(min(targetE, snapshot.close - snapshot.atr14 * 3.0).coerceAtLeast(0.00001)),
                targetNT = round2(min(targetNT, snapshot.close - snapshot.atr14 * 1.2).coerceAtLeast(0.00001)),
                barsSinceWaveStart = barsSinceA,
                nearestTimeCycleNumber = nearestCycle,
                isNearTimeCycleChangeDay = nearChangeDay,
            )
        }
    }

    /**
     * هم‌ترازسازی پایه قیمتی کندل‌های آتی (مانند `GC=F` طلا، `SI=F` نقره و `YM=F` داوجونز)
     * با قیمت لحظه‌ای اسپات (`livePriceOverride`) تا اختلاف پایه فیوچرز و اسپات (Futures-Spot Basis)
     * باعث خطای فاصله کاذب از کیجون/اسنایپر و رد شدن سیگنال طلا و نقره نشود.
     */
    fun alignCandlesToSpotBasis(
        candles: List<Candle>,
        referenceClose: Double?,
        liveSpotPrice: Double?,
    ): List<Candle> {
        if (candles.isEmpty() || referenceClose == null || liveSpotPrice == null) return candles
        if (!referenceClose.isFinite() || !liveSpotPrice.isFinite() || referenceClose <= 0.0 || liveSpotPrice <= 0.0) {
            return candles
        }
        val basisDelta = liveSpotPrice - referenceClose
        val relDiff = abs(basisDelta) / referenceClose
        // Align any basis discrepancy larger than 0.03% (3 bps) up to 12% so USD/USDT or Futures/Spot basis never skews SL/Entry
        if (abs(basisDelta) <= referenceClose * 0.0003 || relDiff > 0.12) {
            return candles
        }
        return candles.map { bar ->
            val o = (bar.open + basisDelta).coerceAtLeast(0.00001)
            val h = (bar.high + basisDelta).coerceAtLeast(o)
            val l = (bar.low + basisDelta).coerceIn(0.00001, h)
            val c = (bar.close + basisDelta).coerceIn(l, h)
            bar.copy(open = o, high = h, low = l, close = c)
        }
    }

    /**
     * حداقل فاصلهٔ ایمن ساختاری حد ضرر (Anti-Premature Stop-Out Floor):
     * در ارزهای کریپتو (به‌خصوص تایم‌فریم ۵ و ۱۵ دقیقه)، نوسانات لحظه‌ای و اسپرد/بیسیس بین صرافی‌ها
     * می‌تواند استاپ‌های خیلی نزدیک را در چند ثانیه اول فعال کند. این تابع تضمین می‌کند حد ضرر حداقل به اندازهٔ
     * ساختار کیجون/کومو + ضریب امن ATR14 و کف درصدی ایمن از هم نقطهٔ ورود اسنایپر و هم قیمت لحظه‌ای فاصله داشته باشد.
     */
    fun minSafeStructuralStopDistance(
        symbol: String,
        referencePrice: Double,
        trigAtr14: Double,
        stratAtr14: Double = trigAtr14,
        triggerInterval: Interval = Interval.M5,
    ): Double {
        val norm = V1Universe.normalize(symbol)
        val safePrice = if (referencePrice.isFinite() && referencePrice > 0.0) referencePrice else 1.0
        val safeTrigAtr = if (trigAtr14.isFinite() && trigAtr14 > 0.0) trigAtr14 else safePrice * 0.003
        val safeStratAtr = if (stratAtr14.isFinite() && stratAtr14 > 0.0) stratAtr14 else safeTrigAtr
        val venueMin = VenueSpecs.minStopDistance(norm, safePrice)

        return when (AssetClass.of(norm)) {
            AssetClass.CRYPTO -> {
                val isMajorCrypto = norm.startsWith("BTC") || norm.startsWith("ETH")
                val pctFloor = when {
                    isMajorCrypto && triggerInterval == Interval.M5 -> safePrice * 0.0055 // 0.55% (~$500 on $90k BTC)
                    isMajorCrypto -> safePrice * 0.0065 // 0.65% on 15m BTC/ETH
                    triggerInterval == Interval.M5 -> safePrice * 0.0075 // 0.75% on 5m SOL/BNB/XRP
                    else -> safePrice * 0.0085 // 0.85% on 15m SOL/BNB/XRP
                }
                val atrFloor = if (isMajorCrypto) {
                    max(safeTrigAtr * 1.45, safeStratAtr * 0.55)
                } else {
                    max(safeTrigAtr * 1.55, safeStratAtr * 0.60)
                }
                max(venueMin * 4.0, max(pctFloor, atrFloor))
            }

            AssetClass.COMMODITY -> {
                val pctFloor = if (norm.startsWith("XAG")) safePrice * 0.0035 else safePrice * 0.0020
                max(venueMin * 3.0, max(pctFloor, max(safeTrigAtr * 0.85, safeStratAtr * 0.35)))
            }

            AssetClass.STOCK -> {
                max(venueMin * 3.0, max(safePrice * 0.0018, max(safeTrigAtr * 0.80, safeStratAtr * 0.32)))
            }

            AssetClass.FOREX -> {
                max(venueMin * 3.0, max(safePrice * 0.0008, max(safeTrigAtr * 0.75, safeStratAtr * 0.30)))
            }
        }
    }

    /**
     * موتور «متود جامع یکپارچه کتاب‌های درایو ۱ تا ۹» با الگوریتم روز
     * `Adaptive Bayesian Log-Odds 9-Book Consensus + Regime-Weighted Stacking`:
     * ارزیابی هم‌زمان هر ۹ کتاب PDF گوگل درایو و استخراج احتمال برد بیزین، سنگر حد ضرر ترکیبی و تارگت بهینه.
     */
    fun evaluateUnified9BookConsensus(
        isLong: Boolean,
        trigSnap: IchimokuBarSnapshot,
        stratSnap: IchimokuBarSnapshot,
        macroSnap: IchimokuBarSnapshot? = null,
    ): Unified9BookConsensusResult {
        val atr = trigSnap.atr14.coerceAtLeast(1e-8)

        // ۱. کتاب درایو ۱ (TuAnh): BB(20,2) Squeeze/Band + EMA(89)/SMA(200) + RSI(14) > 50
        val book1 = if (isLong) {
            trigSnap.close >= trigSnap.bbMiddle20 &&
                trigSnap.close >= trigSnap.ema89 - atr * 0.15 &&
                trigSnap.rsi14 >= 50.0 &&
                (trigSnap.isBollingerSqueeze || trigSnap.isMarubozuBreakoutBull || trigSnap.close > trigSnap.kumoTop)
        } else {
            trigSnap.close <= trigSnap.bbMiddle20 &&
                trigSnap.close <= trigSnap.ema89 + atr * 0.15 &&
                trigSnap.rsi14 <= 50.0 &&
                (trigSnap.isBollingerSqueeze || trigSnap.isMarubozuBreakoutBear || trigSnap.close < trigSnap.kumoBottom)
        }

        // ۲. کتاب درایو ۲ (Foka 15m/Day): بیرون کومو + شیب EMA(21) و کیجون + پرش RSI(7) از خط ۵۰ + فرکتال ۵ کندلی
        val book2 = if (isLong) {
            trigSnap.close > trigSnap.kumoTop &&
                trigSnap.close >= trigSnap.ema21 - atr * 0.12 &&
                trigSnap.ema21SlopeAtrPerBar > -0.01 &&
                (trigSnap.rsi7Bullish50Bounce || trigSnap.rsi7 >= 51.5) &&
                trigSnap.lastDownFractalLow < trigSnap.close
        } else {
            trigSnap.close < trigSnap.kumoBottom &&
                trigSnap.close <= trigSnap.ema21 + atr * 0.12 &&
                trigSnap.ema21SlopeAtrPerBar < 0.01 &&
                (trigSnap.rsi7Bearish50Bounce || trigSnap.rsi7 <= 48.5) &&
                trigSnap.lastUpFractalHigh > trigSnap.close
        }

        // ۳. کتاب درایو ۳ (Keenbase 10-30-60): کراس نهادی Tenkan(10)/Kijun(30) + بیرون Kumo(60)
        val book3 = if (isLong) {
            trigSnap.tenkan10 >= trigSnap.kijun30 &&
                trigSnap.close > trigSnap.kumoTop103060 &&
                stratSnap.close >= stratSnap.kumoBottom103060
        } else {
            trigSnap.tenkan10 <= trigSnap.kijun30 &&
                trigSnap.close < trigSnap.kumoBottom103060 &&
                stratSnap.close <= stratSnap.kumoTop103060
        }

        // ۴. کتاب درایو ۴ (Mukherjee Kelly & RSI): حداقل ۳ سیگنال طوفان کامل (Kelly >= 62%) + الگوی شمعی + سلامت RSI
        val book4 = trigSnap.kellySignalCount >= 3 && if (isLong) {
            trigSnap.tenkan >= trigSnap.kijun &&
                trigSnap.rsi14 >= 50.0 &&
                trigSnap.rsiPeakDropLong < 10.5 &&
                (trigSnap.hasBullishCandlestickPattern || trigSnap.candleBodyRatio >= 0.30)
        } else {
            trigSnap.tenkan <= trigSnap.kijun &&
                trigSnap.rsi14 <= 50.0 &&
                trigSnap.rsiTroughRiseShort < 11.5 &&
                (trigSnap.hasBearishCandlestickPattern || trigSnap.candleBodyRatio >= 0.30)
        }

        // ۵. کتاب درایو ۵ (Mukherjee Clouds & Chikou Filter): خط ماشه تنکان + کیجون غیرفلت + آزادی چیکو
        val book5 = !trigSnap.isKijunFlat && if (isLong) {
            trigSnap.close >= trigSnap.tenkan &&
                trigSnap.kijunSlopeAtrPerBar >= -0.01 &&
                trigSnap.chikouBullishOpenSpace
        } else {
            trigSnap.close <= trigSnap.tenkan &&
                trigSnap.kijunSlopeAtrPerBar <= 0.01 &&
                trigSnap.chikouBearishOpenSpace
        }

        // ۶. کتاب درایو ۶ (HowToTrade Key Level & Kumo Twist): شکست کامل کانال کومو + Twist هم‌جهت ابر آینده
        val book6 = if (isLong) {
            trigSnap.close > trigSnap.kumoTop &&
                stratSnap.close > stratSnap.kumoTop &&
                trigSnap.isFutureKumoBullish &&
                stratSnap.isFutureKumoBullish
        } else {
            trigSnap.close < trigSnap.kumoBottom &&
                stratSnap.close < stratSnap.kumoBottom &&
                trigSnap.isFutureKumoBearish &&
                stratSnap.isFutureKumoBearish
        }

        // ۷. کتاب درایو ۷ (TSG 4-Step Quick Profit): کلوز بیرون کومو + کراس TK + چیکو بالای/زیر کومو ۲۶ دوره قبل
        val book7 = if (isLong) {
            trigSnap.close > trigSnap.kumoTop &&
                trigSnap.tenkan >= trigSnap.kijun &&
                trigSnap.chikouAboveCloud26
        } else {
            trigSnap.close < trigSnap.kumoBottom &&
                trigSnap.tenkan <= trigSnap.kijun &&
                trigSnap.chikouBelowCloud26
        }

        // ۸. کتاب درایو ۸ (Koonitz 5 Golden Rules + DMI/MACD + EMA34): ۵ قانون کونیتز با ADX/DMI یا MACD
        val book8 = if (isLong) {
            trigSnap.close > trigSnap.kijun &&
                trigSnap.close > trigSnap.kumoTop &&
                trigSnap.chikouAboveCloud26 &&
                trigSnap.close >= trigSnap.ema34 &&
                (trigSnap.isDmiBullish || trigSnap.isMacdBullish)
        } else {
            trigSnap.close < trigSnap.kijun &&
                trigSnap.close < trigSnap.kumoBottom &&
                trigSnap.chikouBelowCloud26 &&
                trigSnap.close <= trigSnap.ema34 &&
                (trigSnap.isDmiBearish || trigSnap.isMacdBearish)
        }

        // ۹. کتاب درایو ۹ (Koonitz Triple Screen 7-22-44 + EMA 13/34/48): هم‌جهتی ۳ تایم‌فریم و ایچیموکوی سریع
        val macroTripleOk = if (macroSnap == null) {
            true
        } else if (isLong) {
            !macroSnap.isPriceBelowKumo && macroSnap.ema13 >= macroSnap.ema48 - macroSnap.atr14 * 0.25
        } else {
            !macroSnap.isPriceAboveKumo && macroSnap.ema13 <= macroSnap.ema48 + macroSnap.atr14 * 0.25
        }
        val book9 = macroTripleOk && if (isLong) {
            trigSnap.tenkan7 >= trigSnap.kijun22 &&
                trigSnap.close > trigSnap.kumoTop72244 &&
                trigSnap.ema13 >= trigSnap.ema34 &&
                stratSnap.ema13 >= stratSnap.ema34 - stratSnap.atr14 * 0.08
        } else {
            trigSnap.tenkan7 <= trigSnap.kijun22 &&
                trigSnap.close < trigSnap.kumoBottom72244 &&
                trigSnap.ema13 <= trigSnap.ema34 &&
                stratSnap.ema13 <= stratSnap.ema34 + stratSnap.atr14 * 0.08
        }

        val flags = listOf(book1, book2, book3, book4, book5, book6, book7, book8, book9)
        val count = flags.count { it }

        // Adaptive Bayesian Log-Odds Pooling with Regime Weighting (Squeeze vs Trend vs Pullback)
        val isSqueezeRegime = trigSnap.isBollingerSqueeze
        val isStrongAdxRegime = trigSnap.adx14 >= 22.0
        var logOdds = 0.15 // Base prior (~53.7%)
        if (book1) logOdds += if (isSqueezeRegime) 0.42 else 0.26
        if (book2) logOdds += 0.34
        if (book3) logOdds += 0.36
        if (book4) logOdds += if (trigSnap.kellySignalCount >= 4) 0.38 else 0.28
        if (book5) logOdds += 0.30
        if (book6) logOdds += 0.26
        if (book7) logOdds += 0.28
        if (book8) logOdds += if (isStrongAdxRegime) 0.40 else 0.30
        if (book9) logOdds += 0.36
        if (!book5 && trigSnap.isKijunFlat) logOdds -= 0.35

        val bayesianProb = (100.0 / (1.0 + kotlin.math.exp(-logOdds))).coerceIn(18.0, 97.5)

        // Composite Sniper Anchor (Weighted median of Tenkan9, Tenkan10, Tenkan7, EMA21)
        val anchors = listOf(trigSnap.tenkan, trigSnap.tenkan10, trigSnap.tenkan7, trigSnap.ema21).sorted()
        val compositeAnchor = round2((anchors[1] + anchors[2]) / 2.0)

        // Composite Fortress Stop-Loss: combines Fractal (Book 2), Kijun30±0.4ATR (Book 3), EMA34 (Book 8/9), and BB Mid (Book 1)
        val compositeStop = if (isLong) {
            val fokaStop = min(trigSnap.lastDownFractalLow, trigSnap.low) - atr * 0.18
            val keenbaseStop = trigSnap.kijun30 - atr * 0.40
            val koonitzStop = min(trigSnap.kijun, trigSnap.ema34) - atr * 0.25
            round2(min(min(fokaStop, keenbaseStop), koonitzStop))
        } else {
            val fokaStop = max(trigSnap.lastUpFractalHigh, trigSnap.high) + atr * 0.18
            val keenbaseStop = trigSnap.kijun30 + atr * 0.40
            val koonitzStop = max(trigSnap.kijun, trigSnap.ema34) + atr * 0.25
            round2(max(max(fokaStop, keenbaseStop), koonitzStop))
        }

        val dynamicRr = when {
            count >= 8 -> 3.15
            count >= 7 -> 3.00
            count >= 6 -> 2.85
            count >= 5 -> 2.55
            else -> 2.25
        }

        val badges = buildList {
            if (book1) add("درایو ۱ (TuAnh BB)")
            if (book2) add("درایو ۲ (Foka 15m)")
            if (book3) add("درایو ۳ (Keenbase 10-30-60)")
            if (book4) add("درایو ۴ (Mukherjee Kelly)")
            if (book5) add("درایو ۵ (Chikou Filter)")
            if (book6) add("درایو ۶ (HowToTrade)")
            if (book7) add("درایو ۷ (TSG 4-Step)")
            if (book8) add("درایو ۸ (Koonitz DMI/MACD)")
            if (book9) add("درایو ۹ (Triple Screen 7-22-44)")
        }

        val summary = String.format(
            Locale.US,
            "اجماع %d/9 کتاب درایو · احتمال برد بیزین %.1f%% · تارگت بهینه 1:%.2f",
            count,
            bayesianProb,
            dynamicRr,
        )

        return Unified9BookConsensusResult(
            isLong = isLong,
            book1TuAnhPassed = book1,
            book2FokaPassed = book2,
            book3KeenbasePassed = book3,
            book4MukherjeeKellyPassed = book4,
            book5MukherjeeChikouPassed = book5,
            book6HowToTradePassed = book6,
            book7Tsg4StepPassed = book7,
            book8Koonitz5RulesPassed = book8,
            book9KoonitzTripleScreenPassed = book9,
            confirmedBooksCount = count,
            bayesianWinProbabilityPct = round2(bayesianProb),
            compositeSniperAnchor = compositeAnchor,
            compositeFortressStopLoss = compositeStop,
            dynamicOptimalRr = dynamicRr,
            activeBookBadgesFa = badges,
            summaryFa = summary,
        )
    }

    /**
     * ارزیابی کامل هر یک از ۱۵ نماد داخل اپ (`V1Universe.allSymbols`) بر اساس تنها استراتژی فعال انتخاب‌شده.
     */
    fun evaluateBitcoinStrategy(
        activeModel: BitcoinStrategyModel,
        config: BaseStrategyConfig,
        macroCandles: List<Candle>,
        strategyCandles: List<Candle>,
        triggerCandles: List<Candle>,
        livePriceOverride: Double? = null,
        customTitleOverride: String? = null,
        customIdOverride: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        symbolInput: String = BTC_SYMBOL,
    ): StrategyEvaluationReport {
        val normalizedSymbol = V1Universe.normalize(symbolInput).takeIf { V1Universe.valid(it) } ?: BTC_SYMBOL
        val effectiveId = customIdOverride ?: activeModel.id
        val effectiveTitle = customTitleOverride ?: activeModel.titleFa

        val rawRefClose = triggerCandles.lastOrNull()?.close ?: strategyCandles.lastOrNull()?.close
        val alignedMacroCandles = alignCandlesToSpotBasis(macroCandles, rawRefClose, livePriceOverride)
        val alignedStrategyCandles = alignCandlesToSpotBasis(strategyCandles, rawRefClose, livePriceOverride)
        val alignedTriggerCandles = alignCandlesToSpotBasis(triggerCandles, rawRefClose, livePriceOverride)

        val macroSnap = computeSnapshot(alignedMacroCandles, config)
        val stratSnap = computeSnapshot(alignedStrategyCandles, config)
        val trigSnap = computeSnapshot(alignedTriggerCandles, config)

        if (stratSnap == null || trigSnap == null) {
            return StrategyEvaluationReport(
                symbol = normalizedSymbol,
                strategyId = effectiveId,
                strategyTitleFa = effectiveTitle,
                strategyBadgeFa = activeModel.badgeFa,
                macroInterval = config.macroInterval,
                strategyInterval = config.strategyInterval,
                triggerInterval = config.triggerInterval,
                readiness = StrategyReadinessState.INSUFFICIENT_DATA,
                action = SignalAction.NO_TRADE,
                triggerType = StrategyTriggerType.NONE,
                confidence = 0.0,
                livePrice = livePriceOverride ?: trigSnap?.close ?: stratSnap?.close ?: 0.0,
                suggestedEntry = null,
                suggestedStopLoss = null,
                targetV = null,
                targetN = null,
                targetE = null,
                primaryTakeProfit = null,
                riskReward = null,
                macroSnapshot = macroSnap,
                strategySnapshot = stratSnap,
                triggerSnapshot = trigSnap,
                waveProjection = null,
                conditions = emptyList(),
                summaryFa = "در حال تکمیل کندل‌های ۳ تایم‌فریم (${config.macroInterval.label}/${config.strategyInterval.label}/${config.triggerInterval.label}) برای $normalizedSymbol...",
                triggerBarTime = trigSnap?.time ?: 0L,
                evaluatedAt = nowMillis,
            )
        }

        // Apply model-specific overrides onto config for all 15 strategy combinations (2^4 - 1 = 15)
        // قانون قطعی سیستم: حداقل R:R تمام استراتژی‌ها و معاملات همیشه حداقل 1:1.5 (1.5R) و بالاتر است
        val minAllowedRr = max(config.minRiskReward, 1.5)
        val effectiveConfig = config.copy(
            enablePullbackTrigger = activeModel.hasPatel || activeModel.hasHosoda || activeModel.hasPeloille || !activeModel.hasTuttle,
            enableBreakoutTrigger = activeModel.hasTuttle || activeModel.hasHosoda || activeModel.hasPeloille || !activeModel.hasPatel,
            requireHigherTfAlignment = activeModel.hasPeloille || activeModel.combinationCount >= 2 || config.requireHigherTfAlignment,
            requireChikouOpenSpace = activeModel.hasTuttle || activeModel.hasPeloille || activeModel.combinationCount >= 2 || config.requireChikouOpenSpace,
            requireFutureKumoSlope = activeModel.hasPatel || activeModel.hasTuttle || activeModel.combinationCount >= 2 || config.requireFutureKumoSlope,
            minRiskReward = if (activeModel.hasHosoda && activeModel.combinationCount == 1) max(minAllowedRr, 1.8) else minAllowedRr,
        )

        val livePrice = livePriceOverride?.takeIf { it.isFinite() && it > 0.0 } ?: trigSnap.close

        // Determine candidate bias from Strategy Timeframe (1H) + Macro Timeframe (4H) + Book-Specific Settings
        val bullishBias = stratSnap.isPriceAboveKumo ||
            (macroSnap?.isPriceAboveKumo == true && trigSnap.isPriceAboveKumo)
        val bearishBias = stratSnap.isPriceBelowKumo ||
            (macroSnap?.isPriceBelowKumo == true && trigSnap.isPriceBelowKumo)

        val candidateDirection = when {
            bullishBias && !bearishBias -> SignalAction.BUY
            bearishBias && !bullishBias -> SignalAction.SELL
            stratSnap.tenkan >= stratSnap.kijun -> SignalAction.BUY
            else -> SignalAction.SELL
        }

        val isLong = candidateDirection == SignalAction.BUY
        val unified9BookConsensus = evaluateUnified9BookConsensus(
            isLong = isLong,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            macroSnap = macroSnap,
        )

        // ── Check 1: 3-TF Kumo Regime Alignment (4H / 1H / 15m) ───────────────
        val stratKumoOk = if (isLong) stratSnap.isPriceAboveKumo else stratSnap.isPriceBelowKumo
        val trigKumoOk = if (isLong) trigSnap.isPriceAboveKumo else trigSnap.isPriceBelowKumo
        val macroKumoOk = if (!effectiveConfig.requireHigherTfAlignment || macroSnap == null) {
            true
        } else {
            if (isLong) !macroSnap.isPriceBelowKumo else !macroSnap.isPriceAboveKumo
        }
        val cond1Passed = stratKumoOk && trigKumoOk && macroKumoOk
        val cond1Detail = buildString {
            append("${config.triggerInterval.label}/${config.strategyInterval.label}: ")
            append(if (stratSnap.isPriceAboveKumo) "بالای کومو (${stratSnap.kumoTop})" else if (stratSnap.isPriceBelowKumo) "زیر کومو (${stratSnap.kumoBottom})" else "داخل کومو")
            macroSnap?.let { m ->
                append(" · ${config.macroInterval.label}: ")
                append(if (m.isPriceAboveKumo) "بالای کومو" else if (m.isPriceBelowKumo) "زیر کومو" else "داخل کومو")
            }
        }

        // ── Check 2: Sanyaku 5-Line Order (Price > Tenkan >= Kijun > Kumo) ─────
        val sanyakuOk = if (isLong) {
            stratSnap.sanyakuBullishOrder && trigSnap.sanyakuBullishOrder
        } else {
            stratSnap.sanyakuBearishOrder && trigSnap.sanyakuBearishOrder
        }
        val cond2Detail = if (isLong) {
            "قیمت > تنکان (${trigSnap.tenkan}) ≥ کیجون (${trigSnap.kijun}) > کومو (${trigSnap.kumoTop})"
        } else {
            "قیمت < تنکان (${trigSnap.tenkan}) ≤ کیجون (${trigSnap.kijun}) < کومو (${trigSnap.kumoBottom})"
        }

        // ── Check 3: Tenkan / Kijun Koten-Gyakuten & Dynamic Equilibrium Slope ──
        val tkAligned = if (isLong) {
            stratSnap.tenkan >= stratSnap.kijun &&
                stratSnap.tenkan >= stratSnap.prevTenkan - stratSnap.atr14 * 0.05 &&
                stratSnap.kijun >= stratSnap.prevKijun - stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan >= trigSnap.prevTenkan - trigSnap.atr14 * 0.05
        } else {
            stratSnap.tenkan <= stratSnap.kijun &&
                stratSnap.tenkan <= stratSnap.prevTenkan + stratSnap.atr14 * 0.05 &&
                stratSnap.kijun <= stratSnap.prevKijun + stratSnap.atr14 * 0.02 &&
                trigSnap.tenkan <= trigSnap.prevTenkan + trigSnap.atr14 * 0.05
        }
        val cond3Detail = String.format(
            Locale.US,
            "شیب تنکان: %+.2f ATR · شیب کیجون: %+.2f ATR",
            trigSnap.tenkanSlopeAtrPerBar,
            trigSnap.kijunSlopeAtrPerBar,
        )

        // ── Check 4: Future Kumo Direction & Non-diverging Span B (26 bars ahead) ──
        val futureKumoOk = if (!effectiveConfig.requireFutureKumoSlope) {
            true
        } else if (isLong) {
            stratSnap.isFutureKumoBullish && stratSnap.isFutureSpanARising && stratSnap.isFutureSpanBNonFalling &&
                trigSnap.isFutureKumoBullish
        } else {
            stratSnap.isFutureKumoBearish && stratSnap.isFutureSpanAFalling && stratSnap.isFutureSpanBNonRising &&
                trigSnap.isFutureKumoBearish
        }
        val cond4Detail = "Future SSA=${stratSnap.futureSpanA} vs SSB=${stratSnap.futureSpanB} (${if (stratSnap.isFutureKumoBullish) "صعودی" else "نزولی"})"

        // ── Check 5: Future Kumo Thickness & Twist Immunity (الیوت + پلویل) ────
        val futureThicknessOk = stratSnap.futureKumoThicknessAtr >= 0.05 && trigSnap.futureKumoThicknessAtr >= 0.04
        val cond5Detail = String.format(
            Locale.US,
            "ضخامت ابر آینده: %.2f ATR (${config.strategyInterval.label}) · %.2f ATR (${config.triggerInterval.label})",
            stratSnap.futureKumoThicknessAtr,
            trigSnap.futureKumoThicknessAtr,
        )

        // ── Check 6: Chikou Span 3D Open Space (26 bars back + 6-bar corridor) ──
        val chikouOk = if (!effectiveConfig.requireChikouOpenSpace) {
            true
        } else if (isLong) {
            stratSnap.chikouBullishOpenSpace && trigSnap.chikouBullishOpenSpace
        } else {
            stratSnap.chikouBearishOpenSpace && trigSnap.chikouBearishOpenSpace
        }
        val cond6Detail = if (chikouOk) {
            "چیکو در ${config.strategyInterval.label} و ${config.triggerInterval.label} آزاد از کندل، TK و ابر کومو"
        } else {
            "چیکو درگیر کندل‌ها یا ابر ۲۶ دوره قبل است"
        }

        // ── Check 7: Chikou Structural Peak / Trough Clearance (مارک تاتل) ─────
        val chikouPeakClear = if (isLong) {
            trigSnap.close >= trigSnap.chikouResistancePeak - trigSnap.atr14 * 0.15
        } else {
            trigSnap.close <= trigSnap.chikouSupportTrough + trigSnap.atr14 * 0.15
        }
        val cond7Detail = if (isLong) {
            "کلوز=${trigSnap.close} در برابر سقف چیکو=${trigSnap.chikouResistancePeak}"
        } else {
            "کلوز=${trigSnap.close} در برابر کف چیکو=${trigSnap.chikouSupportTrough}"
        }

        // ── Check 8: Kijun Rubber-Band & Anti-C-Clamp Overextension Filter ─────
        val extAtr = stratSnap.distanceToKijunAtr
        val notOverextended = extAtr <= effectiveConfig.maxKijunExtensionAtr &&
            !stratSnap.isCClampWarning && !trigSnap.isCClampWarning
        val cond8Detail = String.format(
            Locale.US,
            "فاصله از کیجون: %.2f ATR (سقف: %.2f ATR)%s",
            extAtr,
            effectiveConfig.maxKijunExtensionAtr,
            if (stratSnap.isCClampWarning || trigSnap.isCClampWarning) " · هشدار C-Clamp" else "",
        )

        // ── Check 9: Flat SSB Gravity & Katana Equilibrium Filter (تاتل + پلویل) ──
        val reqGravityClearance = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            trigSnap.atr14 * effectiveConfig.breakoutBufferAtr
        } else {
            0.0
        }
        val flatSsbGravityCleared = if (isLong) {
            trigSnap.close >= trigSnap.kumoTop + reqGravityClearance
        } else {
            trigSnap.close <= trigSnap.kumoBottom - reqGravityClearance
        }
        val cond9Detail = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            "SSB فلت شناسایی شد؛ عبور با بافر امن انجام شد"
        } else {
            "بدون تله جاذبه SSB فلت (${if (trigSnap.isKatanaEquilibrium) "هم‌پوشانی کاتانا" else "تفکیک استاندارد TK"})"
        }

        // ── Detect Trigger on 15m/5m (Pullback / Breakout / 3-Candle Sanyaku / 9 Drive PDF Books) ────
        val closedTriggerBars = alignedTriggerCandles.filter { it.closed }.ifEmpty { alignedTriggerCandles }
        val triggerType = detectEntryTrigger(
            direction = candidateDirection,
            bars = closedTriggerBars,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            config = effectiveConfig,
            activeModel = activeModel,
        )

        // ── Compute Sniper Entry Zone & Tight Structural SL ───────────────────
        val slBuffer = trigSnap.atr14 * effectiveConfig.slBufferAtr
        val candleGoldenPocket = if (isLong) {
            trigSnap.low + (trigSnap.high - trigSnap.low) * 0.55
        } else {
            trigSnap.high - (trigSnap.high - trigSnap.low) * 0.55
        }
        val activeTenkanRef = when (activeModel) {
            BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> unified9BookConsensus.compositeSniperAnchor
            else -> trigSnap.tenkan
        }
        val sniperOptimal = if (isLong) {
            round2(max(activeTenkanRef, min(livePrice, candleGoldenPocket)))
        } else {
            round2(min(activeTenkanRef, max(livePrice, candleGoldenPocket)))
        }
        val sniperLow = if (isLong) {
            round2(min(activeTenkanRef, sniperOptimal) - trigSnap.atr14 * 0.12)
        } else {
            round2(min(sniperOptimal, livePrice) - trigSnap.atr14 * 0.35)
        }
        val sniperHigh = if (isLong) {
            round2(max(sniperOptimal, livePrice) + trigSnap.atr14 * 0.35)
        } else {
            round2(max(activeTenkanRef, sniperOptimal) + trigSnap.atr14 * 0.12)
        }
        val distToSniperAtr = abs(livePrice - sniperOptimal) / trigSnap.atr14
        val sniperEntryScore = round2((100.0 - distToSniperAtr * 55.0).coerceIn(15.0, 100.0))

        val wave = computeHosodaWave(alignedStrategyCandles, candidateDirection, stratSnap)
        val minSafeStopDist = minSafeStructuralStopDistance(
            symbol = normalizedSymbol,
            referencePrice = livePrice,
            trigAtr14 = trigSnap.atr14,
            stratAtr14 = stratSnap.atr14,
            triggerInterval = effectiveConfig.triggerInterval,
        )
        val safeAnchorLong = min(sniperOptimal, livePrice)
        val safeAnchorShort = max(sniperOptimal, livePrice)

        val rawTechnicalSl = when (activeModel) {
            BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> {
                if (isLong) {
                    round2(min(unified9BookConsensus.compositeFortressStopLoss, safeAnchorLong - minSafeStopDist))
                } else {
                    round2(max(unified9BookConsensus.compositeFortressStopLoss, safeAnchorShort + minSafeStopDist))
                }
            }
            else -> {
                if (isLong) {
                    val kumoKijunFloor = min(trigSnap.kijun, stratSnap.kijun.coerceAtLeast(trigSnap.kumoBottom)) - slBuffer
                    val microStructureFloor = min(trigSnap.low, trigSnap.kijun) - trigSnap.atr14 * 0.25
                    val tightStructuralSl = min(kumoKijunFloor, microStructureFloor)
                    round2(min(tightStructuralSl, safeAnchorLong - minSafeStopDist))
                } else {
                    val kumoKijunCeiling = max(trigSnap.kijun, stratSnap.kijun.coerceAtMost(trigSnap.kumoTop)) + slBuffer
                    val microStructureCeiling = max(trigSnap.high, trigSnap.kijun) + trigSnap.atr14 * 0.25
                    val tightStructuralSl = max(kumoKijunCeiling, microStructureCeiling)
                    round2(max(tightStructuralSl, safeAnchorShort + minSafeStopDist))
                }
            }
        }

        val rawStopDist = max(
            abs(sniperOptimal - rawTechnicalSl),
            abs(livePrice - rawTechnicalSl),
        ).coerceAtLeast(minSafeStopDist)

        val candidateTargets = if (wave != null) {
            listOf(wave.targetV, wave.targetN, wave.targetE).sortedBy { abs(it - livePrice) }
        } else {
            emptyList()
        }

        // اگر موج هوسودا بخواهد از ۱:۳ (3.0R) رد شود، حد ضرر در امن‌ترین سنگر ساختاری (کف/سقف کیجون + شندلیر ATR)
        // قرار می‌گیرد تا هم معامله با نوسان عادی استاپ نخورد (روند از دست نرود) و هم R:R بالای ۱:۳ حفظ شود.
        // در هر حالت فاصلهٔ حد ضرر از هر دو قیمت اسنایپر و قیمت لحظه‌ای کمتر از minSafeStopDist نمی‌شود.
        val farthestWaveTarget = candidateTargets.lastOrNull()
        val wantsToExceedOneToThree = farthestWaveTarget != null &&
            (abs(farthestWaveTarget - sniperOptimal) / rawStopDist) >= 3.0
        val technicalSl = if (wantsToExceedOneToThree && farthestWaveTarget != null) {
            val waveDist = abs(farthestWaveTarget - sniperOptimal)
            val maxAllowedStopFor3R = (waveDist / 3.0)
                .coerceAtLeast(minSafeStopDist)
                .coerceAtMost(max(minSafeStopDist * 1.35, trigSnap.atr14 * 1.85))
            if (isLong) {
                val deepStructuralSl = min(trigSnap.kijun, trigSnap.low) - trigSnap.atr14 * 0.35
                val chandelierFloor = safeAnchorLong - maxAllowedStopFor3R
                round2(min(safeAnchorLong - minSafeStopDist, max(chandelierFloor, min(rawTechnicalSl, deepStructuralSl))))
            } else {
                val deepStructuralSl = max(trigSnap.kijun, trigSnap.high) + trigSnap.atr14 * 0.35
                val chandelierCeiling = safeAnchorShort + maxAllowedStopFor3R
                round2(max(safeAnchorShort + minSafeStopDist, min(chandelierCeiling, max(rawTechnicalSl, deepStructuralSl))))
            }
        } else {
            rawTechnicalSl
        }

        val stopDist = max(
            abs(sniperOptimal - technicalSl),
            abs(livePrice - technicalSl),
        ).coerceAtLeast(minSafeStopDist)

        // ── Check 10: Hosoda N-Wave S-Point & Kihon Suchi Time Cycle Guard ────
        val sPointOk = wave?.sPointHeld != false
        val timeCycleExhausted = wave?.isNearTimeCycleChangeDay == true && trigSnap.isTenkanFlat
        val cond10Passed = sPointOk && !timeCycleExhausted
        val cond10Detail = if (wave != null) {
            "موج (${wave.pointA}→${wave.pointB}→${wave.pointC}) · کندل ${wave.barsSinceWaveStart} (چرخه هوسودا: ${wave.nearestTimeCycleNumber})"
        } else {
            "در حال تکمیل ساختار موج هوسودا"
        }

        // ── Check 11: Price Action Candle Body, Close Location, Volume Momentum & Drive PDF Book Rules ──
        val directionalCandleOk = if (isLong) {
            trigSnap.close > trigSnap.open && trigSnap.closeLocationValue >= 0.45 && trigSnap.candleBodyRatio >= 0.22
        } else {
            trigSnap.close < trigSnap.open && trigSnap.closeLocationValue <= 0.55 && trigSnap.candleBodyRatio >= 0.22
        }
        val volumeMomentumOk = trigSnap.volumeRatio20 >= effectiveConfig.minVolumeRatio20
        val reqConsensusCount = effectiveConfig.minDriveBooksConsensus.coerceIn(4, 9)
        val driveBookRuleOk = when (activeModel) {
            BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER ->
                unified9BookConsensus.confirmedBooksCount >= reqConsensusCount &&
                    unified9BookConsensus.bayesianWinProbabilityPct >= 76.0
            else -> true
        }
        val cond11Passed = directionalCandleOk && volumeMomentumOk && driveBookRuleOk
        val driveBookExtraDetail = when (activeModel) {
            BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER ->
                String.format(
                    Locale.US,
                    " · اجماع %d/9 کتاب درایو (برد بیزین=%.1f%% · Kelly=%d%%)",
                    unified9BookConsensus.confirmedBooksCount,
                    unified9BookConsensus.bayesianWinProbabilityPct,
                    trigSnap.kellyWinProbabilityPercent,
                )
            else -> ""
        }
        val cond11Detail = String.format(
            Locale.US,
            "بدنه کندل: %.0f%% · CLV: %.0f%% · حجم به میانگین ۲۰: %.2fx%s",
            trigSnap.candleBodyRatio * 100.0,
            trigSnap.closeLocationValue * 100.0,
            trigSnap.volumeRatio20,
            driveBookExtraDetail,
        )

        val requiredMinRr = max(effectiveConfig.minRiskReward, 1.5)

        // Check 13 & 15 early inputs so Optimal R:R (عدد مناسب R:R) can adapt to Trend Phase & Efficiency
        val erStrat = stratSnap.efficiencyRatio14
        val erTrig = trigSnap.efficiencyRatio14
        val dualFlatChop = stratSnap.isKijunFlat && stratSnap.isCurrentSpanBFlat &&
            trigSnap.isKijunFlat && trigSnap.isCurrentSpanBFlat && erStrat < 0.20
        val htfKijunCounterTrap = if (isLong) {
            trigSnap.close > trigSnap.kijun && stratSnap.close < stratSnap.kijun - stratSnap.atr14 * 0.25
        } else {
            trigSnap.close < trigSnap.kijun && stratSnap.close > stratSnap.kijun + stratSnap.atr14 * 0.25
        }
        val cond13Passed = (erStrat >= 0.20 || erTrig >= 0.22) && !dualFlatChop && !htfKijunCounterTrap

        val waveBars = wave?.barsSinceWaveStart ?: 12
        val trendPhase = when {
            !cond13Passed || dualFlatChop -> TrendPhase.CHOP_RANGE
            !notOverextended || (waveBars > 55 && trigSnap.isTenkanFlat && stratSnap.isTenkanFlat) -> TrendPhase.LATE_EXHAUSTION
            waveBars <= 18 || trigSnap.recentKatanaRelease || triggerType == StrategyTriggerType.EARLY_KATANA_GENESIS -> TrendPhase.GENESIS
            else -> TrendPhase.CORE_EXPANSION
        }

        // ── محاسبه «عدد مناسب R:R» (Optimal R:R) متناسب با مدل استراتژی (تمام ۱۵ ترکیب)، فاز روند و کارایی بازار ──
        val modelBaseOptimalRr = activeModel.baseOptimalRr
        val phaseRrBonus = when (trendPhase) {
            TrendPhase.GENESIS -> 0.30
            TrendPhase.CORE_EXPANSION -> 0.15
            else -> 0.0
        }
        val erRrBonus = ((erStrat + erTrig - 0.50) * 0.55).coerceIn(-0.15, 0.45)
        val volRrBonus = if (trigSnap.volumeRatio20 >= 1.20) 0.15 else 0.0
        val computedOptimalRr = round2(
            (modelBaseOptimalRr + phaseRrBonus + erRrBonus + volRrBonus).coerceIn(requiredMinRr, 4.20)
        )

        val labeledWaveTargets: List<Pair<String, Double>> = if (wave != null) {
            listOf(
                "موج NT هوسودا" to wave.targetNT,
                "موج V هوسودا" to wave.targetV,
                "موج N هوسودا" to wave.targetN,
                "موج E هوسودا" to wave.targetE,
            ).sortedBy { abs(it.second - sniperOptimal) }
        } else {
            emptyList()
        }

        // انتخاب هدف قیمتی از بین امواج هوسودا یا اهداف اختصاصی ۹ کتاب درایو (مثل 3R سیستم Keenbase و Foka و بررسی MA200 کتاب TuAnh)
        val validWaveCandidates = labeledWaveTargets.filter { (_, targetPrice) ->
            val inRightDirection = if (isLong) targetPrice > max(livePrice, sniperOptimal) else targetPrice < min(livePrice, sniperOptimal)
            val minDistFromEntries = min(abs(targetPrice - livePrice), abs(targetPrice - sniperOptimal))
            inRightDirection && (minDistFromEntries / stopDist) >= requiredMinRr
        }
        val chosenWavePair: Pair<String, Double>? = when (activeModel) {
            BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> {
                val targetMult = max(unified9BookConsensus.dynamicOptimalRr, computedOptimalRr)
                val unifiedTargetPrice = if (isLong) {
                    max(livePrice, sniperOptimal) + stopDist * targetMult
                } else {
                    (min(livePrice, sniperOptimal) - stopDist * targetMult).coerceAtLeast(0.00001)
                }
                "هدف ترکیبی ${String.format(Locale.US, "%.2fR", targetMult)} اجماع ۹ کتاب درایو" to unifiedTargetPrice
            }
            else -> {
                validWaveCandidates.minByOrNull { (_, targetPrice) ->
                    val targetRr = abs(targetPrice - sniperOptimal) / stopDist
                    abs(targetRr - computedOptimalRr)
                }
            }
        }

        val rawPrimaryTp = chosenWavePair?.second ?: if (isLong) {
            max(livePrice, sniperOptimal) + stopDist * max(computedOptimalRr, requiredMinRr)
        } else {
            (min(livePrice, sniperOptimal) - stopDist * max(computedOptimalRr, requiredMinRr)).coerceAtLeast(0.00001)
        }
        val selectedWaveLabel = chosenWavePair?.first ?: String.format(Locale.US, "امتداد مومنتوم (%.2fR)", computedOptimalRr)

        // Check nearest HTF horizontal plateau (Karen Péloille's Flat SSB/Kijun wall)
        val allPlateaus = ((macroSnap?.flatEquilibriumLevels ?: emptyList()) + stratSnap.flatEquilibriumLevels).distinct().sorted()
        val nearestObstacle = if (isLong) {
            allPlateaus.firstOrNull { it > livePrice + trigSnap.atr14 * 0.4 }
        } else {
            allPlateaus.lastOrNull { it < livePrice - trigSnap.atr14 * 0.4 }
        }

        // ── Check 12: Room to HTF Plateau & Optimal R:R >= 1:1.5 (minRiskReward) ──
        val effectiveTp = if (nearestObstacle != null) {
            val distToObstacle = min(abs(nearestObstacle - livePrice), abs(nearestObstacle - sniperOptimal))
            if (distToObstacle / stopDist >= requiredMinRr) {
                val bufferedObstacle = if (isLong) {
                    nearestObstacle - trigSnap.atr14 * 0.08
                } else {
                    nearestObstacle + trigSnap.atr14 * 0.08
                }
                val candidateCapped = if (isLong) min(rawPrimaryTp, bufferedObstacle) else max(rawPrimaryTp, bufferedObstacle)
                if (min(abs(candidateCapped - livePrice), abs(candidateCapped - sniperOptimal)) / stopDist >= requiredMinRr) {
                    candidateCapped
                } else {
                    rawPrimaryTp
                }
            } else {
                rawPrimaryTp
            }
        } else {
            rawPrimaryTp
        }

        val rrFromLive = abs(effectiveTp - livePrice) / abs(livePrice - technicalSl).coerceAtLeast(trigSnap.atr14 * 0.25)
        val rrFromSniper = abs(effectiveTp - sniperOptimal) / abs(sniperOptimal - technicalSl).coerceAtLeast(trigSnap.atr14 * 0.25)
        val rr = round2(min(rrFromLive, rrFromSniper))
        val effectiveOptimalRr = round2(max(computedOptimalRr, rr))
        val obstacleClear = if (nearestObstacle == null) {
            true
        } else {
            (min(abs(nearestObstacle - livePrice), abs(nearestObstacle - sniperOptimal)) / stopDist) >= requiredMinRr
        }
        val cond12Passed = rr >= requiredMinRr && obstacleClear

        val rewardDistSniper = round2(abs(effectiveTp - sniperOptimal))
        val riskDistSniper = round2(abs(sniperOptimal - technicalSl).coerceAtLeast(trigSnap.atr14 * 0.25))
        val slBasisTextFa = buildString {
            when (activeModel) {
                BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER ->
                    append("سنگر ترکیبی ۹ کتاب درایو: فرکتال ۵ کندلی + Kijun(30)±0.4ATR + EMA(34) (${round2(unified9BookConsensus.compositeFortressStopLoss)})")
                else -> {
                    val pillars = mutableListOf<String>()
                    if (activeModel.hasPatel) pillars += "تعادل کیجون پاتل (${round2(trigSnap.kijun)})"
                    if (activeModel.hasTuttle) pillars += "لبه کومو تاتل (${round2(if (isLong) trigSnap.kumoBottom else trigSnap.kumoTop)})"
                    if (activeModel.hasHosoda) pillars += "نقطه S موج هوسودا (${round2(wave?.pointC ?: trigSnap.kijun)})"
                    if (activeModel.hasPeloille) pillars += "پلاتوی فلت کارن پلویل"
                    append(pillars.joinToString(" + ").ifEmpty { "سنگر ساختاری کیجون/کومو" })
                }
            }
            append(String.format(Locale.US, " + بافر %.2f ATR", slBuffer))
        }
        val tpBasisTextFa = buildString {
            append(selectedWaveLabel)
            append(" (${round2(effectiveTp)})")
            if (nearestObstacle != null) {
                append(" · قبل از دیوار فلت تایم بالاتر ($nearestObstacle)")
            }
        }
        val formulaTextFa = String.format(
            Locale.US,
            "فرمول: پاداش (|TP−Entry| = %s) ÷ ریسک ۱R (|Entry−SL| = %s) = 1:%.2f",
            rewardDistSniper.toString(),
            riskDistSniper.toString(),
            rr,
        )
        val optimalRrReasonTextFa = buildString {
            append(String.format(Locale.US, "عدد مناسب R:R = 1:%.2f", rr))
            append(" (پایه ${activeModel.badgeFa}: 1:${String.format(Locale.US, "%.2f", modelBaseOptimalRr)}")
            append(" + ${trendPhase.badgeFa}")
            append(" + کارایی ER=${(erStrat * 100).toInt()}%")
            append(" ← هدف: $selectedWaveLabel")
            if (rr >= 3.0) {
                append(" · 🚀 مجهز به شکارچی روند بالای ۱:۳")
            }
            append(")")
        }
        val rrBreakdownObj = RrCalculationBreakdown(
            entryPrice = round2(sniperOptimal),
            stopLossPrice = round2(technicalSl),
            takeProfitPrice = round2(effectiveTp),
            riskDistance1R = riskDistSniper,
            rewardDistance = rewardDistSniper,
            actualRiskReward = rr,
            optimalRiskReward = effectiveOptimalRr,
            selectedTargetWaveLabel = selectedWaveLabel,
            formulaTextFa = formulaTextFa,
            slBasisFa = slBasisTextFa,
            tpBasisFa = tpBasisTextFa,
            optimalRrReasonFa = optimalRrReasonTextFa,
            runnerArmedAbove3R = rr >= 3.0,
        )

        val cond12Detail = buildString {
            append(String.format(Locale.US, "عدد مناسب R:R = 1:%.2f (%s)", rr, selectedWaveLabel))
            append(" · فرمول: $rewardDistSniper ÷ $riskDistSniper")
            if (rr >= 3.0) {
                append(" · 🚀 شکارچی روند بالای ۱:۳ فعال")
            }
        }

        // ── Check 13: Directional Efficiency Ratio (Kaufman ER) & Anti-Chop Filter ──
        val cond13Detail = String.format(
            Locale.US,
            "کارایی روند (ER14): %.0f%% (${config.strategyInterval.label}) · %.0f%% (${config.triggerInterval.label})%s",
            erStrat * 100.0,
            erTrig * 100.0,
            if (htfKijunCounterTrap) " · تله خلاف کیجون 1H" else if (dualFlatChop) " · رِنج افقی" else " · روند خالص",
        )

        // ── Check 14: Anti-Fakeout Wick Guard & Liquidity Sweep Filter ────────
        val adverseWick = if (isLong) trigSnap.upperWickRatio else trigSnap.lowerWickRatio
        val favorableTail = if (isLong) trigSnap.lowerWickRatio else trigSnap.upperWickRatio
        val cond14Passed = adverseWick <= 0.45 && (trigSnap.candleBodyRatio >= 0.22 || favorableTail >= 0.25)
        val cond14Detail = String.format(
            Locale.US,
            "شدوی مخالف: %.0f%% (سقف مجاز ۴۵%%) · شدوی تایید: %.0f%%",
            adverseWick * 100.0,
            favorableTail * 100.0,
        )

        // ── Check 15: Core-Trend Phase Classification (Genesis / Core vs Exhaustion) ──
        val cond15Passed = trendPhase == TrendPhase.GENESIS || trendPhase == TrendPhase.CORE_EXPANSION
        val cond15Detail = "${trendPhase.badgeFa} · کندل $waveBars موج · فاصله کیجون: ${String.format(Locale.US, "%.2f", extAtr)} ATR"

        // ── Check 16: Sniper Entry Precision & Anti-Chase Pocket ──────────────
        val distFromTenkanAtr = abs(trigSnap.close - activeTenkanRef) / trigSnap.atr14.coerceAtLeast(1e-8)
        val cond16Passed = distToSniperAtr <= 0.65 && distFromTenkanAtr <= 1.15
        val cond16Detail = String.format(
            Locale.US,
            "ورود بهینه: %s · بازه نقطه‌زنی: [%s .. %s] · دقت نقطه‌زنی: %.0f%%",
            round2(sniperOptimal).toString(),
            round2(sniperLow).toString(),
            round2(sniperHigh).toString(),
            sniperEntryScore,
        )

        val baseSixteenConditions = listOf(
            StrategyConditionCheck(1, "۱. رژیم کوموی ۳ تایم‌فریم (${config.macroInterval.label}/${config.strategyInterval.label}/${config.triggerInterval.label})", cond1Passed, cond1Detail),
            StrategyConditionCheck(2, "۲. چیدمان ۵ خط سانیاکو (Sanyaku Order)", sanyakuOk, cond2Detail),
            StrategyConditionCheck(3, "۳. کراس و شیب پویا Tenkan / Kijun", tkAligned, cond3Detail),
            StrategyConditionCheck(4, "۴. جهت و شیب ابر آینده (۲۶ کندل جلوتر)", futureKumoOk, cond4Detail),
            StrategyConditionCheck(5, "۵. ضخامت ابر آینده و ایمنی از پیچش (Twist)", futureThicknessOk, cond5Detail),
            StrategyConditionCheck(6, "۶. رهایی سه‌بعدی Chikou Span در ۲۶ دوره قبل", chikouOk, cond6Detail),
            StrategyConditionCheck(7, "۷. شکست سقف/کف ساختاری چیکو (فیلتر تاتل)", chikouPeakClear, cond7Detail),
            StrategyConditionCheck(8, "۸. قانون کش لاستیکی کیجون و نبود C-Clamp", notOverextended, cond8Detail),
            StrategyConditionCheck(9, "۹. فیلتر جاذبه فلت SSB و تعادل کاتانا", flatSsbGravityCleared, cond9Detail),
            StrategyConditionCheck(10, "۱۰. هندسه موج N هوسودا، نقطه S و چرخه زمانی", cond10Passed, cond10Detail),
            StrategyConditionCheck(11, "۱۱. قدرت پرایس‌اکشن کندل ماشه و حجم (CLV/Vol)", cond11Passed, cond11Detail),
            StrategyConditionCheck(12, "۱۲. فرمول و عدد مناسب R:R (حداقل ۱:۱.۵) و فضای باز فلت", cond12Passed, cond12Detail),
            StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند و فیلتر ضد رِنج (ER14)", cond13Passed, cond13Detail),
            StrategyConditionCheck(14, "۱۴. فیلتر ضد فیک‌بریک‌اوت و رد شدوی معکوس", cond14Passed, cond14Detail),
            StrategyConditionCheck(15, "۱۵. شکار اصل روند (فاز Genesis/Core و عدم اشباع)", cond15Passed, cond15Detail),
            StrategyConditionCheck(16, "۱۶. نقطه‌زنی میلی‌متری ورود (Sniper Entry Pocket)", cond16Passed, cond16Detail),
        )

        val conditions = if (activeModel.isDrivePdfStrategy) {
            buildDriveBookConditions(
                model = activeModel,
                isLong = isLong,
                config = effectiveConfig,
                macroSnap = macroSnap,
                stratSnap = stratSnap,
                trigSnap = trigSnap,
                wave = wave,
                rr = rr,
                selectedWaveLabel = selectedWaveLabel,
                rewardDistSniper = rewardDistSniper,
                riskDistSniper = riskDistSniper,
                cond1Passed = cond1Passed,
                cond1Detail = cond1Detail,
                cond4Detail = cond4Detail,
                cond5Passed = futureThicknessOk,
                cond5Detail = cond5Detail,
                cond6Passed = chikouOk,
                cond6Detail = cond6Detail,
                cond7Passed = chikouPeakClear,
                cond8Passed = notOverextended,
                cond9Passed = flatSsbGravityCleared,
                cond10Passed = cond10Passed,
                cond10Detail = cond10Detail,
                cond12Passed = cond12Passed,
                cond12Detail = cond12Detail,
                cond13Passed = cond13Passed,
                cond13Detail = cond13Detail,
                cond14Passed = cond14Passed,
                cond14Detail = cond14Detail,
                cond15Passed = cond15Passed,
                cond15Detail = cond15Detail,
                cond16Passed = cond16Passed,
                cond16Detail = cond16Detail,
            )
        } else {
            baseSixteenConditions
        }

        // ارتقای دقت تمام ۵ مدل استراتژی: تمام ۱۶ شرط ساختاری، مومنتوم، چیکو، هوسودا و نقطه‌زنی برای همه مدل‌ها الزامی است
        val requiredIndicesForModel: Set<Int> = (1..16).toSet()
        val modelCoreConditions = conditions.filter { it.index in requiredIndicesForModel }
        val allSixteenPassed = modelCoreConditions.all { it.passed }
        val firstFifteenPassed = modelCoreConditions.filter { it.index != 16 }.all { it.passed }
        val triggerFired = triggerType != StrategyTriggerType.NONE
        val passedCount = conditions.count { it.passed }
        val phaseBonus = when (trendPhase) {
            TrendPhase.GENESIS -> 8.0
            TrendPhase.CORE_EXPANSION -> 5.0
            TrendPhase.LATE_EXHAUSTION -> -15.0
            TrendPhase.CHOP_RANGE -> -25.0
        }
        val erBonus = ((erStrat + erTrig) * 5.0).coerceIn(0.0, 10.0)
        val rrBonus = (rr * 2.0).coerceIn(0.0, 8.0)
        val sniperBonus = (sniperEntryScore * 0.06).coerceIn(0.0, 6.0)
        val confidence = round2(((passedCount.toDouble() / conditions.size.toDouble()) * 84.0 + if (triggerFired) 16.0 else 0.0).coerceIn(0.0, 100.0))
        val coreTrendScore = round2((confidence * 0.74 + phaseBonus + erBonus + rrBonus + sniperBonus).coerceIn(0.0, 100.0))

        val sniperArmedPendingRetest = firstFifteenPassed && triggerFired && !cond16Passed

        val readiness = when {
            allSixteenPassed && triggerFired && isLong -> StrategyReadinessState.SIGNAL_BUY
            allSixteenPassed && triggerFired && !isLong -> StrategyReadinessState.SIGNAL_SELL
            sniperArmedPendingRetest || passedCount >= 11 -> StrategyReadinessState.WATCH_SETUP
            else -> StrategyReadinessState.BLOCKED
        }

        val finalAction = when (readiness) {
            StrategyReadinessState.SIGNAL_BUY -> SignalAction.BUY
            StrategyReadinessState.SIGNAL_SELL -> SignalAction.SELL
            else -> if (sniperArmedPendingRetest) {
                if (isLong) SignalAction.BUY else SignalAction.SELL
            } else {
                SignalAction.NO_TRADE
            }
        }

        val tfTag = "[تایم ${config.triggerInterval.label}]"
        val summaryFa = when {
            readiness == StrategyReadinessState.SIGNAL_BUY ->
                "$tfTag 🎯 خرید نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            readiness == StrategyReadinessState.SIGNAL_SELL ->
                "$tfTag 🎯 فروش نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            sniperArmedPendingRetest ->
                "$tfTag 🎯 مسلح برای نقطه‌زنی ($normalizedSymbol · $effectiveTitle): در کمین لمس $sniperOptimal"
            readiness == StrategyReadinessState.WATCH_SETUP ->
                "$tfTag ⏳ $passedCount/۱۶ شرط (${trendPhase.badgeFa})؛ منتظر ماشه در ${config.triggerInterval.label}"
            readiness == StrategyReadinessState.BLOCKED -> {
                val firstFailed = modelCoreConditions.firstOrNull { !it.passed } ?: conditions.firstOrNull { !it.passed }
                "$tfTag 🛑 $passedCount/۱۶ شرط · مانع: ${firstFailed?.titleFa ?: ""}"
            }
            else -> "$tfTag در حال دریافت کندل‌های $normalizedSymbol..."
        }

        return StrategyEvaluationReport(
            symbol = normalizedSymbol,
            strategyId = effectiveId,
            strategyTitleFa = effectiveTitle,
            strategyBadgeFa = activeModel.badgeFa,
            macroInterval = config.macroInterval,
            strategyInterval = config.strategyInterval,
            triggerInterval = config.triggerInterval,
            readiness = readiness,
            action = finalAction,
            triggerType = triggerType,
            confidence = confidence,
            livePrice = round2(livePrice),
            suggestedEntry = sniperOptimal,
            suggestedStopLoss = technicalSl,
            targetV = wave?.targetV,
            targetN = wave?.targetN,
            targetE = wave?.targetE,
            primaryTakeProfit = round2(effectiveTp),
            riskReward = rr,
            macroSnapshot = macroSnap,
            strategySnapshot = stratSnap,
            triggerSnapshot = trigSnap,
            waveProjection = wave,
            conditions = conditions,
            summaryFa = summaryFa,
            triggerBarTime = trigSnap.time,
            evaluatedAt = nowMillis,
            trendPhase = trendPhase,
            coreTrendScore = coreTrendScore,
            sniperOptimalEntry = sniperOptimal,
            sniperZoneLow = sniperLow,
            sniperZoneHigh = sniperHigh,
            sniperEntryScore = sniperEntryScore,
            isSniperArmedPendingRetest = sniperArmedPendingRetest,
            optimalRiskReward = effectiveOptimalRr,
            rrBreakdown = rrBreakdownObj,
        )
    }

    /**
     * ارزیابی هم‌زمان تمام استراتژی‌های فعال روی هر دو تایم‌فریم ورود `15m` (`4H -> 1H -> 15m`) و `5m` (`1H -> 15m -> 5m`)
     * با اشتراک‌گذاری کندل‌های `4H`/`1H`/`15m`/`5m` برای جلوگیری از مصرف اضافه اینترنت.
     */
    fun evaluateDualTimeframeStrategy(
        activeModel: BitcoinStrategyModel,
        config: BaseStrategyConfig,
        candles4h: List<Candle>,
        candles1h: List<Candle>,
        candles15m: List<Candle>,
        candles5m: List<Candle>,
        livePriceOverride: Double? = null,
        customTitleOverride: String? = null,
        customIdOverride: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        symbolInput: String = BTC_SYMBOL,
        runAllSimultaneously: Boolean = true,
        enabledStrategyIds: Set<String> = BitcoinStrategyModel.entries.map { it.id }.toSet(),
        customStrategies: List<CustomStrategySlot> = emptyList(),
    ): DualTimeframeSymbolReport {
        val config15m = config.copy(
            macroIntervalLabel = Interval.H4.label,
            strategyIntervalLabel = Interval.H1.label,
            triggerIntervalLabel = Interval.M15.label,
        )
        val config5m = config.copy(
            macroIntervalLabel = Interval.H1.label,
            strategyIntervalLabel = Interval.M15.label,
            triggerIntervalLabel = Interval.M5.label,
        )

        val map15m = LinkedHashMap<String, StrategyEvaluationReport>()
        val map5m = LinkedHashMap<String, StrategyEvaluationReport>()

        if (runAllSimultaneously) {
            val activeBuiltIns = BitcoinStrategyModel.entries.filter { it.id in enabledStrategyIds }
                .ifEmpty { BitcoinStrategyModel.entries }
            for (model in activeBuiltIns) {
                map15m[model.id] = evaluateBitcoinStrategy(
                    activeModel = model,
                    config = config15m,
                    macroCandles = candles4h,
                    strategyCandles = candles1h,
                    triggerCandles = candles15m,
                    livePriceOverride = livePriceOverride,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
                map5m[model.id] = evaluateBitcoinStrategy(
                    activeModel = model,
                    config = config5m,
                    macroCandles = candles1h,
                    strategyCandles = candles15m,
                    triggerCandles = candles5m,
                    livePriceOverride = livePriceOverride,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
            }
            for (slot in customStrategies.filter { it.id in enabledStrategyIds }) {
                val baseMod = BitcoinStrategyModel.fromId(slot.baseModelId)
                map15m[slot.id] = evaluateBitcoinStrategy(
                    activeModel = baseMod,
                    config = slot.config.copy(
                        macroIntervalLabel = Interval.H4.label,
                        strategyIntervalLabel = Interval.H1.label,
                        triggerIntervalLabel = Interval.M15.label,
                    ),
                    macroCandles = candles4h,
                    strategyCandles = candles1h,
                    triggerCandles = candles15m,
                    livePriceOverride = livePriceOverride,
                    customTitleOverride = slot.titleFa,
                    customIdOverride = slot.id,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
                map5m[slot.id] = evaluateBitcoinStrategy(
                    activeModel = baseMod,
                    config = slot.config.copy(
                        macroIntervalLabel = Interval.H1.label,
                        strategyIntervalLabel = Interval.M15.label,
                        triggerIntervalLabel = Interval.M5.label,
                    ),
                    macroCandles = candles1h,
                    strategyCandles = candles15m,
                    triggerCandles = candles5m,
                    livePriceOverride = livePriceOverride,
                    customTitleOverride = slot.titleFa,
                    customIdOverride = slot.id,
                    nowMillis = nowMillis,
                    symbolInput = symbolInput,
                )
            }
        }

        val focusedId = customIdOverride ?: activeModel.id
        val rep15m = map15m[focusedId]
            ?: map15m.values.firstOrNull { it.isActionable }
            ?: evaluateBitcoinStrategy(
                activeModel = activeModel,
                config = config15m,
                macroCandles = candles4h,
                strategyCandles = candles1h,
                triggerCandles = candles15m,
                livePriceOverride = livePriceOverride,
                customTitleOverride = customTitleOverride,
                customIdOverride = customIdOverride,
                nowMillis = nowMillis,
                symbolInput = symbolInput,
            ).also { map15m[it.strategyId] = it }

        val rep5m = map5m[focusedId]
            ?: map5m.values.firstOrNull { it.isActionable }
            ?: evaluateBitcoinStrategy(
                activeModel = activeModel,
                config = config5m,
                macroCandles = candles1h,
                strategyCandles = candles15m,
                triggerCandles = candles5m,
                livePriceOverride = livePriceOverride,
                customTitleOverride = customTitleOverride,
                customIdOverride = customIdOverride,
                nowMillis = nowMillis,
                symbolInput = symbolInput,
            ).also { map5m[it.strategyId] = it }

        val best15m = map15m.values.firstOrNull { it.isActionable } ?: rep15m
        val best5m = map5m.values.firstOrNull { it.isActionable } ?: rep5m

        return DualTimeframeSymbolReport(
            symbol = best15m.symbol,
            report15m = best15m,
            report5m = best5m,
            reports15mByStrategy = map15m,
            reports5mByStrategy = map5m,
        )
    }

    /**
     * ساخت چک‌لیست اختصاصی ۱۶ شرطی برای هر یک از ۹ کتاب PDF گوگل درایو طبق قوانین خط‌به‌خط هر کتاب.
     */
    private fun buildDriveBookConditions(
        model: BitcoinStrategyModel,
        isLong: Boolean,
        config: BaseStrategyConfig,
        macroSnap: IchimokuBarSnapshot?,
        stratSnap: IchimokuBarSnapshot,
        trigSnap: IchimokuBarSnapshot,
        wave: HosodaWaveProjection?,
        rr: Double,
        selectedWaveLabel: String,
        rewardDistSniper: Double,
        riskDistSniper: Double,
        cond1Passed: Boolean,
        cond1Detail: String,
        cond4Detail: String,
        cond5Passed: Boolean,
        cond5Detail: String,
        cond6Passed: Boolean,
        cond6Detail: String,
        cond7Passed: Boolean,
        cond8Passed: Boolean,
        cond9Passed: Boolean,
        cond10Passed: Boolean,
        cond10Detail: String,
        cond12Passed: Boolean,
        cond12Detail: String,
        cond13Passed: Boolean,
        cond13Detail: String,
        cond14Passed: Boolean,
        cond14Detail: String,
        cond15Passed: Boolean,
        cond15Detail: String,
        cond16Passed: Boolean,
        cond16Detail: String,
    ): List<StrategyConditionCheck> {
        val dirCandle = if (isLong) trigSnap.close >= trigSnap.open else trigSnap.close <= trigSnap.open
        return when (model) {
            BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> {
                val consensus = evaluateUnified9BookConsensus(isLong, trigSnap, stratSnap, macroSnap)
                val reqBooks = config.minDriveBooksConsensus.coerceIn(4, 9)
                val cConsensus = consensus.confirmedBooksCount >= reqBooks && consensus.bayesianWinProbabilityPct >= 76.0
                val cTripleKumo = if (isLong) {
                    trigSnap.isPriceAboveKumo && trigSnap.close > trigSnap.kumoTop103060 && trigSnap.close > trigSnap.kumoTop72244
                } else {
                    trigSnap.isPriceBelowKumo && trigSnap.close < trigSnap.kumoBottom103060 && trigSnap.close < trigSnap.kumoBottom72244
                }
                listOf(
                    StrategyConditionCheck(
                        1,
                        "۱. اجماع بیزین تطبیقی ۹ کتاب درایو (حداقل $reqBooks از ۹ کتاب + احتمال برد ≥ ۷۶٪)",
                        cConsensus,
                        String.format(
                            Locale.US,
                            "تایید %d/9 کتاب · احتمال برد بیزین: %.1f%% · کتاب‌های فعال: %s",
                            consensus.confirmedBooksCount,
                            consensus.bayesianWinProbabilityPct,
                            consensus.activeBookBadgesFa.joinToString("، ").ifEmpty { "در انتظار تکمیل" },
                        ),
                    ),
                    StrategyConditionCheck(
                        2,
                        "۲. هم‌جهتی هر ۳ ابر ایچیموکو: استاندارد (9,26,52) + Keenbase (10,30,60) + سریع (7,22,44)",
                        cTripleKumo && cond1Passed,
                        String.format(
                            Locale.US,
                            "Kumo(52)=%s · Kumo(60)=%s · Kumo(44)=%s",
                            round2(if (isLong) trigSnap.kumoTop else trigSnap.kumoBottom).toString(),
                            round2(if (isLong) trigSnap.kumoTop103060 else trigSnap.kumoBottom103060).toString(),
                            round2(if (isLong) trigSnap.kumoTop72244 else trigSnap.kumoBottom72244).toString(),
                        ),
                    ),
                    StrategyConditionCheck(
                        3,
                        "۳. کتاب درایو ۱ (TuAnh): فشردگی/باند بولینگر BB(20,2) + EMA(89) + RSI(14)>50",
                        consensus.book1TuAnhPassed,
                        String.format(Locale.US, "BB20=%s (Squeeze=%s) · EMA89=%s · RSI14=%.1f", round2(trigSnap.bbMiddle20).toString(), if (trigSnap.isBollingerSqueeze) "بله" else "عادی", round2(trigSnap.ema89).toString(), trigSnap.rsi14),
                    ),
                    StrategyConditionCheck(
                        4,
                        "۴. کتاب درایو ۲ (Foka 15m/Day): شیب EMA(21) + پرش RSI(7) از خط ۵۰ + فرکتال ۵ کندلی",
                        consensus.book2FokaPassed,
                        String.format(Locale.US, "EMA21=%s (شیب=%+.2f) · RSI7=%.1f · فرکتال=%s", round2(trigSnap.ema21).toString(), trigSnap.ema21SlopeAtrPerBar, trigSnap.rsi7, round2(if (isLong) trigSnap.lastDownFractalLow else trigSnap.lastUpFractalHigh).toString()),
                    ),
                    StrategyConditionCheck(
                        5,
                        "۵. کتاب درایو ۳ (Keenbase): کراس نهادی Tenkan(10)/Kijun(30) و کلوز بیرون Kumo(60)",
                        consensus.book3KeenbasePassed,
                        String.format(Locale.US, "Tenkan(10)=%s · Kijun(30)=%s · استاپ مکانیکی=%s", round2(trigSnap.tenkan10).toString(), round2(trigSnap.kijun30).toString(), round2(consensus.compositeFortressStopLoss).toString()),
                    ),
                    StrategyConditionCheck(
                        6,
                        "۶. کتاب درایو ۴ (Mukherjee Kelly): طوفان کامل ۵ سیگناله (Kelly ≥ 62%) + الگوی شمعی و RSI",
                        consensus.book4MukherjeeKellyPassed,
                        String.format(Locale.US, "سیگنال‌های کلی=%d/5 (%d%%) · افت RSI از قله=%.1f", trigSnap.kellySignalCount, trigSnap.kellyWinProbabilityPercent, if (isLong) trigSnap.rsiPeakDropLong else trigSnap.rsiTroughRiseShort),
                    ),
                    StrategyConditionCheck(
                        7,
                        "۷. کتاب درایو ۵ و ۷ (Mukherjee & TSG): خط ماشه تنکان + کیجون غیرفلت + آزادی چیکو بالای/زیر ابر",
                        consensus.book5MukherjeeChikouPassed && consensus.book7Tsg4StepPassed && cond6Passed,
                        String.format(Locale.US, "شیب کیجون=%+.2f ATR · چیکو بیرون ابر ۲۶=%s", trigSnap.kijunSlopeAtrPerBar, if (trigSnap.chikouAboveCloud26 || trigSnap.chikouBelowCloud26) "تایید ✅" else "خیر"),
                    ),
                    StrategyConditionCheck(
                        8,
                        "۸. کتاب درایو ۶ (HowToTrade): شکست کانال کومو و تایید Kumo Twist ابر آینده",
                        consensus.book6HowToTradePassed && cond5Passed,
                        cond4Detail,
                    ),
                    StrategyConditionCheck(
                        9,
                        "۹. کتاب درایو ۸ (Koonitz 5 Rules): ۵ قانون طلایی کونیتز + تایید DMI/ADX و MACD + EMA(34)",
                        consensus.book8Koonitz5RulesPassed,
                        String.format(Locale.US, "ADX(14)=%.1f (+DI=%.0f/-DI=%.0f) · MACD=%+.3f · EMA34=%s", trigSnap.adx14, trigSnap.plusDi14, trigSnap.minusDi14, trigSnap.macdHistogram, round2(trigSnap.ema34).toString()),
                    ),
                    StrategyConditionCheck(
                        10,
                        "۱۰. کتاب درایو ۹ (Koonitz Triple Screen): صفحه سه‌گانه EMA(13/34/48) + ایچیموکوی سریع (7,22,44)",
                        consensus.book9KoonitzTripleScreenPassed,
                        String.format(Locale.US, "Fast(7/22)=%s/%s · EMA(13/34/48)=%s/%s/%s", round2(trigSnap.tenkan7).toString(), round2(trigSnap.kijun22).toString(), round2(trigSnap.ema13).toString(), round2(trigSnap.ema34).toString(), round2(trigSnap.ema48).toString()),
                    ),
                    StrategyConditionCheck(11, "۱۱. هندسه موج N هوسودا و فاصله امن از روز چرخش زمانی", cond10Passed, cond10Detail),
                    StrategyConditionCheck(12, "۱۲. نسبت R:R بهینه اجماع ۹ کتاب (حداقل 1:2.25 تا 1:3.15) با سنگر استاپ ترکیبی", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند کافمن (ER14) و فیلتر ضد رِنج", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. کندل تایید جهت‌دار با حجم مناسب و شدوی مخالف کنترل‌شده", cond14Passed && dirCandle, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز تولد یا گسترش موج (Genesis / Core Expansion)", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی اسنایپر ترکیبی روی میانه وزن‌دار (Tenkan 7/9/10 + EMA21)", cond16Passed, cond16Detail),
                )
            }

            else -> emptyList()
        }
    }

    /**
     * تشخیص ۴ ماشه ورود برای شکار اصل روند از لحظه تولد (`EARLY_KATANA_GENESIS`) تا قلب موج (`PULLBACK / BREAKOUT / SANYAKU`)
     * + ماشه‌های اختصاصی استراتژی جامع ۹ کتاب درایو (`DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER`).
     */
    private fun detectEntryTrigger(
        direction: SignalAction,
        bars: List<Candle>,
        trigSnap: IchimokuBarSnapshot,
        stratSnap: IchimokuBarSnapshot,
        config: BaseStrategyConfig,
        activeModel: BitcoinStrategyModel = BitcoinStrategyModel.BASE_ICHIMOKU,
    ): StrategyTriggerType {
        if (bars.size < 5) return StrategyTriggerType.NONE
        val cur = bars.last()
        val prev1 = bars[bars.lastIndex - 1]
        val prev2 = bars[bars.lastIndex - 2]
        val prev3 = bars[bars.lastIndex - 3]

        val isLong = direction == SignalAction.BUY
        val breakoutBuf = if (trigSnap.isCurrentSpanBFlat || stratSnap.isCurrentSpanBFlat) {
            trigSnap.atr14 * config.breakoutBufferAtr
        } else {
            trigSnap.atr14 * 0.03
        }

        // ── ماشه‌های اختصاصی متود جامع ۹ کتاب درایو ────────────────────────────
        when (activeModel) {
            BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> {
                val consensus = evaluateUnified9BookConsensus(isLong, trigSnap, stratSnap, null)
                val reqBooks = config.minDriveBooksConsensus.coerceIn(4, 9)
                if (consensus.confirmedBooksCount >= reqBooks) {
                    if (isLong && cur.close > cur.open && cur.close > trigSnap.kumoTop) {
                        if ((trigSnap.isBollingerSqueeze && trigSnap.isMarubozuBreakoutBull) || trigSnap.recentKatanaRelease) {
                            return StrategyTriggerType.EARLY_KATANA_GENESIS
                        }
                        val touchedAnchor = listOf(prev1, prev2, prev3, cur).any { b ->
                            b.low <= max(consensus.compositeSniperAnchor, trigSnap.kijun) + trigSnap.atr14 * 0.35
                        }
                        if (touchedAnchor && cur.close >= min(trigSnap.tenkan7, trigSnap.tenkan)) {
                            return StrategyTriggerType.PULLBACK_BOUNCE
                        }
                        if (cur.close >= trigSnap.chikouResistancePeak + breakoutBuf) {
                            return StrategyTriggerType.VALIDATED_BREAKOUT
                        }
                    } else if (!isLong && cur.close < cur.open && cur.close < trigSnap.kumoBottom) {
                        if ((trigSnap.isBollingerSqueeze && trigSnap.isMarubozuBreakoutBear) || trigSnap.recentKatanaRelease) {
                            return StrategyTriggerType.EARLY_KATANA_GENESIS
                        }
                        val touchedAnchor = listOf(prev1, prev2, prev3, cur).any { b ->
                            b.high >= min(consensus.compositeSniperAnchor, trigSnap.kijun) - trigSnap.atr14 * 0.35
                        }
                        if (touchedAnchor && cur.close <= max(trigSnap.tenkan7, trigSnap.tenkan)) {
                            return StrategyTriggerType.PULLBACK_BOUNCE
                        }
                        if (cur.close <= trigSnap.chikouSupportTrough - breakoutBuf) {
                            return StrategyTriggerType.VALIDATED_BREAKOUT
                        }
                    }
                }
            }

            else -> Unit
        }

        if (isLong) {
            // Trigger 0: Early Katana Genesis (captures the very birth of the core trend right as TK compression releases)
            if (config.enablePullbackTrigger || config.enableBreakoutTrigger) {
                if (trigSnap.recentKatanaRelease && cur.close > cur.open &&
                    cur.close > max(trigSnap.tenkan, trigSnap.kijun) &&
                    cur.close > trigSnap.kumoTop &&
                    trigSnap.chikouBullishOpenSpace &&
                    trigSnap.distanceToKijunAtr <= 1.15
                ) {
                    return StrategyTriggerType.EARLY_KATANA_GENESIS
                }
            }

            // Trigger 1: Pullback bounce off Kijun / Tenkan / Kumo Top (S-point) and close above Tenkan
            if (config.enablePullbackTrigger) {
                val recentTouchedEquilibrium = listOf(prev1, prev2, prev3, cur).any { b ->
                    b.low <= max(trigSnap.tenkan, trigSnap.kijun) + trigSnap.atr14 * 0.35 &&
                        b.close >= trigSnap.kijun - trigSnap.atr14 * 0.20
                }
                val bullishCandleCloseAboveTenkan = cur.close > cur.open &&
                    cur.close >= trigSnap.tenkan &&
                    cur.close > trigSnap.kumoTop
                if (recentTouchedEquilibrium && bullishCandleCloseAboveTenkan) {
                    return StrategyTriggerType.PULLBACK_BOUNCE
                }
            }

            // Trigger 2: Validated Kumo / Kijun breakout with Chikou resistance peak clearance
            if (config.enableBreakoutTrigger) {
                val crossedUpRecently = (prev1.close <= trigSnap.kumoTop || prev2.close <= trigSnap.kumoTop || prev1.close <= trigSnap.kijun) &&
                    cur.close > max(trigSnap.kumoTop, trigSnap.kijun) + breakoutBuf
                val chikouClearedPeak = cur.close >= trigSnap.chikouResistancePeak + breakoutBuf
                if (crossedUpRecently && chikouClearedPeak && cur.close > cur.open) {
                    return StrategyTriggerType.VALIDATED_BREAKOUT
                }
                if (trigSnap.sanyakuBullishOrder && trigSnap.threeCandleBullishImpulse &&
                    cur.close >= max(prev1.high, prev2.high) && trigSnap.distanceToKijunAtr <= config.maxKijunExtensionAtr * 0.85
                ) {
                    return StrategyTriggerType.SANYAKU_TK_CONTINUATION
                }
            }
        } else {
            // Short Trigger 0: Early Katana Genesis (captures the very birth of a bearish core trend)
            if (config.enablePullbackTrigger || config.enableBreakoutTrigger) {
                if (trigSnap.recentKatanaRelease && cur.close < cur.open &&
                    cur.close < min(trigSnap.tenkan, trigSnap.kijun) &&
                    cur.close < trigSnap.kumoBottom &&
                    trigSnap.chikouBearishOpenSpace &&
                    trigSnap.distanceToKijunAtr <= 1.15
                ) {
                    return StrategyTriggerType.EARLY_KATANA_GENESIS
                }
            }

            // Short Trigger 1: Pullback bounce off Kijun / Tenkan / Kumo Bottom and close below Tenkan
            if (config.enablePullbackTrigger) {
                val recentTouchedEquilibrium = listOf(prev1, prev2, prev3, cur).any { b ->
                    b.high >= min(trigSnap.tenkan, trigSnap.kijun) - trigSnap.atr14 * 0.35 &&
                        b.close <= trigSnap.kijun + trigSnap.atr14 * 0.20
                }
                val bearishCandleCloseBelowTenkan = cur.close < cur.open &&
                    cur.close <= trigSnap.tenkan &&
                    cur.close < trigSnap.kumoBottom
                if (recentTouchedEquilibrium && bearishCandleCloseBelowTenkan) {
                    return StrategyTriggerType.PULLBACK_BOUNCE
                }
            }

            // Short Trigger 2: Validated Kumo / Kijun breakdown with Chikou support trough clearance
            if (config.enableBreakoutTrigger) {
                val crossedDownRecently = (prev1.close >= trigSnap.kumoBottom || prev2.close >= trigSnap.kumoBottom || prev1.close >= trigSnap.kijun) &&
                    cur.close < min(trigSnap.kumoBottom, trigSnap.kijun) - breakoutBuf
                val chikouClearedTrough = cur.close <= trigSnap.chikouSupportTrough - breakoutBuf
                if (crossedDownRecently && chikouClearedTrough && cur.close < cur.open) {
                    return StrategyTriggerType.VALIDATED_BREAKOUT
                }
                if (trigSnap.sanyakuBearishOrder && trigSnap.threeCandleBearishImpulse &&
                    cur.close <= min(prev1.low, prev2.low) && trigSnap.distanceToKijunAtr <= config.maxKijunExtensionAtr * 0.85
                ) {
                    return StrategyTriggerType.SANYAKU_TK_CONTINUATION
                }
            }
        }

        return StrategyTriggerType.NONE
    }

    /**
     * بررسی دقیق زمان از دست رفتن روند در یک معامله باز که در سود است (`Trend Loss In Profit Detector`):
     * تشخیص می‌دهد کی معامله باز روند خود را از دست داده و دیگر ادامه نمی‌دهد تا همان‌جا که در سود است بسته شود.
     *
     * ۵ نشانه قطعی مرگ روند در سود بر اساس ۴ کتاب ایچیموکو:
     * ۱. شکست خط تعادل کیجون‌سن یا کراس معکوس تنکان/کیجون (`Kijun Breakdown / TK Counter-Cross`)
     * ۲. تخلیه کشیدگی C-Clamp با کندل بازگشتی و شدوی معکوس سنگین زیر/بالای تنکان (`C-Clamp Climax Rejection`)
     * ۳. تکمیل چرخه زمانی هوسودا (9, 17, 26, 33) همراه با فلت شدن تنکان و کندل خلاف جهت (`Hosoda Time Exhaustion`)
     * ۴. برخورد به دیوار فلت تایم بالاتر یا قفل شدن چیکو همراه با سقوط کارایی روند کافمن (`ER14 < 0.20`)
     * ۵. بازگشت قیمت از اوج سود کسب‌شده به زیر سنگر قفل سود تنکان/کیجون (`Profit Giveback Guard`)
     */
    fun evaluateTrendHealthAndProfitExit(
        trade: PaperTrade,
        report: StrategyEvaluationReport?,
        livePriceOverride: Double? = null,
    ): OpenTradeTrendHealthReport {
        val isBuy = trade.action == SignalAction.BUY
        val livePrice = livePriceOverride?.takeIf { it > 0.0 }
            ?: report?.livePrice?.takeIf { it > 0.0 }
            ?: trade.entry
        val initStop = trade.initialStopLoss ?: trade.stopLoss
        val oneR = abs(trade.entry - initStop).coerceAtLeast(1e-8)
        val currentProfitDist = if (isBuy) livePrice - trade.entry else trade.entry - livePrice
        val currentR = round2(currentProfitDist / oneR)
        val inProfit = currentR >= 0.35

        val trigSnap = report?.triggerSnapshot
        val stratSnap = report?.strategySnapshot ?: trigSnap
        val rulesSummary = "شروط خروج فوری در سود: ۱) کلوز کندل پشت کیجون یا کراس معکوس TK · ۲) شدوی بازگشتی C-Clamp زیر تنکان · ۳) اتمام چرخه زمانی هوسودا (9/17/26) با تنکان فلت · ۴) برخورد به دیوار فلت MTF و افت کارایی (ER<20%)"

        if (trigSnap == null || stratSnap == null) {
            return OpenTradeTrendHealthReport(
                tradeId = trade.id,
                state = OpenTradeTrendState.STRONG_CONTINUATION,
                continuationScorePercent = if (currentR >= 0.0) 85 else 65,
                currentProfitR = currentR,
                shouldExitNowInProfit = false,
                exitTriggerReasonFa = "",
                activeWarningSignalsFa = emptyList(),
                profitExitRulesSummaryFa = rulesSummary,
            )
        }

        // Only evaluate candle-snapshot structural breakdown if the snapshot is on the same price scale as livePrice
        val snapMatchesLiveScale = abs(trigSnap.close - livePrice) <= trigSnap.atr14 * 4.0
        val warnings = mutableListOf<String>()
        val fatalProfitTriggers = mutableListOf<String>()

        if (snapMatchesLiveScale) {
            // ۱. شکست خط تعادل کیجون‌سن یا کراس معکوس تنکان/کیجون
            val kijunBroken = if (isBuy) {
                trigSnap.close < trigSnap.kijun - trigSnap.atr14 * 0.06
            } else {
                trigSnap.close > trigSnap.kijun + trigSnap.atr14 * 0.06
            }
            val tkCounterCross = if (isBuy) {
                trigSnap.tenkan < trigSnap.kijun - trigSnap.atr14 * 0.04 && trigSnap.tenkanSlopeAtrPerBar < -0.03
            } else {
                trigSnap.tenkan > trigSnap.kijun + trigSnap.atr14 * 0.04 && trigSnap.tenkanSlopeAtrPerBar > 0.03
            }
            if (kijunBroken || tkCounterCross) {
                warnings += "شکست تعادل کیجون/کراس معکوس TK در تایم ${report.triggerInterval.label}"
                if (inProfit) {
                    fatalProfitTriggers += "شکست کیجون‌سن و چرخش تنکان (${round2(trigSnap.kijun)})"
                }
            }

            // ۲. تخلیه کشیدگی C-Clamp با کندل بازگشتی سنگین
            val adverseWick = if (isBuy) trigSnap.upperWickRatio else trigSnap.lowerWickRatio
            val closedBeyondTenkan = if (isBuy) trigSnap.close < trigSnap.tenkan else trigSnap.close > trigSnap.tenkan
            val cClampReversal = (stratSnap.distanceToKijunAtr >= 1.85 || trigSnap.isCClampWarning) &&
                adverseWick >= 0.48 && closedBeyondTenkan
            if (stratSnap.distanceToKijunAtr >= 1.75 || trigSnap.isCClampWarning) {
                warnings += "هشدار کشیدگی C-Clamp از کیجون (${String.format(Locale.US, "%.1f", stratSnap.distanceToKijunAtr)} ATR)"
            }
            if (cClampReversal && inProfit && currentR >= 0.65) {
                fatalProfitTriggers += "تخلیه کشیدگی C-Clamp با شدوی بازگشتی (${(adverseWick * 100).toInt()}%) و کلوز پشت تنکان"
            }

            // ۳. اتمام چرخه زمانی هوسودا همراه با فلت شدن تنکان و کندل خلاف جهت
            val oppositeCandle = if (isBuy) {
                trigSnap.close < trigSnap.open && trigSnap.closeLocationValue <= 0.35
            } else {
                trigSnap.close > trigSnap.open && trigSnap.closeLocationValue >= 0.65
            }
            val hosodaCycleExhausted = report.waveProjection?.isNearTimeCycleChangeDay == true &&
                trigSnap.isTenkanFlat && oppositeCandle && closedBeyondTenkan
            if (report.waveProjection?.isNearTimeCycleChangeDay == true) {
                warnings += "نزدیک روز چرخش چرخه زمانی هوسودا (عدد ${report.waveProjection.nearestTimeCycleNumber})"
            }
            if (hosodaCycleExhausted && inProfit && currentR >= 0.60) {
                fatalProfitTriggers += "اتمام چرخه زمانی هوسودا (${report.waveProjection?.nearestTimeCycleNumber}) و فلت شدن تنکان"
            }

            // ۴. برخورد چیکو اسپن به مانع و افت شدید کارایی روند کافمن (ER14 < 0.20)
            val chikouBlocked = if (isBuy) !trigSnap.chikouBullishOpenSpace else !trigSnap.chikouBearishOpenSpace
            val momentumCollapsed = chikouBlocked && trigSnap.efficiencyRatio14 < 0.20 && closedBeyondTenkan && oppositeCandle
            if (trigSnap.efficiencyRatio14 < 0.24) {
                warnings += "کاهش کارایی روند کافمن (ER14=${(trigSnap.efficiencyRatio14 * 100).toInt()}%)"
            }
            if (momentumCollapsed && inProfit && currentR >= 0.55) {
                fatalProfitTriggers += "قفل شدن چیکو و سقوط کارایی روند به ${(trigSnap.efficiencyRatio14 * 100).toInt()}%"
            }

            // ۵. تکنیک خروج محرمانه RSI موکرجی (کتاب درایو ۴ — comic 3): افت بیش از ۱۰ واحد از قله RSI یا شکست قله همراه با کلوز پشت تنکان
            val mukherjeeRsiFailure = if (isBuy) {
                trigSnap.rsiPeakDropLong >= 11.5 && closedBeyondTenkan && oppositeCandle
            } else {
                trigSnap.rsiTroughRiseShort >= 14.0 && closedBeyondTenkan && oppositeCandle
            }
            if (isBuy && trigSnap.rsiPeakDropLong >= 9.0) {
                warnings += String.format(Locale.US, "افت RSI از قله اخیر (%.1f واحد — قانون موکرجی)", trigSnap.rsiPeakDropLong)
            } else if (!isBuy && trigSnap.rsiTroughRiseShort >= 10.0) {
                warnings += String.format(Locale.US, "برگشت RSI از کف اخیر (%.1f واحد — قانون موکرجی)", trigSnap.rsiTroughRiseShort)
            }
            if (mukherjeeRsiFailure && inProfit && currentR >= 0.70) {
                fatalProfitTriggers += String.format(
                    Locale.US,
                    "سیگنال خروج RSI موکرجی (انحراف %.1f واحد از اکسترمم RSI با کلوز پشت تنکان)",
                    if (isBuy) trigSnap.rsiPeakDropLong else trigSnap.rsiTroughRiseShort,
                )
            }

            // ۶. سیستم ۴ قانون خروج کونیتز (کتاب‌های ۸ و ۹): شکست هم‌زمان کیجون و EMA34 همراه با برگشت MACD/DMI
            val koonitz4RuleExit = if (isBuy) {
                trigSnap.close < trigSnap.ema34 && trigSnap.close < trigSnap.kijun &&
                    (trigSnap.isMacdBearish || trigSnap.isDmiBearish)
            } else {
                trigSnap.close > trigSnap.ema34 && trigSnap.close > trigSnap.kijun &&
                    (trigSnap.isMacdBullish || trigSnap.isDmiBullish)
            }
            if (koonitz4RuleExit && inProfit && currentR >= 0.50) {
                fatalProfitTriggers += "تکمیل ۴ قانون خروج کونیتز (شکست هم‌زمان کیجون و EMA34 با چرخش MACD/DMI)"
            }
        }

        val shouldExitInProfit = inProfit && fatalProfitTriggers.isNotEmpty()
        val rawScore = 92 - (warnings.size * 18) - (fatalProfitTriggers.size * 35) + (if (currentR >= 1.5) 8 else 0)
        val score = rawScore.coerceIn(10, 99)

        val state = when {
            shouldExitInProfit -> OpenTradeTrendState.TREND_LOST_EXIT_IN_PROFIT
            warnings.isNotEmpty() -> OpenTradeTrendState.MOMENTUM_COOLING_LOCK_ACTIVE
            else -> OpenTradeTrendState.STRONG_CONTINUATION
        }

        val exitReason = if (shouldExitInProfit) {
            "⚡ خروج هوشمند در سود (+${String.format(Locale.US, "%.2f", currentR)}R) به دلیل اتمام روند: ${fatalProfitTriggers.joinToString(" + ")}"
        } else {
            ""
        }

        return OpenTradeTrendHealthReport(
            tradeId = trade.id,
            state = state,
            continuationScorePercent = score,
            currentProfitR = currentR,
            shouldExitNowInProfit = shouldExitInProfit,
            exitTriggerReasonFa = exitReason,
            activeWarningSignalsFa = warnings,
            profitExitRulesSummaryFa = rulesSummary,
        )
    }

    /**
     * مدیریت پویا ۵ مرحله‌ای معاملات باز با موتور جهانی `Chandelier ATR + Tenkan/Kijun Hybrid Ratchet`
     * + تشخیص لحظه‌ای از دست رفتن روند در سود (`shouldCloseImmediatelyInProfit`):
     * ۰. اگر معامله در سود است و روند ایچیموکو از دست برود (شکست کیجون / تخلیه C-Clamp / چرخه زمانی هوسودا / قفل چیکو)،
     *    معامله همان‌جا در سود بسته می‌شود تا سود از بین نرود.
     * ۱. قفل زودهنگام سود در `0.65R` و ریسک‌فری پلاس خودکار (`Break-Even + Fee` در `1.0R`)
     * ۲. قفل سود پایه و تریل کیجون‌سن (`1.5R`)
     * ۳. تثبیت قطعی کف سود `1:1.5` (`+1.50R` در `2.15R`)
     * ۴. عبور از مرز `1:3` (`>= 2.85R..3.0R+`): قرار دادن حد ضرر در نقطه طلایی (ترکیب قفل پلکانی `75%–82%` سود +
     *    سپر نوسانگیر شندلیر `ATR` و `Tenkan-sen`) به همراه امتداد خودکار `TP` تا موج بعدی هوسودا.
     */
    fun evaluateOpenTradeManagement(
        trade: PaperTrade,
        report: StrategyEvaluationReport,
        config: BaseStrategyConfig,
    ): PreserveModeEvaluation? {
        if (!trade.isOpen || V1Universe.normalize(trade.symbol) != V1Universe.normalize(report.symbol)) return null
        val trigSnap = report.triggerSnapshot ?: return null
        val stratSnap = report.strategySnapshot ?: trigSnap
        val livePrice = report.livePrice.takeIf { it > 0.0 } ?: return null

        val initialStop = trade.initialStopLoss ?: trade.stopLoss
        val oneRDistance = abs(trade.entry - initialStop).coerceAtLeast(trigSnap.atr14 * 0.4)
        val isBuy = trade.action == SignalAction.BUY

        val currentProfitDist = if (isBuy) livePrice - trade.entry else trade.entry - livePrice
        val currentRMultiple = currentProfitDist / oneRDistance

        // ── Stage 0: تشخیص از دست رفتن روند در سود و بستن فوری در همان نقطه سود ──
        val trendHealth = evaluateTrendHealthAndProfitExit(trade, report, livePrice)
        if (trendHealth.shouldExitNowInProfit && currentRMultiple > 0.35) {
            return PreserveModeEvaluation(
                tradeId = trade.id,
                newStopLoss = round2(livePrice),
                stageTitleFa = "⚡ خروج هوشمند در سود (اتمام روند در +${round2(currentRMultiple)}R)",
                reasonFa = trendHealth.exitTriggerReasonFa,
                lockedRMultiple = round2(currentRMultiple),
                shouldCloseImmediatelyInProfit = true,
                closePrice = round2(livePrice),
            )
        }

        val nearTimeCycleAndFlatTenkan = report.waveProjection?.isNearTimeCycleChangeDay == true &&
            (trigSnap.isTenkanFlat || stratSnap.isTenkanFlat)

        val overextendedFromKijun = stratSnap.distanceToKijunAtr >= config.maxKijunExtensionAtr

        // ── Stage 4: بستن کامل و قطعی معامله در رسیدن به ۳R (۱:۳) یا TP بدون ادامه تریل استاپ ──
        val reachedTp = if (isBuy) livePrice >= trade.takeProfit else livePrice <= trade.takeProfit
        if (currentRMultiple >= 3.0 || reachedTp) {
            return PreserveModeEvaluation(
                tradeId = trade.id,
                newStopLoss = round2(livePrice),
                stageTitleFa = "🎯 اتمام و بستن کامل معامله در هدف ۳R (+${round2(currentRMultiple)}R)",
                reasonFa = String.format(
                    Locale.US,
                    "رسیدن به سقف ۱:۳ (%.2fR)؛ بستن کامل معامله و تسویه سود بدون ادامه تریل استاپ",
                    currentRMultiple,
                ),
                lockedRMultiple = round2(currentRMultiple),
                shouldCloseImmediatelyInProfit = true,
                closePrice = round2(livePrice),
            )
        }

        // ── Stage 3: Pre-3R Guaranteed 1:1.5 Floor + Preserve Mode (`2.15R .. < 2.85R`) ──
        if (currentRMultiple >= 2.15 || (config.enablePreserveMode && currentRMultiple >= 1.5 && (overextendedFromKijun || nearTimeCycleAndFlatTenkan))) {
            val floor15R = if (isBuy) {
                trade.entry + oneRDistance * (if (currentRMultiple >= 2.15) 1.50 else 0.95)
            } else {
                trade.entry - oneRDistance * (if (currentRMultiple >= 2.15) 1.50 else 0.95)
            }
            val tenkanBuffer = trigSnap.atr14 * 0.18
            val tenkanStop = if (isBuy) trigSnap.tenkan - tenkanBuffer else trigSnap.tenkan + tenkanBuffer
            val minBreathing = max(trigSnap.atr14 * 0.30, oneRDistance * 0.25)
            val candidateStop = if (isBuy) {
                round2(min(max(floor15R, tenkanStop), livePrice - minBreathing))
            } else {
                round2(max(min(floor15R, tenkanStop), livePrice + minBreathing))
            }
            val improvesStop = if (isBuy) {
                candidateStop > trade.stopLoss + trigSnap.atr14 * 0.05 && candidateStop < livePrice - trigSnap.atr14 * 0.1
            } else {
                candidateStop < trade.stopLoss - trigSnap.atr14 * 0.05 && candidateStop > livePrice + trigSnap.atr14 * 0.1
            }
            if (improvesStop) {
                val lockedR = round2(abs(candidateStop - trade.entry) / oneRDistance)
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = candidateStop,
                    stageTitleFa = "🛡️ قفل قطعی سود حداقل ۱:۱.۵ (+${lockedR}R)",
                    reasonFa = String.format(Locale.US, "سود %.2fR؛ تثبیت سود +%.2fR پشت تنکان/کیجون", currentRMultiple, lockedR),
                    lockedRMultiple = lockedR,
                )
            }
        }

        // ── Stage 2: Base Profit Lock + Kijun-sen Trailing (`1.50R .. < 2.15R`) ──
        if (currentRMultiple >= 1.50) {
            val kijunBuffer = trigSnap.atr14 * config.slBufferAtr
            val profitFloor065R = if (isBuy) trade.entry + oneRDistance * 0.65 else trade.entry - oneRDistance * 0.65
            val kijunStop = if (isBuy) trigSnap.kijun - kijunBuffer else trigSnap.kijun + kijunBuffer
            val candidateStop = if (isBuy) {
                round2(min(max(profitFloor065R, kijunStop), livePrice - trigSnap.atr14 * 0.25))
            } else {
                round2(max(min(profitFloor065R, kijunStop), livePrice + trigSnap.atr14 * 0.25))
            }
            val improvesBeyondEntry = if (isBuy) {
                candidateStop >= trade.entry && candidateStop > trade.stopLoss + trigSnap.atr14 * 0.05 && candidateStop < livePrice
            } else {
                candidateStop <= trade.entry && candidateStop < trade.stopLoss - trigSnap.atr14 * 0.05 && candidateStop > livePrice
            }
            if (improvesBeyondEntry) {
                val lockedR = round2(abs(candidateStop - trade.entry) / oneRDistance)
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = candidateStop,
                    stageTitleFa = "📈 تریل کیجون و قفل سود (+${lockedR}R)",
                    reasonFa = String.format(Locale.US, "عبور سود از ۱:۱.۵ (%.2fR)؛ قفل سود در +%.2fR", currentRMultiple, lockedR),
                    lockedRMultiple = lockedR,
                )
            }
        }

        // ── Stage 1: Break-Even Plus Shield (`>= 1.0R`) ──────────────────────
        if (currentRMultiple >= 1.0) {
            val feeOffset = max(oneRDistance * 0.12, max(trade.entry * 0.0008, trigSnap.atr14 * 0.05))
            val breakEvenStop = if (isBuy) round2(trade.entry + feeOffset) else round2(trade.entry - feeOffset)
            val notYetBreakEven = if (isBuy) {
                trade.stopLoss < trade.entry + feeOffset * 0.5 && breakEvenStop < livePrice
            } else {
                trade.stopLoss > trade.entry - feeOffset * 0.5 && breakEvenStop > livePrice
            }
            if (notYetBreakEven) {
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = breakEvenStop,
                    stageTitleFa = "🔒 ریسک‌فری پلاس خودکار (Break-Even در 1R)",
                    reasonFa = "عبور سود از 1R؛ انتقال حد ضرر به بالای نقطه ورود (+0.12R)",
                    lockedRMultiple = 0.12,
                )
            }
        }

        // ── Stage 0.5: Early Profit Shield (`>= 0.65R` when momentum cools) ──
        if (currentRMultiple >= 0.65 && trendHealth.activeWarningSignalsFa.isNotEmpty()) {
            val earlyLockOffset = max(oneRDistance * 0.22, trade.entry * 0.0015)
            val earlyProfitStop = if (isBuy) round2(trade.entry + earlyLockOffset) else round2(trade.entry - earlyLockOffset)
            val improvesEarly = if (isBuy) {
                trade.stopLoss < earlyProfitStop - trigSnap.atr14 * 0.03 && earlyProfitStop < livePrice - trigSnap.atr14 * 0.10
            } else {
                trade.stopLoss > earlyProfitStop + trigSnap.atr14 * 0.03 && earlyProfitStop > livePrice + trigSnap.atr14 * 0.10
            }
            if (improvesEarly) {
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = earlyProfitStop,
                    stageTitleFa = "🛡️ قفل زودهنگام سود در کاهش مومنتوم (+0.22R)",
                    reasonFa = "تشخیص کاهش شتاب روند در سود (${trendHealth.activeWarningSignalsFa.first()})؛ قفل سود در +0.22R",
                    lockedRMultiple = 0.22,
                )
            }
        }

        return null
    }

    private fun mergeNearbyLevels(levels: List<Double>, tolerance: Double): List<Double> {
        if (levels.isEmpty()) return emptyList()
        val sorted = levels.sorted()
        val merged = mutableListOf<Double>()
        var clusterSum = sorted[0]
        var clusterCount = 1
        for (i in 1 until sorted.size) {
            val prevAvg = clusterSum / clusterCount
            if (abs(sorted[i] - prevAvg) <= tolerance) {
                clusterSum += sorted[i]
                clusterCount++
            } else {
                merged += clusterSum / clusterCount
                clusterSum = sorted[i]
                clusterCount = 1
            }
        }
        merged += clusterSum / clusterCount
        return merged
    }

    /**
     * آناتومی و دسته‌بندی ۳ کندل قبل از ورود به معامله (`C-2`, `C-1`, `C0`)
     * تا مشخص شود هر معامله دقیقاً در چه شرایط کندلی، نسبت به تنکان/کیجون/کومو و با چه آرایش شدو و بدنه باز شده است.
     */
    fun buildPreEntry3CandleContext(
        candles: List<Candle>,
        action: SignalAction,
        activeModel: BitcoinStrategyModel = BitcoinStrategyModel.BASE_ICHIMOKU,
    ): PreEntry3CandleContext {
        if (candles.isEmpty()) {
            return PreEntry3CandleContext(
                categoryCode = PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER.code,
                categoryBadgeFa = PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER.badgeFa,
                categoryTitleFa = PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER.titleFa,
                summaryFa = "منتظر دریافت کندل‌های قبل از ورود",
                candles = emptyList(),
            )
        }
        val isBuy = action != SignalAction.SELL
        val (tenkanPeriod, kijunPeriod, senkouBPeriod) = Triple(9, 26, 52)
        val displacement = kijunPeriod

        // ترجیحاً از آخرین کندل بسته‌شده (یا در صورت نبود، آخرین کندل) و ۲ کندل قبل از آن استفاده می‌کنیم
        val closedPart = candles.filter { it.closed }
        val pool = if (closedPart.size >= 3) closedPart else candles
        val endIdx = pool.lastIndex
        val startIdx = max(0, endIdx - 2)

        fun midpointAt(bars: List<Candle>, toIdx: Int, period: Int): Double {
            val safeTo = toIdx.coerceIn(0, bars.lastIndex)
            val from = (safeTo - period + 1).coerceIn(0, safeTo)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (k in from..safeTo) {
                if (bars[k].high > hh) hh = bars[k].high
                if (bars[k].low < ll) ll = bars[k].low
            }
            return (hh + ll) / 2.0
        }

        val atrPeriod = min(14, pool.size - 1).coerceAtLeast(1)
        var trSum = 0.0
        for (k in (endIdx - atrPeriod + 1).coerceAtLeast(0)..endIdx) {
            val cur = pool[k]
            val prevC = pool[(k - 1).coerceAtLeast(0)].close
            trSum += maxOf(cur.high - cur.low, abs(cur.high - prevC), abs(cur.low - prevC))
        }
        val atr14 = (trSum / atrPeriod).coerceAtLeast(pool[endIdx].close * 0.0005).coerceAtLeast(1e-6)

        val slots = mutableListOf<PreEntryCandleSlot>()
        for (i in startIdx..endIdx) {
            val c = pool[i]
            val slotOffset = i - endIdx // -2, -1, 0
            val slotTitle = when (slotOffset) {
                -2 -> "کندل ۳ قبل (C-2)"
                -1 -> "کندل ۲ قبل (C-1)"
                else -> "کندل ۱ قبل / ماشه (C0)"
            }
            val range = (c.high - c.low).coerceAtLeast(1e-8)
            val body = abs(c.close - c.open)
            val upperWick = c.high - max(c.open, c.close)
            val lowerWick = min(c.open, c.close) - c.low
            val bodyPct = ((body / range) * 100.0).toInt().coerceIn(0, 100)
            val upWickPct = ((upperWick / range) * 100.0).toInt().coerceIn(0, 100)
            val lowWickPct = ((lowerWick / range) * 100.0).toInt().coerceIn(0, 100)
            val rangeAtr = round2(range / atr14)
            val bullish = c.close >= c.open

            val tVal = midpointAt(pool, i, min(tenkanPeriod, i + 1))
            val kVal = midpointAt(pool, i, min(kijunPeriod, i + 1))
            val pastIdx = max(0, i - displacement + 1)
            val spanA = (midpointAt(pool, pastIdx, min(tenkanPeriod, pastIdx + 1)) + midpointAt(pool, pastIdx, min(kijunPeriod, pastIdx + 1))) / 2.0
            val spanB = midpointAt(pool, pastIdx, min(senkouBPeriod, pastIdx + 1))
            val kTop = max(spanA, spanB)
            val kBot = min(spanA, spanB)

            val vsTenkan = when {
                abs(c.close - tVal) <= atr14 * 0.15 || (c.low <= tVal && c.high >= tVal) -> "پولبک/لمس تنکان"
                c.close > tVal -> "بالای تنکان"
                else -> "زیر تنکان"
            }
            val vsKijun = when {
                abs(c.close - kVal) <= atr14 * 0.18 || (c.low <= kVal && c.high >= kVal) -> "پولبک/لمس کیجون"
                c.close > kVal -> "بالای کیجون"
                else -> "زیر کیجون"
            }
            val vsKumo = when {
                c.close > kTop -> "بالای کومو"
                c.close < kBot -> "زیر کومو"
                else -> "داخل کومو"
            }

            val dirStr = if (bullish) "🟢 صعودی" else "🔴 نزولی"
            val anatomy = "$dirStr · بدنه $bodyPct% · شدو بالا $upWickPct% / پایین $lowWickPct% · ${rangeAtr}×ATR"
            val role = when (slotOffset) {
                -2 -> if ((isBuy && !bullish) || (!isBuy && bullish)) {
                    "آغاز اصلاح به سمت تعادل ($vsTenkan)"
                } else {
                    "پیش‌ران روند ($vsTenkan · $vsKumo)"
                }
                -1 -> if (isBuy && (lowWickPct >= 30 || c.low <= tVal * 1.001)) {
                    "تست و ریجکت شدو از حمایت ($vsTenkan / $vsKijun)"
                } else if (!isBuy && (upWickPct >= 30 || c.high >= tVal * 0.999)) {
                    "تست و ریجکت شدو از مقاومت ($vsTenkan / $vsKijun)"
                } else {
                    "تثبیت ماقبل ورود ($vsTenkan)"
                }
                else -> if ((isBuy && bullish && bodyPct >= 55) || (!isBuy && !bullish && bodyPct >= 55)) {
                    "کندل ماشه پرقدرت در جهت معامله ($vsTenkan · $vsKumo)"
                } else {
                    "کندل تایید ورود ($vsTenkan · $vsKumo)"
                }
            }

            slots += PreEntryCandleSlot(
                slotIndex = slotOffset,
                slotTitleFa = slotTitle,
                barTime = c.time,
                open = round2(c.open),
                high = round2(c.high),
                low = round2(c.low),
                close = round2(c.close),
                isBullish = bullish,
                bodyPctOfRange = bodyPct,
                upperWickPct = upWickPct,
                lowerWickPct = lowWickPct,
                rangeAtrRatio = rangeAtr,
                tenkanValue = round2(tVal),
                kijunValue = round2(kVal),
                kumoTop = round2(kTop),
                kumoBottom = round2(kBot),
                priceVsTenkanFa = vsTenkan,
                priceVsKijunFa = vsKijun,
                priceVsKumoFa = vsKumo,
                candleAnatomyFa = anatomy,
                roleInSetupFa = role,
            )
        }

        // دسته‌بندی الگوی ۳ کندلی
        val c0 = slots.last()
        val c1 = slots.getOrNull(slots.size - 2) ?: c0
        val c2 = slots.getOrNull(slots.size - 3) ?: c1

        val all3Aligned = slots.size == 3 && slots.all { it.isBullish == isBuy && it.bodyPctOfRange >= 45 }
        val insideBarCompression = (c1.high <= c2.high && c1.low >= c2.low) || (c1.rangeAtrRatio <= 0.65 && c0.rangeAtrRatio >= 0.95)
        val engulfingTrigger = (c1.isBullish != isBuy) && (c0.isBullish == isBuy) &&
            abs(c0.close - c0.open) > abs(c1.close - c1.open) * 1.05
        val pullbackReject = (c2.priceVsTenkanFa.contains("پولبک") || c1.priceVsTenkanFa.contains("پولبک") ||
            c1.priceVsKijunFa.contains("پولبک") ||
            (isBuy && c1.lowerWickPct >= 32) || (!isBuy && c1.upperWickPct >= 32)) && (c0.isBullish == isBuy)
        val cClampChase = abs(c0.close - c0.kijunValue) > atr14 * 1.85 && all3Aligned

        val category = when {
            cClampChase -> PreEntry3CandleCategory.CCLAMP_EXTENDED_CHASE
            pullbackReject -> PreEntry3CandleCategory.PULLBACK_REJECTION_IGNITION
            engulfingTrigger -> PreEntry3CandleCategory.ENGULFING_REVERSAL_OFF_LEVEL
            insideBarCompression -> PreEntry3CandleCategory.INSIDE_BAR_COMPRESSION_EXPANSION
            all3Aligned -> PreEntry3CandleCategory.THREE_SOLDIERS_MOMENTUM_BREAKOUT
            else -> PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER
        }

        val summary = buildString {
            append("${category.badgeFa}: ")
            slots.forEachIndexed { idx, s ->
                if (idx > 0) append(" ⬅ ")
                val tag = when (s.slotIndex) {
                    -2 -> "[۳ قبل]"
                    -1 -> "[۲ قبل]"
                    else -> "[۱ قبل/ماشه]"
                }
                val dir = if (s.isBullish) "صعودی" else "نزولی"
                append("$tag $dir(بدنه ${s.bodyPctOfRange}% · ${s.priceVsTenkanFa})")
            }
        }

        return PreEntry3CandleContext(
            categoryCode = category.code,
            categoryBadgeFa = category.badgeFa,
            categoryTitleFa = category.titleFa,
            summaryFa = summary,
            candles = slots,
        )
    }

    fun round2(v: Double): Double = round2Internal(v)
}

private fun round2(v: Double): Double = round2Internal(v)

private fun round2Internal(v: Double): Double {
    if (!v.isFinite()) return 0.0
    val a = abs(v)
    val factor = when {
        a >= 500.0 -> 100.0
        a >= 10.0 -> 1_000.0
        a >= 0.01 -> 100_000.0
        else -> 1_000_000.0
    }
    return round(v * factor) / factor
}
