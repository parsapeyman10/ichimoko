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
    val symbols = listOf(
        WatchSymbol("BTC/USD", "بیت‌کوین", "$", linkedMapOf(
            "crypto_coingecko" to "bitcoin", "stocks_yahoo" to "BTC-USD", "twelve_data_quote" to "BTC/USD",
        ), listOf("crypto_coingecko", "stocks_yahoo"), 5 * 60_000L, 0.8),
        WatchSymbol("ETH/USD", "اتریوم", "$", linkedMapOf(
            "crypto_coingecko" to "ethereum", "stocks_yahoo" to "ETH-USD", "twelve_data_quote" to "ETH/USD",
        ), listOf("crypto_coingecko", "stocks_yahoo"), 5 * 60_000L, 0.8),
        WatchSymbol("XAU/USD", "طلای جهانی (هر انس)", "$",  linkedMapOf(
            "twelve_data_quote" to "XAU/USD",
        ), listOf("twelve_data_quote"), 10 * 60_000L, 0.5),
        WatchSymbol("AAPL", "اپل", "$", linkedMapOf(
            "stocks_yahoo" to "AAPL", "twelve_data_quote" to "AAPL",
        ), listOf("stocks_yahoo"), 20 * 60_000L, 0.5),
        WatchSymbol("USD/IRT", "دلار آزاد", "تومان", linkedMapOf(
            "iran_tgju_web" to "price_dollar_rl", "iran_navasan_fiat" to "usd",
        ), listOf("iran_tgju_web", "iran_navasan_fiat"), 60 * 60_000L, 1.0),
        WatchSymbol("GOLD18/IRT", "طلای ۱۸ عیار / گرم", "تومان", linkedMapOf(
            "iran_tgju_web" to "geram18", "iran_navasan_gold" to "18ayar",
        ), listOf("iran_tgju_web", "iran_navasan_gold"), 60 * 60_000L, 1.0),
        WatchSymbol("GOLD24/IRT", "طلای ۲۴ عیار / گرم", "تومان", linkedMapOf(
            "iran_tgju_web" to "geram24",
        ), listOf("iran_tgju_web"), 30 * 60_000L, 1.0),
        WatchSymbol("MESGHAL/IRT", "مثقال طلا", "تومان", linkedMapOf(
            "iran_tgju_web" to "mesghal",
        ), listOf("iran_tgju_web"), 30 * 60_000L, 1.0),
        WatchSymbol("SEKEE/IRT", "سکه امامی", "تومان", linkedMapOf(
            "iran_tgju_web" to "sekee", "iran_navasan_gold" to "sekkeh",
        ), listOf("iran_tgju_web", "iran_navasan_gold"), 4 * 60 * 60_000L, 1.0),
        WatchSymbol("SEKEB/IRT", "سکه بهار آزادی", "تومان", linkedMapOf(
            "iran_tgju_web" to "sekeb", "iran_navasan_gold" to "bahar",
        ), listOf("iran_tgju_web", "iran_navasan_gold"), 4 * 60 * 60_000L, 1.0),
        WatchSymbol("NIM/IRT", "نیم‌سکه", "تومان", linkedMapOf(
            "iran_tgju_web" to "nim", "iran_navasan_gold" to "nim",
        ), listOf("iran_tgju_web", "iran_navasan_gold"), 4 * 60 * 60_000L, 1.0),
        WatchSymbol("ROB/IRT", "ربع‌سکه", "تومان", linkedMapOf(
            "iran_tgju_web" to "rob", "iran_navasan_gold" to "rob",
        ), listOf("iran_tgju_web", "iran_navasan_gold"), 4 * 60 * 60_000L, 1.0),
        WatchSymbol("GERAMI/IRT", "سکهٔ یک‌گرمی", "تومان", linkedMapOf(
            "iran_tgju_web" to "gerami", "iran_navasan_gold" to "gerami",
        ), listOf("iran_tgju_web", "iran_navasan_gold"), 4 * 60 * 60_000L, 1.0),
    )

    fun find(id: String): WatchSymbol? = symbols.firstOrNull { it.id == id }

    /** Keep exploratory watch requests in the explicitly chosen workspace. */
    fun forWorkspace(id: String): List<WatchSymbol> = when (id) {
        "forex" -> symbols.filter { it.id == "XAU/USD" }
        "iran_stocks" -> symbols.filter { it.id.endsWith("/IRT") }
        else -> emptyList() // Crypto and Nobitex use their own dedicated read-only feeds
    }
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
    fun assess(symbol: WatchSymbol, sourceId: String, quote: Quote?, now: Long = System.currentTimeMillis()): QuoteAssessment {
        if (sourceId !in symbol.providerCodes || SourceCatalog.find(sourceId)?.unit != symbol.unit)
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
