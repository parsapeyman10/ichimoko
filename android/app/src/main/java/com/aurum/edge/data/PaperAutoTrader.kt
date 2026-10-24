package com.aurum.edge.data

import com.aurum.edge.core.MarketTrend
import com.aurum.edge.core.MarketTrendRead
import com.aurum.edge.core.MarketTrendRecord
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import com.aurum.edge.core.MarketState

import com.aurum.edge.engine.EngineRegistry
import com.aurum.edge.engine.MtfAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Opt-in, automatic PAPER entries only. Atomic deduplication and persistence live in JournalStore.
 * Purely technical: no AI and no news layer participates in entries anymore. */
class PaperAutoTrader(
    private val settings: SettingsStore,
    private val journal: JournalStore,
) {
    private val _status = MutableStateFlow("فعال")
    val status: StateFlow<String> = _status.asStateFlow()

    /**
     * «روند کلی بازار» از پویشِ پیوستهٔ نمادها می‌آید (عرض بازار + جهت دلار + جوّ ریسک).
     * اگر وصل نشده باشد null است و فقط روندِ خودِ نماد (که از کندل‌های همان ورود حساب می‌شود)
     * به‌عنوان شواهد ثبت می‌شود — هیچ جهتِ کلیِ حدسی ساخته نمی‌شود و گیت ورود هم نیست.
     */
    private var trendSource: (() -> MarketTrendRead?)? = null

    fun attachTrendSource(provider: () -> MarketTrendRead?) {
        trendSource = provider
    }

    fun stopped(reason: String) { _status.value = reason }

    /** Returns only a trade whose atomic journal write has completed; null is never an entry event. */
    suspend fun onMarketUpdate(state: MarketState, catalogQuote: PriceTick? = null): PaperTrade? {
        // The catalog runner may pass a second, independently fetched same-venue trade
        // tick even for the selected crypto symbol's 5m closed-bar scan. Persistence and
        // duplicate/portfolio limits remain atomic in JournalStore.
        val allowed = settings.read().activeSymbols
        if (catalogQuote != null &&
            (!TradeQuotePolicy.accepts(catalogQuote) || catalogQuote.price != state.lastPrice ||
                state.symbol !in WatchCatalog.scannerSymbols)) {
            _status.value = "قیمت مستقل و تازهٔ نماد کاتالوگ تأیید نشد"
            return null
        }
        val config = settings.read()
        val reason = PaperAutoRules.blocker(state, config, allowedSymbols = allowed)
        if (reason != null) {
            _status.value = if (catalogQuote != null) "${state.symbol}: $reason" else reason
            return null
        }
        com.aurum.edge.core.PaperPortfolioPolicy.blocker(journal.trades.value, state.symbol,
            config.accountBalance, 0.0, state.signal?.barTime)?.let {
            _status.value = it
            return null
        }
        // Computing higher-timeframe bars is read-only.
        val mtf = withContext(Dispatchers.Default) {
            runCatching { MtfAnalyzer.analyze(state.candles, state.interval) }.getOrNull()
        }

        // Re-verify against the SAME emission plus a freshly read settings snapshot.
        val current = state
        val recentSettings = settings.read()
        PaperAutoRules.blocker(current, recentSettings, allowedSymbols = allowed)?.let {
            _status.value = it
            return null
        }
        val signal = current.signal ?: return null

        // ── «کدام روش برای کدام بازار» ────────────────────────────────────────
        // Market context annotation only; category mode and the four-layer score were
        // already applied by SignalEngine. No second dynamic confidence threshold.
        val playbook = withContext(Dispatchers.Default) {
            runCatching {
                com.aurum.edge.core.MarketPlaybook.assess(
                    current.symbol, current.candles, current.interval, System.currentTimeMillis())
            }.getOrNull()
        }

        // ── «روند کلی بازار» فقط به‌عنوان شواهد ژورنال ثبت می‌شود، نه گیت ──────────
        val symbolTrend = withContext(Dispatchers.Default) {
            runCatching { MarketTrend.symbolTrend(current.symbol, current.candles, current.interval) }.getOrNull()
        }
        val marketTrend = runCatching { trendSource?.invoke() }.getOrNull()
        val trendContext = withContext(Dispatchers.Default) {
            runCatching {
                MarketTrend.contextOf(signal.action, symbolTrend, marketTrend, playbook?.method)
            }.getOrNull()
        }
        val ict = IctEntryRules.approvedEvidence(current) // optional legacy research snapshot

        return try {
            // The *same provider tick* must still be within the 45-second trade window
            // when JournalStore atomically opens.
            if (catalogQuote != null) require(TradeQuotePolicy.accepts(catalogQuote)) {
                "قیمت مستقل کاتالوگ هنگام ثبت معامله کهنه شد"
            }
            PaperAutoRules.blocker(current, settings.read(), allowedSymbols = allowed)?.let {
                throw IllegalArgumentException("بازبینی نهایی پیش از ثبت: $it")
            }
            require(signal.isActionable) { "سیگنال در بازبینی نهایی قابل معامله نیست" }
            val trade = journal.open(
                signal = signal,
                symbol = current.symbol,
                // A catalog signal is calculated on closed bars, but the *fill* must be the
                // separately fetched trade tick. Preview rechecks SL/TP and 0.5% risk at it.
                price = catalogQuote?.price ?: if (com.aurum.edge.core.AssetClass.of(current.symbol) ==
                    com.aurum.edge.core.AssetClass.CRYPTO) current.lastTradePrice!!
                    else signal.entry ?: current.lastPrice!!,
                balance = recentSettings.accountBalance,
                riskPercent = recentSettings.riskPercent,
                mtf = mtf?.let { MtfSnapshotRecord.from(it) },
                automatic = true,
                priceAction = ict,
                customNote = EngineRegistry.autoEntryNote(current.symbol),
                cryptoEntryQuote = catalogQuote,
                sourceIntegrityRevision = current.sourceIntegrityRevision,
                marketTrend = trendContext?.let { MarketTrendRecord.from(it) },
                // The recorded venue must be the venue of the candles the signal was built on:
                // Nobitex reference normally, Binance only for legacy Binance-based bars.
                cryptoVenue = if (com.aurum.edge.data.CryptoCatalog.isCrypto(current.symbol))
                    com.aurum.edge.data.CryptoVenuePolicy.entryVenue(current.historyProvider)
                else "NOBITEX",
            )
            _status.value = "کاغذی ثبت شد: ${trade.symbol} ${trade.action} (${signal.confidence.toInt()}٪)" +
                (trendContext?.let { " · روند: ${it.alignment.label}" } ?: "")
            trade
        } catch (e: Exception) {
            _status.value = "ورود خودکار کاغذی انجام نشد: ${e.message ?: "ژورنال یا ریسک نامعتبر است"}"
            null
        }
    }
}
