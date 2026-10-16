package com.aurum.edge.data

import kotlin.math.abs

/** Each mapping identifies the exact instrument and its single dedicated source (1-to-1). */
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
    /**
     * Focused 15-symbol universe with strict 1-to-1 source assignment:
     * - 2 Metals: XAU/USD, XAG/USD
     * - 1 Index: DOW (Dow Jones / US30)
     * - 7 Ichimoku Forex pairs: EUR/USD, GBP/USD, USD/JPY, EUR/JPY, GBP/JPY, AUD/USD, USD/CHF
     * - 5 High-volume Crypto pairs: BTC/USDT, ETH/USDT, SOL/USDT, BNB/USDT, XRP/USDT
     * Every symbol has exactly one source, and no two symbols share a source.
     */
    val symbols: List<WatchSymbol> = listOf(
        // ── طلا و نقره ────────────────────────────────────────────────────
        WatchSymbol(
            id = "XAU/USD",
            label = "طلای جهانی (هر انس)",
            unit = "$",
            providerCodes = linkedMapOf("gold_api_xau" to "XAU"),
            defaultSources = listOf("gold_api_xau"),
            maxAgeMillis = 10 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "XAG/USD",
            label = "نقرهٔ جهانی (هر انس)",
            unit = "$",
            providerCodes = linkedMapOf("gold_api_xag" to "XAG"),
            defaultSources = listOf("gold_api_xag"),
            maxAgeMillis = 10 * 60_000L,
            tolerancePct = 0.5,
        ),

        // ── شاخص داوجونز ──────────────────────────────────────────────────
        WatchSymbol(
            id = "DOW",
            label = "شاخص داوجونز آمریکا",
            unit = "$",
            providerCodes = linkedMapOf("yahoo_dow" to "^DJI"),
            defaultSources = listOf("yahoo_dow"),
            maxAgeMillis = 10 * 60_000L,
            tolerancePct = 0.5,
        ),

        // ── جفت‌ارزهای مناسب ایچیموکو ────────────────────────────────────
        WatchSymbol(
            id = "EUR/USD",
            label = "یورو / دلار",
            unit = "$",
            providerCodes = linkedMapOf("yahoo_eurusd" to "EURUSD=X"),
            defaultSources = listOf("yahoo_eurusd"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "GBP/USD",
            label = "پوند / دلار",
            unit = "$",
            providerCodes = linkedMapOf("swissquote_gbpusd" to "GBP/USD"),
            defaultSources = listOf("swissquote_gbpusd"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "USD/JPY",
            label = "دلار / ین ژاپن",
            unit = "JPY",
            providerCodes = linkedMapOf("yahoo_usdjpy" to "JPY=X"),
            defaultSources = listOf("yahoo_usdjpy"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "EUR/JPY",
            label = "یورو / ین ژاپن",
            unit = "JPY",
            providerCodes = linkedMapOf("swissquote_eurjpy" to "EUR/JPY"),
            defaultSources = listOf("swissquote_eurjpy"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "GBP/JPY",
            label = "پوند / ین ژاپن",
            unit = "JPY",
            providerCodes = linkedMapOf("yahoo_gbpjpy" to "GBPJPY=X"),
            defaultSources = listOf("yahoo_gbpjpy"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "AUD/USD",
            label = "دلار استرالیا / دلار",
            unit = "$",
            providerCodes = linkedMapOf("swissquote_audusd" to "AUD/USD"),
            defaultSources = listOf("swissquote_audusd"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),
        WatchSymbol(
            id = "USD/CHF",
            label = "دلار / فرانک سوئیس",
            unit = "CHF",
            providerCodes = linkedMapOf("swissquote_usdchf" to "USD/CHF"),
            defaultSources = listOf("swissquote_usdchf"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.3,
        ),

        // ── ۵ رمزارز پرحجم و پرمعامله ─────────────────────────────────────
        WatchSymbol(
            id = "BTC/USDT",
            label = "بیت‌کوین / تتر",
            unit = "$",
            providerCodes = linkedMapOf("binance_btc_spot" to "BTC/USDT"),
            defaultSources = listOf("binance_btc_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "ETH/USDT",
            label = "اتریوم / تتر",
            unit = "$",
            providerCodes = linkedMapOf("okx_eth_spot" to "ETH/USDT"),
            defaultSources = listOf("okx_eth_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "SOL/USDT",
            label = "سولانا / تتر",
            unit = "$",
            providerCodes = linkedMapOf("gate_sol_spot" to "SOL/USDT"),
            defaultSources = listOf("gate_sol_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "BNB/USDT",
            label = "بی‌ان‌بی / تتر",
            unit = "$",
            providerCodes = linkedMapOf("mexc_bnb_spot" to "BNB/USDT"),
            defaultSources = listOf("mexc_bnb_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
        WatchSymbol(
            id = "XRP/USDT",
            label = "ریپل / تتر",
            unit = "$",
            providerCodes = linkedMapOf("kraken_xrp_spot" to "XRP/USDT"),
            defaultSources = listOf("kraken_xrp_spot"),
            maxAgeMillis = 5 * 60_000L,
            tolerancePct = 0.5,
        ),
    )

    val scannerSymbols: List<String> = symbols.map { it.id }

    fun scanUniverse(activeWatchlist: List<String>): List<String> =
        (activeWatchlist + scannerSymbols).filter(com.aurum.edge.core.V1Universe::valid).distinct()

    fun find(id: String): WatchSymbol? {
        val normalized = com.aurum.edge.core.V1Universe.normalize(id)
        return symbols.firstOrNull { it.id == normalized }
    }

    val chartSymbols: List<String> = symbols.map { it.id }
}

data class DisplayQuote(val quote: Quote?, val sourceId: String, val fallback: Boolean)

enum class QuoteDisplayState { NO_DATA, ERROR, CACHED, INVALID, OLD, UNDATED, DATED }

data class QuoteAssessment(val state: QuoteDisplayState, val detail: String) {
    val readable: Boolean get() = state == QuoteDisplayState.UNDATED || state == QuoteDisplayState.DATED
    val verifiable: Boolean get() = state == QuoteDisplayState.DATED
}

object SourceComparison {
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
        return QuoteAssessment(QuoteDisplayState.DATED, "قیمت زمان‌دار و تازهٔ منبع اختصاصی")
    }

    fun isFresh(symbol: WatchSymbol, quote: Quote, now: Long = System.currentTimeMillis()): Boolean =
        assess(symbol, quote.sourceId, quote, now).verifiable

    fun verify(symbol: WatchSymbol, sources: List<String>, quotes: Map<String, Quote>, now: Long = System.currentTimeMillis()): Verification {
        val enabled = sources.distinct().filter { it in symbol.providerCodes }
        if (enabled.isEmpty()) return Verification(VerificationStatus.NO_DATA, 0,
            reason = "منبعی برای این نماد فعال نیست؛ از تنظیمات انتخاب کنید", badge = "منبع ندارد")
        val assessed = enabled.associateWith { assess(symbol, it, quotes[it], now) }
        val valid = enabled.mapNotNull { id -> quotes[id]?.takeIf { assessed[id]?.verifiable == true } }
        if (symbol.providerCodes.size == 1 && enabled.size == 1 && valid.size == 1) {
            val sourceTitle = SourceCatalog.find(enabled.single())?.title ?: enabled.single()
            return Verification(
                status = VerificationStatus.CONFIRMED,
                freshSources = 1,
                spreadPct = 0.0,
                reason = "تأیید منبع اختصاصی و یکتا ($sourceTitle)",
                badge = "سورس اختصاصی",
            )
        }
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
                onlyOneAvailable -> "در این نسخه هر نماد دقیقاً یک منبع اختصاصی دارد. "
                enabled.size == 1 -> "فقط یک منبع فعال است. "
                else -> ""
            }
            val reason = "تأیید منبع تازه ممکن نیست (${valid.size}). $sourceLimit" + issues.joinToString("؛ ")
            val hasPrice = enabled.any { id -> quotes[id]?.price?.let { it.isFinite() && it > 0 } == true }
            return Verification(if (hasPrice) VerificationStatus.UNVERIFIED else VerificationStatus.NO_DATA,
                valid.size, reason = reason, badge = badge)
        }
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
    val badge: String,
)

enum class VerificationStatus { CONFIRMED, CONFLICT, UNVERIFIED, NO_DATA }

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
