#!/usr/bin/env python3
"""EUR-ARES — offline research harness for EUR/USD on M5.

Mirrors ``engine/EurAresEngine.kt`` closely enough to measure it without Android, Gradle or a
broker: the same pip arithmetic, the same DST-aware sessions, the same regime machine with
hysteresis, the same four price engines (plus the news engine that reports "no data"), the same
0–100 score, the same cost gate and the same lot sizing.

What is faithful
----------------
- closed bars only; a decision on bar *i* is filled at the open of bar *i+1*;
- confirmed pivots only (``left``/``right`` closed bars) — no repainting, no look-ahead;
- pips: spread, commission, slippage, stop distance and targets are converted to pips before any
  comparison; one pip is 0.0001, one pipette 0.00001, and the two are never mixed;
- sessions from the **local** clocks (Europe/London, America/New_York, Asia/Tokyo) so DST moves the
  boundaries instead of mislabelling the overlap for half the year, plus the 17:00-New-York
  rollover window where new entries are refused;
- the same cost veto: cost ÷ stop distance above the cap refuses the setup, it never shrinks it.

What is not claimed
-------------------
- No result here is a probability, a forecast or a promise. The synthetic fixture exists to exercise
  the code path and says so in every report.
- Engine E is absent, not faked: the app has no reliable timestamped economic calendar.
- Swap/rollover financing is not estimated; the report says so instead of inventing a number.

Usage::

    python3 tools/eur_ares_backtest.py                    # deterministic synthetic fixture
    python3 tools/eur_ares_backtest.py --csv eurusd_m5.csv
    python3 tools/eur_ares_backtest.py --csv eurusd_m5.csv --h1-csv eurusd_h1.csv --stages
    python3 tools/eur_ares_backtest.py --csv eurusd_m5.csv --spread 0.3 --json report.json
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
from zoneinfo import ZoneInfo

PIP = 0.0001
PIPETTE = 0.00001
M5_MS = 300_000
H1_MS = 3_600_000

LONDON = ZoneInfo("Europe/London")
NEW_YORK = ZoneInfo("America/New_York")
TOKYO = ZoneInfo("Asia/Tokyo")


@dataclass(frozen=True)
class Bar:
    time_ms: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 1000.0


@dataclass(frozen=True)
class EurParams:
    """Mirror of ``EurAresEngine.Config``. Distances are pips unless the name says otherwise."""

    pivot_left: int = 3
    pivot_right: int = 3
    micro_left: int = 2
    micro_right: int = 1
    atr_period: int = 14
    adx_period: int = 14
    ema_fast: int = 21
    ema_slow: int = 55
    htf_ema: int = 50
    rsi_period: int = 14
    vol_median_lookback: int = 60
    atr_percentile_lookback: int = 240
    session_vol_lookback: int = 240
    range_expansion_fast: int = 3
    range_expansion_baseline: int = 20
    low_atr_ratio: float = 0.70
    elevated_atr_ratio: float = 1.40
    extreme_atr_ratio: float = 2.20
    transition_expansion: float = 1.35
    event_expansion: float = 1.80
    trend_adx_min: float = 22.0
    range_adx_max: float = 18.0
    compression_max_range_atr: float = 0.80
    min_range_width_atr: float = 1.2
    max_range_width_atr: float = 5.0
    regime_confirm_bars: int = 2
    # Engine A
    min_impulse_atr: float = 1.20
    min_pullback_frac: float = 0.25
    max_pullback_frac: float = 0.85
    pullback_zone_atr: float = 0.55
    max_chase_atr: float = 1.20
    min_body_frac: float = 0.50
    min_impulse_pips: float = 6.0
    # Engine B
    sweep_window_bars: int = 6
    min_sweep_atr: float = 0.15
    max_sweep_atr: float = 1.20
    min_sweep_pips: float = 0.8
    require_retest: bool = False
    retest_tolerance_atr: float = 0.20
    # Engine C
    range_touch_frac: float = 0.20
    min_rejection_wick_frac: float = 0.40
    range_invalidation_atr: float = 0.25
    # Engine D
    session_range_min_bars: int = 12
    min_breakout_atr: float = 0.15
    min_expansion_for_breakout: float = 1.15
    follow_through_frac: float = 0.55
    max_breakout_chase_atr: float = 1.00
    min_room_r: float = 1.50
    round_number_pips: float = 50.0
    require_session_retest: bool = False
    # Risk / execution
    stop_buffer_atr: float = 0.30
    min_stop_pips: float = 4.0
    max_stop_pips: float = 30.0
    target_rr: float = 2.0
    min_reward_risk: float = 1.5
    max_cost_to_risk_fraction: float = 0.30
    slippage_pips: float = 0.2
    spread_pips_override: Optional[float] = None
    commission_per_unit: float = 0.000035  # $3.50 per 100k per side
    pip_value_usd_per_lot: float = 10.0
    lot_step: float = 0.01
    min_units: float = 1000.0
    max_leverage: int = 30
    risk_per_trade_pct: float = 0.25
    max_daily_loss_pct: float = 1.00
    account_equity_usd: float = 10_000.0
    score_threshold: int = 70
    conflict_margin: int = 10
    max_setup_age_bars: int = 12
    session_gate_enabled: bool = False
    require_htf_alignment: bool = True
    min_bars: int = 280
    enable_a: bool = True
    enable_b: bool = True
    enable_c: bool = True
    enable_d: bool = True
    # Replay-only
    max_hold_bars: int = 48
    exit_model: str = "FIXED_RR"  # FIXED_RR | TRAIL_AFTER_1R | PARTIAL_HALF
    exit_on_structure: bool = False


# ---------------------------------------------------------------------------------------------
# Sessions and clocks (DST-aware; the same rules as EurAresPolicy.sessionAt)
# ---------------------------------------------------------------------------------------------
SESSIONS = ("ASIA", "LONDON", "OVERLAP", "NEW_YORK", "ROLLOVER", "OFF")


def session_at(time_ms: int, rollover_minutes: int = 10) -> Dict[str, object]:
    instant = datetime.fromtimestamp(time_ms / 1000.0, tz=timezone.utc)
    london = instant.astimezone(LONDON)
    new_york = instant.astimezone(NEW_YORK)
    tokyo = instant.astimezone(TOKYO)
    in_london = 8 <= london.hour <= 16
    in_new_york = 8 <= new_york.hour <= 16
    in_tokyo = 9 <= tokyo.hour <= 17
    rollover = new_york.hour == 17 and new_york.minute < rollover_minutes
    if rollover:
        session = "ROLLOVER"
    elif in_london and in_new_york:
        session = "OVERLAP"
    elif in_london:
        session = "LONDON"
    elif in_new_york:
        session = "NEW_YORK"
    elif in_tokyo:
        session = "ASIA"
    else:
        session = "OFF"
    return {
        "session": session,
        "rollover": rollover,
        "london": london.strftime("%H:%M"),
        "newYork": new_york.strftime("%H:%M"),
        "london_date": london.date(),
        "london_hour": london.hour,
    }


def is_tradable_session(session: str) -> bool:
    return session in ("LONDON", "OVERLAP", "NEW_YORK")


SESSION_LABELS = {
    "ASIA": "Asia (thin liquidity)",
    "LONDON": "London",
    "OVERLAP": "London/New York overlap",
    "NEW_YORK": "New York",
    "ROLLOVER": "New York 17:00 rollover",
    "OFF": "Off-session",
}


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
        out[i] = 100.0 if loss == 0.0 and g > 0.0 else (50.0 if loss == 0.0 else 100.0 - 100.0 / (1.0 + g / loss))
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


def confirmed_swings(
    bars: Sequence[Bar], left: int, right: int, up_to_index: Optional[int] = None,
) -> Tuple[List[Tuple[int, float]], List[Tuple[int, float]]]:
    last = (len(bars) - 1) if up_to_index is None else min(up_to_index, len(bars) - 1)
    highs: List[Tuple[int, float]] = []
    lows: List[Tuple[int, float]] = []
    for i in range(left, last - right + 1):
        window = bars[i - left:i + right + 1]
        bar = bars[i]
        others = [b for b in window if b is not bar]
        if bar.high == max(b.high for b in window) and bar.high > max(b.high for b in others):
            highs.append((i, bar.high))
        if bar.low == min(b.low for b in window) and bar.low < min(b.low for b in others):
            lows.append((i, bar.low))
    return highs, lows


def percentile_rank(series: Sequence[Optional[float]], lookback: int) -> float:
    window = [v for v in series[-lookback:] if v is not None and v > 0.0]
    if not window:
        return 0.0
    last = window[-1]
    return (sum(1 for v in window if v < last) + 0.5 * sum(1 for v in window if v == last)) / len(window)


# ---------------------------------------------------------------------------------------------
# Pip economics
# ---------------------------------------------------------------------------------------------
def round_trip_cost_pips(
    price: float, spread_pips: Optional[float], p: EurParams,
) -> float:
    if not math.isfinite(price) or price <= 0.0:
        return float("nan")
    spread = spread_pips if spread_pips is not None and spread_pips >= 0.0 else 0.1
    commission = 2.0 * p.commission_per_unit / PIP
    return spread + commission + 2.0 * p.slippage_pips


def net_reward_risk(entry: float, stop: float, target: float, cost_pips: float) -> float:
    if not all(math.isfinite(v) for v in (entry, stop, target, cost_pips)):
        return float("nan")
    risk_pips = abs(entry - stop) / PIP + cost_pips
    if risk_pips <= 0.0:
        return float("nan")
    return (abs(target - entry) / PIP - cost_pips) / risk_pips


def position_size(
    entry: float, stop_pips: float, equity: float, risk_pct: float, cost_pips: float, p: EurParams,
) -> Tuple[float, float, float]:
    """Returns (lots, units, riskUsd). A size below the venue minimum is a refusal, not a bigger bet."""
    if not all(math.isfinite(v) for v in (entry, stop_pips, equity, risk_pct, cost_pips)):
        return 0.0, 0.0, 0.0
    if entry <= 0.0 or stop_pips <= 0.0 or equity <= 0.0 or risk_pct <= 0.0 or cost_pips < 0.0:
        return 0.0, 0.0, 0.0
    risk_per_lot = (stop_pips + cost_pips) * p.pip_value_usd_per_lot
    if risk_per_lot <= 0.0:
        return 0.0, 0.0, 0.0
    risk_usd = equity * risk_pct / 100.0
    lots = risk_usd / risk_per_lot
    max_lots = equity * p.max_leverage / (entry * 100_000.0)
    lots = min(lots, max_lots)
    lots = math.floor(round(lots / p.lot_step, 6)) * p.lot_step
    lots = round(lots, 2)
    units = lots * 100_000.0
    if lots <= 0.0 or units < p.min_units:
        return 0.0, 0.0, 0.0
    return lots, units, lots * risk_per_lot


def pips(distance: float) -> float:
    return distance / PIP


# ---------------------------------------------------------------------------------------------
# Regime
# ---------------------------------------------------------------------------------------------
REGIMES = ("BULL_TREND", "BEAR_TREND", "RANGE", "COMPRESSION", "EVENT_UNSTABLE")
VOL_STATES = ("LOW", "NORMAL", "TRANSITION", "ELEVATED", "EXTREME")
ENGINE_IDS = ("A_TREND", "B_SWEEP", "C_RANGE", "D_SESSION", "E_NEWS", "X_MACRO")
STATES = ("IDLE", "WATCH", "FORMING", "CONFIRMATION", "READY", "ACTIVE", "INVALIDATED", "EXPIRED",
          "UNAVAILABLE")


@dataclass(frozen=True)
class Features:
    atr: float
    atr_pips: float
    atr_ratio: float
    atr_percentile: float
    session_vol_ratio: float
    range_expansion: float
    adx: float
    ema_fast: float
    ema_slow: float
    rsi: float
    macd_hist: float
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
    htf_bias: int
    htf_available: bool
    session: str
    rollover: bool
    session_vol_note: str
    asian_high: Optional[float]
    asian_low: Optional[float]
    asian_bars: int
    opening_high: Optional[float]
    opening_low: Optional[float]
    opening_ready: bool
    prev_day_high: Optional[float]
    prev_day_low: Optional[float]
    round_above: Optional[float]
    round_below: Optional[float]


def utc_day(ms: int) -> int:
    return ms // 86_400_000


def asian_range(bars: Sequence[Bar]) -> Tuple[Optional[float], Optional[float], int]:
    if not bars:
        return None, None, 0
    day = utc_day(bars[-1].time_ms)
    today = [b for b in bars if utc_day(b.time_ms) == day and session_at(b.time_ms)["session"] == "ASIA"]
    if not today:
        return None, None, 0
    return max(b.high for b in today), min(b.low for b in today), len(today)


def london_opening_range(bars: Sequence[Bar]) -> Tuple[Optional[float], Optional[float], bool]:
    if not bars:
        return None, None, False
    last = session_at(bars[-1].time_ms)
    rng = [b for b in bars
           if session_at(b.time_ms)["london_date"] == last["london_date"]
           and session_at(b.time_ms)["london_hour"] == 8]
    if not rng:
        return None, None, False
    return max(b.high for b in rng), min(b.low for b in rng), last["london_hour"] >= 9


def previous_day_range(bars: Sequence[Bar]) -> Tuple[Optional[float], Optional[float]]:
    if not bars:
        return None, None
    day = utc_day(bars[-1].time_ms)
    prior = sorted({utc_day(b.time_ms) for b in bars if utc_day(b.time_ms) < day})
    if not prior:
        return None, None
    slice_ = [b for b in bars if utc_day(b.time_ms) == prior[-1]]
    if len(slice_) < 24:
        return None, None
    return max(b.high for b in slice_), min(b.low for b in slice_)


def round_numbers(price: float, step_pips: float) -> Tuple[Optional[float], Optional[float]]:
    if price <= 0.0 or step_pips <= 0.0:
        return None, None
    step = step_pips * PIP
    below = math.floor(price / step) * step
    above = below + step
    return round(above, 5), round(below, 5)


def volatility_of(f: Features, p: EurParams) -> str:
    if f.atr_ratio >= p.extreme_atr_ratio or f.atr_percentile >= 0.97:
        return "EXTREME"
    if f.atr_ratio >= p.elevated_atr_ratio or f.atr_percentile >= 0.85:
        return "ELEVATED"
    if f.atr_ratio <= p.low_atr_ratio and f.atr_percentile <= 0.25:
        return "LOW"
    if f.range_expansion >= p.transition_expansion and 0.85 <= f.atr_ratio <= p.elevated_atr_ratio:
        return "TRANSITION"
    return "NORMAL"


def raw_regime(f: Features, p: EurParams) -> str:
    vol = volatility_of(f, p)
    bullish = (f.hh and f.hl) or f.bos_up
    bearish = (f.lh and f.ll) or f.bos_down
    if vol == "EXTREME":
        return "EVENT_UNSTABLE"
    if vol == "ELEVATED" and f.range_expansion >= p.event_expansion:
        return "EVENT_UNSTABLE"
    if bullish and bearish:
        return "EVENT_UNSTABLE"
    if f.compression_ratio <= p.compression_max_range_atr and f.adx < p.trend_adx_min:
        return "COMPRESSION"
    width_atr = (f.range_high - f.range_low) / f.atr if f.atr > 0 else 0.0
    if bullish and f.adx >= p.trend_adx_min and f.htf_bias != -1:
        return "BULL_TREND"
    if bearish and f.adx >= p.trend_adx_min and f.htf_bias != 1:
        return "BEAR_TREND"
    if p.min_range_width_atr <= width_atr <= p.max_range_width_atr and f.adx <= p.range_adx_max:
        return "RANGE"
    return "EVENT_UNSTABLE"


def classify_regime(now: str, previous: Optional[str], p: EurParams) -> Tuple[str, str]:
    if previous is None or now == previous:
        return now, ""
    if now == "EVENT_UNSTABLE":
        return now, "protective state entered immediately"
    if previous == "EVENT_UNSTABLE":
        return previous, "leaving protection needs one more confirmation bar"
    if p.regime_confirm_bars <= 1:
        return now, ""
    return previous, f"hysteresis: {previous} held until confirmed"


def build_features(bars: Sequence[Bar], h1: Sequence[Bar], p: EurParams) -> Features:
    closes = [b.close for b in bars]
    atr_series = compute_atr(bars, p.atr_period)
    atr = next((v for v in reversed(atr_series) if v is not None and v > 0.0), 0.0)
    recent = sorted(v for v in atr_series[-p.vol_median_lookback:] if v is not None and v > 0.0)
    median = recent[len(recent) // 2] if recent else atr
    ranges = [b.high - b.low for b in bars]
    fast = sum(ranges[-p.range_expansion_fast:]) / max(1, len(ranges[-p.range_expansion_fast:]))
    base = sum(ranges[-p.range_expansion_baseline:]) / max(1, len(ranges[-p.range_expansion_baseline:]))
    expansion = fast / base if base > 0 else 1.0
    adx = next((v for v in reversed(compute_adx(bars, p.adx_period)) if v is not None), 0.0)
    ema_fast = next((v for v in reversed(compute_ema(closes, p.ema_fast)) if v is not None), closes[-1])
    ema_slow = next((v for v in reversed(compute_ema(closes, p.ema_slow)) if v is not None), closes[-1])
    rsi = next((v for v in reversed(compute_rsi(bars, p.rsi_period)) if v is not None), 50.0)
    macd = next((v for v in reversed(compute_macd_histogram(bars)) if v is not None), 0.0)

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

    window = list(bars[-25:-1])
    range_high = max((b.high for b in window), default=bars[-1].high)
    range_low = min((b.low for b in window), default=bars[-1].low)
    compression_ratio = (range_high - range_low) / (atr * math.sqrt(24.0)) if atr > 0 else 1.0

    htf_available = len(h1) >= 200
    htf_bias = 0
    if htf_available:
        c = h1[-1].close
        e = next((v for v in reversed(compute_ema([b.close for b in h1], p.htf_ema)) if v is not None), c)
        htf_bias = 1 if c > e else (-1 if c < e else 0)

    session_info = session_at(bars[-1].time_ms)
    session = str(session_info["session"])
    # Session-relative volatility: current ATR ÷ median ATR of the same session.
    current = atr
    peers = sorted(
        v for i, v in enumerate(atr_series[-p.session_vol_lookback:])
        if v is not None and v > 0.0
        and str(session_at(bars[max(0, len(bars) - p.session_vol_lookback) + i].time_ms)["session"]) == session
    )
    session_vol_ratio = current / peers[len(peers) // 2] if len(peers) >= 10 and peers[len(peers) // 2] > 0 else 1.0

    asian = asian_range(bars)
    opening = london_opening_range(bars)
    prev_day = previous_day_range(bars)
    round_ = round_numbers(close, p.round_number_pips)

    return Features(
        atr=atr, atr_pips=pips(atr), atr_ratio=atr / median if median > 0 else 1.0,
        atr_percentile=percentile_rank(atr_series, p.atr_percentile_lookback),
        session_vol_ratio=session_vol_ratio, range_expansion=expansion, adx=adx,
        ema_fast=ema_fast, ema_slow=ema_slow, rsi=rsi, macd_hist=macd,
        swing_high=last_highs[-1] if last_highs else None,
        swing_low=last_lows[-1] if last_lows else None,
        hh=hh, hl=hl, lh=lh, ll=ll, bos_up=bos_up, bos_down=bos_down,
        range_high=range_high, range_low=range_low, compression_ratio=compression_ratio,
        htf_bias=htf_bias, htf_available=htf_available, session=session,
        rollover=bool(session_info["rollover"]),
        session_vol_note=f"session ATR ×{session_vol_ratio:.2f}",
        asian_high=asian[0], asian_low=asian[1], asian_bars=asian[2],
        opening_high=opening[0], opening_low=opening[1], opening_ready=opening[2],
        prev_day_high=prev_day[0], prev_day_low=prev_day[1],
        round_above=round_[0], round_below=round_[1],
    )


# ---------------------------------------------------------------------------------------------
# Engines
# ---------------------------------------------------------------------------------------------
@dataclass
class Candidate:
    engine: str
    state: str
    direction: str
    regime_score: int = 0
    structure_score: int = 0
    entry_score: int = 0
    confirmation_score: int = 0
    volatility_score: int = 0
    session_score: int = 0
    rr_score: int = 0
    cost_score: int = 0
    structural_level: Optional[float] = None
    invalidation: Optional[float] = None
    entry: Optional[float] = None
    stop: Optional[float] = None
    target: Optional[float] = None
    rr: Optional[float] = None
    net_rr: Optional[float] = None
    cost_pips: float = 0.0
    cost_to_risk: float = 0.0
    setup_type: str = ""
    satisfied: Optional[List[str]] = None
    missing: Optional[List[str]] = None
    detail: str = ""
    data_available: bool = True

    @property
    def score(self) -> int:
        return max(0, min(100, self.regime_score + self.structure_score + self.entry_score
                           + self.confirmation_score + self.volatility_score + self.session_score
                           + self.rr_score + self.cost_score))

    @property
    def stop_pips(self) -> Optional[float]:
        if self.entry is None or self.stop is None:
            return None
        return abs(self.entry - self.stop) / PIP


def _no_trade(engine: str, state: str, detail: str, *, cost: float = 0.0,
              missing: Optional[List[str]] = None, satisfied: Optional[List[str]] = None,
              data_available: bool = True) -> Candidate:
    return Candidate(engine, state, "NO_TRADE", cost_pips=cost, setup_type="",
                     satisfied=satisfied or [], missing=missing or [], detail=detail,
                     data_available=data_available)


def session_points(session: str) -> int:
    return {"OVERLAP": 10, "LONDON": 9, "NEW_YORK": 7, "ASIA": 3}.get(session, 0)


def rr_points(net_rr: Optional[float], p: EurParams) -> int:
    if net_rr is None or not math.isfinite(net_rr):
        return 0
    if net_rr >= 2.0:
        return 15
    if net_rr >= 1.75:
        return 12
    if net_rr >= p.min_reward_risk:
        return 9
    return 0


def cost_points(cost_to_risk: float, p: EurParams) -> int:
    if cost_to_risk <= 0.10:
        return 10
    if cost_to_risk <= 0.20:
        return 7
    if cost_to_risk <= p.max_cost_to_risk_fraction:
        return 4
    return 0


def volatility_points(vol: str, engine: str) -> int:
    table = {
        "A_TREND": {"NORMAL": 10, "TRANSITION": 8, "LOW": 6, "ELEVATED": 3, "EXTREME": 0},
        "B_SWEEP": {"NORMAL": 10, "LOW": 9, "TRANSITION": 6, "ELEVATED": 4, "EXTREME": 0},
        "C_RANGE": {"NORMAL": 10, "LOW": 9, "TRANSITION": 6, "ELEVATED": 4, "EXTREME": 0},
        "D_SESSION": {"TRANSITION": 10, "NORMAL": 8, "LOW": 5, "ELEVATED": 3, "EXTREME": 0},
    }
    return table.get(engine, {}).get(vol, 0)


def stop_for(direction: str, raw: float, price: float, atr: float, p: EurParams) -> float:
    distance = abs(price - raw) + p.stop_buffer_atr * atr
    distance = max(distance, p.min_stop_pips * PIP)
    distance = min(distance, p.max_stop_pips * PIP)
    return price - distance if direction == "BUY" else price + distance


def target_at(direction: str, entry: float, stop: float, rr: float) -> float:
    risk = abs(entry - stop)
    return entry + rr * risk if direction == "BUY" else entry - rr * risk


def engine_a(bars: Sequence[Bar], f: Features, regime: str, vol: str, p: EurParams,
             cost: float) -> Candidate:
    if not p.enable_a:
        return _no_trade("A_TREND", "IDLE", "engine A disabled", cost=cost)
    if regime not in ("BULL_TREND", "BEAR_TREND"):
        return _no_trade("A_TREND", "IDLE", f"regime {regime} does not allow continuation", cost=cost)
    if not f.htf_available:
        return _no_trade("A_TREND", "IDLE", "no H1 frame", cost=cost)
    up = regime == "BULL_TREND"
    direction = "BUY" if up else "SELL"
    if p.require_htf_alignment and f.htf_bias != (1 if up else -1):
        return _no_trade("A_TREND", "IDLE", "H1 disagrees", cost=cost)
    price = bars[-1].close
    atr = f.atr
    impulse_level = f.swing_low if up else f.swing_high
    if impulse_level is None:
        return _no_trade("A_TREND", "IDLE", "no confirmed swing", cost=cost)
    other = f.swing_high if up else f.swing_low
    impulse = abs(price - impulse_level)
    satisfied: List[str] = []
    missing: List[str] = []
    (satisfied if impulse >= p.min_impulse_atr * atr and pips(impulse) >= p.min_impulse_pips
     else missing).append(f"impulse {pips(impulse):.1f} pips")
    zone = max(impulse_level, f.ema_fast) if up else min(impulse_level, f.ema_fast)
    distance = abs(price - zone)
    in_zone = distance <= p.pullback_zone_atr * atr
    (satisfied if in_zone else missing).append(f"zone {pips(distance):.1f} pips")
    depth = abs(other - price) / abs(other - impulse_level) if other is not None and other != impulse_level else float("nan")
    depth_ok = math.isfinite(depth) and p.min_pullback_frac <= depth <= p.max_pullback_frac
    (satisfied if depth_ok else missing).append(
        f"depth {depth * 100:.0f}%" if math.isfinite(depth) else "depth unknown")
    last = bars[-1]
    span = last.high - last.low
    body = abs(last.close - last.open) / span if span > 0 else 0.0
    directional = (last.close > last.open) == up and body >= p.min_body_frac
    wick = ((min(last.open, last.close) - last.low) / span if up else
            (last.high - max(last.open, last.close)) / span) if span > 0 else 0.0
    rejection = directional or wick >= 0.30
    (satisfied if directional else missing).append("directional candle" if directional else "rejection wick")
    micro = confirmed_swings(bars, p.micro_left, p.micro_right)
    micro_break = (bool(micro[0]) and price > micro[0][-1][1]) if up else (
        bool(micro[1]) and price < micro[1][-1][1])
    (satisfied if micro_break else missing).append("micro break")
    not_chasing = abs(price - f.ema_fast) / atr <= p.max_chase_atr * 2.0
    (satisfied if not_chasing else missing).append("not extended")
    momentum = (f.macd_hist > 0 and f.rsi >= 45) if up else (f.macd_hist < 0 and f.rsi <= 55)
    (satisfied if momentum else missing).append("momentum")
    ready = (impulse >= p.min_impulse_atr * atr and pips(impulse) >= p.min_impulse_pips
             and in_zone and depth_ok and rejection and not_chasing and momentum
             and (micro_break or directional))
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 5 else (
        "FORMING" if in_zone or micro_break else "WATCH"))
    level = min(impulse_level, zone) if up else max(impulse_level, zone)
    raw_stop = level - p.stop_buffer_atr * atr * 0.5 if up else level + p.stop_buffer_atr * atr * 0.5
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    fixed = target_at(direction, price, stop, max(p.min_reward_risk, p.target_rr))
    obstacles = [v for v in (f.prev_day_high, f.round_above, f.range_high) if v is not None]
    if up:
        obstacle = min([v for v in obstacles if v > price] or [fixed])
        target = max(fixed, min(obstacle, price + 3.0 * risk))
    else:
        obstacle = max([v for v in obstacles if v < price] or [fixed])
        target = min(fixed, max(obstacle, price - 3.0 * risk))
    rr = abs(target - price) / risk if risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost)
    ctr = cost / pips(risk) if risk > 0 else float("inf")
    return Candidate(
        "A_TREND", state, direction if ready else "NO_TRADE",
        regime_score=15,
        structure_score=15 if impulse >= p.min_impulse_atr * atr and depth_ok else (9 if in_zone else 4),
        entry_score=10 if in_zone and depth_ok else (6 if in_zone else 2),
        confirmation_score=15 if micro_break and rejection else (9 if rejection or micro_break else 3),
        volatility_score=volatility_points(vol, "A_TREND"),
        session_score=session_points(f.session),
        rr_score=rr_points(net, p), cost_score=cost_points(ctr, p),
        structural_level=level, invalidation=stop, entry=price, stop=stop, target=target,
        rr=rr, net_rr=net, cost_pips=cost, cost_to_risk=ctr, setup_type="TREND_PULLBACK",
        satisfied=satisfied, missing=missing,
        detail=f"{regime} · stop {pips(risk):.1f}p · net R:R {net:.2f}",
    )


def engine_b(bars: Sequence[Bar], f: Features, regime: str, vol: str, p: EurParams,
             cost: float) -> Candidate:
    if not p.enable_b:
        return _no_trade("B_SWEEP", "IDLE", "engine B disabled", cost=cost)
    if regime == "EVENT_UNSTABLE":
        return _no_trade("B_SWEEP", "IDLE", "no sweeps during event volatility", cost=cost)
    atr = f.atr
    price = bars[-1].close
    window = bars[-p.sweep_window_bars:]
    highs, lows = confirmed_swings(bars, p.pivot_left, p.pivot_right)
    prior_high = highs[-1][1] if highs else None
    prior_low = lows[-1][1] if lows else None
    levels_low = [v for v in (prior_low, f.asian_low) if v is not None]
    levels_high = [v for v in (prior_high, f.asian_high) if v is not None]
    swept_low = [lv for lv in levels_low
                 if any(b.low < lv and b.low >= lv - p.max_sweep_atr * atr for b in window)]
    swept_high = [lv for lv in levels_high
                  if any(b.high > lv and b.high <= lv + p.max_sweep_atr * atr for b in window)]
    swept_low_level = max(swept_low) if swept_low else None
    swept_high_level = min(swept_high) if swept_high else None
    if swept_low_level is not None and price > swept_low_level:
        direction, level = "BUY", swept_low_level
    elif swept_high_level is not None and price < swept_high_level:
        direction, level = "SELL", swept_high_level
    else:
        return _no_trade("B_SWEEP", "WATCH", "no confirmed sweep", cost=cost,
                         missing=["no level pierced and reclaimed"])
    satisfied = ["pierced a level and closed back inside"]
    missing: List[str] = []
    extreme = next((b for b in reversed(window)
                    if (b.low < level if direction == "BUY" else b.high > level)), None)
    penetration = 0.0 if extreme is None else (
        (level - extreme.low) / atr if direction == "BUY" else (extreme.high - level) / atr)
    penetration_pips = 0.0 if extreme is None else (
        pips(level - extreme.low) if direction == "BUY" else pips(extreme.high - level))
    (satisfied if penetration >= p.min_sweep_atr and penetration_pips >= p.min_sweep_pips
     else missing).append(f"penetration {penetration_pips:.1f} pips")
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
        (satisfied if retest_ok else missing).append("retest")
    ready = penetration >= p.min_sweep_atr and penetration_pips >= p.min_sweep_pips and shift and close_ok and retest_ok
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 4 else (
        "FORMING" if shift or close_ok else "WATCH"))
    raw_stop = (extreme.low if extreme is not None else level - 0.5 * atr) - p.stop_buffer_atr * atr \
        if direction == "BUY" else (extreme.high if extreme is not None else level + 0.5 * atr) + p.stop_buffer_atr * atr
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    mid = (f.range_high + f.range_low) / 2.0
    opposite = f.range_high if direction == "BUY" else f.range_low
    fixed = target_at(direction, price, stop, max(p.min_reward_risk, p.target_rr))
    target = max(min(mid, opposite), fixed) if direction == "BUY" else min(max(mid, opposite), fixed)
    rr = abs(target - price) / risk if risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost)
    ctr = cost / pips(risk) if risk > 0 else float("inf")
    return Candidate(
        "B_SWEEP", state, direction if ready else "NO_TRADE",
        regime_score=15 if regime in ("RANGE", "COMPRESSION") else 9,
        structure_score=15 if penetration >= p.min_sweep_atr and (
            abs(level - f.range_low) < 0.5 * atr or abs(level - f.range_high) < 0.5 * atr) else (
            10 if penetration >= p.min_sweep_atr else 4),
        entry_score=10 if close_ok else 5,
        confirmation_score=15 if shift and close_ok else (9 if shift or close_ok else 3),
        volatility_score=volatility_points(vol, "B_SWEEP"),
        session_score=session_points(f.session),
        rr_score=rr_points(net, p), cost_score=cost_points(ctr, p),
        structural_level=level, invalidation=stop, entry=price, stop=stop, target=target,
        rr=rr, net_rr=net, cost_pips=cost, cost_to_risk=ctr, setup_type="LIQUIDITY_SWEEP",
        satisfied=satisfied, missing=missing,
        detail=f"sweep {penetration_pips:.1f} pips · net R:R {net:.2f}",
    )


def engine_c(bars: Sequence[Bar], f: Features, regime: str, vol: str, p: EurParams,
             cost: float) -> Candidate:
    if not p.enable_c:
        return _no_trade("C_RANGE", "IDLE", "engine C disabled", cost=cost)
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
    wick = ((min(last.open, last.close) - last.low) / span if direction == "BUY" else
            (last.high - max(last.open, last.close)) / span) if span > 0 else 0.0
    (satisfied if wick >= p.min_rejection_wick_frac else missing).append(f"rejection wick {wick * 100:.0f}%")
    rsi_ok = f.rsi <= 40 if direction == "BUY" else f.rsi >= 60
    (satisfied if rsi_ok else missing).append(f"RSI {f.rsi:.0f}")
    adx_ok = f.adx <= p.range_adx_max + 4.0
    (satisfied if adx_ok else missing).append(f"ADX {f.adx:.1f}")
    level = f.range_low if direction == "BUY" else f.range_high
    raw_stop = level - p.stop_buffer_atr * atr if direction == "BUY" else level + p.stop_buffer_atr * atr
    stop = stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop)
    mid = (f.range_high + f.range_low) / 2.0
    fixed = target_at(direction, price, stop, max(p.min_reward_risk, p.target_rr))
    target = max(mid, fixed) if direction == "BUY" else min(mid, fixed)
    rr = abs(target - price) / risk if risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost)
    ctr = cost / pips(risk) if risk > 0 else float("inf")
    ready = wick >= p.min_rejection_wick_frac and rsi_ok and adx_ok and net >= p.min_reward_risk
    state = "READY" if ready else ("CONFIRMATION" if len(satisfied) >= 3 else "FORMING")
    return Candidate(
        "C_RANGE", state, direction if ready else "NO_TRADE",
        regime_score=15, structure_score=12,
        entry_score=10 if near_low or near_high else 5,
        confirmation_score=15 if wick >= p.min_rejection_wick_frac and rsi_ok else (
            9 if wick >= p.min_rejection_wick_frac else 3),
        volatility_score=volatility_points(vol, "C_RANGE"),
        session_score=session_points(f.session),
        rr_score=rr_points(net, p), cost_score=cost_points(ctr, p),
        structural_level=level, invalidation=stop, entry=price, stop=stop, target=target,
        rr=rr, net_rr=net, cost_pips=cost, cost_to_risk=ctr, setup_type="RANGE_REVERSION",
        satisfied=satisfied, missing=missing,
        detail=f"position {position * 100:.0f}% · net R:R {net:.2f}",
    )


def engine_d(bars: Sequence[Bar], f: Features, regime: str, vol: str, p: EurParams,
             cost: float) -> Candidate:
    if not p.enable_d:
        return _no_trade("D_SESSION", "IDLE", "engine D disabled", cost=cost)
    if regime == "EVENT_UNSTABLE":
        return _no_trade("D_SESSION", "IDLE", "no session breakouts during event volatility", cost=cost)
    atr = f.atr
    price = bars[-1].close
    use_asian = f.session != "ASIA" and f.asian_bars >= p.session_range_min_bars \
        and f.asian_high is not None and f.asian_low is not None
    use_opening = f.opening_ready and f.opening_high is not None and f.opening_low is not None
    if use_opening:
        range_high, range_low, name = f.opening_high, f.opening_low, "London opening range"
    elif use_asian:
        range_high, range_low, name = f.asian_high, f.asian_low, "Asian range"
    else:
        return _no_trade("D_SESSION", "WATCH",
                         f"session range incomplete (Asia {f.asian_bars} bars)", cost=cost,
                         missing=["session range not complete"])
    width = range_high - range_low
    if width <= 0:
        return _no_trade("D_SESSION", "WATCH", "invalid session range", cost=cost)
    width_atr = width / atr if atr > 0 else 0.0
    satisfied: List[str] = []
    missing: List[str] = []
    (satisfied if p.min_range_width_atr <= width_atr <= p.max_range_width_atr
     else missing).append(f"{name} width {width_atr:.2f} ATR")
    up_break = price > range_high + p.min_breakout_atr * atr
    down_break = price < range_low - p.min_breakout_atr * atr
    if up_break and not down_break:
        direction = "BUY"
    elif down_break and not up_break:
        direction = "SELL"
    else:
        direction = "NO_TRADE"
        missing.append("no confirmed close beyond the range boundary")
    if direction != "NO_TRADE":
        satisfied.append(f"close beyond the {'upper' if up_break else 'lower'} boundary")
    expansion_ok = f.range_expansion >= p.min_expansion_for_breakout
    (satisfied if expansion_ok else missing).append(f"range expansion {f.range_expansion:.2f}x")
    last = bars[-1]
    span = last.high - last.low
    close_frac = (last.close - last.low) / span if span > 0 else 0.5
    follow = (close_frac >= p.follow_through_frac if direction == "BUY" else
              close_frac <= 1.0 - p.follow_through_frac) if direction != "NO_TRADE" else False
    if direction != "NO_TRADE":
        (satisfied if follow else missing).append("close in the breakout direction")
    late = ((price - range_high) / atr if direction == "BUY" else
            (range_low - price) / atr) if direction != "NO_TRADE" else 0.0
    not_late = late <= p.min_breakout_atr + p.max_breakout_chase_atr
    if direction != "NO_TRADE":
        (satisfied if not_late else missing).append(f"distance from boundary {late:.2f} ATR")
    level = range_low if direction == "SELL" else range_high
    raw_stop = range_high if direction == "SELL" else range_low
    stop = None if direction == "NO_TRADE" else stop_for(direction, raw_stop, price, atr, p)
    risk = abs(price - stop) if stop is not None else 0.0
    obstacles = [v for v in (f.prev_day_high, f.round_above) if v is not None] if direction == "BUY" else \
        [v for v in (f.prev_day_low, f.round_below) if v is not None]
    if direction == "BUY":
        obstacle = min([v for v in obstacles if v > price] or [None]) if obstacles else None
    elif direction == "SELL":
        obstacle = max([v for v in obstacles if v < price] or [None]) if obstacles else None
    else:
        obstacle = None
    measured = price + width if direction == "BUY" else price - width
    capped = price + 3.0 * risk if direction == "BUY" else price - 3.0 * risk
    obstacle_target = obstacle if obstacle is not None else measured
    if direction == "BUY":
        target = min(max(obstacle_target, price + p.min_reward_risk * risk), max(measured, capped))
    elif direction == "SELL":
        target = max(min(obstacle_target, price - p.min_reward_risk * risk), min(measured, capped))
    else:
        target = None
    room_r = abs(obstacle - price) / risk if (risk > 0 and obstacle is not None) else float("nan")
    room_ok = not math.isfinite(room_r) or room_r >= p.min_room_r
    if math.isfinite(room_r):
        (satisfied if room_ok else missing).append(f"room to obstacle {room_r:.2f}R")
    retest_ok = True
    if p.require_session_retest and direction != "NO_TRADE":
        retest_ok = (last.low <= level + 0.25 * atr and price > level) if direction == "BUY" else (
            last.high >= level - 0.25 * atr and price < level)
        (satisfied if retest_ok else missing).append("retest of the broken boundary")
    rr = abs(target - price) / risk if target is not None and risk > 0 else 0.0
    net = net_reward_risk(price, stop, target, cost) if target is not None and stop is not None else float("nan")
    ctr = cost / pips(risk) if risk > 0 else float("inf")
    ready = (direction != "NO_TRADE" and p.min_range_width_atr <= width_atr <= p.max_range_width_atr
             and expansion_ok and follow and not_late and room_ok and retest_ok
             and math.isfinite(net) and net >= p.min_reward_risk)
    state = "READY" if ready else ("CONFIRMATION" if direction != "NO_TRADE" and len(satisfied) >= 4
                                   else ("FORMING" if direction != "NO_TRADE" else "WATCH"))
    return Candidate(
        "D_SESSION", state, direction if ready else "NO_TRADE",
        regime_score=15 if vol in ("TRANSITION", "NORMAL") else 9,
        structure_score=15 if p.min_range_width_atr <= width_atr <= p.max_range_width_atr else 6,
        entry_score=10 if not_late else 4,
        confirmation_score=15 if expansion_ok and follow else (8 if expansion_ok or follow else 2),
        volatility_score=volatility_points(vol, "D_SESSION"),
        session_score=session_points(f.session),
        rr_score=rr_points(net if math.isfinite(net) else None, p),
        cost_score=cost_points(ctr, p),
        structural_level=None if direction == "NO_TRADE" else level,
        invalidation=stop,
        entry=None if direction == "NO_TRADE" else price,
        stop=stop, target=target, rr=rr,
        net_rr=net if math.isfinite(net) else None,
        cost_pips=cost, cost_to_risk=ctr, setup_type="SESSION_BREAKOUT",
        satisfied=satisfied, missing=missing,
        detail=f"{name} · width {width_atr:.2f} ATR · net R:R "
               f"{(net if math.isfinite(net) else 0.0):.2f}",
    )


def engine_e(cost: float) -> Candidate:
    return _no_trade("E_NEWS", "UNAVAILABLE",
                     "no reliable timestamped economic calendar; news module disabled",
                     cost=cost, missing=["no event source with publication time"],
                     data_available=False)


def engine_x(macro_available: bool = False) -> Candidate:
    # No measured series was supplied, so the module has no data — it is not a quiet "0".
    return _no_trade("X_MACRO", "UNAVAILABLE",
                     "macro/cross-market module off (no measured series supplied)",
                     data_available=False)


# ---------------------------------------------------------------------------------------------
# Decision
# ---------------------------------------------------------------------------------------------
@dataclass
class Decision:
    bar_time: int
    regime: str
    volatility: str
    session: str
    rollover: bool
    engines: List[Candidate]
    action: str
    chosen_engine: Optional[str]
    score: int
    entry: Optional[float]
    stop: Optional[float]
    target: Optional[float]
    stop_pips: Optional[float]
    rr: Optional[float]
    net_rr: Optional[float]
    cost_pips: float
    cost_to_risk: float
    lots: float
    units: float
    risk_usd: float
    conflicts: List[str]
    no_trade_reasons: List[str]
    setup_key: str
    raw_regime: str


def decide(
    bars: Sequence[Bar], h1: Sequence[Bar], p: EurParams, previous_raw: Optional[str] = None,
    daily_realized_loss_pct: float = 0.0, daily_loss_cap: Optional[float] = None,
    spread_pips: Optional[float] = None,
) -> Decision:
    f = build_features(bars, h1, p)
    vol = volatility_of(f, p)
    now_raw = raw_regime(f, p)
    regime, _ = classify_regime(now_raw, previous_raw, p)
    price = bars[-1].close
    cost = round_trip_cost_pips(price, spread_pips if spread_pips is not None else p.spread_pips_override, p)
    candidates = [
        engine_a(bars, f, regime, vol, p, cost),
        engine_b(bars, f, regime, vol, p, cost),
        engine_c(bars, f, regime, vol, p, cost),
        engine_d(bars, f, regime, vol, p, cost),
        engine_e(cost),
        engine_x(),
    ]
    ready = sorted((c for c in candidates if c.state == "READY" and c.direction != "NO_TRADE"),
                   key=lambda c: c.score, reverse=True)
    conflicts: List[str] = []
    if len(ready) >= 2:
        top = ready[0]
        opposite = next((c for c in ready if c.direction != top.direction), None)
        if opposite is not None and abs(top.score - opposite.score) < p.conflict_margin:
            conflicts.append(f"conflict {top.engine} vs {opposite.engine}")
    reasons: List[str] = []
    best = None if conflicts or not ready else ready[0]
    if best is None and not conflicts:
        reasons.append("no engine reached READY; " + ", ".join(
            f"{c.engine}={c.state}" for c in candidates
            if c.state not in ("IDLE", "WATCH", "UNAVAILABLE")))
    if best is not None and best.score < p.score_threshold:
        reasons.append(f"score {best.score}/100 < {p.score_threshold}")
    if best is not None and (best.net_rr or 0.0) < p.min_reward_risk:
        reasons.append(f"net R:R {best.net_rr or 0.0:.2f} < {p.min_reward_risk}")
    if best is not None and best.cost_to_risk > p.max_cost_to_risk_fraction:
        reasons.append(f"cost/risk {best.cost_to_risk:.2f} > {p.max_cost_to_risk_fraction}")
    cap = p.max_daily_loss_pct if daily_loss_cap is None else daily_loss_cap
    if daily_realized_loss_pct >= cap:
        reasons.append("daily loss cap reached")
    if regime == "EVENT_UNSTABLE":
        reasons.append("event/unstable volatility: no new entries")
    if f.rollover:
        reasons.append("New York rollover window")
    if p.session_gate_enabled and not is_tradable_session(f.session):
        reasons.append(f"session {f.session} is blocked")

    actionable = best if not reasons else None
    lots, units, risk_usd = (position_size(actionable.entry, actionable.stop_pips or 0.0,
                                           p.account_equity_usd, p.risk_per_trade_pct,
                                           actionable.cost_pips, p)
                             if actionable and actionable.entry is not None else (0.0, 0.0, 0.0))
    if actionable is not None and lots <= 0.0:
        reasons.append("size below the venue minimum")
    action = actionable.direction if actionable and lots > 0.0 and not reasons else "NO_TRADE"
    key = ""
    if action != "NO_TRADE" and actionable is not None:
        key = "|".join(["EUR-ARES", "EUR/USD", actionable.engine, actionable.setup_type, action,
                        f"{actionable.structural_level or 0.0:.5f}", str(bars[-1].time_ms)])
    return Decision(
        bar_time=bars[-1].time_ms, regime=regime, volatility=vol, session=f.session,
        rollover=f.rollover, engines=candidates, action=action,
        chosen_engine=actionable.engine if action != "NO_TRADE" else None,
        score=actionable.score if action != "NO_TRADE" else (best.score if best else 0),
        entry=actionable.entry if action != "NO_TRADE" else None,
        stop=actionable.stop if action != "NO_TRADE" else None,
        target=actionable.target if action != "NO_TRADE" else None,
        stop_pips=actionable.stop_pips if action != "NO_TRADE" else None,
        rr=actionable.rr if action != "NO_TRADE" else None,
        net_rr=actionable.net_rr if action != "NO_TRADE" else None,
        cost_pips=cost,
        cost_to_risk=actionable.cost_to_risk if action != "NO_TRADE" else 0.0,
        lots=lots if action != "NO_TRADE" else 0.0,
        units=units if action != "NO_TRADE" else 0.0,
        risk_usd=risk_usd if action != "NO_TRADE" else 0.0,
        conflicts=conflicts, no_trade_reasons=reasons, setup_key=key, raw_regime=now_raw,
    )


# ---------------------------------------------------------------------------------------------
# Trade simulation
# ---------------------------------------------------------------------------------------------
@dataclass
class Trade:
    engine: str
    side: str
    regime: str
    session: str
    entry_time: int
    exit_time: int
    entry: float
    exit_price: float
    stop: float
    target: float
    lots: float
    units: float
    stop_pips: float
    gross_pnl_usd: float
    cost_usd: float
    net_pnl_usd: float
    r_multiple: float
    outcome: str
    bars_held: int


def simulate_trade(
    bars: Sequence[Bar], trigger_index: int, action: str, planned_entry: float, stop: float,
    target: float, lots: float, p: EurParams, spread_pips: Optional[float] = None,
) -> Dict[str, object]:
    """Fill on the next open, walk bar by bar, and let the stop win any tie with the target.

    Slippage is charged in the trade direction on both sides, the spread once, and a gap that opens
    beyond the invalidation is a *refusal* — never a stop widened to fit.
    """
    if trigger_index + 1 >= len(bars) or lots <= 0.0:
        return {"lots": 0.0}
    fill_bar = bars[trigger_index + 1]
    slip = p.slippage_pips * PIP
    entry = fill_bar.open + slip if action == "BUY" else fill_bar.open - slip
    if (action == "BUY" and entry <= stop) or (action == "SELL" and entry >= stop):
        return {"lots": 0.0}
    units = lots * 100_000.0
    spread = spread_pips if spread_pips is not None else (p.spread_pips_override or 0.1)
    cost_usd = units * (2.0 * p.commission_per_unit + spread * PIP)
    exit_price: Optional[float] = None
    exit_time = 0
    outcome = "EXPIRED"
    bars_held = 0
    partial = False
    realized_partial = 0.0
    qty = lots
    horizon = p.max_hold_bars * (2 if p.exit_model == "PARTIAL_HALF" else 1)
    for j in range(trigger_index + 1, min(trigger_index + 1 + horizon, len(bars))):
        bar = bars[j]
        bars_held = j - trigger_index
        stop_hit = bar.low <= stop if action == "BUY" else bar.high >= stop
        target_hit = bar.high >= target if action == "BUY" else bar.low <= target
        if p.exit_model == "TRAIL_AFTER_1R":
            risk = abs(entry - stop)
            moved = (bar.high - entry) if action == "BUY" else (entry - bar.low)
            if moved >= risk:
                trail = entry + 0.5 * risk if action == "BUY" else entry - 0.5 * risk
                stop = max(stop, trail) if action == "BUY" else min(stop, trail)
        if p.exit_model == "PARTIAL_HALF" and not partial:
            half = entry + (target - entry) * 0.5 if action == "BUY" else entry - (entry - target) * 0.5
            half_hit = bar.high >= half if action == "BUY" else bar.low <= half
            if half_hit and not stop_hit:
                partial = True
                move = (half - entry) if action == "BUY" else (entry - half)
                realized_partial = move * (units / 2.0)
                qty = lots / 2.0
                stop = entry
        if stop_hit:
            exit_price, exit_time, outcome = stop, bar.time_ms, "STOP"
            break
        if target_hit:
            exit_price, exit_time, outcome = target, bar.time_ms, "TARGET"
            break
    if exit_price is None:
        last = bars[min(trigger_index + p.max_hold_bars, len(bars) - 1)]
        exit_price, exit_time = last.close, last.time_ms
    exit_slip = p.slippage_pips * PIP
    exit_fill = exit_price - exit_slip if action == "BUY" else exit_price + exit_slip
    units_now = qty * 100_000.0
    gross = ((exit_fill - entry) * units_now if action == "BUY" else
             (entry - exit_fill) * units_now) + realized_partial
    return {
        "lots": lots, "entry": entry, "exit_price": exit_fill, "exit_time": exit_time,
        "entry_time": fill_bar.time_ms, "gross_pnl_usd": gross, "cost_usd": cost_usd,
        "net_pnl_usd": gross - cost_usd, "outcome": outcome, "bars_held": bars_held,
    }


def replay(
    bars: Sequence[Bar], h1: Sequence[Bar], p: EurParams, warmup: int = 280,
    daily_loss_cap: Optional[float] = None, spread_pips: Optional[float] = None,
) -> Tuple[List[Trade], List[Decision]]:
    trades: List[Trade] = []
    decisions: List[Decision] = []
    equity = p.account_equity_usd
    day_start = equity
    current_day: Optional[int] = None
    busy_until = -1
    previous_raw: Optional[str] = None
    cap = p.max_daily_loss_pct if daily_loss_cap is None else daily_loss_cap
    for i in range(warmup, len(bars) - 1):
        bar = bars[i]
        day = utc_day(bar.time_ms)
        if day != current_day:
            current_day = day
            day_start = equity
        realized_loss = max(0.0, (day_start - equity) / day_start * 100.0) if day_start > 0 else 0.0
        h1_closed = [b for b in h1 if b.time_ms + H1_MS <= bar.time_ms + M5_MS]
        decision = decide(bars[:i + 1], h1_closed, p, previous_raw, realized_loss, cap, spread_pips)
        decisions.append(decision)
        previous_raw = decision.raw_regime
        if i <= busy_until or decision.action == "NO_TRADE":
            continue
        assert decision.entry is not None and decision.stop is not None and decision.target is not None
        fill = simulate_trade(bars, i, decision.action, decision.entry, decision.stop,
                              decision.target, decision.lots, p, spread_pips)
        if fill.get("lots", 0.0) <= 0.0:
            continue
        # R is a currency ratio: net USD ÷ the USD at risk (stop + costs, in pips × pip value).
        risk_usd = ((decision.stop_pips or 0.0) + decision.cost_pips) * p.pip_value_usd_per_lot * decision.lots
        r_multiple = fill["net_pnl_usd"] / risk_usd if risk_usd > 0 else 0.0
        trades.append(Trade(
            engine=decision.chosen_engine or "", side=decision.action, regime=decision.regime,
            session=decision.session, entry_time=fill["entry_time"], exit_time=fill["exit_time"],
            entry=fill["entry"], exit_price=fill["exit_price"], stop=decision.stop,
            target=decision.target, lots=decision.lots, units=decision.units,
            stop_pips=decision.stop_pips or 0.0, gross_pnl_usd=fill["gross_pnl_usd"],
            cost_usd=fill["cost_usd"], net_pnl_usd=fill["net_pnl_usd"], r_multiple=r_multiple,
            outcome=fill["outcome"], bars_held=fill["bars_held"]))
        equity += fill["net_pnl_usd"]
        busy_until = i + max(1, fill["bars_held"])
    return trades, decisions


def summarize(trades: Sequence[Trade], initial_equity: float = 10_000.0) -> Dict[str, object]:
    total = len(trades)
    base: Dict[str, object] = {
        "trade_count": 0, "win_rate_pct": 0.0, "net_pnl_usd": 0.0, "return_pct": 0.0,
        "profit_factor": None, "expectancy_r": 0.0, "avg_win_usd": 0.0, "avg_loss_usd": 0.0,
        "gross_profit_usd": 0.0, "gross_loss_usd": 0.0, "max_drawdown_pct": 0.0,
        "max_consecutive_losses": 0, "costs_usd": 0.0, "cost_share_of_gross_profit_pct": 0.0,
        "avg_stop_pips": 0.0, "avg_cost_pips": 0.0, "by_engine": {}, "by_regime": {},
        "by_session": {}, "by_side": {}, "by_outcome": {},
    }
    if total == 0:
        return base
    wins = [t for t in trades if t.net_pnl_usd > 0]
    losses = [t for t in trades if t.net_pnl_usd <= 0]
    gross_win = sum(t.net_pnl_usd for t in wins)
    gross_loss = abs(sum(t.net_pnl_usd for t in losses))
    net = sum(t.net_pnl_usd for t in trades)
    costs = sum(t.cost_usd for t in trades)
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
        "trade_count": total,
        "win_rate_pct": round(100.0 * len(wins) / total, 2),
        "net_pnl_usd": round(net, 2),
        "return_pct": round(net / initial_equity * 100.0, 3),
        # With no losing trade there is no ratio to report; a fake 0.0 would read as "nothing works".
        "profit_factor": round(gross_win / gross_loss, 3) if gross_loss > 0 else None,
        "expectancy_r": round(sum(t.r_multiple for t in trades) / total, 3),
        "avg_win_usd": round(gross_win / len(wins), 2) if wins else 0.0,
        "avg_loss_usd": round(-gross_loss / len(losses), 2) if losses else 0.0,
        "gross_profit_usd": round(gross_win, 2),
        "gross_loss_usd": round(gross_loss, 2),
        "max_drawdown_pct": round(max_dd, 3),
        "max_consecutive_losses": worst,
        "costs_usd": round(costs, 2),
        "cost_share_of_gross_profit_pct": round(100.0 * costs / gross_win, 2) if gross_win > 0 else 0.0,
        "avg_stop_pips": round(sum(t.stop_pips for t in trades) / total, 2),
        "avg_cost_pips": round(sum(t.cost_usd / (t.units * PIP) for t in trades) / total, 2),
        "by_engine": group(lambda t: t.engine),
        "by_regime": group(lambda t: t.regime),
        "by_session": group(lambda t: t.session),
        "by_side": group(lambda t: t.side),
        "by_outcome": group(lambda t: t.outcome),
    })
    return base


# ---------------------------------------------------------------------------------------------
# Fixtures / CSV
# ---------------------------------------------------------------------------------------------
def generate_synthetic_fixture(
    count: int = 3000, seed: int = 20260715, noise_pips: float = 1.2, base: float = 1.0850,
    start: Optional[int] = None,
) -> List[Bar]:
    """Deterministic euro-like fixture: regimes rotate so every engine can be exercised.

    Synthetic by construction: its metrics describe the fixture, never the euro.
    """
    bars: List[Bar] = []
    price = base
    t0 = start if start is not None else (1_784_000_000_000 - (1_784_000_000_000 % M5_MS))
    state = seed
    for i in range(count):
        state = (1103515245 * state + 12345) % (2 ** 31)
        noise = (state / 2 ** 31 - 0.5) * 2.0
        phase = (i // 120) % 4
        drift = (0.20, -0.20, 0.0, 0.0)[phase]
        vol = (1.0, 1.0, 0.35, 1.8)[phase]
        open_price = price
        close = open_price + (drift + noise * noise_pips * vol) * PIP
        wick = abs(noise) * (0.6 + vol) * noise_pips * PIP
        bars.append(Bar(t0 + i * M5_MS, open_price, max(open_price, close) + wick,
                        min(open_price, close) - wick, close, 1000.0 + abs(noise) * 500.0))
        price = close
    return bars


def to_h1(bars: Sequence[Bar]) -> List[Bar]:
    """Aggregate H1 from the M5 tape for the *harness only*; the app fetches each frame itself."""
    out: List[Bar] = []
    bucket: List[Bar] = []
    for bar in bars:
        if bucket and bar.time_ms // H1_MS != bucket[0].time_ms // H1_MS:
            out.append(Bar(bucket[0].time_ms, bucket[0].open, max(b.high for b in bucket),
                           min(b.low for b in bucket), bucket[-1].close, sum(b.volume for b in bucket)))
            bucket = []
        bucket.append(bar)
    if bucket:
        out.append(Bar(bucket[0].time_ms, bucket[0].open, max(b.high for b in bucket),
                       min(b.low for b in bucket), bucket[-1].close, sum(b.volume for b in bucket)))
    return out


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
            raw = row[names["time"]].strip()
            if raw.isdigit():
                time_ms = int(raw)
                if time_ms < 10_000_000_000:
                    time_ms *= 1000
            else:
                parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=timezone.utc)
                time_ms = int(parsed.timestamp() * 1000)
            volume = float(row[names["volume"]]) if "volume" in names and row[names["volume"]] else 1000.0
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
                              "profit_factor", "max_drawdown_pct", "max_consecutive_losses",
                              "avg_stop_pips", "costs_usd")}


def run_stages(bars: Sequence[Bar], h1: Sequence[Bar], params: Optional[EurParams] = None) -> Dict[str, object]:
    """A) engines alone, B) combined, C) ablation, D) robustness, E) chronological split."""
    p = params or EurParams()
    warmup = min(p.min_bars, max(30, len(bars) // 3))
    out: Dict[str, object] = {"engine_alone": {}, "combined": {}, "ablation": {},
                              "robustness": {}, "split": {}}
    flags = {
        "A_TREND": dict(enable_a=True, enable_b=False, enable_c=False, enable_d=False),
        "B_SWEEP": dict(enable_a=False, enable_b=True, enable_c=False, enable_d=False),
        "C_RANGE": dict(enable_a=False, enable_b=False, enable_c=True, enable_d=False),
        "D_SESSION": dict(enable_a=False, enable_b=False, enable_c=False, enable_d=True),
    }
    for name, kw in flags.items():
        trades, _ = replay(bars, h1, replace(p, **kw), warmup=warmup)
        out["engine_alone"][name] = _metrics(trades, p.account_equity_usd)

    combined, _ = replay(bars, h1, p, warmup=warmup)
    out["combined"] = _metrics(combined, p.account_equity_usd)

    ablations = {
        "without_session_gate": dict(session_gate_enabled=False),
        "without_htf_alignment": dict(require_htf_alignment=False),
        "without_cost_gate": dict(max_cost_to_risk_fraction=10.0),
        "without_net_rr_gate": dict(min_reward_risk=0.0),
        "without_session_engine_D": dict(enable_d=False),
        "without_sweep_engine_B": dict(enable_b=False),
    }
    for name, kw in ablations.items():
        trades, _ = replay(bars, h1, replace(p, **kw), warmup=warmup)
        out["ablation"][name] = _metrics(trades, p.account_equity_usd)

    for label, spread in (("base_0.1pip", 0.1), ("wide_0.3pip", 0.3), ("stress_0.8pip", 0.8)):
        trades, _ = replay(bars, h1, p, warmup=warmup, spread_pips=spread)
        out["robustness"][label] = _metrics(trades, p.account_equity_usd)

    third = len(bars) // 3
    if third > warmup + 50:
        for label, start, end in (("dev", 0, third), ("validation", third, 2 * third),
                                  ("out_of_sample", 2 * third, len(bars))):
            segment = bars[start:end]
            if len(segment) > warmup:
                trades, _ = replay(segment, to_h1(segment), p)
                out["split"][label] = _metrics(trades, p.account_equity_usd)
    return out


# ---------------------------------------------------------------------------------------------
# Report / CLI
# ---------------------------------------------------------------------------------------------
def report(bars: List[Bar], h1: List[Bar], source: str, p: EurParams, stages: bool,
           spread: Optional[float] = None) -> Dict[str, object]:
    warmup = min(p.min_bars, max(30, len(bars) // 3))
    trades, decisions = replay(bars, h1, p, warmup=warmup, spread_pips=spread)
    engine_states: Dict[str, Dict[str, int]] = {e: {} for e in ENGINE_IDS}
    for d in decisions:
        for c in d.engines:
            engine_states[c.engine][c.state] = engine_states[c.engine].get(c.state, 0) + 1
    reason_counts: Dict[str, int] = {}
    for d in decisions:
        for reason in d.no_trade_reasons:
            head = reason.split(";")[0][:80]
            reason_counts[head] = reason_counts.get(head, 0) + 1
    sessions: Dict[str, int] = {}
    for d in decisions:
        sessions[d.session] = sessions.get(d.session, 0) + 1
    payload: Dict[str, object] = {
        "source": source,
        "synthetic_warning": (
            "SYNTHETIC FIXTURE — metrics describe the code path, not the euro. Use --csv for any claim."
            if source.startswith("synthetic") else None),
        "bars": len(bars), "decisions": len(decisions),
        "session_mix": sessions,
        "decision_counts": {
            "entries": sum(1 for d in decisions if d.action != "NO_TRADE"),
            "conflicts": sum(1 for d in decisions if d.conflicts),
            "rollover_windows": sum(1 for d in decisions if d.rollover),
        },
        "engine_states": engine_states,
        "no_trade_reason_counts": dict(sorted(reason_counts.items(), key=lambda kv: -kv[1])[:12]),
        "metrics": summarize(trades, p.account_equity_usd),
        "cost_model": {
            "spread_pips": spread if spread is not None else (p.spread_pips_override or 0.1),
            "commission_per_unit_per_side": p.commission_per_unit,
            "slippage_pips_per_side": p.slippage_pips,
            "round_trip_cost_pips": round(round_trip_cost_pips(1.0850, spread if spread is not None
                                                               else p.spread_pips_override, p), 4),
            "swap": "not estimated: the app has no financing source, so 0.0 is reported and labelled",
        },
        "note": "Win rate is an observed frequency on these bars, not a probability of future success.",
    }
    if stages:
        payload["stages"] = run_stages(bars, h1, p)
    return payload


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="EUR-ARES offline replay (paper only)")
    parser.add_argument("--csv", help="EUR/USD M5 OHLCV CSV (time,open,high,low,close[,volume])")
    parser.add_argument("--h1-csv", help="optional H1 CSV; without it H1 is aggregated from M5")
    parser.add_argument("--bars", type=int, default=3000, help="synthetic fixture length")
    parser.add_argument("--noise-pips", type=float, default=1.2,
                        help="synthetic per-bar amplitude in pips (ignored with --csv)")
    parser.add_argument("--spread", type=float, default=None, help="override spread in pips")
    parser.add_argument("--json", help="write the full report to this path")
    parser.add_argument("--stages", action="store_true", help="run stages A–E")
    parser.add_argument("--exit-model", default="FIXED_RR",
                        choices=["FIXED_RR", "TRAIL_AFTER_1R", "PARTIAL_HALF"],
                        help="exit variant to compare against the fixed-RR baseline")
    args = parser.parse_args(argv)

    if args.csv:
        bars = load_csv_bars(Path(args.csv))
        if len(bars) < 300:
            raise SystemExit(f"{args.csv} has {len(bars)} bars; EUR-ARES needs at least 300 M5 bars")
        source = f"csv:{Path(args.csv).name}"
        h1 = load_csv_bars(Path(args.h1_csv)) if args.h1_csv else to_h1(bars)
    else:
        bars = generate_synthetic_fixture(args.bars, noise_pips=args.noise_pips)
        source = "synthetic"
        h1 = to_h1(bars)
    params = replace(EurParams(), exit_model=args.exit_model)
    if args.spread is not None:
        params = replace(params, spread_pips_override=args.spread)
    payload = report(bars, h1, source, params, args.stages, args.spread)
    text = json.dumps(payload, indent=2, ensure_ascii=False)
    if args.json:
        Path(args.json).write_text(text + "\n", encoding="utf-8")
    metrics = payload["metrics"]
    print(f"source: {source} · bars: {payload['bars']} · decisions: {payload['decisions']}")
    if payload["synthetic_warning"]:
        print(payload["synthetic_warning"])
    print(f"cost model: {payload['cost_model']['round_trip_cost_pips']} pips round trip "
          f"(swap: not estimated)")
    print("trades {trade_count} · win {win_rate_pct}% · expectancy {expectancy_r}R · "
          "net {net_pnl_usd} USD · PF {profit_factor} · maxDD {max_drawdown_pct}% · "
          "avg stop {avg_stop_pips}p".format(
              **{**metrics, "profit_factor": metrics["profit_factor"] if metrics["profit_factor"]
                 is not None else "n/a (no losing trade)"}))
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
