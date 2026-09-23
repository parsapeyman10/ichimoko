package com.aurum.edge.engine

import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.PaperNewsEvidence
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.data.PersianHeadline
import java.net.URI

/** Live-only, ninth condition: all eight technical confirmations PLUS verifiable model news.
 * Historical backtests have no point-in-time news archive and must NEVER claim 9/9.
 */
object NewsConfluence {
    const val TECHNICAL_COUNT = 8
    const val NEWS_LABEL = "۹ · خبر AI با شاهد ناشر"
    private const val MAX_REVIEW_AGE_MS = 180_000L
    private const val MAX_EVIDENCE_AGE_MS = 180 * 60_000L

    data class Alignment(val status: ConfluenceStatus, val detail: String)

    fun alignment(symbol: String, action: SignalAction, news: PersianNewsState,
                  now: Long = System.currentTimeMillis()): Alignment {
        fun unknown(reason: String) = Alignment(ConfluenceStatus.UNKNOWN, reason)
        if (symbol != "XAU/USD") return unknown("این مدل فقط برای XAU/USD ارزیابی می‌شود؛ نماد دیگر تأیید نشده")
        if (action == SignalAction.NO_TRADE) return unknown("ابتدا هشت شرط فنی باید جهت معتبر بدهند")
        if (news.loading || news.cached || news.error != null || news.sources.isEmpty() ||
            news.sources.any { it.state != "online" } || news.lastCheckedAt?.let {
                now - it in 0L..MAX_REVIEW_AGE_MS
            } != true) return unknown("فید/زمان ناشران ناقص، قدیمی یا در حال بررسی است")
        if (news.gate != NewsGate.CLEAR) {
            return Alignment(if (news.gate == NewsGate.BLOCKED) ConfluenceStatus.CONFLICT else ConfluenceStatus.UNKNOWN,
                "وتوی خبر پراثر یا وضعیت خبری نامشخص: ${news.reason}")
        }
        val ai = news.ai
        if (ai.status != "AVAILABLE" || ai.symbol != symbol || ai.model.isNullOrBlank() ||
            ai.model == "deterministic-fallback" || ai.checkedAt?.let {
                now - it in 0L..MAX_REVIEW_AGE_MS && it <= news.lastCheckedAt!!
            } != true || !ai.confidence.isFinite() || ai.confidence !in 80.0..100.0 ||
            ai.evidenceIds.isEmpty() || ai.evidenceIds.size > 3 || ai.evidenceIds.distinct().size != ai.evidenceIds.size) {
            return unknown(ai.reason.ifBlank { "مدل AI و شواهد معتبر در دسترس نیست" })
        }
        val evidence = ai.evidenceIds.map { id -> news.articles.singleOrNull { it.id == id } }
        if (evidence.any { item -> item == null || item.publishedAt?.let {
                    now - it in 0L..MAX_EVIDENCE_AGE_MS
                } != true || !trustedPublisher(item, news) }) {
            return unknown("شاهد خبر، تاریخ یا لینک ناشر معتبر نیست")
        }
        val result = when (ai.direction) {
            action.name -> ConfluenceStatus.CONFIRMED
            "BUY", "SELL" -> ConfluenceStatus.CONFLICT
            else -> ConfluenceStatus.UNKNOWN
        }
        val label = evidence.filterNotNull().joinToString("، ") { it.source }
        val detail = when (result) {
            ConfluenceStatus.CONFIRMED -> "هم‌جهت ${ai.direction} · ${ai.confidence.toInt()}٪ · $label · ${ai.model}"
            ConfluenceStatus.CONFLICT -> "تعارض: مدل ${ai.direction} در برابر ${action.name} · $label"
            ConfluenceStatus.UNKNOWN -> "تحلیل مدل جهت روشن ندارد؛ ورود تأیید نشده"
        }
        return Alignment(result, detail)
    }

    private fun trustedPublisher(item: PersianHeadline, news: PersianNewsState): Boolean {
        val link = item.link ?: return false
        val article = runCatching { URI(link) }.getOrNull() ?: return false
        if (article.scheme != "https" || article.rawUserInfo != null ||
            article.port !in listOf(-1, 443)) return false
        val host = article.host?.lowercase()?.removePrefix("www.") ?: return false
        return news.sources.any { source ->
            val feed = runCatching { URI(source.feed) }.getOrNull()
            source.name == item.source && source.state == "online" && feed?.scheme == "https" &&
                feed.host?.lowercase()?.removePrefix("www.") == host
        }
    }

    fun record(news: PersianNewsState): PaperNewsRecord? {
        val ai = news.ai
        val model = ai.model ?: return null
        val at = ai.checkedAt ?: return null
        val evidence = ai.evidenceIds.map { id ->
            val item = news.articles.singleOrNull { it.id == id } ?: return null
            PaperNewsEvidence(id, item.source, item.headline,
                item.link ?: return null, item.publishedAt ?: return null)
        }
        if (evidence.isEmpty()) return null
        return PaperNewsRecord(model, ai.direction, ai.confidence, at, evidence)
    }

    fun apply(raw: Signal?, symbol: String, news: PersianNewsState,
              now: Long = System.currentTimeMillis()): Signal? {
        if (raw == null) return null
        val core = raw.confluence.take(TECHNICAL_COUNT)
        val technicalOk = core.size == TECHNICAL_COUNT && core.all { it.ok }
        val match = alignment(symbol, raw.action, news, now)
        val item = ConfluenceItem(NEWS_LABEL, match.status == ConfluenceStatus.CONFIRMED,
            match.detail, match.status)
        val combined = raw.copy(confluence = core + item + raw.confluence.drop(TECHNICAL_COUNT))
        if (!raw.isActionable) return combined
        val blocker = when {
            !technicalOk -> "هشت شرط فنی هم‌زمان تأیید نشده‌اند (${core.count { it.ok }}/$TECHNICAL_COUNT)"
            match.status != ConfluenceStatus.CONFIRMED -> "شرط نهم (خبر AI): ${match.detail}"
            else -> return combined.copy(reasons = combined.reasons + "شرط نهم: تحلیل مدل و ناشر هم‌جهت و تازه")
        }
        // No entry levels are published as an actionable paper trade when the ninth condition
        // is missing. The technical score remains informational, NOT nine-way confidence.
        return combined.copy(action = SignalAction.NO_TRADE, entry = null, stopLoss = null,
            takeProfit = null, riskReward = null, blockers = combined.blockers + blocker)
    }
}
