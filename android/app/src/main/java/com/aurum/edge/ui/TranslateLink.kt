package com.aurum.edge.ui

import java.net.URLEncoder

/** Explicit external Google Translate link. No APK API key, scraping, or fake translated text. */
object TranslateLink {
    fun englishToPersian(title: String, excerpt: String = ""): String? {
        val safeTitle = title.trim().take(220).takeIf { it.isNotBlank() && it.none { ch -> Character.isISOControl(ch) } }
            ?: return null
        val safeExcerpt = excerpt.trim().take(260).takeIf { it.none { ch -> Character.isISOControl(ch) } }.orEmpty()
        val text = if (safeExcerpt.isBlank()) safeTitle else "$safeTitle\n$safeExcerpt"
        return "https://translate.google.com/?sl=en&tl=fa&text=" +
            URLEncoder.encode(text, "UTF-8") + "&op=translate"
    }
}
