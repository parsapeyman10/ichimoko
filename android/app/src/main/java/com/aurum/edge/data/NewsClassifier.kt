package com.aurum.edge.data

enum class NewsImportance { HIGH, MEDIUM, LOW }
enum class NewsDirection { BULLISH, BEARISH, NEUTRAL }

data class NewsClassification(val importance: NewsImportance, val direction: NewsDirection)

/**
 * Rule-based keyword scan of an ALREADY-FETCHED real headline (title+excerpt) — never a model,
 * never a fabricated headline, and NEVER the server's AI news gate ([com.aurum.edge] "شرط نهم"),
 * which stays authoritative and fails closed to UNKNOWN on its own. This is a fast, transparent,
 * disclosed heuristic to help a human scan a headline list; it must not be read as a trading
 * signal or an AI verdict, exactly like the backend's own rule: "no keyword fallback may call
 * itself AI". Same dictionary is reused across the four workspaces (forex/gold, both crypto
 * screens, Iran bourse) since financial direction words overlap heavily across languages/markets;
 * "high" importance additionally flags macro/regulatory/corporate-action terms per domain.
 */
object NewsClassifier {
    private val highImportance = listOf(
        "fed", "fomc", "rate decision", "interest rate", "cpi", "inflation", "nonfarm", "payrolls",
        "war", "sanction", "opec", "sec ", "etf", "hack", "exploit", "collapse", "bankrupt",
        "default", "delisting", "halt", "emergency", "recession", "tariff",
        "فدرال", "نرخ بهره", "تورم", "تحریم", "جنگ", "کدال", "مجمع عمومی", "افزایش سرمایه",
        "توقف نماد", "ورشکست", "بحران", "تعرفه",
    )
    private val bullishWords = listOf(
        "surge", "rally", "soar", "jump", "gain", "record high", "all-time high", "beats",
        "upgrade", "approval", "approves", "rate cut", "stimulus", "breakout", "recovery",
        "رشد", "صعود", "افزایش", "رکورد", "رونق", "سود", "تقاضا", "جهش",
    )
    private val bearishWords = listOf(
        "plunge", "crash", "selloff", "sell-off", "slump", "drop", "falls", "downgrade", "ban",
        "hack", "exploit", "bankrupt", "recession", "default", "misses", "rate hike", "outage",
        "سقوط", "کاهش", "افت", "ریزش", "بحران", "توقف", "زیان", "ضرر",
    )

    fun classify(title: String, excerpt: String): NewsClassification {
        val text = "$title $excerpt".lowercase()
        val bullHits = bullishWords.count { text.contains(it) }
        val bearHits = bearishWords.count { text.contains(it) }
        val isHigh = highImportance.any { text.contains(it) }
        val importance = when {
            isHigh -> NewsImportance.HIGH
            bullHits > 0 || bearHits > 0 -> NewsImportance.MEDIUM
            else -> NewsImportance.LOW
        }
        val direction = when {
            bullHits > bearHits -> NewsDirection.BULLISH
            bearHits > bullHits -> NewsDirection.BEARISH
            else -> NewsDirection.NEUTRAL
        }
        return NewsClassification(importance, direction)
    }
}
