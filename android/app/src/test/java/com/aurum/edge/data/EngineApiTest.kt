package com.aurum.edge.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the optional server engine.
 *
 * Two things are being protected here. First, the URL layer: a saved setting must never be
 * widenable into an arbitrary request, and a trading endpoint must never be reachable over
 * plaintext on an open network. Second, the parsers: a malformed or error payload has to
 * surface as an explicit failure, never as a half-filled trade card that looks actionable.
 */
class EngineApiTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    // ---------- base URL validation ----------

    @Test fun `bare host is upgraded to https and trailing slash removed`() {
        assertEquals("https://my-host.example", EngineApi.normalizeBase("my-host.example"))
        assertEquals("https://my-host.example", EngineApi.normalizeBase("https://my-host.example/"))
        assertEquals("https://my-host.example", EngineApi.normalizeBase("  https://my-host.example  "))
    }

    @Test fun `explicit port is preserved`() {
        assertEquals("https://my-host.example:8443", EngineApi.normalizeBase("https://my-host.example:8443"))
    }

    @Test fun `cleartext is allowed only for loopback and the emulator host`() {
        assertEquals("http://localhost:8000", EngineApi.normalizeBase("http://localhost:8000"))
        assertEquals("http://127.0.0.1:8000", EngineApi.normalizeBase("http://127.0.0.1:8000"))
        assertEquals("http://10.0.2.2:8000", EngineApi.normalizeBase("http://10.0.2.2:8000"))
        // A LAN or public host over plaintext is refused: trading traffic must not be sniffable.
        assertNull(EngineApi.normalizeBase("http://192.168.1.10:8000"))
        assertNull(EngineApi.normalizeBase("http://my-host.example"))
    }

    @Test fun `credentials paths queries and fragments are refused`() {
        assertNull(EngineApi.normalizeBase("https://user:pass@my-host.example"))
        assertNull(EngineApi.normalizeBase("https://my-host.example/some/path"))
        assertNull(EngineApi.normalizeBase("https://my-host.example?token=abc"))
        assertNull(EngineApi.normalizeBase("https://my-host.example#frag"))
    }

    @Test fun `junk input is refused rather than guessed`() {
        assertNull(EngineApi.normalizeBase(""))
        assertNull(EngineApi.normalizeBase("   "))
        assertNull(EngineApi.normalizeBase("ftp://my-host.example"))
        // Regression: trimming the trailing slashes first turned this into "https:",
        // which was then re-prefixed into the bogus host "https://https".
        assertNull(EngineApi.normalizeBase("https://"))
        assertNull(EngineApi.normalizeBase("https:"))
        assertNull(EngineApi.normalizeBase("//"))
        assertNull(EngineApi.normalizeBase("intranet"))      // dotless host, likely a typo
        assertNull(EngineApi.normalizeBase("h ttp://broken"))
        assertNull(EngineApi.normalizeBase("x".repeat(500)))
    }

    // ---------- endpoint construction ----------

    @Test fun `only allowlisted paths can be requested`() {
        assertNotNull(EngineApi.url("https://h.example", "scalp/signal"))
        assertNull(EngineApi.url("https://h.example", "execution/orders"))
        assertNull(EngineApi.url("https://h.example", "../../etc/passwd"))
        assertNull(EngineApi.url("https://h.example", "autopilot/reset"))
    }

    @Test fun `query parameters are percent encoded and blanks dropped`() {
        val url = EngineApi.url("https://h.example", "scalp/signal", listOf(
            "symbol" to "EUR/USD", "timeframe" to "5m", "q" to "",
        ))
        assertEquals("https://h.example/api/v1/scalp/signal?symbol=EUR%2FUSD&timeframe=5m", url)
    }

    @Test fun `an invalid base makes every url null`() {
        assertNull(EngineApi.url("http://192.168.1.10", "health"))
    }

    // ---------- scalp parsing ----------

    @Test fun `a full scalp payload is mapped including automatic sizing and reasoning`() {
        val scalp = EngineApi.parseScalp(obj("""
            {
              "symbol": "EURUSD", "timeframe": "5m", "ok": true, "bars": 400,
              "scalp_allowed": true, "policy_block": null,
              "spec": {"display": "EUR/USD", "price_precision": 5},
              "context": {"cost_unit": "pip", "spread_pips": 0.9, "round_trip_cost_units": 1.8, "htf_bias": "BUY"},
              "signal": {
                "action": "BUY", "confidence": 88.0, "threshold": 73.0,
                "entry": 1.08345, "stop_loss": 1.08225, "take_profit": 1.08535,
                "risk_reward": 1.58, "reasons": ["cross"], "blockers": []
              },
              "exit_plan": {"rules": ["۱R → بریک‌اون"], "breakeven_trigger": 1.08465, "time_stop_bars": 10},
              "risk": {
                "auto": true, "risk_pct": 0.65,
                "reasoning": [{"factor": "پروفایل ریسک", "effect": "پایه 0.5%", "why": "متعادل"}],
                "sizing": {
                  "tradable": true, "units": 9000.0, "unit_label": "واحد پایه", "lots": 0.09,
                  "risk_cash": 10.8, "risk_pct": 0.54, "notional": 9751.0, "leverage": 4.88,
                  "margin_required": 48.76, "execution_cost": 1.62, "net_reward": 15.48,
                  "net_rr": 1.43, "breakeven_win_rate": 41.1, "warnings": ["گرد شده"]
                }
              }
            }
        """))
        assertNotNull(scalp)
        requireNotNull(scalp)
        assertTrue(scalp.ok)
        assertEquals("EUR/USD", scalp.display)
        assertEquals(5, scalp.pricePrecision)
        assertEquals("BUY", scalp.action)
        assertEquals(1.08345, scalp.entry!!, 1e-9)
        assertEquals(0.65, scalp.riskPct!!, 1e-9)
        assertEquals(1, scalp.reasoning.size)
        assertEquals("پروفایل ریسک", scalp.reasoning.first().factor)
        assertTrue(scalp.sizing!!.tradable)
        assertEquals(0.09, scalp.sizing!!.lots!!, 1e-9)
        assertEquals(41.1, scalp.sizing!!.breakevenWinRate!!, 1e-9)
        assertEquals(listOf("گرد شده"), scalp.sizing!!.warnings)
        assertEquals(10, scalp.timeStopBars ?: 0)
    }

    @Test fun `an untradable size keeps the reason and the balance actually required`() {
        val scalp = EngineApi.parseScalp(obj("""
            {
              "symbol": "EURUSD", "timeframe": "5m", "ok": true,
              "spec": {"display": "EUR/USD", "price_precision": 5},
              "signal": {"action": "BUY", "confidence": 80.0, "entry": 1.085, "stop_loss": 1.0838},
              "risk": {"sizing": {"tradable": false, "reason": "حداقل حجم بیشتر از بودجه است",
                                  "min_balance_needed": 208.7}}
            }
        """))
        requireNotNull(scalp)
        assertFalse(scalp.sizing!!.tradable)
        assertEquals(208.7, scalp.sizing!!.minBalanceNeeded!!, 1e-9)
    }

    @Test fun `an ok-false payload becomes an explicit error, not an empty trade card`() {
        val scalp = EngineApi.parseScalp(obj("""
            {"symbol": "EURUSD", "timeframe": "5m", "ok": false, "error": "کندل کافی نیست"}
        """))
        requireNotNull(scalp)
        assertFalse(scalp.ok)
        assertEquals("کندل کافی نیست", scalp.error)
        assertNull(scalp.entry)
    }

    @Test fun `a payload without a signal block is rejected outright`() {
        assertNull(EngineApi.parseScalp(obj("""{"symbol": "EURUSD", "ok": true}""")))
        assertNull(EngineApi.parseScalp(obj("""{"ok": true}""")))
    }

    @Test fun `crypto cost unit is carried through so the ui never prints pips for bps`() {
        val scalp = EngineApi.parseScalp(obj("""
            {
              "symbol": "BTCUSDT", "timeframe": "5m", "ok": true,
              "spec": {"display": "BTC/USDT", "price_precision": 2},
              "context": {"cost_unit": "bp", "spread_pips": 2.0, "round_trip_cost_units": 24.0},
              "signal": {"action": "SELL", "confidence": 79.0, "entry": 64000.0}
            }
        """))
        requireNotNull(scalp)
        assertEquals("bp", scalp.costUnit)
        assertEquals(24.0, scalp.roundTripCostUnits!!, 1e-9)
        assertEquals(2, scalp.pricePrecision)
    }

    // ---------- instruments ----------

    @Test fun `instrument rows parse and unusable rows are dropped`() {
        val rows = EngineApi.parseInstruments(obj("""
            {"instruments": [
              {"symbol": "EUR/USD", "display": "EUR/USD", "kind": "forex", "scalp_enabled": true, "price_precision": 5},
              {"display": "broken row with no symbol"},
              {"symbol": "XAU/USD", "kind": "metal", "scalp_enabled": false, "price_precision": 2}
            ]}
        """))
        assertEquals(2, rows.size)
        assertTrue(rows[0].scalpEnabled)
        assertFalse(rows[1].scalpEnabled)
        assertEquals("XAU/USD", rows[1].display)   // falls back to the symbol
    }

    // ---------- autopilot ----------

    @Test fun `autopilot snapshot maps performance positions and log`() {
        val auto = EngineApi.parseAutopilot(obj("""
            {
              "running": true, "execution_mode": "paper", "execution_note": "فقط کاغذی",
              "balance": 1006.1, "starting_balance": 1000.0, "target_balance": 10000.0,
              "timeframe": "5m", "watchlist": ["BTCUSDT"],
              "positions": [{"id": "a1", "display": "BTC/USDT", "side": "BUY", "qty": 0.03,
                             "entry": 79722.75, "stop_loss": 79563.0, "take_profit": 80094.0,
                             "risk_pct": 0.45, "unrealised": 2.5}],
              "closed": [{"symbol": "BTCUSDT", "side": "BUY", "net_pnl": 6.1, "fees": 4.79,
                          "r_multiple": 1.34, "exit_reason": "Take-profit reached"}],
              "log": [{"kind": "entry", "message": "BUY BTC/USDT", "at": "2026-10-03T12:00:00Z"}],
              "performance": {"trades": 1, "wins": 1, "losses": 0, "win_rate_pct": 100.0,
                              "profit_factor": null, "avg_r": 1.34, "return_pct": 0.61,
                              "max_drawdown_pct": 0.0, "confidence_note": "نمونه کم است"}
            }
        """))
        requireNotNull(auto)
        assertTrue(auto.running)
        assertEquals("paper", auto.executionMode)
        assertEquals(1, auto.positions.size)
        assertEquals("BTC/USDT", auto.positions.first().display)
        assertEquals(6.1, auto.closed.first().netPnl, 1e-9)
        assertEquals(4.79, auto.closed.first().fees, 1e-9)
        assertEquals(1.34, auto.avgR!!, 1e-9)
        assertNull(auto.profitFactor)            // JSON null must stay null, not become 0
        assertEquals("نمونه کم است", auto.confidenceNote)
        assertEquals(1, auto.log.size)
    }

    @Test fun `autopilot payload without performance is rejected`() {
        assertNull(EngineApi.parseAutopilot(obj("""{"running": true, "balance": 100.0}""")))
    }

    @Test fun `the real order endpoint is not reachable from the app by construction`() {
        // The backend fails this closed anyway; the app must not even be able to form the URL.
        listOf("execution/orders", "execution/preflight", "brokers").forEach { path ->
            assertNull(path, EngineApi.url("https://h.example", path))
        }
    }
}
