package com.aurum.edge.data

import kotlin.math.abs

/** Each mapping identifies the *same* instrument and quote unit at a vetted read-only provider. */
data class WatchSymbol(
    val id: String,
    val label: String,
    val unit: String,
    val providerCodes: Map<String, String>,
    val defaultSources: List<String>,
    val maxAgeMillis: Long,
    val tolerancePct: Double,
)

object WatchCatalog {
    /** Watchlist order: Iran gold/USD, Commodities, Forex pairs, Top Global Shares/Stocks, and Cryptos. */
    val symbols = listOf(
        WatchSymbol("IR_GOLD18", "طلای ۱۸ عیار ایران", "تومان/گرم", linkedMapOf(
            "tgju_public" to "geram18",
        ), listOf("tgju_public"), 30 * 60_000L, 2.0),
        WatchSymbol("USD/IRR", "دلار آزاد ایران", "تومان", linkedMapOf(
            "tgju_public" to "price_dollar_rl",
        ), listOf("tgju_public"), 30 * 60_000L, 2.0),

        // ── فلزات و کالاها ────────────────────────────────────────────────
        WatchSymbol("XAU/USD", "طلای جهانی (هر انس)", "$", linkedMapOf(
            "tradingview_scanner" to "OANDA:XAUUSD", "gold_api_public" to "XAU", "stocks_yahoo" to "GC=F", "twelve_data_quote" to "XAU/USD",
        ), listOf("tradingview_scanner"), 10 * 60_000L, 0.5),
        WatchSymbol("XAG/USD", "نقره جهانی (هر انس)", "$", linkedMapOf(
            "tradingview_scanner" to "OANDA:XAGUSD", "stocks_yahoo" to "SI=F", "twelve_data_quote" to "XAG/USD",
        ), listOf("tradingview_scanner"), 10 * 60_000L, 0.8),
        WatchSymbol("USOIL", "نفت خام WTI", "$", linkedMapOf(
            "tradingview_scanner" to "TVC:USOIL", "stocks_yahoo" to "CL=F",
        ), listOf("tradingview_scanner"), 10 * 60_000L, 0.8),
        WatchSymbol("UKOIL", "نفت برنت", "$", linkedMapOf(
            "tradingview_scanner" to "TVC:UKOIL", "stocks_yahoo" to "BZ=F",
        ), listOf("tradingview_scanner"), 10 * 60_000L, 0.8),
        WatchSymbol("COPPER", "مس جهانی", "$", linkedMapOf(
            "tradingview_scanner" to "CAPITALCOM:COPPER", "stocks_yahoo" to "HG=F",
        ), listOf("tradingview_scanner"), 10 * 60_000L, 0.8),

        // ── جفت‌ارزهای اصلی و کراس فارکس ──────────────────────────────────
        WatchSymbol("EUR/USD", "یورو / دلار", "$", linkedMapOf("tradingview_scanner" to "OANDA:EURUSD", "stocks_yahoo" to "EURUSD=X", "twelve_data_quote" to "EUR/USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("GBP/USD", "پوند / دلار", "$", linkedMapOf("tradingview_scanner" to "OANDA:GBPUSD", "stocks_yahoo" to "GBPUSD=X", "twelve_data_quote" to "GBP/USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("AUD/USD", "دلار استرالیا / دلار", "$", linkedMapOf("tradingview_scanner" to "OANDA:AUDUSD", "stocks_yahoo" to "AUDUSD=X", "twelve_data_quote" to "AUD/USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("NZD/USD", "دلار نیوزیلند / دلار", "$", linkedMapOf("tradingview_scanner" to "OANDA:NZDUSD", "stocks_yahoo" to "NZDUSD=X", "twelve_data_quote" to "NZD/USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("USD/JPY", "دلار / ین ژاپن", "JPY", linkedMapOf("tradingview_scanner" to "OANDA:USDJPY", "stocks_yahoo" to "JPY=X", "twelve_data_quote" to "USD/JPY"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("USD/CHF", "دلار / فرانک سوئیس", "CHF", linkedMapOf("tradingview_scanner" to "OANDA:USDCHF", "stocks_yahoo" to "CHF=X", "twelve_data_quote" to "USD/CHF"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("USD/CAD", "دلار / دلار کانادا", "CAD", linkedMapOf("tradingview_scanner" to "OANDA:USDCAD", "stocks_yahoo" to "CAD=X", "twelve_data_quote" to "USD/CAD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("EUR/GBP", "یورو / پوند", "GBP", linkedMapOf("tradingview_scanner" to "OANDA:EURGBP", "stocks_yahoo" to "EURGBP=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("EUR/JPY", "یورو / ین", "JPY", linkedMapOf("tradingview_scanner" to "OANDA:EURJPY", "stocks_yahoo" to "EURJPY=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("GBP/JPY", "پوند / ین", "JPY", linkedMapOf("tradingview_scanner" to "OANDA:GBPJPY", "stocks_yahoo" to "GBPJPY=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("AUD/JPY", "دلار استرالیا / ین", "JPY", linkedMapOf("tradingview_scanner" to "OANDA:AUDJPY", "stocks_yahoo" to "AUDJPY=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("CAD/JPY", "دلار کانادا / ین", "JPY", linkedMapOf("tradingview_scanner" to "OANDA:CADJPY", "stocks_yahoo" to "CADJPY=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("CHF/JPY", "فرانک سوئیس / ین", "JPY", linkedMapOf("tradingview_scanner" to "OANDA:CHFJPY", "stocks_yahoo" to "CHFJPY=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("NZD/JPY", "دلار نیوزیلند / ین", "JPY", linkedMapOf("tradingview_scanner" to "OANDA:NZDJPY", "stocks_yahoo" to "NZDJPY=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("EUR/AUD", "یورو / دلار استرالیا", "AUD", linkedMapOf("tradingview_scanner" to "OANDA:EURAUD", "stocks_yahoo" to "EURAUD=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("EUR/CAD", "یورو / دلار کانادا", "CAD", linkedMapOf("tradingview_scanner" to "OANDA:EURCAD", "stocks_yahoo" to "EURCAD=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("EUR/CHF", "یورو / فرانک سوئیس", "CHF", linkedMapOf("tradingview_scanner" to "OANDA:EURCHF", "stocks_yahoo" to "EURCHF=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("GBP/AUD", "پوند / دلار استرالیا", "AUD", linkedMapOf("tradingview_scanner" to "OANDA:GBPAUD", "stocks_yahoo" to "GBPAUD=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("GBP/CAD", "پوند / دلار کانادا", "CAD", linkedMapOf("tradingview_scanner" to "OANDA:GBPCAD", "stocks_yahoo" to "GBPCAD=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("GBP/CHF", "پوند / فرانک سوئیس", "CHF", linkedMapOf("tradingview_scanner" to "OANDA:GBPCHF", "stocks_yahoo" to "GBPCHF=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("AUD/CAD", "دلار استرالیا / دلار کانادا", "CAD", linkedMapOf("tradingview_scanner" to "OANDA:AUDCAD", "stocks_yahoo" to "AUDCAD=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("AUD/CHF", "دلار استرالیا / فرانک", "CHF", linkedMapOf("tradingview_scanner" to "OANDA:AUDCHF", "stocks_yahoo" to "AUDCHF=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("AUD/NZD", "دلار استرالیا / دلار نیوزیلند", "NZD", linkedMapOf("tradingview_scanner" to "OANDA:AUDNZD", "stocks_yahoo" to "AUDNZD=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("CAD/CHF", "دلار کانادا / فرانک", "CHF", linkedMapOf("tradingview_scanner" to "OANDA:CADCHF", "stocks_yahoo" to "CADCHF=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),
        WatchSymbol("NZD/CAD", "دلار نیوزیلند / دلار کانادا", "CAD", linkedMapOf("tradingview_scanner" to "OANDA:NZDCAD", "stocks_yahoo" to "NZDCAD=X"), listOf("tradingview_scanner"), 5 * 60_000L, 0.3),

        // ── سهام‌های برتر جهانی و شاخص‌ها ────────────────────────────────
        WatchSymbol("AAPL", "اپل (Apple Inc)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:AAPL", "stocks_yahoo" to "AAPL"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("TSLA", "تسلا (Tesla Inc)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:TSLA", "stocks_yahoo" to "TSLA"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("NVDA", "ان‌ویدیا (NVIDIA Corp)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:NVDA", "stocks_yahoo" to "NVDA"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("MSFT", "مایکروسافت (Microsoft)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:MSFT", "stocks_yahoo" to "MSFT"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("AMZN", "آمازون (Amazon.com)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:AMZN", "stocks_yahoo" to "AMZN"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("GOOGL", "گوگل (Alphabet Inc)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:GOOGL", "stocks_yahoo" to "GOOGL"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("META", "متا / فیس‌بوک (Meta Platforms)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:META", "stocks_yahoo" to "META"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("AMD", "ای‌ام‌دی (Advanced Micro Devices)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:AMD", "stocks_yahoo" to "AMD"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("NFLX", "نتفلیکس (Netflix Inc)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:NFLX", "stocks_yahoo" to "NFLX"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("INTC", "اینتل (Intel Corp)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:INTC", "stocks_yahoo" to "INTC"), listOf("tradingview_scanner"), 15 * 60_000L, 1.0),
        WatchSymbol("SPY", "شاخص SPDR S&P 500 ETF", "$", linkedMapOf("tradingview_scanner" to "AMEX:SPY", "stocks_yahoo" to "SPY"), listOf("tradingview_scanner"), 15 * 60_000L, 0.5),
        WatchSymbol("QQQ", "شاخص Invesco QQQ (Nasdaq 100)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:QQQ", "stocks_yahoo" to "QQQ"), listOf("tradingview_scanner"), 15 * 60_000L, 0.5),
        WatchSymbol("PLTR", "پالانتیر (Palantir Tech)", "$", linkedMapOf("tradingview_scanner" to "NYSE:PLTR", "stocks_yahoo" to "PLTR"), listOf("tradingview_scanner"), 15 * 60_000L, 1.2),
        WatchSymbol("COIN", "کوین‌بیس (Coinbase Global)", "$", linkedMapOf("tradingview_scanner" to "NASDAQ:COIN", "stocks_yahoo" to "COIN"), listOf("tradingview_scanner"), 15 * 60_000L, 1.2),
        WatchSymbol("BABA", "علی‌بابا (Alibaba Group)", "$", linkedMapOf("tradingview_scanner" to "NYSE:BABA", "stocks_yahoo" to "BABA"), listOf("tradingview_scanner"), 15 * 60_000L, 1.2),

        // ── ارزهای دیجیتال برتر ───────────────────────────────────────────
        WatchSymbol("BTC/USDT", "بیت‌کوین / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:BTCUSDT", "stocks_yahoo" to "BTC-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.5),
        WatchSymbol("ETH/USDT", "اتریوم / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:ETHUSDT", "stocks_yahoo" to "ETH-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.5),
        WatchSymbol("SOL/USDT", "سولانا / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:SOLUSDT", "stocks_yahoo" to "SOL-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.8),
        WatchSymbol("BNB/USDT", "بی‌ان‌بی / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:BNBUSDT", "stocks_yahoo" to "BNB-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 0.8),
        WatchSymbol("XRP/USDT", "ریپل / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:XRPUSDT", "stocks_yahoo" to "XRP-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.0),
        WatchSymbol("DOGE/USDT", "دوج‌کوین / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:DOGEUSDT", "stocks_yahoo" to "DOGE-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.0),
        WatchSymbol("ADA/USDT", "کاردانو / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:ADAUSDT", "stocks_yahoo" to "ADA-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.0),
        WatchSymbol("AVAX/USDT", "آوالانچ / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:AVAXUSDT", "stocks_yahoo" to "AVAX-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.0),
        WatchSymbol("LINK/USDT", "چین‌لینک / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:LINKUSDT", "stocks_yahoo" to "LINK-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.0),
        WatchSymbol("SUI/USDT", "سویی / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:SUIUSDT", "stocks_yahoo" to "SUI-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.2),
        WatchSymbol("NEAR/USDT", "نیر / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:NEARUSDT", "stocks_yahoo" to "NEAR-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.2),
        WatchSymbol("PEPE/USDT", "پپه / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:PEPEUSDT", "stocks_yahoo" to "PEPE-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.5),
        WatchSymbol("TON/USDT", "تون‌کوین / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:TONUSDT", "stocks_yahoo" to "TON-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.2),
        WatchSymbol("DOT/USDT", "پولکادات / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:DOTUSDT", "stocks_yahoo" to "DOT-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.2),
        WatchSymbol("LTC/USDT", "لایت‌کوین / تتر", "$", linkedMapOf("tradingview_scanner" to "BINANCE:LTCUSDT", "stocks_yahoo" to "LTC-USD"), listOf("tradingview_scanner"), 5 * 60_000L, 1.0),
    )

    fun find(id: String): WatchSymbol? = symbols.firstOrNull { it.id == id }

    /** Symbols selectable for the chart/feed. Iran cash-board rows are watch-only. */
    val chartSymbols: List<String> = symbols.filter {
        "stocks_yahoo" in it.providerCodes || "twelve_data_quote" in it.providerCodes ||
            "tradingview_scanner" in it.providerCodes
    }.map { it.id }
}

data class DisplayQuote(val quote: Quote?, val sourceId: String, val fallback: Boolean)

/** Different questions: can this number be displayed, and can its publisher time confirm it? */
enum class QuoteDisplayState { NO_DATA, ERROR, CACHED, INVALID, OLD, UNDATED, DATED }

data class QuoteAssessment(val state: QuoteDisplayState, val detail: String) {
    val readable: Boolean get() = state == QuoteDisplayState.UNDATED || state == QuoteDisplayState.DATED
    val verifiable: Boolean get() = state == QuoteDisplayState.DATED
}

    /** Retrieval time alone does not prove a price is fresh. Cached, undated and mismatched quotes cannot confirm. */
    object SourceComparison {
        // providerCodes is the unit contract: each mapping is the *same* instrument and quote unit
        // at that provider. SourceDef.unit is only a static label and cannot express multi-unit
        // providers (Twelve Data serves both $ and JPY/CHF/CAD pairs), so it is not compared here.
        fun assess(symbol: WatchSymbol, sourceId: String, quote: Quote?, now: Long = System.currentTimeMillis()): QuoteAssessment {
            if (sourceId !in symbol.providerCodes)
                return QuoteAssessment(QuoteDisplayState.INVALID, "نماد/واحد این منبع برای این کارت تعریف نشده")
        if (quote == null) return QuoteAssessment(QuoteDisplayState.NO_DATA, "هنوز پاسخی دریافت نشده")
        if (quote.sourceId != sourceId || quote.unit != symbol.unit)
            return QuoteAssessment(QuoteDisplayState.INVALID, "شناسهٔ منبع یا واحد قیمت ناهماهنگ است")
        if (quote.error != null) return QuoteAssessment(QuoteDisplayState.ERROR, "دریافت منبع ناموفق است؛ قیمت قبلی فقط جهت مشاهده")
        if (quote.stale) return QuoteAssessment(QuoteDisplayState.CACHED, "قیمت کش‌شده است، نه قیمت آنلاین")
        if (quote.price?.let { it.isFinite() && it > 0 } != true)
            return QuoteAssessment(QuoteDisplayState.NO_DATA, "قیمت معتبر ارائه نشده")
        if (quote.ts !in (now - symbol.maxAgeMillis)..(now + 60_000L))
            return QuoteAssessment(QuoteDisplayState.OLD, "زمان دریافت قدیمی/نامعتبر است")
        if (quote.providerAt == null)
            return QuoteAssessment(QuoteDisplayState.UNDATED, "صفحه/پاسخ تازه خوانده شد، ولی تاریخ کامل آخرین قیمتِ ناشر نامشخص است")
        if (quote.providerAt !in (now - symbol.maxAgeMillis)..(now + 60_000L))
            return QuoteAssessment(QuoteDisplayState.OLD, "زمان آخرین قیمت ناشر قدیمی/نامعتبر است؛ شاید بازار بسته یا منبع دیرهنگام باشد")
        return QuoteAssessment(QuoteDisplayState.DATED, "قیمت زمان‌دار و تازهٔ یک منبع؛ هنوز لزوماً تأیید دومنبعی نیست")
    }

    fun isFresh(symbol: WatchSymbol, quote: Quote, now: Long = System.currentTimeMillis()): Boolean =
        assess(symbol, quote.sourceId, quote, now).verifiable

    fun verify(symbol: WatchSymbol, sources: List<String>, quotes: Map<String, Quote>, now: Long = System.currentTimeMillis()): Verification {
        val enabled = sources.distinct().filter { it in symbol.providerCodes }
        if (enabled.isEmpty()) return Verification(VerificationStatus.NO_DATA, 0,
            reason = "منبعی برای این نماد فعال نیست؛ از تنظیمات انتخاب کنید", badge = "منبع ندارد")
        val assessed = enabled.associateWith { assess(symbol, it, quotes[it], now) }
        // Map keys alone do not prove independence: the quote's own sourceId must match too.
        val valid = enabled.mapNotNull { id -> quotes[id]?.takeIf { assessed[id]?.verifiable == true } }
        if (valid.size < 2) {
            val issues = enabled.mapNotNull { id -> assessed[id]?.takeUnless { it.verifiable }?.let {
                "${SourceCatalog.find(id)?.title ?: id}: ${it.detail}"
            } }
            val onlyOneAvailable = symbol.providerCodes.size < 2
            val undated = assessed.values.any { it.state == QuoteDisplayState.UNDATED }
            val old = assessed.values.any { it.state == QuoteDisplayState.OLD }
            val badge = when {
                assessed.values.all { it.state == QuoteDisplayState.NO_DATA } -> "قیمت نیست"
                enabled.size == 1 && undated -> "زمان/تک‌منبع"
                enabled.size == 1 && old -> "قدیمی/تک‌منبع"
                enabled.size == 1 && valid.size == 1 -> "تک‌منبعی"
                undated && old -> "زمان/قدمت"
                undated -> "زمان نامعلوم"
                old -> "قیمت قدیمی"
                assessed.values.any { it.state in setOf(QuoteDisplayState.CACHED, QuoteDisplayState.ERROR) } -> "قطع/کش منبع"
                valid.size == 1 || onlyOneAvailable -> "منبع دوم نیست"
                else -> "نیاز به داده"
            }
            val sourceLimit = when {
                onlyOneAvailable -> "در این نسخه برای این نماد منبع مستقل دومی تعریف نشده است. "
                enabled.size == 1 -> "فقط یک منبع فعال است. "
                else -> ""
            }
            val reason = "تأیید دو منبع تازه ممکن نیست (${valid.size}/۲). $sourceLimit" + issues.joinToString("؛ ")
            val hasPrice = enabled.any { id -> quotes[id]?.price?.let { it.isFinite() && it > 0 } == true }
            return Verification(if (hasPrice) VerificationStatus.UNVERIFIED else VerificationStatus.NO_DATA,
                valid.size, reason = reason, badge = badge)
        }
        // SourceCatalog IDs are independent; never combine different units or spot with futures.
        val values = valid.mapNotNull { it.price }
        val min = values.minOrNull() ?: error("قیمت منبع تازه باید موجود باشد")
        val max = values.maxOrNull() ?: error("قیمت منبع تازه باید موجود باشد")
        val spread = abs(max - min) / ((max + min) / 2) * 100
        return if (spread <= symbol.tolerancePct) {
            Verification(VerificationStatus.CONFIRMED, valid.size, spread,
                "${valid.size} منبع زمان‌دار تازه هم‌واحد؛ اختلاف زیر ${symbol.tolerancePct}% است (نه تضمین صحت/مجوز سفارش)", "همخوان")
        } else {
            Verification(VerificationStatus.CONFLICT, valid.size, spread,
                "اختلاف ${valid.size} منبع زمان‌دار از ${symbol.tolerancePct}% بیشتر است؛ از ورود خودکار پرهیز کنید", "اختلاف")
        }
    }
}

data class Verification(
    val status: VerificationStatus,
    val freshSources: Int,
    val spreadPct: Double? = null,
    val reason: String,
    /** Human-readable display diagnosis; status stays conservative for any safety consumers. */
    val badge: String,
)

enum class VerificationStatus { CONFIRMED, CONFLICT, UNVERIFIED, NO_DATA }

/** Display-only fallback; a recent HTML scrape is NOT proof of a fresh provider trade. */
object WatchDisplay {
    fun choose(symbol: WatchSymbol, selection: WatchSelection, quotes: Map<String, Quote>,
               now: Long = System.currentTimeMillis()): DisplayQuote {
        val preferred = selection.preferredSourceId
        fun showable(id: String): Boolean {
            val q = quotes[id] ?: return false
            return id in symbol.providerCodes && q.sourceId == id && q.unit == symbol.unit &&
                q.price?.let { it.isFinite() && it > 0 } == true
        }
        fun readable(id: String): Boolean = showable(id) && SourceComparison.assess(symbol, id, quotes[id], now).readable
        val picked = selection.enabledSources.firstOrNull { it == preferred && readable(it) }
            ?: selection.enabledSources.firstOrNull(::readable)
            ?: preferred.takeIf { it in selection.enabledSources && showable(it) }
            ?: selection.enabledSources.firstOrNull(::showable)
            ?: preferred
        return DisplayQuote(quotes[picked]?.takeIf { showable(picked) }, picked, picked != preferred && picked.isNotBlank())
    }
}
