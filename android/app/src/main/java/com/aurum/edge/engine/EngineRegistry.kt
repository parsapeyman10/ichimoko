package com.aurum.edge.engine

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.V1Universe

/**
 * Which engine is allowed to speak for which symbol — one answer, in one place.
 *
 * Why this exists: gold (ALS-5), silver (four-layer V1 scoring) and the crypto family used to be
 * selected by scattered `if` statements inside [SignalEngine], which is exactly how two similar
 * models end up scoring the same symbol or a new model silently shadowing an old one. This registry
 * makes the mapping explicit, total and testable:
 *  - every symbol of the 15-symbol universe has exactly ONE engine;
 *  - every engine except the crypto families serves exactly one symbol (or one family);
 *  - BTC/USDT belongs to BTC-ARES alone, so the compression engine can never also act on it;
 *  - setup keys are namespaced by symbol + engine, so two engines (or two symbols) can never
 *    collide on the same journal key.
 *
 * Engines are stateless objects: no engine keeps a field that another symbol's evaluation could
 * overwrite, so evaluating gold cannot change silver or BTC, even in the same sweep.
 */
enum class SymbolEngine(val labelFa: String) {
    ALS5_GOLD("ALS-5 طلا · XAU/USD"),
    ALS5S_SILVER("ALS-5S نقره · XAG/USD"),
    EUR_ARES("EUR-ARES · یورو/دلار"),
    V1_FOUR_LAYER("چهارلایهٔ V1 · فارکس/شاخص"),
    BTC_ARES("BTC-ARES · بیت‌کوین"),
    CRYPTO_COMPRESSION("فشردگی ← انفجار · سایر رمزارزها"),
}

object EngineRegistry {
    /** Exact-symbol owners. A symbol here can never be claimed by another engine. */
    private val exact: Map<String, SymbolEngine> = mapOf(
        "XAU/USD" to SymbolEngine.ALS5_GOLD,
        "XAG/USD" to SymbolEngine.ALS5S_SILVER,
        "EUR/USD" to SymbolEngine.EUR_ARES,
        "BTC/USDT" to SymbolEngine.BTC_ARES,
    )

    /** The only symbols the gold model accepts, so a caller cannot pass silver by mistake. */
    fun goldSymbols(): Set<String> = exact.filterValues { it == SymbolEngine.ALS5_GOLD }.keys

    /** The only symbol BTC-ARES accepts. */
    fun btcAresSymbols(): Set<String> = exact.filterValues { it == SymbolEngine.BTC_ARES }.keys

    /** The only symbol ALS-5S accepts. */
    fun silverAresSymbols(): Set<String> = exact.filterValues { it == SymbolEngine.ALS5S_SILVER }.keys

    /** The only symbol EUR-ARES accepts: one pair, its own pip-based model. */
    fun eurAresSymbols(): Set<String> = exact.filterValues { it == SymbolEngine.EUR_ARES }.keys

    fun engineFor(symbol: String): SymbolEngine {
        val key = V1Universe.normalize(symbol)
        exact[key]?.let { return it }
        // Precious-metal aliases must not silently fall into the four-layer model:
        // XAG* (XAGUSD, SILVER…) belongs to silver, XAU* to gold.
        if (key.startsWith("XAG")) return SymbolEngine.ALS5S_SILVER
        if (key.startsWith("XAU") || key == "GOLD") return SymbolEngine.ALS5_GOLD
        return when (AssetClass.of(key)) {
            AssetClass.CRYPTO -> SymbolEngine.CRYPTO_COMPRESSION
            else -> SymbolEngine.V1_FOUR_LAYER
        }
    }

    /**
     * Journal/UI note for an automatic entry. It names the engine that actually authorised the
     * trade: calling a BTC-ARES or ALS-5 entry «چهارلایه» would misdescribe the method, and a user
     * reading the journal later would have no way to tell which model opened the position.
     */
    fun autoEntryNote(symbol: String): String =
        "ورود خودکار صرفاً فنی: طرح SL/TP ${engineFor(symbol).labelFa} + کنترل ریسک ژورنال"

    /** Every symbol with its single owning engine, used by tests and by diagnostics. */
    fun assignments(): Map<String, SymbolEngine> =
        V1Universe.allSymbols.associateWith { engineFor(it) }

    /**
     * Namespaced setup key. Including the symbol and the engine means the same numeric level on
     * gold and on BTC can never be treated as the same setup by the journal or the auto trader.
     */
    fun setupKey(symbol: String, engine: String, side: String, level: Double, barTime: Long): String =
        listOf(V1Universe.normalize(symbol), engine, side,
            "%.4f".format(java.util.Locale.US, level), barTime.toString()).joinToString("|")
}
