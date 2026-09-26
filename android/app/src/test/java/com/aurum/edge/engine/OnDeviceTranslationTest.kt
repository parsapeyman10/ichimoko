package com.aurum.edge.engine

import com.aurum.edge.ui.translationSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** No browser URL or pretend Persian result: translation is performed by ML Kit on the device. */
class OnDeviceTranslationTest {
    @Test fun `only bounded clean publisher text is passed to translation`() {
        assertEquals("Gold and USD\nFed update", translationSnippet(" Gold and USD ", " Fed update "))
        assertEquals("Core CPI", translationSnippet(" Core CPI "))
        assertNull(translationSnippet("  "))
        assertNull(translationSnippet("hidden\u0000key"))
        assertNull(translationSnippet("x".repeat(221)))
        assertEquals("Title", translationSnippet("Title", "y".repeat(261)))
    }
}
