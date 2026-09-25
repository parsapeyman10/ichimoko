package com.aurum.edge.engine

import com.aurum.edge.ui.TranslateLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/** Clicking sends only short publisher-supplied text to the official external translator. */
class TranslateLinkTest {
    @Test fun `fixed google host and encoded short text not an app credential`() {
        val link = TranslateLink.englishToPersian("Gold & USD", "Fed <update>")!!
        val uri = URI(link)
        assertEquals("https", uri.scheme)
        assertEquals("translate.google.com", uri.host)
        assertTrue(link.contains("sl=en&tl=fa"))
        assertFalse(link.contains("Fed <update>"))
        assertTrue(URLDecoder.decode(uri.rawQuery, "UTF-8").contains("Gold & USD\nFed <update>"))
        assertNull(TranslateLink.englishToPersian("  "))
        assertNull(TranslateLink.englishToPersian("hidden\u0000key"))
        assertTrue(TranslateLink.englishToPersian("x".repeat(1000))!!.length < 400)
    }
}
