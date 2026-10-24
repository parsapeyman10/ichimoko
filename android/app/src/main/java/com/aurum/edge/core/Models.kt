package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import kotlinx.serialization.Serializable

data class PriceTick(
    val price: Double,
    val at: Long,
    val bid: Double? = null,
    val ask: Double? = null,
    val isTradeTick: Boolean = true,
    val provider: String? = null,
    val sourceNote: String? = null,
    val sourceConfirmations: Int = 0,
) {
    val spread: Double? get() = if (bid != null && ask != null && ask >= bid) ask - bid else null
}

enum class AssetClass(val code: String, val label: String, val maxSlots: Int) {
    CRYPTO("CRYPTO", "رمزارز", 2),
    FOREX("FOREX", "جفت‌ارز فارکس", 2),
    COMMODITY("COMMODITY", "کالا و انرژی", 2),
    STOCK("STOCK", "سهام و شاخص", 2);

    companion object {
        private val fiat = setOf(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD",
            "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "TRY", "ZAR", "MXN", "SGD", "HKD",
        )
        private val equities = setOf(
            "AAPL", "NVDA", "TSLA", "MSFT", "AMZN", "GOOGL", "META",
            "AMD", "NFLX", "INTC", "NASDAQ", "SP500", "DOW", "US30", "DJI", "^DJI", "DJIA", "DAX", "FTSE", "NIKKEI",
        )

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

                else -> FOREX
            }
        }
    }
}

enum class StrategyKind(val id: String, val label: String, val description: String) {
    ICHIMOKU_PRICE_ACTION(
        "ICHIMOKU_PRICE_ACTION",
        "ایچیموکو + پرایس اکشن",
        "چارت ایچیموکو",
    );

    companion object {
        fun fromId(raw: String?): StrategyKind? = when (raw?.trim()?.uppercase(java.util.Locale.ROOT)) {
            null, "", "ICHIMOKU_PRICE_ACTION", "ICHIMOKU", "SUPER_PLUS", "ICT_SMC",
            "EMA_VWAP", "VOLUME_BREAKOUT", "MEAN_REVERSION" -> ICHIMOKU_PRICE_ACTION
            else -> null
        }
    }
}

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
    val chikouConfirmation: Boolean = true,
) {
    fun persistName(): String = "BASE"

    companion object {
        val BASE = SignalProfile()
    }
}

enum class ConfluenceStatus { CONFIRMED, PARTIAL, CONFLICT, UNKNOWN }

data class ConfluenceItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val status: ConfluenceStatus = if (ok) ConfluenceStatus.CONFIRMED else ConfluenceStatus.CONFLICT,
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

@Serializable
data class PaperConditionRecord(val name: String, val status: String, val detail: String)

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
    val positionOz: Double = 1.0,
    val note: String = "",
    val positionUnit: String = "",
    val autoOpened: Boolean = false,
    val signalBarTime: Long? = null,
    val entryConditions: List<PaperConditionRecord> = emptyList(),
    val leverage: Int = 20,
    val marginUsd: Double = 0.0,
    val commissionUsd: Double = 0.0,
    val spreadCostUsd: Double = 0.0,
    val initialStopLoss: Double? = null,
    val trailExtreme: Double? = null,
    val cryptoVenue: String = "NOBITEX",
) {
    val isOpen: Boolean get() = closedAt == null
    val unit: String get() = positionUnit.ifBlank { PaperOrderRules.unitFor(symbol) }
    val assetClass: AssetClass get() = AssetClass.of(symbol)

    val riskPerOz: Double get() = kotlin.math.abs(entry - (initialStopLoss ?: stopLoss))

    val riskUsd: Double
        get() = PaperOrderRules.quotePnlToUsd(symbol, riskPerOz * positionOz, entry)

    val effectiveLeverage: Int
        get() = if (leverage > 0) leverage else PaperOrderRules.defaultLeverageFor(symbol)

    val notionalValueUsd: Double
        get() = PaperOrderRules.quotePnlToUsd(symbol, positionOz * entry, entry)

    val effectiveMarginUsd: Double
        get() = if (marginUsd > 0.0) marginUsd else (notionalValueUsd / effectiveLeverage)

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

data class AppSettings(
    val apiKey: String = "",
    val twelveDataKeys: Map<String, String> = emptyMap(),
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val riskPercent: Double = 0.5,
    val accountBalance: Double = 1000.0,
    val minConfidence: Double = 85.0,
    val spreadPrice: Double = 0.12,
    val commissionPerOz: Double = 0.035,
    val backgroundMonitor: Boolean = false,
    val notifyOnSignal: Boolean = true,
    val alertSoundUri: String = "",
    val alertSoundName: String = "",
    val newsBaseUrl: String = "",
    val pauseOnNews: Boolean = false,
    val autoPaperTrading: Boolean = false,
    val autoDownloadUpdates: Boolean = true,
    val newsAiApiKey: String = "",
    val newsAiBaseUrl: String = "",
    val newsAiModel: String = "",
    val newsAiFormat: String = "AUTO",
    val signalProfile: SignalProfile = SignalProfile.BASE,
    val activeStrategy: StrategyKind = StrategyKind.ICHIMOKU_PRICE_ACTION,
    val categoryStrategies: Map<AssetClass, CategoryStrategy> = AssetClass.entries.associateWith { CategoryStrategy.HYBRID },
) {
    val hasKey: Boolean get() = apiKey.isNotBlank() || twelveDataKeys.values.any { it.isNotBlank() }

    fun apiKeyFor(symbol: String): String =
        twelveDataKeys[V1Universe.normalize(symbol)]?.takeIf { it.isNotBlank() } ?: apiKey

    fun hasKeyFor(symbol: String): Boolean = apiKeyFor(symbol).isNotBlank()

    fun dedicatedKeySymbols(): List<String> =
        twelveDataKeys.filterValues { it.isNotBlank() }.keys.sorted()

    val activeSymbols: List<String> get() = com.aurum.edge.data.WatchCatalog.scannerSymbols

    fun strategyFor(symbol: String): CategoryStrategy =
        categoryStrategies[AssetClass.of(symbol)] ?: CategoryStrategy.HYBRID
}
