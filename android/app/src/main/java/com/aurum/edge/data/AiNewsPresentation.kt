package com.aurum.edge.data

/** Only a real, recent model verdict citing this exact published headline may be displayed
 * as AI analysis. A keyword classifier, stale verdict or an unrelated gold card is never AI.
 */
object AiNewsPresentation {
    data class Summary(val symbol: String, val direction: String, val confidence: Double,
                       val reason: String, val model: String, val checkedAt: Long)

    fun forHeadline(headline: PublicHeadline, state: PersianNewsState,
                    now: Long = System.currentTimeMillis()): List<Summary> {
        if (state.loading || state.lastCheckedAt?.let { now - it in 0L..180_000L } != true ||
            state.sources.none { it.state == "online" && it.name == headline.feed.title }) return emptyList()
        val article = state.articles.singleOrNull {
            it.link == headline.url && it.publishedAt == headline.publishedAt
        } ?: return emptyList()
        return (state.aiBySymbol.values + state.ai).distinctBy { it.symbol }.mapNotNull { ai ->
            if (ai.status !in setOf("AVAILABLE", "ADVISORY") || ai.symbol.isBlank() ||
                ai.model.isNullOrBlank() || ai.model == "deterministic-fallback" ||
                ai.checkedAt?.let { now - it in 0L..180_000L } != true ||
                article.id !in ai.evidenceIds || ai.direction !in setOf("BUY", "SELL", "NEUTRAL") ||
                !ai.confidence.isFinite() || ai.confidence !in 0.0..100.0 ||
                ai.reason.isBlank()) return@mapNotNull null
            Summary(ai.symbol, ai.direction, ai.confidence, ai.reason.take(180), ai.model!!, ai.checkedAt!!)
        }.take(5)
    }
}
