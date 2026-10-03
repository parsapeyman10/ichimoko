"""
Regression tests for the multi-symbol fix.

The bug being pinned down here: every price-scale constant in the signal engine was a gold
constant. On EUR/USD `round(entry, 2)` collapsed entry, stop and target onto a single
number, and the liquidation gate judged FX against gold's 20x ceiling, which vetoed 100% of
FX signals. These tests fail loudly if either regresses.

Candles here are deliberately constructed fixtures (not market data) used only to drive the
deterministic scoring path. Nothing in the shipped code generates candles.
"""
from __future__ import annotations

import random
from datetime import datetime, timedelta, timezone

import pytest

from app.models import Candle, Direction, StrategyContext, Timeframe
from app.services import instruments as inst
from app.services.strategy import evaluate_scalp, get_trailing_stop


def make_series(
    symbol: str,
    start: float,
    step: float,
    pullback: int = 18,
    resume: int = 10,
    bars: int = 320,
    timeframe: Timeframe = Timeframe.M5,
    seed: int = 5,
) -> list[Candle]:
    """Uptrend → pullback → resume, so a fresh bullish Tenkan/Kijun cross lands on the last bar."""
    rnd = random.Random(seed)
    end = datetime(2026, 9, 15, 15, 0, tzinfo=timezone.utc)  # inside every killzone
    first = datetime.fromtimestamp(
        (int(end.timestamp()) // timeframe.seconds - bars) * timeframe.seconds, timezone.utc
    )
    out: list[Candle] = []
    price = start
    for i in range(bars):
        if i < bars - pullback - resume:
            drift = step * 0.9
        elif i < bars - resume:
            drift = -step * 0.75
        else:
            drift = step * 1.5
        open_, close = price, price + drift + rnd.uniform(-step * 0.2, step * 0.2)
        out.append(Candle(
            symbol=symbol, timeframe=timeframe,
            timestamp=first + timedelta(seconds=timeframe.seconds * i),
            open=open_, high=max(open_, close) + abs(step) * 0.22,
            low=min(open_, close) - abs(step) * 0.22, close=close,
            volume=1200 + (900 if i > bars - resume else 0) + rnd.random() * 200,
            complete=True,
        ))
        price = close
    return out


CASES = [
    # symbol, start price, per-bar step, expected quoting precision
    ("EUR/USD", 1.0850, 0.00012, 5),
    ("USD/JPY", 157.20, 0.012, 3),
    ("XAU/USD", 3900.0, 1.10, 2),
    ("BTCUSDT", 64000.0, 60.0, 2),
]


def spec_for(symbol: str) -> inst.InstrumentSpec:
    return inst.get_spec(symbol, crypto_tick=0.01) if "/" not in symbol else inst.get_spec(symbol)


@pytest.mark.parametrize("symbol,start,step,precision", CASES)
def test_every_asset_class_produces_a_distinct_trade_plan(symbol, start, step, precision):
    spec = spec_for(symbol)
    assert spec.price_precision == precision

    candles = make_series(symbol, start, step)
    context = StrategyContext(
        spread=spec.default_spread(),
        typical_spread=spec.default_spread(),
        higher_timeframe_bias=Direction.BUY,
    )
    signal = evaluate_scalp(candles, context, spec)

    assert signal.action is Direction.BUY, signal.blockers
    assert signal.entry is not None and signal.stop_loss is not None and signal.take_profit is not None
    # The actual bug: these three used to be the same number on anything quoted finer than 0.01.
    assert signal.stop_loss < signal.entry < signal.take_profit
    assert len({signal.entry, signal.stop_loss, signal.take_profit}) == 3
    # Rounded to the instrument's real precision, not gold's two decimals.
    assert signal.entry == round(signal.entry, precision)
    # The stop must be worth more than one tick of noise.
    assert signal.entry - signal.stop_loss > spec.tick_size


def test_gold_behaviour_is_unchanged_without_a_spec():
    """The legacy single-symbol path must keep producing exactly what it produced before."""
    candles = make_series("XAU/USD", 3900.0, 1.10)
    context = StrategyContext(higher_timeframe_bias=Direction.BUY)
    legacy = evaluate_scalp(candles, context)
    explicit = evaluate_scalp(candles, context, inst.GOLD_SPEC)
    assert legacy.action is explicit.action
    assert (legacy.entry, legacy.stop_loss, legacy.take_profit) == \
           (explicit.entry, explicit.stop_loss, explicit.take_profit)


def test_forex_signal_is_impossible_under_the_old_gold_spec():
    """Proves the reported symptom: the same FX candles yield nothing when scored as gold."""
    candles = make_series("EUR/USD", 1.0850, 0.00012)
    context = StrategyContext(higher_timeframe_bias=Direction.BUY)
    assert evaluate_scalp(candles, context).action is Direction.NO_TRADE
    spec = inst.get_spec("EUR/USD")
    fixed = evaluate_scalp(
        candles,
        StrategyContext(spread=spec.default_spread(), typical_spread=spec.default_spread(),
                        higher_timeframe_bias=Direction.BUY),
        spec,
    )
    assert fixed.action is Direction.BUY


def test_trailing_stop_runs_on_forex_scale():
    """`stop_dist < 0.01` silently disabled trailing for every FX pair."""
    spec = inst.get_spec("EUR/USD")
    candles = make_series("EUR/USD", 1.0850, 0.00012)
    context = StrategyContext(spread=spec.default_spread(), typical_spread=spec.default_spread(),
                              higher_timeframe_bias=Direction.BUY)
    signal = evaluate_scalp(candles, context, spec)
    assert signal.entry is not None and signal.stop_loss is not None

    risk = signal.entry - signal.stop_loss
    # Price runs 2R in our favour.
    base = candles[-1]
    run = [
        base.model_copy(update={
            "timestamp": base.timestamp + timedelta(seconds=300 * (n + 1)),
            "open": signal.entry + risk * n, "close": signal.entry + risk * (n + 1),
            "high": signal.entry + risk * (n + 1.1), "low": signal.entry + risk * (n - 0.1),
        })
        for n in range(2)
    ]
    moved = get_trailing_stop(signal, run, kijun=signal.entry, atr=risk, initial_stop=signal.stop_loss, spec=spec)
    assert moved is not None and moved > signal.stop_loss
    assert moved == round(moved, spec.price_precision)
    # Under the gold default the same call is a no-op.
    assert get_trailing_stop(signal, run, kijun=signal.entry, atr=risk, initial_stop=signal.stop_loss) is None


def test_crypto_model_has_no_session_or_weekend_gate():
    spec = inst.get_spec("BTCUSDT", crypto_tick=0.01)
    assert spec.kind == inst.CRYPTO
    assert spec.model.killzones_utc == ()
    assert spec.model.in_killzone(3.5) is True   # 03:30 UTC is a normal crypto hour
    assert spec.model.weekend_gap_risk is False
    # A forex pair still respects both.
    fx = inst.get_spec("EUR/USD")
    assert fx.model.in_killzone(3.5) is False
    assert fx.model.weekend_gap_risk is True


def test_pip_and_tick_conventions_per_quote_currency():
    assert inst.get_spec("EUR/USD").pip_size == pytest.approx(0.0001)
    assert inst.get_spec("USD/JPY").pip_size == pytest.approx(0.01)
    assert inst.get_spec("GBP/JPY").price_precision == 3
    assert inst.get_spec("XAU/USD").pip_size == pytest.approx(0.1)
    assert inst.decimals_from_tick(0.00001000) == 5
    assert inst.decimals_from_tick(1.0) == 0


def test_scalp_policy_is_forex_only():
    """User rule: the scalp panel trades currency pairs; gold and crypto are view-only."""
    assert inst.get_spec("EUR/USD").is_scalpable()
    assert inst.get_spec("GBP/JPY").is_scalpable()
    assert not inst.get_spec("XAU/USD").is_scalpable()          # metal
    assert not inst.get_spec("USD/TRY").is_scalpable()          # exotic, spread too wide
    assert not inst.get_spec("BTCUSDT", crypto_tick=0.01).is_scalpable()


def test_each_pair_gets_its_own_model():
    major, minor, exotic = (inst.get_spec(s) for s in ("EUR/USD", "GBP/NZD", "USD/ZAR"))
    assert major.model.threshold("5m") < minor.model.threshold("5m") < exotic.model.threshold("5m")
    assert major.model.min_adx < exotic.model.min_adx
    assert major.model.typical_spread_pips < minor.model.typical_spread_pips < exotic.model.typical_spread_pips
    assert inst.get_spec("USD/JPY").model.killzones_utc != major.model.killzones_utc  # Tokyo window
