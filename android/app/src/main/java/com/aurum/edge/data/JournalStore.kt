package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import com.aurum.edge.core.Candle
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.IctPriceActionRecord
import com.aurum.edge.core.MarketTrendRecord
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.TechnicalEvidence
import com.aurum.edge.core.PaperPortfolioPolicy
import com.aurum.edge.core.PaperTrailing
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.PaperAiReview
import com.aurum.edge.core.PaperHoldReview
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.Signal
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

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
        /**
         * «روند کلی بازار» در همان لحظهٔ ورود. فقط یک عکسِ ثبت‌شده است: نه گیت ورود است و
         * نه بعد از ورود دوباره حساب می‌شود (گیتِ روند در پویشگر/ورود خودکار اعمال شده است).
         */
        marketTrend: MarketTrendRecord? = null,
        observedAt: Long = System.currentTimeMillis(),
        cryptoVenue: String = "NOBITEX",
        /** Independent same-venue trade print and observed top-of-book, never a candle close. */
        cryptoEntryQuote: com.aurum.edge.core.PriceTick? = null,
        cryptoNews: PersianNewsState? = null,
        sourceIntegrityRevision: Long = 0L,
    ): PaperTrade {
        SourceIntegrity.shared.blocker(symbol, sourceIntegrityRevision)?.let { throw IllegalArgumentException(it) }
        require(com.aurum.edge.core.V1Universe.valid(symbol)) {
            "نماد از بازارهای فعال حذف شده است؛ فقط مدیریت سوابق قبلی مجاز است"
        }
        // Authoritative entry guard, independent of UI, scanner and automatic trader. Do not
        // move this into settlement: already-open oil positions must still be manageable.
        require(signal.interval == com.aurum.edge.core.Interval.M5) {
            "ثبت پوزیشن تازه فقط از سیگنال کندل بستهٔ ۵ دقیقه (5m) مجاز است"
        }
        if (CryptoCatalog.isCrypto(symbol)) require(signal.cryptoBreakout?.matches(signal) == true) {
            "فقط استراتژی فشردگی ← انفجار برای ورود تازهٔ رمزارز مجاز است"
        }
        require(signal.ichimokuEntry?.matches(signal) == true) {
            "کراس بستهٔ تنکان/کیجون، ابر نازک و فشردگی پیش از ورود تأیید نشده‌اند"
        }
        require(com.aurum.edge.core.OilEntrySafety.blocker(symbol) == null) {
            com.aurum.edge.core.OilEntrySafety.REASON
        }
        require(!com.aurum.edge.core.MarketHours.closedFor(symbol, observedAt)) {
            "بازار این نماد بسته است؛ ثبت پوزیشن کاغذی ممنوع"
        }
        require(!CryptoCatalog.isCrypto(symbol) || cryptoVenue in setOf("NOBITEX", BinanceSpotClient.VENUE)) {
            "صرافی ورود نامعتبر است"
        }
        if (CryptoCatalog.isCrypto(symbol)) {
            require(riskPercent in 0.3..0.5) { "ریسک رمزارز باید ۰٫۳ تا ۰٫۵ درصد باشد" }
            require(com.aurum.edge.core.CryptoCompressionPolicy.quoteBlocker(
                signal, cryptoEntryQuote, observedAt) == null && cryptoEntryQuote?.price == price) {
                "اسپرد و قیمت واقعی مستقل هم‌صرافی رمزارز تأیید نشده است"
            }
            require(cryptoNews?.gate == NewsGate.CLEAR && cryptoNews.cached == false &&
                cryptoNews.lastCheckedAt?.let { observedAt - it in 0L..180_000L } == true &&
                cryptoNews.calendarCheckedAt?.let { observedAt - it in 0L..1_200_000L } == true) {
                "خبر/تقویم پراثر یا تازگی آن برای ورود رمزارز تأیید نشده است"
            }
        }
        val technicalConditions = TechnicalEvidence.items(signal)
        require(signal.isActionable && TechnicalEvidence.confirmed(signal)) {
            "شروط فنی کامل و تأیید شده نیستند"
        }
        require(!automatic || (!manual && signal.barTime > 0)) {
            "ورود خودکار فقط با سیگنال همان کندل بسته مجاز است"
        }
        val stop = signal.stopLoss ?: throw IllegalArgumentException("حد ضرر وجود ندارد")
        val target = signal.takeProfit ?: throw IllegalArgumentException("حد سود وجود ندارد")
        require(priceAction == null || priceAction.matches(signal, symbol, price)) {
            "شواهد اختیاری ICT با همان کندل مطابقت ندارد"
        }
        if (CryptoCatalog.isCrypto(symbol)) require(kotlin.math.abs(target - price) >=
            3.0 * kotlin.math.abs(price - stop) - 1e-9) { "نسبت واقعی ریسک به ریوارد رمزارز باید حداقل ۱:۳ باشد" }
        val draft = PaperOrderRules.preview(signal.action, symbol, price, stop, target, balance, riskPercent)
        val startConditions = technicalConditions
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
            confidence = signal.confidence,
            riskReward = draft.rewardRisk,
            openedAt = observedAt,
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
            signalBarTime = signal.barTime,
            newsEvidence = if (manual) null else newsEvidence,
            entryConditions = technicalConditions.map {
                com.aurum.edge.core.PaperConditionRecord.from(it)
            },
            priceAction = if (manual) null else priceAction,
            leverage = draft.leverage,
            marginUsd = draft.marginUsd,
            commissionUsd = draft.commissionUsd,
            spreadCostUsd = draft.spreadCostUsd,
            marketTrend = marketTrend,
            initialStopLoss = stop,
            trailExtreme = price,
            cryptoEntryAtr = if (CryptoCatalog.isCrypto(symbol)) signal.cryptoBreakout?.atr else null,
            usesTickArchive = CryptoCatalog.isCrypto(symbol) && cryptoVenue == "NOBITEX" &&
                settingsStore?.hasTickArchive() == true,
            cryptoVenue = cryptoVenue,
        )
        mutex.withLock {
            // Settings and ledger can change while an AI/preview was running. Fail closed
            // rather than saving a ticket sized against an old balance or risk setting.
            val latest = settingsStore?.read()
            require(latest == null || (kotlin.math.abs(latest.accountBalance - balance) < 0.02 &&
                riskPercent <= latest.riskPercent + 1e-6)) {
                "موجودی یا درصد ریسک تغییر کرد؛ بلیت را دوباره محاسبه کنید"
            }
            // Check and append under the same mutex; UI/alerts are only advisory.
            val blocker = PaperPortfolioPolicy.blocker(_trades.value, symbol, balance,
                draft.actualRiskUsd + draft.commissionUsd + draft.spreadCostUsd,
                signal.barTime, now = observedAt,
                openingBalance = settingsStore?.dayOpeningEquity(observedAt))
            require(blocker == null) { blocker ?: "ظرفیت پورتفو پر است" }
            // Recheck after scheduling, sizing and any journal-lock wait. Fresh ticks or an
            // AI response cannot authorize a preview calculated before a source conflict.
            SourceIntegrity.shared.blocker(symbol, sourceIntegrityRevision)?.let { throw IllegalArgumentException(it) }
            persist(_trades.value + trade)
        }
        return trade
    }

    /** Same-venue CLOSED candles only; never a fill. The intent and ATR trail are
     * durable, but settlement waits for a later ordered executed trade on the original venue.
     */
    suspend fun applyCryptoClosedBars(symbol: String, openedAt: Long,
        venue: String, m5: List<Candle>, m15: List<Candle>,
        now: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        if (!loaded || venue != BinanceSpotClient.VENUE) return@withLock false
        val trade = _trades.value.singleOrNull { it.isOpen && it.symbol == symbol &&
            it.openedAt == openedAt && it.cryptoVenue == venue && it.cryptoEntryAtr != null }
            ?: return@withLock false
        val decision = com.aurum.edge.engine.CryptoCompressionExit.assess(trade, m5, m15, now)
            ?: return@withLock false
        val suggested = decision.stop?.let { if (trade.action == SignalAction.BUY)
            maxOf(trade.stopLoss, it) else minOf(trade.stopLoss, it) } ?: trade.stopLoss
        val next = trade.copy(
            stopLoss = suggested,
            cryptoLastClosedBarAt = decision.barClosedAt,
            cryptoExitPendingAt = if (decision.exitReason != null)
                decision.barClosedAt else trade.cryptoExitPendingAt,
            cryptoExitReason = decision.exitReason ?: trade.cryptoExitReason,
        )
        persist(_trades.value.map { if (it.id == trade.id) next else it })
        true
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
     * Attach the companion AI's advisory opinion about an OPEN position.
     *
     * Deliberately limited to a note: it never touches entry/stop/target, never sets [PaperTrade.closedAt]
     * and never adjusts the balance. A position still closes ONLY inside [settle]/[settleTick] when a real
     * price reaches its stop or target.
     */
    suspend fun attachHoldReview(tradeId: String, review: PaperHoldReview): PaperTrade? = mutex.withLock {
        val current = _trades.value
        val index = current.indexOfFirst { it.id == tradeId && it.isOpen }
        if (index < 0) return@withLock null // closed meanwhile -> nothing to annotate
        val updated = current[index].copy(holdReview = review)
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
            if (!t.isOpen || t.symbol != symbol || candle.time < t.openedAt ||
                t.usesTickArchive || CryptoCatalog.isCrypto(symbol)) return@map t

            val hitStop = if (t.action == SignalAction.BUY) {
                candle.low <= t.stopLoss + 1e-9
            } else {
                candle.high >= t.stopLoss - 1e-9
            }
            val hitTarget = if (t.initialStopLoss != null) false else if (t.action == SignalAction.BUY) {
                candle.high >= t.takeProfit - 1e-9
            } else {
                candle.low <= t.takeProfit + 1e-9
            }
            if (!hitStop && !hitTarget) {
                val advanced = PaperTrailing.advance(t, candle.high, candle.low)
                if (advanced != t) changed = true
                return@map advanced
            }
            // If both were touched inside one bar, assume the stop was hit first (conservative).
            val exit = if (hitStop) t.stopLoss else t.takeProfit
            val pnlPerOz = if (t.action == SignalAction.BUY) {
                exit - t.entry
            } else {
                t.entry - exit
            }
            val pnl = kotlin.math.round(
                (PaperOrderRules.quotePnlToUsd(t.symbol, pnlPerOz * t.positionOz, exit) -
                    t.effectiveCommissionUsd - t.effectiveSpreadCostUsd) * 100.0) / 100.0
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

    /** Persistent audit flag: no trade ID is provided to prove no tick was lost. */
    suspend fun noteTickCoverageGap(symbol: String, at: Long, expectedOpenedAt: Long? = null) = mutex.withLock {
        if (!loaded || at <= 0L || _trades.value.none {
                it.isOpen && it.symbol == symbol && it.tickCoverageGapAt == null &&
                    (expectedOpenedAt == null || it.openedAt == expectedOpenedAt) }) return@withLock
        persist(_trades.value.map { trade ->
            if (trade.isOpen && trade.symbol == symbol && trade.tickCoverageGapAt == null &&
                (expectedOpenedAt == null || trade.openedAt == expectedOpenedAt))
                trade.copy(tickCoverageGapAt = at) else trade
        })
    }

    /**
     * Direct live-tick settlement: whenever a fresh real-time quote arrives for any symbol,
     * immediately checks if Stop Loss or Take Profit has been touched and closes the trade automatically.
     */
    suspend fun settleTick(symbol: String, price: Double, observedAt: Long = System.currentTimeMillis(),
                           expectedOpenedAt: Long? = null, archiveSeq: Long? = null): List<PaperTrade> = mutex.withLock {
        if (!loaded || !price.isFinite() || price <= 0.0) return@withLock emptyList()
        val current = _trades.value
        val openForSymbol = current.filter { it.isOpen && it.symbol == symbol }
        if (openForSymbol.isEmpty()) return@withLock emptyList()

        var changed = false
        var settledPnlDelta = 0.0
        val newlyClosed = mutableListOf<PaperTrade>()
        val updated = current.map { t ->
            if (!t.isOpen || t.symbol != symbol ||
                (expectedOpenedAt != null && t.openedAt != expectedOpenedAt)) return@map t
            // A trade fetched just before entry must never close a position opened
            // afterwards, even if the quote was still within the feed freshness window.
            if (t.cryptoVenue == BinanceSpotClient.VENUE) return@map t
            if (t.usesTickArchive != (archiveSeq != null)) return@map t
            if (archiveSeq != null && archiveSeq <= t.tickArchiveSeq) return@map t
            if (observedAt < t.openedAt) {
                if (archiveSeq == null) return@map t
                changed = true
                return@map t.copy(tickArchiveSeq = archiveSeq)
            }
            if (archiveSeq != null) changed = true // Checkpoint is atomic with settlement/trailing.

            val hitStop = if (t.action == SignalAction.BUY) {
                price <= t.stopLoss + 1e-9
            } else {
                price >= t.stopLoss - 1e-9
            }
            val hitTarget = if (t.initialStopLoss != null) false else if (t.action == SignalAction.BUY) {
                price >= t.takeProfit - 1e-9
            } else {
                price <= t.takeProfit + 1e-9
            }

            if (!hitStop && !hitTarget) {
                val advanced = PaperTrailing.advance(t, price, price)
                if (advanced != t) changed = true
                return@map if (archiveSeq != null) advanced.copy(tickArchiveSeq = archiveSeq) else advanced
            }

            val exit = if (hitStop) t.stopLoss else t.takeProfit
            val pnlPerOz = if (t.action == SignalAction.BUY) {
                exit - t.entry
            } else {
                t.entry - exit
            }
            val pnl = kotlin.math.round(
                (PaperOrderRules.quotePnlToUsd(t.symbol, pnlPerOz * t.positionOz, exit) -
                    t.effectiveCommissionUsd - t.effectiveSpreadCostUsd) * 100.0) / 100.0
            settledPnlDelta += pnl
            changed = true
            val closed = t.copy(
                closedAt = observedAt,
                exitPrice = exit,
                exitReason = if (hitStop) "حد ضرر (قیمت لحظه‌ای)" else "حد سود (قیمت لحظه‌ای)",
                pnlUsd = pnl,
                tickArchiveSeq = archiveSeq ?: t.tickArchiveSeq,
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

    /** One journal commit per ordered batch (not one disk sync per WebSocket publication).
     * The sequence cursor, trailing stop and exit/balance are advanced together. A restart
     * replays from the last committed ID. Never apply a row after an ID gap or a different venue.
     */
    suspend fun settleBinanceBatch(symbol: String, openedAt: Long,
        events: List<BinanceSpotClient.Aggregate>): List<PaperTrade> = mutex.withLock {
        if (!loaded || events.isEmpty()) return@withLock emptyList()
        val original = _trades.value.firstOrNull { it.isOpen && it.symbol == symbol &&
            it.openedAt == openedAt && it.cryptoVenue == BinanceSpotClient.VENUE }
            ?: return@withLock emptyList()
        var trade = original
        var next = trade.binanceAggId + 1L
        var pnlDelta = 0.0
        val closed = mutableListOf<PaperTrade>()
        for (event in events) {
            if (event.id < next) continue // replay after a process restart
            if (trade.binanceAggId >= 0 && event.id != next)
                throw DataFeedException("شناسهٔ معاملهٔ Binance قطع شده؛ تسویه متوقف شد")
            if (event.tick.at < openedAt || !event.tick.price.isFinite() || event.tick.price <= 0 ||
                event.tick.at > System.currentTimeMillis() + 10_000L)
                throw DataFeedException("تیک Binance خارج از زمان پوزیشن یا نامعتبر است")
            val price = event.tick.price
            val stopHit = if (trade.action == SignalAction.BUY) price <= trade.stopLoss + 1e-9
                else price >= trade.stopLoss - 1e-9
            val targetHit = (trade.initialStopLoss == null || trade.cryptoEntryAtr != null) &&
                (if (trade.action == SignalAction.BUY) price >= trade.takeProfit - 1e-9
                    else price <= trade.takeProfit + 1e-9)
            val structureExit = trade.cryptoEntryAtr != null &&
                trade.cryptoExitPendingAt?.let { event.tick.at >= it } == true
            trade = if (stopHit || targetHit || structureExit) {
                // For the new strategy, a stop/target/structural intent is executed at
                // the OBSERVED same-venue price, not at an unobserved threshold. This
                // models gap/slippage honestly; old saved positions retain their policy.
                val exit = if (trade.cryptoEntryAtr != null) price
                    else if (stopHit) trade.stopLoss else trade.takeProfit
                val perUnit = if (trade.action == SignalAction.BUY) exit - trade.entry else trade.entry - exit
                val pnl = kotlin.math.round((PaperOrderRules.quotePnlToUsd(symbol,
                    perUnit * trade.positionOz, exit) - trade.effectiveCommissionUsd -
                    trade.effectiveSpreadCostUsd) * 100.0) / 100.0
                pnlDelta = pnl
                trade.copy(closedAt = event.tick.at, exitPrice = exit, binanceAggId = event.id,
                    exitReason = when {
                        stopHit -> "حد ضرر (Binance Spot)"
                        structureExit -> trade.cryptoExitReason ?: "خروج ساختاری (Binance Spot)"
                        else -> "حد سود (Binance Spot)"
                    }, pnlUsd = pnl)
            } else PaperTrailing.advance(trade, price, price).copy(binanceAggId = event.id)
            next = event.id + 1L
            if (!trade.isOpen) { closed.add(trade); break }
        }
        if (trade != original) {
            persist(_trades.value.map { if (it.id == original.id) trade else it })
            if (pnlDelta != 0.0) settingsStore?.adjustBalance(pnlDelta)
        }
        closed
    }

    suspend fun close(tradeId: String, price: Double, reason: String): PaperTrade = mutex.withLock {
        require(price.isFinite() && price > 0) { "قیمت خروج معتبر نیست" }
        val trade = _trades.value.singleOrNull { it.id == tradeId && it.isOpen }
            ?: throw IllegalArgumentException("پوزیشن باز در ژورنال پیدا نشد یا قبلاً بسته شده است")
        val pnlPerOz = if (trade.action == SignalAction.BUY) price - trade.entry else trade.entry - price
        val pnl = kotlin.math.round(
            (PaperOrderRules.quotePnlToUsd(trade.symbol, pnlPerOz * trade.positionOz, price) -
                trade.effectiveCommissionUsd - trade.effectiveSpreadCostUsd) * 100.0) / 100.0
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
