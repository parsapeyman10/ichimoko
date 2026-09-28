package com.aurum.edge.data

import android.content.Context
import android.content.SharedPreferences
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalProfile
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

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun read(): AppSettings = AppSettings(
        apiKey = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
            ?: com.aurum.edge.BuildConfig.DEFAULT_TD_API_KEY,
        // Legacy installs may still hold a removed symbol (crypto/stock); the app is forex-only now.
        symbol = (prefs.getString(KEY_SYMBOL, null) ?: "XAU/USD").takeIf { it in WatchCatalog.chartSymbols } ?: "XAU/USD",
        interval = Interval.fromLabel(prefs.getString(KEY_INTERVAL, null) ?: "5m"),
        riskPercent = prefs.getFloat(KEY_RISK, 0.5f).toDouble(),
        accountBalance = prefs.getFloat(KEY_BALANCE, 100f).toDouble(),
        minConfidence = prefs.getFloat(KEY_MIN_CONF, 72f).toDouble(),
        spreadPrice = prefs.getFloat(KEY_SPREAD, 0.30f).toDouble(),
        commissionPerOz = prefs.getFloat(KEY_COMMISSION, 0.05f).toDouble(),
        backgroundMonitor = prefs.getBoolean(KEY_MONITOR, false),
        notifyOnSignal = prefs.getBoolean(KEY_NOTIFY, true),
        alertSoundUri = prefs.getString(KEY_ALERT_SOUND_URI, "").orEmpty(),
        alertSoundName = prefs.getString(KEY_ALERT_SOUND_NAME, "").orEmpty(),
        newsBaseUrl = prefs.getString(KEY_NEWS_URL, "").orEmpty(),
        pauseOnNews = prefs.getBoolean(KEY_NEWS_PAUSE, false),
        autoPaperTrading = prefs.getBoolean(KEY_AUTO_PAPER, false),
        newsAiApiKey = prefs.getString(KEY_NEWS_AI_KEY, "").orEmpty(),
        newsAiBaseUrl = prefs.getString(KEY_NEWS_AI_URL, "").orEmpty(),
        newsAiModel = prefs.getString(KEY_NEWS_AI_MODEL, "").orEmpty(),
        newsAiFormat = prefs.getString(KEY_NEWS_AI_FORMAT, "AUTO").orEmpty().ifBlank { "AUTO" },
        signalProfile = SignalProfile.fromName(prefs.getString(KEY_SIGNAL_PROFILE, SignalProfile.BASE.name)),
    )

    /**
     * Persist the market key and symbol in ONE disk transaction. A successful commit,
     * not merely an in-memory SharedPreferences.apply(), is required before reconnecting.
     * Empty input preserves an existing key; it never silently erases credentials.
     */
    @Synchronized
    fun saveMarketCredentials(keyInput: String, symbolInput: String): Boolean {
        val key = keyInput.trim().ifBlank { read().apiKey }
        if (key.isBlank() || key.any { it.isWhitespace() }) return false
        val symbol = symbolInput.trim().uppercase(java.util.Locale.ROOT).ifBlank { "XAU/USD" }
        if (symbol !in WatchCatalog.chartSymbols) return false
        val saved = prefs.edit().putString(KEY_API, key).putString(KEY_SYMBOL, symbol).commit()
        if (saved && prefs.getString(KEY_API, null) == key && prefs.getString(KEY_SYMBOL, null) == symbol) {
            _settings.value = read()
            return true
        }
        // Do not report success or restart the feed on a failed disk write.
        return false
    }

    /**
     * Switch ONLY the chart/signal symbol, without touching the stored key. Allowed set is the
     * watch catalog (gold + major pairs); the write is commit-verified like every other setting.
     * Works keyless: the Swissquote fallback feed serves ticks for any catalog pair.
     */
    @Synchronized
    fun saveChartSymbol(symbolInput: String): Boolean {
        val symbol = symbolInput.trim().uppercase(java.util.Locale.ROOT)
        if (symbol !in WatchCatalog.chartSymbols) return false
        val saved = prefs.edit().putString(KEY_SYMBOL, symbol).commit()
        if (saved && prefs.getString(KEY_SYMBOL, null) == symbol) {
            _settings.value = read()
            return true
        }
        return false
    }

    /** Server configuration must also survive process death before we say it was saved. */
    @Synchronized
    fun saveNewsBaseUrl(url: String): Boolean {
        val saved = prefs.edit().putString(KEY_NEWS_URL, url).commit()
        if (saved && prefs.getString(KEY_NEWS_URL, null) == url) {
            _settings.value = read()
            return true
        }
        return false
    }

    /**
     * User's OWN key for a DIRECT-FROM-PHONE AI news call (Anthropic or OpenAI-compatible,
     * see the format parameter), replacing the need
     * for a self-hosted backend for the Forex ninth-condition gate. Explicit, on-device only;
     * never logged. An empty [apiKey] or [baseUrl] or [model] clears client mode entirely (falls
     * back to [newsBaseUrl] server mode, or UNKNOWN if neither is configured).
     */
    @Synchronized
    fun saveNewsAiConfig(apiKey: String, baseUrl: String, model: String, format: String = "AUTO"): Boolean {
        val key = apiKey.trim()
        val url = baseUrl.trim()
        val modelName = model.trim()
        val wireFormat = format.trim().uppercase(java.util.Locale.ROOT).let {
            if (it in setOf("ANTHROPIC", "OPENAI")) it else "AUTO" }
        if (key.isNotBlank() && (url.isBlank() || !url.startsWith("https://") || modelName.isBlank())) return false
        val saved = prefs.edit()
            .putString(KEY_NEWS_AI_KEY, key)
            .putString(KEY_NEWS_AI_URL, url)
            .putString(KEY_NEWS_AI_MODEL, modelName)
            .putString(KEY_NEWS_AI_FORMAT, wireFormat)
            .commit()
        if (saved && prefs.getString(KEY_NEWS_AI_KEY, null) == key) {
            _settings.value = read()
            return true
        }
        return false
    }

    @Synchronized
    fun clearNewsAiConfig(): Boolean {
        val saved = prefs.edit().remove(KEY_NEWS_AI_KEY).remove(KEY_NEWS_AI_URL)
            .remove(KEY_NEWS_AI_MODEL).remove(KEY_NEWS_AI_FORMAT).commit()
        if (saved && prefs.getString(KEY_NEWS_AI_KEY, null) == null) {
            _settings.value = read()
            return true
        }
        return false
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_API, next.apiKey.trim())
            .putString(KEY_SYMBOL, next.symbol.trim().ifBlank { "XAU/USD" })
            .putString(KEY_INTERVAL, next.interval.label)
            .putFloat(KEY_RISK, next.riskPercent.toFloat())
            .putFloat(KEY_BALANCE, next.accountBalance.toFloat())
            .putFloat(KEY_MIN_CONF, next.minConfidence.toFloat())
            .putFloat(KEY_SPREAD, next.spreadPrice.toFloat())
            .putFloat(KEY_COMMISSION, next.commissionPerOz.toFloat())
            .putBoolean(KEY_MONITOR, next.backgroundMonitor)
            .putBoolean(KEY_NOTIFY, next.notifyOnSignal)
            .putString(KEY_ALERT_SOUND_URI, next.alertSoundUri)
            .putString(KEY_ALERT_SOUND_NAME, next.alertSoundName)
            .putString(KEY_NEWS_URL, next.newsBaseUrl.trim())
            .putString(KEY_SIGNAL_PROFILE, next.signalProfile.name)
            .putBoolean(KEY_NEWS_PAUSE, next.pauseOnNews)
            .putBoolean(KEY_AUTO_PAPER, next.autoPaperTrading)
            .apply()
        _settings.value = next
    }

    fun setLastSync(symbol: String, interval: Interval, at: Long) {
        prefs.edit().putLong("last_sync_${symbol}_${interval.label}", at).apply()
    }

    fun lastSync(symbol: String, interval: Interval): Long? =
        prefs.getLong("last_sync_${symbol}_${interval.label}", 0L).takeIf { it > 0L }

    companion object {
        private const val KEY_API = "td_api_key"
        private const val KEY_SYMBOL = "symbol"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_RISK = "risk_percent"
        private const val KEY_BALANCE = "balance"
        private const val KEY_MIN_CONF = "min_confidence"
        private const val KEY_SPREAD = "spread_price"
        private const val KEY_COMMISSION = "commission_per_oz"
        private const val KEY_MONITOR = "background_monitor"
        private const val KEY_NOTIFY = "notify_signal"
        private const val KEY_ALERT_SOUND_URI = "verified_alert_sound_uri"
        private const val KEY_ALERT_SOUND_NAME = "verified_alert_sound_name"
        private const val KEY_NEWS_URL = "news_base_url"
        private const val KEY_NEWS_PAUSE = "pause_on_news"
        private const val KEY_AUTO_PAPER = "auto_paper_nine_conditions"
        private const val KEY_NEWS_AI_KEY = "news_ai_client_key"
        private const val KEY_NEWS_AI_URL = "news_ai_client_base_url"
        private const val KEY_NEWS_AI_MODEL = "news_ai_client_model"
        private const val KEY_NEWS_AI_FORMAT = "news_ai_format"
        private const val KEY_SIGNAL_PROFILE = "signal_profile"
    }
}
