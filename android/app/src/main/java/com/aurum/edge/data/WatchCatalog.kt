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
}

data class DisplayQuote(val quote: Quote?, val sourceId: String, val fallback: Boolean)

/** Display-only fallback; a recent HTML scrape is NOT proof of a fresh provider trade. */
object WatchDisplay {
    fun choose(symbol: WatchSymbol, selection: WatchSelection, quotes: Map<String, Quote>,
               now: Long = System.currentTimeMillis()): DisplayQuote {
        val preferred = selection.preferredSourceId
        fun readable(id: String): Boolean {
            val q = quotes[id] ?: return false
            return id in symbol.providerCodes && q.price?.let { it.isFinite() && it > 0 } == true &&
                q.unit == symbol.unit && !q.stale && q.error == null &&
                q.ts in (now - symbol.maxAgeMillis)..(now + 60_000L) &&
                (q.providerAt == null || q.providerAt in (now - symbol.maxAgeMillis)..(now + 60_000L))
        }
        val picked = selection.enabledSources.firstOrNull { it == preferred && readable(it) }
            ?: selection.enabledSources.firstOrNull(::readable)
            ?: preferred.takeIf { it in selection.enabledSources && quotes[it]?.price != null }
            ?: selection.enabledSources.firstOrNull { quotes[it]?.price != null }
            ?: preferred
        return DisplayQuote(quotes[picked], picked, picked != preferred && picked.isNotBlank())
    }
}

enum class VerificationStatus { CONFIRMED, CONFLICT, UNVERIFIED, NO_DATA }

data class Verification(
    val status: VerificationStatus,
    val freshSources: Int,
    val spreadPct: Double? = null,
    val reason: String,
)

/** Retrieval time alone does not prove a price is fresh. Cached, undated and mismatched quotes cannot confirm. */
object SourceComparison {
    fun isFresh(symbol: WatchSymbol, quote: Quote, now: Long = System.currentTimeMillis()): Boolean {
        val providerAt = quote.providerAt ?: return false
        return !quote.stale && quote.error == null && quote.unit == symbol.unit && SourceCatalog.find(quote.sourceId)?.unit == symbol.unit &&
            quote.price != null && quote.price.isFinite() && quote.price > 0 &&
            providerAt in (now - symbol.maxAgeMillis)..(now + 60_000L) &&
            quote.ts in (now - symbol.maxAgeMillis)..(now + 60_000L)
    }

    fun verify(symbol: WatchSymbol, sources: List<String>, quotes: Map<String, Quote>, now: Long = System.currentTimeMillis()): Verification {
        val valid = sources.distinct().mapNotNull { id -> quotes[id]?.takeIf { isFresh(symbol, it, now) } }
        if (valid.isEmpty()) return Verification(
            if (sources.any { quotes[it]?.price != null }) VerificationStatus.UNVERIFIED else VerificationStatus.NO_DATA,
            0, reason = "قیمت تازه با زمان معتبر ارائه‌دهنده موجود نیست؛ کش/داده بدون زمان تأیید نمی‌شود",
        )
        if (valid.size < 2) return Verification(VerificationStatus.UNVERIFIED, valid.size,
            reason = "برای تأیید دست‌کم دو منبع مستقل با قیمت تازه لازم است")
        // SourceCatalog IDs are independent; never combine different units or spot with futures.
        val values = valid.mapNotNull { it.price }
        val min = values.minOrNull() ?: return Verification(VerificationStatus.UNVERIFIED, 0, reason = "قیمت موجود نیست")
        val max = values.maxOrNull() ?: return Verification(VerificationStatus.UNVERIFIED, 0, reason = "قیمت موجود نیست")
        val spread = abs(max - min) / ((max + min) / 2) * 100
        return if (spread <= symbol.tolerancePct) {
            Verification(VerificationStatus.CONFIRMED, valid.size, spread, "اختلاف قیمت منابع مستقل زیر ${symbol.tolerancePct}% است")
        } else {
            Verification(VerificationStatus.CONFLICT, valid.size, spread, "اختلاف قیمت منابع از ${symbol.tolerancePct}% بیشتر است؛ از ورود خودکار پرهیز کنید")
        }
    }
}
