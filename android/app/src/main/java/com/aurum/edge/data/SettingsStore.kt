package com.aurum.edge.data

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.aurum.edge.core.AppSettings
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.V1Universe
import com.aurum.edge.core.SignalProfile
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local settings. The Twelve Data key is a read-only market-data credential
 * (no trading rights), stored in app-private storage only and never logged.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE)

    // Retired in 1.3.55: an old enabled flag must not strand an upgraded install
    // waiting for a daemon which is no longer bundled. No other preferences are cleared.
    init {
        prefs.edit().remove("embedded_tor_enabled").apply()
        migrateAutoDownload() // owner-requested: in-app updating is on unless switched off
    }

    /**
     * In-app updating became default-on in 2.0.2. A stored `false` from an older build cannot be
     * told apart from "never chosen", so the preference is enabled once, recorded as migrated, and
     * left alone afterwards. Turning the switch off after this migration sticks.
     */
    private fun migrateAutoDownload() {
        if (prefs.getBoolean(KEY_AUTO_DOWNLOAD_MIGRATED, false)) return
        prefs.edit()
            .putBoolean(KEY_AUTO_DOWNLOAD_MIGRATED, true)
            .putBoolean(KEY_AUTO_DOWNLOAD_UPDATES, true)
            .apply()
    }

    // Declared BEFORE the first read(): read() parses the per-symbol key map, and a property
    // initialised later would still be null on that first call.
    private var symbolKeyCache: Pair<String, Map<String, String>>? = null

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Dedicated Twelve Data keys, one per catalog symbol. The free plan meters every key
     * separately, so a symbol with its own key is not starved by the other thirteen.
     * Anything unreadable (corrupt JSON, unknown symbol, whitespace) is dropped rather than
     * guessed: a bad entry silently means "use the global key" and never a made-up credential.
     */
    private fun readSymbolKeys(): Map<String, String> {
        val raw = prefs.getString(KEY_TD_KEYS, null).orEmpty()
        symbolKeyCache?.let { (cachedRaw, cached) -> if (cachedRaw == raw) return cached }
        val map: Map<String, String> = if (raw.isBlank()) emptyMap() else
            runCatching { KEY_JSON.decodeFromString(symbolKeySerializer, raw) }
                .getOrElse { emptyMap<String, String>() }
                .entries.mapNotNull { entry ->
                    val symbol = V1Universe.normalize(entry.key)
                    val key = entry.value.trim()
                    if (!V1Universe.valid(symbol) || key.isEmpty() || key.any { it.isWhitespace() }) null
                    else symbol to key
                }.toMap()
        symbolKeyCache = raw to map
        return map
    }

    /** Persist one dedicated key (blank removes it) as a single verified disk write. */
    @Synchronized
    fun saveSymbolKey(symbolInput: String, keyInput: String): Boolean {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return false
        val key = keyInput.trim()
        if (key.any { it.isWhitespace() }) return false
        val next = readSymbolKeys().toMutableMap()
        if (key.isBlank()) next.remove(symbol) else next[symbol] = key
        val encoded = KEY_JSON.encodeToString(symbolKeySerializer, next)
        if (!prefs.edit().putString(KEY_TD_KEYS, encoded).commit()) return false
        symbolKeyCache = null
        if (readSymbolKeys() != next) return false
        _settings.value = read()
        return true
    }

    /** The dedicated key for one symbol, or blank when it falls back to the global key. */
    fun symbolKey(symbol: String): String = readSymbolKeys()[V1Universe.normalize(symbol)].orEmpty()

    fun read(): AppSettings {
        // Legacy profile preferences remain readable for existing on-device records, but
        // they are not V1 controls and are not displayed as editable signal filters.
        // Four scored layers and absolute locks are fixed by the current contract.
        val profile = SignalProfile.BASE
        val storedApiKey = prefs.getString(KEY_API, null)?.trim()
        val storedNewsAiKey = prefs.getString(KEY_NEWS_AI_KEY, null)?.trim().orEmpty()
        val storedNewsAiUrl = prefs.getString(KEY_NEWS_AI_URL, null)?.trim().orEmpty()
        val storedNewsAiModel = prefs.getString(KEY_NEWS_AI_MODEL, null)?.trim().orEmpty()
        val storedStrategy = com.aurum.edge.core.StrategyKind.fromId(
            prefs.getString(KEY_ACTIVE_STRATEGY, "ICHIMOKU"))
            ?: com.aurum.edge.core.StrategyKind.ICHIMOKU_PRICE_ACTION
        // Retired v1_watchlist is intentionally ignored. It cannot narrow the app catalog.
        // AI/news fields are retired: stored values stay readable for old installs but nothing
        // in the app consumes them anymore (kept only so AppSettings keeps its stored shape).
        val aiUrl = storedNewsAiUrl
        val aiFormat = prefs.getString(KEY_NEWS_AI_FORMAT, "AUTO").orEmpty().ifBlank { "AUTO" }
        val aiModel = storedNewsAiModel
        val categoryStrategies = AssetClass.entries.associateWith { category ->
            CategoryStrategy.fromStored(prefs.getString("v1_strategy_${category.name}", null))
        }
        return AppSettings(
        apiKey = storedApiKey?.takeIf { it.isNotBlank() }
            ?: com.aurum.edge.BuildConfig.DEFAULT_TD_API_KEY.trim(),
        twelveDataKeys = readSymbolKeys(),
        // Symbol can be any instrument in the 15-symbol catalog (metals, Dow Jones, Ichimoku forex, top-5 crypto)
        symbol = V1Universe.normalize(prefs.getString(KEY_SYMBOL, null) ?: "XAU/USD")
            .takeIf(V1Universe::valid) ?: "XAU/USD",
        interval = Interval.M5,
        riskPercent = prefs.getFloat(KEY_RISK, 0.5f).toDouble().takeIf { it.isFinite() }?.coerceIn(0.1, 10.0) ?: 0.5,
        accountBalance = prefs.getFloat(KEY_BALANCE, 1000f).toDouble(),
        initialCapital = prefs.getFloat(KEY_INITIAL_CAPITAL, prefs.getFloat(KEY_BALANCE, 1000f)).toDouble()
            .takeIf { it.isFinite() && it >= 10.0 } ?: 1000.0,
        dailyLossLimitPercent = prefs.getFloat(KEY_DAILY_LOSS_LIMIT, 3.0f).toDouble()
            .takeIf { it.isFinite() }?.coerceIn(0.5, 25.0) ?: 3.0,
        maxDrawdownLimitPercent = prefs.getFloat(KEY_MAX_DRAWDOWN_LIMIT, 10.0f).toDouble()
            .takeIf { it.isFinite() }?.coerceIn(1.0, 50.0) ?: 10.0,
        minConfidence = prefs.getFloat(KEY_MIN_CONF, 85f).toDouble().takeIf { it.isFinite() }?.coerceIn(60.0, 95.0) ?: 85.0,
        spreadPrice = prefs.getFloat(KEY_SPREAD, 0.12f).toDouble(),
        commissionPerOz = prefs.getFloat(KEY_COMMISSION, 0.035f).toDouble(),
        backgroundMonitor = prefs.getBoolean(KEY_MONITOR, false),
        notifyOnSignal = prefs.getBoolean(KEY_NOTIFY, true),
        alertSoundUri = prefs.getString(KEY_ALERT_SOUND_URI, "").orEmpty(),
        alertSoundName = prefs.getString(KEY_ALERT_SOUND_NAME, "").orEmpty(),
        newsBaseUrl = prefs.getString(KEY_NEWS_URL, "").orEmpty().trim(),
        pauseOnNews = prefs.getBoolean(KEY_NEWS_PAUSE, false),
        autoPaperTrading = prefs.getBoolean(KEY_AUTO_PAPER, false),
        autoDownloadUpdates = prefs.getBoolean(KEY_AUTO_DOWNLOAD_UPDATES, true),
        newsAiApiKey = storedNewsAiKey,
        newsAiBaseUrl = aiUrl,
        newsAiModel = aiModel,
        newsAiFormat = aiFormat,
        signalProfile = profile,
        activeStrategy = storedStrategy,
        categoryStrategies = categoryStrategies,
    )
    }

    /**
     * Persist the optional market key and symbol in ONE disk transaction. A successful commit,
     * not merely an in-memory SharedPreferences.apply(), is required before reconnecting.
     * Empty input preserves an existing/default key; if no key exists it explicitly enters the
     * keyless public-feed mode instead of blocking the Yahoo/Swissquote connection.
     */
    @Synchronized
    fun saveMarketCredentials(keyInput: String, symbolInput: String): Boolean {
        val existing = read().apiKey.trim()
        val key = keyInput.trim().ifBlank { existing }
        if (key.any { it.isWhitespace() }) return false
        val symbol = V1Universe.normalize(symbolInput).ifBlank { "XAU/USD" }
        val valid = V1Universe.valid(symbol)
        if (!valid) return false
        val saved = prefs.edit().putString(KEY_API, key).putString(KEY_SYMBOL, symbol).commit()
        if (saved && prefs.getString(KEY_API, null).orEmpty() == key && prefs.getString(KEY_SYMBOL, null) == symbol) {
            _settings.value = read()
            return true
        }
        // Do not report success or restart the feed on a failed disk write.
        return false
    }

    /**
     * Switch ONLY the chart/signal symbol, without touching the stored key.
     * Allowed set is the instrument universe (gold, commodities, forex, crypto).
     */
    @Synchronized
    fun saveChartSymbol(symbolInput: String): Boolean {
        val symbol = V1Universe.normalize(symbolInput)
        val valid = V1Universe.valid(symbol)
        if (!valid) return false
        val saved = prefs.edit().putString(KEY_SYMBOL, symbol).commit()
        if (saved && prefs.getString(KEY_SYMBOL, null) == symbol) {
            _settings.value = read()
            return true
        }
        return false
    }

    /** Market-only archive on the user's own HTTPS host. Never send journal/account data.
     * The bearer token stays in app-private preferences, not in AppSettings/Compose state.
     */
    fun tickArchiveConfig(): Pair<String, String>? {
        val url = prefs.getString(KEY_TICK_ARCHIVE_URL, null)?.trim().orEmpty()
        val token = prefs.getString(KEY_TICK_ARCHIVE_TOKEN, null).orEmpty()
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null || parsed.encodedPath != "/" || token.length < 32)
            return null
        return url.trimEnd('/') to token
    }

    fun tickArchiveUrl(): String = prefs.getString(KEY_TICK_ARCHIVE_URL, null).orEmpty()
    fun hasTickArchive(): Boolean = tickArchiveConfig() != null

    @Synchronized
    fun saveTickArchive(urlInput: String, tokenInput: String): Boolean {
        val url = urlInput.trim().trimEnd('/')
        if (url.isBlank()) return prefs.edit().remove(KEY_TICK_ARCHIVE_URL)
            .remove(KEY_TICK_ARCHIVE_TOKEN).commit()
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null || parsed.encodedPath != "/") return false
        val token = tokenInput.trim().ifEmpty { prefs.getString(KEY_TICK_ARCHIVE_TOKEN, null).orEmpty() }
        if (token.length < 32 || token.any { it.isWhitespace() }) return false
        return prefs.edit().putString(KEY_TICK_ARCHIVE_URL, url)
            .putString(KEY_TICK_ARCHIVE_TOKEN, token).commit()
    }

    /**
     * Real-time continuous balance adjustment upon settled paper trade (profit or loss).
     * Ensures position sizing and compounding dynamically track actual account equity.
     */
    /** Stable UTC-day paper equity. Manual balance increases cannot enlarge today's loss cap.
     * Must be called before every balance mutation and again by the journal's final entry guard.
     */
    @Synchronized
    fun dayOpeningEquity(now: Long = System.currentTimeMillis()): Double {
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        if (prefs.getString("v1_risk_day", null) == day) {
            return prefs.getFloat("v1_risk_open_equity", 0f).toDouble()
        }
        val opening = read().accountBalance
        check(opening.isFinite() && opening > 0.0 && prefs.edit()
            .putString("v1_risk_day", day).putFloat("v1_risk_open_equity", opening.toFloat()).commit()) {
            "موجودی آغاز روز برای قفل ریسک ثبت نشد"
        }
        return opening
    }

    @Synchronized
    fun adjustBalance(deltaUsd: Double) {
        if (!deltaUsd.isFinite() || deltaUsd == 0.0) return
        dayOpeningEquity()
        val current = read()
        val updated = kotlin.math.round((current.accountBalance + deltaUsd) * 100.0) / 100.0
        val finalBalance = maxOf(10.0, updated)
        update { it.copy(accountBalance = finalBalance) }
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val proposed = transform(_settings.value)
        if (proposed.accountBalance != _settings.value.accountBalance) dayOpeningEquity()
        val next = proposed.copy(
            symbol = V1Universe.normalize(proposed.symbol).takeIf(V1Universe::valid) ?: "XAU/USD",
            interval = Interval.M5,
            riskPercent = proposed.riskPercent.takeIf { it.isFinite() }?.coerceIn(0.1, 10.0) ?: 0.5,
            initialCapital = proposed.initialCapital.takeIf { it.isFinite() && it >= 10.0 } ?: proposed.accountBalance.coerceAtLeast(10.0),
            dailyLossLimitPercent = proposed.dailyLossLimitPercent.takeIf { it.isFinite() }?.coerceIn(0.5, 25.0) ?: 3.0,
            maxDrawdownLimitPercent = proposed.maxDrawdownLimitPercent.takeIf { it.isFinite() }?.coerceIn(1.0, 50.0) ?: 10.0,
            minConfidence = proposed.minConfidence.takeIf { it.isFinite() }?.coerceIn(60.0, 95.0) ?: 85.0,
        )
        prefs.edit()
            .putString(KEY_API, next.apiKey.trim())
            .putString(KEY_SYMBOL, next.symbol.trim().ifBlank { "XAU/USD" })
            .putString(KEY_INTERVAL, next.interval.label)
            .putFloat(KEY_RISK, next.riskPercent.toFloat())
            .putFloat(KEY_BALANCE, next.accountBalance.toFloat())
            .putFloat(KEY_INITIAL_CAPITAL, next.initialCapital.toFloat())
            .putFloat(KEY_DAILY_LOSS_LIMIT, next.dailyLossLimitPercent.toFloat())
            .putFloat(KEY_MAX_DRAWDOWN_LIMIT, next.maxDrawdownLimitPercent.toFloat())
            .putFloat(KEY_MIN_CONF, next.minConfidence.toFloat())
            .putFloat(KEY_SPREAD, next.spreadPrice.toFloat())
            .putFloat(KEY_COMMISSION, next.commissionPerOz.toFloat())
            .putBoolean(KEY_MONITOR, next.backgroundMonitor)
            .putBoolean(KEY_NOTIFY, next.notifyOnSignal)
            .putString(KEY_ALERT_SOUND_URI, next.alertSoundUri)
            .putString(KEY_ALERT_SOUND_NAME, next.alertSoundName)
            .putString(KEY_NEWS_URL, next.newsBaseUrl.trim())
            .putString(KEY_SIGNAL_PROFILE, next.signalProfile.persistName())
            .putBoolean(KEY_SIGNAL_MOMENTUM_VOLUME, next.signalProfile.momentumVolume)
            .putBoolean(KEY_SIGNAL_FLAT_SPAN_B, next.signalProfile.flatSpanB)
            .putBoolean(KEY_SIGNAL_RANGE_CHOP, next.signalProfile.rangeChopFilter)
            .putBoolean(KEY_SIGNAL_HIGHER_TIMEFRAME, next.signalProfile.higherTimeframeFilter)
            .putBoolean(KEY_SIGNAL_FAKE_BREAKOUT, next.signalProfile.fakeBreakoutFilter)
            .putBoolean(KEY_SIGNAL_DYNAMIC_SPREAD, next.signalProfile.dynamicSpreadFilter)
            .putBoolean(KEY_SIGNAL_RISKY_TIMING, next.signalProfile.riskyTimingFilter)
            .putBoolean(KEY_SIGNAL_STRUCTURE_RISK, next.signalProfile.structureRiskFilter)
            .putBoolean(KEY_SIGNAL_COOLDOWN, next.signalProfile.cooldownFilter)
            .putBoolean(KEY_SIGNAL_CHIKOU, next.signalProfile.chikouConfirmation)
            .putString(KEY_ACTIVE_STRATEGY, next.activeStrategy.id)
            .apply {
                AssetClass.entries.forEach { category ->
                    putString("v1_strategy_${category.name}",
                        (next.categoryStrategies[category] ?: CategoryStrategy.HYBRID).name)
                }
            }
            .putBoolean(KEY_NEWS_PAUSE, next.pauseOnNews)
            .putBoolean(KEY_AUTO_PAPER, next.autoPaperTrading)
            .putBoolean(KEY_AUTO_DOWNLOAD_UPDATES, next.autoDownloadUpdates)
            .apply()
        _settings.value = next
    }

    fun setLastSync(symbol: String, interval: Interval, at: Long) {
        prefs.edit().putLong("last_sync_${symbol}_${interval.label}", at).apply()
    }

    companion object {
        /** Dedicated keys are a symbol→key JSON map; unknown keys are ignored, never guessed. */
        private val symbolKeySerializer = MapSerializer(String.serializer(), String.serializer())
        private val KEY_JSON = Json { ignoreUnknownKeys = true }
        private const val KEY_API = "td_api_key"
        private const val KEY_TD_KEYS = "td_symbol_keys_json"
        private const val KEY_SYMBOL = "symbol"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_RISK = "risk_percent"
        private const val KEY_BALANCE = "balance"
        private const val KEY_INITIAL_CAPITAL = "initial_capital"
        private const val KEY_DAILY_LOSS_LIMIT = "daily_loss_limit_percent"
        private const val KEY_MAX_DRAWDOWN_LIMIT = "max_drawdown_limit_percent"
        private const val KEY_MIN_CONF = "min_confidence"
        private const val KEY_SPREAD = "spread_price"
        private const val KEY_COMMISSION = "commission_per_oz"
        private const val KEY_MONITOR = "background_monitor"
        private const val KEY_NOTIFY = "notify_signal"
        private const val KEY_ALERT_SOUND_URI = "verified_alert_sound_uri"
        private const val KEY_ALERT_SOUND_NAME = "verified_alert_sound_name"
        private const val KEY_NEWS_URL = "news_base_url"
        private const val KEY_TICK_ARCHIVE_URL = "tick_archive_url"
        private const val KEY_TICK_ARCHIVE_TOKEN = "tick_archive_token"
        private const val KEY_NEWS_PAUSE = "pause_on_news"
        // Historical preference key must not be renamed: an existing opt-in survives an upgrade.
        private const val KEY_AUTO_PAPER = "auto_paper_nine_conditions"
        private const val KEY_AUTO_DOWNLOAD_UPDATES = "auto_download_updates"
        private const val KEY_AUTO_DOWNLOAD_MIGRATED = "auto_download_updates_migrated_2_0_2"
        private const val KEY_NEWS_AI_KEY = "news_ai_client_key"
        private const val KEY_NEWS_AI_URL = "news_ai_client_base_url"
        private const val KEY_NEWS_AI_MODEL = "news_ai_client_model"
        private const val KEY_NEWS_AI_FORMAT = "news_ai_format"
        private const val KEY_SIGNAL_PROFILE = "signal_profile"
        private const val KEY_SIGNAL_MOMENTUM_VOLUME = "signal_momentum_volume"
        private const val KEY_SIGNAL_FLAT_SPAN_B = "signal_flat_span_b"
        private const val KEY_SIGNAL_RANGE_CHOP = "signal_range_chop_filter"
        private const val KEY_SIGNAL_HIGHER_TIMEFRAME = "signal_higher_timeframe_filter"
        private const val KEY_SIGNAL_FAKE_BREAKOUT = "signal_fake_breakout_filter"
        private const val KEY_SIGNAL_DYNAMIC_SPREAD = "signal_dynamic_spread_filter"
        private const val KEY_SIGNAL_RISKY_TIMING = "signal_risky_timing_filter"
        private const val KEY_SIGNAL_STRUCTURE_RISK = "signal_structure_risk_filter"
        private const val KEY_SIGNAL_COOLDOWN = "signal_cooldown_filter"
        private const val KEY_SIGNAL_CHIKOU = "signal_chikou_confirmation"
        private const val KEY_ACTIVE_STRATEGY = "active_strategy"
    }
}
