package com.aurum.edge.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningContinuityTest {
    @Test fun onlyTheSameNonEmptySignerSetCanUpdate() {
        assertTrue(sameCurrentSigners(setOf("403"), setOf("403")))
        assertFalse(sameCurrentSigners(setOf("403"), setOf("old")))
        assertFalse(sameCurrentSigners(setOf("403"), setOf("403", "other")))
        assertFalse(sameCurrentSigners(emptySet(), setOf("403")))
        assertFalse(sameCurrentSigners(setOf("403"), emptySet()))
    }
}
