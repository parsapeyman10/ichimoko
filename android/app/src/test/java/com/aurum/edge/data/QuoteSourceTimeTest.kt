package com.aurum.edge.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class QuoteSourceTimeTest {
    @Test fun `only parse actual supplied plausible source timestamps`() {
        assertNull(LiveQuoteFetcher.sourceTimeMillis(null))
        assertNull(LiveQuoteFetcher.sourceTimeMillis(JsonPrimitive("invalid")))
        assertEquals(1700000000000L, LiveQuoteFetcher.sourceTimeMillis(JsonPrimitive(1700000000L)))
        assertEquals(1700000000000L, LiveQuoteFetcher.sourceTimeMillis(JsonPrimitive(1700000000000L)))
        assertEquals(1700000000000L, LiveQuoteFetcher.sourceTimeMillis(JsonPrimitive("2023-11-14T22:13:20Z")))
    }
}
