"""
Autonomous paper trader — the supervisor that watches the market and acts on its own.

This is the "AI manages it" layer. On every cycle it:

  1. checks the global guardrails (daily loss limit, losing-streak cooldown, news blackout,
     weekend, max concurrent positions),
  2. manages every open position first — stop, target, Kijun invalidation, time stop and
     the breakeven/lock/trail ladder,
  3. only then scans the watchlist for new entries, ranked by conviction,
  4. sizes each candidate with the automatic risk advisor,
  5. opens the position and writes a full audit record of WHY.

Two boundaries that are deliberate and not negotiable here:

* **It trades on paper.** Every fill is simulated against real closed candles and charged
  the venue's real spread and commission. It never contacts a broker. Real order
  submission stays fail-closed in `app.services.execution_gate`, which requires server-side
  auth, durable idempotency, reconciliation and an audited release before it can ever send
  anything. Wiring an autonomous loop to a live account without those is how accounts get
  destroyed, so the loop ends at the journal.

* **It cannot guarantee a win rate.** Nothing can. What it can do is apply the same rules
  every time without fear, boredom or revenge-trading, refuse trades that do not clear
  cost, and stop itself when the account is bleeding. The measured results it accumulates
  here are the only honest basis for deciding whether the edge is real.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.config import Settings
from app.models import Candle, Direction, Timeframe, TradeSignal
from app.services import instruments as inst
from app.services import universe
from app.services.history import DataUnavailable
from app.services.indicators import atr, ichimoku
from app.services.risk_advisor import DAILY_LOSS_LIMIT_PCT, AccountState, advise
from app.services.scalper import MIN_BARS, build_context
from app.services.strategy import evaluate_scalp, get_trailing_stop, should_exit

STORE = Path(__file__).resolve().parent.parent / "data" / "autopilot.json"
STORE.parent.mkdir(parents=True, exist_ok=True)

DEFAULT_WATCHLIST = ["EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "BTCUSDT", "ETHUSDT", "SOLUSDT"]

MAX_CONCURRENT = 3
COOLDOWN_AFTER_LOSSES = 4
MAX_LOG_ENTRIES = 400
MAX_CLOSED_TRADES = 1000


# ─────────────────────────────────────────────────────────────────────────
# State
# ─────────────────────────────────────────────────────────────────────────

def _blank_state(balance: float = 1000.0) -> dict[str, Any]:
    return {
        "enabled": False,
        "starting_balance": balance,
        "balance": balance,
        "target_balance": balance * 10,
        "profile": "balanced",
        "timeframe": "5m",
        "watchlist": list(DEFAULT_WATCHLIST),
        "positions": [],
        "closed": [],
        "log": [],
        "equity_curve": [{"at": datetime.now(timezone.utc).isoformat(), "balance": balance}],
        "day": datetime.now(timezone.utc).date().isoformat(),
        "day_start_balance": balance,
        "consecutive_losses": 0,
        "cycles": 0,
        "last_cycle_at": None,
        "created_at": datetime.now(timezone.utc).isoformat(),
    }


def load_state() -> dict[str, Any]:
    if not STORE.exists():
        return _blank_state()
    try:
        state = json.loads(STORE.read_text())
    except Exception:
        return _blank_state()
    if not isinstance(state, dict) or "balance" not in state:
        return _blank_state()
    return {**_blank_state(state.get("starting_balance", 100.0)), **state}


def save_state(state: dict[str, Any]) -> None:
    state["positions"] = state.get("positions", [])[:50]
    state["closed"] = state.get("closed", [])[-MAX_CLOSED_TRADES:]
    state["log"] = state.get("log", [])[-MAX_LOG_ENTRIES:]
    state["equity_curve"] = state.get("equity_curve", [])[-2000:]
    STORE.write_text(json.dumps(state, ensure_ascii=False, indent=2, default=str))


def reset(balance: float = 1000.0, target: float | None = None, watchlist: list[str] | None = None,
          timeframe: str = "5m", profile: str = "balanced") -> dict[str, Any]:
    state = _blank_state(balance)
    state["target_balance"] = target if target else balance * 10
    state["timeframe"] = timeframe
    state["profile"] = profile
    if watchlist:
        state["watchlist"] = [inst.normalize(s) for s in watchlist][:20]
    save_state(state)
    return state


def _log(state: dict, kind: str, message: str, detail: dict | None = None) -> None:
    state.setdefault("log", []).append({
        "at": datetime.now(timezone.utc).isoformat(),
        "kind": kind,
        "message": message,
        "detail": detail or {},
    })


def _roll_day(state: dict) -> None:
    today = datetime.now(timezone.utc).date().isoformat()
    if state.get("day") != today:
        state["day"] = today
        state["day_start_balance"] = state["balance"]
        _log(state, "day", f"روز معاملاتی جدید — بودجهٔ ضرر روزانه بازنشانی شد (موجودی {state['balance']:.2f}$)")


def _daily_pnl_pct(state: dict) -> float:
    start = state.get("day_start_balance") or state["balance"]
    if start <= 0:
        return 0.0
    return (state["balance"] - start) / start * 100


# ─────────────────────────────────────────────────────────────────────────
# Position lifecycle
# ─────────────────────────────────────────────────────────────────────────

def _fill_price(spec: inst.InstrumentSpec, price: float, side: str, opening: bool) -> float:
    """Charge the real half-spread on entry and exit — paper fills must not be free."""
    half = spec.default_spread(price) / 2
    if side == "BUY":
        return price + half if opening else price - half
    return price - half if opening else price + half


def _commission(spec: inst.InstrumentSpec, price: float, qty: float) -> float:
    if not spec.model.taker_fee_bps:
        return 0.0
    return price * qty * (spec.model.taker_fee_bps / 10_000)


async def _close_position(state: dict, position: dict, exit_price: float, reason: str,
                          spec: inst.InstrumentSpec) -> None:
    side = position["side"]
    qty = position["qty"]
    fill = _fill_price(spec, exit_price, side, opening=False)
    gross = (fill - position["entry"]) * qty if side == "BUY" else (position["entry"] - fill) * qty
    fee = _commission(spec, fill, qty)
    net = gross - fee - position.get("entry_fee", 0.0)

    state["balance"] += net
    state["consecutive_losses"] = 0 if net > 0 else state.get("consecutive_losses", 0) + 1

    record = {
        **position,
        "exit": spec.round_price(fill),
        "exit_reason": reason,
        "closed_at": datetime.now(timezone.utc).isoformat(),
        "gross_pnl": round(gross, 4),
        "fees": round(fee + position.get("entry_fee", 0.0), 4),
        "net_pnl": round(net, 4),
        "r_multiple": round(net / position["risk_cash"], 2) if position.get("risk_cash") else None,
        "balance_after": round(state["balance"], 2),
    }
    state["closed"].append(record)
    state["positions"] = [p for p in state["positions"] if p["id"] != position["id"]]
    state["equity_curve"].append({
        "at": record["closed_at"], "balance": round(state["balance"], 2),
    })
    _log(state, "exit",
         f"{'سود' if net > 0 else 'ضرر'} {net:+.2f}$ روی {position['symbol']} — {reason}",
         {"symbol": position["symbol"], "net": round(net, 2), "r": record["r_multiple"]})


async def _manage_open_positions(settings: Settings, state: dict) -> None:
    """Open positions are always handled before new entries are even considered."""
    for position in list(state.get("positions", [])):
        timeframe = Timeframe(position["timeframe"])
        try:
            candles, spec = await universe.load_candles(
                settings, position["symbol"], timeframe, limit=MIN_BARS,
            )
        except (DataUnavailable, ValueError) as exc:
            _log(state, "warn", f"دادهٔ {position['symbol']} برای مدیریت پوزیشن در دسترس نیست: {exc}")
            continue

        opened_at = datetime.fromisoformat(position["opened_at"])
        since = [c for c in candles if c.timestamp > opened_at]
        if not since:
            continue

        values = ichimoku(candles, 9, 26, 52, 26)
        kijun = values["kijun"][-1] or position["entry"]
        atr_now = atr(candles, 14)[-1] or 0.0

        signal = TradeSignal(
            action=Direction(position["side"]),
            confidence=position.get("confidence", 80),
            entry=position["entry"],
            stop_loss=position["stop_loss"],
            take_profit=position["take_profit"],
        )

        # 1) Trail the stop before testing exits, exactly as a disciplined trader would.
        moved = get_trailing_stop(
            signal, since, kijun=float(kijun), atr=float(atr_now),
            initial_stop=position["initial_stop"], spec=spec,
        )
        if moved is not None and moved != position["stop_loss"]:
            position["stop_loss"] = moved
            signal.stop_loss = moved
            _log(state, "trail", f"حد ضرر {position['symbol']} به {moved} منتقل شد")

        # 2) Exit policy on closed bars.
        exit_now, reason = should_exit(signal, since, float(kijun), spec)
        if exit_now:
            last = since[-1]
            if position["side"] == "BUY":
                price = position["stop_loss"] if last.low <= (position["stop_loss"] or 0) else (
                    position["take_profit"] if position["take_profit"] and last.high >= position["take_profit"]
                    else last.close)
            else:
                price = position["stop_loss"] if last.high >= (position["stop_loss"] or 1e18) else (
                    position["take_profit"] if position["take_profit"] and last.low <= position["take_profit"]
                    else last.close)
            await _close_position(state, position, float(price), reason, spec)
        else:
            position["bars_held"] = len(since)
            position["unrealised"] = round(
                ((since[-1].close - position["entry"]) if position["side"] == "BUY"
                 else (position["entry"] - since[-1].close)) * position["qty"], 2)


# ─────────────────────────────────────────────────────────────────────────
# Guardrails
# ─────────────────────────────────────────────────────────────────────────

async def _blocking_reasons(settings: Settings, state: dict) -> list[str]:
    reasons: list[str] = []
    daily = _daily_pnl_pct(state)
    if daily <= -DAILY_LOSS_LIMIT_PCT:
        reasons.append(f"سقف ضرر روزانه ({DAILY_LOSS_LIMIT_PCT}%) مصرف شده — امروز ورود جدید نداریم")
    if state.get("consecutive_losses", 0) >= COOLDOWN_AFTER_LOSSES:
        reasons.append(f"{state['consecutive_losses']} باخت پیاپی — استراحت اجباری تا یک برد یا شروع روز بعد")
    if len(state.get("positions", [])) >= MAX_CONCURRENT:
        reasons.append(f"سقف {MAX_CONCURRENT} پوزیشن همزمان پر است")
    if state["balance"] <= state["starting_balance"] * 0.5:
        reasons.append("موجودی به نصف سرمایهٔ اولیه رسیده — اتوپایلوت متوقف شد")

    # News blackout: a high-impact release invalidates technical entries for both FX and gold.
    try:
        from app.services.news_feed import NewsAggregator
        from app.services.forex_calendar import calendar_guard
        aggregator = NewsAggregator(settings)
        events = await aggregator.fetch_calendar()
        status = aggregator.status()
        guard = calendar_guard(
            events, bool(status.get("calendar_online", True)), datetime.now(timezone.utc),
            settings.news_hold_minutes,
        )
        if guard["state"] == "BLOCKED":
            reasons.append(f"پنجرهٔ خبر پراثر — {guard['reason']}")
    except Exception:
        # A calendar outage must not silently remove a safety gate, but it also must not
        # halt trading forever; it is recorded and treated as unknown.
        _log(state, "warn", "تقویم اقتصادی در دسترس نیست — فیلتر خبر در این سیکل اعمال نشد")

    return reasons


# ─────────────────────────────────────────────────────────────────────────
# Entries
# ─────────────────────────────────────────────────────────────────────────

async def _consider_entry(settings: Settings, state: dict, symbol: str,
                          timeframe: Timeframe) -> dict | None:
    """Evaluate one symbol. Returns an audit record describing the decision."""
    if any(p["symbol"] == symbol for p in state.get("positions", [])):
        return {"symbol": symbol, "decision": "skip", "why": "پوزیشن باز روی همین نماد"}

    try:
        candles, spec = await universe.load_candles(settings, symbol, timeframe, limit=400)
    except (DataUnavailable, ValueError) as exc:
        return {"symbol": symbol, "decision": "error", "why": str(exc)}

    if not spec.is_scalpable():
        return {"symbol": symbol, "decision": "skip", "why": "اسکلپ روی این نماد مجاز نیست"}
    if len(candles) < MIN_BARS:
        return {"symbol": symbol, "decision": "skip", "why": f"کندل کافی نیست ({len(candles)})"}

    context, _ = await build_context(settings, spec, timeframe, candles, state["balance"])
    signal = evaluate_scalp(candles, context, spec)
    if signal.action not in (Direction.BUY, Direction.SELL):
        hard = [b for b in signal.blockers if b.startswith("Hard gate")]
        return {
            "symbol": symbol, "decision": "no_trade",
            "confidence": signal.confidence,
            "why": hard[0] if hard else f"امتیاز {signal.confidence} زیر آستانهٔ {spec.model.threshold(timeframe.value)}",
        }

    account = AccountState(
        balance=state["balance"],
        profile=state.get("profile", "balanced"),
        daily_pnl_pct=_daily_pnl_pct(state),
        consecutive_losses=state.get("consecutive_losses", 0),
        open_positions=len(state.get("positions", [])),
    )
    risk = advise(spec, signal, account, context.volatility_regime, timeframe.value)
    sizing = risk["sizing"]
    if not sizing.get("tradable"):
        return {
            "symbol": symbol, "decision": "unsizable",
            "confidence": signal.confidence,
            "why": sizing.get("reason") or (sizing.get("blockers") or ["قابل معامله نیست"])[0],
        }

    # ── open the paper position ──
    entry_fill = _fill_price(spec, float(signal.entry), signal.action.value, opening=True)
    entry_fee = _commission(spec, entry_fill, sizing["units"])
    position = {
        "id": str(uuid.uuid4())[:8],
        "symbol": spec.symbol,
        "display": spec.display,
        "side": signal.action.value,
        "timeframe": timeframe.value,
        "qty": sizing["units"],
        "unit_label": sizing["unit_label"],
        "entry": spec.round_price(entry_fill),
        "stop_loss": signal.stop_loss,
        "initial_stop": signal.stop_loss,
        "take_profit": signal.take_profit,
        "confidence": signal.confidence,
        "risk_cash": sizing["risk_cash"],
        "risk_pct": sizing["risk_pct"],
        "entry_fee": round(entry_fee, 6),
        "opened_at": candles[-1].timestamp.isoformat(),
        "bars_held": 0,
        "unrealised": 0.0,
        "reasons": signal.reasons[:6],
    }
    state.setdefault("positions", []).append(position)
    _log(state, "entry",
         f"{signal.action.value} {spec.display} @ {position['entry']} — حجم {sizing['units']} "
         f"({sizing['risk_pct']}% ریسک، اعتماد {signal.confidence:.0f})",
         {"symbol": spec.symbol, "risk_pct": sizing["risk_pct"], "confidence": signal.confidence})
    return {
        "symbol": symbol, "decision": "opened",
        "confidence": signal.confidence,
        "why": "همهٔ دروازه‌ها باز بود و حجم قابل اجرا بود",
        "position_id": position["id"],
    }


# ─────────────────────────────────────────────────────────────────────────
# Cycle
# ─────────────────────────────────────────────────────────────────────────

async def run_cycle(settings: Settings, state: dict | None = None) -> dict:
    """One full supervision pass. Safe to call repeatedly."""
    own_state = state is None
    state = state if state is not None else load_state()
    _roll_day(state)
    state["cycles"] = state.get("cycles", 0) + 1
    state["last_cycle_at"] = datetime.now(timezone.utc).isoformat()

    await _manage_open_positions(settings, state)

    blocked = await _blocking_reasons(settings, state)
    decisions: list[dict] = []
    if blocked:
        for reason in blocked:
            _log(state, "gate", reason)
    else:
        timeframe = Timeframe(state.get("timeframe", "5m"))
        for symbol in state.get("watchlist", []):
            if len(state.get("positions", [])) >= MAX_CONCURRENT:
                break
            try:
                decision = await _consider_entry(settings, state, symbol, timeframe)
            except Exception as exc:
                decision = {"symbol": symbol, "decision": "error", "why": type(exc).__name__}
            if decision:
                decisions.append(decision)

    # A silent log is the worst outcome: the UI says "running" while the user cannot tell
    # whether anything is being evaluated. Summarise each pass, but only write a new line
    # when the summary actually changes, so a 15-second loop does not flood the journal.
    if decisions:
        tally: dict[str, int] = {}
        for item in decisions:
            tally[item["decision"]] = tally.get(item["decision"], 0) + 1
        label = {
            "opened": "ورود", "no_trade": "بدون سیگنال", "skip": "رد شد",
            "unsizable": "حجم غیرقابل اجرا", "error": "داده در دسترس نیست",
        }
        summary = "، ".join(f"{label.get(k, k)}: {v}" for k, v in sorted(tally.items()))
        sample = next((d["why"] for d in decisions if d.get("why")), "")
        message = f"{len(decisions)} نماد بررسی شد — {summary}" + (f" · {sample[:90]}" if sample else "")
        if state.get("_last_summary") != message:
            state["_last_summary"] = message
            _log(state, "scan", message)

    if own_state:
        save_state(state)

    return {
        "cycle": state["cycles"],
        "at": state["last_cycle_at"],
        "balance": round(state["balance"], 2),
        "open_positions": len(state.get("positions", [])),
        "blocked": blocked,
        "decisions": decisions,
    }


def performance(state: dict | None = None) -> dict:
    """Measured results so far — the only honest basis for trusting the edge."""
    state = state or load_state()
    closed = state.get("closed", [])
    wins = [t for t in closed if (t.get("net_pnl") or 0) > 0]
    losses = [t for t in closed if (t.get("net_pnl") or 0) <= 0]
    gross_win = sum(t["net_pnl"] for t in wins)
    gross_loss = abs(sum(t["net_pnl"] for t in losses))
    rs = [t["r_multiple"] for t in closed if t.get("r_multiple") is not None]

    peak = state["starting_balance"]
    max_dd = 0.0
    for point in state.get("equity_curve", []):
        peak = max(peak, point["balance"])
        if peak > 0:
            max_dd = max(max_dd, (peak - point["balance"]) / peak)

    return {
        "starting_balance": state["starting_balance"],
        "balance": round(state["balance"], 2),
        "target_balance": state.get("target_balance"),
        "return_pct": round((state["balance"] / state["starting_balance"] - 1) * 100, 2)
        if state["starting_balance"] else 0.0,
        "progress_to_target_pct": round(
            (state["balance"] - state["starting_balance"]) /
            max(1e-9, state.get("target_balance", 0) - state["starting_balance"]) * 100, 1
        ) if state.get("target_balance") else None,
        "trades": len(closed),
        "wins": len(wins),
        "losses": len(losses),
        "win_rate_pct": round(len(wins) / len(closed) * 100, 1) if closed else None,
        "profit_factor": round(gross_win / gross_loss, 2) if gross_loss > 0 else None,
        "avg_r": round(sum(rs) / len(rs), 3) if rs else None,
        "expectancy_r": round(sum(rs) / len(rs), 3) if rs else None,
        "max_drawdown_pct": round(max_dd * 100, 1),
        "consecutive_losses": state.get("consecutive_losses", 0),
        "open_positions": len(state.get("positions", [])),
        "cycles": state.get("cycles", 0),
        "confidence_note": (
            "برای اینکه این آمار معنا داشته باشد حداقل ۱۰۰ معاملهٔ بسته لازم است؛ "
            f"الان {len(closed)} معامله ثبت شده."
            if len(closed) < 100 else
            f"{len(closed)} معاملهٔ بسته — نمونه برای سنجش اولیهٔ لبه کافی است."
        ),
    }


# ─────────────────────────────────────────────────────────────────────────
# Background loop
# ─────────────────────────────────────────────────────────────────────────

class Autopilot:
    """Owns the background task. One instance per process."""

    def __init__(self) -> None:
        self._task: asyncio.Task | None = None
        self._lock = asyncio.Lock()
        self.interval_seconds = 15

    @property
    def running(self) -> bool:
        return bool(self._task and not self._task.done())

    async def _loop(self, settings: Settings) -> None:
        while True:
            try:
                async with self._lock:
                    state = load_state()
                    if not state.get("enabled"):
                        save_state(state)
                        return
                    await run_cycle(settings, state)
                    save_state(state)
            except asyncio.CancelledError:
                raise
            except Exception:
                pass  # a bad cycle must never kill the supervisor
            await asyncio.sleep(self.interval_seconds)

    async def start(self, settings: Settings) -> dict:
        self.interval_seconds = max(5, min(int(settings.autopilot_interval_seconds), 3600))
        state = load_state()
        state["enabled"] = True
        save_state(state)
        if not self.running:
            self._task = asyncio.create_task(self._loop(settings))
        return {"running": True, "interval_seconds": self.interval_seconds}

    async def boot(self, settings: Settings) -> dict:
        """Called once by the server at startup so running the app IS the whole setup.

        First launch configures the account from settings; later launches keep whatever
        the user changed and only resume if they had not explicitly stopped it.
        """
        if not settings.autopilot_enabled:
            return {"running": False, "reason": "AURUM_AUTOPILOT_ENABLED=false"}

        first_run = not STORE.exists()
        if first_run:
            reset(
                balance=settings.autopilot_balance,
                target=settings.autopilot_balance * 10,
                watchlist=[s.strip() for s in settings.autopilot_symbols.split(",") if s.strip()],
                timeframe=settings.autopilot_timeframe,
            )
        elif not load_state().get("enabled"):
            # The user pressed stop. Respect it across restarts.
            return {"running": False, "reason": "توسط کاربر متوقف شده است"}

        return await self.start(settings)

    async def shutdown(self) -> None:
        """Cancel the loop WITHOUT clearing `enabled`, so a restart resumes trading.

        Distinct from `stop`, which is the user saying "stand down" and must persist.
        """
        if self._task:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    async def stop(self) -> dict:
        state = load_state()
        state["enabled"] = False
        _log(state, "gate", "اتوپایلوت توسط کاربر متوقف شد")
        save_state(state)
        if self._task:
            self._task.cancel()
            self._task = None
        return {"running": False}


autopilot = Autopilot()
