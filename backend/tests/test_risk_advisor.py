"""
Tests for automatic risk selection and position sizing.

The property that matters most: a user who knows nothing about lot sizes must never be
handed a position that risks more than intended. Sizing therefore rounds DOWN to the
venue step, and when the venue minimum would force more risk than the budget allows the
answer is "not tradable" plus the balance that would be required — never a silently
oversized order.
"""
from __future__ import annotations

import pytest

from app.models import Direction, TradeSignal
from app.services import instruments as inst
from app.services.risk_advisor import (
    ABSOLUTE_MAX_RISK_PCT,
    DAILY_LOSS_LIMIT_PCT,
    AccountState,
    advise,
    decide_risk_pct,
    instrument_tier,
    size_position,
)

FX = inst.get_spec("EUR/USD")
GOLD = inst.get_spec("XAU/USD")
BTC = inst.crypto_spec("BTCUSDT", "BTC", "USDT", 0.01,
                       min_qty=0.00001, qty_step=0.00001, min_notional=5.0)


def signal(entry: float, stop: float, target: float, confidence: float = 85.0) -> TradeSignal:
    return TradeSignal(action=Direction.BUY, confidence=confidence,
                       entry=entry, stop_loss=stop, take_profit=target)


# Both carry the SAME gross reward:risk shape (~1.58R) so that any difference in the
# fee-adjusted outcome is attributable to execution cost alone, not to the target.
FX_SIGNAL = signal(1.08500, 1.08380, 1.08690)       # 12 pip stop, 19 pip target
BTC_SIGNAL = signal(64_000.0, 63_840.0, 64_253.3)   # 25 bps stop, 39.6 bps target


# ── risk selection ────────────────────────────────────────────────────────

def test_conviction_increases_and_weak_signals_decrease_risk():
    account = AccountState(balance=5_000)
    strong, _ = decide_risk_pct(FX, signal(1.085, 1.0838, 1.0869, confidence=95), account)
    weak, _ = decide_risk_pct(FX, signal(1.085, 1.0838, 1.0869, confidence=74), account)
    assert strong > weak


def test_instrument_tier_scales_risk_down_for_thinner_markets():
    account = AccountState(balance=5_000)
    major, _ = decide_risk_pct(FX, FX_SIGNAL, account)
    exotic, _ = decide_risk_pct(inst.get_spec("USD/TRY"), FX_SIGNAL, account)
    assert instrument_tier(FX) == "fx_major"
    assert instrument_tier(inst.get_spec("USD/TRY")) == "fx_exotic"
    assert exotic < major


def test_losing_streak_and_drawdown_compound_into_smaller_size():
    base, _ = decide_risk_pct(FX, FX_SIGNAL, AccountState(balance=5_000))
    tilted, steps = decide_risk_pct(
        FX, FX_SIGNAL,
        AccountState(balance=5_000, consecutive_losses=3, daily_pnl_pct=-1.8),
    )
    assert tilted <= base / 3
    assert any("باخت پیاپی" == s["factor"] for s in steps)
    assert any("افت روزانه" == s["factor"] for s in steps)


def test_daily_loss_limit_stops_trading_entirely():
    risk, steps = decide_risk_pct(
        FX, FX_SIGNAL, AccountState(balance=5_000, daily_pnl_pct=-DAILY_LOSS_LIMIT_PCT),
    )
    assert risk == 0.0
    assert any("سقف ضرر روزانه" == s["factor"] for s in steps)

    plan = size_position(FX, FX_SIGNAL, AccountState(balance=5_000), risk)
    assert plan["tradable"] is False


def test_risk_never_exceeds_the_absolute_cap():
    for profile in ("conservative", "balanced", "aggressive"):
        risk, _ = decide_risk_pct(
            FX, signal(1.085, 1.0838, 1.0869, confidence=100),
            AccountState(balance=1_000_000, profile=profile),
        )
        assert 0 <= risk <= ABSOLUTE_MAX_RISK_PCT


def test_every_decision_is_explained():
    _, steps = decide_risk_pct(FX, FX_SIGNAL, AccountState(balance=5_000))
    assert steps
    for step in steps:
        assert step["factor"] and step["effect"] and step["why"]


# ── sizing ────────────────────────────────────────────────────────────────

def test_small_account_is_blocked_instead_of_being_oversized():
    """$100 cannot trade EUR/USD on a 12-pip stop: 0.01 lots alone risks 1.2%."""
    plan = size_position(FX, FX_SIGNAL, AccountState(balance=100), 0.5)
    assert plan["tradable"] is False
    assert plan["forced_risk_pct"] > 0.5
    # It must say what balance WOULD work, rather than just refusing.
    assert plan["min_balance_needed"] > 100
    plan_ok = size_position(FX, FX_SIGNAL, AccountState(balance=plan["min_balance_needed"] * 1.1), 0.5)
    assert plan_ok["tradable"] is True


def test_gold_needs_a_far_bigger_account_than_forex():
    fx = size_position(FX, FX_SIGNAL, AccountState(balance=100), 0.5)
    gold = size_position(GOLD, signal(3900.0, 3896.0, 3907.0), AccountState(balance=100), 0.5)
    assert not fx["tradable"] and not gold["tradable"]
    assert gold["min_balance_needed"] > fx["min_balance_needed"]


def test_realised_risk_never_exceeds_the_target():
    """Quantising must round down. Rounding up would silently breach the budget."""
    for balance in (500, 1_000, 2_500, 7_777, 20_000):
        plan = size_position(FX, FX_SIGNAL, AccountState(balance=balance), 0.5)
        if not plan["tradable"]:
            continue
        assert plan["risk_cash"] <= plan["risk_cash_target"] + 1e-9
        assert plan["risk_pct"] <= 0.5 + 1e-9
        assert plan["units"] % FX.qty_step == pytest.approx(0, abs=1e-6)


def test_quantize_returns_zero_below_the_venue_minimum():
    assert FX.quantize_qty(999) == 0.0
    assert FX.quantize_qty(1_999) == 1_000.0      # rounds down, not up
    assert BTC.quantize_qty(0.000009) == 0.0
    assert BTC.quantize_qty(0.0123456789) == pytest.approx(0.01234, abs=1e-9)


def test_min_notional_is_enforced_for_crypto():
    tiny = inst.crypto_spec("XYZUSDT", "XYZ", "USDT", 0.0001,
                            min_qty=0.001, qty_step=0.001, min_notional=10.0)
    assert tiny.meets_min_notional(1.0, 5.0) is False   # $5 order, $10 floor
    assert tiny.meets_min_notional(3.0, 5.0) is True


def test_sizing_reports_leverage_margin_and_fee_adjusted_expectancy():
    plan = size_position(BTC, BTC_SIGNAL, AccountState(balance=500), 0.5)
    assert plan["tradable"] is True
    # Reported values are rounded for display, so compare within the rounding step.
    assert plan["leverage"] == pytest.approx(plan["notional"] / 500, abs=0.01)
    assert plan["margin_required"] == pytest.approx(plan["notional"] / BTC.model.max_leverage, abs=0.01)
    # Fees must actually be subtracted, so net reward is strictly below gross.
    assert plan["execution_cost"] > 0
    assert plan["net_reward"] < plan["gross_reward"]
    assert 0 < plan["breakeven_win_rate"] <= 100


def test_crypto_fees_make_the_breakeven_win_rate_worse_than_forex():
    fx = size_position(FX, FX_SIGNAL, AccountState(balance=10_000), 0.5)
    btc = size_position(BTC, BTC_SIGNAL, AccountState(balance=10_000), 0.5)
    assert fx["tradable"] and btc["tradable"]
    # Identical ~1.58R gross shape, but Binance's 20 bps round trip eats far more of it:
    # FX gives up ~15% of its edge to the spread, crypto gives up most of its edge to fees.
    assert fx["net_rr"] > btc["net_rr"]
    assert btc["breakeven_win_rate"] > fx["breakeven_win_rate"] + 10


def test_no_signal_means_no_position():
    flat = TradeSignal(action=Direction.NO_TRADE, confidence=40)
    assert size_position(FX, flat, AccountState(balance=5_000), 0.5)["tradable"] is False


# ── end-to-end advice ─────────────────────────────────────────────────────

def test_advise_runs_automatically_from_balance_alone():
    result = advise(FX, FX_SIGNAL, AccountState(balance=5_000), timeframe="5m")
    assert result["auto"] is True
    assert 0 < result["risk_pct"] <= ABSOLUTE_MAX_RISK_PCT
    assert result["reasoning"]
    assert result["sizing"]["tradable"] is True
    assert result["guardrails"]["remaining_daily_budget_pct"] == DAILY_LOSS_LIMIT_PCT


def test_manual_override_is_respected_but_still_capped():
    result = advise(FX, FX_SIGNAL, AccountState(balance=5_000), timeframe="5m", manual_risk_pct=1.0)
    assert result["auto"] is False
    assert result["risk_pct"] == 1.0
    capped = advise(FX, FX_SIGNAL, AccountState(balance=5_000), timeframe="5m", manual_risk_pct=99.0)
    assert capped["risk_pct"] == ABSOLUTE_MAX_RISK_PCT
