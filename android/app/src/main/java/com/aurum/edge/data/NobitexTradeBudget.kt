package com.aurum.edge.data

/** Process-wide rolling public-trades budget. The publisher allows 60 requests/minute.
 * Reserve at least 40 of 55 attempts for at most two open crypto positions (3s/pair),
 * and give snapshots / new-entry quotes no more than 15. This gate is deliberately
 * non-blocking: an exhausted budget is missing data, never a made-up price.
 */
internal class NobitexTradeBudget {
    private val attempts = ArrayDeque<Pair<Long, Boolean>>()

    @Synchronized
    fun tryAcquire(now: Long, openPosition: Boolean): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first().first >= 60_000L)
            attempts.removeFirst()
        if (attempts.size >= 55 || (!openPosition && attempts.count { !it.second } >= 15))
            return false
        attempts.addLast(now to openPosition)
        return true
    }
}
