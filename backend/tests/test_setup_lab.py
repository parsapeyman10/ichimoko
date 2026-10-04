"""
Tests for the setup laboratory.

The single most important test here is `test_random_walk_yields_no_accepted_rules`: fed a
price series that provably contains no edge, the lab must accept nothing. An earlier
version of this module failed that test three different ways — a cost term wrongly folded
into the null hypothesis, overlapping (non-independent) samples inflating confidence, and
time-stopped trades counted as wins. Each bug manufactured edge out of noise. This file
exists so they cannot come back.
"""
from __future__ import annotations

import math
import random
from datetime import datetime, timedelta, timezone

import pytest

from app.models import Candle, Direction, Timeframe
from app.services import instruments as inst
from app.services import setup_lab as lab

FX = inst.get_spec("EUR/USD")


def random_walk(bars: int = 6000, start: float = 1.0850, sigma: float = 0.00035,
                seed: int = 11, drift: float = 0.0) -> list[Candle]:
    """A driftless geometric random walk: by construction, zero predictable edge."""
    rnd = random.Random(seed)
    first = datetime(2025, 1, 1, tzinfo=timezone.utc)
    out: list[Candle] = []
    price = start
    for i in range(bars):
        open_ = price
        path = [open_]
        for _ in range(4):
            path.append(path[-1] * (1 + rnd.gauss(drift, sigma)))
        close = path[-1]
        out.append(Candle(
            symbol="EUR/USD", timeframe=Timeframe.M5,
            timestamp=first + timedelta(seconds=300 * i),
            open=open_, high=max(path), low=min(path), close=close,
            volume=1000 + rnd.random() * 200, complete=True,
        ))
        price = close
    return out


# ── the equation ──────────────────────────────────────────────────────────

@pytest.mark.parametrize("rr,expected_wr", [(0.25, 80.0), (1.0, 50.0), (1.5, 40.0), (3.0, 25.0)])
def test_baseline_is_one_over_one_plus_rr(rr, expected_wr):
    assert lab.baseline_win_rate(rr) * 100 == pytest.approx(expected_wr, abs=0.01)


def test_a_65_percent_win_rate_is_worth_exactly_nothing_without_edge():
    """The core claim: 65% is reachable by geometry alone, at zero expectancy."""
    rr = lab.rr_for_target_win_rate(0.65)
    assert rr == pytest.approx(0.538, abs=0.001)
    expectancy = 0.65 * rr - 0.35
    assert expectancy == pytest.approx(0.0, abs=1e-9)
    # And that RR is exactly the one whose random baseline IS 65%.
    assert lab.baseline_win_rate(rr) == pytest.approx(0.65, abs=1e-9)


def test_cost_raises_the_breakeven_hurdle_but_not_the_null():
    """Cost changes what a win is worth, not which barrier price touches first."""
    assert lab.baseline_win_rate(1.5) == pytest.approx(0.40, abs=1e-9)
    assert lab.required_win_rate(1.5, cost_r=0.0) == pytest.approx(0.40, abs=1e-9)
    assert lab.required_win_rate(1.5, cost_r=0.2) > 0.40


def test_wilson_bound_refuses_to_trust_tiny_samples():
    lo_small, _ = lab.wilson_bounds(7, 10)
    lo_large, _ = lab.wilson_bounds(350, 500)
    assert lo_small < 0.45        # 70% of 10 trades is not evidence of 70%
    assert lo_large > 0.63        # 70% of 500 trades is
    assert lab.wilson_bounds(0, 0) == (0.0, 1.0)


def test_multiple_testing_tightens_the_threshold():
    assert lab.bonferroni_z(1) == pytest.approx(1.96, abs=0.01)
    assert lab.bonferroni_z(100) > lab.bonferroni_z(20) > lab.bonferroni_z(1)


# ── labelling ─────────────────────────────────────────────────────────────

def test_ambiguous_bars_are_recorded_as_losses():
    """When one bar spans both barriers, intrabar order is unknown — assume the loss."""
    first = datetime(2025, 1, 1, tzinfo=timezone.utc)

    def bar(i, o, h, lo, c):
        return Candle(symbol="X", timeframe=Timeframe.M5,
                      timestamp=first + timedelta(seconds=300 * i),
                      open=o, high=h, low=lo, close=c, volume=1, complete=True)

    candles = [
        bar(0, 100, 100, 100, 100),
        bar(1, 100, 100, 100, 100),   # signal bar
        bar(2, 100, 100, 100, 100),   # entry bar: filled at its open (100)
        bar(3, 100, 130, 70, 100),    # engulfs TP (115) and SL (90) together
    ]
    atr_values = [None, 10.0, 10.0, 10.0]
    opp = lab.Opportunity(index=1, setup="x", direction=Direction.BUY)
    result = lab.label_outcome(candles, opp, atr_values, lab.LabelConfig(rr=1.5, stop_atr=1.0))
    assert result["outcome"] == "loss"
    assert result["ambiguous"] is True


def test_timeouts_are_excluded_from_the_win_rate():
    rows = [
        {"outcome": "win", "r": 1.5, "bars": 5},
        {"outcome": "loss", "r": -1.0, "bars": 5},
        {"outcome": "timeout", "r": 0.3, "bars": 24},
        {"outcome": "timeout", "r": 0.4, "bars": 24},
    ]
    summary = lab._summarise(rows, rr=1.5, cost_r=0.0, z=lab.Z_95)
    assert summary["samples"] == 4
    assert summary["resolved"] == 2
    assert summary["timeouts"] == 2
    assert summary["win_rate"] == 50.0          # 1 of 2 resolved, not 3 of 4
    assert summary["expectancy_r"] == pytest.approx((1.5 - 1.0 + 0.3 + 0.4) / 4, abs=1e-6)


# ── the headline guarantee ────────────────────────────────────────────────

def test_random_walk_yields_no_accepted_rules():
    """Fed provable noise, the lab must find nothing worth trading."""
    candles = random_walk()
    book = lab.playbook(candles, FX, config=lab.LabelConfig(rr=1.5, stop_atr=1.0, max_bars=24, cost_r=0.0))
    assert book["accepted"] == []
    # And it must be the filters doing the work, not an absence of candidates: plenty of
    # buckets look good on the in-sample point estimate alone.
    assert len(book["in_sample_only"]) > 0
    assert book["hypotheses_tested"] > 50


def test_random_walk_measured_win_rate_tracks_the_baseline():
    """Estimator sanity: on noise, observed win rate must sit on 1/(1+RR)."""
    candles = random_walk()
    curve = lab.win_rate_curve(candles, FX)
    checked = 0
    for row in curve["rows"]:
        if row["rr"] < 1.0 or row["resolved"] < 100:
            continue  # low RR carries a known pessimistic bias from ambiguous bars
        checked += 1
        assert abs(row["edge_points"]) < 6.0, row
    assert checked >= 3


def test_overlapping_samples_are_purged():
    candles = random_walk()
    result = lab.study(candles, FX, lab.LabelConfig(cost_r=0.0), min_samples=40)
    assert result["opportunities"] < result["opportunities_before_overlap_purge"]
    assert result["overlap_note"]


def test_volatility_context_uses_only_trailing_data():
    """A look-ahead vol label would leak the future into the context bucket."""
    candles = random_walk(bars=1200)
    full = lab.detect_opportunities(candles)
    truncated = lab.detect_opportunities(candles[:900])
    by_index = {o.index: o for o in full if o.index < 850}
    for opp in truncated:
        if opp.index in by_index and opp.setup == by_index[opp.index].setup:
            assert opp.context["vol"] == by_index[opp.index].context["vol"]


def test_study_reports_the_math_and_the_multiple_testing_burden():
    result = lab.study(random_walk(bars=2500), FX, lab.LabelConfig(cost_r=0.0), min_samples=30)
    assert result["math"]["baseline_win_rate_pct"] == pytest.approx(40.0, abs=0.1)
    assert "multiple_testing_note" in result
    assert result["confidence_z"] >= 1.96


def test_detector_finds_every_ichimoku_opportunity_type():
    found = {o.setup for o in lab.detect_opportunities(random_walk(bars=4000))}
    assert {"tk_cross", "kumo_breakout", "kijun_bounce", "tenkan_bounce",
            "kumo_twist", "full_stack"} <= found


def test_a_real_edge_is_detected_when_one_exists():
    """Control: inject persistent drift and confirm the lab is not simply blind."""
    trending = random_walk(bars=6000, sigma=0.00025, drift=0.00012, seed=5)
    curve = lab.win_rate_curve(trending, FX)
    rows = [r for r in curve["rows"] if r["rr"] >= 1.0 and r["resolved"] >= 50]
    assert rows, curve
    assert max(r["edge_points"] for r in rows) > 8.0
