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
    /** این روش فقط با انتخاب صریح کاربر به فهرست ارزیابی اضافه می‌شود. */
    val requiresExplicitOptIn: Boolean = false,
    /** روش‌های تازهٔ BTC و پارسا از یادگیری، گیت و تیونینگ عصبی مستثنا هستند. */
    val neuralLearningEnabled: Boolean = true,
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

    // ── ۵. استراتژی پیمان — نسخهٔ الیوت–نئوویو (موقتاً غیرفعال) ─────────────────
    // بنا به درخواست کاربر، از متود جامع قبلی فقط «لایهٔ الیوت–نئوویو» باقی مانده است؛ بقیهٔ
    // لایه‌ها (اجماع لایه‌های ساختاری، گیت کیجون و …) حذف شده و خود استراتژی هم تا اطلاع بعدی
    // خاموش است تا معامله‌ای با آن باز نشود. شناسهٔ داخلی برای حفظ آموخته‌های شبکه تغییر نکرده.
    DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER(
        id = "DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER",
        titleFa = "استراتژی ۱۶ · استراتژی پیمان (الیوت–نئوویو) · موقتاً غیرفعال",
        badgeFa = "استراتژی پیمان · الیوت · غیرفعال",
        authorRefFa = "Elliott Wave + NeoWave (Glenn Neely) — هندسهٔ موجی",
        summaryFa = "استراتژی پیمان — نسخهٔ الیوت‌محور و موقتاً غیرفعال. " +
            "۱) تنها لایهٔ باقی‌مانده، هندسهٔ امواج الیوت و قواعد نئوویو است: جهت موج جاری، " +
            "معتبر بودن نقطهٔ چرخش (S)، تکمیل‌نشدن موج مخالف و فضای اهداف موجی V/N/E. " +
            "۲) ورود فقط وقتی مجاز است که موج جهت‌دار تأییدشده باشد، نقطهٔ S نشکسته باشد و " +
            "چرخهٔ زمانی در نقطهٔ تغییر نباشد. " +
            "۳) خروج بر پایهٔ اتمام موج/ابطال شمارش انجام می‌شود. " +
            "۴) لایه‌های ساختاری، اجماع چندلایه و گیت کیجونِ نسخهٔ قبلی از این استراتژی حذف شده‌اند. " +
            "۵) این استراتژی فعلاً در هیچ ارزیابی و معامله‌ای شرکت نمی‌کند؛ با روشن‌کردن دوباره از " +
            "تنظیمات، فقط همان لایهٔ الیوت فعال می‌شود.",
        hasPatel = false,
        hasTuttle = false,
        hasHosoda = true,
        hasPeloille = false,
        baseOptimalRr = 2.25,
    ),
    BTC_52_CANDLE_REGIME(
        id = "BTC_52_CANDLE_REGIME",
        titleFa = "بیت‌کوین · ساختار ۵۲ کندلی و شکست/بازآزمایی",
        badgeFa = "BTC · پنجرهٔ ۵۲ کندل",
        authorRefFa = "روش مستقل بیت‌کوین · ساختار روند/فشردگی/شکست ۵۲ کندلی",
        summaryFa = "فقط برای BTC/USDT: سه کندل آخر الگوی تصمیم نیستند. روش، پنجرهٔ ۵۲ کندل بسته را برای جهت و جایگاه قیمت می‌سنجد؛ پس از فشردگی یا همگرایی، شکست معتبر سقف/کف ۵۲ کندلی یا بازآزمایی همان مرز را با شیب‌های ایچیموکو، ابر، آزادی چیکو، حجم و گسترش نوسان تأیید می‌کند. ورودِ تعقیبیِ بیش‌ازحد دور از کیجون رد می‌شود.",
        hasPatel = false,
        hasTuttle = false,
        hasHosoda = false,
        hasPeloille = false,
        baseOptimalRr = 2.20,
        requiresExplicitOptIn = true,
        neuralLearningEnabled = false,
    ),
    PARSA_52_LINE_TURN(
        id = "PARSA_52_LINE_TURN",
        titleFa = "استراتژی پارسا · چرخش ابر پس از فلت ۵۲",
        badgeFa = "پارسا · فلت >۱۷ + چرخش + کراس",
        authorRefFa = "روش مستقل پارسا · ایچیموکو ۹/۲۶/۵۲",
        summaryFa = "«خط ۵۲» در این روش صریحاً Senkou Span B (SSB، خط دورهٔ ۵۲) است. فقط پس از تخت‌ماندن SSB دست‌کم ۱۸ کندل بسته، چرخش Senkou Span A و کراس هم‌جهت تنکان/کیجون بررسی می‌شوند؛ سپس شیب و فاصلهٔ خطوط، آزادی Chikou، کیفیت شکست و نشانه‌های حرکت شارپ فیلتر می‌شوند. روش به یادگیری و تیونینگ عصبی وصل نیست.",
        hasPatel = false,
        hasTuttle = false,
        hasHosoda = false,
        hasPeloille = false,
        baseOptimalRr = 2.15,
        requiresExplicitOptIn = true,
        neuralLearningEnabled = false,
    );

    val isDrivePdfStrategy: Boolean
        get() = this == DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER

    /**
     * تنها استراتژی‌ای که لایهٔ **نئوویو–الیوت** در آن ادغام می‌شود: استراتژی جایگاه ۱۶.
     * تمام روش‌های دیگر، ازجمله دو روش مستقل BTC و پارسا، هیچ محاسبهٔ نئوویو ندارند.
     */
    val isNeoWaveElliottStrategy: Boolean
        get() = this == DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER

    /**
     * استراتژی‌هایی که کاربر موقتاً خاموش کرده است. شناسه و آموخته‌های شبکه‌شان حفظ می‌شود،
     * اما در هیچ ارزیابی/معامله‌ای شرکت نمی‌کنند تا با روشن‌شدن دوباره از صفر شروع نشوند.
     */
    val isTemporarilyDisabled: Boolean
        get() = id in temporarilyDisabledIds

    val combinationCount: Int
        get() = listOf(hasPatel, hasTuttle, hasHosoda, hasPeloille).count { it }

    val combinationCategoryFa: String
        get() = if (this == DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER) {
            "الیوت–نئوویو (موقتاً غیرفعال)"
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

        /** استراتژی‌های موقتاً خاموش (فعلاً فقط استراتژی پیمان / جایگاه ۱۶). */
        val temporarilyDisabledIds: Set<String> = setOf(DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER.id)

        /** مدل‌هایی که یادگیری آنلاین و تیونینگ عصبی برایشان مجاز است. */
        val neuralLearningModels: List<BitcoinStrategyModel>
            get() = entries.filter { it.neuralLearningEnabled }

        /** شناسهٔ مدل‌های پیش‌فرض ارزیابی؛ روش‌های opt-in فقط پس از انتخاب صریح کاربر فعال می‌شوند. */
        val activeStrategyIds: Set<String>
            get() = entries.filterNot { it.isTemporarilyDisabled || it.requiresExplicitOptIn }.map { it.id }.toSet()

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
 * شناسنامه فنی و دفترچه قوانین متود جامع یکپارچهٔ ۹ لایه و ۱۵ ترکیب پایه.
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
    BitcoinStrategyModel.BTC_52_CANDLE_REGIME -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "روش مستقل بیت‌کوین بر پایهٔ زمینهٔ ۵۲ کندلی",
        authorAndEdition = "قواعد اختصاصی این اپ · Ichimoku 9/26/52",
        ichimokuSettingsFa = "پنجرهٔ ۵۲ کندل بسته در تایم‌فریم راهبرد و ماشه؛ کندل جاری فقط برای عبورِ بسته‌شده از مرز به‌کار می‌رود. سه کندل آخر هیچ الگوی مستقلی برای رأی‌دادن ندارند.",
        auxiliaryIndicatorsFa = "رگرسیون و تغییر قیمت ۵۲ کندلی، دامنه/فشردگی نیمهٔ اخیر نسبت به نیمهٔ قبل، شیب TK و Senkou A، فاصله از Kijun، آزادی Chikou، ATR(14)/میانگین دامنهٔ ۵۲ کندلی و حجم نسبی.",
        entryChecklistStepsFa = listOf(
            "۱. نماد باید BTC/USDT باشد و دادهٔ بسته‌شدهٔ کافی برای پنجرهٔ ۵۲ کندلی موجود باشد.",
            "۲. جهت باید با شیب رگرسیون ۵۲ کندلی و جایگاه قیمت نسبت به میانهٔ دامنهٔ قبلی همسو باشد؛ سه کندل اخیر به‌تنهایی جهت نمی‌سازند.",
            "۳. ورود فقط روی شکست بسته‌شدهٔ سقف/کف ۵۲ کندل قبل یا بازآزمایی معتبر همان مرز است؛ عبورِ شدویی کافی نیست.",
            "۴. شیب Tenkan/Kijun و Senkou A نباید با جهت مخالف باشند؛ فاصلهٔ قیمت از Kijun باید در محدودهٔ قابل‌تعقیب بماند.",
            "۵. Chikou باید از کندل، خطوط TK و ابر در مسیر معامله آزاد باشد؛ حجم/بدنه و گسترش ATR حرکت را تأیید کنند.",
            "۶. در فشردگی بدون شکست، حجم ضعیف، شدوی مخالف بزرگ، ورود بیش‌ازحد کشیده یا فضای ناکافی تا مانع، فقط انتظار؛ بدون ورود.",
        ),
        stopLossFormulaFa = "پشت مرز شکست/کف یا سقف ساختاری ۵۲ کندلی با بافر ATR؛ سپس اعتبارسنجی اجباری TradePlanGuard.",
        takeProfitAndExitRulesFa = "هدف بر پایهٔ نوسان و مانع ساختاری نزدیک، با R:R هرگز کمتر از ۱:۱.۵؛ خروج هوشمند فقط پس از عبور سود باز از ۲R.",
        specialEdgeAdvantageFa = "روش مستقل و BTC-only؛ با زمینهٔ ۵۲ کندلی تصمیم می‌گیرد، از الگوی سه کندل آخر استفاده نمی‌کند و از یادگیری/تیونینگ عصبی مستثناست.",
    )

    BitcoinStrategyModel.PARSA_52_LINE_TURN -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "استراتژی پارسا · فلت SSB و چرخش ابر",
        authorAndEdition = "قواعد اختصاصی پارسا · Ichimoku 9/26/52",
        ichimokuSettingsFa = "خط ۵۲ در این روش دقیقاً Senkou Span B (SSB) است؛ تخت‌ماندن یعنی دامنهٔ SSB در دست‌کم ۱۸ کندل بسته از بزرگ‌ترِ ۰٫۰۴ ATR یا ۰٫۰۳٪ قیمت بیشتر نشود.",
        auxiliaryIndicatorsFa = "چرخش شیب Senkou Span A محاسبه‌شده برای ابر آینده، سن کراس Tenkan/Kijun، شیب TK/ابر، فاصلهٔ TK و قیمت از Kijun، آزادی Chikou، ATR و حجم نسبی.",
        entryChecklistStepsFa = listOf(
            "۱. پیش‌شرط قطعی: SSB/خط دورهٔ ۵۲ حداقل ۱۸ کندل بسته تخت مانده باشد؛ ۱۷ کندل کافی نیست.",
            "۲. Senkou Span A محاسبه‌شده برای ابر آینده باید از شیب مخالف به چرخش هم‌جهت برسد؛ صرفاً صاف‌شدن خط، چرخش محسوب نمی‌شود.",
            "۳. کراس Tenkan/Kijun هم‌جهت باید در پنجرهٔ حداکثر ۸ کندل بسته رخ داده باشد؛ الگوی سه کندلی شرط نیست.",
            "۴. شیب Tenkan و Senkou A باید همسو، شیب Kijun نباید به‌شدت مخالف و فاصلهٔ TK باید در حال بازشدن اما نه بیش‌ازحد باشد.",
            "۵. Chikou باید از قیمت/کندل‌های متناظر، TK و ابر آزاد باشد؛ بدنه، حجم و گسترش ATR باید شکست/حرکت شارپ را تأیید کنند.",
            "۶. ورود تعقیبی دور از Kijun، کراس کهنه، ابر/چیکوی مسدود، یا پلن فاقد R:R≥۱٫۵ ممنوع است.",
        ),
        stopLossFormulaFa = "پشت SSB تخت یا ساختار اخیرِ همسو با ATR؛ عبور از TradePlanGuard الزامی است.",
        takeProfitAndExitRulesFa = "هدف در محدودهٔ ۱٫۵R تا ۳R و پیش از مانع ساختاری؛ خروج هوشمند فقط پس از عبور سود باز از ۲R.",
        specialEdgeAdvantageFa = "شرط ۱۸+ کندلی SSB، چرخش Senkou A و کراس TK همگی لازم‌اند؛ روش از یادگیری، گیت و تیونینگ عصبی مستثناست.",
    )

    BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Elliott Wave + NeoWave Geometry — استراتژی پیمان (نسخهٔ الیوت)",
        authorAndEdition = "Elliott Wave Theory + NeoWave (Glenn Neely) — فقط لایهٔ موجی",
        ichimokuSettingsFa = "ایچیموکوی استاندارد (9, 26, 52) فقط به‌عنوان مرجع ساختار موج و تعیین نقطهٔ پایان اصلاح",
        auxiliaryIndicatorsFa = "شمارش امواج الیوت (Impulse/Corrective) + قواعد نئوویو + نقطهٔ چرخش S + اهداف موجی V/N/E + چرخهٔ زمانی هوسودا",
        entryChecklistStepsFa = listOf(
            "۱. شمارش موج جهت‌دار کامل و تأییدشده باشد (Impulse معتبر یا پایان اصلاح مشخص)",
            "۲. جهت موج جاری هم‌سو با جهت معامله باشد و نقطهٔ چرخش S نشکسته باشد",
            "۳. هدف موجی بعدی (V/N/E) فضای کافی برای حداقل ۱:۱.۵ فراهم کند",
            "۴. چرخهٔ زمانی در نقطهٔ تغییر نباشد و ناحیهٔ ورود هم‌تراز با مرز موج باشد",
        ),
        stopLossFormulaFa = "حد ضرر ساختاری پشت نقطهٔ ابطال شمارش موج (S-Point / انتهای موج قبلی) با بافر ATR",
        takeProfitAndExitRulesFa = "خروج روی هدف موجی بعدی؛ اگر شمارش موج باطل شود یا خروج هوشمند مجاز باشد " +
            "(فقط بالای +۲R) معامله بسته می‌شود",
        specialEdgeAdvantageFa = "تمرکز کامل روی هندسهٔ موج: بدون اجماع چندلایه، بدون گیت کیجون و بدون " +
            "تأییدهای شاخصی اضافه. این استراتژی فعلاً غیرفعال است و فقط با روشن‌کردن دوباره از تنظیمات کار می‌کند.",
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
    if (trigSnap == null) return "در حال دریافت کندل‌ها برای محاسبه اندیکاتورهای اختصاصی روش..."
    return when (this) {
        BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER -> String.format(
            Locale.US,
            "استراتژی پیمان (الیوت–نئوویو) · فقط لایهٔ موج فعال است · ADX=%.1f · RSI7=%.1f · این استراتژی موقتاً غیرفعال است",
            trigSnap.adx14,
            trigSnap.rsi7,
        )
        BitcoinStrategyModel.BTC_52_CANDLE_REGIME -> String.format(
            Locale.US,
            "پنجرهٔ ۵۲ کندل · شیب=%+.2f ATR/کندل · دامنه=%.1f ATR · فشردگی نیمه=%.2f · Chikou=%s",
            trigSnap.regressionSlope52AtrPerBar,
            trigSnap.range52Atr,
            trigSnap.compressionRatio26,
            if (if (isLong) trigSnap.chikouBullishOpenSpace else trigSnap.chikouBearishOpenSpace) "آزاد" else "درگیر",
        )
        BitcoinStrategyModel.PARSA_52_LINE_TURN -> String.format(
            Locale.US,
            "SSB فلت=%d کندل · شیب SSA=%+.2f ATR · کراس TK=%s · فاصله TK=%.2f ATR · Chikou=%s",
            trigSnap.senkouSpanBFlatBars,
            trigSnap.senkouSpanASlopeAtrPerBar,
            if (isLong) trigSnap.bullishTenkanKijunCrossAgeBars else trigSnap.bearishTenkanKijunCrossAgeBars,
            trigSnap.tenkanKijunGapAtr,
            if (if (isLong) trigSnap.chikouBullishOpenSpace else trigSnap.chikouBearishOpenSpace) "آزاد" else "درگیر",
        )

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
 * اوررایدهای اختصاصی تیونینگ شبکه عصبی برای هر یک از ۱۶ استراتژی به صورت مجزا.
 */
@Serializable
data class PerStrategyConfigOverride(
    val strategyId: String,
    val minRiskReward: Double = 1.65,
    val maxKijunExtensionAtr: Double = 1.75,
    val slBufferAtr: Double = 0.25,
    val breakoutBufferAtr: Double = 0.15,
    val minEfficiencyRatio: Double = 0.20,
    val minVolumeRatio20: Double = 0.60,
    val maxAdverseWickRatio: Double = 0.45,
    val minFutureKumoThicknessAtr: Double = 0.05,
    /**
     * حداقل فاصله برای «مسلح شدن» نقطه‌زنی (معنای اصلی پارامتر).
     * توجه: تا پیش از این، همین مقدار به‌عنوان **سقف** در شرط ۱۶ استفاده می‌شد و
     * «نقطه‌زنی میلی‌متری» را تا ۰٫۶۵ ATR شل می‌کرد. سقف واقعی اکنون [maxSniperDistanceAtr] است.
     */
    val minSniperDistanceAtr: Double = 0.65,
    /** سقف فاصلهٔ مجاز ورود از نقطهٔ بهینه (شرط ۱۶ · Sniper Pocket). */
    val maxSniperDistanceAtr: Double = 0.35,
    val minDriveBooksConsensus: Int = 6,
)

/**
 * اوررایدهای اختصاصی **هر نماد بازار** که شبکهٔ عصبی از تجربهٔ همان نماد یاد گرفته است
 * (شخصیت نماد: نویز شدوی، پیوستگی روند، اعتبار شکست و …).
 *
 * قانون ایمنی: هیچ‌کدام از این مقادیر نمی‌تواند `R:R` را زیر ۱.۵ ببرد یا حد ضرر را از بازهٔ
 * مجاز قبلی تنگ‌تر کند؛ فقط «شخصی‌سازی» در همان بازه‌های مجاز است.
 */
@Serializable
data class PerSymbolConfigOverride(
    val symbol: String,
    val minRiskReward: Double = 1.60,
    val maxKijunExtensionAtr: Double = 1.65,
    val slBufferAtr: Double = 0.26,
    val breakoutBufferAtr: Double = 0.16,
    val minEfficiencyRatio: Double = 0.22,
    val minVolumeRatio20: Double = 0.62,
    val neuralWinProbGatePct: Double = 56.0,
    val minDriveBooksConsensus: Int = 6,
)

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
    val maxAdverseWickRatio: Double = 0.45,
    val minFutureKumoThicknessAtr: Double = 0.05,
    val minSniperDistanceAtr: Double = 0.65,
    /**
     * سقف فاصلهٔ مجاز ورود از نقطهٔ بهینه (شرط ۱۶ · Sniper Pocket).
     * قبلاً همین پارامتر «حداقل» به‌اشتباه به‌عنوان سقف استفاده می‌شد و ورود تا ۰٫۶۷ ATR دور را
     * «نقطه‌زنی میلی‌متری» می‌شمرد؛ اکنون سقف جدا و سخت‌گیرانه است.
     */
    val maxSniperDistanceAtr: Double = 0.35,
    val minDriveBooksConsensus: Int = 6,
    /** لایهٔ اختیاری نئوویو (NeoWave). پیش‌فرض خاموش ⇒ خروجی موتور بدون تغییر. */
    val neoWaveEnabled: Boolean = false,
    /** حداقل قواعد نیلی که باید پاس شود تا شمارش «معتبر» شمرده شود. */
    val neoWaveMinRulesPassed: Int = NeoWaveEngine.MIN_RULE_PASS,
    /** حداقل درصد اطمینان برای اینکه نئوویو اجازهٔ وتو داشته باشد. */
    val neoWaveMinConfidencePct: Double = NeoWaveEngine.MIN_CONFIDENCE_PCT,
    /** دامنهٔ زیگزاگ مونوویو بر حسب ATR. */
    val neoWaveAmplitudeAtr: Double = NeoWaveEngine.DEFAULT_AMPLITUDE_ATR,
    /** مسیر ورود رمزارز بر پایهٔ نئوویو (الگوی کامل و تأییدشده = ماشهٔ ورود). */
    val neoWaveCryptoEntryEnabled: Boolean = true,
    /** حداقل قواعد پاس‌شده برای ورود کریپتو (سخت‌گیرانه‌تر از آستانهٔ وتو). */
    val neoWaveCryptoMinRulesPassed: Int = 8,
    /** حداقل اطمینان شمارش برای ورود کریپتو. */
    val neoWaveCryptoMinConfidencePct: Double = 65.0,
    /** تنظیمات نئوویو به هوش عصبی سپرده شود (آستانه‌ها از معاملات واقعی آموخته می‌شوند). */
    val neoWaveAutoTuneEnabled: Boolean = true,
    /** آستانهٔ قواعدِ آموخته‌شده توسط هوش عصبی (۰ = استفاده از مقدار دستی). */
    val neoWaveAutoRules: Int = 0,
    /** آستانهٔ اطمینانِ آموخته‌شده توسط هوش عصبی (۰ = استفاده از مقدار دستی). */
    val neoWaveAutoConfidencePct: Double = 0.0,
    /** تعداد نمونه‌ای که هوش عصبی بر پایهٔ آن تصمیم گرفته است. */
    val neoWaveAutoSamples: Int = 0,
    /** دلیل فارسی تصمیم هوش عصبی. */
    val neoWaveAutoBasisFa: String = "",
    /** زمان آخرین به‌روزرسانی تنظیمات توسط هوش عصبی. */
    val neoWaveAutoUpdatedAt: Long = 0L,
    /** وزن آموخته‌شدهٔ ۱۲ قاعدهٔ نیلی (خالی = وزن خنثی). */
    val neoWaveAutoRuleWeights: List<Double> = emptyList(),
    val autoTunedGeneration: Int = 0,
    val perStrategyOverrides: Map<String, PerStrategyConfigOverride> = emptyMap(),
    /** شخصیت آموخته‌شدهٔ هر نماد؛ کلید = نماد نرمال‌شده مثل `BTC/USDT`. */
    val perSymbolOverrides: Map<String, PerSymbolConfigOverride> = emptyMap(),
) {
    /** آیا آستانه‌های نئوویو همین حالا از هوش عصبی می‌آیند. */
    val neoWaveAutoTuningActive: Boolean
        get() = neoWaveAutoTuneEnabled && neoWaveAutoRules in 1..12 && neoWaveAutoConfidencePct > 0.0

    /** آستانهٔ مؤثر قواعد نیلی (هوش عصبی → دستی → پیش‌فرض کلاس دارایی). */
    fun resolvedNeoWaveRules(isCrypto: Boolean): Int = when {
        neoWaveAutoTuningActive -> neoWaveAutoRules.coerceIn(1, 12)
        isCrypto -> neoWaveCryptoMinRulesPassed.coerceIn(1, 12)
        else -> neoWaveMinRulesPassed.coerceIn(1, 12)
    }

    /** آستانهٔ مؤثر اطمینان نیلی. */
    fun resolvedNeoWaveConfidence(isCrypto: Boolean): Double = when {
        neoWaveAutoTuningActive -> neoWaveAutoConfidencePct.coerceIn(35.0, 95.0)
        isCrypto -> neoWaveCryptoMinConfidencePct.coerceIn(35.0, 95.0)
        else -> neoWaveMinConfidencePct.coerceIn(35.0, 95.0)
    }

    /** وزن‌های آموخته‌شدهٔ قواعد به‌صورت نقشه (خالی = بدون وزن‌دهی). */
    fun neoWaveWeightsMap(): Map<Int, Double> =
        if (!neoWaveAutoTuningActive) {
            emptyMap()
        } else {
            neoWaveAutoRuleWeights.mapIndexedNotNull { index, w ->
                if (w > 0.0 && index in 0 until 12) (index + 1) to w else null
            }.toMap()
        }

    val macroInterval: Interval get() = Interval.fromLabel(macroIntervalLabel)
    val strategyInterval: Interval get() = Interval.fromLabel(strategyIntervalLabel)
    val triggerInterval: Interval get() = Interval.fromLabel(triggerIntervalLabel)

    /** اورراید شخصیت نماد (اگر شبکه برای این نماد دادهٔ واقعی داشته باشد). */
    fun resolveForSymbol(rawSymbol: String?): PerSymbolConfigOverride? {
        val sym = rawSymbol?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return perSymbolOverrides[sym] ?: perSymbolOverrides[V1Universe.normalize(sym)]
    }

    fun resolveForStrategy(strategyId: String, rawSymbol: String? = null): BaseStrategyConfig {
        val normId = BitcoinStrategyModel.normalizeStrategyId(strategyId)
        val symOv = resolveForSymbol(rawSymbol)
        val ov = perStrategyOverrides[normId] ?: perStrategyOverrides[strategyId]
        if (ov == null && symOv == null) return this
        // دقیق‌ترین سطح یادگیری، اولویت دارد: شخصیت نماد ← پروفایل استراتژی ← مقدار پایه.
        // برای R:R همیشه سخت‌گیرانه‌ترین مقدار انتخاب می‌شود تا هیچ نمادی ریسک را کم نکند.
        return copy(
            minRiskReward = max(1.5, max(symOv?.minRiskReward ?: 0.0, ov?.minRiskReward ?: minRiskReward)),
            maxKijunExtensionAtr = (symOv?.maxKijunExtensionAtr ?: ov?.maxKijunExtensionAtr ?: maxKijunExtensionAtr)
                .coerceIn(1.25, 2.40),
            slBufferAtr = (symOv?.slBufferAtr ?: ov?.slBufferAtr ?: slBufferAtr).coerceIn(0.15, 0.50),
            breakoutBufferAtr = (symOv?.breakoutBufferAtr ?: ov?.breakoutBufferAtr ?: breakoutBufferAtr)
                .coerceIn(0.08, 0.35),
            minEfficiencyRatio = (symOv?.minEfficiencyRatio ?: ov?.minEfficiencyRatio ?: minEfficiencyRatio)
                .coerceIn(0.14, 0.42),
            minVolumeRatio20 = (symOv?.minVolumeRatio20 ?: ov?.minVolumeRatio20 ?: minVolumeRatio20)
                .coerceIn(0.45, 1.10),
            maxAdverseWickRatio = (ov?.maxAdverseWickRatio ?: maxAdverseWickRatio).coerceIn(0.28, 0.58),
            minFutureKumoThicknessAtr = (ov?.minFutureKumoThicknessAtr ?: minFutureKumoThicknessAtr)
                .coerceIn(0.02, 0.16),
            minSniperDistanceAtr = (ov?.minSniperDistanceAtr ?: minSniperDistanceAtr).coerceIn(0.35, 1.05),
            minDriveBooksConsensus = (symOv?.minDriveBooksConsensus ?: ov?.minDriveBooksConsensus
                ?: minDriveBooksConsensus).coerceIn(4, 9),
        )
    }
}

/**
 * خروجی موتور «اجماع بیزین تطبیقی ۹ لایهٔ ساختاری» (Adaptive Bayesian Log-Odds 9-Book Consensus):
 * ارزیابی هم‌زمان هر ۹ لایهٔ تحلیلی و استخراج احتمال برد بیزین، سنگر حد ضرر ترکیبی و تارگت بهینه.
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
 * خروجی محاسبه دقیق ۵ خط ایچیموکو + اندیکاتورهای ۹ لایهٔ تحلیلی روی یک تایم‌فریم مشخص.
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
    /** تعداد کندل‌های بستهٔ متوالی که Senkou Span B (خط دورهٔ ۵۲) در آن‌ها تخت مانده است. */
    val senkouSpanBFlatBars: Int = 0,
    /** شیب Span A دورهٔ جاری و قبلی بر حسب ATR در هر کندل. */
    val senkouSpanASlopeAtrPerBar: Double = 0.0,
    val previousSenkouSpanASlopeAtrPerBar: Double = 0.0,
    /** سن آخرین کراس Tenkan/Kijun؛ مقدار پیش‌فرض یعنی کراسی در پنجره پیدا نشده است. */
    val bullishTenkanKijunCrossAgeBars: Int = Int.MAX_VALUE,
    val bearishTenkanKijunCrossAgeBars: Int = Int.MAX_VALUE,
    /** آمار جهت/دامنهٔ پنجرهٔ قبلی ۵۲ کندلی؛ کندل جاری داخل سطح شکست محاسبه نمی‌شود. */
    val closeChange52Atr: Double = 0.0,
    val regressionSlope52AtrPerBar: Double = 0.0,
    val prior52High: Double = 0.0,
    val prior52Low: Double = 0.0,
    val range52Atr: Double = 0.0,
    val compressionRatio26: Double = 1.0,
    val atrExpansionRatio52: Double = 1.0,
    val tenkanKijunGapAtr: Double = 0.0,
    val tenkanKijunGapExpansionAtr: Double = 0.0,
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
    // ── اندیکاتورهای استخراج‌شده از ۹ لایهٔ تحلیلی ──
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
    BTC_52_STRUCTURE_BREAK("شکست/بازآزمایی ساختار ۵۲ کندلی بیت‌کوین"),
    PARSA_52_LINE_TURN("چرخش Senkou A پس از فلت ۱۸+ کندلی SSB و کراس Tenkan/Kijun"),
    NONE("در انتظار ماشهٔ بسته‌شده"),
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
    /** حد ضرر جدیدی که باید روی معاملهٔ باز نوشته شود (ریسک‌فری/تریل). `null` = بدون تغییر. */
    val updatedStopLoss: Double? = null,
    /** حد سود بازنویسی‌شده (اگر فاصلهٔ هدف با حد ضرر جدید جابه‌جا شود). `null` = بدون تغییر. */
    val updatedTakeProfit: Double? = null,
    /** برچسب کوتاه مرحله: `BE` (ریسک‌فری) یا `TRAIL` (تریل). */
    val stopStageTag: String = "",
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
    /** درصد فاصلهٔ حد ضرر از قیمت ورود (1R) پس از عبور از موتور Trade Plan Guard. */
    val planStopPct: Double = 0.0,
    /** درصد فاصلهٔ حد سود از قیمت ورود پس از عبور از موتور Trade Plan Guard. */
    val planTargetPct: Double = 0.0,
    /** اهرم متناسب با فاصلهٔ حد ضرر (لیکوییدیشن حداقل ۲.۵R دورتر از حد ضرر). */
    val planRecommendedLeverage: Int = 0,
    /** نشان داوری پلن: تأییدشده / اصلاح‌شده / رد شد. */
    val planVerdictFa: String = "",
    /** خلاصهٔ فارسی داوری پلن (دلیل اصلاح حد ضرر، حد سود و اهرم). */
    val planGuardNoteFa: String = "",
    /** نشان لایهٔ نئوویو (خالی = لایه خاموش است). */
    val neoWaveBadgeFa: String = "",
    /** آیا نئوویو این ورود را وتو کرده است. */
    val neoWaveVeto: Boolean = false,
    /** آیا این ورود از «مسیر ورود رمزارز بر پایهٔ نئوویو» آمده است. */
    val cryptoNeoWaveEntry: Boolean = false,
    /** الگوی نئوویو در این ارزیابی (برای آموزش هوش عصبی). */
    val neoWavePatternId: String = "",
    val neoWaveRulesPassed: Int = 0,
    val neoWaveRulesTotal: Int = 0,
    val neoWaveConfidencePct: Double = 0.0,
    val neoWaveFailedRuleIds: List<Int> = emptyList(),
    /** داوری چندتایم‌فریمی نئوویو (بالاتر + جاری) برای نمایش. */
    val neoWaveMtfFa: String = "",
    /** دامنهٔ زیگزاگِ داینامیک استفاده‌شده (بر پایهٔ رژیم نوسان). */
    val neoWaveAmplitudeAtr: Double = 0.0,
    /** توضیح فارسی مسیر ورود (برای نمایش در چک‌لیست و ژورنال). */
    val entryPathFa: String = "",
    /** متن گیت کیجون (استراتژی ۱۶): کراس ۹/۲۶ + خطوط پیش‌بینی ۸/۲۵، ۷/۲۴، ۶/۲۳ + وضعیت ابر. */
    val kijunGateFa: String = "",
    /** متن قاعدهٔ حد ضرر استراتژی ۱۶: بالای خط ۲۶ و با فاصله از کندل. */
    val kijunStopRuleFa: String = "",
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
        if (entryPathFa.isNotBlank()) append(" | مسیر ورود: ").append(entryPathFa)
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

    /**
     * کف سود لازم برای «خروج هوشمند»: تا وقتی معامله به **۲R** نرسیده باشد، هیچ استراتژی حق
     * بستن زودهنگام با خروج هوشمند را ندارد (دستور صریح کاربر). بالای ۲R، اگر روند تمام شود
     * خروج هوشمند آزاد است.
     */
    const val MIN_SMART_EXIT_PROFIT_R: Double = 2.0

    /**
     * استراتژی ۱۶ در نسخهٔ الیوت‌محور: گیت و قاعدهٔ حد ضرر کیجون (که از لایه‌های ساختاری
     * می‌آمدند) خاموش است و فقط هندسهٔ موج باقی می‌ماند.
     */
    const val KIJUN_LAYER_IN_STRATEGY_16_ENABLED: Boolean = false

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

        // مشخصات افق ۵۲ کندلی: سطح شکست از ۵۲ کندل کاملِ قبل از کندل جاری ساخته می‌شود.
        val prior52Start = (lastIdx - 52).coerceAtLeast(0)
        val has52PriorBars = lastIdx >= 52
        val prior52Bars = closedBars.subList(prior52Start, lastIdx)
        val prior52High = prior52Bars.maxOfOrNull { it.high } ?: closedBars[lastIdx].high
        val prior52Low = prior52Bars.minOfOrNull { it.low } ?: closedBars[lastIdx].low
        val range52Atr = (prior52High - prior52Low) / atr14
        val closeChange52Atr = if (has52PriorBars) {
            (closeNow - closedBars[prior52Start].close) / atr14
        } else 0.0
        val regressionBars = closedBars.subList((lastIdx - 51).coerceAtLeast(0), lastIdx + 1)
        val regressionMeanX = (regressionBars.size - 1) / 2.0
        val regressionMeanY = regressionBars.map { it.close }.average()
        var regressionNumerator = 0.0
        var regressionDenominator = 0.0
        regressionBars.forEachIndexed { index, bar ->
            val dx = index - regressionMeanX
            regressionNumerator += dx * (bar.close - regressionMeanY)
            regressionDenominator += dx * dx
        }
        val regressionSlope52AtrPerBar = if (regressionDenominator > 0.0) {
            regressionNumerator / regressionDenominator / atr14
        } else 0.0
        val older26 = if (lastIdx >= 52) closedBars.subList(lastIdx - 52, lastIdx - 26) else emptyList()
        val recent26 = if (lastIdx >= 52) closedBars.subList(lastIdx - 26, lastIdx) else emptyList()
        fun candleRange(bars: List<Candle>): Double =
            if (bars.isEmpty()) 0.0 else (bars.maxOf { it.high } - bars.minOf { it.low })
        val compressionRatio26 = if (older26.isNotEmpty() && candleRange(older26) > 0.0) {
            candleRange(recent26) / candleRange(older26)
        } else 1.0
        val meanTr52 = if (has52PriorBars) {
            (lastIdx - 51..lastIdx).map { i ->
                val bar = closedBars[i]
                val previousClose = closedBars[(i - 1).coerceAtLeast(0)].close
                maxOf(bar.high - bar.low, abs(bar.high - previousClose), abs(bar.low - previousClose))
            }.average()
        } else atr14
        val atrExpansionRatio52 = if (meanTr52 > 0.0) atr14 / meanTr52 else 1.0

        val firstValidSpanBIndex = (config.senkouBPeriod - 1 + disp).coerceAtLeast(0)
        var senkouSpanBFlatBars = if (lastIdx >= firstValidSpanBIndex) 1 else 0
        var flatSpanBMin = currentSpanB
        var flatSpanBMax = currentSpanB
        for (i in (lastIdx - 1) downTo firstValidSpanBIndex) {
            val value = currentSpanBAt(i)
            val nextMin = min(flatSpanBMin, value)
            val nextMax = max(flatSpanBMax, value)
            if (nextMax - nextMin <= flatTol) {
                senkouSpanBFlatBars++
                flatSpanBMin = nextMin
                flatSpanBMax = nextMax
            } else break
        }

        var bullishTenkanKijunCrossAgeBars = Int.MAX_VALUE
        var bearishTenkanKijunCrossAgeBars = Int.MAX_VALUE
        for (age in 0..min(12, lastIdx - 1)) {
            val currentIndex = lastIdx - age
            val currentGap = tenkanAt(currentIndex) - kijunAt(currentIndex)
            val priorGap = tenkanAt(currentIndex - 1) - kijunAt(currentIndex - 1)
            if (bullishTenkanKijunCrossAgeBars == Int.MAX_VALUE && currentGap > 0.0 && priorGap <= 0.0) {
                bullishTenkanKijunCrossAgeBars = age
            }
            if (bearishTenkanKijunCrossAgeBars == Int.MAX_VALUE && currentGap < 0.0 && priorGap >= 0.0) {
                bearishTenkanKijunCrossAgeBars = age
            }
        }
        val senkouSpanASlopeAtrPerBar = (futureSpanA - prevFutureSpanA) / atr14
        val prevPreviousSpanA = spanAAtCreation((lastIdx - 2).coerceAtLeast(0))
        val previousSenkouSpanASlopeAtrPerBar = (prevFutureSpanA - prevPreviousSpanA) / atr14
        val tenkanKijunGapAtr = abs(tenkan - kijun) / atr14
        val previousTenkanKijunGapAtr = abs(prevTenkan - prevKijun) / atr14
        val tenkanKijunGapExpansionAtr = tenkanKijunGapAtr - previousTenkanKijunGapAtr

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

        // ── ۱. بولینگر باند (20, 2.0) و فشردگی Squeeze (لایهٔ ساختاری ۱ — TuAnh و لایهٔ ۴ — Mukherjee) ──
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

        // ── ۲. میانگین‌های متحرک EMA 13, 21, 34, 48, 89 و SMA 200 (لایه‌های ۱، ۲، ۸ و ۹) ──
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

        // ── ۳. محاسبه RSI(14) و RSI(7) سریع + پرش از خط ۵۰ و قله/دره RSI (لایه‌های ۱، ۲ و ۴) ──
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

        // ── ۴. فرکتال‌های ۵ کندلی بیل ویلیامز (لایهٔ ساختاری ۲ — 15minstrategy.pdf) ──
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

        // ── ۵. تنظیمات ایچیموکو (10,30,60) روش Keenbase و تنظیمات سریع (7,22,44) روش Koonitz ──
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

        // ── ۶. اندیکاتورهای MACD (12, 26, 9) و DMI/ADX (14) (لایه‌های ۸ و ۹ — Charles G. Koonitz) ──
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

        // ── ۷. عبور چیکو از کومو، الگوهای کندل‌استیک ژاپنی و امتیاز معیار کلی (لایه‌های ۴، ۵، ۶، ۷ و ۸) ──
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
            senkouSpanBFlatBars = senkouSpanBFlatBars,
            senkouSpanASlopeAtrPerBar = round2(senkouSpanASlopeAtrPerBar),
            previousSenkouSpanASlopeAtrPerBar = round2(previousSenkouSpanASlopeAtrPerBar),
            bullishTenkanKijunCrossAgeBars = bullishTenkanKijunCrossAgeBars,
            bearishTenkanKijunCrossAgeBars = bearishTenkanKijunCrossAgeBars,
            closeChange52Atr = round2(closeChange52Atr),
            regressionSlope52AtrPerBar = round2(regressionSlope52AtrPerBar),
            prior52High = round2(prior52High),
            prior52Low = round2(prior52Low),
            range52Atr = round2(range52Atr),
            compressionRatio26 = round2(compressionRatio26),
            atrExpansionRatio52 = round2(atrExpansionRatio52),
            tenkanKijunGapAtr = round2(tenkanKijunGapAtr),
            tenkanKijunGapExpansionAtr = round2(tenkanKijunGapExpansionAtr),
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
        val safeTrigAtr = if (trigAtr14.isFinite() && trigAtr14 > 0.0) trigAtr14 else safePrice * 0.0020
        val safeStratAtr = if (stratAtr14.isFinite() && stratAtr14 > 0.0) stratAtr14 else safeTrigAtr
        val venueMin = VenueSpecs.minStopDistance(norm, safePrice)

        return when (AssetClass.of(norm)) {
            AssetClass.CRYPTO -> {
                val isMajorCrypto = norm.startsWith("BTC") || norm.startsWith("ETH")
                val pctFloor = when {
                    isMajorCrypto && triggerInterval == Interval.M5 -> safePrice * 0.0016
                    isMajorCrypto -> safePrice * 0.0020
                    triggerInterval == Interval.M5 -> safePrice * 0.0020
                    else -> safePrice * 0.0025
                }
                val atrFloor = max(safeTrigAtr * 0.82, safeStratAtr * 0.28)
                max(venueMin * 3.0, max(pctFloor, atrFloor))
            }

            AssetClass.COMMODITY -> {
                val pctFloor = if (norm.startsWith("XAG")) safePrice * 0.0014 else safePrice * 0.0009
                max(venueMin * 2.5, max(pctFloor, max(safeTrigAtr * 0.78, safeStratAtr * 0.25)))
            }

            AssetClass.STOCK -> {
                max(venueMin * 2.5, max(safePrice * 0.0009, max(safeTrigAtr * 0.78, safeStratAtr * 0.25)))
            }

            AssetClass.FOREX -> {
                max(venueMin * 2.5, max(safePrice * 0.0004, max(safeTrigAtr * 0.75, safeStratAtr * 0.24)))
            }
        }
    }

    /**
     * محاسبه بهترین محل حد ضرر ساختاری طبق متود روز دنیا
     * (`SOTA Multi-Anchor Structural Invalidation + Liquidity-Sweep Shield + Volatility Corridor [0.80 ATR .. 2.10 ATR]`):
     * - به جای قرار دادن استاپ در فضای خالی یا درصدهای بزرگ مصنوعی (که ۱R را متورم و TP را دست‌نیافتنی می‌کرد)
     *   یا استاپ خیلی نزدیک زیر کندل جاری (که شکار شدوی مارکت‌میکر می‌شد)،
     * - ۵ سنگر ابطال ساختاری واقعی (فرکتال ۵ کندلی بیل ویلیامز، کیجون‌سن ۲۶، کیجون ۳۰ نهادی/EMA34، لبه کومو و نقطه S موج هوسودا)
     *   را بررسی کرده، بافر ضد استاپ‌هانت تطبیقی (`ATR * (slBuffer + 0.16 * wickRatio)`) را پشت سنگر اعمال می‌کند،
     *   و بهترین سنگر ساختاری داخل دالان طلایی `[minSafeDist .. maxStructuralDist]` را انتخاب می‌نماید.
     */
    fun computeOptimalStructuralStopLoss(
        symbol: String,
        isLong: Boolean,
        referenceEntry: Double,
        trigSnap: IchimokuBarSnapshot,
        stratSnap: IchimokuBarSnapshot,
        wave: HosodaWaveProjection?,
        slBufferAtr: Double,
        triggerInterval: Interval,
        /**
         * استراتژی فعال — ترتیب اعتبار سنگرها بر اساس مکتب پایهٔ همان استراتژی تعیین می‌شود
         * (پاتل: کیجون، تاتل: کومو/چیکو، هوسودا: نقطه S، پلویل: فلت/کومو، لایه‌های ۱ تا ۹: ریبون نهادی).
         */
        activeModel: BitcoinStrategyModel = BitcoinStrategyModel.BASE_ICHIMOKU,
    ): Double {
        // ATR مقاوم (برش ATR های گلِیچ‌خورده) تا یک کندل خراب کل ۱R را متورم نکند
        val atr = TradePlanGuard.robustAtr(
            symbol = symbol,
            price = referenceEntry,
            atrValue = trigSnap.atr14.coerceAtLeast(referenceEntry * 0.0004),
            interval = triggerInterval,
        )
        val stratAtrSafe = TradePlanGuard.robustAtr(
            symbol = symbol,
            price = referenceEntry,
            atrValue = stratSnap.atr14,
            interval = triggerInterval,
        )
        val minSafeDist = TradePlanGuard.stopDistanceFloor(
            symbol = symbol,
            price = referenceEntry,
            atr = atr,
            interval = triggerInterval,
            structuralFloorOverride = minSafeStructuralStopDistance(
                symbol = symbol,
                referencePrice = referenceEntry,
                trigAtr14 = atr,
                stratAtr14 = stratAtrSafe,
                triggerInterval = triggerInterval,
            ),
        )
        // سقف فاصله مجاز ساختاری تا ۱R بیش از حد متورم نشود و تارگت ۱.۵R..۳.۰R کاملاً در دسترس بماند
        val maxStructuralDist = TradePlanGuard.stopDistanceCeiling(
            symbol = symbol,
            price = referenceEntry,
            atr = atr,
            interval = triggerInterval,
        )
        val adverseWick = if (isLong) trigSnap.lowerWickRatio else trigSnap.upperWickRatio
        val sweepShieldBuffer = atr * (slBufferAtr.coerceIn(0.18, 0.42) + 0.16 * adverseWick.coerceIn(0.0, 0.65))

        return if (isLong) {
            val anchorFractal5 = min(trigSnap.lastDownFractalLow, trigSnap.low) - sweepShieldBuffer
            val anchorKijun26 = trigSnap.kijun - sweepShieldBuffer
            val anchorKijun30Ema34 = min(trigSnap.kijun30, trigSnap.ema34) - sweepShieldBuffer * 0.90
            val anchorKumoEdge = trigSnap.kumoBottom - sweepShieldBuffer * 0.85
            val anchorWaveC = (wave?.pointC ?: trigSnap.kijun) - sweepShieldBuffer

            val anchors = listOf(
                StopAnchorKind.FRACTAL_5BAR to anchorFractal5,
                StopAnchorKind.KIJUN_26 to anchorKijun26,
                StopAnchorKind.RIBBON_KIJUN30_EMA34 to anchorKijun30Ema34,
                StopAnchorKind.KUMO_EDGE to anchorKumoEdge,
                StopAnchorKind.WAVE_S_POINT to anchorWaveC,
            ).filter { (_, price) -> price.isFinite() && price > 0.0 && price < referenceEntry }

            // «مطمئن‌ترین» سنگرِ همان استراتژی که بیرون از کف نویز و داخل دالان نوسان باشد؛
            // اگر هیچ سنگر مورد اعتماد استراتژی در دالان نبود، نزدیک‌ترین سنگر ساختاری انتخاب می‌شود.
            val chosenSl = chooseStrategyStopAnchor(
                isLong = true,
                referenceEntry = referenceEntry,
                anchors = anchors,
                preferredOrder = stopAnchorPriorityFor(activeModel),
                floorDist = minSafeDist,
                ceilingDist = maxStructuralDist,
            ) ?: min(referenceEntry - minSafeDist, referenceEntry - atr * 0.5)
            round2(chosenSl.coerceAtLeast(0.00001))
        } else {
            val anchorFractal5 = max(trigSnap.lastUpFractalHigh, trigSnap.high) + sweepShieldBuffer
            val anchorKijun26 = trigSnap.kijun + sweepShieldBuffer
            val anchorKijun30Ema34 = max(trigSnap.kijun30, trigSnap.ema34) + sweepShieldBuffer * 0.90
            val anchorKumoEdge = trigSnap.kumoTop + sweepShieldBuffer * 0.85
            val anchorWaveC = (wave?.pointC ?: trigSnap.kijun) + sweepShieldBuffer

            val anchors = listOf(
                StopAnchorKind.FRACTAL_5BAR to anchorFractal5,
                StopAnchorKind.KIJUN_26 to anchorKijun26,
                StopAnchorKind.RIBBON_KIJUN30_EMA34 to anchorKijun30Ema34,
                StopAnchorKind.KUMO_EDGE to anchorKumoEdge,
                StopAnchorKind.WAVE_S_POINT to anchorWaveC,
            ).filter { (_, price) -> price.isFinite() && price > referenceEntry }

            val chosenSl = chooseStrategyStopAnchor(
                isLong = false,
                referenceEntry = referenceEntry,
                anchors = anchors,
                preferredOrder = stopAnchorPriorityFor(activeModel),
                floorDist = minSafeDist,
                ceilingDist = maxStructuralDist,
            ) ?: max(referenceEntry + minSafeDist, referenceEntry + atr * 0.5)
            round2(chosenSl)
        }
    }

    /** سنگرهای ابطال ساختاری؛ هر استراتژی «مطمئن‌ترین» سنگر خودش را دارد. */
    enum class StopAnchorKind(val labelFa: String) {
        FRACTAL_5BAR("فرکتال ۵ کندلی (بیل ویلیامز/فوکا)"),
        KIJUN_26("کیجون‌سن ۲۶ (تعادل پاتل)"),
        RIBBON_KIJUN30_EMA34("ریبون نهادی کیجون۳۰/EMA34 (کونیتز)"),
        KUMO_EDGE("لبهٔ کومو / SSB فلت (تاتل و پلویل)"),
        WAVE_S_POINT("نقطهٔ S موج هوسودا/الیوت"),
    }

    /**
     * ترتیب اعتبار سنگرها بر اساس مکتب‌های پایه‌ای که هر استراتژی از آن‌ها ساخته شده است.
     *
     * این همان «حد ضرر متناسب هر استراتژی» است: مثلاً استراتژی پاتل روی تعادل کیجون بنا شده،
     * پس کیجون‌سن معتبرترین ابطال آن است؛ استراتژی تاتل روی شکست کومو/چیکو بنا شده، پس لبهٔ کومو؛
     * استراتژی هوسودا/الیوت روی ساختار موج بنا شده، پس نقطهٔ S؛ و متود جامع ۹ لایه روی ریبون نهادی.
     */
    fun stopAnchorPriorityFor(model: BitcoinStrategyModel): List<StopAnchorKind> {
        if (model == BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER) {
            return listOf(
                StopAnchorKind.RIBBON_KIJUN30_EMA34,
                StopAnchorKind.KIJUN_26,
                StopAnchorKind.FRACTAL_5BAR,
                StopAnchorKind.KUMO_EDGE,
                StopAnchorKind.WAVE_S_POINT,
            )
        }
        val order = LinkedHashSet<StopAnchorKind>()
        if (model.hasPatel) {
            order += listOf(
                StopAnchorKind.KIJUN_26,
                StopAnchorKind.RIBBON_KIJUN30_EMA34,
                StopAnchorKind.FRACTAL_5BAR,
                StopAnchorKind.KUMO_EDGE,
                StopAnchorKind.WAVE_S_POINT,
            )
        }
        if (model.hasTuttle) {
            order += listOf(
                StopAnchorKind.KUMO_EDGE,
                StopAnchorKind.FRACTAL_5BAR,
                StopAnchorKind.KIJUN_26,
                StopAnchorKind.RIBBON_KIJUN30_EMA34,
                StopAnchorKind.WAVE_S_POINT,
            )
        }
        if (model.hasHosoda) {
            order += listOf(
                StopAnchorKind.WAVE_S_POINT,
                StopAnchorKind.KIJUN_26,
                StopAnchorKind.FRACTAL_5BAR,
                StopAnchorKind.KUMO_EDGE,
                StopAnchorKind.RIBBON_KIJUN30_EMA34,
            )
        }
        if (model.hasPeloille) {
            order += listOf(
                StopAnchorKind.KUMO_EDGE,
                StopAnchorKind.RIBBON_KIJUN30_EMA34,
                StopAnchorKind.KIJUN_26,
                StopAnchorKind.FRACTAL_5BAR,
                StopAnchorKind.WAVE_S_POINT,
            )
        }
        if (order.isEmpty()) {
            order += StopAnchorKind.entries.toList()
        }
        return order.toList()
    }

    /**
     * انتخاب سنگر حد ضرر: اول معتبرترین سنگرِ همان استراتژی که داخل دالان مجاز
     * `[floorDist .. ceilingDist]` باشد؛ در غیر این صورت نزدیک‌ترین سنگر ساختاری.
     */
    private fun chooseStrategyStopAnchor(
        isLong: Boolean,
        referenceEntry: Double,
        anchors: List<Pair<StopAnchorKind, Double>>,
        preferredOrder: List<StopAnchorKind>,
        floorDist: Double,
        ceilingDist: Double,
    ): Double? {
        val byKind = anchors.toMap()
        for (kind in preferredOrder) {
            val price = byKind[kind] ?: continue
            val distance = abs(price - referenceEntry)
            if (distance in floorDist..ceilingDist) return price
        }
        return TradePlanGuard.chooseStructuralAnchor(
            isLong = isLong,
            referenceEntry = referenceEntry,
            candidates = anchors.map { it.second },
            floorDist = floorDist,
            ceilingDist = ceilingDist,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // گیت کیجون — سه شرط تازهٔ استراتژی ۱۶ (۹ لایهٔ ساختاری)
    // ۱) کراس ۹ و ۲۶ همیشه در جهت معامله بررسی شود
    // ۲) خطوط پیش‌بینی ۸/۲۵ · ۷/۲۴ · ۶/۲۳ جهت ۹/۲۶ را تأیید کنند
    // ۳) وضعیت ابرها (قیمت نسبت به کومو + جهت ابر آینده) تأیید کند
    // ─────────────────────────────────────────────────────────────────────────

    /** خروجی گیت کیجون/ابر/خطوط پیش‌بینی برای استراتژی ۱۶. */
    data class KijunPredictiveGate(
        /** کراس تنکان ۹ و کیجون ۲۶ در جهت معامله برقرار است. */
        val tkAligned: Boolean,
        /** چند کندل پیش آخرین کراس در جهت معامله رخ داده (‑۱ = در پنجرهٔ بررسی نبود). */
        val crossBarsAgo: Int,
        /** فاصلهٔ تنکان از کیجون بر حسب ATR (قدرمطلق، در جهت معامله مثبت). */
        val tkSpreadAtr: Double,
        /** چند جفت از خطوط پیش‌بینی (۸/۲۵، ۷/۲۴، ۶/۲۳) جهت را تأیید کردند. */
        val predictiveAligned: Int,
        val predictiveTotal: Int,
        /** وضعیت ابر تأییدکننده است. */
        val cloudAligned: Boolean,
        val cloudLabelFa: String,
        val passed: Boolean,
        val summaryFa: String,
    )

    /**
     * محاسبهٔ میانگین سقف/کف برای یک دوره (همان تعریف تنکان/کیجون در ایچیموکو).
     * خروجی، سریِ مقدار خط از کندلِ `period‑1` تا آخرین کندل است.
     */
    private fun midpointSeries(candles: List<Candle>, period: Int): List<Double> {
        if (period < 2 || candles.size < period) return emptyList()
        val out = ArrayList<Double>(candles.size - period + 1)
        for (i in (period - 1) until candles.size) {
            var highest = Double.NEGATIVE_INFINITY
            var lowest = Double.POSITIVE_INFINITY
            for (j in (i - period + 1)..i) {
                val bar = candles[j]
                if (bar.high > highest) highest = bar.high
                if (bar.low < lowest) lowest = bar.low
            }
            if (highest.isFinite() && lowest.isFinite() && highest > 0.0) {
                out.add((highest + lowest) / 2.0)
            }
        }
        return out
    }

    /**
     * گیت کیجون برای استراتژی ۱۶. طبق درخواست کاربر (نسخهٔ ۳.۹.۴۷) این گیت موقتاً کنار گذاشته شده
     * است تا استراتژی فقط لایهٔ الیوت را داشته باشد؛ با `KIJUN_LAYER_IN_STRATEGY_16_ENABLED` قابل بازگشت است.
     *
     * منطق «خطوط پیش‌بینی»: هر جفت (۸/۲۵)، (۷/۲۴) و (۶/۲۳) همان میانگین سقف/کف است با پنجرهٔ
     * یک، دو و سه دوره کوتاه‌تر از جفت ۹/۲۶؛ چون پنجرهٔ کوتاه‌تر زودتر واکنش می‌دهد، جهت این سه
     * جفت *قبل از* کراس ۹/۲۶ برمی‌گردد و می‌تواند کراس آینده را پیش‌بینی کند. شرط اجرا:
     * کراس ۹/۲۶ هم‌جهت + حداقل دو جفت از سه جفت پیش‌بینی هم‌جهت و در حال باز شدن + ابر هم‌جهت.
     */
    fun evaluateKijunPredictiveGate(
        candlesInput: List<Candle>,
        isLong: Boolean,
        strategySnapshot: IchimokuBarSnapshot,
    ): KijunPredictiveGate {
        val bars = candlesInput.filter { it.closed }.ifEmpty { candlesInput }.takeLast(140)
        val signed = if (isLong) 1.0 else -1.0
        val pairs = listOf(9 to 26, 8 to 25, 7 to 24, 6 to 23)

        fun pairAligned(shortPeriod: Int, longPeriod: Int): Boolean {
            val shortSeries = midpointSeries(bars, shortPeriod)
            val longSeries = midpointSeries(bars, longPeriod)
            val size = min(shortSeries.size, longSeries.size)
            if (size < 4) return false
            val diffNow = shortSeries[size - 1] - longSeries[size - 1]
            val diffBefore = shortSeries[size - 4] - longSeries[size - 4]
            val rightSide = diffNow * signed > 0.0
            val opening = (diffNow - diffBefore) * signed > 0.0
            return rightSide && opening
        }

        val tkAligned = pairAligned(pairs[0].first, pairs[0].second)
        val predictiveAligned = pairs.drop(1).count { pairAligned(it.first, it.second) }

        // کراس تازهٔ ۹/۲۶: چند کندل پیش علامتِ اختلاف عوض شده است؟
        val nine = midpointSeries(bars, 9)
        val twentySix = midpointSeries(bars, 26)
        val size = min(nine.size, twentySix.size)
        val spreadNow = if (size > 0) nine[size - 1] - twentySix[size - 1] else 0.0
        var crossBarsAgo = -1
        if (size > 1 && tkAligned) {
            var idx = size - 1
            val lookback = min(size - 1, 13)
            while (idx > size - 1 - lookback) {
                val prev = nine[idx - 1] - twentySix[idx - 1]
                if (prev * signed <= 0.0) {
                    crossBarsAgo = (size - 1) - (idx - 1)
                    break
                }
                idx--
            }
        }
        val atrRef = strategySnapshot.atr14.takeIf { it.isFinite() && it > 0.0 }
            ?: (strategySnapshot.close * 0.001)
        val tkSpreadAtr = if (atrRef > 0.0) abs(spreadNow) / atrRef else 0.0

        // ابر: قیمت بیرون از کومو، ضخامت کافی، و جهت ابر آینده هم‌سو
        val close = strategySnapshot.close
        val kumoTopValue = strategySnapshot.kumoTop
        val kumoBottomValue = strategySnapshot.kumoBottom
        val thicknessAtr = strategySnapshot.currentKumoThicknessAtr
        val futureBullish = strategySnapshot.futureSpanA >= strategySnapshot.futureSpanB
        val priceAboveCloud = close > kumoTopValue
        val priceBelowCloud = close < kumoBottomValue
        val insideCloud = !priceAboveCloud && !priceBelowCloud
        val cloudAligned = if (isLong) {
            priceAboveCloud && futureBullish && thicknessAtr >= 0.08
        } else {
            priceBelowCloud && !futureBullish && thicknessAtr >= 0.08
        }
        val cloudLabelFa = when {
            insideCloud -> "قیمت داخل ابر است (بی‌تصمیم) — جهت ابر: ${if (futureBullish) "صعودی" else "نزولی"}"
            priceAboveCloud && isLong -> "قیمت بالای ابر · ابر آینده ${if (futureBullish) "صعودی" else "نزولی"} · ضخامت ${round2(thicknessAtr)} ATR"
            priceBelowCloud && !isLong -> "قیمت زیر ابر · ابر آینده ${if (futureBullish) "صعودی" else "نزولی"} · ضخامت ${round2(thicknessAtr)} ATR"
            priceAboveCloud -> "قیمت بالای ابر ولی معامله فروش است (ناسازگار)"
            else -> "قیمت زیر ابر ولی معامله خرید است (ناسازگار)"
        }

        val passed = tkAligned && predictiveAligned >= 2 && cloudAligned
        val crossTextFa = when {
            crossBarsAgo >= 0 -> "کراس ۹/۲۶ در جهت معامله، ${crossBarsAgo} کندل پیش"
            tkAligned -> "تنکان بالاتر/پایین‌تر از کیجون در جهت معامله است ولی کراس تازه‌ای در ۱۳ کندل اخیر نبود"
            else -> "کراس ۹/۲۶ در جهت معامله نیست"
        }
        val predictiveTextFa = "خطوط پیش‌بینی: $predictiveAligned از ۳ تأیید (۸/۲۵ · ۷/۲۴ · ۶/۲۳)"
        val summaryFa = when {
            passed -> "✅ گیت کیجون تأیید شد · $crossTextFa · $predictiveTextFa · $cloudLabelFa"
            !tkAligned && predictiveAligned < 2 ->
                "🛑 نه کراس ۹/۲۶ هم‌جهت است و نه خطوط پیش‌بینی تأیید می‌کنند · $predictiveTextFa · $cloudLabelFa"
            !cloudAligned ->
                "🛑 ابر هم‌جهت نیست · $crossTextFa · $predictiveTextFa · $cloudLabelFa"
            else ->
                "🛑 $crossTextFa · $predictiveTextFa · $cloudLabelFa"
        }
        return KijunPredictiveGate(
            tkAligned = tkAligned,
            crossBarsAgo = crossBarsAgo,
            tkSpreadAtr = round2(tkSpreadAtr),
            predictiveAligned = predictiveAligned,
            predictiveTotal = pairs.size - 1,
            cloudAligned = cloudAligned,
            cloudLabelFa = cloudLabelFa,
            passed = passed,
            summaryFa = summaryFa,
        )
    }

    /** خروجی قاعدهٔ حد ضرر کیجون. */
    data class KijunStopRule(
        /** حد ضرر نهایی پس از قاعده. */
        val stop: Double,
        /** آیا مقدار اولیه تغییر کرد؟ */
        val adjusted: Boolean,
        /** آیا شرایط (بالای خط ۲۶ + فاصله از کندل) با هم جمع نمی‌شوند؟ */
        val blocked: Boolean,
        val noteFa: String,
    )

    /**
     * قاعدهٔ حد ضرر استراتژی ۱۶:
     *  - برای خرید، حد ضرر باید **بالای خط دورهٔ ۲۶** باشد (تا حد ضرر قبل از شکست خط تعادل فعال شود)
     *    و برای فروش **پایین همان خط**؛
     *  - حد ضرر نباید به کندل بچسبد؛ حداقل فاصله = `ATR × (۰.۲۲ + ۰.۱۵ × سهم سایه)` از سقف/کف کندل.
     * اگر این دو شرط با هم جمع نشوند (قیمت چسبیده به خط ۲۶)، پلن «مسدود» اعلام می‌شود تا معاملهٔ
     * بی‌کیفیت باز نشود.
     */
    fun applyKijunStopRules(
        isLong: Boolean,
        candidateStop: Double,
        triggerSnapshot: IchimokuBarSnapshot,
    ): KijunStopRule {
        val atr = triggerSnapshot.atr14.takeIf { it.isFinite() && it > 0.0 }
            ?: (triggerSnapshot.close * 0.001)
        val kijunValue = triggerSnapshot.kijun
        if (!kijunValue.isFinite() || kijunValue <= 0.0) {
            return KijunStopRule(
                stop = candidateStop,
                adjusted = false,
                blocked = false,
                noteFa = "خط ۲۶ خوانده نشد؛ قاعدهٔ حد ضرر کیجون اعمال نشد",
            )
        }
        val wickRatio = if (isLong) triggerSnapshot.lowerWickRatio else triggerSnapshot.upperWickRatio
        val candleGap = atr * (0.22 + 0.15 * wickRatio.coerceIn(0.0, 0.6))
        val kijunGap = atr * 0.10
        val safeCandidate = candidateStop.takeIf { it.isFinite() && it > 0.0 } ?: return KijunStopRule(
            stop = candidateStop,
            adjusted = false,
            blocked = true,
            noteFa = "حد ضرر محاسبه‌شده معتبر نبود؛ ورود مسدود شد",
        )

        return if (isLong) {
            val floor = kijunValue + kijunGap
            val ceiling = triggerSnapshot.low - candleGap
            if (floor >= ceiling) {
                KijunStopRule(
                    stop = safeCandidate,
                    adjusted = false,
                    blocked = true,
                    noteFa = "فضای بین خط ۲۶ (${round2(kijunValue)}) و کف کندل (${round2(triggerSnapshot.low)}) کمتر از " +
                        "بافر لازم است؛ حد ضرر هم‌زمان «بالای خط ۲۶» و «دور از کندل» نمی‌شود",
                )
            } else {
                val stop = safeCandidate.coerceIn(floor, ceiling)
                KijunStopRule(
                    stop = round2(stop),
                    adjusted = abs(stop - safeCandidate) > 1e-9,
                    blocked = false,
                    noteFa = "حد ضرر بالای خط ۲۶ (${round2(kijunValue)}) و با فاصلهٔ ${round2((triggerSnapshot.low - stop) / atr)} ATR از کف کندل",
                )
            }
        } else {
            val floor = triggerSnapshot.high + candleGap
            val ceiling = kijunValue - kijunGap
            if (floor >= ceiling) {
                KijunStopRule(
                    stop = safeCandidate,
                    adjusted = false,
                    blocked = true,
                    noteFa = "فضای بین خط ۲۶ (${round2(kijunValue)}) و سقف کندل (${round2(triggerSnapshot.high)}) کمتر از " +
                        "بافر لازم است؛ حد ضرر هم‌زمان «پایین خط ۲۶» و «دور از کندل» نمی‌شود",
                )
            } else {
                val stop = safeCandidate.coerceIn(floor, ceiling)
                KijunStopRule(
                    stop = round2(stop),
                    adjusted = abs(stop - safeCandidate) > 1e-9,
                    blocked = false,
                    noteFa = "حد ضرر پایین خط ۲۶ (${round2(kijunValue)}) و با فاصلهٔ ${round2((stop - triggerSnapshot.high) / atr)} ATR از سقف کندل",
                )
            }
        }
    }

    /**
     * موتور «متود جامع یکپارچه لایه‌های ساختاری ۱ تا ۹» با الگوریتم روز
     * `Adaptive Bayesian Log-Odds 9-Book Consensus + Regime-Weighted Stacking`:
     * ارزیابی هم‌زمان هر ۹ لایهٔ تحلیلی و استخراج احتمال برد بیزین، سنگر حد ضرر ترکیبی و تارگت بهینه.
     */
    fun evaluateUnified9BookConsensus(
        isLong: Boolean,
        trigSnap: IchimokuBarSnapshot,
        stratSnap: IchimokuBarSnapshot,
        macroSnap: IchimokuBarSnapshot? = null,
    ): Unified9BookConsensusResult {
        val atr = trigSnap.atr14.coerceAtLeast(1e-8)

        // ۱. لایهٔ ساختاری ۱ (TuAnh): BB(20,2) Squeeze/Band + EMA(89)/SMA(200) + RSI(14) > 50
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

        // ۲. لایهٔ ساختاری ۲ (Foka 15m/Day): بیرون کومو + شیب EMA(21) و کیجون + پرش RSI(7) از خط ۵۰ + فرکتال ۵ کندلی
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

        // ۳. لایهٔ ساختاری ۳ (Keenbase 10-30-60): کراس نهادی Tenkan(10)/Kijun(30) + بیرون Kumo(60)
        val book3 = if (isLong) {
            trigSnap.tenkan10 >= trigSnap.kijun30 &&
                trigSnap.close > trigSnap.kumoTop103060 &&
                stratSnap.close >= stratSnap.kumoBottom103060
        } else {
            trigSnap.tenkan10 <= trigSnap.kijun30 &&
                trigSnap.close < trigSnap.kumoBottom103060 &&
                stratSnap.close <= stratSnap.kumoTop103060
        }

        // ۴. لایهٔ ساختاری ۴ (Mukherjee Kelly & RSI): حداقل ۳ سیگنال طوفان کامل (Kelly >= 62%) + الگوی شمعی + سلامت RSI
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

        // ۵. لایهٔ ساختاری ۵ (Mukherjee Clouds & Chikou Filter): خط ماشه تنکان + کیجون غیرفلت + آزادی چیکو
        val book5 = !trigSnap.isKijunFlat && if (isLong) {
            trigSnap.close >= trigSnap.tenkan &&
                trigSnap.kijunSlopeAtrPerBar >= -0.01 &&
                trigSnap.chikouBullishOpenSpace
        } else {
            trigSnap.close <= trigSnap.tenkan &&
                trigSnap.kijunSlopeAtrPerBar <= 0.01 &&
                trigSnap.chikouBearishOpenSpace
        }

        // ۶. لایهٔ ساختاری ۶ (HowToTrade Key Level & Kumo Twist): شکست کامل کانال کومو + Twist هم‌جهت ابر آینده
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

        // ۷. لایهٔ ساختاری ۷ (TSG 4-Step Quick Profit): کلوز بیرون کومو + کراس TK + چیکو بالای/زیر کومو ۲۶ دوره قبل
        val book7 = if (isLong) {
            trigSnap.close > trigSnap.kumoTop &&
                trigSnap.tenkan >= trigSnap.kijun &&
                trigSnap.chikouAboveCloud26
        } else {
            trigSnap.close < trigSnap.kumoBottom &&
                trigSnap.tenkan <= trigSnap.kijun &&
                trigSnap.chikouBelowCloud26
        }

        // ۸. لایهٔ ساختاری ۸ (Koonitz 5 Golden Rules + DMI/MACD + EMA34): ۵ قانون کونیتز با ADX/DMI یا MACD
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

        // ۹. لایهٔ ساختاری ۹ (Koonitz Triple Screen 7-22-44 + EMA 13/34/48): هم‌جهتی ۳ تایم‌فریم و ایچیموکوی سریع
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

        // Composite Fortress Stop-Loss: 5-Bar Fractal + Kijun26 + Kijun30 + EMA34 inside [0.80 ATR .. 2.10 ATR]
        val compositeStop = computeOptimalStructuralStopLoss(
            symbol = BTC_SYMBOL,
            isLong = isLong,
            referenceEntry = trigSnap.close,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            wave = null,
            slBufferAtr = 0.25,
            triggerInterval = Interval.M5,
            activeModel = BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER,
        )

        val dynamicRr = when {
            count >= 8 -> 2.85
            count >= 7 -> 2.70
            count >= 6 -> 2.55
            count >= 5 -> 2.40
            else -> 2.25
        }.coerceIn(1.50, 3.00)

        val badges = buildList {
            if (book1) add("لایهٔ ۱ (فشردگی بولینگر)")
            if (book2) add("لایهٔ ۲ (EMA21 + RSI7 + فرکتال)")
            if (book3) add("لایهٔ ۳ (ایچیموکو ۱۰/۳۰/۶۰)")
            if (book4) add("لایهٔ ۴ (معیار کلی + RSI)")
            if (book5) add("لایهٔ ۵ (فیلتر چیکو)")
            if (book6) add("لایهٔ ۶ (سطح کلیدی + کومو تویست)")
            if (book7) add("لایهٔ ۷ (۴ گام سریع)")
            if (book8) add("لایهٔ ۸ (DMI/MACD + EMA34)")
            if (book9) add("لایهٔ ۹ (سه‌صفحهٔ ۷‑۲۲‑۴۴)")
        }

        val summary = String.format(
            Locale.US,
            "اجماع %d/9 لایهٔ ساختاری · احتمال برد بیزین %.1f%% · تارگت بهینه 1:%.2f",
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

        if (activeModel == BitcoinStrategyModel.BTC_52_CANDLE_REGIME && normalizedSymbol != BTC_SYMBOL) {
            val condition = StrategyConditionCheck(
                index = 1,
                titleFa = "محدودیت نماد",
                passed = false,
                detailFa = "این روش فقط برای BTC/USDT تعریف شده است؛ برای $normalizedSymbol هیچ معامله‌ای صادر نمی‌شود.",
            )
            return StrategyEvaluationReport(
                symbol = normalizedSymbol,
                strategyId = effectiveId,
                strategyTitleFa = effectiveTitle,
                strategyBadgeFa = activeModel.badgeFa,
                macroInterval = config.macroInterval,
                strategyInterval = config.strategyInterval,
                triggerInterval = config.triggerInterval,
                readiness = StrategyReadinessState.BLOCKED,
                action = SignalAction.NO_TRADE,
                triggerType = StrategyTriggerType.NONE,
                confidence = 0.0,
                livePrice = livePriceOverride ?: trigSnap?.close ?: 0.0,
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
                conditions = listOf(condition),
                summaryFa = "روش ۵۲ کندلی بیت‌کوین فقط روی BTC/USDT ارزیابی می‌شود.",
                triggerBarTime = trigSnap?.time ?: 0L,
                evaluatedAt = nowMillis,
            )
        }

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

        // روش‌های BTC/پارسا از هر اورراید عصبی (حتی پروفایل عمومی نماد) جدا می‌مانند؛
        // فقط بازه‌های تایم‌فریم کاربر حفظ می‌شود و تنظیمات ثابت زیر مبنای ایمن آن‌هاست.
        val modelConfig = if (activeModel.neuralLearningEnabled) config else config.copy(
            maxKijunExtensionAtr = 1.8,
            slBufferAtr = 0.25,
            breakoutBufferAtr = 0.15,
            minRiskReward = 1.5,
            minEfficiencyRatio = 0.20,
            minVolumeRatio20 = 0.60,
            maxAdverseWickRatio = 0.45,
            minFutureKumoThicknessAtr = 0.05,
            minSniperDistanceAtr = 0.65,
            minDriveBooksConsensus = 6,
            perStrategyOverrides = emptyMap(),
            perSymbolOverrides = emptyMap(),
            neoWaveAutoTuneEnabled = false,
            neoWaveAutoRules = 0,
            neoWaveAutoConfidencePct = 0.0,
            neoWaveAutoSamples = 0,
            neoWaveAutoBasisFa = "",
        )
        // قانون قطعی سیستم: حداقل R:R تمام استراتژی‌ها و معاملات همیشه حداقل 1:1.5 و بالاتر است.
        val stratOverride = modelConfig.perStrategyOverrides[activeModel.id]
        val symbolOverride = modelConfig.resolveForSymbol(normalizedSymbol)
        val baseMinRr = max(
            stratOverride?.minRiskReward ?: modelConfig.minRiskReward,
            symbolOverride?.minRiskReward ?: 0.0,
        )
        val minAllowedRr = max(baseMinRr, 1.5)
        val effectiveConfig = modelConfig.copy(
            enablePullbackTrigger = activeModel.hasPatel || activeModel.hasHosoda || activeModel.hasPeloille || !activeModel.hasTuttle,
            enableBreakoutTrigger = activeModel.hasTuttle || activeModel.hasHosoda || activeModel.hasPeloille || !activeModel.hasPatel,
            requireHigherTfAlignment = activeModel.hasPeloille || activeModel.combinationCount >= 2 || config.requireHigherTfAlignment,
            requireChikouOpenSpace = activeModel.hasTuttle || activeModel.hasPeloille || activeModel.combinationCount >= 2 || config.requireChikouOpenSpace,
            requireFutureKumoSlope = activeModel.hasPatel || activeModel.hasTuttle || activeModel.combinationCount >= 2 || config.requireFutureKumoSlope,
            minRiskReward = if (activeModel.hasHosoda && activeModel.combinationCount == 1) max(minAllowedRr, 1.8) else minAllowedRr,
            maxKijunExtensionAtr = (symbolOverride?.maxKijunExtensionAtr
                ?: stratOverride?.maxKijunExtensionAtr ?: modelConfig.maxKijunExtensionAtr).coerceIn(1.25, 2.50),
            slBufferAtr = (symbolOverride?.slBufferAtr
                ?: stratOverride?.slBufferAtr ?: modelConfig.slBufferAtr).coerceIn(0.15, 0.55),
            breakoutBufferAtr = (symbolOverride?.breakoutBufferAtr
                ?: stratOverride?.breakoutBufferAtr ?: modelConfig.breakoutBufferAtr).coerceIn(0.05, 0.35),
            minEfficiencyRatio = (symbolOverride?.minEfficiencyRatio
                ?: stratOverride?.minEfficiencyRatio ?: modelConfig.minEfficiencyRatio).coerceIn(0.14, 0.45),
            minVolumeRatio20 = (symbolOverride?.minVolumeRatio20
                ?: stratOverride?.minVolumeRatio20 ?: modelConfig.minVolumeRatio20).coerceIn(0.40, 1.15),
            maxAdverseWickRatio = (stratOverride?.maxAdverseWickRatio ?: modelConfig.maxAdverseWickRatio).coerceIn(0.28, 0.60),
            minFutureKumoThicknessAtr = (stratOverride?.minFutureKumoThicknessAtr ?: modelConfig.minFutureKumoThicknessAtr).coerceIn(0.02, 0.18),
            minSniperDistanceAtr = (stratOverride?.minSniperDistanceAtr ?: modelConfig.minSniperDistanceAtr).coerceIn(0.35, 1.10),
            minDriveBooksConsensus = (symbolOverride?.minDriveBooksConsensus
                ?: stratOverride?.minDriveBooksConsensus ?: modelConfig.minDriveBooksConsensus).coerceIn(4, 9),
        )

        val livePrice = livePriceOverride?.takeIf { it.isFinite() && it > 0.0 } ?: trigSnap.close

        // Determine candidate bias from Strategy Timeframe (1H) + Macro Timeframe (4H) + Book-Specific Settings
        val bullishBias = stratSnap.isPriceAboveKumo ||
            (macroSnap?.isPriceAboveKumo == true && trigSnap.isPriceAboveKumo)
        val bearishBias = stratSnap.isPriceBelowKumo ||
            (macroSnap?.isPriceBelowKumo == true && trigSnap.isPriceBelowKumo)

        val isBtc52Model = activeModel == BitcoinStrategyModel.BTC_52_CANDLE_REGIME
        val isParsaModel = activeModel == BitcoinStrategyModel.PARSA_52_LINE_TURN
        val long52Context = trigSnap.regressionSlope52AtrPerBar >= 0.025 &&
            trigSnap.closeChange52Atr >= 1.0 && trigSnap.close >= (trigSnap.prior52High + trigSnap.prior52Low) / 2.0
        val short52Context = trigSnap.regressionSlope52AtrPerBar <= -0.025 &&
            trigSnap.closeChange52Atr <= -1.0 && trigSnap.close <= (trigSnap.prior52High + trigSnap.prior52Low) / 2.0
        val parsaBullishStructure = trigSnap.senkouSpanASlopeAtrPerBar >= 0.025 &&
            trigSnap.tenkan > trigSnap.kijun && trigSnap.bullishTenkanKijunCrossAgeBars <= 8
        val parsaBearishStructure = trigSnap.senkouSpanASlopeAtrPerBar <= -0.025 &&
            trigSnap.tenkan < trigSnap.kijun && trigSnap.bearishTenkanKijunCrossAgeBars <= 8
        val candidateDirection = when {
            isBtc52Model && long52Context -> SignalAction.BUY
            isBtc52Model && short52Context -> SignalAction.SELL
            isParsaModel && parsaBullishStructure -> SignalAction.BUY
            isParsaModel && parsaBearishStructure -> SignalAction.SELL
            bullishBias && !bearishBias -> SignalAction.BUY
            bearishBias && !bullishBias -> SignalAction.SELL
            stratSnap.tenkan >= stratSnap.kijun -> SignalAction.BUY
            else -> SignalAction.SELL
        }

        val isLong = candidateDirection == SignalAction.BUY

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
        val minThickStrat = effectiveConfig.minFutureKumoThicknessAtr
        val minThickTrig = (minThickStrat * 0.80).coerceAtLeast(0.02)
        val futureThicknessOk = stratSnap.futureKumoThicknessAtr >= minThickStrat && trigSnap.futureKumoThicknessAtr >= minThickTrig
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

        // سه کندل پایانی برای این دو روش الگوی تصمیم نیستند؛ منطقشان صرفاً از پنجرهٔ بلند و بسته می‌آید.
        val closedTriggerBars = alignedTriggerCandles.filter { it.closed }.ifEmpty { alignedTriggerCandles }
        val isDirectionalLong = isLong
        val previousBarIndex = closedTriggerBars.lastIndex - 1
        val priorRangeBeforePrevious = if (closedTriggerBars.size >= 54) {
            closedTriggerBars.subList(closedTriggerBars.size - 54, closedTriggerBars.size - 2)
        } else emptyList()
        val previousBar = closedTriggerBars.getOrNull(previousBarIndex)
        val priorHighBeforePrevious = priorRangeBeforePrevious.maxOfOrNull { it.high }
        val priorLowBeforePrevious = priorRangeBeforePrevious.minOfOrNull { it.low }
        val btc52RetestOk = if (isDirectionalLong) {
            val priorHigh = priorHighBeforePrevious
            priorHigh != null && previousBar != null &&
                previousBar.close > priorHigh + trigSnap.atr14 * 0.05 &&
                trigSnap.low <= priorHigh + trigSnap.atr14 * 0.30 &&
                trigSnap.close >= priorHigh - trigSnap.atr14 * 0.08
        } else {
            val priorLow = priorLowBeforePrevious
            priorLow != null && previousBar != null &&
                previousBar.close < priorLow - trigSnap.atr14 * 0.05 &&
                trigSnap.high >= priorLow - trigSnap.atr14 * 0.30 &&
                trigSnap.close <= priorLow + trigSnap.atr14 * 0.08
        }
        val btc52BreakoutOk = if (isDirectionalLong) {
            trigSnap.close > trigSnap.prior52High + trigSnap.atr14 * 0.05 || btc52RetestOk
        } else {
            trigSnap.close < trigSnap.prior52Low - trigSnap.atr14 * 0.05 || btc52RetestOk
        }
        val btc52TrendOk = if (isDirectionalLong) {
            long52Context && trigSnap.tenkanSlopeAtrPerBar > 0.0 &&
                trigSnap.senkouSpanASlopeAtrPerBar >= -0.02 &&
                (macroSnap == null || !macroSnap.isPriceBelowKumo)
        } else {
            short52Context && trigSnap.tenkanSlopeAtrPerBar < 0.0 &&
                trigSnap.senkouSpanASlopeAtrPerBar <= 0.02 &&
                (macroSnap == null || !macroSnap.isPriceAboveKumo)
        }
        val btc52SharpMoveOk = trigSnap.candleBodyRatio >= 0.38 &&
            (trigSnap.volumeRatio20 >= 1.05 || trigSnap.atrExpansionRatio52 >= 1.08) &&
            (if (isDirectionalLong) trigSnap.closeLocationValue >= 0.62 else trigSnap.closeLocationValue <= 0.38)
        val btc52ChikouOk = if (isDirectionalLong) trigSnap.chikouBullishOpenSpace else trigSnap.chikouBearishOpenSpace
        val btc52Ready = closedTriggerBars.size >= 53 && btc52TrendOk && btc52BreakoutOk &&
            btc52SharpMoveOk && btc52ChikouOk && trigSnap.distanceToKijunAtr <= 1.65 &&
            trigSnap.range52Atr >= 2.0

        val parsaSpanATurnOk = if (isDirectionalLong) {
            trigSnap.previousSenkouSpanASlopeAtrPerBar <= 0.0 && trigSnap.senkouSpanASlopeAtrPerBar >= 0.025
        } else {
            trigSnap.previousSenkouSpanASlopeAtrPerBar >= 0.0 && trigSnap.senkouSpanASlopeAtrPerBar <= -0.025
        }
        val parsaCrossAge = if (isDirectionalLong) trigSnap.bullishTenkanKijunCrossAgeBars else trigSnap.bearishTenkanKijunCrossAgeBars
        val parsaFlatOk = trigSnap.senkouSpanBFlatBars >= 18
        val parsaSlopeOk = if (isDirectionalLong) {
            trigSnap.tenkanSlopeAtrPerBar > 0.015 && trigSnap.kijunSlopeAtrPerBar >= -0.04 &&
                trigSnap.senkouSpanASlopeAtrPerBar > 0.0
        } else {
            trigSnap.tenkanSlopeAtrPerBar < -0.015 && trigSnap.kijunSlopeAtrPerBar <= 0.04 &&
                trigSnap.senkouSpanASlopeAtrPerBar < 0.0
        }
        val parsaLineSpacingOk = trigSnap.tenkanKijunGapAtr in 0.02..0.75 &&
            trigSnap.tenkanKijunGapExpansionAtr >= -0.02 && trigSnap.distanceToKijunAtr <= 1.50
        val parsaChikouOk = if (isDirectionalLong) trigSnap.chikouBullishOpenSpace else trigSnap.chikouBearishOpenSpace
        val parsaSharpMoveOk = trigSnap.candleBodyRatio >= 0.35 &&
            (trigSnap.volumeRatio20 >= 1.10 || trigSnap.atrExpansionRatio52 >= 1.05) &&
            (if (isDirectionalLong) trigSnap.closeLocationValue >= 0.60 else trigSnap.closeLocationValue <= 0.40)
        val parsaCloudOk = if (isDirectionalLong) {
            trigSnap.close > trigSnap.kijun && (macroSnap == null || !macroSnap.isPriceBelowKumo)
        } else {
            trigSnap.close < trigSnap.kijun && (macroSnap == null || !macroSnap.isPriceAboveKumo)
        }
        val parsaReady = parsaFlatOk && parsaSpanATurnOk && parsaCrossAge <= 8 &&
            parsaSlopeOk && parsaLineSpacingOk && parsaChikouOk && parsaSharpMoveOk && parsaCloudOk

        val triggerType = when {
            isBtc52Model && btc52Ready -> StrategyTriggerType.BTC_52_STRUCTURE_BREAK
            isParsaModel && parsaReady -> StrategyTriggerType.PARSA_52_LINE_TURN
            isBtc52Model || isParsaModel -> StrategyTriggerType.NONE
            else -> detectEntryTrigger(
                direction = candidateDirection,
                bars = closedTriggerBars,
                trigSnap = trigSnap,
                stratSnap = stratSnap,
                config = effectiveConfig,
                activeModel = activeModel,
            )
        }

        // ── Compute Sniper Entry Zone & Tight Structural SL (متود یکپارچه با جامع بیس) ──
        val slBuffer = trigSnap.atr14 * effectiveConfig.slBufferAtr
        val candleGoldenPocket = if (isLong) {
            trigSnap.low + (trigSnap.high - trigSnap.low) * 0.55
        } else {
            trigSnap.high - (trigSnap.high - trigSnap.low) * 0.55
        }
        val activeTenkanRef = trigSnap.tenkan
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
        val referenceEntryForSl = if (isLong) safeAnchorLong else safeAnchorShort

        // متود روز دنیا برای حد ضرر: هم‌پوشانی ۵ سنگر ابطال ساختاری (فرکتال ۵ کندلی بیل ویلیامز، کیجون۲۶، کیجون۳۰/EMA34، لبه کومو و نقطه S هوسودا)
        // به‌علاوه بافر تطبیقی ضد استاپ‌هانت و محصور در دالان طلایی نوسان [minSafeStopDist .. 2.10 * ATR14]
        val baseTechnicalSl = computeOptimalStructuralStopLoss(
            symbol = normalizedSymbol,
            isLong = isLong,
            referenceEntry = referenceEntryForSl,
            trigSnap = trigSnap,
            stratSnap = stratSnap,
            wave = wave,
            slBufferAtr = effectiveConfig.slBufferAtr,
            triggerInterval = effectiveConfig.triggerInterval,
            activeModel = activeModel,
        )
        val specialStopAnchor = when {
            isBtc52Model && isLong -> trigSnap.prior52Low - trigSnap.atr14 * effectiveConfig.slBufferAtr
            isBtc52Model -> trigSnap.prior52High + trigSnap.atr14 * effectiveConfig.slBufferAtr
            isParsaModel && isLong && trigSnap.currentSpanB < referenceEntryForSl ->
                trigSnap.currentSpanB - trigSnap.atr14 * effectiveConfig.slBufferAtr
            isParsaModel && !isLong && trigSnap.currentSpanB > referenceEntryForSl ->
                trigSnap.currentSpanB + trigSnap.atr14 * effectiveConfig.slBufferAtr
            else -> null
        }
        val rawTechnicalSl = specialStopAnchor?.let { structural ->
            if (isLong) min(baseTechnicalSl, structural) else max(baseTechnicalSl, structural)
        } ?: baseTechnicalSl

        val rawStopDist = max(
            abs(sniperOptimal - rawTechnicalSl),
            abs(livePrice - rawTechnicalSl),
        ).coerceAtLeast(minSafeStopDist)

        // حد ضرر ساختاری (Kijun / Senkou B / 5-Bar Fractal + ATR Buffer) کاملاً ثابت و غیرپله‌ای است
        // قاعدهٔ تازهٔ استراتژی ۱۶: حد ضرر بالای خط ۲۶ (برای خرید) و با فاصله از کندل؛ اگر جمع نشود، پلن مسدود می‌شود.
        val kijunStopRule = if (KIJUN_LAYER_IN_STRATEGY_16_ENABLED && activeModel.isNeoWaveElliottStrategy) {
            applyKijunStopRules(
                isLong = isLong,
                candidateStop = rawTechnicalSl,
                triggerSnapshot = trigSnap,
            )
        } else {
            null
        }
        val technicalSl = kijunStopRule?.stop ?: rawTechnicalSl

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
        // استراتژی ۱۶ در نسخهٔ الیوت‌محور دیگر شرط اجماع چندلایه ندارد.
        val driveBookRuleOk = true
        val cond11Passed = directionalCandleOk && volumeMomentumOk && driveBookRuleOk
        val driveBookExtraDetail = ""
        val cond11Detail = String.format(
            Locale.US,
            "بدنه کندل: %.0f%% · CLV: %.0f%% · حجم به میانگین ۲۰: %.2fx%s",
            trigSnap.candleBodyRatio * 100.0,
            trigSnap.closeLocationValue * 100.0,
            trigSnap.volumeRatio20,
            driveBookExtraDetail,
        )

        val requiredMinRr = effectiveConfig.minRiskReward.coerceIn(1.50, 2.85)
        val maxAllowedRr = 3.00

        // Check 13 & 15 early inputs so Optimal R:R (عدد مناسب R:R) can adapt to Trend Phase & Efficiency
        val erStrat = stratSnap.efficiencyRatio14
        val erTrig = trigSnap.efficiencyRatio14
        val minErReq = effectiveConfig.minEfficiencyRatio
        val dualFlatChop = stratSnap.isKijunFlat && stratSnap.isCurrentSpanBFlat &&
            trigSnap.isKijunFlat && trigSnap.isCurrentSpanBFlat && erStrat < minErReq
        val htfKijunCounterTrap = if (isLong) {
            trigSnap.close > trigSnap.kijun && stratSnap.close < stratSnap.kijun - stratSnap.atr14 * 0.25
        } else {
            trigSnap.close < trigSnap.kijun && stratSnap.close > stratSnap.kijun + stratSnap.atr14 * 0.25
        }
        val cond13Passed = (erStrat >= minErReq || erTrig >= (minErReq + 0.02)) && !dualFlatChop && !htfKijunCounterTrap

        val waveBars = wave?.barsSinceWaveStart ?: 12
        val trendPhase = when {
            !cond13Passed || dualFlatChop -> TrendPhase.CHOP_RANGE
            !notOverextended || (waveBars > 55 && trigSnap.isTenkanFlat && stratSnap.isTenkanFlat) -> TrendPhase.LATE_EXHAUSTION
            waveBars <= 18 || trigSnap.recentKatanaRelease || triggerType == StrategyTriggerType.EARLY_KATANA_GENESIS -> TrendPhase.GENESIS
            else -> TrendPhase.CORE_EXPANSION
        }

        // ── محاسبه «عدد مناسب R:R» (Optimal R:R) در بازه قفل‌شده [1:1.50 .. 1:3.00] برای تمام ۱۶ استراتژی ──
        val modelBaseOptimalRr = activeModel.baseOptimalRr.coerceIn(1.50, 3.00)
        val phaseRrBonus = when (trendPhase) {
            TrendPhase.GENESIS -> 0.25
            TrendPhase.CORE_EXPANSION -> 0.12
            else -> 0.0
        }
        val erRrBonus = ((erStrat + erTrig - 0.50) * 0.45).coerceIn(-0.15, 0.35)
        val volRrBonus = if (trigSnap.volumeRatio20 >= 1.20) 0.12 else 0.0
        val computedOptimalRr = round2(
            (modelBaseOptimalRr + phaseRrBonus + erRrBonus + volRrBonus).coerceIn(requiredMinRr, maxAllowedRr)
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

        // انتخاب هدف قیمتی از بین امواج هوسودا (NT, V, N, E) که داخل دالان [requiredMinRr .. 3.00] قرار داشته باشد
        val validWaveCandidates = labeledWaveTargets.filter { (_, targetPrice) ->
            val inRightDirection = if (isLong) targetPrice > max(livePrice, sniperOptimal) else targetPrice < min(livePrice, sniperOptimal)
            val minDistFromEntries = min(abs(targetPrice - livePrice), abs(targetPrice - sniperOptimal))
            val maxDistFromEntries = max(abs(targetPrice - livePrice), abs(targetPrice - sniperOptimal))
            inRightDirection && (minDistFromEntries / stopDist) >= requiredMinRr && (maxDistFromEntries / stopDist) <= maxAllowedRr
        }
        val chosenWavePair: Pair<String, Double>? = validWaveCandidates.minByOrNull { (_, targetPrice) ->
            val targetRr = abs(targetPrice - sniperOptimal) / stopDist
            abs(targetRr - computedOptimalRr)
        }

        val entryAnchorForTp = if (isLong) max(livePrice, sniperOptimal) else min(livePrice, sniperOptimal)
        val minTpDist = stopDist * requiredMinRr
        val maxTpDist = stopDist * maxAllowedRr
        val optTpDist = stopDist * computedOptimalRr.coerceIn(requiredMinRr, maxAllowedRr)

        val btc52TargetDistance = (trigSnap.range52Atr * trigSnap.atr14 * 0.35)
            .coerceIn(minTpDist, maxTpDist)
        val rawPrimaryTp = when {
            isBtc52Model && isLong -> entryAnchorForTp + btc52TargetDistance
            isBtc52Model -> (entryAnchorForTp - btc52TargetDistance).coerceAtLeast(0.00001)
            isParsaModel && isLong -> entryAnchorForTp + optTpDist
            isParsaModel -> (entryAnchorForTp - optTpDist).coerceAtLeast(0.00001)
            chosenWavePair != null -> chosenWavePair.second
            isLong -> entryAnchorForTp + optTpDist
            else -> (entryAnchorForTp - optTpDist).coerceAtLeast(0.00001)
        }
        val selectedWaveLabel = when {
            isBtc52Model -> "امتداد دامنهٔ ساختار ۵۲ کندلی"
            isParsaModel -> "هدف حرکتی پس از چرخش Senkou A"
            chosenWavePair != null -> chosenWavePair.first
            else -> String.format(Locale.US, "موج بهینه هوسودا (%.2fR)", computedOptimalRr)
        }

        // Check nearest HTF horizontal plateau (Karen Péloille's Flat SSB/Kijun wall)
        val allPlateaus = ((macroSnap?.flatEquilibriumLevels ?: emptyList()) + stratSnap.flatEquilibriumLevels).distinct().sorted()
        val nearestObstacle = if (isLong) {
            allPlateaus.firstOrNull { it > livePrice + trigSnap.atr14 * 0.4 }
        } else {
            allPlateaus.lastOrNull { it < livePrice - trigSnap.atr14 * 0.4 }
        }

        // ── Check 12: Room to HTF Plateau & Optimal R:R strictly in [1:1.50 .. 1:3.00] ──
        val unclampedTp = if (nearestObstacle != null) {
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

        // قفل قطعی تارگت سود در بازه [1.50R .. 3.00R] نسبت به نقطه ورود
        val effectiveTp = if (isLong) {
            val minAllowedTp = max(livePrice, sniperOptimal) + abs(max(livePrice, sniperOptimal) - technicalSl) * requiredMinRr
            val maxAllowedTp = min(livePrice, sniperOptimal) + abs(min(livePrice, sniperOptimal) - technicalSl) * maxAllowedRr
            round2(unclampedTp.coerceIn(minAllowedTp, max(minAllowedTp, maxAllowedTp)))
        } else {
            val minAllowedTp = (min(livePrice, sniperOptimal) - abs(min(livePrice, sniperOptimal) - technicalSl) * requiredMinRr).coerceAtLeast(0.00001)
            val maxAllowedTp = (max(livePrice, sniperOptimal) - abs(max(livePrice, sniperOptimal) - technicalSl) * maxAllowedRr).coerceAtLeast(0.00001)
            round2(unclampedTp.coerceIn(min(minAllowedTp, maxAllowedTp), minAllowedTp))
        }

        // ── موتور واحد ایمن‌سازی پلن (Trade Plan Guard) ─────────────────────────
        // حد ضرر ساختاری و هدف موج هوسودا داخل «دالان نوسان تایم‌فریم» قفل می‌شوند؛ بنابراین
        // ۱R هرگز از نوسان واقعی همان تایم‌فریم بزرگ‌تر نمی‌شود و هدف هم روی کاغذ باقی نمی‌ماند.
        val guardedPlan = TradePlanGuard.sanitizePlan(
            symbol = normalizedSymbol,
            action = if (isLong) SignalAction.BUY else SignalAction.SELL,
            entryPrice = sniperOptimal,
            candidateStopLoss = technicalSl,
            candidateTakeProfit = effectiveTp,
            desiredRiskReward = computedOptimalRr,
            referenceAtr = trigSnap.atr14,
            triggerInterval = effectiveConfig.triggerInterval,
            strategyAtr = stratSnap.atr14,
        )
        // در گزارش‌های بدون سیگنال (NO_TRADE) اعداد ساختاری خام برای نمایش حفظ می‌شوند؛
        // اما هر گزارش قابل‌اجرا فقط با پلن ایمن‌شده ساخته می‌شود.
        val finalStopLoss = if (guardedPlan.isUsable) guardedPlan.stopLoss else round2(technicalSl)
        val finalTakeProfit = if (guardedPlan.isUsable) guardedPlan.takeProfit else round2(effectiveTp)
        val finalStopDist = if (guardedPlan.isUsable) {
            guardedPlan.stopDistance.coerceAtLeast(trigSnap.atr14 * 0.25)
        } else {
            abs(sniperOptimal - technicalSl).coerceAtLeast(trigSnap.atr14 * 0.25)
        }
        val finalTargetDist = if (guardedPlan.isUsable) guardedPlan.targetDistance else abs(effectiveTp - sniperOptimal)

        val rr = if (guardedPlan.isUsable) {
            round2(guardedPlan.riskReward.coerceIn(1.50, 3.00))
        } else {
            round2(computedOptimalRr.coerceIn(1.50, 3.00))
        }
        val effectiveOptimalRr = round2(max(computedOptimalRr, rr).coerceIn(1.50, 3.00))
        val obstacleClear = if (nearestObstacle == null) {
            true
        } else {
            (min(abs(nearestObstacle - livePrice), abs(nearestObstacle - sniperOptimal)) / finalStopDist) >= requiredMinRr
        }
        val cond12Passed = rr in requiredMinRr..maxAllowedRr && obstacleClear

        val rewardDistSniper = round2(finalTargetDist)
        val riskDistSniper = round2(finalStopDist)
        val slBasisTextFa = buildString {
            append("هم‌پوشانی فرکتال ۵ کندلی بیل ویلیامز + کیجون۲۶ (${round2(trigSnap.kijun)}) + کیجون۳۰/EMA34 + لبه کومو")
            if (activeModel == BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER) {
                append(" + هندسهٔ الیوت–نئوویو (نسخهٔ پیمان، هم‌تراز جامع بیس)")
            }
            append(String.format(Locale.US, " + سپر ضد شدو (%.2f ATR) در دالان [0.80..2.10 ATR]", slBuffer))
            append(" + قفل سقف درصدی نوسان " + effectiveConfig.triggerInterval.label)
        }
        val tpBasisTextFa = buildString {
            append(selectedWaveLabel)
            append(" (${round2(finalTakeProfit)}) · قفل در بازه [1:1.50 .. 1:3.00]")
            if (nearestObstacle != null) {
                append(" · قبل از دیوار فلت تایم بالاتر ($nearestObstacle)")
            }
        }
        val formulaTextFa = String.format(
            Locale.US,
            "فرمول: پاداش (|TP−Entry| = %s) ÷ ریسک ۱R (|Entry−SL| = %s) = 1:%.2f (در بازه مجاز ۱.۵ تا ۳.۰)",
            rewardDistSniper.toString(),
            riskDistSniper.toString(),
            rr,
        )
        val optimalRrReasonTextFa = buildString {
            append(String.format(Locale.US, "عدد مناسب R:R = 1:%.2f", rr))
            append(" (در بازه قفل‌شده [1:1.50 .. 1:3.00] · پایه ${activeModel.badgeFa}: 1:${String.format(Locale.US, "%.2f", modelBaseOptimalRr)}")
            append(" + ${trendPhase.badgeFa}")
            append(" + کارایی ER=${(erStrat * 100).toInt()}%")
            append(" ← هدف: $selectedWaveLabel · 🔒 حد ضرر ساختاری کاملاً ثابت و غیرپله‌ای)")
            append(" · 🛡️ ${guardedPlan.summaryFa()}")
        }
        val rrBreakdownObj = RrCalculationBreakdown(
            entryPrice = round2(sniperOptimal),
            stopLossPrice = round2(finalStopLoss),
            takeProfitPrice = round2(finalTakeProfit),
            riskDistance1R = riskDistSniper,
            rewardDistance = rewardDistSniper,
            actualRiskReward = rr,
            optimalRiskReward = effectiveOptimalRr,
            selectedTargetWaveLabel = selectedWaveLabel,
            formulaTextFa = formulaTextFa,
            slBasisFa = slBasisTextFa,
            tpBasisFa = tpBasisTextFa,
            optimalRrReasonFa = optimalRrReasonTextFa,
            runnerArmedAbove3R = false,
        )

        val cond12Detail = buildString {
            append(String.format(Locale.US, "عدد مناسب R:R = 1:%.2f (%s · بازه مجاز 1.5..3.0)", rr, selectedWaveLabel))
            append(" · فرمول: $rewardDistSniper ÷ $riskDistSniper")
            append(" · 🔒 حد ضرر ثابت ساختاری")
            append(" · 🛡️ ${guardedPlan.verdict.badgeFa}")
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
        val maxWickAllowed = effectiveConfig.maxAdverseWickRatio
        val cond14Passed = adverseWick <= maxWickAllowed && (trigSnap.candleBodyRatio >= 0.22 || favorableTail >= 0.25)
        val cond14Detail = String.format(
            Locale.US,
            "شدوی مخالف: %.0f%% (سقف مجاز %.0f%%) · شدوی تایید: %.0f%%",
            adverseWick * 100.0,
            maxWickAllowed * 100.0,
            favorableTail * 100.0,
        )

        // ── Check 15: Core-Trend Phase Classification (Genesis / Core vs Exhaustion) ──
        val cond15Passed = trendPhase == TrendPhase.GENESIS || trendPhase == TrendPhase.CORE_EXPANSION
        val cond15Detail = "${trendPhase.badgeFa} · کندل $waveBars موج · فاصله کیجون: ${String.format(Locale.US, "%.2f", extAtr)} ATR"

        // ── Check 16: Sniper Entry Precision & Anti-Chase Pocket ──────────────
        val distFromTenkanAtr = abs(trigSnap.close - activeTenkanRef) / trigSnap.atr14.coerceAtLeast(1e-8)
        val maxSniperDist = effectiveConfig.maxSniperDistanceAtr.coerceIn(0.20, 0.55)
        val cond16Passed = distToSniperAtr <= maxSniperDist && distFromTenkanAtr <= (maxSniperDist + 0.50)
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

        val btc52Conditions = listOf(
            StrategyConditionCheck(1, "۱. دادهٔ بستهٔ کامل پنجرهٔ ۵۲ کندلی", closedTriggerBars.size >= 53, "${closedTriggerBars.size} کندل بسته؛ برای سطح شکست به ۵۲ کندل قبل + کندل جاری نیاز است."),
            StrategyConditionCheck(2, "۲. جهت و جایگاه ساختاری ۵۲ کندلی", if (isLong) long52Context else short52Context, String.format(Locale.US, "شیب رگرسیون=%+.2f ATR/کندل · تغییر=%+.2f ATR · دامنه=%.1f ATR", trigSnap.regressionSlope52AtrPerBar, trigSnap.closeChange52Atr, trigSnap.range52Atr)),
            StrategyConditionCheck(3, "۳. شکست بسته‌شده یا بازآزمایی مرز ۵۲ کندلی", btc52BreakoutOk, "مرز قبلی: ${if (isLong) trigSnap.prior52High else trigSnap.prior52Low} · کندل جاری در محاسبهٔ مرز وارد نشده است."),
            StrategyConditionCheck(4, "۴. فشردگی/همگرایی پیش از گسترش", trigSnap.compressionRatio26 <= 1.20 || trigSnap.isBollingerSqueeze, String.format(Locale.US, "نسبت دامنهٔ ۲۶ کندل اخیر به ۲۶ کندل قبل=%.2f · فشردگی بولینگر=%s", trigSnap.compressionRatio26, trigSnap.isBollingerSqueeze)),
            StrategyConditionCheck(5, "۵. همسویی ابر و تایم‌فریم بالاتر", if (isLong) trigSnap.isFutureKumoBullish && (macroSnap == null || !macroSnap.isPriceBelowKumo) else trigSnap.isFutureKumoBearish && (macroSnap == null || !macroSnap.isPriceAboveKumo), "ابر آینده و تایم‌فریم بالاتر نباید جهت معامله را نقض کنند."),
            StrategyConditionCheck(6, "۶. شیب Tenkan/Kijun و Senkou A", if (isLong) trigSnap.tenkanSlopeAtrPerBar > 0.0 && trigSnap.kijunSlopeAtrPerBar >= -0.05 && trigSnap.senkouSpanASlopeAtrPerBar >= -0.02 else trigSnap.tenkanSlopeAtrPerBar < 0.0 && trigSnap.kijunSlopeAtrPerBar <= 0.05 && trigSnap.senkouSpanASlopeAtrPerBar <= 0.02, String.format(Locale.US, "شیب TK=%+.2f/%+.2f ATR · شیب SSA=%+.2f ATR", trigSnap.tenkanSlopeAtrPerBar, trigSnap.kijunSlopeAtrPerBar, trigSnap.senkouSpanASlopeAtrPerBar)),
            StrategyConditionCheck(7, "۷. آزادی Chikou در مسیر معامله", btc52ChikouOk, if (btc52ChikouOk) "چیکو از قیمت/کندل متناظر، TK و ابر آزاد است." else "چیکو با کندل‌ها یا ابر برخورد دارد."),
            StrategyConditionCheck(8, "۸. کیفیت حرکت شارپ (بدنه، حجم یا گسترش ATR)", btc52SharpMoveOk, String.format(Locale.US, "بدنه=%.0f%% · حجم=%.2fx · ATR گسترش=%.2fx", trigSnap.candleBodyRatio * 100.0, trigSnap.volumeRatio20, trigSnap.atrExpansionRatio52)),
            StrategyConditionCheck(9, "۹. فاصلهٔ قابل‌تعقیب از Kijun", trigSnap.distanceToKijunAtr <= 1.65, String.format(Locale.US, "%.2f ATR (حداکثر ۱٫۶۵)", trigSnap.distanceToKijunAtr)),
            StrategyConditionCheck(10, "۱۰. پلن نهایی و R:R حداقل ۱:۱٫۵", cond12Passed && guardedPlan.isUsable, cond12Detail),
        )
        val parsaConditions = listOf(
            StrategyConditionCheck(1, "۱. فلت واقعی Senkou Span B (خط دورهٔ ۵۲)", parsaFlatOk, "SSB به‌مدت ${trigSnap.senkouSpanBFlatBars} کندل بسته تخت؛ حد لازم ۱۸ کندل (بیش از ۱۷)."),
            StrategyConditionCheck(2, "۲. چرخش Senkou Span A برای ابر آینده", parsaSpanATurnOk, String.format(Locale.US, "شیب قبلی=%+.2f ATR، اکنون=%+.2f ATR در جهت معامله.", trigSnap.previousSenkouSpanASlopeAtrPerBar, trigSnap.senkouSpanASlopeAtrPerBar)),
            StrategyConditionCheck(3, "۳. کراس هم‌جهت Tenkan/Kijun در ۸ کندل", parsaCrossAge <= 8, "سن کراس: ${if (parsaCrossAge == Int.MAX_VALUE) "یافت نشد" else "$parsaCrossAge کندل"}؛ مهلت مجاز ۸ کندل بسته."),
            StrategyConditionCheck(4, "۴. شیب همسو و نبود مخالفت شدید Kijun", parsaSlopeOk, String.format(Locale.US, "شیب Tenkan=%+.2f · Kijun=%+.2f · Senkou A=%+.2f ATR/کندل", trigSnap.tenkanSlopeAtrPerBar, trigSnap.kijunSlopeAtrPerBar, trigSnap.senkouSpanASlopeAtrPerBar)),
            StrategyConditionCheck(5, "۵. فاصله و گسترش سنجیدهٔ Tenkan/Kijun", parsaLineSpacingOk, String.format(Locale.US, "فاصله=%.2f ATR · تغییر فاصله=%+.2f ATR · قیمت تا Kijun=%.2f ATR", trigSnap.tenkanKijunGapAtr, trigSnap.tenkanKijunGapExpansionAtr, trigSnap.distanceToKijunAtr)),
            StrategyConditionCheck(6, "۶. آزادی Chikou", parsaChikouOk, if (parsaChikouOk) "چیکو از کندل، TK و ابر در مسیر معامله آزاد است." else "چیکو هنوز درگیر ساختار متناظر است."),
            StrategyConditionCheck(7, "۷. کیفیت حرکت شارپ و تأیید حجم/ATR", parsaSharpMoveOk, String.format(Locale.US, "بدنه=%.0f%% · حجم=%.2fx · گسترش ATR=%.2fx", trigSnap.candleBodyRatio * 100.0, trigSnap.volumeRatio20, trigSnap.atrExpansionRatio52)),
            StrategyConditionCheck(8, "۸. سمت قیمت و نبود تضاد تایم‌فریم بالاتر", parsaCloudOk, "قیمت نسبت به Kijun و تایم‌فریم بالاتر با جهت معامله سنجیده شد."),
            StrategyConditionCheck(9, "۹. پلن نهایی و R:R حداقل ۱:۱٫۵", cond12Passed && guardedPlan.isUsable, cond12Detail),
        )
        val conditions = when {
            isBtc52Model -> btc52Conditions
            isParsaModel -> parsaConditions
            activeModel.isDrivePdfStrategy -> buildElliottWaveConditions(
                isLong = isLong,
                wave = wave,
                selectedWaveLabel = selectedWaveLabel,
                rewardDistSniper = rewardDistSniper,
                cond12Passed = cond12Passed,
                cond12Detail = cond12Detail,
                cond13Passed = cond13Passed,
                cond13Detail = cond13Detail,
                cond14Passed = cond14Passed,
                cond14Detail = cond14Detail,
                cond16Passed = cond16Passed,
                cond16Detail = cond16Detail,
            )
            else -> baseSixteenConditions
        }

        val requiredIndicesForModel: Set<Int> = when {
            isBtc52Model -> btc52Conditions.indices.map { it + 1 }.toSet()
            isParsaModel -> parsaConditions.indices.map { it + 1 }.toSet()
            else -> (1..16).toSet()
        }
        val modelCoreConditions = conditions.filter { it.index in requiredIndicesForModel }
        val allSixteenPassed = modelCoreConditions.all { it.passed }

        // ── گیت کیجون (فقط استراتژی ۱۶): کراس ۹/۲۶ + خطوط پیش‌بینی ۸/۲۵، ۷/۲۴، ۶/۲۳ + وضعیت ابر ──
        val kijunGate = if (KIJUN_LAYER_IN_STRATEGY_16_ENABLED && activeModel.isNeoWaveElliottStrategy) {
            evaluateKijunPredictiveGate(
                candlesInput = alignedStrategyCandles,
                isLong = isLong,
                strategySnapshot = stratSnap,
            )
        } else {
            null
        }
        val kijunGatePassed = (kijunGate?.passed ?: true) && (kijunStopRule?.blocked != true)
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

        // ── لایهٔ اختیاری نئوویو (NeoWave Gate) ────────────────────────────────
        // قانون صفر-اثر: وقتی `neoWaveEnabled = false` باشد هیچ محاسبه‌ای انجام نمی‌شود و
        // خروجی این تابع بیت‌به‌بیت مثل نسخهٔ قبل از نئوویو می‌ماند.
        // نئوویو–الیوت فقط و فقط در استراتژی جایگاه ۱۶ ادغام شده است و همیشه در همان استراتژی
        // روشن است (کلید کاربر ندارد). برای هیچ روش دیگری محاسبه‌ای انجام نمی‌شود.
        val neoWaveOn = activeModel.isNeoWaveElliottStrategy
        // دامنهٔ زیگزاگ داینامیک: با رژیم نوسان همان نماد/تایم‌فریم مقیاس می‌گیرد (قاعدهٔ درجه/دامنهٔ نیلی).
        val neoWaveAmplitude = if (neoWaveOn) {
            NeoWaveEngine.dynamicAmplitudeAtr(alignedStrategyCandles, effectiveConfig.neoWaveAmplitudeAtr)
        } else {
            effectiveConfig.neoWaveAmplitudeAtr
        }
        val neoWaveVerdict = if (neoWaveOn) {
            NeoWaveEngine.analyze(candles = alignedStrategyCandles, amplitudeAtr = neoWaveAmplitude)
        } else {
            null
        }
        // ساختار درجهٔ بالاتر (تایم‌فریم ماکرو): بر درجهٔ پایین‌تر حکومت می‌کند.
        val neoWaveHigherVerdict = if (neoWaveOn) {
            NeoWaveEngine.analyze(candles = alignedMacroCandles, amplitudeAtr = neoWaveAmplitude)
        } else {
            null
        }
        val neoWaveMtf = if (neoWaveOn) {
            NeoWaveEngine.mtfConfluence(neoWaveVerdict, neoWaveHigherVerdict, isLong)
        } else {
            NeoWaveEngine.MtfConfluence.NEUTRAL
        }
        // آستانه‌ها: اگر تنظیمات به هوش عصبی سپرده شده باشد، همان مقادیر آموخته‌شده اعمال می‌شوند.
        val neoWaveRulesThreshold = effectiveConfig.resolvedNeoWaveRules(isCrypto = false)
        val neoWaveConfidenceThreshold = effectiveConfig.resolvedNeoWaveConfidence(isCrypto = false)
        val neoWaveGate = if (neoWaveOn) {
            NeoWaveEngine.gate(
                verdict = neoWaveVerdict,
                isLong = isLong,
                minRulesPassed = neoWaveRulesThreshold,
                minConfidencePct = neoWaveConfidenceThreshold,
                ruleWeights = effectiveConfig.neoWaveWeightsMap(),
            )
        } else {
            NeoWaveEngine.NeoWaveGate.OFF
        }
        val neoWaveTotalDelta = neoWaveGate.confidenceDelta + neoWaveMtf.deltaConfidence
        val gatedConfidence = if (neoWaveOn) {
            round2((confidence + neoWaveTotalDelta).coerceIn(0.0, 100.0))
        } else {
            confidence
        }
        val gatedTrendScore = if (neoWaveOn) {
            round2((coreTrendScore + neoWaveTotalDelta * 0.5).coerceIn(0.0, 100.0))
        } else {
            coreTrendScore
        }

        val customWatchSetup = when {
            isBtc52Model -> closedTriggerBars.size >= 53 && (long52Context || short52Context)
            isParsaModel -> parsaFlatOk && (parsaSpanATurnOk || parsaCrossAge <= 8)
            else -> false
        }
        val sniperArmedPendingRetest = !isBtc52Model && !isParsaModel && firstFifteenPassed && triggerFired && !cond16Passed

        val baseReadiness = when {
            neoWaveGate.veto || (neoWaveOn && neoWaveMtf.veto) -> StrategyReadinessState.BLOCKED
            allSixteenPassed && kijunGatePassed && triggerFired && isLong -> StrategyReadinessState.SIGNAL_BUY
            allSixteenPassed && kijunGatePassed && triggerFired && !isLong -> StrategyReadinessState.SIGNAL_SELL
            allSixteenPassed && !kijunGatePassed -> StrategyReadinessState.BLOCKED
            customWatchSetup || sniperArmedPendingRetest || (!isBtc52Model && !isParsaModel && passedCount >= 11) -> StrategyReadinessState.WATCH_SETUP
            else -> StrategyReadinessState.BLOCKED
        }

        // ── مسیر ورود رمزارز بر پایهٔ نئوویو ─────────────────────────────────
        // شرط عمومی (هر ۱۶ شرط هم‌زمان) در رمزارز می‌تواند روزها برقرار نشود. در این مسیر
        // الگوی کامل، تأییدشده و هم‌جهتِ نئوویو ماشهٔ ورود است؛ کنترل‌های ریسک دست‌نخورده‌اند
        // (R:R ≥ ۱.۵، پلن معتبر، بدون وتو، بدون بازار بسته).
        val isCryptoClass = AssetClass.of(normalizedSymbol) == AssetClass.CRYPTO
        val cryptoEntryDecision = NeoWaveEngine.cryptoEntryDecision(
            enabled = neoWaveOn && effectiveConfig.neoWaveCryptoEntryEnabled &&
                !neoWaveGate.veto && !neoWaveMtf.veto,
            isCrypto = isCryptoClass,
            verdict = neoWaveVerdict,
            isLong = isLong,
            minRulesPassed = effectiveConfig.resolvedNeoWaveRules(isCrypto = true),
            minConfidencePct = effectiveConfig.resolvedNeoWaveConfidence(isCrypto = true),
            riskReward = rr,
            hasValidPlan = finalStopLoss != null && finalTakeProfit != null && sniperOptimal != null &&
                rr >= 1.5,
        )
        val cryptoNeoWaveEntry = cryptoEntryDecision.eligible &&
            baseReadiness != StrategyReadinessState.SIGNAL_BUY &&
            baseReadiness != StrategyReadinessState.SIGNAL_SELL
        val readiness = when {
            cryptoNeoWaveEntry && isLong -> StrategyReadinessState.SIGNAL_BUY
            cryptoNeoWaveEntry -> StrategyReadinessState.SIGNAL_SELL
            else -> baseReadiness
        }

        val finalAction = if (neoWaveGate.veto || (neoWaveOn && neoWaveMtf.veto)) {
            SignalAction.NO_TRADE
        } else if (!kijunGatePassed && !cryptoNeoWaveEntry) {
            // استراتژی ۱۶ فقط با تأیید کراس ۹/۲۶، خطوط پیش‌بینی و ابر معامله می‌کند.
            SignalAction.NO_TRADE
        } else if (cryptoNeoWaveEntry) {
            if (isLong) SignalAction.BUY else SignalAction.SELL
        } else {
            when (readiness) {
                StrategyReadinessState.SIGNAL_BUY -> SignalAction.BUY
                StrategyReadinessState.SIGNAL_SELL -> SignalAction.SELL
                else -> if (sniperArmedPendingRetest) {
                    if (isLong) SignalAction.BUY else SignalAction.SELL
                } else {
                    SignalAction.NO_TRADE
                }
            }
        }

        val tfTag = "[تایم ${config.triggerInterval.label}]"
        val baseSummaryFa = when {
            readiness == StrategyReadinessState.SIGNAL_BUY -> when {
                isBtc52Model -> "$tfTag 🟠 خرید BTC بر پایهٔ شکست/بازآزمایی ۵۲ کندلی · بدون الگوی سه کندل آخر · R:R=1:${round2(rr)}"
                isParsaModel -> "$tfTag 🟢 خرید پارسا · فلت SSB ${trigSnap.senkouSpanBFlatBars} کندل + چرخش Senkou A + کراس TK · R:R=1:${round2(rr)}"
                else -> "$tfTag 🎯 خرید نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            }
            readiness == StrategyReadinessState.SIGNAL_SELL -> when {
                isBtc52Model -> "$tfTag 🟠 فروش BTC بر پایهٔ شکست/بازآزمایی ۵۲ کندلی · بدون الگوی سه کندل آخر · R:R=1:${round2(rr)}"
                isParsaModel -> "$tfTag 🔴 فروش پارسا · فلت SSB ${trigSnap.senkouSpanBFlatBars} کندل + چرخش Senkou A + کراس TK · R:R=1:${round2(rr)}"
                else -> "$tfTag 🎯 فروش نقطه‌زنی ($normalizedSymbol · $effectiveTitle · دقت ${sniperEntryScore.toInt()}% · ${trendPhase.badgeFa})"
            }
            sniperArmedPendingRetest ->
                "$tfTag 🎯 مسلح برای نقطه‌زنی ($normalizedSymbol · $effectiveTitle): در کمین لمس $sniperOptimal"
            readiness == StrategyReadinessState.WATCH_SETUP -> when {
                isBtc52Model -> "$tfTag ⏳ ساختار ۵۲ کندلی هم‌جهت است؛ منتظر شکست بسته‌شده/بازآزمایی و تأیید حجم/چیکو (${passedCount}/${conditions.size})"
                isParsaModel -> "$tfTag ⏳ SSB ${trigSnap.senkouSpanBFlatBars} کندل تخت؛ منتظر چرخش Senkou A، کراس TK و تأیید شارپ (${passedCount}/${conditions.size})"
                else -> "$tfTag ⏳ $passedCount/${conditions.size} شرط (${trendPhase.badgeFa})؛ منتظر ماشه در ${config.triggerInterval.label}"
            }
            readiness == StrategyReadinessState.BLOCKED -> {
                if (!kijunGatePassed) {
                    val why = when {
                        kijunStopRule?.blocked == true -> kijunStopRule.noteFa
                        kijunGate != null -> kijunGate.summaryFa
                        else -> ""
                    }
                    "$tfTag 🛑 گیت کیجون اجازه نداد · $why"
                } else {
                    val firstFailed = modelCoreConditions.firstOrNull { !it.passed } ?: conditions.firstOrNull { !it.passed }
                    "$tfTag 🛑 $passedCount/${conditions.size} شرط · مانع: ${firstFailed?.titleFa ?: ""}"
                }
            }
            else -> "$tfTag در حال دریافت کندل‌های $normalizedSymbol..."
        }
        val neoWaveSuffix = when {
            !neoWaveOn || neoWaveGate.noteFa.isBlank() -> ""
            neoWaveMtf.noteFa.isBlank() -> " · ${neoWaveGate.noteFa}"
            else -> " · ${neoWaveGate.noteFa} · ${neoWaveMtf.noteFa}"
        }
        val summaryFa = when {
            neoWaveGate.veto || (neoWaveOn && neoWaveMtf.veto) ->
                "$tfTag ⛔ ورود وتو شد · $normalizedSymbol · $effectiveTitle · ${neoWaveGate.noteFa}"
            cryptoNeoWaveEntry ->
                "$tfTag 🌊 ورود رمزارز بر پایهٔ نئوویو · $normalizedSymbol · $effectiveTitle · " +
                    "${cryptoEntryDecision.reasonFa} · R:R ۱:${round2(rr)}"
            else -> baseSummaryFa + neoWaveSuffix
        }
        val entryPathFa = when {
            isBtc52Model -> "روش مستقل بیت‌کوین با زمینهٔ ۵۲ کندل بسته؛ سه کندل آخر الگوی تصمیم نیستند؛ یادگیری عصبی خاموش"
            isParsaModel -> "پارسا: SSB/خط دورهٔ ۵۲ تخت ≥۱۸ کندل + چرخش Senkou A + کراس TK؛ شیب/فاصله، چیکو و حرکت شارپ سنجیده شد؛ یادگیری عصبی خاموش"
            cryptoNeoWaveEntry -> "🌊 مسیر نئوویو (کریپتو): ${cryptoEntryDecision.reasonFa}"
            neoWaveOn && isCryptoClass && neoWaveVerdict != null ->
                "🌊 کریپتو: ${cryptoEntryDecision.reasonFa}"
            else -> ""
        }

        // شرایط نئوویو فقط برای نمایش به چک‌لیست اضافه می‌شوند (در محاسبهٔ امتیاز دخالت ندارند).
        val displayConditions = if (neoWaveOn && neoWaveVerdict != null) {
            conditions + neoWaveVerdict.rules.map { rule ->
                StrategyConditionCheck(
                    index = 16 + rule.index,
                    titleFa = "🌊 نئوویو ${rule.index}. ${rule.titleFa}",
                    passed = rule.passed,
                    detailFa = rule.detailFa,
                )
            }
        } else {
            conditions
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
            confidence = gatedConfidence,
            livePrice = round2(livePrice),
            suggestedEntry = sniperOptimal,
            suggestedStopLoss = finalStopLoss,
            targetV = wave?.targetV,
            targetN = wave?.targetN,
            targetE = wave?.targetE,
            primaryTakeProfit = finalTakeProfit,
            riskReward = rr,
            macroSnapshot = macroSnap,
            strategySnapshot = stratSnap,
            triggerSnapshot = trigSnap,
            waveProjection = wave,
            conditions = displayConditions,
            summaryFa = summaryFa,
            triggerBarTime = trigSnap.time,
            evaluatedAt = nowMillis,
            trendPhase = trendPhase,
            coreTrendScore = gatedTrendScore,
            sniperOptimalEntry = sniperOptimal,
            sniperZoneLow = sniperLow,
            sniperZoneHigh = sniperHigh,
            sniperEntryScore = sniperEntryScore,
            isSniperArmedPendingRetest = sniperArmedPendingRetest,
            optimalRiskReward = effectiveOptimalRr,
            rrBreakdown = rrBreakdownObj,
            planStopPct = guardedPlan.stopPct,
            planTargetPct = guardedPlan.targetPct,
            planRecommendedLeverage = guardedPlan.recommendedLeverage,
            planVerdictFa = guardedPlan.verdict.badgeFa,
            planGuardNoteFa = guardedPlan.summaryFa(),
            neoWaveBadgeFa = if (neoWaveOn) neoWaveGate.noteFa else "",
            neoWaveVeto = neoWaveGate.veto,
            neoWavePatternId = if (neoWaveOn) neoWaveVerdict?.pattern?.name.orEmpty() else "",
            neoWaveRulesPassed = if (neoWaveOn) neoWaveVerdict?.rulesPassed ?: 0 else 0,
            neoWaveRulesTotal = if (neoWaveOn) neoWaveVerdict?.rulesTotal ?: 0 else 0,
            neoWaveConfidencePct = if (neoWaveOn) neoWaveVerdict?.confidencePct ?: 0.0 else 0.0,
            neoWaveFailedRuleIds = if (neoWaveOn) {
                neoWaveVerdict?.rules?.filter { !it.passed }?.map { it.index }.orEmpty()
            } else {
                emptyList()
            },
            neoWaveMtfFa = if (neoWaveOn) neoWaveMtf.noteFa else "",
            neoWaveAmplitudeAtr = if (neoWaveOn) neoWaveAmplitude else 0.0,
            cryptoNeoWaveEntry = cryptoNeoWaveEntry,
            entryPathFa = entryPathFa,
            kijunGateFa = kijunGate?.summaryFa.orEmpty(),
            kijunStopRuleFa = kijunStopRule?.noteFa.orEmpty(),
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
        enabledStrategyIds: Set<String> = BitcoinStrategyModel.activeStrategyIds,
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
            // استراتژی‌های موقتاً خاموش (فعلاً جایگاه ۱۶) حتی اگر در تنظیمات روشن باشند
            // ارزیابی نمی‌شوند؛ شناسه و آموخته‌هایشان حفظ می‌شود تا بعداً از صفر شروع نشوند.
            val activeBuiltIns = BitcoinStrategyModel.entries
                .filter { it.id in enabledStrategyIds && !it.isTemporarilyDisabled }
                .ifEmpty {
                    BitcoinStrategyModel.entries.filterNot {
                        it.isTemporarilyDisabled || it.requiresExplicitOptIn
                    }
                }
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
     * چک‌لیست اختصاصی استراتژی پیمان در نسخهٔ **الیوت–نئوویو**.
     *
     * بنا به درخواست کاربر فقط لایهٔ موجی باقی مانده است؛ شرط‌های اجماع چندلایه، گیت کیجون و
     * تأییدهای DMI/MACD/Kelly حذف شده‌اند. هشت شرط زیر مستقیماً از هندسهٔ موج و کیفیت ورود
     * ساخته می‌شوند و `confidence` موتور مثل قبل از نسبت شرط‌های پاس‌شده محاسبه می‌شود.
     */
    private fun buildElliottWaveConditions(
        isLong: Boolean,
        wave: HosodaWaveProjection?,
        selectedWaveLabel: String,
        rewardDistSniper: Double,
        cond12Passed: Boolean,
        cond12Detail: String,
        cond13Passed: Boolean,
        cond13Detail: String,
        cond14Passed: Boolean,
        cond14Detail: String,
        cond16Passed: Boolean,
        cond16Detail: String,
    ): List<StrategyConditionCheck> {
        val waveAligned = wave != null &&
            ((wave.direction == SignalAction.BUY) == isLong)
        val sPointHeld = wave?.sPointHeld == true
        val waveRoomOk = selectedWaveLabel.isNotBlank() && rewardDistSniper > 0.0
        val timeCycleClear = wave?.isNearTimeCycleChangeDay != true
        return listOf(
            StrategyConditionCheck(
                1,
                "۱. جهت موج الیوت هم‌سو با جهت معامله",
                waveAligned,
                if (wave == null) {
                    "شمارش موج هنوز کامل نیست"
                } else {
                    "جهت موج: " + if (wave.direction == SignalAction.BUY) "صعودی" else "نزولی"
                },
            ),
            StrategyConditionCheck(
                2,
                "۲. معتبر بودن نقطهٔ چرخش (S-Point) و شکسته‌نشدن آن",
                sPointHeld,
                if (wave == null) "نقطهٔ S در دسترس نیست" else "نقطهٔ S: ${round2(wave.pointC)}",
            ),
            StrategyConditionCheck(
                3,
                "۳. فضای هدف موجی (V/N/E) برای ادامهٔ حرکت",
                waveRoomOk,
                if (wave == null) "هدف موجی محاسبه نشده" else "هدف انتخابی: $selectedWaveLabel",
            ),
            StrategyConditionCheck(
                4,
                "۴. چرخهٔ زمانی هوسودا در نقطهٔ تغییر نباشد",
                timeCycleClear,
                if (wave == null) "چرخهٔ زمانی نامشخص" else "چرخهٔ نزدیک: ${wave.nearestTimeCycleNumber}",
            ),
            StrategyConditionCheck(
                5,
                "۵. عدد مناسب R:R (حداقل ۱:۱.۵) روی اهداف موجی",
                cond12Passed,
                cond12Detail,
            ),
            StrategyConditionCheck(
                6,
                "۶. کارایی روند کافمن (ER14) برای موج جهت‌دار",
                cond13Passed,
                cond13Detail,
            ),
            StrategyConditionCheck(
                7,
                "۷. نبود شدوی معکوس و فیک‌بریک‌اوت در موج جاری",
                cond14Passed,
                cond14Detail,
            ),
            StrategyConditionCheck(
                8,
                "۸. نقطه‌زنی ورود در ناحیهٔ موج (Sniper)",
                cond16Passed,
                cond16Detail,
            ),
        )
    }

    /**
     * تشخیص ۴ ماشه ورود برای شکار اصل روند از لحظه تولد (`EARLY_KATANA_GENESIS`) تا قلب موج (`PULLBACK / BREAKOUT / SANYAKU`)
     * استراتژی ۱۶ (پیمان) ماشهٔ اختصاصی ندارد و از همین ماشه‌های استاندارد استفاده می‌کند.
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

        // ماشه‌های اختصاصی متود جامع ۹ لایه حذف شدند: استراتژی ۱۶ فقط لایهٔ الیوت را دارد و از
        // ماشه‌های استاندارد پایین استفاده می‌کند (و فعلاً غیرفعال است).

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
     * ۵ نشانه قطعی مرگ روند در سود بر اساس ۴ مکتب ایچیموکو:
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
        val rulesSummary = "شروط خروج هوشمند: فقط وقتی معامله به +۲R رسیده باشد و یکی از این‌ها رخ دهد: " +
            "۱) کلوز کندل پشت کیجون یا کراس معکوس TK · ۲) شدوی بازگشتی C-Clamp زیر تنکان · " +
            "۳) اتمام چرخه زمانی هوسودا (9/17/26) با تنکان فلت · ۴) برخورد به دیوار فلت MTF و افت کارایی (ER<20%)"

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

            // ۵. تکنیک خروج محرمانه RSI موکرجی (لایهٔ ساختاری ۴ — comic 3): افت بیش از ۱۰ واحد از قله RSI یا شکست قله همراه با کلوز پشت تنکان
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

            // ۶. سیستم ۴ قانون خروج کونیتز (لایه‌های ۸ و ۹): شکست هم‌زمان کیجون و EMA34 همراه با برگشت MACD/DMI
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

        // قانون کاربر: خروج هوشمند فقط وقتی مجاز است که معامله دست‌کم +۲R در سود باشد.
        val shouldExitInProfit = inProfit && currentR >= MIN_SMART_EXIT_PROFIT_R && fatalProfitTriggers.isNotEmpty()
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
     * موتور مدیریت معاملات باز با معماری «حد ضرر ساختاری کاملاً ثابت (غیرپله‌ای) + حفظ R:R اولیه + خروج هوشمند در اتمام روند»:
     * - حد ضرر (`stopLoss`) تحت هیچ شرایطی به‌صورت پله‌ای (`Break-Even` یا تریل پلکانی) جابجا نمی‌شود و تا پایان معامله
     *   دقیقاً روی همان حد ضرر ساختاری اولیه (`initialStopLoss ?: stopLoss`) ثابت می‌ماند.
     * - نسبت `R:R` و حد سود (`takeProfit`) دقیقاً همان مقدار اولیه زمان باز شدن معامله باقی می‌ماند و حتی اگر `R:R > 3.0` باشد،
     *   حد ضرر ثابت مانده و معامله تا هدف اولیه خود (`takeProfit`) یا تشخیص قطعی برگشت روند ادامه می‌یابد.
     */
    fun evaluateOpenTradeManagement(
        trade: PaperTrade,
        report: StrategyEvaluationReport,
        config: BaseStrategyConfig,
    ): PreserveModeEvaluation? {
        if (!trade.isOpen || V1Universe.normalize(trade.symbol) != V1Universe.normalize(report.symbol)) return null
        val trigSnap = report.triggerSnapshot ?: return null
        val livePrice = report.livePrice.takeIf { it > 0.0 } ?: return null

        val fixedStopLoss = round2(trade.initialStopLoss ?: trade.stopLoss)
        val oneRDistance = abs(trade.entry - fixedStopLoss).coerceAtLeast(trigSnap.atr14 * 0.4)
        val isBuy = trade.action == SignalAction.BUY

        val currentProfitDist = if (isBuy) livePrice - trade.entry else trade.entry - livePrice
        val currentRMultiple = currentProfitDist / oneRDistance

        // ── ۱. تشخیص از دست رفتن قطعی روند ایچیموکو در سود و بستن مستقیم در سود (بدون جابجایی پله‌ای حد ضرر) ──
        val trendHealth = evaluateTrendHealthAndProfitExit(trade, report, livePrice)
        if (trendHealth.shouldExitNowInProfit && currentRMultiple >= MIN_SMART_EXIT_PROFIT_R) {
            return PreserveModeEvaluation(
                tradeId = trade.id,
                newStopLoss = fixedStopLoss,
                stageTitleFa = "⚡ خروج هوشمند در سود (اتمام روند در +${round2(currentRMultiple)}R با حد ضرر ثابت)",
                reasonFa = trendHealth.exitTriggerReasonFa,
                lockedRMultiple = round2(currentRMultiple),
                shouldCloseImmediatelyInProfit = true,
                closePrice = round2(livePrice),
            )
        }

        // ── ۲. بستن کامل و قطعی معامله در رسیدن به حد سود اولیه (یا سقف ۳R برای معاملات با R:R اولیه ≤ ۳) با حفظ حد ضرر ثابت ──
        val reachedTp = if (isBuy) livePrice >= trade.takeProfit else livePrice <= trade.takeProfit
        // سقف ۳R فقط برای معامله‌ای است که حد ضررش دست‌نخورده مانده. وقتی حد ضرر در سود قفل
        // شده (ریسک‌فری/تریل) دیگر ریسکی وجود ندارد که سقف ۳R بخواهد آن را مهار کند.
        val hasLockedInProfit =
            trade.note.contains(BE_TAG_MARK) ||
                trade.note.contains(TRAIL_TAG_MARK) ||
                fixedStopLoss != (trade.initialStopLoss ?: fixedStopLoss)
        val reached3RCeilingForStandardTrade =
            trade.riskReward <= 3.0001 && currentRMultiple >= 3.0 && !hasLockedInProfit
        if (reachedTp || reached3RCeilingForStandardTrade) {
            val exitTargetPrice = if (reachedTp) round2(trade.takeProfit) else round2(livePrice)
            return PreserveModeEvaluation(
                tradeId = trade.id,
                newStopLoss = fixedStopLoss,
                stageTitleFa = "🎯 اتمام و بستن کامل معامله در هدف R:R اولیه (+${round2(currentRMultiple)}R · حد ضرر ثابت)",
                reasonFa = String.format(
                    Locale.US,
                    "رسیدن به هدف تعیین‌شده (%.2fR · R:R اولیه 1:%.2f) با حد ضرر کاملاً ثابت و غیرپله‌ای",
                    currentRMultiple,
                    trade.riskReward,
                ),
                lockedRMultiple = round2(currentRMultiple),
                shouldCloseImmediatelyInProfit = true,
                closePrice = exitTargetPrice,
            )
        }

        // ── ۳. ریسک‌فری در +۱R و تریل در +۲R (Preserve Mode واقعی) ───────────────
        // پیش از این، حد ضرر «تحت هیچ شرایطی» جابه‌جا نمی‌شد و هر معامله‌ای که به +۱R می‌رسید
        // تا −۱R برمی‌گشت. اکنون حد ضرر پله‌ای در سود قفل می‌شود و حد سود جابه‌جا نمی‌شود تا
        // هندسهٔ اولیهٔ R:R دست‌نخورده بماند.
        if (config.enablePreserveMode) {
            val hasMovedToBe = trade.note.contains(BE_TAG_MARK) || fixedStopLoss == trade.entry
            if (currentRMultiple >= 1.0 && !hasMovedToBe) {
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = round2(trade.entry),
                    stageTitleFa = "🛡️ ریسک‌فری در +${round2(currentRMultiple)}R (حد ضرر روی نقطهٔ ورود)",
                    reasonFa = String.format(
                        Locale.US,
                        "سود باز به %.2fR رسید؛ حد ضرر به نقطهٔ ورود (%.2f) منتقل شد تا معامله بدون ریسک ادامه یابد",
                        currentRMultiple,
                        trade.entry,
                    ),
                    lockedRMultiple = round2(currentRMultiple),
                    updatedStopLoss = round2(trade.entry),
                    stopStageTag = "BE",
                )
            }
            val trailAnchor = trigSnap.tenkan
            // در خرید: حد ضرر روی تنکان (یا ۰٫۳۵ ATR عقب‌تر از قیمت، هرکدام نزدیک‌تر) قفل می‌شود.
            val trailCandidate = if (isBuy) {
                maxOf(trigSnap.tenkan, livePrice - trigSnap.atr14 * 0.35)
            } else {
                minOf(trigSnap.tenkan, livePrice + trigSnap.atr14 * 0.35)
            }
            val trailImproves = if (isBuy) {
                trailCandidate > fixedStopLoss && trailCandidate < livePrice
            } else {
                trailCandidate < fixedStopLoss && trailCandidate > livePrice
            }
            if (currentRMultiple >= 2.0 && trailImproves) {
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = round2(trailCandidate),
                    stageTitleFa = "🔒 قفل سود در +${round2(currentRMultiple)}R روی تنکان (${round2(trailAnchor)})",
                    reasonFa = String.format(
                        Locale.US,
                        "سود باز به %.2fR رسید؛ حد ضرر روی تنکان (%.2f) با بافر %.2f ATR قفل شد",
                        currentRMultiple,
                        trailAnchor,
                        trigSnap.atr14,
                    ),
                    lockedRMultiple = round2(currentRMultiple),
                    updatedStopLoss = round2(trailCandidate),
                    stopStageTag = "TRAIL",
                )
            }
        }

        return null
    }

    /**
     * سقف زمانی نگه‌داری معاملهٔ خودکار، بر حسب کندل تایم‌فریم ورود.
     *
     * ۲۰ کندل ۵ دقیقه‌ای = ۱ ساعت و ۴۰ دقیقه؛ ۲۰ کندل ۱۵ دقیقه‌ای = ۵ ساعت. ماشه‌ای
     * (`PULLBACK_BOUNCE` و هم‌خانواده‌هایش) که در این پنجره به هدف نرسیده، دیگر «پولبک
     * در روند» نیست؛ تبدیل به معاملهٔ بلاتکلیف شده که فقط کارمزد و ریسک گپ می‌خرد.
     */
    const val DEFAULT_MAX_HOLD_BARS: Int = 20

    /** اگر معامله در پایان پنجرهٔ زمانی حتی به این مقدار R نرسیده باشد، خروج اجباری است. */
    const val MIN_R_AT_TIME_STOP: Double = 0.5

    data class TimeStopEvaluation(
        val shouldClose: Boolean,
        val barsHeld: Int,
        val currentRMultiple: Double,
        val reasonFa: String,
    )

    /**
     * ترمز پنجرهٔ زمانی (Time Stop).
     *
     * حد ضرر و حد سود فقط دو بُعد معامله را می‌بینند؛ بُعد سوم — **زمان** — هیچ‌جا سنجیده
     * نمی‌شد. نتیجه: پوزیشن‌هایی که ۲۰ کندل بی‌حرکت مانده بودند هم باز می‌ماندند و هم در
     * آمار به‌صورت «معاملهٔ زیان‌ده» ثبت می‌شدند، در حالی که بهتر بود اصلاً ادامه پیدا نکنند.
     *
     * @param livePrice آخرین قیمت معتبر؛ `null` یعنی قضاوت انجام نمی‌شود.
     */
    fun evaluateTimeStop(
        trade: PaperTrade,
        livePrice: Double?,
        now: Long = System.currentTimeMillis(),
        maxHoldBars: Int = DEFAULT_MAX_HOLD_BARS,
    ): TimeStopEvaluation {
        if (!trade.isOpen) return TimeStopEvaluation(false, 0, 0.0, "")
        val price = livePrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: return TimeStopEvaluation(false, 0, 0.0, "")
        val barMs = trade.interval.millis.coerceAtLeast(60_000L)
        val heldMs = (now - trade.openedAt).coerceAtLeast(0L)
        val barsHeld = (heldMs / barMs).toInt()
        if (barsHeld < maxHoldBars) return TimeStopEvaluation(false, barsHeld, 0.0, "")

        val fixedStop = trade.initialStopLoss ?: trade.stopLoss
        val oneR = abs(trade.entry - fixedStop).coerceAtLeast(1e-9)
        val isBuy = trade.action == SignalAction.BUY
        val currentR = ((if (isBuy) price - trade.entry else trade.entry - price) / oneR)
        val reachedTp = if (isBuy) price >= trade.takeProfit else price <= trade.takeProfit
        if (reachedTp || currentR >= MIN_R_AT_TIME_STOP) {
            return TimeStopEvaluation(false, barsHeld, round2(currentR), "")
        }
        val reason = String.format(
            Locale.US,
            "⏱️ خروج زمانی: %d کندل %s گذشت و معامله فقط %+.2fR پیشرفت کرد (کمینهٔ لازم %+.2fR) — روند مورد انتظار شکل نگرفت",
            barsHeld,
            trade.interval.label,
            currentR,
            MIN_R_AT_TIME_STOP,
        )
        return TimeStopEvaluation(true, barsHeld, round2(currentR), reason)
    }

    /** نشانهٔ متنی مرحلهٔ ریسک‌فری در یادداشت معامله. */
    const val BE_TAG_MARK: String = "🛡️ BE"

    /** نشانهٔ متنی مرحلهٔ تریل در یادداشت معامله. */
    const val TRAIL_TAG_MARK: String = "🔒 TRAIL"

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
