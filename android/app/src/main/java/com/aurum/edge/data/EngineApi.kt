package com.aurum.edge.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

/**
 * Contract for this project's Python backend (`backend/app/main.py`).
 *
 * The phone's own Kotlin engine stays the default and is unchanged. This layer is an
 * OPTIONAL second opinion from a backend the user runs themselves: it adds the
 * multi-symbol instrument registry, the automatic risk sizing (which knows each venue's
 * real lot step and minimum notional) and the autonomous paper trader — none of which
 * exist on-device.
 *
 * Everything here is pure: URL construction, validation and parsing. No sockets, so the
 * whole contract is unit-testable without a server, in the same spirit as
 * [NewsRepository.apiUrl].
 *
 * Security posture, deliberately strict and matching the rest of the app:
 *  - only a fixed allowlist of paths may ever be requested,
 *  - the base URL may not carry credentials, a path, a query or a fragment,
 *  - HTTPS everywhere, with ONE exception: loopback and the Android emulator host, so a
 *    developer can point at a laptop without inventing a certificate. Any other cleartext
 *    host is rejected, because a trading endpoint on an open network is a real hazard.
 */
object EngineApi {

    /** Hosts allowed to speak plain HTTP. Everything else must be HTTPS. */
    private val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    /** Every endpoint the app is permitted to call. Anything else is a bug, not a feature. */
    val ALLOWED_PATHS = setOf(
        "instruments",
        "instruments/spec",
        "scalp/signal",
        "scalp/diagnose",
        "autopilot/state",
        "autopilot/start",
        "autopilot/stop",
        "autopilot/cycle",
        "health",
    )

    /**
     * Normalise a user-entered base URL, or null when it is not safe to call.
     *
     * Accepts `https://host[:port]` and bare `host` (assumed HTTPS). Rejects anything that
     * carries a path, query, fragment or user info, so a saved setting can never be
     * widened into an arbitrary request.
     */
    fun normalizeBase(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.length > 200) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank() || uri.rawUserInfo != null) return null
        if (uri.path.orEmpty() !in listOf("", "/")) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in listOf("http", "https")) return null
        if (scheme == "http" && host !in CLEARTEXT_HOSTS) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        val authority = if (port == -1) host else "$host:$port"
        return "$scheme://$authority"
    }

    /** Build a full endpoint URL, or null if the base or path is not acceptable. */
    fun url(base: String, path: String, params: List<Pair<String, String>> = emptyList()): String? {
        if (path !in ALLOWED_PATHS) return null
        val root = normalizeBase(base) ?: return null
        val query = params
            .filter { it.second.isNotBlank() }
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return "$root/api/v1/$path" + if (query.isEmpty()) "" else "?$query"
    }

    /** Percent-encode a query component; `URLEncoder` would turn a space into `+`. */
    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() || char in "-_.~") append(char)
            else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }

    // ── parsing ─────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /** Instrument rows for the symbol picker. Unparseable rows are dropped, never guessed. */
    fun parseInstruments(root: JsonObject): List<EngineInstrument> =
        (root["instruments"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val symbol = item.str("symbol") ?: return@mapNotNull null
            EngineInstrument(
                symbol = symbol,
                display = item.str("display") ?: symbol,
                kind = item.str("kind") ?: "unknown",
                scalpEnabled = item.bool("scalp_enabled") ?: false,
                pricePrecision = item.int("price_precision") ?: 5,
            )
        }

    /**
     * A scalp assessment. Returns null when the payload is not a well-formed response, so
     * the UI shows the backend's error rather than a half-populated card.
     */
    fun parseScalp(root: JsonObject): EngineScalp? {
        val symbol = root.str("symbol") ?: return null
        if (root.bool("ok") == false) {
            return EngineScalp(
                symbol = symbol,
                display = root.obj("spec")?.str("display") ?: symbol,
                timeframe = root.str("timeframe") ?: "",
                ok = false,
                error = root.str("error"),
            )
        }
        val signal = root.obj("signal") ?: return null
        val spec = root.obj("spec")
        val exit = root.obj("exit_plan")
        val risk = root.obj("risk")
        val sizing = risk?.obj("sizing") ?: root.obj("sizing")
        val context = root.obj("context")

        return EngineScalp(
            symbol = symbol,
            display = spec?.str("display") ?: symbol,
            timeframe = root.str("timeframe") ?: "",
            ok = true,
            bars = root.int("bars"),
            scalpAllowed = root.bool("scalp_allowed") ?: true,
            policyBlock = root.str("policy_block"),
            pricePrecision = spec?.int("price_precision") ?: 5,
            action = signal.str("action") ?: "NO_TRADE",
            confidence = signal.num("confidence") ?: 0.0,
            threshold = signal.num("threshold"),
            entry = signal.num("entry"),
            stopLoss = signal.num("stop_loss"),
            takeProfit = signal.num("take_profit"),
            riskReward = signal.num("risk_reward"),
            reasons = signal.strings("reasons"),
            blockers = signal.strings("blockers"),
            costUnit = context?.str("cost_unit") ?: exit?.str("cost_unit") ?: "pip",
            spreadUnits = context?.num("spread_pips"),
            roundTripCostUnits = context?.num("round_trip_cost_units"),
            htfBias = context?.str("htf_bias"),
            riskPct = risk?.num("risk_pct"),
            riskAuto = risk?.bool("auto") ?: true,
            reasoning = (risk?.get("reasoning") as? JsonArray).orEmpty().mapNotNull { element ->
                val step = element as? JsonObject ?: return@mapNotNull null
                EngineRiskStep(
                    factor = step.str("factor") ?: return@mapNotNull null,
                    effect = step.str("effect") ?: "",
                    why = step.str("why") ?: "",
                )
            },
            sizing = sizing?.let { box ->
                EngineSizing(
                    tradable = box.bool("tradable") ?: false,
                    reason = box.str("reason"),
                    units = box.num("units"),
                    unitLabel = box.str("unit_label") ?: "",
                    lots = box.num("lots"),
                    riskCash = box.num("risk_cash"),
                    riskPct = box.num("risk_pct"),
                    notional = box.num("notional"),
                    leverage = box.num("leverage"),
                    marginRequired = box.num("margin_required"),
                    executionCost = box.num("execution_cost"),
                    netReward = box.num("net_reward"),
                    netRr = box.num("net_rr"),
                    breakevenWinRate = box.num("breakeven_win_rate"),
                    minBalanceNeeded = box.num("min_balance_needed"),
                    warnings = box.strings("warnings"),
                    blockers = box.strings("blockers"),
                )
            },
            exitRules = exit?.strings("rules").orEmpty(),
            breakevenTrigger = exit?.num("breakeven_trigger"),
            lockTrigger = exit?.num("lock_trigger"),
            timeStopBars = exit?.int("time_stop_bars"),
        )
    }

    /** Autopilot snapshot: balance, open positions, recent closes and the decision log. */
    fun parseAutopilot(root: JsonObject): EngineAutopilot? {
        val performance = root.obj("performance") ?: return null
        return EngineAutopilot(
            running = root.bool("running") ?: false,
            executionMode = root.str("execution_mode") ?: "paper",
            executionNote = root.str("execution_note").orEmpty(),
            balance = root.num("balance") ?: 0.0,
            startingBalance = root.num("starting_balance") ?: 0.0,
            targetBalance = root.num("target_balance"),
            timeframe = root.str("timeframe").orEmpty(),
            watchlist = root.strings("watchlist"),
            trades = performance.int("trades") ?: 0,
            wins = performance.int("wins") ?: 0,
            losses = performance.int("losses") ?: 0,
            winRatePct = performance.num("win_rate_pct"),
            profitFactor = performance.num("profit_factor"),
            avgR = performance.num("avg_r"),
            returnPct = performance.num("return_pct") ?: 0.0,
            maxDrawdownPct = performance.num("max_drawdown_pct") ?: 0.0,
            confidenceNote = performance.str("confidence_note").orEmpty(),
            positions = (root["positions"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                EnginePosition(
                    id = item.str("id") ?: return@mapNotNull null,
                    display = item.str("display") ?: item.str("symbol").orEmpty(),
                    side = item.str("side") ?: "BUY",
                    qty = item.num("qty") ?: 0.0,
                    entry = item.num("entry") ?: 0.0,
                    stopLoss = item.num("stop_loss"),
                    takeProfit = item.num("take_profit"),
                    riskPct = item.num("risk_pct"),
                    unrealised = item.num("unrealised") ?: 0.0,
                )
            },
            closed = (root["closed"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                EngineClosedTrade(
                    symbol = item.str("symbol") ?: return@mapNotNull null,
                    side = item.str("side") ?: "",
                    netPnl = item.num("net_pnl") ?: 0.0,
                    fees = item.num("fees") ?: 0.0,
                    rMultiple = item.num("r_multiple"),
                    exitReason = item.str("exit_reason").orEmpty(),
                )
            },
            log = (root["log"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                EngineLogLine(
                    kind = item.str("kind") ?: "info",
                    message = item.str("message") ?: return@mapNotNull null,
                    at = item.str("at").orEmpty(),
                )
            },
        )
    }
}

// ── models ───────────────────────────────────────────────────────────────

data class EngineInstrument(
    val symbol: String,
    val display: String,
    val kind: String,
    val scalpEnabled: Boolean,
    val pricePrecision: Int,
)

data class EngineRiskStep(val factor: String, val effect: String, val why: String)

data class EngineSizing(
    val tradable: Boolean,
    val reason: String? = null,
    val units: Double? = null,
    val unitLabel: String = "",
    val lots: Double? = null,
    val riskCash: Double? = null,
    val riskPct: Double? = null,
    val notional: Double? = null,
    val leverage: Double? = null,
    val marginRequired: Double? = null,
    val executionCost: Double? = null,
    val netReward: Double? = null,
    val netRr: Double? = null,
    val breakevenWinRate: Double? = null,
    val minBalanceNeeded: Double? = null,
    val warnings: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
)

data class EngineScalp(
    val symbol: String,
    val display: String,
    val timeframe: String,
    val ok: Boolean,
    val error: String? = null,
    val bars: Int? = null,
    val scalpAllowed: Boolean = true,
    val policyBlock: String? = null,
    val pricePrecision: Int = 5,
    val action: String = "NO_TRADE",
    val confidence: Double = 0.0,
    val threshold: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val riskReward: Double? = null,
    val reasons: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val costUnit: String = "pip",
    val spreadUnits: Double? = null,
    val roundTripCostUnits: Double? = null,
    val htfBias: String? = null,
    val riskPct: Double? = null,
    val riskAuto: Boolean = true,
    val reasoning: List<EngineRiskStep> = emptyList(),
    val sizing: EngineSizing? = null,
    val exitRules: List<String> = emptyList(),
    val breakevenTrigger: Double? = null,
    val lockTrigger: Double? = null,
    val timeStopBars: Int? = null,
)

data class EnginePosition(
    val id: String,
    val display: String,
    val side: String,
    val qty: Double,
    val entry: Double,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val riskPct: Double?,
    val unrealised: Double,
)

data class EngineClosedTrade(
    val symbol: String,
    val side: String,
    val netPnl: Double,
    val fees: Double,
    val rMultiple: Double?,
    val exitReason: String,
)

data class EngineLogLine(val kind: String, val message: String, val at: String)

data class EngineAutopilot(
    val running: Boolean,
    val executionMode: String,
    val executionNote: String,
    val balance: Double,
    val startingBalance: Double,
    val targetBalance: Double?,
    val timeframe: String,
    val watchlist: List<String>,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double?,
    val profitFactor: Double?,
    val avgR: Double?,
    val returnPct: Double,
    val maxDrawdownPct: Double,
    val confidenceNote: String,
    val positions: List<EnginePosition>,
    val closed: List<EngineClosedTrade>,
    val log: List<EngineLogLine>,
)
