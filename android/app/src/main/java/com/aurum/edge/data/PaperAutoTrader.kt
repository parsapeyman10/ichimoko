package com.aurum.edge.data

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
        if (openTrades.size >= 3) {
            _status.value = "سقف ۳ معاملهٔ همزمان باز پر شده است (${openTrades.size}/3)"
            return null
        }
        if (openTrades.any { it.symbol == state.symbol }) {
            _status.value = "برای نماد ${state.symbol} از قبل پوزیشن کاغذی باز است"
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
        val adv = advisor
        val (finalSignal, entryNote) = if (recentSettings.hasClientNewsAi && adv != null) {
            val aiPlan = runCatching {
                adv.planTradeWithAi(signal, current)
            }.getOrNull()
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
                    "بدون هوش مصنوعی ترید شده (خطای ارتباط با مدل AI)؛ مقادیر طبق محاسبات فنی ایچیموکو (SL کیجون ± 0.5 ATR و TP ۱:۱.۸) تنظیم شدند."
                )
            }
        } else {
            Pair(
                signal,
                "بدون هوش مصنوعی ترید شده؛ مقادیر طبق محاسبات فنی ایچیموکو (SL کیجون ± 0.5 ATR و TP با نسبت ۱:۱.۸) تنظیم شده است."
            )
        }

        return try {
            val trade = journal.open(
                signal = finalSignal,
                symbol = current.symbol,
                price = finalSignal.entry ?: current.lastPrice!!,
                balance = recentSettings.accountBalance,
                riskPercent = recentSettings.riskPercent,
                mtf = MtfSnapshotRecord.from(mtf),
                automatic = true,
                newsEvidence = newsRecord,
                priceAction = ict,
                customNote = entryNote,
            )
            val conditions = trade.entryConditions.take(8).joinToString("، ") {
                it.name.substringAfter('·').trim()
            }
            _status.value = "کاغذی ثبت شد: ${trade.symbol} ${trade.action} (${finalSignal.confidence.toInt()}٪) · $entryNote"
            trade
        } catch (e: Exception) {
            _status.value = "ورود خودکار کاغذی انجام نشد: ${e.message ?: "ژورنال یا ریسک نامعتبر است"}"
            null
        }
    }
}
