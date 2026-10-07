"""Anti fake-cross gate: a single Tenkan/Kijun crossing may never become a direction.

The complaint that motivated this file is the common one: in a range the Ichimoku lines
cross several times a day and most of those crossings are noise. These tests pin down that

* one crossing bar alone is not a signal, no matter how good the rest of the story looks;
* a marginal "kiss" crossing (lines touching, separation below 0.08×ATR) is rejected;
* a flip-flop zone (opposite cross within the last bars) is rejected;
* the full plan only appears once the crossing is confirmed by a further closed bar.

Candles here are deterministic fixtures built by the shared helper in
`test_instrument_scalp.py`; nothing in the shipped code generates market data.
"""
from __future__ import annotations

from app.models import Direction, StrategyContext
from app.services import instruments as inst
from app.services.strategy import CROSS_QUALITY_LABEL, assess_cross_quality, evaluate_scalp

from test_instrument_scalp import make_series

XAU = inst.GOLD_SPEC


def _values(tenkan: list[float | None], kijun: list[float | None]) -> dict:
    return {"tenkan": tenkan, "kijun": kijun}


def test_single_bar_kiss_cross_is_not_a_direction():
    """One bar of contact with almost no separation is the classic fake cross."""
    values = _values([100.0, 100.05], [101.0, 100.0])
    verdict = assess_cross_quality(
        values, 1, atr=1.0, tick=0.01, close=100.2, cloud_top=100.0, cloud_bottom=99.0, is_long=True,
    )
    assert verdict.ok is False
    assert verdict.checks["two_bars_aligned"] is False
    assert verdict.checks["real_separation"] is False
    joined = " ".join(verdict.blockers)
    assert "ضد کراس فیک" in joined
    assert "دو کندل بستهٔ پیاپی" in joined
    assert "بوسهٔ مرزی" in joined


def test_confirmed_cross_with_real_separation_passes():
    # crossed up at bar 1 and stayed above, the gap widens, and Kijun is flat-to-rising.
    values = _values([99.0, 100.4, 101.2], [100.0, 100.2, 100.3])
    verdict = assess_cross_quality(
        values, 2, atr=1.0, tick=0.01, close=102.0, cloud_top=100.5, cloud_bottom=99.5, is_long=True,
    )
    assert verdict.ok is True
    assert all(verdict.checks.values())
    assert "تأیید کراس" in verdict.detail


def test_opposite_cross_in_recent_bars_is_a_whipsaw():
    """T > K two bars running, but the lines swapped sides three bars ago."""
    values = _values([100.6, 99.4, 100.0, 100.4], [100.0, 100.0, 100.0, 100.0])
    verdict = assess_cross_quality(
        values, 3, atr=1.0, tick=0.01, close=101.0, cloud_top=100.0, cloud_bottom=99.0, is_long=True,
    )
    assert verdict.ok is False
    assert verdict.checks["no_whipsaw"] is False
    assert any("کراس مخالف" in blocker for blocker in verdict.blockers)


def test_engine_refuses_direction_from_an_unconfirmed_cross():
    """The crossing bar itself carries no direction until the next closed bar confirms it."""
    candles = make_series("XAU/USD", 3900.0, 1.10, confirm_bars=0)
    context = StrategyContext(higher_timeframe_bias=Direction.BUY)
    signal = evaluate_scalp(candles, context, XAU)
    assert signal.action is Direction.NO_TRADE
    assert any("ضد کراس فیک" in blocker for blocker in signal.blockers)
    assert any(item["name"] == CROSS_QUALITY_LABEL and item["ok"] is False for item in signal.confluence)
    assert signal.entry is None and signal.stop_loss is None and signal.take_profit is None


def test_the_same_series_trades_once_the_cross_is_confirmed():
    """Only the confirmation bar is added — the trend, symbol and rules are identical."""
    candles = make_series("XAU/USD", 3900.0, 1.10, confirm_bars=1)
    context = StrategyContext(
        spread=XAU.default_spread(), typical_spread=XAU.default_spread(),
        higher_timeframe_bias=Direction.BUY,
    )
    signal = evaluate_scalp(candles, context, XAU)
    assert signal.action is Direction.BUY, signal.blockers
    assert any(item["name"] == CROSS_QUALITY_LABEL and item["ok"] is True for item in signal.confluence)
