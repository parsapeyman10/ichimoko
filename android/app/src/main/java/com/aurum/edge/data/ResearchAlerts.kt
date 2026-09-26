package com.aurum.edge.data

/** User-visible NEWS notifications, entirely separate from paper candidate/trade alerts. */
data class ResearchAlert(val evidenceId: String, val title: String, val text: String)

object ResearchAlerts {
    fun forex(calendar: ForexCalendarState, now: Long): List<ResearchAlert> {
        if (!calendar.online(now)) return emptyList()
        return calendar.events.asSequence().filter { it.country == "USD" && it.impact == "High" }
            .filter { it.at in (now - 15 * 60_000L)..(now + 15 * 60_000L) }
            .sortedBy { kotlin.math.abs(it.at - now) }.take(3).mapNotNull { event ->
                val note = NewsResearch.gold(event, calendar, now)
                val phase = when (note.state) {
                    ResearchState.SCHEDULED -> "upcoming"
                    ResearchState.AWAITING_RESULT -> if (now - event.at <= 3 * 60_000L) "pending" else return@mapNotNull null
                    ResearchState.PUBLISHED -> "published_${event.actual}"
                    else -> return@mapNotNull null
                }
                ResearchAlert("ff_${event.country}_${event.at}_${event.title}_$phase",
                    "خبر پژوهشی Forex Factory · نه معامله",
                    "${event.title}: ${note.title}. ${note.detail}")
            }.toList()
    }

    fun latestHeadline(news: PublicWebNewsState, space: ResearchSpace, now: Long): ResearchAlert? {
        if (space == ResearchSpace.FOREX || news.loading) return null // FF events are the Forex priority
        val item = news.headlines.asSequence().filter {
            now - it.publishedAt in 0L..(10 * 60_000L)
        }.firstOrNull { NewsResearch.headline(it, news, space, now).state == ResearchState.CONTEXT }
            ?: return null
        val note = NewsResearch.headline(item, news, space, now)
        val title = when (space) {
            ResearchSpace.CRYPTO -> "خبر جهانی CoinDesk · نه سیگنال"
            ResearchSpace.NOBITEX -> "خبر جهانی · نه اطلاعیهٔ نوبیتکس"
            ResearchSpace.IRAN_STOCKS -> "خبر اقتصاد عمومی · نه کدال"
            ResearchSpace.FOREX -> return null
        }
        return ResearchAlert("${space.name}_${item.feed.id}_${item.publishedAt}_${item.url}", title,
            "${item.title}. ${note.detail}")
    }
}
