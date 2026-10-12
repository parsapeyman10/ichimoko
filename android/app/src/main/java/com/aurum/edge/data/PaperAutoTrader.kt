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
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.NewsConfluence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Opt-in, automatic PAPER entries only. Atomic deduplication and persistence live in JournalStore. */
class PaperAutoTrader(
    private val settings: SettingsStore,
    private val news: NewsRepository,
    private val journal: JournalStore,
    private val advisor: TraderAdvisor? = null,
    private val decisionLog: DecisionLogStore? = null,
) {
    private val _status = MutableStateFlow("فعال")
    val status: StateFlow<String> = _status.asStateFlow()

    /**
     * «روند کلی بازار» از پویشِ پیوستهٔ ۵۰+ نماد می‌آید (عرض بازار + جهت دلار + جوّ ریسک).
     * اگر وصل نشده باشد null است و فقط روندِ خودِ نماد (که از کندل‌های همان ورود حساب می‌شود)
     * اعمال می‌شود — هیچ جهتِ کلیِ حدسی ساخته نمی‌شود.
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
        val allowed = if (state.symbol in WatchCatalog.scannerSymbols) WatchCatalog.scannerSymbols
            else settings.read().activeWatchlist
        if (catalogQuote != null &&
            (!TradeQuotePolicy.accepts(catalogQuote) || catalogQuote.price != state.lastPrice ||
                state.symbol !in WatchCatalog.scannerSymbols)) {
            _status.value = "قیمت مستقل و تازهٔ نماد کاتالوگ تأیید نشد"
            return null
        }
        val headlines = news.state.value
        val config = settings.read()
        val reason = PaperAutoRules.blocker(state, config, headlines, allowedSymbols = allowed)
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

        // Re-verify against the SAME emission plus freshly read settings/news snapshot. News is
        // not an entry gate; the snapshot is only used for journal evidence.
        val current = state
        val recentNews = news.state.value
        val recentSettings = settings.read()
        PaperAutoRules.blocker(current, recentSettings, recentNews, allowedSymbols = allowed)?.let {
            _status.value = it
            return null
        }
        val signal = current.signal ?: return null

        // ── «کدام روش برای کدام بازار» ────────────────────────────────────────
        // Before anything else, the market itself decides WHICH method is even legal right now:
        // gold/FX trend-pullback in London/NY, FX range-fade in Asia, crypto breakout, equity
        // opening drive, and STAND_ASIDE during rollover/thin sessions/volatility spikes.
        // Everything here is measured from real closed candles + real session clocks.
        val playbook = withContext(Dispatchers.Default) {
            runCatching {
                com.aurum.edge.core.MarketPlaybook.assess(
                    current.symbol, current.candles, current.interval, System.currentTimeMillis())
            }.getOrNull()
        }
        // Playbook remains a market-context annotation; category mode and four-layer score
        // were already applied by SignalEngine. No second dynamic confidence threshold.

        // ── «روند کلی بازار» → چطور به همین معامله اضافه می‌شود ─────────────────
        // لایهٔ اول از کندل‌های بستهٔ خودِ نماد (پایه + تایم‌فریم مرجعِ تجمیع‌شده) و لایهٔ
        // دوم/سوم از آخرین خوانشِ پویشگر پیوسته. روش روندی خلاف جهتِ اندازه‌گیری‌شده مسدود
        // می‌شود؛ بازگشت به میانگین فقط در بازارِ بی‌روند؛ و ورودِ دارایی ریسکی خلافِ جوّ کلی
        // بازار با کف اطمینانِ سخت‌تر. نبودِ داده هرگز منع نمی‌کند، فقط صریح اعلام می‌شود.
        val symbolTrend = withContext(Dispatchers.Default) {
            runCatching { MarketTrend.symbolTrend(current.symbol, current.candles, current.interval) }.getOrNull()
        }
        val marketTrend = runCatching { trendSource?.invoke() }.getOrNull()
        val trendContext = withContext(Dispatchers.Default) {
            runCatching {
                MarketTrend.contextOf(signal.action, symbolTrend, marketTrend, playbook?.method)
            }.getOrNull()
        }
        val newsRecord = NewsConfluence.record(recentNews, current.symbol)
        val ict = IctEntryRules.approvedEvidence(current) // optional legacy research snapshot

        // ── برنامه ریزی توسط AI یا استفاده از مقادیر فنی پایه ──
        // The connection is probed FIRST. If the model answers, its worthiness verdict gates the
        // entry; if it is unreachable the trade continues on the technical rules alone.
        val adv = advisor
        val aiOnline = adv != null && adv.ensureConnection()
        val (finalSignal, entryNote) = if (aiOnline && adv != null) {
            val aiPlan = runCatching {
                adv.planTradeWithAi(signal, current)
            }.getOrNull()
            // Persist the observed review of THIS symbol/bar, not the preceding connection probe.
            // Only an explicit, validated false verdict may veto. Positive/absent verdicts
            // cannot authorize a trade or override its technical entry/SL/TP/risk checks.
            val effect = AiEntryEffect.fromResponse(connected = true, plan = aiPlan)
            auditAiEntry(current, signal, effect, aiPlan)
            if (effect == AiEntryEffect.VETO) {
                _status.value = "وتوی AI برای ${current.symbol}: ${aiPlan!!.worthReason}"
                return null
            }
            if (aiPlan != null && effect == AiEntryEffect.NON_VETO) {
                Pair(signal,
                    "AI (${aiPlan.model}) صریحاً وتو نکرد؛ قیمت و SL/TP همان طرح فنی باقی ماندند و ورود همچنان مشروط به کنترل ریسک است")
            } else if (aiPlan != null) {
                Pair(signal,
                    "پاسخ مدل (${aiPlan.model}) فاقد حکم صریح ارزندگی بود؛ ورود فقط با طرح فنی و کنترل ریسک ادامه یافت")
            } else {
                Pair(signal,
                    "اتصال AI برقرار شد اما طرح ورود معتبر برنگشت (پاسخ نامعتبر/خطای میانی)؛ ورود با طرح SL/TP موتور فنی جاری و بدون AI انجام شد.")
            }
        } else {
            auditAiEntry(current, signal, AiEntryEffect.UNAVAILABLE, null)
            Pair(signal,
                "اتصال AI بررسی شد و مدل در دسترس نبود؛ ورود بدون AI و با طرح SL/TP موتور فنی جاری انجام شد.")
        }

        return try {
            // AI and persistence can take time. The *same provider tick* must still be
            // within the 45-second trade window when JournalStore atomically opens.
            if (catalogQuote != null) require(TradeQuotePolicy.accepts(catalogQuote)) {
                "قیمت مستقل کاتالوگ هنگام ثبت معامله کهنه شد"
            }
            PaperAutoRules.blocker(current, settings.read(), news.state.value, allowedSymbols = allowed)?.let {
                throw IllegalArgumentException("بازبینی پس از پاسخ AI: $it")
            }
            val lastVeto = NewsConfluence.apply(finalSignal, current.symbol, news.state.value)
            require(lastVeto?.isActionable == true) {
                lastVeto?.blockers?.lastOrNull() ?: "وتوی AI یا شواهد چهارلایه نامعتبر شدند"
            }
            val trade = journal.open(
                signal = finalSignal,
                symbol = current.symbol,
                // A catalog signal is calculated on closed bars, but the *fill* must be the
                // separately fetched trade tick. Preview rechecks SL/TP and 0.5% risk at it.
                price = catalogQuote?.price ?: if (com.aurum.edge.core.AssetClass.of(current.symbol) ==
                    com.aurum.edge.core.AssetClass.CRYPTO) current.lastTradePrice!!
                    else finalSignal.entry ?: current.lastPrice!!,
                balance = recentSettings.accountBalance,
                riskPercent = recentSettings.riskPercent,
                mtf = mtf?.let { MtfSnapshotRecord.from(it) },
                automatic = true,
                newsEvidence = newsRecord,
                priceAction = ict,
                customNote = entryNote,
                cryptoEntryQuote = catalogQuote,
                cryptoNews = recentNews,
                marketTrend = trendContext?.let { MarketTrendRecord.from(it) },
                cryptoVenue = if (com.aurum.edge.data.CryptoCatalog.isCrypto(current.symbol))
                    BinanceSpotClient.VENUE else "NOBITEX",
            )
            _status.value = "کاغذی ثبت شد: ${trade.symbol} ${trade.action} (${finalSignal.confidence.toInt()}٪)" +
                (trendContext?.let { " · روند: ${it.alignment.label}" } ?: "") + " · $entryNote"
            trade
        } catch (e: Exception) {
            _status.value = "ورود خودکار کاغذی انجام نشد: ${e.message ?: "ژورنال یا ریسک نامعتبر است"}"
            null
        }
    }

    private fun auditAiEntry(state: MarketState, signal: com.aurum.edge.core.Signal,
                             effect: AiEntryEffect, plan: AiTradePlan?) {
        val reason = if (effect == AiEntryEffect.VETO) {
            "${effect.label} · علت مدل: ${plan?.worthReason.orEmpty().take(180)}"
        } else effect.label
        decisionLog?.append(state.symbol, state.interval.label, effect.action, reason,
            barTimeOverride = signal.barTime, aiModel = plan?.model,
            dedupe = true)
    }
}
