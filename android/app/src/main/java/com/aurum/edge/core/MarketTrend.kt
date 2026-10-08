package com.aurum.edge.core

import com.aurum.edge.engine.Indicators
import com.aurum.edge.engine.MtfAnalyzer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * «روند کلی بازار» — چه چیزی اندازه گرفته می‌شود و چطور به خودِ معامله اضافه می‌شود.
 *
 * روند کلی بازار یک عدد جادویی نیست؛ از سه لایهٔ کاملاً اندازه‌گیری‌شدنی ساخته می‌شود و همه‌جا
 * فقط از **کندل‌های بستهٔ واقعی** می‌آید (هیچ کندلی ساخته یا درون‌یابی نمی‌شود):
 *
 *  (۱) **روندِ همان نماد در تایم‌فریم مرجع** ([symbolTrend]): کندل‌های تایم‌فریم پایه با
 *     [MtfAnalyzer.resample] به تایم‌فریم بالاتر تجمیع می‌شوند (M5→H1، M15→H4، H1→H4، H4→D1) و
 *     روی آن‌ها شش رأیِ مستقل شمرده می‌شود: قیمت نسبت به EMA50، EMA20 نسبت به EMA50، شیب EMA50
 *     بر حسب ATR، Efficiency Ratio کافمن (همان [MarketPlaybook.efficiencyRatio])، ساختار
 *     سقف/کف‌های تأییدشده (HH/HL در برابر LH/LL) و اندازهٔ جابه‌جایی خالص بر حسب ATR.
 *     مجموع ≥ +۲ یعنی صعودی، ≤ −۲ یعنی نزولی، وگرنه خنثی. «قدرت» هم ترکیب همان رأی‌ها
 *     (تا ۶۰٪) با ER (تا ۴۰٪) است، پس یک عدد ۰ تا ۱۰۰ قابل توضیح است نه یک امتیاز مبهم.
 *     اگر کندل بستهٔ کافی برای تایم‌فریم بالاتر نباشد، جهتِ آن صریحاً UNKNOWN می‌ماند و
 *     فقط تایم‌فریم پایه گزارش می‌شود — حدس زده نمی‌شود.
 *
 *  (۲) **عرض بازار (Breadth)** ([overall]): همان اندازه‌گیری برای هر نمادی که پویشگر پیوسته
 *     کندل واقعی‌اش را گرفته است (۵۰+ نماد طلا/نقره/نفت/مس، فارکس اصلی و کراس، سهام و شاخص،
 *     رمزارز). سهمِ نمادهای صعودی منهای نزولی، جهتِ کلی بازار را می‌دهد و برای هر خانوادهٔ
 *     بازار هم جداگانه گزارش می‌شود.
 *
 *  (۳) **جوّ بازار**: دو متغیر کلاسیکِ جهتِ کلان از همان نمادهای واقعی خوانده می‌شود —
 *     **جهت دلار** از شش جفت اصلی (USD/JPY، USD/CHF، USD/CAD با علامت مثبت و EUR/USD، GBP/USD،
 *     AUD/USD، NZD/USD با علامت منفی، چون در آن‌ها دلار سمتِ مظنه (quote) است است) و
 *     **ریسک‌پذیری** از مقایسهٔ دارایی‌های ریسکی (رمزارز/سهام/شاخص) با پناهگاه امن (طلا) و دلار.
 *
 * چطور به معامله اضافه می‌شود ([entryGate] و [contextOf]):
 *  - روش‌های روندی (ادامهٔ روند با پولبک، شکست و مومنتوم، درایو بازگشایی) **خلاف جهت** روندِ
 *    اندازه‌گیری‌شده مسدود می‌شوند؛ اگر روند خنثی یا اندازه‌گیری‌نشده باشد، کف اطمینان بالا می‌رود.
 *  - روش «بازگشت به میانگین در رنج» فقط وقتی مجاز است که واقعاً روندی وجود نداشته باشد.
 *  - روی دارایی ریسکی، ورود خلافِ جوّ کلی بازار (خرید در جوّ ریسک‌گریز / فروش در جوّ ریسک‌پذیر)
 *    منع نمی‌شود ولی کف امتیاز و کف اطمینانش سخت‌تر می‌شود.
 *  - نتیجهٔ اندازه‌گیری همان لحظهٔ ورود در ژورنال ثبت می‌شود ([MarketTrendRecord]) و در پنجرهٔ
 *    همان معامله روی صفحهٔ چارت نشان داده می‌شود؛ یعنی برای هر معامله می‌دانیم با روند بوده یا خلافش.
 *
 * این ماژول پیش‌بینی نمی‌کند: فقط می‌گوید جهتِ الان چه چیزی است، با چه قدرتی، و از چه داده‌ای.
 */

/** جهتِ اندازه‌گیری‌شده. UNKNOWN یعنی «دادهٔ کافی نداریم»، نه «خنثی». */
enum class TrendDirection(val label: String) {
    UP("صعودی"),
    DOWN("نزولی"),
    SIDEWAYS("خنثی/رنج"),
    UNKNOWN("اندازه گرفته نشد"),
}

/** جوّ کلی بازار، از مقایسهٔ دارایی‌های ریسکی با پناهگاه امن و جهت دلار. */
enum class RiskTone(val label: String) {
    RISK_ON("ریسک‌پذیر"),
    RISK_OFF("ریسک‌گریز"),
    MIXED("مختلط"),
    UNKNOWN("اندازه گرفته نشد"),
}

/** جایگاه یک ورود نسبت به روندِ اندازه‌گیری‌شده. */
enum class TrendAlignment(val label: String) {
    WITH("هم‌جهت با روند"),
    AGAINST("خلاف جهت روند"),
    NEUTRAL("روند خنثی است"),
    UNKNOWN("روند اندازه گرفته نشد"),
}

/** روندِ اندازه‌گیری‌شدهٔ یک نماد، با تمام عددهایی که آن را ساخته‌اند. */
data class SymbolTrend(
    val symbol: String,
    val family: MarketFamily,
    val intervalLabel: String,
    /** تایم‌فریم مرجعِ روند (تجمیع‌شده از همان کندل‌های واقعی)؛ برای D1 بالاتری وجود ندارد. */
    val higherLabel: String?,
    /** جهت نهایی: تایم‌فریم مرجع اگر اندازه گرفته شده باشد، وگرنه تایم‌فریم پایه. */
    val direction: TrendDirection,
    val baseDirection: TrendDirection,
    val higherDirection: TrendDirection,
    /** ۰ تا ۱۰۰: تا ۶۰ از رأی‌های جهت + تا ۴۰ از ER. */
    val strength: Int,
    val efficiencyRatio: Double?,
    val higherEfficiencyRatio: Double?,
    /** شیب EMA50 بر حسب ATR در هر کندل. */
    val slopeAtr: Double?,
    /** جابه‌جایی خالص ۵۰ کندل بر حسب ATR. */
    val netAtr: Double?,
    val changePct: Double?,
    /** تایم‌فریم پایه و مرجع هم‌جهت نیستند → قدرت کاهش می‌یابد. */
    val conflict: Boolean,
    val closedBars: Int,
    val higherBars: Int,
    val votes: List<String>,
    val detailFa: String,
) {
    val known: Boolean get() = direction != TrendDirection.UNKNOWN

    /** +۱ صعودی، −۱ نزولی، ۰ خنثی/نامعلوم — واحدِ شمارشِ عرض بازار. */
    val vote: Int
        get() = when (direction) {
            TrendDirection.UP -> 1
            TrendDirection.DOWN -> -1
            else -> 0
        }

    /** عنوان کوتاهِ جهت برای نمایش در کارت‌ها. */
    val shortFa: String get() = "${direction.label} · قدرت $strength٪"
}

/** روند یک خانوادهٔ بازار (مثلاً همهٔ رمزارزها یا همهٔ فلزات) از همان رأی‌های تک‌نمادی. */
data class FamilyTrend(
    val family: MarketFamily,
    val direction: TrendDirection,
    val strength: Int,
    val up: Int,
    val down: Int,
    val flat: Int,
    val measured: Int,
) {
    val summaryFa: String
        get() = "${family.label}: ${direction.label} · $up صعودی / $down نزولی / $flat خنثی از $measured نماد"
}

/** خوانشِ روند کلی بازار: عرض بازار + جهت دلار + جوّ ریسک، هر سه از دادهٔ واقعی. */
data class MarketTrendRead(
    val bias: TrendDirection,
    val strength: Int,
    val dollarBias: TrendDirection,
    val riskTone: RiskTone,
    val breadthUp: Int,
    val breadthDown: Int,
    val breadthFlat: Int,
    val measured: Int,
    val families: List<FamilyTrend>,
    /** چند دلیل فارسی که خوانش از آن‌ها ساخته شده است. */
    val drivers: List<String>,
    val reasonFa: String,
    val computedAt: Long,
) {
    val known: Boolean get() = bias != TrendDirection.UNKNOWN

    val vote: Int
        get() = when (bias) {
            TrendDirection.UP -> 1
            TrendDirection.DOWN -> -1
            else -> 0
        }

    val breadthFa: String get() = "$breadthUp صعودی / $breadthDown نزولی / $breadthFlat خنثی از $measured نمادِ اندازه‌گیری‌شده"
}

/** اثرِ روند روی یک ورود: منع، یا سخت‌تر شدن کف امتیاز/اطمینان، به‌همراه دلیل فارسی. */
data class TrendGate(
    val allowed: Boolean,
    val minScoreAdd: Int,
    val minConfidenceAdd: Double,
    val blockerFa: String?,
    val noteFa: String,
)

/** بستهٔ کاملِ «روند + جایگاه این معامله»؛ همان چیزی که در ژورنال ثبت و در پنجرهٔ معامله نشان داده می‌شود. */
data class TrendContext(
    val action: SignalAction,
    val symbol: SymbolTrend?,
    val overall: MarketTrendRead?,
    val alignment: TrendAlignment,
    val gate: TrendGate,
    val noteFa: String,
)

object MarketTrend {

    /** پنجرهٔ اندازه‌گیری: آخرین کندل‌های بستهٔ معتبر (نه همهٔ ۳۰۰۰ کندل، برای سرعت). */
    private const val MAX_BARS = 500

    /** کمتر از این تعداد کندل بسته ⇒ UNKNOWN. زیر ۶۰ کندل، EMA50 خودش معنا ندارد. */
    const val MIN_BARS = 60

    /** برای خواندنِ «عرض بازار» حداقل چند نماد باید اندازه گرفته شده باشد. */
    const val MIN_BREADTH = 5

    private const val ER_TREND = 0.30
    private const val SLOPE_ATR_MIN = 0.05
    private const val NET_ATR_MIN = 3.0
    private const val NET_WINDOW = 50
    private const val PIVOT_ARM = 2

    /** سهمِ خالص (صعودی‌ها منهای نزولی‌ها) که جهتِ کلی بازار را از «خنثی» جدا می‌کند. */
    private const val BREADTH_BIAS = 0.25

    /** زیر این قدرت، روند «ضعیف» است و ورود روندی به اطمینان بیشتری نیاز دارد. */
    const val WEAK_STRENGTH = 35

    /** رأی‌های جهت: حداکثر ۶ رأی مستقل. */
    private const val MAX_VOTES = 6

    /** چقدر کف امتیاز/اطمینان وقتی ورود خلافِ جوّ کلی بازار است سخت‌تر می‌شود. */
    private const val SCORE_TONE = 6
    private const val CONF_TONE = 5

    // دلار در این جفت‌ها «ارز پایه» است: صعودِ جفت = صعود دلار.
    private val dollarBasePairs = setOf("USD/JPY", "USD/CHF", "USD/CAD")

    // دلار در این جفت‌ها «ارز مظنه» است: صعودِ جفت = افت دلار.
    private val dollarQuotePairs = setOf("EUR/USD", "GBP/USD", "AUD/USD", "NZD/USD")

    /** تایم‌فریم مرجعِ روند برای هر تایم‌فریم پایه؛ فقط با تجمیع، هرگز با شکستن کندل. */
    fun higherFor(interval: Interval): Interval? = when (interval) {
        Interval.M1 -> Interval.M15
        Interval.M5 -> Interval.H1
        Interval.M15 -> Interval.H4
        Interval.M30 -> Interval.H4
        Interval.H1 -> Interval.H4
        Interval.H4 -> Interval.D1
        Interval.D1 -> null
    }

    // ── لایهٔ ۱: روندِ خودِ نماد ─────────────────────────────────────────────
    /**
     * اندازه‌گیری روند یک نماد از کندل‌های بستهٔ واقعی خودش.
     *
     * @param candles کندل‌های همان نماد روی [interval] (کندلِ در حال تشکیل شمرده نمی‌شود).
     * @param higher تایم‌فریم مرجع؛ اگر کندل بستهٔ کافی نداشته باشد، جهتش UNKNOWN می‌ماند.
     */
    fun symbolTrend(
        symbol: String,
        candles: List<Candle>,
        interval: Interval = Interval.M5,
        higher: Interval? = higherFor(interval),
    ): SymbolTrend {
        val family = MarketPlaybook.familyOf(symbol)
        val closed = candles.filter { it.closed }.takeLast(MAX_BARS)
        val base = measure(closed)
        if (base == null) {
            return SymbolTrend(
                symbol = symbol, family = family, intervalLabel = interval.label, higherLabel = higher?.label,
                direction = TrendDirection.UNKNOWN, baseDirection = TrendDirection.UNKNOWN,
                higherDirection = TrendDirection.UNKNOWN, strength = 0, efficiencyRatio = null,
                higherEfficiencyRatio = null, slopeAtr = null, netAtr = null, changePct = null,
                conflict = false, closedBars = closed.size, higherBars = 0, votes = emptyList(),
                detailFa = "فقط ${closed.size} کندل بستهٔ معتبر داریم؛ اندازه‌گیری روند به حداقل " +
                    "$MIN_BARS کندل بسته نیاز دارد. جهت حدس زده نمی‌شود.",
            )
        }

        val canResample = higher != null && higher.minutes > interval.minutes &&
            higher.minutes % interval.minutes == 0
        val higherBars = if (canResample && higher != null) {
            MtfAnalyzer.resample(closed, higher, interval).filter { it.closed }
        } else emptyList()
        val higherMeasure = if (higherBars.size >= MIN_BARS) measure(higherBars) else null

        val baseDirection = directionOf(base)
        val higherDirection = if (higherMeasure == null) TrendDirection.UNKNOWN else directionOf(higherMeasure)
        val direction = if (higherDirection == TrendDirection.UNKNOWN) baseDirection else higherDirection
        val conflict = higherDirection != TrendDirection.UNKNOWN &&
            baseDirection != TrendDirection.SIDEWAYS && higherDirection != baseDirection

        val reference = higherMeasure ?: base
        val rawStrength = strengthOf(reference)
        val strength = if (conflict) (rawStrength * 0.6).roundToInt().coerceIn(0, 100) else rawStrength

        val detail = buildString {
            append("روند ${direction.label} با قدرت $strength٪")
            append(if (higherMeasure != null) " روی ${higher?.label ?: interval.label} (${higherBars.size} کندل بستهٔ تجمیع‌شده)"
            else " روی خودِ ${interval.label} (${closed.size} کندل بسته)")
            append(" · ER=${fmt(reference.er)}")
            reference.slopeAtr?.let { append(" · شیب EMA50 ${fmt(it)}×ATR") }
            reference.netAtr?.let { append(" · جابه‌جایی خالص ${fmt(abs(it))}×ATR") }
            append(" · ساختار ${reference.structure}")
            if (conflict) append(" · تایم‌فریم پایه (${baseDirection.label}) و مرجع (${higherDirection.label}) هم‌جهت نیستند، پس قدرت کاهش یافت")
            else if (higherMeasure == null && higher != null) {
                append(" · برای ${higher.label} کندل بستهٔ کافی نداریم (${higherBars.size} از $MIN_BARS)؛ جهتِ تایم‌فریم بالاتر حدس زده نشد")
            } else if (higher == null) append(" · از تایم‌فریم روزانه بالاتری در اپ نیست")
            append(" · رأی‌ها: ")
            append(reference.votes.joinToString("، "))
        }

        return SymbolTrend(
            symbol = symbol, family = family, intervalLabel = interval.label, higherLabel = higher?.label,
            direction = direction, baseDirection = baseDirection, higherDirection = higherDirection,
            strength = strength, efficiencyRatio = base.er, higherEfficiencyRatio = higherMeasure?.er,
            slopeAtr = reference.slopeAtr, netAtr = reference.netAtr, changePct = reference.changePct,
            conflict = conflict, closedBars = closed.size, higherBars = higherBars.size,
            votes = reference.votes, detailFa = detail,
        )
    }

    /** شش رأیِ مستقل روی یک دسته کندل بسته. اگر داده کافی نباشد null. */
    private class Measurement(
        val score: Int,
        val er: Double?,
        val slopeAtr: Double?,
        val netAtr: Double?,
        val changePct: Double?,
        val structure: String,
        val bars: Int,
        val votes: List<String>,
    )

    private fun measure(closed: List<Candle>): Measurement? {
        if (closed.size < MIN_BARS) return null
        val bars = closed.takeLast(MAX_BARS)
        val closes = bars.map { it.close }
        val ema20 = Indicators.ema(closes, 20)
        val ema50 = Indicators.ema(closes, 50)
        val atr = Indicators.atr(bars, 14)
        val last = bars.last()
        val i = bars.lastIndex
        val fast = ema20[i] ?: return null
        val slow = ema50[i] ?: return null
        val atrNow = atr[i] ?: atr.lastOrNull { it != null } ?: return null
        if (atrNow <= 0.0 || slow <= 0.0) return null

        val er = MarketPlaybook.efficiencyRatio(bars, 20)
        val slopeStart = ema50.getOrNull(i - 5)
        val slopeAtr = if (slopeStart != null) (slow - slopeStart) / 5.0 / atrNow else null
        val netBars = bars.takeLast(NET_WINDOW)
        val netFirst = netBars.first().close
        val net = last.close - netFirst
        val netAtr = net / atrNow
        val changePct = if (netFirst > 0.0) net / netFirst * 100.0 else null
        val structure = structureOf(bars)

        val votes = ArrayList<String>(MAX_VOTES)
        var score = 0

        if (last.close > slow) { score += 1; votes += "قیمت بالای EMA50" }
        else if (last.close < slow) { score -= 1; votes += "قیمت زیر EMA50" }

        if (fast > slow) { score += 1; votes += "EMA20 بالای EMA50" }
        else if (fast < slow) { score -= 1; votes += "EMA20 زیر EMA50" }

        if (slopeAtr != null && abs(slopeAtr) >= SLOPE_ATR_MIN) {
            score += if (slopeAtr > 0.0) 1 else -1
            votes += "شیب EMA50 ${fmt(slopeAtr)}×ATR در هر کندل"
        } else {
            votes += "شیب EMA50 زیر ${fmt(SLOPE_ATR_MIN)}×ATR (خنثی)"
        }

        if (er != null && er >= ER_TREND) {
            score += if (net > 0.0) 1 else -1
            votes += "ER=${fmt(er)} با جابه‌جایی ${if (net > 0.0) "صعودی" else "نزولی"}"
        } else {
            votes += "ER=${fmt(er)} زیر ${fmt(ER_TREND)} (حرکت بازدهی‌دار نیست)"
        }

        when (structure) {
            "HH/HL" -> { score += 1; votes += "ساختار سقف/کف بالاتر" }
            "LH/LL" -> { score -= 1; votes += "ساختار سقف/کف پایین‌تر" }
            else -> votes += "ساختار سقف/کف نامشخص"
        }

        if (abs(netAtr) >= NET_ATR_MIN) {
            score += if (netAtr > 0.0) 1 else -1
            votes += "جابه‌جایی خالص ${fmt(abs(netAtr))}×ATR در $NET_WINDOW کندل"
        } else {
            votes += "جابه‌جایی خالص زیر ${fmt(NET_ATR_MIN)}×ATR"
        }

        return Measurement(score, er, slopeAtr, netAtr, changePct, structure, bars.size, votes)
    }

    /**
     * ساختار سقف/کف با پیوت‌های تأییدشده: پیوت یعنی کندلی که [PIVOT_ARM] کندلِ دو طرفش
     * سقف/کفِ بالاتر (یا پایین‌تر) از آن نداشته باشند. بدون پیوت کافی، «نامشخص» برمی‌گردد.
     */
    private fun structureOf(bars: List<Candle>): String {
        val highs = ArrayList<Int>()
        val lows = ArrayList<Int>()
        val from = PIVOT_ARM
        val to = bars.size - PIVOT_ARM - 1
        if (to <= from) return "نامشخص"
        for (j in from..to) {
            var isHigh = true
            var isLow = true
            for (k in j - PIVOT_ARM..j + PIVOT_ARM) {
                if (k == j) continue
                if (bars[k].high >= bars[j].high) isHigh = false
                if (bars[k].low <= bars[j].low) isLow = false
            }
            if (isHigh) highs += j
            if (isLow) lows += j
        }
        if (highs.size < 2 || lows.size < 2) return "نامشخص"
        val higherHigh = bars[highs.last()].high > bars[highs[highs.size - 2]].high
        val higherLow = bars[lows.last()].low > bars[lows[lows.size - 2]].low
        return when {
            higherHigh && higherLow -> "HH/HL"
            !higherHigh && !higherLow -> "LH/LL"
            else -> "مخلوط"
        }
    }

    private fun directionOf(m: Measurement): TrendDirection = when {
        m.score >= 2 -> TrendDirection.UP
        m.score <= -2 -> TrendDirection.DOWN
        else -> TrendDirection.SIDEWAYS
    }

    /** قدرت روند: تا ۶۰٪ از سهم رأی‌ها + تا ۴۰٪ از ER. هر دو اندازه‌گیری‌اند، نه امتیاز سلیقه‌ای. */
    private fun strengthOf(m: Measurement): Int {
        val voteShare = abs(m.score).toDouble() / MAX_VOTES
        val trendiness = (m.er ?: 0.0).coerceIn(0.0, 1.0)
        return (voteShare * 60.0 + trendiness * 40.0).roundToInt().coerceIn(0, 100)
    }

    // ── لایهٔ ۲ و ۳: عرض بازار، جهت دلار و جوّ ریسک ─────────────────────────
    /**
     * خوانش روند کلی بازار از مجموعهٔ نمادهایی که واقعاً اندازه گرفته شده‌اند.
     * کمتر از [MIN_BREADTH] نماد ⇒ UNKNOWN (و دلیلش صریحاً نوشته می‌شود).
     */
    fun overall(trends: List<SymbolTrend>, now: Long = System.currentTimeMillis()): MarketTrendRead {
        val known = trends.filter { it.known }
        val up = known.count { it.vote > 0 }
        val down = known.count { it.vote < 0 }
        val flat = known.size - up - down

        if (known.size < MIN_BREADTH) {
            return MarketTrendRead(
                bias = TrendDirection.UNKNOWN, strength = 0, dollarBias = TrendDirection.UNKNOWN,
                riskTone = RiskTone.UNKNOWN, breadthUp = up, breadthDown = down, breadthFlat = flat,
                measured = known.size, families = emptyList(),
                drivers = listOf(
                    "فقط ${known.size} نماد اندازه گرفته شد؛ برای خواندن روند کلی بازار حداقل " +
                        "$MIN_BREADTH نماد با کندل بستهٔ کافی لازم است",
                ),
                reasonFa = "روند کلی بازار اندازه گرفته نشد: کندل بستهٔ کافیِ نمادهای پویش‌شده در دسترس نیست. " +
                    "هیچ جهتی حدس زده نمی‌شود و معامله‌ها بدون این لایه بررسی می‌شوند.",
                computedAt = now,
            )
        }

        val netShare = (up - down).toDouble() / known.size
        val bias = when {
            netShare >= BREADTH_BIAS -> TrendDirection.UP
            netShare <= -BREADTH_BIAS -> TrendDirection.DOWN
            else -> TrendDirection.SIDEWAYS
        }
        val avgStrength = known.map { it.strength }.average()
        val strength = (abs(netShare) * 60.0 + avgStrength / 100.0 * 40.0).roundToInt().coerceIn(0, 100)
        val dollar = dollarBias(known)
        val tone = riskTone(known, dollar)
        val families = known.groupBy { it.family }
            .map { (family, list) -> familyTrendOf(family, list) }
            .sortedWith(compareByDescending<FamilyTrend> { it.measured }.thenBy { it.family.name })

        val drivers = ArrayList<String>(6)
        drivers += "عرض بازار: ${fmtPct(netShare * 100.0)} خالصِ صعودی ($up↑ / $down↓ / $flat خنثی از ${known.size} نماد)"
        drivers += "جهت دلار از ۶ جفت اصلی: ${dollar.label}"
        families.filter { it.family == MarketFamily.CRYPTO || it.family == MarketFamily.INDEX ||
            it.family == MarketFamily.US_STOCK || it.family == MarketFamily.GOLD }
            .forEach { drivers += it.summaryFa }
        drivers += "جوّ بازار: ${tone.label} (دارایی ریسکی در برابر طلا و دلار سنجیده شد)"

        val reason = buildString {
            append("روند کلی بازار ${bias.label} با قدرت $strength٪ است؛ از ${known.size} نمادِ دارای کندل بستهٔ کافی، ")
            append("$up نماد صعودی، $down نماد نزولی و $flat نماد خنثی اندازه گرفته شد. ")
            append("دلار ${dollar.label} و جوّ بازار ${tone.label} است. ")
            append("این خوانش از همان کندل‌های واقعیِ پویشگر ساخته شده و پیش‌بینی نیست؛ ")
            append("برای هر معامله، جهتِ تایم‌فریم مرجعِ همان نماد ملاکِ هم‌جهت بودن است.")
        }

        return MarketTrendRead(
            bias = bias, strength = strength, dollarBias = dollar, riskTone = tone,
            breadthUp = up, breadthDown = down, breadthFlat = flat, measured = known.size,
            families = families, drivers = drivers.take(8), reasonFa = reason, computedAt = now,
        )
    }

    private fun familyTrendOf(family: MarketFamily, list: List<SymbolTrend>): FamilyTrend {
        val up = list.count { it.vote > 0 }
        val down = list.count { it.vote < 0 }
        val flat = list.size - up - down
        val net = (up - down).toDouble() / list.size
        val direction = when {
            net >= BREADTH_BIAS -> TrendDirection.UP
            net <= -BREADTH_BIAS -> TrendDirection.DOWN
            else -> TrendDirection.SIDEWAYS
        }
        val strength = (abs(net) * 60.0 + list.map { it.strength }.average() / 100.0 * 40.0)
            .roundToInt().coerceIn(0, 100)
        return FamilyTrend(family, direction, strength, up, down, flat, list.size)
    }

    /** جهت دلار از همان جفت‌های اصلیِ پویش‌شده؛ علامت هر جفت به جایگاه دلار در آن جفت بستگی دارد. */
    private fun dollarBias(known: List<SymbolTrend>): TrendDirection {
        var score = 0
        var counted = 0
        known.forEach { trend ->
            val s = trend.symbol.trim().uppercase(java.util.Locale.ROOT)
            val sign = when {
                dollarBasePairs.contains(s) -> 1
                dollarQuotePairs.contains(s) -> -1
                else -> 0
            }
            if (sign == 0) return@forEach
            // یک جفتِ اصلیِ خنثی هم اطلاعات است (دلار تکان نخورده)، پس در مخرج شمرده می‌شود.
            score += sign * trend.vote
            counted += 1
        }
        if (counted == 0) return TrendDirection.UNKNOWN
        val share = score.toDouble() / counted
        return when {
            share >= 0.34 -> TrendDirection.UP
            share <= -0.34 -> TrendDirection.DOWN
            else -> TrendDirection.SIDEWAYS
        }
    }

    /**
     * جوّ بازار: میانگین رأیِ دارایی‌های ریسکی (رمزارز/سهام/شاخص) در برابر پناهگاه امن (طلا)
     * و جهت دلار. یک اندازه‌گیری از نوارِ فعلی بازار است، نه پیش‌بینی جریان پول.
     */
    private fun riskTone(known: List<SymbolTrend>, dollar: TrendDirection): RiskTone {
        val risk = known.filter {
            it.family == MarketFamily.CRYPTO || it.family == MarketFamily.US_STOCK || it.family == MarketFamily.INDEX
        }
        if (risk.isEmpty()) return RiskTone.UNKNOWN
        val haven = known.filter { it.family == MarketFamily.GOLD }
        val riskAvg = risk.map { it.vote }.average()
        val havenAvg = if (haven.isEmpty()) 0.0 else haven.map { it.vote }.average()
        val dollarVote = when (dollar) {
            TrendDirection.UP -> 1
            TrendDirection.DOWN -> -1
            else -> 0
        }
        val tone = riskAvg * 2.0 - havenAvg - dollarVote
        return when {
            tone >= 0.8 -> RiskTone.RISK_ON
            tone <= -0.8 -> RiskTone.RISK_OFF
            else -> RiskTone.MIXED
        }
    }

    // ── اضافه شدن به خودِ معامله ────────────────────────────────────────────
    /** جایگاه یک ورود نسبت به روندِ اندازه‌گیری‌شدهٔ همان نماد. */
    fun alignmentOf(action: SignalAction, trend: SymbolTrend?): TrendAlignment {
        if (action == SignalAction.NO_TRADE) return TrendAlignment.NEUTRAL
        if (trend == null || !trend.known) return TrendAlignment.UNKNOWN
        return when {
            trend.direction == TrendDirection.SIDEWAYS -> TrendAlignment.NEUTRAL
            trend.direction == TrendDirection.UP && action == SignalAction.BUY -> TrendAlignment.WITH
            trend.direction == TrendDirection.DOWN && action == SignalAction.SELL -> TrendAlignment.WITH
            else -> TrendAlignment.AGAINST
        }
    }

    /**
     * اثرِ روند روی یک ورود. سه قاعدهٔ مستند:
     *  ۱. روش روندی خلاف جهتِ اندازه‌گیری‌شده ⇒ مسدود (دلیل فارسی با عددهای همان اندازه‌گیری).
     *  ۲. بازگشت به میانگین فقط در بازارِ بدون روند ⇒ اگر روندی هست، مسدود.
     *  ۳. ورود خلافِ جوّ کلی بازار روی دارایی ریسکی ⇒ مسدود نمی‌شود، ولی کف امتیاز (+۶) و
     *     کف اطمینان (+۵) سخت‌تر می‌شود.
     * نبودِ داده هرگز به معنی مسدودکردن نیست؛ فقط کف اطمینان را بالا می‌برد و صریح اعلام می‌شود.
     */
    fun entryGate(
        action: SignalAction,
        method: TradeMethod?,
        trend: SymbolTrend?,
        overall: MarketTrendRead? = null,
    ): TrendGate {
        if (action == SignalAction.NO_TRADE) {
            return TrendGate(true, 0, 0.0, null, "سیگنال ورودی نیست؛ لایهٔ روند اعمال نمی‌شود")
        }
        val methodLabel = method?.label ?: "—"
        val referenceLabel = trend?.higherLabel ?: trend?.intervalLabel ?: "تایم‌فریم پایه"
        val alignment = alignmentOf(action, trend)
        val notes = ArrayList<String>(3)
        var scoreAdd = 0
        var confAdd = 0.0
        var blocker: String? = null

        when (method) {
            TradeMethod.RANGE_MEAN_REVERSION -> if (trend != null && trend.known &&
                trend.direction != TrendDirection.SIDEWAYS
            ) {
                blocker = "روش «$methodLabel» ذاتاً خلاف حرکت است و فقط در بازارِ بدون روند مجاز است؛ " +
                    "روند $referenceLabel این نماد ${trend.direction.label} با قدرت ${trend.strength}٪ است"
            } else if (trend == null || !trend.known) {
                confAdd += 6.0
                notes += "روند اندازه گرفته نشد؛ ورودِ بازگشت به میانگین فقط با اطمینان بیشتر"
            } else {
                notes += "بازار خنثی است؛ بازگشت به میانگین با روند نمی‌جنگد"
            }

            TradeMethod.TREND_PULLBACK, TradeMethod.BREAKOUT_MOMENTUM, TradeMethod.OPENING_DRIVE -> when (alignment) {
                TrendAlignment.AGAINST -> blocker = "روش «$methodLabel» ادامهٔ روند است، ولی ورود " +
                    "${sideLabel(action)} خلاف جهتِ روندِ اندازه‌گیری‌شدهٔ $referenceLabel " +
                    "(${trend?.direction?.label ?: "—"} با قدرت ${trend?.strength ?: 0}٪) است"
                TrendAlignment.NEUTRAL -> {
                    confAdd += 4.0
                    notes += "روند $referenceLabel خنثی است؛ ورود روندی فقط با اطمینان بالاتر"
                }
                TrendAlignment.UNKNOWN -> {
                    confAdd += 4.0
                    notes += "روند اندازه گرفته نشد؛ این ورود بدون تأییدِ لایهٔ روند ثبت می‌شود"
                }
                TrendAlignment.WITH -> if ((trend?.strength ?: 0) < WEAK_STRENGTH) {
                    confAdd += 3.0
                    notes += "هم‌جهت است ولی روند ضعیف است (قدرت ${trend?.strength ?: 0}٪ از $WEAK_STRENGTH٪)"
                } else {
                    notes += "هم‌جهت با روند $referenceLabel (قدرت ${trend?.strength ?: 0}٪)"
                }
            }

            else -> notes += "روتر بازار روشی تعیین نکرده است؛ لایهٔ روند فقط اطلاع‌رسانی می‌کند"
        }

        val family = trend?.family ?: MarketFamily.OTHER
        val tone = overall?.riskTone
        val risky = family == MarketFamily.CRYPTO || family == MarketFamily.US_STOCK || family == MarketFamily.INDEX
        if (blocker == null && risky && tone != null && tone != RiskTone.UNKNOWN) {
            val againstTone = (tone == RiskTone.RISK_OFF && action == SignalAction.BUY) ||
                (tone == RiskTone.RISK_ON && action == SignalAction.SELL)
            if (againstTone) {
                scoreAdd += 6
                confAdd += 5.0
                notes += "جوّ کلی بازار «${tone.label}» است و این ${sideLabel(action)} روی دارایی ریسکی خلاف آن است؛ " +
                    "کف امتیاز +$SCORE_TONE و کف اطمینان +$CONF_TONE"
            } else {
                notes += "جوّ کلی بازار «${tone.label}» با این ورود هم‌جهت است"
            }
        }
        if (blocker == null && overall != null && overall.known && trend != null && trend.known &&
            overall.bias != TrendDirection.SIDEWAYS && trend.vote != 0 && trend.vote != overall.vote &&
            family != MarketFamily.GOLD && family != MarketFamily.SILVER
        ) {
            confAdd += 2.0
            notes += "این نماد (${trend.direction.label}) خلاف جهت کلی بازار (${overall.bias.label}) است"
        }

        val note = buildString {
            append("جایگاه ورود: ${alignment.label}")
            overall?.let {
                append(" · روند کلی بازار ${it.bias.label} (قدرت ${it.strength}٪) · ${it.breadthFa}")
                append(" · دلار ${it.dollarBias.label} · جوّ ${it.riskTone.label}")
            }
            trend?.let { append(" · روند $referenceLabel نماد ${it.direction.label} (قدرت ${it.strength}٪، ER=${fmt(it.efficiencyRatio)})") }
            if (notes.isNotEmpty()) {
                append(" · ")
                append(notes.joinToString(" · "))
            }
        }
        return TrendGate(
            allowed = blocker == null,
            minScoreAdd = scoreAdd,
            minConfidenceAdd = confAdd,
            blockerFa = blocker,
            noteFa = note,
        )
    }

    /** بستهٔ کاملِ قابل ثبت در ژورنال: روند + جوّ بازار + جایگاه این معامله + اثرش روی آستانه‌ها. */
    fun contextOf(
        action: SignalAction,
        trend: SymbolTrend?,
        overall: MarketTrendRead?,
        method: TradeMethod? = null,
    ): TrendContext {
        val gate = entryGate(action, method, trend, overall)
        return TrendContext(
            action = action, symbol = trend, overall = overall,
            alignment = alignmentOf(action, trend), gate = gate, noteFa = gate.noteFa,
        )
    }

    fun sideLabel(action: SignalAction): String = when (action) {
        SignalAction.BUY -> "خرید (LONG)"
        SignalAction.SELL -> "فروش (SHORT)"
        SignalAction.NO_TRADE -> "بدون ورود"
    }

    private fun fmt(value: Double?): String =
        if (value == null) "—" else String.format(java.util.Locale.US, "%.2f", value)

    private fun fmtPct(value: Double): String =
        String.format(java.util.Locale.US, "%.1f%%", value)
}
