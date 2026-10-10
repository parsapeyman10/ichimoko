package com.aurum.edge.core

import kotlinx.serialization.Serializable

/**
 * Real market data contracts.
 *
 * Rule of this app: every price that reaches the UI originates from a real provider
 * (Twelve Data REST/WebSocket, labelled live fallback ticks) or from a local cache of previously received real prices.
 * There is no generator, no random walk, no seeded simulation anywhere in this module.
 */
@Serializable
data class Candle(
    /** Bar open time, epoch millis (UTC). */
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
    /** false only for the bar that is still forming right now. */
    val closed: Boolean = true,
) {
}

enum class Interval(val api: String, val label: String, val minutes: Int) {
    M1("1min", "1m", 1),
    M5("5min", "5m", 5),
    M15("15min", "15m", 15),
    M30("30min", "30m", 30),
    H1("1h", "1H", 60),
    H4("4h", "4H", 240),
    D1("1day", "1D", 1440);

    val millis: Long get() = minutes * 60_000L

    companion object {
        fun fromLabel(label: String): Interval = entries.firstOrNull { it.label == label } ?: M5
    }
}

data class PriceTick(val price: Double, val at: Long, val bid: Double? = null, val ask: Double? = null,
                     val isTradeTick: Boolean = true) {
    val spread: Double? get() = if (bid != null && ask != null && ask >= bid) ask - bid else null
}

enum class SignalAction { BUY, SELL, NO_TRADE }

enum class AssetClass(val code: String, val label: String, val maxSlots: Int) {
    CRYPTO("CRYPTO", "رمزارز", 2),
    FOREX("FOREX", "جفت‌ارز فارکس", 2),
    COMMODITY("COMMODITY", "کالا و انرژی", 2),
    STOCK("STOCK", "سهام و شاخص", 2);

    companion object {
        // Provider-independent identity; do not mistake a slashless EURUSD for a stock.
        // Known currencies are explicit; arbitrary six-letter strings are not assumed FX.
        private val fiat = setOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD",
            "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "TRY", "ZAR", "MXN", "SGD", "HKD")
        private val equities = setOf("AAPL", "NVDA", "TSLA", "MSFT", "AMZN", "GOOGL", "META",
            "AMD", "NFLX", "INTC", "NASDAQ", "SP500", "DOW", "DAX", "FTSE", "NIKKEI")

        fun of(symbol: String): AssetClass {
            val s = symbol.trim().uppercase(java.util.Locale.ROOT)
            val pair = s.replace("/", "")
            return when {
                s.contains("USDT") || s.startsWith("BTC") || s.startsWith("ETH") || s.startsWith("SOL") ||
                    s.startsWith("BNB") || s.startsWith("XRP") || s.startsWith("DOGE") || s.startsWith("ADA") ||
                    s.startsWith("AVAX") || s.startsWith("LINK") || s.startsWith("TON") || s.startsWith("DOT") ||
                    s.startsWith("SHIB") || s.startsWith("PEPE") || s.startsWith("SUI") || s.startsWith("NEAR") ||
                    s.endsWith("/USDT") -> CRYPTO

                s.startsWith("XAU") || s.startsWith("XAG") || s.startsWith("BRENT") || s.startsWith("WTI") ||
                    s.startsWith("USOIL") || s.startsWith("UKOIL") || s.startsWith("NATGAS") || s.startsWith("COPPER") ||
                    s == "GOLD" || s == "SILVER" -> COMMODITY

                pair.length == 6 && pair.substring(0, 3) in fiat &&
                    pair.substring(3) in fiat && pair.substring(0, 3) != pair.substring(3) -> FOREX

                s in equities || (!s.contains("/") && s.length in 1..5) -> STOCK

                // Historical unknown symbols retain their previous risk bucket; a feed must
                // still validate the actual instrument before a trade can be considered.
                else -> FOREX
            }
        }
    }
}

enum class StrategyKind(val id: String, val label: String, val description: String) {
    ICHIMOKU_PRICE_ACTION(
        "ICHIMOKU_PRICE_ACTION",
        "ایچیموکو + پرایس اکشن",
        "موتور واحد V1: EMA/ایچیموکو + RSI/MACD + واکنش حمایت/مقاومت + هفت تایم‌فریم M1 تا D1",
    );

    companion object {
        // Older persisted choices are migrated to the only V1 engine; none is offered as
        // a separate executable strategy. The real selectable modes are CategoryStrategy.
        fun fromId(raw: String?): StrategyKind? = when (raw?.trim()?.uppercase(java.util.Locale.ROOT)) {
            null, "", "ICHIMOKU_PRICE_ACTION", "ICHIMOKU", "SUPER_PLUS", "ICT_SMC",
            "EMA_VWAP", "VOLUME_BREAKOUT", "MEAN_REVERSION" -> ICHIMOKU_PRICE_ACTION
            else -> null // unknown historical IDs must not be advertised as implemented
        }
    }
}

/**
 * Legacy profile record kept for persisted settings and old journal compatibility.
 * These options are NOT V1 signal switches; the live engine uses four scored layers,
 * seven independent frames and the per-category TREND/RANGE/HYBRID mode only.
 */
data class SignalProfile(
    val momentumVolume: Boolean = false,
    val flatSpanB: Boolean = false,
    val rangeChopFilter: Boolean = false,
    val higherTimeframeFilter: Boolean = false,
    val fakeBreakoutFilter: Boolean = false,
    val dynamicSpreadFilter: Boolean = false,
    val riskyTimingFilter: Boolean = false,
    val structureRiskFilter: Boolean = false,
    val cooldownFilter: Boolean = false,
    /** The standard Chikou confirmation can be explicitly disabled for profile experiments. */
    val chikouConfirmation: Boolean = true,
) {
    val isBaseOnly: Boolean get() = !momentumVolume && !flatSpanB && !rangeChopFilter &&
        !higherTimeframeFilter && !fakeBreakoutFilter && !dynamicSpreadFilter &&
        !riskyTimingFilter && !structureRiskFilter && !cooldownFilter && chikouConfirmation
    val title: String get() = activeLabels().joinToString(" + ")

    fun activeLabels(): List<String> = buildList {
        add("پایه")
        if (momentumVolume) add("مومنتوم/حجم")
        if (flatSpanB) add("تختی SpanB52")
        if (rangeChopFilter) add("ضد رنج")
        if (higherTimeframeFilter) add("تایم بالاتر")
        if (fakeBreakoutFilter) add("ضد فیک‌بریک")
        if (dynamicSpreadFilter) add("اسپرد پویا")
        if (riskyTimingFilter) add("زمان خطرناک")
        if (structureRiskFilter) add("ریسک ساختار")
        if (cooldownFilter) add("کول‌داون")
        if (!chikouConfirmation) add("بدون تایید چیکو")
    }

    fun persistName(): String = buildList {
        if (momentumVolume) add("MOMENTUM_VOLUME")
        if (flatSpanB) add("FLAT_SPAN_B")
        if (rangeChopFilter) add("RANGE_CHOP_FILTER")
        if (higherTimeframeFilter) add("HIGHER_TIMEFRAME_FILTER")
        if (fakeBreakoutFilter) add("FAKE_BREAKOUT_FILTER")
        if (dynamicSpreadFilter) add("DYNAMIC_SPREAD_FILTER")
        if (riskyTimingFilter) add("RISKY_TIMING_FILTER")
        if (structureRiskFilter) add("STRUCTURE_RISK_FILTER")
        if (cooldownFilter) add("COOLDOWN_FILTER")
        if (!chikouConfirmation) add("CHIKOU_OFF")
    }.ifEmpty { listOf("BASE") }.joinToString(",")

    companion object {
        val BASE = SignalProfile()

        fun forStrategy(kind: StrategyKind): SignalProfile = SignalProfile(
            momentumVolume = true,
            higherTimeframeFilter = true,
            rangeChopFilter = true,
            dynamicSpreadFilter = true,
            chikouConfirmation = true,
            flatSpanB = true,
        )

        /** Migrates the previous single-choice enum value, and also accepts comma lists. */
        fun fromName(raw: String?): SignalProfile {
            val parts = raw.orEmpty().split(',', '|', '+').map { it.trim().uppercase() }.toSet()
            return SignalProfile(
                momentumVolume = "MOMENTUM_VOLUME" in parts,
                flatSpanB = "FLAT_SPAN_B" in parts,
                rangeChopFilter = "RANGE_CHOP_FILTER" in parts,
                higherTimeframeFilter = "HIGHER_TIMEFRAME_FILTER" in parts,
                fakeBreakoutFilter = "FAKE_BREAKOUT_FILTER" in parts,
                dynamicSpreadFilter = "DYNAMIC_SPREAD_FILTER" in parts,
                riskyTimingFilter = "RISKY_TIMING_FILTER" in parts,
                structureRiskFilter = "STRUCTURE_RISK_FILTER" in parts,
                cooldownFilter = "COOLDOWN_FILTER" in parts,
                chikouConfirmation = "CHIKOU_OFF" !in parts,
            )
        }
    }
}

enum class ConfluenceStatus { CONFIRMED, PARTIAL, CONFLICT, UNKNOWN }

data class ConfluenceItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val status: ConfluenceStatus = if (ok) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
    /** Optional per-condition contribution/health shown in the UI as a percent out of 100. */
    val scorePercent: Int? = null,
)

data class Signal(
    val action: SignalAction,
    val confidence: Double,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val riskReward: Double? = null,
    val reasons: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val confluence: List<ConfluenceItem> = emptyList(),
    val interval: Interval = Interval.M5,
    val barTime: Long = 0L,
    val evaluatedAt: Long = System.currentTimeMillis(),
) {
    val isActionable: Boolean get() = action != SignalAction.NO_TRADE
}

/** Connection truth for the UI. OFFLINE is a first-class state — never replaced by invented data. */
enum class FeedMode(val label: String) {
    NO_KEY("کلید API وارد نشده"),
    CONNECTING("در حال اتصال"),
    LIVE("زنده — تیک تازه"),
    POLLING("کندل/تاریخچه آنلاین دوره‌ای — نه تیک زنده"),
    MARKET_CLOSED("تعطیلی معمول بازار؛ دریافت قیمت متوقف"),
    DELAYED("دادهٔ بازار قدیمی؛ اتصال/بازار را بررسی کنید"),
    OFFLINE("آفلاین"),
}

data class FeedStatus(
    val mode: FeedMode,
    val detail: String = "",
    val lastSuccessAt: Long? = null,
    val provider: String = "Twelve Data",
)

@Serializable
data class PaperNewsEvidence(
    val id: String,
    val source: String,
    val headline: String,
    val url: String,
    val publishedAt: Long,
)

@Serializable
data class PaperNewsRecord(
    val model: String,
    val direction: String,
    val confidence: Double,
    val checkedAt: Long,
    val evidence: List<PaperNewsEvidence>,
    /** Calendar is a schedule only; retain its verified receipt time, never invent an article. */
    val calendarSource: String? = null,
    val calendarCheckedAt: Long? = null,
)

@Serializable
data class PaperAiReview(
    /** WORTHY | RISKY | NOT_WORTHY */
    val verdict: String,
    val confidence: Int,
    val summary: String,
    val reasons: List<String>,
    val cautions: List<String>,
    val model: String,
    val checkedAt: Long,
)

/**
 * Companion AI's opinion about an ALREADY OPEN paper position ("continue or not").
 *
 * Advisory ONLY by design: the app closes a position exclusively when a real price touches its
 * stop or target ([com.aurum.edge.data.JournalStore.settle] / `settleTick`). This record never
 * edits, closes or re-prices a trade — it is attached as a note and may raise one research
 * notification so the user can decide. [lastPrice]/[unrealizedUsd] are the REAL numbers the model
 * was shown, kept next to the verdict so the note stays auditable later.
 */
@Serializable
data class PaperHoldReview(
    /** HOLD | WATCH | DO_NOT_CONTINUE */
    val verdict: String,
    val confidence: Int,
    val summary: String,
    val reasons: List<String>,
    val cautions: List<String>,
    val lastPrice: Double? = null,
    val unrealizedUsd: Double? = null,
    val model: String,
    val checkedAt: Long,
)

/** Snapshot of an OHLC approximation at the moment a PAPER opportunity/entry was checked. */
@Serializable
data class IctPriceActionRecord(
    val model: String = "OHLC_RANGE_ICT_V1",
    val symbol: String,
    val interval: Interval,
    val barTime: Long,
    val action: SignalAction,
    val feedProvider: String,
    val checkedAt: Long,
    val nyDate: String,
    val nySession: String,
    val nyTime: String,
    val support: Double,
    val resistance: Double,
    val atr: Double,
    val supportTouches: Int,
    val resistanceTouches: Int,
    val levelsConfirmedAt: Long,
    val sweepAt: Long,
    val mssAt: Long,
    val fvgAt: Long,
    val fvgLow: Double,
    val fvgHigh: Double,
    val orderBlockLow: Double?,
    val orderBlockHigh: Double?,
    val retestAt: Long,
    val quote: Double,
    val stop: Double,
    val target: Double,
    val stopBoundary: Double,
    val opposingLevel: Double,
    val rewardRisk: Double,
) {
    fun matches(signal: Signal, marketSymbol: String, marketPrice: Double): Boolean {
        val risk = if (action == SignalAction.BUY) quote - stop else stop - quote
        val reward = if (action == SignalAction.BUY) target - quote else quote - target
        return model == "OHLC_RANGE_ICT_V1" && symbol == marketSymbol &&
            action != SignalAction.NO_TRADE && action == signal.action &&
            interval == signal.interval && barTime == signal.barTime &&
            quote == marketPrice && stop == signal.stopLoss && target == signal.takeProfit &&
            feedProvider.isNotBlank() && checkedAt > barTime && nyDate.isNotBlank() &&
            nySession in setOf("LONDON", "NEW_YORK") && nyTime.isNotBlank() &&
            support > 0.0 && resistance > support && atr.isFinite() && atr > 0.0 &&
            supportTouches >= 2 && resistanceTouches >= 2 &&
            levelsConfirmedAt > 0L && levelsConfirmedAt <= sweepAt &&
            sweepAt < mssAt && mssAt < fvgAt &&
            fvgAt < retestAt && retestAt == barTime && fvgLow.isFinite() && fvgHigh > fvgLow &&
            risk > 0.0 && reward / risk >= 1.5 && reward / risk <= 5.0 &&
            rewardRisk.isFinite() && kotlin.math.abs(reward / risk - rewardRisk) < 1e-6 &&
            (if (action == SignalAction.BUY) stop <= stopBoundary && target <= opposingLevel
             else stop >= stopBoundary && target >= opposingLevel)
    }
}

@Serializable
data class PaperTrade(
    val id: String,
    val symbol: String,
    val interval: Interval,
    val action: SignalAction,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val confidence: Double,
    val riskReward: Double,
    val openedAt: Long,
    val closedAt: Long? = null,
    val exitPrice: Double? = null,
    val exitReason: String? = null,
    val pnlUsd: Double? = null,
    /** Historical JSON field name; quantity is in [unit], not necessarily troy ounces. */
    val positionOz: Double = 1.0,
    val note: String = "paper روی قیمت واقعی",
    /** Empty for older journal records; infer from the symbol on read. */
    val positionUnit: String = "",
    /** What the multi-timeframe engine said on the phone when this paper trade was opened. */
    val mtf: MtfSnapshotRecord? = null,
    /** Defaults keep older journal JSON readable. Auto is always PAPER, never a broker fill. */
    val autoOpened: Boolean = false,
    val signalBarTime: Long? = null,
    val newsEvidence: PaperNewsRecord? = null,
    /** Companion AI's post-open educational review; never a gate and never financial advice. */
    val aiReview: PaperAiReview? = null,
    /** Latest AI opinion about keeping this OPEN position; advisory note only, never closes it. */
    val holdReview: PaperHoldReview? = null,
    /** Snapshot at the moment the paper position was actually saved; never recompute on read. */
    val entryConditions: List<PaperConditionRecord> = emptyList(),
    /** Null on older/manual records; never infer a historical ICT verdict on read. */
    val priceAction: IctPriceActionRecord? = null,
    val leverage: Int = 20,
    val marginUsd: Double = 0.0,
    val commissionUsd: Double = 0.0,
    val spreadCostUsd: Double = 0.0,
    /**
     * «روند کلی بازار» در همان لحظهٔ ورود: روندِ تایم‌فریم مرجعِ خودِ نماد + خوانشِ عرض بازار/
     * دلار/جوّ ریسک + اینکه این ورود هم‌جهت بود یا خلاف جهت. یک عکسِ ثبت‌شده است و هرگز
     * بعد از ورود دوباره محاسبه نمی‌شود. برای رکوردهای قدیمی‌تر null است.
     */
    val marketTrend: MarketTrendRecord? = null,
    /** Non-null on V1 records; old positions retain their historic fixed-target exit. */
    val initialStopLoss: Double? = null,
    val trailExtreme: Double? = null,
) {
    val isOpen: Boolean get() = closedAt == null
    val unit: String get() = positionUnit.ifBlank { PaperOrderRules.unitFor(symbol) }
    val assetClass: AssetClass get() = AssetClass.of(symbol)

    val riskPerOz: Double get() = kotlin.math.abs(entry - (initialStopLoss ?: stopLoss))

    /** Risk in QUOTE currency per unit; convert to USD before comparing with the budget. */
    val riskUsd: Double
        get() = PaperOrderRules.quotePnlToUsd(symbol, riskPerOz * positionOz, entry)

    val effectiveLeverage: Int
        get() = if (leverage > 0) leverage else PaperOrderRules.defaultLeverageFor(symbol)

    val notionalValueUsd: Double
        get() = PaperOrderRules.quotePnlToUsd(symbol, positionOz * entry, entry)

    val effectiveMarginUsd: Double
        get() = if (marginUsd > 0.0) marginUsd else (notionalValueUsd / effectiveLeverage)

    /** Legacy records (saved before the venue model) are re-costed with the SAME real venue specs. */
    val effectiveCommissionUsd: Double
        get() = if (commissionUsd > 0.0) commissionUsd
                else VenueSpecs.of(symbol).commissionUsd(entry, positionOz) * 2.0

    val effectiveSpreadCostUsd: Double
        get() = if (spreadCostUsd > 0.0) spreadCostUsd
                else VenueSpecs.of(symbol).spreadCostUsd(entry, positionOz)

    val rMultiple: Double?
        get() {
            val pnl = pnlUsd ?: return null
            val risk = riskUsd
            return if (risk <= 0.0) null else pnl / risk
        }
}

@Serializable
data class PaperConditionRecord(val name: String, val status: String, val detail: String) {
    companion object {
        fun from(item: ConfluenceItem) = PaperConditionRecord(item.name, item.status.name, item.detail)
    }
}

/** An eligible alert is NOT a trade. Stored separately from paper positions and their statistics. */
@Serializable
data class PaperOpportunity(
    val key: String,
    val symbol: String,
    val interval: Interval,
    val action: SignalAction,
    val signalBarTime: Long,
    val priceAtAlert: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val alertedAt: Long,
    val conditions: List<PaperConditionRecord>,
    val mtf: MtfSnapshotRecord,
    val newsEvidence: PaperNewsRecord? = null,
    val paperTradeId: String? = null,
    /** Null only for a candidate written before the new ICT gate. */
    val priceAction: IctPriceActionRecord? = null,
) {
    companion object {
        fun from(signal: Signal, symbol: String, price: Double, mtf: MtfSnapshotRecord,
                 news: PaperNewsRecord?, ict: IctPriceActionRecord? = null,
                 now: Long = System.currentTimeMillis()): PaperOpportunity {
            val technicalConditions = TechnicalEvidence.items(signal)
            require(PaperOrderRules.paperable(symbol) && signal.isActionable && signal.barTime > 0 &&
                TechnicalEvidence.confirmed(signal) &&
                price.isFinite() && price > 0 && signal.stopLoss != null && signal.takeProfit != null &&
                mtf.frames.isNotEmpty() && mtf.barTime == signal.barTime && mtf.baseInterval == signal.interval.label &&
                (ict == null || ict.matches(signal, symbol, price))) {
                "فرصت فنی معتبر نیست"
            }
            return PaperOpportunity(
                key = "$symbol|${signal.interval.label}|${signal.barTime}|${signal.action}",
                symbol = symbol, interval = signal.interval, action = signal.action,
                signalBarTime = signal.barTime, priceAtAlert = price,
                stopLoss = signal.stopLoss, takeProfit = signal.takeProfit,
                alertedAt = now, conditions = technicalConditions.map(PaperConditionRecord::from),
                mtf = mtf, newsEvidence = news, priceAction = ict,
            )
        }
    }
}

/**
 * «روند کلی بازار» — عکسِ اندازه‌گیری‌شدهٔ لحظهٔ ورود.
 *
 * هر فیلد اینجا یک عددِ اندازه‌گیری‌شده از کندل‌های بستهٔ واقعی است (نه پیش‌بینی):
 * جهتِ روندِ تایم‌فریم مرجعِ همان نماد، قدرتِ آن، جهتِ کلی بازار از عرضِ ۵۰+ نماد،
 * جهت دلار از شش جفت اصلی، جوّ ریسک‌پذیری، و جایگاه این ورود نسبت به همهٔ این‌ها.
 */
@Serializable
data class SymbolTrendRecord(
    val direction: String,
    val strength: Int,
    val intervalLabel: String,
    val higherLabel: String? = null,
    val efficiencyRatio: Double? = null,
    val conflict: Boolean = false,
    val closedBars: Int = 0,
    val higherBars: Int = 0,
    val detailFa: String = "",
) {
    companion object {
        fun from(trend: com.aurum.edge.core.SymbolTrend): SymbolTrendRecord = SymbolTrendRecord(
            direction = trend.direction.name,
            strength = trend.strength,
            intervalLabel = trend.intervalLabel,
            higherLabel = trend.higherLabel,
            efficiencyRatio = trend.efficiencyRatio,
            conflict = trend.conflict,
            closedBars = trend.closedBars,
            higherBars = trend.higherBars,
            detailFa = trend.detailFa,
        )
    }
}

@Serializable
data class MarketTrendRecord(
    /** جهت کلی بازار از عرضِ نمادهای پویش‌شده. */
    val bias: String,
    val strength: Int = 0,
    /** جهت دلار از شش جفت اصلی. */
    val dollarBias: String = "UNKNOWN",
    /** RISK_ON | RISK_OFF | MIXED | UNKNOWN */
    val riskTone: String = "UNKNOWN",
    val breadthUp: Int = 0,
    val breadthDown: Int = 0,
    val breadthFlat: Int = 0,
    val measured: Int = 0,
    /** WITH | AGAINST | NEUTRAL | UNKNOWN — جایگاه همین معامله نسبت به روند. */
    val alignment: String = "UNKNOWN",
    /** روندِ خودِ این نماد؛ برای رکوردهای قدیمی null. */
    val symbol: SymbolTrendRecord? = null,
    val noteFa: String = "",
    val computedAt: Long = 0L,
) {
    val aligned: Boolean get() = alignment == "WITH"
    val against: Boolean get() = alignment == "AGAINST"

    companion object {
        fun from(context: com.aurum.edge.core.TrendContext): MarketTrendRecord {
            val overall = context.overall
            return MarketTrendRecord(
                bias = overall?.bias?.name ?: "UNKNOWN",
                strength = overall?.strength ?: 0,
                dollarBias = overall?.dollarBias?.name ?: "UNKNOWN",
                riskTone = overall?.riskTone?.name ?: "UNKNOWN",
                breadthUp = overall?.breadthUp ?: 0,
                breadthDown = overall?.breadthDown ?: 0,
                breadthFlat = overall?.breadthFlat ?: 0,
                measured = overall?.measured ?: 0,
                alignment = context.alignment.name,
                symbol = context.symbol?.let { SymbolTrendRecord.from(it) },
                noteFa = context.noteFa,
                computedAt = overall?.computedAt ?: System.currentTimeMillis(),
            )
        }
    }
}

@Serializable
data class MtfFrameRecord(
    val interval: String,
    val bias: String,
    val strength: Int,
    val detail: String,
    val weight: Double,
)

@Serializable
data class MtfSnapshotRecord(
    val baseInterval: String,
    val bias: String,
    val alignment: Double,
    val buyCount: Int,
    val sellCount: Int,
    val neutralCount: Int,
    val veto: Boolean,
    val advisory: String,
    val barTime: Long,
    val frames: List<MtfFrameRecord> = emptyList(),
    val skippedFrames: List<String> = emptyList(),
) {
    companion object {
        fun from(snapshot: com.aurum.edge.engine.MtfAnalyzer.Snapshot): MtfSnapshotRecord = MtfSnapshotRecord(
            baseInterval = snapshot.baseInterval.label,
            bias = snapshot.bias.name,
            alignment = snapshot.alignment,
            buyCount = snapshot.buyCount,
            sellCount = snapshot.sellCount,
            neutralCount = snapshot.neutralCount,
            veto = snapshot.veto,
            advisory = snapshot.advisory,
            barTime = snapshot.barTime,
            frames = snapshot.frames.map {
                MtfFrameRecord(
                    interval = it.interval.label,
                    bias = it.bias.name,
                    strength = it.strength,
                    detail = it.detail,
                    weight = it.weight,
                )
            },
            skippedFrames = snapshot.skippedFrames,
        )
    }
}

data class AppSettings(
    val apiKey: String = "",
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val riskPercent: Double = 0.5,
    val accountBalance: Double = 1000.0,
    val minConfidence: Double = 85.0,
    /** Cost assumptions in USD. They must match your broker; every report states them. */
    // Defaults are the REAL reference costs of the most liquid gold venue pair we quote:
    // IC Markets Raw Spread (EU): XAU/USD ≈ $0.12/oz spread + $3.50 per 100 oz lot/side = $0.035/oz.
    val spreadPrice: Double = 0.12,
    val commissionPerOz: Double = 0.035,
    val backgroundMonitor: Boolean = false,
    val notifyOnSignal: Boolean = true,
    /** Persistable SAF content Uri; blank uses the device's system notification tone. */
    val alertSoundUri: String = "",
    val alertSoundName: String = "",
    /** Optional HTTPS URL of this project's backend (licensed Persian news). */
    val newsBaseUrl: String = "",
    /** Applies to NEW paper entries; real orders remain disabled independently. */
    val pauseOnNews: Boolean = false,
    /** Automatic orders here are local paper records, never broker orders. */
    val autoPaperTrading: Boolean = false,
    /** Check for a public APK when the app starts and download it when one is available. */
    val autoDownloadUpdates: Boolean = false,
    /**
     * OPTIONAL alternative to [newsBaseUrl] for the Forex ninth-condition AI gate: instead of your
     * own backend server, the phone calls this endpoint DIRECTLY with your own key - either the
     * Anthropic (Claude) Messages API or any OpenAI-compatible service (see [newsAiFormat]). Used only if [newsBaseUrl] is blank. The SAME publisher-rights/consent responsibility
     * applies to whoever holds this key; headline/excerpt text goes straight to this host from
     * the phone, so if you later share this APK, this key travels with it and is extractable.
     */
    val newsAiApiKey: String = "",
    val newsAiBaseUrl: String = "",
    val newsAiModel: String = "",
    /**
     * Wire format of the AI endpoint: AUTO (detect Anthropic by host, else OpenAI-compatible),
     * ANTHROPIC (Messages API on ANY host, e.g. a relay) or OPENAI (chat/completions on any
     * host). Explicit beats guessing for proxy keys with non-standard prefixes.
     */
    val newsAiFormat: String = "AUTO",
    val signalProfile: SignalProfile = SignalProfile.BASE,
    val activeStrategy: StrategyKind = StrategyKind.ICHIMOKU_PRICE_ACTION,
    val activeWatchlist: List<String> = V1Universe.defaults,
    val categoryStrategies: Map<AssetClass, CategoryStrategy> = AssetClass.entries.associateWith { CategoryStrategy.HYBRID },
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()
    val hasClientNewsAi: Boolean get() = newsAiApiKey.isNotBlank() && newsAiBaseUrl.isNotBlank() && newsAiModel.isNotBlank()
    val newsAiFormatNormalized: String get() = if (newsAiFormat in setOf("ANTHROPIC", "OPENAI")) newsAiFormat else "AUTO"
}
