"""
Tests for the growth planner and the autonomous paper trader.

The planner's job is to be honest about what a balance target costs. The autopilot's job
is to apply the rules identically every time and to stop itself when it should. Both are
tested for the behaviours that protect the account, not for producing nice numbers.
"""
from __future__ import annotations

import asyncio
from datetime import timedelta

import pytest

from app.config import get_settings
from app.models import Candle, Timeframe
from app.services import autopilot as ap
from app.services import growth_planner as gp
from app.services import instruments as inst
from app.services import universe

from test_instrument_scalp import make_series


# ─── growth planner ───────────────────────────────────────────────────────

def test_negative_expectancy_can_never_reach_the_target():
    """Below the break-even win rate no amount of size or patience helps."""
    edge = gp.EdgeAssumption(win_rate=0.30, net_rr=1.45, risk_pct=0.5)
    assert edge.expectancy_r() < 0
    assert gp.trades_to_target(100, 1000, edge) is None
    plan = gp.plan(100, 1000, edge)
    assert plan["path"]["trades_needed"] is None
    assert any("منفی" in w for w in plan["warnings"])


def test_breakeven_win_rate_matches_the_reward_ratio():
    edge = gp.EdgeAssumption(win_rate=0.4082, net_rr=1.45, risk_pct=0.5)
    assert edge.expectancy_r() == pytest.approx(0.0, abs=1e-3)
    assert gp.plan(100, 1000, edge)["edge"]["breakeven_win_rate_pct"] == pytest.approx(40.8, abs=0.1)


def test_higher_risk_is_faster_but_strictly_more_dangerous():
    rows = gp.risk_ladder(100, 1000, win_rate=0.5, net_rr=1.45, trades_per_day=3)
    by_risk = {r["risk_pct"]: r for r in rows}
    # Fewer trades needed as risk rises...
    assert by_risk[2.0]["trades_needed"] < by_risk[0.5]["trades_needed"]
    # ...but drawdown and ruin rise monotonically with it.
    assert by_risk[10.0]["p95_max_drawdown_pct"] > by_risk[0.5]["p95_max_drawdown_pct"]
    assert by_risk[10.0]["ruin_pct"] > by_risk[1.0]["ruin_pct"]


def test_a_deadline_forces_a_risk_level_and_it_is_flagged_when_unsafe():
    edge = gp.EdgeAssumption(win_rate=0.5, net_rr=1.45, risk_pct=0.5, trades_per_day=3)
    plan = gp.plan(100, 1000, edge, deadline_days=90)
    assert plan["deadline"]["required_risk_pct"] > 2.0
    assert plan["deadline"]["within_system_cap"] is False
    assert any("سقف ۲٪" in w for w in plan["warnings"])


def test_thin_evidence_widens_the_outcome_distribution():
    """A 50% win rate over 30 trades is a far weaker claim than over 500."""
    thin = gp.plan(100, 1000, gp.EdgeAssumption(0.5, 1.45, 0.5, 3, sample_size=30))
    thick = gp.plan(100, 1000, gp.EdgeAssumption(0.5, 1.45, 0.5, 3, sample_size=500))
    assert thin["simulation"]["prob_edge_is_actually_negative_pct"] > \
           thick["simulation"]["prob_edge_is_actually_negative_pct"]
    assert thin["simulation"]["ruin_pct"] > thick["simulation"]["ruin_pct"]
    assert thin["simulation"]["reached_target_pct"] < thick["simulation"]["reached_target_pct"]
    # The weak-evidence case must warn explicitly rather than quietly reporting odds.
    assert any("نمونهٔ بیشتری" in w for w in thin["warnings"])


def test_small_account_floor_is_reported():
    plan = gp.plan(100, 1000, gp.EdgeAssumption(0.5, 1.45, 0.5, 3))
    assert any("کریپتوی اسپات" in w for w in plan["warnings"])


def test_kelly_is_reported_and_overbetting_is_warned():
    plan = gp.plan(100, 1000, gp.EdgeAssumption(0.5, 1.45, 20.0, 3))
    assert plan["edge"]["kelly_pct"] > 0
    assert any("کِلی" in w for w in plan["warnings"])


# ─── autopilot ────────────────────────────────────────────────────────────

BTC = inst.crypto_spec("BTCUSDT", "BTC", "USDT", 0.01,
                       min_qty=0.00001, qty_step=0.00001, min_notional=5.0)


@pytest.fixture
def paper(tmp_path, monkeypatch):
    """Isolated autopilot state plus an offline candle source."""
    monkeypatch.setattr(ap, "STORE", tmp_path / "autopilot.json")

    base = make_series("BTCUSDT", 64_000.0, 60.0)   # fresh BUY cross on the final bar

    def extended(extra: int) -> list[Candle]:
        out = list(base)
        last = base[-1]
        for i in range(1, extra + 1):
            open_ = out[-1].close
            close = open_ + 45.0
            out.append(Candle(
                symbol="BTCUSDT", timeframe=Timeframe.M5,
                timestamp=last.timestamp + timedelta(seconds=300 * i),
                open=open_, high=max(open_, close) + 18.0, low=min(open_, close) - 18.0,
                close=close, volume=1500, complete=True,
            ))
        return out

    cursor = {"extra": 0}

    async def fake_load(settings, symbol, timeframe, limit=500, use_cache=True):
        return extended(cursor["extra"]), BTC

    async def fake_quote(spec):
        return None

    async def no_blockers(settings, state):
        return []

    monkeypatch.setattr(universe, "load_candles", fake_load)
    monkeypatch.setattr(universe, "live_quote", fake_quote)
    monkeypatch.setattr(ap, "_blocking_reasons", no_blockers)
    return cursor


def run(coro):
    return asyncio.get_event_loop_policy().new_event_loop().run_until_complete(coro)


def test_autopilot_opens_manages_and_closes_a_position(paper):
    settings = get_settings()
    state = ap.reset(balance=1_000.0, target=10_000.0, watchlist=["BTCUSDT"], timeframe="5m")

    report = run(ap.run_cycle(settings, state))
    assert report["decisions"][0]["decision"] == "opened"
    assert len(state["positions"]) == 1
    position = state["positions"][0]
    assert position["side"] == "BUY"
    assert position["qty"] > 0
    assert position["risk_pct"] <= 2.0

    # Let price run into the target.
    for _ in range(4):
        paper["extra"] += 3
        run(ap.run_cycle(settings, state))

    assert len(state["closed"]) == 1
    trade = state["closed"][0]
    assert trade["exit_reason"]
    assert trade["fees"] > 0                      # paper fills are charged real costs
    assert trade["net_pnl"] < trade["gross_pnl"]  # fees always reduce the result
    assert state["balance"] != 1_000.0


def test_autopilot_never_opens_two_positions_on_one_symbol(paper):
    settings = get_settings()
    state = ap.reset(balance=1_000.0, watchlist=["BTCUSDT"], timeframe="5m")
    run(ap.run_cycle(settings, state))
    assert len(state["positions"]) == 1
    paper["extra"] += 1
    report = run(ap.run_cycle(settings, state))
    assert report["decisions"][0]["decision"] == "skip"
    assert len(state["positions"]) == 1


def test_concurrency_cap_is_enforced(paper):
    settings = get_settings()
    state = ap.reset(balance=10_000.0, watchlist=["BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"],
                     timeframe="5m")
    run(ap.run_cycle(settings, state))
    assert len(state["positions"]) <= ap.MAX_CONCURRENT


def test_daily_loss_limit_and_losing_streak_block_new_entries(tmp_path, monkeypatch):
    monkeypatch.setattr(ap, "STORE", tmp_path / "autopilot.json")
    settings = get_settings()

    state = ap.reset(balance=1_000.0)
    state["day_start_balance"] = 1_000.0
    state["balance"] = 960.0                      # -4% on the day
    assert any("سقف ضرر روزانه" in r for r in run(ap._blocking_reasons(settings, state)))

    state = ap.reset(balance=1_000.0)
    state["consecutive_losses"] = ap.COOLDOWN_AFTER_LOSSES
    assert any("باخت پیاپی" in r for r in run(ap._blocking_reasons(settings, state)))

    state = ap.reset(balance=1_000.0)
    state["balance"] = 400.0                      # halved
    assert any("نصف سرمایه" in r for r in run(ap._blocking_reasons(settings, state)))


def test_blocked_cycles_make_no_entry_decisions(paper, monkeypatch):
    settings = get_settings()

    async def blocked(settings_, state_):
        return ["تست: دروازهٔ مسدود"]

    monkeypatch.setattr(ap, "_blocking_reasons", blocked)
    state = ap.reset(balance=1_000.0, watchlist=["BTCUSDT"], timeframe="5m")
    report = run(ap.run_cycle(settings, state))
    assert report["blocked"]
    assert report["decisions"] == []
    assert state["positions"] == []


def test_performance_refuses_to_oversell_a_small_sample(paper):
    settings = get_settings()
    state = ap.reset(balance=1_000.0, watchlist=["BTCUSDT"], timeframe="5m")
    run(ap.run_cycle(settings, state))
    for _ in range(4):
        paper["extra"] += 3
        run(ap.run_cycle(settings, state))
    perf = ap.performance(state)
    assert perf["trades"] >= 1
    assert "۱۰۰ معاملهٔ بسته" in perf["confidence_note"]


def test_new_day_resets_the_daily_budget(tmp_path, monkeypatch):
    monkeypatch.setattr(ap, "STORE", tmp_path / "autopilot.json")
    state = ap.reset(balance=1_000.0)
    state["day"] = "2000-01-01"
    state["balance"] = 950.0
    ap._roll_day(state)
    assert state["day"] != "2000-01-01"
    assert state["day_start_balance"] == 950.0
    assert ap._daily_pnl_pct(state) == 0.0
