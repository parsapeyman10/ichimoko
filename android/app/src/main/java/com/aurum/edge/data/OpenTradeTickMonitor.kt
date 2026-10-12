package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide OPEN crypto position monitor. Unlike the quote-only screen feed, replay every
 * executed trade in each overlapping public page, in exchange order. No REST endpoint with
 * missing trade IDs can promise zero loss across a page rollover, app death or network gap.
 */
class OpenTradeTickMonitor(
    private val journal: JournalStore,
    private val spot: SpotFallbackClient = SpotFallbackClient(),
) {
    private data class Session(val openedAt: Long, val cursor: TradePageCursor = TradePageCursor(),
                               val mutex: Mutex = Mutex(), var lastReceiptAt: Long = 0L,
                               var lastRequestAt: Long = 0L)
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()

    /** Null when the current batch is unavailable; never promote a stale quote. */
    suspend fun poll(symbol: String, openedAt: Long): PriceTick? {
        val session = sessions.compute(symbol) { _, old ->
            old?.takeIf { it.openedAt == openedAt } ?: Session(openedAt)
        }!!
        // A blocked request for another pair no longer holds this pair's cursor/settlement.
        return session.mutex.withLock {
            if (sessions[symbol] !== session) return@withLock null
            if (journal.trades.value.none { it.isOpen && it.symbol == symbol && it.openedAt == openedAt }) {
                sessions.remove(symbol, session)
                return@withLock null
            }
            val now = System.currentTimeMillis()
            // This worker has the reserved public-trades lane. A 3s minimum is not a
            // promise of every tick: pages must overlap or coverage is marked unknown.
            if (now - session.lastRequestAt in 0L until 3_000L) return@withLock null
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
            val update = session.cursor.advance(page, openedAt)
            session.lastReceiptAt = now
            if (update.gap) journal.noteTickCoverageGap(symbol, now, openedAt)
            // If a page did not overlap the last one, unknown trades are NOT replayed as
            // if the page were complete. Later overlapping pages resume known-only ticks.
            for (trade in update.newlySeen) {
                if (journal.settleTick(symbol, trade.price, trade.at, expectedOpenedAt = openedAt).isNotEmpty()) {
                    sessions.remove(symbol, session)
                    break
                }
            }
            page.last().tick
        }
    }
}
