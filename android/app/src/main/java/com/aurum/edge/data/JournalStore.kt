package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AutoExitEvaluation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.RiskCalculationResult
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Candle
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class JournalStore(
    context: Context,
    private val file: File = File(context.filesDir, "user_journal_v1.json"),
    private val settingsStore: SettingsStore? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(PaperTrade.serializer())

    init {
        // Ensure legacy auto-strategy journal files stay purged
        runCatching {
            File(context.filesDir, "paper_journal.json").delete()
            File(context.filesDir, "paper_journal.json.bak").delete()
        }
    }

    private val _trades = MutableStateFlow(loadFromDisk())
    val trades: StateFlow<List<PaperTrade>> = _trades.asStateFlow()

    @Synchronized
    private fun loadFromDisk(): List<PaperTrade> {
        val candidates = listOf(file, File(file.path + ".bak"))
        for (candidate in candidates) {
            if (!candidate.exists()) continue
            val parsed = runCatching {
                json.decodeFromString(listSerializer, candidate.readText())
            }.getOrNull()
            if (parsed != null) {
                return parsed.sortedByDescending { it.openedAt }
            }
        }
        return emptyList()
    }

    @Synchronized
    private fun persist(list: List<PaperTrade>) {
        val sorted = list.sortedByDescending { it.openedAt }
        _trades.value = sorted
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = json.encodeToString(listSerializer, sorted)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encoded)
            if (file.exists()) {
                file.copyTo(File(file.path + ".bak"), overwrite = true)
            }
            if (!tmp.renameTo(file)) {
                file.writeText(encoded)
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun openTrade(
        calc: RiskCalculationResult,
        note: String = "",
        now: Long = System.currentTimeMillis(),
    ): PaperTrade {
        val trade = calc.toPaperTrade(note = note, openedAt = now)
        persist( listOf(trade) + _trades.value )
        return trade
    }

    @Synchronized
    fun addTrade(trade: PaperTrade) {
        persist(listOf(trade) + _trades.value.filterNot { it.id == trade.id })
    }

    /**
     * به‌روزرسانی خودکار حد ضرر (Break-Even در `1R`، تریل با `Kijun-sen` یا `Preserve Mode` پشت `Tenkan-sen`)
     * روی معاملات باز بیت‌کوین.
     */
    @Synchronized
    fun updateTradeStopLoss(
        tradeId: String,
        newStopLoss: Double,
        stageNote: String,
    ): PaperTrade? {
        if (!newStopLoss.isFinite() || newStopLoss <= 0.0) return null
        val target = _trades.value.firstOrNull { it.id == tradeId && it.isOpen } ?: return null
        val updatedNote = if (stageNote.isBlank() || target.note.contains(stageNote)) {
            target.note
        } else if (target.note.isBlank()) {
            stageNote
        } else {
            "${target.note} · $stageNote"
        }
        val modified = target.copy(
            stopLoss = newStopLoss,
            initialStopLoss = target.initialStopLoss ?: target.stopLoss,
            note = updatedNote,
        )
        persist(_trades.value.map { if (it.id == tradeId) modified else it })
        return modified
    }

    /**
     * Evaluates an incoming live market tick against all open trades for [symbolInput].
     * Any trade whose TP or SL is reached is automatically closed, persisted, and settled
     * against account balance.
     */
    @Synchronized
    fun onLiveTick(symbolInput: String, tick: PriceTick): List<AutoExitEvaluation> {
        val symbol = V1Universe.normalize(symbolInput)
        val current = _trades.value
        if (current.none { it.isOpen && V1Universe.normalize(it.symbol) == symbol }) {
            return emptyList()
        }
        val triggered = mutableListOf<AutoExitEvaluation>()
        val updated = current.map { trade ->
            if (!trade.isOpen || V1Universe.normalize(trade.symbol) != symbol) {
                trade
            } else {
                val hit = RiskAndCalendarEngine.evaluateAutoExit(trade, tick)
                if (hit != null) {
                    triggered += hit
                    trade.copy(
                        closedAt = hit.closedAt,
                        exitPrice = hit.exitPrice,
                        exitReason = hit.kind.reasonFa,
                        pnlUsd = hit.netPnlUsd,
                    )
                } else {
                    trade
                }
            }
        }
        if (triggered.isNotEmpty()) {
            persist(updated)
            val totalDelta = triggered.sumOf { it.netPnlUsd }
            settingsStore?.adjustBalance(totalDelta)
        }
        return triggered
    }

    /**
     * Chronologically evaluates historical OHLC candles (fetched after an offline gap)
     * against all open trades for [symbolInput]. Any trade whose TP or SL was hit during
     * the offline hours is closed at the exact candle timestamp and settled in the journal.
     */
    @Synchronized
    fun onBackfillCandles(symbolInput: String, candles: List<Candle>): List<AutoExitEvaluation> {
        val symbol = V1Universe.normalize(symbolInput)
        val current = _trades.value
        if (candles.isEmpty() || current.none { it.isOpen && V1Universe.normalize(it.symbol) == symbol }) {
            return emptyList()
        }
        val triggered = mutableListOf<AutoExitEvaluation>()
        val updated = current.map { trade ->
            if (!trade.isOpen || V1Universe.normalize(trade.symbol) != symbol) {
                trade
            } else {
                val hit = RiskAndCalendarEngine.evaluateCandleWindow(trade, candles)
                if (hit != null) {
                    triggered += hit
                    trade.copy(
                        closedAt = hit.closedAt,
                        exitPrice = hit.exitPrice,
                        exitReason = "${hit.kind.reasonFa} · بازیابی ساعات قطعی",
                        pnlUsd = hit.netPnlUsd,
                    )
                } else {
                    trade
                }
            }
        }
        if (triggered.isNotEmpty()) {
            persist(updated)
            val totalDelta = triggered.sumOf { it.netPnlUsd }
            settingsStore?.adjustBalance(totalDelta)
        }
        return triggered
    }

    @Synchronized
    fun closeTradeAtPrice(
        tradeId: String,
        exitPrice: Double,
        reason: String = "بستن با قیمت لحظه‌ای",
        now: Long = System.currentTimeMillis(),
    ): PaperTrade? {
        if (!exitPrice.isFinite() || exitPrice <= 0.0) return null
        val target = _trades.value.firstOrNull { it.id == tradeId && it.isOpen } ?: return null
        val (_, netPnl) = RiskAndCalendarEngine.computeExitPnlUsd(target, exitPrice)
        val roundedNet = round(netPnl * 100.0) / 100.0
        val closed = target.copy(
            closedAt = max(now, target.openedAt + 1L),
            exitPrice = exitPrice,
            exitReason = reason,
            pnlUsd = roundedNet,
        )
        persist(_trades.value.map { if (it.id == tradeId) closed else it })
        settingsStore?.adjustBalance(roundedNet)
        return closed
    }

    @Synchronized
    fun deleteTrade(tradeId: String) {
        persist(_trades.value.filterNot { it.id == tradeId })
    }

    @Synchronized
    fun clear() {
        runCatching {
            if (file.exists()) file.delete()
            val backup = File(file.path + ".bak")
            if (backup.exists()) backup.delete()
        }
        _trades.value = emptyList()
    }

    fun stats(): JournalStats {
        val closed = _trades.value.filter { !it.isOpen && it.pnlUsd != null }
        val wins = closed.filter { (it.pnlUsd ?: 0.0) > 0 }
        val losses = closed.filter { (it.pnlUsd ?: 0.0) <= 0 }
        val grossWin = wins.sumOf { it.pnlUsd ?: 0.0 }
        val grossLoss = abs(losses.sumOf { it.pnlUsd ?: 0.0 })
        val rMultiples = closed.mapNotNull { it.rMultiple }
        return JournalStats(
            total = closed.size,
            open = _trades.value.count { it.isOpen },
            wins = wins.size,
            losses = losses.size,
            winRate = if (closed.isEmpty()) null else wins.size * 100.0 / closed.size,
            profitFactor = if (grossLoss <= 0.0) null else grossWin / grossLoss,
            netPnl = closed.sumOf { it.pnlUsd ?: 0.0 },
            avgR = if (rMultiples.isEmpty()) null else rMultiples.average(),
        )
    }
}

data class JournalStats(
    val total: Int,
    val open: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double?,
    val profitFactor: Double?,
    val netPnl: Double,
    val avgR: Double?,
)
