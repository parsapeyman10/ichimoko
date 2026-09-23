package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import com.aurum.edge.core.Candle
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.Signal
import com.aurum.edge.core.WalkForwardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Journal of *paper* trades executed at *real* prices.
 *
 * This is the only "demo" concept the app has: the strategy practices on live market data
 * and every outcome is settled against real subsequent prices. Nothing here is invented —
 * an open position is marked to market with the last real price, and closes only when a
 * real price touches the stop or the target.
 */
class JournalStore(context: Context) {

    private val file = File(context.filesDir, "paper_journal.json")
    private val reportsFile = File(context.filesDir, "walk_forward_reports.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /**
     * Last walk-forward reports run **on this device**. They are persisted on purpose: a report
     * that cannot be re-checked later would be exactly the kind of unverifiable claim this app
     * refuses to make.
     */
    private val _reports = MutableStateFlow<List<WalkForwardRecord>>(emptyList())
    val reports: StateFlow<List<WalkForwardRecord>> = _reports.asStateFlow()

    private val _trades = MutableStateFlow<List<PaperTrade>>(emptyList())
    val trades: StateFlow<List<PaperTrade>> = _trades.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = if (file.exists() || File(file.path + ".bak").exists()) {
                runCatching {
                    json.decodeFromString(ListSerializer(PaperTrade.serializer()),
                        AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                }.getOrDefault(emptyList())
            } else emptyList()
            _trades.value = list.sortedByDescending { it.openedAt }
        }
    }

    private suspend fun persist(list: List<PaperTrade>) = withContext(Dispatchers.IO) {
        val sorted = list.sortedByDescending { it.openedAt }.take(500)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(ListSerializer(PaperTrade.serializer()), sorted).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error // no toast claiming an entry succeeded if it was not saved
        }
        _trades.value = sorted
    }

    suspend fun loadReports() = withContext(Dispatchers.IO) {
        val list = if (reportsFile.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(WalkForwardRecord.serializer()), reportsFile.readText())
            }.getOrDefault(emptyList())
        } else emptyList()
        _reports.value = list.sortedByDescending { it.generatedAt }
    }

    suspend fun saveReport(report: WalkForwardRecord) = withContext(Dispatchers.IO) {
        val next = (_reports.value + report).sortedByDescending { it.generatedAt }.take(12)
        _reports.value = next
        runCatching {
            val tmp = File(reportsFile.parentFile, reportsFile.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(WalkForwardRecord.serializer()), next))
            if (reportsFile.exists()) reportsFile.delete()
            tmp.renameTo(reportsFile)
        }
    }

    suspend fun open(
        signal: Signal,
        symbol: String,
        price: Double,
        balance: Double,
        riskPercent: Double,
        mtf: MtfSnapshotRecord? = null,
        manual: Boolean = false,
    ): PaperTrade {
        val stop = signal.stopLoss ?: throw IllegalArgumentException("حد ضرر وجود ندارد")
        val target = signal.takeProfit ?: throw IllegalArgumentException("حد سود وجود ندارد")
        val draft = PaperOrderRules.preview(signal.action, symbol, price, stop, target, balance, riskPercent)
        val trade = PaperTrade(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            interval = signal.interval,
            action = signal.action,
            entry = price,
            stopLoss = stop,
            takeProfit = target,
            confidence = if (manual) 0.0 else signal.confidence,
            riskReward = draft.rewardRisk,
            openedAt = System.currentTimeMillis(),
            positionOz = draft.quantity,
            positionUnit = draft.unit,
            note = if (manual) "ورود دستی کاغذی؛ بدون تأیید موتور/بروکر" else "سیگنال کاغذی روی قیمت دریافتی — ${signal.interval.label}",
            mtf = if (manual) null else mtf,
        )
        mutex.withLock {
            // Serialize the check and append. No pyramiding or duplicate position per symbol.
            require(_trades.value.none { it.isOpen && it.symbol == symbol }) {
                "برای این نماد پوزیشن کاغذی باز دارید؛ ابتدا آن را ببندید"
            }
            val totalRisk = _trades.value.filter { it.isOpen }.sumOf { it.riskPerOz * it.positionOz }
            require(totalRisk + draft.actualRiskUsd <= balance * 0.05 + 1e-8) {
                "مجموع ریسک پوزیشن‌های کاغذی از ۵٪ موجودی عبور می‌کند"
            }
            persist(_trades.value + trade)
        }
        return trade
    }

    /**
     * Mark open trades against the newest real candle.
     * A trade closes only when a real price actually reached its stop or target.
     */
    suspend fun settle(candle: Candle, symbol: String, observedAt: Long) = mutex.withLock {
        val current = _trades.value
        if (current.none { it.isOpen }) return@withLock
        var changed = false
        val updated = current.map { t ->
            // A different symbol or a bar opened before this position must never settle it.
            // In particular, caching/replaying historical bars cannot close a new position.
            if (!t.isOpen || t.symbol != symbol || candle.time <= t.openedAt) return@map t
            val hitStop = if (t.action == SignalAction.BUY) {
                candle.low <= t.stopLoss
            } else {
                candle.high >= t.stopLoss
            }
            val hitTarget = if (t.action == SignalAction.BUY) {
                candle.high >= t.takeProfit
            } else {
                candle.low <= t.takeProfit
            }
            if (!hitStop && !hitTarget) return@map t
            // If both were touched inside one bar, assume the stop was hit first (conservative).
            val exit = if (hitStop) t.stopLoss else t.takeProfit
            val pnlPerOz = if (t.action == SignalAction.BUY) {
                exit - t.entry
            } else {
                t.entry - exit
            }
            changed = true
            t.copy(
                closedAt = observedAt,
                exitPrice = exit,
                exitReason = if (hitStop) "حد ضرر (قیمت واقعی)" else "حد سود (قیمت واقعی)",
                pnlUsd = kotlin.math.round(pnlPerOz * t.positionOz * 100.0) / 100.0,
            )
        }
        if (changed) persist(updated)
    }

    suspend fun close(tradeId: String, price: Double, reason: String) = mutex.withLock {
        require(price.isFinite() && price > 0) { "قیمت خروج معتبر نیست" }
        val updated = _trades.value.map { t ->
            if (t.id != tradeId || !t.isOpen) return@map t
            val pnlPerOz = if (t.action == SignalAction.BUY) {
                price - t.entry
            } else {
                t.entry - price
            }
            t.copy(
                closedAt = System.currentTimeMillis(),
                exitPrice = price,
                exitReason = reason,
                pnlUsd = kotlin.math.round(pnlPerOz * t.positionOz * 100.0) / 100.0,
            )
        }
        persist(updated)
    }

    suspend fun clear() = mutex.withLock { persist(emptyList()) }

    /** Statistics computed only from settled real-price outcomes. */
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
