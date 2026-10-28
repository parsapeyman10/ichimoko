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

    // ── ۵. استراتژی‌های استخراج‌شده خط‌به‌خط از ۹ کتاب PDF گوگل درایو (Drive PDF Books 1..9) ──
    TUANH_BOLLINGER_SQUEEZE(
        id = "TUANH_BOLLINGER_SQUEEZE",
        titleFa = "کتاب درایو ۱ (TuAnh): فشردگی بولینگر (BB 20,2 Squeeze) + کومو + EMA89/MA200 + RSI(50)",
        badgeFa = "درایو ۱ · بولینگر+ایچیموکو (TuAnh)",
        authorRefFa = "TuAnh (Ichimoku & Bollinger Bands Strategy)",
        summaryFa = "شناسایی فشردگی باند بولینگر (Squeeze) و ابر نازک کومو، فیلتر روند با EMA89 و MA200، تایید RSI(14) بالای/زیر ۵۰ و ورود روی شکست کندل قوی Marubozu با ریسک‌فری ۱:۱ و تارگت ۱:۲ تا ۱:۵.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = false,
        baseOptimalRr = 2.50,
    ),
    FOKA_15MIN_EMA21_RSI7_FRACTAL(
        id = "FOKA_15MIN_EMA21_RSI7_FRACTAL",
        titleFa = "کتاب درایو ۲ (15min/Day): کومو + شیب EMA21 و کیجون + پرش RSI(7) از خط ۵۰ + استاپ فرکتال",
        badgeFa = "درایو ۲ · 15min/Day (EMA21+RSI7)",
        authorRefFa = "Marcin P. Foka / Vince Vora (15minstrategy.pdf)",
        summaryFa = "قیمت بیرون ابر کومو + شیب واضح EMA(21) و کیجون غیرفلت + اصلاح و پرش RSI(7) از مرز ۵۰ در جهت روند + حد ضرر روی آخرین فرکتال ۵ کندلی بیل ویلیامز و هدف ۳R (۱:۳).",
        hasPatel = true,
        hasTuttle = false,
        hasHosoda = false,
        hasPeloille = true,
        baseOptimalRr = 3.00,
    ),
    KEENBASE_CROSS_10_30_60(
        id = "KEENBASE_CROSS_10_30_60",
        titleFa = "کتاب درایو ۳ (Keenbase): سیستم کراس مکانیکی (10,30,60) + استاپ Kijun±0.4ATR + تارگت 3R",
        badgeFa = "درایو ۳ · کراس مکانیکی 10/30/60",
        authorRefFa = "Keenbase Trading Lab (Ichimoku Cross System)",
        summaryFa = "تنظیمات نهادی ایچیموکو (Tenkan=10, Kijun=30, Senkou B=60): کراس تنکان ۱۰ و کیجون ۳۰ با کلوز کندل سیگنال بیرون هر دو خط Senkou A و B، استاپ دقیق روی Kijun±0.4×ATR(14) و تارگت ثابت 3R.",
        hasPatel = false,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = false,
        baseOptimalRr = 3.00,
    ),
    MUKHERJEE_RELATIVE_TK_RSI_KELLEY(
        id = "MUKHERJEE_RELATIVE_TK_RSI_KELLEY",
        titleFa = "کتاب درایو ۴ (Mukherjee Adv): کراس نسبی TK/Kijun + طوفان کامل + خروج RSI و معیار کلی",
        badgeFa = "درایو ۴ · کراس نسبی + RSI و کلی",
        authorRefFa = "Indrajit Mukherjee (Advanced Ichimoku Trading Made Easy)",
        summaryFa = "کراس نسبی Tenkan/Kijun و Kijun-Sen Cross (طوفان کامل ۴-۵ سیگناله با احتمال برد ۶۹٪ تا ۷۶٪ معیار کلی)، الگوهای کندل‌استیک ژاپنی (Checkmate/Engulfing) و خروج مخفی در افت ۱۰٪ یا ضعف قله RSI.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = true,
        baseOptimalRr = 2.35,
    ),
    MUKHERJEE_CLOUDS_CHIKOU_FILTER(
        id = "MUKHERJEE_CLOUDS_CHIKOU_FILTER",
        titleFa = "کتاب درایو ۵ (Mukherjee Clouds): خط ماشه تنکان + شیب کیجون ضد رِنج + فیلتر نهایی چیکو",
        badgeFa = "درایو ۵ · فیلتر چیکو و شیب کیجون",
        authorRefFa = "Indrajit Mukherjee (Profit Making With Ichimoku Clouds)",
        summaryFa = "استفاده از Tenkan-Sen به‌عنوان Trigger Line، فیلتر آهنربای کیجون فلت برای حذف نوسانات رِنج (Whipsaw) و تایید نهایی Chikou Span (The Filter) با مقایسه قیمت ۲۶ دوره قبل.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = false,
        baseOptimalRr = 2.20,
    ),
    HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK(
        id = "HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK",
        titleFa = "کتاب درایو ۶ (HowToTrade): شکست کانال کومو و پولبک به سطوح کلیدی + کراس Senkou",
        badgeFa = "درایو ۶ · شکست-پولبک کومو (HowToTrade)",
        authorRefFa = "HowToTrade (Ichimoku Cloud Trading Strategy PDF)",
        summaryFa = "ترکیب ۲ متود HowToTrade: ۱) پولبک به سطوح کلیدی S/R منطبق بر لبه کومو در روند اصلی و ۲) شکست کامل کانال کومو (عبور از هر دو خط Senkou A و B) همراه با تست مجدد لبه کومو و تایید Kumo Twist.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = false,
        hasPeloille = true,
        baseOptimalRr = 2.40,
    ),
    TSG_QUICK_PROFIT_4STEP(
        id = "TSG_QUICK_PROFIT_4STEP",
        titleFa = "کتاب درایو ۷ (TSG Quick Profit): ۴ گام شکست و کلوز بیرون کومو + کراس TK + استاپ کندل شکست",
        badgeFa = "درایو ۷ · سود سریع ۴ گامی (TSG)",
        authorRefFa = "Trading Strategy Guides (trading strategy.pdf)",
        summaryFa = "استراتژی ۴ گامی سود سریع: ۱) شکست و کلوز کندل بیرون ابر کومو، ۲) کراس Conversion/Base با فاصله مناسب از ابر و آزادی چیکو، ۳) ورود در کندل بعدی، ۴) استاپ پشت کندل شکست و خروج با کراس معکوس TK.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = false,
        baseOptimalRr = 2.25,
    ),
    KOONITZ_5RULES_DMI_MACD_EMA34(
        id = "KOONITZ_5RULES_DMI_MACD_EMA34",
        titleFa = "کتاب درایو ۸ (Koonitz 2nd Ed): ۵ قانون طلایی ایچیموکو + عبور چیکو از کومو + DMI/ADX(24) و MACD",
        badgeFa = "درایو ۸ · ۵ قانون کونیتز (DMI/MACD)",
        authorRefFa = "Charles G. Koonitz (Ichimoku Charting & Technical Analysis)",
        summaryFa = "۵ قانون خرید/فروش کونیتز: ۱) قیمت در سمت درست کیجون صعودی/نزولی، ۲) قیمت بیرون کومو، ۳) ابر آینده هم‌رنگ، ۴) عبور چیکو از کومو در فضای بکر، ۵) تایید DMI (+DI/-DI با ADX≥24) یا MACD و EMA34.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = true,
        baseOptimalRr = 2.45,
    ),
    KOONITZ_TRIPLE_SCREEN_FAST_7_22_44(
        id = "KOONITZ_TRIPLE_SCREEN_FAST_7_22_44",
        titleFa = "کتاب درایو ۹ (Koonitz Triple Screen): صفحه سه‌گانه EMA13/34/48 + تنظیمات سریع (7,22,44)",
        badgeFa = "درایو ۹ · صفحه سه‌گانه 7-22-44 (کونیتز)",
        authorRefFa = "Charles G. Koonitz (Ichimoku Analysis & Strategies)",
        summaryFa = "تکنیک صفحه سه‌گانه (Triple Screen 1:4:16) با کراس EMA13/EMA34/EMA48 و تنظیمات سریع ۵ روزه ایچیموکو (7-22-44) برای شکار زودهنگام چرخش روندها ۱۰٪ سریع‌تر از جمعیت بازار.",
        hasPatel = true,
        hasTuttle = true,
        hasHosoda = true,
        hasPeloille = true,
        baseOptimalRr = 2.35,
    );

    val isDrivePdfStrategy: Boolean
        get() = this in setOf(
            TUANH_BOLLINGER_SQUEEZE,
            FOKA_15MIN_EMA21_RSI7_FRACTAL,
            KEENBASE_CROSS_10_30_60,
            MUKHERJEE_RELATIVE_TK_RSI_KELLEY,
            MUKHERJEE_CLOUDS_CHIKOU_FILTER,
            HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK,
            TSG_QUICK_PROFIT_4STEP,
            KOONITZ_5RULES_DMI_MACD_EMA34,
            KOONITZ_TRIPLE_SCREEN_FAST_7_22_44,
        )

    val combinationCount: Int
        get() = listOf(hasPatel, hasTuttle, hasHosoda, hasPeloille).count { it }

    val combinationCategoryFa: String
        get() = if (isDrivePdfStrategy) {
            "کتاب‌های PDF گوگل درایو (۹ استراتژی)"
        } else {
            when (combinationCount) {
                1 -> "تک‌استراتژی (۱گانه)"
                2 -> "ترکیب دوتایی (۲گانه)"
                3 -> "ترکیب سه‌تایی (۳گانه)"
                else -> "ترکیب کامل چهارگانه (۴گانه)"
            }
        }

    companion object {
        val drivePdfStrategies: List<BitcoinStrategyModel>
            get() = entries.filter { it.isDrivePdfStrategy }

        val drivePdfStrategyIds: Set<String>
            get() = drivePdfStrategies.map { it.id }.toSet()

        fun fromId(raw: String?): BitcoinStrategyModel =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: BASE_ICHIMOKU
    }
}

/**
 * شناسنامه فنی و دفترچه قوانین کامل هر یک از ۹ استراتژی کتاب‌های PDF گوگل درایو (و ۱۵ ترکیب پایه).
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
    BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Ichimoku & Bollinger Bands Strategy.pdf (TuAnh)",
        authorAndEdition = "TuAnh — ترکیبی ایچیموکو، باند بولینگر و مومنتوم RSI",
        ichimokuSettingsFa = "ایچیموکوی استاندارد (9, 26, 52) با تمرکز بر ابر کوموی نازک و کراس TK",
        auxiliaryIndicatorsFa = "Bollinger Bands (20, Dev=2) + EMA(89) + SMA(200) + RSI(14, Level=50)",
        entryChecklistStepsFa = listOf(
            "۱. تشخیص فشردگی باند بولینگر (BB Squeeze) همراه با ابر کوموی نازک یا حرکت روی لبه باند (Band Walk)",
            "۲. شکست و کلوز کامل کندل تصمیم (Marubozu / بدنه قوی) بیرون ابر کومو و در سمت درست باند میانی BB(20)",
            "۳. تایید فیلتر روند با قرارگیری بالای/زیر EMA(89) و بررسی موقعیت SMA(200)",
            "۴. عبور و تثبیت RSI(14) بالای ۵۰ در خرید و زیر ۵۰ در فروش + آزادی Chikou Span",
        ),
        stopLossFormulaFa = "پشت کندل شکست ماروبوزو + سنگر ترکیبی باند میانی BB(20) و Kijun-Sen(26) + بافر امن ATR",
        takeProfitAndExitRulesFa = "ریسک‌فری در ۱:۱ (1R)؛ اگر معامله خلاف جهت SMA(200) باز شده باشد هدف اول روی SMA(200) است، در غیر این صورت اهداف موجی ۱:۲ تا ۱:۵ (2.5R–3.5R)",
        specialEdgeAdvantageFa = "ترکیب فشردگی نوسان بولینگر با شکست کومو، فوران‌های انفجاری بازار را در همان کندل اول شکار می‌کند.",
    )

    BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "15minstrategy.pdf — My 15min per day Ichimoku Strategy",
        authorAndEdition = "Marcin P. Foka / Vince Vora (TradingTools.net Complete Guide)",
        ichimokuSettingsFa = "فقط ابر کومو (Senkou A & B) + خط Kijun-Sen (26) — خطوط Tenkan و Chikou در قالب کتاب حذف شده‌اند",
        auxiliaryIndicatorsFa = "EMA(21) جهت شیب روند + RSI(7) با خط مرکزی 50 + Bill Williams 5-Bar Fractals",
        entryChecklistStepsFa = listOf(
            "۱. کلوز کامل قیمت و EMA(21) در بالای ابر کومو (خرید) یا زیر ابر کومو (فروش)",
            "۲. شیب واضح EMA(21) و غیرفلت بودن Kijun-Sen(26) در جهت روند اصلی",
            "۳. صبر برای پولبک موقت درون‌روزی که در آن RSI(7) به سمت خط ۵۰ اصلاح کرده و مجدداً از ۵۰ در جهت روند پرش کند",
            "۴. ثبت کندل تایید در نزدیکی EMA(21)/Kijun(26) با وجود فرکتال ۵ کندلی معتبر بیل ویلیامز",
        ),
        stopLossFormulaFa = "پشت آخرین فرکتال ۵ کندلی بیل ویلیامز (Down Fractal در خرید / Up Fractal در فروش) + پشت Kijun(26)",
        takeProfitAndExitRulesFa = "ریسک‌فری خودکار در ۱:۱ (1R) و هدف سود قطعی روی ۱:۳ (3.0R = ۳ برابر فاصله استاپ فرکتال)",
        specialEdgeAdvantageFa = "حذف شلوغی چارت (حذف تنکان و چیکو) و ورود دقیق در پایان پولبک با پرش RSI(7) از خط ۵۰ و استاپ فرکتال.",
    )

    BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Ichimoku Kinko Hyo Strategy.pdf (Keenbase Cross System)",
        authorAndEdition = "Keenbase Trading Lab — سیستم مکانیکی کراس نهادی",
        ichimokuSettingsFa = "تنظیمات نهادی مدرن (Tenkan = 10, Kijun = 30, Senkou Span B = 60, Displacement = 30)",
        auxiliaryIndicatorsFa = "ATR(14) جهت محاسبه مکانیکی فاصله حد ضرر (0.40 × ATR14)",
        entryChecklistStepsFa = listOf(
            "۱. محاسبه خطوط ایچیموکو با پریودهای نهادی (10, 30, 60) به‌جای (9, 26, 52)",
            "۲. کراس و چیدمان صعودی/نزولی Tenkan(10) و Kijun(30) با شیب فعال",
            "۳. کلوز کامل کندل سیگنال بیرون هر دو خط Senkou Span A(10/30) و Senkou Span B(60)",
            "۴. تایید آزادی Chikou و عدم فاصله بیش از حد از Kijun(30)",
        ),
        stopLossFormulaFa = "فرمول مکانیکی کتاب: Kijun(30) − 0.40 × ATR(14) در خرید و Kijun(30) + 0.40 × ATR(14) در فروش",
        takeProfitAndExitRulesFa = "حد سود مکانیکی ثابت ۱:۳ (3.0R) همراه با تریلینگ روی Kijun(30) پس از عبور از 2R",
        specialEdgeAdvantageFa = "تنظیمات (10, 30, 60) با بازارهای ۲۴ ساعته مدرن هماهنگ‌تر است و حدود ۳۰٪ سیگنال‌های کاذب کراس ۹/۲۶ را فیلتر می‌کند.",
    )

    BitcoinStrategyModel.MUKHERJEE_RELATIVE_TK_RSI_KELLEY -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Advanced Ichimoku Trading Made Easy.pdf",
        authorAndEdition = "Indrajit Mukherjee — کراس نسبی، طوفان کامل ۵ سیگناله و فرمول کلی (Kelly)",
        ichimokuSettingsFa = "ایچیموکوی ۵ سیگناله (TK Cross, Kijun Cross, Kumo Breakout, Senkou Cross, Chikou Cross)",
        auxiliaryIndicatorsFa = "Kelly Criterion Win-Rate Counter (62%..76%) + Japanese Candlesticks (Checkmate/Engulfing) + RSI(14)",
        entryChecklistStepsFa = listOf(
            "۱. ارزیابی قدرت نسبی کراس TK نسبت به ابر کومو (کراس بالای ابر در خرید / زیر ابر در فروش)",
            "۲. شمارش ۵ سیگنال هم‌زمان ایچیموکو (طوفان کامل: حداقل ۳ تا ۵ سیگنال با احتمال برد ۶۲٪ تا ۷۶٪ طبق فرمول کلی)",
            "۳. تایید الگوی شمعی ژاپنی (Engulfing / Checkmate / PinBar) روی خط Kijun-Sen یا Tenkan-Sen",
            "۴. تایید مومنتوم RSI(14) بالای/زیر ۵۰ بدون افت ۱۰ واحدی از قله اخیر",
        ),
        stopLossFormulaFa = "پشت الگوی کندل‌استیک ژاپنی و خط تعادل Kijun-Sen(26) + بافر ATR",
        takeProfitAndExitRulesFa = "اهداف موجی هوسودا (حداقل 2.35R) + تکنیک خروج مخفی موکرجی در صورت افت ۱۰ تا ۱۲ واحدی RSI(14) از قله و کلوز پشت تنکان",
        specialEdgeAdvantageFa = "تلفیق ریاضیات معیار کلی (Kelly Criterion) با الگوهای کندل‌استیک ژاپنی و خروج هوشمند قبل از برگشت کامل روند.",
    )

    BitcoinStrategyModel.MUKHERJEE_CLOUDS_CHIKOU_FILTER -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Profit Making With Ichimoku Clouds.pdf",
        authorAndEdition = "Indrajit Mukherjee — خط ماشه تنکان، شیب کیجون ضد رِنج و فیلتر نهایی چیکو",
        ichimokuSettingsFa = "ایچیموکوی (9, 26, 52) با تفکیک ۳ نقش: Tenkan (Trigger)، Kijun (Anti-Range) و Chikou (Final Filter)",
        auxiliaryIndicatorsFa = "شیب نرمال‌شده Kijun-Sen به ATR + بررسی سه‌بعدی Chikou نسبت به قیمت و ابر ۲۶ دوره قبل",
        entryChecklistStepsFa = listOf(
            "۱. استقرار قیمت بیرون ابر کومو در هر دو تایم‌فریم استراتژی و ورود",
            "۲. فیلتر ضد رِنج (Anti-Whipsaw): خط Kijun-Sen نباید فلت باشد و باید شیب هم‌جهت داشته باشد",
            "۳. ماشه ورود روی خط Tenkan-Sen (کلوز کندل بالای/زیر تنکان در پایان اصلاح)",
            "۴. فیلتر نهایی Chikou Span: عبور کامل چیکو از قیمت و ابر کوموی ۲۶ دوره قبل",
        ),
        stopLossFormulaFa = "پشت خط Kijun-Sen(26) و لبه ابر کومو + بافر امن ساختار",
        takeProfitAndExitRulesFa = "هدف سود موجی حداقل ۱:۲.۲۰ (2.20R) و قفل سود روی خط ماشه Tenkan-Sen در حرکت‌های شتاب‌دار",
        specialEdgeAdvantageFa = "حذف کامل معاملات فرسایشی بازار رِنج با قانون آهنربای کیجون فلت و تایید قطعی فیلتر نهایی چیکو.",
    )

    BitcoinStrategyModel.HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Ichimoku-Trading-Strategy-PDF.pdf (HowToTrade)",
        authorAndEdition = "HowToTrade Academy — دو استراتژی پولبک به سطح کلیدی و شکست کانال کومو",
        ichimokuSettingsFa = "ایچیموکوی (9, 26, 52) + استخراج سطوح کلیدی S/R از فلت‌های Senkou Span B و Kijun",
        auxiliaryIndicatorsFa = "Key Horizontal S/R Levels + Full Kumo Channel (Span A & B) + Future Kumo Twist",
        entryChecklistStepsFa = listOf(
            "۱. ستاپ اول (پولبک): هم‌پوشانی لبه ابر کومو و کیجون با سطح کلیدی افقی S/R و واکنش برگشتی قیمت",
            "۲. ستاپ دوم (شکست کانال): عبور و کلوز کامل قیمت از هر دو خط کانال کومو (Senkou A و B) همراه با ری‌تست لبه ابر",
            "۳. تایید پیچش ابر آینده (Future Kumo Twist: Senkou A crossing Senkou B) در جهت معامله",
            "۴. هم‌جهتی Tenkan/Kijun و آزادی Chikou Span",
        ),
        stopLossFormulaFa = "پشت سطح کلیدی S/R و لبه مخالف کانال کومو (Senkou Span B) + بافر ATR",
        takeProfitAndExitRulesFa = "نسبت R:R حداقل ۱:۲.۴۰ (2.40R) روی هدف موج N/E هوسودا قبل از دیوار فلت تایم بالاتر",
        specialEdgeAdvantageFa = "هم‌افزایی پرایس‌اکشن کلاسیک (سطوح کلیدی حمایت و مقاومت) با هندسه کانال کومو.",
    )

    BitcoinStrategyModel.TSG_QUICK_PROFIT_4STEP -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "trading strategy.pdf — Quick Profit Ichimoku Strategy",
        authorAndEdition = "Trading Strategy Guides (TSG Research Team)",
        ichimokuSettingsFa = "ایچیموکوی ۴ گامی (Conversion Line 9, Base Line 26, Cloud 52, Chikou 26)",
        auxiliaryIndicatorsFa = "فیلتر فاصله کراس TK از ابر کومو + موقعیت Chikou نسبت به ابر ۲۶ کندل قبل",
        entryChecklistStepsFa = listOf(
            "۱. گام اول: شکست و کلوز قطعی کندل قیمت بیرون ابر کومو",
            "۲. گام دوم: کراس خط Conversion (تنکان) و Base (کیجون) در جهت شکست با فاصله استاندارد از ابر",
            "۳. تایید مکمل گام دوم: قرارگیری خط Chikou Span بیرون ابر کوموی ۲۶ دوره قبل",
            "۴. گام سوم: ورود در کندل تایید پس از کراس یا پولبک به خط Conversion/Base",
        ),
        stopLossFormulaFa = "گام چهارم TSG: پشت کف/سقف کندل شکست کومو و خط Base Line (کیجون)",
        takeProfitAndExitRulesFa = "هدف سود حداقل ۱:۲.۲۵ (2.25R) و خروج تضمینی در سود به محض کراس معکوس Conversion و Base",
        specialEdgeAdvantageFa = "فرآیند ۴ گامی کاملاً مکانیکی با فیلتر قرارگیری چیکو بیرون ابر ۲۶ دوره قبل برای سودهای سریع و کم‌ریسک.",
    )

    BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34 -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Ichimoku Charting and Technical Analysis (2nd Ed).pdf",
        authorAndEdition = "Charles G. Koonitz — ۵ قانون طلایی ایچیموکو + همگرایی اندیکاتورهای غربی",
        ichimokuSettingsFa = "ایچیموکوی (9, 26, 52) با قانون عبور چیکو از درون ابر کومو و ۴ قانون خروج کونیتز",
        auxiliaryIndicatorsFa = "Welles Wilder DMI/ADX(14) (+DI/-DI, ADX≥20) + MACD(12,26,9) + EMA(34) + RSI(14)",
        entryChecklistStepsFa = listOf(
            "۱. قانون ۱ و ۲ کونیتز: قیمت در سمت درست خط Base (کیجون) دارای شیب موافق و کاملاً بیرون ابر کومو",
            "۲. قانون ۳ و ۴ کونیتز: ابر آینده ۲۶ دوره جلوتر هم‌رنگ روند + عبور Chikou Span از ابر ۲۶ دوره قبل به فضای باز",
            "۳. قانون ۵ الف کونیتز: تایید DMI (+DI بالای -DI در خرید / برعکس در فروش) با قدرت روند ADX(14) ≥ 20",
            "۴. قانون ۵ ب و ج کونیتز: تایید هیستوگرام/خط MACD(12,26,9) و قرارگیری قیمت در سمت درست EMA(34)",
        ),
        stopLossFormulaFa = "سنگر دوگانه کونیتز: پشت هر دو خط Base Line (کیجون ۲۶) و میانگین نمایی EMA(34) + بافر ATR",
        takeProfitAndExitRulesFa = "هدف سود ۱:۲.۴۵ (2.45R) + سیستم ۴ قانون خروج کونیتز (شکست هم‌زمان کیجون و EMA34 با برگشت MACD/DMI)",
        specialEdgeAdvantageFa = "ترکیب ۵ قانون اصیل ایچیموکو با فیلتر قدرت روند ADX/DMI و EMA(34) که ورود در بازارهای بی‌رمق را به صفر می‌رساند.",
    )

    BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 -> DriveBookPlaybookSpec(
        strategyId = id,
        bookFileTitle = "Ichimoku Analysis and Strategies.pdf",
        authorAndEdition = "Charles G. Koonitz — تکنیک صفحه سه‌گانه (Triple Screen) و تنظیمات سریع (7, 22, 44)",
        ichimokuSettingsFa = "ایچیموکوی سریع ۵ روز کاری (Tenkan = 7, Kijun = 22, Senkou Span B = 44, Displacement = 22)",
        auxiliaryIndicatorsFa = "EMA Ribbon (EMA 13, EMA 34, EMA 48) در نسبت تایم‌فریم 1:4:16 + MACD / DMI",
        entryChecklistStepsFa = listOf(
            "۱. صفحه اول (تایم کلان): هم‌جهتی کومو و آرایش ریبون EMA(13 > 34 > 48 در خرید / معکوس در فروش)",
            "۲. صفحه دوم (تایم استراتژی): استقرار قیمت بیرون ابر سریع (7, 22, 44) و تایید کراس EMA(13/34)",
            "۳. صفحه سوم (تایم ورود): کراس و برتری سریع Tenkan(7) و Kijun(22) بالای/زیر کوموی ۴۴ دوره‌ای",
            "۴. تایید مومنتوم صفحه سوم با MACD/DMI و آزادی Chikou برای ورود ۱۰٪ تا ۲۰٪ سریع‌تر از جمعیت بازار",
        ),
        stopLossFormulaFa = "پشت خط Kijun(22) سریع و میانگین نمایی EMA(34) + بافر امن ATR",
        takeProfitAndExitRulesFa = "هدف سود ۱:۲.۳۵ (2.35R) تا ۱:۳.۲۰ با قفل سود پلکانی روی Tenkan(7) و EMA(13)",
        specialEdgeAdvantageFa = "پریودهای سریع (7, 22, 44) همراه با ریبون EMA(13/34/48) چرخش روندها را قبل از معامله‌گران کلاسیک شکار می‌کند.",
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
        takeProfitAndExitRulesFa = "اهداف موجی V/N/E هوسودا با حداقل R:R ≥ 1:1.5 و شکارچی روند بالای 1:3",
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
        BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE -> String.format(
            Locale.US,
            "BB(20,2): [%s .. %s .. %s] (فشردگی=%s) · EMA89=%s · SMA200=%s · RSI(14)=%.1f · کندل ماروبوزو=%s",
            IchimokuStrategyEngine.round2(trigSnap.bbLower20).toString(),
            IchimokuStrategyEngine.round2(trigSnap.bbMiddle20).toString(),
            IchimokuStrategyEngine.round2(trigSnap.bbUpper20).toString(),
            if (trigSnap.isBollingerSqueeze) "بله ✅" else "عادی",
            IchimokuStrategyEngine.round2(trigSnap.ema89).toString(),
            IchimokuStrategyEngine.round2(trigSnap.sma200).toString(),
            trigSnap.rsi14,
            if ((isLong && trigSnap.isMarubozuBreakoutBull) || (!isLong && trigSnap.isMarubozuBreakoutBear)) "تایید ✅" else "در انتظار",
        )

        BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL -> String.format(
            Locale.US,
            "EMA(21)=%s (شیب=%+.2f ATR) · Kijun(26)=%s · RSI(7)=%.1f (پرش ۵۰=%s) · استاپ فرکتال ۵ کندلی=%s",
            IchimokuStrategyEngine.round2(trigSnap.ema21).toString(),
            trigSnap.ema21SlopeAtrPerBar,
            IchimokuStrategyEngine.round2(trigSnap.kijun).toString(),
            trigSnap.rsi7,
            if ((isLong && trigSnap.rsi7Bullish50Bounce) || (!isLong && trigSnap.rsi7Bearish50Bounce)) "تایید ✅" else "در انتظار",
            IchimokuStrategyEngine.round2(if (isLong) trigSnap.lastDownFractalLow else trigSnap.lastUpFractalHigh).toString(),
        )

        BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 -> String.format(
            Locale.US,
            "Tenkan(10)=%s · Kijun(30)=%s · Kumo(60)=[%s .. %s] · استاپ مکانیکی (Kijun30±0.4ATR)=%s",
            IchimokuStrategyEngine.round2(trigSnap.tenkan10).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kijun30).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kumoBottom103060).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kumoTop103060).toString(),
            IchimokuStrategyEngine.round2(
                if (isLong) trigSnap.kijun30 - trigSnap.atr14 * 0.40 else trigSnap.kijun30 + trigSnap.atr14 * 0.40
            ).toString(),
        )

        BitcoinStrategyModel.MUKHERJEE_RELATIVE_TK_RSI_KELLEY -> String.format(
            Locale.US,
            "طوفان کامل موکرجی: %d/5 سیگنال (احتمال برد کلی=%d%%) · الگوی شمعی=%s · RSI(14)=%.1f (افت از قله=%.1f)",
            trigSnap.kellySignalCount,
            trigSnap.kellyWinProbabilityPercent,
            if ((isLong && trigSnap.hasBullishCandlestickPattern) || (!isLong && trigSnap.hasBearishCandlestickPattern)) "تایید ✅" else "خیر",
            trigSnap.rsi14,
            if (isLong) trigSnap.rsiPeakDropLong else trigSnap.rsiTroughRiseShort,
        )

        BitcoinStrategyModel.MUKHERJEE_CLOUDS_CHIKOU_FILTER -> String.format(
            Locale.US,
            "خط ماشه Tenkan=%s · شیب Kijun=%+.2f ATR (%s) · فیلتر نهایی Chikou=%s (بالای/زیر ابر ۲۶=%s)",
            IchimokuStrategyEngine.round2(trigSnap.tenkan).toString(),
            trigSnap.kijunSlopeAtrPerBar,
            if (trigSnap.isKijunFlat) "فلت/رِنج ⚠️" else "رونددار ✅",
            if ((isLong && trigSnap.chikouBullishOpenSpace) || (!isLong && trigSnap.chikouBearishOpenSpace)) "آزاد ✅" else "درگیر",
            if ((isLong && trigSnap.chikouAboveCloud26) || (!isLong && trigSnap.chikouBelowCloud26)) "بله ✅" else "خیر",
        )

        BitcoinStrategyModel.HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK -> String.format(
            Locale.US,
            "کانال کومو=[%s .. %s] · سطوح کلیدی S/R فلت=%d سطح · ابر آینده=%s · کیجون=%s",
            IchimokuStrategyEngine.round2(trigSnap.kumoBottom).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kumoTop).toString(),
            ((stratSnap?.flatEquilibriumLevels?.size ?: 0) + trigSnap.flatEquilibriumLevels.size),
            if (trigSnap.isFutureKumoBullish) "صعودی (Twist+)" else "نزولی (Twist-)",
            IchimokuStrategyEngine.round2(trigSnap.kijun).toString(),
        )

        BitcoinStrategyModel.TSG_QUICK_PROFIT_4STEP -> String.format(
            Locale.US,
            "گام ۱ (کلوز بیرون کومو)=%s · گام ۲ (کراس TK)=%s · چیکو بیرون ابر ۲۶=%s · فاصله TK از کومو=%.2f ATR",
            if ((isLong && trigSnap.isPriceAboveKumo) || (!isLong && trigSnap.isPriceBelowKumo)) "تایید ✅" else "خیر",
            if ((isLong && trigSnap.tenkan >= trigSnap.kijun) || (!isLong && trigSnap.tenkan <= trigSnap.kijun)) "تایید ✅" else "خیر",
            if ((isLong && trigSnap.chikouAboveCloud26) || (!isLong && trigSnap.chikouBelowCloud26)) "تایید ✅" else "خیر",
            abs(trigSnap.tenkan - (if (isLong) trigSnap.kumoTop else trigSnap.kumoBottom)) / trigSnap.atr14.coerceAtLeast(1e-8),
        )

        BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34 -> String.format(
            Locale.US,
            "ADX(14)=%.1f (+DI=%.1f / -DI=%.1f) · MACD Hist=%+.3f · EMA(34)=%s · Kijun=%s · چیکو بیرون کومو=%s",
            trigSnap.adx14,
            trigSnap.plusDi14,
            trigSnap.minusDi14,
            trigSnap.macdHistogram,
            IchimokuStrategyEngine.round2(trigSnap.ema34).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kijun).toString(),
            if ((isLong && trigSnap.chikouAboveCloud26) || (!isLong && trigSnap.chikouBelowCloud26)) "بله ✅" else "خیر",
        )

        BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 -> String.format(
            Locale.US,
            "ایچیموکوی سریع: Tenkan(7)=%s · Kijun(22)=%s · Kumo(44)=[%s..%s] · ریبون EMA(13/34/48)=%s / %s / %s",
            IchimokuStrategyEngine.round2(trigSnap.tenkan7).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kijun22).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kumoBottom72244).toString(),
            IchimokuStrategyEngine.round2(trigSnap.kumoTop72244).toString(),
            IchimokuStrategyEngine.round2(trigSnap.ema13).toString(),
            IchimokuStrategyEngine.round2(trigSnap.ema34).toString(),
            IchimokuStrategyEngine.round2(trigSnap.ema48).toString(),
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
) {
    val macroInterval: Interval get() = Interval.fromLabel(macroIntervalLabel)
    val strategyInterval: Interval get() = Interval.fromLabel(strategyIntervalLabel)
    val triggerInterval: Interval get() = Interval.fromLabel(triggerIntervalLabel)
}

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
        val bullishBias = when (activeModel) {
            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 ->
                stratSnap.close > stratSnap.kumoTop103060 ||
                    (trigSnap.close > trigSnap.kumoTop103060 && trigSnap.tenkan10 >= trigSnap.kijun30)
            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 ->
                stratSnap.close > stratSnap.kumoTop72244 ||
                    (trigSnap.close > trigSnap.kumoTop72244 && trigSnap.tenkan7 >= trigSnap.kijun22 && trigSnap.ema13 >= trigSnap.ema34)
            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL ->
                (stratSnap.isPriceAboveKumo && trigSnap.isPriceAboveKumo) ||
                    (trigSnap.isPriceAboveKumo && trigSnap.close >= trigSnap.ema21 && trigSnap.ema21SlopeAtrPerBar >= 0.0)
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE ->
                (stratSnap.isPriceAboveKumo && trigSnap.close >= trigSnap.bbMiddle20) ||
                    (trigSnap.isPriceAboveKumo && trigSnap.close >= trigSnap.ema89 && trigSnap.rsi14 >= 50.0)
            else ->
                stratSnap.isPriceAboveKumo ||
                    (macroSnap?.isPriceAboveKumo == true && trigSnap.isPriceAboveKumo)
        }
        val bearishBias = when (activeModel) {
            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 ->
                stratSnap.close < stratSnap.kumoBottom103060 ||
                    (trigSnap.close < trigSnap.kumoBottom103060 && trigSnap.tenkan10 <= trigSnap.kijun30)
            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 ->
                stratSnap.close < stratSnap.kumoBottom72244 ||
                    (trigSnap.close < trigSnap.kumoBottom72244 && trigSnap.tenkan7 <= trigSnap.kijun22 && trigSnap.ema13 <= trigSnap.ema34)
            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL ->
                (stratSnap.isPriceBelowKumo && trigSnap.isPriceBelowKumo) ||
                    (trigSnap.isPriceBelowKumo && trigSnap.close <= trigSnap.ema21 && trigSnap.ema21SlopeAtrPerBar <= 0.0)
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE ->
                (stratSnap.isPriceBelowKumo && trigSnap.close <= trigSnap.bbMiddle20) ||
                    (trigSnap.isPriceBelowKumo && trigSnap.close <= trigSnap.ema89 && trigSnap.rsi14 <= 50.0)
            else ->
                stratSnap.isPriceBelowKumo ||
                    (macroSnap?.isPriceBelowKumo == true && trigSnap.isPriceBelowKumo)
        }

        val candidateDirection = when {
            bullishBias && !bearishBias -> SignalAction.BUY
            bearishBias && !bullishBias -> SignalAction.SELL
            activeModel == BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 ->
                if (trigSnap.tenkan10 >= trigSnap.kijun30) SignalAction.BUY else SignalAction.SELL
            activeModel == BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 ->
                if (trigSnap.tenkan7 >= trigSnap.kijun22) SignalAction.BUY else SignalAction.SELL
            activeModel == BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL ->
                if (trigSnap.close >= trigSnap.ema21) SignalAction.BUY else SignalAction.SELL
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
            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 -> trigSnap.tenkan10
            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 -> trigSnap.tenkan7
            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL -> trigSnap.ema21
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
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE -> {
                if (isLong) {
                    val tuAnhFloor = min(min(trigSnap.low, trigSnap.bbMiddle20), trigSnap.kijun) - slBuffer
                    round2(min(tuAnhFloor, safeAnchorLong - minSafeStopDist))
                } else {
                    val tuAnhCeiling = max(max(trigSnap.high, trigSnap.bbMiddle20), trigSnap.kijun) + slBuffer
                    round2(max(tuAnhCeiling, safeAnchorShort + minSafeStopDist))
                }
            }
            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL -> {
                if (isLong) {
                    val fractalFloor = min(trigSnap.lastDownFractalLow, min(trigSnap.low, trigSnap.kijun)) - trigSnap.atr14 * 0.18
                    round2(min(fractalFloor, safeAnchorLong - minSafeStopDist))
                } else {
                    val fractalCeiling = max(trigSnap.lastUpFractalHigh, max(trigSnap.high, trigSnap.kijun)) + trigSnap.atr14 * 0.18
                    round2(max(fractalCeiling, safeAnchorShort + minSafeStopDist))
                }
            }
            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 -> {
                if (isLong) {
                    val keenbaseStop = trigSnap.kijun30 - trigSnap.atr14 * 0.40
                    round2(min(keenbaseStop, safeAnchorLong - minSafeStopDist))
                } else {
                    val keenbaseStop = trigSnap.kijun30 + trigSnap.atr14 * 0.40
                    round2(max(keenbaseStop, safeAnchorShort + minSafeStopDist))
                }
            }
            BitcoinStrategyModel.MUKHERJEE_RELATIVE_TK_RSI_KELLEY,
            BitcoinStrategyModel.MUKHERJEE_CLOUDS_CHIKOU_FILTER -> {
                if (isLong) {
                    val mukherjeeFloor = min(trigSnap.kijun, trigSnap.low) - slBuffer
                    round2(min(mukherjeeFloor, safeAnchorLong - minSafeStopDist))
                } else {
                    val mukherjeeCeiling = max(trigSnap.kijun, trigSnap.high) + slBuffer
                    round2(max(mukherjeeCeiling, safeAnchorShort + minSafeStopDist))
                }
            }
            BitcoinStrategyModel.HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK -> {
                if (isLong) {
                    val httFloor = min(trigSnap.kumoBottom, trigSnap.kijun) - slBuffer
                    round2(min(httFloor, safeAnchorLong - minSafeStopDist))
                } else {
                    val httCeiling = max(trigSnap.kumoTop, trigSnap.kijun) + slBuffer
                    round2(max(httCeiling, safeAnchorShort + minSafeStopDist))
                }
            }
            BitcoinStrategyModel.TSG_QUICK_PROFIT_4STEP -> {
                if (isLong) {
                    val tsgFloor = min(trigSnap.low, trigSnap.kijun) - slBuffer
                    round2(min(tsgFloor, safeAnchorLong - minSafeStopDist))
                } else {
                    val tsgCeiling = max(trigSnap.high, trigSnap.kijun) + slBuffer
                    round2(max(tsgCeiling, safeAnchorShort + minSafeStopDist))
                }
            }
            BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34 -> {
                if (isLong) {
                    val koonitzFloor = min(min(trigSnap.kijun, trigSnap.ema34), trigSnap.low) - slBuffer
                    round2(min(koonitzFloor, safeAnchorLong - minSafeStopDist))
                } else {
                    val koonitzCeiling = max(max(trigSnap.kijun, trigSnap.ema34), trigSnap.high) + slBuffer
                    round2(max(koonitzCeiling, safeAnchorShort + minSafeStopDist))
                }
            }
            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 -> {
                if (isLong) {
                    val koonitzFastFloor = min(min(trigSnap.kijun22, trigSnap.ema34), trigSnap.low) - slBuffer
                    round2(min(koonitzFastFloor, safeAnchorLong - minSafeStopDist))
                } else {
                    val koonitzFastCeiling = max(max(trigSnap.kijun22, trigSnap.ema34), trigSnap.high) + slBuffer
                    round2(max(koonitzFastCeiling, safeAnchorShort + minSafeStopDist))
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
        val volumeMomentumOk = trigSnap.volumeRatio20 >= 0.60
        val driveBookRuleOk = when (activeModel) {
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE ->
                trigSnap.isBollingerSqueeze && if (isLong) {
                    trigSnap.close >= trigSnap.ema89 && trigSnap.rsi14 >= 50.0 && trigSnap.isMarubozuBreakoutBull
                } else {
                    trigSnap.close <= trigSnap.ema89 && trigSnap.rsi14 <= 50.0 && trigSnap.isMarubozuBreakoutBear
                }
            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL ->
                if (isLong) {
                    trigSnap.close > trigSnap.kumoTop && trigSnap.ema21SlopeAtrPerBar > -0.01 && trigSnap.rsi7Bullish50Bounce
                } else {
                    trigSnap.close < trigSnap.kumoBottom && trigSnap.ema21SlopeAtrPerBar < 0.01 && trigSnap.rsi7Bearish50Bounce
                }
            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 ->
                if (isLong) {
                    trigSnap.tenkan10 >= trigSnap.kijun30 && trigSnap.close > trigSnap.kumoTop103060
                } else {
                    trigSnap.tenkan10 <= trigSnap.kijun30 && trigSnap.close < trigSnap.kumoBottom103060
                }
            BitcoinStrategyModel.MUKHERJEE_RELATIVE_TK_RSI_KELLEY ->
                trigSnap.kellySignalCount >= 3 && if (isLong) {
                    trigSnap.tenkan >= trigSnap.kijun && trigSnap.rsi14 >= 50.0 && trigSnap.hasBullishCandlestickPattern
                } else {
                    trigSnap.tenkan <= trigSnap.kijun && trigSnap.rsi14 <= 50.0 && trigSnap.hasBearishCandlestickPattern
                }
            BitcoinStrategyModel.MUKHERJEE_CLOUDS_CHIKOU_FILTER ->
                !trigSnap.isKijunFlat && if (isLong) {
                    trigSnap.chikouBullishOpenSpace && trigSnap.kijunSlopeAtrPerBar >= -0.01
                } else {
                    trigSnap.chikouBearishOpenSpace && trigSnap.kijunSlopeAtrPerBar <= 0.01
                }
            BitcoinStrategyModel.HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK ->
                if (isLong) {
                    trigSnap.close > trigSnap.kumoTop && trigSnap.isFutureKumoBullish
                } else {
                    trigSnap.close < trigSnap.kumoBottom && trigSnap.isFutureKumoBearish
                }
            BitcoinStrategyModel.TSG_QUICK_PROFIT_4STEP ->
                if (isLong) {
                    trigSnap.close > trigSnap.kumoTop && trigSnap.tenkan >= trigSnap.kijun && trigSnap.chikouAboveCloud26
                } else {
                    trigSnap.close < trigSnap.kumoBottom && trigSnap.tenkan <= trigSnap.kijun && trigSnap.chikouBelowCloud26
                }
            BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34 ->
                if (isLong) {
                    trigSnap.close > trigSnap.kijun && trigSnap.close > trigSnap.kumoTop &&
                        trigSnap.chikouAboveCloud26 && (trigSnap.isDmiBullish || trigSnap.isMacdBullish) &&
                        trigSnap.close >= trigSnap.ema34
                } else {
                    trigSnap.close < trigSnap.kijun && trigSnap.close < trigSnap.kumoBottom &&
                        trigSnap.chikouBelowCloud26 && (trigSnap.isDmiBearish || trigSnap.isMacdBearish) &&
                        trigSnap.close <= trigSnap.ema34
                }
            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 ->
                if (isLong) {
                    trigSnap.tenkan7 >= trigSnap.kijun22 && trigSnap.close > trigSnap.kumoTop72244 &&
                        trigSnap.ema13 >= trigSnap.ema34 && stratSnap.ema13 >= stratSnap.ema34
                } else {
                    trigSnap.tenkan7 <= trigSnap.kijun22 && trigSnap.close < trigSnap.kumoBottom72244 &&
                        trigSnap.ema13 <= trigSnap.ema34 && stratSnap.ema13 <= stratSnap.ema34
                }
            else -> true
        }
        val cond11Passed = directionalCandleOk && volumeMomentumOk && driveBookRuleOk
        val driveBookExtraDetail = when (activeModel) {
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE ->
                String.format(Locale.US, " · BB Squeeze=%s · EMA89=%s · RSI14=%.1f", if (trigSnap.isBollingerSqueeze) "OK" else "No", trigSnap.ema89.toString(), trigSnap.rsi14)
            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL ->
                String.format(Locale.US, " · EMA21=%s · RSI7=%.1f · FractalSL=%s", trigSnap.ema21.toString(), trigSnap.rsi7, (if (isLong) trigSnap.lastDownFractalLow else trigSnap.lastUpFractalHigh).toString())
            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 ->
                String.format(Locale.US, " · TK(10/30)=%s/%s · Kumo60=%s", trigSnap.tenkan10.toString(), trigSnap.kijun30.toString(), (if (isLong) trigSnap.kumoTop103060 else trigSnap.kumoBottom103060).toString())
            BitcoinStrategyModel.MUKHERJEE_RELATIVE_TK_RSI_KELLEY ->
                String.format(Locale.US, " · Kelly=%d سیگنال (%d%%) · RSI14=%.1f", trigSnap.kellySignalCount, trigSnap.kellyWinProbabilityPercent, trigSnap.rsi14)
            BitcoinStrategyModel.MUKHERJEE_CLOUDS_CHIKOU_FILTER ->
                String.format(Locale.US, " · شیب کیجون=%+.2f ATR · فیلتر چیکو=%s", trigSnap.kijunSlopeAtrPerBar, if (trigSnap.chikouBullishOpenSpace || trigSnap.chikouBearishOpenSpace) "آزاد" else "مسدود")
            BitcoinStrategyModel.HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK ->
                String.format(Locale.US, " · S/R Kumo=%s · ابر آینده=%s", (if (isLong) trigSnap.kumoTop else trigSnap.kumoBottom).toString(), if (trigSnap.isFutureKumoBullish) "صعودی" else "نزولی")
            BitcoinStrategyModel.TSG_QUICK_PROFIT_4STEP ->
                String.format(Locale.US, " · گام ۱-۴ TSG · چیکو بالای کومو=%s", if (trigSnap.chikouAboveCloud26 || trigSnap.chikouBelowCloud26) "تایید" else "خیر")
            BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34 ->
                String.format(Locale.US, " · ADX14=%.1f (+DI=%.0f/-DI=%.0f) · MACD=%+.2f · EMA34=%s", trigSnap.adx14, trigSnap.plusDi14, trigSnap.minusDi14, trigSnap.macdHistogram, trigSnap.ema34.toString())
            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 ->
                String.format(Locale.US, " · Fast(7/22/44)=%s/%s · EMA(13/34/48)=%s/%s/%s", trigSnap.tenkan7.toString(), trigSnap.kijun22.toString(), trigSnap.ema13.toString(), trigSnap.ema34.toString(), trigSnap.ema48.toString())
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
            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 -> {
                val fixed3RPrice = if (isLong) {
                    max(livePrice, sniperOptimal) + stopDist * max(3.0, computedOptimalRr)
                } else {
                    (min(livePrice, sniperOptimal) - stopDist * max(3.0, computedOptimalRr)).coerceAtLeast(0.00001)
                }
                "هدف ۳R سیستم Keenbase (10-30-60)" to fixed3RPrice
            }
            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL -> {
                val fixed3RPrice = if (isLong) {
                    max(livePrice, sniperOptimal) + stopDist * max(3.0, computedOptimalRr)
                } else {
                    (min(livePrice, sniperOptimal) - stopDist * max(3.0, computedOptimalRr)).coerceAtLeast(0.00001)
                }
                "هدف ۳R سیستم ۱۵ دقیقه‌ای فکا (Fractal/RSI7)" to fixed3RPrice
            }
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE -> {
                // قانون کتاب TuAnh: اگر سیگنال خرید زیر MA200 است و MA200 در فاصله حداقل 1.5R بالای ورود قرار دارد، MA200 هدف اول است
                val ma200Dist = abs(trigSnap.sma200 - sniperOptimal)
                val ma200MilestoneValid = (isLong && trigSnap.sma200 > max(livePrice, sniperOptimal) && ma200Dist / stopDist >= requiredMinRr) ||
                    (!isLong && trigSnap.sma200 < min(livePrice, sniperOptimal) && ma200Dist / stopDist >= requiredMinRr)
                if (ma200MilestoneValid && ma200Dist / stopDist <= 3.5) {
                    "هدف مقاومت/حمایت MA200 کتاب TuAnh" to trigSnap.sma200
                } else {
                    validWaveCandidates.minByOrNull { (_, targetPrice) ->
                        val targetRr = abs(targetPrice - sniperOptimal) / stopDist
                        abs(targetRr - computedOptimalRr)
                    }
                }
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
                BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL ->
                    append("فرکتال ۵ کندلی بیل ویلیامز (${round2(if (isLong) trigSnap.lastDownFractalLow else trigSnap.lastUpFractalHigh)}) + پشت کیجون")
                BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 ->
                    append("Kijun(30)=${round2(trigSnap.kijun30)} ± 0.4×ATR14 سیستم Keenbase")
                BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE ->
                    append("زیر/بالای کندل شکست ماروبوزو + فشردگی بولینگر باند (${round2(trigSnap.bbMiddle20)})")
                BitcoinStrategyModel.TSG_QUICK_PROFIT_4STEP ->
                    append("زیر/بالای کندل شکست کومو (گام ۴ سیستم TSG) + کیجون (${round2(trigSnap.kijun)})")
                BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34,
                BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 ->
                    append("سنگر ترکیبی Base Line (${round2(trigSnap.kijun)}) + EMA34 (${round2(trigSnap.ema34)}) کونیتز")
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
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE -> {
                val c1 = if (isLong) stratSnap.isPriceAboveKumo && trigSnap.isPriceAboveKumo else stratSnap.isPriceBelowKumo && trigSnap.isPriceBelowKumo
                val c2 = trigSnap.isBollingerSqueeze || (if (isLong) trigSnap.close >= trigSnap.bbMiddle20 else trigSnap.close <= trigSnap.bbMiddle20)
                val c3 = if (isLong) trigSnap.isMarubozuBreakoutBull && trigSnap.close >= trigSnap.bbMiddle20 else trigSnap.isMarubozuBreakoutBear && trigSnap.close <= trigSnap.bbMiddle20
                val c4 = if (isLong) trigSnap.close >= trigSnap.ema89 - trigSnap.atr14 * 0.15 else trigSnap.close <= trigSnap.ema89 + trigSnap.atr14 * 0.15
                val c5 = if (isLong) trigSnap.rsi14 >= 50.0 else trigSnap.rsi14 <= 50.0
                val c6 = if (isLong) trigSnap.tenkan >= trigSnap.kijun - trigSnap.atr14 * 0.05 else trigSnap.tenkan <= trigSnap.kijun + trigSnap.atr14 * 0.05
                val c7 = if (isLong) trigSnap.isFutureKumoBullish else trigSnap.isFutureKumoBearish
                val c8 = cond6Passed
                val c9 = cond8Passed
                val c10 = dirCandle && trigSnap.volumeRatio20 >= 0.55
                listOf(
                    StrategyConditionCheck(1, "۱. کلوز قیمت بیرون ابر کومو در تایم استراتژی و ورود (TuAnh Rule 1)", c1, cond1Detail),
                    StrategyConditionCheck(2, "۲. فشردگی باند بولینگر (BB 20,2 Squeeze) یا حرکت روی باند", c2, String.format(Locale.US, "پهنای BB=%.2f%% · باند میانی=%s · Squeeze=%s", trigSnap.bbBandwidthRatio * 100.0, round2(trigSnap.bbMiddle20).toString(), if (trigSnap.isBollingerSqueeze) "فعال ✅" else "عادی")),
                    StrategyConditionCheck(3, "۳. کندل شکست ماروبوزو/تصمیم بیرون باند میانی BB(20)", c3, String.format(Locale.US, "بدنه=%.0f%% · CLV=%.0f%% · عبور از BB20=%s", trigSnap.candleBodyRatio * 100.0, trigSnap.closeLocationValue * 100.0, if (c3) "تایید ✅" else "در انتظار")),
                    StrategyConditionCheck(4, "۴. فیلتر روند EMA(89) و موقعیت هدف نسبت به SMA(200)", c4, String.format(Locale.US, "قیمت=%s · EMA(89)=%s · SMA(200)=%s", round2(trigSnap.close).toString(), round2(trigSnap.ema89).toString(), round2(trigSnap.sma200).toString())),
                    StrategyConditionCheck(5, "۵. تثبیت مومنتوم RSI(14) بالای ۵۰ در خرید / زیر ۵۰ در فروش", c5, String.format(Locale.US, "RSI(14)=%.1f (قبلی=%.1f · خط معیار=50.0)", trigSnap.rsi14, trigSnap.prevRsi14)),
                    StrategyConditionCheck(6, "۶. هم‌جهتی Tenkan(9) و Kijun(26) با انفجار بولینگر", c6, String.format(Locale.US, "Tenkan=%s · Kijun=%s", round2(trigSnap.tenkan).toString(), round2(trigSnap.kijun).toString())),
                    StrategyConditionCheck(7, "۷. جهت و رنگ ابر آینده (Future Kumo 26) هم‌سو با شکست باند", c7, cond4Detail),
                    StrategyConditionCheck(8, "۸. آزادی Chikou Span در ۲۶ دوره قبل از کندل و ابر", c8, cond6Detail),
                    StrategyConditionCheck(9, "۹. عدم کشیدگی افراطی از باند میانی BB(20) و Kijun", c9, String.format(Locale.US, "فاصله از کیجون=%.2f ATR", stratSnap.distanceToKijunAtr)),
                    StrategyConditionCheck(10, "۱۰. تایید حجم کندل شکست (Volume) و ساختار موج هوسودا", c10 && cond10Passed, String.format(Locale.US, "حجم=%.2fx · %s", trigSnap.volumeRatio20, cond10Detail)),
                    StrategyConditionCheck(11, "۱۱. عبور از سقف/کف ساختاری و بافر فلت SSB", cond7Passed && cond9Passed, "شکست سقف چیکو و عبور از جاذبه فلت"),
                    StrategyConditionCheck(12, "۱۲. فرمول R:R کتاب TuAnh (ریسک‌فری ۱:۱ و هدف MA200 یا ۱:۲ تا ۱:۵)", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند (ER14) و خروج از رِنج", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. محافظ ضد شدوی معکوس در لبه باند بولینگر", cond14Passed, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز فوران فشردگی بولینگر (Genesis/Core)", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود روی شکست ماروبوزو یا پولبک به BB20/EMA89", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL -> {
                val c1 = if (isLong) stratSnap.isPriceAboveKumo && trigSnap.isPriceAboveKumo else stratSnap.isPriceBelowKumo && trigSnap.isPriceBelowKumo
                val c2 = if (isLong) trigSnap.close >= trigSnap.ema21 - trigSnap.atr14 * 0.12 && trigSnap.ema21 >= trigSnap.kumoBottom else trigSnap.close <= trigSnap.ema21 + trigSnap.atr14 * 0.12 && trigSnap.ema21 <= trigSnap.kumoTop
                val c3 = if (isLong) trigSnap.ema21SlopeAtrPerBar >= -0.01 && stratSnap.ema21 >= stratSnap.kijun - stratSnap.atr14 * 0.25 else trigSnap.ema21SlopeAtrPerBar <= 0.01 && stratSnap.ema21 <= stratSnap.kijun + stratSnap.atr14 * 0.25
                val c4 = if (isLong) trigSnap.close >= trigSnap.kijun - trigSnap.atr14 * 0.10 && trigSnap.kijunSlopeAtrPerBar >= -0.02 else trigSnap.close <= trigSnap.kijun + trigSnap.atr14 * 0.10 && trigSnap.kijunSlopeAtrPerBar <= 0.02
                val c5 = if (isLong) trigSnap.isFutureKumoBullish else trigSnap.isFutureKumoBearish
                val c7 = if (isLong) trigSnap.rsi7Bullish50Bounce || (trigSnap.rsi7 >= 50.0 && trigSnap.prevRsi7 <= 54.0) else trigSnap.rsi7Bearish50Bounce || (trigSnap.rsi7 <= 50.0 && trigSnap.prevRsi7 >= 46.0)
                val c8 = if (isLong) trigSnap.rsi7 in 48.0..80.0 else trigSnap.rsi7 in 20.0..52.0
                val fractalPrice = if (isLong) trigSnap.lastDownFractalLow else trigSnap.lastUpFractalHigh
                val c9 = if (isLong) fractalPrice < trigSnap.close && fractalPrice > 0.0 else fractalPrice > trigSnap.close
                val distEmaAtr = abs(trigSnap.close - trigSnap.ema21) / trigSnap.atr14.coerceAtLeast(1e-8)
                val c10 = distEmaAtr <= 1.65 && cond8Passed
                listOf(
                    StrategyConditionCheck(1, "۱. کلوز کامل قیمت بیرون ابر کومو در 1H و تایم ورود (15min Guide Rule 1)", c1, cond1Detail),
                    StrategyConditionCheck(2, "۲. قرارگیری قیمت و EMA(21) در سمت درست ابر کومو (بدون نیاز به تنکان/چیکو)", c2, String.format(Locale.US, "قیمت=%s · EMA(21)=%s · لبه کومو=%s", round2(trigSnap.close).toString(), round2(trigSnap.ema21).toString(), round2(if (isLong) trigSnap.kumoTop else trigSnap.kumoBottom).toString())),
                    StrategyConditionCheck(3, "۳. شیب موافق میانگین نمایی EMA(21) در جهت روند اصلی", c3, String.format(Locale.US, "شیب EMA(21)=%+.2f ATR/bar", trigSnap.ema21SlopeAtrPerBar)),
                    StrategyConditionCheck(4, "۴. حمایت/مقاومت خط Kijun-Sen(26) و شیب غیرمخالف کیجون", c4, String.format(Locale.US, "Kijun(26)=%s · شیب کیجون=%+.2f ATR", round2(trigSnap.kijun).toString(), trigSnap.kijunSlopeAtrPerBar)),
                    StrategyConditionCheck(5, "۵. هم‌رنگی و حمایت ابر آینده (Future Kumo 26)", c5, cond4Detail),
                    StrategyConditionCheck(6, "۶. ضخامت استاندارد ابر کومو به‌عنوان سپر روند", cond5Passed, cond5Detail),
                    StrategyConditionCheck(7, "۷. اصلاح و پرش RSI(7) از خط مرکزی ۵۰ در جهت روند (RSI-7 50 Bounce)", c7, String.format(Locale.US, "RSI(7) فعلی=%.1f · RSI(7) قبلی=%.1f · پرش از ۵۰=%s", trigSnap.rsi7, trigSnap.prevRsi7, if (c7) "تایید ✅" else "در انتظار")),
                    StrategyConditionCheck(8, "۸. قرارگیری RSI(7) در پنجره امن مومنتوم (عدم اشباع افراطی)", c8, String.format(Locale.US, "RSI(7)=%.1f (پنجره امن: %s)", trigSnap.rsi7, if (isLong) "48..80" else "20..52")),
                    StrategyConditionCheck(9, "۹. ثبت فرکتال ۵ کندلی معتبر بیل ویلیامز برای سنگر حد ضرر", c9, String.format(Locale.US, "آخرین فرکتال ۵ کندلی=%s", round2(fractalPrice).toString())),
                    StrategyConditionCheck(10, "۱۰. فاصله بهینه قیمت از EMA(21) و Kijun(26) پس از پولبک", c10, String.format(Locale.US, "فاصله از EMA(21)=%.2f ATR · فاصله از کیجون=%.2f ATR", distEmaAtr, stratSnap.distanceToKijunAtr)),
                    StrategyConditionCheck(11, "۱۱. کندل تایید بازگشت روند با کلوز در جهت EMA(21)", dirCandle, String.format(Locale.US, "بدنه=%.0f%% · CLV=%.0f%%", trigSnap.candleBodyRatio * 100.0, trigSnap.closeLocationValue * 100.0)),
                    StrategyConditionCheck(12, "۱۲. فرمول R:R = 1:3.0 کتاب فکا (ریسک‌فری در 1R و هدف 3R فرکتال)", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند (ER14) و نبود بازار رِنج", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. نبود شدوی تله نقدینگی خلاف جهت پرش RSI(7)", cond14Passed, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز ادامه روند پس از پایان پولبک (Core/Genesis)", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود نزدیک EMA(21) در کندل پرش RSI(7)", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 -> {
                val c1 = if (isLong) trigSnap.close > trigSnap.kumoTop103060 && stratSnap.close > stratSnap.kumoTop103060 else trigSnap.close < trigSnap.kumoBottom103060 && stratSnap.close < stratSnap.kumoBottom103060
                val c2 = if (isLong) trigSnap.tenkan10 >= trigSnap.kijun30 && stratSnap.tenkan10 >= stratSnap.kijun30 - stratSnap.atr14 * 0.08 else trigSnap.tenkan10 <= trigSnap.kijun30 && stratSnap.tenkan10 <= stratSnap.kijun30 + stratSnap.atr14 * 0.08
                val c3 = if (isLong) trigSnap.tenkanSlopeAtrPerBar >= -0.04 && trigSnap.kijunSlopeAtrPerBar >= -0.02 else trigSnap.tenkanSlopeAtrPerBar <= 0.04 && trigSnap.kijunSlopeAtrPerBar <= 0.02
                val c4 = if (isLong) trigSnap.tenkan10 >= trigSnap.kumoBottom103060 && trigSnap.kijun30 >= trigSnap.kumoBottom103060 - trigSnap.atr14 * 0.15 else trigSnap.tenkan10 <= trigSnap.kumoTop103060 && trigSnap.kijun30 <= trigSnap.kumoTop103060 + trigSnap.atr14 * 0.15
                val c5 = if (isLong) trigSnap.senkouA103060 >= trigSnap.senkouB60 - trigSnap.atr14 * 0.15 || trigSnap.isFutureKumoBullish else trigSnap.senkouA103060 <= trigSnap.senkouB60 + trigSnap.atr14 * 0.15 || trigSnap.isFutureKumoBearish
                val kumo60ThickAtr = abs(trigSnap.senkouA103060 - trigSnap.senkouB60) / trigSnap.atr14.coerceAtLeast(1e-8)
                val c6 = kumo60ThickAtr >= 0.04 && cond5Passed
                val distKijun30Atr = abs(trigSnap.close - trigSnap.kijun30) / trigSnap.atr14.coerceAtLeast(1e-8)
                val c8 = distKijun30Atr <= config.maxKijunExtensionAtr
                val keenSl = if (isLong) trigSnap.kijun30 - trigSnap.atr14 * 0.40 else trigSnap.kijun30 + trigSnap.atr14 * 0.40
                listOf(
                    StrategyConditionCheck(1, "۱. کلوز کامل کندل بیرون هر دو خط Senkou A(10/30) و Senkou B(60)", c1, String.format(Locale.US, "کلوز=%s · ابر (10,30,60)=[%s .. %s]", round2(trigSnap.close).toString(), round2(trigSnap.kumoBottom103060).toString(), round2(trigSnap.kumoTop103060).toString())),
                    StrategyConditionCheck(2, "۲. کراس و چیدمان نهادی Tenkan(10) و Kijun(30)", c2, String.format(Locale.US, "Tenkan(10)=%s · Kijun(30)=%s", round2(trigSnap.tenkan10).toString(), round2(trigSnap.kijun30).toString())),
                    StrategyConditionCheck(3, "۳. شیب فعال خطوط Tenkan(10) و Kijun(30)", c3, String.format(Locale.US, "شیب تنکان=%+.2f ATR · شیب کیجون=%+.2f ATR", trigSnap.tenkanSlopeAtrPerBar, trigSnap.kijunSlopeAtrPerBar)),
                    StrategyConditionCheck(4, "۴. استقرار خطوط Tenkan(10) و Kijun(30) بیرون ناحیه مخالف کوموی ۶۰", c4, String.format(Locale.US, "TK(10/30)=%s/%s در برابر کومو ۶۰", round2(trigSnap.tenkan10).toString(), round2(trigSnap.kijun30).toString())),
                    StrategyConditionCheck(5, "۵. هم‌سویی Senkou Span A(10/30) و Senkou Span B(60) و ابر آینده", c5, String.format(Locale.US, "SpanA(10/30)=%s · SpanB(60)=%s", round2(trigSnap.senkouA103060).toString(), round2(trigSnap.senkouB60).toString())),
                    StrategyConditionCheck(6, "۶. ضخامت و پایداری ابر ۶۰ دوره‌ای (Senkou B 60)", c6, String.format(Locale.US, "ضخامت کوموی ۶۰=%.2f ATR", kumo60ThickAtr)),
                    StrategyConditionCheck(7, "۷. رهایی خط Chikou در فضای باز و عبور از سقف/کف", cond6Passed && cond7Passed, cond6Detail),
                    StrategyConditionCheck(8, "۸. فاصله استاندارد قیمت از Kijun(30) (کنترل اندازه استاپ مکانیکی)", c8, String.format(Locale.US, "فاصله از Kijun(30)=%.2f ATR · استاپ مکانیکی=%s", distKijun30Atr, round2(keenSl).toString())),
                    StrategyConditionCheck(9, "۹. عبور از جاذبه فلت Senkou B(60)", cond9Passed, "مسیر باز از فلت ۶۰ دوره‌ای"),
                    StrategyConditionCheck(10, "۱۰. هندسه موج هوسودا هم‌سو با کراس مکانیکی (10,30,60)", cond10Passed, cond10Detail),
                    StrategyConditionCheck(11, "۱۱. کلوز قدرتمند کندل سیگنال بیرون ابر ۶۰ دوره‌ای", dirCandle && trigSnap.candleBodyRatio >= 0.20, String.format(Locale.US, "بدنه=%.0f%% · CLV=%.0f%%", trigSnap.candleBodyRatio * 100.0, trigSnap.closeLocationValue * 100.0)),
                    StrategyConditionCheck(12, "۱۲. فرمول مکانیکی Keenbase: استاپ Kijun(30)±0.4ATR و تارگت قطعی 3R", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند (ER14) و فیلتر بازار رِنج", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. نبود شدوی معکوس سنگین روی کندل کراس 10/30", cond14Passed, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز آغاز یا قلب موج کراس 10/30/60", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود روی Tenkan(10) / Kijun(30)", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.MUKHERJEE_RELATIVE_TK_RSI_KELLEY -> {
                val c1 = if (isLong) trigSnap.tenkan >= trigSnap.kijun && trigSnap.kijun >= trigSnap.kumoBottom else trigSnap.tenkan <= trigSnap.kijun && trigSnap.kijun <= trigSnap.kumoTop
                val c2 = if (isLong) trigSnap.close > trigSnap.kijun else trigSnap.close < trigSnap.kijun
                val c3 = cond1Passed
                val c4 = if (isLong) trigSnap.isFutureKumoBullish else trigSnap.isFutureKumoBearish
                val c5 = cond6Passed
                val c6 = trigSnap.kellySignalCount >= 3 && trigSnap.kellyWinProbabilityPercent >= 62
                val c7 = if (isLong) trigSnap.hasBullishCandlestickPattern else trigSnap.hasBearishCandlestickPattern
                val c8 = if (isLong) trigSnap.rsi14 >= 50.0 && trigSnap.rsiPeakDropLong < 10.5 else trigSnap.rsi14 <= 50.0 && trigSnap.rsiTroughRiseShort < 12.0
                val c9 = !trigSnap.isKijunFlat || !stratSnap.isKijunFlat
                listOf(
                    StrategyConditionCheck(1, "۱. قدرت نسبی کراس Tenkan/Kijun نسبت به ابر کومو (قانون موکرجی)", c1, String.format(Locale.US, "TK=%s/%s در سمت قوی کومو", round2(trigSnap.tenkan).toString(), round2(trigSnap.kijun).toString())),
                    StrategyConditionCheck(2, "۲. سیگنال Kijun-Sen Cross و تثبیت قیمت بالای/زیر کیجون", c2, String.format(Locale.US, "کلوز=%s در برابر Kijun=%s", round2(trigSnap.close).toString(), round2(trigSnap.kijun).toString())),
                    StrategyConditionCheck(3, "۳. سیگنال Kumo Breakout در تایم استراتژی و ورود", c3, cond1Detail),
                    StrategyConditionCheck(4, "۴. سیگنال Senkou Span Cross در ابر آینده (۲۶ کندل جلوتر)", c4, cond4Detail),
                    StrategyConditionCheck(5, "۵. سیگنال Chikou Span Cross در فضای باز ۲۶ دوره قبل", c5, cond6Detail),
                    StrategyConditionCheck(6, "۶. حد نصاب طوفان کامل و معیار کلی (Kelly Criterion ≥ 62%)", c6, String.format(Locale.US, "تعداد سیگنال‌های هم‌زمان: %d/5 · احتمال برد کلی: %d%%", trigSnap.kellySignalCount, trigSnap.kellyWinProbabilityPercent)),
                    StrategyConditionCheck(7, "۷. الگوی کندل‌استیک ژاپنی تاییدکننده (Checkmate / Engulfing / PinBar)", c7, if (c7) "الگوی شمعی ژاپنی روی تعادل تایید شد ✅" else "در انتظار الگوی کندلی تایید"),
                    StrategyConditionCheck(8, "۸. مومنتوم RSI(14) بالای/زیر ۵۰ بدون افت ۱۰ واحدی از قله (فیلتر موکرجی)", c8, String.format(Locale.US, "RSI(14)=%.1f · انحراف از قله/کف اخیر=%.1f واحد", trigSnap.rsi14, if (isLong) trigSnap.rsiPeakDropLong else trigSnap.rsiTroughRiseShort)),
                    StrategyConditionCheck(9, "۹. شیب فعال Kijun-Sen و نبود کشیدگی C-Clamp", c9 && cond8Passed, String.format(Locale.US, "فاصله از کیجون=%.2f ATR · شیب کیجون=%+.2f", stratSnap.distanceToKijunAtr, trigSnap.kijunSlopeAtrPerBar)),
                    StrategyConditionCheck(10, "۱۰. هندسه موج N هوسودا هم‌سو با طوفان کامل موکرجی", cond10Passed, cond10Detail),
                    StrategyConditionCheck(11, "۱۱. تایید حجم معاملات در کندل شمعی ژاپنی", trigSnap.volumeRatio20 >= 0.60, String.format(Locale.US, "نسبت حجم به میانگین ۲۰=%.2fx", trigSnap.volumeRatio20)),
                    StrategyConditionCheck(12, "۱۲. نسبت R:R بهینه (≥ 1:2.35) منطبق با فرمول کلی", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند (ER14) و فیلتر نویز", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. رد شدوی معکوس و تایید کلوز کندل", cond14Passed, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز گسترش روند و سلامت قله RSI(14)", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود روی کندل تایید ژاپنی نزدیک Tenkan/Kijun", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.MUKHERJEE_CLOUDS_CHIKOU_FILTER -> {
                val c1 = cond1Passed
                val c2 = if (isLong) trigSnap.close >= trigSnap.tenkan && trigSnap.tenkanSlopeAtrPerBar >= -0.02 else trigSnap.close <= trigSnap.tenkan && trigSnap.tenkanSlopeAtrPerBar <= 0.02
                val c3 = !trigSnap.isKijunFlat && if (isLong) trigSnap.kijunSlopeAtrPerBar >= -0.01 else trigSnap.kijunSlopeAtrPerBar <= 0.01
                val c4 = cond6Passed
                val c5 = if (isLong) trigSnap.chikouAboveCloud26 else trigSnap.chikouBelowCloud26
                val c6 = if (isLong) trigSnap.sanyakuBullishOrder else trigSnap.sanyakuBearishOrder
                val c7 = if (isLong) trigSnap.isFutureKumoBullish else trigSnap.isFutureKumoBearish
                listOf(
                    StrategyConditionCheck(1, "۱. استقرار قیمت بیرون ابر کومو در هر دو تایم‌فریم (Mukherjee Dual Cloud)", c1, cond1Detail),
                    StrategyConditionCheck(2, "۲. خط ماشه Tenkan-Sen (Trigger Line): کلوز و شیب فعال تنکان", c2, String.format(Locale.US, "Tenkan=%s · شیب تنکان=%+.2f ATR/bar", round2(trigSnap.tenkan).toString(), trigSnap.tenkanSlopeAtrPerBar)),
                    StrategyConditionCheck(3, "۳. فیلتر ضد رِنج Kijun-Sen: غیرفلت بودن کیجون (حذف تله آهنربای رِنج)", c3, String.format(Locale.US, "Kijun=%s · شیب کیجون=%+.2f ATR · وضعیت=%s", round2(trigSnap.kijun).toString(), trigSnap.kijunSlopeAtrPerBar, if (trigSnap.isKijunFlat) "فلت/رِنج ⚠️" else "رونددار ✅")),
                    StrategyConditionCheck(4, "۴. فیلتر نهایی Chikou Span (The Filter): عبور از قیمت ۲۶ دوره قبل", c4, cond6Detail),
                    StrategyConditionCheck(5, "۵. عبور Chikou Span از ابر کوموی ۲۶ دوره قبل", c5, if (c5) "چیکو کاملاً بیرون ابر ۲۶ کندل قبل ✅" else "چیکو هنوز درگیر ابر ۲۶ کندل قبل است"),
                    StrategyConditionCheck(6, "۶. چیدمان کامل Price / Tenkan / Kijun / Kumo", c6, String.format(Locale.US, "قیمت=%s · تنکان=%s · کیجون=%s", round2(trigSnap.close).toString(), round2(trigSnap.tenkan).toString(), round2(trigSnap.kijun).toString())),
                    StrategyConditionCheck(7, "۷. جهت و شیب ابر آینده هم‌راستا با خط ماشه تنکان", c7, cond4Detail),
                    StrategyConditionCheck(8, "۸. ضخامت کافی ابر آینده و ایمنی از پیچش", cond5Passed, cond5Detail),
                    StrategyConditionCheck(9, "۹. قانون آهنربای کیجون: عدم کشیدگی بیش از حد قیمت از کیجون", cond8Passed, String.format(Locale.US, "فاصله از کیجون=%.2f ATR", stratSnap.distanceToKijunAtr)),
                    StrategyConditionCheck(10, "۱۰. هندسه موج هوسودا در جهت خط ماشه تنکان", cond10Passed, cond10Detail),
                    StrategyConditionCheck(11, "۱۱. قدرت بدنه کندل ماشه روی Tenkan-Sen و تایید حجم", dirCandle && trigSnap.volumeRatio20 >= 0.60, String.format(Locale.US, "بدنه=%.0f%% · حجم=%.2fx", trigSnap.candleBodyRatio * 100.0, trigSnap.volumeRatio20)),
                    StrategyConditionCheck(12, "۱۲. نسبت R:R ≥ 1:2.20 با سنگر حد ضرر پشت Kijun-Sen", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی روند (ER14) و نبود نوسان فرسایشی", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. نبود شدوی تله در برخورد با خط ماشه Tenkan-Sen", cond14Passed, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز روند فعال پس از خروج کیجون از حالت فلت", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود روی خط ماشه Tenkan-Sen", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.HOWTOTRADE_KEY_LEVEL_BREAK_PULLBACK -> {
                val c1 = cond1Passed
                val keyLevels = (stratSnap.flatEquilibriumLevels + trigSnap.flatEquilibriumLevels).distinct()
                val hasSupportiveKeyLevel = if (isLong) {
                    keyLevels.any { it <= trigSnap.close + trigSnap.atr14 * 0.25 } || trigSnap.isPriceAboveKumo
                } else {
                    keyLevels.any { it >= trigSnap.close - trigSnap.atr14 * 0.25 } || trigSnap.isPriceBelowKumo
                }
                val c3 = if (isLong) trigSnap.close > trigSnap.kumoTop && trigSnap.tenkan >= trigSnap.kijun else trigSnap.close < trigSnap.kumoBottom && trigSnap.tenkan <= trigSnap.kijun
                val c4 = if (isLong) trigSnap.isFutureKumoBullish && stratSnap.isFutureKumoBullish else trigSnap.isFutureKumoBearish && stratSnap.isFutureKumoBearish
                listOf(
                    StrategyConditionCheck(1, "۱. شکست کامل کانال کومو (عبور از هر دو خط Senkou A و B)", c1, cond1Detail),
                    StrategyConditionCheck(2, "۲. هم‌پوشانی لبه کومو/کیجون با سطح کلیدی افقی S/R (HowToTrade Setup 1)", hasSupportiveKeyLevel, String.format(Locale.US, "سطوح کلیدی شناسایی‌شده=%d سطح · لبه کومو=%s", keyLevels.size, round2(if (isLong) trigSnap.kumoTop else trigSnap.kumoBottom).toString())),
                    StrategyConditionCheck(3, "۳. تثبیت بالای/زیر کانال کومو همراه با هم‌جهتی Tenkan و Kijun (Setup 2)", c3, String.format(Locale.US, "Tenkan=%s · Kijun=%s · کلوز=%s", round2(trigSnap.tenkan).toString(), round2(trigSnap.kijun).toString(), round2(trigSnap.close).toString())),
                    StrategyConditionCheck(4, "۴. تایید پیچش ابر آینده (Future Kumo Twist: کراس Senkou A و B)", c4, cond4Detail),
                    StrategyConditionCheck(5, "۵. ضخامت و استحکام کانال کوموی آینده", cond5Passed, cond5Detail),
                    StrategyConditionCheck(6, "۶. آزادی خط Chikou Span در ۲۶ دوره قبل", cond6Passed, cond6Detail),
                    StrategyConditionCheck(7, "۷. شکست سقف/کف ساختاری چیکو در جهت شکست کانال", cond7Passed, "عبور از مقاومت/حمایت چیکو"),
                    StrategyConditionCheck(8, "۸. عدم کشیدگی بیش از حد از سطح کلیدی کومو/کیجون", cond8Passed, String.format(Locale.US, "فاصله از کیجون=%.2f ATR", stratSnap.distanceToKijunAtr)),
                    StrategyConditionCheck(9, "۹. عبور ایمن از جاذبه فلت SSB", cond9Passed, "بافر امن بالای/زیر فلت SSB"),
                    StrategyConditionCheck(10, "۱۰. ساختار موج N/V/E هوسودا از مبدا سطح کلیدی شکسته شده", cond10Passed, cond10Detail),
                    StrategyConditionCheck(11, "۱۱. کندل تایید پرایس‌اکشن روی ری‌تست لبه کومو/کیجون با حجم مناسب", dirCandle && trigSnap.volumeRatio20 >= 0.60, String.format(Locale.US, "بدنه=%.0f%% · حجم=%.2fx", trigSnap.candleBodyRatio * 100.0, trigSnap.volumeRatio20)),
                    StrategyConditionCheck(12, "۱۲. نسبت R:R ≥ 1:2.40 با حد ضرر پشت کانال کومو و سطح کلیدی", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند (ER14) پس از خروج از کانال کومو", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. نبود شدوی بازگشتی به درون کانال کومو", cond14Passed, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز آغاز یا گسترش موج پس از شکست کانال", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود روی ری‌تست سطح کلیدی کومو/کیجون", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.TSG_QUICK_PROFIT_4STEP -> {
                val step1 = if (isLong) trigSnap.isPriceAboveKumo && stratSnap.isPriceAboveKumo else trigSnap.isPriceBelowKumo && stratSnap.isPriceBelowKumo
                val step2a = if (isLong) trigSnap.tenkan >= trigSnap.kijun else trigSnap.tenkan <= trigSnap.kijun
                val step2b = if (isLong) trigSnap.chikouAboveCloud26 && trigSnap.chikouBullishOpenSpace else trigSnap.chikouBelowCloud26 && trigSnap.chikouBearishOpenSpace
                val tkCloudDistAtr = abs(trigSnap.tenkan - (if (isLong) trigSnap.kumoTop else trigSnap.kumoBottom)) / trigSnap.atr14.coerceAtLeast(1e-8)
                val step2c = tkCloudDistAtr <= 2.40
                val step3 = dirCandle && trigSnap.candleBodyRatio >= 0.22
                listOf(
                    StrategyConditionCheck(1, "۱. گام اول TSG: شکست و کلوز قطعی کندل بیرون ابر کومو", step1, cond1Detail),
                    StrategyConditionCheck(2, "۲. گام دوم الف TSG: کراس و برتری Conversion Line (تنکان) بر Base Line (کیجون)", step2a, String.format(Locale.US, "Conversion(9)=%s · Base(26)=%s", round2(trigSnap.tenkan).toString(), round2(trigSnap.kijun).toString())),
                    StrategyConditionCheck(3, "۳. گام دوم ب TSG: قرارگیری Chikou Span بیرون ابر کوموی ۲۶ دوره قبل", step2b, if (step2b) "چیکو بالای/زیر ابر ۲۶ دوره قبل و آزاد ✅" else "چیکو درگیر ابر ۲۶ دوره قبل"),
                    StrategyConditionCheck(4, "۴. قانون فاصله TSG: عدم فاصله افراطی کراس TK از ابر کومو", step2c, String.format(Locale.US, "فاصله تنکان از لبه کومو=%.2f ATR", tkCloudDistAtr)),
                    StrategyConditionCheck(5, "۵. گام سوم TSG: کندل تایید ورود در جهت کراس Conversion/Base", step3, String.format(Locale.US, "بدنه کندل=%.0f%% · CLV=%.0f%%", trigSnap.candleBodyRatio * 100.0, trigSnap.closeLocationValue * 100.0)),
                    StrategyConditionCheck(6, "۶. هم‌جهتی تایم‌فریم بالاتر (1H/4H) با شکست ۴ گامی TSG", cond1Passed, cond1Detail),
                    StrategyConditionCheck(7, "۷. جهت و رنگ ابر آینده (Future Kumo 26) هم‌سو با گام ۱ و ۲", if (isLong) trigSnap.isFutureKumoBullish else trigSnap.isFutureKumoBearish, cond4Detail),
                    StrategyConditionCheck(8, "۸. ضخامت کافی ابر آینده و ایمنی از پیچش", cond5Passed, cond5Detail),
                    StrategyConditionCheck(9, "۹. عدم کشیدگی افراطی از Base Line (کیجون) در لحظه گام ۳", cond8Passed, String.format(Locale.US, "فاصله از Base Line=%.2f ATR", stratSnap.distanceToKijunAtr)),
                    StrategyConditionCheck(10, "۱۰. تطابق گام ۴ با اهداف موجی هوسودا (V/N/E)", cond10Passed, cond10Detail),
                    StrategyConditionCheck(11, "۱۱. عبور از سقف/کف چیکو و بافر فلت SSB", cond7Passed && cond9Passed, "مسیر باز چیکو و فلت SSB"),
                    StrategyConditionCheck(12, "۱۲. گام چهارم TSG: استاپ پشت کندل شکست/کیجون و نسبت R:R ≥ 1:2.25", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی روند (ER14) و نبود بازار رِنج در زمان کراس TK", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. نبود شدوی معکوس سنگین خلاف جهت گام ۳", cond14Passed, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز موج تازه نفس پس از شکست کومو (Genesis/Core)", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود گام ۳ روی سطح بهینه Conversion/Base", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34 -> {
                val r1 = if (isLong) trigSnap.close > trigSnap.kijun && trigSnap.kijunSlopeAtrPerBar >= -0.02 else trigSnap.close < trigSnap.kijun && trigSnap.kijunSlopeAtrPerBar <= 0.02
                val r2 = cond1Passed
                val r3 = if (isLong) trigSnap.isFutureKumoBullish && stratSnap.isFutureKumoBullish else trigSnap.isFutureKumoBearish && stratSnap.isFutureKumoBearish
                val r4 = if (isLong) trigSnap.chikouAboveCloud26 && trigSnap.chikouBullishOpenSpace else trigSnap.chikouBelowCloud26 && trigSnap.chikouBearishOpenSpace
                val r5Dmi = if (isLong) trigSnap.isDmiBullish else trigSnap.isDmiBearish
                val r5Macd = if (isLong) trigSnap.isMacdBullish else trigSnap.isMacdBearish
                val r5Ema34 = if (isLong) trigSnap.close >= trigSnap.ema34 - trigSnap.atr14 * 0.10 else trigSnap.close <= trigSnap.ema34 + trigSnap.atr14 * 0.10
                val tkEma34Ok = if (isLong) trigSnap.tenkan >= trigSnap.kijun - trigSnap.atr14 * 0.05 else trigSnap.tenkan <= trigSnap.kijun + trigSnap.atr14 * 0.05
                val rsiOk = if (isLong) trigSnap.rsi14 in 48.0..78.0 else trigSnap.rsi14 in 22.0..52.0
                listOf(
                    StrategyConditionCheck(1, "۱. قانون ۱ کونیتز: قیمت در سمت درست خط Base (کیجون) دارای شیب موافق", r1, String.format(Locale.US, "کلوز=%s · Kijun=%s (شیب=%+.2f ATR)", round2(trigSnap.close).toString(), round2(trigSnap.kijun).toString(), trigSnap.kijunSlopeAtrPerBar)),
                    StrategyConditionCheck(2, "۲. قانون ۲ کونیتز: استقرار کامل قیمت بیرون ابر کوموی فعلی", r2, cond1Detail),
                    StrategyConditionCheck(3, "۳. قانون ۳ کونیتز: هم‌رنگی و شیب موافق ابر آینده (Future Kumo 26)", r3, cond4Detail),
                    StrategyConditionCheck(4, "۴. قانون ۴ کونیتز: عبور Chikou Span از درون ابر ۲۶ دوره قبل به فضای باز", r4, if (r4) "چیکو از کوموی ۲۶ دوره قبل عبور کرده و آزاد است ✅" else "چیکو درگیر ابر/کندل ۲۶ دوره قبل"),
                    StrategyConditionCheck(5, "۵. قانون ۵ الف کونیتز (DMI/ADX): برتری +DI/-DI همراه با ADX(14) ≥ 20", r5Dmi || r5Macd, String.format(Locale.US, "ADX(14)=%.1f · +DI=%.1f · -DI=%.1f", trigSnap.adx14, trigSnap.plusDi14, trigSnap.minusDi14)),
                    StrategyConditionCheck(6, "۶. قانون ۵ ب کونیتز (MACD 12,26,9): تایید خط MACD و هیستوگرام", r5Macd || r5Dmi, String.format(Locale.US, "MACD=%.3f · Signal=%.3f · Hist=%+.3f", trigSnap.macdLine, trigSnap.macdSignal, trigSnap.macdHistogram)),
                    StrategyConditionCheck(7, "۷. قانون ۵ ج کونیتز (EMA 34): حمایت/مقاومت پویا میانگین نمایی ۳۴", r5Ema34, String.format(Locale.US, "قیمت=%s · EMA(34)=%s", round2(trigSnap.close).toString(), round2(trigSnap.ema34).toString())),
                    StrategyConditionCheck(8, "۸. چیدمان هم‌جهت Tenkan-Sen و Kijun-Sen بالای/زیر EMA(34)", tkEma34Ok, String.format(Locale.US, "Tenkan=%s · Kijun=%s · EMA34=%s", round2(trigSnap.tenkan).toString(), round2(trigSnap.kijun).toString(), round2(trigSnap.ema34).toString())),
                    StrategyConditionCheck(9, "۹. عدم اشباع افراطی از کیجون و EMA(34) (فیلتر تعادل کونیتز)", cond8Passed, String.format(Locale.US, "فاصله از کیجون=%.2f ATR", stratSnap.distanceToKijunAtr)),
                    StrategyConditionCheck(10, "۱۰. قرارگیری RSI(14) در ناحیه مومنتوم سالم و ساختار موج هوسودا", rsiOk && cond10Passed, String.format(Locale.US, "RSI(14)=%.1f · %s", trigSnap.rsi14, cond10Detail)),
                    StrategyConditionCheck(11, "۱۱. ضخامت کافی ابر آینده و عبور از جاذبه فلت SSB", cond5Passed && cond9Passed, cond5Detail),
                    StrategyConditionCheck(12, "۱۲. نسبت R:R ≥ 1:2.45 با سنگر دوگانه Kijun + EMA(34)", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی روند (ER14) و قدرت روند DMI/ADX", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. کندل ورود معتبر با کلوز قوی و بدون شدوی معکوس", cond14Passed && dirCandle, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. عدم فعال بودن ۴ قانون خروج کونیتز در لحظه ورود", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود روی همگرایی Tenkan / Kijun / EMA(34)", cond16Passed, cond16Detail),
                )
            }

            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 -> {
                val s1Macro = if (macroSnap == null) true else if (isLong) !macroSnap.isPriceBelowKumo && macroSnap.ema13 >= macroSnap.ema48 - macroSnap.atr14 * 0.25 else !macroSnap.isPriceAboveKumo && macroSnap.ema13 <= macroSnap.ema48 + macroSnap.atr14 * 0.25
                val s2Strat = if (isLong) stratSnap.close > stratSnap.kumoTop72244 && stratSnap.ema13 >= stratSnap.ema34 - stratSnap.atr14 * 0.10 else stratSnap.close < stratSnap.kumoBottom72244 && stratSnap.ema13 <= stratSnap.ema34 + stratSnap.atr14 * 0.10
                val s3TrigCloud = if (isLong) trigSnap.close > trigSnap.kumoTop72244 else trigSnap.close < trigSnap.kumoBottom72244
                val s4FastTk = if (isLong) trigSnap.tenkan7 >= trigSnap.kijun22 else trigSnap.tenkan7 <= trigSnap.kijun22
                val s5RibbonTrig = if (isLong) trigSnap.ema13 >= trigSnap.ema34 && trigSnap.ema34 >= trigSnap.ema48 - trigSnap.atr14 * 0.12 else trigSnap.ema13 <= trigSnap.ema34 && trigSnap.ema34 <= trigSnap.ema48 + trigSnap.atr14 * 0.12
                val s6RibbonStrat = if (isLong) stratSnap.ema13 >= stratSnap.ema34 - stratSnap.atr14 * 0.08 else stratSnap.ema13 <= stratSnap.ema34 + stratSnap.atr14 * 0.08
                val s7FutureFast = if (isLong) trigSnap.senkouA72244 >= trigSnap.senkouB44 - trigSnap.atr14 * 0.10 || trigSnap.isFutureKumoBullish else trigSnap.senkouA72244 <= trigSnap.senkouB44 + trigSnap.atr14 * 0.10 || trigSnap.isFutureKumoBearish
                val s9Osc = if (isLong) trigSnap.isMacdBullish || trigSnap.isDmiBullish else trigSnap.isMacdBearish || trigSnap.isDmiBearish
                val distKijun22Atr = abs(trigSnap.close - trigSnap.kijun22) / trigSnap.atr14.coerceAtLeast(1e-8)
                val s10NotExt = distKijun22Atr <= config.maxKijunExtensionAtr && cond8Passed
                listOf(
                    StrategyConditionCheck(1, "۱. صفحه اول کونیتز (${config.macroInterval.label}): هم‌جهتی کومو و ریبون EMA(13/34/48)", s1Macro, String.format(Locale.US, "تایم کلان ${config.macroInterval.label}: هم‌سو با روند اصلی")),
                    StrategyConditionCheck(2, "۲. صفحه دوم کونیتز (${config.strategyInterval.label}): قیمت بیرون ابر سریع (7, 22, 44)", s2Strat, String.format(Locale.US, "کلوز ${config.strategyInterval.label}=%s · ابر سریع ۴۴=[%s..%s]", round2(stratSnap.close).toString(), round2(stratSnap.kumoBottom72244).toString(), round2(stratSnap.kumoTop72244).toString())),
                    StrategyConditionCheck(3, "۳. صفحه سوم کونیتز (${config.triggerInterval.label}): کلوز قیمت بیرون ابر سریع (7, 22, 44)", s3TrigCloud, String.format(Locale.US, "کلوز=%s · ابر سریع (7,22,44)=[%s .. %s]", round2(trigSnap.close).toString(), round2(trigSnap.kumoBottom72244).toString(), round2(trigSnap.kumoTop72244).toString())),
                    StrategyConditionCheck(4, "۴. کراس و برتری سریع Tenkan(7) و Kijun(22) (شکار زودهنگام روند)", s4FastTk, String.format(Locale.US, "Tenkan(7)=%s · Kijun(22)=%s", round2(trigSnap.tenkan7).toString(), round2(trigSnap.kijun22).toString())),
                    StrategyConditionCheck(5, "۵. آرایش ریبون نمایی کونیتز: EMA(13) / EMA(34) / EMA(48) در تایم ورود", s5RibbonTrig, String.format(Locale.US, "EMA13=%s · EMA34=%s · EMA48=%s", round2(trigSnap.ema13).toString(), round2(trigSnap.ema34).toString(), round2(trigSnap.ema48).toString())),
                    StrategyConditionCheck(6, "۶. تایید کراس EMA(13/34) در تایم استراتژی (${config.strategyInterval.label})", s6RibbonStrat, String.format(Locale.US, "EMA13(${config.strategyInterval.label})=%s · EMA34=%s", round2(stratSnap.ema13).toString(), round2(stratSnap.ema34).toString())),
                    StrategyConditionCheck(7, "۷. جهت و ضخامت کوموی سریع ۴۴ دوره‌ای و ابر آینده", s7FutureFast && cond5Passed, String.format(Locale.US, "SpanA(7/22)=%s · SpanB(44)=%s", round2(trigSnap.senkouA72244).toString(), round2(trigSnap.senkouB44).toString())),
                    StrategyConditionCheck(8, "۸. آزادی خط Chikou در فضای باز و عبور از ابر دوره قبل", cond6Passed, cond6Detail),
                    StrategyConditionCheck(9, "۹. تایید نوسان‌نمای مومنتوم صفحه سوم (MACD / DMI)", s9Osc, String.format(Locale.US, "MACD Hist=%+.3f · ADX=%.1f", trigSnap.macdHistogram, trigSnap.adx14)),
                    StrategyConditionCheck(10, "۱۰. فاصله استاندارد قیمت از Kijun(22) و EMA(34)", s10NotExt, String.format(Locale.US, "فاصله از Kijun(22)=%.2f ATR", distKijun22Atr)),
                    StrategyConditionCheck(11, "۱۱. هندسه موج هوسودا هم‌سو با سیستم سریع 7-22-44", cond10Passed, cond10Detail),
                    StrategyConditionCheck(12, "۱۲. نسبت R:R ≥ 1:2.35 با حد ضرر پشت Kijun(22) و EMA(34)", cond12Passed, cond12Detail),
                    StrategyConditionCheck(13, "۱۳. کارایی جهت‌دار روند (ER14) و فیلتر بازار رِنج", cond13Passed, cond13Detail),
                    StrategyConditionCheck(14, "۱۴. کندل شلیک صفحه سوم با بدنه قوی و شدوی مخالف کم", cond14Passed && dirCandle, cond14Detail),
                    StrategyConditionCheck(15, "۱۵. فاز تولد یا گسترش موج (Genesis/Core) در تنظیمات 7-22-44", cond15Passed, cond15Detail),
                    StrategyConditionCheck(16, "۱۶. نقطه‌زنی ورود روی Tenkan(7) / Kijun(22) / EMA(13)", cond16Passed, cond16Detail),
                )
            }

            else -> emptyList()
        }
    }

    /**
     * تشخیص ۴ ماشه ورود برای شکار اصل روند از لحظه تولد (`EARLY_KATANA_GENESIS`) تا قلب موج (`PULLBACK / BREAKOUT / SANYAKU`)
     * + ماشه‌های اختصاصی هر یک از ۹ کتاب PDF گوگل درایو.
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

        // ── ماشه‌های اختصاصی ۹ کتاب PDF گوگل درایو ────────────────────────────
        when (activeModel) {
            BitcoinStrategyModel.TUANH_BOLLINGER_SQUEEZE -> {
                if (isLong && cur.close > cur.open && cur.close > trigSnap.kumoTop) {
                    if (trigSnap.isBollingerSqueeze && trigSnap.isMarubozuBreakoutBull && trigSnap.rsi14 >= 50.0) {
                        return StrategyTriggerType.EARLY_KATANA_GENESIS
                    }
                    val touchedMidOrEma = listOf(prev1, prev2, cur).any { b ->
                        b.low <= max(trigSnap.bbMiddle20, trigSnap.kijun) + trigSnap.atr14 * 0.35
                    }
                    if (touchedMidOrEma && cur.close >= trigSnap.bbMiddle20 && trigSnap.rsi14 >= 50.0) {
                        return StrategyTriggerType.PULLBACK_BOUNCE
                    }
                } else if (!isLong && cur.close < cur.open && cur.close < trigSnap.kumoBottom) {
                    if (trigSnap.isBollingerSqueeze && trigSnap.isMarubozuBreakoutBear && trigSnap.rsi14 <= 50.0) {
                        return StrategyTriggerType.EARLY_KATANA_GENESIS
                    }
                    val touchedMidOrEma = listOf(prev1, prev2, cur).any { b ->
                        b.high >= min(trigSnap.bbMiddle20, trigSnap.kijun) - trigSnap.atr14 * 0.35
                    }
                    if (touchedMidOrEma && cur.close <= trigSnap.bbMiddle20 && trigSnap.rsi14 <= 50.0) {
                        return StrategyTriggerType.PULLBACK_BOUNCE
                    }
                }
            }

            BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL -> {
                if (isLong && cur.close > cur.open && cur.close > trigSnap.kumoTop && cur.close >= trigSnap.ema21 - trigSnap.atr14 * 0.10) {
                    if (trigSnap.rsi7Bullish50Bounce || (trigSnap.rsi7 >= 50.0 && trigSnap.prevRsi7 <= 54.0)) {
                        return StrategyTriggerType.PULLBACK_BOUNCE
                    }
                } else if (!isLong && cur.close < cur.open && cur.close < trigSnap.kumoBottom && cur.close <= trigSnap.ema21 + trigSnap.atr14 * 0.10) {
                    if (trigSnap.rsi7Bearish50Bounce || (trigSnap.rsi7 <= 50.0 && trigSnap.prevRsi7 >= 46.0)) {
                        return StrategyTriggerType.PULLBACK_BOUNCE
                    }
                }
            }

            BitcoinStrategyModel.KEENBASE_CROSS_10_30_60 -> {
                if (isLong && cur.close > cur.open && cur.close > trigSnap.kumoTop103060 && trigSnap.tenkan10 >= trigSnap.kijun30) {
                    val touched1030 = listOf(prev1, prev2, prev3, cur).any { b ->
                        b.low <= max(trigSnap.tenkan10, trigSnap.kijun30) + trigSnap.atr14 * 0.35
                    }
                    if (touched1030 && cur.close >= trigSnap.tenkan10) {
                        return StrategyTriggerType.PULLBACK_BOUNCE
                    }
                    if (prev1.close <= trigSnap.kumoTop103060 || prev2.close <= trigSnap.kumoTop103060) {
                        return StrategyTriggerType.VALIDATED_BREAKOUT
                    }
                } else if (!isLong && cur.close < cur.open && cur.close < trigSnap.kumoBottom103060 && trigSnap.tenkan10 <= trigSnap.kijun30) {
                    val touched1030 = listOf(prev1, prev2, prev3, cur).any { b ->
                        b.high >= min(trigSnap.tenkan10, trigSnap.kijun30) - trigSnap.atr14 * 0.35
                    }
                    if (touched1030 && cur.close <= trigSnap.tenkan10) {
                        return StrategyTriggerType.PULLBACK_BOUNCE
                    }
                    if (prev1.close >= trigSnap.kumoBottom103060 || prev2.close >= trigSnap.kumoBottom103060) {
                        return StrategyTriggerType.VALIDATED_BREAKOUT
                    }
                }
            }

            BitcoinStrategyModel.KOONITZ_TRIPLE_SCREEN_FAST_7_22_44 -> {
                if (isLong && cur.close > cur.open && cur.close > trigSnap.kumoTop72244 && trigSnap.tenkan7 >= trigSnap.kijun22) {
                    val touchedFast = listOf(prev1, prev2, prev3, cur).any { b ->
                        b.low <= max(trigSnap.tenkan7, max(trigSnap.kijun22, trigSnap.ema13)) + trigSnap.atr14 * 0.35
                    }
                    if (touchedFast && cur.close >= trigSnap.tenkan7) {
                        return StrategyTriggerType.EARLY_KATANA_GENESIS
                    }
                } else if (!isLong && cur.close < cur.open && cur.close < trigSnap.kumoBottom72244 && trigSnap.tenkan7 <= trigSnap.kijun22) {
                    val touchedFast = listOf(prev1, prev2, prev3, cur).any { b ->
                        b.high >= min(trigSnap.tenkan7, min(trigSnap.kijun22, trigSnap.ema13)) - trigSnap.atr14 * 0.35
                    }
                    if (touchedFast && cur.close <= trigSnap.tenkan7) {
                        return StrategyTriggerType.EARLY_KATANA_GENESIS
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

        // ── Stage 4: World-Class Institutional 1:3+ Chandelier & Tenkan-Kijun Trend-Runner (`>= 2.85R`) ──
        if (currentRMultiple >= 2.85) {
            // ۱. قفل پلکانی سود (Asymptotic R-Ratchet): در 3.0R حداقل +2.27R قفل می‌شود و به ازای هر 1R بالاتر، 82% آن قفل می‌گردد
            val ratchetR = 2.15 + (currentRMultiple - 2.85).coerceAtLeast(0.0) * 0.82
            val ratchetStop = if (isBuy) {
                trade.entry + ratchetR * oneRDistance
            } else {
                trade.entry - ratchetR * oneRDistance
            }

            // ۲. سنگر ساختاری ایچیموکو + شندلیر ATR (در فاز گسترش روند، پشت تنکان با بافر امن؛ در فاز اشباع، تنگ‌تر)
            val tenkanAtrBuffer = if (nearTimeCycleAndFlatTenkan || overextendedFromKijun) {
                trigSnap.atr14 * 0.12
            } else {
                trigSnap.atr14 * 0.26
            }
            val structuralTenkanStop = if (isBuy) {
                trigSnap.tenkan - tenkanAtrBuffer
            } else {
                trigSnap.tenkan + tenkanAtrBuffer
            }

            // ۳. فاصله تنفس ضد نویز (Anti-Noise Breathing Room) تا کندل‌های اصلاحی عادی روند را از دست ندهند
            val minBreathingDist = max(trigSnap.atr14 * 0.35, oneRDistance * 0.25)
            val rawOptimalStop = if (isBuy) {
                val combined = max(ratchetStop, structuralTenkanStop)
                min(combined, livePrice - minBreathingDist)
            } else {
                val combined = min(ratchetStop, structuralTenkanStop)
                max(combined, livePrice + minBreathingDist)
            }
            val candidateStop = round2(rawOptimalStop)

            // ۴. امتداد هوشمند حد سود (TP Horizon Expansion) وقتی قیمت می‌خواهد از ۱:۳ رد شود تا روند از دست نرود
            val distToCurrentTp = if (isBuy) trade.takeProfit - livePrice else livePrice - trade.takeProfit
            val waveTargets = listOfNotNull(report.targetN, report.targetE)
            val nextWaveTp = if (isBuy) {
                waveTargets.filter { it > trade.takeProfit + oneRDistance * 0.5 }.maxOrNull()
            } else {
                waveTargets.filter { it < trade.takeProfit - oneRDistance * 0.5 }.minOrNull()
            }
            val extendedTp: Double? = if (distToCurrentTp <= oneRDistance * 0.75) {
                val projectedRunnerTp = if (isBuy) {
                    trade.entry + max(currentRMultiple + 1.60, 4.50) * oneRDistance
                } else {
                    (trade.entry - max(currentRMultiple + 1.60, 4.50) * oneRDistance).coerceAtLeast(0.00001)
                }
                val chosenTp = if (nextWaveTp != null) {
                    if (isBuy) max(nextWaveTp, projectedRunnerTp) else min(nextWaveTp, projectedRunnerTp)
                } else {
                    projectedRunnerTp
                }
                round2(chosenTp)
            } else {
                null
            }

            val improvesStop = if (isBuy) {
                candidateStop > trade.stopLoss + trigSnap.atr14 * 0.04 && candidateStop < livePrice
            } else {
                candidateStop < trade.stopLoss - trigSnap.atr14 * 0.04 && candidateStop > livePrice
            }
            val improvesTp = extendedTp != null && (if (isBuy) extendedTp > trade.takeProfit + oneRDistance * 0.2 else extendedTp < trade.takeProfit - oneRDistance * 0.2)

            if (improvesStop || improvesTp) {
                val finalStop = if (improvesStop) candidateStop else trade.stopLoss
                val lockedR = round2(abs(finalStop - trade.entry) / oneRDistance)
                val tpNote = if (improvesTp && extendedTp != null) " + امتداد TP به $extendedTp" else ""
                return PreserveModeEvaluation(
                    tradeId = trade.id,
                    newStopLoss = finalStop,
                    stageTitleFa = "🚀 شکارچی روند بالای ۱:۳ (قفل سود +${lockedR}R$tpNote)",
                    reasonFa = String.format(
                        Locale.US,
                        "عبور از ۱:۳ (سود فعلی %.2fR)؛ جایگذاری هوشمند استاپ در +%.2fR با تنفس شندلیر%s",
                        currentRMultiple,
                        lockedR,
                        tpNote,
                    ),
                    newTakeProfit = if (improvesTp) extendedTp else null,
                    lockedRMultiple = lockedR,
                )
            }
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
}

private fun round2(v: Double): Double {
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
