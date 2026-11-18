package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * گارد «بعد از بسته شدن معامله، فوراً همان روند را دوباره باز نکن».
 *
 * سابقه: کاربر گزارش داد که سیستم پشتِ بسته شدن یک معامله، بلافاصله همان استراتژی را روی همان
 * نماد و همان روند دوباره باز می‌کرد و همه منفی می‌شد.
 */
class ReentryGuardTest {

    private fun trade(
        id: String = "t1",
        symbol: String = "BTC/USDT",
        strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        openedAt: Long = 1_000_000L,
        closedAt: Long? = 1_000_000L + 900_000L,
        pnl: Double? = -5.0,
        interval: Interval = Interval.M15,
    ) = PaperTrade(
        id = id,
        symbol = symbol,
        interval = interval,
        action = SignalAction.BUY,
        entry = 100.0,
        stopLoss = 99.0,
        takeProfit = 102.0,
        confidence = 70.0,
        riskReward = 2.0,
        openedAt = openedAt,
        closedAt = closedAt,
        exitPrice = if ((pnl ?: 0.0) < 0) 99.0 else 102.0,
        pnlUsd = pnl,
        strategyId = strategyId,
    )

    @Test
    fun cooldownIsEightBarsOfTheTradeTimeframe() {
        assertEquals(40L * 60_000L, ReentryGuard.cooldownMs(Interval.M5, 0))
        assertEquals(120L * 60_000L, ReentryGuard.cooldownMs(Interval.M15, 0))
        // H4 با ۸ کندل می‌شد ۳۲ ساعت؛ سقف ۸ ساعت اعمال می‌شود.
        assertEquals(ReentryGuard.MAX_COOLDOWN_MS, ReentryGuard.cooldownMs(Interval.H4, 0))
    }

    @Test
    fun aLossAndTwoLossesStretchTheWindow() {
        val base = ReentryGuard.cooldownMs(Interval.M15, 0)
        val afterLoss = ReentryGuard.cooldownMs(Interval.M15, 1)
        val afterTwoLosses = ReentryGuard.cooldownMs(Interval.M15, 2)
        assertTrue(afterLoss > base)
        assertTrue(afterTwoLosses > afterLoss)
        assertTrue(afterTwoLosses <= ReentryGuard.MAX_LOSS_COOLDOWN_MS)
    }

    @Test
    fun aFreshCloseBlocksAndExplainsWhyInPersian() {
        val closed = trade(pnl = -5.0)
        val decision = ReentryGuard.evaluate(closed, consecutiveLosses = 1, now = closed.closedAt!! + 60_000L)
        assertTrue(decision.blocked)
        assertTrue(decision.reasonFa.contains("خنک"))
        assertTrue(decision.remainingMs > 0L)
        assertEquals(ReentryGuard.cooldownMs(Interval.M15, 1), decision.cooldownMs)
    }

    @Test
    fun afterTheWindowTheSamePairIsFreeAgain() {
        val closed = trade(pnl = 5.0)
        val after = closed.closedAt!! + ReentryGuard.cooldownMs(closed.interval, 0) + 1_000L
        val decision = ReentryGuard.evaluate(closed, consecutiveLosses = 0, now = after)
        assertFalse(decision.blocked)
        assertNull(decision.reasonFa.takeIf { it.isNotBlank() })
    }

    @Test
    fun aWinUsesTheBaseWindowAndALossUsesTheLongerOne() {
        val now = 2_000_000L + 1_000L
        val win = trade(pnl = 12.0, closedAt = 2_000_000L)
        val loss = trade(pnl = -5.0, closedAt = 2_000_000L)
        val winDecision = ReentryGuard.evaluate(win, consecutiveLosses = 0, now = now)
        val lossDecision = ReentryGuard.evaluate(loss, consecutiveLosses = 1, now = now)
        assertTrue(lossDecision.cooldownMs > winDecision.cooldownMs)
    }

    @Test
    fun consecutiveLossesCountStopsAtTheFirstWin() {
        val list = listOf(
            trade(id = "a", closedAt = 5_000_000L, pnl = -5.0),
            trade(id = "b", closedAt = 4_000_000L, pnl = -5.0),
            trade(id = "c", closedAt = 3_000_000L, pnl = 10.0),
            trade(id = "d", closedAt = 2_000_000L, pnl = -5.0),
        )
        val symbol = V1Universe.normalize("BTC/USDT")
        val strategy = BitcoinStrategyModel.normalizeStrategyId(BitcoinStrategyModel.BASE_ICHIMOKU.id)
        assertEquals(2, ReentryGuard.consecutiveLosses(list, symbol, strategy))
    }

    @Test
    fun otherSymbolsAndStrategiesAreNotAffected() {
        val list = listOf(trade(id = "a", closedAt = 5_000_000L, pnl = -5.0))
        assertEquals(
            0,
            ReentryGuard.consecutiveLosses(
                list,
                V1Universe.normalize("ETH/USDT"),
                BitcoinStrategyModel.normalizeStrategyId(BitcoinStrategyModel.BASE_ICHIMOKU.id),
            ),
        )
        assertEquals(
            0,
            ReentryGuard.consecutiveLosses(
                list,
                V1Universe.normalize("BTC/USDT"),
                BitcoinStrategyModel.normalizeStrategyId(BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT.id),
            ),
        )
    }

    @Test
    fun anOpenOrBacktestTradeNeverStartsACooldown() {
        assertEquals(null, ReentryGuard.evaluate(null, 0, 10_000_000L).reasonFa.takeIf { it.isNotBlank() })
        val open = trade(pnl = null, closedAt = null)
        assertFalse(ReentryGuard.evaluate(open, 0, 10_000_000L).blocked)
        val backtest = trade(pnl = -5.0).copy(origin = PaperTrade.ORIGIN_BACKTEST)
        assertEquals(
            0,
            ReentryGuard.consecutiveLosses(
                listOf(backtest),
                V1Universe.normalize("BTC/USDT"),
                BitcoinStrategyModel.normalizeStrategyId(BitcoinStrategyModel.BASE_ICHIMOKU.id),
            ),
        )
    }
}
