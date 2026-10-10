package com.aurum.edge.core

import com.aurum.edge.engine.Indicators
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * «کدام روش، برای کدام بازار، در چه ساعتی» — the per-market method router.
 *
 * چرا این فایل وجود دارد: یک موتور واحد نمی‌تواند هم‌زمان برای طلا، فارکس، رمزارز، سهام و نفت
 * درست باشد. ساختار این بازارها با هم فرق می‌کند:
 *  - **هزینهٔ واقعی** رفت‌وبرگشت: فارکس اصلی ≈ ۰٫۷bps · طلا ≈ ۰٫۵bps · سهام ≈ ۰٫۵bps ·
 *    رمزارز ≈ ۲۱bps (۰٫۱٪ در هر سمت). یعنی اسکالپ رمزارز باید حرکتی ده‌ها برابر بزرگ‌تر از
 *    اسکالپ فارکس داشته باشد تا همان نسبت «سود به هزینه» را بدهد.
 *  - **سشن نقدشوندگی**: فارکس/طلا در هم‌پوشانی لندن–نیویورک جهت‌دار و در آسیا رنج است؛
 *    سهام فقط ۰۹:۳۰–۱۶:۰۰ نیویورک؛ رمزارز ۲۴/۷ ولی آخر هفته نازک‌تر؛ نفت حول گزارش موجودی.
 *  - **اهرم قانونی** (ESMA): ۳۰/۲۰/۱۰/۵/۲ → یعنی مارجین و فاصلهٔ استاپ مجاز فرق می‌کند.
 *
 * این ماژول هیچ پیش‌بینی‌ای نمی‌کند؛ فقط از دادهٔ واقعی (کندل‌های بسته + ساعت واقعی سشن‌ها +
 * مشخصات واقعی بازار) تصمیم می‌گیرد کدام **روش** مجاز است و با چه پارامترهایی. وقتی زمان/بازار
 * مناسب نیست، خروجی صریحاً `STAND_ASIDE` با دلیل فارسی است. این خوانش در V1
 * فقط زمینه/رتبه‌بندی است، نه گیت مستقلِ ورود؛ مرجع ورود SignalEngine و قفل‌های ریسک است.
 */

/** خانوادهٔ بازار؛ دقیق‌تر از [AssetClass] چون روش معاملهٔ طلا با گاز طبیعی یکی نیست. */
enum class MarketFamily(val label: String) {
    GOLD("طلا"),
    SILVER("نقره"),
    OIL("نفت"),
    NATGAS("گاز طبیعی"),
    COPPER("مس"),
    FX_MAJOR("فارکس اصلی"),
    FX_MINOR("فارکس غیراصلی/کراس"),
    CRYPTO("رمزارز"),
    US_STOCK("سهام آمریکا"),
    INDEX("شاخص"),
    OTHER("سایر"),
}

/** روشی که برای این بازار/لحظه مجاز است. */
enum class TradeMethod(val label: String) {
    /** ادامهٔ روند با پولبک به کیجون/ابر — روش اصلی ایچیموکو. */
    TREND_PULLBACK("ادامهٔ روند با پولبک"),
    /** شکست سطح + مومنتوم با تأیید حجم. */
    BREAKOUT_MOMENTUM("شکست و مومنتوم"),
    /** بازگشت به میانگین داخل یک رنج مشخص (فقط جایی که اسپرد واقعاً کم است). */
    RANGE_MEAN_REVERSION("بازگشت به میانگین در رنج"),
    /** درایو بازگشایی سهام: ۶۰ دقیقهٔ اول سشن نیویورک. */
    OPENING_DRIVE("درایو بازگشایی سهام"),
    /** ورود ممنوع: زمان/نقدشوندگی/رژیم مناسب این بازار نیست. */
    STAND_ASIDE("ورود ممنوع (زمان نامناسب)"),
}

enum class MarketSession(val label: String) {
    SYDNEY("سیدنی"),
    TOKYO("توکیو/آسیا"),
    LONDON("لندن"),
    NEW_YORK("نیویورک"),
    LONDON_NY_OVERLAP("هم‌پوشانی لندن–نیویورک"),
    ROLLOVER("رول‌اور ۱۷:۰۰ نیویورک"),
    EQUITY_OPEN("بازگشایی سهام ۰۹:۳۰–۱۰:۳۰"),
    EQUITY_CLOSE("دقایق پایانی سهام ۱۵:۳۰–۱۶:۰۰"),
    OFF_HOURS("خارج از سشن اصلی"),
    WEEKEND("پایان هفته"),
    CRYPTO_24_7("۲۴/۷ کریپتو"),
}

enum class RegimeKind(val label: String) {
    TREND("رونددار"),
    RANGE("رنج"),
    MIXED("مختلط/بدون برتری"),
    SPIKE("جهش نوسان"),
    DEAD("بی‌جان"),
    UNKNOWN("دادهٔ کافی نیست"),
}

/** نتیجهٔ ارزیابی: روش مجاز + پارامترهای واقعی + دلیل فارسی. هیچ عدد ساختگی‌ای ندارد. */
data class PlaybookDecision(
    val symbol: String,
    val family: MarketFamily,
    val session: MarketSession,
    val regime: RegimeKind,
    val method: TradeMethod,
    val allowed: Boolean,
    /** کف امتیاز موتور برای این بازار/سشن (موتور به‌طور پایه ۷۵ می‌خواهد). */
    val minScore: Int,
    val minConfidence: Double,
    val rrMin: Double,
    val rrMax: Double,
    val stopAtrMin: Double,
    val stopAtrMax: Double,
    /** کف سود مورد انتظار، بر حسب bps از قیمت. */
    val minRewardBps: Double,
    /** سود باید حداقل این چند برابر هزینهٔ واقعی رفت‌وبرگشت باشد. */
    val costRewardMultiple: Double,
    /** Efficiency Ratio کافمن روی ۲۰ کندل بسته (۰..۱). */
    val efficiencyRatio: Double?,
    /** ATR فعلی تقسیم بر میانهٔ ATR شصت کندل اخیر. */
    val atrRatio: Double?,
    /** اسپرد واقعی تقسیم بر ATR؛ اگر بزرگ باشد اسکالپ بی‌معناست. */
    val spreadAtrRatio: Double?,
    /** آخر هفتهٔ رمزارز: بازار باز است ولی نقدشوندگی نازک‌تر است. */
    val thinLiquidity: Boolean,
    val venue: String,
    val reasonFa: String,
    val blockers: List<String>,
) {
    val isStandAside: Boolean get() = method == TradeMethod.STAND_ASIDE || !allowed
}

object MarketPlaybook {

    private val zoneNewYork: ZoneId = ZoneId.of("America/New_York")
    private val zoneLondon: ZoneId = ZoneId.of("Europe/London")
    private val zoneTokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val zoneSydney: ZoneId = ZoneId.of("Australia/Sydney")

    private const val TREND_ER = 0.35
    private const val RANGE_ER = 0.18
    private const val SPIKE_ATR_RATIO = 2.5
    private const val DEAD_ATR_RATIO = 0.6
    private const val MAX_SPREAD_ATR_RATIO = 0.25

    /** چند کندل بسته برای تشخیص رژیم لازم است؛ کمتر از این یعنی «نمی‌دانیم» نه حدس. */
    const val MIN_REGIME_BARS = 40

    // ── خانوادهٔ بازار ────────────────────────────────────────────────────────
    fun familyOf(symbol: String): MarketFamily {
        val s = symbol.trim().uppercase(java.util.Locale.ROOT)
        return when {
            s.startsWith("XAU") || s == "GOLD" || s.startsWith("GOLD") -> MarketFamily.GOLD
            s.startsWith("XAG") || s == "SILVER" -> MarketFamily.SILVER
            s.startsWith("NATGAS") || s.contains("NATURALGAS") -> MarketFamily.NATGAS
            s.contains("WTI") || s.contains("USOIL") || s.contains("BRENT") ||
                s.contains("UKOIL") || s.contains("CRUDE") || s == "CL" || s == "BZ" -> MarketFamily.OIL
            s.startsWith("COPPER") || s.contains("XCU") -> MarketFamily.COPPER
            AssetClass.of(symbol) == AssetClass.CRYPTO -> MarketFamily.CRYPTO
            AssetClass.of(symbol) == AssetClass.STOCK ->
                if (s.contains("SPX") || s.contains("US500") || s.contains("NAS") ||
                    s.contains("US30") || s.contains("DJI") || s.contains("IXIC") ||
                    s.contains("SPY") || s.contains("QQQ") || s.endsWith("INDEX")
                ) MarketFamily.INDEX else MarketFamily.US_STOCK
            AssetClass.of(symbol) == AssetClass.FOREX ->
                if (VenueSpecs.isMajorPair(s)) MarketFamily.FX_MAJOR else MarketFamily.FX_MINOR
            AssetClass.of(symbol) == AssetClass.COMMODITY -> MarketFamily.OTHER
            else -> MarketFamily.OTHER
        }
    }

    // ── سشن واقعی (DST-aware) ─────────────────────────────────────────────────
    fun sessionOf(symbol: String, now: Long): MarketSession {
        val family = familyOf(symbol)
        val ny = Instant.ofEpochMilli(now).atZone(zoneNewYork)
        if (MarketHours.weekendClosedFor(symbol, now)) {
            return if (family == MarketFamily.CRYPTO) MarketSession.CRYPTO_24_7 else MarketSession.WEEKEND
        }
        val nyTime = ny.toLocalTime()
        // رول‌اور ۱۷:۰۰ نیویورک: اسپرد wide می‌شود و بهرهٔ شبانه می‌خورد؛ ورود ممنوع.
        if (family != MarketFamily.CRYPTO && !nyTime.isBefore(LocalTime.of(17, 0)) &&
            nyTime.isBefore(LocalTime.of(18, 0))
        ) return MarketSession.ROLLOVER

        return when (family) {
            MarketFamily.CRYPTO -> MarketSession.CRYPTO_24_7
            MarketFamily.US_STOCK, MarketFamily.INDEX -> when {
                ny.dayOfWeek == DayOfWeek.SATURDAY || ny.dayOfWeek == DayOfWeek.SUNDAY -> MarketSession.OFF_HOURS
                nyTime.isBefore(LocalTime.of(9, 30)) || !nyTime.isBefore(LocalTime.of(16, 0)) -> MarketSession.OFF_HOURS
                nyTime.isBefore(LocalTime.of(10, 30)) -> MarketSession.EQUITY_OPEN
                !nyTime.isBefore(LocalTime.of(15, 30)) -> MarketSession.EQUITY_CLOSE
                else -> MarketSession.NEW_YORK
            }
            else -> {
                val london = Instant.ofEpochMilli(now).atZone(zoneLondon).toLocalTime()
                val tokyo = Instant.ofEpochMilli(now).atZone(zoneTokyo).toLocalTime()
                val sydney = Instant.ofEpochMilli(now).atZone(zoneSydney).toLocalTime()
                val londonOpen = !london.isBefore(LocalTime.of(8, 0)) && london.isBefore(LocalTime.of(17, 0))
                val nyOpen = !nyTime.isBefore(LocalTime.of(8, 0)) && nyTime.isBefore(LocalTime.of(17, 0))
                val tokyoOpen = !tokyo.isBefore(LocalTime.of(9, 0)) && tokyo.isBefore(LocalTime.of(16, 0))
                val sydneyOpen = !sydney.isBefore(LocalTime.of(8, 0)) && sydney.isBefore(LocalTime.of(17, 0))
                when {
                    londonOpen && nyOpen -> MarketSession.LONDON_NY_OVERLAP
                    nyOpen -> MarketSession.NEW_YORK
                    londonOpen -> MarketSession.LONDON
                    tokyoOpen -> MarketSession.TOKYO
                    sydneyOpen -> MarketSession.SYDNEY
                    else -> MarketSession.OFF_HOURS
                }
            }
        }
    }

    /** آخر هفتهٔ رمزارز یا سشن‌های کم‌عمق: بازار باز است ولی نقدشوندگی نازک‌تر است. */
    fun isThinLiquidity(symbol: String, session: MarketSession, now: Long): Boolean {
        if (familyOf(symbol) != MarketFamily.CRYPTO) return false
        val ny = Instant.ofEpochMilli(now).atZone(zoneNewYork)
        return ny.dayOfWeek == DayOfWeek.SATURDAY || ny.dayOfWeek == DayOfWeek.SUNDAY
    }

    // ── کف سود و ضریب هزینه (بدون ساعت؛ قابل استفاده در بلیت دستی) ────────────
    /**
     * کمینهٔ سود مورد انتظار بر حسب bps. از هزینهٔ واقعی همان بازار و اندازهٔ معمول حرکتِ
     * قابل‌گرفتن در آن بازار گرفته شده، نه از یک عدد سراسری.
     */
    fun minRewardBpsFor(symbol: String): Double = when (familyOf(symbol)) {
        MarketFamily.FX_MAJOR -> 8.0    // ≈ ۰٫۸ پیپ روی EUR/USD — هزینه ≈ ۰٫۷bps
        MarketFamily.FX_MINOR -> 15.0   // اسپرد دو برابر، پس هدف هم باید بزرگ‌تر باشد
        MarketFamily.GOLD -> 25.0       // ≈ ۱۰ دلار روی طلای ۴۰۰۰ دلاری
        MarketFamily.SILVER -> 35.0
        MarketFamily.OIL -> 30.0
        MarketFamily.NATGAS -> 60.0     // whipsaw زیاد؛ هدف کوچک ارزش ریسک ندارد
        MarketFamily.COPPER -> 30.0
        MarketFamily.CRYPTO -> 60.0     // هزینه ≈ ۲۱bps رفت‌وبرگشت
        MarketFamily.US_STOCK -> 30.0
        MarketFamily.INDEX -> 20.0
        MarketFamily.OTHER -> 30.0
    }

    /** سود باید چند برابر هزینهٔ واقعی رفت‌وبرگشت باشد تا معامله از نظر هزینه توجیه داشته باشد. */
    fun costRewardMultipleFor(symbol: String): Double = when (familyOf(symbol)) {
        MarketFamily.CRYPTO -> 5.0
        MarketFamily.NATGAS -> 6.0
        MarketFamily.FX_MINOR, MarketFamily.SILVER -> 6.0
        else -> 5.0
    }

    private fun rrBounds(method: TradeMethod): Pair<Double, Double> = when (method) {
        TradeMethod.TREND_PULLBACK -> 1.5 to 5.0
        TradeMethod.BREAKOUT_MOMENTUM -> 1.8 to 6.0
        TradeMethod.RANGE_MEAN_REVERSION -> 1.2 to 2.2
        TradeMethod.OPENING_DRIVE -> 1.5 to 4.0
        TradeMethod.STAND_ASIDE -> 1.5 to 5.0
    }

    private fun stopAtrBounds(family: MarketFamily): Pair<Double, Double> = when (family) {
        MarketFamily.GOLD -> 0.9 to 2.5
        MarketFamily.SILVER -> 1.0 to 2.8
        MarketFamily.FX_MAJOR -> 0.7 to 1.6
        MarketFamily.FX_MINOR -> 0.8 to 1.8
        MarketFamily.CRYPTO -> 1.2 to 3.5
        MarketFamily.US_STOCK, MarketFamily.INDEX -> 0.8 to 2.0
        MarketFamily.OIL -> 1.0 to 2.5
        MarketFamily.NATGAS -> 1.2 to 3.0
        MarketFamily.COPPER -> 0.9 to 2.2
        MarketFamily.OTHER -> 0.9 to 2.5
    }

    /**
     * ارزیابی کامل: خانواده + سشن واقعی + رژیم اندازه‌گیری‌شده از کندل‌های بسته → روش مجاز.
     *
     * @param candles کندل‌های همان نماد (فقط کندل‌های بسته شمرده می‌شوند).
     * @param now زمان واقعی — سشن‌ها با zoneهای DST-aware حساب می‌شوند.
     */
    fun assess(
        symbol: String,
        candles: List<Candle>,
        interval: Interval = Interval.M5,
        now: Long = System.currentTimeMillis(),
        spec: VenueSpec = VenueSpecs.of(symbol),
    ): PlaybookDecision {
        val family = familyOf(symbol)
        val session = sessionOf(symbol, now)
        val thin = isThinLiquidity(symbol, session, now)
        val closed = candles.filter { it.closed }
        val lastClose = closed.lastOrNull()?.close

        // ── رژیم: فقط از دادهٔ واقعی، وگرنه صریحاً UNKNOWN ────────────────────
        val er = efficiencyRatio(closed, 20)
        val atrSeries = if (closed.size > 15) Indicators.atr(closed, 14) else emptyList()
        val atrNow = atrSeries.lastOrNull { it != null }
        val atrMedian = atrSeries.mapNotNull { it }.takeLast(60).median()
        val atrRatio = if (atrNow != null && atrMedian != null && atrMedian > 0.0) atrNow / atrMedian else null
        val spreadPrice = when {
            lastClose == null || lastClose <= 0.0 -> null
            spec.spreadBps > 0.0 -> lastClose * spec.spreadBps / 10_000.0
            else -> spec.spreadPrice.takeIf { it > 0.0 }
        }
        val spreadAtrRatio = if (spreadPrice != null && atrNow != null && atrNow > 0.0) spreadPrice / atrNow else null
        val regime = when {
            closed.size < MIN_REGIME_BARS || er == null -> RegimeKind.UNKNOWN
            atrRatio != null && atrRatio >= SPIKE_ATR_RATIO -> RegimeKind.SPIKE
            atrRatio != null && atrRatio <= DEAD_ATR_RATIO -> RegimeKind.DEAD
            er >= TREND_ER -> RegimeKind.TREND
            er <= RANGE_ER -> RegimeKind.RANGE
            else -> RegimeKind.MIXED
        }

        val blockers = mutableListOf<String>()
        val method = chooseMethod(family, session, regime, thin, er, spreadAtrRatio, blockers)
        val (rrMin, rrMax) = rrBounds(method)
        val (stopMin, stopMax) = stopAtrBounds(family)

        var minScore = 75
        var minConfidence = 72.0
        when (family) {
            MarketFamily.FX_MINOR, MarketFamily.SILVER, MarketFamily.COPPER, MarketFamily.NATGAS -> minScore += 5
            else -> Unit
        }
        if (regime == RegimeKind.MIXED) minScore += 5
        if (thin) { minScore += 10; minConfidence += 4.0 }
        if (session == MarketSession.TOKYO || session == MarketSession.SYDNEY) {
            if (family != MarketFamily.FX_MAJOR) minScore += 5
        }
        if (method == TradeMethod.RANGE_MEAN_REVERSION) { minScore += 3; minConfidence += 2.0 }
        minScore = min(95, minScore)
        minConfidence = min(90.0, minConfidence)

        // اسپردِ بزرگ نسبت به نوسان، هر روشی را بی‌معنا می‌کند.
        if (spreadAtrRatio != null && spreadAtrRatio > MAX_SPREAD_ATR_RATIO && method != TradeMethod.STAND_ASIDE) {
            blockers += "اسپرد واقعی ${fmt(spreadAtrRatio * 100.0)}٪ از ATR است (سقف ${fmt(MAX_SPREAD_ATR_RATIO * 100.0)}٪) — هزینه از سودِ قابل‌گرفتن بیشتر می‌شود"
        }
        if (regime == RegimeKind.SPIKE && method != TradeMethod.STAND_ASIDE) {
            blockers += "نوسان ${fmt(atrRatio ?: 0.0)} برابر میانهٔ اخیر است؛ ورود در جهش نوسان ممنوع"
        }
        if (regime == RegimeKind.UNKNOWN) {
            blockers += "کمتر از $MIN_REGIME_BARS کندل بستهٔ معتبر؛ رژیم بازار قابل تشخیص نیست"
        }

        val allowed = method != TradeMethod.STAND_ASIDE && blockers.isEmpty()
        val reason = buildReason(family, session, regime, method, er, atrRatio, spreadAtrRatio, spec, thin)
        return PlaybookDecision(
            symbol = symbol, family = family, session = session, regime = regime, method = method,
            allowed = allowed, minScore = minScore, minConfidence = minConfidence,
            rrMin = rrMin, rrMax = rrMax, stopAtrMin = stopMin, stopAtrMax = stopMax,
            minRewardBps = minRewardBpsFor(symbol), costRewardMultiple = costRewardMultipleFor(symbol),
            efficiencyRatio = er, atrRatio = atrRatio, spreadAtrRatio = spreadAtrRatio,
            thinLiquidity = thin, venue = spec.venue, reasonFa = reason, blockers = blockers,
        )
    }

    /**
     * ماتریس روش‌ها. هر شاخه یک دلیل بازاری دارد:
     *  - طلا/نقره: حرکت اصلی در لندن و هم‌پوشانی لندن–نیویورک است؛ در آسیا رنجِ کم‌عمق است و
     *    اسپرد نسبت به حرکت بزرگ‌تر می‌شود → فقط با روند قوی (ER بالا) مجاز است.
     *  - فارکس اصلی: آسیا رنج است (بازگشت به میانگین با هدف کوچک)، لندن/نیویورک جهت‌دار
     *    (پولبک در روند). رول‌اور و جهش نوسان ممنوع.
     *  - فارکس غیراصلی: اسپرد دو برابر است، پس فقط در سشن مرجعِ خودش و فقط با روند.
     *  - رمزارز: ۲۴/۷ ولی هزینهٔ ۲۱bps و whipsawِ رنج → شکست مومنتوم؛ رنج ممنوع؛
     *    آخر هفته فقط با روند قوی‌تر.
     *  - سهام/شاخص: فقط داخل سشن نیویورک؛ ۶۰ دقیقهٔ اول «درایو بازگشایی»؛ دقایق آخر ممنوع.
     *  - نفت: سشن نیویورک + شکست؛ حول گزارش موجودی EIA (چهارشنبه ۱۰:۳۰ نیویورک) ورود ممنوع.
     *  - گاز طبیعی: فقط روندِ قوی؛ در غیر این صورت ایستادن کنار.
     */
    private fun chooseMethod(
        family: MarketFamily,
        session: MarketSession,
        regime: RegimeKind,
        thin: Boolean,
        er: Double?,
        spreadAtrRatio: Double?,
        blockers: MutableList<String>,
    ): TradeMethod {
        if (session == MarketSession.WEEKEND) {
            blockers += "بازار این نماد در تعطیلی پایان هفته است"
            return TradeMethod.STAND_ASIDE
        }
        if (session == MarketSession.ROLLOVER) {
            blockers += "رول‌اور ۱۷:۰۰ نیویورک: اسپرد wide و بهرهٔ شبانه؛ ورود ممنوع"
            return TradeMethod.STAND_ASIDE
        }
        if (regime == RegimeKind.SPIKE) {
            blockers += "جهش نوسان (ATR چند برابر میانه) — معمولاً خبر/شوک است، نه روند قابل ورود"
            return TradeMethod.STAND_ASIDE
        }
        if (regime == RegimeKind.DEAD) {
            blockers += "بازار بی‌جان است (ATR زیر ۰٫۶ برابر میانه)؛ حرکت کافی برای پوشش هزینه وجود ندارد"
            return TradeMethod.STAND_ASIDE
        }

        return when (family) {
            MarketFamily.CRYPTO -> when {
                regime == RegimeKind.RANGE -> {
                    blockers += "رمزارز در رنج: هزینهٔ ۲۱bps رفت‌وبرگشت + whipsaw → بازگشت به میانگین ممنوع"
                    TradeMethod.STAND_ASIDE
                }
                thin && (er == null || er < 0.40) -> {
                    blockers += "آخر هفتهٔ رمزارز با نقدشوندگی نازک و روند ضعیف (ER=${fmt(er ?: 0.0)}) — فقط روند قوی مجاز است"
                    TradeMethod.STAND_ASIDE
                }
                regime == RegimeKind.TREND -> TradeMethod.BREAKOUT_MOMENTUM
                else -> TradeMethod.BREAKOUT_MOMENTUM
            }

            MarketFamily.US_STOCK, MarketFamily.INDEX -> when (session) {
                MarketSession.OFF_HOURS -> {
                    blockers += "بازار سهام/شاخص بسته است (خارج از ۰۹:۳۰–۱۶:۰۰ نیویورک)"
                    TradeMethod.STAND_ASIDE
                }
                MarketSession.EQUITY_CLOSE -> {
                    blockers += "۳۰ دقیقهٔ پایانی سشن سهام: نوسان بستنِ قیمت و ریسک گپ بسته‌شدن"
                    TradeMethod.STAND_ASIDE
                }
                MarketSession.EQUITY_OPEN -> TradeMethod.OPENING_DRIVE
                else -> if (regime == RegimeKind.TREND) TradeMethod.TREND_PULLBACK else {
                    blockers += "سهام داخل سشن ولی رژیم رونددار نیست؛ درایوِ بازگشایی تمام شده"
                    TradeMethod.STAND_ASIDE
                }
            }

            MarketFamily.OIL -> when {
                session != MarketSession.NEW_YORK && session != MarketSession.LONDON_NY_OVERLAP -> {
                    blockers += "نفت: نقدشوندگی اصلی در سشن نیویورک است؛ سشن فعلی ${session.label}"
                    TradeMethod.STAND_ASIDE
                }
                regime == RegimeKind.TREND || regime == RegimeKind.MIXED -> TradeMethod.BREAKOUT_MOMENTUM
                else -> {
                    blockers += "نفت در رنج: شکست‌های جعلی زیاد است؛ فقط شکست با مومنتوم مجاز است"
                    TradeMethod.STAND_ASIDE
                }
            }

            MarketFamily.NATGAS -> if (regime == RegimeKind.TREND && er != null && er >= 0.45) {
                TradeMethod.TREND_PULLBACK
            } else {
                blockers += "گاز طبیعی فقط با روند قوی (ER≥۰٫۴۵) قابل معامله است؛ فعلی ${fmt(er ?: 0.0)}"
                TradeMethod.STAND_ASIDE
            }

            MarketFamily.GOLD, MarketFamily.SILVER -> when (session) {
                MarketSession.LONDON_NY_OVERLAP, MarketSession.LONDON, MarketSession.NEW_YORK ->
                    if (regime == RegimeKind.TREND || regime == RegimeKind.MIXED) TradeMethod.TREND_PULLBACK
                    else {
                        blockers += "فلزات در سشن اصلی ولی رنج است؛ پولبکِ روندی وجود ندارد"
                        TradeMethod.STAND_ASIDE
                    }
                MarketSession.TOKYO, MarketSession.SYDNEY ->
                    if (regime == RegimeKind.TREND && er != null && er >= 0.45) TradeMethod.TREND_PULLBACK
                    else {
                        blockers += "سشن آسیا برای ${family.label} کم‌عمق است؛ فقط روند قوی (ER≥۰٫۴۵) مجاز است"
                        TradeMethod.STAND_ASIDE
                    }
                else -> {
                    blockers += "خارج از سشن نقدشوندهٔ ${family.label}"
                    TradeMethod.STAND_ASIDE
                }
            }

            MarketFamily.FX_MAJOR -> when {
                regime == RegimeKind.TREND -> TradeMethod.TREND_PULLBACK
                regime == RegimeKind.RANGE && (session == MarketSession.TOKYO || session == MarketSession.SYDNEY) ->
                    TradeMethod.RANGE_MEAN_REVERSION
                regime == RegimeKind.RANGE -> {
                    blockers += "جفت‌ارز اصلی در رنجِ سشن ${session.label}: بازگشت به میانگین فقط در آسیا مجاز است"
                    TradeMethod.STAND_ASIDE
                }
                else -> TradeMethod.TREND_PULLBACK
            }

            MarketFamily.FX_MINOR -> when {
                regime != RegimeKind.TREND -> {
                    blockers += "کراس/غیراصلی در رنج: اسپرد دو برابر است و بازگشت به میانگین به‌صرفه نیست"
                    TradeMethod.STAND_ASIDE
                }
                session == MarketSession.TOKYO || session == MarketSession.SYDNEY ||
                    session == MarketSession.LONDON || session == MarketSession.NEW_YORK ||
                    session == MarketSession.LONDON_NY_OVERLAP -> TradeMethod.TREND_PULLBACK
                else -> {
                    blockers += "خارج از سشن نقدشوندهٔ این کراس"
                    TradeMethod.STAND_ASIDE
                }
            }

            MarketFamily.COPPER -> if (regime == RegimeKind.TREND &&
                (session == MarketSession.LONDON_NY_OVERLAP || session == MarketSession.NEW_YORK)
            ) TradeMethod.TREND_PULLBACK else {
                blockers += "مس فقط با روند در سشن لندن/نیویورک"
                TradeMethod.STAND_ASIDE
            }

            MarketFamily.OTHER -> if (regime == RegimeKind.TREND) TradeMethod.TREND_PULLBACK else {
                blockers += "برای این نماد فقط روش روندی مجاز است"
                TradeMethod.STAND_ASIDE
            }
        }.also {
            // اسپردِ بزرگ نسبت به نوسان، عملاً هر روشی را از بین می‌برد.
            if (spreadAtrRatio != null && spreadAtrRatio > MAX_SPREAD_ATR_RATIO && it != TradeMethod.STAND_ASIDE) {
                blockers += "اسپرد واقعی نسبت به ATR بزرگ است؛ ورود مقرون‌به‌صرفه نیست"
                return TradeMethod.STAND_ASIDE
            }
        }
    }

    /** Efficiency Ratio کافمن: نسبت جابه‌جایی خالص به مجموع حرکت؛ ۱ = روند تمیز، ۰ = رنج. */
    fun efficiencyRatio(closed: List<Candle>, lookback: Int = 20): Double? {
        val window = closed.takeLast(lookback + 1)
        if (window.size < lookback) return null
        val net = abs(window.last().close - window.first().close)
        val path = window.zipWithNext { a, b -> abs(b.close - a.close) }.sum()
        if (path <= 0.0) return null
        return net / path
    }

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun buildReason(
        family: MarketFamily, session: MarketSession, regime: RegimeKind, method: TradeMethod,
        er: Double?, atrRatio: Double?, spreadAtrRatio: Double?, spec: VenueSpec, thin: Boolean,
    ): String = buildString {
        append("بازار «${family.label}» · سشن «${session.label}» · رژیم «${regime.label}» → روش «${method.label}». ")
        append("ER=${fmt(er ?: 0.0)}")
        atrRatio?.let { append(" · ATR/میانه=${fmt(it)}") }
        spreadAtrRatio?.let { append(" · اسپرد/ATR=${fmt(it * 100.0)}٪") }
        append(" · مرجع هزینه: ${spec.venue}")
        if (thin) append(" · نقدشوندگی آخر هفتهٔ رمزارز نازک است")
        append(". ")
        append(
            when (family) {
                MarketFamily.GOLD, MarketFamily.SILVER ->
                    "فلزات در لندن/نیویورک جهت‌دارند و در آسیا رنج؛ پس روشِ روند با پولبک به کیجون و استاپ بیرونِ اسپرد ۰٫۱۲ دلاری."
                MarketFamily.FX_MAJOR ->
                    "جفت اصلی: اسپرد ≈۰٫۷bps است، پس در آسیا رنجِ قابل بازگشت و در لندن/نیویورک ادامهٔ روند با هدف کوچک‌تر جواب می‌دهد."
                MarketFamily.FX_MINOR ->
                    "کراس‌ها اسپرد دو برابر دارند؛ فقط روند در سشن مرجع، وگرنه هزینه سود را می‌خورد."
                MarketFamily.CRYPTO ->
                    "رمزارز ۲۴/۷ است ولی هزینهٔ رفت‌وبرگشت ≈۲۱bps؛ پس فقط شکست/مومنتوم با هدف بزرگ، و رنج ممنوع."
                MarketFamily.US_STOCK, MarketFamily.INDEX ->
                    "سهام/شاخص فقط داخل سشن نیویورک؛ ۶۰ دقیقهٔ اول درایو بازگشایی است و دقایق آخر نوسان بستن دارد."
                MarketFamily.OIL ->
                    "نفت با گزارش‌های موجودی و سشن نیویورک حرکت می‌کند؛ روش شکست+مومنتوم و پرهیز از رنج."
                MarketFamily.NATGAS ->
                    "گاز طبیعی whipsaw زیاد دارد؛ فقط روند قوی با تأیید دوکندلی."
                MarketFamily.COPPER -> "مس با روند و در سشن لندن/نیویورک معامله می‌شود."
                MarketFamily.OTHER -> "برای این نماد فقط ورود روندی با تأیید کامل مجاز است."
            }
        )
    }

    private fun fmt(value: Double): String =
        String.format(java.util.Locale.US, "%.2f", value)
}
