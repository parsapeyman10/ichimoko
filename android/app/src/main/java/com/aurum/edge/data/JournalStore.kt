package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import com.aurum.edge.core.Candle
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctPriceActionRecord
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.PaperAiReview
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.Signal
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.engine.NewsConfluence
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
class JournalStore(
    context: Context,
    private val file: File = File(context.filesDir, "paper_journal.json"),
    private val settingsStore: SettingsStore? = null,
) {

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
    private val _reportError = MutableStateFlow<String?>(null)
    val reportError: StateFlow<String?> = _reportError.asStateFlow()
    private var reportsLoaded = false

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
        mutex.withLock {
            val list = try {
                if (reportsFile.exists() || File(reportsFile.path + ".bak").exists()) {
                    json.decodeFromString(ListSerializer(WalkForwardRecord.serializer()),
                        AtomicFile(reportsFile).openRead().bufferedReader().use { it.readText() })
                } else emptyList()
            } catch (error: Exception) {
                reportsLoaded = false
                _reportError.value = "فایل گزارش پژوهش خوانده نشد؛ گزارش‌ها حذف یا بازنویسی نشدند"
                throw IllegalStateException(_reportError.value, error)
            }
            _reports.value = list.sortedByDescending { it.generatedAt }
            _reportError.value = null
            reportsLoaded = true
        }
    }

    suspend fun saveReport(report: WalkForwardRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(reportsLoaded && _reportError.value == null) { "گزارش‌های قبلی بارگذاری نشده/آسیب‌دیده‌اند؛ بازنویسی نمی‌شود" }
            val next = (_reports.value + report).sortedByDescending { it.generatedAt }.take(12)
            val atomic = AtomicFile(reportsFile)
            val stream = atomic.startWrite()
            try {
                stream.write(json.encodeToString(ListSerializer(WalkForwardRecord.serializer()), next)
                    .toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
            _reports.value = next // only a durable report is called 'stored'
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
        customNote: String? = null,
    ): PaperTrade {
        val technicalConditions = signal.confluence.filterNot { it.name == NewsConfluence.NEWS_LABEL }
        val isLegacyEight = technicalConditions.size == 8
        val techOk = if (isLegacyEight) {
            technicalConditions.all { it.ok && it.status == ConfluenceStatus.CONFIRMED }
        } else {
            signal.isActionable && signal.confidence >= 72.0
        }
        require(!automatic || (!manual && signal.isActionable && signal.barTime > 0 &&
            techOk && (mtf == null || !mtf.veto))) {
            "شروط فنی و چندتایم‌فریم برای ورود خودکار کاغذی کامل نیست"
        }
        val stop = signal.stopLoss ?: throw IllegalArgumentException("حد ضرر وجود ندارد")
        val target = signal.takeProfit ?: throw IllegalArgumentException("حد سود وجود ندارد")
        require(manual || !isLegacyEight || priceAction?.matches(signal, symbol, price) == true) {
            "شواهد همان کندلِ رنج/ICT برای ورود سیگنالی کاغذی ثبت نشده است"
        }
        val draft = PaperOrderRules.preview(signal.action, symbol, price, stop, target, balance, riskPercent)
        val startConditions = technicalConditions.take(8)
            .filter { it.status == ConfluenceStatus.CONFIRMED }
            .joinToString("، ") { it.name.substringAfter('·').trim() }
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
                customNote != null -> customNote
                manual -> "ورود دستی کاغذی"
                automatic -> "شروع خودکار: $startConditions"
                else -> "شروع: $startConditions"
            },
            mtf = if (manual) null else mtf,
            autoOpened = automatic,
            signalBarTime = if (manual) null else signal.barTime,
            newsEvidence = if (manual) null else newsEvidence,
            entryConditions = if (manual) emptyList() else technicalConditions.map {
                com.aurum.edge.core.PaperConditionRecord.from(it)
            },
            priceAction = if (manual) null else priceAction,
            leverage = draft.leverage,
            marginUsd = draft.marginUsd,
            commissionUsd = draft.commissionUsd,
            spreadCostUsd = draft.spreadCostUsd,
        )
        mutex.withLock {
            // Serialize the check and append. Up to 4 concurrent open positions allowed (1 Crypto, 1 Forex, 1 Commodity, 1 Stock).
            val openTrades = _trades.value.filter { it.isOpen }
            require(openTrades.size < 4) {
                "سقف ۴ معاملهٔ همزمان باز پورتفو پر شده است (${openTrades.size}/4)؛ ابتدا یکی از معاملات را ببندید"
            }
            require(openTrades.none { it.symbol == symbol }) {
                "برای نماد $symbol از قبل پوزیشن کاغذی باز دارید؛ ابتدا آن را ببندید"
            }
            val targetClass = com.aurum.edge.core.AssetClass.of(symbol)
            val openInClass = openTrades.count { com.aurum.edge.core.AssetClass.of(it.symbol) == targetClass }
            require(openInClass < targetClass.maxSlots) {
                "ظرفیت معاملهٔ همزمان در دستهٔ «${targetClass.label}» تکمیل است (حداکثر ۱ پوزیشن در هر دسته دارایی)"
            }
            if (automatic) require(_trades.value.none {
                it.symbol == symbol && it.signalBarTime == signal.barTime
            }) { "در همین کندل سیگنال، معاملهٔ کاغذی قبلاً ثبت شده است" }
            val totalRisk = _trades.value.filter { it.isOpen }.sumOf { it.riskUsd }
            require(totalRisk + draft.actualRiskUsd <= balance * 0.15 + 1e-4) {
                "مجموع ریسک پوزیشن‌های کاغذی از سقف بودجه پورتفو عبور می‌کند"
            }
            persist(_trades.value + trade)
        }
        return trade
    }

    suspend fun attachAiReview(tradeId: String, review: PaperAiReview): PaperTrade = mutex.withLock {
        val current = _trades.value
        val index = current.indexOfFirst { it.id == tradeId }
        require(index >= 0) { "معاملهٔ کاغذی برای ثبت نظر AI پیدا نشد" }
        val updated = current[index].copy(aiReview = review)
        persist(current.toMutableList().also { it[index] = updated })
        updated
    }

    /**
     * Mark open trades against the newest real candle.
     * A trade closes only when a real price actually reached its stop or target.
     */
    suspend fun settle(candle: Candle, symbol: String, observedAt: Long): List<PaperTrade> = mutex.withLock {
        if (!loaded || candle.time <= 0 ||
            !listOf(candle.open, candle.high, candle.low, candle.close).all { it.isFinite() && it > 0 } ||
            candle.high < maxOf(candle.open, candle.close) ||
            candle.low > minOf(candle.open, candle.close) || candle.low > candle.high) return@withLock emptyList()
        val current = _trades.value
        if (current.none { it.isOpen && it.symbol == symbol }) return@withLock emptyList()
        var changed = false
        var settledPnlDelta = 0.0
        val newlyClosed = mutableListOf<PaperTrade>()
        val updated = current.map { t ->
            // A historical candle timestamped before the trade was opened cannot settle this position.
            if (!t.isOpen || t.symbol != symbol || candle.time < t.openedAt) return@map t

            val hitStop = if (t.action == SignalAction.BUY) {
                candle.low <= t.stopLoss + 1e-9
            } else {
                candle.high >= t.stopLoss - 1e-9
            }
            val hitTarget = if (t.action == SignalAction.BUY) {
                candle.high >= t.takeProfit - 1e-9
            } else {
                candle.low <= t.takeProfit + 1e-9
            }
            if (!hitStop && !hitTarget) return@map t
            // If both were touched inside one bar, assume the stop was hit first (conservative).
            val exit = if (hitStop) t.stopLoss else t.takeProfit
            val pnlPerOz = if (t.action == SignalAction.BUY) {
                exit - t.entry
            } else {
                t.entry - exit
            }
            val pnl = kotlin.math.round(
                PaperOrderRules.quotePnlToUsd(t.symbol, pnlPerOz * t.positionOz, exit) * 100.0) / 100.0
            settledPnlDelta += pnl
            changed = true
            val closed = t.copy(
                closedAt = observedAt,
                exitPrice = exit,
                exitReason = if (hitStop) "حد ضرر (قیمت واقعی)" else "حد سود (قیمت واقعی)",
                pnlUsd = pnl,
            )
            newlyClosed.add(closed)
            closed
        }
        if (changed) {
            persist(updated)
            if (settledPnlDelta != 0.0) {
                settingsStore?.adjustBalance(settledPnlDelta)
            }
        }
        return@withLock newlyClosed
    }

    /**
     * Direct live-tick settlement: whenever a fresh real-time quote arrives for any symbol,
     * immediately checks if Stop Loss or Take Profit has been touched and closes the trade automatically.
     */
    suspend fun settleTick(symbol: String, price: Double, observedAt: Long = System.currentTimeMillis()): List<PaperTrade> = mutex.withLock {
        if (!loaded || !price.isFinite() || price <= 0.0) return@withLock emptyList()
        val current = _trades.value
        val openForSymbol = current.filter { it.isOpen && it.symbol == symbol }
        if (openForSymbol.isEmpty()) return@withLock emptyList()

        var changed = false
        var settledPnlDelta = 0.0
        val newlyClosed = mutableListOf<PaperTrade>()
        val updated = current.map { t ->
            if (!t.isOpen || t.symbol != symbol) return@map t
            if (observedAt < t.openedAt - 10_000L) return@map t

            val hitStop = if (t.action == SignalAction.BUY) {
                price <= t.stopLoss + 1e-9
            } else {
                price >= t.stopLoss - 1e-9
            }
            val hitTarget = if (t.action == SignalAction.BUY) {
                price >= t.takeProfit - 1e-9
            } else {
                price <= t.takeProfit + 1e-9
            }

            if (!hitStop && !hitTarget) return@map t

            val exit = if (hitStop) t.stopLoss else t.takeProfit
            val pnlPerOz = if (t.action == SignalAction.BUY) {
                exit - t.entry
            } else {
                t.entry - exit
            }
            val pnl = kotlin.math.round(
                PaperOrderRules.quotePnlToUsd(t.symbol, pnlPerOz * t.positionOz, exit) * 100.0) / 100.0
            settledPnlDelta += pnl
            changed = true
            val closed = t.copy(
                closedAt = observedAt,
                exitPrice = exit,
                exitReason = if (hitStop) "حد ضرر (قیمت لحظه‌ای)" else "حد سود (قیمت لحظه‌ای)",
                pnlUsd = pnl,
            )
            newlyClosed.add(closed)
            closed
        }
        if (changed) {
            persist(updated)
            if (settledPnlDelta != 0.0) {
                settingsStore?.adjustBalance(settledPnlDelta)
            }
        }
        return@withLock newlyClosed
    }

    suspend fun close(tradeId: String, price: Double, reason: String): PaperTrade = mutex.withLock {
        require(price.isFinite() && price > 0) { "قیمت خروج معتبر نیست" }
        val trade = _trades.value.singleOrNull { it.id == tradeId && it.isOpen }
            ?: throw IllegalArgumentException("پوزیشن باز در ژورنال پیدا نشد یا قبلاً بسته شده است")
        val pnlPerOz = if (trade.action == SignalAction.BUY) price - trade.entry else trade.entry - price
        val pnl = kotlin.math.round(
            PaperOrderRules.quotePnlToUsd(trade.symbol, pnlPerOz * trade.positionOz, price) * 100.0) / 100.0
        val closed = trade.copy(
            closedAt = System.currentTimeMillis(), exitPrice = price, exitReason = reason,
            pnlUsd = pnl,
        )
        persist(_trades.value.map { if (it.id == tradeId) closed else it })
        if (pnl != 0.0) {
            settingsStore?.adjustBalance(pnl)
        }
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
