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
    CRYPTO("CRYPTO", "رمزارز", 999),
    FOREX("FOREX", "جفت‌ارز فارکس", 999),
    COMMODITY("COMMODITY", "کالا و انرژی", 999),
    STOCK("STOCK", "سهام و شاخص", 999);

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
data class PreEntryCandleSlot(
    val slotIndex: Int = 0, // -2 = کندل ۳ قبل, -1 = کندل ۲ قبل, 0 = کندل ۱ قبل (ماشه)
    val slotTitleFa: String = "",
    val barTime: Long = 0L,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val close: Double = 0.0,
    val isBullish: Boolean = true,
    val bodyPctOfRange: Int = 50,
    val upperWickPct: Int = 25,
    val lowerWickPct: Int = 25,
    val rangeAtrRatio: Double = 1.0,
    val tenkanValue: Double = 0.0,
    val kijunValue: Double = 0.0,
    val kumoTop: Double = 0.0,
    val kumoBottom: Double = 0.0,
    val priceVsTenkanFa: String = "",
    val priceVsKijunFa: String = "",
    val priceVsKumoFa: String = "",
    val candleAnatomyFa: String = "",
    val roleInSetupFa: String = "",
)

enum class PreEntry3CandleCategory(
    val code: String,
    val badgeFa: String,
    val titleFa: String,
    val descriptionFa: String,
) {
    PULLBACK_REJECTION_IGNITION(
        code = "PULLBACK_REJECTION_IGNITION",
        badgeFa = "🎯 پولبک + ریجکت شدو + شلیک",
        titleFa = "الگوی ۳ کندلی پولبک، ریجکت شدو از تنکان/کیجون و شلیک روند",
        descriptionFa = "کندل ۳ و ۲ قبل اصلاح به سمت خط تعادلی تنکان/کیجون داشته و با شدوی برگشتی دفاع شده و کندل ماشه در جهت روند بسته شده است.",
    ),
    THREE_SOLDIERS_MOMENTUM_BREAKOUT(
        code = "THREE_SOLDIERS_MOMENTUM_BREAKOUT",
        badgeFa = "🚀 ۳ کندل پیشروی پرقدرت",
        titleFa = "الگوی ۳ کندلی پیشروی پلکانی هم‌جهت با بدنه قوی (3-Bar Momentum)",
        descriptionFa = "هر ۳ کندل قبل از ورود هم‌جهت با معامله و دارای بدنه‌های متوالی در بالای/زیر تنکان و کومو هستند.",
    ),
    INSIDE_BAR_COMPRESSION_EXPANSION(
        code = "INSIDE_BAR_COMPRESSION_EXPANSION",
        badgeFa = "⚡ فشردگی و انفجار (Inside-Bar)",
        titleFa = "الگوی ۳ کندلی فشردگی نوسان و انفجار شکست (Compression -> Expansion)",
        descriptionFa = "کندل‌های قبل دارای رنج فشرده (Inside Bar / Doji) روی سطح ایچیموکو بوده و کندل ماشه با باز شدن دامنه نوسان، شکست را رقم زده است.",
    ),
    ENGULFING_REVERSAL_OFF_LEVEL(
        code = "ENGULFING_REVERSAL_OFF_LEVEL",
        badgeFa = "🔄 چرخش اینگلفینگ روی سطح",
        titleFa = "الگوی ۳ کندلی چرخش و اینگلفینگ روی سطح تعادلی ایچیموکو",
        descriptionFa = "پس از کندل اصلاحی خلاف جهت، کندل ماشه بدنه کندل قبلی را به صورت کامل پوشانده (Engulfing) و روند را بازگردانده است.",
    ),
    CCLAMP_EXTENDED_CHASE(
        code = "CCLAMP_EXTENDED_CHASE",
        badgeFa = "⚠️ کشیدگی ۳ کندلی دور از کیجون",
        titleFa = "الگوی ۳ کندلی کشیدگی متوالی دور از کیجون (Extended Momentum Chase)",
        descriptionFa = "۳ کندل قبل با شتاب بالا از کیجون فاصله گرفته‌اند؛ نیازمند مدیریت سریع حد ضرر روی تنکان.",
    ),
    CONSOLIDATION_BREAKOUT_TRIGGER(
        code = "CONSOLIDATION_BREAKOUT_TRIGGER",
        badgeFa = "📐 تثبیت ۲ کندلی و شکست ماشه",
        titleFa = "الگوی ۳ کندلی تثبیت و شکست از محدوده تعادلی",
        descriptionFa = "۲ کندل اول در تعادل نوسانی بوده و کندل سوم ماشه ورود را فعال کرده است.",
    );

    companion object {
        fun fromCode(raw: String?): PreEntry3CandleCategory =
            entries.firstOrNull { it.code.equals(raw?.trim(), ignoreCase = true) }
                ?: CONSOLIDATION_BREAKOUT_TRIGGER
    }
}

@Serializable
data class PreEntry3CandleContext(
    val categoryCode: String = PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER.code,
    val categoryBadgeFa: String = PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER.badgeFa,
    val categoryTitleFa: String = PreEntry3CandleCategory.CONSOLIDATION_BREAKOUT_TRIGGER.titleFa,
    val summaryFa: String = "",
    val candles: List<PreEntryCandleSlot> = emptyList(),
)

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
    val leverage: Int = 10,
    val marginUsd: Double = 0.0,
    val commissionUsd: Double = 0.0,
    val spreadCostUsd: Double = 0.0,
    val initialStopLoss: Double? = null,
    val trailExtreme: Double? = null,
    val cryptoVenue: String = "NOBITEX",
    val strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
    val strategyTitleFa: String = BitcoinStrategyModel.BASE_ICHIMOKU.titleFa,
    val fixedRiskUsd: Double = 0.0,
    val optimalRiskReward: Double = 0.0,
    val rrBreakdownFa: String = "",
    val preEntry3CandleCategoryCode: String = "",
    val preEntry3CandleCategoryFa: String = "",
    val preEntry3CandleSummaryFa: String = "",
    val preEntry3Candles: List<PreEntryCandleSlot> = emptyList(),
    val tradeNumber: Int = 0,
) {
    val displayTradeNumber: Int
        get() = if (tradeNumber > 0) tradeNumber else ((kotlin.math.abs(id.hashCode()) % 9000) + 1)
    val displayTradeNumberBadgeFa: String
        get() = "معامله #$displayTradeNumber"
    val isOpen: Boolean get() = closedAt == null
    val unit: String get() = positionUnit.ifBlank { PaperOrderRules.unitFor(symbol) }
    val assetClass: AssetClass get() = AssetClass.of(symbol)

    val riskPerOz: Double get() = kotlin.math.abs(entry - (initialStopLoss ?: stopLoss))
    val rewardPerOz: Double get() = kotlin.math.abs(takeProfit - entry)
    val effectiveOptimalRr: Double
        get() = if (optimalRiskReward >= 1.5) optimalRiskReward.coerceIn(1.5, 3.0) else riskReward.coerceIn(1.5, 3.0)

    fun buildRrFormulaSummaryFa(): String {
        val initSl = initialStopLoss ?: stopLoss
        val riskDist = kotlin.math.abs(entry - initSl)
        val rewardDist = kotlin.math.abs(takeProfit - entry)
        val fmt = if (entry >= 500.0) "%.2f" else if (entry >= 10.0) "%.3f" else "%.5f"
        val rStr = String.format(java.util.Locale.US, fmt, riskDist)
        val tpStr = String.format(java.util.Locale.US, fmt, rewardDist)
        val rrStr = String.format(java.util.Locale.US, "%.2f", riskReward.coerceIn(1.5, 3.0))
        return "فرمول R:R = پاداش (|TP−Entry| = $tpStr) ÷ ریسک ۱R (|Entry−SL| = $rStr) = 1:$rrStr"
    }

    fun buildOptimalRrReasonFa(): String {
        if (rrBreakdownFa.isNotBlank()) return rrBreakdownFa
        val optStr = String.format(java.util.Locale.US, "%.2f", effectiveOptimalRr)
        val model = BitcoinStrategyModel.fromId(strategyId)
        val modelHint = "${model.badgeFa} (${model.combinationCategoryFa} · پایه 1:${String.format(java.util.Locale.US, "%.2f", model.baseOptimalRr.coerceIn(1.5, 3.0))})"
        return "عدد مناسب R:R این معامله: 1:$optStr (در بازه قفل‌شده ۱.۵ تا ۳.۰) — بر اساس $modelHint · 🔒 حد ضرر ساختاری کاملاً ثابت و غیرپله‌ای"
    }

    val totalRoundTripFeeUsd: Double
        get() = effectiveCommissionUsd + effectiveSpreadCostUsd

    fun buildVenueRulesAndFeesSummaryFa(): String =
        VenueSpecs.of(symbol).buildRulesSummaryFa(entry, positionOz)

    val riskUsd: Double
        get() = if (fixedRiskUsd > 0.0) {
            fixedRiskUsd
        } else {
            PaperOrderRules.quotePnlToUsd(symbol, riskPerOz * positionOz, entry)
        }

    val effectiveLeverage: Int
        get() = (if (leverage > 0) leverage else PaperOrderRules.defaultLeverageFor(symbol)).coerceIn(1, 10)

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

    val entryTimeframe: String
        get() = interval.label

    val venueNameFa: String
        get() = VenueSpecs.of(symbol).venue

    val estimatedRoundTripFeeUsd: Double
        get() = totalRoundTripFeeUsd
}

data class AppSettings(
    val apiKey: String = "",
    val twelveDataKeys: Map<String, String> = emptyMap(),
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val riskPercent: Double = 0.5,
    val accountBalance: Double = 100_000.0,
    val initialCapital: Double = 100_000.0,
    val targetCapital: Double = 100_000.0,
    val useFixedDollarRisk: Boolean = true,
    val fixedRiskUsd: Double = 5.0,
    val dailyLossLimitPercent: Double = 100.0,
    val maxDrawdownLimitPercent: Double = 100.0,
    val minConfidence: Double = 85.0,
    val spreadPrice: Double = 0.12,
    val commissionPerOz: Double = 0.035,
    val backgroundMonitor: Boolean = true,
    val notifyOnSignal: Boolean = true,
    val alertSoundUri: String = "",
    val alertSoundName: String = "",
    val alarmVolumePercent: Int = 90,
    val alarmTonePreset: String = "CUSTOM_ONLY",
    val alarmVibrationEnabled: Boolean = true,
    val entryAlarmSoundUri: String = "",
    val entryAlarmSoundName: String = "Ding-Dong",
    val entryAlarmEnabled: Boolean = true,
    val tpAlarmSoundUri: String = "",
    val tpAlarmSoundName: String = "Red Dwarf",
    val tpAlarmEnabled: Boolean = true,
    val slAlarmSoundUri: String = "",
    val slAlarmSoundName: String = "Latch",
    val slAlarmEnabled: Boolean = true,
    val showSettingsDescriptions: Boolean = true,
    val maxLeverageCap: Int = 10,
    val newsBaseUrl: String = "",
    val pauseOnNews: Boolean = false,
    val autoPaperTrading: Boolean = false,
    val autoDownloadUpdates: Boolean = true,
    val avalAiEnabled: Boolean = false,
    val newsAiApiKey: String = "",
    val newsAiBaseUrl: String = "https://api.avalai.ir/v1",
    val newsAiModel: String = "gpt-4o-mini",
    val newsAiFormat: String = "AUTO",
    val signalProfile: SignalProfile = SignalProfile.BASE,
    val activeStrategy: StrategyKind = StrategyKind.ICHIMOKU_PRICE_ACTION,
    val selectedBitcoinStrategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
    val runAllStrategiesSimultaneously: Boolean = true,
    val enabledStrategyIds: Set<String> = BitcoinStrategyModel.entries.map { it.id }.toSet(),
    val baseStrategyConfig: BaseStrategyConfig = BaseStrategyConfig(),
    val customStrategies: List<CustomStrategySlot> = emptyList(),
    val categoryStrategies: Map<AssetClass, CategoryStrategy> = AssetClass.entries.associateWith { CategoryStrategy.HYBRID },
) {
    val hasKey: Boolean get() = apiKey.isNotBlank() || twelveDataKeys.values.any { it.isNotBlank() }

    fun activeCustomStrategySlot(): CustomStrategySlot? =
        customStrategies.firstOrNull { it.id == selectedBitcoinStrategyId }

    fun activeBitcoinStrategyModel(): BitcoinStrategyModel =
        activeCustomStrategySlot()?.let { BitcoinStrategyModel.fromId(it.baseModelId) }
            ?: BitcoinStrategyModel.fromId(selectedBitcoinStrategyId)

    fun activeBitcoinStrategyTitleFa(): String =
        activeCustomStrategySlot()?.titleFa ?: activeBitcoinStrategyModel().titleFa

    fun activeStrategiesSummaryFa(): String =
        if (runAllStrategiesSimultaneously) {
            "هم‌زمان ${enabledStrategyCount()} استراتژی فعال"
        } else {
            activeBitcoinStrategyTitleFa()
        }

    fun enabledStrategyCount(): Int {
        val allKnownIds = BitcoinStrategyModel.entries.map { it.id } + customStrategies.map { it.id }
        return if (runAllStrategiesSimultaneously) {
            val matched = allKnownIds.count { it in enabledStrategyIds }
            if (matched > 0) matched else allKnownIds.size
        } else 1
    }

    fun activeBitcoinStrategyConfig(): BaseStrategyConfig =
        activeCustomStrategySlot()?.config ?: baseStrategyConfig

    fun apiKeyFor(symbol: String): String =
        twelveDataKeys[V1Universe.normalize(symbol)]?.takeIf { it.isNotBlank() } ?: apiKey

    fun hasKeyFor(symbol: String): Boolean = apiKeyFor(symbol).isNotBlank()

    fun dedicatedKeySymbols(): List<String> =
        twelveDataKeys.filterValues { it.isNotBlank() }.keys.sorted()

    val activeSymbols: List<String> get() = com.aurum.edge.data.WatchCatalog.scannerSymbols

    fun strategyFor(symbol: String): CategoryStrategy =
        categoryStrategies[AssetClass.of(symbol)] ?: CategoryStrategy.HYBRID
}
