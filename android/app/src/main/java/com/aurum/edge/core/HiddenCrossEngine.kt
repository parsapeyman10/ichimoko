package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import java.util.Locale

/**
 * # موتور «هیدن کراس» (Hidden Cross) و «ابر مخفی» (Hidden Cloud)
 *
 * این فایل قواعد روشی را پیاده می‌کند که مبنایش سه مشاهدهٔ ساده ولی کمتر شناخته‌شده در
 * ایچیموکو است:
 *
 * ۱. **کراس برابری** — وقتی تنکان‌سن و کیجون‌سن کراس می‌کنند، گاهی چند کندل روی هم
 *    می‌افتند (`Tenkan ≈ Kijun`). این «هم‌پوشانی» تصادفی نیست: در همان محدوده دو خط
 *    تعادل بازار یکی شده و از آن پس آن سطح به‌عنوان یک مرز واقعی عمل می‌کند.
 * ۲. **تاج (Crown)** — اندازهٔ همین هم‌پوشانی (بیشترین فاصلهٔ تنکان/کیجون در پنجره) نصف
 *    می‌شود و از سطح کراس به سمت جهت شکست امتداد می‌یابد. آن سطح، «تاج» است: هدف اول و
 *    در بسیاری از موارد مرز برگشت بعدی.
 * ۳. **ابر مخفی** — خودِ نوارِ بین تنکان و کیجون (نه کوموی رسمی) ابری نامرئی است. اگر
 *    هندسهٔ همین نوار در گذشته شبیه چیزی بوده که بعداً قیمت به آن احترام گذاشته،
 *    احتمال تکرار همان رفتار بالاست. «هیدن کراس» فقط **پس از** روشن‌شدن جهت با ابر مخفی
 *    برای تعیین تارگت به‌کار می‌رود — دقیقاً همان ترتیبی که در آموزش اصلی گفته شده است.
 *
 * ## چرا این پیاده‌سازی «اندازه‌گیری‌شده» است
 *
 * بخش سوم (ابر مخفی) یک جست‌وجوی آنالوگ واقعی است: هندسهٔ نوار در `ANALOGUE_WINDOW` کندل
 * اخیر بر حسب ATR نرمال می‌شود، با تمام پنجره‌های تاریخی مقایسه می‌شود و رفتار `ANALOGUE_HORIZON`
 * کندل بعدِ نزدیک‌ترین نمونه‌ها شمرده می‌شود. خروجی همیشه با «تعداد نمونه» گزارش می‌شود؛
 * اگر نمونهٔ کافی نباشد عددی ساخته نمی‌شود.
 *
 * تمام توابع خالص‌اند (بدون وضعیت مشترک) تا مستقل از اندروید تست شوند.
 */
/** یک انطباق تاریخی داخلی برای جست‌وجوی آنالوگ ابر مخفی. */
private data class AnalogueMatch(val distance: Double, val idx: Int, val forwardAtr: Double)

object HiddenCrossEngine {

    /**
     * نتیجهٔ ارزیابی «فقط همان قواعد آموزش اصلی».
     *
     * یک شرط با متن فارسی و وضعیت، تا هم موتور و هم صفحهٔ بک‌تست یک منبع حقیقت داشته باشند.
     */
    data class Rule(
        val index: Int,
        val titleFa: String,
        val passed: Boolean,
        val detailFa: String,
    )

    data class Spec(val conditions: List<Rule>) {
        val allPassed: Boolean get() = conditions.all { it.passed }
    }

    /**
     * ارزیابی هیدن کراس **بدون افزودن شرط**.
     *
     * این تابع عمداً کوچک است. هر شرطی که این‌جا نیست، در آموزش اصلی هم نبوده:
     *
     * | منبع در عکس‌ها | شرط این‌جا |
     * |---|---|
     * | «فاصلهٔ قرمز و آبی همان ابر مخفی است» + دایره‌هایی که نوار باریک است | ۱. ابر مخفی باریک |
     * | «زمانی که با ابر مخفی جهت روند را متوجه شدیم» | ۲. ترتیب تنکان/کیجون هم‌سو + قیمت در سمت روند |
     * | چیکوی سبز و جدا از قیمت در همهٔ نمونه‌ها | ۳. چیکو آزاد |
     * | «مدتی روی هم افتاده‌اند … کراس برابری» | ۴. کراس برابری بالغ (شکست گذشته، بازگشت شروع شده) |
     * | «نصف اندازهٔ کراس … سطحی که تاج می‌شود» + بازگشت قیمت به همان خط‌چین | ۵. لمس تاج و رد شدن از آن |
     *
     * نکتهٔ کلیدی (اشتباه نسخهٔ قبل): معامله روی **شکست** کراس گرفته نمی‌شد؛ در هر
     * نُه تصویر، خط تاج رسم می‌شود، قیمت **برمی‌گردد** به آن و از آن رد می‌شود. شرط
     * «قیمت بیرون نوار» هم همین بازگشت را حذف می‌کرد، چون در پول‌بک قیمت به نوار
     * تنکان/کیجون نزدیک (یا داخل آن) است.
     *
     * @param cloud هندسهٔ ابر مخفی و کراس برابری برای همین کندل.
     * @param long جهت معامله (خرید/فروش).
     * @param tenkanAboveKijun ترتیب تنکان/کیجون؛ باید با جهت هم‌سو باشد.
     * @param chikouFree آیا چیکو آزاد است (از قیمتِ n کندل قبل جدا مانده).
     */
    fun spec(
        cloud: IchimokuBarSnapshot,
        long: Boolean,
        tenkanAboveKijun: Boolean,
        chikouFree: Boolean,
    ): Spec {
        // ۱. ابر مخفی باریک و واقعی — در عکس‌ها نوار در همهٔ نمونه‌های معامله‌شده باریک است.
        val thin = cloud.hiddenCloudThicknessAtr in 0.01..HIDDEN_CLOUD_MAX_THICKNESS_ATR
        // ۲. جهت از ابر مخفی: ترتیب تنکان/کیجون هم‌سو و قیمت در سمت روندِ نوار.
        //    «سمت روند» = میانهٔ نوار، نه بیرونِ آن؛ چون لحظهٔ بازگشت، قیمت داخل نوار است.
        val mid = (cloud.hiddenCloudTop + cloud.hiddenCloudBottom) / 2.0
        val sideOfTrend = if (long) cloud.close >= mid else cloud.close <= mid
        val tkAligned = tenkanAboveKijun == long
        // ۳. چیکو آزاد — در هر ۹ تصویر، خط تأخیر جدا از قیمت رسم شده است.
        // ۴. کراس برابری: باید وجود داشته باشد، **مدت‌دار** شده باشد (شکست پشت سر گذاشته
        //    شده = بازگشت ممکن است) و از عمر تاج بیرون نزده باشد.
        val crossExists = cloud.equalCrossBars >= MIN_EQUAL_CROSS_BARS && cloud.equalCrossLevel > 0.0 &&
            cloud.crownLevel > 0.0
        val crossMatured = crossExists &&
            cloud.equalCrossBarsSinceEnd >= MIN_RETEST_AGE_BARS &&
            cloud.equalCrossBarsSinceEnd <= MAX_EQUAL_CROSS_AGE_BARS
        // ۵. بازگشت به تاج: قیمت به سطح تاج رسیده و کلوز در سمت روند بسته شده (رد شدن).
        //    آستانه‌ها بر حسب ATR هستند تا در m5 و m15 و روی هر نماد یکسان بمانند.
        val atr = if (cloud.atr14 > 0.0) cloud.atr14 else (cloud.close * 0.001).coerceAtLeast(1e-9)
        val crownDistanceAtr = if (crossExists) abs(cloud.close - cloud.crownLevel) / atr else Double.MAX_VALUE
        // «لمس» با دامنهٔ کندل سنجیده می‌شود، نه فقط کلوز: در عکس‌ها سایهٔ کندل خط تاج را
        // می‌زند و کلوز در سمت روند برمی‌گردد. سنجش فقط با کلوز، بیشتر بازگشت‌های واقعی
        // را از دست می‌داد (۴۰ معامله در ۲۲ ماه روی m15).
        val touchBand = CROWN_TOUCH_ATR * atr
        val atCrown = crossExists &&
            cloud.low <= cloud.crownLevel + touchBand &&
            cloud.high >= cloud.crownLevel - touchBand
        val rejected = crossExists && if (long) {
            cloud.close >= cloud.crownLevel - CROWN_REJECT_ATR * atr
        } else {
            cloud.close <= cloud.crownLevel + CROWN_REJECT_ATR * atr
        }

        return Spec(
            listOf(
                Rule(
                    1,
                    "۱. ابر مخفی باریک (فاصلهٔ تنکان از کیجون)",
                    thin,
                    String.format(
                        Locale.US,
                        "ضخامت نوار=%.2f ATR · سقف %.2f ATR",
                        cloud.hiddenCloudThicknessAtr,
                        HIDDEN_CLOUD_MAX_THICKNESS_ATR,
                    ),
                ),
                Rule(
                    2,
                    "۲. جهت از ابر مخفی (ترتیب تنکان/کیجون + قیمت در سمت روند)",
                    tkAligned && sideOfTrend,
                    String.format(
                        Locale.US,
                        "نوار=%.2f..%.2f · کلوز=%.2f · TK=%s",
                        cloud.hiddenCloudBottom,
                        cloud.hiddenCloudTop,
                        cloud.close,
                        if (tenkanAboveKijun) "تنکان بالای کیجون" else "تنکان زیر کیجون",
                    ),
                ),
                Rule(
                    3,
                    "۳. آزادی چیکو (خط تأخیر جدا از قیمت)",
                    chikouFree,
                    if (chikouFree) "چیکو آزاد است" else "چیکو به قیمت گذشته چسبیده",
                ),
                Rule(
                    4,
                    "۴. کراس برابری بالغ (هم‌پوشانی گذشته، بازگشت ممکن)",
                    crossMatured,
                    if (crossExists) {
                        String.format(
                            Locale.US,
                            "سطح برابری=%.2f · %d کندل هم‌پوشانی · %d کندل از پایان",
                            cloud.equalCrossLevel,
                            cloud.equalCrossBars,
                            cloud.equalCrossBarsSinceEnd,
                        )
                    } else {
                        "هم‌پوشانی کافی تنکان/کیجون یافت نشد"
                    },
                ),
                Rule(
                    5,
                    "۵. بازگشت قیمت به تاج و رد شدن از آن",
                    atCrown && rejected,
                    if (crossExists) {
                        String.format(
                            Locale.US,
                            "تاج=%.2f · کلوز=%.2f · فاصله=%.2f ATR (سقف %.2f)",
                            cloud.crownLevel,
                            cloud.close,
                            crownDistanceAtr,
                            CROWN_TOUCH_ATR,
                        )
                    } else {
                        "کراس برابری ساخته نشده، پس تاجی هم نیست"
                    },
                ),
            ),
        )
    }


    /** آستانهٔ «برابری» تنکان و کیجون بر حسب ATR. */
    const val EQUAL_CROSS_TOLERANCE_ATR: Double = 0.06

    /** کمترین تعداد کندل هم‌پوشانی برای اینکه «کراس برابری» شمرده شود. */
    const val MIN_EQUAL_CROSS_BARS: Int = 3

    /** ضریب امتداد تاج: نصف اندازهٔ کراس. */
    const val CROWN_EXTENSION_RATIO: Double = 0.5

    /**
     * حداقل فاصلهٔ کراس برابری از کندل جاری تا «بازگشت به تاج» معنا داشته باشد.
     *
     * در عکس‌های آموزش، معامله **روی خود کراس** گرفته نمی‌شود؛ سطح تاج رسم می‌شود و
     * بعد قیمت چند کندل بعد به آن برمی‌گردد و از آن رد می‌شود. اگر سن کراس کمتر از این
     * باشد، هنوز «شکست» است نه «بازگشت»، پس معامله‌ای هم در کار نیست.
     */
    const val MIN_RETEST_AGE_BARS: Int = 5

    /**
     * حداکثر عمر تاج (کندل): سطح کراس برابری تا این مدت اعتبار دارد.
     *
     * پیش از این ۳۰ بود؛ در عکس‌ها خط تاج تا ده‌ها کندل بعد کشیده می‌شود و همان‌جا
     * واکنش می‌گیرد. با ۳۰، بیشتر بازگشت‌های واقعی از دست می‌رفت.
     */
    const val MAX_EQUAL_CROSS_AGE_BARS: Int = 120

    /** فاصلهٔ مجاز قیمت از سطح تاج برای اینکه «لمس سطح» شمرده شود (ATR). */
    const val CROWN_TOUCH_ATR: Double = 0.35

    /** بسته‌شدن کندل در سمت روند نسبت به تاج، به‌عنوان تأیید برگشت (ATR). */
    const val CROWN_REJECT_ATR: Double = 0.05

    /** ضخامت حداکثر ابر مخفی بر حسب ATR؛ ضخیم‌تر از این دیگر «نوار تعادل» نیست. */
    const val HIDDEN_CLOUD_MAX_THICKNESS_ATR: Double = 0.55

    /** پنجرهٔ شکل ابر مخفی برای مقایسهٔ آنالوگ. */
    const val ANALOGUE_WINDOW: Int = 8

    /** افق آیندهٔ نمونه‌های تاریخی (کندل). */
    const val ANALOGUE_HORIZON: Int = 10

    /** حداکثر پنجرهٔ جست‌وجوی آنالوگ در گذشته (کندل). */
    const val ANALOGUE_LOOKBACK: Int = 240

    /** کمترین تعداد نمونهٔ آنالوگ برای اعلام احتمال. */
    const val ANALOGUE_MIN_SAMPLES: Int = 6

    /** سهم هم‌جهت لازم از نمونه‌های آنالوگ. */
    const val ANALOGUE_MIN_AGREEMENT: Double = 0.55

    /** حداقل فاصلهٔ هدف «تاج» بر حسب R برای اینکه فضا برای معامله کافی باشد. */
    const val HIDDEN_CROSS_MIN_TARGET_R: Double = 1.50

    /** کف کندل بسته برای ارزیابی. */
    const val MIN_CLOSED_BARS: Int = 60

    // ─────────────────────────── مدل‌های خروجی ───────────────────────────

    /** یک «کراس برابری» شناسایی‌شده و سطح تاجِ حاصل از آن. */
    data class EqualCross(
        /** سطح برابری تنکان/کیجون (میانهٔ دو خط در طول هم‌پوشانی). */
        val levelPrice: Double,
        /** تعداد کندل هم‌پوشانی. */
        val bars: Int,
        /** «اندازهٔ کراس»: بیشترین فاصلهٔ تنکان/کیجون در پنجرهٔ هم‌پوشانی، بر حسب ATR. */
        val crossSizeAtr: Double,
        /** چند کندل از پایان هم‌پوشانی گذشته است. */
        val barsSinceEnd: Int,
        /** جهت خروج قیمت از ناحیهٔ برابری. */
        val breakoutDirection: SignalAction,
        /** سطح «تاج»: نصف اندازهٔ کراس، امتداد از سطح برابری در جهت شکست. */
        val crownPrice: Double,
    ) {
        val isFresh: Boolean get() = barsSinceEnd <= MAX_EQUAL_CROSS_AGE_BARS
    }

    /** سطح تاج واقع در مسیر معامله (هدف اول یا مانع). */
    data class CrownLevel(
        val price: Double,
        val distanceAtr: Double,
        val kindFa: String,
        val fromBarsAgo: Int,
    )

    /** وضعیت «ابر مخفی» (نوار بین تنکان و کیجون). */
    data class HiddenCloudState(
        val top: Double,
        val bottom: Double,
        /** ضخامت نوار بر حسب ATR. */
        val thicknessAtr: Double,
        /** آیا ضخامت در محدودهٔ «تعادل» است (ابر مخفی واقعی، نه فاصلهٔ زیاد). */
        val isBalanced: Boolean,
        /** شیب نوار بر حسب ATR در هر کندل. */
        val slopeAtrPerBar: Double,
        /** جایگاه قیمت: `ABOVE` / `BELOW` / `INSIDE`. */
        val priceZone: PriceZone,
        val descriptionFa: String,
    ) {
        enum class PriceZone { ABOVE, BELOW, INSIDE }
    }

    /**
     * نتیجهٔ جست‌وجوی آنالوگ تاریخی.
     *
     * عددها فقط وقتی معنا دارند که `samples >= ANALOGUE_MIN_SAMPLES` باشد؛ در غیر این صورت
     * `isMeasured = false` و هیچ احتمالی گزارش نمی‌شود.
     */
    data class AnalogueResult(
        val samples: Int,
        val sameDirectionSamples: Int,
        val meanForwardAtr: Double,
        val matchedAtBarsAgo: List<Int>,
        val noteFa: String,
    ) {
        val isMeasured: Boolean get() = samples >= ANALOGUE_MIN_SAMPLES
        val agreementShare: Double
            get() = if (samples <= 0) 0.0 else sameDirectionSamples.toDouble() / samples.toDouble()
    }

    /** خروجی کامل ارزیابی هیدن کراس برای یک سری کندل. */
    data class Evaluation(
        val equalCrosses: List<EqualCross>,
        val activeCross: EqualCross?,
        val crowns: List<CrownLevel>,
        val hiddenCloud: HiddenCloudState?,
        val analogues: AnalogueResult,
        val direction: SignalAction,
        val latestTenkan: Double,
        val latestKijun: Double,
        val atr14: Double,
        val reasonFa: String,
    ) {
        val hasFreshCross: Boolean get() = activeCross?.isFresh == true
    }

    // ─────────────────────────── محاسبهٔ اصلی ───────────────────────────

    /**
     * ارزیابی کامل روش روی کندل‌های **بسته**.
     *
     * @param candles فهرست کندل‌ها (مرتب بر اساس زمان)؛ کندل باز نادیده گرفته می‌شود.
     */
    fun evaluate(
        candles: List<Candle>,
        config: BaseStrategyConfig = BaseStrategyConfig(),
    ): Evaluation {
        val bars = sanitize(candles)
        if (bars.size < MIN_CLOSED_BARS) {
            return Evaluation(
                equalCrosses = emptyList(),
                activeCross = null,
                crowns = emptyList(),
                hiddenCloud = null,
                analogues = AnalogueResult(
                    samples = 0,
                    sameDirectionSamples = 0,
                    meanForwardAtr = 0.0,
                    matchedAtBarsAgo = emptyList(),
                    noteFa = "کندل بستهٔ کافی برای محاسبهٔ ابر مخفی وجود ندارد (${bars.size}/${MIN_CLOSED_BARS}).",
                ),
                direction = SignalAction.NO_TRADE,
                latestTenkan = 0.0,
                latestKijun = 0.0,
                atr14 = 0.0,
                reasonFa = "دادهٔ ناکافی: حداقل $MIN_CLOSED_BARS کندل بسته لازم است.",
            )
        }

        val tenkan = lineSeries(bars, config.tenkanPeriod)
        val kijun = lineSeries(bars, config.kijunPeriod)
        val atr = atrSeries(bars, 14)

        val lastIdx = bars.lastIndex
        val lastAtr = atr[lastIdx].coerceAtLeast(bars[lastIdx].close * 0.0005)

        val crosses = findEqualCrosses(bars, tenkan, kijun, atr, config)
        // کراس «فعال» = آخرین کراس بالغ (که بازگشت به تاجش ممکن است)، وگرنه آخرین کراس.
        val active = crosses.lastOrNull { it.barsSinceEnd >= MIN_RETEST_AGE_BARS } ?: crosses.lastOrNull()
        val cloud = hiddenCloudState(bars, tenkan, kijun, atr, lastIdx)
        val analogues = findAnalogues(bars, tenkan, kijun, atr)

        // جهت تصمیم: ابر مخفی اول، کراس برابری بعد — همان ترتیبی که در روش آموزش داده شده.
        val direction = decideDirection(
            bars = bars,
            tenkan = tenkan,
            kijun = kijun,
            cloud = cloud,
            activeCross = active,
            analogues = analogues,
            lastIdx = lastIdx,
            atrLast = lastAtr,
        )
        val crowns = crownLevels(
            crosses = crosses,
            lastPrice = bars[lastIdx].close,
            lastAtr = lastAtr,
            lastIdx = lastIdx,
            long = direction == SignalAction.BUY,
        )
        val reason = buildReasonFa(direction, cloud, active, crossroads = crosses.size, analogues, crowns)

        return Evaluation(
            equalCrosses = crosses,
            activeCross = active,
            crowns = crowns,
            hiddenCloud = cloud,
            analogues = analogues,
            direction = direction,
            latestTenkan = round2(tenkan[lastIdx]),
            latestKijun = round2(kijun[lastIdx]),
            atr14 = round2(lastAtr),
            reasonFa = reason,
        )
    }

    /**
     * سطوح «تاج» در مسیر یک معامله.
     *
     * تاجِ هر کراس برابری دو سطح دارد: سطح کراس و امتداد نصف‌اندازه. هر دو در جهت معامله
     * به‌عنوان هدف/مانع استفاده می‌شوند.
     */
    fun crownLevels(
        crosses: List<EqualCross>,
        lastPrice: Double,
        lastAtr: Double,
        lastIdx: Int,
        long: Boolean,
    ): List<CrownLevel> {
        if (crosses.isEmpty() || lastAtr <= 0.0) return emptyList()
        val out = ArrayList<CrownLevel>()
        crosses.takeLast(6).forEach { cross ->
            val candidates = listOf(
                cross.levelPrice to "سطح کراس برابری (${cross.bars} کندل هم‌پوشانی)",
                cross.crownPrice to "تاج نصف‌اندازهٔ کراس",
            )
            candidates.forEach { (price, kind) ->
                if (!price.isFinite() || price <= 0.0) return@forEach
                val ahead = if (long) price > lastPrice + lastAtr * 0.05 else price < lastPrice - lastAtr * 0.05
                if (!ahead) return@forEach
                out += CrownLevel(
                    price = round2(price),
                    distanceAtr = round4(abs(price - lastPrice) / lastAtr),
                    kindFa = kind,
                    fromBarsAgo = cross.barsSinceEnd,
                )
            }
        }
        return out
            .distinctBy { round2(it.price) }
            .sortedBy { it.distanceAtr }
    }

    // ─────────────────────────── شناسایی کراس برابری ───────────────────────────

    /**
     * تمام «کراس‌های برابری» یک سری.
     *
     * روش: در هر کندل فاصلهٔ |تنکان−کیجون| با آستانهٔ `EQUAL_CROSS_TOLERANCE_ATR × ATR`
     * سنجیده می‌شود. هر بازهٔ پیوستهٔ دست‌کم `MIN_EQUAL_CROSS_BARS` کندلی یک کراس برابری است.
     */
    private fun findEqualCrosses(
        bars: List<Candle>,
        tenkan: DoubleArray,
        kijun: DoubleArray,
        atr: DoubleArray,
        config: BaseStrategyConfig,
    ): List<EqualCross> {
        val out = ArrayList<EqualCross>()
        val lastIdx = bars.lastIndex
        var i = 0
        while (i <= lastIdx) {
            val a = atr[i].coerceAtLeast(bars[i].close * 0.0005)
            val tolerance = a * EQUAL_CROSS_TOLERANCE_ATR * toleranceScale(config)
            val gap = abs(tenkan[i] - kijun[i])
            if (gap > tolerance) {
                i++
                continue
            }
            // بازهٔ هم‌پوشانی را تا جایی که فاصله زیر آستانه می‌ماند جلو ببر.
            var j = i
            var maxGap = gap
            var levelSum = 0.0
            var levelCount = 0
            while (j <= lastIdx) {
                val aj = atr[j].coerceAtLeast(bars[j].close * 0.0005)
                val tol = aj * EQUAL_CROSS_TOLERANCE_ATR * toleranceScale(config)
                val g = abs(tenkan[j] - kijun[j])
                if (g > tol) break
                maxGap = max(maxGap, g)
                levelSum += (tenkan[j] + kijun[j]) / 2.0
                levelCount++
                j++
            }
            val length = j - i
            if (length >= MIN_EQUAL_CROSS_BARS) {
                val level = levelSum / levelCount.coerceAtLeast(1)
                val sizeAtr = maxGap / atr[i].coerceAtLeast(bars[i].close * 0.0005)
                val barsSinceEnd = lastIdx - (j - 1)
                val direction = breakoutDirection(bars, tenkan, kijun, endIdx = j - 1, level = level, lastIdx = lastIdx)
                out += EqualCross(
                    levelPrice = level,
                    bars = length,
                    crossSizeAtr = round4(sizeAtr),
                    barsSinceEnd = barsSinceEnd,
                    breakoutDirection = direction,
                    crownPrice = crownPriceFor(level, sizeAtr, atr[i].coerceAtLeast(bars[i].close * 0.0005), direction),
                )
            }
            i = max(j, i + 1)
        }
        return out
    }

    /**
     * سطح «تاج»: امتداد **نصفِ** اندازهٔ کراس از سطح برابری در جهت شکست.
     *
     * این تابع قلب روش است و عمداً عمومی است تا مستقل از دادهٔ بازار تست شود.
     */
    fun crownPriceFor(level: Double, crossSizeAtr: Double, atrAtCross: Double, direction: SignalAction): Double {
        val extension = atrAtCross * crossSizeAtr * CROWN_EXTENSION_RATIO
        return when (direction) {
            SignalAction.BUY -> level + extension
            SignalAction.SELL -> (level - extension).coerceAtLeast(0.0)
            SignalAction.NO_TRADE -> level
        }
    }

    /** جهت خروج از ناحیهٔ برابری: اولین کلوزِ معنادار پس از بازه. */
    private fun breakoutDirection(
        bars: List<Candle>,
        tenkan: DoubleArray,
        kijun: DoubleArray,
        endIdx: Int,
        level: Double,
        lastIdx: Int,
    ): SignalAction {
        for (k in endIdx + 1..lastIdx) {
            val a = abs(tenkan[k] - kijun[k])
            val close = bars[k].close
            if (close > level && tenkan[k] > kijun[k] && a > 0.0) return SignalAction.BUY
            if (close < level && tenkan[k] < kijun[k] && a > 0.0) return SignalAction.SELL
        }
        // بعد از برابری هنوز جهت قطعی نشده: سمت قیمت نسبت به سطح
        return when {
            bars[lastIdx].close > level -> SignalAction.BUY
            bars[lastIdx].close < level -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
    }

    // ─────────────────────────── ابر مخفی ───────────────────────────

    private fun hiddenCloudState(
        bars: List<Candle>,
        tenkan: DoubleArray,
        kijun: DoubleArray,
        atr: DoubleArray,
        idx: Int,
    ): HiddenCloudState {
        val top = max(tenkan[idx], kijun[idx])
        val bottom = min(tenkan[idx], kijun[idx])
        val a = atr[idx].coerceAtLeast(bars[idx].close * 0.0005)
        val thicknessAtr = (top - bottom) / a
        val prev = (idx - 3).coerceAtLeast(0)
        val midNow = (top + bottom) / 2.0
        val midPrev = (max(tenkan[prev], kijun[prev]) + min(tenkan[prev], kijun[prev])) / 2.0
        val slope = (midNow - midPrev) / (3.0 * a)
        val close = bars[idx].close
        val zone = when {
            close > top -> HiddenCloudState.PriceZone.ABOVE
            close < bottom -> HiddenCloudState.PriceZone.BELOW
            else -> HiddenCloudState.PriceZone.INSIDE
        }
        val balanced = thicknessAtr <= HIDDEN_CLOUD_MAX_THICKNESS_ATR
        val desc = buildString {
            append("نوار تعادل تنکان/کیجون: ${round2(bottom)}..${round2(top)}")
            append(" · ضخامت ${round2(thicknessAtr)} ATR")
            append(if (balanced) " (ابر مخفی باریک و قابل احترام)" else " (باز شده؛ دیگر نوار تعادل نیست)")
            append(" · شیب ${String.format(java.util.Locale.US, "%+.2f", slope)} ATR/کندل")
            append(
                when (zone) {
                    HiddenCloudState.PriceZone.ABOVE -> " · قیمت بالای ابر مخفی"
                    HiddenCloudState.PriceZone.BELOW -> " · قیمت زیر ابر مخفی"
                    HiddenCloudState.PriceZone.INSIDE -> " · قیمت داخل ابر مخفی (بی‌تصمیم)"
                },
            )
        }
        return HiddenCloudState(
            top = round2(top),
            bottom = round2(bottom),
            thicknessAtr = round4(thicknessAtr),
            isBalanced = balanced,
            slopeAtrPerBar = round4(slope),
            priceZone = zone,
            descriptionFa = desc,
        )
    }

    // ─────────────────────────── آنالوگ تاریخی ───────────────────────────

    /**
     * جست‌وجوی نزدیک‌ترین هندسه‌های ابر مخفی در گذشته.
     *
     * بردار شکل: برای هر کندلِ پنجره، `(close − میانهٔ نوار) / ATR` و `(بالا−پایین) / ATR`.
     * فاصلهٔ اقلیدسی نرمال‌شده گرفته می‌شود؛ `ANALOGUE_MIN_SAMPLES` نزدیک‌ترین نمونه‌ها
     * انتخاب و رفتار `ANALOGUE_HORIZON` کندل بعدشان بر حسب ATR شمرده می‌شود.
     */
    private fun findAnalogues(
        bars: List<Candle>,
        tenkan: DoubleArray,
        kijun: DoubleArray,
        atr: DoubleArray,
    ): AnalogueResult {
        val lastIdx = bars.lastIndex
        val window = ANALOGUE_WINDOW
        val horizon = ANALOGUE_HORIZON
        if (lastIdx < window + horizon + 5) {
            return AnalogueResult(0, 0, 0.0, emptyList(), "پنجرهٔ تاریخی کافی برای مقایسهٔ ابر مخفی وجود ندارد.")
        }

        fun atrAt(i: Int) = atr[i].coerceAtLeast(bars[i].close * 0.0005)

        val currentVec = DoubleArray(window * 2)
        for (k in 0 until window) {
            val idx = lastIdx - window + 1 + k
            val a = atrAt(idx)
            val top = max(tenkan[idx], kijun[idx])
            val bottom = min(tenkan[idx], kijun[idx])
            currentVec[k * 2] = (bars[idx].close - (top + bottom) / 2.0) / a
            currentVec[k * 2 + 1] = (top - bottom) / a
        }

        val matches = ArrayList<AnalogueMatch>()
        val oldest = max(window, lastIdx - ANALOGUE_LOOKBACK)
        for (end in oldest..(lastIdx - horizon - 1)) {
            var dist = 0.0
            var valid = true
            for (k in 0 until window) {
                val idx = end - window + 1 + k
                val a = atrAt(idx)
                val top = max(tenkan[idx], kijun[idx])
                val bottom = min(tenkan[idx], kijun[idx])
                val v1 = (bars[idx].close - (top + bottom) / 2.0) / a
                val v2 = (top - bottom) / a
                val d1 = v1 - currentVec[k * 2]
                val d2 = v2 - currentVec[k * 2 + 1]
                dist += d1 * d1 + d2 * d2
                if (!dist.isFinite()) {
                    valid = false
                    break
                }
            }
            if (!valid) continue
            val forward = (bars[end + horizon].close - bars[end].close) / atrAt(end)
            matches += AnalogueMatch(dist, end, forward)
        }

        if (matches.isEmpty()) {
            return AnalogueResult(0, 0, 0.0, emptyList(), "هیچ نمونهٔ تاریخی هم‌شکل با ابر مخفی فعلی پیدا نشد.")
        }

        val top = matches.sortedBy { it.distance }.take(ANALOGUE_MIN_SAMPLES * 2)
        val meanForward = top.map { it.forwardAtr }.average()
        val sameDirection = top.count {
            if (meanForward >= 0.0) it.forwardAtr > 0.0 else it.forwardAtr < 0.0
        }
        val note = if (top.size >= ANALOGUE_MIN_SAMPLES) {
            String.format(
                java.util.Locale.US,
                "آنالوگ ابر مخفی: %d نمونهٔ هم‌شکل · میانگین حرکت %+.2f ATR در %d کندل · هم‌جهتی %d/%d (%.0f%%)",
                top.size,
                meanForward,
                horizon,
                sameDirection,
                top.size,
                sameDirection * 100.0 / top.size,
            )
        } else {
            "نمونهٔ هم‌شکل کافی نیست (${top.size}/$ANALOGUE_MIN_SAMPLES)؛ احتمال جهت اعلام نمی‌شود."
        }
        return AnalogueResult(
            samples = top.size,
            sameDirectionSamples = sameDirection,
            meanForwardAtr = round4(meanForward),
            matchedAtBarsAgo = top.map { lastIdx - it.idx }.sorted(),
            noteFa = note,
        )
    }

    // ─────────────────────────── تصمیم ───────────────────────────

    private fun decideDirection(
        bars: List<Candle>,
        tenkan: DoubleArray,
        kijun: DoubleArray,
        cloud: HiddenCloudState,
        activeCross: EqualCross?,
        analogues: AnalogueResult,
        lastIdx: Int,
        atrLast: Double,
    ): SignalAction {
        val close = bars[lastIdx].close
        val tkBullish = tenkan[lastIdx] > kijun[lastIdx]
        val mid = (cloud.top + cloud.bottom) / 2.0

        // ۱. گام اول روش: جهت با ابر مخفی (ترتیب TK + سمت روند). «بیرون نوار بودن» شرط نیست،
        //    چون لحظهٔ بازگشت به تاج قیمت به نوار نزدیک می‌شود.
        val cloudBias = when {
            tkBullish && close >= mid && cloud.slopeAtrPerBar >= -0.01 -> SignalAction.BUY
            !tkBullish && close <= mid && cloud.slopeAtrPerBar <= 0.01 -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        if (cloudBias == SignalAction.NO_TRADE) return SignalAction.NO_TRADE

        // ۲. گام دوم: کراس برابری باید بالغ باشد و در جهت ابر مخفی.
        val cross = activeCross ?: return SignalAction.NO_TRADE
        if (cross.barsSinceEnd < MIN_RETEST_AGE_BARS || !cross.isFresh) return SignalAction.NO_TRADE
        if (cross.breakoutDirection != cloudBias && cross.breakoutDirection != SignalAction.NO_TRADE) {
            return SignalAction.NO_TRADE
        }

        // ۳. گام سوم: قیمت به سطح تاج برگشته و از آن رد شده است (قلب روش).
        val atrLast = atrLast.coerceAtLeast(close * 0.0005)
        val touchBand = CROWN_TOUCH_ATR * atrLast
        val touched = bars[lastIdx].low <= cross.crownPrice + touchBand &&
            bars[lastIdx].high >= cross.crownPrice - touchBand
        if (!touched) return SignalAction.NO_TRADE
        val rejected = if (cloudBias == SignalAction.BUY) {
            close >= cross.crownPrice - CROWN_REJECT_ATR * atrLast
        } else {
            close <= cross.crownPrice + CROWN_REJECT_ATR * atrLast
        }
        if (!rejected) return SignalAction.NO_TRADE

        // ۴. تأیید آنالوگ: اگر اندازه‌گیری ممکن نیست، تصمیم با ابر مخفی می‌ماند (بی‌ادعا).
        if (analogues.isMeasured) {
            val up = analogues.meanForwardAtr >= 0.0
            val agrees = if (cloudBias == SignalAction.BUY) up else !up
            if (!agrees || analogues.agreementShare < ANALOGUE_MIN_AGREEMENT) return SignalAction.NO_TRADE
        }
        return cloudBias
    }

    private fun buildReasonFa(
        direction: SignalAction,
        cloud: HiddenCloudState?,
        activeCross: EqualCross?,
        crossroads: Int,
        analogues: AnalogueResult,
        crowns: List<CrownLevel>,
    ): String = buildString {
        when (direction) {
            SignalAction.BUY -> append("🟢 جهت هیدن کراس: خرید")
            SignalAction.SELL -> append("🔴 جهت هیدن کراس: فروش")
            SignalAction.NO_TRADE -> append("⚪ هیدن کراس: بدون تصمیم")
        }
        cloud?.let { append(" · ").append(it.descriptionFa) }
        if (activeCross != null) {
            append(
                String.format(
                    java.util.Locale.US,
                    " · کراس برابری: سطح %.2f با %d کندل هم‌پوشانی (اندازه %.2f ATR) · تاج %.2f",
                    activeCross.levelPrice,
                    activeCross.bars,
                    activeCross.crossSizeAtr,
                    activeCross.crownPrice,
                ),
            )
        } else {
            append(" · هنوز کراس برابری شکل نگرفته است (مجموع یافت‌شده: $crossroads)")
        }
        append(" · ").append(analogues.noteFa)
        crowns.firstOrNull()?.let {
            append(String.format(java.util.Locale.US, " · نزدیک‌ترین تاج در مسیر: %.2f (%.2f ATR)", it.price, it.distanceAtr))
        }
    }

    // ─────────────────────────── ابزار پایه ───────────────────────────

    private fun toleranceScale(config: BaseStrategyConfig): Double {
        val periodFactor = config.kijunPeriod.toDouble() / 26.0
        return periodFactor.coerceIn(0.75, 1.35)
    }

    private fun sanitize(candles: List<Candle>): List<Candle> = candles
        .filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.high >= it.low && it.close > 0.0 }
        .sortedBy { it.time }
        .filter { it.closed }
        .ifEmpty { candles.sortedBy { it.time } }

    /** میانهٔ (بالاترین/پایین‌ترین) در پنجرهٔ دوره — همان تعریف تنکان/کیجون. */
    private fun lineSeries(bars: List<Candle>, period: Int): DoubleArray {
        val p = period.coerceAtLeast(1)
        val out = DoubleArray(bars.size)
        for (i in bars.indices) {
            val start = (i - p + 1).coerceAtLeast(0)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (k in start..i) {
                if (bars[k].high > hh) hh = bars[k].high
                if (bars[k].low < ll) ll = bars[k].low
            }
            out[i] = if (hh.isFinite() && ll.isFinite()) (hh + ll) / 2.0 else bars[i].close
        }
        return out
    }

    /** ATR وایلدر با دورهٔ مشخص. */
    private fun atrSeries(bars: List<Candle>, period: Int): DoubleArray {
        val p = period.coerceAtLeast(1)
        val out = DoubleArray(bars.size)
        val tr = DoubleArray(bars.size)
        for (i in bars.indices) {
            val prevClose = bars[(i - 1).coerceAtLeast(0)].close
            tr[i] = maxOf(
                bars[i].high - bars[i].low,
                abs(bars[i].high - prevClose),
                abs(bars[i].low - prevClose),
            )
        }
        var sum = 0.0
        for (i in bars.indices) {
            sum += tr[i]
            if (i >= p) sum -= tr[i - p]
            out[i] = (sum / min(i + 1, p).toDouble()).coerceAtLeast(bars[i].close * 0.0005)
        }
        return out
    }

    private fun round2(v: Double): Double = if (!v.isFinite()) 0.0 else round(v * 100.0) / 100.0

    /**
     * نسبت‌های ATR با چهار رقم اعشار نگه داشته می‌شوند: در بازار آرام اندازهٔ «کراس برابری»
     * می‌تواند ۰٫۰۰۴ ATR باشد و گرد کردن به دو رقم آن را صفر می‌کرد (تاج بی‌معنا می‌شد).
     */
    private fun round4(v: Double): Double = if (!v.isFinite()) 0.0 else round(v * 10_000.0) / 10_000.0
}
