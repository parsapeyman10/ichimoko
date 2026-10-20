#!/usr/bin/env python3
"""BTC-ARES — offline validation for the BTC/USDT model that ships in the Android app.

This is a *research* tool, not a signal generator: it re-implements the rules from
``engine/BtcAresEngine.kt`` bar by bar so the same numbers can be checked without a phone,
Android Studio or Gradle.

What is intentionally faithful to the Kotlin engine:

- confirmed pivots only (``left``/``right`` bars must already be closed) — no repainting;
- the same ATR / ADX / EMA / RSI / MACD / relative-volume definitions;
- the same seven-way regime classifier and four-way volatility state;
- the same five independent price engines (A trend, B sweep, C compression, D range,
  E exhaustion) with their own invalidation levels;
- the same 0–100 score and the same arbitration rule (a close conflict is a *conflict*,
  never an average);
- the same cost model (10 bps commission per side + 1 bp spread + 2 bps slippage);
- the same 0.25 % risk per trade and the same 1 % daily loss cap.

What is intentionally *not* claimed:

- Engine F (derivatives positioning) is reported as ``UNAVAILABLE`` and never scored. The app has
  no funding / open-interest / liquidation feed, so there is nothing honest to replay.
- A synthetic fixture is labelled synthetic in the output. It exercises the code; it is not
  evidence about BTC and must never be quoted as a result.
- Nothing here is a probability of success. ``win_rate`` is an observed frequency on the bars that
  were supplied, nothing more.

Usage::

    python3 tools/btc_ares_backtest.py                       # deterministic synthetic fixture
    python3 tools/btc_ares_backtest.py --csv btc_m5.csv      # real M5 bars
    python3 tools/btc_ares_backtest.py --csv btc_m5.csv --json out.json
    python3 tools/btc_ares_backtest.py --csv btc_m5.csv --stages

CSV needs a header and the columns ``time,open,high,low,close[,volume]`` where ``time`` is either
milliseconds since epoch or an ISO-8601 timestamp.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

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
class BtcParams:
    """Mirror of ``BtcAresEngine.Config``; every number here appears in the Kotlin file."""

    # Structure
    pivot_left: int = 3
    pivot_right: int = 3
    micro_left: int = 2
    micro_right: int = 1
    # Indicators
    atr_period: int = 14
    adx_period: int = 14
    ema_fast: int = 50
    ema_slow: int = 200
    rsi_period: int = 14
    # Volatility / regime
    vol_median_lookback: int = 60
    atr_percentile_lookback: int = 240
    low_atr_ratio: float = 0.60
    elevated_atr_ratio: float = 1.40
    extreme_atr_ratio: float = 2.20
    trend_adx_min: float = 20.0
    range_adx_max: float = 20.0
    compression_max_range_atr: float = 0.80
    min_range_width_atr: float = 1.5
    max_range_width_atr: float = 6.0
    # Engine A
    min_impulse_atr: float = 1.2
    min_pullback_frac: float = 0.25
    max_pullback_frac: float = 0.90
    pullback_zone_atr: float = 0.60
    max_chase_atr: float = 1.25
    # Engine B
    min_sweep_atr: float = 0.12
    max_sweep_atr: float = 1.30
    sweep_window_bars: int = 4
    # Engine C
    compression_window_bars: int = 24
    min_breakout_atr: float = 0.15
    min_breakout_rel_volume: float = 1.20
    # Engine D
    range_touch_frac: float = 0.22
    # Engine E
    exhaustion_stretch_atr: float = 2.50
    exhaustion_lookback: int = 20
    min_rejection_wick_frac: float = 0.40
    # Risk / execution
    stop_buffer_atr: float = 0.25
    min_stop_atr: float = 0.35
    max_stop_atr: float = 4.00
    min_reward_risk: float = 1.50
    score_threshold: int = 70
    conflict_margin: int = 15
    risk_per_trade_pct: float = 0.25
    max_daily_loss_pct: float = 1.00
    account_equity_usd: float = 10_000.0
    commission_bps_per_side: float = 10.0
    spread_bps: float = 1.0
    slippage_bps: float = 2.0
    min_notional_usd: float = 5.0
    qty_step: float = 0.00001
    max_setup_age_bars: int = 12
    min_bars: int = 280
    # Replay-only behaviour (not part of the app's decision, only of how a trade is simulated)
    max_hold_bars: int = 48
    enable_a: bool = True
    enable_b: bool = True
    enable_c: bool = True
    enable_d: bool = True
    enable_e: bool = True


# --------------------------------------------------------------------------------------------
# Indicators — same recursive forms as ``engine/Indicators.kt``.
# --------------------------------------------------------------------------------------------
def wilder(values: Sequence[float], period: int) -> List[Optional[float]]:
    out: List[Optional[float]] = [None] * len(values)
    if period <= 0 or len(values) < period:
        return out
    seed = sum(values[:period]) / period
    out[period - 1] = seed
    prev = seed
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
    gains: List[float] = []
    for i in range(1, len(bars)):
        change = bars[i].close - bars[i - 1].close
        gains.append(max(0.0, change))
    losses = [
        max(0.0, bars[i - 1].close - bars[i].close) for i in range(1, len(bars))
    ]
    avg_gain = wilder(gains, period)
    avg_loss = wilder(losses, period)
    for i in range(period, len(bars)):
        g = avg_gain[i - 1]
        loss = avg_loss[i - 1]
        if g is None or loss is None:
            continue
        if loss == 0.0:
            out[i] = 100.0 if g > 0.0 else 50.0
        else:
            rs = g / loss
            out[i] = 100.0 - 100.0 / (1.0 + rs)
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
    # ADX is a Wilder average of DX over the first index where DX exists.
    start = next((i for i, v in enumerate(dx) if v is not None), None)
    if start is None or len(dx) - start < period:
        return out
    seed_vals = [v for v in dx[start:start + period] if v is not None]
    if len(seed_vals) < period:
        return out
    prev = sum(seed_vals) / period
    out[start + period - 1] = prev
    for i in range(start + period, len(dx)):
        if dx[i] is None:
            continue
        prev = (prev * (period - 1) + dx[i]) / period
        out[i] = prev
    return out


def compute_macd_histogram(bars: Sequence[Bar]) -> List[Optional[float]]:
    closes = [b.close for b in bars]
    fast = compute_ema(closes, 12)
    slow = compute_ema(closes, 26)
    macd = [
        (None if fast[i] is None or slow[i] is None else fast[i] - slow[i])
        for i in range(len(closes))
    ]
    signal_input = [v if v is not None else 0.0 for v in macd]
    signal = compute_ema(signal_input, 9)
    return [
        (None if macd[i] is None or signal[i] is None else macd[i] - signal[i])
        for i in range(len(closes))
    ]


def relative_volume(bars: Sequence[Bar], lookback: int = 30) -> List[Optional[float]]:
    out: List[Optional[float]] = [None] * len(bars)
    for i in range(len(bars)):
        start = max(0, i - lookback + 1)
        window = [b.volume for b in bars[start:i + 1]]
        avg = sum(window) / len(window) if window else 0.0
        out[i] = (bars[i].volume / avg) if avg > 0.0 else 1.0
    return out


def confirmed_swings(
    bars: Sequence[Bar], left: int, right: int, up_to_index: Optional[int] = None
) -> Tuple[List[Tuple[int, float]], List[Tuple[int, float]]]:
    """Pivots that are fully confirmed by ``right`` closed bars. Never peeks forward."""
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
    below = sum(1 for v in window if v < last)
    equal = sum(1 for v in window if v == last)
    return (below + 0.5 * equal) / len(window)


# --------------------------------------------------------------------------------------------
# Regime / volatility
# --------------------------------------------------------------------------------------------
REGIMES = ("TREND_UP", "TREND_DOWN", "RANGE", "COMPRESSION", "TRANSITION", "EXTREME_VOL", "UNCERTAIN")
VOL_STATES = ("LOW", "NORMAL", "ELEVATED", "EXTREME")
ENGINE_IDS = ("A_TREND", "B_SWEEP", "C_COMPRESSION", "D_RANGE", "E_EXHAUSTION", "F_DERIVATIVES")
STATES = ("IDLE", "WATCH", "FORMING", "CONFIRMATION", "READY", "ACTIVE", "INVALIDATED", "EXPIRED",
          "UNAVAILABLE")


@dataclass(frozen=True)
class Features:
    atr: float
    atr_ratio: float
    atr_percentile: float
    adx: float
    adx_m15: Optional[float]
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
    htf_available: bool


def volatility_of(f: Features, p: BtcParams) -> str:
    if f.atr_ratio >= p.extreme_atr_ratio or f.atr_percentile >= 0.97:
        return "EXTREME"
    if f.atr_ratio >= p.elevated_atr_ratio or f.atr_percentile >= 0.85:
        return "ELEVATED"
    if f.atr_ratio <= p.low_atr_ratio and f.atr_percentile <= 0.25:
        return "LOW"
    return "NORMAL"


def classify_regime(f: Features, p: BtcParams) -> str:
    vol = volatility_of(f, p)
    bullish = (f.hh and f.hl) or f.bos_up
    bearish = (f.lh and f.ll) or f.bos_down
    if vol == "EXTREME":
        return "EXTREME_VOL"
    if bullish and bearish:
        return "UNCERTAIN"
    if (
        f.compression_ratio <= p.compression_max_range_atr
        and f.adx < p.trend_adx_min
        and vol != "ELEVATED"
    ):
        return "COMPRESSION"
    if (f.bos_up or f.bos_down) and vol == "ELEVATED":
        return "TRANSITION"
    width_atr = (f.range_high - f.range_low) / f.atr
    if bullish and f.adx >= p.trend_adx_min and f.htf_bias != "SELL":
        return "TREND_UP"
    if bearish and f.adx >= p.trend_adx_min and f.htf_bias != "BUY":
        return "TREND_DOWN"
    if p.min_range_width_atr <= width_atr <= p.max_range_width_atr and f.adx <= p.range_adx_max:
        return "RANGE"
    return "UNCERTAIN"


# --------------------------------------------------------------------------------------------
# The five price engines
# --------------------------------------------------------------------------------------------
@dataclass
class Candidate:
    engine: str
    state: str
    direction: str  # BUY / SELL / NO_TRADE
    score: int
    structural_level: Optional[float]
    invalidation: Optional[float]
    entry: Optional[float]
    stop: Optional[float]
    target: Optional[float]
    rr: Optional[float]
    satisfied: List[str]
    missing: List[str]
    detail: str
    data_available: bool = True


def _no_trade(engine: str, state: str, detail: str, data_available: bool = True) -> Candidate:
    return Candidate(engine, state, "NO_TRADE", 0, None, None, None, None, None, None, [], [],
                     detail, data_available)


def stop_for(direction: str, raw: float, price: float, atr: float, p: BtcParams) -> float:
    distance = min(
        max(abs(price - raw) + p.stop_buffer_atr * atr, p.min_stop_atr * atr), p.max_stop_atr * atr
    )
    return price - distance if direction == "BUY" else price + distance


def target_at(direction: str, entry: float, stop: float, rr: float) -> float:
    risk = abs(entry - stop)
    return entry + rr * risk if direction == "BUY" else entry - rr * risk


def score_of(
    regime_fit: int,
    structure: int,
    location: int,
    confirmation: int,
    vol: str,
    rel_volume_value: float,
    rr: float,
    p: BtcParams,
) -> int:
    vol_points = {"NORMAL": 10, "LOW": 8, "ELEVATED": 5, "EXTREME": 0}[vol]
    if rel_volume_value >= 1.3:
        volume_points = 10
    elif rel_volume_value >= 1.0:
        volume_points = 7
    elif rel_volume_value > 0.0:
        volume_points = 3
    else:
        volume_points = 0
    total_cost_fraction = (
        2 * p.commission_bps_per_side + p.spread_bps + p.slippage_bps
    ) / 10_000.0
    if rr < p.min_reward_risk:
        execution_points = 0
    elif rr >= 2.0 + total_cost_fraction * 100:
        execution_points = 10
    elif rr >= p.min_reward_risk + 0.5:
        execution_points = 7
    else:
        execution_points = 5
    total = (
        min(max(regime_fit, 0), 20)
        + min(max(structure, 0), 20)
        + min(max(location, 0), 15)
        + min(max(confirmation, 0), 15)
        + vol_points
        + volume_points
        + execution_points
    )
    return min(max(total, 0), 100)


def engine_a(bars: Sequence[Bar], f: Features, regime: str, p: BtcParams) -> Candidate:
    if not p.enable_a:
        return _no_trade("A_TREND", "IDLE", "engine A disabled")
    if regime not in ("TREND_UP", "TREND_DOWN"):
        return _no_trade("A_TREND", "IDLE", f"regime {regime} does not allow trend continuation")
    if not f.htf_available:
        return _no_trade("A_TREND", "IDLE", "M15/H1 unavailable for trend confirmation")
    up = regime == "TREND_UP"
    direction = "BUY" if up else "SELL"
    price = bars[-1].close
    atr = f.atr
    impulse_level = f.swing_low if up else f.swing_high
    if impulse_level is None:
        return _no_trade("A_TREND", "IDLE", "no confirmed swing to measure the impulse")
    impulse = abs(price - impulse_level)
    satisfied: List[str] = []
    missing: List[str] = []
    (satisfied if impulse >= p.min_impulse_atr * atr else missing).append(
        f"impulse {impulse / atr:.2f} ATR"
    )
    zone = max(f.swing_low if f.swing_low is not None else price, f.ema_fast) if up else min(
        f.swing_high if f.swing_high is not None else price, f.ema_fast
    )
    distance = abs(price - zone)
    in_zone = distance <= p.pullback_zone_atr * atr
    (satisfied if in_zone else missing).append(f"pullback zone {distance / atr:.2f} ATR")
    micro_highs, _ = confirmed_swings(bars, p.micro_left, p.micro_right)
    _, micro_lows = confirmed_swings(bars, p.micro_left, p.micro_right)
    micro_highs = micro_highs[-2:]
    micro_lows = micro_lows[-2:]
    micro_break = (
        bool(micro_highs) and price > micro_highs[-1][1]
        if up
        else bool(micro_lows) and price < micro_lows[-1][1]
    )
    micro_level = micro_highs[-1][1] if up and micro_highs else (micro_lows[-1][1] if micro_lows else None)
    (satisfied if micro_break else missing).append("micro structure break")
    dist_ema = abs(price - f.ema_fast) / atr
    not_chasing = dist_ema <= p.max_chase_atr * 2.0
    (satisfied if not_chasing else missing).append(f"distance from EMA{ p.ema_fast } {dist_ema:.2f} ATR")
    momentum_ok = (f.macd_hist > 0.0 and f.rsi >= 50.0) if up else (f.macd_hist < 0.0 and f.rsi <= 50.0)
    (satisfied if momentum_ok else missing).append("momentum")
    ready = len(satisfied) >= 5 and micro_break and in_zone and not_chasing
    if ready:
        state = "READY"
    elif len(satisfied) >= 3:
        state = "CONFIRMATION"
    elif in_zone or micro_break:
        state = "FORMING"
    else:
        state = "WATCH"
    level = micro_level if micro_level is not None else zone
    raw_stop = (
        min(impulse_level, level) - p.stop_buffer_atr * atr * 0.5
        if up
        else max(impulse_level, level) + p.stop_buffer_atr * atr * 0.5
    )
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    target = target_at(direction, price, stop, max(p.min_reward_risk, 2.0))
    rr = abs(target - price) / risk if risk > 0.0 else 0.0
    score = score_of(
        20,
        20 if len(satisfied) >= 5 else len(satisfied) * 4,
        15 if in_zone else 5,
        15 if micro_break else 5,
        volatility_of(f, p),
        f.rel_volume,
        rr,
        p,
    )
    return Candidate(
        "A_TREND",
        state,
        direction if ready else "NO_TRADE",
        score,
        level,
        stop,
        price,
        stop,
        target,
        rr,
        satisfied,
        missing,
        f"{regime} · pullback {distance / atr:.2f} ATR · micro break {micro_break}",
    )


def engine_b(bars: Sequence[Bar], f: Features, regime: str, p: BtcParams) -> Candidate:
    if not p.enable_b:
        return _no_trade("B_SWEEP", "IDLE", "engine B disabled")
    if regime == "EXTREME_VOL":
        return _no_trade("B_SWEEP", "IDLE", "sweep entries are not allowed in extreme volatility")
    atr = f.atr
    price = bars[-1].close
    window = bars[-p.sweep_window_bars:]
    highs, lows = confirmed_swings(bars, p.pivot_left, p.pivot_right)
    prior_low = lows[-1][1] if lows else None
    prior_high = highs[-1][1] if highs else None
    swept_low = prior_low is not None and any(
        b.low < prior_low and b.low >= prior_low - p.max_sweep_atr * atr for b in window
    )
    swept_high = prior_high is not None and any(
        b.high > prior_high and b.high <= prior_high + p.max_sweep_atr * atr for b in window
    )
    satisfied: List[str] = []
    missing: List[str] = []
    if swept_low and prior_low is not None and price > prior_low:
        direction, level = "BUY", prior_low
        satisfied.append("swept a confirmed low and closed back above it")
    elif swept_high and prior_high is not None and price < prior_high:
        direction, level = "SELL", prior_high
        satisfied.append("swept a confirmed high and closed back below it")
    else:
        missing.append("no confirmed liquidity sweep")
        return _no_trade("B_SWEEP", "WATCH", "no confirmed sweep")
    sweep_bar = next(
        (
            b
            for b in reversed(window)
            if (b.low < level if direction == "BUY" else b.high > level)
        ),
        None,
    )
    penetration = 0.0
    if sweep_bar is not None:
        penetration = (
            (level - sweep_bar.low) / atr if direction == "BUY" else (sweep_bar.high - level) / atr
        )
    (satisfied if penetration >= p.min_sweep_atr else missing).append(f"penetration {penetration:.2f} ATR")
    micro_highs, micro_lows = confirmed_swings(bars, p.micro_left, p.micro_right, len(bars) - 2)
    if direction == "BUY":
        shift = bool(micro_highs) and price > micro_highs[-1][1]
    else:
        shift = bool(micro_lows) and price < micro_lows[-1][1]
    (satisfied if shift else missing).append("micro shift")
    last = bars[-1]
    span = last.high - last.low
    close_location = (last.close - last.low) / span if span > 0.0 else 0.5
    close_ok = close_location >= 0.5 if direction == "BUY" else close_location <= 0.5
    (satisfied if close_ok else missing).append("close in the right half of the bar")
    ready = len(satisfied) >= 3 and shift
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 2 else "FORMING")
    if direction == "BUY":
        raw_stop = (sweep_bar.low - p.stop_buffer_atr * atr) if sweep_bar else (level - atr * 0.5)
    else:
        raw_stop = (sweep_bar.high + p.stop_buffer_atr * atr) if sweep_bar else (level + atr * 0.5)
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    if direction == "BUY":
        opposite = f.range_high if f.range_high > price else price + 2.0 * risk
        target = max(opposite, price + p.min_reward_risk * risk)
    else:
        opposite = f.range_low if f.range_low < price else price - 2.0 * risk
        target = min(opposite, price - p.min_reward_risk * risk)
    rr = abs(target - price) / risk if risk > 0.0 else 0.0
    score = score_of(
        18 if regime == "TRANSITION" else 14,
        18,
        15 if penetration >= p.min_sweep_atr else 8,
        15 if shift else 5,
        volatility_of(f, p),
        f.rel_volume,
        rr,
        p,
    )
    return Candidate(
        "B_SWEEP", state, direction if ready else "NO_TRADE", score, level, stop, price, stop,
        target, rr, satisfied, missing,
        f"sweep {penetration:.2f} ATR · micro shift {shift}",
    )


def engine_c(bars: Sequence[Bar], f: Features, regime: str, p: BtcParams) -> Candidate:
    if not p.enable_c:
        return _no_trade("C_COMPRESSION", "IDLE", "engine C disabled")
    if regime == "EXTREME_VOL":
        return _no_trade("C_COMPRESSION", "IDLE", "compression breakouts make no sense in extreme vol")
    atr = f.atr
    price = bars[-1].close
    compressed = f.compression_ratio <= p.compression_max_range_atr or regime in ("COMPRESSION", "TRANSITION")
    satisfied: List[str] = []
    missing: List[str] = []
    (satisfied if f.compression_ratio <= p.compression_max_range_atr else missing).append(
        f"compression ratio {f.compression_ratio:.2f}"
    )
    up_break = price > f.range_high + p.min_breakout_atr * atr
    down_break = price < f.range_low - p.min_breakout_atr * atr
    direction = "BUY" if up_break else ("SELL" if down_break else "NO_TRADE")
    (satisfied if direction != "NO_TRADE" else missing).append("close outside the range boundary")
    volume_ok = f.rel_volume >= p.min_breakout_rel_volume
    (satisfied if volume_ok else missing).append(f"relative volume {f.rel_volume:.2f}x")
    level = f.range_high if direction == "BUY" else f.range_low
    late_distance = 0.0
    if direction == "BUY":
        late_distance = (price - f.range_high) / atr
    elif direction == "SELL":
        late_distance = (f.range_low - price) / atr
    not_late = late_distance <= p.min_breakout_atr + p.max_chase_atr
    if direction != "NO_TRADE":
        (satisfied if not_late else missing).append(f"late entry {late_distance:.2f} ATR")
    ready = (
        direction != "NO_TRADE"
        and volume_ok
        and not_late
        and f.compression_ratio <= p.compression_max_range_atr
    )
    if ready:
        state = "READY"
    elif direction != "NO_TRADE":
        state = "CONFIRMATION"
    elif compressed:
        state = "FORMING"
    else:
        state = "WATCH"
    if direction == "NO_TRADE":
        stop = None
        target = None
        rr = 0.0
        level_out = None
    else:
        raw_stop = level - p.stop_buffer_atr * atr if direction == "BUY" else level + p.stop_buffer_atr * atr
        stop = stop_for(direction, raw_stop, price, atr, p)
        target = target_at(direction, price, stop, max(p.min_reward_risk, 2.0))
        risk = abs(price - stop)
        rr = abs(target - price) / risk if risk > 0.0 else 0.0
        level_out = level
    score = score_of(
        18 if compressed else 8,
        16 if compressed else 6,
        15 if direction != "NO_TRADE" else 4,
        15 if volume_ok else 4,
        volatility_of(f, p),
        f.rel_volume,
        rr,
        p,
    )
    return Candidate(
        "C_COMPRESSION", state, direction if ready else "NO_TRADE", score, level_out, stop,
        price if direction != "NO_TRADE" else None, stop, target, rr, satisfied, missing,
        f"compression {f.compression_ratio:.2f} · break {direction}",
    )


def engine_d(bars: Sequence[Bar], f: Features, regime: str, p: BtcParams) -> Candidate:
    if not p.enable_d:
        return _no_trade("D_RANGE", "IDLE", "engine D disabled")
    if regime != "RANGE":
        return _no_trade("D_RANGE", "IDLE", f"regime {regime} does not allow mean reversion")
    atr = f.atr
    price = bars[-1].close
    width = f.range_high - f.range_low
    if width <= 0.0:
        return _no_trade("D_RANGE", "WATCH", "invalid range width")
    position = (price - f.range_low) / width
    near_low = position <= p.range_touch_frac
    near_high = position >= 1.0 - p.range_touch_frac
    direction = "BUY" if near_low else ("SELL" if near_high else "NO_TRADE")
    satisfying: List[str] = []
    missing: List[str] = []
    if direction == "NO_TRADE":
        return _no_trade("D_RANGE", "WATCH", f"middle of the range ({position * 100:.0f}%)")
    satisfying.append(f"price at {position * 100:.0f}% of the range")
    last = bars[-1]
    span = last.high - last.low
    if direction == "BUY":
        rejection = span > 0.0 and (min(last.open, last.close) - last.low) / span >= p.min_rejection_wick_frac
    else:
        rejection = span > 0.0 and (last.high - max(last.open, last.close)) / span >= p.min_rejection_wick_frac
    (satisfying if rejection else missing).append("rejection wick")
    rsi_ok = f.rsi <= 40.0 if direction == "BUY" else f.rsi >= 60.0
    (satisfying if rsi_ok else missing).append(f"RSI {f.rsi:.0f}")
    adx_ok = f.adx <= p.range_adx_max + 2.0
    (satisfying if adx_ok else missing).append(f"ADX {f.adx:.1f}")
    ready = len(satisfying) >= 4
    state = "READY" if ready else ("CONFIRMATION" if len(satisfying) >= 2 else "FORMING")
    level = f.range_low if direction == "BUY" else f.range_high
    raw_stop = level - p.stop_buffer_atr * atr if direction == "BUY" else level + p.stop_buffer_atr * atr
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    mid = (f.range_high + f.range_low) / 2.0
    target = (
        max(mid, price + p.min_reward_risk * risk)
        if direction == "BUY"
        else min(mid, price - p.min_reward_risk * risk)
    )
    rr = abs(target - price) / risk if risk > 0.0 else 0.0
    score = score_of(
        18,
        16,
        15 if direction != "NO_TRADE" else 8,
        15 if rejection else 5,
        volatility_of(f, p),
        f.rel_volume,
        rr,
        p,
    )
    return Candidate(
        "D_RANGE", state, direction if ready else "NO_TRADE", score, level, stop, price, stop,
        target, rr, satisfying, missing,
        f"position in range {position * 100:.0f}% · width {width / atr:.2f} ATR",
    )


def engine_e(bars: Sequence[Bar], f: Features, regime: str, p: BtcParams) -> Candidate:
    if not p.enable_e:
        return _no_trade("E_EXHAUSTION", "IDLE", "engine E disabled")
    if regime == "EXTREME_VOL":
        return _no_trade("E_EXHAUSTION", "IDLE", "exhaustion reversals are unreliable in extreme vol")
    atr = f.atr
    price = bars[-1].close
    stretch = (price - f.ema_fast) / atr
    satisfied: List[str] = []
    missing: List[str] = []
    (satisfied if abs(stretch) >= p.exhaustion_stretch_atr else missing).append(f"stretch {abs(stretch):.2f} ATR")
    look = bars[-(p.exhaustion_lookback + 1):-1]
    prev_high = max((b.high for b in look), default=price)
    prev_low = min((b.low for b in look), default=price)
    rsi_series = [v for v in compute_rsi(bars, p.rsi_period) if v is not None]
    rsi_prev = rsi_series[-2] if len(rsi_series) >= 2 else f.rsi
    bearish_divergence = price >= prev_high and f.rsi < rsi_prev
    bullish_divergence = price <= prev_low and f.rsi > rsi_prev
    if stretch >= p.exhaustion_stretch_atr and bearish_divergence:
        direction = "SELL"
    elif stretch <= -p.exhaustion_stretch_atr and bullish_divergence:
        direction = "BUY"
    else:
        direction = "NO_TRADE"
    if direction == "NO_TRADE":
        missing.append("no RSI divergence aligned with the stretch")
        return _no_trade(
            "E_EXHAUSTION", "FORMING" if abs(stretch) >= p.exhaustion_stretch_atr else "WATCH",
            "no confirmed divergence",
        )
    satisfied.append(f"{'bullish' if direction == 'BUY' else 'bearish'} RSI divergence")
    last = bars[-1]
    span = last.high - last.low
    if direction == "BUY":
        wick = (min(last.open, last.close) - last.low) / span if span > 0.0 else 0.0
    else:
        wick = (last.high - max(last.open, last.close)) / span if span > 0.0 else 0.0
    (satisfied if wick >= p.min_rejection_wick_frac else missing).append(f"rejection wick {wick * 100:.0f}%")
    micro_highs, micro_lows = confirmed_swings(bars, p.micro_left, p.micro_right, len(bars) - 2)
    shift = (
        bool(micro_highs) and price > micro_highs[-1][1]
        if direction == "BUY"
        else bool(micro_lows) and price < micro_lows[-1][1]
    )
    (satisfied if shift else missing).append("micro shift")
    ready = wick >= p.min_rejection_wick_frac and shift
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 3 else "FORMING")
    level = prev_low if direction == "BUY" else prev_high
    raw_stop = level - p.stop_buffer_atr * atr if direction == "BUY" else level + p.stop_buffer_atr * atr
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    target = (
        max(f.ema_fast, price + p.min_reward_risk * risk)
        if direction == "BUY"
        else min(f.ema_fast, price - p.min_reward_risk * risk)
    )
    rr = abs(target - price) / risk if risk > 0.0 else 0.0
    score = score_of(
        14, 16, 12, 15 if wick >= p.min_rejection_wick_frac else 5, volatility_of(f, p),
        f.rel_volume, rr, p
    )
    return Candidate(
        "E_EXHAUSTION", state, direction if ready else "NO_TRADE", score, level, stop, price, stop,
        target, rr, satisfied, missing, f"stretch {stretch:.2f} ATR · wick {wick * 100:.0f}%",
    )


def engine_f() -> Candidate:
    """Engine F has no data in this app. It is reported, never scored, never invented."""
    return _no_trade(
        "F_DERIVATIVES",
        "UNAVAILABLE",
        "no funding / open-interest / liquidation feed in this app; no synthetic value is produced",
        data_available=False,
    )


# --------------------------------------------------------------------------------------------
# Features + one full decision
# --------------------------------------------------------------------------------------------
def build_features(
    bars: Sequence[Bar], m15: Sequence[Bar], h1: Sequence[Bar], p: BtcParams
) -> Features:
    closes = [b.close for b in bars]
    atr_series = compute_atr(bars, p.atr_period)
    atr = next((v for v in reversed(atr_series) if v is not None and v > 0.0), 1.0)
    recent = sorted(
        v for v in atr_series[-p.vol_median_lookback:] if v is not None and v > 0.0 and math.isfinite(v)
    )
    median = recent[len(recent) // 2] if recent else atr
    adx_series = compute_adx(bars, p.adx_period)
    adx = next((v for v in reversed(adx_series) if v is not None), 0.0)
    adx_m15: Optional[float] = None
    if len(m15) > p.adx_period * 2:
        m15_series = compute_adx(m15, p.adx_period)
        adx_m15 = next((v for v in reversed(m15_series) if v is not None), None)
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
    compression_ratio = (
        width / (atr * math.sqrt(p.compression_window_bars)) if atr > 0.0 else 1.0
    )

    htf_available = len(m15) >= 240 and len(h1) >= 200
    htf_bias: Optional[str] = None
    if htf_available:
        m15_close = m15[-1].close
        m15_ema = next((v for v in reversed(compute_ema([b.close for b in m15], p.ema_fast)) if v is not None), m15_close)
        h1_close = h1[-1].close
        h1_ema = next((v for v in reversed(compute_ema([b.close for b in h1], p.ema_fast)) if v is not None), h1_close)
        if m15_close > m15_ema and h1_close > h1_ema:
            htf_bias = "BUY"
        elif m15_close < m15_ema and h1_close < h1_ema:
            htf_bias = "SELL"
        else:
            htf_bias = "NO_TRADE"

    return Features(
        atr=atr,
        atr_ratio=atr / median if median > 0.0 else 1.0,
        atr_percentile=percentile_rank(atr_series, p.atr_percentile_lookback),
        adx=adx,
        adx_m15=adx_m15,
        ema_fast=ema_fast,
        ema_slow=ema_slow,
        rsi=rsi,
        macd_hist=macd,
        rel_volume=rel,
        swing_high=last_highs[-1] if last_highs else None,
        swing_low=last_lows[-1] if last_lows else None,
        hh=hh,
        hl=hl,
        lh=lh,
        ll=ll,
        bos_up=bos_up,
        bos_down=bos_down,
        range_high=range_high,
        range_low=range_low,
        compression_ratio=compression_ratio,
        htf_bias=htf_bias,
        htf_available=htf_available,
    )


@dataclass
class Decision:
    bar_time: int
    regime: str
    volatility: str
    engines: List[Candidate]
    action: str
    chosen_engine: Optional[str]
    score: int
    entry: Optional[float]
    stop: Optional[float]
    target: Optional[float]
    rr: Optional[float]
    qty: float
    risk_usd: float
    net_rr: Optional[float]
    conflicts: List[str]
    no_trade_reasons: List[str]
    setup_key: str


def position_size(entry: float, stop: float, p: BtcParams) -> Tuple[float, float]:
    if not all(math.isfinite(v) for v in (entry, stop)) or entry <= 0.0 or stop <= 0.0 or entry == stop:
        return 0.0, 0.0
    cost_per_unit = entry * (
        2 * p.commission_bps_per_side + p.spread_bps + p.slippage_bps
    ) / 10_000.0
    risk_per_unit = abs(entry - stop) + cost_per_unit
    if risk_per_unit <= 0.0:
        return 0.0, 0.0
    risk_usd = p.account_equity_usd * p.risk_per_trade_pct / 100.0
    # Round *down* to the exchange step so the risk cap is never exceeded by rounding.
    qty = math.floor((risk_usd / risk_per_unit) / p.qty_step) * p.qty_step
    qty = round(qty, 8)
    # An account too small to clear the exchange minimum is refused, never sized *up* to it:
    # sizing up would silently raise the risk above the 0.25 % cap that the record advertises.
    if qty <= 0.0 or qty * entry < p.min_notional_usd:
        return 0.0, 0.0
    return qty, qty * risk_per_unit


def net_reward_risk(entry: float, stop: float, target: float, p: BtcParams) -> float:
    """Reward:risk *after* the round-trip cost, which is what a trade actually earns.

    A gross 2R setup whose stop is a few basis points wide is a losing trade once 10 bps per side
    plus spread and slippage are paid. Reporting only the gross ratio is how a backtest flatters
    itself; the app therefore gates on this number too.
    """
    if not all(math.isfinite(v) for v in (entry, stop, target)):
        return 0.0
    cost_per_unit = entry * (
        2 * p.commission_bps_per_side + p.spread_bps + p.slippage_bps
    ) / 10_000.0
    reward = abs(target - entry) - cost_per_unit
    risk = abs(entry - stop) + cost_per_unit
    if risk <= 0.0:
        return 0.0
    return reward / risk


def decide(
    bars: Sequence[Bar],
    m15: Sequence[Bar],
    h1: Sequence[Bar],
    p: BtcParams,
    daily_realized_loss_pct: float = 0.0,
) -> Decision:
    f = build_features(bars, m15, h1, p)
    vol = volatility_of(f, p)
    regime = classify_regime(f, p)
    candidates = [
        engine_a(bars, f, regime, p),
        engine_b(bars, f, regime, p),
        engine_c(bars, f, regime, p),
        engine_d(bars, f, regime, p),
        engine_e(bars, f, regime, p),
        engine_f(),
    ]
    ready = sorted(
        (c for c in candidates if c.state == "READY" and c.direction != "NO_TRADE"),
        key=lambda c: c.score,
        reverse=True,
    )
    conflicts: List[str] = []
    if len(ready) >= 2:
        top = ready[0]
        opposite = next((c for c in ready if c.direction != top.direction), None)
        if opposite is not None and abs(top.score - opposite.score) < p.conflict_margin:
            conflicts.append(
                f"conflict {top.engine} vs {opposite.engine} "
                f"(score gap {abs(top.score - opposite.score)} < {p.conflict_margin})"
            )
    no_trade_reasons: List[str] = []
    best = None if conflicts or not ready else ready[0]
    if best is None and not conflicts:
        no_trade_reasons.append(
            "no engine reached READY; "
            + ", ".join(f"{c.engine}={c.state}" for c in candidates if c.state not in ("UNAVAILABLE", "IDLE"))
        )
    if best is not None and best.score < p.score_threshold:
        no_trade_reasons.append(f"score {best.score} < {p.score_threshold}")
    if best is not None and (best.rr or 0.0) < p.min_reward_risk:
        no_trade_reasons.append(f"R:R {(best.rr or 0.0):.2f} < {p.min_reward_risk:.2f}")
    if daily_realized_loss_pct >= p.max_daily_loss_pct:
        no_trade_reasons.append("daily loss cap reached")
    if vol == "EXTREME" and regime == "EXTREME_VOL":
        no_trade_reasons.append("extreme volatility: no new entries")
    actionable = best if not no_trade_reasons else None
    qty, risk_usd = (
        position_size(actionable.entry, actionable.stop, p)
        if actionable and actionable.entry is not None and actionable.stop is not None
        else (0.0, 0.0)
    )
    if actionable is not None and qty <= 0.0:
        no_trade_reasons.append("size below the minimum notional")
    net_rr: Optional[float] = None
    if actionable is not None and actionable.entry is not None and actionable.stop is not None \
            and actionable.target is not None:
        net_rr = net_reward_risk(actionable.entry, actionable.stop, actionable.target, p)
        if net_rr < p.min_reward_risk:
            no_trade_reasons.append(
                f"net R:R {net_rr:.2f} after costs < {p.min_reward_risk:.2f} "
                f"(gross {actionable.rr or 0.0:.2f}); the stop is too tight for 10 bps per side"
            )
    action = actionable.direction if actionable and qty > 0.0 and not no_trade_reasons else "NO_TRADE"
    setup_key = ""
    if action != "NO_TRADE" and actionable is not None:
        setup_key = "|".join(
            [
                "BTC-ARES",
                "BTC/USDT",
                actionable.engine,
                action,
                f"{actionable.structural_level or 0.0:.2f}",
                str(bars[-1].time_ms),
            ]
        )
    return Decision(
        bar_time=bars[-1].time_ms,
        regime=regime,
        volatility=vol,
        engines=candidates,
        action=action,
        chosen_engine=actionable.engine if action != "NO_TRADE" else None,
        score=actionable.score if action != "NO_TRADE" else (best.score if best else 0),
        entry=actionable.entry if action != "NO_TRADE" else None,
        stop=actionable.stop if action != "NO_TRADE" else None,
        target=actionable.target if action != "NO_TRADE" else None,
        rr=actionable.rr if action != "NO_TRADE" else None,
        qty=qty if action != "NO_TRADE" else 0.0,
        risk_usd=risk_usd if action != "NO_TRADE" else 0.0,
        net_rr=net_rr,
        conflicts=conflicts,
        no_trade_reasons=no_trade_reasons,
        setup_key=setup_key,
    )


# --------------------------------------------------------------------------------------------
# Replay
# --------------------------------------------------------------------------------------------
@dataclass
class Trade:
    engine: str
    side: str
    regime: str
    entry_time: int
    entry: float
    stop: float
    target: float
    exit_time: int
    exit_price: float
    qty: float
    gross_pnl_usd: float
    cost_usd: float
    net_pnl_usd: float
    r_multiple: float
    outcome: str
    bars_held: int


def replay(
    bars: Sequence[Bar],
    m15: Sequence[Bar],
    h1: Sequence[Bar],
    p: BtcParams,
    warmup: int = 280,
    max_hold_bars: Optional[int] = None,
    daily_loss_cap: float = 1.00,
) -> Tuple[List[Trade], List[Decision]]:
    """Walk forward: decide on the close of bar i, fill at the open of bar i+1.

    The fill is one bar after the decision on purpose — anything faster would use information
    the phone does not have. If both stop and target are touched inside one bar, the stop wins,
    which is the pessimistic (and therefore honest) assumption.
    """
    hold = p.max_hold_bars if max_hold_bars is None else max_hold_bars
    trades: List[Trade] = []
    decisions: List[Decision] = []
    equity = p.account_equity_usd
    day_start_equity = equity
    current_day: Optional[int] = None
    busy_until = -1
    for i in range(warmup, len(bars) - 1):
        bar = bars[i]
        day = bar.time_ms // 86_400_000
        if day != current_day:
            current_day = day
            day_start_equity = equity
        realized_loss_pct = 0.0
        if day_start_equity > 0.0:
            realized_loss_pct = max(0.0, (day_start_equity - equity) / day_start_equity * 100.0)
        m15_closed = [b for b in m15 if b.time_ms + M15_MS <= bar.time_ms + M5_MS]
        h1_closed = [b for b in h1 if b.time_ms + H1_MS <= bar.time_ms + M5_MS]
        decision = decide(bars[: i + 1], m15_closed, h1_closed, p, realized_loss_pct)
        decisions.append(decision)
        if i <= busy_until or decision.action == "NO_TRADE":
            continue
        assert decision.entry is not None and decision.stop is not None and decision.target is not None
        fill_bar = bars[i + 1]
        slip = fill_bar.open * p.slippage_bps / 10_000.0
        entry = fill_bar.open + slip if decision.action == "BUY" else fill_bar.open - slip
        qty = decision.qty
        cost = (
            entry * qty * (p.commission_bps_per_side + p.spread_bps / 2.0) / 10_000.0
        )
        exit_price: Optional[float] = None
        exit_time = 0
        outcome = "EXPIRED"
        bars_held = 0
        for j in range(i + 1, min(i + 1 + hold, len(bars))):
            b = bars[j]
            bars_held = j - i
            if decision.action == "BUY":
                stop_hit = b.low <= decision.stop
                target_hit = b.high >= decision.target
            else:
                stop_hit = b.high >= decision.stop
                target_hit = b.low <= decision.target
            if stop_hit:
                exit_price, exit_time, outcome = decision.stop, b.time_ms, "STOP"
                break
            if target_hit:
                exit_price, exit_time, outcome = decision.target, b.time_ms, "TARGET"
                break
        if exit_price is None:
            last = bars[min(i + hold, len(bars) - 1)]
            exit_price, exit_time = last.close, last.time_ms
        exit_slip = exit_price * p.slippage_bps / 10_000.0
        exit_fill = exit_price - exit_slip if decision.action == "BUY" else exit_price + exit_slip
        cost += exit_fill * qty * (p.commission_bps_per_side + p.spread_bps / 2.0) / 10_000.0
        gross = (
            (exit_fill - entry) * qty if decision.action == "BUY" else (entry - exit_fill) * qty
        )
        net = gross - cost
        risk_per_unit = abs(entry - decision.stop) + entry * (
            2 * p.commission_bps_per_side + p.spread_bps + p.slippage_bps
        ) / 10_000.0
        r_multiple = net / (risk_per_unit * qty) if risk_per_unit * qty > 0.0 else 0.0
        trades.append(
            Trade(
                engine=decision.chosen_engine or "",
                side=decision.action,
                regime=decision.regime,
                entry_time=fill_bar.time_ms,
                entry=entry,
                stop=decision.stop,
                target=decision.target,
                exit_time=exit_time,
                exit_price=exit_fill,
                qty=qty,
                gross_pnl_usd=gross,
                cost_usd=cost,
                net_pnl_usd=net,
                r_multiple=r_multiple,
                outcome=outcome,
                bars_held=bars_held,
            )
        )
        equity += net
        busy_until = i + max(1, bars_held)
    return trades, decisions


def summarize(trades: Sequence[Trade], initial_equity: float = 10_000.0) -> Dict[str, object]:
    total = len(trades)
    summary: Dict[str, object] = {
        "trade_count": total,
        "win_rate_pct": 0.0,
        "net_pnl_usd": 0.0,
        "return_pct": 0.0,
        "profit_factor": 0.0,
        "expectancy_r": 0.0,
        "max_drawdown_pct": 0.0,
        "costs_usd": 0.0,
        "by_engine": {},
        "by_regime": {},
        "by_side": {},
    }
    if total == 0:
        return summary
    wins = [t for t in trades if t.net_pnl_usd > 0.0]
    losses = [t for t in trades if t.net_pnl_usd <= 0.0]
    gross_win = sum(t.net_pnl_usd for t in wins)
    gross_loss = abs(sum(t.net_pnl_usd for t in losses))
    net = sum(t.net_pnl_usd for t in trades)
    equity = initial_equity
    peak = initial_equity
    max_dd = 0.0
    for t in trades:
        equity += t.net_pnl_usd
        peak = max(peak, equity)
        if peak > 0.0:
            max_dd = max(max_dd, (peak - equity) / peak * 100.0)

    def group(key) -> Dict[str, Dict[str, float]]:
        buckets: Dict[str, List[Trade]] = {}
        for t in trades:
            buckets.setdefault(key(t), []).append(t)
        return {
            k: {
                "count": len(v),
                "net_pnl_usd": round(sum(x.net_pnl_usd for x in v), 2),
                "expectancy_r": round(sum(x.r_multiple for x in v) / len(v), 3),
                "win_rate_pct": round(100.0 * sum(1 for x in v if x.net_pnl_usd > 0.0) / len(v), 2),
            }
            for k, v in sorted(buckets.items())
        }

    summary.update(
        {
            "win_rate_pct": round(100.0 * len(wins) / total, 2),
            "net_pnl_usd": round(net, 2),
            "return_pct": round(net / initial_equity * 100.0, 3),
            "profit_factor": round(gross_win / gross_loss, 3) if gross_loss > 0.0 else 0.0,
            "expectancy_r": round(sum(t.r_multiple for t in trades) / total, 3),
            "max_drawdown_pct": round(max_dd, 3),
            "costs_usd": round(sum(t.cost_usd for t in trades), 2),
            "by_engine": group(lambda t: t.engine),
            "by_regime": group(lambda t: t.regime),
            "by_side": group(lambda t: t.side),
        }
    )
    return summary


# --------------------------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------------------------
def generate_synthetic_fixture(
    count: int = 1500, seed: int = 20260220, noise_bps: float = 9.0
) -> List[Bar]:
    """Deterministic pseudo-BTC series: regimes rotate so every engine gets a chance to speak.

    This exists to exercise the code and to keep the tool reproducible offline. It is *not* a
    model of BTC and its metrics must never be quoted as performance.
    """
    bars: List[Bar] = []
    price = 60_000.0
    t0 = 1_760_000_000_000 - (1_760_000_000_000 % M5_MS)
    state = seed
    for i in range(count):
        state = (1103515245 * state + 12345) % (2 ** 31)
        noise = (state / 2 ** 31 - 0.5) * 2.0
        phase = (i // 120) % 4
        drift = (0.55, -0.55, 0.0, 0.0)[phase]
        vol = (1.0, 1.0, 0.35, 1.6)[phase]
        open_price = price
        # ``noise_bps`` is the per-bar amplitude in basis points, so a caller can build a calm or a
        # violent fixture on purpose instead of inheriting an accidental one.
        close = open_price * (1.0 + (drift + noise * noise_bps) / 10_000.0)
        wick = abs(noise) * open_price * (0.6 + vol) * noise_bps / 10_000.0
        high = max(open_price, close) + wick
        low = min(open_price, close) - wick
        volume = 40.0 + (abs(noise) * 90.0) * vol
        bars.append(Bar(t0 + i * M5_MS, open_price, high, low, close, volume))
        price = close
    return bars


def resample(bars: Sequence[Bar], step_ms: int) -> List[Bar]:
    """Higher-timeframe frames are only used as *context*; they are re-sampled from M5 here."""
    out: List[Bar] = []
    bucket: List[Bar] = []
    for bar in bars:
        if bucket and bar.time_ms // step_ms != bucket[0].time_ms // step_ms:
            out.append(
                Bar(bucket[0].time_ms, bucket[0].open, max(b.high for b in bucket),
                    min(b.low for b in bucket), bucket[-1].close, sum(b.volume for b in bucket))
            )
            bucket = []
        bucket.append(bar)
    if bucket:
        out.append(
            Bar(bucket[0].time_ms, bucket[0].open, max(b.high for b in bucket),
                min(b.low for b in bucket), bucket[-1].close, sum(b.volume for b in bucket))
        )
    return out


def load_csv_bars(path: Path) -> List[Bar]:
    bars: List[Bar] = []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ValueError("CSV has no header")
        names = {name.strip().lower(): name for name in reader.fieldnames}
        required = {"time", "open", "high", "low", "close"}
        missing = required - set(names)
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
            bars.append(
                Bar(
                    time_ms=time_ms,
                    open=float(row[names["open"]]),
                    high=float(row[names["high"]]),
                    low=float(row[names["low"]]),
                    close=float(row[names["close"]]),
                    volume=volume,
                )
            )
    bars.sort(key=lambda b: b.time_ms)
    return bars


# --------------------------------------------------------------------------------------------
# Stages
# --------------------------------------------------------------------------------------------
def _stage_metrics(trades: Sequence[Trade], initial_equity: float) -> Dict[str, object]:
    s = summarize(trades, initial_equity)
    return {
        "trades": s["trade_count"],
        "win_rate_pct": s["win_rate_pct"],
        "expectancy_r": s["expectancy_r"],
        "net_pnl_usd": s["net_pnl_usd"],
        "profit_factor": s["profit_factor"],
        "max_drawdown_pct": s["max_drawdown_pct"],
    }


def run_stages(bars: Sequence[Bar], params: Optional[BtcParams] = None) -> Dict[str, object]:
    """Stage A (each engine alone), B (combined), C (ablation), D (chronological split), E (costs).

    Each engine is replayed alone by disabling the others, which is the only way to attribute a
    result to one model. A combined replay that skipped this step could look identical while one
    engine silently supplied every trade.
    """
    p = params or BtcParams()
    m15 = resample(bars, M15_MS)
    h1 = resample(bars, H1_MS)
    warmup = min(p.min_bars, max(30, len(bars) // 3))
    out: Dict[str, object] = {"engine_alone": {}, "combined": {}, "ablation": {}, "split": {}, "costs": {}}

    flags = {
        "A_TREND": dict(enable_a=True, enable_b=False, enable_c=False, enable_d=False, enable_e=False),
        "B_SWEEP": dict(enable_a=False, enable_b=True, enable_c=False, enable_d=False, enable_e=False),
        "C_COMPRESSION": dict(enable_a=False, enable_b=False, enable_c=True, enable_d=False, enable_e=False),
        "D_RANGE": dict(enable_a=False, enable_b=False, enable_c=False, enable_d=True, enable_e=False),
        "E_EXHAUSTION": dict(enable_a=False, enable_b=False, enable_c=False, enable_d=False, enable_e=True),
    }
    for name, kw in flags.items():
        solo = _with_params(p, **kw)
        trades, _ = replay(bars, m15, h1, solo, warmup=warmup)
        out["engine_alone"][name] = _stage_metrics(trades, solo.account_equity_usd)

    all_trades, _ = replay(bars, m15, h1, p, warmup=warmup)
    out["combined"] = _stage_metrics(all_trades, p.account_equity_usd)

    for name, kw in flags.items():
        ablated = _with_params(p, **{k: False for k in kw if k.startswith("enable_")})
        trades, _ = replay(bars, m15, h1, ablated, warmup=warmup)
        out["ablation"][f"without_{name}"] = _stage_metrics(trades, p.account_equity_usd)

    third = len(bars) // 3
    if third > warmup + 50:
        for label, start, end in (
            ("dev", 0, third),
            ("validation", third, 2 * third),
            ("out_of_sample", 2 * third, len(bars)),
        ):
            segment = bars[start:end]
            if len(segment) > warmup:
                trades, _ = replay(segment, resample(segment, M15_MS), resample(segment, H1_MS), p)
                out["split"][label] = _stage_metrics(trades, p.account_equity_usd)

    for label, multiplier in (("base", 1.0), ("double_costs", 2.0), ("triple_costs", 3.0)):
        costed = _with_params(
            p,
            commission_bps_per_side=p.commission_bps_per_side * multiplier,
            slippage_bps=p.slippage_bps * multiplier,
        )
        trades, _ = replay(bars, m15, h1, costed, warmup=warmup)
        out["costs"][label] = _stage_metrics(trades, p.account_equity_usd)

    return out


def _with_params(p: BtcParams, **changes) -> BtcParams:
    from dataclasses import replace

    return replace(p, **changes)


# --------------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------------
def load_bars(
    csv_path: Optional[str], count: int, noise_bps: float = 9.0
) -> Tuple[List[Bar], str]:
    if csv_path:
        path = Path(csv_path)
        bars = load_csv_bars(path)
        if len(bars) < 300:
            raise SystemExit(f"{path} has {len(bars)} bars; BTC-ARES needs at least 300 M5 bars")
        return bars, f"csv:{path}"
    return generate_synthetic_fixture(count, noise_bps=noise_bps), "synthetic"


def report(bars: List[Bar], source: str, params: BtcParams, stages: bool) -> Dict[str, object]:
    m15 = resample(bars, M15_MS)
    h1 = resample(bars, H1_MS)
    warmup = min(params.min_bars, max(30, len(bars) // 3))
    trades, decisions = replay(bars, m15, h1, params, warmup=warmup)
    engine_states: Dict[str, Dict[str, int]] = {name: {} for name in ENGINE_IDS[:5]}
    for d in decisions:
        for c in d.engines:
            if c.engine in engine_states:
                engine_states[c.engine][c.state] = engine_states[c.engine].get(c.state, 0) + 1
    payload: Dict[str, object] = {
        "source": source,
        "synthetic_warning": (
            "SYNTHETIC FIXTURE — metrics describe the code path, not BTC. Use --csv for any claim."
            if source == "synthetic" else None
        ),
        "engine_f": "UNAVAILABLE (no funding/OI/liquidation feed; never scored)",
        "bars": len(bars),
        "decisions": len(decisions),
        "decision_counts": {
            "READY_entries": sum(1 for d in decisions if d.action != "NO_TRADE"),
            "conflicts": sum(1 for d in decisions if d.conflicts),
        },
        "engine_states": engine_states,
        "metrics": summarize(trades, params.account_equity_usd),
        "note": (
            "Win rate is an observed frequency on these bars, not a probability of future success."
        ),
    }
    if stages:
        payload["stages"] = run_stages(bars, params)
    return payload


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="BTC-ARES offline replay (paper only)")
    parser.add_argument("--csv", help="M5 OHLCV CSV (time,open,high,low,close[,volume])")
    parser.add_argument("--bars", type=int, default=3000, help="synthetic fixture length")
    parser.add_argument(
        "--noise-bps", type=float, default=9.0,
        help="synthetic per-bar amplitude in basis points (ignored with --csv)",
    )
    parser.add_argument("--json", help="write the full report to this path")
    parser.add_argument("--stages", action="store_true", help="run stages A–E")
    args = parser.parse_args(argv)
    bars, source = load_bars(args.csv, args.bars, args.noise_bps)
    payload = report(bars, source, BtcParams(), args.stages)
    text = json.dumps(payload, indent=2, ensure_ascii=False)
    if args.json:
        Path(args.json).write_text(text + "\n", encoding="utf-8")
    metrics = payload["metrics"]
    print(f"source: {source} · bars: {payload['bars']} · decisions: {payload['decisions']}")
    if payload["synthetic_warning"]:
        print(payload["synthetic_warning"])
    print(f"engine F: {payload['engine_f']}")
    print(
        "trades {trade_count} · win {win_rate_pct}% · expectancy {expectancy_r}R · "
        "net {net_pnl_usd} USD · PF {profit_factor} · maxDD {max_drawdown_pct}%".format(**metrics)
    )
    for engine, stats in metrics["by_engine"].items():
        print(f"  {engine}: {stats['count']} trades · {stats['expectancy_r']}R · {stats['net_pnl_usd']} USD")
    print(payload["note"])
    if args.json:
        print(f"report written to {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
