package com.aurum.edge.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherBudgetTest {
    @Test fun `saturated publisher does not queue another publisher or a market class`() = runBlocking {
        val budget = PublisherBudget(mapOf("Yahoo" to 1, "Dukascopy" to 1))
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondAcquired = CompletableDeferred<Unit>()
        val first = async { budget.read("Yahoo") {
            firstEntered.complete(Unit)
            releaseFirst.await()
        } }
        firstEntered.await()
        val second = async { budget.read("Yahoo") { secondAcquired.complete(Unit) } }
        val other = async { budget.read("Dukascopy") { "ready" } }
        assertEquals("ready", other.await()) // no category-wide serial lane
        assertFalse(secondAcquired.isCompleted)
        releaseFirst.complete(Unit)
        first.await(); second.await()
        assertTrue(secondAcquired.isCompleted)
    }

    @Test fun `publisher failure releases its request slot`() = runBlocking {
        val budget = PublisherBudget(mapOf("Gate" to 1))
        assertTrue(runCatching { budget.read("Gate") { error("offline") } }.isFailure)
        assertEquals(42, budget.read("Gate") { 42 })
    }
}
