package com.aurum.edge.data

import com.aurum.edge.core.MarketTrend
import com.aurum.edge.core.MarketTrendRead
import com.aurum.edge.core.MarketTrendRecord
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.core.PaperTrade
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
    suspend fun onMarketUpdate(state: MarketState): PaperTrade? {
        val headlines = news.state.value
        val config = settings.read()
        val reason = PaperAutoRules.blocker(state, config, headlines)
        if (reason != null) {
            _status.value = reason
            return null
        }
        if (journal.trades.value.any { it.symbol == state.symbol && it.signalBarTime == state.signal?.barTime }) {
            _status.value = "سیگنال این کندل قبلاً در ژورنال ثبت شده است؛ ورود تکراری نداریم"
            return null
        }
        val openTrades = journal.trades.value.filter { it.isOpen }
        if (openTrades.size >= 4) {
            _status.value = "سقف ۴ معاملهٔ همزمان پورتفو پر شده است (${openTrades.size}/4)"
            return null
        }
        if (openTrades.any { it.symbol == state.symbol }) {
            _status.value = "برای نماد ${state.symbol} از قبل پوزیشن کاغذی باز است"
            return null
        }
        val targetClass = com.aurum.edge.core.AssetClass.of(state.symbol)
        val openInClass = openTrades.count { com.aurum.edge.core.AssetClass.of(it.symbol) == targetClass }
        if (openInClass >= targetClass.maxSlots) {
            _status.value = "ظرفیت پوزیشن باز برای دستهٔ «${targetClass.label}» تکمیل است (۱/۱)"
            return null
        }
        // Computing higher-timeframe bars is read-only.
        val mtf = withContext(Dispatchers.Default) {
            runCatching { MtfAnalyzer.analyze(state.candles, state.interval) }.getOrNull()
        }
        if (mtf?.veto == true) {
            _status.value = "تراز چندتایم‌فریم ورود را وتو کرده است: ${mtf.vetoReason}"
            return null
        }
        // Re-verify against the SAME emission plus freshly read settings/news snapshot. News is
        // not an entry gate; the snapshot is only used for journal evidence.
        val current = state
        val recentNews = news.state.value
        val recentSettings = settings.read()
        PaperAutoRules.blocker(current, recentSettings, recentNews)?.let {
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
        if (playbook != null) {
            if (!playbook.allowed) {
                _status.value = "بازار ${current.symbol} · ${playbook.family.label} · ${playbook.session.label} · " +
                    "${playbook.regime.label} → ${playbook.method.label}: " +
                    (playbook.blockers.firstOrNull() ?: "ورود مجاز نیست")
                return null
            }
            if (signal.confidence < playbook.minConfidence) {
                _status.value = "اطمینان سیگنال (${signal.confidence.toInt()}٪) از کفِ این بازار/سشن " +
                    "(${playbook.minConfidence.toInt()}٪ برای ${playbook.method.label}) کمتر است"
                return null
            }
            val rewardBps = signal.entry?.takeIf { it > 0.0 }?.let { entryPrice ->
                signal.takeProfit?.let { target -> kotlin.math.abs(target - entryPrice) / entryPrice * 10_000.0 }
            }
            if (rewardBps != null && rewardBps < playbook.minRewardBps) {
                _status.value = "هدف سیگنال ${String.format(java.util.Locale.US, "%.1f", rewardBps)}bps است؛ " +
                    "کفِ سودِ واقعیِ ${playbook.family.label} ${String.format(java.util.Locale.US, "%.1f", playbook.minRewardBps)}bps"
                return null
            }
        }

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
        if (trendContext != null && !trendContext.gate.allowed) {
            _status.value = "روند بازار: " +
                (trendContext.gate.blockerFa ?: "این ورود خلاف جهتِ روندِ اندازه‌گیری‌شده است")
            return null
        }
        val trendConfidenceFloor = (playbook?.minConfidence ?: config.minConfidence) +
            (trendContext?.gate?.minConfidenceAdd ?: 0.0)
        if ((trendContext?.gate?.minConfidenceAdd ?: 0.0) > 0.0 && signal.confidence < trendConfidenceFloor) {
            _status.value = "لایهٔ روند بازار کف اطمینان را به " +
                "${trendConfidenceFloor.toInt()}٪ برد و سیگنال ${signal.confidence.toInt()}٪ است · " +
                (trendContext?.gate?.noteFa ?: "—")
            return null
        }

        val newsRecord = NewsConfluence.record(recentNews, current.symbol)
        val isLegacyEight = signal.confluence.filterNot { it.name == NewsConfluence.NEWS_LABEL }.size == 8
        val ict = if (isLegacyEight) {
            IctEntryRules.approvedEvidence(current) ?: run {
                _status.value = "شواهد رنج/ICT همین کندل برای ژورنال تأیید نشد"
                return null
            }
        } else {
            IctEntryRules.approvedEvidence(current)
        }

        // ── برنامه ریزی توسط AI یا استفاده از مقادیر فنی پایه ──
        // The connection is probed FIRST. If the model answers, its worthiness verdict gates the
        // entry; if it is unreachable the trade continues on the technical rules alone.
        val adv = advisor
        val aiOnline = adv != null && adv.ensureConnection()
        val (finalSignal, entryNote) = if (aiOnline && adv != null) {
            val aiPlan = runCatching {
                adv.planTradeWithAi(signal, current)
            }.getOrNull()
            // The AI is consulted first, exactly as asked: when it answers, it decides whether
            // this setup is worth taking at all. A "not worth it" verdict blocks the paper
            // entry instead of only re-pricing it. When the model cannot be reached the call
            // returns null and the entry continues on the technical rules alone.
            if (aiPlan != null && !aiPlan.worth) {
                _status.value = "ارزنده‌بودن معامله از نظر AI تأیید نشد: " +
                    aiPlan.worthReason.ifBlank { "دلیل ارزنده‌نبودن اعلام نشد" }
                return null
            }
            if (aiPlan != null) {
                Pair(
                    signal.copy(
                        entry = aiPlan.entry,
                        stopLoss = aiPlan.stopLoss,
                        takeProfit = aiPlan.takeProfit,
                        riskReward = aiPlan.riskReward,
                        confidence = aiPlan.confidence,
                    ),
                    "طرح ورود توسط هوش مصنوعی (${aiPlan.model}) · نسبت ریسک به ریوارد ۱:${String.format(java.util.Locale.US, "%.1f", aiPlan.riskReward)} · ${aiPlan.summary}"
                )
            } else {
                Pair(
                    signal,
                    "اتصال AI برقرار شد اما طرح ورود معتبر برنگشت (پاسخ نامعتبر/خطای میانی)؛ ورود با محاسبات فنی ایچیموکو و بدون AI انجام شد (SL کیجون ± ۰٫۵×ATR و TP ۱:۱٫۸)."
                )
            }
        } else {
            Pair(
                signal,
                "اتصال AI بررسی شد و مدل در دسترس نبود؛ ورود بدون AI و طبق محاسبات فنی ایچیموکو انجام شد (SL کیجون ± ۰٫۵×ATR و TP ۱:۱٫۸)."
            )
        }

        return try {
            val trade = journal.open(
                signal = finalSignal,
                symbol = current.symbol,
                price = finalSignal.entry ?: current.lastPrice!!,
                balance = recentSettings.accountBalance,
                riskPercent = recentSettings.riskPercent,
                mtf = mtf?.let { MtfSnapshotRecord.from(it) },
                automatic = true,
                newsEvidence = newsRecord,
                priceAction = ict,
                customNote = entryNote,
                marketTrend = trendContext?.let { MarketTrendRecord.from(it) },
            )
            val conditions = trade.entryConditions.take(8).joinToString("، ") {
                it.name.substringAfter('·').trim()
            }
            _status.value = "کاغذی ثبت شد: ${trade.symbol} ${trade.action} (${finalSignal.confidence.toInt()}٪)" +
                (trendContext?.let { " · روند: ${it.alignment.label}" } ?: "") + " · $entryNote"
            trade
        } catch (e: Exception) {
            _status.value = "ورود خودکار کاغذی انجام نشد: ${e.message ?: "ژورنال یا ریسک نامعتبر است"}"
            null
        }
    }
}
