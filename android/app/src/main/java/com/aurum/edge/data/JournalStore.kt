package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.PaperTrade
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JournalStore(
    context: Context,
    private val file: File = File(context.filesDir, "paper_journal.json"),
    @Suppress("UNUSED_PARAMETER") private val settingsStore: SettingsStore? = null,
) {
    init {
        runCatching {
            if (file.exists()) file.delete()
            val backup = File(file.path + ".bak")
            if (backup.exists()) backup.delete()
        }
    }

    private val _trades = MutableStateFlow<List<PaperTrade>>(emptyList())
    val trades: StateFlow<List<PaperTrade>> = _trades.asStateFlow()

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
        val grossLoss = kotlin.math.abs(losses.sumOf { it.pnlUsd ?: 0.0 })
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
