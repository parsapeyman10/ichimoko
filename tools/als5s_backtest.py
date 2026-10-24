#!/usr/bin/env python3
"""ALS-5S — offline research harness for silver (XAG/USD) on M5.

This mirrors the rules that ship in ``engine/XagAresEngine.kt`` so the model can be measured
without a phone, Android Studio or Gradle. It is a *research* tool: it produces numbers only for
the bars you give it, and it labels a synthetic fixture as synthetic.

What is faithful to the app
---------------------------
- confirmed pivots only (``left``/``right`` closed bars) — no repainting, no look-ahead;
- the same ATR / ADX / EMA / RSI / MACD / relative-volume definitions;
- the five-state regime with hysteresis (a new label must survive one bar; HIGH_VOL is entered
  immediately and left only with confirmation);
- Engine A (trend continuation), Engine B (liquidity sweep), Engine C in its two separate modes
  (range reversion and volatility breakout), and module X (silver/gold context, never a signal);
- the same 0–10 score and the same arbitration rule (a close conflict is reported, never averaged);
- the same cost model: 2 × commission + spread + slippage, per ounce, plus a cost ÷ risk cap;
- the same risk model: 0.25 % per trade, 1 % daily cap, 1.5 minimum *net* R:R.

What is not claimed
-------------------
- No result here is a probability of success.
- The synthetic fixture exists to exercise the code; its metrics describe the fixture.
- News filtering is absent because the app has no reliable, timestamped news source.

Usage::

    python3 tools/als5s_backtest.py                              # deterministic synthetic fixture
    python3 tools/als5s_backtest.py --csv xag_m5.csv             # real silver M5 bars
    python3 tools/als5s_backtest.py --csv xag_m5.csv --gold-csv xau_m5.csv
    python3 tools/als5s_backtest.py --csv xag_m5.csv --stages --json report.json
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

M5_MS = 300_000
M15_MS = 900_000
H1_MS = 3_600_000


@dataclass(frozen=True)
class Bar:
    time_ms: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 100.0


@dataclass(frozen=True)
class SilverParams:
    """Mirror of ``XagAresEngine.Config``; every number appears in the Kotlin file."""

    pivot_left: int = 3
    pivot_right: int = 3
    micro_left: int = 2
    micro_right: int = 1
    atr_period: int = 14
    adx_period: int = 14
    ema_fast: int = 21
    ema_slow: int = 55
    htf_ema: int = 200
    rsi_period: int = 14
    vol_median_lookback: int = 60
    atr_percentile_lookback: int = 240
    range_expansion_fast: int = 3
    range_expansion_baseline: int = 20
    low_atr_ratio: float = 0.65
    elevated_atr_ratio: float = 1.45
    extreme_atr_ratio: float = 2.30
    trend_adx_min: float = 22.0
    range_adx_max: float = 20.0
    compression_max_range_atr: float = 0.85
    min_range_width_atr: float = 1.4
    max_range_width_atr: float = 6.5
    regime_confirm_bars: int = 2
    min_impulse_atr: float = 1.1
    min_pullback_frac: float = 0.25
    max_pullback_frac: float = 0.90
    pullback_zone_atr: float = 0.65
    max_chase_atr: float = 1.15
    min_body_frac: float = 0.50
    min_sweep_atr: float = 0.15
    max_sweep_atr: float = 1.40
    sweep_window_bars: int = 4
    require_retest: bool = False
    retest_tolerance_atr: float = 0.25
    compression_window_bars: int = 24
    min_breakout_atr: float = 0.18
    min_breakout_rel_volume: float = 1.20
    min_expansion_for_breakout: float = 1.15
    max_breakout_chase_atr: float = 1.15
    follow_through_frac: float = 0.55
    range_touch_frac: float = 0.22
    min_rejection_wick_frac: float = 0.40
    range_invalidation_atr: float = 0.25
    max_measured_move_rr: float = 3.0
    stop_buffer_atr: float = 0.28
    min_stop_atr: float = 0.80
    max_stop_atr: float = 5.00
    target_rr: float = 2.0
    min_reward_risk: float = 1.50
    max_cost_to_risk_fraction: float = 0.30
    # Venue: COMEX SI 5,000 oz, tick 0.001; CFD commission per ounce.
    commission_per_oz: float = 0.0007
    spread_per_oz: float = 0.02
    slippage_bps: float = 2.0
    qty_step_oz: float = 0.01
    min_notional_usd: float = 5.0
    max_notional_to_equity: float = 5.0
    score_threshold_10: int = 8
    conflict_margin_10: int = 2
    risk_per_trade_pct: float = 0.25
    max_daily_loss_pct: float = 1.00
    account_equity_usd: float = 10_000.0
    max_setup_age_bars: int = 12
    min_bars: int = 280
    session_gate: bool = False
    penalties_low_liquidity: bool = True
    gold_enabled: bool = True
    gold_correlation_window: int = 60
    gold_momentum_window: int = 12
    min_gold_correlation: float = 0.35
    require_gold_confirmation: bool = False
    enable_a: bool = True
    enable_b: bool = True
    enable_c_range: bool = True
    enable_c_breakout: bool = True
    # Replay-only
    max_hold_bars: int = 48
    exit_model: str = "FIXED_RR"  # FIXED_RR | STRUCTURE | TRAIL_AFTER_1R | PARTIAL_HALF


# ---------------------------------------------------------------------------------------------
# Indicators
# ---------------------------------------------------------------------------------------------
def wilder(values: Sequence[float], period: int) -> List[Optional[float]]:
    out: List[Optional[float]] = [None] * len(values)
    if period <= 0 or len(values) < period:
        return out
    prev = sum(values[:period]) / period
    out[period - 1] = prev
    for i in range(period, len(values)):
        prev = (prev * (period - 1) + values[i]) / period
        out[i] = prev
    return out


def true_ranges(bars: Sequence[Bar]) -> List[float]:
    out = [0.0] * len(bars)
    for i, bar in enumerate(bars):
        if i == 0:
            out[i] = bar.high - bar.low
        else:
            prev = bars[i - 1].close
            out[i] = max(bar.high - bar.low, abs(bar.high - prev), abs(bar.low - prev))
    return out


def compute_atr(bars: Sequence[Bar], period: int = 14) -> List[Optional[float]]:
    return wilder(true_ranges(bars), period)


def compute_ema(values: Sequence[float], period: int) -> List[Optional[float]]:
    out: List[Optional[float]] = [None] * len(values)
    if period <= 0 or not values:
        return out
    alpha = 2.0 / (period + 1.0)
    ema = values[0]
    out[0] = ema
    for i in range(1, len(values)):
        ema = alpha * values[i] + (1.0 - alpha) * ema
        out[i] = ema
    return out


def compute_rsi(bars: Sequence[Bar], period: int = 14) -> List[Optional[float]]:
    out: List[Optional[float]] = [None] * len(bars)
    if len(bars) <= period:
        return out
    gains = [max(0.0, bars[i].close - bars[i - 1].close) for i in range(1, len(bars))]
    losses = [max(0.0, bars[i - 1].close - bars[i].close) for i in range(1, len(bars))]
    avg_gain = wilder(gains, period)
    avg_loss = wilder(losses, period)
    for i in range(period, len(bars)):
        g, loss = avg_gain[i - 1], avg_loss[i - 1]
        if g is None or loss is None:
            continue
        out[i] = 100.0 if loss == 0.0 and g > 0.0 else (
            50.0 if loss == 0.0 else 100.0 - 100.0 / (1.0 + g / loss))
    return out


def compute_adx(bars: Sequence[Bar], period: int = 14) -> List[Optional[float]]:
    out: List[Optional[float]] = [None] * len(bars)
    if len(bars) <= period * 2:
        return out
    plus_dm = [0.0] * len(bars)
    minus_dm = [0.0] * len(bars)
    for i in range(1, len(bars)):
        up = bars[i].high - bars[i - 1].high
        down = bars[i - 1].low - bars[i].low
        plus_dm[i] = up if up > down and up > 0.0 else 0.0
        minus_dm[i] = down if down > up and down > 0.0 else 0.0
    atr = wilder(true_ranges(bars), period)
    plus = wilder(plus_dm, period)
    minus = wilder(minus_dm, period)
    dx: List[Optional[float]] = [None] * len(bars)
    for i in range(len(bars)):
        if atr[i] in (None, 0.0) or plus[i] is None or minus[i] is None:
            continue
        pdi = 100.0 * plus[i] / atr[i]
        mdi = 100.0 * minus[i] / atr[i]
        denom = pdi + mdi
        dx[i] = 0.0 if denom == 0.0 else 100.0 * abs(pdi - mdi) / denom
    start = next((i for i, v in enumerate(dx) if v is not None), None)
    if start is None or len(dx) - start < period:
        return out
    seed = [v for v in dx[start:start + period] if v is not None]
    if len(seed) < period:
        return out
    prev = sum(seed) / period
    out[start + period - 1] = prev
    for i in range(start + period, len(dx)):
        if dx[i] is None:
            continue
        prev = (prev * (period - 1) + dx[i]) / period
        out[i] = prev
    return out


def compute_macd_histogram(bars: Sequence[Bar]) -> List[Optional[float]]:
    closes = [b.close for b in bars]
    fast, slow = compute_ema(closes, 12), compute_ema(closes, 26)
    macd = [None if fast[i] is None or slow[i] is None else fast[i] - slow[i] for i in range(len(closes))]
    signal = compute_ema([v if v is not None else 0.0 for v in macd], 9)
    return [None if macd[i] is None or signal[i] is None else macd[i] - signal[i] for i in range(len(closes))]


def relative_volume(bars: Sequence[Bar], lookback: int = 30) -> List[Optional[float]]:
    out: List[Optional[float]] = [None] * len(bars)
    for i in range(len(bars)):
        window = [b.volume for b in bars[max(0, i - lookback + 1):i + 1]]
        avg = sum(window) / len(window) if window else 0.0
        out[i] = (bars[i].volume / avg) if avg > 0.0 else 1.0
    return out


def confirmed_swings(
    bars: Sequence[Bar], left: int, right: int, up_to_index: Optional[int] = None
) -> Tuple[List[Tuple[int, float]], List[Tuple[int, float]]]:
    last = (len(bars) - 1) if up_to_index is None else min(up_to_index, len(bars) - 1)
    highs: List[Tuple[int, float]] = []
    lows: List[Tuple[int, float]] = []
    for i in range(left, last - right + 1):
        window = bars[i - left:i + right + 1]
        bar = bars[i]
        if bar.high == max(b.high for b in window) and bar.high > max(
            b.high for b in window if b is not bar
        ):
            highs.append((i, bar.high))
        if bar.low == min(b.low for b in window) and bar.low < min(
            b.low for b in window if b is not bar
        ):
            lows.append((i, bar.low))
    return highs, lows


def percentile_rank(series: Sequence[Optional[float]], lookback: int) -> float:
    window = [v for v in series[-lookback:] if v is not None]
    if not window:
        return 0.0
    last = window[-1]
    return (sum(1 for v in window if v < last) + 0.5 * sum(1 for v in window if v == last)) / len(window)


# ---------------------------------------------------------------------------------------------
# Context pieces
# ---------------------------------------------------------------------------------------------
REGIMES = ("BULL_TREND", "BEAR_TREND", "RANGE", "COMPRESSION", "HIGH_VOL")
VOL_STATES = ("LOW", "NORMAL", "ELEVATED", "EXTREME")
ENGINE_IDS = ("A_TREND", "B_SWEEP", "C_RANGE", "C_BREAKOUT", "X_CROSS")
STATES = ("IDLE", "WATCH", "FORMING", "CONFIRMATION", "READY", "ACTIVE", "INVALIDATED", "EXPIRED",
          "UNAVAILABLE")


def session_of(time_ms: int) -> str:
    hour = (time_ms // 3_600_000) % 24
    if 7 <= hour <= 11:
        return "LONDON"
    if 12 <= hour <= 15:
        return "OVERLAP"
    if 16 <= hour <= 20:
        return "NEW_YORK"
    if 0 <= hour <= 6:
        return "ASIA"
    return "OFF"


@dataclass(frozen=True)
class GoldContext:
    available: bool
    correlation: Optional[float] = None
    relative_momentum: Optional[float] = None
    ratio: Optional[float] = None
    ratio_change_pct: Optional[float] = None
    bias: str = "NO_TRADE"
    note: str = ""


def gold_context(
    bars: Sequence[Bar], gold_bars: Sequence[Bar], p: SilverParams
) -> GoldContext:
    if not p.gold_enabled:
        return GoldContext(False, note="gold module disabled")
    if not gold_bars:
        return GoldContext(False, note="no aligned XAU/USD bars; module unavailable")
    by_time = {b.time_ms: b for b in gold_bars}
    pairs = [(bar, by_time[bar.time_ms]) for bar in bars[-(p.gold_correlation_window + 1):]
             if bar.time_ms in by_time]
    needed = max(p.gold_correlation_window, p.gold_momentum_window) + 1
    if len(pairs) < needed:
        return GoldContext(False, note=f"only {len(pairs)} of {needed} aligned gold bars")

    def rets(values: List[float]) -> List[float]:
        return [(b - a) / a if a > 0 else 0.0 for a, b in zip(values, values[1:])]

    silver_ret = rets([bar.close for bar, _ in pairs])
    gold_ret = rets([g.close for _, g in pairs])
    n = min(len(silver_ret), len(gold_ret))
    if n < 5:
        return GoldContext(False, note="too few aligned returns")
    x, y = silver_ret[-n:], gold_ret[-n:]
    mx, my = sum(x) / n, sum(y) / n
    cov = sum((x[i] - mx) * (y[i] - my) for i in range(n)) / n
    sx = math.sqrt(sum((v - mx) ** 2 for v in x) / n)
    sy = math.sqrt(sum((v - my) ** 2 for v in y) / n)
    corr = cov / (sx * sy) if sx > 0 and sy > 0 else 0.0
    window = p.gold_momentum_window
    relative = sum(silver_ret[-window:]) - sum(gold_ret[-window:])
    ratio = pairs[-1][0].close / pairs[-1][1].close
    older = pairs[max(0, len(pairs) - 1 - window)]
    ratio_then = older[0].close / older[1].close
    ratio_change = (ratio - ratio_then) / ratio_then * 100.0 if ratio_then > 0 else 0.0
    if abs(corr) < p.min_gold_correlation:
        bias = "NO_TRADE"
    elif relative > 0:
        bias = "BUY"
    elif relative < 0:
        bias = "SELL"
    else:
        bias = "NO_TRADE"
    return GoldContext(True, corr, relative, ratio, ratio_change, bias,
                       f"corr={corr:.2f} rel={relative * 100:.3f}%")


@dataclass(frozen=True)
class Features:
    atr: float
    atr_ratio: float
    atr_percentile: float
    range_expansion: float
    adx: float
    ema_fast: float
    ema_slow: float
    rsi: float
    macd_hist: float
    rel_volume: float
    swing_high: Optional[float]
    swing_low: Optional[float]
    hh: bool
    hl: bool
    lh: bool
    ll: bool
    bos_up: bool
    bos_down: bool
    range_high: float
    range_low: float
    compression_ratio: float
    htf_bias: Optional[str]
    m15_available: bool
    session: str
    gold: GoldContext


def volatility_of(f: Features, p: SilverParams) -> str:
    if f.atr_ratio >= p.extreme_atr_ratio or f.atr_percentile >= 0.97:
        return "EXTREME"
    if f.atr_ratio >= p.elevated_atr_ratio or f.atr_percentile >= 0.85:
        return "ELEVATED"
    if f.atr_ratio <= p.low_atr_ratio and f.atr_percentile <= 0.25:
        return "LOW"
    return "NORMAL"


def raw_regime(f: Features, p: SilverParams) -> str:
    vol = volatility_of(f, p)
    bullish = (f.hh and f.hl) or f.bos_up
    bearish = (f.lh and f.ll) or f.bos_down
    if vol == "EXTREME":
        return "HIGH_VOL"
    if bullish and bearish:
        return "HIGH_VOL"
    if f.compression_ratio <= p.compression_max_range_atr and f.adx < p.trend_adx_min:
        return "COMPRESSION"
    width_atr = (f.range_high - f.range_low) / f.atr
    if bullish and f.adx >= p.trend_adx_min and f.htf_bias != "SELL":
        return "BULL_TREND"
    if bearish and f.adx >= p.trend_adx_min and f.htf_bias != "BUY":
        return "BEAR_TREND"
    if p.min_range_width_atr <= width_atr <= p.max_range_width_atr and f.adx <= p.range_adx_max:
        return "RANGE"
    return "HIGH_VOL"


def classify_regime(now: str, previous: Optional[str], p: SilverParams) -> Tuple[str, str]:
    if previous is None or now == previous:
        return now, ""
    if now == "HIGH_VOL":
        return now, "protective state entered immediately"
    if previous == "HIGH_VOL":
        return previous, "leaving protection needs one more confirmation bar"
    if p.regime_confirm_bars <= 1:
        return now, ""
    return previous, f"hysteresis: {previous} held until confirmed"


def build_features(
    bars: Sequence[Bar], m15: Sequence[Bar], gold_bars: Sequence[Bar], p: SilverParams
) -> Features:
    closes = [b.close for b in bars]
    atr_series = compute_atr(bars, p.atr_period)
    atr = next((v for v in reversed(atr_series) if v is not None and v > 0.0), 1.0)
    recent = sorted(v for v in atr_series[-p.vol_median_lookback:] if v is not None and v > 0.0)
    median = recent[len(recent) // 2] if recent else atr
    ranges = [b.high - b.low for b in bars]
    fast = sum(ranges[-p.range_expansion_fast:]) / max(1, len(ranges[-p.range_expansion_fast:]))
    base = sum(ranges[-p.range_expansion_baseline:]) / max(1, len(ranges[-p.range_expansion_baseline:]))
    expansion = fast / base if base > 0 else 1.0
    adx_series = compute_adx(bars, p.adx_period)
    adx = next((v for v in reversed(adx_series) if v is not None), 0.0)
    ema_fast = next((v for v in reversed(compute_ema(closes, p.ema_fast)) if v is not None), closes[-1])
    ema_slow = next((v for v in reversed(compute_ema(closes, p.ema_slow)) if v is not None), closes[-1])
    rsi = next((v for v in reversed(compute_rsi(bars, p.rsi_period)) if v is not None), 50.0)
    macd = next((v for v in reversed(compute_macd_histogram(bars)) if v is not None), 0.0)
    rel = next((v for v in reversed(relative_volume(bars, 30)) if v is not None), 1.0)

    highs, lows = confirmed_swings(bars, p.pivot_left, p.pivot_right)
    last_highs = [price for _, price in highs[-3:]]
    last_lows = [price for _, price in lows[-3:]]
    hh = len(last_highs) >= 2 and last_highs[-1] > last_highs[-2]
    lh = len(last_highs) >= 2 and last_highs[-1] < last_highs[-2]
    hl = len(last_lows) >= 2 and last_lows[-1] > last_lows[-2]
    ll = len(last_lows) >= 2 and last_lows[-1] < last_lows[-2]
    close = bars[-1].close
    bos_up = bool(last_highs) and close > last_highs[-1]
    bos_down = bool(last_lows) and close < last_lows[-1]

    window = list(bars[-(p.compression_window_bars + 1):-1])
    range_high = max((b.high for b in window), default=bars[-1].high)
    range_low = min((b.low for b in window), default=bars[-1].low)
    width = range_high - range_low
    compression_ratio = width / (atr * math.sqrt(p.compression_window_bars)) if atr > 0 else 1.0

    m15_available = len(m15) >= 240
    htf_bias: Optional[str] = None
    if m15_available:
        m15_close = m15[-1].close
        m15_ema = next((v for v in reversed(compute_ema([b.close for b in m15], p.ema_fast))
                        if v is not None), m15_close)
        htf_bias = "BUY" if m15_close > m15_ema else ("SELL" if m15_close < m15_ema else None)

    return Features(
        atr=atr, atr_ratio=atr / median if median > 0 else 1.0,
        atr_percentile=percentile_rank(atr_series, p.atr_percentile_lookback),
        range_expansion=expansion, adx=adx, ema_fast=ema_fast, ema_slow=ema_slow,
        rsi=rsi, macd_hist=macd, rel_volume=rel,
        swing_high=last_highs[-1] if last_highs else None,
        swing_low=last_lows[-1] if last_lows else None,
        hh=hh, hl=hl, lh=lh, ll=ll, bos_up=bos_up, bos_down=bos_down,
        range_high=range_high, range_low=range_low, compression_ratio=compression_ratio,
        htf_bias=htf_bias, m15_available=m15_available,
        session=session_of(bars[-1].time_ms),
        gold=gold_context(bars, gold_bars, p),
    )


# ---------------------------------------------------------------------------------------------
# Costs, sizing, RR
# ---------------------------------------------------------------------------------------------
def round_trip_cost_per_oz(price: float, p: SilverParams, spread_per_oz: Optional[float] = None) -> float:
    spread = spread_per_oz if spread_per_oz is not None else p.spread_per_oz
    commission = 2.0 * p.commission_per_oz
    slippage = price * p.slippage_bps / 10_000.0 * 2.0
    return commission + spread + slippage


def net_reward_risk(entry: float, stop: float, target: float, cost_per_oz: float) -> float:
    if not all(math.isfinite(v) for v in (entry, stop, target, cost_per_oz)):
        return 0.0
    risk = abs(entry - stop) + cost_per_oz
    if risk <= 0.0:
        return 0.0
    return (abs(target - entry) - cost_per_oz) / risk


def position_oz(entry: float, stop: float, equity: float, risk_pct: float, cost_per_oz: float,
                p: SilverParams) -> Tuple[float, float]:
    if not all(math.isfinite(v) for v in (entry, stop, equity, risk_pct, cost_per_oz)):
        return 0.0, 0.0
    if entry <= 0.0 or stop <= 0.0 or entry == stop or equity <= 0.0 or risk_pct <= 0.0:
        return 0.0, 0.0
    risk_per_oz = abs(entry - stop) + cost_per_oz
    if risk_per_oz <= 0.0:
        return 0.0, 0.0
    risk_usd = equity * risk_pct / 100.0
    oz = min(risk_usd / risk_per_oz, equity * p.max_notional_to_equity / entry)
    steps = math.floor(round(oz / p.qty_step_oz, 6))  # rounding the ratio avoids 107.09 vs 107.10
    oz = round(steps * p.qty_step_oz, 8)
    if oz <= 0.0 or oz * entry < p.min_notional_usd:
        return 0.0, 0.0
    return oz, oz * risk_per_oz


# ---------------------------------------------------------------------------------------------
# Engines
# ---------------------------------------------------------------------------------------------
@dataclass
class Candidate:
    engine: str
    state: str
    direction: str
    regime_score: int
    structure_score: int
    confirmation_score: int
    rr_score: int
    execution_score: int
    structural_level: Optional[float]
    invalidation: Optional[float]
    entry: Optional[float]
    stop: Optional[float]
    target: Optional[float]
    rr: Optional[float]
    net_rr: Optional[float]
    cost_per_oz: float
    cost_to_risk: float
    setup_type: str
    satisfied: List[str]
    missing: List[str]
    detail: str
    data_available: bool = True

    @property
    def score(self) -> int:
        return max(0, min(10, self.regime_score + self.structure_score + self.confirmation_score
                           + self.rr_score + self.execution_score))


def _no_trade(engine: str, state: str, detail: str, *, cost: float = 0.0,
              missing: Optional[List[str]] = None, satisfied: Optional[List[str]] = None,
              data_available: bool = True) -> Candidate:
    return Candidate(engine, state, "NO_TRADE", 0, 0, 0, 0, 0, None, None, None, None, None, None,
                     None, cost, 0.0, "", satisfied or [], missing or [], detail, data_available)


def session_points(session: str, p: SilverParams) -> int:
    if session in ("OVERLAP", "LONDON", "NEW_YORK"):
        return 2
    if session == "ASIA":
        return 1 if p.penalties_low_liquidity else 2
    return 0 if p.penalties_low_liquidity else 1


def execution_points(net_rr: float, cost_to_risk: float, session: str, p: SilverParams) -> int:
    rr_point = 1 if math.isfinite(net_rr) and net_rr >= p.min_reward_risk else 0
    cost_point = 2 if cost_to_risk <= 0.15 else (1 if cost_to_risk <= p.max_cost_to_risk_fraction else 0)
    session_point = session_points(session, p)
    if rr_point and cost_point == 2 and session_point == 2:
        return 2
    if rr_point and cost_point >= 1 and session_point >= 1:
        return 1
    return 0


def rr_points(net_rr: float, p: SilverParams) -> int:
    if not math.isfinite(net_rr):
        return 0
    if net_rr >= 2.0:
        return 2
    if net_rr >= p.min_reward_risk:
        return 1
    return 0


def stop_for(direction: str, raw: float, price: float, atr: float, p: SilverParams) -> float:
    distance = min(max(abs(price - raw) + p.stop_buffer_atr * atr, p.min_stop_atr * atr),
                   p.max_stop_atr * atr)
    return price - distance if direction == "BUY" else price + distance


def target_at(direction: str, entry: float, stop: float, rr: float) -> float:
    risk = abs(entry - stop)
    return entry + rr * risk if direction == "BUY" else entry - rr * risk


def engine_a(bars: Sequence[Bar], f: Features, regime: str, p: SilverParams, cost: float) -> Candidate:
    if not p.enable_a:
        return _no_trade("A_TREND", "IDLE", "engine A disabled", cost=cost)
    if regime not in ("BULL_TREND", "BEAR_TREND"):
        return _no_trade("A_TREND", "IDLE", f"regime {regime} does not allow continuation", cost=cost)
    if not f.m15_available:
        return _no_trade("A_TREND", "IDLE", "M15 frame missing", cost=cost)
    up = regime == "BULL_TREND"
    direction = "BUY" if up else "SELL"
    price = bars[-1].close
    atr = f.atr
    impulse_level = f.swing_low if up else f.swing_high
    if impulse_level is None:
        return _no_trade("A_TREND", "IDLE", "no confirmed swing", cost=cost)
    other = f.swing_high if up else f.swing_low
    impulse = abs(price - impulse_level)
    satisfied: List[str] = []
    missing: List[str] = []
    (satisfied if impulse >= p.min_impulse_atr * atr else missing).append(f"impulse {impulse / atr:.2f} ATR")
    zone = max(impulse_level, f.ema_fast) if up else min(impulse_level, f.ema_fast)
    distance = abs(price - zone)
    in_zone = distance <= p.pullback_zone_atr * atr
    (satisfied if in_zone else missing).append(f"pullback zone {distance / atr:.2f} ATR")
    depth = abs(other - price) / abs(other - impulse_level) if other is not None and other != impulse_level else float("nan")
    depth_ok = math.isfinite(depth) and p.min_pullback_frac <= depth <= p.max_pullback_frac
    (satisfied if depth_ok else missing).append(f"pullback depth {depth * 100:.0f}%" if math.isfinite(depth) else "pullback depth unknown")
    last = bars[-1]
    span = last.high - last.low
    body = abs(last.close - last.open) / span if span > 0 else 0.0
    body_ok = body >= p.min_body_frac and ((last.close > last.open) if up else (last.close < last.open))
    wick = ((min(last.open, last.close) - last.low) / span) if up and span > 0 else (
        (last.high - max(last.open, last.close)) / span if span > 0 else 0.0)
    wick_ok = wick >= 0.30
    rejection = body_ok or wick_ok
    (satisfied if rejection else missing).append("directional candle" if body_ok else "rejection wick")
    micro_highs, micro_lows = confirmed_swings(bars, p.micro_left, p.micro_right)
    micro_break = (bool(micro_highs) and price > micro_highs[-1][1]) if up else (
        bool(micro_lows) and price < micro_lows[-1][1])
    (satisfied if micro_break else missing).append("micro structure break")
    dist_ema = abs(price - f.ema_fast) / atr
    not_chasing = dist_ema <= p.max_chase_atr * 2.0
    (satisfied if not_chasing else missing).append(f"distance from EMA {dist_ema:.2f} ATR")
    momentum_ok = (f.macd_hist > 0 and f.rsi >= 45) if up else (f.macd_hist < 0 and f.rsi <= 55)
    (satisfied if momentum_ok else missing).append("momentum")
    ready = (impulse >= p.min_impulse_atr * atr and in_zone and depth_ok and rejection
             and not_chasing and (micro_break or body_ok))
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 4 else (
        "FORMING" if in_zone or micro_break else "WATCH"))
    level = min(impulse_level, zone) if up else max(impulse_level, zone)
    raw_stop = level - p.stop_buffer_atr * atr * 0.5 if up else level + p.stop_buffer_atr * atr * 0.5
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    target = target_at(direction, price, stop, max(p.min_reward_risk, p.target_rr))
    rr = abs(target - price) / risk if risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost)
    ctr = cost / risk if risk > 0 else float("inf")
    regime_score = 2
    if p.require_gold_confirmation and f.gold.available and f.gold.bias not in ("NO_TRADE", direction):
        regime_score = 1
    return Candidate(
        "A_TREND", state, direction if ready else "NO_TRADE",
        regime_score,
        2 if impulse >= p.min_impulse_atr * atr and depth_ok else (1 if in_zone else 0),
        2 if micro_break and rejection else (1 if rejection or micro_break else 0),
        rr_points(net, p), execution_points(net, ctr, f.session, p),
        level, stop, price, stop, target, rr, net, cost, ctr, "TREND_CONTINUATION",
        satisfied, missing, f"{regime} · RR net {net:.2f}",
    )


def engine_b(bars: Sequence[Bar], f: Features, regime: str, p: SilverParams, cost: float) -> Candidate:
    if not p.enable_b:
        return _no_trade("B_SWEEP", "IDLE", "engine B disabled", cost=cost)
    if regime == "HIGH_VOL":
        return _no_trade("B_SWEEP", "IDLE", "sweeps disabled in high volatility", cost=cost)
    atr = f.atr
    price = bars[-1].close
    window = bars[-p.sweep_window_bars:]
    highs, lows = confirmed_swings(bars, p.pivot_left, p.pivot_right)
    prior_low = lows[-1][1] if lows else None
    prior_high = highs[-1][1] if highs else None
    swept_low = prior_low is not None and any(
        b.low < prior_low and b.low >= prior_low - p.max_sweep_atr * atr for b in window)
    swept_high = prior_high is not None and any(
        b.high > prior_high and b.high <= prior_high + p.max_sweep_atr * atr for b in window)
    satisfied: List[str] = []
    missing: List[str] = []
    if swept_low and prior_low is not None and price > prior_low:
        direction, level = "BUY", prior_low
    elif swept_high and prior_high is not None and price < prior_high:
        direction, level = "SELL", prior_high
    else:
        return _no_trade("B_SWEEP", "WATCH", "no confirmed sweep", cost=cost,
                         missing=["no confirmed liquidity sweep"])
    satisfied.append("swept a confirmed level and closed back inside")
    sweep_bar = next((b for b in reversed(window)
                      if (b.low < level if direction == "BUY" else b.high > level)), None)
    penetration = 0.0
    if sweep_bar is not None:
        penetration = (level - sweep_bar.low) / atr if direction == "BUY" else (sweep_bar.high - level) / atr
    (satisfied if penetration >= p.min_sweep_atr else missing).append(f"penetration {penetration:.2f} ATR")
    micro_highs, micro_lows = confirmed_swings(bars, p.micro_left, p.micro_right, len(bars) - 2)
    shift = (bool(micro_highs) and price > micro_highs[-1][1]) if direction == "BUY" else (
        bool(micro_lows) and price < micro_lows[-1][1])
    (satisfied if shift else missing).append("micro shift")
    last = bars[-1]
    span = last.high - last.low
    close_loc = (last.close - last.low) / span if span > 0 else 0.5
    close_ok = close_loc >= 0.55 if direction == "BUY" else close_loc <= 0.45
    (satisfied if close_ok else missing).append("close in the right half")
    retest_ok = True
    if p.require_retest:
        retest_ok = (last.low <= level + p.retest_tolerance_atr * atr and price > level) if direction == "BUY" else (
            last.high >= level - p.retest_tolerance_atr * atr and price < level)
        (satisfied if retest_ok else missing).append("retest of the reclaimed level")
    ready = penetration >= p.min_sweep_atr and shift and close_ok and retest_ok
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 3 else "FORMING")
    raw_stop = (sweep_bar.low if sweep_bar else level - atr * 0.5) - p.stop_buffer_atr * atr if direction == "BUY" else (
        (sweep_bar.high if sweep_bar else level + atr * 0.5) + p.stop_buffer_atr * atr)
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    if direction == "BUY":
        opposite = f.range_high if f.range_high > price else price + 2.0 * risk
        target = max(opposite, price + p.min_reward_risk * risk)
    else:
        opposite = f.range_low if f.range_low < price else price - 2.0 * risk
        target = min(opposite, price - p.min_reward_risk * risk)
    rr = abs(target - price) / risk if risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost)
    ctr = cost / risk if risk > 0 else float("inf")
    regime_score = 2 if regime in ("RANGE", "COMPRESSION") else 1
    structure_score = 2 if penetration >= p.min_sweep_atr and (
        abs(level - f.range_low) < atr * 0.5 or abs(level - f.range_high) < atr * 0.5) else (
        1 if penetration >= p.min_sweep_atr else 0)
    return Candidate(
        "B_SWEEP", state, direction if ready else "NO_TRADE", regime_score, structure_score,
        2 if shift and close_ok else (1 if shift or close_ok else 0),
        rr_points(net, p), execution_points(net, ctr, f.session, p),
        level, stop, price, stop, target, rr, net, cost, ctr, "LIQUIDITY_SWEEP",
        satisfied, missing, f"sweep {penetration:.2f} ATR · RR net {net:.2f}",
    )


def engine_c_range(bars: Sequence[Bar], f: Features, regime: str, p: SilverParams, cost: float) -> Candidate:
    if not p.enable_c_range:
        return _no_trade("C_RANGE", "IDLE", "engine C range disabled", cost=cost)
    if regime != "RANGE":
        return _no_trade("C_RANGE", "IDLE", f"regime {regime} does not allow mean reversion", cost=cost)
    atr = f.atr
    price = bars[-1].close
    width = f.range_high - f.range_low
    if width <= 0:
        return _no_trade("C_RANGE", "WATCH", "invalid range width", cost=cost)
    if price > f.range_high + p.range_invalidation_atr * atr or price < f.range_low - p.range_invalidation_atr * atr:
        return _no_trade("C_RANGE", "INVALIDATED", "range invalidated by a real breakout", cost=cost)
    position = (price - f.range_low) / width
    near_low = position <= p.range_touch_frac
    near_high = position >= 1.0 - p.range_touch_frac
    if not near_low and not near_high:
        return _no_trade("C_RANGE", "WATCH", f"middle of the range ({position * 100:.0f}%)", cost=cost,
                         missing=[f"price at {position * 100:.0f}% of the range"])
    direction = "BUY" if near_low else "SELL"
    satisfied = [f"price at {position * 100:.0f}% of the range"]
    missing: List[str] = []
    last = bars[-1]
    span = last.high - last.low
    wick = ((min(last.open, last.close) - last.low) / span) if direction == "BUY" and span > 0 else (
        (last.high - max(last.open, last.close)) / span if span > 0 else 0.0)
    (satisfied if wick >= p.min_rejection_wick_frac else missing).append(f"rejection wick {wick * 100:.0f}%")
    rsi_ok = f.rsi <= 40 if direction == "BUY" else f.rsi >= 60
    (satisfied if rsi_ok else missing).append(f"RSI {f.rsi:.0f}")
    adx_ok = f.adx <= p.range_adx_max + 2.0
    (satisfied if adx_ok else missing).append(f"ADX {f.adx:.1f}")
    level = f.range_low if direction == "BUY" else f.range_high
    raw_stop = level - p.stop_buffer_atr * atr if direction == "BUY" else level + p.stop_buffer_atr * atr
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    mid = (f.range_high + f.range_low) / 2.0
    target = max(mid, price + p.min_reward_risk * risk) if direction == "BUY" else min(
        mid, price - p.min_reward_risk * risk)
    rr = abs(target - price) / risk if risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost)
    ctr = cost / risk if risk > 0 else float("inf")
    ready = (len(satisfied) >= 4 and wick >= p.min_rejection_wick_frac and rsi_ok and adx_ok
             and net >= p.min_reward_risk)
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 2 else "FORMING")
    return Candidate(
        "C_RANGE", state, direction if ready else "NO_TRADE", 2,
        2 if near_low or near_high else 1,
        2 if wick >= p.min_rejection_wick_frac and rsi_ok else (1 if wick >= p.min_rejection_wick_frac else 0),
        rr_points(net, p), execution_points(net, ctr, f.session, p),
        level, stop, price, stop, target, rr, net, cost, ctr, "RANGE_REVERSION",
        satisfied, missing, f"position {position * 100:.0f}% · RR net {net:.2f}",
    )


def engine_c_breakout(bars: Sequence[Bar], f: Features, regime: str, p: SilverParams, cost: float) -> Candidate:
    if not p.enable_c_breakout:
        return _no_trade("C_BREAKOUT", "IDLE", "engine C breakout disabled", cost=cost)
    if regime == "HIGH_VOL":
        return _no_trade("C_BREAKOUT", "IDLE", "breakouts disabled in high volatility", cost=cost)
    atr = f.atr
    price = bars[-1].close
    compressed = f.compression_ratio <= p.compression_max_range_atr or regime == "COMPRESSION"
    up_break = price > f.range_high + p.min_breakout_atr * atr
    down_break = price < f.range_low - p.min_breakout_atr * atr
    direction = "BUY" if up_break else ("SELL" if down_break else "NO_TRADE")
    satisfied: List[str] = []
    missing: List[str] = []
    (satisfied if compressed else missing).append(f"compression ratio {f.compression_ratio:.2f}")
    (satisfied if direction != "NO_TRADE" else missing).append("close outside the range boundary")
    expansion_ok = f.range_expansion >= p.min_expansion_for_breakout
    (satisfied if expansion_ok else missing).append(f"range expansion {f.range_expansion:.2f}x")
    volume_ok = f.rel_volume >= p.min_breakout_rel_volume
    (satisfied if volume_ok else missing).append(f"relative volume {f.rel_volume:.2f}x")
    last = bars[-1]
    span = last.high - last.low
    close_frac = (last.close - last.low) / span if span > 0 else 0.5
    follow_through = close_frac >= p.follow_through_frac if direction == "BUY" else close_frac <= 1.0 - p.follow_through_frac
    if direction != "NO_TRADE":
        (satisfied if follow_through else missing).append(f"close in breakout direction ({close_frac * 100:.0f}%)")
    level = f.range_high if direction == "BUY" else f.range_low
    late = 0.0
    if direction == "BUY":
        late = (price - f.range_high) / atr
    elif direction == "SELL":
        late = (f.range_low - price) / atr
    not_late = late <= p.min_breakout_atr + p.max_breakout_chase_atr
    if direction != "NO_TRADE":
        (satisfied if not_late else missing).append(f"late entry {late:.2f} ATR")
    ready = direction != "NO_TRADE" and compressed and expansion_ok and volume_ok and follow_through and not_late
    state = "READY" if ready else ("CONFIRMATION" if direction != "NO_TRADE" else (
        "FORMING" if compressed else "WATCH"))
    measured_move = f.range_high - f.range_low
    raw_stop = level - p.stop_buffer_atr * atr if direction == "BUY" else level + p.stop_buffer_atr * atr
    stop = None if direction == "NO_TRADE" else stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop) if stop is not None else 0.0
    fixed = target_at(direction, price, stop, max(p.min_reward_risk, p.target_rr)) if stop is not None else None
    measured = (price + measured_move) if direction == "BUY" else (price - measured_move)
    capped = None
    if stop is not None and risk > 0:
        capped = min(measured, price + p.max_measured_move_rr * risk) if direction == "BUY" else max(
            measured, price - p.max_measured_move_rr * risk)
    target = None
    if fixed is not None and capped is not None:
        target = max(fixed, capped) if direction == "BUY" else min(fixed, capped)
    rr = abs(target - price) / risk if target is not None and risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost) if stop is not None and target is not None else float("nan")
    ctr = cost / risk if risk > 0 else float("inf")
    return Candidate(
        "C_BREAKOUT", state, direction if ready else "NO_TRADE",
        2 if compressed else 1,
        2 if compressed and direction != "NO_TRADE" else (1 if compressed else 0),
        2 if expansion_ok and volume_ok and follow_through else (1 if expansion_ok or volume_ok else 0),
        rr_points(net, p), execution_points(net, ctr, f.session, p),
        level if direction != "NO_TRADE" else None, stop,
        price if direction != "NO_TRADE" else None, stop, target, rr, net, cost, ctr,
        "VOLATILITY_BREAKOUT", satisfied, missing,
        f"compression {f.compression_ratio:.2f} · expansion {f.range_expansion:.2f}x",
    )


def engine_x(f: Features) -> Candidate:
    gold = f.gold
    if not gold.available:
        return _no_trade("X_CROSS", "UNAVAILABLE",
                         "no aligned XAU/USD bars; cross module unavailable", data_available=False)
    return Candidate(
        "X_CROSS", "WATCH", "NO_TRADE", 0, 0, 1 if gold.bias != "NO_TRADE" else 0, 0, 1,
        None, None, None, None, None, None, None, 0.0, 0.0, "CROSS_CONTEXT",
        [x for x in (f"corr {gold.correlation:.2f}" if gold.correlation is not None else None,
                     f"rel {gold.relative_momentum * 100:.3f}%" if gold.relative_momentum is not None else None)
         if x],
        [], gold.note,
    )


# ---------------------------------------------------------------------------------------------
# Decision
# ---------------------------------------------------------------------------------------------
@dataclass
class Decision:
    bar_time: int
    regime: str
    volatility: str
    session: str
    engines: List[Candidate]
    action: str
    chosen_engine: Optional[str]
    score: int
    entry: Optional[float]
    stop: Optional[float]
    target: Optional[float]
    rr: Optional[float]
    net_rr: Optional[float]
    cost_per_oz: float
    cost_to_risk: float
    qty_oz: float
    risk_usd: float
    conflicts: List[str]
    no_trade_reasons: List[str]
    setup_key: str
    gold_available: bool
    raw_regime: str = ""


def decide(
    bars: Sequence[Bar],
    m15: Sequence[Bar],
    gold_bars: Sequence[Bar],
    p: SilverParams,
    daily_realized_loss_pct: float = 0.0,
    previous_raw: Optional[str] = None,
    daily_loss_cap_pct: Optional[float] = None,
) -> Decision:
    """Mirror of ``XagAresEngine.analyze``: hysteresis compares the *raw* label of the previous
    bar, exactly as the Kotlin engine does, so one noisy candle cannot swap the engine set."""
    f = build_features(bars, m15, gold_bars, p)
    vol = volatility_of(f, p)
    now_raw = raw_regime(f, p)
    regime, _ = classify_regime(now_raw, previous_raw, p)
    price = bars[-1].close
    cost = round_trip_cost_per_oz(price, p)
    candidates = [engine_a(bars, f, regime, p, cost), engine_b(bars, f, regime, p, cost),
                  engine_c_range(bars, f, regime, p, cost), engine_c_breakout(bars, f, regime, p, cost),
                  engine_x(f)]
    ready = sorted((c for c in candidates if c.state == "READY" and c.direction != "NO_TRADE"),
                   key=lambda c: c.score, reverse=True)
    conflicts: List[str] = []
    if len(ready) >= 2:
        top = ready[0]
        opposite = next((c for c in ready if c.direction != top.direction), None)
        if opposite is not None and abs(top.score - opposite.score) < p.conflict_margin_10:
            conflicts.append(f"conflict {top.engine} vs {opposite.engine}")
    reasons: List[str] = []
    best = None if conflicts or not ready else ready[0]
    if best is None and not conflicts:
        reasons.append("no engine reached READY; " + ", ".join(
            f"{c.engine}={c.state}" for c in candidates
            if c.state not in ("IDLE", "WATCH", "UNAVAILABLE")))
    if best is not None and best.score < p.score_threshold_10:
        reasons.append(f"score {best.score}/10 < {p.score_threshold_10}")
    if best is not None and (best.net_rr or 0.0) < p.min_reward_risk:
        reasons.append(f"net R:R {(best.net_rr or 0.0):.2f} < {p.min_reward_risk}")
    if best is not None and best.cost_to_risk > p.max_cost_to_risk_fraction:
        reasons.append(f"cost/risk {best.cost_to_risk:.2f} > {p.max_cost_to_risk_fraction}")
    cap = p.max_daily_loss_pct if daily_loss_cap_pct is None else daily_loss_cap_pct
    if daily_realized_loss_pct >= cap:
        reasons.append("daily loss cap reached")
    if regime == "HIGH_VOL":
        reasons.append("high/uncertain volatility: no new entries")
    if p.session_gate and f.session == "OFF":
        reasons.append("session gate blocks OFF hours")
    if p.require_gold_confirmation:
        if not f.gold.available:
            reasons.append("gold confirmation required but unavailable")
        elif best is not None and f.gold.bias not in ("NO_TRADE", best.direction):
            reasons.append("gold disagrees and the confirmation filter is on")

    actionable = best if not reasons else None
    oz, risk_usd = (position_oz(actionable.entry, actionable.stop, p.account_equity_usd,
                                p.risk_per_trade_pct, actionable.cost_per_oz, p)
                    if actionable and actionable.entry is not None and actionable.stop is not None
                    else (0.0, 0.0))
    if actionable is not None and oz <= 0.0:
        reasons.append("size below the minimum notional")
    action = actionable.direction if actionable and oz > 0.0 and not reasons else "NO_TRADE"
    key = ""
    if action != "NO_TRADE" and actionable is not None:
        key = "|".join(["ALS-5S", "XAG/USD", actionable.engine, actionable.setup_type, action,
                        f"{actionable.structural_level or 0.0:.3f}", str(bars[-1].time_ms)])
    return Decision(
        bar_time=bars[-1].time_ms, regime=regime, volatility=vol, session=f.session,
        engines=candidates, action=action,
        chosen_engine=actionable.engine if action != "NO_TRADE" else None,
        score=actionable.score if action != "NO_TRADE" else (best.score if best else 0),
        entry=actionable.entry if action != "NO_TRADE" else None,
        stop=actionable.stop if action != "NO_TRADE" else None,
        target=actionable.target if action != "NO_TRADE" else None,
        rr=actionable.rr if action != "NO_TRADE" else None,
        net_rr=actionable.net_rr if action != "NO_TRADE" else None,
        cost_per_oz=cost,
        cost_to_risk=actionable.cost_to_risk if action != "NO_TRADE" else 0.0,
        qty_oz=oz if action != "NO_TRADE" else 0.0,
        risk_usd=risk_usd if action != "NO_TRADE" else 0.0,
        conflicts=conflicts, no_trade_reasons=reasons, setup_key=key,
        gold_available=f.gold.available, raw_regime=now_raw,
    )


# ---------------------------------------------------------------------------------------------
# Replay
# ---------------------------------------------------------------------------------------------
@dataclass
class Trade:
    engine: str
    side: str
    regime: str
    session: str
    entry_time: int
    entry: float
    stop: float
    target: float
    exit_time: int
    exit_price: float
    qty_oz: float
    gross_pnl_usd: float
    cost_usd: float
    net_pnl_usd: float
    r_multiple: float
    outcome: str
    bars_held: int
    gold_available: bool


def resample(bars: Sequence[Bar], step_ms: int) -> List[Bar]:
    out: List[Bar] = []
    bucket: List[Bar] = []
    for bar in bars:
        if bucket and bar.time_ms // step_ms != bucket[0].time_ms // step_ms:
            out.append(Bar(bucket[0].time_ms, bucket[0].open, max(b.high for b in bucket),
                           min(b.low for b in bucket), bucket[-1].close,
                           sum(b.volume for b in bucket)))
            bucket = []
        bucket.append(bar)
    if bucket:
        out.append(Bar(bucket[0].time_ms, bucket[0].open, max(b.high for b in bucket),
                       min(b.low for b in bucket), bucket[-1].close, sum(b.volume for b in bucket)))
    return out


def simulate_trade(
    bars: Sequence[Bar], trigger_index: int, action: str, planned_entry: float, stop: float,
    target: float, qty_oz: float, p: SilverParams, spread_per_oz: Optional[float] = None,
) -> Dict[str, object]:
    """Fill on the next open, then walk bar by bar. The stop always wins a tie with the target.

    Slippage is charged in the direction of the trade on both sides, the spread is charged once
    (half on each side), and the stop is allowed to be *through* the fill on a gap — the exit is
    filled at the stop price, not at the friendlier one the plan assumed.
    """
    entry = planned_entry
    if trigger_index + 1 >= len(bars) or qty_oz <= 0.0:
        return {"qty_oz": 0.0}
    fill_bar = bars[trigger_index + 1]
    slip = fill_bar.open * p.slippage_bps / 10_000.0
    entry = fill_bar.open + slip if action == "BUY" else fill_bar.open - slip
    if (action == "BUY" and entry <= stop) or (action == "SELL" and entry >= stop):
        return {"qty_oz": 0.0}  # the market gapped past the invalidation: refuse, never widen
    qty = qty_oz
    spread = p.spread_per_oz if spread_per_oz is None else spread_per_oz
    cost = qty * (2.0 * p.commission_per_oz + spread)
    exit_price: Optional[float] = None
    exit_time = 0
    outcome = "EXPIRED"
    bars_held = 0
    partial_taken = False
    realized_from_partial = 0.0
    horizon = p.max_hold_bars * (2 if p.exit_model == "PARTIAL_HALF" else 1)
    for j in range(trigger_index + 1, min(trigger_index + 1 + horizon, len(bars))):
        b = bars[j]
        bars_held = j - trigger_index
        stop_hit = b.low <= stop if action == "BUY" else b.high >= stop
        target_hit = b.high >= target if action == "BUY" else b.low <= target
        if p.exit_model == "TRAIL_AFTER_1R":
            risk = abs(entry - stop)
            moved = (b.high - entry) if action == "BUY" else (entry - b.low)
            if moved >= risk:
                trail = entry + 0.5 * risk if action == "BUY" else entry - 0.5 * risk
                stop = max(stop, trail) if action == "BUY" else min(stop, trail)
        if p.exit_model == "PARTIAL_HALF" and not partial_taken:
            half = entry + (target - entry) * 0.5 if action == "BUY" else entry - (entry - target) * 0.5
            half_hit = b.high >= half if action == "BUY" else b.low <= half
            if half_hit and not stop_hit:
                partial_taken = True
                realized_from_partial = ((half - entry) if action == "BUY" else (entry - half)) * (qty / 2.0)
                qty = qty / 2.0
                stop = entry  # break-even on the remainder, never further away
        if stop_hit:
            exit_price, exit_time, outcome = stop, b.time_ms, "STOP"
            break
        if target_hit:
            exit_price, exit_time, outcome = target, b.time_ms, "TARGET"
            break
    if exit_price is None:
        last = bars[min(trigger_index + p.max_hold_bars, len(bars) - 1)]
        exit_price, exit_time = last.close, last.time_ms
    exit_slip = exit_price * p.slippage_bps / 10_000.0
    exit_fill = exit_price - exit_slip if action == "BUY" else exit_price + exit_slip
    gross = ((exit_fill - entry) * qty if action == "BUY" else (entry - exit_fill) * qty)
    gross += realized_from_partial
    return {
        "qty_oz": qty_oz, "entry": entry, "exit_price": exit_fill, "exit_time": exit_time,
        "entry_time": fill_bar.time_ms, "gross_pnl_usd": gross, "cost_usd": cost,
        "net_pnl_usd": gross - cost, "outcome": outcome, "bars_held": bars_held,
    }


def replay(
    bars: Sequence[Bar],
    m15: Sequence[Bar],
    gold_bars: Sequence[Bar],
    p: SilverParams,
    warmup: int = 280,
    daily_loss_cap: Optional[float] = None,
) -> Tuple[List[Trade], List[Decision]]:
    """Decide on the close of bar i, fill at the open of bar i+1; the stop wins a tie."""
    cap = p.max_daily_loss_pct if daily_loss_cap is None else daily_loss_cap
    gold_by_time = {b.time_ms: b for b in gold_bars}
    trades: List[Trade] = []
    decisions: List[Decision] = []
    equity = p.account_equity_usd
    day_start = equity
    current_day: Optional[int] = None
    busy_until = -1
    previous_raw: Optional[str] = None
    for i in range(warmup, len(bars) - 1):
        bar = bars[i]
        day = bar.time_ms // 86_400_000
        if day != current_day:
            current_day = day
            day_start = equity
        realized_loss = max(0.0, (day_start - equity) / day_start * 100.0) if day_start > 0 else 0.0
        m15_closed = [b for b in m15 if b.time_ms + M15_MS <= bar.time_ms + M5_MS]
        gold_window = [gold_by_time[t] for t in range(bar.time_ms - 120 * M5_MS, bar.time_ms + 1, M5_MS)
                       if t in gold_by_time]
        decision = decide(bars[:i + 1], m15_closed, gold_window, p, realized_loss, previous_raw, cap)
        decisions.append(decision)
        previous_raw = decision.raw_regime
        if i <= busy_until or decision.action == "NO_TRADE":
            continue
        assert decision.entry is not None and decision.stop is not None and decision.target is not None
        fill = simulate_trade(
            bars, i, decision.action, decision.entry, decision.stop, decision.target,
            decision.qty_oz, p, spread_per_oz=p.spread_per_oz)
        if fill["qty_oz"] <= 0.0:
            continue
        qty = fill["qty_oz"]
        cost = fill["cost_usd"]
        gross = fill["gross_pnl_usd"]
        net = fill["net_pnl_usd"]
        risk_per_oz = abs(fill["entry"] - decision.stop) + p.commission_per_oz * 2 + p.spread_per_oz
        r_multiple = net / (risk_per_oz * qty) if risk_per_oz * qty > 0 else 0.0
        trades.append(Trade(
            engine=decision.chosen_engine or "", side=decision.action, regime=decision.regime,
            session=decision.session, entry_time=fill["entry_time"], entry=fill["entry"],
            stop=decision.stop, target=decision.target, exit_time=fill["exit_time"],
            exit_price=fill["exit_price"], qty_oz=qty, gross_pnl_usd=gross, cost_usd=cost,
            net_pnl_usd=net, r_multiple=r_multiple, outcome=fill["outcome"],
            bars_held=fill["bars_held"], gold_available=decision.gold_available))
        equity += net
        busy_until = i + max(1, fill["bars_held"])
    return trades, decisions


def summarize(trades: Sequence[Trade], initial_equity: float = 10_000.0) -> Dict[str, object]:
    total = len(trades)
    base: Dict[str, object] = {
        "trade_count": total, "win_rate_pct": 0.0, "net_pnl_usd": 0.0, "return_pct": 0.0,
        "profit_factor": 0.0, "expectancy_r": 0.0, "avg_win_usd": 0.0, "avg_loss_usd": 0.0,
        "max_drawdown_pct": 0.0, "max_consecutive_losses": 0, "costs_usd": 0.0,
        "avg_cost_per_trade_usd": 0.0, "gross_pnl_usd": 0.0,
        "by_engine": {}, "by_regime": {}, "by_side": {}, "by_session": {},
    }
    if total == 0:
        return base
    wins = [t for t in trades if t.net_pnl_usd > 0]
    losses = [t for t in trades if t.net_pnl_usd <= 0]
    gross_win = sum(t.net_pnl_usd for t in wins)
    gross_loss = abs(sum(t.net_pnl_usd for t in losses))
    net = sum(t.net_pnl_usd for t in trades)
    equity, peak, max_dd = initial_equity, initial_equity, 0.0
    streak = worst = 0
    for t in trades:
        equity += t.net_pnl_usd
        peak = max(peak, equity)
        if peak > 0:
            max_dd = max(max_dd, (peak - equity) / peak * 100.0)
        if t.net_pnl_usd <= 0:
            streak += 1
            worst = max(worst, streak)
        else:
            streak = 0

    def group(key) -> Dict[str, Dict[str, float]]:
        buckets: Dict[str, List[Trade]] = {}
        for t in trades:
            buckets.setdefault(key(t), []).append(t)
        return {k: {"count": len(v),
                    "net_pnl_usd": round(sum(x.net_pnl_usd for x in v), 2),
                    "expectancy_r": round(sum(x.r_multiple for x in v) / len(v), 3),
                    "win_rate_pct": round(100.0 * sum(1 for x in v if x.net_pnl_usd > 0) / len(v), 2)}
                for k, v in sorted(buckets.items())}

    base.update({
        "win_rate_pct": round(100.0 * len(wins) / total, 2),
        "net_pnl_usd": round(net, 2),
        "return_pct": round(net / initial_equity * 100.0, 3),
        "profit_factor": round(gross_win / gross_loss, 3) if gross_loss > 0 else 0.0,
        "expectancy_r": round(sum(t.r_multiple for t in trades) / total, 3),
        "avg_win_usd": round(gross_win / len(wins), 2) if wins else 0.0,
        "avg_loss_usd": round(-gross_loss / len(losses), 2) if losses else 0.0,
        "max_drawdown_pct": round(max_dd, 3),
        "max_consecutive_losses": worst,
        "costs_usd": round(sum(t.cost_usd for t in trades), 2),
        "avg_cost_per_trade_usd": round(sum(t.cost_usd for t in trades) / total, 3),
        "gross_pnl_usd": round(sum(t.gross_pnl_usd for t in trades), 2),
        "by_engine": group(lambda t: t.engine),
        "by_regime": group(lambda t: t.regime),
        "by_side": group(lambda t: t.side),
        "by_session": group(lambda t: t.session),
    })
    return base


# ---------------------------------------------------------------------------------------------
# Fixtures / CSV
# ---------------------------------------------------------------------------------------------
def generate_synthetic_fixture(
    count: int = 3000, seed: int = 20260220, noise_bps: float = 60.0, base: float = 30.0
) -> List[Bar]:
    """Deterministic silver-like fixture: regimes rotate so every engine can be exercised.

    Synthetic by construction: its metrics describe the fixture, never silver.
    """
    bars: List[Bar] = []
    price = base
    t0 = 1_760_000_000_000 - (1_760_000_000_000 % M5_MS)
    state = seed
    for i in range(count):
        state = (1103515245 * state + 12345) % (2 ** 31)
        noise = (state / 2 ** 31 - 0.5) * 2.0
        phase = (i // 120) % 4
        drift = (0.08, -0.08, 0.0, 0.0)[phase]
        vol = (1.0, 1.0, 0.35, 1.8)[phase]
        open_price = price
        close = open_price * (1.0 + (drift + noise * noise_bps * vol) / 10_000.0)
        wick = abs(noise) * open_price * (0.6 + vol) * noise_bps / 10_000.0
        bars.append(Bar(t0 + i * M5_MS, open_price, max(open_price, close) + wick,
                        min(open_price, close) - wick, close, 40.0 + abs(noise) * 90.0 * vol))
        price = close
    return bars


def load_csv_bars(path: Path) -> List[Bar]:
    bars: List[Bar] = []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ValueError("CSV has no header")
        names = {name.strip().lower(): name for name in reader.fieldnames}
        missing = {"time", "open", "high", "low", "close"} - set(names)
        if missing:
            raise ValueError(f"CSV is missing column(s): {', '.join(sorted(missing))}")
        for row in reader:
            raw_time = row[names["time"]].strip()
            if raw_time.isdigit():
                time_ms = int(raw_time)
                if time_ms < 10_000_000_000:
                    time_ms *= 1000
            else:
                parsed = datetime.fromisoformat(raw_time.replace("Z", "+00:00"))
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=timezone.utc)
                time_ms = int(parsed.timestamp() * 1000)
            volume = float(row[names["volume"]]) if "volume" in names and row[names["volume"]] else 100.0
            bars.append(Bar(time_ms, float(row[names["open"]]), float(row[names["high"]]),
                            float(row[names["low"]]), float(row[names["close"]]), volume))
    bars.sort(key=lambda b: b.time_ms)
    return bars


# ---------------------------------------------------------------------------------------------
# Stages A–E
# ---------------------------------------------------------------------------------------------
def _metrics(trades: Sequence[Trade], equity: float) -> Dict[str, object]:
    s = summarize(trades, equity)
    return {k: s[k] for k in ("trade_count", "win_rate_pct", "expectancy_r", "net_pnl_usd",
                              "profit_factor", "max_drawdown_pct", "max_consecutive_losses")}


def run_stages(
    bars: Sequence[Bar], gold_bars: Sequence[Bar] = (), params: Optional[SilverParams] = None,
) -> Dict[str, object]:
    """A) engines alone, B) combined, C) ablation, D) robustness buckets, E) chronological split.

    Every stage re-runs the *same* replay; nothing is measured twice with different rules.
    """
    p = params or SilverParams()
    m15 = resample(bars, M15_MS)
    warmup = min(p.min_bars, max(30, len(bars) // 3))
    out: Dict[str, object] = {"engine_alone": {}, "combined": {}, "ablation": {},
                              "robustness": {}, "split": {}, "gold_filter": {}}
    flags = {
        "A_TREND": dict(enable_a=True, enable_b=False, enable_c_range=False, enable_c_breakout=False),
        "B_SWEEP": dict(enable_a=False, enable_b=True, enable_c_range=False, enable_c_breakout=False),
        "C_RANGE": dict(enable_a=False, enable_b=False, enable_c_range=True, enable_c_breakout=False),
        "C_BREAKOUT": dict(enable_a=False, enable_b=False, enable_c_range=False, enable_c_breakout=True),
    }
    for name, kw in flags.items():
        solo = replace(p, **kw)
        trades, _ = replay(bars, m15, gold_bars, solo, warmup=warmup)
        out["engine_alone"][name] = _metrics(trades, solo.account_equity_usd)

    combined, _ = replay(bars, m15, gold_bars, p, warmup=warmup)
    out["combined"] = _metrics(combined, p.account_equity_usd)

    # Ablation: remove each feature family, one at a time.
    ablations = {
        "without_gold_module": dict(gold_enabled=False),
        "without_session_penalty": dict(penalties_low_liquidity=False),
        "without_cost_gate": dict(max_cost_to_risk_fraction=10.0),
        "without_net_rr_gate": dict(min_reward_risk=0.0),
        "without_engine_A": dict(enable_a=False),
        "without_engine_B": dict(enable_b=False),
        "without_engine_C_range": dict(enable_c_range=False),
        "without_engine_C_breakout": dict(enable_c_breakout=False),
    }
    for name, kw in ablations.items():
        variant = replace(p, **kw)
        trades, _ = replay(bars, m15, gold_bars, variant, warmup=warmup)
        out["ablation"][name] = _metrics(trades, p.account_equity_usd)

    # D) robustness: cost sensitivity and, when a real tape provides them, regime/session buckets.
    for label, multiplier in (("base", 1.0), ("double_spread", 2.0), ("triple_spread", 3.0)):
        variant = replace(p, spread_per_oz=p.spread_per_oz * multiplier)
        trades, _ = replay(bars, m15, gold_bars, variant, warmup=warmup)
        out["robustness"][label] = _metrics(trades, p.account_equity_usd)
    if combined:
        s = summarize(combined, p.account_equity_usd)
        out["robustness"]["by_side"] = s["by_side"]
        out["robustness"]["by_regime"] = s["by_regime"]
        out["robustness"]["by_session"] = s["by_session"]

    # E) chronological dev / validation / untouched test.
    third = len(bars) // 3
    if third > warmup + 50:
        for label, start, end in (("dev", 0, third), ("validation", third, 2 * third),
                                  ("out_of_sample", 2 * third, len(bars))):
            segment = bars[start:end]
            if len(segment) > warmup:
                trades, _ = replay(segment, resample(segment, M15_MS), gold_bars, p)
                out["split"][label] = _metrics(trades, p.account_equity_usd)

    # Silver-only vs gold-filtered, when gold data exists.
    if gold_bars:
        for label, kw in (("silver_only", dict(require_gold_confirmation=False)),
                          ("gold_confirmed", dict(require_gold_confirmation=True))):
            variant = replace(p, **kw)
            trades, _ = replay(bars, m15, gold_bars, variant, warmup=warmup)
            out["gold_filter"][label] = _metrics(trades, p.account_equity_usd)
    else:
        out["gold_filter"]["note"] = "no XAU/USD series supplied; comparison not run"
    return out


# ---------------------------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------------------------
def load_bars(csv_path: Optional[str], count: int, noise_bps: float) -> Tuple[List[Bar], str]:
    if csv_path:
        path = Path(csv_path)
        bars = load_csv_bars(path)
        if len(bars) < 300:
            raise SystemExit(f"{path} has {len(bars)} bars; ALS-5S needs at least 300 M5 bars")
        return bars, f"csv:{path}"
    return generate_synthetic_fixture(count, noise_bps=noise_bps), "synthetic"


def report(bars: List[Bar], gold_bars: List[Bar], source: str, p: SilverParams,
           stages: bool) -> Dict[str, object]:
    m15 = resample(bars, M15_MS)
    warmup = min(p.min_bars, max(30, len(bars) // 3))
    trades, decisions = replay(bars, m15, gold_bars, p, warmup=warmup)
    engine_states: Dict[str, Dict[str, int]] = {e: {} for e in ENGINE_IDS}
    for d in decisions:
        for c in d.engines:
            engine_states[c.engine][c.state] = engine_states[c.engine].get(c.state, 0) + 1
    no_trade_counts: Dict[str, int] = {}
    for d in decisions:
        for reason in d.no_trade_reasons:
            head = reason.split(";")[0][:80]
            no_trade_counts[head] = no_trade_counts.get(head, 0) + 1
    payload: Dict[str, object] = {
        "source": source,
        "synthetic_warning": (
            "SYNTHETIC FIXTURE — metrics describe the code path, not silver. Use --csv for any claim."
            if source == "synthetic" else None),
        "cross_module": (
            "gold series supplied; comparison available" if gold_bars else
            "no gold series; module reports UNAVAILABLE and silver runs alone"),
        "bars": len(bars), "decisions": len(decisions),
        "decision_counts": {
            "entries": sum(1 for d in decisions if d.action != "NO_TRADE"),
            "conflicts": sum(1 for d in decisions if d.conflicts),
            "gold_available": sum(1 for d in decisions if d.gold_available),
        },
        "engine_states": engine_states,
        "no_trade_reason_counts": dict(sorted(no_trade_counts.items(), key=lambda kv: -kv[1])[:10]),
        "metrics": summarize(trades, p.account_equity_usd),
        "note": "Win rate is an observed frequency on these bars, not a probability of future success.",
    }
    if stages:
        payload["stages"] = run_stages(bars, gold_bars, p)
    return payload


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="ALS-5S silver offline replay (paper only)")
    parser.add_argument("--csv", help="silver M5 OHLCV CSV (time,open,high,low,close[,volume])")
    parser.add_argument("--gold-csv", help="optional XAU/USD M5 CSV for the cross-market module")
    parser.add_argument("--bars", type=int, default=3000, help="synthetic fixture length")
    parser.add_argument("--noise-bps", type=float, default=60.0,
                        help="synthetic per-bar amplitude in basis points (ignored with --csv)")
    parser.add_argument("--json", help="write the full report to this path")
    parser.add_argument("--stages", action="store_true", help="run stages A–E")
    parser.add_argument("--spread", type=float, default=None, help="override spread per ounce")
    parser.add_argument("--exit-model", default="FIXED_RR",
                        choices=["FIXED_RR", "TRAIL_AFTER_1R", "PARTIAL_HALF"],
                        help="exit variant to compare against the fixed-RR baseline")
    args = parser.parse_args(argv)
    bars, source = load_bars(args.csv, args.bars, args.noise_bps)
    gold_bars = load_csv_bars(Path(args.gold_csv)) if args.gold_csv else []
    params = SilverParams()
    if args.spread is not None:
        params = replace(params, spread_per_oz=args.spread)
    params = replace(params, exit_model=args.exit_model)
    payload = report(bars, gold_bars, source, params, args.stages)
    text = json.dumps(payload, indent=2, ensure_ascii=False)
    if args.json:
        Path(args.json).write_text(text + "\n", encoding="utf-8")
    metrics = payload["metrics"]
    print(f"source: {source} · bars: {payload['bars']} · decisions: {payload['decisions']}")
    if payload["synthetic_warning"]:
        print(payload["synthetic_warning"])
    print(f"cross module: {payload['cross_module']}")
    print("trades {trade_count} · win {win_rate_pct}% · expectancy {expectancy_r}R · "
          "net {net_pnl_usd} USD · PF {profit_factor} · maxDD {max_drawdown_pct}%".format(**metrics))
    if metrics["trade_count"]:
        for engine, stats in metrics["by_engine"].items():
            print(f"  {engine}: {stats['count']} trades · {stats['expectancy_r']}R · "
                  f"{stats['net_pnl_usd']} USD")
    else:
        print("  no trade passed the cost gate on this tape; the refusal reasons are in the report")
    print(payload["note"])
    if args.json:
        print(f"report written to {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
