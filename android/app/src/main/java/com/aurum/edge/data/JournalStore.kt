package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import com.aurum.edge.core.Candle
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctPriceActionRecord
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperNewsRecord
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
class JournalStore(context: Context, private val file: File = File(context.filesDir, "paper_journal.json")) {

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
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            val list = try {
                if (file.exists() || File(file.path + ".bak").exists()) {
                    json.decodeFromString(ListSerializer(PaperTrade.serializer()),
                        AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                } else emptyList()
            } catch (e: Exception) {
                // Never quietly replace a damaged journal with [] and overwrite the only copy.
                _loadError.value = "فایل ژورنال خوانده نشد؛ برای جلوگیری از حذف سوابق، ثبت جدید متوقف شد"
                throw IllegalStateException(_loadError.value, e)
            }
            _trades.value = list.sortedByDescending { it.openedAt }
            _loadError.value = null
            loaded = true
        }
    }

    private suspend fun persist(list: List<PaperTrade>) = withContext(Dispatchers.IO) {
        check(loaded && _loadError.value == null) { "ژورنال بارگذاری نشده/آسیب‌دیده است؛ فایل موجود پاک نمی‌شود" }
        val sorted = (list.filter { it.isOpen } +
            list.filterNot { it.isOpen }.sortedByDescending { it.openedAt }.take(500))
            .sortedByDescending { it.openedAt }
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
        automatic: Boolean = false,
        newsEvidence: PaperNewsRecord? = null,
        priceAction: IctPriceActionRecord? = null,
    ): PaperTrade {
        require(!automatic || (!manual && signal.isActionable && signal.barTime > 0 &&
            newsEvidence != null && newsEvidence.evidence.isNotEmpty() &&
            newsEvidence.calendarSource == FOREX_CALENDAR_SOURCE_URL && newsEvidence.calendarCheckedAt != null &&
            signal.confluence.size >= 9 && signal.confluence.take(9).all {
                it.ok && it.status == ConfluenceStatus.CONFIRMED
            } && signal.confluence[8].name == com.aurum.edge.engine.NewsConfluence.NEWS_LABEL &&
            mtf != null && !mtf.veto && mtf.barTime == signal.barTime && mtf.frames.isNotEmpty())) {
            "۹ شرط، تقویم/خبر AI یا چندتایم‌فریم برای ورود خودکار کاغذی کامل نیست"
        }
        val stop = signal.stopLoss ?: throw IllegalArgumentException("حد ضرر وجود ندارد")
        val target = signal.takeProfit ?: throw IllegalArgumentException("حد سود وجود ندارد")
        require(manual || priceAction?.matches(signal, symbol, price) == true) {
            "شواهد همان کندلِ رنج/ICT برای ورود سیگنالی کاغذی ثبت نشده است"
        }
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
            note = when {
                manual -> "ورود دستی کاغذی؛ بدون تأیید موتور/بروکر"
                automatic -> "ورود خودکار کاغذی با ۸ شرط فنی + خبر AI و شواهد رنج/ICT؛ بدون سفارش بروکر"
                else -> "سیگنال کاغذی روی قیمت دریافتی — ${signal.interval.label}"
            },
            mtf = if (manual) null else mtf,
            autoOpened = automatic,
            signalBarTime = if (manual) null else signal.barTime,
            newsEvidence = if (manual) null else newsEvidence,
            entryConditions = if (manual) emptyList() else signal.confluence.take(9).map {
                com.aurum.edge.core.PaperConditionRecord.from(it)
            },
            priceAction = if (manual) null else priceAction,
        )
        mutex.withLock {
            // Serialize the check and append. No pyramiding or duplicate position per symbol.
            require(_trades.value.none { it.isOpen && it.symbol == symbol }) {
                "برای این نماد پوزیشن کاغذی باز دارید؛ ابتدا آن را ببندید"
            }
            if (automatic) require(_trades.value.none {
                it.symbol == symbol && it.signalBarTime == signal.barTime
            }) { "در همین کندل سیگنال، معاملهٔ کاغذی قبلاً ثبت شده است" }
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
        if (!loaded || candle.time <= 0 || observedAt < candle.time ||
            !listOf(candle.open, candle.high, candle.low, candle.close).all { it.isFinite() && it > 0 } ||
            candle.high < maxOf(candle.open, candle.close) ||
            candle.low > minOf(candle.open, candle.close) || candle.low > candle.high) return@withLock
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

    suspend fun close(tradeId: String, price: Double, reason: String): PaperTrade = mutex.withLock {
        require(price.isFinite() && price > 0) { "قیمت خروج معتبر نیست" }
        val trade = _trades.value.singleOrNull { it.id == tradeId && it.isOpen }
            ?: throw IllegalArgumentException("پوزیشن باز در ژورنال پیدا نشد یا قبلاً بسته شده است")
        val pnlPerOz = if (trade.action == SignalAction.BUY) price - trade.entry else trade.entry - price
        val closed = trade.copy(
            closedAt = System.currentTimeMillis(), exitPrice = price, exitReason = reason,
            pnlUsd = kotlin.math.round(pnlPerOz * trade.positionOz * 100.0) / 100.0,
        )
        persist(_trades.value.map { if (it.id == tradeId) closed else it })
        closed
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
