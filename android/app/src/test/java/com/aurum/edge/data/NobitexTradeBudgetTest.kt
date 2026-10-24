package com.aurum.edge.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NobitexTradeBudgetTest {
    @Test fun `low priority quotes cannot spend open position reservation`() {
        val budget = NobitexTradeBudget()
        val now = 100_000L
        repeat(15) { assertTrue(budget.tryAcquire(now + it, openPosition = false)) }
        assertFalse(budget.tryAcquire(now + 20, openPosition = false))
        repeat(40) { assertTrue(budget.tryAcquire(now + 100 + it, openPosition = true)) }
        assertFalse(budget.tryAcquire(now + 200, openPosition = true))
        assertFalse(budget.tryAcquire(now + 200, openPosition = false))
        assertTrue(budget.tryAcquire(now + 60_201L, openPosition = false))
        assertTrue(budget.tryAcquire(now + 60_201L, openPosition = true))
    }

    @Test fun `open positions can use available capacity without waiting for low priority`() {
        val budget = NobitexTradeBudget()
        repeat(55) { assertTrue(budget.tryAcquire(1_000L, openPosition = true)) }
        assertFalse(budget.tryAcquire(1_000L, openPosition = true))
    }
}
