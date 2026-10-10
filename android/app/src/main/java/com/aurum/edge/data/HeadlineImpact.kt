package com.aurum.edge.data

/** A literal reading of the headline, not a forecast or a paper-trading/AI input. */
object HeadlineImpact {
    private data class Subject(val label: String, val words: List<String>)
    private val subjects = listOf(
        Subject("طلا (XAU/USD)", listOf("gold", "xau", "طلا")),
        Subject("نقره (XAG/USD)", listOf("silver", "xag", "نقره")),
        Subject("نفت WTI (USOIL)", listOf("wti", "us oil", "west texas")),
        Subject("نفت برنت (UKOIL)", listOf("brent")),
        Subject("نفت (نوع قرارداد نامشخص)", listOf("oil", "crude", "نفت")),
        Subject("دلار آمریکا (USD)", listOf("dollar", "usd", "دلار آمریکا")),
        Subject("یورو (EUR)", listOf("euro", "eur", "یورو")),
        Subject("بیت‌کوین (BTC/USDT)", listOf("bitcoin", "btc", "بیت کوین", "بیت‌کوین")),
        Subject("اتریوم (ETH/USDT)", listOf("ethereum", "ether", "eth", "اتریوم")),
        Subject("سولانا (SOL/USDT)", listOf("solana", "sol", "سولانا")),
        Subject("ریپل (XRP/USDT)", listOf("xrp", "ریپل")),
        Subject("سهام اپل (AAPL)", listOf("aapl", "apple stock", "سهام اپل")),
        Subject("سهام انویدیا (NVDA)", listOf("nvda", "nvidia stock", "سهام انویدیا")),
        Subject("سهام تسلا (TSLA)", listOf("tsla", "tesla stock", "سهام تسلا")),
    )
    private val rising = Regex("(?i)\\b(?:rises?|gains?|surges?|jumps?|climbs?|rall(?:y|ies))\\b|صعود|رشد|جهش")
    private val falling = Regex("(?i)\\b(?:falls?|drops?|slides?|slumps?|plunges?|crashes?)\\b|سقوط|ریزش|افت")
    private val macro = Regex("(?i)\\b(?:fed|fomc|cpi|inflation|payrolls|interest rates?)\\b|فدرال|تورم|نرخ بهره")
    private val hypothetical = Regex("(?i)\\b(?:may|might|could|would|if|likely|forecast|expected|predict(?:ed|ion)?)\\b|ممکن|شاید|احتمال|پیش‌بینی|انتظار|خواهد")
    private val companyMetric = Regex("(?i)\\b(?:sales|revenue|earnings|profits?|shipments|demand)\\b|فروش|درآمد|سود شرکت")

    fun explain(title: String): List<String> {
        val clauses = title.lowercase().split(Regex("(?i)\\s+(?:as|while|whereas|but)\\s+|[,;،]"))
        val lines = subjects.mapNotNull { subject ->
            fun matches(part: String, word: String): Boolean {
                val at = part.indexOf(word)
                return at >= 0 && (at == 0 || !part[at - 1].isLetterOrDigit()) &&
                    (at + word.length == part.length || !part[at + word.length].isLetterOrDigit())
            }
            val clause = clauses.firstOrNull { part -> subject.words.any { matches(part, it) } }
                ?: return@mapNotNull null
            if (subject.label.startsWith("نفت (نوع") &&
                (clause.contains("brent") || clause.contains("wti"))) return@mapNotNull null
            val word = subject.words.first { matches(clause, it) }
            val after = clause.substringAfter(word).take(55)
            val ambiguous = hypothetical.containsMatchIn(clause) ||
                (subject.label.startsWith("سهام") && companyMetric.containsMatchIn(clause))
            val up = !ambiguous && rising.containsMatchIn(after)
            val down = !ambiguous && falling.containsMatchIn(after)
            val movement = when {
                up && !down -> "↑ تیتر از رشد همین دارایی می‌گوید"
                down && !up -> "↓ تیتر از افت همین دارایی می‌گوید"
                else -> "جهت قیمت این دارایی از تیتر روشن نیست"
            }
            val pairs = if (subject.label == "دلار آمریکا (USD)" && up != down) {
                if (up) "؛ EUR/USD ممکن است ↓ و USD/JPY ممکن است ↑ بروند، فقط اگر ارز مقابل ثابت بماند"
                else "؛ EUR/USD ممکن است ↑ و USD/JPY ممکن است ↓ بروند، فقط اگر ارز مقابل ثابت بماند"
            } else ""
            "${subject.label}: $movement$pairs"
        }
        if (lines.isNotEmpty()) return lines
        return if (macro.containsMatchIn(title))
            listOf("دلار آمریکا و جفت‌های مرتبط ممکن است متاثر شوند؛ جهت USD، EUR/USD و XAU/USD از نام رویداد مشخص نیست")
        else listOf("نماد و جهت مشخصی از این تیتر قابل استخراج نیست؛ صعود یا نزول هیچ نمادی ادعا نمی‌شود")
    }
}
