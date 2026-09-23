package com.aurum.edge.data

import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperAutoRules
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
    private val latestMarket: StateFlow<MarketState>,
) {
    private val _status = MutableStateFlow("خاموش؛ هیچ معاملهٔ خودکاری در بروکر یا ژورنال باز نشده است")
    val status: StateFlow<String> = _status.asStateFlow()

    fun stopped(reason: String) { _status.value = reason }

    suspend fun onMarketUpdate(state: MarketState) {
        val headlines = news.state.value
        val config = settings.read()
        val reason = PaperAutoRules.blocker(state, config, headlines)
        if (reason != null) {
            _status.value = reason
            return
        }
        if (journal.trades.value.any { it.symbol == state.symbol && it.signalBarTime == state.signal?.barTime }) {
            _status.value = "سیگنال این کندل قبلاً در ژورنال ثبت شده است؛ ورود تکراری نداریم"
            return
        }
        if (journal.trades.value.any { it.symbol == state.symbol && it.isOpen }) {
            _status.value = "برای این نماد از قبل پوزیشن کاغذی باز است"
            return
        }
        // Computing higher-timeframe bars is read-only; an unavailable/vetoed MTF cannot open.
        val mtf = withContext(Dispatchers.Default) {
            runCatching { MtfAnalyzer.analyze(state.candles, state.interval) }.getOrNull()
        }
        if (mtf == null || mtf.veto) {
            _status.value = "تراز چندتایم‌فریم در دسترس نیست یا ورود را وتو کرده است"
            return
        }
        val current = latestMarket.value
        val recentNews = news.state.value
        val recentSettings = settings.read()
        PaperAutoRules.blocker(current, recentSettings, recentNews)?.let {
            _status.value = it
            return
        }
        val signal = current.signal ?: return
        if (state.signal?.barTime != signal.barTime || state.symbol != current.symbol) return
        val newsRecord = NewsConfluence.record(recentNews) ?: run {
            _status.value = "شواهد خبر برای ثبت در ژورنال کامل نیست"
            return
        }
        try {
            val trade = journal.open(signal, current.symbol, current.lastPrice!!,
                recentSettings.accountBalance, recentSettings.riskPercent,
                mtf = MtfSnapshotRecord.from(mtf), automatic = true, newsEvidence = newsRecord)
            _status.value = "کاغذی ثبت شد: ${trade.symbol} ${trade.action} · شناسهٔ ${trade.id.take(8)}؛ در ژورنال قابل مشاهده است"
        } catch (e: Exception) {
            _status.value = "ورود خودکار کاغذی انجام نشد: ${e.message ?: "ژورنال یا ریسک نامعتبر است"}"
        }
    }
}
