package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide gate for the same Nobitex venue and one open-position generation.
 * WebSocket publications are the primary ordered path while the user-enabled foreground
 * service runs. REST is an overlapping-page bootstrap/fallback, never a different exchange.
 * Neither a socket nor a finite REST page is proof of all trades across Android process death.
 */
class OpenTradeTickMonitor(
    private val journal: JournalStore,
    private val spot: SpotFallbackClient = SpotFallbackClient(),
    private val settings: SettingsStore? = null,
    private val binance: BinanceSpotClient = BinanceSpotClient(),
) {
    private val archive = NobitexTickArchiveClient()
    enum class Feed { CONNECTING, STREAM, STREAM_RECOVERABLE, ARCHIVE, RECOVERING, REST_FALLBACK, BINANCE_STREAM }
    private data class Session(
        val openedAt: Long,
        val page: TradePageCursor = TradePageCursor(),
        val stream: TradeStreamCursor = TradeStreamCursor(openedAt),
        val mutex: Mutex = Mutex(),
        val watcher: AtomicBoolean = AtomicBoolean(false),
        @Volatile var connected: Boolean = false,
        @Volatile var latest: PriceTick? = null,
        var lastReceiptAt: Long = 0L,
        var lastRequestAt: Long = 0L,
        var disconnectedAt: Long = 0L,
        var binanceBootstrapped: Boolean = false,
    )
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val _feeds = MutableStateFlow<Map<String, Feed>>(emptyMap())
    val feeds: StateFlow<Map<String, Feed>> = _feeds.asStateFlow()

    private fun setFeed(symbol: String, session: Session, feed: Feed?) {
        if (sessions[symbol] !== session) return
        _feeds.update { old -> old.toMutableMap().apply {
            if (feed == null) remove(symbol) else put(symbol, feed)
        } }
    }

    private fun session(symbol: String, openedAt: Long): Session = sessions.compute(symbol) { _, old ->
        old?.takeIf { it.openedAt == openedAt } ?: Session(openedAt)
    }!!

    private fun stillOpen(symbol: String, session: Session): Boolean =
        sessions[symbol] === session && journal.trades.value.any {
            it.isOpen && it.symbol == symbol && it.openedAt == session.openedAt
        }

    private fun forget(symbol: String, session: Session) {
        if (sessions.remove(symbol, session)) setFeedRemoved(symbol)
    }

    private fun setFeedRemoved(symbol: String) {
        _feeds.update { it - symbol }
    }

    /** Called by the visible screen (or as REST bootstrap/fallback by the position worker).
     * While the stream is healthy, the screen sees only its LAST FRESH received trade and
     * never starts a parallel REST replay that could reorder a trailing stop.
     */
    suspend fun poll(symbol: String, openedAt: Long): PriceTick? = when {
        binancePosition(symbol, openedAt) -> pollBinance(symbol, openedAt)
        archivePosition(symbol, openedAt) -> pollArchive(symbol, openedAt).first
        else -> pollPage(symbol, openedAt, bootstrap = false)
    }

    private fun binancePosition(symbol: String, openedAt: Long): Boolean =
        journal.trades.value.any { it.isOpen && it.symbol == symbol &&
            it.openedAt == openedAt && it.cryptoVenue == BinanceSpotClient.VENUE }

    private fun archivePosition(symbol: String, openedAt: Long): Boolean =
        journal.trades.value.any { it.isOpen && it.symbol == symbol &&
            it.openedAt == openedAt && it.usesTickArchive }

    private suspend fun pollArchive(symbol: String, openedAt: Long): Pair<PriceTick?, Boolean> {
        val session = session(symbol, openedAt)
        return session.mutex.withLock {
            if (!stillOpen(symbol, session)) {
                forget(symbol, session)
                return@withLock null to false
            }
            val config = settings?.tickArchiveConfig()
                ?: throw DataFeedException("نشانی/کلید بایگانی معتبر نیست؛ پوزیشن در فید دیگری تسویه نمی‌شود")
            val after = journal.trades.value.firstOrNull {
                it.isOpen && it.symbol == symbol && it.openedAt == openedAt
            }?.tickArchiveSeq ?: return@withLock null to false
            val page = archive.fetch(symbol, openedAt, after, config)
            for (gap in page.gaps) if (gap.at >= openedAt)
                journal.noteTickCoverageGap(symbol, gap.at, openedAt)
            for (row in page.rows) {
                // Older executions are replayed IN ORDER; freshness gate applies only
                // to a live display quote, not to durable, authenticated history.
                if (journal.settleTick(symbol, row.price, row.at,
                        expectedOpenedAt = openedAt, archiveSeq = row.seq).isNotEmpty()) {
                    forget(symbol, session)
                    break
                }
                if (row.at >= openedAt) {
                    val latest = session.latest
                    if (latest == null || row.at >= latest.at) session.latest = PriceTick(row.price, row.at)
                }
            }
            setFeed(symbol, session, if (page.live) Feed.ARCHIVE else Feed.RECOVERING)
            if (page.live) session.lastReceiptAt = System.currentTimeMillis()
            else if (System.currentTimeMillis() - (session.lastReceiptAt.takeIf { it > 0 } ?: openedAt) > 45_000L)
                journal.noteTickCoverageGap(symbol, System.currentTimeMillis(), openedAt)
            session.latest?.takeIf { TradeQuotePolicy.accepts(it) } to (page.rows.size == 500 || page.next < page.head)
        }
    }

    private suspend fun watchArchive(symbol: String, openedAt: Long) {
        val session = session(symbol, openedAt)
        if (!session.watcher.compareAndSet(false, true)) return
        try {
            setFeed(symbol, session, Feed.CONNECTING)
            while (currentCoroutineContext().isActive && stillOpen(symbol, session)) {
                val backlog = try {
                    pollArchive(symbol, openedAt).second
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    setFeed(symbol, session, Feed.RECOVERING)
                    val now = System.currentTimeMillis()
                    if (now - (session.lastReceiptAt.takeIf { it > 0 } ?: openedAt) > 45_000L)
                        journal.noteTickCoverageGap(symbol, now, openedAt)
                    false
                }
                kotlinx.coroutines.delay(if (backlog) 25L else 650L)
            }
        } finally {
            session.watcher.set(false)
            if (sessions[symbol] === session) setFeedRemoved(symbol)
        }
    }

    private suspend fun pollPage(symbol: String, openedAt: Long, bootstrap: Boolean): PriceTick? {
        val session = session(symbol, openedAt)
        return session.mutex.withLock {
            if (!stillOpen(symbol, session)) {
                forget(symbol, session)
                return@withLock null
            }
            val now = System.currentTimeMillis()
            if (session.watcher.get() && !bootstrap) {
                if (session.connected || now - session.disconnectedAt < 10_000L)
                    return@withLock session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
                setFeed(symbol, session, Feed.REST_FALLBACK)
                // REST after a lost socket is only a best-effort fallback: it cannot prove
                // the missing segment even if the pages themselves overlap.
                journal.noteTickCoverageGap(symbol, now, openedAt)
            }
            if (now - session.lastRequestAt in 0L until 3_000L)
                return@withLock session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
            session.lastRequestAt = now
            val page = try {
                spot.fetchCryptoTrades(symbol, openedAt)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (now - (session.lastReceiptAt.takeIf { it > 0 } ?: openedAt) > 45_000L)
                    journal.noteTickCoverageGap(symbol, now, openedAt)
                return@withLock null
            }
            val update = session.page.advance(page, openedAt)
            session.lastReceiptAt = now
            if (update.gap) journal.noteTickCoverageGap(symbol, now, openedAt)
            // No fresh publication is ever replaced with an older page's last row.
            for (trade in update.newlySeen) {
                // REST may contain publications already settled before the socket dropped.
                // Its rows have no IDs: never replay a past/same-ms page over a newer stop.
                if (!session.stream.rest(trade.at)) {
                    journal.noteTickCoverageGap(symbol, now, openedAt)
                    continue
                }
                if (journal.settleTick(symbol, trade.price, trade.at, expectedOpenedAt = openedAt).isNotEmpty()) {
                    forget(symbol, session)
                    break
                }
            }
            val tick = page.last().tick
            if (session.latest == null || tick.at > session.latest!!.at) session.latest = tick
            session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
        }
    }

    /** Binance aggregate IDs are shared by REST and WebSocket. REST fromId is INCLUSIVE:
     * restart from the last atomically persisted ID+1, never jump to the latest quote.
     * Only a complete ordered page can be committed; long backlogs use bounded pages/turn.
     */
    private suspend fun pollBinance(symbol: String, openedAt: Long, bootstrap: Boolean = false): PriceTick? {
        val session = session(symbol, openedAt)
        return session.mutex.withLock {
            if (!stillOpen(symbol, session)) { forget(symbol, session); return@withLock null }
            val now = System.currentTimeMillis()
            if (session.connected && !bootstrap)
                return@withLock session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
            if (!bootstrap && now - session.lastRequestAt in 0 until 1_000)
                return@withLock session.latest?.takeIf { TradeQuotePolicy.accepts(it, now) }
            session.lastRequestAt = now
            try {
                repeat(4) {
                    val cursor = journal.trades.value.firstOrNull { it.isOpen && it.symbol == symbol &&
                        it.openedAt == openedAt && it.cryptoVenue == BinanceSpotClient.VENUE }
                        ?: return@withLock null
                    val rows = if (cursor.binanceAggId >= 0)
                        binance.fetchAggregates(symbol, fromId = cursor.binanceAggId + 1L)
                    else binance.fetchAggregates(symbol, startTime = openedAt)
                    if (rows.isNotEmpty()) {
                        val closed = journal.settleBinanceBatch(symbol, openedAt, rows)
                        session.latest = rows.last().tick
                        if (closed.isNotEmpty()) { forget(symbol, session); return@withLock null }
                    }
                    if (rows.size < 1000) {
                        session.binanceBootstrapped = true
                        session.lastReceiptAt = System.currentTimeMillis()
                        return@withLock session.latest?.takeIf { TradeQuotePolicy.accepts(it) }
                    }
                }
                // Four full pages can still be far behind real time. Do not trust a newer
                // stream event until the remaining IDs have been replayed in order.
                setFeed(symbol, session, Feed.RECOVERING)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                setFeed(symbol, session, Feed.RECOVERING)
                journal.noteTickCoverageGap(symbol, System.currentTimeMillis(), openedAt)
            }
            session.latest?.takeIf { TradeQuotePolicy.accepts(it) }
        }
    }

    private suspend fun watchBinance(symbol: String, openedAt: Long) {
        val session = session(symbol, openedAt)
        if (!session.watcher.compareAndSet(false, true)) return
        val events = Channel<BinanceAggregateStream.Event>(4096)
        val overflow = AtomicBoolean(false)
        var socket: java.io.Closeable? = null
        try {
            setFeed(symbol, session, Feed.CONNECTING)
            while (currentCoroutineContext().isActive && stillOpen(symbol, session)) {
                // Finish older pages BEFORE opening a new stream; the first stream ID
                // forces an additional fromId catch-up for the REST/WS handoff window.
                pollBinance(symbol, openedAt, bootstrap = true)
                if (!stillOpen(symbol, session)) break
                if (!session.binanceBootstrapped) { kotlinx.coroutines.delay(1_000L); continue }
                try {
                    socket = BinanceAggregateStream().open(symbol) { event ->
                        if (!events.trySend(event).isSuccess) overflow.set(true)
                    }
                    val batch = ArrayList<BinanceSpotClient.Aggregate>(256)
                    var connected = false
                    var lastFlush = System.currentTimeMillis()
                    while (currentCoroutineContext().isActive && stillOpen(symbol, session)) {
                        val event = withTimeoutOrNull(250L) { events.receive() }
                        val now = System.currentTimeMillis()
                        if (overflow.getAndSet(false)) {
                            journal.noteTickCoverageGap(symbol, now, openedAt)
                            setFeed(symbol, session, Feed.RECOVERING)
                            break // REST fromId will replay what the bounded queue dropped.
                        }
                        when (event) {
                            BinanceAggregateStream.Event.Connected -> {
                                connected = true
                                session.connected = true
                                setFeed(symbol, session, Feed.BINANCE_STREAM)
                            }
                            BinanceAggregateStream.Event.Disconnected -> break
                            BinanceAggregateStream.Event.Invalid -> {
                                journal.noteTickCoverageGap(symbol, now, openedAt)
                                break
                            }
                            is BinanceAggregateStream.Event.Trade -> {
                                val row = event.trade
                                if (row.tick.at < openedAt) continue
                                val previous = batch.lastOrNull()?.id ?: journal.trades.value.firstOrNull {
                                    it.isOpen && it.symbol == symbol && it.openedAt == openedAt
                                }?.binanceAggId ?: break
                                if (previous < 0 || row.id > previous + 1L) {
                                    if (batch.isNotEmpty()) {
                                        journal.settleBinanceBatch(symbol, openedAt, batch)
                                        batch.clear()
                                    }
                                    // Missing IDs must be replayed before this publication.
                                    // Never let the socket skip a stop while REST is unavailable.
                                    session.connected = false
                                    pollBinance(symbol, openedAt, bootstrap = true)
                                    val caught = journal.trades.value.firstOrNull { it.isOpen &&
                                        it.symbol == symbol && it.openedAt == openedAt }?.binanceAggId ?: break
                                    if (row.id > caught + 1L) {
                                        journal.noteTickCoverageGap(symbol, now, openedAt)
                                        break
                                    }
                                    session.connected = true
                                    if (row.id <= caught) continue
                                }
                                if (row.id <= previous) continue
                                batch.add(row)
                                session.latest = row.tick
                            }
                            null -> Unit
                        }
                        if (batch.isNotEmpty() && (batch.size >= 128 || now - lastFlush >= 250L)) {
                            journal.settleBinanceBatch(symbol, openedAt, batch)
                            batch.clear()
                            lastFlush = now
                        }
                        if (!connected && now - session.lastRequestAt > 10_000L) break
                    }
                    // A disconnected/overloaded stream's already-received contiguous
                    // rows are still checked before REST fromId takes over.
                    if (batch.isNotEmpty() && stillOpen(symbol, session))
                        journal.settleBinanceBatch(symbol, openedAt, batch)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    journal.noteTickCoverageGap(symbol, System.currentTimeMillis(), openedAt)
                } finally {
                    session.connected = false
                    setFeed(symbol, session, Feed.REST_FALLBACK)
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { socket?.close() } }
                    socket = null
                    while (events.tryReceive().isSuccess) { /* stale socket callbacks */ }
                }
                kotlinx.coroutines.delay(1_000L)
            }
        } finally {
            session.watcher.set(false)
            if (sessions[symbol] === session) setFeedRemoved(symbol)
            events.close()
        }
    }

    /** Own one cancellable stream per open crypto position, separate from scan/AI/news.
     * Centrifugo's SDK reconnects and requests recovery only if the server advertised it.
     * A bounded callback queue makes overload observable rather than silently dropping ticks.
     */
    suspend fun watch(symbol: String, openedAt: Long) {
        if (binancePosition(symbol, openedAt)) return watchBinance(symbol, openedAt)
        if (archivePosition(symbol, openedAt)) return watchArchive(symbol, openedAt)
        val session = session(symbol, openedAt)
        if (!session.watcher.compareAndSet(false, true)) return
        val events = Channel<NobitexPublicTradeStream.Event>(2_048)
        val overflow = AtomicBoolean(false)
        var socket: java.io.Closeable? = null
        try {
            setFeed(symbol, session, Feed.CONNECTING)
            // Bootstrap REST while the socket is not yet subscribed. The gap between
            // these sources is explicitly audited on first subscribe, not fabricated.
            pollPage(symbol, openedAt, bootstrap = true)
            if (!stillOpen(symbol, session)) return
            session.disconnectedAt = System.currentTimeMillis()
            socket = try {
                NobitexPublicTradeStream().open(symbol) { event ->
                    if (!events.trySend(event).isSuccess) overflow.set(true)
                }
            } catch (_: Exception) {
                // A blocked WebSocket does not disable same-venue REST fallback.
                null
            }
            var lastSocketAttempt = System.currentTimeMillis()
            while (currentCoroutineContext().isActive && stillOpen(symbol, session)) {
                val event = withTimeoutOrNull(3_000L) { events.receive() }
                val now = System.currentTimeMillis()
                if (overflow.getAndSet(false)) journal.noteTickCoverageGap(symbol, now, openedAt)
                when (event) {
                    is NobitexPublicTradeStream.Event.Subscribed -> session.mutex.withLock {
                        if (!stillOpen(symbol, session)) return@withLock
                        if (session.stream.subscribed(event))
                            journal.noteTickCoverageGap(symbol, now, openedAt)
                        session.connected = true
                        session.disconnectedAt = 0L
                        setFeed(symbol, session, if (event.recoverable) Feed.STREAM_RECOVERABLE else Feed.STREAM)
                    }
                    NobitexPublicTradeStream.Event.Subscribing,
                    NobitexPublicTradeStream.Event.Unsubscribed -> {
                        session.connected = false
                        if (session.disconnectedAt == 0L) session.disconnectedAt = now
                        setFeed(symbol, session, Feed.RECOVERING)
                    }
                    is NobitexPublicTradeStream.Event.Publication -> session.mutex.withLock {
                        if (!stillOpen(symbol, session)) return@withLock
                        val trade = try { NobitexStreamTrade.parse(event.data, now) }
                        catch (_: Exception) {
                            journal.noteTickCoverageGap(symbol, now, openedAt)
                            return@withLock
                        }
                        when (session.stream.publication(trade.at, event.offset)) {
                            TradeStreamCursor.Verdict.SKIP -> return@withLock
                            TradeStreamCursor.Verdict.GAP -> {
                                journal.noteTickCoverageGap(symbol, now, openedAt)
                                return@withLock
                            }
                            TradeStreamCursor.Verdict.GAP_APPLY ->
                                journal.noteTickCoverageGap(symbol, now, openedAt)
                            TradeStreamCursor.Verdict.APPLY -> Unit
                        }
                        session.lastReceiptAt = now
                        session.latest = trade.tick
                        if (journal.settleTick(symbol, trade.price, trade.at, expectedOpenedAt = openedAt).isNotEmpty())
                            forget(symbol, session)
                    }
                    NobitexPublicTradeStream.Event.Invalid -> journal.noteTickCoverageGap(symbol, now, openedAt)
                    null -> Unit
                }
                if (!session.connected) {
                    if (socket == null && now - lastSocketAttempt >= 30_000L) {
                        lastSocketAttempt = now
                        socket = runCatching {
                            NobitexPublicTradeStream().open(symbol) { event ->
                                if (!events.trySend(event).isSuccess) overflow.set(true)
                            }
                        }.getOrNull()
                    }
                    if (session.disconnectedAt == 0L) session.disconnectedAt = now
                    if (now - session.disconnectedAt > 45_000L)
                        journal.noteTickCoverageGap(symbol, now, openedAt)
                    // If WebSocket cannot connect, keep processing the same-venue REST pages.
                    if (now - session.disconnectedAt >= 10_000L) poll(symbol, openedAt)
                }
            }
        } finally {
            session.watcher.set(false)
            session.connected = false
            events.close()
            withContext(NonCancellable + Dispatchers.IO) { runCatching { socket?.close() } }
            if (sessions[symbol] === session) setFeedRemoved(symbol)
        }
    }
}
